# Backend Map - Test Coverage & Cross-References

**Generated:** 2026-06-09 · **Reconciled with code:** 2026-09-21

---

## Test Coverage Summary

**Total Test Files:** 683+ (unit) + 28 (instrumented)

### Test Categories

| Category | Count | Files |
|----------|-------|-------|
| Consistency Tests | 13 | `consistency/*Test.kt` |
| AI Provider Tests | 24 | `data/ai/provider/*Test.kt` |
| Repository Tests | 44 | `data/repository/*Test.kt` |
| Engine Tests | 47 | `*Engine*Test.kt` |
| Domain Logic Tests | 310 | `domain/*/` |
| Privacy Tests | 22 | `domain/privacy/*Test.kt` |
| Parser Tests | 15 | `domain/parser/*`, `data/email/provider/*` |

### Files With Test Coverage

**Domain Layer:**
- `domain/categorization/CategorizationEngine.kt` - ✓ Tests
- `domain/util/MerchantKeyGenerator.kt` - ✓ Tests
- `domain/util/StringDistanceUtils.kt` - ✓ Tests
- `domain/logic/RecurrenceCalculator.kt` - ✓ Tests
- `domain/analytics/*` - ✓ Multiple test files

**Data Layer:**
- `data/database/dao/ExpenseDao.kt` - ✓ Tests
- `data/ai/provider/CloudCategorizationAssistService.kt` - ✓ Tests
- `data/ai/provider/CloudDedupeJudgeService.kt` - ✓ Tests
- `data/ai/provider/CloudQueryInterpretationService.kt` - ✓ Tests
- `data/ai/provider/CloudReceiptAssistService.kt` - ✓ Tests
- `data/repository/*` - ✓ Tests

### Notable Test Files

| Test File | Purpose | Covers |
|-----------|---------|--------|
| `AnalyticsEngineTestBase.kt` | Base test class | Analytics engines |
| `ConstantsConsistencyTest.kt` | Constants validation | AppConstants |
| `CrossParserConsistencyTest.kt` | Parser validation | All parsers |
| `FinancialArithmeticPrecisionTest.kt` | Money math | Amount calculations |
| `HaversineConsistencyTest.kt` | Distance calculations | GeoUtils |
| `MerchantKeyConsistencyTest.kt` | Merchant key gen | MerchantKeyGenerator |
| `TemporalConsistencyTest.kt` | Time logic | Date/Time utilities |

---

## Critical Dependency Chains

### Chain 1: Expense Ingestion → Storage (via TransactionLifecycleCoordinator)

```
Notification/SMS Input
    ↓
Parser (GenericTransactionParser or specialized)
    ↓
ParsedTransaction
    ↓
CreateExpenseRequest
    ↓
TransactionLifecycleCoordinator.createExpense()
    ├─ [validate → normalize → dedupe → insertAtomic]
    ↓
TransactionEvent (event log) + Expense (stored in DB)
    ↓
TransactionSideEffectDispatcher.dispatchOnCreated()
    ├─→ budget check
    ├─→ anomaly alert
    └─→ merchant-category learning
    ↓
ExpenseRepository (read layer)
```

**Files:** `parser/*`, `transaction/CreateExpenseRequest.kt`, `transaction/lifecycle/TransactionLifecycleCoordinator.kt`, `transaction/lifecycle/TransactionSideEffectDispatcher.kt`, `data/database/entity/Expense.kt`, `data/database/entity/TransactionEvent.kt`, `data/database/dao/ExpenseDao.kt`

### Chain 2: Categorization Pipeline

```
Merchant Name Input
    ↓
MerchantNormalizer.normalize()
    ↓
CategorizationEngine.categorize()
    ├─→ CategoryKeywords lookup
    ├─→ ContextualInferenceEngine
    └─→ MerchantCanonicalizer
    ↓
CategoryResult (with confidence)
    ↓
ConfidenceRouter (if low confidence → AI)
    ├─→ OnDeviceCategorizationAssistService
    ├─→ CloudCategorizationAssistService
    └─→ NoOpCategorizationAssistService
    ↓
MerchantCategoryRepository.learnPattern()
    ↓
MerchantCategoryDao
    ↓
MerchantCategory entity
```

