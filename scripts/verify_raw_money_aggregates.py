#!/usr/bin/env python3
"""
Raw money aggregate boundary guard (G-MONEY-RAW-01..07).

PR-GR-10A Slice 3 — EXTRACTED_AND_REGISTERED disposition for the retired
Gradle KTS inline scanner ``checkRawMoneyAggregates`` (app/build.gradle.kts,
PR-E23).  The KTS task is retired by the PR-GR-10A command-authority matrix
(docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md); this registered guard owns the
identical rule family under stable rule IDs.

The rules are transcribed 1:1 from the KTS implementation — none was added,
removed, widened, or narrowed by this extraction:

  G-MONEY-RAW-01   .sumOf { it.amount }
  G-MONEY-RAW-02   .sumOf { it.effectiveAmount }
  G-MONEY-RAW-03   .sumOf { it.normalizedAmount }
  G-MONEY-RAW-04   .sumOf { it.<identifier ending in price/Price> }
  G-MONEY-RAW-05   .sumBy { it.amount.toInt() | it.amount.roundToInt() }
  G-MONEY-RAW-06   total: Double
  G-MONEY-RAW-07   var total = 0.0 ... sum

Scanning semantics preserved from the KTS scanner:
  - scan scope = the declared production roots of the checked-in manifest
    ``config/guards/production_source_roots.yml`` (via
    ``scripts/guardrails/production_source_scope.py``; currently
    ``app/src/main/java``), ``*.kt`` files only; findings keep their
    scan-root-relative path spelling;
  - exact file-name allowlist (7 files);
  - test trees are skipped (the KTS scanner lower-cased the walked path and
    skipped any path containing ``test``/``androidtest``; this port applies
    the same containment test to the scan-root-relative path so the rule is
    independent of the checkout location);
  - lines inside a ``fromBuckets { ... }`` block are skipped (brace-depth
    state machine, faithful port including the triggering line);
  - ``import``/comment lines are skipped;
  - patterns match against the trimmed line, ASCII word semantics (the JVM
    regex ``\\w`` behaviour the KTS scanner relied on).

Exit codes (universal mapping): 0 pass, 1 violation, 2 infrastructure
(missing/unreadable production source scope or source file — including a
missing, malformed, or undeclared source-root manifest; there is NO
conventional-root fallback at repository level).  Never creates or updates
a baseline.  ``--fail-on-violation`` is accepted (and is the only mode): a
violation always fails.

Purity contract: library functions never call ``sys.exit`` — they return
exit codes; only the CLI adapter exits.
"""

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

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

# Exact file-name allowlist, transcribed from the KTS scanner.
ALLOWLIST_FILES = frozenset({
    "MoneyAggregateBuilder.kt",
    "MoneyAggregate.kt",
    "ConvertedMoney.kt",
    "CurrencyConverter.kt",
    "MultiCurrencyRepository.kt",
    "ExpenseDao.kt",
    "BudgetDao.kt",
})


@dataclass(frozen=True)
class RawMoneyRule:
    """One stable-ID raw-aggregate rule (pattern transcribed from the KTS)."""

    rule_id: str
    pattern: "re.Pattern"
    description: str


# ASCII flag keeps ``\\w`` semantics identical to the JVM regex the KTS
# scanner relied on (Python's default ``\\w`` is Unicode-aware).
_ASCII = re.ASCII

RULES: Tuple[RawMoneyRule, ...] = (
    RawMoneyRule(
        "G-MONEY-RAW-01",
        re.compile(r"\.sumOf\s*\{\s*it\.amount\s*\}", _ASCII),
        "Raw sumOf { it.amount } aggregate — route through MoneyAggregate",
    ),
    RawMoneyRule(
        "G-MONEY-RAW-02",
        re.compile(r"\.sumOf\s*\{\s*it\.effectiveAmount\s*\}", _ASCII),
        "Raw sumOf { it.effectiveAmount } aggregate — route through MoneyAggregate",
    ),
    RawMoneyRule(
        "G-MONEY-RAW-03",
        re.compile(r"\.sumOf\s*\{\s*it\.normalizedAmount\s*\}", _ASCII),
        "Raw sumOf { it.normalizedAmount } aggregate — route through MoneyAggregate",
    ),
    RawMoneyRule(
        "G-MONEY-RAW-04",
        re.compile(r"\.sumOf\s*\{\s*it\.\w*[Pp]rice\s*\}", _ASCII),
        "Raw sumOf { it.*price } aggregate — route through MoneyAggregate",
    ),
    RawMoneyRule(
        "G-MONEY-RAW-05",
        re.compile(
            r"\.sumBy\s*\{\s*it\.amount\s*\.(?:toInt|roundToInt)\s*\(\)\s*\}",
            _ASCII,
        ),
        "Raw sumBy { it.amount.toInt()/roundToInt() } aggregate — route through MoneyAggregate",
    ),
    RawMoneyRule(
        "G-MONEY-RAW-06",
        re.compile(r"total\s*:\s*Double", _ASCII),
        "Raw `total: Double` declaration — use a normalized money type",
    ),
    RawMoneyRule(
        "G-MONEY-RAW-07",
        re.compile(r"var\s+total\s*=\s*0\.0\s*;?\s*//?\s*.*sum", _ASCII),
        "Raw mutable `var total = 0.0` sum accumulator — use MoneyAggregate",
    ),
)


