#!/usr/bin/env python3
"""
VERIFY_PRODUCTION_SOURCE_ROOTS -- Meta-guard verifying that the checked-in
production source-root manifest (``config/guards/production_source_roots.yml``)
matches the repository's actual Gradle source-root topology (PR-GR-03 Slice B).

The guard is declarative: the manifest declares the approved production source
roots (module / source-set / path triples, validated by
``scripts/db_guard/source_roots.py``); this meta-guard cross-checks that
declaration against what the Gradle layout actually exposes and fails closed
on any mismatch or on any layout it cannot interpret.

Diagnostics are ``(code, context)`` tuples whose ``code`` is one of the five
closed ``DB_SOURCE_ROOT_*`` codes registered in
``scripts/ci/finding_rule_catalog.py``:

  * ``DB_SOURCE_ROOT_MANIFEST_INVALID`` -- manifest cannot be loaded/validated;
  * ``DB_SOURCE_ROOT_UNDECLARED``       -- declared-vs-observed mismatch;
  * ``DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED`` -- settings layout not interpretable;
  * ``DB_SOURCE_ROOT_UNREADABLE``       -- root/settings/manifest unreadable;
  * ``DB_SOURCE_ROOT_SYMLINK_OUTSIDE``  -- root resolves outside the repo.

Every diagnostic ``context`` carries only bounded structured fields:
controlled reason constants, the fixed target name ``settings.gradle.kts``,
or repository-relative POSIX paths that are either declared in the manifest
or derived from literal module names parsed out of ``settings.gradle.kts``.
Raw exception text, stack traces, and runtime-discovered filesystem paths are
never included.

Verification precedence (fail closed; each step short-circuits the next):

  1. ``settings.gradle.kts`` missing            -> LAYOUT_UNSUPPORTED
     (reason ``settings-file-missing``);
  2. settings file unreadable                   -> LAYOUT_UNSUPPORTED
     (reason ``settings-file-unreadable``);
  3. unsupported settings layout                -> LAYOUT_UNSUPPORTED with the
     parser's controlled reason (``dynamic-include-expression`` or
     ``custom-source-set-or-projectdir``); partial conclusions are skipped;
  4. manifest load/validation failure           -> its own diagnostics
     (MANIFEST_INVALID / UNREADABLE);
  5. topology comparison                        -> UNDECLARED /
     UNREADABLE / SYMLINK_OUTSIDE diagnostics as described below.

Topology comparison combines three sources, in this deterministic order:

  * ``verify_declared_root_topology()`` per declared root (manifest order):
    missing/unreadable -> UNREADABLE; symlink escaping the repo ->
    SYMLINK_OUTSIDE;
  * conventional-root observation issues (discovery order): UNREADABLE or
    SYMLINK_OUTSIDE for a candidate root that exists but cannot be safely
    observed;
  * observed Kotlin-containing conventional roots absent from the manifest ->
    UNDECLARED (reason ``undeclared-observed-root``, discovery order);
  * declared roots not observed in the supported topology -> UNDECLARED
    (reason ``declared-root-not-observed``, manifest order).

Exact duplicate ``(code, context)`` pairs are collapsed (first occurrence
kept); everything else is reported.

Scope limitations (documented, fail closed):

  * Only ``settings.gradle.kts`` is inspected. Module-level build files
    (``build.gradle.kts``) are OUT of scope: a module that customizes its own
    ``sourceSets`` or ``projectDir`` there is not detected here.
  * Only literal ``include(...)`` calls are recognized (balanced-paren,
    quote-aware scanning; multi-line literal calls are fine). Any ``include(``
    call whose arguments are not plain quoted literals (variables,
    concatenation, interpolation, loops) or that never closes makes the
    layout unsupported (``dynamic-include-expression``).
  * Line comments (``//``) are stripped before parsing; block comments
    (``/* ... */``) are not handled.
  * Lines containing ``sourceSets``, ``projectDir``, or
    ``rootProject.projectDir`` mark the layout unsupported
    (``custom-source-set-or-projectdir``).

Exit codes (owned exclusively by ``main()``; no helper ever calls
``sys.exit``):

  * 0 -- zero diagnostics;
  * 2 -- at least one diagnostic (infrastructure failure; never baseline-able).

Usage::

    python scripts/ci/verify_production_source_roots.py [--root ROOT]
                                                        [--manifest MANIFEST]
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from typing import Dict, List, Optional, Tuple

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PROJECT_ROOT = os.path.dirname(os.path.dirname(_SCRIPT_DIR))
for _path in (_SCRIPT_DIR, _PROJECT_ROOT):
    if _path not in sys.path:
        sys.path.insert(0, _path)

from scripts.db_guard.source_roots import (  # noqa: E402
    DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
    DB_SOURCE_ROOT_SYMLINK_OUTSIDE,
    DB_SOURCE_ROOT_UNDECLARED,
    DB_SOURCE_ROOT_UNREADABLE,
    load_source_root_manifest,
    verify_declared_root_topology,
)

# ── Controlled constants ─────────────────────────────────────────────────────

# Unsupported-layout reasons (context ``reason`` values; controlled only).
_REASON_DYNAMIC_INCLUDE = "dynamic-include-expression"
_REASON_CUSTOM_SOURCE_SET = "custom-source-set-or-projectdir"
_REASON_SETTINGS_MISSING = "settings-file-missing"
_REASON_SETTINGS_UNREADABLE = "settings-file-unreadable"

# Undeclared-comparison reasons (context ``reason`` values; controlled only).
_REASON_UNDECLARED_OBSERVED = "undeclared-observed-root"
_REASON_DECLARED_NOT_OBSERVED = "declared-root-not-observed"

# Conventional-root observation issue reasons (internal; mapped to codes).
_ISSUE_UNREADABLE = "conventional-root-unreadable"
_ISSUE_SYMLINK_OUTSIDE = "conventional-root-symlink-outside"

_TARGET_SETTINGS = "settings.gradle.kts"

_DEFAULT_MANIFEST = "config/guards/production_source_roots.yml"

# Conventional source directories checked under each declared module dir.
_CONVENTIONAL_TAILS = ("src/main/java", "src/main/kotlin")

# Non-production tree segments never counted during observation (mirrors the
# forbidden-segment policy of scripts/db_guard/source_roots.py).
_EXCLUDED_TREE_SEGMENTS = frozenset(
    {"test", "androidTest", "debug", "release", "generated", "build"}
)

# Literal customization markers inside settings.gradle.kts that make the
# source-root layout unverifiable (rootProject.projectDir contains
# projectDir, so it is covered too).
_CUSTOMIZATION_MARKERS = ("sourcesets", "projectdir")

_INCLUDE_CALL_RE = re.compile(r"(?<![\w.])include\s*\(")
_QUOTED_LITERAL_RE = re.compile(r"^\"([^\"\\]*)\"$|^'([^'\\]*)'$")

__all__ = [
    "parse_declared_modules",
    "observed_conventional_roots",
    "verify_topology",
    "main",
]


class _ObservationUnreadable(Exception):
    """Internal signal: a conventional root could not be listed."""


# ── Settings parsing (pure) ──────────────────────────────────────────────────


def _strip_line_comments(text):
    # type: (str) -> str
    """Strip ``//`` line comments (naive; block comments unsupported)."""
    return "\n".join(line.split("//", 1)[0] for line in text.splitlines())


