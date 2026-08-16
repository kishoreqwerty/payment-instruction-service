package com.kishore.payments.railsim.scenario;

/** One rule: a match condition, and the behavior overrides to apply when it matches. Evaluated in list order; first match wins. */
public record ScenarioRule(MatchCriteria match, BehaviorSpec overrides) {
}
