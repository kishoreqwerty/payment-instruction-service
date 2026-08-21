package com.kishore.payments.gateway.consumer;

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
 * Same shape as processing-service's KafkaConsumerConfig: manual ack, five
 * attempts / 1s-2s-4s-8s-16s backoff / then payments.dlq for whatever
 * escapes a listener method uncaught. This only ever fires for genuinely
 * environmental failures ({@link com.kishore.payments.gateway.failure.TransientFailureException}
 * or a bug) -- a rail 5xx is retried in-process instead (see
 * .notes/reports/PHASE-6-REPORT.md section 5), never by escaping the
 * listener, so it never reaches this backoff/DLQ path.
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
        // Phase 10: see processing-service's own KafkaConsumerConfig for why this listener has to
        // be added by hand -- this factory bypasses KafkaAutoConfiguration entirely, so nothing
        // wires it in for free, and without it there is no consumer-lag data for this service's
        // topics at all.
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
        // Phase 10 (.notes/reports/PHASE-10-REPORT.md section 3): see processing-service's own
        // KafkaConsumerConfig for why this has to be set explicitly here rather than via
        // spring.kafka.listener.observation-enabled.
        factory.getContainerProperties().setObservationEnabled(true);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(dlqTopic, Math.floorMod(record.key().hashCode(), dlqPartitions)));

        // Phase 10 (.notes/reports/PHASE-10-REPORT.md section 5): see processing-service's own
        // KafkaConsumerConfig for why this is a plain arrival counter, not a lag reading -- nothing
        // consumes payments.dlq, so Kafka consumer-group lag can't express "unattended message."
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
