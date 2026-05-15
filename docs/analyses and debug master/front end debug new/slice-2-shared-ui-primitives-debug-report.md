# Slice 2 Debug Report — Theme + Shared UI Primitives

Commit reviewed: `ea3f716eebba8c513edeeba40db394c10ca829cb`  
Scope:
- `ui/theme/*`
- `ui/components/common/*`
- `ui/components/emptystate/*`
- `ui/components/feature/*`
- `ui/util/AmountInputSanitizer.kt`
- `ui/util/ColorExtensions.kt`
- `ui/util/ModifierExtensions.kt`

Primary source:  
https://github.com/panospao7/Cost-agregator/commit/ea3f716eebba8c513edeeba40db394c10ca829cb

---

# Executive Summary

Slice 2 is **partially fixed but not complete**.

Good progress:
- Shared `EmptyState`, `EnhancedEmptyState`, `ErrorState`, and `LoadingSkeleton` primitives exist.
- Empty/error states are mostly scroll-safe.
- Legacy empty-state primary/secondary buttons correctly disable when callbacks are missing.
- Empty-state actions use string resources.
- `AmountInputSanitizer` exists.
- `ContextualActionRegistry` exists and has default Hilt initialization.

Still problematic:
1. `FormAmountField` does **not** use `AmountInputSanitizer`.
2. Contextual empty-state chips are clickable even when no callback is provided.
3. Dismiss icons are shown even when dismiss behavior is absent.
4. Several default empty-state actions are no-op or weakly mapped.
5. `LoadingSkeleton` is too noisy for accessibility.
6. Shared components still use `SemanticColors`/hardcoded colors instead of `MaterialTheme`.
7. `EmptyStateAction.descriptionRes` is not surfaced in chip UI or accessibility.
8. `ColorExtensions` fail open to gray and `isValidHexColor()` may accept non-hex color names.
9. There are missing contract tests for amount sanitizing, empty-state action executability, skeleton semantics, and MaterialTheme usage.

Recommended fix order:
1. Fix amount-field sanitizer usage.
2. Fix empty-state action click/dismiss callback behavior.
3. Remove/replace no-op default registry actions.
4. Fix loading skeleton semantics.
5. Normalize shared component color usage.
6. Add focused JVM/Compose tests.

---

# Status of Previous Slice 2 Invariants

## S2-PREV-001 — Null callbacks render disabled, not clickable

**Status:** Partially resolved.

Resolved:
- `EnhancedEmptyState` legacy primary button uses `enabled = onPrimaryClick != null`.
- Legacy secondary button uses `enabled = onSecondaryClick != null`.
- `FormActions` submit button supports `submitEnabled`.

Unresolved:
- Contextual `ActionChip` always calls:

```kotlin
onClick = { onActionClick?.invoke(action) }
onDismiss = { onDismissAction?.invoke(action.id) }
```

If callback is null, the chip/dismiss control remains clickable but does nothing.

**Impact:** Misleading UX and bad accessibility. TalkBack/users can activate controls with no effect.

---

## S2-PREV-002 — Empty/error states are scroll-safe

**Status:** Mostly resolved.

Evidence:
- `EnhancedEmptyState` uses `verticalScroll(rememberScrollState())`.
- `ErrorState` uses `verticalScroll(rememberScrollState())`.
- `EnhancedEmptyState` top-aligns on shorter height.

Remaining risk:
- Full-screen `.fillMaxSize()` may be too aggressive when used inside small embedded containers.
- Component tests should verify small-height rendering.

---

## S2-PREV-003 — Shared components use `MaterialTheme`

**Status:** Partially unresolved.

Resolved:
- `EnhancedEmptyState` and `ErrorState` mostly use `MaterialTheme.colorScheme`.

