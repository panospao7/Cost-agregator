# Global Currency Normalization / MoneyAggregate Implementation Plan

Baseline commit: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`

Universal rule:

```text
No financial arithmetic may mix currencies implicitly.
Every aggregate must declare rate basis.
Every aggregate must return MoneyAggregate or a typed normalized-money result.
No conversion failure may silently fallback to raw foreign amount.
```

Affected pipelines:

```text
P5 Currency / Dashboard / Analytics
P6 Budget / Forecasting / Cashflow
P10 Bank Integration / Bank Statement Imports
P12 Import / Export / Accounting
Indirectly:
P2 Transaction lifecycle
P3 Receipt/OCR
P11 Email receipt ingestion
```

---

## 0. Current state summary

Current code already has important foundations:

```text
CurrencyConverter.convert()
CurrencyConverter.convertAsOf()
MoneyAggregate
MoneyAggregateBuilder
AnalyticsCurrencyNormalizer
MultiCurrencyRepository
ExchangeRate.validDate
ExchangeRateDao.getRateAsOf()
```

But the app still mixes several incompatible currency contracts:

```text
1. latest-rate conversion via CurrencyConverter.convert()
2. historical conversion via convertAsOf(expense.date)
3. midpoint/date-bucket estimates
4. raw Double fallback
5. raw Expense.amount / effectiveAmount sums
6. Result<Double> legacy aggregate APIs
```

Key current issues:

1. `MoneyAggregate` is documented as the approved aggregate type, but many APIs still return raw `Double` totals.
2. `MultiCurrencyRepository` still documents and uses latest-rate aggregates for many period totals.
3. `AnalyticsCurrencyNormalizer` uses historical `convertAsOf()`, but not every dashboard/forecast/budget path uses it.
4. `ExchangeRateDao.getRate()` orders by `lastUpdated`, so historical backfills can poison latest-rate lookup.
5. `ConversionResult.timestamp` represents `lastUpdated`, not clearly `validDate`; stale-rate detection can be wrong.
6. Some widgets/forecast/cashflow paths still fallback to raw `effectiveAmount` when conversion fails.
7. Budget/forecast/export paths can lose partial/warning information.
8. Bank/import/export can preserve original currency but not conversion quality/provenance.

---

# 1. Target invariants

## 1.1 No raw mixed-currency arithmetic

Forbidden in production logic:

```kotlin
expenses.sumOf { it.amount }
expenses.sumOf { it.effectiveAmount }
converted?.convertedAmount ?: expense.effectiveAmount
```

Allowed only when:

```text
all rows are asserted same currency
or values are source buckets before conversion
or row display is showing original transaction amount
```

---

## 1.2 Every aggregate has a rate basis

Every financial total must declare one of:

```kotlin
enum class RateBasis {
    IDENTITY,
    LATEST_AVAILABLE,
    TRANSACTION_DATE,
    PERIOD_START,
    PERIOD_END,
    PERIOD_MIDPOINT_ESTIMATE,
    FORECAST_DATE,
    MANUAL_LOCKED
}
```

Examples:

```text
Dashboard historical month total -> TRANSACTION_DATE
Budget period spend -> TRANSACTION_DATE
Budget limit conversion -> PERIOD_END or MANUAL_LOCKED, explicitly documented
Current valuation widget -> LATEST_AVAILABLE
Future recurring forecast -> FORECAST_DATE or LATEST_AVAILABLE, explicitly labeled
Accounting export -> usually IDENTITY/per-currency, not hidden conversion
```

---

## 1.3 Conversion failures are explicit

If conversion fails:

```text
Do not add raw foreign amount.
Exclude row/bucket and mark aggregate partial,
or fail the operation if exact conversion is required.
```

Use:

```kotlin
enum class ConversionFailureType {
    INVALID_SOURCE_CURRENCY,
    INVALID_TARGET_CURRENCY,
    MISSING_RATE,
    MISSING_HISTORICAL_RATE,
    STALE_RATE,
    UNSUPPORTED_PAIR,
    HOME_CURRENCY_UNAVAILABLE,
    RATE_SOURCE_UNTRUSTED,
    UNKNOWN
}
```

---

## 1.4 Original and normalized values both survive

For every normalized transaction-like row, keep:

```text
originalAmount
originalCurrency
normalizedAmount
normalizedCurrency
rateBasis
rateUsed
rateValidDate
rateLastUpdated
conversionStatus
```

Never overwrite original amount/currency with home-currency values without preserving source values.

---

## 1.5 MoneyAggregate is the only aggregate result type

Every aggregate API should return:

```kotlin
MoneyAggregate
```

or a richer wrapper containing `MoneyAggregate`.

Legacy APIs returning:

```kotlin
Double
Result<Double>
Map<Something, Double>
```

must be deprecated or made internal after migration.

---

# 2. Core model additions

## 2.1 ConversionOutcome

Replace nullable `ConversionResult?` at important boundaries.

```kotlin
sealed interface ConversionOutcome {
    data class Converted(
        val originalAmount: Double,
        val originalCurrency: CurrencyCode,
        val convertedAmount: Double,
        val targetCurrency: CurrencyCode,
        val rateUsed: Double,
        val rateBasis: RateBasis,
        val rateValidDate: Long?,
        val rateLastUpdated: Long?,
        val rateSource: String?,
        val conversionPath: ConversionPath
    ) : ConversionOutcome

