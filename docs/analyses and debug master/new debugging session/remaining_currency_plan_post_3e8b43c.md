# Remaining Currency Normalization Implementation Plan

Baseline commit:

```text
3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69
feat: currency normalization post-9a6afc4 fixes (CURR-9A6)
```

Commit source:

```text
https://github.com/panospao7/Cost-agregator/commit/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69
```

---

## 0. Current status

### Good fixes already present

Keep these unless tests prove they are broken:

```text
1. Historical purchase total happy path now uses MoneyNormalizationEngine.aggregateExpenses(... TRANSACTION_DATE).

2. DashboardNormalizedInputResult exists with Available / Unavailable.

3. SpendingTrend now exposes currencyQuality for dropped conversion rows.

4. BudgetForecastingEngine no longer uses fake currency = "EUR" in home-currency-unavailable branch.

5. StaleRatePolicy.LatestDefault exists.

6. MoneyNormalizationEngine.normalizeExpense() uses LatestDefault for LATEST_AVAILABLE.

7. getHomeCurrencyTotal() is now DeprecationLevel.ERROR.

8. verify_money_boundaries.py has G-MONEY-09 and G-MONEY-10.

9. Docs mention unavailable state and staleness policy.
```

### Open issue map

```text
CURR-3E8-01 CurrencyCode("") crashes unavailable historical aggregate path.
CURR-3E8-02 resolveHomeCurrencyOrUnavailable() still returns fake EUR on failure.
CURR-3E8-03 DashboardNormalizedInputResult exists but compute() does not populate/use it.
CURR-3E8-04 Dashboard widgets still use raw/legacy values instead of canonical normalized input.
CURR-3E8-05 Forecast/runway still builds raw ExpenseSnapshot values.
CURR-3E8-06 Static guard likely flags current source due raw ExpenseSnapshot path.
CURR-3E8-07 SpendingTrend quality is partial but not canonical/failure-typed.
CURR-3E8-08 Latest-rate staleness policy is inconsistent across row/bucket/builder paths.
CURR-3E8-09 Blank currency strings are being used as unavailable-state placeholders.
CURR-3E8-10 Legacy aggregate APIs remain available and dangerous.
CURR-3E8-11 Tests are still mostly fake/unit, not real integration coverage.
```

---

# Recommended PR order

```text
PR 1  Typed unavailable money/result modeling
PR 2  Wire DashboardNormalizedInput into dashboard compute()
PR 3  Forecast/runway normalized input migration
PR 4  SpendingTrend canonical quality and unavailable handling
PR 5  Staleness policy consistency
PR 6  Legacy API containment
PR 7  Static money guard v3 and current-source cleanup
PR 8  Real integration/behavioral tests
PR 9  Docs/tracker cleanup
```

Fastest risk-reduction order:

```text
1. Remove CurrencyCode("") crash.
2. Remove fake EUR helper.
3. Make guard pass current source or fix raw snapshot path.
4. Populate CompiledDashboardData.normalizedInput.
5. Stop raw ExpenseSnapshot forecast/runway path.
```

---

# PR 1 — Typed unavailable money/result modeling

## Fixes

```text
CURR-3E8-01
CURR-3E8-02
CURR-3E8-09
```

## Goal

Unavailable money must not be represented as:

```kotlin
CurrencyCode("")
CurrencyCode("EUR")
currency = ""
currency = "EUR"
```

Use typed unavailable results instead.

## Files

```text
domain/core/money/MoneyAggregate.kt
domain/core/money/new MoneyAggregateResult.kt
data/repository/MultiCurrencyRepository.kt
domain/budget/BudgetForecastingEngine.kt
dashboard models if needed
tests
```

---

## 1.1 Add typed aggregate result

Create:

```kotlin
sealed interface MoneyAggregateResult {
    data class Available(
        val aggregate: MoneyAggregate
    ) : MoneyAggregateResult

    data class Unavailable(
        val reason: String,
        val requestedRateBasis: RateBasis,
        val metadata: MoneyAggregateMetadata = MoneyAggregateMetadata(),
        val warningMessage: String = reason
    ) : MoneyAggregateResult
}
```

Add helpers:

```kotlin
val MoneyAggregateResult.isAvailable: Boolean
val MoneyAggregateResult.aggregateOrNull: MoneyAggregate?
```

Do **not** add fake currencies.

---

