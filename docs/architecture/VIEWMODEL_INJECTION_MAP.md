# ExpenseTracker ViewModel Injection Reference

**Generated:** May 18, 2026  
**Total ViewModels:** 38 (37 screen ViewModels + MainViewModel)  
**Architecture:** Hilt @HiltViewModel with constructor injection

---

## Table of Contents

1. [Main ViewModel](#1-main-viewmodel)
2. [Shell Tab ViewModels (6)](#2-shell-tab-viewmodels)
3. [Overlay ViewModels (5)](#3-overlay-viewmodels)
4. [Feature Screen ViewModels (23)](#4-feature-screen-viewmodels)
5. [Management Screen ViewModels (3)](#5-management-screen-viewmodels)
6. [Debug ViewModels (2)](#6-debug-viewmodels)
7. [Injection Complexity Heatmap](#7-injection-complexity-heatmap)

---

## 1. Main ViewModel

### MainViewModel
**File:** `ui/MainViewModel.kt`
**Injections:** (typically minimal — app-level state, navigation)
- See source for exact injection list

---

## 2. Shell Tab ViewModels

### HomeViewModel
**File:** `ui/screens/home/HomeViewModel.kt`
**Injections:** DashboardRepository, DashboardDataProvider, CategoryRepository, PlannedExpenseRepository, DashboardAnalyticsRepository, ExpenseRepository, ComputeDashboardWidgetsUseCase, TotalsAggregationEngine, AdvancedAnalyticsEngine, AiSettingsRepository, AiArtifactRepository, AiEngagementRepository, AiEnvironmentMonitor, WidgetStyleRepository, TimeProvider, RecommendationStateManager, NavigationTargetResolver, RecommendationDismissalHandler, CurrencySettingsRepository
**Complexity:** 🔴 High (19 dependencies)

### TransactionsViewModel
**File:** `ui/screens/transactions/TransactionsViewModel.kt`
**Injections:** NotificationRepository, ExpenseRepository, CategoryRepository, RecurringExpenseRepository, MerchantLocationRepository, TimeProvider, GeocodingService, CurrencySettingsRepository
**Complexity:** 🟡 Medium (8 dependencies)

### ReviewViewModel
**File:** `ui/screens/review/ReviewViewModel.kt`
**Injections:** NotificationRepository, ReviewQueueRepository, CategoryRepository, ReceiptRepository, ReceiptLifecycleCoordinator, TransactionLifecycleCoordinator, AiArtifactRepository, AiSettingsRepository, ExplainPendingReviewUseCase, JudgePendingReviewDuplicateUseCase, SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase
**Complexity:** 🔴 High (12+ dependencies)

### BudgetViewModel
**File:** `ui/screens/budget/BudgetViewModel.kt`
**Injections:** BudgetRepository, CategoryRepository, SharedExpenseBudgetOffsetEngine, BudgetAutopilotEngine, TimeProvider, CurrencySettingsRepository, AppDatabase
**Complexity:** 🟡 Medium (7 dependencies)

### AnalyticsViewModel
**File:** `ui/screens/analytics/AnalyticsViewModel.kt`
**Injections:** ExpenseRepository, CategoryRepository, BudgetRepository, InsightsEngine, RecurringExpenseEngine, AnalyticsRepository, AdvancedAnalyticsEngine, AnalyticsCurrencyNormalizer, LocationInsightsEngine, AreaSpendingEngine, TravelDetectionEngine, SpendingPersonalityClassifier, TimeProvider, CurrencyConverter, CurrencySettingsRepository
**Complexity:** 🔴 High (15 dependencies)

### SpendingMapViewModel
**File:** `ui/screens/map/SpendingMapViewModel.kt`
**Injections:** ExpenseRepository, CategoryRepository, LocationResolver
**Complexity:** 🟢 Low (3 dependencies)

---

## 3. Overlay ViewModels

### AddExpenseViewModel
**File:** `ui/screens/addexpense/AddExpenseViewModel.kt`
**Injections:** ManualExpenseRepository, ExpenseRepository, CategoryRepository, TimeProvider, CurrencySettingsRepository
**Complexity:** 🟢 Low (5 dependencies)

### ReceiptScanViewModel
**File:** `ui/screens/receiptscan/ReceiptScanViewModel.kt`
**Injections:** CategoryRepository, ReceiptRepository, ReceiptItemCategorizationRepository, ReceiptLifecycleCoordinator, ReceiptLinkService, TransactionLifecycleCoordinator, CategorizeReceiptItemsUseCase, SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase, AiArtifactRepository, AiSettingsRepository, HybridExpenseClassifier, MerchantNormalizer, ReceiptParser, TimeProvider, CurrencySettingsRepository
**Complexity:** 🔴 High (16+ dependencies)

### RecurringExpensesViewModel
**File:** `ui/screens/recurring/RecurringExpensesScreen.kt` (defined inline)
**Injections:** RecurringExpenseRepository
**Complexity:** 🟢 Low

### ManualRecurringExpenseViewModel
**File:** `ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt`
**Injections:** ManualRecurringExpenseRepository
**Complexity:** 🟢 Low

### AssistantViewModel
**File:** `ui/screens/assistant/AssistantViewModel.kt`
**Injections:** AiChatRepository, QueryInterpretationService
**Complexity:** 🟢 Low (2 dependencies)

---

## 4. Feature Screen ViewModels

### BudgetForecastingViewModel
**File:** `ui/screens/budget/BudgetForecastingViewModel.kt`
**Injections:** See source
**Complexity:** 🟡 Medium

### SavingsGoalsViewModel
**File:** `ui/screens/savings/SavingsGoalsViewModel.kt`
**Injections:** SavingsGoalRepository, SmartSavingsEngine, AutomatedSavingsRuleEngine, SavingsGamificationEngine
**Complexity:** 🟡 Medium (4 dependencies)

### CarbonFootprintViewModel
**File:** `ui/screens/carbon/CarbonFootprintViewModel.kt`
**Injections:** ExpenseRepository, CategoryRepository
**Complexity:** 🟢 Low (2 dependencies)

### WarrantyTrackerViewModel
**File:** `ui/screens/warranty/WarrantyTrackerViewModel.kt`
**Injections:** WarrantyTrackerRepository
**Complexity:** 🟢 Low (1 dependency)

### PriceProtectionViewModel
**File:** `ui/screens/price/PriceProtectionViewModel.kt`
**Injections:** See source
**Complexity:** 🟢 Low

### BillNegotiationViewModel
**File:** `ui/screens/negotiation/BillNegotiationViewModel.kt`
**Injections:** See source
**Complexity:** 🟢 Low

### NaturalLanguageSearchViewModel
**File:** `ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt`
**Injections:** See source
**Complexity:** 🟡 Medium

### ReceiptMatchingViewModel
**File:** `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt`
**Injections:** ReceiptRepository, ExpenseRepository
**Complexity:** 🟢 Low (2 dependencies)

### InvestmentViewModel
**File:** `ui/screens/investment/InvestmentViewModel.kt`
**Injections:** InvestmentDao, InvestmentValueDao
**Complexity:** 🟢 Low (2 dependencies — direct DAO usage)

### BankConnectionsViewModel
**File:** `ui/screens/bank/BankConnectionsViewModel.kt`
**Injections:** BankConnectionDao
**Complexity:** 🟢 Low (1 dependency — direct DAO usage)

### BillRemindersViewModel
**File:** `ui/screens/reminder/BillRemindersViewModel.kt`
**Injections:** BillReminderManager, RecurringLifecycleCoordinator
**Complexity:** 🟢 Low (2 dependencies)

### SpendingChallengesViewModel
**File:** `ui/screens/challenge/SpendingChallengesViewModel.kt`
**Injections:** See source
**Complexity:** 🟡 Medium

### AdvancedAnalyticsViewModel
**File:** `ui/screens/analytics/AdvancedAnalyticsViewModel.kt`
**Injections:** AnalyticsRepository, CategoryRepository
**Complexity:** 🟢 Low (2 dependencies)

### CashFlowCalendarViewModel
**File:** `ui/screens/cashflow/CashFlowCalendarViewModel.kt`
**Injections:** CashFlowCalculator, ExpenseRepository, CategoryRepository
**Complexity:** 🟡 Medium (3 dependencies)

### LifestyleInflationViewModel
**File:** `ui/screens/lifestyle/LifestyleInflationViewModel.kt`
**Injections:** See source
**Complexity:** 🟢 Low

### VisualSplitViewModel
**File:** `ui/screens/split/VisualSplitViewModel.kt`
**Injections:** SplitTemplateDao, SplitItemAssignmentDao
**Complexity:** 🟢 Low (2 dependencies — direct DAO usage)

### CurrencyManagementViewModel
**File:** `ui/screens/currency/CurrencyManagementViewModel.kt`
**Injections:** CurrencySettingsRepository, CurrencyRatesRepository, MultiCurrencyRepository
**Complexity:** 🟡 Medium (3 dependencies)

### SubscriptionManagementViewModel
**File:** `ui/screens/subscription/SubscriptionManagementViewModel.kt`
**Injections:** SubscriptionManagementRepository
**Complexity:** 🟢 Low (1 dependency)

### TaxConfigurationViewModel
**File:** `ui/screens/tax/TaxConfigurationViewModel.kt`
**Injections:** See source
**Complexity:** 🟢 Low

### ExportOptionsViewModel
**File:** `ui/screens/export/ExportOptionsViewModel.kt`
**Injections:** See source
**Complexity:** 🟡 Medium

### SharedExpenseGroupsViewModel
**File:** `ui/screens/groups/SharedExpenseGroupsViewModel.kt`
**Injections:** See source
**Complexity:** 🟡 Medium

### BackupRestoreViewModel
**File:** `ui/screens/backup/BackupRestoreViewModel.kt`
**Injections:** DatabaseBackupRepository
**Complexity:** 🟢 Low (1 dependency)

---

## 5. Management Screen ViewModels

### AiSettingsViewModel
**File:** `ui/screens/aisettings/AiSettingsViewModel.kt`
**Injections:** AiSettingsRepository
**Complexity:** 🟢 Low (1 dependency)

### CategoryViewModel
**File:** `ui/screens/categories/CategoryViewModel.kt`
**Injections:** CategoryRepository
**Complexity:** 🟢 Low (1 dependency)

### PrivacySettingsViewModel
**File:** `ui/screens/privacysettings/PrivacySettingsViewModel.kt`
**Injections:** PrivacySettingsRepository
**Complexity:** 🟢 Low (1 dependency)

---

## 6. Debug ViewModels

### DebugViewModel
**File:** `ui/screens/debug/DebugViewModel.kt`
**Injections:** NotificationRepository, ExpenseRepository, BudgetRepository, CategoryRepository
**Complexity:** 🟡 Medium (4 dependencies)

### CategorizationDebugViewModel
**File:** `ui/screens/debug/CategorizationDebugViewModel.kt`
**Injections:** CategorizationEngine
**Complexity:** 🟢 Low (1 dependency)

---

## 7. Injection Complexity Heatmap

### By Dependency Count

| Complexity | # VMs | ViewModels |
|-----------|-------|------------|
| 🔴 High (10+) | 4 | HomeViewModel, ReviewViewModel, AnalyticsViewModel, ReceiptScanViewModel |
| 🟡 Medium (3-9) | 12 | TransactionsViewModel, BudgetViewModel, SavingsGoalsViewModel, CashFlowCalendarViewModel, CurrencyManagementViewModel, ExportOptionsViewModel, SharedExpenseGroupsViewModel, BudgetForecastingViewModel, NaturalLanguageSearchViewModel, SpendingChallengesViewModel, DebugViewModel, BillNegotiationViewModel |
| 🟢 Low (1-2) | 23 | AddExpenseViewModel, SpendingMapViewModel, AssistantViewModel, ManualRecurringExpenseViewModel, RecurringExpensesViewModel, CarbonFootprintViewModel, WarrantyTrackerViewModel, MainViewModel, etc. |

### Most-Injected Dependencies

| Dependency | Used By (# VMs) |
|------------|-----------------|
| **CategoryRepository** | 10+ |
| **ExpenseRepository** | 8+ |
| **TimeProvider** | 6+ |
| **CurrencySettingsRepository** | 5+ |
| **ReceiptRepository** | 3+ |

---

## Notes

1. **Direct DAO injection** is used by InvestmentViewModel (InvestmentDao, InvestmentValueDao), BankConnectionsViewModel (BankConnectionDao), and VisualSplitViewModel (SplitTemplateDao, SplitItemAssignmentDao). These are grandfathered exceptions to the repository pattern.
2. **BackupRestoreViewModel** and **PrivacySettingsViewModel** are new additions (May 2026) not present in earlier documentation snapshots.
3. ViewModels marked "see source" have their full injection lists documented inline in their source files.
4. The complexity classification is: Low = 1-2 deps, Medium = 3-9 deps, High = 10+ deps.

---

**End of ViewModel Injection Reference**
