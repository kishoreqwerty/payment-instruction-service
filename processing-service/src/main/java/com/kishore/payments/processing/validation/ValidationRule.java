package com.kishore.payments.processing.validation;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.util.Optional;

/**
 * Strategy pattern: each rule is independent and independently testable,
 * evaluating one concern against the instruction and returning the
 * violation if it fails. {@link ValidationChain} runs every rule regardless
 * of earlier failures and collects the full set -- a message with three
 * defects should produce one exception case naming all three, not three
 * separate round trips through repair.
 */
public interface ValidationRule {

    Optional<FailureDetail> validate(PaymentInstructionEntity instruction);
}
