import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import type { CaseSummaryResponse } from "../../api/types";
import { server } from "../../test/mocks/server";
import { renderWithSession } from "../../test/test-utils";
import { ClassifierProposalPanel } from "./ClassifierProposalPanel";

const baseCase: CaseSummaryResponse = {
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
  openedAt: new Date().toISOString(),
  closedAt: null,
  classifierCode: "AC01",
  classifierRepairability: "REPAIRABLE",
  classifierConf: 0.92,
  classifierAccepted: null,
  classifierSuggestedField: "creditorAccount",
  classifierSuggestedValue: "DE89370400440532013000",
  classifierRationale: "The creditor IBAN fails the mod-97 checksum, a data-entry error an operator can correct and resubmit.",
};

describe("ClassifierProposalPanel", () => {
  it("renders nothing when the classifier produced no proposal", () => {
    const noProposal: CaseSummaryResponse = {
      ...baseCase,
      classifierCode: null,
      classifierRepairability: null,
      classifierConf: null,
      classifierSuggestedField: null,
      classifierSuggestedValue: null,
      classifierRationale: null,
    };
    const { container } = renderWithSession(
      <ClassifierProposalPanel exceptionCase={noProposal} canAct={true} onUseSuggestion={vi.fn()} />,
      { username: "maker1", roles: ["MAKER"] },
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the proposal as a suggestion, not a determination, with confidence and rationale visible", () => {
    renderWithSession(<ClassifierProposalPanel exceptionCase={baseCase} canAct={true} onUseSuggestion={vi.fn()} />, {
      username: "maker1",
      roles: ["MAKER"],
    });

    expect(screen.getByText("Classifier suggestion")).toBeInTheDocument();
    expect(screen.getByText("not a determination")).toBeInTheDocument();
    expect(screen.getByText("92% confidence")).toBeInTheDocument();
    expect(screen.getByText(/fails the mod-97 checksum/)).toBeInTheDocument();
  });

  it("does not render accept/dismiss actions when the operator cannot act on this case", () => {
    renderWithSession(<ClassifierProposalPanel exceptionCase={baseCase} canAct={false} onUseSuggestion={vi.fn()} />, {
      username: "checker1",
      roles: ["CHECKER"],
    });

    expect(screen.queryByRole("button", { name: "Use this suggestion" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Dismiss" })).not.toBeInTheDocument();
  });

  it("accepting calls back with the suggested field/value and records classifier_accepted=true", async () => {
    const user = userEvent.setup();
    const onUseSuggestion = vi.fn();
    let submittedBody: unknown = null;
    server.use(
      http.post("http://localhost:8084/v1/cases/case-1/classifier-feedback", async ({ request }) => {
        submittedBody = await request.json();
        return HttpResponse.json({ ...baseCase, classifierAccepted: true });
      }),
    );

    renderWithSession(<ClassifierProposalPanel exceptionCase={baseCase} canAct={true} onUseSuggestion={onUseSuggestion} />, {
      username: "maker1",
      roles: ["MAKER"],
    });

    await user.click(screen.getByRole("button", { name: "Use this suggestion" }));

    expect(onUseSuggestion).toHaveBeenCalledWith("creditorAccount", "DE89370400440532013000");
    await waitFor(() => expect(submittedBody).toEqual({ accepted: true }));
  });

  it("dismissing records classifier_accepted=false without calling the pre-fill callback", async () => {
    const user = userEvent.setup();
    const onUseSuggestion = vi.fn();
    let submittedBody: unknown = null;
    server.use(
      http.post("http://localhost:8084/v1/cases/case-1/classifier-feedback", async ({ request }) => {
        submittedBody = await request.json();
        return HttpResponse.json({ ...baseCase, classifierAccepted: false });
      }),
    );

    renderWithSession(<ClassifierProposalPanel exceptionCase={baseCase} canAct={true} onUseSuggestion={onUseSuggestion} />, {
      username: "maker1",
      roles: ["MAKER"],
    });

    await user.click(screen.getByRole("button", { name: "Dismiss" }));

    expect(onUseSuggestion).not.toHaveBeenCalled();
    await waitFor(() => expect(submittedBody).toEqual({ accepted: false }));
  });

  it("shows prior feedback instead of action buttons once recorded", () => {
    const accepted: CaseSummaryResponse = { ...baseCase, classifierAccepted: true };
    renderWithSession(<ClassifierProposalPanel exceptionCase={accepted} canAct={true} onUseSuggestion={vi.fn()} />, {
      username: "maker1",
      roles: ["MAKER"],
    });

    expect(screen.getByText("You accepted this suggestion.")).toBeInTheDocument();
  });
});
