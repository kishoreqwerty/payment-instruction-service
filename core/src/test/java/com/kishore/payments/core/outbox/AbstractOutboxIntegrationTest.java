package com.kishore.payments.core.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * core has no Spring Boot context of its own, so these tests build the small
 * amount of plain-Spring wiring OutboxPublisher needs (a DataSource, a
 * transaction manager) directly, the same way a hosting Boot service's
 * autoconfiguration would.
 */
abstract class AbstractOutboxIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");

    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4");

    static final DriverManagerDataSource DATA_SOURCE = new DriverManagerDataSource();

    static final JdbcTemplate JDBC = new JdbcTemplate(DATA_SOURCE);

    static final PlatformTransactionManager TRANSACTION_MANAGER = new DataSourceTransactionManager(DATA_SOURCE);

    static {
        POSTGRES.start();
        REDPANDA.start();

        DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    OutboxPublisher newPublisher(Producer<String, String> producer, int batchSize) {
        return newPublisher(producer, batchSize, List.of("payments.received"));
    }

    OutboxPublisher newPublisher(Producer<String, String> producer, int batchSize, List<String> knownTopics) {
        return new OutboxPublisher(
                JDBC, producer, new OutboxMetrics(new SimpleMeterRegistry(), knownTopics), TRANSACTION_MANAGER, batchSize, knownTopics);
    }

    Producer<String, String> newProducer() {
        return OutboxProducerFactory.create(REDPANDA.getBootstrapServers());
    }

    void createTopic(String topic, int partitions) throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        try (Admin admin = Admin.create(props)) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        }
    }

    Consumer<String, String> newConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    /** Insert an outbox row directly, bypassing OutboxWriter, for tests that only care about the publisher's read side. */
    UUID insertOutboxRow(String topic, String partitionKey, String payload) {
        UUID aggregateId = UUID.randomUUID();
        insertOutboxRow(aggregateId, topic, partitionKey, payload);
        return aggregateId;
    }

    /** Same as {@link #insertOutboxRow(String, String, String)}, but for tests that need several rows to share one aggregate_id. */
    void insertOutboxRow(UUID aggregateId, String topic, String partitionKey, String payload) {
        JDBC.update(
                "INSERT INTO core.outbox (aggregate_id, topic, partition_key, headers, payload) VALUES (?, ?, ?, '{}'::jsonb, ?::jsonb)",
                aggregateId,
                topic,
                partitionKey,
                payload);
    }

    List<ConsumerRecord<String, String>> consumeAll(Consumer<String, String> consumer, String topic, int expectedCount, Duration timeout) {
        consumer.subscribe(List.of(topic));
        List<ConsumerRecord<String, String>> records = new java.util.ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (records.size() < expectedCount && System.nanoTime() < deadline) {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
            polled.forEach(records::add);
        }
        return records;
    }
}
