package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ValidationChain must run every rule and collect every violation, not stop at the first -- verified here with fakes rather than the real rules, so this test is about the chain's own behaviour, not any one rule's. */
class ValidationChainTest {

    @Test
    void collectsViolationsFromEveryFailingRuleRatherThanStoppingAtTheFirst() {
        ValidationRule alwaysFailsA = instruction -> Optional.of(new FailureDetail("A01", Repairability.REPAIRABLE, "fieldA", "fails A"));
        ValidationRule alwaysPasses = instruction -> Optional.empty();
        ValidationRule alwaysFailsB = instruction -> Optional.of(new FailureDetail("B01", Repairability.REPAIRABLE, "fieldB", "fails B"));

        ValidationChain chain = new ValidationChain(List.of(alwaysFailsA, alwaysPasses, alwaysFailsB));

        List<FailureDetail> violations = chain.validate(dummyInstruction());

        assertThat(violations).extracting(FailureDetail::reasonCode).containsExactlyInAnyOrder("A01", "B01");
    }

    @Test
    void returnsNoViolationsWhenEveryRulePasses() {
        ValidationChain chain = new ValidationChain(List.of(instruction -> Optional.empty(), instruction -> Optional.empty()));

        assertThat(chain.validate(dummyInstruction())).isEmpty();
    }

    private static PaymentInstructionEntity dummyInstruction() {
        return com.kishore.payments.processing.support.InstructionFixtures.eurInstruction(
                java.math.BigDecimal.TEN, java.time.LocalDate.now());
    }
}
