package com.kishore.payments.railsim.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.railsim.support.AbstractRailSimulatorTest;
import com.kishore.payments.railsim.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** GET /rail/{railId}/received/{uetr}/raw -- the exact bytes a client sent, for byte-for-byte fidelity checks against this simulator. */
class ReceivedRawPayloadTest extends AbstractRailSimulatorTest {

    @Test
    void rawEndpointReturnsExactlyTheBytesSent() {
        loadScenario("fedwire", """
                rail: raw-payload
                default:
                  acceptResponse: ACCEPT
                  acceptDelayMs: 0
                  confirmation: NONE
                  confirmationDelayMs: 0
                rules: []
                """);
        Fixtures.Pacs008Fixture fixture = Fixtures.validPayment();
        byte[] sent = fixture.bytes();

        postPayment("fedwire", sent);

        ResponseEntity<byte[]> raw = restTemplate.getForEntity(
                "/rail/{railId}/received/{uetr}/raw", byte[].class, "fedwire", fixture.uetr());

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).isEqualTo(sent);
    }

    @Test
    void rawEndpointReturns404ForAnUnknownUetr() {
        ResponseEntity<byte[]> raw = restTemplate.getForEntity(
                "/rail/{railId}/received/{uetr}/raw", byte[].class, "fedwire", "no-such-uetr");

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
