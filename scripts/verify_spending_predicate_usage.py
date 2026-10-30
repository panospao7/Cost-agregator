#!/usr/bin/env python3
"""
Spending predicate usage guard (G-SPENDING-PREDICATE-01..03).

Loan-installments slice S4d, invariants INV-1 and INV-8 (plan amendment A12.6,
docs/features/loan-installments/domain-spec.md): once a purchase row can carry a
spending-exclusion flag (INSTALLMENT_PURCHASE / REVERSED), every DAO aggregate
over the ``expenses`` table must either use the single canonical SQL spending
predicate / effective-amount owner (``SpendingInclusionSql``) or be explicitly
classified as exempt with a bounded reason.  Without the guard a new aggregate
would silently count the flagged purchase twice (once as the purchase, once as
its installments).

Surface: every production Kotlin file whose repository-relative path matches
``.../data/database/dao/<File>.kt`` (enumerated through the checked-in manifest
``config/guards/production_source_roots.yml`` via
``scripts/guardrails/production_source_scope.py``).  Inside those files every
``@Query`` annotation is parsed (single-line, triple-quoted and concatenated
string literals; ``${...}`` templates are kept as text so a qualified reference
stays visible; comments are ignored).

A query is "in scope" for Rule 01 when its SQL names the ``expenses`` table in a
FROM or JOIN clause and uses one of the aggregate functions SUM, AVG, TOTAL,
COUNT, MIN or MAX.

  G-SPENDING-PREDICATE-01  an in-scope query that neither references a
                           qualified ``SpendingInclusionSql.<X>`` with X in
                           {SPENDING_PREDICATE, SPENDING_PREDICATE_E,
                           EFFECTIVE_AMOUNT, EFFECTIVE_AMOUNT_E} nor carries an
                           exemption marker.  An unqualified member (for example
                           ``${SPENDING_PREDICATE}``) is not accepted.
  G-SPENDING-PREDICATE-02  marker hygiene.  The marker is a Kotlin line comment
                           ``// spending-exempt: <REASON>`` that stands alone on
                           a line directly above the ``@Query`` (only other
                           annotation lines or line comments may sit between).
                           Violations: unknown REASON, malformed marker, marker
                           without a following ``@Query``, more than one marker
                           for one query, or a marked query that also references
                           ``SpendingInclusionSql`` (exempt and converted at the
                           same time is contradictory), or a stale marker (a
                           non-DEDUPE marker above a query that is not in
                           scope, i.e. does not aggregate over ``expenses``;
                           the marker would no longer classify anything).
                           DEDUPE_BLOCKING / DEDUPE_RESOLVER markers are
                           exempt from the stale check: they are placed on
                           every dedupe-family query, aggregating or not, so
                           that Rule 03 protects the whole family (A12.6).
                           REASON is one of DEDUPE_BLOCKING, DEDUPE_RESOLVER,
                           NON_SPENDING_INCOME, ROW_COUNT, NON_MONEY,
                           SUGGESTION, ASSET_VALUE.
  G-SPENDING-PREDICATE-03  INV-8: a query marked DEDUPE_BLOCKING or
                           DEDUPE_RESOLVER that references
                           ``SpendingInclusionSql`` (the dedupe families must
                           keep seeing flagged and not-mine rows).

There is no allowlist and no baseline.  Adding a new aggregate over ``expenses``
requires a conscious choice: use the predicate, or add a marker with a reason.

Report mode (``--report``): additionally prints one ``REPORT`` line per
classified query (every marked query, plus every in-scope query classified as
``predicate`` or ``UNCLASSIFIED``) and a bounded summary, sorted by path and
line, so the exemption set can be compared with the recorded set.  Report mode
does not change the exit code.

Scope limits (documented, not silent): comma joins (``FROM a, expenses``) are
not recognised as naming ``expenses``; ``@RawQuery`` builders and Kotlin-side
readers are outside this guard (Kotlin readers use the Expense effectiveAmount
member; raw builders are covered by the ComparableHomeValue parity tests).
``${CONST}`` templates are kept as text and never resolved, so an aggregate or
a ``FROM expenses`` that lives only inside an interpolated constant, and an
``@Query(CONST)`` whose argument is a constant instead of a literal, are
invisible to the guard (zero such cases in the current tree).

Exit codes (universal mapping): 0 pass, 1 violation, 2 infrastructure
(unresolved production source scope, unreadable or unparseable source file,
empty DAO surface, no ``@Query`` parsed).  ``--fail-on-violation`` is accepted
for canonical argv compatibility; a violation always fails.

Output contract: ``<path>:<line> [<rule id>] <function> <code>`` per violation.
Only the file path, line number, function identifier and a fixed code are
printed; SQL text, marker text and file content are never echoed.

Purity contract: library functions never call ``sys.exit``; only the CLI
adapter exits.
"""

