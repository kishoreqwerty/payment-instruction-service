package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditorIbanFormatRuleTest {

    private final CreditorIbanFormatRule rule = new CreditorIbanFormatRule();

    @Test
    void passesForAValidIban() {
        PaymentInstructionEntity instruction = InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now());

        assertThat(rule.validate(instruction)).isEmpty();
    }

    /** The exact reproduction from the shared-database run that found this rule missing entirely. */
    @Test
    void failsWithAc01RepairableForAnInvalidIban() {
        PaymentInstructionEntity instruction = new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-1",
                null,
                "Debtor GmbH",
                "DE89370400440532013000",
                "DEUTDEFFXXX",
                "Creditor SARL",
                "FR9999999999999999999X99999", // fails mod-97
                "DEUTDEFFXXX",
                BigDecimal.valueOf(500),
                "EUR",
                null,
                LocalDate.now());

        var violation = rule.validate(instruction).orElseThrow();
        assertThat(violation.reasonCode()).isEqualTo("AC01");
        assertThat(violation.repairability()).isEqualTo(Repairability.REPAIRABLE);
        assertThat(violation.field()).isEqualTo("creditorAccount");
    }
}
