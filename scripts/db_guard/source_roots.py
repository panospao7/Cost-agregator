"""DB compatibility re-export layer for the production source-scope authority.

Since PR-GR-10B Slice 1, the ONE live implementation of root-manifest
parsing/validation, topology verification, root-set resolution, deterministic
production Kotlin enumeration, declared-path membership, and safe source-file
resolution lives in the neutral module
``scripts.guardrails.production_source_scope``.  This module is now a thin
DB compatibility shim (plan option 1): every historical public name is
re-exported unchanged so all existing consumers (DB scanner, evidence,
candidate, inventory, GR-01 modules, capture tool) keep working without
edits.  It contains no duplicated scope logic — only imports, name aliases,
the two documented DB-seam projection adapters, and the DB-specific legacy
GR-01 contract below.

DB-specific legacy names (NOT part of the neutral production source-scope
authority; retained only until GR-10B removes the reliance, and no NEW
production guard may depend on them):

* ``APPROVED_PRODUCTION_SOURCE_ROOTS`` — hard-coded single-root tuple kept
  only for the legacy GR-01 canonical DB policy-path contract
  (``scripts/db_guard/policy_legacy.py``) and its tests.  The authoritative
  production source roots come solely from
  ``config/guards/production_source_roots.yml`` via the neutral module.
* ``approved_root_error`` / ``is_approved_source_path`` — legacy GR-01
  canonical policy-path validation reporting closed ``POLICY_ERROR_PATH_*``
  codes from ``scripts/db_guard/policy_errors.py``; mirrors
  ``canonical_policy_path_error()`` in
  ``scripts/verify_db_access_boundaries.py``.
* ``resolve_source_root_set`` — the legacy DB resolution seam
  (PR-GR-03 Slice C1), now an alias of the neutral
  ``resolve_source_root_set_for_test_fixtures``.  Real repositories always
  ship the checked-in manifest, which takes precedence; the implicit
  conventional-root fallback inside that seam exists solely for synthetic
  test fixtures and manifest-less embedders and is scheduled for removal
  with GR-10B.

The historical ``DB_SOURCE_ROOT_*`` diagnostic-code names below are pure
aliases of the neutral ``PRODUCTION_SOURCE_SCOPE_*`` constants (identical
string values), so DB reports stay byte-identical while the diagnostic
vocabulary has exactly one home.
"""
from __future__ import annotations

import re
from typing import Dict, Optional, Tuple

try:
    from scripts.guardrails.production_source_scope import (
        PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES,
        PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED,
        PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
        PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH,
        PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION,
        PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE,
        PRODUCTION_SOURCE_SCOPE_UNDECLARED,
        PRODUCTION_SOURCE_SCOPE_UNREADABLE,
        ProductionSourceFile,
        ProductionSourceScopeError,
        ProductionSourceScopeEvidence,
        SourceRoot,
        SourceRootSet,
        collect_production_source_files,
        is_declared_production_path,
        iter_production_kotlin_files,
        load_production_source_manifest,
        resolve_production_kotlin_file,
        resolve_production_source_scope,
        resolve_source_root_set_for_test_fixtures,
        scope_evidence,
        validate_production_source_manifest,
        verify_production_source_topology,
    )
except ImportError:  # direct execution from outside the repository root
    from guardrails.production_source_scope import (
        PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES,
        PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED,
        PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
        PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH,
        PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION,
        PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE,
        PRODUCTION_SOURCE_SCOPE_UNDECLARED,
        PRODUCTION_SOURCE_SCOPE_UNREADABLE,
        ProductionSourceFile,
        ProductionSourceScopeError,
        ProductionSourceScopeEvidence,
        SourceRoot,
        SourceRootSet,
        collect_production_source_files,
        is_declared_production_path,
        iter_production_kotlin_files,
        load_production_source_manifest,
        resolve_production_kotlin_file,
        resolve_production_source_scope,
        resolve_source_root_set_for_test_fixtures,
        scope_evidence,
        validate_production_source_manifest,
        verify_production_source_topology,
    )

