package com.kishore.payments.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxWriterTest extends AbstractOutboxIntegrationTest {

    private final OutboxWriter writer = new OutboxWriter(JDBC, null, null);

    @Test
    void writesAllFieldsAndLeavesPublishedAtNull() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        Map<String, Object> headers = OutboxHeaders.of("TestEvent", 1, OffsetDateTime.now());
        Map<String, Object> payload = Map.of("foo", "bar", "n", 42);

        writer.write(new OutboxMessage(aggregateId, "payments.received", aggregateId.toString(), headers, payload));

        Map<String, Object> row = JDBC.queryForMap("SELECT * FROM core.outbox WHERE aggregate_id = ?", aggregateId);
        assertThat(row.get("topic")).isEqualTo("payments.received");
        assertThat(row.get("partition_key")).isEqualTo(aggregateId.toString());
        assertThat(row.get("published_at")).isNull();

        Map<String, Object> storedHeaders = com.kishore.payments.core.event.EventJson.MAPPER.readValue(row.get("headers").toString(), Map.class);
        assertThat(storedHeaders).containsEntry("eventType", "TestEvent").containsEntry("eventVersion", 1);

        Map<String, Object> storedPayload = com.kishore.payments.core.event.EventJson.MAPPER.readValue(row.get("payload").toString(), Map.class);
        assertThat(storedPayload).containsEntry("foo", "bar").containsEntry("n", 42);
    }

    @Test
    void omitsTraceparentWhenAbsent() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        Map<String, Object> headers = OutboxHeaders.of("TestEvent", 1, OffsetDateTime.now());

        writer.write(new OutboxMessage(aggregateId, "payments.received", aggregateId.toString(), headers, Map.of()));

        Map<String, Object> row = JDBC.queryForMap("SELECT headers FROM core.outbox WHERE aggregate_id = ?", aggregateId);
        Map<String, Object> storedHeaders = com.kishore.payments.core.event.EventJson.MAPPER.readValue(row.get("headers").toString(), Map.class);
        assertThat(storedHeaders).doesNotContainKey("traceparent");
    }
}
