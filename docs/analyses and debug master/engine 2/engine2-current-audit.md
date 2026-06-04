# Engine 2 Current Audit — Analytical Engines

Target branch inspected: `fix/pipeline-1-5-local-issues`  
Mode: static GitHub inspection only.  
No Gradle, compile, KSP, Hilt, Room, lint, or tests were run.

## Self-review verdict

**YELLOW — improved, but not fully clean**

Engine 2 is in a much better state than the old report. The core analytics path now has:

- `AnalyticsInputAssembler`
- `NormalizedAnalyticsInput`
- `AnalyticsCurrencyNormalizer`
- `DailyBucketEngine`
- `BudgetVsActualEngine`
- historical-rate `MultiCurrencyRepository` APIs
- visible conversion warnings in `AnalyticsViewModel`
- many analytics tests

But the engine stack is **not clean yet** because adoption is inconsistent. The main problem is no longer “missing infrastructure”; it is **mixed old/new paths**.

The biggest remaining risks:

1. `AnalyticsViewModel` still calls legacy `InsightsEngine.generateInsights(categories, allExpenses...)`, which computes current-month insights from `timeProvider.now()`, not necessarily the selected period.
2. `AdvancedAnalyticsEngine.getSpendingPatterns()` and `getStatisticalInsights()` still self-fetch data instead of consuming `NormalizedAnalyticsInput`.
3. `BudgetVsActualEngine` exists, but budget limit conversion in the ViewModel still uses latest rate, not explicit period basis.
4. Category name snapshots are still derived from current categories, not persisted historical category names.
5. Several raw/deprecated analytics APIs remain callable.
6. `AnalyticsRepository.SpendingSummary.aggregate` is still `null` even though the model says it carries an aggregate.
7. `ExcludedExpense.warningType/message` exist but are not populated by `AnalyticsInputAssembler`.
8. `NormalizedExpense` has rate provenance fields, but the normalizer/assembler does not populate them.

---

# Sources inspected

Architecture:

- `ENGINE_INTERACTION_MAP.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/docs/architecture/ENGINE_INTERACTION_MAP.md
- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/docs/architecture/CODEBASE_SEGMENTS.md

Core analytics:

- `AnalyticsInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt
- `NormalizedAnalyticsInput.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt
- `AnalyticsCurrencyNormalizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt
- `AnalyticsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt
- `MultiCurrencyRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/data/repository/MultiCurrencyRepository.kt
- `TotalsAggregationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt
- `DailyBucketEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/DailyBucketEngine.kt
- `AdvancedAnalyticsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt
- `InsightsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt
- `SpendingPersonalityClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt
- `BudgetVsActualEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/analytics/BudgetVsActualEngine.kt
- `AnalyticsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

Location analytics:

- `AreaSpendingEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt
- `TravelDetectionEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt
- `LocationInsightsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/main/java/com/yourname/expensetracker/domain/location/LocationInsightsEngine.kt

Tests:

- analytics test directory listing  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/analytics
- analytics UI test directory listing  
  https://github.com/panospao7/Cost-agregator/tree/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/ui/screens/analytics
- `AnalyticsCurrencyNormalizerTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizerTest.kt
- `TotalsAggregationEngineDeepTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/fix/pipeline-1-5-local-issues/app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineDeepTest.kt

---

# 1. Engine scout

## Engine

Engine 2 — Analytical Engines.

Main components:

- `AnalyticsInputAssembler`
- `AnalyticsCurrencyNormalizer`
- `NormalizedAnalyticsInput`
- `AnalyticsRepository`
- `MultiCurrencyRepository`
- `TotalsAggregationEngine`
- `DailyBucketEngine`
- `AdvancedAnalyticsEngine`
- `InsightsEngine`
- `SpendingPersonalityClassifier`
- `BudgetVsActualEngine`
- analytics-related location engines:
  - `LocationInsightsEngine`
  - `AreaSpendingEngine`
  - `TravelDetectionEngine`
- UI consumer:
  - `AnalyticsViewModel`

## Risk level

**High**

Reason: analytics touches dashboard, budget, forecast, location insights, recurring detection display, merchant/category insights, and data-quality UI.

From `ENGINE_INTERACTION_MAP.md`:

- `AnalyticsInputAssembler` affects Dashboard, Budget/Forecast, Analytics.
- `DailyBucketEngine` affects Dashboard, Budget, Analytics.
- `BudgetVsActualEngine` affects Dashboard, Budget, Analytics.
- `MoneyAggregate/Builder` affects Dashboard, Budget, Export, Analytics.

