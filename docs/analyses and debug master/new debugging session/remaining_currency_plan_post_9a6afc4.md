# Remaining Currency Normalization Implementation Plan

Baseline commit:

`9a6afc438093ed6a03c7f831d3e4acd41a7f2a40`

## Current open issue map

```text
CURR-9A6-01 Historical purchase total still uses midpoint/latest fallback.
CURR-9A6-02 Dashboard normalized unavailable state still uses EUR placeholder.
CURR-9A6-03 SpendingTrend drops failed conversions without visible quality.
CURR-9A6-04 Budget forecast unavailable state still stores currency="EUR".
CURR-9A6-05 MoneyNormalizationEngine uses StaleRatePolicy.None.
CURR-9A6-06 Legacy/latest-rate APIs still available and dangerous.
CURR-9A6-07 Dashboard canonical input exists but is not the single widget source.
CURR-9A6-08 Forecast/runway path still builds raw ExpenseSnapshot inputs.
CURR-9A6-09 Static money guard is useful but still bypassable.
CURR-9A6-10 Integration tests still need stronger real Room/repository coverage.
```

---

# PR 1 — Fix historical aggregate correctness

## Goal
`getHomeCurrencyPurchaseTotalHistorical()` must be truly historical, not midpoint + latest fallback.

## Files
- `MultiCurrencyRepository.kt`
- `MoneyNormalizationEngine.kt`
- `ExpenseDao.kt`
- tests

## Tasks
- [ ] Replace midpoint bucket conversion in historical purchase total.
- [ ] Load real expenses for the requested period.
- [ ] Filter explicitly:
  - purchases only
  - exclude transfers/deposits
  - respect `isNotMine`
- [ ] Call:

```kotlin
normalizationEngine.aggregateExpenses(
    expenses = expenses,
    homeCurrency = homeCurrency,
    rateBasis = RateBasis.TRANSACTION_DATE,
    transactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
)
```

- [ ] Do not fallback to `LATEST_AVAILABLE`.
- [ ] If historical rates are missing:
  - exclude affected rows
  - mark `MoneyAggregate.isPartial = true`
  - populate missing-rate metadata
- [ ] Rename/keep midpoint method only if explicitly labeled:

```kotlin
getPurchaseAggregatePeriodMidpointEstimate(...)
```

and set:
- `requestedRateBasis = PERIOD_MIDPOINT_ESTIMATE`
- `conversionQuality = ESTIMATED`

## Tests
- [ ] `historical_purchase_total_uses_each_expense_date`
- [ ] `historical_purchase_total_does_not_use_latest_fallback`
- [ ] `historical_missing_rate_marks_partial`
- [ ] `historical_purchase_excludes_deposits_and_transfers`

## Done when
Historical totals never silently use latest or midpoint rates unless method name and metadata explicitly say estimate.

---

# PR 2 — Remove fake EUR unavailable containers

## Goal
Unavailable home-currency states must not look like valid EUR money.

