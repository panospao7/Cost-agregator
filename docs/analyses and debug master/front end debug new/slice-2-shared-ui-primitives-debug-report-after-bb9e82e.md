# Slice 2 Re-Debug Report — Theme + Shared UI Primitives

Commit reviewed: `bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0`  
Commit title: `fix(ui): Slice 2 review - S2-002/007R/008R/009R/011R/018R leading decimal, typed OpenFeature, BudgetCreate, FormDialog submitting, skeleton semantics`  
Review type: static GitHub source review, not local Gradle/device execution.

Primary commit:  
https://github.com/panospao7/Cost-agregator/commit/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0

---

# Executive Summary

Slice 2 is **much improved**, but **not fully closed**.

Confirmed improvements:
- `AmountInputSanitizer` now handles leading decimal input like `.5` by normalizing to `0.5`.
- `OpenFeature(String)` was replaced with typed `EmptyStateFeatureAction`.
- Default empty-state registry now uses typed feature actions.
- Obvious preview raw feature strings were replaced by direct navigation destinations.
- Budget empty-state action now targets `NavigationDestination.BudgetCreate` instead of `Budget`.
- `BudgetCreate` was added to navigation token serialization/tab-index logic.
- `FormDialog` now has `isSubmitting`.
- `FormDialog` disables confirm/dismiss buttons during submit.
- `DashboardCardSkeleton` and `ChartSkeleton` now announce loading once at parent level.
- Several skeleton accent colors were moved from fixed `SemanticColors.PrimaryIndigo` to `MaterialTheme.colorScheme.primary`.

Still high-risk / unresolved:
1. **`BudgetCreate` route does not actually open the create-budget dialog.**
2. **Typed `OpenFeature` is not truly exhaustive because local handlers use `else -> {}`.**
3. **Several typed feature actions still intentionally no-op.**
4. **`FormDialog(isSubmitting)` still allows outside/back dismiss because `onDismissRequest` is not guarded.**
5. **`FormDialog` loading indicator uses hardcoded `Color.White`.**
6. **`ReceiptScanSkeleton` and `AIProcessingSkeleton` still use `SemanticColors` and lack parent loading semantics.**
7. **`AmountInputSanitizer` still has weak multiple-decimal behavior.**
8. **No strong Slice 2 contract tests were added in this commit.**
9. **Metric components and raw dimension cleanup remain unresolved.**

Recommended next order:
1. Wire `BudgetCreate` to actually open budget creation UI.
2. Centralize/exhaustively handle `EmptyStateFeatureAction`.
3. Guard `FormDialog.onDismissRequest` while submitting.
4. Finish skeleton semantics/theme cleanup.
5. Add contract tests.

---

# Updated Status Table

| ID | Status after `bb9e82e` | Notes |
|---|---|---|
| S2-001 | Resolved | `FormAmountField` already uses shared sanitizer. |
| S2-002 | Mostly fixed | Leading decimal fixed; multiple decimal edge cases remain. |
| S2-003 | Resolved | Action chips disabled when click callback missing. |
| S2-004 | Resolved | Dismiss icon hidden when callback missing. |
| S2-005 | Resolved | `descriptionRes` exposed in semantics. |
| S2-006 | Mostly fixed | Obvious no-op default actions replaced earlier. |
| S2-007R | Partial | `OpenFeature` is typed, but handlers are not exhaustive/centralized. |
| S2-008R | Partial/unresolved | `BudgetCreate` added, but does not open dialog. |
| S2-009R | Partial | Dashboard/chart skeletons fixed; receipt/AI skeletons still incomplete. |
| S2-010R | Partial | Metric components still use raw/default colors. |
| S2-011R | Partial | Some skeleton colors fixed; others still use `SemanticColors`. |
| S2-012R | Unresolved | Raw `dp` values remain common. |
| S2-013 | Mostly fixed | Explicit color APIs exist. |
| S2-014 | Resolved | Strict hex validation exists. |
| S2-015 | Resolved | Alpha/clamping supported. |
| S2-016 | Resolved | Scale clamp exists. |
| S2-017 | Resolved | Disabled dropdown expansion guarded. |
| S2-018R | Partial | `isSubmitting` added, but outside dismiss still possible. |
| S2-019R | Unresolved | Executable-action registry contract missing. |
| S2-020R | Partial/test gap | Focused primitive tests still missing or unverified. |

