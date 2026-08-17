package com.kishore.payments.gateway.callback;

import java.util.UUID;

/**
 * A confirmation arrived for an instruction still at {@code ROUTED}: the
 * dispatch it presupposes was accepted, but {@code DispatchOrchestrator}'s
 * own ACK-driven {@code ROUTED->SENT} transition has not committed yet.
 * Thrown rather than silently discarded so the caller can retry outside
 * this attempt's transaction -- see {@link CallbackCorrelationService#handleStatus}.
 */
public class AckPendingException extends RuntimeException {

    private final UUID instructionId;

    public AckPendingException(UUID instructionId) {
        super("Instruction " + instructionId + " is still ROUTED; the dispatch ACK has not committed yet");
        this.instructionId = instructionId;
    }

    public UUID instructionId() {
        return instructionId;
    }
}
