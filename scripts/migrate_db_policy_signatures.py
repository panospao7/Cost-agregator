#!/usr/bin/env python3
"""Discover and safely add callable signatures to the DB ownership policy."""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
from copy import deepcopy
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover - environment/configuration failure
    yaml = None

from db_policy_signature import FunctionSignature, SignatureError
from kotlin_callable_parser import (
    CallableDeclaration, ParserError, canonical_source_path,
    find_callable_declarations, find_owner_declarations, mask_kotlin_source,
    resolve_callable,
)

STATUS = frozenset({
    "RESOLVED_EXACTLY", "AMBIGUOUS_OVERLOAD", "METHOD_MISSING",
    "SIGNATURE_UNSUPPORTED", "PAIR_NOT_FOUND",
})
DEFAULT_POLICY = "config/guards/db_ownership_policy.yml"
_ENTRY_KEYS = {"path", "class", "method", "daos", "operation", "barrier_required",
               "barrier_via", "reason", "owner", "linked_issue", "signature"}
_CONTROLLED_STRING_RE = re.compile(r"[A-Za-z][A-Za-z0-9_.:-]{0,127}\Z")


class PolicyError(Exception):
    """Configuration error whose public representation contains no input data."""


def _error() -> PolicyError:
    return PolicyError("invalid DB policy configuration")


def _signature(entry: dict[str, Any], path: str) -> FunctionSignature | None:
    raw = entry.get("signature")
    if raw is None:
        return None
    if not isinstance(raw, dict) or set(raw) != {"parameters", "receiver"}:
        raise _error()
    params, receiver = raw["parameters"], raw["receiver"]
    if not isinstance(params, list) or not all(isinstance(x, str) for x in params):
        raise _error()
    if receiver is not None and not isinstance(receiver, str):
        raise _error()
    try:
        return FunctionSignature(path, entry["class"], entry["method"], receiver, tuple(params))
    except (SignatureError, KeyError, TypeError):
        raise _error()


