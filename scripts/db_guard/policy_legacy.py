"""Legacy v1 compatibility pending GR-06 removal.

Policy-path and entry-metadata validation helpers copied verbatim from
the v1 scanner (renamed with a ``legacy_``
prefix / ``_legacy_`` prefix).  Bodies are intentionally identical to the
v1 scanner implementations; ``APPROVED_PRODUCTION_SOURCE_ROOTS`` comes from
``scripts.db_guard.source_roots`` so the approved-root contract stays in
exact parity with the production guard.

Legacy helpers:
  * ``legacy_canonical_path_error`` / ``legacy_canonical_path`` /
    ``_legacy_scanned_file_canonical_path`` — canonical policy paths;
  * ``legacy_ownership_entry_metadata_errors`` /
    ``legacy_structural_entry_metadata_errors`` — pure entry validators;
  * ``LEGACY_OWNERSHIP_ALLOWED_KEYS`` / ``LEGACY_STRUCTURAL_ALLOWED_KEYS`` —
    strict allowed-key schemas;
  * ``_legacy_is_wildcard_method`` / ``_legacy_is_valid_method_pattern`` /
    ``_legacy_noncanonical_signature_type`` — field-level predicates;
  * ``LEGACY_MANIFEST_ALLOWED_TOP_KEYS`` / ``LEGACY_MANIFEST_COUNT_KEYS`` —
    strict manifest schemas;
  * ``legacy_yaml_safe_load`` / ``legacy_load_ownership_policy`` /
    ``legacy_load_structural_exceptions`` — non-exiting loaders mirroring
    ``_yaml_safe_load_or_exit`` / ``load_db_ownership_policy`` /
    ``load_db_structural_exceptions`` with every ``sys.exit(2)`` replaced by
    a controlled ``PolicyError`` return (closed codes from
    ``scripts.db_guard.policy_errors``, strictly bounded context);
  * ``_legacy_canonical_path_file`` / ``_legacy_verify_ownership_group`` /
    ``legacy_verify_ownership_policy_source_evidence`` — pure source-evidence
    verification copied verbatim from the v1 scanner (renamed only).

Shared vocabulary that is not part of the legacy rename (the signature
normalizer) is still imported from ``scripts.db_policy_signature``.  The
structural-operation whitelist and the wildcard/method-pattern constants can
no longer be imported from the production scanner CLI (forbidden by PR-01),
so they are copied VERBATIM below and renamed with a
``LEGACY_`` / ``_LEGACY_`` prefix per this module's convention:
  * ``LEGACY_STRUCTURAL_FILE_OPERATIONS`` <- ``STRUCTURAL_FILE_OPERATIONS``;
  * ``_LEGACY_WILDCARD_CHARS``            <- ``_WILDCARD_CHARS``;
  * ``_LEGACY_EXACT_METHOD_NAME_RE``      <- ``_EXACT_METHOD_NAME_RE``;
  * ``_LEGACY_MIGRATION_FORM_RE``         <- ``_MIGRATION_FORM_RE``.
They must be kept in lockstep with the production contracts until GR-06
removes this legacy module entirely.

The shared parsing machinery used by the source-evidence trio (Kotlin
declaration/mutation parsers, DAO variable-map builders, mutation-match
extraction, barrier lookup, and the structured source-evidence error
builder) lives in ``scripts.db_guard.policy_parsing`` and is imported from
there; it is not duplicated here.
"""

import os
import re

from ..db_policy_signature import SignatureError, normalize_type_text
from .policy_errors import (
    POLICY_ERROR_ENTRY_NOT_MAPPING,
    POLICY_ERROR_INVALID_TYPE,
    POLICY_ERROR_POLICY_EMPTY,
    POLICY_ERROR_POLICY_FILE_NOT_FOUND,
    POLICY_ERROR_YAML_MALFORMED,
    POLICY_ERROR_YAML_MODULE_UNAVAILABLE,
    PolicyError,
)
from .source_roots import (
    APPROVED_PRODUCTION_SOURCE_ROOTS,
    DB_SOURCE_ROOT_UNDECLARED,
    SourceRoot,
    SourceRootSet,
    is_declared_production_path,
    resolve_source_root_set,
)
from .declaration_scanner import declared_root_pairs

# Shared parsing machinery for the source-evidence trio below.  These helpers
# live in scripts/db_guard/policy_parsing.py (extracted from the legacy CLI);
# they are imported, not duplicated, per this module's convention.
from .policy_parsing import (
    _barrier_before_line,
    _extract_mutation_matches,
    _source_evidence_error,
    build_class_scope_dao_var_map,
    build_dao_var_map,
    parse_function_declarations,
    parse_type_declarations,
)

# Repository root resolved relative to this module (scripts/db_guard/ is one
# level deeper than scripts/, where the original module computes it).
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))

# ── Legacy copies of production guard vocabulary ──────────────────────────────
# PR-01 forbids scripts/db_guard/* modules from importing the production
# scanner CLI, so the four shared-vocabulary names used by the validators
# below are copied VERBATIM from it and renamed with a LEGACY_ / _LEGACY_
# prefix per this module's convention.
# They are intentionally identical to the production contracts at copy time
# and must be updated in lockstep if the production grammar changes (until
# GR-06 removes this module).
#
# Verbatim copies (renamed only):
#   * STRUCTURAL_FILE_OPERATIONS -> LEGACY_STRUCTURAL_FILE_OPERATIONS
#   * _WILDCARD_CHARS            -> _LEGACY_WILDCARD_CHARS
#   * _EXACT_METHOD_NAME_RE      -> _LEGACY_EXACT_METHOD_NAME_RE
#   * _MIGRATION_FORM_RE         -> _LEGACY_MIGRATION_FORM_RE

