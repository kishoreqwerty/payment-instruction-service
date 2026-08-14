-- Phase 1 domain core schema.
-- See .notes/ARCHITECTURE.md §3 for the data model rationale and §5 for the
-- lifecycle rationale.

CREATE SCHEMA core;
CREATE SCHEMA intake;

CREATE TYPE core.instruction_state AS ENUM (
    'RECEIVED','VALIDATED','ENRICHED','ROUTED','SENT','SENT_UNCONFIRMED',
    'SETTLED','RETURNED','EXCEPTION','REPAIRED','INVESTIGATION','REJECTED','CANCELLED'
);

-- intake.raw_message: immutable record of exactly what arrived, persisted
-- before parsing. When an instruction is disputed, the question is what the
-- counterparty actually sent, not what the parser made of it.
CREATE TABLE intake.raw_message (
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
CREATE INDEX ON intake.raw_message (received_at);
CREATE INDEX ON intake.raw_message (payload_sha256);

-- core.payment_instruction: current state, one row per instruction.
CREATE TABLE core.payment_instruction (
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

    CONSTRAINT uq_idempotency UNIQUE
        (debtor_account, end_to_end_id, amount, currency, requested_exec_date)
);

CREATE INDEX ON core.payment_instruction (state, created_at);
CREATE INDEX ON core.payment_instruction (creditor_agent_bic);
CREATE INDEX ON core.payment_instruction (settlement_date, selected_rail);

COMMENT ON COLUMN core.payment_instruction.state_version IS
    'Optimistic lock. Every state transition increments it and every update carries WHERE state_version = :expected. A redelivered message that would repeat a transition updates zero rows and is discarded.';

-- core.instruction_event: append-only. This table is the audit trail.
CREATE TABLE core.instruction_event (
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
CREATE INDEX ON core.instruction_event (instruction_id, sequence_no);
CREATE INDEX ON core.instruction_event (occurred_at);
CREATE INDEX ON core.instruction_event (reason_code) WHERE reason_code IS NOT NULL;

COMMENT ON TABLE core.instruction_event IS
    'Append-only. Never updated, never deleted. This is the audit trail.';

-- core.outbox: the transactional outbox.
CREATE TABLE core.outbox (
    outbox_id       BIGSERIAL PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    topic           TEXT NOT NULL,
    partition_key   TEXT NOT NULL,
    headers         JSONB,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX ON core.outbox (published_at, outbox_id) WHERE published_at IS NULL;
