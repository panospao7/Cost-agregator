# Slice 4 Debug Report — Home / Dashboard Shell

Commit reviewed: `ea3f716eebba8c513edeeba40db394c10ca829cb`  
Review type: static GitHub source review, not local Gradle/device execution.

Scope:
- `ui/screens/home/HomeScreen.kt`
- `ui/screens/home/HomeViewModel.kt`
- `ui/mappers/DashboardWidgetUiMapper.kt`
- dashboard/home widgets:
  - `TotalsDashboardCard`
  - `BudgetBlockPartyCard`
  - `FinancialWeatherCard`
  - `FinancialRunwayCard`
  - `FinancialStressForecastCard`
  - `MonteCarloForecastCard`
  - `RecommendationCard`
  - dashboard chart/period/category components
- relevant currency/dashboard prior findings from the frontend debug docs.

---

# Executive Summary

Slice 4 is **partially fixed but still risky**.

Good progress:
- Home dashboard state is centralized in `HomeViewModel`.
- Dashboard rendering is driven by a `DashboardWidget` sealed model.
- Widget config visibility/order is respected through `dashboardRepository.configFlow`.
- Widget styles are separated through `WidgetStyleRepository`.
- Recommendation navigation now clears `_selectedRecommendation` when resolver returns `NoOp` or throws. This appears to address a known S4 navigation-hang issue.
- `DashboardWidgetUiMapper` exists for domain-to-UI mapping of budget-block-party day status.
- `TotalsDashboardCard` now accepts explicit `currency`.
- `RecommendationCard` has accessibility descriptions and dismiss semantics.

High-risk unresolved issues:
1. Dashboard widgets can still be silently dropped when config IDs drift from computed widget IDs.
2. Widget IDs are still string-based and split across `HomeViewModel.getWidgetId`, dashboard config, `StyledWidgets`, and UI assumptions.
3. Currency/data-quality warnings from `MoneyAggregate` are still not surfaced in dashboard UI contracts.
4. `HomeViewModel` still uses `TotalsAggregationEngine` for totals drilldown, and the existing debug docs say that engine raw-sums mixed-currency totals.
5. Home currency still has `"EUR"` as initial placeholder state.
6. Period drilldown has state consistency risks, especially around DAY leaf clicks and parent/breadcrumb behavior.
7. Widget order/visibility mutations are read-modify-write operations without serialization.
8. Category trend loading can race and stale-write because `loadCategoryTrends()` launches internally and is called from multiple places.
9. `HomeScreen` is too large and still contains a lot of widget-specific navigation/rendering logic, making targeted tests difficult.

Recommended fix order:
1. Add a canonical dashboard widget registry/metadata contract.
2. Fix currency/data-quality propagation.
3. Replace raw totals drilldown aggregation.
4. Harden ViewModel concurrency and period drilldown state.
5. Extract widget rendering into testable components.
6. Add contract tests before large UI refactors.

---

# Status of Previously Known Slice 4 Findings

## S4-PREV-001 — Widget ID/config/style mapping can drift

**Status:** Unresolved.

Evidence:
- `HomeViewModel.getWidgetId(widget)` is the current widget-ID authority.
- `StyledWidgets.all` is a separate style-capability registry.
- Dashboard config IDs come from `DashboardRepository`.
- `HomeViewModel.dashboard` maps config to widgets with:

```kotlin
configList
    .filter { it.isVisible || editMode }
    .mapNotNull { conf -> compiledData.allWidgets.find { w -> getWidgetId(w) == conf.id } }
```

Problem:
- Unknown config IDs are silently ignored.
- Computed widgets absent from config are silently omitted.
- Styleable widgets are determined elsewhere.

Fix:
Create one canonical `DashboardWidgetMetaRegistry`.

---

## S4-PREV-002 — Recommendation navigation errors should not hang dashboard

**Status:** Mostly resolved.

Evidence:
`HomeViewModel.navigateToRecommendation()` now:
- sets `_selectedRecommendation`,
- resolves navigation target,
- clears selection if resolver returns `NavigationAction.NoOp`,
- clears selection if resolver throws.

Remaining issue:
On successful navigation, `_selectedRecommendation` is not cleared after `_navigationActions.emit(action)`. If the UI uses this selected state for a dialog/detail surface, it may remain stale.

Recommendation:
Clear selected recommendation after successful navigation unless it is intentionally used as persistent detail state.

---

## S4-PREV-003 — Dashboard must not silently hide mixed-currency partial totals

**Status:** Unresolved.

