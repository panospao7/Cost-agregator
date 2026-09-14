#!/usr/bin/env python3
"""
verify_time_boundaries.py — canonical direct wall-clock time guard (PR-GR-02).

RULE_ID: G-TIME-01

Scans the declared production Kotlin source scope (the roots of the
checked-in manifest ``config/guards/production_source_roots.yml`` via
``scripts/guardrails/production_source_scope.py`` — currently
``app/src/main/java``) and flags direct
wall-clock / calendar-clock API calls that must be routed through the
canonical ``TimeProvider`` boundary (``SystemTimeProvider`` via Hilt):

    System.currentTimeMillis()
    System.nanoTime()
    Date()
    Calendar.getInstance()
    Instant.now()
    LocalDate.now()
    LocalDateTime.now()
    OffsetDateTime.now()
    ZonedDateTime.now()
    Clock.systemDefaultZone()
    Clock.systemUTC()

``System.nanoTime()`` is NOT automatically exempt: it is treated like any
other detected API and only an exact, source-verified exception entry in
``config/guards/time_boundary_exceptions.yml`` can authorize a monotonic
elapsed-duration adapter.

Behavior / exit codes:
    0 — no violations (or violations found without --fail-on-violation)
    1 — violations found AND --fail-on-violation was given
    2 — infrastructure error (missing/malformed/unreadable policy or source,
        empty source tree, stale exception, parser failure)

The guard fails closed:
    - a missing or malformed exceptions file is fatal (exit 2);
    - a missing or empty source tree is fatal (exit 2);
    - an unreadable source file is fatal (exit 2);
    - the policy's top-level mapping must use exactly the keys
      ``version`` and ``exceptions``; unknown top-level keys are fatal
      (exit 2) and ``version`` must be the integer ``SUPPORTED_VERSION``
      (missing, boolean, string, or other-number versions are rejected);
    - every exception entry must use exactly the allowed keys
      (path, class, method, api, reason, owner, linked_issue) with a
      canonical repository-relative source path; unknown keys (expires,
      baseline, permanent, metadata, ...), wildcard values, and
      non-canonical paths (including backslash-separated paths, which are
      rejected before normalization and never silently converted to forward
      slashes) are fatal (exit 2);
    - ``Date()`` is flagged only when the constructor argument list is
      empty (a direct wall-clock read); ``Date(0L)``,
      ``Date(epochMillis)``, or ``Date(timeProvider.now())`` convert an
      already-known epoch and are not flagged; the empty argument list may
      span masked lines (``Date(\n)`` closes on the following line) and is
      detected only when the balanced argument content is whitespace;
    - every exception entry must match real, detected source evidence,
      otherwise the entry is stale and the guard exits 2;
    - an expression-bodied function whose boundary cannot be proven on the
      declaration line attributes later API occurrences to a controlled
      ambiguous marker (``<expression-body>``) instead of carrying a stale
      previous method name; the reserved marker can never be authorized by
      an exact exception, so an exception for method A can never suppress an
      occurrence belonging to a later method or an ambiguous scope;
    - class/type and method scope tracking survives multiline headers: a
      ``class Foo @Inject constructor(...) : Interface {`` header (or a
      wrapped interface list) keeps the declaration pending until the first
      top-level class body ``{`` opens, so members are attributed to their
      real enclosing type instead of ``<file>.<top>``; the same applies to
      block-bodied functions whose parameter list wraps across lines;
    - body-less declarations (``data class Foo(...)`` without a body) never
      create an attribution scope, and a function declared inside another
      function body (a local helper such as ``exceedsSolverBudget`` inside
      ``findMinimalTransferPlan``) is attributed to its enclosing method so
      an exact exception for that method covers its whole implementation;
    - no broad source-line substring exemptions exist (the old
      ``contains("now()")`` / ``contains("now =")`` / ``contains("TimeProvider(")``
      loopholes are gone).

Output format (deterministic):
    G-TIME-01 <relative-path>:<line> <Class>.<method> <message>

Usage:
    python3 scripts/verify_time_boundaries.py --root .
    python3 scripts/verify_time_boundaries.py --root . --fail-on-violation
    python3 scripts/verify_time_boundaries.py --root . \
        --allowlist config/guards/time_boundary_exceptions.yml --fail-on-violation
"""

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
)

RULE_ID = "G-TIME-01"

# Canonical exceptions policy (exact path/class/method/api entries only).
DEFAULT_ALLOWLIST = "config/guards/time_boundary_exceptions.yml"