import argparse
import bisect
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

EXIT_PASS = 0
EXIT_VIOLATION = 1
EXIT_INFRA = 2

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
    resolve_source_root_set_for_test_fixtures,
)

RULE_UNCOVERED = "G-SPENDING-PREDICATE-01"
RULE_MARKER = "G-SPENDING-PREDICATE-02"
RULE_DEDUPE = "G-SPENDING-PREDICATE-03"

ALLOWED_REASONS: Tuple[str, ...] = (
    "DEDUPE_BLOCKING",
    "DEDUPE_RESOLVER",
    "NON_SPENDING_INCOME",
    "ROW_COUNT",
    "NON_MONEY",
    "SUGGESTION",
    "ASSET_VALUE",
)
DEDUPE_REASONS = frozenset({"DEDUPE_BLOCKING", "DEDUPE_RESOLVER"})

ACCEPTED_CONSTANTS: Tuple[str, ...] = (
    "SPENDING_PREDICATE",
    "SPENDING_PREDICATE_E",
    "EFFECTIVE_AMOUNT",
    "EFFECTIVE_AMOUNT_E",
)

# Bounded violation codes (fixed vocabulary, never source text).
CODE_UNCOVERED = "AGGREGATE_WITHOUT_PREDICATE_OR_MARKER"
CODE_UNKNOWN_REASON = "UNKNOWN_REASON"
CODE_MALFORMED = "MALFORMED_MARKER"
CODE_ORPHAN = "MARKER_WITHOUT_QUERY"
CODE_DUPLICATE = "DUPLICATE_MARKER"
CODE_MARKER_WITH_REFERENCE = "MARKER_WITH_PREDICATE_REFERENCE"
CODE_DEDUPE_REFERENCE = "DEDUPE_QUERY_REFERENCES_PREDICATE"
CODE_STALE_MARKER = "STALE_MARKER"

_DESCRIPTIONS = {
    CODE_UNCOVERED: (
        "aggregate over expenses has neither a qualified SpendingInclusionSql "
        "reference nor a spending-exempt marker"
    ),
    CODE_UNKNOWN_REASON: "spending-exempt marker has an unknown reason",
    CODE_MALFORMED: "malformed spending-exempt marker",
    CODE_ORPHAN: "spending-exempt marker is not directly above an @Query",
    CODE_DUPLICATE: "more than one spending-exempt marker for one @Query",
    CODE_MARKER_WITH_REFERENCE: (
        "spending-exempt query also references SpendingInclusionSql"
    ),
    CODE_DEDUPE_REFERENCE: (
        "dedupe-marked query references SpendingInclusionSql (INV-8)"
    ),
    CODE_STALE_MARKER: (
        "spending-exempt marker sits above a query that does not aggregate over expenses"
    ),
}

E_SURFACE_EMPTY = "E_SPENDING_PREDICATE_DAO_SURFACE_EMPTY"
E_NO_QUERIES = "E_SPENDING_PREDICATE_NO_QUERY_PARSED"
E_UNREADABLE = "E_SPENDING_PREDICATE_SOURCE_UNREADABLE"
E_UNPARSEABLE = "E_SPENDING_PREDICATE_SOURCE_UNPARSEABLE"
E_ROOT_MISSING = "E_SPENDING_PREDICATE_SOURCE_ROOT_MISSING"

_ASCII = re.ASCII

_DAO_PATH_RE = re.compile(r"(?:^|/)data/database/dao/[^/]+\.kt$", _ASCII)

