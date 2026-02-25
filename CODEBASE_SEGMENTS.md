# ExpenseTracker Codebase Segmentation Guide

**Purpose:** Break down the codebase into logical feature segments for targeted AI analysis and bug hunting.

> **For overall architecture understanding, see [ARCHITECTURE.md](./ARCHITECTURE.md)**

---

## FILES COVERED: 152 Total Kotlin Files

| Segment | Files | Description |
|---------|-------|-------------|
| 1 | ~15 | Financial Forecast/Weather |
| 2 | ~8 | Budget Management |
| 3 | ~20 | Notification Parsing |
| 4 | ~8 | Receipt Scanning (OCR) |
| 5 | ~15 | Merchant Categorization |
| 6 | ~5 | Recurring Expenses |
| 7 | ~15 | Analytics & Insights |
| 8 | ~20 | Core Expense Management |
| 9 | ~10 | Dashboard & Widgets |
| 10 | ~3 | Notifications |
| 11 | ~8 | Debug & Diagnostics |
| 12 | ~3 | Dependency Injection |
| 13 | ~20 | Utilities |

---

## How to Use This Guide

When analyzing a specific feature, check files in this order:
1. **UI Layer** (Screens/ViewModels)
2. **Domain Layer** (Engines/Logic/Models)
3. **Data Layer** (Repositories)
4. **Database Layer** (DAOs/Entities)

---

## SEGMENT 1: FINANCIAL FORECAST / WEATHER

**Description:** Core forecasting engine that predicts month-end spending and generates financial "weather" narratives.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/home/HomeViewModel.kt` | Main VM that uses FinancialWeatherRepository |
| `ui/screens/home/HomeScreen.kt` | Displays FinancialRunwayCard, FinancialWeatherCard |
| `ui/components/FinancialRunwayCard.kt` | Shows days until money runs out |
| `ui/components/FinancialWeatherCard.kt` | Shows weather narrative (clear, cloudy, stormy) |
| `ui/components/ForecastTimeline.kt` | Visual timeline of projected spending |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/logic/SynthesisEngine.kt` | **MAIN ENGINE** - Synthesizes forecasts from budgets, recurring expenses |
| `domain/logic/NarrativeGenerator.kt` | Generates human-readable weather narratives |
| `domain/analytics/InsightsEngine.kt` | Provides spending pace and insights for forecast |
| `domain/model/FinancialForecast.kt` | Forecast data models |
| `domain/model/Result.kt` | Result wrapper types |
| `domain/model/PeriodRange.kt` | Date period range model |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/FinancialWeatherRepository.kt` | **MAIN REPO** - Coordinates forecast data fetching |
| `data/repository/BudgetRepository.kt` | Budget data for forecast calculations |
| `data/repository/RecurringExpenseRepository.kt` | Recurring expenses for committed costs |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/dao/ExpenseDao.kt` | Query expenses for forecasting |
| `data/database/dao/BudgetDao.kt` | Budget queries |

### Shared/Utility
| File | Purpose |
|------|---------|
| `domain/util/TimePeriodUtils.kt` | Date range calculations |
| `domain/util/StatisticsUtils.kt` | Statistics calculations |

---

## SEGMENT 2: BUDGET MANAGEMENT

**Description:** All budget-related functionality including creation, tracking, rollover, and notifications.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/budget/BudgetScreen.kt` | Budget list and management UI |
| `ui/screens/budget/BudgetViewModel.kt` | Budget CRUD operations |
| `ui/components/BudgetBlockPartyCard.kt` | "Block Party" feature - budget burning visualization |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/budget/BudgetCalculator.kt` | **MAIN ENGINE** - Calculates budget periods, rollover amounts |
| `domain/budget/BudgetMonitor.kt` | **MAIN ENGINE** - Monitors spending vs budget, sends notifications |
| `domain/budget/BudgetModels.kt` | Budget-related models (BudgetStatus, BudgetHealthStatus) |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/BudgetRepository.kt` | **MAIN REPO** - Budget CRUD, rollover calculations |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/entity/Budget.kt` | Budget entity |
| `data/database/dao/BudgetDao.kt` | Budget DAO |

---

## SEGMENT 3: TRANSACTION NOTIFICATION PARSING

