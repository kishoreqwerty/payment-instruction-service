-- Phase 7 ambiguity resolution: one row per instruction that has ever
-- become ambiguous (SENT_UNCONFIRMED). Durable, not in-memory, because
-- multiple settlement-gateway replicas each run AmbiguityResolver --
-- in-memory counts would disagree by replica and reset on restart, and
-- the two-consecutive-UNKNOWN rule the resolver depends on requires a
-- single, durable count. The row persists for the instruction's lifetime,
-- not deleted when one ambiguity episode resolves, so redispatch_count
-- keeps accumulating correctly if the instruction becomes ambiguous again
-- after a redispatch.

CREATE TABLE core.reconciliation_state (
    instruction_id                    UUID PRIMARY KEY REFERENCES core.payment_instruction,
    consecutive_unknown_count         INTEGER NOT NULL DEFAULT 0,
    consecutive_inconclusive_count    INTEGER NOT NULL DEFAULT 0,
    redispatch_count                  INTEGER NOT NULL DEFAULT 0,
    last_outcome                      TEXT,
    last_checked_at                   TIMESTAMPTZ,
    updated_at                        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE core.reconciliation_state IS
    'Durable counters an AmbiguityResolver needs across scheduler cycles and replicas: how many consecutive UNKNOWN or inconclusive rail-status observations an instruction has accrued, and how many times it has been redispatched. See .notes/reports/PHASE-7-REPORT.md.';

COMMENT ON COLUMN core.reconciliation_state.consecutive_unknown_count IS
    'Resets to 0 on any observation that is not UNKNOWN (KNOWN or inconclusive) -- a non-consecutive UNKNOWN does not count toward the two-observation redispatch threshold.';

COMMENT ON COLUMN core.reconciliation_state.consecutive_inconclusive_count IS
    'Resets to 0 on any successful query (KNOWN or UNKNOWN) -- only an unbroken run of query failures/timeouts counts toward the inconclusive-window threshold.';

COMMENT ON COLUMN core.reconciliation_state.redispatch_count IS
    'Never reset -- a total across the instruction''s whole lifetime, checked against the redispatch cap before every redispatch.';
