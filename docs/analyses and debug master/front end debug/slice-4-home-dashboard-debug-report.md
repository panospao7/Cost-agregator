# Slice 4 Debug Report — Home / Dashboard Composition

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Primary scope:
- `HomeScreen.kt`
- `HomeViewModel.kt`
- dashboard widgets rendered from `DashboardWidget`
- `TotalsDashboardCard`
- `BudgetBlockPartyCard`
- forecast/runway/weather/stress widgets
- recommendations
- financial health widgets
- feature/menu entry points

Sources inspected:
- `HomeScreen.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt
- `HomeViewModel.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt
- `ComputeDashboardWidgetsUseCase.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- `TotalsDashboardCard.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/TotalsDashboardCard.kt
- `BudgetBlockPartyCard.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/BudgetBlockPartyCard.kt
- UI map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- Segments: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 4 is one of the highest-risk UI slices.

The current Home dashboard works as a composition hub, but it is too monolithic and has several correctness/testability risks:

1. `HomeScreen.kt` is a very large composable with app bar, grid, widget renderer, dialogs, feature menu, debug overlay, quick settings, recommendation rendering, and widget edit overlay in one file.
2. `HomeViewModel` has many responsibilities and many injected dependencies: dashboard loading, AI briefing, recommendations, widget config, widget styling, totals drill-down, category trends, planned expense insertion, navigation events, and currency observation.
3. Dashboard widget render coverage is not contract-tested. New `DashboardWidget` types can silently render as empty UI because the render `when` has an `else -> Box(...)`.
4. Dashboard widget IDs are duplicated as string mappings in `HomeViewModel.getWidgetId()`, config rows, style config, and render logic. This is a drift point.
5. `SafeToSpend` has a documented “no budget” fallback that currently looks misleading in UI.
6. Several Home flows bypass deterministic `TimeProvider` and use `Calendar.getInstance()` in the composable.
7. Currency reactivity is fragile. Category trends load once during init using placeholder `"EUR"` from `homeCurrency`.
8. Totals drill-down state uses only `selectedPeriod` and `parentPeriod`, which is too weak for multi-level breadcrumbs and can lose context.
9. Totals/category-breakdown errors are mostly invisible to users.
10. Widget config mutation uses synchronous repository calls from UI event handlers.
11. Widget rendering lacks focused Compose/component tests.
12. Cross-widget financial invariants are not enforced: dashboard totals, totals drill-down, analytics totals, budget widgets, and currency conversion should agree for the same seed period.

Recommended approach: **do not rewrite the dashboard in one pass**. Add contract tests and small safety fixes first, then extract UI renderer components.

---

## 2. Current data/render pipeline

### Main pipeline

```text
DashboardDataProvider.getProcessedDataFlow(...)
        ↓
ComputeDashboardWidgetsUseCase.compute(...)
        ↓
CompiledDashboardData(allWidgets, totalSpent, txCount)
        ↓
DashboardRepository.configFlow filters/orders widgets
        ↓
HomeViewModel.dashboard: StateFlow<DashboardState>
        ↓
HomeScreen LazyVerticalGrid
        ↓
when (DashboardWidget) render card
```

### Secondary state flows

`HomeScreen` also collects:

```kotlin
viewModel.categories
viewModel.recommendations
viewModel.homeCurrency
viewModel.totalsDrillDownState inside TotalsDashboard rendering
viewModel.navigationActions via LaunchedEffect
```

### Main UI actions

- widget edit mode
- move widget
- hide widget
- toggle widget style
- navigate to review
- navigate to recurring expenses
- navigate to transactions with filters
- navigate to analytics
- navigate to feature destination
- add planned expense
- load category breakdown
- recommendation click/dismiss

---

## 3. Baseline commands

Agent should start with:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*HomeViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Dashboard*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ComputeDashboardWidgetsUseCase*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*TotalsAggregation*" --stacktrace
```

If Compose tests exist:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Robolectric Compose tests exist:

```bash
./gradlew :app:testDebugUnitTest --tests "*HomeScreen*" --stacktrace
```

Stop at first compile failure.

---

# 4. Issues

## S4-001 — `HomeScreen.kt` is a monolithic high-blast-radius composable

Severity: High  
Files:
- `HomeScreen.kt`

Evidence:
- The file contains root screen state collection, app bar, loading/error/empty states, full widget render switch, feature menu, quick settings, debug overlay, add-planned-expense dialog, category breakdown sheet, and `WidgetWrapper`.
- It renders many `DashboardWidget` variants inline.

