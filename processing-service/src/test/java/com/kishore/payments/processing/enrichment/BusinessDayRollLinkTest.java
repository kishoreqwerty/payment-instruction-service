package com.kishore.payments.processing.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kishore.payments.processing.refdata.ReferenceDataService;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BusinessDayRollLinkTest {

    @Mock
    private ReferenceDataService referenceData;

    @Test
    void doesNotRollWhenAlreadyOnABusinessDay() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.of(2026, 6, 17));
        instruction.setSettlementDate(LocalDate.of(2026, 6, 17));
        when(referenceData.isBusinessDay(LocalDate.of(2026, 6, 17), "EUR")).thenReturn(true);

        new BusinessDayRollLink(referenceData).apply(instruction);

        assertThat(instruction.getSettlementDate()).isEqualTo(LocalDate.of(2026, 6, 17));
    }

    @Test
    void rollsForwardThroughAWeekendToMonday() {
        LocalDate saturday = LocalDate.of(2026, 6, 20);
        LocalDate sunday = LocalDate.of(2026, 6, 21);
        LocalDate monday = LocalDate.of(2026, 6, 22);
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), saturday);
        instruction.setSettlementDate(saturday);
        when(referenceData.isBusinessDay(saturday, "EUR")).thenReturn(false);
        when(referenceData.isBusinessDay(sunday, "EUR")).thenReturn(false);
        when(referenceData.isBusinessDay(monday, "EUR")).thenReturn(true);

        new BusinessDayRollLink(referenceData).apply(instruction);

        assertThat(instruction.getSettlementDate()).isEqualTo(monday);
    }

    @Test
    void rollsForwardAcrossAMonthBoundary() {
        LocalDate lastDayOfMonth = LocalDate.of(2026, 1, 31); // a Saturday
        LocalDate firstOfNextMonth = LocalDate.of(2026, 2, 1); // a Sunday
        LocalDate secondOfNextMonth = LocalDate.of(2026, 2, 2); // a Monday
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), lastDayOfMonth);
        instruction.setSettlementDate(lastDayOfMonth);
        when(referenceData.isBusinessDay(lastDayOfMonth, "EUR")).thenReturn(false);
        when(referenceData.isBusinessDay(firstOfNextMonth, "EUR")).thenReturn(false);
        when(referenceData.isBusinessDay(secondOfNextMonth, "EUR")).thenReturn(true);

        new BusinessDayRollLink(referenceData).apply(instruction);

        assertThat(instruction.getSettlementDate()).isEqualTo(secondOfNextMonth);
    }

    @Test
    void rollsForwardOverAHolidayThatIsNotAWeekend() {
        LocalDate holidayWeekday = LocalDate.of(2026, 8, 31); // seeded as a USD holiday in V2__refdata_schema.sql
        LocalDate dayAfter = LocalDate.of(2026, 9, 1);
        var instruction = InstructionFixtures.usdInstruction(BigDecimal.valueOf(500), holidayWeekday);
        instruction.setSettlementDate(holidayWeekday);
        when(referenceData.isBusinessDay(holidayWeekday, "USD")).thenReturn(false);
        when(referenceData.isBusinessDay(dayAfter, "USD")).thenReturn(true);

        new BusinessDayRollLink(referenceData).apply(instruction);

        assertThat(instruction.getSettlementDate()).isEqualTo(dayAfter);
    }
}