# PR-GR-10B: the scanned production source scope is declared by the
# checked-in manifest ``config/guards/production_source_roots.yml`` (resolved
# via scripts/guardrails/production_source_scope.py).  There is NO
# conventional-root fallback: a missing, malformed, or undeclared manifest
# fails closed with exit 2.  The historical hard-coded root
# (``app/src/main/java``) is the manifest's currently declared single root,
# so the scanned file set is unchanged.

SUPPORTED_VERSION = 1

# Controlled attribution emitted when an expression-bodied function's boundary
# cannot be proven on the declaration line. Occurrences in that unproven scope
# are attributed to this marker so the scanner fails closed instead of carrying
# the previous method name forward (which could let an exact exception for that
# method suppress a later or ambiguous occurrence). The marker is reserved:
# load_exceptions() rejects it as a policy method, so an ambiguous scope can
# never be authorized.
_AMBIGUOUS_METHOD = "<expression-body>"

# ── API patterns ────────────────────────────────────────────────────────────────
# (compiled pattern, canonical api name, violation message)
# Order matters for deterministic output.
_API_SPECS: List[Tuple[re.Pattern, str, str]] = [
    (
        re.compile(r"\bSystem\.currentTimeMillis\s*\("),
        "System.currentTimeMillis",
        "direct wall-clock read System.currentTimeMillis() — inject TimeProvider and call now()",
    ),
    (
        re.compile(r"\bSystem\.nanoTime\s*\("),
        "System.nanoTime",
        "System.nanoTime() outside an approved elapsed-duration adapter — requires an exact exception",
    ),
    (
        re.compile(r"\bDate\s*\("),
        "Date",
        "direct wall-clock Date() constructor — derive the date from TimeProvider.now()",
    ),
    (
        re.compile(r"\bCalendar\.getInstance\s*\("),
        "Calendar.getInstance",
        "direct wall-clock Calendar.getInstance() — derive the calendar from TimeProvider.now()",
    ),
    (
        re.compile(r"\bInstant\.now\s*\("),
        "Instant.now",
        "direct wall-clock Instant.now() — derive from TimeProvider.now()",
    ),
    (
        re.compile(r"\bLocalDate\.now\s*\("),
        "LocalDate.now",
        "direct wall-clock LocalDate.now() — derive from TimeProvider.now()",
    ),
    (
        re.compile(r"\bLocalDateTime\.now\s*\("),
        "LocalDateTime.now",
        "direct wall-clock LocalDateTime.now() — derive from TimeProvider.now()",
    ),
    (
        re.compile(r"\bOffsetDateTime\.now\s*\("),
        "OffsetDateTime.now",
        "direct wall-clock OffsetDateTime.now() — derive from TimeProvider.now()",
    ),
    (
        re.compile(r"\bZonedDateTime\.now\s*\("),
        "ZonedDateTime.now",
        "direct wall-clock ZonedDateTime.now() — derive from TimeProvider.now()",
    ),
    (
        re.compile(r"\bClock\.systemDefaultZone\s*\("),
        "Clock.systemDefaultZone",
        "direct Clock.systemDefaultZone() — inject the clock through the time provider boundary",
    ),
    (
        re.compile(r"\bClock\.systemUTC\s*\("),
        "Clock.systemUTC",
        "direct Clock.systemUTC() — inject the clock through the time provider boundary",
    ),
]

# Compiled once for speed.
_API_PATTERNS: List[Tuple[re.Pattern, str, str]] = [
    (pat, api, msg) for pat, api, msg in _API_SPECS
]

# ── Declaration tracking ────────────────────────────────────────────────────────
_CLASS_DECL_RE = re.compile(
    r"\b(?:data\s+|sealed\s+|enum\s+|annotation\s+)?(?:class|interface|object)\s+([A-Za-z_]\w*)"
)
_COMPANION_RE = re.compile(r"\bcompanion\s+object(?:\s+([A-Za-z_]\w*))?\s*\{")
_FUN_DECL_RE = re.compile(
    r"\bfun\s+(?:<[^>]*>\s*)?"
    r"(?:[A-Za-z_]\w*(?:\s*<[^>]*>)?\s*\.\s*)*"
    r"([A-Za-z_]\w*)\s*\("
)
# Expressions that must precede "Date(" for it to be a declaration, not a call.
_DECLARATION_PREFIX_RE = re.compile(
    r"(?:(?:class|interface|object|fun|typealias)\s+)$", re.IGNORECASE
)


