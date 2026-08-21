import type { TimelineEntry } from "../../api/types";
import { EmptyState } from "../../components/EmptyState";

function describe(entry: TimelineEntry): string {
  switch (entry.type) {
    case "STATE_TRANSITION":
      return entry.fromState ? `${entry.fromState} → ${entry.toState}` : `Opened at ${entry.toState}`;
    case "REPAIR_PROPOSED":
      return `Proposed repair: ${entry.fieldPath} "${entry.oldValue ?? "(empty)"}" → "${entry.newValue}"`;
    case "REPAIR_APPROVED":
      return `Approved repair: ${entry.fieldPath} → "${entry.newValue}"`;
    case "CONFIRMATION_PROPOSED":
      return "Proposed confirm-sent";
    case "CONFIRMATION_APPROVED":
      return "Approved confirm-sent";
    default:
      return entry.type;
  }
}

/** "What happened to this payment" (brief §3) -- every transition and repair action, in the order they actually happened. */
export function Timeline({ entries }: { entries: TimelineEntry[] }) {
  if (entries.length === 0) {
    return <EmptyState title="No history yet" detail="This instruction has no recorded transitions." />;
  }
  return (
    <div className="timeline">
      {entries.map((entry, index) => (
        <div className="timeline-entry" key={index}>
          <span className="ts">{new Date(entry.occurredAt).toLocaleString()}</span>
          <span className="actor mono">{entry.actor}</span>
          <span className="detail">
            {describe(entry)}
            {entry.reasonCode && <span className="reason-code-tag">{entry.reasonCode}</span>}
            {entry.reasonDetail && entry.type !== "STATE_TRANSITION" && <span> — {entry.reasonDetail}</span>}
          </span>
        </div>
      ))}
    </div>
  );
}
