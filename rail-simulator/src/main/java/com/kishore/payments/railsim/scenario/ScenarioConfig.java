package com.kishore.payments.railsim.scenario;

import com.kishore.payments.railsim.dispatch.InboundPayment;
import java.util.List;

/**
 * A whole scenario for one rail: its defaults, an ordered list of override
 * rules (first match wins -- see {@link #resolve}), and where to POST
 * confirmations and returns. A real rail sends status reports (pacs.002)
 * and returns (pacs.004) to different endpoints, since they're different
 * message types with different consumers on the receiving end -- one
 * shared {@code callbackUrl} could not express that (see
 * .notes/reports/PHASE-6-REPORT.md §6 for the gap this caused). Both URLs
 * are optional here and fall back to this service's own {@code
 * railsim.default-callback-url} property when absent -- {@code
 * returnCallbackUrl} falls back to {@code statusCallbackUrl} first (see
 * {@link #returnCallbackUrl()}), so a scenario that only configures one URL
 * keeps working for both message types, exactly as before this split.
 *
 * <p>{@code statusQueryBehaviour} and its parameters govern {@code GET
 * /rail/{railId}/payments/{uetr}} -- a second, independent interaction with
 * the rail (reconciliation), not the dispatch/confirmation path {@link
 * BehaviorSpec} governs. They are scenario-level, not per-rule: a status
 * query happens after dispatch, sometimes for a UETR the rail never
 * recorded at all, so there is no inbound payment left to match rules
 * against by the time one arrives.
 */
public record ScenarioConfig(
        String rail,
        BehaviorSpec defaults,
        List<ScenarioRule> rules,
        String statusCallbackUrl,
        String returnCallbackUrl,
        StatusQueryBehaviour statusQueryBehaviour,
        Integer statusQueryUnknownCount,
        Long statusQuerySlowDelayMs) {

    public ScenarioConfig {
        rules = List.copyOf(rules);
    }

    /** Falls back to {@link #statusCallbackUrl} when not explicitly set, so existing single-URL scenarios still work for returns. */
    public String returnCallbackUrl() {
        return returnCallbackUrl != null ? returnCallbackUrl : statusCallbackUrl;
    }

    /** {@link StatusQueryBehaviour#NORMAL} unless a scenario says otherwise. */
    public StatusQueryBehaviour statusQueryBehaviour() {
        return statusQueryBehaviour != null ? statusQueryBehaviour : StatusQueryBehaviour.NORMAL;
    }

    /** How many leading status queries {@link StatusQueryBehaviour#UNKNOWN_THEN_KNOWN} answers UNKNOWN before it starts answering truthfully. Defaults to 1. */
    public int resolvedStatusQueryUnknownCount() {
        return statusQueryUnknownCount != null ? statusQueryUnknownCount : 1;
    }

    /** How long {@link StatusQueryBehaviour#SLOW} holds a status query before answering. Defaults to 0. */
    public long resolvedStatusQuerySlowDelayMs() {
        return statusQuerySlowDelayMs != null ? statusQuerySlowDelayMs : 0L;
    }

    /** First matching rule's overrides merged onto the defaults; the defaults themselves if nothing matches. */
    public BehaviorSpec resolve(InboundPayment payment, long requestOrdinal, int deliveryAttempt) {
        for (ScenarioRule rule : rules) {
            if (rule.match().matches(payment, requestOrdinal, deliveryAttempt)) {
                return rule.overrides().mergeOnto(defaults);
            }
        }
        return defaults;
    }
}