**Files:** 
- Domain: `categorization/*`, `intelligence/ml/MerchantNormalizer.kt`
- Data: `repository/MerchantCategoryRepository.kt`, `database/dao/MerchantCategoryDao.kt`
- AI: `ai/provider/*CategorizationAssistService.kt`

### Chain 3: Dashboard Computation

```
ComputeDashboardWidgetsUseCase
    ↓
DashboardDataProvider (collects data)
    ├─→ DashboardExpenseRepository (contract)
    ├─→ DashboardBudgetRepository (contract)
    ├─→ DashboardSavingsGoalRepository (contract)
    ├─→ DashboardReviewQueueRepository (contract)
    └─→ DashboardFinancialWeatherRepository (contract)
    ↓
Multiple Engines compute in parallel:
    ├─→ SpendingPaceCalculator
    ├─→ CategoryInsightEngine
    ├─→ AnomalyDetector
    ├─→ BudgetMonitor
    └─→ TotalsAggregationEngine
    ↓
DashboardPrimitives (UI data)
    ↓
UI Layer
```

**Files:** `usecase/dashboard/*`, multiple repositories, multiple engines

### Chain 4: Receipt Processing

```
ProcessReceiptUseCase
    ↓
ReceiptParser (domain interface)
    ↓
ReceiptOcrService
    ├─→ OcrPreprocessingPipeline
    ├─→ OcrLanguageProcessor
    └─→ EnhancedMerchantExtractor
    ↓
Receipt Items extracted
    ↓
ReceiptItemCategorizationService (AI)
    ├─→ CategorizeReceiptItemsUseCase
    └─→ ReceiptItemCategorizationRepository
    ↓
Receipt saved to ScannedReceiptDao
    ↓
Matched to Expense via ReceiptTransactionMatcher
```

**Files:** `receipt/*`, `usecase/receipt/*`, `ai/usecase/CategorizeReceiptItemsUseCase.kt`, `ai/service/ReceiptItemCategorizationService.kt`

### Chain 5: Budget Forecasting

```
GetMonteCarloBudgetImpactUseCase
    ↓
MonteCarloSpendingSimulator
    ├─→ DataQualityAssessor
    ├─→ HistoricalSpendingDistribution
    └─→ TimeProvider
    ↓
Reads from:
    ├─→ ExpenseRepository (historical data)
    ├─→ BudgetRepository (budget limits)
    └─→ RecurringExpenseRepository (recurring patterns)
    ↓
MonteCarloResult (scenarios)
    ↓
MonteCarloBudgetImpact model
    ↓
UI visualization
```

**Files:** `forecasting/*`, `usecase/budget/GetMonteCarloBudgetImpactUseCase.kt`, multiple repositories

### Chain 6: Shared Expenses Settlement

```
AddGroupExpenseUseCase
    ↓
GroupTransactionCoordinator (data implementation)
    ├─→ GroupTransactionCoordinator (domain interface)
    ├─→ SettlementCalculator
    └─→ SharedExpenseBudgetOffsetEngine
    ↓
GroupExpenseDao
    ↓
GroupExpense entity + GroupMember entities
    ↓
GroupsRepository
    ↓
Settlement calculations for UI
```

**Files:** `groups/*`, `data/database/dao/GroupExpenseDao.kt`, `data/repository/GroupsRepository.kt`

### Chain 7: Natural Language Query

```
ExecuteFinancialQueryUseCase
    ↓
QueryInterpretationService
    ↓
Cloud/OnDevice NL Models
    ├─→ CloudQueryInterpretationService
    ├─→ OnDeviceQueryInterpretationService
    └─→ NoOpQueryInterpretationService
    ↓
NaturalLanguageSearchEngine
    ↓
ExpenseRepository queries
    ↓
Filtered transactions
    ↓
MapFinancialQueryToNavigationUseCase
    ↓
UI Navigation
```

**Files:** `naturallanguage/*`, `usecase/expense/*`, `ai/usecase/*QueryUseCase.kt`

### Chain 8: Transaction Lifecycle Coordinator

