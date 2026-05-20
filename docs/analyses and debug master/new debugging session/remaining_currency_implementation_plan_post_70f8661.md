# Remaining Currency Normalization Implementation Plan

Baseline commit:

```text
70f866194ba5991e126a7b8491881d4d4e90ab68
feat: complete currency normalization PRs A-H (CURR-C62 fixes)
```

## Overall goal

Close Global Issue #4:

```text
No financial arithmetic may mix currencies implicitly.
Every aggregate must declare and actually honor its rate basis.
Every conversion failure must be explicit.
No failed conversion may silently fall back to raw foreign amount or hidden latest-rate conversion.
```

---

# 0. Current state summary

## Keep / do not rework unless tests prove broken

Already improved:

```text
RateBasis exists.
ConversionOutcome exists.
MoneyAggregate has requested/actual basis and quality metadata fields.
MoneyNormalizationEngine exists.
CurrencyConverter.storeRate/storeRates now set validDate.
MIGRATION_130_131 now backfills validDate to start-of-day for fresh migration path.
convertOutcome fails for most historical bases without atMillis.
Dashboard spending trend no longer falls back to raw effectiveAmount.
Budget hard conversion failure no longer returns raw source amount.
verify_money_boundaries.py exists.
Some behavioral tests exist.
```

## Still open

```text
CURR-70F-01 PERIOD_MIDPOINT_ESTIMATE still uses latest lookup.
CURR-70F-02 StaleRatePolicy.compareAgainst is ignored.
CURR-70F-03 Composite EUR conversion loses weakest-leg provenance.
CURR-70F-04 Store adapter still permits validDate = 0.
CURR-70F-05 MoneyNormalizationEngine drops rate provenance.
CURR-70F-06 Legacy MoneyAggregateBuilder.fromBuckets can lie about basis.
CURR-70F-07 Typed MoneyAggregateBuilder does not strictly enforce RequireBucketDate.
CURR-70F-08 MultiCurrencyRepository remains legacy/latest-rate centric.
CURR-70F-09 HomeCurrencyResolution is underused.
CURR-70F-10 BudgetRepository still hides/mixes basis in partial limit cases.
CURR-70F-11 BudgetForecastingEngine hides latest fallback as success.
CURR-70F-12 Cashflow recurring predictions still use latest rates.
CURR-70F-13 Dashboard migration is partial.
CURR-70F-14 Static money-boundary guard is weak.
CURR-70F-15 Behavioral/integration tests are incomplete.
```

---

# Recommended execution order

```text
PR 1  Conversion semantics hardening
PR 2  Normalization provenance completion
PR 3  Restrict legacy aggregate APIs
PR 4  MultiCurrencyRepository explicit historical/latest split
PR 5  Home currency failure propagation
PR 6  Budget/forecast/cashflow correctness
PR 7  Dashboard canonical normalized input
PR 8  Static money guard v2
PR 9  Live integration/behavioral tests
PR 10 Docs and cleanup
```

Fastest risk reduction:

```text
1. Fix PERIOD_MIDPOINT_ESTIMATE latest-rate bug.
2. Enforce validDate at store boundary.
3. Restrict legacy fromBuckets basis lies.
4. Fix budget forecast hidden latest fallback.
5. Add guard rules for convertAsOf ?: convert and homeCurrency().first().
```

---

# PR 1 — Conversion semantics hardening

## Fixes

```text
CURR-70F-01
CURR-70F-02
CURR-70F-03
CURR-70F-04
```

## Goal

`CurrencyConverter.convertOutcome(...)` must honor the requested `RateBasis`, preserve rate provenance, and prevent undated rates from entering production storage.

## Files

```text
CurrencyConverter.kt
ConversionOutcome.kt
ConversionFailureType.kt
StaleRatePolicy.kt
ExchangeRateStoreAdapter.kt
ExchangeRateDao.kt
DomainExchangeRate model if applicable
tests under domain/currency and data/currency
```

---

## 1.1 Fix `PERIOD_MIDPOINT_ESTIMATE`

### Problem

`PERIOD_MIDPOINT_ESTIMATE` requires `atMillis`, but then still uses latest lookup because it is missing from the historical lookup switch.

### Implementation

In `CurrencyConverter.convertOutcome(...)`, define one canonical set:

