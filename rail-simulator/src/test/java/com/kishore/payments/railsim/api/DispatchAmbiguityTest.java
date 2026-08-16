package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.Fixtures;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Exercises the dispatch-ambiguity semantics in .notes/ARCHITECTURE.md
 * section 6.4: a TIMEOUT can leave the rail KNOWN (it recorded the payment
 * before going dark) or UNKNOWN (it never got that far), and DROP -- a true
 * connection reset with no response at all -- always leaves it UNKNOWN
 * unless the scenario explicitly says otherwise.
 */
class DispatchAmbiguityTest extends AbstractRailSimulatorTest {

    @Test
    void timeoutWithRecordBeforeTimeoutTrueIsKnownDuringAndAfterHold() throws Exception {
        loadScenario("ach_equiv", """
                rail: timeout-record-before
                default:
                  acceptResponse: TIMEOUT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  timeoutHoldMs: 2000
                  recordBeforeTimeout: true
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        CompletableFuture<ResponseEntity<String>> future =
                CompletableFuture.supplyAsync(() -> postPayment("ach_equiv", fixture.bytes()));

        Thread.sleep(500);
        ResponseEntity<RailController.PaymentStatusResponse> duringHold = getPayment("ach_equiv", fixture.uetr());
        assertThat(duringHold.getBody().status()).isEqualTo("KNOWN");

        ResponseEntity<String> finalResponse = future.get(5, TimeUnit.SECONDS);
        assertThat(finalResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void timeoutWithRecordBeforeTimeoutFalseIsUnknownDuringHoldThenKnownAfter() throws Exception {
        loadScenario("ach_equiv", """
                rail: timeout-record-after
                default:
                  acceptResponse: TIMEOUT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  timeoutHoldMs: 2000
                  recordBeforeTimeout: false
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        CompletableFuture<ResponseEntity<String>> future =
                CompletableFuture.supplyAsync(() -> postPayment("ach_equiv", fixture.bytes()));

        Thread.sleep(500);
        ResponseEntity<RailController.PaymentStatusResponse> duringHold = getPayment("ach_equiv", fixture.uetr());
        assertThat(duringHold.getBody().status()).isEqualTo("UNKNOWN");

        future.get(5, TimeUnit.SECONDS);
        ResponseEntity<RailController.PaymentStatusResponse> afterHold = getPayment("ach_equiv", fixture.uetr());
        assertThat(afterHold.getBody().status()).isEqualTo("KNOWN");
    }

    @Test
    void dropClosesConnectionWithNoResponseAndPaymentStaysUnknown() {
        loadScenario("ach_equiv", """
                rail: drop-test
                default:
                  acceptResponse: DROP
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                  recordBeforeTimeout: false
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        assertThatThrownBy(() -> postPayment("ach_equiv", fixture.bytes())).isInstanceOf(RuntimeException.class);

        ResponseEntity<RailController.PaymentStatusResponse> status = getPayment("ach_equiv", fixture.uetr());
        assertThat(status.getBody().status()).isEqualTo("UNKNOWN");
    }
}
