package com.kishore.payments.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;

/**
 * Not the Phase 12 load test -- a sanity baseline for the synchronous
 * in-order production trade-off recorded in
 * .notes/reports/PHASE-3-REPORT.md §5, to compare against if that decision
 * gets revisited.
 */
class OutboxLoadSanityTest extends AbstractOutboxIntegrationTest {

    private static final int TOTAL = 5000;

    @Test
    void publishes5000RowsWithNoDuplicates() throws Exception {
        String topic = "test-outbox-load-" + UUID.randomUUID();

        List<Object[]> rows = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            rows.add(new Object[] {UUID.randomUUID(), topic, UUID.randomUUID().toString(), "{\"i\":" + i + "}"});
        }
        JDBC.batchUpdate(
                "INSERT INTO core.outbox (aggregate_id, topic, partition_key, headers, payload) VALUES (?, ?, ?, '{}'::jsonb, ?::jsonb)",
                rows);

        Producer<String, String> producer = newProducer();
        OutboxPublisher publisher = newPublisher(producer, 100, List.of(topic));

        Instant started = Instant.now();
        int cycles = 0;
        while (true) {
            Integer pending = JDBC.queryForObject(
                    "SELECT count(*) FROM core.outbox WHERE topic = ? AND published_at IS NULL", Integer.class, topic);
            if (pending == 0) {
                break;
            }
            publisher.publishBatch();
            cycles++;
            if (cycles > TOTAL) {
                fail("publisher did not converge after " + cycles + " cycles");
            }
        }
        Duration elapsed = Duration.between(started, Instant.now());
        producer.close();

        System.out.printf(
                "OutboxLoadSanityTest: published %d rows in %d cycles, wall-clock %d ms (%.1f rows/sec)%n",
                TOTAL, cycles, elapsed.toMillis(), TOTAL / Math.max(elapsed.toMillis() / 1000.0, 0.001));

        Integer publishedCount = JDBC.queryForObject(
                "SELECT count(*) FROM core.outbox WHERE topic = ? AND published_at IS NOT NULL", Integer.class, topic);
        assertThat(publishedCount).isEqualTo(TOTAL);

        try (Consumer<String, String> consumer = newConsumer("load-" + UUID.randomUUID())) {
            List<ConsumerRecord<String, String>> records = consumeAll(consumer, topic, TOTAL, Duration.ofSeconds(60));
            assertThat(records).hasSize(TOTAL);
            Set<String> keys = records.stream().map(ConsumerRecord::key).collect(Collectors.toSet());
            assertThat(keys).as("no row should have been produced more than once").hasSize(TOTAL);
        }
    }
}
