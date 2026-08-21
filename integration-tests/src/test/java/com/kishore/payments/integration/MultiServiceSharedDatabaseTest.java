package com.kishore.payments.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.exception.ExceptionServiceApplication;
import com.kishore.payments.gateway.SettlementGatewayApplication;
import com.kishore.payments.intake.IntakeServiceApplication;
import com.kishore.payments.processing.ProcessingServiceApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Reproduces the defect found running every service together locally against
 * one shared Postgres instance (see
 * .notes/reports/CROSS-SERVICE-INTEGRATION-DEFECTS.md): {@code
 * intake-service}, {@code processing-service}, {@code settlement-gateway}
 * and {@code exception-service} each depend on {@code core} and each run
 * their own Flyway instance on startup. Every per-service test suite in this
 * repository starts a fresh, isolated Testcontainers database per test
 * class, which is exactly why none of them ever exercised two real services'
 * migrations landing in the same physical database -- the one condition
 * that actually exposed the bug. This test is the one thing in the repo that
 * does: four real Spring Boot applications, four real independent Flyway
 * runs, one shared database, no isolation to hide behind.
 *
 * <p>Before the fix (each service's own {@code flyway_schema_history} in its
 * own schema, {@code core}'s V1 migration made idempotent so a second,
 * third and fourth independent history can each re-apply it against a
 * database where a sibling service already created its objects, and {@code
 * baseline-on-migrate} for the one service -- {@code intake-service} --
 * whose own schema is also created by that same shared V1), the second
 * service to start against a blank database would fail Flyway checksum
 * validation outright: whichever service started first "owned" version V2
 * in the one shared {@code public.flyway_schema_history} table, and the
 * other two services' own, differently-shaped V2 migrations were rejected
 * as a checksum mismatch. This test starts all four, in an order that is
 * deliberately not the order any of them "should" start in (there is no
 * such order -- see README), and asserts every one of them comes up clean.
 *
 * <p>See {@link ServiceBoot}'s own javadoc for why every property is passed
 * as a {@code run(String...)} argument and {@code spring.flyway.locations}
 * is overridden per service to a filesystem path.
 */
@Testcontainers
class MultiServiceSharedDatabaseTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");

    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4");

    static {
        POSTGRES.start();
        REDPANDA.start();
    }

    private ConfigurableApplicationContext intakeContext;
    private ConfigurableApplicationContext processingContext;
    private ConfigurableApplicationContext gatewayContext;
    private ConfigurableApplicationContext exceptionContext;

    @AfterAll
    static void tearDownContainers() {
        REDPANDA.stop();
        POSTGRES.stop();
    }

    @Test
    void allFourServicesMigrateCleanlyAgainstOneSharedDatabaseRegardlessOfStartOrder() {
        // processing-service first is the adversarial case: its own V2 both creates a schema and
        // seeds data in the same migration.
        processingContext = ServiceBoot.boot(
                new SpringApplicationBuilder(ProcessingServiceApplication.class), POSTGRES, REDPANDA, "processing-service",
                ServiceBoot.processingArgs());
        exceptionContext = ServiceBoot.boot(
                new SpringApplicationBuilder(ExceptionServiceApplication.class), POSTGRES, REDPANDA, "exception-service",
                ServiceBoot.exceptionArgs());
        gatewayContext = ServiceBoot.boot(
                new SpringApplicationBuilder(SettlementGatewayApplication.class), POSTGRES, REDPANDA, "settlement-gateway",
                ServiceBoot.gatewayArgs());
        // intake-service last: the adversarial case for baseline-on-migrate -- by now, `intake`
        // (created by core's shared V1) already exists with intake.raw_message in it, populated by
        // whichever of the other three services happened to run V1 first, before intake-service's
        // own Flyway history (also rooted in the `intake` schema) has ever run at all.
        intakeContext = ServiceBoot.boot(
                new SpringApplicationBuilder(IntakeServiceApplication.class), POSTGRES, REDPANDA, "intake-service", ServiceBoot.intakeArgs());

        assertThat(processingContext.isActive()).isTrue();
        assertThat(exceptionContext.isActive()).isTrue();
        assertThat(gatewayContext.isActive()).isTrue();
        assertThat(intakeContext.isActive()).isTrue();

        intakeContext.close();
        processingContext.close();
        gatewayContext.close();
        exceptionContext.close();
    }
}
