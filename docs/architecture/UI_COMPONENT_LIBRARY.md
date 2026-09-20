# ExpenseTracker UI Component Library

**Last Updated:** September 21, 2026  
**Total Components:** 59 files in `ui/components/` across 9 categories + root level (plus 7 files in `ui/util/` and 1 in `ui/integration/`)  
**Framework:** Jetpack Compose with Material 3

---

## Table of Contents

1. [Dashboard Widgets](#1-dashboard-widgets)
2. [Chart & Visualization Components](#2-chart--visualization-components)
3. [AI Components](#3-ai-components)
4. [Common/Shared Components](#4-commonshared-components)
5. [Dialog/Sheet Components](#5-dialogsheet-components)
6. [Navigation Components](#6-navigation-components)
7. [Feature & Support Components](#7-feature--support-components)
8. [Privacy & Security Components](#8-privacy--security-components)
9. [Utility & Form Components](#9-utility--form-components)
10. [Component Usage Heatmap](#10-component-usage-heatmap)

---

## 1. Dashboard Widgets

*Used on Home Screen (Tab 0) — configurable grid layout*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **TotalsDashboardCard** | `components/TotalsDashboardCard.kt` | Period totals + spending summary | HomeScreen | Dashboard-only |
| **RetroTotalsDashboardCard** | `components/RetroTotalsDashboardCard.kt` | Alternative totals card styling | HomeScreen | Dashboard-only |
| **BudgetBlockPartyCard** | `components/BudgetBlockPartyCard.kt` | Budget overview grid | HomeScreen | Dashboard-only |
| **RetroBudgetBlockPartyCard** | `components/RetroBudgetBlockPartyCard.kt` | Alternative budget card | HomeScreen | Dashboard-only |
| **FinancialWeatherCard** | `components/FinancialWeatherCard.kt` | Health status (sunny/cloudy/stormy) | HomeScreen | Dashboard-only |
| **FinancialRunwayCard** | `components/FinancialRunwayCard.kt` | Months of runway estimation | HomeScreen | Dashboard-only |
| **FinancialStressForecastCard** | `components/FinancialStressForecastCard.kt` | Financial stress indicator | HomeScreen | Dashboard-only |
| **MonteCarloForecastCard** | `components/MonteCarloForecastCard.kt` | Probabilistic forecast visualization | HomeScreen | Dashboard-only |
| **HealthScoreWidget** | `components/health/HealthScoreWidget.kt` | Financial health V1 score | HomeScreen | Dashboard-only |
| **FinancialHealthScoreV2Widget** | `components/health/FinancialHealthScoreV2Widget.kt` | Financial health V2 score | HomeScreen | Dashboard-only |
| **RecommendationCard** | `components/RecommendationCard.kt` | AI recommendations display | HomeScreen, BudgetForecastingScreen, CarbonFootprintScreen, LifestyleInflationScreen | Cross-screen |
| **PlaceInsightCard** | `components/PlaceInsightCard.kt` | Location-based spending insights | SpendingMapScreen, AnalyticsScreen | Dashboard + Map |
| **NearbyShopSuggestionCard** | `components/NearbyShopSuggestionCard.kt` | Nearby store suggestions | SpendingMapScreen | Map-only |
| **NoSpendStreakWidget** | `components/analytics/NoSpendStreakWidget.kt` | Spending streaks counter | HomeScreen | Dashboard-only |
| **DataQualityWarningChip** | `components/DataQualityWarningChip.kt` | Data quality warning chip | HomeScreen, BudgetScreen, FinancialRunwayCard | Cross-screen |

---

## 2. Chart & Visualization Components

*Used across multiple screens for data visualization*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **CategoryDonutChart** | `components/CategoryDonutChart.kt` | Pie/donut spending breakdown | AnalyticsScreen | Analytics-only |
| **SpendingTrendChart** | `components/SpendingTrendChart.kt` | Line chart of spending over time | HomeScreen | Dashboard-only |
| **SpendingPaceGauge** | `components/SpendingPaceGauge.kt` | Gauge chart for budget burn rate | HomeScreen | Dashboard-only |
| **ChartMarker** | `components/ChartMarker.kt` | `rememberMarker()` — Vico chart marker (data-point label) | SpendingTrendChart, ForecastTimeline | Chart-internal |
| **ForecastTimeline** | `components/ForecastTimeline.kt` | Timeline visualization of forecast | FinancialWeatherCard (HomeScreen) | Dashboard-only |
| **MoneyRadarWidget** | `components/dashboard/MoneyRadarWidget.kt` | Radar/spider chart for spending dimensions | HomeScreen | Dashboard-only |
| **PeriodGridView** | `components/PeriodGridView.kt` | Grid of period cells for date ranges | TotalsDashboardCard (HomeScreen) | Dashboard-only |
| **PeriodBlock** | `components/PeriodBlock.kt` | Individual period cell component | PeriodGridView | Dashboard-only |
| **PeriodNavigationBar** | `components/PeriodNavigationBar.kt` | Period selector with arrows | TotalsDashboardCard (HomeScreen) | Dashboard-only |
| **StatisticalVisualizations** | `components/analytics/StatisticalVisualizations.kt` | Advanced statistical charts (PercentileGridCard, TransactionHistogramChart, CategoryPercentileBadge, RichMerchantCard) | AnalyticsScreen | Analytics-only |

---

## 3. AI Components

*Used in AI Assistant sheet and review flows*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **AssistantResultCard** | `components/ai/AssistantResultCard.kt` | AI assistant response display | AssistantSheet | Assistant-only |
| **CategoryAssistCard** | `components/ai/CategoryAssistCard.kt` | AI category suggestion card | ReviewScreen, ReceiptScanScreen | Cross-screen |
| **DedupeAssistCard** | `components/ai/DedupeAssistCard.kt` | Duplicate detection UI | ReviewScreen | Review-only |
| **ReceiptAssistCard** | `components/ai/ReceiptAssistCard.kt` | Receipt scanning results | ReceiptScanScreen, ReviewScreen | Cross-screen |
| **ReceiptItemBreakdownCard** | `components/ai/ReceiptItemBreakdownCard.kt` | Item-level receipt data | ReceiptScanScreen | Receipt-only |
| **AiChatBubble** | `components/ai/AiChatBubble.kt` | Chat message bubble | AssistantSheet | Assistant-only |
| **AiInsightsCard** | `components/ai/AiInsightsCard.kt` | AI-generated insights display | AssistantSheet | Assistant-only |
| **AiRecommendationCard** | `components/ai/AiRecommendationCard.kt` | AI recommendation card | AssistantSheet | Assistant-only |
| **AiTypingIndicator** | `components/ai/AiTypingIndicator.kt` | Typing indicator animation | AssistantSheet | Assistant-only |

---

## 4. Common/Shared Components

*Reusable across any screen*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **EmptyState** | `components/common/EmptyState.kt` | Default empty state with icon + message (delegates to EnhancedEmptyState; also `AnimatedEmptyState`) | Feature screens via `LoadableUiState.Empty` pattern | Global |
| **EnhancedEmptyState** | `components/common/EnhancedEmptyState.kt` | Rich empty state with CTA button + contextual action chips (also `AnimatedEnhancedEmptyState`) | Feature screens via registry actions | Global |
| **ErrorState** | `components/common/ErrorState.kt` | Error display with retry action (also `InlineErrorBanner`, `AnimatedErrorState`) | Feature screens via `LoadableUiState.Error` pattern | Global |
| **LoadingSkeleton** | `components/common/LoadingSkeleton.kt` | Placeholder shimmer loading (7 skeleton variants) | Main tabs (Home, Transactions, Analytics, Budget, Map) | Global |
| **ContextualActionRegistry** | `components/emptystate/ContextualActionRegistry.kt` | Registry for contextual empty state actions | All screens | Global |
| **DefaultEmptyStateRegistryInitializer** | `components/emptystate/DefaultEmptyStateRegistryInitializer.kt` | Bootstrap for empty state registry | App startup | Global |
| **EmptyStateAction** | `components/emptystate/EmptyStateAction.kt` | Action data class for empty state CTAs | All screens | Global |
| **EmptyStatePresentationModule** | `components/emptystate/EmptyStatePresentationModule.kt` | Hilt module wiring for empty states | DI | Global |

---

## 5. Dialog/Sheet Components

*Modal overlays for specific interactions*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **CategoryBreakdownSheet** | `components/CategoryBreakdownSheet.kt` | Modal category spending details | HomeScreen | Dashboard-only |
| **RetroCategoryBreakdownSheet** | `components/RetroCategoryBreakdownSheet.kt` | Alternative category breakdown | HomeScreen | Dashboard-only |
| **LocationCorrectionSheet** | `components/LocationCorrectionSheet.kt` | Fix location data modal | SpendingMapScreen | Map-only |
| **LocationPermissionDialog** | `components/LocationPermissionDialog.kt` | Request location permission | SpendingMapScreen | Map-only |
| **NotificationPermissionDialog** | `components/NotificationPermissionDialog.kt` | Request notification permission | MainActivity | Startup-only |
| **LocationSearchPicker** | `components/LocationSearchPicker.kt` | Location search/selection | SpendingMapScreen, TransactionsScreen (also used by LocationCorrectionSheet) | Cross-screen |

---

## 6. Navigation Components

*App chrome and navigation infrastructure*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **AppNavigationBar** | `components/AppNavigationBar.kt` | Bottom navigation bar (6 tabs) | MainActivity | Global |
| **PulseDot** | `components/PulseDot.kt` | Animated service status indicator | HomeScreen | Dashboard-only |
| **TransferDirectionBadge** | `components/TransferDirectionBadge.kt` | Income/expense direction badge | TransactionsScreen, ReviewScreen | Cross-screen |
| **BentoCard** | `components/BentoCard.kt` | Grid card layout wrapper | HomeScreen, AnalyticsScreen (+ internal use by dashboard/chart components) | Cross-screen |

---

## 7. Feature & Support Components

*Feature-specific reusable patterns*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **FeatureComponents** | `components/feature/FeatureComponents.kt` | Feature scaffolding (FeatureScaffold, LoadingState, feature-level EmptyState/ErrorState, SectionHeader, FeatureCard) | SectionHeader used by AnalyticsScreen, PrivacySettingsScreen; other helpers currently unused | Cross-feature |
| **FormComponents** | `components/feature/FormComponents.kt` | Form inputs (FormTextField, FormAmountField, FormDropdown, FormDateField, FormDialog, FormSection, FormActions) | Currently unused (reserved library code) | Reserved |
| **MetricComponents** | `components/feature/MetricComponents.kt` | Metric display (MetricCard, MetricRow, SummaryTotalCard, AmountComparisonCard, StatusPill) | Currently unused (reserved library code) | Reserved |
| **FeatureIntegration** | `integration/FeatureIntegration.kt` | Feature menu/quick-action integration composables | Currently unreferenced (no imports; cleanup candidate) | Infrastructure |
| **PersonalityProfileCard** | `components/analytics/PersonalityProfileCard.kt` | Spending personality display | AnalyticsScreen | Analytics-only |
| **RetroTopCategoriesCard** | `components/RetroTopCategoriesCard.kt` | Alternative top categories card | HomeScreen | Dashboard-only |

---

## 8. Privacy & Security Components

*Components for privacy-denied states and security messaging*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **PrivacyBlockedCard** | `components/PrivacyBlockedCard.kt` | Typed privacy-blocked card consuming domain `PrivacyBlocked` (capability + reason), semantics content description, `privacy_blocked_card` testTag, optional privacy-settings button with lock icon. | PrivacySettingsScreen, AssistantSheet | Cross-cutting (privacy UI) |

---

## 9. Utility & Form Components

*Reusable utilities and form helpers*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **UiTextExtensions** | `components/UiTextExtensions.kt` | `UiText.asString()` resolution helpers for domain `UiText` | Currently unreferenced (no importers) | Cross-cutting |
| **ColorExtensions** | `util/ColorExtensions.kt` | Color transformations/extensions | Currently unreferenced (no importers) | Cross-cutting |
| **HapticFeedback** | `util/HapticFeedback.kt` | Haptic feedback utilities (`AppHaptics`, `rememberHapticFeedback`) | MainActivity, ReviewScreen | Cross-cutting |
| **ModifierExtensions** | `util/ModifierExtensions.kt` | Reusable Compose modifiers (`budgetScale`, `ifTrue`) | BudgetScreen | Cross-cutting |
| **ClipboardAmountParser** | `util/ClipboardAmountParser.kt` | Clipboard amount parsing | MainActivity (SmartFAB / AddExpense prefill) | Screen-specific |
| **AmountInputSanitizer** | `util/AmountInputSanitizer.kt` | Money input sanitization (digits, single decimal separator, max 2 decimals) — S2-008 fix | AddExpenseViewModel, ReceiptScanViewModel, BudgetScreen, FormComponents | Cross-screen |
| **OwnershipValidator** | `util/OwnershipValidator.kt` | Shared-expense ownership validation for Add/Edit expense paths | AddExpenseViewModel (add), TransactionsViewModel (edit) | Cross-screen |
| **UiTimeUtils** | `util/UiTimeUtils.kt` | Time formatting/conversion helpers (uses `TimeProvider`) | HomeScreen, HomeViewModel | Cross-cutting |

---

## 10. Component Usage Heatmap

Usage counts below are direct call-site references verified against `app/src/main/java` (September 2026). Many feature screens reach these components indirectly through the `LoadableUiState` (Loading/Data/Empty/Error) contract in `ui/model/LoadableUiState.kt` — see `ui/model/RouteContentPattern.kt`.

| Component | # Call Sites | Risk Level |
|-----------|----------------|------------|
| **EmptyState / EnhancedEmptyState** | 8 feature screens direct (+ via `LoadableUiState` pattern) | 🔴 CRITICAL — the standard empty-state contract |
| **ErrorState** | 8 screen files (+ FeatureComponents wrapper) | 🔴 CRITICAL — the standard error contract |
| **LoadingSkeleton** | 5 main-tab screens (Home, Transactions, Analytics, Budget, Map) | 🔴 HIGH — all primary tabs |
| **AppNavigationBar** | 1 (MainActivity) | 🔴 HIGH — the entire app chrome |
| **BentoCard** | 2 screens + 7 dashboard/chart components | 🟡 MEDIUM — shared layout primitive |
| **CategoryAssistCard** | 2 (Review, ReceiptScan) | 🟢 LOW — isolated |
| **RecommendationCard** | 4 (Home, BudgetForecasting, CarbonFootprint, LifestyleInflation) | 🟡 MEDIUM — cross-screen |
| **FormComponents** | 0 (currently unused) | 🟢 LOW — reserved library code |
| **MetricComponents** | 0 (currently unused) | 🟢 LOW — reserved library code |
| **All others** | 1-2 screens | 🟢 LOW — screen-specific |

---

## Quick Reference: Naming Conventions

| Suffix | Meaning | Examples |
|--------|---------|---------|
| `Screen` | Full-screen composable | `HomeScreen`, `TransactionsScreen` |
| `Sheet` | Modal bottom sheet | `AddExpenseSheet`, `AssistantSheet` |
| `Dialog` | Alert/dialog overlay | `LocationPermissionDialog` |
| `Card` | Card-shaped widget | `RecommendationCard`, `TotalsDashboardCard` |
| `Widget` | Dashboard widget | `HealthScoreWidget`, `MoneyRadarWidget` |
| `Component` | Reusable UI pattern | `FeatureComponents`, `FormComponents` |

---

**End of UI Component Library**