## Affected pipelines

| Pipeline / segment | Impact |
|---|---|
| Segment 8 — Analytics & Insights | primary |
| Segment 10 — Dashboard Totals & Widgets | totals/drilldowns |
| Segment 2 — Budget Management | budget-vs-actual |
| Segment 1 — Forecasting & Runway | quality/confidence inputs |
| Segment 13 — Cash Flow Planning | if analytics summaries feed projections |
| Segment 19 — Location Enrichment | area/travel/place analytics |
| Segment 7 — Recurring Expenses | recurring candidates surfaced in analytics |
| Segment 16 — Currency & Exchange | all normalization/rate-basis behavior |

## Schema/migration impact

No direct schema impact for the current audit.

Potential future schema impact only if you decide to persist:

- category name snapshots on expenses
- analytics run snapshots
- diagnostic events per analytics run
- historical rate provenance per normalized row

## Hilt/DI impact

Current inspected classes are injectable.

Potential future DI impact:

- if `AdvancedAnalyticsEngine` stops self-fetching and consumes only inputs, constructor dependencies can be reduced
- if static guards are added, no Hilt impact
- if budget conversion moves into a dedicated engine/service, possible new binding

---

# 2. Positive findings

## 2.1 Canonical input exists

`AnalyticsInputAssembler` now builds `NormalizedAnalyticsInput` using:

- expenses fetched by period
- home currency from `CurrencySettingsRepository`
- category snapshot lookup
- `AnalyticsCurrencyNormalizer`
- included/excluded rows
- `AnalyticsDataQuality`

This is the right direction.

## 2.2 Home currency no longer silently defaults to EUR in the assembler

`AnalyticsInputAssembler.build(period)` now reads:

```kotlin
currencySettingsRepository.homeCurrency().first()
```

and does not silently fallback to `"EUR"`.

This is better than earlier versions.

## 2.3 Normalizer uses transaction-date conversion

`AnalyticsCurrencyNormalizer` uses:

```kotlin
RateBasis.TRANSACTION_DATE
```

and explicitly avoids latest-rate fallback for historical analytics.

This is a major improvement.

## 2.4 TotalsAggregationEngine is much better

`TotalsAggregationEngine` now documents and uses historical APIs:

- `getMonthlyAggregatesHistorical`
- `getWeeklyAggregatesHistorical`
- `getDailyAggregatesHistorical`
- `getHomeCurrencyPurchaseTotalHistoricalResult`
- `getCategoryAggregatesHistorical`

It also propagates `isPartial` and `warningMessage` into `PeriodTotal`.

## 2.5 AnalyticsRepository headline summary improved

`AnalyticsRepository.getSpendingSummary()` now derives headline total and daily history from the same normalized expenses instead of midpoint/latest-rate mixed logic.

That closes one of the old highest-risk issues.

## 2.6 DailyBucketEngine is clean and simple

`DailyBucketEngine` consumes normalized input and exact period boundaries. This is a good isolated engine.

## 2.7 Tests exist

The analytics test directory is populated with many tests:

- `AdvancedAnalyticsEngineDeepTest`
- `AnalyticsCurrencyNormalizerTest`
- `InsightsEngineDeepTest`
- `SpendingPersonalityClassifierTest`
- `TotalsAggregationEngineDeepTest`
- etc.

This is much better than Engine 1’s coverage state.

---

# 3. Old issue reconciliation

## A01 — No canonical analytics input contract

Old tracker: `FIXED`  
Current status: **PARTIAL / mostly implemented**

Evidence:

- `NormalizedAnalyticsInput` exists.
- `AnalyticsInputAssembler` exists.
- `AnalyticsViewModel` uses assembler for current, previous, all, and YOY windows.

Remaining gaps:

- `AdvancedAnalyticsEngine.getSpendingPatterns()` still self-fetches.
- `AdvancedAnalyticsEngine.getStatisticalInsights()` still self-fetches.
- `InsightsEngine` normalized overload exists, but `AnalyticsViewModel` still calls the legacy overload.
- `categoryNameSnapshot` is derived from current categories, not persisted historical category names.
- `isSharedExpense`, `ownershipMode`, and `source` are still placeholders/null in assembler output.

Decision: **downgrade to partial**

---

## A02 — TotalsAggregationEngine unsafe multi-currency

Old tracker: `FIXED`  
Current status: **MOSTLY FIXED**

Evidence:

- `TotalsAggregationEngine` now uses historical `MultiCurrencyRepository` APIs.
- `PeriodTotal` gets partial/warning fields.
- Tests exist around totals behavior.

Remaining gaps:

- Some `MultiCurrencyRepository` latest-rate APIs remain public.
- Some old methods are `DeprecationLevel.ERROR`, which is good, but not all latest-rate APIs are blocked.
- AnalyticsRepository `SpendingSummary.aggregate` still returns `null`.

Decision: **mostly fixed / guard remaining legacy paths**

---

## A03 — Historical analytics uses current rates

Old tracker: `FIXED`  
Current status: **MOSTLY FIXED**

Evidence:

- `AnalyticsCurrencyNormalizer` uses `RateBasis.TRANSACTION_DATE`.
- `AnalyticsRepository.getSpendingSummary()` derives total from normalized rows.
- `AnalyticsRepository.getCategoryBreakdown()` also normalizes rows.

Remaining gaps:

- Budget-vs-actual budget limit conversion still uses latest rate via `currencyConverter.convert()`.
- Some `MultiCurrencyRepository` latest-rate APIs remain available for current-state use.
- Advanced old paths normalize snapshots but still self-fetch independently.

Decision: **mostly fixed, but budget rate-basis remains open**

---

## A04 — SpendingPersonalityClassifier not currency-safe

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `classify(input: NormalizedAnalyticsInput)` exists.
- `AnalyticsViewModel` calls `spendingPersonalityClassifier.classify(allInput)`.

Remaining gaps:

- Raw `classify()` still exists and queries raw repositories.
- Normalized path sets:

```kotlin
featureScores["budgetAdherence"] = 0.5
```

So budget behavior is neutralized/degraded.

Decision: **partial**

---

## A05 — AnalyticsRepository drops partial conversion state

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `SpendingSummary` has `aggregate` and `isPartial`.
- `getSpendingSummary()` computes `isPartial`.

Bug:

- `aggregate = null` in emitted `SpendingSummary`.

Also:

- no structured top-level warning list in `SpendingSummary`
- only `isPartial` boolean survives

Decision: **partial / model says aggregate exists but implementation does not populate it**

---

## A06 — Multiple analytics paths disagree

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `AnalyticsViewModel` centralizes many computations through `AnalyticsInputAssembler`.

Remaining disagreement paths:

- `AdvancedAnalyticsEngine.getSpendingPatterns()` self-fetches.
- `AdvancedAnalyticsEngine.getStatisticalInsights()` self-fetches.
- `InsightsEngine` legacy overload computes current-month from `timeProvider.now()`.
- ViewModel still independently computes year-over-year, velocity anomalies, post-salary pattern, suspect transactions.

Decision: **partial**

---

## A07 — InsightsEngine defaults to EUR

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `InsightsEngine.generateInsights(input: NormalizedAnalyticsInput, categories)` exists.
- Legacy overloads still default `displayCurrency = "EUR"`.

More importantly:

`AnalyticsViewModel` still calls:

```kotlin
insightsEngine.generateInsights(
    analyticsCategories,
    allExpenseSnapshots,
    displayCurrency = homeCurrency,
    conversionWarnings = conversionWarnings
)
```

That is the legacy current-month overload.

Decision: **partial / production caller still uses legacy overload**

---

## A08 — Daily chart uses last N days from now

Old tracker: `FIXED`  
Current status: **MOSTLY FIXED**

Evidence:

- `DailyBucketEngine` builds exact buckets from `PeriodRange`.
- `AnalyticsViewModel` uses it for `dailyTotals`.

Remaining footgun:

- `InsightsEngine` legacy internals still use current-month logic.
- Any caller of legacy insight/daily methods can reintroduce now-anchored behavior.

Decision: **mostly fixed / legacy footgun remains**

---

## A09 — Advanced analytics may use different period

Old tracker: `FIXED`  
Current status: **PARTIAL / still real bug for WEEK**

Evidence:

In `AnalyticsViewModel`:

```kotlin
val advancedPeriod = timePeriodToAnalyticsPeriod(period)
val advRange = if (advancedPeriod != null) {
    advancedAnalyticsEngine.getPeriodRange(advancedPeriod)
} else {
    AnalyticsPeriodRange(...)
}
```

`timePeriodToAnalyticsPeriod(TimePeriod.WEEK)` returns `AnalyticsPeriod.WEEK`.

So for WEEK, the ViewModel allows `AdvancedAnalyticsEngine` to recompute period using its own `timeProvider.now()` instead of the already-computed `currentStart/currentEnd`.

