import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { CaseDetailScreen } from "../screens/CaseDetailScreen";
import { server } from "../test/mocks/server";
import { renderWithSession } from "../test/test-utils";

/** Acceptance criterion 5: a 403 renders as a clear message, not a blank screen or a raw error. */
describe("403 handling", () => {
  it("renders the server's detail message instead of a blank screen or an unhandled exception", async () => {
    server.use(
      http.post("http://localhost:8084/v1/cases/case-1/reject", () =>
        HttpResponse.json({ error: "FORBIDDEN", detail: "Your role does not permit this action" }, { status: 403 }),
      ),
    );
    const user = userEvent.setup();
    renderWithSession(
      <Routes>
        <Route path="/cases/:caseId" element={<CaseDetailScreen />} />
      </Routes>,
      { username: "checker1", roles: ["CHECKER"], route: "/cases/case-1" },
    );

    await user.click(await screen.findByRole("button", { name: "Reject case" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Your role does not permit this action");
    // The rest of the screen is still there -- this is a banner, not a crash.
    expect(screen.getByText("Creditor SARL")).toBeInTheDocument();
  });
});
