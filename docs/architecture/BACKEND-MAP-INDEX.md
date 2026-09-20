# 📋 COMPLETE BACKEND & DATABASE MAP INDEX

**Generated:** 2026-06-09 · **Reconciled with code:** 2026-09-21  
**Total Files Documented:** 1075 source files (538 domain + 306 data + 166 UI + 35 di (32 @Module) + 19 service + 3 startup + 2 receiver + 1 worker + 3 util + 1 diagnostics + 1 root)  
**Scope:** ExpenseTracker domain, data, and DI packages  
**Current DB Version:** v148 (baseline v145) · **DAOs:** 68 · **Entities:** 70 · **Hilt @Module files:** 32

---

## 📚 Documentation Files

### Primary Maps (NEW)

1. **[COMPLETE-BACKEND-MAP.md](./COMPLETE-BACKEND-MAP.md)** ⭐ START HERE
    - Exhaustive list of the 1075 backend/source files (principal classes per subsystem)
   - Organized by package and subpackage
   - File type, purpose, dependencies for each
   - Data flow diagrams
   - Architecture patterns
   - **Size:** ~1700 lines

2. **[BACKEND-DEPENDENCIES.md](./BACKEND-DEPENDENCIES.md)** ⭐ DEPENDENCY CHAINS
    - Test coverage summary (600+ tests)
   - 11 critical dependency chains with visualizations
   - Repository → DAO → Entity relationships
   - Service → Engine → Utility stacks
   - DI module dependency graph
   - Data sources and external integrations
   - Extension points and ports

### Existing Maps

3. **[backend-domain-map.md](./backend-domain-map.md)**
   - Domain layer organization
   - Business logic components

4. **[backend-data-map.md](./backend-data-map.md)**
   - Data layer organization
   - Repository patterns

5. **[backend-di-infrastructure-map.md](./backend-di-infrastructure-map.md)**
   - Dependency injection setup
   - Module structure

---

## 🗂️ Quick Navigation

### By Package Type

#### Domain Package (538 files)
**Location:** `app/src/main/java/com/yourname/expensetracker/domain/`

- **AI Subsystem** (64+ files)
  - Models, policies, services, use cases
  - 25 use cases covering AI capabilities

- **Analytics & Insights** (23 files)
  - Advanced analytics, anomaly detection
  - Spending insights, personality classification
  - 28 engine test files

- **Budget Management** (10 files)
  - Budget calculation, forecasting, monitoring
  - Shared budget management, autopilot engine

- **Categorization** (7 files)
  - Core categorization engine
  - Contextual inference, semantic matching

- **Data Models** (31 files)
  - Dashboard primitives, recommendations
  - Navigation, dashboard-specific models

- **Use Cases** (17 files)
  - Budget, dashboard, expense, forecast, receipt, savings, warranty

- **Utilities** (22 files)
  - Amount, currency, date/time, merchant, statistics
  - String matching, geography, hashing
  - `domain/common/Hashing.kt` — SHA-256 hash prefix utility
  - `domain/notification/RawNotificationFingerprint.kt` — SHA-256 notification fingerprinting

- **Alerts** (2 files)
  - AnomalyAlertRepository, AnomalyAlertOrchestrator
  - System anomaly detection and alerting

- **Bank Integration** (`domain/bank/`, 2+ files)
  - BankApiIntegration, BankApiConfig
  - External bank API connectivity

- **Business Expense** (`domain/business/`, 1 file)
  - BusinessExpenseReportGenerator (`BusinessExpenseRepository` lives in `data/repository/`)
  - Business-specific expense reporting

- **Carbon Footprint** (`domain/carbon/`, 1 file)
  - CarbonFootprintCalculator
  - Environmental impact tracking

- **Cash Flow** (1 file)
  - CashFlowCalculator
  - Cash flow analysis and projections

- **Core Types** (~28 files)
  - `domain/core/time/` — PeriodRange, PeriodKind (typed time primitives)
  - `domain/core/money/` — CurrencyCode, MoneyAmount, MoneyAggregate, MoneyNormalizationEngine, etc. (type-safe money)
  - `domain/core/validation/` — EntityTimeValidation

- **Diagnostics** (14 files)
  - DatabaseIntegrityScanner, CompositeDiagnosticEventWriter, DiagnosticReasonCode
  - Debug utilities: DebugIssueDetector, DebugIssue, DebugData, AiRuntimeDiagnostics, NotificationSeeder, ReceiptDebugExporter

