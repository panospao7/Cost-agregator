#!/usr/bin/env python3
"""
verify_receipt_link_boundaries.py
Static architecture guard for receipt link ownership.

Rule: G-RCPT-LINK-01

Scans the declared production Kotlin source scope (the roots of the
checked-in manifest ``config/guards/production_source_roots.yml`` via
``scripts/guardrails/production_source_scope.py`` — currently
``app/src/main/java``), enumerating EVERY declared production file and then
applying the guard-specific approved-path/test semantic filter. Detects
ScannedReceipt.expenseId being mutated or scannedReceiptDao being
called directly outside the approved receipt link service and lifecycle paths.

Detection patterns:
  1. Direct updates to ScannedReceipt.expenseId outside ReceiptLinkService.kt
     (copies that set expenseId, e.g. receipt.copy(expenseId = ...))
  2. Direct scannedReceiptDao mutations (.insert, .update, .delete, .deleteAll)
     outside approved files
  3. SQL UPDATE/INSERT on scanned_receipts table outside approved paths
  4. expenseId setter calls on receipt entities outside ReceiptLinkService

Exit codes:
  0 — no violations (or warning mode without --fail-on-violation)
  1 — violations found AND --fail-on-violation is set
  2 — script error (fatal)

Usage:
  python3 scripts/verify_receipt_link_boundaries.py
  python3 scripts/verify_receipt_link_boundaries.py --fail-on-violation
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import List, Set, Tuple, Optional

# ── Configuration ──────────────────────────────────────────────────────────
RULE_ID = "G-RCPT-LINK-01"
DESCRIPTION = "Receipt link ownership guard — detects ScannedReceipt.expenseId mutations outside approved paths"
FILE_PATTERN = "*.kt"
DEFAULT_ALLOWLIST = "scripts/allowlists/receipt_link_allowlist.yml"

SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# PR-GR-10B: the scanned production source scope is declared by the
# checked-in manifest ``config/guards/production_source_roots.yml`` (resolved
# via scripts/guardrails/production_source_scope.py).  There is NO
# conventional-root fallback: a missing, malformed, or undeclared manifest
# fails closed with exit 2.  The historical hard-coded root
# (``app/src/main/java``) is the manifest's currently declared single root,
# so the scanned file set is unchanged.

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
)

# ── Approved files (set from allowlist + hardcoded base set) ─────────────
# These are files where ScannedReceiptDao mutations and expenseId writes are
# inherently safe (DAO definitions, Room-generated code).
BASE_APPROVED_FILES = {
    "ScannedReceiptDao_Impl",      # Room-generated DAO implementation
    "ScannedReceiptDao",           # DAO interface — defines operations
    "ScannedReceipt",              # Entity — defines data class
    "ReceiptInsertResolver",       # Resolver for insert-or-resolve patterns
    "AppDatabase",                 # Database definition
    "DaoModule",                   # DI module — no mutations
    "RetentionModule",             # DI module
}

# ── Mutation patterns ──────────────────────────────────────────────────────

# Pattern 1: expenseId set via copy() on a receipt
# Matches: receipt.copy(expenseId = ...) or ScannedReceipt(... expenseId = ...,
# but only the copy() variant is a mutation of existing data
EXPENSE_ID_COPY_RE = re.compile(
    r'\.copy\s*\(\s*.*?\bexpenseId\s*=\s*',
    re.DOTALL
)

# Pattern 1b: Direct ScannedReceipt constructor with non-null expenseId
# Matches: ScannedReceipt(... expenseId = <non-null>, ...)
# Only flags when expenseId is explicitly set (not default null)
EXPENSE_ID_CONSTRUCTOR_RE = re.compile(
    r'ScannedReceipt\s*\([^)]*\bexpenseId\s*=\s*(?!\s*null\b)[^,)]+',
    re.DOTALL
)

# Pattern 2: Direct scannedReceiptDao mutation calls
# Matches: scannedReceiptDao.<mutation>(...) or dao.<mutation>(...) when dao is a receipt dao
SCANNED_RECEIPT_DAO_MUTATION_RE = re.compile(
    r'\bscannedReceiptDao\s*\.\s*(?:insert|insertAll|update|delete|deleteAll|clear|'
    r'claimForAutoMatch|linkToExpense|unlinkReceipt|setExpenseId)\s*\('
)

# Pattern 2b: Local variable referencing ScannedReceiptDao mutations
# More general: any variable ending with "ReceiptDao" performing mutations
LOCAL_RECEIPT_DAO_MUTATION_RE = re.compile(
    r'(\w*[Rr]eceiptDao)\s*\.\s*(?:insert|insertAll|update|delete|deleteAll|clear)\s*\('
)

# Pattern 3: SQL on scanned_receipts table
SQL_SCANNED_RECEIPTS_RE = re.compile(
    r'(?:UPDATE|INSERT\s+INTO|DELETE\s+FROM)\s+["\'`]?scanned_receipts["\'`]?',
    re.IGNORECASE
)

# Pattern 4: expenseId = <value> in assignment context (for receipt entities)
# More targeted: expenseId on its own line or in an assignment
EXPENSE_ID_ASSIGN_RE = re.compile(
    r'\bexpenseId\s*=\s*(?!\s*null\b)[^,;\n)]+'
)

# ── YAML Allowlist Parsing ─────────────────────────────────────────────────

def load_allowlist(path: Path) -> Set[str]:
    """
    Load allowlisted class names from YAML file.
    Returns a set of class names (without .kt extension).
    Expects a flat-list format with `path` field containing the class name.

    Exits with code 2 on infrastructure errors.
    """
    approved: Set[str] = set()
    if not path.exists():
        print(f"ERROR: Allowlist not found: {path}", file=sys.stderr)
        sys.exit(2)

    try:
        import yaml
    except ImportError:
        print("ERROR: PyYAML not installed. pip install pyyaml", file=sys.stderr)
        sys.exit(2)

    try:
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except yaml.YAMLError as e:
        print(f"ERROR: Malformed allowlist: {e}", file=sys.stderr)
        sys.exit(2)
    except Exception as e:
        print(f"ERROR: Could not parse allowlist: {e}", file=sys.stderr)
        sys.exit(2)

    if not data or not isinstance(data, list):
        print(f"ERROR: Allowlist is not a list: {path}", file=sys.stderr)
        sys.exit(2)

    for entry in data:
        if not isinstance(entry, dict):
            continue
        entry_path = entry.get("path", "")
        if not entry_path:
            continue
        # `path` may be a simple class name like "ReceiptLinkService" or "ReceiptLinkService.kt"
        cls = entry_path.replace(".kt", "").strip()
        if cls:
            approved.add(cls)

    return approved


# ── Detection Functions ────────────────────────────────────────────────────

def is_test_file(filepath: str) -> bool:
    """Check if file is in a test directory."""
    parts = filepath.replace("\\", "/").split("/")
    return any(d in SKIP_DIRS for d in parts)


def is_migration_file(class_name: str) -> bool:
    """Check if class name suggests a Room migration file."""
    return "Migration" in class_name


def is_approved(class_name: str, approved_files: Set[str]) -> bool:
    """Check if a class is in the approved set."""
    return class_name in approved_files or class_name in BASE_APPROVED_FILES or is_migration_file(class_name)


def is_dao_interface(filename: str) -> bool:
    """Check if file is a DAO interface (ends with Dao.kt)."""
    return filename.endswith("Dao.kt")


def scan_file(filepath: Path, rel_path: str, approved_files: Set[str]) -> List[Tuple[str, int, str, str]]:
    """
    Scan a single Kotlin file for receipt link boundary violations.

    Returns list of (filepath, line_number, line_text, reason_code).
    """
    violations: List[Tuple[str, int, str, str]] = []
    class_name = filepath.stem

    if is_dao_interface(filepath.name):
        return violations

    if not is_approved(class_name, approved_files):
        # Unapproved file — check for all patterns
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                lines = f.readlines()
        except Exception as e:
            print(f"ERROR reading {filepath}: {e}", file=sys.stderr)
            return violations

        for lineno, line in enumerate(lines, start=1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue

            # Pattern 1: expenseId copy()
            if EXPENSE_ID_COPY_RE.search(line):
                violations.append((
                    rel_path, lineno, stripped,
                    "DIRECT_EXPENSEID_COPY: expenseId set via copy() outside approved path"
                ))
                continue

            # Pattern 1b: ScannedReceipt constructor with expenseId set
            if EXPENSE_ID_CONSTRUCTOR_RE.search(line):
                violations.append((
                    rel_path, lineno, stripped,
                    "DIRECT_EXPENSEID_CONSTRUCTOR: ScannedReceipt expenseId set at construction outside approved path"
                ))
                continue

            # Pattern 2: scannedReceiptDao mutations
            if SCANNED_RECEIPT_DAO_MUTATION_RE.search(line):
                violations.append((
                    rel_path, lineno, stripped,
                    "DIRECT_RECEIPT_DAO_MUTATION: scannedReceiptDao mutation outside approved path"
                ))
                continue

            # Pattern 2b: local receipt dao variable mutations
            # Only flag if the matched variable is NOT allowed and contains "ReceiptDao"
            m = LOCAL_RECEIPT_DAO_MUTATION_RE.search(line)
            if m:
                var_name = m.group(1)
                # Don't flag if it's just the DAO class itself
                if "Dao" in var_name and "ReceiptDao" not in var_name:
                    pass  # likely a different DAO (e.g., emailReceiptDao, receiptEventDao)
                else:
                    violations.append((
                        rel_path, lineno, stripped,
                        f"DIRECT_RECEIPT_DAO_MUTATION: {var_name} mutation outside approved path"
                    ))
                    continue

            # Pattern 3: SQL on scanned_receipts
            if SQL_SCANNED_RECEIPTS_RE.search(line):
                violations.append((
                    rel_path, lineno, stripped,
                    "RAW_SQL_SCANNED_RECEIPTS: raw SQL mutation on scanned_receipts outside approved path"
                ))
                continue

            # Pattern 4: expenseId assignment (broader)
            if EXPENSE_ID_ASSIGN_RE.search(line):
                # Only flag if it looks like it's setting a receipt's expenseId
                # not just reading it in a query or comparison
                if "=" in stripped and "==" not in stripped and "!=" not in stripped:
                    # Check context: if the line also mentions receipt or scanned
                    lower_line = stripped.lower()
                    if any(kw in lower_line for kw in ["receipt", "scanned"]):
                        violations.append((
                            rel_path, lineno, stripped,
                            "EXPENSEID_ASSIGN: receipt expenseId assignment outside approved path"
                        ))

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

    # PR-GR-10B: resolve the production source scope from the checked-in
    # manifest (fail closed — no conventional-root fallback).
    root_set, scope_diagnostics = resolve_production_source_scope(str(root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"FATAL: production source scope unresolved: {codes}",
            file=sys.stderr,
        )
        sys.exit(2)

    allowlist_path = root / (args.allowlist or DEFAULT_ALLOWLIST)

    approved_files = load_allowlist(allowlist_path)
    if not approved_files:
        print(f"WARNING: Allowlist empty from {allowlist_path}", file=sys.stderr)

    all_violations: List[Tuple[str, int, str, str]] = []

    try:
        source_files = list(iter_production_kotlin_files(str(root), root_set))
    except ProductionSourceScopeError as exc:
        print(
            f"FATAL: production source enumeration failed: {exc.code}",
            file=sys.stderr,
        )
        sys.exit(2)

    for source_file in source_files:
        rel_path = source_file.repository_relative_path

        if is_test_file(rel_path):
            continue

        violations = scan_file(source_file.absolute_path, rel_path, approved_files)
        all_violations.extend(violations)

    if not all_violations:
        print(f"PASS: {RULE_ID} — no receipt link boundary violations found")
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
    print("  1. Route receipt expenseId mutations through ReceiptLinkService.linkReceiptToExpense()")
    print("  2. Route receipt DAO writes through ReceiptLifecycleCoordinator")
    print("  3. Add class to allowlist ONLY with architecture team approval and valid reason")
    print(f"     Allowlist: {allowlist_path}")
    print()

    if args.fail_on_violation:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
