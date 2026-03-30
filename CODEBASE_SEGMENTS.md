# ExpenseTracker Codebase Segmentation Guide

**Purpose:** Break down the codebase into logical feature segments for targeted AI analysis and bug hunting.

> **For overall architecture understanding, see [ARCHITECTURE.md](./ARCHITECTURE.md)**

---

## FILES COVERED: 185+ Total Kotlin Files

| Segment | Files | Description |
|---------|-------|-------------|
| 1 | ~20 | Financial Forecast/Weather (+ Monte Carlo) |
| 2 | ~8 | Budget Management |
| 3 | ~20 | Notification Parsing |
| 4 | ~8 | Receipt Scanning (OCR) |
| 5 | ~15 | Merchant Categorization |
| 6 | ~5 | Recurring Expenses |
| 7 | ~15 | Analytics & Insights |
| 8 | ~20 | Core Expense Management |
| 9 | ~20 | Dashboard & Widgets (NEW: Totals Dashboard) |
| 10 | ~3 | Notifications |
| 11 | ~8 | Debug & Diagnostics |
| 12 | ~6 | Dependency Injection (Updated) |
| 13 | ~25 | Utilities (Updated) |
| 14 | ~3 | Use Cases (NEW) |
| 15 | ~1 | Performance (NEW) |
| 16 | ~1 | Configuration (NEW) |
| 17 | ~15 | Location Enrichment (NEW Mar 2026) |
| 18 | ~8 | AI Follow-Through (Phase 4B - NEW Mar 2026) |
| 19 | ~10 | Totals Dashboard (NEW Mar 2026) |

---

## How to Use This Guide

When analyzing a specific feature, check files in this order:
1. **UI Layer** (Screens/ViewModels)
2. **Domain Layer** (Engines/Logic/Models)
3. **Data Layer** (Repositories)
4. **Database Layer** (DAOs/Entities)

---

## SEGMENT 1: FINANCIAL FORECAST / WEATHER

**Description:** Core forecasting engine that predicts month-end spending and generates financial "weather" narratives. Includes both deterministic (SynthesisEngine) and stochastic (Monte Carlo) forecasting.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/home/HomeViewModel.kt` | Main VM that uses FinancialWeatherRepository |
| `ui/screens/home/HomeScreen.kt` | Displays FinancialRunwayCard, FinancialWeatherCard, MonteCarloForecastCard |
| `ui/components/FinancialRunwayCard.kt` | Shows days until money runs out |
| `ui/components/FinancialWeatherCard.kt` | Shows weather narrative (clear, cloudy, stormy) |
| `ui/components/ForecastTimeline.kt` | Visual timeline of projected spending |
| `ui/components/MonteCarloForecastCard.kt` | Probabilistic month-end forecast (NEW Mar 2026) |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/logic/SynthesisEngine.kt` | **MAIN ENGINE** - Synthesizes forecasts from budgets, recurring expenses |
| `domain/forecasting/MonteCarloSpendingSimulator.kt` | **NEW** - Monte Carlo simulation (1000 iterations) |
| `domain/forecasting/MonteCarloResult.kt` | **NEW** - Result models (percentiles, confidence) |
| `domain/forecasting/HistoricalSpendingDistribution.kt` | **NEW** - Weekly aggregation + log-normal fit |
| `domain/forecasting/DataQualityAssessor.kt` | **NEW** - Confidence scoring |
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
| `data/repository/DashboardRepository.kt` | Dashboard widget configuration |

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

### Monte Carlo Design Notes (Mar 2026)
- **Sampling unit**: Weekly totals (18-month lookback)
- **Distribution**: Log-normal fit on weekly spending (right-skewed)
- **Quality filter**: Weeks with < 3 transaction-days excluded
- **Outlier handling**: Trim to middle 80% (top/bottom 10%)
- **Simulation**: 1000 iterations, two-stage (deterministic + stochastic)
- **Output**: P10/P25/P50/P75/P90 percentiles + probability under budget
- **Confidence**: Weighted score (volume 40%, density 25%, fitness 20%, recency 15%)

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
| `domain/parser/TransferDirectionDetector.kt` | Transfer direction detection (60+ patterns, ENHANCED) |
| `domain/parser/parsers/GreekBankParser.kt` | Greek bank notifications (NBG, Alpha, Eurobank, Piraeus) |
| `domain/parser/parsers/RevolutParser.kt` | Revolut app notifications |
| `domain/parser/parsers/GoogleWalletParser.kt` | Google Wallet notifications |
| `domain/parser/parsers/SmsParser.kt` | SMS-based bank notifications |
| `domain/intelligence/ConfidenceRouter.kt` | Routes transactions based on confidence scoring |
| `domain/intelligence/TransactionClassifier.kt` | ML classifier for transaction detection |
| `domain/intelligence/CrossSourceDeduplication.kt` | Duplicate detection (ENHANCED Feb 2026) |
| `domain/service/NotificationService.kt` | Notification sending interface |

