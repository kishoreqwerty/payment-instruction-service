package com.kishore.payments.processing.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScreeningLinkTest {

    @Test
    void noOpProviderNeverThrows() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());

        new ScreeningLink(new NoOpScreeningProvider()).apply(instruction);
        // No exception -- the instruction is untouched otherwise.
        assertThat(instruction.getState()).isNotNull();
    }

    @Test
    void aHeldResultThrowsUnrepairable() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());
        ScreeningProvider alwaysHolds = i -> ScreeningResult.HELD;

        assertThatThrownBy(() -> new ScreeningLink(alwaysHolds).apply(instruction))
                .isInstanceOfSatisfying(BusinessFailureException.class, e -> {
                    assertThat(e.stage()).isEqualTo(FailureStage.ENRICHMENT);
                    assertThat(e.details().get(0).repairability()).isEqualTo(Repairability.UNREPAIRABLE);
                });
    }
}
