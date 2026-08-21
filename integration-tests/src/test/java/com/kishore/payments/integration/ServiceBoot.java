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
                // Every service's own application.yml sits at the same classpath root
                // (classpath:/application.yml); on this module's combined classpath only one of
                // them is ever actually loaded, and it is not necessarily this one's own -- so
                // spring.application.name (which OTel's Resource auto-configuration uses as the
                // service.name every span is tagged with) has to be set explicitly here too.
                // Without it, whichever service's own yml happens to win the classpath race
                // silently donates its application name to every context booted in this module,
                // and every trace this test looks for shows up filed under one wrong service.
                "--spring.application.name=" + serviceName,
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
        return gatewayArgs("http://localhost:1");
    }

    /**
     * Parameterised on the rail base URL (Phase 10's {@code ObservabilityTracingIntegrationTest}
     * is the first test in this module to boot a real rail-simulator, so it needs this pointed at
     * that instance's actual Testcontainers-assigned port rather than the always-broken placeholder
     * every earlier test here uses, since none of them exercise real dispatch).
     */
    static List<String> gatewayArgs(String railBaseUrl) {
        return List.of(
                "--spring.flyway.schemas=gateway",
                "--spring.flyway.baseline-on-migrate=true",
                "--spring.flyway.locations=" + CORE_MIGRATIONS + ",filesystem:../settlement-gateway/target/classes/db/migration",
                "--payments.kafka.routed-partitions=1",
                "--payments.kafka.dlq-topic=payments.dlq",
                "--payments.kafka.dlq-partitions=1",
                "--payments.outbox.known-topics=payments.sent,payments.settled,payments.exceptions,payments.routed",
                "--payments.gateway.rail-base-urls.FEDWIRE=" + railBaseUrl,
                "--payments.gateway.rail-base-urls.SEPA=" + railBaseUrl,
                "--payments.gateway.rail-base-urls.ACH_EQUIV=" + railBaseUrl,
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

    /**
     * rail-simulator needs no database and declares no JPA/Flyway dependency of its own -- but
     * this module's combined test classpath merges in every other service's transitive
     * dependencies too (the same flattening {@link #EXCLUDE_SECURITY_AUTOCONFIG}'s own javadoc
     * describes for security), so {@code FlywayAutoConfiguration}/{@code DataSourceAutoConfiguration}
     * activate here purely because the classes are present, not because rail-simulator asked for
     * them. Left alone, Flyway then connects to whatever real Postgres {@link #boot} happens to
     * pass on the command line (every service gets those args unconditionally) and falls back to
     * its classpath-scan default, discovering every *other* service's migrations at once --
     * exactly the "Found more than one migration with version 2" collision
     * .notes/reports/CROSS-SERVICE-INTEGRATION-DEFECTS.md already fixed once, resurfacing here for
     * a service that was never part of that fix because it never needed a database standalone.
     */
    private static final String EXCLUDE_JPA_AND_FLYWAY_AUTOCONFIG = "--spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
            + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
            // core's own @AutoConfiguration also activates purely from being on this shared
            // classpath (same mechanism, one level up): it needs the JdbcTemplate/DataSource this
            // exclusion list just removed, and rail-simulator has no use for the outbox/instruction
            // -state machinery core provides regardless -- that's intake/processing/gateway/
            // exception's shared domain, not a rail simulator's.
            + "com.kishore.payments.core.autoconfigure.PaymentCoreAutoConfiguration";

    static List<String> railSimulatorArgs() {
        return List.of(
                EXCLUDE_SECURITY_AUTOCONFIG,
                EXCLUDE_JPA_AND_FLYWAY_AUTOCONFIG,
                // Every service's own application.yml sits at the same classpath root
                // (classpath:/application.yml); on this module's merged classpath only one of them
                // wins, and it is not reliably rail-simulator's, so its one property with no
                // override above (railsim.default-callback-url) has to be supplied explicitly here
                // too rather than left to its own default. Unused by any assertion this module
                // makes -- scenarios loaded by these tests don't rely on the callback actually
                // being delivered -- but its placeholder still has to resolve for RailController's
                // bean creation to succeed at all.
                "--railsim.default-callback-url=http://localhost:1/unused",
                // Whichever other service's application.yml wins the classpath race above (only
                // one `classpath:/application.yml` can) brings its own readiness group with it,
                // which names a "db" health contributor this exclusion list just removed.
                "--management.endpoint.health.group.readiness.include=readinessState");
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
