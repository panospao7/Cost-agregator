#!/usr/bin/env python3
"""
verify_event_writers.py
Static guard for durable diagnostics / lifecycle events — PR 9 (CI failure mode)

Rules enforced:
  1. No direct PipelineDiagnosticEvent(...) construction outside DiagnosticEventWriter
     or test/migration files.
  2. No direct TransactionEvent(...) construction outside TransactionLifecycleEventWriter
     or TransactionLifecycleCoordinator or test/migration files.
  3. No direct ReceiptEvent(...) construction outside ReceiptLifecycleEventWriter
     or ReceiptLifecycleCoordinator or ReceiptSideEffectDispatcher or test/migration files.
  4. No direct BackgroundJobRun(...) construction outside WorkerRunLogger or test/migration files.
  5. No direct OperationRun(...) construction outside RoomOperationRunRecorder or test/migration files.

Exit codes:
  0 — no violations
  1 — violations found AND --fail-on-violation flag is set

Usage:
  python3 scripts/verify_event_writers.py
  python3 scripts/verify_event_writers.py --fail-on-violation
"""

import os
import re
import sys
import argparse

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Rules ─────────────────────────────────────────────────────────────────────

# Each rule: (entity_name, construction_pattern, allowed_file_substrings)
RULES = [
    (
        "PipelineDiagnosticEvent",
        re.compile(r'\bPipelineDiagnosticEvent\s*\('),
        [
            "DiagnosticEventWriter.kt",
            "RoomDiagnosticEventWriter",
            # migrations are in AppDatabase.kt but use SQL, not Kotlin constructors
        ],
    ),
    (
        "TransactionEvent",
        re.compile(r'\bTransactionEvent\s*\('),
        [
            "TransactionLifecycleEventWriter.kt",
            "TransactionLifecycleCoordinator.kt",
        ],
    ),
    (
        "ReceiptEvent",
        re.compile(r'\bReceiptEvent\s*\('),
        [
            "ReceiptLifecycleEventWriter.kt",
            "ReceiptLifecycleCoordinator.kt",
            "ReceiptSideEffectDispatcher.kt",
        ],
    ),
    (
        "BackgroundJobRun",
        re.compile(r'\bBackgroundJobRun\s*\('),
        [
            "WorkerRunLogger.kt",
        ],
    ),
    (
        "OperationRun",
        re.compile(r'\bOperationRun\s*\('),
        [
            "OperationRunRecorder.kt",
        ],
    ),
    (
        "OperationRunEvent",
        re.compile(r'\bOperationRunEvent\s*\('),
        [
            "OperationRunRecorder.kt",
        ],
    ),
]

# Paths that are always exempt (tests, migrations, entity definitions)
EXEMPT_PATH_FRAGMENTS = [
    os.sep + "test" + os.sep,
    os.sep + "androidTest" + os.sep,
    "AppDatabase.kt",          # migration SQL — no Kotlin constructors
    "entity" + os.sep,         # entity data class definitions themselves
]

# ── Scanner ───────────────────────────────────────────────────────────────────

def is_exempt(filepath: str) -> bool:
    for fragment in EXEMPT_PATH_FRAGMENTS:
        if fragment in filepath:
            return True
    return False


def is_allowed(filepath: str, allowed_substrings: list) -> bool:
    basename = os.path.basename(filepath)
    for allowed in allowed_substrings:
        if allowed in filepath or allowed in basename:
            return True
    return False


def scan(root: str) -> list:
    violations = []
    for dirpath, _, filenames in os.walk(root):
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            filepath = os.path.join(dirpath, filename)
            if is_exempt(filepath):
                continue
            try:
                with open(filepath, encoding="utf-8", errors="replace") as f:
                    lines = f.readlines()
            except OSError:
                continue
            for entity_name, pattern, allowed_substrings in RULES:
                if is_allowed(filepath, allowed_substrings):
                    continue
                for lineno, line in enumerate(lines, start=1):
                    # Skip comment lines
                    stripped = line.lstrip()
                    if stripped.startswith("//") or stripped.startswith("*"):
                        continue
                    if pattern.search(line):
                        violations.append({
                            "entity": entity_name,
                            "file": filepath,
                            "line": lineno,
                            "text": line.rstrip(),
                        })
    return violations


def main():
    parser = argparse.ArgumentParser(description="Verify event writer boundaries.")
    parser.add_argument("--fail-on-violation", action="store_true",
                        help="Exit with code 1 if violations are found.")
    parser.add_argument("--root", default=None,
                        help="Root directory to scan (default: auto-detect).")
    args = parser.parse_args()

    # Auto-detect project root
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = args.root or os.path.dirname(script_dir)
    scan_root = os.path.join(project_root, "app", "src", "main", "java")

    if not os.path.isdir(scan_root):
        print(f"[verify_event_writers] Scan root not found: {scan_root}", file=sys.stderr)
        sys.exit(1)

    print(f"[verify_event_writers] Scanning: {scan_root}")
    violations = scan(scan_root)

    if not violations:
        print("[verify_event_writers] ✅ No violations found.")
        sys.exit(0)

    print(f"\n[verify_event_writers] ❌ {len(violations)} violation(s) found:\n")
    for v in violations:
        rel = os.path.relpath(v["file"], project_root)
        print(f"  {v['entity']} — {rel}:{v['line']}")
        print(f"    {v['text']}")
        print()

    print("Fix: construct event entities only through their designated writers.")
    print("  PipelineDiagnosticEvent  → DiagnosticEventWriter")
    print("  TransactionEvent         → TransactionLifecycleEventWriter / TransactionLifecycleCoordinator")
    print("  ReceiptEvent             → ReceiptLifecycleEventWriter / ReceiptLifecycleCoordinator / ReceiptSideEffectDispatcher")
    print("  BackgroundJobRun         → WorkerRunLogger")
    print("  OperationRun/Event       → OperationRunRecorder")

    if args.fail_on_violation:
        sys.exit(1)
    else:
        print("\n[verify_event_writers] (warning mode — pass --fail-on-violation to fail CI)")
        sys.exit(0)


if __name__ == "__main__":
    main()
