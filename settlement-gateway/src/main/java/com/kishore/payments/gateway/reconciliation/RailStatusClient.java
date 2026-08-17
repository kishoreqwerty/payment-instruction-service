package com.kishore.payments.gateway.reconciliation;

import com.kishore.payments.gateway.GatewayProperties;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * GETs a rail's opinion of one UETR and maps the HTTP-level result to a
 * {@link RailStatusOutcome}. A second, independent interaction with the
 * rail from {@link com.kishore.payments.gateway.dispatch.RailDispatcher}'s
 * dispatch POST -- this is the query {@link AmbiguityResolver} uses to
 * resolve a dispatch that timed out, not part of the dispatch path itself.
 * Defines its own response shape rather than sharing rail-simulator's
 * {@code RailController.PaymentStatusResponse} Java type: these are two
 * separate deployable services that only agree on a JSON contract over
 * HTTP, the same reason settlement-gateway's own event records are
 * independently defined rather than imported from another service.
 */
@Component
public class RailStatusClient {

    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    public RailStatusClient(RestTemplate railRestTemplate, GatewayProperties properties) {
        this.restTemplate = railRestTemplate;
        this.properties = properties;
    }

    public RailStatusOutcome query(String rail, UUID uetr) {
        String baseUrl = properties.railBaseUrls().get(rail);
        if (baseUrl == null) {
            return new RailStatusOutcome.QueryFailed("No base URL configured for rail: " + rail);
        }
        String url = baseUrl + "/rail/" + rail + "/payments/" + uetr;

        try {
            ResponseEntity<PaymentStatusResponse> response = restTemplate.getForEntity(url, PaymentStatusResponse.class);
            PaymentStatusResponse body = response.getBody();
            if (body == null) {
                return new RailStatusOutcome.QueryFailed("Empty response body from rail " + rail + " for status query");
            }
            return switch (body.status()) {
                case "KNOWN" -> new RailStatusOutcome.Known(body.railStatus(), body.railReasonCode());
                case "UNKNOWN" -> new RailStatusOutcome.Unknown();
                default -> new RailStatusOutcome.QueryFailed("Unrecognised status '" + body.status() + "' from rail " + rail);
            };
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            return new RailStatusOutcome.QueryFailed("Rail " + rail + " returned HTTP " + e.getStatusCode().value() + " on status query");
        } catch (ResourceAccessException e) {
            // Same reasoning as RailDispatcher.dispatch: a read timeout and a
            // dropped/reset connection are indistinguishable here, and both
            // mean the query itself failed -- not that the rail said UNKNOWN.
            return new RailStatusOutcome.QueryFailed("Timeout or connection error querying rail " + rail + ": " + e.getMessage());
        }
    }

    private record PaymentStatusResponse(String uetr, String status, String railStatus, String railReasonCode, String receivedAt) {
    }
}
