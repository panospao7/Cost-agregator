#!/usr/bin/env python3
"""
generate_db_baseline_v2.py -- PR-D5 baseline candidate generator.

Consumes a triage YAML and the current v2 report, and generates a v2
baseline candidate containing only PREEXISTING_TEMPORARY_DEBT entries
that meet all acceptance criteria.

GR-09 writer hardening (plan "Required tooling behavior"): the writer
requires a trusted findings report, an explicit reviewed-debt manifest
(the triage YAML), an explicit --generated-at value from the review
evidence (never a hidden current-time default), an explicit --report-sha256
evidence hash, and an explicit --output path.  It refuses to write a
candidate when the approved-debt set does not exactly equal the current
unresolved finding set, so an unreviewed report can never be bulk-copied
into a baseline.

Exit codes:
  0 -- baseline candidate written successfully (or no candidate needed:
       no entries met acceptance criteria but triage is complete and the
       current report has no unapproved findings)
  1 -- incomplete/rejected triage: PENDING entries remain, triage
       contains rejected classifications (LEGAL_WRITER_POLICY_MISSING,
       REAL_ARCHITECTURE_VIOLATION, PARSER_FALSE_POSITIVE,
       STRUCTURAL_OPERATION, ANALYZER_UNSUPPORTED, DUPLICATE_DETECTION)
       that must be resolved before generating a baseline candidate, a
       stale manifest fingerprint (triage fingerprint absent from the
       current report), an unapproved current finding (current report
       fingerprint with no accepted triage entry -- the bulk-generation
       refusal), or an expiry beyond --expires-max-days
  2 -- infrastructure/schema/validation failure (fail closed): malformed
       inputs, missing files, path conflicts, schema errors, mismatched
       --report-sha256, blocking (non-advisory) report diagnostics, an
       explicitly untrusted report, or duplicate triage fingerprints

Acceptance criteria for baseline candidates:
  - Classification must be PREEXISTING_TEMPORARY_DEBT
  - owner, linked_issue, reason, expires must all be non-null
  - expires must be a valid ISO-8601 date in the future and no more than
    --expires-max-days (default 60) calendar days after the approval date
    (the date component of --generated-at)
  - present_at_reference_sha must be a valid SHA (not "unknown")
  - evidence must be non-empty
  - Fingerprint must match a current v2 finding exactly
  - Count must match the current v2 finding count exactly

Rejection criteria (all refuse to write a candidate):
  - Any PENDING entries remain
  - Any LEGAL_WRITER_POLICY_MISSING entries
  - Any PARSER_FALSE_POSITIVE entries
  - Any ANALYZER_UNSUPPORTED entries
  - Any DUPLICATE_DETECTION entries
  - Missing metadata (owner/issue/reason/expiry)
  - Expired entries
  - Unproven historical presence (unknown SHA)
  - Fingerprint not in current v2 report (stale manifest fingerprint)
  - Count mismatch
  - Current report finding with no accepted triage entry (unapproved
    finding / bulk-generation refusal)
  - Duplicate triage fingerprints
  - Expiry beyond --expires-max-days after the approval date
  - Blocking (non-advisory) report diagnostics / explicitly untrusted report
  - --report-sha256 mismatch
  - Output path colliding with the active baseline, the active ownership
    policy, the triage manifest, or the v2 report

Never overwrites active baseline or active policy.  Requires explicit
--output path.  Atomic write via sibling-temp + fsync + os.replace.

Usage:
  python scripts/ci/generate_db_baseline_v2.py \
    --triage build/guard-v2/DB_ACCESS_V2_TRIAGE.yml \
    --v2-report build/guard-v2/db-final-inventory.json \
    --report-sha256 <sha256 of the v2 report file> \
    --generated-at 2026-08-29T00:00:00Z \
    --output build/guard-v2/db_access_v2_candidate.json
"""

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guard_findings import (  # noqa: E402
    GuardRunReport,
    load_report_json,
    aggregate_findings,
)
from finding_rule_catalog import RULE_PROFILES  # noqa: E402

# ------------------------------------------------------------------
# Constants
# ------------------------------------------------------------------

# Classifications that are accepted for baseline candidacy.
_ACCEPTED_CLASSIFICATION = "PREEXISTING_TEMPORARY_DEBT"

