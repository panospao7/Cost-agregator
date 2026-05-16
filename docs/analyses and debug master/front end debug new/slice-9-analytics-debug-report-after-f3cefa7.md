# Slice 9 Debug Report — Analytics + Advanced Analytics

Commit reviewed: `f3cefa7111e7cb75264769cf9dde8c2666ed4976`  
Review type: static GitHub source review, not local Gradle/device execution.

Scope:
- `ui/screens/analytics/*`
- `ui/components/analytics/*`
- `domain/analytics/*`
- analytics currency/data-quality flow
- chart totals, category/merchant analytics, drill-through filters, advanced dashboard

Primary commit context:  
https://github.com/panospao7/Cost-agregator/commit/f3cefa7111e7cb75264769cf9dde8c2666ed4976

---

# Executive Summary

Slice 9 is **partially fixed but not closed**.

Good progress:
- `AnalyticsCurrencyNormalizer` is now a central normalization point.
- `AnalyticsInputAssembler` no longer silently defaults to `"EUR"` when resolving home currency.
- `AnalyticsViewModel` blocks analytics computation when `homeCurrency == null`.
- Analytics cache keys include home currency and rate timestamp.
- `conversionWarnings`, `qualityWarnings`, `dataQualityPartial`, and `advancedSectionErrors` exist.
- `AnalyticsScreen` shows a warning card when conversion warnings exist.
- `AnalyticsScreen` has a fallback partial-data warning when `dataQualityPartial == true`.
- `AdvancedAnalyticsEngine` has canonical overloads that accept `NormalizedAnalyticsInput`.
- `PercentileGridCard`, `TransactionHistogramChart`, `CategoryPercentileBadge`, and `RichMerchantCard` now require explicit currency instead of defaulting to EUR.
- Debug logging is `BuildConfig.DEBUG` gated.

Still high-risk:
1. **Top-level analytics errors are not rendered.**
2. **`AnalyticsState.loadableState` never returns `Error`, even when `state.error != null`.**
3. **`AnalyticsScreen` still passes `state.homeCurrency ?: ""` into many money widgets.**
4. **Some analytics composables still have default `"EUR"` currency parameters.**
5. **Budget-vs-actual and enhanced category budget context can still mislabel or double-convert budget amounts.**
6. **Budget snapshot conversion quality is lost before advanced category analytics.**
7. **Advanced section failures are only partially surfaced.**
8. **Several async catches swallow `CancellationException`.**
9. **Raw strings and enum-derived labels remain widespread.**
10. **Analytics UI is still monolithic and ViewModel-coupled.**
11. **Domain tests exist, but Slice 9 UI/ViewModel contract tests are still thin.**

Recommended fix order:
1. Fix top-level error/loading/currency UI contract.
2. Remove all blank-currency and default-EUR paths.
3. Fix budget-vs-actual / category-budget currency quality.
4. Harden advanced section error and cancellation handling.
5. Localize/harden analytics copy.
6. Add ViewModel/UI contract tests.

---

# Confirmed Improvements

## S9-FIX-001 — Central normalized analytics input exists

**Status:** Mostly fixed  
**Files:**
- `AnalyticsCurrencyNormalizer.kt`
- `AnalyticsInputAssembler.kt`
- `NormalizedAnalyticsInput.kt`

`AnalyticsInputAssembler` now builds `NormalizedAnalyticsInput` from raw expenses and carries:
- included normalized expenses,
- excluded expenses,
- conversion warnings,
- confidence penalty/multiplier,
- missing/stale/invalid currency counts.

This is the right foundation.

Remaining:
- not every analytics engine consumes `NormalizedAnalyticsInput`.
- some legacy methods still query repositories and normalize internally.
- some UI paths still format with blank/default currency.

---

## S9-FIX-002 — Home currency fallback improved

**Status:** Partial  
**Files:**
- `AnalyticsViewModel.kt`
- `AdvancedAnalyticsDashboard.kt`
- `AdvancedAnalyticsViewModel.kt`

