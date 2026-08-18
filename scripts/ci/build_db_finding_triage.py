#!/usr/bin/env python3
"""
build_db_finding_triage.py -- PR-D5 triage builder for DB guard findings.

Consumes a protocol-v2 DB report and the old v1 baseline, crosswalks old
entries to v2 by semantic rule/path, and builds PENDING triage entries for
every current v2 finding.

Exit codes:
  0 -- triage YAML written successfully
  2 -- infrastructure/schema/malformed-input failure (fail closed)

Crosswalk outcomes per old v1 entry:
  ONE_TO_ONE          -- exactly one v2 finding matches rule family + path
  ONE_TO_MANY         -- multiple v2 findings match rule family + path
  NO_CURRENT_MATCH    -- no v2 finding matches rule family + path
  UNRESOLVED_RULE_MAPPING -- old rule family cannot be mapped to a v2 rule

Rules:
  - Every current v2 finding becomes a PENDING triage entry.
  - Historical presence, ownership, reason, issue, expiry, and classification
    are NEVER inferred -- all remain null/unknown/empty.
  - Duplicate exact source occurrences are diagnosed, not silently collapsed.
  - Malformed inputs fail closed (exit 2).
  - Deterministic ordering and sanitized output.

Usage:
  python scripts/ci/build_db_finding_triage.py \
    --v2-report build/guard-v2/db-final-inventory.json \
    --v1-baseline config/baselines/db_access.json \
    --output build/guard-v2/DB_ACCESS_V2_TRIAGE.yml
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guard_findings import (  # noqa: E402
    DuplicateFindingError,
    GuardRunReport,
    load_report_json,
)
from finding_rule_catalog import RULE_PROFILES  # noqa: E402

# ------------------------------------------------------------------
# Constants
# ------------------------------------------------------------------

# Allowed classifications exactly as specified in PR-D5.
ALLOWED_CLASSIFICATIONS = frozenset({
    "LEGAL_WRITER_POLICY_MISSING",
    "REAL_ARCHITECTURE_VIOLATION",
    "PARSER_FALSE_POSITIVE",
    "PREEXISTING_TEMPORARY_DEBT",
    "STRUCTURAL_OPERATION",
    "ANALYZER_UNSUPPORTED",
    "DUPLICATE_DETECTION",
    "PENDING",
})

# Crosswalk outcome values.
CROSSWALK_OUTCOMES = frozenset({
    "ONE_TO_ONE",
    "ONE_TO_MANY",
    "NO_CURRENT_MATCH",
    "UNRESOLVED_RULE_MAPPING",
})

# Map v1 rule families to v2 rule codes.  The v1 baseline uses coarse
# rule IDs like UNALLOWLISTED_CLASS, FORBIDDEN_FILE_OP, etc.  We map
# them by semantic family to the v2 policy rules.
_V1_TO_V2_RULE_MAP: Dict[str, Tuple[str, ...]] = {
    "UNALLOWLISTED_CLASS": ("DB_UNAUTHORIZED_MUTATION", "DB_MISSING_WRITE_BARRIER"),
    "UNALLOWLISTED_CLASS_DIRECT_CHAIN": ("DB_UNAUTHORIZED_MUTATION", "DB_MISSING_WRITE_BARRIER"),
    "FORBIDDEN_FILE_OP": ("DB_FORBIDDEN_STRUCTURAL_OPERATION",),
    "UNSUPPORTED_EXPRESSION_BODY": ("DB_UNAUTHORIZED_MUTATION", "DB_MISSING_WRITE_BARRIER"),
    "UNSUPPORTED_DAO_SCOPE": ("DB_UNAUTHORIZED_MUTATION", "DB_MISSING_WRITE_BARRIER"),
    "UNSUPPORTED_METHOD_BODY": ("DB_UNAUTHORIZED_MUTATION", "DB_MISSING_WRITE_BARRIER"),
}

# Pattern to extract rule and path from a v1 fingerprint string.
# v1 formats:
#   "UNALLOWLISTED_CLASS app/src/.../File.kt"
#   "UNALLOWLISTED_CLASS_DIRECT_CHAIN app/src/.../File.kt"
#   "FORBIDDEN_FILE_OP: ... app/src/.../File.kt"
_V1_FP_RE = re.compile(
    r'^([A-Z][A-Z0-9_]+)(?::?\s+)(.+\.kt|.+\.java)\s*$'
)


# ------------------------------------------------------------------
# V1 baseline parsing
# ------------------------------------------------------------------


def load_v1_baseline(path: Path) -> Dict[str, Any]:
    """Load and validate a v1 baseline JSON file.

    Returns the parsed dict.  Exits 2 on any validation failure.
    """
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        print(f"ERROR: v1 baseline not found: {path}", file=sys.stderr)
        sys.exit(2)
    except json.JSONDecodeError:
        print(f"ERROR: Malformed v1 baseline JSON: {path}", file=sys.stderr)
        sys.exit(2)
    except Exception as exc:
        print(
            f"ERROR: Failed to read v1 baseline: {exc.__class__.__name__}",
            file=sys.stderr,
        )
        sys.exit(2)

    if not isinstance(data, dict):
        print("ERROR: v1 baseline must be a JSON object", file=sys.stderr)
        sys.exit(2)

    fingerprints = data.get("fingerprints")
    if not isinstance(fingerprints, list):
        print("ERROR: v1 baseline 'fingerprints' must be a list", file=sys.stderr)
        sys.exit(2)

    for fp in fingerprints:
        if not isinstance(fp, str) or not fp.strip():
            print(
                "ERROR: v1 baseline 'fingerprints' entries must be non-empty strings",
                file=sys.stderr,
            )
            sys.exit(2)

    return data


def parse_v1_fingerprint(fp: str) -> Optional[Tuple[str, str]]:
    """Parse a v1 fingerprint string into (rule_family, path).

    Returns None if the fingerprint cannot be parsed.
    """
    match = _V1_FP_RE.match(fp.strip())
    if match:
        rule_family = match.group(1)
        path = match.group(2).strip()
        return (rule_family, path)
    return None


# ------------------------------------------------------------------
# Crosswalk logic
# ------------------------------------------------------------------


def crosswalk_entry(
    v1_rule_family: str,
    v1_path: str,
    v2_findings_by_rule_path: Dict[Tuple[str, str], List[Dict[str, Any]]],
) -> Tuple[str, List[Dict[str, Any]]]:
    """Crosswalk one v1 entry to v2 findings.

    Returns (outcome, matched_v2_findings).
    """
    mapped_rules = _V1_TO_V2_RULE_MAP.get(v1_rule_family)
    if mapped_rules is None:
        return ("UNRESOLVED_RULE_MAPPING", [])

    # Normalize paths for comparison: strip whitespace, normalize separators.
    norm_v1_path = v1_path.replace("\\", "/").strip()

    matched = []
    for v2_rule in mapped_rules:
        # Match by rule + exact canonical path, or controlled one-direction
        # v2-path-under-v1 mapping (v1 was a coarser directory-level path,
        # so v2 must start with v1 + "/").  Bidirectional suffix matching
        # is intentionally NOT used: it would silently match unrelated files
        # sharing a suffix (e.g. "Foo.kt" matching "Bar/Foo.kt").
        for (rule, path), findings in v2_findings_by_rule_path.items():
            if rule != v2_rule:
                continue
            norm_v2_path = path.replace("\\", "/").strip()
            if norm_v2_path == norm_v1_path:
                # Exact canonical path match.
                matched.extend(findings)
            elif norm_v2_path.startswith(norm_v1_path + "/"):
                # Controlled one-direction mapping: v1 path is a coarser
                # directory prefix; v2 is a specific file under that dir.
                matched.extend(findings)

    if len(matched) == 0:
        return ("NO_CURRENT_MATCH", [])
    elif len(matched) == 1:
        return ("ONE_TO_ONE", matched)
    else:
        return ("ONE_TO_MANY", matched)


def build_crosswalk(
    v1_entries: List[str],
    v2_findings_by_rule_path: Dict[Tuple[str, str], List[Dict[str, Any]]],
) -> List[Dict[str, Any]]:
    """Build the crosswalk report for all v1 entries.

    Returns a list of crosswalk result dicts.
    """
    results = []
    for fp in v1_entries:
        parsed = parse_v1_fingerprint(fp)
        if parsed is None:
            results.append({
                "v1_fingerprint": fp,
                "outcome": "UNRESOLVED_RULE_MAPPING",
                "matched_count": 0,
                "note": "v1 fingerprint could not be parsed",
            })
            continue

        v1_rule_family, v1_path = parsed
        outcome, matched = crosswalk_entry(
            v1_rule_family, v1_path, v2_findings_by_rule_path
        )
        results.append({
            "v1_fingerprint": fp,
            "v1_rule_family": v1_rule_family,
            "v1_path": v1_path,
            "outcome": outcome,
            "matched_count": len(matched),
            "matched_v2_fingerprints": [m["fingerprint"] for m in matched],
        })

    return results


# ------------------------------------------------------------------
# Triage entry construction
# ------------------------------------------------------------------


def build_triage_entries(
    report: GuardRunReport,
) -> List[Dict[str, Any]]:
    """Build PENDING triage entries for every finding in the report.

    Every entry is PENDING.  Historical presence, ownership, reason,
    issue, expiry, and classification are never inferred.

    Duplicate exact source occurrences are diagnosed: if two findings
    share the same fingerprint but differ in location, they each get
    their own entry (count tracks multiplicity).  If they are exact
    duplicates (same rule, path, location, symbol, identity), the
    report validation would have already rejected them.
    """
    from guard_findings import aggregate_findings

    aggregates = aggregate_findings(report.findings)

    entries = []
    for agg in aggregates:
        finding = next(
            f for f in report.findings if f.fingerprint == agg.fingerprint
        )
        entry = {
            "fingerprint": agg.fingerprint,
            "classification": "PENDING",
            "path": finding.path,
            "symbol": {
                "owner": finding.symbol.owner,
                "name": finding.symbol.name,
                "receiver": finding.symbol.receiver,
                "parameters": list(finding.symbol.parameters),
                "kind": finding.symbol.kind,
            },
            "dao": finding.identity.get("dao") if "dao" in finding.identity else None,
            "operation": finding.identity.get("operation") if "operation" in finding.identity else None,
            "present_at_reference_sha": "unknown",
            "owner": None,
            "linked_issue": None,
            "reason": None,
            "expires": None,
            "evidence": [],
        }
        entries.append(entry)

    # Deterministic ordering: sort by fingerprint (already deterministic
    # from the aggregation, but we enforce it explicitly).
    entries.sort(key=lambda e: e["fingerprint"])

    return entries


# ------------------------------------------------------------------
# Duplicate diagnostics
# ------------------------------------------------------------------


def diagnose_duplicates(
    findings: Sequence[Any],
) -> List[Dict[str, Any]]:
    """Diagnose exact duplicate source occurrences (test-only helper).

    **Test-only:** In production, exact duplicates are rejected by
    ``aggregate_findings`` (``DuplicateFindingError``) before this function
    can run.  This helper is retained solely for unit-test assertions that
    duplicate detection logic works correctly on pre-built finding lists.

    Returns a list of diagnostic entries for any duplicate
    (rule, path, location, symbol, identity) tuples.
    """
    seen: Dict[Tuple, int] = {}
    for finding in findings:
        key = (
            finding.rule,
            finding.path,
            finding.location.line,
            finding.location.column,
            finding.symbol.owner,
            finding.symbol.name,
            finding.symbol.receiver,
            tuple(finding.symbol.parameters),
            finding.symbol.kind,
            tuple(sorted(finding.identity.items())),
        )
        seen[key] = seen.get(key, 0) + 1

    duplicates = []
    for key, count in seen.items():
        if count > 1:
            duplicates.append({
                "rule": key[0],
                "path": key[1],
                "line": key[2],
                "count": count,
                "diagnosis": "DUPLICATE_DETECTION",
            })

    return duplicates


# ------------------------------------------------------------------
# YAML output (minimal, stdlib-only)
# ------------------------------------------------------------------


def _yaml_escape(value: str) -> str:
    """Escape a string for YAML output."""
    if value is None:
        return "null"
    # Check if quoting is needed
    if (
        value == ""
        or value in ("null", "true", "false", "yes", "no", "~")
        or value[0] in "0123456789"
        or any(c in value for c in ":{}[]&*?|->!%@`,#")
        or value.startswith((" ", "- ", "? ", ": "))
        or value.endswith(" ")
    ):
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'
    return value


def write_triage_yaml(
    path: Path,
    entries: List[Dict[str, Any]],
    crosswalk: List[Dict[str, Any]],
    duplicates: List[Dict[str, Any]],
    metadata: Dict[str, Any],
) -> None:
    """Write the triage YAML file atomically.

    Uses sibling-temp + fsync + os.replace for atomicity.
    """
    lines = []
    lines.append("# DB Access Finding Triage -- PR-D5")
    lines.append("# Generated by build_db_finding_triage.py")
    lines.append(f"# Classification status: ALL PENDING -- requires manual review")
    lines.append("")
    lines.append("metadata:")
    for key in sorted(metadata):
        val = metadata[key]
        if isinstance(val, int):
            lines.append(f"  {key}: {val}")
        elif isinstance(val, str):
            lines.append(f"  {key}: {_yaml_escape(val)}")
        elif val is None:
            lines.append(f"  {key}: null")
        else:
            lines.append(f"  {key}: {_yaml_escape(str(val))}")
    lines.append("")

    if crosswalk:
        lines.append("crosswalk:")
        for cw in crosswalk:
            lines.append(f"  - v1_fingerprint: {_yaml_escape(cw['v1_fingerprint'])}")
            if "v1_rule_family" in cw:
                lines.append(f"    v1_rule_family: {_yaml_escape(cw['v1_rule_family'])}")
            if "v1_path" in cw:
                lines.append(f"    v1_path: {_yaml_escape(cw['v1_path'])}")
            lines.append(f"    outcome: {cw['outcome']}")
            lines.append(f"    matched_count: {cw['matched_count']}")
            if cw.get("matched_v2_fingerprints"):
                lines.append("    matched_v2_fingerprints:")
                for fp in cw["matched_v2_fingerprints"]:
                    lines.append(f"      - {_yaml_escape(fp)}")
            if cw.get("note"):
                lines.append(f"    note: {_yaml_escape(cw['note'])}")
        lines.append("")

    if duplicates:
        lines.append("duplicate_diagnostics:")
        for dup in duplicates:
            lines.append(f"  - rule: {_yaml_escape(dup['rule'])}")
            lines.append(f"    path: {_yaml_escape(dup['path'])}")
            lines.append(f"    line: {dup['line']}")
            lines.append(f"    count: {dup['count']}")
            lines.append(f"    diagnosis: {dup['diagnosis']}")
        lines.append("")

    lines.append("triage_entries:")
    if not entries:
        lines.append("  []")
    else:
        for entry in entries:
            lines.append(f"  - fingerprint: {_yaml_escape(entry['fingerprint'])}")
            lines.append(f"    classification: {entry['classification']}")
            lines.append(f"    path: {_yaml_escape(entry['path'])}")
            sym = entry["symbol"]
            lines.append("    symbol:")
            lines.append(f"      owner: {_yaml_escape(sym['owner'])}")
            lines.append(f"      name: {_yaml_escape(sym['name'])}")
            lines.append(f"      receiver: {_yaml_escape(str(sym['receiver'])) if sym['receiver'] is not None else 'null'}")
            lines.append("      parameters:")
            if sym["parameters"]:
                for p in sym["parameters"]:
                    lines.append(f"        - {_yaml_escape(p)}")
            else:
                lines.append("        []")
            lines.append(f"      kind: {_yaml_escape(sym['kind'])}")
            lines.append(f"    dao: {_yaml_escape(str(entry['dao'])) if entry['dao'] is not None else 'null'}")
            lines.append(f"    operation: {_yaml_escape(str(entry['operation'])) if entry['operation'] is not None else 'null'}")
            lines.append(f"    present_at_reference_sha: {_yaml_escape(str(entry['present_at_reference_sha']))}")
            lines.append(f"    owner: null")
            lines.append(f"    linked_issue: null")
            lines.append(f"    reason: null")
            lines.append(f"    expires: null")
            lines.append(f"    evidence: []")
    lines.append("")

    content = "\n".join(lines)

    # Atomic write: sibling temp + fsync + os.replace
    import tempfile
    tmp_fd = None
    tmp_name = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp_fd, tmp_name = tempfile.mkstemp(
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=str(path.parent),
            text=True,
        )
        with os.fdopen(tmp_fd, "w", encoding="utf-8", newline="\n") as f:
            tmp_fd = None
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, str(path))
        tmp_name = None
    finally:
        if tmp_fd is not None:
            try:
                os.close(tmp_fd)
            except OSError:
                pass
        if tmp_name is not None:
            try:
                os.unlink(tmp_name)
            except OSError:
                pass



# ------------------------------------------------------------------
# Main
# ------------------------------------------------------------------


def main(argv: Optional[Sequence[str]] = None) -> None:
    parser = argparse.ArgumentParser(
        description="Build DB finding triage YAML from v2 report and v1 baseline."
    )
    parser.add_argument(
        "--v2-report",
        required=True,
        help="Path to the protocol-v2 DB report JSON file.",
    )
    parser.add_argument(
        "--v1-baseline",
        required=True,
        help="Path to the old v1 baseline JSON file.",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Path to write the triage YAML file.",
    )
    parser.add_argument(
        "--reference-sha",
        default="unknown",
        help="Approved reference SHA for historical proof (default: unknown).",
    )
    args = parser.parse_args(argv)

    v2_report_path = Path(args.v2_report)
    v1_baseline_path = Path(args.v1_baseline)
    output_path = Path(args.output)

    # 1. Load v2 report
    #    DuplicateFindingError is a controlled fail-closed (exit 2) path:
    #    exact duplicates in the v2 report are an infrastructure/schema error.
    try:
        report = load_report_json(v2_report_path)
    except DuplicateFindingError as exc:
        print(
            f"ERROR: DuplicateFindingError in v2 report: "
            f"code={getattr(exc, 'code', 'UNKNOWN')}",
            file=sys.stderr,
        )
        sys.exit(2)
    except Exception as exc:
        print(
            f"ERROR: Failed to load v2 report: {exc.__class__.__name__}: "
            f"{getattr(exc, 'code', 'UNKNOWN')}",
            file=sys.stderr,
        )
        sys.exit(2)

    # 2. Load v1 baseline
    v1_data = load_v1_baseline(v1_baseline_path)
    v1_fingerprints = v1_data.get("fingerprints", [])

    # 3. Build v2 findings index by (rule, path)
    v2_by_rule_path: Dict[Tuple[str, str], List[Dict[str, Any]]] = {}
    for finding in report.findings:
        key = (finding.rule, finding.path)
        v2_by_rule_path.setdefault(key, []).append({
            "fingerprint": finding.fingerprint,
            "rule": finding.rule,
            "path": finding.path,
        })

    # 4. Build crosswalk
    crosswalk = build_crosswalk(v1_fingerprints, v2_by_rule_path)

    # 5. Diagnose duplicates
    duplicates = diagnose_duplicates(report.findings)

    # 6. Build triage entries (all PENDING)
    #    DuplicateFindingError from aggregate_findings is fail-closed (exit 2):
    #    exact duplicates in the v2 report are an infrastructure/schema error,
    #    not a triage-able classification.
    try:
        entries = build_triage_entries(report)
    except DuplicateFindingError as exc:
        print(
            f"ERROR: DuplicateFindingError in v2 report: "
            f"code={getattr(exc, 'code', 'UNKNOWN')}",
            file=sys.stderr,
        )
        sys.exit(2)

    # 7. Build metadata
    metadata = {
        "generated_by": "build_db_finding_triage.py",
        "v2_report": str(v2_report_path),
        "v1_baseline": str(v1_baseline_path),
        "reference_sha": args.reference_sha,
        "total_v2_findings": len(report.findings),
        "total_v2_aggregates": len(entries),
        "total_v1_entries": len(v1_fingerprints),
        "crosswalk_one_to_one": sum(1 for c in crosswalk if c["outcome"] == "ONE_TO_ONE"),
        "crosswalk_one_to_many": sum(1 for c in crosswalk if c["outcome"] == "ONE_TO_MANY"),
        "crosswalk_no_current_match": sum(1 for c in crosswalk if c["outcome"] == "NO_CURRENT_MATCH"),
        "crosswalk_unresolved_rule_mapping": sum(1 for c in crosswalk if c["outcome"] == "UNRESOLVED_RULE_MAPPING"),
        "all_classifications_pending": True,
    }

    # 8. Write output
    write_triage_yaml(output_path, entries, crosswalk, duplicates, metadata)

    print(f"Triage written: {output_path}")
    print(f"  v2 findings: {metadata['total_v2_findings']}")
    print(f"  v2 aggregates: {metadata['total_v2_aggregates']}")
    print(f"  v1 entries: {metadata['total_v1_entries']}")
    print(f"  crosswalk 1:1: {metadata['crosswalk_one_to_one']}")
    print(f"  crosswalk 1:N: {metadata['crosswalk_one_to_many']}")
    print(f"  crosswalk no match: {metadata['crosswalk_no_current_match']}")
    print(f"  crosswalk unresolved: {metadata['crosswalk_unresolved_rule_mapping']}")
    print(f"  duplicates diagnosed: {len(duplicates)}")
    print(f"  all classifications: PENDING")


if __name__ == "__main__":
    main()
