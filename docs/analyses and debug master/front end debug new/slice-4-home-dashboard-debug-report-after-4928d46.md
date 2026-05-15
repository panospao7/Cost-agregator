# Slice 4 Re-Debug Report — Home / Dashboard Shell

Commit reviewed: `4928d461a658eac72769efc08a0b2ef795480442`  
Commit title: `fix(ui): Slice 4 remaining - S4-001/002/006/010/012/014/015/016`  
Review type: static GitHub source review, not local Gradle/device execution.

Primary commit:  
https://github.com/panospao7/Cost-agregator/commit/4928d461a658eac72769efc08a0b2ef795480442

---

# Executive Summary

Slice 4 is **improved but still not closed**.

Confirmed progress:
- A new `DashboardWidgetRegistry` centralizes widget ID constants and `idFor(widget)`.
- `HomeViewModel.getWidgetId()` now delegates to that registry.
- Dashboard config mutation now uses a `Mutex`.
- Widget move logic tries to move within visible widgets instead of swapping with hidden widgets.
- `DashboardState` and some widget models now carry partial-conversion flags.
- Home currency is now nullable rather than defaulting to fake `"EUR"`.
- A midnight refresh loop was added.
- Recommendation selection is cleared after successful navigation.
- `RecommendationCard` no longer merges dismiss-button semantics into the card.
- Several `FinancialWeatherCard` hardcoded strings were moved to resources.
- `HomeRoute` was introduced as a route wrapper.

Still high-risk:
1. `DashboardWidgetRegistry` is only an ID registry, not a full metadata/config/style/render authority.
2. `DashboardRepository.getDefaultConfig()` and `StyledWidgets` still maintain independent hardcoded ID lists.
3. Unknown config IDs are still silently dropped by `mapNotNull`.
4. Data-quality flags are added but mostly not populated or rendered.
5. `HomeScreen` still ignores `DashboardState.isPartial`.
6. `SafeToSpend.isPartial`, `SafeToSpend.conversionWarningCount`, and `PeriodSummary.isPartial` exist but are not set during widget assembly.
7. Totals drilldown still uses `TotalsAggregationEngine`, so the mixed-currency raw-sum risk remains.
8. Initial totals loading now happens in both `HomeViewModel.init` and `HomeScreen.LaunchedEffect(Unit)`.
9. `HomeScreen` still falls back to `Calendar.getInstance()` despite the ViewModel fix.
10. `loadCategoryTrends()` still launches an internal coroutine, so `collectLatest` does not actually cancel stale trend loads.
11. Category trend errors are still represented as `emptyMap()`, indistinguishable from legitimate no-trend data.
12. Widget move behavior is now wrong for hidden widgets rendered in edit mode.
13. Totals-card error mapping likely drops `UiText.StringResource` errors.
14. `HomeRoute` extraction is mostly superficial; `HomeScreen` still owns Hilt/ViewModel/event/effect logic.

Recommended next order:
1. Finish widget registry into real metadata registry.
2. Fix data-quality propagation and display.
3. Replace raw totals drilldown provider.
4. Remove duplicate HomeScreen totals init.
5. Fix category trend cancellation/state.
6. Fix edit-mode move semantics.
7. Finish HomeRoute/HomeScreen extraction.
8. Add stronger contract tests.

---

# Updated Previous-Issue Status

