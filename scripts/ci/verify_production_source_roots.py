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
  * ``DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED`` -- settings/build-file layout not
    interpretable;
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
  4. root/module build-file layout checks (PR-GR-10B Slice 3) -> LAYOUT_UNSUPPORTED
     with a controlled reason (see below); partial conclusions are skipped;
  5. manifest load/validation failure           -> its own diagnostics
     (MANIFEST_INVALID / UNREADABLE);
  6. topology comparison                        -> UNDECLARED /
     UNREADABLE / SYMLINK_OUTSIDE diagnostics as described below.

Build-file layout checks (PR-GR-10B Slice 3; fail closed):

  * The root ``build.gradle.kts``, if present, must be readable and must not
    touch source-set layout at all (``sourceSets``, ``srcDir``/``srcDirs``,
    ``setRoot``, or a ``projectDir =`` / ``.set(`` override): root-level
    source-set mutation is not modeled (``custom-source-set-or-projectdir``
    / ``root-build-file-unreadable``).
  * Every declared module must have a readable ``build.gradle.kts``. A
    missing build file (including a module directory that cannot be mapped)
    fails closed (``module-build-file-missing``), as does an unreadable one
    (``module-build-file-unreadable``).
  * A module ``build.gradle.kts`` is modeled conservatively. A
    ``sourceSets { }`` block is supported ONLY when every source-dir
    addition it performs for the ``main`` source set is a plain quoted,
    repository-safe, module-relative path via ``java.srcDirs("...")`` or
    ``kotlin.srcDirs("...")``. Non-main source-set groups (debug, test,
    androidTest, release, ...) are ignored entirely: they can never define
    production roots. Everything else fails closed:

      - dynamic srcDirs arguments (variables, concatenation, string
        templates, closures, empty or escaped arguments)
        -> ``dynamic-source-dir-expression``;
      - ``projectDir =``/``.set(`` overrides, ``setRoot``, assignment or
        ``setSrcDirs`` forms, non-literal accessor groups, unattributed
        brace groups/closures, ``srcDir`` usage outside a modeled
        ``sourceSets`` block, unclosed blocks or quotes
        -> ``custom-source-set-or-projectdir``.

Topology comparison combines three sources, in this deterministic order:

  * ``verify_declared_root_topology()`` per declared root (manifest order):
    missing/unreadable -> UNREADABLE; symlink escaping the repo ->
    SYMLINK_OUTSIDE;
  * conventional-root observation issues (discovery order): UNREADABLE or
    SYMLINK_OUTSIDE for a candidate root that exists but cannot be safely
    observed;
  * observed Kotlin-containing candidate roots absent from the manifest ->
    UNDECLARED (reason ``undeclared-observed-root``, discovery order);
  * declared roots not observed in the supported topology -> UNDECLARED
    (reason ``declared-root-not-observed``, manifest order).

Candidate roots per declared module, in deterministic order:
``src/main/java``, ``src/main/kotlin``, then the module's literal ``main``
srcDir paths in source order. Test/debug/release/generated segments are
pruned during Kotlin-content observation and are never production roots.

Exact duplicate ``(code, context)`` pairs are collapsed (first occurrence
kept); everything else is reported.

Scope limitations (documented, fail closed):

  * Line comments (``//``) are stripped before parsing with quote awareness;
    block comments (``/* ... */``) are not handled: a build file using them
    fails closed rather than being mis-modeled.
  * Only literal ``include(...)`` calls are recognized (balanced-paren,
    quote-aware scanning; multi-line literal calls are fine). Any ``include(``
    call whose arguments are not plain quoted literals (variables,
    concatenation, interpolation, loops) or that never closes makes the
    layout unsupported (``dynamic-include-expression``).
  * Lines containing ``sourceSets``, ``projectDir``, or
    ``rootProject.projectDir`` mark the settings layout unsupported
    (``custom-source-set-or-projectdir``).
  * Only ``.kts`` build files are inspected; ``build.gradle`` (Groovy) files
    are out of scope.
  * Arbitrary Gradle/Kotlin DSL is never evaluated: anything outside the
    modeled shapes above fails closed instead of guessing.

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

# Root/module build-file reasons (PR-GR-10B Slice 3; controlled only).
_REASON_MODULE_BUILD_MISSING = "module-build-file-missing"
_REASON_MODULE_BUILD_UNREADABLE = "module-build-file-unreadable"
_REASON_ROOT_BUILD_UNREADABLE = "root-build-file-unreadable"
_REASON_DYNAMIC_SOURCE_DIR = "dynamic-source-dir-expression"

# Undeclared-comparison reasons (context ``reason`` values; controlled only).
_REASON_UNDECLARED_OBSERVED = "undeclared-observed-root"
_REASON_DECLARED_NOT_OBSERVED = "declared-root-not-observed"

# Conventional-root observation issue reasons (internal; mapped to codes).
_ISSUE_UNREADABLE = "conventional-root-unreadable"
_ISSUE_SYMLINK_OUTSIDE = "conventional-root-symlink-outside"

_TARGET_SETTINGS = "settings.gradle.kts"
_TARGET_ROOT_BUILD = "build.gradle.kts"
_TARGET_MODULE_BUILD = "build.gradle.kts"

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

# ── Module build-file parsing (PR-GR-10B Slice 3; conservative) ──────────────

# projectDir override forms (read uses like "$projectDir/x" do not match):
#   projectDir = file("...")   rootProject.projectDir = ...
#   projectDir.set(file("..."))
_PROJECTDIR_OVERRIDE_RE = re.compile(r"projectdir\s*(?:=|\.set\s*\()")

# ``sourceSets`` container token (case-insensitive; word-bounded so
# ``android.sourceSets`` matches but ``mySourceSets`` does not).
_SOURCESETS_TOKEN_RE = re.compile(r"(?<![\w])sourceSets(?![\w])", re.IGNORECASE)

# Literal-named source-set accessors: getByName("main"), named("main"),
# findByName("main"), maybeCreate("main"), create("extra").
_NAMED_ACCESSOR_RE = re.compile(
    r"(?:getByName|named|findByName|maybeCreate|create)"
    r"\s*\(\s*(?:\"([^\"\\]*)\"|'([^'\\]*)')\s*\)"
)

# Bare quoted group name (Groovy-style ``"main" { ... }``).
_BARE_QUOTED_RE = re.compile(r"\"([^\"\\]+)\"|'([^'\\]+)'")

# The ONLY supported source-dir form inside a main source-set group:
# java.srcDirs("...") / kotlin.srcDirs("...") with plain quoted literals.
_SUPPORTED_SRC_DIRS_RE = re.compile(r"(?:java|kotlin)\s*\.\s*srcDirs\s*\(")

_IDENT_RE = re.compile(r"[A-Za-z_]\w*")

__all__ = [
    "parse_declared_modules",
    "parse_module_build_sources",
    "observed_conventional_roots",
    "verify_topology",
    "main",
]


class _ObservationUnreadable(Exception):
    """Internal signal: a conventional root could not be listed."""


# ── Settings parsing (pure) ──────────────────────────────────────────────────


def _strip_line_comments(text):
    # type: (str) -> str
    """Strip ``//`` line comments with quote awareness (naive for block
    comments, which remain unsupported and fail closed via marker scans).

    Quote awareness matters for module build files: string literals such as
    ``startsWith("//")`` or ``"https://..."`` must survive stripping intact
    or brace/quote balance downstream would be corrupted (which would fail
    closed, but avoidably and non-deterministically with respect to layout).
    """
    if not isinstance(text, str):
        return text
    stripped_lines = []
    for line in text.splitlines():
        quote = None
        index = 0
        length = len(line)
        cut = length
        while index < length:
            character = line[index]
            if quote is not None:
                if character == "\\":
                    index += 2
                    continue
                if character == quote:
                    quote = None
            elif character in "\"'":
                quote = character
            elif (
                character == "/"
                and index + 1 < length
                and line[index + 1] == "/"
            ):
                cut = index
                break
            index += 1
        stripped_lines.append(line[:cut])
    return "\n".join(stripped_lines)


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
    module build files are parsed separately by
    ``parse_module_build_sources`` (see module docstring).
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


# ── Module build-file parsing (pure; PR-GR-10B Slice 3) ──────────────────────


def _extract_braced_block(text, open_index):
    # type: (str, int) -> Optional[Tuple[str, int]]
    """Return ``(body, close_index)`` for the balanced ``{...}`` block whose
    opening brace is at ``open_index`` (quote-aware), or ``None`` when the
    block never closes or closes prematurely."""
    depth = 0
    quote = None
    index = open_index
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
        elif character == "{":
            depth += 1
        elif character == "}":
            if depth == 1:
                return text[open_index + 1 : index], index
            if depth == 0:
                return None
            depth -= 1
        index += 1
    return None


def _has_unquoted_brace(text):
    # type: (str) -> bool
    """True when ``text`` contains a ``{`` or ``}`` outside any quoted
    string (quote-aware; escaped characters skipped)."""
    quote = None
    index = 0
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
        elif character in "{}":
            return True
        index += 1
    return False


def _validate_literal_source_dir(argument):
    # type: (str) -> Optional[str]
    """Return the directory when ``argument`` is a plain quoted, repository-
    safe, module-relative POSIX path literal; ``None`` otherwise (dynamic,
    absolute, traversal, backslash, wildcard, template, or empty)."""
    token = argument.strip()
    match = _QUOTED_LITERAL_RE.match(token)
    if match is None:
        return None
    inner = match.group(1) if match.group(1) is not None else match.group(2)
    if not inner or "$" in inner:
        return None
    if "\\" in inner or ":" in inner or "*" in inner or "?" in inner:
        return None
    if inner.startswith("/") or inner.startswith("~"):
        return None
    parts = inner.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return None
    return inner


def _parse_main_source_set_content(content):
    # type: (str) -> Tuple[Tuple[str, ...], Optional[str]]
    """Strictly parse one ``main`` source-set body (a braced group body or a
    dot-chain statement).

    Returns ``(literal_module_relative_dirs, None)`` when every source-dir
    addition is a supported ``java.srcDirs("...")`` / ``kotlin.srcDirs("...")``
    call with plain quoted literal module-relative paths, or ``((), reason)``
    when anything unmodeled appears.
    """
    if "sourcesets" in content.lower():
        return (), _REASON_CUSTOM_SOURCE_SET  # nested container mutation
    covered = []  # type: List[Tuple[int, int]]
    dirs = []  # type: List[str]
    for match in _SUPPORTED_SRC_DIRS_RE.finditer(content):
        open_index = match.end() - 1  # index of the '(' ending the match
        args_text = _extract_call_args(content, open_index)
        if args_text is None or not args_text.strip():
            return (), _REASON_DYNAMIC_SOURCE_DIR
        for argument in _split_top_level_args(args_text):
            directory = _validate_literal_source_dir(argument)
            if directory is None:
                return (), _REASON_DYNAMIC_SOURCE_DIR
            dirs.append(directory)
        covered.append((match.start(), open_index + 1 + len(args_text) + 1))
    masked = list(content)
    for start, end in covered:
        for position in range(start, min(end, len(masked))):
            masked[position] = " "
    if "srcdir" in "".join(masked).lower():
        # Any other srcDir form (setSrcDirs, +=, assignment, bare srcDir...)
        # is a source-set customization we do not model.
        return (), _REASON_CUSTOM_SOURCE_SET
    if _has_unquoted_brace(content):
        # Nested accessor blocks (java { srcDirs(...) }) or closures inside
        # the main source set are not modeled.
        return (), _REASON_CUSTOM_SOURCE_SET
    return tuple(dirs), None


def _consume_dot_chain(text, index):
    # type: (str, int) -> Optional[int]
    """Consume a ``.ident(...).ident(...)`` chain starting at the ``.`` at
    ``index`` (quote-aware, balanced parens).  Returns the index just past
    the chain, or ``None`` when the chain is malformed or never closes."""
    length = len(text)
    while index < length and text[index] == ".":
        index += 1
        while index < length and text[index].isspace():
            index += 1
        match = _IDENT_RE.match(text, index)
        if match is None:
            return None
        index = match.end()
        while index < length and text[index].isspace():
            index += 1
        if index < length and text[index] == "(":
            args_text = _extract_call_args(text, index)
            if args_text is None:
                return None
            index += 1 + len(args_text) + 1
            while index < length and text[index].isspace():
                index += 1
    return index


def _extract_group_or_chain(body, start, cursor):
    # type: (str, int, int) -> Optional[Tuple[str, int]]
    """From ``cursor`` (just past a named accessor or bare quoted source-set
    name), return ``(content, next_index)`` for the braced group, the dot
    chain, or the bare call.  ``content`` starts at ``start`` so supported
    ``java.srcDirs(...)`` calls inside a chain remain visible.  ``None``
    when the shape is malformed or never closes."""
    length = len(body)
    while cursor < length and body[cursor].isspace():
        cursor += 1
    if cursor >= length:
        return "", cursor
    if body[cursor] == "{":
        extracted = _extract_braced_block(body, cursor)
        if extracted is None:
            return None
        content, close_index = extracted
        return content, close_index + 1
    if body[cursor] == ".":
        chain_end = _consume_dot_chain(body, cursor)
        if chain_end is None:
            return None
        return body[start:chain_end], chain_end
    return "", cursor


def _advance_unattributed_statement(text, index):
    # type: (str, int) -> int
    """Advance past one unattributed statement: stop after the first newline
    or ``;`` at paren/bracket depth zero (quote-aware)."""
    depth = 0
    quote = None
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
        elif character in "([":
            depth += 1
        elif character in ")]":
            if depth > 0:
                depth -= 1
        elif depth == 0 and (character == "\n" or character == ";"):
            return index + 1
        index += 1
    return length


def _parse_source_sets_body(body):
    # type: (str) -> Tuple[Tuple[str, ...], Optional[str]]
    """Parse the body of one ``sourceSets { ... }`` block.

    Literal-named source-set groups/chains (``getByName("main") { ... }``,
    ``named("main")`` chains, bare ``"main" { ... }``) are attributed to
    their source set; ``main`` bodies are strictly parsed for literal
    ``java.srcDirs``/``kotlin.srcDirs`` paths, non-``main`` bodies are
    ignored (they cannot define production roots).  Unattributed statements
    fail closed when they contain a brace or any ``srcDir`` form.
    """
    dirs = []  # type: List[str]
    index = 0
    length = len(body)
    while index < length:
        while index < length and (body[index].isspace() or body[index] == ";"):
            index += 1
        if index >= length:
            break
        match = _NAMED_ACCESSOR_RE.match(body, index)
        if match is not None:
            name = (
                match.group(1) if match.group(1) is not None else match.group(2)
            )
            extracted = _extract_group_or_chain(body, match.start(), match.end())
            if extracted is None:
                return (), _REASON_CUSTOM_SOURCE_SET
            content, next_index = extracted
        elif body[index] in "\"'":
            quoted = _BARE_QUOTED_RE.match(body, index)
            if quoted is None:
                return (), _REASON_CUSTOM_SOURCE_SET  # unclosed quote
            name = (
                quoted.group(1)
                if quoted.group(1) is not None
                else quoted.group(2)
            )
            extracted = _extract_group_or_chain(body, index, quoted.end())
            if extracted is None:
                return (), _REASON_CUSTOM_SOURCE_SET
            content, next_index = extracted
        else:
            # Unattributed statement (e.g. configureEach { }, all { }, or an
            # unknown call): any brace (unknown closure/group) or any
            # srcDir form is a customization we cannot model.
            statement_end = _advance_unattributed_statement(body, index)
            statement = body[index:statement_end]
            if "srcdir" in statement.lower() or _has_unquoted_brace(statement):
                return (), _REASON_CUSTOM_SOURCE_SET
            index = statement_end
            continue
        if name.strip().lower() == "main":
            group_dirs, group_reason = _parse_main_source_set_content(content)
            if group_reason is not None:
                return (), group_reason
            dirs.extend(group_dirs)
        index = next_index
    return tuple(dirs), None


def parse_module_build_sources(text):
    # type: (object) -> Tuple[Tuple[str, ...], Optional[str]]
    """Parse one module ``build.gradle.kts`` for production-relevant
    source-dir customization.

    Returns ``(main_src_dirs, None)`` when the layout is supported —
    ``main_src_dirs`` holds the literal module-relative directories added to
    the ``main`` source set via ``java.srcDirs("...")`` /
    ``kotlin.srcDirs("...")`` (empty when the module keeps only conventional
    sources) — or ``((), reason)`` when the layout is unsupported:

      * ``dynamic-source-dir-expression`` -- a supported-form srcDirs call
        has non-literal arguments;
      * ``custom-source-set-or-projectdir`` -- any other source-set or
        projectDir customization (see the module docstring for the closed
        list);
      * ``dynamic-source-dir-expression`` for non-string input.

    Line comments are stripped with quote awareness first; block comments
    are unhandled (fail closed via marker/brace scans).
    """
    if not isinstance(text, str):
        return (), _REASON_DYNAMIC_SOURCE_DIR
    stripped = _strip_line_comments(text)
    lowered = stripped.lower()
    if _PROJECTDIR_OVERRIDE_RE.search(lowered) or "setroot" in lowered:
        return (), _REASON_CUSTOM_SOURCE_SET
    spans = []
    for match in _SOURCESETS_TOKEN_RE.finditer(stripped):
        cursor = match.end()
        while cursor < len(stripped) and stripped[cursor].isspace():
            cursor += 1
        if cursor >= len(stripped) or stripped[cursor] != "{":
            # e.g. Groovy-style sourceSets.main.java.srcDirs(...) — not
            # modeled in .kts files.
            return (), _REASON_CUSTOM_SOURCE_SET
        extracted = _extract_braced_block(stripped, cursor)
        if extracted is None:
            return (), _REASON_CUSTOM_SOURCE_SET  # unclosed block
        spans.append((match.start(), extracted[1] + 1))
    dirs = []  # type: List[str]
    for start, end in spans:
        block = stripped[start:end]
        open_index = block.index("{")
        body = block[open_index + 1 : len(block) - 1]
        block_dirs, block_reason = _parse_source_sets_body(body)
        if block_reason is not None:
            return (), block_reason
        dirs.extend(block_dirs)
    masked = list(stripped)
    for start, end in spans:
        for position in range(start, min(end, len(masked))):
            masked[position] = " "
    if "srcdir" in "".join(masked).lower():
        # srcDir usage outside a modeled sourceSets block cannot be
        # attributed to a source set — fail closed.
        return (), _REASON_CUSTOM_SOURCE_SET
    return tuple(dirs), None


def _root_build_layout_reason(text):
    # type: (str) -> Optional[str]
    """Controlled reason when the root ``build.gradle.kts`` touches source
    layout at all (never modeled at root level), or ``None``."""
    stripped = _strip_line_comments(text)
    lowered = stripped.lower()
    if "sourcesets" in lowered or "srcdir" in lowered or "setroot" in lowered:
        return _REASON_CUSTOM_SOURCE_SET
    if _PROJECTDIR_OVERRIDE_RE.search(lowered):
        return _REASON_CUSTOM_SOURCE_SET
    return None


def _collect_build_file_layouts(repo_abs, modules):
    # type: (str, Tuple[str, ...]) -> Tuple[Dict[str, Tuple[str, ...]], Tuple[Tuple[str, Dict[str, object]], ...]]
    """Fail-closed build-file layout checks (PR-GR-10B Slice 3).

    Inspects the root ``build.gradle.kts`` (if present) and every declared
    module's ``build.gradle.kts`` (required).  Returns
    ``(custom_dirs_by_module, diagnostics)``: the mapping from module to its
    literal ``main`` srcDir directories (module-relative, source order) and
    LAYOUT_UNSUPPORTED diagnostics in deterministic order (root first, then
    module declaration order).  When ``diagnostics`` is non-empty the layout
    is unsupported and callers must fail closed before any partial
    conclusion.
    """
    custom_dirs_by_module = {}  # type: Dict[str, Tuple[str, ...]]
    diagnostics = []  # type: List[Tuple[str, Dict[str, object]]]

    root_build_path = os.path.join(repo_abs, _TARGET_ROOT_BUILD)
    if os.path.isfile(root_build_path):
        try:
            with open(root_build_path, "r", encoding="utf-8") as handle:
                root_build_text = handle.read()
        except (OSError, UnicodeDecodeError):
            diagnostics.append(
                (
                    DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
                    {"reason": _REASON_ROOT_BUILD_UNREADABLE},
                )
            )
        else:
            root_reason = _root_build_layout_reason(root_build_text)
            if root_reason is not None:
                diagnostics.append(
                    (
                        DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
                        {"reason": root_reason},
                    )
                )

    for module in modules:
        base = _module_directory(module)
        build_path = (
            os.path.join(repo_abs, *base.split("/"), _TARGET_MODULE_BUILD)
            if base is not None
            else None
        )
        if build_path is None or not os.path.isfile(build_path):
            diagnostics.append(
                (
                    DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
                    {"reason": _REASON_MODULE_BUILD_MISSING},
                )
            )
            continue
        try:
            with open(build_path, "r", encoding="utf-8") as handle:
                build_text = handle.read()
        except (OSError, UnicodeDecodeError):
            diagnostics.append(
                (
                    DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
                    {"reason": _REASON_MODULE_BUILD_UNREADABLE},
                )
            )
            continue
        custom_dirs, build_reason = parse_module_build_sources(build_text)
        if build_reason is not None:
            diagnostics.append(
                (
                    DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
                    {"reason": build_reason},
                )
            )
            continue
        custom_dirs_by_module[module] = custom_dirs

    return custom_dirs_by_module, tuple(diagnostics)


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


def observed_conventional_roots(repo_root, modules, custom_dirs_by_module=None):
    # type: (str, object, Optional[Dict[str, Tuple[str, ...]]]) -> Tuple[Tuple[str, ...], Tuple[Tuple[str, str], ...]]
    """Observe production candidate roots for each declared module.

    For each module ``":a:b"`` checks whether ``a/b/src/main/java`` and
    ``a/b/src/main/kotlin`` exist below ``repo_root`` and recursively contain
    at least one readable ``.kt`` file, excluding any path segment named
    ``test``/``androidTest``/``debug``/``release``/``generated``/``build``
    and never following symlinks.

    When ``custom_dirs_by_module`` is supplied, each module's literal
    ``main`` srcDir directories (module-relative, from
    ``parse_module_build_sources``) are appended to that module's candidate
    list after the conventional tails, in source order.

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
        rel_candidates = [base + "/" + tail for tail in _CONVENTIONAL_TAILS]
        if custom_dirs_by_module:
            rel_candidates.extend(custom_dirs_by_module.get(module, ()))
        for rel in rel_candidates:
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

    # PR-GR-10B Slice 3: root/module build-file layout checks fail closed
    # before any partial conclusion (manifest load, topology comparison).
    custom_dirs_by_module, build_diagnostics = _collect_build_file_layouts(
        os.path.abspath(repo_root), modules
    )
    if build_diagnostics:
        return build_diagnostics

    root_set, manifest_diagnostics = load_source_root_manifest(manifest_path)
    if manifest_diagnostics:
        return tuple(manifest_diagnostics)
    assert root_set is not None

    diagnostics = []  # type: List[Tuple[str, Dict[str, object]]]

    # 1. Declared-root filesystem checks (manifest order).
    diagnostics.extend(verify_declared_root_topology(repo_root, root_set))

    # 2. Observation issues (discovery order; deduplicated against (1)).
    observed, issues = observed_conventional_roots(
        repo_root, modules, custom_dirs_by_module
    )
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
