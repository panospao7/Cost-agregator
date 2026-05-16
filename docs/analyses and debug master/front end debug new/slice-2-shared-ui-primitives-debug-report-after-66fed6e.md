# Slice 2 Re-Debug Report — Theme + Shared UI Primitives

Commit reviewed: `66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7`  
Commit title: `fix(receipt): Slice 7 S7-F583-001/002 atomic create+link, duplicate receipt detection`  
Review type: static GitHub source review, not local Gradle/device execution.

Important note: this commit changes only:
- `ReceiptLifecycleCoordinator.kt`
- `ReceiptScanViewModel.kt`

So it is not a Slice 2 commit. This report evaluates the current Slice 2 state at this commit, including fixes that appear to have landed in earlier commits.

Primary Slice 2 scope:
- `ui/components/common/*`
- `ui/components/emptystate/*`
- `ui/components/feature/*`
- `ui/util/AmountInputSanitizer.kt`
- `ui/util/ColorExtensions.kt`
- `ui/util/ModifierExtensions.kt`

---

# Executive Summary

Slice 2 is **substantially improved** compared with the first Slice 2 report, but still **not fully closed**.

Confirmed improvements:
- `FormAmountField` now delegates to `AmountInputSanitizer`.
- `AmountInputSanitizer` now preserves transient `"0"` / `"0."` style input better than before.
- Contextual empty-state chips are disabled when `onActionClick` is missing.
- Empty-state dismiss icon is only rendered when `onDismissAction` exists.
- `EmptyStateAction.descriptionRes` is exposed to accessibility semantics.
- The two obvious default no-op registry actions were replaced with navigation actions.
- `SkeletonBox` is quiet by default and uses theme-aware default colors.
- `ColorExtensions` now has explicit nullable/default APIs.
- `isValidHexColor()` now uses a strict hex regex.
- `Color.toHexString()` supports alpha and clamps/rounds channel values.
- `Modifier.budgetScale()` clamps unsafe scale values.
- `FormDropdown` now guards expansion when disabled and closes when disabled.

Still unresolved or partial:
1. `OpenFeature(String)` remains stringly typed and unverified.
2. Budget empty-state “create budget” still likely navigates to the same screen instead of a real create flow.
3. Skeleton components are quiet, but not all parent skeletons announce loading once.
4. `LoadingSkeleton` still uses `SemanticColors` overrides in multiple concrete skeletons.
5. `MetricComponents` still use raw color defaults and raw dimensions.
6. `FormDialog` still has no built-in submitting/loading protection.
7. Default empty-state action executability is still not contract-tested.
8. Shared primitive tests are still likely incomplete.
9. `AmountInputSanitizer` still has edge cases around leading decimal and multiple decimal separators.
10. Shared components still use many raw `dp` values instead of `Dimens`.

Recommended next order:
1. Finish empty-state action executability contract.
2. Fix budget/create empty-state action.
3. Finish skeleton accessibility and theme cleanup.
4. Add `FormDialog(isSubmitting)`.
5. Add focused JVM/Compose tests.

---

# Status of Previous Slice 2 Issues