def _split_top_level_args(content):
    # type: (str) -> List[str]
    """Split an argument list on commas outside quoted spans."""
    parts = []
    buf = []
    quote = None
    for character in content:
        if quote is not None:
            buf.append(character)
            if character == quote:
                quote = None
        elif character in "\"'":
            quote = character
            buf.append(character)
        elif character == ",":
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(character)
    parts.append("".join(buf))
    return parts


def _extract_call_args(text, open_index):
    # type: (str, int) -> Optional[str]
    """Return the raw argument text between the ``(`` at ``open_index`` and
    its matching ``)`` (quote-aware), or ``None`` when the call never closes."""
    depth = 0
    quote = None
    index = open_index + 1
    length = len(text)
    while index < length:
        character = text[index]
        if quote is not None:
            if character == "\\":
                index += 2
                continue
            if character == quote:
                quote = None
        elif character in "\"'":
            quote = character
        elif character == "(":
            depth += 1
        elif character == ")":
            if depth == 0:
                return text[open_index + 1 : index]
            depth -= 1
        index += 1
    return None


def _literal_module(argument):
    # type: (str) -> Optional[str]
    """Return the module string when ``argument`` is a plain quoted literal,
    or ``None`` when it is dynamic (variable, concatenation, interpolation,
    escape sequence, or anything else non-literal)."""
    token = argument.strip()
    match = _QUOTED_LITERAL_RE.match(token)
    if match is None:
        return None
    inner = match.group(1) if match.group(1) is not None else match.group(2)
    if "$" in inner:
        return None  # Kotlin string template -> dynamic
    inner = inner.strip()
    return inner or None


