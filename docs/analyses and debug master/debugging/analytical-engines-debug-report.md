# Analytical Engines Debug Report

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local execution.

## 1. Executive summary

The analytical layer is large and partially well-tested. It includes:

```text
AnalyticsRepository
AnalyticsViewModel
AdvancedAnalyticsEngine
InsightsEngine
TotalsAggregationEngine
AnalyticsCurrencyNormalizer
AnomalyDetector
CategoryInsightEngine
MerchantInsightEngine
MonthlyComparisonCalculator
SpendingPaceCalculator
DayOfWeekAnalyzer
SpendingPersonalityClassifier
location analytics engines
forecast/weather consumers
dashboard consumers
```

The good news:

- Many analytical engines already have direct unit tests.
- `AnalyticsCurrencyNormalizer` exists.
- `AdvancedAnalyticsEngine` mostly normalizes before arithmetic.
- `AnalyticsViewModel` normalizes many expense flows before computing totals.
- Conversion warnings exist.
- `DataQualityReport` exists.
- Anomaly detection has robust IQR/MAD/contextual logic.
- Some engine KDocs clearly document currency safety limitations.

The bad news:

> The analytical stack still has **multiple competing analytics paths**, and some are safe only if callers pre-normalize data correctly.

Highest-risk findings:

1. **No single canonical analytics input contract exists.**
2. **`TotalsAggregationEngine` still knowingly raw-sums mixed-currency DAO totals.**
3. **`AnalyticsCurrencyNormalizer` uses current rates, not historical `convertAsOf(expense.date)`.**
4. **`SpendingPersonalityClassifier` uses raw repository snapshots and assumes they are normalized, but it does not normalize them.**
5. **`AnalyticsRepository` returns plain `Double` totals and drops partial-conversion warnings.**
6. **`InsightsEngine` is caller-normalized only, but still defaults to `"EUR"` and has public entry points that can be misused.**
7. **Daily chart generation uses “last N days from now,” not the selected period’s exact start/end.**
8. **Advanced analytics, basic analytics, repository analytics, and ViewModel analytics can disagree.**
9. **Budget comparisons inside analytics can compare normalized spend against raw budget amounts.**
10. **Large analytics recomputations duplicate DB reads and conversions, which can become slow/flaky.**

Main recommendation:

> Create one `NormalizedAnalyticsInput` / `AnalyticsSnapshot` contract and force every analytics engine to consume it instead of raw `ExpenseSnapshot` or raw DAO totals.

---

# 2. Analytical engine map

Current rough flow:

```text
AnalyticsScreen
→ AnalyticsViewModel
   → ExpenseRepository
   → CategoryRepository
   → BudgetRepository
   → AnalyticsCurrencyNormalizer
   → InsightsEngine
      → MonthlyComparisonCalculator
      → CategoryInsightEngine
      → MerchantInsightEngine
      → SpendingPaceCalculator
      → AnomalyDetector
      → DayOfWeekAnalyzer
   → AdvancedAnalyticsEngine
   → LocationInsightsEngine / AreaSpendingEngine / TravelDetectionEngine
   → SpendingPersonalityClassifier

AnalyticsRepository
→ ExpenseDao
→ MultiCurrencyRepository
→ AnalyticsCurrencyNormalizer

Dashboard/other consumers
→ TotalsAggregationEngine
→ MultiCurrencyRepository
→ AnalyticsRepository
```

This means analytics can be computed through at least four routes:

```text
1. AnalyticsViewModel manual normalized calculations
2. AnalyticsRepository repository-level analytics
3. AdvancedAnalyticsEngine
4. TotalsAggregationEngine raw DAO analytics
```

That is too many sources of truth.

---

# 3. Strong parts

## 3.1 Good test presence

The repo has many direct analytics tests:

```text
AdvancedAnalyticsDashboardTest
AdvancedAnalyticsEngineDeepTest
AdvancedAnalyticsEngineTest
AnalyticsCurrencyNormalizerTest
AnalyticsStressTest
AnalyticsWindowingSupportTest
AnomalyDetectorTest
CategoryInsightEngineTest
DayOfWeekAnalyzerTest
InsightsEngineDeepTest
InsightsEngineEdgeCaseTest
InsightsEngineTest
InsightsEngineValidationTest
MerchantInsightEngineTest
MonthlyComparisonCalculatorTest
SpendingPaceBoundaryTest
SpendingPaceCalculatorDeepTest
SpendingPaceCalculatorValidationTest
SpendingPaceGoldenTest
SpendingPersonalityClassifierTest
SpendingThresholdCalculatorTest
TotalsAggregationEngineDeepTest
TotalsAggregationEngineTest
TotalsAggregationEngineValidationTest
TransferDirectionAnalyticsTest
```

So this is not an “untested” subsystem.

The problem is mostly **contract drift between engines**.

---

## 3.2 `AdvancedAnalyticsEngine` is heading in the right direction

Its KDoc says:

```text
CURRENCY NORMALIZATION: SAFE — fully normalized
```

It injects `AnalyticsCurrencyNormalizer` and normalizes:

```text
category analytics
merchant analytics
spending patterns
statistical insights
```

This is the right model.

But it still returns warnings separately and many downstream models still expose plain `Double`.

---

## 3.3 `AnalyticsViewModel` normalizes many paths

`AnalyticsViewModel.computeAnalyticsInternal()` normalizes:

```text
current period expenses
previous period expenses
all expenses
year-over-year expenses
warning aggregation data
```

Then it uses normalized snapshots for:

```text
current total
category breakdown
merchant breakdown
year-over-year
velocity anomalies
post-salary pattern
suspect transaction detection
day/hour patterns
location normalization
```

That is much safer than raw DAO summing.

---

# 4. Major findings

## Finding P0-1 — No single canonical analytics input contract

Many engines rely on comments like:

```text
SAFE: data normalized before reaching this engine
```

Examples:

- `InsightsEngine`
- `CategoryInsightEngine`
- `MerchantInsightEngine`
- `MonthlyComparisonCalculator`
- `SpendingPaceCalculator`
- `DayOfWeekAnalyzer`
- `SpendingPersonalityClassifier`

This is fragile because the type system does not enforce it.

A raw `List<ExpenseSnapshot>` and a normalized `List<ExpenseSnapshot>` have the same type.

So a future caller can accidentally pass unnormalized multi-currency snapshots and get plausible but wrong analytics.

### Fix

Introduce explicit types:

```kotlin
data class NormalizedAnalyticsInput(
    val period: AnalyticsPeriodRange,
    val homeCurrency: String,
    val expenses: List<NormalizedExpenseSnapshot>,
    val categories: Map<Long, AnalyticsCategoryRef>,
    val budgets: List<NormalizedBudgetSnapshot>,
    val conversionWarnings: List<AnalyticsConversionWarning>,
    val dataQuality: DataQualityReport,
    val generatedAt: Long
)

data class NormalizedExpenseSnapshot(
    val id: Long,
    val normalizedAmount: Double,
    val normalizedCurrency: String,
    val originalAmount: Double,
    val originalCurrency: String,
    val transactionType: DomainTransactionType,
    val date: Long,
    val categoryId: Long?,
    val merchant: String,
    val merchantKey: String?,
    val isNotMine: Boolean,
    val transferDirection: DomainTransferDirection?
)
```

Then engines should accept:

```kotlin
NormalizedAnalyticsInput
```

not raw snapshots.

Priority: highest.

---

## Finding P0-2 — `TotalsAggregationEngine` is knowingly unsafe for multi-currency

`TotalsAggregationEngine` contains a clear warning:

```text
CURRENCY NORMALIZATION: GAP — no normalization applied
```

It says it raw-sums DAO totals in:

```text
getMonthlyTotals
getWeeklyTotals
getDailyTotals
getYearlyTotals
getCategoryBreakdown
getAverageForPeriodType
```

This is dangerous because the class still exists in production dependency maps and is injected into `SpendingPersonalityClassifier`.

If any screen uses it with mixed-currency data, totals are wrong.

### Fix

Choose one:

1. Refactor `TotalsAggregationEngine` to use `MultiCurrencyRepository` / `AnalyticsCurrencyNormalizer`.
2. Mark it internal legacy and block production callers.
3. Add CI guard that fails on production references unless allowlisted.

Short-term guard:

```kotlin
require(isSingleCurrencyDataset) {
    "TotalsAggregationEngine cannot aggregate mixed-currency expenses"
}
```

Priority: highest.

---

## Finding P0-3 — Historical analytics uses current exchange rates

`AnalyticsCurrencyNormalizer` calls:

```kotlin
currencyConverter.convert(...)
```

not:

```kotlin
currencyConverter.convertAsOf(..., atMillis = expense.date)
```

So historical analytics can change when exchange rates update.

Example:

```text
2024 USD expense
2026 exchange rate added
2024 analytics now changes
```

That breaks:

- month comparisons,
- year-over-year,
- trend charts,
- spending personality,
- anomaly detection,
- forecast training,
- backup/restore parity.

### Fix

For analytics/reporting:

```kotlin
currencyConverter.convertAsOf(
    amount = expense.effectiveAmount,
    fromCurrency = sourceCurrency.code,
    toCurrency = homeCurrency.code,
    atMillis = expense.date
)
```

Keep `convert()` only for current-value display.

Priority: highest.

---

## Finding P0-4 — `SpendingPersonalityClassifier` is not currency-safe

`SpendingPersonalityClassifier.classify()` fetches:

```kotlin
expenseRepository.getExpenseSnapshotsBetween(...)
```

Then raw-sums:

```text
weekendSpendShare
nightSpendShare
daily spending variance
budget adherence
anomaly frequency
average transaction size
```

The comments say:

```text
SAFE: data normalized before reaching this engine
```

But the classifier itself fetches raw snapshots. It does not inject `AnalyticsCurrencyNormalizer` or `CurrencySettingsRepository`.

It also compares category spending to `BudgetSnapshot.amount`, which may be in a different currency.

### Symptoms

A user with EUR + USD + GBP transactions may be classified incorrectly as:

```text
IMPULSE
SOCIAL_SPENDER
MINIMALIST
```

depending on raw nominal amounts, not real home-currency spend.

### Fix

Inject:

```text
CurrencySettingsRepository
AnalyticsCurrencyNormalizer
```

Normalize `allExpenses` before feature extraction.

Also normalize budgets before budget-adherence scoring or use `BudgetRepository.getBudgetStatuses()`.

Priority: highest.

---

## Finding P0-5 — AnalyticsRepository drops partial-conversion state

`AnalyticsRepository.getSpendingSummary()` uses:

```text
currentAggregate.displayAmount
previousAggregate.displayAmount
currentAggregate.totalTransactionCount
```

and returns:

```kotlin
SpendingSummary(
    totalSpent: Double,
    previousTotalSpent: Double?,
    dailyHistory: List<Double>,
    currency: String
)
```

`MoneyAggregate` knows:

```text
isPartial
conversionFailures
sourceBuckets
warningMessage
```

But `SpendingSummary` loses that information.

### Risk

UI can show:

```text
Total: €100
```

when actual data is:

```text
€100 + £30 excluded because GBP rate missing
```

### Fix

Change output models:

```kotlin
data class SpendingSummary(
    val total: MoneyAggregate,
    val previousTotal: MoneyAggregate?,
    val dailyHistory: List<DailyMoneyAggregate>,
    val transactionCount: Int,
    val dataQuality: DataQualityReport
)
```

Short-term:

```text
add isPartial, conversionWarnings, excludedCount, sourceBuckets
```

Priority: highest.

---

## Finding P0-6 — Basic, advanced, and repository analytics can disagree

Examples:

### Basic analytics

`AnalyticsViewModel` computes current totals manually from normalized snapshots.

### Repository analytics

`AnalyticsRepository` uses `MultiCurrencyRepository` for totals and `AnalyticsCurrencyNormalizer` for daily history.

### Advanced analytics

`AdvancedAnalyticsEngine` independently fetches snapshots from `ExpenseRepository` and normalizes them.

### Legacy totals

`TotalsAggregationEngine` raw-sums repository totals.

Because they do not share one input snapshot, they can differ if:

- exchange rates update during computation,
- an expense changes during computation,
- a conversion fails in one path,
- date windows differ,
- category maps change,
- budget data changes,
- one path filters transaction types differently.

### Fix

Create one orchestrator:

```kotlin
AnalyticsInputAssembler
```

It should build one consistent snapshot:

```text
period
comparison period
normalized expenses
categories
budgets
conversion warnings
data quality
```

Then pass that exact object to every engine.

Priority: highest.

---

## Finding P1-1 — `InsightsEngine` is caller-normalized only and defaults to EUR

`InsightsEngine` explicitly says:

```text
caller responsibility
hardcoded EUR default
```

Main `AnalyticsViewModel` passes normalized data and home currency, which is good.

But public methods still allow unsafe calls:

```kotlin
generateInsights(... displayCurrency: String = "EUR")
getLegacyInsights(... homeCurrency: String = "EUR")
getSpendingPaceSuspend(... displayCurrency: String = "EUR")
```

### Fix

Remove defaults or require:

```kotlin
NormalizedAnalyticsInput
```

At minimum:

```kotlin
@Deprecated("Use NormalizedAnalyticsInput overload")
```

for raw overloads.

Priority: high.

---

## Finding P1-2 — Daily chart generation uses “last N days from now,” not exact selected range

`AnalyticsViewModel` computes selected period:

```text
currentStart/currentEnd
```

But daily chart uses:

```kotlin
insightsEngine.buildDailyTotals(currentExpenseSnapshots, chartDays)
```

`buildDailyTotals()` initializes days from:

```text
now - N days
```

not from:

```text
currentStart → currentEnd
```

For `MONTH`, `QUARTER`, `YEAR`, and `ALL`, this can produce odd charts.

Examples:

```text
YEAR selected on Jan 10:
  currentExpenseSnapshots = Jan 1-Jan 10
  daily chart initializes last 365 days
  chart mostly prior-year zero days

QUARTER selected:
  calendar quarter range != last 90 days exactly

ALL selected:
  capped 365 days, not actual all-time
```

### Fix

Replace:

```kotlin
buildDailyTotals(expenses, days)
```

with:

```kotlin
buildDailyTotalsForRange(expenses, startMs, endMs, bucket = DAY/WEEK/MONTH)
```

For long ranges:

```text
TODAY → hourly
WEEK/MONTH → daily
QUARTER → weekly
YEAR/ALL → monthly
```

Priority: high.

---

## Finding P1-3 — Advanced analytics may use a different period than the main screen

`AnalyticsViewModel` mostly uses `CUSTOM` for month/quarter/year, which is good.

But for `WEEK`, it maps to:

```kotlin
AdvancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.WEEK)
```

using the engine’s own `timeProvider.now()`.

Usually this matches the main ViewModel’s selected week, but it is still a second source of period truth.

### Fix

Always pass explicit `AnalyticsPeriodRange` from the ViewModel:

```kotlin
AnalyticsPeriodRange(
    period = ...,
    startMs = currentStart,
    endMs = currentEnd,
    comparisonRange = ...
)
```

Do not let sub-engines recalculate screen periods.

Priority: medium-high.

---

## Finding P1-4 — Advanced category analytics compares normalized spend to raw budget amount

`AdvancedAnalyticsEngine.getCategoryAnalytics()` normalizes expenses to `displayCurrency`.

Then it loads budget snapshots and does:

```text
budgetUtilization = total / budget.amount
budgetRemaining = budget.amount - total
```

If budget is not already in `displayCurrency`, this is mixed-unit math.

### Fix

Normalize budget snapshots before passing them into advanced analytics.

Or use `BudgetRepository.getBudgetStatuses()` as canonical budget state.

Priority: high.

---

## Finding P1-5 — Conversion warnings do not consistently affect confidence/severity

Warnings exist and `AnalyticsState` has:

```text
conversionWarnings
qualityWarnings
latestRateTimestamp
```

But many engines still compute:

```text
pace status
anomaly severity
personality confidence
statistical volatility
category trend severity
```

as if excluded transactions did not exist.

If 30% of transactions were excluded due to missing rates, analytics should degrade confidence.

### Fix

Add `AnalyticsDataQuality` to every result:

```kotlin
data class AnalyticsDataQuality(
    val inputCount: Int,
    val includedCount: Int,
    val excludedCount: Int,
    val lossPercentage: Double,
    val warnings: List<AnalyticsConversionWarning>
)
```

Then apply:

```text
confidencePenalty = f(lossPercentage)
hide or downgrade anomaly/pace claims if partial
show “partial analytics” banner
```

Priority: high.

---

## Finding P1-6 — Merchant-level anomaly detection has limited history in normal screen path

`InsightsEngine.findAnomalies()` compares current-month merchant max against historical average.

But `AnalyticsViewModel` passes `allExpenseSnapshots` built from:

```text
fullWindowStart = start of current year
```

except for `ALL`.

So in January/early year there may be almost no historical data even though older DB data exists.

The comment says “all-time historical average,” but the actual input may be year-to-date only.

### Fix

For anomaly detection, explicitly fetch a historical lookback:

```text
last 12 months or all available history
```

independent of selected chart period.

Or pass `AnalyticsInput` with separate:

```text
currentPeriodExpenses
comparisonExpenses
historicalBaselineExpenses
```

Priority: high.

---

## Finding P1-7 — Spending pace and explicit historical periods can be wrong

`SpendingPaceCalculator.calculate()` uses:

```kotlin
val now = timeProvider.now()
val currentWindowEnd = minOf(now, currentMonthEnd)
```

That is correct for current-month pace.

But `InsightsEngine.generateInsights(periodRange)` can generate insights for arbitrary historical ranges. If pace is computed for a historical period, using real “now” is wrong.

### Fix

Add parameter:

```kotlin
referenceNow: Long
```

or use:

```text
period.endMs for historical periods
timeProvider.now() only for live/current periods
```

Priority: medium-high.

---

## Finding P1-8 — Location analytics has a raw DAO path

`AnalyticsRepository.getLocationSpendSummary()` uses:

```text
expenseDao.getLocatedMerchantTotals()
```

and returns:

```text
totalSpend: Double
```

This likely raw-sums located merchant totals without currency normalization.

`AnalyticsViewModel` has a safer path for current located purchases, but repository location summary remains risky.

### Fix

Make location analytics use normalized snapshots / `MoneyAggregate`.

Priority: high if location analytics is visible.

---

## Finding P1-9 — Category deletion / history still weak

`CategoryInsightEngine` documents a limitation:

```text
deleted category IDs fall back to Uncategorized
```

That means historical analytics can change after deleting categories.

Example:

```text
2025 Grocery category deleted
2025 analytics now shows Uncategorized
```

### Fix

Choose a history policy:

1. soft-delete categories,
2. persist category name/color snapshot on expense,
3. create a historical category dimension table.

For financial analytics, soft-delete is usually simplest.

Priority: medium-high.

---

## Finding P1-10 — Analytics recomputes too much

`AnalyticsViewModel.computeAnalyticsInternal()` performs many independent queries and normalizations:

```text
current expenses
previous expenses
all expenses
year-over-year expenses
warning normalization over de-duped union
advanced engine fetches expenses again internally
spending personality classifier fetches another 3-month window
location analytics builds more structures
```

This can be slow on large DBs and can produce inconsistent snapshots if writes happen during computation.

### Fix

Use one assembler:

```kotlin
AnalyticsInputAssembler
```

It should:

```text
query once
normalize once
split into windows in memory
build category/budget maps once
record input version/timestamp
```

Also consider:

```text
database.withTransaction/read snapshot
```

or one DAO query returning all required rows for a stable period.

Priority: medium-high.

---

# 5. Debugging checklist for analytical engines

## Currency/data quality

Check:

- [ ] every analytics total uses normalized home currency,
- [ ] historical analytics uses `convertAsOf(expense.date)`,
- [ ] missing rates create warnings,
- [ ] stale rates create warnings,
- [ ] invalid currency rows are excluded with warnings,
- [ ] partial outputs do not look complete,
- [ ] every output exposes `isPartial` or data quality.

## Period/windowing

Check:

- [ ] selected period has one source of truth,
- [ ] current and comparison periods use exact boundaries,
- [ ] daily/weekly/monthly buckets align to selected range,
- [ ] DST-safe day boundaries,
- [ ] leap day behavior,
- [ ] month-end behavior,
- [ ] historical period pace does not use real “now”.

## Transaction semantics

Check:

- [ ] purchases only where spending is required,
- [ ] deposits excluded from spending,
- [ ] transfers handled by direction,
- [ ] `isNotMine` excluded,
- [ ] shared expenses use effective amount,
- [ ] refunds policy explicit,
- [ ] recurring planned/actual not double-counted.

## Engine consistency

Check:

- [ ] AnalyticsRepository total equals AnalyticsViewModel total,
- [ ] AdvancedAnalyticsEngine total equals basic analytics total,
- [ ] category breakdown sums to total,
- [ ] merchant breakdown sums to total,
- [ ] daily buckets sum to total,
- [ ] dashboard total equals analytics total for same period.

## Performance/snapshot

Check:

- [ ] no repeated huge DB reads per screen recomposition,
- [ ] no raw all-history fetch for every minor UI state change,
- [ ] no analytics compute on UI thread,
- [ ] concurrent DB writes do not produce internally inconsistent analytics,
- [ ] caches invalidate on expense/category/budget/rate changes.

## Historical identity

Check:

- [ ] category deletion does not corrupt historical analytics,
- [ ] merchant rename/normalization changes do not corrupt history,
- [ ] old reports do not change after new exchange rates,
- [ ] backup/restore analytics totals match pre-backup totals.

---

# 6. Recommended fix plan

## PR 1 — Add `NormalizedAnalyticsInput`

Create a canonical analytics input assembler:

```kotlin
class AnalyticsInputAssembler {
    suspend fun assemble(
        period: PeriodRange,
        comparison: PeriodRange?,
        baseline: PeriodRange?,
        displayCurrency: String
    ): NormalizedAnalyticsInput
}
```

It should:

```text
fetch expenses once
normalize with convertAsOf
split current/comparison/baseline
load categories/budgets
normalize budgets
build data quality
```

Priority: P0.

---

## PR 2 — Refactor engines to accept normalized input

Change:

```text
InsightsEngine
CategoryInsightEngine
MerchantInsightEngine
MonthlyComparisonCalculator
SpendingPaceCalculator
DayOfWeekAnalyzer
AnomalyDetector
SpendingPersonalityClassifier
```

to consume:

```text
NormalizedAnalyticsInput
```

or typed normalized lists.

Priority: P0.

---

## PR 3 — Remove/guard raw `TotalsAggregationEngine`

Either:

```text
refactor it to normalized aggregation
```

or:

```text
deprecate and block production use
```

Add CI guard:

```text
No production code may call raw totals engine unless allowlisted.
```

Priority: P0.

---

## PR 4 — Use historical rates

Update `AnalyticsCurrencyNormalizer`:

```text
convertAsOf(expense.date)
```

Add `ConversionPolicy` for stale rates.

Priority: P0.

---

## PR 5 — Propagate data quality everywhere

Every analytics result should include:

```text
dataQuality
conversionWarnings
isPartial
excludedCount
sourceBuckets if relevant
```

Priority: P1.

---

## PR 6 — Fix period bucket generation

Replace `buildDailyTotals(expenses, days)` with:

```text
buildBuckets(expenses, start, end, granularity)
```

Use:

```text
TODAY → hourly
WEEK/MONTH → daily
QUARTER → weekly
YEAR/ALL → monthly
```

Priority: P1.

---

## PR 7 — Make personality classifier currency-safe

Inject normalizer/settings.

Use normalized spending and normalized budget statuses.

Priority: P1.

---

## PR 8 — Create analytics consistency tests

Add DB/fed scenario tests proving:

```text
basic analytics
advanced analytics
repository analytics
dashboard analytics
```

agree for the same dataset.

Priority: P1.

---

# 7. Tests to add

## `AnalyticsEngineConsistencyScenarioTest`

Seed:

```text
EUR purchase 50
USD purchase 10 with historical rate 0.90
GBP purchase 20 missing rate
EUR deposit 1000
EUR transfer 100
not-mine purchase 30
shared expense gross 100, my share 40
```

Assert:

```text
AnalyticsViewModel currentTotal = 99 EUR
AnalyticsRepository total = 99 EUR
AdvancedAnalytics category total sum = 99 EUR
dashboard monthly spend = 99 EUR
GBP warning appears everywhere
deposit/transfer/not-mine excluded from spend
shared expense uses effective amount
```

---

## `AnalyticsHistoricalRateStabilityTest`

Seed:

```text
2024 USD expense
2024 USD→EUR rate = 0.90
2026 USD→EUR rate = 0.70
```

Assert:

```text
2024 analytics uses 0.90
adding 2026 rate does not change 2024 report
```

---

## `AnalyticsBucketRangeContractTest`

For each period:

```text
TODAY
WEEK
MONTH
QUARTER
YEAR
ALL
CUSTOM
```

Assert:

```text
bucket start/end match selected period
bucket totals sum to period total
DST/leap day safe
```

---

## `SpendingPersonalityMultiCurrencyTest`

Seed:

```text
EUR + USD + missing GBP expenses
budget in EUR
```

Assert:

```text
classifier uses normalized amounts
missing GBP lowers confidence or creates warning
raw nominal GBP does not dominate classification
```

---

## `TotalsAggregationEngineGuardTest`

Assert:

```text
production code cannot use raw TotalsAggregationEngine on mixed-currency data
```

or if refactored:

```text
engine returns same normalized totals as AnalyticsRepository
```

---

## `CategoryHistorySoftDeleteAnalyticsTest`

Seed:

```text
category Groceries
expense in Groceries
delete/soft-delete category
```

Assert:

```text
historical analytics still display Groceries or stable historical label
not silently Uncategorized unless intended
```

---

## `AnalyticsPartialWarningPropagationTest`

Seed missing-rate transactions.

Assert warnings are present in:

```text
SpendingSummary
AnalyticsState
InsightsSnapshot
Advanced analytics results
dashboard widgets
forecast/weather inputs
```

---

# 8. Suggested canonical scenario

## `analytics_engine_consistency_multicurrency_contract`

Seed:

```text
home currency EUR

exchange rates:
  USD→EUR 0.90 valid on expense date
  GBP→EUR missing

expenses:
  groceries 50 EUR PURCHASE category Food
  amazon 10 USD PURCHASE category Shopping
  tesco 20 GBP PURCHASE category Food
  salary 1000 EUR DEPOSIT
  savings transfer 200 EUR TRANSFER OUTGOING
  friend dinner 100 EUR shared, myShareAmount = 40
  reimbursed/not-mine 30 EUR isNotMine = true

budget:
  Food 200 EUR
```

Run:

```text
AnalyticsInputAssembler
AnalyticsRepository
AnalyticsViewModel calculation
AdvancedAnalyticsEngine
InsightsEngine
Dashboard total consumer
```

Expected:

```text
spending total = 50 + 9 + 40 = 99 EUR
GBP excluded with warning
deposit excluded
transfer excluded from spending
not-mine excluded
category totals:
  Food = 50 EUR + missing GBP warning
  Shopping = 9 EUR
  Dining/shared = 40 EUR
daily buckets sum to 99
merchant totals sum to 99
advanced stats mean/median use normalized included data
personality classifier does not raw-sum GBP
budget Food status is partial because Food has missing GBP data
all engines report same total and same warning set
```

This should be the master fed-DB test for the analytical engines.

---

# 9. Most likely real instability sources

Ranked:

1. **Multiple analytics paths with no shared normalized input.**
2. **`TotalsAggregationEngine` raw-summing mixed currencies.**
3. **Current-rate conversion used for historical analytics.**
4. **`SpendingPersonalityClassifier` using raw snapshots.**
5. **Warnings dropped when outputs become plain `Double`.**
6. **Daily chart bucket generation tied to `now` instead of selected range.**
7. **Advanced/basic/repository analytics using different period/data fetch paths.**
8. **Budget comparisons using normalized spend against raw budget amounts.**
9. **Insufficient confidence/data-quality propagation.**
10. **Repeated DB reads and conversions causing performance and consistency issues.**

---

# 10. Final recommendation

Stabilize analytical engines in this order:

```text
1. Create `NormalizedAnalyticsInput`.
2. Use historical `convertAsOf()` in `AnalyticsCurrencyNormalizer`.
3. Refactor all engines to consume normalized input or typed normalized snapshots.
4. Remove/guard `TotalsAggregationEngine`.
5. Propagate `DataQualityReport` / `isPartial` / warnings through every analytics output.
6. Fix daily/weekly/monthly bucket generation to use exact selected ranges.
7. Make `SpendingPersonalityClassifier` currency-safe.
8. Add analytics consistency fed-DB scenario.
```

Guiding rule:

> No analytical engine should accept raw monetary amounts unless the type proves they are already normalized.

Second guiding rule:

> All analytical outputs must either be complete or visibly partial. A partial total must never look like a confident total.

---

# Sources

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `AnalyticsRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

- `AnalyticsViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

- `AdvancedAnalyticsEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt

- `AnalyticsCurrencyNormalizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt

- `TotalsAggregationEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt

- `InsightsEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt

- `AnomalyDetector.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnomalyDetector.kt

- `CategoryInsightEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt

- `MonthlyComparisonCalculator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt

- `SpendingPaceCalculator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt

- `DayOfWeekAnalyzer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt

- `MerchantInsightEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/MerchantInsightEngine.kt

- `SpendingPersonalityClassifier.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt

- Analytics tests directory:  
  https://github.com/panospao7/Cost-agregator/tree/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/analytics