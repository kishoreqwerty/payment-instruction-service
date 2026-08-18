package com.kishore.payments.exception.api;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.CaseType;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.cases.Resolution;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CaseSummaryResponse(
        UUID caseId,
        UUID instructionId,
        CaseType caseType,
        CaseStatus status,
        FailureStage failureStage,
        String reasonCode,
        String reasonDetail,
        Repairability repairability,
        String assignedTo,
        Resolution resolution,
        int repairAttempts,
        String justification,
        OffsetDateTime openedAt,
        OffsetDateTime closedAt) {

    public static CaseSummaryResponse of(ExceptionCaseEntity entity) {
        return new CaseSummaryResponse(
                entity.getCaseId(),
                entity.getInstructionId(),
                entity.getCaseType(),
                entity.getStatus(),
                entity.getFailureStage(),
                entity.getReasonCode(),
                entity.getReasonDetail(),
                entity.getRepairability(),
                entity.getAssignedTo(),
                entity.getResolution(),
                entity.getRepairAttempts(),
                entity.getJustification(),
                entity.getOpenedAt(),
                entity.getClosedAt());
    }
}
