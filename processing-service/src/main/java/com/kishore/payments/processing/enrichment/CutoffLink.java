package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.refdata.RailDefinition;
import com.kishore.payments.processing.routing.RailSelector;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Not a failure: a missed cutoff is normal operation, and this link must
 * never throw. It rolls {@code settlementDate} forward by one day if the
 * requested date is today, in the eligible rail's own cutoff time zone, and
 * the current time is already past that rail's daily cutoff.
 *
 * <p>Which rail applies is determined by {@link RailSelector} -- the same
 * selection logic {@code RailRouter} uses in the routing stage, kept in one
 * place so enrichment's provisional pick and routing's final pick can never
 * disagree. If no rail is eligible yet (e.g. the currency has none
 * configured), there is no cutoff to apply here; that is a routing-stage
 * failure (AG01), not this link's concern.
 */
@Component
@Order(4)
public class CutoffLink implements EnrichmentLink {

    private final RailSelector railSelector;
    private final Clock clock;

    public CutoffLink(RailSelector railSelector, Clock clock) {
        this.railSelector = railSelector;
        this.clock = clock;
    }

    @Override
    public void apply(PaymentInstructionEntity instruction) {
        LocalDate settlementDate = instruction.getRequestedExecDate();

        Optional<RailDefinition> rail =
                railSelector.select(instruction.getCurrency(), instruction.getAmount(), isUrgent(instruction));
        if (rail.isPresent()) {
            ZonedDateTime nowInRailZone = clock.instant().atZone(rail.get().cutoffZone());
            boolean missedCutoff =
                    settlementDate.equals(nowInRailZone.toLocalDate()) && nowInRailZone.toLocalTime().isAfter(rail.get().cutoffTime());
            if (missedCutoff) {
                settlementDate = settlementDate.plusDays(1);
            }
        }

        instruction.setSettlementDate(settlementDate);
    }

    private boolean isUrgent(PaymentInstructionEntity instruction) {
        return instruction.getRequestedExecDate().equals(LocalDate.now(clock));
    }
}
