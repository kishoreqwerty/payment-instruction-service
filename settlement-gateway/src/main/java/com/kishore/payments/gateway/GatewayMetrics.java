package com.kishore.payments.gateway;

import com.kishore.payments.core.state.InstructionState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every labelled counter is pre-registered at zero for every known
 * combination at construction time -- the same reasoning as {@code
 * OutboxMetrics}: a counter nobody has incremented does not appear in a
 * scrape, which silently disables any alert built on it. {@code
 * payment_dispatch_ambiguous_total} is the one that should page a human --
 * any increment means an instruction is in a state the system cannot
 * resolve alone (.notes/ARCHITECTURE.md §6.4).
 *
 * <p>{@code payment_dispatch_ambiguous_total{rail}} (Phase 6) and {@code
 * payment_ambiguity_resolution_total{rail,resolution}} (Phase 7) are two
 * deliberately distinct metrics, not one relabelled: the first counts the
 * moment a dispatch attempt itself comes back ambiguous (a {@code
 * DispatchOrchestrator} concern, at dispatch time); the second counts how
 * {@code AmbiguityResolver} eventually resolved that ambiguity, sometimes
 * a full reconciliation cycle later. They answer different questions --
 * "how often is dispatch ambiguous" versus "what happened to the ones that
 * were" -- and Phase 7's first draft named its own metric identically to
 * Phase 6's, which Prometheus/Micrometer would have rejected outright (one
 * metric name cannot carry two different label sets). Renamed here rather
 * than left collided or silently merged; see .notes/reports/PHASE-7-REPORT.md §6.
 */
@Component
public class GatewayMetrics {

    private static final List<String> RAILS = List.of("FEDWIRE", "SEPA", "ACH_EQUIV");
    private static final List<String> DISPATCH_OUTCOMES = List.of("acknowledged", "rejected", "failed", "timeout");
    private static final List<String> CONFIRMATION_STATUSES = List.of("ACSC", "ACSP", "RJCT");
    private static final List<String> AMBIGUITY_RESOLUTIONS =
            List.of("sent", "settled", "rejected", "redispatched", "investigation", "pending");
    private static final List<String> RECONCILIATION_OUTCOMES = List.of("known", "unknown", "inconclusive");

    private final Map<String, Counter> dispatchOutcomes = new HashMap<>();
    private final Map<String, Timer> dispatchDuration = new HashMap<>();
    private final Map<String, Counter> dispatchAmbiguous = new HashMap<>();
    private final Map<String, Counter> confirmations = new HashMap<>();
    private final Map<String, Counter> confirmationsUncorrelated = new HashMap<>();
    private final Map<InstructionState, AtomicLong> instructionsCurrent = new EnumMap<>(InstructionState.class);
    private final Map<String, Counter> ambiguityResolutions = new HashMap<>();
    private final Map<String, Counter> reconciliationAttempts = new HashMap<>();
    private final Map<String, Timer> reconciliationDuration = new HashMap<>();
    private final Map<String, Counter> redispatches = new HashMap<>();
    private final AtomicLong investigationOpen = new AtomicLong();
    private final JdbcTemplate jdbc;