# Exact structural file-operation whitelist: these five names are the ONLY
# operation values any structural exception entry may name in the legacy
# policy path (the generic ``write``, ``raw_*`` categories, empty strings,
# and arbitrary values fail closed).
LEGACY_STRUCTURAL_FILE_OPERATIONS = frozenset({
    "execSQL", "openDatabase", "getDatabasePath", "deleteRecursively",
    "writableDatabase",
})

# Characters that mark a method name as wildcard/pattern syntax instead of an
# exact name; regex anchors (^/$) are rejected separately by the predicate
# below.
_LEGACY_WILDCARD_CHARS = set("*?[]+")

# Bounded structural method_pattern grammar: an exact Kotlin identifier or the
# single migration form MIGRATION_\d+_\d+ (fullmatch only).  Every other
# pattern (alternation, character classes, unbounded quantifiers, anchors) is
# rejected.  _LEGACY_MIGRATION_FORM_RE matches the literal config text
# `MIGRATION_\d+_\d+` (backslashes as written in the YAML source string):
# `\\d` matches a literal backslash-d and `\+` a literal plus, so the accepted
# string is exactly MIGRATION_\d+_\d+.
_LEGACY_EXACT_METHOD_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_LEGACY_MIGRATION_FORM_RE = re.compile(r"^MIGRATION_\\d\+_\\d\+$")


def legacy_canonical_path_error(raw):
    """Return a human-readable error for a non-canonical policy path, or None.

    Canonical policy paths are:
      * non-empty strings;
      * repository-relative POSIX paths ('/' separators, no backslash);
      * not absolute (no leading '/', drive letter, or UNC prefix);
      * free of empty / '.' / '..' segments;
      * not bare basenames (must contain a directory component);
      * ending in ``.kt``;
      * under an approved production source root
        (``app/src/main/java``).

    Bare basenames are rejected because duplicate basenames exist across
    packages; suffix/ambiguous paths are rejected because matching is exact
    canonical path equality.
    """
    if not isinstance(raw, str):
        return "path must be a string"
    p = raw.strip()
    if not p:
        return "path must be non-empty"
    if "\\" in p:
        return f"path contains a backslash: {p!r} (use '/' separators)"
    if p.startswith("/") or p.startswith("\\\\") or re.match(r"^[A-Za-z]:[\\/]", p):
        return f"path must be repository-relative, not absolute: {p!r}"
    if p.startswith("./") or p == ".":
        return f"path must not start with './': {p!r}"
    parts = p.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return f"path must not contain empty, '.', or '..' segments: {p!r}"
    if len(parts) < 2:
        return f"bare basename path is ambiguous and not allowed: {p!r}"
    if not p.endswith(".kt"):
        return f"path must reference a Kotlin source file (.kt): {p!r}"
    for root in APPROVED_PRODUCTION_SOURCE_ROOTS:
        if p == root or p.startswith(root + "/"):
            return None
    return (
        f"path {p!r} is not under an approved production source root "
        f"{sorted(APPROVED_PRODUCTION_SOURCE_ROOTS)}"
    )


def legacy_canonical_path(raw):
    """Return the canonical policy path string, or None when not canonical.

    Canonical policy paths are repository-relative POSIX paths under an
    approved production source root.  Matching is exact canonical path
    equality — a non-canonical policy path never authorizes anything.
    """
    if legacy_canonical_path_error(raw) is None:
        return raw.strip()
    return None


def _legacy_scanned_file_canonical_path(filepath):
    """Return the repository-relative POSIX path of a scanned file.

    For production files under ``app/src/main/java`` this equals the canonical
    policy path form; for any other tree it produces a path that no canonical
    policy path can equal (fail closed).
    """
    return os.path.relpath(filepath, PROJECT_ROOT).replace("\\", "/")


# ── Legacy entry-metadata validation (pure helpers, no I/O, no sys.exit) ──────
# Copied verbatim from the v1 scanner with legacy_
# prefixes; cross-references point at the local legacy twins and the locally
# copied guard vocabulary defined above.


