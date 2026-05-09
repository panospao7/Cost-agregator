# Pipeline 5 Debug Report — Currency / Dashboard / Analytics

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 5 is **much improved but not fully clean/stable yet**.

The codebase now has strong infrastructure:

- `CurrencyCode`
- `MoneyAmount`
- `MoneyAggregate`
- `MoneyBucket`
- `ConversionFailure`
- `MoneyAggregateBuilder`
- `CurrencyConverter.convertAsOf()`
- `MultiCurrencyRepository`
- `AnalyticsCurrencyNormalizer`
- `AnalyticsInputAssembler`
- `NormalizedAnalyticsInput`
- per-currency SQL aggregate queries in `ExpenseDao`

But the pipeline is still **yellow/orange**, not production-clean, because dashboard and analytics still mix three contracts:

```text
1. canonical MoneyAggregate / normalized input
2. latest-rate aggregate conversion
3. deprecated raw Double totals
```

Main risks:

1. current/latest FX rates are still used for several historical period aggregates;
2. dashboard adapter drops `MoneyAggregate`, partial flags, source buckets, and warnings;
3. weekly/daily totals drilldown currently returns empty lists;
4. dashboard trend/forecast widgets still use raw dashboard expenses;
5. stale-rate information is not propagated into analytics quality;
6. `ExchangeRateDao.getRate()` can return an arbitrary historical row because it has no `ORDER BY`;
7. many deprecated raw aggregate DAO methods remain reachable;
8. UI model does not carry enough currency-quality metadata to show warnings consistently.

Current state: **core currency primitives are good, but dashboard/analytics consumption is still partial**.

---

# Severity scale

- **P0 / Critical:** silently wrong financial totals in common user-visible dashboard/analytics paths.
- **P1 / High:** partial/missing warnings, broken drilldown, wrong FX-rate basis, lifecycle-wide regression risk.
- **P2 / Medium:** weak diagnostics, stale confidence, legacy API surface, UX caveat.
- **P3 / Low:** cleanup/maintainability.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| Home currency setting loaded | Mostly yes. `CurrencySettingsRepository.homeCurrency()` is used in several paths. Silent EUR fallback remains. |
| Original currency preserved | Mostly yes. `Expense.currency`, `NormalizedExpense.originalCurrency`, and `MoneyBucket` preserve source currency. |
| Exchange rate lookup works | Partial. Direct/latest and historical lookup exist, but latest lookup query is ambiguous with historical rows. |
| Historical rates used correctly | Partial. `AnalyticsCurrencyNormalizer` uses `convertAsOf()`, but `MultiCurrencyRepository` aggregates use `convertMultiple()` / latest rates. |
| Stale rate detected | Partial. `CurrencyConverter.convertMultiple()` classifies stale vs missing, but analytics quality hardcodes `staleRateCount = 0`. |
| Missing rate detected | Mostly yes at domain layer, but warnings are often dropped before dashboard UI. |
| Source buckets preserved | Yes inside `MoneyAggregate`; lost in dashboard DTOs. |
| No raw cross-currency sum | Not fully. Deprecated raw DAO methods remain; dashboard trend/forecast still raw-sum some paths. |
| Partial aggregate flag shown | Not consistently. Several adapters strip `isPartial` and `aggregate`. |
| Dashboard warning shown | Not reliably. Dashboard widget models mostly have raw `Double`, no quality/warning fields. |
| Analytics warning shown | Partial. Advanced analytics returns warning lists; repository summary TODO still says quality is incomplete. |
| Budget uses normalized values safely | Partial. Advanced analytics deprecates budget comparison path and says use `BudgetVsActualEngine`. |
| Export includes original + converted fields | Not rechecked in this pipeline. |
| Forecast confidence reduced if data partial | Partial. `AnalyticsDataQuality` has penalty fields, but TODO says downstream propagation is incomplete. |

---

# Positive findings to preserve

## PF-01 — Strong money primitives now exist