```kotlin
private val historicalRateBases = setOf(
    RateBasis.TRANSACTION_DATE,
    RateBasis.PERIOD_START,
    RateBasis.PERIOD_END,
    RateBasis.PERIOD_MIDPOINT_ESTIMATE,
    RateBasis.FORECAST_DATE
)
```

Use it for both:

```kotlin
requiresDateContext
```

and:

```kotlin
useHistoricalLookup
```

Behavior:

```text
PERIOD_MIDPOINT_ESTIMATE + atMillis -> getRateAsOf(atMillis)
PERIOD_MIDPOINT_ESTIMATE + null -> Failed(...)
Never latest lookup unless RateBasis.LATEST_AVAILABLE was requested.
```

### Tests

```text
convertOutcome_period_midpoint_uses_as_of_rate
convertOutcome_period_midpoint_does_not_use_latest_rate
convertOutcome_period_midpoint_without_date_fails
```

---

## 1.2 Honor `StaleRatePolicy.compareAgainst`

### Problem

`StaleRatePolicy.compareAgainst` exists but is ignored.

### Implementation

Add helper:

```kotlin
private fun computeRateAgeMs(
    stalePolicy: StaleRatePolicy,
    rate: RateResult,
    requestAtMillis: Long?,
    nowMillis: Long
): Long? {
    val comparisonReference = when (stalePolicy.compareAgainst) {
        StaleRateReference.NOW -> nowMillis
        StaleRateReference.TRANSACTION_DATE -> requestAtMillis ?: return null
        StaleRateReference.RATE_VALID_DATE -> rate.validDate ?: return null
    }

    val rateReference = when (stalePolicy.compareAgainst) {
        StaleRateReference.NOW -> rate.lastUpdated
        StaleRateReference.TRANSACTION_DATE -> rate.validDate ?: rate.lastUpdated
        StaleRateReference.RATE_VALID_DATE -> rate.validDate ?: return null
    }

    return kotlin.math.abs(comparisonReference - rateReference)
}
```

Then:

```kotlin
val ageMs = computeRateAgeMs(...)
val isStale = stalePolicy.maxAgeMs != null &&
    ageMs != null &&
    ageMs > stalePolicy.maxAgeMs
```

If rate age cannot be computed for a policy that requires it, either:
- fail as `STALE_RATE` / `UNKNOWN`, or
- mark conversion quality stale/unknown depending current model.

Do **not** silently treat unknown age as fresh.

### Tests

```text
stalePolicy_NOW_uses_now_reference
stalePolicy_TRANSACTION_DATE_uses_atMillis_reference
stalePolicy_RATE_VALID_DATE_uses_validDate_reference
stalePolicy_missing_reference_does_not_mark_fresh
```

---

## 1.3 Composite EUR-bridge provenance

### Problem

EUR-bridge conversion currently stores one valid date/source and can hide a stale leg.

### Implementation

Minimum viable fix:

```kotlin
private fun minNonNull(a: Long?, b: Long?): Long? =
    listOfNotNull(a, b).minOrNull()

private fun maxNonNull(a: Long?, b: Long?): Long? =
    listOfNotNull(a, b).maxOrNull()
```

For composite result:

```kotlin
validDate = minNonNull(toBase.validDate, fromBase.validDate)
lastUpdated = minOf(toBase.lastUpdated, fromBase.lastUpdated)
source = listOfNotNull(toBase.source, fromBase.source).joinToString("+")
path = ConversionPath.VIA_BASE_CURRENCY
```

Better long-term model:

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

Add to `ConversionOutcome.Converted`:

```kotlin
val rateLegs: List<RateLeg> = emptyList()
```

If adding `rateLegs` is too large for this PR, at least use oldest valid date and weakest last updated.

### Tests

```text
composite_rate_uses_oldest_validDate_for_staleness
composite_rate_uses_weakest_lastUpdated_for_freshness
composite_rate_records_via_base_path
composite_rate_source_mentions_both_legs
```

---

## 1.4 Enforce `validDate` at storage boundary

### Problem

`CurrencyConverter.storeRate(...)` sets `validDate`, but direct `ExchangeRateStoreAdapter` inserts can still write `validDate = 0`.

### Implementation options

Preferred:

```kotlin
require(rate.validDate != null && rate.validDate > 0L) {
    "Exchange rates must have a non-zero validDate"
}
```

inside `ExchangeRateStoreAdapter.toEntity(...)` or insert method.

If test/migration paths need exceptions, create explicit APIs:

```kotlin
insertLegacyUndatedForMigrationOnly(...)
```

or allow only from migrations, not runtime code.

Alternative if adapter has access to clock:

```kotlin
validDate = rate.validDate ?: startOfDay(timeProvider.now())
```

But this can hide bad callers. Prefer failing in production and fixing callers.

### Also add DAO/data cleanup

If any rows still exist with `validDate = 0`, add migration:

```sql
UPDATE exchange_rates
SET validDate = (lastUpdated / 86400000) * 86400000
WHERE validDate = 0 AND lastUpdated > 0;
```

If previous migration already shipped, add a new migration version instead of editing old migration.

### Tests

```text
exchangeRateStore_rejects_null_validDate
direct_store_insert_cannot_create_validDate_zero
migration_corrects_validDate_zero_rows
latest_lookup_ignores_or_never_sees_validDate_zero
```

---

## PR 1 acceptance criteria

```text
1. PERIOD_MIDPOINT_ESTIMATE uses getRateAsOf.
2. StaleRatePolicy.compareAgainst changes behavior in tests.
3. Composite conversion uses weakest/oldest leg provenance.
4. Runtime exchange-rate inserts cannot create validDate = 0.
```

---

# PR 2 — Normalization provenance completion

## Fixes

```text
CURR-70F-05
```

## Goal

Every normalized row and aggregate must explain exactly how currency conversion was performed.

## Files

```text
MoneyNormalizationEngine.kt
NormalizedExpense model
MoneyAggregate.kt
MoneyAggregateMetadata.kt
ConversionOutcome.kt
tests
```

---

## 2.1 Populate `rateValidDate` and `rateLastUpdated`

### Problem

`MoneyNormalizationEngine.toNormalizedExpense(...)` sets `rateValidDate = null`.

### Implementation

Change mapper from:

```kotlin
expense.toNormalizedExpense(homeCurrency, convertedAmount, rateUsed, rateBasis, path)
```

to:

```kotlin
expense.toNormalizedExpense(
    homeCurrency = homeCurrency,
    outcome = outcome,
    requestedRateBasis = rateBasis
)
```

Set:

```kotlin
rateUsed = outcome.rateUsed
rateBasis = outcome.rateBasis.name
rateValidDate = outcome.rateValidDate
rateLastUpdated = outcome.rateLastUpdated
conversionPath = outcome.conversionPath.name
```

If `NormalizedExpense` lacks `rateLastUpdated`, add it:

```kotlin
val rateLastUpdated: Long?
```

Also add, if feasible:

```kotlin
val requestedRateBasis: String
val actualRateBasis: String
val conversionSource: String?
```

---

## 2.2 Identity conversion semantics

For same-currency rows:

```text
requestedRateBasis = requested basis
actualRateBasis = IDENTITY
conversionPath = IDENTITY
rateUsed = 1.0
rateValidDate = null
rateLastUpdated = null
```

This avoids historical aggregates being mislabeled as latest.

---

## 2.3 Aggregate metadata from row outcomes

For each aggregate, fill:

```kotlin
MoneyAggregateMetadata(
    includedTransactionCount = included.size,
    excludedTransactionCount = failures.size,
    staleRateCount = failures.count { it.failureType == STALE_RATE },
    missingRateCount = failures.count { it.failureType == MISSING_RATE || MISSING_HISTORICAL_RATE },
    invalidCurrencyCount = failures.count { invalid currency },
    latestRateValidDate = included.mapNotNull { it.rateValidDate }.maxOrNull(),
    oldestRateValidDate = included.mapNotNull { it.rateValidDate }.minOrNull()
)
```

Quality:

```kotlin
COMPLETE if no failures and no stale conversions
PARTIAL if any failed rows
UNAVAILABLE if all rows failed
ESTIMATED if PERIOD_MIDPOINT_ESTIMATE or explicit latest fallback policy
MIXED_BASIS if included rows use incompatible actual bases
```

---

## Tests

```text
normalizeExpense_includes_rateValidDate
normalizeExpense_includes_rateLastUpdated
normalizeExpense_includes_conversionPath
normalizeExpense_identity_records_actual_basis_identity
aggregate_metadata_counts_included_excluded_missing_stale_invalid
aggregate_quality_unavailable_when_all_rows_fail
aggregate_quality_mixed_basis_when_actual_bases_differ
```

## PR 2 acceptance criteria

