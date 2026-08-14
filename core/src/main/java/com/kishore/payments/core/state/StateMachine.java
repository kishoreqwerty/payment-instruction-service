package com.kishore.payments.core.state;

/**
 * Validates and executes instruction state transitions against a
 * {@link StateTransitionTable}. Pure: no persistence, no side effects.
 * Database wiring is a later phase's concern.
 */
public final class StateMachine {

    private final StateTransitionTable transitionTable;

    public StateMachine(StateTransitionTable transitionTable) {
        this.transitionTable = transitionTable;
    }

    public TransitionResult transition(InstructionState from, InstructionState to, TransitionContext ctx) {
        if (!transitionTable.isLegal(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
        return new TransitionResult(to, ctx.currentSequenceNo() + 1);
    }
}
