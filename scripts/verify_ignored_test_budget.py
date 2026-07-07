#!/usr/bin/env python3
"""
G-IGNORE-01 — Ignored Test Budget Guard

Scans `app/src/test/` and `app/src/androidTest/` for @Ignore annotations,
validates each has a non-empty reason string, categorizes them, counts total
and per-category, and checks against a release-block denylist.

Output format:
  G-IGNORE-01 path/to/File.kt:line category reason_snippet
  Total ignored: N  Categories: stress=X, jvm_incompatible=Y, ...

Exit codes:
  0 = pass (no violations, or violations in warning mode)
  1 = violations found and --fail-on-violation set
  2 = script error (missing directories, unparseable config, etc.)

RULE_ID: G-IGNORE-01
"""

import argparse
import re
import sys
from pathlib import Path
from typing import List, Tuple, Optional, Dict


# ── Configuration ──────────────────────────────────────────────
RULE_ID = "G-IGNORE-01"
DESCRIPTION = "Ignored test budget — validates @Ignore annotations have reasons and checks release-block denylist"
SCOPE_DIRS = ["app/src/test", "app/src/androidTest"]
FILE_PATTERNS = ["*.kt", "*.java"]
DENYLIST_PATH = "config/release_block_denylist.yml"


# ── Category definitions ───────────────────────────────────────
# Ordered by priority: first-match wins
CATEGORY_RULES = [
    ("stress", r"Stress test"),
    ("jvm_incompatible", r"not available on desktop JVM|AndroidKeyStore"),
    ("removed_api", r"Tests reference removed"),
    ("vat_logic", r"VAT calculation"),
    ("truth_boxing", r"Truth assertThat incompatible"),
    ("negative_id", r"Negative IDs are unsupported"),
    ("rewrite_needed", r"Needs rewrite"),
]


def categorize_reason(reason: str) -> str:
    """Classify a reason string into one of the predefined categories."""
    for category, pattern in CATEGORY_RULES:
        if re.search(pattern, reason, re.IGNORECASE):
            return category
    return "other"


# ── Scanning ──────────────────────────────────────────────────

def scan_ignored_tests(root_dir: Path) -> List[Tuple[str, int, str, str]]:
    """Scan test directories for @Ignore annotations.

    Returns list of (filepath, line_number, reason_string, category) tuples.
    """
    results: List[Tuple[str, int, str, str]] = []

    for scope_dir in SCOPE_DIRS:
        scan_dir = root_dir / scope_dir
        if not scan_dir.exists():
            continue
        for pattern in FILE_PATTERNS:
            for filepath in scan_dir.rglob(pattern):
                try:
                    content = filepath.read_text(encoding="utf-8")
                except Exception as e:
                    print(
                        f"ERROR reading {filepath}: {e}",
                        file=sys.stderr,
                    )
                    continue

                violations, had_fatal = _scan_file(str(filepath), content)
                if had_fatal:
                    continue
                results.extend(violations)

    return results


def _scan_file(
    filepath: str, content: str
) -> Tuple[List[Tuple[str, int, str, str]], bool]:
    """Scan a single file's content for @Ignore annotations.

    Returns (results, had_fatal_error).
    """
    results: List[Tuple[str, int, str, str]] = []
    had_fatal = False

    lines = content.splitlines()
    for i, line in enumerate(lines, 1):
        stripped = line.strip()

        # Skip commented-out @Ignore lines
        if stripped.startswith("//") and "@Ignore" in stripped:
            continue

        # Match @Ignore with an optional parenthesized reason string
        # Patterns:
        #   @Ignore("reason")
        #   @Ignore
        #   @Ignore(value = "reason")
        m = re.match(r'@Ignore\s*(?:\((?:(?:value\s*=\s*)?\s*"([^"]*)"\s*)?\))?', stripped)
        if not m:
            continue

        reason = m.group(1)
        if reason is None:
            reason = ""

        category = categorize_reason(reason) if reason else "missing_reason"
        results.append((filepath, i, reason, category))

    return results, had_fatal


# ── Denylist ───────────────────────────────────────────────────

