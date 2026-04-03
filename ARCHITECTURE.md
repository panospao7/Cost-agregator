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
- Segment 15 → Performance
- Segment 16 → Configuration
- Segment 17 → Location
- Segment 18 → AI Follow-Through
- Segment 19 → Totals Dashboard
- Segment 20 → Groups (Shared Expense)

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
7. [Recent Changes & Fixes](#recent-changes--fixes)
8. [Quick Reference](#quick-reference)

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
| Totals Aggregation | `domain/analytics/TotalsAggregationEngine.kt` | Period totals aggregation (NEW Mar 2026) |
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

## Recent Changes & Fixes (Mar 2026)

### 1. AI Receipt Item Categorization (NEW)
**Overview:** AI-powered analysis of individual receipt items to categorize each line item separately, providing better spending insights.

**Key Features:**
- Automatically categorizes each item on a receipt (e.g., "Apples → Food", "Detergent → Household")
- Confidence scoring for each categorization (90%+ = High, 70-89% = Good, <70% = Needs Review)
- Alternative category suggestions for uncertain items
- Tax distribution calculation across categories
- User can manually correct AI suggestions (learns from corrections)
- Can suggest creating new categories when items don't fit existing ones

**Components:**
| Component | File | Purpose |
|-----------|------|---------|
| **Entity** | `ReceiptItemCategorization.kt` | Stores categorization for each item |
| **DAO** | `ReceiptItemCategorizationDao.kt` | Database operations |
| **Use Case** | `CategorizeReceiptItemsUseCase.kt` | Main orchestrator |
| **Input Builder** | `ReceiptItemCategorizationInputBuilder.kt` | Prepares AI input |
| **Cloud Service** | `CloudReceiptItemCategorizationService.kt` | Gemini API integration |
| **On-Device Service** | `OnDeviceReceiptItemCategorizationService.kt` | Keyword-based fallback |
| **Hybrid Service** | `HybridReceiptItemCategorizationService.kt` | Smart routing |
| **UI Component** | `ReceiptItemBreakdownCard.kt` | Interactive item breakdown |
| **AI Capability** | `RECEIPT_ITEM_CATEGORIZATION` | Registered in AiCapability enum |

**Database Schema (v37):**
```sql
receipt_item_categorizations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  receiptId INTEGER NOT NULL,
  expenseId INTEGER,
  itemDescription TEXT NOT NULL,
  itemAmount REAL NOT NULL,
  suggestedCategoryId INTEGER,
  suggestedCategoryName TEXT,
  confidence REAL NOT NULL,
  aiRationale TEXT,
  alternativeCategoriesJson TEXT,
  userCorrectedCategoryId INTEGER,
  userCorrectedCategoryName TEXT,
  userCorrectedAt INTEGER,
  taxAmount REAL,
  isNewCategorySuggestion INTEGER NOT NULL DEFAULT 0,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
  FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
)
```

**AI Prompt Design:**
- Sends merchant name, available categories, and line items to AI
- AI returns JSON with category assignments, confidence scores, and rationale
- Alternative categories provided for items with confidence < 70%
- Tax distributed proportionally by item amount

**Auto-Trigger:**
- Automatically runs after receipt scan if:
  - AI is enabled (`aiEnabled = true`)
  - Receipt item categorization is enabled (`receiptItemCategorizationEnabled = true`)
  - Receipt has line items (`parsedItems.isNotEmpty()`)

**UI Flow:**
1. User scans receipt → Items detected
2. AI categorizes items automatically (shows loading state)
3. Displays breakdown with confidence badges
4. User can tap category chip to change it
5. Shows alternatives for low-confidence items
6. Rationale available via info button

---

### 2. Week Standardization Fix
**Problem:** Inconsistent week definitions across the app:
- Some screens used rolling 7-day windows (Wed→Wed)
- Others used calendar weeks (Mon→Sun)
- This caused confusion when comparing "weekly" data

**Solution:** Standardized all week calculations to Monday-Sunday calendar weeks.

**Files Changed:**
- `domain/util/TimePeriodUtils.kt` - Added `getWeekRange()` function
- `ui/screens/transactions/TransactionsViewModel.kt` - Changed WEEK tab
- `ui/screens/analytics/AnalyticsViewModel.kt` - Changed WEEK period
- `domain/ai/usecase/InterpretFinancialQueryUseCase.kt` - Changed "this week" query

**New Function:**
```kotlin
fun getWeekRange(timestamp: Long, weekOffset: Int = 0): Pair<Long, Long>
// Returns Monday 00:00:00.000 → Sunday 23:59:59.999
```

**Benefits:**
- Consistent week boundaries across all screens
- Matches traditional financial tracking (payroll, budgeting)
- No more confusion when comparing week data

---

### 3. SQL Date Boundary Fixes
**Problem:** Mixed inclusive/exclusive date boundaries in SQL queries caused:
- Expenses at exact boundary timestamps being double-counted or missed
- Inconsistent totals between list queries and aggregation queries

**Solution:** Standardized all queries to use half-open intervals `[start, end)`:
```sql
-- Standard pattern (inclusive start, exclusive end)
date >= :startMs AND date < :endMs
```

**Files Changed (10 queries in ExpenseDao.kt):**
- `getExpensesWithCategoryFilteredFlow()` 
- `getExpensesWithCategoryInPeriodFlow()`
- `getExpensesBetween()` / `getExpensesBetweenFlow()`
- `getExpensesByTypeBetween()` / `getExpensesByTypeBetweenFlow()`
- `getTotalSpentBetween()`
- `getMerchantTotalsBetween()`
- `getCategoryTotalsBetween()`
- `getDepositsBetween()` / `getDepositsBetweenFlow()`

**Before:** `date >= :start AND date <= :end` (inclusive-inclusive)
**After:** `date >= :start AND date < :end` (half-open)

**Benefits:**
- No double-counting at boundaries
- Consistent expense counting across all queries
- Clean mathematical intervals

---

### 4. Weekly Totals Partial Week Fix
**Problem:** March showing 6 weeks due to partial weeks at month boundaries:
- Week of Feb 24-Mar 2 had only 1 day (Mar 1) in March
- Week of Mar 30-Apr 5 had only 2 days in March
- These partial weeks were being counted as full weeks

**Solution:** Modified `TotalsAggregationEngine.getWeeklyTotals()` to include all weeks that touch the month:
```kotlin
// Include ALL weeks that have at least one day in the month
val monthWeeks = weeklyTotals.filter { weekly ->
    weekly.startDate < monthEndMs && weekly.endDate > monthStartMs
}
```

**Partial Week Labels:**
- Week 1 (Feb 24-Mar 2) shows as "W1 (1-1 Mar)" when viewed in March
- Week 6 (Mar 30-Apr 5) shows as "W6 (30-31 Mar)" when viewed in March
- Only the days actually in the month are counted

**Benefits:**
- No expenses lost at month boundaries
- Clear labeling of partial weeks
- Accurate monthly totals

---

### 5. Spending Totals Navigation Fix
**Problem:** Drill-up (back button) in spending totals widget was broken:
- Only handled MONTH→YEAR navigation
- Returned empty lists for other paths
- DAY→WEEK and WEEK→MONTH navigation didn't work

**Solution:** Complete rewrite of `HomeViewModel.drillUp()`:
- Properly handles all navigation paths: DAY→WEEK→MONTH→YEAR
- Loads appropriate data for each level
- Tracks parent/grandparent hierarchy correctly
- Shows proper loading states

**Files Changed:**
- `ui/screens/home/HomeViewModel.kt` - Fixed `drillUp()` function
- `domain/analytics/TotalsAggregationEngine.kt` - Added `getYearlyTotals()` method
- `data/repository/ExpenseRepository.kt` - Added `getTransactionCountForPeriod()`

**Navigation Flow:**
```
YEAR (2024, 2023, 2022, 2021, 2020)
  ↓ Click on 2024
MONTH (Jan, Feb, Mar, Apr, May, Jun, Jul, Aug, Sep, Oct, Nov, Dec)
  ↓ Click on March
WEEK (W1, W2, W3, W4, W5)
  ↓ Click on W2
DAY (Mon, Tue, Wed, Thu, Fri, Sat, Sun)
  ↓ Click on Mon
Category Breakdown (Food, Transport, etc.)

← Back button works at every level
← Filter chips work (click YEAR chip to go back from MONTH)
```

---

### 6. Statistical UI Enhancements (NEW Mar 2026)

**Problem:** Rich statistical calculations were being performed but not exposed in the UI:
- Percentiles (P10-P99) calculated but only P50 shown
- Histogram data built but never visualized
- Category velocity computed but not displayed
- Days without spending tracked but not gamified
- Merchant intelligence rich but underutilized

**Solution:** Created comprehensive statistical visualization layer:

#### **6.1 Percentile Grid Card**
**Location:** Analytics Screen (after Statistical Highlights)
**Shows:** P10, P25, P50, P75, P90 with visual gradient
**Value:** Users understand their "typical" transaction size spectrum
**Data Source:** `StatisticalInsights.percentiles`
**UI Component:** `PercentileGridCard()` in `StatisticalVisualizations.kt`

```kotlin
Your Spending Profile:
┌─────────────────────────────────────┐
│  Small (P10):    €12.50            │
│  Low (P25):      €28.30            │
│  Median (P50):   €45.00 ← Typical  │
│  High (P75):     €78.90            │
│  Large (P90):    €125.00           │
└─────────────────────────────────────┘
```

#### **6.2 Transaction Histogram Chart**
**Location:** Analytics Screen (below Percentile Grid)
**Shows:** 10-bin bar chart of transaction size distribution
**Value:** Visual understanding of spending concentration
**Data Source:** `StatisticalInsights.histogramBins`
**UI Component:** `TransactionHistogramChart()` in `StatisticalVisualizations.kt`

**Features:**
- X-axis: Amount ranges (€0-10, €10-25, etc.)
- Y-axis: Transaction count
- Highlight peak bin with percentage
- Insight text: "Peak: 36% of transactions are €25-€50"

#### **6.3 Category Percentile Badges**
**Location:** Analytics Screen (Enhanced Category cards)
**Shows:** P25/P75 ranges + velocity indicator
**Value:** Category-level spending patterns and trends
**Data Source:** `EnhancedCategoryAnalytics.percentile25/75, velocity`
**UI Component:** `CategoryPercentileBadge()` in `StatisticalVisualizations.kt`

**Velocity Indicators:**
- 🚀 Accelerating (velocity > 1.2) - Spending faster than typical
- 🐢 Slowing (velocity < 0.8) - Spending slower than typical  
- ➡️ Steady (0.8-1.2) - Consistent spending pace

**Example:**
```
Food: €450 (12 transactions)
  P25: €25 · P75: €55 · Steady ➡️
```

#### **6.4 No-Spend Streak Widget** (Gamification)
**Location:** Dashboard (Home Screen)
**Shows:** Current streak, personal best, progress bar, motivational messages
**Value:** Encourages mindful spending through gamification
**Data Source:** `ComputeDashboardWidgetsUseCase.calculateStreakData()`
**UI Component:** `NoSpendStreakWidget()` in `NoSpendStreakWidget.kt`

**Features:**
- 🔥 Fire emoji multiplies with streak length (up to 5)
- Progress bar toward personal best
- "NEW RECORD! 🏆" celebration when beaten
- Monthly dry days counter
- Motivational messages (rotating)
- Percentage of month saved calculation

**Calculation Logic:**
```kotlin
calculateStreakData(calendar, expenses, monthStart): Triple<Int, Int, Int>
- Current streak: Days from today backward until expense found
- Personal best: Maximum gap between any two expense dates
- Days without spending this month: Total days - expense days
```

#### **6.5 Enhanced Merchant Intelligence**
**Location:** Analytics Screen (Merchant cards)
**Shows:** Loyalty score, streaks, consistency, price trends, predictions
**Value:** Rich insights into merchant relationships
**Data Source:** `EnhancedMerchantAnalytics`
**UI Component:** `RichMerchantCard()` in `StatisticalVisualizations.kt`

**Card Features:**
1. **Loyalty Bar (0-100)**
   - Color-coded: Green (80+), Yellow (50-79), Primary (<50)
   - 5-star rating display
   - Numeric score

2. **Streak Counter**
   - 🔥 Consecutive months visited
   - "8 months" streak badge

3. **Consistency Rating**
   - 🟢 Highly Consistent / 🟡 Consistent / 🔴 Variable
   - Based on coefficient of variation

4. **Price Trends**
   - 📈📉 Directional indicators
   - Percentage change vs last quarter
   - "+5% vs last quarter" labels

5. **Predicted Next Visit**
   - 📅 Calendar icon
   - "Soon" or "X days"
   - Based on average days between visits

**Files Changed:**
- `ui/components/analytics/StatisticalVisualizations.kt` - New components
- `ui/components/analytics/NoSpendStreakWidget.kt` - Gamification widget
- `ui/screens/analytics/AnalyticsScreen.kt` - Integration
- `ui/screens/home/HomeScreen.kt` - Dashboard integration
- `ui/screens/home/HomeViewModel.kt` - Widget ID mapping
- `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` - Streak calculation

**Total Lines Added:** ~650 lines across 6 files

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
| Analytics slow | InsightsEngine, AdvancedAnalyticsEngine, AnalyticsRepository |
| Totals not showing | TotalsAggregationEngine, ExpenseDao, DashboardRepository |
| Weekly data wrong | getMonthRange (0-indexed), ExpenseDao.getWeeklyTotalsForPeriod |
| Category breakdown empty | loadCategoryBreakdownForCurrentPeriod(), ExpenseDao.getCategoryBreakdown |
| Drill-down not working | PeriodNavigationBar (filter chips), HomeViewModel.drillDownToPeriod() |
| Statistical data not showing | AdvancedAnalyticsEngine, AnalyticsScreen, AnalyticsViewModel |
| Percentile grid missing | StatisticalVisualizations.kt, TransactionPercentiles |
| Histogram not displayed | StatisticalVisualizations.kt, HistogramBin data |
| Category velocity hidden | EnhancedCategoryAnalytics, CategoryPercentileBadge |
| Streak widget not appearing | ComputeDashboardWidgetsUseCase, NoSpendStreakWidget |

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
| `TotalsDashboardCard.kt` | Monthly/weekly totals with drill-down (NEW Mar 2026) |
| `PeriodNavigationBar.kt` | Period navigation with filter chips (NEW Mar 2026) |
| `PeriodGridView.kt` | Period grid display with blocks (NEW Mar 2026) |
| `PeriodBlock.kt` | Individual period block (NEW Mar 2026) |
| `CategoryBreakdownSheet.kt` | Category breakdown bottom sheet (NEW Mar 2026) |

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
| `PeriodTotal.kt` | Period totals with drill-down (NEW Mar 2026) |
| `PeriodType.kt` | Period type enum (YEAR, MONTH, WEEK, DAY) (NEW Mar 2026) |
| `PeriodStatus.kt` | Period status enum (UNDER_AVERAGE, OVER_AVERAGE, CURRENT, NO_DATA) (NEW Mar 2026) |
| `CategoryBreakdown.kt` | Category spending breakdown (NEW Mar 2026) |
| `CategoryInfo.kt` | Domain model for category info (NEW Mar 2026) |
| `PeriodDrillDownState.kt` | UI state for drill-down feature (NEW Mar 2026) |

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
| 9: Dashboard | ~20 | MainActivity, DashboardRepository, HomeViewModel, ComputeDashboardWidgetsUseCase, DashboardDataProvider, TotalsDashboardCard, TotalsAggregationEngine |
| 10: Notifications | ~3 | AndroidNotificationService, NotificationService |
| 11: Debug | ~8 | DebugScreen, DebugViewModel, ServiceDiagnostics |
| 12: DI | ~6 | AppModule, DatabaseModule, DaoModule, ServiceModule |
| 13: Utilities | ~20 | AmountUtils, DateFormatterUtils, TimeProvider |
| 14: Use Cases | ~6 | ProcessReceiptUseCase, CategorizeExpenseUseCase, etc. |
| 15: Performance | ~2 | ImageCache, ReceiptOcrService optimizations |
| 16: Configuration | ~1 | AppConfig |
| 17: Location | ~15 | CompositeGeocodingService, NominatimGeocodingService, LocationResolver, SpendingMapScreen |
| 18: AI Follow-Through (Phase 4B) | ~25 | DashboardFollowThroughEngine, RecommendationRepository, RecommendationStateManager, RecommendationCard |
| 19: Totals Dashboard | ~10 | TotalsDashboardCard, PeriodGridView, PeriodBlock, TotalsAggregationEngine, PeriodNavigationBar, CategoryBreakdownSheet |
| 20: Groups (Shared Expense) | ~12 | SharedExpenseManager, SettlementCalculator, SharedExpenseGroupsScreen, GroupRepository (extracted in Phase 2) |

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
- **CODEBASE_SEGMENTS.md → Segment 27**: Phase 5 features and issues
- **REMEDIATION.md**: Complete code review with 83 issues and fixes

---

## 9. Comprehensive Code Review Findings (Phase 5)

### Overview
A complete architectural review of all 28 features was conducted in March 2026.

**Total Issues Found:** 83  
**Critical:** 4 | **High:** 10 | **Medium:** 15 | **Low:** 8  
**Full Report:** See `REMEDIATION.md`

### Critical Issues (Immediate Action Required)

#### 1. Security: API Key Exposure
**Location:** `build.gradle.kts`  
**Issue:** Keys stored in BuildConfig can be extracted from APK  
**Fix:** Move to Android Keystore + EncryptedSharedPreferences  
**Risk:** All external API keys exposed

#### 2. Data Consistency: Race Conditions
**Location:** `WarrantyTrackerRepository`, `SharedExpenseManager`  
**Issue:** Non-atomic transactions across multiple suspend calls  
**Fix:** Add `@Transaction` annotation, use database transactions

#### 3. Memory Leak: Bitmap Processing
**Location:** `ReceiptOcrService`  
**Issue:** Concurrent bitmap access without synchronization  
**Fix:** Add `Mutex` for bitmap serialization

#### 4. Security: SQL Injection Risk
**Location:** `AccountingExporters`  
**Issue:** String interpolation in CSV generation  
**Fix:** Use Apache Commons CSV library

### High Priority Issues

#### 5. Architecture Violation
**Issue:** ViewModels directly call Repositories, bypassing UseCases  
**Fix:** Refactor to proper Clean Architecture: VM → UseCase → Repository

#### 6. Performance: Blocking Operations
**Issue:** Database queries may block UI thread  
**Fix:** Ensure all database calls are properly suspended or use Flow

#### 7. Logic Error: Floating Point Precision
**Issue:** `Double` for monetary calculations causes rounding errors  
**Fix:** Use `BigDecimal` for all financial math

#### 8. Resource Leak: SpeechRecognizer
**Issue:** SpeechRecognizer never destroyed in NLP Search  
**Fix:** Implement proper lifecycle cleanup in ViewModel.onCleared()

#### 9. Performance: Inefficient Queries
**Issue:** Multiple sequential queries in loops  
**Fix:** Batch with `IN` clause, add missing indices

#### 10-14. Additional High Issues
- Null safety in OAuth flow
- Integer overflow in notification IDs
- Manual collection creation (no caching)
- Hardcoded tax rates

### Architecture Recommendations

#### Proper Clean Architecture
```
✅ Correct Flow:
UI Layer
  ↓ calls
ViewModel (State Management)
  ↓ calls
UseCase (Business Logic)
  ↓ calls
Repository (Data Access)
  ↓ calls
DAO (Database Queries)

❌ Anti-Patterns:
- VM → Repository (skipping UseCase)
- Repository → Other Repository
- UI → Repository (direct access)
```

#### Financial Calculation Standards
```kotlin
// ❌ DON'T: Double arithmetic
val total = price * 0.15  // Rounding errors!

// ✅ DO: BigDecimal
val total = BigDecimal(price.toString())
    .multiply(BigDecimal("0.15"))
    .setScale(2, RoundingMode.HALF_UP)
```

#### Database Transaction Boundaries
```kotlin
// ❌ DON'T: Sequential calls
suspend fun transfer() {
    fromAccount.deduct(amount)  // Can fail independently
    toAccount.add(amount)       // Data inconsistency!
}

// ✅ DO: Atomic transaction
@Transaction
suspend fun transferAtomic() {
    fromAccount.deduct(amount)
    toAccount.add(amount)
}
```

#### Coroutine Best Practices
```kotlin
// ❌ DON'T: Blocking operations
fun loadData() = runBlocking {  // Blocks thread!
    repository.getData()
}

// ✅ DO: Proper suspension
suspend fun loadData() {  // Caller decides dispatcher
    repository.getData()
}

// ✅ DO: Flow for reactive
fun loadData(): Flow<Data> = flow {
    emit(repository.getData())
}.flowOn(Dispatchers.IO)
```

### Performance Optimization Guidelines

#### Database Optimization
1. **Add Indices:** For frequently queried columns
2. **Batch Queries:** Use `IN` clause instead of N queries
3. **Pagination:** Use `LIMIT`/`OFFSET` for large datasets
4. **Lazy Loading:** Defer loading until needed
5. **Caching:** Cache frequently accessed data in memory

#### Memory Management
1. **Bitmap Lifecycle:** Always recycle, use reference counting
2. **Flow Collection:** Cancel properly in onCleared()
3. **Resource Cleanup:** Use `use` blocks or Closeable pattern
4. **Avoid Memory Leaks:** Don't hold references in static fields

#### UI Optimization
1. **Compose:** Use `remember`, `derivedStateOf`, `key`
2. **Recomposition:** Avoid unnecessary recompositions
3. **Lazy Lists:** Use LazyColumn/LazyRow for large lists
4. **Image Loading:** Cache and resize images appropriately

### Security Best Practices

1. **Never commit API keys** to version control
2. **Use Android Keystore** for sensitive data
3. **Encrypt SharedPreferences** for stored tokens
4. **Validate all inputs** to prevent injection
5. **Use HTTPS** for all network calls
6. **Implement certificate pinning** for critical APIs
7. **Regular security audits** with tools like MobSF

### Testing Strategy

#### Unit Tests
- Every UseCase must have tests
- Every Engine needs logic tests
- Repository tests with in-memory database

#### Integration Tests
- Database migrations
- API integrations (mocked)
- Feature workflows end-to-end

#### Security Tests
- APK decompilation check
- SQL injection testing
- Certificate validation

#### Performance Tests
- Memory profiling with LeakCanary
- Query performance benchmarks
- UI rendering performance

### Documentation Requirements

Every feature must have:
- [ ] Architecture diagram
- [ ] Data flow documentation
- [ ] API documentation (if applicable)
- [ ] Known issues and limitations
- [ ] Testing guidelines

### Code Quality Checklist

Before merging any feature:
- [ ] No security vulnerabilities
- [ ] All tests passing
- [ ] Architecture patterns followed
- [ ] Proper error handling
- [ ] Resource cleanup implemented
- [ ] Documentation updated
- [ ] Performance benchmarks met
- [ ] Code review approved

---

## 10. Remediation Roadmap

### Sprint 1: Security & Critical (Weeks 1-2)
- [ ] Fix API key storage (Keystore)
- [ ] Fix race conditions (@Transaction)
- [ ] Fix bitmap memory leaks
- [ ] Fix SQL injection (Apache CSV)

### Sprint 2: Architecture (Weeks 3-4)
- [ ] Refactor to UseCase pattern
- [ ] Add transaction boundaries
- [ ] Implement BigDecimal for money
- [ ] Fix resource cleanup

### Sprint 3: Performance (Weeks 5-6)
- [ ] Optimize database queries
- [ ] Add missing indices
- [ ] Implement caching layer
- [ ] Fix coroutine cancellation

### Sprint 4: Quality (Weeks 7-8)
- [ ] Centralize duplicate code
- [ ] Standardize error handling
- [ ] Move config to database
- [ ] Add comprehensive docs

**Full Details:** See `REMEDIATION.md`

---

## 11. Quick Reference: Common Issues & Fixes

### Issue: Null Pointer Exception
**Likely Cause:** Missing null check on query result  
**Fix:** Use Elvis operator, check `isActive` in coroutines

### Issue: UI Not Updating
**Likely Cause:** Not using Flow/StateFlow properly  
**Fix:** Use `collectAsState()`, ensure Flow emissions

### Issue: Database Locked
**Likely Cause:** Long-running transaction or query  
**Fix:** Add `@Transaction`, optimize query, use WAL mode

### Issue: Memory Leak
**Likely Cause:** Resource not cleaned up  
**Fix:** Use `onCleared()`, `use` blocks, proper lifecycle

### Issue: Slow Queries
**Likely Cause:** Missing index or N+1 problem  
**Fix:** Add index, use JOIN, batch with IN clause

### Issue: Race Condition
**Likely Cause:** Non-atomic multi-table operation  
**Fix:** Add `@Transaction`, use Mutex, synchronize

---

**Last Updated:** March 31, 2026  
**Total Features:** 28 | **Total Issues:** 83 | **Next Review:** Q2 2026
