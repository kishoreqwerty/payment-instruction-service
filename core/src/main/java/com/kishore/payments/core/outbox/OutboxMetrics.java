package com.kishore.payments.core.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every labelled counter is pre-registered at zero for every known topic at
 * construction time. A counter that has never been incremented does not
 * appear in the scrape output, which means an alert on the failure rate
 * silently never fires until the first failure -- pre-initialising is what
 * makes the alert reliable from the moment the service starts.
 */
public class OutboxMetrics {

    private static final List<String> OUTCOMES = List.of("success", "failure");

    private final Map<String, Counter> published = new HashMap<>();
    private final Map<String, AtomicLong> pending = new HashMap<>();
    private final Map<String, AtomicLong> oldestPendingSeconds = new HashMap<>();
    private final Timer publishDuration;
    private final Counter deleted;

    public OutboxMetrics(MeterRegistry registry, List<String> knownTopics) {
        for (String topic : knownTopics) {
            for (String outcome : OUTCOMES) {
                published.put(
                        key(topic, outcome),
                        Counter.builder("payment_outbox_published_total")
                                .tag("topic", topic)
                                .tag("outcome", outcome)
                                .register(registry));
            }

            AtomicLong pendingCount = new AtomicLong();
            pending.put(topic, pendingCount);
            Gauge.builder("payment_outbox_pending", pendingCount, AtomicLong::get)
                    .tag("topic", topic)
                    .register(registry);

            AtomicLong oldest = new AtomicLong();
            oldestPendingSeconds.put(topic, oldest);
            Gauge.builder("payment_outbox_oldest_pending_seconds", oldest, AtomicLong::get)
                    .tag("topic", topic)
                    .register(registry);
        }

        this.publishDuration = Timer.builder("payment_outbox_publish_duration_seconds").register(registry);
        this.deleted = Counter.builder("payment_outbox_deleted_total").register(registry);
    }

    public void recordPublished(String topic, boolean success) {
        Counter counter = published.get(key(topic, success ? "success" : "failure"));
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordPublishDuration(Duration duration) {
        publishDuration.record(duration);
    }

    public void setPending(String topic, long count) {
        AtomicLong gauge = pending.get(topic);
        if (gauge != null) {
            gauge.set(count);
        }
    }

    public void setOldestPendingSeconds(String topic, long seconds) {
        AtomicLong gauge = oldestPendingSeconds.get(topic);
        if (gauge != null) {
            gauge.set(seconds);
        }
    }

    public void recordDeleted(long count) {
        deleted.increment(count);
    }

    public void clearPending(String topic) {
        setPending(topic, 0);
        setOldestPendingSeconds(topic, 0);
    }

    private static String key(String topic, String outcome) {
        return topic + ":" + outcome;
    }
}
