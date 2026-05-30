# Pipeline Fix Status

> Tracks per-pipeline fix + validation state. Code changes were made WITHOUT
> compiling/testing (per agent constraint). Human runs validation below.

## Pipeline 5 — Currency / Dashboard / Analytics
Audit: done (see this session's review)
Implementation: done (PR-1..PR-4 applied as code slices)
Validation:
- assembleDebug: pending
- testDebugUnitTest: pending
- check (incl. verify_money_boundaries.py): pending
- connectedDebugAndroidTest: not required (no schema/migration change)

### Issues addressed
- P5-P1-01 / P5-NEW-01 / P5-P1-04: TotalsAggregationEngine year/month/week/day/category
  now use per-expense TRANSACTION_DATE, PURCHASE-only historical aggregation
  (`MultiCurrencyRepository.get*AggregatesHistorical` /
  `getHomeCurrencyPurchaseTotalHistoricalResult`). Type-agnostic latest-rate
  `getHomeCurrencyWeeklyTotals`/`getHomeCurrencyDailyTotals` are
  `@Deprecated(DeprecationLevel.ERROR)`.
- P5-NEW-09: monthly/yearly `PeriodTotal` now propagate `isPartial`/`warningMessage`.
- P5-NEW-06 / P5-P1-03: `BudgetStatusSnapshot` gained `isPartial`/`conversionWarning`;
  mapped in `DashboardContractsAdapter.observeBudgetStatuses`.
- P5-NEW-07 / P5-P1-06: `AnalyticsCurrencyNormalizer` uses `convertOutcome(TRANSACTION_DATE)`
  and detects staleness against the rate's `validDate` (not `lastUpdated`).

### Open blockers / residual
- P5-P1-08 residual (shared with Pipeline 6): budget LIMIT uses PERIOD_END historical
  while SPEND uses latest-rate `getAggregateSpent`. Documented as P6-CURRENT-001;
  not changed here to avoid cross-pipeline regression. Budget partial/warning is now
  surfaced, and conversion failure yields `BudgetHealthStatus.UNKNOWN` (not ON_TRACK).

### Compile-risk areas (verify first)
- `TotalsAggregationEngine.purchaseTotalHistorical` returns `MoneyAggregate?`
  (null on home-currency `Unavailable`); call sites use `?.displayAmount ?: 0.0`.
- Three engine test files were updated to mock the new historical methods and to
  wrap returns in `MoneyAggregateResult.Available(...)`. If MockK strict-mock errors
  appear, check for any missed `getHomeCurrency*` stub.
- No Room schema/migration/Hilt-binding change → `compileDebugKotlin` is a reasonable
  first gate, but still run `assembleDebug` to be safe.

## Pipeline 6
Not started
