# Pipeline 5 Static Debug Report — Currency / Dashboard / Analytics

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 5 is **significantly better than the old report**, but it is still **not clean**.

Important improvements now exist:

```text
AnalyticsRepository spending summary uses AnalyticsCurrencyNormalizer
category breakdown uses per-expense historical normalization
weekly/daily drilldown no longer returns empty lists
DashboardContractsAdapter propagates some isPartial/warning fields
MultiCurrencyRepository MoneyAggregate helpers now use MoneyAggregateBuilder
AnalyticsInputAssembler now fills staleRateCount
BudgetRepository now attempts currency-aware budget status calculation
ExchangeRateDao.getRate() is deterministic by lastUpdated
```

However, the current implementation still mixes three incompatible contracts:

```text
1. per-expense historical conversion via convertAsOf()
2. latest-rate aggregate conversion via convertMultiple()/convert()
3. raw or fallback Double amounts inside dashboard widgets
```

Highest remaining user-impact risks:

1. **Historical totals are still inconsistent across dashboard/analytics surfaces.**
2. **Weekly/daily drilldowns are no longer empty, but they likely include deposits/transfers because the new MCR weekly/daily methods use type-agnostic expense reads.**
3. **Spending trend can silently add raw foreign-currency amounts when conversion fails.**
4. **Budget-vs-actual still mixes rate bases: budget limit uses historical period-end conversion, while spending uses latest-rate MCR totals.**
5. **Dashboard models only carry partial/warning strings, not full `MoneyAggregate`, source buckets, or conversion failures.**
6. **`ExchangeRateDao.getRate()` is deterministic but can still choose a historical backfill row as the “latest” current rate because it orders only by `lastUpdated`.**
7. **Stale-rate detection exists, but it uses conversion timestamp/`lastUpdated`, not clearly the rate’s `validDate`.**
8. **Silent EUR fallback still exists in several dashboard/currency paths.**

Current status: **yellow/orange**. The core currency primitives are good, but consumption is still partial and some user-visible totals can still be wrong.

---

# Sources checked

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 5 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-5-currency-dashboard-analytics-debug-report.md