def _line_opens_body(masked_line: str, enclosing_depth: int, start_depth: int) -> bool:
    """True when ``masked_line`` opens a declaration body ``{``.

    The declaration (class/interface/object/block-bodied function) was
    declared at absolute brace depth ``enclosing_depth`` and its body may be
    separated from the declaration by a multiline constructor/interface/
    parameter header.  The body ``{`` is the first ``{`` that appears while
    parens are balanced and the absolute brace depth equals the enclosing
    depth — i.e. the first top-level brace after the header.

    ``start_depth`` is the absolute brace depth at the start of the line, so
    the check works for wrapped headers that begin mid-scope.
    """
    brace_depth = start_depth
    paren_depth = 0
    for ch in masked_line:
        if ch == "(":
            paren_depth += 1
        elif ch == ")":
            paren_depth = max(0, paren_depth - 1)
        elif ch == "{":
            if paren_depth == 0 and brace_depth == enclosing_depth:
                return True
            brace_depth += 1
        elif ch == "}":
            brace_depth = max(0, brace_depth - 1)
    return False

# Tokens whose presence at the END of a line force a multi-line expression
# body to continue onto the next line (mirrors the DB guard conventions).
_EXPR_CONTINUATION_ENDINGS = (
    ".", ",", "=", "(", "[", "{", "->", "&&", "||", "+", "-", "*", "/", "%",
)


def _line_ends_with_continuation(line: str) -> bool:
    """True when a masked ``line`` ends with an expression-continuation token.

    The line must already be masked (strings/comments are spaces), so trailing
    comments can never hide a real continuation token and a comment-only
    trailing token can never force a spurious continuation.
    """
    s = line.rstrip()
    if not s:
        return False
    return s.endswith(_EXPR_CONTINUATION_ENDINGS)


def _expression_body_provable_on_line(line: str, paren_depth_after: int) -> bool:
    """True when an expression body (``fun ... = expr``) provably completes on
    the declaration line itself.

    The boundary is only proven when parens are balanced at end of line, the
    ``=`` is not the last token, and the line does not end with a continuation
    token. Anything else means the expression continues across lines and the
    method boundary cannot be proven from the declaration line alone.
    """
    if paren_depth_after != 0:
        return False
    if re.search(r"=\s*$", line):
        return False
    if _line_ends_with_continuation(line):
        return False
    return True

# ── Fatal error ─────────────────────────────────────────────────────────────────

class GuardFatalError(Exception):
    """Infrastructure error — the guard must exit 2."""


# ── Masking ─────────────────────────────────────────────────────────────────────

def _mask_template(content: str, i: int, out: List[str]) -> int:
    """Preserve a ``${...}`` template expression while masking the surrounding string.

    ``i`` points at the ``$`` of ``${``.  Nested strings/chars/comments inside
    the template are masked recursively; plain ``{``/``}`` keep brace balance.
    Returns the index just past the matching ``}``.
    """
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
            out.append(c)
        elif c == "}":
            depth -= 1
            out.append(c)
        elif c == "\n":
            out.append(c)
        else:
            out.append(c)
        j += 1
    return j


def _mask_single_string(content: str, i: int, out: List[str]) -> int:
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


def _mask_triple_string(content: str, i: int, out: List[str]) -> int:
    """Mask a ``\"\"\"...\"\"\"`` raw string; preserve ``${...}`` template code."""
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


def _mask_char_literal(content: str, i: int, out: List[str]) -> int:
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


def _mask_line_comment(content: str, i: int, out: List[str]) -> int:
    """Mask a ``//`` line comment (keeps newline)."""
    n = len(content)
    j = i
    while j < n and content[j] != "\n":
        out.append(" ")
        j += 1
    return j


def _mask_block_comment(content: str, i: int, out: List[str]) -> int:
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
    """Return a copy of ``content`` with comments and string literals masked.

    Newlines are preserved so line numbers stay aligned.  String template
    expressions (``${...}``) are preserved so real API calls inside them are
    still detected — masking them would be a source-text exemption loophole.
    """
    out: List[str] = []
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


