package com.kishore.payments.core.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback conversion word ("%masked") that redacts account-number-like digit
 * runs in the rendered log message down to their last four digits. Registered
 * once in each service's logback-spring.xml so masking applies to every log
 * line rather than relying on call-site discipline.
 *
 * <p>Lives in core (moved here in Phase 10, .notes/reports/PHASE-10-REPORT.md
 * section 6) rather than duplicated per service: every service's structured
 * JSON log output needs the exact same masking behaviour, and a single
 * shared class is what keeps that guarantee from drifting the way
 * independently-copied logic tends to.
 */
public class SensitiveFieldMaskingConverter extends ClassicConverter {

    // Account identifiers this system handles (IBAN account numbers,
    // ABA-routed account numbers) run 8+ digits. Shorter digit runs --
    // amounts, dates, ports -- are left alone.
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{8,}");

    @Override
    public String convert(ILoggingEvent event) {
        return mask(event.getFormattedMessage());
    }

    static String mask(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = ACCOUNT_NUMBER.matcher(message);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(message, lastEnd, matcher.start());
            String digits = matcher.group();
            String lastFour = digits.substring(digits.length() - 4);
            result.append("*".repeat(digits.length() - 4)).append(lastFour);
            lastEnd = matcher.end();
        }
        result.append(message.substring(lastEnd));
        return result.toString();
    }
}
