package com.kishore.payments.exception.api;

import com.kishore.payments.exception.cases.InvestigationConfirmationEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InvestigationConfirmationResponse(
        UUID confirmationId, UUID caseId, String justification, String proposedBy, OffsetDateTime proposedAt, String approvedBy,
        OffsetDateTime approvedAt) {

    public static InvestigationConfirmationResponse of(InvestigationConfirmationEntity entity) {
        return new InvestigationConfirmationResponse(
                entity.getConfirmationId(), entity.getCaseId(), entity.getJustification(), entity.getProposedBy(), entity.getProposedAt(),
                entity.getApprovedBy(), entity.getApprovedAt());
    }
}