| ID | Status at `66fed6e` | Notes |
|---|---|---|
| S2-001 | Resolved | `FormAmountField` now calls `AmountInputSanitizer.sanitize(raw)`. |
| S2-002 | Partial | `"0"` preserved, but `".5"` still appears to sanitize to `".5"` instead of `"0.5"`. |
| S2-003 | Resolved | Contextual chips receive `enabled = onActionClick != null`. |
| S2-004 | Resolved | Dismiss button only renders when `canDismiss`. |
| S2-005 | Resolved | Chip semantics include title + description. |
| S2-006 | Mostly fixed | Carbon/lifestyle no-op actions replaced. Need contract test to prevent future no-ops. |
| S2-007 | Unresolved | `OpenFeature(String)` remains raw string action. |
| S2-008 | Unresolved | Budget create action still navigates to `NavigationDestination.Budget`. |
| S2-009 | Mostly fixed | Child skeleton boxes quiet; not every skeleton parent announces loading once. |
| S2-010 | Partial | Metric text uses `MaterialTheme`, but color defaults/raw colors remain. |
| S2-011 | Partial | `SkeletonBox` defaults theme-aware; concrete skeletons still pass `SemanticColors`. |
| S2-012 | Unresolved | Raw `dp` values still common in shared primitives. |
| S2-013 | Mostly fixed | Explicit color APIs added; deprecated fail-open API remains for compatibility. |
| S2-014 | Resolved | Strict hex regex rejects named colors. |
| S2-015 | Resolved | Alpha/clamping supported. |
| S2-016 | Resolved | Scale is finite/clamped. |
| S2-017 | Resolved | Disabled dropdown cannot expand; closes when disabled. |
| S2-018 | Unresolved | `FormDialog` still lacks `isSubmitting`. |
| S2-019 | Unresolved | No strong executable-action registry contract verified. |
| S2-020 | Partial/unverified | Some tests may exist, but key contracts still need coverage. |

---

# Confirmed Fixes

## S2-FIX-001 — `FormAmountField` now uses shared sanitizer

**Status:** Resolved  
**File:** `FormComponents.kt`

Current behavior:

```kotlin
onValueChange = { raw ->
    onValueChange(AmountInputSanitizer.sanitize(raw))
}
```

This resolves the previous inconsistency where the shared amount sanitizer existed but the shared form field bypassed it.

## Acceptance tests still needed

Add `FormAmountFieldSanitizerContractTest`:
- entering `"12.345abc"` emits `"12.34"`;
- entering `"0012.30"` emits `"12.30"`;
- entering `"12..34"` has deterministic behavior;
- blank input remains blank.

---

## S2-FIX-002 — Sanitizer preserves zero transient input better

**Status:** Partial  
**File:** `AmountInputSanitizer.kt`

Current behavior now preserves all-zero integer input as `"0"`.

This fixes the old bug where typing `"0"` could become `""`.

## Remaining issue

For input starting with a decimal:

```text
.5
```

current logic likely returns:

```text
.5
```

instead of:

```text
0.5
```

Recommended rule:

```kotlin
if (cleaned.startsWith(".")) return "0.$fraction"
```

Also, multiple separators such as `"12..34"` currently discard later fractional digits. That may be acceptable, but it must be explicitly tested.

---

## S2-FIX-003 — Empty-state action chips are no longer clickable no-ops

**Status:** Resolved  
**File:** `EnhancedEmptyState.kt`

`ActionChip` receives:

```kotlin
enabled = onActionClick != null
```

and passes that to `ElevatedAssistChip`.

This fixes the misleading UX where a chip looked actionable but no callback existed.

---

## S2-FIX-004 — Empty-state dismiss button hidden when no callback exists

**Status:** Resolved  
**File:** `EnhancedEmptyState.kt`

`ActionChip` now receives:

```kotlin
canDismiss = onDismissAction != null
```

and only renders the `IconButton` when `canDismiss` is true.

---

## S2-FIX-005 — `descriptionRes` is exposed through accessibility semantics

**Status:** Resolved  
**File:** `EnhancedEmptyState.kt`

The chip content description now includes title and description.

Recommended cleanup:
- avoid manual string concatenation with punctuation if localization needs different grammar;
- prefer a resource format:

```xml
<string name="a11y_empty_action_format">%1$s. %2$s</string>
```

---

## S2-FIX-006 — Obvious default no-op actions replaced

**Status:** Mostly fixed  
**File:** `DefaultEmptyStateRegistryInitializer.kt`

Previous no-op actions like:
- carbon `track_carbon`
- lifestyle `analyze_patterns`

now navigate to real destinations:
- `NavigationDestination.AddExpense`
- `NavigationDestination.Analytics()`

Good.

## Remaining issue

The default registry still uses many raw `OpenFeature(String)` IDs:
- `"add_warranty"`
- `"notification_settings"`
- `"add_subscription"`
- `"create_savings_goal"`
- `"savings_recommendations"`
- `"create_challenge"`
- `"no_spend_streak"`
- `"carbon_offset"`
- `"income_settings"`

