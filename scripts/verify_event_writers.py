#!/usr/bin/env python3
"""
verify_event_writers.py
Static guard for durable diagnostics / lifecycle events — PR 10 (CI failure mode)

Rules enforced:
  1. No direct PipelineDiagnosticEvent(...) construction outside DiagnosticEventWriter or allowlist.
  2. No direct TransactionEvent(...) construction outside TransactionLifecycleEventWriter/Coordinator or allowlist.
  3. No direct ReceiptEvent(...) construction outside ReceiptLifecycleEventWriter/Coordinator/SideEffectDispatcher or allowlist.
  4. No direct BackgroundJobRun(...) construction outside WorkerRunLogger or allowlist.
  5. No direct OperationRun(...) / OperationRunEvent(...) construction outside OperationRunRecorder or allowlist.
  6. No direct pipelineDiagnosticEventDao.insert/update outside DiagnosticEventWriter or allowlist.
  7. No direct operationRunDao.insert/update outside OperationRunRecorder or allowlist.
  8. No direct operationRunEventDao.insert outside OperationRunRecorder or allowlist.
  9. No direct backgroundJobRunDao.insert/update outside WorkerRunLogger or allowlist.
  10. No direct transactionEventDao.insert outside TransactionLifecycleEventWriter/Coordinator or allowlist.
  11. No direct receiptEventDao.insert outside ReceiptLifecycleEventWriter/Coordinator/SideEffectDispatcher or allowlist.

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

ENTITY_RULES = [
    (
        "PipelineDiagnosticEvent",
        re.compile(r'\bPipelineDiagnosticEvent\s*\('),
        ["DiagnosticEventWriter.kt"],
    ),
    (
        "TransactionEvent",
        re.compile(r'\bTransactionEvent\s*\('),
        ["TransactionLifecycleEventWriter.kt", "TransactionLifecycleCoordinator.kt"],
    ),
    (
        "ReceiptEvent",
        re.compile(r'\bReceiptEvent\s*\('),
        ["ReceiptLifecycleEventWriter.kt", "ReceiptLifecycleCoordinator.kt", "ReceiptSideEffectDispatcher.kt"],
    ),
    (
        "BackgroundJobRun",
        re.compile(r'\bBackgroundJobRun\s*\('),
        ["WorkerRunLogger.kt"],
    ),
    (
        "OperationRun",
        re.compile(r'\bOperationRun\s*\('),
        ["OperationRunRecorder.kt"],
    ),
    (
        "OperationRunEvent",
        re.compile(r'\bOperationRunEvent\s*\('),
        ["OperationRunRecorder.kt"],
    ),
]

DAO_RULES = [
    (
        "pipelineDiagnosticEventDao.insert",
        re.compile(r'\bpipelineDiagnosticEventDao\s*\.\s*(insert|update)\s*\('),
        ["DiagnosticEventWriter.kt"],
    ),
    (
        "operationRunDao.insert/update",
        re.compile(r'\boperationRunDao\s*\.\s*(insert|update|incrementCounters|finalizeIfRunning)\s*\('),
        ["OperationRunRecorder.kt"],
    ),
    (
        "operationRunEventDao.insert",
        re.compile(r'\boperationRunEventDao\s*\.\s*insert\s*\('),
        ["OperationRunRecorder.kt"],
    ),
    (
        "backgroundJobRunDao.insert/update",
        re.compile(r'\bbackgroundJobRunDao\s*\.\s*(insert|update)\s*\('),
        ["WorkerRunLogger.kt", "WorkerExecutionGuard.kt"],
    ),
    (
        "transactionEventDao.insert",
        re.compile(r'\btransactionEventDao\s*\.\s*insert\s*\('),
        ["TransactionLifecycleEventWriter.kt", "TransactionLifecycleCoordinator.kt"],
    ),
    (
        "receiptEventDao.insert",
        re.compile(r'\breceiptEventDao\s*\.\s*insert\s*\('),
        ["ReceiptLifecycleEventWriter.kt", "ReceiptLifecycleCoordinator.kt", "ReceiptSideEffectDispatcher.kt"],
    ),
]

EXEMPT_PATH_FRAGMENTS = [
    os.sep + "test" + os.sep,
    os.sep + "androidTest" + os.sep,
    "AppDatabase.kt",
    "entity" + os.sep,
]


def load_allowlist(script_dir: str) -> set:
    allowlist_path = os.path.join(script_dir, "event_writer_allowlist.txt")
    allowed = set()
    if not os.path.exists(allowlist_path):
        return allowed
    with open(allowlist_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            filename = line.split("#")[0].strip()
            if filename:
                allowed.add(filename)
    return allowed


def is_exempt(filepath: str) -> bool:
    for fragment in EXEMPT_PATH_FRAGMENTS:
        if fragment in filepath:
            return True
    return False


def is_allowed(filepath: str, allowed_substrings: list, allowlist_filenames: set) -> bool:
    basename = os.path.basename(filepath)
    if basename in allowlist_filenames:
        return True
    for allowed in allowed_substrings:
        if allowed in filepath or allowed in basename:
            return True
    return False


def scan(root: str, allowlist_filenames: set) -> list:
    violations = []
    all_rules = [(name, pattern, allowed, "entity") for name, pattern, allowed in ENTITY_RULES] + \
                [(name, pattern, allowed, "dao") for name, pattern, allowed in DAO_RULES]

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
            for rule_name, pattern, allowed_substrings, rule_type in all_rules:
                if is_allowed(filepath, allowed_substrings, allowlist_filenames):
                    continue
                for lineno, line in enumerate(lines, start=1):
                    stripped = line.lstrip()
                    if stripped.startswith("//") or stripped.startswith("*"):
                        continue
                    if pattern.search(line):
                        violations.append({
                            "rule": rule_name,
                            "file": filepath,
                            "line": lineno,
                            "text": line.rstrip(),
                            "type": rule_type,
                        })
    return violations


def main():
    parser = argparse.ArgumentParser(description="Verify event writer boundaries.")
    parser.add_argument("--fail-on-violation", action="store_true",
                        help="Exit with code 1 if violations are found.")
    parser.add_argument("--root", default=None,
                        help="Root directory to scan (default: auto-detect).")
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = args.root or os.path.dirname(script_dir)
    scan_root = os.path.join(project_root, "app", "src", "main", "java")

    if not os.path.isdir(scan_root):
        print(f"[verify_event_writers] Scan root not found: {scan_root}", file=sys.stderr)
        sys.exit(1)

    allowlist = load_allowlist(script_dir)
    print(f"[verify_event_writers] Scanning: {scan_root}")
    print(f"[verify_event_writers] Allowlist: {len(allowlist)} file(s)")
    violations = scan(scan_root, allowlist)

    if not violations:
        print("[verify_event_writers] ✅ No violations found.")
        sys.exit(0)

    print(f"\n[verify_event_writers] ❌ {len(violations)} violation(s) found:\n")
    for v in violations:
        rel = os.path.relpath(v["file"], project_root)
        print(f"  [{v['type'].upper()}] {v['rule']} — {rel}:{v['line']}")
        print(f"    {v['text']}")
        print()

    print("Fix: use designated writers. See scripts/event_writer_allowlist.txt to allowlist with reason.")

    if args.fail_on_violation:
        sys.exit(1)
    else:
        print("\n[verify_event_writers] (warning mode — pass --fail-on-violation to fail CI)")
        sys.exit(0)


if __name__ == "__main__":
    main()
