# Deep Evaluation / Debugging Report — Global Currency Normalization

Commit reviewed: `c62de2be3f117f14f0a1dc9053ebc97ae21d883c`  
Commit title: `feat: global currency normalization — PRs 1-4, 5, 7, 8`

Source:  
https://github.com/panospao7/Cost-agregator/commit/c62de2be3f117f14f0a1dc9053ebc97ae21d883c

## 0. Executive verdict

This commit is a **good foundation commit**, but it is **not a complete fix for global issue #4**.

It adds several important building blocks:

- `RateBasis`
- `ConversionOutcome`
- `ConversionPath`
- `ConversionFailureType`
- `StaleRatePolicy`
- `HomeCurrencyResolution`
- `MoneyNormalizationEngine`
- rate-basis-aware `MoneyAggregateBuilder.fromBuckets(...)`
- `BudgetHealthStatus.UNKNOWN`
- `ExchangeRateDao.getLatestRateForPair(...)`
- dashboard spending trend no longer falls back to raw `effectiveAmount`

However, the implementation still has major correctness gaps.

The biggest remaining risks are:

1. **Newly stored exchange rates still often get `validDate = 0`, so latest-rate lookup can ignore fresh manual/API rates once historical rows exist.**
2. **`convertOutcome()` can silently use latest-rate conversion for historical rate bases when `atMillis` is missing.**
3. **Stale-rate logic in `convertOutcome()` compares against `lastUpdated` even when the selected policy should compare against `validDate`.**
4. **`MoneyAggregate.rateBasis` is not correctly populated in most new aggregate paths.**
5. **`MoneyNormalizationEngine` exists but is not actually the canonical path for dashboard/budget/forecast/cashflow yet.**
6. **`HomeCurrencyResolution` exists but most production callers still use silent `"EUR"` fallback.**
7. **Budget status `UNKNOWN` is improved, but budget limit conversion still falls back to raw source amount and then performs mixed-currency remaining/rollover math.**
8. **`BudgetForecastingEngine` still has the exact raw budget amount fallback that the plan wanted removed.**
9. **No static money-boundary guard or meaningful new tests are included in this commit.**

So: **PR1–PR5 are partially implemented; PR7 and PR8 are only narrow spot-fixes; PR6/9/10/11/12 are still mostly open.**

---

# 1. Confirmed improvements

## 1.1 Exchange-rate latest lookup partly fixed

File:

- `ExchangeRateDao.kt`

Good change:

- `getLatestRateForPair(...)` now orders by `validDate DESC, lastUpdated DESC`.
- legacy `getRate(...)` now also uses `validDate DESC, lastUpdated DESC`.
- migration `130_131` backfills `validDate` for legacy rows.

This addresses the old obvious bug where a historical backfill inserted today could poison latest-rate lookup purely because `lastUpdated` was newer.

## 1.2 Typed conversion model added

Files:

- `ConversionOutcome.kt`
- `ConversionFailureType.kt`
- `ConversionPath.kt`
- `RateBasis.kt`
- `StaleRatePolicy.kt`
- `CurrencyConverter.kt`

Good:

- `convertOutcome(...)` now returns success/failure explicitly.
- failure types distinguish missing, stale, invalid currency, etc.
- converted outcome carries rate basis and conversion path.

This is the right direction.

## 1.3 MoneyAggregate got rate basis field

File:

- `MoneyAggregate.kt`

Good:

- `rateBasis` exists.

But see issue `CURR-C62-04`: it is not populated consistently.

## 1.4 MoneyNormalizationEngine added

File:

- `MoneyNormalizationEngine.kt`

Good:

- provides `normalizeExpense(...)`
- provides `aggregateExpenses(...)`
- provides `aggregateBuckets(...)`
- excludes failed conversions rather than adding raw foreign amounts

This is useful foundation work.

## 1.5 HomeCurrencyResolution added

Files:

- `HomeCurrencyResolution.kt`
- `CurrencySettingsRepository.kt`
- `CurrencySettingsRepositoryImpl.kt`

Good:

- first-run default vs read failure can now be represented.
- failed resolution can be surfaced instead of silently using EUR.

But most consumers do not use it yet.

## 1.6 Dashboard spending trend raw fallback removed

File:

- `ComputeDashboardWidgetsUseCase.kt`

Good:

- the spending trend no longer does `converted ?: effectiveAmount`.
- failed conversion is excluded.

This fixes one real mixed-currency chart bug.

## 1.7 Budget health UNKNOWN added

Files:

- `BudgetModels.kt`
- `BudgetRepository.kt`
- UI/narrative files