**Description:** Captures bank/Payment notifications and parses them into structured transactions.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/review/ReviewScreen.kt` | Review pending transactions |
| `ui/screens/review/ReviewViewModel.kt` | Approve/reject transactions |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/parser/AppParserRegistry.kt` | Routes notifications to appropriate parser |
| `domain/parser/GenericTransactionParser.kt` | Fallback parser for generic notifications |
| `domain/parser/parsers/GreekBankParser.kt` | Greek bank notifications (NBG, Alpha, Eurobank, Piraeus) |
| `domain/parser/parsers/RevolutParser.kt` | Revolut app notifications |
| `domain/parser/parsers/GoogleWalletParser.kt` | Google Wallet notifications |
| `domain/parser/parsers/SmsParser.kt` | SMS-based bank notifications |
| `domain/intelligence/ConfidenceRouter.kt` | Routes transactions based on confidence scoring |
| `domain/intelligence/TransactionClassifier.kt` | ML classifier for transaction detection |
| `domain/service/NotificationService.kt` | Notification sending interface |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/NotificationRepository.kt` | **MAIN REPO** - Processes and stores notifications |
| `data/repository/ReviewQueueRepository.kt` | Review queue management |
| `data/repository/SourceStatsRepository.kt` | Tracks parser performance stats |
| `data/repository/UserCorrectionRepository.kt` | User corrections for ML learning |
| `data/repository/MerchantRulesRepository.kt` | Merchant rules storage |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/entity/RawNotification.kt` | Raw notification storage |
| `data/database/entity/PendingReview.kt` | Pending review items |
| `data/database/entity/PendingReviewWithReceipt.kt` | Pending review with receipt model |
| `data/database/entity/SourceStats.kt` | Parser performance stats |
| `data/database/entity/BlockedPackage.kt` | Blocked spam packages |
| `data/database/dao/RawNotificationDao.kt` | Raw notification queries |
| `data/database/dao/PendingReviewDao.kt` | Review queue queries |
| `data/database/dao/SourceStatsDao.kt` | Stats queries |
| `data/database/dao/BlockedPackageDao.kt` | Blocked package queries |

### Services/Receivers
| File | Purpose |
|------|---------|
| `service/NotificationCaptureService.kt` | **MAIN** - Android NotificationListenerService |
| `receiver/ServiceRestartReceiver.kt` | Restarts notification service |
| `receiver/BootReceiver.kt` | Starts service on device boot |

---

## SEGMENT 4: RECEIPT SCANNING (OCR)

**Description:** OCR-based receipt scanning to extract transaction details.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/receiptscan/ReceiptScanScreen.kt` | Camera/gallery receipt capture |
| `ui/screens/receiptscan/ReceiptScanViewModel.kt` | OCR processing coordination |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/receipt/ReceiptOcrService.kt` | **MAIN ENGINE** - ML Kit OCR processing |
| `domain/receipt/ReceiptParser.kt` | Parses OCR text into structured data |
| `domain/receipt/BankStatementParser.kt` | Parses bank statement images |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/ReceiptRepository.kt` | **MAIN REPO** - Receipt storage and processing |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/entity/ScannedReceipt.kt` | Scanned receipt entity |
| `data/database/dao/ScannedReceiptDao.kt` | Receipt queries |

---

## SEGMENT 5: MERCHANT CATEGORIZATION

**Description:** Automatically categorizes transactions based on merchant names using rules and ML.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/categories/CategoryScreen.kt` | Category management UI |
| `ui/screens/categories/CategoryViewModel.kt` | Category operations |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/categorization/CategorizationEngine.kt` | **MAIN ENGINE** - Merchant to category mapping |
| `domain/intelligence/ml/MerchantNormalizer.kt` | Normalizes merchant names using BK-tree |
| `domain/intelligence/ml/HybridExpenseClassifier.kt` | ML-based category prediction |
| `domain/intelligence/ml/ExpenseCategoryClassifier.kt` | Naive Bayes category classifier |
| `domain/intelligence/ml/ExpenseClassifier.kt` | Base expense classifier |
| `domain/intelligence/ml/FeatureExtractor.kt` | Feature extraction for ML |
| `domain/util/MerchantCleaner.kt` | Cleans merchant name strings |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/CategoryRepository.kt` | **MAIN REPO** - Category CRUD |
| `data/repository/MerchantCategoryRepository.kt` | Merchant-category mappings |
| `data/repository/MerchantNormalizationRepository.kt` | Merchant canonical storage |
| `data/repository/MerchantRulesRepository.kt` | Merchant rules storage |
| `data/provider/MerchantCategoryProvider.kt` | Pre-defined merchant categories |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/entity/Category.kt` | Category entity |
| `data/database/entity/MerchantCategory.kt` | Merchant-category mapping |
| `data/database/entity/MerchantAlias.kt` | Merchant aliases |
| `data/database/entity/MerchantCanonical.kt` | Canonical merchant names |
| `data/database/entity/UserCorrection.kt` | User corrections for learning |
| `data/database/dao/CategoryDao.kt` | Category queries |
| `data/database/dao/MerchantCategoryDao.kt` | Merchant category queries |
| `data/database/dao/MerchantNormalizationDao.kt` | Merchant normalization queries |
| `data/database/dao/UserCorrectionDao.kt` | Correction queries |

