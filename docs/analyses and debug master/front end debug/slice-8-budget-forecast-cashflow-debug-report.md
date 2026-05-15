# Slice 8 Debug Report — Budget, Forecasting, Cash Flow UI

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/screens/budget/*`
- `ui/screens/cashflow/*`
- `ui/components/SpendingPaceGauge.kt`
- `ui/components/ForecastTimeline.kt`
- `ui/components/PeriodGridView.kt`
- `ui/components/PeriodBlock.kt`
- `ui/components/PeriodNavigationBar.kt`
- connected domain:
  - `BudgetRepository`
  - `BudgetForecastingEngine`
  - `BudgetAutopilotEngine`
  - `SharedExpenseBudgetOffsetEngine`
  - `CashFlowCalculator`
  - currency normalization/conversion infrastructure

Sources inspected:
- Budget folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/budget
- `BudgetViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt
- `BudgetScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt
- `BudgetForecastingViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt
- `BudgetForecastingScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingScreen.kt
- Cashflow folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow
- `CashFlowCalendarViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarViewModel.kt
- `CashFlowCalendarScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarScreen.kt
- `SpendingPaceGauge.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/SpendingPaceGauge.kt
- `ForecastTimeline.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ForecastTimeline.kt
- `PeriodGridView.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/PeriodGridView.kt
- `PeriodBlock.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/PeriodBlock.kt
- `PeriodNavigationBar.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/PeriodNavigationBar.kt
- `CashFlowCalculator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt
- `BudgetForecastingEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt
- `BudgetAutopilotEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt
- `SharedExpenseBudgetOffsetEngine.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt
- Segments: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md
- UI map: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- UI component library: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/UI_COMPONENT_LIBRARY.md

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 8 crosses four sensitive domains:

- Segment 2: Budget Management
- Segment 1: Forecasting & Runway
- Segment 13: Cash Flow Planning
- Segment 16: Currency & Exchange

The implementation has good building blocks:
- budget status flow,
- shared-expense budget offset engine,
- budget forecasting engine,
- budget autopilot recommendations,
- cash-flow daily projection,
- recurring occurrence prediction,
- UI visualization components.

But this slice has several high-risk issues:

1. Budget CRUD dialogs close before async save/update result is known.
2. Budget UI does not clearly preserve/format currency; several paths still use placeholder or default `"EUR"`.
3. `BudgetAutopilotEngine` still suppresses deprecated raw SQL aggregate usage and is not multi-currency safe.
4. Autopilot errors are swallowed even though UI state has `autopilotError`.
5. Budget forecast screen initializes `homeCurrency` as empty string and may format money before currency is resolved.
6. Budget forecast generation can race; older forecasts can overwrite newer refreshes.
7. Budget forecast docs say `ForecastTimeline` is used, but the screen currently does not render it.
8. Cash-flow calendar recomputes on every starting-balance keystroke and has no error state.
9. Cash-flow month navigation can race and show stale data.
10. Cash-flow daily detail totals sum raw item amounts and format them as home currency.
11. `CashFlowCalculator` drops failed currency conversions without surfacing data quality to UI.
12. Shared-expense budget offset conversion failures are hidden from Budget UI.
13. Many visualization components have hardcoded fallback `"EUR"`, hardcoded English, weak semantics, and no test tags.
14. Calendar/date code uses raw `Calendar.getInstance()` in UI, hurting determinism and DST/time-zone testing.
15. Existing component docs drift from source: some components are documented as consumers but not actually wired.

Recommended strategy:
- First add invariant tests for budget totals, currency, cash-flow, and forecast generation.
- Then fix critical currency/data-integrity issues.
- Then extract UI components and add Compose tests.
- Do not rewrite all budget/cashflow/forecast code in one PR.

---

## 2. Baseline commands

Run first:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*BudgetViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BudgetForecasting*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BudgetAutopilot*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CashFlow*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*SpendingPace*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ForecastTimeline*" --stacktrace
```

Inventory current tests:

```bash
find app/src/test app/src/androidTest \
  -iname "*Budget*" -o \
  -iname "*Forecast*" -o \
  -iname "*CashFlow*" -o \
  -iname "*Pace*" -o \
  -iname "*PeriodGrid*" -o \
  -iname "*PeriodBlock*"
```

If Compose tests exist:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

Stop at first compile failure.

---

## 3. Current architecture map

### Budget screen

```text
BudgetRepository.getBudgetStatuses()
        ↓
BudgetViewModel.adjustedBudgetStatuses
        ↓
SharedExpenseBudgetOffsetEngine.calculateEffectiveBudgetSpend(...)
        ↓
BudgetUiState
        ↓
BudgetScreen LazyColumn
        ↓
BudgetSummaryCard / SuggestionsBanner / AutopilotBanner / BudgetCard / AddEditBudgetDialog
```

### Budget forecast

```text
BudgetForecastingScreen receives NavigationDestination.BudgetForecasting(budget)
        ↓
BudgetForecastingViewModel.generateForecast(budget)
        ↓
BudgetForecastingEngine.generateForecast(...)
        ↓
BudgetRecommendationEngine.generateRecommendations(...)
        ↓
BudgetForecastUiState
        ↓
RiskLevelCard / ForecastDetailsCard / ConfidenceCard / RecommendationCard
```

### Cash-flow calendar

```text
CashFlowCalendarViewModel.loadCurrentMonth()
        ↓
CashFlowCalculator.calculateDailyCashFlow(...)
        ↓
DailyCashFlow list
        ↓
CashFlowCalendarScreen
        ↓
calendar grid / day modal details
```

### Cash-flow calculator

```text
expenseRepository.getExpensesBetween(...)
recurringPatternsProvider.getConfirmedPatterns()
recurringLifecycleCoordinator.generateOccurrences(...)
recurringOccurrenceDao.getByDateRange(...)
currencySettingsRepository.homeCurrency()
currencyConverter.convert(...)
        ↓
DailyCashFlow(date, income, expenses, predictedRecurring, endingBalance, riskLevel, currency)
```

---

# 4. Issues

## S8-001 — Budget add/edit dialog closes before persistence result

Severity: High  
Files:
- `BudgetScreen.kt`
- `BudgetViewModel.kt`

Evidence:
`AddEditBudgetDialog` calls `onConfirm(budgetToSave)` and immediately calls `onDismiss()`.
`BudgetViewModel.addBudget/updateBudget` are asynchronous and can fail.

Problem:
If repository save fails, the dialog closes and the user loses context. This mirrors the same failure class found in Transactions, Review, and Receipt slices.

Fix strategy:
Make budget mutations stateful.

Implementation plan:
1. Add mutation state:

```kotlin
data class BudgetMutationState(
    val activeBudgetId: Long? = null,
    val operation: BudgetOperation? = null,
    val isSaving: Boolean = false,
    val error: UiText? = null
)

enum class BudgetOperation {
    ADD,
    UPDATE,
    DELETE,
    TOGGLE
}
```

2. Add one-off events:

```kotlin
sealed interface BudgetUiEvent {
    data object BudgetSaved : BudgetUiEvent
    data class BudgetSaveFailed(val message: UiText) : BudgetUiEvent
}
```

3. UI closes dialog only after `BudgetSaved`.
4. On failure, keep dialog open and show inline error.

Acceptance:
- add failure keeps dialog open.
- update failure keeps dialog open.
- delete/toggle errors are visible.
- tests verify close-on-success only.

---

## S8-002 — Budget dialog does not pass home currency into new budgets

Severity: Critical multi-currency correctness  
Files:
- `BudgetScreen.kt`
- `BudgetViewModel.kt`
- `Budget` entity

Evidence:
`BudgetUiState.homeCurrency` exists, but `AddEditBudgetDialog` does not receive it.
New `Budget(...)` construction in the dialog does not visibly pass `currency`.
`BudgetForecastingEngine` later reads `budget.currency`.

Problem:
If the `Budget` entity default currency is `"EUR"` or another default, non-EUR users can create budgets in the wrong currency.

Fix strategy:
Budget creation must explicitly use resolved home currency or user-selected budget currency.

Implementation plan:
1. Add `currency` to `AddEditBudgetDialog`.
2. Pass `uiState.homeCurrency` only after loaded.
3. Disable Save if currency is not resolved.
4. On create:

```kotlin
Budget(
    categoryId = selectedCategory,
    amount = amt,
    period = period,
    periodMode = periodMode,
    startDate = referenceNowMillis,
    rollover = rollover,
    currency = homeCurrency
)
```

5. If per-budget currency is desired, add a `CurrencyPicker`.

Acceptance:
- non-EUR user creates budget with non-EUR currency.
- test verifies no created budget defaults to `"EUR"` unless home currency is EUR.
- Forecasting, budget status, and autopilot all use consistent budget currency.

---

## S8-003 — Budget UI formats money as raw strings, not consistent currency values

Severity: High  
Files:
- `BudgetScreen.kt`

Evidence:
Budget card uses string resources with numeric values:
- spent format,
- limit format,
- base amount,
- remaining,
- over budget,
- pending reimbursement.

It does not consistently use `CurrencyFormatter.format(...)`, nor does `BudgetCard` receive `homeCurrency`.

Problem:
Users can see bare numbers with no currency. Worse, adjusted spend from `SharedExpenseBudgetOffsetEngine` is home-currency normalized while budget limit may be budget-currency unless status already normalized. The UI does not make that basis explicit.

Fix strategy:
Create a `BudgetDisplayMoneyContext`.

Implementation plan:
1. Extend UI model:

```kotlin
data class BudgetCardUiModel(
    val spend: MoneyAmount,
    val limit: MoneyAmount,
    val remaining: MoneyAmount,
    val baseLimit: MoneyAmount?,
    val isPartial: Boolean,
    val warnings: List<UiText>
)
```

2. Format all money through one formatter.
3. Pass `homeCurrency`/budget currency explicitly.
4. Surface `AdjustedSpendBreakdown.displayCurrency`.

Acceptance:
- every budget card amount includes correct currency symbol/code.
- adjusted spend and limit are on same currency basis.
- mixed-currency fixtures fail if raw numbers are displayed.
- no bare `"(base: ${status.budget.amount})"`.

---

## S8-004 — Shared-expense budget offset conversion failures are hidden

Severity: High financial correctness  
Files:
- `SharedExpenseBudgetOffsetEngine.kt`
- `BudgetViewModel.kt`
- `BudgetScreen.kt`

Evidence:
`BudgetSpendBreakdown` includes:
- `isPartial`
- `conversionWarnings`
- `failedConversionCount`
- `displayCurrency`
- MoneyAggregate fields

But `BudgetViewModel.calculateAdjustedSpend()` maps only numeric fields into `AdjustedSpendBreakdown`; UI only shows pending reimbursements.

Problem:
If FX conversion fails, effective budget spend silently excludes those amounts. Users may think they are on track when data is partial.

Fix strategy:
Preserve data-quality metadata into UI.

Implementation plan:
1. Add to `AdjustedSpendBreakdown` or create `BudgetSpendUiBreakdown`:
   - `displayCurrency`
   - `isPartial`
   - `failedConversionCount`
   - `warnings`
2. Show warning chip in `BudgetCard`:
   - “Partial budget spend: 2 conversions missing.”
3. Add details expandable section.

Acceptance:
- failed conversion is visible in budget card.
- tests verify partial conversion produces warning.
- no silent fallback to raw spend if offset engine fails; show degraded state if possible.

---

## S8-005 — BudgetViewModel uses placeholder `"EUR"` for home currency

Severity: High  
Files:
- `BudgetViewModel.kt`

Evidence:
`BudgetUiState.homeCurrency` defaults to `"EUR"`.
`_homeCurrency` uses `stateIn(..., "EUR")`.

Problem:
A placeholder currency can leak into UI and actions before repository emits, especially Autopilot formatting and budget creation if fixed later.

Fix strategy:
Make currency loading explicit.

Implementation:

```kotlin
data class BudgetUiState(
    val homeCurrency: String? = null,
    val isCurrencyLoaded: Boolean = false,
    ...
)
```

Only allow currency-dependent actions after loaded.

Acceptance:
- initial UI does not format money as EUR for non-EUR users.
- Add Budget save disabled until currency loaded.
- test delays currency flow and verifies no EUR formatting/persistence.

---

## S8-006 — BudgetScreen error banner can overlap content

Severity: Medium  
File:
- `BudgetScreen.kt`

Evidence:
`ErrorBanner` is rendered as a sibling before loading/content and is not integrated into the `LazyColumn` or a root `Box` overlay with padding.

Problem:
Error content can visually overlap the top/content area and is not tied to screen scroll.

Fix strategy:
Use either:
- snackbar/event, or
- inline `LazyColumn` item, or
- `Box` overlay with explicit alignment/padding.

Recommended:
For budget CRUD errors, use inline error in dialog or snackbar. For load errors, use common `ErrorState`.

Acceptance:
- error does not overlap top app bar or list content.
- retry/dismiss behavior is tested.

---

## S8-007 — Budget manual operations are not idempotency-safe

Severity: High  
Files:
- `BudgetViewModel.kt`

Evidence:
`addBudget`, `updateBudget`, `deleteBudget`, `toggleBudget` do not guard against repeated calls while `ManualState.Loading`.

Problem:
Double taps can enqueue duplicate add/update/delete/toggle operations.

Fix strategy:
Add operation guard.

Implementation:

```kotlin
private fun canStartManualOperation(): Boolean =
    _manualState.value !is ManualState.Loading
```

Or track per-budget operation.

Acceptance:
- double Add calls repository once.
- double Delete calls repository once.
- Toggle spam is serialized or last-write-wins explicitly.

---

## S8-008 — Budget validation is incomplete and hardcoded

Severity: Medium/High  
Files:
- `BudgetViewModel.kt`
- `BudgetScreen.kt`

Evidence:
ViewModel validates threshold values but UI does not expose threshold inputs.
Dialog validates only amount > 0.
Error messages are hardcoded strings.

Problem:
Invalid budgets can be created/updated if entity defaults are invalid or if future UI adds threshold fields. Tests cannot assert structured errors.

Fix strategy:
Create a pure `BudgetValidator`.

Implementation:

```kotlin
data class BudgetInput(
    val amountText: String,
    val categoryId: Long?,
    val period: BudgetPeriod,
    val periodMode: BudgetPeriodMode,
    val warningThreshold: Float,
    val criticalThreshold: Float,
    val currency: String?
)

sealed interface BudgetValidationResult {
    data class Valid(val budget: Budget) : BudgetValidationResult
    data class Invalid(val errors: List<BudgetFieldError>) : BudgetValidationResult
}
```

Acceptance:
- amount, thresholds, period mode, currency, and category duplication policy are validated.
- hardcoded strings replaced by `UiText`.
- pure unit tests cover validation.

---

## S8-009 — `BudgetAutopilotEngine` uses deprecated raw SQL aggregates

Severity: Critical multi-currency correctness  
Files:
- `BudgetAutopilotEngine.kt`

Evidence:
`getHistoricalSpendForBudget()` suppresses `DEPRECATION_ERROR` and calls:
- `expenseDao.getMonthlySpendingTotalsByCategoryBetween`
- `expenseDao.getMonthlySpendingTotalsBetween`

The comment says TODO migrate to MultiCurrencyRepository.

Problem:
This bypasses canonical currency normalization. Mixed-currency historical spend can be summed raw and then used to recommend budgets.

Fix strategy:
Migrate Autopilot to currency-aware aggregation.

Implementation options:
1. Use `AnalyticsCurrencyNormalizer` with raw snapshots and group by month.
2. Use `MultiCurrencyRepository` if it exposes monthly/category aggregation.
3. Create `BudgetHistoricalSpendProvider` abstraction.

Recommended:
Create `BudgetHistoricalSpendProvider`:

```kotlin
interface BudgetHistoricalSpendProvider {
    suspend fun monthlySpend(
        categoryId: Long?,
        startInclusive: Long,
        endExclusive: Long,
        displayCurrency: String
    ): BudgetHistorySeries
}
```

Production implementation uses canonical currency normalization.

Acceptance:
- no `@Suppress("DEPRECATION_ERROR")` in `BudgetAutopilotEngine`.
- mixed-currency autopilot test passes.
- conversion failures produce partial recommendations or disable recommendation.

---

## S8-010 — Autopilot errors are swallowed

Severity: High UX/debuggability  
Files:
- `BudgetViewModel.kt`
- `BudgetScreen.kt`

Evidence:
`BudgetUiState` has `autopilotError`, but ViewModel never sets it.
`generateAutopilotRecommendations()` catches exception and returns empty recommendations.
`applyAutopilotRecommendation()` swallows errors.
`applyAllAutopilotRecommendations()` catches and logs rollback but does not show UI error.

Problem:
Autopilot can fail silently. Users see nothing or stale state.

Fix strategy:
Use typed autopilot state.

Implementation:

```kotlin
sealed interface AutopilotUiState {
    data object Idle : AutopilotUiState
    data object Loading : AutopilotUiState
    data class Ready(val recommendations: BudgetAutopilotRecommendations) : AutopilotUiState
    data class Error(val message: UiText) : AutopilotUiState
}
```

Acceptance:
- generate failure shows retryable error.
- single apply failure shows error and keeps recommendation.
- apply-all rollback shows rollback error.
- tests cover all failure paths.

---

## S8-011 — Autopilot apply-single is not transactionally safe

Severity: Medium/High  
Files:
- `BudgetViewModel.kt`

Evidence:
Apply-all has a transaction and comments about rollback.
Apply-single calls `budgetRepository.updateBudget(updatedBudget)` and then mutates recommendations if success.

Problem:
Single apply is less risky than apply-all, but still needs idempotency and explicit result handling. If update fails, UI silently keeps or removes depending path.

Fix strategy:
Guard and surface result.

Implementation:
- Track `applyingRecommendationIds`.
- Disable apply button per recommendation.
- On success remove recommendation.
- On failure keep recommendation and show error.

Acceptance:
- double apply calls repository once.
- failure keeps recommendation visible.
- success removes only the applied recommendation.

---

## S8-012 — Autopilot overall/category scaling can produce confusing numbers

Severity: Medium  
Files:
- `BudgetAutopilotEngine.kt`

Evidence:
If an overall budget exists, category recommendations can be scaled down to fit the overall budget. The reason string appends “scaled to fit overall budget”.

Problem:
Scaling can make category recommendation mathematically disconnected from category history. Users need an explicit explanation and tests.

Fix strategy:
Add a recommendation metadata field:

```kotlin
val scalingApplied: Boolean
val scaleFactor: Double?
val originalRecommendedBudget: Double?
```

Acceptance:
- UI clearly shows scaled recommendation.
- tests verify category recommendations sum <= overall budget after scaling.
- original recommendation is preserved for diagnostics.

---

## S8-013 — Budget forecasting screen initializes currency as empty string

Severity: High  
File:
- `BudgetForecastingScreen.kt`

Evidence:
`val homeCurrency by viewModel.homeCurrency.collectAsState(initial = "")`
Then it passes `homeCurrency` into `CurrencyFormatter.format(...)`.

Problem:
Formatting with empty currency can produce invalid display or fallback behavior.

Fix strategy:
Expose currency in `BudgetForecastUiState` as nullable/loading.

Implementation:
```kotlin
data class BudgetForecastUiState(
    val homeCurrency: String? = null,
    val isCurrencyLoaded: Boolean = false,
    ...
)
```

UI:
- show skeleton/degraded state until currency loaded,
- or use `forecast.currency` after forecast generation.

Acceptance:
- no formatting with empty currency.
- forecast screen for USD user renders USD from first non-loading state.
- tests delay currency flow.

---

## S8-014 — Budget forecast generation can race

Severity: High  
Files:
- `BudgetForecastingViewModel.kt`

Evidence:
`generateForecast` launches a coroutine each call. Refresh can trigger another call while old call is still running. No job cancellation/request ID guard.

Problem:
Older forecast results can overwrite newer results.

Fix strategy:
Cancel previous forecast job or use request IDs.

Implementation:

```kotlin
private var forecastJob: Job? = null
private var forecastRequestId = 0L

fun generateForecast(budget: Budget, forecastPeriodDays: Int = 30) {
    val requestId = ++forecastRequestId
    forecastJob?.cancel()
    forecastJob = viewModelScope.launch {
        ...
        if (requestId != forecastRequestId) return@launch
        _uiState.value = ...
    }
}
```

Acceptance:
- slow forecast A, fast forecast B => final state is B.
- cancelled forecast does not emit error.
- test uses fake engine with delays.

---

## S8-015 — Forecast recommendation current spending is computed ambiguously

Severity: High financial correctness  
Files:
- `BudgetForecastingViewModel.kt`
- `BudgetForecastingEngine.kt`

Evidence:
ViewModel computes:
```kotlin
val currentSpending = budget.amount - forecast.predictedRemaining
```

But `forecast.predictedRemaining` is calculated from normalized budget amount, spent-to-date, and predicted spending. This means currentSpending can become:
```text
raw budget.amount - homeCurrency predictedRemaining
```
or conceptually `spentToDate + predictedSpending`, not actual current spending.

Problem:
Recommendation engine may receive the wrong actual spending basis, especially for multi-currency budgets.

Fix strategy:
Forecast should expose `spentToDate` and `normalizedBudgetAmount`.

Implementation:
1. Add fields to `BudgetForecast` or use a UI/domain forecast DTO:
   - `spentToDate`
   - `normalizedBudgetAmount`
   - `currency`
2. Pass `spentToDate` to recommendation engine.
3. Avoid subtracting values of unclear currency/basis.

Acceptance:
- recommendation input equals actual spent-to-date, not forecasted total.
- multi-currency forecast recommendation test passes.

---

## S8-016 — ForecastTimeline documented but not rendered by BudgetForecastingScreen

Severity: Medium docs/source drift  
Files:
- `BudgetForecastingScreen.kt`
- `ForecastTimeline.kt`
- docs UI map/component library

Evidence:
Docs say Budget Forecasting uses `ForecastTimeline`.
Source screen renders Risk, Details, Confidence, Recommendations but no `ForecastTimeline`.

Problem:
Agents and tests may expect a timeline that is not actually present.

Fix strategy:
Either wire the component or update docs.

Recommended:
Wire it if forecast model can produce points.

Implementation:
- Add timeline data to `BudgetForecastUiState`.
- Render `ForecastTimeline(...)` in `ForecastContent`.
- Pass explicit currency.
- If no points, show no-data timeline.

Acceptance:
- BudgetForecastingScreen renders ForecastTimeline.
- component doc matches source.
- test checks timeline exists.

---

## S8-017 — Forecast screen and Budget screen contain many hardcoded English strings

Severity: Medium  
Files:
- `BudgetScreen.kt`
- `BudgetForecastingScreen.kt`
- `CashFlowCalendarScreen.kt`

Examples:
- “AI Budget Autopilot”
- “Analyze”
- “Dismiss All”
- “Apply All”
- “Apply”
- “Period mode”
- “Rolling”
- “Calendar”
- “Forecast range (low / base / high)”
- “Low”
- “Base”
- “High”
- “No cash flow details for this day.”
- “Income items”
- “Expense items”
- “Recurring items”

Problem:
Not localizable and brittle for tests.

Fix strategy:
Move visible strings to resources or typed `UiText`.

Acceptance:
- no hardcoded user-facing strings in Slice 8 UI except debug-only text.
- tests use tags/resources, not raw English.

---

## S8-018 — Locale-fragile number formatting

Severity: Medium  
Files:
- `BudgetScreen.kt`
- `BudgetForecastingScreen.kt`

Evidence:
Uses `String.format("%.0f", ...)`, `String.format("%.1f", ...)`, and raw interpolation for percentages.

Problem:
Default locale can change decimal separators and produce inconsistent UI/tests.

Fix strategy:
Use a `PercentFormatter` utility or `NumberFormat`.

Implementation:
```kotlin
object PercentFormatter {
    fun whole(value: Double, locale: Locale): String
    fun oneDecimal(value: Double, locale: Locale): String
}
```

Acceptance:
- locale tests pass for US and comma-decimal locale.
- no direct `String.format` for user-visible percentages.

---

## S8-019 — CashFlowCalendarViewModel has no error handling

Severity: High  
Files:
- `CashFlowCalendarViewModel.kt`

Evidence:
`loadCashFlow`, `loadUpcomingBills`, and `collectHomeCurrency` launch coroutines without try/catch. If calculator throws, `isLoading` can remain true.

Problem:
Cash-flow screen can get stuck loading or silently fail.

Fix strategy:
Add typed state.

Implementation:

```kotlin
data class CashFlowCalendarState(
    val dailyCashFlows: List<DailyCashFlow> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val dataQualityWarnings: List<UiText> = emptyList(),
    ...
)
```

Use `runCatching` and `finally`.

Acceptance:
- calculator failure sets error and clears loading.
- upcoming bills failure does not break calendar load.
- currency flow failure shows degraded state.

---

## S8-020 — Cash-flow month navigation can race

Severity: High  
Files:
- `CashFlowCalendarViewModel.kt`

Evidence:
Each navigation call launches a new `loadCashFlow` job. No request ID/cancel guard.

Problem:
Rapid previous/next taps can leave the calendar showing a month label from one request and data from another request.

Fix strategy:
Use request ID or cancel previous load.

Implementation:
```kotlin
private var cashFlowJob: Job? = null
private var cashFlowRequestId = 0L
```

Acceptance:
- rapid next/previous cannot show stale data.
- test delays first request and verifies last request wins.

---

## S8-021 — Starting balance input recomputes on every keystroke

Severity: Medium/High UX and performance  
Files:
- `CashFlowCalendarScreen.kt`
- `CashFlowCalendarViewModel.kt`

Evidence:
`OutlinedTextField` value is `state.startingBalance.toString()`.
`onValueChange` parses immediately and calls `viewModel.setStartingBalance(balance)`, which reloads month.

Problem:
Users cannot comfortably type blank/partial/negative values. Every digit triggers full cash-flow calculation.

Fix strategy:
Introduce a draft field and explicit apply/debounce.

Implementation:

```kotlin
data class CashFlowCalendarState(
    val startingBalance: Double = 0.0,
    val startingBalanceInput: String = "0.00",
    val startingBalanceError: UiText? = null
)
```

UI:
- update draft on typing,
- validate,
- apply on IME Done or Apply button,
- optionally debounce.

Acceptance:
- typing `-`, `.`, blank does not corrupt state.
- calculator not called for every invalid partial input.
- apply invalid input shows inline error.

---

## S8-022 — Cash-flow daily details sum raw mixed-currency items

Severity: Critical financial correctness  
Files:
- `CashFlowCalendarScreen.kt`
- `CashFlowCalculator.kt`

Evidence:
`CashFlowCalculator` converts day totals to home currency for endingBalance, but `DailyCashFlow` still carries raw `income`, `expenses`, and `predictedRecurring`.
`DailyCashFlowDetails` sums:
```kotlin
cashFlow.income.sumOf { abs(it.amount) }
cashFlow.expenses.sumOf { it.effectiveAmount }
cashFlow.predictedRecurring.sumOf { it.averageAmount }
```
and formats all as `homeCurrency`.
There is also a comment claiming it is “almost always same-currency”.

Problem:
This is a known unsafe pattern: raw mixed-currency item amounts are displayed as home-currency totals.

Fix strategy:
Move converted daily totals into domain model.

Implementation:
Add to `DailyCashFlow`:
```kotlin
val incomeTotalHome: Double
val expenseTotalHome: Double
val predictedRecurringTotalHome: Double
val conversionWarnings: List<ConversionFailure>
val isPartial: Boolean
```

Or use `MoneyAggregate`.

Acceptance:
- Daily details totals match `endingBalance` math.
- mixed-currency test fails on old raw-sum behavior and passes after fix.
- conversion failures are visible.

---

## S8-023 — CashFlowCalculator drops failed conversions silently

Severity: High  
File:
- `CashFlowCalculator.kt`

Evidence:
Conversion failures increment a local count and log warning. Failed amounts are dropped from cash-flow totals. UI receives no data-quality signal.

Problem:
A day’s risk level and ending balance may be wrong while user sees a normal calendar cell.

Fix strategy:
Add data-quality metadata to `DailyCashFlow`.

Implementation:
```kotlin
data class CashFlowDataQuality(
    val isPartial: Boolean,
    val failedConversionCount: Int,
    val warnings: List<String>
)
```

Acceptance:
- calendar day shows partial-data indicator.
- day details list failed conversion warning.
- tests cover failed FX conversion.

---

## S8-024 — Cash-flow home currency failure falls back to EUR

Severity: High  
Files:
- `CashFlowCalculator.kt`
- `CashFlowCalendarViewModel.kt`

Evidence:
Calculator catches home currency failure and uses `"EUR"`.

Problem:
Cash-flow calculations with an arbitrary fallback can be wrong. Starting balance must be denominated in home currency; if home currency is unknown, projection should be blocked or degraded explicitly.

Fix strategy:
Fail closed or return typed degraded result.

Implementation options:
1. Throw `HomeCurrencyUnavailableException` and show UI error.
2. Return partial state with `currency = null` and no calculation.

Recommended:
Do not calculate if home currency unavailable.

Acceptance:
- no hidden EUR fallback in production cash-flow calculations.
- test simulates currency repo failure and verifies error/degraded state.

---

## S8-025 — Cash-flow view mode is unused

Severity: Medium  
Files:
- `CashFlowCalendarViewModel.kt`
- `CashFlowCalendarScreen.kt`

Evidence:
`CalendarViewMode { MONTH, WEEK, DAY }` exists and state stores `viewMode`, but screen only renders month grid.

Problem:
Dead/unused state confuses agents and tests.

Fix strategy:
Either implement view-mode switching or remove it until needed.

Acceptance:
- if kept, UI exposes and tests Month/Week/Day modes.
- if removed, state and docs no longer mention unsupported modes.

---

## S8-026 — Cash-flow selected date is not cleared on month change/today

Severity: Medium  
Files:
- `CashFlowCalendarViewModel.kt`

Problem:
If selectedDate belongs to old month and user navigates month, bottom sheet can remain open with old date or no matching cash flow.

Fix strategy:
Clear selected date when month changes unless the selected date remains in range.

Implementation:
```kotlin
_state.update {
    it.copy(
        dailyCashFlows = cashFlows,
        currentMonth = startDate,
        selectedDate = it.selectedDate?.takeIf { d -> d.time in startDate.time until endDate.time }
    )
}
```

Acceptance:
- navigating month closes old day sheet.
- Today resets current month and selected date policy is deterministic.

---

## S8-027 — Cash-flow UI uses raw Calendar and Date normalization

Severity: Medium testability/date correctness  
Files:
- `CashFlowCalendarScreen.kt`

Evidence:
Screen uses:
- `Calendar.getInstance()`
- `normalizeDateKey(date)` using system default calendar
- date math inside Compose

Problem:
Date grouping can be fragile around DST/time zones and hard to test.

Fix strategy:
Move calendar grid construction to ViewModel or pure utility using java.time.

Implementation:
```kotlin
data class CalendarMonthGrid(
    val monthLabel: String,
    val cells: List<CalendarDayCell?>
)
```

Utility accepts:
- `YearMonth`
- `ZoneId`
- `firstDayOfWeek`.

Acceptance:
- DST boundary tests pass.
- week start policy is explicit.
- Compose screen no longer builds date grid.

---

## S8-028 — Cash-flow day cells and details lack semantics/test tags

Severity: Medium  
Files:
- `CashFlowCalendarScreen.kt`

Problem:
Calendar cells are difficult to test and screen-reader output is weak.

Fix strategy:
Add semantics:
- day number,
- date label,
- ending balance,
- risk level,
- income/expense/recurring indicators.

Tags:
- `cashflow_day_yyyy_mm_dd`
- `cashflow_day_selected`
- `cashflow_details_sheet`
- `cashflow_starting_balance_input`

Acceptance:
- Compose tests use stable tags.
- TalkBack summary is meaningful.

---

## S8-029 — Forecast and cash-flow write/read operations lack restore/write-barrier consistency

Severity: Medium/High  
Files:
- `BudgetForecastingEngine.kt`
- `CashFlowCalculator.kt`
- `BudgetAutopilotEngine.kt`

Evidence:
`BudgetForecastingEngine` checks `DatabaseWriteBarrier` before saving forecast.
Cash-flow generates recurring occurrences through `RecurringLifecycleCoordinator.generateOccurrences(...)`, which may write planned occurrences.
Autopilot apply-all writes budgets.

Problem:
During backup restore, UI-triggered operations should respect read/write barriers consistently. Forecasting has explicit write barrier; cash-flow and autopilot need verification.

Fix strategy:
Audit all write-capable operations:
- forecast persistence,
- recurring occurrence materialization,
- budget update/apply.

Acceptance:
- restore mode blocks budget apply/update and occurrence generation.
- user sees maintenance/restore-blocked UI.
- tests cover write-barrier denial.

---

## S8-030 — `PeriodGridView` / `PeriodBlock` default to EUR and lack semantics

Severity: Medium  
Files:
- `PeriodGridView.kt`
- `PeriodBlock.kt`

Evidence:
Both components have default `currency = "EUR"`.
`PeriodBlock` displays formatted money but has no content description/test tag.
`PeriodGridView` empty state contains an empty `Text("")`.

Problem:
These shared components can leak EUR if caller forgets currency. They are also hard to test/access.

Fix strategy:
Make currency required or use nullable explicit policy.

Implementation:
```kotlin
fun PeriodGridView(..., currency: String)
fun PeriodBlock(..., currency: String)
```

Add:
```kotlin
Modifier.semantics {
    contentDescription = "$periodLabel, ${formattedAmount}, ${statusLabel}"
}
```

Acceptance:
- no default EUR in shared period components.
- empty state has no empty text node.
- tests cover selected/no-data/current states.

---

## S8-031 — `ForecastTimeline` default EUR and weak invalid-data policy

Severity: Medium  
Files:
- `ForecastTimeline.kt`

Evidence:
`ForecastTimeline` default currency is `"EUR"`.
It accepts arbitrary `Double` points and budgetLimit with no NaN/infinite filtering.

Problem:
Charting with invalid values can crash or produce broken rendering. Default currency can leak.

Fix strategy:
- Require currency.
- Filter/validate chart inputs.
- Show error/empty state for invalid data.

Acceptance:
- no default EUR.
- NaN/Infinity points do not crash.
- no-budget state is explicit.
- tests cover no data, no budget, invalid data, normal data.

---

## S8-032 — `SpendingPaceGauge` has hardcoded English and no reduced-motion option

Severity: Low/Medium  
Files:
- `SpendingPaceGauge.kt`

Evidence:
Status labels are hardcoded:
- “Under pace”
- “On track”
- “Over pace”
- “Calculating...”

It animates by default with `animateFloatAsState`.

Problem:
Not localizable; animations make tests less deterministic.

Fix strategy:
- Move labels to resources.
- Add `animate: Boolean = true`.
- Use Material theme/status tokens.

Acceptance:
- Compose test can render with `animate = false`.
- labels are localized.
- content description uses resources.

---

## S8-033 — `PeriodNavigationBar` accessible levels depend on ordinal ordering

Severity: Medium  
Files:
- `PeriodNavigationBar.kt`

Evidence:
Accessible levels are calculated by `PeriodLevel.entries.filter { it.ordinal <= currentLevel.ordinal }`.

Problem:
If enum order changes, navigation behavior changes. Also clicking current selected chip is no-op but still appears enabled.

Fix strategy:
Define explicit hierarchy.

Implementation:
```kotlin
fun PeriodLevel.parentLevels(): List<PeriodLevel> = when (this) {
    YEAR -> listOf(YEAR)
    MONTH -> listOf(YEAR, MONTH)
    WEEK -> listOf(YEAR, MONTH, WEEK)
    DAY -> listOf(YEAR, MONTH, WEEK, DAY)
}
```

Disable current chip or give selected semantics.

Acceptance:
- tests assert accessible levels for each level.
- enum reorder cannot break behavior.

---

## S8-034 — Budget/Cashflow screens are monolithic

Severity: Medium/High  
Files:
- `BudgetScreen.kt`
- `CashFlowCalendarScreen.kt`
- `BudgetForecastingScreen.kt`

Problem:
These files contain route logic, state collection, UI cards, dialogs, formatting, and date math. Component tests require too much setup.

Fix strategy:
Split route/content/components.

Budget:
```text
BudgetRoute.kt
BudgetScreenContent.kt
BudgetTopBar.kt
BudgetSummaryCard.kt
BudgetCard.kt
BudgetDialog.kt
BudgetAutopilotBanner.kt
BudgetSuggestionsBanner.kt
```

Forecast:
```text
BudgetForecastingRoute.kt
BudgetForecastingContent.kt
ForecastRiskCard.kt
ForecastDetailsCard.kt
ForecastConfidenceCard.kt
ForecastRecommendationCard.kt
```

Cashflow:
```text
CashFlowCalendarRoute.kt
CashFlowCalendarContent.kt
CashFlowMonthHeader.kt
CashFlowStartingBalanceInput.kt
CashFlowMonthGrid.kt
CashFlowDayCell.kt
CashFlowDayDetailsSheet.kt
```

Acceptance:
- route files collect ViewModel state only.
- pure content components are testable with fake state.
- business/date/math logic leaves composables.

---

# 5. Recommended tests to add

## JVM/ViewModel/domain tests

### `BudgetViewModelMutationTest`
Required cases:
- add success emits saved event.
- add failure keeps mutation error.
- update failure keeps dialog context.
- delete failure surfaces error.
- toggle double-tap guarded.
- validation failure blocks repository call.
- currency delayed blocks add.

### `BudgetCurrencyInvariantTest`
Required cases:
- non-EUR home currency creates non-EUR budget.
- adjusted shared spend and budget limit are same display currency.
- conversion failure surfaces partial warning.
- budget card model has no raw unformatted amounts.

### `BudgetAutopilotCurrencyTest`
Required cases:
- mixed-currency monthly history is normalized.
- failed conversion disables or marks recommendation partial.
- no deprecated raw aggregate path remains.
- overall/category scaled recommendations sum correctly.

### `BudgetAutopilotApplyTest`
Required cases:
- generate failure enters Error state.
- apply single success removes one recommendation.
- apply single failure keeps recommendation.
- apply all success updates all in transaction.
- apply all failure rolls back and shows error.
- double apply is guarded.

### `BudgetForecastingViewModelTest`
Required cases:
- generate success maps forecast and recommendations.
- engine failure shows error.
- refresh uses last period.
- slow old forecast cannot overwrite fast new forecast.
- empty currency is never emitted to UI.
- current spending passed to recommendations is spent-to-date.

### `BudgetForecastCurrencyTest`
Required cases:
- budget amount converted to home currency.
- forecast currency equals home currency.
- non-EUR budget does not mix raw budget amount with home-currency forecast.
- write barrier denial maps to UI blocked/error state.

### `CashFlowCalendarViewModelTest`
Required cases:
- initial load uses current month range from fixed TimeProvider.
- calculator failure clears loading and sets error.
- next/previous race last request wins.
- selected date cleared on month navigation.
- starting balance draft does not recalc on invalid partial input.
- home currency failure does not silently use EUR.

### `CashFlowCalculatorCurrencyTest`
Required cases:
- income/expense/recurring converted to home currency.
- mixed-currency day totals equal ending-balance delta.
- failed conversion sets partial data quality.
- transfer incoming/outgoing classification works.
- unclassified transfer excluded.
- actual recurring expense dedupes planned occurrence correctly.

### `CashFlowCalendarGridTest`
Required cases:
- month grid cell count/alignment.
- leap year February.
- DST boundary.
- configured first day of week.
- date key normalization stable.

---

## Compose/component tests

### `BudgetScreenContentTest`
- loading state shows skeleton.
- empty state add button invokes callback.
- error state retry/dismiss visible.
- add dialog save disabled with invalid amount/currency not loaded.
- dialog remains open on save error.
- autopilot error visible.

### `BudgetCardTest`
- renders category/overall budget.
- active switch callback.
- delete callback.
- forecast button callback.
- progress clamps 0..1.
- partial conversion warning visible.
- shared reimbursement note visible.

### `BudgetAutopilotBannerTest`
- idle shows analyze.
- loading shows spinner.
- ready expanded shows recommendations.
- apply/apply-all/dismiss callbacks fire.
- error state shows retry.

### `BudgetForecastingContentTest`
- loading/error/empty/data states.
- risk levels render correct labels.
- forecast timeline appears after wiring.
- non-EUR currency formats correctly.
- recommendation card shows potential savings.

### `CashFlowCalendarContentTest`
- month header previous/next callbacks.
- starting balance input validation.
- upcoming bills alert.
- day cell click opens details.
- selected date sheet dismiss.
- error state retry.

### `CashFlowDayCellTest`
- risk color state.
- income/expense/recurring indicators.
- selected state.
- semantics include date/balance/risk.

### `ForecastTimelineTest`
- no data state.
- no budget state.
- invalid data state.
- valid data semantics summary.
- legend renders actual/projected/budget.

### `PeriodGridAndBlockTest`
- required currency.
- selected border.
- no-data state semantics.
- click callback.
- empty state has no empty text node.

### `SpendingPaceGaugeTest`
- each pace status label.
- content description.
- animation disabled render.
- no baseline state.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile.
2. Run current budget/forecast/cashflow tests.
3. Inventory tests with `find`.
4. Inventory budget entity fields:
   - `currency`
   - threshold defaults
   - period mode type
5. Inventory currency aggregation APIs:
   - `MultiCurrencyRepository`
   - `AnalyticsCurrencyNormalizer`
   - deprecated DAO aggregate methods.

## Phase B — Add invariant tests first

Add:
```text
BudgetCurrencyInvariantTest.kt
BudgetAutopilotCurrencyTest.kt
BudgetForecastCurrencyTest.kt
CashFlowCalculatorCurrencyTest.kt
CashFlowCalendarViewModelTest.kt
```

These tests define financial correctness before UI extraction.

## Phase C — Fix critical currency/data bugs

1. Remove hidden EUR from Budget creation.
2. Make Budget UI currency explicit.
3. Migrate BudgetAutopilot away from raw DAO aggregates.
4. Fix BudgetForecast current-spending basis.
5. Fix BudgetForecastingScreen empty currency.
6. Add cash-flow converted item totals/data quality.
7. Remove cash-flow EUR fallback or make degraded state explicit.

## Phase D — Fix state/race/idempotency

1. Add Budget mutation state and close dialogs only on success.
2. Add Budget operation idempotency guards.
3. Add Forecast request cancellation/request ID.
4. Add CashFlow request cancellation/request ID.
5. Add CashFlow error state.
6. Fix starting balance draft/apply flow.

## Phase E — Wire/document visual components

1. Wire `ForecastTimeline` into BudgetForecastingScreen or update docs.
2. Make `ForecastTimeline`, `PeriodGridView`, and `PeriodBlock` require currency.
3. Add semantics/test tags to chart/calendar components.
4. Add reduced-motion/test mode to `SpendingPaceGauge`.

## Phase F — UI extraction

Extract components listed in S8-034.

## Phase G — Localization/theme/accessibility

1. Replace hardcoded strings.
2. Replace dark-only `SemanticColors` where Slice 2 theme policy requires.
3. Add test tags and semantic summaries.
4. Add light/dark smoke tests.

---

# 7. Cross-slice golden scenarios after local tests pass

Add only after Slice 8 local tests are green:

1. Add budget in non-EUR home currency → Budget screen, Home widget, and Forecast screen agree.
2. Manual add expense updates budget spent and cash-flow ending balance.
3. Shared expense with reimbursement updates budget adjusted spend.
4. Missing FX conversion shows partial warning in Budget and Cash Flow.
5. Budget forecast for non-EUR budget uses normalized home-currency basis.
6. Autopilot recommendation for mixed-currency history is normalized.
7. Cash-flow calendar mixed-currency day details match ending balance delta.
8. Recurring occurrence appears in cash-flow prediction and does not double-count when actual exists.
9. Home dashboard budget widget and Budget tab show same spent/remaining values.
10. Restore mode blocks budget writes/forecast writes/occurrence materialization.

---

# 8. Acceptance checklist for Slice 8 green

Slice 8 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] Budget ViewModel mutation tests pass.
- [ ] Budget currency invariant tests pass.
- [ ] Budget Autopilot currency/apply tests pass.
- [ ] Budget Forecasting tests pass.
- [ ] Cash Flow Calculator currency tests pass.
- [ ] Cash Flow Calendar ViewModel tests pass.
- [ ] Add/Edit Budget dialogs close only after success.
- [ ] Budget creation cannot persist placeholder/default EUR for non-EUR users.
- [ ] Budget UI formats every money value with explicit currency.
- [ ] Shared-expense offset partial conversion state is visible.
- [ ] BudgetAutopilot no longer suppresses deprecated raw aggregate methods.
- [ ] Autopilot failures are visible.
- [ ] Forecast screen never formats with empty currency.
- [ ] Forecast generation cannot race.
- [ ] Forecast recommendation uses actual spent-to-date basis.
- [ ] Cash-flow load errors clear loading and show retry.
- [ ] Cash-flow navigation cannot race.
- [ ] Starting balance input is draft/apply or debounced.
- [ ] Cash-flow day totals are converted, not raw mixed-currency sums.
- [ ] Cash-flow conversion failures are visible.
- [ ] No shared visual component defaults to EUR in production.
- [ ] ForecastTimeline docs/source are reconciled.
- [ ] UI components are split enough for focused tests.
- [ ] Docs are updated only after source/tests are green.

---

# 9. Agent guardrails

Do:
- Protect currency correctness first.
- Use fixed `TimeProvider`.
- Use fake currency rates and fake repositories.
- Add tests before UI extraction.
- Treat budget/cash-flow calculations as financial invariants, not display details.
- Keep forecasting and cash-flow writes behind restore/write barriers.
- Surface partial data quality instead of silently dropping values.

Do not:
- Rewrite the entire budgeting engine in one PR.
- Let composables calculate money totals.
- Persist or display placeholder EUR.
- Swallow Autopilot/Forecast/CashFlow exceptions.
- Close dialogs before mutation success.
- Use raw DAO aggregates for multi-currency recommendations.
- Add new budget/cash-flow features before invariants are tested.

Main invariant:

> For a fixed clock, fixed home currency, fixed FX rates, and fixed transaction fixture, Budget, Forecasting, Cash Flow, and Home budget widgets must agree on spend/remaining/currency basis, show partial data when conversions fail, and never persist or display placeholder money values.