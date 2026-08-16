package com.kishore.payments.processing.event;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published to payments.exceptions. Nothing consumes this topic yet --
 * exception-service is Phase 8 -- but the shape mirrors
 * exceptions.exception_case (.notes/ARCHITECTURE.md §3.2) closely enough
 * that opening that case later is a straight mapping. {@code details}
 * carries every violation a single failure produced, not just the first: a
 * message that fails three validation rules at once produces one event
 * naming all three, matching {@link
 * com.kishore.payments.processing.failure.BusinessFailureException}.
 */
public record InstructionExceptionEvent(
        UUID instructionId,
        UUID uetr,
        String endToEndId,
        int sequenceNo,
        OffsetDateTime occurredAt,
        FailureStage failureStage,
        List<Detail> details,
        int eventVersion) {

    public static final int CURRENT_VERSION = 1;

    public record Detail(String reasonCode, Repairability repairability, String field, String detail) {
    }
}