```
CreateExpenseRequest (from any source)
    ↓
TransactionLifecycleCoordinator.createExpense()
    ├─ Validation (required fields, types)
    ├─ Normalization (merchant, currency, amount)
    ├─ Deduplication (DeduplicationMode)
    └─ Atomic insert + TransactionEvent log
    ↓
TransactionSideEffectDispatcher.dispatchOnCreated()
    ├─→ BudgetMonitor.checkBudgets()
    ├─→ AnomalyAlertOrchestrator.checkAndAlert()
    └─→ MerchantCategoryRepository.learnPattern()
    ↓
Expense stored in DB + event audit trail
```

**Files:** `transaction/lifecycle/TransactionLifecycleCoordinator.kt`, `transaction/lifecycle/TransactionSideEffectDispatcher.kt`, `transaction/CreateExpenseRequest.kt`, `transaction/CreateExpenseResult.kt`, `transaction/DeduplicationMode.kt`, `transaction/SideEffectMode.kt`, `data/database/dao/TransactionEventDao.kt`

### Chain 9: Privacy Gate

```
Feature Request
    ↓
CompositePrivacyGate.check(capability, context)
    ↓
┌───────────────────────────────────────────────────────────┐
│ 1. NotificationPrivacyGate: NOTIFICATION_CAPTURE, etc.   │
│ 2. CloudAiPrivacyGate: CLOUD_AI_*, RECEIPT_IMAGE_CLOUD_  │
│    UPLOAD                                                │
│ 3. LocationPrivacyGate: EXTERNAL_GEOCODING, DEVICE_GPS_  │
│    LOCATION, etc.                                        │
│ 4. BackupPrivacyGate: RAWBACKUP_EXPORT, ENCRYPTED_BACKUP│
└───────────────────────────────────────────────────────────┘
    ↓ (first Denied wins, or Allowed if all pass)
PrivacyDecision (Allowed | Denied(reason))
    ↓
PrivacyAuditLogger.logDecision(capability, decision, context)
    ↓
Proceed or Block operation
```

**Files:** `privacy/PrivacyGate.kt`, `privacy/CompositePrivacyGate.kt`, `privacy/NotificationPrivacyGate.kt`, `privacy/CloudAiPrivacyGate.kt`, `privacy/LocationPrivacyGate.kt`, `privacy/BackupPrivacyGate.kt`, `privacy/PrivacyDecision.kt`, `privacy/PrivacyBlocked.kt`, `privacy/PrivacyCapability.kt`, `privacy/PrivacyAuditLogger.kt`, `privacy/PrivacySettings.kt`, `privacy/EffectiveCloudAiPolicy.kt`, `privacy/CloudPayloadPolicy.kt`, `privacy/RawStorageMode.kt`, `privacy/RawContentSanitizer.kt`

### Chain 10: Worker Infrastructure

```
WorkerSpec (configuration)
    ↓
WorkerSpecScheduler.schedule(workerName)
    ├─ Reads WorkerSpec.DEFAULTS[name]
    ├─ Detects version changes → force REPLACE
    └─ Delegates to WorkManager
    ↓
WorkerExecutionGuard.acquire(workerName)
    ├─ Prevents concurrent execution
    └─ Timeout-based locking
    ↓
WorkerRunLogger.start(workerName) → WorkerRunHandle
    ↓
Worker execution (domain logic)
    ↓
WorkerRunHandle.success/failure/skipped/retry/cancelled(workerName, runId)
    ↓
PrivacyRuntimeWorkerPolicy (gates execution at runtime)
```

**Files:** `workers/WorkerSpec.kt`, `workers/WorkerSpecScheduler.kt`, `workers/WorkerExecutionGuard.kt`, `workers/WorkerRunLogger.kt`, `workers/WorkerRegistry.kt`, `workers/RetryableWorkerException.kt`, `workers/PrivacyRuntimeWorkerPolicy.kt`, `workers/NotificationPermissionChecker.kt`, `workers/WorkerRunContext.kt`, `workers/WorkerReasonCodes.kt`, `workers/WorkerTerminalDiagnosticSink.kt`, `workers/FileWorkerTerminalDiagnosticSink.kt`, `workers/WorkerLease.kt`, `workers/WorkerLeaseRegistry.kt`, `workers/WorkerLeaseRegistryImpl.kt`

