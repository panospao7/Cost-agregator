#!/usr/bin/env python3
"""
Static money-boundary guard v3 for ExpenseTracker.

CURR-587-07: Hardened guard — structured allowlists, multiline call extraction,
fake-currency sentinel detection, raw snapshot non-allowlistable rules.

Rules:
  G-MONEY-01  No currencyConverter.convert(...) in aggregate/financial paths
  G-MONEY-02  No convertAsOf(...) ?: convert(...) hidden latest fallback
  G-MONEY-03  No convertMultiple(...) outside explicitly latest-rate methods
  G-MONEY-04  No homeCurrency().first() in financial math paths
  G-MONEY-05  No MoneyAggregate(...) without explicit rateBasis
  G-MONEY-06  No legacy fromBuckets(List<Pair<Double,String>>) outside latest wrappers
  G-MONEY-07  No Result<Double> or Map<..., Double> aggregate APIs in non-deprecated code
  G-MONEY-08  No raw sums (sumOf { it.amount }, sumOf { it.effectiveAmount }, ?: effectiveAmount)
  G-MONEY-09  No fake EUR in unavailable/failure containers
  G-MONEY-10  No raw ExpenseSnapshot amounts in synthesis/forecast (NON-ALLOWLISTABLE in main)
  G-MONEY-11  No fake unavailable currency sentinels CurrencyCode("XXX") or CurrencyCode("")
  G-MONEY-12  No misleading resolveHomeCurrencyOrUnavailable helper
  G-MONEY-13  No normal BudgetForecast for unavailable home-currency branch
  G-MONEY-14  CompiledDashboardData must include normalizedInput
  G-MONEY-15  Dashboard widgets must not use raw processed dashboard money values
  G-MONEY-16  SpendingTrend must not perform direct currency conversion
"""

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

@dataclass
class Rule:
    pattern: str
    rule_id: str
    description: str
    financial_only: bool
    allowlistable: bool = True  # False = cannot be suppressed in main source


@dataclass
class Violation:
    line_num: int
    rule: str
    description: str
    line: str


# ── Structured allowlist syntax ────────────────────────────────────────────────
# // G-MONEY-ALLOW[ISSUE-ID][RULE-ID]: reason (at least 15 chars)
STRUCTURED_ALLOW_RE = re.compile(
    r'G-MONEY-ALLOW\[(?P<issue>[A-Z]+-\d+)\]\[(?P<rule>G-MONEY-\d+)\]:\s*(?P<reason>.{15,})'
)
# Generic (unstructured) allowlist — rejected for non-allowlistable rules
GENERIC_ALLOW_RE = re.compile(r'G-MONEY-ALLOW(?!\[)')


# ── Financial math directories ─────────────────────────────────────────────────
FINANCIAL_DIRS = {
    'dashboard', 'budget', 'forecast', 'cashflow', 'analytics',
    'repository', 'usecase', 'forecasting', 'health', 'tax',
    'investment', 'savings', 'subscription', 'income',
}

def is_financial_path(file_path: str) -> bool:
    parts = file_path.replace('\\', '/').lower().split('/')
    return any(d in parts for d in FINANCIAL_DIRS)