def legacy_ownership_entry_metadata_errors(entry):
    """Return human-readable metadata errors for one ownership entry, or [].

    Pure helper (no I/O, no ``sys.exit``) that tests can exercise directly.
    Validates the COMPLETE ownership-entry contract shared by the loader and
    scan()'s direct API:

      1. ``entry`` must be a mapping;
      2. only keys in ``LEGACY_OWNERSHIP_ALLOWED_KEYS`` are accepted — an
         unknown key is a configuration error, never a silently-ignored field;
      3. the required string fields (path, class, method, operation, reason,
         owner, linked_issue) must be non-empty strings;
      4. ``path`` must be a canonical policy path;
      5. ``operation`` must be the EXACT DAO method name — the universal
         ``write`` value is invalid policy metadata;
      6. ``method`` must be EXACT — wildcard/pattern methods are rejected;
      7. ``daos`` must be a non-empty list of non-empty strings;
      8. ``barrier_required`` must be present and a REAL boolean;
      9. ``barrier_via`` / ``delegate_of`` must be non-empty strings when
         present, and ``private`` must be a real boolean when present.
    """
    errors = []
    if not isinstance(entry, dict):
        return ["entry must be a mapping"]

    # H2 strict schema: reject unknown keys so a mistyped field (e.g. ``daoz``)
    # can never be silently ignored while the guard approves a mutation.
    unknown_keys = set(entry) - LEGACY_OWNERSHIP_ALLOWED_KEYS
    if unknown_keys:
        errors.append(f"unknown key(s) {sorted(unknown_keys)}")

    for field in ("path", "class", "method", "operation", "reason", "owner", "linked_issue"):
        value = entry.get(field)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"'{field}' must be a non-empty string")

    path = entry.get("path")
    if isinstance(path, str) and path.strip():
        path_error = legacy_canonical_path_error(path)
        if path_error:
            errors.append(f"'path' is not canonical: {path_error}")

    op = entry.get("operation")
    if op == "write":
        errors.append(
            "'operation: write' is invalid policy metadata — every entry must "
            "name the EXACT DAO method it authorizes "
            "(e.g. 'insert', 'insertOrIgnore', 'archiveGroup', "
            "'deleteAllForGroup', 'staleAbortIfStillRunning')"
        )
    elif not isinstance(op, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", op or ""):
        errors.append(f"'operation' must be an exact DAO method name, got {op!r}")

    method = entry.get("method")
    if isinstance(method, str) and _legacy_is_wildcard_method(method):
        errors.append(
            f"method {method!r} is a wildcard/pattern method — every writer "
            "method must be individually enumerated with an exact name"
        )

    daos = entry.get("daos")
    if "daos" not in entry:
        errors.append("'daos' must be a non-empty list of non-empty strings")
    elif not isinstance(daos, list) or not daos:
        errors.append("'daos' must be a non-empty list of non-empty strings")
    else:
        for dao in daos:
            if not isinstance(dao, str) or not dao.strip():
                errors.append("every 'daos' entry must be a non-empty string")
                break

    if "barrier_required" not in entry:
        errors.append("'barrier_required' must be a real boolean (true/false)")
    elif not isinstance(entry["barrier_required"], bool):
        errors.append("'barrier_required' must be a real boolean (true/false)")

    for field in ("barrier_via", "delegate_of"):
        if field in entry:
            value = entry[field]
            if not isinstance(value, str) or not value.strip():
                errors.append(f"'{field}' must be a non-empty string when present")

    if "signature" in entry:
        signature = entry["signature"]
        if not isinstance(signature, dict):
            errors.append("'signature' must be a mapping when present")
        else:
            if set(signature) != {"receiver", "kind", "parameters"}:
                errors.append("'signature' must contain exactly receiver, kind, parameters")
            receiver = signature.get("receiver")
            if receiver is not None and not isinstance(receiver, str):
                errors.append("'signature.receiver' must be a string or null")
            elif isinstance(receiver, str):
                try:
                    normalized = normalize_type_text(receiver)
                    if normalized != receiver or receiver.startswith("vararg "):
                        errors.append("'signature.receiver' must be a canonical type")
                except SignatureError:
                    errors.append("'signature.receiver' must be a valid canonical type")
            kind = signature.get("kind")
            if kind not in {"function", "constructor", "property_getter", "property_setter", "top_level_function", "initializer"}:
                errors.append("'signature.kind' is not an allowed callable kind")
            parameters = signature.get("parameters")
            if not isinstance(parameters, list) or any(not isinstance(item, str) or not item for item in parameters):
                errors.append("'signature.parameters' must be a list of non-empty strings")
            elif any(_legacy_noncanonical_signature_type(item) for item in parameters):
                errors.append("'signature.parameters' must be an ordered list of canonical types")

    if "private" in entry and not isinstance(entry["private"], bool):
        errors.append("'private' must be a real boolean (true/false) when present")

    return errors


def _legacy_noncanonical_signature_type(value):
    """Return true unless a policy type is exactly the canonical spelling."""
    if not isinstance(value, str) or not value or "*" in value:
        return True
    try:
        return normalize_type_text(value) != value
    except SignatureError:
        return True


def legacy_structural_entry_metadata_errors(entry):
    """Return human-readable metadata errors for one structural entry, or [].

    Pure helper (no I/O, no ``sys.exit``): validates the COMPLETE structural
    exception contract shared by the loader and scan()'s direct API:

      1. ``entry`` must be a mapping;
      2. only keys in ``LEGACY_STRUCTURAL_ALLOWED_KEYS`` are accepted — an
         unknown key is a configuration error;
      3. the required fields (path, class, method_pattern, operation) must be
         non-empty strings;
      4. optional documentation fields (reason, owner, linked_issue) must be
         non-empty strings when present;
      5. ``path`` must be a canonical policy path;
      6. ``method_pattern`` must be bounded: an exact method name or the single
         migration form ``MIGRATION_\\d+_\\d+``;
      7. ``operation`` must be one of the exact whitelisted structural
         operations (``LEGACY_STRUCTURAL_FILE_OPERATIONS``) — the generic ``write``,
         ``raw_*`` categories, empty strings, and arbitrary values are invalid
         policy metadata and fail closed.
    """
    errors = []
    if not isinstance(entry, dict):
        return ["entry must be a mapping"]

    unknown_keys = set(entry) - LEGACY_STRUCTURAL_ALLOWED_KEYS
    if unknown_keys:
        errors.append(f"unknown key(s) {sorted(unknown_keys)}")

    for field in ("path", "class", "method_pattern", "operation"):
        value = entry.get(field)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"'{field}' must be a non-empty string")

    for field in ("reason", "owner", "linked_issue"):
        if field in entry and (
            not isinstance(entry[field], str) or not entry[field].strip()
        ):
            errors.append(f"'{field}' must be a non-empty string when present")

    path = entry.get("path")
    if isinstance(path, str) and path.strip():
        path_error = legacy_canonical_path_error(path)
        if path_error:
            errors.append(f"'path' is not canonical: {path_error}")

    mp = entry.get("method_pattern")
    if not _legacy_is_valid_method_pattern(mp):
        errors.append(
            f"'method_pattern' must be an exact method name or the bounded "
            f"migration form MIGRATION_\\d+_\\d+, got {mp!r}"
        )

    # H2 strict operation whitelist: a structural exception (or manifest
    # tuple) may name ONLY one of the exact supported structural operations.
    # The generic ``write`` value, ``raw_*`` categories (``raw_sqlite`` /
    # ``raw_db_file``), empty strings, and arbitrary values are invalid policy
    # metadata — a value outside the whitelist can never authorize a file
    # operation and fails closed here (exit 2 via the loaders / scan()).
    op = entry.get("operation")
    if not isinstance(op, str) or op not in LEGACY_STRUCTURAL_FILE_OPERATIONS:
        errors.append(
            f"'operation' must be one of the exact supported structural "
            f"operations {sorted(LEGACY_STRUCTURAL_FILE_OPERATIONS)}, got {op!r}"
        )
    return errors

