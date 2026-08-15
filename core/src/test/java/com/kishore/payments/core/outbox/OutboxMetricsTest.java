package com.kishore.payments.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A counter that has never been incremented does not appear in a scrape,
 * which means an alert on the failure rate silently never fires until the
 * first failure. Pre-registering at construction, rather than lazily on
 * first use, is what avoids that.
 */
class OutboxMetricsTest {

    @Test
    void publishedCounterIsPreRegisteredAtZeroForEveryKnownTopicAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetrics(registry, List.of("payments.received", "payments.dlq"));

        for (String topic : List.of("payments.received", "payments.dlq")) {
            for (String outcome : List.of("success", "failure")) {
                double value = registry.get("payment_outbox_published_total")
                        .tag("topic", topic)
                        .tag("outcome", outcome)
                        .counter()
                        .count();
                assertThat(value).as("%s/%s should be registered at zero before any publish happens", topic, outcome).isZero();
            }
        }
    }

    @Test
    void pendingAndOldestPendingGaugesArePreRegisteredForEveryKnownTopic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxMetrics(registry, List.of("payments.received"));

        assertThat(registry.get("payment_outbox_pending").tag("topic", "payments.received").gauge().value()).isZero();
        assertThat(registry.get("payment_outbox_oldest_pending_seconds")
                        .tag("topic", "payments.received")
                        .gauge()
                        .value())
                .isZero();
    }

    @Test
    void recordPublishedIncrementsOnlyTheMatchingCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry, List.of("payments.received"));

        metrics.recordPublished("payments.received", true);

        assertThat(registry.get("payment_outbox_published_total")
                        .tag("topic", "payments.received")
                        .tag("outcome", "success")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("payment_outbox_published_total")
                        .tag("topic", "payments.received")
                        .tag("outcome", "failure")
                        .counter()
                        .count())
                .isZero();
    }
}
