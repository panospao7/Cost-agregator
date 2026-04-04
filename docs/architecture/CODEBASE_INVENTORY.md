# ExpenseTracker Android Codebase - Ground-Truth Inventory

**Generated:** 2026-04-02  
**Total Kotlin Files:** 528  
**Database Version:** 51  
**Architecture:** Clean Architecture + MVVM + Jetpack Compose + Room + Hilt DI

---

## EXECUTIVE SUMMARY

| Metric | Count |
|--------|-------|
| UI Screens | 77 |
| ViewModels | 36 |
| Domain Files | 198 |
| Repositories | 32 |
| Database Entities | 37 |
| DAOs | 36 |
| DI Modules | 21 |
| UI Components | 44 |
| Services/Receivers | 14 |
| String Resources | 1,730 |

---

## 1. UI SCREENS - COMPLETE INVENTORY

### Screen Distribution
- **Total Screen Files:** 77 (includes screens, ViewModels, sheets, utilities)
- **Screens with ViewModel:** 34
- **Screens without ViewModel:** 3 (SplitTemplates, DebugViewer, RecurringExpenses)
- **Navigable Screens:** 32
- **Orphaned Screens:** 5 (RecurringExpenses, AiSettings, Categories, Debug variants)

### Main Tabs (4)
1. **HomeScreen** + HomeViewModel → `NavigationDestination.Home`
2. **TransactionsScreen** + TransactionsViewModel → `NavigationDestination.Transactions`
3. **AnalyticsScreen** + AnalyticsViewModel → `NavigationDestination.Analytics`
4. **AssistantSheet** + AssistantViewModel → `NavigationDestination.Assistant`

### Feature Screens (32 navigable + 5 orphaned)

#### Budget & Finance
- BudgetScreen + BudgetViewModel → `Budget`
- BudgetForecastingScreen + BudgetForecastingViewModel → `BudgetForecasting` (parameterized)
- SavingsGoalsScreen + SavingsGoalsViewModel → `SavingsGoals`

#### Expense Management
- AddExpenseSheet + AddExpenseViewModel → `AddExpense`
- ManualRecurringExpenseScreen + ManualRecurringExpenseViewModel → `ManualRecurringExpense`
- RecurringExpensesScreen (ORPHANED - no ViewModel, not in NavDestination)

#### Receipt & Categorization
- ReceiptScanScreen + ReceiptScanViewModel → `ScanReceipt`
- ReceiptMatchingScreen + ReceiptMatchingViewModel → `ReceiptMatching`
- CategoryScreen + CategoryViewModel (ORPHANED)

#### Advanced Features
- AdvancedAnalyticsScreen + AdvancedAnalyticsViewModel → `AdvancedAnalytics`
- CarbonFootprintScreen + CarbonFootprintViewModel → `CarbonFootprint`
- WarrantyTrackerScreen + WarrantyTrackerViewModel → `WarrantyTracker`
- PriceProtectionScreen + PriceProtectionViewModel → `PriceProtection`
- BillNegotiationScreen + BillNegotiationViewModel → `BillNegotiation`

#### AI & Search
- NaturalLanguageSearchScreen + NaturalLanguageSearchViewModel → `SmartSearch`
- AiSettingsScreen + AiSettingsViewModel (ORPHANED)

#### Investments & Banking
- InvestmentPortfolioScreen + InvestmentViewModel → `InvestmentPortfolio`
- BankConnectionsScreen + BankConnectionsViewModel → `BankConnections`

#### Reminders & Challenges
- BillRemindersScreen + BillRemindersViewModel → `BillReminders`
- SpendingChallengesScreen + SpendingChallengesViewModel → `SpendingChallenges`

#### Location & Cash Flow
- SpendingMapScreen + SpendingMapViewModel → `SpendingMap`
- CashFlowCalendarScreen + CashFlowCalendarViewModel → `CashFlowCalendar`

