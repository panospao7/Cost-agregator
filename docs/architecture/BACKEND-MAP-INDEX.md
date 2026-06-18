# 📋 COMPLETE BACKEND & DATABASE MAP INDEX

**Generated:** 2026-06-09  
**Total Files Documented:** 1050 source files (520 domain + 305 data + 167 UI + 35 di (32 @Module) + 17 service + 3 startup + 2 receiver + 1 worker + 3 util)  
**Scope:** ExpenseTracker domain, data, and DI packages  
**Current DB Version:** v147 · **DAOs:** 68 · **Entities:** 69 · **Hilt @Module files:** 32

---

## 📚 Documentation Files

### Primary Maps (NEW)

1. **[COMPLETE-BACKEND-MAP.md](./COMPLETE-BACKEND-MAP.md)** ⭐ START HERE
    - Exhaustive list of ALL 1050 backend files
   - Organized by package and subpackage
   - File type, purpose, dependencies for each
   - Data flow diagrams
   - Architecture patterns
   - **Size:** ~8000 lines

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

#### Domain Package (520 files)
**Location:** `app/src/main/java/com/yourname/expensetracker/domain/`

- **AI Subsystem** (64+ files)
  - Models, policies, services, use cases
  - 25 use cases covering AI capabilities

- **Analytics & Insights** (16+ files)
  - Advanced analytics, anomaly detection
  - Spending insights, personality classification
  - 28 engine test files

- **Budget Management** (8+ files)
  - Budget calculation, forecasting, monitoring
  - Shared budget management, autopilot engine

- **Categorization** (7 files)
  - Core categorization engine
  - Contextual inference, semantic matching

- **Data Models** (24+ files)
  - Dashboard primitives, recommendations
  - Navigation, dashboard-specific models

- **Use Cases** (13 files)
  - Budget, dashboard, expense, forecast, receipt, savings, warranty

- **Utilities** (26+ files)
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

- **Business Expense** (`domain/business/`, 2 files)
  - BusinessExpenseReportGenerator, BusinessExpenseRepository
  - Business-specific expense reporting

- **Carbon Footprint** (`domain/carbon/`, 1 file)
  - CarbonFootprintCalculator
  - Environmental impact tracking

- **Cash Flow** (1 file)
  - CashFlowCalculator
  - Cash flow analysis and projections

- **Core Types** (~10 files)
  - `domain/core/time/` — PeriodRange, PeriodKind (typed time primitives)
  - `domain/core/money/` — CurrencyCode, MoneyAmount, MoneyAggregate, MoneyNormalizationEngine, etc. (type-safe money)

- **Diagnostics** (14 files)
  - DatabaseIntegrityScanner, DatabaseOperationResults, ServiceDiagnostics
  - Debug utilities: DebugIssueDetector, DebugIssue, DebugData, AiRuntimeDiagnostics, NotificationSeeder, ReceiptDebugExporter

- **Data Transfer Objects (DTO)** (4 files)
  - AiArtifactRecord, CategoryRef, ReceiptItemCategorizationSnapshot, ReviewPriorityInput

- **Privacy** (34+ files)
  - PrivacyGate, PrivacyCapability, CompositePrivacyGate, 4 sub-gates
  - PrivacyAuditLogger, RedactionSanitizer, PrivacySettings, PrivacySettingsRepository, etc.
  - EffectiveCloudAiPolicy, CloudPayloadPolicy, RawStorageMode, RawContentSanitizer

- **Reminder** (1 file)
  - BillReminderManager
  - Bill payment reminders

- **Transaction** (~10 files)
  - ExpenseSource, LifecycleEventType, DeduplicationMode, CreateExpenseRequest, CreateExpenseResult, ExpenseUpdates, SideEffectMode
  - `lifecycle/` — TransactionLifecycleCoordinator, TransactionSideEffectDispatcher
  - `validation/` — transaction validation rules

