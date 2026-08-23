"""Shared root contract for approved production source paths.

This module is the single home of ``APPROVED_PRODUCTION_SOURCE_ROOTS`` — the
repository-relative POSIX roots that canonical DB policy paths must live
under (currently only ``app/src/main/java``).  It mirrors the canonical
policy-path validation performed by ``canonical_policy_path_error()`` in
``scripts/verify_db_access_boundaries.py``, but reports every rejection as a
controlled ``POLICY_ERROR_PATH_*`` code from ``scripts/db_guard/policy_errors.py``
instead of human-readable text, so callers can classify failures without
parsing messages and diagnostics stay limited to controlled constants.

A path is an approved production source path only when ALL of the following
hold (fail closed otherwise):

  * it is a non-empty string;
  * it is repository-relative POSIX ('/' separators, no backslash);
  * it is not absolute (no leading '/', drive letter, or UNC prefix);
  * it has no leading './' and no empty / '.' / '..' segments;
  * it is not a bare basename (duplicate basenames exist across packages);
  * it ends in ``.kt``;
  * it equals an approved root or lives below one (segment-aligned prefix,
    never a sloppy string prefix).

Anything else yields exactly one error code from ``approved_root_error``;
``is_approved_source_path`` is defined as ``approved_root_error(path) is
None`` so the two helpers can never disagree.

── Declared source-root manifest layer (PR-GR-03 Slice A) ────────────────────

The second half of this module adds a declarative, multi-root generalization
of the legacy constant above.  A source-root manifest is a YAML document::

    schemaVersion: 1
    roots:
      - module: :app
        sourceSet: main
        path: app/src/main/java

Every rejection is reported as a ``(code, context)`` diagnostic tuple whose
``code`` is a member of the closed ``SOURCE_ROOT_DIAGNOSTIC_CODES`` set and
whose ``context`` carries only bounded structured fields (controlled labels,
small integers, controlled reason constants) — never raw exception text,
stack traces, or filesystem paths discovered at runtime.  Declared manifest
paths themselves are configuration values validated into a canonical form
before use, so echoing them back as a bounded ``target`` field is safe.

Layer responsibilities stay strictly separated:

  * ``validate_source_root_manifest()`` — pure document-shape validation;
  * ``load_source_root_manifest()`` — YAML loading + shape validation
    (topology is deliberately NOT checked here; callers pass their own
    ``repo_root`` to ``verify_declared_root_topology()``);
  * ``verify_declared_root_topology()`` — pure topology-vs-filesystem
    checks for already-validated roots;
  * ``collect_production_kotlin_files()`` — deterministic ``.kt``
    discovery under declared roots (fails closed);
  * ``is_declared_production_path()`` / ``resolve_canonical_source_file()``
    — membership and traversal/symlink-safe resolution of individual paths;
  * ``resolve_source_root_set()`` — the precedence seam (explicit
    ``SourceRootSet`` > checked-in manifest > implicit conventional single
    root) used by inventory consumers (PR-GR-03 Slice C1).

No helper in this module ever calls ``sys.exit``; every failure mode is
returned as diagnostics so callers own process-exit policy.
"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass
from typing import Dict, Optional, Tuple

try:
    import yaml
    _HAS_YAML = True
except ImportError:  # pragma: no cover - exercised only without PyYAML
    _HAS_YAML = False

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

# Approved production source roots (repository-relative POSIX).  Canonical
# policy paths must live under one of these roots — a path cannot point at
# tests, generated code, or any other non-production tree.  Keep in exact
# parity with ``APPROVED_PRODUCTION_SOURCE_ROOTS`` in
# ``scripts/verify_db_access_boundaries.py``.
APPROVED_PRODUCTION_SOURCE_ROOTS = ("app/src/main/java",)

# Drive-letter absolute form (e.g. ``C:/`` or ``C:\``).  Backslash forms are
# rejected earlier by the separator rule; this mirrors the legacy validator's
# ``^[A-Za-z]:[\\/]`` check.
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


# ── Controlled diagnostic codes (closed set) ────────────────────────────────
# Every diagnostic emitted by the declared-manifest layer uses one of these
# codes; the set is closed so unknown reason codes cannot leak into reports.

DB_SOURCE_ROOT_MANIFEST_INVALID = "DB_SOURCE_ROOT_MANIFEST_INVALID"
DB_SOURCE_ROOT_UNDECLARED = "DB_SOURCE_ROOT_UNDECLARED"
DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED = "DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED"
DB_SOURCE_ROOT_UNREADABLE = "DB_SOURCE_ROOT_UNREADABLE"
DB_SOURCE_ROOT_SYMLINK_OUTSIDE = "DB_SOURCE_ROOT_SYMLINK_OUTSIDE"

SOURCE_ROOT_DIAGNOSTIC_CODES = frozenset(
    {
        DB_SOURCE_ROOT_MANIFEST_INVALID,
        DB_SOURCE_ROOT_UNDECLARED,
        DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
        DB_SOURCE_ROOT_UNREADABLE,
        DB_SOURCE_ROOT_SYMLINK_OUTSIDE,
    }
)

# ── Bounded context vocabulary ───────────────────────────────────────────────
# Diagnostic ``context`` dicts use only these controlled labels / reason
# constants plus small integer ``index`` values.  Never raw exception text,
# never runtime-discovered filesystem paths.

_FIELD_DOCUMENT = "<document>"
_NON_STRING_LABEL = "<non-string>"
_FIELD_SCHEMA_VERSION = "schemaVersion"
_FIELD_ROOTS = "roots"
_FIELD_MODULE = "module"
_FIELD_SOURCE_SET = "sourceSet"
_FIELD_PATH = "path"
_TARGET_MANIFEST = "manifest"

_REASON_MALFORMED_YAML = "malformed-yaml"
_REASON_YAML_UNAVAILABLE = "yaml-module-unavailable"
_REASON_NOT_A_STRING = "not-a-string"
_REASON_EMPTY = "empty"
_REASON_BACKSLASH = "backslash"
_REASON_ABSOLUTE = "absolute"
_REASON_BAD_SEGMENT = "bad-segment"
_REASON_WILDCARD = "wildcard"
_REASON_UNSUPPORTED_TAIL = "unsupported-tail"
_REASON_FORBIDDEN_SEGMENT = "forbidden-segment"
_REASON_DUPLICATE_PATH = "duplicate-path"
_REASON_OVERLAPPING_PATH = "overlapping-path"
_REASON_NON_CANONICAL_ORDER = "non-canonical-order"

# ── Manifest schema constants ────────────────────────────────────────────────

#: The only accepted ``schemaVersion`` value.
SOURCE_ROOT_MANIFEST_SCHEMA_VERSION = 1

_MANIFEST_TOP_LEVEL_KEYS = frozenset({"schemaVersion", "roots"})
_ROOT_ENTRY_KEYS = frozenset({"module", "sourceSet", "path"})
_SUPPORTED_SOURCE_SET = "main"
_SUPPORTED_ROOT_TAILS = ("/src/main/java", "/src/main/kotlin")

# Non-production Gradle source trees that must never be declared as
# production roots, matched as exact path segments anywhere in ``path``.
_FORBIDDEN_ROOT_SEGMENTS = frozenset(
    ("test", "debug", "release", "androidTest", "generated", "build")
)

# Declared Gradle module path, e.g. ``:app`` or ``:core:data``.
_MODULE_PATH_RE = re.compile(r":[A-Za-z0-9_-]+(?:[.:][A-Za-z0-9_-]+)*")

# Glob wildcard characters are never legal in declared paths.
_WILDCARD_CHARACTERS = "*?[]"


# ── Immutable models ─────────────────────────────────────────────────────────


@dataclass(frozen=True)
class SourceRoot:
    """One declared production source root (immutable)."""

    module: str
    source_set: str
    path: str


@dataclass(frozen=True)
class SourceRootSet:
    """Immutable, ordered set of declared production source roots.

    ``roots`` preserves manifest order; ``paths`` projects that order as a
    tuple of repository-relative POSIX root paths.
    """

    roots: Tuple[SourceRoot, ...]

    @property
    def paths(self) -> Tuple[str, ...]:
        """Declared root paths in manifest order."""
        return tuple(root.path for root in self.roots)


# ── Internal helpers ─────────────────────────────────────────────────────────


def _declared_root_path_reason(path):
    # type: (object) -> Optional[str]
    """Return a controlled rejection reason for a manifest ``path`` value.

    Returns ``None`` only when ``path`` is a canonical repository-relative
    POSIX directory path ending in ``/src/main/java`` or
    ``/src/main/kotlin`` with no wildcards and no forbidden segments.
    Otherwise returns one of the ``_REASON_*`` constants above.
    """
    if not isinstance(path, str):
        return _REASON_NOT_A_STRING
    if not path:
        return _REASON_EMPTY
    if "\\" in path:
        return _REASON_BACKSLASH
    if path.startswith("/") or path.startswith("\\\\") or _DRIVE_LETTER_RE.match(path):
        return _REASON_ABSOLUTE
    parts = path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return _REASON_BAD_SEGMENT
    if any(character in _WILDCARD_CHARACTERS for character in path):
        return _REASON_WILDCARD
    if not path.endswith(_SUPPORTED_ROOT_TAILS):
        return _REASON_UNSUPPORTED_TAIL
    if any(part in _FORBIDDEN_ROOT_SEGMENTS for part in parts):
        return _REASON_FORBIDDEN_SEGMENT
    return None


def _unknown_key_labels(mapping, allowed_keys):
    # type: (Dict[object, object], frozenset) -> Tuple[str, ...]
    """Sorted labels for disallowed mapping keys.

    Non-string keys collapse to the fixed label ``<non-string>`` so
    arbitrary payloads cannot leak into diagnostics.
    """
    labels = set()
    for key in mapping.keys():
        if isinstance(key, str) and key in allowed_keys:
            continue
        labels.add(key if isinstance(key, str) else _NON_STRING_LABEL)
    return tuple(sorted(labels))


def _manifest_entry_result(entry, index):
    # type: (object, int) -> Tuple[Tuple[Tuple[str, Dict[str, object]], ...], Optional[Tuple[str, str]]]
    """Validate one ``roots`` entry.

    Returns ``(diagnostics, parsed)`` where ``parsed`` is ``(module, path)``
    for a fully valid entry and ``None`` otherwise.
    """
    diagnostics = []
    if not isinstance(entry, dict):
        diagnostics.append(
            (
                DB_SOURCE_ROOT_MANIFEST_INVALID,
                {"field": _FIELD_ROOTS, "index": index},
            )
        )
        return tuple(diagnostics), None

    for label in _unknown_key_labels(entry, _ROOT_ENTRY_KEYS):
        diagnostics.append(
            (DB_SOURCE_ROOT_MANIFEST_INVALID, {"field": label, "index": index})
        )

    present = {key for key in entry.keys() if isinstance(key, str)}
    for required in sorted(_ROOT_ENTRY_KEYS - present):
        diagnostics.append(
            (DB_SOURCE_ROOT_MANIFEST_INVALID, {"field": required, "index": index})
        )
    if present != _ROOT_ENTRY_KEYS:
        return tuple(diagnostics), None

    module_value = entry["module"]
    if (
        not isinstance(module_value, str)
        or _MODULE_PATH_RE.fullmatch(module_value) is None
    ):
        diagnostics.append(
            (DB_SOURCE_ROOT_MANIFEST_INVALID, {"field": _FIELD_MODULE, "index": index})
        )

    source_set_value = entry["sourceSet"]
    if (
        not isinstance(source_set_value, str)
        or source_set_value != _SUPPORTED_SOURCE_SET
    ):
        diagnostics.append(
            (
                DB_SOURCE_ROOT_MANIFEST_INVALID,
                {"field": _FIELD_SOURCE_SET, "index": index},
            )
        )

    path_value = entry["path"]
    path_reason = _declared_root_path_reason(path_value)
    if path_reason is not None:
        diagnostics.append(
            (
                DB_SOURCE_ROOT_MANIFEST_INVALID,
                {"field": _FIELD_PATH, "index": index, "reason": path_reason},
            )
        )

    if diagnostics:
        return tuple(diagnostics), None
    return (), (module_value, path_value)


def _realpath_contains(parent_abs, child_abs):
    # type: (str, str) -> bool
    """True when ``child_abs``'s real path equals or lives below
    ``parent_abs``'s real path (case-normalized for Windows)."""
    parent = os.path.normcase(os.path.realpath(parent_abs))
    child = os.path.normcase(os.path.realpath(child_abs))
    if child == parent:
        return True
    return child.startswith(parent + os.sep)


class _SourceTreeUnreadable(Exception):
    """Internal signal: part of a declared source tree could not be read."""


def _collect_kotlin_under(directory_abs, rel_prefix, out):
    # type: (str, str, list) -> None
    """Depth-first collect of readable ``.kt`` file paths below a root.

    Raises ``_SourceTreeUnreadable`` (fail closed) when any directory cannot
    be listed or any ``.kt`` entry is not a readable regular file.
    """
    try:
        names = os.listdir(directory_abs)
    except OSError:
        raise _SourceTreeUnreadable()
    for name in sorted(names):
        entry_abs = os.path.join(directory_abs, name)
        entry_rel = rel_prefix + "/" + name
        if os.path.isdir(entry_abs):
            _collect_kotlin_under(entry_abs, entry_rel, out)
        elif name.endswith(".kt"):
            if not os.path.isfile(entry_abs) or not os.access(entry_abs, os.R_OK):
                raise _SourceTreeUnreadable()
            out.append(entry_rel)


# ── Manifest validation (pure document shape) ────────────────────────────────


def validate_source_root_manifest(data):
    # type: (object) -> Tuple[Tuple[str, Dict[str, object]], ...]
    """Validate an in-memory source-root manifest document.

    Returns an empty tuple when the document is valid; otherwise returns one
    or more ``(code, context)`` diagnostics where ``code`` is
    ``DB_SOURCE_ROOT_MANIFEST_INVALID`` and ``context`` carries only bounded
    structured fields (controlled labels, indices, controlled reasons).

    Contract enforced (fail closed on every violation):

      * top-level keys are exactly ``{schemaVersion, roots}``;
      * ``schemaVersion`` is the integer ``1`` (booleans rejected);
      * ``roots`` is a non-empty list of mappings whose keys are exactly
        ``{module, sourceSet, path}``;
      * ``module`` matches ``^:[A-Za-z0-9_-]+([.:][A-Za-z0-9_-]+)*$``
        (a declared Gradle module path such as ``:app`` or ``:core:data``);
      * ``sourceSet`` is exactly ``main``;
      * ``path`` is a repository-relative POSIX directory ending in
        ``/src/main/java`` or ``/src/main/kotlin`` — no absolute form, no
        backslashes, no ``.``/``..``/empty segments, no wildcards, and none
        of the ``test``/``debug``/``release``/``androidTest``/
        ``generated``/``build`` segments anywhere in the path;
      * declared paths are unique (no duplicates);
      * no declared path lives below another declared path (no overlap);
      * entries appear in canonical order: sorted by ``(module, path)``.
    """
    if not isinstance(data, dict):
        return ((DB_SOURCE_ROOT_MANIFEST_INVALID, {"field": _FIELD_DOCUMENT}),)

    diagnostics = []

    for label in _unknown_key_labels(data, _MANIFEST_TOP_LEVEL_KEYS):
        diagnostics.append((DB_SOURCE_ROOT_MANIFEST_INVALID, {"field": label}))

    present = {key for key in data.keys() if isinstance(key, str)}
    for required in sorted(_MANIFEST_TOP_LEVEL_KEYS - present):
        diagnostics.append((DB_SOURCE_ROOT_MANIFEST_INVALID, {"field": required}))

    if "schemaVersion" in present:
        schema_version = data["schemaVersion"]
        if (
            isinstance(schema_version, bool)
            or not isinstance(schema_version, int)
            or schema_version != SOURCE_ROOT_MANIFEST_SCHEMA_VERSION
        ):
            diagnostics.append(
                (
                    DB_SOURCE_ROOT_MANIFEST_INVALID,
                    {"field": _FIELD_SCHEMA_VERSION},
                )
            )

    entries = None
    if "roots" in present:
        roots_value = data["roots"]
        if not isinstance(roots_value, list) or not roots_value:
            diagnostics.append(
                (DB_SOURCE_ROOT_MANIFEST_INVALID, {"field": _FIELD_ROOTS})
            )
        else:
            entries = roots_value

    if entries is None:
        return tuple(diagnostics)

    entry_errors = []
    parsed = []  # (entry index, module, path) for fully valid entries
    for index, entry in enumerate(entries):
        entry_diagnostics, entry_parsed = _manifest_entry_result(entry, index)
        if entry_diagnostics:
            entry_errors.extend(entry_diagnostics)
        else:
            assert entry_parsed is not None
            parsed.append((index, entry_parsed[0], entry_parsed[1]))

    if entry_errors:
        diagnostics.extend(entry_errors)
        return tuple(diagnostics)

    # Cross-entry checks run only on fully well-formed manifests so each
    # diagnostic class stays observable in isolation.
    seen_paths = set()
    for index, _module, path in parsed:
        if path in seen_paths:
            diagnostics.append(
                (
                    DB_SOURCE_ROOT_MANIFEST_INVALID,
                    {
                        "field": _FIELD_PATH,
                        "index": index,
                        "reason": _REASON_DUPLICATE_PATH,
                    },
                )
            )
        else:
            seen_paths.add(path)

    for i in range(len(parsed)):
        path_i = parsed[i][2]
        for j in range(i + 1, len(parsed)):
            path_j = parsed[j][2]
            if path_i == path_j:
                continue  # duplicates already reported above
            if path_j.startswith(path_i + "/") or path_i.startswith(path_j + "/"):
                diagnostics.append(
                    (
                        DB_SOURCE_ROOT_MANIFEST_INVALID,
                        {
                            "field": _FIELD_PATH,
                            "index": parsed[j][0],
                            "reason": _REASON_OVERLAPPING_PATH,
                        },
                    )
                )

    actual_order = [(module, path) for _index, module, path in parsed]
    canonical_order = sorted(actual_order)
    if actual_order != canonical_order:
        diagnostics.append(
            (
                DB_SOURCE_ROOT_MANIFEST_INVALID,
                {"field": _FIELD_ROOTS, "reason": _REASON_NON_CANONICAL_ORDER},
            )
        )

    return tuple(diagnostics)