Good:

- conversion failure no longer returns green `ON_TRACK`.
- `UNKNOWN` is much safer than pretending the user is within budget.

---

# 2. High-priority issues found

## CURR-C62-01 — Fresh manual/API rates can still be ignored because new rows use `validDate = 0`

Severity: **High**  
Type: **actual conversion bug**

### Problem

`ExchangeRateDao.getLatestRateForPair(...)` now orders by `validDate DESC`.

But `CurrencyConverter.storeRate(...)` and `storeRates(...)` still create `DomainExchangeRate` without setting `validDate`.

`ExchangeRateStoreAdapter.toEntity()` maps null `validDate` to `0`.

That means new manually/API-stored rates can have:

```text
validDate = 0
lastUpdated = now
```

If the DB also contains historical rows with real `validDate`, the “latest” query will prefer the historical dated row over the fresh manual/API row.

### Example failure

1. Historical USD/EUR row exists:
   - validDate = 2026-05-01
   - lastUpdated = 2026-05-19
2. User manually updates USD/EUR today:
   - validDate = 0
   - lastUpdated = 2026-05-19 later
3. `getLatestRateForPair("USD", "EUR")` orders by `validDate DESC`.
4. The 2026-05-01 row wins.
5. The user’s fresh manual rate is ignored.

### User impact

Latest-rate dashboard/budget/valuation can show stale values even after the user updates rates.

### Fix strategy

When inserting current/manual/latest rates, always set `validDate`.

Minimum:

```kotlin
validDate = startOfDay(timeProvider.now())
```

Better:

- API rates should use the feed’s declared rate date.
- manual rates should use either:
  - start of current day, or
  - explicit manual locked date if supported.

### Acceptance tests

- `manual_rate_insert_sets_validDate`
- `latest_rate_prefers_fresh_manual_rate_over_older_historical_rate`
- `api_rate_insert_sets_feed_validDate`
- `latest_rate_ignores_validDate_zero_when_dated_rate_exists_or_migrates_it`

---

## CURR-C62-02 — Migration backfills `validDate = lastUpdated`, not start-of-day

Severity: **Medium/High**  
Type: **historical lookup correctness bug**

### Problem

Migration `130_131` does:

```text
validDate = lastUpdated
```

But `validDate` should represent the day/date the rate is valid for, not the exact update timestamp.

If a rate was updated at 13:00 and an expense happened at 09:00 on the same day, `getRateAsOf(expense.date)` will not select that same-day rate because:

```text
validDate 13:00 > expense date 09:00
```

It may fall back to the previous day or fail.

### Fix strategy

Backfill to start-of-day:

```text
validDate = startOfDay(lastUpdated)
```

SQLite implementation can use day-bucketing.

Also ensure future inserts do the same.

### Acceptance tests

- `same_day_rate_applies_to_expense_before_rate_update_time`
- `migration_backfills_validDate_to_start_of_day`
- `rate_as_of_uses_same_day_rate_for_morning_expense`

---

## CURR-C62-03 — `convertOutcome()` silently uses latest rates for historical bases when `atMillis` is missing

Severity: **High**  
Type: **actual rate-basis bug**

### Problem

`convertOutcome(...)` determines whether historical lookup is needed, but if `rateBasis` is historical and `atMillis == null`, it falls into the latest-rate path.

This violates the invariant:

```text
Every aggregate must declare and actually use its rate basis.
```

For these rate bases, missing `atMillis` should fail:

- `TRANSACTION_DATE`
- `PERIOD_START`
- `PERIOD_END`
- `FORECAST_DATE`
- maybe `PERIOD_MIDPOINT_ESTIMATE`

### Example failure

A caller requests:

```text
rateBasis = TRANSACTION_DATE
atMillis = null
```

Expected:

```text
Failed(INVALID_RATE_CONTEXT or UNKNOWN)
```

Actual:

```text
latest-rate conversion
```

This is a silent basis downgrade.

### Fix strategy

Add precondition logic:

```kotlin
when (rateBasis) {
    RateBasis.TRANSACTION_DATE,
    RateBasis.PERIOD_START,
    RateBasis.PERIOD_END,
    RateBasis.FORECAST_DATE,
    RateBasis.PERIOD_MIDPOINT_ESTIMATE -> {
        if (atMillis == null) return Failed(... missing date ...)
    }
    else -> ...
}
```

Do not silently switch basis.

### Acceptance tests

- `convertOutcome_transaction_date_without_date_fails`
- `convertOutcome_period_end_without_date_fails`
- `convertOutcome_forecast_date_without_date_fails`
- `convertOutcome_never_silently_uses_latest_for_historical_basis`

