# ExpenseTracker Architecture Guide

## How to Use This Document

### For Quick Understanding
→ Read **Architecture Overview** and **Layer Structure** sections

### For Adding Features
1. Read **Key Components** to find similar patterns
2. Check **Quick Reference** → "Add New Screen/Parser/Entity"

### For Bug Analysis (RECOMMENDED WORKFLOW)

1. Start with **CODEBASE_SEGMENTS.md** to find the owning segment.
2. Use **CODEBASE_INVENTORY.md** for the current route / subsystem map.
3. Trace the flow here when you need cross-layer context.
4. Use the quick reference tables for likely failure points.

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
- Database version: v92
- 560+ Kotlin files
- Destination-driven navigation via `NavigationDestination`
- 6 shell destinations in the app chrome; Assistant is an overlay/entry surface, not a bottom tab
- Deep links are handled in `ui/MainActivity.kt` (`handleIntent` / `onNewIntent`); saved navigation state stays in `NavigationController`
- Startup/background pipeline: `MainApplication` → `AppStartupDelegate` → `AppStartupCoordinator` → `AppBackgroundLifecycleObserver`
- WorkManager startup jobs include `DailyBriefingWorker`, `LocationBackfillWorker`, `MerchantKeyBackfillWorker`, and `WarrantyExpirationWorker`
- AI, location, shared-expense, and split flows are first-class subsystems

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
├── MainActivity.kt              # App entry, navigation, deep links
├── MainViewModel.kt             # App-wide state
├── navigation/                  # NavigationDestination + controller
├── theme/                       # Compose theming
│   └── Theme.kt                 # Material 3 colors/typography
├── components/                  # Reusable composables
│   ├── BentoCard.kt            # Dashboard card layout
│   ├── FinancialWeatherCard.kt  # Forecast display
│   ├── BudgetBlockPartyCard.kt # Budget visualization
│   └── ...
├── screens/                     # Shell screens + feature surfaces
│   ├── home/                    # Home / dashboard
│   ├── transactions/            # Activity / transaction flow
│   ├── review/                  # Review queue
│   ├── budget/                  # Budget + budget detail
│   ├── analytics/               # Analytics views
│   ├── map/                     # Spending map / location views
│   ├── cashflow/                # Cash flow calendar
│   ├── bank/                    # Bank connections
│   ├── investment/              # Investment portfolio
│   ├── currency/                # Currency management
│   ├── tax/                     # Tax configuration
│   └── ...
└── util/
    ├── HapticFeedback.kt       # Haptic feedback utilities
    └── ClipboardAmountParser.kt # Clipboard parsing
```

### Domain Layer (`domain/`)
```
domain/
├── ai/                          # AI capabilities, policies, models, use cases
├── analytics/                   # Insights, totals, advanced analytics
├── budget/                      # Budget management
├── categorization/               # Merchant categorization pipeline
├── forecasting/                 # Monte Carlo + stress forecast engines
├── groups/                      # Shared expense and settlement flows
├── location/                    # Location enrichment and POI lookup
├── parser/                      # Notification parsing
├── receipt/                     # OCR and receipt processing
├── split/                       # Split-template and expense splitting logic
├── service/                     # Domain service interfaces
├── usecase/                     # Use cases / orchestration
├── model/                       # Shared domain models
├── subscription/                # Subscription detection / management
├── tax/                         # Tax configuration and estimation
├── export/                      # Export flows
├── performance/                 # Performance helpers
├── debug/                       # Debug-only diagnostics
└── util/                        # Shared utilities
```

### Data Layer (`data/`)
```
data/
├── repository/                   # Data access (single source of truth)
├── ai/provider/                 # Cloud + on-device AI providers
├── location/                    # Geocoding services
├── security/                    # Secure storage / crypto helpers
├── database/
│   ├── AppDatabase.kt          # Room database (v92)
│   ├── entity/                  # Room entities across finance, AI, groups, location, and settings
│   ├── dao/                     # Room DAOs
│   ├── model/                   # Database models
│   └── converter/               # Type converters
├── service/
│   └── AndroidNotificationService.kt # Android notifications
└── provider/
    └── MerchantCategoryProvider.kt # Pre-defined categories
```

### Startup / Background Pipeline (`startup/`)
```text
MainApplication
  └─ AppStartupDelegate
       └─ AppStartupCoordinator
            ├─ AppBackgroundLifecycleObserver
            └─ WorkManager jobs
               ├─ DailyBriefingWorker
               ├─ LocationBackfillWorker
               ├─ MerchantKeyBackfillWorker
               └─ WarrantyExpirationWorker
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

