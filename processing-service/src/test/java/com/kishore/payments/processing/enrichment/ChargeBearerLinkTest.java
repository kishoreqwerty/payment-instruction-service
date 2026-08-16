package com.kishore.payments.processing.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ChargeBearerLinkTest {

    private final ChargeBearerLink link = new ChargeBearerLink();

    @Test
    void defaultsToSlevWhenTheMessageOmitsChargeBearer() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());

        link.apply(instruction);

        assertThat(instruction.getChargeBearer()).isEqualTo("SLEV");
    }

    @Test
    void leavesAnExplicitChargeBearerUntouched() {
        var instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());
        instruction.setChargeBearer("DEBT");

        link.apply(instruction);

        assertThat(instruction.getChargeBearer()).isEqualTo("DEBT");
    }
}
