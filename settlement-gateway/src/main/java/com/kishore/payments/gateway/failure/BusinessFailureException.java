package com.kishore.payments.gateway.failure;

import com.kishore.payments.core.domain.FailureStage;
import java.util.List;

/**
 * A dispatch or confirmation failure this gateway cannot resolve by itself:
 * a schema-invalid assembled message, a rail's 4xx rejection, an explicit
 * pacs.002 RJCT, a rail server error after retries are exhausted. Routes to
 * {@code payments.exceptions} on determination and is never retried by this
 * exception type -- retrying a permanently malformed message produces
 * identical failures and obscures the real signal
 * (.notes/ARCHITECTURE.md §6.2).
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

    public List<FailureDetail> details() {
        return details;
    }
}