# H2 strict schema: the ONLY keys accepted in an ownership-policy entry.
# Everything else is a config error (exit 2) so typos/misnamed metadata can
# never silently change approval semantics.
LEGACY_OWNERSHIP_ALLOWED_KEYS = frozenset({
    "path", "class", "method", "daos", "operation",
    "barrier_required", "barrier_via", "reason", "owner",
    "linked_issue", "private", "delegate_of", "signature",
})

# H2 strict schema: the ONLY keys accepted in a structural-exception entry.
LEGACY_STRUCTURAL_ALLOWED_KEYS = frozenset({
    "path", "class", "method_pattern", "operation",
    "reason", "owner", "linked_issue",
})


def _legacy_is_wildcard_method(method):
    """Return True if ``method`` uses wildcard/regex syntax instead of an exact name.

    Exact writer methods must be individually enumerated.  ``"*"``, glob
    characters, or regex anchors are rejected.
    """
    if not isinstance(method, str):
        return True
    return (
        any(ch in _LEGACY_WILDCARD_CHARS for ch in method)
        or method.startswith("^")
        or method.endswith("$")
    )


def _legacy_is_valid_method_pattern(method_pattern):
    """Return True only for whitelisted bounded structural patterns.

    Accepts an exact method name (a plain Kotlin identifier) or the single
    bounded migration form ``MIGRATION_\\d+_\\d+`` via ``re.fullmatch``.
    Everything else — including alternation, character classes, ``\\w``,
    unbounded ``.*?``/``.+``, and anchors — fails closed.
    """
    if not isinstance(method_pattern, str):
        return False
    pattern = method_pattern.strip()
    if not pattern:
        return False
    return bool(
        _LEGACY_EXACT_METHOD_NAME_RE.fullmatch(pattern)
        or _LEGACY_MIGRATION_FORM_RE.fullmatch(pattern)
    )


# ── Legacy structural expected-methods manifest validation ────────────────────
# Copied verbatim from the v1 scanner with legacy_
# prefixes.  Only the strict manifest schema constants and their building
# blocks were carried over into this module — the v1 manifest validators,
# immutable tuple contracts, derived counts, and private manifest helpers have
# no legacy twins here.
#
# Verbatim copies (renamed only):
#   * MANIFEST_ALLOWED_TOP_KEYS   -> LEGACY_MANIFEST_ALLOWED_TOP_KEYS
#   * MANIFEST_COUNT_KEYS         -> LEGACY_MANIFEST_COUNT_KEYS
#   * _MIGRATION_METHOD_PATTERN   -> _LEGACY_MIGRATION_METHOD_PATTERN
#   * _STRUCT_PATH_*              -> _LEGACY_STRUCT_PATH_*

# Only these top-level keys are accepted in the manifest.  Everything else
# (including ``allowlist``-style metadata) is a config error (exit 2).
LEGACY_MANIFEST_ALLOWED_TOP_KEYS = frozenset(
    {"baseline", "expected", "fixtures", "counts"}
)

# Only these keys are accepted inside the ``counts`` section.
LEGACY_MANIFEST_COUNT_KEYS = frozenset({"ownership_entries", "structural_entries"})

_LEGACY_MIGRATION_METHOD_PATTERN = "MIGRATION_\\d+_\\d+"

_LEGACY_STRUCT_PATH_DATABASE_MIGRATIONS = (
    "app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt"
)
_LEGACY_STRUCT_PATH_APP_DATABASE = (
    "app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt"
)
_LEGACY_STRUCT_PATH_FINANCIAL_RESCUE = (
    "app/src/main/java/com/yourname/expensetracker/data/rescue/FinancialRescueCoordinator.kt"
)
_LEGACY_STRUCT_PATH_DB_BACKUP_IMPL = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt"
)
_LEGACY_STRUCT_PATH_APP_STARTUP = (
    "app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt"
)
_LEGACY_STRUCT_PATH_DB_INTEGRITY = (
    "app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DatabaseIntegrityScanner.kt"
)
_LEGACY_STRUCT_PATH_EXPORT_ANONYMIZER = (
    "app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt"
)
_LEGACY_STRUCT_PATH_LEGACY_MIGRATION = (
    "app/src/main/java/com/yourname/expensetracker/service/debug/LegacyDataMigrationService.kt"
)
_LEGACY_STRUCT_PATH_BACKUP_VERIFIER = (
    "app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt"
)
_LEGACY_STRUCT_PATH_SNAPSHOT_CREATOR = (
    "app/src/main/java/com/yourname/expensetracker/data/backup/SqliteSnapshotCreator.kt"
)


