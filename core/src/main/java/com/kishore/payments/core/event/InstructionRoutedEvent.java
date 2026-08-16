package com.kishore.payments.core.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to payments.routed. Richer than {@link InstructionReceivedEvent}:
 * a settlement-gateway consumer needs the routing outcome itself (rail,
 * correspondent, nostro account, settlement date) to actually dispatch, not
 * just the fact that routing happened. Lives in core, not processing-service,
 * because a second real service (settlement-gateway, Phase 6) now consumes
 * it -- the same reason {@link InstructionReceivedEvent} lives here rather
 * than in intake-service.
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