Unresolved:
- `MetricComponents.kt` uses `SemanticColors.TextPrimary`, `SemanticColors.TextSecondary`, and caller-provided raw colors.
- `LoadingSkeleton.kt` uses `SemanticColors.SurfaceLight`, `SemanticColors.TextSecondary`, and `SemanticColors.PrimaryIndigo`.
- Several shared components use raw `dp` values instead of `Dimens`.

**Impact:** Dark mode/theme consistency and contrast can drift.

---

## S2-PREV-004 — Loading skeleton semantics are quiet

**Status:** Unresolved.

Problem:
- `SkeletonBox` sets a content description on every placeholder.
- `ListSkeleton` also sets a loading content description on the parent.

**Impact:** Screen readers may announce many identical “loading content” nodes.

Recommended policy:
- Parent skeleton container announces loading once.
- Individual shimmer boxes should be hidden/quiet.

---

## S2-PREV-005 — Empty-state actions are localized

**Status:** Mostly resolved.

Evidence:
- `EmptyStateAction` uses `@StringRes titleRes` and `descriptionRes`.
- `EnhancedEmptyState` resolves action title via `stringResource(action.titleRes)`.
- Header uses `R.string.empty_state_suggested_actions`.

Remaining issue:
- `descriptionRes` is not displayed or exposed through accessibility semantics.
- `FormComponents` and `MetricComponents` accept raw `String` labels, which is flexible but makes hardcoded string drift easier.

---

## S2-PREV-006 — Amount inputs use the shared sanitizer

**Status:** Unresolved.

`AmountInputSanitizer` exists, but `FormAmountField` reimplements different filtering inline.

Current `FormAmountField` allows:
- unlimited fractional digits,
- unlimited integer digits,
- leading zero drift,
- only a loose single-decimal check.

This defeats the purpose of the shared sanitizer.

---

# Issues Found

---

## S2-001 — `FormAmountField` bypasses `AmountInputSanitizer`

**Severity:** High  
**Files:**
- `app/src/main/java/com/yourname/expensetracker/ui/components/feature/FormComponents.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/util/AmountInputSanitizer.kt`

## Problem

`AmountInputSanitizer` defines centralized money-input behavior, but `FormAmountField` still performs local filtering:

```kotlin
val filtered = newValue.filter { it.isDigit() || it == '.' }
val decimalCount = filtered.count { it == '.' }
if (decimalCount <= 1) {
    onValueChange(filtered)
}
```

This does not enforce:
- max 2 decimal places,
- max integer digits,
- consistent leading-zero policy,
- same behavior as other screens.

## Fix Strategy

Replace inline filtering with the sanitizer.

```kotlin
import com.yourname.expensetracker.ui.util.AmountInputSanitizer

OutlinedTextField(
    value = value,
    onValueChange = { raw ->
        onValueChange(AmountInputSanitizer.sanitize(raw))
    },
    ...
)
```

If some fields need more decimals, expose:

```kotlin
maxFractionDigits: Int = 2
```

and call:

```kotlin
AmountInputSanitizer.sanitize(raw, maxFractionDigits)
```

## Tests

Add `AmountInputSanitizerTest`.

Required cases:
- `"12.345"` -> `"12.34"`
- `"0012.30"` -> `"12.30"`
- `"abc12.3x"` -> `"12.3"`
- `"12..34"` deterministic behavior
- `"1234567890123"` limited to 10 integer digits
- blank -> `""`
- `"0"` preserves a usable transient input if desired

Add a Compose test or extracted JVM test for `FormAmountField` to prove it delegates to the sanitizer.

---

## S2-002 — `AmountInputSanitizer` has questionable transient-input behavior

**Severity:** Medium  
**File:** `AmountInputSanitizer.kt`

## Problem

Current sanitizer trims all leading zeroes. For `"0"` with no decimal separator, the result becomes `""`.

That can make typing awkward:
1. User types `0`.
2. Field becomes empty.
3. User cannot naturally type `0.50`.

## Fix Strategy

Clarify policy:

### Recommended UI policy
Allow transient editing states:
- `"0"` is allowed in the field.
- `"0."` is allowed while typing.
- `"0.5"` is valid transient.
- final validation still requires amount > 0.

Suggested logic:
- If cleaned is all zeroes and no decimal, return `"0"`.
- If cleaned starts with `.`, return `"0." + fraction`.
- Preserve `"0."`.

## Acceptance Criteria

- Typing `0`, `.`, `5` can result in `0.5`.
- `isValid("0") == false`.
- `isValid("0.01") == true`.

---

## S2-003 — Contextual action chips are clickable no-ops when callback is null

**Severity:** High  
**File:** `EnhancedEmptyState.kt`

## Problem

`ActionChip` is always enabled, even if `onActionClick` is null.

Current behavior:
```kotlin
onClick = { onActionClick?.invoke(action) }
```

So the UI presents an actionable chip, but clicking does nothing.

## Fix Strategy

Pass enabled state into `ActionChip`.

```kotlin
ActionChip(
    action = action,
    enabled = onActionClick != null,
    onClick = { onActionClick?.invoke(action) },
    canDismiss = onDismissAction != null,
    onDismiss = { onDismissAction?.invoke(action.id) }
)
```

Update private composable:

```kotlin
private fun ActionChip(
    action: EmptyStateAction,
    enabled: Boolean,
    canDismiss: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit
)
```

For `ElevatedAssistChip`:

```kotlin
enabled = enabled
```

## Acceptance Criteria

- If `actions` is non-empty but `onActionClick == null`, chips are disabled or not rendered.
- No clickable no-op semantics exist.
- Compose semantics test verifies disabled state.

---

## S2-004 — Dismiss icon appears even when no dismiss behavior exists

**Severity:** Medium  
**File:** `EnhancedEmptyState.kt`

## Problem

Each contextual action always renders an `IconButton` for dismiss, even if `onDismissAction` is null. Clicking it does nothing.

## Fix Strategy

Only render dismiss button if callback exists.

```kotlin
if (canDismiss) {
    IconButton(onClick = onDismiss) { ... }
}
```

Alternative:
- Keep visible but disabled if design requires constant layout.
- Preferred: hide absent behavior.

## Acceptance Criteria

- No dismiss icon when `onDismissAction == null`.
- Dismiss icon appears and works when callback exists.
- `cd_dismiss_action` is only present when actionable.

---

## S2-005 — `EmptyStateAction.descriptionRes` is unused

**Severity:** Medium  
**Files:**
- `EmptyStateAction.kt`
- `EnhancedEmptyState.kt`

## Problem

`EmptyStateAction` carries `descriptionRes`, but `ActionChip` displays only `titleRes`.

This means helpful descriptions are unused, and accessibility does not explain what the chip does.

## Fix Strategy

At minimum, expose description through semantics:

```kotlin
val label = stringResource(action.titleRes)
val description = stringResource(action.descriptionRes)

ElevatedAssistChip(
    modifier = Modifier.semantics {
        contentDescription = "$label. $description"
    },
    ...
)
```

Better UI option:
- For larger empty states, render a compact action card instead of a chip:
  - title
  - description
  - icon
  - optional dismiss

## Acceptance Criteria

- `descriptionRes` is either visible or part of content description.
- Accessibility test verifies chip announces title + description.

---

## S2-006 — Default empty-state registry contains no-op actions

**Severity:** High  
**File:** `DefaultEmptyStateRegistryInitializer.kt`

## Problem

Some registry actions are defined as:

```kotlin
EmptyStateActionType.ExecuteAction { }
```

Examples:
- Carbon: `track_carbon`
- Lifestyle: `analyze_patterns`

These are visible CTAs that execute nothing unless another layer intercepts them specially. That is fragile and likely a real no-op.

## Fix Strategy

Do not register no-op actions.

Options:
1. Replace with real `NavigateToDestination`.
2. Replace with concrete `OpenFeature` IDs that have a verified executor.
3. Remove until implemented.

