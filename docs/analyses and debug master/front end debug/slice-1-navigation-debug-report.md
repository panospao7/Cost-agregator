# Slice 1 Debug Report — App Shell, Navigation Core, Deep Links, Feature Routing

Repo target: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Primary files:
- `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/MainViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/navigation/NavigationDestination.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/navigation/NavigationController.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/navigation/FeatureConfig.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/integration/FeatureIntegration.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/AppNavigationBar.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/navigation/NavigationRouteContractTest.kt`

Sources inspected:
- Commit: https://github.com/panospao7/Cost-agregator/commit/18d442c5abb42a8997fd8b6bd04978776c5f6596
- UI map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- Architecture: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/ARCHITECTURE.md
- Segments: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md

Note: I performed static debugging from GitHub source/docs. The agent must still run Gradle locally.

---

## 1. Executive summary

Slice 1 is mostly coherent: the app has a custom destination-driven navigation model using `NavigationDestination`, `NavigationController`, a `CompositionLocal`, six bottom tabs, modal/feature destinations, and route serialization tests.

However, the navigation layer is under-tested and has several drift/behavior risks:

1. **Route inventory drift** between `NavigationDestination.featureDestinations`, `FeatureConfig.allFeatures`, docs, and the `MainActivity` render `when`.
2. **Behavior tests are missing** for back-stack, tab switching, feature chaining, overlay dismissal, and restoration.
3. **`FeatureIntegration` quick-action sections appear non-clickable** despite accepting callback parameters.
4. **Deep links are explicitly marked as unauthenticated in code comments**, which is risky for financial-data screens.
5. **Debug screen still bypasses `NavigationDestination`**, while AI settings/categories were migrated to destination routing.
6. **Navigation state restoration is fragile for parameter/payload destinations**, especially `BudgetForecasting` and `VisualSplitEditor`.
7. **Docs/test coverage do not enforce render coverage** for every destination variant.

Recommended strategy: do not rewrite navigation. Add contracts and small fixes around the existing architecture.

---

## 2. Confirmed architecture facts

From docs/source:

- App uses **destination-driven navigation via `NavigationDestination`**.
- There are **6 shell/bottom-tab destinations**:
  - Home
  - Transactions
  - Review
  - Budget
  - Analytics
  - Spending Map
- Assistant is an overlay/entry surface, not a bottom tab.
- Deep links are handled in `MainActivity.handleIntent()` / `onNewIntent()`.
- Saved navigation state is in `NavigationController`.
- UI map says the root uses:
  - `MainActivity`
  - `ExpenseTrackerTheme`
  - `NavigationController`
  - Material3 `Scaffold`
  - bottom navigation
  - FAB
  - `NavigationDestination` render block.

---

## 3. Existing tests

Current known Slice 1 test:

```bash
./gradlew :app:testDebugUnitTest --tests "*NavigationRouteContractTest" --stacktrace
```

Current `NavigationRouteContractTest` focuses on:

- `NavigationDestination.toSaveToken()`
- `destinationFromSaveToken()`
- simple route round-trips
- parameterized route round-trips
- legacy `visual_split_editor:` token support
- expected route token strings

This is useful but insufficient. It does **not** validate runtime navigation behavior.

Missing test classes should be added:
- `NavigationControllerBehaviorTest`
- `NavigationDestinationCoverageTest`
- `FeatureConfigNavigationContractTest`
- optionally `MainNavigationRequestContractTest`

---

## 4. Issue list

## Issue S1-001 — Route inventory drift

### Severity
High

### Area
`ui/navigation/NavigationDestination.kt`  
`ui/navigation/FeatureConfig.kt`  
`ui/MainActivity.kt`  
docs/reference UI docs

### Evidence
`NavigationDestination` defines many destinations, including:
- main tabs
- overlays
- feature screens
- settings/management screens
- `BudgetForecasting`
- `AiSettings`
- `CategoryManagement`

`FeatureConfig.allFeatures` is a separate hardcoded list of menu-accessible features.

Docs say “22 features” in one section, but source/docs also indicate newer totals around 23/24 config-driven features and 38 ViewModels.

### Problem
There is no contract ensuring:

1. every `FeatureConfig.destination` is renderable by `MainActivity`;
2. every renderable feature has a route token;
3. every destination with a route token can restore safely;
4. docs/source feature counts match;
5. `NavigationDestination.featureDestinations` and `FeatureConfig.allFeatures` intentionally differ.

