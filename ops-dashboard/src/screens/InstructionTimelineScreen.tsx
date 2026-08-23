import { Link, useLocation, useParams } from "react-router-dom";
import { useTimeline } from "../api/queries";
import type { InstructionSummaryResponse } from "../api/types";
import { ErrorBanner } from "../components/ErrorBanner";
import { Timeline } from "./case-detail/Timeline";

/**
 * Where a lookup-screen row lands when its instruction has no open case (see
 * InstructionLookupScreen) -- "what happened to this payment" without a case to view it
 * through. The instruction summary is passed via router state from the row click (the lookup
 * screen already has it in hand; there is no standalone "get instruction by id" endpoint to
 * re-fetch it from), so a direct link to this URL -- no state, e.g. a bookmark or page refresh
 * -- still renders correctly, just without the summary header fields.
 */
export function InstructionTimelineScreen() {
  const { instructionId } = useParams<{ instructionId: string }>();
  const location = useLocation();
  const instruction = location.state as InstructionSummaryResponse | undefined;
  const { data, isLoading, error } = useTimeline(instructionId);

  return (
    <div>
      <Link to="/lookup" className="back-link">
        ← Back to lookup
      </Link>

      <div className="case-header">
        <span className="instruction-ref">{instruction?.endToEndId ?? instructionId}</span>
        {instruction && (
          <span className="num">
            {instruction.amount} {instruction.currency}
          </span>
        )}
        <span className="case-type-badge">No open case</span>
      </div>

      <ErrorBanner error={error} />

      <div className="panel">
        <h2>Timeline</h2>
        {isLoading && <div className="loading-state">Loading timeline…</div>}
        {data && <Timeline entries={data} />}
      </div>
    </div>
  );
}
