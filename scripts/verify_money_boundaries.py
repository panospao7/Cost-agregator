#!/usr/bin/env python3
"""
Static money-boundary guard v5 for ExpenseTracker.

CURR-587-07 / CURR-587-09 finalization: comment stripping, G-MONEY-17/18/19/21,
precise scoping so current source passes honestly.

Rules enforced:
  G-MONEY-10  No raw ExpenseSnapshot amounts in synthesis/forecast (NON-ALLOWLISTABLE)
  G-MONEY-11  No fake unavailable currency sentinels (NON-ALLOWLISTABLE)
  G-MONEY-12  No misleading resolveHomeCurrencyOrUnavailable helper (NON-ALLOWLISTABLE)
  G-MONEY-13  No normal BudgetForecast for unavailable home-currency branch (NON-ALLOWLISTABLE)
  G-MONEY-14  CompiledDashboardData must include normalizedInput
  G-MONEY-15  Dashboard widgets must not use raw processed dashboard money values
  G-MONEY-16  SpendingTrend must not perform direct currency conversion (NON-ALLOWLISTABLE)
  G-MONEY-17  No convertMultiple in new aggregate code (allowlistable)
  G-MONEY-18  No RateBasis.LATEST_AVAILABLE with StaleRatePolicy.None (allowlistable)
  G-MONEY-19  Latest aggregate code must use StaleRatePolicy.forBasis (allowlistable)
  G-MONEY-21  No emptyList() fallback for unavailable forecast/runway synthesis (NON-ALLOWLISTABLE)
"""

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

@dataclass
class Rule:
    pattern: str
    rule_id: str
    description: str
    financial_only: bool
    allowlistable: bool = True


@dataclass
class Violation:
    line_num: int
    rule: str
    description: str
    line: str


# ── Structured allowlist syntax ────────────────────────────────────────────────
STRUCTURED_ALLOW_RE = re.compile(
    r'G-MONEY-ALLOW\[(?P<issue>[A-Z]+-\d+)\]\[(?P<rule>G-MONEY-\d+)\]:\s*(?P<reason>.{15,})'
)
GENERIC_ALLOW_RE = re.compile(r'G-MONEY-ALLOW(?!\[)')

# ── Comment stripping ──────────────────────────────────────────────────────────
LINE_COMMENT_RE = re.compile(r'//.*$', re.MULTILINE)
BLOCK_COMMENT_RE = re.compile(r'/\*.*?\*/', re.DOTALL)

def strip_comments(content: str) -> str:
    """Strip Kotlin line and block comments before scanning."""
    content = BLOCK_COMMENT_RE.sub('', content)
    content = LINE_COMMENT_RE.sub('', content)
    return content


# ── Financial math directories ─────────────────────────────────────────────────
FINANCIAL_DIRS = {
    'dashboard', 'budget', 'forecast', 'cashflow', 'analytics',
    'repository', 'usecase', 'forecasting', 'health', 'tax',
    'investment', 'savings', 'subscription', 'income',
}

def is_financial_path(file_path: str) -> bool:
    parts = file_path.replace('\\', '/').lower().split('/')
    return any(d in parts for d in FINANCIAL_DIRS)


# ── Call-block extractor ───────────────────────────────────────────────────────
def extract_call_block(content: str, start_index: int) -> str:
    depth = 0
    i = start_index
    in_string = False
    escape = False
    while i < len(content):
        ch = content[i]
        if in_string:
            if escape:
                escape = False
            elif ch == '\\':
                escape = True
            elif ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    return content[start_index:i + 1]
        i += 1
    return content[start_index:]


# ── Rules ──────────────────────────────────────────────────────────────────────
# Only rules that are relevant to the CURR-587 work and scoped to avoid
# false positives in the broader codebase.

