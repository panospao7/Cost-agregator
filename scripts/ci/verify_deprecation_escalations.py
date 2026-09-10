#!/usr/bin/env python3
"""
VERIFY_DEPRECATION_ESCALATIONS — PR-GR-10a deprecation escalation guard.

Process rule (enforced here): every escalation of a Kotlin declaration to
``@Deprecated(..., level = DeprecationLevel.ERROR)`` in production source must
be announced in the tracked changelog ``docs/ci/DEPRECATION_ESCALATIONS.md``
BEFORE it lands. The guard enforces the PRESENCE of a changelog entry (file +
symbol + date + reason + migration target); it does not judge the approval
flow behind the entry.

Background (evidence): rounds R12/R13 landed consecutive
``compileDebugKotlin`` breakages (setZone misuse; a @Deprecated(ERROR) call
site) which zeroed all Kotlin validation for those rounds. This guard makes
new ERROR-deprecation call-site breakage visible at the source level: an
unannounced escalation is a CI finding even before compilation runs.

Scope:
    The declared production roots of the checked-in manifest
    ``config/guards/production_source_roots.yml`` (via
    ``scripts/guardrails/production_source_scope.py`` — currently
    ``app/src/main/java``; recursive; test, androidTest, generated, build,
    migration segments are skipped by the legacy .java enumeration leg).
    ``--source`` must name a declared root; there is NO conventional-root
    fallback: a missing, malformed, or undeclared manifest fails closed
    with exit 2.

Detection:
    The source is masked (line/block comments, KDoc, string and char literals
    blanked; newlines preserved; ``${...}`` template expressions preserved —
    same masking model as scripts/verify_time_boundaries.py) and scanned for
    ``@Deprecated( ... )`` annotation uses whose balanced argument list
    contains ``DeprecationLevel.ERROR`` (named ``level = ...`` or positional).

Fingerprint:
    (repository-relative POSIX file path, annotated declaration name).
    The declaration name is read from the masked source after the annotation's
    closing paren: intervening annotations (``@Query``, ``@Suppress``, ...,
    including use-site targets) and declaration modifiers (``suspend``,
    ``override``, ``private``, ``data``, ...) are skipped, then the
    ``fun``/``val``/``var``/``class``/``interface``/``object``/``typealias``/
    ``constructor`` keyword and its identifier are read. Overloads in the same
    file intentionally collapse to ONE fingerprint: one changelog row covers
    every ERROR-deprecated overload of that name in that file.

Changelog format (markdown table; cells must not contain ``|``):

    | File | Symbol | Date | Reason | Migration target |
    | --- | --- | --- | --- | --- |
    | app/src/main/java/.../Foo.kt | oldApi | 2026-08-30 | why | newApi() |

    * ``File``   — repo-relative POSIX path (``.kt``/``.java``); entries
      whose file lies outside the scanned source root can never match a
      live site and are reported as stale.
    * ``Symbol`` — bare declaration identifier (``[A-Za-z_][A-Za-z0-9_]*``).
    * ``Date``   — ``YYYY-MM-DD``.
    * ``Reason`` / ``Migration target`` — non-empty free text.

Verdicts (exit codes owned exclusively by ``main()``):
    0 — every ERROR-deprecation fingerprint has a changelog entry and every
        entry matches a live site.
    1 — findings: G-DEPRECATION-01 (site without entry) and/or
        G-DEPRECATION-02 (stale entry whose site no longer exists).
        Findings are unconditional (no --fail-on-violation flag): a finding
        IS the violation; the guard is registered blocking.
    2 — infrastructure error, fail closed: missing/unreadable source tree,
        empty source tree, unreadable source file, unterminated annotation,
        declaration name that cannot be determined, missing changelog file,
        or a malformed changelog (broken table, wrong cell count, invalid
        cell, duplicate entry, missing ledger table).

Output (deterministic):
    G-DEPRECATION-01 <file>:<line> <symbol> — <message>
    G-DEPRECATION-02 <changelog>:<row> <file>::<symbol> — <message>
    Missing findings first (sorted by file, then symbol), then stale findings
    (sorted the same way).

Known limitations (documented, fail closed or benign):
    * ``@ Deprecated`` (space after ``@``) is detected; use-site-targeted
      ``@get:Deprecated(...)`` is not (no such site exists).
    * A ``@Deprecated`` annotation whose declaration cannot be resolved is an
      exit-2 infrastructure error, never a silent skip.
    * ``constructor`` deprecations fingerprint as the literal symbol
      ``constructor`` (no such site exists today).

Usage:
    python3 scripts/ci/verify_deprecation_escalations.py --root .
    python3 scripts/ci/verify_deprecation_escalations.py --root . \
        --changelog docs/ci/DEPRECATION_ESCALATIONS.md \
        --source app/src/main/java
"""

