package com.kishore.payments.processing.health;

import jakarta.annotation.PreDestroy;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness must reflect broker reachability, not just the database
 * (.notes/ARCHITECTURE.md §7.1) -- a pod that has lost its Kafka connection
 * should be pulled from service endpoints rather than accept traffic it
 * cannot process. {@code describeCluster()} with a short timeout is the
 * cheapest true round-trip to the broker; the {@link AdminClient} exists
 * solely for this check and is closed with it.
 */
@Component("kafka")
public class KafkaBrokerHealthIndicator implements HealthIndicator {

    private final AdminClient adminClient;

    public KafkaBrokerHealthIndicator(@Value("${payments.kafka.bootstrap-servers}") String bootstrapServers) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000);
        this.adminClient = AdminClient.create(props);
    }

    @Override
    public Health health() {
        try {
            adminClient.describeCluster().nodes().get(2, TimeUnit.SECONDS);
            return Health.up().build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (ExecutionException | TimeoutException e) {
            return Health.down(e).build();
        }
    }

    @PreDestroy
    public void close() {
        adminClient.close();
    }
}
