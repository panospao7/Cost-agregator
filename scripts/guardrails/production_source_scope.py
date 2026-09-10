"""Neutral production source-scope authority (PR-GR-10B Slice 1).

This module is the ONE live implementation of production source-scope
authority for every repository-level guard: strict manifest loading and
validation, topology verification, root-set resolution, deterministic
production Kotlin enumeration, declared-path membership, safe source-file
resolution, and scope-evidence hashing.  The authoritative production
source roots come ONLY from the checked-in manifest at
``config/guards/production_source_roots.yml``.  There is NO conventional
``app/src/main/java`` fallback in any repository-level API, and no helper
in this module ever calls ``sys.exit``: every failure mode is returned as
``(value, diagnostics)`` so callers own process-exit policy.

Historical note (wire vocabulary): the controlled diagnostic-code string
values below keep their historical ``DB_SOURCE_ROOT_*`` spelling so DB-layer
reports and baselines produced before PR-GR-10B stay byte-identical.  New
consumers use the neutral ``PRODUCTION_SOURCE_SCOPE_*`` names;
``scripts/db_guard/source_roots.py`` re-exports the same objects under the
historical ``DB_SOURCE_ROOT_*`` Python names.

Every diagnostic is a ``(code, context)`` tuple whose ``code`` is a member
of the closed ``PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES`` set and whose
``context`` carries only bounded structured fields (controlled labels,
small integers, controlled reason constants) — never raw exception text,
stack traces, or filesystem paths discovered at runtime.  Declared manifest
paths are configuration values validated into a canonical form before use,
so echoing them back as a bounded ``target`` field is safe.

Layer responsibilities stay strictly separated:

  * ``validate_production_source_manifest()`` — pure document-shape
    validation;
  * ``load_production_source_manifest()`` — YAML loading + shape validation
    (topology is deliberately NOT checked here; callers pass their own
    ``repo_root`` to ``verify_production_source_topology()``);
  * ``verify_production_source_topology()`` — pure topology-vs-filesystem
    checks for already-validated roots (the Gradle include / module
    topology cross-check remains with the meta-guard
    ``scripts/ci/verify_production_source_roots.py`` until its GR-10B
    slice);
  * ``resolve_production_source_scope()`` — repository-level resolution:
    explicit ``SourceRootSet`` > checked-in manifest; ANY diagnostic fails
    closed; an absent manifest fails closed with
    ``UNDECLARED``/``manifest-absent`` (NO conventional-root fallback);
  * ``collect_production_source_files()`` /
    ``iter_production_kotlin_files()`` — deterministic root-order then
    canonical path-order ``ProductionSourceFile`` enumeration (fail
    closed);
  * ``is_declared_production_path()`` /
    ``resolve_production_kotlin_file()`` — membership and traversal/
    symlink-safe resolution of individual paths;
  * ``scope_evidence()`` — roots, source-file count, ordered file-list
    hash, and manifest hash for registry evidence;
  * ``resolve_source_root_set_for_test_fixtures()`` — the ONLY place the
    implicit conventional-root fallback survives (PR-GR-10B §3): an
    explicitly named TEST-FIXTURE seam for synthetic repositories without
    a manifest.  Repository-level guards, suites, ratchets, and Gradle
    tasks must never call it.

``ProductionSourceFile`` rules (PR-GR-10B §4): ``repository_relative_path``
is repository-relative POSIX in reports; files are regular readable
``.kt`` files; symlinked directories and ``.kt`` files — including any
real path escaping the repository — fail closed during enumeration and
resolution; roots are walked in manifest order with per-root canonical
path order; overlapping roots are impossible (manifest validation rejects
duplicate and nested declared paths).
"""
from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass
from typing import Dict, Iterator, Optional, Tuple

try:
    import yaml
    _HAS_YAML = True
except ImportError:  # pragma: no cover - exercised only without PyYAML
    _HAS_YAML = False

# ── Controlled diagnostic codes (closed set) ────────────────────────────────
# Every diagnostic emitted by this module uses one of these codes; the set is
# closed so unknown reason codes cannot leak into reports.  The string values
# are the historical ``DB_SOURCE_ROOT_*`` wire vocabulary (see module
# docstring) — kept verbatim for byte-identical DB-layer reports.

PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID = "DB_SOURCE_ROOT_MANIFEST_INVALID"
PRODUCTION_SOURCE_SCOPE_UNDECLARED = "DB_SOURCE_ROOT_UNDECLARED"
PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED = "DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED"
PRODUCTION_SOURCE_SCOPE_UNREADABLE = "DB_SOURCE_ROOT_UNREADABLE"
PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE = "DB_SOURCE_ROOT_SYMLINK_OUTSIDE"

PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES = frozenset(
    {
        PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
        PRODUCTION_SOURCE_SCOPE_UNDECLARED,
        PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED,
        PRODUCTION_SOURCE_SCOPE_UNREADABLE,
        PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE,
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
PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION = 1

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

# Drive-letter absolute form (e.g. ``C:/`` or ``C:\``).  Backslash forms are
# rejected earlier by the separator rule.  (The legacy GR-01 contract in
# ``scripts/db_guard/source_roots.py`` keeps its own documented copy of this
# constant beside the DB-specific code it serves.)
_DRIVE_LETTER_RE = re.compile(r"^[A-Za-z]:[\\/]")


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


@dataclass(frozen=True)
class ProductionSourceFile:
    """One enumerated production Kotlin source file (immutable value object).

    ``repository_relative_path`` is repository-relative POSIX (the
    report-facing form); ``absolute_path`` is the normalized (not fully
    resolved) filesystem path; ``root_path`` is the absolute anchor of the
    declared root the file was found under; ``module`` / ``source_set``
    carry the declaring manifest entry's identity.  Files are regular
    readable ``.kt`` files only, reached through deterministic root-order
    then canonical path-order traversal with symlink-escape fail-closed
    checks (PR-GR-10B §4).
    """

    repository_relative_path: str
    absolute_path: str
    root_path: str
    module: str
    source_set: str

    # Plan-literal camelCase aliases (PR-GR-10B §4 names the fields
    # ``repositoryRelativePath`` / ``absolutePath`` / ``rootPath`` /
    # ``sourceSet``); the snake_case fields above are the Python-canonical
    # form used by the implementation.
    @property
    def repositoryRelativePath(self) -> str:
        return self.repository_relative_path

    @property
    def absolutePath(self) -> str:
        return self.absolute_path

    @property
    def rootPath(self) -> str:
        return self.root_path

    @property
    def sourceSet(self) -> str:
        return self.source_set


@dataclass(frozen=True)
class ProductionSourceScopeEvidence:
    """Scope evidence for one verified production source scope (immutable).

    * ``roots`` — declared root paths in manifest order;
    * ``source_file_count`` — number of enumerated production ``.kt`` files;
    * ``ordered_file_list_hash`` — SHA-256 hex digest over the ordered
      repository-relative POSIX paths, each encoded UTF-8 and followed by a
      single ``\\n`` (order- and membership-sensitive; content-independent);
    * ``manifest_hash`` — SHA-256 hex digest over the raw manifest file
      bytes.
    """

    roots: Tuple[str, ...]
    source_file_count: int
    ordered_file_list_hash: str
    manifest_hash: str


class ProductionSourceScopeError(Exception):
    """Controlled fail-closed enumeration error (iterator seam only).

    Carries ONLY the controlled diagnostic ``code`` string — never
    filesystem paths or exception text.  Batch APIs return diagnostics
    instead of raising; the iterator form raises this so a partially
    consumed traversal can never be mistaken for a complete scope.
    """

    def __init__(self, code):
        # type: (str) -> None
        super().__init__(code)
        self.code = code


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
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                {"field": _FIELD_ROOTS, "index": index},
            )
        )
        return tuple(diagnostics), None

    for label in _unknown_key_labels(entry, _ROOT_ENTRY_KEYS):
        diagnostics.append(
            (
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                {"field": label, "index": index},
            )
        )

    present = {key for key in entry.keys() if isinstance(key, str)}
    for required in sorted(_ROOT_ENTRY_KEYS - present):
        diagnostics.append(
            (
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                {"field": required, "index": index},
            )
        )
    if present != _ROOT_ENTRY_KEYS:
        return tuple(diagnostics), None

    module_value = entry["module"]
    if (
        not isinstance(module_value, str)
        or _MODULE_PATH_RE.fullmatch(module_value) is None
    ):
        diagnostics.append(
            (
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                {"field": _FIELD_MODULE, "index": index},
            )
        )

    source_set_value = entry["sourceSet"]
    if (
        not isinstance(source_set_value, str)
        or source_set_value != _SUPPORTED_SOURCE_SET
    ):
        diagnostics.append(
            (
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                {"field": _FIELD_SOURCE_SET, "index": index},
            )
        )

    path_value = entry["path"]
    path_reason = _declared_root_path_reason(path_value)
    if path_reason is not None:
        diagnostics.append(
            (
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
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


class _SourceTreeError(Exception):
    """Internal signal: a declared source tree could not be enumerated.

    ``code`` is the controlled diagnostic code for the failure class; the
    exception never carries filesystem paths or exception text.
    """

    def __init__(self, code):
        # type: (str) -> None
        super().__init__(code)
        self.code = code


def _collect_kotlin_sources_under(repo_abs, directory_abs, rel_prefix, out):
    # type: (str, str, str, list) -> None
    """Depth-first collect of readable ``.kt`` files below a declared root.

    Appends ``(repository_relative_posix_path, absolute_path)`` pairs in
    sorted-name depth-first order; callers sort each root's pairs by
    relative path for the canonical order.  Raises ``_SourceTreeError``
    (fail closed) when any directory cannot be listed, any ``.kt`` entry
    is not a readable regular file, or any descended directory or ``.kt``
    entry is a symlink or resolves outside the repository (PR-GR-10B §4:
    symlink escapes and non-deterministic link traversal fail closed;
    non-``.kt`` file entries are ignored exactly as before).
    """
    try:
        names = os.listdir(directory_abs)
    except OSError:
        raise _SourceTreeError(PRODUCTION_SOURCE_SCOPE_UNREADABLE)
    for name in sorted(names):
        entry_abs = os.path.join(directory_abs, name)
        entry_rel = rel_prefix + "/" + name
        if os.path.isdir(entry_abs):
            if os.path.islink(entry_abs) or not _realpath_contains(
                repo_abs, entry_abs
            ):
                raise _SourceTreeError(PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE)
            _collect_kotlin_sources_under(repo_abs, entry_abs, entry_rel, out)
        elif name.endswith(".kt"):
            if os.path.islink(entry_abs) or not _realpath_contains(
                repo_abs, entry_abs
            ):
                raise _SourceTreeError(PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE)
            if not os.path.isfile(entry_abs) or not os.access(entry_abs, os.R_OK):
                raise _SourceTreeError(PRODUCTION_SOURCE_SCOPE_UNREADABLE)
            out.append((entry_rel, entry_abs))


def _matching_declared_root(root_set, rel_posix_path):
    # type: (SourceRootSet, object) -> Optional[SourceRoot]
    """Return the declared root declaring ``rel_posix_path``, or ``None``.

    Membership is segment-aligned (never a sloppy string prefix) and the
    path must be canonical repository-relative POSIX: non-strings, empty
    values, backslashes, absolute forms, and empty/``.``/``..`` segments
    all return ``None`` (fail closed).  This is a pure
    declaration-membership check; filesystem resolution belongs to
    ``resolve_production_kotlin_file()``.
    """
    if not isinstance(rel_posix_path, str) or not rel_posix_path:
        return None
    if "\\" in rel_posix_path:
        return None
    if rel_posix_path.startswith("/") or _DRIVE_LETTER_RE.match(rel_posix_path):
        return None
    parts = rel_posix_path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return None
    for root in root_set.roots:
        root_parts = root.path.split("/")
        if parts[: len(root_parts)] == root_parts:
            return root
    return None


# ── Manifest validation (pure document shape) ────────────────────────────────


def validate_production_source_manifest(data):
    # type: (object) -> Tuple[Tuple[str, Dict[str, object]], ...]
    """Validate an in-memory production source-root manifest document.

    Returns an empty tuple when the document is valid; otherwise returns one
    or more ``(code, context)`` diagnostics where ``code`` is
    ``PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID`` and ``context`` carries
    only bounded structured fields (controlled labels, indices, controlled
    reasons).

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
        return (
            (PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID, {"field": _FIELD_DOCUMENT}),
        )

    diagnostics = []

    for label in _unknown_key_labels(data, _MANIFEST_TOP_LEVEL_KEYS):
        diagnostics.append((PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID, {"field": label}))

    present = {key for key in data.keys() if isinstance(key, str)}
    for required in sorted(_MANIFEST_TOP_LEVEL_KEYS - present):
        diagnostics.append(
            (PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID, {"field": required})
        )

    if "schemaVersion" in present:
        schema_version = data["schemaVersion"]
        if (
            isinstance(schema_version, bool)
            or not isinstance(schema_version, int)
            or schema_version != PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION
        ):
            diagnostics.append(
                (
                    PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                    {"field": _FIELD_SCHEMA_VERSION},
                )
            )

    entries = None
    if "roots" in present:
        roots_value = data["roots"]
        if not isinstance(roots_value, list) or not roots_value:
            diagnostics.append(
                (PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID, {"field": _FIELD_ROOTS})
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
                    PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
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
                        PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
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
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                {"field": _FIELD_ROOTS, "reason": _REASON_NON_CANONICAL_ORDER},
            )
        )

    return tuple(diagnostics)


# ── Manifest loading (YAML + shape; topology is a separate API) ─────────────


def load_production_source_manifest(path):
    # type: (str) -> Tuple[Optional[SourceRootSet], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Load and validate a production source-root manifest YAML document.

    Returns ``(SourceRootSet, ())`` on success, or ``(None, diagnostics)``
    on any failure:

      * PyYAML unavailable        -> ``MANIFEST_INVALID``
        (context reason ``yaml-module-unavailable``);
      * file missing / unreadable -> ``UNREADABLE``;
      * malformed YAML            -> ``MANIFEST_INVALID``
        (context reason ``malformed-yaml``);
      * non-mapping document or any shape violation
                                  -> ``MANIFEST_INVALID``.

    Topology is intentionally NOT verified here — callers that know the
    repository root call ``verify_production_source_topology()``
    themselves.
    """
    if not _HAS_YAML:
        return None, (
            (
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                {"reason": _REASON_YAML_UNAVAILABLE},
            ),
        )

    if not os.path.exists(path):
        return None, (
            (PRODUCTION_SOURCE_SCOPE_UNREADABLE, {"target": _TARGET_MANIFEST}),
        )

    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
    except yaml.YAMLError:
        return None, (
            (
                PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
                {"reason": _REASON_MALFORMED_YAML},
            ),
        )
    except (OSError, UnicodeDecodeError):
        return None, (
            (PRODUCTION_SOURCE_SCOPE_UNREADABLE, {"target": _TARGET_MANIFEST}),
        )

    if not isinstance(data, dict):
        return None, (
            (PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID, {"field": _FIELD_DOCUMENT}),
        )

    diagnostics = validate_production_source_manifest(data)
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


def _declared_root_traverses_symlink(repo_root, declared_path):
    # type: (str, str) -> bool
    """True when any component of the declared root's path below the
    repository root is a symlink.

    Completes the documented fail-closed contract: a symlinked ancestor
    INSIDE the repository makes the root's traversal non-deterministic and
    must be rejected exactly like an escape (the plain ``realpath``
    containment check alone only catches ancestors resolving outside).
    """
    current = os.path.abspath(repo_root)
    for part in declared_path.split("/"):
        current = os.path.join(current, part)
        if os.path.islink(current):
            return True
    return False


def verify_production_source_topology(repo_root, root_set):
    # type: (str, SourceRootSet) -> Tuple[Tuple[str, Dict[str, object]], ...]
    """Verify each declared root exists, is readable, and stays inside
    ``repo_root`` when resolved.

    Pure topology-vs-filesystem checks only — the manifest is assumed to
    have been validated already
    (``validate_production_source_manifest()``).  Per declared root
    (checked in manifest order, all roots reported):

      * directory missing / not a directory / not readable
        -> ``UNREADABLE``;
      * root itself is a symlink, any component of its declared path
        below the repository root is a symlink (including through a
        symlinked ancestor inside the repository), or its real path
        resolves outside ``repo_root``
        -> ``SYMLINK_OUTSIDE``.

    Diagnostics carry the declared repository-relative path as bounded
    ``target`` context — never runtime-discovered filesystem paths.
    Individual symlinked *files* are rejected later at resolution time by
    ``resolve_production_kotlin_file()`` and during enumeration by
    ``collect_production_source_files()``.
    """
    diagnostics = []
    for root in root_set.roots:
        root_abs = os.path.join(os.path.abspath(repo_root), *root.path.split("/"))
        if not os.path.isdir(root_abs) or not os.access(root_abs, os.R_OK | os.X_OK):
            diagnostics.append(
                (PRODUCTION_SOURCE_SCOPE_UNREADABLE, {"target": root.path})
            )
            continue
        if _declared_root_traverses_symlink(
            repo_root, root.path
        ) or not _realpath_contains(repo_root, root_abs):
            diagnostics.append(
                (PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE, {"target": root.path})
            )
    return tuple(diagnostics)


# ── Production Kotlin discovery (deterministic, fail closed) ────────────────


def collect_production_source_files(repo_root, root_set):
    # type: (object, SourceRootSet) -> Tuple[Tuple[ProductionSourceFile, ...], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Collect every declared production Kotlin source as
    ``ProductionSourceFile`` values (deterministic, fail closed).

    Walks each declared root in manifest order and sorts each root's files
    by canonical relative path, so the result is fully deterministic for a
    given manifest and tree.  No filtering beyond the ``.kt`` suffix is
    applied: roots are already validated and topology is verified
    separately via ``verify_production_source_topology()``.

    Fails closed: if any directory cannot be listed, any ``.kt`` entry is
    not a readable regular file, or any descended directory / ``.kt``
    entry is a symlink or resolves outside the repository, returns an
    empty tuple plus a single diagnostic naming the declared root (bounded
    ``target`` context, never the failing filesystem path).
    """
    collected = []
    repo_abs = os.path.abspath(repo_root)
    for root in root_set.roots:
        root_abs = os.path.join(repo_abs, *root.path.split("/"))
        per_root = []
        try:
            _collect_kotlin_sources_under(repo_abs, root_abs, root.path, per_root)
        except _SourceTreeError as error:
            return (), ((error.code, {"target": root.path}),)
        per_root.sort(key=lambda pair: pair[0])
        for rel_path, abs_path in per_root:
            collected.append(
                ProductionSourceFile(
                    repository_relative_path=rel_path,
                    absolute_path=abs_path,
                    root_path=root_abs,
                    module=root.module,
                    source_set=root.source_set,
                )
            )
    return tuple(collected), ()


def iter_production_kotlin_files(repo_root, root_set):
    # type: (object, SourceRootSet) -> Iterator[ProductionSourceFile]
    """Iterate declared production Kotlin sources deterministically.

    Root-order then canonical path-order traversal: each declared root is
    fully enumerated (and its files sorted by repository-relative POSIX
    path) before that root's first value is yielded, so a failure can only
    cut the traversal at a root boundary.  Fail closed: a raised
    ``ProductionSourceScopeError`` carries only the controlled diagnostic
    code and consumers must treat it as "scope not established" rather
    than consuming the partial prefix.
    """
    repo_abs = os.path.abspath(repo_root)
    for root in root_set.roots:
        root_abs = os.path.join(repo_abs, *root.path.split("/"))
        per_root = []
        try:
            _collect_kotlin_sources_under(repo_abs, root_abs, root.path, per_root)
        except _SourceTreeError as error:
            raise ProductionSourceScopeError(error.code) from None
        per_root.sort(key=lambda pair: pair[0])
        for rel_path, abs_path in per_root:
            yield ProductionSourceFile(
                repository_relative_path=rel_path,
                absolute_path=abs_path,
                root_path=root_abs,
                module=root.module,
                source_set=root.source_set,
            )


# ── Membership and safe resolution ───────────────────────────────────────────


def is_declared_production_path(root_set, rel_posix_path):
    # type: (SourceRootSet, object) -> bool
    """True iff ``rel_posix_path`` equals a declared root or lives below one.

    Membership is segment-aligned (never a sloppy string prefix) and the
    path must be canonical repository-relative POSIX: non-strings, empty
    values, backslashes, absolute forms, and empty/``.``/``..`` segments all
    return False (fail closed).  This is a pure declaration-membership
    check; filesystem resolution belongs to
    ``resolve_production_kotlin_file()``.
    """
    return _matching_declared_root(root_set, rel_posix_path) is not None


def resolve_production_kotlin_file(repo_root, root_set, rel_path):
    # type: (object, SourceRootSet, object) -> Tuple[Optional[ProductionSourceFile], Optional[str]]
    """Resolve a declared production Kotlin source file safely.

    Returns ``(ProductionSourceFile, None)`` on success, or
    ``(None, code)`` with exactly one controlled diagnostic code:

      * ``LAYOUT_UNSUPPORTED`` — not a canonical repository-relative POSIX
        ``.kt`` path (wrong type, empty, absolute form, backslashes,
        ``.``/``..`` traversal segments, wildcards, bare basenames, or a
        non-``.kt`` file);
      * ``UNDECLARED`` — canonical ``.kt`` path but not under any declared
        root;
      * ``UNREADABLE`` — target missing, not a regular file, or not
        readable;
      * ``SYMLINK_OUTSIDE`` — target itself is a symlink, or its real path
        resolves outside the repository.

    The returned ``absolute_path`` is normalized but not fully resolved;
    the real-path resolution is used only for containment validation.
    """
    if not isinstance(rel_path, str):
        return None, PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED
    if (
        not rel_path
        or "\\" in rel_path
        or rel_path.startswith("/")
        or rel_path.startswith("./")
        or _DRIVE_LETTER_RE.match(rel_path)
        or any(character in _WILDCARD_CHARACTERS for character in rel_path)
        or not rel_path.endswith(".kt")
    ):
        return None, PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED
    parts = rel_path.split("/")
    if len(parts) < 2 or any(part in ("", ".", "..") for part in parts):
        return None, PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED

    matched_root = _matching_declared_root(root_set, rel_path)
    if matched_root is None:
        return None, PRODUCTION_SOURCE_SCOPE_UNDECLARED

    repo_abs = os.path.abspath(repo_root)
    candidate = os.path.join(repo_abs, *parts)
    if not os.path.isfile(candidate) or not os.access(candidate, os.R_OK):
        return None, PRODUCTION_SOURCE_SCOPE_UNREADABLE
    if os.path.islink(candidate) or not _realpath_contains(repo_abs, candidate):
        return None, PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE
    return (
        ProductionSourceFile(
            repository_relative_path=rel_path,
            absolute_path=candidate,
            root_path=os.path.join(repo_abs, *matched_root.path.split("/")),
            module=matched_root.module,
            source_set=matched_root.source_set,
        ),
        None,
    )


# ── Scope-evidence hashing ───────────────────────────────────────────────────


def scope_evidence(repo_root, root_set, manifest_path):
    # type: (object, SourceRootSet, str) -> Tuple[Optional[ProductionSourceScopeEvidence], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Produce registry-grade evidence for one production source scope.

    Returns ``(ProductionSourceScopeEvidence, ())`` with the declared
    roots (manifest order), the enumerated source-file count, the ordered
    file-list hash (SHA-256 over each repository-relative POSIX path,
    UTF-8, each followed by ``\\n`` — membership- and order-sensitive,
    content-independent), and the manifest hash (SHA-256 over the raw
    manifest file bytes).  Otherwise returns ``(None, diagnostics)``:

      * enumeration fails (unreadable tree / symlink escape)
        -> that enumeration's single controlled diagnostic;
      * manifest missing / unreadable -> ``UNREADABLE`` with bounded
        ``target`` ``manifest``.

    The caller is expected to have validated the manifest and verified the
    topology first (``load_production_source_manifest()`` +
    ``verify_production_source_topology()``); this helper re-enumerates
    deterministically and never calls ``sys.exit``.
    """
    files, diagnostics = collect_production_source_files(repo_root, root_set)
    if diagnostics:
        return None, diagnostics

    if not os.path.exists(manifest_path):
        return None, (
            (PRODUCTION_SOURCE_SCOPE_UNREADABLE, {"target": _TARGET_MANIFEST}),
        )
    try:
        with open(manifest_path, "rb") as handle:
            manifest_bytes = handle.read()
    except OSError:
        return None, (
            (PRODUCTION_SOURCE_SCOPE_UNREADABLE, {"target": _TARGET_MANIFEST}),
        )

    ordered_paths = [source_file.repository_relative_path for source_file in files]
    list_digest = hashlib.sha256()
    for rel_path in ordered_paths:
        list_digest.update(rel_path.encode("utf-8"))
        list_digest.update(b"\n")

    evidence = ProductionSourceScopeEvidence(
        roots=root_set.paths,
        source_file_count=len(ordered_paths),
        ordered_file_list_hash=list_digest.hexdigest(),
        manifest_hash=hashlib.sha256(manifest_bytes).hexdigest(),
    )
    return evidence, ()


# ── Scope resolution (PR-GR-10B Slice 1; no repository-level fallback) ──────

#: Repository-relative POSIX location of the checked-in source-root manifest,
#: resolved against the repository root by
#: ``resolve_production_source_scope``.
PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH = "config/guards/production_source_roots.yml"

# Controlled reason constants (diagnostic context ``reason`` values only).
_REASON_MANIFEST_ABSENT = "manifest-absent"
_REASON_NO_CONVENTIONAL_ROOT = "no-conventional-root"
_REASON_REPO_ROOT_NOT_A_PATH = "repo-root-not-a-path"
_REASON_EXPLICIT_TYPE = "explicit-not-a-source-root-set"


def resolve_production_source_scope(repo_root, explicit=None):
    # type: (object, object) -> Tuple[Optional[SourceRootSet], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Resolve the effective production source-root set for ``repo_root``.

    Returns ``(SourceRootSet, ())`` on success or ``(None, diagnostics)``
    on failure, with precedence (fail closed):

      (a) ``explicit`` ``SourceRootSet`` — used exactly as-is; the caller
          vouches for it and topology is re-checked at walk time;
      (b) the manifest file ``<repo_root>/config/guards/
          production_source_roots.yml`` when that FILE exists — loaded,
          shape-validated, and topology-verified; ANY diagnostic fails
          closed.  A present-but-malformed manifest NEVER falls back.

    There is NO conventional-root fallback here.  When the manifest file
    is absent the result is ``(None, ((UNDECLARED,
    {"reason": "manifest-absent"}),))`` — repository-level guards, suites,
    ratchets, and Gradle tasks must fail closed (PR-GR-10B §3).  The
    implicit conventional-root fallback survives ONLY inside the
    explicitly named test-fixture seam
    ``resolve_source_root_set_for_test_fixtures()``.

    A non-``SourceRootSet`` explicit value or a non-path-like repository
    root fails closed with ``LAYOUT_UNSUPPORTED`` and a controlled reason.

    Never raises for resolution outcomes; never calls ``sys.exit``.
    """
    if explicit is not None:
        if isinstance(explicit, SourceRootSet):
            return explicit, ()
        return None, (
            (
                PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED,
                {"reason": _REASON_EXPLICIT_TYPE},
            ),
        )
    try:
        repo_abs = os.path.abspath(os.fspath(repo_root))
    except (TypeError, ValueError):
        return None, (
            (
                PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED,
                {"reason": _REASON_REPO_ROOT_NOT_A_PATH},
            ),
        )
    manifest_path = os.path.join(
        repo_abs, *PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH.split("/")
    )
    if not os.path.isfile(manifest_path):
        return None, (
            (
                PRODUCTION_SOURCE_SCOPE_UNDECLARED,
                {"reason": _REASON_MANIFEST_ABSENT},
            ),
        )
    root_set, load_diagnostics = load_production_source_manifest(manifest_path)
    if root_set is None or load_diagnostics:
        return None, tuple(load_diagnostics)
    topology_diagnostics = verify_production_source_topology(repo_abs, root_set)
    if topology_diagnostics:
        return None, topology_diagnostics
    return root_set, ()


# ── TEST-FIXTURE-ONLY legacy seam (PR-GR-10B §3) ─────────────────────────────
# Everything below supports exclusively synthetic test fixtures and
# manifest-less embedders.  It must never be reached by a repository-level
# guard, suite, ratchet, or Gradle task; production code calls
# ``resolve_production_source_scope()``.

#: Declared module label for implicitly resolved (non-manifest) roots.  The
#: label is a controlled constant; implicit roots carry no real Gradle module
#: identity because none was declared.
_IMPLICIT_MODULE = ":implicit"

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

    TEST-FIXTURE-ONLY support helper (see
    ``resolve_source_root_set_for_test_fixtures``).  Branch order:

      * ``repo_abs`` ends with ``src/main/java`` or ``src/main/kotlin``
        -> that directory itself;
      * legacy compatibility: ``repo_abs`` ends with ``src/main`` or
        ``src`` -> the ``src/main/java`` directory below it, when that
        directory exists;
      * otherwise ``<repo>/app/src/main/java``, then
        ``<repo>/src/main/java``, when the directory exists.
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


def resolve_source_root_set_for_test_fixtures(repo_root, explicit=None):
    # type: (object, object) -> Tuple[Optional[SourceRootSet], Tuple[Tuple[str, Dict[str, object]], ...]]
    """TEST-FIXTURE-ONLY legacy resolution seam — never call from a
    repository-level guard, suite, ratchet, or Gradle task.

    This is the ONLY function in the production source-scope authority
    that still contains the implicit conventional-root fallback
    (``app/src/main/java`` / ``src/main/java`` and the legacy
    ``src/main`` / ``src`` intermediate forms), because synthetic test
    fixtures and manifest-less embedders have no checked-in manifest to
    declare their roots (PR-GR-10B §3).  Precedence:

      (a) ``explicit`` ``SourceRootSet`` — used exactly as-is; the caller
          vouches for it and topology is re-checked at walk time;
      (b) the manifest file ``<repo_root>/config/guards/
          production_source_roots.yml`` when that FILE exists — loaded,
          shape-validated, and topology-verified; ANY diagnostic fails
          closed.  A present-but-malformed manifest NEVER falls back to
          the implicit conventions;
      (c) manifest file absent -> the implicit conventional single root
          (see ``_implicit_conventional_dir``); with no conventional root
          at all the result is ``(None, ((UNDECLARED,
          {"reason": "no-conventional-root"}),))``.

    When ``repo_root`` itself is the conventional source directory (or a
    legacy ``src/main``/``src`` directory above one), the single returned
    root carries that directory as an ABSOLUTE native-separator path so
    callers can both walk it and anchor emitted repository-relative POSIX
    paths at the enclosing project.

    Never raises for resolution outcomes; never calls ``sys.exit``.
    """
    root_set, diagnostics = resolve_production_source_scope(repo_root, explicit)
    if root_set is not None:
        return root_set, diagnostics
    if diagnostics != (
        (PRODUCTION_SOURCE_SCOPE_UNDECLARED, {"reason": _REASON_MANIFEST_ABSENT}),
    ):
        return None, diagnostics
    # Manifest absent: legacy conventional fallback (synthetic fixtures only).
    repo_abs = os.path.abspath(os.fspath(repo_root))
    try:
        conventional = _implicit_conventional_dir(repo_abs)
    except OSError:
        conventional = None
    if conventional is None:
        return None, (
            (
                PRODUCTION_SOURCE_SCOPE_UNDECLARED,
                {"reason": _REASON_NO_CONVENTIONAL_ROOT},
            ),
        )
    implicit_root = SourceRoot(
        module=_IMPLICIT_MODULE, source_set="main", path=conventional
    )
    return SourceRootSet(roots=(implicit_root,)), ()


__all__ = [
    # Controlled diagnostic vocabulary (one live implementation).
    "PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID",
    "PRODUCTION_SOURCE_SCOPE_UNDECLARED",
    "PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED",
    "PRODUCTION_SOURCE_SCOPE_UNREADABLE",
    "PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE",
    "PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES",
    "PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION",
    "PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH",
    # Immutable models and controlled iterator error.
    "SourceRoot",
    "SourceRootSet",
    "ProductionSourceFile",
    "ProductionSourceScopeEvidence",
    "ProductionSourceScopeError",
    # Manifest layer.
    "validate_production_source_manifest",
    "load_production_source_manifest",
    "verify_production_source_topology",
    # Scope resolution (no repository-level fallback).
    "resolve_production_source_scope",
    "resolve_source_root_set_for_test_fixtures",
    # Deterministic enumeration.
    "collect_production_source_files",
    "iter_production_kotlin_files",
    # Membership and safe resolution.
    "is_declared_production_path",
    "resolve_production_kotlin_file",
    # Scope evidence.
    "scope_evidence",
]
