package com.kishore.payments.processing.refdata;

/** One row of refdata.correspondent: which correspondent bank clears for a given creditor agent, and in what currency. */
public record CorrespondentRelationship(String creditorAgentBic, String correspondentBic, String settlementCurrency) {
}
