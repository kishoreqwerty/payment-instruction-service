"""Regression gate: is a candidate prompt/model materially worse than the stored baseline
(Phase 11 section 5). `mcnemar_test` is the same implementation used by the Agent Trajectory
Harness (src/harness/regression.py) for paired binary comparisons -- reused, not reinvented.

Two kinds of check, per the phase brief:
1. Absolute thresholds on the *candidate* run alone (kappa >= 0.80 on held-out).
2. Relative thresholds against a *stored baseline* run (no per-class recall dropping more than
   5 points), backed by McNemar's test so a single-case wobble doesn't masquerade as "the prompt
   got worse" -- McNemar isolates the *discordant* pairs (cases one run got right and the other
   didn't), which is exactly what "real change vs. noise" means for a paired comparison like this.
"""

from __future__ import annotations


def mcnemar_test(b: int, c: int) -> dict:
    """`b` = pairs where baseline was wrong and candidate was right; `c` = pairs where baseline
    was right and candidate was wrong. Concordant pairs (both right or both wrong) carry no
    information about a shift and are excluded -- that's McNemar's whole point.

    Uses the exact binomial test when there are fewer than 25 discordant pairs (the chi-square
    approximation is unreliable with few of them), otherwise chi-square with continuity
    correction.
    """
    n = b + c
    if n == 0:
        return {"b": b, "c": c, "n_discordant": 0, "statistic": None, "p_value": 1.0, "method": "undefined_no_discordant_pairs"}
    if n < 25:
        from scipy.stats import binomtest

        result = binomtest(min(b, c), n, 0.5, alternative="two-sided")
        return {"b": b, "c": c, "n_discordant": n, "statistic": None, "p_value": result.pvalue, "method": "exact_binomial"}
    from scipy.stats import chi2

    statistic = (abs(b - c) - 1) ** 2 / n
    p_value = 1 - chi2.cdf(statistic, df=1)
    return {"b": b, "c": c, "n_discordant": n, "statistic": statistic, "p_value": p_value, "method": "chi_square_continuity_corrected"}


def mcnemar_from_case_results(baseline_correct: dict, candidate_correct: dict) -> dict:
    """`baseline_correct`/`candidate_correct`: {case_id: bool}, whether that run's reason-code
    proposal matched ground truth. Only case_ids present in both are paired."""
    shared = set(baseline_correct) & set(candidate_correct)
    b = sum(1 for cid in shared if not baseline_correct[cid] and candidate_correct[cid])
    c = sum(1 for cid in shared if baseline_correct[cid] and not candidate_correct[cid])
    return mcnemar_test(b, c)


def per_class_recall_regressions(baseline_per_class: dict, candidate_per_class: dict, max_regression: float) -> list[dict]:
    """Every class where candidate recall dropped by more than `max_regression` versus the
    baseline. A class absent from the candidate's per-class report (zero support, shouldn't
    happen if the same dataset is used) is skipped rather than treated as a 100% drop."""
    regressions = []
    for cls, baseline_row in baseline_per_class.items():
        candidate_row = candidate_per_class.get(cls)
        if candidate_row is None:
            continue
        drop = baseline_row["recall"] - candidate_row["recall"]
        if drop > max_regression:
            regressions.append(
                {
                    "class": cls,
                    "baseline_recall": baseline_row["recall"],
                    "candidate_recall": candidate_row["recall"],
                    "drop": drop,
                    "support": baseline_row["support"],
                }
            )
    return regressions


_FIELDS = ("reason_code", "repairability")


def _field_correct_key(field: str) -> str:
    return f"{field}_correct"


def evaluate_gate(candidate_report: dict, baseline_report: dict | None, gate_config: dict) -> dict:
    """The full gate decision, checked separately for reason code and repairability (phase brief
    section 5). `candidate_report`/`baseline_report` are the JSON produced by `run_evaluation.py`
    (see that script's `--out`). `baseline_report` may be `None` on the very first run ever
    (nothing to regress against yet) -- in that case only the absolute kappa thresholds apply,
    and that is reported explicitly rather than silently passing every relative check.

    Every check here uses `excluding_ambiguous` (`run_evaluation.py`'s per-field sub-report with
    labeller-flagged-ambiguous cases removed) rather than the raw numbers -- gating on cases the
    labeller themselves found genuinely arguable would punish honest ambiguity-labelling, not
    catch real regressions. `cases` rows are filtered by their own `ambiguous` flag the same way
    before feeding McNemar's test, for the same reason.
    """
    reasons = []
    passed = True
    per_field = {}

    for field in _FIELDS:
        min_kappa = gate_config["min_kappa_holdout"][field]
        candidate_field = candidate_report[field]["excluding_ambiguous"]
        candidate_kappa = candidate_field["cohens_kappa"]
        kappa_passed = candidate_kappa >= min_kappa
        if not kappa_passed:
            passed = False
            reasons.append(f"{field} kappa (excluding ambiguous) {candidate_kappa:.3f} < required {min_kappa}")

        field_result = {
            "kappa_check": {"value": candidate_kappa, "threshold": min_kappa, "passed": kappa_passed},
            "recall_regressions": [],
            "mcnemar": None,
        }

        if baseline_report is not None:
            baseline_field = baseline_report[field]["excluding_ambiguous"]
            max_recall_regression = gate_config["max_per_class_recall_regression"]
            regressions = per_class_recall_regressions(baseline_field["per_class"], candidate_field["per_class"], max_recall_regression)
            field_result["recall_regressions"] = regressions
            if regressions:
                passed = False
                for r in regressions:
                    reasons.append(
                        f"{field} {r['class']} recall dropped {r['drop']:.3f} (baseline {r['baseline_recall']:.3f} -> "
                        f"candidate {r['candidate_recall']:.3f})"
                    )

            correct_key = _field_correct_key(field)
            baseline_correct = {row["case_id"]: row[correct_key] for row in baseline_report["cases"] if not row["ambiguous"]}
            candidate_correct = {row["case_id"]: row[correct_key] for row in candidate_report["cases"] if not row["ambiguous"]}
            mcnemar_result = mcnemar_from_case_results(baseline_correct, candidate_correct)
            field_result["mcnemar"] = mcnemar_result
            significance_level = gate_config.get("mcnemar_significance_level", 0.05)
            if regressions and mcnemar_result["p_value"] is not None and mcnemar_result["p_value"] < significance_level:
                reasons.append(
                    f"{field}: McNemar's test judges the regression significant "
                    f"(p={mcnemar_result['p_value']:.4f} < {significance_level}), not noise"
                )

        per_field[field] = field_result

    return {"passed": passed, "reasons": reasons, **per_field}