These are not compile-time verified.

---

## S2-FIX-007 — Skeleton semantics are quieter

**Status:** Mostly fixed  
**File:** `LoadingSkeleton.kt`

`SkeletonBox` is now decorative by default:

```kotlin
Modifier.clearAndSetSemantics { }
```

and only announces loading when `announceLoading = true`.

`ListSkeleton` announces loading once at parent level.

## Remaining issue

Other parent skeletons:
- `DashboardCardSkeleton`
- `ChartSkeleton`
- `ReceiptScanSkeleton`
- `AIProcessingSkeleton`

do not appear to announce loading once at parent level.

They are no longer noisy, but some may now be silent to accessibility.

---

## S2-FIX-008 — Color extension APIs are much safer

**Status:** Resolved / mostly resolved  
**File:** `ColorExtensions.kt`

Good:
- `toComposeColorOrNull()`
- `toComposeColorOrDefault(default)`
- deprecated `toComposeColor()`
- strict `isValidHexColor()`
- `toHexString(includeAlpha)`

Remaining:
- deprecated fail-open API still exists, but that is acceptable as migration compatibility if no new call sites use it.

---

## S2-FIX-009 — `budgetScale()` is safe

**Status:** Resolved  
**File:** `ModifierExtensions.kt`

Now clamps to:

```kotlin
0.8f..1.2f
```

and rejects `NaN` / infinite values.

---

## S2-FIX-010 — Disabled dropdown behavior fixed

**Status:** Resolved  
**File:** `FormComponents.kt`

`FormDropdown` now:
- refuses expansion when disabled,
- closes if `enabled` becomes false.

---

# Remaining Issues

---

## S2-007R — `OpenFeature(String)` remains stringly typed and unverified

**Severity:** High  
**Files:**
- `EmptyStateAction.kt`
- `DefaultEmptyStateRegistryInitializer.kt`

## Problem

Default empty-state actions still contain raw feature IDs.

Examples:

```kotlin
EmptyStateActionType.OpenFeature("create_savings_goal")
EmptyStateActionType.OpenFeature("carbon_offset")
EmptyStateActionType.OpenFeature("income_settings")
```

There is no compile-time guarantee that:
- these IDs are handled,
- the target feature exists,
- the action opens the intended UI,
- the action does not silently no-op.

## Fix Strategy

Replace raw strings with a typed enum:

```kotlin
enum class EmptyStateFeatureAction {
    AddWarranty,
    NotificationSettings,
    AddSubscription,
    CreateSavingsGoal,
    SavingsRecommendations,
    CreateChallenge,
    NoSpendStreak,
    CarbonOffset,
    IncomeSettings
}
```

Then:

```kotlin
data class OpenFeature(val action: EmptyStateFeatureAction) : EmptyStateActionType()
```

Executor:

```kotlin
when (action) {
    EmptyStateFeatureAction.CreateSavingsGoal -> ...
}
```

## Acceptance Tests

- every default `OpenFeature` has an executor branch;
- adding a new enum value fails until executor is updated;
- no default empty-state action can be an unknown string.

---

## S2-008R — Budget create empty-state action likely remains same-screen no-op

**Severity:** Medium/High  
**File:** `DefaultEmptyStateRegistryInitializer.kt`

## Problem

Budget empty-state CTA:

```kotlin
id = "create_budget"
action = NavigateToDestination(NavigationDestination.Budget)
```

If the user is already on Budget, this likely reopens the same screen instead of opening a create-budget dialog/form.

## Fix Strategy

Use an explicit create intent:

Option A:

```kotlin
data class Budget(val openCreateDialog: Boolean = false) : NavigationDestination()
```

Then:

```kotlin
NavigationDestination.Budget(openCreateDialog = true)
```

Option B:

```kotlin
EmptyStateFeatureAction.CreateBudget
```

and handle in the budget screen.

## Acceptance Tests

