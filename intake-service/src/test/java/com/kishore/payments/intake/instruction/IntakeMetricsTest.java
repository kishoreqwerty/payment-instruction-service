package com.kishore.payments.intake.instruction;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Phase 10's pre-initialisation rule: see .notes/reports/PHASE-10-REPORT.md §2/§4. */
class IntakeMetricsTest {

    @Test
    void receivedCounterIsPreRegisteredAtZeroForRest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new IntakeMetrics(registry);

        assertThat(registry.get("payment_instructions_received_total").tag("channel", "REST").counter().count()).isZero();
    }

    @Test
    void recordReceivedIncrementsTheMatchingCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntakeMetrics metrics = new IntakeMetrics(registry);

        metrics.recordReceived("REST");

        assertThat(registry.get("payment_instructions_received_total").tag("channel", "REST").counter().count()).isEqualTo(1.0);
    }
}