import argparse
import datetime
import os
import re
import sys

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from shared_guard_engine import find_source_files, safe_read_file  # noqa: E402

_SCRIPT_PARENT = os.path.dirname(_SCRIPT_DIR)
if _SCRIPT_PARENT not in sys.path:
    sys.path.insert(0, _SCRIPT_PARENT)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
)

# ── Controlled constants ────────────────────────────────────────────────────────

RULE_ID_MISSING = "G-DEPRECATION-01"
RULE_ID_STALE = "G-DEPRECATION-02"

DEFAULT_SOURCE = "app/src/main/java"
DEFAULT_CHANGELOG = "docs/ci/DEPRECATION_ESCALATIONS.md"

# Token searched inside a @Deprecated argument list (named or positional form).
_ERROR_LEVEL_RE = re.compile(r"DeprecationLevel\.ERROR\b")
_DEPRECATED_RE = re.compile(r"@\s*Deprecated\s*\(")

# Changelog table shape.
_HEADER_CELLS = ("file", "symbol", "date", "reason", "migration target")
_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_SYMBOL_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_FILE_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_./-]*\.(?:kt|java)$")
_SEPARATOR_CELL_RE = re.compile(r"^:?-{3,}:?$")

# Declaration extraction.
_IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_QUAL_IDENT_RE = re.compile(
    r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*"
)
_MODIFIER_KEYWORDS = frozenset({
    "actual", "annotation", "companion", "const", "data", "enum", "expect",
    "external", "final", "infix", "inner", "internal", "lateinit", "open",
    "operator", "override", "private", "protected", "public", "sealed",
    "suspend", "tailrec", "value",
})
_DECL_KEYWORDS = frozenset({
    "class", "constructor", "fun", "interface", "object", "typealias",
    "val", "var",
})

__all__ = [
    "ChangelogError",
    "GuardFatalError",
    "mask_kotlin",
    "parse_changelog_entries",
    "scan_error_deprecation_sites",
    "main",
]


# ── Fatal errors (exit 2) ───────────────────────────────────────────────────────

class GuardFatalError(Exception):
    """Infrastructure error — the guard must exit 2 (fail closed)."""


class ChangelogError(Exception):
    """Malformed changelog — the guard must exit 2 (fail closed)."""


class _ExtractionError(Exception):
    """Internal: annotated declaration could not be determined."""


# ── Masking (same model as scripts/verify_time_boundaries.py) ───────────────────

def _mask_template(content: str, i: int, out: list) -> int:
    """Preserve a ``${...}`` template expression while masking the surrounding
    string. ``i`` points at the ``$`` of ``${``. Returns index past ``}``."""
    n = len(content)
    out.append("$")
    out.append("{")
    depth = 1
    j = i + 2
    while j < n and depth > 0:
        c = content[j]
        if c == '"':
            if content.startswith('"""', j):
                j = _mask_triple_string(content, j, out)
            else:
                j = _mask_single_string(content, j, out)
            continue
        if c == "'":
            j = _mask_char_literal(content, j, out)
            continue
        if c == "/" and j + 1 < n:
            nxt = content[j + 1]
            if nxt == "/":
                j = _mask_line_comment(content, j, out)
                continue
            if nxt == "*":
                j = _mask_block_comment(content, j, out)
                continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        # Template expression code is preserved verbatim (it is real code);
        # nested strings/comments inside it were masked by the branches above.
        out.append(c)
        j += 1
    return j


