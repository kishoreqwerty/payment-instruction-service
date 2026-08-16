package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;

/**
 * Chain of Responsibility: each link mutates the instruction in place and
 * either falls through to the next link, or throws {@link
 * com.kishore.payments.processing.failure.BusinessFailureException} to
 * short-circuit the whole chain into the exception path. Not every link can
 * fail -- the cutoff and business-day rolling links never throw, since a
 * missed cutoff or a non-business settlement date is normal operation, not
 * a defect (.notes/ARCHITECTURE.md §6.1).
 */
public interface EnrichmentLink {

    void apply(PaymentInstructionEntity instruction);
}
