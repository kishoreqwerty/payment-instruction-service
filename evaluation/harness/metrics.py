"""Agreement and per-class metrics between classifier proposals and hand labels (Phase 11).

`cohens_kappa`/`confusion_matrix` are the same functions used by the Agent Trajectory Harness
(src/harness/agreement.py) for judge-vs-human agreement -- reused rather than reinvented, per
the phase brief's section 5. Everything here is reported **per class, never only aggregate**:
a reason-code taxonomy is heavily imbalanced (AC01/RC01 dominate; RR04/MD07 are thin), and a
single blended accuracy number would hide a classifier that is excellent on the common classes
and silently useless on the rare ones -- exactly the classes where the deterministic rules
*don't* already handle things and the model is supposed to be earning its place.
"""

from __future__ import annotations

from collections import Counter


def cohens_kappa(rated_pairs: list[tuple]) -> float:
    """Cohen's kappa for two raters' categorical judgments, given as a list of (rater1, rater2)
    tuples. Corrects raw agreement for the agreement chance alone would produce -- inflated
    whenever one category dominates."""
    n = len(rated_pairs)
    if n == 0:
        return 0.0
    categories = sorted({v for pair in rated_pairs for v in pair}, key=str)
    observed_agreement = sum(1 for a, b in rated_pairs if a == b) / n
    rater1_counts = dict.fromkeys(categories, 0)
    rater2_counts = dict.fromkeys(categories, 0)
    for a, b in rated_pairs:
        rater1_counts[a] += 1
        rater2_counts[b] += 1
    chance_agreement = sum((rater1_counts[c] / n) * (rater2_counts[c] / n) for c in categories)
    if chance_agreement >= 1.0:
        return 1.0 if observed_agreement >= 1.0 else 0.0
    return (observed_agreement - chance_agreement) / (1 - chance_agreement)


def confusion_matrix(rated_pairs: list[tuple], categories: list[str] | None = None) -> dict:
    """{truth: {predicted: count}} over every category, including zero-count cells and an
    explicit "null" bucket for a classifier that abstained (predicted None)."""
    cats = categories if categories is not None else sorted({v for pair in rated_pairs for v in pair}, key=str)
    matrix = {t: dict.fromkeys(cats, 0) for t in cats}
    for truth, predicted in rated_pairs:
        if truth not in matrix:
            matrix[truth] = dict.fromkeys(cats, 0)
        if predicted not in matrix[truth]:
            matrix[truth][predicted] = 0
        matrix[truth][predicted] += 1
    return matrix


def per_class_precision_recall_f1(rated_pairs: list[tuple]) -> dict:
    """{class: {precision, recall, f1, support}} for every ground-truth class present, support
    always reported (the phase brief: "per-class precision/recall/F1 with support reported --
    never only aggregate"). A class with zero predicted instances gets precision=0.0, not a
    divide-by-zero crash; a class with zero support (never appears in truth) is not included --
    there is nothing to compute recall against."""
    truth_counts = Counter(t for t, _ in rated_pairs)
    predicted_counts = Counter(p for _, p in rated_pairs)
    true_positives = Counter(t for t, p in rated_pairs if t == p)

    result = {}
    for cls in sorted(truth_counts, key=str):
        support = truth_counts[cls]
        tp = true_positives[cls]
        predicted_as_cls = predicted_counts.get(cls, 0)
        precision = (tp / predicted_as_cls) if predicted_as_cls else 0.0
        recall = (tp / support) if support else 0.0
        f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) else 0.0
        result[cls] = {
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "support": support,
        }
    return result


def macro_average(per_class: dict, field: str) -> float:
    """Unweighted mean across classes -- deliberately not support-weighted, since a
    support-weighted average would let performance on the dominant classes (AC01, RC01) hide
    poor performance on the thin ones, the exact blind spot per-class reporting exists to avoid."""
    values = [row[field] for row in per_class.values()]
    return sum(values) / len(values) if values else 0.0


def overall_accuracy(rated_pairs: list[tuple]) -> float:
    n = len(rated_pairs)
    return (sum(1 for a, b in rated_pairs if a == b) / n) if n else 0.0
