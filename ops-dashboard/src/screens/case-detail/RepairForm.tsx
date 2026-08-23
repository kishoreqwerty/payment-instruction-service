import { useEffect, useState } from "react";
import { useRepairableFields, useProposeRepair } from "../../api/queries";
import type { FieldChange, InstructionSummaryResponse } from "../../api/types";
import { ErrorBanner } from "../../components/ErrorBanner";

/** GET /v1/repairable-fields, not a hardcoded list -- see that endpoint's own javadoc for why. */
function currentValueFor(fieldPath: string, instruction: InstructionSummaryResponse): string {
  switch (fieldPath) {
    case "creditorAccount":
      return instruction.creditorAccount ?? "";
    case "creditorAgentBic":
      return instruction.creditorAgentBic ?? "";
    case "creditorName":
      return instruction.creditorName ?? "";
    case "chargeBearer":
      return instruction.chargeBearer ?? "";
    case "requestedExecutionDate":
      return instruction.requestedExecDate ?? "";
    default:
      return "";
  }
}

/**
 * Acceptance criteria 2 and 3: only allowlisted fields are offered, current
 * and proposed values sit side by side, and submitting *proposes* -- the
 * button says exactly that, never "Save," because a maker believing a
 * proposal already took effect is a real operational failure (brief §3).
 */
export function RepairForm({
  caseId,
  instruction,
  suggestion,
}: {
  caseId: string;
  instruction: InstructionSummaryResponse;
  /** Set when the operator clicks "Use this suggestion" on ClassifierProposalPanel -- pre-fills
   * and pre-checks that one field. Still just a starting point: the operator can clear the
   * checkbox or edit the value like any other field before proposing. */
  suggestion?: { fieldPath: string; value: string } | null;
}) {
  const { data: fields } = useRepairableFields();
  const proposeRepair = useProposeRepair();
  const [selected, setSelected] = useState<Record<string, boolean>>(() => (suggestion ? { [suggestion.fieldPath]: true } : {}));
  const [values, setValues] = useState<Record<string, string>>(() => (suggestion ? { [suggestion.fieldPath]: suggestion.value } : {}));

  // RepairForm is already mounted (case OPEN, maker signed in) by the time the operator clicks
  // "Use this suggestion" on the panel above it -- the useState initializers above only cover a
  // suggestion present at first render, so this effect covers the actual, later click.
  useEffect(() => {
    if (!suggestion) return;
    setSelected((s) => ({ ...s, [suggestion.fieldPath]: true }));
    setValues((v) => ({ ...v, [suggestion.fieldPath]: suggestion.value }));
  }, [suggestion]);

  function toggle(fieldPath: string) {
    setSelected((s) => ({ ...s, [fieldPath]: !s[fieldPath] }));
  }

  function handleSubmit() {
    const changes: FieldChange[] = (fields ?? [])
      .filter((f) => selected[f.fieldPath])
      .map((f) => ({ fieldPath: f.fieldPath, newValue: values[f.fieldPath] ?? "" }));
    if (changes.length === 0) return;
    proposeRepair.mutate({ caseId, changes });
  }

  const anySelected = Object.values(selected).some(Boolean);

  return (
    <div className="panel">
      <h2>Repair form</h2>
      <ErrorBanner error={proposeRepair.error} />
      {(fields ?? []).map((field) => (
        <div className="repair-form-row" key={field.fieldPath}>
          <label htmlFor={`repair-${field.fieldPath}`}>
            <input
              type="checkbox"
              id={`repair-${field.fieldPath}`}
              checked={Boolean(selected[field.fieldPath])}
              onChange={() => toggle(field.fieldPath)}
            />
            {field.label}
          </label>
          <span className="current-value">{currentValueFor(field.fieldPath, instruction) || "(empty)"}</span>
          <input
            aria-label={`Proposed ${field.label}`}
            placeholder="Proposed value"
            disabled={!selected[field.fieldPath]}
            value={values[field.fieldPath] ?? ""}
            onChange={(e) => setValues((v) => ({ ...v, [field.fieldPath]: e.target.value }))}
          />
        </div>
      ))}
      <p className="propose-warning">
        Submitting proposes this change for a checker's approval. It does not apply to the payment until approved.
      </p>
      <div className="form-actions">
        <button type="button" className="btn btn-primary" disabled={!anySelected || proposeRepair.isPending} onClick={handleSubmit}>
          {proposeRepair.isPending ? "Proposing…" : "Propose repair"}
        </button>
      </div>
    </div>
  );
}