    public GatewayMetrics(MeterRegistry registry, JdbcTemplate jdbc, GatewayProperties properties) {
        this.jdbc = jdbc;
        int maxRedispatchAttempts = properties.reconciliation() != null ? properties.reconciliation().maxRedispatchAttempts() : 3;
        for (String rail : RAILS) {
            for (String outcome : DISPATCH_OUTCOMES) {
                dispatchOutcomes.put(
                        key(rail, outcome),
                        Counter.builder("payment_dispatch_outcome_total").tag("rail", rail).tag("outcome", outcome).register(registry));
            }
            dispatchDuration.put(rail, Timer.builder("payment_dispatch_duration_seconds").tag("rail", rail).register(registry));
            dispatchAmbiguous.put(rail, Counter.builder("payment_dispatch_ambiguous_total").tag("rail", rail).register(registry));
            confirmationsUncorrelated.put(
                    rail, Counter.builder("payment_confirmations_uncorrelated_total").tag("rail", rail).register(registry));
            for (String status : CONFIRMATION_STATUSES) {
                confirmations.put(
                        key(rail, status),
                        Counter.builder("payment_confirmations_total").tag("rail", rail).tag("status", status).register(registry));
            }
            for (String resolution : AMBIGUITY_RESOLUTIONS) {
                ambiguityResolutions.put(
                        key(rail, resolution),
                        Counter.builder("payment_ambiguity_resolution_total")
                                .tag("rail", rail).tag("resolution", resolution).register(registry));
            }
            for (String outcome : RECONCILIATION_OUTCOMES) {
                reconciliationAttempts.put(
                        key(rail, outcome),
                        Counter.builder("payment_reconciliation_attempts_total")
                                .tag("rail", rail).tag("outcome", outcome).register(registry));
            }
            reconciliationDuration.put(rail, Timer.builder("payment_reconciliation_duration_seconds").tag("rail", rail).register(registry));
            // attempt_no 1 is the original dispatch, never a redispatch -- the
            // label space starts at 2 and runs through the configured cap.
            for (int attemptNo = 2; attemptNo <= maxRedispatchAttempts; attemptNo++) {
                redispatches.put(
                        key(rail, String.valueOf(attemptNo)),
                        Counter.builder("payment_redispatch_total")
                                .tag("rail", rail).tag("attempt_no", String.valueOf(attemptNo)).register(registry));
            }
        }
        for (InstructionState state : InstructionState.values()) {
            AtomicLong gauge = new AtomicLong();
            instructionsCurrent.put(state, gauge);
            Gauge.builder("payment_instructions_current", gauge, AtomicLong::get).tag("state", state.name()).register(registry);
        }
        Gauge.builder("payment_investigation_open", investigationOpen, AtomicLong::get).register(registry);
        refreshInstructionsCurrent();
    }

    public void recordDispatchOutcome(String rail, String outcome) {
        Counter counter = dispatchOutcomes.get(key(rail, outcome));
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordDispatchDuration(String rail, Duration duration) {
        Timer timer = dispatchDuration.get(rail);
        if (timer != null) {
            timer.record(duration);
        }
    }

    /** A dispatch attempt itself came back ambiguous (timeout or dropped connection) -- DispatchOrchestrator, at dispatch time. */
    public void recordDispatchAmbiguous(String rail) {
        Counter counter = dispatchAmbiguous.get(rail);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * How a Phase 7 {@code AmbiguityResolver} eventually resolved one
     * ambiguous dispatch -- sent | settled | rejected | redispatched |
     * investigation | pending. Distinct from {@link #recordDispatchAmbiguous}:
     * that counts the ambiguous dispatch outcome itself, this counts what
     * became of it, sometimes a full reconciliation cycle later.
     */
    public void recordAmbiguityResolution(String rail, String resolution) {
        Counter counter = ambiguityResolutions.get(key(rail, resolution));
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordReconciliationAttempt(String rail, String outcome) {
        Counter counter = reconciliationAttempts.get(key(rail, outcome));
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordReconciliationDuration(String rail, Duration duration) {
        Timer timer = reconciliationDuration.get(rail);
        if (timer != null) {
            timer.record(duration);
        }
    }

    public void recordRedispatch(String rail, int attemptNo) {
        Counter counter = redispatches.get(key(rail, String.valueOf(attemptNo)));
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordConfirmation(String rail, String status) {
        Counter counter = confirmations.get(key(rail, status));
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordConfirmationUncorrelated(String rail) {
        Counter counter = confirmationsUncorrelated.get(rail);
        if (counter != null) {
            counter.increment();
        }
    }

    /** Polls rather than being pushed to on every transition, so this stays accurate even for transitions this service didn't itself perform. */
    @Scheduled(fixedDelayString = "${payments.gateway.instructions-current-refresh:PT5S}")
    public void refreshInstructionsCurrent() {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT state, COUNT(*) AS cnt FROM core.payment_instruction GROUP BY state", rs -> {
            counts.put(rs.getString("state"), rs.getInt("cnt"));
        });
        for (Map.Entry<InstructionState, AtomicLong> entry : instructionsCurrent.entrySet()) {
            entry.getValue().set(counts.getOrDefault(entry.getKey().name(), 0));
        }
        // A dedicated top-level gauge, not just payment_instructions_current
        // {state="INVESTIGATION"}, because this is the one number a human
        // watches for the operator queue Phase 8 will build against -- see
        // .notes/reports/PHASE-7-REPORT.md.
        investigationOpen.set(counts.getOrDefault(InstructionState.INVESTIGATION.name(), 0));
    }

    private static String key(String rail, String label) {
        return rail + ":" + label;
    }
}
