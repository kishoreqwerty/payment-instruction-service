package com.kishore.payments.gateway.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.gateway.dispatch.DispatchRecordEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * §7's "double-payment trial" -- the project's headline claim, acceptance
 * criterion 1. Drives all five outcomes the branch table actually produces
 * at real volume, not just the two conservative ones: settling on the first
 * dispatch, redispatching once and settling, resolving a transient UNKNOWN
 * without ever redispatching, and reaching INVESTIGATION by two different
 * routes (an inconclusive query window, and the redispatch cap). Earlier
 * versions of this trial drove every {@code recordBeforeTimeout: false}
 * trial into a permanently-undeliverable rail, so the redispatch path --
 * the one the phase exists for -- never actually ran. That was a real gap
 * in this test's design, not a scenario misconfiguration: closing it
 * needed a rail-simulator capability ({@code deliveryAttemptAtLeast}) that
 * did not exist yet, described below.
 *
 * <h2>How each bucket is driven</h2>
 *
 * <p>Bucket assignment is content-based (currency), same reasoning as
 * before -- {@code everyNth} is a whole-rail counter and cannot survive
 * many trials interleaving on one rail. Distinguishing an original delivery
 * from its redispatch, though, needs something {@code everyNth} genuinely
 * cannot do at all: a redispatch carries the exact same UETR and content as
 * its original by design (.notes/ARCHITECTURE.md §6.4), so no content-based
 * criterion can tell them apart either. {@code deliveryAttemptAtLeast}
 * (new, this test) matches on the 1-based count of times *this exact UETR*
 * has been POSTed to a rail, independent of every other trial's own
 * requests interleaving with it -- "fail on delivery 1, succeed from
 * delivery 2 on" is now expressible.
 *
 * <p>Three {@code statusQueryBehaviour} values are needed simultaneously
 * (NORMAL, UNKNOWN_THEN_KNOWN, ALWAYS_ERROR) and that setting is scenario-
 * level, not per-rule (a status query can arrive for a UETR with no inbound
 * payment left to match rules against). With exactly three rail ids
 * available, each rail is dedicated to one behaviour:
 *
 * <ul>
 *   <li>{@code FEDWIRE} (NORMAL): buckets 1, 2 and 5, told apart by currency
 *       and, for bucket 2, {@code deliveryAttemptAtLeast}.
 *   <li>{@code SEPA} (UNKNOWN_THEN_KNOWN, count 1): bucket 3 only.
 *   <li>{@code ACH_EQUIV} (ALWAYS_ERROR): bucket 4 only.
 * </ul>
 *
 * <p>Every bucket uses {@code DROP}, never {@code TIMEOUT}: this
 * simulator's {@code DROP} now schedules a confirmation exactly when the
 * payment was actually recorded ({@code recordBeforeTimeout: true} -- see
 * {@code RailController}'s DROP case), which used not to be true and made
 * an earlier version of this trial need {@code TIMEOUT}'s far more
 * expensive client-side block (the routed-events topic in this test
 * environment has one partition, so every dispatch is processed by a
 * single consumer thread, and a {@code TIMEOUT} trial blocks that thread
 * for the full 800ms client read-timeout). With {@code DROP} everywhere,
 * this trial runs in well under a minute.
 *
 * <p>Running bucket 2 at its real ~250-trial volume, rather than driving
 * only a handful of redispatches, is itself load-bearing: it is what
 * surfaced a genuine race between {@code DispatchOrchestrator}'s own
 * ACK-driven {@code ROUTED->SENT} transition and the rail's independently
 * scheduled pacs.002 confirmation, both stemming from the same accepted
 * dispatch with no ordering guarantee between them. At low volume the
 * transition reliably wins; under a near-simultaneous 250-trial burst it
 * sometimes did not, and {@code CallbackCorrelationService} used to treat
 * that as a permanent no-op -- rail-simulator deliberately never retries a
 * status callback (see its {@code CallbackSender}), so nothing else in the
 * system would have recovered it either. Fixed by {@code
 * CallbackCorrelationService} retrying briefly (see its {@code
 * handleStatus} javadoc) rather than giving up on the first read.
 */
class DoublePaymentTrialTest extends AbstractAmbiguityResolverIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DoublePaymentTrialTest.class);

    private record Bucket(String name, int count, String currency, String rail, InstructionState expectedState, int expectedDispatchRecords) {
    }

    private static final List<Bucket> BUCKETS = List.of(
            new Bucket("settled on first dispatch (no redispatch)", 200, "USD", "FEDWIRE", InstructionState.SETTLED, 1),
            new Bucket("redispatched once and settled", 250, "EUR", "FEDWIRE", InstructionState.SETTLED, 2),
            new Bucket("resolved without redispatch after a transient UNKNOWN", 200, "GBP", "SEPA", InstructionState.SENT, 1),
            new Bucket("reached investigation (inconclusive window)", 150, "JPY", "ACH_EQUIV", InstructionState.INVESTIGATION, 1),
            new Bucket("reached investigation (redispatch cap)", 200, "CHF", "FEDWIRE", InstructionState.INVESTIGATION, 2));

    private static final int TRIAL_COUNT = BUCKETS.stream().mapToInt(Bucket::count).sum();

    @DynamicPropertySource
    static void highThroughputProperties(DynamicPropertyRegistry registry) {
        // Enough reconciliation batch headroom to sweep 1,000 trials in a
        // handful of calls rather than thousands.
        registry.add("payments.gateway.reconciliation.batch-size", () -> TRIAL_COUNT * 2);
        // Reduced from the production default (3): the redispatch-cap
        // bucket only needs to demonstrate that a cap exists and is
        // honoured, not exercise the full production value -- production
        // stays at 3 (.notes/reports/PHASE-7-REPORT.md §5).
        registry.add("payments.gateway.reconciliation.max-redispatch-attempts", () -> 1);
    }

    @Test
    void oneThousandAmbiguousDispatchesCoverAllFiveResolutionsWithZeroDoublePayments() {
        assertThat(TRIAL_COUNT).isEqualTo(1000);

        loadRailScenario("FEDWIRE", """
                rail: trial-fedwire
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                statusCallbackUrl: "%s"
                rules:
                  - match:
                      currency: USD
                    recordBeforeTimeout: true
                    confirmation: ACSC
                    confirmationDelayMs: 0
                  - match:
                      currency: EUR
                      deliveryAttemptAtLeast: 2
                    acceptResponse: ACCEPT
                    confirmation: ACSC
                    confirmationDelayMs: 0
                """.formatted(gatewayCallbackUrl("FEDWIRE")));
        // GBP (bucket 3) and CHF (bucket 5) both fall through to FEDWIRE's
        // default -- CHF forever (bucket 5's whole point); GBP is never
        // dispatched to FEDWIRE at all, it goes to SEPA below.

        loadRailScenario("SEPA", """
                rail: trial-sepa
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: true
                statusCallbackUrl: "http://localhost:1"
                statusQueryBehaviour: UNKNOWN_THEN_KNOWN
                statusQueryUnknownCount: 1
                rules: []
                """);

        loadRailScenario("ACH_EQUIV", """
                rail: trial-ach
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                statusCallbackUrl: "http://localhost:1"
                statusQueryBehaviour: ALWAYS_ERROR
                rules: []
                """);

        // Sourced from Postgres itself, not the JVM's clock: every trial's
        // own created_at is DEFAULT now() from that same database, and any
        // clock skew between the JVM host and the Postgres container --
        // even a few milliseconds -- could put trialStart *after* the
        // created_at of a row inserted a moment later on the wall clock the
        // trial actually ran against, silently dropping that trial from
        // every created_at >= trialStart query below.
        OffsetDateTime trialStart = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);

        Map<String, List<PaymentInstructionEntity>> trialsByBucket = new LinkedHashMap<>();
        List<PaymentInstructionEntity> allTrials = new ArrayList<>(TRIAL_COUNT);
        int seeded = 0;
        for (Bucket bucket : BUCKETS) {
            List<PaymentInstructionEntity> bucketTrials = new ArrayList<>(bucket.count());
            for (int i = 0; i < bucket.count(); i++) {
                PaymentInstructionEntity trial = seedRoutedInstruction(
                        new BigDecimal("10.00").add(new BigDecimal(seeded % 500)), bucket.currency(), bucket.rail());
                bucketTrials.add(trial);
                allTrials.add(trial);
                seeded++;
            }
            trialsByBucket.put(bucket.name(), bucketTrials);
            log.info("Seeded {} trials for bucket '{}' ({}/{})", bucket.count(), bucket.name(), bucket.currency(), bucket.rail());
        }
        assertThat(allTrials).hasSize(TRIAL_COUNT);

        long t0 = System.currentTimeMillis();
        drainOutbox();
        awaitCondition(Duration.ofSeconds(120), () -> countInState(trialStart, "ROUTED") == 0);
        log.info("Initial dispatch phase took {} ms; ROUTED remaining: {}", System.currentTimeMillis() - t0, countInState(trialStart, "ROUTED"));

        // Bucket 1's confirmation is scheduled asynchronously (even at
        // confirmationDelayMs: 0, it still queues onto a scheduler thread,
        // not inline) -- wait for it to actually land before reconciling,
        // the same reasoning as the USD case in earlier versions of this
        // trial, just against DROP's now-much-shorter race window instead
        // of TIMEOUT's 800ms one.
        List<PaymentInstructionEntity> settlesFirstDispatch = trialsByBucket.get(BUCKETS.get(0).name());
        awaitCondition(Duration.ofSeconds(30), () -> settlesFirstDispatch.stream().allMatch(
                t -> "ACSC".equals(railSimulatorGetPayment(selectedRailOf(t), t.getUetr().toString()).getBody().railStatus())));

        // Bucket 5 (redispatch cap) needs two full two-consecutive-UNKNOWN
        // episodes (max-redispatch-attempts: 1 above) to reach the cap;
        // bucket 4 (ACH_EQUIV, ALWAYS_ERROR) needs inconclusive-window
        // (3, from the test base) consecutive query failures. Both fit
        // inside three rounds of two reconcile() calls each; buckets 1-3
        // resolve within round 1 and are no-ops afterward.
        for (int round = 1; round <= 3; round++) {
            long tRound = System.currentTimeMillis();
            ambiguityResolver.reconcile();
            ambiguityResolver.reconcile();
            drainOutbox();
            awaitCondition(Duration.ofSeconds(60), () -> countInState(trialStart, "ROUTED") == 0);
            log.info("Round {} took {} ms; ROUTED remaining: {}, SENT_UNCONFIRMED remaining: {}",
                    round, System.currentTimeMillis() - tRound, countInState(trialStart, "ROUTED"), countInState(trialStart, "SENT_UNCONFIRMED"));
        }
        awaitCondition(Duration.ofSeconds(60), () -> countInState(trialStart, "SENT_UNCONFIRMED") == 0);

        // Bucket 2's redispatch is a genuinely normal ACCEPT, not an
        // ambiguous one -- it settles through the real pacs.002 callback
        // path (CallbackCorrelationService), not through reconciliation,
        // so it needs its own wait independent of the SENT_UNCONFIRMED
        // drain above. 250 real, near-simultaneous HTTP callbacks (each a
        // synchronous request against settlement-gateway's own Tomcat
        // thread pool) queue up under this trial's burst in a way none of
        // this test's other buckets do. This used to strand a real fraction
        // of them permanently: the confirmation can legitimately arrive
        // before DispatchOrchestrator's own ACK-driven ROUTED->SENT
        // transition commits (two independently scheduled paths racing off
        // the same accepted dispatch), and CallbackCorrelationService used
        // to discard that as a permanent no-op rather than retry --
        // CallbackCorrelationService#handleStatus now retries for up to
        // 500ms per callback, so a generous but not enormous budget here is
        // about queueing delay alone, not the race itself.
        List<PaymentInstructionEntity> redispatchedOnceSettled = trialsByBucket.get(BUCKETS.get(1).name());
        awaitCondition(Duration.ofSeconds(30), () -> redispatchedOnceSettled.stream().allMatch(
                t -> instructions.findById(t.getInstructionId()).orElseThrow().getState() == InstructionState.SETTLED));

        Map<String, Long> distribution = stateDistribution(trialStart);
        log.info("Double-payment trial resolution distribution across {} trials: {}", TRIAL_COUNT, distribution);

        assertThat(countInState(trialStart, "SENT_UNCONFIRMED"))
                .as("no trial instruction may remain at SENT_UNCONFIRMED once reconciliation has drained")
                .isZero();
        assertThat(countInState(trialStart, "ROUTED"))
                .as("no trial instruction may remain at ROUTED once dispatch has drained")
                .isZero();

        // Every bucket resolved to exactly the state its own mechanism
        // promises, and produced exactly the dispatch_record count that
        // implies -- the precise, per-bucket claim behind the aggregate
        // distribution above. Only once every one of these has actually
        // passed does trialsByBucket's own bucket sizes double as accurate
        // per-resolution counts for the summary sentence below.
        for (Bucket bucket : BUCKETS) {
            for (PaymentInstructionEntity trial : trialsByBucket.get(bucket.name())) {
                InstructionState state = instructions.findById(trial.getInstructionId()).orElseThrow().getState();
                assertThat(state).as("bucket '%s', trial %s", bucket.name(), trial.getInstructionId()).isEqualTo(bucket.expectedState());
                List<DispatchRecordEntity> records = dispatchRecordsFor(trial.getUetr());
                assertThat(records).as("bucket '%s', trial %s dispatch_record count", bucket.name(), trial.getInstructionId())
                        .hasSize(bucket.expectedDispatchRecords());
            }
        }

        log.info("As a sentence: {} trials: {} settled on first dispatch, {} redispatched once and settled, "
                        + "{} resolved without redispatch after a transient UNKNOWN, {} reached investigation, zero double payments.",
                TRIAL_COUNT,
                trialsByBucket.get(BUCKETS.get(0).name()).size(),
                trialsByBucket.get(BUCKETS.get(1).name()).size(),
                trialsByBucket.get(BUCKETS.get(2).name()).size(),
                trialsByBucket.get(BUCKETS.get(3).name()).size() + trialsByBucket.get(BUCKETS.get(4).name()).size());

        // The double-payment claim, precisely: before the redispatch
        // bucket's original delivery is ever simulated arriving late, the
        // rail must show zero duplicates for every trial in every bucket --
        // including bucket 2, whose real automated flow (original silently
        // lost, redispatch succeeds) never delivers the same UETR twice on
        // its own.
        for (PaymentInstructionEntity trial : allTrials) {
            int duplicateCount = railSimulatorReceivedOne(selectedRailOf(trial), trial.getUetr().toString()).getBody().duplicateCount();
            assertThat(duplicateCount).as("trial %s must show no duplicate deliveries before its original is ever replayed", trial.getInstructionId()).isZero();
        }

        // Now prove duplicate recognition actually engages, not just that
        // it was never exercised: replay bucket 2's stored *original*
        // pacs.008 (dispatch_record attempt_no 1's exact bytes -- the
        // delivery that was genuinely lost) directly at the rail, as if it
        // had shown up late after all. deliveryAttemptAtLeast: 2 still
        // matches (this is now the 3rd delivery of this UETR), so the rail
        // accepts it -- and must recognise it as a duplicate of the
        // already-settled payment, not a second, distinct one.
        for (PaymentInstructionEntity trial : redispatchedOnceSettled) {
            String rail = selectedRailOf(trial);
            byte[] originalPayload = dispatchRecordsFor(trial.getUetr()).get(0).getRequestPayload();
            railSimulatorPostPayment(rail, originalPayload);
            int duplicateCount = railSimulatorReceivedOne(rail, trial.getUetr().toString()).getBody().duplicateCount();
            assertThat(duplicateCount)
                    .as("trial %s: the redispatched original, arriving late, must be recognised as a duplicate of the settled payment", trial.getInstructionId())
                    .isEqualTo(1);
        }
    }

    private void drainOutbox() {
        for (int i = 0; i < 30; i++) {
            outboxPublisher.publishBatch();
        }
    }

    private int countInState(OffsetDateTime since, String state) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core.payment_instruction WHERE created_at >= ? AND state = ?::core.instruction_state",
                Integer.class, since, state);
        return count == null ? 0 : count;
    }

    private Map<String, Long> stateDistribution(OffsetDateTime since) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(
                "SELECT state, COUNT(*) AS cnt FROM core.payment_instruction WHERE created_at >= ? GROUP BY state",
                rs -> {
                    counts.put(rs.getString("state"), rs.getLong("cnt"));
                },
                since);
        return counts;
    }

    private String selectedRailOf(PaymentInstructionEntity trial) {
        return instructions.findById(trial.getInstructionId()).orElseThrow().getSelectedRail();
    }
}