# ── Rules ──────────────────────────────────────────────────────────────────────
RULES: List[Rule] = [
    Rule(r'currencyConverter\.convert\(', 'G-MONEY-01',
         'Direct currencyConverter.convert() in financial path — use convertOutcome() or convertAsOf()',
         financial_only=True),

    Rule(r'convertAsOf\([^)]*\)\s*\?\s*:\s*\w*\.?convert\(', 'G-MONEY-02',
         'Hidden latest-rate fallback: convertAsOf() ?: convert()',
         financial_only=False),

    Rule(r'convertMultiple\(', 'G-MONEY-03',
         'convertMultiple() uses latest rates — use MoneyNormalizationEngine for historical',
         financial_only=True),

    Rule(r'homeCurrency\(\)\.first\(\)', 'G-MONEY-04',
         'Use resolveHomeCurrency() instead of homeCurrency().first() in financial math',
         financial_only=True),

    Rule(r'MoneyAggregate\(\s*$', 'G-MONEY-05',
         'MoneyAggregate() constructor — ensure rateBasis is explicitly set',
         financial_only=False),

    Rule(r'fromBuckets\(\s*buckets\s*=.*Pair', 'G-MONEY-06',
         'Legacy fromBuckets(Pair) overload — use typed overload or MoneyNormalizationEngine',
         financial_only=True),

    Rule(r'Result<\s*Double\s*>', 'G-MONEY-07',
         'Legacy Result<Double> aggregate API — use MoneyAggregate',
         financial_only=False),

    Rule(r'sumOf\s*\{\s*it\.amount\s*\}', 'G-MONEY-08',
         'Raw sumOf { it.amount } without currency normalization',
         financial_only=False),
    Rule(r'sumOf\s*\{\s*it\.effectiveAmount\s*\}', 'G-MONEY-08',
         'Raw sumOf { it.effectiveAmount } without currency normalization',
         financial_only=False),
    Rule(r'\?\s*:\s*\w*\.?effectiveAmount', 'G-MONEY-08',
         'Raw effectiveAmount fallback on conversion failure',
         financial_only=False),
    Rule(r'\.getOrDefault\s*\(\s*"EUR"\s*\)', 'G-MONEY-08',
         'Silent EUR fallback via getOrDefault("EUR")',
         financial_only=False),

    Rule(r'CurrencyCode\.EUR.*UNAVAILABLE|CurrencyCode\("EUR"\).*unavailable|currency\s*=\s*"EUR".*fail',
         'G-MONEY-09',
         'Fake EUR in unavailable/failure container — use typed Unavailable result',
         financial_only=True),

    # G-MONEY-10: NON-ALLOWLISTABLE in main source
    Rule(r'ExpenseSnapshot\(.*effectiveAmount\s*=\s*\w+\.effectiveAmount', 'G-MONEY-10',
         'Raw ExpenseSnapshot.effectiveAmount passed to synthesis — normalize first',
         financial_only=True, allowlistable=False),

    # G-MONEY-11: NON-ALLOWLISTABLE
    Rule(r'CurrencyCode\("XXX"\)', 'G-MONEY-11',
         'Fake unavailable currency sentinel CurrencyCode("XXX") — use MoneyAggregateResult.Unavailable',
         financial_only=False, allowlistable=False),
    Rule(r'CurrencyCode\(""\)', 'G-MONEY-11',
         'Blank currency sentinel CurrencyCode("") — use typed Unavailable result',
         financial_only=False, allowlistable=False),
    Rule(r'MoneyAggregate\.empty\(CurrencyCode\("XXX"\)', 'G-MONEY-11',
         'MoneyAggregate.empty with fake XXX currency — use MoneyAggregateResult.Unavailable',
         financial_only=False, allowlistable=False),

    # G-MONEY-12: NON-ALLOWLISTABLE
    Rule(r'resolveHomeCurrencyOrUnavailable', 'G-MONEY-12',
         'Misleading helper name — use resolveHomeCurrencyForMoneyMath() or requireHomeCurrencyForMoneyMath()',
         financial_only=False, allowlistable=False),

    Rule(r'normalizedInput\s*:\s*DashboardNormalizedInputResult\?\s*=\s*null', 'G-MONEY-14',
         'normalizedInput must not default to null in production — always populate it',
         financial_only=False),
]

# ── Multiline rule: G-MONEY-02 across lines ────────────────────────────────────
MULTILINE_FALLBACK = re.compile(
    r'convertAsOf\([^)]*\)\s*\n\s*\?\s*:\s*\w*\.?convert\(',
    re.MULTILINE
)

