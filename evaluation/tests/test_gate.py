"""Proves the regression gate actually blocks -- not just that its functions compute a number.
Uses synthetic case-level reports (no live API calls, no network) so this runs in CI on every
push with no ANTHROPIC_API_KEY needed. The real, live-API demonstration against an actually
degraded prompt (phase brief section 5/8: "a deliberately degraded prompt fails the CI gate") is
run separately and recorded in the phase report; this test is what keeps that guarantee from
silently rotting on the next change to gate.py.

Every synthetic report here is built with zero ambiguous cases, so "excluding_ambiguous" and
"all cases" are identical -- the ambiguous-filtering behavior itself (real production data does
have some) is exercised by the live report checked in at reports/baseline_holdout.json, not
re-derived from scratch here.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from harness import metrics, regression

GATE_CONFIG = {
    "min_kappa_holdout": {"reason_code": 0.80, "repairability": 0.80},
    "max_per_class_recall_regression": 0.05,
    "mcnemar_significance_level": 0.05,
}


def _field_report(pairs: list[tuple]) -> dict:
    categories = sorted({t for t, _ in pairs} | {p for _, p in pairs}, key=str)
    report = {
        "accuracy": metrics.overall_accuracy(pairs),
        "cohens_kappa": metrics.cohens_kappa(pairs),
        "confusion_matrix": metrics.confusion_matrix(pairs, categories),
        "per_class": metrics.per_class_precision_recall_f1(pairs),
    }
    report["excluding_ambiguous"] = report  # no ambiguous cases in these synthetic fixtures
    return report


def _report_from_pairs(reason_pairs: list[tuple], repair_pairs: list[tuple], case_ids: list[str]) -> dict:
    """Builds a minimal run_evaluation.py-shaped report from parallel (truth, predicted) pairs
    for both fields. `repair_pairs` defaults to a trivially-perfect repairability signal when the
    test only cares about reason-code behavior."""
    return {
        "reason_code": _field_report(reason_pairs),
        "repairability": _field_report(repair_pairs),
        "cases": [
            {"case_id": cid, "reason_code_correct": rt == rp, "repairability_correct": pt == pp, "ambiguous": False}
            for cid, (rt, rp), (pt, pp) in zip(case_ids, reason_pairs, repair_pairs, strict=True)
        ],
    }


def _make_dataset(n_per_class: int = 20) -> list[str]:
    classes = ["AC01", "RC01", "AC04", "MD07", "RR04"]
    truths = []
    for cls in classes:
        truths.extend([cls] * n_per_class)
    return truths


def _perfect_repair_pairs(n: int) -> list[tuple]:
    return [("REPAIRABLE", "REPAIRABLE")] * n


def test_gate_passes_when_candidate_matches_baseline():
    truths = _make_dataset()
    case_ids = [f"case-{i}" for i in range(len(truths))]
    predicted = [t if i % 20 != 0 else "AC01" for i, t in enumerate(truths)]  # 95% correct, uniformly
    pairs = list(zip(truths, predicted, strict=True))
    repair_pairs = _perfect_repair_pairs(len(truths))
    baseline = _report_from_pairs(pairs, repair_pairs, case_ids)
    candidate = _report_from_pairs(pairs, repair_pairs, case_ids)  # identical run

    result = regression.evaluate_gate(candidate, baseline, GATE_CONFIG)
    assert result["passed"] is True
    assert result["reasons"] == []


def test_gate_fails_on_absolute_kappa_floor():
    truths = _make_dataset()
    case_ids = [f"case-{i}" for i in range(len(truths))]
    predicted = ["AC01"] * len(truths)  # ignores the input -- kappa near zero
    pairs = list(zip(truths, predicted, strict=True))
    candidate = _report_from_pairs(pairs, _perfect_repair_pairs(len(truths)), case_ids)

    result = regression.evaluate_gate(candidate, None, GATE_CONFIG)
    assert result["passed"] is False
    assert any("reason_code kappa" in r for r in result["reasons"])


def test_gate_fails_on_degraded_candidate_vs_baseline():
    """The core demonstration: a baseline that's strong everywhere, and a "candidate" (standing
    in for a deliberately degraded prompt) that has collapsed specifically on one thin-support
    class (RR04) while staying strong elsewhere -- exactly the failure mode per-class recall
    checking exists to catch, and that a single blended-accuracy number would hide."""
    truths = _make_dataset(n_per_class=20)
    case_ids = [f"case-{i}" for i in range(len(truths))]

    baseline_pairs = list(zip(truths, truths, strict=True))  # 100% correct baseline
    baseline = _report_from_pairs(baseline_pairs, _perfect_repair_pairs(len(truths)), case_ids)

    # Candidate: still perfect on every class except RR04, where it now always guesses AC01 --
    # RR04 recall collapses from 1.0 to 0.0, a 100-point drop, while overall accuracy only drops
    # from 100% to 80% (1 of 5 classes wrecked) -- the kind of regression a single aggregate
    # number would badly understate.
    candidate_predicted = [("AC01" if t == "RR04" else t) for t in truths]
    candidate_pairs = list(zip(truths, candidate_predicted, strict=True))
    candidate = _report_from_pairs(candidate_pairs, _perfect_repair_pairs(len(truths)), case_ids)

    result = regression.evaluate_gate(candidate, baseline, GATE_CONFIG)

    assert result["passed"] is False
    reason_code_result = result["reason_code"]
    assert len(reason_code_result["recall_regressions"]) == 1
    assert reason_code_result["recall_regressions"][0]["class"] == "RR04"
    assert reason_code_result["recall_regressions"][0]["drop"] == 1.0
    assert reason_code_result["mcnemar"] is not None
    assert reason_code_result["mcnemar"]["p_value"] < 0.05
    assert any("RR04" in r for r in result["reasons"])


def test_gate_fails_on_repairability_regression_alone():
    """Repairability is checked independently of reason code -- a prompt change that leaves
    reason-code classification untouched but wrecks repairability judgment (exactly what
    happened in the real degraded-prompt run behind this phase's report) must still fail."""
    truths = _make_dataset()
    case_ids = [f"case-{i}" for i in range(len(truths))]
    perfect_reason_pairs = list(zip(truths, truths, strict=True))

    baseline_repair = [("UNREPAIRABLE", "UNREPAIRABLE")] * len(truths)
    baseline = _report_from_pairs(perfect_reason_pairs, baseline_repair, case_ids)

    candidate_repair = [("UNREPAIRABLE", "REPAIRABLE")] * len(truths)  # always guesses REPAIRABLE
    candidate = _report_from_pairs(perfect_reason_pairs, candidate_repair, case_ids)

    result = regression.evaluate_gate(candidate, baseline, GATE_CONFIG)
    assert result["passed"] is False
    assert result["reason_code"]["kappa_check"]["passed"] is True
    assert result["repairability"]["kappa_check"]["passed"] is False
    assert any("repairability" in r for r in result["reasons"])


def test_gate_with_no_baseline_skips_relative_checks():
    truths = _make_dataset()
    case_ids = [f"case-{i}" for i in range(len(truths))]
    pairs = list(zip(truths, truths, strict=True))  # perfect
    candidate = _report_from_pairs(pairs, _perfect_repair_pairs(len(truths)), case_ids)

    result = regression.evaluate_gate(candidate, None, GATE_CONFIG)
    assert result["passed"] is True
    assert result["reason_code"]["mcnemar"] is None
    assert result["reason_code"]["recall_regressions"] == []