Good:
- ViewModel no longer silently substitutes `"EUR"` when home currency is unavailable.
- `AnalyticsInputAssembler.build(period)` directly reads `homeCurrency().first()` instead of `getOrDefault("EUR")`.

Remaining:
- UI still uses `state.homeCurrency ?: ""`.
- `AdvancedAnalyticsViewModel` generates dashboard data before checking whether `homeCurrency` is null.
- some model defaults still contain `"EUR"`.

---

## S9-FIX-003 — Data-quality state exists

**Status:** Partial  
**Files:**
- `AnalyticsViewModel.kt`
- `AnalyticsScreen.kt`

State now includes:
- `conversionWarnings`
- `qualityWarnings`
- `latestRateTimestamp`
- `dataQualityPartial`
- `advancedSectionErrors`

UI shows:
- `AnalyticsWarningsCard` if warnings exist.
- a generic partial-data card if only `dataQualityPartial` is true.

Remaining:
- warning copy is partly hardcoded.
- warning is not consistently tied to affected widgets.
- category/merchant rows do not show per-row partial state.
- top-level error still not shown.

---

## S9-FIX-004 — Some chart components require explicit currency

**Status:** Mostly fixed  
**Files:**
- `StatisticalVisualizations.kt`

Good:
- `PercentileGridCard`
- `TransactionHistogramChart`
- `CategoryPercentileBadge`
- `RichMerchantCard`

now require `currency: String`.

Remaining:
- `HourOfDayChartBento` still has default `"EUR"`.
- other analytics helper components may still have default `"EUR"` comments.
- `AnalyticsScreen` still passes blank string when currency is missing.

---

# High-Priority Findings

---

## S9-001 — Top-level analytics error is not rendered

**Severity:** High  
**Files:**
- `AnalyticsViewModel.kt`
- `AnalyticsScreen.kt`

## Problem

When `homeCurrency == null`, the ViewModel emits:

```kotlin
AnalyticsState(
    isLoading = false,
    error = "Home currency not available. Please check your settings."
)
```

But `AnalyticsScreen` does not render `state.error`.

Worse, `AnalyticsState.loadableState` returns:

```kotlin
Loading if isLoading
else Data(this)
```

There is no `Error` branch.

## Impact

If currency settings fail:
- screen exits loading,
- renders empty/zero analytics state,
- uses blank currency,
- user does not see the actual blocking error.

## Fix Strategy

Update `loadableState`:

```kotlin
val loadableState: LoadableUiState<AnalyticsState>
    get() = when {
        isLoading -> LoadableUiState.Loading
        error != null -> LoadableUiState.Error(UiText.DynamicString(error))
        else -> LoadableUiState.Data(this)
    }
```

Better: make `error: UiText?`.

Render shared `ErrorState`.

## Acceptance Tests

- missing home currency shows error state.
- analytics content does not render with blank currency.
- retry action works.
- no `CurrencyFormatter` receives `""`.

---

## S9-002 — Analytics screen still passes blank currency to widgets

**Severity:** High  
**File:** `AnalyticsScreen.kt`

## Problem

Top-level screen does:

```kotlin
val currency = state.homeCurrency ?: ""
```

That `currency` is then passed into many widgets:
- `PercentileGridCard`
- `TransactionHistogramChart`
- `HourOfDayChartBento`
- `CategoryDonutChart`
- `CategoryItem`
- `PlaceInsightCard`
- `AreaSpendingItem`
- `TravelInsightCard`
- `VelocityAnomalyCard`
- `YearOverYearCard`
- etc.

## Impact

During loading/error/divergence, the app can display malformed money strings with no currency.

## Fix Strategy

Use typed currency state:

```kotlin
sealed interface AnalyticsCurrencyUiState {
    data object Loading
    data class Ready(val code: String)
    data class Error(val message: UiText)
}
```

Short-term:

```kotlin
val currency = state.homeCurrency
if (currency == null) {
    AnalyticsCurrencyUnavailableState(...)
    return
}
```

