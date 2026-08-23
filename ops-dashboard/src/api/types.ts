// Mirrors exception-service's response DTOs (exception-service/src/main/java/.../api/*.java).
// Kept as one file, hand-written rather than generated: the API is small and stable enough
// (Phase 8, plus the Phase 9 additions in CaseSummaryResponse/the /pending endpoints) that a
// generator would be more machinery than the eight shapes below justify.

export type FailureStage = "INTAKE" | "VALIDATION" | "ENRICHMENT" | "ROUTING" | "DISPATCH" | "CONFIRMATION" | "RECONCILIATION";

export type Repairability = "REPAIRABLE" | "STATIC_DATA" | "TRANSIENT" | "UNREPAIRABLE";

export type CaseType = "BUSINESS_FAILURE" | "INVESTIGATION";

export type CaseStatus = "OPEN" | "ASSIGNED" | "PENDING_APPROVAL" | "RESOLVED" | "REJECTED";

export type Resolution = "REPAIRED" | "REJECTED" | "CANCELLED" | "AUTO_RETRIED" | "CONFIRMED_SENT";

export type InstructionState =
  | "RECEIVED"
  | "VALIDATED"
  | "ENRICHED"
  | "ROUTED"
  | "DISPATCHED"
  | "SENT"
  | "SETTLED"
  | "RETURNED"
  | "REPAIRED"
  | "EXCEPTION"
  | "REJECTED"
  | "INVESTIGATION";

export interface CaseSummaryResponse {
  caseId: string;
  instructionId: string;
  endToEndId: string | null;
  amount: string | null; // BigDecimal serialises as a JSON string-safe number; treat as opaque text for display.
  currency: string | null;
  creditorName: string | null;
  caseType: CaseType;
  status: CaseStatus;
  failureStage: FailureStage;
  reasonCode: string | null;
  reasonDetail: string | null;
  repairability: Repairability;
  assignedTo: string | null;
  resolution: Resolution | null;
  repairAttempts: number;
  justification: string | null;
  openedAt: string;
  closedAt: string | null;
  // Phase 11: the classifier's proposal, if it ran and produced one -- all null if the
  // classifier was unavailable or its response was unusable. Never authoritative; see
  // ClassifierProposalPanel for how this is presented (a suggestion, not a determination).
  classifierCode: string | null;
  classifierRepairability: Repairability | null;
  classifierConf: number | null;
  classifierAccepted: boolean | null;
  classifierSuggestedField: string | null;
  classifierSuggestedValue: string | null;
  classifierRationale: string | null;
}

export interface RepairActionResponse {
  actionId: string;
  caseId: string;
  fieldPath: string;
  oldValue: string | null;
  newValue: string;
  proposedBy: string;
  proposedAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
}

export interface InvestigationConfirmationResponse {
  confirmationId: string;
  caseId: string;
  justification: string;
  proposedBy: string;
  proposedAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
}

export interface CaseDetailResponse {
  exceptionCase: CaseSummaryResponse;
  instruction: InstructionSummaryResponse;
  repairActions: RepairActionResponse[];
  investigationConfirmations: InvestigationConfirmationResponse[];
}

export interface InstructionSummaryResponse {
  instructionId: string;
  uetr: string;
  endToEndId: string;
  state: InstructionState;
  amount: string;
  currency: string;
  requestedExecDate: string;
  selectedRail: string | null;
  creditorAccount: string | null;
  creditorAgentBic: string | null;
  creditorName: string | null;
  chargeBearer: string | null;
  // Non-null when a non-terminal exception case exists for this instruction -- lets a
  // lookup-screen result row navigate straight to it. Null from CaseDetailResponse's own
  // embedded instruction (the case is already known there).
  openCaseId: string | null;
}

export interface TimelineEntry {
  type: "STATE_TRANSITION" | "REPAIR_PROPOSED" | "REPAIR_APPROVED" | "CONFIRMATION_PROPOSED" | "CONFIRMATION_APPROVED";
  occurredAt: string;
  actor: string;
  fromState: string | null;
  toState: string | null;
  reasonCode: string | null;
  reasonDetail: string | null;
  fieldPath: string | null;
  oldValue: string | null;
  newValue: string | null;
}

// Spring Data's default Page<T> JSON shape.
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number; // zero-based current page
  size: number;
}

export interface ErrorBody {
  error: string;
  detail: string;
}

export interface FieldNotRepairableBody extends ErrorBody {
  disallowedFields: string[];
}

export interface FieldChange {
  fieldPath: string;
  newValue: string;
}

export interface RepairableFieldResponse {
  fieldPath: string;
  label: string;
}

export interface MeResponse {
  username: string;
  roles: string[];
}
