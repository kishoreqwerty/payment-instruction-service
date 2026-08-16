package com.kishore.payments.gateway;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments.gateway")
public record GatewayProperties(Map<String, String> railBaseUrls, Duration connectTimeout, Duration readTimeout, DispatchRetry dispatchRetry) {

    /** Retry policy for a rail 5xx, per .notes/ARCHITECTURE.md §6.2: five attempts, 1s/2s/4s/8s/16s backoff. */
    public record DispatchRetry(int maxAttempts, Duration initialBackoff, double backoffMultiplier) {
    }
}