- Current code:
  - `ExchangeRateDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt
  - `ExchangeRate.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt
  - `CurrencyConverter.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt
  - `MultiCurrencyRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt
  - `AnalyticsRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt
  - `AnalyticsCurrencyNormalizer.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt
  - `AnalyticsInputAssembler.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt
  - `NormalizedAnalyticsInput.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt
  - `DataQualityReport.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/analytics/DataQualityReport.kt
  - `DashboardContractsAdapter.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt
  - `DashboardDataProvider.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt
  - `ComputeDashboardWidgetsUseCase.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
  - `TotalsAggregationEngine.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt
  - `ExpenseDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
  - `BudgetRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

---

# 1. Tracker reconciliation

Master tracker currently says:

| ID | Tracker status |
|---|---|
| P5-P1-01 | TODO |
| P5-P1-02 | fixed |
| P5-P1-03 | TODO |
| P5-P1-04 | TODO |
| P5-P1-05 | TODO |
| P5-P1-06 | TODO |
| P5-P1-07 | TODO |
| P5-P1-08 | TODO |

My current status:

| ID | My status | Reason |
|---|---:|---|
| P5-P1-01 | **Partial** | `AnalyticsRepository` summary/category now use per-expense historical normalization, but MCR period/month/year/category/merchant APIs still mostly use latest-rate conversion. New `getHomeCurrencyPurchaseTotalHistorical()` uses period midpoint bucket conversion, not per-transaction conversion. |
| P5-P1-02 | **Mostly fixed / caveat** | `getRate()` is now deterministic, but orders by `lastUpdated`, not `validDate DESC, lastUpdated DESC`. Historical backfills can still poison “latest” current-rate semantics. |
| P5-P1-03 | **Partial** | Dashboard adapter propagates `isPartial` and warning strings, but still drops full `MoneyAggregate`, source buckets, and conversion failures. `AnalyticsRepository.SpendingSummary.aggregate` is emitted as `null`. |
| P5-P1-04 | **Mostly fixed / new bug** | Weekly/daily drilldown now uses MCR and returns data. But the new weekly/daily MCR methods use type-agnostic `getExpensesBetweenUncapped()`, so drilldown can include deposits/transfers. |
| P5-P1-05 | **Partial** | Spending trend now attempts conversion, but uses latest rate and falls back to raw amount on conversion failure. Forecast/block-party/dashboard context still receives raw dashboard expenses. |
| P5-P1-06 | **Partial / mostly fixed** | `staleRateCount` is no longer hardcoded to 0. But stale detection depends on conversion timestamp/`lastUpdated`, not clearly `validDate`, and conversion failures are not fully typed end-to-end. |
| P5-P1-07 | **Mostly fixed** | MCR `MoneyAggregate` helpers now use `MoneyAggregateBuilder` with transaction counts. Legacy `Result<Double>` paths still use `convertMultiple()` / exception style. |
| P5-P1-08 | **Partial** | Budget status is currency-aware, but spend and budget limit use different FX bases; budget partial/warning metadata is mostly lost before dashboard UI. |

---

# 2. Original issue evaluation

## P5-P1-01 — Historical totals use latest-rate aggregate conversion

### Current state

Partially improved.

Good:

- `AnalyticsRepository.getSpendingSummary()` now fetches period expenses and calls `AnalyticsCurrencyNormalizer.normalizeExpenses(...)`.
- The normalizer converts non-home-currency expenses via `currencyConverter.convertAsOf(..., atMillis = expense.date)`.
- `AnalyticsRepository.getCategoryBreakdown()` also uses normalized per-expense amounts, so category totals and summary totals are now on the same historical basis in that path.

Still problematic:

- `MultiCurrencyRepository.getHomeCurrencyPurchaseTotal()` still uses latest-rate conversion.
- `getHomeCurrencyPurchaseCategoryTotals()` still uses latest-rate conversion.
- `getHomeCurrencyPurchaseMonthlyTotals()` still uses latest-rate conversion.
- `TotalsAggregationEngine.getMonthlyTotals()` uses latest-rate monthly MCR totals.
- `TotalsAggregationEngine.getYearlyTotals()` uses latest-rate purchase total.
- `BudgetRepository.getAggregateSpent()` uses latest-rate MCR spend totals.
- New `getHomeCurrencyPurchaseTotalHistorical()` exists, but it converts each currency bucket at the **period midpoint**, not each transaction at its own date. That is not equivalent for a month/year with volatile rates.

### User impact

A user can still see inconsistent numbers for the same period:

```text
Dashboard month summary      -> per-expense historical conversion
Totals monthly chart         -> latest-rate bucket conversion
Yearly totals                -> latest-rate bucket conversion
Budget spent                 -> latest-rate bucket conversion
Category analytics screen    -> per-expense historical conversion
```

### Classification

Actual user-visible financial correctness bug.

### Fix strategy

Define explicit rate-basis APIs:

```kotlin
enum class RateBasis {
    HISTORICAL_TRANSACTION_DATE,
    HISTORICAL_PERIOD_MIDPOINT,
    LATEST_AVAILABLE
}
```

Preferred canonical period reporting:

```kotlin
suspend fun getPurchaseAggregate(
    start: Long,
    end: Long,
    rateBasis: RateBasis = HISTORICAL_TRANSACTION_DATE
): MoneyAggregate
```

Implementation options:

1. **Correct first:** fetch rows and convert per expense with `convertAsOf(expense.date)`.
2. **Optimized later:** group by `(day, currency)` or `(validRateWindow, currency)` and convert bucket/date pairs.

Do not call the midpoint implementation “historical total” unless the UI label explicitly says “period midpoint estimate.”

---

## P5-P1-02 — `ExchangeRateDao.getRate()` ambiguous with historical rows

### Current state

The old “arbitrary row” bug is improved:

```sql
ORDER BY lastUpdated DESC LIMIT 1
```

So it is deterministic.

Remaining issue:

Current-rate lookup should not be defined by “most recently updated row” when the table supports historical rows by `validDate`.

Example failure:

```text
USD->EUR current row: validDate = 2026-05-17, lastUpdated = 2026-05-17
Historical backfill row: validDate = 2024-01-01, lastUpdated = 2026-05-18
getRate() returns the 2024 historical row because it was inserted later.
```

Also, `ExchangeRate.validDate` defaults to `0L`. If API/manual store paths do not set it consistently, `convertAsOf()` can behave like “use a timeless rate for every date.”

### Classification

Mostly fixed for determinism, not fully fixed for semantics.

### Fix strategy

Split the DAO methods:

```kotlin
getLatestRateForPair(from, to)
-- ORDER BY validDate DESC, lastUpdated DESC

getMostRecentlyUpdatedRateForPair(from, to)
-- ORDER BY lastUpdated DESC

