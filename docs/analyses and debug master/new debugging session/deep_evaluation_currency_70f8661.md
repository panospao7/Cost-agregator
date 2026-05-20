# Deep Evaluation / Debugging Report — Currency Normalization

Commit reviewed: `70f866194ba5991e126a7b8491881d4d4e90ab68`  
Commit title: `feat: complete currency normalization PRs A-H (CURR-C62 fixes)`

Source:  
https://github.com/panospao7/Cost-agregator/commit/70f866194ba5991e126a7b8491881d4d4e90ab68

---

## Executive verdict

This commit is a **large and meaningful improvement** over `c62de2b`, but it does **not fully close global issue #4** yet.

It fixes several important problems from the previous review:

- New `CurrencyConverter.storeRate(...)` and `storeRates(...)` now set `validDate`.
- Migration `130_131` now backfills `validDate` to start-of-day instead of exact `lastUpdated`.
- `convertOutcome(...)` now fails if historical bases are called without `atMillis`.
- Stale historical checks now use `validDate` instead of only `lastUpdated`.
- `MoneyAggregate` now has `requestedRateBasis`, `actualRateBasis`, `conversionQuality`, and `metadata`.
- `MoneyNormalizationEngine.aggregateExpenses(...)` and its own `aggregateBuckets(...)` now set basis and metadata.
- Budget hard-failure fallback no longer returns the raw source-currency amount.
- Dashboard spending trend now uses `convertAsOf(...)` instead of raw fallback.
- A money-boundary guard script exists.
- Some regression tests were added.

But several critical correctness gaps remain.

The biggest remaining issues are:

1. `PERIOD_MIDPOINT_ESTIMATE` still silently uses latest-rate lookup inside `convertOutcome(...)`.
2. Composite EUR-bridge conversions still lose weakest-leg provenance.
3. `MoneyNormalizationEngine` still drops `rateValidDate` and rate-last-updated provenance.
4. The old `MoneyAggregateBuilder.fromBuckets(...)` remains latest-rate-only but accepts any `rateBasis`.
5. `MultiCurrencyRepository` is still largely legacy/latest-rate-centric.
6. `getHomeCurrencyPurchaseTotalHistorical(...)` still uses midpoint buckets and latest fallback.
7. Budget forecast still hides latest-rate fallback as successful conversion.
8. Cashflow predicted recurring items still use latest rates instead of forecast-date rates.
9. Dashboard widgets are still not fed by one canonical normalized input.
10. Static guard is too weak and misses many live patterns.
11. Tests are useful but still mostly unit/fake based, not full live-path behavioral coverage.

So: **the foundation is much stronger, but global issue #4 should remain open.**

---

# 1. Confirmed fixes

## 1.1 New exchange-rate inserts now usually get `validDate`

In `CurrencyConverter.storeRate(...)` and `storeRates(...)`, the code now computes:

```kotlin
effectiveValidDate = validDate ?: startOfDay(now)
```

and stores it into `DomainExchangeRate.validDate`.

This addresses the previous issue where new manual/API rates could be inserted with `validDate = 0`, causing latest-rate lookup to prefer older historical rows.

### Caveat

`ExchangeRateStoreAdapter.toEntity()` still maps:

```kotlin
validDate = validDate ?: 0L
```

So any code path that bypasses `CurrencyConverter.storeRate(...)` and calls `exchangeRateStore.insertOrUpdate(...)` directly with `validDate = null` can still create `validDate = 0`.

### Status

**Mostly fixed, but not enforced at storage boundary.**

---

## 1.2 Migration now backfills `validDate` to start-of-day

`MIGRATION_130_131` now uses:

```sql
UPDATE exchange_rates
SET validDate = (lastUpdated / 86400000) * 86400000
WHERE validDate = 0 AND lastUpdated > 0
```

This fixes the previous same-day lookup bug where a rate updated at 13:00 would not apply to a 09:00 transaction on the same day.

### Caveat

If users/devices already ran the old `130_131` migration from the previous commit, changing the migration body will not rerun for those already at schema version 131. If this has not shipped, fine. If it has shipped, add a new `131_132` correction migration.

### Status

**Fixed for fresh migration path; needs release-state check.**

---

## 1.3 `convertOutcome(...)` fails on missing date for most historical bases

