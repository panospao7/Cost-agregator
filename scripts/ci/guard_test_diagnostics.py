"""Bounded, read-only assertion context; never changes a guard's verdict.

Production CLI diagnostics intentionally hide internal policy evidence. Tests
can additionally supply an observed evidence result from a pass-through spy,
without rescanning, bypassing a stage, or changing the public CLI protocol.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from scripts.ci.finding_rule_catalog import is_known_diagnostic, is_known_rule

MAX_REPORT_BYTES = 1024 * 1024
MAX_ITEMS = 8
# Opt-in real-tree detail; compact callers retain the original eight-row bound.
MAX_EVIDENCE_GROUPS = 64
MAX_DISCOVERY_DIAGNOSTICS = 64


def _codes(items, field, predicate):
    return [
        value if isinstance(value, str) and len(value) <= 96 and predicate(value)
        else "UNKNOWN_CODE"
        for item in items[:MAX_ITEMS]
        for value in [item.get(field) if isinstance(item, dict) else None]
    ]


def _diagnostic_identity(item):
    """Copy only a controlled code, relative Kotlin path and bounded line."""
    identity = {"code": _codes([item], "code", is_known_diagnostic)[0]}
    path = item.get("path") if isinstance(item, dict) else None
    if (not isinstance(path, str) or len(path) > 384
            or len(path.split("/")) < 2 or not path.endswith(".kt")
            or any(segment in {".", ".."} or not re.fullmatch(
                r"[A-Za-z0-9_][A-Za-z0-9_.-]*", segment
            ) for segment in path.split("/"))):
        identity["identity"] = "INVALID_SOURCE_IDENTITY"
        return identity
    identity["path"] = path
    context = item.get("controlled_context")
    line = context.get("line") if isinstance(context, dict) else None
    if type(line) is int and 1 <= line <= 2 ** 31 - 1:
        identity["line"] = line
    return identity


def _discovery_summary(diagnostics, *, expanded=False):
    # Match the existing production trust predicate exactly. Missing, malformed
    # or merely truthy advisory markers must never hide a blocking diagnostic.
    blocking = []
    for item in diagnostics:
        context = item.get("controlled_context") if isinstance(item, dict) else None
        if not (isinstance(context, dict) and context.get("advisory") is True):
            blocking.append(item)
    limit = MAX_DISCOVERY_DIAGNOSTICS if expanded else MAX_ITEMS
    return {
        "blockingDiagnosticCount": len(blocking),
        "advisoryDiagnosticCount": len(diagnostics) - len(blocking),
        "omittedDiagnosticCodeCount": max(0, len(diagnostics) - MAX_ITEMS),
        "omittedBlockingDiagnosticCount": max(0, len(blocking) - limit),
        "blockingDiagnostics": [_diagnostic_identity(item) for item in blocking[:limit]],
    }


def _callable_identity(key):
    if not isinstance(key, str) or len(key) > 4096:
        return {"identity": "INVALID_CALLABLE_IDENTITY"}
    parts = key.split("|")
    if len(parts) != 6:
        return {"identity": "INVALID_CALLABLE_IDENTITY"}
    path, owner, kind, method, _receiver, _parameters = parts
    segments = path.split("/")
    if (
        len(path) > 384 or len(segments) < 2 or not path.endswith(".kt")
        or any(segment in {".", ".."} or not re.fullmatch(
            r"[A-Za-z0-9_][A-Za-z0-9_.-]*", segment
        ) for segment in segments)
        or len(owner) > 256 or not re.fullmatch(
            r"[A-Za-z_][A-Za-z0-9_]*(?:[.][A-Za-z_][A-Za-z0-9_]*)+", owner
        )
        or kind not in {"function", "constructor"}
        or len(method) > 96 or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", method)
    ):
        return {"identity": "INVALID_CALLABLE_IDENTITY"}
    # No raw signature, source, exception context, or absolute filesystem path.
    return {"path": path, "owner": owner, "method": method}


def _mutation_identity(key):
    if not isinstance(key, str) or len(key) > 193:
        return {"identity": "INVALID_MUTATION_IDENTITY"}
    parts = key.split("|")
    if len(parts) != 2 or any(
        len(part) > 96 or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", part)
        for part in parts
    ):
        return {"identity": "INVALID_MUTATION_IDENTITY"}
    return {"daoAccessor": parts[0], "operation": parts[1]}


def _evidence_summary(evidence, *, expanded=False):
    if not isinstance(evidence, dict):
        return {"status": "INVALID_EVIDENCE"}
    groups = evidence.get("groups")
    diagnostics = evidence.get("diagnostics")
    if not isinstance(groups, list) or not isinstance(diagnostics, list):
        return {"status": "INVALID_EVIDENCE"}
    failed = [group for group in groups
              if not isinstance(group, dict) or group.get("trusted") is not True]
    group_limit = MAX_EVIDENCE_GROUPS if expanded else MAX_ITEMS
    selected = []
    for group in failed[:group_limit]:
        if not isinstance(group, dict):
            selected.append({"identity": "INVALID_GROUP"})
            continue
        item = _callable_identity(group.get("callable_key"))
        group_diagnostics = group.get("diagnostics")
        item["codes"] = _codes(
            group_diagnostics if isinstance(group_diagnostics, list) else [None],
            "code", is_known_diagnostic,
        )
        # The evidence gate emits at most one failed-stage diagnostic per
        # group. Identify a stale mutation without copying arbitrary context.
        if isinstance(group_diagnostics, list) and group_diagnostics:
            diagnostic = group_diagnostics[0]
            context = diagnostic.get("context") if isinstance(diagnostic, dict) else None
            if isinstance(context, dict):
                mutation = {}
                for source_key, output_key in (
                    ("dao_accessor", "daoAccessor"), ("operation", "operation"),
                ):
                    value = context.get(source_key)
                    if (isinstance(value, str) and len(value) <= 96
                            and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value)):
                        mutation[output_key] = value
                if mutation:
                    item["mutation"] = mutation
        if expanded:
            mutations = group.get("mutation_keys")
            if isinstance(mutations, list):
                item["actualMutationCount"] = len(mutations)
                item["omittedMutationCount"] = max(0, len(mutations) - MAX_ITEMS)
                item["actualMutations"] = [
                    _mutation_identity(key) for key in mutations[:MAX_ITEMS]
                ]
            else:
                item["mutationStatus"] = "UNAVAILABLE"
        selected.append(item)
    return {
        "failedGroupCount": len(failed),
        "omittedGroupCount": max(0, len(failed) - group_limit),
        "diagnosticCodes": _codes(diagnostics, "code", is_known_diagnostic),
        "failedGroups": selected,
    }


def db_guard_failure_summary(
    exit_code, report_path, *, evidence_reports=(), expanded_evidence=False,
):
    """Describe a failure without echoing process args, source, or raw streams.

    The real-tree assertion can opt into at most 64 failed groups, each with
    at most eight sanitized actual mutation identities, and 64 blocking
    discovery diagnostics. Compact callers keep eight-row bounds. Advisory
    diagnostics cannot crowd blocking detail out of the preview; all omission
    counts stay visible. This formatter never changes the recorded verdict.
    """
    summary = {
        "exitCode": exit_code if type(exit_code) is int and -65535 <= exit_code <= 65535 else None,
        "reportStatus": "UNAVAILABLE",
    }
    try:
        with Path(report_path).open("rb") as stream:
            payload = stream.read(MAX_REPORT_BYTES + 1)
        if len(payload) > MAX_REPORT_BYTES:
            summary["reportStatus"] = "TOO_LARGE"
        else:
            data = json.loads(payload.decode("utf-8"))
            if (not isinstance(data, dict)
                    or not isinstance(data.get("findings"), list)
                    or not isinstance(data.get("diagnostics"), list)
                    or not isinstance(data.get("statistics"), dict)):
                summary["reportStatus"] = "INVALID_SHAPE"
            else:
                trusted = data["statistics"].get("trusted")
                summary.update({
                    "reportStatus": "READ",
                    "trusted": trusted if type(trusted) is bool else None,
                    "findingCount": len(data["findings"]),
                    "diagnosticCount": len(data["diagnostics"]),
                    "findingCodes": _codes(data["findings"], "rule", is_known_rule),
                    "diagnosticCodes": _codes(data["diagnostics"], "code", is_known_diagnostic),
                })
                summary.update(_discovery_summary(
                    data["diagnostics"], expanded=expanded_evidence is True,
                ))
    except (OSError, TypeError):
        summary["reportStatus"] = "UNAVAILABLE"
    except (ValueError, UnicodeError, RecursionError):
        summary["reportStatus"] = "INVALID_JSON"
    summary["evidenceReportCount"] = len(evidence_reports)
    # One evidence invocation is expected; retain its bounded group detail.
    summary["evidence"] = [
        _evidence_summary(item, expanded=expanded_evidence is True)
        for item in evidence_reports[:1]
    ]
    return json.dumps(summary, sort_keys=True, separators=(",", ":"))
