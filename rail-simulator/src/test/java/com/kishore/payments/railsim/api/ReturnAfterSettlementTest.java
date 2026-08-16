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

/** confirmation: RETURN_AFTER_SETTLEMENT -- an ACSC followed by a pacs.004 return after a further delay. */
class ReturnAfterSettlementTest extends AbstractRailSimulatorTest {

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
    void sendsAcscThenAPacs004ReturnAfterTheConfiguredDelay() {
        loadScenario("fedwire", """
                rail: return-after-settlement
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: RETURN_AFTER_SETTLEMENT
                  confirmationDelayMs: 50
                  returnDelayMs: 100
                  returnReasonCode: AM04
                statusCallbackUrl: "%s"
                rules: []
                """.formatted(callbackServer.url()));
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        postPayment("fedwire", fixture.bytes());

        List<CallbackTestServer.ReceivedCallback> callbacks = callbackServer.awaitAtLeast(2, Duration.ofSeconds(5));
        assertThat(callbacks).hasSize(2);

        String acsc = bodyContaining(callbacks, "<TxSts>ACSC</TxSts>");
        assertThat(acsc).contains(fixture.uetr());

        String pacs004 = bodyContaining(callbacks, "<PmtRtr>");
        assertThat(pacs004).contains(fixture.uetr());
        assertThat(pacs004).contains("<Cd>AM04</Cd>");
    }

    @Test
    void theReturnArrivesAfterTheConfirmationNotBeforeOrInstead() {
        loadScenario("sepa", """
                rail: return-ordering
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: RETURN_AFTER_SETTLEMENT
                  confirmationDelayMs: 50
                  returnDelayMs: 300
                  returnReasonCode: RR04
                statusCallbackUrl: "%s"
                rules: []
                """.formatted(callbackServer.url()));
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        postPayment("sepa", fixture.bytes());

        // Shortly after the ACSC's own delay but well before the return's,
        // only the ACSC should have arrived.
        List<CallbackTestServer.ReceivedCallback> afterConfirmation = callbackServer.awaitAtLeast(1, Duration.ofSeconds(5));
        assertThat(afterConfirmation).hasSize(1);
        assertThat(afterConfirmation.get(0).bodyAsString()).contains("<TxSts>ACSC</TxSts>");

        List<CallbackTestServer.ReceivedCallback> both = callbackServer.awaitAtLeast(2, Duration.ofSeconds(5));
        assertThat(both).hasSize(2);
    }

    @Test
    void statusAndReturnGoToIndependentlyConfiguredUrls() {
        CallbackTestServer statusServer = new CallbackTestServer();
        CallbackTestServer returnServer = new CallbackTestServer();
        try {
            loadScenario("ach_equiv", """
                    rail: split-urls
                    default:
                      acceptResponse: ACCEPT
                      acceptDelayMs: 0
                      confirmation: RETURN_AFTER_SETTLEMENT
                      confirmationDelayMs: 20
                      returnDelayMs: 20
                      returnReasonCode: AM04
                    statusCallbackUrl: "%s"
                    returnCallbackUrl: "%s"
                    rules: []
                    """.formatted(statusServer.url(), returnServer.url()));
            Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

            postPayment("ach_equiv", fixture.bytes());

            List<CallbackTestServer.ReceivedCallback> statusCallbacks = statusServer.awaitAtLeast(1, Duration.ofSeconds(5));
            List<CallbackTestServer.ReceivedCallback> returnCallbacks = returnServer.awaitAtLeast(1, Duration.ofSeconds(5));

            assertThat(statusCallbacks).hasSize(1);
            assertThat(statusCallbacks.get(0).bodyAsString()).contains("<TxSts>ACSC</TxSts>");
            assertThat(returnCallbacks).hasSize(1);
            assertThat(returnCallbacks.get(0).bodyAsString()).contains("<PmtRtr>");
        } finally {
            statusServer.close();
            returnServer.close();
        }
    }

    private static String bodyContaining(List<CallbackTestServer.ReceivedCallback> callbacks, String needle) {
        return callbacks.stream()
                .map(CallbackTestServer.ReceivedCallback::bodyAsString)
                .filter(b -> b.contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No callback body contained: " + needle));
    }
}