## Acceptance Tests

- loading currency renders skeleton/error, not blank currency.
- ready currency propagates to every money widget.
- no analytics call uses `currency = ""`.

---

## S9-003 — Some analytics components still default to EUR

**Severity:** High  
**Files:**
- `AnalyticsScreen.kt`
- analytics helper composables

## Problem

`HourOfDayChartBento` still declares:

```kotlin
currency: String = "EUR"
```

Comments say it is a placeholder. Slice 9 should not allow placeholder currencies in money-rendering components.

## Fix Strategy

Remove all default `"EUR"` params from analytics UI components.

```kotlin
fun HourOfDayChartBento(
    hourOfDayPattern: List<Pair<Int, Double>>,
    currency: String
)
```

Add static/contract test:
- no `currency: String = "EUR"` in analytics UI.

## Acceptance Tests

- code fails to compile if caller omits currency.
- grep/static test finds no default EUR in analytics components.

---

## S9-004 — Budget-vs-actual conversion can be inconsistent and loses data-quality detail

**Severity:** High  
**Files:**
- `AnalyticsViewModel.kt`
- `BudgetVsActualEngine.kt`
- `BudgetRepository.kt`

## Problem

Analytics budget-vs-actual flow:
1. `BudgetRepository.getActiveBudgetSnapshots()` already converts budget amounts to home currency using latest rate.
2. `AnalyticsViewModel.buildBudgetVsActualItems()` then calls `convertBudgetAmountToHomeCurrency(...)` again.
3. `BudgetVsActualEngine.compute()` compares actual normalized period spend against those budget limits.

If `getActiveBudgetSnapshots()` failed conversion, it returns a snapshot with source currency and a partial warning is lost because `BudgetSnapshot` has no quality field.

Also, comments admit budget conversion uses latest rate, not period-end/period-average rate.

## Impact

Budget-vs-actual can:
- compare period-normalized spend with latest-rate budget limits,
- drop partial conversion quality,
- silently exclude budgets with missing rates,
- show no per-budget warning.

## Fix Strategy

Create explicit budget analytics model:

```kotlin
data class AnalyticsBudgetSnapshot(
    val categoryId: Long?,
    val amount: Double,
    val currency: String,
    val isPartial: Boolean,
    val warning: UiText?,
    val rateBasis: RateBasis
)
```

Convert budget limits at the same rate basis as the analytics period.

Do not convert twice.

## Acceptance Tests

- missing budget conversion produces visible warning.
- budget-vs-actual excludes/marks affected budget, not silently.
- period-end conversion used consistently.
- actual and budget currency basis match.

---

## S9-005 — Enhanced category budget context can mislabel budgets

**Severity:** High  
**Files:**
- `AnalyticsViewModel.kt`
- `AdvancedAnalyticsEngine.kt`
- `BudgetRepository.kt`

## Problem

Enhanced category analytics uses:

```kotlin
val budgetSnapshots = budgetRepository.getActiveBudgetSnapshots()
advancedAnalyticsEngine.getCategoryAnalytics(currentInput, previousInput, categories, budgetSnapshots)
```

Inside `computeCategoryAnalyticsCore`, budget utilization is:

```kotlin
total / budget.amount
```

and rendered with `displayCurrency`.

If `getActiveBudgetSnapshots()` failed conversion, the snapshot may carry a source-currency amount, but advanced analytics has no warning/partial field. The UI can show budget utilization in the home display currency as if it were valid.

## Fix Strategy

Use `AnalyticsBudgetSnapshot` with quality and display currency.

If budget conversion failed:
- do not compute utilization;
- show “budget comparison unavailable” warning.

## Acceptance Tests

- unconvertible budget does not show misleading utilization.
- category row shows conversion warning.
- home-currency budget still works.

---

## S9-006 — Advanced section failures are only partially surfaced

**Severity:** Medium/High  
**Files:**
- `AnalyticsViewModel.kt`
- `AnalyticsScreen.kt`

