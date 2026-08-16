package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DebtorAgentBicFormatRuleTest {

    private final DebtorAgentBicFormatRule rule = new DebtorAgentBicFormatRule();

    @ParameterizedTest
    @ValueSource(strings = {"DEUTDEFFXXX", "DEUTDEFF"})
    void passesForValidBics(String bic) {
        assertThat(rule.validate(entityWithDebtorAgentBic(bic))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEUT", "DEUTDEFFXX", "1EUTDEFFXXX"})
    void failsWithRc01RepairableForMalformedBics(String bic) {
        var violation = rule.validate(entityWithDebtorAgentBic(bic)).orElseThrow();
        assertThat(violation.reasonCode()).isEqualTo("RC01");
        assertThat(violation.repairability()).isEqualTo(Repairability.REPAIRABLE);
        assertThat(violation.field()).isEqualTo("debtorAgentBic");
    }

    private static PaymentInstructionEntity entityWithDebtorAgentBic(String bic) {
        return new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-1",
                null,
                "Debtor GmbH",
                "DE89370400440532013000",
                bic,
                "Creditor SARL",
                "FR1420041010050500013M02606",
                "DEUTDEFFXXX",
                BigDecimal.valueOf(500),
                "EUR",
                null,
                LocalDate.now());
    }
}