This is a classic large-app navigation drift problem.

### Fix strategy
Create one canonical route inventory test.

### Implementation plan
Add `FeatureConfigNavigationContractTest.kt`:

Assertions:
- `FeatureConfig.allFeatures.map { it.id }` has unique IDs.
- `FeatureConfig.allFeatures.map { it.destination }` has no duplicate destination unless explicitly allowed.
- every `FeatureConfig.destination.toSaveToken()` restores non-null.
- every `FeatureConfig.destination` is covered by a renderability list.

Since Compose render blocks are hard to introspect, create an explicit production helper:

```kotlin
internal fun NavigationDestination.isRenderedByMainActivity(): Boolean = when (this) {
    is NavigationDestination.Home,
    is NavigationDestination.Transactions,
    is NavigationDestination.Review,
    is NavigationDestination.Budget,
    is NavigationDestination.BudgetDetail,
    is NavigationDestination.Analytics,
    is NavigationDestination.SpendingMap,
    is NavigationDestination.AddExpense,
    is NavigationDestination.ScanReceipt,
    is NavigationDestination.RecurringExpenses,
    is NavigationDestination.ManualRecurringExpense,
    is NavigationDestination.Assistant,
    is NavigationDestination.BudgetForecasting,
    is NavigationDestination.AiSettings,
    is NavigationDestination.CategoryManagement,
    is NavigationDestination.SavingsGoals,
    is NavigationDestination.CarbonFootprint,
    is NavigationDestination.WarrantyTracker,
    is NavigationDestination.PriceProtection,
    is NavigationDestination.BillNegotiation,
    is NavigationDestination.SmartSearch,
    is NavigationDestination.ReceiptMatching,
    is NavigationDestination.InvestmentPortfolio,
    is NavigationDestination.BankConnections,
    is NavigationDestination.BillReminders,
    is NavigationDestination.SpendingChallenges,
    is NavigationDestination.AdvancedAnalytics,
    is NavigationDestination.CashFlowCalendar,
    is NavigationDestination.LifestyleInflation,
    is NavigationDestination.SplitTemplates,
    is NavigationDestination.VisualSplitEditor,
    is NavigationDestination.CurrencyManagement,
    is NavigationDestination.SubscriptionManagement,
    is NavigationDestination.TaxConfiguration,
    is NavigationDestination.ExportOptions,
    is NavigationDestination.BackupRestore,
    is NavigationDestination.SharedExpenseGroups -> true
}
```

Then test:

```kotlin
@Test
fun `all feature config destinations are renderable and serializable`() {
    FeatureConfig.allFeatures.forEach { feature ->
        assertTrue(feature.destination.isRenderedByMainActivity())
        val token = feature.destination.toSaveToken()
        assertNotNull(destinationFromSaveToken(token))
    }
}
```

Acceptance:
- route inventory test fails when a new destination is added but not serialized/rendered.
- docs count can then be corrected from source truth.

---

## Issue S1-002 — NavigationController behavior lacks tests

### Severity
High

### Area
`ui/navigation/NavigationController.kt`

### Evidence
Current contract tests validate serialization only. They do not validate:
- back behavior
- previous main tab behavior
- tab clearing
- feature-to-feature back stack
- overlay dismissal
- invalid tab index fallback
- `canNavigateBack()`

### Problem
The app relies on custom navigation rather than Android Navigation Component. That is fine, but custom routers need strong behavior tests.

### Fix strategy
Add direct JVM tests around `NavigationController`.

### Implementation plan
Add `NavigationControllerBehaviorTest.kt`.

Test cases:

1. **Initial state**
   - initial destination Home
   - `canNavigateBack() == false`
   - current tab index 0

2. **Tab switching**
   - `navigateToTab(1)` => Transactions
   - `canNavigateBack() == true`
   - `navigateBack()` => Home
   - second `navigateBack()` returns false

3. **Feature from Home**
   - current Home
   - `navigateTo(SavingsGoals)`
   - `previousMainTab == 0`
   - `isOnMainTab() == false`
   - `navigateBack()` => Home

4. **Feature from Transactions**
   - `navigateToTab(1)`
   - `navigateTo(ExportOptions)`
   - back returns Transactions, not Home

