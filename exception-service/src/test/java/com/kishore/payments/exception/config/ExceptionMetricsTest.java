package com.kishore.payments.exception.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 10's pre-initialisation rule: see .notes/reports/PHASE-10-REPORT.md
 * §2/§4. {@link JdbcTemplate} is mocked rather than a real Postgres, the
 * same reasoning as {@code GatewayMetricsTest}: {@code refreshCasesOpen}
 * (called once, synchronously, at construction) is a plain read, and every
 * metric this test cares about is registered before that read ever runs.
 */
class ExceptionMetricsTest {

    private ExceptionMetrics newMetrics(SimpleMeterRegistry registry) {
        return new ExceptionMetrics(registry, mock(JdbcTemplate.class));
    }

    @Test
    void casesOpenedIsPreRegisteredAtZeroForEveryStageAndRepairabilityUnderTheNoneReasonCode() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (FailureStage stage : FailureStage.values()) {
            for (Repairability repairability : Repairability.values()) {
                double value = registry.get("payment_exception_cases_opened_total")
                        .tag("stage", stage.name())
                        .tag("reason_code", "none")
                        .tag("repairability", repairability.name())
                        .counter()
                        .count();
                assertThat(value).as("%s/%s", stage, repairability).isZero();
            }
        }
    }

    @Test
    void casesOpenIsPreRegisteredAtZeroForEveryStage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (FailureStage stage : FailureStage.values()) {
            assertThat(registry.get("payment_exception_cases_open").tag("stage", stage.name()).gauge().value()).isZero();
        }
    }

    @Test
    void repairsProposedIsPreRegisteredAtZeroForEveryOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (String outcome : java.util.List.of("accepted", "rejected")) {
            assertThat(registry.get("payment_repairs_proposed_total").tag("outcome", outcome).counter().count()).isZero();
        }
    }

    @Test
    void repairRejectedIsPreRegisteredAtZeroForEveryReason() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (String reason : java.util.List.of("maker_checker", "field_not_repairable", "cap_reached")) {
            assertThat(registry.get("payment_repair_rejected_total").tag("reason", reason).counter().count()).isZero();
        }
    }

    @Test
    void investigationResolutionsIsPreRegisteredAtZeroForEveryOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        for (String outcome : java.util.List.of("confirmed_sent", "rejected")) {
            assertThat(registry.get("payment_investigation_resolutions_total").tag("outcome", outcome).counter().count()).isZero();
        }
    }

    @Test
    void repairsAppliedAndCaseAgeArePreRegisteredAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        newMetrics(registry);

        assertThat(registry.get("payment_repairs_applied_total").counter().count()).isZero();
        assertThat(registry.get("payment_case_age_seconds").timer().count()).isZero();
    }

    @Test
    void recordCaseOpenedIncrementsOnlyTheMatchingCounterAndLazilyRegistersANewReasonCode() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExceptionMetrics metrics = newMetrics(registry);

        metrics.recordCaseOpened(FailureStage.VALIDATION, "AC01", Repairability.REPAIRABLE);

        assertThat(registry.get("payment_exception_cases_opened_total")
                        .tag("stage", "VALIDATION")
                        .tag("reason_code", "AC01")
                        .tag("repairability", "REPAIRABLE")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        // The pre-registered "none" series for the same stage/repairability is untouched.
        assertThat(registry.get("payment_exception_cases_opened_total")
                        .tag("stage", "VALIDATION")
                        .tag("reason_code", "none")
                        .tag("repairability", "REPAIRABLE")
                        .counter()
                        .count())
                .isZero();
    }
}