getRateAsOf(from, to, at)
-- WHERE validDate <= at ORDER BY validDate DESC, lastUpdated DESC
```

Add policy for `validDate = 0L`:

```text
Either migrate to validDate = startOfDay(lastUpdated)
or exclude validDate=0 from historical lookup unless no dated rate exists.
```

---

## P5-P1-03 — Dashboard adapter drops `MoneyAggregate` and partial warnings

### Current state

Partially fixed.

Good:

- Dashboard `SpendingSummary` has `isPartial` and `warningMessage`.
- `DashboardCategoryBreakdown` has `isPartial` and `warningMessage`.
- `DashboardContractsAdapter.observeSpendingSummary()` passes `isPartial`.
- `observeCategoryBreakdown()` passes category warning fields.

Still missing:

- Dashboard model does not carry `MoneyAggregate`.
- Source buckets are lost.
- Conversion failure details are lost.
- `AnalyticsRepository.SpendingSummary.aggregate` is emitted as `null`.
- The repository computes a `warningMessage` local variable but does not include it in its data class.
- Dashboard adapter falls back to a generic warning because `summary.aggregate` is null.

### User impact

The system may know that a total excluded 5 USD transactions, but the dashboard can only show a generic warning, or no specific reason.

### Fix strategy

Create compact UI contract:

```kotlin
data class CurrencyQualityUi(
    val isPartial: Boolean,
    val warningMessage: String?,
    val missingRateCount: Int,
    val staleRateCount: Int,
    val invalidCurrencyCount: Int,
    val sourceBuckets: List<CurrencyBucketUi>
)
```

Add this to:

- dashboard summary,
- period totals,
- category breakdown,
- budget status,
- forecast widgets,
- health widgets.

---

## P5-P1-04 — Weekly/daily totals drilldown functionally broken

### Current state

Original issue mostly fixed.

`TotalsAggregationEngine.getWeeklyTotals()`, `getDailyTotals()`, and `getDailyTotalsForRange()` now call:

```kotlin
multiCurrencyRepository.getHomeCurrencyWeeklyTotals(...)
multiCurrencyRepository.getHomeCurrencyDailyTotals(...)
```

They no longer return unconditional empty lists.

New serious issue:

`MultiCurrencyRepository.getHomeCurrencyWeeklyTotals()` and `getHomeCurrencyDailyTotals()` read:

```kotlin
expenseDao.getExpensesBetweenUncapped(startDate, endDate)
```

That DAO method is type-agnostic. It does not filter `transactionType = PURCHASE`.

But `TotalsAggregationEngine.getMonthlyTotals()` uses purchase-only monthly totals.

### User impact

A monthly total can show purchases only, while weekly/daily drilldown can include:

```text
deposits
transfers
withdrawals
unknown transaction types
```

So drilldown can exceed or disagree with parent totals.

### Fix strategy

Add purchase-only methods:

```kotlin
getHomeCurrencyPurchaseWeeklyTotals(start, end)
getHomeCurrencyPurchaseDailyTotals(start, end)
```

Use:

```kotlin
expenseDao.getExpensesByTypeBetween(start, end, ExpenseDao.SPENDING_TYPE)
```

or an equivalent per-day/per-week purchase-only grouped query.

Acceptance:

```text
monthly_total == sum(weekly_purchase_totals)
weekly_total == sum(daily_purchase_totals)
deposits_do_not_enter_spending_drilldown
transfers_do_not_enter_spending_drilldown
```

---

## P5-P1-05 — Dashboard widgets still raw-sum `DashboardExpense.effectiveAmount`

### Current state

Partial.

Good:

- Today/week headline widgets use `MultiCurrencyRepository`.
- `computeSpendingTrend()` now attempts to convert each non-home-currency expense.

Still unsafe:

1. `computeSpendingTrend()` uses latest-rate `currencyConverter.convert(...)`, not historical `convertAsOf(exp.date)`.
2. If conversion fails, it falls back to:

```kotlin
exp.effectiveAmount
```

That silently adds raw foreign-currency amounts into a home-currency trend.

3. `DashboardDataProvider` injects `AnalyticsCurrencyNormalizer`, but does not use it in `getAllDataFlow()` or `getProcessedDataFlow()`.
4. `computeRunwayAndForecast()` still builds raw `ExpenseSnapshot` objects from dashboard expenses. `ForecastInputAssembler` may normalize internally, but the contract still accepts raw snapshots.
5. `computeBlockParty()` passes raw transaction summaries for top transaction display/logic.
6. `PeriodSummary` widget marks `isPartial = ctx.periodIsPartial`, which only covers today/week aggregates, not the month summary.

### User impact

Widgets can disagree:

```text
month summary = historical normalized
trend = latest-rate normalized, or raw fallback on missing rates
budget/block-party/forecast = depends on downstream normalization
period summary warning = can hide partial month data
```

### Fix strategy

Build canonical dashboard input:

```kotlin
data class DashboardNormalizedInput(
    val homeCurrency: String,
    val period: PeriodRange,
    val expenses: List<NormalizedExpense>,
    val dataQuality: AnalyticsDataQuality
)
```

Dashboard widgets should consume this for all arithmetic.

Never do:

```kotlin
conversion ?: rawAmount
```

Instead:

```text
exclude unconvertible tx from aggregate
mark widget partial
show missing-rate warning
```

---

## P5-P1-06 — Stale-rate state not propagated to analytics quality

### Current state

Mostly improved, but not perfect.

Good:

- `AnalyticsInputAssembler` now sets:
  - `staleRateCount`
  - `missingRateCount`
  - `invalidCurrencyCount`
  - `confidencePenalty`
  - `confidenceMultiplier`
- `DataQualityReport.fromNormalization()` applies a stale-rate penalty.
- `AnalyticsCurrencyNormalizer` can emit `STALE_EXCHANGE_RATE`.

Remaining problems:

1. Stale detection compares `expense.date - conversion.timestamp`.
2. `conversion.timestamp` comes from `ConversionResult.timestamp`, which is set from `ExchangeRate.lastUpdated`, not necessarily the historical `validDate`.
3. If historical rates are imported today for a 2024 date, `lastUpdated` can be 2026 while `validDate` is 2024. Stale detection may be wrong.
4. `convertAsOf()` returns nullable `ConversionResult`; it does not return a typed failure explaining whether the miss was:
   - no historical rate,
   - stale fallback,
   - invalid currency,
   - composite-leg missing.
5. `missingWarnings` in `AnalyticsInputAssembler` counts warning categories, not necessarily affected transaction count.

### Fix strategy

Change conversion contract:

```kotlin
sealed interface ConversionOutcome {
    data class Converted(
        val result: ConversionResult,
        val rateValidDate: Long?,
        val rateLastUpdated: Long?,
        val basis: RateBasis
    ) : ConversionOutcome