Evidence from prior debug docs:
- The currency/dashboard pipeline improved with `MoneyAggregate`.
- But downstream dashboard/analytics frequently keeps only `.displayAmount`.
- Current `DashboardState` still exposes `totalSpent: Double`.
- Widgets appear to use primitive `Double` totals.

Impact:
Dashboard can display a clean-looking number even when conversion failures made the result partial.

Fix:
Dashboard widget models need data-quality fields:
- `isPartial`
- `conversionWarning`
- `sourceBuckets`
- `failedCurrencyCount`

Long-term: pass `MoneyAggregate` or a UI wrapper, not only `Double`.

---

## S4-PREV-004 — Totals dashboard uses raw mixed-currency aggregation

**Status:** Unresolved / high risk.

Evidence:
`HomeViewModel` still injects and uses `TotalsAggregationEngine` for:
- `loadTotalsForYear`
- `drillDownToPeriod`
- `drillUp`
- `loadCategoryBreakdownForPeriod`
- `loadCategoryBreakdownForCurrentPeriod`

Prior debug docs say `TotalsAggregationEngine` raw-sums mixed-currency DAO totals and does not normalize.

Impact:
The dashboard totals card can disagree with other currency-aware dashboard widgets.

Fix:
Refactor totals drilldown to use a currency-aware totals provider.

---

## S4-PREV-005 — Dashboard home currency must not fall back to fake EUR

**Status:** Partially unresolved.

Evidence:
`HomeViewModel.homeCurrency` uses:

```kotlin
currencySettingsRepository.homeCurrency()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "EUR")
```

Comment says it is a placeholder, but the UI can render with `"EUR"` before real settings emit.

Impact:
Transient wrong currency display. In tests or slow repository startup this can become visible.

Fix:
Use nullable/loading state:

```kotlin
val homeCurrency: StateFlow<String?> = ...
```

or expose:

```kotlin
data class CurrencyUiState(
    val code: String? = null,
    val isLoading: Boolean = true
)
```

Dashboard widgets should render loading/skeleton until real currency is loaded.

---

## S4-PREV-006 — Dashboard widget config mutations are async/safe

**Status:** Partially unresolved.

Evidence:
`moveWidget()` and `toggleWidgetVisibility()` perform read-modify-write operations directly in separate coroutines.

Risk:
Rapid clicks can lose updates because two operations can read the same old config and overwrite each other.

Fix:
Serialize dashboard config mutations with a `Mutex` or repository-level transactional update API.

---

## S4-PREV-007 — Dashboard widget unknown IDs should fail visibly

**Status:** Unresolved.

Current behavior uses `mapNotNull`, so unknown IDs disappear.

Fix:
In debug/test builds, expose unknown IDs as an error widget or fail tests. In production, optionally filter but log a structured warning.

---

# New / Remaining Issues Found

---

## S4-001 — Dashboard widgets can be silently dropped by config/widget drift

**Severity:** High  
**Files:**
- `HomeViewModel.kt`
- `DashboardRepository`
- `ComputeDashboardWidgetsUseCase`
- widget config tests

## Problem

The dashboard only renders widgets that exist in both:
1. `dashboardRepository.configFlow`
2. `compiledData.allWidgets`

The join is string-based and uses `mapNotNull`.

If config has an ID that no computed widget exposes, it is silently dropped.  
If the use case computes a new widget but config has not been migrated to include it, it is silently absent.

## Fix Strategy

Create canonical metadata:

```kotlin
@JvmInline
value class DashboardWidgetId(val value: String)

data class DashboardWidgetMeta(
    val id: DashboardWidgetId,
    val titleRes: Int,
    val defaultVisible: Boolean,
    val defaultOrder: Int,
    val fullSpan: Boolean,
    val styleable: Boolean
)
```

Add registry:

```kotlin
object DashboardWidgetMetaRegistry {
    val all: List<DashboardWidgetMeta> = listOf(...)
    fun idFor(widget: DashboardWidget): DashboardWidgetId = when (widget) { ... }
}
```

Then:
- Dashboard config defaults derive from this registry.
- `StyledWidgets` derives from this registry.
- `HomeViewModel.getWidgetId()` is removed or delegates to registry.
- Tests assert computed widgets, config widgets, and style widgets are all known.

## Acceptance Tests

Add `DashboardWidgetMetaContractTest`:
- every computed widget has a registry entry,
- every default config ID exists in registry,
- every styleable ID exists in registry,
- every registry ID has render coverage,
- no duplicate widget IDs.

---

## S4-002 — Widget move boundaries use visible widget index but mutation uses full config index

**Severity:** Medium/High  
**File:** `HomeViewModel.kt`

## Problem

