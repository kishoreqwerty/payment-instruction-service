import { useClassifierFeedback } from "../../api/queries";
import type { CaseSummaryResponse } from "../../api/types";
import { ErrorBanner } from "../../components/ErrorBanner";
import { RepairabilityBadge } from "../../components/RepairabilityBadge";

/**
 * The classifier's proposal (Phase 11 §7), shown as a suggestion an operator can accept, edit,
 * or ignore -- never pre-filled into the case's own reasonCode/repairability, never rendered by
 * StatusBadge/RepairabilityBadge in the case header, and visually distinct from "What broke"
 * above it (dashed border, an explicit "Suggestion" label, the model's own confidence and
 * rationale kept in view) so it cannot be mistaken for a system determination. Maker-checker is
 * unchanged: accepting a suggestion only pre-fills the repair form below -- it still has to go
 * through RepairForm -> checker approval like any other proposed repair.
 *
 * Renders nothing if the classifier never produced a proposal for this case (unavailable,
 * circuit open, or a malformed response) -- an absent panel is the honest state, not an error or
 * a loading placeholder.
 */
export function ClassifierProposalPanel({
  exceptionCase,
  canAct,
  onUseSuggestion,
}: {
  exceptionCase: CaseSummaryResponse;
  canAct: boolean;
  onUseSuggestion: (fieldPath: string, value: string) => void;
}) {
  const feedback = useClassifierFeedback();

  const hasProposal = exceptionCase.classifierCode !== null || exceptionCase.classifierRepairability !== null;
  if (!hasProposal) return null;

  const alreadyRecorded = exceptionCase.classifierAccepted !== null;

  function useSuggestion() {
    if (exceptionCase.classifierSuggestedField && exceptionCase.classifierSuggestedValue) {
      onUseSuggestion(exceptionCase.classifierSuggestedField, exceptionCase.classifierSuggestedValue);
    }
    feedback.mutate({ caseId: exceptionCase.caseId, accepted: true });
  }

  function dismiss() {
    feedback.mutate({ caseId: exceptionCase.caseId, accepted: false });
  }

  return (
    <div className="panel classifier-proposal">
      <h2>
        Classifier suggestion
        <span className="suggestion-tag">not a determination</span>
      </h2>
      <ErrorBanner error={feedback.error} />

      <div className="reason-block">
        {exceptionCase.classifierCode && <span className="reason-code">{exceptionCase.classifierCode}</span>}
        {exceptionCase.classifierRepairability && <RepairabilityBadge value={exceptionCase.classifierRepairability} />}
        {exceptionCase.classifierConf !== null && (
          <span className="classifier-confidence">{Math.round(exceptionCase.classifierConf * 100)}% confidence</span>
        )}
      </div>

      {exceptionCase.classifierRationale && <p className="reason-detail">{exceptionCase.classifierRationale}</p>}

      {exceptionCase.classifierSuggestedField && (
        <div className="field-grid" style={{ marginTop: 8 }}>
          <div className="field">
            <span className="field-label">Suggested field</span>
            <span className="field-value">{exceptionCase.classifierSuggestedField}</span>
          </div>
          <div className="field">
            <span className="field-label">Suggested value</span>
            <span className="field-value">{exceptionCase.classifierSuggestedValue ?? "(no specific suggestion)"}</span>
          </div>
        </div>
      )}

      {canAct && (
        <div className="form-actions">
          <button
            type="button"
            className="btn btn-primary"
            disabled={feedback.isPending || !exceptionCase.classifierSuggestedField}
            onClick={useSuggestion}
          >
            Use this suggestion
          </button>
          <button type="button" className="btn" disabled={feedback.isPending} onClick={dismiss}>
            Dismiss
          </button>
        </div>
      )}

      {alreadyRecorded && (
        <p className="classifier-feedback-note">
          {exceptionCase.classifierAccepted ? "You accepted this suggestion." : "You dismissed this suggestion."}
        </p>
      )}
    </div>
  );
}
