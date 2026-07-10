#!/usr/bin/env python3
"""
verify_db_access_boundaries.py
Global Write/Read/Restore Barrier — PR 6/10 (CI failure mode)

Scans app/src/main/java for:
  1. Direct DAO mutation calls outside the approved writer allowlist.
  2. Allowlisted methods that are missing writeBarrier.checkWritesAllowed.
  3. debug_only methods missing BuildConfig.DEBUG guard.
  4. Forbidden DB file operations outside approved backup/restore classes.

Exit codes:
  0 — no violations (or warning mode)
  1 — violations found AND --fail-on-violation flag is set

Usage:
  python3 scripts/verify_db_access_boundaries.py
  python3 scripts/verify_db_access_boundaries.py --fail-on-violation
"""

import os
import re
import sys
import argparse

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Patterns ──────────────────────────────────────────────────────────────────

MUTATION_PATTERN = re.compile(
    r'\.\s*(?:insert|insertAll|update|delete|deleteAll|clear|replace|upsert|'
    r'set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|'
    r'bulkRename|approve|reject)\s*\('
)

FILE_OP_PATTERN = re.compile(
    r'(?:getDatabasePath|\.deleteRecursively\(\)|openDatabase|'
    r'writableDatabase\s*(?://.*)?$|execSQL\s*\()'
)

WRITE_BARRIER_PATTERN = re.compile(r'writeBarrier\s*\.\s*(?:checkWritesAllowed|runWrite)\s*\(')
DEBUG_GUARD_PATTERN = re.compile(r'BuildConfig\s*\.\s*DEBUG')
FUN_PATTERN = re.compile(r'^\s*(?:suspend\s+)?fun\s+(\w+)\s*\(')

# ── Paths ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
SOURCE_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java")
ALLOWLIST_PATH = os.path.join(PROJECT_ROOT, "config", "db_access_allowlist.yml")

SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# Classes approved for DB file operations (backup/restore infrastructure)
FILE_OP_APPROVED = {
    "DatabaseBackupRepositoryImpl",
    "AppStartupCoordinator",
    "RestoreDatabaseOpenerImpl",
    "AppDatabase",
    "BackupVerifier",
    "CostbackupBundle",
    "RestoreJournal",
    "ExportAnonymizer",
    "BackupEncryptionService",
    "DeterministicExpenseExportPager",
    "DatabaseIntegrityScanner",
    "LegacyDataMigrationService",
    "SqliteSnapshotCreator",
}

# Structural exceptions for exact operations that are intrinsically required.
# Format: (file_suffix, class_name, method_pattern, operation_type)
# These are NOT architectural debt — they are proven structural necessities.
STRUCTURAL_EXCEPTIONS = [
    # Exact migration SQL inside named MIGRATION objects
    ("DatabaseMigrations.kt", "DatabaseMigrations", r"MIGRATION_\d+_\d+", "execSQL"),
    # Exact rescue operations under exclusive maintenance ownership
    ("FinancialRescueCoordinator.kt", "FinancialRescueCoordinator", r"performMaintenanceRescue", "raw_sqlite"),
]

# ── YAML parse ────────────────────────────────────────────────────────────────

def load_allowlist(path: str) -> dict:
    """
    Returns dict: class_name -> {
        'daos': set,
        'methods_only': set or None,
        'debug_only': bool,
        'requires_write_barrier': bool,  # default True unless coordinator
        'reason': str
    }
    """
    entries = {}
    if not os.path.exists(path):
        print(f"ERROR: allowlist not found at {path}", file=sys.stderr)
        sys.exit(2)

    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    try:
        entries = _parse_allowlist_lines(lines)
    except Exception as e:
        print(f"ERROR: malformed allowlist file at {path}: {e}", file=sys.stderr)
        sys.exit(2)

    return entries


