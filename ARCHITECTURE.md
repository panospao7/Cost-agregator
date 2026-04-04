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
1. Architecture Overview
2. Layer Structure
3. Data Flow
4. Key Components
5. Dependency Injection
6. Database Schema
7. Recent Changes & Fixes
8. Quick Reference

## Current Project Metrics
- Database version: v68
- 560+ Kotlin files
- 77 screen files (32 navigable routes)
- 6 bottom tabs: Home, Activity/Transactions, Review, Plan/Budget, Analytics, Map
- NavigationDestination pattern in UI, deep links for all tabs
- BackHandler integration and rememberSaveable state across screens

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
└──────────────────────────────────────────────────────────────────────┘
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
├── screens/                     # Screen directories (6 bottom tabs)
│   ├── home/                    # Home (Dashboard)
│   ├── activity/                # Activity / Transactions
│   ├── review/                  # Review
│   ├── plan/                    # Plan / Budget
│   ├── analytics/               # Analytics
│   ├── map/                     # Map
│   └── ...
└── util/
    ├── HapticFeedback.kt       # Haptic feedback utilities
    └── ClipboardAmountParser.kt # Clipboard parsing
```

### Domain Layer (`domain/`)
```
domain/
├── logic/                       # Core business engines
├── forecasting/                 # Monte Carlo Spending Simulator (NEW)
├── analytics/                   # Analytics engines
├── budget/                      # Budget management
├── categorization/              # Merchant categorization
├── intelligence/
├── parser/                      # Notification parsing
├── receipt/                     # Receipt OCR
├── service/                     # Service interfaces
├── usecase/                     # Use Cases (Clean Architecture)
├── model/                       # Domain models
├── config/                      # Configuration
├── location/                    # Location enrichment (NEW)
├── performance/                 # Performance utilities
├── debug/
└── util/                        # Utilities
```

### Data Layer (`data/`)
```
data/
├── repository/                   # Data access (single source of truth)
├── location/                    # Geocoding services (NEW)
├── database/
│   ├── AppDatabase.kt          # Room database (v68)
│   ├── entity/                  # Room entities (EXPANDED)
│   │   ├── Expense.kt
│   │   ├── Budget.kt
│   │   ├── Category.kt
│   │   ├── RawNotification.kt
│   │   ├── PendingReview.kt
│   │   ├── MerchantLocation.kt  # (NEW)
│   │   ├── MerchantLocationCorrection.kt  # (NEW)
│   │   └── ...
│   ├── dao/                     # Room DAOs
│   │   ├── ExpenseDao.kt
│   │   ├── BudgetDao.kt
│   │   ├── MerchantLocationDao.kt  # (NEW)
│   │   └── ...
│   ├── model/                   # Database models
│   └── converter/               # Type converters
├── service/
│   └── AndroidNotificationService.kt # Android notifications
└── provider/
    └── MerchantCategoryProvider.kt # Pre-defined categories
```

---

## Data Flow

### Notification → Expense Flow
```
NotificationCaptureService (Android)
       ↓
AppParserRegistry → Specific Parser (GreekBank, Revolut, etc.)
       ↓
ConfidenceRouter → Determine confidence level
       ↓
CategorizationEngine → Assign category
       ↓
NotificationRepository → Save to DB
       ↓
ReviewQueueRepository → Add to review queue (if needed)
       ↓
ReviewScreen (UI) → User approves/rejects
       ↓
ExpenseRepository → Save as final expense
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
| Database | `data/database/AppDatabase.kt` | Room DB v68 |

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
| Greeklish Normalizer | `domain/categorization/GreeklishNormalizer.kt` | Normalize Greek to Latin |
| Merchant Canonicalizer | `domain/categorization/MerchantCanonicalizer.kt` | Canonical merchant names |
| Semantic Keyword Matcher | `domain/categorization/SemanticKeywordMatcher.kt` | Keyword-based matching |
| Contextual Inference | `domain/categorization/ContextualInferenceEngine.kt` | Inference based on amount/time |
| Category Keywords | `domain/categorization/CategoryKeywords.kt` | Pre-defined keyword mappings |