Example:

```kotlin
action = EmptyStateActionType.NavigateToDestination(
    NavigationDestination.CarbonFootprint
)
```

Only if such route exists and renders.

## Acceptance Criteria

- No default `ExecuteAction { }` no-ops.
- Every default action has an executable path.
- Contract test fails if action type is no-op.

---

## S2-007 — `OpenFeature(String)` has no visible contract guaranteeing executability

**Severity:** Medium  
**Files:**
- `EmptyStateAction.kt`
- `DefaultEmptyStateRegistryInitializer.kt`

## Problem

Default actions use string feature IDs such as:
- `"add_warranty"`
- `"notification_settings"`
- `"create_savings_goal"`
- `"savings_recommendations"`
- `"create_challenge"`
- `"carbon_offset"`
- `"income_settings"`

There is no compile-time guarantee that these strings map to a destination or handler.

## Fix Strategy

Replace stringly typed `OpenFeature` with one of:

### Option A — Destination-only actions
```kotlin
sealed class EmptyStateActionType {
    data class NavigateToDestination(val destination: NavigationDestination) : EmptyStateActionType()
}
```

### Option B — Typed feature action enum
```kotlin
enum class EmptyStateFeatureAction {
    AddWarranty,
    NotificationSettings,
    CreateSavingsGoal,
    SavingsRecommendations,
    CreateChallenge,
    CarbonOffset,
    IncomeSettings
}
```

Then create one executor with exhaustive `when`.

## Acceptance Criteria

- No raw feature string IDs without a registry/executor.
- Contract test verifies all default actions resolve.

---

## S2-008 — Budget empty-state action likely navigates to the same screen instead of create flow

**Severity:** Medium  
**File:** `DefaultEmptyStateRegistryInitializer.kt`

## Problem

Budget action:

```kotlin
id = "create_budget"
action = NavigateToDestination(NavigationDestination.Budget)
```

If the user is already on the empty budget screen, this CTA probably just navigates to the same tab/screen rather than opening creation.

## Fix Strategy

Create a specific destination or action:
- `NavigationDestination.BudgetCreate`
- `NavigationDestination.Budget(openCreateDialog = true)`
- `EmptyStateFeatureAction.CreateBudget`

Preferred:
```kotlin
data class Budget(val openCreateDialog: Boolean = false) : NavigationDestination()
```

But only if Slice 1 route persistence policy supports parameterized tab routes.

## Acceptance Criteria

- “Create budget” CTA opens the budget creation UI.
- Test verifies action does not resolve to a no-op same-screen navigation.

---

## S2-009 — Loading skeleton accessibility is too noisy

**Severity:** Medium  
**File:** `LoadingSkeleton.kt`

## Problem

`SkeletonBox` sets:

```kotlin
.semantics { contentDescription = loadingContentDescription }
```

Every skeleton block becomes an accessibility node. In list/dashboard skeletons this can produce repeated announcements.

## Fix Strategy

Make `SkeletonBox` quiet by default.

```kotlin
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    announceLoading: Boolean = false,
    ...
) {
    val semanticsModifier =
        if (announceLoading) {
            Modifier.semantics { contentDescription = loadingContentDescription }
        } else {
            Modifier.clearAndSetSemantics { }
        }

    Box(modifier = modifier.then(semanticsModifier) ...)
}
```

Then parent components announce once:

```kotlin
Column(
    modifier = modifier.semantics {
        contentDescription = stringResource(R.string.a11y_loading_content)
    }
)
```

## Acceptance Criteria

- `ListSkeleton` exposes one loading announcement, not one per item/box.
- `DashboardCardSkeleton`, `ChartSkeleton`, `ReceiptScanSkeleton`, and `AIProcessingSkeleton` have parent-level semantics only.
- Compose semantics tests validate this.

---

