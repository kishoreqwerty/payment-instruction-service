package com.kishore.payments.core.state;

import java.util.UUID;

/**
 * Thrown when another writer already moved an instruction's state before
 * this one -- detected one of two ways. Usually the optimistic lock on
 * payment_instruction.state_version catches it at write time, when this
 * writer's own read was still the pre-race value. If this writer's read
 * instead lands after the other writer's commit and this call's own target
 * is exactly the state that writer just reached, {@link
 * InstructionStateWriter} reclassifies what would otherwise look like a
 * self-transition ({@link IllegalTransitionException}) into this same
 * exception, since it is the same fact -- another writer got here first --
 * just observed earlier in the method (see that class's javadoc). Either
 * way, from a caller's perspective this instruction was already moved by
 * someone else and there is nothing to do. Phase 2's intake path performs
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