@dataclass(frozen=True)
class Violation:
    path: str  # scan-root-relative, forward slashes
    line: int  # 1-based
    rule_id: str
    description: str


def _is_test_path(relative_path: str) -> bool:
    """True when the scan-root-relative path looks like a test tree.

    Faithful port of the KTS ``filePathLower.contains("test") ||
    filePathLower.contains("androidtest")`` skip, applied to the
    scan-root-relative spelling so the rule cannot depend on where the
    repository is checked out.
    """
    lowered = relative_path.replace("\\", "/").lower()
    return "test" in lowered


def _scan_lines(
    relative_path: str,
    lines: List[str],
) -> List[Violation]:
    """Scan one file's lines with the faithful KTS line state machine."""
    violations: List[Violation] = []
    in_from_buckets = False
    bracket_depth = 0
    for line_num, line in enumerate(lines, start=1):
        stripped = line.strip()
        if "fromBuckets" in stripped and "{" in stripped:
            in_from_buckets = True
            bracket_depth = 0
        if in_from_buckets:
            bracket_depth += stripped.count("{") - stripped.count("}")
            if bracket_depth <= 0:
                in_from_buckets = False
                bracket_depth = 0
            continue
        if (
            stripped.startswith("import ")
            or stripped.startswith("//")
            or stripped.startswith("*")
            or stripped.startswith("/*")
        ):
            continue
        for rule in RULES:
            if rule.pattern.search(stripped):
                violations.append(
                    Violation(
                        path=relative_path,
                        line=line_num,
                        rule_id=rule.rule_id,
                        description=rule.description,
                    )
                )
    return violations


def _scan_declared_roots(repo_root: Path, root_set) -> Tuple[List[Violation], Optional[str]]:
    """Scan the declared production source roots for raw money aggregates.

    Enumerates every declared production Kotlin file (deterministic
    root-order then canonical path-order) and keeps the historical
    scan-root-relative finding-path spelling.  Returns
    ``(violations, infra_error)``.  ``infra_error`` is a bounded controlled
    string (never an exception message or filesystem path) when the run is
    an infrastructure failure (exit 2).
    """
    violations: List[Violation] = []
    try:
        for source_file in iter_production_kotlin_files(str(repo_root), root_set):
            relative = (
                Path(source_file.absolute_path)
                .relative_to(Path(source_file.root_path))
                .as_posix()
            )
            if Path(source_file.absolute_path).name in ALLOWLIST_FILES:
                continue
            if _is_test_path(relative):
                continue
            try:
                text = Path(source_file.absolute_path).read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                return [], "E_RAW_MONEY_SOURCE_UNREADABLE"
            violations.extend(_scan_lines(relative, text.splitlines()))
    except ProductionSourceScopeError:
        return [], "E_RAW_MONEY_SOURCE_UNREADABLE"
    return violations, None


def scan_source_root(source_root: Path) -> Tuple[List[Violation], Optional[str]]:
    """Scan ``<source_root>`` for raw money aggregates (fixture-level API).

    PR-GR-10B: repository-level invocations go through ``main()`` (manifest
    required, fail closed).  This direct entry point resolves the scope via
    the explicitly named TEST-FIXTURE seam so synthetic repositories without
    a manifest keep working; the conventional-root fallback must never be
    reached by a repository-level guard, suite, ratchet, or Gradle task.

    Returns ``(violations, infra_error)``.  ``infra_error`` is a bounded
    controlled string (never an exception message or filesystem path) when
    the run is an infrastructure failure (exit 2).
    """
    if not Path(source_root).is_dir():
        return [], "E_RAW_MONEY_SOURCE_ROOT_MISSING"
    root_set, _diagnostics = resolve_source_root_set_for_test_fixtures(
        str(source_root)
    )
    if root_set is None:
        return [], "E_RAW_MONEY_SOURCE_ROOT_MISSING"
    return _scan_declared_roots(Path(source_root), root_set)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Raw money aggregate boundary guard (G-MONEY-RAW-01..07)."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="Repository root directory (default: two levels up from this script).",
    )
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Accepted for canonical argv compatibility; violations always fail.",
    )
    args = parser.parse_args(argv)

    # PR-GR-10B: resolve the production source scope from the checked-in
    # manifest (fail closed — no conventional-root fallback).
    root_set, scope_diagnostics = resolve_production_source_scope(str(args.root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"RAW MONEY AGGREGATE: infrastructure error "
            f"(E_RAW_MONEY_SOURCE_SCOPE_UNRESOLVED: {codes})",
            file=sys.stderr,
        )
        return EXIT_INFRA

    violations, infra_error = _scan_declared_roots(args.root, root_set)
    if infra_error is not None:
        print(f"RAW MONEY AGGREGATE: infrastructure error ({infra_error})", file=sys.stderr)
        return EXIT_INFRA

    if violations:
        for violation in violations:
            print(
                f"RAW MONEY AGGREGATE: {violation.path}:{violation.line} "
                f"[{violation.rule_id}] {violation.description}"
            )
        print(
            f"FAIL: {len(violations)} raw money aggregate violation(s) "
            f"(G-MONEY-RAW-01..07). Route totals through MoneyAggregate / "
            f"normalized money primitives."
        )
        return EXIT_VIOLATION

    print("PASS: no raw money aggregate violations found (G-MONEY-RAW-01..07).")
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main())
