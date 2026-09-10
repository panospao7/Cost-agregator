#!/usr/bin/env python3
"""
GUARD_TEMPLATE — Standardized Architecture Guard Script Template

Copy this file and replace the placeholders below to create a new guard.

Every guard script MUST implement:
  - RULE_ID          : unique identifier (e.g., "G-CLOUD-01")
  - Description      : what the guard enforces
  - Scope            : which files/directories are scanned
  - fail_on_violation: boolean flag, exit nonzero on violation when True
  - allowlist        : optional allowlist file path (YAML preferred)
  - Output format    : RULE_ID filepath:line message
  - Exit codes       : 0 = pass, 1 = violations found, 2 = script error

Output format (machine-readable):
  RULE_ID path/to/File.kt:line_number violation_message
  Hint: suggestion for remediation

Allowlist format (YAML preferred):
  - rule: RULE_ID
    path: app/src/main/java/com/example/File.kt
    symbol: SomeClass.someMethod
    reason: "Why this is safe — required"
    owner: "@github-handle — required"
    expires: "YYYY-MM-DD — required for temporary exceptions"
    linked_issue: "MIT-### — recommended"
"""

import argparse
import sys
import os
import re
from pathlib import Path
from typing import List, Tuple, Optional

# ── Configuration ──────────────────────────────────────────
RULE_ID = "G-TEMPLATE-01"
DESCRIPTION = "Template guard — replace with your guard description"
SCOPE_DIRS = ["app/src/main/java"]
FILE_PATTERNS = ["*.kt"]
ALLOWLIST_PATH = "config/allowlist_template.yml"  # optional

# ── Violation Detection ────────────────────────────────────
def scan_file(filepath: Path) -> Tuple[List[str], bool]:
    """Scan a single file for violations.
    Returns (violations, had_fatal_error).
    """
    violations: List[str] = []
    try:
        content = filepath.read_text(encoding="utf-8")
    except Exception as e:
        print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
        return violations, True

    # TODO: Replace with actual detection logic
    # Example: check for forbidden imports
    for i, line in enumerate(content.splitlines(), 1):
        if "FORBIDDEN_PATTERN" in line:
            violations.append(
                f"{RULE_ID} {filepath}:{i} FORBIDDEN_PATTERN found"
            )

    return violations, False

# ── Allowlist ──────────────────────────────────────────────
def load_allowlist(path: Path) -> List[dict]:
    """Load allowlist entries from YAML file. Returns list of dicts with:
    rule, path, symbol, reason, owner, expires, linked_issue

    Exits with code 2 on infrastructure errors (missing PyYAML, malformed YAML).
    """
    allowlist = []
    if not path.exists():
        return allowlist

    try:
        import yaml
        with open(path, "r") as f:
            data = yaml.safe_load(f)
        if data and isinstance(data, list):
            allowlist = data
    except ImportError:
        print("ERROR: PyYAML not installed. pip install pyyaml", file=sys.stderr)
        sys.exit(2)
    except yaml.YAMLError as e:
        print(f"ERROR: Malformed allowlist: {e}", file=sys.stderr)
        sys.exit(2)
    except Exception as e:
        print(f"ERROR: Could not load allowlist: {e}", file=sys.stderr)
        sys.exit(2)

    return allowlist

def is_allowlisted(filepath: str, symbol: str, allowlist: List[dict]) -> bool:
    """Check if a file:symbol is in the allowlist.
    Supports partial path matching: unrooted relative paths match suffixes.
    """
    for entry in allowlist:
        entry_path = entry.get("path", "")
        # Only match if the allowlisted path is a suffix of the actual file path
        # This prevents substring matching like "er/File.kt" matching "Worker/File.kt"
        if filepath.endswith(entry_path):
            if not symbol or entry.get("symbol", "") == symbol:
                return True
    return False

# ── Main ───────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description=DESCRIPTION)
    parser.add_argument("--root", default=".", help="Project root directory")
    parser.add_argument("--fail-on-violation", action="store_true",
                        help="Exit with code 1 on violations")
    parser.add_argument("--allowlist", default=ALLOWLIST_PATH,
                        help="Path to allowlist file")
    args = parser.parse_args()

    root = Path(args.root).resolve()

    # Fail-closed: missing configured allowlist is fatal
    if args.allowlist and not (root / args.allowlist).exists():
        print(f"ERROR: Allowlist not found: {args.allowlist}", file=sys.stderr)
        sys.exit(2)

    allowlist = load_allowlist(root / args.allowlist) if args.allowlist else []

    all_violations = []
    fatal_errors = []
    for scope_dir in SCOPE_DIRS:
        scan_dir = root / scope_dir
        if not scan_dir.exists():
            continue
        for pattern in FILE_PATTERNS:
            for filepath in scan_dir.rglob(pattern):
                violations, had_fatal = scan_file(filepath)
                if had_fatal:
                    fatal_errors.append(str(filepath))
                # Filter out allowlisted entries
                filtered = []
                for v in violations:
                    # Extract filepath from violation message (part before first ':')
                    # The violation format is: RULE_ID filepath:line message
                    parts = v.split(" ", 2)
                    if len(parts) >= 2:
                        fpath = parts[1].split(":")[0] if ":" in parts[1] else parts[1]
                        if not is_allowlisted(fpath, "", allowlist):
                            filtered.append(v)
                    else:
                        filtered.append(v)
                all_violations.extend(filtered)

    if fatal_errors:
        for fp in fatal_errors:
            print(f"FATAL: Could not read file: {fp}", file=sys.stderr)
        sys.exit(2)

    if all_violations:
        for v in all_violations:
            print(v)

        if args.fail_on_violation:
            print(f"\nVIOLATIONS FOUND: {len(all_violations)}", file=sys.stderr)
            sys.exit(1)
        else:
            print(f"\nWARNING: {len(all_violations)} violations (--fail-on-violation not set)",
                  file=sys.stderr)
            sys.exit(0)
    else:
        print(f"PASS: {RULE_ID} — no violations found")
        sys.exit(0)

if __name__ == "__main__":
    main()
