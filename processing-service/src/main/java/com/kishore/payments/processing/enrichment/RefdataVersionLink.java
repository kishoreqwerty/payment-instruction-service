package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Records which refdata version this instruction was enriched against, so "why did this go to Fedwire" can be answered from the audit trail even after refdata later changes underneath it. */
@Component
@Order(7)
public class RefdataVersionLink implements EnrichmentLink {

    private final ReferenceDataService referenceData;

    public RefdataVersionLink(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    @Override
    public void apply(PaymentInstructionEntity instruction) {
        instruction.setRefdataVersion(referenceData.currentVersion());
    }
}
