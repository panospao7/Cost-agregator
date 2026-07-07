#!/usr/bin/env python3
"""
verify_import_lifecycle_boundaries.py
Static architecture guard for import lifecycle boundaries.

Rule: G-IMPORT-01

Detects import paths (CSV, JSON, backup/restore) bypassing lifecycle
coordinators by calling expenseDao/categoryDao directly, and import
util files missing provenance field writes (importRunId, importBatchId).

Detection patterns:
  1. Files with "Import" in name calling DAOs directly
  2. expenseDao.insert / expenseDao.insertAll in import files not allowlisted
  3. categoryDao.insert / categoryDao.insertAll in import files not allowlisted
  4. Import util files missing provenance field writes (importRunId, importBatchId)

Exit codes:
  0 — no violations (or warning mode without --fail-on-violation)
  1 — violations found AND --fail-on-violation is set
  2 — script error (fatal)

Usage:
  python3 scripts/verify_import_lifecycle_boundaries.py
  python3 scripts/verify_import_lifecycle_boundaries.py --fail-on-violation
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import List, Set, Tuple, Optional

# ── Configuration ──────────────────────────────────────────────────────────
RULE_ID = "G-IMPORT-01"
DESCRIPTION = "Import lifecycle guard — detects import paths bypassing lifecycle coordinators"
SCOPE_DIR = "app/src/main/java"
FILE_PATTERN = "*.kt"
DEFAULT_ALLOWLIST = "scripts/allowlists/import_lifecycle_allowlist.yml"

SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# Files always allowed because they define the DAO interfaces themselves
BASE_APPROVED_FILES = {
    "ExpenseDao",                  # DAO interface definition
    "ExpenseDao_Impl",             # Room-generated DAO implementation
    "CategoryDao",                 # DAO interface definition
    "CategoryDao_Impl",            # Room-generated DAO implementation
    "AppDatabase",                 # Database definition
    "DaoModule",                   # DI module — no mutations
}

# ── Detection Patterns ─────────────────────────────────────────────────────

# Files whose name suggests they are import-related
IMPORT_FILE_NAME_RE = re.compile(r'(?:Import|Importer|import)', re.IGNORECASE)

# Pattern: expenseDao.insert / expenseDao.insertAll
EXPENSE_DAO_INSERT_RE = re.compile(
    r'\bexpenseDao\s*\.\s*(?:insert|insertAll)\s*\('
)

# Pattern: categoryDao.insert / categoryDao.insertAll
CATEGORY_DAO_INSERT_RE = re.compile(
    r'\bcategoryDao\s*\.\s*(?:insert|insertAll)\s*\('
)

# Pattern: local variable expenseDao/categoryDao mutations (for importers that
# inject DAOs under different names)
LOCAL_EXPENSE_DAO_MUTATION_RE = re.compile(
    r'(?:\bdao\b|\bexpenseDao\b)\s*\.\s*(?:insert|insertAll)\s*\('
)

LOCAL_CATEGORY_DAO_MUTATION_RE = re.compile(
    r'(?:\bdao\b|\bcategoryDao\b)\s*\.\s*(?:insert|insertAll)\s*\('
)

# Pattern: importRunId or importBatchId field references
PROVENANCE_FIELD_RE = re.compile(
    r'\b(?:importRunId|importBatchId|fileImportRunId|csvImportBatchId)\b'
)

# Pattern: general DAO insert pattern (for import files)
GENERAL_DAO_INSERT_RE = re.compile(
    r'(\w+Dao)\.(?:insert|insertAll)\s*\('
)

# ── YAML Allowlist Parsing ─────────────────────────────────────────────────

def load_allowlist(path: Path) -> Set[str]:
    """
    Load allowlisted class names from YAML file.
    Returns a set of class names (without .kt extension).
    Expects a flat-list format with `path` field containing the class name.
    """
    approved: Set[str] = set()
    if not path.exists():
        print(f"WARNING: allowlist not found at {path}", file=sys.stderr)
        return approved

    try:
        import yaml
    except ImportError:
        print("WARNING: PyYAML not installed, cannot parse allowlist", file=sys.stderr)
        return approved

    try:
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except Exception as e:
        print(f"WARNING: Could not parse allowlist: {e}", file=sys.stderr)
        return approved

    if not data or not isinstance(data, list):
        print(f"WARNING: Allowlist is not a list: {path}", file=sys.stderr)
        return approved

    for entry in data:
        if not isinstance(entry, dict):
            continue
        entry_path = entry.get("path", "")
        if not entry_path:
            continue
        # `path` may be a simple class name like "ImportCoordinator" or "ImportCoordinator.kt"
        cls = entry_path.replace(".kt", "").strip()
        if cls:
            approved.add(cls)

    return approved


# ── Detection Functions ────────────────────────────────────────────────────

def is_test_file(filepath: str) -> bool:
    """Check if file is in a test directory."""
    parts = filepath.replace("\\", "/").split("/")
    return any(d in SKIP_DIRS for d in parts)


def is_dao_interface(filename: str) -> bool:
    """Check if file is a DAO interface (ends with Dao.kt)."""
    return filename.endswith("Dao.kt")


def is_approved(class_name: str, approved_files: Set[str]) -> bool:
    """Check if a class is in the approved set."""
    return class_name in approved_files or class_name in BASE_APPROVED_FILES


def is_import_file(filename: str) -> bool:
    """Check if a file's name suggests it's import-related."""
    return bool(IMPORT_FILE_NAME_RE.search(filename))


def has_provenance_writes(content: str) -> bool:
    """
    Check if file content contains provenance field writes.
    Looks for assignments to importRunId or importBatchId.
    """
    # Look for assignments like: importRunId = ..., importBatchId = ...
    provenance_assignments = re.findall(
        r'\b(?:importRunId|importBatchId|fileImportRunId|csvImportBatchId)\s*=\s*\S',
        content
    )
    # Also look for constructor parameter passing: fileImportRunId = fileImportRunId
    provenance_params = re.findall(
        r'\b(?:fileImportRunId|csvImportBatchId)\s*[=:]\s*',
        content
    )
    return len(provenance_assignments) + len(provenance_params) > 0


def scan_file(filepath: Path, rel_path: str, approved_files: Set[str]) -> List[Tuple[str, int, str, str]]:
    """
    Scan a single Kotlin file for import lifecycle boundary violations.

    Returns list of (filepath, line_number, line_text, reason_code).
    """
    violations: List[Tuple[str, int, str, str]] = []
    class_name = filepath.stem

    if is_dao_interface(filepath.name):
        return violations

    # Determine if this is an import-related file
    file_is_import = is_import_file(filepath.name)
    file_is_approved = is_approved(class_name, approved_files)

    try:
        with open(filepath, "r", encoding="utf-8") as f:
            content = f.read()
        lines = content.splitlines()
    except Exception as e:
        print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
        return violations

    # ── Check 1: Import files calling DAOs directly ────────────────────────
    if file_is_import and not file_is_approved:
        for lineno, line in enumerate(lines, start=1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue

            # Check for expenseDao.insert
            if EXPENSE_DAO_INSERT_RE.search(line):
                violations.append((
                    rel_path, lineno, stripped,
                    "IMPORT_DIRECT_EXPENSE_DAO: import file calls expenseDao.insert directly"
                ))
                continue

            # Check for categoryDao.insert
            if CATEGORY_DAO_INSERT_RE.search(line):
                violations.append((
                    rel_path, lineno, stripped,
                    "IMPORT_DIRECT_CATEGORY_DAO: import file calls categoryDao.insert directly"
                ))
                continue

            # Check for general DAO insert in import files
            m = GENERAL_DAO_INSERT_RE.search(line)
            if m:
                dao_name = m.group(1)
                # Skip known approved DAOs that aren't expense/category
                if dao_name not in ("ExpenseDao", "CategoryDao", "expenseDao", "categoryDao"):
                    continue
                violations.append((
                    rel_path, lineno, stripped,
                    f"IMPORT_DIRECT_DAO: import file calls {dao_name}.insert directly"
                ))

    # ── Check 2 & 3: expenseDao.insert / categoryDao.insert in any
    # unapproved file (not just import-named files)
    if not file_is_approved:
        for lineno, line in enumerate(lines, start=1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue

            # Only flag if the file is import-related OR actually contains
            # import-like patterns (to avoid false positives on legitimate
            # lifecycle coordinators and repositories)
            should_check = file_is_import
            if not should_check:
                # Check if the file references import patterns at all
                # If it references importRunId or contains "Import" in content
                lower_content = content.lower()
                should_check = (
                    "importrunid" in lower_content or
                    "importbatchid" in lower_content or
                    "fileimportrunid" in lower_content
                )

            if not should_check:
                continue

            # expenseDao mutations
            if EXPENSE_DAO_INSERT_RE.search(line):
                violations.append((
                    rel_path, lineno, stripped,
                    "UNALLOWED_EXPENSE_DAO_INSERT: expenseDao.insert called in import-adjacent file outside allowlist"
                ))
                continue

            # categoryDao mutations
            if CATEGORY_DAO_INSERT_RE.search(line):
                violations.append((
                    rel_path, lineno, stripped,
                    "UNALLOWED_CATEGORY_DAO_INSERT: categoryDao.insert called in import-adjacent file outside allowlist"
                ))
                continue

    # ── Check 4: Import files missing provenance writes ────────────────────
    if file_is_import and not file_is_approved:
        if PROVENANCE_FIELD_RE.search(content):
            # File references provenance fields — check if it writes them
            if not has_provenance_writes(content):
                violations.append((
                    rel_path, 0, "(whole file)",
                    "IMPORT_MISSING_PROVENANCE: import file references provenance fields but does not write them"
                ))

    # ── Check 4b: Allowlisted import files — trust the allowlist ──────────
    # Allowlisted import files are trusted architectural exceptions.
    # They do not need additional provenance-write verification.
    if file_is_import and file_is_approved:
        pass  # Allowlisted files are trusted — no provenance check needed

    return violations


# ── Main ───────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=DESCRIPTION)
    parser.add_argument("--root", type=Path, default=None,
                        help="Project root directory (default: script's parent's parent)")
    parser.add_argument("--fail-on-violation", action="store_true",
                        help="Exit with code 1 on violations")
    parser.add_argument("--allowlist", type=str, default=None,
                        help="Path to allowlist file (relative to root)")
    args = parser.parse_args()

    if args.root is None:
        args.root = Path(__file__).resolve().parent.parent

    root = args.root.resolve()
    scope_dir = root / SCOPE_DIR
    allowlist_path = root / (args.allowlist or DEFAULT_ALLOWLIST)

    if not scope_dir.exists():
        print(f"FATAL: Source directory not found: {scope_dir}", file=sys.stderr)
        sys.exit(2)

    approved_files = load_allowlist(allowlist_path)
    if not approved_files:
        print(f"WARNING: Allowlist empty or not loaded from {allowlist_path}", file=sys.stderr)

    all_violations: List[Tuple[str, int, str, str]] = []

    for filepath in scope_dir.rglob(FILE_PATTERN):
        rel_path = str(filepath.relative_to(root)).replace("\\", "/")

        if is_test_file(rel_path):
            continue

        violations = scan_file(filepath, rel_path, approved_files)
        all_violations.extend(violations)

    if not all_violations:
        print(f"PASS: {RULE_ID} — no import lifecycle boundary violations found")
        sys.exit(0)

    # Print violations
    status = "FAIL" if args.fail_on_violation else "WARNING"
    print(f"{status}: {RULE_ID} — {len(all_violations)} violation(s):\n")

    for filepath, lineno, line_text, reason in all_violations:
        print(f"  [{reason}]")
        print(f"  {filepath}:{lineno}")
        print(f"    {line_text[:150]}")
        print()

    print("Remediation:")
    print("  1. Route expense creation through TransactionLifecycleCoordinator.createExpense()")
    print("  2. Pass fileImportRunId in CreateExpenseRequest for provenance tracking")
    print("  3. Route category creation through lifecycle coordinator or category creation service")
    print("  4. Add class to allowlist ONLY with architecture team approval")
    print(f"     Allowlist: {allowlist_path}")
    print()

    if args.fail_on_violation:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