_TABLE_RE = re.compile(
    r"\b(?:FROM|JOIN)\s+[`\"\[]?expenses(?![A-Za-z0-9_])", _ASCII | re.IGNORECASE
)
_AGGREGATE_RE = re.compile(
    r"(?<![A-Za-z0-9_])(?:SUM|AVG|TOTAL|COUNT|MIN|MAX)\s*\(", _ASCII | re.IGNORECASE
)
_QUALIFIED_REF_RE = re.compile(
    r"(?<![A-Za-z0-9_])SpendingInclusionSql\s*\.\s*(?:"
    + "|".join(ACCEPTED_CONSTANTS)
    + r")(?![A-Za-z0-9_])",
    _ASCII,
)
_ANY_REF_RE = re.compile(r"(?<![A-Za-z0-9_])SpendingInclusionSql(?![A-Za-z0-9_])", _ASCII)

_QUERY_ANNOTATION_RE = re.compile(r"@(?:[A-Za-z_]\w*\.)*Query(?![A-Za-z0-9_])", _ASCII)
_FUN_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_]\w*\.)*([A-Za-z_]\w*)", _ASCII)
_MARKER_RE = re.compile(r"^//\s*spending-exempt:\s*([A-Za-z0-9_]+)\s*$", _ASCII)
_MARKER_WORD = "spending-exempt"
_MAX_NAME_LENGTH = 80


class KotlinParseError(Exception):
    """Unterminated string, comment, character literal or annotation."""


@dataclass(frozen=True)
class Violation:
    path: str  # repository-relative, forward slashes
    line: int  # 1-based
    rule_id: str
    function: str
    code: str


@dataclass(frozen=True)
class Classified:
    path: str
    line: int  # 1-based line of the @Query annotation
    function: str
    classification: str  # "exempt:<REASON>" | "predicate" | "UNCLASSIFIED"


@dataclass
class ScanResult:
    violations: List[Violation]
    classified: List[Classified]
    queries_parsed: int


@dataclass
class ScanOutcome:
    violations: List[Violation]
    classified: List[Classified]
    files_scanned: int
    queries_parsed: int
    infra_error: Optional[str]


# ── Kotlin lexing helpers ──────────────────────────────────────────────────────


def _skip_block_comment(text: str, i: int) -> int:
    """``text[i:i+2] == '/*'``; supports nesting.  Returns the index after ``*/``."""
    depth = 0
    n = len(text)
    while i < n:
        if text.startswith("/*", i):
            depth += 1
            i += 2
        elif text.startswith("*/", i):
            depth -= 1
            i += 2
            if depth == 0:
                return i
        else:
            i += 1
    raise KotlinParseError("unterminated block comment")


def _skip_char_literal(text: str, i: int) -> int:
    """``text[i] == "'"``.  Returns the index after the closing quote."""
    start = i + 3 if text[i + 1 : i + 2] == "\\" else i + 2
    end = text.find("'", start)
    if end == -1 or end - i > 10:
        raise KotlinParseError("unterminated character literal")
    return end + 1


def _skip_template(text: str, i: int) -> int:
    """``text[i:i+2] == '${'``.  Returns the index after the matching ``}``."""
    n = len(text)
    depth = 1
    k = i + 2
    while k < n:
        c = text[k]
        if c == '"':
            _content, k = _read_string(text, k)
            continue
        if c == "'":
            k = _skip_char_literal(text, k)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return k + 1
        k += 1
    raise KotlinParseError("unterminated string template")


def _read_string(text: str, i: int) -> Tuple[str, int]:
    """``text[i] == '"'``.  Returns ``(raw content, index after the literal)``.

    The content is the source text between the delimiters, ``${...}`` templates
    included verbatim (so a qualified constant reference stays visible).
    """
    n = len(text)
    if text.startswith('"""', i):
        start = i + 3
        j = start
        while j < n:
            if text.startswith("${", j):
                j = _skip_template(text, j)
                continue
            if text.startswith('"""', j):
                while text[j + 3 : j + 4] == '"':
                    j += 1  # extra quotes belong to the content
                return text[start:j], j + 3
            j += 1
        raise KotlinParseError("unterminated raw string")
    start = i + 1
    j = start
    while j < n:
        c = text[j]
        if c == "\\":
            j += 2
            continue
        if text.startswith("${", j):
            j = _skip_template(text, j)
            continue
        if c == '"':
            return text[start:j], j + 1
        if c == "\n":
            break
        j += 1
    raise KotlinParseError("unterminated string literal")


