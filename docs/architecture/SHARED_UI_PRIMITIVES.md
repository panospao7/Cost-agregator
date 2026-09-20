# Shared UI Primitives Architecture

**Last Updated:** September 21, 2026

## Overview

Global UI components used across all screens. Changes here have high blast radius.

## Theme System

| File | Role |
|------|------|
| `ui/theme/Theme.kt` | Material 3 theme (light/dark/dynamic), typography, status bar; also hosts the `SemanticColors` object (brand/status colors: budget health, pace, confidence) |
| `ui/theme/Dimens.kt` | Spacing, touch targets, sizing constants |

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
|------|------|
| `NavigateToDestination(dest)` | Navigate via NavigationController |
| `OpenFeature(feature: EmptyStateFeatureAction)` | Open a feature via typed enum (S2-007R — no raw string IDs; executor must handle every enum value: AddWarranty, NotificationSettings, AddSubscription, CreateSavingsGoal, SavingsRecommendations, CreateChallenge, NoSpendStreak, CarbonOffset, IncomeSettings) |
| `ExecuteAction { }` | Run arbitrary lambda |

### Action Data

`EmptyStateAction` uses `@StringRes titleRes` and `@StringRes descriptionRes` for localization. No hardcoded English strings. Each action also carries `id`, `icon`, and a `priority` (higher = more important, sorted descending).

## Loading Skeleton

All defined in `ui/components/common/LoadingSkeleton.kt`:

- `SkeletonBox` — single shimmer box (theme-aware defaults since S2-011)
- `TransactionItemSkeleton` — transaction list row
- `DashboardCardSkeleton` — dashboard card placeholder
- `ChartSkeleton` — chart placeholder
- `ReceiptScanSkeleton` — receipt scanning state
- `ListSkeleton` — list of shimmer rows
- `AIProcessingSkeleton` — AI processing state

A few skeletons still reference `SemanticColors.SurfaceLight` / `SemanticColors.PrimaryIndigo` (ChartSkeleton, AIProcessingSkeleton); `SkeletonBox` defaults are theme-aware.

## Test Coverage

| Test | What it validates |
|------|------------------|
| `ContextualActionRegistryTest` | Registration, completion, merge, clear |
| `EmptyStateRegistryCompletenessTest` | All screen keys have actions |

## Known Tech Debt

- 15 files under `ui/` use hardcoded `Color(0xFF4CAF50)` instead of `SemanticColors.StatusGreen` (re-verified 2026-09-21)
- Loading skeleton accessibility is noisy (S2-005) — ✅ FIXED: parent semantics
- Empty-state action strings are hardcoded English (S2-007) — ✅ FIXED: @StringRes
- Form amount input lacks proper money sanitization (S2-008) — ✅ FIXED: AmountInputSanitizer (`ui/util/AmountInputSanitizer.kt`)
- `EmptyState` and `EnhancedEmptyState` duplicate layout logic (S2-004) — ✅ FIXED: EmptyState delegates to EnhancedEmptyState
- Contextual screen keys missing registered actions — ✅ RESOLVED: all 10 `EmptyStateScreenKeys` are registered by `DefaultEmptyStateRegistryInitializer` and enforced by `EmptyStateRegistryCompletenessTest`
