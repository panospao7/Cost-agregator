# Slice 8 Debug Report — Budget + Budget Forecasting

Commit reviewed: `f3cefa7111e7cb75264769cf9dde8c2666ed4976`  
Review type: static GitHub source review, not local Gradle/device execution.

## Scope

- `ui/screens/budget/BudgetScreen.kt`
- `ui/screens/budget/BudgetViewModel.kt`
- `ui/screens/budget/BudgetForecastingScreen.kt`
- `ui/screens/budget/BudgetForecastingViewModel.kt`
- `data/repository/BudgetRepository.kt`
- `domain/budget/BudgetForecastingEngine.kt`
- budget-facing routing interactions from prior slices
- visible budget tests:
  - `BudgetForecastingViewModelTest`
  - `BudgetViewModelStressTest` ([github.com](https://github.com/panospao7/Cost-agregator/tree/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget))

---

# Executive Summary

Slice 8 is **partially fixed but not closed**.

Important improvements already present:
- Add/edit budget dialogs now close only after a `BudgetSaved` event, not immediately on tap.
- New budget creation no longer silently uses default EUR before home currency loads.
- Budget save/update paths have a basic duplicate-submit guard.
- Budget repository now performs currency-aware budget-limit conversion and carries `isPartial` / `conversionWarning`.
- Forecast generation has cancellation/request-ID guards against stale results.
- Forecast ViewModel no longer initializes home currency to an empty string. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))

However, there are still **high-impact correctness issues**:

1. **Budget cards recompute percentage/health in UI and ignore repository partial-currency safeguards.**
2. **`BudgetStatus.isPartial` and `conversionWarning` are produced but not rendered.**
3. **Forecast recommendations derive `currentSpending` from a raw-budget approximation that is explicitly only correct when budget currency equals home currency.**
4. **Forecast UI still passes `""` as currency while home currency is loading.**
5. **Budget UI also still passes blank currency to Autopilot when home currency is not ready.**
6. **Forecasting engine silently falls back to EUR if home-currency lookup fails.**
7. **`BudgetCreate` route exists but still does not actually open budget creation UI.**
8. **Add/Edit dialog still bypasses the shared amount sanitizer and has weak submit/loading UX.**
9. **Error state is duplicated and user-facing messages remain raw/stringly.**

Recommended fix order:
1. Fix budget-card partial/mixed-currency rendering.
2. Fix forecast currency correctness.
3. Make currency loading typed and explicit across budget + forecast.
4. Wire `BudgetCreate` route.
5. Harden budget dialog mutation UX.
6. Add focused tests before further visual work.

---

# Status of Existing Slice 8 Fixes

| Area | Status | Notes |
|---|---:|---|
| Dialog closes only after persistence success | **Resolved** | `BudgetScreen` listens for `BudgetUiEvent.BudgetSaved` before closing add/edit dialogs. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt)) |
| New budgets avoid fake EUR before currency load | **Mostly resolved** | `homeCurrency` is nullable, Save is disabled until loaded, and newly created `Budget` uses resolved home currency. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt)) |
| Save double-tap guard | **Mostly resolved** | `addBudget()` checks `ManualState.Loading`; update path has the same event-based pattern. UI still lacks strong submitting state. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt)) |
| Repository currency normalization | **Improved** | `BudgetRepository` converts budget limits, marks partial results, and avoids mixed-currency percent calculation when limit conversion fails. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt)) |
| Forecast stale-result race | **Resolved** | Forecast ViewModel cancels previous job and discards old request IDs. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt)) |
| Forecast home currency no blank default in state | **Partial** | State is nullable, but the screen immediately converts null to `""`. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt)) |

---

# High-Priority Findings

## S8-001 — Budget card recomputes unsafe percent/health and can defeat repository currency safeguards

**Severity:** Critical / High  
**Files:**
- `BudgetRepository.kt`
- `BudgetScreen.kt`

## Problem

`BudgetRepository` already does the right defensive work:
- it converts budget limit and spend into comparable currencies where possible;
- it sets `budgetIsPartial`;
- if budget-limit conversion fails, it explicitly avoids calculating percent because that would mix currencies;
- it returns `isPartial` and `conversionWarning` on `BudgetStatus`. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt))