## S2-010 — Shared metric components use `SemanticColors` instead of theme colors

**Severity:** Medium  
**File:** `MetricComponents.kt`

## Problem

`MetricCard`, `SummaryTotalCard`, and `AmountComparisonCard` use:
- `SemanticColors.TextPrimary`
- `SemanticColors.TextSecondary`
- raw color backgrounds via `color.copy(alpha = 0.15f)`

This can drift from Material3 color schemes.

## Fix Strategy

Use `MaterialTheme.colorScheme`.

Example:

```kotlin
val colors = MaterialTheme.colorScheme

Text(color = colors.onSurface)
Text(color = colors.onSurfaceVariant)

CardDefaults.cardColors(
    containerColor = color.copy(alpha = 0.12f)
)
```

For semantic status colors, use explicit semantic tokens:
- success
- warning
- error
- info

Do not use legacy text colors in generic shared components.

## Acceptance Criteria

- Generic metrics use `MaterialTheme.colorScheme`.
- Semantic custom colors are only for status indicators.
- Dark-theme screenshot/golden does not show low-contrast text.

---

## S2-011 — Loading skeleton uses fixed semantic colors instead of theme-aware skeleton colors

**Severity:** Medium  
**File:** `LoadingSkeleton.kt`

## Problem

Skeleton colors are currently based on:
- `SemanticColors.SurfaceLight`
- `SemanticColors.TextSecondary`
- `SemanticColors.PrimaryIndigo`

These may not adapt correctly to dark theme or dynamic color.

## Fix Strategy

Default to MaterialTheme:

```kotlin
val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
```

Allow overrides for special cases.

## Acceptance Criteria

- Skeletons look correct in light and dark themes.
- No direct use of `SemanticColors.SurfaceLight` in generic skeleton primitives.

---

## S2-012 — Shared primitives still use many raw dimension values

**Severity:** Low/Medium  
**Files:**
- `FormComponents.kt`
- `MetricComponents.kt`
- `EnhancedEmptyState.kt`
- `LoadingSkeleton.kt`

## Problem

Examples:
- `12.dp`
- `16.dp`
- `20.dp`
- `48.dp`
- `4.dp`

Some are acceptable for local shape details, but many should use `Dimens`.

## Fix Strategy

Move recurring values to `Dimens`:
- `Space4`
- `Space8`
- `Space12`
- `Space16`
- `Space20`
- `MinTouchTarget`
- `CardCornerRadius`
- `PillCornerRadius`

## Acceptance Criteria

- Shared primitives use `Dimens` for spacing/sizing.
- Raw `dp` is only used for one-off visual details with comment justification.

---

## S2-013 — `ColorExtensions.toComposeColor()` silently falls back to gray

**Severity:** Medium  
**File:** `ColorExtensions.kt`

## Problem

```kotlin
fun String.toComposeColor(): Color = try {
    Color(AndroidColor.parseColor(this))
} catch (e: Exception) {
    Color.Gray
}
```

Invalid color input silently becomes gray. This can hide bad config, broken theme values, or corrupted data.

## Fix Strategy

Make failure explicit:

```kotlin
fun String.toComposeColorOrNull(): Color? =
    runCatching { Color(AndroidColor.parseColor(this)) }.getOrNull()

fun String.toComposeColorOrDefault(default: Color): Color =
    toComposeColorOrNull() ?: default
```

Deprecate fail-open function:

```kotlin
@Deprecated("Use toComposeColorOrNull or toComposeColorOrDefault")
fun String.toComposeColor(): Color = ...
```

## Acceptance Criteria

- Callers choose fallback explicitly.
- Tests cover invalid color behavior.

---

## S2-014 — `isValidHexColor()` may accept named colors

**Severity:** Low/Medium  
**File:** `ColorExtensions.kt`

## Problem

`AndroidColor.parseColor()` accepts named colors on Android, not just hex. If the function is named `isValidHexColor`, it should reject `"red"` or `"blue"`.