`MoneyAggregate` is explicitly documented as the approved result type for financial aggregation. It preserves:

```text
displayAmount
displayCurrency
sourceBuckets
conversionFailures
isPartial
warningMessage
```

This is the right contract.

## PF-02 — Effective amount SQL is centralized

`ExpenseDao.EFFECTIVE_AMOUNT_SQL` mirrors ownership logic:

```text
isNotMine → 0
shared with myShareAmount → myShareAmount
shared with mySharePercentage → proportional amount
else → full amount
```

The per-currency aggregate queries use this formula, which is good for dashboard totals and shared expenses.

## PF-03 — Per-currency aggregate DAO paths exist

`ExpenseDao` has per-currency methods such as:

```text
getAllSpentBetweenByCurrency
getTotalSpentBetweenByCurrency
getDepositTotalsBetweenByCurrency
getAllCategoryTotalsBetweenByCurrency
getCategoryTotalsBetweenByCurrency
getAllMerchantTotalsBetweenByCurrency
getAllMonthlyTotalsBetweenByCurrency
```

This is much safer than raw `SUM(amount)`.

## PF-04 — `AnalyticsCurrencyNormalizer` uses historical conversion

For per-row analytics, it calls:

```kotlin
currencyConverter.convertAsOf(
    amount = expense.effectiveAmount,
    fromCurrency = sourceCurrency.code,
    toCurrency = homeCurrency.code,
    atMillis = expense.date
)
```

This is the right basis for historical analytics.

## PF-05 — Advanced analytics mostly normalizes before arithmetic

`AdvancedAnalyticsEngine` now injects `AnalyticsCurrencyNormalizer` and normalizes expenses before statistical/category/merchant/day-of-week calculations.

## PF-06 — Some dangerous dashboard totals are now blocked

`TotalsAggregationEngine.getWeeklyTotals()`, `getDailyTotals()`, and `getDailyTotalsForRange()` no longer execute known raw mixed-currency DAO sums. They warn and return empty lists. That avoids silent wrong data, but it breaks drilldown.

---

# Issue P1-01 — Historical totals use latest-rate aggregate conversion

## Severity

P1 / High

## Evidence

`MultiCurrencyRepository.getHomeCurrencyPurchaseTotal()`, category totals, merchant totals, and monthly totals use per-currency aggregate SQL, then call:

```kotlin
currencyConverter.convertMultiple(amounts, homeCurrency)
```

`convertMultiple()` uses `convert()`, and `convert()` explicitly uses latest/current exchange rates, not historical rates.

At the same time, `AnalyticsCurrencyNormalizer` uses `convertAsOf(expense.date)` for daily-history normalization.

## Impact

A dashboard period can contain internally inconsistent numbers:

```text
monthly total = converted with latest rate
daily history = converted with historical per-expense rate
category total = latest rate
analytics insight = historical rate
```

For volatile currencies, old transactions can be materially misreported.

## Fixing strategy

Define one conversion basis per output:

```text
Historical reporting/dashboard period totals → convertAsOf(expense.date)
Current valuation cards → convert()
```

Do not let a period-reporting API use latest rates unless the method name says so.

## Implementation plan

1. Add historical aggregate API:

```kotlin
suspend fun getHomeCurrencyPurchaseTotalHistorical(
    startDate: Long,
    endDate: Long
): MoneyAggregate
```

2. For historical correctness, either:
   - fetch rows and convert each row with `convertAsOf(expense.date)`, or
   - group by `(currency, validDate/day)` if a performant SQL path is needed.

3. Rename current-rate APIs:

```kotlin
getHomeCurrencyPurchaseTotalLatestRate(...)
getHomeCurrencyCategoryTotalsLatestRate(...)
```

4. Migrate dashboard and analytics period/reporting paths to historical APIs.

5. Tests:

```text
period_total_uses_purchase_date_rate_not_latest_rate
daily_history_sum_matches_period_total_when_all_rates_available
category_breakdown_sum_matches_period_total
latest_rate_card_uses_latest_rate_explicitly
```

