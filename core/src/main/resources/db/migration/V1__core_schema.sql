-- Phase 1 domain core schema.
-- See .notes/ARCHITECTURE.md §3 for the data model rationale and §5 for the
-- lifecycle rationale.
--
-- Idempotent throughout (IF NOT EXISTS on every schema/table/index, a
-- guarded DO block for the enum type Postgres has no IF NOT EXISTS form
-- for) since this migration is a physically shared foundation applied
-- independently by every service that depends on `core`: each service now
-- tracks its own Flyway history in its own schema (see each service's own
-- application.yml, spring.flyway.schemas), so whichever service starts
-- first against a blank database is the one that actually creates these
-- objects, and every other service's own, separate migration run must be
-- able to reach the same end state without erroring on "already exists."
-- See .notes/reports/CROSS-SERVICE-INTEGRATION-DEFECTS.md for the defect
-- this replaces (one shared flyway_schema_history table, one V2 version
-- number claimed by three different services' own first migration).

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS intake;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_type WHERE typname = 'instruction_state' AND typnamespace = 'core'::regnamespace
    ) THEN
        CREATE TYPE core.instruction_state AS ENUM (
            'RECEIVED','VALIDATED','ENRICHED','ROUTED','SENT','SENT_UNCONFIRMED',
            'SETTLED','RETURNED','EXCEPTION','REPAIRED','INVESTIGATION','REJECTED','CANCELLED'
        );
    END IF;
END
$$;

-- intake.raw_message: immutable record of exactly what arrived, persisted
-- before parsing. When an instruction is disputed, the question is what the
-- counterparty actually sent, not what the parser made of it.
CREATE TABLE IF NOT EXISTS intake.raw_message (
    raw_message_id      UUID PRIMARY KEY,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    source_channel      TEXT NOT NULL,
    source_identifier   TEXT,
    content_type        TEXT NOT NULL,
    payload             BYTEA NOT NULL,
    payload_sha256      BYTEA NOT NULL,
    schema_valid        BOOLEAN NOT NULL,
    schema_errors       JSONB
);
CREATE INDEX IF NOT EXISTS idx_raw_message_received_at ON intake.raw_message (received_at);
CREATE INDEX IF NOT EXISTS idx_raw_message_payload_sha256 ON intake.raw_message (payload_sha256);

-- core.payment_instruction: current state, one row per instruction.
CREATE TABLE IF NOT EXISTS core.payment_instruction (
    instruction_id      UUID PRIMARY KEY,
    raw_message_id      UUID NOT NULL,
    uetr                UUID NOT NULL UNIQUE,
    end_to_end_id       TEXT NOT NULL,
    instruction_id_ext  TEXT,
    state               core.instruction_state NOT NULL,
    state_version       INTEGER NOT NULL DEFAULT 1,

    debtor_name          TEXT NOT NULL,
    debtor_account       TEXT NOT NULL,
    debtor_agent_bic     TEXT NOT NULL,
    creditor_name        TEXT NOT NULL,
    creditor_account     TEXT NOT NULL,
    creditor_agent_bic   TEXT NOT NULL,

    amount               NUMERIC(18,5) NOT NULL CHECK (amount > 0),
    currency             CHAR(3) NOT NULL,
    charge_bearer        TEXT,
    requested_exec_date  DATE NOT NULL,
    value_date           DATE,
    settlement_date      DATE,

    selected_rail        TEXT,
    correspondent_bic    TEXT,
    nostro_account       TEXT,
    refdata_version      BIGINT,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_reference UNIQUE (debtor_account, end_to_end_id)
);

CREATE INDEX IF NOT EXISTS idx_payment_instruction_state_created_at ON core.payment_instruction (state, created_at);
CREATE INDEX IF NOT EXISTS idx_payment_instruction_creditor_agent_bic ON core.payment_instruction (creditor_agent_bic);
CREATE INDEX IF NOT EXISTS idx_payment_instruction_settlement_date_rail ON core.payment_instruction (settlement_date, selected_rail);

COMMENT ON COLUMN core.payment_instruction.state_version IS
    'Optimistic lock. Every state transition increments it and every update carries WHERE state_version = :expected. A redelivered message that would repeat a transition updates zero rows and is discarded.';

COMMENT ON CONSTRAINT uq_reference ON core.payment_instruction IS
    'EndToEndId is the senders own unique reference for a payment, scoped to the debtor account it was sent from. A collision on (debtor_account, end_to_end_id) means either a retry of the same payment or a sender defect (the same reference reused for a different payment) -- the two are distinguished by comparing the rest of the content, not by widening this key. Widening it (e.g. to include creditor_account) would let two different payments share one reference, which breaks reconciliation on the senders side instead of surfacing the error.';

-- core.instruction_event: append-only. This table is the audit trail.
CREATE TABLE IF NOT EXISTS core.instruction_event (
    event_id        BIGSERIAL,
    instruction_id  UUID NOT NULL REFERENCES core.payment_instruction,
    sequence_no     INTEGER NOT NULL,
    from_state      core.instruction_state,
    to_state        core.instruction_state NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_type      TEXT NOT NULL,
    actor_id        TEXT NOT NULL,
    reason_code     TEXT,
    reason_detail   TEXT,
    payload         JSONB,
    CONSTRAINT pk_instruction_event PRIMARY KEY (event_id),
    CONSTRAINT uq_instruction_event_audit UNIQUE (instruction_id, sequence_no)
);
CREATE INDEX IF NOT EXISTS idx_instruction_event_instruction_sequence ON core.instruction_event (instruction_id, sequence_no);
CREATE INDEX IF NOT EXISTS idx_instruction_event_occurred_at ON core.instruction_event (occurred_at);
CREATE INDEX IF NOT EXISTS idx_instruction_event_reason_code ON core.instruction_event (reason_code) WHERE reason_code IS NOT NULL;

COMMENT ON TABLE core.instruction_event IS
    'Append-only. Never updated, never deleted. This is the audit trail.';

-- core.outbox: the transactional outbox.
CREATE TABLE IF NOT EXISTS core.outbox (
    outbox_id       BIGSERIAL PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    topic           TEXT NOT NULL,
    partition_key   TEXT NOT NULL,
    headers         JSONB,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON core.outbox (published_at, outbox_id) WHERE published_at IS NULL;