    data class Failed(
        val type: ConversionFailureType,
        val from: String,
        val to: String,
        val amount: Double,
        val atMillis: Long?
    ) : ConversionOutcome
}
```

Analytics should use `rateValidDate` for stale historical warnings.

---

## P5-P1-07 — `MultiCurrencyRepository` inconsistent `MoneyAggregateBuilder` use

### Current state

Mostly fixed.

Good:

- `aggregateToMoneyAggregate()` now delegates to `MoneyAggregateBuilder.fromBuckets(...)`.
- `aggregateCurrencyTotalsToMoneyAggregate()` also delegates to `MoneyAggregateBuilder`.
- Transaction counts from DAO buckets are passed into the builder.

Remaining caveat:

Older methods returning `Result<Double>` still use `convertMultiple()` and throw `MissingExchangeRateException`, losing partial aggregate data.

Examples:

```text
getTotalExpensesInHomeCurrency()
getCategoryTotalsInHomeCurrency()
getMerchantTotalsInHomeCurrency()
getMonthlyTotalsInHomeCurrency()
```

These should either be deleted or renamed as legacy.

### Fix strategy

Make `MoneyAggregate` the only financial aggregate return type. Move `Result<Double>` APIs behind `@Deprecated(ERROR)` or make them private.

---

## P5-P1-08 — Budget-vs-actual comparisons not fully normalized

### Current state

Partial.

Good:

- `BudgetRepository.getBudgetStatuses()` no longer depends on raw expense sums.
- Budget limit conversion exists.
- Category and whole-wallet spending use MCR aggregates.

Still problematic:

1. Budget limit is converted via `convertBudgetAmountToHomeCurrencyAsOf(... periodEnd)`.
2. Spend uses `multiCurrencyRepository.getHomeCurrencyPurchaseTotal()` / category totals, which are **latest-rate** aggregates.
3. Code comments claim expenses are transaction-date converted, but current MCR spend APIs are latest-rate.
4. If budget limit conversion fails, the code forces `percent = 0f` and `health = ON_TRACK`, relying on partial flags to explain unreliability.
5. Dashboard `BudgetStatusSnapshot` mapping drops `isPartial` and `conversionWarning`.

### User impact

A budget can appear:

```text
ON_TRACK
0% used
```

even when the real status is unknown because the budget limit could not be converted.

Or utilization can be numerically wrong because spend and limit use different FX basis.

### Fix strategy

Create canonical output:

```kotlin
data class NormalizedBudgetStatus(
    val budgetId: Long,
    val budgetLimit: MoneyAggregate,
    val spent: MoneyAggregate,
    val remaining: MoneyAggregate?,
    val percentUsed: Double?,
    val status: BudgetHealthStatus,
    val isPartial: Boolean,
    val warningMessage: String?
)
```

If budget limit conversion fails:

```text
percentUsed = null
status = UNKNOWN / UNRELIABLE
```

Do not show `ON_TRACK`.

---

# 3. New/current issues found

## P5-NEW-01 — Weekly/daily drilldown includes non-spending transaction types

### Severity

P1.

### Evidence

`TotalsAggregationEngine.getWeeklyTotals()` and daily methods now call MCR weekly/daily methods.

Those MCR methods read `expenseDao.getExpensesBetweenUncapped(...)`, which is type-agnostic.

### Impact

Deposits/transfers can enter spending drilldown.

### Fix

Use purchase-only weekly/daily aggregate methods.

---

## P5-NEW-02 — Spending trend silently raw-sums on conversion failure

### Severity

P1.

### Evidence

`computeSpendingTrend()` does:

```kotlin
currencyConverter.convert(...)?.convertedAmount ?: exp.effectiveAmount
```

### Impact

If USD→EUR rate is missing, the chart adds raw USD as if it were EUR.

### Fix

Do not fallback to raw amount. Exclude and mark partial.

---

## P5-NEW-03 — PeriodSummary partial flag ignores partial month summary

### Severity

P1/P2.

### Evidence

`CompiledDashboardData.isPartial` includes:

```kotlin
ctx.data.summary.isPartial || ctx.periodIsPartial
```

But `DashboardWidget.PeriodSummary` uses only:

```kotlin
isPartial = ctx.periodIsPartial
```

`ctx.periodIsPartial` only covers today/week MCR aggregates.

### Impact

Month total can be partial while the period summary card says it is not.

### Fix

```kotlin
isPartial = ctx.data.summary.isPartial || ctx.periodIsPartial
```

Also pass warning count/message.

---

## P5-NEW-04 — `getHomeCurrencyPurchaseTotalHistorical()` is midpoint-based, not transaction-date historical

### Severity

P1.

### Evidence

It groups by currency and converts each currency bucket at:

```kotlin
midpoint = startDate + (endDate - startDate) / 2
```

### Impact

For monthly/yearly ranges, this can be materially different from transaction-date conversion.

### Fix

Rename to `getHomeCurrencyPurchaseTotalPeriodMidpointEstimate()` or implement true per-transaction/date-bucket historical conversion.

---

## P5-NEW-05 — Latest-rate lookup can be poisoned by historical backfill

### Severity

P1/P2.

### Evidence

`ExchangeRateDao.getRate()` orders only by `lastUpdated DESC`.

### Impact

Backfilled historical data inserted later can become the “latest” rate.

### Fix

Order current-rate lookup by `validDate DESC, lastUpdated DESC`.

---

## P5-NEW-06 — Budget dashboard drops partial/warning state

### Severity

P1/P2.

### Evidence

`BudgetRepository.BudgetStatus` has `isPartial` and `conversionWarning`, but `DashboardContractsAdapter.observeBudgetStatuses()` maps into `BudgetStatusSnapshot` without those fields.

### Impact

Budget card can show unreliable status without warning.

### Fix

Extend `BudgetStatusSnapshot` with:

```kotlin
isPartial
conversionWarning
percentReliable: Boolean
```

---

## P5-NEW-07 — Stale-rate detection likely uses wrong timestamp

### Severity

P2.

### Evidence

`AnalyticsCurrencyNormalizer` compares expense date with conversion timestamp. `CurrencyConverter.convertAsOf()` returns timestamp from rate `lastUpdated`, not clearly the historical `validDate`.

### Impact

Stale historical rates can be missed or falsely reported.

### Fix

Return both `rateValidDate` and `rateLastUpdated`.

---

## P5-NEW-08 — Silent EUR fallback remains

### Severity

P2, P1 for non-EUR users if settings fail.

### Evidence

Several paths still do:

```kotlin
runCatching { homeCurrency().first() }.getOrDefault("EUR")
```

or equivalent fallback in MCR/Analytics/Dashboard.

### Impact

If DataStore/settings fail, user sees EUR totals silently.

### Fix

Create:

```kotlin
sealed interface HomeCurrencyResolution {
    data class Resolved(...)
    data class DefaultedFirstRun(...)
    data class Failed(...)
}
```

Failed resolution should produce a dashboard warning or block financial totals.

---

## P5-NEW-09 — TotalsAggregationEngine monthly/yearly drops partial warnings

### Severity

P2.

### Evidence

Monthly/yearly methods use `aggregate.displayAmount` and transaction count but do not set `PeriodTotal.isPartial` / `warningMessage`.

Weekly/daily do set those fields.

### Impact

A monthly chart can hide conversion failures while weekly/day rows show them.

### Fix

Propagate aggregate quality in all `PeriodTotal` constructors.

---

## P5-NEW-10 — Historical fallback classified as `RATE_STALE`

### Severity

P2.

### Evidence

`getHomeCurrencyPurchaseTotalHistorical()` falls back to latest rate when no historical rate exists and adds a failure with `FailureReason.RATE_STALE`.

### Impact

Missing historical rate and stale latest rate are different conditions. UI/debug can mislead the user.

### Fix

Add failure reason:

```text
MISSING_HISTORICAL_RATE_USED_LATEST
```

or keep separate fields:

```text
requestedBasis = HISTORICAL_TRANSACTION_DATE
actualBasis = LATEST_AVAILABLE
```

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize these:

1. **Weekly/daily drilldown can include deposits/transfers.**
2. **Spending trend silently raw-sums unconverted foreign amounts.**
3. **Budget status mixes FX bases and can show ON_TRACK when conversion failed.**
4. **Historical/month/year totals still use latest rates in common paths.**
5. **PeriodSummary can hide partial month data.**
6. **Latest exchange-rate lookup can select backfilled historical rows.**
7. **Dashboard budget cards lose partial/conversion warnings.**
8. **Stale-rate warnings may be wrong because they use `lastUpdated`, not `validDate`.**
9. **Silent EUR fallback can misstate all dashboard totals.**

## Architectural / cleanup work

Important but lower immediate severity:

1. Create one canonical normalized dashboard input.
2. Make rate basis explicit in every aggregate API.
3. Replace `Result<Double>` aggregate APIs with `MoneyAggregate`.
4. Add conversion outcome sealed type.
5. Add currency-quality UI DTO.
6. Add CI/static guard for raw aggregate usage.
7. Remove stale comments claiming raw paths still exist or fixed paths are historical when they are latest-rate.
8. Build optimized historical date/currency bucket queries.

---

# 5. Recommended implementation plan

## PR 1 — Fix rate lookup semantics

### Goal

Current-rate and historical-rate lookups are unambiguous.

### Files

- `ExchangeRateDao.kt`
- `CurrencyConverter.kt`
- exchange-rate store implementation
- migration/tests

### Tasks

1. Add:
   - `getLatestRateForPair()`
   - `getMostRecentlyUpdatedRateForPair()`
   - `getRateAsOf()`
2. Latest current rate orders by:
   ```sql
   validDate DESC, lastUpdated DESC
   ```
3. Backfill `validDate = startOfDay(lastUpdated)` where validDate is 0, if safe.
4. Return `rateValidDate` in conversion result.

### Acceptance tests

```text
latest_rate_uses_highest_validDate_not_lastUpdated
historical_backfill_does_not_poison_current_rate
rate_as_of_uses_validDate_lte_requested_date
validDate_zero_policy_is_explicit
```

---

## PR 2 — Canonical historical aggregate contract

### Goal

All period/reporting totals use the same rate basis.

### Files

- `MultiCurrencyRepository.kt`
- `AnalyticsRepository.kt`
- `TotalsAggregationEngine.kt`
- `BudgetRepository.kt`

### Tasks

1. Add true historical per-expense/date-bucket aggregate APIs.
2. Rename current latest-rate APIs with `LatestRate` suffix.
3. Replace dashboard month/year/category period callsites with historical APIs.
4. Keep latest-rate only for explicit “current valuation” cards.

### Acceptance tests

```text
month_summary_matches_sum_of_historical_daily_totals
year_total_uses_transaction_date_rates
category_breakdown_sum_matches_parent_total
latest_rate_card_explicitly_uses_latest_rate
```

---

## PR 3 — Fix weekly/daily drilldown semantics

### Goal

Drilldown matches parent purchase totals.

### Files

- `MultiCurrencyRepository.kt`
- `ExpenseDao.kt`
- `TotalsAggregationEngine.kt`

### Tasks

1. Add purchase-only weekly/daily APIs.
2. Use `getExpensesByTypeBetween(..., PURCHASE)` or grouped SQL.
3. Propagate `isPartial` and warning in all period totals.
4. Decide whether daily drilldown should zero-fill days.

### Acceptance tests

```text
weekly_drilldown_excludes_deposits
daily_drilldown_excludes_transfers
sum_weekly_equals_monthly_purchase_total
sum_daily_equals_weekly_purchase_total
monthly_period_total_propagates_partial_warning
```

---

## PR 4 — Dashboard currency-quality propagation

### Goal

Every dashboard amount knows whether it is complete.

### Files

- dashboard model files
- `DashboardContractsAdapter.kt`
- `AnalyticsRepository.kt`
- UI cards

### Tasks

1. Add `CurrencyQualityUi`.
2. Add to:
   - `SpendingSummary`
   - `DashboardCategoryBreakdown`
   - `BudgetStatusSnapshot`
   - `DashboardWidget.PeriodSummary`
   - `SafeToSpend`
   - `SpendingTrend`
3. Include source buckets where useful.
4. Stop using generic warning when precise conversion failures exist.

### Acceptance tests

```text
summary_preserves_missing_rate_count
category_breakdown_preserves_conversion_warning
budget_widget_shows_conversion_warning
period_summary_marks_partial_when_month_summary_partial
```

---

## PR 5 — Normalize dashboard widget input and remove raw fallback

### Goal

No dashboard widget does arithmetic on raw mixed currencies.

### Files

- `DashboardDataProvider.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- `ForecastInputAssembler.kt`
- `SynthesisEngine.kt`