- **Data Transfer Objects (DTO)** (4 files)
  - AiArtifactRecord, CategoryRef, ReceiptItemCategorizationSnapshot, ReviewPriorityInput

- **Privacy** (34+ files)
  - PrivacyGate, PrivacyCapability, CompositePrivacyGate, 4 sub-gates
  - PrivacyAuditLogger, RedactionSanitizer, PrivacySettings, PrivacySettingsRepository, etc.
  - EffectiveCloudAiPolicy, CloudPayloadPolicy, RawStorageMode, RawContentSanitizer

- **Reminder** (3 files)
  - BillReminderManager, BillReminderSettings, BillReminderSettingsRepository
  - Bill payment reminders (`BillReminderWorker` lives in `service/reminder/`)

- **Transaction** (~24 files)
  - ExpenseSource, LifecycleEventType, DeduplicationMode, CreateExpenseRequest, CreateExpenseResult, ExpenseUpdates, SideEffectMode
  - `lifecycle/` — TransactionLifecycleCoordinator, TransactionSideEffectDispatcher
  - `validation/` — transaction validation rules

- **Workers** (19 domain files)
  - WorkerSpec, WorkerSpecScheduler, WorkerExecutionGuard, WorkerRunLogger, WorkerRegistry
  - WorkerRunContext, RetryableWorkerException, PrivacyRuntimeWorkerPolicy, NotificationPermissionChecker
  - WorkerLease/WorkerLeaseRegistry(+Impl), WorkerDrainController(+NoOp), WorkerGuardVerifier
  - WorkerReasonCodes, WorkerTerminalDiagnosticSink, FileWorkerTerminalDiagnosticSink, ScheduleResult
  - CoroutineWorkers live elsewhere (10 total, 7 registered in `WorkerRegistry`)

- **Recurring** (~10 files)
  - RecurringOccurrenceExpander, OccurrenceConflictResolver, RecurringPlanProjectionService
  - `lifecycle/` — RecurringLifecycleCoordinator, RecurringRuleLifecycleCoordinator, RecurringOccurrenceMaterializer, RecurringLifecycleEventWriter (~7 files)

- **Receipt Lifecycle** (~16 files in `domain/receipt/lifecycle/`)
  - `domain/receipt/lifecycle/` — ReceiptLifecycleCoordinator, ReceiptLinkService, ReceiptAssetStore, ReceiptInputValidator, ReceiptDuplicateDetector, ReceiptSideEffectDispatcher, BankStatementLifecycleProcessor
  - `domain/receiptmatching/` — ReceiptTransactionMatcher

- **Provenance** (28 files)
  - SourceLinkWriter, SourceLinkQueryService, SourceLinkBackfillWorker, SourceIdentityKeyFactory
  - PendingReviewSourceLinkService, PendingReviewSourceLinkPromoter
  - Payload factories for bank, notification, receipt, import, pending-review sources

- **Side Effects** (20 files)
  - Dedicated side-effect domain package for post-transaction side effects

- **Engine** (standalone package)
  - `DashboardFollowThroughEngine` — dashboard follow-through tracking

- **Negotiation** (2 files)
  - SmartBillNegotiationEngine, MarketRateProvider

- **Investment** (1 file)
  - InvestmentTracker

- **Export** (7 files)
  - FreshBooks, QuickBooksIIF, XeroCSV export formatters

- **Backup** (3 files)
  - BackupPrivacyMode, DatabaseOperationResults, DatabaseBackupRepository

- **Currency** (7 files)
  - CurrencyConverter, CurrencyResolution, exchange rate services/settings

- **Lifestyle** (1 file)
  - LifestyleInflationDetector

- **Logic** (7 files)
  - SynthesisEngine, RecurringExpenseEngine, CustomSplitParser, RecurrenceCalculator

- **Split** (1 file)
  - EnhancedSplitManager

- **Price** (1 file)
  - PriceProtectionTracker

- **Performance** (1 file)
  - ImageCache

- **Challenge** (1 file)
  - SpendingChallengeManager

- **Common** (1 file)
  - Hashing (SHA-256 hash prefix utility)

- **Config** (1 file)
  - AppConfig

- **Income** (1 file)
  - RecurringIncomeTracker

