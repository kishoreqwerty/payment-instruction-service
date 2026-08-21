package com.kishore.payments.core.state;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link InstructionStateWriter} is the single choke point every service's
 * every state transition passes through -- exactly the property that made
 * it the right place to add {@code payment_instructions_state_transitions_total}
 * and {@code payment_pipeline_duration_seconds}, both declared in
 * .notes/ARCHITECTURE.md §9.1 since the project's early phases but never
 * actually implemented (see .notes/reports/PHASE-10-REPORT.md §2's metric
 * audit), and {@code payment_pipeline_stage_duration_seconds}, this phase's
 * own new cross-cutting family. One class here, auto-configured once,
 * instead of every consumer across every service re-deriving "how long was
 * this instruction in its previous state" by hand.
 *
 * <p>Every counter is pre-registered at zero for every legal transition in
 * {@link StateTransitionTable#allLegalTransitions()} -- the table is already
 * the single source of truth for what's legal, so it is also the natural
 * source of truth for what to pre-initialise; a transition the table
 * doesn't know about can't happen anyway. {@code payment_pipeline_stage_duration_seconds}
 * is pre-registered per state that ever appears as a "from" (every
 * non-terminal state); a terminal state (RETURNED, REJECTED, CANCELLED) is
 * never a stage an instruction is "in transit through", only one it ends up
 * in, so it never needs its own duration series.
 */
public class InstructionStateMetrics {

    private final Map<StateTransitionTable.Transition, Counter> transitions = new HashMap<>();
    private final Map<InstructionState, Timer> stageDuration = new EnumMap<>(InstructionState.class);
    private final Timer pipelineDuration;

    public InstructionStateMetrics(MeterRegistry registry, StateTransitionTable table) {
        for (StateTransitionTable.Transition transition : table.allLegalTransitions()) {
            transitions.put(
                    transition,
                    Counter.builder("payment_instructions_state_transitions_total")
                            .tag("from", transition.from().name())
                            .tag("to", transition.to().name())
                            .register(registry));
            stageDuration.computeIfAbsent(
                    transition.from(),
                    from -> Timer.builder("payment_pipeline_stage_duration_seconds").tag("stage", from.name()).register(registry));
        }
        // RECEIVED -> terminal, so this can only ever be recorded, correctly,
        // once no matter how many intermediate stages an instruction visits.
        this.pipelineDuration = Timer.builder("payment_pipeline_duration_seconds").register(registry);
    }

    public void recordTransition(InstructionState from, InstructionState to) {
        Counter counter = transitions.get(new StateTransitionTable.Transition(from, to));
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordStageDuration(InstructionState stage, Duration timeInStage) {
        Timer timer = stageDuration.get(stage);
        if (timer != null) {
            timer.record(timeInStage);
        }
    }

    public void recordPipelineDuration(Duration receivedToTerminal) {
        pipelineDuration.record(receivedToTerminal);
    }
}
