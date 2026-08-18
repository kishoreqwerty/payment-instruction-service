package com.kishore.payments.processing;

import com.kishore.payments.core.instruction.InstructionEventRepository;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxPublisher;
import com.kishore.payments.core.state.InstructionState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Same Testcontainers "singleton container" pattern as core and
 * intake-service. Unlike them, this Spring context starts real
 * {@code @KafkaListener} consumer threads for the whole test class's
 * lifetime -- the consumer-group id is randomised per test class run (see
 * {@link #containerProperties}) so that Spring's test context cache keys
 * different test classes to different contexts and different consumer
 * groups, rather than one class's committed offsets silently starving
 * another's.
 *
 * <p>{@code @DirtiesContext} is required, not just tidy, here: Spring's test
 * context cache otherwise keeps a finished test class's context -- and its
 * live {@code @KafkaListener} threads and {@code @Scheduled} OutboxPublisher
 * -- running in the background for the rest of the suite. Because every
 * cached context has its own randomised consumer group, each one receives
 * its own full copy of every message on a shared topic (ordinary Kafka
 * fan-out across groups), so a later test class's instructions get raced by
 * an earlier class's still-live consumers too -- using that earlier
 * context's own bean wiring (e.g. the real system clock instead of a test's
 * {@code MutableClock}). Tearing down each context after its class finishes
 * is what stops that.
 */
@SpringBootTest(classes = ProcessingServiceApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractProcessingIntegrationTest {

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
        registry.add("payments.kafka.consumer-group", () -> "processing-service-test-" + UUID.randomUUID());
        registry.add("payments.kafka.received-partitions", () -> 1);
        registry.add("payments.kafka.validated-partitions", () -> 1);
        registry.add("payments.kafka.enriched-partitions", () -> 1);
        registry.add("payments.kafka.repaired-partitions", () -> 1);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PaymentInstructionRepository instructions;

    @Autowired
    protected InstructionEventRepository events;

    /**
     * The application's own Clock bean (UTC by default -- see ClockConfig),
     * not the system default zone. RequestedExecutionDateNotPastRule checks
     * "is this in the past" against this clock, so a fixture built with bare
     * {@code LocalDate.now()} (system default zone) can appear to be
     * yesterday whenever UTC has already rolled to the next calendar day
     * relative to local time -- late evening in any zone west of UTC. Use
     * {@code LocalDate.now(clock)} in every fixture instead.
     */
    @Autowired
    protected Clock clock;

    @Autowired
    protected OutboxPublisher outboxPublisher;

    @Value("${payments.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Seeds an instruction directly at RECEIVED, with its seed
     * instruction_event and a payments.received outbox row -- exactly what
     * intake-service would have committed in one transaction, reproduced
     * here by hand since this module has no intake path of its own.
     */
    protected PaymentInstructionEntity seedReceivedEurInstruction(BigDecimal amount, LocalDate requestedExecDate) {
        return seedReceived(eurEntity(amount, requestedExecDate));
    }

    protected PaymentInstructionEntity seedReceivedUsdInstruction(BigDecimal amount, LocalDate requestedExecDate) {
        return seedReceived(usdEntity(amount, requestedExecDate));
    }

    protected PaymentInstructionEntity seedReceived(PaymentInstructionEntity entity) {
        jdbc.update(
                "INSERT INTO core.payment_instruction (instruction_id, raw_message_id, uetr, end_to_end_id, state, state_version, "
                        + "debtor_name, debtor_account, debtor_agent_bic, creditor_name, creditor_account, creditor_agent_bic, "
                        + "amount, currency, requested_exec_date) "
                        + "VALUES (?, ?, ?, ?, 'RECEIVED'::core.instruction_state, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entity.getInstructionId(),
                entity.getRawMessageId(),
                entity.getUetr(),
                entity.getEndToEndId(),
                entity.getDebtorName(),
                entity.getDebtorAccount(),
                entity.getDebtorAgentBic(),
                entity.getCreditorName(),
                entity.getCreditorAccount(),
                entity.getCreditorAgentBic(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getRequestedExecDate());
        jdbc.update(
                "INSERT INTO core.instruction_event (instruction_id, sequence_no, from_state, to_state, actor_type, actor_id) "
                        + "VALUES (?, 1, NULL, 'RECEIVED'::core.instruction_state, 'SYSTEM', 'test-seed')",
                entity.getInstructionId());
        jdbc.update(
                "INSERT INTO core.outbox (aggregate_id, topic, partition_key, headers, payload) VALUES (?, 'payments.received', ?, '{}'::jsonb, ?::jsonb)",
                entity.getInstructionId(),
                entity.getInstructionId().toString(),
                receivedEventJson(entity));
        return entity;
    }

    private String receivedEventJson(PaymentInstructionEntity entity) {
        return "{"
                + "\"instruction_id\":\"" + entity.getInstructionId() + "\","
                + "\"uetr\":\"" + entity.getUetr() + "\","
                + "\"end_to_end_id\":\"" + entity.getEndToEndId() + "\","
                + "\"state\":\"RECEIVED\","
                + "\"sequence_no\":1,"
                + "\"occurred_at\":\"" + OffsetDateTime.now() + "\","
                + "\"amount\":" + entity.getAmount() + ","
                + "\"currency\":\"" + entity.getCurrency() + "\","
                + "\"debtor_agent_bic\":\"" + entity.getDebtorAgentBic() + "\","
                + "\"creditor_agent_bic\":\"" + entity.getCreditorAgentBic() + "\","
                + "\"requested_execution_date\":\"" + entity.getRequestedExecDate() + "\","
                + "\"event_version\":1}";
    }

    private static PaymentInstructionEntity eurEntity(BigDecimal amount, LocalDate requestedExecDate) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Debtor GmbH",
                "DE89370400440532013000",
                "DEUTDEFFXXX",
                "Creditor SARL",
                "FR1420041010050500013M02606",
                "DEUTDEFFXXX",
                amount,
                "EUR",
                null,
                requestedExecDate);
    }

    private static PaymentInstructionEntity usdEntity(BigDecimal amount, LocalDate requestedExecDate) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Debtor Sp",
                "PL61109010140000071219812874",
                "CHASUS33XXX",
                "Creditor Inc",
                "GB29NWBK60161331926819",
                "CHASUS33XXX",
                amount,
                "USD",
                null,
                requestedExecDate);
    }

    /** Polls the database for the instruction to reach the expected state, since consumption is asynchronous once payments.received is published. */
    protected InstructionState awaitState(UUID instructionId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        InstructionState last = null;
        while (System.nanoTime() < deadline) {
            last = instructions.findById(instructionId).map(PaymentInstructionEntity::getState).orElse(null);
            if (last == InstructionState.EXCEPTION || last == InstructionState.ROUTED) {
                return last;
            }
            sleep();
        }
        return last;
    }

    protected void awaitCondition(Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected Consumer<String, String> newConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    protected List<ConsumerRecord<String, String>> consumeMatching(
            String topic, Predicate<ConsumerRecord<String, String>> matches, int expectedCount, Duration timeout) {
        try (Consumer<String, String> consumer = newConsumer("assert-" + UUID.randomUUID())) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> found = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (found.size() < expectedCount && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : polled) {
                    if (matches.test(record)) {
                        found.add(record);
                    }
                }
            }
            return found;
        }
    }
}