### Monte Carlo Spending Simulator (Mar 2026)
| Component | File | Purpose |
|-----------|------|---------|
| Simulator Engine | `domain/forecasting/MonteCarloSpendingSimulator.kt` | 1000-iteration Monte Carlo simulation |
| Result Model | `domain/forecasting/MonteCarloResult.kt` | Percentiles (P10/P25/P50/P75/P90) |
| Distribution Builder | `domain/forecasting/HistoricalSpendingDistribution.kt` | Weekly aggregation + log-normal fitting |
| Quality Assessor | `domain/forecasting/DataQualityAssessor.kt` | Confidence scoring |
| UI Card | `ui/components/analytics/MonteCarloForecastCard.kt` | Dashboard widget display |

### Location Enrichment System (Mar 2026) - ALL 5 FEATURES IMPLEMENTED
| Component | File | Purpose |
|-----------|------|---------|
| Composite Geocoder | `data/location/CompositeGeocodingService.kt` | Multi-provider fallback chain |
| Nominatim | `data/location/NominatimGeocodingService.kt` | OpenStreetMap |
| Geoapify | `data/location/GeoapifyGeocodingService.kt` | Geoapify API |
| Google Places | `data/location/GooglePlacesGeocodingService.kt` | Google Places API |
| Photon | `data/location/PhotonGeocodingService.kt` | Photon API |
| Location Resolver | `domain/location/LocationResolver.kt` | Domain coordination |
| Location Models | `data/location/LocationModels.kt` | Location domain models |
| Map Screen | `ui/screens/map/SpendingMapScreen.kt` | Map visualization |

### Advanced Analytics Features (Mar 2026)
... (same high-level mapping as before) ...

### Parsers (Notification Processing)
| Parser | File | Handles |
|--------|------|---------|
| Greek Bank | `domain/parser/parsers/GreekBankParser.kt` | NBG, Alpha, Eurobank, Piraeus |
| Revolut | `domain/parser/parsers/RevolutParser.kt` | Revolut app |
| Google Wallet | `domain/parser/parsers/GoogleWalletParser.kt` | Google Pay |
| SMS | `domain/parser/parsers/SmsParser.kt` | SMS bank notifications |
| Generic | `domain/parser/parsers/GenericTransactionParser.kt` | Fallback parser |

---

## Dependency Injection

### Hilt Modules
```
di/
├── AppModule.kt        # Legacy (backwards compatibility)
├── DatabaseModule.kt   # Room database (NEW)
├── DaoModule.kt        # All DAOs (NEW)
├── ServiceModule.kt    # Android services (NEW)
├── TimeModule.kt       # TimeProvider binding
├── DispatchersModule.kt  # Coroutine dispatchers
├── AiModule.kt           # AI-related bindings (NEW)
├── SecurityModule.kt     # Secure storage bindings (NEW)
├── GroupsModule.kt       # Shared expense groups bindings (NEW)
├── NetworkModule.kt      # Network providers (NEW)
├── NetworkQualifiers.kt  # Qualifiers for network bindings (NEW)
```

### Key Bindings
```kotlin
// DatabaseModule
@Singleton @Provides AppDatabase

// DaoModule  
@Singleton @Provides ExpenseDao
@Singleton @Provides BudgetDao
// ... all other DAOs

// AiModule
// @Provides AiService, AiClient etc.

// SecurityModule
// @Provides SecureKeyStorage binding
```

---

## Database Schema

### Version: v68 (Current)

