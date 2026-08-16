package com.kishore.payments.railsim.scenario;

/** What pacs.002 status (if any) the simulator sends asynchronously after accepting a payment. */
public enum ConfirmationType {
    ACSC,
    ACSP,
    RJCT,
    NONE
}
