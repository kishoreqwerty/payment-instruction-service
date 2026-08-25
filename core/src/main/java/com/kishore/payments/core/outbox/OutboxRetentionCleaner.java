package com.kishore.payments.core.outbox;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes published outbox rows older than the retention window. Published
 * rows have already done their job (produced to Kafka, at-least-once
 * delivery discharged) and exist afterward only for operational visibility;
 * left unpruned, core.outbox grows without bound forever.
 *
 * <p>Each run deletes at most {@code batchSize} rows in a single statement
 * rather than every eligible row at once, so a service that has fallen far
 * behind on cleanup (or is running this for the first time against a large
 * backlog) never holds a lock across an unbounded delete -- it catches up
 * over several short runs instead of one long one.
 *
 * <p>Unpublished rows ({@code published_at IS NULL}) are never touched here
 * regardless of age: an outbox row that never got produced is a stuck
 * instruction, not cleanup debris, and {@link OutboxPublisher} is the only
 * thing that should ever cause one to leave the table.
 *
 * <p>Defaults changed in Phase 12 (.notes/reports/PHASE-12-REPORT.md section 4.2): the
 * original 1,000-rows-per-60-seconds cadence deletes at most ~16.7 published rows/sec --
 * independently of the read-side query-plan defect V6__fix_outbox_unpublished_index.sql
 * fixes, this cleanup throughput is well below the 500/sec sustained target §1.3 sets for
 * the whole pipeline, so at steady-state target volume core.outbox would grow without
 * bound purely from published rows outpacing their own deletion, even with the read
 * query fixed. 2,000 rows every 2 seconds (~1,000/sec) gives roughly 2x headroom over the
 * 500/sec target rather than a fraction of it.
 */
public class OutboxRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionCleaner.class);

    private static final String DELETE_BATCH_SQL = "DELETE FROM core.outbox WHERE outbox_id IN ("
            + "SELECT outbox_id FROM core.outbox WHERE published_at IS NOT NULL AND published_at < ? "
            + "ORDER BY outbox_id LIMIT ?)";

    private final JdbcTemplate jdbc;
    private final OutboxMetrics metrics;
    private final Duration retention;
    private final int batchSize;

    public OutboxRetentionCleaner(
            JdbcTemplate jdbc,
            OutboxMetrics metrics,
            @Value("${payments.outbox.retention:7d}") Duration retention,
            @Value("${payments.outbox.retention-batch-size:2000}") int batchSize) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelay = 2_000)
    public void cleanup() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retention));
        int deleted = jdbc.update(DELETE_BATCH_SQL, cutoff, batchSize);
        metrics.recordDeleted(deleted);
        if (deleted > 0) {
            log.info("Deleted {} published outbox row(s) older than {}", deleted, retention);
        }
    }
}
