package com.kishore.payments.exception.cases;

/**
 * The terminal disposition of a resolved or rejected case -- why {@link
 * CaseStatus} reached a terminal value, not just that it did.
 *
 * <p>{@code CONFIRMED_SENT} is not in .notes/ARCHITECTURE.md §3.2's own DDL
 * sketch, which predates the investigation-resolution endpoints this phase
 * adds. {@code REJECTED} already fit investigation-reject without change
 * (the phase brief's own §6: "transitions INVESTIGATION -> EXCEPTION, then
 * the normal reject path"), but nothing in the original four values means
 * "an operator confirmed out of band that the rail holds the payment" --
 * {@code REPAIRED} would misdescribe it as a field fix, and {@code
 * AUTO_RETRIED} would misdescribe it as an automated retry. Added rather
 * than overloading an existing value to mean two different things.
 */
public enum Resolution {
    REPAIRED,
    REJECTED,
    CANCELLED,
    AUTO_RETRIED,
    CONFIRMED_SENT
}
