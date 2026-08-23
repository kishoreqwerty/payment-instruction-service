package com.kishore.payments.exception.classifier;

import com.kishore.payments.exception.cases.ExceptionCaseRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of recording a classifier proposal, split out of {@link
 * ClassifierInvoker} for the same reason {@code ExceptionCaseOpener} is its own bean rather than a
 * method on {@code ExceptionCaseService}: {@code @Transactional} only takes effect through
 * Spring's proxy, which a same-bean {@code this.} call bypasses entirely. {@link
 * ClassifierInvoker} runs this on its own executor thread, well after the case-opening
 * transaction has already committed, so this is always a fresh, independent transaction.
 */
@Component
class ClassifierProposalWriter {

    private static final Logger log = LoggerFactory.getLogger(ClassifierProposalWriter.class);

    private final ExceptionCaseRepository cases;

    ClassifierProposalWriter(ExceptionCaseRepository cases) {
        this.cases = cases;
    }

    @Transactional
    void apply(UUID caseId, ClassifierProposal proposal) {
        cases.findById(caseId).ifPresentOrElse(
                entity -> {
                    entity.setClassifierCode(proposal.reasonCode());
                    entity.setClassifierRepairability(proposal.repairability() == null ? null : proposal.repairability().name());
                    entity.setClassifierConf(proposal.confidence());
                    entity.setClassifierSuggestedField(proposal.suggestedField());
                    entity.setClassifierSuggestedValue(proposal.suggestedValue());
                    entity.setClassifierRationale(proposal.rationale());
                    cases.save(entity);
                },
                // The case could, in principle, already be resolved by the time a slow
                // classifier call comes back (an operator moving fast, or a static-data retry
                // closing it) -- not an error, just a proposal that arrived too late to matter.
                () -> log.info("Case {} no longer present when classifier proposal arrived; discarding", caseId));
    }
}
