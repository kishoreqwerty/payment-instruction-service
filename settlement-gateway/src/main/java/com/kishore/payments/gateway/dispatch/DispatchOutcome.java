package com.kishore.payments.gateway.dispatch;

/**
 * What one dispatch attempt to a rail resolved to. A read timeout and a
 * dropped/reset connection are represented identically ({@link Ambiguous}):
 * the message may or may not have arrived, and there is no way to tell them
 * apart from here, so there must not be a way to tell them apart in how they
 * are handled either (.notes/ARCHITECTURE.md §6.4).
 */
public sealed interface DispatchOutcome {

    /** 202: the rail accepted the message for processing. */
    record Acknowledged(int httpStatus) implements DispatchOutcome {
    }

    /** 4xx: the rail rejected the message outright -- a defect in the message itself, never retried. */
    record Rejected(int httpStatus, String detail) implements DispatchOutcome {
    }

    /** 5xx: the rail processed the request and returned an explicit server error -- worth retrying. */
    record ServerError(int httpStatus, String detail) implements DispatchOutcome {
    }

    /** Read timeout, connection reset, or connection drop: receipt by the rail is unknown. Never retried. */
    record Ambiguous(String detail) implements DispatchOutcome {
    }
}
