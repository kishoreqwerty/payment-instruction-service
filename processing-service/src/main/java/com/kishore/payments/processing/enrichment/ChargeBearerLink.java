package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Defaults charge bearer to SLEV ("following Service Level", the SEPA-conventional default) when the message omits it. Never fails. */
@Component
@Order(3)
public class ChargeBearerLink implements EnrichmentLink {

    static final String DEFAULT_CHARGE_BEARER = "SLEV";

    @Override
    public void apply(PaymentInstructionEntity instruction) {
        if (instruction.getChargeBearer() == null || instruction.getChargeBearer().isBlank()) {
            instruction.setChargeBearer(DEFAULT_CHARGE_BEARER);
        }
    }
}