The new check covers:

- `TRANSACTION_DATE`
- `PERIOD_START`
- `PERIOD_END`
- `FORECAST_DATE`
- `PERIOD_MIDPOINT_ESTIMATE`

This fixes the prior silent downgrade where historical requests without dates became latest-rate conversions.

### Status

**Mostly fixed.**

But see `CURR-70F-01`: `PERIOD_MIDPOINT_ESTIMATE` is still broken when a date is provided.

---

## 1.4 Stale historical checks now use `validDate`

For historical conversion, the code compares:

```text
transaction/period date vs rate validDate
```

instead of comparing against `lastUpdated`.

This is the correct direction because backfilled historical rates may be inserted today but valid for an old date.

### Status

**Improved.**

But see `CURR-70F-02`: `StaleRatePolicy.compareAgainst` is still effectively ignored.

---

## 1.5 `MoneyAggregate` now has quality and metadata fields

Added:

- `requestedRateBasis`
- `actualRateBasis`
- `conversionQuality`
- `metadata`

This is a good implementation of the v2 shape from the plan.

### Status

**Model-level foundation fixed.**

But not all creators populate the fields correctly.

---

## 1.6 Budget hard-failure raw fallback removed

`BudgetRepository.convertBudgetAmountToHomeCurrencyAsOf(...)` no longer returns a raw source-currency `MoneyAggregate` if conversion fully fails.

It now returns a home-currency empty/partial aggregate with `ConversionQuality.UNAVAILABLE`.

This is a real fix.

### Status

**Hard-failure path improved.**

But latest fallback and mixed basis still need cleanup.

---

## 1.7 Dashboard spending trend no longer raw-fallbacks

`computeSpendingTrend(...)` now uses:

```kotlin
currencyConverter.convertAsOf(...)
```

and skips rows when conversion fails.

This removes one explicit `converted ?: effectiveAmount` bug.

### Status

**Specific trend raw fallback fixed.**

But dashboard is still not canonical-normalized end-to-end.

---

## 1.8 Static guard script added

`scripts/verify_money_boundaries.py` exists and checks for several bad patterns.

### Status

**Good start, but too weak.**

See `CURR-70F-13`.

---

# 2. High-priority remaining issues

---

## CURR-70F-01 — `PERIOD_MIDPOINT_ESTIMATE` still silently uses latest rates

Severity: **High**  
Type: **actual rate-basis bug**

### Problem

`convertOutcome(...)` includes `PERIOD_MIDPOINT_ESTIMATE` in the “requires atMillis” list, which is good.

But the actual historical lookup switch is:

```kotlin
val useHistorical = rateBasis in listOf(
    TRANSACTION_DATE,
    PERIOD_START,
    PERIOD_END,
    FORECAST_DATE
)
```

`PERIOD_MIDPOINT_ESTIMATE` is missing.

So this call:

```kotlin
convertOutcome(
    amount = 100.0,
    fromCurrency = "USD",
    toCurrency = "EUR",
    rateBasis = RateBasis.PERIOD_MIDPOINT_ESTIMATE,
    atMillis = midpoint
)
```

passes the date requirement but then goes to the latest-rate branch.

### Impact

A caller can believe it is using midpoint historical conversion while actually using latest-rate conversion.

This violates the core invariant:

```text
Every aggregate must declare and actually use rate basis.
```

### Fix

Include `PERIOD_MIDPOINT_ESTIMATE` in `useHistorical`, or explicitly treat it as an estimated historical lookup:

```kotlin
val useHistorical = rateBasis in listOf(
    RateBasis.TRANSACTION_DATE,
    RateBasis.PERIOD_START,
    RateBasis.PERIOD_END,
    RateBasis.FORECAST_DATE,
    RateBasis.PERIOD_MIDPOINT_ESTIMATE
)
```

### Tests

Add:

```text
convertOutcome_period_midpoint_uses_as_of_rate
convertOutcome_period_midpoint_does_not_use_latest_rate
```

---

## CURR-70F-02 — `StaleRatePolicy.compareAgainst` is ignored

Severity: **Medium/High**  
Type: **policy/architecture bug**

### Problem

`StaleRatePolicy` exposes:

```kotlin
StaleRateReference.NOW
StaleRateReference.TRANSACTION_DATE
StaleRateReference.RATE_VALID_DATE
```

But `convertOutcome(...)` does not actually branch on `stalePolicy.compareAgainst`.

It uses:

```text
if historical: atMillis vs validDate
else: now vs lastUpdated
```

regardless of what the caller requested.

### Impact

The API suggests callers can control staleness basis, but they cannot.

This can create misleading tests and incorrect stale/fresh classification.

### Fix

Implement:

```kotlin
val referenceTime = when (stalePolicy.compareAgainst) {
    NOW -> timeProvider.now()
    TRANSACTION_DATE -> atMillis ?: fail
    RATE_VALID_DATE -> rateResult.validDate ?: rateResult.lastUpdated
}
```

Then decide which rate timestamp to compare against according to policy.

### Tests

```text
stalePolicy_NOW_uses_now_reference
stalePolicy_TRANSACTION_DATE_uses_atMillis_reference
stalePolicy_RATE_VALID_DATE_uses_rate_validDate_reference
```

---

## CURR-70F-03 — Composite EUR conversion still loses weakest-leg provenance

Severity: **Medium/High**  
Type: **conversion provenance bug**

### Problem

For EUR-bridge conversion:

```kotlin
RateResult(
    rate = toEur.rate * fromEur.rate,
    validDate = toEur.validDate ?: fromEur.validDate,
    lastUpdated = maxOf(toEur.lastUpdated, fromEur.lastUpdated),
    source = toEur.source,
    path = VIA_BASE_CURRENCY
)
```

This still does not preserve both legs.

Issues:

- `toEur.validDate ?: fromEur.validDate` does not choose the oldest/weakest valid date.
- `source` only records one leg.
- stale evaluation can be too optimistic.
- metadata cannot explain which leg caused quality loss.

### Fix

Minimum:

```kotlin
validDate = minOfNonNull(toEur.validDate, fromEur.validDate)
lastUpdated = minOf(toEur.lastUpdated, fromEur.lastUpdated) // for freshness, weakest leg
source = "${toEur.source}+${fromEur.source}"
```

Better:

```kotlin
data class RateLeg(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: Double,
    val validDate: Long?,
    val lastUpdated: Long?,
    val source: String?
)
```

and add legs to `ConversionOutcome.Converted`.

### Tests

```text
composite_rate_uses_oldest_validDate_for_staleness
composite_rate_records_via_base_path
composite_rate_metadata_includes_both_legs
```

---

## CURR-70F-04 — Storage layer still permits `validDate = 0`

Severity: **Medium/High**  
Type: **data integrity gap**

### Problem

`CurrencyConverter.storeRate(...)` sets `validDate`, but `ExchangeRateStoreAdapter.toEntity()` still does:

```kotlin
validDate = validDate ?: 0L
```

So any caller that inserts through `ExchangeRateStore` directly can still create undated rows.

### Fix

Move enforcement into the storage boundary.

Options:

1. Make `DomainExchangeRate.validDate` non-null for production inserts.
2. Reject insert/update if `validDate == null`.
3. Fill it in adapter using an injected clock.
4. Add a DAO-level cleanup before insert.

Best:

```kotlin
require(rate.validDate != null && rate.validDate > 0) {
    "Exchange rates must have validDate"
}
```

except for explicit test fixtures/migrations.

### Tests

```text
exchangeRateStore_rejects_null_validDate_in_production_insert
direct_store_insert_cannot_create_validDate_zero
```

---

## CURR-70F-05 — `MoneyNormalizationEngine` still drops rate provenance

Severity: **High**  
Type: **architecture/provenance bug**

### Problem

`normalizeExpense(...)` receives `ConversionOutcome.Converted`, but `toNormalizedExpense(...)` only takes:

```kotlin
rateUsed
rateBasis
path
```

and then writes:

```kotlin
rateValidDate = null
```

The `NormalizedExpense` model now has `rateValidDate`, but the engine does not populate it.

It also does not expose:

- `rateLastUpdated`
- failure type
- requested vs actual basis
- conversion source

### Impact

Downstream analytics/forecast/export cannot prove which rate was used.

### Fix

Change mapper signature:

```kotlin
private fun Expense.toNormalizedExpense(
    homeCurrency: CurrencyCode,
    outcome: ConversionOutcome.Converted,
    requestedRateBasis: RateBasis
): NormalizedExpense
```

Set:

```kotlin
rateBasis = outcome.rateBasis.name
rateUsed = outcome.rateUsed
rateValidDate = outcome.rateValidDate
conversionPath = outcome.conversionPath.name
```

Add `rateLastUpdated` to `NormalizedExpense`.

For identity:

- requested basis should be preserved somewhere
- actual basis/path should show identity

### Tests

```text
normalizeExpense_includes_rateValidDate
normalizeExpense_includes_rateUsed
normalizeExpense_includes_conversionPath
normalizeExpense_identity_preserves_requested_and_actual_basis
```

---

## CURR-70F-06 — Old `MoneyAggregateBuilder.fromBuckets(...)` can lie about rate basis

Severity: **High**  
Type: **actual aggregate metadata bug**

### Problem

The legacy overload:

```kotlin
fromBuckets(
    buckets: List<Pair<Double, String>>,
    homeCurrency: String,
    converter: CurrencyConverter,
    transactionCounts: List<Int> = emptyList(),
    rateBasis: RateBasis = LATEST_AVAILABLE
)
```

always uses:

```kotlin
converter.convertMultiple(...)
```

`convertMultiple(...)` is latest-rate conversion.

But this overload accepts any `rateBasis` and returns it in `MoneyAggregate`.

So a caller could pass:

```kotlin
rateBasis = TRANSACTION_DATE
```

and receive latest-rate math labeled as transaction-date.

### Current usage risk

Many repositories still use this old overload through `MultiCurrencyRepository`.

### Fix

Options:

1. Restrict this overload to latest only:

```kotlin
require(rateBasis == RateBasis.LATEST_AVAILABLE)
```

2. Remove the `rateBasis` parameter from the legacy overload.
3. Deprecate it with error and migrate callers to the typed overload or `MoneyNormalizationEngine`.

### Tests

```text
legacy_fromBuckets_rejects_non_latest_rateBasis
legacy_fromBuckets_labels_latest_available_only
```

---

## CURR-70F-07 — Typed `MoneyAggregateBuilder.fromBuckets(...)` still does not explicitly enforce `RequireBucketDate`

Severity: **Medium**

### Problem

`MoneyNormalizationEngine.aggregateBuckets(...)` now enforces `RequireBucketDate`.

But `MoneyAggregateBuilder.fromBuckets(...)` typed overload still does:

```kotlin
is BucketDatePolicy.RequireBucketDate -> bucket.bucketDate
```

and passes null to `convertOutcome(...)`.

Because `convertOutcome(...)` now fails historical/null-date calls, this no longer silently uses latest, but the builder still relies on lower-level failure instead of enforcing the policy itself.

### Fix

Mirror `MoneyNormalizationEngine` behavior:

```kotlin
if (bucketDatePolicy is RequireBucketDate && bucket.bucketDate == null) {
    add failure
    continue
}
```

### Tests

```text
builder_requireBucketDate_missing_date_fails_before_conversion
builder_requireBucketDate_does_not_call_converter_when_missing_date
```

---

## CURR-70F-08 — `MultiCurrencyRepository` remains largely legacy/latest-rate centric

Severity: **High**  
Type: **major architecture gap and user-facing reporting risk**

### Problem

`MultiCurrencyRepository` still contains many legacy paths:

- `Result<Double>` APIs
- `Map<..., Double>` APIs
- `getHomeCurrencyTotal(...)`
- `getHomeCurrencyCategoryTotals(...)`
- `getHomeCurrencyMerchantTotals(...)`
- `getHomeCurrencyMonthlyTotals(...)`
- `getHomeCurrencyWeeklyTotals(...)`
- `getHomeCurrencyDailyTotals(...)`

Many use latest-rate `convertMultiple(...)`.

Some methods are deprecated, but production code can still call them.

### High-risk method

`getHomeCurrencyPurchaseTotalHistorical(...)` still:

1. groups by currency,
2. converts at period midpoint,
3. falls back to latest rate if historical rate is unavailable,
4. returns `MoneyAggregate(...)` without explicit `rateBasis`, so default labels can be wrong.

This violates:

```text
Historical reports must use transaction-date conversion or explicitly label estimates/mixed basis.
```

### Fix

Create explicit API families:

```kotlin
getPurchaseAggregateHistorical(...)
getPurchaseAggregateLatestRate(...)
getCategoryAggregatesHistorical(...)
getCategoryAggregatesLatestRate(...)
getDailyAggregatesHistorical(...)
getWeeklyAggregatesHistorical(...)
```

Historical methods should use `MoneyNormalizationEngine.aggregateExpenses(...)` per row.

Deprecate legacy APIs at `ERROR` level after migration.

### Tests

```text
purchase_historical_uses_each_expense_date
historical_purchase_total_does_not_fallback_to_latest
legacy_double_apis_are_not_used_by_dashboard_or_budget
```

---

## CURR-70F-09 — HomeCurrencyResolution exists but is still underused

Severity: **High**  
Type: **financial failure semantics bug**

### Problem

`CurrencySettingsRepository.resolveHomeCurrency()` exists, but many production paths still call:

```kotlin
homeCurrency().first()
```

Examples:

- `BudgetRepository.resolveHomeCurrency()`
- `BudgetForecastingEngine.generateForecast(...)`
- `CashFlowCalculator.calculateDailyCashFlow(...)`
- `ComputeDashboardWidgetsUseCase.computeSpendingTrend(...)`

Some paths now throw instead of silently using EUR. That is safer than silent EUR, but still not the target state.

The target state was:

```text
Failed home currency resolution -> partial/unavailable typed result
FirstRunDefault -> explicit defaulted result
```

### Impact

A DataStore read failure can now crash/drop widgets or fail jobs instead of producing a typed unavailable state.

### Fix

Use:

```kotlin
currencySettingsRepository.resolveHomeCurrency()
```

and handle:

```kotlin
Resolved -> proceed
FirstRunDefault -> proceed with explicit default flag
Failed -> return unavailable/partial model
```

### Tests

```text
dashboard_home_currency_failure_returns_unavailable_widget_not_exception
budget_home_currency_failure_status_unknown
cashflow_home_currency_failure_returns_partial_result
forecast_home_currency_failure_records_unavailable_forecast
```

---

## CURR-70F-10 — BudgetRepository still mixes or hides basis in several cases

Severity: **High**

### Good fix

If budget limit conversion fully fails, it no longer returns raw source amount.

### Remaining issues

1. `convertBudgetAmountToHomeCurrencyAsOf(...)` still falls back to latest rate if historical is missing.
2. That fallback is marked `isPartial`, and `createBudgetStatus(...)` treats `isPartial` as `budgetConversionFailed`.
3. But it still stores `baseLimit` and `effectiveLimit` using the latest-rate amount.
4. Rollover calculations still run even when the budget limit basis is partial/mixed.
5. `BudgetStatus` still has non-null numeric fields, so UNKNOWN status may still carry misleading numeric values.

### Fix

If latest fallback is used for a historical budget limit, either:

- block numeric percent/remaining/effectiveLimit, or
- expose explicit basis fields and `BudgetReliability`.

Recommended:

```kotlin
data class NormalizedBudgetStatus(
    val limit: MoneyAggregate?,
    val spent: MoneyAggregate,
    val remaining: MoneyAggregate?,
    val percentUsed: Double?,
    val reliability: BudgetReliability,
    val health: BudgetHealthStatus
)
```

Short-term:

- if `initialLimitAggregate.isPartial`, skip rollover arithmetic
- set `effectiveLimit = 0.0`
- set `remainingAmount = 0.0`
- set `percentUsed = 0f`
- make UI display unknown, not numeric values

### Tests

```text
budget_latest_fallback_marks_unknown_and_skips_rollover
budget_limit_partial_does_not_compute_effectiveLimit_from_mixed_basis
budget_limit_conversion_failure_health_unknown
```

---

## CURR-70F-11 — BudgetForecastingEngine hides latest-rate fallback

Severity: **High**  
Type: **forecast correctness bug**

### Problem

`BudgetForecastingEngine.generateForecast(...)` does:

```kotlin
currencyConverter.convertAsOf(...)
    ?: currencyConverter.convert(...)
```

If latest-rate conversion succeeds, it sets:

```kotlin
budgetConversionFailed = false
```

No warning or partial state is carried.

### Impact

A forecast can appear reliable while using latest-rate fallback instead of period-end historical rate.

Also, if conversion fails completely, the code persists:

```kotlin
riskLevel = ForecastRiskLevel.LOW
```

That is misleading; it should be `UNKNOWN`/`UNAVAILABLE` if the enum supports it, or a new status should be added.

### Fix

Use `convertOutcome(...)` with `RateBasis.PERIOD_END`.

If failed:

- forecast unavailable or partial
- risk unknown
- confidence 0
- explicit warning/status

If latest fallback is intentionally allowed:

- mark `conversionQuality = ESTIMATED` or `MIXED_BASIS`
- expose warning in `BudgetForecast`

### Tests

```text
budget_forecast_does_not_hide_latest_fallback
budget_forecast_missing_historical_rate_is_partial_or_unavailable
budget_forecast_conversion_failure_is_not_low_risk
```

---

## CURR-70F-12 — Cashflow actuals improved, but recurring predictions still use latest rates

Severity: **Medium/High**

### Good

Actual income/expenses now use:

```kotlin
convertAsOf(..., expense.date)
```

This is correct for actual transactions.

### Remaining problem

Predicted recurring items use:

```kotlin
currencyConverter.convert(...)
```

which is latest-rate conversion.

For forecasted occurrences, the expected basis should be:

```text
FORECAST_DATE
```

or a documented explicit policy.

### Also missing

No per-line normalized model exists:

```text
originalAmount
originalCurrency
displayAmount
displayCurrency
conversionFailure
rateBasis
```

Only daily `isPartial` and `failedConversionCount` are surfaced.

### Fix

Use:

```kotlin
convertOutcome(
    amount = recurring.averageAmount,
    fromCurrency = recurring.currency,
    toCurrency = homeCurrency,
    rateBasis = RateBasis.FORECAST_DATE,
    atMillis = currentDay.time
)
```

Add line-item conversion provenance or at least day-level failure details.

### Tests

```text
cashflow_recurring_uses_forecast_date_basis
cashflow_line_item_contains_conversion_failure
cashflow_day_partial_includes_failure_details
```

---

## CURR-70F-13 — Dashboard migration is still partial

Severity: **High**

### What is fixed

`computeSpendingTrend(...)` now uses transaction-date conversion and skips failed rows.

### What remains

1. Missing conversions in trend are silently skipped without a trend-level partial warning.
2. Category totals come from `ctx.data.categoryBreakdown`, not from one canonical normalized input.
3. Monte Carlo uses `getHomeCurrencyPurchaseTotal(...)`, which is latest-rate.
4. Runway/forecast paths still depend on upstream values whose basis is not proven consistent.
5. `CompiledDashboardData.isPartial` is too coarse and not carried into all widgets.

### Fix

Introduce:

```kotlin
DashboardNormalizedInput(
    homeCurrency,
    period,
    normalizedExpenses,
    aggregate,
    dataQuality
)
```

All widgets should consume from that.

### Tests

```text
dashboard_summary_category_trend_share_same_rate_basis
dashboard_trend_missing_rate_marks_partial
dashboard_monte_carlo_uses_same_basis_as_month_summary
dashboard_home_currency_failure_returns_unavailable_widgets
```

---

## CURR-70F-14 — Static money-boundary guard is too weak

Severity: **High regression risk**

### Problems

`scripts/verify_money_boundaries.py` is a good start, but it misses many important patterns:

- Does not flag `currencyConverter.convert(...)` in aggregate/forecast paths.
- Does not flag `convertMultiple(...)`.
- Does not flag `homeCurrency().first()` in financial math.
- Does not flag hidden latest fallback `convertAsOf(...) ?: convert(...)`.
- Does not flag `MoneyAggregate(...)` constructor calls missing explicit basis.
- Does not flag old `MoneyAggregateBuilder.fromBuckets(...)` latest-only overload misuse.
- Does not scan multiline expressions robustly.
- Has broad allowlists such as `displayAmount`, which can hide real math.
- Does not prove the script is wired into CI.

### Fix

Add rules:

```text
G-MONEY-01 no currencyConverter.convert(...) in dashboard/budget/forecast/cashflow aggregate math
G-MONEY-02 no convertAsOf(...) ?: convert(...) latest fallback
G-MONEY-03 no convertMultiple(...) except inside explicitly latest APIs
G-MONEY-04 no homeCurrency().first() in financial math; use resolveHomeCurrency()
G-MONEY-05 no MoneyAggregate(...) without explicit requested/actual basis
G-MONEY-06 no old MoneyAggregateBuilder.fromBuckets Pair overload outside latest-only wrappers
G-MONEY-07 no Result<Double> aggregate APIs in non-deprecated production code
```

### Tests

```text
money_guard_flags_latest_fallback_after_historical_failure
money_guard_flags_homeCurrency_first_in_financial_math
money_guard_flags_convertMultiple_in_historical_path
money_guard_flags_missing_moneyAggregate_basis
```

---

## CURR-70F-15 — Behavioral tests are helpful but still incomplete

Severity: **Medium/High**

### Good

New tests cover:

- latest vs historical basics
- missing date failure
- some aggregate metadata
- bucket date enforcement through `MoneyNormalizationEngine`

### Gaps

The tests are still mostly fake/unit tests.

Missing live-path coverage:

- real Room migration for `validDate`
- real DAO latest/as-of ordering
- real `MultiCurrencyRepository` historical behavior
- dashboard category/summary/trend consistency
- budget forecast latest fallback
- budget status rollover with failed limit conversion
- cashflow forecast-date basis
- static guard fixture tests
- import/export conversion provenance

### Specific weakness

The current fake store may not reflect Room uniqueness/order semantics exactly, so some exchange-rate tests can pass while DAO/migration behavior remains untested.

### Fix

Add integration tests using Room in-memory DB for exchange-rate and repository behavior.

### Tests to add

```text
room_migration_130_131_backfills_validDate_startOfDay
room_latest_rate_prefers_highest_validDate
room_getRateAsOf_uses_validDate_lte_date
multiCurrency_historical_uses_per_expense_transaction_dates
budget_forecast_latest_fallback_marks_partial
cashflow_recurring_uses_forecast_date
dashboard_widgets_share_rate_basis
```

---

# 3. Remaining issue status matrix

| Issue from previous review | Status after `70f8661` | Notes |
|---|---:|---|
| New rates `validDate = 0` | Mostly fixed | `CurrencyConverter` fixed; adapter still permits null → 0 |
| Migration start-of-day | Fixed if not shipped | Needs new migration if previous `130_131` shipped |
| Historical without `atMillis` | Mostly fixed | `PERIOD_MIDPOINT_ESTIMATE` still latest with date |
| Stale logic uses validDate | Partial | compareAgainst ignored |
| Composite provenance | Not done | still only one validDate/source |
| MoneyAggregate metadata | Partial/foundation | fields added, not complete propagation |
| NormalizedExpense provenance | Partial | `rateValidDate` still null |
| RequireBucketDate | Partial | engine enforces; builder relies on converter failure |
| Silent EUR fallback | Partial | many calls now throw; not typed unavailable |
| MultiCurrency API split | Not done | legacy/latest APIs remain |
| Dashboard canonical input | Not done | trend improved only |
| Budget remaining/rollover | Partial | hard failure safer, latest fallback still unclear |
| BudgetForecast raw fallback | Partial | no raw fallback, but hidden latest fallback |
| Cashflow basis | Partial | actuals historical, recurring latest |
| Static guard | Partial | exists but weak |
| Behavioral tests | Partial | useful but not enough |

---

# 4. Actual bugs vs architectural gaps

## Actual user-facing bugs

1. `PERIOD_MIDPOINT_ESTIMATE` uses latest-rate lookup.
2. Budget forecast can appear reliable while using latest fallback.
3. Cashflow predicted recurring items use latest instead of forecast-date basis.
4. Dashboard trend skips failed conversions without surfacing partial quality.
5. MultiCurrency historical purchase total still uses midpoint/latest fallback.
6. Composite conversions can underreport stale/weak-leg risk.
7. Home currency read failure can throw/drop reports instead of returning typed unavailable state.

## Architectural gaps