RULES: List[Rule] = [
    # G-MONEY-10: raw ExpenseSnapshot in synthesis — NON-ALLOWLISTABLE
    Rule(r'ExpenseSnapshot\(.*effectiveAmount\s*=\s*\w+\.effectiveAmount', 'G-MONEY-10',
         'Raw ExpenseSnapshot.effectiveAmount in synthesis — normalize first',
         financial_only=True, allowlistable=False),

    # G-MONEY-11: fake unavailable currency sentinels — NON-ALLOWLISTABLE
    Rule(r'CurrencyCode\("XXX"\)', 'G-MONEY-11',
         'Fake unavailable currency sentinel CurrencyCode("XXX") — use MoneyAggregateResult.Unavailable',
         financial_only=False, allowlistable=False),
    Rule(r'MoneyAggregate\.empty\(CurrencyCode\("XXX"\)', 'G-MONEY-11',
         'MoneyAggregate.empty with fake XXX currency — use MoneyAggregateResult.Unavailable',
         financial_only=False, allowlistable=False),

    # G-MONEY-12: misleading helper — NON-ALLOWLISTABLE
    Rule(r'resolveHomeCurrencyOrUnavailable\s*\(', 'G-MONEY-12',
         'Misleading helper — use resolveHomeCurrencyForMoneyMath() or requireHomeCurrencyForMoneyMath()',
         financial_only=False, allowlistable=False),

    # G-MONEY-14: CompiledDashboardData without normalizedInput
    Rule(r'normalizedInput\s*:\s*DashboardNormalizedInputResult\?\s*=\s*null', 'G-MONEY-14',
         'normalizedInput must not default to null in production',
         financial_only=False),

    # G-MONEY-16: SpendingTrend direct conversion — NON-ALLOWLISTABLE (scoped to dashboard use case)
    Rule(r'currencyConverter\.convertAsOf', 'G-MONEY-16',
         'SpendingTrend must not call convertAsOf directly — use DashboardNormalizedInputResult',
         financial_only=True, allowlistable=False),

    # G-MONEY-21: emptyList() fallback for unavailable forecast/runway — NON-ALLOWLISTABLE
    # Scoped to dashboard use case file only
    Rule(r'Unavailable\s*->\s*\n?\s*emptyList\(\)', 'G-MONEY-21',
         'emptyList() fallback for unavailable normalized input in forecast/runway — return typed unavailable instead',
         financial_only=True, allowlistable=False),

    # G-MONEY-17: convertMultiple in new aggregate code (allowlistable for legacy compat)
    Rule(r'convertMultiple\(', 'G-MONEY-17',
         'convertMultiple() may not carry stale metadata — use convertOutcome() with StaleRatePolicy.forBasis()',
         financial_only=True),

    # G-MONEY-18: LATEST_AVAILABLE with StaleRatePolicy.None
    Rule(r'RateBasis\.LATEST_AVAILABLE[\s\S]{0,200}?StaleRatePolicy\.None', 'G-MONEY-18',
         'RateBasis.LATEST_AVAILABLE must not use StaleRatePolicy.None — use StaleRatePolicy.forBasis()',
         financial_only=True),

    # G-MONEY-19: latest aggregate without forBasis (allowlistable)
    Rule(r'stalePolicy\s*=\s*StaleRatePolicy\.None', 'G-MONEY-19',
         'Hardcoded StaleRatePolicy.None — use StaleRatePolicy.forBasis(rateBasis) for canonical policy',
         financial_only=True),
]

# ── G-MONEY-13: BudgetForecast unavailable sentinel (multiline) ────────────────
BUDGET_FORECAST_UNAVAILABLE_RE = re.compile(
    r'HomeCurrencyResolution\.Failed[\s\S]{0,600}?BudgetForecast\s*\(',
    re.MULTILINE
)
BUDGET_FORECAST_SAFE_RE = re.compile(
    r'BudgetForecastResult\.Unavailable|ForecastCurrencyStatus\.HOME_CURRENCY_UNAVAILABLE'
)

# ── G-MONEY-15: dashboard raw money (scoped to ComputeDashboardWidgetsUseCase) ─
G_MONEY_15_PATTERNS = [
    (r'summary\.totalSpent', 'summary.totalSpent'),
    (r'summary\.previousTotalSpent', 'summary.previousTotalSpent'),
    (r'weather\.discretionaryBudget', 'weather.discretionaryBudget'),
    (r'getHomeCurrencyPurchaseTotal\(', 'getHomeCurrencyPurchaseTotal()'),
    (r'getHomeCurrencyDepositTotal\(', 'getHomeCurrencyDepositTotal()'),
]
G_MONEY_15_FILE_RE = re.compile(r'ComputeDashboardWidgetsUseCase', re.IGNORECASE)

# ── Multiline G-MONEY-02 ───────────────────────────────────────────────────────
MULTILINE_FALLBACK = re.compile(
    r'convertAsOf\([^)]*\)\s*\n\s*\?\s*:\s*\w*\.?convert\(',
    re.MULTILINE
)

# ── Files excluded from scanning ──────────────────────────────────────────────
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
    'StaleRatePolicy.kt',  # defines the policies
}

EXCLUDED_PATH_PATTERNS = [
    r'/test/',
    r'/androidTest/',
    r'Test\.kt$',
    r'Fixture\.kt$',
    r'Fake\.kt$',
    r'Mock\.kt$',
]

# G-MONEY-17 allowlist: these files use convertMultiple for legacy compat
G_MONEY_17_ALLOWLIST_FILES = {
    'MultiCurrencyRepository.kt',  # legacy latest-rate compat methods
}

# G-MONEY-19 allowlist: StaleRatePolicy.None is valid for non-latest bases
G_MONEY_19_ALLOWLIST_CONTEXT = re.compile(
    r'RateBasis\.TRANSACTION_DATE|RateBasis\.PERIOD_END|RateBasis\.IDENTITY|RateBasis\.PERIOD_MIDPOINT'
)


def is_excluded(file_path: Path) -> bool:
    if file_path.name in EXCLUDED_FILES:
        return True
    path_str = str(file_path).replace('\\', '/')
    return any(re.search(p, path_str) for p in EXCLUDED_PATH_PATTERNS)


def get_structured_allow(line: str) -> Optional[str]:
    m = STRUCTURED_ALLOW_RE.search(line)
    return m.group('rule') if m else None


def has_generic_allow(line: str) -> bool:
    return bool(GENERIC_ALLOW_RE.search(line))