#### Configuration & Sharing
- CurrencyManagementScreen + CurrencyManagementViewModel → `CurrencyManagement`
- SubscriptionManagementScreen + SubscriptionManagementViewModel → `SubscriptionManagement`
- TaxConfigurationScreen + TaxConfigurationViewModel → `TaxConfiguration`
- ExportOptionsScreen + ExportOptionsViewModel → `ExportOptions`
- SharedExpenseGroupsScreen + SharedExpenseGroupsViewModel → `SharedExpenseGroups`

#### Splitting & Lifestyle
- SplitTemplatesScreen (no ViewModel) → `SplitTemplates`
- VisualSplitEditorScreen + VisualSplitViewModel → `VisualSplitEditor` (parameterized)
- LifestyleInflationScreen + LifestyleInflationViewModel → `LifestyleInflation`

#### Special
- ReviewScreen + ReviewViewModel → `Review`

#### Debug Screens (Conditional)
- DebugScreen + DebugViewModel (not navigable)
- DebugViewerScreen (no ViewModel)
- CategorizationDebugScreen + CategorizationDebugViewModel (not navigable)
- DebugDataStorage, DebugIssueDetector (utilities)

---

## 2. VIEWMODELS (36 Total)

### By Category
**Main:** MainViewModel

**Tabs (5):** HomeViewModel, TransactionsViewModel, AnalyticsViewModel, AdvancedAnalyticsViewModel, AssistantViewModel

**Features (31):** 
- Expense: AddExpenseViewModel, ManualRecurringExpenseViewModel
- Budget: BudgetViewModel, BudgetForecastingViewModel
- Analytics: AnalyticsViewModel, AdvancedAnalyticsViewModel
- Savings: SavingsGoalsViewModel
- Carbon: CarbonFootprintViewModel
- Warranty: WarrantyTrackerViewModel
- Price: PriceProtectionViewModel
- Negotiation: BillNegotiationViewModel
- Search: NaturalLanguageSearchViewModel
- Receipt: ReceiptScanViewModel, ReceiptMatchingViewModel
- Investment: InvestmentViewModel
- Bank: BankConnectionsViewModel
- Reminders: BillRemindersViewModel
- Challenges: SpendingChallengesViewModel
- CashFlow: CashFlowCalendarViewModel
- Lifestyle: LifestyleInflationViewModel
- Split: VisualSplitViewModel
- Currency: CurrencyManagementViewModel
- Subscription: SubscriptionManagementViewModel
- Tax: TaxConfigurationViewModel
- Export: ExportOptionsViewModel
- Groups: SharedExpenseGroupsViewModel
- Map: SpendingMapViewModel
- Review: ReviewViewModel
- AI Settings: AiSettingsViewModel
- Category: CategoryViewModel
- Debug: DebugViewModel, CategorizationDebugViewModel

---

## 3. NAVIGATION GRAPH (32 Routes)

**File:** `ui/navigation/NavigationDestination.kt` (sealed class, type-safe)

### Main Routes
```kotlin
- Home
- Transactions
- Analytics
- Assistant
```

### Feature Routes (28)
1. AddExpense
2. ScanReceipt
3. SavingsGoals
4. CarbonFootprint
5. WarrantyTracker
6. PriceProtection
7. BillNegotiation
8. SmartSearch
9. ReceiptMatching
10. InvestmentPortfolio
11. BankConnections
12. BillReminders
13. SpendingChallenges
14. AdvancedAnalytics
15. CashFlowCalendar
16. LifestyleInflation
17. SplitTemplates
18. VisualSplitEditor (parameterized: expense, templateId)
19. CurrencyManagement
20. SubscriptionManagement
21. TaxConfiguration
22. ExportOptions
23. ManualRecurringExpense
24. SharedExpenseGroups
25. BudgetForecasting (parameterized: budget)
26. Review
27. SpendingMap
28. Budget

### Navigation Infrastructure
- **NavigationController.kt:** State management for navigation
- **FeatureConfig.kt:** Feature feature configuration
- **MainActivity.kt:** Navigation host and activity setup

