package com.kishore.payments.railsim.dispatch;

import java.math.BigDecimal;

/** What this simulator needs from an inbound pacs.008 -- enough to record it, match scenario rules, and build a correlated pacs.002/pacs.004 later. */
public record InboundPayment(
        String uetr,
        String messageId,
        String endToEndId,
        String instructionId,
        String transactionId,
        BigDecimal amount,
        String currency,
        String creditorAccount,
        String creditorAgentBic,
        String debtorAgentBic) {
}
