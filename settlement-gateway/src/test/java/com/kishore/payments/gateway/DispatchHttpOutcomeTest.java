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
import org.springframework.http.ResponseEntity;

/**
 * The two dispatch outcomes rail-simulator could not itself produce until
 * Phase 5 was extended for this: a genuine HTTP 4xx or 5xx from a
 * well-formed pacs.008 ({@code REJECT_4XX}/{@code ERROR_5XX}, see
 * .notes/reports/PHASE-5-REPORT.md). Previously driven by a test-local fake
 * rail; now driven by the real embedded simulator, closing the coupling gap
 * a same-session fake would otherwise leave (.notes/reports/PHASE-6-REPORT.md
 * section 6).
 */
class DispatchHttpOutcomeTest extends AbstractGatewayIntegrationTest {

    @Test
    void fourHundredRejectsImmediatelyWithNoRetry() {
        loadRailScenario("FEDWIRE", """
                rail: reject-4xx
                default:
                  acceptResponse: REJECT_4XX
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  rejectReasonCode: AC06
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("100.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.EXCEPTION);
        assertThat(reached).isEqualTo(InstructionState.EXCEPTION);

        List<DispatchRecordEntity> records = dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getDispatchState()).isEqualTo(DispatchState.FAILED);
        assertThat(records.get(0).getResponseStatus()).isEqualTo(400);
    }

    @Test
    void fiveHundredRetriesPerPolicyThenException() {
        loadRailScenario("SEPA", """
                rail: always-500
                default:
                  acceptResponse: ERROR_5XX
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("200.00"), "USD", "SEPA");
        outboxPublisher.publishBatch();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.EXCEPTION);
        assertThat(reached).isEqualTo(InstructionState.EXCEPTION);

        // This test's dynamic properties (see AbstractGatewayIntegrationTest)
        // cap max-attempts at 3, so a rail that never recovers still produces
        // exactly 3 dispatch_record rows, all FAILED with a real 500.
        List<DispatchRecordEntity> records = dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr());
        assertThat(records).hasSize(3);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.getDispatchState()).isEqualTo(DispatchState.FAILED);
            assertThat(r.getResponseStatus()).isEqualTo(500);
        });
    }

    @Test
    void fiveHundredThatRecoversStopsRetryingAndReachesSent() {
        // errorCount: 1 means the rail's first attempt for a given UETR
        // 500s and every attempt after that succeeds -- the retry loop's
        // second attempt is what actually reaches SENT, proving recovery
        // rather than just proving exhaustion.
        loadRailScenario("ACH_EQUIV", """
                rail: recovers-after-one
                default:
                  acceptResponse: ERROR_5XX
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  errorCount: 1
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("300.00"), "USD", "ACH_EQUIV");
        outboxPublisher.publishBatch();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT);
        assertThat(reached).isEqualTo(InstructionState.SENT);

        List<DispatchRecordEntity> records = dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr());
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getDispatchState()).isEqualTo(DispatchState.FAILED);
        assertThat(records.get(1).getDispatchState()).isEqualTo(DispatchState.ACKNOWLEDGED);
    }

    @Test
    void storedRequestPayloadMatchesWhatTheRailReceivedByteForByte() {
        loadRailScenario("FEDWIRE", """
                rail: fidelity-check
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);
        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("999.99"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();

        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT);

        List<DispatchRecordEntity> records = dispatchRecords.findByUetrOrderByAttemptNo(instruction.getUetr());
        assertThat(records).hasSize(1);

        // rail-simulator's test-only GET /received/{uetr}/raw returns the
        // exact bytes it received (added specifically so this comparison
        // could be genuinely byte-for-byte against the real dependency --
        // see .notes/reports/PHASE-5-REPORT.md and PHASE-6-REPORT.md §6 for
        // why a field-level check was not treated as good enough here).
        ResponseEntity<byte[]> rawReceived = railSimulatorReceivedRaw("FEDWIRE", instruction.getUetr().toString());
        assertThat(rawReceived.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(records.get(0).getRequestPayload()).isEqualTo(rawReceived.getBody());
    }
}