## Fix Strategy

Use regex:

```kotlin
private val HexColorRegex =
    Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")

fun String.isValidHexColor(): Boolean =
    matches(HexColorRegex)
```

If you want named colors, rename function to `isValidAndroidColor()`.

## Acceptance Criteria

- `"#FF0000"` valid.
- `"#FFFF0000"` valid if alpha allowed.
- `"red"` invalid for `isValidHexColor`.
- `"GGGGGG"` invalid.

---

## S2-015 — `Color.toHexString()` ignores alpha and does not clamp

**Severity:** Low  
**File:** `ColorExtensions.kt`

## Problem

Current conversion:
- ignores alpha,
- truncates rather than rounds,
- does not clamp values.

## Fix Strategy

```kotlin
private fun Float.toByteInt(): Int =
    (coerceIn(0f, 1f) * 255f).roundToInt()

fun Color.toHexString(includeAlpha: Boolean = false): String {
    val r = red.toByteInt()
    val g = green.toByteInt()
    val b = blue.toByteInt()
    val a = alpha.toByteInt()

    return if (includeAlpha) {
        "#%02X%02X%02X%02X".format(a, r, g, b)
    } else {
        "#%02X%02X%02X".format(r, g, b)
    }
}
```

---

## S2-016 — `Modifier.budgetScale()` accepts unsafe scale values

**Severity:** Low  
**File:** `ModifierExtensions.kt`

## Problem

```kotlin
fun Modifier.budgetScale(scale: Float): Modifier =
    this.graphicsLayer(scaleX = scale, scaleY = scale)
```

Negative, zero, `NaN`, or infinite values can cause broken visuals.

## Fix Strategy

```kotlin
fun Modifier.budgetScale(scale: Float): Modifier {
    val safeScale = if (scale.isFinite()) scale.coerceIn(0.8f, 1.2f) else 1f
    return graphicsLayer(scaleX = safeScale, scaleY = safeScale)
}
```

Or rename to generic `safeScale()` if it is not budget-specific.

---

## S2-017 — `FormDropdown` should guard expansion when disabled

**Severity:** Low  
**File:** `FormComponents.kt`

## Problem

The field is disabled, but `onExpandedChange` currently does not explicitly guard `enabled`.

## Fix Strategy

```kotlin
ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { if (enabled) expanded = it }
)
```

Also close menu if enabled becomes false:

```kotlin
LaunchedEffect(enabled) {
    if (!enabled) expanded = false
}
```

---

## S2-018 — `FormDialog` has no built-in submitting/loading protection

**Severity:** Medium  
**File:** `FormComponents.kt`

## Problem

`FormDialog` supports `confirmEnabled`, but does not model `isSubmitting`. Many feature screens may implement save-once behavior inconsistently.

## Fix Strategy

Add optional parameter:

```kotlin
isSubmitting: Boolean = false
```

Then:

```kotlin
Button(
    onClick = onConfirm,
    enabled = confirmEnabled && !isSubmitting
) {
    if (isSubmitting) CircularProgressIndicator(...)
    else Text(resolvedConfirm)
}
```

## Acceptance Criteria

- Shared dialog can prevent double-submit.
- Feature screens stop reimplementing loading buttons.

---

## S2-019 — No contract test guaranteeing default empty-state actions are executable

**Severity:** High  
**Files:**
- `DefaultEmptyStateRegistryInitializer.kt`
- missing/insufficient test coverage

## Problem

The registry can contain:
- no-op execute actions,
- string `OpenFeature` IDs with no handler,
- same-screen navigation actions.

There should be a contract test that proves every default action resolves.

## Fix Strategy

Add `EmptyStateRegistryCompletenessTest`.

Test matrix:
- all screen keys have expected actions,
- all action IDs are unique per screen,
- all titles/descriptions are valid string resources,
- no `ExecuteAction { }` default no-op,
- every `OpenFeature` ID resolves through an executor,
- every `NavigateToDestination` is renderable.

