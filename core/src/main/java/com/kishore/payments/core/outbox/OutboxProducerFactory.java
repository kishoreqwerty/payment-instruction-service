package com.kishore.payments.core.outbox;

import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Builds the one producer configuration every service's {@link OutboxPublisher}
 * uses, so "identical everywhere" is enforced by sharing this factory rather
 * than by convention across services.
 *
 * {@code enable.idempotence=true} suppresses broker-level duplicates caused
 * by the producer's own retries (a retried produce of the same message is
 * deduplicated by the broker using the producer's sequence number). It does
 * NOT make the system exactly-once: that guarantee stops at the Kafka
 * boundary and does not extend to the outbox row's own published_at update,
 * which is a separate write against a separate system. See
 * .notes/ARCHITECTURE.md §4.3.
 */
public final class OutboxProducerFactory {

    private OutboxProducerFactory() {
    }

    public static Producer<String, String> create(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        return new KafkaProducer<>(props);
    }
}
