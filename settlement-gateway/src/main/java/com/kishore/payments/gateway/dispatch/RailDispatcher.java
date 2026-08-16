package com.kishore.payments.gateway.dispatch;

import com.kishore.payments.gateway.GatewayProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * POSTs an assembled pacs.008 to the configured base URL for a rail and maps
 * the HTTP-level result to a {@link DispatchOutcome}. Knows nothing about
 * dispatch records, instruction state, or retries -- those are the calling
 * consumer's concern (see .notes/reports/PHASE-6-REPORT.md section 5 for why
 * the retry loop lives there and not here).
 */
@Component
public class RailDispatcher {

    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    public RailDispatcher(RestTemplate railRestTemplate, GatewayProperties properties) {
        this.restTemplate = railRestTemplate;
        this.properties = properties;
    }

    public DispatchOutcome dispatch(String rail, byte[] pacs008Xml) {
        String baseUrl = properties.railBaseUrls().get(rail);
        if (baseUrl == null) {
            throw new IllegalStateException("No base URL configured for rail: " + rail);
        }
        String url = baseUrl + "/rail/" + rail + "/payments";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>(pacs008Xml, headers), String.class);
            return new DispatchOutcome.Acknowledged(response.getStatusCode().value());
        } catch (HttpClientErrorException e) {
            return new DispatchOutcome.Rejected(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            return new DispatchOutcome.ServerError(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            // Covers both a read timeout and a genuine connection reset/drop
            // identically -- RestTemplate wraps both as the same exception
            // type, and .notes/ARCHITECTURE.md section 6.4 requires they be
            // handled identically anyway: receipt by the rail is unknown
            // either way.
            return new DispatchOutcome.Ambiguous(e.getMessage());
        }
    }
}
