#!/usr/bin/env python3
"""
VERIFY_DB_POLICY_V2_EVIDENCE -- PR-GR-06 Slice 3 shadow evidence CLI.

Verifies the v2 DB ownership signatures CANDIDATE (default
``config/guards/db_ownership_policy.signatures.candidate.yml``) against exact
production source evidence and writes a deterministic JSON report:

  1. the candidate is loaded with ``scripts.db_guard.policy_v2_loader.
     load_policy_v2`` (any loader error fails closed, exit 2);
  2. production source roots are resolved from the declared manifest
     (``--manifest``, default ``config/guards/production_source_roots.yml``)
     via ``scripts.db_guard.source_roots`` (load + topology; ANY diagnostic
     fails closed, exit 2);
  3. a Room inventory is built with ``room_inventory.build_room_inventory``
     over those declared roots and passed into the verifier so the
     ``daoFqcn`` cross-check is ACTIVE (inventory diagnostics are an
     infrastructure condition -> exit 2);
  4. ``policy_v2_evidence.verify_v2_policy_source_evidence`` produces an
     ``EvidenceResult``;
  5. a deterministic JSON report is written atomically to ``--output``
     (required): ``{schema, version, policy_path, policy_sha256,
     tree_sha256, trusted, groups, diagnostics, mutation_key_count,
     policy_mutation_key_count}`` plus, when requested, a report-only
     ``shadow_comparison`` section.

Exit codes: ``0`` only when the candidate verifies TRUSTED; ``2`` for every
failure mode (untrusted candidate, loader/root/inventory diagnostics,
unreadable inputs, output collision, report-write failure).  Uncertainty is
an infrastructure condition, never a pass.

SHADOW-ONLY guarantees (this tool never activates anything):

  * read-only over every input (candidate, manifest, accounting artifact,
    legacy shadow report); the ONLY file it writes is ``--output``;
  * ``--output`` colliding with the active policy, the candidate, the
    manifest, the accounting artifact, or the legacy report input is
    rejected before any work (exit 2);
  * no ratchet interaction and no activation mode of any kind.

Legacy shadow comparison (only when ``--legacy-shadow-report`` is given):
the legacy findings report written by ``scripts/verify_db_access_boundaries``
(protocol-v2 guard-findings JSON) is read read-only and its differences vs
the v2 result are classified against the GR-05 accounting artifact
(``--accounting``, default
``config/guards/db_ownership_policy.signatures.accounting.json``) into
EXACTLY five closed classes:

  * ``EXPECTED_LEGACY_OVERLOAD_UNION`` -- a legacy finding whose callable has
    no v2 group while its accounting row RESOLVED (the legacy gate verified
    overload unions; v2 pins exact signatures);
  * ``LEGACY_STALE_ENTRY`` -- a legacy finding whose callable has no v2 group
    and whose accounting disposition is UNRESOLVED debt;
  * ``PARSER_OR_RESOLVER_DEFECT`` -- a v2 group that is untrusted (or a
    batch-level verification failure);
  * ``CANDIDATE_GAP`` -- a TRUSTED v2 group absent from the legacy findings
    whose accounting disposition is UNRESOLVED;
  * ``UNREVIEWED_DIFFERENCE`` -- everything not derivable from the two
    artifacts (including a trusted group absent from the legacy findings
    whose rows are all RESOLVED, and any callable that cannot be attributed
    to an accounting row at all).

The comparison is REPORT-ONLY: it NEVER influences the v2 exit code.  The
section carries ``reviewed: false`` plus per-class counts and bounded
entries; ``CANDIDATE_GAP``, ``PARSER_OR_RESOLVER_DEFECT``, and
``UNREVIEWED_DIFFERENCE`` deltas block GR-07 activation until human review.

Privacy posture: reports carry repository-relative POSIX paths, controlled
codes, hashes, and counts only -- never absolute paths, raw source, or
exception text.  The CLI accepts tokenized argv only (no shell).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from typing import Any, Dict, List, Optional, Tuple

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PROJECT_ROOT = os.path.dirname(os.path.dirname(_SCRIPT_DIR))
for _path in (_SCRIPT_DIR, _PROJECT_ROOT):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    COVERAGE_OBSERVED_BUT_UNRESOLVED,
    OUTCOME_RESOLVED,
    OUTCOME_UNRESOLVED,
    production_source_manifest_digest,
)
from scripts.db_guard.policy_v2_evidence import (  # noqa: E402
    verify_v2_policy_source_evidence,
)
from scripts.db_guard.policy_v2_loader import load_policy_v2  # noqa: E402
from scripts.db_guard.room_inventory import build_room_inventory  # noqa: E402
from scripts.db_guard.source_roots import (  # noqa: E402
    load_source_root_manifest,
    verify_declared_root_topology,
)

# ── Controlled constants ─────────────────────────────────────────────────────

REPORT_SCHEMA_NAME = "db-policy-v2-evidence-shadow-report"
REPORT_SCHEMA_VERSION = 1

DEFAULT_POLICY_RELPATH = (
    "config/guards/db_ownership_policy.signatures.candidate.yml"
)
DEFAULT_ACCOUNTING_RELPATH = (
    "config/guards/db_ownership_policy.signatures.accounting.json"
)
DEFAULT_MANIFEST_RELPATH = "config/guards/production_source_roots.yml"
ACTIVE_POLICY_RELPATH = "config/guards/db_ownership_policy.yml"
RAW_QUERY_POLICY_RELPATH = "config/guards/db_raw_query_classification.yml"

# The legacy findings-report format written by scripts/verify_db_access_boundaries.
LEGACY_REPORT_SCHEMA = "cost-aggregator.guard-findings"
LEGACY_REPORT_SCHEMA_VERSION = 2

# Closed shadow-delta classification (report-only; fixed order = report order).
CLASS_EXPECTED_LEGACY_OVERLOAD_UNION = "EXPECTED_LEGACY_OVERLOAD_UNION"
CLASS_CANDIDATE_GAP = "CANDIDATE_GAP"
CLASS_LEGACY_STALE_ENTRY = "LEGACY_STALE_ENTRY"
CLASS_PARSER_OR_RESOLVER_DEFECT = "PARSER_OR_RESOLVER_DEFECT"
CLASS_UNREVIEWED_DIFFERENCE = "UNREVIEWED_DIFFERENCE"
SHADOW_DELTA_CLASSES = (
    CLASS_EXPECTED_LEGACY_OVERLOAD_UNION,
    CLASS_CANDIDATE_GAP,
    CLASS_LEGACY_STALE_ENTRY,
    CLASS_PARSER_OR_RESOLVER_DEFECT,
    CLASS_UNREVIEWED_DIFFERENCE,
)
#: Classes whose deltas block GR-07 activation until human review.
GR07_BLOCKING_CLASSES = frozenset(
    {CLASS_CANDIDATE_GAP, CLASS_PARSER_OR_RESOLVER_DEFECT, CLASS_UNREVIEWED_DIFFERENCE}
)

# Controlled comparison reason codes (bounded context ``reason`` values only).
REASON_ROW_RESOLVED = "accounting-row-resolved"
REASON_ROW_UNRESOLVED = "accounting-row-unresolved"
REASON_NO_ROW = "legacy-row-not-derivable"
REASON_GROUP_UNTRUSTED = "v2-group-untrusted"
REASON_BATCH_UNTRUSTED = "batch-untrusted"
REASON_TRUSTED_ABSENT = "trusted-group-absent-from-legacy-findings"

# Controlled comparison-level diagnostic codes (closed set).
COMPARE_LEGACY_UNREADABLE = "legacy-report-unreadable"
COMPARE_LEGACY_MALFORMED = "legacy-report-malformed"
COMPARE_LEGACY_SCHEMA_UNSUPPORTED = "legacy-report-schema-unsupported"
COMPARE_ACCOUNTING_UNREADABLE = "accounting-unreadable"
COMPARE_ACCOUNTING_MALFORMED = "accounting-malformed"

# CLI-boundary failure codes (printed bounded, one line each).
CODE_OUTPUT_COLLISION = "DB_V2_SHADOW_OUTPUT_COLLISION"
CODE_REPORT_WRITE_FAILED = "DB_V2_SHADOW_REPORT_WRITE_FAILED"
CODE_POLICY_UNREADABLE = "DB_V2_SHADOW_POLICY_UNREADABLE"

_TARGET_ACTIVE_POLICY = "active-policy"
_TARGET_CANDIDATE = "candidate-policy"
_TARGET_MANIFEST = "source-root-manifest"
_TARGET_ACCOUNTING = "accounting-artifact"
_TARGET_LEGACY_REPORT = "legacy-shadow-report"

_BATCH_TARGET = "<batch>"
_MAX_SHADOW_ENTRIES = 200
_MAX_BOUND_TEXT = 400

__all__ = [
    "classify_shadow_deltas",
    "main",
    "SHADOW_DELTA_CLASSES",
    "GR07_BLOCKING_CLASSES",
]


# ── Small shared helpers ─────────────────────────────────────────────────────


def _sha256_file(path):
    # type: (str) -> Optional[str]
    """Lowercase sha256 hex of file bytes, or ``None`` when unreadable."""
    digest = hashlib.sha256()
    try:
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(65536), b""):
                digest.update(chunk)
    except OSError:
        return None
    return digest.hexdigest()


def _resolve_against_root(root, value):
    # type: (str, str) -> str
    """Resolve a CLI path against ``root`` unless it is already absolute."""
    if os.path.isabs(value):
        return value
    return os.path.join(root, value)


def _report_path(path, root):
    # type: (str, str) -> str
    """Repository-relative POSIX rendering for reports; never absolute.

    Paths outside ``root`` (different drive on Windows) collapse to the
    controlled marker ``<external>`` instead of leaking an absolute path.
    """
    try:
        relative = os.path.relpath(path, root)
    except ValueError:
        return "<external>"
    relative = relative.replace(os.sep, "/")
    if os.path.isabs(relative):
        return "<external>"
    return relative


def _bound(text):
    # type: (object) -> str
    """Bound a string taken from an external artifact before reporting."""
    if not isinstance(text, str):
        return "<non-string>"
    return text[:_MAX_BOUND_TEXT]


def _format_diagnostic(code, context):
    # type: (str, Dict[str, object]) -> str
    """One bounded deterministic line: code plus sorted key=value fields."""
    fields = " ".join(
        "{0}={1}".format(key, context[key]) for key in sorted(context)
    )
    return "{0} {1}".format(code, fields) if fields else code


def _same_path(first, second):
    # type: (str, str) -> bool
    """Case-normalized absolute path equality (Windows-safe)."""
    return os.path.normcase(os.path.abspath(first)) == os.path.normcase(
        os.path.abspath(second)
    )


def _write_report_atomic(path, text):
    # type: (str, str) -> None
    """Atomically publish the report (temp sibling + ``os.replace``).

    The report's parent directory is created when missing (the same
    contract as the sibling atomic writers in ``promote_db_policy_v2.py``
    and ``generate_db_baseline_v2.py``), so the documented
    ``--output build/...`` usage works in a fresh checkout.  Any creation
    or write failure still propagates as ``OSError`` to the CLI's
    controlled ``DB_V2_SHADOW_REPORT_WRITE_FAILED`` exit.
    """
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    handle_fd, temp_path = tempfile.mkstemp(
        prefix=".db_v2_shadow_report-", dir=directory
    )
    try:
        with os.fdopen(handle_fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
    except Exception:
        try:
            os.unlink(temp_path)
        except OSError:
            pass
        raise


# ── Reduced callable identity helpers ────────────────────────────────────────
#
# The legacy gate attributes mutations to enclosing methods WITHOUT exact
# overload signatures, while v2 groups pin exact signatures.  Attribution
# between the artifacts therefore uses the REDUCED callable identity
# ``path|owner|kind|method`` (the first four canonical-key segments), which
# both sides can derive unambiguously.


def _reduced_callable_key(canonical_key):
    # type: (str) -> str
    """First four ``|`` segments of a canonical callable/mutation key."""
    parts = str(canonical_key).split("|")
    if len(parts) < 4:
        return str(canonical_key)
    return "|".join(parts[:4])


def _intent_key(reduced_key):
    # type: (str) -> Optional[Tuple[str, str]]
    """ ``(path, "ownerFqcn#method")`` pair used by coverage intents."""
    parts = reduced_key.split("|")
    if len(parts) < 4:
        return None
    return (parts[0], parts[1] + "#" + parts[3])


def _callable_prefix_from_mutation_key(mutation_key):
    # type: (object) -> Optional[str]
    """Reduced callable prefix of one canonical MUTATION key."""
    if not isinstance(mutation_key, str):
        return None
    head = mutation_key.rsplit("|", 3)[0]
    parts = head.split("|")
    if len(parts) < 4:
        return None
    return "|".join(parts[:4])


# ── GR-05 accounting artifact index ──────────────────────────────────────────


def _load_accounting_index(path):
    # type: (str) -> Tuple[Optional[Dict[str, Any]], Optional[str], Optional[Dict[str, Any]]]
    """Read-only index of the GR-05 accounting artifact.

    Returns ``(index, reason, document)``.  ``index`` is ``None`` when the
    artifact cannot be used (``reason`` is then a controlled constant):
    every classification that would have needed it degrades to
    ``UNREVIEWED_DIFFERENCE`` instead of guessing.  Only RESOLVED records
    carry canonical mutation keys, so ``index["resolved"]`` maps reduced
    callables to legacy row indices; UNRESOLVED rows are reachable only via
    the artifact's ``sourceMutations`` coverage section
    (``OBSERVED_BUT_UNRESOLVED`` entries carry explicit legacy indices),
    indexed by ``(path, "ownerFqcn#method")``.
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            document = json.load(handle)
    except (OSError, ValueError):
        return None, COMPARE_ACCOUNTING_UNREADABLE, None
    if not isinstance(document, dict):
        return None, COMPARE_ACCOUNTING_MALFORMED, None

    records = document.get("records")
    if not isinstance(records, list):
        return None, COMPARE_ACCOUNTING_MALFORMED, document

    resolved = {}  # type: Dict[str, set]
    intents = {}  # type: Dict[Tuple[str, str], set]
    for record in records:
        if not isinstance(record, dict):
            return None, COMPARE_ACCOUNTING_MALFORMED, document
        index = record.get("index")
        outcome = record.get("outcome")
        keys = record.get("mutationKeys")
        if isinstance(index, bool) or not isinstance(index, int):
            return None, COMPARE_ACCOUNTING_MALFORMED, document
        if outcome not in (OUTCOME_RESOLVED, OUTCOME_UNRESOLVED):
            return None, COMPARE_ACCOUNTING_MALFORMED, document
        if not isinstance(keys, list):
            return None, COMPARE_ACCOUNTING_MALFORMED, document
        if outcome == OUTCOME_RESOLVED:
            for key in keys:
                prefix = _callable_prefix_from_mutation_key(key)
                if prefix is None:
                    continue
                resolved.setdefault(prefix, set()).add(index)

    source_mutations = document.get("sourceMutations")
    if source_mutations is not None:
        if not isinstance(source_mutations, list):
            return None, COMPARE_ACCOUNTING_MALFORMED, document
        for coverage in source_mutations:
            if not isinstance(coverage, dict):
                return None, COMPARE_ACCOUNTING_MALFORMED, document
            if coverage.get("kind") != COVERAGE_OBSERVED_BUT_UNRESOLVED:
                continue
            site_path = coverage.get("path")
            symbol = coverage.get("symbol")
            indices = coverage.get("legacyIndices")
            if not isinstance(site_path, str) or not isinstance(symbol, str):
                return None, COMPARE_ACCOUNTING_MALFORMED, document
            if not isinstance(indices, list):
                return None, COMPARE_ACCOUNTING_MALFORMED, document
            clean = {
                item
                for item in indices
                if not isinstance(item, bool) and isinstance(item, int)
            }
            if len(clean) != len(indices):
                return None, COMPARE_ACCOUNTING_MALFORMED, document
            intents.setdefault((site_path, symbol), set()).update(clean)

    index = {
        "resolved": resolved,
        "unresolved_intents": intents,
        "candidate_sha256": document.get("candidateSha256"),
    }
    return index, None, document


