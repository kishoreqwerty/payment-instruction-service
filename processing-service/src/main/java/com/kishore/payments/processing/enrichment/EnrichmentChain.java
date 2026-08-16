package com.kishore.payments.processing.enrichment;

import com.kishore.payments.core.instruction.PaymentInstructionEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link EnrichmentLink} in order, mutating the instruction in
 * place. Order is significant here, unlike {@link
 * com.kishore.payments.processing.validation.ValidationChain}: nostro
 * lookup depends on the correspondent link having already run, and the
 * business-day roll depends on the cutoff link having already set a
 * starting settlement date. Order is fixed via {@code @Order} on each link
 * (Spring sorts an injected {@code List<T>} by it) rather than by
 * declaration order in this class, so the dependency is visible on the link
 * itself.
 */
@Component
public class EnrichmentChain {

    private final List<EnrichmentLink> links;

    public EnrichmentChain(List<EnrichmentLink> links) {
        this.links = List.copyOf(links);
    }

    public void enrich(PaymentInstructionEntity instruction) {
        for (EnrichmentLink link : links) {
            link.apply(instruction);
        }
    }
}
