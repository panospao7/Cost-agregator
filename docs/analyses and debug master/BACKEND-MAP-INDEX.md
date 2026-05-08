# 📋 COMPLETE BACKEND & DATABASE MAP INDEX

**Generated:** 2026-05-07  
**Total Files Documented:** 804 source files (344 domain + 253 data + 30 DI + 177 other) + 475 test files  
**Scope:** ExpenseTracker domain, data, and DI packages

---

## 📚 Documentation Files

### Primary Maps (NEW)

1. **[COMPLETE-BACKEND-MAP.md](./COMPLETE-BACKEND-MAP.md)** ⭐ START HERE
   - Exhaustive list of ALL 804 backend files
   - Organized by package and subpackage
   - File type, purpose, dependencies for each
   - Data flow diagrams
   - Architecture patterns
   - **Size:** ~8000 lines

2. **[BACKEND-DEPENDENCIES.md](./BACKEND-DEPENDENCIES.md)** ⭐ DEPENDENCY CHAINS
   - Test coverage summary (475 tests)
   - 7 critical dependency chains with visualizations
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

#### Domain Package (344 files)
**Location:** `app/src/main/java/com/yourname/expensetracker/domain/`

- **AI Subsystem** (58 files)
  - Models, policies, services, use cases
  - 24 use cases covering AI capabilities

- **Analytics & Insights** (16 files)
  - Advanced analytics, anomaly detection
  - Spending insights, personality classification

- **Budget Management** (8 files)
  - Budget calculation, forecasting, monitoring
  - Shared budget management

- **Categorization** (7 files)
  - Core categorization engine
  - Contextual inference, semantic matching

- **Data Models** (24 files)
  - Dashboard primitives, recommendations
  - Navigation, dashboard-specific models

- **Use Cases** (13 files)
  - Budget, dashboard, expense, forecast, receipt, savings, warranty

- **Utilities** (26 files)
  - Amount, currency, date/time, merchant, statistics
  - String matching, geography, hashing
  - `domain/common/Hashing.kt` — SHA-256 hash prefix utility
  - `domain/notification/RawNotificationFingerprint.kt` — SHA-256 notification fingerprinting

- **Alerts** (2 files)
  - AnomalyAlertRepository, AnomalyAlertOrchestrator
  - System anomaly detection and alerting

- **Bank Integration** (2 files)
  - BankApiIntegration, BankApiConfig
  - External bank API connectivity

- **Business Expense** (2 files)
  - BusinessExpenseReportGenerator, BusinessExpenseRepository
  - Business-specific expense reporting

- **Carbon Footprint** (1 file)
  - CarbonFootprintCalculator
  - Environmental impact tracking

- **Cash Flow** (1 file)
  - CashFlowCalculator
  - Cash flow analysis and projections

- **Core Types** (~10 files)
  - `domain/core/time/` — PeriodRange, PeriodKind (typed time primitives)
  - `domain/core/money/` — CurrencyCode, MoneyAmount, MoneyAggregate, etc. (type-safe money)

- **Diagnostics** (1 file)
  - DatabaseIntegrityScanner
  - Database health and integrity checks

- **Data Transfer Objects (DTO)** (4 files)
  - AiArtifactRecord, CategoryRef, ReceiptItemCategorizationSnapshot, ReviewPriorityInput

- **Privacy** (~14 files)
  - PrivacyGate, PrivacyCapability, CompositePrivacyGate, 4 sub-gates
  - PrivacyAuditLogger, RedactionSanitizer, PrivacySettings, PrivacySettingsRepository, etc.

- **Reminder** (1 file)
  - BillReminderManager
  - Bill payment reminders

- **Transaction** (~10 files)
  - ExpenseSource, LifecycleEventType, DeduplicationMode, CreateExpenseRequest, CreateExpenseResult, ExpenseUpdates, SideEffectMode
  - `lifecycle/` — TransactionLifecycleCoordinator, TransactionSideEffectDispatcher

- **Workers** (2 files)
  - WorkerSpec, WorkerSpecScheduler
  - Background work specification and scheduling