But `BudgetCard` then recalculates:

```kotlin
val displayPercentUsed =
    if (status.effectiveLimit > 0.0) displaySpend / status.effectiveLimit else 0f
```

and recomputes `displayHealthStatus` from that UI-side percentage. This means the UI can reintroduce the exact mixed-currency percentage computation the repository intentionally prevented. The UI also recomputes remaining amount from `status.effectiveLimit - displaySpend`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))

## Impact

If a budget limit cannot be converted but spend is in home currency:
- repository marks the state unreliable,
- UI can still show a seemingly precise percentage,
- health can show warning/exceeded based on incompatible currencies,
- users may make wrong decisions from invalid progress bars.

## Fix Strategy

Do **not** recompute canonical budget health in the UI.

Use repository/domain-provided fields:

```kotlin
val displayPercentUsed = status.percentUsed
val displayHealthStatus = status.healthStatus
val displayRemainingAmount = status.remainingAmount
```

If `adjustedSpendBreakdown` must override spend:
- move adjusted-spend recomputation into domain/repository;
- return a new fully normalized `BudgetDisplayStatus`;
- never recompute health from potentially incompatible values in Compose.

## Acceptance Tests

- failed limit conversion leaves progress/health in explicit degraded state;
- partial budget does not show a misleading percentage;
- adjusted spend uses same currency basis as effective limit;
- UI never computes percent from mixed currencies.

---

## S8-002 — Budget partial/conversion warnings are generated but invisible in UI

**Severity:** High  
**Files:**
- `BudgetRepository.kt`
- `BudgetModels.kt`
- `BudgetScreen.kt`

## Problem

`BudgetRepository` returns:

```kotlin
isPartial = budgetIsPartial
conversionWarning = ...
```

on `BudgetStatus`, but `BudgetScreen` / `BudgetCard` do not visibly render either field. The repository comments explicitly state that these flags tell the UI the status is unreliable when conversion failed. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt))

## Impact

The app may show:
- “On track”
- a green progress bar
- remaining amount

while the underlying data is marked partial/unreliable.

## Fix Strategy

Add a shared warning component:

```kotlin
if (status.isPartial) {
    DataQualityWarningChip(
        message = status.conversionWarning
            ?: stringResource(R.string.budget_partial_currency_warning)
    )
}
```

Also:
- suppress or visually degrade progress bar if the percent is unreliable;
- include warning in accessibility description;
- propagate warning into summary counts if many budgets are partial.

## Acceptance Tests

- partial status renders warning;
- warning text includes conversion reason;
- accessibility exposes partial-data state;
- warning survives adjusted-spend rendering.

---

## S8-003 — Forecast recommendations use raw-budget approximation for current spending

**Severity:** High  
**File:** `BudgetForecastingViewModel.kt`

## Problem

The ViewModel computes:

```kotlin
currentSpending =
    budget.amount - forecast.predictedRemaining - forecast.predictedSpending
```

and the comment admits this is only correct when `budget.currency == homeCurrency`. The forecasting engine itself normalizes budget amounts before forecasting, but the ViewModel does not receive/use the normalized amount and falls back to the raw `budget.amount` approximation. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt))

## Impact

For non-home-currency budgets:
- recommendations can be generated from the wrong current-spending value;
- risk/recommendation text may be internally inconsistent with the forecast engine;
- the same budget can look correct in forecast core calculations but wrong in recommendation derivation.

## Fix Strategy

The forecasting engine should return explicit fields:

```kotlin
data class BudgetForecast(
    val normalizedBudgetAmount: Double,
    val spentToDate: Double,
    val displayCurrency: String,
    val isPartial: Boolean,
    val conversionWarning: String?,
    ...
)
```

Then the ViewModel passes:

```kotlin
currentSpending = forecast.spentToDate
```

to `BudgetRecommendationEngine`.

## Acceptance Tests

- non-home-currency budget recommendations use normalized spent-to-date;
- forecast and recommendation calculations agree;
- conversion failure marks forecast partial instead of silently using raw amount.

---

## S8-004 — Forecast screen still renders blank currency while settings load

