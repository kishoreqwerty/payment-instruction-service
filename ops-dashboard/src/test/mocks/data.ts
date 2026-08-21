import type {
  CaseDetailResponse,
  CaseSummaryResponse,
  InstructionSummaryResponse,
  InvestigationConfirmationResponse,
  RepairActionResponse,
  TimelineEntry,
} from "../../api/types";

const now = Date.now();
const hoursAgo = (h: number) => new Date(now - h * 60 * 60 * 1000).toISOString();

export const openBusinessCase: CaseSummaryResponse = {
  caseId: "case-1",
  instructionId: "instr-1",
  endToEndId: "E2E-0001",
  amount: "100.00",
  currency: "EUR",
  creditorName: "Creditor SARL",
  caseType: "BUSINESS_FAILURE",
  status: "OPEN",
  failureStage: "VALIDATION",
  reasonCode: "AC01",
  reasonDetail: "invalid IBAN checksum",
  repairability: "REPAIRABLE",
  assignedTo: null,
  resolution: null,
  repairAttempts: 0,
  justification: null,
  openedAt: hoursAgo(0.1),
  closedAt: null,
};

export const pendingRepairAction: RepairActionResponse = {
  actionId: "action-1",
  caseId: "case-2",
  fieldPath: "creditorName",
  oldValue: "Old Name SARL",
  newValue: "Corrected Name SARL",
  proposedBy: "maker1",
  proposedAt: hoursAgo(1),
  approvedBy: null,
  approvedAt: null,
};

export const pendingApprovalCase: CaseSummaryResponse = {
  ...openBusinessCase,
  caseId: "case-2",
  instructionId: "instr-2",
  endToEndId: "E2E-0002",
  status: "PENDING_APPROVAL",
  openedAt: hoursAgo(5),
};

export const investigationCase: CaseSummaryResponse = {
  caseId: "case-3",
  instructionId: "instr-3",
  endToEndId: "E2E-0003",
  amount: "5000.00",
  currency: "USD",
  creditorName: "Overseas Ltd",
  caseType: "INVESTIGATION",
  status: "PENDING_APPROVAL",
  failureStage: "RECONCILIATION",
  reasonCode: null,
  reasonDetail: "inconclusive",
  repairability: "TRANSIENT",
  assignedTo: "maker1",
  resolution: null,
  repairAttempts: 0,
  justification: null,
  openedAt: hoursAgo(2),
  closedAt: null,
};

export const pendingConfirmation: InvestigationConfirmationResponse = {
  confirmationId: "conf-1",
  caseId: "case-3",
  justification: "Confirmed with the rail's ops desk by phone",
  proposedBy: "maker1",
  proposedAt: hoursAgo(1),
  approvedBy: null,
  approvedAt: null,
};

const instructionFor = (c: CaseSummaryResponse): InstructionSummaryResponse => ({
  instructionId: c.instructionId,
  uetr: `${c.instructionId}-uetr`,
  endToEndId: c.endToEndId ?? "",
  state: c.caseType === "INVESTIGATION" ? "INVESTIGATION" : "EXCEPTION",
  amount: c.amount ?? "0.00",
  currency: c.currency ?? "EUR",
  requestedExecDate: "2026-08-20",
  selectedRail: "fedwire",
  creditorAccount: "DE89370400440532013000",
  creditorAgentBic: "DEUTDEFFXXX",
  creditorName: c.creditorName,
  chargeBearer: "SHAR",
});

export const caseDetails: Record<string, CaseDetailResponse> = {
  "case-1": { exceptionCase: openBusinessCase, instruction: instructionFor(openBusinessCase), repairActions: [], investigationConfirmations: [] },
  "case-2": {
    exceptionCase: pendingApprovalCase,
    instruction: instructionFor(pendingApprovalCase),
    repairActions: [pendingRepairAction],
    investigationConfirmations: [],
  },
  "case-3": {
    exceptionCase: investigationCase,
    instruction: instructionFor(investigationCase),
    repairActions: [],
    investigationConfirmations: [pendingConfirmation],
  },
};

export const timelineFor: Record<string, TimelineEntry[]> = {
  "instr-2": [
    { type: "STATE_TRANSITION", occurredAt: hoursAgo(6), actor: "SYSTEM:system", fromState: null, toState: "RECEIVED", reasonCode: null, reasonDetail: null, fieldPath: null, oldValue: null, newValue: null },
    { type: "STATE_TRANSITION", occurredAt: hoursAgo(5.5), actor: "SYSTEM:system", fromState: "RECEIVED", toState: "EXCEPTION", reasonCode: "AC01", reasonDetail: "invalid IBAN checksum", fieldPath: null, oldValue: null, newValue: null },
    { type: "REPAIR_PROPOSED", occurredAt: hoursAgo(1), actor: "maker1", fromState: null, toState: null, reasonCode: null, reasonDetail: null, fieldPath: "creditorName", oldValue: "Old Name SARL", newValue: "Corrected Name SARL" },
  ],
};

export const usersRoles: Record<string, string[]> = {
  viewer1: ["VIEWER"],
  maker1: ["MAKER"],
  checker1: ["CHECKER"],
  checker2: ["CHECKER"],
  dual1: ["MAKER", "CHECKER"],
};