def _parse_allowlist_lines(lines: list) -> dict:
    """Parse allowlist YAML-like lines into the entries dict."""
    entries = {}
    current = None
    in_daos = False
    in_methods = False
    _merging = False

    for line in lines:
        stripped = line.strip()

        if stripped.startswith("- class:"):
            cls_name = stripped[len("- class:"):].strip()
            if cls_name in entries:
                # Merge into existing — preserve the first entry's settings
                # (first entry is the main allowed_writers entry)
                current = entries[cls_name]
                # Don't overwrite methods_only or debug_only from a secondary entry
                _merging = True
            else:
                _merging = False
                current = {
                    "class": cls_name,
                    "daos": set(),
                    "methods_only": None,
                    "debug_only": False,
                    "requires_write_barrier": True,
                    "reason": "",
                }
                entries[cls_name] = current
            in_daos = False
            in_methods = False

        elif current is None:
            continue

        elif stripped == "daos:":
            in_daos = True
            in_methods = False

        elif stripped == "methods_only:":
            in_methods = True
            in_daos = False
            if not _merging:
                current["methods_only"] = set()

        elif stripped.startswith("- ") and in_daos:
            current["daos"].add(stripped[2:].strip())

        elif stripped.startswith("- ") and in_methods:
            if current["methods_only"] is not None:
                current["methods_only"].add(stripped[2:].strip())

        elif stripped.startswith("reason:"):
            current["reason"] = stripped[len("reason:"):].strip().strip('"').strip("'")
            in_daos = False
            in_methods = False

        elif stripped.startswith("debug_only:"):
            if not _merging:
                val = stripped[len("debug_only:"):].strip().lower()
                current["debug_only"] = val == "true"
            in_daos = False
            in_methods = False

        elif stripped.startswith("requires_write_barrier:"):
            val = stripped[len("requires_write_barrier:"):].strip().lower()
            current["requires_write_barrier"] = val != "false"
            in_daos = False
            in_methods = False

        elif stripped.startswith("allowed_until:") or stripped.startswith("class:"):
            in_daos = False
            in_methods = False

    return entries


LOCAL_DAO_ASSIGN = re.compile(r'\bval\s+(\w+)\s*=\s*\w+\.(\w+Dao)\s*\(')
# Detect direct chain: database.someDao().update(...)
DIRECT_CHAIN_DAO = re.compile(r'\w+\.\w+Dao\s*\(\s*\)\s*\.\s*(?:insert|insertAll|update|delete|deleteAll|clear|replace|upsert|set|mark|link|unlink|increment|suppress|claim|fulfill|restore|save|bulkRename|approve|reject)\s*\(')


def _extract_method_body(lines: list, start: int) -> str:
    """Extract the body of the function starting at line index `start`."""
    depth = 0
    body_lines = []
    started = False
    for i in range(start, min(start + 80, len(lines))):
        line = lines[i]
        for ch in line:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                depth -= 1
        body_lines.append(line)
        if started and depth == 0:
            break
        # Expression-bodied: no braces — single line
        if not started and '=' in line and i == start:
            body_lines.append(line)
            break
    return "".join(body_lines)


def _barrier_before_line(lines: list, fun_start: int, mutation_lineno: int) -> bool:
    """Return True if a writeBarrier call appears between fun_start and mutation_lineno."""
    for i in range(fun_start, min(mutation_lineno - 1, len(lines))):
        if WRITE_BARRIER_PATTERN.search(lines[i]):
            return True
    return False


def _matches_structural_exception(
    rel_path: str,
    class_name: str,
    current_fun: str | None,
    lineno: int,
    line: str,
    lines: list,
) -> bool:
    """Return True if the file operation matches a proven structural exception.

    Checks: file_suffix, class_name, operation_type in line,
    and method_pattern in either current_fun or nearby enclosing context.

    Special op_type prefixes:
      - "raw_" matches any file operation (catch-all wildcard for a class/method)
      - otherwise the op_type string must appear literally in the line
    """
    for file_suffix, exc_class, method_pattern, op_type in STRUCTURAL_EXCEPTIONS:
        if not rel_path.replace("\\", "/").endswith(file_suffix):
            continue
        if class_name != exc_class:
            continue
        # "raw_" is a wildcard: matches any file op in the approved class/method
        if not op_type.startswith("raw_"):
            if op_type not in line:
                continue
        # Check method_pattern in current function name
        if current_fun and re.search(method_pattern, current_fun):
            return True
        # Search backwards in lines for the pattern (for val/object declarations)
        for back in range(max(0, lineno - 15), lineno):
            if re.search(method_pattern, lines[back]):
                return True
    return False


