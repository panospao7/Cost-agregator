#!/usr/bin/env python3
"""
capture_db_guard_evidence.py

Diagnostic-only, reproducible evidence capture for the DB access guard at the
caller-pinned exact Git SHA (``--expected-sha``).

This tool is NOT an architecture guard.  It is never registered in the guard
registry and never mutates policy, baseline, config/guards, production Kotlin,
Gradle, workflow, scanner, or ratchet files.  It only *observes* the existing
control plane and records a tamper-evident evidence bundle so a reviewer can
reproduce every observed status from recorded argv and input hashes.

What it does
------------
1. Records a fixed, declared command matrix (preflight + registry validation +
   focused DB Python tests + inventory-only run + normal DB CLI + ratchet +
   full static suite + Gradle wiring).
2. Streams combined stdout/stderr for every command (a single merged pipe,
   read incrementally) into an atomic temporary log file next to the final
   log path; on completion the temp file is ``os.replace``d onto the final
   name.  The production runner reads the live merged pipe incrementally;
   an injected runner's returned output is fed through the identical atomic
   sink in bounded chunks.  The child exit code is preserved.  Expected
   nonzero commands (the DB CLI, the ratchet, Gradle) are observations, never
   capture failures.
3. Every command log is in exactly one of two states:
   * ``COMPLETE`` — the entire combined stdout/stderr was captured within the
     bounded size cap, written atomically, hashed (SHA-256), and marked
     complete (``logComplete=true``); or
   * ``INCOMPLETE`` — the size cap was exceeded (``output-limit-exceeded``),
     the log could not be written (``log-write-failed``), or the finished
     artifact could not be read back/hashed (``log-unreadable``).  An
     incomplete log is still preserved on disk as a flagged artifact but is
     NEVER hashed as valid evidence, and it forces the whole capture to exit
     ``2`` (incomplete evidence).  The capture never exits ``0`` with a
     silently truncated log.
4. Writes every artifact atomically (sibling temp + fsync + os.replace).
5. Executes every command as an argv array with ``shell=False`` — never a
   shell-string.
6. Stores repository-relative paths only; never absolute temp/machine paths.
7. Redacts environment values except an explicit allowlist of version fields.
8. Hashes every listed input and output artifact (SHA-256).
9. Rejects a dirty checkout by default; ``--allow-dirty`` captures but marks
   the evidence bundle untrusted.
10. Emits a deterministic ``semantic-summary.json`` that excludes timestamps,
    durations, machine paths, Gradle cache paths, transient temp names, and
    per-command log volume metadata (see ``build_semantic_summary``), so two
    clean runs at the same SHA compare equal.

The expected SHA is a per-run pin supplied by the caller (``--expected-sha`` /
``expected_sha=``).  It is mandatory, must be exactly 40 lowercase hex
characters, and is never derived silently from HEAD — the caller must state it.
Preflight rejects the capture before any matrix command starts unless the
observed ``git rev-parse HEAD`` equals the pin; after the matrix, HEAD/tree/
status are re-observed and any drift marks the bundle incomplete/untrusted.

Exit codes (the capture tool itself)
------------------------------------
* ``0`` — capture completed and every required artifact is present.
* ``2`` — capture incomplete, corrupt, dirty (without ``--allow-dirty``), a
  required artifact is missing, the ``--expected-sha`` run pin is missing or
  syntactically invalid (not exactly 40 lowercase hex), the observed HEAD does
  not equal the requested pin (rejected before any matrix command starts),
  HEAD/tree/status drifted during the capture (post-matrix re-check), or any
  command log ended INCOMPLETE (``output-limit-exceeded`` /
  ``log-write-failed`` / ``log-unreadable``).  The tool NEVER returns ``1``
  merely because a guarded child command returned ``1`` or ``2``; those are
  stored observations.

The DB gate is expected to remain blocked at the tested SHA.  Capturing that
state truthfully is the deliverable; this tool must not edit policy to change
it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
from contextlib import suppress
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple

# ── Protocol-v2 report constants (mirrors scripts/ci/guard_findings.py) ───────
REPORT_SCHEMA = "cost-aggregator.guard-findings"
REPORT_SCHEMA_VERSION = 2

EVIDENCE_SCHEMA = "db-guard-evidence/v1"
SEMANTIC_SCHEMA = "db-guard-evidence.semantic/v1"

# ── Run pin (PR-GR-00R part A) ────────────────────────────────────────────────
# There is deliberately NO hard-coded target SHA.  The expected commit is a
# per-run pin supplied by the caller (``--expected-sha`` / ``expected_sha=``):
#   * it is mandatory and must be exactly 40 lowercase hex characters;
#   * preflight fails closed BEFORE any matrix command starts unless the
#     observed ``git rev-parse HEAD`` equals the requested pin;
#   * the requested pin, the observed HEAD, and the observed tree SHA
#     (``git rev-parse HEAD^{tree}``) are recorded in git-state / evidence
#     metadata;
#   * after the matrix, HEAD/tree/status are re-observed; any drift marks the
#     bundle incomplete/untrusted (capture exit 2).
# The expected SHA is never derived silently from HEAD (tautological) — the
# caller must state it.

# Maximum number of characters persisted for any single child command's combined
# stdout/stderr.  Bounding prevents unbounded payloads from reaching the evidence
# bundle (privacy: no unbounded user/SQL/secret output in artifacts).
CHILD_OUTPUT_LIMIT = 20000

# ── Command-log capture contract (PR-GR-00R part B) ───────────────────────────
# A command log is in exactly one of two states:
#   * COMPLETE — the entire combined stdout/stderr stream was captured within
#     ``CHILD_OUTPUT_LIMIT``, written atomically, hashed, and marked complete
#     (``log_complete=True``); or
#   * INCOMPLETE — the cap was exceeded, a write failed, or the finished
#     artifact could not be read back/hashed.  The partial artifact is still
#     preserved on disk (flagged via ``log_complete=False`` plus a controlled
#     ``log_failure_code`` from ``LOG_FAILURE_CODES``) but is NEVER hashed as
#     valid evidence: per the existing schema convention its hash is omitted
#     (``log_sha256=""``, the same "no authoritative hash" sentinel used by
#     LAUNCH_FAILED / BUNDLE_PATH_ESCAPE records).  Any incomplete log fails
#     the whole capture closed (exit 2) — a capture must never exit 0 with a
#     silently truncated log.  The former ``LOG_HASH_FAILED`` launch_error
#     sentinel is superseded by the structured ``log-unreadable`` code.
LOG_OUTPUT_LIMIT_EXCEEDED = "output-limit-exceeded"
LOG_WRITE_FAILED = "log-write-failed"
LOG_UNREADABLE = "log-unreadable"
LOG_FAILURE_CODES = frozenset({
    LOG_OUTPUT_LIMIT_EXCEEDED,
    LOG_WRITE_FAILED,
    LOG_UNREADABLE,
})

# Marker appended exactly once when the persistence cap trips so the preserved
# partial artifact is self-describing.  The artifact stays flagged incomplete
# regardless of this marker; the marker never legitimizes it as evidence.
LOG_TRUNCATION_MARKER = "<truncated>"

# Streaming read size (characters) for the real runner's merged stdout+stderr
# pipe.  Output is sanitized line-by-line and written incrementally so raw
# child payloads are never fully materialized in memory.
LOG_STREAM_CHUNK_CHARS = 8192

# Upper bound for one unterminated line buffered during streaming.  A hostile
# child emitting no newlines cannot grow memory past this bound: when reached,
# the buffered fragment is flushed through the normal sanitize path and the
# buffer restarts (a redaction pattern spanning the flush boundary may then be
# missed — an accepted bounded-memory tradeoff, documented honestly here).
MAX_LOG_STREAM_BUFFER = 65536

# Environment keys whose *values* may be recorded.  Everything else is redacted
# to a bounded marker so secrets/paths never reach the evidence bundle.
ENV_VERSION_ALLOWLIST = frozenset({
    "PYTHON_VERSION", "PYTHON3_VERSION", "JAVA_VERSION", "GRADLE_VERSION",
    "OS", "OSTYPE",
})
REDACTED_MARKER = "<redacted>"

# Forbidden files that the GR-00 plan prohibits from changing.  The preservation
# checker fails closed (ok=False) when any of these differ from HEAD.
FORBIDDEN_PRESERVATION_PATHS = (
    "config/baselines/db_access.json",
    "config/guards/db_ownership_policy.yml",
    "config/guards/db_ownership_policy.signatures.candidate.yml",
    "config/guards/db_structural_exceptions.yml",
    "config/guards/db_structural_exceptions_expected_methods.yml",
    "config/guards/db_raw_query_classification.yml",
    "config/guards/production_source_roots.yml",
)

# Git pathspec patterns used to discover tracked DB-guard inputs dynamically.
# Every tracked ``scripts/db_guard/*.py`` and every DB-related guard
# config/policy/structural/raw-query file is enumerated from the working tree so
# the manifest can never silently omit a relevant input.
INPUT_DISCOVERY_PATTERNS = (
    "scripts/db_guard/*.py",
    "config/guards/db_*.yml",
    "config/guards/production_source_roots.yml",
    "config/baselines/db_access.json",
    "scripts/verify_db_access_boundaries.py",
    "scripts/ci/guard_ratchet.py",
    "scripts/ci/guard_registry.py",
    "scripts/ci/run_static_guard_suite.py",
    "scripts/ci/guard_findings.py",
    "app/build.gradle.kts",
    ".github/workflows/ci.yml",
    "settings.gradle.kts",
)

# DB-related guard config/policy/structural/raw-query files that the plan
# requires to be present.  They are always included in the manifest (even when
# not returned by ``git ls-files``) so a missing required input is still flagged
# by the missing-input diagnostic.
REQUIRED_INPUT_CANDIDATES = (
    "config/baselines/db_access.json",
    "config/guards/db_ownership_policy.yml",
    "config/guards/db_ownership_policy.signatures.candidate.yml",
    "config/guards/db_structural_exceptions.yml",
    "config/guards/db_structural_exceptions_expected_methods.yml",
    "config/guards/db_raw_query_classification.yml",
    "config/guards/production_source_roots.yml",
)

# ── Finite bounds for every persisted list/string/count/report-derived field ──
# Persisting unbounded content (diagnostic arrays, manifest entries, warnings,
# command matrix, finding counts, argv tokens, summaries) is rejected so a hostile
# or malformed child report / input cannot inflate the evidence bundle.  Every
# bound fails closed: the capture returns exit ``2`` and records a controlled
# ``overflow:*`` diagnostic rather than persisting unbounded data.
MAX_DIAGNOSTIC_CODES = 256
MAX_MANIFEST_ENTRIES = 2000
MAX_WARNINGS = 200
MAX_MATRIX_COMMANDS = 64
MAX_FINDING_COUNT = 100000
MAX_ARGV_TOKENS = 256
MAX_ARGV_TOKEN_LEN = 4096
MAX_ARGV_TOTAL_LEN = 65536
MAX_SUMMARY_CHARS = 20000

# Controlled overflow diagnostic markers (bounded, no raw content).
OVERFLOW_DIAGNOSTIC_CODES = "OVERFLOW_DIAGNOSTIC_CODES"
OVERFLOW_FINDING_COUNT = "OVERFLOW_FINDING_COUNT"
OVERFLOW_WARNINGS = "OVERFLOW_WARNINGS"
OVERFLOW_MANIFEST = "OVERFLOW_MANIFEST"
OVERFLOW_MATRIX = "OVERFLOW_MATRIX"
OVERFLOW_ARGV = "OVERFLOW_ARGV"

# Controlled parser_error codes for structurally malformed v2 report containers.
# A report whose ``findings`` / ``diagnostics`` / ``statistics`` container (or any
# nested entry) has the wrong JSON type is treated as unparseable (fail closed)
# rather than being partially accepted or raising on a hostile shape.
MALFORMED_FINDINGS = "MALFORMED_FINDINGS"
MALFORMED_DIAGNOSTICS = "MALFORMED_DIAGNOSTICS"
MALFORMED_DIAGNOSTIC_ENTRY = "MALFORMED_DIAGNOSTIC_ENTRY"
MALFORMED_STATISTICS = "MALFORMED_STATISTICS"
MALFORMED_FINDING_ENTRY = "MALFORMED_FINDING_ENTRY"

# Bound for the per-finding shape check: each finding must be a JSON object with
# at most this many keys, so a scalar/list finding or a hostile wide-object
# finding fails the report closed instead of being accepted.
MAX_FINDING_KEYS = 64

# Bounds for custom CommandSpec fields.  A custom (injected) command matrix is
# validated *before any runner call* so a hostile or malformed matrix never
# executes or persists unbounded data.  Every bound fails closed with a controlled
# ``OVERFLOW_*`` marker rather than persisting unbounded content.
MAX_COMMAND_ID_LEN = 128
MAX_PATH_LEN = 1024
MAX_REQUIRED_ARTIFACTS = 64
MAX_ARTIFACT_KINDS = 64

# Controlled overflow markers for custom CommandSpec field bounds.
OVERFLOW_COMMAND_ID = "OVERFLOW_COMMAND_ID"
OVERFLOW_PATH = "OVERFLOW_PATH"
OVERFLOW_ARTIFACT_KINDS = "OVERFLOW_ARTIFACT_KINDS"

# Bound for the collected validation-violation list (fail closed with a controlled
# overflow marker rather than persisting an unbounded violation set).
MAX_VIOLATIONS = 512
OVERFLOW_VIOLATIONS = "OVERFLOW_VIOLATIONS"

# Bound for the collected required-artifact hash set (fail closed with a controlled
# overflow marker rather than materializing unbounded hashes).  The required-artifact
# hash loop stops once this many hashes have been collected and fails closed.
MAX_REQUIRED_ARTIFACT_HASHES = 256
OVERFLOW_REQUIRED_ARTIFACT_HASHES = "OVERFLOW_REQUIRED_ARTIFACT_HASHES"

# Controlled diagnostic for a top-level output (git-state / environment / manifest /
# evidence / summary / semantic) whose hash/read failed (symlink, non-regular file,
# read error, or replaced/changed mid-read).  The capture fails closed (exit 2) and
# never substitutes an empty hash for the failed output.
OUTPUT_HASH_FAILED = "output-hash-failed"

# Controlled diagnostic for a required artifact whose hash could not be computed
# (symlink root already rejected separately; this covers read errors, a file
# replaced by a symlink/non-regular file at read time, or a TOCTOU change).
ARTIFACT_HASH_FAILED = "artifact-hash-failed"

# Controlled diagnostic for a matrix entry that is not a ``CommandSpec`` instance
# or carries a malformed nested field (argv / required_artifacts / artifact_kinds).
INVALID_MATRIX_SPEC = "invalid-matrix-spec"

# ── Closed warning-code allowlist (GR-00 strict review) ───────────────────────
# Every infrastructure warning must carry a code drawn from this explicit, closed
# set.  Anything else (an unknown code, an arbitrary payload, or a non-string) is
# reduced to ``REDACTED_MARKER`` by ``_sanitize_warning`` so untrusted content can
# never reach the evidence bundle as raw text.  ``make_warning`` is the only
# sanctioned constructor and refuses to emit a code outside this set.
WARNING_CODE_ALLOWLIST = frozenset({
    # Overflow markers (controlled, bounded).
    OVERFLOW_DIAGNOSTIC_CODES,
    OVERFLOW_FINDING_COUNT,
    OVERFLOW_WARNINGS,
    OVERFLOW_MANIFEST,
    OVERFLOW_MATRIX,
    OVERFLOW_ARGV,
    OVERFLOW_COMMAND_ID,
    OVERFLOW_PATH,
    OVERFLOW_ARTIFACT_KINDS,
    OVERFLOW_VIOLATIONS,
    OVERFLOW_REQUIRED_ARTIFACT_HASHES,
    OUTPUT_HASH_FAILED,
    # Validation / containment failures.
    INVALID_MATRIX_SPEC,
    "invalid-matrix-argv",
    "invalid-bundle-path",
    "missing-artifact-kind",
    # Required-input / artifact / report failures.
    "missing-required-input",
    "missing-blob-id",
    "missing-required-artifact",
    "invalid-required-artifact-type",
    "invalid-required-report",
    "symlink-artifact",
    ARTIFACT_HASH_FAILED,
    # Preflight / identity failures.
    "preflight-failed",
    "wrong-sha",
    "git-meta-failed",
    # Run-pin failures (PR-GR-00R part A): missing/invalid caller-stated pin and
    # post-matrix identity drift (surface token: head/tree/status/unverifiable).
    "missing-expected-sha",
    "invalid-expected-sha",
    "post-capture-drift",
    # Infrastructure observations.
    "missing-test-file",
    # Command-log capture failures (PR-GR-00R part B): a command log ended
    # INCOMPLETE (payload is the sanitized command id + controlled
    # ``LOG_FAILURE_CODES`` constant).
    "incomplete-command-log",
})


# ── Small value types ─────────────────────────────────────────────────────────
@dataclass
class RunOutcome:
    """Result of running one command via an injectable runner.

    Preflight, manifest discovery, and the preservation check consume this
    shape directly.  Matrix commands in production are executed through
    ``streaming_subprocess_runner`` (the live merged child pipe streamed into
    the atomic log sink); an *injected* runner returns this shape and its
    text is fed through the identical sink in ``LOG_STREAM_CHUNK_CHARS``
    chunks, so both paths share one persistence contract.
    """
    returncode: int
    combined: str = ""


@dataclass
class CommandSpec:
    """A single declared command in the capture matrix."""
    id: str
    log_name: str
    argv: Sequence[str]
    report_path: Optional[str] = None
    required_artifacts: Tuple[str, ...] = ()
    # Explicit artifact-kind metadata: ``(rel, "file"|"dir")`` for every entry in
    # ``required_artifacts``.  Dot-in-basename inference is intentionally removed;
    # a required artifact whose kind is absent or not ``"file"``/``"dir"`` fails
    # closed with ``missing-artifact-kind:<id>:<rel>``.
    artifact_kinds: Tuple[Tuple[str, str], ...] = ()


@dataclass
class CommandResult:
    """Captured evidence for one command."""
    id: str
    argv: List[str]
    cwd: str
    start_utc: str
    end_utc: str
    elapsed_ms: int
    exit_code: Optional[int]
    log_path: str
    log_sha256: str
    report_path: Optional[str]
    report_sha256: Optional[str]
    report_schema_version: Optional[int]
    report_trusted: Optional[bool]
    report_diagnostic_codes: List[str]
    report_finding_count: Optional[int]
    parser_error: Optional[str]
    launch_error: Optional[str]
    # Command-log capture state (PR-GR-00R part B).  ``log_bytes`` is the size
    # in bytes of the published log artifact (0 when none was published).
    # ``log_complete`` is True iff the ENTIRE combined stream was captured
    # within ``CHILD_OUTPUT_LIMIT``, published atomically, and read back
    # successfully.  ``log_failure_code`` is a bounded controlled constant from
    # ``LOG_FAILURE_CODES`` (None iff the log is complete; a pre-execution
    # bundle-path escape record is incomplete with no code — ``launch_error``
    # carries ``BUNDLE_PATH_ESCAPE`` instead).
    log_bytes: int = 0
    log_complete: bool = False
    log_failure_code: Optional[str] = None


# ── Path helpers (repository-relative, POSIX separators) ──────────────────────
def _posix_rel(path: str, root: str) -> str:
    """Return ``path`` relative to ``root`` using POSIX separators."""
    rel = os.path.relpath(path, root)
    return rel.replace(os.sep, "/")


def _abs(rel_to_bundle: str, out_dir: str) -> str:
    """Resolve a bundle-relative path to an absolute filesystem path."""
    return os.path.join(out_dir, rel_to_bundle)


def _is_within(child: str, parent: str) -> bool:
    """True iff ``child`` is the same as or nested under ``parent``.

    Both arguments are normalized to absolute paths. Used to reject output
    bundles that escape the repository root (traversal / outside-root).
    """
    child = os.path.abspath(child)
    parent = os.path.abspath(parent)
    return child == parent or child.startswith(parent + os.sep)


def _is_within_realpath(child: str, parent: str) -> bool:
    """Containment check using resolved real paths.

    Rejects symlink escapes: if ``child`` is a symlink whose target lives outside
    ``parent``, the resolved path is no longer contained and this returns False.
    """
    child = os.path.realpath(child)
    parent = os.path.realpath(parent)
    return child == parent or child.startswith(parent + os.sep)


def _bundle_path_contained(rel_to_bundle: str, out_dir: str) -> bool:
    """True iff a bundle-relative path resolves *inside* ``out_dir``.

    Uses ``os.path.realpath`` so a ``..`` traversal that escapes the bundle and a
    symlink whose target lies outside the bundle are both rejected.  The capture
    tool must never read from or write to a path that fails this check.
    """
    abs_path = os.path.realpath(os.path.join(out_dir, rel_to_bundle))
    return _is_within_realpath(abs_path, out_dir)


def _is_safe_repo_relative_path(rel: str, root: str) -> bool:
    """True iff ``rel`` is a safe repository-relative path contained under root.

    Rejects non-strings, empties, backslash separators, UNC shares (``//`` or
    ``\\\\``), Windows drive prefixes, POSIX absolute paths, ``..`` traversal
    segments, and any resolved real path that escapes ``root`` (including via a
    symlink).  Used to validate custom ``input_candidates`` *before* any
    filesystem/Git access so an external file is never read or hashed.
    """
    if not isinstance(rel, str) or not rel:
        return False
    if "\\" in rel:
        return False
    if rel.startswith("//") or rel.startswith("\\\\"):
        return False
    if _DRIVE_LETTER_RE.search(rel):
        return False
    if rel.startswith("/"):
        return False
    norm = rel.replace("\\", "/")
    if ".." in norm.split("/"):
        return False
    root_real = os.path.realpath(root)
    abs_candidate = os.path.realpath(os.path.join(root, rel))
    return _is_within_realpath(abs_candidate, root_real)


def _is_safe_git_filename(name: str) -> bool:
    """True iff ``name`` is a safe repository-relative git filename.

    Git status/diff filenames are repository-relative by construction, but a
    hostile or malformed value (backslash, UNC, drive prefix, absolute path, or
    ``..`` traversal) must never be persisted verbatim.  This is a form check
    only (no filesystem access), so it is safe to call on untrusted output.
    """
    if not isinstance(name, str) or not name:
        return False
    if "\\" in name:
        return False
    if name.startswith("//") or name.startswith("\\\\"):
        return False
    if _DRIVE_LETTER_RE.search(name):
        return False
    if name.startswith("/"):
        return False
    norm = name.replace("\\", "/")
    if ".." in norm.split("/"):
        return False
    return True


def _is_valid_sha40(value: str) -> bool:
    """True iff ``value`` is a 40-character lowercase hexadecimal Git SHA-1."""
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None


# Absolute-path / UNC / backslash redaction for persisted child output and
# preflight records.  Windows drive form requires a single drive letter not
# preceded by another letter (so ``https://`` is not redacted); POSIX form
# requires at least two path segments so bare ``/api`` is left alone but
# ``/home/user/file`` is not.  UNC shares (``//host/share`` and the backslash
# form ``\\host\share``) and any backslash-separated path segment are also
# redacted so hostile machine/path content never reaches the bundle.
_PATH_SANITIZE_RE = re.compile(
    r"(?<![A-Za-z])[A-Za-z]:[\\/][^ \n\r\t]*"      # windows drive prefix
    r"|\\\\{1,2}[^ \n\r\t]*"                         # backslash UNC / path
    r"|//[^\s/]+/[^\s]*"                             # forward UNC share
    r"|(?<![\w/])/(?:[\w.\-]+/)+[\w.\-]+"            # posix absolute (2+ segs)
)

# Windows drive-letter prefix detector (e.g. ``C:\`` or ``C:/``).  Used by
# ``validate_command_matrix`` to reject absolute/outside argv tokens on either
# platform.  Must be defined at module scope so the capture tool (not just the
# test suite) can reference it.
_DRIVE_LETTER_RE = re.compile(r"[A-Za-z]:[\\/]")

# Controlled diagnostic-code pattern. Anything else (paths, messages, secrets)
# is replaced by the redaction marker so untrusted report content cannot leak
# raw exception text, file paths, or payloads into the evidence bundle.
_DIAGNOSTIC_CODE_RE = re.compile(r"[A-Z][A-Z0-9_]*")


def _redact_absolute_paths(text: str) -> str:
    """Replace absolute filesystem paths in ``text`` with a bounded marker."""
    if not text:
        return text
    return _PATH_SANITIZE_RE.sub("<redacted-path>", text)


def _sanitize_diagnostic_code(code: str) -> str:
    """Return ``code`` if it is a controlled constant; else a redaction marker."""
    if isinstance(code, str) and _DIAGNOSTIC_CODE_RE.fullmatch(code):
        return code
    return REDACTED_MARKER


def _bounded(text: str, limit: int = 2000) -> str:
    """Redact absolute paths and truncate to a bounded length for records."""
    redacted = _redact_absolute_paths(text or "")
    if len(redacted) > limit:
        return redacted[:limit] + "<truncated>"
    return redacted


def _sanitize_argv_token(token: str) -> str:
    """Sanitize a single argv token before persistence.

    Custom command-matrix argv tokens must never be persisted verbatim.  Secrets
    (``password=…``, ``api_key=…``, …) and absolute/UNC/backslash paths are
    redacted, and the token is length-bounded so an oversized or hostile token
    cannot reach ``evidence.json`` / ``semantic-summary.json``.  The default
    (trusted) matrix contains no such tokens, so sanitization is a no-op there.
    """
    if not isinstance(token, str):
        return "<non-string>"
    redacted = _redact_secrets(token)
    redacted = _redact_absolute_paths(redacted)
    if len(redacted) > MAX_ARGV_TOKEN_LEN:
        return redacted[:MAX_ARGV_TOKEN_LEN] + "<argv-truncated>"
    return redacted


# ── Hostile path / warning sanitization (GR-00 hardening) ───────────────────────
# Rejects path forms that must never be persisted or executed: backslash
# separators, UNC shares (``//host/share``), Windows drive prefixes, POSIX
# absolute paths, and ``..`` traversal segments.  Used to harden both custom
# command-matrix argv tokens and derived bundle paths so a hostile custom path is
# rejected and never leaks raw machine/path content into the evidence bundle.
_UNC_RE = re.compile(r"/{2,}[^ \n\r\t]*")

# Explicit safe argv schema/allowlist for custom command matrices.  Every custom
# argv token must match this allowlist of characters; anything else (spaces,
# quotes, shell metacharacters, backticks, ``$``, ``;``, ``|``, ``&``, ``<``,
# ``>``, parentheses, newlines, NUL, etc.) is an arbitrary payload token and is
# rejected outright so a hostile custom matrix is never executed or persisted.
# The default (trusted) matrix uses only repository-relative tokens that all match.
_SAFE_ARGV_TOKEN_RE = re.compile(r"^[A-Za-z0-9_./:=-]+$")

# Controlled warning-code pattern.  Every infrastructure warning must begin with a
# controlled code constant (letters/digits/``-``/``_``); an untrusted or malformed
# code means the whole warning is untrusted and is reduced to a bounded marker
# rather than persisted verbatim.
_WARNING_CODE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_-]*$")

# Control-character stripper for warning payloads: NUL / newline / tab / DEL and
# other C0/C1 controls are removed so a warning can never smuggle content that
# breaks JSON or log parsing or escapes its single-line bounded envelope.
_CONTROL_CHAR_RE = re.compile(r"[\x00-\x1f\x7f]")


def _is_hostile_path_value(value: str) -> bool:
    """True iff ``value`` is a hostile path form that must never be persisted."""
    if not isinstance(value, str) or not value:
        return False
    if "\\" in value:
        return True
    if value.startswith("//") or value.startswith("\\\\"):
        return True
    if _DRIVE_LETTER_RE.search(value):
        return True
    if value.startswith("/"):
        return True
    norm = value.replace("\\", "/")
    if ".." in norm.split("/"):
        return True
    return False


def _sanitize_warning_token(token: str) -> str:
    """Reduce a hostile argv/bundle token to a bounded marker for warnings.

    Secrets, absolute/UNC/backslash paths, and other hostile forms are redacted so
    no raw machine/path/secret content leaks into an infrastructure warning.
    """
    if not isinstance(token, str) or not token:
        return "<redacted>"
    token = _redact_secrets(token)
    if _is_hostile_path_value(token):
        return "<redacted-path>"
    return _redact_absolute_paths(token)


def _sanitize_warning(text: str) -> str:
    """Bounded sanitization of an infrastructure-warning payload.

    The leading warning code must belong to the closed ``WARNING_CODE_ALLOWLIST``;
    an untrusted or malformed code means the whole warning is untrusted and is
    reduced to ``REDACTED_MARKER`` rather than persisted verbatim.  Secret
    assignments, hostile path forms (backslash, UNC share, absolute path,
    traversal), and control characters in the payload are redacted/stripped so a
    warning never leaks raw machine/path/secret content into the evidence bundle.
    The result is length-bounded so an unbounded warning payload cannot reach the
    evidence.  Arbitrary untrusted payload text is never preserved: only the
    controlled code, controlled markers (``<redacted-*>``), and safe
    repository-relative tokens survive sanitization.
    """
    if not isinstance(text, str):
        return REDACTED_MARKER
    # Redact secret assignments (``key=value``) and hostile path forms first so no
    # raw secret/machine/path content can reach the persisted warning.
    cleaned = _redact_secrets(text)
    cleaned = cleaned.replace("\\", "/")
    cleaned = _UNC_RE.sub("<redacted-path>", cleaned)
    cleaned = _redact_absolute_paths(cleaned)
    # Strip control characters (NUL, newline, tab, DEL) that could break JSON or log
    # parsing or smuggle content; the warning must be a single bounded line of
    # controlled content.
    cleaned = _CONTROL_CHAR_RE.sub("", cleaned)
    # The leading code must be a member of the closed allowlist.  An untrusted or
    # malformed code means the entire warning is untrusted and is reduced to a
    # bounded marker.
    code = cleaned.split(":", 1)[0]
    if code not in WARNING_CODE_ALLOWLIST:
        return REDACTED_MARKER
    if len(cleaned) > 256:
        cleaned = cleaned[:256] + "<truncated>"
    return cleaned


def make_warning(code: str, *parts: Any) -> str:
    """Structured, closed warning constructor.

    The only sanctioned way to build an infrastructure warning.  The ``code`` must
    be a member of the closed ``WARNING_CODE_ALLOWLIST``; an unknown code collapses
    to ``REDACTED_MARKER`` so untrusted content can never be persisted as raw
    text.  Every payload part is reduced via ``_sanitize_warning_token`` (secrets
    and hostile path forms become controlled markers) before being joined with
    ``:`` separators, and the whole result is length-bounded.  The output is
    idempotent under ``_sanitize_warning``.
    """
    if code not in WARNING_CODE_ALLOWLIST:
        return REDACTED_MARKER
    sanitized = []
    for part in parts:
        if not isinstance(part, str):
            part = str(part)
        sanitized.append(_sanitize_warning_token(part))
    joined = code if not sanitized else (code + ":" + ":".join(sanitized))
    if len(joined) > 256:
        joined = joined[:256] + "<truncated>"
    return joined


def _sanitize_command_id(value: Any) -> str:
    """Sanitize a ``CommandSpec.id`` before it is persisted.

    Warning payloads built from a command id are reduced by ``make_warning``, but
    the ``CommandResult.id`` field itself was previously persisted verbatim, so a
    short-but-hostile custom id (control characters, secret assignments, absolute
    path fragments) could reach ``evidence.json`` / ``semantic-summary.json`` /
    ``summary.md``.  A non-string or empty id collapses to ``<non-string>``,
    secret assignments and absolute/UNC/backslash path forms are redacted,
    control characters are stripped, and the result is length-bounded by
    ``MAX_COMMAND_ID_LEN`` so no untrusted content survives persistence.
    """
    if not isinstance(value, str) or not value:
        return "<non-string>"
    cleaned = _redact_secrets(value)
    cleaned = _redact_absolute_paths(cleaned)
    cleaned = _CONTROL_CHAR_RE.sub("", cleaned)
    if len(cleaned) > MAX_COMMAND_ID_LEN:
        cleaned = cleaned[:MAX_COMMAND_ID_LEN] + "<truncated>"
    return cleaned


# ── Git status / diff filename normalization (GR-00) ───────────────────────────
# Git status/diff filenames must be normalized and sanitized before persistence
# so a hostile or malformed filename (backslash, UNC, absolute path, traversal)
# never reaches ``git-state.json``.  Each line is parsed for one or two filenames
# (renames use ``a -> b``); unsafe names are reduced to a bounded
# ``<redacted-path>`` marker.  Output is length-bounded.
def _extract_git_filenames(line: str) -> List[str]:
    """Return the filename(s) referenced by a git status/diff output line.

    Porcelain status lines (``XY <path>``) carry two status columns followed by a
    single space separator at column 2.  The status columns are parsed
    **positionally, before any trimming**, so they are never leaked into the
    extracted filename and so every form is handled correctly: untracked
    (``?? path``), working-tree modified (`` M path``), staged added (``A  path``),
    and renames/copies (``R  a -> b`` / ``C  a -> b``).  ``--name-only`` diff lines
    (no status columns) are returned verbatim.  Rename/copy annotations (``a -> b``)
    yield both names.  Surrounding double quotes (git's quoting of special
    characters) are stripped.
    """
    # Normalize trailing line endings only; do NOT strip leading whitespace, or the
    # positional status columns would be lost.
    line = line.rstrip("\r\n")
    # A porcelain status line has exactly two status columns (0-1) and a single space
    # separator at column 2.  Detect it by the column-2 space so staged/rename forms
    # are parsed correctly, while a bare ``--name-only`` filename (no status columns)
    # is returned as-is.
    if len(line) >= 3 and line[2] == ' ':
        rest = line[3:].strip()
        if " -> " in rest:
            a, b = rest.split(" -> ", 1)
            return [a.strip().strip('"'), b.strip().strip('"')]
        return [rest.strip().strip('"')]
    # ``--name-only`` diff line (no status columns): the whole line is the filename.
    return [line.strip().strip('"')]


def _sanitize_git_filenames(text: str, is_status: bool = False) -> str:
    """Normalize a git status/diff filename listing to safe repo-relative names.

    Each line is parsed for filename(s); a filename that is a hostile path form
    (backslash, UNC, absolute, traversal) or otherwise unsafe is reduced to a
    bounded ``<redacted-path>`` marker so raw machine/path content never reaches
    ``git-state.json``.  Secret assignments inside a filename are also redacted.
    The result is length-bounded by ``CHILD_OUTPUT_LIMIT``.
    """
    if not isinstance(text, str):
        return ""
    out_lines: List[str] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        names = _extract_git_filenames(line) if is_status else [line.strip().strip('"')]
        safe = []
        for name in names:
            if _is_safe_git_filename(name):
                safe.append(_redact_secrets(name))
            else:
                safe.append("<redacted-path>")
        out_lines.append(" ".join(safe) if safe else "<redacted-path>")
    result = "\n".join(out_lines)
    if len(result) > CHILD_OUTPUT_LIMIT:
        result = result[:CHILD_OUTPUT_LIMIT] + "<truncated>"
    return result


def _sanitize_changed_filename(name: str) -> str:
    """Sanitize a changed git filename before persisting it in the preservation result.

    Changed filenames come from git status/diff/untracked output and must never be
    persisted verbatim if they are a hostile path form (backslash, UNC share,
    Windows drive prefix, POSIX absolute path, or ``..`` traversal).  A safe name
    is kept (with secret assignments redacted); a hostile name is reduced to the
    bounded ``<redacted-path>`` marker so no raw machine/path content leaks into
    the evidence bundle.
    """
    if _is_safe_git_filename(name):
        return _redact_secrets(name)
    return "<redacted-path>"


# ── Comprehensive child-output sanitization ────────────────────────────────────
# Persisted child output must never contain raw exception text, secrets, SQL
# errors, user payloads, absolute paths, or unbounded content.  Each redactor
# replaces offending content with a bounded controlled marker; the original
# (untrusted) text is never written to the evidence bundle.
_SECRET_RE = re.compile(
    r"(?i)\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|"
    r"access[_-]?key|private[_-]?key|client[_-]?secret)\b\s*[=:]\s*\S+"
)
_SQL_ERR_RE = re.compile(
    r"(?i)(sqlite\w*|sql)\b[^\n]*(error|exception|syntax|constraint|near\s+\")"
)
_EXC_LINE_RE = re.compile(r"(?i)^\s*(\S*(error|exception)\s*[:\s]|traceback\b)")
# Uncommon Python exception class names that do not contain ``error`` /
# ``exception`` (e.g. ``KeyboardInterrupt``, ``StopIteration``) must still be
# redacted so raw exception text never reaches the bundle.  Matches a class-name
# token ending in a known exception suffix, plus ``SystemExit`` / ``GeneratorExit``
# and ``traceback`` lines.  Deliberately excludes bare ``Exit`` / ``Warning`` so
# ordinary prose such as ``Exit code 0`` is not redacted.
_EXC_NAME_RE = re.compile(
    r"(?i)^\s*([A-Za-z_][A-Za-z0-9_]*(?:Error|Exception|Interrupt|Iteration)\b"
    r"|SystemExit\b|GeneratorExit\b|traceback\b)"
)
_TRACEBACK_FRAME_RE = re.compile(r'(?i)^\s*file\s+"')


def _redact_secrets(text: str) -> str:
    """Replace secret assignments (``key=value``) with a bounded marker."""
    return _SECRET_RE.sub("<redacted-secret>", text)


def _redact_sql_errors(text: str) -> str:
    """Replace SQL error / exception signatures with a bounded marker."""
    return _SQL_ERR_RE.sub("<redacted-sql>", text)


def _redact_exceptions(text: str) -> str:
    """Replace exception traceback lines with a bounded marker.

    Covers ordinary ``error`` / ``exception`` lines, ``traceback`` frames, and
    uncommon exception class names (``KeyboardInterrupt``, ``StopIteration``,
    ``SystemExit``, ``GeneratorExit``, and any ``*Error`` / ``*Exception`` /
    ``*Interrupt`` / ``*Iteration`` class) so raw exception text never reaches
    the bundle.
    """
    out: List[str] = []
    for line in text.splitlines():
        if (_EXC_LINE_RE.search(line) or _TRACEBACK_FRAME_RE.search(line)
                or _EXC_NAME_RE.search(line)):
            out.append("<redacted-exception>")
        else:
            out.append(line)
    return "\n".join(out)


def _sanitize_child_output(text: str, limit: int = CHILD_OUTPUT_LIMIT) -> str:
    """Comprehensively sanitize a child command's combined output before persist.

    Redacts absolute paths, secrets, SQL errors, and exception tracebacks, then
    bounds the total length.  Child exit codes are never touched here (they are
    recorded separately and must not be swallowed).

    Scope note (PR-GR-00R part B): this whole-text helper now serves only the
    bounded *preflight* records (git/version metadata).  Matrix-command logs
    are persisted through ``_CommandLogSink`` / ``_sanitize_log_line``, which
    apply the same redactors incrementally and enforce the cap as a fail-closed
    INCOMPLETE state instead of a silent truncation.
    """
    if not text:
        return text
    text = _redact_absolute_paths(text)
    text = _redact_secrets(text)
    text = _redact_sql_errors(text)
    text = _redact_exceptions(text)
    if len(text) > limit:
        text = text[:limit] + "<truncated>"
    return text


def _sanitize_log_line(line: str) -> str:
    """Sanitize one streamed log line with the same redactors as
    ``_sanitize_child_output`` (absolute paths, secrets, SQL errors, exception
    lines).

    Every pattern is line-local (none can span a newline), so per-line
    application matches whole-text sanitization.  Length bounding is
    deliberately NOT applied here: the streaming sink enforces
    ``CHILD_OUTPUT_LIMIT`` across the whole log and turns exceedance into a
    controlled INCOMPLETE state instead of a silent truncation.
    """
    line = _redact_absolute_paths(line)
    line = _redact_secrets(line)
    line = _redact_sql_errors(line)
    if (_EXC_LINE_RE.search(line) or _TRACEBACK_FRAME_RE.search(line)
            or _EXC_NAME_RE.search(line)):
        return "<redacted-exception>"
    return line


def _sanitize_preflight_log(text: str) -> str:
    """Persist only 40-hex commit SHAs from ``git log --oneline`` output.

    Commit-message text can carry secrets, paths, or PII, so it is never
    persisted.  Only the leading 40-hex SHA of each line is kept (already captured
    authoritatively via ``git rev-parse HEAD`` / ``HEAD^{tree}``), preserving
    reproducibility without leaking message content.  Output is bounded by the
    ``-20`` preflight limit and the SHA-only extraction.
    """
    shas: List[str] = []
    for line in (text or "").splitlines():
        line = line.strip()
        if not line:
            continue
        sha = line.split(None, 1)[0]
        if _is_valid_sha40(sha):
            shas.append(sha)
    return "\n".join(shas)


# ── Atomic writes (sibling temp + fsync + os.replace) ─────────────────────────
def atomic_write_text(path: str, text: str) -> None:
    """Write ``text`` atomically; never echo raw paths on failure."""
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    name = os.path.basename(path) or "artifact"
    fd, tmp = tempfile.mkstemp(prefix=f".{name}.", suffix=".tmp", dir=parent or ".", text=True)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        with suppress(OSError):
            os.chmod(tmp, 0o644)
        os.replace(tmp, path)
        tmp = None
    finally:
        if tmp is not None:
            with suppress(OSError):
                os.unlink(tmp)


def atomic_write_json(path: str, obj: Any) -> None:
    """Serialize ``obj`` as canonical JSON and write atomically."""
    text = json.dumps(obj, indent=2, sort_keys=False, ensure_ascii=False) + "\n"
    atomic_write_text(path, text)


# ── Hashing ───────────────────────────────────────────────────────────────────
def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ── Race-safe file reads / hashing (GR-00 strict review) ───────────────────────
# Inputs, reports, and required artifacts are read/hashed through these helpers so
# a symlink, a non-regular file, or a file replaced/changed during the read is
# rejected rather than followed or hashed inconsistently.
#
# Actual guarantee (documented honestly):
#   * We ``os.lstat`` first and reject anything that is not a regular file (this
#     catches symlinks, directories, devices, …) before opening.
#   * Where the platform exposes ``O_NOFOLLOW`` (Linux/macOS), the file is opened
#     with that flag so a symlink is refused at ``open`` time (``ELOOP``), not
#     merely at the earlier ``lstat``.
#   * We ``os.fstat`` the opened descriptor and confirm it is the same regular
#     file we ``lstat``'d (matching ``st_dev``/``st_ino``) and record its size.
#   * We read in chunks, then ``os.fstat`` the descriptor again and reject the
#     result if the identity or size changed mid-read (TOCTOU / replacement).
#   * On Windows ``O_NOFOLLOW`` is generally unavailable, so the ``lstat`` +
#     descriptor ``fstat`` identity/size checks are the enforced containment; this
#     is best-effort TOCTOU mitigation, not a hard guarantee against a privileged
#     actor able to swap inodes between our checks.  The bundle lives under a
#     git-ignored directory produced by the capture tool itself, so the realistic
#     threat is an unprivileged swap, which the identity/size re-checks defeat.
# Any rejection returns ``None`` so the caller can fail the capture closed.
def _race_safe_read_bytes(path: str) -> Optional[bytes]:
    """Read a regular file's bytes, rejecting symlinks / non-regular / changed files.

    Returns ``None`` when the path is not a regular file, is a symlink, cannot be
    opened/read consistently, or is replaced/changed during the read.
    """
    try:
        st = os.lstat(path)
    except OSError:
        return None
    if not stat.S_ISREG(st.st_mode):
        return None
    flags = os.O_RDONLY
    no_follow = getattr(os, "O_NOFOLLOW", 0)
    if no_follow:
        flags |= no_follow
    try:
        fd = os.open(path, flags)
    except OSError:
        return None
    try:
        try:
            fst = os.fstat(fd)
        except OSError:
            return None
        if (not stat.S_ISREG(fst.st_mode)
                or (fst.st_dev, fst.st_ino) != (st.st_dev, st.st_ino)):
            return None
        size0 = fst.st_size
        chunks: List[bytes] = []
        while True:
            chunk = os.read(fd, 65536)
            if not chunk:
                break
            chunks.append(chunk)
        try:
            fst2 = os.fstat(fd)
        except OSError:
            return None
        if ((fst2.st_dev, fst2.st_ino) != (fst.st_dev, fst.st_ino)
                or fst2.st_size != size0):
            return None
        return b"".join(chunks)
    finally:
        os.close(fd)


def _race_safe_hash_file(path: str) -> Optional[str]:
    """SHA-256 of a regular file, race-safe (no-follow open + identity/size checks).

    Returns ``None`` when the path is a symlink, not a regular file, or cannot be
    read/hashed consistently (replaced/changed during the read).
    """
    try:
        st = os.lstat(path)
    except OSError:
        return None
    if not stat.S_ISREG(st.st_mode):
        return None
    flags = os.O_RDONLY
    no_follow = getattr(os, "O_NOFOLLOW", 0)
    if no_follow:
        flags |= no_follow
    try:
        fd = os.open(path, flags)
    except OSError:
        return None
    try:
        try:
            fst = os.fstat(fd)
        except OSError:
            return None
        if (not stat.S_ISREG(fst.st_mode)
                or (fst.st_dev, fst.st_ino) != (st.st_dev, st.st_ino)):
            return None
        size0 = fst.st_size
        h = hashlib.sha256()
        while True:
            chunk = os.read(fd, 65536)
            if not chunk:
                break
            h.update(chunk)
        try:
            fst2 = os.fstat(fd)
        except OSError:
            return None
        if ((fst2.st_dev, fst2.st_ino) != (fst.st_dev, fst.st_ino)
                or fst2.st_size != size0):
            return None
        return h.hexdigest()
    finally:
        os.close(fd)


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ── Real command runner (argv array, shell=False, combined output) ────────────
def subprocess_runner(argv: Sequence[str], cwd: str) -> RunOutcome:
    """Run ``argv`` in ``cwd`` capturing combined stdout+stderr.

    Launch failures (e.g. a missing executable) raise ``OSError`` so the caller
    can record a controlled ``LAUNCH_FAILED`` outcome and fail the capture.
    """
    proc = subprocess.run(
        list(argv),
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return RunOutcome(proc.returncode, proc.stdout or "")


# ── Streaming command-log capture (PR-GR-00R part B) ──────────────────────────
class _CommandLogSink:
    """Atomic, capped, sanitized streaming writer for one command log.

    Sanitized chunks of the combined stdout/stderr stream are appended to a
    sibling temporary file; ``finish`` flushes, fsyncs, and ``os.replace``s it
    onto the final log path, so a reader only ever sees a fully published
    artifact.  Once ``CHILD_OUTPUT_LIMIT`` sanitized characters have been
    persisted the sink flags ``output-limit-exceeded``, appends the single
    ``LOG_TRUNCATION_MARKER``, and discards further input — the caller keeps
    draining the child pipe so the child can never block on a full pipe.  Any
    filesystem failure flags ``log-write-failed`` (sticky) instead of raising;
    when several faults occur the first one wins as the recorded code.  Raw
    child payloads are never fully materialized: only the bounded pending-line
    buffer (capped by ``MAX_LOG_STREAM_BUFFER``) persists between ``sink``
    calls.
    """

    def __init__(self, final_path: str) -> None:
        self.final_path = final_path
        self.tmp_path: Optional[str] = None
        self._handle = None
        self._pending = ""
        self.persisted_chars = 0
        self.exceeded = False
        self.write_failed = False
        self.failure_code: Optional[str] = None
        self.published_bytes = 0

    def open(self) -> bool:
        """Create the sibling temp log.  Returns False (flagged) on failure."""
        try:
            parent = os.path.dirname(self.final_path) or "."
            os.makedirs(parent, exist_ok=True)
            name = os.path.basename(self.final_path) or "command.log"
            fd, tmp = tempfile.mkstemp(
                prefix=f".{name}.", suffix=".tmp", dir=parent)
            self._handle = os.fdopen(fd, "w", encoding="utf-8", newline="\n")
            self.tmp_path = tmp
            return True
        except Exception:
            self._flag_write_failed()
            return False

    def _flag_write_failed(self) -> None:
        self.write_failed = True
        if self.failure_code is None:
            self.failure_code = LOG_WRITE_FAILED

    def sink(self, chunk: str) -> None:
        """Consume one chunk of combined child output (never raises)."""
        if self.exceeded or self._handle is None:
            # Cap already tripped or no temp log: keep draining, persist nothing.
            return
        if self.failure_code == LOG_WRITE_FAILED:
            return  # sticky failure: consume and discard
        try:
            self._write_chunk(chunk)
        except Exception:
            self._flag_write_failed()

    def _write_chunk(self, chunk: str) -> None:
        data = self._pending + chunk
        self._pending = ""
        if not data:
            return
        parts = data.split("\n")
        self._pending = parts.pop()
        for line in parts:
            self._emit_line(line + "\n")
        if len(self._pending) > MAX_LOG_STREAM_BUFFER:
            # A hostile child emitting no newlines cannot grow memory past this
            # bound: flush the buffered fragment through the normal sanitize
            # path (a redaction pattern spanning the flush boundary may then be
            # missed — the documented bounded-memory tradeoff).
            self._emit_line(self._pending)
            self._pending = ""

    def _emit_line(self, line: str) -> None:
        sanitized = _sanitize_log_line(line)
        room = CHILD_OUTPUT_LIMIT - self.persisted_chars
        if room <= 0:
            self._trip_cap()
            return
        if len(sanitized) <= room:
            self._handle.write(sanitized)
            self.persisted_chars += len(sanitized)
            return
        self._handle.write(sanitized[:room])
        self.persisted_chars += room
        self._trip_cap()

    def _trip_cap(self) -> None:
        if self.exceeded:
            return
        self.exceeded = True
        if self.failure_code is None:
            self.failure_code = LOG_OUTPUT_LIMIT_EXCEEDED
        try:
            self._handle.write(LOG_TRUNCATION_MARKER)
            self.persisted_chars += len(LOG_TRUNCATION_MARKER)
        except Exception:
            self._flag_write_failed()

    def finish(self) -> None:
        """Flush, fsync, close, and atomically publish the temp log.

        Never raises.  On any failure the temp file is removed (no ``*.tmp``
        leftovers), the failure is flagged ``log-write-failed``, and nothing is
        published; whatever was persisted before an earlier fault stays flagged
        via ``failure_code`` and is never hashed as valid evidence.
        """
        if self._handle is None:
            return
        try:
            if self._pending and not self.exceeded and self.failure_code is None:
                self._emit_line(self._pending)
            self._pending = ""
            self._handle.flush()
            os.fsync(self._handle.fileno())
            self._handle.close()
            self._handle = None
            with suppress(OSError):
                os.chmod(self.tmp_path, 0o644)
            os.replace(self.tmp_path, self.final_path)
            self.tmp_path = None
            try:
                self.published_bytes = os.path.getsize(self.final_path)
            except OSError:
                self.published_bytes = 0
        except Exception:
            self._flag_write_failed()
        finally:
            if self._handle is not None:
                with suppress(Exception):
                    self._handle.close()
                self._handle = None
            if self.tmp_path is not None:
                with suppress(OSError):
                    os.unlink(self.tmp_path)
                self.tmp_path = None


def streaming_subprocess_runner(
    argv: Sequence[str], cwd: str, sink: Callable[[str], None]
) -> int:
    """Run ``argv`` in ``cwd``, streaming merged stdout+stderr through ``sink``.

    A single merged pipe (``stderr=STDOUT``) is read incrementally in
    ``LOG_STREAM_CHUNK_CHARS`` character chunks and handed to ``sink`` as it
    arrives, so the raw child payload is never fully materialized in memory and
    the pipe is always drained — draining continues even after the sink's size
    cap has tripped, so the child can never block on a full pipe.  Text is
    decoded UTF-8 with ``errors="replace"``.  Returns the child exit code;
    launch failures (e.g. a missing executable) raise ``OSError`` so the caller
    can record a controlled ``LAUNCH_FAILED`` outcome.
    """
    proc = subprocess.Popen(
        list(argv),
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    try:
        stream = proc.stdout
        while True:
            chunk = stream.read(LOG_STREAM_CHUNK_CHARS)
            if not chunk:
                break
            sink(chunk)
        stream.close()
    finally:
        if proc.stdout is not None and not proc.stdout.closed:
            with suppress(Exception):
                proc.stdout.close()
        # Always reap the child, even when the sink or the read loop raised.
        returncode = proc.wait()
    return returncode


# ── Environment redaction ─────────────────────────────────────────────────────
def _env_value_allowed(key: str) -> bool:
    # Explicit allowlist only.  No broad suffix (e.g. ``*_VERSION``) is permitted,
    # so a secret-like variable such as ``DB_PASSWORD_VERSION`` stays redacted.
    return key.upper() in ENV_VERSION_ALLOWLIST


def collect_environment() -> Dict[str, Any]:
    """Return a redacted environment snapshot.

    Only an explicit allowlist of version-field keys keep their values; every
    other value is replaced by ``REDACTED_MARKER``.  Key names are always
    recorded so a reviewer sees which variables were present.
    """
    redacted: Dict[str, Any] = {}
    for key in sorted(os.environ):
        allowed = _env_value_allowed(key)
        if allowed:
            # Even allowlisted version values are sanitized (absolute paths /
            # secrets redacted) and length-bounded so a hostile environment value
            # cannot leak raw payloads into the evidence bundle.
            redacted[key] = _bounded(os.environ[key], 256)
        else:
            redacted[key] = REDACTED_MARKER
    return {
        "variables": redacted,
        "allowed_value_keys": sorted(
            k for k in os.environ if _env_value_allowed(k)
        ),
        "redacted_count": sum(1 for k in os.environ if not _env_value_allowed(k)),
    }


# ── Preflight git / version capture ───────────────────────────────────────────
def run_preflight(root: str, runner: Callable[[Sequence[str], str], RunOutcome]) -> Dict[str, Any]:
    """Record the preflight command matrix (plan section A).

    Every preflight command is recorded as a bounded, sanitized record so a
    reviewer can reproduce the captured git/version state. The capture fails
    closed when the essential git identity commands (``HEAD`` / ``HEAD^{tree}``)
    cannot be resolved: without a known SHA/tree the evidence bundle is
    meaningless and must not be trusted.
    """
    records: List[Dict[str, Any]] = []

    def run(argv: List[str]) -> RunOutcome:
        try:
            outcome = runner(argv, root)
        except Exception:
            records.append({
                "argv": list(argv),
                "exit_code": None,
                "output": "<launch-failed>",
            })
            return RunOutcome(-1, "")
        # The git-log preflight record must never persist commit-message text,
        # so its output is reduced to SHAs only.  Git status/diff records have
        # their filenames normalized/sanitized (hostile path forms redacted) so
        # raw machine/path content never reaches the preflight records.  Every
        # other preflight record is comprehensively sanitized (absolute paths /
        # secrets / SQL / exceptions) and bounded before persistence.
        if argv == ["git", "log", "--oneline", "-20"]:
            output = _sanitize_preflight_log(outcome.combined)
        elif argv == ["git", "status", "--porcelain=v1"]:
            output = _sanitize_git_filenames(outcome.combined, is_status=True)
        elif argv in (["git", "diff", "--name-only"],
                      ["git", "diff", "--cached", "--name-only"]):
            output = _sanitize_git_filenames(outcome.combined, is_status=False)
        else:
            output = _sanitize_child_output(outcome.combined)
        records.append({
            # Custom argv tokens are never persisted verbatim: secrets and
            # absolute/UNC/backslash paths are redacted and the token is
            # length-bounded.  The preflight commands are fixed git invocations,
            # so this is a no-op there, but it guarantees no injected token leaks
            # into ``git-state.json``.
            "argv": [_sanitize_argv_token(t) for t in argv],
            "exit_code": outcome.returncode,
            "output": output,
        })
        return outcome

    head_out = run(["git", "rev-parse", "HEAD"])
    tree_out = run(["git", "rev-parse", "HEAD^{tree}"])
    status_out = run(["git", "status", "--porcelain=v1"])
    diff_out = run(["git", "diff", "--name-only"])
    staged_diff_out = run(["git", "diff", "--cached", "--name-only"])
    log_raw = run(["git", "log", "--oneline", "-20"]).combined
    status_raw = status_out.combined
    diff_raw = diff_out.combined
    staged_diff_raw = staged_diff_out.combined

    py = run(["python", "--version"])
    py3 = run(["python3", "--version"])
    java = run(["java", "-version"])
    gradle = run(["./gradlew", "--version"])

    # Reject failed/nonzero or malformed git rev-parse even when output exists:
    # the identity is only trusted if the command succeeded AND produced a valid
    # 40-hex SHA.  Otherwise the bundle is meaningless and must fail closed.
    head_raw = head_out.combined.strip()
    tree_raw = tree_out.combined.strip()
    head_ok = head_out.returncode == 0 and _is_valid_sha40(head_raw)
    tree_ok = tree_out.returncode == 0 and _is_valid_sha40(tree_raw)
    preflight_ok = head_ok and tree_ok

    # The preflight git metadata commands (status / diff / staged-diff) must
    # succeed.  A failure (nonzero exit or launch error) means the checkout
    # state cannot be observed reliably, so the capture must fail closed rather
    # than record a possibly-stale or empty git state.
    git_meta_failures = [
        name for name, out in (
            ("status", status_out),
            ("diff", diff_out),
            ("staged-diff", staged_diff_out),
        ) if out.returncode != 0
    ]
    git_meta_ok = not git_meta_failures

    return {
        "commit": head_raw if head_ok else None,
        "tree": tree_raw if tree_ok else None,
        # Preflight status/diff/log metadata is sanitized and bounded before
        # persistence: absolute paths, secrets, SQL errors, and raw exception
        # text are redacted, and commit-message text is dropped (only SHAs are
        # kept) so no raw path / secret / message reaches git-state.json.
        # Git status / diff / staged-diff filenames are normalized and sanitized
        # (hostile path forms — backslash, UNC, Windows drive prefix, POSIX
        # absolute, ``..`` traversal — redacted to ``<redacted-path>``) before
        # persistence so raw machine/path content never reaches git-state.json.
        # This is the same git-filename sanitization applied to the preflight
        # command records, now applied to every status/diff/staged field.
        "status": _sanitize_git_filenames(status_raw, is_status=True),
        "diff_name_only": _sanitize_git_filenames(diff_raw, is_status=False),
        "staged_diff_name_only": _sanitize_git_filenames(staged_diff_raw, is_status=False),
        "log_oneline": _sanitize_preflight_log(log_raw),
        "python_available": py.returncode == 0,
        "python3_available": py3.returncode == 0,
        # Version metadata is bounded and sanitized (absolute paths / secrets
        # redacted, length capped) so an unusual interpreter version string
        # cannot leak raw payloads into the evidence bundle.
        "python_version": _bounded(py.combined.strip(), 256) if py.returncode == 0 else None,
        "python3_version": _bounded(py3.combined.strip(), 256) if py3.returncode == 0 else None,
        "java_version": _bounded(_first_line(java.combined), 256) if java.returncode == 0 else None,
        "gradle_version": _bounded(_first_line(gradle.combined), 256) if gradle.returncode == 0 else None,
        "preflight_ok": preflight_ok,
        "git_meta_ok": git_meta_ok,
        "git_meta_failures": git_meta_failures,
        "preflight_commands": records,
    }


def _first_line(text: str) -> Optional[str]:
    for line in text.splitlines():
        if line.strip():
            return line.strip()
    return None


# ── Input manifest ────────────────────────────────────────────────────────────
def git_blob_id(root: str, rel_path: str,
                runner: Callable[[Sequence[str], str], RunOutcome]) -> Optional[str]:
    """Return the Git blob ID for a tracked file, or None when untracked/missing.

    The returned value is validated to be a 40-hex Git SHA-1 (blob ID).  Any
    other output (garbage, a path, a multi-line payload, or a forged/truncated
    value) is rejected so an untrusted blob ID never reaches the input manifest.
    """
    try:
        proc = runner(["git", "rev-parse", f"HEAD:{rel_path}"], root)
    except Exception:
        return None
    if proc.returncode != 0:
        return None
    value = proc.combined.strip()
    if not _is_valid_sha40(value):
        return None
    return value


def collect_input_manifest(
    root: str,
    candidates: Sequence[str],
    runner: Callable[[Sequence[str], str], RunOutcome],
) -> List[Dict[str, Any]]:
    """Build the input manifest: one entry per candidate repo-relative path."""
    manifest: List[Dict[str, Any]] = []
    for rel in candidates:
        # Stop materializing once the finite manifest bound is reached; the
        # overflow is surfaced by the manifest-bound check in ``capture_evidence``
        # (which retains only the controlled ``OVERFLOW_MANIFEST`` marker).
        if len(manifest) >= MAX_MANIFEST_ENTRIES:
            break
        # Validate every candidate (including a custom ``input_candidates`` list)
        # with realpath containment *before* any filesystem/Git access.  A hostile
        # or malformed candidate (non-string, backslash, UNC share, Windows drive
        # prefix, POSIX absolute path, ``..`` traversal, or a symlink whose
        # resolved target escapes ``root``) must never be read or hashed; it is
        # recorded as missing so the capture fails closed on a missing required
        # input rather than touching an outside file.  This is the containment
        # gate that prevents a custom candidate from leaking or reading arbitrary
        # machine paths.
        if not isinstance(rel, str) or not rel:
            manifest.append({
                "rel_path": "<redacted-unsafe-candidate>",
                "exists": False,
                "blob_id": None,
                "sha256": None,
                "size": None,
            })
            continue
        if not _is_safe_repo_relative_path(rel, root):
            # The raw candidate is never persisted verbatim; a hostile or malformed
            # value (absolute path, traversal, UNC, backslash, or symlink escape)
            # is reduced to a bounded controlled marker so no machine/path content
            # leaks into the input manifest.
            manifest.append({
                "rel_path": "<redacted-unsafe-candidate>",
                "exists": False,
                "blob_id": None,
                "sha256": None,
                "size": None,
            })
            continue
        abs_path = os.path.join(root, rel)
        exists = os.path.isfile(abs_path)
        entry: Dict[str, Any] = {
            "rel_path": rel.replace(os.sep, "/"),
            "exists": exists,
            "blob_id": git_blob_id(root, rel, runner) if exists else None,
            "sha256": None,
            "size": None,
        }
        if exists:
            # Race-safe read: a symlink / non-regular / replaced input is rejected
            # (``None``) and recorded as missing so the capture fails closed on a
            # missing required input rather than hashing untrusted content.
            try:
                data = _race_safe_read_bytes(abs_path)
            except Exception:
                data = None
            if data is None:
                entry["exists"] = False
            else:
                entry["sha256"] = sha256_bytes(data)
                entry["size"] = len(data)
        manifest.append(entry)
    return manifest


def input_manifest_hash(manifest: Sequence[Dict[str, Any]]) -> str:
    """Deterministic SHA-256 over the canonical manifest JSON."""
    canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return sha256_bytes(canonical.encode("utf-8"))


def discover_input_candidates(
    root: str,
    runner: Callable[[Sequence[str], str], RunOutcome],
) -> List[str]:
    """Build the input manifest candidate list dynamically from tracked files.

    Enumerates tracked files matching ``INPUT_DISCOVERY_PATTERNS`` via
    ``git ls-files`` (so every tracked ``scripts/db_guard/*.py`` and DB-related
    guard config/policy/structural/raw-query file is included automatically),
    then unions with ``REQUIRED_INPUT_CANDIDATES`` so a missing required input is
    still surfaced by the missing-input diagnostic.  Returns a sorted,
    de-duplicated list of repository-relative paths.
    """
    tracked: set = set()
    try:
        proc = runner(["git", "ls-files", "--", *INPUT_DISCOVERY_PATTERNS], root)
    except Exception:
        proc = None
    if proc is not None and proc.returncode == 0:
        for line in proc.combined.splitlines():
            line = line.strip()
            if not line:
                continue
            # Stop materializing once the finite manifest bound is reached; the
            # overflow is surfaced by the manifest-bound check in ``capture_evidence``.
            if len(tracked) >= MAX_MANIFEST_ENTRIES:
                break
            tracked.add(line.replace(os.sep, "/"))
    candidates = set(tracked)
    candidates.update(REQUIRED_INPUT_CANDIDATES)
    return sorted(candidates)


# ── Preservation checker (forbidden files must not change) ────────────────────
def preservation_check(
    root: str,
    runner: Callable[[Sequence[str], str], RunOutcome],
) -> Dict[str, Any]:
    """Fail closed when any forbidden file or ``app/src/main`` differs from HEAD.

    Inspects every change surface: unstaged working-tree changes (``git diff
    HEAD`` / ``git diff --exit-code``), staged changes (``git diff --cached``),
    and untracked paths (``git ls-files --others`` and porcelain ``??`` entries).
    Porcelain status lines are parsed positionally on the raw, unstripped line so
    every form — including the leading-space `` M path`` working-tree/staged form
    — resolves to the bare repository-relative path(s).
    A forbidden path or any path under ``app/src/main`` appearing in *any* of
    these surfaces fails the capture closed.  Every persisted filename is
    sanitized (hostile path forms redacted) so raw machine/path content never
    reaches the evidence bundle.
    """
    forbidden_set = set(FORBIDDEN_PRESERVATION_PATHS)
    main_prefix = "app/src/main/"

    def clean(diff_argv: List[str]) -> bool:
        try:
            proc = runner(diff_argv, root)
        except Exception:
            return False
        return proc.returncode == 0

    def name_only(argv: List[str]) -> List[str]:
        try:
            proc = runner(argv, root)
        except Exception:
            return []
        if proc.returncode != 0:
            return []
        return [ln.strip() for ln in proc.combined.splitlines() if ln.strip()]

    def raw_lines(argv: List[str]) -> List[str]:
        """Raw (unstripped) output lines for positional porcelain parsing.

        Porcelain status lines carry two status columns followed by a single
        space at column 2; stripping a line *before* parsing would destroy a
        leading-space status column (`` M path``) and mangle the extracted
        filename into ``M path`` (which matches nothing).  Only blank lines are
        dropped; the raw line is handed to ``_extract_git_filenames`` intact.
        """
        try:
            proc = runner(argv, root)
        except Exception:
            return []
        if proc.returncode != 0:
            return []
        return [ln for ln in proc.combined.splitlines() if ln.strip()]

    # Unstaged working-tree changes vs HEAD (policy + production).
    ok_policy_unstaged = clean(
        ["git", "diff", "--exit-code", "--", *FORBIDDEN_PRESERVATION_PATHS])
    ok_main_unstaged = clean(["git", "diff", "--exit-code", "--", "app/src/main"])
    # Staged changes vs HEAD (policy + production).
    ok_policy_staged = clean(
        ["git", "diff", "--cached", "--exit-code", "--", *FORBIDDEN_PRESERVATION_PATHS])
    ok_main_staged = clean(
        ["git", "diff", "--cached", "--exit-code", "--", "app/src/main"])

    # Collect changed tracked paths (staged + unstaged) via ``git diff HEAD``.
    changed_tracked = set(name_only(["git", "diff", "HEAD", "--name-only"]))
    # Untracked paths via ``git ls-files --others`` and porcelain ``??`` entries.
    # Porcelain lines are parsed on the RAW line (never pre-stripped) so the
    # positional status columns survive: a leading-space staged/working-tree form
    # (`` M path``) resolves to the bare repository-relative path.
    untracked: set = set(name_only(["git", "ls-files", "--others", "--exclude-standard"]))
    for line in raw_lines(["git", "status", "--porcelain=v1"]):
        names = _extract_git_filenames(line)
        for name in names:
            # Porcelain ``??`` marks an untracked path; a staged/rename line may
            # also surface a new (untracked-then-added) path — treat any parsed
            # name as a candidate untracked surface so it is never skipped.
            untracked.add(name.strip().strip('"'))

    # Determine which forbidden / production paths actually changed.
    forbidden_changed: set = set()
    for p in changed_tracked:
        if p in forbidden_set:
            forbidden_changed.add(p)
    main_changed = any(
        p == "app/src/main" or p.startswith(main_prefix)
        for p in changed_tracked
    )
    for p in untracked:
        if p in forbidden_set:
            forbidden_changed.add(p)
        if p == "app/src/main" or p.startswith(main_prefix):
            main_changed = True

    ok_untracked = not any(
        (p in forbidden_set) or p == "app/src/main" or p.startswith(main_prefix)
        for p in untracked
    )

    policy_ok = bool(
        ok_policy_unstaged and ok_policy_staged and ok_untracked
        and not forbidden_changed
    )
    production_ok = bool(ok_main_unstaged and ok_main_staged and not main_changed)
    staged_ok = bool(ok_policy_staged and ok_main_staged)
    untracked_ok = bool(ok_untracked)
    ok = bool(policy_ok and production_ok)

    # Sanitize every persisted filename (hostile path forms redacted) so raw
    # machine/path content never reaches the evidence bundle.
    checked_paths = [
        _sanitize_changed_filename(p) for p in FORBIDDEN_PRESERVATION_PATHS
    ] + ["app/src/main"]
    sanitized_changed = [
        _sanitize_changed_filename(p) for p in sorted(forbidden_changed)
    ]
    return {
        "ok": ok,
        "policy_ok": policy_ok,
        "production_ok": production_ok,
        "staged_ok": staged_ok,
        "untracked_ok": untracked_ok,
        "checked_paths": checked_paths,
        "forbidden_changed": sanitized_changed,
    }


def _default_preservation_result() -> Dict[str, Any]:
    """The fail-closed preservation result used when a capture stage is skipped.

    Used by every stop-before-run path (matrix validation failure, missing or
    invalid run pin, pre-launch SHA-gate rejection) so a skipped stage never
    fabricates a passing preservation observation.
    """
    return {
        "ok": False,
        "policy_ok": False,
        "production_ok": False,
        "staged_ok": False,
        "untracked_ok": False,
        "checked_paths": [
            _sanitize_changed_filename(p) for p in FORBIDDEN_PRESERVATION_PATHS
        ] + ["app/src/main"],
        "forbidden_changed": [],
    }


# ── v2 report parsing (bounded, no validation of untrusted content) ──────────
def parse_v2_report(path: str, raw: Optional[bytes] = None) -> Dict[str, Any]:
    """Parse a protocol-v2 report, returning bounded structured fields.

    Never raises; on missing/invalid/absent report returns a controlled
    ``parser_error`` and ``None`` for the structured fields.  Nested containers
    are strictly type-checked: ``findings`` / ``diagnostics`` must be JSON
    arrays, ``statistics`` a JSON object, every diagnostic an object carrying a
    string ``code``, and every finding a bounded object (see
    ``MAX_FINDING_KEYS``).  A wrong-type container or entry returns a controlled
    ``MALFORMED_*`` parser error instead of being partially accepted.

    When ``raw`` is supplied it is used as the single authoritative byte snapshot
    for both the parse and (by the caller) the hash, so the evidence can never
    combine a hash of one report version with a parse of another (TOCTOU /
    replacement).  When ``raw`` is ``None`` the bytes are read race-safely from
    ``path`` (a symlink / non-regular / replaced report is rejected and fails
    closed with ``READ_FAILED``).
    """
    empty = {
        "schema_version": None,
        "trusted": None,
        "diagnostic_codes": [],
        "finding_count": None,
        "parser_error": None,
    }
    # Use the supplied snapshot when present; otherwise read race-safely.  A
    # symlink / non-regular / replaced report is rejected (None) and fails closed
    # with a controlled diagnostic rather than being followed.
    if raw is None:
        try:
            raw = _race_safe_read_bytes(path)
        except Exception:
            raw = None
    if raw is None:
        return {**empty, "parser_error": "READ_FAILED"}
    try:
        data = json.loads(raw.decode("utf-8"))
    except FileNotFoundError:
        return {**empty, "parser_error": "MISSING_FILE"}
    except json.JSONDecodeError:
        return {**empty, "parser_error": "INVALID_JSON"}
    except Exception:
        return {**empty, "parser_error": "READ_FAILED"}

    if not isinstance(data, dict):
        return {**empty, "parser_error": "NOT_OBJECT"}
    if data.get("schema") != REPORT_SCHEMA or data.get("schema_version") != REPORT_SCHEMA_VERSION:
        return {**empty, "parser_error": "SCHEMA_MISMATCH"}

    # Container type checks (fail closed): a wrong-type container is a malformed
    # report, never partially accepted.  Every nested value is validated *before*
    # any attribute access so a hostile shape (e.g. a list-typed ``statistics``,
    # which previously raised ``AttributeError`` on ``.get``) can never make the
    # parser raise.  A missing / null container keeps the historical tolerance
    # and is treated as empty.
    findings = data.get("findings")
    if findings is None:
        findings = []
    if not isinstance(findings, list):
        return {**empty, "parser_error": MALFORMED_FINDINGS}
    diagnostics = data.get("diagnostics")
    if diagnostics is None:
        diagnostics = []
    if not isinstance(diagnostics, list):
        return {**empty, "parser_error": MALFORMED_DIAGNOSTICS}
    statistics = data.get("statistics")
    if statistics is None:
        statistics = {}
    if not isinstance(statistics, dict):
        return {**empty, "parser_error": MALFORMED_STATISTICS}
    trusted = statistics.get("trusted")
    # Bound the persisted diagnostic-code collection *during iteration*: stop
    # appending once the finite limit is reached and retain only the controlled
    # overflow marker, so an unbounded or hostile report cannot inflate the
    # evidence bundle.  Each diagnostic must be an object carrying a string
    # ``code``; anything else is a malformed entry that fails the whole report
    # closed instead of being silently skipped.
    codes: List[str] = []
    codes_overflow = False
    for d in diagnostics:
        if not isinstance(d, dict) or not isinstance(d.get("code"), str):
            return {**empty, "parser_error": MALFORMED_DIAGNOSTIC_ENTRY}
        if len(codes) >= MAX_DIAGNOSTIC_CODES:
            codes_overflow = True
            break
        codes.append(_sanitize_diagnostic_code(d.get("code")))
    if codes_overflow:
        codes = [OVERFLOW_DIAGNOSTIC_CODES]
    # Bound the persisted finding count: a report carrying more findings than the
    # finite bound is treated as unparseable (fail closed) so an unbounded count
    # never reaches the evidence bundle.
    if len(findings) > MAX_FINDING_COUNT:
        return {**empty, "parser_error": OVERFLOW_FINDING_COUNT}
    # Each finding must have a safe bounded shape: a JSON object with at most
    # ``MAX_FINDING_KEYS`` keys.  Only the count is persisted, but a scalar /
    # list finding or a hostile wide-object finding fails the report closed
    # rather than being accepted.
    for f in findings:
        if not isinstance(f, dict) or len(f) > MAX_FINDING_KEYS:
            return {**empty, "parser_error": MALFORMED_FINDING_ENTRY}
    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "trusted": bool(trusted) if isinstance(trusted, bool) else None,
        "diagnostic_codes": codes,
        "finding_count": len(findings),
        "parser_error": None,
    }


# ── Command matrix (default) ──────────────────────────────────────────────────
def default_command_matrix(root: str, out_dir: str) -> List[CommandSpec]:
    """Build the fixed command matrix (plan sections B–H).

    ``report_path`` and ``required_artifacts`` are stored relative to ``out_dir``
    (the bundle directory) so the capture tool can resolve them on disk; the
    evidence JSON records them repository-relative via the bundle root.

    Output paths embedded in ``argv`` are stored **repository-relative** (never
    absolute) so no machine path leaks through the evidence. The ratchet child
    command uses repeatable ``--command-arg=<value>`` tokens so option-like
    child values are never re-parsed by a shell.
    """
    bundle_rel = _posix_rel(out_dir, root)

    def rop(*parts: str) -> str:
        """Repository-relative output path (POSIX) safe to store in evidence argv."""
        return "/".join([bundle_rel, *parts])

    return [
        CommandSpec(
            id="registry-validation",
            log_name="00-registry.log",
            argv=["python3", "scripts/ci/verify_guard_registry.py"],
        ),
        CommandSpec(
            id="focused-python-tests",
            log_name="01-focused-python-tests.log",
            argv=[
                "python3", "-m", "pytest",
                "scripts/ci/test_guard_findings.py",
                "scripts/ci/test_guard_ratchet.py",
                "scripts/ci/test_guard_ratchet_v2.py",
                "scripts/test_kotlin_callable_parser.py",
                "scripts/test_migrate_db_policy_signatures.py",
                "scripts/test_db_guard_room_inventory.py",
                "scripts/test_db_guard_sql_classifier.py",
                "scripts/test_db_guard_declaration_scanner.py",
                "scripts/test_db_guard_scanner_d4.py",
                "scripts/test_verify_db_access_v2.py",
                "scripts/test_verify_db_access_boundaries.py",
                "-v", "--tb=short",
                # Zero-side-effect pin (strict-review blocker B-1): pytest's
                # cacheprovider is disabled because no pytest config file exists
                # and .gitignore has no .pytest_cache entry, so a plain run
                # would materialize an untracked .pytest_cache/ directory,
                # trip the post-capture drift re-check on the status surface
                # (exit 2), and make the two-clean-capture protocol
                # unreachable. The matrix must leave the working tree untouched.
                "-p", "no:cacheprovider",
            ],
        ),
        CommandSpec(
            id="room-inventory",
            log_name="02-room-inventory.log",
            argv=[
                "python3", "scripts/verify_db_access_boundaries.py",
                "--inventory-only",
                "--findings-output", rop("02-room-inventory.findings.json"),
                "--dump-room-mutators", rop("02-room-mutators.json"),
            ],
            report_path="02-room-inventory.findings.json",
            required_artifacts=("02-room-inventory.findings.json", "02-room-mutators.json"),
            artifact_kinds=(
                ("02-room-inventory.findings.json", "file"),
                ("02-room-mutators.json", "file"),
            ),
        ),
        CommandSpec(
            id="db-cli",
            log_name="03-db-cli.log",
            argv=[
                "python3", "scripts/verify_db_access_boundaries.py",
                "--fail-on-violation",
                "--ownership-policy", "config/guards/db_ownership_policy.yml",
                "--structural-exceptions", "config/guards/db_structural_exceptions.yml",
                "--structural-manifest", "config/guards/db_structural_exceptions_expected_methods.yml",
                "--findings-output", rop("03-db-cli.findings.json"),
            ],
            report_path="03-db-cli.findings.json",
            required_artifacts=("03-db-cli.findings.json",),
            artifact_kinds=(("03-db-cli.findings.json", "file"),),
        ),
        CommandSpec(
            id="db-ratchet",
            log_name="04-db-ratchet.log",
            argv=[
                "python3", "scripts/ci/guard_ratchet.py",
                "--guard-name=db_access",
                "--command-arg=python3",
                "--command-arg=scripts/verify_db_access_boundaries.py",
                "--command-arg=--fail-on-violation",
                "--command-arg=--ownership-policy",
                "--command-arg=config/guards/db_ownership_policy.yml",
                "--command-arg=--structural-exceptions",
                "--command-arg=config/guards/db_structural_exceptions.yml",
                "--command-arg=--structural-manifest",
                "--command-arg=config/guards/db_structural_exceptions_expected_methods.yml",
                "--baseline=config/baselines/db_access.json",
                "--ci-mode",
                "--finding-protocol=2",
                "--output-summary", rop("04-db-ratchet.summary.json"),
            ],
            report_path="04-db-ratchet.summary.json",
            required_artifacts=("04-db-ratchet.summary.json",),
            artifact_kinds=(("04-db-ratchet.summary.json", "file"),),
        ),
        CommandSpec(
            id="static-suite",
            log_name="05-static-suite.log",
            argv=[
                "python3", "scripts/ci/run_static_guard_suite.py",
                "--output-dir", rop("05-static-suite"),
            ],
            required_artifacts=("05-static-suite", "05-static-suite/summary.json"),
            artifact_kinds=(
                ("05-static-suite", "dir"),
                ("05-static-suite/summary.json", "file"),
            ),
        ),
        CommandSpec(
            id="gradle-db",
            log_name="06-gradle-db.log",
            argv=["./gradlew", ":app:verifyDbAccessBoundaries", "--no-daemon", "--stacktrace"],
        ),
        CommandSpec(
            id="gradle-task-graph",
            log_name="07-gradle-task-graph.log",
            argv=["./gradlew", ":app:check", "--dry-run", "--no-daemon"],
        ),
    ]


# ── Custom command-matrix validation (prevent absolute/outside argv) ───────────
def validate_command_matrix(
    matrix: Sequence[CommandSpec], root: str
) -> List[str]:
    """Reject unsafe or unbounded fields embedded in a custom command matrix.

    The default matrix uses only repository-relative tokens, but an injected
    custom matrix must never persist or execute an absolute/outside path, a
    secret/payload token, an oversized/unbounded token, an unsafe command id, an
    unbounded path string, or a required artifact lacking an explicit valid
    artifact kind.  Every injected ``CommandSpec`` and nested field is validated
    *before* any ``len``/unpack/iteration so a malformed matrix (non-``CommandSpec``
    entry, non-string/non-sequence ``argv``/``required_artifacts``, or a malformed
    ``artifact_kinds`` entry) fails closed with a bounded ``invalid-matrix-spec``
    warning and zero runner calls.  Each violation is returned as a bounded warning
    (``invalid-matrix-argv:``, ``invalid-bundle-path:``, ``missing-artifact-kind:``,
    or ``OVERFLOW_*``) so the capture can fail closed **before any runner call**.
    Custom argv tokens are never persisted verbatim (see ``_sanitize_argv_token``);
    here they are rejected outright so a hostile matrix is not executed at all.
    """
    violations: List[str] = []
    root_real = os.path.realpath(root)
    violations_overflow = False
    if len(matrix) > MAX_MATRIX_COMMANDS:
        violations.append(make_warning(OVERFLOW_MATRIX, str(len(matrix))))
    for idx, spec in enumerate(matrix):
        # Stop materializing violations once the finite bound is reached; retain
        # only the controlled overflow marker.
        if violations_overflow or len(violations) >= MAX_VIOLATIONS:
            violations_overflow = True
            break
        # Validate the injected spec type *before* any attribute access.  A
        # non-``CommandSpec`` entry must fail closed, never raise.
        if not isinstance(spec, CommandSpec):
            violations.append(make_warning(INVALID_MATRIX_SPEC, str(idx)))
            continue
        # Validate nested field types *before* len/unpack/iteration so a malformed
        # field cannot crash the validator or be iterated unsafely.  A malformed
        # field fails closed (``invalid-matrix-spec``) but we still iterate the
        # original, type-checked sequence where possible so per-token warnings
        # (e.g. ``invalid-matrix-argv``) are still emitted for the trusted default.
        argv = spec.argv
        argv_ok = isinstance(argv, (list, tuple)) and all(isinstance(t, str) for t in argv)
        if not argv_ok:
            violations.append(make_warning(INVALID_MATRIX_SPEC, str(idx), "argv"))
        safe_argv = argv if isinstance(argv, (list, tuple)) else ()
        required_artifacts = spec.required_artifacts
        req_ok = isinstance(required_artifacts, (list, tuple)) and all(
            isinstance(a, str) for a in required_artifacts)
        if not req_ok:
            violations.append(make_warning(INVALID_MATRIX_SPEC, str(idx), "required_artifacts"))
        safe_required = required_artifacts if isinstance(required_artifacts, (list, tuple)) else ()
        artifact_kinds = spec.artifact_kinds
        normalized_kinds: List[Tuple[str, str]] = []
        if not isinstance(artifact_kinds, (list, tuple)):
            violations.append(make_warning(INVALID_MATRIX_SPEC, str(idx), "artifact_kinds"))
        else:
            for entry in artifact_kinds:
                if not (isinstance(entry, (list, tuple)) and len(entry) == 2
                        and isinstance(entry[0], str) and isinstance(entry[1], str)):
                    violations.append(make_warning(INVALID_MATRIX_SPEC, str(idx), "artifact_kinds"))
                    continue
                normalized_kinds.append((entry[0], entry[1]))
        artifact_kinds = normalized_kinds

        total_len = 0
        # Bounded safe command id.
        if not isinstance(spec.id, str) or not spec.id:
            violations.append(make_warning(OVERFLOW_COMMAND_ID, "<non-string>"))
            spec_id = "<non-string>"
        elif len(spec.id) > MAX_COMMAND_ID_LEN:
            violations.append(
                make_warning(OVERFLOW_COMMAND_ID, _sanitize_warning_token(spec.id[:32])))
            spec_id = spec.id[:MAX_COMMAND_ID_LEN]
        else:
            spec_id = spec.id
        # log_name must be present, bounded, and a safe (non-hostile) path.
        if not isinstance(spec.log_name, str) or not spec.log_name:
            violations.append(make_warning("invalid-bundle-path", spec_id, "<empty-log-name>"))
        elif len(spec.log_name) > MAX_PATH_LEN:
            violations.append(make_warning(OVERFLOW_PATH, spec_id, "<redacted-path>"))
        elif _is_hostile_path_value(spec.log_name):
            violations.append(
                make_warning("invalid-bundle-path", spec_id, _sanitize_warning_token(spec.log_name)))
        # report_path (when present) must be bounded and safe.
        if spec.report_path is not None:
            if not isinstance(spec.report_path, str) or not spec.report_path:
                violations.append(make_warning("invalid-bundle-path", spec_id, "<empty-report-path>"))
            elif len(spec.report_path) > MAX_PATH_LEN:
                violations.append(make_warning(OVERFLOW_PATH, spec_id, "<redacted-path>"))
            elif _is_hostile_path_value(spec.report_path):
                violations.append(
                    make_warning("invalid-bundle-path", spec_id, _sanitize_warning_token(spec.report_path)))
        # required_artifacts must be bounded in count and each entry safe + bounded.
        if len(safe_required) > MAX_REQUIRED_ARTIFACTS:
            violations.append(make_warning(OVERFLOW_PATH, spec_id, "<redacted-path>"))
        for art in safe_required:
            if not isinstance(art, str) or not art:
                violations.append(make_warning("invalid-bundle-path", spec_id, "<empty-artifact>"))
            elif len(art) > MAX_PATH_LEN:
                violations.append(make_warning(OVERFLOW_PATH, spec_id, "<redacted-path>"))
            elif _is_hostile_path_value(art):
                violations.append(
                    make_warning("invalid-bundle-path", spec_id, _sanitize_warning_token(art)))
        # artifact_kinds must be bounded and declare a valid kind ("file"/"dir") for
        # every required artifact.  Dot-in-basename inference is absent; a missing
        # or non-"file"/"dir" kind fails closed with ``missing-artifact-kind``.
        if len(artifact_kinds) > MAX_ARTIFACT_KINDS:
            violations.append(make_warning(OVERFLOW_ARTIFACT_KINDS, spec_id))
        kind_map: Dict[str, Any] = {}
        for rel, kind in artifact_kinds:
            if isinstance(rel, str) and len(rel) <= MAX_PATH_LEN:
                kind_map[rel] = kind
        for art in safe_required:
            kind = kind_map.get(art)
            if kind not in ("file", "dir"):
                violations.append(
                    make_warning("missing-artifact-kind", spec_id, _sanitize_warning_token(str(art)[:64])))
        # argv token validation (absolute/outside, secret, hostile, bounded).
        if len(safe_argv) == 0:
            # An empty argv is a malformed command and must be rejected outright.
            violations.append(make_warning("invalid-matrix-argv", spec_id, "<empty>"))
        if len(safe_argv) > MAX_ARGV_TOKENS:
            violations.append(make_warning(OVERFLOW_ARGV, spec_id, "tokens", str(len(safe_argv))))
        for token in safe_argv:
            # Empty or non-string argv tokens are malformed and rejected (fail
            # closed) rather than silently skipped; a hostile matrix must never be
            # executed or persisted.
            if not isinstance(token, str) or not token:
                violations.append(
                    make_warning("invalid-matrix-argv", spec_id, "<empty-or-non-string>"))
                continue
            total_len += len(token)
            if len(token) > MAX_ARGV_TOKEN_LEN:
                violations.append(
                    make_warning(OVERFLOW_ARGV, spec_id, "token-len", _sanitize_warning_token(token[:32])))
                continue
            # Explicit safe argv schema/allowlist: every custom token must match the
            # controlled character set.  Arbitrary payload tokens (spaces, quotes,
            # shell metacharacters, backticks, ``$``, ``;``, ``|``, ``&``, ``<``,
            # ``>``, parentheses, newlines, NUL, …) are rejected outright so a
            # hostile custom matrix is never executed or persisted verbatim.
            if not _SAFE_ARGV_TOKEN_RE.match(token):
                violations.append(
                    make_warning("invalid-matrix-argv", spec_id, _sanitize_warning_token(token)))
                continue
            # Secret / payload assignments (``password=…``, ``api_key=…``, …) must
            # never be persisted or executed; reject the token outright.
            if _SECRET_RE.search(token):
                violations.append(make_warning("invalid-matrix-argv", spec_id, "<redacted-secret>"))
                continue
            # Hostile path forms (backslash, UNC share, Windows drive prefix, POSIX
            # absolute path, or ``..`` traversal) are never allowed in
            # persisted/executed argv.  The offending token is reduced to a bounded
            # marker in the warning so no raw machine/path content leaks.
            if _is_hostile_path_value(token):
                violations.append(
                    make_warning("invalid-matrix-argv", spec_id, _sanitize_warning_token(token)))
                continue
            # Repository-relative tokens that resolve outside the root are rejected.
            abs_token = os.path.realpath(os.path.join(root, token))
            if not _is_within_realpath(abs_token, root_real):
                violations.append(
                    make_warning("invalid-matrix-argv", spec_id, _sanitize_warning_token(token)))
        if total_len > MAX_ARGV_TOTAL_LEN:
            violations.append(make_warning(OVERFLOW_ARGV, spec_id, "total-len", str(total_len)))
    if violations_overflow:
        # Retain only the controlled overflow marker (truncate real violations).
        violations = violations[:MAX_VIOLATIONS - 1] + [make_warning(OVERFLOW_VIOLATIONS)]
    return violations


# ── Derived bundle-path validation (prevent traversal / external symlink) ───────
def validate_bundle_paths(
    matrix: Sequence[CommandSpec], out_dir: str
) -> List[str]:
    """Reject derived log/report/artifact paths that escape the bundle.

    Every ``log_name`` (resolved under ``commands/``), ``report_path``, and
    ``required_artifacts`` entry is resolved via ``os.path.realpath`` and must
    stay inside ``out_dir``.  A ``..`` traversal or a symlink whose target lies
    outside the bundle is rejected so the capture tool never reads from or
    writes to a location outside the evidence bundle.  Each violation is returned
    as a bounded ``invalid-bundle-path:<id>:<rel>`` warning so the capture fails
    closed.  Violation collection is itself bounded **during iteration** (mirroring
    ``validate_command_matrix``): once ``MAX_VIOLATIONS`` is reached the loops stop
    materializing violations and only the controlled ``OVERFLOW_VIOLATIONS``
    marker is retained, so an unbounded malformed matrix / artifact list can never
    inflate the returned violation set.
    """
    violations: List[str] = []
    violations_overflow = False
    for idx, spec in enumerate(matrix):
        # Stop materializing violations once the finite bound is reached; retain
        # only the controlled overflow marker.
        if violations_overflow or len(violations) >= MAX_VIOLATIONS:
            violations_overflow = True
            break
        # Validate the injected spec type *before* any attribute access so a
        # non-``CommandSpec`` entry fails closed rather than raising.
        if not isinstance(spec, CommandSpec):
            violations.append(make_warning(INVALID_MATRIX_SPEC, str(idx)))
            continue
        # Validate nested field types *before* any ``os.path`` operation so a
        # malformed non-string value cannot crash the validator (``TypeError`` on
        # ``os.path.join`` / ``os.path.realpath``) or be resolved unsafely.  A
        # non-string ``log_name`` / ``report_path`` fails closed with a bounded
        # ``invalid-bundle-path`` warning; a non-sequence ``required_artifacts`` is
        # skipped defensively.  This mirrors the type checks in
        # ``validate_command_matrix`` so the capture fails closed (zero runner calls)
        # rather than raising on a malformed matrix.
        if not isinstance(spec.log_name, str) or not spec.log_name:
            violations.append(make_warning("invalid-bundle-path", spec.id, "<empty-or-non-string-log-name>"))
            continue
        report_path = spec.report_path
        report_path_ok = isinstance(report_path, str) and bool(report_path)
        if report_path is not None and not report_path_ok:
            violations.append(make_warning("invalid-bundle-path", spec.id, "<empty-or-non-string-report-path>"))
        # A hostile log_name (backslash / UNC / drive / absolute / traversal) is
        # rejected outright; the offending value is reduced to a bounded marker.
        if _is_hostile_path_value(spec.log_name):
            violations.append(make_warning("invalid-bundle-path", spec.id, _sanitize_warning_token(spec.log_name)))
        else:
            log_rel = os.path.join("commands", spec.log_name)
            if not _bundle_path_contained(log_rel, out_dir):
                violations.append(make_warning("invalid-bundle-path", spec.id, _sanitize_warning_token(spec.log_name)))
        if report_path_ok:
            if _is_hostile_path_value(report_path):
                violations.append(make_warning("invalid-bundle-path", spec.id, _sanitize_warning_token(report_path)))
            elif not _bundle_path_contained(report_path, out_dir):
                violations.append(make_warning("invalid-bundle-path", spec.id, _sanitize_warning_token(report_path)))
        required_artifacts = spec.required_artifacts
        if not isinstance(required_artifacts, (list, tuple)):
            continue
        for art in required_artifacts:
            # Bound the inner artifact iteration too: a hostile spec carrying an
            # unbounded malformed artifact list stops materializing violations at
            # the finite bound instead of appending one warning per entry.
            if violations_overflow or len(violations) >= MAX_VIOLATIONS:
                violations_overflow = True
                break
            if not isinstance(art, str):
                continue
            if _is_hostile_path_value(art):
                violations.append(make_warning("invalid-bundle-path", spec.id, _sanitize_warning_token(art)))
            elif not _bundle_path_contained(art, out_dir):
                violations.append(make_warning("invalid-bundle-path", spec.id, _sanitize_warning_token(art)))
    if violations_overflow:
        # Retain only the controlled overflow marker (truncate real violations).
        violations = violations[:MAX_VIOLATIONS - 1] + [make_warning(OVERFLOW_VIOLATIONS)]
    return violations


# ── Required-artifact type + hashing ───────────────────────────────────────────
def _resolve_artifact_kind(art: str, spec: "CommandSpec") -> Optional[str]:
    """Return the explicit artifact kind for ``art`` from ``spec.artifact_kinds``.

    Dot-in-basename inference is intentionally absent: the kind must be supplied
    as explicit metadata.  Returns ``None`` when the kind is missing or not one
    of ``"file"`` / ``"dir"``, which the caller treats as a fail-closed
    ``missing-artifact-kind`` condition.
    """
    kind_map = dict(spec.artifact_kinds)
    kind = kind_map.get(art)
    if kind not in ("file", "dir"):
        return None
    return kind


def hash_artifact(path: str, kind: str, bundle_root: Optional[str] = None) -> Optional[str]:
    """SHA-256 of a required artifact.

    For a file, the file contents.  For a directory, a deterministic recursive
    hash over every contained *regular* file (stable file order, rel-path
    prefixed) so the whole subtree (logs/reports/summary) is covered by a single
    digest.

    A symlink artifact *root* is rejected outright (returns ``None``) so the
    capture never follows a symlink into content that may live outside the
    evidence bundle.  Symlinks are never followed: a symlink file is skipped, and
    a nested symlink whose resolved target escapes the artifact directory (and
    therefore the bundle) is skipped.  When ``bundle_root`` is supplied, every
    file is re-checked (TOCTOU-safe) against both the artifact root and the
    bundle root before it is opened or hashed, so containment is enforced at the
    moment of hashing, not only at an earlier path check.
    """
    # Reject a symlink artifact root: never follow a symlink into outside content.
    if os.path.islink(path):
        return None
    if kind == "dir":
        if not os.path.isdir(path):
            return None
        path_real = os.path.realpath(path)
        # Recheck the resolved artifact root against the bundle root before walking:
        # a directory whose resolved root escapes the bundle is rejected.
        if bundle_root is not None and not _is_within_realpath(path_real, os.path.realpath(bundle_root)):
            return None
        h = hashlib.sha256()
        files: List[str] = []
        for dirpath, _dirs, names in os.walk(path):
            for name in names:
                f = os.path.join(dirpath, name)
                # Never follow a symlink: skip it so an external/escaping target
                # is never read or hashed.
                if os.path.islink(f):
                    continue
                f_real = os.path.realpath(f)
                # Skip any regular file whose resolved path escapes the artifact
                # directory (a nested symlink whose target lies outside the
                # bundle).  Only contained regular files are hashed.
                if not _is_within_realpath(f_real, path_real):
                    continue
                # Recheck containment against the bundle root before opening/hashing
                # (TOCTOU-safe): a file that escapes the bundle is never read.
                if bundle_root is not None and not _is_within_realpath(f_real, os.path.realpath(bundle_root)):
                    continue
                files.append(f)
        for f in sorted(files):
            rel = os.path.relpath(f, path)
            h.update(rel.encode("utf-8"))
            h.update(b"\0")
            # Race-safe read: a file that became a symlink / non-regular / changed
            # mid-read is rejected, so the artifact hash fails closed rather than
            # hashing inconsistent or untrusted content.
            fb = _race_safe_read_bytes(f)
            if fb is None:
                return None
            h.update(fb)
        return h.hexdigest()
    if not os.path.isfile(path):
        return None
    # Recheck containment against the bundle root before opening the file
    # (TOCTOU-safe): a file that escapes the bundle is never read or hashed.
    if bundle_root is not None and not _is_within_realpath(os.path.realpath(path), os.path.realpath(bundle_root)):
        return None
    return _race_safe_hash_file(path)


# ── Run one command ───────────────────────────────────────────────────────────
def run_command(
    spec: CommandSpec,
    out_dir: str,
    root: str,
    runner: Callable[[Sequence[str], str], RunOutcome],
) -> CommandResult:
    """Execute ``spec`` and capture its evidence record (PR-GR-00R part B).

    The combined stdout/stderr stream is persisted through ``_CommandLogSink``:
    sanitized chunks are appended to a sibling temporary log which is fsync'd
    and ``os.replace``d onto the final name on completion.  The resulting log
    is in exactly one of two states:

    * ``COMPLETE`` — the entire stream fit within ``CHILD_OUTPUT_LIMIT``, was
      published atomically, and was read back + hashed (``log_complete=True``,
      ``log_sha256`` set, ``log_failure_code=None``); or
    * ``INCOMPLETE`` — the cap was exceeded (``output-limit-exceeded``), a
      write failed (``log-write-failed``), or the finished artifact could not
      be read back/hashed (``log-unreadable``).  The partial artifact stays on
      disk flagged via ``log_complete=False``, its hash is omitted
      (``log_sha256=""``), and the caller fails the whole capture closed
      (exit 2).  A capture never exits 0 with a silently truncated log.

    The production runner streams the live merged child pipe; an injected
    runner's returned text is fed through the identical chunked sink so both
    paths share one persistence contract.  The child exit code is preserved in
    every state — it is an observation, never altered by log completeness.
    """
    bundle_rel = _posix_rel(out_dir, root)
    log_rel = "/".join([bundle_rel, "commands", spec.log_name])
    log_abs = os.path.join(out_dir, "commands", spec.log_name)

    # Reject a log path that escapes the bundle (``..`` traversal or a symlink
    # whose target lies outside).  Never write outside the bundle, and never run
    # the child command — its output would otherwise land outside via the same
    # escaped path.  The up-front ``validate_bundle_paths`` pass records the
    # bounded ``invalid-bundle-path`` warning; here we defensively skip the write.
    if not _bundle_path_contained(os.path.join("commands", spec.log_name), out_dir):
        return CommandResult(
            id=_sanitize_command_id(spec.id),
            argv=list(spec.argv),
            cwd=".",
            start_utc=_utc_now(),
            end_utc=_utc_now(),
            elapsed_ms=0,
            exit_code=None,
            log_path=log_rel,
            log_sha256="",
            # No log was attempted (pre-execution rejection): incomplete with
            # no log-capture code; ``launch_error`` carries the escape reason.
            log_bytes=0,
            log_complete=False,
            log_failure_code=None,
            report_path=None,
            report_sha256=None,
            report_schema_version=None,
            report_trusted=None,
            report_diagnostic_codes=[],
            report_finding_count=None,
            parser_error=None,
            launch_error="BUNDLE_PATH_ESCAPE",
        )

    start = time.time()
    start_utc = _utc_now()
    launch_error: Optional[str] = None
    exit_code: Optional[int] = None

    # Open the atomic temp log BEFORE executing the child (PR-GR-00R part B):
    # a command whose log cannot be captured is never executed (no
    # uncapturable side effects) and fails closed with ``log-write-failed``.
    sink = _CommandLogSink(log_abs)
    if sink.open():
        if runner is subprocess_runner:
            # Production path: stream the live merged child pipe straight into
            # the atomic log sink (incremental reads, bounded memory, always
            # drained so the child can never block on a full pipe).
            try:
                exit_code = streaming_subprocess_runner(
                    list(spec.argv), root, sink.sink)
            except Exception:
                # Ordinary launch failures (e.g. OSError / FileNotFoundError for
                # a missing executable) become a controlled ``LAUNCH_FAILED``
                # outcome that fails the capture closed below.  The empty log is
                # still published and hashed: zero output was produced, so the
                # (empty) stream was captured completely.
                # KeyboardInterrupt / SystemExit derive directly from
                # ``BaseException`` (not ``Exception``) and still propagate
                # unchanged.
                launch_error = "LAUNCH_FAILED"
        else:
            # Injected-runner path (tests / alternative engines): the runner
            # returns the full combined text; it is fed through the IDENTICAL
            # chunked sink so both paths share one persistence contract.
            try:
                outcome = runner(list(spec.argv), root)
                exit_code = getattr(outcome, "returncode", None)
                combined = getattr(outcome, "combined", "") or ""
                for i in range(0, len(combined), LOG_STREAM_CHUNK_CHARS):
                    sink.sink(combined[i:i + LOG_STREAM_CHUNK_CHARS])
            except Exception:
                launch_error = "LAUNCH_FAILED"
    end_utc = _utc_now()
    elapsed_ms = int((time.time() - start) * 1000)

    # Flush, fsync, close, and atomically publish the temp log onto the final
    # name.  Never raises; any publish failure is flagged ``log-write-failed``.
    sink.finish()
    failure_code = sink.failure_code
    log_bytes = sink.published_bytes

    # Hash ONLY a complete log (PR-GR-00R part B): a partial artifact is never
    # hashed as valid evidence — its hash is omitted (``log_sha256=""``).
    log_sha256 = ""
    if failure_code is None:
        hashed = _race_safe_hash_file(log_abs)
        if hashed is None:
            # The finished artifact could not be read back/hashed (symlink,
            # non-regular file, read error, replaced/changed mid-read): the log
            # is INCOMPLETE with ``log-unreadable`` and fails the capture closed.
            failure_code = LOG_UNREADABLE
        else:
            log_sha256 = hashed
    log_complete = failure_code is None

    report_path_rel: Optional[str] = None
    report_sha256: Optional[str] = None
    parsed = {
        "schema_version": None,
        "trusted": None,
        "diagnostic_codes": [],
        "finding_count": None,
        "parser_error": None,
    }
    if spec.report_path is not None:
        report_path_rel = "/".join([bundle_rel, spec.report_path])
        report_abs = os.path.join(out_dir, spec.report_path)
        if not _bundle_path_contained(spec.report_path, out_dir):
            # A report path that escapes the bundle is never read (external
            # symlink / traversal read).  Mark it as an escape, not a parse error.
            parsed = {
                "schema_version": None,
                "trusted": None,
                "diagnostic_codes": [],
                "finding_count": None,
                "parser_error": "REPORT_PATH_ESCAPES_BUNDLE",
            }
        elif os.path.isdir(report_abs):
            # A required report path that resolves to a directory is invalid and
            # must fail closed (never parsed as a report file).
            parsed = {
                "schema_version": None,
                "trusted": None,
                "diagnostic_codes": [],
                "finding_count": None,
                "parser_error": "REPORT_PATH_IS_DIRECTORY",
            }
        elif os.path.isfile(report_abs):
            # Read the report once and use the SAME byte snapshot for both the hash
            # and the parse, so the evidence can never combine a hash of one report
            # version with a parse of another (TOCTOU / replacement).  A read/hash
            # failure (symlink / non-regular / replaced / changed file) fails the
            # capture closed via ``parser_error`` and leaves ``report_sha256`` None.
            raw = _race_safe_read_bytes(report_abs)
            if raw is None:
                report_sha256 = None
                parsed = {
                    "schema_version": None,
                    "trusted": None,
                    "diagnostic_codes": [],
                    "finding_count": None,
                    "parser_error": "REPORT_HASH_FAILED",
                }
            else:
                report_sha256 = sha256_bytes(raw)
                parsed = parse_v2_report(report_abs, raw=raw)
        else:
            # Defensive (N-1): a declared report that is neither a regular file
            # nor a directory is simply absent.  Record a controlled
            # ``MISSING_REPORT`` parser error instead of silently leaving
            # ``parser_error`` None, so the invalid-required-report gate
            # observes the absence and the capture fails closed rather than
            # recording a phantom "no report" success for a declared output.
            parsed = {
                "schema_version": None,
                "trusted": None,
                "diagnostic_codes": [],
                "finding_count": None,
                "parser_error": "MISSING_REPORT",
            }

    return CommandResult(
        id=_sanitize_command_id(spec.id),
        # Custom argv tokens are never persisted verbatim: secrets and absolute
        # paths are redacted and the token is length-bounded.  The default
        # (trusted) matrix contains no such tokens, so this is a no-op there.
        argv=[_sanitize_argv_token(t) for t in spec.argv],
        cwd=".",
        start_utc=start_utc,
        end_utc=end_utc,
        elapsed_ms=elapsed_ms,
        exit_code=exit_code,
        log_path=log_rel,
        log_sha256=log_sha256,
        log_bytes=log_bytes,
        log_complete=log_complete,
        log_failure_code=failure_code,
        report_path=report_path_rel,
        report_sha256=report_sha256,
        report_schema_version=parsed["schema_version"],
        report_trusted=parsed["trusted"],
        report_diagnostic_codes=parsed["diagnostic_codes"],
        report_finding_count=parsed["finding_count"],
        parser_error=parsed["parser_error"],
        launch_error=launch_error,
    )


# ── Infrastructure warnings (missing referenced test files) ───────────────────
def collect_infrastructure_warnings(matrix: Sequence[CommandSpec], root: str) -> List[str]:
    """Record referenced .py test files that do not exist at the tested SHA."""
    warnings: List[str] = []
    for spec in matrix:
        # Skip malformed matrix entries defensively; the validator already fails
        # closed on them before any runner call.
        if not isinstance(spec, CommandSpec):
            continue
        if spec.id != "focused-python-tests":
            continue
        argv = spec.argv if isinstance(spec.argv, (list, tuple)) else ()
        for token in argv:
            if not isinstance(token, str) or token.startswith("-"):
                continue
            if token.endswith(".py") and not os.path.isfile(os.path.join(root, token)):
                warnings.append(make_warning("missing-test-file", token))
    return warnings


# ── Semantic summary (deterministic) ──────────────────────────────────────────
def _normalize_semantic_argv(token: str, bundle_rel: str) -> str:
    """Normalize a run-specific bundle output path inside an argv token.

    The command matrix embeds repository-relative bundle output paths both as bare
    tokens (``<bundle_rel>/02-room-inventory.findings.json``) and in equals form
    (``--findings-output=<bundle_rel>/02-room-inventory.findings.json``).
    ``bundle_rel`` contains the run id (``build/guard-evidence/<sha>/<run-id>``),
    which differs between runs, so two clean captures at the same SHA would
    otherwise produce different ``semantic-summary.json`` files.  We replace the
    run-specific ``bundle_rel`` prefix (or its equals-form value) with a stable
    ``<bundle>/`` marker so the semantic summary stays byte-identical across runs.
    Tokens that do not reference the bundle output directory are returned unchanged.
    """
    if not (isinstance(token, str) and bundle_rel):
        return token
    # Equals form: ``--key=<value>`` or ``key=<value>`` — normalize only the value
    # portion so flag tokens keep their prefix while the run-specific path is masked.
    if "=" in token:
        key, sep, value = token.partition("=")
        norm = _normalize_bundle_prefix(value, bundle_rel)
        if norm == value:
            return token
        return f"{key}{sep}{norm}"
    return _normalize_bundle_prefix(token, bundle_rel)


def _normalize_bundle_prefix(value: str, bundle_rel: str) -> str:
    """Replace every run-specific ``bundle_rel`` path segment in ``value`` with ``<bundle>``.

    The bundle path (``build/guard-evidence/<sha>/<run-id>``) differs between runs,
    so any occurrence must be masked to the stable ``<bundle>`` marker so the
    semantic summary stays byte-identical across runs.  The mask applies whether
    the run-specific path appears as a bare token, a prefix, an equals-form value
    (e.g. ``--output=<bundle>/x.json`` or ``prefix=<bundle>``), or embedded in the
    middle of a larger path (e.g. ``some/dir/<bundle>/nested``).  Only complete
    path segments are replaced — a segment boundary is start-of-string or ``/`` on
    both sides — so a longer component that merely contains ``bundle_rel`` as a
    substring (e.g. ``out/run-10`` when ``bundle_rel`` is ``out/run-1``) is left
    untouched and never accidentally masked.
    """
    if not (isinstance(value, str) and bundle_rel):
        return value
    # Match ``bundle_rel`` only when bounded by a path separator or string edge on
    # both sides, then keep the leading separator (``^`` contributes nothing) and
    # substitute the stable ``<bundle>`` marker.
    pattern = re.compile(r"(^|/)" + re.escape(bundle_rel) + r"(?=/|$)")
    return pattern.sub(r"\1<bundle>", value)


def build_semantic_summary(
    commit: Optional[str],
    tree: Optional[str],
    trusted: bool,
    preservation: Dict[str, Any],
    git_state: Dict[str, Any],
    input_manifest_sha256: str,
    commands: Sequence[CommandResult],
    bundle_rel: str = "",
) -> Dict[str, Any]:
    """Build a deterministic summary that excludes volatile fields.

    Run-specific bundle output paths embedded in command ``argv`` are normalized
    (see ``_normalize_semantic_argv``) so two clean runs at the same SHA compare
    equal.

    Per-command log volume metadata (``log_bytes`` / ``log_complete`` /
    ``log_failure_code``, PR-GR-00R part B) is deliberately EXCLUDED here:
    byte counts vary between runs (child verbosity/timing), and log
    completeness is already reflected in the top-level ``trusted`` flag (any
    incomplete log fails the capture closed).  Including them would break the
    byte-identical same-SHA comparison; they appear only in ``evidence.json``.
    """
    semantic_commands = []
    for c in commands:
        semantic_commands.append({
            "id": c.id,
            "argv": [_normalize_semantic_argv(t, bundle_rel) for t in c.argv],
            "exit_code": c.exit_code,
            "launch_error": c.launch_error,
            "report_schema_version": c.report_schema_version,
            "report_trusted": c.report_trusted,
            "report_diagnostic_codes": c.report_diagnostic_codes,
            "report_finding_count": c.report_finding_count,
            "parser_error": c.parser_error,
        })
    # OS / OSTYPE are environment-derived and must be sanitized (absolute paths /
    # secrets redacted) and length-bounded before persistence, exactly like the
    # allowlisted interpreter version fields.  An unusual OS/OSTYPE value can never
    # leak raw machine/path content into semantic-summary.json.
    versions = {
        "python_version": git_state.get("python_version"),
        "python3_version": git_state.get("python3_version"),
        "java_version": git_state.get("java_version"),
        "gradle_version": git_state.get("gradle_version"),
        "os": _bounded(os.environ.get("OS"), 256),
        "ostype": _bounded(os.environ.get("OSTYPE"), 256),
    }
    return {
        "schema": SEMANTIC_SCHEMA,
        "commit": commit,
        "tree": tree,
        # Run-pin metadata (PR-GR-00R part A): the caller-stated pin is
        # run-invariant (same caller, same SHA, same value), so it may appear in
        # the deterministic summary without breaking byte-identical comparison
        # across two runs at the same SHA.  Nothing run-specific leaks here.
        "requested_sha": git_state.get("requested_sha"),
        "trusted": trusted,
        "preservation_ok": bool(preservation.get("ok", False)),
        "versions": versions,
        "input_manifest_sha256": input_manifest_sha256,
        "commands": semantic_commands,
    }


# ── Human-readable summary ─────────────────────────────────────────────────────
def build_summary_markdown(
    bundle_rel: str,
    commit: Optional[str],
    tree: Optional[str],
    trusted: bool,
    dirty: bool,
    allow_dirty: bool,
    preservation: Dict[str, Any],
    commands: Sequence[CommandResult],
    infrastructure_warnings: Sequence[str],
) -> str:
    lines: List[str] = []
    lines.append("# DB Guard Evidence Summary")
    lines.append("")
    lines.append(f"- Bundle: `{bundle_rel}`")
    lines.append(f"- Commit: `{commit}`")
    lines.append(f"- Tree: `{tree}`")
    lines.append(f"- Trusted: `{trusted}`")
    lines.append(f"- Dirty checkout: `{dirty}` (allow-dirty={allow_dirty})")
    lines.append(f"- Preservation ok: `{preservation.get('ok')}`")
    lines.append("")
    lines.append("## Commands")
    lines.append("")
    lines.append("| ID | Exit | Launch | Report trusted | Diagnostics | Findings | Parser |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for c in commands:
        diag = ",".join(c.report_diagnostic_codes) or "-"
        lines.append(
            f"| {c.id} | {c.exit_code} | {c.launch_error or '-'} | "
            f"{c.report_trusted} | {diag} | {c.report_finding_count} | "
            f"{c.parser_error or '-'} |"
        )
    if infrastructure_warnings:
        lines.append("")
        lines.append("## Infrastructure warnings")
        lines.append("")
        for w in infrastructure_warnings:
            lines.append(f"- {w}")
    lines.append("")
    lines.append(
        "NOTE: This evidence bundle is diagnostic-only. A blocked DB gate is an "
        "observed status, not a failure of this capture tool. The DB gate "
        "remains blocked pending GR-01 onward."
    )
    lines.append("")
    result = "\n".join(lines)
    # Bound the persisted summary markdown so an unbounded payload cannot reach the
    # evidence bundle.  Fail closed by truncation with a controlled marker.
    if len(result) > MAX_SUMMARY_CHARS:
        result = result[:MAX_SUMMARY_CHARS] + "<truncated>"
    return result


# ── Post-matrix identity re-check (PR-GR-00R part A) ──────────────────────────
def post_capture_identity_check(
    root: str,
    runner: Callable[[Sequence[str], str], RunOutcome],
    expected_head: Optional[str],
    expected_tree: Optional[str],
    expected_status: str,
) -> Tuple[bool, str]:
    """Re-observe HEAD / tree / status after the matrix and detect drift.

    Returns ``(ok, surface)`` where ``surface`` names the first drifting or
    unverifiable observation (``"head"``, ``"tree"``, ``"status"``, or
    ``"unverifiable"`` when a re-check command itself failed) and is ``""``
    when every observation still matches the preflight state.  Any drift or
    observation failure fails the capture closed: the caller marks the bundle
    incomplete/untrusted (exit 2).  Status output is sanitized exactly like the
    preflight record before comparison, so both sides are deterministic.
    """
    try:
        head = runner(["git", "rev-parse", "HEAD"], root)
        tree = runner(["git", "rev-parse", "HEAD^{tree}"], root)
        status = runner(["git", "status", "--porcelain=v1"], root)
    except Exception:
        return False, "unverifiable"
    head_raw = head.combined.strip()
    tree_raw = tree.combined.strip()
    if (head.returncode != 0 or not _is_valid_sha40(head_raw)
            or head_raw != expected_head):
        return False, "head"
    if (tree.returncode != 0 or not _is_valid_sha40(tree_raw)
            or tree_raw != expected_tree):
        return False, "tree"
    if status.returncode != 0:
        return False, "unverifiable"
    if _sanitize_git_filenames(status.combined, is_status=True) != expected_status:
        return False, "status"
    return True, ""


# ── Main capture routine ───────────────────────────────────────────────────────
def capture_evidence(
    root: str,
    out_dir: str,
    *,
    allow_dirty: bool = False,
    expected_sha: Optional[str] = None,
    runner: Optional[Callable[[Sequence[str], str], RunOutcome]] = None,
    command_matrix: Optional[Sequence[CommandSpec]] = None,
    input_candidates: Optional[Sequence[str]] = None,
) -> int:
    """Capture a reproducible DB guard evidence bundle at a caller-pinned SHA.

    Returns ``0`` on a complete, trusted-or-allowed capture; ``2`` when the
    capture is incomplete, dirty (without ``--allow-dirty``), the output escapes
    the repository root (including via symlink), the run pin is missing or not
    exactly 40 lowercase hex (a controlled failure before any command is issued),
    the observed HEAD does not equal the caller-stated ``expected_sha`` pin
    (fails closed pre-launch — no matrix command starts), HEAD/tree/status
    drifted while the matrix ran (post-matrix re-check marks the bundle
    incomplete/untrusted), preflight git identity could not be resolved, a
    required input is missing, a required report is present but invalid (or is a
    directory), a required artifact is missing or of the wrong type, a derived
    log/report/artifact path escapes the bundle (traversal or external symlink),
    a custom command matrix embeds an absolute/outside path, or a command
    suffered a launch failure.  Never returns ``1`` for a child command's
    nonzero exit.  The expected SHA is never derived silently from HEAD — the
    caller must state it.
    """
    runner = runner or subprocess_runner
    root = os.path.realpath(root)
    out_dir = os.path.realpath(out_dir)

    # ── Output containment (fail closed on traversal / outside-root / symlink) ──
    if not _is_within_realpath(out_dir, root):
        # Reject an output bundle that escapes the repository root, including a
        # symlink whose resolved target lives outside the root.  No bundle is
        # written.
        return 2

    bundle_rel = _posix_rel(out_dir, root)
    commands_dir = os.path.join(out_dir, "commands")
    os.makedirs(commands_dir, exist_ok=True)

    capture_failed = False
    infrastructure_warnings: List[str] = []
    required_artifact_hashes: Dict[str, str] = {}

    # ── Run-pin validation (PR-GR-00R part A) — before ANY runner invocation ────
    # The expected SHA is caller-supplied and mandatory; there is no hard-coded
    # target and it is never derived from HEAD.  A missing or syntactically
    # invalid pin (not exactly 40 lowercase hex) is a controlled failure before
    # any command — git probe included — is issued.
    pin_ok = expected_sha is not None and _is_valid_sha40(expected_sha)
    if not pin_ok:
        capture_failed = True
        infrastructure_warnings.append(make_warning(
            "missing-expected-sha" if expected_sha is None
            else "invalid-expected-sha"))

    # ── Compute the command matrix early ─────────────────────────────────────────
    matrix = list(command_matrix) if command_matrix is not None else default_command_matrix(root, out_dir)

    # ── Validate command matrix + bundle paths BEFORE any runner invocation ───────
    # The validation must run before preflight, input-manifest Git calls, and the
    # preservation check so a hostile or malformed matrix never triggers a single
    # runner call (no git identity probe, no input-manifest read, no preservation
    # diff).  A failure here fails the capture closed with no child command run.
    matrix_validation_failed = False
    # The CommandSpec pre-runner validates EVERY matrix (default + custom) before
    # any runner call, so a malformed/empty/non-string/hostile argv is rejected
    # even for the trusted default matrix (defense in depth).  A failure here fails
    # the capture closed with no child command run.
    for violation in validate_command_matrix(matrix, root):
        capture_failed = True
        matrix_validation_failed = True
        infrastructure_warnings.append(violation)
    for violation in validate_bundle_paths(matrix, out_dir):
        capture_failed = True
        matrix_validation_failed = True
        infrastructure_warnings.append(violation)

    if matrix_validation_failed or not pin_ok:
        # Stop before any runner call: no preflight, no input-manifest Git calls,
        # no preservation diff, no command execution.  The capture fails closed
        # with an empty git state / manifest / preservation / command set.  The
        # run-pin metadata is still recorded (schema-stable); an invalid or
        # missing pin value itself is never persisted verbatim.
        git_state: Dict[str, Any] = {
            "requested_sha": expected_sha if pin_ok else None,
            "observed_sha": None,
            "tree_sha": None,
        }
        env_state = collect_environment()
        manifest: List[Dict[str, Any]] = []
        manifest_hash = input_manifest_hash([])
        preservation = _default_preservation_result()
        dirty = False
        untrusted = True
        results: List[CommandResult] = []
    else:
        # ── Preflight + dirty gate ────────────────────────────────────────────────
        git_state = run_preflight(root, runner)
        # Record the run pin and the observed identity (PR-GR-00R part A): the
        # requested pin, the observed HEAD, and the observed tree SHA travel
        # with the git state into git-state.json and evidence.json.
        git_state["requested_sha"] = expected_sha
        git_state["observed_sha"] = git_state.get("commit")
        git_state["tree_sha"] = git_state.get("tree")
        env_state = collect_environment()
        dirty = bool((git_state.get("status") or "").strip())
        if dirty and not allow_dirty:
            # Reject a dirty checkout by default.  No bundle is written.
            return 2

        untrusted = dirty  # dirty + --allow-dirty is captured but untrusted

        # Fail closed when the essential git identity could not be resolved.
        if not git_state.get("preflight_ok", False):
            capture_failed = True
            infrastructure_warnings.append(make_warning("preflight-failed"))

        # ── Run-pin gate: requested vs observed HEAD, BEFORE any matrix command ───
        # The caller-stated pin must equal the observed HEAD exactly.  An
        # unresolved identity (None) can never equal the pin, so a failed
        # preflight also stops the launch here.  On mismatch the capture fails
        # closed PRE-LAUNCH: the observed git state is still recorded, but no
        # matrix command is ever started against an unpinned commit.
        sha_gate_failed = git_state.get("observed_sha") != expected_sha
        if sha_gate_failed:
            capture_failed = True
            infrastructure_warnings.append(
                make_warning("wrong-sha", str(git_state.get("observed_sha"))))

        # Fail closed when any preflight git metadata command (status / diff /
        # staged-diff) failed.  An unobservable checkout state cannot be trusted, so
        # the capture must not proceed and must not record a possibly-stale git state
        # as authoritative.
        if not git_state.get("git_meta_ok", False):
            capture_failed = True
            failures = git_state.get("git_meta_failures") or ["status", "diff", "staged-diff"]
            infrastructure_warnings.append(make_warning("git-meta-failed", ",".join(failures)))

        # ── Input manifest (built dynamically from tracked files) ──────────────────
        candidates = (
            list(input_candidates)
            if input_candidates is not None
            else discover_input_candidates(root, runner)
        )
        manifest = collect_input_manifest(root, candidates, runner)
        manifest_hash = input_manifest_hash(manifest)

        # Fail closed when a required input is missing (cannot hash/observe it) or
        # when its Git blob ID is forged/missing (an untrusted or uncommitted input
        # must never be treated as a valid, hashable source of record).
        for entry in manifest:
            if not entry["exists"]:
                capture_failed = True
                infrastructure_warnings.append(
                    make_warning("missing-required-input", entry["rel_path"]))
            elif not entry["blob_id"]:
                capture_failed = True
                infrastructure_warnings.append(
                    make_warning("missing-blob-id", entry["rel_path"]))

        # Bound the persisted input-manifest collection: an unbounded or hostile
        # candidate set must not inflate the evidence bundle.  Fail closed with a
        # controlled overflow marker AND truncate the persisted manifest so no more
        # than MAX_MANIFEST_ENTRIES entries are materialized.  The bound is enforced
        # during iteration inside ``collect_input_manifest``; here we detect the
        # truncation (a candidate set larger than the bound) and retain only the
        # controlled overflow marker.
        if len(candidates) > MAX_MANIFEST_ENTRIES or len(manifest) > MAX_MANIFEST_ENTRIES:
            capture_failed = True
            manifest = manifest[:MAX_MANIFEST_ENTRIES]
            manifest_hash = input_manifest_hash(manifest)
            infrastructure_warnings.append(make_warning("OVERFLOW_MANIFEST"))

        # ── Preservation check ─────────────────────────────────────────────────────
        preservation = preservation_check(root, runner)
        if not preservation.get("ok", False):
            untrusted = True

        # ── Run commands (only when the run-pin gate passed) ──────────────────────
        # PR-GR-00R part A: a pin/HEAD mismatch blocks the launch entirely — no
        # matrix command, artifact hash, or report validation is performed
        # against an unpinned commit.  Every derived stage below then no-ops on
        # the empty executable matrix; the read-only git observations recorded
        # above (manifest / preservation) stay in the bundle.
        executable_matrix: List[CommandSpec] = [] if sha_gate_failed else matrix
        results = []
        for spec in executable_matrix:
            try:
                res = run_command(spec, out_dir, root, runner)
            except Exception:
                # An ordinary internal failure while running/recording one command
                # must not crash the whole capture: record a controlled
                # ``LAUNCH_FAILED`` outcome (the bounded reason code is the
                # diagnostic) so the bundle stays complete and the capture fails
                # closed below.  KeyboardInterrupt / SystemExit derive directly
                # from ``BaseException`` and still propagate unchanged.  Matrix
                # validation already ran, so ``spec`` fields are well-formed here.
                res = CommandResult(
                    id=_sanitize_command_id(spec.id),
                    argv=[_sanitize_argv_token(t) for t in spec.argv],
                    cwd=".",
                    start_utc=_utc_now(),
                    end_utc=_utc_now(),
                    elapsed_ms=0,
                    exit_code=None,
                    log_path="/".join([bundle_rel, "commands", spec.log_name]),
                    log_sha256="",
                    # No log was attempted (internal failure before capture):
                    # incomplete with no log-capture code; ``launch_error``
                    # carries the controlled reason.
                    log_bytes=0,
                    log_complete=False,
                    log_failure_code=None,
                    report_path=None,
                    report_sha256=None,
                    report_schema_version=None,
                    report_trusted=None,
                    report_diagnostic_codes=[],
                    report_finding_count=None,
                    parser_error=None,
                    launch_error="LAUNCH_FAILED",
                )
            results.append(res)
            if res.launch_error is not None:
                capture_failed = True
            # PR-GR-00R part B: an INCOMPLETE command log (cap exceeded, log
            # write failure, or unreadable finished artifact) fails the whole
            # capture closed — the tool never exits 0 with a silently truncated
            # or unhashed log.  The diagnostic carries the sanitized command id
            # plus a controlled ``LOG_FAILURE_CODES`` constant only.
            if res.log_failure_code is not None:
                capture_failed = True
                infrastructure_warnings.append(make_warning(
                    "incomplete-command-log", res.id, res.log_failure_code))

        # ── Required-artifact presence + type validation + hashing ─────────────────
        # The collected hash set is bounded by ``MAX_REQUIRED_ARTIFACT_HASHES``.  The
        # loop stops materializing hashes once the finite aggregate limit is reached
        # and fails closed with a controlled ``OVERFLOW_REQUIRED_ARTIFACT_HASHES``
        # marker, so an unbounded custom matrix can never inflate the evidence bundle
        # with unbounded hashes (never materialize more than the bound).
        artifact_hash_overflow = False
        for spec in executable_matrix:
            if artifact_hash_overflow:
                break
            for art in spec.required_artifacts:
                if len(required_artifact_hashes) >= MAX_REQUIRED_ARTIFACT_HASHES:
                    artifact_hash_overflow = True
                    break
                # Never read or hash an artifact path that escapes the bundle
                # (traversal or external symlink).  The up-front ``validate_bundle_paths``
                # pass already flagged this and set ``capture_failed``; here we
                # defensively skip the read so nothing outside the bundle is touched.
                if not _bundle_path_contained(art, out_dir):
                    capture_failed = True
                    continue
                # Explicit artifact-kind metadata is required; dot-in-basename
                # inference is gone.  A missing/unknown kind fails closed.
                kind = _resolve_artifact_kind(art, spec)
                if kind is None:
                    capture_failed = True
                    infrastructure_warnings.append(
                        make_warning("missing-artifact-kind", spec.id, _sanitize_warning_token(art)))
                    continue
                abs_art = os.path.join(out_dir, art)
                # Reject a symlink artifact root: never follow a symlink into content
                # that may live outside the evidence bundle.  This is checked before
                # type/hash handling so a symlinked artifact fails closed.
                if os.path.islink(abs_art):
                    capture_failed = True
                    infrastructure_warnings.append(
                        make_warning("symlink-artifact", _sanitize_warning_token(art)))
                    continue
                # Type mismatch is checked before "missing" so a directory masquerading
                # as a required report file (or vice versa) is rejected unambiguously.
                if kind == "file" and os.path.isdir(abs_art):
                    capture_failed = True
                    infrastructure_warnings.append(make_warning("invalid-required-artifact-type", art))
                    continue
                if kind == "dir" and os.path.isfile(abs_art):
                    capture_failed = True
                    infrastructure_warnings.append(make_warning("invalid-required-artifact-type", art))
                    continue
                present = os.path.isfile(abs_art) if kind == "file" else os.path.isdir(abs_art)
                if not present:
                    capture_failed = True
                    infrastructure_warnings.append(make_warning("missing-required-artifact", art))
                    continue
                art_hash = hash_artifact(abs_art, kind, out_dir)
                if art_hash is None:
                    # A required artifact that is present and of the correct type but
                    # cannot be hashed (symlink swapped in, non-regular file, read
                    # error, or replaced/changed during the read) fails the capture
                    # closed with a controlled diagnostic rather than recording a
                    # partial/untrusted hash.
                    capture_failed = True
                    infrastructure_warnings.append(make_warning(ARTIFACT_HASH_FAILED, art))
                    continue
                required_artifact_hashes[art] = art_hash
        if artifact_hash_overflow:
            capture_failed = True
            infrastructure_warnings.append(make_warning(OVERFLOW_REQUIRED_ARTIFACT_HASHES))

        # ── Invalid required report (present but unparseable / a directory) ─────────
        for spec, res in zip(matrix, results):
            if spec.report_path is not None and res.parser_error is not None:
                capture_failed = True
                infrastructure_warnings.append(make_warning("invalid-required-report", spec.id))

        # ── Post-matrix identity re-check (PR-GR-00R part A) ──────────────────────
        # HEAD, tree, and status are re-observed after the matrix ran.  Any drift
        # (or an unverifiable post-state) marks the bundle incomplete/untrusted
        # and fails the capture (exit 2).  Skipped when the pin gate already
        # blocked the launch: nothing ran, so nothing can have drifted.
        if not sha_gate_failed:
            post_ok, post_surface = post_capture_identity_check(
                root, runner,
                git_state.get("observed_sha"), git_state.get("tree_sha"),
                git_state.get("status") or "",
            )
            if not post_ok:
                capture_failed = True
                untrusted = True
                infrastructure_warnings.append(
                    make_warning("post-capture-drift", post_surface))

    if not capture_failed:
        infrastructure_warnings.extend(collect_infrastructure_warnings(matrix, root))

    # ── Assemble evidence (first pass, pre-cap) ─────────────────────────────────
    # Sanitize every infrastructure warning so a hostile payload (e.g. a rejected
    # custom path token) never leaks raw machine/path content into the bundle.
    sanitized_warnings = [_sanitize_warning(w) for w in infrastructure_warnings]
    trusted = (not untrusted) and (not capture_failed)
    evidence = {
        "schema": EVIDENCE_SCHEMA,
        "captured_at_utc": _utc_now(),
        "tool": "capture_db_guard_evidence.py",
        "root": bundle_rel,
        "commit": git_state.get("commit"),
        "tree": git_state.get("tree"),
        # Run-pin metadata (PR-GR-00R part A): the caller-stated requested SHA,
        # the observed HEAD, and the observed tree SHA.  There is no hard-coded
        # target; the pin is never derived from HEAD.
        "requested_sha": git_state.get("requested_sha"),
        "observed_sha": git_state.get("observed_sha"),
        "tree_sha": git_state.get("tree_sha"),
        "trusted": trusted,
        "allow_dirty": bool(allow_dirty),
        "dirty": dirty,
        "preservation": preservation,
        "environment": env_state,
        "git_state": git_state,
        "input_manifest": manifest,
        "input_manifest_sha256": manifest_hash,
        "required_artifact_hashes": required_artifact_hashes,
        "commands": [vars(c) for c in results],
        "infrastructure_warnings": sanitized_warnings,
    }

    # ── Write the seven top-level outputs atomically ────────────────────────────
    written_outputs: List[str] = []
    atomic_write_json(os.path.join(out_dir, "git-state.json"), git_state)
    written_outputs.append(os.path.join(out_dir, "git-state.json"))
    atomic_write_json(os.path.join(out_dir, "environment.json"), env_state)
    written_outputs.append(os.path.join(out_dir, "environment.json"))
    atomic_write_json(os.path.join(out_dir, "input-manifest.json"), manifest)
    written_outputs.append(os.path.join(out_dir, "input-manifest.json"))

    # input-sha256.txt
    input_lines = []
    for entry in manifest:
        rel = entry["rel_path"]
        h = entry["sha256"] or "-"
        input_lines.append(f"{h}  {rel}")
    input_sha_text = "\n".join(input_lines) + "\n"
    atomic_write_text(os.path.join(out_dir, "input-sha256.txt"), input_sha_text)
    written_outputs.append(os.path.join(out_dir, "input-sha256.txt"))

    atomic_write_json(os.path.join(out_dir, "evidence.json"), evidence)
    written_outputs.append(os.path.join(out_dir, "evidence.json"))

    summary_md = build_summary_markdown(
        bundle_rel, git_state.get("commit"), git_state.get("tree"),
        trusted, dirty, allow_dirty, preservation, results, sanitized_warnings,
    )
    atomic_write_text(os.path.join(out_dir, "summary.md"), summary_md)
    written_outputs.append(os.path.join(out_dir, "summary.md"))

    semantic = build_semantic_summary(
        git_state.get("commit"), git_state.get("tree"), trusted,
        preservation, git_state, manifest_hash, results, bundle_rel,
    )
    atomic_write_json(os.path.join(out_dir, "semantic-summary.json"), semantic)
    written_outputs.append(os.path.join(out_dir, "semantic-summary.json"))

    # ── output-sha256: hash each top-level output; detect failures ──────────────
    # Each output is hashed race-safely; a non-regular / replaced / changed output
    # fails the capture closed (exit 2) with a controlled ``output-hash-failed``
    # diagnostic and is never substituted with an empty hash.  The names recorded
    # here are the documented bundle-relative top-level names (relative to the
    # bundle directory, e.g. ``git-state.json``), not the repository-relative
    # bundle path, so the contract stays stable across run ids.
    output_hash_failed = False
    failed_outputs: set = set()
    for path in written_outputs:
        rel = _posix_rel(path, out_dir)
        h = _race_safe_hash_file(path)
        if h is None:
            # A top-level output that cannot be hashed (symlink / non-regular /
            # read error / replaced mid-read) fails the capture closed; never
            # substitute an empty hash for it.  Record it so it is excluded from
            # output-sha256.txt and the diagnostic is persisted.
            output_hash_failed = True
            capture_failed = True
            failed_outputs.add(path)
            infrastructure_warnings.append(make_warning(OUTPUT_HASH_FAILED, rel))
            continue

    # ── Cap warnings + rewrite final artifacts (fail closed) ─────────────────────
    # The warning collection is bounded so an unbounded or hostile set of
    # infrastructure warnings cannot inflate the evidence bundle.  The cap is
    # applied after every diagnostic pass (first-pass output-hash-failed, overflow,
    # and any final-pass hash failure) so the persisted list never exceeds
    # MAX_WARNINGS.  When MAX_WARNINGS == 0 the persisted list is empty (the cap is
    # exact); the overflow is still signaled via ``capture_failed`` (exit 2).  The
    # rewrite guarantees the on-disk evidence / semantic / summary reflect the final
    # (capped) warning set and trusted state.
    def _finalize_warnings_and_artifacts():
        nonlocal infrastructure_warnings, sanitized_warnings, capture_failed
        warnings_overflow = len(infrastructure_warnings) > MAX_WARNINGS
        if warnings_overflow:
            capture_failed = True
            if MAX_WARNINGS > 0:
                infrastructure_warnings = (
                    infrastructure_warnings[:MAX_WARNINGS - 1]
                    + [make_warning("OVERFLOW_WARNINGS")]
                )
            else:
                # MAX_WARNINGS == 0: the cap is exact — persist nothing, not even the
                # overflow marker, so the bound holds.  The overflow is still signaled
                # via ``capture_failed`` (exit 2) below.
                infrastructure_warnings = []
        sanitized_warnings = [_sanitize_warning(w) for w in infrastructure_warnings]
        trusted = (not untrusted) and (not capture_failed)
        evidence["trusted"] = trusted
        evidence["infrastructure_warnings"] = sanitized_warnings
        semantic["trusted"] = trusted
        atomic_write_json(os.path.join(out_dir, "evidence.json"), evidence)
        atomic_write_json(os.path.join(out_dir, "semantic-summary.json"), semantic)
        summary_md = build_summary_markdown(
            bundle_rel, git_state.get("commit"), git_state.get("tree"),
            trusted, dirty, allow_dirty, preservation, results, sanitized_warnings,
        )
        atomic_write_text(os.path.join(out_dir, "summary.md"), summary_md)

    # Apply the cap + rewrite once if the first-pass diagnostics changed the
    # evidence (output-hash-failed or warning overflow).  When neither fired the
    # pre-cap evidence written above is already correct and within bounds.
    warnings_overflow = len(infrastructure_warnings) > MAX_WARNINGS
    if output_hash_failed or warnings_overflow:
        _finalize_warnings_and_artifacts()

    # ── Write output-sha256.txt using FINAL hashes (after any rewrite) ──────────
    # Recompute the hash of every non-failed top-level output so the contract
    # reflects the final rewritten artifacts (evidence / semantic / summary may
    # have been rewritten above).  A hash that fails here (a rewritten/final
    # artifact replaced or changed mid-read, i.e. TOCTOU) fails the capture CLOSED:
    # it is excluded, recorded, and ``capture_failed`` is set so the evidence is
    # rewritten to an untrusted state below.  No empty hash is ever substituted.
    def _rehash_final_outputs():
        """Rehash every non-failed top-level output; never swallow a failure.

        Returns ``(lines, new_failures)``.  Each hash failure sets
        ``capture_failed``, appends the controlled ``output-hash-failed``
        diagnostic to the warning list, permanently excludes the output from
        ``output-sha256.txt``, and flags the pass as failed so the caller
        persists the diagnostic (rewrite) before the next rehash.
        """
        nonlocal output_hash_failed, capture_failed
        lines: List[str] = []
        new_failures = False
        for path in written_outputs:
            if path in failed_outputs:
                continue
            rel = _posix_rel(path, out_dir)
            h = _race_safe_hash_file(path)
            if h is None:
                # Final-pass hash failure: fail closed.  Never substitute an
                # empty hash and never drop the diagnostic.
                output_hash_failed = True
                capture_failed = True
                failed_outputs.add(path)
                infrastructure_warnings.append(make_warning(OUTPUT_HASH_FAILED, rel))
                new_failures = True
                continue
            lines.append(f"{h}  {rel}")
        return lines, new_failures

    final_output_lines, final_pass_failed = _rehash_final_outputs()

    # Converge deterministically: persist every accumulated final-pass diagnostic
    # (rewrite evidence / semantic / summary via the capped finalize helper), then
    # rehash.  Each failing pass permanently excludes at least one output from the
    # pool, so the loop terminates after at most ``len(written_outputs)`` failing
    # passes; the bound below is defensive only.  No final diagnostic can be lost:
    # every failure is appended to the warning list before the rewrite that
    # persists it into evidence / semantic / summary, and ``output-sha256.txt`` is
    # recomputed only after the last rewrite so no stale hash is published.
    rehash_passes = 0
    while final_pass_failed and rehash_passes <= len(written_outputs):
        rehash_passes += 1
        _finalize_warnings_and_artifacts()
        final_output_lines, final_pass_failed = _rehash_final_outputs()
    if final_pass_failed:
        # Defensive (unreachable while each failing pass shrinks the pool):
        # persist the residual diagnostics so none is lost, then conservatively
        # drop the rewritten artifacts' lines because their bytes changed after
        # the last successful hash — never publish a stale hash.
        _finalize_warnings_and_artifacts()
        rewritten_rels = {"evidence.json", "semantic-summary.json", "summary.md"}
        final_output_lines = [
            ln for ln in final_output_lines
            if ln.split("  ", 1)[-1] not in rewritten_rels
        ]

    atomic_write_text(os.path.join(out_dir, "output-sha256.txt"), "\n".join(final_output_lines) + "\n")

    return 2 if capture_failed else 0


# ── CLI ───────────────────────────────────────────────────────────────────────
def _parse_expected_sha(value: str) -> str:
    """argparse type for ``--expected-sha``: exactly 40 lowercase hex characters.

    A syntactically invalid pin raises ``argparse.ArgumentTypeError`` so the CLI
    fails closed (exit 2) before any command is issued.  The pin is never
    derived from HEAD; the caller must state it.
    """
    if not _is_valid_sha40(value):
        raise argparse.ArgumentTypeError(
            "--expected-sha must be exactly 40 lowercase hexadecimal characters")
    return value


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Capture reproducible DB guard evidence at one caller-pinned "
                    "exact Git SHA (diagnostic-only; never an architecture guard).",
    )
    parser.add_argument("--root", default=".", help="Repository root (default: .)")
    parser.add_argument("--out", required=True, help="Output bundle directory (repo-relative).")
    parser.add_argument("--expected-sha", required=True, type=_parse_expected_sha,
                        metavar="<40-lowercase-hex>",
                        help="Caller-declared Git SHA this capture must observe at "
                             "HEAD (mandatory run pin; never derived from HEAD).")
    parser.add_argument("--allow-dirty", action="store_true",
                        help="Capture despite a dirty checkout; marks evidence untrusted.")
    args = parser.parse_args(argv)
    return capture_evidence(args.root, args.out, allow_dirty=args.allow_dirty,
                            expected_sha=args.expected_sha)


if __name__ == "__main__":
    sys.exit(main())
