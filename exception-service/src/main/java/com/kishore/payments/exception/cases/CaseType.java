package com.kishore.payments.exception.cases;

/**
 * Distinguishes a normal repairable business failure from an INVESTIGATION
 * case, per the phase brief's own instruction: "these are different from
 * repairable business failures ... model that distinction explicitly rather
 * than forcing it into the field-repair shape." An INVESTIGATION case has no
 * field to fix -- the payment's content was never in question, only whether
 * the rail received it -- so its only valid actions are confirm-sent and
 * reject, never propose-repair or retry.
 */
public enum CaseType {
    BUSINESS_FAILURE,
    INVESTIGATION
}