# ── Manifest loading (YAML + shape; topology is a separate API) ─────────────


def load_source_root_manifest(path):
    # type: (str) -> Tuple[Optional[SourceRootSet], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Load and validate a source-root manifest YAML document.

    Returns ``(SourceRootSet, ())`` on success, or ``(None, diagnostics)``
    on any failure:

      * PyYAML unavailable        -> ``DB_SOURCE_ROOT_MANIFEST_INVALID``
        (context reason ``yaml-module-unavailable``);
      * file missing / unreadable -> ``DB_SOURCE_ROOT_UNREADABLE``;
      * malformed YAML            -> ``DB_SOURCE_ROOT_MANIFEST_INVALID``
        (context reason ``malformed-yaml``);
      * non-mapping document or any shape violation
                                  -> ``DB_SOURCE_ROOT_MANIFEST_INVALID``.

    Topology is intentionally NOT verified here — callers that know the
    repository root call ``verify_declared_root_topology()`` themselves.
    """
    if not _HAS_YAML:
        return None, (
            (DB_SOURCE_ROOT_MANIFEST_INVALID, {"reason": _REASON_YAML_UNAVAILABLE}),
        )

    if not os.path.exists(path):
        return None, ((DB_SOURCE_ROOT_UNREADABLE, {"target": _TARGET_MANIFEST}),)

    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
    except yaml.YAMLError:
        return None, (
            (DB_SOURCE_ROOT_MANIFEST_INVALID, {"reason": _REASON_MALFORMED_YAML}),
        )
    except (OSError, UnicodeDecodeError):
        return None, ((DB_SOURCE_ROOT_UNREADABLE, {"target": _TARGET_MANIFEST}),)

    if not isinstance(data, dict):
        return None, ((DB_SOURCE_ROOT_MANIFEST_INVALID, {"field": _FIELD_DOCUMENT}),)

    diagnostics = validate_source_root_manifest(data)
    if diagnostics:
        return None, diagnostics

    roots = tuple(
        SourceRoot(
            module=entry["module"],
            source_set=entry["sourceSet"],
            path=entry["path"],
        )
        for entry in data["roots"]
    )
    return SourceRootSet(roots=roots), ()


# ── Topology verification (pure filesystem-vs-declaration checks) ───────────


def verify_declared_root_topology(repo_root, root_set):
    # type: (str, SourceRootSet) -> Tuple[Tuple[str, Dict[str, object]], ...]
    """Verify each declared root exists, is readable, and stays inside
    ``repo_root`` when resolved.

    Pure topology-vs-filesystem checks only — the manifest is assumed to
    have been validated already (``validate_source_root_manifest()``).
    Per declared root (checked in manifest order, all roots reported):

      * directory missing / not a directory / not readable
        -> ``DB_SOURCE_ROOT_UNREADABLE``;
      * root itself is a symlink, or its real path resolves outside
        ``repo_root`` (including through a symlinked ancestor)
        -> ``DB_SOURCE_ROOT_SYMLINK_OUTSIDE``.

    Diagnostics carry the declared repository-relative path as bounded
    ``target`` context — never runtime-discovered filesystem paths.
    Individual symlinked *files* are rejected later at resolution time by
    ``resolve_canonical_source_file()``.
    """
    diagnostics = []
    for root in root_set.roots:
        root_abs = os.path.join(os.path.abspath(repo_root), *root.path.split("/"))
        if not os.path.isdir(root_abs) or not os.access(root_abs, os.R_OK | os.X_OK):
            diagnostics.append((DB_SOURCE_ROOT_UNREADABLE, {"target": root.path}))
            continue
        if os.path.islink(root_abs) or not _realpath_contains(repo_root, root_abs):
            diagnostics.append(
                (DB_SOURCE_ROOT_SYMLINK_OUTSIDE, {"target": root.path})
            )
    return tuple(diagnostics)


# ── Production Kotlin discovery (deterministic, fail closed) ────────────────


def collect_production_kotlin_files(repo_root, root_set):
    # type: (str, SourceRootSet) -> Tuple[Tuple[str, ...], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Collect repository-relative POSIX ``.kt`` paths under declared roots.

    Walks each declared root in manifest order and sorts each root's files
    by canonical relative path, so the result is fully deterministic for a
    given manifest and tree.  No filtering beyond the ``.kt`` suffix is
    applied: roots are already validated and topology is verified separately
    via ``verify_declared_root_topology()``.

    Fails closed: if any directory cannot be listed or any ``.kt`` entry is
    not a readable regular file, returns an empty tuple plus a single
    ``DB_SOURCE_ROOT_UNREADABLE`` diagnostic naming the declared root
    (bounded ``target`` context, never the failing filesystem path).
    """
    collected = []
    repo_abs = os.path.abspath(repo_root)
    for root in root_set.roots:
        per_root = []
        try:
            _collect_kotlin_under(
                os.path.join(repo_abs, *root.path.split("/")), root.path, per_root
            )
        except _SourceTreeUnreadable:
            return (), ((DB_SOURCE_ROOT_UNREADABLE, {"target": root.path}),)
        per_root.sort()
        collected.extend(per_root)
    return tuple(collected), ()