### Analytics (Transfer Direction)
| File | Purpose |
|------|---------|
| `domain/analytics/TransferDirectionAnalytics.kt` | Transfer detection analytics |

### Cross-Source Deduplication (NEW Feb 2026)
| File | Purpose |
|------|---------|
| `domain/intelligence/CrossSourceDeduplication.kt` | Detects duplicates across notifications, statements, pending reviews |

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

## SEGMENT 5: MERCHANT CATEGORIZATION (ENHANCED Feb 2026)

**Description:** Automatically categorizes transactions based on merchant names using rules and ML. Now includes 5-layer categorization pipeline.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/categories/CategoryScreen.kt` | Category management UI |
| `ui/screens/categories/CategoryViewModel.kt` | Category operations |
| `ui/screens/debug/CategorizationDebugScreen.kt` | Debug categorization pipeline (NEW) |
| `ui/screens/debug/CategorizationDebugViewModel.kt` | Debug VM (NEW) |

### Domain Layer (Enhanced)
| File | Purpose |
|------|---------|
| `domain/categorization/CategorizationEngine.kt` | **MAIN ENGINE** - 5-layer categorization pipeline |
| `domain/categorization/GreeklishNormalizer.kt` | Greek to Latin with diphthongs (NEW) |
| `domain/categorization/MerchantCanonicalizer.kt` | Strip corporate suffixes (NEW) |
| `domain/categorization/SemanticKeywordMatcher.kt` | Word-boundary keyword matching (NEW) |
| `domain/categorization/ContextualInferenceEngine.kt` | Amount/time-based inference (NEW) |
| `domain/categorization/CategoryKeywords.kt` | Pre-defined keyword mappings (NEW) |
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

### Domain Layer (NEW - Focused Analytics Engines)
| File | Purpose |
|------|---------|
| `domain/analytics/InsightsEngine.kt` | **COORDINATOR** - Orchestrates all insight calculations |
| `domain/analytics/SpendingPaceCalculator.kt` | Calculates spending pace vs typical |
| `domain/analytics/AnomalyDetector.kt` | Detects unusual transactions |
| `domain/analytics/MonthlyComparisonCalculator.kt` | Compares current vs previous month |
| `domain/analytics/CategoryInsightEngine.kt` | Analyzes category spending |
| `domain/analytics/MerchantInsightEngine.kt` | Analyzes merchant patterns |
| `domain/analytics/DayOfWeekAnalyzer.kt` | Analyzes day-of-week patterns |
| `domain/analytics/AdvancedAnalyticsEngine.kt` | Advanced pattern analysis |
| `domain/analytics/AnalyticsModels.kt` | Analytics data models |

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

**Description:** Home screen dashboard with configurable widgets. Includes the new Totals Dashboard with hierarchical drill-down (Year → Month → Week → Day).

### UI Layer
| File | Purpose |
|------|---------|
| `ui/MainActivity.kt` | Main activity with NavHost |
| `ui/MainViewModel.kt` | Main app state |
| `ui/theme/Theme.kt` | App theming (colors, typography) |
| `ui/components/BentoCard.kt` | Bento grid layout card |
| `ui/components/PulseDot.kt` | Animated pulse indicator |
| `ui/components/AppNavigationBar.kt` | Navigation bar (6 tabs: Home, Activity, Review, Plan, Analytics, **Map**) |
| `ui/components/AppFabMenu.kt` | FAB menu (NEW) |
| `ui/components/NotificationPermissionDialog.kt` | Permission dialog (NEW) |
| `ui/components/TotalsDashboardCard.kt` | Monthly/weekly totals with drill-down (NEW Mar 2026) |
| `ui/components/PeriodNavigationBar.kt` | Period navigation with filter chips (NEW Mar 2026) |
| `ui/components/PeriodGridView.kt` | Period grid display with blocks (NEW Mar 2026) |
| `ui/components/PeriodBlock.kt` | Individual period block (NEW Mar 2026) |
| `ui/components/CategoryBreakdownSheet.kt` | Category breakdown bottom sheet (NEW Mar 2026) |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/model/BlockPartyDay.kt` | Block party day model |
| `domain/model/PeriodTotal.kt` | Period totals with drill-down (NEW Mar 2026) |
| `domain/model/PeriodType.kt` | Period type enum (YEAR, MONTH, WEEK, DAY) (NEW Mar 2026) |
| `domain/model/PeriodStatus.kt` | Period status enum (UNDER_AVERAGE, OVER_AVERAGE, CURRENT, NO_DATA) (NEW Mar 2026) |
| `domain/model/CategoryBreakdown.kt` | Category spending breakdown (NEW Mar 2026) |
| `domain/model/CategoryInfo.kt` | Domain model for category info (NEW Mar 2026) |
| `domain/model/PeriodDrillDownState.kt` | UI state for drill-down feature (NEW Mar 2026) |
| `domain/analytics/TotalsAggregationEngine.kt` | Period totals aggregation engine (NEW Mar 2026) |

