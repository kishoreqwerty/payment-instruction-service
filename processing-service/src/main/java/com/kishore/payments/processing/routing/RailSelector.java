package com.kishore.payments.processing.routing;

import com.kishore.payments.processing.refdata.RailDefinition;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The rail-selection logic itself, shared by two callers that both need it:
 * {@link RailRouter} (the routing stage, which records the decision and
 * fails the instruction with AG01 if nothing is eligible) and enrichment's
 * cutoff link (which needs to know which rail's cutoff applies before a
 * rail has been formally chosen). Selection is a pure function of
 * currency, amount and urgency, so both callers always agree on the same
 * answer -- there is exactly one place this decision is made.
 *
 * <p>Selection: no rail configured for the currency, or none whose amount
 * band covers this amount, is not eligible. Among eligible rails, an urgent
 * payment prefers a same-day rail and a routine one prefers a non-same-day
 * (typically cheaper) rail, falling back to whichever is actually eligible
 * when the preferred kind isn't available. USD's FEDWIRE and ACH_EQUIV bands
 * deliberately overlap (see V2__refdata_schema.sql) so this preference has
 * an amount range where it actually changes the outcome, rather than amount
 * alone always deciding it.
 */
@Component
public class RailSelector {

    private final ReferenceDataService referenceData;

    public RailSelector(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    /** Every rail configured for the currency whose amount band covers this amount -- the candidate pool selection chooses from, and what an audit trail records as "considered". */
    public List<RailDefinition> eligibleFor(String currency, BigDecimal amount) {
        return referenceData.railsFor(currency).stream().filter(rail -> rail.coversAmount(amount)).toList();
    }

    public Optional<RailDefinition> select(String currency, BigDecimal amount, boolean urgent) {
        List<RailDefinition> eligible = eligibleFor(currency, amount);
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        Optional<RailDefinition> preferred = eligible.stream().filter(rail -> rail.sameDay() == urgent).findFirst();
        return preferred.isPresent() ? preferred : Optional.of(eligible.get(0));
    }
}
