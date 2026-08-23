import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useInstructionLookup } from "../api/queries";
import type { InstructionSummaryResponse } from "../api/types";
import { EmptyState } from "../components/EmptyState";
import { ErrorBanner } from "../components/ErrorBanner";

/** "Someone calls asking about a payment; this is how it gets found" (brief §3). */
export function InstructionLookupScreen() {
  const [uetr, setUetr] = useState("");
  const [endToEndId, setEndToEndId] = useState("");
  const [submitted, setSubmitted] = useState<{ uetr?: string; endToEndId?: string } | null>(null);
  const navigate = useNavigate();

  const { data, isLoading, error, isFetched } = useInstructionLookup(submitted);

  // A search exists to find a specific payment's detail -- a result row that doesn't go
  // anywhere is a dead end. An open case is the fuller view (status, repair history, the
  // classifier's own suggestion), so it wins when one exists; otherwise the instruction's own
  // timeline is the next best thing. The looked-up summary travels as router state so the
  // timeline screen doesn't need a second round trip to re-fetch it.
  function open(instruction: InstructionSummaryResponse) {
    if (instruction.openCaseId) {
      navigate(`/cases/${instruction.openCaseId}`);
    } else {
      navigate(`/instructions/${instruction.instructionId}`, { state: instruction });
    }
  }

  function handleSubmit() {
    if (uetr.trim()) {
      setSubmitted({ uetr: uetr.trim() });
    } else if (endToEndId.trim()) {
      setSubmitted({ endToEndId: endToEndId.trim() });
    }
  }

  return (
    <div>
      <ErrorBanner error={error} />
      <div className="lookup-form">
        <div className="filter-field">
          <label htmlFor="lookup-uetr">UETR</label>
          <input id="lookup-uetr" className="mono" value={uetr} onChange={(e) => setUetr(e.target.value)} placeholder="uuid" />
        </div>
        <div className="filter-field">
          <label htmlFor="lookup-e2e">End-to-end ID</label>
          <input id="lookup-e2e" className="mono" value={endToEndId} onChange={(e) => setEndToEndId(e.target.value)} />
        </div>
        <button type="button" className="btn btn-primary" onClick={handleSubmit} disabled={!uetr.trim() && !endToEndId.trim()}>
          Search
        </button>
      </div>

      {isLoading && <div className="loading-state">Searching…</div>}

      {isFetched && data?.length === 0 && <EmptyState title="No instruction found" detail="Check the UETR or end-to-end ID and try again." />}

      {data && data.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <th>End-to-end ID</th>
              <th>UETR</th>
              <th>State</th>
              <th>Amount</th>
              <th>Rail</th>
            </tr>
          </thead>
          <tbody>
            {data.map((instruction) => (
              <tr key={instruction.instructionId} onClick={() => open(instruction)} data-testid="lookup-row">
                <td className="id">{instruction.endToEndId}</td>
                <td className="id">{instruction.uetr}</td>
                <td>{instruction.state}</td>
                <td className="num">
                  {instruction.amount} {instruction.currency}
                </td>
                <td>{instruction.selectedRail ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
