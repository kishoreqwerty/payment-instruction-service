package com.kishore.payments.processing.failure;

import com.kishore.payments.core.domain.Repairability;

/**
 * One violation: the ISO external reason code where one applies, whether
 * it's repairable, and a human-readable detail. A single message can
 * produce several of these -- the validation stage in particular collects
 * every rule violation rather than stopping at the first.
 */
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
