package com.kishore.payments.gateway.callback;

/** What this gateway needs out of an inbound pacs.002. */
public record InboundConfirmation(String uetr, String txStatus, String reasonCode) {
}
