#!/usr/bin/env python3
"""Migrate the legacy v1 DB ownership policy into v2 candidates (PR-GR-02).

Thin CLI adapter over ``scripts.db_guard.policy_v2_candidate`` (steps 3, 4,
6, and 7 of PR-GR-02): every row-level migration decision is made by
``migrate_policy``; this adapter owns only flag handling, legacy-YAML
loading, deterministic report/candidate serialization, atomic writes, and
the exit-code table.

Privacy posture: reports and candidates carry identity fields, controlled
status constants, and counts only — never raw source text, absolute paths,
exception text, SQL, or user data.  Stderr diagnostics are fixed bounded
strings; stdout carries counts plus controlled status constants only.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover - environment/configuration failure
    yaml = None

# ``policy_v2_candidate`` uses in-package relative imports, so it must be
# imported as ``scripts.db_guard.policy_v2_candidate``.  Make the repository
# root (this file's parent directory's parent) importable first.  This uses
# the real on-disk location at import time; ``main`` re-derives the repo
# root from ``__file__`` at call time so embedders can redirect it.
_REPO_ROOT = Path(__file__).resolve().parents[1]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    find_duplicate_mutation_keys,
    migrate_policy,
)

DEFAULT_POLICY = "config/guards/db_ownership_policy.yml"

_REPORT_SCHEMA = "db-policy-migration-report"
_REPORT_VERSION = 2
_V2_SCHEMA_VERSION = 2

#: Bounded fallback identifier for policies outside the repository tree;
#: absolute paths never enter reports.
_CUSTOM_POLICY_LABEL = "custom-policy"

# Fixed, bounded stderr diagnostics — never exception text, never paths.
_MSG_INPUT = "malformed or unreadable DB policy input"
_MSG_INFRASTRUCTURE = "db policy migration infrastructure failure"
_MSG_OUTPUT_REQUIRED = "--write-candidate requires --output"
_MSG_PATH_COLLISION = "output/report paths collide with the active policy or each other"


class CliFailure(Exception):
    """CLI failure carrying only a fixed bounded public message."""


# ── Legacy input loading ──────────────────────────────────────────────────────


def _load_legacy_entries(policy_path: Path) -> list[Any]:
    """Load the legacy v1 policy YAML and return its raw entries list.

    Only the document shape is validated here (mapping with an ``entries``
    list); every per-entry problem is surfaced later as row-level debt by
    ``migrate_policy`` instead of being rejected up front.  Any read or
    parse failure fails closed with a bounded diagnostic.
    """
    try:
        with open(policy_path, "r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
    except (OSError, UnicodeDecodeError, yaml.YAMLError):
        raise CliFailure(_MSG_INPUT)
    if not isinstance(data, dict) or not isinstance(data.get("entries"), list):
        raise CliFailure(_MSG_INPUT)
    return data["entries"]


# ── Report/candidate serialization ────────────────────────────────────────────


def _policy_identifier(policy_path: Path, repo_root: Path) -> str:
    """Return the repo-relative posix policy path, or a bounded constant."""
    try:
        return policy_path.resolve().relative_to(repo_root.resolve()).as_posix()
    except (OSError, ValueError):
        return _CUSTOM_POLICY_LABEL


def _resolved_view(row: Any) -> dict[str, Any]:
    """One report row built exclusively from v2 :class:`PolicyEntry` fields."""
    entry = row.entry
    return {
        "index": row.index,
        "path": entry.path,
        "ownerFqcn": entry.owner_fqcn,
        "kind": entry.kind.value,
        "method": entry.method,
        "receiver": entry.receiver,
        "parameterTypes": list(entry.parameter_types),
        "daoAccessor": entry.dao_accessor,
        "daoFqcn": entry.dao_fqcn,
        "operation": entry.operation,
        "barrierMode": entry.barrier_mode.value,
    }


def _unresolved_view(row: Any) -> dict[str, Any]:
    """One debt row: controlled status constant plus bounded detail only."""
    return {
        "index": row.index,
        "legacyClass": row.legacy_class,
        "legacyMethod": row.legacy_method,
        "status": row.status,
        "detail": row.detail,
    }


def _build_report_payload(
    result: Any, duplicates: tuple[str, ...], policy_identifier: str
) -> dict[str, Any]:
    """Deterministic v2 report payload; identity fields and counts only."""
    resolved = [_resolved_view(row) for row in result.resolved]
    resolved.sort(
        key=lambda item: (
            item["index"],
            item["method"],
            item["daoAccessor"],
            item["operation"],
        )
    )
    unresolved = [_unresolved_view(row) for row in result.unresolved]
    unresolved.sort(key=lambda item: (item["index"], item["status"]))
    return {
        "schema": _REPORT_SCHEMA,
        "version": _REPORT_VERSION,
        "policy": policy_identifier,
        "counts": {
            "input": result.input_count,
            "resolved": len(resolved),
            "unresolved": len(unresolved),
        },
        "resolved": resolved,
        "unresolved": unresolved,
        "duplicateMutationKeys": list(duplicates),
    }


def _entry_document(entry: Any) -> dict[str, Any]:
    """One full v2 candidate entry (round-trips through the v2 loader)."""
    return {
        "path": entry.path,
        "ownerFqcn": entry.owner_fqcn,
        "kind": entry.kind.value,
        "method": entry.method,
        "receiver": entry.receiver,
        "parameterTypes": list(entry.parameter_types),
        "daoAccessor": entry.dao_accessor,
        "daoFqcn": entry.dao_fqcn,
        "operation": entry.operation,
        "barrierMode": entry.barrier_mode.value,
        "reason": entry.reason,
        "owner": entry.owner,
        "linkedIssue": entry.linked_issue,
    }


def _candidate_document(result: Any) -> dict[str, Any]:
    """Inert v2 candidate document with deterministically sorted entries."""
    entries = [_entry_document(row.entry) for row in result.resolved]
    entries.sort(
        key=lambda item: (
            item["path"],
            item["ownerFqcn"],
            item["method"],
            item["daoAccessor"],
            item["operation"],
        )
    )
    return {"schemaVersion": _V2_SCHEMA_VERSION, "entries": entries}


# ── Atomic writes ─────────────────────────────────────────────────────────────


def _atomic_write_text(target: Path, text: str, temporary_prefix: str) -> None:
    """Write ``text`` atomically: temp file in the target dir + os.replace."""
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(
        prefix=temporary_prefix, suffix=".tmp", dir=str(target.parent), text=True
    )
    try:
        # newline="\n" keeps line endings normalized on every platform.
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


# ── Path collision guard ──────────────────────────────────────────────────────


def _validate_output_paths(
    report: str | None, output: str | None, policy_path: Path
) -> None:
    """Reject artifact collisions before analysis or either write begins."""
    try:
        resolved_policy = policy_path.resolve()
        resolved_report = Path(report).resolve() if report else None
        resolved_output = Path(output).resolve() if output else None
    except (OSError, RuntimeError):
        raise CliFailure(_MSG_PATH_COLLISION)
    if resolved_report is not None and resolved_report == resolved_policy:
        raise CliFailure(_MSG_PATH_COLLISION)
    if resolved_output is not None and resolved_output == resolved_policy:
        raise CliFailure(_MSG_PATH_COLLISION)
    if (
        resolved_report is not None
        and resolved_output is not None
        and resolved_report == resolved_output
    ):
        raise CliFailure(_MSG_PATH_COLLISION)


# ── Exit-code table ───────────────────────────────────────────────────────────


def _decide_exit(result: Any, duplicates: tuple[str, ...]) -> tuple[int, bool]:
    """Map the analysis outcome to ``(exit_code, may_write_candidate)``.

    * duplicate mutation keys      -> 2, no candidate write;
    * zero resolved rows           -> 1, no candidate write;
    * unresolved debt, some solved -> 1, candidate write allowed;
    * every row resolved           -> 0, candidate write allowed.
    """
    if duplicates:
        return 2, False
    if not result.resolved:
        return 1, False
    return (0 if not result.unresolved else 1), True


def _print_summary(result: Any, duplicates: tuple[str, ...]) -> None:
    """Bounded stdout summary: counts plus controlled status constants."""
    status_counts: dict[str, int] = {}
    for row in result.unresolved:
        status_counts[row.status] = status_counts.get(row.status, 0) + 1
    print(
        "db-policy migration: input=%d resolved=%d unresolved=%d"
        " duplicateMutationKeys=%d"
        % (
            result.input_count,
            len(result.resolved),
            len(result.unresolved),
            len(duplicates),
        )
    )
    for status in sorted(status_counts):
        print("unresolved %s=%d" % (status, status_counts[status]))


# ── CLI entry point ───────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="migrate legacy DB ownership policy entries into v2 candidates"
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write-candidate", action="store_true")
    parser.add_argument("--output")
    parser.add_argument("--policy", default=DEFAULT_POLICY)
    parser.add_argument("--report")
    args = parser.parse_args(argv)
    try:
        if yaml is None:
            raise CliFailure(_MSG_INFRASTRUCTURE)
        repo_root = Path(__file__).resolve().parents[1]
        policy_path = (
            repo_root / DEFAULT_POLICY
            if args.policy == DEFAULT_POLICY
            else Path(args.policy)
        )
        if args.write_candidate and not args.output:
            raise CliFailure(_MSG_OUTPUT_REQUIRED)
        # In check mode the output path is ignored entirely.
        _validate_output_paths(
            args.report, args.output if args.write_candidate else None, policy_path
        )
        entries = _load_legacy_entries(policy_path)
        result = migrate_policy(entries, repo_root, dao_index=None)
        duplicates = find_duplicate_mutation_keys(result)
        payload = _build_report_payload(
            result, duplicates, _policy_identifier(policy_path, repo_root)
        )
        if args.report:
            _atomic_write_text(
                Path(args.report),
                json.dumps(payload, sort_keys=False, separators=(",", ":")) + "\n",
                ".db-policy-report-",
            )
        exit_code, may_write_candidate = _decide_exit(result, duplicates)
        if args.write_candidate and may_write_candidate:
            text = yaml.safe_dump(
                _candidate_document(result), sort_keys=False, allow_unicode=False
            ).replace("\r\n", "\n")
            _atomic_write_text(
                Path(args.output), text, ".db-policy-candidate-"
            )
        _print_summary(result, duplicates)
        return exit_code
    except CliFailure as failure:
        print(failure.message, file=sys.stderr)
        return 2
    except Exception:
        # Infrastructure failure: fail closed with a bounded diagnostic that
        # never echoes exception text, paths, SQL, or payloads.
        print(_MSG_INFRASTRUCTURE, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
