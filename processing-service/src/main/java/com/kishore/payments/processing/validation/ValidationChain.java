package com.kishore.payments.processing.validation;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link ValidationRule} regardless of earlier failures and
 * collects every violation, rather than stopping at the first: a message
 * with three defects should surface as one exception case naming all three,
 * so an operator repairs it once instead of resubmitting into the same
 * chain three separate times.
 */
@Component
public class ValidationChain {

    private final List<ValidationRule> rules;

    public ValidationChain(List<ValidationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<FailureDetail> validate(PaymentInstructionEntity instruction) {
        return rules.stream()
                .map(rule -> rule.validate(instruction))
                .flatMap(Optional::stream)
                .toList();
    }
}