def parse_declared_modules(settings_text):
    # type: (object) -> Tuple[Tuple[str, ...], Optional[str]]
    """Parse literal ``include(...)`` calls from ``settings.gradle.kts`` text.

    Returns ``(modules, None)`` where ``modules`` is a de-duplicated tuple of
    module paths (first-occurrence order) such as ``":app"`` and
    ``":core:data"``, or ``((), reason)`` when the layout is unsupported:

      * ``dynamic-include-expression`` -- some ``include(`` call has
        non-literal arguments (variables, concatenation, interpolation,
        loops) or never closes;
      * ``custom-source-set-or-projectdir`` -- a line mentions
        ``sourceSets``, ``projectDir``, or ``rootProject.projectDir``.

    Precedence: ``dynamic-include-expression`` wins over
    ``custom-source-set-or-projectdir``.  Scope: settings.gradle.kts only;
    module build files are out of scope (see module docstring).
    """
    if not isinstance(settings_text, str):
        return (), _REASON_DYNAMIC_INCLUDE

    stripped = _strip_line_comments(settings_text)

    modules = []
    seen = set()
    for match in _INCLUDE_CALL_RE.finditer(stripped):
        open_index = match.end() - 1  # index of the '(' ending the match
        content = _extract_call_args(stripped, open_index)
        if content is None:
            return (), _REASON_DYNAMIC_INCLUDE  # unterminated call
        if not content.strip():
            return (), _REASON_DYNAMIC_INCLUDE  # empty include() -> unparseable
        for argument in _split_top_level_args(content):
            module = _literal_module(argument)
            if module is None:
                return (), _REASON_DYNAMIC_INCLUDE
            if module not in seen:
                seen.add(module)
                modules.append(module)

    for line in stripped.splitlines():
        lowered = line.lower()
        if any(marker in lowered for marker in _CUSTOMIZATION_MARKERS):
            return (), _REASON_CUSTOM_SOURCE_SET

    return tuple(modules), None


# ── Conventional-root observation (filesystem; fail closed) ──────────────────


def _realpath_contains(parent_abs, child_abs):
    # type: (str, str) -> bool
    """True when ``child_abs``'s real path equals or lives below
    ``parent_abs``'s real path (case-normalized for Windows)."""
    parent = os.path.normcase(os.path.realpath(parent_abs))
    child = os.path.normcase(os.path.realpath(child_abs))
    if child == parent:
        return True
    return child.startswith(parent + os.sep)


def _module_directory(module):
    # type: (str) -> Optional[str]
    """Map a Gradle module path like ``:core:data`` to ``core/data``."""
    trimmed = module.strip().lstrip(":")
    if not trimmed:
        return None
    rel_dir = trimmed.replace(".", "/").replace(":", "/")
    if any(part in ("", ".", "..") for part in rel_dir.split("/")):
        return None
    return rel_dir


