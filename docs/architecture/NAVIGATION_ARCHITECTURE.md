# Navigation Architecture

*Last updated: 2026-09-21*

## Overview

The app uses a **custom destination-driven navigation** model (not Jetpack Navigation Component). All routing is managed by `NavigationController` with `NavigationDestination` as the sealed route type.

## Core Components

| Component | File | Role |
|-----------|------|------|
| `NavigationDestination` | `ui/navigation/NavigationDestination.kt` | Sealed class defining all routes (40 destination types) |
| `NavigationController` | `ui/navigation/NavigationController.kt` | State machine: back stack, tab switching, token serialization, navigation events/results |
| `FeatureConfig` | `ui/navigation/FeatureConfig.kt` | Menu-accessible feature registry (`allFeatures`, 24 entries) |
| `DeepLinkParser` | `ui/navigation/DeepLinkParser.kt` | Deep-link URI → `DeepLinkDecision` (Allow / RequireConfirmation / Reject); unit-tested, not yet wired into MainActivity |
| `DestinationPersistencePolicy` | `ui/navigation/DestinationPersistencePolicy.kt` | FULL / DEGRADED / EPHEMERAL restore classification per route |
| `FeatureIntegration` | `ui/integration/FeatureIntegration.kt` | Quick action entry points on screens |
| `MainViewModel` | `ui/MainViewModel.kt` | Emits `MainNavigationRequest` (Tab / Transactions / Destination); Activity collects and applies them |
| `MainActivity` | `ui/MainActivity.kt` | Render block (`when` on destination) + deep-link intake (`handleIntent` / `onNewIntent`) |

## Tab Structure (6 main tabs)

| Index | Destination | Screen |
|-------|-------------|--------|
| 0 | `Home` | Dashboard (`HomeRoute`) |
| 1 | `Transactions` | Transaction list |
| 2 | `Review` | Pending review queue |
| 3 | `Budget` | Budget management |
| 4 | `Analytics` | Analytics charts |
| 5 | `SpendingMap` | Location map |

`BudgetCreate` and `BudgetDetail(categoryId?, categoryName?)` are tab-3 variants: `isMainTab()` treats them as main tabs and `getCurrentTabIndex()` maps them to index 3. `BudgetCreate` opens the Budget screen with its create dialog pre-opened (S2-008R).

## Render Model

- Tabs 0-5 render inside an `AnimatedContent` keyed on `selectedTab`.
- All other destinations render via a `when (currentDestination)` block in `MainScreen`.
- `Assistant` renders the `AssistantSheet` overlay (small FAB above the SmartFAB opens it).
- `Debug` renders only when `BuildConfig.DEBUG`; otherwise the controller navigates back automatically.
- Overlays that were previously boolean flags (`AddExpense`, `ScanReceipt`, `RecurringExpenses`, `ManualRecurringExpense`) are full destinations on the sealed class.

## Navigation Rules

1. **Tab switch** clears the feature back stack.
2. **Feature navigation** from a main tab saves the tab index in `previousMainTab`.
3. **Back from feature** pops the back stack. If empty, returns to `previousMainTab` or Home.
4. **Back from non-Home tab** returns to Home (not app exit).
5. **Back from Home** returns `false` (system handles app exit).
6. **Feature-to-feature** pushes current feature onto back stack.

## Route Serialization

Every destination has a `toSaveToken()` / `destinationFromSaveToken()` pair for state persistence across process death (and configuration changes via `rememberSaveable` in `ProvideNavigationController`). Parameterized destinations encode params as URL query strings; the persisted snapshot holds the current destination token, the back-stack tokens, and `previousMainTab`.

### Persistence Policies

| Policy | Destinations | Behavior on restore |
|--------|-------------|-------------------|
| FULL | Most features | Restores exactly |
| DEGRADED | `BudgetForecasting`, `VisualSplitEditor` | Restores without entity payload |
| EPHEMERAL | `AddExpense`, `ScanReceipt`, `Assistant`, `Debug` | Not persisted (returns to previous) |

## Feature Config