def check_file(file_path: Path) -> List[Violation]:
    violations = []
    try:
        raw_content = file_path.read_text(encoding='utf-8')
    except Exception as e:
        print(f"Warning: Could not read {file_path}: {e}", file=sys.stderr)
        return violations

    # Strip comments for multiline pattern matching only
    content_stripped = strip_comments(raw_content)
    lines_raw = raw_content.splitlines()

    path_str = str(file_path).replace('\\', '/')
    file_is_financial = is_financial_path(path_str)
    is_dashboard_usecase = G_MONEY_15_FILE_RE.search(file_path.name) is not None

    # G-MONEY-13: BudgetForecast unavailable sentinel (multiline, budget files only)
    if 'budget' in path_str.lower():
        for match in BUDGET_FORECAST_UNAVAILABLE_RE.finditer(content_stripped):
            block = match.group()
            if not BUDGET_FORECAST_SAFE_RE.search(block):
                line_num = content_stripped[:match.start()].count('\n') + 1
                violations.append(Violation(
                    line_num, 'G-MONEY-13',
                    'Normal BudgetForecast in unavailable home-currency branch — use BudgetForecastResult.Unavailable',
                    block[:120].replace('\n', ' ')
                ))

    # G-MONEY-15: dashboard raw money (scoped to ComputeDashboardWidgetsUseCase only)
    if is_dashboard_usecase:
        for pattern, label in G_MONEY_15_PATTERNS:
            for m in re.finditer(pattern, content_stripped):
                line_num = content_stripped[:m.start()].count('\n') + 1
                violations.append(Violation(
                    line_num, 'G-MONEY-15',
                    f'Dashboard widget must not use raw {label} — use normalized input',
                    lines_raw[line_num - 1].strip() if line_num <= len(lines_raw) else ''
                ))

    # Line-level rules — scan raw lines (allowlists are in comments, patterns are in code)
    for line_idx, raw_line in enumerate(lines_raw):
        # Skip pure comment lines
        stripped = raw_line.strip()
        if stripped.startswith('//') or stripped.startswith('*') or stripped.startswith('/*'):
            continue

        # Check for generic allowlist
        if has_generic_allow(raw_line):
            violations.append(Violation(
                line_idx + 1, 'G-MONEY-ALLOW',
                'Generic // G-MONEY-ALLOW rejected. Use: // G-MONEY-ALLOW[ISSUE-ID][RULE-ID]: reason',
                raw_line.strip()
            ))
            continue

        # Check structured allowlist from raw line
        structured_allowed_rule = get_structured_allow(raw_line)

        # Strip inline comment for pattern matching
        code_part = LINE_COMMENT_RE.sub('', raw_line)

        for rule in RULES:
            if rule.financial_only and not file_is_financial:
                continue
            # G-MONEY-16/21 only in dashboard use case
            if rule.rule_id in ('G-MONEY-16', 'G-MONEY-21') and not is_dashboard_usecase:
                continue
            if not re.search(rule.pattern, code_part):
                continue

            # G-MONEY-17: skip for legacy compat files
            if rule.rule_id == 'G-MONEY-17' and file_path.name in G_MONEY_17_ALLOWLIST_FILES:
                continue

            # G-MONEY-19: skip if nearby context shows non-latest basis
            if rule.rule_id == 'G-MONEY-19':
                context_window = '\n'.join(lines_raw[max(0, line_idx-5):line_idx+5])
                if G_MONEY_19_ALLOWLIST_CONTEXT.search(context_window):
                    continue

            if not rule.allowlistable:
                violations.append(Violation(line_idx + 1, rule.rule_id, rule.description, code_part.strip()))
                continue

            if structured_allowed_rule == rule.rule_id:
                continue

            violations.append(Violation(line_idx + 1, rule.rule_id, rule.description, code_part.strip()))

    # Multiline G-MONEY-02
    for match in MULTILINE_FALLBACK.finditer(content_stripped):
        line_num = content_stripped[:match.start()].count('\n') + 1
        violations.append(Violation(line_num, 'G-MONEY-02',
            'Hidden latest-rate fallback: convertAsOf() ?: convert() (multiline)',
            match.group().strip().replace('\n', ' ')))

    return violations


def main():
    parser = argparse.ArgumentParser(description='Static money-boundary guard v5')
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
            print(f"\nFAIL {rel_path}:")
            for v in violations:
                print(f"  L{v.line_num} [{v.rule}] {v.description}")
                print(f"    {v.line[:120]}")

    print(f"\n{'='*70}")
    if total_violations == 0:
        print("PASS: No money boundary violations found!")
        sys.exit(0)
    else:
        print(f"FAIL: Found {total_violations} violation(s) in {files_with_violations} file(s)")
        print("\nAllowlist syntax (for allowlistable rules only):")
        print("  // G-MONEY-ALLOW[CURR-123][G-MONEY-17]: legacy compat path, not new aggregate code")
        print("\nNon-allowlistable rules (G-MONEY-10/11/12/13/16/21) cannot be suppressed.")
        sys.exit(1)


if __name__ == '__main__':
    main()