### Tasks

1. Build `DashboardNormalizedInput`.
2. `computeSpendingTrend()` uses normalized historical amounts.
3. Missing conversion excludes transaction and marks trend partial.
4. `computeRunwayAndForecast()` accepts normalized input or verifies assembler normalization.
5. `computeBlockParty()` uses normalized daily values and normalized expense summaries.

### Acceptance tests

```text
spending_trend_does_not_raw_fallback_when_rate_missing
spending_trend_uses_expense_date_rate
forecast_receives_home_currency_snapshots
block_party_uses_normalized_daily_spending
dashboard_data_provider_uses_analytics_normalizer
```

---

## PR 6 — Budget-vs-actual currency contract

### Goal

Budget and spend use one rate basis and unreliable statuses are not shown as ON_TRACK.

### Files

- `BudgetRepository.kt`
- `BudgetStatus.kt`
- `BudgetStatusSnapshot.kt`
- dashboard budget UI

### Tasks

1. Use historical spend aggregate for period budgets.
2. Convert budget limit using same documented basis.
3. If budget limit conversion fails:
   - `percentUsed = null`
   - `healthStatus = UNKNOWN` or `UNRELIABLE`
4. Preserve `isPartial` and warning through dashboard adapter.

### Acceptance tests

```text
budget_spend_and_limit_use_same_rate_basis
budget_limit_conversion_failure_does_not_show_on_track
budget_status_snapshot_preserves_partial_warning
budget_category_status_uses_normalized_category_spend
```

