# Shared UI Primitives Architecture

## Overview

Global UI components used across all screens. Changes here have high blast radius.

## Theme System

| File | Role |
|------|------|
| `ui/theme/Theme.kt` | Material 3 theme (light/dark/dynamic), typography, status bar |
| `ui/theme/Dimens.kt` | Spacing, touch targets, sizing constants |
| `ui/theme/SemanticColors` | Brand/status colors (budget health, pace, confidence) |

### Color Usage Rules

1. **Global primitives** (EmptyState, ErrorState, LoadingSkeleton) → use `MaterialTheme.colorScheme`
2. **Status indicators** (budget health, pace gauges) → use `SemanticColors` directly
3. **Screen-specific** → prefer `MaterialTheme.colorScheme`, use `SemanticColors` only for status

### Theme Safety

- `ExpenseTrackerTheme` uses `findActivity()` extension (safe in previews/tests)
- Supports light, dark, and dynamic color (Android 12+)
- Typography uses tabular lining figures (`tnum`) for financial numbers

## Empty State Components

| Component | Use Case | Scroll | Actions |
|-----------|----------|--------|---------|
| `EmptyState` | Simple empty screen | ✅ verticalScroll | Primary + secondary button |
| `EnhancedEmptyState` | Empty with contextual chips | ✅ adaptive | Chips from ContextualActionRegistry |
| `ErrorState` | Error with retry | ✅ verticalScroll | Retry + dismiss |
| `InlineErrorBanner` | Inline error card (in `ErrorState.kt`) | N/A | Retry button |

### Button Behavior

- Buttons with `null` callback are **disabled** (not hidden, not active-looking)
- `ErrorState` retry disabled while `isRetrying = true`

## ContextualActionRegistry

Singleton managing empty-state actions per screen.

### Registration Semantics

- `registerActions(screenKey, actions)` **merges** with existing (does not overwrite)
- Duplicate action IDs: later registration wins
- Actions sorted by priority (descending)
- Completion tracked per screen key

### Registered Screens (10)

All `EmptyStateScreenKeys` have registered actions:
- WARRANTY, SUBSCRIPTION, SAVINGS, CHALLENGES, CARBON, LIFESTYLE
- TRANSACTIONS, RECEIPTS, ANALYTICS, BUDGET

### Action Types

| Type | Behavior |
|------|----------|
| `NavigateToDestination(dest)` | Navigate via NavigationController |
| `OpenFeature(featureId)` | Open feature by string ID |
| `ExecuteAction { }` | Run arbitrary lambda |

### Action Data

`EmptyStateAction` uses `@StringRes titleRes` and `@StringRes descriptionRes` for localization. No hardcoded English strings.

## Loading Skeleton

- `SkeletonBox` — single shimmer box
- `ListSkeleton` — list of shimmer rows
- `ChartSkeleton` — chart placeholder
- `DashboardSkeleton` — full dashboard loading state

Uses `SemanticColors.SurfaceLight` (acceptable — loading state is always dark-themed).

## Test Coverage

| Test | What it validates |
|------|------------------|
| `ContextualActionRegistryTest` | Registration, completion, merge, clear |
| `EmptyStateRegistryCompletenessTest` | All screen keys have actions |

## Known Tech Debt

- 12+ screens use hardcoded `Color(0xFF4CAF50)` instead of `SemanticColors.StatusGreen`
- Loading skeleton accessibility is noisy (S2-005) — ✅ FIXED: parent semantics
- Empty-state action strings are hardcoded English (S2-007) — ✅ FIXED: @StringRes
- Form amount input lacks proper money sanitization (S2-008) — ✅ FIXED: AmountInputSanitizer
- `EmptyState` and `EnhancedEmptyState` duplicate layout logic (S2-004) — ✅ FIXED: EmptyState delegates
- Some contextual screen keys lack registered actions — partially addressed
