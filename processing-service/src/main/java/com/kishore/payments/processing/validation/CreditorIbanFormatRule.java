package com.kishore.payments.processing.validation;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * AC01: the creditor account must be a structurally and check-digit valid
 * IBAN -- the creditor-side counterpart {@link DebtorIbanFormatRule} did not
 * have until this class was added. Found running the full pipeline locally
 * against a real database (not by any unit or integration test, all of
 * which happened to use a valid creditor IBAN in every fixture): a bad
 * creditor IBAN passed VALIDATION untouched and only surfaced later, at
 * ENRICHMENT, if the creditor agent BIC it was paired with also happened
 * not to resolve to a correspondent relationship -- a coincidental,
 * unrelated failure that masked the real defect rather than exposing it.
 * See {@code .notes/reports/CROSS-SERVICE-INTEGRATION-DEFECTS.md}.
 */
@Component
public class CreditorIbanFormatRule implements ValidationRule {

    @Override
    public Optional<FailureDetail> validate(PaymentInstructionEntity instruction) {
        String iban = instruction.getCreditorAccount();
        if (IbanValidator.isValid(iban)) {
            return Optional.empty();
        }
        return Optional.of(new FailureDetail(
                "AC01", Repairability.REPAIRABLE, "creditorAccount", "Creditor account is not a valid IBAN: " + iban));
    }
}