# ── Legacy findings report (protocol-v2 guard-findings shape) ────────────────


def _load_legacy_shadow_findings(path):
    # type: (str) -> Dict[str, Any]
    """Read-only parse of the legacy findings report; never raises.

    Returns a bounded summary dict: sorted unique reduced callable keys for
    well-formed findings, parsed/unparsed counters, the legacy diagnostics
    count, schema support, and a controlled ``reason`` when the report could
    not be used.  An unusable report yields ZERO findings (recorded), never
    an exit-code change.
    """
    summary = {
        "reduced": (),
        "findings_considered": 0,
        "findings_unparsed": 0,
        "diagnostic_count": 0,
        "schema_supported": False,
        "reason": None,
    }
    try:
        with open(path, "r", encoding="utf-8") as handle:
            document = json.load(handle)
    except OSError:
        summary["reason"] = COMPARE_LEGACY_UNREADABLE
        return summary
    except ValueError:
        summary["reason"] = COMPARE_LEGACY_MALFORMED
        return summary

    if (
        not isinstance(document, dict)
        or document.get("schema") != LEGACY_REPORT_SCHEMA
        or document.get("schema_version") != LEGACY_REPORT_SCHEMA_VERSION
    ):
        summary["reason"] = COMPARE_LEGACY_SCHEMA_UNSUPPORTED
        return summary

    summary["schema_supported"] = True
    diagnostics = document.get("diagnostics")
    if isinstance(diagnostics, list):
        summary["diagnostic_count"] = len(diagnostics)

    findings = document.get("findings")
    if not isinstance(findings, list):
        summary["reason"] = COMPARE_LEGACY_MALFORMED
        return summary

    reduced = set()
    unparsed = 0
    for finding in findings:
        if not isinstance(finding, dict):
            unparsed += 1
            continue
        finding_path = finding.get("path")
        symbol = finding.get("symbol")
        if not isinstance(symbol, dict):
            unparsed += 1
            continue
        owner = symbol.get("owner")
        name = symbol.get("name")
        kind = symbol.get("kind", "function")
        if not isinstance(finding_path, str) or not finding_path:
            unparsed += 1
            continue
        if not isinstance(owner, str) or not owner:
            unparsed += 1
            continue
        if not isinstance(name, str) or not name:
            unparsed += 1
            continue
        if not isinstance(kind, str) or not kind:
            unparsed += 1
            continue
        reduced.add(_bound("|".join((finding_path, owner, kind, name))))
    summary["reduced"] = tuple(sorted(reduced))
    summary["findings_considered"] = len(reduced)
    summary["findings_unparsed"] = unparsed
    return summary


