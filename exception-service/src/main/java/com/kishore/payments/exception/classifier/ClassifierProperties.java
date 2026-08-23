package com.kishore.payments.exception.classifier;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code apiKey} is read from the {@code ANTHROPIC_API_KEY} environment variable directly (see
 * {@link ClassifierClient}), never through this properties class and never through
 * application.yml -- so it can never end up in a properties file that gets committed. Everything
 * else here is ordinary configuration.
 */
@ConfigurationProperties(prefix = "payments.classifier")
public record ClassifierProperties(
        String model, Duration timeout, int maxRetries, int circuitBreakerFailureThreshold, Duration circuitBreakerCooldown,
        String baseUrl) {

    public ClassifierProperties {
        if (model == null) {
            model = "claude-sonnet-4-6";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(5);
        }
        // maxRetries left at its unconfigured default of 0 deliberately: "no retry storm" (phase
        // brief section 3) means zero additional attempts after a failure, not one or more.
        if (circuitBreakerFailureThreshold == 0) {
            circuitBreakerFailureThreshold = 3;
        }
        if (circuitBreakerCooldown == null) {
            circuitBreakerCooldown = Duration.ofSeconds(60);
        }
        // baseUrl left null means "use the SDK's own default (the real Anthropic API)" -- only
        // tests override it, to point at a local fake endpoint instead.
    }
}
