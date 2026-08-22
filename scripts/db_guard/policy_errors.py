"""Controlled error objects for DB policy validation.

Defines a closed set of error-code constants plus a typed, immutable
``PolicyError`` value object carrying only bounded structured context.
Reason-code fields must stay limited to these constants; never embed raw
SQL text, exception messages, file paths from exceptions, or user payloads
in ``context``.

The closed set spans two vocabularies:

* scan findings — raised when the guard proves a boundary violation in
  production sources (forbidden DAO writes, raw query mutations, ...);
* policy-metadata failures — aligned with the legacy DB policy validators
  in ``scripts/verify_db_access_boundaries.py``
  (``canonical_policy_path_error``, ``ownership_entry_metadata_errors``,
  ``structural_entry_metadata_errors``, ``_yaml_safe_load_or_exit``):
  path canonicalization failures, unknown / missing / mistyped fields,
  generic or non-exact operations, wildcard methods, unbounded method
  patterns, malformed signatures, and YAML load failures.

The set is closed: ``PolicyError`` construction fails closed on any code
outside ``KNOWN_POLICY_ERROR_CODES`` so unknown reason codes cannot leak
into diagnostics.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict

# ── Scan-finding codes (controlled constants only) ────────────────────────────
# Raised when the guard proves a real boundary violation in production code.
POLICY_ERROR_FORBIDDEN_DAO_WRITE = "POLICY_ERROR_FORBIDDEN_DAO_WRITE"
POLICY_ERROR_RAW_QUERY_MUTATION = "POLICY_ERROR_RAW_QUERY_MUTATION"
POLICY_ERROR_MISSING_LIFECYCLE_COORDINATOR = "POLICY_ERROR_MISSING_LIFECYCLE_COORDINATOR"
POLICY_ERROR_ALLOWLIST_VIOLATION = "POLICY_ERROR_ALLOWLIST_VIOLATION"
POLICY_ERROR_SCHEMA_MISMATCH = "POLICY_ERROR_SCHEMA_MISMATCH"

# ── Path canonicalization codes (canonical_policy_path_error) ──────────────────
# One code per distinct canonical-form rejection, in the same order the legacy
# validator applies its rules: string type, non-empty, POSIX separators,
# repository-relative, no './' prefix, no empty/'.'/'..' segments, not a bare
# basename, Kotlin source file, and under an approved production source root.
POLICY_ERROR_PATH_NOT_STRING = "POLICY_ERROR_PATH_NOT_STRING"
POLICY_ERROR_PATH_EMPTY = "POLICY_ERROR_PATH_EMPTY"
POLICY_ERROR_PATH_BACKSLASH = "POLICY_ERROR_PATH_BACKSLASH"
POLICY_ERROR_PATH_ABSOLUTE = "POLICY_ERROR_PATH_ABSOLUTE"
POLICY_ERROR_PATH_DOT_PREFIX = "POLICY_ERROR_PATH_DOT_PREFIX"
POLICY_ERROR_PATH_BAD_SEGMENT = "POLICY_ERROR_PATH_BAD_SEGMENT"
POLICY_ERROR_PATH_BARE_BASENAME = "POLICY_ERROR_PATH_BARE_BASENAME"
POLICY_ERROR_PATH_NOT_KOTLIN = "POLICY_ERROR_PATH_NOT_KOTLIN"
POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT = "POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT"

# ── Entry metadata codes (ownership_/structural_entry_metadata_errors) ────────
# Entry-shape and schema failures shared by both policy entry validators:
# non-mapping entries, unknown keys, missing required fields, mistyped
# optional fields, the universal ``operation: write`` value, operations
# outside the exact-name / whitelisted vocabularies, wildcard methods,
# unbounded method patterns, and malformed signature blocks.
POLICY_ERROR_ENTRY_NOT_MAPPING = "POLICY_ERROR_ENTRY_NOT_MAPPING"
POLICY_ERROR_UNKNOWN_FIELD = "POLICY_ERROR_UNKNOWN_FIELD"
POLICY_ERROR_MISSING_FIELD = "POLICY_ERROR_MISSING_FIELD"
POLICY_ERROR_INVALID_TYPE = "POLICY_ERROR_INVALID_TYPE"
POLICY_ERROR_GENERIC_OPERATION = "POLICY_ERROR_GENERIC_OPERATION"
POLICY_ERROR_INVALID_OPERATION = "POLICY_ERROR_INVALID_OPERATION"
POLICY_ERROR_WILDCARD_METHOD = "POLICY_ERROR_WILDCARD_METHOD"
POLICY_ERROR_INVALID_METHOD_PATTERN = "POLICY_ERROR_INVALID_METHOD_PATTERN"
POLICY_ERROR_INVALID_SIGNATURE = "POLICY_ERROR_INVALID_SIGNATURE"

# ── YAML load codes (_yaml_safe_load_or_exit) ─────────────────────────────────
# Infrastructure failures while loading a policy document.  Context must stay
# bounded (label, counts) — never raw exception text or file contents.
POLICY_ERROR_YAML_MODULE_UNAVAILABLE = "POLICY_ERROR_YAML_MODULE_UNAVAILABLE"
POLICY_ERROR_POLICY_FILE_NOT_FOUND = "POLICY_ERROR_POLICY_FILE_NOT_FOUND"
POLICY_ERROR_YAML_MALFORMED = "POLICY_ERROR_YAML_MALFORMED"
POLICY_ERROR_POLICY_EMPTY = "POLICY_ERROR_POLICY_EMPTY"

# ── Policy v2 document codes (scripts/db_guard/policy_v2_loader.py) ───────────
# v2-specific failures with no fitting v1 code: a ``path`` field rejected by
# ``canonical_source_path`` (the parser's controlled code is retained in
# ``context`` as a bounded constant), and duplicate mutation identities across
# entries (reported as a count only — never raw payloads).
POLICY_ERROR_V2_PATH_NOT_CANONICAL = "POLICY_ERROR_V2_PATH_NOT_CANONICAL"
POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY = "POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY"

# ── v2 source-evidence codes (policy_v2_evidence.py) ──────────────────────────
POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS = "POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS"
POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE = "POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE"
POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING = "POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING"
POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS = "POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS"
POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING = "POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING"
POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS = "POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS"
POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED = "POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED"
POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN = "POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN"
POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED = "POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED"
POLICY_ERROR_V2_EVIDENCE_DAO_ACCESSOR_UNRESOLVED = "POLICY_ERROR_V2_EVIDENCE_DAO_ACCESSOR_UNRESOLVED"
POLICY_ERROR_V2_EVIDENCE_DAO_FQCN_MISMATCH = "POLICY_ERROR_V2_EVIDENCE_DAO_FQCN_MISMATCH"
POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND = "POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND"
POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION = "POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION"
POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS = "POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS"

KNOWN_POLICY_ERROR_CODES = frozenset(
    {
        # Scan findings.
        POLICY_ERROR_FORBIDDEN_DAO_WRITE,
        POLICY_ERROR_RAW_QUERY_MUTATION,
        POLICY_ERROR_MISSING_LIFECYCLE_COORDINATOR,
        POLICY_ERROR_ALLOWLIST_VIOLATION,
        POLICY_ERROR_SCHEMA_MISMATCH,
        # Path canonicalization.
        POLICY_ERROR_PATH_NOT_STRING,
        POLICY_ERROR_PATH_EMPTY,
        POLICY_ERROR_PATH_BACKSLASH,
        POLICY_ERROR_PATH_ABSOLUTE,
        POLICY_ERROR_PATH_DOT_PREFIX,
        POLICY_ERROR_PATH_BAD_SEGMENT,
        POLICY_ERROR_PATH_BARE_BASENAME,
        POLICY_ERROR_PATH_NOT_KOTLIN,
        POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT,
        # Entry metadata.
        POLICY_ERROR_ENTRY_NOT_MAPPING,
        POLICY_ERROR_UNKNOWN_FIELD,
        POLICY_ERROR_MISSING_FIELD,
        POLICY_ERROR_INVALID_TYPE,
        POLICY_ERROR_GENERIC_OPERATION,
        POLICY_ERROR_INVALID_OPERATION,
        POLICY_ERROR_WILDCARD_METHOD,
        POLICY_ERROR_INVALID_METHOD_PATTERN,
        POLICY_ERROR_INVALID_SIGNATURE,
        # YAML load.
        POLICY_ERROR_YAML_MODULE_UNAVAILABLE,
        POLICY_ERROR_POLICY_FILE_NOT_FOUND,
        POLICY_ERROR_YAML_MALFORMED,
        POLICY_ERROR_POLICY_EMPTY,
        # Policy v2 documents.
        POLICY_ERROR_V2_PATH_NOT_CANONICAL,
        POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY,
        # v2 source-evidence codes.
        POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS,
        POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE,
        POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING,
        POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS,
        POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING,
        POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS,
        POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED,
        POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN,
        POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED,
        POLICY_ERROR_V2_EVIDENCE_DAO_ACCESSOR_UNRESOLVED,
        POLICY_ERROR_V2_EVIDENCE_DAO_FQCN_MISMATCH,
        POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND,
        POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION,
        POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS,
    }
)


@dataclass(frozen=True)
class PolicyError:
    """Typed controlled error for DB policy validation failures.

    ``code`` must be one of ``KNOWN_POLICY_ERROR_CODES``; construction
    fails closed on anything else so unknown reason codes cannot leak
    into diagnostics.
    """

    code: str
    context: Dict[str, object] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.code not in KNOWN_POLICY_ERROR_CODES:
            raise ValueError("unknown policy error code")
        if not isinstance(self.context, dict):
            raise TypeError("context must be a dict")


__all__ = [
    "PolicyError",
    "KNOWN_POLICY_ERROR_CODES",
    "POLICY_ERROR_FORBIDDEN_DAO_WRITE",
    "POLICY_ERROR_RAW_QUERY_MUTATION",
    "POLICY_ERROR_MISSING_LIFECYCLE_COORDINATOR",
    "POLICY_ERROR_ALLOWLIST_VIOLATION",
    "POLICY_ERROR_SCHEMA_MISMATCH",
    "POLICY_ERROR_PATH_NOT_STRING",
    "POLICY_ERROR_PATH_EMPTY",
    "POLICY_ERROR_PATH_BACKSLASH",
    "POLICY_ERROR_PATH_ABSOLUTE",
    "POLICY_ERROR_PATH_DOT_PREFIX",
    "POLICY_ERROR_PATH_BAD_SEGMENT",
    "POLICY_ERROR_PATH_BARE_BASENAME",
    "POLICY_ERROR_PATH_NOT_KOTLIN",
    "POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT",
    "POLICY_ERROR_ENTRY_NOT_MAPPING",
    "POLICY_ERROR_UNKNOWN_FIELD",
    "POLICY_ERROR_MISSING_FIELD",
    "POLICY_ERROR_INVALID_TYPE",
    "POLICY_ERROR_GENERIC_OPERATION",
    "POLICY_ERROR_INVALID_OPERATION",
    "POLICY_ERROR_WILDCARD_METHOD",
    "POLICY_ERROR_INVALID_METHOD_PATTERN",
    "POLICY_ERROR_INVALID_SIGNATURE",
    "POLICY_ERROR_YAML_MODULE_UNAVAILABLE",
    "POLICY_ERROR_POLICY_FILE_NOT_FOUND",
    "POLICY_ERROR_YAML_MALFORMED",
    "POLICY_ERROR_POLICY_EMPTY",
    "POLICY_ERROR_V2_PATH_NOT_CANONICAL",
    "POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY",
    "POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS",
    "POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE",
    "POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING",
    "POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS",
    "POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING",
    "POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS",
    "POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED",
    "POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN",
    "POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED",
    "POLICY_ERROR_V2_EVIDENCE_DAO_ACCESSOR_UNRESOLVED",
    "POLICY_ERROR_V2_EVIDENCE_DAO_FQCN_MISMATCH",
    "POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND",
    "POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION",
    "POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS",
]