- **Text** (3 files)
  - UiTextArg, DomainTextKeys, DashboardTextKeys

- **Other Subsystems**
  - Forecasting, location, parsing (`domain/parser/parsers/`), receipt
  - Health, savings, subscriptions, tax
  - Notification capture (12 files), notification money (1 file)
  - Notification fingerprinting — `domain/notification/RawNotificationFingerprint`
  - Shared hashing — `domain/common/Hashing.kt`

#### Data Package (306 files)
**Location:** `app/src/main/java/com/yourname/expensetracker/data/`

- **Database** (151 files)
   - 1 main database (AppDatabase.kt, v148) + DatabaseMigrations.kt + DatabaseSchemaPolicy.kt (DB ownership policy v2) + RoomDomainTransactionRunner.kt + GroupTransactionCoordinator.kt
   - 68 DAOs (data access objects)
   - 70 Entities (Room-managed tables, all registered in AppDatabase)
   - 6+ composite models, 1 converter file (22 `@TypeConverter` methods)

- **Repositories** (70 files)
   - 54 data-layer implementations + 16 domain-layer interfaces
   - Expense, budget, analytics, currency
   - Merchant, location, notification
   - Savings, subscription, warranty
   - AutomatedSavingsRuleStateRepository, SavingsContributionHistoryRepository, SpendingChallengeRepository
   - DeterministicExpenseExportPager, GroupsRepository (interface), AnomalyAlertRepositoryImpl, SharedExpenseDataPortAdapter
   - TaxSettingsRepository, WidgetStyleRepository, NaturalLanguageExpenseQueryRepository

- **AI Providers** (43 files)
   - Cloud, OnDevice, Hybrid, NoOp implementations
   - 6 core capabilities with Cloud/OnDevice/Hybrid/NoOp variants, plus receipt item categorization, warranty extraction, notification parsing, review priority scoring, semantic duplicate detection (+ SmartReceiptAssistService)
   - SmartReceiptAssistService, StrictAiJsonParsing, DashboardBriefingPromptFormatter, DashboardBriefingResponseParser
   - Several NoOp* services
   - OnDevice notification parser, review priority scorer, semantic duplicate detector
   - All Cloud*Service implementations (8+ files)
   - Several Hybrid*Service implementations
   - OkHttpCloudProviderConnectionTester (data/ai root)

- **AI Provider Internals** (7 files)
   - `data/ai/provider/internal/` — CloudCorrelation, CloudJsonParser, CloudPiiSanitizer, CloudRetryPolicy
   - `data/ai/provider/` — DashboardBriefingPromptFormatter, DashboardBriefingResponseParser, StrictAiJsonParsing

- **AI Workers** (2 files)
   - `data/ai/worker/` — AiWorkSchedulerImpl, DailyBriefingWorker

- **Email Parsers** (5 files)
   - `data/email/EmailReceiptIngestionService.kt`
   - `data/email/provider/` — AmazonReceiptParser, AppleReceiptParser, EmailReceiptParser, UberReceiptParser

- **Location Services** (11 files)
   - `data/location/` — CompositeGeocodingService, NominatimGeocodingService, GeoapifyGeocodingService, GooglePlacesGeocodingService, PhotonGeocodingService, OverpassNearbyService, AndroidForegroundLocationProvider, LocationBackfillWorker, MerchantKeyBackfillWorker
   - `data/location/internal/` — CancellableHttpCall, LogSanitizer

- **Privacy** (9 files)
   - `data/privacy/` — PrivacySettingsRepositoryImpl, BackupEncryptionService, ExportAnonymizer, DataRetentionWorker, AtRestEncryptionService, PrivacyAuditLoggerImpl, DefaultCloudPayloadRedactor, DefaultCloudPayloadPolicy, DefaultSensitiveHashingService

- **Security** (2 files)
   - `data/security/` — BankTokenCipher, SecureKeyStorage

- **Service Layer** (3 files)
   - `data/service/` — AndroidNotificationService, AndroidNotificationPermissionChecker
   - `data/speech/` — AndroidSpeechInputGateway

- **CSV Import** (1 file)
   - `util/CsvExpenseImporter.kt` — Bulk CSV expense import via TransactionLifecycleCoordinator
   - Supports date, amount, merchant, category, description columns
   - Routes every row through full lifecycle: validate → normalize → dedupe → insert

