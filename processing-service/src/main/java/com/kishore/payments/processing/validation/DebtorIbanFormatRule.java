package com.kishore.payments.processing.validation;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * AC01: the debtor account must be a structurally and check-digit valid
 * IBAN. Named {@code Debtor...} to pair explicitly with {@link
 * CreditorIbanFormatRule}, the way {@link DebtorAgentBicFormatRule} and
 * {@link CreditorAgentBicFormatRule} already pair -- see that class's own
 * javadoc for why the un-prefixed {@code IbanFormatRule} this was renamed
 * from was itself the bug: it checked only the debtor side, and nothing
 * else in {@link ValidationChain} ever checked the creditor account's IBAN
 * at all.
 */
@Component
public class DebtorIbanFormatRule implements ValidationRule {

    @Override
    public Optional<FailureDetail> validate(PaymentInstructionEntity instruction) {
        String iban = instruction.getDebtorAccount();
        if (IbanValidator.isValid(iban)) {
            return Optional.empty();
        }
        return Optional.of(new FailureDetail(
                "AC01", Repairability.REPAIRABLE, "debtorAccount", "Debtor account is not a valid IBAN: " + iban));
    }
}
