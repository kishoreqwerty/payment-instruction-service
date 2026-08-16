package com.kishore.payments.processing.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.refdata.RailDefinition;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RailRouterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final RailDefinition FEDWIRE =
            new RailDefinition("FEDWIRE", "USD", BigDecimal.ZERO, null, true, LocalTime.of(17, 0), ZoneId.of("America/New_York"));

    @Mock
    private RailSelector railSelector;

    @Test
    void recordsTheSelectedRailAndDecisionInputsOnSuccess() {
        var instruction = InstructionFixtures.usdInstruction(new BigDecimal("250000.00"), todayFor(CLOCK));
        instruction.setCorrespondentBic("CHASUS33XXX");
        when(railSelector.eligibleFor(anyString(), any())).thenReturn(List.of(FEDWIRE));
        when(railSelector.select(anyString(), any(), anyBoolean())).thenReturn(Optional.of(FEDWIRE));

        var decision = new RailRouter(railSelector, CLOCK).route(instruction);

        assertThat(instruction.getSelectedRail()).isEqualTo("FEDWIRE");
        assertThat(decision.selectedRail()).isEqualTo("FEDWIRE");
        assertThat(decision.currency()).isEqualTo("USD");
        assertThat(decision.correspondentBic()).isEqualTo("CHASUS33XXX");
        assertThat(decision.eligibleRails()).containsExactly("FEDWIRE");
    }

    @Test
    void throwsAg01RepairableWhenNothingIsEligible() {
        var instruction = InstructionFixtures.usdInstruction(new BigDecimal("250000.00"), todayFor(CLOCK));
        when(railSelector.eligibleFor(anyString(), any())).thenReturn(List.of());
        when(railSelector.select(anyString(), any(), anyBoolean())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new RailRouter(railSelector, CLOCK).route(instruction))
                .isInstanceOfSatisfying(BusinessFailureException.class, e -> {
                    assertThat(e.stage()).isEqualTo(FailureStage.ROUTING);
                    assertThat(e.details().get(0).reasonCode()).isEqualTo("AG01");
                    assertThat(e.details().get(0).repairability()).isEqualTo(Repairability.REPAIRABLE);
                });
    }

    private static java.time.LocalDate todayFor(Clock clock) {
        return java.time.LocalDate.now(clock);
    }
}
