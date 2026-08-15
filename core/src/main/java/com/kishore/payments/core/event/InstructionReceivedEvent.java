package com.kishore.payments.core.event;

import com.kishore.payments.core.state.InstructionState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to payments.received when an instruction is recorded. A Kafka
 * topic is a copy of the data with its own retention and its own access
 * controls, so it deliberately does not carry {@code debtorName},
 * {@code creditorName}, or full account numbers. A consumer needs enough to
 * route and validate; anything more sensitive it can load from the database
 * by {@code instructionId} when it actually needs it.
 *
 * @param eventVersion present from the first release so a future schema
 *                     change is additive for consumers rather than requiring
 *                     every one of them to add a null-check retroactively
 */
public record InstructionReceivedEvent(
        UUID instructionId,
        UUID uetr,
        String endToEndId,
        InstructionState state,
        int sequenceNo,
        OffsetDateTime occurredAt,
        BigDecimal amount,
        String currency,
        String debtorAgentBic,
        String creditorAgentBic,
        LocalDate requestedExecutionDate,
        int eventVersion) {

    public static final int CURRENT_VERSION = 1;
}