def _skip_whitespace_and_comments(text: str, i: int) -> int:
    n = len(text)
    while i < n:
        if text[i] in " \t\r\n":
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end == -1 else end
        elif text.startswith("/*", i):
            i = _skip_block_comment(text, i)
        else:
            break
    return i


def _parse_query_arguments(text: str, open_paren: int) -> Tuple[str, int]:
    """Parse the argument list of an ``@Query(...)``.

    Returns ``(sql_view, index after the closing paren)``.  The SQL view is the
    concatenation of every string literal's raw content plus any code text
    between literals (so ``"a" + SpendingInclusionSql.X + "b"`` keeps the
    reference visible).  Comments are dropped.
    """
    n = len(text)
    depth = 1
    k = open_paren + 1
    parts: List[str] = []
    while k < n:
        c = text[k]
        if c == '"':
            content, k = _read_string(text, k)
            parts.append(content)
            continue
        if c == "'":
            k = _skip_char_literal(text, k)
            continue
        if text.startswith("//", k):
            end = text.find("\n", k)
            k = n if end == -1 else end
            continue
        if text.startswith("/*", k):
            k = _skip_block_comment(text, k)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return "".join(parts), k + 1
        if c != "+":  # concatenation operator: "FROM " + "expenses" must read as one clause
            parts.append(c)
        k += 1
    raise KotlinParseError("unterminated @Query annotation")


@dataclass
class _ParsedQuery:
    line: int
    start: int
    end: int
    sql: str
    function: str = "<unknown>"


@dataclass
class _LineComment:
    line: int
    standalone: bool
    text: str


def _lex(text: str) -> Tuple[List[_ParsedQuery], List[_LineComment]]:
    n = len(text)
    newline_offsets = [i for i, char in enumerate(text) if char == "\n"]

    def line_of(index: int) -> int:
        return bisect.bisect_left(newline_offsets, index) + 1

    queries: List[_ParsedQuery] = []
    comments: List[_LineComment] = []
    i = 0
    while i < n:
        c = text[i]
        if c == "/" and text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end == -1 else end
            line_start = text.rfind("\n", 0, i) + 1
            comments.append(
                _LineComment(
                    line_of(i),
                    text[line_start:i].strip() == "",
                    text[i:end].rstrip("\r"),
                )
            )
            i = end
            continue
        if c == "/" and text.startswith("/*", i):
            i = _skip_block_comment(text, i)
            continue
        if c == '"':
            _content, i = _read_string(text, i)
            continue
        if c == "'":
            i = _skip_char_literal(text, i)
            continue
        if c == "@":
            match = _QUERY_ANNOTATION_RE.match(text, i)
            if match is not None and (i == 0 or not re.match(r"[A-Za-z0-9_]", text[i - 1])):
                after = _skip_whitespace_and_comments(text, match.end())
                if after < n and text[after] == "(":
                    sql, end = _parse_query_arguments(text, after)
                    queries.append(_ParsedQuery(line_of(i), i, end, sql))
                    i = end
                    continue
            i += 1
            continue
        i += 1

    for index, query in enumerate(queries):
        limit = queries[index + 1].start if index + 1 < len(queries) else n
        found = _FUN_RE.search(text, query.end, limit)
        if found is not None:
            query.function = found.group(1)[:_MAX_NAME_LENGTH]
    return queries, comments


# ── Rules ──────────────────────────────────────────────────────────────────────


@dataclass
class _Marker:
    line: int
    reason: Optional[str]  # None when malformed
    text_ok: bool


def _marker_target(
    lines: List[str], marker_line: int, queries: List[_ParsedQuery]
) -> Optional[int]:
    """Index of the query this marker sits directly above, else ``None``."""
    for index, query in enumerate(queries):
        if query.line <= marker_line:
            continue
        for between in range(marker_line + 1, query.line):
            stripped = lines[between - 1].strip()
            if not (stripped.startswith("//") or stripped.startswith("@")):
                return None
        return index
    return None


