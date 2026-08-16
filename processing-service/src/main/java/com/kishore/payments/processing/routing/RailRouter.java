package com.kishore.payments.processing.routing;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.failure.FailureDetail;
import com.kishore.payments.processing.refdata.RailDefinition;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Strategy pattern: selects a rail from currency, amount, urgency and
 * correspondent, via the same {@link RailSelector} enrichment's cutoff link
 * already used provisionally -- so the rail this stage commits to is always
 * the one the settlement date was already rolled against. AG01/REPAIRABLE
 * when nothing is eligible: an operator can select a rail manually, which
 * is what makes it repairable rather than static-data-blocked.
 */
@Component
public class RailRouter {

    private final RailSelector railSelector;
    private final Clock clock;

    public RailRouter(RailSelector railSelector, Clock clock) {
        this.railSelector = railSelector;
        this.clock = clock;
    }

    public RoutingDecision route(PaymentInstructionEntity instruction) {
        String currency = instruction.getCurrency();
        var amount = instruction.getAmount();
        boolean urgent = instruction.getRequestedExecDate().equals(LocalDate.now(clock));

        List<String> eligibleRailNames = railSelector.eligibleFor(currency, amount).stream().map(RailDefinition::rail).toList();
        Optional<RailDefinition> selected = railSelector.select(currency, amount, urgent);

        if (selected.isEmpty()) {
            throw new BusinessFailureException(
                    FailureStage.ROUTING,
                    new FailureDetail(
                            "AG01",
                            Repairability.REPAIRABLE,
                            "selectedRail",
                            "No eligible rail for " + amount + " " + currency));
        }

        instruction.setSelectedRail(selected.get().rail());

        return new RoutingDecision(currency, amount, urgent, instruction.getCorrespondentBic(), eligibleRailNames, selected.get().rail());
    }
}
