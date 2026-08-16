package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.failure.FailureDetail;
import com.kishore.payments.processing.refdata.CorrespondentRelationship;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * RC01/STATIC_DATA: no correspondent relationship on file for the creditor
 * agent BIC. STATIC_DATA, not REPAIRABLE, because there is nothing wrong
 * with the instruction itself -- an operator correcting a field on this
 * payment cannot fix a missing correspondent relationship. It blocks until
 * reference data is updated (.notes/ARCHITECTURE.md §6.1).
 */
@Component
@Order(1)
public class CorrespondentResolutionLink implements EnrichmentLink {

    private final ReferenceDataService referenceData;

    public CorrespondentResolutionLink(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    @Override
    public void apply(PaymentInstructionEntity instruction) {
        Optional<CorrespondentRelationship> relationship = referenceData.findCorrespondent(instruction.getCreditorAgentBic());
        if (relationship.isEmpty()) {
            throw new BusinessFailureException(
                    FailureStage.ENRICHMENT,
                    new FailureDetail(
                            "RC01",
                            Repairability.STATIC_DATA,
                            "creditorAgentBic",
                            "No correspondent relationship on file for creditor agent BIC " + instruction.getCreditorAgentBic()));
        }
        instruction.setCorrespondentBic(relationship.get().correspondentBic());
    }
}
