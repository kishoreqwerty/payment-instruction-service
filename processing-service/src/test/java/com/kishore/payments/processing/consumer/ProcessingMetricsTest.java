package com.kishore.payments.processing.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 10's pre-initialisation rule: see .notes/reports/PHASE-10-REPORT.md §2/§4. */
class ProcessingMetricsTest {

    @Test
    void duplicatesSuppressedIsPreRegisteredAtZeroForEveryStage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ProcessingMetrics(registry);

        for (String stage : List.of("VALIDATION", "ENRICHMENT", "ROUTING")) {
            double value = registry.get("payment_duplicates_suppressed_total").tag("stage", stage).counter().count();
            assertThat(value).as("%s should be registered at zero before any duplicate is suppressed", stage).isZero();
        }
    }

    @Test
    void recordDuplicateSuppressedIncrementsOnlyTheMatchingStage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProcessingMetrics metrics = new ProcessingMetrics(registry);

        metrics.recordDuplicateSuppressed("VALIDATION");

        assertThat(registry.get("payment_duplicates_suppressed_total").tag("stage", "VALIDATION").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("payment_duplicates_suppressed_total").tag("stage", "ENRICHMENT").counter().count()).isZero();
    }
}