**Severity:** High  
**Files:**
- `BudgetForecastingViewModel.kt`
- `BudgetForecastingScreen.kt`

## Problem

The ViewModel correctly keeps:

```kotlin
homeCurrency: String? = null
```

until loaded. But the screen immediately does:

```kotlin
val homeCurrency = uiState.homeCurrency ?: ""
```

and passes that blank string into `CurrencyFormatter.formatMoney(...)` throughout forecast content. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt))

## Impact

During initial load or currency repository failure:
- forecast amounts can render without currency;
- users can briefly see malformed money text;
- there is no explicit “currency loading/unavailable” UI.

## Fix Strategy

Use typed currency state:

```kotlin
sealed interface BudgetCurrencyUiState {
    data object Loading
    data class Ready(val code: String)
    data class Error(val message: UiText)
}
```

In screen:
- do not render money cards until `Ready`;
- show skeleton/degraded state while loading;
- show explicit error if currency fails.

## Acceptance Tests

- forecast screen never passes `""` to money formatter;
- loading currency shows a loading/degraded card;
- ready currency renders all money values consistently.

---

## S8-005 — Budget screen still passes blank currency into Autopilot

**Severity:** Medium / High  
**Files:**
- `BudgetScreen.kt`
- `BudgetViewModel.kt`

## Problem

The ViewModel no longer defaults home currency to EUR, which is good. But the screen still calls:

```kotlin
homeCurrency = uiState.homeCurrency ?: ""
```

for `AutopilotBanner`, and `AutopilotBanner` itself still has a default currency of `"EUR"`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))

## Impact

- blank-currency formatting remains possible;
- a future caller can omit currency and silently get EUR;
- budget/autopilot widgets are inconsistent with the stricter Add/Edit dialog.

## Fix Strategy

- Remove default `"EUR"` from `AutopilotBanner` and `AutopilotRecommendationItem`.
- Require explicit currency.
- Hide/skeleton Autopilot money actions until currency is ready.

## Acceptance Tests

- no budget/autopilot component has fake default EUR;
- null home currency does not render blank money strings.

---

## S8-006 — Forecasting engine silently falls back to EUR

**Severity:** High  
**File:** `BudgetForecastingEngine.kt`

## Problem

The engine resolves home currency with:

```kotlin
currencySettingsRepository.homeCurrency().first()
    .getOrDefault("EUR")
```

If settings lookup fails, calculations silently use EUR. The UI work elsewhere intentionally removed silent fake EUR defaults, but the domain engine still reintroduces one. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt))

## Impact

A transient settings failure can produce:
- EUR-based forecast math for a non-EUR user;
- no visible warning;
- forecast results that look authoritative but are based on a fallback currency.

## Fix Strategy

Do not default to EUR in financial calculation paths.

Use typed result:

```kotlin
sealed interface CurrencyResolution {
    data class Ready(val code: String)
    data class Error(val reason: String)
}
```

If currency cannot be resolved:
- return forecast error/degraded result;
- do not generate a “valid” forecast.

## Acceptance Tests

- home-currency repository failure does not generate EUR forecast;
- UI receives visible forecast error/degraded state;
- successful currency load produces same forecast as before.

---

## S8-007 — `BudgetCreate` route exists but does not open creation UI

**Severity:** High  
**Files:**
- `NavigationDestination.kt`
- `MainActivity.kt`
- `BudgetScreen.kt`

## Problem

`BudgetCreate` was added in Slice 2 work, but `BudgetScreen` still has no `initialOpenCreateDialog` parameter and there is no route-specific handling that opens the create dialog. The route therefore appears to select the Budget tab without performing the intended create action. This is an inference from the current `BudgetScreen` API and route behavior. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))

## Fix Strategy

Add one-shot route input:

```kotlin
fun BudgetScreen(
    initialOpenCreateDialog: Boolean = false,
    ...
)
```

Consume once:

```kotlin
LaunchedEffect(initialOpenCreateDialog) {
    if (initialOpenCreateDialog) {
        preselectedCategoryIdForAdd = null
        showAddDialog = true
    }
}
```