---

# Issue P1-02 — `ExchangeRateDao.getRate()` is ambiguous with historical rows

## Severity

P1 / High

## Evidence

`ExchangeRate` has a unique index on:

```text
fromCurrency + toCurrency + validDate
```

so multiple rows can exist for the same pair across dates.

But `ExchangeRateDao.getRate()` is:

```sql
SELECT * FROM exchange_rates
WHERE fromCurrency = :fromCurrency
  AND toCurrency = :toCurrency
LIMIT 1
```

No `ORDER BY validDate DESC` or `lastUpdated DESC`.

## Impact

`CurrencyConverter.convert()` can use an arbitrary historical rate rather than the newest available rate.

That affects:

```text
MultiCurrencyRepository latest-rate totals
current dashboard cards
currency settings screen
rate existence checks
```

## Fixing strategy

Make “latest rate” deterministic.

## Implementation plan

1. Change DAO:

```sql
SELECT * FROM exchange_rates
WHERE fromCurrency = :fromCurrency
  AND toCurrency = :toCurrency
ORDER BY validDate DESC, lastUpdated DESC
LIMIT 1
```

2. If `validDate = 0` means unknown, decide ordering policy:

```text
known validDate rows first, or lastUpdated first for manual rates
```

3. Add a specific DAO for current/latest rate:

```kotlin
suspend fun getLatestRateForPair(from: String, to: String): ExchangeRate?
```

4. Tests:

```text
getRate_returns_newest_validDate_for_pair
getRate_tiebreaks_by_lastUpdated
convert_uses_latest_pair_rate
historical_getRateAsOf_still_uses_validDate_lte_requested_date
```

---

# Issue P1-03 — Dashboard adapter drops `MoneyAggregate` and partial warnings

## Severity

P1 / High

## Evidence

`AnalyticsRepository.getSpendingSummary()` returns:

```text
aggregate: MoneyAggregate?
isPartial: Boolean
currency
```

But `DashboardContractsAdapter.observeSpendingSummary()` maps it to dashboard-domain `SpendingSummary` with only:

```text
totalSpent
previousTotalSpent
changePercent
dailyHistory
previousDailyHistory
transactionCount
currency
```

The aggregate and `isPartial` do not survive.

Similarly, dashboard category breakdown maps to raw amount/percentage and loses aggregate, source buckets, and conversion failures.

## Impact

The domain knows totals are partial, but the dashboard cannot display:

```text
“Total excludes 3 transactions due to missing/stale exchange rates”
```

This violates the pipeline checklist:

```text
partial aggregate flag shown
dashboard warning shown
source buckets preserved
```

## Fixing strategy

Carry `MoneyAggregate` or a compact `CurrencyQuality` object through dashboard DTOs.

## Implementation plan

1. Extend dashboard model:

```kotlin
data class SpendingSummary(
    val totalSpent: Double,
    ...
    val currency: String,
    val aggregate: MoneyAggregate? = null,
    val isPartial: Boolean = false,
    val warningMessage: String? = null
)
```

2. Extend `DashboardCategoryBreakdown`:

```kotlin
val aggregate: MoneyAggregate?
val isPartial: Boolean
val warningMessage: String?
```

3. Update `DashboardContractsAdapter` to preserve these fields.

4. Update UI cards:
   - period summary,
   - top categories,
   - totals dashboard,
   - analytics cards.

5. Tests:

```text
dashboard_spending_summary_preserves_partial_flag
dashboard_category_breakdown_preserves_source_buckets
dashboard_shows_warning_when_money_aggregate_partial
```

---

# Issue P1-04 — Weekly/daily totals drilldown is functionally broken

## Severity

P1 / High

## Evidence

`TotalsAggregationEngine.getWeeklyTotals()`, `getDailyTotals()`, and `getDailyTotalsForRange()` are deprecated and immediately return:

```kotlin
return@reactiveFlow emptyList()
```