Problem:
This makes debugging hard because a failure in any single widget recompiles/retests the whole Home screen. It also makes Compose tests expensive and brittle.

Fix strategy:
Split by stable UI responsibility, not by feature count.

Implementation plan:

Create these files:

```text
ui/screens/home/HomeScreen.kt                 // root only
ui/screens/home/HomeDashboardGrid.kt          // LazyVerticalGrid
ui/screens/home/DashboardWidgetRenderer.kt    // when(widget)
ui/screens/home/HomeTopBar.kt                 // title/actions
ui/screens/home/HomeDialogs.kt                // quick settings/add/category
ui/screens/home/WidgetWrapper.kt              // edit overlay
ui/screens/home/HomeNavigationEventHandler.kt // optional
```

Root should become roughly:

```kotlin
@Composable
fun HomeScreen(...) {
    val state by viewModel.dashboard.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState()

    HomeNavigationEventHandler(viewModel, callbacks)

    HomeScreenContent(
        state = state,
        categories = categories,
        recommendations = recommendations,
        homeCurrency = homeCurrency,
        actions = HomeActions(...)
    )
}
```

Acceptance:
- `HomeScreen.kt` root file is mostly orchestration.
- `DashboardWidgetRenderer` can be tested independently.
- Dialog rendering can be tested without dashboard data fixtures.
- No business logic moves into composables during extraction.

---

## S4-002 — `HomeViewModel` has too many responsibilities/dependencies

Severity: High  
Files:
- `HomeViewModel.kt`

Current responsibilities:
- dashboard data flow
- dashboard config ordering
- widget visibility/edit mode
- widget style config
- category trend loading
- AI briefing state
- recommendation refresh/navigation/dismissal
- planned expense insert
- totals drill-down state machine
- category breakdown loading
- home currency observation
- navigation events

Problem:
The ViewModel is difficult to unit-test. Constructor fixtures will break often. Failures from unrelated subsystems can break the Home screen.

Fix strategy:
Introduce small injected coordinators/facades. Do not change screen behavior first.

Recommended extraction:

```text
HomeDashboardStateAssembler
HomeTotalsDrillDownController
HomeWidgetConfigController
HomeRecommendationCoordinator
HomeAiBriefingPresenter
HomeCategoryTrendsLoader
HomePlannedExpenseCoordinator
```

Short-term minimal version:
- Extract only totals drill-down first.
- Extract widget config mutations second.
- Extract AI briefing third.

Acceptance:
- `HomeViewModel` constructor dependency count decreases.
- `HomeTotalsDrillDownControllerTest` covers drill-down/back behavior.
- `HomeWidgetConfigControllerTest` covers move/hide/style.
- `HomeViewModelTest` becomes orchestration-only.

---

## S4-003 — Dashboard widget render coverage can silently fail

Severity: Critical maintainability  
Files:
- `HomeScreen.kt`
- `HomeViewModel.kt`
- `ComputeDashboardWidgetsUseCase.kt`

Evidence:
- `DashboardWidget` is a sealed class with many variants.
- `HomeScreen` has a `when (widget)` render block.
- The render block currently has an `else -> Box(modifier = Modifier.fillMaxWidth())` fallback.

Problem:
Because of the `else`, adding a new dashboard widget can compile but render as an invisible empty box. This defeats Kotlin sealed-class exhaustiveness.

Fix strategy:
Remove the `else` branch and make the `when` exhaustive.

Implementation plan:
1. Move render logic to:

```kotlin
@Composable
fun DashboardWidgetRenderer(
    widget: DashboardWidget,
    state: DashboardState,
    homeCurrency: String,
    categories: List<Category>,
    actions: HomeActions,
    viewModelActions: HomeViewModelActions
)
```

2. Remove:

```kotlin
else -> Box(modifier = Modifier.fillMaxWidth())
```

3. Ensure every `DashboardWidget` variant is explicitly handled.

Acceptance:
- Adding a new `DashboardWidget` fails compilation until rendered.
- Add test: `DashboardWidgetContractTest` with sample widgets for render metadata.

---

## S4-004 — Widget ID/config/style mapping drift

Severity: High  
Files:
- `HomeViewModel.kt`
- `DashboardRepository.kt`
- widget style config
- `ComputeDashboardWidgetsUseCase.kt`

Evidence:
`HomeViewModel.getWidgetId(widget)` maps each widget type to strings like:
- `safe_to_spend`
- `spending_pace`
- `review_alert`
- `totals_dashboard`
- `financial_stress_forecast`
- etc.