In `HomeScreen`, move buttons are enabled based on the rendered widget index:

```kotlin
onMoveUp = if (index > 0) ...
onMoveDown = if (index < state.widgets.lastIndex) ...
```

But `moveWidget()` swaps entries in the full dashboard config list.

If hidden widgets exist in the full config between visible widgets, moving a visible widget can swap with a hidden widget and appear to do nothing.

## Fix Strategy

When not in edit mode:
- either do not allow moves, or
- move among visible widgets only.

When in edit mode:
- render all widgets and move against full config.

Preferred:
Move logic should accept the same rendered ordered ID list:

```kotlin
fun moveWidgetWithinVisibleOrder(widgetId: DashboardWidgetId, renderedOrder: List<DashboardWidgetId>, direction: MoveDirection)
```

Better:
Make edit mode the only place where order changes.

## Acceptance Tests

- Hidden widget between two visible widgets does not cause visible move to no-op.
- Move up/down updates visible order predictably.
- Hidden widget order remains stable.

---

## S4-003 — Widget config read-modify-write operations can race

**Severity:** Medium  
**File:** `HomeViewModel.kt`

## Problem

`moveWidget()` and `toggleWidgetVisibility()` each launch a coroutine and do:
1. read config,
2. mutate config,
3. save config.

Rapid UI events can interleave.

## Fix Strategy

Add a mutation mutex:

```kotlin
private val widgetConfigMutex = Mutex()

private suspend fun updateDashboardConfig(
    transform: (List<DashboardWidgetConfig>) -> List<DashboardWidgetConfig>
) {
    widgetConfigMutex.withLock {
        val current = dashboardRepository.getDashboardConfig()
        dashboardRepository.saveDashboardConfig(transform(current))
    }
}
```

Or better, add repository-level atomic update:

```kotlin
dashboardRepository.updateDashboardConfig { current -> ... }
```

## Acceptance Tests

- Two rapid toggles end in the expected final state.
- Rapid move-up/move-down does not corrupt order.
- Save failure surfaces a UI error.

---

## S4-004 — Home currency can render as fake EUR before real settings load

**Severity:** Medium/High  
**Files:**
- `HomeViewModel.kt`
- `HomeScreen.kt`
- `TotalsDashboardCard.kt`
- dashboard money widgets

## Problem

`homeCurrency` starts as `"EUR"`.

This is a placeholder, not real user state.

## Impact

Dashboard can momentarily render:
- totals,
- financial weather,
- trend charts,
- forecast cards,
- recent transactions,

with the wrong currency symbol/code.

## Fix Strategy

Expose a loading currency state:

```kotlin
sealed interface HomeCurrencyUiState {
    data object Loading : HomeCurrencyUiState
    data class Ready(val code: String) : HomeCurrencyUiState
    data class Error(val message: UiText) : HomeCurrencyUiState
}
```

Do not render money widgets until `Ready`.

Short-term:
Use `""` or `null` instead of `"EUR"` and render skeleton/placeholder.

## Acceptance Tests

- Before repository emits, dashboard money widgets do not show EUR.
- After repository emits USD, all dashboard widgets show USD.
- Repository failure shows visible error/degraded state.

---

## S4-005 — Totals dashboard still uses raw aggregation engine

**Severity:** High  
**Files:**
- `HomeViewModel.kt`
- `TotalsAggregationEngine.kt`
- `TotalsDashboardCard.kt`

## Problem

`HomeViewModel` uses `TotalsAggregationEngine` for monthly/weekly/daily/yearly totals and category breakdown.

The existing pipeline debug report explicitly identifies that engine as raw-summing mixed-currency totals.

## Impact

Dashboard widgets can disagree:
- safe-to-spend may use currency-aware totals,
- totals dashboard may raw-sum,
- analytics may use a different normalizer.

Example:
- `100 USD + 100 JPY` might display as `200 EUR/USD` equivalent in totals drilldown if raw summed.

## Fix Strategy

Create a currency-aware totals provider:

```kotlin
interface DashboardTotalsProvider {
    fun monthlyTotals(year: Int, currency: String): Flow<List<PeriodTotalUi>>
    fun weeklyTotals(year: Int, month: Int, currency: String): Flow<List<PeriodTotalUi>>
    fun dailyTotals(startMs: Long, endMs: Long, currency: String): Flow<List<PeriodTotalUi>>
    fun categoryBreakdown(startMs: Long, endMs: Long, currency: String): Flow<CategoryBreakdownUi>
}
```

It should use:
- `MultiCurrencyRepository`
- transaction-date conversion where appropriate,
- `MoneyAggregate` warnings.