    data class Failed(
        val originalAmount: Double,
        val originalCurrency: String,
        val targetCurrency: String,
        val rateBasis: RateBasis,
        val failureType: ConversionFailureType,
        val message: String
    ) : ConversionOutcome
}

enum class ConversionPath {
    IDENTITY,
    DIRECT,
    VIA_BASE_CURRENCY
}
```

Keep old `convert()` / `convertAsOf()` temporarily, but internally route to:

```kotlin
convertOutcome(...)
```

---

## 2.2 NormalizedMoneyAmount

```kotlin
data class NormalizedMoneyAmount(
    val originalAmount: Double,
    val originalCurrency: CurrencyCode,
    val displayAmount: Double,
    val displayCurrency: CurrencyCode,
    val rateBasis: RateBasis,
    val rateUsed: Double,
    val rateValidDate: Long?,
    val rateLastUpdated: Long?,
    val conversionPath: ConversionPath,
    val sourceEntityType: String?,
    val sourceEntityId: Long?
)
```

---

## 2.3 NormalizedExpense

```kotlin
data class NormalizedExpense(
    val expenseId: Long,
    val merchant: String,
    val merchantKey: String?,
    val transactionType: DomainTransactionType,
    val categoryId: Long?,
    val date: Long,

    val originalAmount: Double,
    val originalEffectiveAmount: Double,
    val originalCurrency: CurrencyCode,

    val normalizedEffectiveAmount: Double,
    val displayCurrency: CurrencyCode,

    val rateBasis: RateBasis,
    val rateUsed: Double,
    val rateValidDate: Long?,
    val rateLastUpdated: Long?,
    val conversionPath: ConversionPath
)
```

---

## 2.4 MoneyAggregate v2 additions

Current `MoneyAggregate` is good. Add fields:

```kotlin
data class MoneyAggregate(
    val displayAmount: Double,
    val displayCurrency: CurrencyCode,
    val sourceBuckets: List<MoneyBucket>,
    val conversionFailures: List<ConversionFailure>,
    val isPartial: Boolean = conversionFailures.isNotEmpty(),
    val warningMessage: String? = null,

    // new
    val rateBasis: RateBasis = RateBasis.LATEST_AVAILABLE,
    val requestedRateBasis: RateBasis = rateBasis,
    val actualRateBasis: RateBasis = rateBasis,
    val conversionQuality: ConversionQuality = ConversionQuality.COMPLETE,
    val metadata: MoneyAggregateMetadata = MoneyAggregateMetadata()
)
```

```kotlin
enum class ConversionQuality {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
    ESTIMATED,
    MIXED_BASIS
}