## Problem

ViewModel collects `advancedSectionErrors`, but UI only visibly renders a fallback for missing statistics:

```kotlin
advancedSectionErrors["statistics"] -> "Statistical insights unavailable"
```

Category, merchant, pattern, and other advanced section errors are not consistently shown.

## Impact

A section can silently disappear. User cannot distinguish:
- no data,
- section failed,
- section disabled,
- conversion issue.

## Fix Strategy

Create common component:

```kotlin
AnalyticsSectionUnavailableCard(section: AnalyticsSection, message: UiText)
```

Render for:
- statistics,
- categories,
- merchants,
- patterns,
- location,
- personality,
- budget-vs-actual.

## Acceptance Tests

- category engine failure renders category-section error.
- merchant engine failure renders merchant-section error.
- spending-pattern failure renders section error.
- legitimate empty state is distinct from failure.

---

## S9-007 — Analytics catches swallow `CancellationException`

**Severity:** Medium/High  
**Files:**
- `AnalyticsViewModel.kt`
- `AdvancedAnalyticsViewModel.kt`
- `AdvancedAnalyticsEngine.kt`

## Problem

Several blocks use:

```kotlin
catch (e: Exception)
```

for analytics sub-engines and ViewModel loads.

This can catch `CancellationException`, especially in:
- `AdvancedAnalyticsViewModel`
- section `async` awaits
- personality classifier block
- advanced engine fallbacks

## Impact

When user changes period quickly:
- old work may not cancel cleanly;
- stale results/errors may update state;
- tests with `flatMapLatest` can be flaky.

## Fix Strategy

Always rethrow cancellation:

```kotlin
catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ...
}
```

## Acceptance Tests

- fast period changes cancel old analytics work.
- old canceled section does not emit error.
- stale result cannot overwrite current period.

---

## S9-008 — Load failure inside `computeAnalyticsInternal` has no top-level catch

**Severity:** High  
**File:** `AnalyticsViewModel.kt`

## Problem

Source flows have `.catch`, but the `flatMapLatest` compute body itself does not visibly have a top-level catch around `computeAnalyticsInternal`.

If any non-section computation throws, the state flow may:
- remain at loading,
- terminate collection,
- or emit no user-visible error.

## Fix Strategy

Wrap compute:

```kotlin
try {
    val result = computeAnalyticsInternal(...)
    emit(result)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    emit(AnalyticsState(
        isLoading = false,
        selectedPeriod = period,
        homeCurrency = homeCurrency,
        error = ...
    ))
}
```

Use `UiText`.

## Acceptance Tests

- repository exception shows analytics error.
- failure clears loading.
- retry or data change recovers.

---

## S9-009 — Category percentages are based only on included/converted transactions

**Severity:** Medium  
**Files:**
- `AnalyticsViewModel.kt`
- `AnalyticsModels.kt`
- `AnalyticsScreen.kt`

## Problem

`categoryBreakdown.percentage` uses:

```kotlin
total / currentTotal
```

where `currentTotal` only includes successfully normalized transactions.

This is mathematically valid for included data, but the UI does not explain that excluded transactions may alter the real percentages.

`AnalyticsCategoryBreakdown` has:
- `isPartial`
- `warningMessage`

but ViewModel leaves them default.

## Fix Strategy

If current input is partial:
- mark every breakdown as partial, or
- mark only affected categories if excluded expenses carry category ID.

```kotlin
isPartial = currentInput.dataQuality.isPartial
warningMessage = ...
```

## Acceptance Tests

- missing exchange rate marks category chart partial.
- chart accessibility mentions partial data.
- percentages still sum to ~100 for included data.

---

## S9-010 — Budget-vs-actual section has no partial/error UI

**Severity:** Medium/High  
**Files:**
- `AnalyticsScreen.kt`
- `AnalyticsViewModel.kt`

## Problem

`buildBudgetVsActualItems()` returns warnings, but the chart items themselves do not carry:
- partial flag,
- excluded budget count,
- warning message.