```text
1. Normalized rows carry rate provenance.
2. Aggregates carry accurate basis/quality/count metadata.
3. Identity rows do not default to latest-rate semantics.
```

---

# PR 3 — Restrict legacy aggregate APIs

## Fixes

```text
CURR-70F-06
CURR-70F-07
```

## Goal

Old helper APIs must not label latest-rate math as historical math.

## Files

```text
MoneyAggregateBuilder.kt
MoneyNormalizationEngine.kt
MultiCurrencyRepository.kt callers
tests
```

---

## 3.1 Restrict old `fromBuckets(Pair<Double, String>)`

### Problem

Legacy overload uses latest-rate `convertMultiple(...)`, but accepts arbitrary `rateBasis`.

### Implementation

Option A — strict:

```kotlin
require(rateBasis == RateBasis.LATEST_AVAILABLE) {
    "Legacy fromBuckets uses latest-rate conversion only. Use typed overload for $rateBasis."
}
```

Option B — remove basis parameter:

```kotlin
rateBasis = RateBasis.LATEST_AVAILABLE
```

and deprecate:

```kotlin
@Deprecated(
    "Use typed fromBuckets with BucketDatePolicy or MoneyNormalizationEngine",
    level = DeprecationLevel.WARNING
)
```

After migration, raise to `ERROR`.

---

## 3.2 Enforce `RequireBucketDate` inside typed builder

### Problem

Typed builder relies on `convertOutcome(...)` failure instead of enforcing the policy before conversion.

### Implementation

```kotlin
val atMillis = when (bucketDatePolicy) {
    BucketDatePolicy.RequireBucketDate -> {
        if (bucket.bucketDate == null) {
            failures += ConversionFailure(...)
            continue
        }
        bucket.bucketDate
    }
    is BucketDatePolicy.FixedDate -> bucketDatePolicy.atMillis
    BucketDatePolicy.Latest -> null
}
```

Do not call converter if the bucket date is missing.

---

## 3.3 Deprecate direct constructor misuse

If many callers construct:

```kotlin
MoneyAggregate(...)
```

without explicit basis/quality, add factory methods and consider making constructor internal later.

Short-term guard in static script will catch missing basis.

---

## Tests

```text
legacy_fromBuckets_rejects_non_latest_rateBasis
legacy_fromBuckets_labels_latest_available_only
typed_builder_requireBucketDate_missing_date_fails_before_converter
typed_builder_requireBucketDate_does_not_call_converter_when_missing_date
```

## PR 3 acceptance criteria

```text
1. Legacy bucket builder cannot claim TRANSACTION_DATE/PERIOD_END while using latest rates.
2. Missing bucket dates are explicit failures.
3. Callers are pushed toward typed builder or MoneyNormalizationEngine.
```

---

# PR 4 — MultiCurrencyRepository explicit historical/latest split

## Fixes

```text
CURR-70F-08
```

## Goal

All aggregate repository methods must clearly distinguish historical transaction-date reports from latest-rate current valuations.

## Files

```text
MultiCurrencyRepository.kt
MoneyNormalizationEngine.kt
Dashboard callers
Budget callers
Analytics callers
tests
```

---

## 4.1 Add explicit API families

Create:

```kotlin
suspend fun getPurchaseAggregateHistorical(
    startDate: Long,
    endDate: Long,
    homeCurrency: CurrencyCode,
    transactionTypeFilter: TransactionTypeFilter = PURCHASE_ONLY
): MoneyAggregate
```

```kotlin
suspend fun getPurchaseAggregateLatestRate(...): MoneyAggregate
```

Category:

```kotlin
suspend fun getCategoryAggregatesHistorical(...): Map<CategoryKey, MoneyAggregate>
suspend fun getCategoryAggregatesLatestRate(...): Map<CategoryKey, MoneyAggregate>
```

Merchant:

```kotlin
suspend fun getMerchantAggregatesHistorical(...): Map<MerchantKey, MoneyAggregate>
suspend fun getMerchantAggregatesLatestRate(...): Map<MerchantKey, MoneyAggregate>
```

Period:

```kotlin
suspend fun getDailyAggregatesHistorical(...): List<PeriodMoneyAggregate>
suspend fun getWeeklyAggregatesHistorical(...): List<PeriodMoneyAggregate>
suspend fun getMonthlyAggregatesHistorical(...): List<PeriodMoneyAggregate>
```

