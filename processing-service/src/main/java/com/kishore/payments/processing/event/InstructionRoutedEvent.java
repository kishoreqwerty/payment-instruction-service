package com.kishore.payments.processing.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to payments.routed. Richer than {@link InstructionStageEvent}:
 * a future settlement-gateway consumer needs the routing outcome itself
 * (rail, correspondent, nostro account, settlement date) to actually
 * dispatch, not just the fact that routing happened.
 */
public record InstructionRoutedEvent(
        UUID instructionId,
        UUID uetr,
        String endToEndId,
        int sequenceNo,
        OffsetDateTime occurredAt,
        BigDecimal amount,
        String currency,
        String selectedRail,
        String correspondentBic,
        String nostroAccount,
        LocalDate settlementDate,
        int eventVersion) {

    public static final int CURRENT_VERSION = 1;
}
