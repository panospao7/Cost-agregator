#!/usr/bin/env python3
"""
Static money-boundary guard v2 for ExpenseTracker.

CURR-70F-14: Comprehensive CI guard that catches regressions back to
raw mixed-currency math or hidden latest-rate fallback.

Rules:
  G-MONEY-01  No currencyConverter.convert(...) in aggregate/financial paths
  G-MONEY-02  No convertAsOf(...) ?: convert(...) hidden latest fallback
  G-MONEY-03  No convertMultiple(...) outside explicitly latest-rate methods
  G-MONEY-04  No homeCurrency().first() in financial math paths
  G-MONEY-05  No MoneyAggregate(...) without explicit rateBasis
  G-MONEY-06  No legacy fromBuckets(List<Pair<Double,String>>) outside latest wrappers
  G-MONEY-07  No Result<Double> or Map<..., Double> aggregate APIs in non-deprecated code
  G-MONEY-08  No raw sums (sumOf { it.amount }, sumOf { it.effectiveAmount }, ?: effectiveAmount, getOrDefault("EUR"))
"""

import argparse
import re
import sys
from pathlib import Path
from typing import List, Tuple, NamedTuple

class Violation(NamedTuple):
    line_num: int
    rule: str
    description: str
    line: str


# ── Financial math directories (aggregate/budget/forecast/cashflow/analytics) ──
FINANCIAL_DIRS = {
    'dashboard', 'budget', 'forecast', 'cashflow', 'analytics',
    'repository', 'usecase', 'forecasting', 'health', 'tax',
    'investment', 'savings', 'subscription', 'income',
}

def is_financial_path(file_path: str) -> bool:
    """True if file is in a financial math path (not row-display or UI)."""
    parts = file_path.replace('\\', '/').lower().split('/')
    return any(d in parts for d in FINANCIAL_DIRS)


# ── Rules ──────────────────────────────────────────────────────────────────────

RULES: List[Tuple[str, str, str, bool]] = [
    # (regex, rule_id, description, financial_only)

    # G-MONEY-01: direct convert() in financial paths
    (r'currencyConverter\.convert\(', 'G-MONEY-01',
     'Direct currencyConverter.convert() in financial path — use convertOutcome() or convertAsOf()', True),

    # G-MONEY-02: hidden latest fallback
    (r'convertAsOf\([^)]*\)\s*\?\s*:\s*\w*\.?convert\(', 'G-MONEY-02',
     'Hidden latest-rate fallback: convertAsOf() ?: convert()', False),

    # G-MONEY-03: convertMultiple in non-latest paths
    (r'convertMultiple\(', 'G-MONEY-03',
     'convertMultiple() uses latest rates — use MoneyNormalizationEngine for historical', True),

    # G-MONEY-04: homeCurrency().first() in financial math
    (r'homeCurrency\(\)\.first\(\)', 'G-MONEY-04',
     'Use resolveHomeCurrency() instead of homeCurrency().first() in financial math', True),

    # G-MONEY-05: MoneyAggregate constructor without rateBasis
    # Matches MoneyAggregate( without rateBasis on same or next few lines
    # Simplified: flag MoneyAggregate( that doesn't have rateBasis nearby
    (r'MoneyAggregate\(\s*$', 'G-MONEY-05',
     'MoneyAggregate() constructor — ensure rateBasis is explicitly set', False),

    # G-MONEY-06: legacy fromBuckets with Pair
    (r'fromBuckets\(\s*buckets\s*=.*Pair', 'G-MONEY-06',
     'Legacy fromBuckets(Pair) overload — use typed overload or MoneyNormalizationEngine', True),

    # G-MONEY-07: legacy Double aggregate APIs
    (r'Result<\s*Double\s*>', 'G-MONEY-07',
     'Legacy Result<Double> aggregate API — use MoneyAggregate', False),

    # G-MONEY-08: raw sums and fallbacks
    (r'sumOf\s*\{\s*it\.amount\s*\}', 'G-MONEY-08',
     'Raw sumOf { it.amount } without currency normalization', False),
    (r'sumOf\s*\{\s*it\.effectiveAmount\s*\}', 'G-MONEY-08',
     'Raw sumOf { it.effectiveAmount } without currency normalization', False),
    (r'\?\s*:\s*\w*\.?effectiveAmount', 'G-MONEY-08',
     'Raw effectiveAmount fallback on conversion failure', False),
    (r'\.getOrDefault\s*\(\s*"EUR"\s*\)', 'G-MONEY-08',
     'Silent EUR fallback via getOrDefault("EUR")', False),

    # G-MONEY-09: fake EUR in unavailable/failure containers
    (r'CurrencyCode\.EUR.*UNAVAILABLE|CurrencyCode\("EUR"\).*unavailable|currency\s*=\s*"EUR".*fail|currency\s*=\s*"EUR".*unknown',
     'G-MONEY-09',
     'Fake EUR in unavailable/failure container — use typed Unavailable result or empty string', True),

    # G-MONEY-10: raw ExpenseSnapshot amounts in synthesis/forecast without normalization
    (r'ExpenseSnapshot\(.*effectiveAmount\s*=\s*\w+\.effectiveAmount', 'G-MONEY-10',
     'Raw ExpenseSnapshot.effectiveAmount passed to synthesis — normalize first', True),
]

