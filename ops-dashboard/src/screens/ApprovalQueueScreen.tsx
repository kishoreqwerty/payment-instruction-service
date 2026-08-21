import { useState } from "react";
import { Link } from "react-router-dom";
import { useApproveConfirmSent, useApproveRepair, usePendingConfirmations, usePendingRepairs, useRejectCase, useRejectInvestigation } from "../api/queries";
import { useAuth, useRole } from "../auth/AuthContext";
import { EmptyState } from "../components/EmptyState";
import { ErrorBanner } from "../components/ErrorBanner";

/**
 * A checker's home screen (brief §3: "not nested inside a case detail
 * view"). Every pending proposal, across every case, with its diff already
 * resolved -- see RepairController#pending / InvestigationConfirmationController#pending,
 * added this phase precisely so this screen never has to open a case to
 * show what changed.
 *
 * The self-approval hide is client-side only, exactly like every other
 * role gate in this app: comparing `proposedBy` to the signed-in username
 * decides whether the Approve button renders, but the server's own
 * MakerCheckerViolationException (403) and the ck_maker_checker /
 * ck_investigation_maker_checker database constraints are what actually
 * enforce it (brief §5, §8).
 */
export function ApprovalQueueScreen() {
  const { session } = useAuth();
  const isChecker = useRole("CHECKER");
  const repairs = usePendingRepairs();
  const confirmations = usePendingConfirmations();
  const approveRepair = useApproveRepair();
  const approveConfirmSent = useApproveConfirmSent();
  const rejectCase = useRejectCase();
  const rejectInvestigation = useRejectInvestigation();
  const [rejectJustifications, setRejectJustifications] = useState<Record<string, string>>({});

  const isLoading = repairs.isLoading || confirmations.isLoading;
  const error = repairs.error ?? confirmations.error ?? approveRepair.error ?? approveConfirmSent.error ?? rejectCase.error ?? rejectInvestigation.error;
  const totalPending = (repairs.data?.length ?? 0) + (confirmations.data?.length ?? 0);

  return (
    <div>
      <ErrorBanner error={error} />
      <div className="toolbar">
        <span className="result-count" style={{ marginLeft: 0 }}>
          {isLoading ? "Loading…" : `${totalPending} pending approval${totalPending === 1 ? "" : "s"}`}
        </span>
      </div>

      {isLoading && <div className="loading-state">Loading approval queue…</div>}

      {!isLoading && totalPending === 0 && (
        <EmptyState title="Nothing waiting on approval" detail="Proposed repairs and confirm-sent requests will appear here." />
      )}

      {repairs.data?.map((action) => {
        const isOwn = session?.username === action.proposedBy;
        return (
          <div className="approval-card" key={action.actionId}>
            <div className="meta">
              <span className="proposer mono">{action.proposedBy}</span>
              <span className="proposer">{new Date(action.proposedAt).toLocaleString()}</span>
              <Link className="case-link" to={`/cases/${action.caseId}`}>
                Open case
              </Link>
            </div>
            <div className="diff-area">
              <div className="diff">
                <span>{action.fieldPath}:</span>
                <span className="old">{action.oldValue ?? "(empty)"}</span>
                <span className="arrow">→</span>
                <span className="new">{action.newValue}</span>
              </div>
            </div>
            <div className="actions">
              {isChecker && !isOwn && (
                <button
                  type="button"
                  className="btn btn-approve"
                  disabled={approveRepair.isPending}
                  onClick={() => approveRepair.mutate(action.actionId)}
                >
                  Approve
                </button>
              )}
              {isChecker && isOwn && <span className="proposer">You proposed this — a different checker must approve it.</span>}
              {isChecker && (
                <button
                  type="button"
                  className="btn btn-danger"
                  disabled={rejectCase.isPending}
                  onClick={() => rejectCase.mutate(action.caseId)}
                >
                  Reject case
                </button>
              )}
            </div>
          </div>
        );
      })}

      {confirmations.data?.map((confirmation) => {
        const isOwn = session?.username === confirmation.proposedBy;
        return (
          <div className="approval-card" key={confirmation.confirmationId}>
            <div className="meta">
              <span className="proposer mono">{confirmation.proposedBy}</span>
              <span className="proposer">{new Date(confirmation.proposedAt).toLocaleString()}</span>
              <Link className="case-link" to={`/cases/${confirmation.caseId}`}>
                Open case
              </Link>
            </div>
            <div className="diff-area">
              <span className="case-type-badge investigation">Confirm-sent</span> “{confirmation.justification}”
            </div>
            <div className="actions">
              {isChecker && !isOwn && (
                <button
                  type="button"
                  className="btn btn-approve"
                  disabled={approveConfirmSent.isPending}
                  onClick={() => approveConfirmSent.mutate(confirmation.confirmationId)}
                >
                  Approve
                </button>
              )}
              {isChecker && isOwn && <span className="proposer">You proposed this — a different checker must approve it.</span>}
              {isChecker && (
                <>
                  <input
                    aria-label="Rejection justification"
                    placeholder="Reason for rejecting"
                    value={rejectJustifications[confirmation.confirmationId] ?? ""}
                    onChange={(e) => setRejectJustifications((s) => ({ ...s, [confirmation.confirmationId]: e.target.value }))}
                  />
                  <button
                    type="button"
                    className="btn btn-danger"
                    disabled={!rejectJustifications[confirmation.confirmationId]?.trim() || rejectInvestigation.isPending}
                    onClick={() =>
                      rejectInvestigation.mutate({
                        caseId: confirmation.caseId,
                        justification: rejectJustifications[confirmation.confirmationId] ?? "",
                      })
                    }
                  >
                    Reject
                  </button>
                </>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
