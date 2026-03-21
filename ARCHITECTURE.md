# ExpenseTracker Architecture Guide

## How to Use This Document

### For Quick Understanding
→ Read **Architecture Overview** and **Layer Structure** sections

### For Adding Features
1. Read **Key Components** to find similar patterns
2. Check **Quick Reference** → "Add New Screen/Parser/Entity"

### For Bug Analysis (RECOMMENDED WORKFLOW)

**Step 1: Identify the Segment**
Use CODEBASE_SEGMENTS.md to find which segment contains the issue:
- Segment 1 → Financial Forecast
- Segment 2 → Budget
- Segment 3 → Notification Parsing
- Segment 4 → OCR/Receipt
- Segment 5 → Categorization
- Segment 6 → Recurring
- Segment 7 → Analytics
- Segment 8 → Core Expense
- Segment 9 → Dashboard
- Segment 10 → Notifications
- Segment 11 → Debug
- Segment 12 → DI
- Segment 13 → Utilities
- Segment 14 → Database

**Step 2: Find Related Files**
Check CODEBASE_SEGMENTS.md for files in that segment

**Step 3: Understand Data Flow**
Use **Data Flow** section in this document to trace the issue

**Step 4: Quick Reference**
Use **Check Bug Sources** table to find likely causes

---

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Layer Structure](#layer-structure)
3. [Data Flow](#data-flow)
4. [Key Components](#key-components)
5. [Dependency Injection](#dependency-injection)
6. [Database Schema](#database-schema)
7. [Quick Reference](#quick-reference)

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────┐
│                         UI LAYER                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Screens   │  │  ViewModels  │  │ Components   │              │
│  │  (Compose)  │  │   (State)    │  │  (Reusable) │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└────────────────────────────┬───────────────────────────────────────┘
                             │ calls
                             ▼
┌────────────────────────────────────────────────────────────────────┐
│                       DOMAIN LAYER                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Engines    │  │   Models     │  │   Services   │              │
│  │ (Business    │  │  (Data       │  │  (Interfaces│              │
│  │   Logic)     │  │   Classes)   │  │   & Abstr.) │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└────────────────────────────┬───────────────────────────────────────┘
                             │ uses
                             ▼
┌────────────────────────────────────────────────────────────────────┐
│                        DATA LAYER                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ Repositories │  │    DAOs      │  │  Services    │              │
│  │  (Data       │  │  (Database   │  │  (Android    │              │
│  │   Access)    │  │   Queries)    │  │   System)    │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
└────────────────────────────────────────────────────────────────────┘
```

---

## Layer Structure

### UI Layer (`ui/`)
```
ui/
├── MainActivity.kt              # App entry, navigation
├── MainViewModel.kt             # App-wide state
├── theme/                       # Compose theming
│   └── Theme.kt                 # Material 3 colors/typography
├── components/                  # Reusable composables
│   ├── BentoCard.kt            # Dashboard card layout
│   ├── FinancialWeatherCard.kt  # Forecast display
│   ├── BudgetBlockPartyCard.kt # Budget visualization
│   └── ...
├── screens/
│   ├── home/                   # Dashboard
│   ├── review/                 # Transaction review
│   ├── budget/                 # Budget management
│   ├── analytics/              # Analytics & insights
│   ├── transactions/           # Transaction list
│   ├── categories/             # Category management
│   ├── recurring/              # Recurring expenses
│   ├── receiptscan/            # OCR receipt scanning
│   ├── addexpense/             # Manual expense entry
│   ├── debug/                  # Debug & diagnostics
│   └── ...
└── util/
    ├── HapticFeedback.kt       # Haptic feedback utilities
    └── ClipboardAmountParser.kt # Clipboard parsing
```

### Domain Layer (`domain/`)
```
domain/
├── logic/                       # Core business engines
│   ├── SynthesisEngine.kt       # Financial forecast synthesis
│   ├── NarrativeGenerator.kt   # Weather narratives
│   └── RecurringExpenseEngine.kt # Recurring pattern detection
├── forecasting/                 # Monte Carlo Spending Simulator (NEW Mar 2026)
│   ├── MonteCarloSpendingSimulator.kt # Core simulation engine
│   ├── MonteCarloResult.kt      # Result data models
│   ├── HistoricalSpendingDistribution.kt # Weekly aggregation + log-normal fit
│   └── DataQualityAssessor.kt   # Confidence scoring
├── analytics/                   # Analytics engines
│   ├── InsightsEngine.kt        # Spending insights (coordinator)
│   ├── SpendingPaceCalculator.kt      # Spending pace (NEW)
│   ├── AnomalyDetector.kt             # Unusual transactions (NEW)
│   ├── MonthlyComparisonCalculator.kt # Month comparison (NEW)
│   ├── CategoryInsightEngine.kt       # Category analysis (NEW)
│   ├── MerchantInsightEngine.kt      # Merchant patterns (NEW)
│   ├── DayOfWeekAnalyzer.kt         # Day-of-week patterns (NEW)
│   ├── TransferDirectionAnalytics.kt # Transfer direction analytics (NEW)
│   ├── AdvancedAnalyticsEngine.kt # Advanced patterns
│   └── AnalyticsModels.kt        # Insight data classes
├── budget/                      # Budget management
│   ├── BudgetCalculator.kt      # Budget calculations
│   ├── BudgetMonitor.kt         # Budget monitoring & alerts
│   └── BudgetModels.kt
├── categorization/              # Merchant categorization
│   └── CategorizationEngine.kt  # Category assignment
├── intelligence/
│   ├── ConfidenceRouter.kt      # Confidence-based routing
│   ├── TransactionClassifier.kt # Transaction detection
│   └── ml/                     # Machine learning
│       ├── MerchantNormalizer.kt # Merchant name normalization
│       ├── HybridExpenseClassifier.kt
│       ├── ExpenseCategoryClassifier.kt
│       └── FeatureExtractor.kt
├── parser/                      # Notification parsing
│   ├── AppParserRegistry.kt     # Parser routing
│   ├── GenericTransactionParser.kt
│   ├── TransferDirectionDetector.kt  # Transfer direction detection (NEW)
│   └── parsers/
│       ├── GreekBankParser.kt   # NBG, Alpha, Eurobank
│       ├── RevolutParser.kt
│       ├── GoogleWalletParser.kt
│       └── SmsParser.kt
├── receipt/                     # Receipt OCR
│   ├── ReceiptOcrService.kt   # ML Kit OCR
│   ├── ReceiptParser.kt
│   └── BankStatementParser.kt
├── service/                     # Service interfaces
│   └── NotificationService.kt  # Notification interface
├── usecase/                    # Use Cases (Clean Architecture)
│   ├── receipt/
│   │   └── ProcessReceiptUseCase.kt
│   ├── expense/
│   │   ├── CategorizeExpenseUseCase.kt
│   │   └── DetectDuplicateExpenseUseCase.kt  # NEW
│   ├── budget/
│   │   └── CalculateBudgetStatusUseCase.kt
│   ├── dashboard/
│   │   └── DashboardDataProvider.kt
│   └── forecast/
│       └── CalculateFinancialForecastUseCase.kt  # NEW
├── model/                       # Domain models
│   ├── FinancialForecast.kt
│   ├── Budget.kt
│   ├── Expense.kt
│   └── ...
├── config/                     # Configuration
│   └── AppConfig.kt           # Centralized thresholds
├── location/                   # Location enrichment (NEW Mar 2026)
│   ├── LocationResolver.kt    # Coordinates geocoding
│   ├── LocationModels.kt      # Location domain models
│   ├── GeocodingResult.kt     # Geocoding result models
│   └── LocatedExpense.kt      # Expense with location
├── performance/                 # Performance utilities
│   └── ImageCache.kt          # Bitmap caching
├── debug/
│   ├── ServiceDiagnostics.kt
│   └── NotificationSeeder.kt
└── util/                       # Utilities
    ├── TimeProvider.kt         # Time abstraction (testable)
    ├── AmountUtils.kt          # Amount parsing
    ├── CurrencyFormatter.kt    # Currency formatting (NEW)
    ├── AmountExtractionUtils.kt # Regex patterns (NEW)
    ├── DateFormatterUtils.kt   # Date formatting
    ├── TimePeriodUtils.kt      # Date range calculations
    ├── StringDistanceUtils.kt  # String similarity
    ├── BKTree.kt              # Fuzzy search
    ├── MerchantCleaner.kt      # Merchant name cleaning
    ├── CurrencyNormalizer.kt
    └── AppConstants.kt
```

### Data Layer (`data/`)
```
data/
├── repository/                   # Data access (single source of truth)
│   ├── ExpenseRepository.kt     # Expense CRUD
│   ├── BudgetRepository.kt      # Budget CRUD
│   ├── CategoryRepository.kt    # Category CRUD
│   ├── NotificationRepository.kt # Notification processing
│   ├── ReviewQueueRepository.kt # Review queue
│   ├── RecurringExpenseRepository.kt
│   ├── FinancialWeatherRepository.kt
│   ├── AnalyticsRepository.kt
│   ├── MerchantLocationRepository.kt  # Location enrichment
│   └── ...
├── location/                    # Geocoding services (NEW Mar 2026)
│   ├── CompositeGeocodingService.kt   # Multi-provider fallback
│   ├── NominatimGeocodingService.kt   # OpenStreetMap
│   ├── GeoapifyGeocodingService.kt    # Geoapify API
│   ├── GooglePlacesGeocodingService.kt # Google Places API
│   └── PhotonGeocodingService.kt      # Photon API
├── database/
│   ├── AppDatabase.kt          # Room database (v31)
│   ├── entity/                  # Room entities
│   │   ├── Expense.kt
│   │   ├── Budget.kt
│   │   ├── Category.kt
│   │   ├── RawNotification.kt
│   │   ├── PendingReview.kt
│   │   ├── MerchantLocation.kt  # (NEW)
│   │   ├── MerchantLocationCorrection.kt  # (NEW)
│   │   └── ...
│   ├── dao/                    # Room DAOs
│   │   ├── ExpenseDao.kt
│   │   ├── BudgetDao.kt
│   │   ├── MerchantLocationDao.kt  # (NEW)
│   │   └── ...
│   ├── model/                  # Database models
│   └── converter/              # Type converters
├── service/
│   └── AndroidNotificationService.kt # Android notifications
└── provider/
    └── MerchantCategoryProvider.kt # Pre-defined categories
```

---

## Data Flow

### Notification → Expense Flow
```
1. NotificationCaptureService (Android)
         ↓
2. AppParserRegistry → Specific Parser (GreekBank, Revolut, etc.)
         ↓
3. ConfidenceRouter → Determine confidence level
         ↓
4. CategorizationEngine → Assign category
         ↓
5. NotificationRepository → Save to DB
         ↓
6. ReviewQueueRepository → Add to review queue (if needed)
         ↓
7. ReviewScreen (UI) → User approves/rejects
         ↓
8. ExpenseRepository → Save as final expense
```

### Forecast Flow
```
HomeScreen
    │
    ▼
HomeViewModel
    │
    ▼
FinancialWeatherRepository
    │
    ├──► BudgetRepository ──────────────► BudgetCalculator
    │                                        │
    ├──► RecurringExpenseRepository ──────► SynthesisEngine
    │                                        │
    └──► ExpenseRepository ──────────────► NarrativeGenerator
                                                     │
                                                     ▼
                                              FinancialForecast
                                                     │
                                                     ▼
                                              HomeScreen (UI)
```

---

## Key Components

### Main Entry Points
| Component | File | Purpose |
|-----------|------|---------|
| Application | `ExpenseTrackerApp.kt` | Hilt setup, lifecycle |
| Main Activity | `ui/MainActivity.kt` | Navigation, bottom bar |
| Database | `data/database/AppDatabase.kt` | Room DB v23 |

### Core Engines
| Engine | File | Purpose |
|--------|------|---------|
| Forecast | `domain/logic/SynthesisEngine.kt` | Month-end prediction (deterministic) |
| Monte Carlo | `domain/forecasting/MonteCarloSpendingSimulator.kt` | Probabilistic spending forecast (stochastic) |
| Budget | `domain/budget/BudgetMonitor.kt` | Budget alerts |
| Categorization | `domain/categorization/CategorizationEngine.kt` | Auto-categorization (5-layer pipeline) |
| Recurring | `domain/logic/RecurringExpenseEngine.kt` | Pattern detection |
| Insights | `domain/analytics/InsightsEngine.kt` | Spending insights (coordinator) |
| Spending Pace | `domain/analytics/SpendingPaceCalculator.kt` | Pace calculation |
| Anomaly Detection | `domain/analytics/AnomalyDetector.kt` | Unusual transactions |
| Month Comparison | `domain/analytics/MonthlyComparisonCalculator.kt` | Month vs month |
| Category Insights | `domain/analytics/CategoryInsightEngine.kt` | Category analysis |
| Merchant Insights | `domain/analytics/MerchantInsightEngine.kt` | Merchant patterns |
| Day of Week | `domain/analytics/DayOfWeekAnalyzer.kt` | Day patterns |
| Dashboard Widgets | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Dashboard widget computation |
| Dashboard Data | `domain/usecase/dashboard/DashboardDataProvider.kt` | Dashboard data provider (flatMapLatest) |

### New Categorization Components (Feb 2026)
| Component | File | Purpose |
|-----------|------|---------|
| Greeklish Normalizer | `GreeklishNormalizer.kt` | Greek to Latin with diphthongs (μπ→b, ου→ou) |
| Merchant Canonicalizer | `MerchantCanonicalizer.kt` | Strip corporate suffixes (IKE, EPE, ΑΦΟΙ) |
| Semantic Keyword Matcher | `SemanticKeywordMatcher.kt` | Word-boundary regex matching |
| Contextual Inference | `ContextualInferenceEngine.kt` | Amount/time-based category inference |
| Category Keywords | `CategoryKeywords.kt` | Pre-defined keyword mappings |

### Monte Carlo Spending Simulator (Mar 2026)
| Component | File | Purpose |
|-----------|------|---------|
| Simulator Engine | `MonteCarloSpendingSimulator.kt` | 1000-iteration Monte Carlo simulation |
| Result Model | `MonteCarloResult.kt` | Percentiles (P10/P25/P50/P75/P90), probability under budget |
| Distribution Builder | `HistoricalSpendingDistribution.kt` | Weekly aggregation + log-normal fitting |
| Quality Assessor | `DataQualityAssessor.kt` | Confidence scoring (volume/density/fitness/recency) |
| UI Card | `MonteCarloForecastCard.kt` | Dashboard widget display |

### Location Enrichment System (Mar 2026) - ALL 5 FEATURES IMPLEMENTED
| Component | File | Purpose |
|-----------|------|---------|
| Composite Geocoder | `CompositeGeocodingService.kt` | Multi-provider fallback chain |
| Nominatim | `NominatimGeocodingService.kt` | OpenStreetMap (free, no API key) |
| Geoapify | `GeoapifyGeocodingService.kt` | Geoapify API (freemium) |
| Google Places | `GooglePlacesGeocodingService.kt` | Google Places API (paid) |
| Photon | `PhotonGeocodingService.kt` | Photon API (free) |
| Location Resolver | `LocationResolver.kt` | Domain layer coordinator |
| Location Models | `LocationModels.kt`, `GeocodingResult.kt` | Domain models |
| Location Insights | `LocationInsightsEngine.kt` | Location-based spending insights |
| Spending Heatmap | `SpendingHeatmapEngine.kt` | Heatmap data generation |
| Nearby POI | `NearbyPoi.kt` | Points of interest model |
| Overpass Service | `OverpassNearbyService.kt` | OpenStreetMap POI queries |
| Background Worker | `LocationBackfillWorker.kt` | Background location enrichment |
| Location Provider | `AndroidForegroundLocationProvider.kt` | Foreground location tracking |
| Map Screen | `SpendingMapScreen.kt` | Map visualization (contains OsmMapView, MarkerDetailCard, PinExpenseSheet) |
| Location Search Picker | `LocationSearchPicker.kt` | Manual location picker UI (collapsible map) |
| Correction Sheet | `LocationCorrectionSheet.kt` | "Correct pin" bottom sheet (uses LocationSearchPicker) |
| Permission Dialog | `LocationPermissionDialog.kt` | Location permission request |

**Feature A**: Auto-enrich from merchant name (reverse geocode from known merchant locations)
**Feature B**: Reverse geocode from transaction address text
**Feature C**: Forward geocode user search queries
**Feature D**: Manual user correction
**Feature E**: Map visualization of spending

### Advanced Analytics Features (Mar 2026) - ALL 6 FEATURES IMPLEMENTED

#### Feature 1: Anomaly Detection Upgrade
| Component | File | Purpose |
|-----------|------|---------|
| Detector | `AnomalyDetector.kt` | Multi-method anomaly detection (MAD, IQR, Contextual, Multiplier) |
| Models | `AnalyticsModels.kt` | `AnomalyMethod` enum, `AnomalyTransaction` fields |
| Integration | `InsightsEngine.kt` | `findAnomalies()` updated to use new detector |

**Detection Methods:**
- **MAD** (Median Absolute Deviation): Flags transactions > 3x MAD from median - most robust
- **IQR** (Interquartile Range): Flags transactions > 1.5x IQR above Q3 - classic statistical outlier
- **Contextual**: Compares to category average for that merchant
- **Multiplier**: Flags round amounts >=500 EUR divisible by 50 AND >2x average

#### Feature 2: Cumulative Spending Curve
| Component | File | Purpose |
|-----------|------|---------|
| Data Model | `ComputeDashboardWidgetsUseCase.kt` | `SpendingTrendSeries` with multi-month cumulative data |
| Chart | `SpendingTrendChart.kt` | Multi-series line chart (current + 5 prior months) |
| Widget | `DashboardWidget.SpendingTrend` | Stores series list |
| UI | `HomeScreen.kt` | Renders multi-line trend chart |

#### Feature 3: Year-over-Year Comparison
| Component | File | Purpose |
|-----------|------|---------|
| Models | `AnalyticsModels.kt` | `MonthlyYearTotal`, `YearOverYearComparison` |
| Compute | `AnalyticsViewModel.kt` | `computeYearOverYear()` function |
| UI | `AnalyticsScreen.kt` | `YearOverYearCard` composable |

#### Feature 4: Spending Velocity Anomaly
| Component | File | Purpose |
|-----------|------|---------|
| Models | `AnalyticsModels.kt` | `VelocityAnomaly` with date, amount, deviation |
| Compute | `AnalyticsViewModel.kt` | `computeVelocityAnomalies()` - flags days >2x avg AND >IQR fence |
| UI | `AnalyticsScreen.kt` | `VelocityAnomalyCard` composable |

#### Feature 5: Post-Salary Sequential Pattern
| Component | File | Purpose |
|-----------|------|---------|
| Models | `AnalyticsModels.kt` | `PostSalaryCategory`, `PostSalaryPattern` |
| Compute | `AnalyticsViewModel.kt` | `computePostSalaryPattern()` - tracks spending after salary deposits |
| UI | `AnalyticsScreen.kt` | `PostSalaryPatternCard` composable |

**Algorithm:**
- Identifies salary deposits (DEPOSIT or incoming TRANSFER)
- Finds largest deposit per month (assumed salary)
- Tracks spending in 7 days after each salary
- Shows: avg days to first purchase, avg spend per cycle, top categories

#### Feature 6: Duplicate/Error Detection
| Component | File | Purpose |
|-----------|------|---------|
| Models | `AnalyticsModels.kt` | `SuspectReason` enum, `SuspectTransaction` model |
| Compute | `AnalyticsViewModel.kt` | `detectSuspectTransactions()` function |
| UI | `AnalyticsScreen.kt` | `SuspectTransactionCard` composable |

**Detection Rules:**
- **Near Duplicate**: Same amount + same merchant within 24 hours
- **Round Amount**: >=500 EUR AND divisible by 50 AND >2x average
- **Extreme Outlier**: >5x period average

### Analytics ViewModel (NEW)
| Component | File | Purpose |
|-----------|------|---------|
| ViewModel | `AnalyticsViewModel.kt` | Full analytics state with all 6 features |
| State | `AnalyticsState` | Contains all feature fields (anomalies, yearOverYear, velocityAnomalies, postSalaryPattern, suspectTransactions) |
| Screen | `AnalyticsScreen.kt` | Main analytics UI (replaced `AdvancedAnalyticsScreen`) |

### Parsers (Notification Processing)
| Parser | File | Handles |
|--------|------|---------|
| Greek Bank | `GreekBankParser.kt` | NBG, Alpha, Eurobank, Piraeus |
| Revolut | `RevolutParser.kt` | Revolut app |
| Google Wallet | `GoogleWalletParser.kt` | Google Pay |
| SMS | `SmsParser.kt` | SMS bank notifications |
| Generic | `GenericTransactionParser.kt` | Fallback parser |

---

## Dependency Injection

### Hilt Modules
```
di/
├── AppModule.kt           # Legacy (backwards compatibility)
├── DatabaseModule.kt      # Room database (NEW)
├── DaoModule.kt           # All DAOs (NEW)
├── ServiceModule.kt       # Android services (NEW)
├── TimeModule.kt         # TimeProvider binding
└── DispatchersModule.kt  # Coroutine dispatchers
```

### Key Bindings
```kotlin
// DatabaseModule
@Singleton @Provides AppDatabase

// DaoModule  
@Singleton @Provides ExpenseDao
@Singleton @Provides BudgetDao
@Singleton @Provides CategoryDao
@Singleton @Provides RawNotificationDao
@Singleton @Provides PendingReviewDao
// ... all other DAOs

// ServiceModule
@Singleton @Provides NotificationService → AndroidNotificationService

// GeocodingService: Multi-provider cascade (Photon → Geoapify → Google → Nominatim)
@Singleton @Provides GeocodingService → CompositeGeocodingService

// NearbyPoiService: Overpass API for POI queries
@Singleton @Provides NearbyPoiService → OverpassNearbyService

// ForegroundLocationProvider: Device GPS tracking
@Singleton @Provides ForegroundLocationProvider → AndroidForegroundLocationProvider

// TimeModule
@Binds @Singleton TimeProvider → SystemTimeProvider
```

---

## Database Schema

### Version: 31 (Updated Mar 2026)

### Key Entities
```
expenses
├── id (PK)
├── amount
├── merchant
├── categoryId (FK)
├── date
├── transactionType (PURCHASE, DEPOSIT, etc.)
├── isManualEntry
├── paymentMethod
├── notes
└── dedupeKey

categories
├── id (PK)
├── name
├── icon
├── color
└── isIncome

budgets
├── id (PK)
├── categoryId (FK)
├── amount
├── period (DAILY, WEEKLY, MONTHLY, YEARLY)
├── notifyAtWarning (0.0-1.0)
├── notifyAtCritical (0.0-1.0)
├── rollover (Boolean)
└── lastWarningNotifiedAt

raw_notifications
├── id (PK)
├── packageName
├── appName
├── title
├── text
├── timestamp
└── isRelevant

pending_reviews
├── id (PK)
├── rawNotificationId (FK)
├── scannedReceiptId (FK)
├── suggestedAmount
├── suggestedCurrency
├── suggestedMerchant
├── suggestedType (PURCHASE, TRANSFER, etc.)
├── suggestedCategoryId (FK)
├── suggestedDate
├── confidence
├── matchType (EXACT, CANONICAL, KEYWORD, CONTEXT, ML) (NEW v27)
├── explanation (NEW v27)
├── packageName
├── notificationTitle
├── notificationText
├── status (PENDING, APPROVED, REJECTED)
├── suggestedDirection (INCOMING, OUTGOING) (NEW v24)
├── suggestedAccountName (NEW v24)
└── createdAt

merchant_categories (NEW v26)
├── merchantPattern (PK)
├── categoryId (FK)
├── confidence
├── timesUsed
└── normalizedCanonicalName (NEW v26)
```

### Key Indices
```sql
index_expenses_date ON expenses(date)
index_expenses_categoryId_date ON expenses(categoryId, date)
index_expenses_transactionType_date ON expenses(transactionType, date)
index_expenses_dedupeKey ON expenses(dedupeKey) UNIQUE
index_raw_notifications_packageName_timestamp_title_text UNIQUE
```

---

## Quick Reference

### Add New Parser
1. Create `domain/parser/parsers/NewParser.kt` extending base parser
2. Register in `AppParserRegistry.parserList`
3. Add test cases in `domain/parser/`

### Add New Screen
1. Create `ui/screens/feature/FeatureScreen.kt`
2. Create `ui/screens/feature/FeatureViewModel.kt`
3. Add navigation in `MainActivity.kt`
4. Add DI bindings if needed in `di/`

### Add New Database Entity
1. Create `data/database/entity/NewEntity.kt`
2. Add to `AppDatabase.entities` array
3. Create DAO in `data/database/dao/NewEntityDao.kt`
4. Add provider in `di/AppModule.kt`
5. Create migration in `AppDatabase` (version++)

### Check Bug Sources
| Issue | Check Files |
|-------|-------------|
| Forecast wrong | SynthesisEngine, FinancialWeatherRepository, BudgetCalculator |
| Monte Carlo wrong | MonteCarloSpendingSimulator, HistoricalSpendingDistribution, DataQualityAssessor |
| Budget alerts | BudgetMonitor, AndroidNotificationService |
| Parser failing | AppParserRegistry, specific *Parser.kt, ConfidenceRouter |
| OCR issues | ReceiptOcrService, ReceiptParser, ML Kit config |
| Category wrong | CategorizationEngine, MerchantNormalizer, HybridExpenseClassifier |
| Recurring missed | RecurringExpenseEngine, RecurringExpenseRepository |

### Recent Critical Fixes (2026)
| Issue | Fix |
|-------|-----|
| ExpenseRepository memory leak | Removed local CoroutineScope, uses direct flow |
| InsightsEngine God Object | Split into 6 focused engines |
| Input validation | Added max 200 char limit to MerchantNormalizer |
| Flow error handling | Added catch + emit empty in FinancialWeatherRepository |
| Category learning race | Added Mutex to updateExpenseCategory |
| Statement vs Notification duplicates | Added CrossSourceDeduplication check in ReceiptRepository |
| PendingReview duplicates | Added duplicate detection against pending reviews before creating new ones |
| Greek pattern matching | Added accent-insensitive Greek patterns to TransferDirectionDetector |
| Keyword false positives | Added regex word boundaries to SemanticKeywordMatcher |
| Grocery amount inference | Added €20-€150 bracket to ContextualInferenceEngine |
| Monte Carlo Simulator | NEW: Probabilistic month-end spending forecast (Mar 2026) |
| AnomalyDetector ordinal priority | Fixed < to > so MAD beats IQR |
| AnomalyDetector division by zero | Added guards for categoryAvg/contextAvg = 0 |
| ComputeDashboardWidgetsUseCase txCount | Now uses today's count instead of month-wide |
| detectSuspectTransactions round-amount | Added >2x average requirement |
| computeVelocityAnomalies unused param | Removed unused periodStartMs |
| computePostSalaryPattern force-unwrap | Replaced !! with safe unwrap |
| String.format locale | Added Locale.US for decimal consistency |
| FinancialRunway daily rate | Fixed to use actual MTD spend, not projected total |
| DashboardDataProvider stale timestamp | Now recomputes monthStart/monthEnd on every emission |
| AnalyticsScreen Tab 4 | Switched to AnalyticsScreen with all 6 features |

### Recent Bug Fixes (Mar 2026)
| Issue | Fix |
|-------|-----|
| Cross-source Greek/Latin merchant duplicate detection | Greek→Latin transliteration in MerchantNormalizer.createSearchKey(), Expense.generateDedupeKey(), MerchantRulesRepository regex (Fix 1a-c) |
| Revolut duplicate detection | Removed AND transactionType='PURCHASE' filter from isDuplicate() query in ExpenseDao (Fix 2) |
| Revolut trust score inflation | Trust score denominator changed from totalNotifications to totalNotifications - autoRejected in SourceStats (Fix 3) |
| Shared expense amounts not in totals | Added effectiveAmount computed property to Expense entity, updated all SUM queries and Kotlin sumOf calls across 15+ files (Fix 4a-c) |

### Location Feature Bug Fixes (Mar 2026)
| Issue | Fix |
|-------|-----|
| F1: Map always visible in LocationSearchPicker | Made map collapsible (hidden by default, toggle button, auto-expand on search results) |
| F2: Long-press pin not resolving address | Added reverseGeocode override in CompositeGeocodingService |
| F3: FAB centre-on-device not working | Wired FAB onClick → centreOnDeviceRequest flag → OsmMapView animateTo |
| F4: osmdroid config loading race condition | Moved Configuration.getInstance().load() from LaunchedEffect to factory lambda |
| F5: Map tiles not loading immediately | Added mv.onResume() in factory lambda |
| F6: Map markers disappear on recomposition | Added key-based diff guard in OsmMapView.update |
| F7: OSM ID not captured in Review | Captured osmId in onResult callback, added to onSave |
| F8: Map too small | Increased map height from 200dp to 260dp |
| F1 Regression: Map breaks dialog layouts | Map now collapsed by default, toggle to show/hide |

### Transfer Direction Detection Feature (Updated Feb 2026)
| Component | File | Purpose |
|-----------|------|---------|
| Detector | `domain/parser/TransferDirectionDetector.kt` | 60+ patterns for EN/GR, Greek accent handling |
| Analytics | `domain/analytics/TransferDirectionAnalytics.kt` | Detection rate tracking |
| UI Badge | `ui/components/TransferDirectionBadge.kt` | Direction visual indicator |
| Deduplication | `domain/intelligence/CrossSourceDeduplication.kt` | Cross-source duplicate detection (ENHANCED) |

### Cross-Source Deduplication (Feb 2026)
| Component | File | Purpose |
|-----------|------|---------|
| Deduplication | `CrossSourceDeduplication.kt` | Detects duplicates across notifications, statements, pending reviews |
| DAO | `PendingReviewDao.kt` | Date range queries for duplicate checking |
| Repository | `ReceiptRepository.kt` | Skips duplicate pending reviews when processing statements |

### Transaction Types Supported
- **PURCHASE** - Regular purchases
- **DEPOSIT** - Money received (salary, etc.)
- **TRANSFER** - Between accounts (with INCOMING/OUTGOING direction)
- **WITHDRAWAL** - Cash withdrawals
| Analytics slow | InsightsEngine, AdvancedAnalyticsEngine, AnalyticsRepository |

---

## Testing

### Unit Tests Location
```
app/src/test/java/com/yourname/expensetracker/
├── domain/
│   ├── budget/
│   │   ├── BudgetMonitorTest.kt
│   │   └── BudgetCalculatorTest.kt
│   ├── logic/
│   │   └── RecurringExpenseEngineTest.kt
│   ├── parser/
│   │   ├── GreekBankParserTest.kt
│   │   └── RevolutParserTest.kt
│   └── analytics/
│       └── InsightsEngineTest.kt
├── data/repository/
│   ├── ExpenseRepositoryTest.kt
│   └── FinancialWeatherRepositoryTest.kt
└── domain/util/
    └── TimePeriodUtilsTest.kt
```

### Run Tests
```bash
./gradlew testDebugUnitTest
```

---

## Common Patterns

### StateFlow Usage
```kotlin
// In ViewModel
val state: StateFlow<UiState> = repository.data
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialValue)

// In Composable
val state by viewModel.state.collectAsState()
```

### Repository Pattern
```kotlin
@Singleton
class ExpenseRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    fun getExpenses(): Flow<List<Expense>> = expenseDao.getAll()
    
    suspend fun insertExpense(expense: Expense) = withContext(ioDispatcher) {
        expenseDao.insert(expense)
    }
}
```

### Engine Pattern
```kotlin
class SynthesisEngine @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val recurringRepository: RecurringExpenseRepository
) {
    suspend fun generateForecast(): FinancialForecast = withContext(Dispatchers.Default) {
        // Complex calculation
    }
}
```

---

## Navigation

### MainActivity Tabs
| Index | Screen | File |
|-------|--------|------|
| 0 | Dashboard | `HomeScreen.kt` |
| 1 | Activity | `TransactionsScreen.kt` |
| 2 | Review | `ReviewScreen.kt` |
| 3 | Plan | `BudgetScreen.kt` |
| 4 | Analytics | `AnalyticsScreen.kt` (all 6 advanced features) |
| 5 | Map | `SpendingMapScreen.kt` (location visualization, marker details, pin expense) |

### Deep Links
```
expensetracker://home       → Tab 0
expensetracker://activity   → Tab 1  
expensetracker://review     → Tab 2
expensetracker://plan       → Tab 3
expensetracker://analytics  → Tab 4
expensetracker://map        → Tab 5
```

---

## Appendix: Complete File Reference

### UI Components (`ui/components/`)
| Component | Purpose |
|-----------|---------|
| `BentoCard.kt` | Dashboard card layout |
| `PulseDot.kt` | Animated pulse indicator |
| `FinancialWeatherCard.kt` | Forecast display (clear/cloudy/stormy) |
| `FinancialRunwayCard.kt` | Days until money runs out |
| `ForecastTimeline.kt` | Visual timeline of projected spending |
| `BudgetBlockPartyCard.kt` | Budget burning visualization |
| `SpendingTrendChart.kt` | Trend visualization |
| `SpendingPaceGauge.kt` | Spending pace gauge |
| `ChartMarker.kt` | Chart markers |
| `TransferDirectionBadge.kt` | Transfer direction indicator (NEW) |
| `MonteCarloForecastCard.kt` | Probabilistic month-end forecast (P10/P50/P90) |
| `SpendingTrendChart.kt` | Multi-month cumulative spending curve (Feature 2) |
| `FinancialRunwayCard.kt` | Days remaining + discretionary + daily rate |
| `VelocityAnomalyCard.kt` | Spending velocity anomaly display (Feature 4) |
| `YearOverYearCard.kt` | Year-over-year comparison (Feature 3) |
| `PostSalaryPatternCard.kt` | Post-salary spending pattern (Feature 5) |
| `SuspectTransactionCard.kt` | Duplicate/error detection (Feature 6) |
| `LocationSearchPicker.kt` | Location search + picker (collapsible map) |
| `LocationCorrectionSheet.kt` | "Correct pin" bottom sheet |
| `LocationPermissionDialog.kt` | Location permission dialog |

### Domain Models (`domain/model/`)
| Model | Purpose |
|-------|---------|
| `FinancialForecast.kt` | Forecast data |
| `Budget.kt`, `BudgetStatus.kt`, `BudgetHealthStatus.kt` | Budget models |
| `Expense.kt`, `TransactionType.kt` | Expense models |
| `Category.kt` | Category model |
| `RecurringPattern.kt` | Recurring expense pattern |
| `UpcomingItem.kt` | Upcoming expense item |
| `PeriodRange.kt` | Date period range |
| `Result.kt` | Result wrapper (Success/Error/Loading) |
| `BlockPartyDay.kt` | Block party day model |

### All Repositories (`data/repository/`)
| Repository | Purpose |
|------------|---------|
| `ExpenseRepository.kt` | Expense CRUD |
| `BudgetRepository.kt` | Budget CRUD, rollover calculations |
| `CategoryRepository.kt` | Category CRUD |
| `NotificationRepository.kt` | Notification processing |
| `ReviewQueueRepository.kt` | Review queue management |
| `RecurringExpenseRepository.kt` | Recurring expenses |
| `PlannedExpenseRepository.kt` | Planned/future expenses |
| `FinancialWeatherRepository.kt` | Forecast data |
| `AnalyticsRepository.kt` | Analytics queries |
| `SavingsGoalRepository.kt` | Savings goals |
| `SourceStatsRepository.kt` | Parser performance stats |
| `UserCorrectionRepository.kt` | User corrections for ML |
| `MerchantRulesRepository.kt` | Merchant rules |
| `MerchantCategoryRepository.kt` | Merchant-category mappings |
| `MerchantNormalizationRepository.kt` | Merchant canonical storage |
| `ManualExpenseRepository.kt` | Manual expense entry |
| `MerchantLocationRepository.kt` | Location enrichment storage |

### Android Services & Receivers
| Component | File | Purpose |
|-----------|------|---------|
| Notification Capture | `service/NotificationCaptureService.kt` | Android NotificationListenerService |
| Service Restart | `receiver/ServiceRestartReceiver.kt` | Restarts notification service |
| Boot | `receiver/BootReceiver.kt` | Starts service on device boot |

### Database Entities (Room)
| Entity | File |
|--------|------|
| Expense | `data/database/entity/Expense.kt` |
| Budget | `data/database/entity/Budget.kt` |
| Category | `data/database/entity/Category.kt` |
| RawNotification | `data/database/entity/RawNotification.kt` |
| PendingReview | `data/database/entity/PendingReview.kt` |
| SourceStats | `data/database/entity/SourceStats.kt` |
| BlockedPackage | `data/database/entity/BlockedPackage.kt` |
| ScannedReceipt | `data/database/entity/ScannedReceipt.kt` |
| ManualRecurringExpense | `data/database/entity/ManualRecurringExpense.kt` |
| PlannedExpense | `data/database/entity/PlannedExpense.kt` |
| SavingsGoal | `data/database/entity/SavingsGoal.kt` |
| MerchantCanonical | `data/database/entity/MerchantCanonical.kt` |
| MerchantAlias | `data/database/entity/MerchantAlias.kt` |
| UserCorrection | `data/database/entity/UserCorrection.kt` |
| MerchantLocation | `data/database/entity/MerchantLocation.kt` |
| MerchantLocationCorrection | `data/database/entity/MerchantLocationCorrection.kt` |

### Database DAOs
| DAO | Purpose |
|-----|---------|
| `ExpenseDao.kt` | Expense queries |
| `BudgetDao.kt` | Budget queries |
| `CategoryDao.kt` | Category queries |
| `RawNotificationDao.kt` | Notification queries |
| `PendingReviewDao.kt` | Review queue queries |
| `SourceStatsDao.kt` | Stats queries |
| `BlockedPackageDao.kt` | Blocked package queries |
| `ScannedReceiptDao.kt` | Receipt queries |
| `RecurringExpenseDao.kt` | Recurring expense queries |
| `PlannedExpenseDao.kt` | Planned expense queries |
| `SavingsGoalDao.kt` | Savings goal queries |
| `MerchantCategoryDao.kt` | Merchant category queries |
| `MerchantNormalizationDao.kt` | Merchant normalization queries |
| `UserCorrectionDao.kt` | Correction queries |
| `MerchantLocationDao.kt` | Merchant location queries |

---

## Segment Mapping

| Segment | Files | Main Files |
|---------|-------|------------|
| 1: Financial Forecast | ~20 | SynthesisEngine, MonteCarloSpendingSimulator, FinancialWeatherRepository, HomeScreen |
| 2: Budget | ~8 | BudgetCalculator, BudgetMonitor, BudgetRepository |
| 3: Notification Parsing | ~20 | NotificationCaptureService, AppParserRegistry, *Parser.kt |
| 4: OCR/Receipt | ~8 | ReceiptOcrService, ReceiptParser, ReceiptRepository |
| 5: Categorization | ~15 | CategorizationEngine, MerchantNormalizer, CategoryRepository |
| 6: Recurring | ~5 | RecurringExpenseEngine, RecurringExpenseRepository |
| 7: Analytics | ~20 | InsightsEngine, AnomalyDetector, AdvancedAnalyticsEngine, AnalyticsViewModel, AnalyticsScreen |
| 8: Core Expense | ~20 | ExpenseRepository, TransactionsScreen, AddExpenseSheet |
| 9: Dashboard | ~15 | MainActivity, DashboardRepository, HomeViewModel, ComputeDashboardWidgetsUseCase, DashboardDataProvider |
| 10: Notifications | ~3 | AndroidNotificationService, NotificationService |
| 11: Debug | ~8 | DebugScreen, DebugViewModel, ServiceDiagnostics |
| 12: DI | ~6 | AppModule, DatabaseModule, DaoModule, ServiceModule |
| 13: Utilities | ~20 | AmountUtils, DateFormatterUtils, TimeProvider |
| 14: Use Cases | ~6 | ProcessReceiptUseCase, CategorizeExpenseUseCase, etc. |
| 15: Performance | ~2 | ImageCache, ReceiptOcrService optimizations |
| 16: Configuration | ~1 | AppConfig |
| 17: Location | ~15 | CompositeGeocodingService, NominatimGeocodingService, LocationResolver, SpendingMapScreen |
| 18: AI Follow-Through (Phase 4B) | ~25 | DashboardFollowThroughEngine, RecommendationRepository, RecommendationStateManager, RecommendationCard |

---

## Phase 4B: AI Follow-Through (NEW - Mar 2026)

**Overview:** Dashboard follow-through recommendations system that transforms passive AI insights into actionable guidance. Users tap on AI briefing recommendations to navigate to deterministic filtered views.

**Key Principle:** AI is responsible for summarization only. All navigation targets, filters, and financial truth remain deterministic and authoritative.

### Architecture

```
AI Briefing (Phase 4A)     Transaction Created
    ↓                          ↓
    └─────────────────────┬────┘
                          ↓
              DashboardFollowThroughEngine
              (Deterministic Rules)
                          ↓
    ┌─────────────────────┼─────────────────────┐
    ↓                     ↓                     ↓
HIGH PRIORITY     MEDIUM PRIORITY     LOW PRIORITY
(Large tx)        (Category/Merchant) (Recent)
    ↓                     ↓                     ↓
    └─────────────────────┬─────────────────────┘
                          ↓
    RecommendationRepository
    (CRUD + Cache)
                          ↓
    Room: recommendations table
    (Multi-user, TTL-based)
                          ↓
    RecommendationStateManager
    (StateFlow for UI)
                          ↓
    HomeScreen
    (RecommendationCards)
                          ↓
    User Tap → Navigation
    User Dismiss → Archive
```

### Components

| Component | Purpose | Status |
|-----------|---------|--------|
| **DashboardFollowThroughEngine** | Rule-based recommendation builder (deterministic) | ✅ Phase 2 |
| **RecommendationRepository** | CRUD + cache logic | ✅ Phase 1 |
| **RecommendationStateManager** | Reactive state for UI | ✅ Phase 2 |
| **RecommendationDismissalHandler** | Dismissal workflow | ✅ Phase 2 |
| **RecommendationLifecycleManager** | TTL management, periodic cleanup | ✅ Phase 2.1 |
| **RecommendationCacheService** | LRU in-memory cache | ✅ Phase 2 |
| **RecommendationCard** | UI component | ✅ Phase 2 |

### Database: `recommendations` Table (v32+)

```sql
recommendations (
  id TEXT PRIMARY KEY,
  userId TEXT NOT NULL,              -- Multi-user isolation
  recommendationText TEXT NOT NULL,   -- AI-generated summary
  navigationTarget TEXT NOT NULL,     -- Deterministic target
  filterCriteria TEXT NOT NULL,      -- Serialized TransactionFilter
  priority TEXT NOT NULL,             -- HIGH, MEDIUM, LOW
  status TEXT NOT NULL,               -- ACTIVE, ARCHIVED, EXPIRED
  createdAt BIGINT NOT NULL,
  expiresAt BIGINT NOT NULL,          -- TTL = 7 days
  dismissedAt BIGINT,                 -- Null unless dismissed
  category TEXT NOT NULL,
  sourceArtifactId TEXT NOT NULL,     -- Link to ai_artifacts
  
  INDEX idx_rec_active (userId, status, expiresAt),
  INDEX idx_rec_artifact (sourceArtifactId),
  INDEX idx_rec_created (createdAt),
  INDEX idx_rec_expiry (expiresAt)
)
```

### Configuration

```kotlin
RECOMMENDATION_TTL_MS = 7 days
MAX_RECOMMENDATIONS_PER_USER = 5
RECOMMENDATION_CLEANUP_INTERVAL_MS = 6 hours
PRIORITY_WEIGHTS: HIGH=3, MEDIUM=2, LOW=1
```

### Design Principles

1. **Deterministic Authority**: All navigation and filtering is rule-based code
2. **AI Summarization Only**: Recommendation text comes from AI; decisions do not
3. **Multi-User Safe**: Complete userId isolation
4. **TTL-Based Lifecycle**: Automatic expiry after 7 days
5. **Soft-Delete Pattern**: Archive before hard delete for analytics
6. **Observable**: Reactive StateFlow for UI observation
7. **Thread-Safe**: AtomicBoolean guards for concurrent access
8. **Well-Logged**: Timber integration for debugging

### Related Documents

- **PHASE_4B_MASTER.md**: Complete Phase 4B documentation (master reference)
- **ARCHITECTURE_ADDENDUM.md**: Extended architecture patterns
- **PHASE_4B_PHASE1.md**: Phase 1 infrastructure specification
- **CODEBASE_SEGMENTS.md → Segment 18**: File-level mapping