5. **Feature-to-feature stack**
   - Home -> SavingsGoals -> BackupRestore
   - first back => SavingsGoals
   - second back => Home

6. **Overlay from tab**
   - Transactions -> AddExpense
   - back returns Transactions

7. **Tab switch clears feature stack**
   - Home -> SavingsGoals -> BackupRestore
   - `navigateToTab(4)`
   - back => Home, not SavingsGoals

8. **Invalid tab fallback**
   - `navigateToTab(99)` => Home

9. **Parameterized main destinations**
   - `navigateTo(Analytics("MONTH"))`
   - current tab index 4
   - back goes Home

Acceptance:
- all behavior tests green.
- if semantics are intentionally different, update docs and tests together.

---

## Issue S1-003 — `FeatureIntegration` quick action cards are likely non-clickable

### Severity
Medium

### Area
`ui/integration/FeatureIntegration.kt`

### Evidence
`HomeScreenQuickActions(...)` accepts callbacks:
- `onInvestmentPortfolio`
- `onBankConnections`
- `onBillReminders`
- `onSpendingChallenges`

But the visible `ListItem(...)` blocks do not attach `Modifier.clickable { callback() }`.

Same concern exists in `BudgetScreenActions(...)`: it accepts callbacks but renders static `ListItem`s.

### Problem
The UI appears to show navigation options that cannot be tapped.

### Fix strategy
Make each action row clickable, add semantics role, and add tests if Compose test infra exists.

### Implementation plan
Patch examples:

```kotlin
ListItem(
    headlineContent = { Text(stringResource(R.string.menu_investment_portfolio)) },
    supportingContent = { Text(stringResource(R.string.desc_track_investments)) },
    leadingContent = { Icon(Icons.Default.TrendingUp, null, tint = MaterialTheme.colorScheme.tertiary) },
    modifier = Modifier
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .clickable { onInvestmentPortfolio() },
    colors = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    )
)
```

Do this for each quick action.

Acceptance:
- tapping each quick action invokes the matching callback exactly once.
- add Compose test if feasible:
  - render component
  - click text
  - assert callback flag/count.

---

## Issue S1-004 — Deep links are handled without auth gate

### Severity
High security/privacy risk

### Area
`ui/MainActivity.kt`

### Evidence
`MainActivity.handleIntent()` contains a documented TODO for PRV-16: deep links exported without auth. It handles hosts:
- `home`
- `dashboard`
- `activity`
- `review`
- `plan`
- `add`
- `analytics`
- `map`

### Problem
Financial data screens can be opened by external URI intent without a confirmation/auth checkpoint.

### Fix strategy
Do not block all deep links immediately. Add a sensitive-route confirmation/auth gate.

### Implementation plan
Introduce:

```kotlin
sealed interface DeepLinkDecision {
    data class Allow(val destination: NavigationDestination) : DeepLinkDecision
    data class RequireAuth(val destination: NavigationDestination) : DeepLinkDecision
    data object Reject : DeepLinkDecision
}
```

Create parser separate from side effects:

```kotlin
internal fun parseExpenseTrackerDeepLink(uri: Uri): DeepLinkDecision
```

Sensitive destinations should require auth/confirmation:
- Transactions with `expenseId`
- Review
- Analytics
- Map with location query
- AddExpense
- Budget maybe medium sensitivity

Then in `MainActivity`, if `RequireAuth`, show biometric/device confirmation or in-app confirmation dialog before navigating.

Acceptance:
- parser unit tests cover every supported host.
- unsupported hosts reject or safe-home according to policy.
- sensitive hosts do not navigate before confirmation.
- no DB lookup occurs until route is accepted.

---

## Issue S1-005 — Debug screen bypasses destination router

### Severity
Medium

### Area
`HomeScreen.kt`, `MainActivity.kt`, `NavigationDestination.kt`

### Evidence
Home quick settings migrated AI Settings and Category Management to `NavigationDestination`, but Debug remains a direct dev-only overlay from `HomeScreen`.

### Problem
This is inconsistent with the destination-driven navigation architecture. It also prevents route tests from covering debug navigation.

### Fix strategy
Either:
1. keep Debug explicitly outside router and document it as dev-only local overlay, or
2. add `NavigationDestination.Debug` and render it in `MainActivity` gated by `BuildConfig.DEBUG`.

### Recommended option
Add a destination, but protect it:

```kotlin
data object Debug : NavigationDestination()
```

Render:

```kotlin
is NavigationDestination.Debug -> {
    if (BuildConfig.DEBUG) {
        DebugScreen(onDismiss = { navigation.navigateBack() })
    } else {
        LaunchedEffect(Unit) { navigation.navigateBack() }
    }
}
```

Acceptance:
- Release builds cannot access Debug.
- Debug route token should either:
  - not serialize, or
  - restore to Home in release.
- Add test that Debug is excluded or safely gated.

---

## Issue S1-006 — Payload destinations have fragile restore behavior

### Severity
Medium

### Area
`NavigationDestination.BudgetForecasting`
`NavigationDestination.VisualSplitEditor`
`NavigationController.toSaveToken()`

### Evidence
`BudgetForecasting(val budget: BudgetEntity? = null)` serializes only as `"budget_forecasting"` and intentionally drops the entity. On restore, `MainActivity` sees null budget and navigates back.

`VisualSplitEditor` serializes scalar expense/template fields and drops the in-memory `Expense`.

### Problem
This is partly intentional, but it means some destinations are not truly restorable.

### Fix strategy
Classify each destination as:
- `Restorable`
- `RestorableWithDegradedPayload`
- `Ephemeral`

Add tests documenting which is which.

### Implementation plan
Create helper:

```kotlin
internal enum class DestinationPersistencePolicy {
    FULL,
    DEGRADED,
    EPHEMERAL
}

internal fun NavigationDestination.persistencePolicy(): DestinationPersistencePolicy = when (this) {
    is NavigationDestination.BudgetForecasting -> DestinationPersistencePolicy.DEGRADED
    is NavigationDestination.VisualSplitEditor -> DestinationPersistencePolicy.DEGRADED
    is NavigationDestination.AddExpense,
    is NavigationDestination.ScanReceipt,
    is NavigationDestination.Assistant -> DestinationPersistencePolicy.EPHEMERAL
    else -> DestinationPersistencePolicy.FULL
}
```

Acceptance:
- contract tests enforce known degraded/ephemeral behavior.
- docs mention that `BudgetForecasting` restore returns to Budget when budget payload is absent.

---

## Issue S1-007 — `AppFabMenu` and `SmartFAB` duplication/drift

### Severity
Low to Medium

### Area
`ui/components/AppFabMenu.kt`
`ui/MainActivity.kt`

### Evidence
There is a reusable `AppFabMenu` component with add, scan receipt, and recurring expenses actions. `MainActivity` does not use it; it defines local `SmartFAB`.

### Problem
Duplicated FAB behavior creates drift:
- `AppFabMenu` supports recurring expenses.
- `SmartFAB` supports add, scan, approve all, clipboard amount, and assistant FAB.
- Docs mention FAB feature access, but actual behavior depends on `SmartFAB`.

### Fix strategy
Either:
1. delete/retire `AppFabMenu`, or
2. refactor `SmartFAB` to use/extend `AppFabMenu`.

### Recommended option
Keep `SmartFAB`, document it as app shell FAB, and move it into its own file:

`ui/components/SmartFAB.kt`

Then either remove `AppFabMenu` if unused or mark it legacy.

Acceptance:
- one canonical FAB implementation for shell.
- docs updated to avoid misleading component references.
- tests target `SmartFAB` behavior.

---

## Issue S1-008 — Missing destination render coverage test

### Severity
High for long-term maintainability

### Area
`MainActivity.kt`
`NavigationDestination.kt`

### Problem
The `when (currentDestination)` render block in `MainActivity` is the real route graph. Kotlin exhaustiveness helps when compiling, but it does not protect:
- feature config drift
- test fixture drift
- docs drift
- destinations that serialize but cannot render meaningfully
- destinations that render but are unreachable

### Fix strategy
Create an explicit route metadata table.

### Implementation plan
Add:

```kotlin
data class DestinationMeta(
    val sample: NavigationDestination,
    val kind: DestinationKind,
    val routeTokenExpected: Boolean,
    val featureMenuExpected: Boolean,
    val requiresDebugBuild: Boolean = false
)

enum class DestinationKind {
    MAIN_TAB,
    OVERLAY,
    FEATURE,
    MANAGEMENT,
    DEBUG
}
```

Then define `AllDestinations.kt` in test or main source.

