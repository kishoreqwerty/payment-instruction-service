package com.kishore.payments.exception.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments.exceptions")
public record ExceptionServiceProperties(int maxRepairAttempts) {

    private static final int DEFAULT_MAX_REPAIR_ATTEMPTS = 3;

    public ExceptionServiceProperties {
        if (maxRepairAttempts <= 0) {
            maxRepairAttempts = DEFAULT_MAX_REPAIR_ATTEMPTS;
        }
    }
}
