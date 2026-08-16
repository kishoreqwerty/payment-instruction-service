package com.kishore.payments.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * core.outbox is a shared table across every test in this module (a single
 * static Testcontainers Postgres, per AbstractOutboxIntegrationTest) and
 * nothing truncates it between tests -- other suites rely on unique random
 * topics/keys per test to stay isolated. Retention deletes by age, not by
 * topic, so an unscoped {@code count(*)} here would pick up rows left behind
 * by unrelated tests; every assertion below is scoped to the aggregate_ids
 * this test itself inserted.
 */
class OutboxRetentionCleanerTest extends AbstractOutboxIntegrationTest {

    @Test
    void deletesOnlyPublishedRowsOlderThanRetention() {
        UUID oldPublished = insertOutboxRow(uniqueTopic("old"), UUID.randomUUID().toString(), "{}");
        UUID recentPublished = insertOutboxRow(uniqueTopic("recent"), UUID.randomUUID().toString(), "{}");
        UUID oldUnpublished = insertOutboxRow(uniqueTopic("stuck"), UUID.randomUUID().toString(), "{}");

        markPublishedAt(oldPublished, Instant.now().minus(Duration.ofDays(10)));
        markPublishedAt(recentPublished, Instant.now().minus(Duration.ofHours(1)));
        // oldUnpublished stays published_at IS NULL.

        OutboxMetrics metrics = new OutboxMetrics(new SimpleMeterRegistry(), List.of());
        new OutboxRetentionCleaner(JDBC, metrics, Duration.ofDays(7), 1000).cleanup();

        assertThat(rowExists(oldPublished)).as("published rows older than retention must be deleted").isFalse();
        assertThat(rowExists(recentPublished)).as("published rows within retention must survive").isTrue();
        assertThat(rowExists(oldUnpublished))
                .as("unpublished rows are never deleted regardless of age -- an unpublished row is a stuck instruction, not cleanup debris")
                .isTrue();
    }

    @Test
    void deletionIsBatchedByLimitRatherThanUnbounded() {
        int total = 25;
        int batchSize = 10;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            UUID id = insertOutboxRow(uniqueTopic("batch"), UUID.randomUUID().toString(), "{}");
            markPublishedAt(id, Instant.now().minus(Duration.ofDays(30)));
            ids.add(id);
        }

        OutboxMetrics metrics = new OutboxMetrics(new SimpleMeterRegistry(), List.of());
        OutboxRetentionCleaner cleaner = new OutboxRetentionCleaner(JDBC, metrics, Duration.ofDays(7), batchSize);

        // A DELETE from this cleaner is never scoped to one test's rows --
        // retention deletes by age across the whole table by design -- so a
        // run here may also sweep up eligible rows left behind by other
        // tests in this class. What must hold regardless of that
        // interleaving is the batching guarantee itself: no single run
        // removes more than batchSize of THIS test's own rows, and enough
        // runs eventually clear all of them.
        int remaining = total;
        for (int call = 0; call < 3; call++) {
            cleaner.cleanup();
            int nowRemaining = publishedRemainingAmong(ids);
            assertThat(remaining - nowRemaining)
                    .as("a single run must not delete more than batchSize of this test's own rows")
                    .isBetween(0, batchSize);
            remaining = nowRemaining;
        }
        assertThat(remaining).as("three runs of batchSize 10 must have cleared all 25 rows").isEqualTo(0);
    }

    @Test
    void recordsRowsDeletedPerRunAsAMetric() {
        UUID id = insertOutboxRow(uniqueTopic("metric"), UUID.randomUUID().toString(), "{}");
        markPublishedAt(id, Instant.now().minus(Duration.ofDays(30)));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry, List.of());
        double before = registry.get("payment_outbox_deleted_total").counter().count();

        new OutboxRetentionCleaner(JDBC, metrics, Duration.ofDays(7), 1000).cleanup();

        // Not asserted as exactly 1: this cleanup() call is not scoped to
        // this test's own row (retention deletes by age across the whole
        // table by design), so it may also sweep up old rows left behind by
        // other tests in this class. What must hold regardless is that the
        // counter advanced by at least the one row this test knows it made
        // eligible, and that the row itself is gone.
        assertThat(registry.get("payment_outbox_deleted_total").counter().count()).isGreaterThanOrEqualTo(before + 1.0);
        assertThat(rowExists(id)).isFalse();
    }

    private void markPublishedAt(UUID aggregateId, Instant publishedAt) {
        JDBC.update(
                "UPDATE core.outbox SET published_at = ? WHERE aggregate_id = ?", Timestamp.from(publishedAt), aggregateId);
    }

    private Integer publishedRemainingAmong(List<UUID> ids) {
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return JDBC.queryForObject(
                "SELECT count(*) FROM core.outbox WHERE published_at IS NOT NULL AND aggregate_id IN (" + placeholders + ")",
                Integer.class,
                ids.toArray());
    }

    private boolean rowExists(UUID aggregateId) {
        Integer count = JDBC.queryForObject("SELECT count(*) FROM core.outbox WHERE aggregate_id = ?", Integer.class, aggregateId);
        return count != null && count > 0;
    }

    private static String uniqueTopic(String label) {
        return "test-outbox-" + label + "-" + UUID.randomUUID();
    }
}
