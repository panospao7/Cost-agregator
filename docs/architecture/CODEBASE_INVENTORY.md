# ExpenseTracker Android Codebase - Ground-Truth Inventory

**Generated:** 2026-05-07 (snapshot)  
**Database Version:** 117  
**Architecture:** Clean Architecture + MVVM + Jetpack Compose + Room + Hilt DI

---

## EXECUTIVE SUMMARY

Snapshot summary only: this inventory tracks the current UI, domain, data, and DI surfaces without freezing volatile counts.

### Drift Sync (2026-05-06)
- `TransactionLifecycleCoordinator` now depends on `CurrencySettingsRepository` to resolve the user's home currency for base snapshot conversion metadata.
- Reminder action receivers (`SnoozeReminderReceiver`, `DismissReminderReceiver`) are Hilt entry points and no longer construct raw database instances; both enforce `RestoreMaintenanceMode` write gating and use `TimeProvider`.
- `BackupVerifier` now classifies lifecycle/event tables as exact-restore critical (`TIER_1_EXACT`) rather than validity-only.
- Generic `CSV`/`JSON` exports use `Expense.effectiveAmount` (not raw `amount`) in `ExportOptionsViewModel`.

---

## 1. UI SCREENS - COMPLETE INVENTORY

### Screen Distribution
- Screen inventory includes screens, ViewModels, sheets, and utilities.
- Most routed screens have a ViewModel.
- Some utility/overlay screens intentionally do not.
- Navigable screens are destination-driven; the set changes with feature flags and parameterized destinations.

### Shell Destinations
1. **HomeScreen** + HomeViewModel → `NavigationDestination.Home`
2. **TransactionsScreen** + TransactionsViewModel → `NavigationDestination.Transactions(initialExpenseId)`
3. **AnalyticsScreen** + AnalyticsViewModel → `NavigationDestination.Analytics(initialPeriod)`
4. **ReviewScreen** + ReviewViewModel → `NavigationDestination.Review`
5. **BudgetScreen** + BudgetViewModel → `NavigationDestination.Budget`
6. **SpendingMapScreen** + SpendingMapViewModel → `NavigationDestination.SpendingMap(initialLocationQuery)`

Assistant is an overlay/entry surface, not a bottom tab.

### Feature Screens (destination-driven + overlays/settings)

#### Budget & Finance
- BudgetScreen + BudgetViewModel → `Budget`
- BudgetForecastingScreen + BudgetForecastingViewModel → `BudgetForecasting` (parameterized)
- SavingsGoalsScreen + SavingsGoalsViewModel → `SavingsGoals`

#### Expense Management
- AddExpenseSheet + AddExpenseViewModel → `AddExpense`
- RecurringExpensesScreen + RecurringExpensesViewModel → `RecurringExpenses`
- ManualRecurringExpenseScreen + ManualRecurringExpenseViewModel → `ManualRecurringExpense`

#### Receipt & Categorization
- ReceiptScanScreen + ReceiptScanViewModel → `ScanReceipt`
- ReceiptMatchingScreen + ReceiptMatchingViewModel → `ReceiptMatching`
- CategoryScreen + CategoryViewModel → category management surface

#### Advanced Features
- AdvancedAnalyticsScreen + AdvancedAnalyticsViewModel → `AdvancedAnalytics`
- CarbonFootprintScreen + CarbonFootprintViewModel → `CarbonFootprint`
- WarrantyTrackerScreen + WarrantyTrackerViewModel → `WarrantyTracker`
- PriceProtectionScreen + PriceProtectionViewModel → `PriceProtection`
- BillNegotiationScreen + BillNegotiationViewModel → `BillNegotiation`

#### AI & Search
- NaturalLanguageSearchScreen + NaturalLanguageSearchViewModel → `SmartSearch`
- AssistantSheet + AssistantViewModel → overlay / entry surface
- AiSettingsScreen + AiSettingsViewModel → assistant settings

#### Investments & Banking
- InvestmentPortfolioScreen + InvestmentViewModel → `InvestmentPortfolio`
- BankConnectionsScreen + BankConnectionsViewModel → `BankConnections`

#### Reminders & Challenges
- BillRemindersScreen + BillRemindersViewModel → `BillReminders`
- SpendingChallengesScreen + SpendingChallengesViewModel → `SpendingChallenges(showCreateDialog)`

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