## Acceptance Tests

- Mixed-currency monthly totals are normalized.
- Conversion failure shows warning.
- Totals dashboard and analytics summary agree for same period/currency.
- No production Home dashboard code calls raw total DAO methods.

---

## S4-006 — MoneyAggregate/data-quality is not carried into dashboard widget UI

**Severity:** High  
**Files:**
- `ComputeDashboardWidgetsUseCase.kt`
- `DashboardWidget` models
- `HomeViewModel.kt`
- dashboard cards

## Problem

Prior debug docs show `.displayAmount` is extracted and warning metadata is lost.

Current `DashboardState` still has primitive `Double` totals.

## Fix Strategy

Introduce:

```kotlin
data class MoneyDisplayUi(
    val amount: Double,
    val currency: String,
    val isPartial: Boolean,
    val warning: UiText?,
    val sourceBuckets: List<SourceCurrencyBucketUi> = emptyList()
)
```

Use this in widgets:
- SafeToSpend
- SpendingTrend
- FinancialWeather
- FinancialRunway
- TotalsDashboard
- MonteCarloForecast
- FinancialStressForecast
- TopCategories
- BudgetHealth

Render warning chip:

```kotlin
if (money.isPartial) {
    DataQualityWarningChip(money.warning)
}
```

## Acceptance Tests

- Missing GBP rate causes dashboard warning, not silent partial total.
- Warning appears once per relevant widget.
- Data-quality state survives ViewModel mapping.

---

## S4-007 — Period drilldown DAY leaf click can mutate breadcrumb/state unexpectedly

**Severity:** Medium  
**File:** `HomeViewModel.kt`

## Problem

`drillDownToPeriod()` handles `PeriodType.DAY` as:

```kotlin
PeriodType.DAY -> arrayOf(PeriodType.DAY, listOf(period), emptyList())
```

Then it still updates:

```kotlin
selectedPeriod = period
breadcrumb = state.breadcrumb + listOfNotNull(state.selectedPeriod)
```

Clicking a DAY period repeatedly can keep appending prior selected periods to breadcrumb or keep reselecting a leaf.

## Fix Strategy

Define DAY behavior explicitly:

Option A — DAY is leaf:
```kotlin
if (period.periodType == PeriodType.DAY) {
    emitNavigationToTransactionsForDay(period)
    return
}
```

Option B — DAY is selectable but not drillable:
```kotlin
if (period.periodType == PeriodType.DAY) {
    _totalsDrillDownState.update { it.copy(selectedPeriod = period) }
    return
}
```

Do not append breadcrumb on leaf reselection.

## Acceptance Tests

- Clicking DAY twice does not grow breadcrumb.
- DAY click either navigates to transactions or only updates selected day.
- Back/up behavior from DAY is deterministic.

---

## S4-008 — Parent/breadcrumb state in totals drill-up needs verification

**Severity:** Medium  
**File:** `HomeViewModel.kt`

## Problem

`drillUp()` uses:

```kotlin
val parent = state.parentPeriod
```

But `drillDownToPeriod()` visibly updates `breadcrumb` and `selectedPeriod`, not an explicit `parentPeriod`.

If `parentPeriod` is a derived property from `breadcrumb`, this may be okay.  
If it is a stored field, it is not being updated.

## Fix Strategy

Make drilldown state explicit:

```kotlin
data class PeriodDrillDownState(
    val currentLevel: PeriodType,
    val selectedPeriod: PeriodTotal?,
    val parentStack: List<PeriodTotal>,
    ...
)
```

On drill down:
```kotlin
parentStack = state.parentStack + period
```

On drill up:
```kotlin
parentStack = state.parentStack.dropLast(1)
```

Avoid mixing `breadcrumb`, `selectedPeriod`, and `parentPeriod` unless clearly derived.

## Acceptance Tests

- Year → Month → Week → Day → Up restores Week.
- Week → Up restores Month.
- Month → Up restores Year.
- Breadcrumb labels match current level.

---

## S4-009 — Initial totals load is UI-owned and can use real clock instead of injected `TimeProvider`

**Severity:** Medium  
**File:** `HomeScreen.kt`

## Problem

`HomeScreen` does:

```kotlin
LaunchedEffect(Unit) {
    val currentYear =
        if (state.referenceNowMillis > 0L) TimePeriodUtils.getYear(state.referenceNowMillis)
        else Calendar.getInstance().get(Calendar.YEAR)

    viewModel.loadTotalsForYear(currentYear)
}
```

At first composition, `state.referenceNowMillis` may still be `0`, so the UI falls back to `Calendar.getInstance()` instead of the injected `TimeProvider`.

