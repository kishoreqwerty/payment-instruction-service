package com.kishore.payments.exception.config;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
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
 * Every counter is pre-registered at zero for every known label combination
 * at startup -- the same reasoning as {@code GatewayMetrics}/{@code
 * OutboxMetrics}: a counter nobody has incremented does not appear in a
 * scrape, which silently disables any alert built on it.
 *
 * <p>One label is not fully pre-initialisable, and it is worth saying so
 * rather than quietly falling short of the project's own standard:
 * {@code payment_exception_cases_opened_total}'s {@code reason_code} is an
 * ISO external code (AC01, RC01, ...), not a bounded domain enum the way
 * {@code failure_stage} and {@code repairability} are -- a rail can report a
 * code this system has never seen before, by design, and no fixed list
 * pre-registered here could stay exhaustive. What *is* pre-registered is one
 * zero-valued series per (stage, repairability) pair under a {@code
 * reason_code="none"} sentinel, which guarantees the metric name itself is
 * always present in a scrape from startup -- the specific failure mode the
 * project's own pre-initialisation standard exists to prevent -- even though
 * a genuinely new reason code will still, unavoidably, start its own series
 * at first occurrence rather than at zero.
 */
@Component
public class ExceptionMetrics {

    private static final List<String> PROPOSE_OUTCOMES = List.of("accepted", "rejected");
    private static final List<String> REJECT_REASONS = List.of("maker_checker", "field_not_repairable", "cap_reached");
    private static final List<String> INVESTIGATION_OUTCOMES = List.of("confirmed_sent", "rejected");
    private static final String NO_REASON_CODE = "none";

    private final Map<String, Counter> casesOpened = new HashMap<>();
    private final Map<FailureStage, AtomicLong> casesOpen = new EnumMap<>(FailureStage.class);
    private final Map<String, Counter> repairsProposed = new HashMap<>();
    private final Counter repairsApplied;
    private final Map<String, Counter> repairsRejected = new HashMap<>();
    private final Timer caseAge;
    private final Map<String, Counter> investigationResolutions = new HashMap<>();
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    public ExceptionMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.registry = registry;

        for (FailureStage stage : FailureStage.values()) {
            for (Repairability repairability : Repairability.values()) {
                casesOpened.put(
                        key(stage, NO_REASON_CODE, repairability),
                        Counter.builder("payment_exception_cases_opened_total")
                                .tag("stage", stage.name())
                                .tag("reason_code", NO_REASON_CODE)
                                .tag("repairability", repairability.name())
                                .register(registry));
            }
            AtomicLong gauge = new AtomicLong();
            casesOpen.put(stage, gauge);
            Gauge.builder("payment_exception_cases_open", gauge, AtomicLong::get).tag("stage", stage.name()).register(registry);
        }

        for (String outcome : PROPOSE_OUTCOMES) {
            repairsProposed.put(outcome, Counter.builder("payment_repairs_proposed_total").tag("outcome", outcome).register(registry));
        }
        for (String reason : REJECT_REASONS) {
            repairsRejected.put(reason, Counter.builder("payment_repair_rejected_total").tag("reason", reason).register(registry));
        }
        for (String outcome : INVESTIGATION_OUTCOMES) {
            investigationResolutions.put(
                    outcome, Counter.builder("payment_investigation_resolutions_total").tag("outcome", outcome).register(registry));
        }

        repairsApplied = Counter.builder("payment_repairs_applied_total").register(registry);
        caseAge = Timer.builder("payment_case_age_seconds").register(registry);

        refreshCasesOpen();
    }

    /** Uses the pre-registered {@code reason_code="none"} series when {@code reasonCode} is null or not otherwise pre-initialised; see class javadoc. */
    public void recordCaseOpened(FailureStage stage, String reasonCode, Repairability repairability) {
        String code = reasonCode == null ? NO_REASON_CODE : reasonCode;
        Counter counter = casesOpened.get(key(stage, code, repairability));
        if (counter == null) {
            // A genuinely new reason code: register it lazily, on this same
            // injected registry, rather than dropping the observation --
            // Micrometer itself de-duplicates repeat registrations of the
            // same name+tags, so a second case with this same new code
            // finds the same counter next time, not a fresh zero one.
            counter = Counter.builder("payment_exception_cases_opened_total")
                    .tag("stage", stage.name())
                    .tag("reason_code", code)
                    .tag("repairability", repairability.name())
                    .register(registry);
            casesOpened.put(key(stage, code, repairability), counter);
        }
        counter.increment();
    }

    public void recordRepairProposed(String outcome) {
        Counter counter = repairsProposed.get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordRepairApplied() {
        repairsApplied.increment();
    }

    public void recordRepairRejected(String reason) {
        Counter counter = repairsRejected.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordCaseAge(Duration age) {
        caseAge.record(age);
    }

    public void recordInvestigationResolution(String outcome) {
        Counter counter = investigationResolutions.get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }

    /** Polls rather than being pushed to on every case-status change, so this stays accurate regardless of which code path changed it. */
    @Scheduled(fixedDelayString = "${payments.exceptions.open-refresh:PT5S}")
    public void refreshCasesOpen() {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query(
                "SELECT failure_stage, COUNT(*) AS cnt FROM exceptions.exception_case "
                        + "WHERE status NOT IN ('RESOLVED', 'REJECTED') GROUP BY failure_stage",
                rs -> {
                    counts.put(rs.getString("failure_stage"), rs.getInt("cnt"));
                });
        for (Map.Entry<FailureStage, AtomicLong> entry : casesOpen.entrySet()) {
            entry.getValue().set(counts.getOrDefault(entry.getKey().name(), 0));
        }
    }

    private static String key(FailureStage stage, String reasonCode, Repairability repairability) {
        return stage.name() + ":" + reasonCode + ":" + repairability.name();
    }
}
