import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useCases } from "../api/queries";
import type { CaseStatus, FailureStage, Repairability } from "../api/types";
import { AgeCell } from "../components/AgeCell";
import { EmptyState } from "../components/EmptyState";
import { ErrorBanner } from "../components/ErrorBanner";
import { RepairabilityBadge } from "../components/RepairabilityBadge";
import { StatusBadge } from "../components/StatusBadge";

const STAGES: FailureStage[] = ["INTAKE", "VALIDATION", "ENRICHMENT", "ROUTING", "DISPATCH", "CONFIRMATION", "RECONCILIATION"];
const REPAIRABILITIES: Repairability[] = ["REPAIRABLE", "STATIC_DATA", "TRANSIENT", "UNREPAIRABLE"];
const STATUSES: CaseStatus[] = ["OPEN", "ASSIGNED", "PENDING_APPROVAL", "RESOLVED", "REJECTED"];

/** The landing screen (brief §3): a dense table an operator scans for the whole shift. */
export function ExceptionQueueScreen() {
  const [status, setStatus] = useState<CaseStatus | "">("");
  const [failureStage, setFailureStage] = useState<FailureStage | "">("");
  const [reasonCode, setReasonCode] = useState("");
  const [repairability, setRepairability] = useState<Repairability | "">("");
  const [assignedTo, setAssignedTo] = useState("");
  const [sortDirection, setSortDirection] = useState<"asc" | "desc">("asc");
  const [now, setNow] = useState(() => Date.now());
  const navigate = useNavigate();

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(id);
  }, []);

  const { data, isLoading, error } = useCases({
    status: status || undefined,
    failureStage: failureStage || undefined,
    reasonCode: reasonCode || undefined,
    repairability: repairability || undefined,
    assignedTo: assignedTo || undefined,
    sort: `openedAt,${sortDirection}`,
  });

  const cases = data?.content ?? [];

  return (
    <div>
      <ErrorBanner error={error} />
      <div className="toolbar">
        <div className="filter-field">
          <label htmlFor="filter-stage">Stage</label>
          <select id="filter-stage" value={failureStage} onChange={(e) => setFailureStage(e.target.value as FailureStage | "")}>
            <option value="">All</option>
            {STAGES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-field">
          <label htmlFor="filter-reason">Reason code</label>
          <input id="filter-reason" value={reasonCode} onChange={(e) => setReasonCode(e.target.value)} placeholder="e.g. AC01" />
        </div>
        <div className="filter-field">
          <label htmlFor="filter-repairability">Repairability</label>
          <select
            id="filter-repairability"
            value={repairability}
            onChange={(e) => setRepairability(e.target.value as Repairability | "")}
          >
            <option value="">All</option>
            {REPAIRABILITIES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-field">
          <label htmlFor="filter-assignee">Assignee</label>
          <input id="filter-assignee" value={assignedTo} onChange={(e) => setAssignedTo(e.target.value)} placeholder="username" />
        </div>
        <div className="filter-field">
          <label htmlFor="filter-status">Resolution status</label>
          <select id="filter-status" value={status} onChange={(e) => setStatus(e.target.value as CaseStatus | "")}>
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <span className="result-count">{isLoading ? "Loading…" : `${cases.length} case${cases.length === 1 ? "" : "s"}`}</span>
      </div>

      {isLoading && <div className="loading-state">Loading exception queue…</div>}

      {!isLoading && cases.length === 0 && (
        <EmptyState title="No cases match these filters" detail="Try widening the filters above, or check back later." />
      )}

      {!isLoading && cases.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Instruction</th>
              <th>Amount</th>
              <th>Creditor</th>
              <th>Stage</th>
              <th>Reason</th>
              <th>Repairability</th>
              <th>Type</th>
              <th>
                <button type="button" className="sort" onClick={() => setSortDirection((d) => (d === "asc" ? "desc" : "asc"))}>
                  Age {sortDirection === "asc" ? "↑" : "↓"}
                </button>
              </th>
              <th>Assignee</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {cases.map((c) => (
              <tr key={c.caseId} onClick={() => navigate(`/cases/${c.caseId}`)} data-testid="case-row">
                <td className="id">{c.endToEndId ?? c.instructionId}</td>
                <td className="num">
                  {c.amount ?? "—"} {c.currency ?? ""}
                </td>
                <td>{c.creditorName ?? "—"}</td>
                <td>{c.failureStage}</td>
                <td className="mono">{c.reasonCode ?? "—"}</td>
                <td>
                  <RepairabilityBadge value={c.repairability} />
                </td>
                <td>
                  {c.caseType === "INVESTIGATION" && <span className="case-type-badge investigation">Investigation</span>}
                  {c.caseType === "BUSINESS_FAILURE" && <span className="case-type-badge">Business failure</span>}
                </td>
                <td>
                  <AgeCell openedAt={c.openedAt} now={now} />
                </td>
                <td className="mono">{c.assignedTo ?? "—"}</td>
                <td>
                  <StatusBadge value={c.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
