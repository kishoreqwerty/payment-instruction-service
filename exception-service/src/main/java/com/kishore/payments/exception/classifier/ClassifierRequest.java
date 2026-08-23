package com.kishore.payments.exception.classifier;

/**
 * The complete, redacted payload sent to the classifier model. Every field here is one
 * {@link PromptRedactor} deliberately chose to include -- there is no fuller object this was
 * trimmed from, so a test asserting the serialised JSON's key set is exhaustive rather than
 * "at least these fields" (.notes/reports/PHASE-11-REPORT.md section 2).
 *
 * <p>Never permitted anywhere in this record, per .notes/ARCHITECTURE.md section 8: account
 * numbers, party names, BICs, the raw message, instruction_id, uetr, end_to_end_id. {@code
 * errorMessage} looks like it could carry any of those -- several validation rules interpolate a
 * raw field value directly into their own message text -- so it is never the rule's message
 * unmodified; see {@link PromptRedactor#sanitizeMessage}.
 */
public record ClassifierRequest(
        String failureStage,
        String reasonCode,
        String errorMessage,
        FieldShape fieldShape,
        String currency,
        String rail,
        String amountBand,
        long instructionAgeDays,
        int repairAttemptCount) {

    /**
     * Structural shape of whichever instruction field a rule identified as the offending one
     * ({@code null} if the failure isn't attributable to one field) -- length, character
     * classes, IBAN checksum result if applicable, and country prefix if the value is
     * IBAN-shaped. Never the value itself.
     */
    public record FieldShape(String fieldPath, int length, String characterClasses, Boolean ibanChecksumValid, String countryPrefix) {
    }
}