If conversion fails for one budget, it may be omitted or only represented in a generic warning card.

## Fix Strategy

Add:

```kotlin
data class BudgetVsActualUiState(
    val items: List<BudgetVsActualItem>,
    val isPartial: Boolean,
    val warnings: List<AnalyticsConversionWarning>
)
```

Render section-level warning.

## Acceptance Tests

- one failed budget conversion shows budget section partial warning.
- omitted budgets count is visible.
- chart does not imply complete coverage.

---

## S9-011 — AdvancedAnalyticsViewModel computes dashboard data before validating currency

**Severity:** Medium  
**File:** `AdvancedAnalyticsViewModel.kt`

## Problem

Current flow:
1. call `analyticsDashboard.generateDashboardData(...)`
2. then check:
   ```kotlin
   val resolvedCurrency = homeCurrency ?: throw ...
   ```

But `generateDashboardData()` itself resolves home currency again internally.

## Impact

- duplicated home currency subscriptions;
- wasted work if currency is unavailable;
- possible divergence between `homeCurrency` passed to UI and dashboard’s internal display currency.

## Fix Strategy

Resolve currency first and pass it into dashboard generation:

```kotlin
if (homeCurrency == null) emit(Error(...))
else analyticsDashboard.generateDashboardData(start, end, homeCurrency)
```

Refactor dashboard API:

```kotlin
generateDashboardData(startDate, endDate, displayCurrency)
```

## Acceptance Tests

- null currency does not query dashboard data.
- UI currency equals dashboard data currency.
- currency failure is visible.

---

## S9-012 — AdvancedAnalyticsDashboard still uses deprecated/unstable time handling

**Severity:** Medium  
**File:** `AdvancedAnalyticsDashboard.kt`

## Problem

Comments still mark `Calendar` usage with `A18` TODOs. Monthly and weekly pattern calculations use `Calendar.getInstance()` directly.

## Impact

- timezone/DST-dependent tests can flap;
- behavior may differ from `TimePeriodUtils` / injected clock policy;
- period boundaries can drift from main analytics screen.

## Fix Strategy

Use `TimeProvider` + `java.time` consistently.

## Acceptance Tests

- DST boundary month trend stable.
- fake time controls dashboard output.
- weekly pattern day mapping consistent with main analytics.

---

## S9-013 — Analytics copy is still raw/stringly in several components

**Severity:** Medium  
**Files:**
- `AnalyticsScreen.kt`
- `PersonalityProfileCard.kt`
- `StatisticalVisualizations.kt`

Examples:
- `"Some data may be incomplete due to missing exchange rates."`
- `"Why this matches"`
- `"Coaching tips"`
- `"Last updated ..."`
- enum display via `.name.lowercase().replaceFirstChar`
- hardcoded confidence/accessibility strings.

## Fix Strategy

Move user-facing copy to resources or `UiText`.

Avoid enum-name rendering; use explicit label resources.

## Acceptance Tests

- no hardcoded user-facing analytics strings in UI components.
- personality type labels use resources.
- section unavailable messages localized.

---

## S9-014 — Analytics UI remains monolithic and ViewModel-coupled

**Severity:** Medium  
**File:**
- `AnalyticsScreen.kt`

## Problem

`AnalyticsScreen`:
- uses `hiltViewModel()` default,
- collects state,
- handles initial route period,
- renders all sections,
- owns navigation filter callbacks,
- contains many nested chart/card helpers.

## Impact

Hard to test:
- loading/error/partial states,
- individual section failures,
- drill-down filter correctness,
- currency loading.

## Fix Strategy

Split:

```text
AnalyticsRoute.kt
AnalyticsScreen.kt
AnalyticsSectionRenderer.kt
AnalyticsCharts.kt
AnalyticsCallbacks.kt
AnalyticsUiState.kt
```

Route owns:
- Hilt VM,
- state collection,
- initial period effect,
- navigation events.

Screen takes pure state + callbacks.

## Acceptance Tests