### Key Entities
```
expenses
categories
budgets
raw_notifications
pending_reviews
merchant_categories (NEW v26)
merchant_locations (NEW)
merchant_location_corrections (NEW)
group_expenses (NEW)
expense_groups (NEW)
group_members (NEW)
warranties (NEW)
return_windows (NEW)
subscriptions (NEW)
subscription_usage (NEW)
subscription_price_history (NEW)
investments (NEW)
investment_values (NEW)
mileage_logs (NEW)
scanned_receipts (NEW)
receipt_item_categorizations (NEW)
recommendations (NEW)
savings_goals (NEW)
planned_expenses (NEW)
manual_recurring_expenses (NEW)
blocked_packages (NEW)
source_stats (NEW)
merchant_canonicals (NEW)
merchant_aliases (NEW)
user_corrections (NEW)
dashboard_widget_configs (NEW)
ai_settings (NEW)
ai_artifacts (NEW)
notification_processing_log (NEW)
```

### Key Indices
```
-- Updated indices for v68 schema
```

---

## Recent Changes & Fixes

### Phase 1: Critical & High Priority Fixes (18 issues)
- CRIT-05: API keys removed from BuildConfig → SecureKeyStorage
- CRIT-07: NotificationCaptureService exported properly secured
- HIGH-01: SimpleDateFormat → DateTimeFormatter (thread-safe)
- HIGH-04: PDF resource leaks fixed
- HIGH-05: CSV formula injection prevention
- HIGH-13/14: API key logging removed, merchant names anonymized
- HIGH-02: Merchant std-dev calculation fixed
- CRIT-02: Time-based Flow queries made reactive
- CRIT-06/08/09: Navigation fixes (tab alignment, deep links, config change)
- HIGH-08/09/10/18/19: UX fixes (back behavior, scrollable menu, empty CTA, budget errors, retry)

### Phase 2: Architecture Fixes (7 issues)
- CRIT-11: MainActivity import removed from data layer
- CRIT-10: Domain→UI imports extracted to mappers
- HIGH-03: UTC/local-time SQL fixed with 'localtime' modifier
- HIGH-06: Duplicate GroupTransactionCoordinator consolidated
- HIGH-17: Mixed navigation architecture unified
- CRIT-03: FK Contract fixed (DB v51→52 migration)
- CRIT-04: Groups repository + VM refactored

### Phase 3: Review Fixes + Polish (10 issues)
- Notification service exported, deep link intent filters
- rememberSaveable fixes, DomainExpenseSummary DTO
- Bitmap cleanup exception-safe, GroupExpense.expenseId nullable
- Orphaned screens connected, Spanish i18n, accessibility 48dp
- Documentation updated

### Phase 4: Time Period Fixes (22 issues)
- All ViewModels use rolling windows via TimePeriodUtils
- All engines use half-open [start, end) intervals
- All DAOs use >= start AND < end
- Daily average fixed: total / periodDays (not days with spending)
- Cross-engine consistency verified

### Phase 5: Analytics Verification (48 tests)
- Semantic Contract Map: 43 components across 12 metric groups
- Golden Master Test: 22 tests covering Groups 1-9
- Specialized Tests: 18 tests covering Groups 10-12
- Cross-Group Integration: 8 tests for end-to-end flows

### Phase 6: UI/UX Consistency (22 fixes)
- 13 hardcoded colors → SemanticColors
- 8 hardcoded strings → strings.xml
- Currency display → CurrencyFormatter
- Typography → MaterialTheme styles

### Phase 7: State Management (7 fixes)
- PriceProtectionViewModel flow collector leak
- DebugScreen detached coroutine scope
- Repository fire-and-forget scopes
- HomeScreen stale remember
- VisualSplitEditorScreen config state
- Period controls rotation survival
- ReceiptOcrService recognizer lifecycle

### Phase 8: Error Handling (16 fixes)
- Destructive migration fallback removed
- Backup safety enforced
- AI services typed errors
- Geocoding typed errors
- Export error UI
- Map crash prevention
- Notification pipeline results
- Receipt analysis errors
- Export count errors
- Budget use case implemented
- Currency errors
- Camera denial UX
- Review reject errors

