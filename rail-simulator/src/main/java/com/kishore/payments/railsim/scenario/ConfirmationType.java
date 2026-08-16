package com.kishore.payments.railsim.scenario;

/**
 * What pacs.002 status (if any) the simulator sends asynchronously after
 * accepting a payment. RETURN_AFTER_SETTLEMENT is not a pacs.002 status in
 * its own right -- it means "send ACSC, then also send a pacs.004 payment
 * return after a further delay," the post-settlement-return path from
 * .notes/ARCHITECTURE.md §6.1's failure taxonomy.
 */
public enum ConfirmationType {
    ACSC,
    ACSP,
    RJCT,
    RETURN_AFTER_SETTLEMENT,
    NONE
}
