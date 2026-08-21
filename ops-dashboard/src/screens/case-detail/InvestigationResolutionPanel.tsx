import { useState } from "react";
import { useApproveConfirmSent, useProposeConfirmSent, useRejectInvestigation } from "../../api/queries";
import type { CaseDetailResponse } from "../../api/types";
import { useAuth, useRole } from "../../auth/AuthContext";
import { ErrorBanner } from "../../components/ErrorBanner";

/**
 * Brief §3: "visually distinct from field repair, because it is a
 * different act... there is no field to change; there is a justification
 * to write and an assertion to make about a payment the system cannot
 * see." Rendered with its own `.panel.investigation` styling (a left
 * accent border, not the plain panel a repair form gets) rather than
 * reusing RepairForm's layout for a case with zero fields.
 *
 * Role gating here is a usability affordance only -- hiding the approve
 * button for the proposer's own confirmation, or hiding propose/approve
 * from a viewer -- and never the actual control. The server's own
 * @PreAuthorize checks and the ck_investigation_maker_checker database
 * constraint are what actually stop a self-approval or a wrong role; see
 * brief §5 and SecurityConfig's own javadoc.
 */
export function InvestigationResolutionPanel({ caseDetail }: { caseDetail: CaseDetailResponse }) {
  const { session } = useAuth();
  const isMaker = useRole("MAKER");
  const isChecker = useRole("CHECKER");
  const [justification, setJustification] = useState("");
  const [rejectReason, setRejectReason] = useState("");

  const proposeConfirmSent = useProposeConfirmSent();
  const approveConfirmSent = useApproveConfirmSent();
  const rejectInvestigation = useRejectInvestigation();

  const exceptionCase = caseDetail.exceptionCase;
  const pending = caseDetail.investigationConfirmations.find((c) => c.approvedBy === null);
  const isOwnProposal = pending && session && pending.proposedBy === session.username;
  const caseOpen = exceptionCase.status === "OPEN";
  const caseActionable = exceptionCase.status === "OPEN" || exceptionCase.status === "PENDING_APPROVAL";

  return (
    <div className="panel investigation">
      <h2>Investigation resolution</h2>
      <ErrorBanner error={proposeConfirmSent.error ?? approveConfirmSent.error ?? rejectInvestigation.error} />

      {pending ? (
        <div>
          <p>
            Pending confirm-sent proposal by <span className="mono">{pending.proposedBy}</span>: “{pending.justification}”
          </p>
          {isChecker && !isOwnProposal && (
            <button
              type="button"
              className="btn btn-approve"
              disabled={approveConfirmSent.isPending}
              onClick={() => approveConfirmSent.mutate(pending.confirmationId)}
            >
              Approve confirm-sent
            </button>
          )}
          {isChecker && isOwnProposal && <p className="propose-warning">You proposed this confirmation; a different checker must approve it.</p>}
        </div>
      ) : (
        isMaker &&
        caseOpen && (
          <div className="justification-form">
            <label htmlFor="confirm-justification">Justification (what evidence confirms this payment reached the rail?)</label>
            <textarea id="confirm-justification" value={justification} onChange={(e) => setJustification(e.target.value)} />
            <p className="propose-warning">
              Proposing does not resolve the case. A different checker must approve this confirm-sent before the payment is marked SENT.
            </p>
            <div className="form-actions">
              <button
                type="button"
                className="btn btn-primary"
                disabled={!justification.trim() || proposeConfirmSent.isPending}
                onClick={() => proposeConfirmSent.mutate({ caseId: exceptionCase.caseId, justification })}
              >
                {proposeConfirmSent.isPending ? "Proposing…" : "Propose confirm-sent"}
              </button>
            </div>
          </div>
        )
      )}

      {isChecker && caseActionable && (
        <div className="justification-form" style={{ marginTop: 16 }}>
          <label htmlFor="reject-justification">Reject this investigation (no maker step required)</label>
          <textarea id="reject-justification" value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} />
          <div className="form-actions">
            <button
              type="button"
              className="btn btn-danger"
              disabled={!rejectReason.trim() || rejectInvestigation.isPending}
              onClick={() => rejectInvestigation.mutate({ caseId: exceptionCase.caseId, justification: rejectReason })}
            >
              Reject investigation
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
