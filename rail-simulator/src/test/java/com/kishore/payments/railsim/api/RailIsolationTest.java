package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** A test injecting chaos on one rail must not perturb a test running clean on another (phase 5 brief, section 6). */
class RailIsolationTest extends AbstractRailSimulatorTest {

    @Test
    void chaosOnOneRailDoesNotAffectAnother() {
        loadScenario("fedwire", """
                rail: fedwire-chaos
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                rules: []
                """);
        loadScenario("sepa", """
                rail: sepa-clean
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);

        Fixtures.Pacs008Fixture sepaPayment = Fixtures.validPayment();
        ResponseEntity<String> sepaResponse = postPayment("sepa", sepaPayment.bytes());
        assertThat(sepaResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Fixtures.Pacs008Fixture fedwirePayment = Fixtures.validPayment();
        assertThatThrownBy(() -> postPayment("fedwire", fedwirePayment.bytes())).isInstanceOf(RuntimeException.class);

        ResponseEntity<RailController.PaymentStatusResponse> sepaStatus = getPayment("sepa", sepaPayment.uetr());
        assertThat(sepaStatus.getBody().status()).isEqualTo("KNOWN");

        ResponseEntity<RailController.PaymentStatusResponse> fedwireStatus = getPayment("fedwire", fedwirePayment.uetr());
        assertThat(fedwireStatus.getBody().status()).isEqualTo("UNKNOWN");
    }
}
