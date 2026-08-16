package com.kishore.payments.gateway.event;

import com.kishore.payments.core.state.InstructionState;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Published to payments.sent on ROUTED -> SENT and ROUTED -> SENT_UNCONFIRMED. {@code state} is which of the two actually happened. */
public record InstructionDispatchEvent(
        UUID instructionId,
        UUID uetr,
        String endToEndId,
        InstructionState state,
        int sequenceNo,
        OffsetDateTime occurredAt,
        String rail,
        UUID dispatchId,
        int attemptNo,
        int eventVersion) {

    public static final int CURRENT_VERSION = 1;
}
