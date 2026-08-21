package com.kishore.payments.core.autoconfigure;

import com.kishore.payments.core.instruction.InstructionEventRepository;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxMetrics;
import com.kishore.payments.core.outbox.OutboxPublisher;
import com.kishore.payments.core.outbox.OutboxRetentionCleaner;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionStateMetrics;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.core.state.StateMachine;
import com.kishore.payments.core.state.StateTransitionTable;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * core has no {@code @SpringBootApplication} of its own, so its beans were
 * previously only reachable by a hosting service adding
 * {@code scanBasePackages = "com.kishore.payments.core"} to its own
 * {@code @SpringBootApplication} -- easy to forget, and every service that
 * depends on core needed the identical workaround. Listing this class in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * makes Spring Boot pick it up automatically for any service that has core
 * on its classpath, the same way any other auto-configuration starter works,
 * and needs no scanBasePackages anywhere.
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean}: a service that
 * wants its own implementation (or wants to exclude {@link OutboxPublisher}
 * or {@link OutboxRetentionCleaner} entirely, e.g. a read replica that
 * should never poll or prune the outbox) can either define its own bean of
 * that type or exclude this class via
 * {@code @SpringBootApplication(exclude = PaymentCoreAutoConfiguration.class)}.
 *
 * <p>{@code @EntityScan}/{@code @EnableJpaRepositories} are needed for the
 * same reason as the historical scanBasePackages problem, but on the JPA
 * side: Spring Boot's default entity and repository scanning is rooted at
 * the hosting application's own package, which doesn't reach
 * {@code com.kishore.payments.core.instruction}. Both are scoped to the
 * whole shared root package ({@code com.kishore.payments}, per CLAUDE.md's
 * base-package convention) rather than to core's own slice of it:
 * {@code @EnableJpaRepositories} declared anywhere in the context replaces
 * Spring Boot's own default repository scan entirely rather than adding to
 * it, so scoping it narrowly to core's instruction package would have
 * silently stopped every hosting service's own repositories (intake-service's
 * RawMessageRepository, for one) from being found.
 */
@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@EntityScan(basePackages = "com.kishore.payments")
@EnableJpaRepositories(basePackages = "com.kishore.payments")
public class PaymentCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StateTransitionTable stateTransitionTable() {
        return new StateTransitionTable();
    }

    @Bean
    @ConditionalOnMissingBean
    public StateMachine stateMachine(StateTransitionTable stateTransitionTable) {
        return new StateMachine(stateTransitionTable);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(JdbcTemplate jdbc, @Nullable Tracer tracer, @Nullable Propagator propagator) {
        return new OutboxWriter(jdbc, tracer, propagator);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxPublisher outboxPublisher(
            JdbcTemplate jdbc,
            Producer<String, String> producer,
            OutboxMetrics metrics,
            PlatformTransactionManager transactionManager,
            @Nullable Tracer tracer,
            @Nullable Propagator propagator,
            @Value("${payments.outbox.batch-size:100}") int batchSize,
            @Value("${payments.outbox.known-topics:payments.received}") List<String> knownTopics) {
        return new OutboxPublisher(jdbc, producer, metrics, transactionManager, tracer, propagator, batchSize, knownTopics);
    }

    @Bean
    @ConditionalOnMissingBean
    public InstructionStateMetrics instructionStateMetrics(MeterRegistry registry, StateTransitionTable stateTransitionTable) {
        return new InstructionStateMetrics(registry, stateTransitionTable);
    }

    @Bean
    @ConditionalOnMissingBean
    public InstructionStateWriter instructionStateWriter(
            PaymentInstructionRepository instructions, InstructionEventRepository events, StateMachine stateMachine,
            InstructionStateMetrics instructionStateMetrics, @Nullable Tracer tracer) {
        return new InstructionStateWriter(instructions, events, stateMachine, instructionStateMetrics, tracer);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRetentionCleaner outboxRetentionCleaner(
            JdbcTemplate jdbc,
            OutboxMetrics metrics,
            @Value("${payments.outbox.retention:7d}") Duration retention,
            @Value("${payments.outbox.retention-batch-size:1000}") int batchSize) {
        return new OutboxRetentionCleaner(jdbc, metrics, retention, batchSize);
    }
}