---

## PR 7 — Typed conversion outcome

### Goal

Missing/stale/invalid/fallback cases are distinguishable.

### Files

- `CurrencyConverter.kt`
- `AnalyticsCurrencyNormalizer.kt`
- `AnalyticsInputAssembler.kt`
- `DataQualityReport.kt`
- `MoneyAggregateBuilder.kt`

### Tasks

1. Add `ConversionOutcome`.
2. Include:
   - failure type,
   - requested basis,
   - actual basis,
   - validDate,
   - lastUpdated.
3. Use affected transaction counts, not warning-category counts.
4. Update confidence penalties.

### Acceptance tests

```text
missing_rate_count_uses_affected_transaction_count
stale_rate_count_uses_validDate
historical_missing_latest_fallback_has_distinct_warning
forecast_confidence_reduced_by_partial_currency_data
```

---

## PR 8 — Raw aggregate guardrails

### Goal

No new raw mixed-currency sum reaches production code.

### Files

- `ExpenseDao.kt`
- build scripts / CI guard
- docs

### Tasks

1. Keep dangerous DAO methods as `DeprecationLevel.ERROR`.
2. Add CI grep/Detekt rule for:
   - `getTotalForPeriod`
   - `getWeeklyTotalsForPeriod`
   - `getDailyTotalsWithDatesForPeriod`
   - `sumOf { it.effectiveAmount }`
   - `?: exp.effectiveAmount` after conversion failure.
