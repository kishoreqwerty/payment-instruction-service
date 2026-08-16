package com.kishore.payments.processing.validation;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** No ISO reason code: the requested execution date must not be strictly before today. */
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
                null,
                Repairability.REPAIRABLE,
                "requestedExecDate",
                "Requested execution date " + requested + " is in the past (today is " + today + ")"));
    }
}
