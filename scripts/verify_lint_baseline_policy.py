#!/usr/bin/env python3
"""
LINT_BASELINE_POLICY — Validates the lint baseline file.

Checks:
  1. Only MissingTranslation issue IDs are baselined
  2. Baseline count has not increased beyond a threshold
  3. Baseline was not regenerated wholesale (detectable by unexpected count change)

Exit codes: 0 = compliant, 1 = violation, 2 = error
"""
import xml.etree.ElementTree as ET
import sys
import argparse
import os


def main():
    parser = argparse.ArgumentParser(
        description="Validate that the lint baseline only contains allowed issue types."
    )
    parser.add_argument(
        "--baseline",
        default="app/lint-baseline.xml",
        help="Path to the lint-baseline.xml file (default: app/lint-baseline.xml)",
    )
    parser.add_argument(
        "--max-missing-translations",
        type=int,
        default=None,
        help="Maximum allowed MissingTranslation count. "
             "If the baseline contains more, it may indicate unauthorized regeneration.",
    )
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Exit with code 1 if violations are found.",
    )
    args = parser.parse_args()

    # Resolve relative to project root (script is in scripts/)
    if not os.path.isabs(args.baseline):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        project_root = os.path.dirname(script_dir)
        baseline_path = os.path.join(project_root, args.baseline)
    else:
        baseline_path = args.baseline

    if not os.path.isfile(baseline_path):
        print(f"ERROR: Baseline file not found: {baseline_path}", file=sys.stderr)
        sys.exit(2)

    try:
        tree = ET.parse(baseline_path)
        root = tree.getroot()
    except Exception as e:
        print(f"ERROR reading baseline: {e}", file=sys.stderr)
        sys.exit(2)

    # Count issues by type
    issue_counts = {}
    non_allowed = []
    total = 0
    for issue in root.iter("issue"):
        issue_id = issue.get("id", "")
        issue_counts[issue_id] = issue_counts.get(issue_id, 0) + 1
        total += 1
        if issue_id != "MissingTranslation":
            # Find location info for the report
            locations = list(issue.iter("location"))
            loc_str = ""
            if locations:
                loc = locations[0]
                loc_str = f"{loc.get('file', '?')}:{loc.get('line', '?')}"
            non_allowed.append(f"  {issue_id} {loc_str}")

    print(f"Total baselined issues: {total}")
    for issue_id, count in sorted(issue_counts.items()):
        status = "ALLOWED" if issue_id == "MissingTranslation" else "FORBIDDEN"
        print(f"  {issue_id}: {count} ({status})")

    violations_found = False

    if non_allowed:
        print(f"\nFORBIDDEN: Non-MissingTranslation issues in baseline ({len(non_allowed)}):")
        for item in non_allowed:
            print(item)
        violations_found = True

    if args.max_missing_translations is not None:
        mt_count = issue_counts.get("MissingTranslation", 0)
        if mt_count > args.max_missing_translations:
            print(
                f"\nVIOLATION: MissingTranslation count ({mt_count}) "
                f"exceeds maximum ({args.max_missing_translations}). "
                f"Baseline may have been regenerated wholesale."
            )
            violations_found = True

    if violations_found:
        print("\nFAIL: Baseline policy violations detected.")
        if args.fail_on_violation:
            sys.exit(1)
    else:
        print("\nPASS: Baseline contains only MissingTranslation issues.")

    sys.exit(0)


if __name__ == "__main__":
    main()
