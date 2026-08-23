package com.kishore.payments.exception.classifier;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.event.InstructionExceptionEvent;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Builds the classifier's outbound payload from an explicit allowlist, never by starting from a
 * fuller object and removing fields -- a denylist fails open the day someone adds a field to
 * {@link PaymentInstructionEntity} or {@link InstructionExceptionEvent.Detail} (.notes/reports/
 * PHASE-11-REPORT.md section 2). {@link ClassifierRequest} is the complete, fixed shape; nothing
 * this class builds ever has an extra key.
 *
 * <p>The one place this is genuinely hard, not just mechanical: several validation rules
 * interpolate a raw field value directly into their own error message (e.g. {@code
 * DebtorIbanFormatRule}: {@code "Debtor account is not a valid IBAN: " + iban}). The message is on
 * the permitted list ("validator or rail error message"), but forwarding it unmodified would leak
 * exactly the account numbers/BICs/names this class exists to keep out. {@link #sanitizeMessage}
 * handles this by substituting every one of *this instruction's own* known-sensitive values out of
 * the message text before anything else touches it, then applies a shape-based regex sweep as a
 * second, independent layer -- not because the first layer is expected to miss something, but
 * because a single point of failure in a redaction path is exactly the kind of thing this class is
 * supposed to make structurally impossible.
 */
@Component
public class PromptRedactor {

    private static final List<BigDecimal> AMOUNT_BAND_UPPER_BOUNDS = List.of(
            new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("10000"), new BigDecimal("100000"), new BigDecimal("1000000"));
    private static final List<String> AMOUNT_BAND_LABELS =
            List.of("UNDER_100", "100_TO_1K", "1K_TO_10K", "10K_TO_100K", "100K_TO_1M", "1M_PLUS");

    // Defense-in-depth only (see class javadoc): the primary redaction is the exact-value
    // substitution in sanitizeMessage, which works regardless of these patterns. IBAN shape:
    // two letters, two check digits, 10-30 further alphanumerics. Long digit run: the same
    // 8+-digit threshold core's own SensitiveFieldMaskingConverter uses for log output.
    private static final Pattern IBAN_SHAPED = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{8,}");
    private static final String REDACTED = "[REDACTED]";

    private final Clock clock;

    public PromptRedactor(Clock clock) {
        this.clock = clock;
    }

    public ClassifierRequest redact(
            FailureStage failureStage, PaymentInstructionEntity instruction, InstructionExceptionEvent.Detail detail,
            int repairAttemptCount) {
        return new ClassifierRequest(
                // Not instruction.getState(): every failed instruction sits at InstructionState
                // .EXCEPTION regardless of which pipeline stage actually failed (VALIDATION,
                // ENRICHMENT, CONFIRMATION, ...) -- the caller's own failureStage (from the case
                // this proposal is for) is what the classifier actually needs to distinguish, say,
                // a malformed-BIC format error (VALIDATION, REPAIRABLE) from the same RC01 code
                // meaning "no correspondent on file" (ENRICHMENT, STATIC_DATA).
                failureStage.name(),
                detail.reasonCode(),
                sanitizeMessage(detail.detail(), instruction),
                fieldShape(detail.field(), instruction),
                instruction.getCurrency(),
                instruction.getSelectedRail(),
                amountBand(instruction.getAmount()),
                instructionAgeDays(instruction),
                repairAttemptCount);
    }

    private long instructionAgeDays(PaymentInstructionEntity instruction) {
        OffsetDateTime createdAt = instruction.getCreatedAt();
        if (createdAt == null) {
            return 0;
        }
        return Duration.between(createdAt, OffsetDateTime.now(clock)).toDays();
    }

    static String amountBand(BigDecimal amount) {
        BigDecimal abs = amount.abs();
        for (int i = 0; i < AMOUNT_BAND_UPPER_BOUNDS.size(); i++) {
            if (abs.compareTo(AMOUNT_BAND_UPPER_BOUNDS.get(i)) < 0) {
                return AMOUNT_BAND_LABELS.get(i);
            }
        }
        return AMOUNT_BAND_LABELS.get(AMOUNT_BAND_LABELS.size() - 1);
    }

    /**
     * {@code null} if {@code fieldPath} is null (many failures -- enrichment, dispatch, routing
     * -- aren't attributable to one instruction field at all) or doesn't name a field this class
     * knows how to describe structurally.
     */
    private ClassifierRequest.FieldShape fieldShape(String fieldPath, PaymentInstructionEntity instruction) {
        if (fieldPath == null) {
            return null;
        }
        String value = fieldValue(fieldPath, instruction);
        if (value == null) {
            return null;
        }
        return new ClassifierRequest.FieldShape(
                fieldPath, value.length(), characterClasses(value), ibanChecksumValid(fieldPath, value), countryPrefix(value));
    }

    private static String fieldValue(String fieldPath, PaymentInstructionEntity instruction) {
        return switch (fieldPath) {
            case "debtorAccount" -> instruction.getDebtorAccount();
            case "creditorAccount" -> instruction.getCreditorAccount();
            case "debtorAgentBic" -> instruction.getDebtorAgentBic();
            case "creditorAgentBic" -> instruction.getCreditorAgentBic();
            case "creditorName" -> instruction.getCreditorName();
            case "chargeBearer" -> instruction.getChargeBearer();
            default -> null;
        };
    }

    private static final List<String> IBAN_FIELD_PATHS = List.of("debtorAccount", "creditorAccount");

    private static Boolean ibanChecksumValid(String fieldPath, String value) {
        if (!IBAN_FIELD_PATHS.contains(fieldPath)) {
            return null;
        }
        return isValidIbanChecksum(value);
    }

    /** Mod-97 check per ISO 7064: rearrange, letters -> two-digit numbers (A=10..Z=35), mod 97 == 1. */
    static boolean isValidIbanChecksum(String iban) {
        String cleaned = iban.replaceAll("\\s", "").toUpperCase();
        if (cleaned.length() < 5 || !cleaned.substring(0, 2).chars().allMatch(Character::isLetter)
                || !cleaned.substring(2, 4).chars().allMatch(Character::isDigit)) {
            return false;
        }
        String rearranged = cleaned.substring(4) + cleaned.substring(0, 4);
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (Character.isLetter(c)) {
                numeric.append(c - 'A' + 10);
            } else {
                return false;
            }
        }
        java.math.BigInteger value = new java.math.BigInteger(numeric.toString());
        return value.mod(java.math.BigInteger.valueOf(97)).equals(java.math.BigInteger.ONE);
    }

    private static String countryPrefix(String value) {
        if (value.length() >= 2 && Character.isLetter(value.charAt(0)) && Character.isLetter(value.charAt(1))) {
            return value.substring(0, 2).toUpperCase();
        }
        return null;
    }

    private static String characterClasses(String value) {
        if (value.isEmpty()) {
            return "EMPTY";
        }
        boolean hasAlpha = value.chars().anyMatch(Character::isLetter);
        boolean hasDigit = value.chars().anyMatch(Character::isDigit);
        boolean hasOther = value.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (hasOther) {
            return "MIXED";
        }
        if (hasAlpha && hasDigit) {
            return "ALPHA_DIGITS";
        }
        return hasDigit ? "DIGITS_ONLY" : "ALPHA_ONLY";
    }

    /**
     * Replaces every occurrence of this specific instruction's own known-sensitive values with
     * {@code [REDACTED]}, longest values first (so a short value that happens to be a substring
     * of a longer one -- unlikely, but not impossible -- doesn't leave a partial redaction
     * behind), then sweeps the result for IBAN-shaped and long-digit-run substrings as a second,
     * independent layer. {@code null} in, {@code null} out.
     */
    static String sanitizeMessage(String message, PaymentInstructionEntity instruction) {
        if (message == null) {
            return null;
        }
        Map<String, String> knownValues = new LinkedHashMap<>();
        putIfPresent(knownValues, instruction.getDebtorAccount());
        putIfPresent(knownValues, instruction.getCreditorAccount());
        putIfPresent(knownValues, instruction.getDebtorAgentBic());
        putIfPresent(knownValues, instruction.getCreditorAgentBic());
        putIfPresent(knownValues, instruction.getCorrespondentBic());
        putIfPresent(knownValues, instruction.getNostroAccount());
        putIfPresent(knownValues, instruction.getDebtorName());
        putIfPresent(knownValues, instruction.getCreditorName());
        putIfPresent(knownValues, instruction.getEndToEndId());
        putIfPresent(knownValues, instruction.getInstructionIdExt());
        putIfPresent(knownValues, instruction.getUetr() == null ? null : instruction.getUetr().toString());
        putIfPresent(knownValues, instruction.getInstructionId() == null ? null : instruction.getInstructionId().toString());

        String result = message;
        for (String sensitive : knownValues.keySet().stream().sorted((a, b) -> b.length() - a.length()).toList()) {
            result = result.replace(sensitive, REDACTED);
        }
        result = IBAN_SHAPED.matcher(result).replaceAll(REDACTED);
        result = LONG_DIGIT_RUN.matcher(result).replaceAll(REDACTED);
        return result;
    }

    private static void putIfPresent(Map<String, String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.put(value, value);
        }
    }
}
