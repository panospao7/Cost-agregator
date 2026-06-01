# Backend Map - Test Coverage & Cross-References

**Generated:** 2026-06-01

---

## Test Coverage Summary

**Total Test Files:** 475+

### Test Categories

| Category | Count | Files |
|----------|-------|-------|
| Consistency Tests | 15+ | `consistency/*Test.kt` |
| AI Provider Tests | 20+ | `data/ai/provider/*Test.kt` |
| Repository Tests | 30+ | `data/repository/*Test.kt` |
| Data Layer Tests | 25+ | `data/*/` |
| Domain Logic Tests | 40+ | `domain/*/` |
| Integration Tests | 80+ | Various |
| Unit Tests | 100+ | Various |

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
ConfidenceRouter (routes confidence-adjusted ParsedTransaction)
    ├─ sourceStatsRepository: SourceStatsRepository
    ├─ userCorrectionRepository: UserCorrectionRepository
    ├─ classifier: TransactionClassifier
    └─ timeProvider: TimeProvider
    ↓
RoutingResult (AUTO_ACCEPT | NEEDS_REVIEW | AUTO_REJECT)
    ↓
MerchantCategoryRepository.save()
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
    ├─→ BudgetRepository
    ├─→ ExpenseRepository
    ├─→ SavingsGoalRepository
    ├─→ RecurringExpenseRepository
    └─→ AnalyticsRepository
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
    └─→ FinancialStressForecastEngine
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
    ├─→ BudgetMonitor.checkBudget()
    ├─→ AnomalyAlertOrchestrator.assess()
    └─→ MerchantCategoryRepository.learn()
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
│ 2. CloudAiPrivacyGate: CLOUD_AI_*, RECEIPT_IMAGE_CLOUD  │
│ 3. LocationPrivacyGate: EXTERNAL_GEOCODING, GPS, etc.   │
│ 4. BackupPrivacyGate: RAWBACKUP_EXPORT, ENCRYPTED_BACKUP│
└───────────────────────────────────────────────────────────┘
    ↓
PrivacyDecision (Allowed | Denied(reason))
    ↓
PrivacyAuditLogger.log(capability, decision, reason, caller)
    ↓
Proceed or Block operation
```

**Files:** `privacy/PrivacyGate.kt`, `privacy/CompositePrivacyGate.kt`, `privacy/NotificationPrivacyGate.kt`, `privacy/CloudAiPrivacyGate.kt`, `privacy/LocationPrivacyGate.kt`, `privacy/BackupPrivacyGate.kt`, `privacy/PrivacyDecision.kt`, `privacy/PrivacyCapability.kt`, `privacy/PrivacyAuditLogger.kt`, `privacy/PrivacySettings.kt`, `privacy/EffectiveCloudAiPolicy.kt`, `privacy/RawContentSanitizer.kt`

### Chain 10: Worker Infrastructure

```
WorkerSpec (configuration)
    ↓
WorkerSpecScheduler.schedule(workerName)
    ├─ Reads WorkerSpec.DEFAULTS[name]
    └─ Delegates to WorkManager
    ↓
WorkerExecutionGuard.acquire(workerName)
    ├─ Prevents concurrent execution
    └─ Timeout-based locking
    ↓
WorkerRunLogger.runStarted(workerName, runId)
    ↓
Worker execution (domain logic)
    ↓
WorkerRunLogger.runCompleted/runFailed(workerName, runId, result)
```

**Files:** `workers/WorkerSpec.kt`, `workers/WorkerSpecScheduler.kt`, `workers/WorkerExecutionGuard.kt`, `workers/WorkerRunLogger.kt`, `workers/WorkerRegistry.kt`, `workers/RetryableWorkerException.kt`, `workers/PrivacyRuntimeWorkerPolicy.kt`, `workers/WorkerRunContext.kt`

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

**Files:** `receipt/lifecycle/ReceiptLifecycleCoordinator.kt`, `receipt/lifecycle/ReceiptLinkService.kt`, `receipt/lifecycle/ReceiptMatchLifecycleService.kt`, `receipt/lifecycle/ReceiptSideEffectDispatcher.kt`, `receipt/lifecycle/ReceiptDuplicateDetector.kt`

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

### Recurring Expenses Graph (expanded)

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
    └─ Used by: OperationRunRecorder, DiagnosticsRepository
```

