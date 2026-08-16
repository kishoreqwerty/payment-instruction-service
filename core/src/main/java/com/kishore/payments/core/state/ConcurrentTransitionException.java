package com.kishore.payments.core.state;

import java.util.UUID;

/**
 * Thrown when the optimistic lock on payment_instruction.state_version
 * detects that another writer already moved an instruction's state between
 * this writer's read and its attempted update. Phase 2's intake path performs
 * exactly one transition per instruction and does not retry; Phase 4's
 * processing-service consumers are expected to branch on this exception.
 */
public class ConcurrentTransitionException extends RuntimeException {

    private final UUID instructionId;
    private final InstructionState from;
    private final InstructionState to;

    public ConcurrentTransitionException(UUID instructionId, InstructionState from, InstructionState to, Throwable cause) {
        super("Concurrent transition detected for instruction " + instructionId
                + " (" + from + " -> " + to + "): another writer already advanced its state_version", cause);
        this.instructionId = instructionId;
        this.from = from;
        this.to = to;
    }

    public UUID instructionId() {
        return instructionId;
    }

    public InstructionState from() {
        return from;
    }

    public InstructionState to() {
        return to;
    }
}