# ── Violation model ─────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class TimeViolation:
    path: str
    line: int
    class_name: str
    method_name: str
    api: str
    message: str

    @property
    def symbol(self) -> str:
        if self.class_name:
            method = self.method_name or "<init>"
            return f"{self.class_name}.{method}"
        return "<file>.<top>"

    def format(self) -> str:
        return f"{RULE_ID} {self.path}:{self.line} {self.symbol} {self.message}"

    def key_tuple(self) -> Tuple[str, str, str, str]:
        return (self.path, self.class_name, self.method_name, self.api)


# ── Exceptions policy ───────────────────────────────────────────────────────────

_REQUIRED_EXCEPTION_FIELDS = ("path", "class", "method", "api", "reason", "owner", "linked_issue")
# Exact exception schema: any other key (expires, baseline, permanent,
# metadata, unknown, ...) is rejected as an unknown field.
_ALLOWED_EXCEPTION_FIELDS = frozenset(_REQUIRED_EXCEPTION_FIELDS)
_WILDCARD_MARKERS = ("*", "?", "...", "$", "[", "]")
# Canonical exception paths mirror the scanner's rel_path form: forward
# slashes, rooted at the scanned source directory.
_CANONICAL_PATH_PREFIX = "app/src/main/java/"


def _contains_wildcard(value: str) -> bool:
    return any(marker in value for marker in _WILDCARD_MARKERS)


def _is_canonical_path(path: str) -> bool:
    """True when ``path`` is a canonical repository-relative source path.

    Canonical matches how the scanner reports ``rel_path``: forward slashes,
    no leading ``./``, no absolute (drive-letter or root) prefix, no
    ``.``/``..`` segments, rooted at ``app/src/main/java/`` and ending in
    ``.kt``.
    """
    if not path or path.startswith("./") or path.startswith("/"):
        return False
    if re.match(r"^[A-Za-z]:", path):
        return False
    parts = path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return False
    if not path.startswith(_CANONICAL_PATH_PREFIX):
        return False
    if not path.endswith(".kt"):
        return False
    return True


def load_exceptions(allowlist_path: Path) -> List[Dict[str, str]]:
    """Load and validate the exact-exception policy file.

    Raises GuardFatalError (exit 2) on missing, unreadable, malformed, or
    structurally invalid policy content.
    """
    if not allowlist_path.exists():
        raise GuardFatalError(
            f"{RULE_ID}: exceptions policy not found: {allowlist_path}"
        )
    if not allowlist_path.is_file():
        raise GuardFatalError(
            f"{RULE_ID}: exceptions policy is not a regular file: {allowlist_path}"
        )
    try:
        with open(allowlist_path, "r", encoding="utf-8") as f:
            text = f.read()
    except OSError as exc:
        raise GuardFatalError(
            f"{RULE_ID}: exceptions policy unreadable: {allowlist_path} ({exc})"
        )
    if not text.strip():
        raise GuardFatalError(f"{RULE_ID}: exceptions policy is empty: {allowlist_path}")

    try:
        import yaml
    except ImportError:
        raise GuardFatalError(
            f"{RULE_ID}: PyYAML is not installed — cannot parse exceptions policy "
            f"{allowlist_path} (pip install pyyaml)"
        )
    try:
        data = yaml.safe_load(text)
    except Exception as exc:
        raise GuardFatalError(
            f"{RULE_ID}: malformed exceptions policy {allowlist_path}: {exc}"
        )

    if not isinstance(data, dict):
        raise GuardFatalError(
            f"{RULE_ID}: malformed exceptions policy {allowlist_path}: "
            f"top level must be a mapping with 'version' and 'exceptions'"
        )
    # Top-level schema is exact: only 'version' and 'exceptions' are allowed.
    _TOP_LEVEL_ALLOWED = ("version", "exceptions")
    extra_top = sorted(str(k) for k in data.keys() if k not in _TOP_LEVEL_ALLOWED)
    if extra_top:
        raise GuardFatalError(
            f"{RULE_ID}: malformed exceptions policy {allowlist_path}: "
            f"unknown top-level key(s): {', '.join(extra_top)} — "
            f"allowed keys: version, exceptions"
        )
    version = data.get("version")
    # Version semantics are strict: the policy must declare the integer
    # SUPPORTED_VERSION. bool is rejected explicitly because ``True == 1``
    # in Python, so ``version: true`` would otherwise pass as "1".
    if isinstance(version, bool) or not isinstance(version, int):
        raise GuardFatalError(
            f"{RULE_ID}: malformed exceptions policy {allowlist_path}: "
            f"version must be the integer {SUPPORTED_VERSION} (got {version!r})"
        )
    if version != SUPPORTED_VERSION:
        raise GuardFatalError(
            f"{RULE_ID}: unsupported exceptions policy version {version!r} "
            f"in {allowlist_path} (expected {SUPPORTED_VERSION})"
        )
    raw_exceptions = data.get("exceptions")
    if not isinstance(raw_exceptions, list):
        raise GuardFatalError(
            f"{RULE_ID}: malformed exceptions policy {allowlist_path}: "
            f"'exceptions' must be a list"
        )

    entries: List[Dict[str, str]] = []
    for idx, raw in enumerate(raw_exceptions):
        if not isinstance(raw, dict):
            raise GuardFatalError(
                f"{RULE_ID}: malformed exceptions policy {allowlist_path}: "
                f"entry #{idx} is not a mapping"
            )
        extra = sorted(str(k) for k in raw.keys() if k not in _ALLOWED_EXCEPTION_FIELDS)
        if extra:
            raise GuardFatalError(
                f"{RULE_ID}: malformed exceptions policy {allowlist_path}: "
                f"entry #{idx} has unknown field(s): {', '.join(extra)} — "
                f"allowed fields: {', '.join(_REQUIRED_EXCEPTION_FIELDS)}"
            )
        missing = [f for f in _REQUIRED_EXCEPTION_FIELDS if not raw.get(f)]
        if missing:
            raise GuardFatalError(
                f"{RULE_ID}: malformed exceptions policy {allowlist_path}: "
                f"entry #{idx} missing required field(s): {', '.join(missing)}"
            )
        entry = {f: str(raw.get(f, "")).strip() for f in _REQUIRED_EXCEPTION_FIELDS}
        for field in ("path", "class", "method", "api"):
            if not entry[field]:
                raise GuardFatalError(
                    f"{RULE_ID}: malformed exceptions policy {allowlist_path}: "
                    f"entry #{idx} has empty '{field}'"
                )
            if _contains_wildcard(entry[field]):
                raise GuardFatalError(
                    f"{RULE_ID}: wildcard exception rejected in {allowlist_path}: "
                    f"entry #{idx} '{field}' = {entry[field]!r} — exact entries only"
                )
        # The ambiguous expression-body marker is reserved: an exact exception
        # must name a real method, so an unproven expression-body scope can
        # never be authorized (fail closed).
        if entry["method"] == _AMBIGUOUS_METHOD:
            raise GuardFatalError(
                f"{RULE_ID}: reserved ambiguous attribution rejected in {allowlist_path}: "
                f"entry #{idx} method = {_AMBIGUOUS_METHOD!r} — an exact exception must "
                f"name a real method, not the expression-body marker"
            )
        # Canonical policy paths use forward slashes only. Reject backslashes
        # BEFORE any normalization: a Windows-style path must never be silently
        # rewritten into a valid canonical path (fail closed).
        path = entry["path"]
        if "\\" in path:
            raise GuardFatalError(
                f"{RULE_ID}: non-canonical exception path rejected in {allowlist_path}: "
                f"entry #{idx} path = {entry['path']!r} — repository paths must use "
                f"forward slashes; backslash-separated paths are not normalized"
            )
        if not _is_canonical_path(path):
            raise GuardFatalError(
                f"{RULE_ID}: non-canonical exception path rejected in {allowlist_path}: "
                f"entry #{idx} path = {entry['path']!r} — expected a repository-relative "
                f"'app/src/main/java/...' source path (forward slashes, no ./ or ../)"
            )
        entry["path"] = path
        entries.append(entry)
    return entries


# ── Scanner ─────────────────────────────────────────────────────────────────────

