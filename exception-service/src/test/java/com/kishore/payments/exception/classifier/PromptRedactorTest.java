package com.kishore.payments.exception.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * PromptRedactor's own acceptance criterion (.notes/reports/PHASE-11-REPORT.md section 2, phase
 * brief acceptance criterion 1): every generated-value test here is checking that a *specific
 * random value this run generated* cannot be found anywhere in the redacted, serialised payload
 * -- not that the redactor's logic looks right by inspection.
 *
 * <p>Originally written against jqwik's {@code @Property}; replaced with JUnit 5 {@code
 * @ParameterizedTest}s driven by a seeded {@link Random} (see
 * .notes/reports/SUPPLY-CHAIN-JQWIK.md for why: jqwik 1.10.0/1.10.1 print an AI-agent-directed
 * instruction embedded in test output, confirmed by the maintainer as deliberate and hostile to
 * this project's entirely-agent-driven workflow -- not a risk worth carrying regardless of how
 * benign the current string reads). The property under test is unchanged: generate instructions
 * with random account numbers and party names, redact, assert none of the generated values
 * appear in the serialised payload. {@link #ITERATIONS} matches jqwik's own default
 * tries-per-property (1000). {@link #randomInstructions()} is called fresh per test method (a
 * new seed each time, not shared between the two tests below, matching jqwik's own
 * independent-per-property randomness); the seed rides along as each invocation's first
 * parameter and is folded into AssertJ's {@code .as(...)} description, which surfaces only in a
 * failure message -- exactly enough to reproduce a failing case later via a hardcoded {@code new
 * Random(seed)}, without printing anything on the successful path.
 */
class PromptRedactorTest {

    private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final PromptRedactor REDACTOR = new PromptRedactor(CLOCK);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int ITERATIONS = 1000;
    private static final int ACCOUNT_MIN_LENGTH = 10;
    private static final int ACCOUNT_MAX_LENGTH = 20;
    private static final int NAME_MIN_LENGTH = 10;
    private static final int NAME_MAX_LENGTH = 25;
    private static final int BIC_LENGTH = 11;

    @ParameterizedTest(name = "[{index}] seed={0}")
    @MethodSource("randomInstructions")
    void redactedPayloadNeverContainsTheGeneratedAccountNumbersOrNames(long seed, PaymentInstructionEntity instruction) throws Exception {
        InstructionExceptionEvent.Detail detail = new InstructionExceptionEvent.Detail(
                "AC01", Repairability.REPAIRABLE, "debtorAccount", "Debtor account is not a valid IBAN: " + instruction.getDebtorAccount());

        ClassifierRequest request = REDACTOR.redact(FailureStage.VALIDATION, instruction, detail, 0);
        String serialised = JSON.writeValueAsString(request);

        assertThat(serialised)
                .as("seed=%dL -- reproduce with `new Random(%dL)` and replay randomInstructions() from the start", seed, seed)
                .doesNotContain(instruction.getDebtorAccount())
                .doesNotContain(instruction.getCreditorAccount())
                .doesNotContain(instruction.getDebtorName())
                .doesNotContain(instruction.getCreditorName())
                .doesNotContain(instruction.getDebtorAgentBic())
                .doesNotContain(instruction.getCreditorAgentBic())
                .doesNotContain(instruction.getEndToEndId())
                .doesNotContain(instruction.getUetr().toString())
                .doesNotContain(instruction.getInstructionId().toString());
    }

    @ParameterizedTest(name = "[{index}] seed={0}")
    @MethodSource("randomInstructions")
    void redactedPayloadNeverContainsSensitiveValuesEvenWhenTheMessageMentionsTheCreditorSide(long seed, PaymentInstructionEntity instruction)
            throws Exception {
        InstructionExceptionEvent.Detail detail = new InstructionExceptionEvent.Detail(
                "RC01", Repairability.REPAIRABLE, "creditorAgentBic",
                "Creditor agent BIC is not a valid format: " + instruction.getCreditorAgentBic());

        ClassifierRequest request = REDACTOR.redact(FailureStage.VALIDATION, instruction, detail, 0);
        String serialised = JSON.writeValueAsString(request);

        assertThat(serialised)
                .as("seed=%dL -- reproduce with `new Random(%dL)` and replay randomInstructions() from the start", seed, seed)
                .doesNotContain(instruction.getCreditorAgentBic())
                .doesNotContain(instruction.getDebtorAccount())
                .doesNotContain(instruction.getCreditorAccount());
    }

    @Test
    void payloadKeySetIsExactlyTheAllowlistNoMoreNoLess() throws Exception {
        PaymentInstructionEntity instruction = sampleInstruction("DE89370400440532013000", "FR1420041010050500013M02606",
                "Acme Gmbh", "Beneficiary SARL", "DEUTDEFFXXX", "BNPAFRPPXXX");
        InstructionExceptionEvent.Detail detail = new InstructionExceptionEvent.Detail(
                "AC01", Repairability.REPAIRABLE, "debtorAccount", "Debtor account is not a valid IBAN: DE89370400440532013000");

        ClassifierRequest request = REDACTOR.redact(FailureStage.VALIDATION, instruction, detail, 2);
        JsonNode node = JSON.valueToTree(request);

        Set<String> expectedTopLevelKeys = Set.of(
                "failureStage", "reasonCode", "errorMessage", "fieldShape", "currency", "rail", "amountBand", "instructionAgeDays",
                "repairAttemptCount");
        assertThat(fieldNames(node)).containsExactlyInAnyOrderElementsOf(expectedTopLevelKeys);

        Set<String> expectedFieldShapeKeys = Set.of("fieldPath", "length", "characterClasses", "ibanChecksumValid", "countryPrefix");
        assertThat(fieldNames(node.get("fieldShape"))).containsExactlyInAnyOrderElementsOf(expectedFieldShapeKeys);
    }

    @Test
    void everyFieldMarkedSensitiveInTheDomainModelIsAbsentFromTheOutboundJson() throws Exception {
        PaymentInstructionEntity instruction = sampleInstruction("DE89370400440532013000", "FR1420041010050500013M02606",
                "Acme Gmbh", "Beneficiary SARL", "DEUTDEFFXXX", "BNPAFRPPXXX");
        InstructionExceptionEvent.Detail detail = new InstructionExceptionEvent.Detail(
                "AC01", Repairability.REPAIRABLE, "debtorAccount", "Debtor account is not a valid IBAN: DE89370400440532013000");

        ClassifierRequest request = REDACTOR.redact(FailureStage.VALIDATION, instruction, detail, 0);
        String serialised = JSON.writeValueAsString(request);

        // Sensitive fields per .notes/ARCHITECTURE.md section 8: account numbers, party names,
        // BICs, instruction_id, uetr, end_to_end_id -- none of them, in any form.
        assertThat(serialised)
                .doesNotContain("DE89370400440532013000")
                .doesNotContain("FR1420041010050500013M02606")
                .doesNotContain("Acme Gmbh")
                .doesNotContain("Beneficiary SARL")
                .doesNotContain("DEUTDEFFXXX")
                .doesNotContain("BNPAFRPPXXX")
                .doesNotContain(instruction.getEndToEndId())
                .doesNotContain(instruction.getUetr().toString())
                .doesNotContain(instruction.getInstructionId().toString());
    }

    @Test
    void amountItselfNeverAppearsOnlyItsBand() throws Exception {
        PaymentInstructionEntity instruction = sampleInstructionWithAmount(new BigDecimal("543210.77"));
        InstructionExceptionEvent.Detail detail =
                new InstructionExceptionEvent.Detail(null, Repairability.REPAIRABLE, null, "no eligible rail for this corridor");

        ClassifierRequest request = REDACTOR.redact(FailureStage.VALIDATION, instruction, detail, 0);
        String serialised = JSON.writeValueAsString(request);

        assertThat(serialised).doesNotContain("543210.77").doesNotContain("543210");
        assertThat(request.amountBand()).isEqualTo("100K_TO_1M");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    // Fixed vocabulary that legitimately appears elsewhere in the serialised payload
    // (instruction state, character-class labels, amount bands, the redaction marker itself).
    // Generated names/BICs are filtered to exclude any value colliding with these -- otherwise a
    // short random string can coincidentally be a genuine substring of, say, "RECEIVED" and the
    // test would fail on a false positive that has nothing to do with the redactor. Minimum
    // lengths are also well above every one of these fixed words' own length, which is the main
    // defense; the filter is the second, independent layer, same reasoning as the redactor itself.
    private static final Set<String> SAFE_VOCAB = Set.of(
            "RECEIVED", "REDACTED", "DIGITS_ONLY", "ALPHA_ONLY", "ALPHA_DIGITS", "MIXED", "EMPTY");

    private static boolean doesNotCollideWithSafeVocab(String generated) {
        return SAFE_VOCAB.stream().noneMatch(safe -> generated.contains(safe) || safe.contains(generated));
    }

    /**
     * A fresh, freshly-randomised seed each time this is called (once per {@code @MethodSource}
     * annotation site, i.e. once per test method run) drives {@link #ITERATIONS} generations --
     * matching jqwik's own default tries-per-property so this replacement gives up none of the
     * original coverage. The stream is sequential (no {@code .parallel()}), so {@code random} is
     * drawn from in a fixed, reproducible order.
     */
    private static Stream<Arguments> randomInstructions() {
        long seed = new Random().nextLong();
        Random random = new Random(seed);
        return IntStream.range(0, ITERATIONS).mapToObj(i -> Arguments.of(seed, randomInstruction(random)));
    }

    private static PaymentInstructionEntity randomInstruction(Random random) {
        String debtorAccount = randomDigits(random, ACCOUNT_MIN_LENGTH, ACCOUNT_MAX_LENGTH);
        String creditorAccount = randomDigits(random, ACCOUNT_MIN_LENGTH, ACCOUNT_MAX_LENGTH);
        String debtorName = randomUppercaseFiltered(random, NAME_MIN_LENGTH, NAME_MAX_LENGTH);
        String creditorName = randomUppercaseFiltered(random, NAME_MIN_LENGTH, NAME_MAX_LENGTH);
        String debtorBic = randomUppercaseFiltered(random, BIC_LENGTH, BIC_LENGTH);
        String creditorBic = randomUppercaseFiltered(random, BIC_LENGTH, BIC_LENGTH);
        return sampleInstruction(debtorAccount, creditorAccount, debtorName, creditorName, debtorBic, creditorBic);
    }

    private static String randomDigits(Random random, int minLength, int maxLength) {
        int length = minLength + random.nextInt(maxLength - minLength + 1);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }

    private static String randomUppercase(Random random, int minLength, int maxLength) {
        int length = minLength == maxLength ? minLength : minLength + random.nextInt(maxLength - minLength + 1);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('A' + random.nextInt(26)));
        }
        return sb.toString();
    }

    private static String randomUppercaseFiltered(Random random, int minLength, int maxLength) {
        String candidate;
        do {
            candidate = randomUppercase(random, minLength, maxLength);
        } while (!doesNotCollideWithSafeVocab(candidate));
        return candidate;
    }

    private static PaymentInstructionEntity sampleInstruction(
            String debtorAccount, String creditorAccount, String debtorName, String creditorName, String debtorBic, String creditorBic) {
        PaymentInstructionEntity instruction = new PaymentInstructionEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "E2E-" + UUID.randomUUID(), null, debtorName, debtorAccount,
                debtorBic, creditorName, creditorAccount, creditorBic, new BigDecimal("1000.00"), "EUR", "SLEV",
                LocalDate.now(CLOCK));
        instruction.setCorrespondentBic(debtorBic);
        instruction.setNostroAccount(creditorAccount);
        instruction.setSelectedRail("SEPA");
        return instruction;
    }

    private static PaymentInstructionEntity sampleInstructionWithAmount(BigDecimal amount) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "E2E-" + UUID.randomUUID(), null, "Acme Gmbh",
                "DE89370400440532013000", "DEUTDEFFXXX", "Beneficiary SARL", "FR1420041010050500013M02606", "BNPAFRPPXXX", amount, "EUR",
                "SLEV", LocalDate.now(CLOCK));
    }
}
