-- Phase 6 settlement gateway: one row per dispatch attempt. Committed
-- before the network call that attempt makes -- see Pacs008Assembler /
-- RailDispatcher call sites and .notes/reports/PHASE-6-REPORT.md for why
-- that ordering is the point of this table.

CREATE TABLE core.dispatch_record (
    dispatch_id      UUID PRIMARY KEY,
    instruction_id   UUID NOT NULL REFERENCES core.payment_instruction,
    uetr             UUID NOT NULL,
    rail             TEXT NOT NULL,
    attempt_no       INTEGER NOT NULL CHECK (attempt_no > 0),
    dispatch_state   TEXT NOT NULL CHECK (dispatch_state IN ('PENDING', 'ACKNOWLEDGED', 'TIMED_OUT', 'FAILED')),
    request_payload  BYTEA NOT NULL,
    sent_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at     TIMESTAMPTZ,
    response_status  INTEGER,
    CONSTRAINT uq_dispatch_record_attempt UNIQUE (uetr, attempt_no)
);

CREATE INDEX ON core.dispatch_record (instruction_id);
CREATE INDEX ON core.dispatch_record (dispatch_state) WHERE dispatch_state = 'PENDING';

COMMENT ON TABLE core.dispatch_record IS
    'One row per dispatch attempt to a rail, written and committed before the network call it describes -- not after, not in the same transaction as the call. If the process dies between the commit and the call, the evidence that a dispatch was attempted survives; the alternative is a restart that finds an instruction at ROUTED with no record anything was sent, and redispatching that is a double payment.';

COMMENT ON COLUMN core.dispatch_record.request_payload IS
    'The exact pacs.008 bytes sent for this attempt. When a payment is disputed, the question is what was transmitted, not what the assembler would produce today from current reference data.';
