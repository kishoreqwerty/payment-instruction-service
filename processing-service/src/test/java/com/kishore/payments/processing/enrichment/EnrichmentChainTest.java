package com.kishore.payments.processing.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnrichmentChainTest {

    @Test
    void runsEveryLinkInTheOrderGiven() {
        List<String> executed = new ArrayList<>();
        EnrichmentLink first = instruction -> executed.add("first");
        EnrichmentLink second = instruction -> executed.add("second");
        EnrichmentLink third = instruction -> executed.add("third");

        new EnrichmentChain(List.of(first, second, third)).enrich(dummyInstruction());

        assertThat(executed).containsExactly("first", "second", "third");
    }

    private static PaymentInstructionEntity dummyInstruction() {
        return InstructionFixtures.eurInstruction(BigDecimal.TEN, LocalDate.now());
    }
}
