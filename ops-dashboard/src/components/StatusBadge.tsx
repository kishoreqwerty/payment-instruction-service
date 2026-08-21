import type { CaseStatus } from "../api/types";

const LABELS: Record<CaseStatus, string> = {
  OPEN: "Open",
  ASSIGNED: "Assigned",
  PENDING_APPROVAL: "Pending approval",
  RESOLVED: "Resolved",
  REJECTED: "Rejected",
};

export function StatusBadge({ value }: { value: CaseStatus }) {
  return <span className="status-badge">{LABELS[value]}</span>;
}
