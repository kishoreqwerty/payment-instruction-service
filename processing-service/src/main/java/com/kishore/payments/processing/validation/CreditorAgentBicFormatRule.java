package com.kishore.payments.processing.validation;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** RC01: the creditor agent BIC must be a structurally valid ISO 9362 BIC. */
@Component
public class CreditorAgentBicFormatRule implements ValidationRule {

    @Override
    public Optional<FailureDetail> validate(PaymentInstructionEntity instruction) {
        String bic = instruction.getCreditorAgentBic();
        if (BicFormat.isValid(bic)) {
            return Optional.empty();
        }
        return Optional.of(new FailureDetail(
                "RC01", Repairability.REPAIRABLE, "creditorAgentBic", "Creditor agent BIC is not a valid format: " + bic));
    }
}