---

## CURR-C62-04 — `convertOutcome()` stale-rate logic compares against `lastUpdated` even when policy should use `validDate`

Severity: **High**  
Type: **actual stale-rate bug**

### Problem

`StaleRatePolicy` has references:

- `NOW`
- `TRANSACTION_DATE`
- `RATE_VALID_DATE`

But the age calculation always subtracts `rateResult.lastUpdated`.

That is wrong for historical conversion.

For transaction-date staleness, the comparison should usually be:

```text
transactionDate - rateValidDate
```

not:

```text
transactionDate - lastUpdated
```

If historical rates are backfilled today, `lastUpdated` may be after the transaction date, producing negative age or hiding stale historical coverage.

### User impact

The app can report “complete” or “fresh” conversions even when the rate used is far from the transaction date.

### Fix strategy

Use the correct timestamp based on policy:

```kotlin
val rateReference = when (stalePolicy.compareAgainst) {
    NOW -> rateResult.lastUpdated or rateResult.validDate depending policy
    TRANSACTION_DATE -> rateResult.validDate
    RATE_VALID_DATE -> rateResult.validDate
}
```

For historical conversion, compare:

```text
abs(atMillis - rateValidDate)
```

or at least:

```text
atMillis - rateValidDate
```

### Acceptance tests

- `stale_historical_rate_uses_validDate_not_lastUpdated`
- `backfilled_old_rate_inserted_today_can_still_be_stale`
- `transaction_date_staleness_uses_transaction_minus_validDate`

---

## CURR-C62-05 — Composite conversion loses per-leg valid-date provenance

Severity: **Medium**  
Type: **accuracy/provenance gap**

### Problem

For EUR-bridge conversions, the code combines two rates but stores only one `validDate` and one `source`.

If one leg is fresh and another is old, the final conversion quality is determined by the weaker leg, but this is not represented.

### Fix strategy

Add composite metadata:

```kotlin
val directOrCompositeLegs: List<RateLeg>
```

or at least:

```text
oldestRateValidDate
latestRateValidDate
oldestRateLastUpdated
latestRateLastUpdated
```

For now:

- use the oldest valid date for staleness.
- include conversion path `VIA_BASE_CURRENCY`.
- mark quality partial/stale if either leg is stale.

### Acceptance tests

- `composite_rate_uses_oldest_validDate_for_staleness`
- `composite_rate_metadata_contains_both_legs`

---

## CURR-C62-06 — `MoneyAggregate.rateBasis` is often wrong/defaulted

Severity: **High**  
Type: **actual reporting metadata bug**

### Problem

`MoneyAggregate` now has:

```kotlin
rateBasis = LATEST_AVAILABLE
```

as a default.

But many constructors and callers do not override it.

Examples:

- `MoneyNormalizationEngine.aggregateExpenses(...)` accepts `rateBasis`, but returns `MoneyAggregate(...)` without setting it.
- `MoneyNormalizationEngine.aggregateBuckets(...)` accepts `rateBasis`, but returns `MoneyAggregate(...)` without setting it.
- `MoneyAggregate.empty(...)` defaults to `LATEST_AVAILABLE`.
- `MoneyAggregate.singleCurrency(...)` defaults to `LATEST_AVAILABLE`, even when identity or transaction-date context was requested.
- `MoneyAggregate.partial(...)` defaults to `LATEST_AVAILABLE`.

### Impact

A historical aggregate can be labeled latest-rate.

This breaks the core requirement:

```text
Every aggregate must declare rate basis.
```

### Fix strategy

Make rate basis required or always explicit in factory methods.

Recommended:

```kotlin
fun empty(currency: CurrencyCode, rateBasis: RateBasis)
fun singleCurrency(..., rateBasis: RateBasis)
fun partial(..., rateBasis: RateBasis)
```

For identity conversions, separate:

```text
requestedRateBasis
actualRateBasis
```

So same-currency transaction-date aggregate can say:

```text
requested = TRANSACTION_DATE
actual = IDENTITY
```

### Acceptance tests

- `aggregateExpenses_transaction_date_sets_rateBasis_transaction_date`
- `aggregateBuckets_period_end_sets_rateBasis_period_end`
- `empty_aggregate_preserves_requested_rate_basis`
- `single_currency_identity_does_not_default_to_latest`

---

## CURR-C62-07 — `MoneyAggregate` v2 fields are missing

Severity: **Medium/High**  
Type: **architecture gap**

The plan required fields such as:

- requested rate basis
- actual rate basis
- conversion quality
- metadata
- counts for included/excluded/stale/missing/invalid
- latest/oldest rate dates

Current `MoneyAggregate` only adds `rateBasis`.

This is not enough to propagate partial/estimated/mixed-basis state across dashboard/budget/forecast/export.

### Fix strategy

Add:

```kotlin
requestedRateBasis
actualRateBasis
conversionQuality
metadata
```

At minimum:

```kotlin
data class MoneyAggregateMetadata(
    includedTransactionCount: Int,
    excludedTransactionCount: Int,
    staleRateCount: Int,
    missingRateCount: Int,
    invalidCurrencyCount: Int,
    latestRateValidDate: Long?,
    oldestRateValidDate: Long?
)
```

### Acceptance tests

- `aggregate_metadata_counts_missing_rates`
- `aggregate_metadata_counts_included_and_excluded_transactions`
- `latest_fallback_marks_mixed_basis_or_estimated`

---

## CURR-C62-08 — `MoneyNormalizationEngine` is not provenance-complete

Severity: **High**  
Type: **architecture gap with downstream correctness impact**

### Problem

The plan wanted a `NormalizedExpense` carrying:

- original amount
- original effective amount
- original currency
- normalized amount
- display currency
- rate basis
- rate used
- rate valid date
- rate last updated
- conversion path

Current `MoneyNormalizationEngine` imports and returns the existing analytics `NormalizedExpense`, which does not include rate provenance.

### Impact

Downstream systems cannot know:

- which rate was used
- whether conversion was direct or via EUR
- whether the row was transaction-date, latest-rate, or identity
- whether a latest fallback was used

### Fix strategy

Create a dedicated core/domain `NormalizedExpense` for money normalization or extend the analytics one.

Do not reuse an analytics-only model that lacks conversion provenance.

### Acceptance tests

- `normalizeExpense_includes_rateUsed`
- `normalizeExpense_includes_rateValidDate`
- `normalizeExpense_includes_conversionPath`
- `normalizeExpense_identity_records_actual_basis_identity`

---

## CURR-C62-09 — `BucketDatePolicy.RequireBucketDate` does not actually require a date

Severity: **High**  
Type: **actual conversion bug**

### Problem

In `aggregateBuckets(...)`, when policy is `RequireBucketDate`, the code reads `bucket.bucketDate`.

If it is null, it passes null through to `convertOutcome(...)`.

Because `convertOutcome()` currently falls back to latest rates when `atMillis` is null, a supposedly historical bucket can become latest-rate silently.

### Fix strategy

Fail the bucket immediately when date is required but missing:

```kotlin
if (bucketDatePolicy is RequireBucketDate && bucket.bucketDate == null) {
    failure(MISSING_RATE_CONTEXT)
}
```

And also fix `convertOutcome()` as described earlier.

### Acceptance tests

- `aggregateBuckets_requireBucketDate_missing_date_fails`
- `aggregateBuckets_requireBucketDate_never_uses_latest`
- `builder_transaction_date_without_bucket_date_fails`

---

## CURR-C62-10 — `HomeCurrencyResolution` is added but mostly unused

Severity: **High**  
Type: **actual user-facing currency bug**

### Problem

Several production paths still use:

- `homeCurrency().first()`
- `getOrDefault("EUR")`
- local `resolveHomeCurrency()` that catches and returns `"EUR"`

Examples:

- `MultiCurrencyRepository.resolveHomeCurrency()` still catches failure and returns `DEFAULT_HOME_CURRENCY`.
- `BudgetRepository.resolveHomeCurrency()` still catches failure and returns `"EUR"`.
- `ComputeDashboardWidgetsUseCase.computeSpendingTrend(...)` still uses fallback `"EUR"`.

### Impact

If currency settings DataStore fails/corrupts, the app can silently display EUR totals for a non-EUR user.

That was one of the original core issues.

### Fix strategy

All financial math should call:

```kotlin
currencySettingsRepository.resolveHomeCurrency()
```

Then:

- `Resolved` → proceed
- `FirstRunDefault` → proceed but mark defaulted if useful
- `Failed` → return partial/unavailable aggregate, not EUR

### Acceptance tests

- `multi_currency_repository_home_currency_failure_returns_unavailable`
- `budget_home_currency_failure_status_unknown`
- `dashboard_home_currency_failure_marks_widgets_unavailable`
- `spending_trend_home_currency_failure_no_silent_EUR`

---

## CURR-C62-11 — MultiCurrencyRepository remains legacy/latest-rate centric