---

# Confirmed Fixes

## S2-BB9-FIX-001 — Leading decimal input fixed

**Status:** Mostly resolved  
**File:** `AmountInputSanitizer.kt`

The sanitizer now prepends `0` when cleaned input starts with a decimal point.

Expected:
- `.5` → `0.5`

## Remaining issue

Multiple decimals still behave poorly:

- `12..34` likely becomes `12.`
- `..5` likely becomes `0.`

This is deterministic, but not user-friendly.

## Recommended fix

Instead of `split('.')`, keep first decimal and merge later digits:

```kotlin
val firstDot = normalized.indexOf('.')
val integerRaw = normalized.take(firstDot)
val fractionRaw = normalized.drop(firstDot + 1).filter { it.isDigit() }
```

Acceptance:
- `12..34` → `12.34`
- `..5` → `0.5`
- `1.2.3` → `1.23`

---

## S2-BB9-FIX-002 — `OpenFeature(String)` replaced with typed enum

**Status:** Partial  
**Files:**
- `EmptyStateAction.kt`
- `DefaultEmptyStateRegistryInitializer.kt`

Good:
```kotlin
EmptyStateActionType.OpenFeature(EmptyStateFeatureAction.AddWarranty)
```

replaces raw strings.

## Remaining issue

This is not true compile-time enforcement yet because individual screen handlers use:

```kotlin
else -> {}
```

That means a newly added enum value or default action can still silently no-op.

---

## S2-BB9-FIX-003 — Budget empty-state route changed to `BudgetCreate`

**Status:** Partial / not functionally fixed  
**Files:**
- `NavigationDestination.kt`
- `NavigationController.kt`
- `DefaultEmptyStateRegistryInitializer.kt`
- `MainActivity.kt`
- `BudgetScreen.kt`

Good:
- `NavigationDestination.BudgetCreate` exists.
- Save token `budget_create` exists.
- Tab index maps to Budget tab.
- Default budget empty-state action targets `BudgetCreate`.

Problem:
`MainActivity` still renders tab 3 as:

```kotlin
BudgetScreen(
    initialCategoryId = ...,
    initialCategoryName = ...,
    onNavigateToForecast = ...
)
```

It does not pass any `openCreateDialog` flag.  
`BudgetScreen` does not have an `initialOpenCreateDialog` parameter.

So `BudgetCreate` currently just lands on Budget tab and does not open creation UI.

---

## S2-BB9-FIX-004 — `FormDialog(isSubmitting)` added

**Status:** Partial  
**File:** `FormComponents.kt`

Good:
- Confirm button disabled while submitting.
- Dismiss button disabled while submitting.
- Spinner shown in confirm button.

Remaining:
- `AlertDialog(onDismissRequest = onDismiss)` still allows outside/back dismiss during submit.
- Spinner color is hardcoded `Color.White`.

Fix:

```kotlin
onDismissRequest = {
    if (!isSubmitting) onDismiss()
}
```

Spinner:

```kotlin
color = MaterialTheme.colorScheme.onPrimary
```

---

## S2-BB9-FIX-005 — Skeleton parent semantics improved

**Status:** Partial  
**File:** `LoadingSkeleton.kt`

Good:
- `DashboardCardSkeleton` announces loading once.
- `ChartSkeleton` announces loading once.
- Several accent skeleton colors now use theme primary.

Remaining:
- `ReceiptScanSkeleton` has no parent loading semantics.
- `AIProcessingSkeleton` has no parent loading semantics.
- Both still use `SemanticColors`.
- `TransactionItemSkeleton` is silent if used standalone, though `ListSkeleton` covers list usage.

---

# Remaining / New Issues

---

## S2-BB9-001 — `BudgetCreate` route does not open create dialog

