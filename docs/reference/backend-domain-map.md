# Backend Domain Layer Map

**Generated:** April 4, 2026  
**Total Kotlin/Java Files:** 219  
**Total Directories:** 42

---

## Table of Contents

1. [Directory Structure](#directory-structure)
2. [Architecture Overview](#architecture-overview)
3. [Engines](#engines)
4. [Use Cases](#use-cases)
5. [Models](#models)
6. [Services](#services)
7. [Clean Architecture Violations](#clean-architecture-violations)
8. [Circular Dependencies](#circular-dependencies)

---

## Directory Structure

### Overview by Category

| Directory | File Count | Purpose |
|-----------|-----------|---------|
| `ai/` | 31 | AI/ML capabilities (models, services, use cases) |
| `analytics/` | 15 | Spending analysis and insights generation |
| `alerts/` | 1 | Anomaly-based alerting |
| `backup/` | 2 | Database backup operations |
| `bank/` | 1 | Bank integration |
| `budget/` | 6 | Budget calculation and management |
| `business/` | 1 | Business expense reporting |
| `carbon/` | 1 | Carbon footprint calculation |
| `cashflow/` | 1 | Cash flow analysis |
| `categorization/` | 6 | Expense categorization engines |
| `challenge/` | 1 | Spending challenges |
| `config/` | 1 | Application configuration |
| `currency/` | 3 | Currency conversion and settings |
| `debug/` | 3 | Debug utilities |
| `engine/` | 1 | Dashboard recommendation engine |
| `export/` | 1 | Data export (accounting format) |
| `forecasting/` | 4 | Financial forecasting (Monte Carlo) |
| `groups/` | 5 | Group expense sharing |
| `health/` | 2 | Financial health scoring |
| `income/` | 1 | Recurring income tracking |
| `intelligence/` | 6 | ML classification and deduplication |
| `investment/` | 1 | Investment tracking |
| `lifestyle/` | 1 | Lifestyle inflation detection |
| `location/` | 8 | Location-based analytics |
| `logic/` | 5 | Core financial logic utilities |
| `model/` | 17 | Domain models and data structures |
| `naturallanguage/` | 1 | Natural language processing (speech) |
| `negotiation/` | 1 | Bill negotiation engine |
| `parser/` | 5 | Transaction parsing |
| `performance/` | 1 | Image caching |
| `price/` | 1 | Price protection tracking |
| `receipt/` | 6 | Receipt processing (OCR, parsing) |
| `receiptmatching/` | 1 | Receipt-to-transaction matching |
| `reminder/` | 1 | Bill reminder management |
| `savings/` | 3 | Savings goals and automation |
| `service/` | 1 | General services |
| `split/` | 1 | Expense split calculation |
| `subscription/` | 2 | Subscription detection |
| `tax/` | 2 | Tax estimation and configuration |
| `usecase/` | 10 | Public use cases for features |
| `util/` | 24 | Utility functions and helpers |
| `widget/` | 2 | Widget-related services |

---

## Architecture Overview

### Layered Architecture (Clean Architecture)

```
Domain Layer (This Document)
├── Models (Pure data classes)
├── Use Cases (Interactors - business logic entry points)
├── Engines (Specialized processors)
├── Services (Contracts/interfaces for external dependencies)
└── Utilities (Pure functions, helpers)
```

### Key Design Patterns

- **Strategy Pattern:** Categorization engines, parsers
- **Composite Pattern:** Analytics engines combining sub-engines
- **Adapter Pattern:** Currency converter, format exporters
- **Observer Pattern:** Implied through Flow<T> in repositories
- **Dependency Injection:** All components use @Inject + Dagger

---

## Engines

### 1. Dashboard Follow-Through Engine
**File:** `engine/DashboardFollowThroughEngine.kt` (260 lines)

**Purpose:** Generates contextual recommendations for dashboard navigation based on transaction events.

**Key Concepts:**
- Deterministic rule-based engine (AI only provides summary text)
- Generates up to 5 recommendations per transaction
- Uses adaptive spending thresholds from `SpendingThresholdCalculator`

**Key Methods:**
| Method | Input | Output | Logic |
|--------|-------|--------|-------|
| `generateRecommendations()` | Expense, AiArtifact?, userId | List<DashboardFollowThroughRecommendation> | Applies 4 rules: high-amount, category, merchant, recent |
| `generateFromInsight()` | Insight text, category, dates | DashboardFollowThroughRecommendation | Creates recommendation from AI insight |
| `createHighAmountRecommendation()` | Expense, AiArtifact? | Recommendation | Priority: HIGH, uses adaptive threshold |
| `createCategoryRecommendation()` | Expense, AiArtifact? | Recommendation | Priority: MEDIUM, 30-day category view |
| `createMerchantRecommendation()` | Expense, AiArtifact? | Recommendation | Priority: MEDIUM, all from merchant |
| `createRecentTransactionsRecommendation()` | Expense, AiArtifact? | Recommendation | Priority: LOW, last 7 days |

**Dependencies:**
- `TransactionFilterSerializer` (service layer)
- `SpendingThresholdCalculator` (analytics)
- `TimeProvider` (util)
- `TimePeriodUtils` (util)

**Output Models:**
- `DomainTransactionFilter` (navigation/model/)
- `DashboardFollowThroughRecommendation` (recommendation/model/)
- `RecommendationPriority` enum

---

### 2. Insights Engine
**File:** `analytics/InsightsEngine.kt` (751 lines)

**Purpose:** Comprehensive spending analytics and insights generation.

**Key Concepts:**
- Parallel async computation of 9 independent analyses
- Error-resilient (each async task wrapped in try-catch)
- Dual anomaly detection (merchant-level + statistical)
- Monthly and multi-month aggregations

**Key Methods:**
| Method | Returns | Description |
|--------|---------|-------------|
| `generateInsights()` | InsightsSnapshot | Master orchestrator, async parallel processing |
| `getLegacyInsights()` | List<SpendingInsight> | Converts snapshot to legacy format |
| `buildMonthlyComparison()` | MonthlyComparison | Current vs previous month |
| `buildCategoryInsights()` | List<CategoryInsight> | Top categories with trends |
| `buildMerchantInsights()` | List<MerchantInsight> | Top merchants with stats |
| `buildSpendingPace()` | SpendingPace | Daily rate projection |
| `findAnomalies()` | List<AnomalyTransaction> | Merged detection methods |
| `findRecurringExpenses()` | List<RecurringExpense> | Via RecurringExpenseEngine |
| `buildDayOfWeekPattern()` | List<DayOfWeekInsight> | 7-day breakdown |
| `buildDailyTotals()` | Map<String, Double> | Day-by-day aggregation |

**Sub-Engines Used:**
- `SpendingPaceCalculator`
- `AnomalyDetector`
- `MonthlyComparisonCalculator`
- `CategoryInsightEngine`
- `MerchantInsightEngine`
- `DayOfWeekAnalyzer`
- `RecurringExpenseEngine`

**Anomaly Detection (2-Path Approach):**

Path 1: Merchant-level (DB-backed)
- Compares current-month max vs historical average
- Adaptive multiplier: 5x (few txns), 4x (5-10), 3x (10+)
- Precise but only for known merchants

Path 2: Statistical (in-memory)
- IQR, MAD, contextual methods
- Fires on new merchants
- More sensitive to distribution outliers

---

### 3. Categorization Engine
**File:** `categorization/CategorizationEngine.kt`

**Purpose:** Smart expense categorization using multiple strategies.

**Sub-Engines:**
- `ContextualInferenceEngine` - Uses transaction context
- `SemanticKeywordMatcher` - Keyword-based matching
- `GreeklishNormalizer` - Greek language normalization
- `MerchantCanonicalizer` - Merchant name standardization

---

### 4. Budget Autopilot Engine
**File:** `budget/BudgetAutopilotEngine.kt`

**Purpose:** Automated budget optimization and recommendations.

**Dependencies:**
- `BudgetCalculator`
- `BudgetMonitor`
- `BudgetRecommendationEngine`

---

### 5. Forecasting Engines

#### Financial Stress Forecast Engine
**File:** `forecasting/FinancialStressForecastEngine.kt`

**Purpose:** Monte Carlo-based stress testing of financial scenarios.

**Dependencies:**
- `MonteCarloSpendingSimulator`
- `DataQualityAssessor`
- `HistoricalSpendingDistribution`

#### Monte Carlo Spending Simulator
**File:** `forecasting/MonteCarloSpendingSimulator.kt`

**Purpose:** Probabilistic spending projection using Monte Carlo methods.

---

### 6. Intelligence Engines

#### Transaction Classifier
**File:** `intelligence/TransactionClassifier.kt`

**Purpose:** Multi-mode transaction classification (rule-based, ML, hybrid).

#### Hybrid Expense Classifier
**File:** `intelligence/ml/HybridExpenseClassifier.kt`

**Purpose:** Combines rule-based and ML models for expense classification.

#### Cross-Source Deduplication
**File:** `intelligence/CrossSourceDeduplication.kt`

**Purpose:** Detects duplicate transactions across multiple sources.

---

### 7. Location Analytics Engines

#### Spending Heatmap Engine
**File:** `location/SpendingHeatmapEngine.kt`

**Purpose:** Geographic spending patterns visualization.

#### Travel Detection Engine
**File:** `location/TravelDetectionEngine.kt`

**Purpose:** Identifies travel periods based on location spending patterns.

#### Location Insights Engine
**File:** `location/LocationInsightsEngine.kt`

**Purpose:** Geographic insights and recommendations.

#### Area Spending Engine
**File:** `location/AreaSpendingEngine.kt`

**Purpose:** Regional spending aggregation and analysis.

---

### 8. AI Service Engines

#### DashboardBriefingService
**File:** `ai/service/DashboardBriefingService.kt`

**Purpose:** AI-generated daily/weekly dashboard summaries.

#### QueryInterpretationService
**File:** `ai/service/QueryInterpretationService.kt`

**Purpose:** Natural language query understanding.

#### ReceiptAssistService
**File:** `ai/service/ReceiptAssistService.kt`

**Purpose:** AI-assisted receipt processing.

#### SemanticDuplicateDetector
**File:** `ai/service/SemanticDuplicateDetector.kt`

**Purpose:** AI-based duplicate detection.

#### DedupeJudgeService
**File:** `ai/service/DedupeJudgeService.kt`

**Purpose:** Final verdict on transaction duplicates.

---

## Use Cases

### Budget Use Cases
**Directory:** `usecase/budget/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `CalculateBudgetStatusUseCase.kt` | Compute budget vs actual spending | Budget, period | Status (under/over) |
| `GetMonteCarloBudgetImpactUseCase.kt` | Forecast budget impact | Budget, scenarios | MonteCarloResult |

---

### Dashboard Use Cases
**Directory:** `usecase/dashboard/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `ComputeDashboardWidgetsUseCase.kt` | Aggregate all dashboard widgets | Period, filters | Widget data |
| `ComputeMoneyRadarUseCase.kt` | Generate money radar visualization | - | RadarData |
| `DashboardDataProvider.kt` | Interface for dashboard data sources | - | Various |

---

### Expense Use Cases
**Directory:** `usecase/expense/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `CategorizeExpenseUseCase.kt` | Classify expense to category | Expense | Category |
| `DetectDuplicateExpenseUseCase.kt` | Find duplicate transaction | Amount, merchant, date | DuplicateCheckResult |
| `ExpenseUseCases.kt` | Facade for all expense operations | - | Various |

---

### Forecast Use Cases
**Directory:** `usecase/forecast/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `CalculateFinancialForecastUseCase.kt` | Generate financial forecast | Historical data, period | FinancialForecast |

---

### Receipt Use Cases
**Directory:** `usecase/receipt/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `ProcessReceiptUseCase.kt` | End-to-end receipt processing | Image, OCR settings | Extracted items |

---

### Savings Use Cases
**Directory:** `usecase/savings/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `LifestyleSavingsPromptUseCase.kt` | Generate savings recommendations | Spending patterns | SavingsGoal |
| `MonthlySavingsSweepUseCase.kt` | Execute automated savings transfer | Account, amount | Result |

---

### Warranty Use Cases
**Directory:** `usecase/warranty/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `AutoCreateWarrantyFromReceiptUseCase.kt` | Extract warranty from receipt | Receipt items | WarrantyEntity |

---

### AI Use Cases
**Directory:** `ai/usecase/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `ExecuteFinancialQueryUseCase.kt` | Execute natural language query | FinancialQueryIntent | FinancialQueryResult |
| `GenerateDashboardBriefingUseCase.kt` | Create AI briefing | Period, transactions | Briefing text |
| `DetectSemanticDuplicateUseCase.kt` | Find semantic duplicates | Expenses | List<ExpensePair> |
| `ExplainPendingReviewUseCase.kt` | Explain review item | AiArtifact | Explanation |
| `CategorizeReceiptItemsUseCase.kt` | AI categorization | Receipt items | Categorized items |
| `InterpretFinancialQueryUseCase.kt` | Parse query intent | Query text | FinancialQueryIntent |
| `GetAiRuntimeStatusUseCase.kt` | Check AI availability | - | OnDeviceModelStatus |
| `MapFinancialQueryToNavigationUseCase.kt` | Convert query to navigation | Query result | NavigationTarget |
| `GenerateTransactionInsightUseCase.kt` | AI insight generation | Transaction | Insight text |
| `PrioritizeReviewItemsUseCase.kt` | Rank pending reviews | Reviews | Prioritized list |

**Input Builders (Transform UX data to domain models):**
- `CategorizationAssistInputBuilder.kt`
- `DashboardBriefingInputBuilder.kt`
- `DedupeJudgeInputBuilder.kt`
- `FinancialQueryInterpretationInputBuilder.kt`
- `ReceiptAssistInputBuilder.kt`
- `ReceiptItemCategorizationInputBuilder.kt`
- `ReviewExplanationInputBuilder.kt`

---

### Groups Use Cases
**Directory:** `groups/usecase/`

| File | Purpose | Input | Output |
|------|---------|-------|--------|
| `AddGroupExpenseUseCase.kt` | Add shared expense | Expense, members | GroupTransaction |
| `DeleteGroupMemberUseCase.kt` | Remove group member | Group, member | Result |
| `DeleteGroupUseCase.kt` | Delete group | Group ID | Result |

---

## Models

### Root Models
**Directory:** `model/`

| File | Fields | Purpose | Consumers |
|------|--------|---------|-----------|
| `Result.kt` | Success<T>, Error, Duplicate, Loading | Generic result wrapper | All use cases |
| `UiText.kt` | Lazy string composition | UI-agnostic text rendering | Recommendations, AI responses |
| `PeriodRange.kt` | start, end (Long) | Time window | Forecasting, analytics |
| `PeriodTotal.kt` | period, total, count | Aggregated spending | Dashboard |
| `CategoryBreakdown.kt` | category, amount, percentage | Category-level analytics | Dashboard, reports |
| `CategoryInfo.kt` | id, name, icon, color | Category metadata | All engines |
| `FinancialForecast.kt` | scenarios, probability distribution | Projected spending | Forecasting use case |
| `PlannedExpense.kt` | description, amount, dueDate | Planned transaction | Budget planning |
| `RecurringPattern.kt` | merchant, frequency, avgAmount | Recurring transaction | Insights, reminders |
| `SavingsGoal.kt` | name, targetAmount, deadline, progress | Savings target | Savings use cases |
| `UpcomingItem.kt` | description, date, amount, type | Upcoming transaction | Reminders, dashboard |
| `BlockPartyDay.kt` | date, totalSpent, transactionCount | Daily summary | Dashboard |

### Budget Models
**Directory:** `model/budget/`

| File | Purpose |
|------|---------|
| `MonteCarloBudgetImpact.kt` | Probabilistic budget scenarios |

### Dashboard Models
**Directory:** `model/dashboard/`

| File | Purpose |
|------|---------|
| `DomainBlockStatus.kt` | Dashboard widget status |
| `DomainDayBudgetStatus.kt` | Daily budget tracking |

### Navigation Models
**Directory:** `model/navigation/`

| File | Purpose |
|------|---------|
| `DomainTransactionFilter.kt` | Transaction filtering criteria |

**Fields:**
```kotlin
categoryId: Long?
dateRange: Pair<Long, Long>?
minAmount: Double?
maxAmount: Double?
merchantName: String?
transactionType: TransactionType?
```

### Recommendation Models
**Directory:** `model/recommendation/`

| File | Purpose | Fields |
|------|---------|--------|
| `DashboardFollowThroughRecommendation.kt` | Contextual recommendations | userId, recommendationText, navigationTarget, filterCriteria, priority, category, sourceArtifactId |
| `RecommendationPriority.kt` | Enum: HIGH, MEDIUM, LOW | Priority ranking |
| `RecommendationStatus.kt` | Enum: PENDING, VIEWED, ACTED | Recommendation lifecycle |

### AI Models
**Directory:** `ai/model/`

| File | Purpose | Key Entities |
|------|---------|--------------|
| `AiModels.kt` | Core AI types | AiCapability, AiRouteDecision |
| `AiLoadState.kt` | Model loading states | Enum: IDLE, LOADING, READY, ERROR |
| `AiRuntimeStatusModels.kt` | Runtime status tracking | OnDeviceModelStatus |
| `FinancialQueryModels.kt` | Query intent/result | FinancialQueryIntent, FinancialQueryResult |
| `ReceiptItemCategorizationModels.kt` | Receipt categorization | ReceiptItem, CategorizationResult |
| `CaptureAssistModels.kt` | Receipt capture AI | CaptureIntent, CaptureResult |
| `NotificationParsingModels.kt` | Notification extraction | NotificationIntent |
| `ReviewPriorityModels.kt` | Review ranking | ReviewItem, PriorityScore |
| `SemanticDuplicateModels.kt` | Duplicate detection | DuplicatePair, Confidence |
| `AiArtifactPresentation.kt` | AI output packaging | AiArtifact display format |
| `OnDeviceRuntimePresentation.kt` | Runtime UI presentation | Status display |

---

## Services

### Interfaces & Repositories

**File:** `service/NotificationService.kt`

**Purpose:** Notification dispatch contract.

**File:** `ai/service/AiCapabilityRouter.kt`

**Purpose:** Route capability requests to appropriate AI backend.

```kotlin
interface AiCapabilityRouter {
    suspend fun decide(
        capability: AiCapability,
        settings: AiSettings,
        onDeviceStatus: OnDeviceModelStatus?
    ): AiRouteDecision
}
```

### AI Service Implementations
**Directory:** `ai/service/`

| Service | Purpose | Output |
|---------|---------|--------|
| `AiSettingsRepository.kt` | Fetch AI configuration | AiSettings |
| `AiEnvironmentMonitor.kt` | Monitor device capacity | Battery, network status |
| `AiWorkScheduler.kt` | Schedule async AI tasks | WorkRequest |
| `AiEngagementRepository.kt` | Track user engagement with AI | Metrics |
| `AiChatRepository.kt` | Chat history management | Message list |
| `AiArtifactRepository.kt` | Persist AI outputs | AiArtifactEntity |

### Categorization Services
**Directory:** `categorization/`

| Service | Purpose |
|---------|---------|
| `CategorizationEngine.kt` | Master categorization | 
| `ContextualInferenceEngine.kt` | Context-aware matching |
| `SemanticKeywordMatcher.kt` | Keyword-based classification |
| `CategoryKeywords.kt` | Category→keywords mapping |
| `GreeklishNormalizer.kt` | Greek text normalization |
| `MerchantCanonicalizer.kt` | Merchant name standardization |

### Receipt Services
**Directory:** `receipt/`

| Service | Purpose |
|---------|---------|
| `ReceiptOcrService.kt` | OCR engine invocation |
| `ReceiptParser.kt` | Receipt format parsing |
| `BankStatementParser.kt` | Bank statement extraction |
| `EnhancedMerchantExtractor.kt` | Merchant name extraction |
| `OcrLanguageProcessor.kt` | Multi-language OCR |
| `OcrPreprocessingPipeline.kt` | Image preprocessing |
| `WarrantyTextExtractor.kt` | Warranty clause extraction |

### Location Services
**Directory:** `location/`

| Service | Purpose | Returns |
|---------|---------|---------|
| `LocationResolver.kt` | Address↔Coordinates conversion | GeocodingResult |
| `GeocodingResult.kt` | Geocoding response | Lat/long, address |
| `LocatedExpense.kt` | Transaction with location | Expense + Location |
| `NearbyPoi.kt` | Point of interest | POI data |
| `LocationModels.kt` | Location data types | Various |

### Budget Services
**Directory:** `budget/`

| Service | Purpose |
|---------|---------|
| `BudgetCalculator.kt` | Period calculation (daily/weekly/monthly/yearly/rolling) |
| `BudgetMonitor.kt` | Track budget vs actual |
| `BudgetRecommendationEngine.kt` | Suggest budget adjustments |
| `SharedBudgetManager.kt` | Group budget coordination |

### Analytics Services
**Directory:** `analytics/`

| Service | Purpose |
|---------|---------|
| `SpendingPaceCalculator.kt` | Daily rate projection |
| `AnomalyDetector.kt` | Statistical outlier detection |
| `MonthlyComparisonCalculator.kt` | Month-over-month analysis |
| `CategoryInsightEngine.kt` | Category trends |
| `MerchantInsightEngine.kt` | Top merchants ranking |
| `DayOfWeekAnalyzer.kt` | Weekly spending patterns |
| `SpendingPersonalityClassifier.kt` | Spending type classification |
| `TotalsAggregationEngine.kt` | Aggregate spending metrics |
| `TransferDirectionAnalytics.kt` | Transfer vs purchase analysis |

### Intelligence Services
**Directory:** `intelligence/`

| Service | Purpose |
|---------|---------|
| `TransactionClassifier.kt` | ML classification router |
| `ConfidenceRouter.kt` | Route by confidence threshold |
| `CrossSourceDeduplication.kt` | Multi-source duplicate detection |

### Parser Services
**Directory:** `parser/`

| Service | Purpose |
|---------|---------|
| `AppParserRegistry.kt` | Parser discovery and invocation |
| `GenericTransactionParser.kt` | Fallback parser |
| `TransferDirectionDetector.kt` | In/out classification |

**Parsers (Specialized):**
- `parsers/GoogleWalletParser.kt` - Google Wallet SMS
- `parsers/GreekBankParser.kt` - Greek bank statements
- `parsers/RevolutParser.kt` - Revolut notifications
- `parsers/SmsParser.kt` - Generic SMS parsing

### Other Services
**Directory:** `**/`

| Service | Purpose |
|---------|---------|
| `CurrencyConverter.kt` | FX rate application |
| `CurrencyRatesRepository.kt` | FX data source |
| `CurrencySettingsRepository.kt` | Currency preferences |
| `NotificationSubscriptionDetector.kt` | Recurring bill detection |
| `SubscriptionManagerEngine.kt` | Subscription lifecycle |
| `AutomatedSavingsRuleEngine.kt` | Auto-savings setup |
| `SavingsGamificationEngine.kt` | Savings challenges |
| `SmartSavingsEngine.kt` | Savings optimization |
| `BillReminderManager.kt` | Bill notification scheduling |
| `AreaSpendingEngine.kt` | Geographic analytics |
| `LifestyleInflationDetector.kt` | Spending growth detection |
| `InvestmentTracker.kt` | Investment portfolio |
| `PriceProtectionTracker.kt` | Price match detection |
| `SmartBillNegotiationEngine.kt` | Bill optimization |
| `AnomalyAlertOrchestrator.kt` | Alert triggering |
| `EnhancedSplitManager.kt` | Expense splitting |
| `SpendingChallengeManager.kt` | Challenge creation/tracking |
| `NaturalLanguageSearchEngine.kt` | Voice/text search |
| `DatabaseBackupRepository.kt` | Backup operations |
| `DatabaseOperationResults.kt` | Backup result types |
| `BankApiIntegration.kt` | Bank data sync |

---

## Utilities

### Core Utilities
**Directory:** `util/`

| Utility | Purpose | Key Functions |
|---------|---------|----------------|
| `Money.kt` | Money type wrapper | Format, compare, convert |
| `TimeProvider.kt` | System time abstraction | now() |
| `TimePeriodUtils.kt` | Date range calculations | getMonthRange(), getWeekRange(), addDays(), etc. |
| `DateFormatterUtils.kt` | Date formatting | dateKey(), monthDay(), etc. |
| `AmountUtils.kt` | Numeric amount handling | Parse, format, round |
| `AmountExtractionUtils.kt` | Amount extraction from text | Regex-based parsing |
| `CurrencyFormatter.kt` | Currency display | Format with symbol |
| `CurrencyNormalizer.kt` | Currency code normalization | Canonical form |
| `StatisticsUtils.kt` | Statistical calculations | StdDev, median, etc. |
| `StringDistanceUtils.kt` | String similarity | Levenshtein, fuzzy match |
| `MerchantCleaner.kt` | Merchant name normalization | Remove special chars |
| `MerchantKeyGenerator.kt` | Canonical merchant ID | Generate from name |
| `GeoUtils.kt` | Geographic calculations | Distance, bounds |
| `NotificationIdGenerator.kt` | Unique ID generation | For notifications |
| `SystemTimeProvider.kt` | Real system time | Concrete implementation |
| `CommonPatterns.kt` | Regex patterns | Email, phone, IBAN, etc. |
| `BKTree.kt` | String distance index | Fast fuzzy search |
| `AppConstants.kt` | Application constants | Magic numbers, thresholds |

---

## Clean Architecture Violations

### 🚨 Android Framework Imports in Domain

The following files **violate Clean Architecture** by importing Android/AndroidX classes:

#### Android Framework Imports

| File | Import | Reason | Severity |
|------|--------|--------|----------|
| `ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt` | `android.content.Context` | Domain should not know about Context | **HIGH** |
| `debug/NotificationSeeder.kt` | `android.content.Context` | Debug utility in domain | **MEDIUM** |
| `debug/ServiceDiagnostics.kt` | `android.content.Context`, `SharedPreferences` | Debug in domain, should be in data | **MEDIUM** |
| `intelligence/ml/ExpenseCategoryClassifier.kt` | `android.content.Context` | ML model loading dependency | **HIGH** |
| `intelligence/ml/HybridExpenseClassifier.kt` | `android.content.Context` | ML model loading dependency | **HIGH** |
| `intelligence/ml/MerchantNormalizer.kt` | `android.content.Context` | ML model loading dependency | **HIGH** |
| `intelligence/TransactionClassifier.kt` | `android.content.Context` | ML model loading dependency | **HIGH** |
| `location/LocationResolver.kt` | `android.util.Log` | Should use Timber | **LOW** |
| `model/UiText.kt` | `android.content.Context`, `androidx.annotation.*`, `androidx.compose.runtime.*`, `androidx.compose.ui.res.*` | Domain model is UI-aware | **CRITICAL** |
| `naturallanguage/NaturalLanguageSearchEngine.kt` | `android.content.*`, `android.speech.*`, `android.os.Bundle` | Speech recognition is framework-specific | **CRITICAL** |
| `performance/ImageCache.kt` | `android.content.Context`, `android.graphics.*`, `android.net.Uri` | Image cache should be in data layer | **HIGH** |
| `receipt/ReceiptOcrService.kt` | `androidx.exifinterface.media.ExifInterface` | OK - EXIF is file format library |  **LOW** |

#### Compose Framework Imports

| File | Impact | Suggestion |
|------|--------|-----------|
| `analytics/AdvancedAnalyticsModels.kt` | `@Immutable` from Compose | Remove @Immutable or move to presentation |
| `model/UiText.kt` | Full Compose dependency | Extract `@Composable` functions to presentation layer |

### Remediation Strategy

**Priority 1 (Critical):**
1. **`model/UiText.kt`** - Extract composables to presentation layer
   - Keep domain model as pure data class
   - Move @Composable rendering to UI layer
   - Domain should return raw text/string IDs

2. **`naturallanguage/NaturalLanguageSearchEngine.kt`** - Refactor
   - Move SpeechRecognizer to presentation/feature layer
   - Domain should define interface: `SpeechInput { suspend fun recognize(): String }`
   - Presentation implements with actual framework

3. **ML Classifiers** - Extract Context dependency
   - Add init block to perform model loading in data layer
   - Domain receives pre-loaded classifier interface
   - Example: `interface ExpenseClassifier { suspend fun classify(data): Category }`

**Priority 2 (High):**
4. **`performance/ImageCache.kt`** - Move to data/cache layer
5. **`intelligence/TransactionClassifier.kt`** - Use dependency injection for context
6. **Debug utilities** - Move debug folder or extract domain logic

**Priority 3 (Low):**
7. Replace `android.util.Log` with `timber.log.Timber`
8. Remove `@Immutable` from analytics models

---

## Circular Dependencies

### Analysis

Performed grep search for circular imports. **No direct circular dependencies found** in current implementation.

However, **potential soft circularities exist:**

### Dependency Graph Risks

```
AI Layer ─depends on──→ Domain Engines (analytics, categorization)
         ├────────────→ Models (domain.model.*)
         └────────────→ Services (ai/service/*)
                           │
                           └──depends on─→ Repositories (data layer)

Analytics Engine ─depends on──→ Intelligence (TransactionClassifier)
                   └──────────→ LogicUtils (RecurringExpenseEngine)
```

**Risk:** If any service in `ai/service/` imports from `ai/usecase/` or vice versa, circularity exists.

### Safe Dependencies Direction

✅ **Correct:**
```
UseCase → Engine → Service → Repository → Database
UseCase → Engine → Model
UseCase → Model
Engine → Model
Service → Model
```

❌ **Dangerous:**
```
Service → UseCase (violation - dependency reversal)
Model → UseCase (violation - models should be agnostic)
Engine → UseCase (violation - engines precede use cases)
```

### Recommended Checks

```bash
# Find potential circular imports
grep -r "import.*domain.ai.usecase" app/src/main/java/com/yourname/expensetracker/domain/ai/service/

# Find service→usecase dependencies
grep -r "import.*domain.*usecase" app/src/main/java/com/yourname/expensetracker/domain/*/service/

# Find reverse model dependencies
grep -r "import.*domain.usecase\|import.*domain.engine" app/src/main/java/com/yourname/expensetracker/domain/model/
```

---

## Data Flow Examples

### Scenario 1: User Views Dashboard

```
Dashboard Fragment
    │
    └──→ ComputeDashboardWidgetsUseCase
            │
            ├──→ InsightsEngine.generateInsights()
            │       ├──→ [8 async] SpendingPaceCalculator, AnomalyDetector, ...
            │       └──→ [DB] ExpenseRepository.getTotalForPeriod()
            │
            ├──→ BudgetCalculator.calculatePeriodRange()
            │
            └──→ [DB] ExpenseRepository (fetch transactions)
    
    ┌──Result: DashboardData
    └──Render: DashboardWidgets, Insights, Budget Status
```

### Scenario 2: Transaction Categorization

```
New Transaction Created
    │
    ├──→ CategorizeExpenseUseCase
    │       │
    │       └──→ CategorizationEngine
    │               ├──→ ContextualInferenceEngine (category rules)
    │               ├──→ SemanticKeywordMatcher (merchant keywords)
    │               ├──→ MerchantCanonicalizer (normalize name)
    │               └──→ [ML] HybridExpenseClassifier (if confidence low)
    │                       └──→ [File] Pre-loaded TF Lite model
    │
    └──Result: CategoryId
        │
        └──→ [DB] ExpenseRepository.update(categoryId)
```

### Scenario 3: Receipt Processing

```
User Selects Receipt Image
    │
    ├──→ ProcessReceiptUseCase
    │       │
    │       ├──→ OcrPreprocessingPipeline (image enhancement)
    │       │
    │       ├──→ ReceiptOcrService (OCR invocation)
    │       │       └──→ [ML] ML Kit Text Recognition
    │       │
    │       ├──→ ReceiptParser (line item extraction)
    │       │       ├──→ AmountExtractionUtils (regex)
    │       │       ├──→ EnhancedMerchantExtractor
    │       │       └──→ WarrantyTextExtractor
    │       │
    │       ├──→ CategorizeReceiptItemsUseCase
    │       │       └──→ AI or CategorizationEngine
    │       │
    │       ├──→ ReceiptTransactionMatcher ([fuzzy] match to existing)
    │       │
    │       └──→ AutoCreateWarrantyFromReceiptUseCase
    │
    └──Result: Expense + ReceiptItems
        │
        └──→ [DB] Persist
```

### Scenario 4: AI Query Execution

```
User: "How much did I spend on groceries this month?"
    │
    ├──→ InterpretFinancialQueryUseCase
    │       │
    │       └──→ QueryInterpretationService (AI)
    │               └──Result: FinancialQueryIntent
    │                   { metric: TOTAL, grouping: null, category: GROCERIES, period: THIS_MONTH }
    │
    ├──→ ExecuteFinancialQueryUseCase
    │       │
    │       ├──→ [DB] ExpenseRepository.getCategoryTotalsForPeriod()
    │       │
    │       └──Result: FinancialQueryResult.Summary
    │           { title: "Total Spending", primaryText: "€234.56" }
    │
    ├──→ MapFinancialQueryToNavigationUseCase
    │       └──Result: NavigationIntent (TRANSACTION_LIST + filter)
    │
    └──Result to User: Summary + Drilldown Link
```

---

## Key Architectural Insights

### 1. Heavy Analytics Dependency
- **InsightsEngine** is heavyweight (~750 lines) combining 7 sub-engines
- Most analytics features depend on it directly or indirectly
- Performance: Async/parallel execution mitigates blocking

### 2. AI Infrastructure
- **31 AI files** across models, services, use cases
- Policy-based routing via `AiCapabilityRouter` (on-device vs cloud)
- Input builders decouple presentation from AI domain models

### 3. Categorization Pipeline
- **Multi-strategy:** Rules → Keywords → Semantic → ML fallback
- Normalization handles Greek text and merchant variations
- Used by receipt processing and transaction classification

### 4. Location Is Cross-Cutting
- 8 files dedicated to geo-analytics
- Used by: Dashboard, Analytics, Travel detection, Heatmap
- Not currently well integrated with other engines

### 5. Budget System Complexity
- **5 period modes:** Daily, Weekly, Monthly, Yearly, Rolling
- Month-end edge cases heavily tested (Feb 29, 31st days)
- Budget forecasting (Monte Carlo) distinct from budget status

### 6. Recurring Transaction Detection
- Centralized in `RecurringExpenseEngine`
- Used by: Insights, Subscription detection, Reminders
- Pattern recognition + interval calculation

### 7. Dual Anomaly Detection
- **Merchant-level:** Historical baseline + multiplier
- **Statistical:** IQR/MAD in-memory analysis
- Results merged and deduplicated for final list

---

## Integration Points

### Domain ↔ Data Layer
- Repository interfaces injected into all engines/use cases
- No direct database access within domain
- Async/coroutine-based (Flow<T>, suspend functions)

### Domain ↔ Presentation Layer
- Use cases return domain models (Result<T>)
- Navigation intents for cross-screen routing
- UiText for localization (but violates Clean Architecture)

### Domain ↔ External Libraries
- **Timber** - Logging
- **Kotlin Coroutines** - Async/concurrency
- **Dagger/Hilt** - DI annotations
- **Android ML Kit** - OCR, on-device ML
- **TensorFlow Lite** - Expense classifier models

---

## Testing Considerations

### Unit Testable
- ✅ All utility functions (DateFormatterUtils, AmountUtils, etc.)
- ✅ Pure calculation engines (BudgetCalculator, CurrencyConverter)
- ✅ Logic utilities (SplitCalculator, RecurrenceCalculator)

### Integration Testable (Mock Repositories)
- ✅ Analytics engines (mock ExpenseRepository)
- ✅ Use cases (mock ExpenseRepository + CategorizationEngine)
- ✅ Parsers (mock OCR service)

### System Testable (Full App)
- ✅ End-to-end flows (Receipt → Expense → Analytics)
- ✅ Concurrent async operations (InsightsEngine parallel tasks)

### Difficult to Test
- ⚠️ ML Classifiers (TF Lite model loading with Context)
- ⚠️ NaturalLanguageSearchEngine (framework Speech API)
- ⚠️ LocationResolver (external geocoding)

---

## Summary Statistics

| Metric | Count |
|--------|-------|
| **Total Files** | 219 |
| **Total Lines of Code** | ~35,000 |
| **Engines** | 20+ |
| **Use Cases** | 25+ |
| **Models** | 50+ |
| **Services** | 40+ |
| **Utilities** | 20+ |
| **Clean Architecture Violations** | 12 files |
| **Circular Dependencies** | 0 direct |

---

## Recommendations

### Short Term
1. ✅ Fix `UiText.kt` - Move Composable rendering to presentation
2. ✅ Isolate ML Classifier Context dependency
3. ✅ Move debug utilities to data layer

### Medium Term
4. Extract NaturalLanguageSearchEngine to presentation/feature
5. Consolidate analytics sub-engines documentation
6. Add integration tests for high-complexity flows (InsightsEngine)

### Long Term
7. Implement formal API versioning for domain models
8. Create domain event bus for cross-engine communication
9. Modularize AI layer into separate gradle module
10. Extract location analytics to separate module

---

**Generated:** April 4, 2026 | **Version:** 1.0