def scan_text(relative_path: str, text: str) -> ScanResult:
    """Scan one DAO file's text (fixture-level API).

    Raises ``KotlinParseError`` on unparseable Kotlin; the CLI maps it to an
    infrastructure failure (fail closed).
    """
    queries, comments = _lex(text)
    lines = text.split("\n")
    violations: List[Violation] = []

    attached: Dict[int, List[_Marker]] = {}
    for comment in comments:
        if _MARKER_WORD not in comment.text:
            continue
        match = _MARKER_RE.match(comment.text.strip()) if comment.standalone else None
        if match is None:
            violations.append(Violation(relative_path, comment.line, RULE_MARKER, "-", CODE_MALFORMED))
            continue
        reason = match.group(1)
        target = _marker_target(lines, comment.line, queries)
        if target is None:
            violations.append(Violation(relative_path, comment.line, RULE_MARKER, "-", CODE_ORPHAN))
            continue
        attached.setdefault(target, []).append(
            _Marker(comment.line, reason, reason in ALLOWED_REASONS)
        )

    classified: List[Classified] = []
    for index, query in enumerate(queries):
        sql = query.sql
        refs_any = _ANY_REF_RE.search(sql) is not None
        refs_qualified = _QUALIFIED_REF_RE.search(sql) is not None
        in_scope = _TABLE_RE.search(sql) is not None and _AGGREGATE_RE.search(sql) is not None
        markers = attached.get(index, [])

        if len(markers) > 1:
            for extra in markers[1:]:
                violations.append(
                    Violation(relative_path, extra.line, RULE_MARKER, query.function, CODE_DUPLICATE)
                )
        first_valid: Optional[_Marker] = None
        for marker in markers:
            if marker.text_ok:
                first_valid = first_valid or marker
            else:
                violations.append(
                    Violation(relative_path, marker.line, RULE_MARKER, query.function, CODE_UNKNOWN_REASON)
                )

        if first_valid is not None:
            if refs_any:
                if first_valid.reason in DEDUPE_REASONS:
                    violations.append(
                        Violation(relative_path, query.line, RULE_DEDUPE, query.function, CODE_DEDUPE_REFERENCE)
                    )
                else:
                    violations.append(
                        Violation(relative_path, query.line, RULE_MARKER, query.function, CODE_MARKER_WITH_REFERENCE)
                    )
            if not in_scope and first_valid.reason not in DEDUPE_REASONS:
                # DEDUPE markers stay allowed on non-aggregating dedupe-family queries.
                violations.append(
                    Violation(relative_path, query.line, RULE_MARKER, query.function, CODE_STALE_MARKER)
                )
            classified.append(
                Classified(relative_path, query.line, query.function, "exempt:" + str(first_valid.reason))
            )
        elif markers:
            pass  # marker present but invalid; already reported under Rule 02
        elif in_scope and refs_qualified:
            classified.append(Classified(relative_path, query.line, query.function, "predicate"))
        elif in_scope:
            violations.append(
                Violation(relative_path, query.line, RULE_UNCOVERED, query.function, CODE_UNCOVERED)
            )
            classified.append(Classified(relative_path, query.line, query.function, "UNCLASSIFIED"))

    violations.sort(key=lambda v: (v.path, v.line, v.rule_id, v.code))
    return ScanResult(violations, classified, len(queries))


def is_dao_path(relative_path: str) -> bool:
    """True when the repository-relative path is a ``data/database/dao/*.kt`` file."""
    return _DAO_PATH_RE.search(relative_path.replace("\\", "/")) is not None


def _scan_declared_roots(repo_root: Path, root_set) -> ScanOutcome:
    violations: List[Violation] = []
    classified: List[Classified] = []
    files = 0
    queries = 0
    try:
        for source_file in iter_production_kotlin_files(str(repo_root), root_set):
            relative = source_file.repository_relative_path
            if not is_dao_path(relative):
                continue
            try:
                text = Path(source_file.absolute_path).read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                return ScanOutcome([], [], 0, 0, E_UNREADABLE)
            try:
                result = scan_text(relative, text)
            except KotlinParseError:
                return ScanOutcome([], [], 0, 0, E_UNPARSEABLE)
            files += 1
            queries += result.queries_parsed
            violations.extend(result.violations)
            classified.extend(result.classified)
    except ProductionSourceScopeError:
        return ScanOutcome([], [], 0, 0, E_UNREADABLE)
    if files == 0:
        return ScanOutcome([], [], 0, 0, E_SURFACE_EMPTY)
    if queries == 0:
        return ScanOutcome([], [], files, 0, E_NO_QUERIES)
    violations.sort(key=lambda v: (v.path, v.line, v.rule_id, v.code))
    classified.sort(key=lambda c: (c.path, c.line, c.function))
    return ScanOutcome(violations, classified, files, queries, None)


