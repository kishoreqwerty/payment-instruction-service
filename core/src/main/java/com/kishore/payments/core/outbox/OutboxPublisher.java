package com.kishore.payments.core.outbox;

import com.kishore.payments.core.event.EventJson;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls core.outbox and produces unpublished rows to Kafka. One instance runs
 * inside every service that writes to an outbox; {@code SKIP LOCKED} is what
 * lets several replicas of the same service run this concurrently without
 * either processing the same row twice or blocking each other on rows they
 * don't hold.
 *
 * <p>The whole poll-produce-mark cycle for a batch is one transaction (see
 * {@link #publishBatch()}): the {@code FOR UPDATE SKIP LOCKED} select's row
 * locks must still be held while each row is produced and marked, otherwise
 * a second replica could pick up a row this one is mid-flight on the moment
 * the select's own transaction ended.
 *
 * <p>Rows are produced strictly in {@code outbox_id} order, awaiting each
 * broker acknowledgement before producing the next, rather than firing the
 * whole batch concurrently. Kafka only guarantees ordering within a
 * partition; the partition key here is {@code instruction_id}, so two events
 * for the same instruction produced out of order by two overlapping batches
 * would break the per-instruction ordering .notes/ARCHITECTURE.md §4.2
 * depends on. Synchronous in-order production costs throughput -- see
 * .notes/reports/PHASE-3-REPORT.md §5 for the trade-off and what would make
 * it worth revisiting.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final String SELECT_BATCH_SQL = "SELECT outbox_id, aggregate_id, topic, partition_key, headers, payload "
            + "FROM core.outbox WHERE published_at IS NULL ORDER BY outbox_id LIMIT ? FOR UPDATE SKIP LOCKED";

    private static final String MARK_PUBLISHED_SQL = "UPDATE core.outbox SET published_at = now() WHERE outbox_id = ?";

    private static final String PENDING_SQL = "SELECT topic, count(*) AS pending_count, "
            + "extract(epoch FROM (now() - min(created_at))) AS oldest_seconds "
            + "FROM core.outbox WHERE published_at IS NULL GROUP BY topic";

    private static final RowMapper<OutboxRow> ROW_MAPPER = (ResultSet rs, int rowNum) -> new OutboxRow(
            rs.getLong("outbox_id"),
            (UUID) rs.getObject("aggregate_id"),
            rs.getString("topic"),
            rs.getString("partition_key"),
            rs.getString("headers"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;
    private final Producer<String, String> producer;
    private final OutboxMetrics metrics;
    private final int batchSize;
    private final List<String> knownTopics;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(
            JdbcTemplate jdbc,
            Producer<String, String> producer,
            OutboxMetrics metrics,
            PlatformTransactionManager transactionManager,
            @Value("${payments.outbox.batch-size:100}") int batchSize,
            @Value("${payments.outbox.known-topics:payments.received}") List<String> knownTopics) {
        this.jdbc = jdbc;
        this.producer = producer;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.knownTopics = knownTopics;
        // A TransactionTemplate managed explicitly here, rather than
        // @Transactional on this method, on purpose: @Transactional only
        // takes effect through a Spring AOP proxy, which exists when Spring
        // constructs this bean via component scan but not when it's built
        // directly (as core's own tests do, since core has no Spring Boot
        // context of its own). Managing the transaction in code makes the
        // FOR UPDATE SKIP LOCKED lock genuinely span the produce-and-mark
        // loop regardless of how this class is instantiated.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 50)
    public void publishBatch() {
        transactionTemplate.executeWithoutResult(status -> {
            List<OutboxRow> batch = jdbc.query(SELECT_BATCH_SQL, ROW_MAPPER, batchSize);

            for (OutboxRow row : batch) {
                Instant started = Instant.now();
                try {
                    producer.send(toProducerRecord(row)).get();
                    jdbc.update(MARK_PUBLISHED_SQL, row.outboxId());
                    metrics.recordPublished(row.topic(), true);
                    metrics.recordPublishDuration(Duration.between(started, Instant.now()));
                } catch (Exception e) {
                    // Leave published_at null and stop here for this cycle:
                    // the remaining rows in this batch (including this one)
                    // stay locked until this transaction ends, then
                    // unpublished and eligible for the next cycle. Not
                    // deleted, not moved to a DLQ -- an unpublishable row is
                    // a stuck instruction and should stay visible as one.
                    metrics.recordPublished(row.topic(), false);
                    log.warn("Failed to publish outbox row {} to topic {}", row.outboxId(), row.topic(), e);
                    break;
                }
            }

            refreshPendingMetrics();
        });
    }

    private static ProducerRecord<String, String> toProducerRecord(OutboxRow row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.partitionKey(), row.payload());
        for (Map.Entry<String, Object> header : parseHeaders(row.headersJson()).entrySet()) {
            record.headers().add(header.getKey(), String.valueOf(header.getValue()).getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseHeaders(String headersJson) {
        try {
            return EventJson.MAPPER.readValue(headersJson, Map.class);
        } catch (Exception e) {
            log.warn("Could not parse outbox headers JSON, producing without them: {}", headersJson, e);
            return Map.of();
        }
    }

    private void refreshPendingMetrics() {
        Map<String, long[]> byTopic = new HashMap<>();
        jdbc.query(PENDING_SQL, rs -> {
            byTopic.put(rs.getString("topic"), new long[] {rs.getLong("pending_count"), rs.getLong("oldest_seconds")});
        });

        for (String topic : knownTopics) {
            long[] values = byTopic.get(topic);
            if (values == null) {
                metrics.clearPending(topic);
            } else {
                metrics.setPending(topic, values[0]);
                metrics.setOldestPendingSeconds(topic, values[1]);
            }
        }
    }

    private record OutboxRow(long outboxId, UUID aggregateId, String topic, String partitionKey, String headersJson, String payload) {
    }
}
