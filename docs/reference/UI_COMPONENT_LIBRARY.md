# ExpenseTracker UI Component Library

**Generated:** May 12, 2026 (updated from May 7)  
**Total Components:** 59 across 8 categories + root level  
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
| **RecommendationCard** | `components/RecommendationCard.kt` | AI recommendations display | HomeScreen | Dashboard-only |
| **PlaceInsightCard** | `components/PlaceInsightCard.kt` | Location-based spending insights | HomeScreen, SpendingMapScreen | Dashboard + Map |
| **NearbyShopSuggestionCard** | `components/NearbyShopSuggestionCard.kt` | Nearby store suggestions | SpendingMapScreen | Map-only |
| **NoSpendStreakWidget** | `components/analytics/NoSpendStreakWidget.kt` | Spending streaks counter | HomeScreen | Dashboard-only |

---

## 2. Chart & Visualization Components

*Used across multiple screens for data visualization*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **CategoryDonutChart** | `components/CategoryDonutChart.kt` | Pie/donut spending breakdown | AnalyticsScreen, HomeScreen | Cross-screen |
| **SpendingTrendChart** | `components/SpendingTrendChart.kt` | Line chart of spending over time | AnalyticsScreen | Analytics-only |
| **SpendingPaceGauge** | `components/SpendingPaceGauge.kt` | Gauge chart for budget burn rate | BudgetScreen, HomeScreen | Cross-screen |
| **ChartMarker** | `components/ChartMarker.kt` | Data point marker for charts | AnalyticsScreen | Analytics-only |
| **ForecastTimeline** | `components/ForecastTimeline.kt` | Timeline visualization of forecast | BudgetForecastingScreen | Forecasting-only |
| **MoneyRadarWidget** | `components/dashboard/MoneyRadarWidget.kt` | Radar/spider chart for spending dimensions | HomeScreen | Dashboard-only |
| **PeriodGridView** | `components/PeriodGridView.kt` | Calendar grid for date periods | CashFlowCalendarScreen | Calendar-only |
| **PeriodBlock** | `components/PeriodBlock.kt` | Individual period cell component | CashFlowCalendarScreen | Calendar-only |
| **PeriodNavigationBar** | `components/PeriodNavigationBar.kt` | Period selector with arrows | HomeScreen, AnalyticsScreen | Cross-screen |
| **StatisticalVisualizations** | `components/analytics/StatisticalVisualizations.kt` | Advanced statistical charts | AdvancedAnalyticsScreen | Analytics-only |

---

## 3. AI Components

*Used in AI Assistant sheet and review flows*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **AssistantResultCard** | `components/ai/AssistantResultCard.kt` | AI assistant response display | AssistantSheet | Assistant-only |
| **CategoryAssistCard** | `components/ai/CategoryAssistCard.kt` | AI category suggestion card | ReviewScreen, AssistantSheet | Cross-screen |
| **DedupeAssistCard** | `components/ai/DedupeAssistCard.kt` | Duplicate detection UI | ReviewScreen | Review-only |
| **ReceiptAssistCard** | `components/ai/ReceiptAssistCard.kt` | Receipt scanning results | ReceiptScanScreen | Receipt-only |
| **ReceiptItemBreakdownCard** | `components/ai/ReceiptItemBreakdownCard.kt` | Item-level receipt data | ReceiptScanScreen | Receipt-only |
| **AiChatBubble** | `components/ai/AiChatBubble.kt` | Chat message bubble | AssistantSheet | Assistant-only |
| **AiInsightsCard** | `components/ai/AiInsightsCard.kt` | AI-generated insights display | AssistantSheet, HomeScreen | Cross-screen |
| **AiRecommendationCard** | `components/ai/AiRecommendationCard.kt` | AI recommendation card | HomeScreen | Dashboard-only |
| **AiTypingIndicator** | `components/ai/AiTypingIndicator.kt` | Typing indicator animation | AssistantSheet | Assistant-only |

---

## 4. Common/Shared Components

*Reusable across any screen*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **EmptyState** | `components/common/EmptyState.kt` | Default empty state with icon + message | All screens | Global |
| **EnhancedEmptyState** | `components/common/EnhancedEmptyState.kt` | Rich empty state with CTA button | All screens | Global |
| **ErrorState** | `components/common/ErrorState.kt` | Error display with retry action | All screens | Global |
| **LoadingSkeleton** | `components/common/LoadingSkeleton.kt` | Placeholder shimmer loading | All screens | Global |
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
| **LocationCorrectionSheet** | `components/LocationCorrectionSheet.kt` | Fix location data modal | SpendingMapScreen, TransactionsScreen | Cross-screen |
| **LocationPermissionDialog** | `components/LocationPermissionDialog.kt` | Request location permission | SpendingMapScreen | Map-only |
| **NotificationPermissionDialog** | `components/NotificationPermissionDialog.kt` | Request notification permission | App startup | Startup-only |
| **LocationSearchPicker** | `components/LocationSearchPicker.kt` | Location search/selection | SpendingMapScreen | Map-only |

