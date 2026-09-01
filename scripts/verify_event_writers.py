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

Scan scope (PR-GR-10B): every declared production Kotlin root of the
checked-in manifest ``config/guards/production_source_roots.yml``,
enumerated through the neutral fail-closed enumerator
``scripts/guardrails/production_source_scope.py`` (deterministic
root-order then canonical path-order; currently ``app/src/main/java``).
The writer/allowlist rules are a semantic per-file filter, not a root
filter.  A missing, malformed, or undeclared manifest fails closed with
exit 2 — there is NO conventional-root fallback.  Enumeration is fail
closed too: an unreadable subtree/file or a symlinked (or
repository-escaping) ``.kt`` entry raises a controlled diagnostic and
exits 2 — a partial scan is never reported as a pass (exit 0) or as a
violation (exit 1).

Exit codes:
  0 — no violations
  1 — violations found AND --fail-on-violation flag is set
  2 — infrastructure error (production source scope unresolved, or
      fail-closed enumeration failure: unreadable tree / symlink escape)

Usage:
  python3 scripts/verify_event_writers.py
  python3 scripts/verify_event_writers.py --fail-on-violation
"""

import os
import re
import sys

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    PRODUCTION_SOURCE_SCOPE_UNREADABLE,
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
)
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


def scan(source_files, allowlist_filenames: set) -> list:
    """Scan enumerated production Kotlin sources for event-writer violations.

    PR-GR-10B: ``source_files`` comes from the neutral fail-closed
    enumerator ``iter_production_kotlin_files`` (deterministic root-order
    then canonical path-order ``ProductionSourceFile`` values), so an
    unreadable subtree/file or a symlink escape can never silently shrink
    the scanned surface.  Per-file detection semantics — exemptions,
    allowlist, comment skipping, rule matching — are unchanged from the
    pre-GR-10B per-root walk.
    """
    violations = []
    all_rules = [(name, pattern, allowed, "entity") for name, pattern, allowed in ENTITY_RULES] + \
                [(name, pattern, allowed, "dao") for name, pattern, allowed in DAO_RULES]

    for source_file in source_files:
        filepath = source_file.absolute_path
        if is_exempt(filepath):
            continue
        try:
            with open(filepath, encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
        except OSError:
            # Fail closed: enumeration vouches for readable regular files,
            # so a read failure here must never silently shrink the scanned
            # surface into a false pass.  The controlled error carries only
            # the diagnostic code — no paths, no exception text.
            raise ProductionSourceScopeError(
                PRODUCTION_SOURCE_SCOPE_UNREADABLE
            ) from None
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

    # PR-GR-10B: resolve the production source scope from the checked-in
    # manifest (fail closed — no conventional-root fallback).  Every
    # declared root is enumerated through the neutral module; the
    # writer/allowlist relevance rules are a semantic filter, not a root
    # filter.
    root_set, scope_diagnostics = resolve_production_source_scope(str(project_root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"[verify_event_writers] production source scope unresolved: {codes}",
            file=sys.stderr,
        )
        sys.exit(2)

    allowlist = load_allowlist(script_dir)

    # PR-GR-10B: every declared production root is enumerated once through
    # the neutral fail-closed enumerator.  An unreadable subtree/file or a
    # symlink escape raises ProductionSourceScopeError and must exit 2 —
    # never a partial scan reported as pass (exit 0) or violation (exit 1).
    try:
        source_files = list(
            iter_production_kotlin_files(str(project_root), root_set)
        )
    except ProductionSourceScopeError as exc:
        print(
            "[verify_event_writers] production source enumeration failed: "
            f"{exc.code}",
            file=sys.stderr,
        )
        sys.exit(2)

    for declared_root in root_set.paths:
        scan_root = os.path.join(
            os.path.abspath(project_root), *declared_root.split("/")
        )
        print(f"[verify_event_writers] Scanning: {scan_root}")
    violations = scan(source_files, allowlist)
    print(f"[verify_event_writers] Allowlist: {len(allowlist)} file(s)")

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