# ── Non-exiting legacy policy loaders ──────────────────────────────────────────
# Verbatim control flow of the v1 scanner's exiting loaders
# (``_yaml_safe_load_or_exit`` / ``load_db_ownership_policy`` /
# ``load_db_structural_exceptions``) with every ``sys.exit(2)`` replaced by a
# returned controlled ``PolicyError`` (closed codes from
# ``scripts.db_guard.policy_errors``).  Checks, ordering, and fail-closed
# semantics are intentionally identical to the production implementations;
# only the failure channel changes (return instead of exit).
#
# PR-01 forbids scripts/db_guard/* modules from importing the production
# scanner CLI, so the optional-PyYAML import pattern is copied below and must
# stay in lockstep with the production module until GR-06 removes this file.
#
# Bounded-context contract (see policy_errors.py): ``PolicyError.context``
# carries only fixed labels, indices, counts, and type names — never file
# paths, never raw exception text, never policy entry payloads.  The
# human-readable reasons behind an entry failure are exactly the strings
# returned by the pure validators (``legacy_ownership_entry_metadata_errors``
# / ``legacy_structural_entry_metadata_errors``); callers that need them call
# those validators directly, mirroring what the exiting loaders print before
# they exit.

try:
    import yaml
    _HAS_YAML = True
except ImportError:
    _HAS_YAML = False


def legacy_yaml_safe_load(path, label="policy document"):
    """Load a YAML file with ``yaml.safe_load`` without exiting.

    Same read/parse flow as ``_yaml_safe_load_or_exit`` (same checks, same
    order, same fail-closed semantics), with ``sys.exit(2)`` replaced by a
    returned ``PolicyError``:

      * PyYAML unavailable          -> POLICY_ERROR_YAML_MODULE_UNAVAILABLE
      * file does not exist         -> POLICY_ERROR_POLICY_FILE_NOT_FOUND
      * ``yaml.YAMLError`` on parse -> POLICY_ERROR_YAML_MALFORMED
      * document parses to ``None`` -> POLICY_ERROR_POLICY_EMPTY

    Returns ``(data, None)`` on success and ``(None, PolicyError)`` on
    failure.  Like the production loader, only ``yaml.YAMLError`` is handled
    — any other I/O error propagates.  ``context`` stays bounded (``label``
    only): never the path, never raw exception text.
    """
    if not _HAS_YAML:
        return None, PolicyError(
            POLICY_ERROR_YAML_MODULE_UNAVAILABLE, {"label": label}
        )

    if not os.path.exists(path):
        return None, PolicyError(
            POLICY_ERROR_POLICY_FILE_NOT_FOUND, {"label": label}
        )

    try:
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except yaml.YAMLError:
        return None, PolicyError(POLICY_ERROR_YAML_MALFORMED, {"label": label})

    if data is None:
        return None, PolicyError(POLICY_ERROR_POLICY_EMPTY, {"label": label})

    return data, None


def _legacy_entry_policy_error(section_label, index, entry, validator_errors):
    """Return the controlled ``PolicyError`` for one failing policy entry.

    Bounded mapping of the exiting loaders' per-entry ``sys.exit(2)`` onto
    the closed code set:

      * non-mapping entry -> POLICY_ERROR_ENTRY_NOT_MAPPING;
      * mapping entry that failed its metadata contract
        -> POLICY_ERROR_INVALID_TYPE (``error_count`` records how many
        validator reasons fired).

    The human-readable reasons themselves are intentionally NOT embedded —
    ``PolicyError.context`` stays bounded (labels, indices, counts).
    """
    if not isinstance(entry, dict):
        return PolicyError(
            POLICY_ERROR_ENTRY_NOT_MAPPING,
            {"label": section_label, "index": index},
        )
    return PolicyError(
        POLICY_ERROR_INVALID_TYPE,
        {
            "label": section_label,
            "index": index,
            "error_count": len(validator_errors),
        },
    )


def legacy_load_ownership_policy(path):
    """Load and validate the DB ownership policy without exiting.

    Verbatim validation flow of ``load_db_ownership_policy`` minus the
    ``sys.exit(2)`` calls: load via :func:`legacy_yaml_safe_load`, unwrap the
    ``entries`` key (or accept a top-level list), require a list, then run
    the COMPLETE per-entry validator
    ``legacy_ownership_entry_metadata_errors`` — stopping at the FIRST
    failing entry, exactly like the exiting loader.

    Returns ``(entries, [])`` on success and ``([], errors)`` on failure,
    where ``errors`` holds one ``PolicyError`` per handled exit point:

      * YAML load failure  -> the underlying :func:`legacy_yaml_safe_load`
        code (module unavailable / file not found / malformed / empty);
      * entries not a list -> POLICY_ERROR_INVALID_TYPE (``expected`` /
        ``got`` type name in context);
      * first failing entry -> POLICY_ERROR_ENTRY_NOT_MAPPING or
        POLICY_ERROR_INVALID_TYPE (see :func:`_legacy_entry_policy_error`).
    """
    data, error = legacy_yaml_safe_load(path, "DB ownership policy")
    if error is not None:
        return [], [error]

    entries = data.get("entries", data) if isinstance(data, dict) else data

    if not isinstance(entries, list):
        return [], [
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {
                    "label": "DB ownership policy",
                    "expected": "list",
                    "got": type(entries).__name__,
                },
            )
        ]

    for i, entry in enumerate(entries):
        # Complete validation — the SAME validator scan()'s direct API uses
        # (verbatim legacy twin).  Fail fast on the first invalid entry.
        errors = legacy_ownership_entry_metadata_errors(entry)
        if errors:
            return [], [
                _legacy_entry_policy_error(
                    "ownership policy entry", i, entry, errors
                )
            ]

    return entries, []