### Data Layer
| File | Purpose |
|------|---------|
| `data/repository/DashboardRepository.kt` | **MAIN REPO** - Widget configuration |
| `data/repository/ExpenseRepository.kt` | Added methods for weekly/monthly/daily totals and category breakdown |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/model/DashboardWidgetConfig.kt` | Widget configuration model |
| `data/database/dao/ExpenseDao.kt` | Added DAO queries for weekly/monthly/daily totals (NEW Mar 2026) |

### App Entry Point
| File | Purpose |
|------|---------|
| `ExpenseTrackerApp.kt` | Application class (Hilt setup) |

### Totals Dashboard Design Notes (Mar 2026)
- **Drill-down**: Year → Month → Week → Day (hierarchical navigation)
- **Data source**: ExpenseDao with strftime grouping for periods
- **Status colors**: Green (under avg), Red (over avg), Indigo (current), Gray (no data)
- **Category breakdown**: Loaded on-demand when user clicks "View Category Breakdown"
- **Filter chips**: Only show accessible levels (can go back, not forward)
- **Empty state**: Shows "No spending data yet" when no expenses exist

### Bug Fixes (Totals Dashboard - Mar 2026)
- **getMonthRange off-by-one**: Fixed Calendar.MONTH to use 0-indexed (month-1)
- **DailyTotal column mismatch**: Fixed query to return dayEpoch, startDate, endDate
- **CategoryTotalResult isIncome**: Removed non-existent isIncome column from query
- **Filter chips not working**: Only show accessible levels (can go back)
- **Category breakdown empty**: Load current month when no period selected
- **Widget not showing**: Added totals_dashboard to default dashboard config

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

### UI Layer (Updated Feb 2026)
| File | Purpose |
|------|---------|
| `ui/screens/debug/DebugScreen.kt` | Main debug screen |
| `ui/screens/debug/DebugViewModel.kt` | Debug operations |
| `ui/screens/debug/DebugViewerScreen.kt` | Debug data viewer |
| `ui/screens/debug/DebugDataStorage.kt` | Debug data storage/loading |
| `ui/screens/debug/DebugIssueDetector.kt` | Issue detection logic |
| `ui/screens/debug/CategorizationDebugScreen.kt` | Categorization pipeline debug (NEW) |
| `ui/screens/debug/CategorizationDebugViewModel.kt` | Categorization debug VM (NEW) |

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
| `di/AppModule.kt` | Legacy module (backwards compatibility) |
| `di/DatabaseModule.kt` | Room database provider (NEW) |
| `di/DaoModule.kt` | All DAO providers (NEW) |
| `di/ServiceModule.kt` | Android service providers (NEW) |
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

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/util/TimeProvider.kt` | Time abstraction interface |
| `domain/util/SystemTimeProvider.kt` | System time implementation |
| `domain/util/AmountUtils.kt` | Amount parsing (used by parsers) |
| `domain/util/CurrencyFormatter.kt` | Currency formatting |
| `domain/util/AmountExtractionUtils.kt` | Regex patterns for extraction |
| `domain/util/CurrencyNormalizer.kt` | Currency handling |
| `domain/util/DateFormatterUtils.kt` | Date formatting |
| `domain/util/TimePeriodUtils.kt` | Date range calculations |
| `domain/util/CommonPatterns.kt` | Regex patterns |
| `domain/util/StringDistanceUtils.kt` | String similarity |
| `domain/util/BKTree.kt` | BK-tree for fuzzy search |
| `domain/util/StatisticsUtils.kt` | Statistics calculations |
| `domain/util/MerchantCleaner.kt` | Merchant name cleaning |
| `domain/util/AppConstants.kt` | App constants |

