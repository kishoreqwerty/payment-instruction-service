import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from harness.metrics import cohens_kappa, confusion_matrix, overall_accuracy, per_class_precision_recall_f1


def test_cohens_kappa_perfect_agreement():
    pairs = [("A", "A"), ("B", "B"), ("A", "A"), ("C", "C")]
    assert cohens_kappa(pairs) == 1.0


def test_cohens_kappa_no_agreement_beyond_chance():
    # Two raters, one category each -- every pair agrees but there's only one category, so
    # chance agreement is already 1.0 -- kappa is defined as 1.0 in that degenerate case.
    pairs = [("A", "A")] * 10
    assert cohens_kappa(pairs) == 1.0


def test_cohens_kappa_known_textbook_value():
    # Classic worked example: 50 items, 2 raters, 2 categories. Observed agreement = 0.70,
    # chance agreement = 0.50 -- kappa = (0.70 - 0.50) / (1 - 0.50) = 0.40.
    pairs = [("yes", "yes")] * 20 + [("no", "no")] * 15 + [("yes", "no")] * 10 + [("no", "yes")] * 5
    kappa = cohens_kappa(pairs)
    assert abs(kappa - 0.40) < 0.02


def test_cohens_kappa_handles_none_values():
    pairs = [(None, None), ("AC01", "AC01"), (None, "AC01"), ("AC01", None)]
    # Just needs to not crash on unorderable types (None vs str) -- exact value not asserted.
    cohens_kappa(pairs)


def test_confusion_matrix_counts_every_cell():
    pairs = [("AC01", "AC01"), ("AC01", "RC01"), ("RC01", "RC01")]
    matrix = confusion_matrix(pairs, ["AC01", "RC01"])
    assert matrix["AC01"]["AC01"] == 1
    assert matrix["AC01"]["RC01"] == 1
    assert matrix["RC01"]["AC01"] == 0
    assert matrix["RC01"]["RC01"] == 1


def test_per_class_precision_recall_f1_basic():
    # Truth: AC01 x3, RC01 x2. Predicted: AC01 correct x2, one AC01 predicted as RC01,
    # RC01 correct x1, one RC01 predicted as AC01.
    pairs = [
        ("AC01", "AC01"),
        ("AC01", "AC01"),
        ("AC01", "RC01"),
        ("RC01", "RC01"),
        ("RC01", "AC01"),
    ]
    result = per_class_precision_recall_f1(pairs)
    assert result["AC01"]["support"] == 3
    assert result["AC01"]["recall"] == 2 / 3
    # Predicted AC01: 3 times (2 correct, 1 actually RC01) -> precision = 2/3
    assert abs(result["AC01"]["precision"] - 2 / 3) < 1e-9
    assert result["RC01"]["support"] == 2
    assert result["RC01"]["recall"] == 1 / 2


def test_per_class_zero_predictions_gives_zero_precision_not_crash():
    pairs = [("AC01", "RC01"), ("AC01", "RC01")]
    result = per_class_precision_recall_f1(pairs)
    assert result["AC01"]["precision"] == 0.0
    assert result["AC01"]["recall"] == 0.0
    assert result["AC01"]["f1"] == 0.0


def test_overall_accuracy():
    pairs = [("A", "A"), ("A", "B"), ("B", "B"), ("B", "B")]
    assert overall_accuracy(pairs) == 0.75


def test_overall_accuracy_empty():
    assert overall_accuracy([]) == 0.0