## Impact

- Tests using fake time can fail/flap.
- Around year boundary, dashboard widgets and totals drilldown can load different years.
- UI owns initialization that belongs in the ViewModel.

## Fix Strategy

Move initial totals load into `HomeViewModel.init`, using `timeProvider`.

```kotlin
init {
    loadTotalsForYear(TimePeriodUtils.getYear(timeProvider.now()))
}
```

Or expose a `start()` method called once with no fallback to system clock.

## Acceptance Tests

- Fake `TimeProvider` year is used.
- No direct `Calendar.getInstance()` in Home dashboard initialization.
- Year boundary test is deterministic.

---

## S4-010 — Dashboard does not auto-refresh when day/month changes while app remains open

**Severity:** Medium  
**File:** `HomeViewModel.kt`

## Problem

`referenceNowMillis` is computed during dashboard flow emissions. If no source flow emits across midnight/month boundary, dashboard time-sensitive widgets may not refresh.

Affected:
- month range,
- days remaining,
- financial weather,
- budget block party,
- dashboard briefing daily key,
- totals current year/month.

## Fix Strategy

Add a clock ticker or daily-boundary invalidation flow:

```kotlin
val timeTickFlow = timeProvider.dailyBoundaryTicks()
```

Combine it with dashboard data:

```kotlin
combine(processedDataFlow, configFlow, timeTickFlow, ...)
```

At minimum, schedule a refresh at next local midnight.

## Acceptance Tests

- Advancing fake time to next day updates days remaining.
- Advancing to new month reloads month widgets.
- Dashboard briefing key changes only at local-day boundary.

---

## S4-011 — Category trend loading can race and stale-write

**Severity:** Medium  
**File:** `HomeViewModel.kt`

## Problem

`loadCategoryTrends()` launches its own coroutine and is called from:
- `homeCurrency.collect`
- `reloadDashboard`
- potentially UI/other triggers

Because it launches internally, callers cannot cancel/await it. Older requests can complete after newer currency changes and overwrite `_categoryTrends`.

## Fix Strategy

Make it suspend:

```kotlin
private suspend fun loadCategoryTrendsForCurrency(currency: String)
```

Then use `flatMapLatest` or `collectLatest`:

```kotlin
viewModelScope.launch {
    homeCurrency.filterNotNull().collectLatest { currency ->
        loadCategoryTrendsForCurrency(currency)
    }
}
```

Or derive `_categoryTrends` as a `StateFlow` from `homeCurrency`.

## Acceptance Tests

- If EUR load is slow and USD load is fast, final trend state is USD.
- Errors expose degraded state or warning, not only Timber log.
- Reload cancels or sequences correctly.

---

## S4-012 — Category trend failures are invisible to UI

**Severity:** Low/Medium  
**File:** `HomeViewModel.kt`

## Problem

`loadCategoryTrends()` catches and logs errors but does not update dashboard state with trend data-quality status.

Impact:
Retro top categories or trend widgets may silently show missing/zero trend data.

## Fix Strategy

Add:

```kotlin
data class CategoryTrendUiState(
    val trends: Map<Long, CategoryTrendInfo>,
    val isLoading: Boolean,
    val error: UiText?
)
```

Render a small degraded indicator where trends are expected.

---

## S4-013 — Recommendation selected state is not cleared after successful navigation

**Severity:** Medium  
**File:** `HomeViewModel.kt`

## Problem

`navigateToRecommendation()` clears `_selectedRecommendation` on `NoOp` and exception, but not after successful emit.

## Fix Strategy

```kotlin
_navigationActions.emit(action)
_selectedRecommendation.value = null
```

If the UI needs to show detail before navigation, separate the concepts:

```kotlin
val selectedRecommendation: DashboardFollowThroughRecommendation?
val recommendationNavigationInProgress: Boolean
```

## Acceptance Tests

- Successful recommendation navigation clears selected state.
- Resolver `NoOp` clears selected state.
- Resolver exception clears selected state and surfaces visible error/snackbar.

---

## S4-014 — Recommendation card accessibility merges dismiss button into card semantics

**Severity:** Medium  
**File:** `RecommendationCard.kt`

## Problem

`RecommendationCard` uses:

```kotlin
.semantics(mergeDescendants = true) {
    contentDescription = cardContentDescription
}
```

The dismiss `IconButton` inside also has its own content description.

Merged semantics can make the dismiss action harder to discover or can flatten child semantics depending on Compose behavior.

## Fix Strategy

Do not merge descendants across an interactive child. Use:
- separate card click semantics,
- separate dismiss button semantics,
- optional custom accessibility action.

