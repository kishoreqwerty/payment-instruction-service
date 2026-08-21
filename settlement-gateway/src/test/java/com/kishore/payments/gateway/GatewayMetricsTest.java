package com.kishore.payments.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.kishore.payments.core.state.InstructionState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 10's pre-initialisation rule: see .notes/reports/PHASE-10-REPORT.md
 * §2/§4. {@link JdbcTemplate} is mocked rather than a real Postgres:
 * {@code refreshInstructionsCurrent} (called once, synchronously, at
 * construction) is a plain read with nothing to assert about here, and
 * every metric this test cares about is registered before that read ever
 * runs.
 */
class GatewayMetricsTest {

    private static final List<String> RAILS = List.of("FEDWIRE", "SEPA", "ACH_EQUIV");

    private GatewayMetrics newMetrics(SimpleMeterRegistry registry) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        GatewayProperties properties = new GatewayProperties(
                Map.of(), Duration.ofSeconds(2), Duration.ofSeconds(10), new GatewayProperties.DispatchRetry(5, Duration.ofSeconds(1), 2.0),
                new GatewayProperties.Reconciliation(
                        Duration.ofMinutes(2), Duration.ofSeconds(30), 50, 2, 10, 3, Duration.ofMinutes(5)));
        return new GatewayMetrics(registry, jdbc, properties);
    }

    @Test
    void dispatchOutcomeIsPreRegisteredAtZeroForEveryRailAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (String rail : RAILS) {
            for (String outcome : List.of("acknowledged", "rejected", "failed", "timeout")) {
                assertThat(registry.get("payment_dispatch_outcome_total").tag("rail", rail).tag("outcome", outcome).counter().count())
                        .as("%s/%s", rail, outcome)
                        .isZero();
            }
        }
    }

    @Test
    void dispatchAmbiguousIsPreRegisteredAtZeroForEveryRail() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (String rail : RAILS) {
            assertThat(registry.get("payment_dispatch_ambiguous_total").tag("rail", rail).counter().count()).isZero();
        }
    }

    @Test
    void ambiguityResolutionIsPreRegisteredAtZeroForEveryRailAndResolution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (String rail : RAILS) {
            for (String resolution : List.of("sent", "settled", "rejected", "redispatched", "investigation", "pending")) {
                assertThat(registry.get("payment_ambiguity_resolution_total").tag("rail", rail).tag("resolution", resolution).counter().count())
                        .as("%s/%s", rail, resolution)
                        .isZero();
            }
        }
    }

    @Test
    void instructionsCurrentIsPreRegisteredAtZeroForEveryState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (InstructionState state : InstructionState.values()) {
            assertThat(registry.get("payment_instructions_current").tag("state", state.name()).gauge().value())
                    .as(state.name())
                    .isZero();
        }
    }

    @Test
    void investigationOpenIsPreRegisteredAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        assertThat(registry.get("payment_investigation_open").gauge().value()).isZero();
    }

    @Test
    void redispatchIsPreRegisteredAtZeroFromAttemptTwoThroughTheConfiguredCap() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (String rail : RAILS) {
            for (int attemptNo = 2; attemptNo <= 3; attemptNo++) {
                assertThat(registry.get("payment_redispatch_total")
                                .tag("rail", rail)
                                .tag("attempt_no", String.valueOf(attemptNo))
                                .counter()
                                .count())
                        .as("%s attempt %d", rail, attemptNo)
                        .isZero();
            }
        }
    }

    @Test
    void recordDispatchOutcomeIncrementsOnlyTheMatchingCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = newMetrics(registry);

        metrics.recordDispatchOutcome("FEDWIRE", "acknowledged");

        assertThat(registry.get("payment_dispatch_outcome_total").tag("rail", "FEDWIRE").tag("outcome", "acknowledged").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("payment_dispatch_outcome_total").tag("rail", "FEDWIRE").tag("outcome", "rejected").counter().count())
                .isZero();
    }
}
