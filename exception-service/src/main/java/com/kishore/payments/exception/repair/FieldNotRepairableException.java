package com.kishore.payments.exception.repair;

import java.util.List;

/** One or more proposed {@code fieldPath} values are not on the repair allowlist. Mapped to 422, naming every disallowed field. */
public class FieldNotRepairableException extends RuntimeException {

    private final List<String> disallowedFields;

    public FieldNotRepairableException(List<String> disallowedFields) {
        super("Not repairable, not on the allowlist: " + disallowedFields);
        this.disallowedFields = disallowedFields;
    }

    public List<String> disallowedFields() {
        return disallowedFields;
    }
}
