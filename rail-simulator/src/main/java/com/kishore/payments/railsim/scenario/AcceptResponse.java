package com.kishore.payments.railsim.scenario;

/**
 * What the simulator does with the inbound HTTP request itself, before any
 * asynchronous confirmation. ACCEPT and REJECT_SYNC are observably
 * identical at the HTTP layer -- both 202 with an empty body, deliberately
 * (see RailController) -- because a real rail never reports final
 * disposition synchronously; REJECT_SYNC only means this rule has already
 * decided the eventual confirmation will be RJCT.
 *
 * <p>REJECT_4XX and ERROR_5XX are different: they are the rail refusing the
 * HTTP request itself, synchronously, with a real 4xx/5xx status -- the two
 * outcomes a dispatching client's error handling actually has to branch on
 * (permanent rejection versus a retryable server error), which nothing
 * above could previously produce for a well-formed pacs.008.
 */
public enum AcceptResponse {
    ACCEPT,
    REJECT_SYNC,
    REJECT_4XX,
    ERROR_5XX,
    TIMEOUT,
    DROP
}