---

## SEGMENT 6: RECURRING EXPENSES

**Description:** Detects and manages recurring expenses (subscriptions, bills).

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/recurring/RecurringExpensesScreen.kt` | Recurring expense list UI |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/logic/RecurringExpenseEngine.kt` | **MAIN ENGINE** - Detects recurring patterns |
| `domain/model/RecurringPattern.kt` | Recurring pattern model |
| `domain/model/UpcomingItem.kt` | Upcoming expense item model |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/RecurringExpenseRepository.kt` | **MAIN REPO** - Recurring expense CRUD |
| `data/repository/PlannedExpenseRepository.kt` | Planned/future expenses |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/entity/ManualRecurringExpense.kt` | Manual recurring expense entity |
| `data/database/dao/RecurringExpenseDao.kt` | Recurring expense queries |
| `data/database/dao/PlannedExpenseDao.kt` | Planned expense queries |

---

## SEGMENT 7: ANALYTICS & INSIGHTS

**Description:** Advanced analytics, spending patterns, merchant insights.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/analytics/AnalyticsScreen.kt` | Basic analytics UI |
| `ui/screens/analytics/AnalyticsViewModel.kt` | Analytics data preparation |
| `ui/screens/analytics/AdvancedAnalyticsScreen.kt` | Advanced analytics UI |
| `ui/screens/analytics/AdvancedAnalyticsViewModel.kt` | Advanced analytics data |
| `ui/components/SpendingTrendChart.kt` | Trend visualization |
| `ui/components/SpendingPaceGauge.kt` | Spending pace gauge |
| `ui/components/ChartMarker.kt` | Chart markers |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/analytics/InsightsEngine.kt` | **MAIN ENGINE** - Generates spending insights |
| `domain/analytics/AdvancedAnalyticsEngine.kt` | **MAIN ENGINE** - Advanced pattern analysis |
| `domain/analytics/AnalyticsModels.kt` | Analytics data models |
| `domain/analytics/AdvancedAnalyticsModels.kt` | Advanced analytics models |
| `domain/util/StringDistanceUtils.kt` | String similarity (Jaro-Winkler) |
| `domain/util/BKTree.kt` | BK-tree for fuzzy matching |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/AnalyticsRepository.kt` | **MAIN REPO** - Analytics data queries |
| `data/repository/ExpenseRepository.kt` | Base expense data |

---

## SEGMENT 8: EXPENSE MANAGEMENT (Core)

**Description:** Core expense CRUD operations.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/transactions/TransactionsScreen.kt` | Transaction list |
| `ui/screens/transactions/TransactionsViewModel.kt` | Transaction operations |
| `ui/screens/transactions/TransactionFilter.kt` | Transaction filtering |
| `ui/screens/addexpense/AddExpenseSheet.kt` | Manual expense entry |
| `ui/screens/addexpense/AddExpenseViewModel.kt` | Manual expense VM |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/util/AmountUtils.kt` | Amount parsing and validation |
| `domain/util/CurrencyNormalizer.kt` | Currency normalization |
| `domain/util/CommonPatterns.kt` | Regex patterns for parsing |
| `domain/util/DateFormatterUtils.kt` | Date formatting |
| `domain/util/CalendarUtils.kt` | Calendar utilities |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/ExpenseRepository.kt` | **MAIN REPO** - Expense CRUD |
| `data/repository/ManualExpenseRepository.kt` | Manual expense entry |
| `data/repository/PlannedExpenseRepository.kt` | Planned/future expenses |
| `data/repository/SavingsGoalRepository.kt` | Savings goals |
| `data/repository/CategoryRepository.kt` | Categories |
| `data/repository/AnalyticsRepository.kt` | Analytics queries |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/entity/Expense.kt` | Expense entity (includes TransferDirection enum, isNotMine, isSharedExpense) |
| `data/database/entity/PlannedExpense.kt` | Planned expense entity |
| `data/database/entity/SavingsGoal.kt` | Savings goal entity |
| `data/database/dao/ExpenseDao.kt` | Expense queries |
| `data/database/dao/PlannedExpenseDao.kt` | Planned expense queries |
| `data/database/dao/SavingsGoalDao.kt` | Savings goal queries |
| `data/database/model/ExpenseWithCategory.kt` | Expense with category model |
| `data/database/model/ExpenseWithCategory_Extensions.kt` | ExpenseWithCategory extensions |
| `data/database/converter/Converters.kt` | Type converters for Room |

---

## SEGMENT 9: DASHBOARD & WIDGETS

**Description:** Home screen dashboard with configurable widgets.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/MainActivity.kt` | Main activity with NavHost |
| `ui/MainViewModel.kt` | Main app state |
| `ui/theme/Theme.kt` | App theming (colors, typography) |
| `ui/components/BentoCard.kt` | Bento grid layout card |
| `ui/components/PulseDot.kt` | Animated pulse indicator |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/model/BlockPartyDay.kt` | Block party day model |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/DashboardRepository.kt` | **MAIN REPO** - Widget configuration |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/model/DashboardWidgetConfig.kt` | Widget configuration model |

### App Entry Point
| File | Purpose |
|------|---------|
| `ExpenseTrackerApp.kt` | Application class (Hilt setup) |

---

## SEGMENT 10: NOTIFICATIONS (Budget Alerts)

**Description:** In-app and system notifications for budget alerts.

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/service/NotificationService.kt` | Notification interface |

