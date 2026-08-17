package com.kishore.payments.gateway;

import com.kishore.payments.core.instruction.InstructionEventRepository;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.core.outbox.OutboxPublisher;
import com.kishore.payments.core.state.InstructionState;
import com.kishore.payments.gateway.dispatch.DispatchRecordRepository;
import com.kishore.payments.railsim.RailSimulatorApplication;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Testcontainers Postgres + Redpanda, same "singleton container, per-class
 * random consumer group, @DirtiesContext after class" pattern as
 * processing-service's own base class -- see its javadoc for why both are
 * necessary. The addition here: a real rail-simulator instance, booted via
 * {@link SpringApplicationBuilder} on its own random port in this same JVM
 * rather than via a Docker image + Testcontainers GenericContainer.
 * rail-simulator's own isolation design (no dependency on core, no shared
 * domain types) is what makes this safe -- it cannot see or touch this
 * module's Spring context, so embedding it is just running two independent
 * Boot applications in one process, which Spring Boot supports natively as
 * long as their ports differ.
 */
@SpringBootTest(classes = SettlementGatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractGatewayIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");

    static final RedpandaContainer REDPANDA =
            new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4");

    static final ConfigurableApplicationContext RAIL_SIMULATOR_CONTEXT;
    static final int RAIL_SIMULATOR_PORT;

    static {
        POSTGRES.start();
        REDPANDA.start();
        // rail-simulator is a test-scope dependency of this module, which
        // means its classes share this JVM's test classpath with
        // settlement-gateway's own compile dependencies -- flyway-core,
        // the Postgres driver, spring-kafka, and core's auto-configuration
        // entry (discovered via META-INF/spring/...AutoConfiguration.imports,
        // which Spring Boot scans the whole classpath for, not just
        // RailSimulatorApplication's own package). Left alone, booting
        // RailSimulatorApplication here would auto-configure all of that
        // in, and rail-simulator has no datasource or Kafka config of its
        // own to satisfy it. Excluding them explicitly keeps the embedded
        // instance exactly as isolated as it is when rail-simulator runs
        // its own test suite standalone.
        RAIL_SIMULATOR_CONTEXT = new SpringApplicationBuilder(RailSimulatorApplication.class)
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        // rail-simulator's own application.yml and this
                        // module's both sit at classpath:/application.yml;
                        // only one is actually resolved when both jars are
                        // on the same test classpath, and it isn't
                        // rail-simulator's. Supplying the one property it
                        // needs directly sidesteps relying on that file at
                        // all for this embedded instance.
                        "railsim.default-callback-url=http://localhost:1",
                        // Same classpath-shadowing problem, this time for
                        // management.endpoint.health.group.readiness.include
                        // -- settlement-gateway's application.yml (the one
                        // actually resolved) names a "db" health group
                        // member that doesn't exist once DataSourceAutoConfiguration
                        // is excluded above.
                        "management.endpoint.health.validate-group-membership=false",
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration,"
                                + "com.kishore.payments.core.autoconfigure.PaymentCoreAutoConfiguration")
                .run();
        RAIL_SIMULATOR_PORT =
                Integer.parseInt(RAIL_SIMULATOR_CONTEXT.getEnvironment().getProperty("local.server.port"));
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payments.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        registry.add("payments.kafka.consumer-group", () -> "settlement-gateway-test-" + UUID.randomUUID());
        registry.add("payments.kafka.routed-partitions", () -> 1);
        registry.add("payments.gateway.rail-base-urls.FEDWIRE", () -> "http://localhost:" + RAIL_SIMULATOR_PORT);
        registry.add("payments.gateway.rail-base-urls.SEPA", () -> "http://localhost:" + RAIL_SIMULATOR_PORT);
        registry.add("payments.gateway.rail-base-urls.ACH_EQUIV", () -> "http://localhost:" + RAIL_SIMULATOR_PORT);
        // Fast retry backoff for the 5xx-retry tests -- real production timing
        // (1s/2s/4s/8s/16s) would make those tests take over half a minute.
        registry.add("payments.gateway.dispatch-retry.max-attempts", () -> 3);
        registry.add("payments.gateway.dispatch-retry.initial-backoff", () -> "50ms");
        registry.add("payments.gateway.dispatch-retry.backoff-multiplier", () -> 1.0);
        // Short enough that a rail-simulator TIMEOUT scenario's hold
        // (comfortably longer than this) actually produces a client-side
        // read timeout within a fast test, rather than just a slow 202.
        registry.add("payments.gateway.read-timeout", () -> "800ms");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PaymentInstructionRepository instructions;

    @Autowired
    protected InstructionEventRepository events;

    @Autowired
    protected Clock clock;

    @Autowired
    protected OutboxPublisher outboxPublisher;

    @Autowired
    protected DispatchRecordRepository dispatchRecords;

    @Autowired
    protected TestRestTemplate restTemplate;

    @LocalServerPort
    protected int gatewayPort;

    private final TestRestTemplate railSimulatorClient = new TestRestTemplate();

    protected String callbackStatusUrl(String railId) {
        return "http://localhost:" + gatewayPort + "/callbacks/rail/" + railId + "/status";
    }

    protected String callbackReturnUrl(String railId) {
        return "http://localhost:" + gatewayPort + "/callbacks/rail/" + railId + "/return";
    }

    protected String gatewayCallbackUrl(String railId) {
        return callbackStatusUrl(railId);
    }

    /** Loads a scenario directly on the embedded rail-simulator, bypassing the gateway entirely -- test setup, not something the gateway does. */
    protected void loadRailScenario(String railId, String yaml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Void> response = railSimulatorClient.postForEntity(
                railSimulatorUrl("/rail/" + railId + "/scenario"), new HttpEntity<>(yaml, headers), Void.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Failed to load rail-simulator scenario for " + railId + ": " + response.getStatusCode());
        }
    }

    protected String railSimulatorUrl(String path) {
        return "http://localhost:" + RAIL_SIMULATOR_PORT + path;
    }

    /** GET /rail/{railId}/received on the embedded rail-simulator, for tests that need to see what it recorded. */
    protected ResponseEntity<String> railSimulatorReceived(String railId) {
        return railSimulatorClient.getForEntity(railSimulatorUrl("/rail/" + railId + "/received"), String.class);
    }

    /**
     * GET /rail/{railId}/payments/{uetr} on the embedded rail-simulator
     * directly -- what {@link com.kishore.payments.gateway.reconciliation.RailStatusClient}
     * itself queries, useful for a test asserting on the rail's own opinion
     * before or independent of reconciliation acting on it. Deserializes
     * straight into rail-simulator's own response type rather than a raw
     * map: rail-simulator is a test-scope dependency of this module (see
     * this class's own javadoc), so its types are safely on the classpath
     * here.
     */
    protected ResponseEntity<com.kishore.payments.railsim.api.RailController.PaymentStatusResponse> railSimulatorGetPayment(String railId, String uetr) {
        return railSimulatorClient.getForEntity(
                railSimulatorUrl("/rail/" + railId + "/payments/" + uetr),
                com.kishore.payments.railsim.api.RailController.PaymentStatusResponse.class);
    }

    /** GET /rail/{railId}/received/{uetr} on the embedded rail-simulator -- receipt + duplicate-delivery count for one UETR. */
    protected ResponseEntity<com.kishore.payments.railsim.api.RailController.ReceivedSummary> railSimulatorReceivedOne(String railId, String uetr) {
        return railSimulatorClient.getForEntity(
                railSimulatorUrl("/rail/" + railId + "/received/" + uetr),
                com.kishore.payments.railsim.api.RailController.ReceivedSummary.class);
    }

    /** GET /rail/{railId}/received/{uetr}/raw -- the exact bytes rail-simulator received, for byte-for-byte fidelity checks. */
    protected ResponseEntity<byte[]> railSimulatorReceivedRaw(String railId, String uetr) {
        return railSimulatorClient.getForEntity(railSimulatorUrl("/rail/" + railId + "/received/" + uetr + "/raw"), byte[].class);
    }

    /**
     * POSTs raw pacs.008 bytes directly to the embedded rail-simulator,
     * bypassing settlement-gateway and DispatchOrchestrator entirely --
     * for a test that needs to simulate an already-redispatched original
     * arriving late (its own stored {@code dispatch_record.request_payload},
     * re-delivered), independent of whether this service would ever
     * actually redeliver it itself. A blocking POST, exactly like a real
     * dispatch: it returns (or throws, for a scenario that drops the
     * connection) only once rail-simulator has fully decided how to answer.
     */
    protected ResponseEntity<String> railSimulatorPostPayment(String railId, byte[] pacs008Xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return railSimulatorClient.postForEntity(
                railSimulatorUrl("/rail/" + railId + "/payments"), new HttpEntity<>(pacs008Xml, headers), String.class);
    }

    /**
     * Seeds an instruction directly at ROUTED with its seed instruction_event
     * chain and a payments.routed outbox row -- exactly what processing-service
     * would have committed, reproduced by hand since this module has no
     * processing path of its own.
     */
    protected PaymentInstructionEntity seedRoutedInstruction(BigDecimal amount, String currency, String rail) {
        PaymentInstructionEntity entity = new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "Alice Debtor",
                "DE89370400440532013000",
                "CHASUS33XXX",
                "Bob Creditor",
                "ACCT-" + UUID.randomUUID().toString().substring(0, 8),
                "DEUTDEFFXXX",
                amount,
                currency,
                "SHAR",
                LocalDate.now(clock));
        entity.setSettlementDate(LocalDate.now(clock).plusDays(1));
        entity.setSelectedRail(rail);
        entity.setCorrespondentBic("DEUTDEFFXXX");
        entity.setNostroAccount("NOSTRO-1");

        jdbc.update(
                "INSERT INTO core.payment_instruction (instruction_id, raw_message_id, uetr, end_to_end_id, state, state_version, "
                        + "debtor_name, debtor_account, debtor_agent_bic, creditor_name, creditor_account, creditor_agent_bic, "
                        + "amount, currency, charge_bearer, requested_exec_date, settlement_date, selected_rail, correspondent_bic, nostro_account) "
                        + "VALUES (?, ?, ?, ?, 'ROUTED'::core.instruction_state, 4, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                entity.getChargeBearer(),
                entity.getRequestedExecDate(),
                entity.getSettlementDate(),
                entity.getSelectedRail(),
                entity.getCorrespondentBic(),
                entity.getNostroAccount());

        for (int seq = 1; seq <= 4; seq++) {
            String from = seq == 1 ? null : stateAt(seq - 1);
            String to = stateAt(seq);
            jdbc.update(
                    "INSERT INTO core.instruction_event (instruction_id, sequence_no, from_state, to_state, actor_type, actor_id) "
                            + "VALUES (?, ?, " + (from == null ? "NULL" : "?::core.instruction_state") + ", ?::core.instruction_state, 'SYSTEM', 'test-seed')",
                    from == null
                            ? new Object[] {entity.getInstructionId(), seq, to}
                            : new Object[] {entity.getInstructionId(), seq, from, to});
        }

        jdbc.update(
                "INSERT INTO core.outbox (aggregate_id, topic, partition_key, headers, payload) VALUES (?, 'payments.routed', ?, '{}'::jsonb, ?::jsonb)",
                entity.getInstructionId(),
                entity.getInstructionId().toString(),
                routedEventJson(entity));
        return entity;
    }

    private static String stateAt(int seq) {
        return switch (seq) {
            case 1 -> "RECEIVED";
            case 2 -> "VALIDATED";
            case 3 -> "ENRICHED";
            case 4 -> "ROUTED";
            default -> throw new IllegalArgumentException("seq " + seq);
        };
    }

    private String routedEventJson(PaymentInstructionEntity entity) {
        return "{"
                + "\"instruction_id\":\"" + entity.getInstructionId() + "\","
                + "\"uetr\":\"" + entity.getUetr() + "\","
                + "\"end_to_end_id\":\"" + entity.getEndToEndId() + "\","
                + "\"sequence_no\":4,"
                + "\"occurred_at\":\"" + OffsetDateTime.now(clock) + "\","
                + "\"amount\":" + entity.getAmount() + ","
                + "\"currency\":\"" + entity.getCurrency() + "\","
                + "\"selected_rail\":\"" + entity.getSelectedRail() + "\","
                + "\"correspondent_bic\":\"" + entity.getCorrespondentBic() + "\","
                + "\"nostro_account\":\"" + entity.getNostroAccount() + "\","
                + "\"settlement_date\":\"" + entity.getSettlementDate() + "\","
                + "\"event_version\":1}";
    }

    protected InstructionState awaitState(UUID instructionId, Duration timeout) {
        return awaitState(instructionId, timeout,
                InstructionState.SETTLED, InstructionState.RETURNED, InstructionState.EXCEPTION,
                InstructionState.SENT_UNCONFIRMED, InstructionState.SENT);
    }

    protected InstructionState awaitState(UUID instructionId, Duration timeout, InstructionState... anyOf) {
        long deadline = System.nanoTime() + timeout.toNanos();
        InstructionState last = null;
        java.util.Set<InstructionState> targets = java.util.Set.of(anyOf);
        while (System.nanoTime() < deadline) {
            last = instructions.findById(instructionId).map(PaymentInstructionEntity::getState).orElse(null);
            if (targets.contains(last)) {
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
}
