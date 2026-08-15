package com.kishore.payments.core.outbox;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** The standard header shape every outbox row carries, so every producer builds the same envelope. */
public final class OutboxHeaders {

    private OutboxHeaders() {
    }

    public static Map<String, Object> of(String eventType, int eventVersion, OffsetDateTime occurredAt, String traceparent) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("eventType", eventType);
        headers.put("eventVersion", eventVersion);
        headers.put("occurredAt", occurredAt);
        if (traceparent != null) {
            headers.put("traceparent", traceparent);
        }
        return headers;
    }
}