- **Workers** (14 domain files)
  - WorkerSpec, WorkerSpecScheduler, WorkerExecutionGuard, WorkerRunLogger, WorkerRegistry
  - WorkerRunContext, RetryableWorkerException, PrivacyRuntimeWorkerPolicy, NotificationPermissionChecker

- **Recurring** (~7 files)
  - RecurringOccurrenceExpander, OccurrenceConflictResolver, RecurringPlanProjectionService
  - `lifecycle/` — RecurringLifecycleCoordinator, RecurringOccurrenceMaterializer (~2 files)

- **Receipt Lifecycle** (~7+ files)
  - `domain/receipt/lifecycle/` — ReceiptLifecycleCoordinator, ReceiptLinkService, ReceiptAssetStore, ReceiptInputValidator, ReceiptDuplicateDetector, ReceiptSideEffectDispatcher, BankStatementLifecycleProcessor
  - `domain/receiptmatching/` — ReceiptTransactionMatcher

- **Provenance** (28 files)
  - SourceLinkWriter, SourceLinkQueryService, SourceLinkBackfillWorker, SourceIdentityKeyFactory
  - PendingReviewSourceLinkService, PendingReviewSourceLinkPromoter
  - Payload factories for bank, notification, receipt, import, pending-review sources

- **Side Effects** (19 files)
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
  - CurrencyConverter, CurrencyNormalizer, exchange rate services

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
  - Notification capture (10 files), notification money (1 file)
  - Notification fingerprinting — `domain/notification/RawNotificationFingerprint`
  - Shared hashing — `domain/common/Hashing.kt`

#### Data Package (305 files)
**Location:** `app/src/main/java/com/yourname/expensetracker/data/`

- **Database** (109+ files)
   - 1 main database (AppDatabase.kt, v147)
   - 68 DAOs (data access objects)
   - 69 Entities (Room-managed tables, all registered in AppDatabase)
   - 6+ composite models

- **Repositories** (63 files)
   - 47 data-layer implementations + 16 domain-layer interfaces
   - Expense, budget, analytics, currency
   - Merchant, location, notification
   - Savings, subscription, warranty
   - AutomatedSavingsRuleStateRepository, SavingsContributionHistoryRepository, SpendingChallengeRepository
   - DeterministicExpenseExportPager, GroupsRepository (interface), AnomalyAlertRepositoryImpl, SharedExpenseDataPortAdapter
   - TaxSettingsRepository, WidgetStyleRepository, NaturalLanguageExpenseQueryRepository

- **AI Providers** (44+ files)
   - Cloud, OnDevice, Hybrid, NoOp implementations
   - 8 capability types × 4 implementations (+ SmartReceiptAssistService)
   - SmartReceiptAssistService, StrictAiJsonParsing, DashboardBriefingPromptFormatter, DashboardBriefingResponseParser
   - Several NoOp* services
   - OnDevice notification parser, review priority scorer, semantic duplicate detector
   - All Cloud*Service implementations (8+ files)
   - Several Hybrid*Service implementations

- **AI Provider Internals** (7 files)
   - `data/ai/provider/internal/` — CloudCorrelation, CloudJsonParser, CloudPiiSanitizer, CloudRetryPolicy, DashboardBriefingPromptFormatter, DashboardBriefingResponseParser, StrictAiJsonParsing

- **AI Workers** (1 file)
   - `data/ai/worker/` — DailyBriefingWorker

- **Email Parsers** (5 files)
   - `data/email/EmailReceiptIngestionService.kt`
   - `data/email/provider/` — AmazonReceiptParser, AppleReceiptParser, EmailReceiptParser, UberReceiptParser

- **Location Services** (11 files)
   - `data/location/` — CompositeGeocodingService, NominatimGeocodingService, GeoapifyGeocodingService, GooglePlacesGeocodingService, PhotonGeocodingService, OverpassNearbyService, AndroidForegroundLocationProvider, LocationBackfillWorker, MerchantKeyBackfillWorker
   - `data/location/internal/` — CancellableHttpCall, LogSanitizer

