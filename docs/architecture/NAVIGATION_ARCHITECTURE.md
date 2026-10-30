# Navigation Architecture

*Last updated: 2026-10-24 (deep-link section and components: app-lock-privacy-mode 1a); rest verified 2026-09-21*

## Overview

The app uses a **custom destination-driven navigation** model (not Jetpack Navigation Component). All routing is managed by `NavigationController` with `NavigationDestination` as the sealed route type.

## Core Components

| Component | File | Role |
|-----------|------|------|
| `NavigationDestination` | `ui/navigation/NavigationDestination.kt` | Sealed class defining all routes (40 destination types) |
| `NavigationController` | `ui/navigation/NavigationController.kt` | State machine: back stack, tab switching, token serialization, navigation events/results |
| `FeatureConfig` | `ui/navigation/FeatureConfig.kt` | Menu-accessible feature registry (`allFeatures`, 24 entries) |
| `DeepLinkPolicy` | `ui/navigation/DeepLinkParser.kt` (file name kept) | Pure validation of every `expensetracker:` intent → `DeepLinkPolicyResult` (Accepted / Rejected / NotADeepLink); the only parser of the scheme. Wired into `MainActivity.handleIntent` |
| `DeepLinkApplier` | `ui/navigation/DeepLinkApplier.kt` | The only code that acts on a validated link (navigation via `MainViewModel`, the single briefing-opened write, read-only expense lookup, bounded `DL_*` diagnostics) |
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

> **App PIN route (app-lock S27)**: `NavigationDestination.AppPinSettings` (save token `"app_pin_settings"`, mapped in `NavigationController`) opens `AppPinSettingsScreen` from the "App PIN" row of the privacy section (the row is hidden while app lock is off and disabled while it is paused). It is rendered in `MainActivity` inside the app-lock shell, so it is never reachable while the lock gate is closed. The PIN lock surface is not a destination: `AppLockGate` composes `PinLockSurface` for the APP_PIN lock state.

## Test Coverage

| Test | What it validates |
|------|------------------|
| `NavigationControllerBehaviorTest` | Back stack, tab switching, feature navigation, edge cases |
| `FeatureConfigNavigationContractTest` | Route inventory integrity, serialization, no duplicates |
| `NavigationRouteContractTest` | Token round-trip for all destinations |
| `DeepLinkPolicyTest`, `DeepLinkParserTest` | Deep-link validation (hosts, parameters, structural limits, rejection reasons) |
| `DeepLinkApplierTest`, `DeepLinkDiagnosticsSentinelTest` | Applier behaviour per host/rejection; no raw URI / host / briefingKey in diagnostics |
| `DestinationPersistencePolicyTest` | Degraded/ephemeral/full persistence documented |

## Invariants

1. Every `NavigationDestination` has exactly one legal render path in `MainActivity`.
2. Every `FeatureConfig.destination` can be serialized and restored.
3. Main tabs are never in `FeatureConfig.allFeatures`.
4. Tab switch always clears the back stack.
5. `navigateBack()` from Home always returns `false`.
6. Every `expensetracker:` intent (notification taps included) is untrusted: it is validated only by `DeepLinkPolicy` and acted on only by `DeepLinkApplier`. With the lock off (or inert) accepted links apply immediately; since increment 1b (S21-b) `AppLockDeepLinkRouter` asks the app lock controller first and HOLDS a link (in memory only, 5 minutes, newest wins) while the app is locked or an app-lock settings prompt is open, then releases it after unlock or when the prompt ends (PRV-16, AE-118; LEGAL_PATHS "Deep Links").
7. Debug destination is gated by `BuildConfig.DEBUG` at render time.
8. Unused FAB components are deleted (SmartFAB is canonical).

## Deep Link Security

Scheme: `expensetracker://`

| Host | Allowed parameter (all others dropped and counted) | Applier action |
|------|----------|--------|
| `home`, `dashboard` | `briefingKey` = `dashboard_home:` + real `yyyy-MM-dd` | Home; marks the briefing opened only if the key equals the last delivered key |
| `activity` | `expenseId` = 1..Long.MAX_VALUE | Transactions filtered to that expense's day (read-only lookup); not found / read blocked → `Transactions(initialExpenseId = X)`; no valid id → Transactions list |
| `review` | none | Review tab |
| `plan` | none | Budget tab |
| `add` | none | Opens an empty Add Expense sheet (never prefilled) |
| `analytics` | `period` from the shared `TimePeriodTokens` allowlist | Analytics tab |
| `map` | `location` (trimmed, 1..100 chars, no control characters) | Spending map |
| bank hosts | n/a | Rejected (`BANK_HOST`) → Home |
| any other host | n/a | Rejected (`UNKNOWN_HOST`) → Home |

Implementation status (read from source 2026-10-24, app-lock-privacy-mode 1a, S3/S10/S11; device evidence is owed at G1a):

- `MainActivity.handleIntent` (from both `onCreate` and `onNewIntent`) calls `DeepLinkPolicy.evaluate(action, dataString)`; `NotADeepLink` returns false, anything else goes to `AppLockDeepLinkRouter.route(result, lifecycleScope, mainViewModel)` (S21-b), which applies it through `DeepLinkApplier` now or holds it, and returns true. It never parses or logs the link.
- Rejections: a non-VIEW action (`ACTION`) is ignored with no navigation; `MALFORMED` (over 2048 chars, fragment, userinfo, port, a path other than empty or "/", non-ASCII authority), `UNKNOWN_HOST` and `BANK_HOST` fall back to Home. Diagnostics are bounded `DL_*` codes launched on the application scope (never awaited, so they cannot delay navigation); no raw URI, host string or parameter value is recorded.
- Deep links are consumed one-shot: when `handleIntent` returns true, `intent.data` is cleared so a recreated Activity does not re-apply the same link.
- `home?briefingKey=Y`: the key is written as `lastOpenedDashboardBriefingKey` (via `MarkDashboardBriefingOpenedUseCase`) only when it equals the persisted last delivered key, read at apply time. A link from another app or a web page cannot change which briefing is shown (INV-16).
- The manifest VIEW filter for the scheme has `DEFAULT` and no `BROWSABLE` category, so web-browser links no longer open the app (INV-20, OD-3a). The manifest is not the security boundary: the exported launcher activity accepts any URI, so `DeepLinkPolicy` validates everything, notification taps included.
- Increment 1b adds the held-link gate behind authentication (PRV-16).
