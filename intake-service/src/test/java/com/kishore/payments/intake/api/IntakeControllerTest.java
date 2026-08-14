package com.kishore.payments.intake.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.intake.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class IntakeControllerTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void validSubmissionReturns202WithAWellFormedUuidPair() throws IOException {
        // Unique reference so this test's outcome doesn't depend on whether
        // some other test already submitted valid-single-eur.xml unmodified
        // (only sameReferenceWithDifferentCreditorReturns409NamingTheConflict
        // does that, deliberately, since conflicting-reference.xml is built
        // to match its exact reference).
        ResponseEntity<Map> response = post(withUniqueReference(sample("valid-single-eur.xml"), "E2E-EUR-0001"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(UUID.fromString((String) response.getBody().get("instructionId"))).isNotNull();
        assertThat(UUID.fromString((String) response.getBody().get("uetr"))).isNotNull();
        assertThat(response.getBody().get("duplicate")).isEqualTo(false);
    }

    @Test
    void sameDocumentSubmittedThreeTimesYieldsOneRowAndTheOriginalUetrEachTime() throws IOException {
        byte[] body = sample("valid-with-uetr.xml");

        ResponseEntity<Map> first = post(body);
        ResponseEntity<Map> second = post(body);
        ResponseEntity<Map> third = post(body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);

        String uetr = (String) first.getBody().get("uetr");
        assertThat(second.getBody().get("uetr")).isEqualTo(uetr);
        assertThat(third.getBody().get("uetr")).isEqualTo(uetr);
        assertThat(second.getBody().get("duplicate")).isEqualTo(true);
        assertThat(third.getBody().get("duplicate")).isEqualTo(true);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM core.payment_instruction WHERE uetr = ?::uuid", Integer.class, uetr);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void payloadSha256MatchesTheReceivedBytesExactly() throws IOException, NoSuchAlgorithmException {
        byte[] body = sample("valid-single-usd.xml");

        post(body);

        byte[] expected = MessageDigest.getInstance("SHA-256").digest(body);
        byte[] actual = jdbc.queryForObject(
                "SELECT payload_sha256 FROM intake.raw_message ORDER BY received_at DESC LIMIT 1", byte[].class);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void malformedXmlReturns400AndNeverCreatesAnInstruction() throws IOException {
        int before = countRawMessages();

        ResponseEntity<Map> response = post(sample("malformed.xml"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("MALFORMED_XML");
        assertThat(countRawMessages()).isEqualTo(before + 1);
        Boolean schemaValid = jdbc.queryForObject(
                "SELECT schema_valid FROM intake.raw_message ORDER BY received_at DESC LIMIT 1", Boolean.class);
        assertThat(schemaValid).isFalse();
    }

    @Test
    void schemaInvalidXmlReturns422AndRawBytesAreStillPersisted() throws IOException {
        int before = countRawMessages();

        ResponseEntity<Map> response = post(sample("schema-invalid-missing-amount.xml"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("error")).isEqualTo("SCHEMA_INVALID");
        assertThat((List<?>) response.getBody().get("violations")).isNotEmpty();
        assertThat(countRawMessages()).isEqualTo(before + 1);
    }

    @Test
    void badDateSchemaInvalidXmlReturns422() throws IOException {
        ResponseEntity<Map> response = post(sample("schema-invalid-bad-date.xml"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("error")).isEqualTo("SCHEMA_INVALID");
    }

    @Test
    void aReceivedEventExistsWithSequenceOneAndNullFromState() throws IOException {
        ResponseEntity<Map> response = post(withUniqueReference(sample("valid-single-eur.xml"), "E2E-EUR-0001"));
        String instructionId = (String) response.getBody().get("instructionId");

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT sequence_no, from_state, to_state FROM core.instruction_event WHERE instruction_id = ?::uuid",
                instructionId);

        assertThat(event.get("sequence_no")).isEqualTo(1);
        assertThat(event.get("from_state")).isNull();
        assertThat(event.get("to_state")).isEqualTo("RECEIVED");
    }

    @Test
    void twoDocumentsDifferingOnlyInEndToEndIdProduceTwoDistinctInstructions() throws IOException {
        String original = new String(sample("valid-single-eur.xml"), StandardCharsets.UTF_8);
        byte[] first = original.replace("E2E-EUR-0001", "E2E-DISTINCT-A").getBytes(StandardCharsets.UTF_8);
        byte[] second = original.replace("E2E-EUR-0001", "E2E-DISTINCT-B").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map> firstResponse = post(first);
        ResponseEntity<Map> secondResponse = post(second);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(firstResponse.getBody().get("instructionId")).isNotEqualTo(secondResponse.getBody().get("instructionId"));
    }

    @Test
    void endToEndIdNotProvidedReturns422() throws IOException {
        String original = new String(sample("valid-single-eur.xml"), StandardCharsets.UTF_8);
        byte[] body = original.replace("E2E-EUR-0001", "NOTPROVIDED").getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Map> response = post(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("error")).isEqualTo("END_TO_END_ID_NOT_PROVIDED");
    }

    @Test
    void sameReferenceWithDifferentCreditorReturns409NamingTheConflict() throws IOException {
        // The one test in this class allowed to submit valid-single-eur.xml
        // unmodified: conflicting-reference.xml is a committed fixture built
        // specifically to match its (debtor_account, end_to_end_id) exactly.
        ResponseEntity<Map> original = post(sample("valid-single-eur.xml"));
        assertThat(original.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map> conflict = post(sample("conflicting-reference.xml"));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("error")).isEqualTo("REFERENCE_CONFLICT");
        @SuppressWarnings("unchecked")
        List<String> conflictingFields = (List<String>) conflict.getBody().get("conflictingFields");
        assertThat(conflictingFields).containsExactly("creditorAccount");
        assertThat(conflict.getBody().get("existingUetr")).isEqualTo(original.getBody().get("uetr"));
    }

    @Test
    void wrongContentTypeReturns415() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response =
                restTemplate.postForEntity("/v1/instructions", new HttpEntity<>("{}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void emptyBodyReturns400() {
        // A genuinely empty body never reaches IntakeController: Spring MVC's
        // required-@RequestBody check rejects it upstream with its own 400,
        // not our MALFORMED_XML shape. Still a 400, which is what "400 or
        // 415, as appropriate" calls for.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<Map> response =
                restTemplate.postForEntity("/v1/instructions", new HttpEntity<>(new byte[0], headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map> post(byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return restTemplate.postForEntity("/v1/instructions", new HttpEntity<>(body, headers), Map.class);
    }

    private int countRawMessages() {
        return jdbc.queryForObject("SELECT count(*) FROM intake.raw_message", Integer.class);
    }

    private static byte[] withUniqueReference(byte[] template, String originalEndToEndId) {
        String xml = new String(template, StandardCharsets.UTF_8);
        String unique = originalEndToEndId + "-" + UUID.randomUUID().toString().substring(0, 8);
        return xml.replace(originalEndToEndId, unique).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sample(String name) throws IOException {
        try (InputStream in = IntakeControllerTest.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Sample not found on classpath: " + name);
            }
            return in.readAllBytes();
        }
    }
}