---

## S2-020 — Missing focused tests for shared primitives

**Severity:** Medium  
**Files:** test suite

Current visible tests include:
- `ui/components/emptystate` directory
- `ui/util/ClipboardAmountParserTest.kt`

Missing or required:
- `AmountInputSanitizerTest`
- `FormAmountFieldSanitizerContractTest`
- `EnhancedEmptyStateCallbackPolicyTest`
- `LoadingSkeletonSemanticsTest`
- `ColorExtensionsTest`
- `MetricComponentsThemeContractTest`

## Fix Strategy

Prioritize JVM tests first where possible, then minimal Compose UI tests.

---

# Implementation Plan for Agent

## Phase 1 — Amount input consistency

### Files
- `FormComponents.kt`
- `AmountInputSanitizer.kt`
- new `AmountInputSanitizerTest.kt`

### Steps
1. Update `FormAmountField` to call `AmountInputSanitizer.sanitize()`.
2. Add optional `maxFractionDigits`.
3. Improve sanitizer transient behavior for `"0"`, `"0."`, and `".5"`.
4. Add tests for sanitization and validation.

### Commands

```bash
./gradlew :app:testDebugUnitTest --tests "*AmountInputSanitizerTest"
./gradlew :app:compileDebugKotlin
```

---

## Phase 2 — Empty-state action callback correctness

### Files
- `EnhancedEmptyState.kt`
- possibly `EmptyStateAction.kt`

### Steps
1. Add `enabled` and `canDismiss` to private `ActionChip`.
2. Disable or hide chips when click callback is absent.
3. Hide dismiss icon when dismiss callback is absent.
4. Add description semantics using `descriptionRes`.
5. Add Compose tests.

### Acceptance
- No clickable no-op chip.
- No dismiss no-op icon.
- Action description is available to accessibility.

---

## Phase 3 — Registry executable-action cleanup

### Files
- `DefaultEmptyStateRegistryInitializer.kt`
- `EmptyStateAction.kt`
- possible new `EmptyStateActionExecutor.kt`

### Steps
1. Remove `ExecuteAction { }` no-op actions.
2. Replace string `OpenFeature` actions with typed enum or concrete destinations.
3. Implement central executor.
4. Add registry completeness test.

### Acceptance
- Every default action resolves to a real behavior.
- Contract test fails for no-op/unknown action.

---

## Phase 4 — Loading skeleton semantics

### Files
- `LoadingSkeleton.kt`

### Steps
1. Remove per-box content descriptions by default.
2. Add parent-level loading semantics.
3. Use `clearAndSetSemantics {}` for decorative shimmer boxes.
4. Add semantics tests.

### Acceptance
- List skeleton announces loading once.
- Individual placeholder boxes are not focusable/noisy.

---

## Phase 5 — Theme/color cleanup

### Files
- `MetricComponents.kt`
- `LoadingSkeleton.kt`
- `ColorExtensions.kt`
- `ModifierExtensions.kt`
- `Dimens.kt`

### Steps
1. Replace generic `SemanticColors.TextPrimary/TextSecondary` usage with `MaterialTheme.colorScheme`.
2. Make skeleton colors theme-aware.
3. Add explicit color parsing APIs.
4. Replace raw repeated `dp` values with `Dimens`.
5. Clamp/sanitize scale modifier.

---

# Recommended Tests to Add

## `AmountInputSanitizerTest`
Cases:
- strips non-numeric characters,
- enforces one decimal separator,
- enforces fraction limit,
- enforces integer limit,
- supports transient zero input,
- validates positive final amounts.

## `EnhancedEmptyStateCallbackPolicyTest`
Cases:
- primary button disabled if label exists but callback null,
- secondary button disabled if label exists but callback null,
- contextual chip disabled/hidden if click callback null,
- dismiss icon hidden if dismiss callback null,
- descriptionRes included in semantics.

## `EmptyStateRegistryCompletenessTest`
Cases:
- all registered actions have unique IDs per screen,
- no action has missing title/description resource,
- no no-op execute action,
- all `OpenFeature` IDs resolve,
- all `NavigateToDestination` values are renderable.

## `LoadingSkeletonSemanticsTest`
Cases:
- `ListSkeleton` exposes one loading announcement,
- nested `SkeletonBox` nodes do not expose repeated content descriptions.

## `ColorExtensionsTest`
Cases:
- valid `#RRGGBB`,
- valid/invalid alpha policy,
- invalid text returns null/default explicitly,
- named colors rejected by `isValidHexColor`.

---

# Final Severity Table

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S2-001 | High | Unresolved | `FormAmountField` bypasses shared sanitizer |
| S2-002 | Medium | Needs decision | Sanitizer awkward for `"0"` transient input |
| S2-003 | High | Unresolved | Action chips clickable when callback null |
| S2-004 | Medium | Unresolved | Dismiss icon clickable/no-op when callback null |
| S2-005 | Medium | Unresolved | `descriptionRes` unused in chip UI/accessibility |
| S2-006 | High | Unresolved | Default registry contains no-op actions |
| S2-007 | Medium | Unresolved | `OpenFeature(String)` is stringly typed/unverified |
| S2-008 | Medium | Suspected | Budget create CTA likely same-screen no-op |
| S2-009 | Medium | Unresolved | Skeleton semantics too noisy |
| S2-010 | Medium | Unresolved | Metrics use non-theme text colors |
| S2-011 | Medium | Unresolved | Skeleton colors not theme-aware |
| S2-012 | Low/Med | Unresolved | Raw dimensions still common |
| S2-013 | Medium | Unresolved | Invalid colors silently fallback to gray |
| S2-014 | Low/Med | Unresolved | `isValidHexColor()` may accept named colors |
| S2-015 | Low | Unresolved | `toHexString()` ignores alpha/clamping |
| S2-016 | Low | Unresolved | `budgetScale()` accepts unsafe scale |
| S2-017 | Low | Unresolved | Dropdown expansion should guard disabled state |
| S2-018 | Medium | Enhancement | Shared form dialog lacks submitting state |
| S2-019 | High | Test gap | No executable-action registry contract |
| S2-020 | Medium | Test gap | Missing focused primitive tests |

---

# Recommended Immediate Agent Task List

## Task A — Fix amount sanitizer usage
- Update `FormAmountField`.
- Improve `AmountInputSanitizer`.
- Add tests.

## Task B — Fix empty-state action no-op behavior
- Disable/hide chips without callbacks.
- Hide dismiss button without callback.
- Surface `descriptionRes`.

## Task C — Clean default registry actions
- Remove empty lambdas.
- Replace string IDs with typed actions or destinations.
- Add completeness test.

## Task D — Fix skeleton accessibility
- Parent announces loading once.
- Child skeleton boxes are decorative.

## Task E — Theme consistency pass
- Replace generic `SemanticColors` usage in shared primitives.
- Add dark-theme/golden or snapshot tests later.

---

# Source Files Reviewed

- `app/src/main/java/com/yourname/expensetracker/ui/components/common/EmptyState.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/common/EnhancedEmptyState.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/common/ErrorState.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/common/LoadingSkeleton.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/ContextualActionRegistry.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/DefaultEmptyStateRegistryInitializer.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/EmptyStateAction.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/EmptyStatePresentationModule.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/feature/FormComponents.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/feature/MetricComponents.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/util/AmountInputSanitizer.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/util/ColorExtensions.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/util/ModifierExtensions.kt`
- `app/src/main/res/values/strings.xml`
- visible test tree under `app/src/test/java/com/yourname/expensetracker/ui/components`
- visible test tree under `app/src/test/java/com/yourname/expensetracker/ui/util`