- `AnalyticsScreen` renders fake error state without Hilt.
- section failure cards can be tested with fake state.
- drill-down callback emits correct `TransactionFilter`.

---

## S9-015 — Transaction drill-down filters are incomplete

**Severity:** Medium  
**File:**
- `AnalyticsScreen.kt`

## Problem

Drill-down from category/merchant passes:
- categoryId or merchantName
- dateRange

But does not preserve:
- normalized amount filter intent,
- ownership policy,
- transaction type,
- source/currency quality.

This may be acceptable for basic navigation, but it should be a contract.

## Fix Strategy

Define:

```kotlin
AnalyticsDrillDownIntent.Category(...)
AnalyticsDrillDownIntent.Merchant(...)
```

Map to `TransactionFilter` centrally.

## Acceptance Tests

- category click opens Transactions with category + date range.
- merchant click opens merchant + date range.
- active filter chip matches analytics source.
- filter survives route clear/apply semantics from Slice 5.

---

## S9-016 — Domain tests exist, but Slice 9 UI/ViewModel contract tests are missing

**Severity:** High / test gap  
Files:
- test tree

Good domain coverage appears to exist:
- `AnalyticsCurrencyNormalizerTest`
- `AdvancedAnalyticsEngine*`
- `InsightsEngine*`
- `SpendingPace*`
- etc.

Missing or insufficient:
- `AnalyticsViewModelCurrencyStateTest`
- `AnalyticsViewModelErrorStateTest`
- `AnalyticsBudgetVsActualCurrencyTest`
- `AnalyticsSectionErrorUiTest`
- `AnalyticsDrillDownFilterTest`
- `AnalyticsScreenPartialDataTest`

---

# Implementation Plan for Agent

## Phase 1 — Fix analytics load/error/currency contract

Files:
- `AnalyticsState`
- `AnalyticsViewModel.kt`
- `AnalyticsScreen.kt`

Steps:
1. Convert `error: String?` to `UiText?`.
2. Make `loadableState` return `Error` when error exists.
3. Add top-level catch around `computeAnalyticsInternal`.
4. Stop rendering content when currency is null.
5. Remove `state.homeCurrency ?: ""`.

Acceptance:
- no blank currency formatting.
- currency failure shows retryable error.

---

## Phase 2 — Remove default EUR from analytics UI

Files:
- `AnalyticsScreen.kt`
- analytics components

Steps:
1. Remove `currency: String = "EUR"` defaults.
2. Add static test/grep contract.
3. Make all money widgets require explicit nonblank currency.

Acceptance:
- compile fails if currency omitted.
- no default EUR in analytics components.

---

## Phase 3 — Fix budget-vs-actual and category budget quality

Files:
- `BudgetRepository.kt`
- `AnalyticsViewModel.kt`
- `BudgetVsActualEngine.kt`
- `AdvancedAnalyticsEngine.kt`

Steps:
1. Introduce `AnalyticsBudgetSnapshot`.
2. Preserve conversion warning/partial state.
3. Use period-consistent conversion.
4. Do not double-convert budgets.
5. Render budget section partial warnings.

Acceptance:
- failed budget conversion is visible.
- no misleading budget utilization.

---

## Phase 4 — Advanced section resilience

Files:
- `AnalyticsViewModel.kt`
- `AnalyticsScreen.kt`
- `AdvancedAnalyticsViewModel.kt`

Steps:
1. Rethrow `CancellationException`.
2. Add common section-error UI.
3. Resolve currency before dashboard generation.
4. Make advanced dashboard accept explicit currency.

Acceptance:
- section failures visible.
- cancellation does not become error.
- old period result cannot overwrite new.

---

## Phase 5 — UI extraction/localization

Files:
- `AnalyticsScreen.kt`
- `PersonalityProfileCard.kt`
- `StatisticalVisualizations.kt`

Steps:
1. Extract route from stateless screen.
2. Move strings to resources.
3. Replace enum `.name` display with label resources.
4. Add Compose tests.

