package com.kishore.payments.core.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kishore.payments.core.event.EventJson;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Inserts a row into core.outbox. Deliberately has no transactional boundary
 * of its own: it must run inside whatever transaction the caller already has
 * open (the same one writing payment_instruction and instruction_event), so
 * that the outbox row exists if and only if the state change it describes
 * committed. Uses plain JdbcTemplate rather than a JPA entity so it
 * participates in that transaction via the same DataSource regardless of
 * whether the caller's own writes are JPA- or JDBC-based.
 *
 * <p>Also where trace context is captured, not at each of the ~15 call
 * sites that build an {@link OutboxMessage} (see {@link OutboxHeaders}'s own
 * javadoc for why): whatever HTTP request or Kafka listener invocation is
 * currently in progress when a row is written is the trace this event
 * belongs to, and {@code write} is the one place that's true for every
 * producer in the system. {@link OutboxPublisher} is the other half --
 * restoring this context, later, on a different thread, when the row is
 * actually sent to Kafka.
 */
@Component
public class OutboxWriter {

    private static final String INSERT_SQL = "INSERT INTO core.outbox "
            + "(aggregate_id, topic, partition_key, headers, payload) "
            + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb)";

    private final JdbcTemplate jdbc;
    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxWriter(JdbcTemplate jdbc, @Nullable Tracer tracer, @Nullable Propagator propagator) {
        this.jdbc = jdbc;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void write(OutboxMessage message) {
        Map<String, Object> headers = new LinkedHashMap<>(message.headers());
        String traceparent = currentTraceparent();
        if (traceparent != null) {
            headers.put("traceparent", traceparent);
        }
        String headersJson = toJson(headers);
        String payloadJson = toJson(message.payload());
        jdbc.update(INSERT_SQL, message.aggregateId(), message.topic(), message.partitionKey(), headersJson, payloadJson);
    }

    /**
     * Null whenever there is no tracing bridge on this service's classpath
     * (a {@code @Nullable} bean, so tests that build this class directly
     * with no Spring context at all still work unchanged) or no span is
     * currently active (e.g. a {@code @Scheduled} caller with nothing to
     * capture). Otherwise the W3C {@code traceparent} string for whatever
     * span is in progress -- the inbound HTTP request or the Kafka listener
     * invocation this write happened inside of.
     */
    @Nullable
    private String currentTraceparent() {
        if (tracer == null || propagator == null) {
            return null;
        }
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier.get("traceparent");
    }

    private static String toJson(Object value) {
        try {
            return EventJson.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox message content", e);
        }
    }
}