## Files
- `DashboardNormalizedInput.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- `BudgetForecastingEngine.kt`
- UI/view models
- tests

## Tasks
- [ ] Replace fallback containers like:

```kotlin
homeCurrency = CurrencyCode.EUR
dataQuality = UNAVAILABLE
```

with a typed result:

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

- [ ] Make dashboard callers handle unavailable explicitly.
- [ ] For budget forecast, add typed unavailable state or fields:

```kotlin
currencyStatus = HOME_CURRENCY_UNAVAILABLE
currency = null // if schema allows
```

If DB schema requires non-null `currency`, use:
- `currency = ""`
- `riskLevel = UNKNOWN`
- `conversionStatus = HOME_CURRENCY_UNAVAILABLE`

but do not let UI treat it as EUR.

## Tests
- [ ] `dashboard_home_currency_failure_returns_unavailable_result`
- [ ] `dashboard_unavailable_result_has_no_eur_money_container`
- [ ] `budget_forecast_home_currency_failure_not_labeled_eur`
- [ ] `budget_forecast_home_currency_failure_risk_unknown`

## Done when
A failed home-currency resolution cannot be mistaken for a valid EUR total.

---

# PR 3 — Spending trend quality propagation

## Goal
Trend widgets must show when conversion failures caused dropped rows.

## Files
- `ComputeDashboardWidgetsUseCase.kt`
- dashboard widget models
- UI renderers
- tests

## Tasks
- [ ] Change trend computation to return:

```kotlin
data class SpendingTrendResult(
    val series: List<SpendingTrendSeries>,
    val currencyQuality: CurrencyQualityUi
)
```

- [ ] Count:
  - included transactions
  - excluded transactions
  - missing rates
  - stale rates
  - invalid currencies
- [ ] Set widget:

```kotlin
DashboardWidget.SpendingTrend(
    series = trendSeries,
    currencyQuality = quality
)
```

- [ ] If any failed conversion:
  - `isPartial = true`
  - warning: “Some transactions were excluded because exchange rates are missing.”

## Tests
- [ ] `spending_trend_missing_rate_marks_partial`
- [ ] `spending_trend_warning_counts_excluded_rows`
- [ ] `spending_trend_does_not_silently_drop_failed_rows`

## Done when
Skipped conversion rows are visible to users/debuggers.

---

# PR 4 — Make DashboardNormalizedInput the real widget source

## Goal
The canonical normalized input must feed summary/category/trend/Monte Carlo/runway, not just exist.

## Files
- `ComputeDashboardWidgetsUseCase.kt`
- `DashboardNormalizedInput.kt`
- forecast/runway adapters
- dashboard tests

## Tasks
- [ ] In `compute()`, call `produceDashboardNormalizedInput(...)` once.
- [ ] Pass normalized input into:
  - period summary
  - top categories
  - spending trend
  - Monte Carlo
  - runway/forecast where money is summed
- [ ] Remove duplicate ad-hoc conversions in widget methods.
- [ ] Ensure category totals and period summary use the same `RateBasis.TRANSACTION_DATE`.
- [ ] Propagate `CurrencyDataQuality` to all money widgets.
- [ ] Do not build financial sums from raw `DashboardExpense.effectiveAmount`.

## Tests
- [ ] `dashboard_summary_category_trend_share_same_rate_basis`
- [ ] `dashboard_category_total_matches_period_aggregate`
- [ ] `dashboard_monte_carlo_uses_normalized_input`
- [ ] `dashboard_runway_uses_normalized_values`

## Done when
Dashboard money widgets are basis-consistent and quality-aware.

---

# PR 5 — Forecast/runway normalized input migration

## Goal
Forecast/runway synthesis must not consume raw mixed-currency `ExpenseSnapshot`.

## Files
- `ComputeDashboardWidgetsUseCase.kt`
- `ForecastInputAssembler.kt`
- `SynthesisEngine.kt`
- forecast models/tests

## Tasks
- [ ] Add:

```kotlin
data class NormalizedForecastInput(
    val actualExpenses: List<NormalizedExpense>,
    val plannedExpenses: List<NormalizedPlannedExpense>,
    val recurringPatterns: List<NormalizedRecurringPattern>,
    val homeCurrency: CurrencyCode,
    val currencyQuality: CurrencyDataQuality
)
```

- [ ] Convert dashboard expenses through `MoneyNormalizationEngine` before synthesis.
- [ ] Planned/recurring future items should use:
  - `RateBasis.FORECAST_DATE`
  - occurrence date as `atMillis`
- [ ] If conversion fails:
  - exclude item
  - mark forecast partial
  - expose warning
- [ ] Stop computing runway totals from raw snapshot amounts.

## Tests
- [ ] `forecast_input_uses_normalized_expenses`
- [ ] `recurring_forecast_uses_forecast_date_basis`
- [ ] `runway_committed_likely_use_same_currency`
- [ ] `forecast_missing_rate_marks_partial`

## Done when
Forecast and runway paths cannot mix raw currencies.

---

# PR 6 — Latest-rate staleness policy

## Goal
Latest-rate conversions must either check staleness or explicitly document freshness-blind behavior.

## Files
- `MoneyNormalizationEngine.kt`
- `CurrencyConverter.kt`
- settings/config if needed
- tests

## Tasks
- [ ] Define latest-rate stale policy:

```kotlin
val LatestRateStalePolicy = StaleRatePolicy(
    maxAgeMs = 7.days,
    compareAgainst = StaleRateReference.NOW
)
```

- [ ] Use it for `RateBasis.LATEST_AVAILABLE`.
- [ ] Keep `StaleRatePolicy.None` only for explicitly allowed contexts.
- [ ] If stale:
  - conversion should fail or mark aggregate stale/partial depending policy.
- [ ] Add metadata:
  - `staleRateCount`
  - `oldestRateValidDate`
  - `latestRateValidDate`

## Tests
- [ ] `latest_rate_stale_marks_aggregate_partial`
- [ ] `latest_rate_fresh_is_complete`
- [ ] `normalization_engine_does_not_use_stalePolicyNone_by_default`

## Done when
Latest-rate numbers have an explicit freshness policy.

---

# PR 7 — Legacy API containment

## Goal
Old latest-rate/Double APIs must not be used by production dashboard/budget/forecast paths.

## Files
- `MultiCurrencyRepository.kt`
- all callers
- guard script
- tests

## Tasks
- [ ] Raise ambiguous APIs to `DeprecationLevel.ERROR` after migration.
- [ ] Keep only clearly named APIs:
  - `getPurchaseAggregateHistorical`
  - `getPurchaseAggregateLatestRate`
  - `getCategoryAggregatesHistorical`
  - `getCategoryAggregatesLatestRate`
- [ ] Remove or restrict:
  - `Result<Double>` aggregate methods
  - `Map<..., Double>` aggregate methods
  - old `get...InHomeCurrency` methods
- [ ] Add KDoc that states exact rate basis for each remaining method.

## Tests
- [ ] `dashboard_does_not_call_legacy_latest_rate_api`
- [ ] `budget_does_not_call_legacy_double_api`
- [ ] `legacy_aggregate_apis_are_error_deprecated`

## Done when
Production code cannot accidentally call ambiguous aggregate methods.

---

# PR 8 — Static money guard v3

## Goal
The guard should catch the exact remaining regression patterns.

## Files
- `scripts/verify_money_boundaries.py`
- CI
- guard fixture tests

## Add/strengthen rules
- [ ] No `convertAsOf(...) ?: convert(...)`.
- [ ] No `homeCurrency().first()` in financial math.
- [ ] No `CurrencyCode.EUR` fallback in unavailable result builders.
- [ ] No `currency = "EUR"` inside failure/unknown forecast branches.
- [ ] No `MoneyAggregate(...)` without explicit basis/quality.
- [ ] No `convertMultiple(...)` outside explicit latest-rate APIs.
- [ ] No raw `ExpenseSnapshot(amount/effectiveAmount)` passed into synthesis without normalization.
- [ ] No `sumOf { it.effectiveAmount }` except on already-normalized model types.

## Tests
- [ ] `guard_flags_fake_eur_unavailable_container`
- [ ] `guard_flags_raw_snapshot_forecast_input`
- [ ] `guard_flags_trend_skip_without_quality`
- [ ] `guard_flags_convertMultiple_in_historical_method`
- [ ] `guard_allows_explicit_latest_rate_api`

## Done when
CI catches the known bad patterns found in this review.

---

# PR 9 — Real integration tests

## Goal
Prove live behavior with Room/repositories/use cases, not only fake-store logic.

## Add tests
- `ExchangeRateDaoIntegrationTest`
- `MultiCurrencyRepositoryHistoricalIntegrationTest`
- `DashboardCurrencyIntegrationTest`
- `BudgetForecastCurrencyIntegrationTest`
- `CashFlowCurrencyIntegrationTest`
- `MoneyBoundaryGuardTest`

## Required scenarios
- [ ] Room latest/as-of ordering with multiple valid dates.
- [ ] Historical purchase total uses transaction-date rates.
- [ ] Historical missing rate marks partial.
- [ ] Dashboard trend reports partial quality.
- [ ] Dashboard unavailable home currency has no EUR money container.
- [ ] Forecast unavailable home currency is UNKNOWN and not EUR-labeled.
- [ ] Forecast/runway uses normalized inputs.
- [ ] Guard fails on fixture regressions.

## Done when
The bugs listed above fail before fixes and pass after fixes.

---

# PR 10 — Docs and tracker cleanup

## Files
- `docs/currency/rate-basis-policy.md`
- `docs/currency/money-aggregate-contract.md`
- `docs/currency/money-boundary-guard.md`
- debugging master tracker

## Tasks
- [ ] Document unavailable home-currency states.
- [ ] Document dashboard normalized input ownership.
- [ ] Document latest-rate staleness policy.
- [ ] Document allowed legacy APIs or mark removed.
- [ ] Update tracker with fixed/open status.
- [ ] Add “do not use fake EUR container” rule.

---

# Recommended order

```text
1. PR1 Historical aggregate correctness
2. PR2 Remove fake EUR unavailable containers
3. PR3 Spending trend quality
4. PR4 Dashboard normalized input migration
5. PR5 Forecast/runway normalization
6. PR6 Latest-rate staleness policy
7. PR7 Legacy API containment
8. PR8 Static guard v3
9. PR9 Integration tests
10. PR10 Docs cleanup
```

## Final definition of done

```text
1. Historical totals use transaction-date rates only.
2. Missing historical rates create partial aggregates, not latest fallback.
3. Unavailable home currency is typed, not fake EUR.
4. Dashboard summary/category/trend/forecast share normalized input.
5. Trend widgets expose conversion quality.
6. Forecast/runway consume normalized values only.
7. Latest-rate conversions have explicit staleness policy.
8. Legacy ambiguous APIs are removed or error-deprecated.
9. Static guard catches raw/fallback regressions.
10. Integration tests prove real DAO/repository/dashboard behavior.
```

## Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/9a6afc438093ed6a03c7f831d3e4acd41a7f2a40
- `MultiCurrencyRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/9a6afc438093ed6a03c7f831d3e4acd41a7f2a40/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt
- `ComputeDashboardWidgetsUseCase.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/9a6afc438093ed6a03c7f831d3e4acd41a7f2a40/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- `BudgetForecastingEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/9a6afc438093ed6a03c7f831d3e4acd41a7f2a40/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt
- `MoneyNormalizationEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/9a6afc438093ed6a03c7f831d3e4acd41a7f2a40/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyNormalizationEngine.kt
- `verify_money_boundaries.py`: https://raw.githubusercontent.com/panospao7/Cost-agregator/9a6afc438093ed6a03c7f831d3e4acd41a7f2a40/scripts/verify_money_boundaries.py