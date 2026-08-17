package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * A redispatch carries the same UETR as its original (.notes/ARCHITECTURE.md
 * §6.4) -- if the original turns out to have merely been slow rather than
 * lost, both deliveries arrive at this rail under one UETR. The rail must
 * recognise the second as a duplicate of an already-known payment rather
 * than silently overwriting or double-processing it; {@code GET
 * /received/{uetr}} is how a test proves that recognition happened.
 */
class DuplicateReceiptTest extends AbstractRailSimulatorTest {

    @Test
    void secondDeliveryOfAKnownUetrIsCountedAsADuplicate() {
        loadScenario("fedwire", """
                rail: duplicate-detection
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
        postPayment("fedwire", fixture.bytes());

        ResponseEntity<RailController.ReceivedSummary> response = getReceivedOne("fedwire", fixture.uetr());

        assertThat(response.getBody().received()).isTrue();
        assertThat(response.getBody().duplicateCount()).isEqualTo(2);
    }

    @Test
    void firstDeliveryHasZeroDuplicates() {
        loadScenario("fedwire", """
                rail: first-delivery
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();

        postPayment("fedwire", fixture.bytes());

        ResponseEntity<RailController.ReceivedSummary> response = getReceivedOne("fedwire", fixture.uetr());

        assertThat(response.getBody().received()).isTrue();
        assertThat(response.getBody().duplicateCount()).isEqualTo(0);
    }

    @Test
    void neverReceivedUetrIsNotReceived() {
        loadScenario("fedwire", """
                rail: never-received
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);

        ResponseEntity<RailController.ReceivedSummary> response = getReceivedOne("fedwire", "never-seen-uetr");

        assertThat(response.getBody().received()).isFalse();
        assertThat(response.getBody().duplicateCount()).isEqualTo(0);
    }
}