Severity: **High**  
Type: **architecture gap and continuing correctness bug**

### Problems

`MultiCurrencyRepository` still has:

- legacy `Result<Double>` methods
- `Map<..., Double>` APIs
- methods named `getHomeCurrencyPurchaseTotal(...)` that use latest-rate conversion
- default home currency fallback
- a “historical” purchase total that uses period midpoint bucket conversion, not per-transaction conversion

The plan required explicit split:

```text
Historical -> transaction-date basis
Latest -> latest-rate basis
```

### Specific risk

`getHomeCurrencyPurchaseTotalHistorical(...)` converts per-currency buckets at a midpoint date, and falls back to latest rate if historical conversion fails.

That may be acceptable only if explicitly labeled:

```text
PERIOD_MIDPOINT_ESTIMATE
actualRateBasis = mixed / estimated
```

But the returned aggregate does not preserve that quality clearly.

### Fix strategy

Implement explicit methods:

```kotlin
getPurchaseAggregateHistorical(...)
getPurchaseAggregateLatestRate(...)
getCategoryAggregatesHistorical(...)
getCategoryAggregatesLatestRate(...)
```

Historical should use `MoneyNormalizationEngine.aggregateExpenses(...)` per expense, not pre-bucket midpoint conversion.

Legacy APIs should be deprecated and eventually internal.

### Acceptance tests

- `purchase_historical_uses_each_expense_date`
- `purchase_latest_uses_latest_rate`
- `historical_api_does_not_fallback_to_latest_without_mixed_basis_warning`
- `legacy_Result_Double_apis_are_deprecated_or_internal`

---

## CURR-C62-12 — Dashboard spending trend fix is partial

Severity: **Medium/High**  
Type: **actual user-facing reporting bug**

### What is fixed

The spending trend no longer adds raw foreign `effectiveAmount` on conversion failure.

### What remains broken

1. It still uses latest-rate `currencyConverter.convert(...)`, not transaction-date conversion.
2. It still silently defaults home currency to EUR on failure.
3. It excludes failed rows but does not propagate a trend-level partial warning.
4. It does not use `MoneyNormalizationEngine`.
5. The broader dashboard context still consumes `processedData.summary`, `categoryBreakdown`, forecast values, discretionary budget, and weather values that are not proven to share one canonical rate basis.

### Fix strategy

Create a canonical `DashboardNormalizedInput` and feed all widgets from it.

At minimum:

- trend should use transaction-date rate basis
- trend should return partial quality metadata
- no silent EUR fallback
- category totals and total summary must share the same basis

### Acceptance tests

- `spending_trend_uses_transaction_date_rates`
- `spending_trend_missing_rate_marks_partial`
- `dashboard_summary_and_category_use_same_rate_basis`
- `dashboard_home_currency_failure_no_silent_EUR`

---

## CURR-C62-13 — Forecast/runway dashboard path still receives raw or legacy-normalized data

Severity: **High**  
Type: **forecast correctness bug**

### Problem

`ComputeDashboardWidgetsUseCase.computeRunwayAndForecast(...)` builds `ExpenseSnapshot` directly from dashboard expenses using original values, then passes them to `forecastInputAssembler` / `synthesisEngine`.

The plan required:

```text
Forecast recurring patterns, planned expenses, and confirmed occurrences are normalized before synthesis.
```

This commit does not appear to enforce that.

### Impact

Forecast totals such as:

- committed expenses
- likely expenses
- runway days
- discretionary remaining
- block party daily spending

can still mix currencies depending on what upstream models contain.

### Fix strategy

Introduce `NormalizedForecastInput` and make `SynthesisEngine` accept only normalized input.

No raw `ExpenseSnapshot` list should enter synthesis for financial sums.

### Acceptance tests

- `forecast_input_uses_normalized_expenses`
- `forecast_recurring_pattern_usd_converted_before_synthesis`
- `runway_committed_and_likely_have_same_currency_basis`
- `block_party_daily_spending_uses_normalized_amounts`

---

## CURR-C62-14 — BudgetRepository UNKNOWN fix is good but still computes mixed-currency remaining/rollover

Severity: **High**  
Type: **actual budget math bug**

### What improved

When budget limit conversion fails:

```text
healthStatus = UNKNOWN
```

Good.

### Remaining problem

`convertBudgetAmountToHomeCurrencyAsOf(...)` still falls back to returning a single-currency aggregate in the source currency using the raw budget amount when conversion fails.

Then `createBudgetStatus(...)` continues to compute:

- `remaining = effectiveLimit - spent`
- rollover carryover
- effective limit

