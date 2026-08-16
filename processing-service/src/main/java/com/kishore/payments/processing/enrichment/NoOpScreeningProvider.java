package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import org.springframework.stereotype.Component;

/**
 * Always clears. The real screening integration is out of scope for Phase 4;
 * this keeps the pipeline runnable without one. No {@code @ConditionalOnMissingBean}
 * here -- nothing else in this codebase implements {@link ScreeningProvider}
 * yet, and that annotation on a plain {@code @Component} referencing its own
 * interface is a fragile, self-referential pattern rather than a real
 * safeguard. A future phase adding a real provider can wire it in directly.
 */
@Component
public class NoOpScreeningProvider implements ScreeningProvider {

    @Override
    public ScreeningResult screen(PaymentInstructionEntity instruction) {
        return ScreeningResult.CLEAR;
    }
}
