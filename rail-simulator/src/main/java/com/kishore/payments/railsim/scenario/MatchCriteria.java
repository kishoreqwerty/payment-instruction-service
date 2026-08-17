package com.kishore.payments.railsim.scenario;

import com.kishore.payments.railsim.dispatch.InboundPayment;
import java.math.BigDecimal;

/**
 * Every non-null field must hold for the rule to match (AND semantics); a
 * completely empty match block (every field null, {@code always} included)
 * matches everything, the same as an explicit {@code always: true} would.
 *
 * @param everyNth matches when {@code requestOrdinal} (the 1-based count of
 *                 requests this rail has received since its scenario was
 *                 last loaded) is an exact multiple -- the 20th, 40th,
 *                 60th... request for {@code everyNth: 20}, never a nearby one.
 *                 A whole-rail counter: unsuitable for distinguishing a
 *                 redispatch from its original once many payments interleave
 *                 on the same rail concurrently -- {@code deliveryAttemptAtLeast}
 *                 exists for that.
 * @param deliveryAttemptAtLeast matches when this is at least the Nth time
 *                 *this exact UETR* has been POSTed to this rail (1 for an
 *                 original delivery, 2 for its first redispatch, and so on),
 *                 regardless of how many other payments' requests interleave
 *                 with it. A redispatch carries the same content as its
 *                 original by design (.notes/ARCHITECTURE.md §6.4), so
 *                 nothing else in a match block can tell them apart --
 *                 this is the one dimension that can, because it's scoped
 *                 to the UETR, not the rail's overall request stream.
 */
public record MatchCriteria(
        BigDecimal amountGreaterThan,
        BigDecimal amountLessThan,
        String currency,
        String creditorAccountEndsWith,
        String debtorAgentBic,
        Integer everyNth,
        Integer deliveryAttemptAtLeast,
        Boolean always) {

    public boolean matches(InboundPayment payment, long requestOrdinal, int deliveryAttempt) {
        // A standalone override, not combined with the other fields: written
        // for a deliberate catch-all rule (always: true) or a deliberately
        // disabled one (always: false), not alongside other criteria.
        if (always != null) {
            return always;
        }
        if (amountGreaterThan != null && payment.amount().compareTo(amountGreaterThan) <= 0) {
            return false;
        }
        if (amountLessThan != null && payment.amount().compareTo(amountLessThan) >= 0) {
            return false;
        }
        if (currency != null && !currency.equalsIgnoreCase(payment.currency())) {
            return false;
        }
        if (creditorAccountEndsWith != null && !payment.creditorAccount().endsWith(creditorAccountEndsWith)) {
            return false;
        }
        if (debtorAgentBic != null && !debtorAgentBic.equalsIgnoreCase(payment.debtorAgentBic())) {
            return false;
        }
        if (everyNth != null && everyNth > 0 && requestOrdinal % everyNth != 0) {
            return false;
        }
        if (deliveryAttemptAtLeast != null && deliveryAttempt < deliveryAttemptAtLeast) {
            return false;
        }
        return true;
    }
}
