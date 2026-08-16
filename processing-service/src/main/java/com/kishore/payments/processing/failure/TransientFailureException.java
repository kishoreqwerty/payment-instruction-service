package com.kishore.payments.processing.failure;

/**
 * A failure that is expected to succeed on retry: a database timeout, a
 * broker hiccup, anything environmental rather than a defect in the
 * instruction itself. Retried with backoff per .notes/ARCHITECTURE.md §6.2
 * (1s, 2s, 4s, 8s, 16s, then dead-letter) by the consumer's error handler,
 * never routed to {@code payments.exceptions} directly.
 */
public class TransientFailureException extends RuntimeException {

    public TransientFailureException(String message) {
        super(message);
    }

    public TransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
