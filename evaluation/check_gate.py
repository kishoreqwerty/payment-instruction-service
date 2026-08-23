#!/usr/bin/env python3
"""CI entrypoint: fails (exit 1) if a candidate evaluation report violates gate.yaml's thresholds
against the stored baseline report. Never makes an API call itself -- both reports are already on
disk (produced ahead of time by run_evaluation.py), so this is pure comparison logic and runs in
seconds in CI with no ANTHROPIC_API_KEY needed.

    python check_gate.py --candidate reports/candidate_holdout.json \\
        --baseline reports/baseline_holdout.json --config gate.yaml
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))

from harness import regression  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--baseline", default=None, help="Omit on the very first run ever (nothing to regress against yet)")
    parser.add_argument("--config", default="gate.yaml")
    args = parser.parse_args()

    with open(args.candidate) as f:
        candidate_report = json.load(f)
    baseline_report = None
    if args.baseline and Path(args.baseline).exists():
        with open(args.baseline) as f:
            baseline_report = json.load(f)
    with open(args.config) as f:
        gate_config = yaml.safe_load(f)

    result = regression.evaluate_gate(candidate_report, baseline_report, gate_config)

    print(json.dumps(result, indent=2))
    if result["passed"]:
        print("\nGATE PASSED", file=sys.stderr)
        return 0
    print("\nGATE FAILED:", file=sys.stderr)
    for reason in result["reasons"]:
        print(f"  - {reason}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