3. Allow only migration/debug/test paths.

### Acceptance tests / guards

```text
currency_guard_fails_on_raw_total_dao_usage
currency_guard_fails_on_conversion_or_raw_fallback
currency_guard_allows_source_bucket_construction
```

---

# 6. Suggested tracker updates

Update Pipeline 5 tracker:

| ID | Suggested status |
|---|---|
| P5-P1-01 | Partial |
| P5-P1-02 | Mostly fixed / validDate caveat |
| P5-P1-03 | Partial |
| P5-P1-04 | Mostly fixed / new non-spending bug |
| P5-P1-05 | Partial |
| P5-P1-06 | Partial / mostly fixed |
| P5-P1-07 | Mostly fixed |
| P5-P1-08 | Partial |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P5-NEW-01 | P1 | Weekly/daily drilldown includes non-spending transaction types |
| P5-NEW-02 | P1 | Spending trend silently raw-sums on conversion failure |
| P5-NEW-03 | P1/P2 | PeriodSummary partial flag ignores partial month summary |
| P5-NEW-04 | P1 | Historical aggregate API uses midpoint bucket conversion, not transaction-date conversion |
| P5-NEW-05 | P1/P2 | Latest-rate lookup can be poisoned by historical backfill |
| P5-NEW-06 | P1/P2 | Budget dashboard drops partial/warning state |
| P5-NEW-07 | P2 | Stale-rate detection likely uses wrong timestamp |
| P5-NEW-08 | P2/P1 | Silent EUR fallback remains |
| P5-NEW-09 | P2 | Monthly/yearly totals drop partial warnings |
| P5-NEW-10 | P2 | Historical fallback classified as stale instead of missing-historical fallback |