---

# Recommended Tests

## `AnalyticsViewModelCurrencyStateTest`
- null currency emits error.
- no analytics computation runs with null currency.
- ready USD computes with USD.
- no state has blank currency in money fields.

## `AnalyticsViewModelErrorStateTest`
- repository failure emits `LoadableUiState.Error`.
- loading clears on failure.
- retry/data change recovers.

## `AnalyticsBudgetVsActualCurrencyTest`
- budget and actual use same currency basis.
- missing budget conversion shows section warning.
- no double conversion.
- unconvertible budget not shown as valid utilization.

## `AnalyticsSectionErrorUiTest`
- statistics failure card renders.
- category failure card renders.
- merchant failure card renders.
- empty data is distinct from failure.

## `AnalyticsDrillDownFilterTest`
- category row emits correct `TransactionFilter`.
- merchant row emits correct `TransactionFilter`.
- date range matches selected period.
- active filter chip matches route intent in Transactions.

## `AnalyticsCancellationTest`
- rapid period changes cancel old work.
- canceled section does not emit error.
- old result cannot update state.

## `AnalyticsScreenPartialDataTest`
- partial conversion warning renders.
- category chart marks partial data.
- warning is accessible.

---

# Final Severity Table

| ID | Severity | Summary |
|---|---:|---|
| S9-001 | High | Top-level analytics error is not rendered |
| S9-002 | High | Blank currency is passed into many widgets |
| S9-003 | High | Some analytics composables still default to EUR |
| S9-004 | High | Budget-vs-actual conversion/quality inconsistent |
| S9-005 | High | Enhanced category budget context can mislabel budgets |
| S9-006 | Med/High | Advanced section failures only partially surfaced |
| S9-007 | Med/High | Cancellation can be swallowed by broad catches |
| S9-008 | High | Main compute path lacks top-level error catch |
| S9-009 | Medium | Category percentages lack partial-data row state |
| S9-010 | Med/High | Budget-vs-actual has no section-level partial/error UI |
| S9-011 | Medium | Advanced dashboard resolves currency twice / late |
| S9-012 | Medium | Advanced dashboard still has Calendar/time TODOs |
| S9-013 | Medium | Analytics UI copy still raw/stringly |
| S9-014 | Medium | Analytics screen monolithic/ViewModel-coupled |
| S9-015 | Medium | Drill-down filter contract incomplete |
| S9-016 | High | Missing ViewModel/UI contract tests |

---

# Immediate Agent Task List

## Task A — Fix top-level error/currency UI
No blank currency, no silent Data state when `error != null`.

## Task B — Remove analytics default EUR
All money-rendering analytics components require explicit currency.

## Task C — Fix budget-vs-actual currency quality
Preserve budget conversion warnings and use period-consistent rate basis.

## Task D — Surface all section failures
Render category/merchant/pattern/statistics section errors consistently.

## Task E — Add tests
Start with currency/error state, budget-vs-actual, section errors, and drill-down filters.

---

# Sources Reviewed

- Latest shared commit context: https://github.com/panospao7/Cost-agregator/commit/f3cefa7111e7cb75264769cf9dde8c2666ed4976
- `AnalyticsScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt
- `AnalyticsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt
- `AdvancedAnalyticsScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsScreen.kt
- `AdvancedAnalyticsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AdvancedAnalyticsViewModel.kt
- `AnalyticsCurrencyNormalizer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt
- `AnalyticsInputAssembler.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt
- `NormalizedAnalyticsInput.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt
- `AdvancedAnalyticsEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt
- `AdvancedAnalyticsDashboard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt
- `BudgetVsActualEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/analytics/BudgetVsActualEngine.kt
- `StatisticalVisualizations.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/components/analytics/StatisticalVisualizations.kt
- `PersonalityProfileCard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/components/analytics/PersonalityProfileCard.kt
- Analytics test tree: https://github.com/panospao7/Cost-agregator/tree/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/test/java/com/yourname/expensetracker/domain/analytics