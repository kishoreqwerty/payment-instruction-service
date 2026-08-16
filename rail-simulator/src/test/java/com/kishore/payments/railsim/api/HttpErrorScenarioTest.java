package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * REJECT_4XX and ERROR_5XX: the two synchronous HTTP outcomes nothing in
 * this simulator could previously produce for a well-formed pacs.008 (see
 * .notes/reports/PHASE-6-REPORT.md section 6) -- added specifically so a
 * dispatching client's 4xx/5xx handling can be proven against a real rail
 * rather than a test-local fake.
 */
class HttpErrorScenarioTest extends AbstractRailSimulatorTest {

    @Test
    void reject4xxReturnsBadRequestWithErrorBodyAndIsNotRecorded() {
        loadScenario("fedwire", """
                rail: reject-4xx
                default:
                  acceptResponse: REJECT_4XX
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  rejectReasonCode: AC06
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        ResponseEntity<String> response = postPayment("fedwire", fixture.bytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("REJECTED").contains("AC06");

        ResponseEntity<RailController.PaymentStatusResponse> status = getPayment("fedwire", fixture.uetr());
        assertThat(status.getBody().status()).isEqualTo("UNKNOWN");
    }

    @Test
    void error5xxWithNoErrorCountAlwaysFails() {
        loadScenario("sepa", """
                rail: always-500
                default:
                  acceptResponse: ERROR_5XX
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);

        for (int i = 0; i < 3; i++) {
            Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
            ResponseEntity<String> response = postPayment("sepa", fixture.bytes());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Test
    void error5xxWithErrorCountRecoversOnTheConfiguredAttemptAndIsThenRecorded() {
        loadScenario("ach_equiv", """
                rail: recovers-after-two
                default:
                  acceptResponse: ERROR_5XX
                  acceptDelayMs: 0
                  confirmation: ACSC
                  confirmationDelayMs: 20
                  errorCount: 2
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        // Every retry the gateway would make reuses the same UETR/payload --
        // the simulator has no other way to know these three POSTs are the
        // "same" dispatch being retried, exactly like a real rail wouldn't
        // either.
        ResponseEntity<String> first = postPayment("ach_equiv", fixture.bytes());
        ResponseEntity<String> second = postPayment("ach_equiv", fixture.bytes());
        ResponseEntity<String> third = postPayment("ach_equiv", fixture.bytes());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<RailController.PaymentStatusResponse> status = getPayment("ach_equiv", fixture.uetr());
        assertThat(status.getBody().status()).isEqualTo("KNOWN");
    }
}