- **Backup** (18 files)
   - `data/backup/` — BackupVerifier, CostbackupBundle, RestoreJournal, RestoreMaintenanceMode
   - Restore/restore-barrier infrastructure: RestoreDatabaseOpener, RestoreJournalImporter, RestoreInternalWriteScope, RestoreDiagnosticsSink, SqliteSnapshotCreator, AppOperationalState
   - Write/read barriers: DatabaseWriteBarrier, DatabaseReadBarrier (+FlowExt), DatabaseAccessModels
   - Maintenance runners + safe diagnostic sinks: MaintenanceOperationRunner, MaintenanceSafeDiagnosticSink, DataStoreMaintenanceSafeDiagnosticSink, TimberMaintenanceSafeDiagnosticSink

- **Rescue** (4 files)
   - `data/rescue/` — FinancialRescueCoordinator, FinancialRescueSnapshot, RescueActivity, RescueConfig (raw SQLite import bypassing migration chain for pre-v145 DBs)

- **Store** (1 file)
   - `data/store/` — ExpenseReadStore (write paths go through TransactionLifecycleCoordinator)

- **Negotiation** (data layer)
   - `data/negotiation/` — MarketRateProvider data implementations

- **Tax** (data layer)
   - `data/tax/` — Tax configuration data

- **Provider** (data layer)
   - `data/provider/` — MerchantCategoryProvider

#### DI Package (32 Hilt @Module files)

**Location:** `com.yourname.expensetracker.di`

- 32 Hilt @Module files (all in `di/`) + `EmptyStatePresentationModule` in `ui/` + qualifier files (`NetworkQualifiers`, `ApplicationScope`) + `EmptyStateRegistryInitializer` contract in `di/`
- 1 `@EntryPoint` (`AppStartupDelegate` in `startup/`)
- Database, DAO, Repository bindings
- AI, services, location provider modules
- Network, time, currency, parsing modules
- Email ingestion, export, security modules
- Diagnostics, provenance, reminder settings, retention, worker logging
- Negotiation, natural language, OCR improvements, dashboard contracts, savings

#### App Services Package (19 files)
**Location:** `app/src/main/java/com/yourname/expensetracker/service/`

- **Notification Capture** (3 files)
  - `NotificationCaptureService` — Android NotificationListenerService, captures notifications
  - `NotificationFilter` — Filters captured notifications by package/type
  - `NotificationFilterDecision` — Filter decision + reason models

- **Recommendation System** (6 files)
  - `RecommendationCacheService` — In-memory LRU cache with 7-day TTL for dashboard recommendations
  - `RecommendationDeduplicator` — Signature-based deduplication per merchant/category/target
  - `RecommendationDismissalHandler` — Handles user dismissal of recommendation cards
  - `RecommendationInvalidator` — Invalidates stale/expired recommendations on transaction changes
  - `RecommendationLifecycleManager` — Manages recommendation lifecycle: expiration, cleanup, threshold refresh
  - `RecommendationStateManager` — Reactive StateFlow for UI observation, max 5 limit, user-specific

- **Workers** (5 files)
  - `receiptmatching/ReceiptMatchingWorker` — Background receipt-to-transaction matching (registered: `receipt_matching`)
  - `reminder/BillReminderWorker` — Periodic bill reminder delivery, every 6h (registered: `bill_reminder_periodic`)
  - `reminder/DismissReminderActionWorker` — Durable reminder dismiss action
  - `reminder/SnoozeReminderActionWorker` — Durable reminder snooze action
  - `warranty/WarrantyExpirationWorker` — Warranty expiry notification worker (registered: `warranty_expiration_check`)

- **Receivers** (4 files)
  - `reminder/SnoozeReminderReceiver` — Hilt @AndroidEntryPoint broadcast receiver for reminder snooze
  - `reminder/DismissReminderReceiver` — Hilt @AndroidEntryPoint broadcast receiver for reminder dismiss
  - `receiver/BootReceiver` — BOOT_COMPLETED / MY_PACKAGE_REPLACED receiver
  - `receiver/ServiceRestartReceiver` — Service keep-alive receiver

- **Utilities** (2 files)
  - `NavigationTargetResolver` — Resolves navigation targets from recommendations
  - `TransactionFilterSerializer` — Serializes transaction filters for deduplication signatures

