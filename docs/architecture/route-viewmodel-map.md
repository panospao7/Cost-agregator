# Navigation Route ↔ ViewModel Map

> Complete mapping of every `NavigationDestination` to its corresponding ViewModel, screen file, and feature segment.
>
> *Last updated: 2026-06-01*

---

## Main Tabs (6 bottom-navigation destinations)

| Route | Destination Class | ViewModel | Screen File | Segment |
|-------|------------------|-----------|-------------|---------|
| Home | `NavigationDestination.Home` | `HomeViewModel` | `ui/screens/home/HomeScreen.kt` | 10 (Dashboard Totals) |
| Transactions | `NavigationDestination.Transactions(initialExpenseId?)` | `TransactionsViewModel` | `ui/screens/transactions/TransactionsScreen.kt` | 9 (Core Expense) |
| Review | `NavigationDestination.Review` | `ReviewViewModel` | `ui/screens/review/ReviewScreen.kt` | 3 (Notification/Review) |
| Budget | `NavigationDestination.Budget` | `BudgetViewModel` | `ui/screens/budget/BudgetScreen.kt` | 2 (Budget) |
| Analytics | `NavigationDestination.Analytics(initialPeriod?)` | `AnalyticsViewModel` | `ui/screens/analytics/AnalyticsScreen.kt` | 8 (Analytics) |
| Spending Map | `NavigationDestination.SpendingMap(initialLocationQuery?)` | `SpendingMapViewModel` | `ui/screens/map/SpendingMapScreen.kt` | 19 (Location) |

## Detail / Drill-Down Screens

| Route | Destination Class | ViewModel | Screen File | Segment |
|-------|------------------|-----------|-------------|---------|
| Budget Detail | `NavigationDestination.BudgetDetail(categoryId?, categoryName?)` | `BudgetViewModel` | Shared with BudgetScreen | 2 |
| Spending Map | `NavigationDestination.SpendingMap(initialLocationQuery?)` | `SpendingMapViewModel` | `ui/screens/map/SpendingMapScreen.kt` | 19 (Location) |

## Overlay Screens (sheet-style)

| Route | Destination Class | ViewModel | Screen File | Segment |
|-------|------------------|-----------|-------------|---------|
| Add Expense | `NavigationDestination.AddExpense` | `AddExpenseViewModel` | `ui/screens/addexpense/AddExpenseSheet.kt` | 9 (Core Expense) |
| Scan Receipt | `NavigationDestination.ScanReceipt` | `ReceiptScanViewModel` | `ui/screens/receiptscan/ReceiptScanScreen.kt` | 4 (Receipt/OCR) |
| Recurring Expenses | `NavigationDestination.RecurringExpenses` | *(no ViewModel — screen manages state internally)* | `ui/screens/recurring/RecurringExpensesScreen.kt` | 7 (Recurring) |
| Manual Recurring Expense | `NavigationDestination.ManualRecurringExpense` | `ManualRecurringExpenseViewModel` | `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt` | 7 (Recurring) |

## Feature Screens (from FeaturesMenu)