def _mask_single_string(content: str, i: int, out: list) -> int:
    """Mask a ``"..."`` string literal; preserve ``${...}`` template code."""
    n = len(content)
    out.append(" ")  # opening quote
    j = i + 1
    while j < n:
        c = content[j]
        if c == "\\":
            out.append(" ")
            out.append(" ")
            j += 2
            continue
        if c == '"':
            out.append(" ")  # closing quote
            return j + 1
        if c == "$" and j + 1 < n and content[j + 1] == "{":
            j = _mask_template(content, j, out)
            continue
        out.append("\n" if c == "\n" else " ")
        j += 1
    return n


def _mask_triple_string(content: str, i: int, out: list) -> int:
    """Mask a ``\"\"\"...\"\"\"`` raw string; preserve ``${...}`` templates."""
    n = len(content)
    out.extend([" ", " ", " "])  # opening quotes
    j = i + 3
    while j < n:
        if content.startswith('"""', j):
            out.extend([" ", " ", " "])  # closing quotes
            return j + 3
        c = content[j]
        if c == "$" and j + 1 < n and content[j + 1] == "{":
            j = _mask_template(content, j, out)
            continue
        out.append("\n" if c == "\n" else " ")
        j += 1
    return n


def _mask_char_literal(content: str, i: int, out: list) -> int:
    """Mask a ``'c'`` character literal."""
    n = len(content)
    out.append(" ")  # opening quote
    j = i + 1
    while j < n:
        c = content[j]
        if c == "\\":
            out.append(" ")
            out.append(" ")
            j += 2
            continue
        if c == "'":
            out.append(" ")  # closing quote
            return j + 1
        out.append("\n" if c == "\n" else " ")
        j += 1
    return n


def _mask_line_comment(content: str, i: int, out: list) -> int:
    """Mask a ``//`` line comment (keeps newline)."""
    n = len(content)
    j = i
    while j < n and content[j] != "\n":
        out.append(" ")
        j += 1
    return j


def _mask_block_comment(content: str, i: int, out: list) -> int:
    """Mask a ``/* ... */`` block comment (keeps newlines)."""
    n = len(content)
    out.extend([" ", " "])
    j = i + 2
    while j < n:
        if content.startswith("*/", j):
            out.extend([" ", " "])
            return j + 2
        c = content[j]
        out.append("\n" if c == "\n" else " ")
        j += 1
    return n


def mask_kotlin(content: str) -> str:
    """Return ``content`` with comments and string/char literals masked.

    Newlines are preserved so line numbers stay aligned. ``${...}`` template
    expressions are preserved (they are code, and their strings are masked
    recursively, so paren balance of the surrounding code is intact).
    """
    out: list = []
    i = 0
    n = len(content)
    while i < n:
        c = content[i]
        if c == "/" and i + 1 < n:
            nxt = content[i + 1]
            if nxt == "/":
                i = _mask_line_comment(content, i, out)
                continue
            if nxt == "*":
                i = _mask_block_comment(content, i, out)
                continue
        if c == '"':
            if content.startswith('"""', i):
                i = _mask_triple_string(content, i, out)
            else:
                i = _mask_single_string(content, i, out)
            continue
        if c == "'":
            i = _mask_char_literal(content, i, out)
            continue
        out.append(c)
        i += 1
    return "".join(out)


# ── Masked-source scanning helpers ──────────────────────────────────────────────

def _skip_ws(masked: str, pos: int) -> int:
    n = len(masked)
    while pos < n and masked[pos] in " \t\r\n\f\v":
        pos += 1
    return pos


def _find_balanced_close(masked: str, open_idx: int):
    """Index of the ``)`` matching the ``(`` at ``open_idx``, or None."""
    depth = 0
    n = len(masked)
    j = open_idx
    while j < n:
        c = masked[j]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return None


def _skip_angle_brackets(masked: str, pos: int) -> int:
    """Skip a balanced ``<...>`` generic parameter list starting at ``pos``."""
    depth = 0
    n = len(masked)
    j = pos
    while j < n:
        c = masked[j]
        if c == "<":
            depth += 1
        elif c == ">":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    raise _ExtractionError()


