# Navigation Architecture

## Overview

The app uses a **custom destination-driven navigation** model (not Jetpack Navigation Component). All routing is managed by `NavigationController` with `NavigationDestination` as the sealed route type.

## Core Components

| Component | File | Role |
|-----------|------|------|
| `NavigationDestination` | `ui/navigation/NavigationDestination.kt` | Sealed class defining all routes |
| `NavigationController` | `ui/navigation/NavigationController.kt` | State machine: back stack, tab switching |
| `FeatureConfig` | `ui/navigation/FeatureConfig.kt` | Menu-accessible feature registry |
| `FeatureIntegration` | `ui/integration/FeatureIntegration.kt` | Quick action entry points on screens |
| `MainActivity` | `ui/MainActivity.kt` | Render block (`when` on destination) |

## Tab Structure (6 main tabs)

| Index | Destination | Screen |
|-------|-------------|--------|
| 0 | `Home` | Dashboard |
| 1 | `Transactions` | Transaction list |
| 2 | `Review` | Pending review queue |
| 3 | `Budget` | Budget management |
| 4 | `Analytics` | Analytics charts |
| 5 | `SpendingMap` | Location map |

## Navigation Rules

1. **Tab switch** clears the feature back stack.
2. **Feature navigation** from a main tab saves the tab index in `previousMainTab`.
3. **Back from feature** pops the back stack. If empty, returns to `previousMainTab` or Home.
4. **Back from non-Home tab** returns to Home (not app exit).
5. **Back from Home** returns `false` (system handles app exit).
6. **Feature-to-feature** pushes current feature onto back stack.

## Route Serialization

Every destination has a `toSaveToken()` / `destinationFromSaveToken()` pair for state persistence across process death. Parameterized destinations encode params as URL query strings.

### Persistence Policies

| Policy | Destinations | Behavior on restore |
|--------|-------------|-------------------|
| FULL | Most features | Restores exactly |
| DEGRADED | `BudgetForecasting`, `VisualSplitEditor` | Restores without entity payload |
| EPHEMERAL | `AddExpense`, `ScanReceipt`, `Assistant` | Not persisted (returns to previous) |

## Feature Config

`FeatureConfig.allFeatures` is the canonical list of menu-accessible features. Each entry has:
- `id` — unique string identifier
- `destination` — `NavigationDestination` to navigate to
- `titleRes` / `descriptionRes` — display strings
- `icon` / `color` — visual presentation
- `isNew` / `isBeta` — badge flags

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
6. Deep links to sensitive routes require user confirmation before navigation.
7. Debug destination is gated by `BuildConfig.DEBUG` at render time.
8. Unused FAB components are deleted (SmartFAB is canonical).

## Deep Link Security

Scheme: `expensetracker://`

| Host | Decision | Reason |
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

Implementation: `DeepLinkParser.kt` returns `DeepLinkDecision` (Allow/RequireConfirmation/Reject).
MainActivity should check the decision and show confirmation dialog for `RequireConfirmation` routes.