## 2. VIEWMODELS

### By Category
**Main:** MainViewModel

**Shell destinations:** HomeViewModel, TransactionsViewModel, ReviewViewModel, BudgetViewModel, AnalyticsViewModel, SpendingMapViewModel

**Overlay / feature ViewModels:** AdvancedAnalyticsViewModel, AssistantViewModel, RecurringExpensesViewModel

**Features / routed surfaces:** 
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

## 3. NAVIGATION GRAPH

**File:** `ui/navigation/NavigationDestination.kt` (sealed class, type-safe)

### Shell Routes
```kotlin
- Home
- Transactions
- Analytics
- Review
- Budget
- SpendingMap
```

### Overlay / Feature Routes
- Assistant (overlay surface)
- AddExpense
- RecurringExpenses
- ScanReceipt
- SavingsGoals
- CarbonFootprint
- WarrantyTracker
- PriceProtection
- BillNegotiation
- SmartSearch
- ReceiptMatching
- InvestmentPortfolio
- BankConnections
- BillReminders
- SpendingChallenges(showCreateDialog)
- AdvancedAnalytics
- CashFlowCalendar
- LifestyleInflation
- SplitTemplates
- VisualSplitEditor (parameterized: expense, templateId)
- CurrencyManagement
- SubscriptionManagement
- TaxConfiguration
- ExportOptions
- ManualRecurringExpense
- SharedExpenseGroups
- BudgetForecasting (parameterized: budget)
- BudgetDetail (parameterized: categoryId/categoryName)
- AiSettings
- CategoryManagement

### Navigation Infrastructure
- **NavigationController.kt:** State management for navigation
- **FeatureConfig.kt:** Feature feature configuration
- **MainActivity.kt:** Navigation host and activity setup

### Gaps / notes
- Some surfaces are intentional overlays or settings entries rather than shell destinations.
- Debug screens remain conditional / development-only.
- Use `NavigationDestination` as the routing source of truth; this inventory is a compatibility map, not a tab list.

---

## 4. DOMAIN LAYER

### AI & Machine Learning
**Services:**
- AiArtifactRepository, AiCapabilityRouter, AiChatRepository
- AiEngagementRepository, AiEnvironmentMonitor, AiSettingsRepository
- AiWorkScheduler, CategorizationAssistService, DashboardBriefingService
- DedupeJudgeService, NotificationFallbackParser, QueryInterpretationService
- ReceiptAssistService, ReceiptItemCategorizationService
- ReviewExplanationService, ReviewPriorityScorer, SemanticDuplicateDetector

**Use Cases:**
- CategorizeReceiptItemsUseCase, DeliverProactiveBriefingNotificationUseCase
- DetectSemanticDuplicateUseCase, ExecuteFinancialQueryUseCase
- ExplainPendingReviewUseCase, GenerateDashboardBriefingUseCase
- GenerateTransactionInsightUseCase, GetAiRuntimeStatusUseCase
- InterpretFinancialQueryUseCase, JudgePendingReviewDuplicateUseCase
- MapFinancialQueryToNavigationUseCase, PrioritizeReviewItemsUseCase
- SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase
- SyncProactiveBriefingWorkUseCase
- AiArtifactFreshness, TransactionInsightInputBuilder
- ValidateBankStatementTransactionsUseCase
- Plus 7 InputBuilder classes

**Models/Policies:**
- AiArtifactPresentation, AiLoadState, AiModels
- AiPolicy, AiPolicyImpl, CaptureAssistModels
- FinancialQueryModels, NotificationParsingModels
- OnDeviceRuntimePresentation, ReceiptItemCategorizationModels
- ReviewPriorityModels, SemanticDuplicateModels, DefaultAiCapabilityRouter
- AiRuntimeStatusModels

### Analytics
- AdvancedAnalyticsEngine, AdvancedAnalyticsDashboard, AdvancedAnalyticsModels
- AnalyticsModels, AnomalyDetector, CategoryInsightEngine
- DayOfWeekAnalyzer, InsightsEngine, MerchantInsightEngine
- MonthlyComparisonCalculator, SpendingPaceCalculator
- SpendingThresholdCalculator, TotalsAggregationEngine, TransferDirectionAnalytics