# ── Multiline rule: G-MONEY-02 across lines ────────────────────────────────────
MULTILINE_FALLBACK = re.compile(
    r'convertAsOf\([^)]*\)\s*\n\s*\?\s*:\s*\w*\.?convert\(',
    re.MULTILINE
)

# ── Allowlists ─────────────────────────────────────────────────────────────────

# Files completely excluded from scanning
EXCLUDED_FILES = {
    'CurrencyConverter.kt',          # defines convert/convertAsOf/convertMultiple
    'MoneyNormalizationEngine.kt',   # canonical normalizer
    'MoneyAggregateBuilder.kt',      # builder (has its own guards)
    'MoneyAggregate.kt',             # model definition
    'MoneyMappers.kt',               # mapping helpers
    'ExchangeRateStoreAdapter.kt',   # storage layer
    'AppDatabase.kt',                # migrations
}

# Path patterns excluded
EXCLUDED_PATH_PATTERNS = [
    r'/test/',
    r'/androidTest/',
    r'Test\.kt$',
    r'Fixture\.kt$',
    r'Fake\.kt$',
    r'Mock\.kt$',
]

# Line-level allowlist: if line contains any of these, skip
LINE_ALLOWLIST = [
    '@Deprecated',
    '// ALLOWLIST:',
    '// G-MONEY-ALLOW',
    'formatDisplay',
    'formatAmount',
    'formatMoney',
    '// latest-rate API',
    '// LATEST-RATE',
    'LatestRate',  # method names explicitly marked latest
]

# Per-rule method-name allowlists (method is explicitly latest-rate)
LATEST_RATE_METHODS = {
    'getHomeCurrencyPurchaseTotal',
    'getHomeCurrencyTotal',
    'getHomeCurrencyCategoryTotals',
    'getHomeCurrencyMerchantTotals',
    'getHomeCurrencyMonthlyTotals',
    'getHomeCurrencyWeeklyTotals',
    'getHomeCurrencyDailyTotals',
    'getHomeCurrencyDepositTotal',
    'getHomeCurrencyPurchaseCategoryTotals',
    'getHomeCurrencyPurchaseMonthlyTotals',
    'aggregateToMoneyAggregate',
    'aggregateCurrencyTotalsToMoneyAggregate',
    'getExpensesWithConversion',  # row-level display
}


def is_excluded(file_path: Path) -> bool:
    """Check if file should be completely excluded."""
    if file_path.name in EXCLUDED_FILES:
        return True
    path_str = str(file_path).replace('\\', '/')
    return any(re.search(p, path_str) for p in EXCLUDED_PATH_PATTERNS)


def is_line_allowlisted(line: str) -> bool:
    """Check if line is allowlisted."""
    return any(token in line for token in LINE_ALLOWLIST)


