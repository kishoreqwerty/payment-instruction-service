package com.kishore.payments.core.state;

/** Thrown when a state transition is attempted that is absent from the {@link StateTransitionTable}. */
public class IllegalTransitionException extends RuntimeException {

    private final InstructionState from;
    private final InstructionState to;

    public IllegalTransitionException(InstructionState from, InstructionState to) {
        super("Illegal transition from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public InstructionState from() {
        return from;
    }

    public InstructionState to() {
        return to;
    }
}
