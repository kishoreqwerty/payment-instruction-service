package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.domain.FailureStage;
import com.kishore.payments.core.domain.Repairability;
import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import com.kishore.payments.processing.failure.BusinessFailureException;
import com.kishore.payments.processing.failure.FailureDetail;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * A held screening result routes to the exception path as UNREPAIRABLE (a screening hit needs
 * investigation, not a field correction) -- unreachable with {@link NoOpScreeningProvider}, which
 * never returns anything but CLEAR. ISO 20022 external code RR04 ("RegulatoryReason") applies:
 * it is the standard code for a regulatory/sanctions hold and, per its own definition, should
 * never be retried without compliance review -- consistent with UNREPAIRABLE here. Previously
 * emitted no reason code at all.
 */
@Component
@Order(6)
public class ScreeningLink implements EnrichmentLink {

    private final ScreeningProvider screeningProvider;

    public ScreeningLink(ScreeningProvider screeningProvider) {
        this.screeningProvider = screeningProvider;
    }

    @Override
    public void apply(PaymentInstructionEntity instruction) {
        if (screeningProvider.screen(instruction) == ScreeningResult.HELD) {
            throw new BusinessFailureException(
                    FailureStage.ENRICHMENT,
                    new FailureDetail(
                            "RR04", Repairability.UNREPAIRABLE, null,
                            "Instruction held by screening: " + instruction.getInstructionId()));
        }
    }
}
