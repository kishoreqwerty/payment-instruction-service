package com.kishore.payments.core.state;

import java.util.Set;

/**
 * The lifecycle states of a payment instruction, in the order they are
 * declared in {@code core.instruction_state} (see V1__core_schema.sql).
 */
public enum InstructionState {
    RECEIVED,
    VALIDATED,
    ENRICHED,
    ROUTED,
    SENT,
    SENT_UNCONFIRMED,
    SETTLED,
    RETURNED,
    EXCEPTION,
    REPAIRED,
    INVESTIGATION,
    REJECTED,
    CANCELLED;

    private static final Set<InstructionState> TERMINAL = Set.of(REJECTED, CANCELLED, RETURNED);

    /**
     * SETTLED is deliberately not terminal: a pacs.004 payment return can
     * arrive after settlement and unwind it.
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
