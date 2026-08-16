package com.kishore.payments.railsim.scenario;

/**
 * What the simulator does with the inbound HTTP request itself, before any
 * asynchronous confirmation. ACCEPT and REJECT_SYNC are observably
 * identical at the HTTP layer -- both 202 with an empty body, deliberately
 * (see RailController) -- because a real rail never reports final
 * disposition synchronously; REJECT_SYNC only means this rule has already
 * decided the eventual confirmation will be RJCT.
 */
public enum AcceptResponse {
    ACCEPT,
    REJECT_SYNC,
    TIMEOUT,
    DROP
}