Also:

- spending patterns and statistics use old API with `advRange`.

Decision: **partial / reopen for WEEK and old advanced sections**

---

## A10 — Category analytics compares normalized spend to raw budget

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `BudgetVsActualEngine` exists and computes actuals from `NormalizedAnalyticsInput`.

Remaining issue:

`AnalyticsViewModel.convertBudgetAmountToHomeCurrency()` uses:

```kotlin
currencyConverter.convert(...)
```

This is latest/current-rate conversion.

There is a TODO saying budget limit conversion should use period-end or period-average rate.

Decision: **partial / budget FX basis not fixed**

---

## A11 — Conversion warnings do not affect confidence

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `AnalyticsDataQuality` has confidence fields.
- Assembler computes `confidencePenalty` and `confidenceMultiplier`.
- `DataQualityReport.fromNormalization()` computes conversion confidence.

Remaining gaps:

- Not every output exposes confidence/quality.
- `BudgetVsActualItem` does not carry quality.
- advanced sections may only merge warnings at top-level.
- severity/confidence of individual insights is not consistently reduced by data quality.

Decision: **partial**

---

## A12 — Merchant anomaly history too thin

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `AdvancedAnalyticsEngine.getMerchantAnalytics(input, historicalInput)` exists.
- `AnalyticsViewModel` builds 12-month historical input for merchant analytics.

Remaining issue:

- `InsightsEngine` anomaly path still receives `allExpenseSnapshots` from ViewModel’s `fullWindowStart`, usually start-of-current-year unless selected period is ALL.
- January/early-year baselines can still be thin.

Decision: **partial**

---

## A13 — Spending pace period wrong for historical

Old tracker: `FIXED`  
Current status: **PARTIAL / production legacy path remains risky**

Evidence:

- `SpendingPaceCalculator.calculate(... referenceNowMs)` exists and is improved.

But:

`AnalyticsViewModel` calls legacy `InsightsEngine.generateInsights(categories, allExpenseSnapshots, ...)`, and that overload uses `timeProvider.now()` to compute current month.

So selected-period insights can still be current-month based.

Decision: **partial / tied to fixing ViewModel’s InsightsEngine call**

---

## A14 — Location analytics raw DAO/path

Old tracker: `FIXED`  
Current status: **PARTIAL / safe-by-caller but raw APIs remain**

Evidence:

- `LocationInsightsEngine.computeNormalized()` exists.
- `AreaSpendingEngine.computeNormalized()` exists.
- `TravelDetectionEngine.computeNormalized()` exists.

But:

`AnalyticsViewModel` currently calls:

```kotlin
locationInsightsEngine.compute(locatedExpenses)
areaSpendingEngine.compute(normalizedPurchases)
travelDetectionEngine.compute(normalizedPurchases)
```

For `areaSpendingEngine` and `travelDetectionEngine`, the ViewModel passes normalized `Expense.copy(...)` objects, so the result is likely numerically safe.

But this is still a caller contract, not engine-enforced. Raw compute APIs remain callable.

Decision: **partial**

---

## A15 — Category deletion/history weak

Old tracker: `FIXED`  
Current status: **OPEN**

Evidence:

`AnalyticsInputAssembler` builds:

```kotlin
categoryNameById = categories.associate { it.id to it.name }
categoryNameSnapshot = categoryNameById[snap.categoryId]
```

This uses current category table state.

If a category was deleted, the historical expense loses category name snapshot.

Decision: **reopen**

---

## A16 — Analytics recomputes too much / unstable snapshot

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

`AnalyticsViewModel` fetches separately:

- `purchases`
- `allExpenses`
- `previousExpenses`
- `yearOverYearExpenses`
- `historicalExpenses`
- budgets

Advanced old methods also self-fetch.

No stable read transaction/snapshot ensures consistency.

Decision: **partial**

---

## A17 — Calendar to java.time migration

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `AdvancedAnalyticsEngine` still imports `java.util.Calendar`.
- `SpendingPersonalityClassifier` still imports `java.util.Calendar`.
- `AnalyticsViewModel` imports `java.util.*` and uses `java.util.Calendar` day constants.

Decision: **partial**

---

## A18 — SpendingPersonalityClassifier java.time migration

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- Normalized classify path exists.
- But file still imports `java.util.Calendar`.
- Raw `classify()` path remains.

Decision: **partial**

---

## A19 — ExcludedExpense warning metadata missing

Old tracker: `FIXED`  
Current status: **OPEN**

Evidence:

`ExcludedExpense` has:

```kotlin
warningType
message
```

But `AnalyticsInputAssembler` creates excluded rows with only:

```kotlin
id
originalAmount
originalCurrency
reason
```

No warning type or message.

Decision: **reopen**

---

## A20 — BudgetVsActualResult data quality missing

Old tracker: `FIXED`  
Current status: **PARTIAL**

Evidence:

- `BudgetVsActualResult` carries `dataQuality`.

But:

- `AnalyticsViewModel.BudgetVsActualItem` does not carry quality.
- `buildBudgetVsActualItems()` does not expose `engineResult.dataQuality` to items.
- It only merges warnings separately.

Decision: **partial**

---

# 4. New/current issues found

## E2-NOW-001 — AnalyticsViewModel uses legacy InsightsEngine overload, causing selected-period mismatch

Severity: **P1_HIGH**

Evidence:

`AnalyticsViewModel` calls:

```kotlin
insightsEngine.generateInsights(
    analyticsCategories,
    allExpenseSnapshots,
    displayCurrency = homeCurrency,
    conversionWarnings = conversionWarnings
)
```

The called `InsightsEngine` overload computes:

```kotlin
val now = timeProvider.now()
val currentMonth = getMonthPeriod(now)
```

Impact:

If the user selects WEEK, YEAR, ALL, or a custom-like period, the summary cards may be period-aware, but insights may still be current-month based.

Affected pipelines:

- Analytics & Insights
- Dashboard if reusing insights
- recurring insights
- anomaly/pace insights

Recommended fix:

Change ViewModel to call:

```kotlin
insightsEngine.generateInsights(currentInput, analyticsCategories)
```

or, if it needs wider history, add a new overload:

```kotlin
generateInsights(
    currentInput: NormalizedAnalyticsInput,
    historicalInput: NormalizedAnalyticsInput,
    categories: List<AnalyticsCategoryRef>
)
```

Tests:

- `analyticsViewModel_weekPeriod_insightsUseSelectedWeekNotCurrentMonth`
- `insightsEngine_normalizedOverload_usesInputPeriod`
- `analyticsViewModel_yearPeriod_doesNotGenerateCurrentMonthInsightsOnly`

---

## E2-NOW-002 — Advanced spending patterns/statistics still self-fetch and can disagree

Severity: **P1_HIGH**

Evidence:

`AdvancedAnalyticsEngine.getSpendingPatterns(period, displayCurrency)` fetches from `ExpenseRepository`.

`getStatisticalInsights(period, displayCurrency)` also fetches from `ExpenseRepository`.

ViewModel already has `currentInput`, but still calls old APIs.

Impact:

- advanced stats can see different data from main analytics
- rate basis can differ
- DB changes during load can create inconsistent screen sections

Recommended fix:

Add:

```kotlin
getSpendingPatterns(input: NormalizedAnalyticsInput)
getStatisticalInsights(input: NormalizedAnalyticsInput)
```

Then update ViewModel to call normalized overloads.

Tests:

- `advancedPatterns_useNormalizedInput_noRepositoryFetch`
- `statisticalInsights_useSameInputAsSummary`
- `advancedAndBasicTotalsMatchSameInput`

---

## E2-NOW-003 — WEEK advanced analytics still recomputes period

Severity: **P1_HIGH**

Evidence:

`AnalyticsViewModel.timePeriodToAnalyticsPeriod(TimePeriod.WEEK)` returns `AnalyticsPeriod.WEEK`.

Then:

```kotlin
advancedAnalyticsEngine.getPeriodRange(advancedPeriod)
```

uses engine `timeProvider.now()`.

Impact:

The advanced WEEK range can differ from ViewModel’s already selected `currentStart/currentEnd`.

Recommended fix:

Never call `advancedAnalyticsEngine.getPeriodRange()` from ViewModel for computed screen state. Always pass explicit `AnalyticsPeriodRange(currentStart, currentEnd, previousStart, previousEnd)`.

Tests:

- `analyticsViewModel_weekAdvancedRangeMatchesMainRange`
- `advancedAnalyticsEngine_notAskedToComputeRangeForViewModelWeek`

---

## E2-NOW-004 — Budget-vs-actual budget conversion still latest-rate

Severity: **P1_HIGH**

Evidence:

ViewModel TODO confirms:

```kotlin
currencyConverter.convert()
```

is used for budget conversion and should be period-end/period-average.

Impact:

Actual spending uses transaction-date normalization; budget limits use latest rate. Closed historical periods can change when today’s rates change.

Recommended fix:

Use explicit budget basis:

- period-end rate for budget limit
- or budget-period fixed basis
- expose `budgetRateBasis`

Tests:

- `budgetVsActual_budgetLimitUsesPeriodEndRate`
- `budgetVsActual_closedPeriodStableWhenLatestRateChanges`
- `budgetVsActual_warningWhenBudgetConversionFails`

---

## E2-NOW-005 — AnalyticsRepository SpendingSummary says aggregate exists but emits null

Severity: **P1_MEDIUM/HIGH**

Evidence:

`SpendingSummary` includes:

```kotlin
aggregate: MoneyAggregate?
```

But `getSpendingSummary()` emits:

```kotlin
aggregate = null
```

Impact:

Consumers expecting aggregate/data quality/source buckets cannot use it.

Recommended fix:

Either:

1. populate a real `MoneyAggregate`, or
2. remove/rename field if not available

Best: build a `MoneyAggregate` from normalized rows using home currency, or return a structured `NormalizedSummary`.

Tests:

- `spendingSummary_populatesAggregate`
- `spendingSummary_aggregateTotalEqualsDailyHistorySum`
- `spendingSummary_partialStateIncludesWarnings`

---

## E2-NOW-006 — ExcludedExpense warning metadata is dead

Severity: **P2_MEDIUM**

Evidence:

`ExcludedExpense` has warning fields, but assembler does not set them.

Impact:

UI/debug cannot tell whether a specific row was excluded because of invalid currency, missing rate, etc., except coarse reason.

Recommended fix:

Map normalizer warnings back to expense IDs, or make normalizer return structured per-expense failures.

Tests:

- `excludedExpense_invalidCurrency_hasWarningTypeAndMessage`
- `excludedExpense_missingRate_hasSourceCurrencyWarning`

---

## E2-NOW-007 — NormalizedExpense rate provenance fields are not populated

Severity: **P2_MEDIUM**

Evidence:

`NormalizedExpense` has:

```kotlin
rateBasis
rateUsed
rateValidDate
rateLastUpdated
conversionPath
```

But `AnalyticsInputAssembler` does not populate them, and `AnalyticsCurrencyNormalizer.NormalizedExpenseSnapshot` does not carry this metadata.

Impact:

Audit/debug/export cannot explain how a normalized row was converted.

Recommended fix:

Extend `NormalizedExpenseSnapshot` to include conversion provenance from `ConversionOutcome.Converted`.

Tests:

- `normalizedExpense_foreignCurrencyCarriesRateBasisAndRateUsed`
- `normalizedExpense_sameCurrencyMarksIdentityBasis`

---

## E2-NOW-008 — `AnalyticsInputOptions.includeDepositsForBehavior` appears unused

Severity: **P2_LOW/MEDIUM**

Evidence:

`AnalyticsInputOptions` has:

```kotlin
includeDepositsForBehavior
```

But assembler filtering only checks:

```kotlin
spendingOnly
excludeNotMine
```

Impact:

Option name suggests behavior-specific deposit inclusion, but it has no effect. Current ViewModel works around this by using `spendingOnly=false`.

Recommended fix:

Either remove the option or implement it:

```text
spendingOnly=true + includeDepositsForBehavior=true
=> include PURCHASE + DEPOSIT for behavior engines, but not transfers/withdrawals
```

Tests:

- `assembler_includeDepositsForBehavior_includesDepositsButNotTransfers`

---

## E2-NOW-009 — Location analytics still rely on caller-normalized raw compute APIs

Severity: **P2_MEDIUM**

Evidence:

`AreaSpendingEngine.compute()` and `TravelDetectionEngine.compute()` are deprecated but still called by `AnalyticsViewModel` with normalized copies.

This is numerically probably okay because the caller pre-normalizes, but it is fragile.

Impact:

Future callers can pass raw mixed-currency expenses.

Recommended fix:

Update ViewModel to call normalized APIs directly or ERROR-deprecate raw APIs after call sites are migrated.

Tests:

- `analyticsViewModel_locationAreaUsesNormalizedApi`
- `analyticsViewModel_travelUsesNormalizedApi`
- `noProductionCallToRawAreaTravelCompute`

---

## E2-NOW-010 — Some analytics UI MoneyAmount properties can throw when currency is null

Severity: **P2_MEDIUM**

Evidence:

`AnalyticsState.moneyCurrentTotal` and `BudgetVsActualItem.moneyBudgetAmount` construct:

```kotlin
MoneyAmount(amount, CurrencyCode(homeCurrency ?: ""))
```

or `CurrencyCode(displayCurrency ?: "")`.

Impact:

If UI touches these while loading/error before currency is available, `CurrencyCode("")` can throw.

Recommended fix:

Return nullable money properties:

```kotlin
val moneyCurrentTotalOrNull: MoneyAmount?
```

or guard loadable state.

Tests:

- `analyticsState_loading_moneyCurrentTotalDoesNotThrow`
- `budgetVsActual_loading_moneyPropertiesDoNotThrow`

---

# 5. Current issue list

## P1 issues

| ID | Title |
|---|---|
| E2-NOW-001 | ViewModel uses legacy InsightsEngine current-month overload |
| E2-NOW-002 | Advanced patterns/statistics still self-fetch |
| E2-NOW-003 | WEEK advanced range recomputed with engine now |
| E2-NOW-004 | Budget-vs-actual budget conversion uses latest rate |
| E2-NOW-005 | SpendingSummary aggregate is null despite model contract |
| A15 | Category historical names are not stable after deletion |

## P2 issues

| ID | Title |
|---|---|
| E2-NOW-006 | ExcludedExpense warning metadata not populated |
| E2-NOW-007 | NormalizedExpense rate provenance not populated |
| E2-NOW-008 | includeDepositsForBehavior option unused |
| E2-NOW-009 | Location analytics raw APIs still called |
| E2-NOW-010 | UI MoneyAmount properties can throw on null currency |
| A17/A18 | Calendar migration incomplete |
| A20 | BudgetVsActual dataQuality not exposed per item |

---

# 6. Recommended fix order

## PR1 — Insights period correctness

Closes:

- E2-NOW-001
- parts of A07/A08/A13

Files:

- `AnalyticsViewModel.kt`
- `InsightsEngine.kt`
- tests

Implementation:

1. Replace ViewModel legacy insight call with normalized overload.
2. If wider historical context is needed, add explicit overload:
   ```kotlin
   generateInsights(currentInput, historicalInput, categories)
   ```
3. Ensure selected WEEK/YEAR/ALL periods generate period-correct insights.

Tests:

- `analyticsViewModel_week_insightsUseSelectedRange`
- `analyticsViewModel_year_insightsUseYearRange`
- `insightsEngine_normalizedOverloadUsesInputPeriod`

Risk: medium. No schema/Hilt.

---

## PR2 — Advanced analytics normalized-input adoption

Closes:

- E2-NOW-002
- E2-NOW-003
- A06/A09 partial

Files:

- `AdvancedAnalyticsEngine.kt`
- `AnalyticsViewModel.kt`
- tests

Implementation:

1. Add:
   ```kotlin
   getSpendingPatterns(input: NormalizedAnalyticsInput)
   getStatisticalInsights(input: NormalizedAnalyticsInput)
   ```
2. Stop ViewModel from calling old self-fetching APIs.
3. Stop ViewModel from calling `advancedAnalyticsEngine.getPeriodRange(...)`; pass explicit period range.

Tests:

- `advancedPatterns_noRepositoryFetchWhenInputProvided`
- `statisticalInsights_totalEqualsCurrentInputTotal`
- `weekAdvancedRangeMatchesMainRange`

Risk: medium.

---

## PR3 — Budget-vs-actual rate basis

Closes:

- E2-NOW-004
- A10/A20 partial

Files:

- `AnalyticsViewModel.kt`
- maybe `BudgetVsActualEngine.kt`
- maybe currency/budget helper

Implementation:

1. Replace latest-rate budget conversion with explicit period-end basis.
2. Add `budgetRateBasis` or warning.
3. Expose `dataQuality` from `BudgetVsActualResult` into UI state.

Tests:

- `budgetVsActual_usesPeriodEndBudgetRate`
- `budgetVsActual_closedPeriodStableWhenLatestRateChanges`
- `budgetVsActual_itemCarriesDataQuality`

Risk: medium/high because CurrencyConverter semantics are involved. Do not change global CurrencyConverter.

---

## PR4 — Data-quality/provenance completion

Closes:

- E2-NOW-005
- E2-NOW-006
- E2-NOW-007
- A05/A19

Files:

- `AnalyticsCurrencyNormalizer.kt`
- `AnalyticsInputAssembler.kt`
- `NormalizedAnalyticsInput.kt`
- `AnalyticsRepository.kt`
- tests

Implementation:

1. Fill `SpendingSummary.aggregate` or remove misleading field.
2. Populate `ExcludedExpense.warningType/message`.
3. Populate rate provenance fields on `NormalizedExpense`.

