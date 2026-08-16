package com.kishore.payments.processing.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kishore.payments.processing.refdata.RailDefinition;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RailSelectorTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private static final RailDefinition FEDWIRE =
            new RailDefinition("FEDWIRE", "USD", new BigDecimal("100000.00"), null, true, LocalTime.of(17, 0), NY);
    private static final RailDefinition ACH_EQUIV =
            new RailDefinition("ACH_EQUIV", "USD", new BigDecimal("0.01"), new BigDecimal("149999.99"), false, LocalTime.of(15, 0), NY);
    private static final RailDefinition SEPA =
            new RailDefinition("SEPA", "EUR", new BigDecimal("0.01"), null, true, LocalTime.of(15, 0), PARIS);

    @Mock
    private ReferenceDataService referenceData;

    @Test
    void lowValueUsdNonUrgentGoesToAchEquiv() {
        when(referenceData.railsFor("USD")).thenReturn(List.of(FEDWIRE, ACH_EQUIV));
        var selector = new RailSelector(referenceData);

        var selected = selector.select("USD", new BigDecimal("5000.00"), false);

        assertThat(selected).map(RailDefinition::rail).contains("ACH_EQUIV");
    }

    @Test
    void highValueUsdGoesToFedwireRegardlessOfUrgency() {
        when(referenceData.railsFor("USD")).thenReturn(List.of(FEDWIRE, ACH_EQUIV));
        var selector = new RailSelector(referenceData);

        var selected = selector.select("USD", new BigDecimal("250000.00"), false);

        assertThat(selected).map(RailDefinition::rail).contains("FEDWIRE");
    }

    @Test
    void lowValueUrgentUsdStillGoesToAchEquivBecauseFedwiresFloorIsntMet() {
        when(referenceData.railsFor("USD")).thenReturn(List.of(FEDWIRE, ACH_EQUIV));
        var selector = new RailSelector(referenceData);

        // Below FEDWIRE's 100,000 floor entirely -- urgency can't buy a
        // same-day option that isn't eligible in the first place.
        var selected = selector.select("USD", new BigDecimal("5000.00"), true);

        assertThat(selected).map(RailDefinition::rail).contains("ACH_EQUIV");
    }

    @Test
    void inTheOverlapBandUrgencyDecidesBetweenBothEligibleRails() {
        when(referenceData.railsFor("USD")).thenReturn(List.of(FEDWIRE, ACH_EQUIV));
        var selector = new RailSelector(referenceData);

        // 120,000 sits in both rails' amount bands (FEDWIRE >= 100,000,
        // ACH_EQUIV <= 149,999.99): both are eligible, so urgency -- not
        // amount -- decides.
        BigDecimal inOverlap = new BigDecimal("120000.00");
        assertThat(selector.select("USD", inOverlap, true)).map(RailDefinition::rail).contains("FEDWIRE");
        assertThat(selector.select("USD", inOverlap, false)).map(RailDefinition::rail).contains("ACH_EQUIV");
    }

    @Test
    void anyEurAmountGoesToSepa() {
        when(referenceData.railsFor("EUR")).thenReturn(List.of(SEPA));
        var selector = new RailSelector(referenceData);

        assertThat(selector.select("EUR", new BigDecimal("10.00"), false)).map(RailDefinition::rail).contains("SEPA");
        assertThat(selector.select("EUR", new BigDecimal("999999.00"), true)).map(RailDefinition::rail).contains("SEPA");
    }

    @Test
    void noRailConfiguredForACurrencyIsNotEligible() {
        when(referenceData.railsFor("JPY")).thenReturn(List.of());
        var selector = new RailSelector(referenceData);

        assertThat(selector.select("JPY", BigDecimal.TEN, false)).isEmpty();
    }

    @Test
    void anAmountOutsideEveryRailsBandIsNotEligible() {
        when(referenceData.railsFor("USD")).thenReturn(List.of(ACH_EQUIV));
        var selector = new RailSelector(referenceData);

        // Above ACH_EQUIV's 149,999.99 ceiling and FEDWIRE isn't configured here.
        assertThat(selector.select("USD", new BigDecimal("500000.00"), false)).isEmpty();
    }
}