from .policy_errors import (
    POLICY_ERROR_PATH_ABSOLUTE,
    POLICY_ERROR_PATH_BACKSLASH,
    POLICY_ERROR_PATH_BAD_SEGMENT,
    POLICY_ERROR_PATH_BARE_BASENAME,
    POLICY_ERROR_PATH_DOT_PREFIX,
    POLICY_ERROR_PATH_EMPTY,
    POLICY_ERROR_PATH_NOT_KOTLIN,
    POLICY_ERROR_PATH_NOT_STRING,
    POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT,
)

# ── Historical DB diagnostic names (pure aliases; one live implementation) ──

DB_SOURCE_ROOT_MANIFEST_INVALID = PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID
DB_SOURCE_ROOT_UNDECLARED = PRODUCTION_SOURCE_SCOPE_UNDECLARED
DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED = PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED
DB_SOURCE_ROOT_UNREADABLE = PRODUCTION_SOURCE_SCOPE_UNREADABLE
DB_SOURCE_ROOT_SYMLINK_OUTSIDE = PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE

SOURCE_ROOT_DIAGNOSTIC_CODES = PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES
SOURCE_ROOT_MANIFEST_SCHEMA_VERSION = PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION
SOURCE_ROOT_MANIFEST_RELPATH = PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH

# ── Historical DB function names (pure re-exports) ───────────────────────────
# ``SourceRoot`` / ``SourceRootSet`` / ``is_declared_production_path`` keep
# their neutral names and are imported directly above.

validate_source_root_manifest = validate_production_source_manifest
load_source_root_manifest = load_production_source_manifest
verify_declared_root_topology = verify_production_source_topology
resolve_source_root_set = resolve_source_root_set_for_test_fixtures

# ── DB-seam projection adapters (no scope logic here) ────────────────────────


