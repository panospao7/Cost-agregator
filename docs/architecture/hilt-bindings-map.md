# Hilt Module Bindings Map

> Complete interface → implementation binding map for all 28 Hilt modules.

---

## 1. Core Modules

### `DatabaseModule` — `di/DatabaseModule.kt`
```
Provides:
  AppDatabase                                 → AppDatabase (Room)
  GroupTransactionCoordinatorInterface        → GroupTransactionCoordinator
Dependencies:
  Context, ExpenseGroupDao, GroupMemberDao, GroupExpenseDao, ExpenseDao,
  TransactionLifecycleCoordinator, @IoDispatcher
```

### `DaoModule` — `di/DaoModule.kt`
```
Provides (54 DAOs):
  PlannedExpenseDao, SavingsGoalDao, RawNotificationDao, BlockedPackageDao,
  ExpenseDao, BudgetDao, ScannedReceiptDao, CategoryDao, MerchantCategoryDao,
  PendingReviewDao, UserCorrectionDao, SourceStatsDao, RecurringExpenseDao,
  ManualRecurringExpenseDao, MerchantNormalizationDao, MerchantLocationDao,
  RecommendationDao, ReceiptItemCategorizationDao, WarrantyDao, ReturnWindowDao,
  SubscriptionPriceHistoryDao, SubscriptionUsageDao, MileageTrackingDao,
  ExchangeRateDao, ExpenseGroupDao, GroupMemberDao, GroupExpenseDao,
  BudgetForecastDao, InvestmentDao, InvestmentValueDao, BankConnectionDao,
  SplitTemplateDao, SplitItemAssignmentDao, SubscriptionCandidateDao,
  BudgetAdjustmentDao, EmailReceiptDao, AnomalyAlertDao, HealthScoreHistoryDao,
  PromptStateDao, SpendingPersonalityProfileDao, StressForecastSnapshotDao,
  SavingsSweepPlanDao, SpendingChallengeDao, TransactionEventDao,
  ReceiptEventDao, ReceiptExpenseLinkDao, RecurringOccurrenceDao,
  RecurringReminderDeliveryDao, RecurringLifecycleEventDao, PrivacyAuditDao
Dependencies:
  AppDatabase
```

### `DispatchersModule` — `di/DispatchersModule.kt`
```
Provides:
  @IoDispatcher CoroutineDispatcher           → Dispatchers.IO
  @DefaultDispatcher CoroutineDispatcher      → Dispatchers.Default
  @ApplicationScope CoroutineScope            → SupervisorJob + IO
Dependencies:
  @IoDispatcher (for @ApplicationScope)
```

### `TimeModule` — `di/TimeModule.kt`
```
Binds:
  TimeProvider                                → SystemTimeProvider
Dependencies:
  (none)
```

### Entry Points Updated (2026-05-06)
```
BroadcastReceivers:
  SnoozeReminderReceiver                      → @AndroidEntryPoint + injected RecurringReminderDeliveryDao, TimeProvider, RestoreMaintenanceMode
  DismissReminderReceiver                     → @AndroidEntryPoint + injected RecurringReminderDeliveryDao, TimeProvider, RestoreMaintenanceMode

Lifecycle coordinator wiring:
  TransactionLifecycleCoordinator             → now consumes CurrencySettingsRepository for home-currency snapshot resolution
```

### `ServiceModule` — `di/ServiceModule.kt`
```
Provides:
  Gson                                        → GsonBuilder().create()
  NotificationService                         → AndroidNotificationService
  GeocodingService                            → CompositeGeocodingService
  NearbyPoiService                            → OverpassNearbyService
  ForegroundLocationProvider                  → AndroidForegroundLocationProvider
  NavigationTargetResolver                    → NavigationTargetResolverImpl
  WidgetStyleRepository                       → WidgetStyleRepositoryImpl
  SpeechInputGateway                          → AndroidSpeechInputGateway
  StringDistanceUtils                         → StringDistanceUtils
Dependencies:
  PrivacyGate (for CompositeGeocodingService EXTERNAL_GEOCODING check),
  various geocoding services, Android system services
```

---

## 2. AI Modules