Then pass it from `MainActivity` when destination is `BudgetCreate`.

## Acceptance Tests

- `BudgetCreate` opens creation dialog once;
- `Budget` route does not;
- dialog does not reopen after dismiss/recomposition;
- route restore behavior is explicit.

---

## S8-008 — Add/Edit budget dialog bypasses shared amount sanitizer

**Severity:** Medium  
**File:** `BudgetScreen.kt`

## Problem

`AddEditBudgetDialog` uses a local regex:

```kotlin
^\d*\.?\d{0,2}$
```

instead of the shared `AmountInputSanitizer` now used elsewhere. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))

## Impact

Budget amount input can diverge from:
- Add Expense
- receipt scan
- future shared amount-input behavior

This recreates cross-screen inconsistency Slice 2 was trying to remove.

## Fix Strategy

Use:

```kotlin
onValueChange = { raw ->
    amount = AmountInputSanitizer.sanitize(raw)
}
```

Also:
- disable confirm when amount invalid;
- use shared `FormAmountField` if possible.

## Acceptance Tests

- budget amount sanitization matches Add Expense;
- invalid amount cannot be submitted;
- decimal behavior is consistent across app.

---

## S8-009 — Add/Edit budget dialog lacks full submitting state

**Severity:** Medium  
**Files:**
- `BudgetScreen.kt`
- `BudgetViewModel.kt`

## Problem

The ViewModel has a loading guard, and dialogs close after `BudgetSaved`, which is good. But the dialog itself:
- keeps Save visually enabled except for missing currency;
- has no spinner;
- has no inline persistence error;
- still allows repeated clicks, relying on ViewModel guard. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))

## Fix Strategy

Expose mutation state:

```kotlin
val isSavingBudget: Boolean
val budgetMutationError: UiText?
```

Then:
- disable confirm during save;
- use shared `FormDialog(isSubmitting = ...)`;
- keep dialog open with inline error on failure.

## Acceptance Tests

- double tap calls repository once;
- failure keeps dialog open;
- save button disabled/spinner during submit;
- success closes dialog exactly once.

---

## S8-010 — Budget error UI is duplicated and raw-string based

**Severity:** Medium  
**Files:**
- `BudgetScreen.kt`
- `BudgetViewModel.kt`

## Problem

When `uiState.error != null`, the screen renders an `ErrorBanner`. But `loadableState` also becomes `Error`, so the screen can render both a banner and a full-screen error in the same composition path. The ViewModel stores raw `String?` errors and wraps them in `UiText.DynamicString`; the screen also falls back to hardcoded `"Error loading budgets"`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))

## Fix Strategy

Separate:
- screen-load error
- mutation error
- autopilot error

Use typed `UiText`.

Example:

```kotlin
data class BudgetUiState(
    val loadState: LoadableUiState<List<BudgetStatus>>,
    val mutationState: BudgetMutationState,
    val autopilotState: AutopilotUiState
)
```

## Acceptance Tests

- load failure shows one load error surface;
- mutation failure keeps existing data and shows inline/banner error;
- no raw exception string appears to user.

---

## S8-011 — Partial data quality does not affect summary counts

**Severity:** Medium  
**Files:**
- `BudgetScreen.kt`
- `BudgetRepository.kt`

## Problem

`BudgetSummaryCard` counts:
- on-track
- warning/critical
- exceeded

but there is no bucket for partial/unreliable budgets. If repository forced a budget to `ON_TRACK` because conversion failed, the summary can count it as healthy even though it is explicitly partial. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))

## Fix Strategy

Add:
- partial count,
- “data incomplete” summary chip,
- exclude partial budgets from healthy aggregate if product policy prefers.

## Acceptance Tests

- partial budget is not presented as confidently on-track;
- summary shows partial count.

---

## S8-012 — Forecast route still depends on rich `Budget` object

**Severity:** Medium / architectural  
**Files:**
- navigation layer
- `BudgetForecastingScreen.kt`

## Problem

`BudgetForecastingScreen` requires a full `Budget` object. Earlier navigation review found route objects carrying entities instead of primitive IDs, which is fragile for process death/restore. The current forecasting screen API still reflects that model. This is an inference supported by the screen signature and prior navigation architecture work. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingScreen.kt))

