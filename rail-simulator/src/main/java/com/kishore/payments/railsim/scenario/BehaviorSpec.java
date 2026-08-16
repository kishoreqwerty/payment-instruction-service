package com.kishore.payments.railsim.scenario;

/**
 * Every field is boxed/nullable because the same shape is used two ways:
 * the {@code default:} block (expected fully populated, checked at load
 * time -- see ScenarioLoader) and a rule's own overrides (expected
 * partially populated -- only what that rule changes from the default).
 * {@link #mergeOnto} is how a rule's partial overrides become a fully
 * resolved behavior for one request.
 */
public record BehaviorSpec(
        AcceptResponse acceptResponse,
        Long acceptDelayMs,
        ConfirmationType confirmation,
        Long confirmationDelayMs,
        String rejectReasonCode,
        Long timeoutHoldMs,
        Boolean recordBeforeTimeout) {

    public BehaviorSpec mergeOnto(BehaviorSpec fallback) {
        return new BehaviorSpec(
                acceptResponse != null ? acceptResponse : fallback.acceptResponse(),
                acceptDelayMs != null ? acceptDelayMs : fallback.acceptDelayMs(),
                confirmation != null ? confirmation : fallback.confirmation(),
                confirmationDelayMs != null ? confirmationDelayMs : fallback.confirmationDelayMs(),
                rejectReasonCode != null ? rejectReasonCode : fallback.rejectReasonCode(),
                timeoutHoldMs != null ? timeoutHoldMs : fallback.timeoutHoldMs(),
                recordBeforeTimeout != null ? recordBeforeTimeout : fallback.recordBeforeTimeout());
    }
}
