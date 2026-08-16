package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PaymentIngestTest extends AbstractRailSimulatorTest {

    private static final String ACCEPT_NO_CONFIRMATION_SCENARIO = """
            rail: ingest-accept
            default:
              acceptResponse: ACCEPT
              acceptDelayMs: 0
              confirmation: NONE
              confirmationDelayMs: 0
            rules: []
            """;

    @Test
    void validPaymentIsAcceptedWithEmptyBodyAndAppearsInReceived() {
        loadScenario("fedwire", ACCEPT_NO_CONFIRMATION_SCENARIO);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        ResponseEntity<String> response = postPayment("fedwire", fixture.bytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNullOrEmpty();

        List<RailController.RecordedPaymentSummary> received = getReceived("fedwire");
        assertThat(received).extracting(RailController.RecordedPaymentSummary::uetr).contains(fixture.uetr());
    }

    @Test
    void malformedXmlReturns400() {
        loadScenario("fedwire", ACCEPT_NO_CONFIRMATION_SCENARIO);

        ResponseEntity<String> response = postPayment("fedwire", Fixtures.sample("malformed.xml"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void schemaInvalidPaymentReturns400AndIsNotRecorded() {
        loadScenario("fedwire", ACCEPT_NO_CONFIRMATION_SCENARIO);
        byte[] schemaInvalid = Fixtures.sample("schema-invalid-missing-amount.xml");

        ResponseEntity<String> response = postPayment("fedwire", schemaInvalid);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<RailController.RecordedPaymentSummary> received = getReceived("fedwire");
        assertThat(received).extracting(RailController.RecordedPaymentSummary::uetr)
                .doesNotContain("8a562c67-ca16-48ba-b074-65581be6f002");
    }

    @Test
    void unknownUetrReturnsUnknown() {
        loadScenario("fedwire", ACCEPT_NO_CONFIRMATION_SCENARIO);

        ResponseEntity<RailController.PaymentStatusResponse> response = getPayment("fedwire", "no-such-uetr");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("UNKNOWN");
    }
}
