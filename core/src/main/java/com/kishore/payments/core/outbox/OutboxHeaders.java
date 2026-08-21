package com.kishore.payments.core.outbox;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The standard header shape every outbox row carries, so every producer
 * builds the same envelope.
 *
 * <p>No {@code traceparent} parameter: every one of this method's ~15 call
 * sites across the codebase passed a literal {@code null} for it (nobody
 * had a trace context to hand it), so {@link OutboxWriter#write} now
 * captures the current trace context itself, at the one place every outbox
 * row is actually persisted, rather than asking every caller to plumb it
 * through by hand. See {@link OutboxWriter}'s own javadoc and
 * .notes/reports/PHASE-10-REPORT.md §3.
 */
public final class OutboxHeaders {

    private OutboxHeaders() {
    }

    public static Map<String, Object> of(String eventType, int eventVersion, OffsetDateTime occurredAt) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("eventType", eventType);
        headers.put("eventVersion", eventVersion);
        headers.put("occurredAt", occurredAt);
        return headers;
    }
}