def _attribution(line: str, class_stack: List[Tuple[str, int, List[Tuple[str, int]]]],
                 pending_class_stack: List[Tuple[str, int]],
                 pending_method_stack: List[Tuple[str, int]],
                 method_stack: List[Tuple[str, int]],
                 expr_marker_stack: List[Tuple[str, int]],
                 depth: int, paren_depth_after: int) -> Tuple[bool, bool, bool]:
    """Update declaration stacks from one masked line.

    Returns (class_declared, method_declared, expr_body_pushed) so callers
    know whether a declaration was pushed on this exact line (for single-line
    expression bodies) and whether the pushed declaration is an unprovable
    expression body (tracked as a controlled ambiguous marker instead of a
    real method name).

    Classes and block-bodied functions with multiline headers are kept
    ``pending`` until their body ``{`` opens (see ``_line_opens_body``); a
    pending declaration that is overtaken by a new declaration at the same or
    shallower depth was body-less and is dropped instead of being promoted.
    """
    class_declared = False
    method_declared = False
    expr_body_pushed = False

    def _clear_expr_markers() -> None:
        # A new declaration at this depth ends any earlier unproven expression
        # body that was declared at the same or a deeper depth.
        while expr_marker_stack and expr_marker_stack[-1][1] >= depth:
            expr_marker_stack.pop()

    def _drop_stale_pendings() -> None:
        # A new declaration at this depth proves any still-pending class/method
        # header at the same or a deeper depth can never open its body — it was
        # a body-less declaration (e.g. `data class Foo(...)` without a body),
        # not a multiline header.
        while pending_class_stack and pending_class_stack[-1][1] >= depth:
            pending_class_stack.pop()
        while pending_method_stack and pending_method_stack[-1][1] >= depth:
            pending_method_stack.pop()

    m = _COMPANION_RE.search(line)
    if m:
        _clear_expr_markers()
        _drop_stale_pendings()
        name = m.group(1)
        if name:
            class_stack.append((name, depth + 1, list(method_stack)))
        else:
            base = class_stack[-1][0] if class_stack else "<file>"
            class_stack.append((f"{base}.Companion", depth + 1, list(method_stack)))
        method_stack.clear()
        class_declared = True
    else:
        m = _CLASS_DECL_RE.search(line)
        if m:
            _clear_expr_markers()
            _drop_stale_pendings()
            pending_class_stack.append((m.group(1), depth))
            class_declared = True

    m = _FUN_DECL_RE.search(line)
    if m:
        if method_stack:
            # A function declared inside an open method body is a local
            # function. It is attributed to the enclosing method so an exact
            # exception for that method covers its whole implementation
            # (including local helpers). No new attribution scope is created.
            method_declared = False
        else:
            _clear_expr_markers()
            _drop_stale_pendings()
            if "=" in line and not _expression_body_provable_on_line(line, paren_depth_after):
                # Expression-bodied function whose boundary cannot be proven on the
                # declaration line — fail closed by attributing later occurrences
                # to the controlled ambiguous marker instead of carrying the
                # previous method name (which would let an exact exception for that
                # method suppress unrelated later/ambiguous occurrences).
                expr_marker_stack.append((_AMBIGUOUS_METHOD, depth))
                expr_body_pushed = True
            elif "=" in line:
                # Provable single-line expression body: active only on this
                # declaration line and closed by the caller's end-of-line logic.
                method_stack.append((m.group(1), depth))
            else:
                # Block-bodied function. The body `{` may be on a later line
                # (multiline parameter list); keep it pending until the body
                # brace opens.
                pending_method_stack.append((m.group(1), depth))
            method_declared = True
    return class_declared, method_declared, expr_body_pushed


def _is_declaration_context(masked: str, start: int) -> bool:
    """True if ``Date(`` at ``start`` is a type declaration, not a call."""
    before = masked[max(0, start - 32):start]
    return bool(_DECLARATION_PREFIX_RE.search(before))


def _date_constructor_has_empty_args(
    lines: List[str], line_index: int, open_paren_end: int
) -> bool:
    """True when a ``Date(`` match has an empty constructor argument list.

    Only the no-arg ``Date()`` constructor reads the wall clock;
    ``Date(0L)``, ``Date(epochMillis)``, or ``Date(timeProvider.now())``
    convert an already-known epoch and are not flagged. ``open_paren_end``
    is the match end (just past the ``(``) on ``lines[line_index - 1]``.

    The argument list may span masked lines (``Date(\n)`` closes on the
    following line): scanning starts just past the ``(`` and continues
    across the following lines tracking paren balance until the matching
    ``)``. The list is empty only when the balanced argument content is
    empty or whitespace; any non-whitespace token (``Date(0L)``,
    ``Date(\n    epochMillis\n)``, ...) is a conversion, not a read.
    """
    depth = 1  # the opening '(' just matched by the Date( pattern
    saw_argument_text = False
    for offset, line in enumerate(lines[line_index - 1:], start=0):
        start = open_paren_end if offset == 0 else 0
        for ch in line[start:]:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return not saw_argument_text
            elif not ch.isspace():
                saw_argument_text = True
    # No balanced ')' was found — cannot prove an empty argument list.
    return False


