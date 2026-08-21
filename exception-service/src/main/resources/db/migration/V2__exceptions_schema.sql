-- Phase 8: exception case lifecycle. See .notes/ARCHITECTURE.md §3.2 for the
-- DDL this is based on, and .notes/reports/PHASE-8-REPORT.md for what was
-- added beyond that sketch and why.

-- IF NOT EXISTS: Flyway itself pre-creates this schema (spring.flyway.schemas
-- in application.yml, so this service's own flyway_schema_history lives
-- here rather than in the shared `public` default -- see that file's own
-- comment) before running any migration, specifically so it has somewhere
-- to put the history table. Without IF NOT EXISTS here, this statement
-- collides with Flyway's own prior creation of the same schema.
CREATE SCHEMA IF NOT EXISTS exceptions;

-- case_type and status are both additions beyond .notes/ARCHITECTURE.md's own
-- DDL sketch, which only shows `resolution` (the terminal disposition).
-- case_type distinguishes a normal repairable business failure from an
-- INVESTIGATION case (the phase brief's own instruction: "model that
-- distinction explicitly rather than forcing it into the field-repair
-- shape") -- an investigation case has no field to fix, only a rail-receipt
-- question a human answers with confirm-sent/reject, and mixing the two
-- shapes into one set of actions would make an operator's UI have to guess
-- which actions are even valid for a given case. status is the case's own
-- workflow position (OPEN -> ASSIGNED -> PENDING_APPROVAL -> RESOLVED, or
-- OPEN -> REJECTED); resolution is the terminal disposition reason once
-- status reaches RESOLVED or REJECTED, not a duplicate of status itself --
-- a RESOLVED case's resolution says *why* (REPAIRED, AUTO_RETRIED, ...).
CREATE TABLE exceptions.exception_case (
    case_id             UUID PRIMARY KEY,
    instruction_id      UUID NOT NULL REFERENCES core.payment_instruction,
    opened_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at           TIMESTAMPTZ,

    case_type           TEXT NOT NULL CHECK (case_type IN ('BUSINESS_FAILURE', 'INVESTIGATION')),
    status              TEXT NOT NULL CHECK (status IN ('OPEN', 'ASSIGNED', 'PENDING_APPROVAL', 'RESOLVED', 'REJECTED')),

    failure_stage       TEXT NOT NULL
                             CHECK (failure_stage IN ('INTAKE', 'VALIDATION', 'ENRICHMENT', 'ROUTING', 'DISPATCH', 'CONFIRMATION', 'RECONCILIATION')),
    reason_code         TEXT,
    reason_detail       TEXT,
    repairability       TEXT NOT NULL CHECK (repairability IN ('REPAIRABLE', 'STATIC_DATA', 'TRANSIENT', 'UNREPAIRABLE')),

    assigned_to         TEXT,
    resolution          TEXT CHECK (resolution IN ('REPAIRED', 'REJECTED', 'CANCELLED', 'AUTO_RETRIED', 'CONFIRMED_SENT')),
    repair_attempts     INTEGER NOT NULL DEFAULT 0,

    -- Free text an operator records when resolving an INVESTIGATION case
    -- (confirm-sent or reject): the one place a human overrides the
    -- system's own conclusion on no system evidence, so the record of why
    -- must exist (phase brief §6).
    justification       TEXT,

    -- Phase 11's LLM-assisted classifier (.notes/ARCHITECTURE.md §10) writes
    -- these; nothing in Phase 8 does. Columns exist now, matching
    -- .notes/ARCHITECTURE.md §3.2's own DDL, so Phase 11 is additive to this
    -- schema rather than a migration that touches every existing row.
    classifier_code     TEXT,
    classifier_conf     NUMERIC(4,3),
    classifier_accepted BOOLEAN,

    CONSTRAINT uq_case_instruction_opened UNIQUE (instruction_id, opened_at)
);

-- The UNIQUE above does not enforce "one open case per instruction at a
-- time" -- two cases for the same instruction with different opened_at
-- values satisfy it trivially. This partial unique index is what actually
-- enforces it: at most one row per instruction_id where status has not yet
-- reached a terminal value. A concurrent second exception event for an
-- instruction with an open case must append to it, not race this index into
-- an exception -- see ExceptionCaseService, which inserts optimistically
-- and falls back to append-to-existing on a violation here, the same
-- insert-first pattern intake-service's own uq_reference handling uses.
CREATE UNIQUE INDEX uq_one_open_case_per_instruction ON exceptions.exception_case (instruction_id)
    WHERE status NOT IN ('RESOLVED', 'REJECTED');

CREATE INDEX ON exceptions.exception_case (status, opened_at);
CREATE INDEX ON exceptions.exception_case (failure_stage);
CREATE INDEX ON exceptions.exception_case (reason_code) WHERE reason_code IS NOT NULL;
CREATE INDEX ON exceptions.exception_case (assigned_to) WHERE assigned_to IS NOT NULL;

COMMENT ON INDEX exceptions.uq_one_open_case_per_instruction IS
    'Enforces one open case per instruction. An instruction that fails at validation, is repaired, and later fails at dispatch gets a second case here (the first is RESOLVED by then), not a reopening of the first -- see PHASE-8-REPORT.md section 5 for why those are treated as separate operational stories.';

-- exceptions.repair_action -- maker-checker.
CREATE TABLE exceptions.repair_action (
    action_id       UUID PRIMARY KEY,
    case_id         UUID NOT NULL REFERENCES exceptions.exception_case,
    field_path      TEXT NOT NULL,
    old_value       TEXT,
    new_value       TEXT NOT NULL,
    proposed_by     TEXT NOT NULL,
    proposed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by     TEXT,
    approved_at     TIMESTAMPTZ,
    CONSTRAINT ck_maker_checker CHECK (approved_by IS NULL OR approved_by <> proposed_by)
);

CREATE INDEX ON exceptions.repair_action (case_id);

COMMENT ON CONSTRAINT ck_maker_checker ON exceptions.repair_action IS
    'The person who proposed a change to a payment cannot be the person who approves it. Enforced here independently of the application-layer check in ExceptionCaseService -- an authorisation control that lives only in application code fails open the day someone adds a new code path.';
