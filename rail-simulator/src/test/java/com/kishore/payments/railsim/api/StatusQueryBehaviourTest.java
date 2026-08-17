package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code statusQueryBehaviour} governs {@code GET /payments/{uetr}}, a
 * second interaction with the rail independent of the accept/confirmation
 * path {@link HttpErrorScenarioTest} and {@link RailStatusReportingTest}
 * cover. These are the failure modes Phase 7's AmbiguityResolver has to be
 * tested against: a rail that hasn't indexed a payment yet, one whose
 * status endpoint is down, and one that's merely slow.
 */
class StatusQueryBehaviourTest extends AbstractRailSimulatorTest {

    @Test
    void alwaysErrorAnswersEveryQueryWithServerError() {
        loadScenario("fedwire", """
                rail: always-error
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                statusQueryBehaviour: ALWAYS_ERROR
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("fedwire", fixture.bytes());

        ResponseEntity<RailController.PaymentStatusResponse> response = getPayment("fedwire", fixture.uetr());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // A second query fails identically -- ALWAYS_ERROR is not a
        // one-shot fault, it models a status endpoint that is simply down.
        ResponseEntity<RailController.PaymentStatusResponse> second = getPayment("fedwire", fixture.uetr());
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void alwaysErrorAppliesEvenToNeverReceivedUetrs() {
        loadScenario("fedwire", """
                rail: always-error-unknown
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                statusQueryBehaviour: ALWAYS_ERROR
                rules: []
                """);

        ResponseEntity<RailController.PaymentStatusResponse> response = getPayment("fedwire", "never-seen-uetr");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void slowDelaysTheAnswerButStillAnswersTruthfully() {
        loadScenario("fedwire", """
                rail: slow-status
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                statusQueryBehaviour: SLOW
                statusQuerySlowDelayMs: 200
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("fedwire", fixture.bytes());

        long start = System.currentTimeMillis();
        ResponseEntity<RailController.PaymentStatusResponse> response = getPayment("fedwire", fixture.uetr());
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(200);
        assertThat(response.getBody().status()).isEqualTo("KNOWN");
    }

    @Test
    void unknownThenKnownAnswersUnknownForConfiguredCountThenTruthfully() {
        loadScenario("fedwire", """
                rail: unknown-then-known
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                statusQueryBehaviour: UNKNOWN_THEN_KNOWN
                statusQueryUnknownCount: 2
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("fedwire", fixture.bytes());

        ResponseEntity<RailController.PaymentStatusResponse> first = getPayment("fedwire", fixture.uetr());
        ResponseEntity<RailController.PaymentStatusResponse> second = getPayment("fedwire", fixture.uetr());
        ResponseEntity<RailController.PaymentStatusResponse> third = getPayment("fedwire", fixture.uetr());

        assertThat(first.getBody().status()).isEqualTo("UNKNOWN");
        assertThat(second.getBody().status()).isEqualTo("UNKNOWN");
        assertThat(third.getBody().status()).isEqualTo("KNOWN");
    }

    @Test
    void unknownThenKnownDefaultsToOneLeadingUnknown() {
        loadScenario("fedwire", """
                rail: unknown-then-known-default
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                statusQueryBehaviour: UNKNOWN_THEN_KNOWN
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("fedwire", fixture.bytes());

        ResponseEntity<RailController.PaymentStatusResponse> first = getPayment("fedwire", fixture.uetr());
        ResponseEntity<RailController.PaymentStatusResponse> second = getPayment("fedwire", fixture.uetr());

        assertThat(first.getBody().status()).isEqualTo("UNKNOWN");
        assertThat(second.getBody().status()).isEqualTo("KNOWN");
    }

    @Test
    void normalIsUnaffectedAndIsTheDefaultWhenUnset() {
        loadScenario("fedwire", """
                rail: normal-status
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

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("KNOWN");
    }
}
