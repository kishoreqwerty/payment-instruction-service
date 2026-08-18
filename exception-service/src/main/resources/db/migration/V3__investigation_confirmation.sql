-- Investigation confirm-sent becomes a two-step maker-checker action,
-- mirroring exceptions.repair_action: confirm-sent asserts a payment
-- reached the rail on out-of-band evidence alone, with no automated
-- re-validation to catch a wrong call the way a field repair has -- a
-- stronger claim with weaker verification, so it gets the same control a
-- field repair does, not the single-checker-only shape reject keeps
-- (a wrongly-rejected payment is recoverable by re-origination; a
-- wrongly-confirmed one is not). See .notes/reports/PHASE-8-REPORT.md
-- section 5.

CREATE TABLE exceptions.investigation_confirmation (
    confirmation_id  UUID PRIMARY KEY,
    case_id          UUID NOT NULL REFERENCES exceptions.exception_case,
    justification    TEXT NOT NULL,
    proposed_by      TEXT NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by      TEXT,
    approved_at      TIMESTAMPTZ,
    CONSTRAINT ck_investigation_maker_checker CHECK (approved_by IS NULL OR approved_by <> proposed_by)
);

CREATE INDEX ON exceptions.investigation_confirmation (case_id);

COMMENT ON CONSTRAINT ck_investigation_maker_checker ON exceptions.investigation_confirmation IS
    'The same rule as exceptions.repair_action''s ck_maker_checker, independent of the application-layer check in ExceptionCaseService: the person who proposed confirm-sent cannot be the person who approves it.';
