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

from db_policy_signature import FunctionSignature, SignatureError, normalize_type_text
from kotlin_callable_parser import (
    CallableDeclaration, ParserError, canonical_source_path,
    find_callable_declarations, find_owner_declarations, mask_kotlin_source,
    resolve_callable,
)

STATUS = frozenset({
    "RESOLVED_EXACTLY", "AMBIGUOUS_OVERLOAD", "METHOD_MISSING",
    "SIGNATURE_UNSUPPORTED", "PAIR_NOT_FOUND",
})
REASON_CODES = frozenset(f"MIGRATION_{status}" for status in STATUS)
EXPECTED_REPORT_TOTALS = {"input": 99, "resolved": 9, "unresolved": 90}
DEFAULT_POLICY = "config/guards/db_ownership_policy.yml"
_ENTRY_KEYS = {"path", "class", "method", "daos", "operation", "barrier_required",
               "barrier_via", "reason", "owner", "linked_issue", "signature"}
_CONTROLLED_STRING_RE = re.compile(r"[A-Za-z][A-Za-z0-9_.:-]{0,127}\Z")
_IDENTIFIER_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,127}\Z")
_INVALID_OPERATIONS = frozenset({"write", "unknown", "permanent"})
_REPORT_SCHEMA = "cost-aggregator.policy-signature-migration"
_REPORT_KEYS = {"schema", "schema_version", "policy", "counts", "resolved", "unresolved"}
_REPORT_COUNT_KEYS = {"input", "resolved", "unresolved"}
_UNRESOLVED_KEYS = {"status", "file", "class", "method", "dao", "operation",
                    "signature_evidence", "reason_code"}
_RESOLVED_KEYS = {"file", "class", "method", "dao", "operation", "signature"}


class PolicyError(Exception):
    """Configuration error whose public representation contains no input data."""


def _error() -> PolicyError:
    return PolicyError("invalid DB policy configuration")


def _validate_owner_fqcn(path: str, owner: str) -> None:
    """Validate a report/policy owner with FunctionSignature's owner rules."""
    try:
        # Use the public signature constructor so this validation cannot drift
        # from the owner-FQCN grammar used for callable identities.  The
        # placeholder is deliberately constant; method validation remains
        # owned by the exact simple-name checks at each call site.
        FunctionSignature(path, owner, "_report", None, ())
    except (SignatureError, TypeError):
        raise _error()


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
        # Policy identities are exact symbols.  In particular, do not accept
        # glob syntax that could authorize more than the named callable.
        if any(not value.strip() or any(mark in value for mark in ("*", "?"))
               for value in (entry["path"], entry["class"], entry["method"])):
            raise _error()
        if (entry["operation"] in _INVALID_OPERATIONS
                or not _IDENTIFIER_RE.fullmatch(entry["operation"])):
            raise _error()
        # Validate the container before iterating it.  Besides making malformed
        # YAML fail closed, this keeps null/scalar/mapping values from escaping
        # as an uncaught TypeError (or being echoed in a diagnostic).
        daos = entry["daos"]
        if not isinstance(daos, list) or not daos or not all(isinstance(value, str) for value in daos):
            raise _error()
        if any(not _IDENTIFIER_RE.fullmatch(value)
                or any(mark in value for mark in ("*", "?")) for value in daos):
            raise _error()
        if any(not isinstance(entry[key], str) or not entry[key].strip()
               or any(ord(char) < 32 or ord(char) == 127 for char in entry[key])
               for key in ("reason", "owner", "linked_issue")):
            raise _error()
        if "barrier_via" in entry and (
                not isinstance(entry["barrier_via"], str)
                or not _CONTROLLED_STRING_RE.fullmatch(entry["barrier_via"])):
            raise _error()
        if type(entry["barrier_required"]) is not bool or not entry["method"] or not entry["class"]:
            raise _error()
        try:
            canonical_source_path(entry["path"])
        except (ParserError, TypeError):
            raise _error()
        _validate_owner_fqcn(entry["path"], entry["class"])
        # Validate signature metadata while loading the policy.  Do not defer
        # malformed objects until discovery, where a configuration error could
        # otherwise be mistaken for a source-resolution result.
        try:
            _signature(entry, entry["path"])
        except (PolicyError, SignatureError, KeyError, TypeError):
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