## 1.2 Replace `CurrencyCode("")` in `MultiCurrencyRepository`

Current broken pattern:

```kotlin
return MoneyAggregate.empty(CurrencyCode(""), RateBasis.TRANSACTION_DATE)
```

Replace by changing the method that can fail home-currency resolution to return:

```kotlin
MoneyAggregateResult
```

For example:

```kotlin
suspend fun getHomeCurrencyPurchaseTotalHistoricalResult(
    startDate: Long,
    endDate: Long
): MoneyAggregateResult
```

Implementation:

```kotlin
val homeCurrency = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
    is HomeCurrencyResolution.Resolved -> resolution.currency
    is HomeCurrencyResolution.FirstRunDefault -> resolution.currency
    is HomeCurrencyResolution.Failed -> {
        return MoneyAggregateResult.Unavailable(
            reason = "Home currency unavailable: ${resolution.reason}",
            requestedRateBasis = RateBasis.TRANSACTION_DATE
        )
    }
}
```

Then:

```kotlin
return MoneyAggregateResult.Available(
    normalizationEngine.aggregateExpenses(...)
)
```

Compatibility option:

Keep old method temporarily:

```kotlin
@Deprecated("Use getHomeCurrencyPurchaseTotalHistoricalResult()", level = DeprecationLevel.WARNING)
suspend fun getHomeCurrencyPurchaseTotalHistorical(...): MoneyAggregate
```

But only call it in contexts where home currency is guaranteed resolved, or make it throw a typed exception. Do **not** return invalid `CurrencyCode`.

---

## 1.3 Remove fake EUR helper

Current risky helper:

```kotlin
resolveHomeCurrencyOrUnavailable(): Pair<CurrencyCode, Boolean>
```

returns fake `CurrencyCode("EUR")` on failure.

Replace with:

```kotlin
sealed interface HomeCurrencyForMoneyMath {
    data class Available(
        val currency: CurrencyCode,
        val isFirstRunDefault: Boolean = false
    ) : HomeCurrencyForMoneyMath

    data class Unavailable(
        val reason: String
    ) : HomeCurrencyForMoneyMath
}
```

Helper:

```kotlin
private suspend fun resolveHomeCurrencyForMoneyMath(): HomeCurrencyForMoneyMath
```

Rules:

```text
Resolved -> Available(currency)
FirstRunDefault -> Available(currency, isFirstRunDefault = true)
Failed -> Unavailable(reason)
```

---

## 1.4 Budget forecast unavailable model

Current partial fix:

```kotlin
currency = ""
```

This is better than fake EUR but still semantically weak.

Introduce either:

```kotlin
sealed interface BudgetForecastResult {
    data class Available(val forecast: BudgetForecast) : BudgetForecastResult
    data class Unavailable(
        val budgetId: Long,
        val reason: String,
        val createdAt: Long
    ) : BudgetForecastResult
}
```

or add fields:

```kotlin
enum class ForecastCurrencyStatus {
    AVAILABLE,
    HOME_CURRENCY_UNAVAILABLE,
    CONVERSION_FAILED,
    PARTIAL
}
```

Then:

```kotlin
val currencyStatus: ForecastCurrencyStatus
val currency: String?
```

If DB schema forces `currency: String`, keep blank only at persistence boundary and ensure UI/formatters check `currencyStatus` before formatting.

---

## Tests

```text
purchase_historical_home_currency_failure_does_not_construct_invalid_currency
purchase_historical_home_currency_failure_returns_unavailable_result
resolveHomeCurrencyForMoneyMath_failed_does_not_return_EUR
budget_forecast_home_currency_failure_has_unavailable_status
blank_forecast_currency_is_never_formatted_as_money
unavailable_money_never_uses_CurrencyCode_blank
```

## Acceptance criteria

```text
1. No production code constructs CurrencyCode("").
2. No home-currency failure helper returns CurrencyCode("EUR").
3. Unavailable money is represented by typed result/status.
4. UI/formatting code cannot mistake unavailable money for valid EUR or blank currency.
```

---

# PR 2 — Wire DashboardNormalizedInput into dashboard compute()

## Fixes

```text
CURR-3E8-03
CURR-3E8-04
```

## Goal

`DashboardNormalizedInputResult` must become the actual dashboard money source, not a dormant field.

## Files

```text
domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
domain/usecase/dashboard/DashboardNormalizedInput.kt
dashboard widget models
dashboard UI contracts if needed
tests
```

