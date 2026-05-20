# Deep Evaluation / Debugging Report — Currency Normalization

Commit reviewed:

```text
3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69
feat: currency normalization post-9a6afc4 fixes (CURR-9A6)
```

Source:

```text
https://github.com/panospao7/Cost-agregator/commit/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69
```

---

## 0. Executive verdict

This commit fixes several of the previously reported issues, but it still does **not fully close global currency issue #4**.

### Good progress

The commit does correctly improve:

```text
1. getHomeCurrencyPurchaseTotalHistorical()
   Now uses MoneyNormalizationEngine.aggregateExpenses(... TRANSACTION_DATE)
   instead of the previous midpoint/latest fallback path.

2. DashboardNormalizedInputResult
   Adds typed Available / Unavailable result, which is the correct direction.

3. SpendingTrend
   Adds currencyQuality when conversion rows are dropped.

4. BudgetForecastingEngine
   Removes fake "EUR" from the home-currency-unavailable branch.

5. MoneyNormalizationEngine
   Adds LatestDefault stale policy for LATEST_AVAILABLE row normalization.

6. getHomeCurrencyTotal()
   Raised to DeprecationLevel.ERROR.

7. verify_money_boundaries.py
   Adds fake-EUR and raw-ExpenseSnapshot rules.

8. Docs
   Updated to describe unavailable state and staleness policy.
```

### Main remaining problems

However, several important issues remain:

```text
1. MultiCurrencyRepository now uses CurrencyCode("") in an unavailable path.
   This will throw because CurrencyCode requires exactly 3 uppercase letters.

2. DashboardNormalizedInputResult exists but compute() does not use it.
   CompiledDashboardData.normalizedInput is never populated.

3. Dashboard widgets still use raw/legacy values:
   category totals, month total, safe-to-spend, Monte Carlo, runway, forecast.

4. Forecast/runway still builds raw ExpenseSnapshot values.
   The commit only documents this as TODO.

5. verify_money_boundaries.py likely fails the current code because G-MONEY-10
   flags the still-existing raw ExpenseSnapshot path.

6. SpendingTrend quality improved, but it still throws on home-currency failure
   and does not use the canonical normalized input.

7. Latest-rate staleness is applied only in MoneyNormalizationEngine.normalizeExpense().
   Bucket/legacy aggregate paths still use StaleRatePolicy.None or convertMultiple.

8. Tests remain mostly fake/unit tests, not live dashboard/repository/Room behavior.
```

So the commit is a **meaningful partial fix**, but global currency normalization should remain open.

---

# 1. Confirmed improvements

## 1.1 Historical purchase total normal path is improved

File:

```text
MultiCurrencyRepository.kt
```

The normal path for:

```kotlin
getHomeCurrencyPurchaseTotalHistorical(...)
```

now does:

```kotlin
normalizationEngine.aggregateExpenses(
    expenses = expenses,
    homeCurrency = homeCurrency,
    rateBasis = RateBasis.TRANSACTION_DATE,
    transactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
)
```

This is a real fix.

The earlier midpoint-bucket conversion and latest fallback are no longer visible in this method.

### Status

```text
Mostly fixed for the happy path.
```

But see `CURR-3E8-01`: the home-currency failure path is broken.

---

## 1.2 Dashboard unavailable model added

File:

```text
DashboardNormalizedInput.kt
```

New type:

```kotlin
sealed interface DashboardNormalizedInputResult {
    data class Available(val input: DashboardNormalizedInput) : DashboardNormalizedInputResult

    data class Unavailable(
        val reason: String,
        val periodStart: Long,
        val periodEnd: Long
    ) : DashboardNormalizedInputResult
}
```

This is the correct architecture direction.

### Status

```text
Model added, but not fully integrated.
```

---

## 1.3 SpendingTrend now exposes partial quality

File:

```text
ComputeDashboardWidgetsUseCase.kt
```

The trend now increments `totalExcluded` when conversion fails and returns:

```kotlin
DashboardWidget.SpendingTrend(
    series = trendSeries,
    currencyQuality = quality
)
```

This is better than silently dropping rows.

### Status

```text
Partially fixed.
```

Remaining gaps:
- no typed unavailable handling on home-currency failure,
- no failure-type metadata,
- still not fed by canonical normalized input.

---

## 1.4 Budget forecast no longer uses fake EUR on unavailable home currency

File:

```text
BudgetForecastingEngine.kt
```

Changed:

```kotlin
currency = "EUR"
```

to:

```kotlin
currency = ""
```

This avoids presenting a failed forecast as EUR.

### Status

```text
Partial fix.
```

Still needs a typed unavailable/currency-status model.

---

## 1.5 Latest-rate stale policy added

Files:

```text
StaleRatePolicy.kt
MoneyNormalizationEngine.kt
```

Added:

```kotlin
StaleRatePolicy.LatestDefault
```

and `MoneyNormalizationEngine.normalizeExpense(...)` uses it when:

```kotlin
rateBasis == RateBasis.LATEST_AVAILABLE
```

### Status

```text
Partial fix.
```

This does not cover builder/bucket/latest repository aggregate paths.

---

# 2. High-priority remaining issues

---

## CURR-3E8-01 — `CurrencyCode("")` will crash in unavailable aggregate path

Severity: **High**  
Type: **actual runtime bug**

### Evidence

In `MultiCurrencyRepository.getHomeCurrencyPurchaseTotalHistorical(...)`, the home-currency failure path now returns:

```kotlin
MoneyAggregate.empty(CurrencyCode(""), RateBasis.TRANSACTION_DATE)
```

But `CurrencyCode` requires:

```kotlin
require(code.length == 3)
require(code.all { it in 'A'..'Z' })
```

So:

```kotlin
CurrencyCode("")
```

throws `IllegalArgumentException`.

### Impact

The intended “unavailable aggregate” path does not return an unavailable result. It crashes while constructing it.

### Why this matters

This is a direct regression caused by replacing fake EUR with an invalid currency sentinel.

The correct fix is **not** an empty string inside `CurrencyCode`. `MoneyAggregate` cannot represent “no currency” safely with the current type.

### Fix strategy

Use a typed result wrapper instead of invalid currency:

```kotlin
sealed interface MoneyAggregateResult {
    data class Available(val aggregate: MoneyAggregate) : MoneyAggregateResult

    data class Unavailable(
        val reason: String,
        val requestedRateBasis: RateBasis
    ) : MoneyAggregateResult
}
```

Then repository methods that can fail home-currency resolution should return this wrapper, or throw a typed exception that dashboard/budget callers convert to unavailable UI state.

Short-term if changing signatures is too wide:
- do **not** construct `CurrencyCode("")`,
- return failure upward,
- or use an existing typed unavailable state at the caller boundary.

But do not create invalid `CurrencyCode`.

### Tests

```text
purchase_historical_home_currency_failure_does_not_throw_invalid_currency
purchase_historical_home_currency_failure_returns_typed_unavailable
money_aggregate_unavailable_does_not_use_fake_or_empty_currency
```

---

## CURR-3E8-02 — Dead unsafe EUR fallback still exists

Severity: **Medium/High**  
Type: **architecture bug / regression risk**

### Evidence

`MultiCurrencyRepository.resolveHomeCurrencyOrUnavailable()` still contains:

```kotlin
HomeCurrencyResolution.Failed -> CurrencyCode("EUR") to true
```

Even if currently unused, this is exactly the pattern the refactor is trying to remove.

### Impact

A future caller can reintroduce fake EUR unavailable states by using this helper.

### Fix

Remove the helper or change it to return a typed result:

```kotlin
sealed interface ResolvedHomeCurrencyForAggregate {
    data class Available(val currency: CurrencyCode) : ResolvedHomeCurrencyForAggregate
    data class Unavailable(val reason: String) : ResolvedHomeCurrencyForAggregate
}
```

### Tests

```text
no_repository_helper_returns_eur_on_home_currency_failure
money_guard_flags_eur_in_home_currency_failure_helper
```

---

## CURR-3E8-03 — `DashboardNormalizedInputResult` exists but is not used by `compute()`

Severity: **High**  
Type: **architecture gap / user-facing consistency bug**

### Evidence

`CompiledDashboardData` now has:

```kotlin
val normalizedInput: DashboardNormalizedInputResult? = null
```

But `compute(...)` returns:

```kotlin
CompiledDashboardData(
    allWidgets = widgets,
    totalSpent = ctx.totalSpent,
    txCount = ctx.txCount,
    isPartial = ctx.data.summary.isPartial || ctx.periodIsPartial
)
```

It does **not** pass:

```kotlin
normalizedInput = ...
```

Also, `compute(...)` does not call:

```kotlin
produceDashboardNormalizedInput(...)
```

### Impact

The canonical normalized input is still effectively dead code for the main dashboard path.

The widgets still use:
- `processedData.summary.totalSpent`,
- `data.categoryBreakdown`,
- `data.weather.discretionaryBudget`,
- `ExpenseSnapshot` raw values,
- latest-rate repository calls.

### Fix

At the start of `compute(...)`:

```kotlin
val normalizedInput = produceDashboardNormalizedInput(
    expenses = ...,
    periodStart = ...,
    periodEnd = ...
)
```

Then pass it into:
- `buildContext`,
- `computeCategoryTotals`,
- `computeSpendingTrend`,
- `computeMonteCarlo`,
- `computeRunwayAndForecast`,
- returned `CompiledDashboardData`.

### Tests

```text
compute_populates_compiledDashboardData_normalizedInput
dashboard_widgets_consume_normalizedInput_when_available
dashboard_home_currency_unavailable_sets_normalizedInput_unavailable
```

---

## CURR-3E8-04 — Dashboard widgets still use non-canonical raw/legacy values

Severity: **High**  
Type: **actual reporting consistency bug**

### Evidence

Current dashboard paths still use:

```text
ctx.totalSpent = summary.totalSpent
ctx.monthSpent = summary.totalSpent
categoryTotals = ctx.data.categoryBreakdown
safeToSpend = data.weather.discretionaryBudget
today/week = getHomeCurrencyPurchaseTotal(...)
Monte Carlo spentToDate = getHomeCurrencyPurchaseTotal(...)
runway = raw/legacy forecast values
```

`computeCategoryTotals(...)` still maps:

```kotlin
ctx.data.categoryBreakdown.map { CategorySpending(... total = it.amount ...) }
```

instead of using `DashboardNormalizedInput.categoryAggregates`.

`computeMonteCarlo(...)` still does:

```kotlin
multiCurrencyRepository.getHomeCurrencyPurchaseTotal(...).displayAmount
```

which is latest-rate, not necessarily the same basis as dashboard period summary/trend.

### Impact

Dashboard summary, category breakdown, trend, Monte Carlo, and runway can still disagree in rate basis.

### Fix

Make `DashboardNormalizedInput` the single source for money totals:

```text
PeriodSummary -> normalizedInput.periodAggregate
TopCategories -> normalizedInput.categoryAggregates
SpendingTrend -> normalizedInput.normalizedExpenses or aggregate buckets
MonteCarlo -> normalizedInput period/month aggregate
Runway/forecast -> normalized forecast input
```

### Tests

```text
dashboard_summary_category_trend_share_same_rate_basis
dashboard_category_total_matches_period_aggregate
dashboard_monte_carlo_uses_normalized_input
dashboard_safe_to_spend_does_not_use_raw_weather_currency_value
```

---

## CURR-3E8-05 — Forecast/runway raw `ExpenseSnapshot` path is still not fixed

Severity: **High**  
Type: **actual forecast currency-mixing risk**

### Evidence

`computeRunwayAndForecast(...)` still builds:

```kotlin
ExpenseSnapshot(
    amount = expense.amount,
    effectiveAmount = expense.effectiveAmount,
    currency = expense.currency,
    ...
)
```

The commit only adds a comment:

```text
TODO: Full migration to NormalizedExpense requires ForecastInputAssembler refactor.
For now, amounts are in original currency — the assembler handles mixed currencies
via its own normalization path.
```

### Problems

1. This is not a fix; it is a documented limitation.
2. The static guard now has G-MONEY-10 meant to flag this exact pattern.
3. If the guard runs on this file, it likely fails CI because the pattern still exists.

### Impact

Forecast/runway remains a high-risk path for mixed-currency math unless `ForecastInputAssembler` fully normalizes everything downstream. The dashboard code itself cannot prove that.

### Fix

Introduce:

```kotlin
NormalizedForecastInput
```

and pass normalized values into synthesis/runway.

At minimum:
- create `ExpenseSnapshot` only from already-normalized amounts,
- or create a new `NormalizedExpenseSnapshot` type.

### Tests

```text
forecast_runway_does_not_build_raw_expenseSnapshot_from_dashboard_expense
forecast_input_uses_normalized_expenses
runway_committed_likely_use_same_currency_basis
```

---

## CURR-3E8-06 — Static guard likely fails current source

Severity: **High**  
Type: **CI/build regression risk**

### Evidence

`verify_money_boundaries.py` adds:

```text
G-MONEY-10: raw ExpenseSnapshot amounts in synthesis/forecast without normalization
```

Regex:

```python
r'ExpenseSnapshot\(.*effectiveAmount\s*=\s*\w+\.effectiveAmount'
```

Current `ComputeDashboardWidgetsUseCase.kt` still has:

```kotlin
ExpenseSnapshot(
    ...
    effectiveAmount = expense.effectiveAmount,
    ...
)
```

Because the source is minified into long lines, this regex is especially likely to match.

### Impact

The commit may fail CI once `verify_money_boundaries.py` runs.

### Fix options

Preferred:
- fix the raw snapshot path.

Temporary:
- add an explicit `// G-MONEY-ALLOW` with a ticket reference, but this should be avoided because it weakens the guard.

### Tests

```text
money_guard_current_source_passes
money_guard_flags_raw_snapshot_fixture
```

---

## CURR-3E8-07 — SpendingTrend quality improved but still not canonical

Severity: **Medium/High**  
Type: **partial user-facing fix**

### What improved

`computeSpendingTrend(...)` now sets `currencyQuality` if conversions are dropped.

### Remaining issues

1. Home-currency failure still throws:

```kotlin
throw IllegalStateException("Home currency unavailable: ...")
```

2. It uses legacy:

```kotlin
currencyConverter.convertAsOf(...)
```

not:

```kotlin
MoneyNormalizationEngine
```

3. It only counts excluded rows, not:
   - missing rates,
   - invalid currencies,
   - stale rates,
   - included count,
   - actual basis.

4. It does not consume `DashboardNormalizedInput`.

### Fix

Either:
- compute trend from `DashboardNormalizedInput.normalizedExpenses`, or
- use `MoneyNormalizationEngine` directly per trend row and collect `ConversionFailure` details.

### Tests

```text
spending_trend_home_currency_unavailable_returns_unavailable_widget
spending_trend_uses_normalized_input
spending_trend_quality_counts_missing_invalid_stale
```

---

## CURR-3E8-08 — Latest-rate staleness policy is not consistently applied

Severity: **Medium/High**  
Type: **basis/quality inconsistency**

### What improved

`MoneyNormalizationEngine.normalizeExpense(...)` uses:

```kotlin
StaleRatePolicy.LatestDefault
```

for:

```kotlin
RateBasis.LATEST_AVAILABLE
```

### Remaining issues

`MoneyNormalizationEngine.aggregateBuckets(...)` still calls:

```kotlin
stalePolicy = StaleRatePolicy.None
```

`MoneyAggregateBuilder.fromBuckets(...)` typed overload also calls:

```kotlin
stalePolicy = StaleRatePolicy.None
```

Legacy `MoneyAggregateBuilder.fromBuckets(Pair...)` uses:

```kotlin
converter.convertMultiple(...)
```

which has its own 24-hour staleness behavior, not the new 7-day `LatestDefault`.

### Impact

Different latest-rate aggregate paths can disagree on whether the same rate is stale.

### Fix

Centralize latest-rate stale policy:

```kotlin
fun stalePolicyFor(rateBasis: RateBasis): StaleRatePolicy =
    when (rateBasis) {
        RateBasis.LATEST_AVAILABLE -> StaleRatePolicy.LatestDefault
        else -> StaleRatePolicy.None
    }
```

Use it in:
- `MoneyNormalizationEngine.normalizeExpense`,
- `MoneyNormalizationEngine.aggregateBuckets`,
- typed `MoneyAggregateBuilder.fromBuckets`,
- latest-rate repository wrappers.

### Tests

```text
aggregateBuckets_latest_uses_LatestDefault_staleness
moneyAggregateBuilder_latest_uses_LatestDefault_staleness
legacy_convertMultiple_not_used_for_new_latest_aggregate_paths
```

---

## CURR-3E8-09 — `CurrencyCode("")` / `currency = ""` is not a complete unavailable-state model

Severity: **Medium**

### Problem

The code now avoids fake EUR by using blank strings in some places:

```kotlin
CurrencyCode("")
currency = ""
```

The first is invalid and crashes. The second may avoid a crash but still leaves downstream code to interpret a blank currency.

### Correct model

Unavailable money should not be represented by an invalid currency.

Use one of:

```kotlin
sealed interface ForecastResult {
    data class Available(val forecast: BudgetForecast) : ForecastResult
    data class Unavailable(val reason: String) : ForecastResult
}
```

or add explicit status fields:

```kotlin
currencyStatus = HOME_CURRENCY_UNAVAILABLE
currency: String? = null
```

If DB schema requires non-null `currency`, keep blank only at persistence boundary and never expose it as valid UI currency.

### Tests

```text
blank_forecast_currency_not_formatted_as_money
budget_forecast_unavailable_has_currency_status
unavailable_money_never_constructs_CurrencyCode_blank
```

---

## CURR-3E8-10 — Legacy APIs remain widely available

Severity: **Medium/High**  
Type: **regression risk**

### Evidence

There are still many legacy APIs:

```text
getTotalExpensesInHomeCurrency(...)
getExpensesByCurrency(...)
getCategoryTotalsInHomeCurrency(...)
getMerchantTotalsInHomeCurrency(...)
getMonthlyTotalsInHomeCurrency(...)
getExpensesWithConversion(...)
getHomeCurrencyWeeklyTotals(...)
getHomeCurrencyDailyTotals(...)
```

Some are deprecated, but not all at `ERROR`.

Some still return:

```text
Result<Double>
Map<..., Double>
```

or use latest-rate `convertMultiple`.

### Impact

Future callers can still bypass the normalized MoneyAggregate path.

### Fix

After call sites migrate:
- raise legacy aggregate APIs to `DeprecationLevel.ERROR`,
- or make them internal/private,
- keep only clearly named latest-rate APIs.

### Tests

```text
legacy_double_aggregate_apis_are_error_deprecated
dashboard_does_not_call_legacy_latest_rate_api
budget_does_not_call_legacy_double_api
```

---

## CURR-3E8-11 — Tests are still not real integration coverage

Severity: **Medium/High**  
Type: **test-quality issue**

### Evidence

`CurrencyNormalizationPost9a6Test` uses:

```text
TestStore
fake TimeProvider
direct MoneyNormalizationEngine
manual DashboardNormalizedInputResult.Unavailable construction
```

These tests are useful, but they do not prove:

```text
1. Room DAO latest/as-of behavior.
2. Migration correctness.
3. MultiCurrencyRepository historical behavior.
4. Dashboard compute() normalizedInput population.
5. SpendingTrend behavior through real compute().
6. Guard script passing against current source.
7. Budget forecast unavailable handling through DAO/UI paths.
```

### Fix

Add true integration tests:

```text
ExchangeRateDaoIntegrationTest
MultiCurrencyRepositoryHistoricalIntegrationTest
DashboardCurrencyIntegrationTest
BudgetForecastCurrencyIntegrationTest
MoneyBoundaryGuardTest
```

### Tests needed

```text
dashboard_compute_populates_normalizedInput
dashboard_spending_trend_partial_quality_live_path
multiCurrency_home_failure_does_not_construct_invalid_CurrencyCode
money_guard_current_source_passes
room_getRateAsOf_uses_validDate
```

---

# 3. Status matrix

| Area | Status after `3e8b43c` | Notes |
|---|---:|---|
| Historical purchase total midpoint/latest fallback | Mostly fixed | Happy path uses `MoneyNormalizationEngine` |
| Home currency unavailable in historical total | Broken | `CurrencyCode("")` crashes |
| Fake EUR dashboard unavailable container | Model fixed | But main `compute()` does not use it |
| Fake EUR budget forecast | Partial | Uses blank `currency`, but no typed unavailable model |
| SpendingTrend partial quality | Partial | Counts dropped rows, but not canonical/failure-typed |
| Dashboard canonical normalized input | Not done | Exists but main widget path ignores it |
| Forecast/runway normalization | Not done | Raw `ExpenseSnapshot` still built |
| Latest staleness policy | Partial | Only row normalization uses `LatestDefault` |
| Legacy API containment | Partial | One API ERROR-deprecated; many remain |
| Static money guard | Partial / possibly failing | G-MONEY-10 likely flags current source |
| Integration tests | Partial | Still fake-store/unit style |

---

# 4. Actual bugs vs architecture debt

## Actual user-facing / runtime bugs

```text
1. CurrencyCode("") crashes unavailable aggregate path.
2. Dashboard compute() still returns normalizedInput = null.
3. Forecast/runway still builds raw ExpenseSnapshot values.
4. Static money guard likely fails current source.
5. Home currency failure in SpendingTrend throws instead of producing unavailable widget.
```

## Reporting correctness bugs

```text
1. Dashboard category/summary/trend/Monte Carlo/runway still do not share one normalized basis.
2. SpendingTrend only exposes partial row count, not full currency quality.
3. Latest-rate staleness policy differs across normalization/builder/legacy paths.
```

## Architectural debt

```text
1. MoneyAggregate cannot represent unavailable without fake/invalid currency.
2. Legacy aggregate APIs remain.
3. Tests are not yet live integration tests.
4. Guard remains heuristic and line-based.
```

