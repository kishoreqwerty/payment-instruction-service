package com.kishore.payments.core.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to payments.repaired when exception-service sends an instruction
 * back into the pipeline -- either an approved field repair or a static-data
 * retry (.notes/ARCHITECTURE.md §5.2). Consumed by processing-service's
 * {@code ValidationConsumer}, which re-validates exactly as it would a
 * first-time {@link InstructionReceivedEvent}: the instruction has already
 * re-entered at {@code VALIDATED} by the time this is published (see
 * {@code InstructionStateWriter}'s own two-transition sequence,
 * {@code EXCEPTION -> REPAIRED -> VALIDATED}), so only {@code instructionId}
 * and {@code sequenceNo} are load-bearing -- everything else here is for
 * traceability in a downstream consumer's own logs, not required to act on
 * the event.
 */
public record InstructionRepairedEvent(
        UUID instructionId,
        UUID uetr,
        String endToEndId,
        UUID caseId,
        int sequenceNo,
        OffsetDateTime occurredAt,
        int eventVersion) {

    public static final int CURRENT_VERSION = 1;
}