after logging that the raw mixed-currency path is unsafe.

`HomeViewModel.drillDownToPeriod()` still calls:

```kotlin
totalsAggregationEngine.getWeeklyTotals(year, month)
totalsAggregationEngine.getDailyTotalsForRange(...)
```

## Impact

The totals dashboard can show monthly totals, but drilling into weeks/days returns empty data.

This is safer than wrong raw sums, but it is still a user-visible broken pipeline.

## Fixing strategy

Wire the already-existing safe APIs:

```text
MultiCurrencyRepository.getHomeCurrencyWeeklyTotals()
MultiCurrencyRepository.getHomeCurrencyDailyTotals()
```

or use `DailyBucketEngine` + `NormalizedAnalyticsInput`.

## Implementation plan

1. Replace `getWeeklyTotals()` implementation:

```kotlin
val weekly = multiCurrencyRepository.getHomeCurrencyWeeklyTotals(monthStartMs, monthEndMs)
```

2. Replace `getDailyTotalsForRange()`:

```kotlin
val daily = multiCurrencyRepository.getHomeCurrencyDailyTotals(startMs, endMs)
```

3. Convert `PeriodMoneyAggregate` into `PeriodTotal`.

4. Preserve `MoneyAggregate` quality in `PeriodTotal` or add side channel:

```kotlin
val aggregate: MoneyAggregate?
val isPartial: Boolean
val warningMessage: String?
```

5. Tests:

```text
month_drilldown_returns_weekly_totals_for_mixed_currency
week_drilldown_returns_daily_totals_for_mixed_currency
weekly_drilldown_shows_partial_warning_when_rate_missing
daily_drilldown_sum_matches_parent_week_total
```

---

# Issue P1-05 — Dashboard widgets still raw-sum `DashboardExpense.effectiveAmount`

## Severity

P1 / High

## Evidence

`ComputeDashboardWidgetsUseCase` uses `MultiCurrencyRepository` for some headline numbers:

```text
todaySpent
weekSpent
Monte Carlo spentToDate
```

But other widgets still use raw dashboard expenses:

- `computeSpendingTrend()` groups `ctx.data.data.expenses` and accumulates `exp.effectiveAmount`;
- `computeRunwayAndForecast()` converts `DashboardExpense` into `ExpenseSnapshot` with original amount/currency and sends it to `ForecastInputAssembler`;
- `computeBlockParty()` passes `ctx.expenseEntities` and `summary.dailyHistory`;
- recent/category widgets use already-flattened dashboard DTO amounts.

Unless every upstream dashboard expense has already been normalized, these are raw mixed-currency paths.

`DashboardDataProvider` injects `AnalyticsCurrencyNormalizer` but does not actually use it in `getAllDataFlow()` or `getProcessedDataFlow()`.

## Impact

Some dashboard widgets can disagree with the headline totals.

Example:

```text
PeriodSummary monthSpent = normalized total
SpendingTrend = raw EUR+USD+GBP sum
Forecast/block-party = potentially raw expenses
```

## Fixing strategy

Dashboard computation should use one canonical normalized input object.

## Implementation plan

1. Add:

```kotlin
DashboardNormalizedInput(
    val period: PeriodRange,
    val homeCurrency: String,
    val expenses: List<NormalizedExpense>,
    val dataQuality: AnalyticsDataQuality
)
```

2. Build it in `DashboardDataProvider` using `AnalyticsInputAssembler` or `AnalyticsCurrencyNormalizer`.

3. Change `ComputeDashboardWidgetsUseCase.compute()` to consume normalized amounts for:
   - spending trend,
   - forecast input,
   - block party,
   - health score,
   - category widgets.

4. Keep original currency only for display rows/recent transaction detail.

5. Tests:

```text
dashboard_spending_trend_does_not_raw_sum_mixed_currency
dashboard_forecast_receives_home_currency_expense_snapshots
dashboard_period_summary_and_trend_month_total_match
dashboard_data_quality_warning_propagates_to_widgets
```