- **Recurring** (~7 files)
  - RecurringOccurrenceExpander, OccurrenceConflictResolver, RecurringPlanProjectionService
  - `lifecycle/` — RecurringLifecycleCoordinator, RecurringOccurrenceMaterializer (~2 files)

- **Receipt Lifecycle** (~7 files)
  - `domain/receipt/lifecycle/` — ReceiptLifecycleCoordinator, ReceiptLinkService, ReceiptAssetStore, ReceiptInputValidator, ReceiptDuplicateDetector, ReceiptSideEffectDispatcher, BankStatementLifecycleProcessor

- **Other Subsystems**
  - Forecasting, location, parsing, receipt
  - Health, savings, subscriptions, tax
  - Notification fingerprinting — `domain/notification/RawNotificationFingerprint`
  - Shared hashing — `domain/common/Hashing.kt`

#### Data Package (253 files)
**Location:** `app/src/main/java/com/yourname/expensetracker/data/`

- **Database** (89 files)
   - 1 main database (AppDatabase.kt, v120)
   - 58 DAOs (data access objects)
   - 62 Entities (Room-managed tables, 60 registered in AppDatabase)
   - 6 composite models

- **Repositories** (65 files)
  - 52 data-layer implementations + 13 domain-layer interfaces
  - Expense, budget, analytics, currency
  - Merchant, location, notification
  - Savings, subscription, warranty
  - AutomatedSavingsRuleStateRepository, SavingsContributionHistoryRepository, SpendingChallengeRepository
  - DeterministicExpenseExportPager, GroupsRepository (interface), AnomalyAlertRepositoryImpl, SharedExpenseDataPortAdapter

- **AI Providers** (38+ files)
  - Cloud, OnDevice, Hybrid, NoOp implementations
  - 8 capability types × 4 implementations
  - SmartReceiptAssistService, StrictAiJsonParsing, DashboardBriefingPromptFormatter, DashboardBriefingResponseParser
  - Several NoOp* services
  - OnDevice notification parser, review priority scorer, semantic duplicate detector
  - All Cloud*Service implementations (8+ files)
  - Several Hybrid*Service implementations

- **AI Provider Internals** (4 files)
  - `data/ai/provider/internal/` — CloudCorrelation, CloudJsonParser, CloudPiiSanitizer, CloudRetryPolicy

- **Email Parsers** (4 files)
  - `data/email/provider/` — AmazonReceiptParser, AppleReceiptParser, EmailReceiptParser, UberReceiptParser

- **Location Services** (9 files)
  - `data/location/` — CompositeGeocodingService, NominatimGeocodingService, GeoapifyGeocodingService, GooglePlacesGeocodingService, PhotonGeocodingService, OverpassNearbyService, AndroidForegroundLocationProvider, LocationBackfillWorker, MerchantKeyBackfillWorker

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

#### DI Package (28 Hilt @Module files)
**Location:** `app/src/main/java/com/yourname/expensetracker/di/`

- 28 Hilt @Module files (27 in `di/` + `EmptyStatePresentationModule` in `ui/`)
- 1 `@EntryPoint` (`AppStartupDelegate`)
- Database, DAO, Repository bindings
- AI, services, location provider modules
- Network, time, currency, parsing modules
- Email ingestion, export, security modules

#### App Services Package (16 files)
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

---

## 🎯 By Architecture Layer

### Database Layer
- **Core:** `AppDatabase.kt` (Room database, v120)
- **Access:** 58 DAOs for direct table access
- **Entities:** 62 Room-managed entities (60 registered in AppDatabase)
- **Models:** 6 composite query result models
- **Coordinator:** `GroupTransactionCoordinator.kt`

### Repository Layer
- **65 repositories** providing business logic (52 data + 13 domain interfaces)
- Handle data transformation and aggregation
- Implement domain interfaces
- Manage database transactions

### Domain/Business Logic Layer
- **344 files** implementing business rules
- Engines, services, use cases, value objects
- No database dependencies
- Clean separation from infrastructure

