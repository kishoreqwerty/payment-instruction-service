import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { CaseDetailResponse } from "../../api/types";
import { renderWithSession } from "../../test/test-utils";
import { InvestigationResolutionPanel } from "./InvestigationResolutionPanel";

const caseDetail: CaseDetailResponse = {
  exceptionCase: {
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
    openedAt: new Date().toISOString(),
    closedAt: null,
  },
  instruction: {
    instructionId: "instr-3",
    uetr: "uetr-3",
    endToEndId: "E2E-0003",
    state: "INVESTIGATION",
    amount: "5000.00",
    currency: "USD",
    requestedExecDate: "2026-08-20",
    selectedRail: "fedwire",
    creditorAccount: "GB00WEST12345698765432",
    creditorAgentBic: "WESTGB2LXXX",
    creditorName: "Overseas Ltd",
    chargeBearer: "SHAR",
  },
  repairActions: [],
  investigationConfirmations: [
    {
      confirmationId: "conf-1",
      caseId: "case-3",
      justification: "Confirmed with the rail's ops desk by phone",
      proposedBy: "maker1",
      proposedAt: new Date().toISOString(),
      approvedBy: null,
      approvedAt: null,
    },
  ],
};

describe("InvestigationResolutionPanel", () => {
  it("is visually distinct from a field repair panel (its own investigation styling, no field grid)", () => {
    const { container } = renderWithSession(<InvestigationResolutionPanel caseDetail={caseDetail} />, {
      username: "checker2",
      roles: ["CHECKER"],
    });
    expect(container.querySelector(".panel.investigation")).toBeInTheDocument();
    expect(container.querySelector(".field-grid")).not.toBeInTheDocument();
  });

  it("hides approve from the checker who proposed the confirmation themselves", () => {
    renderWithSession(<InvestigationResolutionPanel caseDetail={caseDetail} />, { username: "maker1", roles: ["MAKER", "CHECKER"] });
    expect(screen.queryByRole("button", { name: "Approve confirm-sent" })).not.toBeInTheDocument();
    expect(screen.getByText(/a different checker must approve it/i)).toBeInTheDocument();
  });

  it("shows approve to a different checker", () => {
    renderWithSession(<InvestigationResolutionPanel caseDetail={caseDetail} />, { username: "checker2", roles: ["CHECKER"] });
    expect(screen.getByRole("button", { name: "Approve confirm-sent" })).toBeInTheDocument();
  });

  it("shows the reject action to a checker with its own justification field, independent of the maker step", () => {
    renderWithSession(<InvestigationResolutionPanel caseDetail={caseDetail} />, { username: "checker2", roles: ["CHECKER"] });
    expect(screen.getByLabelText(/reject this investigation/i)).toBeInTheDocument();
  });
});