def scan_source_root(source_root: Path) -> ScanOutcome:
    """Scan ``<source_root>`` (fixture-level API, test-fixture scope seam)."""
    if not Path(source_root).is_dir():
        return ScanOutcome([], [], 0, 0, E_ROOT_MISSING)
    root_set, _diagnostics = resolve_source_root_set_for_test_fixtures(str(source_root))
    if root_set is None:
        return ScanOutcome([], [], 0, 0, E_ROOT_MISSING)
    return _scan_declared_roots(Path(source_root), root_set)


def format_report(outcome: ScanOutcome) -> List[str]:
    """Deterministic report lines for report mode (no SQL, no marker text)."""
    lines = [
        f"SPENDING PREDICATE REPORT: {item.path}:{item.line} {item.function} {item.classification}"
        for item in outcome.classified
    ]
    reasons: Dict[str, int] = {}
    predicate = 0
    unclassified = 0
    for item in outcome.classified:
        if item.classification.startswith("exempt:"):
            reason = item.classification.split(":", 1)[1]
            reasons[reason] = reasons.get(reason, 0) + 1
        elif item.classification == "predicate":
            predicate += 1
        else:
            unclassified += 1
    exempt_total = sum(reasons.values())
    lines.append(
        f"SPENDING PREDICATE REPORT SUMMARY: exempt={exempt_total} predicate={predicate} "
        f"unclassified={unclassified}"
    )
    for reason in sorted(reasons):
        lines.append(f"SPENDING PREDICATE REPORT REASON: {reason} {reasons[reason]}")
    return lines


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Spending predicate usage guard (G-SPENDING-PREDICATE-01..03)."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="Repository root directory (default: one level up from this script).",
    )
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Accepted for canonical argv compatibility; violations always fail.",
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help="Also list every classified query (exempt / predicate / unclassified).",
    )
    args = parser.parse_args(argv)

    root_set, scope_diagnostics = resolve_production_source_scope(str(args.root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"SPENDING PREDICATE USAGE: infrastructure error "
            f"(E_SPENDING_PREDICATE_SOURCE_SCOPE_UNRESOLVED: {codes})",
            file=sys.stderr,
        )
        return EXIT_INFRA

    outcome = _scan_declared_roots(args.root, root_set)
    if outcome.infra_error is not None:
        print(
            f"SPENDING PREDICATE USAGE: infrastructure error ({outcome.infra_error})",
            file=sys.stderr,
        )
        return EXIT_INFRA

    if args.report:
        for line in format_report(outcome):
            print(line)

    if outcome.violations:
        for violation in outcome.violations:
            print(
                f"SPENDING PREDICATE USAGE: {violation.path}:{violation.line} "
                f"[{violation.rule_id}] {violation.function} {violation.code}: "
                f"{_DESCRIPTIONS[violation.code]}"
            )
        print(
            f"FAIL: {len(outcome.violations)} spending predicate usage violation(s) "
            f"(G-SPENDING-PREDICATE-01..03). Aggregates over expenses must use "
            f"SpendingInclusionSql or carry a '// spending-exempt: <REASON>' marker (INV-1, INV-8)."
        )
        return EXIT_VIOLATION

    exempt = sum(1 for item in outcome.classified if item.classification.startswith("exempt:"))
    predicate = sum(1 for item in outcome.classified if item.classification == "predicate")
    print(
        f"PASS: {outcome.files_scanned} DAO file(s), {outcome.queries_parsed} @Query parsed, "
        f"{predicate} predicate-referencing aggregate(s), {exempt} exempt "
        f"(G-SPENDING-PREDICATE-01..03)."
    )
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main())
