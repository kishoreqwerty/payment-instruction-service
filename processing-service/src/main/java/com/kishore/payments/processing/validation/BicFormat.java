package com.kishore.payments.processing.validation;

import java.util.regex.Pattern;

/** ISO 9362 BIC format: 4-letter bank code, 2-letter country code, 2-alphanumeric location code, optional 3-alphanumeric branch code. */
final class BicFormat {

    private static final Pattern BIC_PATTERN = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");

    private BicFormat() {
    }

    static boolean isValid(String bic) {
        return bic != null && BIC_PATTERN.matcher(bic).matches();
    }
}
