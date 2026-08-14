package com.kishore.payments.intake.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logback conversion word ("%masked") that redacts account-number-like digit
 * runs in the rendered log message down to their last four digits. Registered
 * once in logback-spring.xml so masking applies to every log line rather than
 * relying on call-site discipline.
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