- **Legacy** (1 file)
  - `debug/LegacyDataMigrationService` — One-time data migration from older app versions

- **Root Utilities** (1 file)
  - `util/CsvExpenseImporter.kt` — Bulk CSV expense import via TransactionLifecycleCoordinator

#### Additional Packages

- **Startup** (3 files)
  - `AppStartupCoordinator`, `AppStartupDelegate` (with `@EntryPoint`), `AppBackgroundLifecycleObserver`

- **Worker** (top-level, 1 file)
  - `NotificationIntakeWorker` — WorkManager notification intake worker

- **Util** (top-level, 3 files)
  - `ImportCoordinator`, `JsonExpenseImporter`, `CsvExpenseImporter`

---

## 🎯 By Architecture Layer

### Database Layer
- **Core:** `AppDatabase.kt` (Room database, v148, baseline v145 — see `DatabaseSchemaPolicy.kt`)
- **Migrations:** `DatabaseMigrations.kt` (`MIGRATION_145_146/146_147/147_148`)
- **Access:** 68 DAOs for direct table access
- **Entities:** 70 Room-managed entities (all registered in AppDatabase)
- **Models:** 6+ composite query result models
- **Coordinators:** `GroupTransactionCoordinator.kt` (atomic group transactions), `RoomDomainTransactionRunner.kt` (DomainTransactionRunner impl)

### Repository Layer
- **70 repositories** providing business logic (54 data + 16 domain interfaces)
- Handle data transformation and aggregation
- Implement domain interfaces
- Manage database transactions

### Domain/Business Logic Layer
- **538 files** implementing business rules
- Engines, services, use cases, value objects
- No database dependencies
- Clean separation from infrastructure

### DI/Infrastructure Layer
- **32 Hilt @Module files + 1 @EntryPoint** managing dependencies
- Database, network, geocoding setup
- AI capability routing
- Service configuration

---

## 🔍 Files by Type

### Database-Related (151 files)
- DAOs (68), Entities (70), Models (6), Converters (1), Coordinators/Runner (2), Database + Migrations + Schema policy (3)
- **Key files:** `ExpenseDao.kt`, `Expense.kt`, `AppDatabase.kt`
- **Guardrail:** direct DAO access outside the tiered allowlist (`scripts/guardrails/dao-approved-files.txt`) is CI-enforced

### Repository-Related (70 files)
- Data-layer repositories (54), Domain interfaces (16)
- **Key files:** `ExpenseRepository.kt`, `BudgetRepository.kt`, `CategoryRepository.kt`

### AI-Related (110+ files)
- Domain services (18 in `domain/ai/service/`), Data providers (43 files in `data/ai/`), Workers (2 in `data/ai/worker/`)
- **Key files:** `AiCapabilityRouter.kt`, `CloudCategorizationAssistService.kt`

### Engine/Business Logic (70+ files)
- Calculation, analysis, decision engines (28 `*Engine.kt` files)
- **Key files:** `CategorizationEngine.kt`, `BudgetCalculator.kt`, `InsightsEngine.kt`, `InvestmentTracker.kt`

### Side Effects (20 files)
- Post-transaction side-effect orchestration

### Provenance (28 files)
- Source-link tracking, pending-review promotion, event metadata

### Privacy (34+ files)
- Multi-gate privacy system, audit logging, PII sanitization

### Utility (25+ files)
- Text processing, math, time, geo utilities
- **Key files:** `MerchantKeyGenerator.kt`, `AmountUtils.kt`

### Models & Data Structures (40+ files)
- Request/response models, value objects
- **Key files:** `AiModels.kt`, `DashboardPrimitives.kt`

### ViewModels (41 files)
- UI state management (41 @HiltViewModel)
- **Key files:** `DashboardViewModel.kt`, `ExpenseListViewModel.kt`

---

## 📊 Statistics

| Metric | Count |
|--------|-------|
| **Total Source Files** | 1075 |
| Domain files | 538 |
| Data files | 306 |
| UI files | 166 |
| DI files | 32 |
| Hilt @Module files | 32 |
| **Database Entities** | 70 |
| **DAOs** | 68 |
| **Repositories** | 70 (54 data + 16 domain interfaces) |
| **Use Cases** | 17 |
| **ViewModels** | 41 |
| **Workers** | 10 CoroutineWorkers (7 in `WorkerRegistry` + `NotificationIntakeWorker` + 2 reminder action workers) |
| **Engines** | 28 named `*Engine` files |
| **AI Services** | 18 (in `domain/ai/service/`) |
| **Parsers** | 21 (`*Parser*.kt` files, across all layers) |
| **Geocoders** | 5 |
| **Email Receipt Parsers** | 4 |
| **Test Files** | 683+ (unit) + 28 (instrumented) |

