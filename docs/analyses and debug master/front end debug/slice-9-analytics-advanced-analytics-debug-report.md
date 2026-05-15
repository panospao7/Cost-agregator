# Slice 9 Debug Report — Analytics + Advanced Analytics

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/screens/analytics/*`
- `ui/components/CategoryDonutChart.kt`
- `ui/components/SpendingTrendChart.kt`
- `ui/components/ChartMarker.kt`
- `ui/components/analytics/*`
- `domain/analytics/*`
- cross-slice consistency with:
  - Home dashboard totals
  - Budget spent/remaining
  - Transactions filters
  - Currency/exchange infrastructure

Sources inspected:
- Analytics UI folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics
- `AnalyticsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt
- `AnalyticsScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt
- `AdvancedAnalyticsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsViewModel.kt
- `AdvancedAnalyticsScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsScreen.kt
- `CategoryDonutChart.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/CategoryDonutChart.kt
- `SpendingTrendChart.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/SpendingTrendChart.kt
- `ChartMarker.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ChartMarker.kt
- Analytics components folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/analytics
- `StatisticalVisualizations.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/analytics/StatisticalVisualizations.kt
- `PersonalityProfileCard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/analytics/PersonalityProfileCard.kt
- `NoSpendStreakWidget.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/analytics/NoSpendStreakWidget.kt
- Domain analytics folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/analytics
- `AnalyticsInputAssembler.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt
- `AnalyticsCurrencyNormalizer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt
- `NormalizedAnalyticsInput.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt
- `AdvancedAnalyticsEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt
- `AdvancedAnalyticsDashboard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt
- `DailyBucketEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/analytics/DailyBucketEngine.kt
- `SpendingPersonalityClassifier.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt
- UI map: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- UI component library: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/UI_COMPONENT_LIBRARY.md
- Codebase segments: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 9 is a high-risk financial correctness slice because Analytics is where users validate whether Home, Budget, Transactions, and Forecasting “agree.”

The codebase has strong foundations:
- `AnalyticsCurrencyNormalizer` is a central normalization point.
- `AnalyticsInputAssembler` creates `NormalizedAnalyticsInput`.
- `DailyBucketEngine` builds exact daily buckets from normalized input.
- `BudgetVsActualEngine` consumes normalized input.
- `SpendingPersonalityClassifier` has a normalized-input overload.
- Analytics ViewModel already uses normalized inputs for many totals.

However, several important issues remain:

1. Main `AnalyticsViewModel` is too broad and still owns many engines/state transformations inline.
2. Hidden `"EUR"` fallbacks remain in state models, assemblers, Advanced Analytics, and visualization components.
3. Main Analytics and Advanced Analytics use different pipelines and period policies, so the two screens can disagree.
4. Some advanced analytics paths still call old/deprecated APIs instead of `NormalizedAnalyticsInput`.
5. `CategoryDonutChart` hardcodes the euro symbol and hardcoded English text.
6. Analytics chart components have default `"EUR"` parameters, hardcoded labels, and weak reduced-motion/test controls.
7. `NormalizedExpense.toExpenseSnapshot()` sets `currency = originalCurrency` while `effectiveAmount` is normalized, creating a dangerous mixed semantic object.
8. Data-quality warnings exist but are not enforced as a contract across all analytics cards.
9. Analytics debug logging prints financial totals/daily totals through `Timber`.
10. Docs say `SpendingTrendChart` is consumed by `AnalyticsScreen`, but source inspection did not find that usage.
11. Period boundary behavior is complex and not contract-tested against Home/Transactions filters.
12. Component test coverage for charts/statistical widgets is likely insufficient.

Recommended strategy:
- Do not rewrite Analytics.
- Add financial invariant tests first.
- Remove hidden currency fallbacks next.
- Make chart currency explicit.
- Then split ViewModel/screen into smaller units.

---

## 2. Baseline commands

Run first:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*AnalyticsViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AdvancedAnalytics*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AnalyticsCurrencyNormalizer*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AnalyticsInputAssembler*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*DailyBucketEngine*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BudgetVsActualEngine*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*SpendingPersonality*" --stacktrace
```

Inventory tests:

```bash
find app/src/test app/src/androidTest \
  -iname "*Analytics*" -o \
  -iname "*Chart*" -o \
  -iname "*Personality*" -o \
  -iname "*Donut*" -o \
  -iname "*Histogram*" -o \
  -iname "*Trend*"
```

If Compose tests exist:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

Stop on first compile failure.

---

## 3. Current architecture map

### Main Analytics tab

```text
categoryRepository.allCategories
selectedPeriod
expenseRepository.getAllExpenses freshness
budgetRepository.allBudgets freshness
currencySettingsRepository.homeCurrency
currencySettingsRepository.lastRateUpdate
        ↓
AnalyticsViewModel.state
        ↓
AnalyticsInputAssembler.build(...)
        ↓
NormalizedAnalyticsInput
        ↓
inline totals/category/merchant/day/hour/year-over-year/location/personality logic
        ↓
AdvancedAnalyticsEngine partial calls
        ↓
AnalyticsScreen LazyColumn
        ↓
donut chart, cards, warnings, location cards, personality card, statistical cards
```

### Advanced Analytics feature screen

```text
AdvancedAnalyticsViewModel
        ↓
currencySettingsRepository.homeCurrency
refreshNonce
        ↓
AdvancedAnalyticsDashboard.generateDashboardData(thirtyDaysAgo, now)
        ↓
AdvancedAnalyticsScreen
        ↓
cashflow overview, warnings, insights, top categories
```

### Currency/data-quality pipeline

```text
Expense/ExpenseSnapshot
        ↓
AnalyticsCurrencyNormalizer
        ↓
AnalyticsNormalizationResult
        ↓
AnalyticsInputAssembler
        ↓
NormalizedAnalyticsInput + AnalyticsDataQuality
        ↓
AnalyticsState.conversionWarnings / qualityWarnings
```

---

# 4. Issues

## S9-001 — `AnalyticsViewModel` is a God ViewModel

Severity: High  
Files:
- `AnalyticsViewModel.kt`

Evidence:
The ViewModel owns:
- selected period
- cache keys/caches
- expense/budget freshness
- current/previous/all/yoy windows
- normalized input assembly
- category breakdown
- merchant breakdown
- daily totals
- insights
- budget-vs-actual
- recurring detection
- advanced categories/merchants/statistics/patterns
- year-over-year
- velocity anomalies
- post-salary pattern
- suspect duplicate/outlier detection
- day/hour pattern
- location insights
- spending personality profile

The source comment also states that multiple analytics computations remain in the ViewModel pending extraction.

Problem:
A failure in any analytics sub-feature can break the entire tab. Constructor/test fixtures are large, and agents cannot safely debug a single chart/engine in isolation.

Fix strategy:
Extract pure coordinators/engines around existing behavior.

Implementation plan:
Create:

```text
AnalyticsStateAssembler
AnalyticsPeriodController
AnalyticsSummaryCalculator
AnalyticsCategoryMerchantCalculator
AnalyticsTemporalPatternCalculator
AnalyticsAnomalyPresenter
AnalyticsLocationPresenter
AnalyticsAdvancedSectionLoader
AnalyticsBudgetVsActualPresenter
```

Short-term first extraction:
1. `AnalyticsSummaryCalculator`
2. `AnalyticsCategoryMerchantCalculator`
3. `AnalyticsAdvancedSectionLoader`
4. `AnalyticsAnomalyPresenter`

Acceptance:
- `AnalyticsViewModel` only orchestrates flows and exposes state.
- Pure calculators have JVM tests.
- Main ViewModel tests become state/flow orchestration tests.

---

## S9-002 — `AnalyticsScreen.kt` is monolithic

Severity: High  
Files:
- `AnalyticsScreen.kt`

Evidence:
The screen owns:
- root state collection
- period selector
- total hero
- warnings
- category chart/list
- merchant cards
- budget vs actual
- insights
- statistical visualizations
- year-over-year
- velocity anomalies
- post-salary patterns
- suspect transactions
- location/area/travel cards
- parsing helpers
- many small cards inline

Problem:
A UI bug in a single analytics card requires compiling/testing a huge screen. It also creates hidden coupling between state shape and UI layout.

Fix strategy:
Split route/content/sections/cards.

Implementation plan:

```text
AnalyticsRoute.kt
AnalyticsScreenContent.kt
AnalyticsPeriodSelector.kt
AnalyticsSummarySection.kt
AnalyticsCategorySection.kt
AnalyticsMerchantSection.kt
AnalyticsBudgetSection.kt
AnalyticsInsightsSection.kt
AnalyticsTemporalSection.kt
AnalyticsAnomalySection.kt
AnalyticsLocationSection.kt
AnalyticsWarningsCard.kt
AnalyticsEmptyState.kt
```

Acceptance:
- `AnalyticsRoute` collects state and callbacks only.
- `AnalyticsScreenContent` is pure and testable with fake state.
- Each section has independent Compose tests.

---

## S9-003 — Hidden `"EUR"` fallbacks can still leak into Analytics

Severity: Critical multi-currency correctness  
Files:
- `AnalyticsViewModel.kt`
- `AdvancedAnalyticsViewModel.kt`
- `AdvancedAnalyticsDashboard.kt`
- `AnalyticsInputAssembler.kt`
- `NormalizedAnalyticsInput.kt`
- `SpendingTrendChart.kt`
- `StatisticalVisualizations.kt`

Evidence:
Known defaults/fallbacks:
- `AnalyticsState.homeCurrency = "EUR"`
- `BudgetVsActualItem.displayCurrency = "EUR"`
- `currencySettingsRepository.homeCurrency().catch { emit("EUR") }`
- `AdvancedAnalyticsViewModel` catches home currency failure and emits `"EUR"`
- `AdvancedAnalyticsDashboard.generateDashboardData` uses `getOrDefault("EUR")`
- `AnalyticsInputAssembler.build(period)` uses `getOrDefault("EUR")`
- `NormalizedAnalyticsInput.homeCurrency = "EUR"`
- multiple chart components default `currency = "EUR"`

Problem:
If the home currency repository is delayed or fails, analytics can show or compute in EUR for non-EUR users. This is a financial correctness bug.

Fix strategy:
Make currency loading/degraded state explicit. Never silently calculate with EUR unless the user’s actual home currency is EUR.

Implementation plan:
1. Replace state defaults with nullable/typed currency state:

```kotlin
sealed interface CurrencyResolutionState {
    data object Loading : CurrencyResolutionState
    data class Ready(val code: String) : CurrencyResolutionState
    data class Error(val reason: UiText) : CurrencyResolutionState
}
```

2. In ViewModels:
- do not `catch { emit("EUR") }`;
- catch to `CurrencyResolutionState.Error`.

3. In domain builders:
- `AnalyticsInputAssembler.build(period)` should either:
  - require `homeCurrency` param, or
  - return an `AnalyticsInputBuildResult.CurrencyUnavailable`.

4. Chart components:
- make `currency: String` required.
- remove default `"EUR"`.

Acceptance:
- no production analytics path silently defaults to EUR.
- tests delay/fail `homeCurrency()` and verify analytics shows loading/error, not EUR.
- static grep guard:

```bash
grep -R 'currency: String = "EUR"\|homeCurrency.*"EUR"\|getOrDefault("EUR")\|emit("EUR")' \
  app/src/main/java/com/yourname/expensetracker/{ui/screens/analytics,ui/components,domain/analytics}
```

Every remaining result must be test-only or explicitly justified.

---

## S9-004 — `CategoryDonutChart` hardcodes euro symbol and English text

Severity: Critical UI currency correctness  
File:
- `CategoryDonutChart.kt`

Evidence:
The chart summary and center label hardcode:
- `Total €...`
- `€${String.format(...)}`
- `"No category data yet"`
- `"Add a few categorized transactions..."`
- `"CATEGORY SPLIT"`
- `"Legend: ..."`

Problem:
Analytics can be correctly normalized to USD/GBP/etc., but the donut chart still says EUR. Accessibility also announces EUR.

Fix strategy:
Pass explicit currency and use `CurrencyFormatter` + string resources.

Implementation plan:

```kotlin
@Composable
fun CategoryDonutChart(
    categories: List<AnalyticsCategoryBreakdown>,
    totalSpent: Double,
    currency: String,
    modifier: Modifier = Modifier,
    animate: Boolean = true
)
```

Then:
```kotlin
CurrencyFormatter.format(totalSpent, currency, showCents = false)
```

Call site:
```kotlin
CategoryDonutChart(
    categories = state.categoryBreakdown,
    totalSpent = state.currentTotal,
    currency = state.homeCurrency
)
```

Acceptance:
- no hardcoded euro symbol.
- no hardcoded visible English.
- accessibility summary uses selected/home currency.
- Compose test renders USD and asserts not EUR.

---

## S9-005 — `CategoryDonutChart` can render invalid percentages/NaN arcs

Severity: High  
File:
- `CategoryDonutChart.kt`

Problem:
The chart trusts `item.percentage`. If percentages are negative, NaN, >100, or sum far beyond 100 due to bad input, Canvas arcs can render incorrectly.

Fix strategy:
Normalize chart input into a UI model.

Implementation plan:

```kotlin
data class DonutSliceUi(
    val label: String,
    val percentage: Float,
    val amount: Double,
    val color: Color
)
```

Sanitize:
- drop NaN/Infinite
- clamp negative to zero
- if total percentage > 100, normalize relative to sum
- if totalSpent <= 0, render empty state

Acceptance:
- invalid input does not crash.
- chart has tests for empty, zero total, >100 sum, NaN, invalid color.
- legend percentages match arc basis.

---

## S9-006 — Analytics chart animations are not test/reduced-motion friendly

Severity: Medium  
Files:
- `CategoryDonutChart.kt`
- `SpendingTrendChart.kt`
- `StatisticalVisualizations.kt`
- `NoSpendStreakWidget.kt`

Evidence:
`CategoryDonutChart` animates with `Animatable` and hardcoded 800ms. `NoSpendStreakWidget` uses `animateContentSize`. Several charts have no animation toggle.

Problem:
Animations make Compose tests flaky and ignore reduced-motion accessibility preferences.

Fix strategy:
Add animation controls.

Implementation:
```kotlin
animate: Boolean = true
```

For tests:
```kotlin
animate = false
```

Acceptance:
- all chart component tests can run deterministically.
- reduced-motion setting can disable chart motion globally if available.

---

## S9-007 — `NormalizedExpense.toExpenseSnapshot()` has mixed currency semantics

Severity: Critical maintainability/financial correctness  
File:
- `NormalizedAnalyticsInput.kt`

Evidence:
`NormalizedExpense.toExpenseSnapshot()` creates an `ExpenseSnapshot` with:
- `amount = originalAmount`
- `effectiveAmount = normalizedAmount`
- `currency = originalCurrency`

Problem:
This object looks like an original-currency snapshot but contains a home-currency `effectiveAmount`. Any legacy engine or UI that formats `effectiveAmount` using `snapshot.currency` will misrepresent money.

Fix strategy:
Return a clearly normalized snapshot.

Implementation options:

### Option A — safer
Set:
```kotlin
amount = normalizedAmount
effectiveAmount = normalizedAmount
currency = normalizedCurrency
```

### Option B — explicit type
Do not convert to `ExpenseSnapshot`; create:
```kotlin
data class NormalizedExpenseSnapshot(...)
```
and migrate engines to consume it.

Recommended short-term:
- Change `toExpenseSnapshot()` to use normalized currency/amount.
- Add a comment and tests.
- If original amount is needed, keep it in `NormalizedExpense`.

Acceptance:
- no `ExpenseSnapshot` has normalized effective amount with original currency.
- tests verify conversion from EUR expense to USD normalized snapshot has `currency = "USD"`.

---

## S9-008 — Advanced Analytics old APIs still bypass the normalized-input path

Severity: High multi-currency risk  
Files:
- `AnalyticsViewModel.kt`
- `AdvancedAnalyticsEngine.kt`

Evidence:
`AnalyticsViewModel` calls:
```kotlin
@Suppress("DEPRECATION")
advancedAnalyticsEngine.getSpendingPatterns(advRange, displayCurrency = homeCurrency)

@Suppress("DEPRECATION")
advancedAnalyticsEngine.getStatisticalInsights(advRange, displayCurrency = homeCurrency)
```

`AdvancedAnalyticsEngine` comments say old APIs remain and should accept `NormalizedAnalyticsInput`.

Problem:
Category/merchant analytics are partially migrated, but spending patterns/statistical insights still use old APIs and separate queries. This creates risk that advanced cards disagree with main totals and warnings.

Fix strategy:
Add normalized overloads and route all advanced computations through the same `NormalizedAnalyticsInput`.

Implementation plan:
```kotlin
suspend fun getSpendingPatterns(
    input: NormalizedAnalyticsInput
): Pair<SpendingPatternAnalysis?, List<AnalyticsConversionWarning>>

suspend fun getStatisticalInsights(
    input: NormalizedAnalyticsInput
): Pair<StatisticalInsights?, List<AnalyticsConversionWarning>>
```

Then in ViewModel:
```kotlin
val patternsDeferred = async {
    advancedAnalyticsEngine.getSpendingPatterns(currentInput)
}
val statsDeferred = async {
    advancedAnalyticsEngine.getStatisticalInsights(currentInput)
}
```

Acceptance:
- no `@Suppress("DEPRECATION")` in Analytics ViewModel.
- advanced patterns/statistics use same normalized input and same data-quality warnings.
- mixed-currency fixture passes.

---

## S9-009 — Main Analytics and Advanced Analytics use divergent pipelines

Severity: High  
Files:
- `AnalyticsViewModel.kt`
- `AdvancedAnalyticsViewModel.kt`
- `AdvancedAnalyticsDashboard.kt`
- `AdvancedAnalyticsScreen.kt`

Evidence:
Main Analytics uses selected period and complex normalized state.
Advanced Analytics ViewModel always generates a dashboard for `thirtyDaysAgo..now`.
Advanced Analytics Dashboard fetches its own home currency and expenses.

Problem:
The two analytics screens can show different totals, currencies, warnings, and period definitions. Users may see Analytics Month ≠ Advanced Analytics 30 days and think data is wrong.

Fix strategy:
Share a single analytics input/session.

Implementation plan:
- Add `AnalyticsPeriodRange`/`PeriodRange` parameter to Advanced Analytics navigation or ViewModel.
- Make `AdvancedAnalyticsDashboard.generateDashboardData(input: NormalizedAnalyticsInput, comparisonInput: NormalizedAnalyticsInput?)`.
- Or reuse `AnalyticsStateAssembler` output.

Acceptance:
- Advanced Analytics period is visible and matches caller/selected period unless intentionally “last 30 days.”
- Main and Advanced total spent agree for same period/currency fixture.
- tests cover both screens with one fixture.

---

## S9-010 — Advanced Analytics Dashboard still falls back to EUR and uses raw Calendar

Severity: High  
File:
- `AdvancedAnalyticsDashboard.kt`

Evidence:
- `homeCurrency().first()` failure uses `"EUR"`.
- monthly trend and weekly pattern include comments to replace `Calendar`.

Problem:
The screen can compute wrong currency on failure, and calendar behavior may be hard to test around DST/locale/first-day-of-week.

Fix strategy:
- remove EUR fallback;
- use `java.time` utilities;
- expose data-quality/degraded state.

Acceptance:
- no EUR fallback in `AdvancedAnalyticsDashboard`.
- month bucketing tests cover DST, leap year, partial month windows.
- weekly pattern uses explicit `ZoneId` and first-day policy.

---

## S9-011 — Data-quality warnings are visible but not enforced as a contract

Severity: High  
Files:
- `AnalyticsViewModel.kt`
- `AdvancedAnalyticsScreen.kt`
- `AnalyticsWarningsCard`
- `DataQualityReport.kt`

Evidence:
Warnings are collected and `AnalyticsWarningsCard` exists. But not every analytics section is guaranteed to use the same warning basis, and some subengine exceptions are swallowed into empty output.

Problem:
A chart can disappear or show partial values without explaining that data was excluded.

Fix strategy:
Create a screen-level data-quality contract.

Implementation:
```kotlin
data class AnalyticsDataQualityUi(
    val isPartial: Boolean,
    val excludedCount: Int,
    val confidence: Float,
    val warnings: List<AnalyticsConversionWarning>,
    val affectedSections: Set<AnalyticsSectionId>
)
```

Each section state should include:
- `dataQuality`
- `isAvailable`
- `unavailableReason`

Acceptance:
- if conversion failed, every affected section either shows warning or is disabled/degraded.
- tests verify missing FX rates produce visible warning and confidence reduction.
- no silent empty list on conversion/engine failure.

---

## S9-012 — Subengine exceptions are swallowed into empty analytics

Severity: Medium/High  
Files:
- `AnalyticsViewModel.kt`

Evidence:
Advanced category/merchant/patterns/stats deferred calls catch exceptions and return empty lists/null without a user-visible section error.

Problem:
A broken engine looks like “no data.” This is misleading.

Fix strategy:
Return typed section states.

Implementation:
```kotlin
sealed interface AnalyticsSectionState<out T> {
    data object Loading : AnalyticsSectionState<Nothing>
    data class Ready<T>(val value: T, val warnings: List<UiText> = emptyList()) : AnalyticsSectionState<T>
    data class Empty(val reason: UiText) : AnalyticsSectionState<Nothing>
    data class Error(val message: UiText) : AnalyticsSectionState<Nothing>
}
```

Acceptance:
- engine failure shows section-level error.
- true empty is distinguishable from error.
- tests inject failing engine and verify UI state.

---

## S9-013 — Main Analytics has no explicit top-level Error state/retry

Severity: Medium/High  
Files:
- `AnalyticsViewModel.kt`
- `AnalyticsScreen.kt`

Problem:
`AnalyticsState` is a single data class with `isLoading`; there is no top-level `Error`. If repository/currency/assembler errors escape, the flow can stop or stay stale.

Fix strategy:
Use a sealed UI state for the route:

```kotlin
sealed interface AnalyticsUiState {
    data object Loading : AnalyticsUiState
    data class Ready(val state: AnalyticsState) : AnalyticsUiState
    data class Error(val message: UiText, val lastGoodState: AnalyticsState?) : AnalyticsUiState
}
```

Or minimally add:
```kotlin
error: UiText? = null
```

Acceptance:
- load failure shows retry.
- refresh/retry event exists.
- previous successful state can remain visible with stale/error banner if desired.

---

## S9-014 — Analytics cache is fragile and hard to reason about

Severity: Medium  
Files:
- `AnalyticsViewModel.kt`

Evidence:
Caches:
- `analyticsCache`
- `advancedCache`
- keyed by `PeriodCacheKey`
- invalidated when freshness versions change
- includes `rateTimestamp`

Problem:
The cache can grow across period/rate/category permutations. `categories.hashCode()` and `budgetsHash` are brittle. Debugging stale analytics is difficult.

Fix strategy:
Move caching into an explicit repository/cache with bounded size and tests.

Implementation:
```kotlin
class AnalyticsStateCache(maxEntries: Int = 20) {
    fun get(key: AnalyticsCacheKey): AnalyticsState?
    fun put(key: AnalyticsCacheKey, value: AnalyticsState)
    fun clearForDataVersion(...)
}
```

Acceptance:
- bounded cache.
- cache key test covers category rename/color, budget amount/currency, FX rate timestamp.
- manual refresh clears cache.

---

## S9-015 — Budget-vs-actual budget conversion uses latest rate, not as-of basis

Severity: High financial correctness  
Files:
- `AnalyticsViewModel.kt`
- `BudgetVsActualEngine`

Evidence:
Comment says budget limit conversion currently uses the latest available rate via `currencyConverter.convert()`.

Problem:
Actual expenses are converted as-of expense dates, while budgets are converted using latest rates. This may be acceptable for a standing budget, but it must be explicit and tested. Otherwise Budget tab vs Analytics budget-vs-actual can disagree.

Fix strategy:
Define budget currency conversion policy:
- budget amount uses latest rate for active budget,
- or budget amount uses period start rate,
- or budget amount is stored/normalized in home currency.

Implementation:
- Add `BudgetCurrencyConversionPolicy`.
- Add warnings when budget currency conversion fails.
- Show policy in data-quality metadata.

Acceptance:
- policy documented and tested.
- budget-vs-actual in Analytics agrees with Budget screen for same fixture.
- conversion failure creates visible warning, not empty budget chart.

---

## S9-016 — Period boundary behavior is not contract-tested

Severity: High  
Files:
- `AnalyticsViewModel.kt`
- `AdvancedAnalyticsEngine.kt`
- `TimePeriodUtils`

Evidence:
Main Analytics uses `TimePeriodUtils` for TODAY/WEEK/MONTH/QUARTER/YEAR/ALL.
Previous period uses day-count offset.
Advanced engine has its own `getPeriodRange()` and comments warning about duplicate period logic.
Transactions navigation receives `state.currentDateRange`.

Problem:
Analytics totals, drill-down filters, and Home dashboard totals can disagree if period boundaries differ.

Fix strategy:
Add a shared period contract.

Implementation:
```kotlin
object AnalyticsPeriodResolver {
    fun resolve(period: TimePeriod, now: Long, zoneId: ZoneId): PeriodRange
    fun comparisonFor(range: PeriodRange): PeriodRange
}
```

Acceptance:
- Analytics selected period range equals Home/Transactions expectation.
- tests cover DST, leap year, month start/end, quarter, year, all.
- `TransactionFilter.dateRange` from Analytics exactly matches displayed period.

---

## S9-017 — Analytics debug logging exposes financial data

Severity: High privacy/security  
Files:
- `AnalyticsViewModel.kt`

Evidence:
The ViewModel logs:
- period
- date range
- transaction count
- total
- daily totals
- average daily spend

Problem:
Financial totals and daily spending patterns are sensitive. Logging them unconditionally can violate privacy expectations.

Fix strategy:
Gate or remove detailed analytics debug logs.

Implementation:
```kotlin
if (BuildConfig.DEBUG && analyticsDebugLoggingEnabled) {
    Timber.d(...)
}
```

Better:
- log only counts/status in release;
- redact amounts.

Acceptance:
- release builds do not log amounts/daily totals.
- debug logs require explicit diagnostics flag.
- privacy test/static grep covers `Timber.d` with financial data.

---

## S9-018 — `AnalyticsInputAssembler.build(period)` silently falls back to EUR

Severity: High  
File:
- `AnalyticsInputAssembler.kt`

Evidence:
`homeCurrency().first()` failure uses `getOrDefault("EUR")`.

Problem:
Even if ViewModels are fixed, any caller using `build(period)` can still get EUR. This is a central domain component and must fail closed.

Fix strategy:
Make currency required or return typed failure.

Implementation options:
1. Replace with:
```kotlin
suspend fun build(period: PeriodRange, homeCurrency: String, ...)
```

2. Or:
```kotlin
sealed interface AnalyticsInputResult {
    data class Success(val input: NormalizedAnalyticsInput) : AnalyticsInputResult
    data class CurrencyUnavailable(val reason: Throwable?) : AnalyticsInputResult
}
```

Acceptance:
- no default EUR in assembler.
- all callers explicitly resolve currency.
- tests verify currency failure does not produce normalized EUR input.

---

## S9-019 — Advanced Analytics Dashboard injects unused DAO / unclear data source

Severity: Low/Medium  
File:
- `AdvancedAnalyticsDashboard.kt`

Evidence:
It injects `ExpenseDao` but source path shown uses `ExpenseRepository`.

Problem:
Unused dependencies increase fixture burden and confuse agents about the source of truth.

Fix strategy:
Remove `ExpenseDao` if unused, or document why it is needed.

Acceptance:
- constructor dependencies are minimal.
- tests do not need unused DAO mocks.

---

## S9-020 — Docs/source drift: `SpendingTrendChart` listed but not found in `AnalyticsScreen`

Severity: Medium  
Files:
- `UI_COMPONENT_LIBRARY.md`
- `COMPREHENSIVE_UI_MAP.md`
- `AnalyticsScreen.kt`
- `SpendingTrendChart.kt`

Evidence:
Docs list `SpendingTrendChart` as an Analytics consumer. Source search did not find `SpendingTrendChart` usage in `AnalyticsScreen.kt`.

Problem:
Agents will test/fix a chart that may not be reachable from the Analytics tab.

Fix strategy:
Either:
1. wire `SpendingTrendChart` into Analytics, or
2. update docs to reflect current source.

Recommended:
- If `dailyTotals` or historical series exist, add a trend section.
- Otherwise document it as dashboard/home-only or unused.

Acceptance:
- docs and source agree.
- component usage test or static reference inventory catches future drift.

---

## S9-021 — Chart components use SemanticColors instead of Material theme/status adapters

Severity: Medium  
Files:
- `CategoryDonutChart.kt`
- `SpendingTrendChart.kt`
- `ChartMarker.kt`
- `StatisticalVisualizations.kt`
- `NoSpendStreakWidget.kt`
- `PersonalityProfileCard.kt`

Problem:
Slice 2 identified shared primitive issues with hardcoded semantic colors. Analytics charts also use dark-scheme semantic colors directly. This can hurt light/dark/dynamic theme contrast.

Fix strategy:
Use Material theme or app-level status color adapters.

Acceptance:
- analytics charts render in light/dark theme.
- no direct dark-only semantic colors unless justified.
- component smoke tests cover light/dark.

---

## S9-022 — Many analytics UI strings are hardcoded

Severity: Medium  
Files:
- `AnalyticsScreen.kt`
- `CategoryDonutChart.kt`
- `PersonalityProfileCard.kt`
- `NoSpendStreakWidget.kt`
- `StatisticalVisualizations.kt`

Examples:
- “No category data yet”
- “CATEGORY SPLIT”
- “Why this matches”
- “Coaching tips”
- “Last updated”
- motivational streak messages
- “SPENDING ZONES”
- “Home / Local / Travel”
- various formatted English phrases

Problem:
Not localizable and brittle for UI tests.

Fix strategy:
Move visible strings to resources or `UiText`.

Acceptance:
- no hardcoded visible English in analytics UI except debug-only text.
- tests use resource text or test tags.

---

## S9-023 — Charts lack stable test tags

Severity: Medium  
Files:
- analytics UI/components

Problem:
Compose tests must locate nodes by visible text, which is brittle under localization.

Fix strategy:
Add test tags:
- `analytics_screen`
- `analytics_period_selector`
- `analytics_total_hero`
- `analytics_warnings_card`
- `analytics_category_donut`
- `analytics_category_legend`
- `analytics_budget_vs_actual`
- `analytics_merchant_card_{key}`
- `analytics_personality_card`
- `analytics_histogram`
- `analytics_percentile_grid`
- `analytics_area_spending`
- `analytics_travel_insight`
- `analytics_retry`

Acceptance:
- component tests use tags.
- tags do not include private merchant names unless carefully sanitized.

---

## S9-024 — Statistical visualization components have default EUR and invalid-data risks

Severity: Medium/High  
File:
- `StatisticalVisualizations.kt`

Evidence:
Components default `currency = "EUR"`.
Histogram/chart components format bins and percentages directly.

Problem:
Production callers can forget currency. Invalid values like NaN/Infinity/negative bins can produce bad UI.

Fix strategy:
- make currency required;
- sanitize statistical inputs;
- add empty/error states.

Acceptance:
- no default EUR.
- invalid bins do not crash.
- zero/empty histograms show an empty state.
- tests cover invalid percentile ordering and bins.

---

## S9-025 — Rich merchant card percentage/date formatting is locale-fragile

Severity: Medium  
File:
- `StatisticalVisualizations.kt`

Evidence:
`RichMerchantCard` uses `String.format("%.1f", change)` for price trend and text logic for predicted days.

Problem:
Default locale can alter decimal separators. Tests become locale-dependent.

Fix strategy:
Introduce shared formatters:
- `PercentFormatter`
- `RelativeDateFormatter`

Acceptance:
- tests pass under US and comma-decimal locales.
- no direct `String.format` for user-visible percentages unless locale is explicit and intended.

---

## S9-026 — No clear “same fixture” invariant tying Analytics to Home/Budget/Transactions

Severity: Critical financial correctness  
Files:
- Analytics, Home, Budget, Transactions

Problem:
Even if Analytics is internally correct, it must agree with:
- Home dashboard total
- Transactions filtered list total
- Budget actual spent
- Budget-vs-actual
- cashflow where relevant

Fix strategy:
Add cross-slice financial invariant tests.

Implementation:
Create deterministic fixture:
- fixed `TimeProvider`
- fixed home currency
- fixed FX rates
- expenses in 2 currencies
- deposits/transfers/not-mine/shared cases
- categories/budgets
- location optional

Assertions:
- Analytics currentTotal = Home month total for same period.
- Analytics category totals sum to currentTotal.
- Analytics transaction count = included normalized purchases.
- Analytics donut total = currentTotal.
- Transactions filter from category card returns same category sum.
- Budget-vs-actual actualSpent = Budget screen spent for same category/currency.

Acceptance:
- any raw mixed-currency sum fails.
- any period mismatch fails.

---

## S9-027 — Location analytics in Analytics needs privacy/location contract tests

Severity: Medium/High  
Files:
- `AnalyticsViewModel.kt`
- location engines
- `AnalyticsScreen.kt`

Evidence:
Analytics computes:
- location insights
- area spending
- travel insight

Problem:
Location-based analytics can expose sensitive patterns. It also depends on normalized purchases and location availability.

Fix strategy:
Add contract tests:
- no GPS/location data → no location section.
- privacy/location disabled → no location section or blocked card.
- only normalized/included expenses are used.
- exact coordinates are not displayed in normal UI.
- area/travel totals use home currency.

Acceptance:
- location analytics respects privacy gate from Slice 3/10 policies.
- analytics UI never shows exact coordinates unless debug-gated.

---

## S9-028 — Advanced Analytics error messages are raw strings

Severity: Medium  
Files:
- `AdvancedAnalyticsViewModel.kt`
- `AdvancedAnalyticsScreen.kt`

Evidence:
`AnalyticsUiState.Error(val message: String)` and `e.message ?: "Load failed"`.

Problem:
Not localizable and can leak internal exception messages.

Fix strategy:
Use `UiText` or typed error code.

Implementation:
```kotlin
data class Error(val message: UiText, val debugMessage: String? = null)
```

Acceptance:
- user message is safe/localized.
- debug message is shown only in debug diagnostics.

---

## S9-029 — Main analytics currently logs/handles partial failures inconsistently

Severity: Medium  
Files:
- `AnalyticsViewModel.kt`

Problem:
Some failures are caught and ignored:
- personality classifier failure returns null.
- advanced sub-engine failure returns empty/null.
- budget-vs-actual failure returns empty list/warnings empty.

This prevents agents/users from knowing whether a section is truly empty or broken.

Fix strategy:
Add per-section diagnostic state and debug diagnostics event.

Acceptance:
- failing section emits section error.
- diagnostics screen can inspect failure reason.
- user sees non-technical degraded message.

---

## S9-030 — Analytics UI should not expose raw merchant names in test tags/logs

Severity: Medium privacy/testing  
Files:
- Analytics UI

Problem:
When adding test tags, avoid embedding merchant names or sensitive strings. Use IDs/hashes where possible.

Fix strategy:
```kotlin
testTag("analytics_merchant_card_${merchant.stableHash()}")
```

Acceptance:
- no raw merchant/card/account/location data in tags or logs.
- tests can still locate nodes by controlled fixture tags.

---

# 5. Recommended tests to add

## JVM/domain/ViewModel tests

### `AnalyticsCurrencyInvariantTest`
Required cases:
- USD home currency + EUR purchase converts before summing.
- invalid transaction currency is excluded and warning emitted.
- missing FX rate creates partial warning.
- category totals sum to currentTotal.
- merchant totals sum to currentTotal.
- daily buckets sum to currentTotal.
- warnings include affected count/currency.

### `AnalyticsInputAssemblerTest`
Required cases:
- spendingOnly excludes deposits/transfers/withdrawals according policy.
- excludeNotMine excludes not-mine expenses.
- category name snapshot populated.
- currency failure does not default to EUR after fix.
- excluded expenses preserve reason.

### `AnalyticsViewModelStateTest`
Required cases:
- initial state loading.
- selected period changes range and recomputes.
- category rename/color invalidates relevant state.
- budget change invalidates budget-vs-actual.
- FX rate timestamp invalidates cache.
- repository failure emits top-level or section-level error.
- warnings visible when input partial.

### `AnalyticsPeriodBoundaryTest`
Required cases:
- TODAY range exact local day.
- WEEK range matches app week policy.
- MONTH range exact calendar month.
- QUARTER range exact quarter.
- YEAR range exact year.
- ALL range policy defined.
- previous period policy tested.
- DST/leap-year boundary.

### `AdvancedAnalyticsDashboardCurrencyTest`
Required cases:
- no EUR fallback on currency failure.
- same input as main analytics produces same totalSpent.
- conversion warnings produce confidence PARTIAL/LOW.
- monthly trend includes zero months.
- monthly trend uses java.time boundaries after fix.
- weekly pattern uses explicit zone.

### `AnalyticsAdvancedMigrationTest`
Required cases:
- spending patterns consume `NormalizedAnalyticsInput`.
- statistical insights consume `NormalizedAnalyticsInput`.
- no deprecated API suppressions remain in ViewModel.
- advanced warnings are propagated to UI.

### `BudgetVsActualAnalyticsTest`
Required cases:
- budget limit in non-home currency converted according policy.
- conversion failure surfaces warning.
- actualSpent equals Budget screen adjusted basis for same fixture.
- percent used clamps/handles zero budget.

### `AnalyticsNavigationFilterTest`
Required cases:
- category card emits `TransactionFilter(categoryId, dateRange)`.
- merchant card emits `TransactionFilter(merchantName, dateRange)`.
- filter dateRange matches displayed period.
- null callback disables click semantics if applicable.

### `SpendingPersonalityClassifierNormalizedTest`
Required cases:
- normalized path uses home-currency amounts.
- partial data lowers confidence.
- insufficient data returns confidence 0.
- raw `classify()` either deprecated/tested separately or removed from UI path.

---

## Compose/component tests

### `AnalyticsScreenContentTest`
- loading state.
- top-level error state.
- warning card appears for conversion warnings.
- period selector callback.
- empty analytics state.
- category section click emits filter.
- merchant section click emits filter.
- location section hidden when no data.

### `CategoryDonutChartTest`
- empty state localized.
- USD currency renders USD and not EUR.
- invalid color falls back safely.
- NaN/invalid percentages do not crash.
- animation disabled renders immediately.
- semantics summary includes currency and top categories.

### `SpendingTrendChartTest`
- no-data state.
- required currency after fix.
- compact currency axis formatting.
- semantics summary.
- invalid/empty series does not crash.
- light/dark smoke.

### `StatisticalVisualizationsTest`
- percentile grid renders required currency.
- invalid percentile order handled or rejected.
- histogram empty state.
- histogram invalid bins do not crash.
- rich merchant click callback.
- price trend locale-stable formatting.

### `PersonalityProfileCardTest`
- profile title/confidence semantics.
- explanation/tips empty states.
- localized strings.
- timestamp formatter stable with fixed zone/clock if refactored.

### `AnalyticsWarningsCardTest`
- one warning.
- multiple warnings.
- affected count and source currency displayed.
- warning text uses safe message.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile.
2. Run existing analytics tests.
3. Inventory all remaining EUR fallbacks:

```bash
grep -R '"EUR"' app/src/main/java/com/yourname/expensetracker/ui/screens/analytics \
  app/src/main/java/com/yourname/expensetracker/ui/components \
  app/src/main/java/com/yourname/expensetracker/domain/analytics
```

4. Inventory deprecated analytics calls:

```bash
grep -R '@Suppress("DEPRECATION")\|DEPRECATION' \
  app/src/main/java/com/yourname/expensetracker/ui/screens/analytics \
  app/src/main/java/com/yourname/expensetracker/domain/analytics
```

5. Inventory chart consumers:
```bash
grep -R 'CategoryDonutChart\|SpendingTrendChart\|StatisticalVisualizations' app/src/main/java
```

## Phase B — Add financial contract tests first

Add:
```text
AnalyticsCurrencyInvariantTest.kt
AnalyticsInputAssemblerTest.kt
AnalyticsPeriodBoundaryTest.kt
AdvancedAnalyticsDashboardCurrencyTest.kt
AnalyticsNavigationFilterTest.kt
```

These define correctness before UI refactor.

## Phase C — Fix critical currency/data correctness

1. Remove hidden EUR fallback in main Analytics state.
2. Remove EUR fallback in `AdvancedAnalyticsViewModel`.
3. Remove EUR fallback in `AdvancedAnalyticsDashboard`.
4. Remove EUR fallback in `AnalyticsInputAssembler`.
5. Make chart currency parameters required.
6. Fix `NormalizedExpense.toExpenseSnapshot()` mixed-currency semantics.
7. Add visible data-quality/degraded states.

## Phase D — Migrate advanced analytics

1. Add normalized overloads for spending patterns/statistical insights.
2. Remove deprecated calls from `AnalyticsViewModel`.
3. Share normalized input between main and advanced analytics.
4. Define Advanced Analytics period behavior.

## Phase E — Error/cache/state hardening

1. Add top-level or section-level error states.
2. Add retry/refresh.
3. Replace swallowed exceptions with section errors.
4. Extract bounded cache.
5. Add cache invalidation tests.

## Phase F — UI extraction

Extract:
- `AnalyticsRoute`
- `AnalyticsScreenContent`
- `AnalyticsSummarySection`
- `AnalyticsCategorySection`
- `AnalyticsMerchantSection`
- `AnalyticsTemporalSection`
- `AnalyticsAnomalySection`
- `AnalyticsLocationSection`
- `AnalyticsWarningsCard`

## Phase G — Component fixes

1. `CategoryDonutChart`: currency, localization, invalid data, animation toggle.
2. `SpendingTrendChart`: required currency, theme colors, test tags.
3. `StatisticalVisualizations`: required currency, invalid data, locale-stable formatting.
4. `PersonalityProfileCard` / `NoSpendStreakWidget`: localization + theme cleanup.
5. Add test tags and semantics.

## Phase H — Docs update last

Update:
- `COMPREHENSIVE_UI_MAP.md`
- `UI_COMPONENT_LIBRARY.md`
- `CODEBASE_SEGMENTS.md` if ownership changed

Only update docs after source/tests are green.

---

# 7. Cross-slice golden scenarios after local tests pass

Add only after Slice 9 local tests are green:

1. Manual add expense updates Transactions, Home total, and Analytics current total.
2. Analytics category card opens Transactions with matching filtered total.
3. Analytics merchant card opens Transactions with matching filtered total.
4. Multi-currency month: Home total = Analytics total = Transactions converted total.
5. Budget-vs-actual in Analytics equals Budget screen category spent.
6. Missing FX rate produces warning in Home/Analytics/Budget consistently.
7. Advanced Analytics total equals main Analytics total for same period.
8. Not-mine/shared expense exclusion policy matches Transactions filters.
9. Location analytics hidden or blocked when privacy setting disables location.
10. Dashboard monthly total equals Analytics monthly total and daily bucket sum.

---

# 8. Acceptance checklist for Slice 9 green

Slice 9 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] Analytics currency invariant tests pass.
- [ ] Analytics input assembler tests pass.
- [ ] Analytics period boundary tests pass.
- [ ] Advanced analytics currency tests pass.
- [ ] Chart component tests pass.
- [ ] No hidden EUR fallback remains in production analytics paths.
- [ ] `CategoryDonutChart` no longer hardcodes EUR.
- [ ] Chart components require explicit currency or typed money context.
- [ ] `NormalizedExpense.toExpenseSnapshot()` no longer mixes normalized amount with original currency.
- [ ] Advanced patterns/statistics consume normalized input.
- [ ] Main and Advanced Analytics agree for same period fixture.
- [ ] Conversion/data-quality warnings are visible and section-aware.
- [ ] Analytics debug logs do not expose financial values in release.
- [ ] Analytics ViewModel is split enough for focused tests.
- [ ] Analytics Screen is split enough for component tests.
- [ ] Docs match source.

---

# 9. Agent guardrails

Do:
- Protect currency correctness before UI polish.
- Use fixed `TimeProvider`, fixed `ZoneId`, and fake FX rates.
- Add invariant tests before refactors.
- Treat charts as money displays, not decorative-only UI.
- Surface partial data instead of silently hiding sections.
- Keep transaction-filter navigation deterministic.
- Use test tags that do not leak private merchant/location text.

Do not:
- Rewrite all analytics engines in one PR.
- Persist or display placeholder EUR.
- Let components default currency to EUR.
- Swallow engine exceptions as “no data.”
- Let Advanced Analytics use a different hidden period without labeling it.
- Log spending totals/daily totals in release.
- Trust raw percentages/NaN values in chart rendering.

Main invariant:

> For a fixed clock, fixed home currency, fixed FX rates, and fixed expense fixture, Analytics must use one normalized money basis, expose data-quality loss, agree with Home/Budget/Transactions for the same period, and never display placeholder or hardcoded currency.