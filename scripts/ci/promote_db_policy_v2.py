#!/usr/bin/env python3
"""
PROMOTE_DB_POLICY_V2 -- PR-GR-07 Slice 1 controlled promotion CLI.

Promotes the verified v2 DB ownership signatures CANDIDATE (default
``config/guards/db_ownership_policy.signatures.candidate.yml``) over the
LEGACY v1 active policy (default ``config/guards/db_ownership_policy.yml``),
archiving the replaced bytes (default
``config/guards/db_ownership_policy.legacy.yml``).  This is a CONTROLLED
operation: the v1 -> v2 switch happens exclusively through this tool, never
by manual paste.

CLI (tokenized argv only, no shell):

    python scripts/ci/promote_db_policy_v2.py \
        --root . \
        [--candidate PATH] [--active PATH] [--archive PATH] \
        --accounting PATH --evidence-report PATH \
        [--manifest PATH] [--force-repromote]

``--evidence-report`` is the JSON report written by the PR-GR-06 shadow CLI
(``verify_db_policy_v2_evidence.py``); ``--accounting`` is the GR-05
migration accounting artifact.  Relative paths resolve against ``--root``.

READINESS GATES (evaluated in order; ALL must pass before ANY write; any
failure prints bounded controlled diagnostics and exits 2 with NOTHING
written):

  1. Candidate loads via ``scripts.db_guard.policy_v2_loader.load_policy_v2``
     with zero errors (which enforces ``schemaVersion == 2``).
  2. Evidence report exists, parses, carries the GR-06 report schema,
     ``trusted == true``, zero diagnostics, and its recorded
     ``policy_sha256`` equals the CURRENT candidate file SHA-256 (stale
     evidence refuses promotion).
  3. Candidate/accounting crosswalk: the accounting artifact parses; its
     ``candidateSha256`` matches the candidate bytes; every candidate entry
     mutation key appears in the accounting records' ``mutationKeys``; and
     record indices are complete ``0 .. inputCount-1``.
  4. The active path currently holds a loadable LEGACY v1 document (legacy
     entry fields, no ``schemaVersion: 2``, fully loadable via
     ``scripts.db_guard.policy_legacy.legacy_load_ownership_policy``).
     Re-promoting over an existing v2 active refuses unless
     ``--force-repromote`` is given.
  5. Archive target must not exist OR be byte-identical to the current
     active bytes (crash-healing / idempotent re-run).  On an idempotent
     re-run (active already equals the candidate bytes, ``--force-repromote``
     given) the archive MUST exist and — when the existing promotion record
     is readable — must still match its recorded ``previous_v1_sha256``.
  6. Source-root manifest loads and topology verifies (zero diagnostics);
     the production-tree manifest digest must resolve.

PROMOTION OPERATION (atomic, crash-safe ordering):

  a. Stage the EXACT candidate bytes as a temp file in the active file's
     directory; fsync.
  b. Stage the current active bytes as a temp file in the archive file's
     directory; fsync.
  c. ``os.replace`` the archive temp -> archive path FIRST (so a crash
     between the two replaces leaves archive == active and a plain re-run
     heals it), then the active temp -> active path.
  d. Write the promotion record JSON atomically to
     ``config/guards/db_ownership_policy.promotion.json`` (tracked):
     ``{schema, version, recordedShas:{candidate_sha256, active_sha256,
     previous_v1_sha256, tree_sha256, evidence_report_sha256,
     accounting_sha256}}``.  No timestamps: every field is derivable from
     current state, so re-running reproduces byte-identical records.
  e. Verify post-write: the active path loads as v2 with zero errors, the
     active bytes equal the candidate bytes READ FRESH FROM DISK (a
     candidate tampered between staging and replacement is caught here),
     and the archive bytes equal the archived snapshot.  Any post-write
     verification failure exits 2 with a controlled diagnostic; the state
     stays visible and is NOT silently rolled back.

IDEMPOTENCE: re-running after a successful promotion refuses without
``--force-repromote`` (v2 already active).  With ``--force-repromote`` and
active bytes identical to the candidate bytes, the tool takes the idempotent
path: no archive or active write occurs (the archived v1 stays preserved)
and only the deterministic promotion record is rewritten byte-identically.
A FORCED promotion over a DIFFERENT v2 active archives that active's bytes
per the same gate-5 rule (archive absent or already identical).

Exit codes: ``0`` only on a verified promotion (or verified idempotent
re-run); ``2`` for every failure mode.  Privacy posture: diagnostics carry
controlled codes, target labels, counts, and hashes only — never absolute
paths, raw exception text, or policy payloads.  Read-only over every input;
the only files written are the archive, the active policy, and the
promotion record.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from typing import Dict, List, Optional, Tuple

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PROJECT_ROOT = os.path.dirname(os.path.dirname(_SCRIPT_DIR))
for _path in (_SCRIPT_DIR, _PROJECT_ROOT):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from scripts.db_guard.policy_legacy import legacy_load_ownership_policy  # noqa: E402
from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    production_source_manifest_digest,
)
from scripts.db_guard.policy_v2_loader import (  # noqa: E402
    LEGACY_REJECTED_ENTRY_KEYS,
    V2_SCHEMA_VERSION,
    load_policy_v2,
)
from scripts.db_guard.source_roots import (  # noqa: E402
    load_source_root_manifest,
    verify_declared_root_topology,
)
import verify_db_policy_v2_evidence as _shadow_cli  # noqa: E402

# ── Controlled constants ─────────────────────────────────────────────────────

RECORD_SCHEMA_NAME = "db-policy-v2-promotion-record"
RECORD_SCHEMA_VERSION = 1

DEFAULT_CANDIDATE_RELPATH = (
    "config/guards/db_ownership_policy.signatures.candidate.yml"
)
DEFAULT_ACTIVE_RELPATH = "config/guards/db_ownership_policy.yml"
DEFAULT_ARCHIVE_RELPATH = "config/guards/db_ownership_policy.legacy.yml"
DEFAULT_ACCOUNTING_RELPATH = (
    "config/guards/db_ownership_policy.signatures.accounting.json"
)
DEFAULT_MANIFEST_RELPATH = "config/guards/production_source_roots.yml"
PROMOTION_RECORD_RELPATH = "config/guards/db_ownership_policy.promotion.json"

# Closed CLI-boundary diagnostic codes (printed bounded, one line each).
CODE_CANDIDATE_UNREADABLE = "DB_PROMOTE_CANDIDATE_UNREADABLE"
CODE_EVIDENCE_UNREADABLE = "DB_PROMOTE_EVIDENCE_UNREADABLE"
CODE_EVIDENCE_MALFORMED = "DB_PROMOTE_EVIDENCE_MALFORMED"
CODE_EVIDENCE_SCHEMA_UNSUPPORTED = "DB_PROMOTE_EVIDENCE_SCHEMA_UNSUPPORTED"
CODE_EVIDENCE_UNTRUSTED = "DB_PROMOTE_EVIDENCE_UNTRUSTED"
CODE_EVIDENCE_HAS_DIAGNOSTICS = "DB_PROMOTE_EVIDENCE_HAS_DIAGNOSTICS"
CODE_EVIDENCE_STALE_POLICY_SHA = "DB_PROMOTE_EVIDENCE_STALE_POLICY_SHA"
CODE_ACCOUNTING_UNREADABLE = "DB_PROMOTE_ACCOUNTING_UNREADABLE"
CODE_ACCOUNTING_MALFORMED = "DB_PROMOTE_ACCOUNTING_MALFORMED"
CODE_ACCOUNTING_CANDIDATE_SHA_MISMATCH = (
    "DB_PROMOTE_ACCOUNTING_CANDIDATE_SHA_MISMATCH"
)
CODE_ACCOUNTING_INDEX_INCOMPLETE = "DB_PROMOTE_ACCOUNTING_INDEX_INCOMPLETE"
CODE_CROSSWALK_MISSING_KEY = "DB_PROMOTE_CROSSWALK_MISSING_KEY"
CODE_ACTIVE_UNREADABLE = "DB_PROMOTE_ACTIVE_UNREADABLE"
CODE_ACTIVE_NOT_LEGACY_V1 = "DB_PROMOTE_ACTIVE_NOT_LEGACY_V1"
CODE_ACTIVE_ALREADY_V2 = "DB_PROMOTE_ACTIVE_ALREADY_V2"
CODE_ARCHIVE_MISMATCH = "DB_PROMOTE_ARCHIVE_MISMATCH"
CODE_TREE_DIGEST_UNAVAILABLE = "DB_PROMOTE_TREE_DIGEST_UNAVAILABLE"
CODE_PATH_COLLISION = "DB_PROMOTE_PATH_COLLISION"
CODE_WRITE_FAILED = "DB_PROMOTE_WRITE_FAILED"
CODE_RECORD_WRITE_FAILED = "DB_PROMOTE_RECORD_WRITE_FAILED"
CODE_POST_WRITE_VERIFY_FAILED = "DB_PROMOTE_POST_WRITE_VERIFY_FAILED"

# Controlled reason constants (bounded context ``reason`` values only).
REASON_ARCHIVE_UNREADABLE = "archive-unreadable"
REASON_ARCHIVE_CONTENT_MISMATCH = "archive-content-mismatch"
REASON_ARCHIVE_REQUIRED_FOR_IDEMPOTENT = (
    "archive-required-for-idempotent-rerun"
)
REASON_PREVIOUS_V1_SHA_MISMATCH = "previous-v1-sha-mismatch"

# Controlled post-write verification field tokens.
VERIFY_FIELD_ACTIVE_NOT_V2 = "active-not-v2"
VERIFY_FIELD_ACTIVE_BYTES_MISMATCH = "active-bytes-mismatch"
VERIFY_FIELD_ARCHIVE_BYTES_MISMATCH = "archive-bytes-mismatch"

# Target labels (bounded collision/context vocabulary).
_TARGET_CANDIDATE = "candidate-policy"
_TARGET_ACTIVE = "active-policy"
_TARGET_ARCHIVE = "archive-policy"
_TARGET_ACCOUNTING = "accounting-artifact"
_TARGET_EVIDENCE = "evidence-report"
_TARGET_MANIFEST = "source-root-manifest"
_TARGET_RECORD = "promotion-record"

_TEMP_STAGE_PREFIX = ".db_promote_stage-"
_TEMP_RECORD_PREFIX = ".db_promote_record-"

MODE_PROMOTE = "promote"
MODE_IDEMPOTENT = "idempotent"

__all__ = [
    "main",
    "RECORD_SCHEMA_NAME",
    "RECORD_SCHEMA_VERSION",
    "PROMOTION_RECORD_RELPATH",
]


# ── Small shared helpers ─────────────────────────────────────────────────────


def _sha256_bytes(data):
    # type: (bytes) -> str
    """Lowercase sha256 hex of in-memory bytes."""
    return hashlib.sha256(data).hexdigest()


def _read_file_bytes(path):
    # type: (str) -> Tuple[Optional[bytes], bool]
    """Read whole file bytes; ``(None, False)`` on any OSError."""
    try:
        with open(path, "rb") as handle:
            return handle.read(), True
    except OSError:
        return None, False


def _resolve_against_root(root, value):
    # type: (str, str) -> str
    """Resolve a CLI path against ``root`` unless it is already absolute."""
    if os.path.isabs(value):
        return value
    return os.path.join(root, value)


def _same_path(first, second):
    # type: (str, str) -> bool
    """Case-normalized absolute path equality (Windows-safe)."""
    return os.path.normcase(os.path.abspath(first)) == os.path.normcase(
        os.path.abspath(second)
    )


def _format_diagnostic(code, context):
    # type: (str, Dict[str, object]) -> str
    """One bounded deterministic line: code plus sorted key=value fields."""
    fields = " ".join(
        "{0}={1}".format(key, context[key]) for key in sorted(context)
    )
    return "{0} {1}".format(code, fields) if fields else code


def _discard_temp(path):
    # type: (Optional[str]) -> None
    """Best-effort temp cleanup; never raises."""
    if path is None:
        return
    try:
        os.unlink(path)
    except OSError:
        pass


def _stage_bytes(directory, data):
    # type: (str, bytes) -> str
    """Stage bytes as a fsynced temp file in ``directory``; return its path.

    The caller owns replacement and cleanup; a staging failure removes the
    temp before propagating so no partial artifacts remain.
    """
    handle_fd, temp_path = tempfile.mkstemp(
        prefix=_TEMP_STAGE_PREFIX, dir=directory
    )
    try:
        with os.fdopen(handle_fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        _discard_temp(temp_path)
        raise
    return temp_path


def _write_record_atomic(path, text):
    # type: (str, str) -> None
    """Atomically publish the promotion record (temp sibling + replace)."""
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    handle_fd, temp_path = tempfile.mkstemp(
        prefix=_TEMP_RECORD_PREFIX, dir=directory
    )
    try:
        with os.fdopen(handle_fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
    except Exception:
        _discard_temp(temp_path)
        raise


# ── Gate 4: active-document classification ───────────────────────────────────


def _classify_policy_document(data):
    # type: (object) -> Optional[str]
    """Classify a parsed policy document: ``"v2"``, ``"legacy-v1"``, or None.

    A LEGACY v1 document is a mapping with a non-empty ``entries`` list of
    mappings, no usable ``schemaVersion`` key, and at least one entry
    carrying a legacy-only field (``class``/``daos``/``signature``/
    ``barrier_required``/``barrier_via``/``private``/``delegate_of``).
    Anything else — including any explicit ``schemaVersion`` other than a
    well-typed ``2`` — is not promotable-over material.
    """
    if not isinstance(data, dict):
        return None
    schema_version = data.get("schemaVersion")
    if schema_version is not None:
        if isinstance(schema_version, bool) or not isinstance(
            schema_version, int
        ):
            return None
        return "v2" if schema_version == V2_SCHEMA_VERSION else None
    entries = data.get("entries")
    if not isinstance(entries, list) or not entries:
        return None
    if not all(isinstance(entry, dict) for entry in entries):
        return None
    for entry in entries:
        for key in entry.keys():
            try:
                if key in LEGACY_REJECTED_ENTRY_KEYS:
                    return "legacy-v1"
            except TypeError:
                # Unhashable YAML mapping keys can never be legacy fields.
                continue
    return None


class _ParseFailed(object):
    """Sentinel: YAML unavailable or malformed (never a valid document)."""


_PARSE_FAILED = _ParseFailed()


def _parse_yaml_document(raw_bytes):
    # type: (bytes) -> object
    """Parse YAML bytes into a document, or the ``_PARSE_FAILED`` sentinel.

    The sentinel (not ``None``) marks failure because an empty-but-valid
    YAML document legitimately parses to ``None``; classification treats
    both as non-promotable-over material.
    """
    try:
        import yaml
    except ImportError:
        return _PARSE_FAILED
    try:
        return yaml.safe_load(raw_bytes.decode("utf-8"))
    except Exception:
        return _PARSE_FAILED


# ── Gate 3: accounting crosswalk ─────────────────────────────────────────────


def _collect_accounting_mutation_keys(document):
    # type: (Dict[str, object]) -> Tuple[Optional[set], Optional[int], Optional[str]]
    """Validate accounting shape and collect ``(keys, input_count, problem)``.

    ``problem`` is ``None`` on success, ``CODE_ACCOUNTING_MALFORMED`` for
    any type/shape violation, or ``CODE_ACCOUNTING_INDEX_INCOMPLETE`` when
    record indices are not exactly the complete set ``0 .. inputCount-1``
    (missing, duplicate, or out-of-range indices).  ``keys`` is the union
    of every record's ``mutationKeys`` strings plus, since GR-08a, every
    reviewed seed row's ``key`` from the optional ``seedRecords`` section
    (absent section -> no change; present-but-malformed -> malformed).
    """
    records = document.get("records")
    if not isinstance(records, list):
        return None, None, CODE_ACCOUNTING_MALFORMED
    input_count = document.get("inputCount")
    if isinstance(input_count, bool) or not isinstance(input_count, int):
        return None, None, CODE_ACCOUNTING_MALFORMED
    union = set()
    seen_indices = set()
    for record in records:
        if not isinstance(record, dict):
            return None, None, CODE_ACCOUNTING_MALFORMED
        index = record.get("index")
        if isinstance(index, bool) or not isinstance(index, int):
            return None, None, CODE_ACCOUNTING_MALFORMED
        if index < 0 or index >= input_count or index in seen_indices:
            return None, None, CODE_ACCOUNTING_INDEX_INCOMPLETE
        seen_indices.add(index)
        keys = record.get("mutationKeys")
        if not isinstance(keys, list):
            return None, None, CODE_ACCOUNTING_MALFORMED
        for key in keys:
            if not isinstance(key, str):
                return None, None, CODE_ACCOUNTING_MALFORMED
            union.add(key)
    if seen_indices != set(range(input_count)):
        return None, None, CODE_ACCOUNTING_INDEX_INCOMPLETE
    seed_records = document.get("seedRecords")
    if seed_records is not None:
        if not isinstance(seed_records, list):
            return None, None, CODE_ACCOUNTING_MALFORMED
        for seed_record in seed_records:
            if not isinstance(seed_record, dict):
                return None, None, CODE_ACCOUNTING_MALFORMED
            seed_key = seed_record.get("key")
            if not isinstance(seed_key, str) or not seed_key:
                return None, None, CODE_ACCOUNTING_MALFORMED
            union.add(seed_key)
    return union, input_count, None


# ── Promotion write phase ────────────────────────────────────────────────────


def _perform_file_promotion(active_path, archive_path, candidate_bytes,
                            active_bytes):
    # type: (str, str, bytes, bytes) -> None
    """Atomically move ``active_bytes`` -> archive and candidate -> active.

    Crash-safe ordering per plan: the ARCHIVE replacement lands first, so a
    crash between the two replacements leaves archive == active and a plain
    re-run heals it through readiness gate 5.  Any failure removes both temp
    files and propagates; neither target is ever left partially written.
    """
    active_dir = os.path.dirname(os.path.abspath(active_path)) or "."
    archive_dir = os.path.dirname(os.path.abspath(archive_path)) or "."
    active_temp = None
    archive_temp = None
    try:
        active_temp = _stage_bytes(active_dir, candidate_bytes)
        archive_temp = _stage_bytes(archive_dir, active_bytes)
        os.replace(archive_temp, archive_path)
        archive_temp = None
        os.replace(active_temp, active_path)
        active_temp = None
    except Exception:
        _discard_temp(active_temp)
        _discard_temp(archive_temp)
        raise


def _verify_post_write(active_path, candidate_path, archive_path,
                       expected_archive_bytes):
    # type: (str, str, str, bytes) -> List[str]
    """Post-write verification; returns a list of controlled field tokens.

    The candidate bytes are READ FRESH FROM DISK here (not reused from the
    staged snapshot) so a candidate tampered between staging and replacement
    is detected as an active-bytes mismatch.
    """
    problems = []
    fresh_active, active_ok = _read_file_bytes(active_path)
    fresh_candidate, candidate_ok = _read_file_bytes(candidate_path)
    if (
        not active_ok
        or not candidate_ok
        or fresh_active != fresh_candidate
    ):
        problems.append(VERIFY_FIELD_ACTIVE_BYTES_MISMATCH)
    active_entries, active_errors = load_policy_v2(active_path)
    if active_errors or active_entries is None:
        problems.append(VERIFY_FIELD_ACTIVE_NOT_V2)
    fresh_archive, archive_ok = _read_file_bytes(archive_path)
    if not archive_ok or fresh_archive != expected_archive_bytes:
        problems.append(VERIFY_FIELD_ARCHIVE_BYTES_MISMATCH)
    return problems


def _existing_previous_v1_sha(record_path):
    # type: (str) -> Optional[str]
    """Lowercase ``previous_v1_sha256`` from an existing record, or None.

    Absent/unparsable records yield None (state visible; the caller proceeds
    with existence-level checks instead of guessing).
    """
    raw, ok = _read_file_bytes(record_path)
    if not ok or raw is None:
        return None
    try:
        document = json.loads(raw.decode("utf-8"))
    except ValueError:
        return None
    if not isinstance(document, dict):
        return None
    recorded = document.get("recordedShas")
    if not isinstance(recorded, dict):
        return None
    value = recorded.get("previous_v1_sha256")
    return value.lower() if isinstance(value, str) else None


# ── CLI adapter ──────────────────────────────────────────────────────────────


def main(argv=None):
    # type: (Optional[List[str]]) -> None
    """CLI adapter; the only place in this module allowed to ``sys.exit``."""
    parser = argparse.ArgumentParser(
        description=(
            "Promote the verified v2 DB ownership signatures candidate over"
            " the legacy v1 active policy (controlled, atomic, gated;"
            " archives the replaced bytes and writes a deterministic"
            " promotion record)."
        )
    )
    parser.add_argument(
        "--root",
        type=str,
        default=".",
        help="Repository root directory (default: current directory).",
    )
    parser.add_argument(
        "--candidate",
        type=str,
        default=DEFAULT_CANDIDATE_RELPATH,
        help=(
            "v2 candidate policy path (default: "
            f"{DEFAULT_CANDIDATE_RELPATH}, resolved relative to --root)."
        ),
    )
    parser.add_argument(
        "--active",
        type=str,
        default=DEFAULT_ACTIVE_RELPATH,
        help=(
            "Active policy path to overwrite (default: "
            f"{DEFAULT_ACTIVE_RELPATH}, resolved relative to --root)."
        ),
    )
    parser.add_argument(
        "--archive",
        type=str,
        default=DEFAULT_ARCHIVE_RELPATH,
        help=(
            "Archive target for the replaced bytes (default: "
            f"{DEFAULT_ARCHIVE_RELPATH}, resolved relative to --root)."
        ),
    )
    parser.add_argument(
        "--accounting",
        type=str,
        required=True,
        help=(
            "GR-05 accounting artifact path (required; resolved relative"
            " to --root)."
        ),
    )
    parser.add_argument(
        "--evidence-report",
        type=str,
        required=True,
        help=(
            "PR-GR-06 v2 evidence report JSON path (required; resolved"
            " relative to --root)."
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
    parser.add_argument(
        "--force-repromote",
        action="store_true",
        help=(
            "Allow promotion over an existing v2 active document (archive"
            " semantics still apply)."
        ),
    )
    args = parser.parse_args(argv)

    root = os.path.abspath(args.root)
    candidate_path = _resolve_against_root(root, args.candidate)
    active_path = _resolve_against_root(root, args.active)
    archive_path = _resolve_against_root(root, args.archive)
    accounting_path = _resolve_against_root(root, args.accounting)
    evidence_path = _resolve_against_root(root, args.evidence_report)
    manifest_path = _resolve_against_root(root, args.manifest)
    record_path = _resolve_against_root(root, PROMOTION_RECORD_RELPATH)

    # Path-collision pre-check: no two roles may share one file.
    labeled_paths = (
        (_TARGET_CANDIDATE, candidate_path),
        (_TARGET_ACTIVE, active_path),
        (_TARGET_ARCHIVE, archive_path),
        (_TARGET_ACCOUNTING, accounting_path),
        (_TARGET_EVIDENCE, evidence_path),
        (_TARGET_MANIFEST, manifest_path),
        (_TARGET_RECORD, record_path),
    )
    for i in range(len(labeled_paths)):
        for j in range(i + 1, len(labeled_paths)):
            if _same_path(labeled_paths[i][1], labeled_paths[j][1]):
                print(
                    _format_diagnostic(
                        CODE_PATH_COLLISION,
                        {
                            "first": labeled_paths[i][0],
                            "second": labeled_paths[j][0],
                        },
                    )
                )
                sys.exit(2)

    # ── Gate 1: candidate loads as v2 with zero errors ──
    candidate_bytes, candidate_ok = _read_file_bytes(candidate_path)
    if not candidate_ok or candidate_bytes is None:
        print(_format_diagnostic(CODE_CANDIDATE_UNREADABLE, {}))
        sys.exit(2)
    entries, policy_errors = load_policy_v2(candidate_path)
    if policy_errors or entries is None:
        for error in policy_errors:
            print(_format_diagnostic(error.code, error.context))
        sys.exit(2)
    candidate_sha = _sha256_bytes(candidate_bytes)

    # ── Gate 2: evidence report is trusted, clean, and fresh ──
    evidence_bytes, evidence_ok = _read_file_bytes(evidence_path)
    if not evidence_ok or evidence_bytes is None:
        print(_format_diagnostic(CODE_EVIDENCE_UNREADABLE, {}))
        sys.exit(2)
    try:
        evidence = json.loads(evidence_bytes.decode("utf-8"))
    except ValueError:
        print(_format_diagnostic(CODE_EVIDENCE_MALFORMED, {}))
        sys.exit(2)
    if (
        not isinstance(evidence, dict)
        or evidence.get("schema") != _shadow_cli.REPORT_SCHEMA_NAME
        or evidence.get("version") != _shadow_cli.REPORT_SCHEMA_VERSION
    ):
        print(_format_diagnostic(CODE_EVIDENCE_SCHEMA_UNSUPPORTED, {}))
        sys.exit(2)
    if evidence.get("trusted") is not True:
        print(_format_diagnostic(CODE_EVIDENCE_UNTRUSTED, {}))
        sys.exit(2)
    evidence_diagnostics = evidence.get("diagnostics")
    if not isinstance(evidence_diagnostics, list):
        print(_format_diagnostic(CODE_EVIDENCE_MALFORMED, {}))
        sys.exit(2)
    if evidence_diagnostics:
        print(
            _format_diagnostic(
                CODE_EVIDENCE_HAS_DIAGNOSTICS,
                {"count": len(evidence_diagnostics)},
            )
        )
        sys.exit(2)
    recorded_policy_sha = evidence.get("policy_sha256")
    if (
        not isinstance(recorded_policy_sha, str)
        or recorded_policy_sha.lower() != candidate_sha
    ):
        print(_format_diagnostic(CODE_EVIDENCE_STALE_POLICY_SHA, {}))
        sys.exit(2)
    evidence_sha = _sha256_bytes(evidence_bytes)

    # ── Gate 3: accounting crosswalk ──
    accounting_bytes, accounting_ok = _read_file_bytes(accounting_path)
    if not accounting_ok or accounting_bytes is None:
        print(_format_diagnostic(CODE_ACCOUNTING_UNREADABLE, {}))
        sys.exit(2)
    try:
        accounting = json.loads(accounting_bytes.decode("utf-8"))
    except ValueError:
        print(_format_diagnostic(CODE_ACCOUNTING_MALFORMED, {}))
        sys.exit(2)
    if not isinstance(accounting, dict):
        print(_format_diagnostic(CODE_ACCOUNTING_MALFORMED, {}))
        sys.exit(2)
    accounting_candidate_sha = accounting.get("candidateSha256")
    if (
        not isinstance(accounting_candidate_sha, str)
        or accounting_candidate_sha.lower() != candidate_sha
    ):
        print(_format_diagnostic(CODE_ACCOUNTING_CANDIDATE_SHA_MISMATCH, {}))
        sys.exit(2)
    key_union, _input_count, accounting_problem = (
        _collect_accounting_mutation_keys(accounting)
    )
    if accounting_problem is not None:
        print(_format_diagnostic(accounting_problem, {}))
        sys.exit(2)
    candidate_keys = {
        entry.mutation_key().canonical_key() for entry in entries
    }
    missing_keys = candidate_keys - key_union
    if missing_keys:
        print(
            _format_diagnostic(
                CODE_CROSSWALK_MISSING_KEY, {"count": len(missing_keys)}
            )
        )
        sys.exit(2)
    accounting_sha = _sha256_bytes(accounting_bytes)

    # ── Gate 4: active currently holds a loadable LEGACY v1 document ──
    active_bytes, active_readable = _read_file_bytes(active_path)
    if not active_readable or active_bytes is None:
        print(_format_diagnostic(CODE_ACTIVE_UNREADABLE, {}))
        sys.exit(2)
    already_promoted = active_bytes == candidate_bytes
    if already_promoted and not args.force_repromote:
        print(_format_diagnostic(CODE_ACTIVE_ALREADY_V2, {}))
        sys.exit(2)
    if already_promoted:
        mode = MODE_IDEMPOTENT
    else:
        parsed_active = _parse_yaml_document(active_bytes)
        kind = (
            None
            if isinstance(parsed_active, _ParseFailed)
            else _classify_policy_document(parsed_active)
        )
        if kind == "v2":
            if not args.force_repromote:
                print(_format_diagnostic(CODE_ACTIVE_ALREADY_V2, {}))
                sys.exit(2)
            mode = MODE_PROMOTE
        elif kind == "legacy-v1":
            _legacy_entries, legacy_errors = legacy_load_ownership_policy(
                active_path
            )
            if legacy_errors:
                for error in legacy_errors:
                    print(_format_diagnostic(error.code, error.context))
                print(_format_diagnostic(CODE_ACTIVE_NOT_LEGACY_V1, {}))
                sys.exit(2)
            mode = MODE_PROMOTE
        else:
            print(_format_diagnostic(CODE_ACTIVE_NOT_LEGACY_V1, {}))
            sys.exit(2)

    # ── Gate 5: archive target safety ──
    archive_exists = os.path.isfile(archive_path)
    if mode == MODE_IDEMPOTENT:
        if not archive_exists:
            print(
                _format_diagnostic(
                    CODE_ARCHIVE_MISMATCH,
                    {"reason": REASON_ARCHIVE_REQUIRED_FOR_IDEMPOTENT},
                )
            )
            sys.exit(2)
        archive_bytes, archive_readable = _read_file_bytes(archive_path)
        if not archive_readable or archive_bytes is None:
            print(
                _format_diagnostic(
                    CODE_ARCHIVE_MISMATCH, {"reason": REASON_ARCHIVE_UNREADABLE}
                )
            )
            sys.exit(2)
        previous_v1_sha = _existing_previous_v1_sha(record_path)
        if previous_v1_sha is not None:
            if _sha256_bytes(archive_bytes) != previous_v1_sha:
                print(
                    _format_diagnostic(
                        CODE_ARCHIVE_MISMATCH,
                        {"reason": REASON_PREVIOUS_V1_SHA_MISMATCH},
                    )
                )
                sys.exit(2)
        expected_archive_bytes = archive_bytes
    else:
        if archive_exists:
            archive_bytes, archive_readable = _read_file_bytes(archive_path)
            if not archive_readable or archive_bytes is None:
                print(
                    _format_diagnostic(
                        CODE_ARCHIVE_MISMATCH,
                        {"reason": REASON_ARCHIVE_UNREADABLE},
                    )
                )
                sys.exit(2)
            if archive_bytes != active_bytes:
                print(
                    _format_diagnostic(
                        CODE_ARCHIVE_MISMATCH,
                        {"reason": REASON_ARCHIVE_CONTENT_MISMATCH},
                    )
                )
                sys.exit(2)
        expected_archive_bytes = active_bytes
    previous_v1_sha_value = (
        _sha256_bytes(expected_archive_bytes)
        if mode == MODE_IDEMPOTENT
        else _sha256_bytes(active_bytes)
    )

    # ── Gate 6: source-root manifest loads + topology verifies ──
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
    tree_sha = production_source_manifest_digest(root)
    if not tree_sha:
        print(_format_diagnostic(CODE_TREE_DIGEST_UNAVAILABLE, {}))
        sys.exit(2)

    # ── All gates passed: perform the promotion ──
    if mode == MODE_PROMOTE:
        try:
            _perform_file_promotion(
                active_path, archive_path, candidate_bytes, active_bytes
            )
        except Exception:
            print(_format_diagnostic(CODE_WRITE_FAILED, {}))
            sys.exit(2)

    # Deterministic promotion record (no timestamps; recorded SHAs only).
    recorded_shas = {
        "accounting_sha256": accounting_sha,
        "active_sha256": candidate_sha,
        "candidate_sha256": candidate_sha,
        "evidence_report_sha256": evidence_sha,
        "previous_v1_sha256": previous_v1_sha_value,
        "tree_sha256": tree_sha,
    }
    record_payload = {
        "schema": RECORD_SCHEMA_NAME,
        "version": RECORD_SCHEMA_VERSION,
        "recordedShas": recorded_shas,
    }
    record_text = json.dumps(record_payload, indent=2, sort_keys=True) + "\n"
    try:
        _write_record_atomic(record_path, record_text)
    except Exception:
        print(_format_diagnostic(CODE_RECORD_WRITE_FAILED, {}))
        sys.exit(2)

    # ── Post-write verification (state visible; never rolled back) ──
    problems = _verify_post_write(
        active_path, candidate_path, archive_path, expected_archive_bytes
    )
    if problems:
        print(
            _format_diagnostic(
                CODE_POST_WRITE_VERIFY_FAILED,
                {"fields": ",".join(sorted(problems))},
            )
        )
        sys.exit(2)

    print("DB_PROMOTE_OK mode=" + mode)
    sys.exit(0)


if __name__ == "__main__":
    main()