def load_policy(path: str | Path) -> dict[str, Any]:
    """Load and structurally validate a policy without touching source files."""
    if yaml is None:
        raise _error()
    try:
        with Path(path).open("r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
    except Exception as exc:
        del exc
        raise _error()
    if not isinstance(data, dict) or set(data) != {"entries"} or not isinstance(data["entries"], list):
        raise _error()
    for entry in data["entries"]:
        if not isinstance(entry, dict) or not set(entry).issubset(_ENTRY_KEYS):
            raise _error()
        required = {"path", "class", "method", "daos", "operation", "barrier_required",
                    "reason", "owner", "linked_issue"}
        if not required.issubset(entry) or not all(isinstance(entry[k], str) for k in
                                                   ("path", "class", "method", "operation", "reason", "owner", "linked_issue")):
            raise _error()
        if "barrier_via" in entry and (
                not isinstance(entry["barrier_via"], str)
                or not _CONTROLLED_STRING_RE.fullmatch(entry["barrier_via"])):
            raise _error()
        if not isinstance(entry["daos"], list) or not entry["daos"] or not all(isinstance(x, str) for x in entry["daos"]):
            raise _error()
        if type(entry["barrier_required"]) is not bool or not entry["method"] or not entry["class"]:
            raise _error()
        try:
            canonical_source_path(entry["path"])
        except (ParserError, TypeError):
            raise _error()
    return data


def _owner_matches(declarations: tuple[Any, ...], owner: str) -> list[Any]:
    exact = [d for d in declarations if d.signature.owner_fqcn == owner]
    if exact:
        return exact
    short = [d for d in declarations if d.signature.owner_fqcn.rsplit(".", 1)[-1] == owner]
    owners = {d.signature.owner_fqcn for d in short}
    return short if len(owners) == 1 else []


def _contains_generic_declaration(text: str) -> bool:
    """Identify Kotlin declarations whose type parameters are not resolved.

    The callable parser intentionally has no type-parameter environment.  Keep
    that limitation fail-closed, but distinguish it from an ordinary missing
    project type so the CLI can emit the controlled discovery status.
    """
    return re.search(r"\b(?:class|interface|fun)\s*<", mask_kotlin_source(text)) is not None


def resolve_entry(entry: dict[str, Any], repo_root: str | Path) -> dict[str, Any]:
    """Resolve one policy entry; returned data is safe to serialize as a report."""
    path = canonical_source_path(entry["path"])
    source = Path(repo_root) / Path(*path.split("/"))
    try:
        text = source.read_text(encoding="utf-8")
        declarations = tuple(
            CallableDeclaration(
                FunctionSignature(path, d.signature.owner_fqcn, d.signature.function_name,
                                  d.signature.receiver, d.signature.parameter_types),
                d.owner, d.start_offset, d.end_offset, d.body, d.status
            )
            for owner in find_owner_declarations(text)
            for d in find_callable_declarations(text, owner)
        )
    except ParserError as exc:
        if exc.code in {"TYPE_UNRESOLVED", "BAD_TYPE", "BAD_OWNER", "BAD_NAME"}:
            if exc.code == "TYPE_UNRESOLVED" and _contains_generic_declaration(text):
                return {"status": "SIGNATURE_UNSUPPORTED", "identity": {"path": path, "class": entry["class"], "method": entry["method"]}}
            raise _error()
        return {"status": "SIGNATURE_UNSUPPORTED", "identity": {"path": path, "class": entry["class"], "method": entry["method"]}}
    except (OSError, UnicodeError):
        raise _error()
    matches = _owner_matches(declarations, entry["class"])
    named = [d for d in matches if d.signature.function_name == entry["method"]]
    supplied = _signature(entry, path)
    if supplied is None:
        if len(named) != 1:
            status = "AMBIGUOUS_OVERLOAD" if len(named) > 1 else "METHOD_MISSING"
            return {"status": status, "identity": {"path": path, "class": entry["class"], "method": entry["method"]}}
        chosen = named[0]
        if chosen.status != "RESOLVED_EXACTLY":
            return {"status": "SIGNATURE_UNSUPPORTED", "identity": {"path": path, "class": entry["class"], "method": entry["method"]}}
        supplied = chosen.signature
    elif matches:
        # Policy ``class`` is the declared class name; the callable parser's
        # identity additionally carries its package-qualified owner.
        supplied = FunctionSignature(path, matches[0].signature.owner_fqcn,
                                      supplied.function_name, supplied.receiver,
                                      supplied.parameter_types)
    status = resolve_callable(declarations, supplied.owner_fqcn, supplied.function_name,
                              supplied.receiver, supplied.parameter_types)
    if status not in STATUS:
        status = "SIGNATURE_UNSUPPORTED"
    chosen = [d for d in declarations if d.signature == supplied]
    if len(chosen) > 1:
        raise _error()
    if status == "RESOLVED_EXACTLY" and (not chosen or not any(_has_pair(d.body, entry) for d in chosen)):
        status = "PAIR_NOT_FOUND"
    result = {"status": status, "identity": {"canonical": supplied.canonical(), "path": path,
                                                 "class": entry["class"], "method": entry["method"]}}
    if status == "RESOLVED_EXACTLY" and "signature" not in entry:
        result["signature"] = {"parameters": list(supplied.parameter_types), "receiver": supplied.receiver}
    return result


def _has_pair(body: str | None, entry: dict[str, Any]) -> bool:
    if body is None:
        return False
    masked = mask_kotlin_source(body)
    operation = entry["operation"]
    return any(re.search(r"\b" + re.escape(dao) + r"\s*\.\s*" +
                         re.escape(operation) + r"\s*\(", masked) for dao in entry["daos"])


def migrate(policy: dict[str, Any], repo_root: str | Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    candidate = deepcopy(policy)
    report: list[dict[str, Any]] = []
    for index, entry in enumerate(candidate["entries"]):
        item = resolve_entry(entry, repo_root)
        item["location"] = {"entry": index + 1}
        report.append(item)
        if item["status"] == "RESOLVED_EXACTLY" and "signature" in item:
            entry["signature"] = item["signature"]
    return candidate, report


def write_atomic(path: str | Path, data: dict[str, Any]) -> None:
    target = Path(path)
    temporary = None
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        text = yaml.safe_dump(data, sort_keys=False, allow_unicode=False, default_flow_style=False)
        fd, temporary = tempfile.mkstemp(prefix=".db-policy-", suffix=".tmp", dir=str(target.parent), text=True)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
    except Exception:
        if temporary is not None:
            try:
                os.unlink(temporary)
            except OSError:
                pass
        raise _error()


def _report_policy_identifier(policy_path: Path, repo_root: Path) -> str:
    """Return a stable policy identifier without exposing an input path."""
    try:
        relative = policy_path.resolve().relative_to(repo_root.resolve())
    except ValueError:
        return "custom-policy"
    return relative.as_posix()


def _same_resolved_path(left: str | Path, right: str | Path) -> bool:
    try:
        return Path(left).resolve() == Path(right).resolve()
    except OSError:
        raise _error()


def _report(path: str | Path, policy_path: Path, repo_root: Path,
            rows: list[dict[str, Any]]) -> None:
    temporary = None
    try:
        payload = {"schema": "db-policy-signature-migration-v1",
                   "policy": _report_policy_identifier(policy_path, repo_root),
                   "statuses": sorted(STATUS), "entries": rows}
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        text = json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n"
        fd, temporary = tempfile.mkstemp(prefix=".db-policy-report-", suffix=".tmp",
                                         dir=str(target.parent), text=True)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
    except Exception:
        if temporary is not None:
            try:
                os.unlink(temporary)
            except OSError:
                pass
        raise _error()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="discover DB policy callable signatures")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write-candidate", action="store_true")
    parser.add_argument("--output")
    parser.add_argument("--policy", default=DEFAULT_POLICY)
    parser.add_argument("--report")
    args = parser.parse_args(argv)
    try:
        repo_root = Path(__file__).resolve().parents[1]
        policy_path = repo_root / DEFAULT_POLICY if args.policy == DEFAULT_POLICY else Path(args.policy)
        if args.report and _same_resolved_path(args.report, policy_path):
            raise _error()
        if args.write_candidate and (not args.output or _same_resolved_path(args.output, policy_path)):
            raise _error()
        policy = load_policy(policy_path)
        candidate, rows = migrate(policy, repo_root)
        if args.report:
            _report(args.report, policy_path, repo_root, rows)
        bad = any(row["status"] != "RESOLVED_EXACTLY" for row in rows)
        if args.write_candidate and not bad:
            write_atomic(args.output, candidate)
        return 1 if bad else 0
    except PolicyError:
        print("invalid DB policy configuration", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