def collect_production_kotlin_files(repo_root, root_set):
    # type: (object, SourceRootSet) -> Tuple[Tuple[str, ...], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Collect repository-relative POSIX ``.kt`` paths under declared roots.

    DB-seam projection of the neutral
    ``collect_production_source_files()``: byte-identical to the
    pre-PR-GR-10B contract — deterministic root-order then per-root
    canonical path order, fail closed with exactly one bounded diagnostic
    (``DB_SOURCE_ROOT_UNREADABLE`` / ``DB_SOURCE_ROOT_SYMLINK_OUTSIDE``,
    bounded ``target`` context naming the declared root).  Enumeration
    logic lives only in the neutral module.
    """
    files, diagnostics = collect_production_source_files(repo_root, root_set)
    if diagnostics:
        return (), diagnostics
    return tuple(source_file.repository_relative_path for source_file in files), ()


def resolve_canonical_source_file(repo_root, root_set, rel_path):
    # type: (object, SourceRootSet, object) -> Tuple[Optional[str], Optional[str]]
    """Resolve a declared production Kotlin source file to an absolute path.

    DB-seam projection of the neutral ``resolve_production_kotlin_file()``:
    byte-identical to the pre-PR-GR-10B contract —
    ``(absolute_path, None)`` on success, ``(None, code)`` with exactly one
    controlled ``DB_SOURCE_ROOT_*`` diagnostic code otherwise.  Resolution
    logic lives only in the neutral module.
    """
    source_file, code = resolve_production_kotlin_file(repo_root, root_set, rel_path)
    if source_file is None:
        return None, code
    return source_file.absolute_path, code


# ── DB-specific legacy GR-01 contract (see module docstring) ────────────────
# Approved production source roots (repository-relative POSIX).  Canonical
# policy paths must live under one of these roots — a path cannot point at
# tests, generated code, or any other non-production tree.  Keep in exact
# parity with ``APPROVED_PRODUCTION_SOURCE_ROOTS`` in
# ``scripts/verify_db_access_boundaries.py``.  DB-scoped legacy constant
# only: the neutral production source-scope authority does not carry it,
# and no production guard may rely on it after GR-10B.
APPROVED_PRODUCTION_SOURCE_ROOTS = ("app/src/main/java",)

# Drive-letter absolute form (e.g. ``C:/`` or ``C:\``).  Backslash forms are
# rejected earlier by the separator rule; this mirrors the legacy validator's
# ``^[A-Za-z]:[\\/]`` check.  (The neutral module owns its own copy beside
# the manifest/membership code it serves; this one stays beside the legacy
# GR-01 contract it serves.)
_DRIVE_LETTER_RE = re.compile(r"^[A-Za-z]:[\\/]")


def approved_root_error(path):
    # type: (object) -> Optional[str]
    """Return a controlled error code when ``path`` is not an approved
    production source path, or ``None`` when it is.

    Rule order and semantics mirror ``canonical_policy_path_error()`` in
    ``scripts/verify_db_access_boundaries.py``; each distinct canonical-form
    rejection maps to exactly one closed ``POLICY_ERROR_PATH_*`` code.  The
    return value is always ``None`` or a member of
    ``KNOWN_POLICY_ERROR_CODES`` — never free-form text.
    """
    if not isinstance(path, str):
        return POLICY_ERROR_PATH_NOT_STRING
    p = path.strip()
    if not p:
        return POLICY_ERROR_PATH_EMPTY
    if "\\" in p:
        return POLICY_ERROR_PATH_BACKSLASH
    if p.startswith("/") or p.startswith("\\\\") or _DRIVE_LETTER_RE.match(p):
        return POLICY_ERROR_PATH_ABSOLUTE
    if p.startswith("./") or p == ".":
        return POLICY_ERROR_PATH_DOT_PREFIX
    parts = p.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return POLICY_ERROR_PATH_BAD_SEGMENT
    if len(parts) < 2:
        return POLICY_ERROR_PATH_BARE_BASENAME
    if not p.endswith(".kt"):
        return POLICY_ERROR_PATH_NOT_KOTLIN
    for root in APPROVED_PRODUCTION_SOURCE_ROOTS:
        if p == root or p.startswith(root + "/"):
            return None
    return POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT


def is_approved_source_path(path):
    # type: (object) -> bool
    """Return True only for canonical production source paths under an
    approved root; False for everything else (fail closed)."""
    return approved_root_error(path) is None


__all__ = [
    # Legacy single-root contract (DB-specific; GR-01 consumers only).
    "APPROVED_PRODUCTION_SOURCE_ROOTS",
    "approved_root_error",
    "is_approved_source_path",
    # Historical DB diagnostic names (aliases of the neutral constants).
    "SOURCE_ROOT_DIAGNOSTIC_CODES",
    "SOURCE_ROOT_MANIFEST_SCHEMA_VERSION",
    "SOURCE_ROOT_MANIFEST_RELPATH",
    "DB_SOURCE_ROOT_MANIFEST_INVALID",
    "DB_SOURCE_ROOT_UNDECLARED",
    "DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED",
    "DB_SOURCE_ROOT_UNREADABLE",
    "DB_SOURCE_ROOT_SYMLINK_OUTSIDE",
    # Historical DB seam names (re-exports of the neutral implementation).
    "SourceRoot",
    "SourceRootSet",
    "validate_source_root_manifest",
    "load_source_root_manifest",
    "verify_declared_root_topology",
    "collect_production_kotlin_files",
    "is_declared_production_path",
    "resolve_canonical_source_file",
    "resolve_source_root_set",
    "resolve_source_root_set_for_test_fixtures",
    # Neutral names surfaced for incremental DB-side migration.
    "PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH",
    "PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES",
    "ProductionSourceFile",
    "ProductionSourceScopeError",
    "ProductionSourceScopeEvidence",
    "collect_production_source_files",
    "iter_production_kotlin_files",
    "resolve_production_kotlin_file",
    "resolve_production_source_scope",
    "scope_evidence",
    "validate_production_source_manifest",
    "load_production_source_manifest",
    "verify_production_source_topology",
]
