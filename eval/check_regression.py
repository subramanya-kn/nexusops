"""Fail if the latest eval scorecard regresses against the committed baseline.

Used by `.github/workflows/eval.yml` and available locally: `python3 eval/check_regression.py`
after `make eval`. Only compares metrics that are real numbers in both files -- a metric
that's PENDING in either scorecard (no live Docker stack) is skipped, never treated as a
regression.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

REPORTS_DIR = Path(__file__).resolve().parent / "reports"

# For each metric: (direction, tolerance). "higher" means lower-than-baseline is a
# regression; "lower" means higher-than-baseline is a regression.
_RULES: dict[str, tuple[str, float]] = {
    "diagnostic_accuracy": ("higher", 0.0),
    "remediation_appropriateness": ("higher", 0.0),
    "adversarial_pass_rate": ("higher", 0.0),
    "false_action_rate": ("lower", 0.0),
    "mean_hops": ("lower", 1.0),  # allow +1 hop of drift before flagging
}


def main() -> int:
    baseline_path = REPORTS_DIR / "baseline.json"
    latest_path = REPORTS_DIR / "latest.json"
    if not baseline_path.exists():
        print(f"No baseline at {baseline_path} -- nothing to compare, treating as pass.")
        return 0
    if not latest_path.exists():
        print(f"FAIL: no {latest_path} -- run `make eval` first.")
        return 1

    baseline = json.loads(baseline_path.read_text())["metrics"]
    latest = json.loads(latest_path.read_text())["metrics"]

    regressions: list[str] = []
    for metric, (direction, tolerance) in _RULES.items():
        base_val, new_val = baseline.get(metric), latest.get(metric)
        if not isinstance(base_val, (int, float)) or not isinstance(new_val, (int, float)):
            continue  # PENDING or missing -- not comparable, not a regression
        if direction == "higher" and new_val < base_val - tolerance:
            regressions.append(f"{metric}: {base_val} -> {new_val} (worse, lower is bad)")
        elif direction == "lower" and new_val > base_val + tolerance:
            regressions.append(f"{metric}: {base_val} -> {new_val} (worse, higher is bad)")

    if regressions:
        print("REGRESSION vs baseline:")
        for r in regressions:
            print(f"  - {r}")
        return 1

    print(f"No regression vs baseline ({baseline_path}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