# ── Scan ──────────────────────────────────────────────────────────────────────

def scan(source_dir: str, allowlist: dict) -> tuple[list, int]:
    violations = []
    files_scanned = 0

    for root, dirs, files in os.walk(source_dir):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]

        for filename in files:
            if not filename.endswith(".kt"):
                continue

            files_scanned += 1

            class_name = filename.removesuffix(".kt")

            if class_name.endswith("Dao"):
                continue

            filepath = os.path.join(root, filename)
            rel_path = os.path.relpath(filepath, PROJECT_ROOT)

            try:
                with open(filepath, encoding="utf-8") as f:
                    lines = f.readlines()
            except OSError as e:
                violations.append((
                    rel_path, 0, "",
                    f"ERROR: cannot read {rel_path}: {e}"
                ))
                continue

            full_text = "".join(lines)
            entry = allowlist.get(class_name)

            # ── File-operation guard ──────────────────────────────────────
            file_op_fun = None
            for lineno, line in enumerate(lines, start=1):
                s = line.strip()
                if s.startswith("//") or s.startswith("*"):
                    continue

                # Track current function name
                m = FUN_PATTERN.match(line)
                if m:
                    file_op_fun = m.group(1)

                if not FILE_OP_PATTERN.search(line):
                    continue

                # Check structural exceptions first
                if _matches_structural_exception(
                    rel_path, class_name, file_op_fun, lineno, line, lines
                ):
                    continue

                # Check FILE_OP_APPROVED
                if class_name in FILE_OP_APPROVED:
                    continue

                violations.append((
                    rel_path, lineno, line.rstrip(),
                    "FORBIDDEN_FILE_OP: DB file operation outside approved backup/restore class"
                ))

            # ── DAO mutation guard ────────────────────────────────────────
            current_fun = None
            current_fun_start = 0
            # Track local DAO variable names: var_name -> True
            local_dao_vars: set = set()
            # Track pending multi-line DAO assignment: variable name being assigned
            pending_dao_var: str | None = None

            for lineno, line in enumerate(lines, start=1):
                s = line.strip()
                if s.startswith("//") or s.startswith("*"):
                    continue

                # Track local DAO variable assignments: val dao = database.scannedReceiptDao()
                m_assign = LOCAL_DAO_ASSIGN.search(line)
                if m_assign:
                    local_dao_vars.add(m_assign.group(1))
                    pending_dao_var = None
                else:
                    # Multi-line: val dao =\n    database.scannedReceiptDao()
                    m_pending_start = re.search(r'\bval\s+(\w+)\s*=\s*$', line.rstrip())
                    if m_pending_start:
                        pending_dao_var = m_pending_start.group(1)
                    elif pending_dao_var and re.search(r'\w+Dao\s*\(', line):
                        local_dao_vars.add(pending_dao_var)
                        pending_dao_var = None
                    else:
                        pending_dao_var = None

                # Track current function name (including expression-bodied)
                m = FUN_PATTERN.match(line)
                if m:
                    current_fun = m.group(1)
                    current_fun_start = lineno - 1
                    local_dao_vars.clear()
                    pending_dao_var = None

                # Check for direct chain: database.someDao().update(...)
                if DIRECT_CHAIN_DAO.search(line) and not s.startswith("//"):
                    if entry is None:
                        violations.append((rel_path, lineno, line.rstrip(), "UNALLOWLISTED_CLASS_DIRECT_CHAIN"))
                    elif entry.get("requires_write_barrier", True):
                        if not _barrier_before_line(lines, current_fun_start, lineno):
                            violations.append((
                                rel_path, lineno, line.rstrip(),
                                f"MISSING_WRITE_BARRIER_DIRECT_CHAIN: {class_name}.{current_fun}"
                            ))

                # Check for DAO mutation — either via "Dao" reference or local dao var
                has_dao_ref = "Dao" in line
                has_local_dao = any(
                    re.search(r'\b' + re.escape(v) + r'\s*\.', line)
                    for v in local_dao_vars
                ) if local_dao_vars else False

                if not has_dao_ref and not has_local_dao:
                    continue
                if not MUTATION_PATTERN.search(line):
                    continue

                if entry is None:
                    violations.append((rel_path, lineno, line.rstrip(), "UNALLOWLISTED_CLASS"))
                    continue

                # Class is allowlisted — check method-level constraints
                if entry["methods_only"] is not None and current_fun is not None:
                    if current_fun not in entry["methods_only"]:
                        violations.append((
                            rel_path, lineno, line.rstrip(),
                            f"METHOD_NOT_ALLOWED: {current_fun} not in methods_only for {class_name}"
                        ))
                        continue

                # Check debug_only
                if entry["debug_only"]:
                    method_body = _extract_method_body(lines, current_fun_start)
                    if not DEBUG_GUARD_PATTERN.search(method_body):
                        violations.append((
                            rel_path, lineno, line.rstrip(),
                            f"MISSING_DEBUG_GUARD: {class_name}.{current_fun} is debug_only but lacks BuildConfig.DEBUG"
                        ))

                # Check writeBarrier appears BEFORE mutation line
                if entry.get("requires_write_barrier", True):
                    if not _barrier_before_line(lines, current_fun_start, lineno):
                        violations.append((
                            rel_path, lineno, line.rstrip(),
                            f"MISSING_WRITE_BARRIER: {class_name}.{current_fun} has DAO mutation but no writeBarrier before it"
                        ))

    return violations, files_scanned


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Verify DB access boundaries")
    parser.add_argument("--fail-on-violation", action="store_true")
    args = parser.parse_args()

    if not os.path.isdir(SOURCE_DIR):
        print(f"ERROR: source directory not found: {SOURCE_DIR}", file=sys.stderr)
        sys.exit(2)

    allowlist = load_allowlist(ALLOWLIST_PATH)
    violations, files_scanned = scan(SOURCE_DIR, allowlist)

    if files_scanned == 0:
        print("ERROR: no Kotlin source files found to scan in " + SOURCE_DIR, file=sys.stderr)
        sys.exit(2)

    # Separate infrastructure errors (unreadable files) from real violations
    read_errors = [v for v in violations if v[1] == 0 and v[3].startswith("ERROR:")]
    real_violations = [v for v in violations if v not in read_errors]

    if read_errors:
        for _, _, _, reason in read_errors:
            print(reason, file=sys.stderr)
        sys.exit(2)

    if not real_violations:
        print("PASS: DB access boundaries — no unauthorized DAO mutations found.")
        sys.exit(0)

    status = "FAIL" if args.fail_on_violation else "WARNING"
    print(f"{status}: DB access boundaries — {len(real_violations)} violation(s):\n")

    for rel_path, lineno, line_text, reason in real_violations:
        print(f"  [{reason}]")
        print(f"  {rel_path}:{lineno}")
        print(f"    {line_text.strip()}")
        print()

    print("For each violation, either:")
    print("  1. Add/update the class in config/db_access_allowlist.yml with a reason.")
    print("  2. Add writeBarrier.checkWritesAllowed() to the method.")
    print("  3. Route the write through the approved lifecycle coordinator.")
    print()
    print("See docs/DB_WRITE_OWNERSHIP.md for the ownership map.")

    if args.fail_on_violation:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
