package com.kishore.payments.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kishore.payments.core.domain.ActorType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StateTransitionTableExhaustionTest {

    private static final StateTransitionTable TABLE = new StateTransitionTable();

    private static final Set<StateTransitionTable.Transition> LEGAL = TABLE.allLegalTransitions();

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("legalPairs")
    void legalTransitionSucceeds(InstructionState from, InstructionState to) {
        StateMachine machine = new StateMachine(TABLE);
        TransitionContext ctx = new TransitionContext(ActorType.SYSTEM, "test", null, null, 4);

        TransitionResult result = machine.transition(from, to, ctx);

        assertThat(result.newState()).isEqualTo(to);
        assertThat(result.sequenceNo()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("illegalPairs")
    void illegalTransitionThrows(InstructionState from, InstructionState to) {
        StateMachine machine = new StateMachine(TABLE);
        TransitionContext ctx = new TransitionContext(ActorType.SYSTEM, "test", null, null, 4);

        assertThatThrownBy(() -> machine.transition(from, to, ctx))
                .isInstanceOf(IllegalTransitionException.class)
                .satisfies(ex -> {
                    IllegalTransitionException ite = (IllegalTransitionException) ex;
                    assertThat(ite.from()).isEqualTo(from);
                    assertThat(ite.to()).isEqualTo(to);
                });
    }

    @Test
    void tableDeclaresExactly24LegalTransitions() {
        assertThat(LEGAL).hasSize(24);
    }

    @Test
    void everySelfTransitionIsIllegal() {
        for (InstructionState state : InstructionState.values()) {
            assertThat(TABLE.isLegal(state, state))
                    .as("%s -> %s should be illegal", state, state)
                    .isFalse();
        }
    }

    static Stream<Arguments> legalPairs() {
        return LEGAL.stream()
                .map(t -> Arguments.of(t.from(), t.to()));
    }

    static Stream<Arguments> illegalPairs() {
        Set<StateTransitionTable.Transition> legal = LEGAL;
        List<Arguments> illegal = new ArrayList<>();
        for (InstructionState from : EnumSet.allOf(InstructionState.class)) {
            for (InstructionState to : EnumSet.allOf(InstructionState.class)) {
                if (!legal.contains(new StateTransitionTable.Transition(from, to))) {
                    illegal.add(Arguments.of(from, to));
                }
            }
        }
        return illegal.stream();
    }
}
