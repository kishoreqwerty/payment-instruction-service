package com.kishore.payments.gateway.event;

import com.kishore.payments.core.state.InstructionState;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Published to payments.settled on SENT -> SETTLED and SETTLED -> RETURNED. {@code state} is which of the two happened. */
public record InstructionSettlementEvent(
        UUID instructionId,
        UUID uetr,
        String endToEndId,
        InstructionState state,
        int sequenceNo,
        OffsetDateTime occurredAt,
        String rail,
        String railReasonCode,
        int eventVersion) {

    public static final int CURRENT_VERSION = 1;
}
