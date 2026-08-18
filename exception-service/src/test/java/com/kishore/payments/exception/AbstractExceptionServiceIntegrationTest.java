package com.kishore.payments.exception;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.InstructionEventRepository;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxHeaders;
import com.kishore.payments.core.outbox.OutboxMessage;
import com.kishore.payments.core.outbox.OutboxPublisher;
import com.kishore.payments.core.outbox.OutboxWriter;
import com.kishore.payments.core.state.InstructionStateWriter;
import com.kishore.payments.exception.cases.ExceptionCaseRepository;
import com.kishore.payments.exception.cases.ExceptionCaseService;
import com.kishore.payments.exception.repair.RepairActionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/** Same Testcontainers "singleton container, per-class random consumer group, @DirtiesContext after class" pattern as every other service's own base class. */
@SpringBootTest(classes = ExceptionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractExceptionServiceIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");

    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4");

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
        registry.add("payments.kafka.consumer-group", () -> "exception-service-test-" + UUID.randomUUID());
        registry.add("payments.kafka.exceptions-partitions", () -> 1);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PaymentInstructionRepository instructions;

    @Autowired
    protected InstructionEventRepository events;

    @Autowired
    protected ExceptionCaseRepository cases;

    @Autowired
    protected RepairActionRepository repairActions;

    @Autowired
    protected ExceptionCaseService caseService;

    @Autowired
    protected InstructionStateWriter stateWriter;

    @Autowired
    protected Clock clock;

    @Autowired
    protected OutboxPublisher outboxPublisher;

    @Autowired
    protected OutboxWriter outboxWriter;

    @Autowired
    protected TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    protected TestRestTemplate asUser(String username) {
        return restTemplate.withBasicAuth(username, "password");
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** Seeds an instruction at EXCEPTION with a bad-IBAN-shaped creditor account, and its own seed + EXCEPTION instruction_event rows. */
    protected PaymentInstructionEntity seedInstructionAtException(String creditorAccount) {
        return seed(creditorAccount, "EXCEPTION");
    }

    protected PaymentInstructionEntity seedInstructionAtInvestigation() {
        return seed("FR1420041010050500013M02606", "INVESTIGATION");
    }

    private PaymentInstructionEntity seed(String creditorAccount, String state) {
        UUID instructionId = UUID.randomUUID();
        UUID rawMessageId = UUID.randomUUID();
        UUID uetr = UUID.randomUUID();
        String endToEndId = "E2E-" + instructionId.toString().substring(0, 8);
        PaymentInstructionEntity entity = new PaymentInstructionEntity(
                instructionId, rawMessageId, uetr, endToEndId, null,
                "Debtor GmbH", "DE89370400440532013000", "DEUTDEFFXXX",
                "Creditor SARL", creditorAccount, "DEUTDEFFXXX",
                new BigDecimal("100.00"), "EUR", "SHAR", LocalDate.now(clock));
        jdbc.update(
                "INSERT INTO core.payment_instruction (instruction_id, raw_message_id, uetr, end_to_end_id, state, state_version, "
                        + "debtor_name, debtor_account, debtor_agent_bic, creditor_name, creditor_account, creditor_agent_bic, "
                        + "amount, currency, charge_bearer, requested_exec_date) "
                        + "VALUES (?, ?, ?, ?, ?::core.instruction_state, 2, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                instructionId, rawMessageId, uetr, endToEndId, state,
                entity.getDebtorName(), entity.getDebtorAccount(), entity.getDebtorAgentBic(),
                entity.getCreditorName(), entity.getCreditorAccount(), entity.getCreditorAgentBic(),
                entity.getAmount(), entity.getCurrency(), entity.getChargeBearer(), entity.getRequestedExecDate());
        jdbc.update(
                "INSERT INTO core.instruction_event (instruction_id, sequence_no, from_state, to_state, actor_type, actor_id) "
                        + "VALUES (?, 1, NULL, 'RECEIVED'::core.instruction_state, 'SYSTEM', 'test-seed')",
                instructionId);
        jdbc.update(
                "INSERT INTO core.instruction_event (instruction_id, sequence_no, from_state, to_state, actor_type, actor_id, reason_code) "
                        + "VALUES (?, 2, 'RECEIVED'::core.instruction_state, ?::core.instruction_state, 'SYSTEM', 'test-seed', 'AC01')",
                instructionId, state);
        return instructions.findById(instructionId).orElseThrow();
    }

    protected void awaitCondition(Duration timeout, BooleanSupplier condition) {
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

    protected void drainOutbox() {
        for (int i = 0; i < 10; i++) {
            outboxPublisher.publishBatch();
        }
    }

    /**
     * Writes a payments.exceptions message via this service's own outbox
     * infrastructure and flushes it to the real topic -- in production a
     * different service (processing-service, settlement-gateway) is always
     * the actual producer, but Kafka does not care which producer wrote a
     * message, and reusing the same outbox/publish path this service
     * already has is what lets a test exercise the real {@code
     * ExceptionEventConsumer} listener end to end rather than calling its
     * underlying service method directly.
     */
    /**
     * Simulates re-validation catching a new (or still-present) defect
     * after a repair or retry sent the instruction back to REPAIRED: the
     * real ValidationConsumer, reacting to payments.repaired, would attempt
     * {@code REPAIRED -> VALIDATED} and then, since the defect persists,
     * {@code VALIDATED} is never actually reached -- validation fails and
     * {@code REPAIRED -> EXCEPTION} fires directly (see
     * StateTransitionTable's own comment on that transition). A test that
     * skips straight to {@link #publishExceptionEvent} without this step
     * first would leave the instruction sitting at REPAIRED -- a state
     * {@code ExceptionCaseService#applyRepair}'s own {@code EXCEPTION ->
     * REPAIRED} transition can never legally start from, surfacing as a
     * spurious IllegalTransitionException on the *next* repair cycle rather
     * than on this one.
     */
    protected void simulateRevalidationFailure(
            java.util.UUID instructionId, java.util.UUID uetr, String endToEndId, int sequenceNo, FailureStage stage,
            InstructionExceptionEvent.Detail detail) {
        stateWriter.transition(
                instructionId, com.kishore.payments.core.state.InstructionState.EXCEPTION,
                com.kishore.payments.core.domain.ActorType.SYSTEM, "test-revalidation", detail.reasonCode(), detail.detail());
        publishExceptionEvent(instructionId, uetr, endToEndId, sequenceNo, stage, detail);
    }

    protected void publishExceptionEvent(
            UUID instructionId, UUID uetr, String endToEndId, int sequenceNo, FailureStage stage, InstructionExceptionEvent.Detail detail) {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        InstructionExceptionEvent event = new InstructionExceptionEvent(
                instructionId, uetr, endToEndId, sequenceNo, occurredAt, stage, List.of(detail), InstructionExceptionEvent.CURRENT_VERSION);
        outboxWriter.write(new OutboxMessage(
                instructionId, "payments.exceptions", instructionId.toString(),
                OutboxHeaders.of("InstructionException", InstructionExceptionEvent.CURRENT_VERSION, occurredAt, null), event));
        drainOutbox();
    }
}