`FeatureConfig.allFeatures` is the canonical list of menu-accessible features (24 entries, rendered by the `FeaturesMenu` composable in `HomeScreen.kt`). Each entry has:

- `id` — unique string identifier
- `destination` — `NavigationDestination` to navigate to
- `titleRes` / `descriptionRes` — display strings
- `icon` / `color` — visual presentation
- `isNew` / `isBeta` — badge flags

> **Note**: `PrivacySettings` **does** have a standalone `NavigationDestination.PrivacySettings` and a `FeatureConfig` entry (`id = "privacy"`); it renders in the standard feature-screen `when` block in `MainActivity`. Two other config entries do not open feature screens: `recurring` targets the `ManualRecurringExpense` overlay and `privacy` targets the `PrivacySettings` management screen. `NavigationDestination.featureDestinations` (27 entries) is the broader list used by the FeaturesMenu wiring and adds `RecurringExpenses`, `AiSettings`, and `CategoryManagement` beyond the `allFeatures` targets (the remaining difference is only naming: config `recurring` targets `ManualRecurringExpense`, config `privacy` targets `PrivacySettings`).

## Test Coverage

| Test | What it validates |
|------|------------------|
| `NavigationControllerBehaviorTest` | Back stack, tab switching, feature navigation, edge cases |
| `FeatureConfigNavigationContractTest` | Route inventory integrity, serialization, no duplicates |
| `NavigationRouteContractTest` | Token round-trip for all destinations |
| `DeepLinkParserTest` | Deep link parsing, security classification, parameter preservation |
| `DestinationPersistencePolicyTest` | Degraded/ephemeral/full persistence documented |

## Invariants

1. Every `NavigationDestination` has exactly one legal render path in `MainActivity`.
2. Every `FeatureConfig.destination` can be serialized and restored.
3. Main tabs are never in `FeatureConfig.allFeatures`.
4. Tab switch always clears the back stack.
5. `navigateBack()` from Home always returns `false`.
6. `DeepLinkParser` classifies sensitive deep-link routes as `RequireConfirmation`, but **MainActivity does not yet enforce this gate** — it navigates directly (PRV-16 TODO documents the missing auth/confirmation step).
7. Debug destination is gated by `BuildConfig.DEBUG` at render time.
8. Unused FAB components are deleted (SmartFAB is canonical).

## Deep Link Security

Scheme: `expensetracker://`

| Host | Decision (per `DeepLinkParser`) | Reason |
|------|----------|--------|
| `home`, `dashboard` | Allow | No sensitive data |
| `activity` (no ID) | Allow | Just opens list |
| `activity?expenseId=X` | RequireConfirmation | Exposes specific transaction |
| `review` | RequireConfirmation | Shows pending financial data |
| `add` | RequireConfirmation | Can create financial records |
| `analytics` | Allow | Aggregate data only |
| `map` | Allow | Aggregate data only |
| `plan` | Allow | Budget overview |
| Unknown | Reject | Safety default |

Implementation status (verified 2026-09-21):

- `DeepLinkParser.kt` returns `DeepLinkDecision` (Allow / RequireConfirmation / Reject) and is covered by `DeepLinkParserTest`, but it is **not called from production code** today.
- `MainActivity.handleIntent` (invoked from both `onCreate` and `onNewIntent`) performs its own inline parsing of the same 8 hosts and navigates immediately — there is currently no confirmation dialog for sensitive routes (see PRV-16 TODO in `MainActivity`).
- Deep links are consumed one-shot: after handling, `intent.data` is cleared so a recreated Activity does not re-apply the same link.
- `activity?expenseId=X`: if the expense exists, Transactions opens filtered to that expense's day (`MainViewModel.navigateToTransactions` with a date-range `TransactionFilter`); otherwise it falls back to `Transactions(initialExpenseId = X)` for highlighting.
- `home?briefingKey=Y` records the opened briefing key via `AiEngagementRepository` (dedup for dashboard briefings).
- Unknown hosts log a warning and fall back to Home (the Activity-side equivalent of `Reject`).
