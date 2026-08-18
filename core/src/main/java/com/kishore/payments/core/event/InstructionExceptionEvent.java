package com.kishore.payments.core.event;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published to payments.exceptions. Lives in core, not processing-service or
 * settlement-gateway, because a second real service (exception-service,
 * Phase 8) now consumes it -- the same reason {@link InstructionRoutedEvent}
 * lives here. Previously defined independently, identically, in both
 * producing modules (see .notes/reports/PHASE-6-REPORT.md section 6 for why
 * that duplication was accepted at the time); consolidated now that a real
 * consumer exists to keep in sync with.
 *
 * <p>{@code details} carries every violation a single failure produced, not
 * just the first: a message that fails three validation rules at once
 * produces one event naming all three.
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