def _unresolved_result(entry: dict[str, Any], path: str, status: str,
                       *, body: str | None = None,
                       supplied: FunctionSignature | None = None) -> dict[str, Any]:
    """Build the complete, non-sensitive shape used for unresolved rows."""
    if status not in STATUS:
        status = "SIGNATURE_UNSUPPORTED"
    matching_daos = []
    if body is not None:
        masked = mask_kotlin_source(body)
        matching_daos = [dao for dao in entry["daos"] if re.search(
            r"\b" + re.escape(dao) + r"\s*\.\s*" +
            re.escape(entry["operation"]) + r"\s*\(", masked)]
    dao = matching_daos[0] if len(matching_daos) == 1 else (
        entry["daos"][0] if len(entry["daos"]) == 1 else None)
    evidence: dict[str, Any] = {"status": "POLICY_PROVIDED" if supplied else status}
    if supplied is not None:
        evidence.update({"parameters": list(supplied.parameter_types),
                         "receiver": supplied.receiver})
    return {"status": status, "file": path, "class": entry["class"],
            "method": entry["method"], "dao": dao,
            "operation": entry["operation"], "signature_evidence": evidence,
            "reason_code": f"MIGRATION_{status}"}


def resolve_entry(entry: dict[str, Any], repo_root: str | Path) -> dict[str, Any]:
    """Resolve one policy entry; returned data is safe to serialize as a report."""
    path = canonical_source_path(entry["path"])
    policy_supplied = _signature(entry, path)
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
            if exc.code == "TYPE_UNRESOLVED" and "typealias" in mask_kotlin_source(text):
                raise _error()
            if exc.code == "TYPE_UNRESOLVED" and _contains_generic_declaration(text):
                return _unresolved_result(entry, path, "SIGNATURE_UNSUPPORTED",
                                          supplied=policy_supplied)
            if exc.code == "TYPE_UNRESOLVED":
                return _unresolved_result(entry, path, "SIGNATURE_UNSUPPORTED",
                                          supplied=policy_supplied)
            raise _error()
        return _unresolved_result(entry, path, "SIGNATURE_UNSUPPORTED",
                                  supplied=policy_supplied)
    except (OSError, UnicodeError):
        raise _error()
    matches = _owner_matches(declarations, entry["class"])
    named = [d for d in matches if d.signature.function_name == entry["method"]]
    supplied = policy_supplied
    if supplied is None:
        if len(named) != 1:
            status = "AMBIGUOUS_OVERLOAD" if len(named) > 1 else "METHOD_MISSING"
            return _unresolved_result(entry, path, status, supplied=supplied)
        chosen = named[0]
        if chosen.status != "RESOLVED_EXACTLY":
            return _unresolved_result(entry, path, "SIGNATURE_UNSUPPORTED", supplied=supplied)
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
    if status != "RESOLVED_EXACTLY":
        return _unresolved_result(entry, path, status,
                                  body=chosen[0].body if chosen else None,
                                  supplied=supplied)
    result = {"status": status, "identity": {"canonical": supplied.canonical(), "path": path,
                                                 "class": entry["class"], "method": entry["method"]},
              "dao": next((dao for dao in entry["daos"] if _has_pair(chosen[0].body, {**entry, "daos": [dao]})), None),
              "operation": entry["operation"],
              "signature": {"parameters": list(supplied.parameter_types), "receiver": supplied.receiver}}
    return result


def _has_pair(body: str | None, entry: dict[str, Any]) -> bool:
    if body is None:
        return False
    masked = mask_kotlin_source(body)
    operation = entry["operation"]
    return any(re.search(r"\b" + re.escape(dao) + r"\s*\.\s*" +
                         re.escape(operation) + r"\s*\(", masked) for dao in entry["daos"])


