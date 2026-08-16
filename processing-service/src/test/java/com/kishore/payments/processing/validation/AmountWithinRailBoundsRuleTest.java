package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.refdata.RailDefinition;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AmountWithinRailBoundsRuleTest {

    @Mock
    private ReferenceDataService referenceData;

    @Test
    void passesWhenAmountFitsAConfiguredRail() {
        when(referenceData.railsFor("EUR"))
                .thenReturn(List.of(new RailDefinition("SEPA", "EUR", new BigDecimal("0.01"), null, true, LocalTime.NOON, ZoneId.of("Europe/Paris"))));
        var rule = new AmountWithinRailBoundsRule(referenceData);

        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());

        assertThat(rule.validate(instruction)).isEmpty();
    }

    @Test
    void failsWithAm02RepairableWhenAmountFitsNoConfiguredRail() {
        when(referenceData.railsFor("USD"))
                .thenReturn(List.of(new RailDefinition(
                        "ACH_EQUIV", "USD", new BigDecimal("0.01"), new BigDecimal("99999.99"), false, LocalTime.NOON, ZoneId.of("America/New_York"))));
        var rule = new AmountWithinRailBoundsRule(referenceData);

        var instruction = InstructionFixtures.usdInstruction(new BigDecimal("500000.00"), LocalDate.now());

        var violation = rule.validate(instruction).orElseThrow();
        assertThat(violation.reasonCode()).isEqualTo("AM02");
        assertThat(violation.repairability()).isEqualTo(Repairability.REPAIRABLE);
    }

    @Test
    void passesWhenNoRailIsConfiguredForTheCurrencyAtAll() {
        when(referenceData.railsFor("USD")).thenReturn(List.of());
        var rule = new AmountWithinRailBoundsRule(referenceData);

        var instruction = InstructionFixtures.usdInstruction(BigDecimal.valueOf(500), LocalDate.now());

        // A currency with no rail at all is a routing-stage problem (AG01),
        // not a validation-stage amount problem.
        assertThat(rule.validate(instruction)).isEmpty();
    }
}
