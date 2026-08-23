package com.kishore.payments.processing.validation;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * ISO 20022 external code DT01 ("InvalidDate" -- "Invalid date (eg, wrong or missing settlement
 * date)") applies here: the requested execution date must not be strictly before today. This
 * rule previously emitted no reason code at all; {@code .notes/ARCHITECTURE.md} §6.1 never had a
 * row for it either -- both were a spec gap, not a deliberate omission (unlike the cutoff-miss
 * and non-business-date rows, which really are code-less because they are not failures).
 */
@Component
public class RequestedExecutionDateNotPastRule implements ValidationRule {

    private final Clock clock;

    public RequestedExecutionDateNotPastRule(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<FailureDetail> validate(PaymentInstructionEntity instruction) {
        LocalDate today = LocalDate.now(clock);
        LocalDate requested = instruction.getRequestedExecDate();
        if (!requested.isBefore(today)) {
            return Optional.empty();
        }
        return Optional.of(new FailureDetail(
                "DT01",
                Repairability.REPAIRABLE,
                "requestedExecDate",
                "Requested execution date " + requested + " is in the past (today is " + today + ")"));
    }
}