These IDs must match:
- default dashboard config
- persisted config
- style config eligibility
- render keys
- docs

Problem:
A new widget may exist in `CompiledDashboardData.allWidgets` but never appear because config lacks its ID. Or a style may be toggleable but not render retro, or vice versa.

Fix strategy:
Create one metadata table.

Implementation plan:

```kotlin
data class DashboardWidgetMeta(
    val id: String,
    val type: KClass<out DashboardWidget>,
    val defaultVisible: Boolean,
    val defaultOrder: Int,
    val span: DashboardWidgetSpan,
    val supportsStyleToggle: Boolean
)

enum class DashboardWidgetSpan { HALF, FULL }
```

Start with test-only metadata if production refactor is too large.

Tests:
```kotlin
@Test fun `all widget sample ids are unique`()
@Test fun `all widget ids exist in default config`()
@Test fun `styled widgets have render support`()
@Test fun `all widget ids have stable keys`()
```

Acceptance:
- One contract fails when a widget is added without config/render/style policy.
- `getWidgetId` delegates to metadata or is tested against it.

---

## S4-005 — `SafeToSpend` no-budget fallback is misleading

Severity: High UX / financial correctness  
Files:
- `ComputeDashboardWidgetsUseCase.kt`
- `HomeScreen.kt`

Evidence:
The domain comment for `DashboardWidget.SafeToSpend` says that when `totalBudget == null`, `amount` is month-to-date total spent, not actual money safe to spend. It recommends UI should show a CTA encouraging the user to set a budget.

Current UI:
- Always labels the widget as safe-to-spend.
- Shows `AmountText(amount = widget.amount)`.
- Only hides progress when `totalBudget == null`.

Problem:
If no budget exists, the card can display money already spent under a “safe to spend” title. That is financially misleading.

Fix strategy:
Render a separate no-budget state.

Implementation plan:

```kotlin
@Composable
fun SafeToSpendCard(
    widget: DashboardWidget.SafeToSpend,
    currency: String,
    onSetBudget: () -> Unit
) {
    if (widget.totalBudget == null || widget.totalBudget <= 0.0) {
        NoBudgetSafeToSpendCard(
            monthToDateSpent = widget.amount,
            currency = currency,
            onSetBudget = onSetBudget
        )
    } else {
        SafeToSpendBudgetedCard(...)
    }
}
```

Budgeted progress:
```kotlin
val progress = if (widget.totalBudget > 0.0) {
    ((widget.totalBudget - widget.amount) / widget.totalBudget)
        .toFloat()
        .coerceIn(0f, 1f)
} else 0f
```

Acceptance:
- no-budget card says “Set a monthly budget...” or equivalent.
- no-budget card does not label spent amount as safe-to-spend.
- progress never receives NaN/Infinity.
- test cases:
  - `totalBudget = null`
  - `totalBudget = 0.0`
  - `amount > totalBudget`
  - `amount < 0`

---

## S4-006 — Home composable bypasses deterministic time

Severity: High testability / date correctness  
Files:
- `HomeScreen.kt`
- `HomeViewModel.kt`

Evidence:
`HomeScreen` uses:
- `Calendar.getInstance().get(Calendar.YEAR)` in `LaunchedEffect(Unit)` fallback.
- `Calendar.getInstance()` to create day transaction filters in Budget Block Party click handlers.

Problem:
Home has an injected `TimeProvider`, but composable code uses wall-clock time. Tests can become non-deterministic. It can also disagree with the ViewModel’s `referenceNowMillis`.

Fix strategy:
Move date calculations into ViewModel or pure utility functions that accept `referenceNowMillis`.

Implementation plan:
1. Remove initial totals loading from composable.
2. In ViewModel init, call:

```kotlin
loadTotalsForYear(TimePeriodUtils.getYear(timeProvider.now()))
```

or expose:
```kotlin
fun onHomeStarted()
```

3. Replace day range logic with utility:

```kotlin
fun dayRangeFor(dateMs: Long, zoneId: ZoneId): LongRangeLike
```

or use existing `TimePeriodUtils`.

4. BudgetBlockParty click should become:

```kotlin
onDaySelected(dateMs)
```

ViewModel/action layer emits `TransactionFilter`.

Acceptance:
- no `Calendar.getInstance()` in `HomeScreen.kt`.
- tests can set fixed `TimeProvider`.
- initial totals year equals fixed test clock year.

---

