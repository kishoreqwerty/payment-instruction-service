import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../test/mocks/server";
import { renderWithSession } from "../test/test-utils";
import { ApprovalQueueScreen } from "./ApprovalQueueScreen";

const actionProposedByChecker1 = {
  actionId: "action-self",
  caseId: "case-9",
  fieldPath: "creditorName",
  oldValue: "Old",
  newValue: "New",
  proposedBy: "checker1",
  proposedAt: new Date().toISOString(),
  approvedBy: null,
  approvedAt: null,
};

function mockPendingWith(action: typeof actionProposedByChecker1) {
  server.use(
    http.get("http://localhost:8084/v1/repairs/pending", () => HttpResponse.json([action])),
    http.get("http://localhost:8084/v1/investigation-confirmations/pending", () => HttpResponse.json([])),
  );
}

describe("ApprovalQueueScreen", () => {
  it("shows the diff for a pending proposal without navigating to the case", async () => {
    mockPendingWith({ ...actionProposedByChecker1, proposedBy: "maker1" });
    renderWithSession(<ApprovalQueueScreen />, { username: "checker2", roles: ["CHECKER"] });

    expect(await screen.findByText("Old")).toBeInTheDocument();
    expect(screen.getByText("New")).toBeInTheDocument();
    expect(screen.getByText("creditorName:")).toBeInTheDocument();
  });

  it("hides the approve action when the signed-in checker proposed it themselves", async () => {
    mockPendingWith(actionProposedByChecker1);
    renderWithSession(<ApprovalQueueScreen />, { username: "checker1", roles: ["CHECKER"] });

    await screen.findByText("Old");
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.getByText(/a different checker must approve it/i)).toBeInTheDocument();
  });

  it("shows the approve action for a different checker", async () => {
    mockPendingWith(actionProposedByChecker1);
    renderWithSession(<ApprovalQueueScreen />, { username: "checker2", roles: ["CHECKER"] });

    await screen.findByText("Old");
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
  });

  it("renders an intentional empty state, not a bare empty page, when nothing is pending", async () => {
    server.use(
      http.get("http://localhost:8084/v1/repairs/pending", () => HttpResponse.json([])),
      http.get("http://localhost:8084/v1/investigation-confirmations/pending", () => HttpResponse.json([])),
    );
    renderWithSession(<ApprovalQueueScreen />, { username: "checker1", roles: ["CHECKER"] });

    expect(await screen.findByText(/nothing waiting on approval/i)).toBeInTheDocument();
  });
});
