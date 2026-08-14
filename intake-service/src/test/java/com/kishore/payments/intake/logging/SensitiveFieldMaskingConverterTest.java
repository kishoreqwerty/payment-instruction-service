package com.kishore.payments.intake.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SensitiveFieldMaskingConverterTest {

    @Test
    void masksALongDigitRunToItsLastFourDigits() {
        String masked = SensitiveFieldMaskingConverter.mask("debtor account 12345678901234 processed");

        assertThat(masked).doesNotContain("12345678901234");
        assertThat(masked).contains("1234").contains("processed").contains("**********1234");
    }

    @Test
    void leavesShortDigitRunsAlone() {
        // Below the 8-digit threshold -- amounts, dates, ports, not account numbers.
        String masked = SensitiveFieldMaskingConverter.mask("amount 1234567 settled");

        assertThat(masked).isEqualTo("amount 1234567 settled");
    }

    @Test
    void masksMultipleOccurrencesInTheSameMessage() {
        String masked = SensitiveFieldMaskingConverter.mask("debtor 11112222333344 creditor 55556666777788");

        assertThat(masked)
                .doesNotContain("11112222333344")
                .doesNotContain("55556666777788")
                .contains("3344")
                .contains("7788");
    }

    @Test
    void leavesMessagesWithNoDigitRunsUnchanged() {
        assertThat(SensitiveFieldMaskingConverter.mask("no sensitive data here")).isEqualTo("no sensitive data here");
    }

    @Test
    void handlesNullGracefully() {
        assertThat(SensitiveFieldMaskingConverter.mask(null)).isNull();
    }

    @Test
    void convertAppliesMaskingToARealLoggingEvent() {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(LoggerFactory.getLogger(SensitiveFieldMaskingConverterTest.class).getName());
        event.setLevel(Level.INFO);
        event.setMessage("account 98765432109876 received");

        String converted = new SensitiveFieldMaskingConverter().convert(event);

        assertThat(converted).doesNotContain("98765432109876").contains("9876");
    }
}
