package com.kishore.payments.processing.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Pre-registered at zero for every known stage at startup, same reasoning as {@link com.kishore.payments.core.outbox.OutboxMetrics}: a counter nobody has incremented yet does not appear in a scrape, silently disabling an alert on it. */
@Component
public class ProcessingMetrics {

    private final Map<String, Counter> duplicatesSuppressed = new HashMap<>();

    public ProcessingMetrics(MeterRegistry registry) {
        for (String stage : List.of("VALIDATION", "ENRICHMENT", "ROUTING")) {
            duplicatesSuppressed.put(
                    stage, Counter.builder("payment_duplicates_suppressed_total").tag("stage", stage).register(registry));
        }
    }

    public void recordDuplicateSuppressed(String stage) {
        Counter counter = duplicatesSuppressed.get(stage);
        if (counter != null) {
            counter.increment();
        }
    }
}
