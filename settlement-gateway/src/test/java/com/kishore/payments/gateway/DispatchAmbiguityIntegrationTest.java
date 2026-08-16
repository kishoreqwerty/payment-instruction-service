package com.kishore.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.gateway.dispatch.DispatchRecordEntity;
import com.kishore.payments.gateway.dispatch.DispatchState;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Acceptance criteria 4 and 5: a timeout and a dropped connection are both ambiguous, both leave TIMED_OUT/SENT_UNCONFIRMED, neither retries. */
class DispatchAmbiguityIntegrationTest extends AbstractGatewayIntegrationTest {

    @Test
    void timeoutProducesSentUnconfirmedTimedOutDispatchRecordAndNoRetry() {
        loadRailScenario("SEPA", """
                rail: timeout-test
                default:
                  acceptResponse: TIMEOUT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  timeoutHoldMs: 3000
                  recordBeforeTimeout: false
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("300.00"), "EUR", "SEPA");
        outboxPublisher.publishBatch();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);
        assertThat(reached).isEqualTo(InstructionState.SENT_UNCONFIRMED);

        // No retry: exactly one dispatch_record, TIMED_OUT.
        List<DispatchRecordEntity> records = dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getDispatchState()).isEqualTo(DispatchState.TIMED_OUT);
        assertThat(records.get(0).getAttemptNo()).isEqualTo(1);

        // Stays SENT_UNCONFIRMED: nothing in this phase automatically moves it further.
        sleepBriefly();
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SENT_UNCONFIRMED);
    }

    @Test
    void dropProducesSentUnconfirmedTimedOutDispatchRecordAndNoRetry() {
        loadRailScenario("ACH_EQUIV", """
                rail: drop-test
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("450.00"), "USD", "ACH_EQUIV");
        outboxPublisher.publishBatch();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);
        assertThat(reached).isEqualTo(InstructionState.SENT_UNCONFIRMED);

        List<DispatchRecordEntity> records = dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getDispatchState()).isEqualTo(DispatchState.TIMED_OUT);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