---

## 🔗 Key Dependency Chains

### 1. Expense Ingestion
```
Notification → Parser → TransactionLifecycleCoordinator → Expense Entity → Database
```

### 2. Categorization
```
Merchant Name → CategorizationEngine → (AI if needed) → Repository → Database
```

### 3. Dashboard
```
UseCase → Repositories → Engines (parallel) → Dashboard Models → UI
```

### 4. Receipt Processing
```
Receipt Image → OCR → Items → AI Categorization → Repository → Database
```

### 5. Budget Forecasting
```
UseCase → Monte Carlo Simulator → Scenario Analysis → UI
```

### 6. Shared Expenses
```
AddGroupExpenseUseCase → Settlement Calculator → Database
```

### 7. Natural Language Query
```
Query Text → AI Interpretation → Query Execution → Transaction Results → Navigation
```

### 8. Transaction Lifecycle
```
CreateExpenseRequest → validate → normalize → dedupe → atomicInsert + event log → side effects
```

### 9. Privacy Gate
```
Feature Request → CompositePrivacyGate (4 sub-gates) → PrivacyDecision → Allowed/Blocked
```

### 10. Worker Infrastructure
```
WorkerSpec → WorkerSpecScheduler → WorkManager → WorkerExecutionGuard → execution → logging
```

### 11. Receipt Match Lifecycle
```
Receipt captured → validate → dedup → persist → link to expense → side effects (warranty, categorize, match, price-protect)
```

---

## 🎨 Core Architecture Patterns

| Pattern | Usage | Example |
|---------|-------|---------|
| **Repository** | Data abstraction | ExpenseRepository |
| **Use Case** | Single responsibility | CategorizeExpenseUseCase |
| **Strategy** | Multiple implementations | AI providers (Cloud/OnDevice/NoOp) |
| **Adapter** | Boundary crossing | Data ↔ Domain adaptation |
| **Factory** | Creation logic | ParserRegistry, AppDatabase |
| **Decorator** | Enhanced behavior | HybridAiServices |
| **Observer** | Reactive updates | Flow-based repositories |
| **Builder** | Complex construction | AI input builders |
| **Singleton** | Single instance | Repositories via DI |
| **Chain of Responsibility** | Sequential processing | Parsing pipeline |

---

## 🔐 Security Components

| Component | Purpose | File |
|-----------|---------|------|
| **Token Encryption** | Bank token protection | `BankTokenCipher.kt` |
| **Key Storage** | Secure key management | `SecureKeyStorage.kt` |
| **At-Rest Encryption** | AES-256-GCM via Android Keystore | `AtRestEncryptionService.kt` |
| **PII Sanitization** | Privacy protection | `CloudPiiSanitizer.kt` |
| **Log Sanitizer** | Safe logging | `LogSanitizer.kt` |
| **Backup Encryption** | AES-256-GCM + PBKDF2 for backups | `BackupEncryptionService.kt` |
| **Export Anonymizer** | PII stripping for exports | `ExportAnonymizer.kt` |

---

## 🧪 Test Coverage

**Total Tests:** 683+ unit + 28 instrumented (coverage expanding)

### High-Coverage Areas
- Consistency tests (13 files)
- AI provider tests (24 files)
- Repository tests (44 files)
- Analytics engine tests (35 files)
- Parser tests (15 files)
- Engine tests (47 `*Engine*Test.kt` files across all engines)
- Privacy tests (22 files)
- Budget tests (38 files)

### Key Test Files
- `ExpenseDao.kt` - Database DAO testing
- `CloudCategorizationAssistService.kt` - AI service testing
- `CrossParserConsistencyTest.kt` - Parser validation
- `FinancialArithmeticPrecisionTest.kt` - Money math precision
- `SynthesisEngineTest.kt` - Core logic synthesis
- `CategorizationEngineTest.kt` - Categorization engine
- `BudgetForecastingEngineTest.kt` - Budget forecasting
- `InvestmentTrackerTest.kt` - Investment tracking

