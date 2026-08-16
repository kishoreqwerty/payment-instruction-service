package com.kishore.payments.gateway.event;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published to payments.exceptions for a DISPATCH or CONFIRMATION failure.
 * Shape mirrors processing-service's own InstructionExceptionEvent
 * (independently defined, not shared via core -- see
 * .notes/reports/PHASE-6-REPORT.md section 6 for why, and why that's a
 * narrower-risk duplication than InstructionRoutedEvent's).
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
