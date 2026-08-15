package com.kishore.payments.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;

class OutboxPublisherTest extends AbstractOutboxIntegrationTest {

    @Test
    void pendingRowIsProducedToTheRightTopicWithTheRightKeyAndMarkedPublished() throws Exception {
        String topic = uniqueTopic("basic");
        String key = UUID.randomUUID().toString();
        UUID aggregateId = insertOutboxRow(topic, key, "{\"hello\":\"world\"}");

        Producer<String, String> producer = newProducer();
        try {
            newPublisher(producer, 100, List.of(topic)).publishBatch();
        } finally {
            producer.close();
        }

        assertThat(isPublished(aggregateId)).isTrue();

        try (Consumer<String, String> consumer = newConsumer("basic-" + UUID.randomUUID())) {
            List<ConsumerRecord<String, String>> records = consumeAll(consumer, topic, 1, Duration.ofSeconds(15));
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo(key);
            assertThat(records.get(0).value()).contains("hello");
        }
    }

    @Test
    void rowThatFailsProductionStaysUnpublishedAndRetriesNextCycle() throws Exception {
        String topic = uniqueTopic("fail");
        UUID aggregateId = insertOutboxRow(topic, UUID.randomUUID().toString(), "{}");

        Producer<String, String> real = newProducer();
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        FaultInjectingProducer faulty = new FaultInjectingProducer(real, rec -> shouldFail.get(), rec -> false);
        OutboxPublisher publisher = newPublisher(faulty, 100, List.of(topic));

        try {
            publisher.publishBatch();
            assertThat(isPublished(aggregateId)).as("failed production must leave the row unpublished").isFalse();

            shouldFail.set(false);
            publisher.publishBatch();
            assertThat(isPublished(aggregateId)).as("the next cycle should retry and succeed").isTrue();
        } finally {
            real.close();
        }
    }

    @Test
    void twoConcurrentPublishersProduceEachRowExactlyOnce() throws Exception {
        String topic = uniqueTopic("concurrent");
        int n = 30;
        for (int i = 0; i < n; i++) {
            insertOutboxRow(topic, UUID.randomUUID().toString(), "{\"i\":" + i + "}");
        }

        Producer<String, String> producerA = newProducer();
        Producer<String, String> producerB = newProducer();
        OutboxPublisher publisherA = newPublisher(producerA, 100, List.of(topic));
        OutboxPublisher publisherB = newPublisher(producerB, 100, List.of(topic));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (OutboxPublisher publisher : List.of(publisherA, publisherB)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    publisher.publishBatch();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
            producerA.close();
            producerB.close();
        }

        Integer publishedCount = JDBC.queryForObject(
                "SELECT count(*) FROM core.outbox WHERE topic = ? AND published_at IS NOT NULL", Integer.class, topic);
        assertThat(publishedCount).as("SKIP LOCKED should let every row be claimed exactly once between the two publishers").isEqualTo(n);

        try (Consumer<String, String> consumer = newConsumer("concurrent-" + UUID.randomUUID())) {
            List<ConsumerRecord<String, String>> records = consumeAll(consumer, topic, n, Duration.ofSeconds(20));
            assertThat(records).hasSize(n);
            Set<String> keys = records.stream().map(ConsumerRecord::key).collect(Collectors.toSet());
            assertThat(keys).as("no key should have been produced twice").hasSize(n);
        }
    }

    @Test
    void killedBetweenProductionAndMarkingProducesADuplicateButEventuallyMarks() throws Exception {
        String topic = uniqueTopic("crash");
        String key = UUID.randomUUID().toString();
        UUID aggregateId = insertOutboxRow(topic, key, "{}");

        Producer<String, String> real = newProducer();
        AtomicBoolean crashOnce = new AtomicBoolean(true);
        FaultInjectingProducer faulty = new FaultInjectingProducer(real, rec -> false, rec -> crashOnce.compareAndSet(true, false));
        OutboxPublisher publisher = newPublisher(faulty, 100, List.of(topic));

        try {
            // "Crashes" after the message has genuinely been produced, before
            // the row is marked -- this is the process-death scenario, not a
            // produce failure.
            publisher.publishBatch();
            assertThat(isPublished(aggregateId)).isFalse();

            // "Restart": the row is still unpublished, so it's re-selected
            // and re-produced. This time nothing crashes.
            publisher.publishBatch();
            assertThat(isPublished(aggregateId)).isTrue();
        } finally {
            real.close();
        }

        try (Consumer<String, String> consumer = newConsumer("crash-" + UUID.randomUUID())) {
            List<ConsumerRecord<String, String>> records = consumeAll(consumer, topic, 2, Duration.ofSeconds(15));
            assertThat(records).as("duplication, not loss: the message the publisher \"crashed\" on is on the topic twice").hasSize(2);
            assertThat(records).allMatch(r -> r.key().equals(key));
        }
    }

    @Test
    void eventsForOneInstructionLandOnOnePartitionInOutboxIdOrder() throws Exception {
        String topic = uniqueTopic("order");
        createTopic(topic, 6);

        String key = UUID.randomUUID().toString();
        int count = 5;
        for (int i = 0; i < count; i++) {
            insertOutboxRow(topic, key, "{\"seq\":" + i + "}");
        }

        Producer<String, String> producer = newProducer();
        try {
            newPublisher(producer, 100, List.of(topic)).publishBatch();
        } finally {
            producer.close();
        }

        try (Consumer<String, String> consumer = newConsumer("order-" + UUID.randomUUID())) {
            List<ConsumerRecord<String, String>> records = consumeAll(consumer, topic, count, Duration.ofSeconds(15));
            assertThat(records).hasSize(count);

            Set<Integer> partitions = records.stream().map(ConsumerRecord::partition).collect(Collectors.toSet());
            assertThat(partitions).as("all events for the same key must land on one partition").hasSize(1);

            List<Integer> orderedSeqs = records.stream()
                    .sorted(Comparator.comparingLong(ConsumerRecord::offset))
                    .map(r -> extractSeq(r.value()))
                    .toList();
            assertThat(orderedSeqs).as("must match outbox_id insertion order").containsExactly(0, 1, 2, 3, 4);
        }
    }

    @SuppressWarnings("unchecked")
    private static int extractSeq(String jsonPayload) {
        try {
            java.util.Map<String, Object> parsed = com.kishore.payments.core.event.EventJson.MAPPER.readValue(jsonPayload, java.util.Map.class);
            return (Integer) parsed.get("seq");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Boolean isPublished(UUID aggregateId) {
        return JDBC.queryForObject("SELECT published_at IS NOT NULL FROM core.outbox WHERE aggregate_id = ?", Boolean.class, aggregateId);
    }

    private static String uniqueTopic(String label) {
        return "test-outbox-" + label + "-" + UUID.randomUUID();
    }
}