---

# Issue P1-06 — Stale-rate state is not propagated to analytics quality

## Severity

P1 / High

## Evidence

`CurrencyConverter.convertMultiple()` distinguishes:

```text
MISSING_RATE
STALE_RATE
```

But `AnalyticsInputAssembler` sets:

```kotlin
staleRateCount = 0 // A19: STALE_EXCHANGE_RATE not yet surfaced by normalizer
```

`AnalyticsCurrencyNormalizer` treats failed `convertAsOf()` as `MISSING_EXCHANGE_RATE`; it does not expose stale-vs-missing or old-rate-quality metadata.

## Impact

The app cannot accurately communicate:

```text
missing rate
vs stale current rate
vs old historical estimate
```

Forecast confidence and insights confidence cannot react correctly.

## Fixing strategy

Make conversion failure typed end-to-end.

## Implementation plan

1. Add typed result to `CurrencyConverter`:

```kotlin
sealed interface ConversionOutcome {
    data class Converted(...)
    data class Failed(
        val type: ConversionFailureType,
        val sourceCurrency: String,
        val targetCurrency: String,
        val amount: Double
    )
}
```

2. Update `AnalyticsCurrencyNormalizer` warnings:

```text
MISSING_EXCHANGE_RATE
STALE_EXCHANGE_RATE
INVALID_TRANSACTION_CURRENCY
INVALID_HOME_CURRENCY
```

3. Fill:

```kotlin
AnalyticsDataQuality.staleRateCount
missingRateCount
conversionWarnings
confidencePenalty
```

4. Tests:

```text
stale_latest_rate_sets_stale_warning
missing_rate_sets_missing_warning
analytics_quality_counts_stale_and_missing_separately
forecast_confidence_reduced_when_rates_partial
```

---

# Issue P1-07 — `MultiCurrencyRepository` does not consistently use `MoneyAggregateBuilder`

## Severity

P1 / High for diagnostics consistency

## Evidence

`MoneyAggregateBuilder.fromBuckets()` correctly maps failures and sets transaction counts in warning messages.

But `MultiCurrencyRepository.aggregateToMoneyAggregate()` and `aggregateCurrencyTotalsToMoneyAggregate()` manually build `MoneyAggregate` and map:

```kotlin
aggregate.failedConversions.map { it.toConversionFailure() }
```

without attaching the bucket transaction count.

The warning says:

```text
Total excludes N currency bucket(s)
```

not affected transaction count.

## Impact

The UI/debug layer can show incorrect diagnostic counts:

```text
1 failed bucket
```

when the bucket contains 37 transactions.

`MoneyAggregate.failedTransactionCount` may also be unreliable for these paths if `toConversionFailure()` defaults transaction count to zero/one.

## Fixing strategy

Use one builder everywhere.

## Implementation plan

1. Replace internal manual MCR aggregate helpers with:

```kotlin
MoneyAggregateBuilder.fromBuckets(
    buckets = currencyTotals.map { it.total to it.currency },
    transactionCounts = currencyTotals.map { it.txCount },
    homeCurrency = homeCurrency,
    converter = currencyConverter
)
```

2. Delete duplicate manual warning construction.

3. Tests:

```text
mcr_total_failure_reports_failed_transaction_count_not_bucket_count
mcr_category_failure_preserves_source_bucket_counts
mcr_monthly_failure_uses_same_warning_as_money_aggregate_builder
```

---

# Issue P1-08 — Budget-vs-actual comparisons are still not fully normalized

## Severity

P1 / High

## Evidence

`AdvancedAnalyticsEngine.getCategoryAnalytics()` is deprecated and has a code comment:

```text
A10 PARTIAL: Budget amounts may not be normalized.
Use BudgetVsActualEngine for canonical comparison.
```

It still compares normalized spending totals against `budget.amount` from `BudgetSnapshot`.

`DashboardContractsAdapter.observeBudgetStatuses()` maps budget status values from `BudgetRepository` directly into dashboard DTOs.