Example:

```kotlin
Modifier.semantics {
    contentDescription = cardContentDescription
    onClick(label = openRecommendationLabel) {
        onClick()
        true
    }
}
```

Keep dismiss button independent.

## Acceptance Tests

- Screen reader can focus card action.
- Screen reader can separately focus dismiss action.
- Dismiss does not trigger card click.

---

## S4-015 — Financial weather card still has hardcoded user-facing strings

**Severity:** Low/Medium  
**File:** `FinancialWeatherCard.kt`

Examples found:
- `"MANAGE RECURRING"`
- `"Today"`
- `"Tomorrow"`
- `"Recurring"`
- `"Planned"`

Also uses `lowercase().capitalize()` style behavior, which is locale/problematic and deprecated in newer Kotlin patterns.

## Fix Strategy

Move strings to resources:
- `R.string.financial_weather_manage_recurring`
- `R.string.date_today`
- `R.string.date_tomorrow`
- `R.string.recurring`
- `R.string.planned`

Use locale-safe casing or avoid runtime casing.

---

## S4-016 — Dashboard UI rendering is too monolithic

**Severity:** Medium  
**File:** `HomeScreen.kt`

## Problem

`HomeScreen.kt` is very large and contains:
- top app bar,
- feature menu,
- settings dialogs,
- widget grid,
- all widget rendering branches,
- per-widget navigation adapters,
- skeleton/loading state,
- category breakdown dialogs.

This makes Slice 4 hard to test and easy to regress.

## Fix Strategy

Extract:

```text
HomeRoute.kt
HomeScreen.kt
HomeTopBar.kt
DashboardWidgetGrid.kt
DashboardWidgetRenderer.kt
DashboardWidgetChrome.kt
HomeDialogs.kt
HomeFeatureMenu.kt
HomeDashboardCallbacks.kt
```

Keep `HomeRoute` responsible for Hilt VM + collecting state.  
Keep `HomeScreen` stateless.

## Acceptance Tests

- `DashboardWidgetRendererTest` can render each widget with fake data.
- `HomeScreenWidgetTest` verifies loading/error/empty/ready.
- `HomeRouteNavigationActionTest` verifies action-to-callback mapping.

---

## S4-017 — Widget render coverage is not contract-tested

**Severity:** Medium  
**Files:**
- `HomeScreen.kt`
- `HomeViewModel.kt`
- tests

## Problem

There should be a test guaranteeing every `DashboardWidget` subtype:
- has ID,
- has metadata,
- has render branch,
- has full-span policy,
- has style policy if styleable,
- has test tag.

Current architecture relies on the compiler for the render `when`, but not for:
- config,
- styling,
- default order,
- visibility,
- full-span behavior,
- semantics tags.

## Fix Strategy

Add `DashboardWidgetRenderContractTest`.

If Compose render-branch testing is expensive, use metadata and pure mapping tests first.

---

## S4-018 — Totals card has default currency `"EUR"`

**Severity:** Low/Medium  
**File:** `TotalsDashboardCard.kt`

## Problem

`TotalsDashboardCard` still has:

```kotlin
currency: String = "EUR"
```

Even though the comment says production callers should pass explicit currency.

Default money currency in shared widgets is dangerous because a future caller can omit it.

## Fix Strategy

Remove the default:

```kotlin
currency: String
```

Do the same for internal helper defaults such as `CurrentPeriodSummary`.

## Acceptance Tests

- Code does not compile if a production caller omits currency.
- No dashboard widget component has default fake currency.

---

## S4-019 — `DashboardState.error` and drilldown errors are separated

**Severity:** Low/Medium  
**File:** `HomeViewModel.kt`

## Problem

Main dashboard error is `DashboardState.error`.  
Totals drilldown error lives in `_totalsDrillDownState`.

If the dashboard loads but totals fail, the user may only see an error if `TotalsDashboardCard` is wired to display that specific state. This needs a test.

## Fix Strategy

- Ensure `TotalsDashboardCard` receives drilldown `error`.
- Add visible retry for totals only.
- Do not fail the entire dashboard if only totals drilldown fails.

## Acceptance Test

- Totals load fails while main dashboard succeeds: only totals widget shows retry.
- Main dashboard fails: screen-level error shows.

---

## S4-020 — AI briefing errors can be downgraded to Disabled

**Severity:** Low/Medium  
**File:** `HomeViewModel.kt`

## Problem

`aiBriefingFlow.catch` logs and emits `AiLoadState.Disabled`.

This hides runtime failures as if the feature is simply disabled.

## Fix Strategy

Emit a degraded/error state:

```kotlin
emit(AiLoadState.Error(application.getString(R.string.home_ai_briefing_unavailable)))
```

Or include diagnostics if privacy-safe.

## Acceptance Tests

- Artifact repository error shows dashboard briefing degraded/error.
- Settings-disabled still shows Disabled.
- Failure and disabled are distinguishable.

---

# Implementation Plan for Agent

## Phase 1 — Add dashboard widget metadata registry

Files:
- new `DashboardWidgetMeta.kt`
- `HomeViewModel.kt`
- `DashboardRepository`
- `StyledWidgets`
- dashboard config tests

Steps:
1. Introduce `DashboardWidgetId`.
2. Introduce `DashboardWidgetMetaRegistry`.
3. Move `getWidgetId()` logic into registry.
4. Make default dashboard config derive from registry.
5. Make `StyledWidgets` derive from registry or assert equality.
6. Update HomeViewModel to use typed IDs.
7. Add registry contract tests.

Acceptance:
- No independent widget ID lists.
- Unknown config IDs are visible in tests/debug.
- Every widget has metadata.

---

## Phase 2 — Fix dashboard currency/data-quality

Files:
- `DashboardWidget` model definitions
- `ComputeDashboardWidgetsUseCase.kt`
- `HomeViewModel.kt`
- dashboard cards
- `TotalsDashboardCard.kt`

Steps:
1. Add `MoneyDisplayUi` or pass `MoneyAggregate`.
2. Preserve `isPartial` and `warningMessage`.
3. Remove fake `"EUR"` defaults from widgets.
4. Replace `homeCurrency` placeholder with loading state.
5. Add data-quality chips/warnings to affected widgets.

Acceptance:
- Mixed-currency conversion failure is visible.
- Widgets do not render wrong placeholder currency.
- Dashboard and analytics agree for same totals.

---

## Phase 3 — Replace raw totals drilldown

Files:
- `HomeViewModel.kt`
- `TotalsAggregationEngine.kt`
- new `DashboardTotalsProvider.kt`
- tests

Steps:
1. Create currency-aware totals provider.
2. Use transaction-date conversion for historical periods.
3. Return data-quality warnings.
4. Replace `TotalsAggregationEngine` usage in HomeViewModel.
5. Keep old engine internal/deprecated if needed.
6. Add mixed-currency tests.

Acceptance:
- Totals dashboard does not raw-sum mixed currency.
- Category breakdown warnings are visible.
- Existing totals drilldown behavior remains stable.

---

## Phase 4 — Harden ViewModel concurrency/state

Files:
- `HomeViewModel.kt`
- repository APIs if needed

Steps:
1. Add widget config mutation mutex.
2. Make category trend loading suspend/cancellable.
3. Clear recommendation selection after successful navigation.
4. Move initial totals load into ViewModel.
5. Add daily-boundary refresh.
6. Fix DAY drilldown behavior.
7. Make parent/breadcrumb stack explicit.

Acceptance:
- Rapid UI actions do not corrupt config.
- Category trends cannot stale-write after currency change.
- Period drilldown has deterministic stack behavior.
- Fake time tests pass.

---

## Phase 5 — Extract HomeScreen render layers

Files:
- `HomeScreen.kt`
- new `DashboardWidgetGrid.kt`
- new `DashboardWidgetRenderer.kt`
- new `HomeTopBar.kt`
- new `HomeDialogs.kt`

Steps:
1. Split Hilt route from stateless screen.
2. Extract widget renderer.
3. Extract widget chrome/edit controls.
4. Add stable test tags per widget.
5. Add focused Compose tests.

Acceptance:
- `HomeScreen.kt` becomes route/screen composition, not all widget internals.
- Each widget can be rendered in isolation.
- Compose tests can target loading/error/empty/ready states.

---

# Recommended Tests

## `DashboardWidgetMetaContractTest`

Cases:
- every `DashboardWidget` subtype maps to exactly one ID,
- every default config ID exists in metadata,
- every styleable ID exists in metadata,
- IDs are unique,
- full-span policy exists for every widget,
- no unknown config IDs are silently ignored in debug/test.

## `HomeViewModelDashboardConfigTest`

Cases:
- unknown config ID produces debug error or warning,
- computed widget missing from config is added by migration/default config,
- hidden widgets do not interfere with visible move behavior,
- config mutation race is serialized.

## `HomeCurrencyLoadingContractTest`

Cases:
- initial state does not render EUR placeholder,
- emitted USD updates every money widget,
- currency repository failure is visible.

## `DashboardMoneyQualityTest`

