package com.kishore.payments.exception.config;

import com.kishore.payments.core.outbox.OutboxMetrics;
import com.kishore.payments.core.outbox.OutboxProducerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the two beans {@code OutboxPublisher} and {@code
 * OutboxRetentionCleaner} (both wired up by {@code
 * PaymentCoreAutoConfiguration}) need but can't construct themselves.
 * Mirrors processing-service's and settlement-gateway's own OutboxConfig.
 */
@Configuration
public class OutboxConfig {

    @Bean(destroyMethod = "close")
    public Producer<String, String> outboxProducer(@Value("${payments.kafka.bootstrap-servers}") String bootstrapServers) {
        return OutboxProducerFactory.create(bootstrapServers);
    }

    @Bean
    public OutboxMetrics outboxMetrics(MeterRegistry meterRegistry, @Value("${payments.outbox.known-topics}") List<String> knownTopics) {
        return new OutboxMetrics(meterRegistry, knownTopics);
    }
}