## Impact

If budget currency or expense currency differs from home currency, budget utilization can be wrong:

```text
normalized spend / raw budget amount
```

This affects:

```text
budget health widget
category analytics
safe-to-spend
forecast confidence
```

## Fixing strategy

Make `BudgetVsActualEngine` the only budget comparison contract.

## Implementation plan

1. Define canonical output:

```kotlin
data class NormalizedBudgetStatus(
    val budgetId: Long,
    val budgetAmount: MoneyAggregate,
    val spentAmount: MoneyAggregate,
    val remainingAmount: MoneyAggregate,
    val isPartial: Boolean,
    val warningMessage: String?
)
```

2. Make `DashboardContractsAdapter.observeBudgetStatuses()` use the canonical engine.

3. Remove/deprecate raw `BudgetStatusSnapshot.spentAmount` where possible.

4. Tests:

```text
budget_status_uses_same_home_currency_as_spending
budget_status_partial_when_budget_or_spend_rate_missing
advanced_category_analytics_deprecated_path_not_used_by_dashboard
```

---

# Issue P2-09 — Silent EUR fallback can hide settings failures

## Severity

P2 / Medium

## Evidence

Multiple paths do:

```kotlin
runCatching { homeCurrency().first() }.getOrDefault("EUR")
```

or fallback to `MultiCurrencyRepository.DEFAULT_HOME_CURRENCY = "EUR"`.

## Impact

If DataStore fails or settings are corrupted, the dashboard silently switches to EUR instead of showing a configuration/data-quality error.

## Fixing strategy

Distinguish default-on-first-run from settings-load failure.

## Implementation plan

1. Add:

```kotlin
sealed interface HomeCurrencyResolution {
    data class Resolved(val currency: CurrencyCode)
    data class DefaultedFirstRun(val currency: CurrencyCode)
    data class Failed(val fallback: CurrencyCode, val error: Throwable)
}
```

2. Add warning when fallback happens after an exception.

3. Surface warning to dashboard/analytics.

4. Tests:

```text
home_currency_datastore_error_adds_dashboard_warning
first_run_default_EUR_does_not_show_error
invalid_home_currency_blocks_analytics_with_warning
```

---

# Issue P2-10 — Deprecated raw aggregate DAO/repository surface remains broad

## Severity

P2 / Medium, P1 regression risk

## Evidence

`ExpenseDao` still exposes many deprecated raw aggregate methods:

```text
getTotalSpentFlow
getTotalForPeriod
getCategoryTotalsBetween
getDailyTotalsForPeriod
getWeeklyTotalsForPeriod
getMonthlyTotalsForPeriod
getAverageDailySpend
getTotalDepositsForPeriod
getMonthlySpendingTotals
getTopMerchantsForPeriod
...
```

They are marked deprecated, but still callable from production code.

## Impact

New code can accidentally reintroduce mixed-currency sums.

## Fixing strategy

Static guard + allowlist.

## Implementation plan

1. Add CI script:

```text
fail if production code calls deprecated raw aggregate DAO methods
outside allowlisted migration/debug files
```

2. Rename dangerous DAO methods with prefix:

```kotlin
unsafeRawGetTotalForPeriod()
```

3. Add `@Deprecated(level = DeprecationLevel.ERROR)` once migration is complete.

4. Tests/guards:

```text
currency_guard_fails_on_getTotalForPeriod_usage
currency_guard_fails_on_getWeeklyTotalsForPeriod_usage
currency_guard_allows_migration_tests_only
```

---

# Issue P2-11 — Category percentages ignore partial-conversion semantics

## Severity

P2 / Medium

## Evidence

`AnalyticsRepository.getCategoryBreakdown()` computes:

```kotlin
val totalSpent = categoryAggregates.values.sumOf { it.displayAmount }
percentage = aggregate.displayAmount / totalSpent
```

If one category has missing/stale conversion, its display amount excludes some transactions. The percentage is then calculated over only successfully converted amounts.