But `spent` is in home currency, while `effectiveLimit` may be in the budget source currency when conversion failed.

The health is UNKNOWN, but the numeric fields can still be mixed and misleading.

### Fix strategy

If budget limit conversion fails:

- do not compute remaining as a home-currency value
- set `remainingAmount` to 0 or nullable in a new model
- set `percentUsed = null` in a normalized status model
- skip rollover arithmetic or mark rollover unknown
- expose `BudgetReliability.LIMIT_CONVERSION_FAILED`

Short-term with existing model:

- set `remainingAmount = 0.0`
- set `effectiveLimit = 0.0`
- set `isPartial = true`
- set clear conversion warning

Better:

- introduce `NormalizedBudgetStatus` with nullable percent/remaining.

### Acceptance tests

- `budget_limit_conversion_failure_health_unknown`
- `budget_limit_conversion_failure_does_not_compute_mixed_remaining`
- `budget_rollover_skipped_or_unknown_when_limit_conversion_failed`

---

## CURR-C62-15 — BudgetForecastingEngine still falls back to raw budget amount

Severity: **High**  
Type: **actual forecast bug**

### Problem

`BudgetForecastingEngine.generateForecast(...)` still does:

```text
converted historical or latest amount, otherwise raw budget.amount
```

This was specifically listed in the scouting report as a bug.

### Impact

A foreign-currency budget can become a huge or tiny home-currency budget in forecast calculations.

Example:

```text
¥10,000 JPY budget becomes 10,000 EUR
```

### Fix strategy

Use `convertOutcome(...)`.

If conversion fails:

- forecast should be unavailable/partial
- risk should be UNKNOWN
- do not persist a misleading forecast, or persist with a conversion failure status

### Acceptance tests

- `budget_forecast_limit_conversion_failure_does_not_use_raw_amount`
- `budget_forecast_missing_rate_status_unknown_or_unavailable`
- `budget_forecast_records_conversion_failure`

---

## CURR-C62-16 — Cashflow still uses latest rates and lacks normalized line-item provenance

Severity: **Medium/High**  
Type: **forecast/cashflow correctness gap**

### Current state

`CashFlowCalculator` does avoid raw fallback: failed conversions are counted and dropped.

Good.

### Remaining gaps

- actual expenses are converted with latest rate, not expense-date rate
- predicted recurring items are converted with latest rate, not forecast-date basis
- no per-line `ConversionFailure`
- no normalized line item model carrying original/display amounts

### Fix strategy

Use:

- actual expense → `TRANSACTION_DATE`
- recurring forecast occurrence → `FORECAST_DATE`
- line items carry original amount/currency and display amount/currency or failure

### Acceptance tests

- `cashflow_actual_uses_expense_date_rate`
- `cashflow_recurring_uses_forecast_date_basis`
- `cashflow_missing_rate_marks_day_partial_not_raw_fallback`
- `cashflow_line_item_contains_conversion_failure`

---

## CURR-C62-17 — Static money boundary guard is missing

Severity: **High regression risk**

No `scripts/verify_money_boundaries.py` appears in this commit.

The plan required static checks for:

- raw `sumOf { it.amount }`
- raw `sumOf { it.effectiveAmount }`
- `?: effectiveAmount`
- silent EUR fallback
- `Result<Double>` aggregate APIs
- `Map<..., Double>` aggregate APIs
- legacy `get...InHomeCurrency(...)`
- direct `currencyConverter.convert(...)` in aggregate paths
- `ExchangeRateDao.getRate()` usage outside compatibility wrappers

### Fix strategy

Add the guard and CI step.

### Acceptance tests

- `money_guard_fails_on_sumOf_effectiveAmount_in_dashboard`
- `money_guard_fails_on_raw_conversion_fallback`
- `money_guard_fails_on_silent_EUR_default`
- `money_guard_fails_on_ambiguous_exchange_rate_getRate`
- `money_guard_allows_row_display_original_amount`

---

## CURR-C62-18 — Tests are missing for the new currency work

Severity: **High regression risk**

The commit changes production code and schemas, but I did not see new test files in the commit file list.

At minimum, tests are needed for:

- exchange-rate latest vs historical semantics
- migration behavior
- `convertOutcome(...)` missing date behavior
- stale-rate behavior
- `MoneyNormalizationEngine`
- `MoneyAggregate.rateBasis`
- dashboard trend partial handling
- budget UNKNOWN + no mixed remaining math
- home currency resolution failure

### Fix strategy

Add dedicated tests before migrating more pipelines.

---

# 3. Status matrix against the implementation plan

| Plan item | Status after `c62de2b` | Notes |
|---|---:|---|
| PR1 ExchangeRateDao latest vs historical | Partial | Query fixed, but new inserts still `validDate=0`; migration should use start-of-day |
| PR2 ConversionOutcome API | Partial | Added, but historical basis can silently use latest; stale logic wrong |
| PR3 MoneyNormalizationEngine | Partial | Exists, but not canonical and lacks rate provenance |
| PR4 MoneyAggregateBuilder v2 | Partial | Added overload, but old builder path remains dominant |
| PR5 HomeCurrencyResolution | Partial | Type exists, production paths mostly still use old fallback |
| PR6 MultiCurrencyRepository API split | Not done | Legacy/latest APIs remain |
| PR7 Dashboard migration | Partial | One trend fallback fixed; dashboard not normalized end-to-end |
| PR8 Budget/forecast/cashflow | Partial | Budget health UNKNOWN added; forecast raw fallback remains |
| PR9 Transaction/source provenance | Not done | No conversion snapshot/source boundary changes seen |
| PR10 Bank/export/import | Not done | Not covered by this commit |
| PR11 Static guard | Not done | No money guard script |
| PR12 Currency quality UI | Not done | No `CurrencyQualityUi` equivalent |

---

# 4. Actual bugs vs architectural work

## Actual user-impacting bugs

1. Fresh manual/API rates can be ignored due `validDate=0`.
2. Historical conversion can silently use latest rate when date is missing.
3. Staleness detection uses `lastUpdated` instead of `validDate`.
4. Dashboard spending trend still uses latest rates for historical chart points.
5. Dashboard spending trend still silently defaults to EUR.
6. Budget remaining/rollover can still mix source-currency limit with home-currency spend.
7. Budget forecast still falls back to raw budget amount.
8. Home currency failure still silently becomes EUR in major repositories.
9. Same-day historical rate lookup can miss rates if `validDate` is exact update time.

## Architectural gaps

1. `MoneyAggregate` lacks conversion quality/metadata.
2. `MoneyNormalizationEngine` is not provenance-complete.
3. `MoneyNormalizationEngine` is not yet canonical for dashboard/budget/forecast/cashflow.
4. `MultiCurrencyRepository` still exposes ambiguous legacy APIs.
5. Static money guard is absent.
6. Tests are not yet proving the new invariants.

---

# 5. Highest-priority next fixes

## P0 — Fix exchange-rate semantics before further migration

1. Set `validDate` on all new rate inserts.
2. Backfill `validDate` to start-of-day.
3. Make `convertOutcome()` fail if historical basis lacks `atMillis`.
4. Fix stale-rate comparison to use `validDate` where appropriate.
5. Add tests around all of the above.

## P1 — Fix incorrect labels and hidden basis downgrades

1. Ensure `MoneyAggregate.rateBasis` is explicitly set everywhere.
2. Add requested vs actual basis.
3. Mark latest fallback from historical request as estimated/mixed basis, or disallow it.
4. Fix `BucketDatePolicy.RequireBucketDate`.

## P2 — Remove remaining user-facing raw/silent fallbacks

1. Replace home currency `"EUR"` fallbacks with `HomeCurrencyResolution`.
2. Fix `BudgetForecastingEngine` raw amount fallback.
3. Fix budget remaining/rollover when limit conversion fails.
4. Make dashboard trend partial state visible.

## P3 — Real pipeline migration

1. Split `MultiCurrencyRepository` APIs into historical/latest.
2. Introduce canonical dashboard normalized input.
3. Normalize forecast/cashflow inputs before synthesis.
4. Add static guard.

---

# 6. Recommended next PR sequence

## PR A — Exchange-rate correctness hotfix

Files:

- `CurrencyConverter.kt`
- `ExchangeRateDao.kt`
- `AppDatabase.kt`
- rate insert/fetch tests

Tasks:

- set `validDate` on `storeRate(...)` and `storeRates(...)`
- use start-of-day valid dates
- update migration
- update `getRateAsOf` ordering to include `lastUpdated DESC`
- add tests

Acceptance:

```text
latest lookup prefers fresh current/manual rate
historical lookup uses same-day rate
historical backfill cannot poison latest
new inserted rates never have validDate=0 unless explicitly undated
```

## PR B — ConversionOutcome semantics hardening

Files:

- `CurrencyConverter.kt`
- `ConversionFailureType.kt`
- tests

Tasks:

- fail historical basis without `atMillis`
- fix stale comparison
- preserve composite leg metadata or oldest valid date
- no latest fallback unless explicitly requested

Acceptance:

```text
TRANSACTION_DATE without date fails
PERIOD_END without date fails
staleness uses validDate for historical rates
composite rates report weakest/oldest leg
```

## PR C — MoneyAggregate/MoneyNormalizationEngine v2

Files:

- `MoneyAggregate.kt`
- `MoneyAggregateBuilder.kt`
- `MoneyNormalizationEngine.kt`
- `NormalizedExpense` model

Tasks:

- add requested/actual basis
- add conversion quality
- add metadata counts
- make factory methods require basis
- add provenance to normalized expenses
- fix `RequireBucketDate`

Acceptance:

```text
all aggregates carry correct basis
all failures counted
normalized rows contain rate provenance
bucket date required means required
```

## PR D — Home currency failure migration

Files:

- `MultiCurrencyRepository.kt`
- `BudgetRepository.kt`
- `ComputeDashboardWidgetsUseCase.kt`
- forecast/cashflow callers

Tasks:

- use `resolveHomeCurrency()`
- failure returns unavailable/partial status
- remove `getOrDefault("EUR")` from financial math

Acceptance:

```text
DataStore read failure never silently becomes EUR
dashboard/budget/forecast return warnings/unavailable
first-run default remains explicit
```

## PR E — Budget/forecast/cashflow correctness

Files:

- `BudgetRepository.kt`
- `BudgetForecastingEngine.kt`
- `CashFlowCalculator.kt`
- forecast models

Tasks:

- no raw budget amount fallback
- conversion failure gives UNKNOWN/unavailable forecast
- remaining/rollover not computed with mixed currencies
- cashflow actuals use transaction-date; forecasts use forecast-date

Acceptance:

```text
budget forecast missing rate does not use raw amount
budget remaining unknown when limit conversion fails
cashflow line items expose conversion failure
```

## PR F — MultiCurrencyRepository API split

Files:

- `MultiCurrencyRepository.kt`
- all callers

Tasks:

- add explicit historical/latest method names
- deprecate old `Result<Double>` APIs
- migrate dashboard/budget to new API
- use per-expense historical normalization for historical totals

Acceptance:

```text
callers must choose rate basis
legacy aggregate APIs are deprecated/internal
historical totals use transaction-date basis
```

## PR G — Static money guard and tests

Files:

- `scripts/verify_money_boundaries.py`
- CI workflow
- test fixtures

Tasks:

- block raw mixed-currency sums
- block silent EUR fallback
- block raw conversion fallback
- block ambiguous `getRate()`
- block new `Result<Double>` aggregate APIs

Acceptance:

```text
CI fails on known bad patterns
allowlist documents legitimate row display/source bucket cases
```

---

# 7. Updated definition of done for global issue #4

This issue is not done until:

1. All rate inserts set valid `validDate`.
2. Latest rate lookup cannot be poisoned by historical backfills or `validDate=0`.
3. Historical conversion cannot silently use latest rates.
4. Stale-rate checks use `validDate` for historical rates.
5. Every aggregate carries accurate requested/actual basis.
6. Conversion failures never add raw foreign amounts.
7. Home-currency failure never silently uses EUR.
8. Dashboard summary/category/trend use one canonical normalized basis.
9. Budget failure does not compute misleading remaining/rollover values.
10. Budget forecast never falls back to raw foreign budget amount.
11. Forecast/cashflow inputs are normalized before synthesis.
12. MultiCurrencyRepository legacy `Double` APIs are removed/deprecated/internal.
13. Static money guard is in CI.
14. Golden tests prove no mixed-currency arithmetic regressions.

---

# 8. Sources reviewed

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/c62de2be3f117f14f0a1dc9053ebc97ae21d883c

- `CurrencyConverter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt

- `ExchangeRateDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt

- `ExchangeRate.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/database/entity/ExchangeRate.kt

- `ExchangeRateStoreAdapter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/currency/ExchangeRateStoreAdapter.kt

- `MoneyNormalizationEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyNormalizationEngine.kt

- `MoneyAggregate.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyAggregateBuilder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

- `MultiCurrencyRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

- `BudgetRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt

- `BudgetForecastingEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt

- `CashFlowCalculator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt

- `ComputeDashboardWidgetsUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt

- `CurrencySettingsRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/app/src/main/java/com/yourname/expensetracker/data/repository/CurrencySettingsRepositoryImpl.kt

- Scouting report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c62de2be3f117f14f0a1dc9053ebc97ae21d883c/docs/analyses%20and%20debug%20master/new%20debugging%20session/scouting_report_currency_normalization.md