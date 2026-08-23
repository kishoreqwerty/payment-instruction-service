"""Calibration analysis: does the classifier's stated confidence mean anything (Phase 11 section 5).

A confidence number that doesn't track actual accuracy is worse than useless in this system --
the operator is shown it as a signal for how much to trust the suggestion (section 7), so a
classifier that says 0.95 on cases it gets right half the time would actively mislead. Bucketed
by confidence into fixed-width bands rather than by rank/percentile, so a bucket's *label* means
the same thing across different runs (a "0.9-1.0" bucket here is always the same range, unlike a
percentile bucket whose boundaries shift with the run's own confidence distribution).
"""

from __future__ import annotations

DEFAULT_BUCKET_EDGES = [0.0, 0.5, 0.7, 0.8, 0.9, 1.0001]
DEFAULT_BUCKET_LABELS = ["0.0-0.5", "0.5-0.7", "0.7-0.8", "0.8-0.9", "0.9-1.0"]


def bucket_for(confidence: float, edges: list[float] = DEFAULT_BUCKET_EDGES) -> int:
    for i in range(len(edges) - 1):
        if edges[i] <= confidence < edges[i + 1]:
            return i
    return len(edges) - 2


def calibration_report(
    records: list[dict],
    edges: list[float] = DEFAULT_BUCKET_EDGES,
    labels: list[str] = DEFAULT_BUCKET_LABELS,
) -> dict:
    """`records`: [{"confidence": float, "correct": bool}, ...] -- one per case where the
    classifier actually produced a proposal (a null/abstained proposal has no confidence to
    bucket and is reported separately by the caller, not folded in here as a fabricated 0).
    Each bucket reports its own accuracy and count; a bucket with zero cases reports
    `accuracy: None` rather than 0.0, so "no data" is never confused with "0% accurate".
    """
    buckets = [{"label": label, "n": 0, "n_correct": 0} for label in labels]
    for record in records:
        idx = bucket_for(record["confidence"], edges)
        buckets[idx]["n"] += 1
        if record["correct"]:
            buckets[idx]["n_correct"] += 1

    rows = []
    total_abs_gap = 0.0
    n_with_data = 0
    for i, bucket in enumerate(buckets):
        n = bucket["n"]
        accuracy = (bucket["n_correct"] / n) if n else None
        midpoint = (edges[i] + min(edges[i + 1], 1.0)) / 2
        gap = abs(accuracy - midpoint) if accuracy is not None else None
        if gap is not None:
            total_abs_gap += gap * n
            n_with_data += n
        rows.append(
            {
                "bucket": bucket["label"],
                "n": n,
                "accuracy": accuracy,
                "confidence_midpoint": midpoint,
                "gap": gap,
            }
        )

    expected_calibration_error = (total_abs_gap / n_with_data) if n_with_data else None
    return {"buckets": rows, "expected_calibration_error": expected_calibration_error, "n_total": len(records)}