def legacy_load_structural_exceptions(path):
    """Load and validate the DB structural exceptions without exiting.

    Verbatim validation flow of ``load_db_structural_exceptions`` minus the
    ``sys.exit(2)`` calls: load via :func:`legacy_yaml_safe_load`, unwrap the
    ``entries`` key (or accept a top-level list), require a list, then run
    the COMPLETE per-entry validator
    ``legacy_structural_entry_metadata_errors`` — stopping at the FIRST
    failing entry, exactly like the exiting loader.

    Returns ``(entries, [])`` on success and ``([], errors)`` on failure,
    with the same controlled codes as :func:`legacy_load_ownership_policy`.
    """
    data, error = legacy_yaml_safe_load(path, "DB structural exceptions")
    if error is not None:
        return [], [error]

    entries = data.get("entries", data) if isinstance(data, dict) else data

    if not isinstance(entries, list):
        return [], [
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {
                    "label": "DB structural exceptions",
                    "expected": "list",
                    "got": type(entries).__name__,
                },
            )
        ]

    for i, entry in enumerate(entries):
        # Complete validation — the SAME validator scan()'s direct API uses
        # (verbatim legacy twin).  Fail fast on the first invalid entry.
        errors = legacy_structural_entry_metadata_errors(entry)
        if errors:
            return [], [
                _legacy_entry_policy_error(
                    "structural exception entry", i, entry, errors
                )
            ]

    return entries, []


# ── Legacy source-evidence verification ────────────────────────────────────────
# Copied verbatim from the v1 scanner with legacy_
# prefixes; bodies are intentionally identical to the v1 scanner
# implementations (renamed internal references only):
#   * _canonical_path_file -> _legacy_canonical_path_file
#   * _verify_ownership_group -> _legacy_verify_ownership_group
#   * verify_ownership_policy_source_evidence
#                                 -> legacy_verify_ownership_policy_source_evidence
# Cross-references point at the local legacy twins where they exist
# (``legacy_ownership_entry_metadata_errors``); the Kotlin declaration/mutation
# parser helpers and ``_source_evidence_error`` come from the shared parsing
# machinery in ``scripts.db_guard.policy_parsing``.
# ``APPROVED_PRODUCTION_SOURCE_ROOTS`` keeps coming from
# ``scripts.db_guard.source_roots`` for the GENERIC canonical-path layer
# (``legacy_canonical_path_error``), whose value is identical to the CLI
# module's constant.  PR-GR-03 Slice D: the source-evidence resolver below no
# longer validates against that in-code tuple — ``_legacy_canonical_path_file``
# resolves the DECLARED (manifest-backed) production root set through
# ``resolve_source_root_set`` and membership-checks each canonical path with
# ``is_declared_production_path`` instead.


def _legacy_declared_relative_root_set(source_root):
    """Resolve the effective declared production root set, re-anchored.

    Parity twin of the Slice D helpers in ``policy_v2_evidence`` /
    ``policy_v2_candidate``: ``resolve_source_root_set`` returns
    manifest-declared roots as repository-relative POSIX paths but its
    implicit conventional fallback carries each root as an ABSOLUTE
    native-separator path anchored at its enclosing project; absolute roots
    are re-anchored here with the exact parity convention of
    ``declaration_scanner.declared_root_pairs`` so membership checks against
    repository-relative policy paths stay possible.  Returns
    ``(SourceRootSet, ())`` on success or ``(None, diagnostics)`` fail
    closed; an absolute root that cannot be anchored is dropped so none of
    its paths can ever authorize anything.
    """
    root_set, diagnostics = resolve_source_root_set(source_root)
    if root_set is None or diagnostics:
        return None, diagnostics
    anchored = None
    normalized = []
    for root in root_set.roots:
        path = root.path
        if os.path.isabs(path):
            if anchored is None:
                anchored = iter(declared_root_pairs(source_root, root_set))
            pair = next(anchored, None)
            if pair is None:
                continue
            anchor, base = pair
            try:
                path = os.path.relpath(base, anchor).replace(os.sep, "/")
            except ValueError:
                continue
        normalized.append(
            SourceRoot(module=root.module, source_set=root.source_set, path=path)
        )
    if not normalized:
        return None, (
            (DB_SOURCE_ROOT_UNDECLARED, {"reason": "no-conventional-root"}),
        )
    return SourceRootSet(roots=tuple(normalized)), ()


def _legacy_canonical_path_file(canonical_path, source_root):
    """Resolve a canonical policy path to a real file under ``source_root``.

    Membership is checked against the DECLARED production source roots
    resolved for ``source_root`` — the manifest-backed root set when the
    checked-in manifest exists, the implicit conventional single root
    otherwise — never against an in-code root tuple.  The matched declared
    root's prefix is stripped and the remainder joined under ``source_root``
    exactly as before, so single-root callers keep identical resolution.
    Generic syntax rejection (.kt suffix, non-bare basename) plus every
    fail-closed shape (non-strings, empty values, backslashes, absolute
    forms, empty/``.``/``..`` segments) still yields None: a basename or
    suffix can never resolve here.
    """
    if (
        not isinstance(canonical_path, str)
        or not canonical_path.endswith(".kt")
        or "/" not in canonical_path
    ):
        return None
    root_set, _diagnostics = _legacy_declared_relative_root_set(source_root)
    if root_set is None:
        return None
    if not is_declared_production_path(root_set, canonical_path):
        return None
    for root in root_set.roots:
        if canonical_path.startswith(root.path + "/"):
            rel = canonical_path[len(root.path) + 1:]
            return os.path.join(source_root, *rel.split("/"))
    return None


