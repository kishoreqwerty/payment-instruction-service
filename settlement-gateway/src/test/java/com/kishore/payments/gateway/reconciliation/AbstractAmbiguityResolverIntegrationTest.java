package com.kishore.payments.gateway.reconciliation;

import com.kishore.payments.gateway.AbstractGatewayIntegrationTest;
import com.kishore.payments.gateway.dispatch.DispatchRecordEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Adds Phase 7 reconciliation config on top of {@link AbstractGatewayIntegrationTest}'s
 * containers and embedded rail-simulator. A distinct {@code
 * @DynamicPropertySource} method means a distinct Spring context (Spring
 * Test caches by exact property signature) -- worth it here because these
 * tests need a near-zero grace period and pending threshold so a freshly
 * ambiguous instruction is reconcilable the instant a test calls {@link
 * #ambiguityResolver}'s {@code reconcile()} directly, without waiting on
 * real wall-clock elapsed time. The real {@code POSTGRES}/{@code REDPANDA}
 * containers are still the same static singletons from the parent class, so
 * this costs one extra embedded rail-simulator + Spring context boot, not
 * another set of Testcontainers.
 */
public abstract class AbstractAmbiguityResolverIntegrationTest extends AbstractGatewayIntegrationTest {

    @DynamicPropertySource
    static void reconciliationProperties(DynamicPropertyRegistry registry) {
        // Never fires on its own during a test; every test drives reconcile()
        // directly for deterministic timing, exactly like outboxPublisher.publishBatch().
        registry.add("payments.gateway.reconciliation.interval", () -> "PT1H");
        registry.add("payments.gateway.reconciliation.grace-period", () -> "1ms");
        registry.add("payments.gateway.reconciliation.pending-threshold", () -> "1ms");
        // batch-size is deliberately NOT set here: it falls back to
        // application.yml's default (50), comfortably more than any of
        // these tests' own handful of candidates, and leaves the property
        // free for a subclass (DoublePaymentTrialTest) to set its own much
        // larger value without a second @DynamicPropertySource registration
        // for the same key silently losing to this one.
        registry.add("payments.gateway.reconciliation.consecutive-unknown-threshold", () -> 2);
        // Small and explicit, rather than the production default of 10, so
        // "window exhausted" tests don't need ten reconcile() calls to prove
        // the point.
        registry.add("payments.gateway.reconciliation.inconclusive-window", () -> 3);
        // max-redispatch-attempts is deliberately NOT set here: it falls
        // back to application.yml's default (3, the same value this used to
        // set explicitly), for the same reason batch-size is left alone --
        // it leaves the property free for a subclass to set its own value
        // without a losing second registration for the same key. (Spring
        // Test's @DynamicPropertySource collection resolves a duplicate key
        // in favour of the ancestor's registration, not the subclass's --
        // confirmed empirically while building DoublePaymentTrialTest,
        // which needs a smaller cap than every other test in this hierarchy.)
    }

    @Autowired
    protected AmbiguityResolver ambiguityResolver;

    @Autowired
    protected ReconciliationStateRepository reconciliationStates;

    /**
     * Backdates a real dispatch_record's sent_at, so a PENDING row produced
     * by the real pipeline (see {@code StuckPendingDispatchInvestigationTest},
     * which kills {@code RailDispatcher} mid-call the same way {@code
     * DispatchDurabilityIntegrationTest} does) is unambiguously stale under
     * the configured pending threshold regardless of how many milliseconds
     * actually elapsed during the test.
     */
    protected void backdateDispatchRecordSentAt(UUID uetr, java.time.Duration ago) {
        jdbc.update("UPDATE core.dispatch_record SET sent_at = ? WHERE uetr = ?", OffsetDateTime.now(clock).minus(ago), uetr);
    }

    protected List<DispatchRecordEntity> dispatchRecordsFor(UUID uetr) {
        return dispatchRecords.findByUetrOrderByAttemptNo(uetr);
    }

    protected int instructionEventCount(UUID instructionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM core.instruction_event WHERE instruction_id = ?", Integer.class, instructionId);
        return count == null ? 0 : count;
    }
}