- tapping “Create budget” opens creation UI;
- it does not simply navigate to the current screen;
- screen restoration handles the create-dialog parameter safely.

---

## S2-009R — Skeleton parents do not consistently announce loading once

**Severity:** Medium  
**File:** `LoadingSkeleton.kt`

## Problem

`SkeletonBox` is quiet by default, which is good.  
`ListSkeleton` announces loading once, which is also good.

But other skeleton composites do not visibly add parent-level loading semantics.

Affected:
- `DashboardCardSkeleton`
- `ChartSkeleton`
- `ReceiptScanSkeleton`
- `AIProcessingSkeleton`

## Fix Strategy

Add parent semantics:

```kotlin
val loadingDescription = stringResource(R.string.a11y_loading_content)

Card(
    modifier = modifier.semantics {
        contentDescription = loadingDescription
    }
)
```

or for decorative usage, document that the caller must announce loading.

## Acceptance Tests

- `ListSkeleton` has one loading node.
- `DashboardCardSkeleton` has one loading node.
- `ChartSkeleton` has one loading node.
- child `SkeletonBox` nodes remain hidden.

---

## S2-011R — Concrete skeletons still use `SemanticColors`

**Severity:** Medium  
**File:** `LoadingSkeleton.kt`

## Problem

Although `SkeletonBox` defaults are theme-aware, concrete skeletons still pass:
- `SemanticColors.PrimaryIndigo`
- `SemanticColors.SurfaceLight`

Examples:
- transaction icon placeholder,
- dashboard card icon placeholder,
- chart bars,
- receipt scan placeholder,
- AI processing circle.

## Impact

Dark theme/dynamic color may still have contrast or tone drift.

## Fix Strategy

Use `MaterialTheme.colorScheme` for generic skeletons:

```kotlin
val accentSkeleton = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
val surfaceSkeleton = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
```

Only use semantic colors when a skeleton is intentionally representing semantic status.

## Acceptance Tests

- dark theme skeletons have acceptable contrast;
- no generic skeleton uses `SemanticColors.SurfaceLight`;
- no generic skeleton uses fixed primary color unless intentional.

---

## S2-010R — Metric components are only partially theme-cleaned

**Severity:** Medium  
**File:** `MetricComponents.kt`

## Current good state

Text colors mostly use:
- `MaterialTheme.colorScheme.onSurface`
- `MaterialTheme.colorScheme.onSurfaceVariant`

## Remaining problems

Still present:
- default `color = SemanticColors.PrimaryIndigo`
- raw color as text for some values
- raw `dp` dimensions
- caller-provided raw status colors with no contrast guard

## Fix Strategy

Use semantic tokens or Material color scheme:

```kotlin
color: Color = MaterialTheme.colorScheme.primary
```

But because composable default params cannot call composables, use nullable:

```kotlin
color: Color? = null
val resolvedColor = color ?: MaterialTheme.colorScheme.primary
```

## Acceptance Tests

- metric cards render correctly in dark theme;
- status colors meet contrast requirements;
- no generic metric text uses fixed semantic text colors.

---

## S2-012R — Shared primitives still use many raw dimensions

**Severity:** Low/Medium  
**Files:**
- `FormComponents.kt`
- `MetricComponents.kt`
- `EnhancedEmptyState.kt`
- `LoadingSkeleton.kt`

## Examples

Still visible:
- `12.dp`
- `16.dp`
- `20.dp`
- `48.dp`
- `4.dp`
- `18.dp`
- `40.dp`
- `120.dp`

Some local one-off values are fine, but many are recurring spacing/sizing tokens.

## Fix Strategy

Move common values to `Dimens`:
- `Space4`
- `Space8`
- `Space12`
- `Space16`
- `Space20`
- `MinTouchTarget`
- `IconSmall`
- `IconMedium`
- `CardCornerRadius`
- `PillCornerRadius`

## Acceptance Tests

Static check or Detekt rule:
- shared primitives should not introduce new raw spacing values without justification.

---

## S2-018R — `FormDialog` still lacks submitting/loading protection