def _legacy_verify_ownership_group(path, class_name, method_name, group_entries,
                                   source_root):
    """Return structured source-evidence errors for one (path, class, method)
    group of ownership entries.

    ``group_entries`` is a list of ``(index, entry)`` tuples sharing the same
    canonical ``path``, ``class``, and ``method``.  The group is the unit of
    policy-union coverage: for overloaded methods the union of every overload's
    mutation pairs is the method's evidence, and every listed ``(dao,
    operation)`` pair must exist in that union while every actual pair in the
    union must be covered by the group's listed union.

    Fail-closed discipline mirrors ``scan()``: no filename-stem, file-wide
    token, wildcard, or ``matches.last()`` fallback is ever used.
    """
    errors = []

    # 1. Resolve the canonical path to a real file under the source root.
    filepath = _legacy_canonical_path_file(path, source_root)
    if filepath is None:
        return [_source_evidence_error(
            path, class_name, method_name, "PATH_INVALID",
            "policy path is not under an approved production source root",
        )]
    if not os.path.isfile(filepath):
        return [_source_evidence_error(
            path, class_name, method_name, "PATH_NOT_FOUND",
            "canonical source file does not exist under the source root",
        )]
    try:
        with open(filepath, encoding="utf-8") as f:
            lines = f.readlines()
    except (OSError, UnicodeDecodeError):
        return [_source_evidence_error(
            path, class_name, method_name, "FILE_UNREADABLE",
            "cannot read Kotlin source file",
        )]

    # 2. Exact class/object resolution from the ACTUAL Kotlin declarations.
    #    Zero matches and duplicate declarations both fail closed.
    types = parse_type_declarations(lines)
    class_decls = [t for t in types if t["name"] == class_name]
    if not class_decls:
        return [_source_evidence_error(
            path, class_name, method_name, "CLASS_MISSING",
            f"declared class/object {class_name!r} is not present in the "
            "source file",
        )]
    if len(class_decls) > 1:
        return [_source_evidence_error(
            path, class_name, method_name, "CLASS_AMBIGUOUS",
            f"declared class/object {class_name!r} is declared more than once "
            "in the source file",
        )]
    type_decl = class_decls[0]

    # 3. Exact method resolution, including private methods and overloads.
    methods = parse_function_declarations(lines, type_decl["start"],
                                          type_decl["end"])
    target_methods = [m for m in methods if m["name"] == method_name]
    if not target_methods:
        return [_source_evidence_error(
            path, class_name, method_name, "METHOD_MISSING",
            f"method {method_name!r} is not declared in class/object "
            f"{class_name!r}",
        )]

    # Fail closed when any body cannot be bounded: pairs extracted from a
    # partial body can never prove exhaustive coverage.
    for m in target_methods:
        if m.get("unsupported_expression") or m.get("unterminated_braced_body"):
            return [_source_evidence_error(
                path, class_name, method_name, "METHOD_BODY_UNSUPPORTED",
                f"method {method_name!r} body cannot be bounded; source "
                "evidence refused",
            )]

    # 4. DAO identity resolution from class/method declarations/types — the
    #    SAME scoping rules scan() uses (constructor params, class properties,
    #    method locals; a local alias declared in ANOTHER method is never in
    #    scope here and fails closed).
    method_body_lines = set()
    for m in methods:
        method_body_lines.update(range(m["start"], m["end"] + 1))
    class_map = build_class_scope_dao_var_map(
        lines, type_decl["start"], type_decl["end"],
        excluded_line_numbers=method_body_lines,
    )
    all_locals = {}
    for m in methods:
        body_lines = m["body"].split("\n")
        local_map = build_dao_var_map(body_lines, 0, len(body_lines) - 1)
        all_locals.update(local_map)

    # 5. Extract exact (dao, operation) pairs from every target method body
    #    and union them across overloads.
    extracted = {}  # (dao, op) -> [(method_start_0, abs_lineno_1)]
    resolution_failures = []  # (receiver, op, abs_lineno)
    for m in target_methods:
        body_lines = m["body"].split("\n")
        local_map = build_dao_var_map(body_lines, 0, len(body_lines) - 1)
        var_map = {**class_map, **local_map}
        out_of_scope = set(all_locals) - set(var_map)
        matches = _extract_mutation_matches(
            m["body"],
            var_map=var_map,
            out_of_scope_aliases=out_of_scope,
            out_of_scope_alias_identities=all_locals,
        )
        for match in matches:
            abs_lineno = m["start"] + match["lineno"] + 1
            if match["out_of_scope"]:
                resolution_failures.append(
                    (match["receiver"], match["op"], abs_lineno)
                )
            else:
                extracted.setdefault((match["dao"], match["op"]), []).append(
                    (m["start"], abs_lineno)
                )

    for receiver, op, abs_lineno in resolution_failures:
        errors.append(_source_evidence_error(
            path, class_name, method_name, "DAO_RESOLUTION_FAILED",
            f"mutation {receiver}.{op}(...) at line {abs_lineno} cannot be "
            "resolved to a DAO identity in this method (out-of-scope alias); "
            "source evidence refused",
            dao=receiver, operation=op,
        ))

    actual_union = set(extracted)

    # 6. Policy union for the group: every (dao, operation) pair listed by
    #    every entry with the same (path, class, method).
    listed_union = set()
    for _index, entry in group_entries:
        for dao in entry.get("daos") or []:
            listed_union.add((dao, entry["operation"]))

    # 7. Bidirectional exact-pair coverage.
    #    7a. Every listed pair must exist in the source union — a policy entry
    #        that names a DAO/operation the method never invokes is untruthful.
    for _index, entry in group_entries:
        for dao in entry.get("daos") or []:
            pair = (dao, entry["operation"])
            if pair not in actual_union:
                errors.append(_source_evidence_error(
                    path, class_name, method_name, "PAIR_NOT_FOUND",
                    f"policy lists dao={dao} operation={entry['operation']} "
                    "but the source method body contains no such mutation",
                    dao=dao, operation=entry["operation"],
                ))

    #    7b. Every actual pair must be covered by the policy union — a real
    #        mutation the policy omits fails closed (all-or-nothing).
    for dao, op in sorted(actual_union):
        if (dao, op) not in listed_union:
            errors.append(_source_evidence_error(
                path, class_name, method_name, "PAIR_NOT_COVERED",
                f"source method invokes dao={dao} operation={op} which is not "
                "covered by any policy entry for this method",
                dao=dao, operation=op,
            ))

    # 8. Barrier evidence and mediation truthfulness per actual mutation.
    for (dao, op), occurrences in extracted.items():
        covering = [
            entry for _index, entry in group_entries
            if entry.get("operation") == op and dao in (entry.get("daos") or [])
        ]
        if not covering:
            continue  # already reported as PAIR_NOT_COVERED
        for method_start, abs_lineno in occurrences:
            barrier_before = _barrier_before_line(
                lines, method_start, abs_lineno
            )
            for entry in covering:
                if entry.get("barrier_required") and not barrier_before:
                    errors.append(_source_evidence_error(
                        path, class_name, method_name, "MISSING_WRITE_BARRIER",
                        "barrier_required=true but no direct masked "
                        "writeBarrier.checkWritesAllowed/runWrite before "
                        f"dao={dao} operation={op} at line {abs_lineno}",
                        dao=dao, operation=op,
                    ))
                if entry.get("barrier_via") and entry.get("barrier_required"):
                    errors.append(_source_evidence_error(
                        path, class_name, method_name,
                        "MEDIATED_METADATA_UNTRUTHFUL",
                        "entry claims WorkerExecutionGuard mediation "
                        f"(barrier_via={entry.get('barrier_via')}) but "
                        "barrier_required=true; mediation and a direct barrier "
                        "claim cannot both be true",
                        dao=dao, operation=op,
                    ))
                if entry.get("barrier_via") and barrier_before:
                    errors.append(_source_evidence_error(
                        path, class_name, method_name,
                        "MEDIATED_METADATA_UNTRUTHFUL",
                        "entry claims WorkerExecutionGuard mediation "
                        f"(barrier_via={entry.get('barrier_via')}) yet the "
                        f"source directly invokes writeBarrier before dao={dao} "
                        f"operation={op} at line {abs_lineno}",
                        dao=dao, operation=op,
                    ))

    # Deduplicate identical diagnostics (multiple entries may share a pair).
    seen = set()
    unique = []
    for err in errors:
        signature = (
            err["code"], err["path"], err["class"], err["method"],
            err.get("dao"), err.get("operation"), err["detail"],
        )
        if signature in seen:
            continue
        seen.add(signature)
        unique.append(err)
    return unique


