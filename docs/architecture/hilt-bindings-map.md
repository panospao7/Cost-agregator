# Hilt Module Bindings Map

> Complete interface → implementation binding map for all 30 Hilt @Module files (+ 1 @EntryPoint).
>
> **Note:** `SubscriptionModule.kt` was deleted in 2026-05-09 refactoring — `SubscriptionManagerEngine`
> is auto-provided by its `@Singleton @Inject constructor`. Replaced in count by `WorkerModule.kt`.

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
Provides (58 DAOs):
  PlannedExpenseDao, SavingsGoalDao, RawNotificationDao, BlockedPackageDao,
  ExpenseDao, BudgetDao, ScannedReceiptDao, CategoryDao, MerchantCategoryDao,
  PendingReviewDao, UserCorrectionDao, SourceStatsDao, SourceStatsEventDao, RecurringExpenseDao,
  ManualRecurringExpenseDao, MerchantNormalizationDao, MerchantLocationDao,
  RecommendationDao, ReceiptItemCategorizationDao, WarrantyDao, ReturnWindowDao,
  WarrantyLifecycleEventDao, WarrantyReminderDeliveryDao,
  SubscriptionPriceHistoryDao, SubscriptionUsageDao, MileageTrackingDao,
  ExchangeRateDao, ExpenseGroupDao, GroupMemberDao, GroupExpenseDao,
  GroupSettlementDao,
  BudgetForecastDao, InvestmentDao, InvestmentValueDao,
  InvestmentTransactionDao,
  BankConnectionDao, BackgroundJobRunDao,
  SplitTemplateDao, SplitItemAssignmentDao, SubscriptionCandidateDao,
  BudgetAdjustmentDao, EmailReceiptDao, AnomalyAlertDao, HealthScoreHistoryDao,
  PromptStateDao, SpendingPersonalityProfileDao, StressForecastSnapshotDao,
  SavingsSweepPlanDao, SpendingChallengeDao, TransactionEventDao,
  ReceiptEventDao, ReceiptExpenseLinkDao, RecurringOccurrenceDao,
  RecurringReminderDeliveryDao, RecurringLifecycleEventDao, PrivacyAuditDao,
  GroupLifecycleEventDao, PipelineDiagnosticEventDao, OperationRunDao, OperationRunEventDao
Dependencies:
  AppDatabase

Additional DAOs provided via AiModule (3):
  AiArtifactDao, AiChatSessionDao, AiChatMessageDao

Total DAOs: 59 (56 + 3)
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

### `ApplicationScope` — `di/ApplicationScope.kt`
```
Provides nothing directly (qualifier definition only).
Defines @ApplicationScope qualifier annotation used by DispatchersModule.
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

### `WorkerModule` — `di/WorkerModule.kt`
```
Binds:
  WorkerRunLogger                             → WorkerRunLoggerImpl
  NotificationPermissionChecker               → AndroidNotificationPermissionChecker
```
`NotificationPermissionChecker` is injected into `WorkerExecutionGuard` to enforce
`WorkerGuardRequest.requiresNotificationPermission` (durable skip with
`NOTIFICATION_PERMISSION_DENIED` when notifications are disabled).

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
  CloudReceiptItemCategorizationService       → SecureKeyStorage + OkHttpClient + PrivacyGate + CloudPayloadRedactor
  CloudWarrantyExtractionService              → SecureKeyStorage + OkHttpClient + PrivacyGate + CloudPayloadRedactor
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

Auto-provided via @Inject constructor:
  GroupLifecycleCoordinator                   → @Singleton @Inject constructor (no Dagger module needed)
```

### `GroupsModule` — `di/GroupsModule.kt` (continued)
```
Auto-provided via @Inject constructor:
  StaticMarketRateProvider                    → @Singleton @Inject constructor (seed-data impl of MarketRateProvider, no Dagger module needed)
  GroupLifecycleCoordinator                   → @Singleton @Inject constructor (domain interface, no Dagger cycle)
  GroupBalanceCalculator                      → @Singleton @Inject constructor (per-member net balance calculator)
```

