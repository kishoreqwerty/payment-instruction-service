import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { server } from "../test/mocks/server";
import { renderWithSession } from "../test/test-utils";
import { CaseDetailScreen } from "./CaseDetailScreen";
import { ExceptionQueueScreen } from "./ExceptionQueueScreen";

describe("ExceptionQueueScreen", () => {
  it("offers filters for stage, reason code, repairability, assignee, and resolution status; sorts on age", async () => {
    renderWithSession(<ExceptionQueueScreen />, { username: "viewer1", roles: ["VIEWER"] });
    await screen.findAllByTestId("case-row");

    expect(screen.getByLabelText("Stage")).toBeInTheDocument();
    expect(screen.getByLabelText("Reason code")).toBeInTheDocument();
    expect(screen.getByLabelText("Repairability")).toBeInTheDocument();
    expect(screen.getByLabelText("Assignee")).toBeInTheDocument();
    expect(screen.getByLabelText("Resolution status")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Age/ })).toBeInTheDocument();
  });

  it("toggles the age sort direction on click", async () => {
    const user = userEvent.setup();
    renderWithSession(<ExceptionQueueScreen />, { username: "viewer1", roles: ["VIEWER"] });
    await screen.findAllByTestId("case-row");

    const sortButton = screen.getByRole("button", { name: /Age/ });
    expect(sortButton).toHaveTextContent("↑");
    await user.click(sortButton);
    expect(sortButton).toHaveTextContent("↓");
  });

  it("navigates to the case detail screen when a row is clicked", async () => {
    const user = userEvent.setup();
    renderWithSession(
      <Routes>
        <Route path="/" element={<ExceptionQueueScreen />} />
        <Route path="/cases/:caseId" element={<CaseDetailScreen />} />
      </Routes>,
      { username: "viewer1", roles: ["VIEWER"], route: "/" },
    );
    const [firstRow] = await screen.findAllByTestId("case-row");
    await user.click(firstRow!);

    expect(await screen.findByText(/what broke/i)).toBeInTheDocument();
  });

  it("renders an intentional empty state, not a bare empty table, when no cases match", async () => {
    server.use(
      http.get("http://localhost:8084/v1/cases", () => HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 })),
    );
    renderWithSession(<ExceptionQueueScreen />, { username: "viewer1", roles: ["VIEWER"] });

    expect(await screen.findByText(/no cases match these filters/i)).toBeInTheDocument();
  });
});