### Gaps Identified
- RecurringExpensesScreen (NOT in NavigationDestination - orphaned)
- AiSettingsScreen (NOT in NavigationDestination - orphaned)
- CategoryScreen (NOT in NavigationDestination - orphaned)
- Debug screens (Conditional, development-only)

---

## 4. DOMAIN LAYER (198 Files)

### AI & Machine Learning (54 files)
**Services (17):**
- AiArtifactRepository, AiCapabilityRouter, AiChatRepository
- AiEngagementRepository, AiEnvironmentMonitor, AiSettingsRepository
- AiWorkScheduler, CategorizationAssistService, DashboardBriefingService
- DedupeJudgeService, NotificationFallbackParser, QueryInterpretationService
- ReceiptAssistService, ReceiptItemCategorizationService
- ReviewExplanationService, ReviewPriorityScorer, SemanticDuplicateDetector

**Use Cases (22):**
- CategorizeReceiptItemsUseCase, DeliverProactiveBriefingNotificationUseCase
- DetectSemanticDuplicateUseCase, ExecuteFinancialQueryUseCase
- ExplainPendingReviewUseCase, GenerateDashboardBriefingUseCase
- GenerateTransactionInsightUseCase, GetAiRuntimeStatusUseCase
- InterpretFinancialQueryUseCase, JudgePendingReviewDuplicateUseCase
- MapFinancialQueryToNavigationUseCase, PrioritizeReviewItemsUseCase
- SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase
- SyncProactiveBriefingWorkUseCase
- Plus 7 InputBuilder classes

**Models/Policies (15):**
- AiArtifactPresentation, AiLoadState, AiModels
- AiPolicy, AiPolicyImpl, CaptureAssistModels
- FinancialQueryModels, NotificationParsingModels
- OnDeviceRuntimePresentation, ReceiptItemCategorizationModels
- ReviewPriorityModels, SemanticDuplicateModels, DefaultAiCapabilityRouter
- AiRuntimeStatusModels

### Analytics (13 files)
- AdvancedAnalyticsEngine, AdvancedAnalyticsDashboard, AdvancedAnalyticsModels
- AnalyticsModels, AnomalyDetector, CategoryInsightEngine
- DayOfWeekAnalyzer, InsightsEngine, MerchantInsightEngine
- MonthlyComparisonCalculator, SpendingPaceCalculator
- SpendingThresholdCalculator, TotalsAggregationEngine, TransferDirectionAnalytics

### Budget & Forecasting (11 files)
- BudgetCalculator, BudgetForecastingEngine, BudgetModels
- BudgetMonitor, BudgetRecommendationEngine, SharedBudgetManager
- DataQualityAssessor, HistoricalSpendingDistribution
- MonteCarloResult, MonteCarloSpendingSimulator
- Plus backup-related files

### Receipts & OCR (7 files)
- ReceiptOcrService, ReceiptParser, BankStatementParser
- EnhancedMerchantExtractor, OcrLanguageProcessor
- OcrPreprocessingPipeline, ProcessReceiptUseCase

### Categorization (6 files)
- CategorizationEngine, ContextualInferenceEngine
- CategoryKeywords, GreeklishNormalizer
- MerchantCanonicalizer, SemanticKeywordMatcher

### Transaction Parsing (4 files)
- AppParserRegistry, GenericTransactionParser
- TransferDirectionDetector
- Parsers: GoogleWalletParser, GreekBankParser, RevolutParser, SmsParser

### Location & Geolocation (8 files)
- AreaSpendingEngine, GeocodingResult, LocatedExpense
- LocationInsightsEngine, LocationModels, LocationResolver
- NearbyPoi, SpendingHeatmapEngine, TravelDetectionEngine

### Recurring & Scheduling (3 files)
- RecurringExpenseEngine, RecurrenceCalculator, PlannedExpense models

### Savings & Investment (7 files)
- AutomatedSavingsRuleEngine, SavingsGamificationEngine, SmartSavingsEngine
- InvestmentTracker, SmartBillNegotiationEngine
- NaturalLanguageSearchEngine, PriceProtectionTracker

