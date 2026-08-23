import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { InstructionSummaryResponse } from "../api/types";
import { server } from "../test/mocks/server";
import { renderWithSession } from "../test/test-utils";
import { CaseDetailScreen } from "./CaseDetailScreen";
import { InstructionLookupScreen } from "./InstructionLookupScreen";
import { InstructionTimelineScreen } from "./InstructionTimelineScreen";

const BASE = "http://localhost:8084";

function instruction(overrides: Partial<InstructionSummaryResponse> = {}): InstructionSummaryResponse {
  return {
    instructionId: "instr-1",
    uetr: "uetr-1",
    endToEndId: "E2E-0001",
    state: "EXCEPTION",
    amount: "100.00",
    currency: "EUR",
    requestedExecDate: "2026-08-20",
    selectedRail: "fedwire",
    creditorAccount: "DE89370400440532013000",
    creditorAgentBic: "DEUTDEFFXXX",
    creditorName: "Creditor SARL",
    chargeBearer: "SHAR",
    openCaseId: null,
    ...overrides,
  };
}

async function search(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("UETR"), "uetr-1");
  await user.click(screen.getByRole("button", { name: "Search" }));
}

describe("InstructionLookupScreen", () => {
  it("renders a result row without navigating anywhere on its own", async () => {
    server.use(http.get(`${BASE}/v1/instructions`, () => HttpResponse.json([instruction()])));
    const user = userEvent.setup();
    renderWithSession(<InstructionLookupScreen />, { username: "viewer1", roles: ["VIEWER"] });

    await search(user);

    expect(await screen.findAllByTestId("lookup-row")).toHaveLength(1);
  });

  it("navigates to the case detail screen when an open case exists", async () => {
    server.use(http.get(`${BASE}/v1/instructions`, () => HttpResponse.json([instruction({ openCaseId: "case-1" })])));
    const user = userEvent.setup();
    renderWithSession(
      <Routes>
        <Route path="/" element={<InstructionLookupScreen />} />
        <Route path="/cases/:caseId" element={<CaseDetailScreen />} />
      </Routes>,
      { username: "viewer1", roles: ["VIEWER"], route: "/" },
    );

    await search(user);
    const [row] = await screen.findAllByTestId("lookup-row");
    await user.click(row!);

    expect(await screen.findByText(/what broke/i)).toBeInTheDocument();
  });

  it("navigates to the instruction timeline when no case is open", async () => {
    server.use(http.get(`${BASE}/v1/instructions`, () => HttpResponse.json([instruction({ openCaseId: null })])));
    server.use(http.get(`${BASE}/v1/instructions/instr-1/timeline`, () => HttpResponse.json([])));
    const user = userEvent.setup();
    renderWithSession(
      <Routes>
        <Route path="/" element={<InstructionLookupScreen />} />
        <Route path="/instructions/:instructionId" element={<InstructionTimelineScreen />} />
      </Routes>,
      { username: "viewer1", roles: ["VIEWER"], route: "/" },
    );

    await search(user);
    const [row] = await screen.findAllByTestId("lookup-row");
    await user.click(row!);

    expect(await screen.findByText("E2E-0001")).toBeInTheDocument();
    expect(screen.getByText("No open case")).toBeInTheDocument();
  });
});
