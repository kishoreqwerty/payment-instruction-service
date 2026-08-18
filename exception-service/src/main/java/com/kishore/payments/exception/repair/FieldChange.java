package com.kishore.payments.exception.repair;

/** One proposed field change: the request body of {@code POST /v1/cases/{caseId}/repairs} is a list of these. */
public record FieldChange(String fieldPath, String newValue) {
}