Note: `MarketRateProvider` interface is consumed by `SmartBillNegotiationEngine`; `StaticMarketRateProvider` is the single `@Inject`-constructor implementation, satisfying Hilt's auto-binding rules for single-implementation interfaces.

### Analytics Engines — Auto-provided (no module needed)

All three engines are `@Singleton @Inject` with constructor-injected dependencies — Hilt auto-discovers them without a `@Module`:

```
Auto-provided:
  DailyBucketEngine                           → @Singleton @Inject constructor (domain/analytics/DailyBucketEngine.kt)
  BudgetVsActualEngine                        → @Singleton @Inject constructor (domain/analytics/BudgetVsActualEngine.kt)
```

`AnalyticsInputAssembler` is also `@Singleton @Inject` with constructor-injected dependencies (`ExpenseRepository`, `AnalyticsCurrencyNormalizer`, `CurrencySettingsRepository`, `TimeProvider`, `CategoryRepository`) — no module needed, Hilt satisfies all dependencies automatically.

### Barrier & Registry Components — Auto-provided (no module needed)
```
Auto-provided:
  DatabaseReadBarrier                         → @Singleton @Inject (data/backup/DatabaseReadBarrier.kt)
  DatabaseWriteBarrier                        → @Singleton @Inject (data/backup/DatabaseWriteBarrier.kt)
  WorkerRegistry                              → Kotlin `object` (domain/workers/WorkerRegistry.kt)
  AccountingExportPolicy                      → @Inject constructor (domain/export/AccountingExportPolicy.kt)
  RecurringRuleLifecycleCoordinator           → @Singleton @Inject (domain/recurring/lifecycle/)
  NetCashflowBalanceProvider                  → @Singleton @Inject (domain/forecasting/NetCashflowBalanceProvider.kt)
  ReceiptMatchLifecycleService                → @Singleton @Inject (domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt)
  RecurringPlanProjectionService              → @Singleton @Inject (domain/recurring/RecurringPlanProjectionService.kt)
```

### `TaxModule` — `di/TaxModule.kt`
```
Provides:
  TaxConfiguration                            → GreeceTaxConfiguration

Auto-provided via @Inject constructor:
  DemoTaxRateProvider                         → @Singleton @Inject constructor (seed-data impl of TaxRateProvider, no Dagger module needed)
```

Note: `TaxRateProvider` interface is consumed by `TaxEstimator`; `DemoTaxRateProvider` is the single `@Inject`-constructor implementation, satisfying Hilt's auto-binding rules for single-implementation interfaces.

### `ReminderSettingsModule` — `di/ReminderSettingsModule.kt`
```
Binds:
  BillReminderSettingsRepository              → BillReminderSettingsRepositoryImpl
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

### `DiagnosticsModule` — `di/DiagnosticsModule.kt`
Binds:
- `DiagnosticEventWriter` → `CompositeDiagnosticEventWriter`
- `TransactionLifecycleEventWriter` → `RoomTransactionLifecycleEventWriter`
- `ReceiptLifecycleEventWriter` → `RoomReceiptLifecycleEventWriter`
- `RecurringLifecycleEventWriter` → `RoomRecurringLifecycleEventWriter`
- `OperationRunRecorder` → `CompositeOperationRunRecorder`
- `WorkerRunLogger` → `WorkerRunLoggerImpl`
- `DiagnosticsRepository` → `DiagnosticsRepositoryImpl`

---

### `RetentionModule` — `di/RetentionModule.kt`
`@Module @InstallIn(SingletonComponent::class)` providing:
- `RetentionRegistry` with 5 registered `RetentionTarget` entries: `raw_notifications`, `scanned_receipts.rawOcrText`, `ai_artifacts`, `ai_chat_messages`, `email_receipt_sources`

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

---
**Stats:** 31 Hilt @Module files · 65+ repositories · 62 DAOs (58 DaoModule + 3 AiModule + 1 unbound) · 64 entities · DB v141
