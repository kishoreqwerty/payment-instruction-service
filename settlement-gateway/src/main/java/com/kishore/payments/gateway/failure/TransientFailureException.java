package com.kishore.payments.gateway.failure;

/**
 * A failure expected to succeed on retry: a database timeout, a broker
 * hiccup -- environmental, not a defect in the instruction or the rail's
 * response to it. Retried with backoff per .notes/ARCHITECTURE.md §6.2 by
 * the consumer's error handler, then dead-lettered.
 *
 * <p>Deliberately not used for a rail 5xx: that outcome is fully known
 * within a single message's processing (RailDispatcher got a definite
 * response), so it is retried in-process, bounded, by the dispatch loop
 * itself -- see .notes/reports/PHASE-6-REPORT.md section 5. This exception
 * exists for failures that are NOT fully known within one processing pass:
 * the database or broker itself being unavailable.
 */
public class TransientFailureException extends RuntimeException {

    public TransientFailureException(String message) {
        super(message);
    }

    public TransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
