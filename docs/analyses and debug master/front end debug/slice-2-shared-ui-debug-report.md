# Slice 2 Debug Report — Theme + Shared UI Primitives

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/theme/*`
- `ui/components/common/*`
- `ui/components/emptystate/*`
- `ui/components/feature/FormComponents.kt`
- `ui/util/*`
- shared UI tests

Sources inspected:
- UI component library: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/UI_COMPONENT_LIBRARY.md
- UI map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- `EmptyState.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/common/EmptyState.kt
- `EnhancedEmptyState.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/common/EnhancedEmptyState.kt
- `ErrorState.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/common/ErrorState.kt
- `LoadingSkeleton.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/common/LoadingSkeleton.kt
- `ContextualActionRegistry.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/ContextualActionRegistry.kt
- `DefaultEmptyStateRegistryInitializer.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/DefaultEmptyStateRegistryInitializer.kt
- `EmptyStatePresentationModule.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/emptystate/EmptyStatePresentationModule.kt
- `FormComponents.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/feature/FormComponents.kt
- `Theme.kt`: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/theme/Theme.kt
- Current registry test: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/test/java/com/yourname/expensetracker/ui/components/emptystate/ContextualActionRegistryTest.kt

Note: This is static debugging from GitHub source. Agent must run Gradle locally before patching.

---

## 1. Executive summary

Slice 2 has high blast radius because these primitives are reused by nearly every screen. The docs mark `EmptyState`, `ErrorState`, and `LoadingSkeleton` as critical global components.

The implementation is functional but fragile in these areas:

1. Hardcoded `SemanticColors` are used inside global primitives, bypassing `MaterialTheme.colorScheme`.
2. `EmptyState`, `ErrorState`, and `EnhancedEmptyState` duplicate layout/action behavior and drift from each other.
3. Base `EmptyState` and `ErrorState` are not scroll-adaptive, unlike `EnhancedEmptyState`.
4. Loading skeletons create many repeated accessibility descriptions and have no reduced-motion/test-friendly switch.
5. `ContextualActionRegistry` uses mutable maps without merge/concurrency guarantees.
6. Empty-state actions use hardcoded English strings and untyped string feature IDs.
7. `FormComponents` contain shared validation/localization issues.
8. Test coverage is thin: only registry behavior is tested. The actual Compose global components have no targeted render/semantics tests.

Recommendation: do not rewrite the UI system. Add shared contracts, normalize theme usage, introduce small testable helpers, and add focused Compose tests.

---

## 2. Baseline commands

Agent should start with:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ContextualActionRegistryTest" --stacktrace
```

If Compose UI tests already exist/configured:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Robolectric Compose tests are configured:

```bash
./gradlew :app:testDebugUnitTest --tests "*EmptyState*" --stacktrace
```

Do not start screen debugging until shared primitives compile and render.

---

# 3. Issues

## S2-001 — Shared primitives bypass theme colors

Severity: High  
Files:
- `EmptyState.kt`
- `ErrorState.kt`
- `LoadingSkeleton.kt`
- `Theme.kt`

Evidence:
- `EmptyState` uses `SemanticColors.TextSecondary` and `SemanticColors.PrimaryIndigo`.
- `ErrorState` uses `SemanticColors.WarningOrange`, `DangerRed`, `TextSecondary`, `PrimaryIndigo`.
- `LoadingSkeleton` defaults to `SemanticColors.SurfaceLight` and `TextSecondary`.
- `EnhancedEmptyState` already uses `MaterialTheme.colorScheme`, creating inconsistency.

Problem:
The app supports light/dark Material 3 theme, but global primitives hardcode dark-oriented colors. In light mode, dynamic color mode, screenshots, and accessibility contrast checks can drift badly.

Fix strategy:
Move global UI primitives to `MaterialTheme.colorScheme` and keep `SemanticColors` only as brand/status tokens where absolutely required.

Implementation plan:
1. Add a small theme adapter:

```kotlin
@Composable
fun emptyStateColors(): EmptyStateColors = EmptyStateColors(
    icon = MaterialTheme.colorScheme.onSurfaceVariant,
    title = MaterialTheme.colorScheme.onSurface,
    message = MaterialTheme.colorScheme.onSurfaceVariant,
    primaryButton = MaterialTheme.colorScheme.primary,
    primaryButtonContent = MaterialTheme.colorScheme.onPrimary
)
```

2. Apply to:
   - `EmptyState`
   - `ErrorState`
   - `InlineErrorBanner`
   - `LoadingSkeleton`

3. For error colors use:
   - `MaterialTheme.colorScheme.error`
   - `MaterialTheme.colorScheme.onError`
   - `errorContainer` / `onErrorContainer` if available.

Acceptance:
- Empty/error/loading components render correctly in both light and dark theme.
- No direct `SemanticColors.TextSecondary`, `SurfaceLight`, `PrimaryIndigo`, `DangerRed`, or `WarningOrange` inside common primitives unless justified by a comment.
- Add a static grep guard or test for common primitives.

---

## S2-002 — `EmptyState` can render active-looking buttons with null callbacks

Severity: Medium  
File: `EmptyState.kt`

Evidence:
`actionLabel` controls button visibility, but `onActionClick` is nullable. The button calls `onActionClick?.invoke()`, so it can look clickable while doing nothing.

Same pattern exists in `EnhancedEmptyState` legacy primary/secondary buttons.

Problem:
This creates silent no-op CTAs in global empty states. It is especially dangerous because empty states are used as recovery/navigation surfaces.

Fix strategy:
Make callback requirement explicit.

Implementation options:
1. Backward-compatible:
   - button enabled only when callback is non-null.
   - visually disabled if label exists but callback missing.
2. Stricter:
   - split API into `EmptyStateActionButton(label, onClick)`.
   - deprecate nullable callback params.

Recommended first patch:
```kotlin
enabled = onActionClick != null
```

Do same for secondary and Enhanced legacy buttons.

Acceptance:
- If label exists but callback is missing, button is disabled.
- Tests assert no-op CTA is not enabled.
- Screens should ideally stop passing labels without callbacks.

---

## S2-003 — Base `EmptyState` and `ErrorState` are not scroll/responsive-safe

Severity: High for small screens/bottom sheets  
Files:
- `EmptyState.kt`
- `ErrorState.kt`
- `EnhancedEmptyState.kt`

Evidence:
`EnhancedEmptyState` uses `BoxWithConstraints`, `verticalScroll`, and top-aligns under small height. Base `EmptyState` and `ErrorState` use `Column(... fillMaxSize(), Arrangement.Center)` without scroll.

Problem:
On small devices, landscape, split screen, large font, TalkBack/font scaling, or bottom sheets, base empty/error states can clip content and actions.

Fix strategy:
Extract a shared adaptive layout:

```kotlin
@Composable
private fun StateMessageScaffold(
    modifier: Modifier,
    contentDescription: String,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics { this.contentDescription = contentDescription }
    ) {
        val topAlign = maxHeight < 640.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.Space24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (topAlign) Arrangement.Top else Arrangement.Center,
            content = content
        )
    }
}
```

Apply to:
- `EmptyState`
- `ErrorState`
- `EnhancedEmptyState`

Acceptance:
- components do not clip at 320dp height with actions visible.
- compose test renders in constrained height and finds title + action.

---

## S2-004 — `EmptyState` and `EnhancedEmptyState` duplicate behavior and drift

Severity: Medium/High  
Files:
- `EmptyState.kt`
- `EnhancedEmptyState.kt`

Problem:
There are two separate implementations of:
- icon/title/message layout
- primary/secondary actions
- semantics
- colors
- spacing
- scroll behavior

They already differ:
- `EnhancedEmptyState` uses adaptive scroll and Material colors.
- `EmptyState` uses fixed center layout and hardcoded semantic colors.
- button heights differ: `Dimens.ButtonHeightMedium` vs `heightIn(min = 48.dp)`.

Fix strategy:
Make `EmptyState` a thin wrapper around `EnhancedEmptyState` with no contextual actions, or extract a shared internal composable.

Recommended patch:
- Create `BaseStateMessage.kt` internal shared primitive.
- Keep public APIs to avoid broad refactor.
- Migrate both components to common layout/colors.

Acceptance:
- one source of truth for layout/colors.
- existing call sites compile unchanged.
- tests for both wrappers pass.

---

## S2-005 — Loading skeleton accessibility is noisy and animation is not test/reduced-motion friendly

Severity: Medium  
File: `LoadingSkeleton.kt`

Evidence:
Every `SkeletonBox` sets `contentDescription = R.string.a11y_loading_content`. List/chart skeletons contain many boxes, so screen readers may announce “loading” repeatedly.

Problem:
This degrades accessibility and makes Compose semantics tests noisy. Infinite shimmer also complicates deterministic testing and reduced-motion preferences.

Fix strategy:
1. Add a parent loading semantics container:
   - `semantics { contentDescription = loadingContentDescription }`
2. Make child `SkeletonBox` decorative by default:
   - no semantics, or `clearAndSetSemantics { }`
3. Add `shimmerEnabled: Boolean = true` parameter, default true.
4. In tests pass `shimmerEnabled = false`.

Implementation:
```kotlin
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shimmerEnabled: Boolean = true,
    ...
)
```

Acceptance:
- list skeleton exposes one loading node, not N repeated nodes.
- tests can render skeletons deterministically.
- no visual regression for normal runtime.

---

## S2-006 — `ContextualActionRegistry` overwrites registrations and is not concurrency-safe

Severity: High for modular feature growth  
Files:
- `ContextualActionRegistry.kt`
- `EmptyStatePresentationModule.kt`
- `ContextualActionRegistryTest.kt`

Evidence:
`registerActions(screenKey, actions)` assigns `this.actions[screenKey] = ...`.
The Hilt module collects `Set<EmptyStateRegistryInitializer>` and initializes them in unspecified order.

Problem:
If multiple feature initializers register actions for the same screen key, later registrations overwrite earlier ones. Since Hilt multibinding Set order is not guaranteed, this can become nondeterministic.

Also, the registry uses mutable maps with no synchronization. It is probably used mostly on main thread, but as a singleton it has no hard safety boundary.

Fix strategy:
Change registration semantics from replace to merge, or explicitly name it `replaceActions`.

Recommended:
```kotlin
fun registerActions(screenKey: String, newActions: List<EmptyStateAction>) {
    val merged = (actions[screenKey].orEmpty() + newActions)
        .distinctBy { it.id }
        .sortedByDescending { it.priority }
    actions[screenKey] = merged
}
```

If duplicate IDs should be illegal, throw:
```kotlin
require(merged.size == raw.size) { "Duplicate empty-state action id..." }
```

Acceptance:
- add tests:
  - multiple registrations for one screen merge deterministically.
  - duplicate IDs are rejected or deliberately overwritten by documented rule.
  - completion state survives later registration.
- document thread policy:
  - either `@MainThread`, or guard with `Mutex`/synchronized.

---

## S2-007 — Empty-state actions use hardcoded English strings and untyped feature IDs

Severity: Medium  
Files:
- `DefaultEmptyStateRegistryInitializer.kt`
- `EmptyStateAction.kt`

Evidence:
Initializer creates actions with titles/descriptions like `"Scan Receipt"`, `"Add Manually"`, `"Connect Notifications"`.
Some actions use `OpenFeature("add_warranty")`, `OpenFeature("notification_settings")`, etc.

Problem:
- Not localized.
- String feature IDs are not tied to `FeatureConfig` or `NavigationDestination`.
- Some `ExecuteAction { /* comment */ }` actions are effectively no-op unless external handler special-cases them.
- An empty-state chip may appear but not route anywhere.

Fix strategy:
Introduce typed empty-state actions.

Implementation plan:
1. Replace title/description strings with `UiText` or `@StringRes`:
```kotlin
data class EmptyStateAction(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val action: EmptyStateActionType,
    val priority: Int = 0
)
```

2. Replace arbitrary `OpenFeature(String)` with:
```kotlin
data class OpenFeature(val destination: NavigationDestination)
```
or:
```kotlin
data class OpenFeatureId(val featureId: FeatureId)
```

3. Add executor contract:
```kotlin
interface EmptyStateActionExecutor {
    fun execute(action: EmptyStateActionType)
}
```

Acceptance:
- every default empty-state action has a resolvable route or executor.
- no hardcoded visible English strings in the initializer.
- add contract test that all default actions are executable.

---

## S2-008 — Form amount input is too naive for money

Severity: Medium  
File:
- `FormComponents.kt`

Evidence:
`FormAmountField` filters input to digits and dots, then allows input if there is only one dot.

Problem:
This accepts invalid money states like:
- `.`
- `000000`
- `12.345678`
- localized comma decimal input is stripped
- no explicit max decimals
- no domain `AmountUtils`/money parser connection
- cursor behavior may be poor because the field rewrites input on every change.

Fix strategy:
Create a small testable formatter/parser policy:
```kotlin
object AmountInputSanitizer {
    fun sanitize(raw: String, maxFractionDigits: Int = 2): String
}
```

Then `FormAmountField` calls that.

Acceptance:
- unit tests for amount sanitization.
- supports configured decimal separator if app supports localization.
- max fractional digits enforced.
- does not silently produce a value that domain rejects.

---

## S2-009 — Form dialogs have hardcoded English defaults and raw dp spacing

Severity: Low/Medium  
File:
- `FormComponents.kt`

Evidence:
`FormDialog` defaults:
- `confirmText = "Save"`
- `dismissText = "Cancel"`

It also uses `12.dp` directly in several places instead of `Dimens`.

Problem:
Shared form primitives should be localizable and visually consistent.

Fix strategy:
- Replace defaults with string resources at call site or resource IDs.
- Replace raw `12.dp` with `Dimens.Space12`.

Acceptance:
- no hardcoded visible `"Save"` / `"Cancel"` defaults in shared primitives.
- all shared form spacing uses `Dimens`.

---

## S2-010 — Theme applies window changes via direct Activity cast

Severity: Medium  
File:
- `Theme.kt`

Problem:
`ExpenseTrackerTheme` modifies system bars by casting context to `Activity`. This is common, but fragile in previews/tests/wrapped contexts.

Fix strategy:
Use a safe Activity finder:

```kotlin
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
```

Then:
```kotlin
val activity = view.context.findActivity()
if (!view.isInEditMode && activity != null) {
   SideEffect { ... }
}
```

Acceptance:
- theme renders in previews, Compose tests, and non-standard context wrappers without crash.
- add test or preview smoke if feasible.

---

## S2-011 — Missing Compose tests for global components

Severity: High  
Current tests:
- `ContextualActionRegistryTest.kt` only.

Missing:
- `EmptyStateComponentTest`
- `EnhancedEmptyStateComponentTest`
- `ErrorStateComponentTest`
- `LoadingSkeletonComponentTest`
- `FormComponentsTest`
- `ThemeSmokeTest`

Fix strategy:
Add small semantics/action tests. Do not screenshot-test everything.

Recommended test locations:
- If using instrumentation Compose tests:
  - `app/src/androidTest/java/.../ui/components/common/*`
- If Robolectric Compose is configured:
  - `app/src/test/java/.../ui/components/common/*`

Test cases:

### EmptyState
- renders title and message
- primary button invokes callback
- secondary button invokes callback
- label with null callback is disabled after S2-002
- renders in light/dark theme

### EnhancedEmptyState
- renders contextual chips
- chip click returns correct action id
- dismiss icon invokes dismiss with action id
- constrained height still displays action

### ErrorState
- retryable type with callback shows retry
- non-retryable type hides retry
- retrying state disables retry
- dismiss invokes callback

### LoadingSkeleton
- list skeleton exposes single loading semantics node
- shimmer disabled mode renders

### FormComponents
- amount sanitizer cases
- dropdown selection invokes callback
- date field does not open when disabled
- dialog confirm respects `confirmEnabled`

Acceptance:
- global components have targeted tests before debugging high-level screens.
- tests assert semantics/text/actions, not screenshots.

---

## S2-012 — Documentation numbering/count drift

Severity: Low  
File:
- `docs/reference/UI_COMPONENT_LIBRARY.md`

Evidence:
The table of contents lists section 10 as Component Usage Heatmap, but the actual heading after Utility & Form Components repeats `## 9`.

Fix:
Change second `## 9` to `## 10`.

Acceptance:
- docs headings match TOC.
- update docs after source/tests are green.

---

# 4. Recommended implementation order

## Phase A — Compile and current test baseline

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ContextualActionRegistryTest" --stacktrace
```

If compile fails, stop and fix compile first.

## Phase B — Add tests before behavior changes

Add:
```text
app/src/test/java/.../ui/components/emptystate/ContextualActionRegistryTest.kt
```
Extend it with:
- merge behavior
- duplicate ID policy
- repeated registration
- completion survives registration

Add Compose tests if infra exists:
```text
app/src/androidTest/java/.../ui/components/common/EmptyStateComponentTest.kt
app/src/androidTest/java/.../ui/components/common/ErrorStateComponentTest.kt
app/src/androidTest/java/.../ui/components/common/EnhancedEmptyStateComponentTest.kt
app/src/androidTest/java/.../ui/components/common/LoadingSkeletonComponentTest.kt
app/src/androidTest/java/.../ui/components/feature/FormComponentsTest.kt
```

## Phase C — Low-risk UI patches

1. Disable buttons with null callbacks.
2. Replace raw `12.dp` with `Dimens.Space12`.
3. Localize form defaults or remove defaults.
4. Fix docs numbering.

## Phase D — Theme/color normalization

1. Create shared state-message colors.
2. Update EmptyState/ErrorState/LoadingSkeleton.
3. Add light/dark smoke tests.

## Phase E — Shared layout refactor

1. Extract adaptive state layout.
2. Use in EmptyState/ErrorState/EnhancedEmptyState.
3. Add constrained-height test.

## Phase F — Registry hardening

1. Decide merge vs replace.
2. Implement deterministic registration.
3. Type or validate feature actions.
4. Add executor/resolution test.

## Phase G — Loading skeleton accessibility

1. Parent-level loading semantics.
2. Child skeleton boxes decorative.
3. Add `shimmerEnabled` for tests/reduced motion.

---

# 5. Suggested acceptance checklist for Slice 2 green

Slice 2 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] `ContextualActionRegistryTest` passes and covers registration merge/duplicate policy.
- [ ] Empty/Error/Enhanced empty states have render/action tests.
- [ ] Loading skeleton has semantics test.
- [ ] Form amount sanitizer has pure unit tests.
- [ ] Shared components use `MaterialTheme.colorScheme` instead of hardcoded dark semantic colors.
- [ ] Empty/error states are scroll-safe under constrained height.
- [ ] CTAs cannot be active-looking no-ops.
- [ ] Default empty-state actions are localized or converted to resource-backed text.
- [ ] Default empty-state actions are typed/resolvable.
- [ ] Theme does not crash in non-Activity wrapped contexts.
- [ ] Docs are updated after behavior is verified.

---

# 6. Agent guardrails

Do:
- Keep public APIs backward-compatible where possible.
- Add tests first for current intended behavior.
- Prefer semantics/action tests over screenshots.
- Normalize primitives before debugging screens that consume them.
- Update docs last.

Do not:
- Rewrite all screen empty states in this slice.
- Introduce a new design system framework.
- Change business ViewModels.
- Replace all `SemanticColors` globally; only normalize common primitives first.
- Add broad screenshot tests that will be noisy.

Main invariant:

> Shared UI primitives must render safely, accessibly, and theme-correctly in every screen state before debugging individual screens.