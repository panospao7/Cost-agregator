# Analytics Engine Implementation Plan

Target areas:

```text
A01 A02 A04 A07 A08 A09 A10 A11 A12 A13 A15
```

Additional issues found:

```text
A16 AnalyticsViewModel duplicates normalization instead of using AnalyticsInputAssembler
A17 AnalyticsDataQuality lacks confidencePenalty / confidence propagation
A18 analytics engines still use Calendar/direct zone logic in places
A19 NormalizedAnalyticsInput does not preserve enough exclusion detail
A20 public analytics state still exposes raw Double totals without quality wrapper
```

---

# 0. Status correction

| ID | Real status |
|---|---|
| A01 | PARTIAL — `NormalizedAnalyticsInput` exists, but assembler is object/static and not fully consumed |
| A02 | PARTIAL — monthly/year/category improved; weekly/daily still raw DAO |
| A04 | OPEN — classifier still fetches raw snapshots itself; comment claiming safe is wrong |
| A07 | OPEN/PARTIAL — production caller passes currency, but engine still defaults to EUR |
| A08 | OPEN — `chartDays` + `buildDailyTotals(..., days)` still used |
| A09 | PARTIAL — ViewModel creates some explicit ranges, but engine can still derive ranges internally |
| A10 | PARTIAL — budget conversion exists in ViewModel, but should be canonical engine/model |
| A11 | OPEN — warnings exist but do not reduce confidence |
| A12 | OPEN — merchant anomaly/history needs dedicated 12-month lookback contract |
| A13 | OPEN — `SpendingPaceCalculator` uses `timeProvider.now()` unconditionally |
| A15 | DEFERRED unless category history matters now |

---

# PR-A0 — Tracker and TODO cleanup

Fix misleading comments first.

## Required cleanup

### `SpendingPersonalityClassifier`

Current comment says it is safe, but `classify()` fetches raw snapshots directly.

Replace with:

```kotlin
// A04 OPEN: This classifier currently queries raw ExpenseSnapshot data.
// It must consume NormalizedAnalyticsInput before monetary feature extraction.
```

### `InsightsEngine`

Replace hardcoded EUR comments with actionable status:

```kotlin
// A07 OPEN: default displayCurrency exists only for legacy callers.
// New callers must use generateInsights(input: NormalizedAnalyticsInput).
```

### `TotalsAggregationEngine`

Split A02 status:

```text
monthly/year/category = migrated
weekly/daily = still raw
```

Acceptance:

```text
tracker and comments no longer claim normalization that is not guaranteed.
```

---

# PR-A1 — Make `AnalyticsInputAssembler` production-ready

Current issues:

```text
object, not injectable
`isSharedExpense = false`
staleRateCount = 0
excludedExpenses lacks detailed warning source
not consumed by AnalyticsViewModel
```

## Implement

Convert:

```kotlin
object AnalyticsInputAssembler
```

to:

```kotlin
class AnalyticsInputAssembler @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val normalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val timeProvider: TimeProvider
)
```

API:

```kotlin
suspend fun build(
    period: PeriodRange,
    options: AnalyticsInputOptions = AnalyticsInputOptions()
): NormalizedAnalyticsInput
```

Add:

```kotlin
data class AnalyticsInputOptions(
    val spendingOnly: Boolean = true,
    val excludeNotMine: Boolean = true,
    val includeDepositsForBehavior: Boolean = false
)
```

Expand model:

```kotlin
data class NormalizedExpense(
    val id: Long,
    val originalAmount: Double,
    val originalEffectiveAmount: Double,
    val originalCurrency: String,
    val normalizedAmount: Double,
    val normalizedCurrency: String,
    val date: Long,
    val merchant: String,
    val merchantKey: String?,
    val categoryId: Long?,
    val categoryNameSnapshot: String?,
    val transactionType: DomainTransactionType,
    val isNotMine: Boolean,
    val isSharedExpense: Boolean,
    val ownershipMode: String?,
    val source: String?
)
```

Improve exclusions:

```kotlin
data class ExcludedExpense(
    val id: Long,
    val originalAmount: Double,
    val originalCurrency: String,
    val reason: ExclusionReason,
    val warningType: AnalyticsConversionWarningType?,
    val message: String?
)
```

Acceptance:

```text
No hardcoded EUR.
Shared/not-mine/effective semantics preserved.
Missing/stale/invalid currency counts are accurate.
```

Tests:

```text
AnalyticsInputAssemblerHomeCurrencyTest
AnalyticsInputAssemblerPreservesSharedExpenseTest
AnalyticsInputAssemblerMissingRateExclusionTest
AnalyticsInputAssemblerStaleRateCountTest
AnalyticsInputAssemblerSpendingOnlyFilterTest
```

---

# PR-A2 — Replace ViewModel manual normalization with assembler

Current `AnalyticsViewModel` manually does:

```text
currentNormalization
previousNormalization
allNormalization
yearOverYearNormalization
warningNormalization
```

This creates drift.

## Implement

Use assembler for:

```text
current period
previous period
all/history window
year-over-year window
```

Create helper:

```kotlin
data class AnalyticsPeriodInputs(
    val current: NormalizedAnalyticsInput,
    val previous: NormalizedAnalyticsInput,
    val history: NormalizedAnalyticsInput,
    val yearOverYear: NormalizedAnalyticsInput
)
```

Acceptance:

```text
currentTotal, categoryBreakdown, merchantBreakdown, dailyTotals, insights, budget-vs-actual all derive from canonical input.
```

Tests:

```text
AnalyticsViewModelUsesCanonicalInputTest
DashboardAnalyticsSamePeriodTotalTest
CategoryBreakdownSumsToCurrentTotalTest
MerchantBreakdownSumsToCurrentTotalTest
```

---

# PR-A3 — A02: finish TotalsAggregationEngine multi-currency safety

Current raw paths:

```text
getWeeklyTotals()
getDailyTotals()
getDailyTotalsForRange()
```

## Implement in `MultiCurrencyRepository`

```kotlin
suspend fun getHomeCurrencyWeeklyTotals(startMs, endMs): List<PeriodMoneyAggregate>
suspend fun getHomeCurrencyDailyTotals(startMs, endMs): List<PeriodMoneyAggregate>
```

Then update `TotalsAggregationEngine`.

Remove old raw DAO calls or guard them:

```kotlin
@Deprecated("Currency-unsafe; use home-currency aggregate")
```

Acceptance:

```text
weekly/daily totals use MoneyAggregate displayAmount and totalTransactionCount.
```

Tests:

```text
WeeklyTotalsMixedCurrencyConvertsTest
DailyTotalsMissingRatePartialTest
TotalsAggregationNoRawWeeklyDailyTest
```

---

# PR-A4 — A08: exact daily buckets by explicit range

Current:

```kotlin
val chartDays = ...
insightsEngine.buildDailyTotals(currentExpenseSnapshots, chartDays)
```

This is range-misaligned.

## Implement

Create:

```kotlin
DailyBucketEngine.build(
    input: NormalizedAnalyticsInput,
    granularity: BucketGranularity
): Map<Long, Double>
```

Granularity:

```text
TODAY/WEEK/MONTH → DAY
QUARTER/YEAR → WEEK or MONTH
ALL → MONTH
```

Use `period.startInclusiveMillis` and `period.endExclusiveMillis`.

Acceptance:

```text
bucket sum equals current normalized total.
buckets exactly cover selected period.
no “last N days from now” logic.
```

Tests:

```text
DailyBucketsExactRangeTest
DailyBucketsSumToTotalTest
MonthBucketNoFutureDaysTest
QuarterUsesWeekOrMonthBucketsTest
```

---

# PR-A5 — A04: make SpendingPersonalityClassifier normalized-input only

Current bug:

```text
classify() queries ExpenseRepository directly and uses raw effectiveAmount.
```

## Implement

New API:

```kotlin
suspend fun classify(input: NormalizedAnalyticsInput): SpendingPersonalityProfile
```

Remove repository dependency from classifier, or keep only for deprecated path:

```kotlin
@Deprecated("Use classify(NormalizedAnalyticsInput)")
suspend fun classify()
```

Use:

```text
input.includedExpenses mapped to feature model
dataQuality reduces confidence
```

Budget adherence must use normalized budget snapshots too.

Acceptance:

```text
Classifier cannot silently raw-sum mixed currencies.
Partial input lowers confidence or returns partial badge.
```

Tests:

```text
SpendingPersonalityUsesNormalizedInputTest
SpendingPersonalityMissingRateLowersConfidenceTest
SpendingPersonalityNoRepositoryQueryTest
```

---

# PR-A6 — A07: InsightsEngine requires normalized input

Current:

```kotlin
generateInsights(... displayCurrency = "EUR")
```

## Implement

Add:

```kotlin
suspend fun generateInsights(
    input: NormalizedAnalyticsInput,
    categories: List<AnalyticsCategoryRef>
): InsightsSnapshot
```

Remove/Deprecate default currency overloads.

Internally pass:

```text
input.homeCurrency
input.dataQuality.conversionWarnings
```

Acceptance:

```text
new production path cannot default to EUR.
```

Tests:

```text
InsightsEngineNoDefaultCurrencyPathTest
InsightsEngineUsesInputHomeCurrencyTest
InsightsEnginePartialWarningsPropagateTest
```

---

# PR-A7 — A09/A12: explicit advanced analytics period and history windows

## A09

Make `AdvancedAnalyticsEngine` only accept:

```kotlin
AnalyticsPeriodRange
```

No internal recalculation for production methods.

```kotlin
getCategoryAnalytics(range, input)
getMerchantAnalytics(range, input, lookbackRange)
getSpendingPatterns(range, input)
getStatisticalInsights(range, input)
```

## A12

Merchant anomaly/history should fetch/receive:

```text
current range
12-month lookback range
```

not just chart/current period.

Acceptance:

```text
merchant anomaly uses 12-month lookback even when selected period is WEEK/MONTH.
```

Tests:

```text
AdvancedAnalyticsUsesViewModelRangeTest
MerchantAnomalyUsesTwelveMonthLookbackTest
MerchantHistoryIndependentOfChartPeriodTest
```

---

# PR-A8 — A10: budget-vs-actual normalized contract

Current ViewModel converts budget amount ad hoc.

Move to domain:

```kotlin
BudgetVsActualAnalyticsEngine.compute(
    actuals: NormalizedAnalyticsInput,
    budgets: List<BudgetSnapshot>,
    homeCurrency: String
): BudgetVsActualResult
```

Normalize budget limits via `CurrencyConverter.convertAsOf` or current-rate policy.

Return:

```kotlin
data class BudgetVsActualResult(
    val items: List<BudgetVsActualItem>,
    val dataQuality: AnalyticsDataQuality
)
```

Acceptance:

```text
actuals and budget limits are in same currency.
conversion failure creates partial warning.
```

Tests:

```text
BudgetVsActualBudgetCurrencyConvertedTest
BudgetVsActualMissingBudgetRateWarningTest
BudgetVsActualActualAndBudgetSameCurrencyTest
```

---

# PR-A9 — A11/A17: confidence penalty and data-quality propagation

Extend:

```kotlin
data class AnalyticsDataQuality(
    ...
    val confidencePenalty: Double = 0.0,
    val confidenceMultiplier: Double = 1.0
)
```

Policy:

```text
missingRateCount > 0 → penalty
staleRateCount > 0 → smaller penalty
excludedCount / totalCount ratio scales penalty
```

Apply to:

```text
spending personality confidence
anomaly severity/confidence
insight confidence
advanced statistical insights
forecast handoff
```

Acceptance:

```text
partial data never shows max confidence.
```

Tests:

```text
AnalyticsDataQualityConfidencePenaltyTest
PersonalityConfidenceReducedByPartialDataTest
AnomalyConfidenceReducedByPartialDataTest
```

---

# PR-A10 — A13: spending pace referenceNow

Current:

```kotlin
val now = timeProvider.now()
```

inside `SpendingPaceCalculator`.

## Implement

```kotlin
fun calculate(
    currentMonthStart: Long,
    currentMonthEnd: Long,
    previousMonthStart: Long,
    previousMonthEnd: Long,
    allExpenses: List<NormalizedExpense>,
    referenceNowMs: Long = currentMonthEnd
)
```

Rule:

```text
if period includes current date → referenceNow = timeProvider.now()
else referenceNow = period.endExclusiveMs
```

Acceptance:

```text
historical month pace uses that month’s end, not today.
```

Tests:

```text
SpendingPaceHistoricalMonthUsesPeriodEndTest
SpendingPaceCurrentMonthUsesNowTest
```

---

# PR-A11 — A15 category deletion/history

If you implement now:

## Option A — soft delete categories

Add:

```text
Category.isDeleted
Category.deletedAt
```

Queries exclude deleted by default but analytics can resolve historical names.

## Option B — persist category name snapshot on expense

Add:

```text
expense.categoryNameSnapshot
```

when category assigned/changed.

Recommended short-term:

```text
snapshot category name/icon/color into expense analytics projection
```

Acceptance:

```text
deleted category does not collapse into Uncategorized for historical analytics.
```

Tests:

```text
DeletedCategoryHistoricalNamePreservedTest
CategoryInsightMissingIdDoesNotMergeAllDeletedCategoriesTest
```

Can remain deferred if not release-critical.

---

# Additional issues found

## A16 — ViewModel owns too much analytics logic

Move these to engines:

```text
year-over-year
velocity anomalies
post-salary pattern
suspect transactions
day/hour pattern
budget-vs-actual
```

Create:

```kotlin
AnalyticsOrchestrator
```

The ViewModel should select period and render state, not compute analytics.

## A18 — direct Calendar/time-zone logic

Replace `Calendar.getInstance()` in analytics engines with:

```text
TimePeriodUtils / java.time + app ZoneId
```

Tests:

```text
AnalyticsDstBoundaryTest
DayOfWeekUsesAppZoneTest
```

## A20 — public analytics outputs use raw Double totals

This is acceptable at UI model edge only if paired with:

```text
displayCurrency
dataQuality
```

For domain outputs prefer:

```text
MoneyAmount / MoneyAggregate
```

---

# Recommended execution order

```text
1. PR-A0 tracker/comment cleanup
2. PR-A1 AnalyticsInputAssembler production version
3. PR-A2 AnalyticsViewModel consumes assembler
4. PR-A3 weekly/daily MoneyAggregate totals
5. PR-A4 explicit daily buckets
6. PR-A5 SpendingPersonality normalized input
7. PR-A6 InsightsEngine normalized input
8. PR-A7 Advanced analytics explicit range + 12-month merchant lookback
9. PR-A8 Budget-vs-actual engine
10. PR-A9 confidencePenalty propagation
11. PR-A10 SpendingPace referenceNow
12. PR-A11 category history if not deferred
13. A16/A18 cleanup/orchestrator pass
```

---

# Definition of done

Analytics engines are stable when:

```text
1. One canonical NormalizedAnalyticsInput feeds all analytics.
2. No analytics engine queries raw expenses and then sums mixed-currency amounts.
3. Weekly/daily/monthly/category/merchant totals are currency-safe.
4. Daily chart buckets exactly match selected period.
5. Personality/insights/advanced analytics consume normalized input.
6. Partial conversion warnings reduce confidence.
7. Budget-vs-actual compares normalized budget and actual values.
8. Historical periods do not use today as reference date.
9. Deleted categories remain historically understandable or are explicitly deferred.
10. Tests prove dashboard/analytics/assistant totals agree for same period.
```

---

# Sources checked

- `NormalizedAnalyticsInput.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt

- `AnalyticsInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt

- `TotalsAggregationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt

- `SpendingPersonalityClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt

- `InsightsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt

- `AnalyticsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

- `SpendingPaceCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt

- `CategoryInsightEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt

- `MerchantInsightEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/1a480de669fab41d6fde8123f76231b78638d09b/app/src/main/java/com/yourname/expensetracker/domain/analytics/MerchantInsightEngine.kt