# Classifications that cause immediate rejection (no candidate generated).
_REJECTED_CLASSIFICATIONS = frozenset({
    "LEGAL_WRITER_POLICY_MISSING",
    "REAL_ARCHITECTURE_VIOLATION",
    "PARSER_FALSE_POSITIVE",
    "STRUCTURAL_OPERATION",
    "ANALYZER_UNSUPPORTED",
    "DUPLICATE_DETECTION",
    "PENDING",
})

# Required non-null metadata fields for a baseline candidate.
_REQUIRED_METADATA = ("owner", "linked_issue", "reason", "expires")

# SHA-1 or SHA-256 hex pattern.
_SHA_RE = re.compile(r'^[0-9a-f]{8,64}$')

# ISO-8601 date pattern.
_DATE_RE = re.compile(r'^\d{4}-\d{2}-\d{2}$')

# Strict canonical ISO-8601 datetime with an explicit timezone, matching the
# guard_ratchet.py baseline contract (YYYY-MM-DDTHH:MM:SS[.ffffff](Z|±HH:MM)).
# The explicit --generated-at value must be one canonical form so the written
# candidate always satisfies the ratchet's strict generated_at validation.
_ISO8601_DATETIME_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
    r"(?:\.\d{1,6})?"
    r"(?:Z|[+-]\d{2}:\d{2})$"
)

# Exact lowercase-or-uppercase 64-hex SHA-256 digest.
_SHA256_RE = re.compile(r'^[0-9a-fA-F]{64}$')

# Default maximum reviewed-debt expiry horizon in calendar days after the
# approval date (plan debt rule 2: no more than 60 days; no permanent
# exemption).
_DEFAULT_EXPIRES_MAX_DAYS = 60

# Default active v2 ownership policy path the writer must never overwrite.
_DEFAULT_ACTIVE_POLICY = "config/guards/db_ownership_policy.yml"


# ------------------------------------------------------------------
# Triage YAML parsing (minimal, stdlib-only)
# ------------------------------------------------------------------


def _parse_yaml_simple(text: str) -> Dict[str, Any]:
    """Minimal YAML parser for the triage format.

    Handles the specific structure of the triage YAML produced by
    build_db_finding_triage.py (see docs/ci/DB_ACCESS_V2_TRIAGE_SCHEMA.md
    for the schema documentation):
    - metadata (flat key-value)
    - crosswalk (list of dicts)
    - duplicate_diagnostics (list of dicts)
    - triage_entries (list of dicts with nested symbol)

    This is NOT a general YAML parser.  It handles only the subset
    produced by build_db_finding_triage.py.
    """
    try:
        import yaml
        return yaml.safe_load(text)
    except ImportError:
        pass

    # Fallback: parse the specific structure manually.
    # This is intentionally limited to the triage format.
    return _manual_yaml_parse(text)


def _manual_yaml_parse(text: str) -> Dict[str, Any]:
    """Manual YAML parser fallback for the triage format."""
    result: Dict[str, Any] = {}
    lines = text.split("\n")

    # Simple state machine for the specific triage YAML structure.
    current_section = None
    current_list = None
    current_item = None
    current_sublist = None
    current_subkey = None

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue

        # Detect indentation level
        indent = len(line) - len(line.lstrip())

        # Top-level keys
        if indent == 0 and ":" in stripped:
            key = stripped.split(":", 1)[0].strip()
            if key in ("metadata", "crosswalk", "duplicate_diagnostics", "triage_entries"):
                current_section = key
                if key == "metadata":
                    result[key] = {}
                else:
                    result[key] = []
                current_list = result[key]
                current_item = None
                current_sublist = None
                continue

        # Metadata section (indent=2)
        if current_section == "metadata" and indent == 2 and ":" in stripped:
            key, val = stripped.split(":", 1)
            key = key.strip()
            val = val.strip()
            if val == "null":
                result["metadata"][key] = None
            elif val == "true":
                result["metadata"][key] = True
            elif val == "false":
                result["metadata"][key] = False
            else:
                try:
                    result["metadata"][key] = int(val)
                except ValueError:
                    result["metadata"][key] = _unquote(val)
            continue

        # List items (indent=2, starts with "- ")
        if indent == 2 and stripped.startswith("- ") and isinstance(current_list, list):
            current_item = {}
            current_list.append(current_item)
            current_sublist = None
            current_subkey = None
            # Parse the first key-value on the same line as "- "
            rest = stripped[2:]
            if ":" in rest:
                key, val = rest.split(":", 1)
                current_item[key.strip()] = _unquote(val.strip())
            continue

        # Sub-keys of list items (indent=4+)
        if current_item is not None and indent >= 4 and ":" in stripped:
            key, val = stripped.split(":", 1)
            key = key.strip()
            val = val.strip()

            # Check for nested list
            if val == "" or val == "[]":
                if val == "[]":
                    current_item[key] = []
                else:
                    current_subkey = key
                    current_item[key] = []
                    current_sublist = current_item[key]
                continue

            # Check for sub-sub-list items
            if indent >= 6 and stripped.startswith("- ") and isinstance(current_sublist, list):
                rest = stripped[2:]
                current_sublist.append(_unquote(rest))
                continue

            current_item[key] = _unquote(val)
            continue

        # Sub-list items (indent=6, starts with "- ")
        if indent >= 6 and stripped.startswith("- ") and isinstance(current_sublist, list):
            rest = stripped[2:]
            if ":" in rest and indent >= 8:
                # Nested dict in sublist
                sub_item = {}
                k, v = rest.split(":", 1)
                sub_item[k.strip()] = _unquote(v.strip())
                current_sublist.append(sub_item)
            else:
                current_sublist.append(_unquote(rest))
            continue

    return result


