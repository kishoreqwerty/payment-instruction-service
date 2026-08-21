package com.kishore.payments.processing.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;

/**
 * enable.auto.commit=false and MANUAL ack mode: every listener acknowledges
 * explicitly, and only after its transaction has committed (see each
 * consumer class) -- never before, which is what would let a crash between
 * commit and ack look like loss instead of the harmless redelivery the
 * idempotency check already handles.
 *
 * <p>Retry policy per .notes/ARCHITECTURE.md §6.2: five attempts, 1s/2s/4s/8s/16s
 * backoff, then dead-letter to payments.dlq. This only ever fires for an
 * exception that escapes a listener method uncaught -- by construction,
 * that is only {@link com.kishore.payments.processing.failure.TransientFailureException}
 * and genuine bugs; {@link com.kishore.payments.processing.failure.BusinessFailureException}
 * is always caught inside the listener and turned into an EXCEPTION-state
 * transition on first occurrence, never retried.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, String> consumerFactory(
            @Value("${payments.kafka.bootstrap-servers}") String bootstrapServers, @Value("${payments.kafka.consumer-group}") String groupId,
            MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        // Phase 10: this factory is built by hand rather than by KafkaAutoConfiguration (see the
        // observation-enabled comment below), so the MicrometerConsumerListener that
        // auto-configuration would otherwise wire in for free has to be added explicitly too --
        // without it, none of the Kafka client's own consumer-lag metrics (kafka.consumer.fetch
        // .manager.records.lag, tagged by topic and partition) reach the MeterRegistry at all,
        // and "consumer lag by topic" (the pipeline-health dashboard) and the "DLQ non-empty" /
        // "consumer lag growth" alert rules would all have no series to evaluate.
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory(
            @Value("${payments.kafka.bootstrap-servers}") String bootstrapServers, MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${payments.kafka.dlq-topic:payments.dlq}") String dlqTopic,
            @Value("${payments.kafka.dlq-partitions:3}") int dlqPartitions,
            MeterRegistry meterRegistry) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // Phase 10 (.notes/reports/PHASE-10-REPORT.md section 3): a manually built factory, not the
        // one spring-boot-starter's KafkaAutoConfiguration would supply from spring.kafka.* -- that
        // property namespace does nothing here, since this bean is never routed through
        // autoconfiguration at all. Observation has to be turned on explicitly, on this factory,
        // for a @KafkaListener invocation to extract the traceparent header OutboxPublisher wrote
        // and continue that trace as a child span for the whole listener method.
        factory.getContainerProperties().setObservationEnabled(true);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(dlqTopic, Math.floorMod(record.key().hashCode(), dlqPartitions)));

        // Phase 10 (.notes/reports/PHASE-10-REPORT.md section 5): payments.dlq has no consumer of
        // its own -- by design, nothing automatically drains it, a human investigates -- so Kafka
        // consumer-group lag (which tracks a consumer's own read progress, and would self-heal the
        // instant any consumer polled past a new arrival, DLQ-watching or not) cannot express "the
        // DLQ has a message nobody has dealt with." A plain arrival counter can: pre-registered at
        // zero here (Micrometer's own counter() call does that), incremented once per record this
        // recoverer actually sends to payments.dlq, feeding the "DLQ non-empty" alert as
        // increase(payment_dlq_messages_total[...]) > 0 rather than a lag query.
        Counter dlqMessages = meterRegistry.counter("payment_dlq_messages_total");
        ConsumerRecordRecoverer countingRecoverer = (record, exception) -> {
            dlqMessages.increment();
            recoverer.accept(record, exception);
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(16_000L);

        factory.setCommonErrorHandler(new DefaultErrorHandler(countingRecoverer, backOff));
        return factory;
    }
}