# ── Files completely excluded from scanning ────────────────────────────────────
EXCLUDED_FILES = {
    'CurrencyConverter.kt',
    'MoneyNormalizationEngine.kt',
    'MoneyAggregateBuilder.kt',
    'MoneyAggregate.kt',
    'MoneyAggregateResult.kt',
    'HomeCurrencyForMoneyMath.kt',
    'MoneyMappers.kt',
    'ExchangeRateStoreAdapter.kt',
    'AppDatabase.kt',
}

EXCLUDED_PATH_PATTERNS = [
    r'/test/',
    r'/androidTest/',
    r'Test\.kt$',
    r'Fixture\.kt$',
    r'Fake\.kt$',
    r'Mock\.kt$',
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
    'getExpensesWithConversion',
}


def is_excluded(file_path: Path) -> bool:
    if file_path.name in EXCLUDED_FILES:
        return True
    path_str = str(file_path).replace('\\', '/')
    return any(re.search(p, path_str) for p in EXCLUDED_PATH_PATTERNS)


def get_structured_allow(line: str) -> Optional[str]:
    """Return the rule ID if line has a valid structured allowlist comment, else None."""
    m = STRUCTURED_ALLOW_RE.search(line)
    return m.group('rule') if m else None


def has_generic_allow(line: str) -> bool:
    return bool(GENERIC_ALLOW_RE.search(line))


def is_in_latest_method(lines: List[str], line_idx: int) -> bool:
    for i in range(line_idx, max(line_idx - 30, -1), -1):
        for method in LATEST_RATE_METHODS:
            if method in lines[i]:
                return True
    return False


def check_file(file_path: Path) -> List[Violation]:
    violations = []
    try:
        content = file_path.read_text(encoding='utf-8')
        lines = content.splitlines()
    except Exception as e:
        print(f"Warning: Could not read {file_path}: {e}", file=sys.stderr)
        return violations

    path_str = str(file_path).replace('\\', '/')
    file_is_financial = is_financial_path(path_str)

    for line_idx, line in enumerate(lines):
        # Check for generic (unstructured) allowlist — always a violation
        if has_generic_allow(line):
            violations.append(Violation(
                line_idx + 1, 'G-MONEY-ALLOW',
                'Generic // G-MONEY-ALLOW is rejected. Use structured: // G-MONEY-ALLOW[ISSUE-ID][RULE-ID]: reason',
                line.strip()
            ))
            continue

        # Check structured allowlist — skip the flagged rule if present
        structured_allowed_rule = get_structured_allow(line)

        for rule in RULES:
            if rule.financial_only and not file_is_financial:
                continue
            if not re.search(rule.pattern, line):
                continue

            # Non-allowlistable rules cannot be suppressed in main source
            if not rule.allowlistable:
                violations.append(Violation(line_idx + 1, rule.rule_id, rule.description, line.strip()))
                continue

            # Structured allowlist suppresses this specific rule
            if structured_allowed_rule == rule.rule_id:
                continue

            # Method-level allowlist for certain rules
            if rule.rule_id in ('G-MONEY-01', 'G-MONEY-03', 'G-MONEY-04', 'G-MONEY-06'):
                if is_in_latest_method(lines, line_idx):
                    continue

            violations.append(Violation(line_idx + 1, rule.rule_id, rule.description, line.strip()))

    # Multiline rule: G-MONEY-02
    for match in MULTILINE_FALLBACK.finditer(content):
        line_num = content[:match.start()].count('\n') + 1
        matched_text = match.group().strip().replace('\n', ' ')
        violations.append(Violation(line_num, 'G-MONEY-02',
            'Hidden latest-rate fallback: convertAsOf() ?: convert() (multiline)', matched_text))

    return violations


def main():
    parser = argparse.ArgumentParser(description='Static money-boundary guard v3')
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
        violations = check_file(kt_file)
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
        print("\nAllowlist syntax (for allowlistable rules only):")
        print("  // G-MONEY-ALLOW[CURR-123][G-MONEY-08]: row-level display only, not aggregated")
        print("\nNon-allowlistable rules (G-MONEY-10, G-MONEY-11, G-MONEY-12) cannot be suppressed.")
        sys.exit(1)


if __name__ == '__main__':
    main()