# ── Shadow delta classification (report-only) ────────────────────────────────


def classify_shadow_deltas(evidence_result, legacy_reduced, accounting_index):
    # type: (Any, Optional[Tuple[str, ...]], Optional[Dict[str, Any]]) -> Dict[str, Any]
    """Classify v2-vs-legacy differences into the five closed classes.

    Decision table (plan spec; anything not derivable is UNREVIEWED):

      * legacy finding whose callable has NO v2 group ->
        EXPECTED_LEGACY_OVERLOAD_UNION when its accounting row resolved,
        LEGACY_STALE_ENTRY when the accounting shows unresolved intent,
        else UNREVIEWED_DIFFERENCE;
      * v2 group untrusted (or batch-level failure) -> PARSER_OR_RESOLVER_DEFECT;
      * trusted v2 group absent from legacy findings but a legacy row exists
        -> CANDIDATE_GAP only when the accounting shows that row unresolved,
        else UNREVIEWED_DIFFERENCE.

    ``legacy_reduced=None`` means the legacy report itself was unusable
    (unreadable/malformed/unsupported schema): the legacy-dependent rules
    above cannot fire (absence proves nothing), so only the v2-intrinsic
    PARSER_OR_RESOLVER_DEFECT rule is applied.  An unusable ACCOUNTING index
    (``accounting_index=None``) degrades every row-dependent disposition to
    UNREVIEWED_DIFFERENCE instead of guessing.

    Pure and deterministic: entries sort by (class order, target); counts
    cover every classified delta even past the bounded entry list.
    """
    counts = {class_name: 0 for class_name in SHADOW_DELTA_CLASSES}
    entries = []  # type: List[Dict[str, Any]]

    def _emit(class_name, target, indices, reason, diagnostic_codes=()):
        # type: (str, str, set, str, Tuple[str, ...]) -> None
        counts[class_name] += 1
        entry = {
            "class": class_name,
            "target": _bound(target),
            "reason": reason,
            "legacy_row_indices": sorted(indices),
        }
        if diagnostic_codes:
            entry["diagnostic_codes"] = sorted(set(diagnostic_codes))
        entries.append(entry)

    groups_by_reduced = {}  # type: Dict[str, list]
    for group in getattr(evidence_result, "groups", ()):
        reduced = _reduced_callable_key(group.callable_key_canonical)
        groups_by_reduced.setdefault(reduced, []).append(group)

    resolved_map = (
        accounting_index["resolved"] if accounting_index else {}
    )  # type: Dict[str, set]
    intent_map = (
        accounting_index["unresolved_intents"] if accounting_index else {}
    )  # type: Dict[Tuple[str, str], set]

    def _rows(reduced):
        # type: (str) -> Tuple[set, set]
        intent = intent_map.get(_intent_key(reduced), set())
        return resolved_map.get(reduced, set()), intent

    # Batch-level verification failure (no groups at all): one defect entry.
    if not groups_by_reduced and tuple(getattr(evidence_result, "diagnostics", ())):
        codes = tuple(
            diagnostic.code for diagnostic in evidence_result.diagnostics
        )
        _emit(
            CLASS_PARSER_OR_RESOLVER_DEFECT,
            _BATCH_TARGET,
            set(),
            REASON_BATCH_UNTRUSTED,
            codes,
        )

    # v2 side: untrusted groups are parser/resolver defects; trusted groups
    # absent from the legacy findings are candidate gaps or unreviewed.
    for reduced in sorted(groups_by_reduced):
        groups = groups_by_reduced[reduced]
        untrusted = [group for group in groups if not group.trusted]
        if untrusted:
            for group in sorted(untrusted, key=lambda g: g.callable_key_canonical):
                codes = tuple(d.code for d in group.diagnostics)
                _emit(
                    CLASS_PARSER_OR_RESOLVER_DEFECT,
                    group.callable_key_canonical,
                    set(),
                    REASON_GROUP_UNTRUSTED,
                    codes,
                )
            continue
        if legacy_reduced is None:
            # Legacy findings unusable: "absent from legacy findings" is not
            # derivable, so no legacy-dependent delta is emitted.
            continue
        if reduced in legacy_reduced:
            continue  # both sides see this callable: agreement, no delta
        resolved_rows, unresolved_rows = _rows(reduced)
        if unresolved_rows:
            _emit(
                CLASS_CANDIDATE_GAP,
                reduced,
                unresolved_rows,
                REASON_ROW_UNRESOLVED,
            )
        else:
            _emit(
                CLASS_UNREVIEWED_DIFFERENCE,
                reduced,
                resolved_rows,
                REASON_TRUSTED_ABSENT,
            )

    # Legacy side: findings whose callable has no v2 group at all.
    if legacy_reduced is not None:
        for reduced in sorted(set(legacy_reduced) - set(groups_by_reduced)):
            resolved_rows, unresolved_rows = _rows(reduced)
            if resolved_rows:
                _emit(
                    CLASS_EXPECTED_LEGACY_OVERLOAD_UNION,
                    reduced,
                    resolved_rows,
                    REASON_ROW_RESOLVED,
                )
            elif unresolved_rows:
                _emit(
                    CLASS_LEGACY_STALE_ENTRY,
                    reduced,
                    unresolved_rows,
                    REASON_ROW_UNRESOLVED,
                )
            else:
                _emit(
                    CLASS_UNREVIEWED_DIFFERENCE,
                    reduced,
                    set(),
                    REASON_NO_ROW,
                )

    order = {class_name: index for index, class_name in enumerate(SHADOW_DELTA_CLASSES)}
    entries.sort(key=lambda entry: (order[entry["class"]], entry["target"]))
    truncated = len(entries) > _MAX_SHADOW_ENTRIES
    return {
        "deltas_by_class": {name: counts[name] for name in SHADOW_DELTA_CLASSES},
        "entries": entries[:_MAX_SHADOW_ENTRIES],
        "entries_truncated": truncated,
        "gr07_blocked": any(counts[name] > 0 for name in GR07_BLOCKING_CLASSES),
    }


