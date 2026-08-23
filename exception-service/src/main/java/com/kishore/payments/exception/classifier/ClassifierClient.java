package com.kishore.payments.exception.classifier;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kishore.payments.core.domain.Repairability;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Calls the classifier model and parses its response. Never on the critical path
 * (.notes/ARCHITECTURE.md section 10.4): every failure mode here -- no API key, a network error,
 * a timeout, a malformed response -- produces an empty {@link Optional}, never an exception a
 * caller has to handle specially. {@link com.kishore.payments.exception.cases.ExceptionCaseOpener}
 * opens the case regardless of what this class does.
 *
 * <p>A missing {@code ANTHROPIC_API_KEY} is a configuration state, not a startup failure: {@link
 * #client} is simply {@code null} in that case, {@link #isAvailable()} reports it, and every
 * {@link #classify} call short-circuits to empty without attempting anything.
 *
 * <p>The circuit breaker is a plain consecutive-failure counter, not a library: after {@link
 * ClassifierProperties#circuitBreakerFailureThreshold()} failures in a row, the circuit opens and
 * every call short-circuits (no network attempt at all) until {@link
 * ClassifierProperties#circuitBreakerCooldown()} has passed, at which point the next call is
 * allowed through as a trial -- success closes the circuit again, failure reopens it and resets
 * the cooldown clock. This is deliberately simpler than a half-open-with-trial-limit design
 * (Resilience4j's, for instance): one trial call at a time, on a component that is already never
 * on the critical path, is enough to avoid a retry storm without adding a dependency for it.
 */
@Component
public class ClassifierClient {

    private static final Logger log = LoggerFactory.getLogger(ClassifierClient.class);

    // Anthropic's own guidance: instruct the model to respond with only JSON, no prose, no
    // markdown fencing -- parsed defensively regardless (see parseProposal), since "instructed
    // to" is not "guaranteed to." The model receives nothing but PromptRedactor's already-
    // redacted payload; it structurally cannot leak a value it was never shown.
    //
    // The four "repairability" values below are given explicit definitions, not just named --
    // an earlier version of this prompt (evaluation/prompts/system_prompt_v1_original.txt) only
    // listed the four names, and evaluation against the phase 11 training set found that both
    // models tested defaulted to REPAIRABLE on most rail rejections regardless of the actual
    // ISO 20022 code, including textbook UNREPAIRABLE cases (AC04 account closed, MD07 deceased)
    // -- Cohen's kappa on repairability was *negative* (worse than chance) with the undefined
    // version. Adding these definitions raised it well above the 0.80 gate on held-out (see
    // .notes/reports/PHASE-11-REPORT.md section 5). Keep this in sync with
    // evaluation/prompts/system_prompt.txt -- they must stay identical, or the evaluation
    // harness stops measuring what is actually deployed.
    private static final String SYSTEM_PROMPT = """
            You assist a payments operations team by proposing a classification for a payment \
            processing exception. You are given a redacted failure record: a failure stage, an ISO \
            20022 reason code (if already known), a sanitised error message, the structural shape of \
            the field involved (if any), the currency, the rail, an amount band, the instruction's age \
            in days, and how many repair attempts have already been made.

            "repairability" describes what kind of action, if any, would make this payment succeed -- \
            choose exactly one:
            - "REPAIRABLE": the fix is a correction to a field on this specific instruction (a typo, a \
            malformed value, a wrong checksum) that an operator can edit and resubmit. The instruction \
            itself is wrong; correcting it is enough.
            - "STATIC_DATA": the instruction's own fields are correct, but the failure is caused by \
            missing or incomplete reference data elsewhere (no correspondent-banking relationship on \
            file, no nostro account for that currency, no rail configured for that corridor). No edit \
            to this instruction's own fields can fix it -- someone has to update reference data outside \
            this payment first.
            - "TRANSIENT": nothing needs to change on the instruction or in reference data. Retrying \
            later, unmodified, may succeed once external state changes (funds become available, a hold \
            lifts).
            - "UNREPAIRABLE": the payment cannot succeed as submitted, and no edit, reference-data fix, \
            or retry will make it succeed. It must be returned to the originator (the destination \
            account is closed, the customer is deceased, or similar).

            Respond with ONLY a single JSON object, no other text, no markdown code fence, with \
            exactly these keys:
            - "reasonCode": an ISO 20022 external code string, or null if you cannot determine one
            - "repairability": one of "REPAIRABLE", "STATIC_DATA", "TRANSIENT", "UNREPAIRABLE"
            - "suggestedField": the instruction field you believe should change, or null
            - "suggestedValue": your suggested new value for that field, or null if you have no \
            specific suggestion (you were not given the actual field value, so do not guess one that \
            looks real -- a null suggestion is honest, a fabricated-looking one is not)
            - "confidence": a number between 0 and 1
            - "rationale": one or two sentences a human operator can use to evaluate your proposal, \
            not just read it
            """;

    private final AnthropicClient client;
    private final ClassifierProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> circuitOpenedAt = new AtomicReference<>();

    public ClassifierClient(
            ClassifierProperties properties, Clock clock, @Value("${ANTHROPIC_API_KEY:}") String apiKey) {
        this.properties = properties;
        this.clock = clock;
        this.client = buildClient(apiKey, properties);
    }

    @Nullable
    private static AnthropicClient buildClient(String apiKey, ClassifierProperties properties) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("ANTHROPIC_API_KEY not set: exception classifier is unavailable, case creation is unaffected");
            return null;
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries());
        if (properties.baseUrl() != null) {
            builder.baseUrl(properties.baseUrl());
        }
        return builder.build();
    }

    public boolean isAvailable() {
        return client != null && !circuitOpen();
    }

    /** Never throws. Every failure mode -- unavailable, circuit open, network error, malformed response -- returns empty. */
    public Optional<ClassifierProposal> classify(ClassifierRequest request) {
        if (client == null) {
            return Optional.empty();
        }
        if (circuitOpen()) {
            return Optional.empty();
        }
        try {
            String payload = json.writeValueAsString(request);
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(properties.model())
                    .maxTokens(500)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(payload)
                    .build();
            Message response = client.messages().create(params);
            ClassifierProposal proposal = parseProposal(extractText(response));
            recordSuccess();
            return Optional.ofNullable(proposal);
        } catch (Exception e) {
            log.warn("Classifier call failed, proceeding without a proposal: {}", e.toString());
            recordFailure();
            return Optional.empty();
        }
    }

    private static String extractText(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(com.anthropic.models.messages.TextBlock::text)
                .findFirst()
                .orElse(null);
    }

    /** A malformed response is a null proposal, never an exception that reaches the caller. */
    @Nullable
    private ClassifierProposal parseProposal(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(stripMarkdownFence(text));
            String reasonCode = textOrNull(node, "reasonCode");
            Repairability repairability = node.hasNonNull("repairability") ? Repairability.valueOf(node.get("repairability").asText())
                    : null;
            String suggestedField = textOrNull(node, "suggestedField");
            String suggestedValue = textOrNull(node, "suggestedValue");
            double confidence = node.path("confidence").asDouble(0.0);
            String rationale = textOrNull(node, "rationale");
            if (repairability == null) {
                return null;
            }
            return new ClassifierProposal(reasonCode, repairability, suggestedField, suggestedValue, confidence, rationale);
        } catch (Exception e) {
            log.warn("Classifier returned an unparseable response, treating as no proposal: {}", e.toString());
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** The model was asked for JSON only, but "asked to" is not "guaranteed to" -- strip a markdown fence if it added one anyway. */
    private static String stripMarkdownFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private boolean circuitOpen() {
        Instant openedAt = circuitOpenedAt.get();
        if (openedAt == null) {
            return false;
        }
        if (Instant.now(clock).isAfter(openedAt.plus(properties.circuitBreakerCooldown()))) {
            // Cooldown elapsed: let exactly one trial call through. If it fails, recordFailure
            // reopens the circuit and resets the cooldown clock; if it succeeds, recordSuccess
            // clears circuitOpenedAt entirely.
            return false;
        }
        return true;
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        circuitOpenedAt.set(null);
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.circuitBreakerFailureThreshold()) {
            circuitOpenedAt.set(Instant.now(clock));
        }
    }
}