def legacy_verify_ownership_policy_source_evidence(entries, source_root):
    """Return structured source-evidence errors for ownership entries, or [].

    Pure policy-side validator (reads only the referenced source files under
    ``source_root``; never calls ``sys.exit``).  Tests exercise it directly and
    ``main()`` maps non-empty results to exit code 2.

    Every entry must be backed by EXHAUSTIVE exact source evidence:

      1. the canonical path must resolve to a real Kotlin file under
         ``source_root``;
      2. the declared ``class``/``object`` must exist exactly once in that file
         (missing/ambiguous classes fail closed — never a filename-stem or
         file-wide fallback);
      3. the exact ``method`` must exist in that class, including private
         methods and overloads; for overloads the union of all overloads'
         mutation pairs is the method's evidence and the policy union must
         cover every pair;
      4. DAO identities resolve through class-scoped properties/constructor
         params and method-scoped locals (``groupDao: ExpenseGroupDao`` ->
         ``expenseGroupDao``) exactly as ``scan()`` resolves them;
      5. mutation ``(dao, operation)`` pairs are extracted from the COMPLETE
         exact method body; every listed policy pair must exist in the source
         and every actual source pair must be covered by the policy union — a
         single mismatch fails;
      6. when ``barrier_required`` is true, a direct MASKED
         ``writeBarrier.checkWritesAllowed`` / ``writeBarrier.runWrite`` call
         must appear before each mutation; mediated worker metadata
         (``barrier_via``) must be truthful — a mediated entry must not also
         claim a direct barrier, and the source must not directly invoke the
         barrier while mediation is claimed;
      7. no filename stem, file-wide token presence, wildcard, or
         ``matches.last()`` fallback is ever used.

    Each returned error is a structured dict with bounded controlled fields:
    ``{path, class, method, code, detail}`` plus ``dao``/``operation`` when the
    error concerns a specific pair.  ``code`` is one of the controlled
    SOURCE_EVIDENCE_CODES.
    """
    errors = []
    groups = {}
    for i, entry in enumerate(entries):
        if not isinstance(entry, dict):
            errors.append(_source_evidence_error(
                "", "", "", "ENTRY_INVALID", "entry must be a mapping",
            ))
            continue
        # Reuse the loader's complete metadata validator so a malformed entry
        # (unknown key, non-canonical path, wildcard method, operation: write,
        # bad boolean) is reported here instead of being resolved lazily.
        meta_errors = legacy_ownership_entry_metadata_errors(entry)
        if meta_errors:
            errors.append(_source_evidence_error(
                entry.get("path", ""),
                entry.get("class", ""),
                entry.get("method", ""),
                "ENTRY_INVALID",
                "; ".join(meta_errors),
            ))
            continue
        key = (entry["path"], entry["class"], entry["method"])
        groups.setdefault(key, []).append((i, entry))

    for (path, class_name, method_name), group_entries in groups.items():
        errors.extend(_legacy_verify_ownership_group(
            path, class_name, method_name, group_entries, source_root,
        ))

    # Deterministic ordering for stable diagnostics.
    errors.sort(key=lambda e: (
        e.get("path", ""),
        e.get("class", ""),
        e.get("method", ""),
        e.get("code", ""),
        e.get("dao", ""),
        e.get("operation", ""),
        e.get("detail", ""),
    ))
    return errors