| Route | Destination Class | ViewModel | Screen File | Segment |
|-------|------------------|-----------|-------------|---------|
| Savings Goals | `NavigationDestination.SavingsGoals` | `SavingsGoalsViewModel` | `ui/screens/savings/SavingsGoalsScreen.kt` | 35 (Savings) |
| Carbon Footprint | `NavigationDestination.CarbonFootprint` | `CarbonFootprintViewModel` | `ui/screens/carbon/CarbonFootprintScreen.kt` | 27 (Carbon) |
| Warranty Tracker | `NavigationDestination.WarrantyTracker` | `WarrantyTrackerViewModel` | `ui/screens/warranty/WarrantyTrackerScreen.kt` | 34 (Warranty) |
| Price Protection | `NavigationDestination.PriceProtection` | `PriceProtectionViewModel` | `ui/screens/price/PriceProtectionScreen.kt` | 34 (Offers) |
| Bill Negotiation | `NavigationDestination.BillNegotiation` | `BillNegotiationViewModel` | `ui/screens/negotiation/BillNegotiationScreen.kt` | 34 (Offers) |
| Smart Search | `NavigationDestination.SmartSearch` | `NaturalLanguageSearchViewModel` | `ui/screens/naturallanguage/NaturalLanguageSearchScreen.kt` | 26 (NLP) |
| Receipt Matching | `NavigationDestination.ReceiptMatching` | `ReceiptMatchingViewModel` | `ui/screens/receiptmatching/ReceiptMatchingScreen.kt` | 38 (Receipt Matching) |
| Investment Portfolio | `NavigationDestination.InvestmentPortfolio` | `InvestmentViewModel` | `ui/screens/investment/InvestmentPortfolioScreen.kt` | 15 (Investment) |
| Bank Connections | `NavigationDestination.BankConnections` | `BankConnectionsViewModel` | `ui/screens/bank/BankConnectionsScreen.kt` | 14 (Bank) |
| Bill Reminders | `NavigationDestination.BillReminders` | `BillRemindersViewModel` | `ui/screens/reminder/BillRemindersScreen.kt` | 36 (Bill Reminder) |
| Spending Challenges | `NavigationDestination.SpendingChallenges(showCreateDialog?)` | `SpendingChallengesViewModel` | `ui/screens/challenge/SpendingChallengesScreen.kt` | 37 (Challenge) |
| Advanced Analytics | `NavigationDestination.AdvancedAnalytics` | `AdvancedAnalyticsViewModel` | `ui/screens/analytics/AdvancedAnalyticsScreen.kt` | 8 (Analytics) |
| Cash Flow Calendar | `NavigationDestination.CashFlowCalendar` | `CashFlowCalendarViewModel` | `ui/screens/cashflow/CashFlowCalendarScreen.kt` | 13 (Cash Flow) |
| Lifestyle Inflation | `NavigationDestination.LifestyleInflation` | `LifestyleInflationViewModel` | `ui/screens/lifestyle/LifestyleInflationScreen.kt` | 22 (Lifestyle) |
| Split Templates | `NavigationDestination.SplitTemplates` | `VisualSplitViewModel` | `ui/screens/split/SplitTemplatesScreen.kt` | 21 (Split) |
| Visual Split Editor | `NavigationDestination.VisualSplitEditor(...)` | `VisualSplitViewModel` | `ui/screens/split/VisualSplitEditorScreen.kt` | 21 (Split) |
| Currency Management | `NavigationDestination.CurrencyManagement` | `CurrencyManagementViewModel` | `ui/screens/currency/CurrencyManagementScreen.kt` | 16 (Currency) |
| Subscription Management | `NavigationDestination.SubscriptionManagement` | `SubscriptionManagementViewModel` | `ui/screens/subscription/SubscriptionManagementScreen.kt` | 34 (Subscription) |
| Tax Configuration | `NavigationDestination.TaxConfiguration` | `TaxConfigurationViewModel` | `ui/screens/tax/TaxConfigurationScreen.kt` | 17 (Tax) |
| Export Options | `NavigationDestination.ExportOptions` | `ExportOptionsViewModel` | `ui/screens/export/ExportOptionsScreen.kt` | 18 (Export) |
| Backup/Restore | `NavigationDestination.BackupRestore` | `BackupRestoreViewModel` | `ui/screens/backup/BackupRestoreScreen.kt` | 18 (Export) |
| Shared Expense Groups | `NavigationDestination.SharedExpenseGroups` | `SharedExpenseGroupsViewModel` | `ui/screens/groups/SharedExpenseGroupsScreen.kt` | 24 (Groups) |
| Budget Forecasting | `NavigationDestination.BudgetForecasting(budget?)` | `BudgetForecastingViewModel` | `ui/screens/budget/BudgetForecastingScreen.kt` | 1 (Forecast) |
| Category Management | `NavigationDestination.CategoryManagement` | `CategoryViewModel` | `ui/screens/categories/CategoryScreen.kt` | 6 (Merchant Cat.) |
| AI Settings | `NavigationDestination.AiSettings` | `AiSettingsViewModel` | `ui/screens/aisettings/AiSettingsScreen.kt` | 20 (AI Platform) |
| Assistant | `NavigationDestination.Assistant` | `AssistantViewModel` | `ui/screens/assistant/AssistantSheet.kt` | 20 (AI Platform) |