---

## 6. Navigation Components

*App chrome and navigation infrastructure*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **AppNavigationBar** | `components/AppNavigationBar.kt` | Bottom navigation bar (6 tabs) | MainActivity | Global |
| **AppFabMenu** | `components/AppFabMenu.kt` | Floating action button with submenu | MainActivity | Global |
| **PulseDot** | `components/PulseDot.kt` | Animated service status indicator | HomeScreen | Dashboard-only |
| **TransferDirectionBadge** | `components/TransferDirectionBadge.kt` | Income/expense direction badge | TransactionsScreen | Transactions-only |
| **BentoCard** | `components/BentoCard.kt` | Grid card layout wrapper | HomeScreen | Dashboard-only |

---

## 7. Feature & Support Components

*Feature-specific reusable patterns*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **FeatureComponents** | `components/feature/FeatureComponents.kt` | Reusable feature UI patterns | Multiple feature screens | Cross-feature |
| **FormComponents** | `components/feature/FormComponents.kt` | Form inputs (text, dropdown, etc.) | AddExpenseSheet, multiple screens | Cross-screen |
| **MetricComponents** | `components/feature/MetricComponents.kt` | Metric display components | AnalyticsScreen, BudgetScreen | Cross-screen |
| **FeatureIntegration** | `integration/FeatureIntegration.kt` | Feature routing/integration | HomeScreen | Infrastructure |
| **PersonalityProfileCard** | `components/analytics/PersonalityProfileCard.kt` | Spending personality display | AdvancedAnalyticsScreen | Analytics-only |
| **RetroTopCategoriesCard** | `components/RetroTopCategoriesCard.kt` | Alternative top categories card | HomeScreen | Dashboard-only |

---

## 8. Privacy & Security Components

*Components for privacy-denied states and security messaging*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **PrivacyBlockedCard** | `PrivacyBlockedCard.kt` | Displays lock icon + "Feature disabled" title + reason string for privacy-blocked capabilities | PrivacySettingsScreen, BackupRestoreScreen | Cross-cutting (privacy UI) |

---

## 9. Utility & Form Components

*Reusable utilities and form helpers*

| Component | File | Purpose | Consumers | Reusability |
|-----------|------|---------|-----------|-------------|
| **ColorExtensions** | `util/ColorExtensions.kt` | Color transformations/extensions | Multiple screens | Cross-cutting |
| **HapticFeedback** | `util/HapticFeedback.kt` | Haptic feedback utilities | Multiple screens | Cross-cutting |
| **ModifierExtensions** | `util/ModifierExtensions.kt` | Reusable Compose modifiers | Multiple screens | Cross-cutting |
| **ClipboardAmountParser** | `util/ClipboardAmountParser.kt` | Clipboard amount parsing | AddExpenseSheet | Screen-specific |

---

## 9. Component Usage Heatmap

| Component | # Screens Using | Risk Level |
|-----------|----------------|------------|
| **EmptyState** | ALL (35 packages) | 🔴 CRITICAL — breaking this breaks every screen |
| **EnhancedEmptyState** | 15+ screens | 🔴 HIGH — widely adopted |
| **ErrorState** | ALL (35 packages) | 🔴 CRITICAL — every screen uses it |
| **LoadingSkeleton** | ALL (35 packages) | 🔴 CRITICAL — every screen uses it |
| **AppNavigationBar** | 1 (MainActivity) | 🔴 HIGH — the entire app chrome |
| **AppFabMenu** | 1 (MainActivity) | 🟡 MEDIUM — secondary navigation |
| **PeriodNavigationBar** | 3 (Home, Analytics, Budget) | 🟡 MEDIUM — cross-screen |
| **CategoryDonutChart** | 2 (Analytics, Home) | 🟢 LOW — isolated |
| **SpendingPaceGauge** | 2 (Budget, Home) | 🟢 LOW — isolated |
| **CategoryAssistCard** | 2 (Review, Assistant) | 🟢 LOW — isolated |
| **FormComponents** | 5+ screens | 🟡 MEDIUM — shared form patterns |
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
