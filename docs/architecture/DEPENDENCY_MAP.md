# ExpenseTracker Dependency Map

> **Auto-generated dependency analysis.**  
> Answers: *"If this file breaks, what flows depend on it?"*

---

## Table of Contents

1. [Notification Capture Dependency Map](#1-notification-capture-dependency-map)
2. [Transaction Lifecycle Dependency Map](#2-transaction-lifecycle-dependency-map)
3. [Receipt Lifecycle Dependency Map](#3-receipt-lifecycle-dependency-map)
4. [Recurring Lifecycle Dependency Map](#4-recurring-lifecycle-dependency-map)
5. [Backup/Restore Dependency Map](#5-backuprestore-dependency-map)
6. [Privacy Gate Dependency Map](#6-privacy-gate-dependency-map)
7. [Dashboard/Analytics/Currency Dependency Map](#7-dashboardanalyticscurrency-dependency-map)
8. [Worker/Startup Dependency Map](#8-workerstartup-dependency-map)
9. [Hilt Module Map](#9-hilt-module-map)
10. [DAO/Repository Map](#10-daorepository-map)

---

## 1. Notification Capture Dependency Map

```
Android NotificationListener
  │
  ▼
NotificationCaptureService              [service/NotificationCaptureService.kt]
  │
  ├──► NotificationFilter               [domain/privacy/NotificationPrivacyGate.kt]
  │     └──► PrivacyCapability.NOTIFICATION_CAPTURE
  │
  ├──► PrivacyGate.check()              [domain/privacy/CompositePrivacyGate.kt]
  │
  ├──► RestoreMaintenanceMode            [data/backup/RestoreMaintenanceMode.kt]
  │     └── isActive() → pauses capture during restore
  │
  ▼
NotificationProcessingPipeline           [data/repository/NotificationProcessingPipeline.kt]
  │
  ├──► AppParserRegistry                 [domain/parser/AppParserRegistry.kt]
  │     ├── GreekBankParser              [domain/parser/parsers/GreekBankParser.kt]
  │     ├── RevolutParser                [domain/parser/parsers/RevolutParser.kt]
  │     ├── GoogleWalletParser           [domain/parser/parsers/GoogleWalletParser.kt]
  │     ├── SmsParser                    [domain/parser/parsers/SmsParser.kt]
  │     └── GenericTransactionParser     [domain/parser/GenericTransactionParser.kt]
  │
  ├──► ConfidenceRouter                  [domain/intelligence/ConfidenceRouter.kt]
  │
  ├──► TransactionClassifier             [domain/intelligence/TransactionClassifier.kt]
  │
  ├──► CategorizationEngine              [domain/categorization/CategorizationEngine.kt]
  │
  ▼
NotificationRepository                   [data/repository/NotificationRepository.kt]
  │
  ├──► RawNotificationDao
  ├──► BlockedPackageDao
  ├──► ExpenseDao
  ├──► PendingReviewDao
  ├──► UserCorrectionDao
  ├──► SourceStatsDao
  │
  ▼
ReviewQueueRepository                    [data/repository/ReviewQueueRepository.kt]
  │
  ├──► PendingReviewDao
  ├──► RawNotificationDao
  ├──► ExpenseDao
  ├──► SourceStatsDao
  ├──► ReceiptLinkService
  ├──► TransactionLifecycleCoordinator
  ├──► BudgetMonitor
  ├──► AppParserRegistry
  └──► HybridExpenseClassifier
       │
       ▼
  TransactionLifecycleCoordinator.createExpense()
       │
       ▼
  ExpenseDao / TransactionEventDao
```

### Consumer Classes (what depends on notification capture)

| Consumer | File | Dependency |
|----------|------|------------|
| `TransactionsViewModel` | `ui/screens/transactions/TransactionsViewModel.kt` | `NotificationRepository` |
| `ReviewViewModel` | `ui/screens/review/ReviewViewModel.kt` | `NotificationRepository`, `ReviewQueueRepository` |
| `DebugViewModel` | `ui/screens/debug/DebugViewModel.kt` | `NotificationRepository` |
| `CategorizationDebugViewModel` | `ui/screens/debug/CategorizationDebugViewModel.kt` | `CategorizationEngine` |

---

## 2. Transaction Lifecycle Dependency Map

```
ALL expense creation paths route through:
  AddExpenseViewModel / ReceiptScanViewModel / ReviewViewModel /
  GroupsRepositoryImpl / BankApiIntegration / EmailReceiptIngestionService ...
       │
       ▼
TransactionLifecycleCoordinator          [domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt]
       │
       ├──► Validate (CreateExpenseRequest)
       ├──► Normalize (MerchantNormalizer, CategoryLookup)
       ├──► Dedupe (DuplicateDetectionPolicy)
       ├──► insertAtomic (ExpenseDao) — ACID via withTransaction
       ├──► Event log (TransactionEventDao.insert())
       │
       ▼
TransactionSideEffectDispatcher          [domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt]
       │
        ├──► BudgetMonitor.checkBudget()
        ├──► AnomalyAlertOrchestrator.checkAndAlert()
        └──► MerchantCategoryRepository.learnPattern()
```

### Full Call Chain

```
ViewModel
  └──► ManualExpenseRepository / ExpenseRepository
        └──► TransactionLifecycleCoordinator
              ├──► ExpenseDao
              ├──► TransactionEventDao
              ├──► BudgetMonitor → BudgetDao, CategoryDao
              ├──► AnomalyDetector → AnomalyAlertDao
              ├──► RecurringLifecycleCoordinator
              │     ├──► RecurringOccurrenceDao
              │     └──► RecurringReminderDeliveryDao
              └──► Side effects dispatcher
```

### Consumer Classes (10+ callers of TransactionLifecycleCoordinator)

| Consumer | File | Path |
|----------|------|------|
| `ManualExpenseRepository` | `data/repository/ManualExpenseRepository.kt` | Manual entry |
| `ReviewQueueRepository` | `data/repository/ReviewQueueRepository.kt` | Review approval |
| `ReceiptRepository` | `data/repository/ReceiptRepository.kt` | Receipt scan |
| `ExpenseRepository` | `data/repository/ExpenseRepository.kt` | Core expense CRUD |
| `GroupTransactionCoordinator` | `data/database/GroupTransactionCoordinator.kt` | Atomic group ops |
| `EmailReceiptIngestionService` | `data/email/EmailReceiptIngestionService.kt` | Email receipts |
| `BankApiIntegration` | `domain/bank/BankApiIntegration.kt` | Bank sync |

---

## 3. Receipt Lifecycle Dependency Map

```
ReceiptScanScreen / ReviewScreen / EmailReceiptIngestionService
       │
       ▼
ReceiptLifecycleCoordinator              [domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt]
       │
       ├──► ReceiptInputValidator        — URI/MIME/size validation
       ├──► ReceiptAssetStore            — File persistence + SHA-256 hash
       ├──► ReceiptOcrService            — OCR extraction
       ├──► ReceiptParser                — Structured parsing
       ├──► ReceiptDuplicateDetector     — 3-signal dedup (hash/text/semantic)
       ├──► ScannedReceiptDao            — Save entity
       ├──► ReceiptEventDao              — Lifecycle event log
       │
       ▼
ReceiptSideEffectDispatcher              [domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt]
       │
       ├──► AutoCreateWarrantyFromReceiptUseCase
       │     └──► WarrantyDao, ReturnWindowDao
       ├──► ReceiptItemCategorizationService
       │     └──► ReceiptItemCategorizationDao
       ├──► ReceiptTransactionMatcher    [domain/receiptmatching/ReceiptTransactionMatcher.kt]
       └──► PriceProtectionTracker       [domain/price/PriceProtectionTracker.kt]

ReceiptLinkService                        [domain/receipt/lifecycle/ReceiptLinkService.kt]
       │
       ├──► ReceiptExpenseLinkDao        — Many-to-many link table
       ├──► ReceiptEventDao              — Link/unlink audit events
       └──► ExpenseDao                   — Cross-reference
```

### Entity & DAO Flow

```
Receipt Source (Camera/Gallery/Email/File)
  → ScannedReceipt (entity) → ScannedReceiptDao
  → ReceiptEvent (entity) → ReceiptEventDao
  → ReceiptExpenseLink (entity) → ReceiptExpenseLinkDao
  → Expense (entity) → ExpenseDao
  → ReceiptItemCategorization (entity) → ReceiptItemCategorizationDao
```

### Consumer Classes

| Consumer | File | Dependency |
|----------|------|------------|
| `ReceiptScanViewModel` | `ui/screens/receiptscan/ReceiptScanViewModel.kt` | `ReceiptLifecycleCoordinator`, `ReceiptRepository` |
| `ReviewViewModel` | `ui/screens/review/ReviewViewModel.kt` | `ReceiptLifecycleCoordinator`, `ReceiptRepository` |
| `ReceiptMatchingViewModel` | `ui/screens/receiptmatching/ReceiptMatchingViewModel.kt` | `ReceiptRepository` |
| `ReceiptRepository` | `data/repository/ReceiptRepository.kt` | `ReceiptLifecycleCoordinator`, `ReceiptLinkService` |
| `WarrantyTrackerRepository` | `data/repository/WarrantyTrackerRepository.kt` | `AutoCreateWarrantyFromReceiptUseCase` |
| `DashboardContractsAdapter` | `data/repository/DashboardContractsAdapter.kt` | Receipt counts |

---

## 4. Recurring Lifecycle Dependency Map

```
RecurringExpensesScreen
  │
  ▼
RecurringLifecycleCoordinator             [domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt]
  │
  ├──► RecurringOccurrenceExpander        — Expand rule → occurrence candidates
  ├──► OccurrenceConflictResolver         — Resolve candidates vs actual expenses
  ├──► RecurringOccurrenceMaterializer    — Persist + create reminders
  │     │
  │     ├──► RecurringOccurrenceDao       — INSERT IGNORE / UPDATE
  │     ├──► RecurringReminderDeliveryDao — Reminder rows
  │     └──► RecurringLifecycleEventDao   — Audit log
  │
  ▼
RecurringPlanProjectionService            [domain/recurring/RecurringPlanProjectionService.kt]
  │
  └──► PlannedExpenseDao                  — Materialise planned expenses

TransactionLifecycleCoordinator
  └──► RecurringLifecycleCoordinator.linkExpenseToOccurrence()  — auto-link hook
```

### Consumer Classes

| Consumer | File | Dependency |
|----------|------|------------|
| `RecurringExpensesViewModel` | `ui/screens/recurring/RecurringExpensesScreen.kt` | `RecurringExpenseRepository` |
| `ManualRecurringExpenseViewModel` | `ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt` | `ManualRecurringExpenseRepository` |
| `HomeViewModel` | `ui/screens/home/HomeViewModel.kt` | `PlannedExpenseRepository` |
| `BudgetViewModel` | `ui/screens/budget/BudgetViewModel.kt` | Offset engine |
| `FinancialWeatherRepository` | `data/repository/FinancialWeatherRepository.kt` | `RecurringExpenseRepository`, `PlannedExpenseRepository` |
| `CashFlowCalculator` | `domain/cashflow/CashFlowCalculator.kt` | `RecurringLifecycleCoordinator` |
| `ForecastInputAssembler` | `domain/forecasting/ForecastInputAssembler.kt` | `RecurringLifecycleCoordinator` |
| `BillReminderWorker` | `service/reminder/BillReminderWorker.kt` | `RecurringLifecycleCoordinator.getDueReminders()` |

---

## 5. Backup/Restore Dependency Map

```
BackupRestoreScreen (UI)
  │
  ▼
DatabaseBackupRepositoryImpl              [data/repository/DatabaseBackupRepositoryImpl.kt]
  │
  ├──► CostbackupBundle                   — AES-256-GCM encrypted ZIP
  │     ├── Header (metadata)
  │     ├── Manifest (file list + checksums)
  │     ├── Database (AppDatabase backup)
  │     ├── Receipt images (from ReceiptAssetStore)
  │     └── Checksums (SHA-256)
  │
  ├──► BackupEncryptionService            — AES-256-GCM / PBKDF2
  ├──► ExportAnonymizer                   — Strips raw OCR/notification text
  ├──► PrivacyGate.check(RAWBACKUP_EXPORT | ENCRYPTED_BACKUP)
  ├──► RestoreJournal                     — Crash-safe 8-state journal
  └──► RestoreMaintenanceMode             — Pauses 7 workers during restore
       │
       ▼
  TransactionLifecycleCoordinator         — Restore uses SKIP_FOR_DEBUG_RESTORE dedup mode

AppStartupCoordinator
  └──► checkRestoreJournal()              — Crash recovery on every startup
       └──► RestoreJournal.checkAndRecover()
            └──► RecoveryResult states
```

### Consumers

| Consumer | File | Dependency |
|----------|------|------------|
| `BackupRestoreViewModel` | `ui/screens/backup/BackupRestoreViewModel.kt` | `DatabaseBackupRepository` |
| `AppStartupCoordinator` | `startup/AppStartupCoordinator.kt` | `RestoreJournal`, `RestoreMaintenanceMode` |
| `NotificationCaptureService` | `service/NotificationCaptureService.kt` | `RestoreMaintenanceMode.isActive()` |
| All 7 workers | Various | `RestoreMaintenanceMode` pause check |

### Workers paused during restore

| Worker | File | Normal Schedule |
|--------|------|-----------------|
| `DailyBriefingWorker` | `data/ai/worker/DailyBriefingWorker.kt` | Every 24h |
| `LocationBackfillWorker` | `data/location/LocationBackfillWorker.kt` | Every 12h |
| `MerchantKeyBackfillWorker` | `data/location/MerchantKeyBackfillWorker.kt` | One-shot |
| `WarrantyExpirationWorker` | `service/warranty/WarrantyExpirationWorker.kt` | Every 24h |
| `BillReminderWorker` | `service/reminder/BillReminderWorker.kt` | Every 6h |
| `ReceiptMatchingWorker` | `service/receiptmatching/ReceiptMatchingWorker.kt` | Every 2h |
| `DataRetentionWorker` | `data/privacy/DataRetentionWorker.kt` | Every 24h |

---

## 6. Privacy Gate Dependency Map

```
PrivacySettingsScreen (UI)
  │
  ▼
PrivacySettingsViewModel                  [ui/screens/privacysettings/PrivacySettingsViewModel.kt]
  │
  ▼
PrivacySettingsRepositoryImpl             [data/privacy/PrivacySettingsRepositoryImpl.kt]
  │  (DataStore-backed, 10 toggles + 2 retention settings)
  │
  ▼ (read by)
CompositePrivacyGate                      [domain/privacy/CompositePrivacyGate.kt]
  │
  ├──► NotificationPrivacyGate            — NOTIFICATION_CAPTURE, NOTIFICATION_PACKAGE_ALLOWLIST
  ├──► LocationPrivacyGate                — EXTERNAL_GEOCODING, BACKGROUND_LOCATION_BACKFILL, GPS, OVERPASS
  ├──► CloudAiPrivacyGate                 — CLOUD_AI_* capabilities, RECEIPT_IMAGE_CLOUD_UPLOAD
  └──► BackupPrivacyGate                  — RAWBACKUP_EXPORT, ENCRYPTED_BACKUP
       │
       ▼
  PrivacyDecision (Allowed | Denied)

PrivacyAuditLogger                        [domain/privacy/PrivacyAuditLogger.kt]
  │  (logs every gate check → PrivacyAuditEvent entity → PrivacyAuditDao)
  │
  ▼
PrivacyAuditEvent → PrivacyAuditDao       [data/database/entity + dao]

RedactionSanitizer                        [domain/privacy/RedactionSanitizer.kt]
  │  (PII redaction before cloud AI calls)
  │
  ▼
  Used by: CloudReceiptItemCategorizationService, CloudDashboardBriefingService, etc.
```

### Gate Consumers (who calls PrivacyGate.check())

| Capability | Called By | File |
|-----------|-----------|------|
| `NOTIFICATION_CAPTURE` | `NotificationCaptureService` | `service/NotificationCaptureService.kt` |
| `CLOUD_AI_RECEIPT_ASSIST` | `SmartReceiptAssistService` | `data/ai/provider/SmartReceiptAssistService.kt` |
| `CLOUD_AI_CATEGORIZATION_ASSIST` | `HybridCategorizationAssistService` | `data/ai/provider/HybridCategorizationAssistService.kt` |
| `CLOUD_AI_DEDUPE_JUDGE` | `HybridDedupeJudgeService` | `data/ai/provider/HybridDedupeJudgeService.kt` |
| `CLOUD_AI_BRIEFING` | `HybridDashboardBriefingService` | `data/ai/provider/HybridDashboardBriefingService.kt` |
| `CLOUD_AI_REVIEW_EXPLANATION` | `HybridReviewExplanationService` | `data/ai/provider/HybridReviewExplanationService.kt` |
| `CLOUD_AI_QUERY_INTERPRETATION` | `HybridQueryInterpretationService` | `data/ai/provider/HybridQueryInterpretationService.kt` |
| `EXTERNAL_GEOCODING` | `CompositeGeocodingService` | `data/location/CompositeGeocodingService.kt` |
| `BACKGROUND_LOCATION_BACKFILL` | `LocationBackfillWorker` | `data/location/LocationBackfillWorker.kt` |
| `RAWBACKUP_EXPORT` | `DatabaseBackupRepositoryImpl` | `data/repository/DatabaseBackupRepositoryImpl.kt` |
| `ENCRYPTED_BACKUP` | `DatabaseBackupRepositoryImpl` | `data/repository/DatabaseBackupRepositoryImpl.kt` |

---

## 7. Dashboard/Analytics/Currency Dependency Map

```
HomeScreen
  │
  ▼
HomeViewModel                              [ui/screens/home/HomeViewModel.kt]
  │
  ├──► DashboardRepository                 [data/repository/DashboardRepository.kt]
  │     └── SharedPreferences (widget layout)
  │
  ├──► ComputeDashboardWidgetsUseCase      [domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt]
  │     └──► DashboardDataProvider
  │           ├──► DashboardExpenseRepository (adapter)
  │           ├──► DashboardCategoryRepository (adapter)
  │           ├──► DashboardBudgetRepository (adapter)
  │           ├──► DashboardReviewQueueRepository (adapter)
  │           ├──► DashboardFinancialWeatherRepository (adapter)
  │           ├──► DashboardSavingsGoalRepository (adapter)
  │           └──► DashboardAnalyticsRepository (adapter)
  │
  ├──► TotalsAggregationEngine             [domain/analytics/TotalsAggregationEngine.kt]
  │     └──► MultiCurrencyRepository
          │     ├──► ExpenseDao
          │     ├──► CurrencyConverter
          │     ├──► CurrencySettingsRepository
          │     └──► TimeProvider
  │
  └──► AnalyticsRepository                 [data/repository/AnalyticsRepository.kt]
        ├──► ExpenseDao
        ├──► MultiCurrencyRepository
        └──► AnalyticsCurrencyNormalizer

MultiCurrencyRepository                    [data/repository/MultiCurrencyRepository.kt]
  │  (Currency-aware aggregation backbone — wired into 10+ pipelines)
  │
  ├──► ExpenseDao (getAllSpentBetweenByCurrency, etc.)
  ├──► CurrencyConverter (convertMultiple)
  ├──► CurrencySettingsRepository (homeCurrency)
  └──► TimeProvider

AnalyticsCurrencyNormalizer                [domain/analytics/AnalyticsCurrencyNormalizer.kt]
  │  (Per-expense home-currency normalization)
  │
  └──► CurrencyConverter
```

### Pipelines using MultiCurrencyRepository

| Pipeline | File |
|----------|------|
| Dashboard totals | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` |
| Budget status | `data/repository/BudgetRepository.kt` |
| Analytics summary | `data/repository/AnalyticsRepository.kt` |
| Forecast | `data/repository/FinancialWeatherRepository.kt` |
| Health score | `domain/health/FinancialHealthScoreV2.kt` |
| Savings | `domain/savings/SmartSavingsEngine.kt` |
| Groups | `data/repository/GroupsRepositoryImpl.kt` |
| Export | `data/repository/ExportDataRepository.kt` |
| AI/Query | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt` |
| Anomaly | `domain/analytics/AnomalyDetector.kt` |

---

## 8. Worker/Startup Dependency Map

```
MainApplication (@HiltAndroidApp)
  │
  └──► AppStartupDelegate (@EntryPoint)
        │
        └──► AppStartupCoordinator
              │
              ├──► checkRestoreJournal()                 → RestoreJournal
              ├──► registerLifecycleObserver()           → AppBackgroundLifecycleObserver
              └──► scheduleStartupWork()
                    │
                    ├── LocationBackfillWorker            [data/location/LocationBackfillWorker.kt]
                    │     └──► PrivacyGate, GeocodingService, ExpenseDao
                    │
                    ├── MerchantKeyBackfillWorker         [data/location/MerchantKeyBackfillWorker.kt]
                    │     └──► ExpenseDao, MerchantNormalizationDao
                    │
                    ├── WarrantyExpirationWorker          [service/warranty/WarrantyExpirationWorker.kt]
                    │     └──► WarrantyDao, NotificationService
                    │
                    ├── DataRetentionWorker               [data/privacy/DataRetentionWorker.kt]
                    │     └──► RawNotificationDao, ScannedReceiptDao, PrivacyAuditDao
                    │
                    ├── BillReminderWorker                [service/reminder/BillReminderWorker.kt]
                    │     └──► RecurringLifecycleCoordinator, NotificationService
                    │
                    └── ReceiptMatchingWorker             [service/receiptmatching/ReceiptMatchingWorker.kt]
                          └──► ReceiptRepository, ExpenseRepository

WorkerSpec defaults                       [domain/workers/WorkerSpec.kt]
  └── DEFAULTS map with specs for all 7 workers (interval, constraints, backoff)
```

### Worker → DAO Dependencies

| Worker | DAO Dependencies |
|--------|-----------------|
| `DailyBriefingWorker` | AiArtifactDao |
| `LocationBackfillWorker` | ExpenseDao |
| `MerchantKeyBackfillWorker` | ExpenseDao, MerchantNormalizationDao |
| `WarrantyExpirationWorker` | WarrantyDao |
| `BillReminderWorker` | RecurringOccurrenceDao, RecurringReminderDeliveryDao |
| `ReceiptMatchingWorker` | ScannedReceiptDao, ExpenseDao, ReceiptExpenseLinkDao |
| `DataRetentionWorker` | RawNotificationDao, ScannedReceiptDao, PrivacyAuditDao |

---

## 9. Hilt Module Map

### Module → Provided Types → Consumers

#### Core Modules

| Module | File | Provided Types | Consumed By |
|--------|------|---------------|-------------|
| `DatabaseModule` | `di/DatabaseModule.kt` | `AppDatabase`, `GroupTransactionCoordinator` | All DAOs, group operations |
| `DaoModule` | `di/DaoModule.kt` | 39 DAO singletons | All repositories |
| `DispatchersModule` | `di/DispatchersModule.kt` | `@IoDispatcher`, `@DefaultDispatcher`, `ApplicationScope` | 50+ classes |
| `TimeModule` | `di/TimeModule.kt` | `TimeProvider` → `SystemTimeProvider` | 50+ classes |
| `ServiceModule` | `di/ServiceModule.kt` | `Gson`, `NotificationService`, `GeocodingService`, `NearbyPoiService`, `ForegroundLocationProvider`, `NavigationTargetResolver`, `WidgetStyleRepository`, `SpeechInputGateway` | Services, geocoding, navigation |

#### AI Modules

| Module | File | Provided Types | Consumed By |
|--------|------|---------------|-------------|
| `AiModule` | `di/AiModule.kt` | AI repositories (6), AI services (10), AI DAOs (3), `RedactionSanitizer`, `AiPolicy`, `AiCapabilityRouter`, `AiWorkScheduler`, semantic detector, priority scorer, notification parser | AI ViewModels, Workers, use cases |
| `OcrImprovementsModule` | `di/OcrImprovementsModule.kt` | `EnhancedMerchantExtractor`, `OcrLanguageProcessor`, `OcrPreprocessingPipeline` | Receipt OCR pipeline |
| `NaturalLanguageModule` | `di/NaturalLanguageModule.kt` | `NaturalLanguageExpenseQueryRepository` → impl | `NaturalLanguageSearchViewModel` |

#### Feature Modules

| Module | File | Provided Types | Consumed By |
|--------|------|---------------|-------------|
| `CashFlowModule` | `di/CashFlowModule.kt` | `CashFlowCalculator` | CashFlowCalendarViewModel, SmartSavingsEngine |
| `CurrencyModule` | `di/CurrencyModule.kt` | `CurrencySettingsRepository`, `CurrencyRatesRepository`, `ExchangeRateStore` | All currency-aware pipelines |
| `DashboardContractsModule` | `di/DashboardContractsModule.kt` | 7 dashboard contract adapters | `ComputeDashboardWidgetsUseCase` |
| `DashboardAnomalyModule` | `di/DashboardAnomalyModule.kt` | `AnomalyAlertRepository` (domain + dashboard) | Analytics, dashboard |
| `SavingsModule` | `di/SavingsModule.kt` | `SmartSavingsEngine`, `AutomatedSavingsRuleStateRepository`, `SavingsContributionHistoryRepository`, `AutomatedSavingsRuleEngine`, `SavingsGamificationEngine` | Savings ViewModels |
| `SavingsRepositoryBindingsModule` | `di/SavingsRepositoryBindingsModule.kt` | `DomainSavingsGoalRepository` binding | Savings engines |
| `GroupsModule` | `di/GroupsModule.kt` | `GroupsRepository`, `SharedExpenseDataPort`, Use cases (3) | Groups ViewModel |
| `SubscriptionModule` | `di/SubscriptionModule.kt` | `SubscriptionManagerEngine` | Subscription ViewModel |
| `TaxModule` | `di/TaxModule.kt` | `TaxConfiguration` → `GreeceTaxConfiguration` | Tax ViewModel |
| `ExportModule` | `di/ExportModule.kt` | `QuickBooksIIFExporter`, `XeroCSVExporter`, `FreshBooksExporter` | Export ViewModel |

#### Infrastructure Modules

| Module | File | Provided Types | Consumed By |
|--------|------|---------------|-------------|
| `NetworkModule` | `di/NetworkModule.kt` | `@LocationHttpClient`, `@CloudAiHttpClient` | Geocoding services, AI providers |
| `SecurityModule` | `di/SecurityModule.kt` | `SecureKeyStorage` | AI providers, encryption |
| `PrivacyModule` | `di/PrivacyModule.kt` | `CompositePrivacyGate`, `PrivacyAuditLogger`, `PrivacySettingsRepository` | Every gated capability, backup |
| `BackupRepositoryModule` | `di/BackupRepositoryModule.kt` | `DatabaseBackupRepository` → impl | BackupRestoreViewModel |
| `ParserModule` | `di/ParserModule.kt` | `GreekBankParser` | Notification parsing |
| `ReceiptParsingModule` | `di/ReceiptParsingModule.kt` | `MerchantRulesPolicy` binding | Receipt parsing |
| `EmptyStateModule` | `di/EmptyStateModule.kt` | `EmptyStateRegistryInitializer` multibind | Empty state UI |
| `EmailIngestionModule` | `di/EmailIngestionModule.kt` | `AmazonReceiptParser`, `UberReceiptParser`, `AppleReceiptParser` | Email ingestion |
| `LocationResolverPortsModule` | `di/LocationResolverPortsModule.kt` | `LocationCachePort`, `MerchantClusterPort` | Location enrichment |

---

## 10. DAO/Repository Map

### Entity → DAO → Repository → Consumer

| Entity | DAO | Repository | Primary Consumers |
|--------|-----|------------|-------------------|
| `Expense` | `ExpenseDao` | `ExpenseRepository`, `MultiCurrencyRepository`, `NotificationRepository`, `ReviewQueueRepository`, `ReceiptRepository` | TransactionsVM, HomeVM, BudgetVM, AnalyticsVM, etc. |
| `Category` | `CategoryDao` | `CategoryRepository` | Every VM (categories are ubiquitous) |
| `PendingReview` | `PendingReviewDao` | `ReviewQueueRepository`, `NotificationRepository` | ReviewViewModel |
| `Budget` | `BudgetDao` | `BudgetRepository` | BudgetViewModel, BudgetForecastingVM |
| `ScannedReceipt` | `ScannedReceiptDao` | `ReceiptRepository` | ReceiptScanVM, ReviewVM |
| `RawNotification` | `RawNotificationDao` | `NotificationRepository` | TransactionsVM, DebugVM |
| `RecurringExpense` | `ManualRecurringExpenseDao` | `RecurringExpenseRepository` | RecurringExpensesVM, FinancialWeatherRepository |
| `ManualRecurringExpense` | `ManualRecurringExpenseDao` | `ManualRecurringExpenseRepository` | ManualRecurringExpenseVM |
| `RecurringOccurrence` | `RecurringOccurrenceDao` | `RecurringLifecycleCoordinator` | Recurring coordinator, BillReminderWorker |
| `PlannedExpense` | `PlannedExpenseDao` | `PlannedExpenseRepository` | HomeVM, FinancialWeatherRepository |
| `TransactionEvent` | `TransactionEventDao` | `TransactionLifecycleCoordinator` | Audit log (append-only) |
| `ReceiptEvent` | `ReceiptEventDao` | `ReceiptLifecycleCoordinator`, `ReceiptLinkService` | Receipt audit log |
| `ReceiptExpenseLink` | `ReceiptExpenseLinkDao` | `ReceiptLinkService` | Receipt matching |
| `SavingsGoal` | `SavingsGoalDao` | `SavingsGoalRepository` | SavingsGoalsVM |
| `ExchangeRate` | `ExchangeRateDao` | `CurrencyConverter`, `MultiCurrencyRepository` | Currency conversion |
| `Investment` | `InvestmentDao` | (Direct DAO usage) | InvestmentVM |
| `BankConnection` | `BankConnectionDao` | (Direct DAO usage) | BankConnectionsVM |
| `AnomalyAlert` | `AnomalyAlertDao` | `AnomalyAlertRepositoryImpl` | Analytics, Dashboard |
| `BlockedPackage` | `BlockedPackageDao` | `NotificationRepository` | Notification filter |
| `SourceStats` | `SourceStatsDao` | `NotificationRepository` | Debug |
| `PrivacyAuditEvent` | `PrivacyAuditDao` | `PrivacyAuditLogger` | Privacy audit |
| `AiArtifactEntity` | `AiArtifactDao` | `AiArtifactRepositoryImpl` | AI follow-through |
| `AiChatSession` | `AiChatSessionDao` | `AiChatRepositoryImpl` | Assistant |
| `AiChatMessage` | `AiChatMessageDao` | `AiChatRepositoryImpl` | Assistant |
| `ReceiptItemCategorization` | `ReceiptItemCategorizationDao` | `ReceiptItemCategorizationRepository` | AI categorization |
| `RecurringLifecycleEvent` | `RecurringLifecycleEventDao` | `RecurringLifecycleCoordinator` | Recurring audit log |
| `RecurringReminderDelivery` | `RecurringReminderDeliveryDao` | `RecurringLifecycleCoordinator` | Reminder delivery |
| `Warranty` | `WarrantyDao` | `WarrantyTrackerRepository` | WarrantyTrackerVM |
| `ReturnWindow` | `ReturnWindowDao` | `WarrantyTrackerRepository` | WarrantyTrackerVM |
| `SubscriptionCandidate` | `SubscriptionCandidateDao` | `SubscriptionManagementRepository` | SubscriptionVM |
| `SubscriptionPriceHistory` | `SubscriptionPriceHistoryDao` | `SubscriptionManagementRepository` | SubscriptionVM |
| `SubscriptionUsage` | `SubscriptionUsageDao` | `SubscriptionManagementRepository` | SubscriptionVM |
| `EmailReceiptSource` | `EmailReceiptDao` | `EmailReceiptIngestionService` | Email receipt |
| `ExpenseGroup` | `ExpenseGroupDao` | `GroupsRepositoryImpl` | SharedExpenseGroupsVM |
| `GroupMember` | `GroupMemberDao` | `GroupsRepositoryImpl` | SharedExpenseGroupsVM |
| `GroupExpense` | `GroupExpenseDao` | `GroupsRepositoryImpl` | SharedExpenseGroupsVM |
| `SplitTemplate` | `SplitTemplateDao` | (Direct usage) | VisualSplitVM |
| `SplitItemAssignment` | `SplitItemAssignmentDao` | (Direct usage) | VisualSplitVM |
| `SpendingChallengeEntity` | `SpendingChallengeDao` | `SpendingChallengeRepository` | SpendingChallengesVM |
| `PromptState` | `PromptStateDao` | `PromptStateRepository` | Savings prompts |
| `BackgroundJobRun` | `BackgroundJobRunDao` | Workers directly | Worker tracking |

---

## ViewModel Constructor Injection Reference

| ViewModel | Injected Dependencies |
|-----------|----------------------|
| `HomeViewModel` | DashboardRepository, DashboardDataProvider, CategoryRepository, PlannedExpenseRepository, DashboardAnalyticsRepository, ExpenseRepository, ComputeDashboardWidgetsUseCase, TotalsAggregationEngine, AdvancedAnalyticsEngine, AiSettingsRepository, AiArtifactRepository, AiEngagementRepository, AiEnvironmentMonitor, WidgetStyleRepository, TimeProvider, RecommendationStateManager, NavigationTargetResolver, RecommendationDismissalHandler, CurrencySettingsRepository |
| `TransactionsViewModel` | NotificationRepository, ExpenseRepository, CategoryRepository, RecurringExpenseRepository, MerchantLocationRepository, TimeProvider, GeocodingService, CurrencySettingsRepository |
| `ReviewViewModel` | NotificationRepository, ReviewQueueRepository, CategoryRepository, ReceiptRepository, ReceiptLifecycleCoordinator, TransactionLifecycleCoordinator, AiArtifactRepository, AiSettingsRepository, ExplainPendingReviewUseCase, JudgePendingReviewDuplicateUseCase, SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase |
| `BudgetViewModel` | BudgetRepository, CategoryRepository, SharedExpenseBudgetOffsetEngine, BudgetAutopilotEngine, TimeProvider, CurrencySettingsRepository, AppDatabase |
| `AddExpenseViewModel` | ManualExpenseRepository, ExpenseRepository, CategoryRepository, TimeProvider, CurrencySettingsRepository |
| `ReceiptScanViewModel` | CategoryRepository, ReceiptRepository, ReceiptItemCategorizationRepository, ReceiptLifecycleCoordinator, ReceiptLinkService, TransactionLifecycleCoordinator, CategorizeReceiptItemsUseCase, SuggestCategoryFallbackUseCase, SuggestReceiptExtractionUseCase, AiArtifactRepository, AiSettingsRepository, HybridExpenseClassifier, MerchantNormalizer, ReceiptParser, TimeProvider, CurrencySettingsRepository |
| `AnalyticsViewModel` | ExpenseRepository, CategoryRepository, BudgetRepository, InsightsEngine, RecurringExpenseEngine, AnalyticsRepository, AdvancedAnalyticsEngine, AnalyticsCurrencyNormalizer, LocationInsightsEngine, AreaSpendingEngine, TravelDetectionEngine, SpendingPersonalityClassifier, TimeProvider, CurrencyConverter, CurrencySettingsRepository |
| `AdvancedAnalyticsViewModel` | AnalyticsRepository, CategoryRepository |
| `BackupRestoreViewModel` | DatabaseBackupRepository |
| `SavingsGoalsViewModel` | SavingsGoalRepository, SmartSavingsEngine, AutomatedSavingsRuleEngine, SavingsGamificationEngine |
| `SubscriptionManagementViewModel` | SubscriptionManagementRepository |
| `CurrencyManagementViewModel` | CurrencySettingsRepository, CurrencyRatesRepository, MultiCurrencyRepository |
| `CarbonFootprintViewModel` | ExpenseRepository, CategoryRepository |
| `CashFlowCalendarViewModel` | CashFlowCalculator, ExpenseRepository, CategoryRepository |
| `DebugViewModel` | NotificationRepository, ExpenseRepository, BudgetRepository, CategoryRepository |
| `PrivacySettingsViewModel` | PrivacySettingsRepository |
| `VisualSplitViewModel` | SplitTemplateDao, SplitItemAssignmentDao |
| `WarrantyTrackerViewModel` | WarrantyTrackerRepository |
| `SpendingMapViewModel` | ExpenseRepository, CategoryRepository, LocationResolver |
| `InvestmentViewModel` | InvestmentDao, InvestmentValueDao |
| `BankConnectionsViewModel` | BankConnectionDao |
| `ReceiptMatchingViewModel` | ReceiptRepository, ExpenseRepository |
| `AiSettingsViewModel` | AiSettingsRepository |
| `AssistantViewModel` | AiChatRepository, QueryInterpretationService |
| `BillRemindersViewModel` | BillReminderManager, RecurringLifecycleCoordinator |

---

> **Generated:** Manual analysis of 620+ Kotlin files across 3 layers (UI/Domain/Data),  
> 30 Hilt modules, 39 ViewModels, 51 repositories, 54 DAOs, 56 entities.  
> **Next update:** Regenerate when significant architectural changes occur (new module, major refactor).