data class MoneyAggregateMetadata(
    val includedTransactionCount: Int = 0,
    val excludedTransactionCount: Int = 0,
    val staleRateCount: Int = 0,
    val missingRateCount: Int = 0,
    val invalidCurrencyCount: Int = 0,
    val latestRateValidDate: Long? = null,
    val oldestRateValidDate: Long? = null
)
```

---

# 3. Exchange-rate semantics

## PR 1 — Fix latest vs historical rate lookup

### Goal

Backfilled historical rows must not poison current/latest-rate conversion.

### Files

```text
ExchangeRateDao.kt
ExchangeRate.kt
CurrencyConverter.kt
ExchangeRateStore implementation
migrations/tests
```

### DAO methods

Replace ambiguous `getRate()` usage with:

```kotlin
@Query("""
SELECT * FROM exchange_rates
WHERE fromCurrency = :fromCurrency
  AND toCurrency = :toCurrency
ORDER BY validDate DESC, lastUpdated DESC
LIMIT 1
""")
suspend fun getLatestRateForPair(
    fromCurrency: String,
    toCurrency: String
): ExchangeRate?
```

```kotlin
@Query("""
SELECT * FROM exchange_rates
WHERE fromCurrency = :fromCurrency
  AND toCurrency = :toCurrency
  AND validDate <= :validDate
ORDER BY validDate DESC, lastUpdated DESC
LIMIT 1
""")
suspend fun getRateAsOf(
    fromCurrency: String,
    toCurrency: String,
    validDate: Long
): ExchangeRate?
```

Keep old `getRate()` only as deprecated:

```kotlin
@Deprecated(
    message = "Use getLatestRateForPair() or getRateAsOf(); getRate() is ambiguous.",
    level = DeprecationLevel.ERROR
)
```

### Migration

If `validDate = 0` exists:

```text
Option A:
  backfill validDate = startOfDay(lastUpdated)

Option B:
  mark rows as undated and exclude them from historical lookup unless no dated row exists
