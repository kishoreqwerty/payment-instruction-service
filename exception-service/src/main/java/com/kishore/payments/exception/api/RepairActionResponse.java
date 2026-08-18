package com.kishore.payments.exception.api;

import com.kishore.payments.exception.repair.RepairActionEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RepairActionResponse(
        UUID actionId,
        UUID caseId,
        String fieldPath,
        String oldValue,
        String newValue,
        String proposedBy,
        OffsetDateTime proposedAt,
        String approvedBy,
        OffsetDateTime approvedAt) {

    public static RepairActionResponse of(RepairActionEntity entity) {
        return new RepairActionResponse(
                entity.getActionId(),
                entity.getCaseId(),
                entity.getFieldPath(),
                entity.getOldValue(),
                entity.getNewValue(),
                entity.getProposedBy(),
                entity.getProposedAt(),
                entity.getApprovedBy(),
                entity.getApprovedAt());
    }
}