**Severity:** High  
**Files:**
- `MainActivity.kt`
- `BudgetScreen.kt`
- `NavigationDestination.kt`

## Problem

The route exists but is not consumed by `BudgetScreen`.

`BudgetCreate` is included as a main-tab destination, so the selected tab becomes Budget, but no dialog state is triggered.

## Fix strategy

Add parameter:

```kotlin
@Composable
fun BudgetScreen(
    initialCategoryId: Long? = null,
    initialCategoryName: String? = null,
    initialOpenCreateDialog: Boolean = false,
    ...
)
```

In `BudgetScreen`:

```kotlin
LaunchedEffect(initialOpenCreateDialog) {
    if (initialOpenCreateDialog) {
        preselectedCategoryIdForAdd = null
        showAddDialog = true
    }
}
```

In `MainActivity`:

```kotlin
3 -> BudgetScreen(
    initialOpenCreateDialog = currentDestination is NavigationDestination.BudgetCreate,
    ...
)
```

## Acceptance tests

- navigating to `BudgetCreate` opens create dialog.
- `Budget` does not open dialog.
- dismissing dialog does not reopen on recomposition.
- process restore policy is explicit.

---

## S2-BB9-002 — Typed `OpenFeature` still silently no-ops

**Severity:** High  
**Files:**
- `CarbonFootprintScreen.kt`
- `SpendingChallengesScreen.kt`
- `LifestyleInflationScreen.kt`
- `SubscriptionManagementScreen.kt`
- `WarrantyTrackerScreen.kt`
- `EmptyStateAction.kt`

## Problem

Handlers now switch on enum values, but most use:

```kotlin
else -> {}
```

Examples:
- `WarrantyTrackerScreen`: `AddWarranty -> { /* handled by primary button */ }`
- `LifestyleInflationScreen`: `IncomeSettings -> { /* navigate to income settings */ }`
- `SpendingChallengesScreen`: `NoSpendStreak -> { /* handled by home dashboard */ }`

These are typed, but still no-op.

## Fix strategy

Create one central executor:

```kotlin
class EmptyStateActionExecutor {
    fun execute(action: EmptyStateActionType)
}
```

Make enum handling exhaustive with no `else`.

If a feature action is intentionally unavailable in a screen, it should not be registered for that screen.

## Acceptance tests

- every `EmptyStateFeatureAction` has an executor branch.
- no `else -> {}` in feature action handlers.
- every default `OpenFeature` performs a visible action.

---

## S2-BB9-003 — `FormDialog` can dismiss during submit

**Severity:** Medium/High  
**File:** `FormComponents.kt`

## Problem

Buttons are disabled during submit, but outside tap/back can still call `onDismiss`.

## Fix

```kotlin
AlertDialog(
    onDismissRequest = { if (!isSubmitting) onDismiss() },
    ...
)
```

## Acceptance tests

- while submitting, outside dismiss does nothing.
- while submitting, back dismiss does nothing.
- after submit false, dismiss works.

---

## S2-BB9-004 — `FormDialog` spinner color is hardcoded

**Severity:** Medium  
**File:** `FormComponents.kt`

## Problem

```kotlin
color = Color.White
```

This may fail contrast if button color changes.

## Fix

Use theme:

```kotlin
color = MaterialTheme.colorScheme.onPrimary
```

Or `ButtonDefaults.buttonColors()`-aware content color.

---

## S2-BB9-005 — Receipt/AI skeletons remain theme/accessibility incomplete

**Severity:** Medium  
**File:** `LoadingSkeleton.kt`

## Problem

`ReceiptScanSkeleton` still uses:

```kotlin
SemanticColors.SurfaceLight
```

`AIProcessingSkeleton` still uses:

```kotlin
SemanticColors.PrimaryIndigo
```

Neither parent announces loading once.

## Fix

Add:

```kotlin
val loadingDescription = stringResource(R.string.a11y_loading_content)
Column(
    modifier = modifier
        .fillMaxWidth()
        .semantics { contentDescription = loadingDescription }
)
```

Use:

```kotlin
MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
```

---

## S2-BB9-006 — `AmountInputSanitizer` multiple-decimal behavior needs tests/fix

**Severity:** Medium  
**File:** `AmountInputSanitizer.kt`

## Problem

Using `split('.')` and only `parts[1]` discards later digits.

## Fix

Merge all fractional digits after the first decimal.

## Acceptance tests

- `12..34` → `12.34`
- `..5` → `0.5`
- `1.2.3` → `1.23`

---

## S2-BB9-007 — `ExecuteAction` remains arbitrary and untestable

**Severity:** Medium  
**File:** `EmptyStateAction.kt`

## Problem

`ExecuteAction(val action: () -> Unit)` still allows future default registry no-op lambdas.

## Fix strategy

For default registry actions:
- avoid `ExecuteAction`.
- use typed action types only.

If `ExecuteAction` must remain for local caller-provided actions, add a contract test ensuring the default registry never uses it.

---

## S2-BB9-008 — Metric component cleanup still unresolved

**Severity:** Medium  
**File:** `MetricComponents.kt`

Remaining:
- default `SemanticColors.PrimaryIndigo`
- raw status colors
- raw dimensions
- composables accept raw `String` text, which is fine, but no localization contract exists for shared feature usage.

Fix:
- use nullable color defaults resolved inside composable with `MaterialTheme.colorScheme.primary`.
- move repeated dimensions to `Dimens`.

---

## S2-BB9-009 — Raw dimensions remain common

**Severity:** Low/Medium  
**Files:**
- `FormComponents.kt`
- `MetricComponents.kt`
- `LoadingSkeleton.kt`

Examples:
- `12.dp`
- `16.dp`
- `18.dp`
- `20.dp`
- `24.dp`
- `40.dp`
- `80.dp`
- `120.dp`
- `150.dp`
- `200.dp`

Not all raw dimensions are wrong, but shared primitives should standardize common spacing/sizing tokens.

---

## S2-BB9-010 — Contract tests are still missing

**Severity:** High / test gap**

This commit did not visibly add tests.

Critical tests:
- `AmountInputSanitizerTest`
- `FormDialogSubmittingStateTest`
- `EmptyStateRegistryCompletenessTest`
- `EmptyStateFeatureActionExecutorTest`
- `BudgetCreateNavigationTest`
- `LoadingSkeletonSemanticsTest`

---

# Updated Implementation Plan

## Phase 1 — Make `BudgetCreate` real

Files:
- `BudgetScreen.kt`
- `MainActivity.kt`
- `NavigationDestination.kt`
- tests

Steps:
1. Add `initialOpenCreateDialog`.
2. Pass true when `currentDestination is BudgetCreate`.
3. Ensure one-shot consumption so dialog does not reopen forever.
4. Add tests.

---

## Phase 2 — Centralize empty-state action execution

Files:
- `EmptyStateAction.kt`
- new `EmptyStateActionExecutor.kt`
- feature screens
- tests

Steps:
1. Keep `EmptyStateFeatureAction` enum.
2. Create central exhaustive executor.
3. Remove local `else -> {}` handlers.
4. Remove no-op comments.
5. Add registry completeness test.

---

## Phase 3 — Finish `FormDialog`

Files:
- `FormComponents.kt`

Steps:
1. Guard `onDismissRequest` while submitting.
2. Use theme content color for spinner.
3. Add Compose test for submit/dismiss behavior.

---

## Phase 4 — Finish skeleton cleanup

Files:
- `LoadingSkeleton.kt`

Steps:
1. Add parent semantics to `ReceiptScanSkeleton`.
2. Add parent semantics to `AIProcessingSkeleton`.
3. Replace remaining `SemanticColors` in generic skeletons.
4. Add semantics tests.

---

## Phase 5 — Sanitizer/test hardening

Files:
- `AmountInputSanitizer.kt`
- tests

Steps:
1. Fix multiple-decimal merge behavior.
2. Add full sanitizer test matrix.
3. Add `FormAmountField` contract test.

---

# Recommended Tests