## S4-007 — Currency reactivity bug: category trends load with placeholder `"EUR"`

Severity: High for multi-currency correctness  
Files:
- `HomeViewModel.kt`

Evidence:
- `homeCurrency` state starts with placeholder `"EUR"`.
- `loadCategoryTrends()` runs in `init`.
- It calls `advancedAnalyticsEngine.getCategoryAnalytics(period, displayCurrency = homeCurrency.value)`.
- It is not automatically re-run when `homeCurrency` changes.

Problem:
If the actual home currency is not EUR, category trend widgets may be computed/displayed in the wrong currency until manual reload.

Fix strategy:
Make category trends reactive to home currency and period.

Implementation plan:

```kotlin
private val categoryTrends: StateFlow<Map<Long, CategoryTrendInfo>> =
    homeCurrency
        .flatMapLatest { currency ->
            flow {
                val period = advancedAnalyticsEngine.getPeriodRange(AnalyticsPeriod.MONTH)
                val (analytics, _) = advancedAnalyticsEngine.getCategoryAnalytics(
                    period,
                    displayCurrency = currency
                )
                emit(analytics.toTrendMap())
            }.catch {
                Timber.e(it, "Error loading category trends")
                emit(emptyMap())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
```

Then remove manual `_categoryTrends` mutation if possible.

Acceptance:
- changing home currency recomputes category trends.
- tests verify initial non-EUR currency does not use `"EUR"`.
- no one-time init trend load using placeholder currency.

---

## S4-008 — Dashboard totals/currency consistency is not enforced

Severity: Critical financial correctness  
Files:
- `ComputeDashboardWidgetsUseCase.kt`
- `HomeViewModel.kt`
- `TotalsAggregationEngine`
- analytics screens indirectly

Problem:
Home dashboard displays:
- safe-to-spend
- period summary
- trend chart
- top categories
- financial weather
- runway
- totals dashboard
- recommendations
- analytics-linked values

These must agree for the same period/currency. No visible contract guarantees this.

Fix strategy:
Add cross-widget invariant tests with a deterministic fixture.

Implementation plan:
Create:

```text
app/src/test/java/.../ui/screens/home/HomeDashboardFinancialInvariantTest.kt
```

Use fixed data:
- 3 expenses
- 1 refund or transfer if supported
- 2 categories
- 2 currencies if test infra supports conversion
- fixed date/time
- fixed home currency

Assertions:
- dashboard total spent = sum of expense purchases for period
- top category percentages sum to ~100%
- top category total sum = month total, within tolerance
- totals drill-down month total = dashboard month total
- analytics month total = dashboard month total, if analytics fixture exists
- all rendered amounts use selected home currency

Acceptance:
- currency mismatch fails test.
- adding a new aggregate widget requires a fixture assertion or explicit exclusion.

---

## S4-009 — Totals drill-down state machine is fragile

Severity: High  
Files:
- `HomeViewModel.kt`
- `TotalsDashboardCard.kt`

Evidence:
Current state tracks:
- `currentLevel`
- `selectedPeriod`
- `parentPeriod`
- `periodTotals`
- `categoryBreakdown`

Problem:
A single `parentPeriod` is not enough for Year → Month → Week → Day breadcrumb navigation. It can lose or misassign context when drilling back up. For example, drilling down Month → Week → Day and then up can leave `selectedPeriod`/`parentPeriod` ambiguous.

Fix strategy:
Replace `selectedPeriod` + `parentPeriod` with an explicit stack.

Implementation plan:

```kotlin
data class DrillDownNode(
    val level: PeriodType,
    val period: PeriodTotal?
)

data class PeriodDrillDownState(
    val currentLevel: PeriodType,
    val breadcrumb: List<DrillDownNode>,
    val periodTotals: List<PeriodTotal>,
    val categoryBreakdown: List<CategoryBreakdown>,
    val isLoading: Boolean,
    val error: UiText?
)
```

Operations:
- `loadYear(year)` initializes Month view with breadcrumb `[Year]` or empty, depending desired UX.
- `drillDown(period)` pushes current selection.
- `drillUp()` pops.
- `selectedPeriod = breadcrumb.lastOrNull()?.period`.

If full refactor is too large, create `HomeTotalsDrillDownController` first and preserve public state shape.

Tests:
```text
HomeTotalsDrillDownControllerTest
```

Required cases:
- initial month load
- month → week
- week → day
- day → week
- week → month
- month → year
- invalid period key surfaces error, no crash
- failed engine call preserves previous visible data or shows inline error

Acceptance:
- drill state is deterministic.
- selected label is correct at every level.
- back/up never parses the wrong period key.

---

## S4-010 — Totals drill-down errors are invisible or stale

Severity: Medium/High  
Files:
- `HomeViewModel.kt`
- `HomeScreen.kt`
- `TotalsDashboardCard.kt`
- `CategoryBreakdownSheet.kt`

Evidence:
- `_totalsDrillDownState.error` is set in failures.
- `TotalsDashboardCard` is called with `isLoading`, but no visible `error` parameter.
- `loadCategoryBreakdownForPeriod()` catches errors and only logs.
- `HomeScreen` sets `showCategoryBreakdown = true` immediately after requesting breakdown.

Problem:
The user may see stale or empty category breakdown with no explanation. Totals card failures are not clearly recoverable.

Fix strategy:
Promote totals/card error state to UI.

Implementation plan:
1. Extend `TotalsDashboardCard`:

```kotlin
fun TotalsDashboardCard(
    ...,
    error: UiText?,
    onRetry: () -> Unit
)
```

2. In HomeScreen:
```kotlin
error = totalsState.error
onRetry = { viewModel.reloadCurrentTotalsLevel() }
```

3. Add breakdown-specific state:
```kotlin
data class CategoryBreakdownUiState(
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val categories: List<CategoryBreakdown> = emptyList(),
    val error: UiText? = null,
    val periodLabel: String = ""
)
```

4. Only show loaded data or loading state; do not show stale categories after failure.

Acceptance:
- totals failure shows inline retry.
- category breakdown failure shows error, not stale data.
- tests cover failure paths.

---

## S4-011 — Widget config mutations are synchronous and unreported

Severity: Medium/High  
Files:
- `HomeViewModel.kt`
- `DashboardRepository.kt`

Evidence:
`moveWidget()` and `toggleWidgetVisibility()` call:
- `dashboardRepository.getDashboardConfig()`
- `dashboardRepository.saveDashboardConfigSync(...)`

Problem:
These run directly from UI events. If repository touches disk/DataStore/DB, this can block the main thread. Failures are not reported.

Fix strategy:
Make config operations suspend/IO-backed and expose failure.

Implementation plan:
1. Add suspend repository APIs if absent:

```kotlin
suspend fun getDashboardConfigSnapshot(): List<DashboardWidgetConfig>
suspend fun saveDashboardConfig(config: List<DashboardWidgetConfig>)
```

2. In ViewModel:
```kotlin
fun moveWidget(widgetId: String, moveUp: Boolean) {
    viewModelScope.launch {
        runCatching {
            widgetConfigController.move(widgetId, moveUp)
        }.onFailure {
            _events.emit(HomeUiEvent.ShowSnackbar(...))
        }
    }
}
```

3. Add tests:
- move first up = no-op
- move last down = no-op
- move middle swaps order
- hide widget toggles visible
- save failure emits event

Acceptance:
- no sync repository mutation from Home UI.
- config errors are user-visible or at least evented.
- tests do not use real repository storage.

---

## S4-012 — Widget edit overlay lacks boundary/restore UX

Severity: Medium  
Files:
- `WidgetWrapper` in `HomeScreen.kt`

Evidence:
- Move up/down buttons are always enabled.
- Boundary moves return early in ViewModel but UI still appears actionable.
- If config is empty or all widgets are hidden, Home shows generic empty message.

Problem:
Edit mode can feel broken. Users may hide everything and not understand how to restore layout.

Fix strategy:
Pass edit metadata to wrapper.

Implementation plan:

```kotlin
data class WidgetEditControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val canHide: Boolean,
    val canToggleStyle: Boolean
)
```

In grid item rendering, derive index from visible/edit list and disable unavailable controls.

Add reset layout action in empty state/edit mode:
```kotlin
Button(onClick = viewModel::resetDashboardLayout) {
    Text(stringResource(R.string.dashboard_reset_layout))
}
```

Acceptance:
- first widget’s move-up disabled.
- last widget’s move-down disabled.
- empty widget config can be restored.
- tests cover hidden-all recovery.

---

## S4-013 — `HomeViewModel.dashboard` combine uses unsafe `Array<Any?>` casts

Severity: Medium  
Files:
- `HomeViewModel.kt`

Evidence:
The ViewModel combines six flows and casts `params[0]`, `params[1]`, etc.

Problem:
This is fragile and can produce runtime `ClassCastException` during refactors. It is also difficult for agents to modify safely.

Fix strategy:
Introduce typed intermediate state.

Implementation option:

```kotlin
data class HomeDashboardInputs(
    val processed: ProcessedDashboardUiState,
    val editMode: Boolean,
    val config: List<DashboardWidgetConfig>,
    val aiBriefing: AiLoadState<DashboardBriefingUi>,
    val widgetStyles: WidgetStyleConfig,
    val categoryTrends: Map<Long, CategoryTrendInfo>
)
```

Then use nested combine or helper extension to create `HomeDashboardInputs`.

Acceptance:
- no `Array<Any?>` cast in dashboard state assembly.
- tests fail at compile-time if input type changes.

---

## S4-014 — Recommendation navigation lacks error handling

Severity: Medium  
Files:
- `HomeViewModel.kt`
- `HomeScreen.kt`

Evidence:
`navigateToRecommendation()` sets `_selectedRecommendation` then launches resolver and emits action.

Problem:
If resolver throws or returns `NoOp`, selected state may remain set and user sees no feedback.

Fix strategy:
Wrap resolver in `runCatching`.

Implementation:
```kotlin
fun navigateToRecommendation(rec: DashboardFollowThroughRecommendation) {
    viewModelScope.launch {
        _selectedRecommendation.value = rec
        runCatching {
            navigationTargetResolver.resolve(rec.navigationTarget, rec.filterCriteria)
        }.onSuccess { action ->
            if (action == NavigationAction.NoOp) {
                _events.emit(HomeUiEvent.ShowSnackbar(...))
            } else {
                _navigationActions.emit(action)
            }
        }.onFailure {
            Timber.e(it, "Failed to resolve recommendation navigation")
            _selectedRecommendation.value = null
            _events.emit(HomeUiEvent.ShowSnackbar(...))
        }
    }
}
```

Acceptance:
- resolver failure does not leave stale selected recommendation.
- UI receives feedback.
- test covers failure and NoOp.

---

## S4-015 — Add planned expense closes dialog even if save fails

Severity: Medium  
Files:
- `HomeScreen.kt`
- `HomeViewModel.kt`

Evidence:
`AddPlannedExpenseDialog` confirm calls `viewModel.addPlannedExpense(...)` then immediately closes dialog.

Problem:
If repository insertion fails, the dialog closes and the user receives no error. Also validation appears thin at Home layer.

Fix strategy:
Move planned-expense flow to a UI state machine.

Implementation:
```kotlin
data class AddPlannedExpenseUiState(
    val isSaving: Boolean = false,
    val error: UiText? = null
)
```

`addPlannedExpense()` should return/event success or failure:
```kotlin
fun addPlannedExpense(...) {
    viewModelScope.launch {
        _addPlannedState.update { it.copy(isSaving = true, error = null) }
        runCatching { plannedExpenseRepository.addPlannedExpense(...) }
            .onSuccess {
                _addPlannedState.update { AddPlannedExpenseUiState() }
                _events.emit(HomeUiEvent.CloseAddPlannedExpenseDialog)
            }
            .onFailure {
                _addPlannedState.update {
                    it.copy(isSaving = false, error = UiText.StringResource(...))
                }
            }
    }
}
```

Acceptance:
- invalid amount/date/blank description are rejected before repository call.
- save failure keeps dialog open and shows error.
- success closes dialog.

---

## S4-016 — Currency formatting is inconsistent in Home widgets

Severity: High if multi-currency is active  
Files:
- `HomeScreen.kt`
- widget components

Evidence from Home screen:
- some widgets receive `homeCurrency`
- `PeriodSummary` uses both `CurrencyFormatter.formatMoney(...)` and `CurrencyFormatter.format(...)`
- `SafeToSpend` calls `AmountText(amount = widget.amount)` without visible currency argument in the call site
- `CategorySpending` has default currency `"EUR"` in domain model

Problem:
Different widgets may display different symbols/rounding for the same dashboard state.

Fix strategy:
Create a `DashboardCurrencyContext`.

Implementation:
```kotlin
data class DashboardCurrencyContext(
    val code: String,
    val formatter: (Double) -> String
)
```

Pass it to every Home widget renderer. Remove default `"EUR"` from UI-facing models where possible, or make it explicit test-only fallback.

Tests:
- non-EUR home currency renders all main widget amounts with same code/symbol.
- category spending rows use category/model currency only if intentionally already converted.

Acceptance:
- no production Home widget silently defaults to EUR.
- Home dashboard currency changes update visible widgets.