def scan_content(content: str, rel_path: str) -> List[TimeViolation]:
    """Scan one file's content and return detected violations.

    Raises GuardFatalError if attribution becomes impossible (parser error).
    """
    try:
        masked = mask_kotlin(content)
    except Exception as exc:  # defensive: masking must never pass silently
        raise GuardFatalError(
            f"{RULE_ID}: parser error masking {rel_path}: {exc}"
        )

    lines = masked.split("\n")
    # Active classes: (name, depth inside the class body, snapshot of the
    # method stack taken when the class body opened). The snapshot lets the
    # scanner restore the outer method attribution after a nested class closes.
    class_stack: List[Tuple[str, int, List[Tuple[str, int]]]] = []
    # Declared classes/functions whose body `{` has not opened yet (multiline
    # constructor/interface/parameter headers). A pending entry is promoted to
    # its active stack when the body brace opens and is dropped when a new
    # declaration proves it was a body-less declaration.
    pending_class_stack: List[Tuple[str, int]] = []
    pending_method_stack: List[Tuple[str, int]] = []
    method_stack: List[Tuple[str, int]] = []
    # Expression-bodied functions whose boundary could not be proven on the
    # declaration line. While non-empty, its top entry defines attribution:
    # API occurrences are attributed to the controlled ambiguous marker so a
    # stale previous method name can never be carried forward or suppress them.
    expr_marker_stack: List[Tuple[str, int]] = []
    paren_depth = 0
    depth = 0
    found: Dict[Tuple[int, str], TimeViolation] = {}

    for idx, line in enumerate(lines, start=1):
        # Pop scopes closed on the previous line. Classes pop first so their
        # snapshot can restore the outer method attribution before the method
        # pop runs.
        while class_stack and class_stack[-1][1] > depth:
            _, _, saved_methods = class_stack.pop()
            method_stack[:] = saved_methods
        while method_stack and method_stack[-1][1] > depth:
            method_stack.pop()
        # An unproven expression body's enclosing scope has closed.
        while expr_marker_stack and expr_marker_stack[-1][1] > depth:
            expr_marker_stack.pop()
        # A pending declaration whose enclosing scope closed can never open.
        while pending_class_stack and pending_class_stack[-1][1] > depth:
            pending_class_stack.pop()
        while pending_method_stack and pending_method_stack[-1][1] > depth:
            pending_method_stack.pop()

        # Count parens first so expression-body attribution can see the
        # end-of-line balanced state.
        depth_delta = line.count("{") - line.count("}")
        paren_delta = line.count("(") - line.count(")")
        paren_depth_after = paren_depth + paren_delta

        class_declared, method_declared, expr_body_pushed = _attribution(
            line, class_stack, pending_class_stack, pending_method_stack,
            method_stack, expr_marker_stack, depth, paren_depth_after
        )

        # Promote a pending class/method whose body `{` opens on this line.
        if pending_class_stack and _line_opens_body(line, pending_class_stack[-1][1], depth):
            name, enc = pending_class_stack.pop()
            class_stack.append((name, enc + 1, list(method_stack)))
            method_stack.clear()
        if pending_method_stack and _line_opens_body(line, pending_method_stack[-1][1], depth):
            name, enc = pending_method_stack.pop()
            method_stack.append((name, enc + 1))

        class_name = class_stack[-1][0] if class_stack else None
        if expr_marker_stack:
            method_name = expr_marker_stack[-1][0]
        else:
            method_name = method_stack[-1][0] if method_stack else None

        for pattern, api, message in _API_PATTERNS:
            for match in pattern.finditer(line):
                if api == "Date":
                    if _is_declaration_context(line, match.start()):
                        continue
                    # Only the no-arg Date() constructor is a wall-clock
                    # read; Date(millis) conversions of an existing epoch
                    # (Date(0L), Date(timeProvider.now()), ...) are not.
                    # The argument list may span masked lines, so the empty
                    # check scans forward to the balanced ')'.
                    if not _date_constructor_has_empty_args(lines, idx, match.end()):
                        continue
                key = (idx, api)
                if key in found:
                    continue
                found[key] = TimeViolation(
                    path=rel_path,
                    line=idx,
                    class_name=class_name or "",
                    method_name=method_name or "",
                    api=api,
                    message=message,
                )

        # Brace balance for scope tracking (strings/comments are already masked).
        depth += depth_delta
        paren_depth += paren_delta

        # Expression-body functions (fun ... = ...) end when parens balance.
        # A `=` in the line with balanced parens and no continuation means the
        # expression body completes on this line, so the method scope closes.
        if (
            method_declared
            and not expr_body_pushed
            and method_stack
            and paren_depth == 0
            and "=" in line
            and not re.search(r"=\s*$", line)
        ):
            method_stack.pop()

    violations = sorted(found.values(), key=lambda v: (v.line, v.api))
    return violations