**Severity:** Medium  
**File:** `FormComponents.kt`

## Problem

`FormDialog` supports:

```kotlin
confirmEnabled: Boolean
```

but no first-class:

```kotlin
isSubmitting: Boolean
```

Feature screens must implement duplicate-submit prevention themselves.

## Fix Strategy

Add:

```kotlin
isSubmitting: Boolean = false
```

Button:

```kotlin
Button(
    onClick = onConfirm,
    enabled = confirmEnabled && !isSubmitting
) {
    if (isSubmitting) {
        CircularProgressIndicator(...)
    } else {
        Text(resolvedConfirm)
    }
}
```

## Acceptance Tests

- confirm disabled while submitting;
- loading indicator appears;
- click during submitting does not call `onConfirm`;
- existing callers compile with default false.

---

## S2-019R — Default empty-state executable-action contract still missing

**Severity:** High  
**Files:**
- `DefaultEmptyStateRegistryInitializer.kt`
- test suite

## Problem

Even though obvious no-op lambdas were replaced, the system still needs a contract test proving every default action resolves.

## Required Contract Test

`EmptyStateRegistryCompletenessTest`:

Cases:
- every screen key has unique action IDs;
- title/description resource IDs exist;
- no default `ExecuteAction { }`;
- every `NavigateToDestination` is renderable;
- every `OpenFeature` resolves through a typed executor;
- budget create action opens a real create flow.

---

## S2-020R — Focused primitive tests still need completion

**Severity:** Medium  
**Files:** test suite

Recommended tests:
- `AmountInputSanitizerTest`
- `FormAmountFieldSanitizerContractTest`
- `EnhancedEmptyStateCallbackPolicyTest`
- `LoadingSkeletonSemanticsTest`
- `ColorExtensionsTest`
- `MetricComponentsThemeContractTest`
- `FormDialogSubmittingStateTest`
- `EmptyStateRegistryCompletenessTest`

---

# Updated Implementation Plan

## Phase 1 — Empty-state action safety

Files:
- `EmptyStateAction.kt`
- `DefaultEmptyStateRegistryInitializer.kt`
- new action executor
- tests

Steps:
1. Replace `OpenFeature(String)` with typed enum.
2. Implement exhaustive executor.
3. Replace budget same-screen action with real create-budget intent.
4. Add registry completeness test.

Acceptance:
- no raw feature strings;
- no same-screen create no-op;
- every action is executable.

---

## Phase 2 — Skeleton accessibility/theme finalization

Files:
- `LoadingSkeleton.kt`

Steps:
1. Add parent-level loading semantics to all composite skeletons.
2. Keep `SkeletonBox` decorative by default.
3. Replace remaining `SemanticColors` skeleton overrides with theme-aware colors.
4. Add Compose semantics tests.

Acceptance:
- loading announced once per skeleton component;
- no repeated child announcements;
- dark theme safe.

---

## Phase 3 — Form primitive hardening

Files:
- `AmountInputSanitizer.kt`
- `FormComponents.kt`

Steps:
1. Fix leading decimal input: `".5"` → `"0.5"`.
2. Decide/test multiple decimal behavior.
3. Add `isSubmitting` to `FormDialog`.
4. Add tests.

Acceptance:
- amount sanitizer behavior is deterministic and user-friendly;
- dialogs can prevent double-submit.

---

## Phase 4 — Theme/dimension cleanup

Files:
- `MetricComponents.kt`
- shared primitive files
- `Dimens.kt`

Steps:
1. Replace nullable/default metric colors with `MaterialTheme.colorScheme.primary`.
2. Move recurring raw `dp` values to `Dimens`.
3. Add dark-theme/golden or screenshot checks later.

---

# Recommended Tests

## `AmountInputSanitizerTest`

Cases:
- `"0"` → `"0"`
- `"0."` → `"0."`
- `".5"` → `"0.5"`
- `"12.345"` → `"12.34"`
- `"0012.30"` → `"12.30"`
- `"12..34"` deterministic expected behavior
- blank → `""`
- `"0"` invalid final amount
- `"0.01"` valid final amount

