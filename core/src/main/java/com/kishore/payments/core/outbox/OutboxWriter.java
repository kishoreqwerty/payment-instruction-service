package com.kishore.payments.core.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kishore.payments.core.event.EventJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Inserts a row into core.outbox. Deliberately has no transactional boundary
 * of its own: it must run inside whatever transaction the caller already has
 * open (the same one writing payment_instruction and instruction_event), so
 * that the outbox row exists if and only if the state change it describes
 * committed. Uses plain JdbcTemplate rather than a JPA entity so it
 * participates in that transaction via the same DataSource regardless of
 * whether the caller's own writes are JPA- or JDBC-based.
 */
@Component
public class OutboxWriter {

    private static final String INSERT_SQL = "INSERT INTO core.outbox "
            + "(aggregate_id, topic, partition_key, headers, payload) "
            + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb)";

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(OutboxMessage message) {
        String headersJson = toJson(message.headers());
        String payloadJson = toJson(message.payload());
        jdbc.update(INSERT_SQL, message.aggregateId(), message.topic(), message.partitionKey(), headersJson, payloadJson);
    }

    private static String toJson(Object value) {
        try {
            return EventJson.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox message content", e);
        }
    }
}
