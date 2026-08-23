package com.kishore.payments.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionRepository;
import com.kishore.payments.exception.classifier.ClassifierRequest;
import com.kishore.payments.exception.classifier.PromptRedactor;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.IntFunction;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared machinery for {@link EvaluationSetGeneratorPartA} and {@link EvaluationSetGeneratorPartB}
 * (.notes/reports/PHASE-11-REPORT.md section 4): split into two parts, run as two separate JVMs
 * (two separate {@code mvn test -Dtest=...} invocations), because running intake + processing +
 * gateway + rail-simulator + exception-service all at once (needed only for the confirmation-stage
 * categories) pushed this environment's Docker VM over its memory budget and got Postgres itself
 * OOM-killed mid-run ("FATAL: terminating connection due to unexpected postmaster exit"). Part A
 * (validation/enrichment/routing categories, 140 of the 200 cases) never needs a rail at all and
 * runs with three services; Part B (confirmation-stage rail rejections, 60 cases) needs all five,
 * but only for the smaller share of the set. See {@link EvaluationSetCombiner} for the shuffle,
 * train/holdout split, and final {@code evaluation/labels/*.jsonl} write.
 */
abstract class EvaluationCaseGeneratorSupport {

    /** Caps every category's count for a quick smoke run: {@code -Deval.generator.limit=2}. Unset (default) generates the full set. */
    static final int CATEGORY_LIMIT = Integer.getInteger("eval.generator.limit", Integer.MAX_VALUE);

    private final ObjectMapper json = new ObjectMapper();
    private final PromptRedactor redactor = new PromptRedactor(Clock.systemUTC());
    private final List<LabeledCase> cases = new ArrayList<>();
    private int sequence;

    abstract PostgreSQLContainer<?> postgres();

    abstract ConfigurableApplicationContext intakeContext();

    abstract ConfigurableApplicationContext processingContext();

    abstract String outputFileName();

    List<LabeledCase> cases() {
        return cases;
    }

    /**
     * A rotating amount, spanning four different {@code PromptRedactor.amountBand} buckets, so
     * cases within one category don't all redact down to the literally identical
     * classifierRequest payload -- discovered only once the evaluation harness reported 200
     * "cases" collapsing to 15 unique inputs (see .notes/reports/PHASE-11-REPORT.md section 9.4):
     * the allowlist redaction is *designed* to erase exactly the details (account digits, names)
     * that would otherwise have varied case-to-case, and amount is one of the only remaining
     * dimensions still safe and meaningful to vary without touching a category's own semantics
     * (every rail/currency combination used in this generator accepts every one of these four
     * amounts). This does not fully solve the underlying limitation -- see that report section --
     * but it is the honest ceiling given what the allowlist permits through at all.
     */
    private static final List<String> ROTATING_AMOUNTS = List.of("750.00", "1500.00", "25000.00", "250000.00");

    /**
     * Same rotation, but capped below 100,000: {@code no-nostro-account} needs an amount that
     * resolves unambiguously to ACH_EQUIV (the rail its ground-truth label is specifically
     * about), and 100,000-149,999.99 is a genuine overlap where {@code RailSelector} could pick
     * FEDWIRE instead (see {@code V2__refdata_schema.sql}'s own comment on the overlap).
     */
    private static final List<String> ROTATING_AMOUNTS_ACH_ONLY = List.of("750.00", "1500.00", "25000.00", "75000.00");

    static String rotatingAmount(int i) {
        return ROTATING_AMOUNTS.get(i % ROTATING_AMOUNTS.size());
    }

    static String rotatingAchOnlyAmount(int i) {
        return ROTATING_AMOUNTS_ACH_ONLY.get(i % ROTATING_AMOUNTS_ACH_ONLY.size());
    }

    void generateCategory(
            String categoryName, int count, FailureStage failureStage, String groundTruthReasonCode, Repairability groundTruthRepairability,
            String justification, boolean ambiguous, IntFunction<Fixture> fixtureSupplier) throws Exception {
        int actualCount = Math.min(count, CATEGORY_LIMIT);
        for (int i = 0; i < actualCount; i++) {
            Fixture fixture = fixtureSupplier.apply(i);
            UUID instructionId = submit(fixture);
            CaseRow caseRow = awaitCase(instructionId);

            ClassifierRequest request = redactor.redact(
                    failureStage, processingContext().getBean(PaymentInstructionRepository.class).findById(instructionId).orElseThrow(),
                    new InstructionExceptionEvent.Detail(caseRow.reasonCode, caseRow.repairability, fixture.expectedField, caseRow.reasonDetail),
                    caseRow.repairAttempts);

            sequence++;
            String caseId = "eval-" + String.format("%04d", sequence) + "-" + categoryName;
            cases.add(new LabeledCase(
                    caseId, categoryName, failureStage.name(), caseRow.reasonCode, caseRow.repairability.name(),
                    json.valueToTree(request), groundTruthReasonCode, groundTruthRepairability.name(), justification, ambiguous));
        }
    }

    void writeOutput() throws Exception {
        Path labelsDir = Path.of("..", "evaluation", "labels");
        Files.createDirectories(labelsDir);
        List<String> lines = new ArrayList<>();
        for (LabeledCase row : cases) {
            lines.add(json.writeValueAsString(row));
        }
        Files.write(labelsDir.resolve(outputFileName()), lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote " + cases.size() + " cases to evaluation/labels/" + outputFileName());
    }

    private UUID submit(Fixture fixture) {
        int intakePort = Integer.parseInt(intakeContext().getEnvironment().getProperty("local.server.port"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> response = new TestRestTemplate().postForEntity(
                "http://localhost:" + intakePort + "/v1/instructions", new HttpEntity<>(fixture.toXml(), headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Submission failed: " + response.getStatusCode() + " " + response.getBody());
        }
        try {
            JsonNode body = json.readTree(response.getBody());
            return UUID.fromString(body.path("instructionId").asText());
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse submission response: " + response.getBody(), e);
        }
    }

    private CaseRow awaitCase(UUID instructionId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            CaseRow row = queryCase(instructionId);
            if (row != null) {
                return row;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("No exception case opened for instruction " + instructionId + " within 30s");
    }

    private CaseRow queryCase(UUID instructionId) throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres().getJdbcUrl(), postgres().getUsername(), postgres().getPassword());
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT reason_code, reason_detail, repairability, repair_attempts FROM exceptions.exception_case "
                                + "WHERE instruction_id = ? ORDER BY opened_at DESC LIMIT 1")) {
            ps.setObject(1, instructionId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return new CaseRow(
                    rs.getString("reason_code"), rs.getString("reason_detail"), Repairability.valueOf(rs.getString("repairability")),
                    rs.getInt("repair_attempts"));
        }
    }

    /**
     * Every rule below resolves to an asynchronous pacs.002 callback regardless of {@code
     * acceptResponse} -- REJECT_SYNC is not actually synchronous at the HTTP layer either (see
     * {@code AcceptResponse}'s own javadoc: "a real rail never reports final disposition
     * synchronously"), it only forces the eventual confirmation to RJCT. So {@code
     * statusCallbackUrl} has to resolve to the real settlement-gateway callback endpoint, not the
     * always-broken {@code http://localhost:1/unused} placeholder {@code
     * ServiceBoot.railSimulatorArgs()} bakes in for tests that never needed real delivery -- hence
     * this is called only once the gateway is actually up (see {@code EvaluationSetGeneratorPartB},
     * which boots rail-simulator, then gateway with the rail's now-known port, then loads this
     * scenario with the gateway's own now-known port).
     */
    static void loadRejectionScenario(int railPort, int gatewayPort) {
        String yaml = """
                rail: eval-set-rejections
                statusCallbackUrl: http://localhost:%d/callbacks/rail/SEPA/status
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: ACSC
                  confirmationDelayMs: 0
                  recordBeforeTimeout: true
                rules:
                  - match: { creditorAccountEndsWith: "AC04" }
                    acceptResponse: ACCEPT
                    confirmation: RJCT
                    rejectReasonCode: AC04
                  - match: { creditorAccountEndsWith: "AC06" }
                    acceptResponse: REJECT_SYNC
                    rejectReasonCode: AC06
                  - match: { creditorAccountEndsWith: "AM04" }
                    acceptResponse: REJECT_SYNC
                    rejectReasonCode: AM04
                  - match: { creditorAccountEndsWith: "BE01" }
                    acceptResponse: REJECT_SYNC
                    rejectReasonCode: BE01
                  - match: { creditorAccountEndsWith: "MD07" }
                    acceptResponse: ACCEPT
                    confirmation: RJCT
                    rejectReasonCode: MD07
                  - match: { creditorAccountEndsWith: "RR04" }
                    acceptResponse: REJECT_SYNC
                    rejectReasonCode: RR04
                """.formatted(gatewayPort);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Void> response = new TestRestTemplate().postForEntity(
                "http://localhost:" + railPort + "/rail/SEPA/scenario", new HttpEntity<>(yaml.getBytes(), headers), Void.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Scenario load failed: " + response.getStatusCode());
        }
    }

    // -----------------------------------------------------------------------------------------
    // IBAN construction: valid mod-97 checksums by default, with an optional trailing marker
    // (matched by the rail scenario's creditorAccountEndsWith rules) or a deliberately corrupted
    // checksum for validation-failure cases.

    static String validIban(String countryCode, String bban) {
        return countryCode + checkDigits(countryCode, bban) + bban;
    }

    static String ibanWithSuffixMarker(String countryCode, String bbanPrefix, String marker) {
        String bban = bbanPrefix + marker;
        return countryCode + checkDigits(countryCode, bban) + bban;
    }

    static String corruptedIban(String countryCode, String bban) {
        String valid = validIban(countryCode, bban);
        String correctCheckDigits = valid.substring(2, 4);
        String wrongCheckDigits = correctCheckDigits.equals("00") ? "01" : "00";
        return countryCode + wrongCheckDigits + bban;
    }

    private static String checkDigits(String countryCode, String bban) {
        String rearranged = (bban + countryCode + "00").toUpperCase(Locale.ROOT);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else {
                numeric.append(Character.toUpperCase(c) - 'A' + 10);
            }
        }
        BigInteger value = new BigInteger(numeric.toString());
        int check = 98 - value.mod(BigInteger.valueOf(97)).intValue();
        return String.format("%02d", check);
    }

    static Fixture fixture() {
        return new Fixture();
    }

    /** Builder for one pain.001 fixture, defaulting to a fully valid EUR/DEUTDEFFXXX/SEPA-eligible payment. */
    static final class Fixture {
        String debtorIban = validIban("DE", "370400440532013000");
        String creditorIban = validIban("FR", "20041010050500013456789");
        String debtorBic = "DEUTDEFFXXX";
        String creditorBic = "DEUTDEFFXXX";
        String currency = "EUR";
        String amount = "1500.00";
        LocalDate requestedExecDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        String expectedField;

        Fixture debtorIban(String value) {
            this.debtorIban = value;
            this.expectedField = "debtorAccount";
            return this;
        }

        Fixture creditorIban(String value) {
            this.creditorIban = value;
            this.expectedField = "creditorAccount";
            return this;
        }

        Fixture creditorIbanSuffixMarker(String marker) {
            this.creditorIban = ibanWithSuffixMarker("FR", "2004101005050001345", marker);
            return this;
        }

        Fixture debtorBic(String value) {
            this.debtorBic = value;
            this.expectedField = "debtorAgentBic";
            return this;
        }

        Fixture creditorBic(String value) {
            this.creditorBic = value;
            this.expectedField = "creditorAgentBic";
            return this;
        }

        Fixture currency(String value) {
            this.currency = value;
            return this;
        }

        Fixture amount(String value) {
            this.amount = value;
            return this;
        }

        Fixture requestedExecDate(LocalDate value) {
            this.requestedExecDate = value;
            return this;
        }

        Fixture build() {
            return this;
        }

        String toXml() {
            String endToEndId = "EVAL-" + UUID.randomUUID().toString().substring(0, 8);
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.09\">\n"
                    + "  <CstmrCdtTrfInitn>\n"
                    + "    <GrpHdr>\n"
                    + "      <MsgId>MSG-" + endToEndId + "</MsgId>\n"
                    + "      <CreDtTm>" + LocalDate.now(ZoneOffset.UTC) + "T10:00:00</CreDtTm>\n"
                    + "      <NbOfTxs>1</NbOfTxs>\n"
                    + "      <InitgPty><Nm>Acme Gmbh</Nm></InitgPty>\n"
                    + "    </GrpHdr>\n"
                    + "    <PmtInf>\n"
                    + "      <PmtInfId>PMTINF-" + endToEndId + "</PmtInfId>\n"
                    + "      <PmtMtd>TRF</PmtMtd>\n"
                    + "      <ReqdExctnDt><Dt>" + requestedExecDate + "</Dt></ReqdExctnDt>\n"
                    + "      <Dbtr><Nm>Acme Gmbh</Nm></Dbtr>\n"
                    + "      <DbtrAcct><Id><IBAN>" + debtorIban + "</IBAN></Id></DbtrAcct>\n"
                    + "      <DbtrAgt><FinInstnId><BICFI>" + debtorBic + "</BICFI></FinInstnId></DbtrAgt>\n"
                    + "      <ChrgBr>SLEV</ChrgBr>\n"
                    + "      <CdtTrfTxInf>\n"
                    + "        <PmtId><EndToEndId>" + endToEndId + "</EndToEndId></PmtId>\n"
                    + "        <Amt><InstdAmt Ccy=\"" + currency + "\">" + amount + "</InstdAmt></Amt>\n"
                    + "        <CdtrAgt><FinInstnId><BICFI>" + creditorBic + "</BICFI></FinInstnId></CdtrAgt>\n"
                    + "        <Cdtr><Nm>Beneficiary SARL</Nm></Cdtr>\n"
                    + "        <CdtrAcct><Id><IBAN>" + creditorIban + "</IBAN></Id></CdtrAcct>\n"
                    + "      </CdtTrfTxInf>\n"
                    + "    </PmtInf>\n"
                    + "  </CstmrCdtTrfInitn>\n"
                    + "</Document>\n";
        }
    }

    private record CaseRow(String reasonCode, String reasonDetail, Repairability repairability, int repairAttempts) {
    }

    record LabeledCase(
            String caseId, String category, String failureStage, String reasonCodeDeterministic, String repairabilityDeterministic,
            JsonNode classifierRequest, String groundTruthReasonCode, String groundTruthRepairability, String labelJustification,
            boolean ambiguous) {
    }
}
