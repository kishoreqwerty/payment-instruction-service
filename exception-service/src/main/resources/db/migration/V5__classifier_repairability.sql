-- The classifier's proposed repairability was never persisted: ClassifierProposal carries
-- reasonCode, repairability, suggestedField, suggestedValue, confidence, and rationale, but
-- V4__classifier_proposal_detail.sql only added columns for everything except repairability,
-- and ClassifierProposalWriter never set it. Discovered while wiring the ops-dashboard proposal
-- panel (Phase 11 section 7), which needs it to show the operator what the classifier believes
-- the repairability should be, not just its reason code.
ALTER TABLE exceptions.exception_case ADD COLUMN classifier_repairability TEXT;
