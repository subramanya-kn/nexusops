"""verify-p8 support: every internal README link resolves, and the headline eval numbers
in the README match the committed scorecard rather than being hand-typed/stale.

Not a general-purpose tool -- exists solely to make the verify-p8 gate's two textual
assertions ("no fabricated metrics", "every internal link resolves") machine-checkable.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def check_links(readme: str) -> list[str]:
    errors = []
    for link in re.findall(r"\]\(([^)]+)\)", readme):
        if link.startswith(("http://", "https://")):
            continue
        path, _, anchor = link.partition("#")
        if path == "":
            continue  # pure in-page anchor
        target = ROOT / path
        if not target.exists():
            errors.append(f"missing file: {link}")
            continue
        if anchor and target.suffix == ".md":
            headings = re.findall(r"^#{1,6}\s+(.+)$", target.read_text(), re.MULTILINE)
            slugs = {re.sub(r"[^\w\- ]", "", h).strip().lower().replace(" ", "-") for h in headings}
            if anchor not in slugs:
                errors.append(f"anchor not found: {link}")
    return errors


def check_eval_numbers(readme: str) -> list[str]:
    errors = []
    scorecard = json.loads((ROOT / "eval/reports/latest.json").read_text())
    metrics = scorecard["metrics"]
    checks = {
        "1.0": metrics["diagnostic_accuracy"],
        "0.0": metrics["false_action_rate"],
        "7.0": metrics["mean_hops"],
        "$0.00762": metrics["mean_token_cost_usd"],
    }
    for readme_text, actual in checks.items():
        needle = readme_text if isinstance(actual, str) else f"{actual}"
        if str(actual) not in readme and needle not in readme:
            errors.append(f"README does not contain expected value derived from scorecard: {actual!r}")
    if "PENDING" not in readme:
        errors.append("README must honestly mark unmeasured metrics PENDING (resolution_rate)")
    return errors


def main() -> int:
    readme = (ROOT / "README.md").read_text()
    errors = check_links(readme) + check_eval_numbers(readme)
    if errors:
        for e in errors:
            print(f"FAIL: {e}")
        return 1
    print("README links resolve; eval numbers match the committed scorecard.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