---

## 2.1 Compute normalized input once

At the start of the main `compute(...)` flow, call:

```kotlin
val normalizedInputResult = produceDashboardNormalizedInput(
    expenses = dashboardExpenses,
    periodStart = periodStart,
    periodEnd = periodEnd
)
```

Then pass it into context:

```kotlin
data class DashboardComputationContext(
    ...
    val normalizedInputResult: DashboardNormalizedInputResult
)
```

And return it:

```kotlin
CompiledDashboardData(
    ...
    normalizedInput = normalizedInputResult,
    isPartial = existingPartial || normalizedInputResult.isPartial()
)
```

---

## 2.2 Replace raw summary/category inputs

Current risk sources include:

```text
ctx.totalSpent = summary.totalSpent
ctx.monthSpent = summary.totalSpent
ctx.data.categoryBreakdown
ctx.data.weather.discretionaryBudget
getHomeCurrencyPurchaseTotal(...)
```

Replace:

### Period summary

Use:

```kotlin
normalizedInput.periodAggregate
```

### Category totals

Use:

```kotlin
normalizedInput.categoryAggregates
```

### Merchant totals

Use:

```kotlin
normalizedInput.merchantAggregates
```

### Spending trend

Use normalized rows or a trend-specific normalized aggregation result.

### Monte Carlo / current-month spend

Use:

```kotlin
normalizedInput.periodAggregate.displayAmount
```

or a clearly named latest-rate valuation aggregate if that widget is intentionally latest-rate.

---

## 2.3 Define behavior for unavailable normalized input

If:

```kotlin
DashboardNormalizedInputResult.Unavailable
```

then:

```text
1. Money widgets should render unavailable/partial state.
2. Do not calculate fake totals.
3. Do not use raw summary fallback.
4. Return warning metadata.
```

Example:

```kotlin
DashboardWidget.PeriodSummary.Unavailable(
    reason = result.reason
)
```

If the widget sealed class cannot change yet, add fields:

```kotlin
isUnavailable = true
warningMessage = result.reason
amount = null
```

Prefer nullable amount over fake 0.0 where possible.

---

## 2.4 Ensure basis consistency

For historical dashboard period widgets:

```text
RateBasis.TRANSACTION_DATE
```

Required consistency:

```text
summary aggregate basis == category aggregate basis == trend basis
```

If a widget intentionally uses latest valuation, label it:

```text
RateBasis.LATEST_AVAILABLE
conversionQuality = ESTIMATED or COMPLETE depending staleness
```

---

## Tests

```text
compute_populates_compiledDashboardData_normalizedInput
dashboard_home_currency_unavailable_sets_normalizedInput_unavailable
dashboard_period_summary_uses_normalized_periodAggregate
dashboard_category_totals_use_normalized_categoryAggregates
dashboard_summary_category_trend_share_same_rate_basis
dashboard_category_total_matches_period_aggregate
dashboard_does_not_fallback_to_raw_summary_when_normalized_unavailable
```

## Acceptance criteria

```text
1. compute() always sets CompiledDashboardData.normalizedInput.
2. Dashboard money widgets consume normalized input where available.
3. Unavailable normalized input produces unavailable widgets, not fake money.
4. Summary/category/trend share basis and quality metadata.
```

---

# PR 3 — Forecast/runway normalized input migration

## Fixes

```text
CURR-3E8-05
CURR-3E8-06
```

## Goal

Forecast/runway must not build raw `ExpenseSnapshot` from mixed-currency dashboard expense amounts.

## Files

```text
ComputeDashboardWidgetsUseCase.kt
ForecastInputAssembler.kt
SynthesisEngine.kt
forecast/runway models
scripts/verify_money_boundaries.py
tests
```

---

## 3.1 Introduce normalized forecast input

Create:

```kotlin
data class NormalizedForecastInput(
    val actualExpenses: List<NormalizedExpenseSnapshot>,
    val plannedExpenses: List<NormalizedPlannedExpense>,
    val recurringPatterns: List<NormalizedRecurringPattern>,
    val homeCurrency: CurrencyCode,
    val currencyQuality: CurrencyDataQuality
)
```

Create snapshot:

```kotlin
data class NormalizedExpenseSnapshot(
    val sourceExpenseId: Long,
    val originalAmount: Double,
    val originalCurrency: CurrencyCode,
    val normalizedAmount: Double,
    val normalizedCurrency: CurrencyCode,
    val rateBasis: RateBasis,
    val rateUsed: Double,
    val rateValidDate: Long?,
    val rateLastUpdated: Long?,
    val conversionPath: ConversionPath
)
```

---

## 3.2 Replace raw `ExpenseSnapshot(...)`

Current risky pattern:

```kotlin
ExpenseSnapshot(
    amount = expense.amount,
    effectiveAmount = expense.effectiveAmount,
    currency = expense.currency
)
```

Replace with:

```kotlin
val normalizedExpenses = normalizedInput.normalizedExpenses.map { normalized ->
    NormalizedExpenseSnapshot(
        sourceExpenseId = normalized.expenseId,
        originalAmount = normalized.originalAmount,
        originalCurrency = normalized.originalCurrency,
        normalizedAmount = normalized.normalizedEffectiveAmount,
        normalizedCurrency = normalized.displayCurrency,
        rateBasis = normalized.rateBasis,
        rateUsed = normalized.rateUsed,
        rateValidDate = normalized.rateValidDate,
        rateLastUpdated = normalized.rateLastUpdated,
        conversionPath = normalized.conversionPath
    )
}
```

Then feed only normalized snapshots to forecast/runway synthesis.

---

## 3.3 Planned/recurring future items

For future occurrences:

```text
RateBasis.FORECAST_DATE
atMillis = occurrenceDate
```

If conversion fails:

```text
exclude item
increment missing/stale/invalid counters
mark forecast partial
display warning
```

---

## 3.4 Make guard pass without allowlisting

After migration:

```bash
python3 scripts/verify_money_boundaries.py --root .
```

should not flag G-MONEY-10.

Do not suppress with `// G-MONEY-ALLOW` unless there is a documented temporary ticket.

---

## Tests

```text
forecast_runway_does_not_build_raw_expenseSnapshot_from_dashboard_expense
forecast_input_uses_normalized_expenses
runway_committed_likely_use_same_currency_basis
forecast_missing_rate_marks_partial
money_guard_current_source_passes_without_raw_snapshot_allowlist
```

## Acceptance criteria

```text
1. No raw ExpenseSnapshot with amount/effectiveAmount enters synthesis.
2. Forecast/runway uses normalized amounts only.
3. G-MONEY-10 passes on current source.
4. Missing forecast-date conversions produce partial/unavailable forecast output.
```

---

# PR 4 — SpendingTrend canonical quality and unavailable handling

## Fixes

```text
CURR-3E8-07
```

## Goal

Spending trend should be computed from canonical normalized data and expose full conversion quality.

## Files

```text
ComputeDashboardWidgetsUseCase.kt
DashboardNormalizedInput.kt
dashboard widget models
tests
```

---

## 4.1 Compute trend from normalized input

Instead of performing its own:

```kotlin
currencyConverter.convertAsOf(...)
```

inside `computeSpendingTrend(...)`, use:

```kotlin
normalizedInput.normalizedExpenses
```

Group normalized rows by period bucket:

```kotlin
val byDay = normalizedExpenses.groupBy { startOfDay(it.date) }
```

Then sum:

```kotlin
bucket.sumOf { it.normalizedEffectiveAmount }
```

This sum is safe because all rows are already in:

```kotlin
normalizedInput.homeCurrency
```

---

## 4.2 Unavailable handling

If:

```kotlin
normalizedInputResult is Unavailable
```

then return:

```kotlin
DashboardWidget.SpendingTrend(
    series = emptyList(),
    currencyQuality = CurrencyQualityUi(
        isPartial = true,
        isUnavailable = true,
        warningMessage = normalizedInputResult.reason,
        ...
    )
)
```

Do not throw.

---

## 4.3 Full failure metadata

Trend quality should include:

```kotlin
includedTransactionCount
excludedTransactionCount
missingRateCount
staleRateCount
invalidCurrencyCount
requestedRateBasis
actualRateBasis
conversionQuality
warningMessage
```

Reuse `normalizedInput.dataQuality` / `MoneyAggregate.metadata`.

---

## Tests

```text
spending_trend_uses_normalized_input
spending_trend_home_currency_unavailable_returns_unavailable_widget
spending_trend_quality_counts_missing_invalid_stale
spending_trend_does_not_call_currencyConverter_directly
spending_trend_does_not_throw_on_home_currency_failure
```

## Acceptance criteria

