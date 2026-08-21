import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../../test/mocks/server";
import { renderWithSession } from "../../test/test-utils";
import { RepairForm } from "./RepairForm";

const instruction = {
  instructionId: "instr-1",
  uetr: "uetr-1",
  endToEndId: "E2E-0001",
  state: "EXCEPTION" as const,
  amount: "100.00",
  currency: "EUR",
  requestedExecDate: "2026-08-20",
  selectedRail: null,
  creditorAccount: "DE89370400440532013000",
  creditorAgentBic: "DEUTDEFFXXX",
  creditorName: "Creditor SARL",
  chargeBearer: "SHAR",
};

describe("RepairForm", () => {
  it("shows the current value next to each allowlisted field", async () => {
    renderWithSession(<RepairForm caseId="case-1" instruction={instruction} />, { username: "maker1", roles: ["MAKER"] });

    expect(await screen.findByText("DE89370400440532013000")).toBeInTheDocument();
    expect(screen.getByText("DEUTDEFFXXX")).toBeInTheDocument();
    expect(screen.getByText("Creditor SARL")).toBeInTheDocument();
    // amount/currency/uetr are not on the allowlist and must not appear as editable rows.
    expect(screen.queryByLabelText(/Proposed Amount/i)).not.toBeInTheDocument();
  });

  it("labels the submit action 'Propose repair', never 'Save', so it cannot read as having applied", async () => {
    renderWithSession(<RepairForm caseId="case-1" instruction={instruction} />, { username: "maker1", roles: ["MAKER"] });
    await screen.findByText("DE89370400440532013000");

    expect(screen.getByRole("button", { name: "Propose repair" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^save$/i })).not.toBeInTheDocument();
    expect(screen.getByText(/does not apply to the payment until approved/i)).toBeInTheDocument();
  });

  it("submits only the fields the maker selected, not the whole allowlist", async () => {
    const user = userEvent.setup();
    let submittedBody: unknown = null;
    server.use(
      http.post("http://localhost:8084/v1/cases/case-1/repairs", async ({ request }) => {
        submittedBody = await request.json();
        return HttpResponse.json([], { status: 201 });
      }),
    );

    renderWithSession(<RepairForm caseId="case-1" instruction={instruction} />, { username: "maker1", roles: ["MAKER"] });
    await screen.findByText("Creditor SARL");

    await user.click(screen.getByLabelText("Creditor name"));
    await user.type(screen.getByLabelText("Proposed Creditor name"), "Corrected SARL");
    await user.click(screen.getByRole("button", { name: "Propose repair" }));

    await waitFor(() => expect(submittedBody).not.toBeNull());
    expect(submittedBody).toEqual([{ fieldPath: "creditorName", newValue: "Corrected SARL" }]);
  });
});
