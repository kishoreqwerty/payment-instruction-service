package com.kishore.payments.exception.api;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.state.InstructionState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InstructionSummaryResponse(
        UUID instructionId,
        UUID uetr,
        String endToEndId,
        InstructionState state,
        BigDecimal amount,
        String currency,
        LocalDate requestedExecDate,
        String selectedRail) {

    public static InstructionSummaryResponse of(PaymentInstructionEntity entity) {
        return new InstructionSummaryResponse(
                entity.getInstructionId(),
                entity.getUetr(),
                entity.getEndToEndId(),
                entity.getState(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getRequestedExecDate(),
                entity.getSelectedRail());
    }
}
