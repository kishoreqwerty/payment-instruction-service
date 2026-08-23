-- Phase 11 (.notes/reports/PHASE-11-REPORT.md). classifier_code, classifier_conf and
-- classifier_accepted already existed on exception_case since Phase 8's own migration -- this adds
-- the rest of the proposal a Phase 8 case row never had anywhere to put: which field the model
-- suggests changing, what value it suggests, and the rationale shown to the operator alongside it
-- (.notes/ARCHITECTURE.md section 10: "rationale is stored and shown to the operator"). One row's
-- worth of columns, not a separate history table: there is at most one live proposal per case, the
-- one produced when the case opened, and nothing in this phase re-classifies an already-open case.
ALTER TABLE exceptions.exception_case
    ADD COLUMN classifier_suggested_field TEXT,
    ADD COLUMN classifier_suggested_value TEXT,
    ADD COLUMN classifier_rationale TEXT;