# ── Membership and safe resolution ───────────────────────────────────────────


def is_declared_production_path(root_set, rel_posix_path):
    # type: (SourceRootSet, object) -> bool
    """True iff ``rel_posix_path`` equals a declared root or lives below one.

    Membership is segment-aligned (never a sloppy string prefix) and the
    path must be canonical repository-relative POSIX: non-strings, empty
    values, backslashes, absolute forms, and empty/``.``/``..`` segments all
    return False (fail closed).  This is a pure declaration-membership
    check; filesystem resolution belongs to
    ``resolve_canonical_source_file()``.
    """
    if not isinstance(rel_posix_path, str) or not rel_posix_path:
        return False
    if "\\" in rel_posix_path:
        return False
    if rel_posix_path.startswith("/") or _DRIVE_LETTER_RE.match(rel_posix_path):
        return False
    parts = rel_posix_path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return False
    for root in root_set.roots:
        root_parts = root.path.split("/")
        if parts[: len(root_parts)] == root_parts:
            return True
    return False


def resolve_canonical_source_file(repo_root, root_set, rel_path):
    # type: (str, SourceRootSet, object) -> Tuple[Optional[str], Optional[str]]
    """Resolve a declared production Kotlin source file to an absolute path.

    Returns ``(absolute_path, None)`` on success, or ``(None, code)`` with
    exactly one diagnostic code:

      * ``DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED`` — not a canonical
        repository-relative POSIX ``.kt`` path (wrong type, empty, absolute
        form, backslashes, ``.``/``..`` traversal segments, wildcards, bare
        basenames, or a non-``.kt`` file);
      * ``DB_SOURCE_ROOT_UNDECLARED`` — canonical ``.kt`` path but not
        under any declared root;
      * ``DB_SOURCE_ROOT_UNREADABLE`` — target missing, not a regular
        file, or not readable;
      * ``DB_SOURCE_ROOT_SYMLINK_OUTSIDE`` — target itself is a symlink,
        or its real path resolves outside ``repo_root``.

    The returned absolute path is normalized but not fully resolved; the
    real-path resolution is used only for containment validation.
    """
    if not isinstance(rel_path, str):
        return None, DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED
    if (
        not rel_path
        or "\\" in rel_path
        or rel_path.startswith("/")
        or rel_path.startswith("./")
        or _DRIVE_LETTER_RE.match(rel_path)
        or any(character in _WILDCARD_CHARACTERS for character in rel_path)
        or not rel_path.endswith(".kt")
    ):
        return None, DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED
    parts = rel_path.split("/")
    if len(parts) < 2 or any(part in ("", ".", "..") for part in parts):
        return None, DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED

    if not is_declared_production_path(root_set, rel_path):
        return None, DB_SOURCE_ROOT_UNDECLARED

    candidate = os.path.join(os.path.abspath(repo_root), *parts)
    if not os.path.isfile(candidate) or not os.access(candidate, os.R_OK):
        return None, DB_SOURCE_ROOT_UNREADABLE
    if os.path.islink(candidate) or not _realpath_contains(repo_root, candidate):
        return None, DB_SOURCE_ROOT_SYMLINK_OUTSIDE
    return candidate, None


