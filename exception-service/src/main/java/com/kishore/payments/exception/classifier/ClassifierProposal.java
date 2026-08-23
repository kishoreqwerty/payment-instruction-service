package com.kishore.payments.exception.classifier;

import com.kishore.payments.core.domain.Repairability;

/**
 * The model's parsed, structured proposal -- never applied automatically (.notes/ARCHITECTURE.md
 * section 10.1): it pre-fills the operator's form, and the operator accepts, edits, or ignores it.
 * {@code rationale} is shown to the operator verbatim; a proposal a human cannot evaluate gets
 * either rubber-stamped or ignored; both are worse than a proposal with a reason attached.
 */
public record ClassifierProposal(
        String reasonCode, Repairability repairability, String suggestedField, String suggestedValue, double confidence,
        String rationale) {
}