Registered `WorkerRegistry` entries (7): `location_backfill`, `merchant_key_backfill`, `warranty_expiration_check`, `data_retention`, `bill_reminder_periodic`, `receipt_matching`, `ai_daily_briefing`. CoroutineWorkers not in the registry: `DismissReminderActionWorker`, `SnoozeReminderActionWorker`, `NotificationIntakeWorker`.

### Chain 11: Receipt Match Lifecycle

```
Receipt captured/imported
    ↓
ReceiptLifecycleCoordinator (orchestrates)
    ├─ ReceiptInputValidator (URI/MIME/size)
    ├─ ReceiptDuplicateDetector (3-signal dedup)
    └─ ReceiptAssetStore (file persistence)
    ↓
ReceiptLinkService.linkReceiptToExpense()
    ├─ Creates receipt_expense_link row
    └─ Writes receipt_events audit event
    ↓
ReceiptMatchLifecycleService (lifecycle-aware mutations)
    ├─ DatabaseWriteBarrier check
    ├─ ScannedReceiptDao status update
    └─ ReceiptEventDao event recording
    ↓
ReceiptSideEffectDispatcher (document-type-gated)
    ├─ AutoCreateWarrantyFromReceiptUseCase
    ├─ CategorizeReceiptItemsUseCase
    ├─ ReceiptTransactionMatcher
    └─ PriceProtectionTracker
```

**Files:** `receipt/lifecycle/ReceiptLifecycleCoordinator.kt`, `receipt/lifecycle/ReceiptLinkService.kt`, `receipt/lifecycle/ReceiptMatchLifecycleService.kt`, `receipt/lifecycle/ReceiptSideEffectDispatcher.kt`, `receipt/lifecycle/ReceiptDuplicateDetector.kt`, `receipt/lifecycle/ReceiptAssetStore.kt`, `receipt/lifecycle/ReceiptInputValidator.kt`, `debug/ReceiptDebugExporter.kt`

---

## Repository → DAO → Entity Dependencies

### Expense Entity Graph

```
Expense (main entity)
    ├─ Repository: ExpenseRepository
    ├─ DAO: ExpenseDao
    └─ Relationships:
        ├→ Category (via category_id)
        ├→ PendingReview (if unreviewed)
        ├→ UserCorrection (if corrected)
        ├→ ScannedReceipt (if from receipt)
        ├→ MerchantCategory (via merchant lookup)
        └→ ExchangeRate (if multi-currency)
```

### Merchant Normalization Graph

```
MerchantCanonical (main)
    ├─ Repository: MerchantNormalizationRepository
    ├─ DAO: MerchantNormalizationDao
    └─ Related:
        ├→ MerchantAlias (multiple)
        ├→ MerchantLocation (multiple)
        ├→ MerchantCategory
        └→ Used by: CategorizationEngine, ExpenseRepository
```

### Budget Graph

```
Budget (main entity)
    ├─ Repository: BudgetRepository
    ├─ DAO: BudgetDao
    └─ Related:
        ├→ BudgetForecast
        ├→ BudgetAdjustmentRecommendation
        └→ References: Category, Expense (for calculations)
```

### Recurring Expenses Graph

```
ManualRecurringExpense / RecurringExpense / RecurringOccurrence / RecurringLifecycleEvent
    ├─ Repository: ManualRecurringExpenseRepository / RecurringExpenseRepository
    ├─ DAO: ManualRecurringExpenseDao / RecurringExpenseDao / RecurringOccurrenceDao / RecurringLifecycleEventDao
    └─ Used by:
        ├→ RecurringExpenseEngine
        ├→ BudgetCalculator
        ├→ HistoricalSpendingDistribution
        ├→ RecurringLifecycleCoordinator
        └→ BillReminderWorker
```

### Operation Run Graph

```
OperationRun / OperationRunEvent
    ├─ DAO: OperationRunDao / OperationRunEventDao
    └─ Used by:
        ├→ OperationRunRecorder
        └→ DiagnosticsRepository
```

### Warranty Reminder Delivery Graph

```
WarrantyReminderDelivery
    ├─ DAO: WarrantyReminderDeliveryDao
    └─ Related:
        ├→ Warranty (via warranty_id)
        └→ WarrantyLifecycleEvent
```