## Fix Strategy

Route by stable ID:

```kotlin
data class BudgetForecasting(val budgetId: Long)
```

ViewModel loads budget by ID.

If budget is missing/deleted:
- show explicit empty/error state;
- provide back action;
- do not silently degrade to null entity behavior.

## Acceptance Tests

- process restore by budget ID works;
- deleted budget shows error state;
- no entity object required in route.

---

## S8-013 — Forecast display can silently mix raw/display budget values

**Severity:** Medium / High  
**Files:**
- `BudgetForecastingScreen.kt`
- `BudgetForecastingEngine.kt`

## Problem

The engine normalizes the budget amount for forecasting. The screen formats `budget.amount` as `homeCurrency`. If the incoming `Budget` is not already a display-normalized copy, that can mislabel raw source-currency amount as home currency. The current route from `BudgetCard` likely passes a repository-prepared display budget copy, but the screen contract itself does not guarantee that. This is an inference from the current display contract and engine normalization logic. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt))

## Fix Strategy

Screen should receive explicit display model:

```kotlin
data class BudgetForecastUiModel(
    val displayBudgetAmount: MoneyDisplayUi,
    ...
)
```

or the forecast should expose normalized budget amount and display currency.

Do not format arbitrary `budget.amount` with `homeCurrency`.

---

## S8-014 — Forecasting screen still uses raw strings / enum names

**Severity:** Medium  
**File:** `BudgetForecastingScreen.kt`

## Problem

Some strings are localized, but there are still direct enum-name displays such as:

```kotlin
recommendation.priority.name
```

and several text paths are generated from raw domain descriptions. The screen also uses its own local `ErrorState` instead of the shared primitive. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingScreen.kt))

## Fix Strategy

- map recommendation priority to string resources;
- use `UiText` for recommendation descriptions where possible;
- use shared `ErrorState` primitive unless there is a strong feature-specific need.

---

## S8-015 — Test coverage is too thin for the risk surface

**Severity:** High / test gap  
**Files:** visible test tree

## Problem

The visible budget test folder contains only:
- `BudgetForecastingViewModelTest`
- `BudgetViewModelStressTest`

