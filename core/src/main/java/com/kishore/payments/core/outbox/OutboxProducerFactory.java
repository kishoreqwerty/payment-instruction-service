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
 *
 * <p>{@code max.in.flight.requests.per.connection=1} is deliberate, not a
 * missed tuning opportunity: this is the ordering guarantee, established in
 * Phase 3 and asserted directly by {@code OutboxOrderingAcrossReplicasTest}
 * (Phase 4's A1 case) -- events for a single instruction must arrive in
 * {@code sequence_no} order even under concurrent publishers. Phase 12
 * (.notes/reports/PHASE-12-REPORT.md §4.3) tried raising this to 5 (the
 * documented maximum an idempotent producer still guarantees ordering for
 * on one producer/one partition) to see whether it was the sustained-load
 * bottleneck. It measurably was not: {@code payment_outbox_publish_duration_seconds}
 * showed no improvement (52ms avg vs. 41ms before, if anything worse), so
 * the change was reverted rather than kept on the strength of the reasoning
 * alone. Recorded here, not just in that report, because a change that was
 * tried, measured, and reverted for cause is worth knowing about the next
 * time someone looks at this value and wonders why it's 1 -- the answer is
 * not "nobody thought to raise it."
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
