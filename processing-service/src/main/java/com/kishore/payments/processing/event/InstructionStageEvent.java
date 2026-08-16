package com.kishore.payments.processing.event;

import com.kishore.payments.core.state.InstructionState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to payments.validated and payments.enriched: a plain "this
 * instruction advanced to state X" signal, shaped identically to {@link
 * com.kishore.payments.core.event.InstructionReceivedEvent} and, like it,
 * carrying no debtor/creditor name or full account number. The next stage's
 * consumer only needs identity and enough to keep processing; anything more
 * it loads from the database by instructionId.
 */
public record InstructionStageEvent(
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
