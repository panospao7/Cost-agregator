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
  - scan root ``<root>/app/src/main/java``, ``*.kt`` files only;
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
(missing/unreadable source root or source file).  Never creates or updates
a baseline.  ``--fail-on-violation`` is accepted (and is the only mode): a
violation always fails.

Purity contract: library functions never call ``sys.exit`` — they return
exit codes; only the CLI adapter exits.
"""

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

EXIT_PASS = 0
EXIT_VIOLATION = 1
EXIT_INFRA = 2

SCAN_ROOT_SEGMENTS = ("app", "src", "main", "java")

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


def scan_source_root(source_root: Path) -> Tuple[List[Violation], Optional[str]]:
    """Scan ``<source_root>`` for raw money aggregates.

    Returns ``(violations, infra_error)``.  ``infra_error`` is a bounded
    controlled string (never an exception message or filesystem path) when
    the run is an infrastructure failure (exit 2).
    """
    if not source_root.is_dir():
        return [], "E_RAW_MONEY_SOURCE_ROOT_MISSING"
    violations: List[Violation] = []
    for file_path in sorted(source_root.rglob("*.kt")):
        relative = file_path.relative_to(source_root).as_posix()
        if file_path.name in ALLOWLIST_FILES:
            continue
        if _is_test_path(relative):
            continue
        try:
            text = file_path.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            return [], "E_RAW_MONEY_SOURCE_UNREADABLE"
        violations.extend(_scan_lines(relative, text.splitlines()))
    return violations, None


def resolve_scan_root(root: Path) -> Path:
    """Scan root for a repository root (mirrors verify_money_boundaries)."""
    return root.joinpath(*SCAN_ROOT_SEGMENTS)


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

    violations, infra_error = scan_source_root(resolve_scan_root(args.root))
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