```text
1. Trend uses canonical normalized input.
2. Trend never silently drops rows without quality metadata.
3. Trend never throws on home currency failure.
4. Trend shares the same basis as dashboard period aggregate.
```

---

# PR 5 — Staleness policy consistency

## Fixes

```text
CURR-3E8-08
```

## Goal

Latest-rate freshness semantics must be consistent across row, bucket, builder, and repository paths.

## Files

```text
MoneyNormalizationEngine.kt
MoneyAggregateBuilder.kt
StaleRatePolicy.kt
MultiCurrencyRepository.kt
tests
```

---

## 5.1 Add central policy helper

Create:

```kotlin
object MoneyStaleRatePolicies {
    fun forBasis(rateBasis: RateBasis): StaleRatePolicy =
        when (rateBasis) {
            RateBasis.LATEST_AVAILABLE -> StaleRatePolicy.LatestDefault
            else -> StaleRatePolicy.None
        }
}
```

or in `StaleRatePolicy` companion:

```kotlin
fun forBasis(rateBasis: RateBasis): StaleRatePolicy
```

---

## 5.2 Apply in all normalizer/builder paths

Update:

```text
MoneyNormalizationEngine.normalizeExpense()
MoneyNormalizationEngine.aggregateBuckets()
MoneyAggregateBuilder.fromBuckets(typed overload)
latest-rate repository wrappers
```

Avoid:

```kotlin
StaleRatePolicy.None
```

for latest-rate aggregation unless there is explicit documented reason.

---

## 5.3 Avoid `convertMultiple()` in new latest aggregate paths if policy conflicts

`convertMultiple()` may have its own stale semantics.

Options:

1. Update `convertMultiple()` to use `StaleRatePolicy.LatestDefault`.
2. Replace new latest aggregate paths with typed `convertOutcome(... LATEST_AVAILABLE ...)`.
3. Keep `convertMultiple()` only in deprecated legacy APIs.

Recommended:

```text
New APIs -> convertOutcome via MoneyNormalizationEngine / typed builder.
Legacy APIs -> deprecated and guarded.
```

---

## Tests

```text
normalizeExpense_latest_uses_LatestDefault
aggregateBuckets_latest_uses_LatestDefault
moneyAggregateBuilder_latest_uses_LatestDefault
latest_repository_aggregate_marks_stale_rates
legacy_convertMultiple_not_used_by_new_latest_aggregate_paths
```

## Acceptance criteria

```text
1. Latest-rate stale policy is centralized.
2. All new latest aggregate paths use the same policy.
3. Stale latest rates are reflected in aggregate metadata/quality.
```

---

# PR 6 — Legacy API containment

## Fixes

```text
CURR-3E8-10
```

## Goal

Ambiguous `Double`, `Map<..., Double>`, latest-rate, and old `get...InHomeCurrency` APIs must not be used by production dashboard/budget/forecast paths.

## Files

```text
MultiCurrencyRepository.kt
all production callers
verify_money_boundaries.py
tests
```

---

## 6.1 Inventory legacy APIs

Run:

```bash
rg "Result<.*Double|Map<.*Double|get.*InHomeCurrency|getHomeCurrencyWeeklyTotals|getHomeCurrencyDailyTotals|getExpensesWithConversion|convertMultiple" app/src/main/java
```

Classify each as:

```text
A. remove now
B. make private/internal
C. keep latest-rate but rename clearly
D. keep temporarily deprecated ERROR
```

---

## 6.2 Strongly deprecate ambiguous methods

Examples:

```kotlin
@Deprecated(
    "Ambiguous latest-rate aggregate. Use getPurchaseAggregateHistorical or getPurchaseAggregateLatestRate.",
    level = DeprecationLevel.ERROR
)
```

Apply to:

```text
getTotalExpensesInHomeCurrency
getExpensesByCurrency
getCategoryTotalsInHomeCurrency
getMerchantTotalsInHomeCurrency
getMonthlyTotalsInHomeCurrency
getHomeCurrencyWeeklyTotals
getHomeCurrencyDailyTotals
```

If some UI still needs row display:

```text
move method to explicit display/read model repository
document it is not aggregate math
```

---

## 6.3 Create explicit API surface

Keep only methods with names like:

```text
getPurchaseAggregateHistorical
getPurchaseAggregateLatestRate
getCategoryAggregatesHistorical
getCategoryAggregatesLatestRate
getDailyAggregatesHistorical
getDailyAggregatesLatestRate
```

