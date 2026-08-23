package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RequestedExecutionDateNotPastRuleTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);
    private final RequestedExecutionDateNotPastRule rule = new RequestedExecutionDateNotPastRule(fixedClock);

    @Test
    void passesForToday() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.of(2026, 6, 15));
        assertThat(rule.validate(instruction)).isEmpty();
    }

    @Test
    void passesForAFutureDate() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.of(2026, 6, 16));
        assertThat(rule.validate(instruction)).isEmpty();
    }

    @Test
    void failsForAPastDate() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.of(2026, 6, 14));
        var violation = rule.validate(instruction).orElseThrow();
        assertThat(violation.reasonCode()).isEqualTo("DT01");
        assertThat(violation.field()).isEqualTo("requestedExecDate");
    }
}
