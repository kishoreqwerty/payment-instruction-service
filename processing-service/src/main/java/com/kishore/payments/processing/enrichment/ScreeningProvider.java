package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;

/** Sanctions/watchlist screening. A real provider is an external, out-of-process boundary; Phase 4 defines the seam and wires {@link NoOpScreeningProvider}. */
public interface ScreeningProvider {

    ScreeningResult screen(PaymentInstructionEntity instruction);
}