### UI Layer
| File | Purpose |
|------|---------|
| `ui/util/ColorExtensions.kt` | Color parsing extensions (NEW) |
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

### Check Location Enrichment Issues
→ Files: `CompositeGeocodingService`, `NominatimGeocodingService`, `LocationResolver`, `MerchantLocationRepository`, `LocationSearchPicker`, `LocationCorrectionSheet`
→ **Recent Fixes**: Collapsible map (F1), reverseGeocode override (F2), FAB centre (F3), osmdroid config (F4-F6), OSM ID capture (F7), map height (F8)

### Check Duplicate Detection Issues (Mar 2026)
→ Files: `ExpenseDao.isDuplicate()`, `CrossSourceDeduplication`, `MerchantNormalizer`, `Expense.generateDedupeKey()`, `MerchantRulesRepository`
→ **Recent Fixes**: Greek→Latin transliteration for cross-source dedupe, removed transactionType='PURCHASE' filter

### Check Trust Score Issues (Mar 2026)
→ Files: `SourceStats`, `SourceStatsRepository`
→ **Recent Fix**: Denominator now excludes auto-rejected notifications

### Check Shared Expense Calculation Issues (Mar 2026)
→ Files: `Expense.effectiveAmount`, `ExpenseDao` SUM queries, all analytics engines
→ **Recent Fix**: Added effectiveAmount property, updated all sumOf/SUM calls

### Check Totals Dashboard Issues (Mar 2026)
→ Files: `TotalsAggregationEngine`, `ExpenseDao.getWeeklyTotalsForPeriod`, `ExpenseDao.getMonthlyTotalsForPeriod`, `DashboardRepository.getDefaultConfig()`, `PeriodNavigationBar`
→ **Recent Fixes**:
  - getMonthRange off-by-one: Fixed Calendar.MONTH to use 0-indexed (month-1)
  - DailyTotal column mismatch: Fixed query to return dayEpoch, startDate, endDate
  - CategoryTotalResult isIncome: Removed non-existent isIncome column from query
  - Filter chips not working: Only show accessible levels (can go back)
  - Category breakdown empty: Load current month when no period selected
  - Widget not showing: Added totals_dashboard to default dashboard config

### Check ML Training Issues
→ Files: `TransactionClassifier`, `MerchantNormalizer`, `ExpenseCategoryClassifier`, `UserCorrectionRepository`

---

## SEGMENT 14: USE CASES (Clean Architecture)

**Description:** Business use cases that orchestrate domain logic.

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/usecase/receipt/ProcessReceiptUseCase.kt` | Orchestrates OCR + parsing + categorization |
| `domain/usecase/expense/CategorizeExpenseUseCase.kt` | Merchant categorization with learning |
| `domain/usecase/expense/DetectDuplicateExpenseUseCase.kt` | Detects duplicate expenses (NEW) |
| `domain/usecase/budget/CalculateBudgetStatusUseCase.kt` | Budget health calculations |
| `domain/usecase/dashboard/DashboardDataProvider.kt` | Aggregates all dashboard data |
| `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt` | Financial forecast calculations (NEW) |

---

## SEGMENT 15: PERFORMANCE

**Description:** Performance optimization utilities.

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/performance/ImageCache.kt` | Bitmap caching for efficient image loading |

---

## SEGMENT 16: CONFIGURATION

