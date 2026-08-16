package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreditorAgentBicFormatRuleTest {

    private final CreditorAgentBicFormatRule rule = new CreditorAgentBicFormatRule();

    @ParameterizedTest
    @ValueSource(strings = {"DEUTDEFFXXX", "DEUTDEFF", "CHASUS33XXX", "CHASUS33"})
    void passesForValidBicsWithAndWithoutBranchCode(String bic) {
        assertThat(rule.validate(entityWithCreditorAgentBic(bic))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEUT", "DEUTDEFFXX", "1EUTDEFFXXX", "deutdeffxxx"})
    void failsWithRc01RepairableForMalformedBics(String bic) {
        var violation = rule.validate(entityWithCreditorAgentBic(bic)).orElseThrow();
        assertThat(violation.reasonCode()).isEqualTo("RC01");
        assertThat(violation.repairability()).isEqualTo(Repairability.REPAIRABLE);
        assertThat(violation.field()).isEqualTo("creditorAgentBic");
    }

    @Test
    void failsForNull() {
        assertThat(rule.validate(entityWithCreditorAgentBic(null))).isPresent();
    }

    private static PaymentInstructionEntity entityWithCreditorAgentBic(String bic) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-1",
                null,
                "Debtor GmbH",
                InstructionFixtures.eurInstruction(BigDecimal.ONE, LocalDate.now()).getDebtorAccount(),
                "DEUTDEFFXXX",
                "Creditor SARL",
                "FR1420041010050500013M02606",
                bic,
                BigDecimal.valueOf(500),
                "EUR",
                null,
                LocalDate.now());
    }
}
