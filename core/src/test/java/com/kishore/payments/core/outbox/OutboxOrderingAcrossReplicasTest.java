package com.kishore.payments.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * SKIP LOCKED guarantees disjoint ROWS across concurrent publishers, not
 * disjoint AGGREGATES. Two replicas racing over one instruction's several
 * pending outbox rows can produce a later row before an earlier one that is
 * merely locked -- still mid-transaction on the other replica -- which
 * reorders events for a single instruction on the topic.
 *
 * <p>This test does not rely on scheduling luck to expose that: replica A is
 * made to claim only the earliest row (batch size 1, so ORDER BY outbox_id
 * ASC ... LIMIT 1 grabs it deterministically) and then blocks mid-send,
 * still holding that row's lock. Replica B is then run to completion while
 * A is blocked, so it is forced to SKIP the locked row and produce the rest
 * of the aggregate's rows instead. Only once B has finished is A released.
 */
class OutboxOrderingAcrossReplicasTest extends AbstractOutboxIntegrationTest {

    @Test
    void deliveryOrderForOneAggregateMatchesSequenceNoAcrossConcurrentPublishers() throws Exception {
        String topic = uniqueTopic("ordering");
        createTopic(topic, 6);

        UUID instructionId = UUID.randomUUID();
        String key = instructionId.toString();
        int count = 5;
        for (int seq = 0; seq < count; seq++) {
            insertOutboxRow(instructionId, topic, key, "{\"sequence_no\":" + seq + "}");
        }

        CountDownLatch replicaAHoldingLock = new CountDownLatch(1);
        CountDownLatch releaseReplicaA = new CountDownLatch(1);
        BlockingOnFirstSendProducer producerA = new BlockingOnFirstSendProducer(newProducer(), replicaAHoldingLock, releaseReplicaA);
        Producer<String, String> producerB = newProducer();

        // batchSize=1 makes replica A's claim deterministic: ORDER BY
        // outbox_id ASC ... LIMIT 1 FOR UPDATE SKIP LOCKED always takes the
        // row with sequence_no=0, the earliest pending row for this
        // aggregate, and holds its row lock for the rest of A's transaction.
        OutboxPublisher replicaA = newPublisher(producerA, 1, List.of(topic));
        OutboxPublisher replicaB = newPublisher(producerB, 100, List.of(topic));

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<?> replicaAResult = pool.submit(replicaA::publishBatch);

            // Wait until replica A has selected and locked sequence_no=0 and
            // is blocked trying to send it, before letting replica B run --
            // this is what forces the split instead of leaving it to chance.
            assertThat(replicaAHoldingLock.await(15, TimeUnit.SECONDS))
                    .as("replica A should have claimed and locked the first row by now")
                    .isTrue();

            // Pre-fix, replica B's SELECT ... SKIP LOCKED only skips the one
            // physical row replica A holds and happily produces
            // sequence_no=1..4 out from under it. Post-fix, the advisory
            // lock is keyed on the whole aggregate, so replica B should
            // claim nothing here -- proven below rather than assumed.
            replicaB.publishBatch();
            assertThat(publishedCountFor(instructionId))
                    .as("replica B must not be able to claim any row for an aggregate replica A is still mid-transaction on")
                    .isZero();

            releaseReplicaA.countDown();
            replicaAResult.get(15, TimeUnit.SECONDS);

            // Once replica A's transaction ends, the aggregate is free again;
            // a follow-up poll is what a real deployment's next scheduled
            // cycle would do to pick up the rows that were held back.
            replicaB.publishBatch();
        } finally {
            pool.shutdown();
            producerA.close();
            producerB.close();
        }