1. `MoneyNormalizationEngine` is not yet canonical everywhere.
2. `NormalizedExpense` provenance is incomplete.
3. Legacy `MoneyAggregateBuilder` overload is dangerous.
4. Static guard is too heuristic.
5. Tests do not yet exercise enough real production paths.
6. Import/export/source-boundary conversion provenance is not fully implemented.

---

# 5. Recommended next PR sequence

## PR 1 — Conversion semantics hardening

Fix:

- `PERIOD_MIDPOINT_ESTIMATE` latest-rate bug
- `StaleRatePolicy.compareAgainst`
- composite leg provenance
- storage-boundary validDate enforcement

Acceptance:

```text
period midpoint uses as-of lookup
stale policy is honored
composite uses weakest leg
no production insert can create validDate=0
```

---

## PR 2 — Normalize provenance completion

Fix:

- `MoneyNormalizationEngine` should populate rate valid date, last updated, path, source
- normalized models should distinguish requested vs actual basis
- metadata should count stale/missing/invalid correctly

Acceptance:

```text
every normalized row can explain its conversion
aggregate metadata matches row outcomes
```

---

## PR 3 — Kill or restrict legacy aggregate paths

Fix:

- old `MoneyAggregateBuilder.fromBuckets(Pair...)`
- `MultiCurrencyRepository` legacy `Double` / latest-rate methods
- hidden midpoint/latest fallback in historical method

Acceptance:

```text
historical APIs use per-expense transaction-date normalization
latest APIs are explicitly named latest
legacy APIs are ERROR-deprecated or internal
```

---

## PR 4 — Dashboard canonical normalized input

Fix:

- one normalized input for summary/category/trend/Monte Carlo/runway
- partial warnings propagate to widgets

Acceptance:

```text
dashboard summary/category/trend have same basis
missing rate produces visible partial state
home currency failure produces unavailable widget
```

---

## PR 5 — Budget/forecast/cashflow correctness

Fix:

- forecast latest fallback hidden as success
- cashflow recurring latest basis
- budget rollover with partial limit conversion

Acceptance:

```text
forecast conversion failure is unavailable/unknown
forecast latest fallback is labeled estimated
cashflow recurring uses FORECAST_DATE
budget partial limit skips misleading rollover
```

---

## PR 6 — Static guard v2 + live integration tests

Fix:

- guard direct `convert`, `convertMultiple`, `homeCurrency().first`, latest fallback
- add Room/DAO/repository/dashboard/budget/cashflow tests

Acceptance:

```text
guard catches known remaining bugs
tests fail on previous bad behavior
```

---

# 6. Updated definition of done

Global currency issue #4 is done only when:

1. No rate insert path can produce `validDate = 0`.
2. Historical bases always use as-of lookup.
3. `PERIOD_MIDPOINT_ESTIMATE` is not latest-rate.
4. Stale policy honors `compareAgainst`.
5. Composite conversion records weakest-leg provenance.
6. Every normalized row includes rate provenance.
7. Every aggregate has accurate requested/actual basis and quality metadata.
8. Historical APIs use per-expense transaction-date conversion.
9. Latest-rate APIs are explicitly named.
10. Dashboard widgets consume one normalized input.
11. Budget/forecast/cashflow never hide latest fallback as exact.
12. Home currency failure returns typed unavailable/partial output.
13. Static guard blocks raw mixed-currency and hidden fallback patterns.
14. Integration tests prove Room, repository, dashboard, budget, and cashflow behavior.

---

# Sources reviewed

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/70f866194ba5991e126a7b8491881d4d4e90ab68

- `CurrencyConverter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt

- `ExchangeRateDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt

- `ExchangeRateStoreAdapter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt

- `AppDatabase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- `MoneyAggregate.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyNormalizationEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyNormalizationEngine.kt

- `MoneyAggregateBuilder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

- `MultiCurrencyRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

- `BudgetRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

- `BudgetForecastingEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt

- `CashFlowCalculator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt

- `ComputeDashboardWidgetsUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

- `AnalyticsCurrencyNormalizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt

- `verify_money_boundaries.py`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/scripts/verify_money_boundaries.py

- `CurrencyNormalizationBehavioralTest.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/70f866194ba5991e126a7b8491881d4d4e90ab68/app/src/test/java/com/yourname/expensetracker/domain/currency/CurrencyNormalizationBehavioralTest.kt