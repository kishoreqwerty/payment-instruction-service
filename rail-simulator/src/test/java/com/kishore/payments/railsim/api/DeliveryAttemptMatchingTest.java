package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.CallbackTestServer;
import com.kishore.payments.railsim.support.Fixtures;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * {@code deliveryAttemptAtLeast} exists so a scenario can tell an original
 * delivery apart from its redispatch: a redispatch carries the exact same
 * UETR and content as its original by design, so nothing content-based can
 * distinguish them, and {@code everyNth} (a whole-rail request counter)
 * cannot survive many payments interleaving on one rail. This is scoped
 * per UETR instead.
 */
class DeliveryAttemptMatchingTest extends AbstractRailSimulatorTest {

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
    void firstDeliveryDropsSecondDeliveryOfSameUetrAccepts() {
        loadScenario("fedwire", """
                rail: delivery-attempt-test
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                statusCallbackUrl: "%s"
                rules:
                  - match:
                      deliveryAttemptAtLeast: 2
                    acceptResponse: ACCEPT
                    confirmation: ACSC
                    confirmationDelayMs: 0
                """.formatted(callbackServer.url()));

        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        // First delivery: matches no rule (deliveryAttempt == 1), falls
        // through to the DROP default -- never recorded, since
        // recordBeforeTimeout is false. DROP forcibly resets the
        // connection, so the client sees a transport-level failure, not an
        // HTTP response -- the same pattern DispatchAmbiguityTest uses.
        assertThatThrownBy(() -> postPayment("fedwire", fixture.bytes())).isInstanceOf(RuntimeException.class);
        assertThat(getReceivedOne("fedwire", fixture.uetr()).getBody().received()).isFalse();

        // Second delivery of the exact same UETR: matches the rule, is
        // ACCEPTed and recorded, and gets confirmed.
        postPayment("fedwire", fixture.bytes());
        assertThat(getReceivedOne("fedwire", fixture.uetr()).getBody().received()).isTrue();
        assertThat(getReceivedOne("fedwire", fixture.uetr()).getBody().duplicateCount()).isZero();
        callbackServer.awaitAtLeast(1, Duration.ofSeconds(5));
    }

    @Test
    void thirdDeliveryOfAnAlreadyAcceptedUetrIsRecognisedAsADuplicate() {
        loadScenario("fedwire", """
                rail: delivery-attempt-duplicate-test
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);

        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("fedwire", fixture.bytes());
        postPayment("fedwire", fixture.bytes());

        assertThat(getReceivedOne("fedwire", fixture.uetr()).getBody().duplicateCount()).isEqualTo(1);
    }

    @Test
    void dropWithRecordBeforeTimeoutTrueStillDeliversItsConfirmation() {
        // The DROP-never-confirms rule only applies to a payment the rail
        // never accepted at all (recordBeforeTimeout: false). One recorded
        // before the reset genuinely was received -- only the response was
        // lost -- and can still be confirmed later, out of band.
        loadScenario("fedwire", """
                rail: drop-record-before-timeout-confirms-test
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  recordBeforeTimeout: true
                  confirmation: ACSC
                  confirmationDelayMs: 20
                statusCallbackUrl: "%s"
                rules: []
                """.formatted(callbackServer.url()));

        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        assertThatThrownBy(() -> postPayment("fedwire", fixture.bytes())).isInstanceOf(RuntimeException.class);

        assertThat(getReceivedOne("fedwire", fixture.uetr()).getBody().received()).isTrue();
        var callbacks = callbackServer.awaitAtLeast(1, Duration.ofSeconds(5));
        assertThat(callbacks).anyMatch(c -> c.bodyAsString().contains(fixture.uetr()) && c.bodyAsString().contains("<TxSts>ACSC</TxSts>"));

        ResponseEntity<RailController.PaymentStatusResponse> status = getPayment("fedwire", fixture.uetr());
        assertThat(status.getBody().railStatus()).isEqualTo("ACSC");
    }
}