### AI Artifact Storage Graph

```
AiArtifactEntity (cache layer)
    ├─ Repository: AiArtifactRepositoryImpl
    ├─ DAO: AiArtifactDao
    └─ Used for:
        ├→ Caching AI responses
        ├→ Dashboard briefing history
        └→ AI engagement metrics
```

---

## Service → Engine → Utility Dependencies

### Categorization Service Stack

```
CategorizeExpenseUseCase (entry point)
    ↓
CategorizationEngine (main logic)
    ├─ CategoryKeywords (lookup table)
    ├─ ContextualInferenceEngine (semantic)
    ├─ MerchantCanonicalizer (normalization)
    └─ SemanticKeywordMatcher (matching)
    ↓
ConfidenceRouter (routes to AI if needed)
    ├─ Domain: ConfidenceRouter
    └─ Uses:
        ├→ CategorizationAssistService (domain interface)
        ├→ OnDeviceCategorizationAssistService (impl)
        ├→ CloudCategorizationAssistService (impl)
        └→ NoOpCategorizationAssistService (impl)
    ↓
MerchantNormalizer (text processing)
    └─ Uses: MerchantCleaner, GreeklishNormalizer
```

### Analytics Engine Stack

```
DashboardDataProvider
    ↓
Multiple Engines (parallel):
    ├─ TotalsAggregationEngine
    │  └─ AmountUtils, CurrencyNormalizer
    ├─ SpendingPaceCalculator
    │  └─ TimePeriodUtils, StatisticsUtils
    ├─ CategoryInsightEngine
    ├─ AnomalyDetector
    │  └─ StatisticsUtils
    ├─ DayOfWeekAnalyzer
    │  └─ DateFormatterUtils
    ├─ MonthlyComparisonCalculator
    ├─ SpendingPersonalityClassifier
    │  └─ Analytics models
    ├─ MerchantInsightEngine
    └─ InsightsEngine (synthesizes all)
```

### Receipt Processing Stack

```
ProcessReceiptUseCase
    ↓
ReceiptParser (domain interface)
    └─ ReceiptOcrService (provider)
        ├─ OcrPreprocessingPipeline
        │  ├─ OcrLanguageProcessor
        │  └─ Image preprocessing
        ├─ EnhancedMerchantExtractor
        │  └─ MerchantRulesPolicy
        └─ WarrantyTextExtractor
    ↓
Receipt Items extracted
    ↓
ReceiptItemCategorizationService
    └─ CategorizeReceiptItemsUseCase
        ├─ ReceiptAssistService (AI)
        └─ OnDevice/Cloud providers
```

---

## DI Module Dependency Graph

