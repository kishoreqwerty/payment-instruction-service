package com.kishore.payments.intake.pain001;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** The fields intake actually needs out of a validated pain.001 message. */
public record ParsedPain001Instruction(
        String debtorName,
        String debtorAccount,
        String debtorAgentBic,
        String creditorName,
        String creditorAccount,
        String creditorAgentBic,
        BigDecimal amount,
        String currency,
        String chargeBearer,
        LocalDate requestedExecDate,
        String endToEndId,
        String instructionIdExt,
        UUID uetr) {
}
