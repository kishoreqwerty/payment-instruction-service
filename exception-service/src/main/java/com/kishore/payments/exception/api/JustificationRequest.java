package com.kishore.payments.exception.api;

/** Body of both investigation-resolution endpoints: the one place a human overrides the system's own conclusion, so the record of why must exist. */
public record JustificationRequest(String justification) {
}
