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
}

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
        print(f"WARNING: allowlist not found at {path}")
        return entries

    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

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
    return "".join(body_lines)


# ── Scan ──────────────────────────────────────────────────────────────────────

def scan(source_dir: str, allowlist: dict) -> list:
    violations = []

    for root, dirs, files in os.walk(source_dir):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]

        for filename in files:
            if not filename.endswith(".kt"):
                continue

            class_name = filename.removesuffix(".kt")

            if class_name.endswith("Dao"):
                continue

            filepath = os.path.join(root, filename)
            rel_path = os.path.relpath(filepath, PROJECT_ROOT)

            try:
                with open(filepath, encoding="utf-8") as f:
                    lines = f.readlines()
            except OSError:
                continue

            full_text = "".join(lines)
            entry = allowlist.get(class_name)

            # ── File-operation guard ──────────────────────────────────────
            if class_name not in FILE_OP_APPROVED:
                for lineno, line in enumerate(lines, start=1):
                    s = line.strip()
                    if s.startswith("//") or s.startswith("*"):
                        continue
                    if FILE_OP_PATTERN.search(line):
                        violations.append((
                            rel_path, lineno, line.rstrip(),
                            "FORBIDDEN_FILE_OP: DB file operation outside approved backup/restore class"
                        ))

            # ── DAO mutation guard ────────────────────────────────────────
            current_fun = None
            current_fun_start = 0

            for lineno, line in enumerate(lines, start=1):
                s = line.strip()
                if s.startswith("//") or s.startswith("*"):
                    continue

                # Track current function name
                m = FUN_PATTERN.match(line)
                if m:
                    current_fun = m.group(1)
                    current_fun_start = lineno - 1

                if "Dao" not in line:
                    continue
                if not MUTATION_PATTERN.search(line):
                    continue

                if entry is None:
                    # Not in allowlist at all
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

                # Check writeBarrier presence for non-coordinator allowlisted methods
                if entry.get("requires_write_barrier", True):
                    method_body = _extract_method_body(lines, current_fun_start)
                    if not WRITE_BARRIER_PATTERN.search(method_body):
                        violations.append((
                            rel_path, lineno, line.rstrip(),
                            f"MISSING_WRITE_BARRIER: {class_name}.{current_fun} has DAO mutation but no writeBarrier call"
                        ))

    return violations


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Verify DB access boundaries")
    parser.add_argument("--fail-on-violation", action="store_true")
    args = parser.parse_args()

    if not os.path.isdir(SOURCE_DIR):
        print(f"ERROR: source directory not found: {SOURCE_DIR}")
        sys.exit(1)

    allowlist = load_allowlist(ALLOWLIST_PATH)
    violations = scan(SOURCE_DIR, allowlist)

    if not violations:
        print("PASS: DB access boundaries — no unauthorized DAO mutations found.")
        sys.exit(0)

    status = "FAIL" if args.fail_on_violation else "WARNING"
    print(f"{status}: DB access boundaries — {len(violations)} violation(s):\n")

    for rel_path, lineno, line_text, reason in violations:
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