# ── Root-set resolution (PR-GR-03 Slice C1) ──────────────────────────────────

#: Repository-relative POSIX location of the checked-in source-root manifest,
#: resolved against the repository root by ``resolve_source_root_set``.
SOURCE_ROOT_MANIFEST_RELPATH = "config/guards/production_source_roots.yml"

#: Declared module label for implicitly resolved (non-manifest) roots.  The
#: label is a controlled constant; implicit roots carry no real Gradle module
#: identity because none was declared.
_IMPLICIT_MODULE = ":implicit"

# Controlled reason constants (diagnostic context ``reason`` values only).
_REASON_NO_CONVENTIONAL_ROOT = "no-conventional-root"
_REASON_REPO_ROOT_NOT_A_PATH = "repo-root-not-a-path"
_REASON_EXPLICIT_TYPE = "explicit-not-a-source-root-set"

# Conventional production source-directory tails, matched as exact native
# separator segments of the normalized absolute repository root.
_JAVA_TAIL = ("src", "main", "java")
_KOTLIN_TAIL = ("src", "main", "kotlin")
# Legacy compatibility tails from the pre-manifest inventory API: inputs
# ending with ``src/main`` or ``src`` resolved to the ``src/main/java``
# directory below them.  Kept so existing embedders and fixtures keep
# working; they never widen acceptance beyond a conventional java layout.
_LEGACY_INTERMEDIATE_TAILS = (("src", "main"), ("src",))


