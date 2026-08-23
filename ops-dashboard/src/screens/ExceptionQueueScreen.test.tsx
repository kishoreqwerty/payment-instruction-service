import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import * as data from "../test/mocks/data";
import { server } from "../test/mocks/server";
import { renderWithSession } from "../test/test-utils";
import { CaseDetailScreen } from "./CaseDetailScreen";
import { ExceptionQueueScreen } from "./ExceptionQueueScreen";

const BASE = "http://localhost:8084";

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

  it("shows the backend's true total match count, not the number of rows on the current page", async () => {
    // A page-1-of-many response: only 3 rows came back, but 106 cases actually match -- the
    // exact shape that read as "100 cases" (the hardcoded page size) before this fix, regardless
    // of how many actually matched.
    server.use(
      http.get(`${BASE}/v1/cases`, () =>
        HttpResponse.json({
          content: [data.openBusinessCase, data.pendingApprovalCase, data.investigationCase],
          totalElements: 106,
          totalPages: 2,
          number: 0,
          size: 100,
        }),
      ),
    );
    renderWithSession(<ExceptionQueueScreen />, { username: "viewer1", roles: ["VIEWER"] });

    expect(await screen.findByText("106 cases")).toBeInTheDocument();
    expect(screen.queryByText("3 cases")).not.toBeInTheDocument();
  });

  it("shows a smaller count once a filter narrows the match set, distinct from the unfiltered total", async () => {
    server.use(
      http.get(`${BASE}/v1/cases`, ({ request }) => {
        const reasonCode = new URL(request.url).searchParams.get("reasonCode");
        const matchesFilter = reasonCode === "AC01";
        return HttpResponse.json({
          content: [data.openBusinessCase],
          totalElements: matchesFilter ? 12 : 106,
          totalPages: matchesFilter ? 1 : 2,
          number: 0,
          size: 100,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithSession(<ExceptionQueueScreen />, { username: "viewer1", roles: ["VIEWER"] });

    expect(await screen.findByText("106 cases")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Reason code"), "AC01");

    expect(await screen.findByText("12 cases")).toBeInTheDocument();
    expect(screen.queryByText("106 cases")).not.toBeInTheDocument();
  });

  it("does not show pagination controls when everything fits on one page", async () => {
    renderWithSession(<ExceptionQueueScreen />, { username: "viewer1", roles: ["VIEWER"] });
    await screen.findAllByTestId("case-row");

    expect(screen.queryByText(/Page \d+ of \d+/)).not.toBeInTheDocument();
  });

  it("shows pagination controls across multiple pages and lets the operator move between them", async () => {
    server.use(
      http.get(`${BASE}/v1/cases`, ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get("page") ?? "0");
        return HttpResponse.json({
          content: [page === 0 ? data.openBusinessCase : data.pendingApprovalCase],
          totalElements: 106,
          totalPages: 2,
          number: page,
          size: 100,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithSession(<ExceptionQueueScreen />, { username: "viewer1", roles: ["VIEWER"] });

    expect(await screen.findByText("Page 1 of 2")).toBeInTheDocument();
    const previous = screen.getByRole("button", { name: "← Previous" });
    const next = screen.getByRole("button", { name: "Next →" });
    expect(previous).toBeDisabled();
    expect(next).not.toBeDisabled();

    await user.click(next);

    await waitFor(() => expect(screen.getByText("Page 2 of 2")).toBeInTheDocument());
    expect(previous).not.toBeDisabled();
    expect(next).toBeDisabled();
  });

  it("resets to the first page when a filter changes", async () => {
    server.use(
      http.get(`${BASE}/v1/cases`, ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get("page") ?? "0");
        return HttpResponse.json({
          content: [data.openBusinessCase],
          totalElements: 106,
          totalPages: 2,
          number: page,
          size: 100,
        });
      }),
    );
    const user = userEvent.setup();
    renderWithSession(<ExceptionQueueScreen />, { username: "viewer1", roles: ["VIEWER"] });

    await user.click(await screen.findByRole("button", { name: "Next →" }));
    await waitFor(() => expect(screen.getByText("Page 2 of 2")).toBeInTheDocument());

    await user.type(screen.getByLabelText("Reason code"), "AC01");

    await waitFor(() => expect(screen.getByText("Page 1 of 2")).toBeInTheDocument());
  });
});
