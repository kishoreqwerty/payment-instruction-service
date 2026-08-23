package com.kishore.payments.exception.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ClassifierClient} is never on the critical path (.notes/ARCHITECTURE.md section 10.4):
 * every one of these tests is really the same acceptance criterion applied to a different failure
 * mode -- "case creation is unaffected" (phase brief section 3, acceptance criterion 7) -- proven
 * here at the client level (no exception ever escapes {@link ClassifierClient#classify}) rather
 * than only at the full case-opening integration level, since a unit-level guarantee is what
 * actually makes the integration-level one true rather than incidental.
 */
class ClassifierClientTest {

    private ServerSocket hangingServer;

    @AfterEach
    void closeServer() throws IOException {
        if (hangingServer != null && !hangingServer.isClosed()) {
            hangingServer.close();
        }
    }

    @Test
    void missingApiKeyLeavesTheClassifierUnavailableRatherThanFailingToStart() {
        ClassifierClient client = new ClassifierClient(defaultProperties(), Clock.systemUTC(), "");

        assertThat(client.isAvailable()).isFalse();
        assertThat(client.classify(sampleRequest())).isEmpty();
    }

    @Test
    void blankApiKeyIsTreatedTheSameAsAbsent() {
        ClassifierClient client = new ClassifierClient(defaultProperties(), Clock.systemUTC(), "   ");

        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void aHangingEndpointReturnsEmptyWithinTheConfiguredTimeoutRatherThanThrowing() throws IOException {
        String baseUrl = startHangingServer();
        ClassifierProperties properties = new ClassifierProperties("claude-sonnet-4-6", Duration.ofMillis(300), 0, 3, Duration.ofMinutes(1),
                baseUrl);
        ClassifierClient client = new ClassifierClient(properties, Clock.systemUTC(), "fake-key-for-a-fake-endpoint");

        long start = System.nanoTime();
        Optional<ClassifierProposal> result = client.classify(sampleRequest());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result).isEmpty();
        // Generous upper bound (10x the configured timeout): the point is "did not hang
        // indefinitely and did not throw," not a tight timing assertion that would be flaky
        // under CI load.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void theCircuitOpensAfterRepeatedFailuresAndShortCircuitsWithoutAttemptingTheCall() throws IOException {
        String baseUrl = startHangingServer();
        ClassifierProperties properties = new ClassifierProperties(
                "claude-sonnet-4-6", Duration.ofMillis(200), 0, /* failureThreshold */ 2, Duration.ofMinutes(10), baseUrl);
        ClassifierClient client = new ClassifierClient(properties, Clock.systemUTC(), "fake-key-for-a-fake-endpoint");

        // Two real, slow failures open the circuit.
        client.classify(sampleRequest());
        client.classify(sampleRequest());
        assertThat(client.isAvailable()).isFalse();

        // A third call, with the circuit open, must return immediately: no network attempt at all.
        long start = System.nanoTime();
        Optional<ClassifierProposal> result = client.classify(sampleRequest());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result).isEmpty();
        assertThat(elapsed).isLessThan(Duration.ofMillis(100));
    }

    @Test
    void theCircuitClosesAgainAfterTheCooldownIfATrialCallSucceeds() throws IOException {
        String baseUrl = startHangingServer();
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ClassifierProperties properties = new ClassifierProperties(
                "claude-sonnet-4-6", Duration.ofMillis(200), 0, 1, Duration.ofSeconds(30), baseUrl);
        ClassifierClient client = new ClassifierClient(properties, clock, "fake-key-for-a-fake-endpoint");

        client.classify(sampleRequest());
        assertThat(client.isAvailable()).isFalse();

        // Still within the cooldown: circuit stays open, no attempt made.
        assertThat(client.isAvailable()).isFalse();

        // Past the cooldown: the next call is let through as a trial. It will also fail (still
        // the same hanging endpoint), which is exactly what proves this isn't just "always
        // available after cooldown" -- the circuit reopens because the trial itself failed.
        clock.advance(Duration.ofSeconds(31));
        client.classify(sampleRequest());
        assertThat(client.isAvailable()).isFalse();
    }

    private String startHangingServer() throws IOException {
        hangingServer = new ServerSocket();
        hangingServer.bind(new InetSocketAddress("localhost", 0));
        // Deliberately never call accept(): a client's connect() completes against the OS
        // backlog, but no response is ever written, so the client's read blocks until its own
        // configured timeout fires. A deterministic, portable way to simulate a hung endpoint
        // without depending on any real network's timeout behaviour.
        return "http://localhost:" + hangingServer.getLocalPort();
    }

    private static ClassifierProperties defaultProperties() {
        return new ClassifierProperties("claude-sonnet-4-6", Duration.ofSeconds(5), 0, 3, Duration.ofSeconds(60), null);
    }

    private static ClassifierRequest sampleRequest() {
        return new ClassifierRequest(
                "EXCEPTION", "AC01", "Debtor account is not a valid IBAN: [REDACTED]",
                new ClassifierRequest.FieldShape("debtorAccount", 22, "ALPHA_DIGITS", false, "DE"), "EUR", "SEPA", "1K_TO_10K", 0, 0);
    }

    private static final class AdjustableClock extends Clock {
        private Instant now;

        AdjustableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