def load_release_denylist(path: Path) -> List[dict]:
    """Load YAML denylist of test classes that must NOT be @Ignored.

    Returns list of dicts with keys: class, reason
    """
    denylist: List[dict] = []
    if not path.exists():
        print(
            f"WARNING: Release denylist not found at {path}",
            file=sys.stderr,
        )
        return denylist

    try:
        import yaml

        with open(path, "r") as f:
            data = yaml.safe_load(f)
        if data and isinstance(data, dict):
            entries = data.get("release_block_tests", [])
            if isinstance(entries, list):
                denylist = entries
    except ImportError:
        print(
            "WARNING: PyYAML not installed, denylist skipped",
            file=sys.stderr,
        )
    except Exception as e:
        print(f"WARNING: Could not load denylist: {e}", file=sys.stderr)

    return denylist


def check_denylist(
    results: List[Tuple[str, int, str, str]],
    denylist: List[dict],
) -> List[str]:
    """Cross-reference ignored test results against the release-block denylist.

    Returns list of violation messages.
    """
    violations: List[str] = []
    denylist_classes = {entry.get("class", "") for entry in denylist}

    if not denylist_classes:
        return violations

    for filepath, line, reason, category in results:
        # Extract the Kotlin class name from the file path
        # Example: app/src/test/.../MoneyTest.kt → MoneyTest
        filename = Path(filepath).stem
        if filename in denylist_classes:
            entry_reason = ""
            for entry in denylist:
                if entry.get("class", "") == filename:
                    entry_reason = entry.get("reason", "")
                    break
            violations.append(
                f"{RULE_ID} {filepath}:{line} RELEASE-BLOCK VIOLATION — "
                f"@Ignored test in release-critical class '{filename}'. "
                f"Denylist reason: {entry_reason}"
            )

    return violations


# ── Reporting ──────────────────────────────────────────────────

def report(
    results: List[Tuple[str, int, str, str]],
    denylist_violations: List[str],
    fail_on_violation: bool,
) -> int:
    """Print report and exit with appropriate code.

    Returns exit code.
    """
    # Count categories
    category_counts: Dict[str, int] = {}
    for _, _, _, category in results:
        category_counts[category] = category_counts.get(category, 0) + 1

    # Print per-entry output
    for filepath, line, reason, category in results:
        # Truncate long reasons for display
        reason_snippet = reason[:80] + "..." if len(reason) > 80 else reason
        if reason_snippet:
            print(f"{RULE_ID} {filepath}:{line} category={category} reason=\"{reason_snippet}\"")
        else:
            print(f"{RULE_ID} {filepath}:{line} category={category} reason=\"\" (MISSING REASON)")

    # Print category summary
    print(f"\nTotal ignored: {len(results)}")
    print("Categories:")
    for cat in sorted(category_counts.keys()):
        print(f"  {cat}: {category_counts[cat]}")

    # Check for missing reasons
    missing_count = category_counts.get("missing_reason", 0)
    has_violations = missing_count > 0 or len(denylist_violations) > 0

    # Print denylist violations
    if denylist_violations:
        print()
        for v in denylist_violations:
            print(v)

    if has_violations:
        if fail_on_violation:
            print(
                f"\nVIOLATIONS FOUND: "
                f"missing_reason={missing_count}, "
                f"denylist_violations={len(denylist_violations)}",
                file=sys.stderr,
            )
            return 1
        else:
            print(
                f"\nWARNING: "
                f"missing_reason={missing_count}, "
                f"denylist_violations={len(denylist_violations)} "
                f"(--fail-on-violation not set)",
                file=sys.stderr,
            )
            return 0
    else:
        print(f"\nPASS: {RULE_ID} — all @Ignore annotations have reasons, no denylist violations")
        return 0


# ── Main ───────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=DESCRIPTION)
    parser.add_argument(
        "--root",
        default=".",
        help="Project root directory",
    )
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Exit with code 1 on violations",
    )
    parser.add_argument(
        "--denylist",
        default=DENYLIST_PATH,
        help="Path to release-block denylist YAML",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()

    # Validate that at least one scope directory exists
    any_exists = any(
        (root / d).exists() for d in SCOPE_DIRS
    )
    if not any_exists:
        print(
            f"FATAL: No scope directories found under {root}: {SCOPE_DIRS}",
            file=sys.stderr,
        )
        sys.exit(2)

    # Scan
    results = scan_ignored_tests(root)

    # Load denylist
    denylist_path = root / args.denylist
    denylist = load_release_denylist(denylist_path) if args.denylist else []

    # Check denylist
    denylist_violations = check_denylist(results, denylist)

    # Report
    exit_code = report(results, denylist_violations, args.fail_on_violation)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
