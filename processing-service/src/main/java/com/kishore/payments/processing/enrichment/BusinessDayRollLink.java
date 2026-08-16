package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Not a failure: rolls {@code settlementDate} forward, one day at a time,
 * until it lands on a business day for the instruction's currency (weekends
 * are computed, holidays come from refdata.business_calendar). Runs after
 * {@link CutoffLink} so a cutoff-driven roll onto a weekend keeps rolling --
 * a Friday-past-cutoff payment lands on Saturday from the cutoff link, then
 * rolls through Sunday to Monday here, not on Saturday.
 */
@Component
@Order(5)
public class BusinessDayRollLink implements EnrichmentLink {

    private final ReferenceDataService referenceData;

    public BusinessDayRollLink(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    @Override
    public void apply(PaymentInstructionEntity instruction) {
        var settlementDate = instruction.getSettlementDate();
        while (!referenceData.isBusinessDay(settlementDate, instruction.getCurrency())) {
            settlementDate = settlementDate.plusDays(1);
        }
        instruction.setSettlementDate(settlementDate);
    }
}