### Warranty Reminder Delivery Graph

```
WarrantyReminderDelivery
    ├─ DAO: WarrantyReminderDeliveryDao
    └─ Related: Warranty (via warranty_id), WarrantyLifecycleEvent
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
ConfidenceRouter (routes based on confidence scoring)
    ├─ Constructor:
    │  ├→ sourceStatsRepository: SourceStatsRepository
    │  ├→ userCorrectionRepository: UserCorrectionRepository
    │  ├→ classifier: TransactionClassifier
    │  └→ timeProvider: TimeProvider
    └─ Produces: RoutingResult(AUTO_ACCEPT | NEEDS_REVIEW | AUTO_REJECT)
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
    ├─ Provides: AppDatabase (v143, 69 entities)
    ├─ Uses: DaoModule (67 DAOs)
    └─ Provides: GroupTransactionCoordinator

DaoModule
    └─ Provides: All 67 DAOs

DiagnosticsModule
    ├─ DiagnosticEventWriter
    └─ OperationRunRecorder

ProvenanceModule
    └─ Provenance event recording

ReminderSettingsModule
    └─ BillReminderSettingsRepository

RetentionModule
    └─ RetentionRegistry with 5 targets

WorkerModule
    └─ WorkerRunLogger → WorkerRunLoggerImpl

RepositoryModules (multiple)
    ├─ SavingsRepositoryBindingsModule
    ├─ BackupRepositoryModule
    └─ Others

ServiceModule
    └─ Provides: All domain services

AiModule
    ├─ Provides: All AI services
    ├─ AiCapabilityRouter
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
    └─ Provides: IO, Default, Main dispatchers, ApplicationScope

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
    └─ SecureKeyStorage

PrivacyModule
    └─ CompositePrivacyGate, PrivacyAuditLogger, etc.
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
| **Cloud AI** | `Cloud*Service.kt` | AI services |
| **On-Device ML** | `OnDevice*Service.kt` | ML models |
| **Email (IMAP)** | `EmailReceiptIngestionService.kt` | Email receipts |
| **Bank APIs** | `BankApiIntegration.kt` | Bank connections |
| **Android Keystore** | `AtRestEncryptionService.kt`, `SecureKeyStorage.kt` | Hardware-backed encryption |
| **WorkManager** | `WorkerSpecScheduler.kt` | Background scheduling |

---

## Database Barriers

| Barrier | Purpose | File |
|---------|---------|------|
| `DatabaseReadBarrier` | Blocks reads during restore/maintenance mode | `data/database/barrier/` |
| `DatabaseWriteBarrier` | Blocks writes during restore/maintenance mode | `data/database/barrier/` |
| `RestoreMaintenanceMode` | 8-state maintenance mode, pauses workers | `data/backup/RestoreMaintenanceMode.kt` |

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

### Cross-Source Deduplication

| Component | Purpose | File |
|-----------|---------|------|
| `CrossSourceDeduplication` | Deduplicates across sources | `domain/intelligence/` |
| `SemanticDuplicateDetector` | Semantic duplicate detection | `domain/ai/service/` |
| `DetectSemanticDuplicateUseCase` | UseCase wrapper | `domain/ai/usecase/` |
| Consistency tests | Validates dedup logic | `consistency/*Test.kt` |

---

## Key Architecture Decisions

- **All expense CUD → TransactionLifecycleCoordinator**: Single entry point enforcing validation, dedup, event logging.
- **All receipt processing → ReceiptLifecycleCoordinator**: Centralizes OCR, extraction, linking via ReceiptLinkService.
- **All recurring ops → RecurringLifecycleCoordinator**: Expand→Resolve→Materialize triad with audit trail.
- **Privacy Gate Pattern**: Every capability gated through CompositePrivacyGate (fail-closed, audit-logged).
- **Worker Infrastructure**: WorkerSpec → WorkerSpecScheduler → WorkerExecutionGuard → WorkerRunLogger.
- **Database Barriers**: Read/write blocking during RestoreMaintenanceMode.
- **Backup Encryption Pipeline**: AES-256-GCM + PBKDF2 via CostbackupBundle with crash-safe RestoreJournal.

**End of Test Coverage & Cross-References**

