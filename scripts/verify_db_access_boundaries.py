#!/usr/bin/env python3
"""
verify_db_access_boundaries.py
Global Write/Read/Restore Barrier — PR 6 (warning mode) / PR 10 (CI failure mode)

Scans app/src/main/java for direct DAO mutation calls outside the approved
writer allowlist defined in config/db_access_allowlist.yml.

Exit codes:
  0 — no violations (or --warn-only mode, which is the default)
  1 — violations found AND --fail-on-violation flag is set (PR 10)

Usage:
  python3 scripts/verify_db_access_boundaries.py              # warning mode
  python3 scripts/verify_db_access_boundaries.py --fail-on-violation  # CI failure mode
"""

import os
import re
import sys
import argparse

# Ensure UTF-8 output on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Mutation method patterns ──────────────────────────────────────────────────
# Matches: .insert(, .insertAll(, .update(, .delete(, .deleteAll(, .upsert(, etc.
MUTATION_PATTERN = re.compile(
    r'\.\s*(?:insert|insertAll|update|delete|deleteAll|clear|replace|upsert|'
    r'set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|'
    r'bulkRename|approve|reject)\s*\('
)

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
SOURCE_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java")
ALLOWLIST_PATH = os.path.join(PROJECT_ROOT, "config", "db_access_allowlist.yml")

# ── Directories to skip ───────────────────────────────────────────────────────
SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# ── Load allowlist ────────────────────────────────────────────────────────────

def load_allowlist(path: str) -> set:
    """
    Returns a set of class name substrings that are approved writers.
    Parses the `class:` keys from allowed_writers, debug_only_writers sections.
    Does not require a YAML library — simple line-based parse.
    """
    approved = set()
    if not os.path.exists(path):
        print(f"WARNING: allowlist not found at {path} — running without allowlist (all violations reported)")
        return approved

    with open(path, encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            if stripped.startswith("- class:"):
                class_name = stripped[len("- class:"):].strip()
                if class_name:
                    approved.add(class_name)
    return approved


# ── Scan ──────────────────────────────────────────────────────────────────────

def scan(source_dir: str, approved: set) -> list:
    """
    Walk source_dir, find .kt files with DAO mutation calls outside the allowlist.
    Returns list of (relative_path, line_number, line_text) tuples.
    """
    violations = []

    for root, dirs, files in os.walk(source_dir):
        # Prune skipped directories in-place
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]

        for filename in files:
            if not filename.endswith(".kt"):
                continue

            class_name = filename.removesuffix(".kt")

            # Skip DAO interface files themselves — they define the contract, not callers
            if class_name.endswith("Dao"):
                continue

            # Skip if this file's class is in the allowlist
            if any(class_name == a or class_name.startswith(a) for a in approved):
                continue

            filepath = os.path.join(root, filename)
            rel_path = os.path.relpath(filepath, PROJECT_ROOT)

            try:
                with open(filepath, encoding="utf-8") as f:
                    lines = f.readlines()
            except OSError:
                continue

            for lineno, line in enumerate(lines, start=1):
                # Skip comments
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                # Must reference a DAO (ends in Dao)
                if "Dao" not in line:
                    continue
                if MUTATION_PATTERN.search(line):
                    violations.append((rel_path, lineno, line.rstrip()))

    return violations


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Verify DB access boundaries")
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Exit with code 1 if violations are found (CI failure mode, PR 10)"
    )
    args = parser.parse_args()

    if not os.path.isdir(SOURCE_DIR):
        print(f"ERROR: source directory not found: {SOURCE_DIR}")
        sys.exit(1)

    approved = load_allowlist(ALLOWLIST_PATH)
    violations = scan(SOURCE_DIR, approved)

    if not violations:
        print("PASS: DB access boundaries — no unauthorized DAO mutations found.")
        sys.exit(0)

    status = "FAIL" if args.fail_on_violation else "WARNING"
    print(f"{status}: DB access boundaries — {len(violations)} potential unauthorized DAO mutation(s):\n")

    for rel_path, lineno, line_text in violations:
        print(f"  {rel_path}:{lineno}")
        print(f"    {line_text.strip()}")

    print()
    print("For each violation, either:")
    print("  1. Add the class to config/db_access_allowlist.yml with a reason.")
    print("  2. Route the write through the approved lifecycle coordinator.")
    print()
    print("See docs/DB_WRITE_OWNERSHIP.md for the ownership map.")

    if args.fail_on_violation:
        sys.exit(1)
    else:
        # Warning mode — do not fail the build
        sys.exit(0)


if __name__ == "__main__":
    main()
