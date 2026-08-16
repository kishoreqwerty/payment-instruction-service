package com.kishore.payments.processing.validation;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * No ISO reason code: this is a plausibility check, not a rail-defined
 * defect. The debtor's country is read from the IBAN prefix, not a
 * separate field -- there is no other country signal on the instruction.
 * Deliberately bounded to a small set of single-currency countries: for a
 * country outside this table (most of the world), a mismatch is not
 * necessarily wrong -- a German debtor can genuinely originate a USD
 * payment -- so this rule stays silent rather than guessing.
 */
@Component
public class CurrencyConsistentWithDebtorCountryRule implements ValidationRule {

    private static final Map<String, String> SINGLE_CURRENCY_COUNTRIES = Map.ofEntries(
            Map.entry("DE", "EUR"), Map.entry("FR", "EUR"), Map.entry("IT", "EUR"), Map.entry("ES", "EUR"),
            Map.entry("NL", "EUR"), Map.entry("BE", "EUR"), Map.entry("AT", "EUR"), Map.entry("IE", "EUR"),
            Map.entry("PT", "EUR"), Map.entry("FI", "EUR"), Map.entry("GR", "EUR"), Map.entry("LU", "EUR"),
            Map.entry("GB", "GBP"), Map.entry("SE", "SEK"), Map.entry("NO", "NOK"), Map.entry("DK", "DKK"),
            Map.entry("CH", "CHF"));

    @Override
    public Optional<FailureDetail> validate(PaymentInstructionEntity instruction) {
        String debtorAccount = instruction.getDebtorAccount();
        if (debtorAccount == null || debtorAccount.length() < 2) {
            return Optional.empty();
        }
        String debtorCountry = debtorAccount.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        String expectedCurrency = SINGLE_CURRENCY_COUNTRIES.get(debtorCountry);
        if (expectedCurrency == null || expectedCurrency.equalsIgnoreCase(instruction.getCurrency())) {
            return Optional.empty();
        }
        return Optional.of(new FailureDetail(
                null,
                Repairability.REPAIRABLE,
                "currency",
                "Currency " + instruction.getCurrency() + " is inconsistent with debtor country " + debtorCountry
                        + " (expected " + expectedCurrency + ")"));
    }
}
