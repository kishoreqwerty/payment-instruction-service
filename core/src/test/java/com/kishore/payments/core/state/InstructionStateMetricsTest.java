package com.kishore.payments.core.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 10's own pre-initialisation rule, applied to the one metrics class
 * every service's every state transition passes through: every counter
 * pre-registered at zero for every known label combination at startup, so
 * an alert on it never silently waits for the first occurrence to start
 * existing. See .notes/reports/PHASE-10-REPORT.md §2/§4.
 */
class InstructionStateMetricsTest {

    @Test
    void everyLegalTransitionIsPreRegisteredAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StateTransitionTable table = new StateTransitionTable();

        new InstructionStateMetrics(registry, table);

        for (StateTransitionTable.Transition transition : table.allLegalTransitions()) {
            double value = registry.get("payment_instructions_state_transitions_total")
                    .tag("from", transition.from().name())
                    .tag("to", transition.to().name())
                    .counter()
                    .count();
            assertThat(value).as("%s -> %s should be registered at zero before any transition happens", transition.from(), transition.to())
                    .isZero();
        }
    }

    @Test
    void everyNonTerminalStateHasAPreRegisteredStageDurationTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StateTransitionTable table = new StateTransitionTable();

        new InstructionStateMetrics(registry, table);

        Set<InstructionState> nonTerminal = EnumSet.noneOf(InstructionState.class);
        for (InstructionState state : InstructionState.values()) {
            if (!state.isTerminal()) {
                nonTerminal.add(state);
            }
        }

        for (InstructionState stage : nonTerminal) {
            var timer = registry.get("payment_pipeline_stage_duration_seconds").tag("stage", stage.name()).timer();
            assertThat(timer.count()).as("%s should be registered at zero before any transition out of it happens", stage).isZero();
        }
    }

    @Test
    void pipelineDurationIsRegisteredAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new InstructionStateMetrics(registry, new StateTransitionTable());

        assertThat(registry.get("payment_pipeline_duration_seconds").timer().count()).isZero();
    }

    @Test
    void recordTransitionIncrementsOnlyTheMatchingCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InstructionStateMetrics metrics = new InstructionStateMetrics(registry, new StateTransitionTable());

        metrics.recordTransition(InstructionState.RECEIVED, InstructionState.VALIDATED);

        assertThat(registry.get("payment_instructions_state_transitions_total")
                        .tag("from", "RECEIVED")
                        .tag("to", "VALIDATED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("payment_instructions_state_transitions_total")
                        .tag("from", "RECEIVED")
                        .tag("to", "EXCEPTION")
                        .counter()
                        .count())
                .isZero();
    }

    @Test
    void recordStageDurationAndPipelineDurationRecordOntoTheRightSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InstructionStateMetrics metrics = new InstructionStateMetrics(registry, new StateTransitionTable());

        metrics.recordStageDuration(InstructionState.VALIDATED, Duration.ofSeconds(3));
        metrics.recordPipelineDuration(Duration.ofSeconds(90));

        var stageTimer = registry.get("payment_pipeline_stage_duration_seconds").tag("stage", "VALIDATED").timer();
        assertThat(stageTimer.count()).isEqualTo(1);
        assertThat(stageTimer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3.0);

        var pipelineTimer = registry.get("payment_pipeline_duration_seconds").timer();
        assertThat(pipelineTimer.count()).isEqualTo(1);
        assertThat(pipelineTimer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(90.0);
    }
}
