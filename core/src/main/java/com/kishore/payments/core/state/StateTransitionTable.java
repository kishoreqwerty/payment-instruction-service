package com.kishore.payments.core.state;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for which state transitions are legal. Backed
 * by an unmodifiable {@link EnumMap}; every transition attempt anywhere in
 * the system is checked against this table.
 */
public final class StateTransitionTable {

    /** One legal transition, from state to state. */
    public record Transition(InstructionState from, InstructionState to) {
    }

    private static final int EXPECTED_LEGAL_TRANSITION_COUNT = 22;

    private static final Map<InstructionState, Set<InstructionState>> TABLE = buildTable();
    private static final Set<Transition> ALL_TRANSITIONS = buildAllTransitions();

    public boolean isLegal(InstructionState from, InstructionState to) {
        return TABLE.get(from).contains(to);
    }

    public Set<InstructionState> legalTargets(InstructionState from) {
        return TABLE.get(from);
    }

    public Set<Transition> allLegalTransitions() {
        return ALL_TRANSITIONS;
    }

    private static Map<InstructionState, Set<InstructionState>> buildTable() {
        Map<InstructionState, Set<InstructionState>> table = new EnumMap<>(InstructionState.class);

        for (InstructionState state : InstructionState.values()) {
            table.put(state, EnumSet.noneOf(InstructionState.class));
        }

        table.get(InstructionState.RECEIVED).add(InstructionState.VALIDATED);
        table.get(InstructionState.RECEIVED).add(InstructionState.EXCEPTION);

        table.get(InstructionState.VALIDATED).add(InstructionState.ENRICHED);
        table.get(InstructionState.VALIDATED).add(InstructionState.EXCEPTION);

        table.get(InstructionState.ENRICHED).add(InstructionState.ROUTED);
        table.get(InstructionState.ENRICHED).add(InstructionState.EXCEPTION);

        table.get(InstructionState.ROUTED).add(InstructionState.SENT);
        // A dispatch timeout does not mean the dispatch failed. SENT_UNCONFIRMED
        // exists to represent "receipt by the rail is unknown", distinct from
        // both success and failure. See .notes/ARCHITECTURE.md §6.4.
        table.get(InstructionState.ROUTED).add(InstructionState.SENT_UNCONFIRMED);
        table.get(InstructionState.ROUTED).add(InstructionState.EXCEPTION);

        table.get(InstructionState.SENT_UNCONFIRMED).add(InstructionState.SENT);
        table.get(InstructionState.SENT_UNCONFIRMED).add(InstructionState.ROUTED);
        table.get(InstructionState.SENT_UNCONFIRMED).add(InstructionState.INVESTIGATION);

        table.get(InstructionState.SENT).add(InstructionState.SETTLED);
        table.get(InstructionState.SENT).add(InstructionState.EXCEPTION);
        table.get(InstructionState.SENT).add(InstructionState.INVESTIGATION);

        table.get(InstructionState.SETTLED).add(InstructionState.RETURNED);

        table.get(InstructionState.EXCEPTION).add(InstructionState.REPAIRED);
        table.get(InstructionState.EXCEPTION).add(InstructionState.REJECTED);
        table.get(InstructionState.EXCEPTION).add(InstructionState.CANCELLED);

        // A repaired instruction re-enters at VALIDATED, not RECEIVED. It has
        // already been persisted, deduplicated and assigned a UETR; re-entering
        // at intake would either duplicate it or trip the idempotency
        // constraint. Re-entering at validation also means the repair is
        // itself re-validated.
        table.get(InstructionState.REPAIRED).add(InstructionState.VALIDATED);

        table.get(InstructionState.INVESTIGATION).add(InstructionState.SENT);
        table.get(InstructionState.INVESTIGATION).add(InstructionState.EXCEPTION);

        // RETURNED, REJECTED, CANCELLED are terminal: no outgoing transitions.

        for (InstructionState state : InstructionState.values()) {
            table.put(state, Collections.unmodifiableSet(table.get(state)));
        }

        return Collections.unmodifiableMap(table);
    }

    private static Set<Transition> buildAllTransitions() {
        Set<Transition> transitions = new LinkedHashSet<>();
        for (InstructionState from : InstructionState.values()) {
            for (InstructionState to : TABLE.get(from)) {
                transitions.add(new Transition(from, to));
            }
        }
        return Collections.unmodifiableSet(transitions);
    }

    static {
        int count = ALL_TRANSITIONS.size();
        if (count != EXPECTED_LEGAL_TRANSITION_COUNT) {
            throw new IllegalStateException(
                    "StateTransitionTable must declare exactly " + EXPECTED_LEGAL_TRANSITION_COUNT
                            + " legal transitions, but found " + count
                            + ". Check the table for a typo or a missing/extra entry.");
        }
    }
}
