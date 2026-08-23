#!/usr/bin/env python3
"""One-off experiment for the phase report's judgment question 2 (.notes/reports/PHASE-11
-REPORT.md section 9): how much does excluding BICs from the redacted payload cost accuracy?
Deliberately NOT part of the harness proper -- the brief is explicit that the result gets
thrown away and redaction stays as designed; this script exists only to produce that one number
honestly, with the comparison run under conditions as close to the real evaluation as possible.

The labelled dataset only stores the already-redacted classifierRequest (BICs were never
persisted -- that's what redaction means), so the BIC values injected here are reconstructed
from the generator's own source (EvaluationSetGeneratorPartA/B, EvaluationCaseGeneratorSupport's
`Fixture` defaults) rather than pulled from the dataset itself. Every category in the held-out
set uses a fixed, non-randomised BIC pair (see those files), so this reconstruction is exact,
not approximate.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from harness import classifier, metrics  # noqa: E402

# category -> (debtorAgentBic, creditorAgentBic), copied from EvaluationSetGeneratorPartA.java /
# EvaluationSetGeneratorPartB.java / EvaluationCaseGeneratorSupport.java's Fixture defaults.
# Every category not overriding one side keeps the Fixture default "DEUTDEFFXXX".
CATEGORY_BICS = {
    "debtor-iban-invalid": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
    "creditor-iban-invalid": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
    "debtor-bic-malformed": ("1234XXAB", "DEUTDEFFXXX"),
    "creditor-bic-malformed": ("DEUTDEFFXXX", "1234YYCD"),
    "currency-country-mismatch": ("DEUTDEFFXXX", "TESTUS33"),
    "requested-date-in-the-past": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
    "no-eligible-rail": ("DEUTDEFFXXX", "NWBKGB2LXXX"),
    "no-correspondent": ("DEUTDEFFXXX", "TESTGB22"),
    "no-nostro-account": ("DEUTDEFFXXX", "SCBLUS33XXX"),
    "rail-account-closed": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
    "rail-account-blocked": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
    "rail-insufficient-funds": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
    "rail-inconsistent-end-customer": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
    "rail-end-customer-deceased": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
    "rail-regulatory-reason": ("DEUTDEFFXXX", "DEUTDEFFXXX"),
}


def main():
    dataset_path = "labels/holdout.jsonl"
    baseline_report_path = "reports/baseline_holdout.json"
    out_path = "reports/bic_included_experiment_holdout.json"

    with open(dataset_path) as f:
        cases = [json.loads(line) for line in f if line.strip()]
    with open(baseline_report_path) as f:
        baseline_report = json.load(f)
    baseline_by_id = {c["case_id"]: c for c in baseline_report["cases"]}

    system_prompt = classifier.load_system_prompt("prompts/system_prompt.txt")
    runner = classifier.ClassifierRunner(model="claude-sonnet-4-6", system_prompt=system_prompt, cache_db_path="cache/responses.db")

    reason_pairs_baseline, reason_pairs_bic = [], []
    repair_pairs_baseline, repair_pairs_bic = [], []

    for i, case in enumerate(cases):
        case_id = case["caseId"]
        debtor_bic, creditor_bic = CATEGORY_BICS[case["category"]]
        augmented_payload = dict(case["classifierRequest"])
        augmented_payload["debtorAgentBic"] = debtor_bic
        augmented_payload["creditorAgentBic"] = creditor_bic

        proposal, _ = runner.classify(augmented_payload)
        pred_reason = proposal.reason_code if proposal else None
        pred_repair = proposal.repairability if proposal else None

        truth_reason = case["groundTruthReasonCode"]
        truth_repair = case["groundTruthRepairability"]
        reason_pairs_bic.append((truth_reason, pred_reason))
        repair_pairs_bic.append((truth_repair, pred_repair))

        base_row = baseline_by_id[case_id]
        reason_pairs_baseline.append((truth_reason, base_row["predicted_reason_code"]))
        repair_pairs_baseline.append((truth_repair, base_row["predicted_repairability"]))

        print(f"[{i + 1}/{len(cases)}] {case_id}: baseline_pred={base_row['predicted_reason_code']}/{base_row['predicted_repairability']} "
              f"bic_included_pred={pred_reason}/{pred_repair} truth={truth_reason}/{truth_repair}", file=sys.stderr)

    result = {
        "n_cases": len(cases),
        "baseline_redacted": {
            "reason_code_accuracy": metrics.overall_accuracy(reason_pairs_baseline),
            "reason_code_kappa": metrics.cohens_kappa(reason_pairs_baseline),
            "repairability_accuracy": metrics.overall_accuracy(repair_pairs_baseline),
            "repairability_kappa": metrics.cohens_kappa(repair_pairs_baseline),
        },
        "bic_included": {
            "reason_code_accuracy": metrics.overall_accuracy(reason_pairs_bic),
            "reason_code_kappa": metrics.cohens_kappa(reason_pairs_bic),
            "repairability_accuracy": metrics.overall_accuracy(repair_pairs_bic),
            "repairability_kappa": metrics.cohens_kappa(repair_pairs_bic),
        },
    }
    with open(out_path, "w") as f:
        json.dump(result, f, indent=2)

    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