### Phase 9: Navigation (6 fixes)
- System back handler added
- BriefingKey deep link working
- Navigation state saveable
- Deep-link origin preservation
- Unknown host handling
- Manifest declarations aligned

### Phase 10: Performance (11 fixes)
- WarrantyDao N+1 query → JOIN
- Geocoding double throttling
- Analytics recomputation
- Unbounded queries → paged
- Missing composite indices (3)
- OCR mutex narrowed
- HTTP clients shared + cached
- Chart recomposition optimized
- Recent transactions capped

### Phase 11: Accessibility (16 fixes)
- Chart semantics (3 charts)
- Touch targets (3 components)
- BudgetBlockPartyCard semantics
- Heading semantics
- FAB size increased
- Dynamic contentDescriptions
- Redundant speech removed
- Text truncation improved
- Color contrast improved
- Overlapping semantics fixed
- Badge text improved
- Legend labels expanded

### Phase 12: New Components to Document
- Domain Models: `DomainBlockStatus`, `DomainDayBudgetStatus`, `DomainTransactionFilter`, `DomainExpenseSummary`, `AiServiceError`, `AiServiceResult`, `GeocodingError`, `GeocodingLookupResult`, `GeocodingBatchResult`, `NearbyPoiResult`, `ProcessingResult` (NotificationProcessingPipeline)
- UI Mappers: `DashboardWidgetUiMapper`, `TransactionFilterUiMapper`
- Repositories: `GroupsRepository`, `GroupsRepositoryImpl`
- Use Cases: `DeleteGroupMemberUseCase`, `DeleteGroupUseCase`, `AddGroupExpenseUseCase`

### Phase 13: Updated UI Layer Structure
- New screen directories:
  - `groups/` - Shared expense groups
  - `warranty/` - Warranty tracker
  - `carbon/` - Carbon footprint
  - `lifestyle/` - Lifestyle inflation
  - `challenge/` - Spending challenges
  - `negotiation/` - Bill negotiation
  - `price/` - Price protection
  - `naturallanguage/` - Natural language search
  - `split/` - Visual split editor
  - `bank/` - Bank connections
  - `subscription/` - Subscription management
  - `savings/` - Savings goals
  - `investment/` - Investment portfolio
  - `reminder/` - Bill reminders
  - `export/` - Export options
  - `currency/` - Currency management
  - `tax/` - Tax configuration
  - `receiptmatching/` - Receipt matching
  - `assistant/` - AI assistant
  - `aisettings/` - AI settings

### Phase 14: Updated Domain Layer Structure
- New domain directories:
  - `groups/` - Shared expense logic
  - `warranty/` - Warranty tracking
  - `carbon/` - Carbon footprint
  - `lifestyle/` - Lifestyle inflation
  - `challenge/` - Spending challenges
  - `negotiation/` - Bill negotiation
  - `price/` - Price protection
  - `naturallanguage/` - Natural language search
  - `split/` - Split transactions
  - `subscription/` - Subscription management
  - `savings/` - Savings goals
  - `investment/` - Investment tracking
  - `reminder/` - Bill reminders
  - `export/` - Export functionality
  - `currency/` - Currency management
  - `tax/` - Tax estimation
  - `receiptmatching/` - Receipt matching
  - `ai/` - AI services and use cases
  - `forecasting/` - Monte Carlo simulation
  - `model/dashboard/` - Domain models for dashboard
  - `model/navigation/` - Domain models for navigation

### Phase 15: Updated Data Layer Structure
- New data directories:
  - `location/` - Geocoding services (5 providers)
  - `ai/provider/` - AI service providers
  - `security/` - SecureKeyStorage

### Phase 16: Updated Testing Section
- Update test locations to reflect new test files:
  - `verification/` - GoldenMasterVerificationTest, CrossSourceVerificationTest, etc.
- `domain/analytics/` - Deep tests for all analytics engines
- `domain/budget/` - Budget engine tests
- `domain/cashflow/` - Cash flow tests
- `domain/savings/` - Savings engine tests
- `domain/forecasting/` - Monte Carlo simulator tests
- `integration/` - Integration tests
- `e2e/` - End-to-end flow tests