### Budget & Forecasting
- BudgetCalculator, BudgetForecastingEngine, BudgetModels
- BudgetMonitor, BudgetRecommendationEngine, SharedBudgetManager
- DataQualityAssessor, HistoricalSpendingDistribution
- MonteCarloResult, MonteCarloSpendingSimulator
- Plus backup-related files

### Receipts & OCR
- ReceiptOcrService, ReceiptParser, BankStatementParser
- EnhancedMerchantExtractor, OcrLanguageProcessor
- OcrPreprocessingPipeline, ProcessReceiptUseCase

### Categorization
- CategorizationEngine, ContextualInferenceEngine
- CategoryKeywords, GreeklishNormalizer
- MerchantCanonicalizer, SemanticKeywordMatcher

### Transaction Parsing
- AppParserRegistry, GenericTransactionParser
- TransferDirectionDetector
- Parsers: GoogleWalletParser, GreekBankParser, RevolutParser, SmsParser

### Location & Geolocation
- AreaSpendingEngine, GeocodingResult, LocatedExpense
- LocationInsightsEngine, LocationModels, LocationResolver
- NearbyPoi, SpendingHeatmapEngine, TravelDetectionEngine

### Recurring & Scheduling
- RecurringExpenseEngine, RecurrenceCalculator, PlannedExpense models

### Savings & Investment
- AutomatedSavingsRuleEngine, SavingsGamificationEngine, SmartSavingsEngine
- InvestmentTracker, SmartBillNegotiationEngine
- NaturalLanguageSearchEngine, PriceProtectionTracker

### Subscriptions
- SubscriptionManagerEngine

### Groups & Splitting
- GroupTransactionCoordinator, SettlementCalculator
- SharedExpenseManager, EnhancedSplitManager, SplitCalculator

### Utilities
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

### Core Use Cases
- CategorizeExpenseUseCase, DetectDuplicateExpenseUseCase
- CalculateBudgetStatusUseCase, CalculateFinancialForecastUseCase
- ComputeDashboardWidgetsUseCase, DashboardDataProvider

