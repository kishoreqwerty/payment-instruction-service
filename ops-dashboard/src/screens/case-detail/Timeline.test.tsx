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

  /**
   * The longest actor string the real system actually produces: "SYSTEM:" plus
   * AmbiguityResolver's own ACTOR_ID ("settlement-gateway:reconciliation"), 41 characters, longer
   * than the "SYSTEM:processing-service:validation" (37) row that first exposed this. Vitest runs
   * with `css: false` (vite.config.ts), so this can't assert on rendered layout/overlap directly
   * -- what it guards is the DOM-level content model the CSS fix (.timeline-entry .actor's
   * `min-width: 0` + `overflow-wrap: anywhere`, global.css) depends on: the actor and its row's
   * detail text stay two distinct, fully-intact nodes rather than one clipping or swallowing the
   * other, for every row length the system can actually produce, not just the short ones.
   */
  it("keeps a long actor string and its row's own detail as two distinct, fully-intact nodes", () => {
    const longActor = "SYSTEM:settlement-gateway:reconciliation";
    const longEntries: TimelineEntry[] = [
      {
        type: "STATE_TRANSITION",
        occurredAt: "2026-08-20T09:01:00Z",
        actor: longActor,
        fromState: "SENT_UNCONFIRMED",
        toState: "SENT",
        reasonCode: null,
        reasonDetail: null,
        fieldPath: null,
        oldValue: null,
        newValue: null,
      },
    ];
    render(<Timeline entries={longEntries} />);

    const actorNode = screen.getByText(longActor);
    expect(actorNode).toHaveClass("actor");
    expect(actorNode.textContent).toBe(longActor);

    const detailNode = screen.getByText(/SENT_UNCONFIRMED → SENT/);
    expect(detailNode).toHaveClass("detail");
    // Two separate elements, not one node's text bleeding into the other's -- the failure mode
    // an unclipped, non-wrapping actor column produced visually (though not as a DOM merge).
    expect(actorNode).not.toBe(detailNode);
  });
});
