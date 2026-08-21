package com.kishore.payments.exception.api;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.exception.cases.ExceptionCaseEntity;
import com.kishore.payments.exception.cases.InvestigationConfirmationEntity;
import com.kishore.payments.exception.repair.RepairActionEntity;
import java.util.List;

/**
 * {@code investigationConfirmations} is a Phase 9 addition, alongside
 * {@code instruction}: without it, an INVESTIGATION case's own detail
 * response carried no way to tell the case-detail screen "a confirm-sent
 * proposal is awaiting approval, made by X, with this justification" --
 * the only place that fact existed was the global {@code
 * GET /v1/investigation-confirmations/pending} list, which a screen
 * showing one specific case has no case-scoped way to filter down to. See
 * PHASE-9-REPORT.md §5.
 */
public record CaseDetailResponse(
        CaseSummaryResponse exceptionCase,
        InstructionSummaryResponse instruction,
        List<RepairActionResponse> repairActions,
        List<InvestigationConfirmationResponse> investigationConfirmations) {

    public static CaseDetailResponse of(
            ExceptionCaseEntity entity, PaymentInstructionEntity instruction, List<RepairActionEntity> actions,
            List<InvestigationConfirmationEntity> confirmations) {
        return new CaseDetailResponse(
                CaseSummaryResponse.of(entity, instruction),
                InstructionSummaryResponse.of(instruction),
                actions.stream().map(RepairActionResponse::of).toList(),
                confirmations.stream().map(InvestigationConfirmationResponse::of).toList());
    }
}
