import { screen } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { renderWithSession } from "../test/test-utils";
import { CaseDetailScreen } from "./CaseDetailScreen";

/**
 * Acceptance criterion 8: role gating matches server authorisation. This is
 * the client-side half only -- a usability affordance, not the real
 * control (see Layout.tsx and each screen's own comment); SecurityConfig's
 * @PreAuthorize annotations are what actually enforce it server-side.
 */
function renderCaseOne(username: string, roles: ("VIEWER" | "MAKER" | "CHECKER")[]) {
  return renderWithSession(
    <Routes>
      <Route path="/cases/:caseId" element={<CaseDetailScreen />} />
    </Routes>,
    { username, roles, route: "/cases/case-1" },
  );
}

describe("Role gating on case detail", () => {
  it("shows a viewer no action buttons", async () => {
    renderCaseOne("viewer1", ["VIEWER"]);
    await screen.findByText("Creditor SARL");

    expect(screen.queryByRole("button", { name: "Propose repair" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reject case" })).not.toBeInTheDocument();
    expect(screen.getByText(/only a maker can propose a repair/i)).toBeInTheDocument();
  });

  it("shows a maker the propose action but not approve/reject", async () => {
    renderCaseOne("maker1", ["MAKER"]);
    await screen.findByText("Creditor SARL");

    expect(screen.getByRole("button", { name: "Propose repair" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reject case" })).not.toBeInTheDocument();
  });

  it("shows a checker the reject action but not propose", async () => {
    renderCaseOne("checker1", ["CHECKER"]);
    await screen.findByText("Creditor SARL");

    expect(screen.getByRole("button", { name: "Reject case" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Propose repair" })).not.toBeInTheDocument();
  });
});
