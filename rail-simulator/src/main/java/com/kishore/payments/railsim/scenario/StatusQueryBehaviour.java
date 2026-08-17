package com.kishore.payments.railsim.scenario;

/**
 * How this rail answers {@code GET /rail/{railId}/payments/{uetr}} --
 * separate from {@link AcceptResponse}/{@link ConfirmationType}, which
 * govern the dispatch and confirmation paths. A real reconciliation query
 * is a second, independent interaction with the rail, and needs its own
 * failure modes to be testable: a rail that hasn't indexed a payment yet
 * ({@code UNKNOWN_THEN_KNOWN}), one whose status endpoint is down ({@code
 * ALWAYS_ERROR}), and one that's merely slow ({@code SLOW}) are three
 * different problems a reconciliation client has to handle differently.
 */
public enum StatusQueryBehaviour {
    NORMAL,
    UNKNOWN_THEN_KNOWN,
    ALWAYS_ERROR,
    SLOW
}