## `BudgetCreateNavigationTest`

Cases:
- `NavigationDestination.BudgetCreate` selects Budget tab.
- Budget create dialog opens once.
- dismissing dialog does not reopen on recomposition.
- `budget_create` token round-trips.

## `EmptyStateRegistryCompletenessTest`

Cases:
- no duplicate action IDs per screen.
- no default `ExecuteAction`.
- every `NavigateToDestination` is renderable.
- every `OpenFeature` resolves through executor.
- no executor branch is no-op.

## `AmountInputSanitizerTest`

Cases:
- `.5` → `0.5`
- `..5` → `0.5`
- `12..34` → `12.34`
- `1.2.3` → `1.23`
- `0012.30` → `12.30`
- `0` preserved.
- `0.` preserved.
- `0.01` valid.
- `0` invalid as final amount.

## `FormDialogSubmittingStateTest`

Cases:
- confirm disabled while submitting.
- dismiss button disabled while submitting.
- outside/back dismiss ignored while submitting.
- spinner uses themed content color.

## `LoadingSkeletonSemanticsTest`

Cases:
- `DashboardCardSkeleton` announces once.
- `ChartSkeleton` announces once.
- `ReceiptScanSkeleton` announces once.
- `AIProcessingSkeleton` announces once.
- child boxes are decorative.

---

# Final Severity Table After `bb9e82e`

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S2-BB9-001 | High | Unresolved | `BudgetCreate` route exists but does not open create dialog |
| S2-BB9-002 | High | Partial | Typed feature enum still silently no-ops through `else -> {}` |
| S2-BB9-003 | Med/High | Partial | `FormDialog` can still outside/back dismiss while submitting |
| S2-BB9-004 | Medium | Partial | `FormDialog` spinner color hardcoded |
| S2-BB9-005 | Medium | Partial | Receipt/AI skeletons lack semantics/theme cleanup |
| S2-BB9-006 | Medium | Partial | Sanitizer multiple-decimal behavior weak |
| S2-BB9-007 | Medium | Unresolved | Default registry could still use arbitrary `ExecuteAction` |
| S2-BB9-008 | Medium | Unresolved | Metric components still use raw/default colors/dimensions |
| S2-BB9-009 | Low/Med | Unresolved | Raw dimensions remain common |
| S2-BB9-010 | High | Test gap | Slice 2 contract tests still missing |

---

# Immediate Agent Task List

## Task A — Wire `BudgetCreate`
Make the route open the budget creation dialog, with one-shot behavior.

## Task B — Central action executor
No local `else -> {}`. Every `EmptyStateFeatureAction` must be executable or not registered.

## Task C — Harden `FormDialog`
Block outside/back dismiss while submitting and use theme spinner color.

## Task D — Finish skeleton cleanup
Add parent loading semantics to receipt/AI skeletons and remove remaining `SemanticColors`.

## Task E — Add tests
Prioritize sanitizer, budget-create, action registry, form dialog, and skeleton semantics.

---

# Sources Reviewed

- Commit diff: https://github.com/panospao7/Cost-agregator/commit/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0
- Patch: https://github.com/panospao7/Cost-agregator/commit/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0.patch
- `EmptyStateAction.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0/app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/EmptyStateAction.kt
- `DefaultEmptyStateRegistryInitializer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0/app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/DefaultEmptyStateRegistryInitializer.kt
- `FormComponents.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0/app/src/main/java/com/yourname/expensetracker/ui/components/feature/FormComponents.kt
- `LoadingSkeleton.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0/app/src/main/java/com/yourname/expensetracker/ui/components/common/LoadingSkeleton.kt
- `AmountInputSanitizer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0/app/src/main/java/com/yourname/expensetracker/ui/util/AmountInputSanitizer.kt
- `MainActivity.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0/app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt
- `BudgetScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0/app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetScreen.kt
- `MetricComponents.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/bb9e82e60e616b2c7e9987ff9b2cc0b2a8c3bda0/app/src/main/java/com/yourname/expensetracker/ui/components/feature/MetricComponents.kt