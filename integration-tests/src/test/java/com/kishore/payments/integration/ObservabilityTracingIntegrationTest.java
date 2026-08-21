package com.kishore.payments.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kishore.payments.gateway.SettlementGatewayApplication;
import com.kishore.payments.intake.IntakeServiceApplication;
import com.kishore.payments.processing.ProcessingServiceApplication;
import com.kishore.payments.railsim.RailSimulatorApplication;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase 10's own acceptance test, per .notes/reports/PHASE-10-REPORT.md section 8: submit one
 * pain.001 and retrieve a single trace spanning intake, the outbox publisher, all three processing
 * stages, the gateway, and the rail call -- one trace, not six. Every other test in this module
 * stops at ROUTED (processing-service's own last state); this is the first to boot settlement-gateway
 * and rail-simulator too, since only a real dispatch actually exercises the gateway-to-rail hop the
 * brief calls out as the one that has to work ("the Kafka hop is the part that takes work").
 *
 * <p>Traces are verified against a real Jaeger instance (Testcontainers), not a mock exporter --
 * every service's {@code management.otlp.tracing.endpoint} is pointed at it, and the assertions
 * query Jaeger's own HTTP API for the trace tagged with this test's instruction_id. That single
 * query, by itself, is also the outbox-trace-continuity assertion: the {@code outbox.publish} spans
 * it returns could only be present, under the same trace ID, if {@code OutboxWriter} correctly
 * captured trace context into the outbox row and {@code OutboxPublisher} correctly restored it.
 */
@Testcontainers
class ObservabilityTracingIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");

    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4");

    static final GenericContainer<?> JAEGER = new GenericContainer<>(DockerImageName.parse("jaegertracing/all-in-one:1.57"))
            .withEnv("COLLECTOR_OTLP_ENABLED", "true")
            .withExposedPorts(4318, 16686)
            .waitingFor(Wait.forHttp("/").forPort(16686));

    static {
        POSTGRES.start();
        REDPANDA.start();
        JAEGER.start();
    }

    private final ObjectMapper json = new ObjectMapper();

    private ConfigurableApplicationContext railContext;
    private ConfigurableApplicationContext gatewayContext;
    private ConfigurableApplicationContext processingContext;
    private ConfigurableApplicationContext intakeContext;

    @AfterEach
    void tearDownContexts() {
        Stream.of(intakeContext, processingContext, gatewayContext, railContext)
                .filter(java.util.Objects::nonNull)
                .forEach(ConfigurableApplicationContext::close);
    }

    @AfterAll
    static void tearDownContainers() {
        JAEGER.stop();
        REDPANDA.stop();
        POSTGRES.stop();
    }

    private String otlpEndpoint() {
        return "http://localhost:" + JAEGER.getMappedPort(4318) + "/v1/traces";
    }

    private List<String> tracingArgs() {
        return List.of(
                "--management.otlp.tracing.endpoint=" + otlpEndpoint(), "--management.tracing.sampling.probability=1.0");
    }

    private ConfigurableApplicationContext bootWithTracing(
            Class<?> applicationClass, String serviceName, List<String> baseArgs) {
        List<String> args = Stream.concat(baseArgs.stream(), tracingArgs().stream()).toList();
        return ServiceBoot.boot(new SpringApplicationBuilder(applicationClass), POSTGRES, REDPANDA, serviceName, args);
    }

    @Test
    void aPain001SubmissionProducesOneTraceSpanningEveryServiceIncludingTheRailCall() throws Exception {
        railContext = bootWithTracing(RailSimulatorApplication.class, "rail-simulator", ServiceBoot.railSimulatorArgs());
        int railPort = Integer.parseInt(railContext.getEnvironment().getProperty("local.server.port"));
        loadAcceptingScenario(railPort);

        gatewayContext = bootWithTracing(
                SettlementGatewayApplication.class, "settlement-gateway", ServiceBoot.gatewayArgs("http://localhost:" + railPort));
        processingContext = bootWithTracing(ProcessingServiceApplication.class, "processing-service", ServiceBoot.processingArgs());
        intakeContext = bootWithTracing(IntakeServiceApplication.class, "intake-service", ServiceBoot.intakeArgs());

        // Every service shares one JVM (and so one Logback LoggerContext) in this module, and each
        // boot above re-applies its own logback-spring.xml to that same shared root logger -- byte
        // -identical across all five services, so whichever booted last (intake, here) leaves the
        // config every subsequent log call from any of the four running apps' threads goes
        // through. Attached after the last boot, this captures every service's own log output for
        // the acceptance check below (.notes/reports/PHASE-10-REPORT.md section 7: log output
        // contains no full account numbers and no party names across all services).
        ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        rootLogger.addAppender(logCapture);

        String endToEndId = "E2E-TRACE-" + UUID.randomUUID().toString().substring(0, 8);
        UUID instructionId = submit(endToEndId);

        String finalState = awaitState(endToEndId, Duration.ofSeconds(15));
        assertThat(finalState).as("a valid EUR payment through the accepting SEPA scenario should reach at least SENT")
                .isIn("SENT", "SENT_UNCONFIRMED", "SETTLED");

        rootLogger.detachAppender(logCapture);
        assertNoPiiInCapturedLogs(logCapture);

        JsonNode trace = awaitTraceForInstruction(instructionId, Duration.ofSeconds(30));

        JsonNode processes = trace.path("processes");
        java.util.Set<String> serviceNames = new java.util.HashSet<>();
        processes.fieldNames().forEachRemaining(processId -> serviceNames.add(processes.path(processId).path("serviceName").asText()));
        assertThat(serviceNames)
                .as("one trace should span every hop, not one disconnected trace per topic boundary")
                .contains("intake-service", "processing-service", "settlement-gateway", "rail-simulator");

        List<String> operationNames = new java.util.ArrayList<>();
        trace.path("spans").forEach(span -> operationNames.add(span.path("operationName").asText()));
        assertThat(operationNames)
                .as("the outbox publisher's own span must be part of this same trace -- otherwise trace context did not "
                        + "survive the outbox write/publish boundary and every trace ends at the database write")
                .anyMatch(name -> name.contains("outbox.publish"));
    }

    /**
     * The debtor ("Acme Gmbh"), creditor ("Beneficiary SARL") and both IBANs in {@link #painXml}
     * are chosen so a leak would be unambiguous: neither name is a generic English word that could
     * appear in a framework log line by coincidence, and both IBANs contain an unbroken run of 8+
     * digits the masking converter must reduce to its last four. Every captured line, from every
     * one of the four services, is checked -- not just intake's own submission-handling logs.
     */
    private void assertNoPiiInCapturedLogs(ListAppender<ILoggingEvent> logCapture) {
        List<String> messages = logCapture.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).as("no captured log line should contain the debtor name verbatim")
                .noneMatch(m -> m.contains("Acme Gmbh"));
        assertThat(messages).as("no captured log line should contain the creditor name verbatim")
                .noneMatch(m -> m.contains("Beneficiary SARL"));
        assertThat(messages).as("no captured log line should contain the debtor IBAN's full digit run unmasked")
                .noneMatch(m -> m.contains("370400440532013000"));
        assertThat(messages).as("no captured log line should contain the creditor IBAN's full digit run unmasked")
                .noneMatch(m -> m.contains("20041010050500013"));
    }

    private void loadAcceptingScenario(int railPort) {
        String yaml = "rail: sepa-accept\n"
                + "default:\n"
                + "  acceptResponse: ACCEPT\n"
                + "  acceptDelayMs: 0\n"
                + "  confirmation: ACSC\n"
                + "  confirmationDelayMs: 0\n"
                + "  recordBeforeTimeout: true\n";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Void> response = new TestRestTemplate().postForEntity(
                "http://localhost:" + railPort + "/rail/SEPA/scenario", new HttpEntity<>(yaml.getBytes(), headers), Void.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("scenario load should succeed").isTrue();
    }

    private UUID submit(String endToEndId) throws Exception {
        int intakePort = Integer.parseInt(intakeContext.getEnvironment().getProperty("local.server.port"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> response = new TestRestTemplate().postForEntity(
                "http://localhost:" + intakePort + "/v1/instructions", new HttpEntity<>(painXml(endToEndId), headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("submission should be accepted").isTrue();
        JsonNode body = json.readTree(response.getBody());
        return UUID.fromString(body.path("instructionId").asText());
    }

    private String currentState(String endToEndId) {
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = conn.prepareStatement("SELECT state FROM core.payment_instruction WHERE end_to_end_id = ?")) {
            ps.setString(1, endToEndId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String awaitState(String endToEndId, Duration timeout) {
        return awaitUntilNonNull(timeout, () -> {
            String state = currentState(endToEndId);
            return (state != null && !state.equals("RECEIVED") && !state.equals("VALIDATED") && !state.equals("ENRICHED")
                    && !state.equals("ROUTED")) ? state : null;
        });
    }

    /**
     * Polls Jaeger's own query API by the {@code instruction_id} tag {@link
     * com.kishore.payments.core.state.InstructionStateWriter} sets on every span -- proving the
     * acceptance criterion literally, not just approximately: the whole trace is findable from that
     * one identifier alone. Polling accounts for the OTel batch span processor's own export delay
     * (spans are not sent to the collector the instant they end).
     *
     * <p>Queried under {@code processing-service}, not {@code intake-service}: {@link
     * com.kishore.payments.core.state.InstructionStateWriter#transition} is where the tag is set,
     * and intake-service's own initial RECEIVED row is created by a direct insert, not a
     * transition -- see .notes/reports/PHASE-10-REPORT.md section 5 for why that's a real,
     * separately-noted gap rather than an oversight in this test. The fetched trace (by ID, once
     * found) still contains every service's spans regardless of which one the search itself was
     * anchored to.
     */
    private JsonNode awaitTraceForInstruction(UUID instructionId, Duration timeout) throws Exception {
        String tags = java.net.URLEncoder.encode("{\"instruction_id\":\"" + instructionId + "\"}", java.nio.charset.StandardCharsets.UTF_8);
        String url = "http://localhost:" + JAEGER.getMappedPort(16686) + "/api/traces?service=processing-service&tags=" + tags;

        // getForEntity(String, ...) treats its argument as a URI *template* and re-encodes any
        // '%' it finds even with zero {} placeholders present, double-encoding the already-percent
        // -encoded tags param above and producing an invalid '%25...' sequence Jaeger rejects.
        // Passing a pre-built URI instead skips template expansion entirely.
        URI traceSearchUri = URI.create(url);
        JsonNode data = awaitUntilNonNull(timeout, () -> {
            ResponseEntity<String> response = new TestRestTemplate().getForEntity(traceSearchUri, String.class);
            try {
                JsonNode body = json.readTree(response.getBody());
                JsonNode traces = body.path("data");
                return traces.isArray() && traces.size() > 0 ? traces.get(0) : null;
            } catch (Exception e) {
                return null;
            }
        });
        assertThat(data).as("Jaeger should have a trace tagged with instruction_id=" + instructionId + " within " + timeout).isNotNull();
        return data;
    }

    private <T> T awaitUntilNonNull(Duration timeout, Supplier<T> value) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last = null;
        while (System.nanoTime() < deadline) {
            last = value.get();
            if (last != null) {
                return last;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return last;
    }

    /** Same fixture shape as IntakeToProcessingPipelineTest: DEUTDEFFXXX both sides, seeded for EUR/SEPA. */
    private String painXml(String endToEndId) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n"
                + "  <CstmrCdtTrfInitn>\n"
                + "    <GrpHdr>\n"
                + "      <MsgId>MSG-" + endToEndId + "</MsgId>\n"
                + "      <CreDtTm>" + today + "T10:00:00</CreDtTm>\n"
                + "      <NbOfTxs>1</NbOfTxs>\n"
                + "      <InitgPty><Nm>Acme Gmbh</Nm></InitgPty>\n"
                + "    </GrpHdr>\n"
                + "    <PmtInf>\n"
                + "      <PmtInfId>PMTINF-" + endToEndId + "</PmtInfId>\n"
                + "      <PmtMtd>TRF</PmtMtd>\n"
                + "      <ReqdExctnDt><Dt>" + today + "</Dt></ReqdExctnDt>\n"
                + "      <Dbtr><Nm>Acme Gmbh</Nm></Dbtr>\n"
                + "      <DbtrAcct><Id><IBAN>DE89370400440532013000</IBAN></Id></DbtrAcct>\n"
                + "      <DbtrAgt><FinInstnId><BICFI>DEUTDEFFXXX</BICFI></FinInstnId></DbtrAgt>\n"
                + "      <ChrgBr>SLEV</ChrgBr>\n"
                + "      <CdtTrfTxInf>\n"
                + "        <PmtId><EndToEndId>" + endToEndId + "</EndToEndId></PmtId>\n"
                + "        <Amt><InstdAmt Ccy=\"EUR\">1000.00</InstdAmt></Amt>\n"
                + "        <CdtrAgt><FinInstnId><BICFI>DEUTDEFFXXX</BICFI></FinInstnId></CdtrAgt>\n"
                + "        <Cdtr><Nm>Beneficiary SARL</Nm></Cdtr>\n"
                + "        <CdtrAcct><Id><IBAN>FR1420041010050500013M02606</IBAN></Id></CdtrAcct>\n"
                + "      </CdtTrfTxInf>\n"
                + "    </PmtInf>\n"
                + "  </CstmrCdtTrfInitn>\n"
                + "</Document>\n";
    }
}
