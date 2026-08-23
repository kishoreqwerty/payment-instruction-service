package com.kishore.payments.exception.api;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.exception.cases.CaseStatus;
import com.kishore.payments.exception.cases.CaseType;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.cases.Resolution;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code endToEndId}/{@code amount}/{@code currency}/{@code creditorName} are
 * not columns on {@code exception_case} -- they are read off the instruction
 * this case belongs to. Phase 8 had no browser client, so its own {@code
 * CaseSummaryResponse} carried only {@code instructionId} and left the
 * caller to resolve the rest; the ops-dashboard's queue table needs every
 * one of those fields on the landing screen (brief §3: "instruction
 * reference, amount and currency, creditor... "), and fetching them one
 * instruction at a time per queue row would be an N+1 the API can avoid by
 * carrying them here instead. See PHASE-9-REPORT.md §5 for the full
 * account of this gap.
 *
 * <p>{@code classifier*} fields (Phase 11): all null if the classifier was unavailable when this
 * case opened, or produced no usable proposal (a malformed response, per {@code ClassifierClient}).
 * Never a system determination -- see ops-dashboard's own proposal panel, which the phase brief
 * requires to visually read as a suggestion, not a pre-filled fact.
 */
public record CaseSummaryResponse(
        UUID caseId,
        UUID instructionId,
        String endToEndId,
        BigDecimal amount,
        String currency,
        String creditorName,
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
        OffsetDateTime closedAt,
        String classifierCode,
        Repairability classifierRepairability,
        Double classifierConf,
        Boolean classifierAccepted,
        String classifierSuggestedField,
        String classifierSuggestedValue,
        String classifierRationale) {

    public static CaseSummaryResponse of(ExceptionCaseEntity entity, PaymentInstructionEntity instruction) {
        return new CaseSummaryResponse(
                entity.getCaseId(),
                entity.getInstructionId(),
                instruction == null ? null : instruction.getEndToEndId(),
                instruction == null ? null : instruction.getAmount(),
                instruction == null ? null : instruction.getCurrency(),
                instruction == null ? null : instruction.getCreditorName(),
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
                entity.getClosedAt(),
                entity.getClassifierCode(),
                entity.getClassifierRepairability() == null ? null : Repairability.valueOf(entity.getClassifierRepairability()),
                entity.getClassifierConf(),
                entity.getClassifierAccepted(),
                entity.getClassifierSuggestedField(),
                entity.getClassifierSuggestedValue(),
                entity.getClassifierRationale());
    }
}