---

## 4.2 Historical implementation

Historical must:

```text
load actual expenses
filter transaction types explicitly
call MoneyNormalizationEngine.aggregateExpenses(..., RateBasis.TRANSACTION_DATE)
never group by currency first and midpoint convert unless method name says estimate
never fallback to latest silently
```

---

## 4.3 Latest implementation

Latest-rate methods may use buckets:

```text
RateBasis.LATEST_AVAILABLE
BucketDatePolicy.Latest
clear method name includes LatestRate
```

---

## 4.4 Deprecate legacy APIs

Mark ambiguous APIs:

```kotlin
@Deprecated(
    "Use getPurchaseAggregateHistorical or getPurchaseAggregateLatestRate",
    level = DeprecationLevel.WARNING
)
```

Once call sites migrate:

```kotlin
level = DeprecationLevel.ERROR
```

Target old patterns:

```text
Result<Double>
Map<..., Double>
getHomeCurrencyPurchaseTotal
getHomeCurrencyWeeklyTotals
getHomeCurrencyDailyTotals
getTotalExpensesInHomeCurrency
```

---

## Tests

```text
historical_api_uses_each_expense_transaction_date
historical_api_does_not_fallback_to_latest
latest_api_uses_latest_rate
category_historical_sums_to_parent_historical_total
legacy_double_apis_are_deprecated_or_internal
```

## PR 4 acceptance criteria

```text
1. Historical and latest repository APIs are separate by name and behavior.
2. Historical totals use per-expense transaction-date conversion.
3. No production dashboard/budget caller uses ambiguous legacy APIs.
```

---

# PR 5 — Home currency failure propagation

## Fixes

```text
CURR-70F-09
```

## Goal

Home-currency settings failure must become typed unavailable/partial output, not silent EUR and not uncontrolled exceptions.

## Files

```text
CurrencySettingsRepository.kt
CurrencySettingsRepositoryImpl.kt
MultiCurrencyRepository.kt
BudgetRepository.kt
BudgetForecastingEngine.kt
CashFlowCalculator.kt
ComputeDashboardWidgetsUseCase.kt
ExportCoordinator.kt if applicable
UI models
tests
```

---

## 5.1 Define common helper

```kotlin
suspend fun CurrencySettingsRepository.requireHomeCurrencyForMoneyMath():
    HomeCurrencyResolution
```

But do not throw by default.

Caller policy:

```kotlin
when (resolution) {
    is Resolved -> proceed
    is FirstRunDefault -> proceed, mark defaulted if model supports it
    is Failed -> return unavailable/partial result
}
```

---

## 5.2 Migrate major callers

Search:

```bash
rg "homeCurrency\\(\\)\\.first" app/src/main/java
rg "getOrDefault\\(\"EUR\"\\)" app/src/main/java
rg "DEFAULT_HOME_CURRENCY" app/src/main/java
```

Replace in financial math paths.

Expected changes:

### Dashboard

Return widget state:

```text
Unavailable / Partial
warning = "Home currency unavailable"
```

### Budget

```text
health = UNKNOWN
reliability = HOME_CURRENCY_UNAVAILABLE
percentUsed = null if model supports it
```

### Forecast

```text
forecast status = unavailable/partial
risk = UNKNOWN if enum exists
```

### Cashflow

```text
CashFlowResult.isPartial = true
failure reason = HOME_CURRENCY_UNAVAILABLE
```

---

## Tests

```text
dashboard_home_currency_failure_returns_unavailable_widget_not_exception
budget_home_currency_failure_status_unknown
cashflow_home_currency_failure_returns_partial_result
forecast_home_currency_failure_records_unavailable_forecast
firstRunDefault_EUR_is_explicitly_marked
```

## PR 5 acceptance criteria

```text
1. No financial math path silently defaults to EUR.
2. Settings failure is typed and visible.
3. First-run default remains explicit.
```

---

# PR 6 — Budget / forecast / cashflow correctness

## Fixes

```text
CURR-70F-10
CURR-70F-11
CURR-70F-12
```

## Goal

Budget, forecast, and cashflow must not hide mixed basis, latest fallback, or failed conversion.

## Files

```text
BudgetRepository.kt
BudgetForecastingEngine.kt
CashFlowCalculator.kt
BudgetModels.kt
CashFlow models
Forecast models
tests
```

---

## 6.1 BudgetRepository partial limit conversion

