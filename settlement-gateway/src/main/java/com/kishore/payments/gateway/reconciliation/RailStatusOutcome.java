package com.kishore.payments.gateway.reconciliation;

/**
 * What {@link RailStatusClient} learned from {@code GET
 * /rail/{railId}/payments/{uetr}} -- the three worlds a timed-out dispatch
 * leaves open (.notes/ARCHITECTURE.md §6.4): the rail never received it
 * ({@link Unknown}), the rail has it on file ({@link Known}, disposition in
 * {@code railStatus}), or the question itself couldn't be answered
 * ({@link QueryFailed}, which must never be treated as either of the other
 * two).
 */
public sealed interface RailStatusOutcome {

    /** The rail has this UETR on file. {@code railStatus} is null (accepted, no confirmation yet), ACSP, ACSC or RJCT. */
    record Known(String railStatus, String reasonCode) implements RailStatusOutcome {
    }

    /** The rail has no record of this UETR. Requires two consecutive observations before anything acts on it. */
    record Unknown() implements RailStatusOutcome {
    }

    /** The query itself failed or timed out -- learned nothing, positive or negative, about receipt. */
    record QueryFailed(String detail) implements RailStatusOutcome {
    }
}
