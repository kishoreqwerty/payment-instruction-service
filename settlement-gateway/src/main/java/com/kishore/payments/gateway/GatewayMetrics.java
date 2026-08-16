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
 */
@Component
public class GatewayMetrics {

    private static final List<String> RAILS = List.of("FEDWIRE", "SEPA", "ACH_EQUIV");
    private static final List<String> DISPATCH_OUTCOMES = List.of("acknowledged", "rejected", "failed", "timeout");
    private static final List<String> CONFIRMATION_STATUSES = List.of("ACSC", "ACSP", "RJCT");

    private final Map<String, Counter> dispatchOutcomes = new HashMap<>();
    private final Map<String, Timer> dispatchDuration = new HashMap<>();
    private final Map<String, Counter> dispatchAmbiguous = new HashMap<>();
    private final Map<String, Counter> confirmations = new HashMap<>();
    private final Map<String, Counter> confirmationsUncorrelated = new HashMap<>();
    private final Map<InstructionState, AtomicLong> instructionsCurrent = new EnumMap<>(InstructionState.class);
    private final JdbcTemplate jdbc;

    public GatewayMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        }
        for (InstructionState state : InstructionState.values()) {
            AtomicLong gauge = new AtomicLong();
            instructionsCurrent.put(state, gauge);
            Gauge.builder("payment_instructions_current", gauge, AtomicLong::get).tag("state", state.name()).register(registry);
        }
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

    public void recordDispatchAmbiguous(String rail) {
        Counter counter = dispatchAmbiguous.get(rail);
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
    }

    private static String key(String rail, String label) {
        return rail + ":" + label;
    }
}
