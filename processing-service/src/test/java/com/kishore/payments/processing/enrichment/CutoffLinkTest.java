package com.kishore.payments.processing.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.kishore.payments.processing.refdata.RailDefinition;
import com.kishore.payments.processing.routing.RailSelector;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A missed cutoff must never throw -- every scenario here asserts on the
 * resulting settlementDate, never on an exception.
 */
@ExtendWith(MockitoExtension.class)
class CutoffLinkTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final RailDefinition FEDWIRE_LIKE =
            new RailDefinition("FEDWIRE", "USD", BigDecimal.ZERO, null, true, LocalTime.of(17, 0), NY);

    @Mock
    private RailSelector railSelector;

    @Test
    void doesNotRollWhenCurrentTimeIsBeforeCutoff() {
        // 2026-06-15 16:00 America/New_York -- one hour before the 17:00 cutoff.
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T20:00:00Z"), ZoneOffset.UTC);
        LocalDate today = LocalDate.now(clock.withZone(NY));
        var instruction = InstructionFixtures.usdInstruction(BigDecimal.valueOf(500_000), today);
        when(railSelector.select(anyString(), any(), anyBoolean())).thenReturn(Optional.of(FEDWIRE_LIKE));

        new CutoffLink(railSelector, clock).apply(instruction);

        assertThat(instruction.getSettlementDate()).isEqualTo(today);
    }

    @Test
    void rollsForwardOneDayWhenCurrentTimeIsAfterCutoff() {
        // 2026-06-15 18:00 America/New_York -- one hour after the 17:00 cutoff.
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T22:00:00Z"), ZoneOffset.UTC);
        LocalDate today = LocalDate.now(clock.withZone(NY));
        var instruction = InstructionFixtures.usdInstruction(BigDecimal.valueOf(500_000), today);
        when(railSelector.select(anyString(), any(), anyBoolean())).thenReturn(Optional.of(FEDWIRE_LIKE));

        new CutoffLink(railSelector, clock).apply(instruction);

        assertThat(instruction.getSettlementDate()).isEqualTo(today.plusDays(1));
    }

    @Test
    void doesNotRollWhenSettlementIsAlreadyInTheFuture() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T22:00:00Z"), ZoneOffset.UTC);
        LocalDate future = LocalDate.now(clock.withZone(NY)).plusDays(3);
        var instruction = InstructionFixtures.usdInstruction(BigDecimal.valueOf(500_000), future);
        when(railSelector.select(anyString(), any(), anyBoolean())).thenReturn(Optional.of(FEDWIRE_LIKE));

        new CutoffLink(railSelector, clock).apply(instruction);

        assertThat(instruction.getSettlementDate()).isEqualTo(future);
    }

    @Test
    void leavesSettlementDateAtRequestedExecDateWhenNoRailIsEligible() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T22:00:00Z"), ZoneOffset.UTC);
        LocalDate today = LocalDate.now(clock);
        var instruction = InstructionFixtures.usdInstruction(BigDecimal.valueOf(500_000), today);
        when(railSelector.select(anyString(), any(), anyBoolean())).thenReturn(Optional.empty());

        new CutoffLink(railSelector, clock).apply(instruction);

        assertThat(instruction.getSettlementDate()).isEqualTo(today);
    }
}
