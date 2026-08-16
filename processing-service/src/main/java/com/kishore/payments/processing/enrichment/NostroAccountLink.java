package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.failure.FailureDetail;
import com.kishore.payments.processing.refdata.NostroAccount;
import com.kishore.payments.processing.refdata.ReferenceDataService;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * RC01/STATIC_DATA: the correspondent resolved by {@link
 * CorrespondentResolutionLink} has no nostro account on file for this
 * instruction's settlement currency. Runs after that link, not before --
 * there is nothing to look a nostro account up for until correspondentBic
 * is set.
 */
@Component
@Order(2)
public class NostroAccountLink implements EnrichmentLink {

    private final ReferenceDataService referenceData;

    public NostroAccountLink(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    @Override
    public void apply(PaymentInstructionEntity instruction) {
        Optional<NostroAccount> nostro = referenceData.findNostroAccount(instruction.getCorrespondentBic(), instruction.getCurrency());
        if (nostro.isEmpty()) {
            throw new BusinessFailureException(
                    FailureStage.ENRICHMENT,
                    new FailureDetail(
                            "RC01",
                            Repairability.STATIC_DATA,
                            "nostroAccount",
                            "No nostro account on file for correspondent " + instruction.getCorrespondentBic() + " in "
                                    + instruction.getCurrency()));
        }
        instruction.setNostroAccount(nostro.get().accountNumber());
    }
}
