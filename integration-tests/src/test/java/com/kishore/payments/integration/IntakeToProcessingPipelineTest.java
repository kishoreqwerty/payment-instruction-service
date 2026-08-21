package com.kishore.payments.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.intake.IntakeServiceApplication;
import com.kishore.payments.processing.ProcessingServiceApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Drives a real instruction through {@code intake-service} and {@code
 * processing-service} together -- two real Spring Boot applications, one
 * shared database, connected only by the real {@code payments.received}
 * Kafka topic and the outbox pattern each already uses in production. No
 * existing test in either module's own suite does this: each one's own
 * {@code AbstractIntegrationTest}/{@code AbstractProcessingIntegrationTest}
 * starts only its own service, so intake-service's tests stop at "was the
 * outbox row written correctly" and processing-service's tests start by
 * seeding an instruction directly at RECEIVED, never by actually receiving
 * one over HTTP from a real intake-service.
 *
 * <p>The second case here is the regression test for the creditor-IBAN
 * validation gap (see .notes/reports/CROSS-SERVICE-INTEGRATION-DEFECTS.md):
 * a payment with a checksum-invalid creditor IBAN, submitted the same way
 * an operator's own manual reproduction was, asserting it now fails
 * VALIDATION with AC01 -- not silently passing through to a coincidental,
 * unrelated ENRICHMENT failure the way it did before {@code
 * CreditorIbanFormatRule} existed.
 */
@Testcontainers
class IntakeToProcessingPipelineTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUrlParam("stringtype", "unspecified");

    static final RedpandaContainer REDPANDA = new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.4");

    static {
        POSTGRES.start();
        REDPANDA.start();
    }

    private ConfigurableApplicationContext intakeContext;
    private ConfigurableApplicationContext processingContext;

    @AfterEach
    void tearDownContexts() {
        if (intakeContext != null) {
            intakeContext.close();
        }
        if (processingContext != null) {
            processingContext.close();
        }
    }

    @AfterAll
    static void tearDownContainers() {
        REDPANDA.stop();
        POSTGRES.stop();
    }

    private void bootBoth() {
        processingContext = ServiceBoot.boot(
                new SpringApplicationBuilder(ProcessingServiceApplication.class), POSTGRES, REDPANDA, "processing-service",
                ServiceBoot.processingArgs());
        intakeContext = ServiceBoot.boot(
                new SpringApplicationBuilder(IntakeServiceApplication.class), POSTGRES, REDPANDA, "intake-service", ServiceBoot.intakeArgs());
    }

    @Test
    void aValidInstructionTraversesIntakeAndProcessingTogetherToRouted() throws SQLException {
        bootBoth();
        String endToEndId = shortId("E2E-OK-");

        ResponseEntity<String> response = submit(endToEndId, "FR1420041010050500013M02606");
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        String finalState = awaitState(endToEndId, Duration.ofSeconds(10), () -> currentState(endToEndId));
        assertThat(finalState).as("a valid EUR payment through DEUTDEFFXXX (seeded correspondent/nostro/SEPA) should reach ROUTED")
                .isEqualTo("ROUTED");
    }

    /**
     * The exact reproduction that found {@link com.kishore.payments.processing.validation.CreditorIbanFormatRule}
     * missing entirely: a creditor IBAN that fails mod-97, submitted through the real REST endpoint
     * and the real ValidationChain, not a fixture that hand-constructs a {@code PaymentInstructionEntity}
     * already past validation.
     */
    @Test
    void anInvalidCreditorIbanFailsValidationWithAc01NotAnUnrelatedEnrichmentFailure() throws SQLException {
        bootBoth();
        String endToEndId = shortId("E2E-BAD-");

        ResponseEntity<String> response = submit(endToEndId, "FR9999999999999999999X99999");
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        String finalState = awaitState(endToEndId, Duration.ofSeconds(10), () -> currentState(endToEndId));
        assertThat(finalState).isEqualTo("EXCEPTION");

        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT reason_code, reason_detail FROM core.instruction_event ie "
                                + "JOIN core.payment_instruction pi ON pi.instruction_id = ie.instruction_id "
                                + "WHERE pi.end_to_end_id = ? AND ie.to_state = 'EXCEPTION'::core.instruction_state")) {
            ps.setString(1, endToEndId);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).as("an EXCEPTION instruction_event row should exist").isTrue();
            assertThat(rs.getString("reason_code")).isEqualTo("AC01");
            assertThat(rs.getString("reason_detail")).containsIgnoringCase("creditor").contains("IBAN");
        }
    }

    private ResponseEntity<String> submit(String endToEndId, String creditorIban) {
        int intakePort = Integer.parseInt(intakeContext.getEnvironment().getProperty("local.server.port"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        String xml = painXml(endToEndId, creditorIban);
        return new TestRestTemplate().postForEntity(
                "http://localhost:" + intakePort + "/v1/instructions", new HttpEntity<>(xml, headers), String.class);
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

    private String awaitState(String endToEndId, Duration timeout, Supplier<String> currentState) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            last = currentState.get();
            if ("ROUTED".equals(last) || "EXCEPTION".equals(last)) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return last;
    }

    /** pain.001's MsgId/PmtInfId are ISO 20022 Max35Text; this stays well under that once prefixed with "MSG-"/"PMTINF-". */
    private String shortId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * DEUTDEFFXXX both sides: seeded in processing-service's own V2 migration with a correspondent,
     * nostro account and SEPA rail for EUR. The requested execution date is UTC, not the system
     * default zone: processing-service's own Clock bean is UTC (see AbstractProcessingIntegrationTest's
     * own comment on this), and a bare {@code LocalDate.now()} can already be "yesterday" relative to
     * it late in the day in any zone west of UTC -- the same skew Phase 4's own report documented.
     */
    private String painXml(String endToEndId, String creditorIban) {
        String today = LocalDate.now(java.time.ZoneOffset.UTC).toString();
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
                + "        <CdtrAcct><Id><IBAN>" + creditorIban + "</IBAN></Id></CdtrAcct>\n"
                + "      </CdtTrfTxInf>\n"
                + "    </PmtInf>\n"
                + "  </CstmrCdtTrfInitn>\n"
                + "</Document>\n";
    }
}
