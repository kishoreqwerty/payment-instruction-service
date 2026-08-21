package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.event.InstructionRepairedEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The real Phase 8 wiring, complementing {@link
 * RepairReentryDoesNotGetSuppressedByStaleStateComparisonTest}: that test
 * proves the sequence_no-vs-state idempotency check itself, hand-driven
 * against payments.validated since exception-service didn't exist yet when
 * it was written. Now that it does, this test drives the actual topic and
 * event exception-service publishes -- payments.repaired, {@link
 * InstructionRepairedEvent} -- through {@code ValidationConsumer}'s new
 * {@code onRepaired} listener, proving the two services' wiring actually
 * agrees on shape and topic name, not just that the underlying idempotency
 * logic is sound in isolation.
 */
class RepairedEventReentryIntegrationTest extends AbstractProcessingIntegrationTest {

    @Autowired
    private InstructionStateWriter stateWriter;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aRepairedEventOnItsRealTopicReenteringAtValidatedProceedsToRouted() {
        PaymentInstructionEntity instruction = badIbanEurInstruction(clock);
        seedReceived(instruction);
        outboxPublisher.publishBatch();
        InstructionState afterValidation = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(afterValidation).isEqualTo(InstructionState.EXCEPTION);

        // exception-service's own repair-apply fixes the field and
        // transitions only as far as EXCEPTION -> REPAIRED -- reproduced
        // here by hand for the same reason exception-service isn't
        // embedded in this module's test classpath: this test's job is to
        // prove processing-service's own reaction to the topic and event
        // shape (specifically that ValidationConsumer's onRepaired listener
        // performs REPAIRED -> VALIDATED itself), not to re-run
        // exception-service's own already-tested repair logic. Stopping at
        // REPAIRED here, not VALIDATED, matters: the first version of this
        // test drove both transitions by hand, which left
        // ValidationConsumer attempting an illegal VALIDATED -> VALIDATED
        // self-transition when it ran -- silently discarded as a benign
        // race and stalling the instruction at VALIDATED forever. See
        // ExceptionCaseService's own class javadoc for the full account.
        jdbc.update("UPDATE core.payment_instruction SET debtor_account = ? WHERE instruction_id = ?", "DE89370400440532013000",
                instruction.getInstructionId());

        TransitionResult repaired =
                stateWriter.transition(instruction.getInstructionId(), InstructionState.REPAIRED, ActorType.OPERATOR, "test-operator", null,
                        "repaired for test");

        publishRepairedEvent(instruction, repaired);
        outboxPublisher.publishBatch();

        InstructionState finalState = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(finalState)
                .as("payments.repaired must drive ValidationConsumer's onRepaired listener through re-validation, enrichment and routing")
                .isEqualTo(InstructionState.ROUTED);
    }

    /** German IBAN with corrupted check digits -- fails DebtorIbanFormatRule (AC01) and nothing else, so this reaches EXCEPTION via exactly one violation. */
    private static PaymentInstructionEntity badIbanEurInstruction(java.time.Clock clock) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "E2E-" + UUID.randomUUID().toString().substring(0, 8), null,
                "Debtor", "DE00370400440532013000", "DEUTDEFFXXX", "Creditor", "FR1420041010050500013M02606", "DEUTDEFFXXX",
                new BigDecimal("500.00"), "EUR", null, LocalDate.now(clock));
    }

    private void publishRepairedEvent(PaymentInstructionEntity instruction, TransitionResult result) {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        InstructionRepairedEvent event = new InstructionRepairedEvent(
                instruction.getInstructionId(), instruction.getUetr(), instruction.getEndToEndId(), UUID.randomUUID(), result.sequenceNo(),
                occurredAt, InstructionRepairedEvent.CURRENT_VERSION);
        outboxWriter.write(new OutboxMessage(
                instruction.getInstructionId(), "payments.repaired", instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionRepaired", InstructionRepairedEvent.CURRENT_VERSION, occurredAt, null), event));
    }
}