### `AiModule` — `di/AiModule.kt`
```
Binds:
  AiSettingsRepository                        → AiSettingsRepositoryImpl
  AiArtifactRepository                        → AiArtifactRepositoryImpl
  AiEngagementRepository                      → AiEngagementRepositoryImpl
  AiChatRepository                            → AiChatRepositoryImpl
  AiPolicy                                    → AiPolicyImpl
  AiCapabilityRouter                          → DefaultAiCapabilityRouter
  AiEnvironmentMonitor                        → DefaultAiEnvironmentMonitor
  AiWorkScheduler                             → AiWorkSchedulerImpl
  DashboardBriefingService                    → HybridDashboardBriefingService
  ReviewExplanationService                    → HybridReviewExplanationService
  ReceiptAssistService                        → SmartReceiptAssistService
  CategorizationAssistService                 → HybridCategorizationAssistService
  DedupeJudgeService                          → HybridDedupeJudgeService
  QueryInterpretationService                  → HybridQueryInterpretationService
  ReceiptItemCategorizationService            → HybridReceiptItemCategorizationService
  NotificationFallbackParser                  → OnDeviceNotificationParser
  ReviewPriorityScorer                        → OnDeviceReviewPriorityScorer
  SemanticDuplicateDetector                   → OnDeviceSemanticDuplicateDetector
  RedactionSanitizer                          → DefaultRedactionSanitizer

Provides:
  AiArtifactDao                               → database.aiArtifactDao()
  AiChatSessionDao                            → database.aiChatSessionDao()
  AiChatMessageDao                            → database.aiChatMessageDao()
  OnDeviceReceiptItemCategorizationService    → new instance
  CloudReceiptItemCategorizationService       → SecureKeyStorage + OkHttpClient + PrivacyGate
  CloudWarrantyExtractionService              → SecureKeyStorage + OkHttpClient + PrivacyGate
```

### `OcrImprovementsModule` — `di/OcrImprovementsModule.kt`
```
Provides:
  EnhancedMerchantExtractor                   → EnhancedMerchantExtractor
  OcrLanguageProcessor                        → OcrLanguageProcessor
  OcrPreprocessingPipeline                    → OcrPreprocessingPipeline
```

### `NaturalLanguageModule` — `di/NaturalLanguageModule.kt`
```
Binds:
  NaturalLanguageExpenseQueryRepository       → NaturalLanguageExpenseQueryRepositoryImpl
```

---

## 3. Feature Modules

### `CashFlowModule` — `di/CashFlowModule.kt`
```
Provides:
  CashFlowCalculator                          → CashFlowCalculator(
      ExpenseRepository, MergedRecurringPatternsProvider, TimeProvider,
      RecurringLifecycleCoordinator, RecurringOccurrenceDao)
```

### `CurrencyModule` — `di/CurrencyModule.kt`
```
Binds:
  CurrencySettingsRepository                  → CurrencySettingsRepositoryImpl
  CurrencyRatesRepository                     → CurrencyRatesRepositoryImpl
  ExchangeRateStore                           → ExchangeRateStoreAdapter

Note: CurrencyConverter and MultiCurrencyRepository use @Inject constructors
```

### `DashboardContractsModule` — `di/DashboardContractsModule.kt`
```
Binds (all from DashboardContractsAdapter):
  DashboardExpenseRepository                  → DashboardContractsAdapter
  DashboardCategoryRepository                 → DashboardContractsAdapter
  DashboardBudgetRepository                   → DashboardContractsAdapter
  DashboardReviewQueueRepository              → DashboardContractsAdapter
  DashboardFinancialWeatherRepository         → DashboardContractsAdapter
  DashboardSavingsGoalRepository              → DashboardContractsAdapter
  DashboardAnalyticsRepository                → DashboardContractsAdapter
```

### `DashboardAnomalyModule` — `di/DashboardAnomalyModule.kt`
```
Binds (both from AnomalyAlertRepositoryImpl):
  AnomalyAlertRepository (domain)             → AnomalyAlertRepositoryImpl
  AnomalyAlertRepository (dashboard)          → AnomalyAlertRepositoryImpl
```

### `SavingsModule` — `di/SavingsModule.kt`
```
Provides:
  SmartSavingsEngine                          → SmartSavingsEngine(
      ExpenseRepository, CategoryRepository, BudgetRepository, BudgetCalculator,
      MonteCarloSpendingSimulator, TimeProvider, AnalyticsCurrencyNormalizer,
      CashFlowCalculator, SpendingThresholdCalculator)
  AutomatedSavingsRuleStateRepository         → DataStore + TimeProvider
  SavingsContributionHistoryRepository        → DataStore + TimeProvider
  AutomatedSavingsRuleEngine                  → ExpenseRepository + CategoryRepository + ...
  SavingsGamificationEngine                   → DomainSavingsGoalRepository + ...
```

### `SavingsRepositoryBindingsModule` — `di/SavingsRepositoryBindingsModule.kt`
```
Binds:
  DomainSavingsGoalRepository                 → SavingsGoalRepository (data layer)
```