def scan_file(file_path: Path, rel_path: str) -> List[TimeViolation]:
    """Read and scan one Kotlin file. Raises GuardFatalError on read failure."""
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as exc:
        raise GuardFatalError(f"{RULE_ID}: unreadable source file {rel_path}: {exc}")
    return scan_content(content, rel_path)


# ── Main ────────────────────────────────────────────────────────────────────────

def main() -> int:
    parser = argparse.ArgumentParser(
        description="Canonical direct wall-clock time boundary guard (G-TIME-01)"
    )
    parser.add_argument("--root", default=".", help="Project root directory")
    parser.add_argument(
        "--allowlist",
        default=DEFAULT_ALLOWLIST,
        help=f"Exceptions policy file (default: {DEFAULT_ALLOWLIST})",
    )
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Exit 1 when violations are found (fail closed in CI)",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()

    # PR-GR-10B: resolve the production source scope from the checked-in
    # manifest (fail closed — no conventional-root fallback).  Any
    # diagnostic (manifest absent/malformed/undeclared/topology) is fatal.
    root_set, scope_diagnostics = resolve_production_source_scope(str(root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        raise GuardFatalError(
            f"{RULE_ID}: production source scope unresolved: {codes} — fail closed"
        )

    allowlist_path = Path(args.allowlist)
    if not allowlist_path.is_absolute():
        allowlist_path = root / allowlist_path

    exceptions = load_exceptions(allowlist_path)

    # Deterministic declared-root-order then canonical path-order
    # enumeration (identical to the previous sorted rglob over the single
    # declared root ``app/src/main/java``).
    try:
        sources = list(iter_production_kotlin_files(str(root), root_set))
    except ProductionSourceScopeError as exc:
        raise GuardFatalError(
            f"{RULE_ID}: production source enumeration failed: {exc.code} — fail closed"
        )
    kotlin_files = [(Path(s.absolute_path), s.repository_relative_path) for s in sources]
    if not kotlin_files:
        raise GuardFatalError(
            f"{RULE_ID}: no Kotlin sources found under the declared "
            f"production source roots — fail closed"
        )

    all_violations: List[TimeViolation] = []
    raw_match_keys: Set[Tuple[str, str, str, str]] = set()
    for file_path, rel_path in kotlin_files:
        violations = scan_file(file_path, rel_path)
        for v in violations:
            raw_match_keys.add(v.key_tuple())
        all_violations.extend(violations)

    # Stale-exception validation: every exception must match real source
    # evidence, otherwise the policy is out of sync (exit 2).
    exception_keys = {(e["path"], e["class"], e["method"], e["api"]) for e in exceptions}
    stale = sorted(exception_keys - raw_match_keys)
    if stale:
        lines = "\n".join(f"  {path} class={cls} method={meth} api={api}" for path, cls, meth, api in stale)
        raise GuardFatalError(
            f"{RULE_ID}: stale exception(s) without matching source evidence:\n{lines}"
        )

    # Suppress violations that have an exact exception.
    reported = [v for v in all_violations if v.key_tuple() not in exception_keys]

    # Deterministic output: files already sorted; sort violations by
    # (path, line, api).
    reported.sort(key=lambda v: (v.path, v.line, v.api))
    for v in reported:
        print(v.format())

    print(f"{RULE_ID} SCAN root={root} files={len(kotlin_files)} violations={len(reported)}")

    if reported:
        if args.fail_on_violation:
            print(f"{RULE_ID} FAIL: {len(reported)} direct time boundary violation(s) — "
                  f"route through TimeProvider or add an exact exception entry.",
                  file=sys.stderr)
            return 1
        print(f"{RULE_ID} WARNING: {len(reported)} violation(s) "
              f"(--fail-on-violation not set)", file=sys.stderr)
        return 0

    print(f"{RULE_ID} PASS: no direct wall-clock time boundary violations")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except GuardFatalError as exc:
        print(f"FATAL {exc}", file=sys.stderr)
        sys.exit(2)
