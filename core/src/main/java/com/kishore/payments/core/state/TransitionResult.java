package com.kishore.payments.core.state;

/** The outcome of a successful state transition. */
public record TransitionResult(InstructionState newState, int sequenceNo) {
}
