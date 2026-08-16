package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.CallbackTestServer;
import com.kishore.payments.railsim.support.Fixtures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EveryNthAndScenarioResetTest extends AbstractRailSimulatorTest {

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
    void everyNthFiresOnExactMultiplesOnly() {
        loadScenario("fedwire", """
                rail: everynth-test
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: ACSC
                  confirmationDelayMs: 20
                statusCallbackUrl: "%s"
                rules:
                  - match:
                      everyNth: 3
                    confirmation: RJCT
                    rejectReasonCode: AC04
                """.formatted(callbackServer.url()));

        List<String> uetrs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
            uetrs.add(fixture.uetr());
            postPayment("fedwire", fixture.bytes());
        }

        List<CallbackTestServer.ReceivedCallback> callbacks = callbackServer.awaitAtLeast(6, Duration.ofSeconds(5));
        assertThat(bodyFor(callbacks, uetrs.get(2))).contains("<TxSts>RJCT</TxSts>");
        assertThat(bodyFor(callbacks, uetrs.get(5))).contains("<TxSts>RJCT</TxSts>");
        for (int idx : new int[] {0, 1, 3, 4}) {
            assertThat(bodyFor(callbacks, uetrs.get(idx))).contains("<TxSts>ACSC</TxSts>");
        }
    }

    @Test
    void scenarioReloadResetsOrdinalCounterAndClearsRecordedPayments() {
        String scenario = """
                rail: reset-test
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                statusCallbackUrl: "%s"
                rules:
                  - match:
                      everyNth: 3
                    confirmation: RJCT
                    rejectReasonCode: AC04
                """.formatted(callbackServer.url());

        loadScenario("fedwire", scenario);
        Fixtures.Pacs008Fixture recordedBeforeReload = Fixtures.validPayment();
        postPayment("fedwire", recordedBeforeReload.bytes());
        postPayment("fedwire", Fixtures.validPayment().bytes());
        assertThat(getReceived("fedwire")).extracting(RailController.RecordedPaymentSummary::uetr)
                .contains(recordedBeforeReload.uetr());

        loadScenario("fedwire", scenario);
        assertThat(getReceived("fedwire")).isEmpty();

        postPayment("fedwire", Fixtures.validPayment().bytes());
        postPayment("fedwire", Fixtures.validPayment().bytes());
        Fixtures.Pacs008Fixture thirdAfterReload = Fixtures.validPayment();
        postPayment("fedwire", thirdAfterReload.bytes());

        List<CallbackTestServer.ReceivedCallback> callbacks = callbackServer.awaitAtLeast(1, Duration.ofSeconds(5));
        assertThat(callbacks).anyMatch(c ->
                c.bodyAsString().contains(thirdAfterReload.uetr()) && c.bodyAsString().contains("<TxSts>RJCT</TxSts>"));
    }

    private static String bodyFor(List<CallbackTestServer.ReceivedCallback> callbacks, String uetr) {
        return callbacks.stream()
                .map(CallbackTestServer.ReceivedCallback::bodyAsString)
                .filter(b -> b.contains(uetr))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No callback found containing UETR " + uetr));
    }
}
