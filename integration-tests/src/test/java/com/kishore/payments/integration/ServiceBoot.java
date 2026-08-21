package com.kishore.payments.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Shared boot support for every test in this module that starts a real
 * service's Spring context against shared Testcontainers infrastructure.
 * See {@link MultiServiceSharedDatabaseTest}'s own javadoc for why
 * properties are passed as {@code run(String...)} arguments rather than
 * {@link SpringApplicationBuilder#properties}, and why {@code
 * spring.flyway.locations} is overridden to a filesystem path per service
 * rather than left at the {@code classpath:db/migration} default.
 */
final class ServiceBoot {

    /** core's migration folder alone, no sibling service's -- see class javadoc. */
    static final String CORE_MIGRATIONS = "filesystem:../core/target/classes/db/migration";

    private ServiceBoot() {
    }

    static ConfigurableApplicationContext boot(
            SpringApplicationBuilder builder, PostgreSQLContainer<?> postgres, RedpandaContainer redpanda, String serviceName,
            List<String> serviceArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--payments.kafka.bootstrap-servers=" + redpanda.getBootstrapServers(),
                "--payments.kafka.consumer-group=" + serviceName + "-integration-test-" + UUID.randomUUID(),
                "--payments.outbox.batch-size=100"));
        args.addAll(serviceArgs);
        return builder.run(args.toArray(String[]::new));
    }

    /**
     * Neither intake-service, processing-service nor settlement-gateway has
     * {@code spring-boot-starter-security} as a real dependency -- only
     * exception-service does. But this module test-depends on all four, so
     * it is on the combined test classpath regardless of which one is being
     * booted, and Spring Boot's security auto-configuration is
     * classpath-triggered, not scoped to the application class: left alone,
     * every one of the other three silently gets secured behind a
     * generated default password, and a plain {@code TestRestTemplate}
     * call against them gets a 401 instead of ever reaching the
     * controller. Excluding it here restores each service's real,
     * unsecured production behaviour for this shared-classpath test module.
     */
    private static final String EXCLUDE_SECURITY_AUTOCONFIG = "--spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
            + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration";

    static List<String> intakeArgs() {
        return List.of(
                "--spring.flyway.schemas=intake",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.locations=" + CORE_MIGRATIONS,
                "--payments.outbox.known-topics=payments.received",
                EXCLUDE_SECURITY_AUTOCONFIG);
    }

    static List<String> processingArgs() {
        return List.of(
                "--spring.main.web-application-type=none",
                "--spring.flyway.schemas=refdata",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.locations=" + CORE_MIGRATIONS + ",filesystem:../processing-service/target/classes/db/migration",
                "--payments.kafka.received-partitions=1",
                "--payments.kafka.validated-partitions=1",
                "--payments.kafka.enriched-partitions=1",
                "--payments.kafka.repaired-partitions=1",
                "--payments.kafka.dlq-topic=payments.dlq",
                "--payments.kafka.dlq-partitions=1",
                "--payments.outbox.known-topics=payments.validated,payments.enriched,payments.routed,payments.exceptions",
                EXCLUDE_SECURITY_AUTOCONFIG);
    }

    static List<String> gatewayArgs() {
        return List.of(
                "--spring.flyway.schemas=gateway",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.locations=" + CORE_MIGRATIONS + ",filesystem:../settlement-gateway/target/classes/db/migration",
                "--payments.kafka.routed-partitions=1",
                "--payments.kafka.dlq-topic=payments.dlq",
                "--payments.kafka.dlq-partitions=1",
                "--payments.outbox.known-topics=payments.sent,payments.settled,payments.exceptions,payments.routed",
                "--payments.gateway.rail-base-urls.FEDWIRE=http://localhost:1",
                "--payments.gateway.rail-base-urls.SEPA=http://localhost:1",
                "--payments.gateway.rail-base-urls.ACH_EQUIV=http://localhost:1",
                "--payments.gateway.connect-timeout=2s",
                "--payments.gateway.read-timeout=10s",
                "--payments.gateway.dispatch-retry.max-attempts=3",
                "--payments.gateway.dispatch-retry.initial-backoff=50ms",
                "--payments.gateway.dispatch-retry.backoff-multiplier=1.0",
                "--payments.gateway.reconciliation.interval=PT2M",
                "--payments.gateway.reconciliation.grace-period=30s",
                "--payments.gateway.reconciliation.batch-size=50",
                "--payments.gateway.reconciliation.consecutive-unknown-threshold=2",
                "--payments.gateway.reconciliation.inconclusive-window=10",
                "--payments.gateway.reconciliation.max-redispatch-attempts=3",
                "--payments.gateway.reconciliation.pending-threshold=5m",
                EXCLUDE_SECURITY_AUTOCONFIG);
    }

    static List<String> exceptionArgs() {
        return List.of(
                "--spring.flyway.schemas=exceptions",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.locations=" + CORE_MIGRATIONS + ",filesystem:../exception-service/target/classes/db/migration",
                "--payments.kafka.exceptions-partitions=1",
                "--payments.kafka.dlq-topic=payments.dlq",
                "--payments.kafka.dlq-partitions=1",
                "--payments.outbox.known-topics=payments.repaired",
                "--payments.exceptions.max-repair-attempts=3");
    }
}