def _contains_kotlin(directory_abs):
    # type: (str) -> bool
    """True when a readable ``.kt`` regular file exists below the directory,
    pruning excluded non-production segments and never following symlinks.

    Raises ``_ObservationUnreadable`` when any directory cannot be listed
    (fail closed: emptiness cannot be proven)."""
    try:
        names = os.listdir(directory_abs)
    except OSError:
        raise _ObservationUnreadable()
    for name in sorted(names):
        if name in _EXCLUDED_TREE_SEGMENTS:
            continue
        entry_abs = os.path.join(directory_abs, name)
        if os.path.islink(entry_abs):
            continue  # never follow symlinks while observing
        if os.path.isdir(entry_abs):
            if _contains_kotlin(entry_abs):
                return True
        elif name.endswith(".kt") and os.path.isfile(entry_abs):
            return True
    return False


def observed_conventional_roots(repo_root, modules):
    # type: (str, object) -> Tuple[Tuple[str, ...], Tuple[Tuple[str, str], ...]]
    """Observe conventional production roots for each declared module.

    For each module ``":a:b"`` checks whether ``a/b/src/main/java`` and
    ``a/b/src/main/kotlin`` exist below ``repo_root`` and recursively contain
    at least one readable ``.kt`` file, excluding any path segment named
    ``test``/``androidTest``/``debug``/``release``/``generated``/``build``
    and never following symlinks.

    Returns ``(observed, issues)`` where ``observed`` is a tuple of
    repository-relative POSIX root directories in discovery order and
    ``issues`` is a tuple of ``(controlled_reason, rel_path)`` pairs for
    candidates that exist but could not be observed safely:

      * ``conventional-root-symlink-outside`` -- candidate is a symlink or
        resolves outside ``repo_root``;
      * ``conventional-root-unreadable`` -- candidate could not be listed.
    """
    repo_abs = os.path.abspath(repo_root)
    observed = []
    issues = []
    if not isinstance(modules, tuple):
        modules = tuple(modules)
    for module in modules:
        base = _module_directory(module)
        if base is None:
            continue
        for tail in _CONVENTIONAL_TAILS:
            rel = base + "/" + tail
            candidate_abs = os.path.join(repo_abs, *rel.split("/"))
            if os.path.islink(candidate_abs) or not _realpath_contains(
                repo_abs, candidate_abs
            ):
                issues.append((_ISSUE_SYMLINK_OUTSIDE, rel))
                continue
            if not os.path.isdir(candidate_abs):
                continue  # simply absent: nothing to observe
            try:
                has_kotlin = _contains_kotlin(candidate_abs)
            except _ObservationUnreadable:
                issues.append((_ISSUE_UNREADABLE, rel))
                continue
            if has_kotlin:
                observed.append(rel)
    return tuple(observed), tuple(issues)


# ── Topology verification ────────────────────────────────────────────────────