### DI/Infrastructure Layer
- **28 Hilt @Module files + 1 @EntryPoint** managing dependencies
- Database, network, geocoding setup
- AI capability routing
- Service configuration

---

## 🔍 Files by Type

### Database-Related (161 files)
- DAOs (58), Entities (62), Models (6), Converters (1), Coordinator (1), Database (1)
- **Key files:** `ExpenseDao.kt`, `Expense.kt`, `AppDatabase.kt`

### Repository-Related (65 files)
- Data-layer repositories (52), Domain interfaces (13)
- **Key files:** `ExpenseRepository.kt`, `BudgetRepository.kt`, `CategoryRepository.kt`

### AI-Related (78+ files)
- Domain services (32), Data providers (38+), Workers (7)
- **Key files:** `AiCapabilityRouter.kt`, `CloudCategorizationAssistService.kt`

### Engine/Business Logic (50+ files)
- Calculation, analysis, decision engines
- **Key files:** `CategorizationEngine.kt`, `BudgetCalculator.kt`, `InsightsEngine.kt`

### Utility (30+ files)
- Text processing, math, time, geo utilities
- **Key files:** `MerchantKeyGenerator.kt`, `AmountUtils.kt`

### Models & Data Structures (30+ files)
- Request/response models, value objects
- **Key files:** `AiModels.kt`, `DashboardPrimitives.kt`

### ViewModels (39 files)
- UI state management
- **Key files:** `DashboardViewModel.kt`, `ExpenseListViewModel.kt`

---

## 📊 Statistics

| Metric | Count |
|--------|-------|
| **Total Source Files** | 804 |
| Domain files | 344 |
| Data files | 253 |
| DI files | 30 |
| Hilt @Module files | 28 |
| **Database Entities** | 62 |
| **DAOs** | 58 |
| **Repositories** | 65 (52 data + 13 domain interfaces) |
| **Use Cases** | 41 |
| **ViewModels** | 39 |
| **Workers** | 7 |
| **Engines** | 50+ |
| **AI Services** | 32 |
| **Parsers** | 8 |
| **Geocoders** | 5 |
| **Email Receipt Parsers** | 4 |
| **Test Files** | 475 |

---

## 🔗 Key Dependency Chains

### 1. Expense Ingestion
```
Notification → Parser → Expense Entity → Database
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
| **PII Sanitization** | Privacy protection | `CloudPiiSanitizer.kt` |
| **Log Sanitizer** | Safe logging | `LogSanitizer.kt` |

---

## 🧪 Test Coverage

**Total Tests:** 475 (449 test + 26 androidTest)

### High-Coverage Areas
- Consistency tests (15+ files)
- AI provider tests (20+ files)
- Repository tests (30+ files)
- Analytics engine tests (20+ files)
- Parser tests (10+ files)

### Key Test Files
- `ExpenseDao.kt` - Database DAO testing
- `CloudCategorizationAssistService.kt` - AI service testing
- `CrossParserConsistencyTest.kt` - Parser validation
- `FinancialArithmeticPrecisionTest.kt` - Money math precision

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
4. Check `NotificationProcessingPipeline.kt`

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
1. Review all 62 entities in `COMPLETE-BACKEND-MAP.md`
2. Check DAOs and repositories that use them
3. Consider migrations
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
| **This Index** | 500+ | ~20KB |
| **Total** | 10,500+ | ~335KB |

---

## ✅ Completeness Checklist

- ✅ ALL 344 domain files listed
- ✅ ALL 253 data files listed
- ✅ ALL 28 Hilt @Module files + @EntryPoint listed
- ✅ File-by-file breakdown with:
  - ✅ File path
  - ✅ Class name
  - ✅ Purpose (1 sentence)
  - ✅ Type (Entity, DAO, Repository, UseCase, etc.)
  - ✅ Dependencies
  - ✅ Test coverage indicator
- ✅ 7 major dependency chains documented
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

**Last Updated:** 2026-05-07  
**Version:** 2.0 - Complete Exhaustive Map  
**Status:** ✅ Production-Ready Documentation
