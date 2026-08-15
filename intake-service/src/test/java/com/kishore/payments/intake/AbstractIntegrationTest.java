package com.kishore.payments.intake;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Testcontainers' "singleton container" pattern: one PostgreSQL container and
 * one Redpanda container shared by every integration test class in this
 * module, started once and left running for the JVM to reap. Deliberately not
 * annotated with {@code @Testcontainers}/{@code @Container} -- that
 * combination stops the container in each class's own afterAll, which would
 * kill it out from under whichever test class happens to run next since they
 * all share this same static field through inheritance.
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");

    static final RedpandaContainer REDPANDA =
            new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4");

    static {
        POSTGRES.start();
        REDPANDA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payments.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    }
}
