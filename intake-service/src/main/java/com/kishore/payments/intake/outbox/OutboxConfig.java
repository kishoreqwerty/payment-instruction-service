package com.kishore.payments.intake.outbox;

import com.kishore.payments.core.outbox.OutboxMetrics;
import com.kishore.payments.core.outbox.OutboxProducerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the two beans {@code OutboxPublisher} (in core, picked up by
 * component scan) needs but can't construct itself: the Kafka producer and
 * the metrics registrations, both of which depend on this service's own
 * configuration (bootstrap servers, which topics it actually produces to).
 */
@Configuration
public class OutboxConfig {

    @Bean(destroyMethod = "close")
    public Producer<String, String> outboxProducer(@Value("${payments.kafka.bootstrap-servers}") String bootstrapServers) {
        return OutboxProducerFactory.create(bootstrapServers);
    }

    @Bean
    public OutboxMetrics outboxMetrics(
            MeterRegistry meterRegistry, @Value("${payments.outbox.known-topics:payments.received}") List<String> knownTopics) {
        return new OutboxMetrics(meterRegistry, knownTopics);
    }
}