# ── CLI adapter ──────────────────────────────────────────────────────────────


def _check_output_collisions(output_path, protected):
    # type: (str, Tuple[Tuple[str, str], ...]) -> Optional[str]
    """Return the controlled target label when ``output`` collides with a
    protected artifact, else ``None``."""
    for target_label, target_path in protected:
        if target_path and _same_path(output_path, target_path):
            return target_label
    return None


def _build_shadow_section(result, legacy_summary, accounting, root):
    # type: (Any, Dict[str, Any], Dict[str, Any], str) -> Dict[str, Any]
    """Assemble the report-only ``shadow_comparison`` section."""
    legacy_usable = legacy_summary["reason"] is None
    classification = classify_shadow_deltas(
        result,
        legacy_summary["reduced"] if legacy_usable else None,
        accounting["index"],
    )
    section = {
        "reviewed": False,
        "gr07_blocked": classification["gr07_blocked"],
        "deltas_by_class": classification["deltas_by_class"],
        "entries": classification["entries"],
        "entries_truncated": classification["entries_truncated"],
        "notes": [
            (
                "Shadow comparison is report-only; it never influences the"
                " v2 exit code."
            ),
            (
                "CANDIDATE_GAP, PARSER_OR_RESOLVER_DEFECT, and"
                " UNREVIEWED_DIFFERENCE deltas block GR-07 activation"
                " pending human review."
            ),
        ],
        "legacy_report": {
            "schema_supported": legacy_summary["schema_supported"],
            "findings_considered": legacy_summary["findings_considered"],
            "findings_unparsed": legacy_summary["findings_unparsed"],
            "diagnostic_count": legacy_summary["diagnostic_count"],
            "reason": legacy_summary["reason"],
        },
        "accounting": {
            "path": accounting["report_path"],
            "sha256": accounting["sha256"],
            "status": accounting["status"],
            "candidate_sha256_matches_policy_file": accounting[
                "candidate_sha_matches"
            ],
        },
    }
    return section