Tests:
- every metadata sample serializes if `routeTokenExpected`.
- every `FeatureConfig` item appears in metadata with `featureMenuExpected`.
- every feature menu destination is not `MAIN_TAB`.
- every main tab maps to index 0–5.
- no duplicate route tokens.

Acceptance:
- adding new destination requires updating metadata/tests.
- route graph becomes AI-agent-friendly.

---

## 5. Recommended execution order for agent

### Step 1 — Baseline compile
Run:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

If compile fails, stop and fix compile before behavior tests.

### Step 2 — Current route test
Run:

```bash
./gradlew :app:testDebugUnitTest --tests "*NavigationRouteContractTest" --stacktrace
```

### Step 3 — Add behavior tests
Create:

```text
app/src/test/java/com/yourname/expensetracker/ui/navigation/NavigationControllerBehaviorTest.kt
```

Implement the cases from S1-002.

### Step 4 — Add feature config contract
Create:

```text
app/src/test/java/com/yourname/expensetracker/ui/navigation/FeatureConfigNavigationContractTest.kt
```

Implement S1-001 checks.

### Step 5 — Patch clickable quick actions
Modify:

```text
app/src/main/java/com/yourname/expensetracker/ui/integration/FeatureIntegration.kt
```

Add click handlers.

### Step 6 — Decide Debug route policy
Either:
- keep as local dev-only overlay and document/test exclusion, or
- add `NavigationDestination.Debug`.

Recommended: add gated destination.

### Step 7 — Deep link hardening
Refactor parsing from execution:
- parser returns decision
- handler executes only allowed/confirmed decisions
- unit tests cover host/query behavior

### Step 8 — Re-run slice tests
Run:

```bash
./gradlew :app:testDebugUnitTest --tests "*Navigation*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*FeatureConfig*" --stacktrace
```

Then:

```bash
./gradlew :app:testDebugUnitTest --stacktrace
```

---

## 6. Suggested new tests

### `NavigationControllerBehaviorTest`

Required tests:
- `initial home has no back`
- `back from non home tab returns home`
- `back from home returns false`
- `feature from home backs to home`
- `feature from transactions backs to transactions`
- `feature to feature uses stack`
- `tab switch clears feature stack`
- `overlay from tab backs to source tab`
- `invalid tab index falls back home`
- `parameterized analytics is tab 4`

### `FeatureConfigNavigationContractTest`

Required tests:
- `feature ids are unique`
- `feature destinations are serializable`
- `feature destinations restore non null`
- `feature destinations are renderable`
- `new feature flags do not duplicate ids`
- `feature menu excludes debug routes`

### `DeepLinkParserTest`

Required tests:
- `home host maps home`
- `dashboard host maps home`
- `activity no expense maps transactions`
- `activity expense id requires auth`
- `review requires auth`
- `add requires auth`
- `analytics period preserved`
- `map location preserved`
- `unsupported host rejects or safe homes according to policy`
- `wrong scheme ignored`

---

## 7. Acceptance criteria for Slice 1 green

Slice 1 is considered stable when:

1. Production Kotlin compiles.
2. Unit test Kotlin compiles.
3. Existing `NavigationRouteContractTest` passes.
4. New `NavigationControllerBehaviorTest` passes.
5. New `FeatureConfigNavigationContractTest` passes.
6. Every `FeatureConfig.destination`:
   - has a token,
   - restores,
   - renders,
   - has a back path.
7. Every bottom tab:
   - maps to exactly one index,
   - selected state syncs,
   - back behavior is tested.
8. Deep links are parsed separately from execution.
9. Sensitive deep links are gated or explicitly documented as accepted risk.
10. Feature quick actions are actually clickable or removed.
11. Debug navigation policy is explicit.
12. Docs are updated only after tests/source behavior are green.

---

## 8. Agent notes

Do not perform a large navigation rewrite.

Preferred style:
- add small contracts,
- expose tiny metadata helpers,
- patch obvious callback omissions,
- preserve existing `NavigationDestination` architecture,
- update docs last.

Avoid:
- replacing the custom router with Navigation Compose in this pass,
- snapshot-testing the whole app,
- broad UI rewrites,
- changing business ViewModels while debugging Slice 1.

Main invariant to protect:

> Every legal destination has exactly one legal render path, one save/restore policy, and predictable back behavior.