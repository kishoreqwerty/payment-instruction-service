package com.kishore.payments.intake.instruction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code payment_instructions_received_total}, declared in
 * .notes/ARCHITECTURE.md §9.1 since the project's early phases but never
 * actually implemented -- see .notes/reports/PHASE-10-REPORT.md §2's metric
 * audit. Pre-registered for "REST" only, not the "REST" | "KAFKA_FILE" pair
 * §2.1 describes: the Kafka file-drop ingress path was never built (Phase 2
 * only implemented the REST endpoint), so pre-registering a channel with no
 * code path that could ever increment it would be a permanently-zero series
 * with no alerting value, not a safety net -- see the same report section
 * for why this is flagged as a pre-existing gap rather than something this
 * phase should build (Phase 10's own scope boundary: "No new business
 * capability").
 */
@Component
public class IntakeMetrics {

    private final Map<String, Counter> received = new HashMap<>();

    public IntakeMetrics(MeterRegistry registry) {
        for (String channel : List.of("REST")) {
            received.put(channel, Counter.builder("payment_instructions_received_total").tag("channel", channel).register(registry));
        }
    }

    public void recordReceived(String channel) {
        Counter counter = received.get(channel);
        if (counter != null) {
            counter.increment();
        }
    }
}