### Receipt → AI Categorization Flow
```
ReceiptScanScreen
    ↓
ReceiptOcrService → ReceiptParser
    ↓
CategorizeReceiptItemsUseCase / receipt AI providers
    ↓
ReceiptRepository → item categorization entities
    ↓
Receipt review UI / corrections
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
| Application | `MainApplication.kt` | Hilt + WorkManager configuration |
| Startup delegate | `startup/AppStartupDelegate.kt` | Hilt entry-point bootstrap |
| Startup coordinator | `startup/AppStartupCoordinator.kt` | Lifecycle observer + startup jobs |
| Main Activity | `ui/MainActivity.kt` | Navigation host + deep links |
| Database | `data/database/AppDatabase.kt` | Room DB v92 |

### Core Engines
| Engine | File | Purpose |
|--------|------|---------|
| Forecast | `domain/logic/SynthesisEngine.kt` | Month-end prediction (deterministic) |
| Monte Carlo | `domain/forecasting/MonteCarloSpendingSimulator.kt` | Probabilistic spending forecast (stochastic) |
| Budget | `domain/budget/BudgetMonitor.kt` | Budget alerts |
| Categorization | `domain/categorization/CategorizationEngine.kt` | Auto-categorization (5-layer pipeline) |
| Recurring | `domain/logic/RecurringExpenseEngine.kt` | Pattern detection |
| Insights | `domain/analytics/InsightsEngine.kt` | Spending insights coordinator |
| Advanced Analytics | `domain/analytics/AdvancedAnalyticsEngine.kt` | Higher-order analytics surface |
| Spending Pace | `domain/analytics/SpendingPaceCalculator.kt` | Pace calculation |
| Anomaly Detection | `domain/analytics/AnomalyDetector.kt` | Unusual transactions |
| Month Comparison | `domain/analytics/MonthlyComparisonCalculator.kt` | Month-vs-month comparison |
| Category Insights | `domain/analytics/CategoryInsightEngine.kt` | Category analysis |
| Merchant Insights | `domain/analytics/MerchantInsightEngine.kt` | Merchant patterns |
| Day of Week | `domain/analytics/DayOfWeekAnalyzer.kt` | Day patterns |
| Totals Aggregation | `domain/analytics/TotalsAggregationEngine.kt` | Period totals aggregation |
| Dashboard Widgets | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Dashboard widget computation |
| Dashboard Data | `domain/usecase/dashboard/DashboardDataProvider.kt` | Dashboard data provider |
| AI Follow-Through | `domain/ai/...` | Recommendation, assistant, and receipt intelligence flows |

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
| UI Card | `ui/components/MonteCarloForecastCard.kt` | Dashboard widget display |

### Location Enrichment System (Mar 2026) - ALL 5 FEATURES IMPLEMENTED
| Component | File | Purpose |
|-----------|------|---------|
| Composite Geocoder | `data/location/CompositeGeocodingService.kt` | Multi-provider fallback chain |
| Nominatim | `data/location/NominatimGeocodingService.kt` | OpenStreetMap |
| Geoapify | `data/location/GeoapifyGeocodingService.kt` | Geoapify API |
| Google Places | `data/location/GooglePlacesGeocodingService.kt` | Google Places API |
| Photon | `data/location/PhotonGeocodingService.kt` | Photon API |
| Location Resolver | `domain/location/LocationResolver.kt` | Domain coordination |
| Location Models | `domain/location/LocationModels.kt` | Location domain models |
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
| Generic | `domain/parser/GenericTransactionParser.kt` | Fallback parser |

---

## Dependency Injection

### Hilt Modules
- Core: `DatabaseModule`, `DaoModule`, `DispatchersModule`, `TimeModule`, `ServiceModule`
- AI: `AiModule`, `OcrImprovementsModule`, `NaturalLanguageModule`, `DashboardContractsModule`, `DashboardAnomalyModule`
- Location / network: `LocationResolverPortsModule`, `NetworkModule`, `NetworkQualifiers`
- Finance features: `CashFlowModule`, `SavingsModule`, `SavingsRepositoryBindingsModule`, `CurrencyModule`, `TaxModule`, `SubscriptionModule`, `ExportModule`
- Shared expense / groups: `GroupsModule`, `BackupRepositoryModule`
- Security and support: `SecurityModule`, `EmptyStateModule`, `ReceiptParsingModule`, `EmailIngestionModule`

### Key Bindings
- `AppDatabase` from `DatabaseModule`
- DAO singletons from `DaoModule`
- typed repository + engine bindings per feature module
- secure storage and API-key bindings through the security module
- location provider abstractions through the location module

---

## Database Schema

### Version: v92

The Room schema in v92 is limited to the table families actually declared in `AppDatabase.kt`:

- Core finance and capture/review: raw notifications, blocked packages, expenses, categories, merchant categories, merchant canonical/alias normalization (`MerchantCanonical`, `MerchantAlias`), pending reviews, user corrections, source stats, budgets, scanned receipts, manual recurring expenses, planned expenses, savings goals
- AI and assistant: AI artifacts, chat sessions/messages, recommendations, and receipt item categorization
- Location: merchant locations and merchant location corrections
- Groups and split: expense groups, group members, group expenses, split templates, and split item assignments
- Planning, alerts, and tracking: anomaly alerts, budget forecasts, budget adjustment recommendations/events, stress forecast snapshots, health score history, savings sweep plans, spending personality profiles, spending challenges
- Financial products and support tables: warranties, return windows, subscription price history/usage/candidates, mileage tracking, exchange rates, bank connections, investments/investment values, and email receipt sources

Use the database file and migration chain as the source of truth for the exact table list.

---

## Recent Changes & Fixes

### Current Themes
- destination-driven navigation replaced the older boolean-flag / tab-index approach
- advanced analytics and totals aggregation are now dedicated, first-class flows
- AI, location, groups, split, export, currency, tax, subscriptions, and security are represented in both domain and data layers
- startup/background work is centralized through `MainApplication` and the `startup/` pipeline
- database/schema and DI have expanded significantly; exact file lists should be read from the current codebase, not this summary
- typed errors, time standardization, accessibility, and performance refactors are now cross-cutting concerns

---

### UI/UX Fixes (47 fixes across batches A-H)

- Batch A: Navigation & Main
- C1: Deep links re-applied on config change — process only when savedInstanceState==null, clear intent after handling
- C2: Back from non-home tabs exits app — route back to Home tab before allowing exit
- C3: Budget detail navigation loses category context — added NavigationDestination.BudgetDetail(categoryId, name)
- C4: Split editor loses expense context on config change — persist expenseId/amount/currency in save token
- C5: Dashboard errors swallowed as empty state — propagate error/loading states through ProcessedDashboardUiState sealed class
- H1: activeTransactionFilter lost on config change — use rememberSaveable with custom listSaver

- Batch B: Dashboard Widgets
- H2: Forecast horizon tab state invalidates after refresh — key state to result.horizons, clamp on change
- H3: Missing legend entry for budget limit line — added third legend item with matching color
- H4: Interactive chips below 48dp touch target — added minimumInteractiveComponentSize() + heightIn(min=48dp)
- H5: Dashboard chip row overflows on small screens — changed to horizontalScroll Row
- H6: Empty donut chart silently hidden — render explicit empty state card
- H7: Enhanced empty state not scrollable — wrapped with verticalScroll + responsive alignment
- H8: Dismiss affordance too small (16dp) + nested clickables — separate IconButton with sizeIn(minWidth=48dp)

- Batch C: Transactions & Review
- H9: Clear filters doesn't fully reset state — reset both _filter and _ownershipFilter, reload data
- H10: Filter ignores tab date range on non-ALL tabs — intersect tab range with filter range, apply amount constraints
- H11: Pull-to-refresh indicator stops prematurely — drive isRefreshing from ViewModel state
- H12: ALL-tab query race conditions — track/cancel prior load job, use request ID guard
- H13: Add Expense advanced fields weakly validated — validate TRANSFER direction/account, share % 0-100, proper keyboards
- H14: Review screen missing "Approve All" — added with confirmation dialog + progress feedback
- H15: Debug actions always accessible in production — gate behind BuildConfig.DEBUG + confirmation dialogs

- Batch D: Analytics & Charts
- H16: Analytics cache stale after transaction changes — added expense freshness signal to cache key, invalidate on change
- H17: Recurring frequency shows fabricated data — use real occurrence count; hide metric if unavailable
- H18: Budget progress NaN when budget is zero — guard with if (budget > 0), show "No budget set" fallback
- H19: Forecast budget line X-range misaligned — compute from actual max series X, not list-size arithmetic

- Batch E: Budget & Savings
- C6: Savings Goals "Add Goal" FAB is no-op — wired to Add Goal dialog with validation + snackbar
- C7: Cash Flow Calendar day selection shows no details — added bottom sheet with income/expenses/recurring/balance
- H20: Cash flow calendar matches only DAY_OF_MONTH — match by full date (normalized midnight millis)
- H21: Budget card shows contradictory numbers — use adjusted spend consistently for progress/remaining/over
- H22: SavingsGoals refresh creates duplicate collectors — cancel prior job before starting new collector
- H23: Smart recommendation "Save" button is no-op — hook to contribution logic with confirmation + snackbar
- H24: Forecast shows no confidence interval — added Low/Base/High bounds with progress bars

- Batch F: AI Assistant
- C8: AI Assistant exceptions leave chat stuck "thinking" — wrap pipeline in try/catch/finally, always reset loading
- C9: AI card components missing from codebase — created AiInsightsCard, AiRecommendationCard, AiChatBubble, AiTypingIndicator
- H25: AI raw exception text shown to users — map to sanitized user messages, log technical details only
- H26: Clear conversation hidden when history disabled — always show "Clear current conversation"
- H27: No API key/connection UX in AI settings — added masked input, secure storage note, "Test connection" CTA

- Batch G: Advanced Features
- H28: Voice search permanently disabled but visible — removed mic action until feature-ready
- H29: Group split dialog has no per-member inputs — added %/amount inputs for non-equal splits with validation
- H30: Price protection "File claim" is no-op — wired to URL launcher, implemented deals from receipts
- H31: Protected items tab is read-only — added Track/Remove actions with confirmation + undo
- H32: VisualSplitEditor crashes on invalid colors — wrapped with runCatching, validate on save/load
- H33: No settlement plan in group detail — added transfer pairs section with one-tap settle
- H34: Subscription cards missing renewal dates — display on cards, require date picker in add dialog
- H35: Bank disconnect has no confirmation — added modal with consequences + undo snackbar

- Batch H: Shared Components & Theme
- C14: Typography hardcodes colors (breaks light theme) — removed color from TextStyle definitions
- H36: Shared components use hardcoded colors — switched to MaterialTheme.colorScheme tokens
- H37: FAB menu has no outside-tap/back dismissal — added BackHandler + scrim with outside-tap dismissal
- H38: Transfer badge loses semantics when label hidden — added contentDescription for incoming/outgoing transfers
- H39: Bottom nav has 6 tabs (Material recommends 3-5) — added small-screen overflow (4 tabs + "More" dropdown)

- Batch I: Settings & Edge Cases (covered separately in release notes; not counted in this 47-fix summary)

- Coverage note: the 47 fixes above cover batches A-H only; related edge-case items are documented in the full release notes.
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

### Historical Addendum: Updated UI Layer Structure
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

### Historical Addendum: Updated Domain Layer Structure
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

## Quick Reference

### Add New Parser
1. Create `domain/parser/parsers/NewParser.kt` extending base parser
2. Register in `AppParserRegistry.parserList`
3. Add test cases in `domain/parser/`

### Add New Screen
1. Create the screen and ViewModel under the feature directory.
2. Add a `NavigationDestination` entry if it should be routable.
3. Register the route in `NavigationController`, and render the destination in `ui/MainActivity.kt`'s destination `when` block; deep-link handling lives in `MainActivity.handleIntent()`.
4. Add DI bindings only if the screen introduces new data or domain dependencies.

### Add New Database Entity
1. Create `data/database/entity/NewEntity.kt`.
2. Add it to `AppDatabase` and update the migration chain.
3. Create the matching DAO in `data/database/dao/NewEntityDao.kt`.
4. Bind the DAO / repository in the appropriate Hilt module.
5. Update any schema notes only after the migration is in place.

### Check Bug Sources
| Issue | Check Files |
|-------|-------------|
| Forecast wrong | `SynthesisEngine`, `MonteCarloSpendingSimulator`, `BudgetCalculator` |
| Budget alerts | `BudgetMonitor`, notification service bindings |
| Parser failing | `AppParserRegistry`, specific parsers, `ConfidenceRouter` |
| OCR / receipt issues | `ReceiptOcrService`, `ReceiptParser`, AI receipt categorization flow |
| Category wrong | `CategorizationEngine`, `MerchantCanonicalizer`, `HybridExpenseClassifier` |
| Recurring missed | recurring-expense engine + repositories |
| Analytics slow | `InsightsEngine`, `AdvancedAnalyticsEngine`, totals aggregation |
| Navigation broken | `NavigationDestination`, `NavigationController`, `MainActivity.handleIntent()` |
| Map / location issues | location resolver + geocoding providers |
| AI flow issues | AI module, assistant / follow-through use cases |

---

## Testing

### Unit Tests Location
```
app/src/test/java/com/yourname/expensetracker/
└── ...
app/src/test/kotlin/com/yourname/expensetracker/
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

- Use `NavigationDestination` as the routing source of truth; segment numbering is documented in `CODEBASE_SEGMENTS.md`.
- Deep links are handled in `ui/MainActivity.kt` (`handleIntent` / `onNewIntent`); navigation state is owned by `NavigationController`.

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

This appendix is historical context for the earlier feature-wave rollout and is not part of the current architecture body.