| ID | Current Status | Notes |
|---|---|---|
| S4-001 | Partial | Registry added, but default config/style/render metadata still drift. |
| S4-002 | Partial/regressed | Visible-only move improved, but hidden widgets in edit mode cannot move correctly. |
| S4-003 | Mostly fixed | Mutex added for move/toggle visibility. Save failure still not surfaced. |
| S4-004 | Partial | No fake `"EUR"` initial state, but UI renders empty currency and no loading/error state. |
| S4-005 | Unresolved | Totals drilldown still uses `TotalsAggregationEngine`. |
| S4-006 | Partial | Partial flags added but not populated/rendered broadly. |
| S4-007 | Unresolved | Unknown widget config IDs still disappear via `mapNotNull`. |
| S4-008 | Needs verification | Breadcrumb/parent drill-up still uses old `parentPeriod` model. |
| S4-009 | Partial/regressed | VM init added, but HomeScreen duplicate init with `Calendar.getInstance()` remains. |
| S4-010 | Partial | Midnight loop added, but needs injected timezone/ticker tests. |
| S4-011 | Unresolved | `collectLatest` does not cancel inner `loadCategoryTrends()` job. |
| S4-012 | Unresolved | Trend failure becomes `emptyMap()`, no UI-degraded state. |
| S4-013 | Resolved | Selected recommendation cleared after successful navigation. |
| S4-014 | Mostly fixed | Card semantics no longer merge descendants; needs accessibility test. |
| S4-015 | Mostly fixed | Main hardcoded weather strings localized; frequency label still generated from enum. |
| S4-016 | Partial | `HomeRoute` added, but `HomeScreen` still ViewModel-coupled. |
| S4-017 | Partial | Tests exist, but are weak/manual and do not prove actual render/config coverage. |
| S4-018 | Likely unresolved | `TotalsDashboardCard` was not touched in this commit. |
| S4-019 | Needs test | Screen-level vs totals-widget error behavior still fragile. |
| S4-020 | Resolved | AI briefing catch now emits `Error`, not `Disabled`. |

---

# Confirmed Fixes

## S4-FIX-4928-001 — Widget ID mapping extracted to `DashboardWidgetRegistry`

**Status:** Partial improvement  
**Files:**
- `DashboardWidgetRegistry.kt`
- `HomeViewModel.kt`

The old local `HomeViewModel.getWidgetId()` mapping was replaced by a new registry and `getWidgetId()` now delegates to it.

This reduces immediate ID drift inside `HomeViewModel`.

## Remaining problem

The registry only contains:
- constants,
- `allIds`,
- `idFor(widget)`.

It does **not** own:
- default order,
- default visibility,
- title resource,
- full-span policy,
- styleability,
- render/test tag policy.

Also, `DashboardRepository.getDefaultConfig()` still hardcodes IDs independently, and `StyledWidgets` still hardcodes styleable IDs independently.

## Required next step

Replace `DashboardWidgetRegistry` with metadata:

```kotlin
@JvmInline
value class DashboardWidgetId(val value: String)

data class DashboardWidgetMeta(
    val id: DashboardWidgetId,
    val titleRes: Int,
    val defaultOrder: Int,
    val defaultVisible: Boolean,
    val fullSpan: Boolean,
    val styleable: Boolean,
    val testTag: String
)
```

Then:
- `DashboardRepository.getDefaultConfig()` derives from registry.
- `StyledWidgets.all` is removed or derived from registry.
- Home UI full-span logic derives from registry.
- Contract tests compare real repository defaults to registry, not a hardcoded test list.

---

## S4-FIX-4928-002 — Widget config mutation uses `Mutex`

**Status:** Mostly fixed  
**File:** `HomeViewModel.kt`

`moveWidget()` and `toggleWidgetVisibility()` are now wrapped in `widgetConfigMutex.withLock`.

This resolves the original read-modify-write race between rapid reorder/visibility actions.

## Remaining gap

There is still no visible UI error when `saveDashboardConfig()` fails.

Add:

```kotlin
val widgetConfigError: UiText?
```

or a snackbar event.

Acceptance tests:
- two rapid toggles serialize correctly;
- move + visibility toggle serialize correctly;
- save failure surfaces a visible event.

---

## S4-FIX-4928-003 — Recommendation selected state clears after successful navigation

**Status:** Resolved  
**File:** `HomeViewModel.kt`

`navigateToRecommendation()` now clears `_selectedRecommendation` after emitting a successful navigation action.

Acceptance test:
- success emits action and clears selected recommendation;
- NoOp clears;
- resolver exception clears.

---

## S4-FIX-4928-004 — `RecommendationCard` no longer merges dismiss semantics

**Status:** Mostly fixed  
**File:** `RecommendationCard.kt`

The card removed `mergeDescendants = true`, which should allow the dismiss icon to remain an independent accessibility node.

Acceptance tests still needed:
- card click and dismiss are separate semantics actions;
- dismiss does not trigger card click;
- TalkBack can focus dismiss separately.

---

## S4-FIX-4928-005 — Some `FinancialWeatherCard` strings localized

**Status:** Mostly fixed  
**File:** `FinancialWeatherCard.kt`

Localized:
- manage recurring
- today
- tomorrow
- recurring
- planned

Remaining:
- `item.pattern.frequency.name.lowercase().capitalize()` is still enum-generated copy, not localized and not locale-safe.

Fix:
Map frequency enum to string resources.

---

## S4-FIX-4928-006 — AI briefing failure no longer looks Disabled

**Status:** Resolved  
**File:** `HomeViewModel.kt`

`aiBriefingFlow.catch` now emits an `AiLoadState.Error`, making runtime failure distinguishable from “AI disabled.”

---

# Remaining / New Issues

---

## S4-001R — Widget registry is not yet canonical metadata

**Severity:** High  
**Files:**
- `DashboardWidgetRegistry.kt`
- `DashboardRepository.kt`
- `WidgetStyle.kt`
- `HomeScreen.kt`

## Problem

The new registry centralizes ID strings, but other parts still own parallel widget knowledge:
- `DashboardRepository.getDefaultConfig()` has hardcoded IDs/order.
- `StyledWidgets` has hardcoded IDs.
- `HomeScreen.isFullSpan(widget)` is separate.
- render coverage is separate.
- tests use their own hardcoded ID list.

So the drift risk is reduced but not eliminated.

## Fix strategy

Make the registry the only source of:
- ID,
- order,
- default visibility,
- styleability,
- full-span,
- title,
- test tag.

## Acceptance tests

Add/replace with `DashboardWidgetRegistryContractTest`:
- every `DashboardWidget` subtype maps to metadata;
- default config is exactly registry-derived;
- styleable widgets are exactly `registry.filter { it.styleable }`;
- no hardcoded widget ID list exists in tests except registry assertions;
- unknown saved config IDs are detected.

---

## S4-002R — Move logic breaks hidden widgets in edit mode

**Severity:** Medium/High  
**File:** `HomeViewModel.kt`

## Problem

`moveWidget()` now computes:

```kotlin
visibleIds = currentConfig.filter { it.isVisible }.map { it.id }
```

This fixes normal visible-only movement, but edit mode renders hidden widgets too. If a hidden widget is visible in the edit UI and the user taps move:
- `visibleIndex == -1`,
- move becomes no-op.

Also, in edit mode, move buttons are based on rendered index, while mutation moves only among visible IDs.

## Fix strategy

Make move semantics explicit:

```kotlin
fun moveWidget(
    widgetId: String,
    moveUp: Boolean,
    includeHidden: Boolean
)
```

Call:
- `includeHidden = state.isEditMode` from edit UI.
- `includeHidden = false` for non-edit UI if moves are allowed there.

Better:
Only allow reordering in edit mode and move within full config order.

## Acceptance tests

- hidden widget in edit mode can move up/down;
- visible widget in normal mode moves among visible widgets;
- hidden widgets do not cause visible move no-op;
- move buttons match actual mutability.

---

## S4-004R — Nullable home currency prevents fake EUR but lacks loading/error UI

**Severity:** Medium/High  
**Files:**
- `HomeViewModel.kt`
- `HomeScreen.kt`

## Problem

`homeCurrency` now starts as `null`, which is better than fake `"EUR"`.

But the UI often renders:

```kotlin
homeCurrency ?: ""
```

This can produce blank/invalid currency formatting in:
- SpendingTrendChart
- TotalsDashboardCard
- FinancialWeatherCard
- FinancialRunwayCard
- MonteCarloForecastCard
- FinancialStressForecastCard
- SavingsSweepPromptCard
- CategoryBreakdownSheet
- PeriodSummary stats

Also, `catch { emit(null) }` means repository failure looks the same as loading forever.

## Fix strategy

Expose typed state:

```kotlin
sealed interface HomeCurrencyUiState {
    data object Loading : HomeCurrencyUiState
    data class Ready(val code: String) : HomeCurrencyUiState
    data class Error(val message: UiText) : HomeCurrencyUiState
}
```

Render money widgets only when `Ready`.

## Acceptance tests

- before first currency emission, no widget displays blank or fake currency;
- repository error shows degraded/error UI;
- USD/EUR emissions update all widgets.

---