def _split_native(path_abs):
    # type: (str) -> Tuple[str, ...]
    """Split a normalized absolute path into native separator segments."""
    return tuple(os.path.normpath(path_abs).split(os.sep))


def _implicit_conventional_dir(repo_abs):
    # type: (str) -> Optional[str]
    """Return the implicit conventional production source directory for
    ``repo_abs``, or ``None`` when no conventional root exists.

    Branch order:

      * ``repo_abs`` ends with ``src/main/java`` or ``src/main/kotlin``
        -> that directory itself;
      * legacy compatibility: ``repo_abs`` ends with ``src/main`` or
        ``src`` -> the ``src/main/java`` directory below it, when that
        directory exists;
      * otherwise ``<repo>/app/src/main/java``, then
        ``<repo>/src/main/java``, when the directory exists.

    The implicit branch exists solely for synthetic test fixtures and
    embedders without a manifest; real repositories always ship the
    manifest, which takes precedence whenever its file exists.
    """
    parts = _split_native(repo_abs)
    if parts[-3:] == _JAVA_TAIL or parts[-3:] == _KOTLIN_TAIL:
        return repo_abs
    for index, tail in enumerate(_LEGACY_INTERMEDIATE_TAILS):
        if parts[-len(tail):] == tail:
            filler = ("java",) if index == 0 else ("main", "java")
            candidate = os.path.join(repo_abs, *filler)
            if os.path.isdir(candidate):
                return candidate
            return None
    for rel in ("app/src/main/java", "src/main/java"):
        candidate = os.path.join(repo_abs, *rel.split("/"))
        if os.path.isdir(candidate):
            return candidate
    return None


