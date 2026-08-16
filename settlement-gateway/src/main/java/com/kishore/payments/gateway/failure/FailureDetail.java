package com.kishore.payments.gateway.failure;

import com.kishore.payments.core.domain.Repairability;

/** One violation: the ISO external reason code where one applies, whether it's repairable, and a human-readable detail. */
public record FailureDetail(String reasonCode, Repairability repairability, String field, String detail) {

    public FailureDetail {
        if (repairability == null) {
            throw new IllegalArgumentException("repairability is required");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail is required");
        }
    }
}
