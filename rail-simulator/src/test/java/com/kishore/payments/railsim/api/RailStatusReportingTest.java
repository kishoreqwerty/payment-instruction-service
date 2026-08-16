package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.CallbackTestServer;
import com.kishore.payments.railsim.support.Fixtures;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * GET /rail/{railId}/payments/{uetr} is what Phase 7's AmbiguityResolver
 * calls, and railStatus is part of its contract -- these five cases are
 * exactly the rows the phase 5 follow-up spelled out: recorded-but-no-
 * confirmation-yet is the case ambiguity resolution most needs to tell
 * apart from both settled and unknown.
 */
class RailStatusReportingTest extends AbstractRailSimulatorTest {

    private CallbackTestServer callbackServer;

    @BeforeEach
    void startCallbackServer() {
        callbackServer = new CallbackTestServer();
    }

    @AfterEach
    void stopCallbackServer() {
        callbackServer.close();
    }

    @Test
    void recordedWithNoConfirmationSentYetIsKnownWithNullRailStatus() {
        loadScenario("fedwire", """
                rail: recorded-no-confirmation
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("fedwire", fixture.bytes());

        ResponseEntity<RailController.PaymentStatusResponse> response = getPayment("fedwire", fixture.uetr());

        assertThat(response.getBody().status()).isEqualTo("KNOWN");
        assertThat(response.getBody().railStatus()).isNull();
    }

    @Test
    void acspConfirmationSentIsReflectedInRailStatus() {
        assertRailStatusAfterConfirmation("ACSP", null);
    }

    @Test
    void ascConfirmationSentIsReflectedInRailStatus() {
        assertRailStatusAfterConfirmation("ACSC", null);
    }

    @Test
    void rjctConfirmationSentIsReflectedInRailStatus() {
        assertRailStatusAfterConfirmation("RJCT", "AC04");
    }

    @Test
    void neverRecordedIsUnknownWithNullRailStatus() {
        loadScenario("fedwire", """
                rail: unused
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);

        ResponseEntity<RailController.PaymentStatusResponse> response = getPayment("fedwire", "never-seen-uetr");

        assertThat(response.getBody().status()).isEqualTo("UNKNOWN");
        assertThat(response.getBody().railStatus()).isNull();
    }

    private void assertRailStatusAfterConfirmation(String confirmation, String rejectReasonCode) {
        loadScenario("fedwire", """
                rail: confirmation-status
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: %s
                  confirmationDelayMs: 20
                  rejectReasonCode: %s
                statusCallbackUrl: "%s"
                rules: []
                """.formatted(confirmation, rejectReasonCode, callbackServer.url()));
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("fedwire", fixture.bytes());

        callbackServer.awaitAtLeast(1, Duration.ofSeconds(5));

        ResponseEntity<RailController.PaymentStatusResponse> response = getPayment("fedwire", fixture.uetr());
        assertThat(response.getBody().status()).isEqualTo("KNOWN");
        assertThat(response.getBody().railStatus()).isEqualTo(confirmation);
    }
}
