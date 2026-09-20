# ExpenseTracker ViewModel Injection Reference

**Generated:** June 1, 2026 · **Last updated:** September 21, 2026 (verified against constructors)  
**Total ViewModels:** 41 (40 @HiltViewModel files + 1 inline RecurringExpensesViewModel)  
**Architecture:** Hilt @HiltViewModel with constructor injection

---

## Table of Contents

1. [Main ViewModel](#1-main-viewmodel)
2. [Shell Tab ViewModels (6)](#2-shell-tab-viewmodels)
3. [Overlay ViewModels (5)](#3-overlay-viewmodels)
4. [Feature Screen ViewModels (22)](#4-feature-screen-viewmodels)
5. [Management Screen ViewModels (3)](#5-management-screen-viewmodels)
6. [Debug ViewModels (4)](#6-debug-viewmodels)
7. [Injection Complexity Heatmap](#7-injection-complexity-heatmap)

---

## 1. Main ViewModel

### MainViewModel
**File:** `ui/MainViewModel.kt`
**Injections:** ReviewQueueRepository, RestoreMaintenanceMode
**Complexity:** 🟢 Low (2 dependencies)

---

## 2. Shell Tab ViewModels

### HomeViewModel
**File:** `ui/screens/home/HomeViewModel.kt`
**Injections:** Application, DashboardDataProvider, DashboardRepository, CategoryRepository, PlannedExpenseRepository, DashboardAnalyticsRepository, ExpenseRepository, ComputeDashboardWidgetsUseCase, TotalsAggregationEngine, AdvancedAnalyticsEngine, AiSettingsRepository, AiArtifactRepository, AiEngagementRepository, AiEnvironmentMonitor, WidgetStyleRepository, TimeProvider, RecommendationStateManager, NavigationTargetResolver, RecommendationDismissalHandler, CurrencySettingsRepository
**Complexity:** 🔴 High (20 dependencies)

### TransactionsViewModel
**File:** `ui/screens/transactions/TransactionsViewModel.kt`
**Injections:** NotificationRepository, ExpenseRepository, CategoryRepository, RecurringExpenseRepository, MerchantLocationRepository, TimeProvider, GeocodingService, CurrencySettingsRepository, SourceLinkQueryService
**Complexity:** 🟡 Medium (9 dependencies)

### ReviewViewModel
**File:** `ui/screens/review/ReviewViewModel.kt`
**Injections:** NotificationRepository, ReviewQueueRepository, CategoryRepository, ReceiptRepository, ExpenseRepository, DebugDataStorage, GeocodingService, PrivacyGate, ExplainPendingReviewUseCase, SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase, JudgePendingReviewDuplicateUseCase, AiArtifactRepository, AiSettingsRepository, AiRuntimeDiagnostics, ReceiptLifecycleCoordinator, ReceiptDebugExporter, TimeProvider
**Complexity:** 🔴 High (18 dependencies)

### BudgetViewModel
**File:** `ui/screens/budget/BudgetViewModel.kt`
**Injections:** BudgetRepository, CategoryRepository, SharedExpenseBudgetOffsetEngine, BudgetAutopilotEngine, TimeProvider, CurrencySettingsRepository, AppDatabase
**Complexity:** 🟡 Medium (7 dependencies)

### AnalyticsViewModel
**File:** `ui/screens/analytics/AnalyticsViewModel.kt`
**Injections:** ExpenseRepository, CategoryRepository, BudgetRepository, InsightsEngine, RecurringExpenseEngine, AnalyticsRepository, AdvancedAnalyticsEngine, AnalyticsCurrencyNormalizer, LocationInsightsEngine, AreaSpendingEngine, TravelDetectionEngine, SpendingPersonalityClassifier, TimeProvider, AnalyticsInputAssembler, CurrencyConverter, CurrencySettingsRepository, BudgetVsActualEngine, DailyBucketEngine
**Complexity:** 🔴 High (18 dependencies)

### SpendingMapViewModel
**File:** `ui/screens/map/SpendingMapViewModel.kt`
**Injections:** ExpenseRepository, CategoryRepository, LocationResolver, ForegroundLocationProvider, MerchantLocationRepository, SpendingHeatmapEngine, LocationInsightsEngine, GeocodingService, CurrencySettingsRepository, CurrencyConverter, TimeProvider, PrivacyGate
**Complexity:** 🔴 High (12 dependencies)

---

## 3. Overlay ViewModels

### AddExpenseViewModel
**File:** `ui/screens/addexpense/AddExpenseViewModel.kt`
**Injections:** ManualExpenseRepository, ExpenseRepository, CategoryRepository, TimeProvider, CurrencySettingsRepository
**Complexity:** 🟢 Low (5 dependencies)

### ReceiptScanViewModel
**File:** `ui/screens/receiptscan/ReceiptScanViewModel.kt`
**Injections:** ReceiptRepository, CategoryRepository, CurrencySettingsRepository, AiSettingsRepository, SavedStateHandle, TimeProvider, SuggestReceiptExtractionUseCase, SuggestCategoryFallbackUseCase, CategorizeReceiptItemsUseCase, ReceiptItemCategorizationRepository, AiArtifactRepository, AiRuntimeDiagnostics, ReceiptLifecycleCoordinator, ReceiptParser, TransactionLifecycleCoordinator, ReceiptLinkService, MerchantNormalizer, HybridExpenseClassifier
**Complexity:** 🔴 High (18 dependencies)

### RecurringExpensesViewModel
**File:** `ui/screens/recurring/RecurringExpensesScreen.kt` (defined inline)
**Injections:** FinancialWeatherRepository, RecurringExpenseRepository, PlannedExpenseRepository, RecurringExpenseEngine, ExpenseRepository, CurrencySettingsRepository, TimeProvider
**Complexity:** 🟡 Medium (7 dependencies)

### ManualRecurringExpenseViewModel
**File:** `ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt`
**Injections:** ManualRecurringExpenseRepository, TimeProvider, CurrencySettingsRepository
**Complexity:** 🟡 Medium (3 dependencies)

### AssistantViewModel
**File:** `ui/screens/assistant/AssistantViewModel.kt`
**Injections:** Application, AiSettingsRepository, AiChatRepository, GetAiRuntimeStatusUseCase, InterpretFinancialQueryUseCase, ExecuteFinancialQueryUseCase, MapFinancialQueryToNavigationUseCase, PrivacySettingsRepository, MonotonicTimeProvider
**Complexity:** 🟡 Medium (9 dependencies)

---

## 4. Feature Screen ViewModels

### BudgetForecastingViewModel
**File:** `ui/screens/budget/BudgetForecastingViewModel.kt`
**Injections:** BudgetForecastingEngine, BudgetRecommendationEngine, CurrencySettingsRepository
**Complexity:** 🟢 Low (3 dependencies)

### SavingsGoalsViewModel
**File:** `ui/screens/savings/SavingsGoalsViewModel.kt`
**Injections:** SavingsGoalRepository, SavingsContributionHistoryRepository, SmartSavingsEngine, SavingsGamificationEngine, LifestyleSavingsPromptUseCase, MonthlySavingsSweepUseCase, TimeProvider, CurrencySettingsRepository
**Complexity:** 🟡 Medium (8 dependencies)

### CarbonFootprintViewModel
**File:** `ui/screens/carbon/CarbonFootprintViewModel.kt`
**Injections:** CarbonFootprintCalculator, TimeProvider, CurrencySettingsRepository
**Complexity:** 🟡 Medium (3 dependencies)

### WarrantyTrackerViewModel
**File:** `ui/screens/warranty/WarrantyTrackerViewModel.kt`
**Injections:** WarrantyTrackerRepository, TimeProvider
**Complexity:** 🟢 Low (2 dependencies)

### PriceProtectionViewModel
**File:** `ui/screens/price/PriceProtectionViewModel.kt`
**Injections:** PriceProtectionTracker, CurrencySettingsRepository, @ApplicationContext Context
**Complexity:** 🟡 Medium (3 dependencies)

### BillNegotiationViewModel
**File:** `ui/screens/negotiation/BillNegotiationViewModel.kt`
**Injections:** SmartBillNegotiationEngine, CurrencySettingsRepository
**Complexity:** 🟢 Low (2 dependencies)

### NaturalLanguageSearchViewModel
**File:** `ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt`
**Injections:** NaturalLanguageSearchEngine, CurrencySettingsRepository, CurrencyConverter, SpeechInputGateway
**Complexity:** 🟡 Medium (4 dependencies)

### ReceiptMatchingViewModel
**File:** `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt`
**Injections:** ReceiptRepository, ReceiptTransactionMatcher, ReceiptLinkService, ReceiptMatchLifecycleService
**Complexity:** 🟡 Medium (4 dependencies)

### InvestmentViewModel
**File:** `ui/screens/investment/InvestmentViewModel.kt`
**Injections:** InvestmentTracker, CurrencySettingsRepository
**Complexity:** 🟢 Low (2 dependencies)

### BankConnectionsViewModel
**File:** `ui/screens/bank/BankConnectionsViewModel.kt`
**Injections:** BankConnectionLifecycleCoordinator
**Complexity:** 🟢 Low (1 dependency)

### BillRemindersViewModel
**File:** `ui/screens/reminder/BillRemindersViewModel.kt`
**Injections:** BillReminderManager, CurrencySettingsRepository
**Complexity:** 🟢 Low (2 dependencies)

### SpendingChallengesViewModel
**File:** `ui/screens/challenge/SpendingChallengesViewModel.kt`
**Injections:** SpendingChallengeManager, CategoryRepository, CurrencySettingsRepository
**Complexity:** 🟡 Medium (3 dependencies)

### AdvancedAnalyticsViewModel
**File:** `ui/screens/analytics/AdvancedAnalyticsViewModel.kt`
**Injections:** AdvancedAnalyticsDashboard, CurrencySettingsRepository, TimeProvider
**Complexity:** 🟡 Medium (3 dependencies)

### CashFlowCalendarViewModel
**File:** `ui/screens/cashflow/CashFlowCalendarViewModel.kt`
**Injections:** CashFlowCalculator, TimeProvider, CurrencySettingsRepository
**Complexity:** 🟡 Medium (3 dependencies)

### LifestyleInflationViewModel
**File:** `ui/screens/lifestyle/LifestyleInflationViewModel.kt`
**Injections:** LifestyleInflationDetector, CurrencySettingsRepository
**Complexity:** 🟢 Low (2 dependencies)

### VisualSplitViewModel
**File:** `ui/screens/split/VisualSplitViewModel.kt`
**Injections:** EnhancedSplitManager, Gson
**Complexity:** 🟢 Low (2 dependencies)

### CurrencyManagementViewModel
**File:** `ui/screens/currency/CurrencyManagementViewModel.kt`
**Injections:** CurrencyDataRepository, CurrencyConverter, CurrencyRatesRepository, CurrencySettingsRepository, HybridExpenseClassifier
**Complexity:** 🟡 Medium (5 dependencies)

### SubscriptionManagementViewModel
**File:** `ui/screens/subscription/SubscriptionManagementViewModel.kt`
**Injections:** SubscriptionManagementRepository, TimeProvider, SubscriptionManagerEngine, CurrencySettingsRepository, CurrencyConverter
**Complexity:** 🟡 Medium (5 dependencies)

### TaxConfigurationViewModel
**File:** `ui/screens/tax/TaxConfigurationViewModel.kt`
**Injections:** TaxEstimator, TimeProvider
**Complexity:** 🟢 Low (2 dependencies)

### ExportOptionsViewModel
**File:** `ui/screens/export/ExportOptionsViewModel.kt`
**Injections:** ExportDataRepository, AccountingExportPolicy, TimeProvider, XeroCSVExporter, QuickBooksIIFExporter, FreshBooksExporter, DatabaseReadBarrier, PrivacyGate, @IoDispatcher CoroutineDispatcher
**Complexity:** 🟡 Medium (9 dependencies)

### SharedExpenseGroupsViewModel
**File:** `ui/screens/groups/SharedExpenseGroupsViewModel.kt`
**Injections:** GroupsRepository, AddGroupMemberUseCase, AddGroupExpenseUseCase, DeleteGroupUseCase, ManualExpenseRepository, ExpenseRepository, CurrencySettingsRepository
**Complexity:** 🟡 Medium (7 dependencies)

### BackupRestoreViewModel
**File:** `ui/screens/backup/BackupRestoreViewModel.kt`
**Injections:** @ApplicationContext Context, DatabaseBackupRepository, RestoreMaintenanceMode, TimeProvider
**Complexity:** 🟡 Medium (4 dependencies)

---

## 5. Management Screen ViewModels

### AiSettingsViewModel
**File:** `ui/screens/aisettings/AiSettingsViewModel.kt`
**Injections:** AiSettingsRepository, GetAiRuntimeStatusUseCase, AiRuntimeDiagnostics, SyncProactiveBriefingWorkUseCase, SecureKeyStorage, PrivacyGate, CloudProviderConnectionTester
**Complexity:** 🟡 Medium (7 dependencies)

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
**Injections:** @ApplicationContext Context, NotificationRepository, ReviewQueueRepository, ExpenseRepository, BudgetRepository, CategoryRepository, NotificationSeeder, TimeProvider, ServiceDiagnostics, GetAiRuntimeStatusUseCase, AiSettingsRepository, AiEngagementRepository, AiRuntimeDiagnostics, DatabaseBackupRepository, CsvExpenseImporter, LegacyDataMigrationService
**Complexity:** 🔴 High (16 dependencies)

### CategorizationDebugViewModel
**File:** `ui/screens/debug/CategorizationDebugViewModel.kt`
**Injections:** CategorizationEngine, TimeProvider
**Complexity:** 🟢 Low (2 dependencies)

### SourceLinkDebugViewModel
**File:** `ui/screens/debug/SourceLinkDebugViewModel.kt`
**Injections:** SourceLinkQueryService
**Complexity:** 🟢 Low (1 dependency)

### SourceLinkBackfillViewModel
**File:** `ui/screens/settings/SourceLinkBackfillViewModel.kt`
**Injections:** SourceLinkBackfillWorker
**Complexity:** 🟢 Low (1 dependency)

---

## 7. Injection Complexity Heatmap

### By Dependency Count

| Complexity | # VMs | ViewModels |
|-----------|-------|------------|
| 🔴 High (10+) | 6 | HomeViewModel (20), ReviewViewModel (18), AnalyticsViewModel (18), ReceiptScanViewModel (18), SpendingMapViewModel (12), DebugViewModel (16) |
| 🟡 Medium (3-9) | 21 | TransactionsViewModel (9), BudgetViewModel (7), AddExpenseViewModel (5), RecurringExpensesViewModel (7), ManualRecurringExpenseViewModel (3), AssistantViewModel (9), BudgetForecastingViewModel (3), SavingsGoalsViewModel (8), CarbonFootprintViewModel (3), PriceProtectionViewModel (3), NaturalLanguageSearchViewModel (4), ReceiptMatchingViewModel (4), SpendingChallengesViewModel (3), AdvancedAnalyticsViewModel (3), CashFlowCalendarViewModel (3), CurrencyManagementViewModel (5), SubscriptionManagementViewModel (5), ExportOptionsViewModel (9), SharedExpenseGroupsViewModel (7), BackupRestoreViewModel (4), AiSettingsViewModel (7) |
| 🟢 Low (1-2) | 14 | MainViewModel (2), WarrantyTrackerViewModel (2), BillRemindersViewModel (2), InvestmentViewModel (2), BankConnectionsViewModel (1), LifestyleInflationViewModel (2), VisualSplitViewModel (2), TaxConfigurationViewModel (2), BillNegotiationViewModel (2), CategoryViewModel (1), PrivacySettingsViewModel (1), CategorizationDebugViewModel (2), SourceLinkDebugViewModel (1), SourceLinkBackfillViewModel (1) |

### Most-Injected Dependencies

| Dependency | Used By (# VMs) |
|------------|-----------------|
| **CurrencySettingsRepository** | 24 |
| **TimeProvider** | 20 |
| **CategoryRepository** | 11 |
| **ExpenseRepository** | 9 |
| **AiSettingsRepository** | 6 |

---

## Notes

1. **Direct DAO injection** has been retired from the ViewModels that previously used it: InvestmentViewModel now injects `InvestmentTracker`, BankConnectionsViewModel injects `BankConnectionLifecycleCoordinator`, and VisualSplitViewModel injects `EnhancedSplitManager` (+ `Gson`). Workers/engines own DAO access for these flows.
2. **BackupRestoreViewModel** and **PrivacySettingsViewModel** were added in May 2026 as part of the backup/restore and privacy overhaul pipelines.
3. `RecurringExpensesViewModel` is the one inline `@HiltViewModel`, declared inside `ui/screens/recurring/RecurringExpensesScreen.kt` (all others live in their own files).
4. The complexity classification is: Low = 1-2 deps, Medium = 3-9 deps, High = 10+ deps.

---

**End of ViewModel Injection Reference**
