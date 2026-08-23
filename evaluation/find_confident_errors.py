#!/usr/bin/env python3
"""Finds confidently-wrong cases in an evaluation report (phase brief section 6/8): high
classifier confidence, incorrect proposal. Sorted by confidence descending -- the top of this
list is the case the phase report's "confidently wrong" analysis should be built from. If this
list is empty across the entire held-out set, that is itself the reportable finding (section 6:
"if never confidently wrong across 200 cases, say so and treat as suspicious" -- a classifier
that is never confidently wrong on a set this small is more likely under-confident everywhere
than actually perfect).

    python find_confident_errors.py --report reports/baseline_holdout.json --min-confidence 0.7
"""

from __future__ import annotations

import argparse
import json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True)
    parser.add_argument("--min-confidence", type=float, default=0.7)
    args = parser.parse_args()

    with open(args.report) as f:
        report = json.load(f)

    wrong_and_confident = [
        c
        for c in report["cases"]
        if c["confidence"] >= args.min_confidence and not (c["reason_code_correct"] and c["repairability_correct"])
    ]
    wrong_and_confident.sort(key=lambda c: c["confidence"], reverse=True)

    if not wrong_and_confident:
        print(f"No confidently-wrong cases found (threshold={args.min_confidence}) across {len(report['cases'])} cases.")
        print("Per phase brief section 6: this is suspicious, not reassuring -- treat as evidence of")
        print("systematic under-confidence rather than proof the classifier is never wrong.")
        return

    print(f"{len(wrong_and_confident)} confidently-wrong case(s) out of {len(report['cases'])}, threshold={args.min_confidence}:\n")
    for c in wrong_and_confident:
        print(f"case_id: {c['case_id']}  category: {c['category']}  confidence: {c['confidence']:.2f}")
        print(f"  ground truth:  reasonCode={c['ground_truth_reason_code']}  repairability={c['ground_truth_repairability']}")
        print(f"  predicted:     reasonCode={c['predicted_reason_code']}  repairability={c['predicted_repairability']}")
        print(f"  rationale: {c['rationale']}")
        print()


if __name__ == "__main__":
    main()
