package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DebtorIbanFormatRuleTest {

    private final DebtorIbanFormatRule rule = new DebtorIbanFormatRule();

    @Test
    void passesForAValidIban() {
        PaymentInstructionEntity instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());

        assertThat(rule.validate(instruction)).isEmpty();
    }

    @Test
    void failsWithAc01RepairableForAnInvalidIban() {
        PaymentInstructionEntity instruction = new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-1",
                null,
                "Debtor GmbH",
                "DE00370400440532013000", // check digits corrupted
                "DEUTDEFFXXX",
                "Creditor SARL",
                "FR1420041010050500013M02606",
                "DEUTDEFFXXX",
                BigDecimal.valueOf(500),
                "EUR",
                null,
                LocalDate.now());

        var violation = rule.validate(instruction).orElseThrow();
        assertThat(violation.reasonCode()).isEqualTo("AC01");
        assertThat(violation.repairability()).isEqualTo(Repairability.REPAIRABLE);
        assertThat(violation.field()).isEqualTo("debtorAccount");
    }
}
