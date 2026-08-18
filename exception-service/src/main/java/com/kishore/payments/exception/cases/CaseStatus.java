package com.kishore.payments.exception.cases;

import java.util.Set;

/** The case's own workflow position, distinct from {@link Resolution} (the reason a terminal status was reached). */
public enum CaseStatus {
    OPEN,
    ASSIGNED,
    PENDING_APPROVAL,
    RESOLVED,
    REJECTED;

    private static final Set<CaseStatus> TERMINAL = Set.of(RESOLVED, REJECTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