def migrate(policy: dict[str, Any], repo_root: str | Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    # A migration candidate is an authorization artifact, not a copy of the
    # input policy.  Only exact resolutions may enter it; unresolved debt must
    # remain visible in the report but can never be authorized accidentally.
    candidate_entries: list[dict[str, Any]] = []
    report: list[dict[str, Any]] = []
    for entry in policy["entries"]:
        item = resolve_entry(entry, repo_root)
        report.append(item)
        if item["status"] == "RESOLVED_EXACTLY" and "signature" in item:
            candidate_entry = deepcopy(entry)
            candidate_entry["signature"] = deepcopy(item["signature"])
            candidate_entries.append(candidate_entry)
    return {"entries": candidate_entries}, report


def write_atomic(path: str | Path, data: dict[str, Any]) -> None:
    target = Path(path)
    temporary = None
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        text = yaml.safe_dump(data, sort_keys=False, allow_unicode=True, default_flow_style=False)
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


def _validate_output_paths(report: str | Path | None, output: str | Path | None,
                           policy_path: Path) -> None:
    """Reject artifact collisions before discovery or either write begins."""
    try:
        resolved_policy = policy_path.resolve()
        resolved_report = Path(report).resolve() if report else None
        resolved_output = Path(output).resolve() if output else None
    except (OSError, RuntimeError):
        raise _error()
    if (resolved_report is not None and resolved_report == resolved_policy) or (
            resolved_output is not None and resolved_output == resolved_policy):
        raise _error()
    if resolved_report is not None and resolved_output is not None and resolved_report == resolved_output:
        raise _error()


def _report(path: str | Path, policy_path: Path, repo_root: Path,
            rows: list[dict[str, Any]], *, enforce_expected_totals: bool = False) -> None:
    temporary = None
    try:
        resolved = sum(row["status"] == "RESOLVED_EXACTLY" for row in rows)
        totals = {"input": len(rows), "resolved": resolved,
                  "unresolved": len(rows) - resolved}
        if enforce_expected_totals and totals != EXPECTED_REPORT_TOTALS:
            raise _error()
        resolved_rows = []
        unresolved_rows = []
        for row in rows:
            if row["status"] == "RESOLVED_EXACTLY":
                identity = row.get("identity", {})
                signature = row.get("signature")
                if (set(identity) != {"canonical", "path", "class", "method"}
                        or not isinstance(signature, dict)
                        or set(signature) != {"parameters", "receiver"}):
                    raise _error()
                resolved_rows.append({"file": identity["path"], "class": identity["class"],
                                      "method": identity["method"], "dao": row.get("dao"),
                                      "operation": row.get("operation"), "signature": signature})
            else:
                if set(row) != _UNRESOLVED_KEYS:
                    raise _error()
                unresolved_rows.append(row)
        key = lambda row: (row["file"], row["class"], row["method"],
                           row["dao"] or "", row["operation"], row.get("status", ""))
        resolved_rows.sort(key=key)
        unresolved_rows.sort(key=key)
        policy_identifier = _report_policy_identifier(policy_path, repo_root)
        payload = {"schema": _REPORT_SCHEMA, "schema_version": 1,
                   "policy": policy_identifier, "counts": totals,
                   "resolved": resolved_rows, "unresolved": unresolved_rows}
        _validate_report(payload, policy_identifier=policy_identifier,
                         enforce_expected_totals=enforce_expected_totals)
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        # Preserve the contract's leading schema keys; deterministic row order
        # is handled above and object insertion order is intentional here.
        text = json.dumps(payload, sort_keys=False, separators=(",", ":")) + "\n"
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


def _validate_report(payload: dict[str, Any], *, policy_identifier: str | None = None,
                     enforce_expected_totals: bool = False) -> None:
    """Validate the public report contract before it reaches disk."""
    if set(payload) != _REPORT_KEYS or payload["schema"] != _REPORT_SCHEMA or payload["schema_version"] != 1:
        raise _error()
    if (not isinstance(payload["policy"], str) or not payload["policy"].strip()
            or (policy_identifier is not None and payload["policy"] != policy_identifier)):
        raise _error()
    counts = payload["counts"]
    if set(counts) != _REPORT_COUNT_KEYS or any(type(counts[key]) is not int or counts[key] < 0 for key in counts):
        raise _error()
    resolved, unresolved = payload["resolved"], payload["unresolved"]
    if not isinstance(resolved, list) or not isinstance(unresolved, list):
        raise _error()
    if counts != {"input": len(resolved) + len(unresolved), "resolved": len(resolved),
                  "unresolved": len(unresolved)}:
        raise _error()
    if enforce_expected_totals and counts != EXPECTED_REPORT_TOTALS:
        raise _error()
    for row in resolved:
        if set(row) != _RESOLVED_KEYS or not isinstance(row["dao"], str) or not isinstance(row["operation"], str):
            raise _error()
        if (not _IDENTIFIER_RE.fullmatch(row["dao"]) or not _IDENTIFIER_RE.fullmatch(row["operation"])
                or row["operation"] in _INVALID_OPERATIONS):
            raise _error()
        if (not isinstance(row["file"], str) or not isinstance(row["class"], str)
                or not isinstance(row["method"], str)
                or not _IDENTIFIER_RE.fullmatch(row["method"])):
            raise _error()
        try:
            canonical_source_path(row["file"])
        except (ParserError, TypeError):
            raise _error()
        _validate_owner_fqcn(row["file"], row["class"])
        if set(row["signature"]) != {"parameters", "receiver"} or not isinstance(row["signature"]["parameters"], list):
            raise _error()
        if any(not isinstance(param, str) for param in row["signature"]["parameters"]):
            raise _error()
        if row["signature"]["receiver"] is not None and not isinstance(row["signature"]["receiver"], str):
            raise _error()
        try:
            for param in row["signature"]["parameters"]:
                normalize_type_text(param, allow_vararg=True)
            if row["signature"]["receiver"] is not None:
                normalize_type_text(row["signature"]["receiver"])
        except SignatureError:
            raise _error()
    for row in unresolved:
        if set(row) != _UNRESOLVED_KEYS or row["status"] not in STATUS or row["reason_code"] not in REASON_CODES:
            raise _error()
        if (not isinstance(row["file"], str) or not isinstance(row["class"], str)
                or not isinstance(row["method"], str)
                or not _IDENTIFIER_RE.fullmatch(row["method"])):
            raise _error()
        try:
            canonical_source_path(row["file"])
        except (ParserError, TypeError):
            raise _error()
        _validate_owner_fqcn(row["file"], row["class"])
        if row["dao"] is not None and (not isinstance(row["dao"], str) or not _IDENTIFIER_RE.fullmatch(row["dao"])):
            raise _error()
        if (not isinstance(row["operation"], str) or not _IDENTIFIER_RE.fullmatch(row["operation"])
                or row["operation"] in _INVALID_OPERATIONS):
            raise _error()
        evidence = row["signature_evidence"]
        if not isinstance(evidence, dict) or not set(evidence).issubset({"status", "parameters", "receiver"}):
            raise _error()
        if "status" in evidence and evidence["status"] not in STATUS | {"POLICY_PROVIDED"}:
            raise _error()
        if "parameters" in evidence and (not isinstance(evidence["parameters"], list)
                                          or not all(isinstance(param, str) for param in evidence["parameters"])):
            raise _error()
        if "receiver" in evidence and evidence["receiver"] is not None and not isinstance(evidence["receiver"], str):
            raise _error()
        try:
            for param in evidence.get("parameters", []):
                normalize_type_text(param, allow_vararg=True)
            if evidence.get("receiver") is not None:
                normalize_type_text(evidence["receiver"])
        except SignatureError:
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
        if args.write_candidate and not args.output:
            raise _error()
        _validate_output_paths(args.report, args.output if args.write_candidate else None, policy_path)
        policy = load_policy(policy_path)
        candidate, rows = migrate(policy, repo_root)
        if args.report:
            _report(args.report, policy_path, repo_root, rows,
                    enforce_expected_totals=(policy_path.resolve() ==
                                             (repo_root / DEFAULT_POLICY).resolve()))
        bad = any(row["status"] != "RESOLVED_EXACTLY" for row in rows)
        if args.write_candidate:
            write_atomic(args.output, candidate)
        return 1 if bad else 0
    except PolicyError:
        print("invalid DB policy configuration", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
