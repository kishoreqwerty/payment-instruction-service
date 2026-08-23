import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useCase, useRejectCase, useRetryStaticData, useTimeline } from "../api/queries";
import { useRole } from "../auth/AuthContext";
import { EmptyState } from "../components/EmptyState";
import { ErrorBanner } from "../components/ErrorBanner";
import { RepairabilityBadge } from "../components/RepairabilityBadge";
import { ReasonExplanation } from "../components/ReasonExplanation";
import { StatusBadge } from "../components/StatusBadge";
import { ClassifierProposalPanel } from "./case-detail/ClassifierProposalPanel";
import { InvestigationResolutionPanel } from "./case-detail/InvestigationResolutionPanel";
import { RepairForm } from "./case-detail/RepairForm";
import { Timeline } from "./case-detail/Timeline";

/** "What broke, in plain language, and what can be done about it" (brief §3). */
export function CaseDetailScreen() {
  const { caseId } = useParams<{ caseId: string }>();
  const { data, isLoading, error } = useCase(caseId);
  const timeline = useTimeline(data?.exceptionCase.instructionId);
  const isMaker = useRole("MAKER");
  const isChecker = useRole("CHECKER");
  const retry = useRetryStaticData();
  const reject = useRejectCase();
  const [suggestion, setSuggestion] = useState<{ fieldPath: string; value: string } | null>(null);

  if (isLoading) return <div className="loading-state">Loading case…</div>;
  if (error) return <ErrorBanner error={error} />;
  if (!data) return <EmptyState title="Case not found" />;

  const { exceptionCase, instruction, repairActions } = data;
  const caseActionable = exceptionCase.status === "OPEN" || exceptionCase.status === "PENDING_APPROVAL";

  return (
    <div>
      <Link to="/" className="back-link">
        ← Back to queue
      </Link>

      <div className="case-header">
        <span className="instruction-ref">{instruction.endToEndId}</span>
        <span className="num">
          {instruction.amount} {instruction.currency}
        </span>
        {exceptionCase.caseType === "INVESTIGATION" ? (
          <span className="case-type-badge investigation">Investigation</span>
        ) : (
          <span className="case-type-badge">Business failure</span>
        )}
        <StatusBadge value={exceptionCase.status} />
        <RepairabilityBadge value={exceptionCase.repairability} />
      </div>

      <ErrorBanner error={retry.error ?? reject.error} />

      <div className="panel">
        <h2>What broke</h2>
        <ReasonExplanation reasonCode={exceptionCase.reasonCode} reasonDetail={exceptionCase.reasonDetail} />
        <div className="field-grid" style={{ marginTop: 12 }}>
          <div className="field">
            <span className="field-label">State</span>
            <span className="field-value">{instruction.state}</span>
          </div>
          <div className="field">
            <span className="field-label">UETR</span>
            <span className="field-value">{instruction.uetr}</span>
          </div>
          <div className="field">
            <span className="field-label">Creditor name</span>
            <span className="field-value">{instruction.creditorName}</span>
          </div>
          <div className="field">
            <span className="field-label">Creditor account</span>
            <span className="field-value">{instruction.creditorAccount}</span>
          </div>
          <div className="field">
            <span className="field-label">Creditor agent BIC</span>
            <span className="field-value">{instruction.creditorAgentBic}</span>
          </div>
          <div className="field">
            <span className="field-label">Charge bearer</span>
            <span className="field-value">{instruction.chargeBearer}</span>
          </div>
          <div className="field">
            <span className="field-label">Requested execution date</span>
            <span className="field-value">{instruction.requestedExecDate}</span>
          </div>
          <div className="field">
            <span className="field-label">Assigned to</span>
            <span className="field-value">{exceptionCase.assignedTo ?? "Unassigned"}</span>
          </div>
        </div>
      </div>

      <ClassifierProposalPanel
        exceptionCase={exceptionCase}
        canAct={isMaker && exceptionCase.status === "OPEN" && caseActionable}
        onUseSuggestion={(fieldPath, value) => setSuggestion({ fieldPath, value })}
      />

      {((isMaker && exceptionCase.repairability === "STATIC_DATA" && caseActionable) ||
        (isChecker && exceptionCase.caseType === "BUSINESS_FAILURE" && caseActionable)) && (
        <div className="panel">
          <h2>Actions</h2>
          <div className="form-actions" style={{ marginTop: 0 }}>
            {isMaker && exceptionCase.repairability === "STATIC_DATA" && caseActionable && (
              <button type="button" className="btn" disabled={retry.isPending} onClick={() => retry.mutate(exceptionCase.caseId)}>
                {retry.isPending ? "Retrying…" : "Retry now reference data is fixed"}
              </button>
            )}
            {isChecker && exceptionCase.caseType === "BUSINESS_FAILURE" && caseActionable && (
              <button type="button" className="btn btn-danger" disabled={reject.isPending} onClick={() => reject.mutate(exceptionCase.caseId)}>
                Reject case
              </button>
            )}
          </div>
        </div>
      )}

      {exceptionCase.caseType === "BUSINESS_FAILURE" &&
        (isMaker && exceptionCase.status === "OPEN" ? (
          <RepairForm caseId={exceptionCase.caseId} instruction={instruction} suggestion={suggestion} />
        ) : repairActions.length === 0 ? (
          !caseActionable ? null : (
            <div className="panel">
              <h2>Repair form</h2>
              <EmptyState title="Only a maker can propose a repair" detail="Sign in as a maker to propose a field change for this case." />
            </div>
          )
        ) : null)}

      {repairActions.length > 0 && (
        <div className="panel">
          <h2>Proposed repairs</h2>
          {repairActions.map((action) => (
            <div key={action.actionId} className="repair-form-row" style={{ gridTemplateColumns: "160px 1fr 1fr" }}>
              <span>{action.fieldPath}</span>
              <span className="diff">
                <span className="old">{action.oldValue ?? "(empty)"}</span>
                <span className="arrow">→</span>
                <span className="new">{action.newValue}</span>
              </span>
              <span className="proposer">
                by {action.proposedBy}
                {action.approvedBy ? `, approved by ${action.approvedBy}` : " — awaiting approval"}
              </span>
            </div>
          ))}
        </div>
      )}

      {exceptionCase.caseType === "INVESTIGATION" && <InvestigationResolutionPanel caseDetail={data} />}

      <div className="panel">
        <h2>Timeline</h2>
        {timeline.isLoading && <div className="loading-state">Loading timeline…</div>}
        {timeline.data && <Timeline entries={timeline.data} />}
      </div>
    </div>
  );
}