def _skip_annotation(masked: str, pos: int) -> int:
    """Skip one annotation use (``@Name``, ``@a.b.Name(...)``,
    ``@target:Name(...)``) in masked source. ``pos`` points at ``@``."""
    n = len(masked)
    pos += 1
    pos = _skip_ws(masked, pos)
    m = _QUAL_IDENT_RE.match(masked, pos)
    if m is None:
        raise _ExtractionError()
    pos = m.end()
    after = _skip_ws(masked, pos)
    if after < n and masked[after] == ":":
        # Use-site target: ':' followed by the annotation name.
        pos = _skip_ws(masked, after + 1)
        m2 = _IDENT_RE.match(masked, pos)
        if m2 is None:
            raise _ExtractionError()
        pos = m2.end()
    pos = _skip_ws(masked, pos)
    if pos < n and masked[pos] == "(":
        close = _find_balanced_close(masked, pos)
        if close is None:
            raise _ExtractionError()
        pos = close + 1
    return pos


def _extract_declaration_name(masked: str, pos: int) -> str:
    """Read the name of the declaration annotated at/after ``pos``.

    Skips whitespace, further annotations and declaration modifiers, then
    reads the declaration keyword and its identifier. Raises
    ``_ExtractionError`` when the declaration cannot be proven (fail closed).
    """
    n = len(masked)
    pos = _skip_ws(masked, pos)
    while pos < n:
        if masked[pos] == "@":
            pos = _skip_annotation(masked, pos)
            pos = _skip_ws(masked, pos)
            continue
        m = _IDENT_RE.match(masked, pos)
        if m is None:
            raise _ExtractionError()
        word = m.group(0)
        if word in _MODIFIER_KEYWORDS:
            pos = _skip_ws(masked, m.end())
            continue
        if word in _DECL_KEYWORDS:
            pos = _skip_ws(masked, m.end())
            if word == "constructor":
                return "constructor"
            if word == "fun" and pos < n and masked[pos] == "<":
                pos = _skip_angle_brackets(masked, pos)
                pos = _skip_ws(masked, pos)
            name_m = _IDENT_RE.match(masked, pos)
            if name_m is None:
                raise _ExtractionError()
            return name_m.group(0)
        raise _ExtractionError()
    raise _ExtractionError()


# ── Site scan ───────────────────────────────────────────────────────────────────

def scan_error_deprecation_sites(root: str, source_rel: str) -> dict:
    """Scan the source tree for ERROR-deprecation fingerprints.

    PR-GR-10B: the scanned production source scope is the declared roots of
    the checked-in manifest ``config/guards/production_source_roots.yml``
    (fail closed — no conventional-root fallback).  ``source_rel`` must be
    one of the declared manifest roots.  Kotlin files are enumerated via
    the neutral production source-scope module (deterministic declared-root
    order, then canonical path order); the historical ``.java`` extension is
    still enumerated within the declared roots with the legacy skip-directory
    pruning, and the merged file list is sorted exactly as before.

    Returns ``{(rel_posix_path, symbol): first_line}`` (first occurrence wins,
    so overloads collapse to one fingerprint deterministically). Raises
    ``GuardFatalError`` on any fail-closed condition.
    """
    root_set, scope_diagnostics = resolve_production_source_scope(root)
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        raise GuardFatalError(
            f"production source scope unresolved: {codes}"
        )
    if source_rel.replace("\\", "/") not in set(root_set.paths):
        raise GuardFatalError(
            f"source tree not declared in the production source-root "
            f"manifest: {source_rel}"
        )

    files: list = []
    seen: set = set()
    try:
        for source_file in iter_production_kotlin_files(root, root_set):
            if source_file.absolute_path not in seen:
                seen.add(source_file.absolute_path)
                files.append(source_file.absolute_path)
    except ProductionSourceScopeError as exc:
        raise GuardFatalError(
            f"production source enumeration failed: {exc.code}"
        )
    for declared_root in root_set.paths:
        # Historical .java extension coverage within the declared roots;
        # the root authority remains the manifest-declared root set.
        for java_path in find_source_files(
            root, subdir=declared_root, patterns=["*.java"]
        ):
            if java_path not in seen:
                seen.add(java_path)
                files.append(java_path)
    files.sort()
    if not files:
        raise GuardFatalError(f"no Kotlin/Java source files under {source_rel}")

    sites: dict = {}
    for path in files:
        content, err = safe_read_file(path)
        if err:
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            raise GuardFatalError(f"cannot read source file: {rel}")
        masked = mask_kotlin(content)
        rel = os.path.relpath(path, root).replace(os.sep, "/")

        for m in _DEPRECATED_RE.finditer(masked):
            open_idx = m.end() - 1
            close = _find_balanced_close(masked, open_idx)
            line = masked.count("\n", 0, m.start()) + 1
            if close is None:
                raise GuardFatalError(
                    f"unterminated @Deprecated argument list near {rel}:{line}"
                )
            if not _ERROR_LEVEL_RE.search(masked[open_idx + 1:close]):
                continue
            try:
                symbol = _extract_declaration_name(masked, close + 1)
            except _ExtractionError:
                raise GuardFatalError(
                    f"cannot determine annotated declaration near {rel}:{line}"
                )
            key = (rel, symbol)
            if key not in sites:
                sites[key] = line
    return sites