def is_in_latest_method(lines: List[str], line_idx: int) -> bool:
    """Heuristic: check if current line is inside a known latest-rate method."""
    # Look backwards for a function declaration
    for i in range(line_idx, max(line_idx - 30, -1), -1):
        for method in LATEST_RATE_METHODS:
            if method in lines[i]:
                return True
    return False


def check_file(file_path: Path, root: Path) -> List[Violation]:
    """Check a single Kotlin file for money boundary violations."""
    violations = []

    try:
        content = file_path.read_text(encoding='utf-8')
        lines = content.splitlines()
    except Exception as e:
        print(f"Warning: Could not read {file_path}: {e}", file=sys.stderr)
        return violations

    path_str = str(file_path).replace('\\', '/')
    file_is_financial = is_financial_path(path_str)

    # Single-line rules
    for line_idx, line in enumerate(lines):
        if is_line_allowlisted(line):
            continue

        for pattern, rule_id, description, financial_only in RULES:
            if financial_only and not file_is_financial:
                continue
            if re.search(pattern, line):
                # Check method-level allowlist for G-MONEY-01, G-MONEY-03, G-MONEY-04
                if rule_id in ('G-MONEY-01', 'G-MONEY-03', 'G-MONEY-04', 'G-MONEY-06'):
                    if is_in_latest_method(lines, line_idx):
                        continue
                violations.append(Violation(line_idx + 1, rule_id, description, line.strip()))

    # Multiline rule: G-MONEY-02
    for match in MULTILINE_FALLBACK.finditer(content):
        line_num = content[:match.start()].count('\n') + 1
        matched_text = match.group().strip().replace('\n', ' ')
        violations.append(Violation(line_num, 'G-MONEY-02',
            'Hidden latest-rate fallback: convertAsOf() ?: convert() (multiline)', matched_text))

    return violations


def main():
    parser = argparse.ArgumentParser(description='Static money-boundary guard v2')
    parser.add_argument('--root', type=Path, default=Path(__file__).parent.parent,
                        help='Project root directory')
    args = parser.parse_args()

    src_dir = args.root / 'app' / 'src' / 'main' / 'java'
    if not src_dir.exists():
        print(f"Error: Source directory not found: {src_dir}", file=sys.stderr)
        sys.exit(1)

    kotlin_files = [f for f in src_dir.rglob('*.kt') if not is_excluded(f)]
    print(f"Scanning {len(kotlin_files)} Kotlin files for money boundary violations...")

    total_violations = 0
    files_with_violations = 0

    for kt_file in sorted(kotlin_files):
        violations = check_file(kt_file, args.root)
        if violations:
            files_with_violations += 1
            total_violations += len(violations)
            rel_path = kt_file.relative_to(args.root)
            print(f"\n❌ {rel_path}:")
            for v in violations:
                print(f"  L{v.line_num} [{v.rule}] {v.description}")
                print(f"    {v.line[:120]}")

    print(f"\n{'='*70}")
    if total_violations == 0:
        print("✅ No money boundary violations found!")
        sys.exit(0)
    else:
        print(f"❌ Found {total_violations} violation(s) in {files_with_violations} file(s)")
        print("\nTo fix:")
        print("  G-MONEY-01: Replace convert() with convertOutcome() or convertAsOf()")
        print("  G-MONEY-02: Remove ?: convert() fallback; handle failure explicitly")
        print("  G-MONEY-03: Use MoneyNormalizationEngine for historical aggregation")
        print("  G-MONEY-04: Use resolveHomeCurrency() for typed resolution")
        print("  G-MONEY-05: Always pass rateBasis to MoneyAggregate constructor")
        print("  G-MONEY-06: Use typed fromBuckets or MoneyNormalizationEngine")
        print("  G-MONEY-07: Return MoneyAggregate instead of Result<Double>")
        print("  G-MONEY-08: Use MoneyNormalizationEngine for currency-safe sums")
        print("\nTo allowlist a legitimate use, add '// G-MONEY-ALLOW' comment on the line.")
        sys.exit(1)


if __name__ == '__main__':
    main()