```
DatabaseModule (root)
    ├─ Provides: AppDatabase (v148, 70 entities; baseline v145 via DatabaseSchemaPolicy)
    ├─ Uses: DaoModule
    ├─ Provides: GroupTransactionCoordinator (domain interface → data impl)
    └─ Provides: DomainTransactionRunner → RoomDomainTransactionRunner

DaoModule
    ├─ Provides: 64 DAOs
    └─ (AiModule companion provides the 3 AI DAOs; SourceStatsEventDao is not Hilt-bound)

DiagnosticsModule
    ├─ Provides: DiagnosticEventWriter, Lifecycle event writers, OperationRunRecorder, DiagnosticsRepository
    └─ Binds: WorkerRunLogger → WorkerRunLoggerImpl

ProvenanceModule
    └─ Provides: Provenance event recording

ReminderSettingsModule
    └─ Provides: BillReminderSettingsRepository

RetentionModule
    └─ Provides: RetentionRegistry with 10 targets (raw_notifications, notification_intake, scanned_receipts OCR, ai_artifacts, ai_chat_messages, email_receipt_sources, pipeline_diagnostic_events, pending_reviews, background_job_runs, bank_statement_import_items)

WorkerModule
    ├─ Binds: WorkerLeaseRegistry → WorkerLeaseRegistryImpl
    ├─ Binds: WorkerDrainController → WorkerLeaseRegistryImpl
    ├─ Binds: NotificationPermissionChecker → AndroidNotificationPermissionChecker
    └─ Provides: WorkManager

RepositoryModules (multiple)
    ├─ SavingsRepositoryBindingsModule
    ├─ BackupRepositoryModule
    ├─ SavingsModule
    └─ Others

ServiceModule
    └─ Provides: All domain services

AiModule
    ├─ Provides: All AI services
    ├─ AiCapabilityRouter
    ├─ 3 AI DAOs
    └─ AI policy implementation

LocationResolverPortsModule
    └─ Provides: Geocoding services
        ├─ CompositeGeocodingService
        ├─ GooglePlacesGeocodingService
        ├─ GeoapifyGeocodingService
        ├─ NominatimGeocodingService
        ├─ PhotonGeocodingService
        └─ OverpassNearbyService

NetworkModule
    └─ Provides: @LocationHttpClient, @CloudAiHttpClient OkHttpClient

DispatchersModule
    └─ Provides: IO, Default dispatchers, ApplicationScope

CurrencyModule
    ├─ CurrencyConverter
    └─ Exchange rate services

ReceiptParsingModule
    └─ All receipt parsers

EmailIngestionModule
    └─ Email receipt parsers

TimeModule
    └─ TimeProvider implementations

SecurityModule
    └─ Provides: SecureKeyStorage

PrivacyModule
    └─ Provides: CompositePrivacyGate, PrivacyAuditLogger, PrivacySettingsRepository, Backups

GroupsModule
    └─ Provides: GroupsRepository, SharedExpenseDataPort, Use cases

DashboardAnomalyModule
    └─ Provides: AnomalyAlertRepository

ParserModule
    └─ Provides: GreekBankParser

TaxModule
    └─ Provides: TaxConfiguration → GreeceTaxConfiguration

CashFlowModule
    └─ Provides: CashFlowCalculator

NaturalLanguageModule
    └─ Binds: NaturalLanguageExpenseQueryRepository → NaturalLanguageExpenseQueryRepositoryImpl

NegotiationModule
    └─ Binds: MarketRateProvider → StaticMarketRateProvider

OcrImprovementsModule
    ├─ Provides: EnhancedMerchantExtractor
    ├─ OcrLanguageProcessor
    └─ OcrPreprocessingPipeline

DashboardContractsModule
    └─ Binds: 7 dashboard repository interfaces → DashboardContractsAdapter implementations

ExportModule
    ├─ FreshBooksExporter
    ├─ QuickBooksIIFExporter
    └─ XeroCSVExporter

EmptyStateModule
    └─ @Multibinds for empty-state extension points
```

---

## Data Sources (External Integrations)

| Source | Files | Type |
|--------|-------|------|
| **Google Places** | `GooglePlacesGeocodingService.kt` | Geocoding |
| **Geoapify** | `GeoapifyGeocodingService.kt` | Geocoding |
| **Nominatim (OSM)** | `NominatimGeocodingService.kt` | Geocoding |
| **Photon** | `PhotonGeocodingService.kt` | Geocoding |
| **Overpass API** | `OverpassNearbyService.kt` | POI lookup |
| **Cloud AI (Gemini)** | `Cloud*Service.kt` | AI services |
| **On-Device ML (ML Kit GenAI)** | `OnDevice*Service.kt` | ML models |
| **Email receipts (parsed)** | `EmailReceiptIngestionService.kt` | Email receipts |
| **Bank APIs** | `BankApiIntegration.kt` | Bank connections |
| **Android Keystore** | `AtRestEncryptionService.kt`, `SecureKeyStorage.kt` | Hardware-backed encryption |
| **Google Geocoding API** | `CompositeGeocodingService.kt` | Geocoding |
| **WorkManager** | `WorkerSpecScheduler.kt` | Background scheduling |

---

## Extension Points & Ports

### Parser System

**Port:** `GenericTransactionParser` (domain)

**Implementations:**
- `SmsParser`
- `GoogleWalletParser`
- `GreekBankParser`
- `RevolutParser`
- `BankStatementParser`

**Extension:** Add new parser implementing interface

### Geocoding System

**Port:** `LocationResolverPorts.kt` (domain interfaces)