def resolve_source_root_set(repo_root, explicit=None):
    # type: (object, object) -> Tuple[Optional[SourceRootSet], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Resolve the effective production source-root set for ``repo_root``.

    Returns ``(SourceRootSet, ())`` on success or ``(None, diagnostics)``
    on failure, with precedence (fail closed):

      (a) ``explicit`` ``SourceRootSet`` — used exactly as-is; the caller
          vouches for it and topology is re-checked at walk time;
      (b) the manifest file ``<repo_root>/config/guards/
          production_source_roots.yml`` when that FILE exists — loaded,
          shape-validated, and topology-verified; ANY diagnostic fails
          closed.  A present-but-malformed manifest NEVER falls back to
          the implicit conventions;
      (c) manifest file absent -> the implicit conventional single root
          (see ``_implicit_conventional_dir``); with no conventional root
          at all the result is ``(None, ((DB_SOURCE_ROOT_UNDECLARED,
          {"reason": "no-conventional-root"}),))``.

    Branch (c) exists solely for synthetic test fixtures and embedders
    without a manifest; real repositories always ship the manifest.

    When ``repo_root`` itself is the conventional source directory (or a
    legacy ``src/main``/``src`` directory above one), the single returned
    root carries that directory as an ABSOLUTE native-separator path so
    callers can both walk it and anchor emitted repository-relative POSIX
    paths at the enclosing project.  A non-``SourceRootSet`` explicit
    value or a non-path-like repository root fails closed with
    ``DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED`` and a controlled reason.

    Never raises for resolution outcomes; never calls ``sys.exit``.
    """
    if explicit is not None:
        if isinstance(explicit, SourceRootSet):
            return explicit, ()
        return None, (
            (DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED, {"reason": _REASON_EXPLICIT_TYPE}),
        )
    try:
        repo_abs = os.path.abspath(os.fspath(repo_root))
    except (TypeError, ValueError):
        return None, (
            (DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED, {"reason": _REASON_REPO_ROOT_NOT_A_PATH}),
        )
    manifest_path = os.path.join(repo_abs, *SOURCE_ROOT_MANIFEST_RELPATH.split("/"))
    if os.path.isfile(manifest_path):
        root_set, load_diagnostics = load_source_root_manifest(manifest_path)
        if root_set is None or load_diagnostics:
            return None, tuple(load_diagnostics)
        topology_diagnostics = verify_declared_root_topology(repo_abs, root_set)
        if topology_diagnostics:
            return None, topology_diagnostics
        return root_set, ()
    try:
        conventional = _implicit_conventional_dir(repo_abs)
    except OSError:
        conventional = None
    if conventional is None:
        return None, (
            (DB_SOURCE_ROOT_UNDECLARED, {"reason": _REASON_NO_CONVENTIONAL_ROOT}),
        )
    implicit_root = SourceRoot(
        module=_IMPLICIT_MODULE, source_set="main", path=conventional
    )
    return SourceRootSet(roots=(implicit_root,)), ()


__all__ = [
    # Legacy single-root contract (kept working for GR-01 consumers).
    "APPROVED_PRODUCTION_SOURCE_ROOTS",
    "approved_root_error",
    "is_approved_source_path",
    # Declared source-root manifest layer (PR-GR-03 Slice A).
    "SOURCE_ROOT_DIAGNOSTIC_CODES",
    "SOURCE_ROOT_MANIFEST_SCHEMA_VERSION",
    "DB_SOURCE_ROOT_MANIFEST_INVALID",
    "DB_SOURCE_ROOT_UNDECLARED",
    "DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED",
    "DB_SOURCE_ROOT_UNREADABLE",
    "DB_SOURCE_ROOT_SYMLINK_OUTSIDE",
    "SourceRoot",
    "SourceRootSet",
    "validate_source_root_manifest",
    "load_source_root_manifest",
    "verify_declared_root_topology",
    "collect_production_kotlin_files",
    "is_declared_production_path",
    "resolve_canonical_source_file",
    # Root-set resolution seam (PR-GR-03 Slice C1).
    "SOURCE_ROOT_MANIFEST_RELPATH",
    "resolve_source_root_set",
]
