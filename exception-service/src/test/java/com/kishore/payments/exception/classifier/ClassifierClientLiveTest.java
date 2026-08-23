package com.kishore.payments.exception.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.Repairability;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Excluded from every normal run (see exception-service/pom.xml's surefire {@code
 * excludedGroups}); makes a real, billed call to the Anthropic API. Run explicitly with:
 * {@code mvn -pl exception-service test -Dtest=ClassifierClientLiveTest -Dgroups=live -DexcludedGroups=}
 *
 * <p>This is the one test in the suite that answers a question no mock can: does the SDK wiring,
 * the prompt, and the defensive parsing actually agree with what the real API returns. Everything
 * else in {@link ClassifierClientTest} proves the failure paths; this proves the success path is
 * real, not just internally consistent.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClassifierClientLiveTest {

    @Test
    void aRealCallToAnUnambiguousIbanFailureReturnsAWellFormedProposal() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        ClassifierProperties properties =
                new ClassifierProperties("claude-sonnet-4-6", Duration.ofSeconds(20), 0, 3, Duration.ofSeconds(60), null);
        ClassifierClient client = new ClassifierClient(properties, Clock.systemUTC(), apiKey);

        assertThat(client.isAvailable()).isTrue();

        ClassifierRequest request = new ClassifierRequest(
                "EXCEPTION", "AC01", "Debtor account is not a valid IBAN: [REDACTED]",
                new ClassifierRequest.FieldShape("debtorAccount", 22, "ALPHA_DIGITS", false, "DE"), "EUR", "SEPA", "1K_TO_10K", 0, 0);

        Optional<ClassifierProposal> result = client.classify(request);

        assertThat(result).isPresent();
        ClassifierProposal proposal = result.get();
        assertThat(proposal.repairability()).isNotNull();
        assertThat(proposal.confidence()).isBetween(0.0, 1.0);
        assertThat(proposal.rationale()).isNotBlank();
        // A checksum-invalid IBAN is a textbook AC01/REPAIRABLE case -- the one place in this
        // evaluation where the "correct" label is not remotely arguable, so a real assertion on
        // the model's actual output (not just its shape) is warranted here.
        assertThat(proposal.repairability()).isEqualTo(Repairability.REPAIRABLE);
    }
}
