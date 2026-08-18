package com.kishore.payments.exception.cases;

import java.util.UUID;

public class InvestigationConfirmationNotFoundException extends RuntimeException {

    public InvestigationConfirmationNotFoundException(UUID confirmationId) {
        super("No investigation confirmation: " + confirmationId);
    }
}