# ── Changelog parsing ───────────────────────────────────────────────────────────

def _split_table_row(line: str):
    """Split a markdown table row (``| a | b | ...``) into stripped cells."""
    body = line[1:]
    if body.endswith("|"):
        body = body[:-1]
    return [cell.strip() for cell in body.split("|")]


def _is_valid_ledger_date(text: str) -> bool:
    """True when ``text`` is a real calendar date in ``YYYY-MM-DD`` form."""
    if not _DATE_RE.match(text):
        return False
    try:
        datetime.date.fromisoformat(text)
    except ValueError:
        return False
    return True


def _validate_entry_cells(cells, line_no: int) -> tuple:
    """Validate one ledger data row; returns (file, symbol)."""
    file_cell, symbol_cell, date_cell, reason_cell, migration_cell = cells
    if not _FILE_RE.match(file_cell) or "\\" in file_cell or "/../" in file_cell \
            or file_cell.startswith("../"):
        raise ChangelogError(f"malformed 'file' cell at changelog line {line_no}")
    if not _SYMBOL_RE.match(symbol_cell):
        raise ChangelogError(f"malformed 'symbol' cell at changelog line {line_no}")
    if not _is_valid_ledger_date(date_cell):
        raise ChangelogError(f"malformed 'date' cell at changelog line {line_no}")
    if not reason_cell:
        raise ChangelogError(f"empty 'reason' cell at changelog line {line_no}")
    if not migration_cell:
        raise ChangelogError(
            f"empty 'migration target' cell at changelog line {line_no}"
        )
    return file_cell, symbol_cell


def parse_changelog_entries(text: str) -> dict:
    """Parse the escalation ledger table from changelog text.

    Returns ``{(file, symbol): row_line}``. Raises ``ChangelogError`` when the
    ledger table is missing or any row is malformed (fail closed).
    """
    entries: dict = {}
    header_seen = False
    for line_no, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line.startswith("|"):
            continue
        cells = _split_table_row(line)
        normalized = [cell.lower() for cell in cells]
        if tuple(normalized) == _HEADER_CELLS:
            header_seen = True
            continue
        if not header_seen:
            continue  # prose or an unrelated table above the ledger
        if cells and all(_SEPARATOR_CELL_RE.match(c) for c in cells):
            if len(cells) != len(_HEADER_CELLS):
                raise ChangelogError(
                    f"malformed separator row at changelog line {line_no}"
                )
            continue
        if len(cells) != len(_HEADER_CELLS):
            raise ChangelogError(
                f"expected {len(_HEADER_CELLS)} cells at changelog line {line_no}, "
                f"found {len(cells)}"
            )
        file_cell, symbol_cell = _validate_entry_cells(cells, line_no)
        key = (file_cell, symbol_cell)
        if key in entries:
            raise ChangelogError(
                f"duplicate ledger entry {file_cell}::{symbol_cell} "
                f"at changelog line {line_no}"
            )
        entries[key] = line_no
    if not header_seen:
        raise ChangelogError("no escalation ledger table found in changelog")
    return entries


