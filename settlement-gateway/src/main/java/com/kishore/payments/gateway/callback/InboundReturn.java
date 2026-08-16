package com.kishore.payments.gateway.callback;

/** What this gateway needs out of an inbound pacs.004. */
public record InboundReturn(String uetr, String reasonCode) {
}