## Impact

The chart can look precise while excluding data:

```text
Food 40%
Travel 60%
```

but actually some Travel USD transactions were excluded.

## Fixing strategy

Category percentages need quality metadata.

## Implementation plan

1. Add to breakdown:

```kotlin
val isPartial: Boolean
val excludedTransactionCount: Int
val excludedBucketCount: Int
val warningMessage: String?
```

2. If any category aggregate is partial, parent breakdown is partial.

3. UI should show:

```text
Percentages based only on convertible transactions.
```

4. Tests:

```text
category_breakdown_partial_when_one_category_rate_missing
category_percentage_warning_when_parent_total_partial
uncategorized_bucket_preserves_partial_warning
```

---

# Issue P2-12 — Forecast/health/savings consumers are not proven to use normalized currency input

## Severity

P2 / Medium, possibly P1 depending on feature path

## Evidence

`ComputeDashboardWidgetsUseCase.computeRunwayAndForecast()` converts dashboard expenses to `ExpenseSnapshot` and passes them into:

```kotlin
forecastInputAssembler.assemble(...)
synthesisEngine.synthesize(...)
```

Those snapshots carry original currency and effective amount, not guaranteed-normalized amounts.

Some spending values are normalized via `MultiCurrencyRepository`, but not all inputs into forecast/block-party/health are clearly normalized.

## Impact

Dashboard secondary widgets can diverge from canonical totals.

## Fixing strategy

Make forecast input assembly require normalized inputs.

## Implementation plan

1. Change `ForecastInputAssembler` API:

```kotlin
assemble(input: NormalizedAnalyticsInput, ...)
```

2. If legacy raw API remains, mark it deprecated/error.

3. Carry data quality into forecast:

```kotlin
ForecastDataQuality(
    isPartial = input.dataQuality.isPartial,
    confidenceMultiplier = input.dataQuality.confidenceMultiplier,
    warnings = input.dataQuality.conversionWarnings
)
```

4. Tests:

```text
forecast_assembler_rejects_mixed_raw_currency_input
partial_currency_data_reduces_forecast_confidence
dashboard_forecast_warning_matches_analytics_warning
```

---

# Recommended fixing order

## PR 1 — Deterministic exchange-rate lookup

Files:

```text
ExchangeRateDao.kt
CurrencyConverter.kt
ExchangeRateStore implementation
CurrencyConverterTest.kt
```

Fix:

```text
- getRate() returns latest deterministic row
- getRateAsOf() remains historical
- add pair/date tests
```

## PR 2 — Historical aggregate contract

Files:

```text
MultiCurrencyRepository.kt
CurrencyConverter.kt
MoneyAggregateBuilder.kt
AnalyticsRepository.kt
TotalsAggregationEngine.kt
```

Fix:

```text
- add historical MoneyAggregate APIs
- dashboard/analytics period totals use convertAsOf
- latest-rate APIs renamed explicitly
```

## PR 3 — Dashboard quality propagation

Files:

```text
DashboardRepositoryContracts.kt
DashboardContractsAdapter.kt
DashboardDataProvider.kt
ComputeDashboardWidgetsUseCase.kt
HomeViewModel.kt
UI cards
```

Fix:

```text
- preserve aggregate/isPartial/warning/source bucket metadata
- show partial dashboard warnings
```

## PR 4 — Fix totals drilldown

Files:

```text
TotalsAggregationEngine.kt
HomeViewModel.kt
PeriodTotal model
```

Fix:

```text
- weekly/daily drilldown uses MultiCurrencyRepository safe APIs
- no empty-list fallback for valid data
```

## PR 5 — Normalize dashboard widget input

Files:

```text
DashboardDataProvider.kt
ComputeDashboardWidgetsUseCase.kt
ForecastInputAssembler.kt
SynthesisEngine.kt
```

Fix:

```text
- build a canonical normalized dashboard input
- trend/forecast/block-party/health use normalized amounts
```

