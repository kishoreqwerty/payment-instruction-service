import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { TimelineEntry } from "../../api/types";
import { Timeline } from "./Timeline";

const entries: TimelineEntry[] = [
  { type: "STATE_TRANSITION", occurredAt: "2026-08-20T09:00:00Z", actor: "SYSTEM:system", fromState: null, toState: "RECEIVED", reasonCode: null, reasonDetail: null, fieldPath: null, oldValue: null, newValue: null },
  { type: "STATE_TRANSITION", occurredAt: "2026-08-20T09:01:00Z", actor: "SYSTEM:system", fromState: "RECEIVED", toState: "EXCEPTION", reasonCode: "AC01", reasonDetail: "invalid IBAN checksum", fieldPath: null, oldValue: null, newValue: null },
  { type: "REPAIR_PROPOSED", occurredAt: "2026-08-20T10:00:00Z", actor: "maker1", fromState: null, toState: null, reasonCode: null, reasonDetail: null, fieldPath: "creditorName", oldValue: "Old", newValue: "New" },
  { type: "REPAIR_APPROVED", occurredAt: "2026-08-20T10:05:00Z", actor: "checker1", fromState: null, toState: null, reasonCode: null, reasonDetail: null, fieldPath: "creditorName", oldValue: "Old", newValue: "New" },
];

describe("Timeline", () => {
  it("renders transitions in sequence with repair actions interleaved in the given order", () => {
    render(<Timeline entries={entries} />);

    const rows = screen.getAllByText(/./, { selector: ".timeline-entry .detail" });
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining("Opened at RECEIVED"),
      expect.stringContaining("RECEIVED → EXCEPTION"),
      expect.stringContaining('Proposed repair: creditorName "Old" → "New"'),
      expect.stringContaining('Approved repair: creditorName → "New"'),
    ]);
  });

  it("shows the reason code alongside a state transition", () => {
    render(<Timeline entries={entries} />);
    expect(screen.getByText("AC01")).toBeInTheDocument();
  });

  it("renders an intentional empty state, not a blank list, when there is no history", () => {
    render(<Timeline entries={[]} />);
    expect(screen.getByText(/no history yet/i)).toBeInTheDocument();
  });
});
