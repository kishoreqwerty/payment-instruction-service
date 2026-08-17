package com.kishore.payments.gateway.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.gateway.dispatch.DispatchRecordEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * §2 of the Phase 7 brief: "two replicas must never reconcile the same
 * instruction concurrently." {@link AmbiguityResolver} is a stateless
 * singleton, but calling {@code reconcile()} from two different threads at
 * once uses two different pooled JDBC connections -- each gets its own
 * transaction, and the database (via {@code pg_try_advisory_xact_lock}) is
 * the actual arbiter, exactly as it would be for two real gateway replica
 * processes. Thread identity plays no part in the locking, so this is a
 * faithful way to exercise the cross-replica guarantee without booting a
 * second Spring context.
 */
class AmbiguityResolverConcurrencyTest extends AbstractAmbiguityResolverIntegrationTest {

    @Test
    void twoConcurrentReplicasRedispatchExactlyOnceNotTwice() throws Exception {
        loadRailScenario("FEDWIRE", """
                rail: t10-two-replicas
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                rules: []
                """);

        PaymentInstructionEntity instruction = seedRoutedInstruction(new BigDecimal("1000.00"), "USD", "FEDWIRE");
        outboxPublisher.publishBatch();
        awaitState(instruction.getInstructionId(), Duration.ofSeconds(10), com.kishore.payments.core.state.InstructionState.SENT_UNCONFIRMED);

        // First UNKNOWN observation, single-threaded, so both replicas race
        // on the SAME second (redispatch-triggering) observation rather than
        // each independently registering a first one.
        ambiguityResolver.reconcile();
        assertThat(reconciliationStates.findById(instruction.getInstructionId()).orElseThrow().getConsecutiveUnknownCount()).isEqualTo(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> replicas = List.of(
                    pool.submit(() -> {
                        ready.countDown();
                        awaitLatch(go);
                        ambiguityResolver.reconcile();
                    }),
                    pool.submit(() -> {
                        ready.countDown();
                        awaitLatch(go);
                        ambiguityResolver.reconcile();
                    }));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> replica : replicas) {
                replica.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        outboxPublisher.publishBatch();
        awaitCondition(Duration.ofSeconds(10), () -> dispatchRecordsFor(instruction.getUetr()).size() >= 2);
        // Briefly settle: if a second replica somehow also redispatched, a
        // third dispatch attempt would show up shortly after the second.
        Thread.sleep(500);

        List<DispatchRecordEntity> records = dispatchRecordsFor(instruction.getUetr());
        assertThat(records).hasSize(2);
        assertThat(records).extracting(DispatchRecordEntity::getAttemptNo).containsExactly(1, 2);
        assertThat(reconciliationStates.findById(instruction.getInstructionId()).orElseThrow().getRedispatchCount()).isEqualTo(1);
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