Cases:
- partial `MoneyAggregate` appears as warning,
- source buckets are preserved,
- conversion failure does not silently show clean total.

## `TotalsDashboardCurrencyTest`

Cases:
- monthly totals normalize mixed currency,
- weekly/day drilldowns match monthly parent sum within rounding tolerance,
- category breakdown equals selected period total,
- conversion failure warning appears.

## `TotalsDrillDownStateTest`

Cases:
- month → week → day transitions,
- DAY click is leaf-safe,
- drill up restores expected parent,
- breadcrumb does not grow incorrectly.

## `CategoryTrendLoadingTest`

Cases:
- currency change cancels stale trend load,
- trend error is represented in state,
- reload does not launch uncontrolled duplicate jobs.

## `RecommendationNavigationTest`

Cases:
- success emits action and clears selection,
- `NoOp` clears selection,
- exception clears selection and emits visible error/snackbar state,
- dismiss clears selected matching recommendation.

## `RecommendationCardA11yTest`

Cases:
- card click and dismiss are separate accessibility actions,
- dismiss does not trigger card click,
- priority and recommendation text are announced.

---

# Final Severity Table

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S4-001 | High | Unresolved | Widget config/computed widget drift silently drops widgets |
| S4-002 | Med/High | Unresolved | Move button index uses rendered list but mutation uses full config |
| S4-003 | Medium | Unresolved | Widget config read-modify-write operations can race |
| S4-004 | Med/High | Unresolved | Home currency starts as fake `"EUR"` |
| S4-005 | High | Unresolved | Totals dashboard still uses raw aggregation engine |
| S4-006 | High | Unresolved | MoneyAggregate/data-quality warnings lost in dashboard UI |
| S4-007 | Medium | Unresolved | DAY drilldown can mutate breadcrumb/state unexpectedly |
| S4-008 | Medium | Needs verification | Parent/breadcrumb drill-up state may be inconsistent |
| S4-009 | Medium | Unresolved | Initial totals load is UI-owned and can use real clock |
| S4-010 | Medium | Unresolved | Dashboard does not refresh on day/month boundary |
| S4-011 | Medium | Unresolved | Category trend loading can race/stale-write |
| S4-012 | Low/Med | Unresolved | Category trend failures are invisible |
| S4-013 | Medium | Partial | Recommendation success does not clear selected state |
| S4-014 | Medium | Unresolved | Recommendation card merged semantics may hide dismiss action |
| S4-015 | Low/Med | Unresolved | Financial weather has hardcoded strings |
| S4-016 | Medium | Design debt | `HomeScreen` is too monolithic |
| S4-017 | Medium | Test gap | No widget render/metadata contract |
| S4-018 | Low/Med | Unresolved | `TotalsDashboardCard` has default fake currency |
| S4-019 | Low/Med | Needs test | Dashboard error and totals error are separate |
| S4-020 | Low/Med | Unresolved | AI briefing failures can appear as Disabled |

---

# Immediate Agent Task List

## Task A — Widget registry contract
- Add `DashboardWidgetMetaRegistry`.
- Remove/redirect `HomeViewModel.getWidgetId`.
- Add contract tests.

## Task B — Currency/data-quality
- Remove fake EUR defaults.
- Add loading currency state.
- Preserve `MoneyAggregate` warnings in dashboard widgets.

## Task C — Totals drilldown
- Replace `TotalsAggregationEngine` in Home dashboard with currency-aware provider.
- Add mixed-currency totals tests.

## Task D — ViewModel state/concurrency
- Serialize widget config mutations.
- Make category trend loading cancellable.
- Fix DAY leaf behavior.
- Clear selected recommendation on success.

## Task E — Home UI extraction/tests
- Extract widget renderer/grid.
- Add widget test tags.
- Add Compose tests for loading/error/empty/ready and recommendation card accessibility.

---

# Source Links Used

- Commit reviewed: https://github.com/panospao7/Cost-agregator/commit/ea3f716eebba8c513edeeba40db394c10ca829cb
- `HomeScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt
- `HomeViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt
- `DashboardWidgetUiMapper.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/mappers/DashboardWidgetUiMapper.kt
- `TotalsDashboardCard.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/components/TotalsDashboardCard.kt
- `RecommendationCard.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/components/RecommendationCard.kt
- `FinancialWeatherCard.kt`: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/app/src/main/java/com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt
- Prior currency/dashboard debug doc: https://github.com/panospao7/Cost-agregator/blob/ea3f716eebba8c513edeeba40db394c10ca829cb/docs/analyses%20and%20debug%20master/debugging/pipeline-5-currency-dashboard-analytics-debug-report.md