        try (Consumer<String, String> consumer = newConsumer("ordering-" + UUID.randomUUID())) {
            List<ConsumerRecord<String, String>> records = consumeAll(consumer, topic, count, Duration.ofSeconds(15));
            assertThat(records).hasSize(count);

            List<Integer> deliveryOrder = records.stream()
                    .sorted(Comparator.comparingLong(ConsumerRecord::offset))
                    .map(r -> extractSequenceNo(r.value()))
                    .toList();

            assertThat(deliveryOrder)
                    .as("delivery order on the partition must match sequence_no ascending, even under two concurrent publishers")
                    .containsExactly(0, 1, 2, 3, 4);
        }
    }

    /**
     * The three-stage pipeline (Phase 4) writes one outbox row per stage
     * transition, each to a different topic (payments.validated,
     * payments.enriched, payments.routed) but all sharing the same
     * aggregate_id (the instruction_id). This is exactly what makes multiple
     * pending outbox rows per aggregate possible in practice: if a fast
     * consumer advances an instruction through two stages before the next
     * 50ms publish cycle runs, that aggregate now has pending rows on two
     * different topics simultaneously. The fix in OutboxPublisher's WHERE
     * clause is keyed purely on aggregate_id, not (aggregate_id, topic), so
     * it must exclude a locked aggregate's rows on every topic, not just the
     * topic the locked row happens to be on -- proven here the same way as
     * the single-topic case: replica B must claim nothing while replica A
     * holds the earliest row, even though the remaining two rows sit on
     * topics replica A's own claim never touched.
     */
    @Test
    void perAggregateLockExcludesEveryTopicNotJustTheLockedRowsOwnTopic() throws Exception {
        String validatedTopic = uniqueTopic("validated");
        String enrichedTopic = uniqueTopic("enriched");
        String routedTopic = uniqueTopic("routed");
        createTopic(validatedTopic, 3);
        createTopic(enrichedTopic, 3);
        createTopic(routedTopic, 3);
        List<String> allTopics = List.of(validatedTopic, enrichedTopic, routedTopic);

        UUID instructionId = UUID.randomUUID();
        String key = instructionId.toString();
        // Ascending outbox_id, one row per stage topic -- the earliest is on
        // validatedTopic, so that's the one replica A will claim with
        // batchSize=1.
        insertOutboxRow(instructionId, validatedTopic, key, "{\"stage\":\"validated\"}");
        insertOutboxRow(instructionId, enrichedTopic, key, "{\"stage\":\"enriched\"}");
        insertOutboxRow(instructionId, routedTopic, key, "{\"stage\":\"routed\"}");

        CountDownLatch replicaAHoldingLock = new CountDownLatch(1);
        CountDownLatch releaseReplicaA = new CountDownLatch(1);
        BlockingOnFirstSendProducer producerA = new BlockingOnFirstSendProducer(newProducer(), replicaAHoldingLock, releaseReplicaA);
        Producer<String, String> producerB = newProducer();

        OutboxPublisher replicaA = newPublisher(producerA, 1, allTopics);
        OutboxPublisher replicaB = newPublisher(producerB, 100, allTopics);

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<?> replicaAResult = pool.submit(replicaA::publishBatch);

            assertThat(replicaAHoldingLock.await(15, TimeUnit.SECONDS))
                    .as("replica A should have claimed and locked the validatedTopic row by now")
                    .isTrue();

            replicaB.publishBatch();
            assertThat(publishedCountFor(instructionId))
                    .as("replica B must claim none of this aggregate's rows, even the ones on topics replica A never touched")
                    .isZero();

            releaseReplicaA.countDown();
            replicaAResult.get(15, TimeUnit.SECONDS);

            replicaB.publishBatch();
        } finally {
            pool.shutdown();
            producerA.close();
            producerB.close();
        }

        assertThat(publishedCountFor(instructionId)).as("all three stage rows eventually publish once the aggregate is free").isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private static int extractSequenceNo(String jsonPayload) {
        try {
            java.util.Map<String, Object> parsed =
                    com.kishore.payments.core.event.EventJson.MAPPER.readValue(jsonPayload, java.util.Map.class);
            return (Integer) parsed.get("sequence_no");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String uniqueTopic(String label) {
        return "test-outbox-" + label + "-" + UUID.randomUUID();
    }

    private Integer publishedCountFor(UUID aggregateId) {
        return JDBC.queryForObject(
                "SELECT count(*) FROM core.outbox WHERE aggregate_id = ? AND published_at IS NOT NULL", Integer.class, aggregateId);
    }

    /**
     * Delegates every send immediately except the first one, which signals
     * {@code holding} (so the test knows the row backing it is locked) and
     * then blocks until {@code release} counts down before delegating.
     */
    private static class BlockingOnFirstSendProducer implements Producer<String, String> {

        private final Producer<String, String> delegate;
        private final CountDownLatch holding;
        private final CountDownLatch release;
        private volatile boolean first = true;

        BlockingOnFirstSendProducer(Producer<String, String> delegate, CountDownLatch holding, CountDownLatch release) {
            this.delegate = delegate;
            this.holding = holding;
            this.release = release;
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record) {
            if (first) {
                first = false;
                holding.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release replica A in time");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return delegate.send(record);
        }

        @Override
        public Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
            throw new UnsupportedOperationException("not used by OutboxPublisher");
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public List<PartitionInfo> partitionsFor(String topic) {
            return delegate.partitionsFor(topic);
        }

        @Override
        public java.util.Map<MetricName, ? extends Metric> metrics() {
            return delegate.metrics();
        }

        @Override
        public void initTransactions() {
            delegate.initTransactions();
        }

        @Override
        public void beginTransaction() {
            delegate.beginTransaction();
        }

        @Override
        public void sendOffsetsToTransaction(
                java.util.Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets, String consumerGroupId) {
            delegate.sendOffsetsToTransaction(offsets, consumerGroupId);
        }

        @Override
        public void sendOffsetsToTransaction(
                java.util.Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets,
                org.apache.kafka.clients.consumer.ConsumerGroupMetadata groupMetadata) {
            delegate.sendOffsetsToTransaction(offsets, groupMetadata);
        }

        @Override
        public void commitTransaction() {
            delegate.commitTransaction();
        }

        @Override
        public void abortTransaction() {
            delegate.abortTransaction();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public void close(Duration timeout) {
            delegate.close(timeout);
        }
    }
}
