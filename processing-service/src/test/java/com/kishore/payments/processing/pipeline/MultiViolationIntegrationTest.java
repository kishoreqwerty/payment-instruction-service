package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Multiple validation failures in one message must produce exactly one exception, carrying all of them -- not one exception per rule. */
class MultiViolationIntegrationTest extends AbstractProcessingIntegrationTest {

    @Test
    void multipleValidationFailuresProduceOneExceptionCarryingAllOfThem() {
        PaymentInstructionEntity instruction = new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Debtor",
                "DE00370400440532013000", // corrupted IBAN -> AC01
                "DEUTDEFFXXX",
                "Creditor",
                "FR1420041010050500013M02606",
                "NOTABIC", // malformed BIC -> RC01
                new BigDecimal("500.00"),
                "EUR",
                null,
                LocalDate.now(clock).minusDays(1)); // past date -> no-code violation

        seedReceived(instruction);
        outboxPublisher.publishBatch();

        InstructionState finalState = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(finalState).isEqualTo(InstructionState.EXCEPTION);

        Integer exceptionEventCount = jdbc.queryForObject(
                "SELECT count(*) FROM core.instruction_event WHERE instruction_id = ? AND to_state = 'EXCEPTION'::core.instruction_state",
                Integer.class,
                instruction.getInstructionId());
        assertThat(exceptionEventCount).as("exactly one EXCEPTION transition, not one per violated rule").isEqualTo(1);

        Integer exceptionOutboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM core.outbox WHERE aggregate_id = ? AND topic = 'payments.exceptions'",
                Integer.class,
                instruction.getInstructionId());
        assertThat(exceptionOutboxRows).as("exactly one payments.exceptions event, not one per violated rule").isEqualTo(1);

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM core.outbox WHERE aggregate_id = ? AND topic = 'payments.exceptions'",
                String.class,
                instruction.getInstructionId());
        assertThat(payload).as("the single exception event must name every violation").contains("AC01").contains("RC01");
    }
}