### Subscriptions (1 file)
- SubscriptionManagerEngine

### Groups & Splitting (5 files)
- GroupTransactionCoordinator, SettlementCalculator
- SharedExpenseManager, EnhancedSplitManager, SplitCalculator

### Utilities (30+ files in domain/util/)
- AmountExtractionUtils, AmountUtils, AppConstants
- BKTree (Burkhard-Keller tree), CommonPatterns
- CurrencyFormatter, CurrencyNormalizer, DateFormatterUtils
- GeoUtils, MerchantCleaner, MerchantKeyGenerator
- Money, NotificationIdGenerator, StatisticsUtils
- StringDistanceUtils, SystemTimeProvider, TimePeriodUtils, TimeProvider

### Other Features
- BusinessExpenseReportGenerator, CarbonFootprintCalculator, CashFlowCalculator
- DashboardFollowThroughEngine, FinancialHealthCalculator
- LifestyleInflationDetector, RecurringIncomeTracker
- SpendingChallengeManager, BillReminderManager
- ReceiptTransactionMatcher, WidgetStyleRepository

### Core Use Cases (6)
- CategorizeExpenseUseCase, DetectDuplicateExpenseUseCase
- CalculateBudgetStatusUseCase, CalculateFinancialForecastUseCase
- ComputeDashboardWidgetsUseCase, DashboardDataProvider

---

## 5. REPOSITORIES (32 Total)

### Core
1. ExpenseRepository
2. CategoryRepository
3. BudgetRepository
4. DashboardRepository
5. AnalyticsRepository

### AI/Chat
6. AiArtifactRepositoryImpl
7. AiChatRepositoryImpl
8. AiEngagementRepositoryImpl
9. AiSettingsRepositoryImpl

### Specialized
10. AccountingExportRepository
11. BusinessExpenseRepository
12. CurrencySettingsRepositoryImpl
13. DatabaseBackupRepositoryImpl
14. FinancialWeatherRepository
15. ManualExpenseRepository
16. MerchantCategoryRepository
17. MerchantLocationRepository
18. MerchantNormalizationRepository
19. MerchantRulesRepository
20. MultiCurrencyRepository
21. NotificationProcessingPipeline
22. NotificationRepository
23. PlannedExpenseRepository
24. ReceiptRepository
25. RecommendationRepository
26. RecurringExpenseRepository
27. ReviewQueueRepository
28. SavingsGoalRepository
29. SourceStatsRepository
30. UserCorrectionRepository
31. WarrantyTrackerRepository
32. WidgetStyleRepositoryImpl

---

## 6. DATABASE (Version 51)

### Entities (37)

**Core:**
- Expense, Category, Budget, ManualRecurringExpense
- PlannedExpense, SavingsGoal, ScannedReceipt

**Merchants:**
- MerchantCategory, MerchantCanonical, MerchantAlias
- MerchantLocation, MerchantLocationCorrection
- MerchantNormalization

**Notifications & Review:**
- RawNotification, PendingReview, BlockedPackage
- UserCorrection, SourceStats

**Financial:**
- ExchangeRate, Investment, InvestmentValue
- SubscriptionPriceHistory, SubscriptionUsage
- Warranty, ReturnWindow, MileageTracking

**Groups:**
- ExpenseGroup, GroupMember, GroupExpense

**Advanced:**
- BudgetForecast, ReceiptItemCategorization
- RecommendationEntity, SplitTemplate, SplitItemAssignment

**AI:**
- AiArtifactEntity, AiChatSessionEntity, AiChatMessageEntity

### DAOs (36)
One DAO per entity (mostly 1-to-1 mapping)
- **Deprecated:** RecurringExpenseDao (delegates to ManualRecurringExpenseDao)
- **Special:** MerchantNormalizationDao, BankConnectionDao

### Migration History
- Database Version: 51
- Migration methods: 45+
- Export schema: Enabled
- Type converters: Defined in converter/Converters.kt

---

## 7. DEPENDENCY INJECTION (21 Modules)

### Core Infrastructure
1. **AppModule** - Application-level bindings
2. **DatabaseModule** - Room database setup
3. **DaoModule** - All DAO providers
4. **DispatchersModule** - Coroutine dispatchers
5. **TimeModule** - Time providers
6. **ApplicationScope** - Custom scope annotations

### Feature Modules
7. **AiModule** - AI/ML services
8. **BudgetForecastModule** - Budget forecasting
9. **CashFlowModule** - Cash flow calculations
10. **CurrencyModule** - Currency conversion
11. **ExportModule** - Export functionality
12. **GroupsModule** - Shared expenses
13. **InvestmentModule** - Investment tracking
14. **OcrImprovementsModule** - OCR & receipts
15. **SavingsModule** - Savings engines
16. **SubscriptionModule** - Subscriptions
17. **TaxModule** - Tax estimation

### Specialized
18. **BackupRepositoryModule** - Backup/restore
19. **SecurityModule** - Encryption & security
20. **ServiceModule** - App services
21. **Phase4FeaturesModule** - Empty (uses @Inject)

---

## 8. ANDROID MANIFEST

### Services (1)
- **NotificationCaptureService**
  - Type: NotificationListenerService
  - Foreground: dataSync|location
  - Exported: true

### Receivers (2)
- **BootReceiver** - BOOT_COMPLETED, MY_PACKAGE_REPLACED
- **ServiceRestartReceiver** - Service keep-alive

### Permissions (14)
- Foreground service (3): FOREGROUND_SERVICE, DATA_SYNC, LOCATION
- Notifications (2): POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED
- Camera (2): CAMERA permission + hardware feature
- Location (2): ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
- File access (2): READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE
- Network (2): INTERNET, ACCESS_NETWORK_STATE
- System (1): WAKE_LOCK

### Providers
- FileProvider for camera file sharing

---

## 9. UI COMPONENTS (44 Files)

### Layout Components
- AppNavigationBar, PeriodBlock, PeriodGridView, PeriodNavigationBar

### Cards
- RecommendationCard, MonteCarloForecastCard
- PlaceInsightCard, NearbyShopSuggestionCard
- TotalsDashboardCard, RetroTotalsDashboardCard
- RetroBudgetBlockPartyCard, RetroTopCategoriesCard, RetroCategoryBreakdownSheet

### Charts/Visualization
- SpendingTrendChart, SpendingPaceGauge

### Dialogs/Sheets
- LocationPermissionDialog, NotificationPermissionDialog
- LocationCorrectionSheet, LocationSearchPicker

### Badges/Visual
- TransferDirectionBadge, PulseDot

### Component Groups
- ui/components/analytics/ - Analytics-specific
- ui/components/health/ - Health/financial health
- ui/components/feature/ - Feature-specific components
- ui/components/ai/ - AI-related components
- ui/components/common/ - Shared/reusable

### Utilities
- ColorExtensions, HapticFeedback, ModifierExtensions, ClipboardAmountParser

---

## 10. SERVICES & WORKERS (12 Files)

### Main Services
1. **NotificationCaptureService** - Notification listener
2. **AndroidNotificationService** - Notification management

### Recommendation System
3. RecommendationCacheService
4. RecommendationDeduplicator
5. RecommendationDismissalHandler
6. RecommendationInvalidator
7. RecommendationLifecycleManager
8. RecommendationStateManager

### Workers
9. ReceiptMatchingWorker (receipt matching background)
10. WarrantyExpirationWorker (warranty tracking)

### Utilities
11. TransactionFilterSerializer
12. NavigationTargetResolver

---

## 11. FEATURE IMPLEMENTATION CHECKLIST