def _unquote(val: str) -> Any:
    """Remove YAML quoting and convert types."""
    if val == "null" or val == "~":
        return None
    if val == "true":
        return True
    if val == "false":
        return False
    if val.startswith('"') and val.endswith('"'):
        return val[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    if val.startswith("'") and val.endswith("'"):
        return val[1:-1]
    try:
        return int(val)
    except ValueError:
        pass
    return val


# ------------------------------------------------------------------
# Validation
# ------------------------------------------------------------------


def _diagnostic_is_advisory(diagnostic: Any) -> bool:
    """GR-07 Option-B amendment predicate (mirrors
    ``verify_db_access_boundaries._diagnostic_is_advisory``).

    Scanner diagnostics flagged advisory in their bounded controlled context
    (``controlled_context["advisory"] is True``) never break trust; every
    pre-scan/infrastructure diagnostic is unflagged and therefore always
    blocking (fail closed).  A diagnostic without a readable controlled
    context is blocking (fail closed).
    """
    context = getattr(diagnostic, "controlled_context", None)
    try:
        return context.get("advisory") is True
    except AttributeError:
        return False


def _blocking_diagnostics(diagnostics: Any) -> List[Any]:
    """The blocking subset of a diagnostic sequence (advisory excluded)."""
    try:
        items = list(diagnostics)
    except TypeError:
        return []
    return [item for item in items if not _diagnostic_is_advisory(item)]


def _validate_expiry_range(
    entry: Dict[str, Any],
    approved_on: date,
    expires_max_days: int,
    rejection_reasons: List[str],
) -> bool:
    """Reject an accepted entry whose expiry exceeds the reviewed horizon.

    Plan debt rule 2: expiry must be no more than ``expires_max_days``
    calendar days after the approval date (the date component of the
    explicit ``--generated-at`` review-evidence value).  Returns True when
    the expiry is within range; otherwise appends a rejection reason and
    returns False.
    """
    expires = entry.get("expires")
    exp_str = str(expires).strip() if expires is not None else ""
    if not _DATE_RE.match(exp_str):
        # Malformed expiry is already rejected by validate_triage_entry;
        # treat an unparseable value here as out of range (fail closed).
        rejection_reasons.append(
            f"{entry.get('fingerprint', '<missing>')}: expiry out of "
            f"allowed range (unparseable)"
        )
        return False
    try:
        exp_date = date.fromisoformat(exp_str)
    except ValueError:
        rejection_reasons.append(
            f"{entry.get('fingerprint', '<missing>')}: expiry out of "
            f"allowed range (unparseable)"
        )
        return False
    max_date = approved_on + timedelta(days=expires_max_days)
    if exp_date > max_date:
        rejection_reasons.append(
            f"{entry.get('fingerprint', '<missing>')}: expiry out of "
            f"allowed range ({exp_str} > {max_date.isoformat()}, "
            f"max {expires_max_days} days after approval)"
        )
        return False
    return True


def validate_triage_entry(
    entry: Dict[str, Any],
    current_fingerprints: Dict[str, int],
    rejection_reasons: List[str],
) -> bool:
    """Validate a single triage entry for baseline candidacy.

    Returns True if the entry is accepted, False if rejected.
    Rejection reasons are appended to rejection_reasons.
    """
    classification = entry.get("classification", "PENDING")
    fingerprint = entry.get("fingerprint", "<missing>")

    # Reject non-PREEXISTING_TEMPORARY_DEBT classifications
    if classification != _ACCEPTED_CLASSIFICATION:
        rejection_reasons.append(
            f"{fingerprint}: rejected classification '{classification}'"
        )
        return False

    # Reject missing required metadata
    for field in _REQUIRED_METADATA:
        val = entry.get(field)
        if val is None or (isinstance(val, str) and not val.strip()):
            rejection_reasons.append(
                f"{fingerprint}: missing required field '{field}'"
            )
            return False

    # Reject unknown reference SHA
    sha = entry.get("present_at_reference_sha", "unknown")
    if sha == "unknown" or not _SHA_RE.match(str(sha)):
        rejection_reasons.append(
            f"{fingerprint}: unproven historical presence (SHA={sha!r})"
        )
        return False

    # Reject empty evidence
    evidence = entry.get("evidence", [])
    if not evidence:
        rejection_reasons.append(
            f"{fingerprint}: empty evidence"
        )
        return False

    # Reject expired entries
    expires = entry.get("expires")
    if expires is not None:
        try:
            exp_str = str(expires).strip()
            if _DATE_RE.match(exp_str):
                exp_date = date.fromisoformat(exp_str)
                if exp_date < date.today():
                    rejection_reasons.append(
                        f"{fingerprint}: expired ({exp_str})"
                    )
                    return False
        except (ValueError, TypeError):
            rejection_reasons.append(
                f"{fingerprint}: invalid expiry date '{expires}'"
            )
            return False

    # Reject fingerprint not in current v2 report
    if fingerprint not in current_fingerprints:
        rejection_reasons.append(
            f"{fingerprint}: not found in current v2 report"
        )
        return False

    return True


# ------------------------------------------------------------------
# Baseline candidate construction
# ------------------------------------------------------------------


def build_baseline_candidate(
    accepted_entries: List[Dict[str, Any]],
    current_fingerprints: Dict[str, int],
    guard: str = "db_access",
    *,
    generated_at: str,
) -> Dict[str, Any]:
    """Build the v2 baseline candidate structure.

    ``generated_at`` is REQUIRED and must be the explicit review-evidence
    timestamp supplied by the caller (the CLI enforces ``--generated-at``);
    the writer never stamps a hidden current-time default (plan Step 5).

    Schema:
    {
      "baseline_schema_version": 2,
      "guard_output_schema_version": 2,
      "fingerprint_schema_version": 2,
      "guard": "db_access",
      "generated_at": "ISO-8601",
      "entries": [...]
    }
    """
    entries = []
    for entry in accepted_entries:
        fp = entry["fingerprint"]
        count = current_fingerprints[fp]
        baseline_entry = {
            "fingerprint": fp,
            "count": count,
            "rule": fp.split("|")[2] if len(fp.split("|")) > 2 else "UNKNOWN",
            "classification": "temporary_debt",
            "reason": entry["reason"],
            "owner": entry["owner"],
            "linked_issue": entry["linked_issue"],
            "expires": entry["expires"],
        }
        entries.append(baseline_entry)

    # Deterministic ordering by fingerprint
    entries.sort(key=lambda e: e["fingerprint"])

    return {
        "baseline_schema_version": 2,
        "guard_output_schema_version": 2,
        "fingerprint_schema_version": 2,
        "guard": guard,
        "generated_at": generated_at,
        "entries": entries,
    }


# ------------------------------------------------------------------
# Atomic write
# ------------------------------------------------------------------


def write_json_atomic(path: Path, data: Dict[str, Any]) -> None:
    """Write JSON atomically via sibling-temp + fsync + os.replace."""
    fd = None
    tmp_name = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=str(path.parent),
            text=True,
        )
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            fd = None
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_name, str(path))
        tmp_name = None
    finally:
        if fd is not None:
            try:
                os.close(fd)
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
        description="Generate DB baseline v2 candidate from triage YAML."
    )
    parser.add_argument(
        "--triage",
        required=True,
        help="Path to the reviewed-debt triage YAML manifest.",
    )
    parser.add_argument(
        "--v2-report",
        required=True,
        help="Path to the current v2 report JSON file.",
    )
    parser.add_argument(
        "--report-sha256",
        required=True,
        help="Explicit SHA-256 hex digest of the v2 report file (evidence "
             "hash); a mismatch fails closed with exit 2.",
    )
    parser.add_argument(
        "--generated-at",
        required=True,
        help="Explicit review-evidence generation timestamp (strict "
             "ISO-8601 with an explicit timezone, e.g. 2026-08-29T00:00:00Z). "
             "Never defaulted to the current time.",
    )
    parser.add_argument(
        "--expires-max-days",
        type=int,
        default=_DEFAULT_EXPIRES_MAX_DAYS,
        help="Maximum reviewed-debt expiry horizon in calendar days after "
             f"the approval date (default {_DEFAULT_EXPIRES_MAX_DAYS}).",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Path to write the baseline candidate JSON (must not be the "
             "active baseline, the active policy, or an input file).",
    )
    parser.add_argument(
        "--active-baseline",
        default="config/baselines/db_access.json",
        help="Path to the active baseline (candidate must not overwrite it).",
    )
    parser.add_argument(
        "--active-policy",
        default=_DEFAULT_ACTIVE_POLICY,
        help="Path to the active v2 ownership policy (candidate must not "
             "overwrite it).",
    )
    args = parser.parse_args(argv)

    triage_path = Path(args.triage)
    v2_report_path = Path(args.v2_report)
    output_path = Path(args.output)
    active_baseline_path = Path(args.active_baseline)
    active_policy_path = Path(args.active_policy)

    # -- Explicit generated-at (strict ISO-8601 with an explicit timezone) ---
    # The writer never stamps a hidden current-time default: the approval
    # date and the written generated_at both come from the review evidence.
    generated_at = args.generated_at
    if not _ISO8601_DATETIME_RE.match(generated_at):
        print(
            "ERROR: --generated-at must be a strict ISO-8601 timestamp with "
            "an explicit timezone (e.g. 2026-08-29T00:00:00Z)",
            file=sys.stderr,
        )
        sys.exit(2)
    try:
        generated_at_dt = datetime.fromisoformat(
            generated_at.replace("Z", "+00:00")
        )
    except ValueError:
        print(
            "ERROR: --generated-at must be a valid calendar ISO-8601 timestamp",
            file=sys.stderr,
        )
        sys.exit(2)
    approved_on = generated_at_dt.date()

    # -- Explicit expiry horizon ----------------------------------------------
    expires_max_days = args.expires_max_days
    if isinstance(expires_max_days, bool) or expires_max_days < 1:
        print(
            "ERROR: --expires-max-days must be a positive integer",
            file=sys.stderr,
        )
        sys.exit(2)

    # -- Explicit report evidence hash ----------------------------------------
    report_sha256 = str(args.report_sha256).strip().lower()
    if not _SHA256_RE.match(report_sha256):
        print(
            "ERROR: --report-sha256 must be a 64-character hex SHA-256 digest",
            file=sys.stderr,
        )
        sys.exit(2)

    # -- Guard: never overwrite the active baseline / policy / inputs ---------
    protected_paths = (
        ("the active baseline", active_baseline_path),
        ("the active ownership policy", active_policy_path),
        ("the triage manifest", triage_path),
        ("the v2 report", v2_report_path),
    )
    for label, protected_path in protected_paths:
        try:
            collides = output_path.resolve() == protected_path.resolve()
        except OSError:
            # If resolve fails (e.g. file doesn't exist), do a string comparison
            collides = str(output_path) == str(protected_path)
        if collides:
            print(
                f"ERROR: Output path must not be {label}: {output_path}",
                file=sys.stderr,
            )
            sys.exit(2)

    # -- Guard: the report file must match the explicit evidence hash ---------
    try:
        report_bytes = v2_report_path.read_bytes()
    except FileNotFoundError:
        print("ERROR: v2 report file not found", file=sys.stderr)
        sys.exit(2)
    except OSError:
        print("ERROR: Failed to read v2 report file", file=sys.stderr)
        sys.exit(2)
    actual_sha256 = hashlib.sha256(report_bytes).hexdigest()
    if actual_sha256 != report_sha256:
        print(
            "ERROR: Report hash mismatch: --report-sha256 does not match the "
            "v2 report file contents",
            file=sys.stderr,
        )
        sys.exit(2)

    # 1. Load v2 report
    try:
        report = load_report_json(v2_report_path)
    except Exception as exc:
        print(
            f"ERROR: Failed to load v2 report: {exc.__class__.__name__}: "
            f"{getattr(exc, 'code', 'UNKNOWN')}",
            file=sys.stderr,
        )
        sys.exit(2)

    # 1b. Trust gates (fail closed).  Blocking (non-advisory) diagnostics are
    # infrastructure failures and never baseline-able; an explicit
    # trusted=false statistics flag marks an untrusted run.  Advisory-marked
    # scanner diagnostics (GR-07 Option-B amendment) never break trust.
    blocking = _blocking_diagnostics(report.diagnostics)
    if blocking:
        print(
            "ERROR: Report is untrusted: it carries blocking infrastructure "
            f"diagnostics ({len(blocking)}); diagnostics are never "
            "baseline-able",
            file=sys.stderr,
        )
        sys.exit(2)
    try:
        trusted_flag = report.statistics.get("trusted")
    except Exception:
        trusted_flag = None
    if trusted_flag is False:
        print(
            "ERROR: Report is untrusted: its statistics carry trusted=false",
            file=sys.stderr,
        )
        sys.exit(2)

    # Build fingerprint -> count map from current report
    aggregates = aggregate_findings(report.findings)
    current_fingerprints: Dict[str, int] = {
        agg.fingerprint: agg.count for agg in aggregates
    }

    # 2. Load triage YAML
    try:
        with open(triage_path, "r", encoding="utf-8") as f:
            triage_text = f.read()
    except FileNotFoundError:
        print(f"ERROR: Triage file not found: {triage_path}", file=sys.stderr)
        sys.exit(2)
    except Exception as exc:
        print(
            f"ERROR: Failed to read triage file: {exc.__class__.__name__}",
            file=sys.stderr,
        )
        sys.exit(2)

    try:
        triage_data = _parse_yaml_simple(triage_text)
    except Exception as exc:
        print(
            f"ERROR: Failed to parse triage YAML: {exc.__class__.__name__}",
            file=sys.stderr,
        )
        sys.exit(2)

    if not isinstance(triage_data, dict):
        print("ERROR: Triage YAML must be a mapping", file=sys.stderr)
        sys.exit(2)

    triage_entries = triage_data.get("triage_entries", [])
    if not isinstance(triage_entries, list):
        print("ERROR: triage_entries must be a list", file=sys.stderr)
        sys.exit(2)

    # 2b. Duplicate triage fingerprints are a schema defect (exit 2): a
    # manifest that lists the same fingerprint twice can never map to a
    # deterministic baseline.
    seen_fps = set()
    duplicate_fps = set()
    for entry in triage_entries:
        if not isinstance(entry, dict):
            continue
        fp = entry.get("fingerprint")
        if isinstance(fp, str) and fp.strip():
            if fp in seen_fps:
                duplicate_fps.add(fp)
            seen_fps.add(fp)
    if duplicate_fps:
        print(
            "ERROR: Triage manifest contains duplicate fingerprints "
            f"({len(duplicate_fps)}); each fingerprint must appear exactly "
            "once",
            file=sys.stderr,
        )
        sys.exit(2)

    # 2c. Stale manifest fingerprints are a hard rejection (exit 1): every
    # triage fingerprint must match a current v2 finding exactly, otherwise
    # the manifest does not describe the current report state.
    stale_fps = sorted(fp for fp in seen_fps if fp not in current_fingerprints)
    if stale_fps:
        print(
            "REJECTED: Triage manifest contains stale fingerprints not "
            f"present in the current v2 report ({len(stale_fps)}).",
            file=sys.stderr,
        )
        for fp in stale_fps[:10]:
            print(f"    - {fp}", file=sys.stderr)
        if len(stale_fps) > 10:
            print(f"    ... and {len(stale_fps) - 10} more", file=sys.stderr)
        sys.exit(1)

    # 3. Validate entries
    rejection_reasons: List[str] = []
    accepted: List[Dict[str, Any]] = []

    for entry in triage_entries:
        if not isinstance(entry, dict):
            rejection_reasons.append(f"non-dict entry: {type(entry).__name__}")
            continue
        if validate_triage_entry(entry, current_fingerprints, rejection_reasons):
            accepted.append(entry)

    # 4. Check for blocking conditions
    classifications = set()
    for entry in triage_entries:
        if isinstance(entry, dict):
            classifications.add(entry.get("classification", "PENDING"))

    has_pending = "PENDING" in classifications
    has_rejected_class = bool(classifications & _REJECTED_CLASSIFICATIONS - {"PENDING"})

    if has_pending:
        print(
            "REJECTED: Triage contains PENDING entries. "
            "All entries must be classified before generating a baseline candidate.",
            file=sys.stderr,
        )
        print(f"  Rejection reasons ({len(rejection_reasons)}):", file=sys.stderr)
        for reason in rejection_reasons[:10]:
            print(f"    - {reason}", file=sys.stderr)
        if len(rejection_reasons) > 10:
            print(f"    ... and {len(rejection_reasons) - 10} more", file=sys.stderr)
        sys.exit(1)

    if has_rejected_class:
        rejected_classes = classifications & _REJECTED_CLASSIFICATIONS - {"PENDING"}
        print(
            f"REJECTED: Triage contains rejected classifications: "
            f"{sorted(rejected_classes)}. "
            f"These must be resolved before generating a baseline candidate.",
            file=sys.stderr,
        )
        sys.exit(1)

    # 4b. Expiry horizon: every accepted entry must expire within
    # --expires-max-days calendar days after the approval date (the date
    # component of the explicit --generated-at review-evidence value).
    range_rejections: List[str] = []
    in_range: List[Dict[str, Any]] = []
    for entry in accepted:
        if _validate_expiry_range(
            entry, approved_on, expires_max_days, range_rejections
        ):
            in_range.append(entry)
    if range_rejections:
        print(
            "REJECTED: Triage contains expiry dates beyond the allowed "
            f"horizon ({expires_max_days} days after approval "
            f"{approved_on.isoformat()}).",
            file=sys.stderr,
        )
        for reason in range_rejections[:10]:
            print(f"    - {reason}", file=sys.stderr)
        if len(range_rejections) > 10:
            print(f"    ... and {len(range_rejections) - 10} more", file=sys.stderr)
        sys.exit(1)
    accepted = in_range

    # 4c. Unapproved current findings (bulk-generation refusal): the
    # approved-debt set must exactly equal the current unresolved finding
    # set (plan Step 3 equality).  Any current report fingerprint without
    # an accepted triage entry is unapproved -- the writer refuses to
    # silently omit it (or bulk-copy it unreviewed) into a baseline.
    approved_fps = {entry["fingerprint"] for entry in accepted}
    unapproved_fps = sorted(
        fp for fp in current_fingerprints if fp not in approved_fps
    )
    if unapproved_fps:
        print(
            "REJECTED: Current v2 report contains findings with no approved "
            f"triage entry ({len(unapproved_fps)}); an unreviewed report can "
            "never be bulk-copied into a baseline.",
            file=sys.stderr,
        )
        for fp in unapproved_fps[:10]:
            print(f"    - {fp}", file=sys.stderr)
        if len(unapproved_fps) > 10:
            print(f"    ... and {len(unapproved_fps) - 10} more", file=sys.stderr)
        sys.exit(1)

    if not accepted:
        print(
            "NO CANDIDATE: No entries met the acceptance criteria.",
            file=sys.stderr,
        )
        if rejection_reasons:
            print(f"  Rejection reasons ({len(rejection_reasons)}):", file=sys.stderr)
            for reason in rejection_reasons[:10]:
                print(f"    - {reason}", file=sys.stderr)
        # Exit 0: no candidate is not an error
        sys.exit(0)

    # 5. Build and write candidate
    candidate = build_baseline_candidate(
        accepted, current_fingerprints, generated_at=generated_at
    )
    write_json_atomic(output_path, candidate)

    print(f"Baseline candidate written: {output_path}")
    print(f"  Total triage entries: {len(triage_entries)}")
    print(f"  Accepted: {len(accepted)}")
    print(f"  Rejected: {len(rejection_reasons)}")
    print(f"  Entries in candidate: {len(candidate['entries'])}")


if __name__ == "__main__":
    main()