**Implementations:**
- `GooglePlacesGeocodingService`
- `GeoapifyGeocodingService`
- `NominatimGeocodingService`
- `PhotonGeocodingService`
- `OverpassNearbyService`
- `CompositeGeocodingService` (strategy selection)

**Extension:** Implement `GeocodingService` interface

### AI Capability System

**Port:** `AiCapabilityRouter` (domain interface)

**Implementations:**
- `DefaultAiCapabilityRouter` (routing logic)
- Multiple providers per capability

**Extension:** Implement new `*Service` interface

### Receipt Parsing

**Port:** `ReceiptParser` (domain interface)

**Implementations:**
- `ReceiptOcrService` (main)
- `AmazonReceiptParser`
- `AppleReceiptParser`
- `UberReceiptParser`

**Extension:** Implement new email parser or OCR strategy

---

## Validation & Quality Checks

### Data Integrity

| Check | Implementation | Files |
|-------|----------------|-------|
| Amount validation | `AmountUtils.kt`, `AmountExtractionUtils.kt` | `domain/util/` |
| Currency normalization | `CurrencyNormalizer.kt` | `domain/util/` |
| Merchant normalization | `MerchantKeyGenerator.kt` | `domain/util/` |
| Financial arithmetic | `FinancialArithmeticPrecisionTest.kt` | Tests |
| Date/time logic | `TemporalConsistencyTest.kt` | Tests |

### Database Barriers

| Barrier | Purpose | File |
|---------|---------|------|
| `DatabaseReadBarrier` | Blocks reads during restore/maintenance mode | `data/backup/DatabaseReadBarrier.kt` |
| `DatabaseReadBarrierFlowExt` | Flow extensions for the read barrier | `data/backup/DatabaseReadBarrierFlowExt.kt` |
| `DatabaseWriteBarrier` | Blocks writes during restore/maintenance mode | `data/backup/DatabaseWriteBarrier.kt` |
| `RestoreMaintenanceMode` | 8-state maintenance mode coordinator, pauses workers via WorkManager | `data/backup/RestoreMaintenanceMode.kt` |

### Database Schema & Migrations

| Fact | Value | Source |
|------|-------|--------|
| Current schema version | **148** (`APP_DATABASE_SCHEMA_VERSION`) | `data/database/AppDatabase.kt` |
| Migration baseline | **145** (older DBs use the `data/rescue/` import path, not migrations) | `data/database/DatabaseSchemaPolicy.kt` |
| Registered migrations | `MIGRATION_145_146` (creates `negotiation_outcomes`), `MIGRATION_146_147` (group soft-delete: `group_members.leftAt`, `group_expenses.idempotencyKey`, non-unique member-name index), `MIGRATION_147_148` (9 worker-run tracing columns on `background_job_runs`) | `data/database/DatabaseMigrations.kt` |
| Single source of truth | `DatabaseSchemaPolicy` (`CURRENT_VERSION`, `MIGRATION_BASELINE`, `ALL_MIGRATIONS`) — production, tests, and CI must read from it | `data/database/DatabaseSchemaPolicy.kt` |
| Schema snapshots | `app/schemas/com.yourname.expensetracker.data.database.AppDatabase/` up to `148.json` | exported via Room |
| DAO access guardrail | Tiered allowlist enforced by `scripts/guardrails/dao-access-check.kts` + `scripts/guardrails/dao-approved-files.txt`; see `docs/ci/DB_ROOM_INVENTORY.md`, `docs/ci/guard-policy.md` | CI static guard suite |
| Expense write restriction | Direct `ExpenseDao` mutations require `RestrictedExpenseDaoMutation` opt-in (CI-enforced via `ExpenseDaoMutationAccessTest`) | `data/database/dao/RestrictedExpenseDaoMutation.kt` |

### Cross-Source Deduplication

| Component | Purpose | File |
|-----------|---------|------|
| `CrossSourceDeduplication` | Deduplicates across sources | `domain/intelligence/` |
| `SemanticDuplicateDetector` | Semantic duplicate detection | `domain/ai/service/` |
| `DetectSemanticDuplicateUseCase` | UseCase wrapper | `domain/ai/usecase/` |
| Consistency tests | Validates dedup logic | `consistency/*Test.kt` |

---

**End of Test Coverage & Cross-References**