### Problem

Latest fallback or partial limit conversion can still feed numeric remaining/rollover.

### Implementation

Introduce reliability if feasible:

```kotlin
enum class BudgetReliability {
    RELIABLE,
    PARTIAL_SPEND,
    LIMIT_CONVERSION_FAILED,
    LIMIT_CONVERSION_ESTIMATED,
    HOME_CURRENCY_UNAVAILABLE,
    UNKNOWN
}
```

If model change is too broad, add internal flags and UI warnings.

Rules:

```text
If limit conversion failed:
  health = UNKNOWN
  do not compute remaining/rollover from mixed basis
  numeric fields either null in new model or set to safe 0 with warning

If limit conversion used latest fallback:
  health = UNKNOWN or ESTIMATED
  do not present as reliable
  warning must mention estimated/latest-rate basis
```

---

## 6.2 BudgetForecastingEngine

Replace:

```kotlin
convertAsOf(...) ?: convert(...)
```

with:

```kotlin
convertOutcome(
    amount = budget.amount,
    fromCurrency = budget.currency,
    toCurrency = homeCurrency,
    rateBasis = RateBasis.PERIOD_END,
    atMillis = forecastPeriodEnd
)
```

If failed:

```text
forecast status unavailable/partial
risk UNKNOWN
confidence 0
warning includes conversion failure
```

If latest fallback is intentionally allowed, it must be explicit:

```kotlin
rateBasis = RateBasis.LATEST_AVAILABLE
conversionQuality = ESTIMATED
```

Do not mark as reliable.

---

## 6.3 CashFlowCalculator recurring predictions

Actuals:

```text
RateBasis.TRANSACTION_DATE
atMillis = expense.date
```

Recurring forecast occurrences:

```text
RateBasis.FORECAST_DATE
atMillis = occurrenceDate
```

Add line item if feasible:

```kotlin
data class CashFlowLineItem(
    val sourceId: Long?,
    val originalAmount: Double,
    val originalCurrency: CurrencyCode,
    val displayAmount: Double?,
    val displayCurrency: CurrencyCode,
    val rateBasis: RateBasis,
    val conversionFailure: ConversionFailure?
)
```

At minimum, day result should include:

```text
failedConversionCount
conversionFailures
isPartial
```

---

## Tests

```text
budget_latest_fallback_marks_unknown_and_skips_rollover
budget_limit_partial_does_not_compute_effectiveLimit_from_mixed_basis
budget_forecast_does_not_hide_latest_fallback
budget_forecast_missing_historical_rate_is_partial_or_unavailable
budget_forecast_conversion_failure_is_not_low_risk
cashflow_recurring_uses_forecast_date_basis
cashflow_line_item_contains_conversion_failure
cashflow_day_partial_includes_failure_details
```

## PR 6 acceptance criteria

```text
1. Budget limit failures do not produce misleading remaining/rollover.
2. Budget forecast does not hide latest fallback as exact.
3. Cashflow forecasted recurring items use forecast-date basis.
```

---

# PR 7 — Dashboard canonical normalized input

## Fixes

```text
CURR-70F-13
```

## Goal

Dashboard widgets should consume one canonical normalized money model with quality metadata.

## Files

```text
ComputeDashboardWidgetsUseCase.kt
DashboardDataProvider.kt
AnalyticsInputAssembler.kt
DashboardContractsAdapter.kt
Dashboard UI contracts/models
MoneyNormalizationEngine.kt
tests
```

---

## 7.1 Add dashboard normalized input

```kotlin
data class DashboardNormalizedInput(
    val homeCurrency: CurrencyCode,
    val period: DateRange,
    val normalizedExpenses: List<NormalizedExpense>,
    val periodAggregate: MoneyAggregate,
    val categoryAggregates: Map<Long, MoneyAggregate>,
    val merchantAggregates: Map<String, MoneyAggregate>,
    val dataQuality: CurrencyDataQuality
)
```

```kotlin
data class CurrencyDataQuality(
    val isPartial: Boolean,
    val conversionQuality: ConversionQuality,
    val missingRateCount: Int,
    val staleRateCount: Int,
    val invalidCurrencyCount: Int,
    val excludedTransactionCount: Int,
    val warningMessage: String?,
    val requestedRateBasis: RateBasis,
    val actualRateBasis: RateBasis
)
```

---

## 7.2 Feed widgets from normalized input

Migrate:

```text
Spending summary
Category breakdown
Spending trend
Period summary
Monte Carlo/monthly projections
Runway/forecast cards
Narrative generation
```

Rules:

```text
Historical widgets -> TRANSACTION_DATE
Current valuation widgets -> LATEST_AVAILABLE and labeled as such
Missing conversion -> partial warning
No widget should recalculate from raw Expense.effectiveAmount
```

---

## 7.3 Propagate partial warnings

Each widget model should carry at least:

```kotlin
val currencyQuality: CurrencyQualityUi?
```

or equivalent.

UI copy examples:

```text
"Some transactions were excluded because exchange rates are missing."
"Values use estimated midpoint exchange rates."
"Home currency unavailable; totals hidden."
```

---

## Tests

```text
dashboard_summary_category_trend_share_same_rate_basis
dashboard_trend_missing_rate_marks_partial
dashboard_monte_carlo_uses_same_basis_as_month_summary
dashboard_home_currency_failure_returns_unavailable_widgets
dashboard_category_total_sums_to_summary_for_same_basis
```

## PR 7 acceptance criteria

```text
1. Dashboard uses one normalized input for money totals.
2. Summary/category/trend are basis-consistent.
3. Partial currency quality is visible in widget models.
```

---

# PR 8 — Static money-boundary guard v2

## Fixes

```text
CURR-70F-14
```

## Goal

CI should catch regressions back to raw mixed-currency math or hidden latest fallback.

## Files

```text
scripts/verify_money_boundaries.py
.github/workflows/ci.yml
script tests/fixtures
```

---

## 8.1 Add guard rules

### G-MONEY-01 direct latest conversion in aggregate paths

Flag:

```text
currencyConverter.convert(
```

in:

```text
dashboard
budget
forecast
cashflow
analytics
repository aggregate paths
```

Allow only in:
- explicitly latest-rate APIs
- row display
- compatibility wrappers
- tests

### G-MONEY-02 hidden fallback

Flag:

```kotlin
convertAsOf(...) ?: convert(...)
```

and multiline equivalents.

### G-MONEY-03 convertMultiple

Flag:

```text
convertMultiple(
```

outside explicitly latest-rate methods.

### G-MONEY-04 home currency flow

Flag:

```text
homeCurrency().first()
```

in financial math paths.

Require:

```text
resolveHomeCurrency()
```

### G-MONEY-05 MoneyAggregate constructor without basis

Flag:

```kotlin
MoneyAggregate(
```

where no `requestedRateBasis` or `rateBasis` appears in call block.

### G-MONEY-06 legacy builder overload

Flag old `fromBuckets(List<Pair<Double,String>>...)` outside allowlisted latest wrappers.

### G-MONEY-07 legacy aggregate return types

Flag non-deprecated production APIs returning:

```text
Result<Double>
Map<..., Double>
```

from money aggregate repositories.

### G-MONEY-08 raw sums

Keep existing rules:

```text
sumOf { it.amount }
sumOf { it.effectiveAmount }
?: effectiveAmount
getOrDefault("EUR")
```

but make allowlists narrow and documented.

---

## 8.2 Script tests

Use temp fixture files.

```text
money_guard_flags_latest_fallback_after_historical_failure
money_guard_flags_homeCurrency_first_in_financial_math
money_guard_flags_convertMultiple_in_historical_path
money_guard_flags_missing_moneyAggregate_basis
money_guard_flags_raw_sum_effectiveAmount
money_guard_allows_row_display_original_amount
money_guard_allows_explicit_latest_rate_api
```

---

## 8.3 CI

Add/verify:

```yaml
- name: Verify money boundaries
  run: python3 scripts/verify_money_boundaries.py --root .
```

## PR 8 acceptance criteria

```text
1. Guard catches all known remaining bad patterns.
2. Guard has tests.
3. Guard runs in CI.
```

---

# PR 9 — Live integration / behavioral tests

## Fixes

```text
CURR-70F-15
```

## Goal

Prove production paths, not only fake/unit contracts.

## Files

```text
CurrencyNormalizationBehavioralTest.kt
ExchangeRateDaoIntegrationTest.kt
MultiCurrencyRepositoryIntegrationTest.kt
DashboardCurrencyIntegrationTest.kt
BudgetCurrencyIntegrationTest.kt
CashFlowCurrencyIntegrationTest.kt
MoneyBoundaryGuardTest.kt
```