Tests:

- `spendingSummaryAggregateMatchesNormalizedTotal`
- `excludedExpenseHasWarningMetadata`
- `normalizedExpenseCarriesRateProvenance`

Risk: medium.

---

## PR5 — Historical category identity

Closes:

- A15

Options:

1. Soft-delete categories instead of hard delete.
2. Add category snapshot fields to `Expense`.
3. Add separate historical category name table.

This likely needs schema migration. Do later.

Risk: high because DB/schema.

---

## PR6 — Legacy/raw API guardrails

Closes:

- A14/A17/A18 raw footguns
- E2-NOW-009

Implementation:

1. Move raw APIs to `DeprecationLevel.ERROR` where call sites are gone.
2. Add static guards:
   - no production call to raw `SpendingPersonalityClassifier.classify()`
   - no production call to `AreaSpendingEngine.compute(List<Expense>)`
   - no production call to `TravelDetectionEngine.compute(List<Expense>)`
   - no production call to legacy insight overloads
3. Finish Calendar-to-java.time cleanup only after behavior tests.

Risk: low/medium if done after call-site migration.

---

# 7. Pipeline regression matrix

## Analytics screen

Must still verify:

- current total
- previous total
- category breakdown
- merchant breakdown
- daily chart
- insights
- advanced categories
- advanced merchants
- spending patterns
- statistical insights
- budget-vs-actual
- data-quality warnings

## Dashboard totals

After `TotalsAggregationEngine` changes:

- monthly totals
- weekly drilldown
- daily drilldown
- yearly totals
- category breakdown

## Budget pipeline

After PR3:

- budget limits convert consistently
- budget actuals match analytics actuals
- conversion warnings visible

## Location pipeline

After PR6:

- location insights still load
- area spending still sorted
- travel insight still detects trips
- conversion-failed rows do not silently distort totals

## Forecast/health

If `DataQualityReport` changes:

- forecast confidence still receives conversion quality
- health score does not silently assume clean data

---

# 8. Static checks performed

Checked statically:

- canonical input use in `AnalyticsViewModel`
- normalizer rate basis
- summary/category breakdown in `AnalyticsRepository`
- latest/historical APIs in `MultiCurrencyRepository`
- totals aggregation engine methods
- advanced analytics normalized and legacy paths
- insights normalized and legacy paths
- spending personality normalized/raw paths
- budget-vs-actual engine and ViewModel adapter
- location analytics raw/normalized APIs
- test directory coverage

Not checked fully:

- every dashboard consumer
- every forecast/health consumer
- every UI rendering of quality warnings
- compile correctness
- Hilt graph
- Room schema

---

# 9. Known compile risks for future fixes

Potential compile risks:

- replacing legacy `InsightsEngine.generateInsights(...)` calls may require overload signature cleanup
- adding normalized overloads to `AdvancedAnalyticsEngine` can create ambiguous overloads if names are reused poorly
- changing `BudgetVsActualItem` UI model may require Compose/UI updates
- populating rate provenance may require changing `AnalyticsNormalizationResult` and tests
- changing category snapshot persistence requires Room migration
- making deprecated APIs `ERROR` may reveal many production callers

---

# 10. Human validation commands

Do not run during individual slices unless your workflow allows only final engine validation.

After all Engine 2 PRs are finalized:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If schema changes for category snapshots:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt constructor changes:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# 11. Final conclusion

Engine 2 is **significantly improved** and likely usable, but I would not call it clean yet.

The architecture is now mostly there:

```text
raw expenses
  -> AnalyticsInputAssembler
  -> AnalyticsCurrencyNormalizer
  -> NormalizedAnalyticsInput
  -> DailyBucketEngine / BudgetVsActualEngine / advanced engines
```

But adoption is incomplete.

The most important fix is **not** another currency primitive rewrite. It is to finish the migration away from legacy self-fetching/current-month analytics paths.

Best first PR:

```text
PR1: make AnalyticsViewModel call the normalized InsightsEngine overload
```

That is the highest-value, lowest-schema-risk fix because it corrects selected-period insight behavior without touching Room or global currency semantics.

After that:

```text
PR2: migrate AdvancedAnalyticsEngine patterns/statistics to NormalizedAnalyticsInput
PR3: fix budget-vs-actual rate basis
PR4: complete quality/provenance metadata
PR5: handle historical category identity
```

Verdict: **YELLOW — good foundation, but still mixed old/new paths.**