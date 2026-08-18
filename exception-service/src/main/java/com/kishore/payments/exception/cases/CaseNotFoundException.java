package com.kishore.payments.exception.cases;

import java.util.UUID;

public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(UUID caseId) {
        super("No exception case: " + caseId);
    }
}
