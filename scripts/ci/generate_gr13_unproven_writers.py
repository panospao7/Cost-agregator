"""Generate the GR-14 remediation seed from a GR-13 shadow report (plan Step 7).

Reads the shadow report produced by ``inspect_db_mediation_proof.py`` and
writes ``docs/ci/db-mediation/GR-13_UNPROVEN_WRITERS.yml``: exactly one
disposition per non-proven policy row, each carrying its proof status,
root/call-edge reason, bounded path evidence, a closed risk classification,
and a recommended GR-14 remediation family.

Deterministic by construction: the output depends only on the report bytes
(whose SHA-256 is pinned in the header); no timestamps, no environment
values.  Re-running against the same report is byte-identical.

Shadow-only: the seed is a review ledger for GR-14 planning.  No expiry,
baseline, or accepted-debt state may be recorded here (plan hard rule).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from scripts.db_guard.mediation_analysis.models import ProofState  # noqa: E402

__all__ = ["build_unproven_writers", "main"]

DEFAULT_OUTPUT = os.path.join(
    "docs", "ci", "db-mediation", "GR-13_UNPROVEN_WRITERS.yml"
)

# Closed risk classification per proof state (plan: risk classification is
# part of every remediation row; no open vocabulary).
_RISK_BY_STATE = {
    "proven_helper": "PROVEN",
    "proven_worker_mediated": "PROVEN",
    "counterexample_unguarded_call_path": "DEFINITE_VIOLATION",
    "counterexample_non_worker_root": "DEFINITE_VIOLATION",
    "counterexample_outside_worker_scope": "DEFINITE_VIOLATION",
    "unproven_external_entry": "UNPROVEN_PATH",
    "unproven_ambiguous_call": "UNPROVEN_PATH",
    "unproven_async_or_escaping_callback": "UNPROVEN_PATH",
    "unproven_recursion": "UNPROVEN_PATH",
    "unsupported_source": "MISSING_EVIDENCE",
    "infrastructure_failure": "ANALYSIS_BLOCKED",
}

# Closed GR-14 remediation family per (proof state, reason code, local
# guard, convertibility).  First match wins; the default row order keeps
# the specific reasons ahead of the state-level fallbacks.
_REMEDIATION_RULES = (
    ("GR13_NO_D4_OBSERVATION", "GR14_POLICY_ROW_DEDUPLICATION"),
    ("GR13_CALLABLE_BODY_UNMODELABLE", "GR14_SPLIT_UNMODELABLE_BODY"),
    ("GR13_LOCAL_GUARD_UNMODELABLE", "GR14_SPLIT_UNMODELABLE_BODY"),
    ("GR13_WORKER_ROOT_NOT_REGISTERED", "GR14_REGISTER_WORKER_ROOT"),
    ("GR13_RECURSIVE_CALL_REGION", "GR14_BREAK_RECURSION_REGION"),
    ("GR13_SITE_INSIDE_UNRESOLVED_LAMBDA", "GR14_CLOSE_LAMBDA_ESCAPE"),
    ("GR13_UNCERTAIN_CALL_EDGE", "GR14_RESOLVE_DISPATCH"),
    ("GR13_ZERO_INBOUND_CALL_SITES", "GR14_CLOSE_OR_DOCUMENT_ENTRY"),
    ("GR13_AMBIGUOUS_CALLABLE_IDENTITY", "GR14_RESOLVE_DISPATCH"),
    ("GR13_NO_EXACT_PRODUCTION_PATH", "GR14_CLOSE_OR_DOCUMENT_ENTRY"),
)


def _remediation_family(row: dict) -> str:
    reason = row.get("reasonCode", "")
    deciding = row.get("decidingResolution") or ""
    for code, family in _REMEDIATION_RULES:
        if reason == code:
            if (
                code == "GR13_UNCERTAIN_CALL_EDGE"
                and deciding in ("async_dispatch", "escaping_lambda")
            ):
                return "GR14_SYNCHRONIZE_OR_GUARD_ASYNC_PATH"
            return family
    state = row["proofStatus"]
    if state.startswith("counterexample"):
        if row.get("convertibleToDirect"):
            return "GR14_CONVERT_TO_DIRECT_BARRIER"
        return "GR14_GUARD_UNGUARDED_PATH"
    if state == "unproven_external_entry":
        return "GR14_GUARD_EXTERNAL_ENTRY_PATH"
    if state == "unproven_async_or_escaping_callback":
        return "GR14_SYNCHRONIZE_OR_GUARD_ASYNC_PATH"
    if state.startswith("proven"):
        raise ValueError("proven row %s needs no remediation" % row["mutationKey"])
    return "GR14_TRIAGE_UNCLASSIFIED"


def build_unproven_writers(report: dict, report_sha256: str) -> dict:
    """Build the seed document for every non-proven row of ``report``."""
    rows = []
    for entry in report["entries"]:
        state = entry["proofStatus"]
        if state not in _RISK_BY_STATE:
            raise ValueError(
                "unknown proof state %r (closed vocabulary violation)" % state
            )
        if state.startswith("proven"):
            continue
        rows.append(
            {
                "mutationKey": entry["mutationKey"],
                "callableKey": entry["callableKey"],
                "barrierMode": entry["barrierMode"],
                "proofStatus": state,
                "reasonCode": entry.get("reasonCode", ""),
                "decidingResolution": entry.get("decidingResolution"),
                "reachingRootKinds": list(entry.get("reachingRootKinds", ())),
                "boundedPathEvidence": list(entry.get("boundedPath", ())),
                "riskClassification": _RISK_BY_STATE[state],
                "recommendedRemediationFamily": _remediation_family(entry),
                "owner": "@panospao7",
                "linkedIssue": "GR-14",
            }
        )
    rows.sort(key=lambda row: row["mutationKey"])
    summary = report.get("summary", {})
    return {
        "schemaVersion": 1,
        "reportOnly": True,
        "generatedFrom": {
            "engine": report.get("engine", "gr13-mediation-proof"),
            "reportSha256": report_sha256,
            "targetSha": report.get("targetSha"),
            "activePolicySha256": report.get("activePolicySha256"),
            "sourceRootManifestSha256": report.get("sourceRootManifestSha256"),
        },
        "status": "OPEN",
        "summary": {
            "policyRowCount": summary.get("helperWorkerEntryCount"),
            "provenRowCount": sum(
                1
                for entry in report["entries"]
                if entry["proofStatus"].startswith("proven")
            ),
            "remediationRowCount": len(rows),
            "riskCounts": _counts(rows, "riskClassification"),
            "familyCounts": _counts(rows, "recommendedRemediationFamily"),
        },
        "remediationNote": (
            "One disposition per non-proven helper/workerMediated policy "
            "row.  No expiry, baseline, or accepted-debt state is allowed "
            "(GR-13 plan hard rule); every row must close through its GR-14 "
            "remediation family."
        ),
        "rows": rows,
    }


def _counts(rows, key):
    counts = {}
    for row in rows:
        value = row[key]
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def _write_yaml(path: str, document: dict) -> None:
    import yaml

    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8", newline="\n") as handle:
        yaml.safe_dump(
            document,
            handle,
            default_flow_style=False,
            sort_keys=False,
            allow_unicode=True,
            width=100000,
        )
    os.replace(temporary, path)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Generate GR-13_UNPROVEN_WRITERS.yml from a GR-13 shadow report."
        )
    )
    parser.add_argument("--report", required=True, help="shadow report JSON")
    parser.add_argument("--output", default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)

    with open(args.report, "rb") as handle:
        report_bytes = handle.read()
    report_sha256 = hashlib.sha256(report_bytes).hexdigest()
    report = json.loads(report_bytes.decode("utf-8"))
    document = build_unproven_writers(report, report_sha256)
    _write_yaml(args.output, document)
    print(
        "GR-13_UNPROVEN_WRITERS: %d remediation rows from %d policy rows -> %s"
        % (
            document["summary"]["remediationRowCount"],
            document["summary"]["policyRowCount"],
            args.output,
        )
    )
    print("report sha256:", report_sha256)
    return 0


if __name__ == "__main__":
    sys.exit(main())