### `GroupsModule` — `di/GroupsModule.kt`
```
Provides:
  GroupsRepository                            → GroupsRepositoryImpl
  SharedExpenseDataPort                       → SharedExpenseDataPortAdapter
  DeleteGroupMemberUseCase                    → DeleteGroupMemberUseCase(repository)
  DeleteGroupUseCase                          → DeleteGroupUseCase(repository)
  AddGroupExpenseUseCase                      → AddGroupExpenseUseCase(repository, timeProvider)
```

### `SubscriptionModule` — `di/SubscriptionModule.kt`
```
Provides:
  SubscriptionManagerEngine                   → SubscriptionManagerEngine
```

### `TaxModule` — `di/TaxModule.kt`
```
Provides:
  TaxConfiguration                            → GreeceTaxConfiguration
```

### `ExportModule` — `di/ExportModule.kt`
```
Provides:
  QuickBooksIIFExporter                       → QuickBooksIIFExporter
  XeroCSVExporter                             → XeroCSVExporter
  FreshBooksExporter                          → FreshBooksExporter
```

---

## 4. Infrastructure Modules

### `NetworkModule` — `di/NetworkModule.kt`
```
Provides:
  @LocationHttpClient OkHttpClient            → OkHttpClient (10s connect, 20s read, 20MB cache)
  @CloudAiHttpClient OkHttpClient             → OkHttpClient (15s connect, 45s read/write)
```

### `SecurityModule` — `di/SecurityModule.kt`
```
Provides:
  SecureKeyStorage                            → SecureKeyStorage(context)
```

### `PrivacyModule` — `di/PrivacyModule.kt`
```
Binds:
  PrivacySettingsRepository                   → PrivacySettingsRepositoryImpl

Provides:
  PrivacyGate                                 → CompositePrivacyGate(
      NotificationPrivacyGate, LocationPrivacyGate, CloudAiPrivacyGate, BackupPrivacyGate)
  PrivacyAuditLogger                          → PrivacyAuditLoggerImpl
```

### `BackupRepositoryModule` — `di/BackupRepositoryModule.kt`
```
Provides:
  DatabaseBackupRepository                    → DatabaseBackupRepositoryImpl
  RestoreMaintenanceMode                      → @Inject constructor (@Singleton, auto-discovered)
```

### `ParserModule` — `di/ParserModule.kt`
```
Provides:
  GreekBankParser                             → GreekBankParser(
      CurrencyNormalizer, MerchantCleaner, CurrencySettingsRepository)  // homeCurrency derived via runBlocking
```

### `ReceiptParsingModule` — `di/ReceiptParsingModule.kt`
```
Binds:
  MerchantRulesPolicy                         → MerchantRulesRepository
```

### `EmptyStateModule` — `di/EmptyStateModule.kt`
```
Multibinds:
  EmptyStateRegistryInitializer               → Set<EmptyStateRegistryInitializer>
```

### `EmptyStatePresentationModule` — `ui/components/emptystate/EmptyStatePresentationModule.kt`
```
Binds:
  @IntoSet EmptyStateRegistryInitializer           → DefaultEmptyStateRegistryInitializer

Provides:
  ContextualActionRegistry                          → ContextualActionRegistry(
      Set<EmptyStateRegistryInitializer>)
```

### `EmailIngestionModule` — `di/EmailIngestionModule.kt`
```
Provides:
  AmazonReceiptParser                         → AmazonReceiptParser
  UberReceiptParser                           → UberReceiptParser
  AppleReceiptParser                          → AppleReceiptParser
```

### `LocationResolverPortsModule` — `di/LocationResolverPortsModule.kt`
```
Binds:
  LocationCachePort                           → MerchantLocationCachePortAdapter
  MerchantClusterPort                         → ExpenseMerchantClusterPortAdapter
```

---

## 5. Entry Points

### `AppStartupDelegate` — `startup/AppStartupDelegate.kt`
```
@EntryPoint @InstallIn(SingletonComponent::class):
  AppStartupCoordinator                       → obtained via EntryPointAccessors
```

---

## Module Dependency Graph (Simplified)

```
DatabaseModule ──► DaoModule ──► All Repositories ──► ViewModels
     │                  │
     │                  ▼
     │          Feature Modules
     │    (Savings, Budget, Groups, etc.)
     │                  │
     ▼                  ▼
TimeModule ──────► Domain Services
DispatchersModule     │
ServiceModule         ▼
NetworkModule ──► AI Providers, Geocoding
SecurityModule        │
PrivacyModule ───────► PrivacyGate ──► All Gated Services
BackupRepositoryModule ──► Backup/Restore
```