---

## 🚀 Entry Points for Different Tasks

### "I need to add a new expense category"
1. Check `CategoryRepository.kt`
2. Review `CategoryDao.kt`
3. Update `Category.kt` entity
4. See `CategorizationEngine.kt`

### "I need to add a new AI capability"
1. Create service class in `domain/ai/service/`
2. Create implementations in `data/ai/provider/`
3. Add to `AiModule.kt`
4. Update `AiCapabilityRouter.kt`

### "I need to add a new data source"
1. Create parser in `domain/parser/parsers/`
2. Register in `AppParserRegistry.kt`
3. Add DAO if new table needed
4. Create repository

### "I need to add a new report"
1. Create use case in `domain/usecase/`
2. Create engines for calculations
3. Add repository calls
4. Create models for output

### "I need to add notifications"
1. Check `NotificationService.kt`
2. Review `NotificationRepository.kt`
3. See `RawNotificationDao.kt`
4. Check `NotificationProcessingPipeline.kt"

---

## 📖 Reading Guide

### For New Backend Engineers
1. Start with `COMPLETE-BACKEND-MAP.md`
2. Read the "Database Layer" section
3. Read the "Repository Layer" section
4. Study a specific flow (e.g., "Expense Ingestion")
5. Review the relevant source files

### For AI/ML Integration
1. Check the AI Subsystem section in `COMPLETE-BACKEND-MAP.md`
2. Review `BACKEND-DEPENDENCIES.md` section "Categorization Service Stack"
3. Study `AiCapabilityRouter.kt`
4. Review specific provider implementations

### For Database Schema Changes
1. Review all 70 entities in `COMPLETE-BACKEND-MAP.md`
2. Check DAOs and repositories that use them
3. Consider migrations (current version: v148, baseline v145 — see `DatabaseSchemaPolicy.kt` and `docs/ci/DB_ROOM_INVENTORY.md`)
4. Review existing tests

### For Adding New Features
1. Identify the domain package needed
2. Create necessary entities/DAOs
3. Create repositories
4. Create use cases
5. Create engines/services
6. Wire up in DI modules

---

## 🔗 Related Documentation

- `UI_REFERENCE_INDEX.md` - UI layer mapping
- `UI_INTEGRATION_SUMMARY.md` - UI ↔ Backend integration
- `domain-quick-reference.md` - Domain layer quick reference
- `clean-architecture-violations-report.md` - Architecture analysis

---

## 📝 Document Stats

| Document | Lines | Size |
|----------|-------|------|
| **COMPLETE-BACKEND-MAP.md** | 1700+ | ~132KB |
| **BACKEND-DEPENDENCIES.md** | 700+ | ~25KB |
| **This Index** | 600+ | ~26KB |
| **Total** | 3,100+ | ~183KB |

---

## ✅ Completeness Checklist

- ✅ Domain (538 files) and data (306 files) packages covered — principal classes row-listed, remainder noted per package
- ✅ All 32 Hilt @Module files + @EntryPoint listed
- ✅ File-by-file breakdown with:
  - ✅ File path
  - ✅ Class name
  - ✅ Purpose (1 sentence)
  - ✅ Type (Entity, DAO, Repository, UseCase, etc.)
  - ✅ Dependencies
  - ✅ Test coverage indicator
- ✅ 11 major dependency chains documented
- ✅ Data flow diagrams
- ✅ Architecture patterns
- ✅ Test coverage summary
- ✅ Extension points identified

---

## 🎯 Next Steps

### For Contributors
1. Review this index
2. Read `COMPLETE-BACKEND-MAP.md` for your area
3. Check `BACKEND-DEPENDENCIES.md` for data flows
4. Study relevant test files
5. Follow existing patterns

### For Architecture Reviews
1. Use dependency chains from `BACKEND-DEPENDENCIES.md`
2. Check for cross-package dependencies
3. Validate layer separation
4. Review test coverage

### For Maintenance
1. Keep maps updated when adding files
2. Document new patterns
3. Add new test coverage notes
4. Update dependency chains

---

**Last Updated:** 2026-09-21  
**Version:** 2.4 - Reconciled with codebase (1075 source files, DB v148, 68 DAOs, 70 entities)  
**Status:** ✅ Production-Ready Documentation