### Additional Domain Packages
- **privacy/** - Privacy settings & data portability
- **transaction/** - Transaction parsing & validation
- **core/money/** - Money, currency, amount utilities
- **core/time/** - Time providers & period utilities
- **recurring/** - Recurring expense lifecycle
- **alerts/** - Anomaly & alert domain models
- **bank/** - Bank connection domain
- **business/** - Business expense reporting
- **carbon/** - Carbon footprint calculation
- **cashflow/** - Cash flow analysis
- **diagnostics/** - Debug & diagnostics
- **dto/** - Data transfer objects
- **reminder/** - Reminder domain models
- **workers/** - Worker domain definitions

---

## 5. REPOSITORIES

Actual repository inventory (interfaces and implementations); counts shift as implementations are added, renamed, or split.

### Core finance
- ExpenseRepository
- CategoryRepository
- BudgetRepository
- DashboardRepository
- AnalyticsRepository
- FinancialWeatherRepository

### AI/Chat
- AiArtifactRepositoryImpl
- AiChatRepositoryImpl
- AiEngagementRepositoryImpl
- AiSettingsRepositoryImpl

### Specialized
- AccountingExportRepository
- BusinessExpenseRepository
- CurrencySettingsRepositoryImpl
- DatabaseBackupRepositoryImpl
- ManualExpenseRepository
- MerchantCategoryRepository
- MerchantLocationRepository
- MerchantNormalizationRepository
- MerchantRulesRepository
- MultiCurrencyRepository
- NotificationProcessingPipeline
- NotificationRepository
- PlannedExpenseRepository
- ReceiptRepository
- RecommendationRepository
- RecurringExpenseRepository
- ReviewQueueRepository
- SavingsGoalRepository
- SourceStatsRepository
- UserCorrectionRepository
- WarrantyTrackerRepository
- WidgetStyleRepositoryImpl

### Current repositories not to omit
- GroupsRepository (interface) / GroupsRepositoryImpl (implementation)
- ManualRecurringExpenseRepository
- PromptStateRepository
- ReceiptItemCategorizationRepository
- SpendingChallengeRepository
- SubscriptionManagementRepository
- AnomalyAlertRepositoryImpl
- AutomatedSavingsRuleStateRepository
- SavingsContributionHistoryRepository
- DeterministicExpenseExportPager
- SharedExpenseDataPortAdapter
- PrivacySettingsRepositoryImpl

---

## 6. DATABASE (Version 117)

### Entities

**Core finance:**
- Expense, Category, Budget, ManualRecurringExpense
- PlannedExpense, SavingsGoal, ScannedReceipt

**Capture / review:**
- RawNotification, BlockedPackage, PendingReview
- UserCorrection, SourceStats

**Merchants / location:**
- MerchantCategory, MerchantCanonical, MerchantAlias
- MerchantLocation, MerchantLocationCorrection

**AI / assistant:**
- AiArtifactEntity, AiChatSessionEntity, AiChatMessageEntity
- RecommendationEntity, ReceiptItemCategorization

**Financial / planning:**
- ExchangeRate, Investment, InvestmentValue, BankConnection
- SubscriptionPriceHistory, SubscriptionUsage, MileageTracking
- BudgetForecast

**Groups / split:**
- ExpenseGroup, GroupMember, GroupExpense
- SplitTemplate, SplitItemAssignment

**Lifecycle / alerts / support:**
- Warranty, ReturnWindow, AnomalyAlert, PromptState
- HealthScoreHistory, SavingsSweepPlan, SubscriptionCandidate
- BudgetAdjustmentRecommendation, BudgetAdjustmentEvent
- SpendingPersonalityProfileEntity, StressForecastSnapshot
- EmailReceiptSource, SpendingChallengeEntity
- BackgroundJobRun, SourceStatsEvent, ReceiptEvent
- ReceiptExpenseLink, RecurringLifecycleEvent
- RecurringOccurrence, RecurringReminderDelivery
- PrivacyAuditEvent

### DAOs
One DAO per entity (mostly 1-to-1 mapping)
- **Deprecated:** RecurringExpenseDao (delegates to ManualRecurringExpenseDao)
- **Special:** MerchantNormalizationDao, BankConnectionDao
- BackgroundJobRunDao, SourceStatsEventDao, ReceiptEventDao
- ReceiptExpenseLinkDao, RecurringLifecycleEventDao
- RecurringOccurrenceDao, RecurringReminderDeliveryDao
- SpendingChallengeDao, PrivacyAuditDao

### Migration History
- Database Version: 117
- Migration methods: current chain in `AppDatabase.kt`
- Export schema: Enabled
- Type converters: Defined in `converter/Converters.kt`

---

## 7. DEPENDENCY INJECTION

### Core / Infrastructure
- **DatabaseModule** - Room database setup
- **DaoModule** - All DAO providers
- **DispatchersModule** - Coroutine dispatchers
- **TimeModule** - Time providers
- **ServiceModule** - App services
- **ReceiptParsingModule** - Receipt parsing wiring
- **NetworkModule** - Shared HTTP clients
- **NetworkQualifiers** - HTTP client qualifiers

### Feature Modules
- **AiModule** - AI/ML services
- **DashboardContractsModule** - Dashboard wiring
- **DashboardAnomalyModule** - Dashboard anomaly bindings
- **CashFlowModule** - Cash flow calculations
- **CurrencyModule** - Currency conversion
- **ExportModule** - Export functionality
- **GroupsModule** - Shared expenses
- **OcrImprovementsModule** - OCR & receipts
- **SavingsModule** - Savings engines
- **SavingsRepositoryBindingsModule** - Savings repository bindings
- **SubscriptionModule** - Subscriptions
- **TaxModule** - Tax estimation
- **NaturalLanguageModule** - Natural language search bindings
- **EmailIngestionModule** - Email receipt ingestion

### Specialized / Support
- **BackupRepositoryModule** - Backup/restore
- **SecurityModule** - Encryption & security
- **AlertsModule** - Anomaly/alert bindings
- **PrivacyModule** - Privacy settings bindings
- **LocationResolverPortsModule** - Location abstractions
- **EmptyStateModule** - Empty-state wiring
- **EmptyStatePresentationModule** - Empty-state presentation wiring
- **ApplicationScope** - App-scoped coroutine support
- **EmptyStateRegistryInitializer** - Empty-state bootstrap
- **Feature bindings** - Current feature modules bind via `@Inject` / `@Provides`

---

## 8. ANDROID MANIFEST

### Services
- **NotificationCaptureService**
  - Type: NotificationListenerService
  - Foreground: dataSync|location
  - Exported: true

### Receivers
- **BootReceiver** - BOOT_COMPLETED, MY_PACKAGE_REPLACED
- **ServiceRestartReceiver** - Service keep-alive

### Permissions (13)
- Foreground service (3): FOREGROUND_SERVICE, DATA_SYNC, LOCATION
- Notifications (2): POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED
- Camera (1): CAMERA permission + hardware feature
- Location (2): ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
- File access (2): READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE
- Network (2): INTERNET, ACCESS_NETWORK_STATE
- System (1): WAKE_LOCK

### Providers
- FileProvider for camera file sharing

---

## 9. UI COMPONENTS

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

## 10. STARTUP / BACKGROUND / SERVICES / WORKERS

### Startup / Background
1. **MainApplication** - App entry, Hilt + WorkManager bootstrap
2. **AppStartupDelegate** - Startup entry-point bootstrap
3. **AppStartupCoordinator** - Startup orchestration + lifecycle hooks
4. **AppBackgroundLifecycleObserver** - Background lifecycle observer

### Main Services
5. **NotificationCaptureService** - Notification listener
6. **AndroidNotificationService** - Notification management

### Recommendation System
7. RecommendationCacheService
8. RecommendationDeduplicator
9. RecommendationDismissalHandler
10. RecommendationInvalidator
11. RecommendationLifecycleManager
12. RecommendationStateManager

### Workers
13. **DailyBriefingWorker** - Proactive briefing delivery
14. **LocationBackfillWorker** - Location enrichment backfill
15. **MerchantKeyBackfillWorker** - Merchant key backfill
16. **WarrantyExpirationWorker** - Warranty expiry tracking
17. **ReceiptMatchingWorker** - Receipt matching background work
18. **BillReminderWorker** - Bill reminder background processing
19. **DataRetentionWorker** - Data retention policy enforcement
> **Note:** All 7 workers are paused during restore via `RestoreMaintenanceMode`.

### Utilities
18. TransactionFilterSerializer
19. NavigationTargetResolver

---

## 11. FEATURE IMPLEMENTATION CHECKLIST

### Confirmed Implemented
✅ Home/Dashboard  
✅ Transactions/History  
✅ Analytics variants  
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
✅ Recurring Expense Management  
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

### Screen Status Notes
- `RecurringExpensesScreen` remains a secondary recurring-management surface alongside `ManualRecurringExpenseScreen`.
- `AiSettingsScreen` is a routed settings surface.
- `CategoryScreen` is a routed category-management surface.

---

## 12. INTERNATIONALIZATION

- **Primary strings:** `values/strings.xml`
- **Localized variants:** present (for example, `values-es/strings.xml`)
- **Coverage:** screen string density varies by feature
- **Note:** exact string totals are intentionally not frozen here

---

## 13. ARCHITECTURAL COMPLIANCE

### Clean Architecture
✅ **Domain:** Primarily business logic; a few files still import Android APIs  
✅ **Data:** Repositories, entities, DAOs  
✅ **UI:** Screens, ViewModels, components  
✅ **DI:** Hilt modules (live `di/` set)

### Design Patterns
✅ Repository Pattern  
✅ ViewModel Pattern  
✅ Use Case Pattern  
✅ Sealed Class Navigation  
✅ Flow/StateFlow Reactive  
✅ Room Database Persistence  

### Database
✅ Version 117 with current migration chain  
✅ Export schema enabled  
✅ Type converters defined

---

## 14. KNOWN ISSUES & RECOMMENDATIONS

### Critical
1. Keep routed feature surfaces documented as routed unless the navigation map changes.
2. Keep overlay/settings screens documented separately from shell destinations.
3. Preserve compatibility notes only for truly legacy surfaces.

### Technical Debt
1. Review any duplicate feature surfaces before pruning them from navigation.
2. Keep inventory wording aligned with current route ownership.
3. Verify current code paths before classifying a screen as legacy.

### Recommendations
- Complete i18n with language variants.
- Document Phase structure more clearly.
- Add comprehensive integration tests.

---

## End of Inventory

**This inventory represents a comprehensive analysis of the ExpenseTracker codebase as of 2026-05-07.**
