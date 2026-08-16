package com.kishore.payments.railsim.scenario;

import com.kishore.payments.railsim.dispatch.InboundPayment;
import java.util.List;

/**
 * A whole scenario for one rail: its defaults, an ordered list of override
 * rules (first match wins -- see {@link #resolve}), and where to POST
 * confirmations. {@code callbackUrl} is optional here and falls back to
 * this service's own {@code railsim.default-callback-url} property when
 * absent, so a scenario file doesn't have to repeat a URL that's normally a
 * deployment-level fact, while a test loading a scenario via {@code POST
 * /rail/{railId}/scenario} can still point callbacks at a URL it controls.
 */
public record ScenarioConfig(String rail, BehaviorSpec defaults, List<ScenarioRule> rules, String callbackUrl) {

    public ScenarioConfig {
        rules = List.copyOf(rules);
    }

    /** First matching rule's overrides merged onto the defaults; the defaults themselves if nothing matches. */
    public BehaviorSpec resolve(InboundPayment payment, long requestOrdinal) {
        for (ScenarioRule rule : rules) {
            if (rule.match().matches(payment, requestOrdinal)) {
                return rule.overrides().mergeOnto(defaults);
            }
        }
        return defaults;
    }
}