## `EnhancedEmptyStateCallbackPolicyTest`

Cases:
- action chip disabled when click callback null;
- dismiss icon hidden when dismiss callback null;
- description is included in semantics;
- primary/secondary buttons disabled when callback null.

## `LoadingSkeletonSemanticsTest`

Cases:
- list skeleton announces once;
- dashboard card skeleton announces once;
- chart skeleton announces once;
- child skeleton boxes are decorative.

## `EmptyStateRegistryCompletenessTest`

Cases:
- no duplicate action IDs per screen;
- no no-op execute actions;
- all destinations render;
- all feature actions resolve;
- budget create opens create UI.

## `ColorExtensionsTest`

Cases:
- valid `#RRGGBB`;
- valid `#AARRGGBB`;
- reject `"red"`;
- invalid color returns null/default explicitly;
- alpha output works.

---

# Final Severity Table at `66fed6e`

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S2-001 | High | Resolved | `FormAmountField` uses shared sanitizer |
| S2-002 | Medium | Partial | zero preserved; leading decimal still needs policy/test |
| S2-003 | High | Resolved | contextual chips disabled when callback null |
| S2-004 | Medium | Resolved | dismiss icon hidden without callback |
| S2-005 | Medium | Resolved | description exposed to accessibility |
| S2-006 | High | Mostly fixed | obvious registry no-ops replaced |
| S2-007R | High | Unresolved | `OpenFeature(String)` still stringly typed |
| S2-008R | Med/High | Unresolved | budget create CTA likely same-screen no-op |
| S2-009R | Medium | Partial | skeletons quiet; parent announcements incomplete |
| S2-010R | Medium | Partial | metric theme cleanup incomplete |
| S2-011R | Medium | Partial | concrete skeletons still use `SemanticColors` |
| S2-012R | Low/Med | Unresolved | raw dimensions still common |
| S2-013 | Medium | Mostly fixed | explicit color APIs added |
| S2-014 | Low/Med | Resolved | strict hex validation |
| S2-015 | Low | Resolved | alpha/clamping in hex output |
| S2-016 | Low | Resolved | safe scale clamp |
| S2-017 | Low | Resolved | dropdown disabled expansion guarded |
| S2-018R | Medium | Unresolved | `FormDialog` lacks submitting state |
| S2-019R | High | Test gap | executable-action registry contract missing |
| S2-020R | Medium | Test gap | focused primitive tests incomplete |

---

# Immediate Agent Task List

## Task A — Typed empty-state actions
Replace `OpenFeature(String)` and add executor contract tests.

## Task B — Budget create action
Make “Create budget” open actual creation UI, not just Budget route.

## Task C — Skeleton accessibility/theme finish
Parent-level semantics for all skeleton groups and remove remaining generic `SemanticColors`.

## Task D — Form dialog submitting state
Add `isSubmitting` to shared `FormDialog`.

## Task E — Test hardening
Add sanitizer, empty-state, skeleton, color, and registry tests.

---

# Sources Reviewed

- Commit diff showing this commit is Slice 7-only: https://github.com/panospao7/Cost-agregator/commit/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7
- `FormComponents.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/components/feature/FormComponents.kt
- `AmountInputSanitizer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/util/AmountInputSanitizer.kt
- `EnhancedEmptyState.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/components/common/EnhancedEmptyState.kt
- `LoadingSkeleton.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/components/common/LoadingSkeleton.kt
- `DefaultEmptyStateRegistryInitializer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/DefaultEmptyStateRegistryInitializer.kt
- `EmptyStateAction.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/EmptyStateAction.kt
- `ColorExtensions.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/util/ColorExtensions.kt
- `ModifierExtensions.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/util/ModifierExtensions.kt
- `MetricComponents.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/66fed6e6c6b83e78abb5fdb0ff38191f4acbaac7/app/src/main/java/com/yourname/expensetracker/ui/components/feature/MetricComponents.kt