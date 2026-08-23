package com.kishore.payments.exception.api;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code creditorAccount}/{@code creditorAgentBic}/{@code creditorName}/
 * {@code chargeBearer} were added building the ops-dashboard (Phase 9): the
 * repair form needs the instruction's *current* value for every repairable
 * field to show alongside the proposed one (brief §3), and before this
 * change nothing in the read API carried three of the five allowlisted
 * fields at all -- see PHASE-9-REPORT.md §5. {@code requestedExecDate} was
 * already here for a different reason (Phase 8's own lookup screen) and
 * happens to be the fifth.
 */
public record InstructionSummaryResponse(
        UUID instructionId,
        UUID uetr,
        String endToEndId,
        InstructionState state,
        BigDecimal amount,
        String currency,
        LocalDate requestedExecDate,
        String selectedRail,
        String creditorAccount,
        String creditorAgentBic,
        String creditorName,
        String chargeBearer,
        UUID openCaseId) {

    /**
     * {@code openCaseId} lets a lookup-screen result row navigate straight to the case, rather
     * than dead-ending on a row with nowhere to go -- see {@link InstructionController#lookup}.
     * Not meaningful from {@link CaseDetailResponse}, where the case is already known and this
     * would just echo its own {@code caseId} back; that call site uses this overload.
     */
    public static InstructionSummaryResponse of(PaymentInstructionEntity entity) {
        return of(entity, null);
    }

    public static InstructionSummaryResponse of(PaymentInstructionEntity entity, UUID openCaseId) {
        return new InstructionSummaryResponse(
                entity.getInstructionId(),
                entity.getUetr(),
                entity.getEndToEndId(),
                entity.getState(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getRequestedExecDate(),
                entity.getSelectedRail(),
                entity.getCreditorAccount(),
                entity.getCreditorAgentBic(),
                entity.getCreditorName(),
                entity.getChargeBearer(),
                openCaseId);
    }
}