## S4-005R — Totals dashboard still uses raw aggregation engine

**Severity:** High  
**Files:**
- `HomeViewModel.kt`
- `TotalsAggregationEngine.kt`
- totals dashboard components

## Problem

`HomeViewModel` still calls:
- `totalsAggregationEngine.getMonthlyTotals`
- `getWeeklyTotals`
- `getDailyTotalsForRange`
- `getCategoryBreakdown`
- `getAverageForPeriodType`

This was the major previous mixed-currency risk. The current commit does not replace it.

## Impact

Totals dashboard can still disagree with currency-aware dashboard widgets.

## Fix strategy

Introduce:

```kotlin
interface DashboardTotalsProvider {
    suspend fun monthlyTotals(year: Int, displayCurrency: String): TotalsResult
    suspend fun weeklyTotals(year: Int, month: Int, displayCurrency: String): TotalsResult
    suspend fun dailyTotals(startMs: Long, endMs: Long, displayCurrency: String): TotalsResult
    suspend fun categoryBreakdown(startMs: Long, endMs: Long, displayCurrency: String): CategoryBreakdownResult
}
```

Return money-quality metadata:
- `isPartial`,
- failed currencies,
- source buckets,
- warning `UiText`.

## Acceptance tests

- mixed USD/JPY totals normalize correctly;
- conversion failure shows warning;
- totals dashboard and analytics agree for same period.

---

## S4-006R — Data-quality flags are added but mostly inert

**Severity:** High  
**Files:**
- `ComputeDashboardWidgetsUseCase.kt`
- `HomeViewModel.kt`
- `HomeScreen.kt`

## Problem

New fields exist:
- `CompiledDashboardData.isPartial`
- `DashboardState.isPartial`
- `DashboardWidget.SafeToSpend.isPartial`
- `DashboardWidget.SafeToSpend.conversionWarningCount`
- `DashboardWidget.PeriodSummary.isPartial`

But:
- `SafeToSpend` is constructed without setting `isPartial`.
- `PeriodSummary` is constructed without setting `isPartial`.
- `DashboardState.isPartial` is not used in `HomeScreen`.
- no dashboard warning chip/card is rendered.
- `todaySpent` and `weekSpent` still discard `MoneyAggregate` quality data and keep only display amount.
- `conversionWarningCount` is not populated.

## Fix strategy

Create a reusable UI model:

```kotlin
data class MoneyDisplayUi(
    val amount: Double,
    val currency: String,
    val isPartial: Boolean,
    val warning: UiText?,
    val failedCurrencies: Set<String> = emptySet()
)
```

Use it in:
- SafeToSpend
- PeriodSummary
- FinancialWeather
- Runway
- MonteCarlo
- StressForecast
- TotalsDashboard
- TopCategories

Render:

```kotlin
if (money.isPartial) DataQualityWarningChip(money.warning)
```

## Acceptance tests

- missing conversion rate sets widget warning;
- warning is visible in UI;
- `state.isPartial` is not dead state;
- `SafeToSpend.isPartial` and `PeriodSummary.isPartial` are populated.

---

## S4-007R — Unknown widget config IDs still silently disappear

**Severity:** High  
**Files:**
- `HomeViewModel.kt`
- `DashboardRepository.kt`

## Problem

The dashboard still joins config to computed widgets using `mapNotNull`.

Unknown saved IDs remain in repository config, but render silently as nothing.

## Fix strategy

Add diagnostics:

```kotlin
data class DashboardConfigIssue(
    val id: String,
    val reason: DashboardConfigIssueReason
)
```

In test/debug:
- fail or render diagnostic widget.

In production:
- log structured warning,
- optionally migrate/drop unknown IDs.

## Acceptance tests

- unknown saved ID produces diagnostic state;
- unknown saved ID is not silently dropped in tests;
- computed widget missing from config is appended by migration/default config.

---

## S4-009R — Initial totals now load twice and HomeScreen still uses real clock fallback

**Severity:** Medium/High  
**Files:**
- `HomeViewModel.kt`
- `HomeScreen.kt`

## Problem

The ViewModel now calls `loadTotalsForYear(timeProvider.now())` in `init`, which is correct.

But `HomeScreen` still has:

```kotlin
LaunchedEffect(Unit) {
    val currentYear =
        if (state.referenceNowMillis > 0L) ...
        else Calendar.getInstance().get(Calendar.YEAR)

    viewModel.loadTotalsForYear(currentYear)
}
```

So:
- totals can load twice;
- the UI still falls back to `Calendar.getInstance()`;
- tests using fake `TimeProvider` can still flap;
- around year boundary, UI and VM can request different years.

## Fix strategy

Delete the HomeScreen `LaunchedEffect(Unit)` totals initialization.

Only the ViewModel should initialize totals.

## Acceptance tests

- ViewModel init loads totals once;
- HomeScreen composition does not call `loadTotalsForYear`;
- fake time year is respected.

---

## S4-010R — Midnight refresh loop needs testable clock/zone policy

**Severity:** Medium  
**File:** `HomeViewModel.kt`

## Problem

A midnight loop was added, but it uses:
- `ZoneId.systemDefault()`,
- manual infinite `while(true)`,
- nested `reloadDashboard()` that launches another coroutine.

This works in production, but is harder to test and may be brittle under timezone changes.

## Fix strategy

Inject a daily ticker:

```kotlin
interface DashboardRefreshTicker {
    val dailyTicks: Flow<Unit>
}
```

or inject zone/clock:

```kotlin
class DailyBoundaryTicker(
    private val timeProvider: TimeProvider,
    private val zoneProvider: ZoneProvider
)
```

Then:

```kotlin
ticker.dailyTicks.collect {
    reloadDashboardNow()
}
```

## Acceptance tests

- advancing fake time to next day triggers one reload;
- timezone change is deterministic;
- no duplicate reloads at midnight.

---

## S4-011R — Category trend `collectLatest` still does not cancel stale work

**Severity:** High  
**File:** `HomeViewModel.kt`

## Problem

The code says `collectLatest` cancels stale trend load, but `loadCategoryTrends()` immediately launches its own coroutine:

```kotlin
fun loadCategoryTrends() {
    viewModelScope.launch { ... }
}
```

So `collectLatest { loadCategoryTrends() }` cancels only the outer lambda, not the actual analytics work.

Also, the collected `currency` is ignored; `loadCategoryTrends()` reads `homeCurrency.value` later.

## Impact

If currency changes from EUR to USD while EUR analytics is slow:
- EUR job can finish after USD job,
- `_categoryTrends` can be stale.

## Fix strategy

Make loading suspend and parameterized:

```kotlin
private suspend fun loadCategoryTrendsForCurrency(currency: String) {
    ...
    val (analytics, _) = advancedAnalyticsEngine.getCategoryAnalytics(
        period,
        displayCurrency = currency
    )
    _categoryTrends.value = CategoryTrendUiState.Ready(...)
}
```

Then:

```kotlin
homeCurrency.filterNotNull().collectLatest { currency ->
    loadCategoryTrendsForCurrency(currency)
}
```

For manual reload, launch once and call the suspend method.

## Acceptance tests

- slow EUR load cannot overwrite faster USD load;
- reload cancels stale load;
- trend state includes currency used for computation.

---

## S4-012R — Category trend failure still has no UI state

**Severity:** Medium  
**File:** `HomeViewModel.kt`

## Problem

On category trend failure, the code sets:

```kotlin
_categoryTrends.value = emptyMap()
```

This is indistinguishable from:
- no categories,
- zero trends,
- loading not started.

## Fix strategy

Replace `Map<Long, CategoryTrendInfo>` with:

```kotlin
sealed interface CategoryTrendUiState {
    data object Loading : CategoryTrendUiState
    data class Ready(val trends: Map<Long, CategoryTrendInfo>) : CategoryTrendUiState
    data class Error(val message: UiText) : CategoryTrendUiState
}
```

## Acceptance tests

- analytics failure shows degraded trend indicator;
- legitimate empty trends show empty state, not error;
- retry reloads trends.

---

## S4-015R — Financial weather frequency label is still enum-generated

**Severity:** Low/Medium  
**File:** `FinancialWeatherCard.kt`

## Problem

The card still derives recurring frequency copy from enum name using lowercase/capitalize behavior.

This is:
- not localized,
- locale-sensitive,
- visually coupled to enum names.

## Fix strategy

Map frequency enum to resources:

```kotlin
fun RecurrenceFrequency.labelRes(): Int = when (this) { ... }
```

## Acceptance tests

- monthly/weekly/yearly labels use resources;
- no enum `.name` is directly displayed.

---

## S4-016R — `HomeRoute` extraction is superficial

**Severity:** Medium  
**File:** `HomeScreen.kt`

## Problem

`HomeRoute` was added, but it simply forwards the ViewModel into `HomeScreen`.

`HomeScreen` still:
- has default `viewModel: HomeViewModel = hiltViewModel()`,
- collects ViewModel state,
- collects navigation events,
- owns route effects,
- owns dialogs,
- owns widget rendering,
- calls ViewModel functions directly.

So the screen is still difficult to test with fake state.

## Fix strategy

Target split:

```kotlin
@Composable
fun HomeRoute(...) {
    val state by viewModel.dashboard.collectAsState()
    ...
    HomeScreen(
        state = state,
        callbacks = HomeCallbacks(...)
    )
}
```

`HomeScreen` should have no `hiltViewModel()` and no direct ViewModel type.

## Acceptance tests

- `HomeScreen` renders fake `DashboardState` without Hilt;
- `HomeRoute` owns navigation event collection;
- widget renderer can be tested separately.

---

## S4-017R — Existing widget tests are weak and can give false confidence

**Severity:** Medium  
**Files:**
- `DashboardWidgetMetaContractTest.kt`
- `DashboardWidgetRenderCoverageTest.kt`

## Problems

`DashboardWidgetMetaContractTest`:
- still references `HomeViewModel.getWidgetId()`;
- tests only four sample widgets;
- hardcodes default IDs in the test instead of reading real registry/repository metadata;
- does not verify `StyledWidgets`.

`DashboardWidgetRenderCoverageTest`:
- uses a manual `RENDERED_WIDGETS` set;
- does not prove `HomeScreen` actually renders every branch;
- `HomeScreen` still has an `else` fallback that can silently blank future widgets.

## Fix strategy

Tests should verify:
- registry metadata covers all widget subtypes;
- repository defaults derive from registry;
- styleable IDs derive from registry;
- every registry ID has render metadata/test tag;
- no `else` fallback hides new `DashboardWidget` types.

## Acceptance tests

- adding a new `DashboardWidget` fails unless metadata + render branch + test tag are added;
- default config IDs exactly equal registry default IDs;
- styleable IDs exactly equal registry styleable IDs.

---

## S4-021 — Totals error string is likely dropped for `UiText.StringResource`

**Severity:** Medium/High  
**File:** `HomeScreen.kt`

## Problem

`HomeViewModel.loadTotalsForYear()` sets totals errors using `UiText.StringResource`.

But `HomeScreen` passes error to `TotalsDashboardCard` using only `DynamicString` extraction:

```kotlin
error = totalsState.error?.let {
    (it as? UiText.DynamicString)?.value
}
```

So a `StringResource` error becomes `null`, and the totals card may not show the failure.

## Fix strategy

Either:
- pass `UiText?` into `TotalsDashboardCard`, or
- resolve it in Compose:

```kotlin
val totalsError = totalsState.error?.asString()
```

Then:

```kotlin
error = totalsError
```

## Acceptance tests

- `UiText.StringResource(R.string.home_error_unable_to_load_totals)` appears in totals card;
- retry button appears when totals error exists;
- DynamicString and StringResource both render.

---

## S4-022 — Dashboard partial state is dead UI state

**Severity:** Medium  
**Files:**
- `DashboardState`
- `HomeScreen.kt`

## Problem

`DashboardState.isPartial` is set from `CompiledDashboardData.isPartial`, but `HomeScreen` does not read it.

## Fix strategy

Show a global dashboard data-quality banner or per-widget warnings.

Example:

```kotlin
if (state.isPartial) {
    DataQualityBanner(
        text = stringResource(R.string.dashboard_partial_currency_warning)
    )
}
```

Better: per-widget warnings with source currency detail.

## Acceptance tests

- when `state.isPartial = true`, a warning is visible;
- warning does not block normal dashboard rendering;
- warning is accessible.

---

## S4-023 — `CategorySpending` still defaults currency to fake EUR

**Severity:** Medium  
**File:** `ComputeDashboardWidgetsUseCase.kt`

## Problem

`CategorySpending` still has:

```kotlin
val currency: String = "EUR"
```

Even if most callers pass a currency, a default fake currency is risky in dashboard models.

## Fix strategy

Remove the default:

```kotlin
val currency: String
```

Compile should fail until all callers pass a real currency.

## Acceptance tests

- no dashboard money model has default `"EUR"`;
- category widgets cannot be created without explicit currency.

---

# Updated Implementation Plan for Agent

## Phase 1 — Finish widget registry

Files:
- `DashboardWidgetRegistry.kt`
- `DashboardRepository.kt`
- `WidgetStyle.kt`
- `HomeScreen.kt`
- tests

Steps:
1. Replace ID-only registry with `DashboardWidgetMeta`.
2. Make `DashboardRepository.getDefaultConfig()` derive from registry.
3. Derive styleable IDs from registry.
4. Derive full-span from registry.
5. Replace `HomeViewModel.getWidgetId()` call sites with registry directly.
6. Add unknown-ID diagnostics.
7. Strengthen contract tests.

---

## Phase 2 — Fix currency/data-quality display

Files:
- `ComputeDashboardWidgetsUseCase.kt`
- `HomeViewModel.kt`
- `HomeScreen.kt`
- dashboard cards

Steps:
1. Introduce `MoneyDisplayUi`.
2. Propagate `MoneyAggregate` quality from multi-currency calls.
3. Populate SafeToSpend/PeriodSummary partial fields or replace them with `MoneyDisplayUi`.
4. Render global/per-widget warning chips.
5. Remove default `"EUR"` from `CategorySpending`.
6. Add partial-conversion tests.

---

## Phase 3 — Replace totals drilldown provider

Files:
- `HomeViewModel.kt`
- `TotalsAggregationEngine.kt`
- new `DashboardTotalsProvider.kt`

Steps:
1. Create currency-aware totals provider.
2. Use transaction-date conversion.
3. Return warnings/partial quality.
4. Replace calls in:
   - `loadTotalsForYear`
   - `drillDownToPeriod`
   - `drillUp`
   - category breakdown loaders.
5. Fix totals error rendering.

---

## Phase 4 — Fix lifecycle/concurrency

Files:
- `HomeViewModel.kt`
- `HomeScreen.kt`

Steps:
1. Remove HomeScreen totals `LaunchedEffect(Unit)`.
2. Use injected daily ticker instead of manual infinite loop.
3. Make category trend load suspend/cancellable.
4. Add typed `CategoryTrendUiState`.
5. Add tests with fake time/currency.

---

## Phase 5 — Finish UI extraction

Files:
- `HomeScreen.kt`
- new `HomeRoute.kt`
- new `DashboardWidgetRenderer.kt`
- new `DashboardWidgetGrid.kt`
- new `HomeDialogs.kt`

Steps:
1. Move Hilt/ViewModel collection to `HomeRoute`.
2. Make `HomeScreen` stateless.
3. Extract widget rendering.
4. Remove fallback `else` for `DashboardWidget` render branch or make it fail visibly.
5. Add Compose tests for fake states.

---

# Recommended Tests

## `DashboardWidgetRegistryContractTest`
- all `DashboardWidget` subclasses have metadata;
- all registry IDs are unique;
- default config derives from metadata;
- styleable IDs derive from metadata;
- full-span policy exists for every widget.

## `DashboardUnknownConfigTest`
- unknown saved ID creates diagnostic;
- unknown ID is not silently dropped in test/debug;
- new default widget is appended to saved config.

## `DashboardWidgetMoveTest`
- visible-only move ignores hidden widgets in normal mode;
- hidden widget can move in edit mode;
- move buttons match actual mutation behavior;
- rapid moves serialize.

## `DashboardCurrencyLoadingTest`
- no fake EUR or blank currency renders while loading;
- loaded USD appears across all money widgets;
- currency repository error shows degraded UI.

## `DashboardDataQualityTest`
- partial `MoneyAggregate` sets `DashboardState.isPartial`;
- partial state renders warning;
- SafeToSpend/PeriodSummary show warning;
- failed currencies are preserved.

## `TotalsDashboardCurrencyProviderTest`
- mixed currency monthly totals normalize;
- weekly/day drilldown matches parent within rounding tolerance;
- conversion failure shows warning;
- `UiText.StringResource` totals error renders.

## `CategoryTrendConcurrencyTest`
- slow old-currency trend load cannot overwrite fast new-currency load;
- trend failure becomes `CategoryTrendUiState.Error`;
- reload cancels stale work.

## `HomeInitialLoadTest`
- totals load exactly once on init;
- HomeScreen composition does not call totals load;
- fake `TimeProvider` controls year.

## `RecommendationCardA11yTest`
- dismiss action is independently focusable;
- card click and dismiss are separate;
- dismiss does not trigger navigation.

---

# Final Severity Table After `4928d46`

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S4-001R | High | Partial | Registry added but not canonical metadata |
| S4-002R | Med/High | Partial/regressed | Hidden widgets in edit mode cannot move correctly |
| S4-004R | Med/High | Partial | Nullable currency but no loading/error UI |
| S4-005R | High | Unresolved | Totals drilldown still uses raw aggregation engine |
| S4-006R | High | Partial | Data-quality flags added but mostly inert |
| S4-007R | High | Unresolved | Unknown widget IDs still silently dropped |
| S4-009R | Med/High | Partial/regressed | Totals load duplicated; HomeScreen still uses real clock fallback |
| S4-010R | Medium | Partial | Midnight refresh exists but needs injectable ticker/zone tests |
| S4-011R | High | Unresolved | `collectLatest` does not cancel inner category trend job |
| S4-012R | Medium | Unresolved | Trend failure indistinguishable from empty trends |
| S4-015R | Low/Med | Mostly fixed | Weather enum frequency label still not localized |
| S4-016R | Medium | Partial | `HomeRoute` extraction superficial |
| S4-017R | Medium | Partial | Widget tests weak/manual |
| S4-021 | Med/High | New | Totals `StringResource` errors likely dropped |
| S4-022 | Medium | New | `DashboardState.isPartial` is dead UI state |
| S4-023 | Medium | New | `CategorySpending` still defaults to fake EUR |

---

# Immediate Agent Task List

## Task A — Real widget metadata registry
Finish `DashboardWidgetRegistry` so config/style/full-span/render policy derive from one source.

## Task B — Data-quality UI
Use `MoneyDisplayUi` or similar; render partial-conversion warnings.

## Task C — Totals provider
Replace `TotalsAggregationEngine` in Home dashboard with a currency-aware totals provider.

## Task D — Remove duplicate initial totals load
Delete `HomeScreen.LaunchedEffect(Unit)` totals init.

## Task E — Fix category trend cancellation
Make trend loading suspend, parameterized by currency, and represented by typed UI state.

## Task F — Fix edit-mode widget movement
Move hidden widgets correctly when edit mode renders them.

## Task G — Fix totals error rendering
Pass/resolve `UiText` correctly instead of only accepting `DynamicString`.

---

# Sources Reviewed

- Commit diff: https://github.com/panospao7/Cost-agregator/commit/4928d461a658eac72769efc08a0b2ef795480442
- `HomeViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt
- `HomeScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt
- `ComputeDashboardWidgetsUseCase.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- `DashboardWidgetRegistry.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardWidgetRegistry.kt
- `DashboardRepository.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/main/java/com/yourname/expensetracker/data/repository/DashboardRepository.kt
- `WidgetStyle.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/main/java/com/yourname/expensetracker/domain/widget/model/WidgetStyle.kt
- `RecommendationCard.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/main/java/com/yourname/expensetracker/ui/components/RecommendationCard.kt
- `FinancialWeatherCard.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/main/java/com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt
- `DashboardWidgetMetaContractTest.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/test/java/com/yourname/expensetracker/ui/screens/home/DashboardWidgetMetaContractTest.kt
- `DashboardWidgetRenderCoverageTest.kt`: https://github.com/panospao7/Cost-agregator/blob/4928d461a658eac72769efc08a0b2ef795480442/app/src/test/java/com/yourname/expensetracker/ui/screens/home/DashboardWidgetRenderCoverageTest.kt