---

## 9.1 Exchange-rate Room tests

Use in-memory Room DB.

Tests:

```text
room_migration_130_131_backfills_validDate_startOfDay
room_latest_rate_prefers_highest_validDate
room_getRateAsOf_uses_validDate_lte_date
room_historical_backfill_does_not_poison_latest
room_direct_insert_rejects_validDate_zero_if_enforced
```

---

## 9.2 Converter tests with real DAO/store

```text
convertOutcome_period_midpoint_uses_as_of_rate
convertOutcome_transaction_date_without_date_fails
stalePolicy_compareAgainst_changes_result
composite_rate_uses_oldest_validDate
```

---

## 9.3 Repository tests

```text
multiCurrency_historical_uses_per_expense_transaction_dates
multiCurrency_latest_uses_latest_rate
multiCurrency_historical_missing_rate_marks_partial
legacy_apis_not_used_by_dashboard
```

---

## 9.4 Dashboard tests

Use sentinel rates:

```text
USD rate on Jan 1 = 1.0
USD rate on Feb 1 = 2.0
latest USD rate = 10.0
```

Assert historical dashboard does not use latest 10.0.

Tests:

```text
dashboard_summary_category_trend_share_basis
dashboard_trend_missing_rate_marks_partial
dashboard_does_not_use_latest_rate_for_historical_month
```

---

## 9.5 Budget/forecast/cashflow tests

```text
budget_forecast_latest_fallback_marks_partial
budget_forecast_conversion_failure_not_low_risk
budget_rollover_skipped_when_limit_conversion_unavailable
cashflow_recurring_uses_forecast_date_rate
cashflow_actual_uses_transaction_date_rate
cashflow_missing_rate_marks_day_partial
```

---

## 9.6 Guard tests

Run script against temp fixtures.

```text
guard_fails_on_convertAsOf_fallback_convert
guard_fails_on_homeCurrency_first
guard_fails_on_missing_aggregate_basis
guard_passes_explicit_latest_api
```

## PR 9 acceptance criteria

```text
1. Tests would fail on c62de2b/early 70f behavior.
2. Tests exercise Room/repository/dashboard/budget/cashflow paths.
3. Unit tests are supplemented, not used as a substitute for integration tests.
```

---

# PR 10 — Docs and cleanup

## Goal

Make the final currency architecture understandable and prevent future agent drift.

## Files

```text
docs/currency/rate-basis-policy.md
docs/currency/money-aggregate-contract.md
docs/currency/home-currency-resolution.md
docs/currency/money-boundary-guard.md
docs/analyses and debug master/... tracker files
```

---

## Tasks

```text
1. Document RateBasis semantics.
2. Document latest vs historical API rules.
3. Document when PERIOD_MIDPOINT_ESTIMATE is allowed.
4. Document home currency failure handling.
5. Document MoneyAggregate metadata and quality meanings.
6. Document allowed raw-money arithmetic exceptions.
7. Document static guard rules and allowlist process.
8. Update debugging master tracker with fixed/open status.
9. Remove stale comments that endorse latest fallback for historical reports.
10. Raise deprecations to ERROR after all callers migrate.
```

---

# Final definition of done

Global currency normalization is complete only when:

```text
1. No runtime insert can create exchange_rates.validDate = 0.
2. PERIOD_MIDPOINT_ESTIMATE uses as-of lookup, not latest lookup.
3. Historical rate bases fail without date context.
4. StaleRatePolicy.compareAgainst is honored.
5. Composite conversion records weakest-leg provenance.
6. NormalizedExpense includes rateUsed, validDate, lastUpdated, basis, and path.
7. MoneyAggregate metadata accurately counts included/excluded/stale/missing/invalid rows.
8. Legacy aggregate helpers cannot label latest-rate math as historical.
9. MultiCurrencyRepository has explicit historical/latest APIs.
10. Historical APIs use per-expense transaction-date normalization.
11. Home currency failure returns typed unavailable/partial outputs.
12. Budget limit/forecast failures do not compute misleading remaining/risk.
13. Cashflow actuals use transaction-date and predictions use forecast-date basis.
14. Dashboard widgets share one canonical normalized input.
15. Static guard blocks raw sums, hidden latest fallback, silent EUR fallback, and ambiguous aggregate APIs.
16. Integration tests prove DAO, repository, dashboard, budget, forecast, and cashflow behavior.
```