---

# 7. Golden tests for Pipeline 5

Add or verify:

```text
exchange_rate_latest_uses_validDate_not_backfill_lastUpdated
exchange_rate_asOf_uses_validDate_lte_expense_date
historical_month_total_uses_each_expense_date_rate
historical_month_total_matches_sum_of_daily_historical_totals
mcr_midpoint_estimate_not_used_by_dashboard_period_total
dashboard_summary_and_category_use_same_rate_basis
weekly_drilldown_excludes_deposits
daily_drilldown_excludes_transfers
weekly_sum_matches_month_purchase_total
daily_sum_matches_week_purchase_total
spending_trend_uses_expense_date_rate
spending_trend_missing_rate_marks_partial_not_raw_fallback
period_summary_partial_when_month_summary_partial
dashboard_budget_status_preserves_conversion_warning
budget_conversion_failure_status_unknown_not_on_track
budget_spend_and_limit_use_same_rate_basis
analytics_quality_counts_stale_using_validDate
analytics_quality_counts_missing_by_affected_transactions
home_currency_datastore_failure_surfaces_warning_not_silent_EUR
raw_aggregate_guard_blocks_getTotalForPeriod
raw_aggregate_guard_blocks_sumOf_effectiveAmount_dashboard
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "getHomeCurrencyPurchaseTotal(" app/src/main/java
grep -R "getHomeCurrencyPurchaseMonthlyTotals" app/src/main/java
grep -R "getHomeCurrencyWeeklyTotals" app/src/main/java
grep -R "getHomeCurrencyDailyTotals" app/src/main/java
grep -R "getExpensesBetweenUncapped" app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt
grep -R "currencyConverter.convert(" app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard
grep -R "?: exp.effectiveAmount" app/src/main/java
grep -R "sumOf.*effectiveAmount" app/src/main/java
grep -R "getOrDefault(\"EUR\")" app/src/main/java
grep -R "aggregate = null" app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt
grep -R "warningMessage" app/src/main/java/com/yourname/expensetracker/domain/model/dashboard
grep -R "validDate" app/src/main/java/com/yourname/expensetracker
```

Allowed raw money usage should be explicit:

```text
- constructing source buckets before conversion
- displaying original transaction row amounts
- single-currency test fixtures
- migration/debug diagnostics
```

Definition of done:

```text
- Every period/reporting total declares and uses a rate basis.
- Dashboard period totals use transaction-date historical conversion.
- Weekly/daily drilldown is purchase-only and matches parent totals.
- No dashboard widget falls back to raw amount on conversion failure.
- Dashboard summary/category/budget/period widgets carry currency quality.
- Budget status does not show reliable ON_TRACK when conversion failed.
- Latest exchange-rate lookup uses validDate semantics.
- Stale-rate detection uses rate validDate.
- Silent EUR fallback is replaced by explicit HomeCurrencyResolution.
- CI blocks raw mixed-currency aggregate usage.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Fix weekly/daily drilldown transaction-type bug** — fast, direct user correctness issue.
2. **Remove raw fallback in spending trend** — prevents silent mixed-currency charts.
3. **Fix PeriodSummary partial propagation** — small UI correctness patch.
4. **Fix exchange-rate latest/current lookup semantics** — prevents poisoned rates.
5. **Introduce canonical historical aggregate API and migrate period totals.**
6. **Fix budget-vs-actual rate-basis mismatch and warning propagation.**
7. **Build normalized dashboard input and migrate widgets.**
8. **Typed conversion outcome + validDate-based stale detection.**
9. **Replace silent EUR fallback with explicit resolution.**
10. **Add CI guardrails for raw aggregate usage.**