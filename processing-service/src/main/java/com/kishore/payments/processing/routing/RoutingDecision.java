package com.kishore.payments.processing.routing;

import java.math.BigDecimal;
import java.util.List;

/**
 * The inputs a routing decision was made from, not just its outcome --
 * {@code selectedRail} alone cannot answer "why did this go to Fedwire and
 * not ACH", but this record can: the currency and amount that were
 * evaluated, whether the payment was treated as urgent, which rails were
 * even eligible before one was chosen, and the correspondent it will settle
 * through.
 */
public record RoutingDecision(
        String currency, BigDecimal amount, boolean urgent, String correspondentBic, List<String> eligibleRails, String selectedRail) {
}
