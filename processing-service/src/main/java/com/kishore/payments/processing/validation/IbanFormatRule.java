package com.kishore.payments.processing.validation;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** AC01: the debtor account must be a structurally and check-digit valid IBAN. */
@Component
public class IbanFormatRule implements ValidationRule {

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
