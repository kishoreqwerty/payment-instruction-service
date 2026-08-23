package com.kishore.payments.processing.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.support.InstructionFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrencyConsistentWithDebtorCountryRuleTest {

    private final CurrencyConsistentWithDebtorCountryRule rule = new CurrencyConsistentWithDebtorCountryRule();

    @Test
    void passesWhenCurrencyMatchesTheDebtorsSingleCurrencyCountry() {
        // eurInstruction's debtor account is a German IBAN, currency EUR.
        assertThat(rule.validate(InstructionFixtures.eurInstruction(BigDecimal.valueOf(500), LocalDate.now())))
                .isEmpty();
    }

    @Test
    void failsWithCurrWhenCurrencyContradictsASingleCurrencyCountry() {
        PaymentInstructionEntity germanDebtorUsdPayment = new PaymentInstructionEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "E2E-1",
                null,
                "Debtor GmbH",
                "DE89370400440532013000", // German IBAN -> EUR expected
                "DEUTDEFFXXX",
                "Creditor Inc",
                "GB29NWBK60161331926819",
                "CHASUS33XXX",
                BigDecimal.valueOf(500),
                "USD",
                null,
                LocalDate.now());

        var violation = rule.validate(germanDebtorUsdPayment).orElseThrow();
        assertThat(violation.reasonCode()).isEqualTo("CURR");
        assertThat(violation.field()).isEqualTo("currency");
    }

    @Test
    void staysSilentForACountryOutsideTheKnownSingleCurrencyTable() {
        // usdInstruction's debtor account is Polish -- deliberately not in
        // the rule's table, so a mismatch there is not flagged.
        assertThat(rule.validate(InstructionFixtures.usdInstruction(BigDecimal.valueOf(500), LocalDate.now())))
                .isEmpty();
    }
}
