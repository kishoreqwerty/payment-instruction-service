package com.kishore.payments.processing.failure;

import com.kishore.payments.core.domain.FailureStage;
import java.util.List;

/**
 * A failure the pipeline cannot resolve by itself: a bad IBAN, a missing
 * correspondent, a rail nobody can route to. Routes to {@code
 * payments.exceptions} on first occurrence and is never retried -- retrying a
 * bad IBAN five times produces five identical failures and obscures the real
 * signal (.notes/ARCHITECTURE.md §6.2). Enforced by exception type, not a
 * config flag: there is no code path that can accidentally retry one of
 * these.
 */
public class BusinessFailureException extends RuntimeException {

    private final FailureStage stage;
    private final List<FailureDetail> details;

    public BusinessFailureException(FailureStage stage, List<FailureDetail> details) {
        super(stage + ": " + details);
        if (details.isEmpty()) {
            throw new IllegalArgumentException("A business failure must carry at least one FailureDetail");
        }
        this.stage = stage;
        this.details = List.copyOf(details);
    }

    public BusinessFailureException(FailureStage stage, FailureDetail detail) {
        this(stage, List.of(detail));
    }

    public FailureStage stage() {
        return stage;
    }

    /** One or more violations, e.g. every rule a ValidationChain rejected in a single message -- not just the first. */
    public List<FailureDetail> details() {
        return details;
    }
}
