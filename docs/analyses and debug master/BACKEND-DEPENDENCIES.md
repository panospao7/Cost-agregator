# Backend Map - Test Coverage & Cross-References

**Generated:** 2026-04-06

---

## Test Coverage Summary

**Total Test Files:** 317

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

### Chain 1: Expense Ingestion → Storage

```
Notification/SMS Input
    ↓
Parser (GenericTransactionParser or specialized)
    ↓
ParsedTransaction
    ↓
Expense Entity
    ↓
ExpenseDao.insert()
    ↓
ExpenseRepository.saveExpense()
    ↓
SQLite (Expense table)
```

**Files:** `parser/*`, `data/database/entity/Expense.kt`, `data/database/dao/ExpenseDao.kt`, `data/repository/ExpenseRepository.kt`

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
ManualRecurringExpense / RecurringExpense
    ├─ Repository: ManualRecurringExpenseRepository / RecurringExpenseRepository
    ├─ DAO: ManualRecurringExpenseDao / RecurringExpenseDao
    └─ Used by:
        ├→ RecurringExpenseEngine
        ├→ BudgetCalculator
        └→ HistoricalSpendingDistribution
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
    ├─ Provides: AppDatabase
    ├─ Uses: DaoModule
    └─ Provides: GroupTransactionCoordinator

DaoModule
    └─ Provides: All 54 DAOs

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
    └─ Provides: Retrofit, OkHttp

DispatchersModule
    └─ Provides: IO, Default, Main dispatchers

CurrencyModule
    ├─ CurrencyConverter
    └─ Exchange rate services

ReceiptParsingModule
    └─ All receipt parsers

EmailIngestionModule
    └─ Email receipt parsers

TimeModule
    └─ TimeProvider implementations
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

**End of Test Coverage & Cross-References**

