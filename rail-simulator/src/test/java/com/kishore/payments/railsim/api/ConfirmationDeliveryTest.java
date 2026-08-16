package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.CallbackTestServer;
import com.kishore.payments.railsim.support.Fixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmationDeliveryTest extends AbstractRailSimulatorTest {

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
    void acscConfirmationIsDeliveredWithinConfiguredDelay() {
        loadScenario("sepa", """
                rail: acsc-delivery
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: ACSC
                  confirmationDelayMs: 100
                callbackUrl: "%s"
                rules: []
                """.formatted(callbackServer.url()));

        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("sepa", fixture.bytes());

        List<CallbackTestServer.ReceivedCallback> callbacks = callbackServer.awaitAtLeast(1, Duration.ofSeconds(5));
        assertThat(callbacks).hasSize(1);
        String body = callbacks.get(0).bodyAsString();
        assertThat(body).contains("<TxSts>ACSC</TxSts>");
        assertThat(body).contains(fixture.uetr());
    }

    @Test
    void rjctConfirmationIsDeliveredWithReasonCode() {
        loadScenario("sepa", """
                rail: rjct-delivery
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: RJCT
                  confirmationDelayMs: 100
                  rejectReasonCode: AC04
                callbackUrl: "%s"
                rules: []
                """.formatted(callbackServer.url()));

        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        postPayment("sepa", fixture.bytes());

        List<CallbackTestServer.ReceivedCallback> callbacks = callbackServer.awaitAtLeast(1, Duration.ofSeconds(5));
        String body = callbacks.get(0).bodyAsString();
        assertThat(body).contains("<TxSts>RJCT</TxSts>");
        assertThat(body).contains("<Cd>AC04</Cd>");
        assertThat(body).contains(fixture.uetr());
    }
}