- **Privacy** (7 files)
   - `data/privacy/` — PrivacySettingsRepositoryImpl, BackupEncryptionService, ExportAnonymizer, DataRetentionWorker, AtRestEncryptionService, PrivacyAuditLoggerImpl, DefaultCloudPayloadRedactor

- **Security** (2 files)
   - `data/security/` — BankTokenCipher, SecureKeyStorage

- **Service Layer** (2 files)
   - `data/service/` — AndroidNotificationService
   - `data/speech/` — AndroidSpeechInputGateway

- **CSV Import** (1 file)
   - `util/CsvExpenseImporter.kt` — Bulk CSV expense import via TransactionLifecycleCoordinator
   - Supports date, amount, merchant, category, description columns
   - Routes every row through full lifecycle: validate → normalize → dedupe → insert

- **Backup** (4 files)
   - `data/backup/` — BackupVerifier, CostbackupBundle, RestoreJournal, RestoreMaintenanceMode

- **Rescue** (data layer)
   - `data/rescue/` — Financial rescue path (raw SQLite import bypassing migration chain)

- **Negotiation** (data layer)
   - `data/negotiation/` — MarketRateProvider data implementations

- **Tax** (data layer)
   - `data/tax/` — Tax configuration data

- **Store** (data layer)
   - `data/store/` — Data store preferences

- **Provider** (data layer)
   - `data/provider/` — Content provider support

#### DI Package (32 Hilt @Module files)

**Location:** `com.yourname.expensetracker.di`

- 32 Hilt @Module files (31 in `di/` + `EmptyStateModule` in `ui/`)
- 1 `@EntryPoint` (`AppStartupDelegate` in `startup/`)
- Database, DAO, Repository bindings
- AI, services, location provider modules
- Network, time, currency, parsing modules
- Email ingestion, export, security modules
- Diagnostics, provenance, reminder settings, retention, worker logging
- Negotiation, natural language, OCR improvements, dashboard contracts, savings

#### App Services Package (17 files)
**Location:** `app/src/main/java/com/yourname/expensetracker/service/`

- **Notification Capture** (2 files)
  - `NotificationCaptureService` — Android NotificationListenerService, captures notifications
  - `NotificationFilter` — Filters captured notifications by package/type

- **Recommendation System** (7 files)
  - `RecommendationCacheService` — In-memory LRU cache with TTL for dashboard recommendations
  - `RecommendationDeduplicator` — Signature-based deduplication per merchant/category/target
  - `RecommendationDismissalHandler` — Handles user dismissal of recommendation cards
  - `RecommendationInvalidator` — Invalidates stale/expired recommendations on transaction changes
  - `RecommendationLifecycleManager` — Manages recommendation lifecycle: expiration, cleanup, threshold refresh
  - `RecommendationStateManager` — Reactive StateFlow for UI observation, max 5 limit, user-specific
  - `RecommendationCacheService` — LRU cache with 7-day TTL

- **Workers** (3 files)
  - `BillReminderWorker` — Periodic bill reminder delivery
  - `ReceiptMatchingWorker` — Background receipt-to-transaction matching
  - `WarrantyExpirationWorker` — Warranty expiry notification worker

- **Receivers** (2 files)
  - `SnoozeReminderReceiver` — Hilt @AndroidEntryPoint broadcast receiver for reminder snooze
  - `DismissReminderReceiver` — Hilt @AndroidEntryPoint broadcast receiver for reminder dismiss

- **Utilities** (2 files)
  - `NavigationTargetResolver` — Resolves navigation targets from recommendations
  - `TransactionFilterSerializer` — Serializes transaction filters for deduplication signatures

- **Legacy** (1 file)
  - `LegacyDataMigrationService` — One-time data migration from older app versions

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
- **Core:** `AppDatabase.kt` (Room database, v147)
- **Access:** 68 DAOs for direct table access
- **Entities:** 69 Room-managed entities (all registered in AppDatabase)
- **Models:** 6+ composite query result models
- **Coordinator:** `GroupTransactionCoordinator.kt`