There is no visible focused test suite for:
- partial-currency budget UI
- `BudgetSaved` dialog close behavior
- `BudgetCreate`
- budget currency loading
- forecast non-home-currency recommendations
- partial warning rendering
- mixed-currency progress safeguards. ([github.com](https://github.com/panospao7/Cost-agregator/tree/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/test/java/com/yourname/expensetracker/ui/screens/budget))

## Fix Strategy

Add focused tests before more refactors.

---

# Implementation Plan for Agent

## Phase 1 — Budget display correctness

### Files
- `BudgetScreen.kt`
- `BudgetModels.kt`
- possibly `BudgetViewModel.kt`

### Steps
1. Stop recomputing canonical percent/health in `BudgetCard`.
2. Use domain-provided `percentUsed`, `healthStatus`, `remainingAmount`.
3. Render `isPartial` + `conversionWarning`.
4. Add partial count to summary.
5. Move adjusted-spend recomputation into domain if it must alter percent/health.

### Acceptance
- no mixed-currency percent recomputation in UI;
- partial data visibly marked.

---

## Phase 2 — Forecast currency correctness

### Files
- `BudgetForecastingEngine.kt`
- `BudgetForecastingViewModel.kt`
- `BudgetForecastingScreen.kt`

### Steps
1. Expose `normalizedBudgetAmount`, `spentToDate`, display currency, partial flags from forecast engine.
2. Remove raw-budget approximation in ViewModel.
3. Remove `getOrDefault("EUR")` fallback from engine.
4. Use typed currency state in screen.
5. Never pass `""` into `CurrencyFormatter`.

### Acceptance
- non-home-currency forecast/recommendations are correct;
- currency failure is visible, not silently EUR.

---

## Phase 3 — Budget creation/navigation UX

### Files
- `NavigationDestination.kt`
- `MainActivity.kt`
- `BudgetScreen.kt`

### Steps
1. Wire `BudgetCreate` to one-shot open-create behavior.
2. Define restore policy.
3. Add route contract test.

---

## Phase 4 — Dialog/mutation hardening

### Files
- `BudgetScreen.kt`
- `BudgetViewModel.kt`

### Steps
1. Use shared amount sanitizer.
2. Add submitting state and inline error.
3. Disable invalid Save.
4. Use `UiText` instead of raw strings.

---

## Phase 5 — Screen/state cleanup

### Files
- `BudgetScreen.kt`
- `BudgetForecastingScreen.kt`

### Steps
1. Split route from stateless screen.
2. Use shared primitives for loading/error states.
3. Localize remaining hardcoded/enum text.
4. Replace direct ViewModel dependency in composables with callbacks/state.

---

# Recommended Tests

## `BudgetCardCurrencyQualityTest`
- partial budget shows warning;
- conversion failure does not show misleading percent;
- partial budget not counted as confidently on-track.

## `BudgetDialogMutationTest`
- save closes only after `BudgetSaved`;
- failure keeps dialog open;
- double tap saves once;
- missing currency disables Save;
- amount sanitizer matches shared behavior.

## `BudgetCreateNavigationTest`
- route opens dialog once;
- normal budget route does not;
- dismiss does not reopen.

## `BudgetForecastCurrencyTest`
- non-home-currency budget uses normalized budget amount;
- `spentToDate` comes from engine output, not raw approximation;
- missing currency settings does not fallback to EUR;
- conversion failure exposes partial warning.

## `BudgetForecastRaceTest`
- newer forecast request wins;
- canceled older request cannot overwrite state.

## `BudgetErrorStateTest`
- load error shows one surface;
- mutation error does not blank existing list;
- raw exception text is not user-visible.

---

# Final Severity Table

| ID | Severity | Summary |
|---|---:|---|
| S8-001 | Critical/High | Budget UI recomputes mixed-currency percent/health |
| S8-002 | High | Partial/conversion warnings are generated but invisible |
| S8-003 | High | Forecast recommendation `currentSpending` uses raw approximation |
| S8-004 | High | Forecast UI passes blank currency while loading |
| S8-005 | Med/High | Budget Autopilot still receives blank/default fake currency |
| S8-006 | High | Forecasting engine silently falls back to EUR |
| S8-007 | High | `BudgetCreate` route does not open create UI |
| S8-008 | Medium | Budget dialog bypasses shared sanitizer |
| S8-009 | Medium | Budget dialog lacks full submitting/error state |
| S8-010 | Medium | Budget error UI duplicated/raw-string based |
| S8-011 | Medium | Partial budgets counted as healthy summary rows |
| S8-012 | Medium | Forecast route carries rich entity object |
| S8-013 | Med/High | Forecast screen display contract can mislabel raw budget amount |
| S8-014 | Medium | Forecast screen still uses enum/raw text paths |
| S8-015 | High | Missing focused tests for risky budget/forecast paths |

---

# Immediate Agent Task List

## Task A — Fix budget-card money correctness
Use repository-provided percent/health and surface partial warnings.

## Task B — Fix forecast money model
Expose normalized forecast values and remove raw-budget approximation.

## Task C — Remove silent EUR fallback
No financial calculation should silently default to EUR.

## Task D — Wire `BudgetCreate`
Make the route actually open the creation dialog.

## Task E — Harden dialogs
Shared sanitizer, submit state, inline error, no double-tap ambiguity.

## Task F — Add tests
Start with mixed-currency/partial-budget tests and forecast currency tests.

---

# Sources Reviewed

- Budget UI source and dialog behavior. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt))
- Budget ViewModel state/events/currency handling. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetViewModel.kt))
- Budget forecasting UI and ViewModel. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingScreen.kt))
- Budget repository currency normalization and partial-status logic. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt))
- Forecasting engine normalization behavior. ([github.com](https://github.com/panospao7/Cost-agregator/blob/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt))
- Visible budget test tree. ([github.com](https://github.com/panospao7/Cost-agregator/tree/f3cefa7111e7cb75264769cf9dde8c2666ed4976/app/src/test/java/com/yourname/expensetracker/ui/screens/budget))