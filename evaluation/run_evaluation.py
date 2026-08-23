#!/usr/bin/env python3
"""Runs the classifier against a labelled dataset and writes a full metrics report.

    python run_evaluation.py --dataset labels/holdout.jsonl --model claude-sonnet-4-6 \\
        --system-prompt prompts/baseline_system_prompt.txt --cache cache/responses.db \\
        --out reports/baseline_holdout.json

Every classifier call is cached by input hash (harness.cache) -- re-running this script with the
same model/prompt/dataset makes zero new API calls. A case the classifier abstains on (malformed
response, or `repairability` missing/invalid) is scored as incorrect on both fields except where
noted, never dropped from the denominator -- an abstention is a real outcome the metrics have to
account for, not a case that didn't happen.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from harness import calibration, classifier, metrics  # noqa: E402


def load_dataset(path: str) -> list[dict]:
    cases = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases


def run(dataset_path: str, model: str, system_prompt_path: str, cache_db_path: str, verbose: bool = True) -> dict:
    cases = load_dataset(dataset_path)
    system_prompt = classifier.load_system_prompt(system_prompt_path)
    runner = classifier.ClassifierRunner(model=model, system_prompt=system_prompt, cache_db_path=cache_db_path)

    reason_code_pairs = []
    repairability_pairs = []
    ambiguous_flags = []
    calibration_records = []
    case_rows = []
    n_cache_hits = 0
    n_abstained = 0

    for i, case in enumerate(cases):
        case_id = case["caseId"]
        truth_reason = case["groundTruthReasonCode"]
        truth_repair = case["groundTruthRepairability"]
        request_payload = case["classifierRequest"]

        proposal, was_cached = runner.classify(request_payload)
        if was_cached:
            n_cache_hits += 1
        if not was_cached:
            # Real API calls only -- be polite to the rate limiter. Cache hits need no delay.
            time.sleep(0.05)

        if proposal is None:
            n_abstained += 1
            predicted_reason, predicted_repair, confidence = None, None, 0.0
        else:
            predicted_reason, predicted_repair, confidence = proposal.reason_code, proposal.repairability, proposal.confidence

        reason_code_pairs.append((truth_reason, predicted_reason))
        repairability_pairs.append((truth_repair, predicted_repair))
        ambiguous_flags.append(case["ambiguous"])

        reason_correct = truth_reason == predicted_reason
        repair_correct = truth_repair == predicted_repair
        if proposal is not None:
            calibration_records.append({"confidence": confidence, "correct": reason_correct and repair_correct})

        case_rows.append(
            {
                "case_id": case_id,
                "category": case["category"],
                "ambiguous": case["ambiguous"],
                "ground_truth_reason_code": truth_reason,
                "ground_truth_repairability": truth_repair,
                "predicted_reason_code": predicted_reason,
                "predicted_repairability": predicted_repair,
                "confidence": confidence,
                "rationale": proposal.rationale if proposal else None,
                "suggested_field": proposal.suggested_field if proposal else None,
                "suggested_value": proposal.suggested_value if proposal else None,
                "reason_code_correct": reason_correct,
                "repairability_correct": repair_correct,
                "abstained": proposal is None,
            }
        )

        if verbose:
            status = "ABSTAIN" if proposal is None else ("OK" if reason_correct and repair_correct else "WRONG")
            print(f"[{i + 1}/{len(cases)}] {case_id}: truth={truth_reason}/{truth_repair} "
                  f"pred={predicted_reason}/{predicted_repair} conf={confidence:.2f} [{status}]", file=sys.stderr)

    def build_field_report(pairs: list[tuple]) -> dict:
        categories = sorted({t for t, _ in pairs} | {p for _, p in pairs}, key=str)
        report = {
            "accuracy": metrics.overall_accuracy(pairs),
            "cohens_kappa": metrics.cohens_kappa(pairs),
            "confusion_matrix": metrics.confusion_matrix(pairs, categories),
            "per_class": metrics.per_class_precision_recall_f1(pairs),
            "n": len(pairs),
        }
        report["macro_precision"] = metrics.macro_average(report["per_class"], "precision")
        report["macro_recall"] = metrics.macro_average(report["per_class"], "recall")
        report["macro_f1"] = metrics.macro_average(report["per_class"], "f1")
        return report

    # Every metric is also reported excluding cases the labeller themselves flagged ambiguous
    # (case_rows[i]["ambiguous"], set at labelling time -- see labels/*.jsonl and the phase
    # report section 4) -- disagreement between the classifier and a label the labeller
    # themselves found genuinely arguable is not the same evidence of classifier error that
    # disagreement on a clean case is (the same principle the Agent Trajectory Harness's
    # `high_confidence_only` filter applies to judge-vs-human agreement). The gate (regression.py)
    # is checked against the excluding-ambiguous numbers for exactly this reason -- gating on the
    # all-cases number would make the gate punish honest ambiguity-labelling instead of real
    # regressions.
    unambiguous_reason_pairs = [p for p, amb in zip(reason_code_pairs, ambiguous_flags, strict=True) if not amb]
    unambiguous_repair_pairs = [p for p, amb in zip(repairability_pairs, ambiguous_flags, strict=True) if not amb]

    reason_code_report = build_field_report(reason_code_pairs)
    reason_code_report["excluding_ambiguous"] = build_field_report(unambiguous_reason_pairs)

    repairability_report = build_field_report(repairability_pairs)
    repairability_report["excluding_ambiguous"] = build_field_report(unambiguous_repair_pairs)

    return {
        "dataset": dataset_path,
        "model": model,
        "system_prompt_path": system_prompt_path,
        "n_cases": len(cases),
        "n_cache_hits": n_cache_hits,
        "n_abstained": n_abstained,
        "reason_code": reason_code_report,
        "repairability": repairability_report,
        "calibration": calibration.calibration_report(calibration_records),
        "cases": case_rows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--system-prompt", required=True)
    parser.add_argument("--cache", default="cache/responses.db")
    parser.add_argument("--out", required=True)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    report = run(args.dataset, args.model, args.system_prompt, args.cache, verbose=not args.quiet)

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(report, f, indent=2)

    rc, rp = report["reason_code"], report["repairability"]
    print(f"\nWrote report to {args.out}", file=sys.stderr)
    print(f"reason_code:   accuracy={rc['accuracy']:.3f} kappa={rc['cohens_kappa']:.3f}  "
          f"(excl. ambiguous: kappa={rc['excluding_ambiguous']['cohens_kappa']:.3f}, n={rc['excluding_ambiguous']['n']})", file=sys.stderr)
    print(f"repairability: accuracy={rp['accuracy']:.3f} kappa={rp['cohens_kappa']:.3f}  "
          f"(excl. ambiguous: kappa={rp['excluding_ambiguous']['cohens_kappa']:.3f}, n={rp['excluding_ambiguous']['n']})", file=sys.stderr)
    print(f"cache hits: {report['n_cache_hits']}/{report['n_cases']}  abstained: {report['n_abstained']}/{report['n_cases']}", file=sys.stderr)


if __name__ == "__main__":
    main()
