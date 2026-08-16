package com.kishore.payments.railsim.support;

import com.kishore.payments.railsim.api.RailController;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractRailSimulatorTest {

    @Autowired
    protected TestRestTemplate restTemplate;

    @LocalServerPort
    protected int port;

    protected ResponseEntity<String> postPayment(String rail, byte[] pacs008Xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return restTemplate.postForEntity(
                "/rail/{railId}/payments", new HttpEntity<>(pacs008Xml, headers), String.class, rail);
    }

    protected ResponseEntity<RailController.PaymentStatusResponse> getPayment(String rail, String uetr) {
        return restTemplate.getForEntity(
                "/rail/{railId}/payments/{uetr}", RailController.PaymentStatusResponse.class, rail, uetr);
    }

    @SuppressWarnings("unchecked")
    protected List<RailController.RecordedPaymentSummary> getReceived(String rail) {
        ResponseEntity<RailController.RecordedPaymentSummary[]> response = restTemplate.getForEntity(
                "/rail/{railId}/received", RailController.RecordedPaymentSummary[].class, rail);
        return List.of(response.getBody());
    }

    protected void loadScenario(String rail, String yaml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/rail/{railId}/scenario",
                new HttpEntity<>(yaml.getBytes(StandardCharsets.UTF_8), headers),
                Void.class,
                rail);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Failed to load scenario for rail " + rail + ": " + response.getStatusCode());
        }
    }
}
