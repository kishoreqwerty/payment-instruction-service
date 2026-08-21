package com.kishore.payments.core.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.outbox.OutboxMetrics;
import com.kishore.payments.core.outbox.OutboxProducerFactory;
import com.kishore.payments.core.outbox.OutboxPublisher;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.StateMachine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Proves A2's actual claim: a bare Boot application that declares no
 * scanBasePackages anywhere, and whose own package
 * ({@code com.kishore.payments.core.autoconfigure.smoketest}, a sibling of
 * {@code outbox}/{@code instruction}/{@code state}, not an ancestor of any of
 * them) is nowhere near core's classes, still resolves OutboxWriter,
 * OutboxPublisher and InstructionStateWriter as beans -- because
 * {@code PaymentCoreAutoConfiguration} reaches them through
 * {@code AutoConfiguration.imports}, not component scanning.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.NONE,
        classes = PaymentCoreAutoConfigurationTest.MinimalHostApplication.class)
class PaymentCoreAutoConfigurationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");

    private static final RedpandaContainer REDPANDA =
            new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4");

    static {
        POSTGRES.start();
        REDPANDA.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("bootstrap-servers-for-test", REDPANDA::getBootstrapServers);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private InstructionStateWriter instructionStateWriter;

    @Autowired
    private StateMachine stateMachine;

    @Test
    void coreBeansResolveWithNoScanBasePackagesAnywhereInTheHostingApplication() {
        assertThat(outboxWriter).isNotNull();
        assertThat(outboxPublisher).isNotNull();
        assertThat(instructionStateWriter).isNotNull();
        assertThat(stateMachine).isNotNull();

        // None of these beans were declared by MinimalHostApplication itself
        // (see its own source below) or reachable by scanning its package --
        // the only path from this bare host to core's classes is
        // PaymentCoreAutoConfiguration, listed in
        // META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.
        assertThat(context.getBeanNamesForType(OutboxWriter.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(OutboxPublisher.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(InstructionStateWriter.class)).hasSize(1);
    }

    /**
     * Deliberately declared with no {@code scanBasePackages} and in a
     * package that is a sibling of, not an ancestor of,
     * {@code com.kishore.payments.core.outbox}/{@code .instruction}/
     * {@code .state} -- Spring Boot's default component scan (rooted at this
     * class's own package) genuinely cannot reach core's beans on its own.
     * The only two {@code @Bean} methods here supply what
     * {@code PaymentCoreAutoConfiguration} cannot construct itself because it
     * depends on this host's own configuration: the Kafka producer and the
     * metrics registry, exactly mirroring what a real service's own
     * OutboxConfig provides.
     */
    @SpringBootApplication
    static class MinimalHostApplication {

        @Bean(destroyMethod = "close")
        Producer<String, String> outboxProducer(
                org.springframework.core.env.Environment env) {
            return OutboxProducerFactory.create(env.getRequiredProperty("bootstrap-servers-for-test"));
        }

        // A real service always has one of these via spring-boot-starter-actuator; this minimal
        // host deliberately has no starters at all (see class javadoc), so it needs its own, the
        // same reason it supplies OutboxMetrics below rather than relying on autoconfiguration for
        // either -- InstructionStateMetrics (Phase 10) now depends on one too.
        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        OutboxMetrics outboxMetrics() {
            return new OutboxMetrics(new SimpleMeterRegistry(), List.of("payments.received"));
        }
    }
}