```

Recommended:

```text
Backfill validDate = startOfDay(lastUpdated) for existing user-created/manual rates.
```

### Acceptance tests

```text
latest_rate_uses_highest_validDate_not_lastUpdated
historical_backfill_inserted_today_does_not_poison_latest_rate
rate_as_of_uses_validDate_lte_expense_date
validDate_zero_rows_are_migrated_or_excluded_by_policy
composite_rate_uses_validDate_for_both_legs
```

---

# 4. Currency conversion API

## PR 2 — Add typed conversion API

### Goal

All consumers can distinguish success, missing rate, stale rate, invalid currency, and fallback basis.

### Files

```text
CurrencyConverter.kt
ExchangeRateStore.kt
domain/core/money/*
```

### New API

```kotlin
suspend fun convertOutcome(
    amount: Double,
    fromCurrency: String,
    toCurrency: String,
    rateBasis: RateBasis,
    atMillis: Long? = null,
    stalePolicy: StaleRatePolicy = StaleRatePolicy.Default
): ConversionOutcome
```

```kotlin
data class StaleRatePolicy(
    val maxAgeMs: Long?,
    val compareAgainst: StaleRateReference
)

enum class StaleRateReference {
    NOW,
    TRANSACTION_DATE,
    RATE_VALID_DATE
}
```

Rules:

```text
LATEST_AVAILABLE:
  lookup getLatestRateForPair()
  stale = now - rateLastUpdated or now - rateValidDate, policy-defined

TRANSACTION_DATE:
  require atMillis
  lookup getRateAsOf(atMillis)
  stale = transactionDate - rateValidDate

PERIOD_END:
  require atMillis = periodEnd
  lookup getRateAsOf(periodEnd)

IDENTITY:
  from == to only
```

### Compatibility

Keep old methods:

```kotlin
convert(...)
convertAsOf(...)
convertMultiple(...)
```

But mark:

```text
convertMultiple = latest-rate only
convertAsOf = nullable legacy wrapper
```

### Acceptance tests

```text
convertOutcome_identity_has_rate_1_and_basis_IDENTITY
convertOutcome_latest_uses_latest_rate
convertOutcome_transaction_date_uses_as_of_rate
convertOutcome_missing_rate_returns_Failed_not_null
convertOutcome_invalid_currency_returns_INVALID_SOURCE_CURRENCY
convertOutcome_stale_rate_uses_rateValidDate
```


---

# 5. Canonical normalizer

## PR 3 — Create `MoneyNormalizationEngine`

### Goal

One normalizer for dashboard, analytics, budgets, forecasts, exports, and imports.

### Files

```text
new MoneyNormalizationEngine.kt
AnalyticsCurrencyNormalizer.kt
MultiCurrencyRepository.kt
```

### API

```kotlin
class MoneyNormalizationEngine @Inject constructor(
    private val currencyConverter: CurrencyConverter
) {
    suspend fun normalizeExpense(
        expense: Expense,
        homeCurrency: CurrencyCode,
        rateBasis: RateBasis = RateBasis.TRANSACTION_DATE
    ): NormalizationResult<NormalizedExpense>

    suspend fun normalizeExpenseSnapshots(
        snapshots: List<ExpenseSnapshot>,
        homeCurrency: CurrencyCode,
        rateBasis: RateBasis
    ): NormalizedExpenseBatch

    suspend fun aggregateExpenses(
        expenses: List<Expense>,
        homeCurrency: CurrencyCode,
        rateBasis: RateBasis,
        transactionTypeFilter: TransactionTypeFilter = TransactionTypeFilter.PURCHASE_ONLY
    ): MoneyAggregate

    suspend fun aggregateBuckets(
        buckets: List<MoneyBucketInput>,
        homeCurrency: CurrencyCode,
        rateBasis: RateBasis,
        bucketDatePolicy: BucketDatePolicy
    ): MoneyAggregate
}
```

```kotlin
sealed interface NormalizationResult<out T> {
    data class Included<T>(val value: T) : NormalizationResult<T>
    data class Excluded(
        val sourceEntityId: Long?,
        val failure: ConversionFailure
    ) : NormalizationResult<Nothing>
}
```

```kotlin
enum class TransactionTypeFilter {
    PURCHASE_ONLY,
    INCOME_ONLY,
    TRANSFER_ONLY,
    ALL_EXCEPT_TRANSFERS,
    ALL_TYPES
}
```

### Behavior

```text
Historical reports:
  per-expense convertOutcome(... TRANSACTION_DATE, expense.date)

Latest valuation:
  convertOutcome(... LATEST_AVAILABLE)

No rate:
  exclude row from aggregate
  add ConversionFailure
  aggregate.isPartial = true

Invalid currency:
  exclude row
  add ConversionFailure

Never:
  add raw amount as display currency
```

### Acceptance tests

```text
aggregate_expenses_transaction_date_uses_each_expense_date
aggregate_expenses_missing_rate_excludes_and_marks_partial
aggregate_expenses_invalid_currency_marks_failure
aggregate_purchase_only_excludes_deposits
aggregate_all_types_includes_deposits_when_requested
```

---

# 6. MoneyAggregateBuilder v2

## PR 4 — Make builder rate-basis-aware

### Current issue

`MoneyAggregateBuilder.fromBuckets()` uses `CurrencyConverter.convertMultiple()`, which is latest-rate only.

### New builder overloads

```kotlin
suspend fun fromBuckets(
    buckets: List<MoneyBucketInput>,
    homeCurrency: CurrencyCode,
    converter: CurrencyConverter,
    rateBasis: RateBasis,
    bucketDatePolicy: BucketDatePolicy
): MoneyAggregate
```

```kotlin
data class MoneyBucketInput(
    val amount: Double,
    val currency: CurrencyCode,
    val transactionCount: Int,
    val bucketDate: Long? = null,
    val sourceEntityIds: List<Long> = emptyList()
)
```

```kotlin
sealed interface BucketDatePolicy {
    data object RequireBucketDate : BucketDatePolicy
    data class FixedDate(val atMillis: Long) : BucketDatePolicy
    data object Latest : BucketDatePolicy
}
```

Rules:

```text
TRANSACTION_DATE:
  prefer per-expense normalization, not bucket conversion,
  unless bucketDate is day-level and accepted by caller.

PERIOD_MIDPOINT_ESTIMATE:
  allowed only if UI labels estimated.

LATEST_AVAILABLE:
  bucket conversion okay.

PERIOD_END:
  bucket conversion at periodEnd okay.
```

### Acceptance tests

```text
builder_latest_uses_latest_basis
builder_period_end_uses_fixed_period_end_date
builder_transaction_date_without_bucket_date_fails_or_requires_rows
builder_failure_counts_transactions_not_buckets
builder_warning_mentions_basis
```

---

# 7. Home currency resolution

## PR 5 — Remove silent EUR fallback

### Goal

If settings fail, financial totals should be unavailable/partial, not silently EUR.

### Files

```text
CurrencySettingsRepository
MultiCurrencyRepository
BudgetRepository
DashboardDataProvider
FinancialStressForecastEngine
ExportCoordinator
```

### Add

```kotlin
sealed interface HomeCurrencyResolution {
    data class Resolved(val currency: CurrencyCode) : HomeCurrencyResolution
    data class FirstRunDefault(val currency: CurrencyCode) : HomeCurrencyResolution
    data class Failed(val reason: String) : HomeCurrencyResolution
}
```

Repository API:

```kotlin
suspend fun resolveHomeCurrency(): HomeCurrencyResolution
fun observeHomeCurrencyResolution(): Flow<HomeCurrencyResolution>
```

Policy:

```text
FirstRunDefault(EUR) is okay if user has no settings yet.
Failed is not okay for financial math.
```

If failed:

```text
dashboard widgets -> partial/unavailable
budget status -> UNKNOWN
forecast -> partial/unavailable
export -> warning/fail depending format
```

### Acceptance tests

```text
home_currency_first_run_defaults_to_EUR_with_firstRun_flag
home_currency_datastore_failure_does_not_silent_EUR
dashboard_home_currency_failure_shows_unavailable
budget_home_currency_failure_status_UNKNOWN
export_home_currency_failure_records_warning_or_blocks
```

---

# 8. MultiCurrencyRepository migration

## PR 6 — Split latest-rate vs historical APIs

### Goal

Callers must choose basis explicitly.

### Replace ambiguous APIs

Deprecated:

```kotlin
getHomeCurrencyPurchaseTotal(...)
getHomeCurrencyPurchaseMonthlyTotals(...)
getHomeCurrencyWeeklyTotals(...)
getHomeCurrencyDailyTotals(...)
getCategoryTotalsInHomeCurrency(...)
getMerchantTotalsInHomeCurrency(...)
getTotalExpensesInHomeCurrency(...)
```

New names:

```kotlin
getPurchaseAggregateHistorical(...)
getPurchaseAggregateLatest(...)
getPurchaseDailyAggregatesHistorical(...)
getPurchaseWeeklyAggregatesHistorical(...)
getPurchaseMonthlyAggregatesHistorical(...)
getCategoryAggregatesHistorical(...)
getMerchantAggregatesHistorical(...)
```

For current valuation:

```kotlin
getPurchaseAggregateLatestRate(...)
getCategoryAggregatesLatestRate(...)
```

### Return types

```text
MoneyAggregate
Map<Key, MoneyAggregate>
List<PeriodMoneyAggregate>
```

```kotlin
data class PeriodMoneyAggregate(
    val startDate: Long,
    val endDate: Long,
    val label: String,
    val aggregate: MoneyAggregate
)
```

### Type filtering

Make transaction type explicit:

```kotlin
data class ExpenseAggregateQuery(
    val startDate: Long,
    val endDate: Long,
    val transactionTypeFilter: TransactionTypeFilter,
    val includeNotMine: Boolean = false,
    val useEffectiveAmount: Boolean = true,
    val rateBasis: RateBasis
)
```

### Acceptance tests

```text
weekly_purchase_aggregate_excludes_deposits
daily_purchase_aggregate_excludes_transfers
monthly_historical_matches_sum_of_daily_historical
latest_rate_api_name_contains_LatestRate
legacy_Result_Double_api_deprecated_or_internal
```

---

# 9. Dashboard / analytics migration

## PR 7 — Canonical dashboard normalized input

### Goal

Dashboard widgets consume normalized data and quality metadata, not raw expenses.

### Files

```text
DashboardDataProvider.kt
ComputeDashboardWidgetsUseCase.kt
DashboardContractsAdapter.kt
AnalyticsRepository.kt
AnalyticsInputAssembler.kt
TotalsAggregationEngine.kt
```

### Add

```kotlin
data class DashboardNormalizedInput(
    val homeCurrency: CurrencyCode,
    val period: DateRange,
    val normalizedExpenses: List<NormalizedExpense>,
    val aggregate: MoneyAggregate,
    val dataQuality: CurrencyDataQuality
)
```

```kotlin
data class CurrencyDataQuality(
    val isPartial: Boolean,
    val missingRateCount: Int,
    val staleRateCount: Int,
    val invalidCurrencyCount: Int,
    val excludedTransactionCount: Int,
    val warningMessage: String?
)
```

### Required changes

```text
SpendingSummary -> MoneyAggregate + CurrencyDataQuality
CategoryBreakdown -> MoneyAggregate per category
SpendingTrend -> no raw fallback
PeriodSummary -> partial if any source aggregate partial
Weekly/daily/monthly totals -> purchase-only and same rate basis
Block party / forecast widgets -> normalized values only
```

### Remove

```kotlin
converted?.convertedAmount ?: exp.effectiveAmount
```

### Acceptance tests

```text
dashboard_summary_and_category_use_same_rate_basis
spending_trend_missing_rate_marks_partial_not_raw_fallback
period_summary_partial_when_month_summary_partial
weekly_daily_drilldown_excludes_deposits_transfers
monthly_total_equals_sum_daily_historical_totals
```


---

# 10. Budget / forecast / cashflow migration

## PR 8 — Budget and forecast normalized contracts

### Goal

Budgets and forecasts cannot compare normalized spend to raw foreign limits or raw recurring patterns.

### Files

```text
BudgetRepository.kt
BudgetMonitor.kt
BudgetForecastingEngine.kt
ForecastInputAssembler.kt
SynthesisEngine.kt
CashFlowCalculator.kt
FinancialStressForecastEngine.kt
```

### Budget status model

```kotlin
data class NormalizedBudgetStatus(
    val budgetId: Long,
    val limit: MoneyAggregate,
    val spent: MoneyAggregate,
    val remaining: MoneyAggregate?,
    val percentUsed: Double?,
    val reliability: BudgetReliability,
    val health: BudgetHealthStatus,
    val warningMessage: String?
)

enum class BudgetReliability {
    RELIABLE,
    PARTIAL_SPEND,
    LIMIT_CONVERSION_FAILED,
    HOME_CURRENCY_UNAVAILABLE,
    UNKNOWN
}
```

Rules:

```text
If budget limit conversion fails:
  percentUsed = null
  health = UNKNOWN
  reliability = LIMIT_CONVERSION_FAILED

Never:
  health = ON_TRACK because percent forced to 0
```

### Forecast input model

```kotlin
data class NormalizedForecastInput(
    val actualExpenses: List<NormalizedExpense>,
    val plannedExpenses: List<NormalizedPlannedExpense>,
    val recurringPatterns: List<NormalizedRecurringPattern>,
    val confirmedOccurrences: List<NormalizedConfirmedOccurrence>,
    val homeCurrency: CurrencyCode,
    val dataQuality: ForecastCurrencyQuality
)
```

Synthesis engine should accept only normalized input.

### Cashflow line item

```kotlin
data class CashFlowLineItem(
    val sourceId: Long,
    val originalAmount: Double,
    val originalCurrency: CurrencyCode,
    val displayAmount: Double?,
    val displayCurrency: CurrencyCode,
    val conversionFailure: ConversionFailure?
)
```

### Acceptance tests

```text
budget_spend_and_limit_have_documented_rate_basis
budget_limit_conversion_failure_health_UNKNOWN_not_ON_TRACK
budget_dashboard_preserves_partial_warning
forecast_recurring_pattern_usd_converted_before_synthesis
confirmed_occurrence_usd_converted_before_committed_total
cashflow_actual_uses_expense_date_rate
cashflow_missing_rate_marks_day_partial_not_raw_fallback
stress_detected_recurring_usd_converted_to_home
```

---

# 11. Transaction creation and source ingestion

## PR 9 — Store conversion provenance at source boundaries

### Goal

Expenses preserve original currency and optional base conversion snapshot without pretending conversion succeeded.

### Applies to

```text
P2 manual/notification/review create
P3 receipt create
P10 bank sync/statement import
P11 email receipt import
P12 import pipeline
```

### Create request extension

```kotlin
data class CreateExpenseRequest(
    ...
    val originalAmount: Double? = null,
    val originalCurrency: String? = null,
    val conversionSnapshot: ConversionSnapshot? = null
)
```

```kotlin
data class ConversionSnapshot(
    val baseAmount: Double?,
    val baseCurrency: String?,
    val rateUsed: Double?,
    val rateBasis: RateBasis?,
    val rateValidDate: Long?,
    val rateLastUpdated: Long?,
    val conversionStatus: ExportConversionStatus
)
```

Rules:

```text
Expense.amount/currency = transaction's real amount/currency.
baseAmount/baseCurrency only set if conversion succeeded.
No fake baseAmount=0 for missing conversion.
```

### Acceptance tests

```text
foreign_currency_expense_preserves_original_amount_currency
missing_rate_does_not_set_fake_baseAmount_zero
bank_import_preserves_bank_transaction_currency
email_receipt_preserves_parsed_currency
manual_create_invalid_currency_rejected
```

---

# 12. Bank and import/export application

## PR 10 — Bank and export currency correctness

### Bank

Bank import rules:

```text
provider amount/currency are source of truth
pending transactions not auto-approved
if currency invalid -> pending review or failed item
conversion optional but failure must not block source import unless feature requires it
```

Required events/metadata:

```text
originalAmount
originalCurrency
conversionStatus
providerTransactionIdHash
```

### Export

Export schema must include:

```text
amount
currency
effectiveAmount
originalAmount
originalCurrency
baseAmount
baseCurrency
rateUsed
rateBasis
rateValidDate
rateLastUpdated
conversionStatus
isPartial
warningMessage
```

Accounting exports:

```text
Do not silently convert mixed currencies.
Either:
  export per-currency files,
  reject mixed currency,
  or require explicit conversion basis and include warning manifest.
```

Import:

```text
If imported row has original + base conversion metadata, preserve it.
If not, recompute only with explicit import option.
```

### Acceptance tests

```text
bank_import_invalid_currency_creates_review_or_failed_item
statement_import_preserves_currency
json_export_includes_conversion_status_and_rate_basis
csv_import_preserves_original_currency_and_base_snapshot
accounting_export_rejects_mixed_currency_without_explicit_policy
```

---

# 13. Static guards

## PR 11 — Raw money arithmetic guard

Add script:

```text
scripts/verify_money_boundaries.py
```

Fail on production usages:

```regex
sumOf\s*\{\s*it\.amount\s*\}
sumOf\s*\{\s*it\.effectiveAmount\s*\}
\?\:\s*.*effectiveAmount
getOrDefault\("EUR"\)
Result<\s*Double\s*>
Map<[^>]+,\s*Double\s*>
getTotal.*InHomeCurrency\(
get.*TotalsInHomeCurrency\(
currencyConverter\.convert\(
```

Allowlist examples:

```text
MoneyNormalizationEngine
MoneyAggregateBuilder
row display code
source bucket construction
tests
migrations
debug-only diagnostics
```

Also fail on:

```text
ExchangeRateDao.getRate() usage
```

outside compatibility wrappers.

### Acceptance tests

```text
money_guard_fails_on_sumOf_effectiveAmount_in_dashboard
money_guard_fails_on_raw_conversion_fallback
money_guard_fails_on_silent_EUR_default
money_guard_allows_source_bucket_construction
money_guard_allows_row_display_original_amount
```

---

# 14. UI / API propagation

## PR 12 — Currency quality UI propagation

### Goal

Users see when numbers are partial or estimated.

Add:

```kotlin
data class CurrencyQualityUi(
    val isPartial: Boolean,
    val isEstimated: Boolean,
    val warningMessage: String?,
    val missingRateCount: Int,
    val staleRateCount: Int,
    val invalidCurrencyCount: Int,
    val excludedTransactionCount: Int,
    val rateBasisLabel: String
)
```

Attach to:

```text
Dashboard summary
Period summary
Spending trend
Category breakdown
Budget status cards
Forecast widgets
Cashflow day rows
Export preview
Accounting export validation result
```

Acceptance tests:

```text
dashboard_summary_shows_missing_rate_warning
budget_card_shows_unknown_when_limit_conversion_failed
forecast_widget_shows_partial_data_warning
export_preview_shows_mixed_currency_warning
```


---

# 15. Migration strategy

## Phase 1 — Compatibility

```text
Add new types and APIs.
Keep old APIs with deprecation warnings.
No behavior-breaking UI changes yet.
```

## Phase 2 — Migrate P5/P6

```text
Dashboard/analytics/budget/forecast/cashflow use canonical normalizer.
Stop raw fallback.
```

## Phase 3 — Migrate P10/P12

```text
Bank/import/export preserve conversion provenance and warnings.
Accounting exports enforce policy.
```

## Phase 4 — Enforce

```text
Turn deprecations to ERROR.
Enable static guard in CI.
Delete legacy Result<Double> aggregate APIs or make internal.
```

---

# 16. Recommended PR order

```text
PR 1  Exchange-rate DAO semantics: latest vs as-of
PR 2  ConversionOutcome + typed rate-basis conversion API
PR 3  MoneyNormalizationEngine
PR 4  MoneyAggregateBuilder v2 with rate basis
PR 5  HomeCurrencyResolution, remove silent EUR fallback
PR 6  MultiCurrencyRepository API split: historical/latest explicit
PR 7  Dashboard/analytics normalized input migration
PR 8  Budget/forecast/cashflow normalized contracts
PR 9  Transaction/source conversion provenance
PR 10 Bank/export/import currency correctness
PR 11 Static raw-money guard
PR 12 CurrencyQualityUi propagation
```

Fastest risk-reduction order:

```text
1. Remove raw fallback in dashboard/forecast/cashflow.
2. Fix ExchangeRateDao latest lookup semantics.
3. Add ConversionOutcome and MoneyNormalizationEngine.
4. Migrate dashboard/budget to MoneyAggregate.
5. Add static guard.
```

---

# 17. Golden test matrix

Add global tests:

```text
latest_rate_uses_validDate_not_lastUpdated
historical_rate_as_of_uses_validDate_lte_transaction_date
historical_backfill_does_not_poison_latest_rate
convertOutcome_missing_rate_returns_failure
convertOutcome_invalid_currency_returns_failure
money_aggregate_partial_excludes_failed_rows
money_aggregate_warning_counts_transactions_not_buckets

dashboard_month_total_uses_transaction_date_rates
dashboard_category_sum_matches_parent_total
weekly_drilldown_excludes_deposits
daily_drilldown_excludes_transfers
spending_trend_missing_rate_no_raw_fallback

budget_limit_conversion_failure_status_unknown
budget_spend_and_limit_rate_basis_recorded
forecast_recurring_pattern_converted_before_synthesis
confirmed_occurrence_converted_before_synthesis
cashflow_actual_uses_expense_date_rate
stress_detected_pattern_converted

home_currency_failure_no_silent_EUR
json_export_includes_rate_basis_and_conversion_status
accounting_export_rejects_mixed_currency_without_policy
bank_import_preserves_original_currency
import_preserves_conversion_snapshot
money_guard_blocks_sumOf_effectiveAmount
```

---

# 18. Agent implementation checklist

Before coding, run:

```bash
rg "getRate\\(" app/src/main/java
rg "getRateAsOf" app/src/main/java
rg "convertMultiple" app/src/main/java
rg "currencyConverter.convert\\(" app/src/main/java
rg "currencyConverter.convertAsOf" app/src/main/java
rg "sumOf.*amount" app/src/main/java
rg "sumOf.*effectiveAmount" app/src/main/java
rg "\\?:.*effectiveAmount" app/src/main/java
rg "getOrDefault\\(\"EUR\"\\)" app/src/main/java
rg "DEFAULT_HOME_CURRENCY|DEFAULT_BASE_CURRENCY" app/src/main/java
rg "Result<.*Double" app/src/main/java
rg "Map<.*Double" app/src/main/java
rg "MoneyAggregate" app/src/main/java
rg "MoneyAggregateBuilder" app/src/main/java
rg "getHomeCurrencyPurchaseTotal" app/src/main/java
rg "getHomeCurrencyWeeklyTotals" app/src/main/java
rg "getHomeCurrencyDailyTotals" app/src/main/java
rg "BudgetHealthStatus.ON_TRACK" app/src/main/java
rg "baseAmount = 0.0|baseCurrency" app/src/main/java
```

Allowed raw-money usage must be documented:

```text
- row-level display of original transaction amount
- source bucket construction before conversion
- single-currency assertions
- tests/debug fixtures
```

---

# 19. Definition of done

```text
1. Every aggregate API returns MoneyAggregate or a typed wrapper containing MoneyAggregate.

2. Every aggregate declares RateBasis.

3. Exchange-rate latest lookup uses validDate semantics and cannot be poisoned by historical backfill.

4. Historical reports use transaction-date conversion, not latest-rate or midpoint estimate unless explicitly labeled.

5. No dashboard, forecast, budget, or cashflow path falls back to raw foreign amount after conversion failure.

6. Home currency resolution failure does not silently default to EUR.

7. Budget conversion failure does not produce ON_TRACK / 0%.

8. Weekly/daily/monthly drilldowns use the same transaction-type filter and rate basis.

9. Forecast recurring patterns, planned expenses, and confirmed occurrences are normalized before synthesis.

10. Cashflow returns normalized line items with per-day partial warnings.

11. Bank/import/export preserve original currency and conversion provenance.

12. Accounting export rejects or explicitly handles mixed currencies.

13. Currency quality/warnings are visible in UI models.

14. Static guard blocks new raw mixed-currency arithmetic.
```

---

# 20. Sources used

- Baseline commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- `CurrencyConverter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt

- `MultiCurrencyRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

- `MoneyAggregate.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregate.kt

- `MoneyAggregateBuilder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt

- `ExchangeRateDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExchangeRateDao.kt

- `AnalyticsCurrencyNormalizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt
