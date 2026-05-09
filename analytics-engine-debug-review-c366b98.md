# Analytics Engine Debug Review — through `c366b9846502d6ae4d675ff4452ce7430d52dde8`

Reviewed commits:

```text
735ffe3bb8ca7c16842f38286e37c61e002d7384
d3d3102476d4149f91046e4d1a54c7315737c68f
40af2b22902cd2f23d12ba8239ba81473c59e74a
0c567c9e88c875475db2b0cdf583bef3490f53be
95c8287f685a6b135ba0d9e340f9dce5d1fc2227
c366b9846502d6ae4d675ff4452ce7430d52dde8
```

Review type: static GitHub/code review, not local Gradle execution.

---

# 1. Executive verdict

The commits are a **real improvement**. They establish the right architecture:

```text
✅ AnalyticsInputAssembler exists and is injectable.
✅ NormalizedAnalyticsInput exists and is richer.
✅ AnalyticsViewModel now uses AnalyticsInputAssembler for current/previous/all/YoY inputs.
✅ AnalyticsCurrencyNormalizer uses convertAsOf(expense.date).
✅ InsightsEngine has a NormalizedAnalyticsInput overload.
✅ SpendingPersonalityClassifier has a NormalizedAnalyticsInput overload.
✅ BudgetVsActualEngine exists.
✅ DailyBucketEngine exists.
✅ MultiCurrencyRepository has weekly/daily MoneyAggregate helper methods.
✅ confidencePenalty/confidenceMultiplier exist.
```

But:

> The analytical engines are **not fully finalized/stable yet**.

Main reasons:

```text
1. TotalsAggregationEngine weekly/daily paths still use raw DAO totals.
2. DailyBucketEngine has a likely half-open range off-by-one bug and is not wired into AnalyticsViewModel.
3. SpendingPersonalityClassifier.classify(input) currently returns BALANCED always and loses the real classifier logic.
4. AdvancedAnalyticsEngine still re-queries and normalizes internally instead of consuming NormalizedAnalyticsInput.
5. AdvancedAnalyticsEngine category budget analytics still compares normalized spending to raw budget amounts.
6. Merchant anomaly/history uses 6-month lookback, not the requested 12-month contract.
7. Spending pace referenceNow exists but InsightsEngine does not pass it.
8. Category name snapshot is not truly historical; it is resolved from current CategoryRepository.
9. Calendar/direct time logic remains in several analytics engines.
10. Tracker still lists analytics items as TODO, and several comments are stale/over-optimistic.
```

Current analytics status:

```text
Analytics foundation: good
Core current-period totals in AnalyticsViewModel: much safer
Advanced analytics engines: partial
Production-stable: not yet
Beta-stable: yes, if warnings/known gaps are accepted
```

---

# 2. Commit evaluation

## 2.1 `735ffe3` — AnalyticsInputAssembler

Good:

```text
AnalyticsInputAssembler changed from object to @Inject @Singleton class.
homeCurrency comes from CurrencySettingsRepository.
AnalyticsInputOptions added.
NormalizedExpense expanded.
AnalyticsDataQuality gained confidence fields.
```

Remaining problems:

```text
homeCurrency fallback silently defaults to EUR if settings read fails
isSharedExpense = false
ownershipMode = null
source = null
staleRateCount = 0
excludedExpenses only covers conversion failures after pre-filtering
includeDepositsForBehavior option is effectively not used when spendingOnly=true
```

So A01 is **partial/foundation**, not fully done.

---

## 2.2 `d3d3102` — personality/insights/budget/pace

Good:

```text
SpendingPersonalityClassifier.classify(NormalizedAnalyticsInput) exists.
InsightsEngine.generateInsights(NormalizedAnalyticsInput, categories) exists.
BudgetVsActualEngine exists.
SpendingPaceCalculator.calculate() has referenceNowMs.
confidence fields are consumed by personality classifier.
```

Remaining problems:

### Personality regression

`classify(input)` computes some features, but returns:

```kotlin
personalityType = SpendingPersonalityType.BALANCED
explanation = emptyList()
coachingTips = emptyList()
```

So it no longer performs the real `determinePersonalityType()` logic.

Also it omits earlier feature dimensions:

```text
impulseRatio
budgetAdherence
anomalyFrequency
transactionsPerMonth
real confidence calculation
```

Status:

```text
A04 is safer for currency, but feature quality regressed.
```

### Insights partial migration

`generateInsights(input)` maps normalized expenses back to snapshots and calls legacy internals. This is acceptable short-term, but old legacy overloads with `displayCurrency = "EUR"` still exist.

Status:

```text
A07 partial.
```

### Budget-vs-actual partial

`BudgetVsActualEngine` assumes budgets are already converted by ViewModel. It does not normalize budget snapshots itself.

Status:

```text
A10 partial.
```

---

## 2.3 `40af2b2` — DailyBucketEngine

Good:

```text
DailyBucketEngine introduced.
Uses explicit PeriodRange.
Uses NormalizedAnalyticsInput.
No “last N days from now” API in this engine.
```

Problems:

### Off-by-one with half-open end

Current shape:

```kotlin
val days = ChronoUnit.DAYS.between(startDate, endDate).toInt()
return (0..days).map { ... }
```

For a half-open range:

```text
[2026-05-01 00:00, 2026-05-08 00:00)
```

this returns 8 buckets instead of 7 because `0..days` is inclusive.

Correct approach:

```kotlin
generateSequence(startLocalDate) { it.plusDays(1) }
    .takeWhile { it.atStartOfDay(zone).toInstant().toEpochMilli() < period.endExclusiveMillis }
```

or:

```kotlin
0 until days
```

with special handling if `endExclusive` is not at midnight and you want the partial current day included.

### Not wired

I did not see `DailyBucketEngine` used by `AnalyticsViewModel`.

Status:

```text
A08 partial, not complete.
```

---

## 2.4 `0c567c9` — MultiCurrencyRepository weekly/daily aggregates

Good:

```text
getHomeCurrencyWeeklyTotals()
getHomeCurrencyDailyTotals()
PeriodMoneyAggregate added.
```

Problems:

### Not integrated into TotalsAggregationEngine

`TotalsAggregationEngine.getWeeklyTotals()` still calls:

```kotlin
expenseRepository.getWeeklyTotalsForPeriod(...)
```

`getDailyTotals()` and `getDailyTotalsForRange()` still call:

```kotlin
expenseRepository.getDailyTotalsWithDatesForPeriod(...)
```

So A02 remains open.

### Helper methods are not spending-only

`getHomeCurrencyWeeklyTotals()` and `getHomeCurrencyDailyTotals()` call:

```kotlin
expenseDao.getExpensesBetweenUncapped(...)
```

then group all expenses. That likely includes:

```text
deposits
transfers
withdrawals
not-mine rows
```

unless DAO already filters, which the method name suggests it does not.

Analytics spending totals should be purchase/spending-only and exclude not-mine.

### Helper methods aggregate by currency then use current conversion

They group into buckets:

```kotlin
Pair(effectiveAmount, currency)
```

and call:

```kotlin
MoneyAggregateBuilder.fromBuckets(...)
```

That builder uses `convertMultiple`, not `convertAsOf(expense.date)`. For historical weekly/daily analytics this can still use current/latest rates.

Status:

```text
A02 helper foundation exists, but integration and historical-rate policy remain incomplete.
```

---

## 2.5 `95c8287` — AnalyticsViewModel wired to assembler

Good:

```text
ViewModel uses AnalyticsInputAssembler for current/previous/all/yoy.
duplicate normalizer calls reduced.
warnings collected from dataQuality.
currentExpenseSnapshots now come from NormalizedAnalyticsInput.
```

Remaining problems:

```text
ViewModel still manually computes totals/category/merchant/daily/year-over-year/day-hour/suspect patterns.
AdvancedAnalyticsEngine is still called separately and re-queries the repository.
DailyBucketEngine is not used.
Location analytics uses normalized amount but old LocatedExpense model.
Budget conversion still happens in ViewModel before BudgetVsActualEngine.
```

Status:

```text
A16 improved, not fully extracted/orchestrated.
```

---

## 2.6 `c366b98` — category snapshot, budget wiring, Calendar notes

Good:

```text
categoryNameSnapshot populated from CategoryRepository.
BudgetVsActualEngine injected and used by ViewModel.
AnalyticsViewModel uses classify(currentInput).
Some Calendar sites are documented.
```

Remaining problems:

### Category snapshot is not truly historical

`categoryNameSnapshot` is resolved from:

```kotlin
CategoryRepository.getAll()
```

at analytics time.

If a category was deleted, it is missing. If renamed, it uses the new name, not the historical name.

So A15 is not fixed; it only helps when category still exists.

### Calendar/direct time remains

Examples remain in:

```text
DayOfWeekAnalyzer
AdvancedAnalyticsEngine calendarDayToIndex/TimePeriodUtils paths
SpendingPersonality legacy no-arg classify
AnalyticsViewModel day-of-week inline logic
```

Comments were added, but code remains.

Status:

```text
A18 documented, not fully fixed.
```

---

# 3. A-item status after review

| ID | Status now | Notes |
|---|---|---|
| A01 | PARTIAL | Canonical input exists, but not all engines consume it and metadata incomplete. |
| A02 | OPEN/PARTIAL | MCR helpers added, but TotalsAggregationEngine weekly/daily still raw. |
| A04 | PARTIAL | Normalized overload exists, but classifier returns BALANCED always and legacy raw path remains. |
| A07 | PARTIAL | Normalized Insights overload exists; legacy EUR-default paths remain. |
| A08 | PARTIAL | DailyBucketEngine exists, but off-by-one risk and not wired. |
| A09 | PARTIAL | AdvancedAnalyticsEngine accepts range, but ViewModel still uses `getPeriodRange()` for week and engine re-queries. |
| A10 | PARTIAL | BudgetVsActualEngine exists; AdvancedAnalyticsEngine category budget still raw. |
| A11 | PARTIAL | confidencePenalty exists; only personality uses it meaningfully. |
| A12 | OPEN | Merchant analytics uses 6-month lookback, not 12-month contract. |
| A13 | PARTIAL | referenceNowMs exists; InsightsEngine does not pass it. |
| A15 | OPEN/DEFER | Current category lookup is not historical snapshot. |
| A16 | PARTIAL | ViewModel improved, but many computations remain there. |
| A18 | OPEN/PARTIAL | Calendar/time-zone cleanup mostly comments, not code. |
| A20 | PARTIAL | Public outputs still mostly raw Double + displayCurrency, not MoneyAggregate/dataQuality. |

---

# 4. New/regression issues found

## AN-REG-1 — DailyBucketEngine half-open range off-by-one

For endExclusive exactly at midnight, it emits one extra bucket.

Priority: P1.

## AN-REG-2 — SpendingPersonality normalized path loses actual classification

The new currency-safe path returns `BALANCED` unconditionally.

Priority: P1.

## AN-REG-3 — MultiCurrencyRepository weekly/daily helper is unsafe if used as spending analytics

It includes all transaction types and likely uses current conversion rates.

Priority: P1 before wiring into `TotalsAggregationEngine`.

## AN-REG-4 — Advanced analytics still duplicates normalization and repository access

This undermines the “canonical input” contract.

Priority: P1/P2.

## AN-REG-5 — `InsightsEngine.generateInsights(input)` does not pass `referenceNowMs`

`SpendingPaceCalculator` supports the fix, but `InsightsEngine` still calls it without the new argument.

Priority: P1.

## AN-REG-6 — debug logs hardcode `€`

In `AdvancedAnalyticsEngine.getStatisticalInsights()` debug output still logs:

```text
Total: €...
Average Daily: €...
```

Low impact, but inconsistent with multi-currency work.

Priority: P3.

---

# 5. Stability assessment

Current ratings:

| Area | Rating | Verdict |
|---|---:|---|
| AnalyticsCurrencyNormalizer | 8.5/10 | good, uses convertAsOf |
| AnalyticsInputAssembler | 7/10 | good foundation, metadata gaps |
| AnalyticsViewModel core totals | 7.5/10 | safer but still too much logic |
| TotalsAggregationEngine | 5.5/10 | weekly/daily still raw |
| DailyBucketEngine | 6/10 | exists but not wired and off-by-one |
| SpendingPersonality | 5.5/10 | currency-safe path but behavior regressed |
| InsightsEngine | 7/10 | normalized overload exists, legacy remains |
| AdvancedAnalyticsEngine | 6/10 | self-normalizes, not canonical |
| BudgetVsActual | 7/10 | domain engine exists, budget normalization external |
| Merchant anomaly/history | 5.5/10 | 6-month lookback, not 12-month contract |

Overall:

```text
Analytical engines are improved but not production-stable yet.
```

I would call them:

```text
beta-stable foundation
```

not:

```text
finalized/stable
```

---

# 6. What I recommend before moving forward

Do one focused analytics-finalization PR.

## PR-AN-FINAL

### 1. Fix DailyBucketEngine

```text
use while bucketStart < endExclusive
handle partial current day explicitly
wire it into AnalyticsViewModel dailyTotals
```

Tests:

```text
DailyBucketsEndAtMidnightNoExtraBucketTest
DailyBucketsPartialEndIncludesCurrentDayTest
DailyBucketsSumToInputTotalTest
```

### 2. Finish TotalsAggregationEngine weekly/daily

Either:

```text
use AnalyticsInputAssembler/DailyBucketEngine
```

or add purchase-only historical-rate MCR methods.

Do not wire current `getHomeCurrencyWeeklyTotals()` as-is unless it filters purchase-only and uses historical conversion.

Tests:

```text
WeeklyTotalsMixedCurrencyUsesHistoricalRatesTest
DailyTotalsExcludesDepositsTransfersTest
DailyTotalsExcludesNotMineTest
```

### 3. Restore SpendingPersonality real classification

`classify(input)` should compute:

```text
impulseRatio
merchantDiversity
weekendSpendShare
nightSpendShare
variance
budgetAdherence or neutral if unavailable
anomalyFrequency or neutral if unavailable
categoryDiversity
transactionsPerMonth
avgTransactionSize
```

Then call:

```kotlin
determinePersonalityType(featureScores)
generateExplanation(...)
generateCoachingTips(...)
calculateConfidence(...)*dataQuality
```

Tests:

```text
SpendingPersonalityPlannerFromNormalizedInputTest
SpendingPersonalityImpulseFromNormalizedInputTest
SpendingPersonalityPartialDataLowersConfidenceTest
```

### 4. Make advanced analytics consume canonical input or mark as legacy

At minimum add overloads:

```kotlin
getCategoryAnalytics(input, previousInput, budgets)
getMerchantAnalytics(currentInput, lookbackInput)
getSpendingPatterns(input)
getStatisticalInsights(input)
```

Then call these from ViewModel.

Tests:

```text
AdvancedCategoryUsesNormalizedInputTest
AdvancedMerchantUsesLookbackInputTest
AdvancedStatsNoRepositoryQueryTest
```

### 5. Fix A10 in AdvancedAnalyticsEngine

`getCategoryAnalytics()` still compares normalized spend to raw budget.

Use `BudgetVsActualEngine` or normalized budget snapshots.

Test:

```text
AdvancedCategoryBudgetCurrencyConvertedTest
```

### 6. Fix A12

Use explicit 12-month lookback:

```text
lookbackStart = period.startMs minus 12 months
```

independent of chart/current period.

Test:

```text
MerchantAnalyticsUsesTwelveMonthLookbackTest
```

### 7. Fix A13 call site

In `InsightsEngine.buildSpendingPace()` pass:

```kotlin
referenceNowMs =
    if (currentMonth.endMs <= timeProvider.now()) currentMonth.endMs
    else timeProvider.now()
```

or use actual `input.period.endExclusiveMillis`.

Test:

```text
SpendingPaceHistoricalPeriodUsesPeriodEndTest
```

### 8. Tracker reconciliation

Promote only truly fixed:

```text
A01 PARTIAL
A02 PARTIAL/OPEN
A04 PARTIAL
A07 PARTIAL
A08 PARTIAL
A09 PARTIAL
A10 PARTIAL
A11 PARTIAL
A12 OPEN
A13 PARTIAL
A15 DEFERRED
A16 PARTIAL
```

Do not mark analytics section as finalized until PR above is done.

---

# 7. Can you move forward?

My answer:

```text
Not yet, if the goal is “analytics engines stable.”
```

You can move forward only if you accept:

```text
analytics = beta/partial
```

But if the plan is to finish engines before UI, I would do **one more analytics pass** first.

The smallest blocker set:

```text
1. DailyBucketEngine off-by-one + wire into ViewModel.
2. TotalsAggregationEngine weekly/daily raw paths.
3. SpendingPersonality normalized path returning BALANCED always.
4. Advanced category budget raw comparison.
5. Spending pace referenceNow call site.
6. Merchant 12-month lookback.
```

After those, I’d be comfortable saying:

```text
Analytical engines are stable enough to move to categorization/groups/investment/tax or UI integration.
```

---

# Sources

Commits:

- https://github.com/panospao7/Cost-agregator/commit/735ffe3bb8ca7c16842f38286e37c61e002d7384
- https://github.com/panospao7/Cost-agregator/commit/d3d3102476d4149f91046e4d1a54c7315737c68f
- https://github.com/panospao7/Cost-agregator/commit/40af2b22902cd2f23d12ba8239ba81473c59e74a
- https://github.com/panospao7/Cost-agregator/commit/0c567c9e88c875475db2b0cdf583bef3490f53be
- https://github.com/panospao7/Cost-agregator/commit/95c8287f685a6b135ba0d9e340f9dce5d1fc2227
- https://github.com/panospao7/Cost-agregator/commit/c366b9846502d6ae4d675ff4452ce7430d52dde8

Key files:

- `AnalyticsInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt

- `NormalizedAnalyticsInput.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt

- `AnalyticsCurrencyNormalizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt

- `TotalsAggregationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt

- `DailyBucketEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/DailyBucketEngine.kt

- `SpendingPersonalityClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt

- `InsightsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt

- `AdvancedAnalyticsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt

- `SpendingPaceCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt

- `BudgetVsActualEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/domain/analytics/BudgetVsActualEngine.kt

- `MultiCurrencyRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt

- `AnalyticsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

- Tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c366b9846502d6ae4d675ff4452ce7430d52dde8/docs/analyses%20and%20debug%20master/ENGINE_ISSUES_MASTER_TRACKER.md