---

# 5. Recommended next PR sequence

## PR 1 — Fix unavailable money modeling

Fix:

```text
CURR-3E8-01
CURR-3E8-02
CURR-3E8-09
```

Tasks:

```text
1. Remove CurrencyCode("").
2. Remove resolveHomeCurrencyOrUnavailable() fake EUR helper.
3. Add MoneyAggregateResult.Available / Unavailable or equivalent.
4. Make repository/caller boundaries handle unavailable typed state.
```

Acceptance:

```text
Home-currency failure never constructs CurrencyCode("") or CurrencyCode("EUR") as fake fallback.
```

---

## PR 2 — Actually wire DashboardNormalizedInput into compute()

Fix:

```text
CURR-3E8-03
CURR-3E8-04
```

Tasks:

```text
1. compute() calls produceDashboardNormalizedInput().
2. CompiledDashboardData.normalizedInput is populated.
3. PeriodSummary uses normalized aggregate.
4. TopCategories uses categoryAggregates.
5. Monte Carlo uses normalized period/month aggregate.
6. Partial quality propagates to widgets.
```

Acceptance:

```text
Dashboard summary/category/trend use one canonical basis.
```

---

## PR 3 — Forecast/runway normalized input

Fix:

```text
CURR-3E8-05
CURR-3E8-06
```

Tasks:

```text
1. Stop building raw ExpenseSnapshot for synthesis.
2. Add NormalizedForecastInput or NormalizedExpenseSnapshot.
3. Make guard pass without allowlisting this path.
```

Acceptance:

```text
Forecast/runway cannot consume raw mixed-currency snapshots.
```

---

## PR 4 — SpendingTrend canonical quality

Fix:

```text
CURR-3E8-07
```

Tasks:

```text
1. Compute trend from normalized input or MoneyNormalizationEngine.
2. Return typed unavailable on home currency failure.
3. Count missing/stale/invalid failures.
```

Acceptance:

```text
Trend never silently drops conversion failures and never throws on home-currency failure.
```

---

## PR 5 — Staleness policy consistency

Fix:

```text
CURR-3E8-08
```

Tasks:

```text
1. Use one stalePolicyFor(rateBasis) helper.
2. Apply LatestDefault in bucket/builder latest paths.
3. Avoid convertMultiple in new latest aggregate APIs if its 24h policy conflicts.
```

Acceptance:

```text
Latest-rate freshness semantics are consistent across all aggregate paths.
```

---

## PR 6 — Static guard and integration tests

Fix:

```text
CURR-3E8-10
CURR-3E8-11
```

Tasks:

```text
1. Add guard fixture tests.
2. Make guard pass current source.
3. Add Room/repository/dashboard live tests.
4. Raise remaining legacy APIs to ERROR after migration.
```

Acceptance:

```text
Known bad patterns fail tests and CI.
```

---

# 6. Updated definition of done

Global currency issue #4 is not done until:

```text
1. Unavailable money is typed, not fake EUR and not invalid CurrencyCode("").
2. Historical purchase totals use transaction-date rates and handle home-currency failure safely.
3. Dashboard compute() populates and consumes DashboardNormalizedInput.
4. Summary/category/trend/Monte Carlo/runway share a normalized basis.
5. Forecast/runway no longer consume raw ExpenseSnapshot amounts.
6. SpendingTrend exposes full currency quality and does not throw on home-currency failure.
7. Latest-rate staleness policy is consistent across row, bucket, builder, and repository paths.
8. Legacy Double/latest aggregate APIs are removed, internal, or ERROR-deprecated.
9. verify_money_boundaries.py passes current source and has fixture tests.
10. Room/repository/dashboard/budget/forecast integration tests prove live behavior.
```

---

# Sources reviewed

```text
Commit:
https://github.com/panospao7/Cost-agregator/commit/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69

MultiCurrencyRepository.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

ComputeDashboardWidgetsUseCase.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

DashboardNormalizedInput.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardNormalizedInput.kt

BudgetForecastingEngine.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt

MoneyNormalizationEngine.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyNormalizationEngine.kt

MoneyAggregateBuilder.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

CurrencyCode.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/domain/core/money/CurrencyCode.kt

verify_money_boundaries.py:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/scripts/verify_money_boundaries.py

CurrencyNormalizationPost9a6Test.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/test/java/com/yourname/expensetracker/integration/CurrencyNormalizationPost9a6Test.kt
```