package com.kishore.payments.gateway;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments.gateway")
public record GatewayProperties(
        Map<String, String> railBaseUrls,
        Duration connectTimeout,
        Duration readTimeout,
        DispatchRetry dispatchRetry,
        Reconciliation reconciliation) {

    /** Retry policy for a rail 5xx, per .notes/ARCHITECTURE.md §6.2: five attempts, 1s/2s/4s/8s/16s backoff. */
    public record DispatchRetry(int maxAttempts, Duration initialBackoff, double backoffMultiplier) {
    }

    /**
     * Phase 7 ambiguity resolution (.notes/ARCHITECTURE.md §6.4). {@code
     * consecutiveUnknownThreshold} is the number of consecutive UNKNOWN
     * observations required before redispatch -- the phase brief fixes this
     * at two, but it is exposed as config rather than hardcoded so that
     * number is a documented decision, not a magic constant (see
     * .notes/reports/PHASE-7-REPORT.md §5 for the reasoning behind two).
     */
    public record Reconciliation(
            Duration interval,
            Duration gracePeriod,
            int batchSize,
            int consecutiveUnknownThreshold,
            int inconclusiveWindow,
            int maxRedispatchAttempts,
            Duration pendingThreshold) {

        /**
         * The phase brief left this threshold's value unspecified ("a
         * dispatch_record has been PENDING past a threshold", no number).
         * Defaulted here, in code, rather than left to exist only as a line
         * in application.yml, so the default is a documented decision
         * visible at the type that owns it, not an implicit fact about
         * whatever happens to be in a config file. 5 minutes: comfortably
         * longer than any real HTTP call (including retries) could
         * plausibly take, short enough that a genuinely crashed process
         * doesn't sit silently unaccounted-for for the better part of an
         * hour. Not derived from measured production timing -- see
         * .notes/reports/PHASE-7-REPORT.md §6 for why that reasoning is a
         * placeholder, not a settled number.
         */
        private static final Duration DEFAULT_PENDING_THRESHOLD = Duration.ofMinutes(5);

        public Reconciliation {
            if (pendingThreshold == null) {
                pendingThreshold = DEFAULT_PENDING_THRESHOLD;
            }
        }
    }
}