Every method KDoc must state:

```text
RateBasis
Conversion failure behavior
Whether it can be partial
Whether it is safe for dashboard/budget/forecast
```

---

## Tests

```text
legacy_double_aggregate_apis_are_error_deprecated
dashboard_does_not_call_legacy_latest_rate_api
budget_does_not_call_legacy_double_api
forecast_does_not_call_legacy_double_api
new_api_names_contain_historical_or_latestRate
```

## Acceptance criteria

```text
1. Production dashboard/budget/forecast code cannot call ambiguous legacy APIs.
2. Remaining latest-rate APIs are explicitly named and documented.
3. Static guard catches new legacy use.
```

---

# PR 7 — Static money guard v3 and current-source cleanup

## Fixes

```text
CURR-3E8-06
CURR-3E8-10
```

## Goal

The guard should pass the current source and catch the exact known regressions.

## Files

```text
scripts/verify_money_boundaries.py
.github/workflows/ci.yml
script fixture tests
```

---

## 7.1 Make guard robust

Current guard is mostly line-regex based. Add multi-line call extraction for:

```text
ExpenseSnapshot(...)
MoneyAggregate(...)
convertAsOf(...) ?: convert(...)
```

Implement helper:

```python
def extract_call(content: str, start_index: int) -> str:
    # read until matching closing paren
```

Use it for:

```text
G-MONEY-05
G-MONEY-10
G-MONEY-02
```

---

## 7.2 Add/strengthen rules

### G-MONEY-09

Flag fake unavailable currency patterns:

```text
CurrencyCode("")
CurrencyCode("EUR") in failure/unavailable branches
currency = "" in forecast unless status is unavailable and never formatted
currency = "EUR" in failure/unavailable/unknown branches
```

### G-MONEY-10

Flag raw snapshots:

```text
ExpenseSnapshot(... amount = expense.amount ...)
ExpenseSnapshot(... effectiveAmount = expense.effectiveAmount ...)
```

### G-MONEY-11

Flag dashboard widgets using raw processed data for money after normalized input exists:

```text
ctx.data.categoryBreakdown.map { ... it.amount ... }
summary.totalSpent used for dashboard money widget
data.weather.discretionaryBudget used directly as money
```

### G-MONEY-12

Flag direct latest aggregate repository calls in dashboard historical widgets:

```text
getHomeCurrencyPurchaseTotal(
getHomeCurrencyCategoryTotals(
```

unless method name/variable says `LatestRate`.

---

## 7.3 Fixture tests

Create temp files and assert script behavior.

```text
guard_flags_invalid_currency_code_blank
guard_flags_fake_eur_unavailable_helper
guard_flags_raw_snapshot_forecast_input
guard_flags_raw_category_breakdown_widget
guard_flags_convertMultiple_in_historical_method
guard_allows_explicit_latest_rate_api
guard_current_source_passes
```

---

## 7.4 CI

Verify workflow includes:

```yaml
- name: Verify money boundaries
  run: python3 scripts/verify_money_boundaries.py --root .
```

## Acceptance criteria

```text
1. Guard passes current source after PR fixes.
2. Guard fails fixture regressions.
3. No broad allowlist hides real financial paths.
```

---

# PR 8 — Real integration and behavioral tests

## Fixes

```text
CURR-3E8-11
```

## Goal

Prove real Room/repository/dashboard/budget/forecast behavior, not only fake-store unit behavior.

## Files

```text
app/src/test/java/.../ExchangeRateDaoIntegrationTest.kt
app/src/test/java/.../MultiCurrencyRepositoryHistoricalIntegrationTest.kt
app/src/test/java/.../DashboardCurrencyIntegrationTest.kt
app/src/test/java/.../BudgetForecastCurrencyIntegrationTest.kt
app/src/test/java/.../MoneyBoundaryGuardTest.kt
```

---

## 8.1 Room exchange-rate tests

Use in-memory Room DB.

Tests:

```text
room_getLatestRateForPair_uses_highest_validDate
room_getRateAsOf_uses_validDate_lte_date
room_migration_backfills_validDate_startOfDay
room_insert_rejects_validDate_zero_if_adapter_used
```

---

## 8.2 MultiCurrencyRepository tests

Use real DAO + fake converter/rates.

Scenarios:

```text
USD rate Jan = 1.0
USD rate Feb = 2.0
latest USD = 10.0
```

Tests:

```text
historical_purchase_total_uses_transaction_date_not_latest
historical_home_currency_failure_returns_unavailable_not_crash
historical_missing_rate_marks_partial
legacy_latest_api_not_used_by_historical_method
```

---

## 8.3 Dashboard tests

Test through real `ComputeDashboardWidgetsUseCase.compute(...)` as much as possible.

Tests:

```text
dashboard_compute_populates_normalizedInput
dashboard_unavailable_home_currency_returns_unavailable_widgets
dashboard_summary_category_trend_share_basis
dashboard_spending_trend_reports_partial_quality
dashboard_does_not_use_raw_categoryBreakdown_for_money
dashboard_runway_uses_normalized_forecast_input
```

---

## 8.4 Budget forecast tests

```text
budget_forecast_home_currency_failure_not_labeled_eur
budget_forecast_unavailable_has_status
budget_forecast_conversion_failure_not_low_risk
budget_forecast_missing_rate_marks_partial
```

---

## 8.5 Guard tests

Run the Python script against temp fixtures and current source.

```text
money_guard_current_source_passes
money_guard_flags_raw_snapshot_fixture
money_guard_flags_fake_eur_fixture
money_guard_flags_raw_widget_category_fixture
```

## Acceptance criteria

```text
1. Tests would have failed on 3e8b43c before fixes.
2. Tests cover live repository/dashboard paths.
3. Fake tests remain supplementary, not the only proof.
```

---

# PR 9 — Docs and tracker cleanup

## Goal

Make the final currency architecture clear for future agents.

## Files

```text
docs/currency/rate-basis-policy.md
docs/currency/money-aggregate-contract.md
docs/currency/money-boundary-guard.md
docs/currency/dashboard-normalized-input.md
docs/analyses and debug master/... trackers
```

## Tasks

```text
1. Document typed unavailable money/result model.
2. Document that CurrencyCode("") and fake EUR unavailable containers are forbidden.
3. Document DashboardNormalizedInput ownership:
   - summary
   - category
   - trend
   - Monte Carlo
   - runway/forecast
4. Document forecast/runway normalized input contract.
5. Document latest-rate staleness policy and where it applies.
6. Document legacy API deprecation/removal status.
7. Update master tracker with fixed/open status.
8. Include rg checklist for future audits.
```

Preflight checklist to add to docs:

```bash
rg "CurrencyCode\\(\"\"\\)|CurrencyCode\\(\"EUR\"\\).*Unavailable|currency = \"EUR\"|currency = \"\"" app/src/main/java
rg "ExpenseSnapshot\\(" app/src/main/java
rg "effectiveAmount = .*\\.effectiveAmount" app/src/main/java
rg "categoryBreakdown.*amount|summary\\.totalSpent|discretionaryBudget" app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard
rg "convertMultiple\\(" app/src/main/java
rg "Result<.*Double|Map<.*Double" app/src/main/java/com/yourname/expensetracker/data/repository
```

---

# Final definition of done

Global currency issue #4 can be considered complete only when:

```text
1. No production path constructs CurrencyCode("").
2. No unavailable/failure branch uses fake EUR.
3. Unavailable money is represented by typed result/status.
4. Historical purchase totals use TRANSACTION_DATE and handle home-currency failure safely.
5. Dashboard compute() populates DashboardNormalizedInputResult.
6. Dashboard summary/category/trend/Monte Carlo/runway consume normalized input.
7. SpendingTrend uses canonical normalized input and exposes full currency quality.
8. Forecast/runway no longer consume raw ExpenseSnapshot amounts.
9. Latest-rate staleness policy is consistent across row, bucket, builder, and repository paths.
10. Legacy ambiguous aggregate APIs are removed, internal, or ERROR-deprecated.
11. verify_money_boundaries.py passes current source and catches fixtures.
12. Integration tests prove DAO, repository, dashboard, budget, and forecast behavior.
```

---

# Sources used

```text
Commit:
https://github.com/panospao7/Cost-agregator/commit/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69

MultiCurrencyRepository.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

ComputeDashboardWidgetsUseCase.kt:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

verify_money_boundaries.py:
https://raw.githubusercontent.com/panospao7/Cost-agregator/3e8b43c25c2ff73a7cb75a1b6314883cc8dc7a69/scripts/verify_money_boundaries.py
```