### Phase 17: Updated Quick Reference
- Update all "Add New..." sections with current patterns.

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
| 0 | Home (Dashboard) | `ui/screens/home/HomeScreen.kt` |
| 1 | Activity / Transactions | `ui/screens/activity/TransactionsScreen.kt` |
| 2 | Review | `ui/screens/review/ReviewScreen.kt` |
| 3 | Plan / Budget | `ui/screens/plan/BudgetScreen.kt` |
| 4 | Analytics | `ui/screens/analytics/AnalyticsScreen.kt` |
| 5 | Map | `ui/screens/map/SpendingMapScreen.kt` |

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
| `FinancialWeatherCard.kt` | Forecast display (explicit states) |
| `SpendingTrendChart.kt` | Trend visualization |
| `SpendingPaceGauge.kt` | Spending pace gauge |
| `ChartMarker.kt` | Chart markers |
- ... (additional components documented as introduced in the codebase)

### Domain Models (`domain/model/`)
- See expanded domain model list in this document as features were added.

### All Repositories (`data/repository/`)
- See expanded repository list in codebase for Groups, AI, and analytics.

### Android Services & Receivers
- Notification Capture, Service Restart, Boot

### Database Entities (Room)
- See expanded entity list in the updated DB schema section.

---

## Segment Mapping

- 1: Financial Forecast → ~40 files, engines and dashboards
- 2: Budget → ~8 files
- 3: Notification Parsing → ~20 files
- 4: OCR/Receipt → ~8 files
- 5: Categorization → ~15 files
- 6: Recurring → ~5 files
- 7: Analytics → ~20 files
- 8: Core Expense → ~20 files
- 9: Dashboard → ~20 files
- 10: Notifications → ~3 files
- 11: Debug → ~8 files
- 12: DI → ~6 files
- 13: Utilities → ~20 files
- 14: Use Cases → ~6 files
- 15: Performance → ~2 files
- 16: Configuration → ~1 file
- 17: Location → ~15 files
- 18: AI Follow-Through (Phase 4B) → ~25 files

---

## Updated Quick Reference

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
- ... remaining checks as per project

---

## Testing

Refer to the testing sections above for updated locations and test plans.

---

## Feature Wave Addendum (F1–F15)

This section documents the latest 15-feature integration wave and its cross-layer architecture.

### End-to-End Data Flow (Feature Wave)

```text
Notifications / Receipts / Manual Entries / Dashboard Triggers
                │
                ▼
     Domain Engines + Use Cases (F1..F15)
                │
                ▼
      Repositories + Services + Orchestrators
                │
                ▼
     Room Entities / DAOs (AppDatabase v68)
                │
                ▼
       UI Cards / Screens / Assistant Sheet
```

### F1–F15 Architecture Mapping