# ── Verdict ─────────────────────────────────────────────────────────────────────

def compare_sites_to_entries(sites: dict, entries: dict) -> tuple:
    """Compute (missing, stale) fingerprint lists, deterministically sorted."""
    site_keys = set(sites)
    entry_keys = set(entries)
    missing = sorted(site_keys - entry_keys)
    stale = sorted(entry_keys - site_keys)
    return missing, stale


def _format_missing(rel: str, symbol: str, line: int, changelog_rel: str) -> str:
    return (
        f"{RULE_ID_MISSING} {rel}:{line} {symbol} — DeprecationLevel.ERROR site "
        f"has no entry in {changelog_rel} (announce before landing: add a row "
        f"with file | symbol | date | reason | migration target)"
    )


def _format_stale(changelog_rel: str, row_line: int, rel: str, symbol: str,
                  source_rel: str) -> str:
    return (
        f"{RULE_ID_STALE} {changelog_rel}:{row_line} {rel}::{symbol} — stale "
        f"changelog entry: no DeprecationLevel.ERROR site exists under "
        f"{source_rel} (remove the row)"
    )


# ── CLI ─────────────────────────────────────────────────────────────────────────

def main(argv=None) -> None:
    """CLI adapter; the only place in this module allowed to ``sys.exit``."""
    parser = argparse.ArgumentParser(
        description=(
            "Verify every @Deprecated(DeprecationLevel.ERROR) site in "
            "production Kotlin has a tracked escalation-changelog entry."
        )
    )
    parser.add_argument(
        "--root", type=str, default=".",
        help="Project root directory (default: current directory).",
    )
    parser.add_argument(
        "--changelog", type=str, default=DEFAULT_CHANGELOG,
        help=f"Changelog path relative to --root (default: {DEFAULT_CHANGELOG}).",
    )
    parser.add_argument(
        "--source", type=str, default=DEFAULT_SOURCE,
        help=f"Source tree relative to --root (default: {DEFAULT_SOURCE}).",
    )
    args = parser.parse_args(argv)

    root = os.path.abspath(args.root)
    changelog_rel = args.changelog.replace("\\", "/")
    source_rel = args.source.replace("\\", "/")
    changelog_path = os.path.join(root, *args.changelog.split("/"))

    # 1. Scan (fail closed on any infrastructure problem).
    try:
        sites = scan_error_deprecation_sites(root, source_rel)
    except GuardFatalError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(2)

    # 2. Load + parse the changelog (fail closed).
    if not os.path.isfile(changelog_path):
        print(
            f"ERROR: changelog not found: {changelog_rel} "
            "(tracked escalation ledger is required)",
            file=sys.stderr,
        )
        sys.exit(2)
    text, err = safe_read_file(changelog_path)
    if err:
        print(f"ERROR: cannot read changelog: {changelog_rel}", file=sys.stderr)
        sys.exit(2)
    try:
        entries = parse_changelog_entries(text)
    except ChangelogError as exc:
        print(
            f"ERROR: malformed changelog {changelog_rel}: {exc}",
            file=sys.stderr,
        )
        sys.exit(2)

    # 3. Compare and report.
    missing, stale = compare_sites_to_entries(sites, entries)
    for rel, symbol in missing:
        print(_format_missing(rel, symbol, sites[(rel, symbol)], changelog_rel))
    for rel, symbol in stale:
        print(_format_stale(
            changelog_rel, entries[(rel, symbol)], rel, symbol, source_rel
        ))

    if missing or stale:
        print(
            f"FAIL: {len(missing)} missing entry(ies), {len(stale)} stale "
            f"entry(ies) across {len(sites)} ERROR-deprecation fingerprint(s)"
        )
        sys.exit(1)

    print(
        f"PASS: {len(sites)} DeprecationLevel.ERROR fingerprint(s) verified "
        f"against {len(entries)} changelog entry(ies)"
    )
    sys.exit(0)


if __name__ == "__main__":
    main()