---

## S4-017 — AI briefing status is collapsed into the insight slot

Severity: Medium  
Files:
- `HomeViewModel.kt`
- `HomeScreen.kt`

Evidence:
`DashboardWidget.NaturalLanguageInsight` slot displays AI briefing if `state.aiBriefing is Ready`; otherwise deterministic insight. Loading/error/disabled states are mostly invisible.

Problem:
Users may not know whether AI briefing is disabled, loading, failed, or stale. This matters because AI settings/privacy are cross-cutting.

Fix strategy:
Render AI states explicitly inside the insight card.

Implementation:
```kotlin
when (val briefing = state.aiBriefing) {
    AiLoadState.Disabled -> DeterministicInsight(...)
    AiLoadState.Idle -> DeterministicInsight(...)
    AiLoadState.Loading -> AiBriefingLoading(...)
    is AiLoadState.Error -> AiBriefingError(message = briefing.message, fallback = widget)
    is AiLoadState.Ready -> AiBriefingReady(...)
}
```

Acceptance:
- AI loading/error states are visible but do not block dashboard.
- tests cover each AI state.

---

## S4-018 — Fixed 2-column grid is not adaptive

Severity: Low/Medium  
Files:
- `HomeScreen.kt`

Evidence:
Home uses:
```kotlin
GridCells.Fixed(2)
```

Problem:
On very narrow screens, half-width cards can clip. On tablets/foldables, the layout underuses width.

Fix strategy:
Use adaptive grid or window-size-class policy.

Implementation:
```kotlin
val columns = if (isCompactWidth) GridCells.Fixed(2)
              else GridCells.Adaptive(minSize = 180.dp)
```

Or keep fixed columns for now but add constrained-width tests for half cards.

Acceptance:
- Pending review and spending pace cards are readable at common compact widths.
- Large screens can show more cards or larger full-span cards.

---

## S4-019 — Category breakdown can show stale data

Severity: Medium  
Files:
- `HomeScreen.kt`
- `HomeViewModel.kt`

Evidence:
`showCategoryBreakdown = true` is set immediately after:
- `viewModel.loadCategoryBreakdownForCurrentPeriod()`
- `viewModel.loadCategoryBreakdownForPeriod(period)`

Problem:
Because loading is asynchronous and `categoryBreakdown` lives in totals state, the sheet can open with previous period data until the new load finishes.

Fix strategy:
Use explicit `breakdownRequestId` or dedicated breakdown state.

Implementation:
```kotlin
data class CategoryBreakdownSheetState(
    val visible: Boolean,
    val requestKey: String?,
    val loading: Boolean,
    val periodLabel: String,
    val categories: List<CategoryBreakdown>,
    val error: UiText?
)
```

Acceptance:
- opening breakdown for period A then B cannot show A while B loads unless clearly labeled as loading.
- test with delayed fake engine.

---

## S4-020 — Home dashboard lacks focused render/action tests

Severity: High  
Files:
- tests missing or insufficient for Home dashboard

Fix strategy:
Add targeted tests; avoid full screenshot testing.

Recommended tests:

### JVM tests
```text
HomeViewModelDashboardStateTest
HomeTotalsDrillDownControllerTest
HomeWidgetConfigControllerTest
DashboardWidgetContractTest
HomeDashboardFinancialInvariantTest
HomeRecommendationNavigationTest
```

### Compose/Robolectric/instrumented tests
```text
DashboardWidgetRendererTest
SafeToSpendCardTest
WidgetWrapperEditModeTest
HomeDashboardGridTest
TotalsDashboardCardTest
BudgetBlockPartyCardTest
```

Minimum cases:
- loading state shows skeleton
- error state shows retry
- empty widgets show restore/edit guidance
- every widget sample renders at least one identifying text/semantics
- edit overlay move/hide/style callbacks fire
- safe-to-spend no-budget CTA
- category breakdown loading/error/data
- recommendation click emits navigation
- recommendation dismiss calls callback

Acceptance:
- Home failures become local to a small test.
- A new widget requires adding a sample/render assertion.

---

# 5. Suggested test fixtures

Create:

```text
app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeDashboardFixtures.kt
```

Contents:
```kotlin
object HomeDashboardFixtures {
    val fixedNow = ...
    val homeCurrency = "USD"

    fun sampleWidgets(): List<DashboardWidget> = listOf(
        DashboardWidget.SafeToSpend(...),
        DashboardWidget.PendingReviewAlert(...),
        DashboardWidget.PeriodSummary(...),
        DashboardWidget.TopCategories(...),
        DashboardWidget.RecentTransactions(...),
        DashboardWidget.TotalsDashboard,
        ...
    )

    fun defaultDashboardConfigForSamples(): List<DashboardWidgetConfig> = ...
}
```