### Confirmed Implemented (28+)
✅ Home/Dashboard  
✅ Transactions/History  
✅ Analytics (2 variants)  
✅ Expense Management  
✅ Receipt Scanning  
✅ Receipt Matching  
✅ Category Management  
✅ Budget Management  
✅ Savings Goals  
✅ Investment Tracking  
✅ Currency Management  
✅ Subscriptions  
✅ Tax Configuration  
✅ Groups/Shared Expenses  
✅ Warranty Tracking  
✅ Price Protection  
✅ Bill Negotiation  
✅ Bill Reminders  
✅ Spending Challenges  
✅ Spending Map/Location  
✅ Natural Language Search  
✅ Cash Flow Calendar  
✅ Lifestyle Inflation  
✅ Carbon Footprint  
✅ AI Assistant  
✅ AI Chat  
✅ Expense Splitting  
✅ Export/Backup  

### Additional Features Found
✅ Recurring Expense Management (2 screens)  
✅ Bank Connection Integration  
✅ Merchant Normalization  
✅ Recommendation Engine  
✅ Widget Customization  
✅ Business Expense Reporting  
✅ Mileage Tracking  
✅ Source Stats Tracking  
✅ Review Queue System  
✅ User Corrections  
✅ Notification Processing  
✅ Proactive Briefing  

### Screen Status Issues
⚠️ **RecurringExpensesScreen** - ORPHANED
- Exists: Yes
- ViewModel: No
- Navigable: No
- Status: Legacy, replaced by ManualRecurringExpenseScreen

⚠️ **AiSettingsScreen** - ORPHANED
- Exists: Yes
- ViewModel: Yes
- Navigable: No
- Status: Disconnected from navigation

⚠️ **CategoryScreen** - ORPHANED
- Exists: Yes
- ViewModel: Yes
- Navigable: No
- Status: Possibly redundant with category management

---

## 12. INTERNATIONALIZATION

- **Single language:** English strings defined
- **String count:** 1,730 strings in values/strings.xml
- **Coverage:** ~43 strings per screen average
- **No variants found:** No language-specific folders (values-es/, values-fr/, etc.)

---

## 13. ARCHITECTURAL COMPLIANCE

### Clean Architecture
✅ **Domain:** Pure business logic (198 files), no Android deps  
✅ **Data:** Repositories, entities, DAOs (32 repos + 37 entities + 36 DAOs)  
✅ **UI:** Screens, ViewModels, components (77 screens + 36 ViewModels + 44 components)  
✅ **DI:** Hilt modules (21 modules)

### Design Patterns
✅ Repository Pattern  
✅ ViewModel Pattern  
✅ Use Case Pattern  
✅ Sealed Class Navigation  
✅ Flow/StateFlow Reactive  
✅ Room Database Persistence  

### Database
✅ Version 51 with 45+ migrations  
✅ Export schema enabled  
✅ Type converters defined  

---

## 14. KNOWN ISSUES & RECOMMENDATIONS

### Critical
1. **RecurringExpensesScreen orphaned** - Recommend deprecation/removal
2. **AiSettingsScreen orphaned** - Add to NavigationDestination or remove
3. **CategoryScreen orphaned** - Consolidate or expose in navigation

### Technical Debt
1. **RecurringExpenseDao deprecated** - Migration path provided
2. **Duplicate analytics screens** - Consider consolidation
3. **Recurring expense duplication** - Two screens for same feature

### Recommendations
- Remove/consolidate orphaned screens
- Complete i18n with language variants
- Review and update deprecated DAOs
- Document Phase structure more clearly
- Add comprehensive integration tests

---

## 15. STATISTICS SUMMARY

| Category | Count | % |
|----------|-------|---|
| UI Screens | 77 | 14.6% |
| ViewModels | 36 | 6.8% |
| Domain Logic | 198 | 37.5% |
| Repositories | 32 | 6.1% |
| Database Files | 98 | 18.6% |
| DI Modules | 21 | 4.0% |
| Components | 44 | 8.3% |
| Services | 12 | 2.3% |
| Other | 10 | 1.9% |
| **TOTAL** | **528** | **100%** |

---

## End of Inventory

**This inventory represents a comprehensive analysis of the ExpenseTracker codebase as of 2026-04-02.**