## PR 6 — Stale/missing rate quality propagation

Files:

```text
CurrencyConverter.kt
AnalyticsCurrencyNormalizer.kt
AnalyticsInputAssembler.kt
DataQualityReport.kt
ForecastDataQuality.kt
```

Fix:

```text
- typed conversion failures
- staleRateCount populated
- confidence penalties propagated
```

## PR 7 — Raw aggregate guardrails

Files:

```text
ExpenseDao.kt
scripts/currency_guardrails.*
build.gradle.kts
docs/architecture/CONTRACTS.md
```

Fix:

```text
- CI fails on raw aggregate usage
- deprecated raw methods become ERROR after migration
```

---

# Golden tests to add

```text
exchange_rate_getRate_returns_latest_validDate
historical_conversion_uses_rate_as_of_expense_date
dashboard_month_total_matches_sum_of_historical_daily_buckets
dashboard_category_breakdown_sum_matches_month_total
dashboard_partial_rate_warning_survives_adapter_mapping
dashboard_weekly_drilldown_returns_non_empty_safe_totals
dashboard_daily_drilldown_returns_non_empty_safe_totals
dashboard_spending_trend_does_not_raw_sum_USD_EUR
analytics_summary_preserves_MoneyAggregate_and_isPartial
analytics_quality_counts_missing_rates
analytics_quality_counts_stale_rates
budget_vs_actual_uses_normalized_budget_and_spend
forecast_confidence_reduced_for_partial_currency_data
currency_guard_blocks_getTotalForPeriod_in_production_code
currency_guard_blocks_getWeeklyTotalsForPeriod_in_production_code
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "getTotalForPeriod" app/src/main/java
grep -R "getWeeklyTotalsForPeriod" app/src/main/java
grep -R "getDailyTotalsWithDatesForPeriod" app/src/main/java
grep -R "getCategoryTotalsBetween" app/src/main/java
grep -R "sumOf.*effectiveAmount" app/src/main/java/com/yourname/expensetracker/domain
grep -R "displayAmount" app/src/main/java/com/yourname/expensetracker
grep -R "staleRateCount = 0" app/src/main/java
grep -R "getRate(fromCurrency" app/src/main/java
grep -R "getOrDefault(\"EUR\")" app/src/main/java
```

Allowed raw-money usage should be explicit:

```text
- single-currency tests
- migration/backfill diagnostics
- source bucket construction before MoneyAggregate conversion
```

Everything else should use:

```text
MoneyAggregate
AnalyticsCurrencyNormalizer
NormalizedAnalyticsInput
MultiCurrencyRepository historical aggregate APIs
```

---

# Definition of done

```text
- ExchangeRateDao latest-rate lookup is deterministic.
- Historical dashboard/analytics period totals use convertAsOf or documented historical aggregate logic.
- Dashboard DTOs preserve MoneyAggregate/isPartial/warnings.
- Weekly/daily totals drilldown returns safe converted data, not empty lists.
- Dashboard spending trend, forecast, block-party, and health widgets consume normalized amounts.
- Stale and missing exchange-rate failures are typed and propagated to AnalyticsDataQuality.
- Category percentages show partial-data caveats when any bucket failed conversion.
- Budget-vs-actual uses normalized budget and normalized spend.
- CI blocks new raw aggregate DAO usage.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/docs/architecture/CODEBASE_SEGMENTS.md

- `MoneyAggregate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyAggregateBuilder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

- `CurrencyConverter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt

- `ExchangeRateDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt

- `ExchangeRate.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt

- `MultiCurrencyRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

- `AnalyticsCurrencyNormalizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt

- `AnalyticsInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt

- `NormalizedAnalyticsInput.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt

- `AnalyticsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

- `AdvancedAnalyticsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt

- `TotalsAggregationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt

- `DashboardDataProvider.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt

- `DashboardContractsAdapter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt

- `ComputeDashboardWidgetsUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

- `HomeViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt

- `ExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt