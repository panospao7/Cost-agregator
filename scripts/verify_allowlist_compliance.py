#!/usr/bin/env python3
"""
ALLOWLIST_COMPLIANCE — Validates allowlist entries across all guard scripts.

Checks:
  - Every allowlist entry has a reason/justification
  - Every allowlist entry has an owner (where format supports it)
  - No expired temporary entries (allowed_until dates)
  - YAML allowlists have the standard fields
  - Plain-text allowlists have reason comments on each entry

Exit codes:
  0 = compliant (or warnings only when --fail-on-violation is set)
  1 = violations found (--fail-on-violation required)
  2 = script error

Grace period for missing `owner` field: until 2026-10-01
  - Before that date: WARNING only (does not fail CI)
  - On or after that date: FAIL (violation)

Usage:
  python3 scripts/verify_allowlist_compliance.py
  python3 scripts/verify_allowlist_compliance.py --fail-on-violation
"""

import argparse
import datetime
import os
import re
import sys
from typing import List, Dict, Optional, Tuple

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Configuration ──────────────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

# Grace period for missing `owner` field
OWNER_GRACE_PERIOD_END = datetime.date(2026, 10, 1)

# YAML allowlist files to validate
YAML_ALLOWLISTS = [
    os.path.join(PROJECT_ROOT, "config", "db_access_allowlist.yml"),
    os.path.join(PROJECT_ROOT, "config", "release_block_denylist.yml"),
    os.path.join(SCRIPT_DIR, "allowlists", "cancellation_allowlist.yml"),
    os.path.join(SCRIPT_DIR, "allowlists", "ui_dao_allowlist.yml"),
    os.path.join(SCRIPT_DIR, "allowlists", "worker_allowlist.yml"),
    os.path.join(SCRIPT_DIR, "allowlists", "receipt_link_allowlist.yml"),
    os.path.join(SCRIPT_DIR, "allowlists", "import_lifecycle_allowlist.yml"),
    os.path.join(SCRIPT_DIR, "allowlists", "cloud_payload_allowlist.yml"),
    os.path.join(SCRIPT_DIR, "allowlists", "pii_logging_allowlist.yml"),
    os.path.join(SCRIPT_DIR, "allowlists", "di_release_allowlist.yml"),
]

# Plain-text allowlist files to validate (simpler format: filename # reason)
TEXT_ALLOWLISTS = [
    os.path.join(SCRIPT_DIR, "event_writer_allowlist.txt"),
]


# ── YAML Allowlist Validation ──────────────────────────────────────────────────

def _parse_allowed_writers_entries(data: dict) -> List[dict]:
    """
    Parse entries from the `allowed_writers` format used by db_access_allowlist.yml.
    Returns a list of normalized entries with keys: class, reason, allowed_until, owner.
    """
    entries = []
    raw_entries = data.get("allowed_writers", [])
    if not isinstance(raw_entries, list):
        return entries

    for idx, item in enumerate(raw_entries):
        if not isinstance(item, dict):
            continue
        entry = {
            "class": item.get("class", f"entry-{idx}"),
            "reason": str(item.get("reason", "")),
            "allowed_until": str(item.get("allowed_until", "")) if item.get("allowed_until") else None,
            "owner": str(item.get("owner", "")) if item.get("owner") else None,
            "lineno": idx,  # synthetic index instead of file line number
        }
        entries.append(entry)

    return entries


def _parse_release_block_entries(data: dict) -> List[dict]:
    """
    Parse entries from the `release_block_tests` format used by release_block_denylist.yml.
    Each entry has: class, reason.
    Returns a list of normalized entries with keys: class, reason, allowed_until, owner.
    """
    entries = []
    raw_entries = data.get("release_block_tests", [])
    if not isinstance(raw_entries, list):
        return entries

    for idx, item in enumerate(raw_entries):
        if not isinstance(item, dict):
            continue
        entry = {
            "class": item.get("class", f"entry-{idx}"),
            "reason": str(item.get("reason", "")),
            "allowed_until": str(item.get("allowed_until", "")) if item.get("allowed_until") else None,
            "owner": str(item.get("owner", "")) if item.get("owner") else None,
            "lineno": idx,
        }
        entries.append(entry)

    return entries


def _parse_flat_yaml_entries(data: List[dict]) -> List[dict]:
    """
    Parse entries from a flat-list YAML format used by the 5 new allowlists.
    Each entry has: rule, path, symbol, reason, owner, expires, linked_issue.
    Returns a list of normalized entries with consistent keys.
    """
    entries = []
    for idx, item in enumerate(data):
        if not isinstance(item, dict):
            continue
        # Determine the identifier: prefer `path`, fall back to `class`, `worker_class`, or index
        identifier = (
            str(item.get("path", "")) or
            str(item.get("class", "")) or
            str(item.get("worker_class", "")) or
            f"entry-{idx}"
        )
        expires_raw = item.get("expires")
        # Normalize: "permanent" or None means no expiry; a date string means expiry
        expires_str = None
        if expires_raw and str(expires_raw).lower() != "permanent":
            expires_str = str(expires_raw)

        entry = {
            "class": identifier,
            "reason": str(item.get("reason", "")),
            "allowed_until": expires_str,
            "owner": str(item.get("owner", "")) if item.get("owner") else None,
            "lineno": idx,
            "linked_issue": str(item.get("linked_issue", "")) if item.get("linked_issue") else None,
        }
        entries.append(entry)

    return entries


def _load_yaml_data(path: str) -> Optional[object]:
    """Load YAML data from a file. Returns None on failure."""
    if not os.path.exists(path):
        return None
    try:
        import yaml
    except ImportError:
        print("WARNING: PyYAML not installed, cannot parse YAML allowlist", file=sys.stderr)
        return None
    try:
        with open(path, encoding="utf-8") as f:
            return yaml.safe_load(f)
    except Exception as e:
        print(f"ERROR: Could not parse YAML allowlist {path}: {e}", file=sys.stderr)
        return None


def parse_db_access_allowlist(path: str) -> List[dict]:
    """
    Parse YAML allowlist file using PyYAML.
    Auto-detects format: allowed_writers (nested) or flat list.
    Returns a list of normalized entries with keys: class, reason, allowed_until, owner.
    If PyYAML is not installed, falls back to returning an empty list with a warning.
    """
    data = _load_yaml_data(path)
    if data is None:
        return []

    # Detect format
    if isinstance(data, dict):
        if "allowed_writers" in data:
            return _parse_allowed_writers_entries(data)
        if "release_block_tests" in data:
            return _parse_release_block_entries(data)
    elif isinstance(data, list):
        return _parse_flat_yaml_entries(data)
    return []


def check_yaml_allowlist(path: str, project_root: str = None) -> List[str]:
    """
    Validate a YAML allowlist file.
    Returns a list of violation messages.

    Args:
        path: Path to the YAML allowlist file.
        project_root: Project root directory for relative path computation.
                      Defaults to auto-detected PROJECT_ROOT.
    """
    if project_root is None:
        project_root = PROJECT_ROOT
    violations = []
    rel_path = os.path.relpath(path, project_root)
    today = datetime.date.today()
    is_grace_period = today < OWNER_GRACE_PERIOD_END

    entries = parse_db_access_allowlist(path)

    if not entries:
        print(f"WARNING: No entries found in {rel_path}", file=sys.stderr)
        return violations

    for entry in entries:
        cls_name = entry.get("class", "unknown")
        lineno = entry.get("lineno", 0)
        identifier = f"{rel_path}:entry-{lineno} ({cls_name})"

        # Check reason is present
        if not entry.get("reason", "").strip():
            violations.append(
                f"ALLOWLIST-COMPLIANCE {identifier} MISSING_REASON: "
                f"entry for '{cls_name}' has no reason — every allowlist entry must justify why it is safe"
            )

        # Check owner (grace period)
        if not entry.get("owner"):
            msg = (
                f"ALLOWLIST-COMPLIANCE {identifier} MISSING_OWNER: "
                f"entry for '{cls_name}' has no owner field — "
                f"add @github-handle owner (grace period until {OWNER_GRACE_PERIOD_END})"
            )
            if is_grace_period:
                print(f"WARNING: {msg}", file=sys.stderr)
            else:
                violations.append(msg)

        # Check allowed_until for expiry
        allowed_until = entry.get("allowed_until")
        if allowed_until:
            expiry_date = _try_parse_date(allowed_until)
            if expiry_date and expiry_date < today:
                violations.append(
                    f"ALLOWLIST-COMPLIANCE {identifier} EXPIRED_ALLOWED_UNTIL: "
                    f"entry for '{cls_name}' expired on {expiry_date.isoformat()} — "
                    f"remove allowlist entry or update expiry"
                )

    return violations


def _try_parse_date(value: str) -> Optional[datetime.date]:
    """Try to parse a YYYY-MM-DD date from a string. Returns None if not a date."""
    # Try ISO date embedded in the string
    match = re.search(r'(\d{4}-\d{2}-\d{2})', value)
    if match:
        try:
            return datetime.date.fromisoformat(match.group(1))
        except ValueError:
            pass

    # Try full string as ISO date
    try:
        return datetime.date.fromisoformat(value.strip())
    except ValueError:
        pass

    return None


# ── Plain-text Allowlist Validation ────────────────────────────────────────────

def check_text_allowlist(path: str, project_root: str = None) -> List[str]:
    """
    Validate a plain-text allowlist file (format: filename # reason).
    Each non-empty, non-comment line must have a '# reason' comment.

    Args:
        path: Path to the text allowlist file.
        project_root: Project root directory for relative path computation.
                      Defaults to auto-detected PROJECT_ROOT.
    """
    if project_root is None:
        project_root = PROJECT_ROOT
    violations = []
    rel_path = os.path.relpath(path, project_root)

    if not os.path.exists(path):
        print(f"WARNING: Text allowlist not found: {rel_path}", file=sys.stderr)
        return violations

    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    for lineno, line in enumerate(lines, 1):
        stripped = line.strip()

        # Skip empty lines and comment-only lines
        if not stripped or stripped.startswith("#"):
            continue

        identifier = f"{rel_path}:{lineno}"

        # Every entry line should have '# reason' somewhere after the filename
        if "#" not in stripped:
            violations.append(
                f"ALLOWLIST-COMPLIANCE {identifier} MISSING_REASON_COMMENT: "
                f"entry has no '# reason' comment — add justification"
            )
            continue

        # Check that the part after '#' is non-trivial (more than just whitespace)
        comment_part = stripped.split("#", 1)[1].strip()
        if not comment_part or len(comment_part) < 5:
            violations.append(
                f"ALLOWLIST-COMPLIANCE {identifier} INSUFFICIENT_REASON: "
                f"reason comment is too short or empty — provide meaningful justification"
            )

    return violations


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Validate allowlist entries across all guard scripts"
    )
    parser.add_argument(
        "--fail-on-violation", action="store_true",
        help="Exit with code 1 if violations are found (warnings do not count as violations)"
    )
    parser.add_argument(
        "--root", default=None,
        help="Project root directory (default: auto-detect)"
    )
    args = parser.parse_args()

    project_root = args.root or PROJECT_ROOT

    all_violations = []

    # ── Validate YAML allowlists ──────────────────────────────
    for yaml_path in YAML_ALLOWLISTS:
        if not os.path.exists(yaml_path):
            print(f"WARNING: YAML allowlist not found: {yaml_path}", file=sys.stderr)
            continue
        rel = os.path.relpath(yaml_path, project_root)
        print(f"Checking YAML allowlist: {rel}")
        violations = check_yaml_allowlist(yaml_path, project_root=project_root)
        all_violations.extend(violations)

    # ── Validate plain-text allowlists ────────────────────────
    for text_path in TEXT_ALLOWLISTS:
        if not os.path.exists(text_path):
            print(f"WARNING: Text allowlist not found: {text_path}", file=sys.stderr)
            continue
        rel = os.path.relpath(text_path, project_root)
        print(f"Checking text allowlist: {rel}")
        violations = check_text_allowlist(text_path, project_root=project_root)
        all_violations.extend(violations)

    # ── Report ────────────────────────────────────────────
    today = datetime.date.today()
    grace_remaining = (OWNER_GRACE_PERIOD_END - today).days if today < OWNER_GRACE_PERIOD_END else 0

    print()
    if all_violations:
        for v in all_violations:
            print(v)

        print()
        print(f"FOUND: {len(all_violations)} allowlist compliance violation(s)")

        if grace_remaining > 0:
            print(f"NOTE: Owner grace period ends {OWNER_GRACE_PERIOD_END} "
                  f"({grace_remaining} days remaining)")

        if args.fail_on_violation:
            print("FAIL: Allowlist compliance violations (--fail-on-violation set)")
            sys.exit(1)
        else:
            print("WARNING: Allowlist compliance issues (pass --fail-on-violation to fail CI)")
            sys.exit(0)
    else:
        print("PASS: All allowlist entries are compliant.")

        if grace_remaining > 0:
            print(f"NOTE: Owner grace period ends {OWNER_GRACE_PERIOD_END} "
                  f"({grace_remaining} days remaining)")

        sys.exit(0)


if __name__ == "__main__":
    main()