### Data Layer
| File | Purpose |
|------|---------|
| `data/service/AndroidNotificationService.kt` | **MAIN** - Android notification implementation |

---

## SEGMENT 11: DEBUG & DIAGNOSTICS

**Description:** Debug screens for troubleshooting and testing.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/debug/DebugScreen.kt` | Main debug screen |
| `ui/screens/debug/DebugViewModel.kt` | Debug operations |
| `ui/screens/debug/DebugViewerScreen.kt` | Debug data viewer |
| `ui/screens/debug/DebugDataStorage.kt` | Debug data storage/loading |
| `ui/screens/debug/DebugIssueDetector.kt` | Issue detection logic |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/debug/ServiceDiagnostics.kt` | Service health diagnostics |
| `domain/debug/NotificationSeeder.kt` | Test notification seeding |

---

## SEGMENT 12: DEPENDENCY INJECTION

**Description:** Hilt dependency injection setup.

| File | Purpose |
|------|---------|
| `di/AppModule.kt` | Main Hilt module |
| `di/TimeModule.kt` | Time provider bindings |
| `di/DispatchersModule.kt` | Coroutine dispatcher bindings |

---

## SEGMENT 14: DATABASE INFRASTRUCTURE

**Description:** Core database setup and configuration.

| File | Purpose |
|------|---------|
| `data/database/AppDatabase.kt` | Main Room database definition |

---

## SEGMENT 13: UTILITIES (Cross-Cutting)

**Description:** Shared utilities used across multiple segments.

| File | Purpose |
|------|---------|
| `domain/util/TimeProvider.kt` | Time abstraction interface |
| `domain/util/SystemTimeProvider.kt` | System time implementation |
| `domain/util/AmountUtils.kt` | Amount parsing (used by parsers) |
| `domain/util/CurrencyNormalizer.kt` | Currency handling |
| `domain/util/CommonPatterns.kt` | Regex patterns |
| `domain/util/StringDistanceUtils.kt` | String similarity |
| `domain/util/BKTree.kt` | BK-tree for fuzzy search |
| `domain/util/StatisticsUtils.kt` | Statistics calculations |
| `domain/util/MerchantCleaner.kt` | Merchant name cleaning |
| `domain/util/AppConstants.kt` | App constants |
| `domain/util/TimePeriodUtils.kt` | Date period utilities |
| `domain/util/DateFormatterUtils.kt` | Date formatting |
| `ui/util/HapticFeedback.kt` | Haptic feedback utilities |

---

## QUICK REFERENCE: Common Analysis Tasks

### Check Forecast Engine Issues
→ Files: `SynthesisEngine`, `NarrativeGenerator`, `InsightsEngine`, `FinancialWeatherRepository`

### Check Budget Rollover Issues
→ Files: `BudgetCalculator`, `BudgetRepository`, `BudgetMonitor`

### Check Notification Parsing Issues
→ Files: `NotificationCaptureService`, `AppParserRegistry`, all `*Parser.kt` files, `ConfidenceRouter`

### Check OCR/Receipt Issues
→ Files: `ReceiptOcrService`, `ReceiptParser`, `ReceiptRepository`

### Check Category Assignment Issues
→ Files: `CategorizationEngine`, `MerchantNormalizer`, `HybridExpenseClassifier`, `CategoryRepository`

### Check Recurring Detection Issues
→ Files: `RecurringExpenseEngine`, `RecurringExpenseRepository`

### Check Analytics/Insights Issues
→ Files: `InsightsEngine`, `AdvancedAnalyticsEngine`, `AnalyticsRepository`

### Check ML Training Issues
→ Files: `TransactionClassifier`, `MerchantNormalizer`, `ExpenseCategoryClassifier`, `UserCorrectionRepository`