**Description:** Centralized configuration constants.

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/config/AppConfig.kt` | All thresholds, limits, timeouts in one place |


## SEGMENT 17: LOCATION ENRICHMENT (NEW Mar 2026)

**Description:** Auto-enrich transactions with location data using multi-provider geocoding. Includes reverse geocoding, forward geocoding, manual correction, and map visualization.

### UI Layer
| File | Purpose |
|------|---------|
| `ui/screens/map/SpendingMapScreen.kt` | Map visualization (contains OsmMapView, MarkerDetailCard, PinExpenseSheet) |
| `ui/screens/map/SpendingMapViewModel.kt` | Map data preparation |
| `ui/components/LocationSearchPicker.kt` | Manual location search and picker (collapsible map) |
| `ui/components/LocationCorrectionSheet.kt` | "Correct pin" bottom sheet (uses LocationSearchPicker) |
| `ui/components/LocationPermissionDialog.kt` | Location permission request |

### Domain Layer
| File | Purpose |
|------|---------|
| `domain/location/LocationResolver.kt` | **MAIN** - Coordinates geocoding workflow |
| `domain/location/LocationModels.kt` | Domain models for location data |
| `domain/location/GeocodingResult.kt` | Geocoding result models |
| `domain/location/LocatedExpense.kt` | Expense with location wrapper |
| `domain/location/LocationInsightsEngine.kt` | Location-based spending insights |
| `domain/location/SpendingHeatmapEngine.kt` | Heatmap data generation |
| `domain/location/NearbyPoi.kt` | Points of interest model |

### Data Layer
| File | Purpose |
|------|---------|
| `data/location/CompositeGeocodingService.kt` | **MAIN** - Multi-provider fallback chain |
| `data/location/NominatimGeocodingService.kt` | OpenStreetMap (free, no API key) |
| `data/location/GeoapifyGeocodingService.kt` | Geoapify API (freemium) |
| `data/location/GooglePlacesGeocodingService.kt` | Google Places API (paid) |
| `data/location/PhotonGeocodingService.kt` | Photon API (free) |
| `data/location/OverpassNearbyService.kt` | OpenStreetMap POI queries |
| `data/location/LocationBackfillWorker.kt` | Background location enrichment |
| `data/location/AndroidForegroundLocationProvider.kt` | Foreground location tracking |
| `data/repository/MerchantLocationRepository.kt` | Merchant location storage |

### Database Layer
| File | Purpose |
|------|---------|
| `data/database/entity/MerchantLocation.kt` | Merchant location entity |
| `data/database/entity/MerchantLocationCorrection.kt` | User correction entity |
| `data/database/dao/MerchantLocationDao.kt` | Location queries |

### Location Features (A-E)
- **Feature A**: Auto-enrich from merchant name (reverse geocode known merchant locations)
- **Feature B**: Reverse geocode from transaction address text
- **Feature C**: Forward geocode user search queries
- **Feature D**: Manual user correction
- **Feature E**: Map visualization of spending

### Location UI Components (inline in SpendingMapScreen.kt)
- `OsmMapView`: osmdroid MapView composable with marker support
- `MarkerDetailCard`: Selected marker detail display
- `PinExpenseSheet`: Bottom sheet for pinning unlocated expenses

### Location Bug Fixes (Mar 2026)
- **F1**: Map always visible in LocationSearchPicker → Made collapsible (hidden by default, toggle, auto-expand)
- **F2**: Long-press pin not resolving address → Added reverseGeocode override
- **F3**: FAB centre-on-device not working → Wired centreOnDeviceRequest flag
- **F4**: osmdroid config loading race → Moved to factory lambda
- **F5**: Map tiles not loading → Added onResume() in factory
- **F6**: Markers disappear on recompose → Added key-based diff guard
- **F7**: OSM ID not captured in Review → Added to onSave callback
- **F8**: Map too small → Increased height to 260dp
- **Regression**: Map breaks dialog layouts → Collapsed by default


## QUICK REFERENCE: Updated Segments

### Check DI Issues
→ Files: `AppModule`, `DatabaseModule`, `DaoModule`, `ServiceModule`

### Check Use Cases
→ Files: All `*UseCase.kt` files in `domain/usecase/`

### Check Configuration
→ Files: `AppConfig.kt`

### Check Performance Issues
→ Files: `ImageCache.kt`

---

## SEGMENT 18: AI FOLLOW-THROUGH (Phase 4B - Complete Implementation Mar 2026)

**Description:** Dashboard follow-through recommendations system. Allows users to tap on AI briefing insights to navigate to deterministic filtered views. **Fully implemented:** Phase 1 (infrastructure), Phase 2 (UI integration & navigation), Phase 2.1 (improvements & hardening).

### Status Summary

| Phase | Components | Status |
|-------|------------|--------|
| **Phase 1** | DB schema, models, DAO, cache | ✅ COMPLETE |
| **Phase 2** | Engine, state mgmt, UI, navigation | ✅ COMPLETE |
| **Phase 2.1** | Thread safety, logging, docs | ✅ COMPLETE |

### UI Layer
| File | Purpose | Phase |
|------|---------|-------|
| `ui/screens/home/HomeScreen.kt` | Display recommendation cards with tappable actions | 2 |
| `ui/screens/home/HomeViewModel.kt` | State management for recommendations, navigation events | 2 |
| `ui/components/RecommendationCard.kt` | Composable card component with priority indicator and dismiss button | 2 |

### Domain Layer (Models & Enums)
| File | Purpose | Phase |
|------|---------|-------|
| `domain/model/recommendation/DashboardFollowThroughRecommendation.kt` | **MAIN** - Domain model with lifecycle validation | 1 |
| `domain/model/recommendation/RecommendationStatus.kt` | Enum: ACTIVE, ARCHIVED, EXPIRED | 1 |
| `domain/model/recommendation/RecommendationPriority.kt` | Enum: HIGH (3), MEDIUM (2), LOW (1) | 1 |

### Domain Layer (Engines & Services)
| File | Purpose | Phase |
|------|---------|-------|
| `domain/engine/DashboardFollowThroughEngine.kt` | **MAIN ENGINE** - Deterministic rule-based recommendation builder | 2 |
| `service/RecommendationDismissalHandler.kt` | Handles user dismissal workflow (optimistic UI + DB persist) | 2 |
| `service/RecommendationLifecycleManager.kt` | TTL management, periodic expiry checks, @ApplicationScope integration | 2.1 |
| `service/RecommendationStateManager.kt` | Reactive StateFlow for UI observation, max 5 limit enforcement | 2 |
| `service/RecommendationCacheService.kt` | LRU in-memory cache with TTL checks and thread safety | 2 |
| `service/TransactionFilterSerializer.kt` | JSON serialization/deserialization with error handling | 2 |

### Data Layer
| File | Purpose | Phase |
|------|---------|-------|
| `data/repository/RecommendationRepository.kt` | **MAIN REPO** - CRUD, observe, expiry, multi-user isolation | 1 |

### Database Layer
| File | Purpose | Phase |
|------|---------|-------|
| `data/database/entity/RecommendationEntity.kt` | Room entity for `recommendations` table | 1 |
| `data/database/dao/RecommendationDao.kt` | DAO with priority ranking, expiry queries, analytics | 1 |

### Migration
| File | Purpose | Phase |
|------|---------|-------|
| `MIGRATION_31_32` | Create `recommendations` table with indices | 1 |

### Phase 1: Infrastructure (COMPLETE)

**F1: Recommendation Persistence**
- Entity: `RecommendationEntity` with full lifecycle (ACTIVE → ARCHIVED → EXPIRED)
- DAO: Query patterns optimized for active, archived, expired lookups
- Schema: 4 strategic indices for O(log N) performance

**F2: Lifecycle Management**
- Status enum: ACTIVE (shown), ARCHIVED (dismissed), EXPIRED (TTL)
- TTL: 7 days from creation, configurable via AppConfig
- Multi-user isolation: userId field on every record

**F3: Cache Coherence**
- Link to ai_artifacts table: sourceArtifactId for traceability
- Enables cascading invalidation
- Soft-delete pattern: EXPIRED status before hard delete

**F4: Filter Serialization**
- TransactionFilter ↔ JSON round-trip
- Validation on deserialize
- Deterministic navigation targets: TRANSACTION_LIST, BUDGET_DETAIL, CATEGORY_DETAIL, RECURRING, REVIEW_QUEUE

**F5: Account Clearing**
- clearByUser(userId) deletes all recommendations
- Prepared for multi-user scenarios
- Cascade to ai_artifacts (via cascade logic in Phase 2)

### Phase 2: Filter & Navigation Integration (COMPLETE)

**F6: Deterministic Recommendation Engine** (DashboardFollowThroughEngine)
- **Rule 1 (HIGH)**: Large transactions (> €100)
- **Rule 2 (MEDIUM)**: Category-specific patterns
- **Rule 3 (MEDIUM)**: Merchant patterns
- **Rule 4 (LOW)**: Recent spending trends
- Max 5 recommendations per call, sorted HIGH→LOW

**F7: Transaction Hooks**
- `ManualExpenseRepository`: Hook on createExpense()
- `NotificationProcessingPipeline`: Hook on processTransactionNotification()
- Generate recommendations after successful transaction creation
- Graceful degradation if recommendation generation fails

**F8: State Management**
- `RecommendationStateManager`: Reactive StateFlow for UI
- Expires old on refresh, loads active, enforces 5-item limit
- `RecommendationDismissalHandler`: Optimistic dismiss (UI first, DB second)

**F9: Navigation Resolution**
- HomeViewModel.onRecommendationTapped(rec)
- Deserialize filterCriteria JSON
- Map navigationTarget to route
- Emit navigation event
- Target screen receives pre-applied filter

**F10: UI Components**
- RecommendationCard composable with priority color dot
- Tap to navigate, dismiss (X button) to archive
- Integrated into HomeScreen below briefing

### Phase 2.1: Improvements & Hardening (COMPLETE)

**E1: Thread Safety** (RecommendationLifecycleManager)
- AtomicBoolean for one-time periodic check startup
- Prevents duplicate background tasks

**E2: Comprehensive Logging** (Timber integration)
- DashboardFollowThroughEngine: Rule matching, generation count
- RecommendationDismissalHandler: Dismissal events
- RecommendationLifecycleManager: Expiry sweeps, errors
- All service layer operations logged at DEBUG/INFO/WARN

**E3: @ApplicationScope Injection**
- RecommendationLifecycleManager uses app-scoped CoroutineScope
- Lifecycle managed by Hilt, no manual cleanup
- Safe for background expiry checks

**E4: KDoc Documentation**
- All public methods fully documented
- Parameter descriptions, return values, exceptions
- Usage examples in key methods
- Design rationale in class docstrings

**E5: Performance Optimization**
- Removed redundant repository-level cache (DB cache hit rate 99%+)
- Simplified code path: Engine → Repository → DAO
- Better debuggability with fewer layers

**E6: Filter Serialization Improvements**
- TransactionFilterSerializer with error handling
- Fallback to empty filter on deserialization failure
- Validation before storing in DB

### Database Schema (v32+)

**Table: `recommendations` (Phase 1)**
```
CREATE TABLE recommendations (
  id TEXT PRIMARY KEY,
  userId TEXT NOT NULL,
  recommendationText TEXT NOT NULL,           -- AI-generated summary
  navigationTarget TEXT NOT NULL,              -- Deterministic target
  filterCriteria TEXT NOT NULL,               -- Serialized TransactionFilter JSON
  createdAt BIGINT NOT NULL,
  updatedAt BIGINT NOT NULL,
  dismissedAt BIGINT,                         -- null unless user dismissed
  expiresAt BIGINT NOT NULL,                  -- createdAt + 7 days
  priority TEXT NOT NULL,                     -- HIGH, MEDIUM, LOW
  category TEXT NOT NULL,                     -- Category tag for grouping
  sourceArtifactId TEXT NOT NULL,             -- Link to ai_artifacts.id
  status TEXT NOT NULL,                       -- ACTIVE, ARCHIVED, EXPIRED
  
  INDEX idx_rec_active (userId, status, expiresAt),
  INDEX idx_rec_artifact (sourceArtifactId),
  INDEX idx_rec_created (createdAt),
  INDEX idx_rec_expiry (expiresAt)
)
```

### Relationship to Existing AI System

| Component | Phase 4A | Phase 4B | Relationship |
|-----------|----------|----------|-------------|
| GenerateDashboardBriefingUseCase | Generates briefing | Reads for summary | Unidirectional |
| AiArtifactEntity | Stores brief + metadata | Referenced via sourceArtifactId | 1:N (1 artifact → N recs) |
| AI Settings toggle | dashboardBriefingEnabled | Separate (future) | Parallel controls |
| Dashboard data | Aggregates transactions | Input to deterministic builder | Shared foundation |

### Key Design Principles

1. **AI summarization only** - Brief text from AI, all navigation/filtering is deterministic code
2. **Deterministic routing** - No AI decision-making in navigation or filter synthesis
3. **Soft-delete pattern** - Archive before delete to preserve analytics
4. **TTL-based expiry** - Automatic lifecycle without manual intervention
5. **Loose coupling** - No hard FK; linkage via string ID
6. **Multi-user ready** - Complete userId isolation
7. **Thread-safe** - AtomicBoolean + Mutex guards concurrent access
8. **Observable** - Reactive StateFlow for UI, extensive logging for debugging

### Configuration (AppConfig.RecommendationPhase)

```kotlin
const val RECOMMENDATION_TTL_MS = 7L * 24 * 60 * 60 * 1000      // 7 days
const val MAX_RECOMMENDATIONS_PER_USER = 5
const val RECOMMENDATION_CLEANUP_INTERVAL_MS = 6L * 60 * 60 * 1000  // 6 hours

val PRIORITY_WEIGHTS = mapOf(
    RecommendationPriority.HIGH to 3,
    RecommendationPriority.MEDIUM to 2,
    RecommendationPriority.LOW to 1
)
```

### Testing

**Unit Tests (7 test classes, 200+ test methods)**
- RecommendationDaoTest: Query correctness (100% coverage)
- RecommendationRepositoryTest: CRUD, serialization (100% coverage)
- DashboardFollowThroughEngineTest: Rule matching, limit enforcement (95% coverage)
- RecommendationCacheServiceTest: LRU eviction, expiry (100% coverage)
- RecommendationDismissalHandlerTest: Dismissal workflow (100% coverage)
- RecommendationLifecycleManagerTest: Periodic checks, expiry (90% coverage)
- HomeViewModelRecommendationTest: Navigation, event handling (90% coverage)

**Integration Tests**
- E2E: Transaction → Recommendations → Navigation
- E2E: TTL expiration and cleanup
- E2E: Account switching and isolation
- E2E: Cache coherence and invalidation

### Transaction Flow Integration

1. **New Transaction** → ManualExpenseRepository / NotificationProcessingPipeline
2. **Hook Triggered** → DashboardFollowThroughEngine.generateRecommendations()
3. **Rules Applied** → 4 deterministic rules, sorted by priority
4. **Saved** → RecommendationRepository.saveAll() → Room DB
5. **State Updated** → RecommendationStateManager emits new list
6. **UI Renders** → HomeScreen observes StateFlow, displays RecommendationCards
7. **User Interaction** → onRecommendationTapped() or onRecommendationDismissed()
8. **Navigation** → mapToNavigationTarget() → emit NavigationEvent
9. **Lifecycle** → RecommendationLifecycleManager.cleanupExpired() periodic check

### Edge Cases Handled

- **Empty transactions**: Recommendations still generated with default text
- **Concurrent modifications**: Optimistic UI updates + DB fallback
- **Network failures**: Graceful degradation (recommendations continue without AI text)
- **Recommendation explosion**: Enforced max 5 per user
- **Account switching**: Complete userId isolation
- **TTL expiration**: Both soft (EXPIRED status) and hard delete (weekly)
- **Filter deserialization**: Fallback to empty filter on JSON error

### Debug Support

**Debug Screen Integration (Future Phase 3)**
- List active recommendations per user
- Show expiry countdown
- Manual archive/delete operations
- View serialized filter JSON
- Link to source AI artifact
- Cache statistics (size, hit rate)

### Quick Reference: AI Follow-Through File Lookup

**Check Recommendation Generation Issues:**
→ DashboardFollowThroughEngine, RecommendationRepository.saveAll()

**Check Serialization Issues:**
→ TransactionFilterSerializer, TransactionFilter JSON schema

**Check Cache Issues:**
→ RecommendationCacheService, database indices (idx_rec_active)

**Check Expiry Issues:**
→ RecommendationLifecycleManager, RecommendationDao.expireOld()

**Check State Management Issues:**
→ RecommendationStateManager, HomeViewModel.recommendations

**Check Navigation Issues:**
→ HomeViewModel.onRecommendationTapped(), mapToNavigationTarget()

**Check Multi-User Issues:**
→ RecommendationDao.clearByUser(), userId field propagation

**Check Thread Safety Issues:**
→ RecommendationLifecycleManager.periodicStarted AtomicBoolean

**Check Dismissal Issues:**
→ RecommendationDismissalHandler, RecommendationStateManager.removeFromState()

### Phase 3+ Future Enhancements

- Location-aware recommendations
- ML-based recommendation ranking
- Batch dismissal operations
- Time-based recommendation scheduling
- Recommendation feedback collection
- Smart dismissal pattern learning