### Screens with no NavigationDestination route

| Route | Destination Class | ViewModel | Screen File | Segment |
|-------|------------------|-----------|-------------|---------|
| Privacy Settings | *(no NavigationDestination)* | `PrivacySettingsViewModel` | `ui/screens/privacysettings/PrivacySettingsScreen.kt` | 6 (Privacy) |
| Debug | `NavigationDestination.Debug` | `DebugViewModel` | `ui/screens/debug/DebugScreen.kt` | BuildConfig.DEBUG gated |
| Source Link Debug | *(no NavigationDestination)* | `SourceLinkDebugViewModel` | `ui/screens/debug/SourceLinkDebugScreen.kt` | Debug sub-screen |
| Source Link Backfill | *(no NavigationDestination)* | `SourceLinkBackfillViewModel` | `ui/screens/settings/` (no dedicated route) | Settings sub-screen |

---

## Navigation Flow Diagram

```
App Chrome (6 tabs)
  │
  ├── Home ──────────► Overlays: AddExpense, ScanReceipt, RecurringExpenses
  │                       │
  │                       ▼
  │                  ┌── SavingsGoals
  │                  │── CarbonFootprint
  │                  │── WarrantyTracker
  │                  │── PriceProtection
  │                  │── BillNegotiation
  │                  │── SmartSearch
  │                  │── ReceiptMatching
  │                  │── InvestmentPortfolio
  │                  │── BankConnections
  │                  │── BillReminders
  │                  │── SpendingChallenges
  │                  │── AdvancedAnalytics
  │                  │── CashFlowCalendar
  │                  │── LifestyleInflation
  │                  │── SplitTemplates → VisualSplitEditor
  │                  │── CurrencyManagement
  │                  │── SubscriptionManagement
  │                  │── TaxConfiguration
  │                  │── ExportOptions
  │                  │── BackupRestore
  │                  │── SharedExpenseGroups
  │                  └── BudgetForecasting
  │
  ├── Transactions ──► Click expense → VisualSplitEditor
  │
  ├── Review ────────► Approve → AddExpense (pre-filled)
  │
  ├── Budget ────────► Click budget → BudgetDetail → BudgetForecasting
  │
  ├── Analytics ─────► AdvancedAnalytics
  │
  └── Spending Map ──► (no sub-navigation)
```

## Tab Index Mapping

| Index | Tab | NavigationDestination |
|-------|-----|----------------------|
| 0 | Home | `Home` |
| 1 | Transactions | `Transactions(initialExpenseId?)` |
| 2 | Review | `Review` |
| 3 | Budget | `Budget` |
| 4 | Analytics | `Analytics(initialPeriod?)` |
| 5 | Spending Map | `SpendingMap(initialLocationQuery?)` |

---

## ViewModel Instantiation Count

| ViewModel | # Injections | Complexity |
|-----------|-------------|------------|
| `HomeViewModel` | 19+ dependencies | 🔴 High |
| `TransactionsViewModel` | 8 dependencies | 🟡 Medium |
| `ReviewViewModel` | 14+ dependencies | 🔴 High |
| `ReceiptScanViewModel` | 18+ dependencies | 🔴 High |
| `BudgetViewModel` | 7 dependencies | 🟡 Medium |
| `AddExpenseViewModel` | 5 dependencies | 🟢 Low |
| `SavingsGoalsViewModel` | 8 dependencies | 🟡 Medium |
| `AnalyticsViewModel` | 15+ dependencies | 🔴 High |
| Most other VMs | 3-10 dependencies | 🟡 Medium |

**Total ViewModel files:** 40 (39 screen ViewModels + MainViewModel)
