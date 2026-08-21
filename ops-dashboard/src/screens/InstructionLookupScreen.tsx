import { useState } from "react";
import { useInstructionLookup } from "../api/queries";
import { EmptyState } from "../components/EmptyState";
import { ErrorBanner } from "../components/ErrorBanner";

/** "Someone calls asking about a payment; this is how it gets found" (brief §3). */
export function InstructionLookupScreen() {
  const [uetr, setUetr] = useState("");
  const [endToEndId, setEndToEndId] = useState("");
  const [submitted, setSubmitted] = useState<{ uetr?: string; endToEndId?: string } | null>(null);

  const { data, isLoading, error, isFetched } = useInstructionLookup(submitted);

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
              <tr key={instruction.instructionId}>
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
