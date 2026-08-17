package com.kishore.payments.gateway.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.gateway.callback.CallbackCorrelationService;
import com.kishore.payments.gateway.callback.InboundConfirmation;
import com.kishore.payments.gateway.dispatch.DispatchRecordEntity;
import com.kishore.payments.gateway.dispatch.DispatchState;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The named scenarios from the Phase 7 brief's §7 test list, each driven
 * through the real embedded rail-simulator exactly like Phase 6's own
 * integration tests -- {@code ambiguityResolver.reconcile()} is called
 * directly rather than waiting on its real (here, 1-hour) schedule, the
 * same determinism trick {@code outboxPublisher.publishBatch()} already
 * uses throughout this suite.
 */
class AmbiguityResolverScenarioTest extends AbstractAmbiguityResolverIntegrationTest {

    @Autowired
    private CallbackCorrelationService callbackCorrelationService;

    @Test
    void knownWithNullRailStatusResolvesToSentWithNoRedispatch() {
        loadRailScenario("FEDWIRE", """
                rail: t1-known-null-status
                default:
                  acceptResponse: TIMEOUT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  timeoutHoldMs: 3000
                  recordBeforeTimeout: true
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("100.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);

        ambiguityResolver.reconcile();

        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SENT);
        List<DispatchRecordEntity> records = dispatchRecordsFor(instruction.getUetr());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getAttemptNo()).isEqualTo(1);
    }

    @Test
    void knownWithAcscResolvesToSentThenSettled() {
        loadRailScenario("FEDWIRE", """
                rail: t2-known-acsc
                default:
                  acceptResponse: TIMEOUT
                  acceptDelayMs: 0
                  confirmation: ACSC
                  confirmationDelayMs: 0
                  timeoutHoldMs: 3000
                  recordBeforeTimeout: true
                statusCallbackUrl: "http://localhost:1"
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("200.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);
        // confirmationDelayMs is 0, but the rail decides asynchronously --
        // give it a moment to actually flip its internal railStatus before
        // reconciling, same as HappyPathAndCallbackIntegrationTest does for
        // the corresponding real-callback case.
        awaitCondition(Duration.ofSeconds(5), () -> "ACSC".equals(currentRailStatus("FEDWIRE", instruction.getUetr())));

        ambiguityResolver.reconcile();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SETTLED);
        assertThat(reached).isEqualTo(InstructionState.SETTLED);
        assertThat(dispatchRecordsFor(instruction.getUetr())).hasSize(1);
    }

    @Test
    void knownWithRjctResolvesToSentThenExceptionCarryingReasonCode() {
        loadRailScenario("FEDWIRE", """
                rail: t3-known-rjct
                default:
                  acceptResponse: TIMEOUT
                  acceptDelayMs: 0
                  confirmation: RJCT
                  confirmationDelayMs: 0
                  timeoutHoldMs: 3000
                  recordBeforeTimeout: true
                  rejectReasonCode: AC04
                statusCallbackUrl: "http://localhost:1"
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("300.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);
        awaitCondition(Duration.ofSeconds(5), () -> "RJCT".equals(currentRailStatus("FEDWIRE", instruction.getUetr())));

        ambiguityResolver.reconcile();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.EXCEPTION);
        assertThat(reached).isEqualTo(InstructionState.EXCEPTION);
        assertThat(latestReasonCode(instruction.getInstructionId())).isEqualTo("AC04");
    }

    @Test
    void twoConsecutiveUnknownRedispatchesSameUetrAndEventuallySettles() {
        loadRailScenario("FEDWIRE", """
                rail: t4-two-unknown-redispatch
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                statusCallbackUrl: "%s"
                rules:
                  - match:
                      everyNth: 2
                    acceptResponse: ACCEPT
                    confirmation: ACSC
                    confirmationDelayMs: 0
                """.formatted(gatewayCallbackUrl("FEDWIRE")));

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("400.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);

        ambiguityResolver.reconcile(); // 1st UNKNOWN -- no action
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SENT_UNCONFIRMED);
        assertThat(dispatchRecordsFor(instruction.getUetr())).hasSize(1);

        ambiguityResolver.reconcile(); // 2nd consecutive UNKNOWN -- redispatch
        outboxPublisher.publishBatch();

        InstructionState reached = awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SETTLED);
        assertThat(reached).isEqualTo(InstructionState.SETTLED);

        List<DispatchRecordEntity> records = dispatchRecordsFor(instruction.getUetr());
        assertThat(records).hasSize(2);
        assertThat(records).extracting(DispatchRecordEntity::getAttemptNo).containsExactly(1, 2);
        assertThat(records).extracting(DispatchRecordEntity::getUetr).containsOnly(instruction.getUetr());
        assertThat(records.get(0).getDispatchState()).isEqualTo(DispatchState.TIMED_OUT);
        assertThat(records.get(1).getDispatchState()).isEqualTo(DispatchState.ACKNOWLEDGED);
    }

    /**
     * The rule the two-consecutive requirement exists for: a single UNKNOWN,
     * even followed immediately by a truthful KNOWN, must never have
     * triggered a redispatch in between. Fails under a single-observation
     * implementation.
     */
    @Test
    void singleUnknownThenKnownNeverRedispatches() {
        loadRailScenario("FEDWIRE", """
                rail: t5-unknown-then-known
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: true
                statusQueryBehaviour: UNKNOWN_THEN_KNOWN
                statusQueryUnknownCount: 1
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("500.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);

        ambiguityResolver.reconcile(); // answers UNKNOWN (1st query)
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SENT_UNCONFIRMED);
        assertThat(dispatchRecordsFor(instruction.getUetr())).hasSize(1);

        ambiguityResolver.reconcile(); // answers truthfully (2nd query): KNOWN
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SENT);
        assertThat(dispatchRecordsFor(instruction.getUetr())).hasSize(1);
    }

    @Test
    void alwaysErrorExhaustsInconclusiveWindowIntoInvestigation() {
        loadRailScenario("FEDWIRE", """
                rail: t6-always-error
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                statusQueryBehaviour: ALWAYS_ERROR
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("600.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);

        // Test base sets inconclusive-window to 3.
        ambiguityResolver.reconcile();
        ambiguityResolver.reconcile();
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SENT_UNCONFIRMED);

        ambiguityResolver.reconcile();
        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.INVESTIGATION);
    }

    @Test
    void redispatchCapStopsAtExactlyConfiguredAttemptsNotBeyond() {
        loadRailScenario("FEDWIRE", """
                rail: t7-redispatch-cap
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("700.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);

        // Test base sets max-redispatch-attempts to 3: three full
        // two-consecutive-UNKNOWN episodes, each ending in a real redispatch
        // that (via the DROP default rule, unchanged for every attempt)
        // lands back at SENT_UNCONFIRMED for the next episode. That's three
        // redispatches on top of the original attempt -- four dispatch_record
        // rows in total once the loop finishes.
        for (int episode = 1; episode <= 3; episode++) {
            ambiguityResolver.reconcile();
            ambiguityResolver.reconcile();
            outboxPublisher.publishBatch();
            awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);
        }
        assertThat(dispatchRecordsFor(instruction.getUetr())).hasSize(4);
        assertThat(reconciliationStates.findById(instruction.getInstructionId()).orElseThrow().getRedispatchCount()).isEqualTo(3);

        // A fourth episode's second consecutive UNKNOWN must go to
        // INVESTIGATION instead of a fourth redispatch -- no fifth
        // dispatch_record row.
        ambiguityResolver.reconcile();
        ambiguityResolver.reconcile();

        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.INVESTIGATION);
        assertThat(dispatchRecordsFor(instruction.getUetr())).hasSize(4);
    }

    @Test
    void concurrentCallbackAndReconciliationNeverDoubleProcess() throws Exception {
        loadRailScenario("FEDWIRE", """
                rail: t8-race
                default:
                  acceptResponse: TIMEOUT
                  acceptDelayMs: 0
                  confirmation: ACSC
                  confirmationDelayMs: 0
                  timeoutHoldMs: 3000
                  recordBeforeTimeout: true
                statusCallbackUrl: "http://localhost:1"
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("800.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), InstructionState.SENT_UNCONFIRMED);
        awaitCondition(Duration.ofSeconds(5), () -> "ACSC".equals(currentRailStatus("FEDWIRE", instruction.getUetr())));

        int eventsBefore = instructionEventCount(instruction.getInstructionId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> callbackRace = pool.submit(() -> {
                ready.countDown();
                awaitLatch(go);
                callbackCorrelationService.handleStatus("FEDWIRE", new InboundConfirmation(instruction.getUetr().toString(), "ACSC", null));
            });
            Future<?> reconcileRace = pool.submit(() -> {
                ready.countDown();
                awaitLatch(go);
                ambiguityResolver.reconcile();
            });
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            callbackRace.get(10, TimeUnit.SECONDS);
            reconcileRace.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertThat(instructions.findById(instruction.getInstructionId()).orElseThrow().getState()).isEqualTo(InstructionState.SETTLED);
        assertThat(dispatchRecordsFor(instruction.getUetr())).hasSize(1);
        // Exactly one SENT_UNCONFIRMED->SENT and one SENT->SETTLED transition
        // got through, regardless of which racer got there first -- not two
        // of either, which duplicate processing would have produced.
        assertThat(instructionEventCount(instruction.getInstructionId())).isEqualTo(eventsBefore + 2);
    }

    private String currentRailStatus(String rail, java.util.UUID uetr) {
        var body = railSimulatorGetPayment(rail, uetr.toString()).getBody();
        return body == null ? null : body.railStatus();
    }

    private String latestReasonCode(java.util.UUID instructionId) {
        return jdbc.queryForObject(
                "SELECT reason_code FROM core.instruction_event WHERE instruction_id = ? ORDER BY sequence_no DESC LIMIT 1",
                String.class, instructionId);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
