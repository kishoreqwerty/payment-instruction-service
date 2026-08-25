package com.kishore.payments.core.outbox;

import com.kishore.payments.core.event.EventJson;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
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
 * <p>{@code SKIP LOCKED} alone only guarantees disjoint ROWS across
 * concurrent replicas, not disjoint AGGREGATES: two replicas can each claim a
 * different row belonging to the same instruction and produce them in
 * whatever order their own transactions happen to finish in, which breaks
 * the per-instruction ordering .notes/ARCHITECTURE.md §4.2 depends on (proven
 * by {@code OutboxOrderingAcrossReplicasTest} against the pre-fix
 * implementation). {@code pg_try_advisory_xact_lock(hashtext(aggregate_id))}
 * in the select's own predicate closes that: it's a non-blocking, per-session,
 * transaction-scoped lock keyed by aggregate, so once one replica's
 * transaction holds it, every other pending row for that same aggregate is
 * excluded from every other replica's batch -- not just the row that
 * happened to get {@code FOR UPDATE}'d -- until this transaction commits or
 * rolls back. This preserves horizontal scaling (unrelated aggregates still
 * claim and produce fully in parallel across replicas) at the cost of one
 * cheap lock acquisition per candidate row and occasional unrelated
 * aggregates serializing against each other on a {@code hashtext} collision.
 * See .notes/reports/PHASE-4-REPORT.md §5 for why this was chosen over a
 * single active publisher.
 *
 * <p>Sends within one batch are pipelined -- fired in {@code outbox_id}
 * order without waiting for each broker acknowledgement individually, then
 * awaited once at the end -- rather than blocking after every record.
 * Ordering still holds because the producer is configured with
 * {@code acks=all}, {@code enable.idempotence=true} and
 * {@code max.in.flight.requests.per.connection=1} (see
 * {@link OutboxProducerFactory}): the client library itself serializes
 * requests on the wire in call order, so blocking per record bought nothing
 * but latency. Phase 12 tried raising the in-flight limit to 5 to see
 * whether wire-level serialization was the sustained-load bottleneck; it
 * measurably was not (.notes/reports/PHASE-12-REPORT.md §4.3), and the
 * change was reverted rather than kept for a correctness guarantee it
 * bought no throughput for.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    // Depends on idx_outbox_unpublished being keyed on (outbox_id) alone, not
    // (published_at, outbox_id) -- see V6__fix_outbox_unpublished_index.sql for why a
    // published_at leading column, even though constant within this partial index,
    // stopped Postgres's planner from recognising the index as satisfying "ORDER BY
    // outbox_id" at all: with the old index shape this query silently fell back to a
    // primary-key scan that had to walk past every already-published row to reach an
    // unpublished one, a cost that grows with total table history rather than backlog
    // size (found under Phase 12 load testing, PHASE-12-REPORT.md section 4.1).
    private static final String SELECT_BATCH_SQL = "SELECT outbox_id, aggregate_id, topic, partition_key, headers, payload "
            + "FROM core.outbox WHERE published_at IS NULL AND pg_try_advisory_xact_lock(hashtext(aggregate_id::text)) "
            + "ORDER BY outbox_id LIMIT ? FOR UPDATE SKIP LOCKED";

    // A single-row ? form existed here before Phase 12 (.notes/reports/PHASE-12-REPORT.md
    // §4.4) and was called once per successfully-published row, inside the same sequential
    // loop that awaits each row's own Kafka future -- up to batchSize (100) individual JDBC
    // round trips per publishBatch() invocation. Measured directly against
    // payment_outbox_publish_duration_seconds under load: ~41-52ms average per row, implying
    // a full batch's own total processing time ran well past this class's own 50ms poll
    // interval, independent of and in addition to the SELECT-side defect V6 fixed. ANY(?)
    // collapses however many rows a batch actually published into one round trip.
    private static final String MARK_PUBLISHED_SQL = "UPDATE core.outbox SET published_at = now() WHERE outbox_id = ANY(?)";

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
    private final Tracer tracer;
    private final Propagator propagator;
    private final int batchSize;
    private final List<String> knownTopics;
    private final TransactionTemplate transactionTemplate;

    public OutboxPublisher(
            JdbcTemplate jdbc,
            Producer<String, String> producer,
            OutboxMetrics metrics,
            PlatformTransactionManager transactionManager,
            @Nullable Tracer tracer,
            @Nullable Propagator propagator,
            @Value("${payments.outbox.batch-size:100}") int batchSize,
            @Value("${payments.outbox.known-topics:payments.received}") List<String> knownTopics) {
        this.jdbc = jdbc;
        this.producer = producer;
        this.metrics = metrics;
        this.tracer = tracer;
        this.propagator = propagator;
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

            // Fire every send in outbox_id order first, without waiting on
            // any of them individually -- a synchronous throw from the
            // producer (e.g. a serialization error) is captured as a failed
            // future here rather than aborting the rest of the batch, so
            // firing order for the remaining rows is unaffected by an
            // earlier row's outcome.
            List<PendingSend> sends = new ArrayList<>(batch.size());
            for (OutboxRow row : batch) {
                Instant started = Instant.now();
                // A new span, "outbox.publish", as a child of whatever trace was captured into this
                // row at write time (OutboxWriter) -- not just re-forwarding that old header
                // unchanged. This is what makes the write and the publish show up as two connected
                // spans in the same trace, rather than the publish looking like it happened in a
                // vacuum: see this class's own javadoc and .notes/reports/PHASE-10-REPORT.md §3.
                Map<String, Object> headers = parseHeaders(row.headersJson());
                Span publishSpan = startPublishSpan(row, headers);
                Future<RecordMetadata> future;
                try {
                    future = producer.send(toProducerRecord(row, headers, publishSpan));
                } catch (Exception e) {
                    future = CompletableFuture.failedFuture(e);
                }
                sends.add(new PendingSend(row, started, future, publishSpan));
            }

            // Then block once, in the same order, to learn the outcomes --
            // still sequential (ordering and "stop at first failure" both
            // depend on learning outcomes in send order), but marking
            // published is no longer one UPDATE per row: outbox_ids of
            // every row that actually got acked are collected here and
            // marked in one batched round trip below, after this loop ends,
            // rather than once per iteration inside it.
            List<Long> publishedIds = new ArrayList<>(sends.size());
            for (PendingSend send : sends) {
                try {
                    send.future().get();
                    publishedIds.add(send.row().outboxId());
                    metrics.recordPublished(send.row().topic(), true);
                    metrics.recordPublishDuration(Duration.between(send.started(), Instant.now()));
                    endSpan(send.span(), null);
                } catch (Exception e) {
                    // Leave published_at null and stop here for this cycle:
                    // the remaining rows in this batch (including this one)
                    // stay locked until this transaction ends, then
                    // unpublished and eligible for the next cycle. Not
                    // deleted, not moved to a DLQ -- an unpublishable row is
                    // a stuck instruction and should stay visible as one.
                    metrics.recordPublished(send.row().topic(), false);
                    log.warn("Failed to publish outbox row {} to topic {}", send.row().outboxId(), send.row().topic(), e);
                    endSpan(send.span(), e);
                    break;
                }
            }
            markPublished(publishedIds);

            refreshPendingMetrics();
        });
    }

    /**
     * Extracts the {@code traceparent} captured at write time (see {@link
     * OutboxWriter}) and starts a new child span for the act of publishing
     * this specific row, tagged with the instruction it belongs to. Returns
     * {@code null} -- meaning "nothing to attach, forward headers as
     * stored" -- when there is no tracing bridge on this service's
     * classpath, or the row has no captured trace context at all (written
     * before this feature existed, or by a caller with no active span).
     */
    @Nullable
    private Span startPublishSpan(OutboxRow row, Map<String, Object> headers) {
        if (tracer == null || propagator == null) {
            return null;
        }
        Object storedTraceparent = headers.get("traceparent");
        if (storedTraceparent == null) {
            return null;
        }
        Map<String, String> carrier = Map.of("traceparent", String.valueOf(storedTraceparent));
        // The Span.Builder Propagator#extract returns is already parented on the extracted remote
        // context -- this is the builder for the new span, not a separate context to hand to a
        // second builder.
        Span.Builder builder = propagator.extract(carrier, Map::get);
        return builder.name("outbox.publish")
                .tag("instruction_id", String.valueOf(row.aggregateId()))
                .tag("topic", row.topic())
                .start();
    }

    private void endSpan(@Nullable Span span, @Nullable Exception error) {
        if (span == null) {
            return;
        }
        if (error != null) {
            span.error(error);
        }
        span.end();
    }

    private ProducerRecord<String, String> toProducerRecord(OutboxRow row, Map<String, Object> headers, @Nullable Span publishSpan) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.partitionKey(), row.payload());
        for (Map.Entry<String, Object> header : headers.entrySet()) {
            if (header.getKey().equals("traceparent")) {
                continue;
            }
            record.headers().add(header.getKey(), String.valueOf(header.getValue()).getBytes(StandardCharsets.UTF_8));
        }
        // The publish span's own context, not the stored one unchanged: a consumer reading this
        // record becomes a child of "outbox.publish", which is itself a child of the span that
        // wrote the row -- a real chain, not a flat re-broadcast of the original traceparent to
        // every hop downstream regardless of how many outbox round-trips happened in between.
        if (publishSpan != null && tracer != null && propagator != null) {
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(publishSpan.context(), carrier, Map::put);
            String traceparent = carrier.get("traceparent");
            if (traceparent != null) {
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            }
        } else if (headers.get("traceparent") != null) {
            // No tracer on this classpath (or no captured context): fall back to forwarding
            // whatever was stored, unchanged, rather than dropping it -- still better than nothing
            // for a service that isn't itself instrumented but sits between two that are.
            record.headers().add("traceparent", String.valueOf(headers.get("traceparent")).getBytes(StandardCharsets.UTF_8));
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

    private void markPublished(List<Long> outboxIds) {
        if (outboxIds.isEmpty()) {
            return;
        }
        jdbc.update(MARK_PUBLISHED_SQL, (PreparedStatement ps) -> {
            ps.setArray(1, ps.getConnection().createArrayOf("bigint", outboxIds.toArray()));
        });
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

    private record PendingSend(OutboxRow row, Instant started, Future<RecordMetadata> future, @Nullable Span span) {
    }
}
