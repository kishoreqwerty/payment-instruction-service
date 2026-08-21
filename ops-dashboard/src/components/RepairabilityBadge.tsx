import type { Repairability } from "../api/types";

const LABELS: Record<Repairability, string> = {
  REPAIRABLE: "Repairable",
  STATIC_DATA: "Static data",
  TRANSIENT: "Transient",
  UNREPAIRABLE: "Unrepairable",
};

/**
 * Four values, one dot color each (not four shades of one hue) plus the
 * text label -- brief §4 asks whether a four-value axis can be scannable
 * without reading the label. A colored dot alone gets an operator to "this
 * is the red one" at a glance; the label stays for the value itself and for
 * anyone who can't distinguish the hues. See PHASE-9-REPORT.md §5 for why
 * color-plus-label, not color-only, was the answer.
 */
export function RepairabilityBadge({ value }: { value: Repairability }) {
  return (
    <span className={`badge badge-${value.toLowerCase()}`}>
      <span className="dot" aria-hidden="true" />
      {LABELS[value]}
    </span>
  );
}