def verify_topology(repo_root, manifest_path):
    # type: (str, str) -> Tuple[Tuple[str, Dict[str, object]], ...]
    """Verify the declared manifest against the repository's real topology.

    Returns a deterministic tuple of ``(code, context)`` diagnostics using
    only the five closed ``DB_SOURCE_ROOT_*`` codes; see the module docstring
    for precedence, ordering, and bounded-context guarantees.  Never raises
    for verification outcomes and never calls ``sys.exit``.
    """
    settings_path = os.path.join(repo_root, _TARGET_SETTINGS)
    if not os.path.isfile(settings_path):
        return ((DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED, {"reason": _REASON_SETTINGS_MISSING}),)
    try:
        with open(settings_path, "r", encoding="utf-8") as handle:
            settings_text = handle.read()
    except (OSError, UnicodeDecodeError):
        return (
            (DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED, {"reason": _REASON_SETTINGS_UNREADABLE}),
        )

    modules, layout_reason = parse_declared_modules(settings_text)
    if layout_reason is not None:
        # Fail closed: skip all partial conclusions.
        return ((DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED, {"reason": layout_reason}),)

    root_set, manifest_diagnostics = load_source_root_manifest(manifest_path)
    if manifest_diagnostics:
        return tuple(manifest_diagnostics)
    assert root_set is not None

    diagnostics = []  # type: List[Tuple[str, Dict[str, object]]]

    # 1. Declared-root filesystem checks (manifest order).
    diagnostics.extend(verify_declared_root_topology(repo_root, root_set))

    # 2. Observation issues (discovery order; deduplicated against (1)).
    observed, issues = observed_conventional_roots(repo_root, modules)
    for issue_reason, target in issues:
        code = (
            DB_SOURCE_ROOT_UNREADABLE
            if issue_reason == _ISSUE_UNREADABLE
            else DB_SOURCE_ROOT_SYMLINK_OUTSIDE
        )
        diagnostics.append((code, {"target": target}))

    # 3. Observed-but-undeclared (discovery order).
    declared_paths = root_set.paths
    declared_set = set(declared_paths)
    for rel in observed:
        if rel not in declared_set:
            diagnostics.append(
                (
                    DB_SOURCE_ROOT_UNDECLARED,
                    {"target": rel, "reason": _REASON_UNDECLARED_OBSERVED},
                )
            )

    # 4. Declared-but-not-observed (manifest order).
    observed_set = set(observed)
    for path in declared_paths:
        if path not in observed_set:
            diagnostics.append(
                (
                    DB_SOURCE_ROOT_UNDECLARED,
                    {"target": path, "reason": _REASON_DECLARED_NOT_OBSERVED},
                )
            )

    return _dedupe(diagnostics)


def _dedupe(diagnostics):
    # type: (List[Tuple[str, Dict[str, object]]]) -> Tuple[Tuple[str, Dict[str, object]], ...]
    """Collapse exact duplicate ``(code, context)`` pairs, keeping the first."""
    seen = set()
    unique = []
    for code, context in diagnostics:
        key = (code, tuple(sorted(context.items())))
        if key in seen:
            continue
        seen.add(key)
        unique.append((code, context))
    return tuple(unique)


# ── CLI adapter ──────────────────────────────────────────────────────────────


def _format_diagnostic(code, context):
    # type: (str, Dict[str, object]) -> str
    """Render one bounded deterministic diagnostic line (code + sorted
    ``key=value`` context fields; values are controlled strings/ints)."""
    fields = " ".join(
        "{0}={1}".format(key, context[key]) for key in sorted(context)
    )
    return "{0} {1}".format(code, fields) if fields else code


def main(argv=None):
    # type: (Optional[List[str]]) -> None
    """CLI adapter: parse args, run verification, print bounded diagnostic
    lines, exit 0 when zero diagnostics else exit 2.  The only place in this
    module allowed to call ``sys.exit``."""
    parser = argparse.ArgumentParser(
        description=(
            "Verify that the production source-root manifest matches the "
            "repository's Gradle source-root topology."
        )
    )
    parser.add_argument(
        "--root",
        type=str,
        default=".",
        help="Repository root directory (default: current directory).",
    )
    parser.add_argument(
        "--manifest",
        type=str,
        default=_DEFAULT_MANIFEST,
        help=(
            "Source-root manifest path (default: "
            f"{_DEFAULT_MANIFEST}, resolved relative to --root)."
        ),
    )
    args = parser.parse_args(argv)

    repo_root = os.path.abspath(args.root)
    manifest_path = args.manifest
    if not os.path.isabs(manifest_path):
        manifest_path = os.path.join(repo_root, manifest_path)

    diagnostics = verify_topology(repo_root, manifest_path)
    for code, context in diagnostics:
        print(_format_diagnostic(code, context))
    sys.exit(0 if not diagnostics else 2)


if __name__ == "__main__":
    main()
