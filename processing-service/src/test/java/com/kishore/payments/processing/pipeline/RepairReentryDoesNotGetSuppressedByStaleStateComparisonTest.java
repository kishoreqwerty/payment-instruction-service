package com.kishore.payments.processing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.ActorType;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.TransitionResult;
import com.kishore.payments.processing.AbstractProcessingIntegrationTest;
import com.kishore.payments.processing.event.InstructionStageEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression test for a bug in the original idempotency check: comparing
 * the event's {@code to_state} against the instruction's current state
 * cannot tell a legitimate repair re-entry apart from a stale redelivery,
 * because repair re-enters at VALIDATED -- a state the instruction has
 * already occupied once before, earlier in its own lifecycle. Comparing
 * {@code sequence_no} instead fixes this: it is monotonic per instruction by
 * construction and never repeats, so "already handled" and "state visited
 * before" stop being the same question.
 *
 * <p>This drives the Phase 8 shape directly -- exception-service doesn't
 * exist yet, so the REPAIRED -> VALIDATED transition and its event are
 * produced by hand here, exactly as exception-service will produce them --
 * so that this regression is caught now rather than four phases from now
 * when exception-service is actually built on top of this idempotency
 * check.
 */
class RepairReentryDoesNotGetSuppressedByStaleStateComparisonTest extends AbstractProcessingIntegrationTest {

    @Autowired
    private InstructionStateWriter stateWriter;

    @Autowired
    private OutboxWriter outboxWriter;

    @Test
    void aRepairedInstructionReenteringAtValidatedIsProcessedNotSuppressed() {
        // An invalid IBAN drives this to EXCEPTION via the real validation
        // path -- no need to fabricate the failure by hand.
        PaymentInstructionEntity instruction = badIbanEurInstruction(clock);
        seedReceived(instruction);
        outboxPublisher.publishBatch();
        InstructionState afterValidation = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(afterValidation).isEqualTo(InstructionState.EXCEPTION);

        // "Repair": EXCEPTION -> REPAIRED -> VALIDATED, driven by hand
        // exactly as exception-service (Phase 8) will drive it. This
        // instruction was already VALIDATED once before (a state it has
        // already occupied), now under a strictly higher sequence_no.
        stateWriter.transition(instruction.getInstructionId(), InstructionState.REPAIRED, ActorType.OPERATOR, "test-operator", null, "repaired for test");
        TransitionResult revalidated = stateWriter.transition(
                instruction.getInstructionId(), InstructionState.VALIDATED, ActorType.OPERATOR, "test-operator", null, "repaired for test");

        publishValidatedEvent(instruction, revalidated);
        outboxPublisher.publishBatch();

        InstructionState finalState = awaitState(instruction.getInstructionId(), Duration.ofSeconds(30));
        assertThat(finalState)
                .as("the repair re-entry event must be processed by EnrichmentConsumer, not discarded as a stale redelivery of the original VALIDATED event")
                .isEqualTo(InstructionState.ROUTED);
    }

    /** German IBAN with corrupted check digits -- fails DebtorIbanFormatRule (AC01) and nothing else, so this reaches EXCEPTION via exactly one violation. */
    private static PaymentInstructionEntity badIbanEurInstruction(java.time.Clock clock) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Debtor",
                "DE00370400440532013000",
                "DEUTDEFFXXX",
                "Creditor",
                "FR1420041010050500013M02606",
                "DEUTDEFFXXX",
                new BigDecimal("500.00"),
                "EUR",
                null,
                LocalDate.now(clock));
    }

    private void publishValidatedEvent(PaymentInstructionEntity instruction, TransitionResult result) {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        InstructionStageEvent event = new InstructionStageEvent(
                instruction.getInstructionId(),
                instruction.getUetr(),
                instruction.getEndToEndId(),
                InstructionState.VALIDATED,
                result.sequenceNo(),
                occurredAt,
                instruction.getAmount(),
                instruction.getCurrency(),
                instruction.getDebtorAgentBic(),
                instruction.getCreditorAgentBic(),
                instruction.getRequestedExecDate(),
                InstructionStageEvent.CURRENT_VERSION);
        outboxWriter.write(new OutboxMessage(
                instruction.getInstructionId(),
                "payments.validated",
                instruction.getInstructionId().toString(),
                OutboxHeaders.of("InstructionValidated", InstructionStageEvent.CURRENT_VERSION, occurredAt, null),
                event));
    }
}
