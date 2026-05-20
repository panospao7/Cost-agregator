# Money Boundary Guard

## Overview

`scripts/verify_money_boundaries.py` is a static analysis guard that runs in CI to prevent regressions back to mixed-currency arithmetic or hidden latest-rate fallback.

## Guard Rules

| Rule | Pattern Detected | Fix |
|------|-----------------|-----|
| G-MONEY-01 | `currencyConverter.convert()` in financial paths | Use `convertOutcome()` or `convertAsOf()` |
| G-MONEY-02 | `convertAsOf() ?: convert()` | Remove fallback; handle failure explicitly |
| G-MONEY-03 | `convertMultiple()` in historical paths | Use `MoneyNormalizationEngine` |
| G-MONEY-04 | `homeCurrency().first()` in financial math | Use `resolveHomeCurrency()` |
| G-MONEY-05 | `MoneyAggregate(` without `rateBasis` | Always pass explicit `rateBasis` |
| G-MONEY-06 | Legacy `fromBuckets(Pair)` in historical paths | Use typed overload or engine |
| G-MONEY-07 | `Result<Double>` aggregate APIs | Return `MoneyAggregate` |
| G-MONEY-08 | Raw sums, EUR fallback, effectiveAmount fallback | Use normalization engine |

## Financial Paths

Rules G-MONEY-01, 03, 04, 06 only fire in **financial math directories**:
`dashboard`, `budget`, `forecast`, `cashflow`, `analytics`, `repository`, `usecase`, `forecasting`, `health`, `tax`, `investment`, `savings`, `subscription`, `income`

## Excluded Files

Core infrastructure files are excluded (they define the APIs being guarded):
- `CurrencyConverter.kt`
- `MoneyNormalizationEngine.kt`
- `MoneyAggregateBuilder.kt`
- `MoneyAggregate.kt`
- `MoneyMappers.kt`
- `ExchangeRateStoreAdapter.kt`
- `AppDatabase.kt`

All test files (`/test/`, `Test.kt`, `Fixture.kt`, etc.) are excluded.

## Allowlisting

### Line-level allowlist

Add `// G-MONEY-ALLOW` comment on the line:

```kotlin
val rate = currencyConverter.convert(amount, from, to) // G-MONEY-ALLOW: row display
```

### Method-level allowlist

Methods with "LatestRate" in their name or documented as `// LATEST-RATE` are automatically allowed for G-MONEY-01/03/04/06.

### Adding new allowlist entries

1. Verify the usage is genuinely latest-rate or row-display (not aggregate math).
2. Add the method name to `LATEST_RATE_METHODS` in the script, or add `// G-MONEY-ALLOW` inline.
3. Document why in a comment.

## CI Integration

The guard runs in the `unit-tests` job:

```yaml
- name: Verify money boundaries
  run: python3 scripts/verify_money_boundaries.py --root .
```

A violation fails the CI build. Fix the code or add an explicit allowlist entry.

## Running Locally

```bash
python3 scripts/verify_money_boundaries.py --root .
```
