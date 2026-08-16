package com.kishore.payments.processing.validation;

import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.FailureDetail;
import com.kishore.payments.processing.refdata.RailDefinition;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * AM02: the amount must fit at least one configured rail for the currency.
 * Deliberately does not check whether the currency itself has any rail at
 * all -- a currency with no configured rail is a routing-stage problem
 * (AG01, no eligible rail), not a validation-stage amount problem. A rail
 * hasn't been chosen yet at this point in the pipeline; this only rules out
 * amounts that no rail in that currency could ever carry.
 */
@Component
public class AmountWithinRailBoundsRule implements ValidationRule {

    private final ReferenceDataService referenceData;

    public AmountWithinRailBoundsRule(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    @Override
    public Optional<FailureDetail> validate(PaymentInstructionEntity instruction) {
        List<RailDefinition> rails = referenceData.railsFor(instruction.getCurrency());
        if (rails.isEmpty()) {
            return Optional.empty();
        }
        boolean fitsSomeRail = rails.stream().anyMatch(rail -> rail.coversAmount(instruction.getAmount()));
        if (fitsSomeRail) {
            return Optional.empty();
        }
        return Optional.of(new FailureDetail(
                "AM02",
                Repairability.REPAIRABLE,
                "amount",
                "Amount " + instruction.getAmount() + " " + instruction.getCurrency() + " fits no configured rail's bounds"));
    }
}