def main(argv=None):
    # type: (Optional[List[str]]) -> None
    """CLI adapter; the only place in this module allowed to ``sys.exit``."""
    parser = argparse.ArgumentParser(
        description=(
            "Verify the v2 DB ownership signatures candidate against exact"
            " production source evidence (shadow-only; writes one JSON"
            " report)."
        )
    )
    parser.add_argument(
        "--root",
        type=str,
        default=".",
        help="Repository root directory (default: current directory).",
    )
    parser.add_argument(
        "--policy",
        type=str,
        default=DEFAULT_POLICY_RELPATH,
        help=(
            "v2 candidate policy path (default: "
            f"{DEFAULT_POLICY_RELPATH}, resolved relative to --root)."
        ),
    )
    parser.add_argument(
        "--output",
        type=str,
        required=True,
        help="Report JSON output path (required; resolved relative to --root).",
    )
    parser.add_argument(
        "--legacy-shadow-report",
        type=str,
        default=None,
        help=(
            "Read-only legacy findings report (verify_db_access_boundaries"
            " protocol-v2 JSON) for the report-only shadow comparison."
        ),
    )
    parser.add_argument(
        "--accounting",
        type=str,
        default=DEFAULT_ACCOUNTING_RELPATH,
        help=(
            "GR-05 accounting artifact (default: "
            f"{DEFAULT_ACCOUNTING_RELPATH}, resolved relative to --root)."
        ),
    )
    parser.add_argument(
        "--manifest",
        type=str,
        default=DEFAULT_MANIFEST_RELPATH,
        help=(
            "Source-root manifest path (default: "
            f"{DEFAULT_MANIFEST_RELPATH}, resolved relative to --root)."
        ),
    )
    args = parser.parse_args(argv)

    root = os.path.abspath(args.root)
    policy_path = _resolve_against_root(root, args.policy)
    output_path = _resolve_against_root(root, args.output)
    manifest_path = _resolve_against_root(root, args.manifest)
    legacy_path = (
        _resolve_against_root(root, args.legacy_shadow_report)
        if args.legacy_shadow_report is not None
        else None
    )
    accounting_path = (
        _resolve_against_root(root, args.accounting)
        if legacy_path is not None
        else None
    )

    # Output-collision gate runs BEFORE any other work: this tool must never
    # overwrite the active policy, the candidate, or any evidence input.
    collision = _check_output_collisions(
        output_path,
        (
            (_TARGET_ACTIVE_POLICY, os.path.join(root, ACTIVE_POLICY_RELPATH)),
            (_TARGET_CANDIDATE, policy_path),
            (_TARGET_MANIFEST, manifest_path),
            (_TARGET_ACCOUNTING, accounting_path),
            (_TARGET_LEGACY_REPORT, legacy_path),
        ),
    )
    if collision is not None:
        print(_format_diagnostic(CODE_OUTPUT_COLLISION, {"target": collision}))
        sys.exit(2)

    # 1. Candidate policy (fail closed on any loader error).
    try:
        entries, policy_errors = load_policy_v2(policy_path)
    except OSError:
        # The loader owns YAML/shape codes; a raw I/O error here must stay a
        # bounded boundary code (never a traceback leaking filesystem paths).
        print(CODE_POLICY_UNREADABLE)
        sys.exit(2)
    if policy_errors or entries is None:
        for error in policy_errors:
            print(_format_diagnostic(error.code, error.context))
        sys.exit(2)

    # 2. Declared production source roots (fail closed on ANY diagnostic).
    root_set, manifest_diagnostics = load_source_root_manifest(manifest_path)
    if root_set is None or manifest_diagnostics:
        for code, context in manifest_diagnostics:
            print(_format_diagnostic(code, context))
        sys.exit(2)
    topology_diagnostics = verify_declared_root_topology(root, root_set)
    if topology_diagnostics:
        for code, context in topology_diagnostics:
            print(_format_diagnostic(code, context))
        sys.exit(2)

    # 3. Room inventory over the declared roots (daoFqcn cross-check oracle).
    #    Inventory diagnostics are an infrastructure condition -> exit 2.
    inventory = build_room_inventory(
        root,
        os.path.join(root, RAW_QUERY_POLICY_RELPATH),
        source_root_set=root_set,
    )
    if inventory.diagnostics:
        for diagnostic in inventory.diagnostics:
            print(diagnostic)
        sys.exit(2)

    # 4. Exact v2 source-evidence verification.
    result = verify_v2_policy_source_evidence(
        entries, root, source_roots=root_set, room_inventory=inventory
    )

    # 5. Report-only legacy shadow comparison (never touches the exit code).
    shadow_section = None
    if legacy_path is not None:
        legacy_summary = _load_legacy_shadow_findings(legacy_path)
        accounting = {
            "index": None,
            "status": None,
            "sha256": None,
            "candidate_sha_matches": None,
            "report_path": None,
        }
        if accounting_path is not None:
            accounting["report_path"] = _report_path(accounting_path, root)
            accounting["sha256"] = _sha256_file(accounting_path)
            index, reason, document = _load_accounting_index(accounting_path)
            accounting["index"] = index
            accounting["status"] = reason if reason else "ok"
            if document is not None:
                candidate_sha = document.get("candidateSha256")
                policy_sha = _sha256_file(policy_path)
                if isinstance(candidate_sha, str) and policy_sha is not None:
                    accounting["candidate_sha_matches"] = (
                        candidate_sha.lower() == policy_sha.lower()
                    )
        shadow_section = _build_shadow_section(
            result, legacy_summary, accounting, root
        )

    # 6. Deterministic report payload + atomic write.
    payload = {
        "schema": REPORT_SCHEMA_NAME,
        "version": REPORT_SCHEMA_VERSION,
        "policy_path": _report_path(policy_path, root),
        "policy_sha256": _sha256_file(policy_path),
        "tree_sha256": production_source_manifest_digest(root),
        "trusted": bool(result.trusted),
        "groups": [group.to_dict() for group in result.groups],
        "diagnostics": [
            diagnostic.to_dict() for diagnostic in result.diagnostics
        ],
        "mutation_key_count": int(result.mutation_key_count),
        "policy_mutation_key_count": int(result.policy_mutation_key_count),
    }
    if shadow_section is not None:
        payload["shadow_comparison"] = shadow_section
    text = json.dumps(payload, indent=2, sort_keys=True) + "\n"
    try:
        _write_report_atomic(output_path, text)
    except OSError:
        print(CODE_REPORT_WRITE_FAILED)
        sys.exit(2)

    print("DB_V2_SHADOW_TRUSTED" if result.trusted else "DB_V2_SHADOW_UNTRUSTED")
    sys.exit(0 if result.trusted else 2)


if __name__ == "__main__":
    main()