| Feature | Primary Flow | Key Domain / Use Case | Key Data + DB |
|---|---|---|---|
| **F1 Receipt → Warranty Pipeline** | Scanned receipt → warranty extraction → persisted warranty | `AutoCreateWarrantyFromReceiptUseCase`, `WarrantyTextExtractor` | `WarrantyDao`, `warranties` table |
| **F2 Notification → Subscription Detection** | Transaction stream → recurring pattern detection → candidate surfaced | `NotificationSubscriptionDetector`, `SubscriptionManagerEngine` | `SubscriptionCandidateDao`, `subscription_candidates` |
| **F3 Monte Carlo → Budget Linking** | Forecast simulation → budget impact insights | `GetMonteCarloBudgetImpactUseCase`, `MonteCarloSpendingSimulator` | Budget/expense DAOs + forecast models |
| **F4 Today's Money Radar Widget** | Home aggregation → radar widget model → dashboard render | `ComputeDashboardWidgetsUseCase` | `MoneyRadarWidget`, dashboard config |
| **F5 Financial Health Score 2.0** | Health computation → trend snapshot persistence | `FinancialHealthScoreV2` | `HealthScoreHistoryDao`, `health_score_history` |
| **F6 Smart Savings Sweeps** | Month-end underspend → safe sweep plan generation | `MonthlySavingsSweepUseCase` | `SavingsSweepPlanDao`, `savings_sweep_plan` |
| **F7 Anomaly → Real-Time Alerts** | Analytics anomaly detection → alert orchestration + cooldown | `AnomalyDetector`, `AnomalyAlertOrchestrator` | `AnomalyAlertDao`, `anomaly_alerts` |
| **F8 Financial Stress Forecast (30/60/90d)** | Forward stress simulation → snapshot + risk levels | `FinancialStressForecastEngine` | `StressForecastSnapshotDao`, `stress_forecast_snapshots` |
| **F9 AI Budget Autopilot** | Trend analysis → recommendation → application event | `BudgetAutopilotEngine` | `budget_adjustment_recommendations`, `budget_adjustment_events` |
| **F10 Contextual Empty States** | No-data contexts → contextual CTA rendering | Empty-state strategy components | `EnhancedEmptyState`, `EmptyStateAction` |
| **F11 Shared Expenses → Budget Offset** | Shared spend + reimbursements → effective budget pressure | `SharedExpenseBudgetOffsetEngine` | `group_expenses` reimbursement columns |
| **F12 Lifestyle Inflation → Savings Goals** | Lifestyle drift signal → prompt + savings guidance | `LifestyleSavingsPromptUseCase`, `LifestyleInflationDetector` | `prompt_states` |
| **F13 Spending Personality Profile** | Spending behavior analysis → profile classification | `SpendingPersonalityClassifier` | `SpendingPersonalityProfileDao`, `spending_personality_profiles` |
| **F14 Email Receipt Ingestion** | Email parser → receipt linkage → normalized source tracking | `EmailReceiptIngestionService`, `EmailReceiptParser` | `EmailReceiptDao`, `email_receipt_sources` |
| **F15 Conversational Finance Assistant** | Assistant query → AI context/results → UI card/sheet | Assistant orchestration in `AssistantViewModel` | `ai_chat_sessions`, `ai_chat_messages`, AI artifacts |

### DI Module Updates (Feature Wave)

- `DatabaseModule`: migration chain extended to **MIGRATION_67_68**.
- `DaoModule`: feature DAOs bound for anomaly/health/sweep/subscription/stress/personality/email/budget adjustment paths.
- `SubscriptionModule`: subscription detection + management wiring.
- `EmptyStateModule`: contextual empty-state behavior bindings.
- Existing `AiModule`, `SecurityModule`, `NetworkModule`, `GroupsModule` reused by F1/F9/F11/F14/F15 integration points.

### Migration History (Recent)

| Migration | Feature / Purpose |
|---|---|
| 53→54 | F1 receipt→warranty auto-detection fields |
| 54→55 | F11 shared expense reimbursement/budget-offset fields |
| 55→56 | F12 prompt state persistence |
| 56→57 | F5 health score history table |
| 57→58 | F6 savings sweep planning table |
| 58→59 | F2 subscription candidate detection table |
| 59→60 | Health score schema replay safety |
| 60→61 | F9 budget autopilot recommendation/event tables |
| 61→62 | F8 stress forecast snapshot table |
| 62→63 | F13 spending personality profile table |
| 63→64 | Stress snapshot replay safety |
| 64→65 | F14 email receipt source table |
| 65→66 | Email-ingested receipt nullable image path |
| 66→67 | Warranty deduplication hardening |
| **67→68** | **Migration repair pass: rebuild anomaly/feature-wave tables to canonical schemas, fix malformed zero-column tables, preserve data when structure is valid** |

This document reflects the current architecture state following migrations up to v68, including the full F1–F15 feature wave and migration hardening for existing devices.