Need fake dependencies:
- `FakeDashboardDataProvider`
- `FakeDashboardRepository`
- `FakeCategoryRepository`
- `FakePlannedExpenseRepository`
- `FakeTotalsAggregationEngine`
- `FakeCurrencySettingsRepository`
- `FakeWidgetStyleRepository`
- `FakeRecommendationStateManager`
- `FakeNavigationTargetResolver`

Use `MainDispatcherRule`.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile.
2. Run existing Home/Dashboard tests.
3. Grep current test coverage:

```bash
find app/src/test app/src/androidTest -iname "*Home*" -o -iname "*Dashboard*"
```

4. Create a widget inventory doc from source:
   - widget class
   - ID
   - default config presence
   - render branch
   - span
   - style toggle support
   - currency source
   - navigation actions

## Phase B — Add contract tests first

Add:
```text
DashboardWidgetContractTest.kt
HomeTotalsDrillDownControllerTest.kt
HomeDashboardFinancialInvariantTest.kt
```

Even if some fail initially, they define the target.

## Phase C — Safety fixes

1. Remove `else -> Box` from widget render `when`.
2. Fix SafeToSpend no-budget/zero-budget UI.
3. Move initial totals load out of `HomeScreen`.
4. Remove `Calendar.getInstance()` from Home composable.
5. Make category trends currency-reactive.
6. Add visible totals/breakdown error states.
7. Wrap recommendation resolver errors.
8. Do not close planned expense dialog on failed save.

## Phase D — Extract UI slices

1. `HomeTopBar`
2. `HomeDashboardGrid`
3. `DashboardWidgetRenderer`
4. `WidgetWrapper`
5. `HomeDialogs`

Keep behavior unchanged except safety fixes.

## Phase E — Extract ViewModel coordinators

1. `HomeTotalsDrillDownController`
2. `HomeWidgetConfigController`
3. `HomeCategoryTrendsLoader`
4. `HomeRecommendationCoordinator`
5. `HomeAiBriefingPresenter`

## Phase F — Cross-slice golden tests

After Slice 4 local tests pass, add:
1. manual add planned expense updates FinancialWeather/BudgetBlockParty
2. review count changes update pending-review widget
3. currency change updates all Home amounts
4. dashboard month total equals totals drill-down month total
5. hidden widget remains hidden after reload
6. no-budget SafeToSpend CTA navigates to Budget

---

# 7. Acceptance checklist for Slice 4 green

Slice 4 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] Home/Dashboard unit tests pass.
- [ ] Every `DashboardWidget` type has:
  - stable ID,
  - default config policy,
  - render branch,
  - span policy,
  - currency policy,
  - test sample.
- [ ] Widget render `when` has no silent `else`.
- [ ] Safe-to-spend no-budget state is not misleading.
- [ ] Progress calculations cannot emit NaN/Infinity.
- [ ] HomeScreen does not use raw wall-clock `Calendar.getInstance()`.
- [ ] Category trends recompute when home currency changes.
- [ ] Totals drill-down has deterministic state-machine tests.
- [ ] Totals/category-breakdown errors are visible.
- [ ] Widget config mutations are async/safe and tested.
- [ ] Planned expense save failure is visible and does not close the dialog.
- [ ] Recommendation navigation failure is handled.
- [ ] Dashboard financial invariant test exists.
- [ ] Docs are updated only after source/tests are green.

---

# 8. Agent guardrails

Do:
- Add tests before refactors where possible.
- Extract UI into small composables without changing behavior.
- Prefer pure state-machine tests for totals drill-down.
- Use fake repositories/use cases for HomeViewModel tests.
- Protect financial/currency invariants with deterministic fixtures.
- Keep `NavigationDestination` integration unchanged unless Slice 1 fixes are being applied.

Do not:
- Rewrite Home with Navigation Compose.
- Snapshot-test the entire dashboard.
- Change all dashboard widget business formulas in one PR.
- Hide failures with more fallback UI.
- Add new dashboard widgets until the widget contract exists.

Main invariant:

> For a fixed time, fixed data set, and fixed home currency, every Home dashboard widget must render deterministically, use the same financial basis, expose clear loading/error states, and have a tested navigation/action path.