### Repository Layer
- **63 repositories** providing business logic (47 data + 16 domain interfaces)
- Handle data transformation and aggregation
- Implement domain interfaces
- Manage database transactions

### Domain/Business Logic Layer
- **520 files** implementing business rules
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

### Database-Related (178+ files)
- DAOs (68), Entities (69), Models (6+), Converters (1), Coordinator (1), Database (1), BackgroundJobRun (1)
- **Key files:** `ExpenseDao.kt`, `Expense.kt`, `AppDatabase.kt`

### Repository-Related (63 files)
- Data-layer repositories (47), Domain interfaces (16)
- **Key files:** `ExpenseRepository.kt`, `BudgetRepository.kt`, `CategoryRepository.kt`

### AI-Related (110+ files)
- Domain services (32+), Data providers (44+), Workers (8+)
- **Key files:** `AiCapabilityRouter.kt`, `CloudCategorizationAssistService.kt`

### Engine/Business Logic (70+ files)
- Calculation, analysis, decision engines (28 `*Engine.kt` files)
- **Key files:** `CategorizationEngine.kt`, `BudgetCalculator.kt`, `InsightsEngine.kt`, `InvestmentTracker.kt`

### Side Effects (19 files)
- Post-transaction side-effect orchestration

### Provenance (28 files)
- Source-link tracking, pending-review promotion, event metadata

### Privacy (34+ files)
- Multi-gate privacy system, audit logging, PII sanitization

### Utility (30+ files)
- Text processing, math, time, geo utilities
- **Key files:** `MerchantKeyGenerator.kt`, `AmountUtils.kt`

### Models & Data Structures (40+ files)
- Request/response models, value objects
- **Key files:** `AiModels.kt`, `DashboardPrimitives.kt`

### ViewModels (41 files)
- UI state management (40 @HiltViewModel + 1 inline @HiltViewModel)
- **Key files:** `DashboardViewModel.kt`, `ExpenseListViewModel.kt`

---

## 📊 Statistics

| Metric | Count |
|--------|-------|
| **Total Source Files** | 1050 |
| Domain files | 520 |
| Data files | 305 |
| UI files | 167 |
| DI files | 32 |
| Hilt @Module files | 32 |
| **Database Entities** | 69 |
| **DAOs** | 68 |
| **Repositories** | 63 (47 data + 16 domain interfaces) |
| **Use Cases** | 31 |
| **ViewModels** | 41 |
| **Workers** | 9 (7 runtime + 2 backfill) |
| **Engines** | 28 named `*Engine` files |
| **AI Services** | 32+ |
| **Parsers** | 23 (across all layers) |
| **Geocoders** | 5 |
| **Email Receipt Parsers** | 4 |
| **Test Files** | 600+ (unit) + 27 (instrumented) |

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

**Total Tests:** 600+ unit + 27 instrumented (coverage expanding)

### High-Coverage Areas
- Consistency tests (13 files)
- AI provider tests (23 files)
- Repository tests (41 files)
- Analytics engine tests (21 files)
- Parser tests (25 files)
- Engine tests (42 files across all engines)
- Privacy tests (22 files)
- Budget tests (34 files)

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
1. Review all 69 entities in `COMPLETE-BACKEND-MAP.md`
2. Check DAOs and repositories that use them
3. Consider migrations (current version: v147)
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
| **COMPLETE-BACKEND-MAP.md** | 8000+ | ~250KB |
| **BACKEND-DEPENDENCIES.md** | 2000+ | ~65KB |
| **This Index** | 600+ | ~25KB |
| **Total** | 10,500+ | ~335KB |

---

## ✅ Completeness Checklist

- ✅ ALL 520 domain files listed
- ✅ ALL 305 data files listed
- ✅ ALL 32 Hilt @Module files + @EntryPoint listed
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

**Last Updated:** 2026-06-09  
**Version:** 2.2 - Reconciliation with Codebase  
**Status:** ✅ Production-Ready Documentation
