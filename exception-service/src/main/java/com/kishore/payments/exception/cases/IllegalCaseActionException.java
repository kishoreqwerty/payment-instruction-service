package com.kishore.payments.exception.cases;

import java.util.UUID;

/**
 * An action was attempted against a case that cannot accept it right now:
 * wrong {@link CaseType} (propose-repair against an INVESTIGATION case, or
 * confirm-sent against a BUSINESS_FAILURE one), a terminal status, or the
 * repair cap already reached. Distinct from a field-level {@code
 * FieldNotRepairableException} (422, the request itself is malformed) --
 * this is a state conflict, mapped to 409.
 */
public class IllegalCaseActionException extends RuntimeException {

    public IllegalCaseActionException(UUID caseId, String reason) {
        super("Case " + caseId + " cannot accept this action: " + reason);
    }
}
