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
- Database version: v100
- 590+ Kotlin files (~120 modified in Phases 2-3, ~20 new in Phase 4, ~5 new in Phase 5)
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
├── receipt/                     # OCR, receipt processing, lifecycle models
│   ├── ReceiptSourceType.kt     # Enum: CAMERA, GALLERY, FILE_IMPORT, EMAIL, etc.
│   ├── ReceiptDocumentType.kt   # Enum: RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, etc.
│   ├── ReceiptProcessingStatus.kt # Enum: CAPTURED → DELETED (14 values)
│   ├── EmailReceiptData.kt      # Structured email receipt data
│   └── lifecycle/               # Receipt lifecycle coordinator + services
│       ├── ReceiptLifecycleCoordinator.kt   # Single entry point for all receipt processing
│       ├── ReceiptLinkService.kt            # Centralized receipt-expense linking (multi-link)
│       ├── ReceiptAssetStore.kt             # File persistence, hash computation, backup manifest
│       ├── ReceiptInputValidator.kt         # URI / MIME / size validation
│       ├── ReceiptDuplicateDetector.kt      # 3-signal dedup (hash, text, semantic)
│       ├── ReceiptSideEffectDispatcher.kt   # Document-type-gated downstream effects
│       └── BankStatementLifecycleProcessor.kt # Statement-specific processing
├── split/                       # Split-template and expense splitting logic
├── service/                     # Domain service interfaces
├── usecase/                     # Use cases / orchestration
├── model/                       # Shared domain models
├── core/
│   ├── money/                   # Type-safe money primitives (CurrencyCode, MoneyAmount, etc.)
│   └── time/                    # Typed time period models (PeriodRange, PeriodKind)
├── transaction/                 # Transaction lifecycle models
│   ├── ExpenseSource.kt         # Enum of 14 expense origin sources
│   ├── LifecycleEventType.kt    # Enum of 14 lifecycle event types
│   ├── DeduplicationMode.kt     # Enum of deduplication strategies
│   ├── CreateExpenseRequest.kt  # Source-neutral creation request (40+ fields)
│   ├── CreateExpenseResult.kt   # Sealed result (Created, DuplicateSkipped, etc.)
│   ├── ExpenseUpdates.kt        # Patch-style update model
│   └── lifecycle/               # Lifecycle coordinator + dispatcher
│       ├── TransactionLifecycleCoordinator.kt    # Single entry point for ALL expense CUD
│       └── TransactionSideEffectDispatcher.kt    # Post-creation side effects (budget, anomaly, learning)
├── recurring/                   # **NEW — Recurring occurrence lifecycle**
│   ├── RecurringOccurrenceExpander.kt    # Expands recurrence rules into concrete occurrences
│   ├── OccurrenceConflictResolver.kt     # Resolves candidates vs actual expenses
│   ├── RecurringPlanProjectionService.kt # Materialises planned expenses from occurrences
│   └── lifecycle/               # **NEW — Recurring lifecycle coordinator + materializer**
│       ├── RecurringLifecycleCoordinator.kt      # Primary entry point for occurrence generation
│       └── RecurringOccurrenceMaterializer.kt    # Persists occurrences + creates reminders
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
│   ├── AppDatabase.kt          # Room database (v100)
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
TransactionLifecycleCoordinator.createExpense()
       │  [validate → normalize → dedupe → insert atomic → event log]
       ↓
TransactionSideEffectDispatcher.dispatchOnCreated()
       │  [budget check → anomaly alert → pattern learning]
       ↓
Expense persisted + lifecycle event recorded
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
| Database | `data/database/AppDatabase.kt` | Room DB v100 |

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

### Multi-Currency Architecture (May 2026)

A 7-phase refactoring (~70 files) introduced type-safe money primitives and wired currency-aware aggregation into all 10+ financial pipelines.

#### New `domain/core/money/` Package

| Type | File | Purpose |
|------|------|---------|
| `CurrencyCode` | `domain/core/money/CurrencyCode.kt` | Type-safe ISO 4217 wrapper (inline value class). Replaces raw `String` currency codes. |
| `MoneyAmount` | `domain/core/money/MoneyAmount.kt` | Amount + currency pair. Prevents mixed-currency arithmetic via `require()` in `plus()`/`minus()`. |
| `ConvertedMoney` | `domain/core/money/ConvertedMoney.kt` | Full conversion trace: original + converted + rate + timestamp + `ConversionStatus`. |
| `MoneyBucket` | `domain/core/money/MoneyBucket.kt` | Per-currency subtotal (currency, amount, transaction count). Used before conversion. |
| `MoneyAggregate` | `domain/core/money/MoneyAggregate.kt` | **Primary aggregation return type.** Replaces raw `Double`. Contains display amount, source buckets, conversion failures, `isPartial` flag. |
| `ConversionFailure` | `domain/core/money/ConversionFailure.kt` | Records failed conversions with `FailureReason` (MISSING_RATE, INVALID_AMOUNT, RATE_STALE, UNKNOWN). |
| `CurrencyAssumption` | `domain/core/money/CurrencyAssumption.kt` | Enum: `UNKNOWN`, `ASSUMED_HOME_CURRENCY`, `ASSUMED_LEGACY_EUR`, `USER_CONFIRMED`, `PARSED_FROM_SOURCE`. Prevents silent EUR defaults. |
| `MoneyMappers` | `domain/core/money/MoneyMappers.kt` | Bridge from legacy `ConversionResult`/`FailedConversion` → new `ConvertedMoney`/`ConversionFailure`. |
| `MoneyFormatUtils` | `domain/core/money/MoneyFormatUtils.kt` | `MoneyAmount` extension functions (`formatMoney()`, `formatMoneyCompact()`, `formatMoneyWithSign()`) delegating to `CurrencyFormatter`. |

#### `MultiCurrencyRepository` — Canonical Aggregation Backbone

**File:** `data/repository/MultiCurrencyRepository.kt`

The central aggregation bridge. Data flow:

```
ExpenseDao (DAO)
    │ getAllSpentBetweenByCurrency(startDate, endDate)
    ▼
List<CurrencyTotal>  ← per-currency grouped SQL (not raw mixed sum)
    │
    ▼
CurrencyConverter.convertMultiple(amounts, homeCurrency)
    │
    ▼
MoneyAggregate  ← displayAmount, sourceBuckets, conversionFailures, isPartial
    │
    ▼
UI (HomeScreen, BudgetScreen, AnalyticsScreen, etc.)
```

Key methods: `getTotalExpensesInHomeCurrency()`, `getHomeCurrencyCategoryTotals()`, `getHomeCurrencyDailyHistory()`, `getMerchantTotalsInHomeCurrency()`.

Wired into 10+ pipelines: Dashboard, Budget, Analytics, Forecast, Health, Savings, Groups, Export, AI/Query, Anomaly.

#### `AnalyticsCurrencyNormalizer`

**File:** `domain/analytics/AnalyticsCurrencyNormalizer.kt`

Per-expense home-currency normalization used by analytics engines. Converts a list of `Expense` entities into `AnalyticsNormalizationResult` with per-currency buckets, converted amounts, and failure tracking. All analytics, forecasting, health, and savings engines normalize through this component before performing aggregation.

#### Design Decisions

1. **Safe defaults:** `CurrencyCode.parseOr()` falls back to a caller-provided default (not implicit EUR).
2. **Currency assumption tracking:** `CurrencyAssumption` enum on every money-bearing entity records *why* a currency was assigned (legacy default, home currency, user-confirmed, parsed).
3. **Partial aggregate handling:** `MoneyAggregate.isPartial = true` when some currencies could not be converted. UI must display a warning.
4. **Deprecated unsafe paths:** 22+ `ExpenseDao` methods marked `@Deprecated("Use MultiCurrencyRepository for currency-aware aggregation")`.
5. **Deprecated formatter overloads:** `CurrencyFormatter.format(amount)` — defaults to EUR silently; replaced by `formatMoney(amount, currencyCode)`.
6. **DAOs remain raw grouped by currency:** Safe helpers like `getAllSpentBetweenByCurrency()` return `List<CurrencyTotal>` — the conversion happens in the repository.
7. **History rate support:** `ExchangeRate` added `validDate` column; `Expense` added `baseAmount`, `baseCurrency`, `exchangeRateUsed` for stable historical reporting.

---

### Time / Period Semantics Foundation (Phase 2 — May 2026)

A 98-file cross-cutting refactoring established a single source of truth for time handling.

#### New `domain/core/time/` Package

| Type | File | Purpose |
|------|------|---------|
| `PeriodRange` | `domain/core/time/PeriodRange.kt` | Typed half-open period `[startInclusive, endExclusive)` with `kind`, `zoneId`, `label`, `contains()`, `isCalendarPeriod`. Replaces raw `Pair<Long, Long>`. |
| `PeriodKind` | `domain/core/time/PeriodKind.kt` | Enum: `TODAY`, `THIS_WEEK`, `LAST_WEEK`, `LAST_7_DAYS`, `THIS_MONTH`, `LAST_MONTH`, `LAST_30_DAYS`, `THIS_QUARTER`, `LAST_QUARTER`, `THIS_YEAR`, `LAST_YEAR`, `CUSTOM`. Distinguishes calendar vs rolling semantics. |

#### Key Contract Changes

1. **`TimeProvider.now()` is the single source of "now"** (injected into 50+ classes). Direct `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()` are forbidden in business logic (whitelist exceptions in `TIME_SEMANTICS.md`).
2. **All period ranges are half-open** `[startInclusive, endExclusive)`. No more `23:59:59.999` endpoints.
3. **Calendar labels use calendar-range helpers** (`getMonthRange`, `getWeekRange`, etc.). Rolling labels use rolling helpers (`getLastNCalendarDaysRange`, `getLastNCompleteDaysRange`). Never `getLastNDaysRange(30)` for "This Month".
4. **Raw millis division** (`(end - start) / 86_400_000`) is replaced with DST-safe `TimePeriodUtils.daysBetween()`.
5. **38 entity `System.currentTimeMillis()` defaults** migrated to `0L` sentinel.
6. **`DateFormatterUtils`** — all 13 methods accept explicit timestamps (no internal `Instant.now()`).
7. **`RecurrenceFrequency.days`** — removed from constructor, now a computed property.

See [`docs/development/TIME_SEMANTICS.md`](../development/TIME_SEMANTICS.md) for full developer rules.

---

### Transaction Lifecycle Architecture (Phase 3 — May 2026)

A 120+ file cross-cutting feature establishing a single, auditable entry point for all expense creation, update, and delete operations.

#### New `domain/transaction/` Package

| Type | File | Purpose |
|------|------|---------|
| `ExpenseSource` | `domain/transaction/ExpenseSource.kt` | Enum tracking the origin of every expense: MANUAL_ENTRY, NOTIFICATION_AUTO_ACCEPT, REVIEW_APPROVAL, RECEIPT_SCAN, CSV_IMPORT, EMAIL_RECEIPT, GROUP_EXPENSE, BANK_API_SYNC, etc. (14 values) |
| `LifecycleEventType` | `domain/transaction/LifecycleEventType.kt` | Enum of lifecycle transition types: CREATED, UPDATED, DELETED, CREATE_DUPLICATE_SKIPPED, etc. (14 values) |
| `DeduplicationMode` | `domain/transaction/DeduplicationMode.kt` | Enum: STANDARD, STRICT_EXTERNAL_ID, BULK_IMPORT, SKIP_FOR_DEBUG_RESTORE |
| `CreateExpenseRequest` | `domain/transaction/CreateExpenseRequest.kt` | Source-neutral creation request with 40+ fields covering all expense properties, source-link fields, and deduplication policy controls |
| `CreateExpenseResult` | `domain/transaction/CreateExpenseResult.kt` | Sealed result: Created(id), DuplicateSkipped(existingId, reason), ValidationFailed(errors), InsertConflict(dedupeKey), Error(exception) |
| `ExpenseUpdates` | `domain/transaction/ExpenseUpdates.kt` | Patch-style update model for modifying existing expense fields |

#### New `domain/transaction/lifecycle/` Package

| Component | File | Purpose |
|-----------|------|---------|
| `TransactionLifecycleCoordinator` | `lifecycle/TransactionLifecycleCoordinator.kt` | **Single entry point** for ALL expense creation/update/delete. Pipeline: validate → normalize → dedupe → insert atomic → event logging → side effects. Injected by 10+ consumer classes. |
| `TransactionSideEffectDispatcher` | `lifecycle/TransactionSideEffectDispatcher.kt` | Consolidates post-creation side effects: budget check, anomaly alert, merchant-category pattern learning. Best-effort / fire-and-forget. |

#### Migration Paths (all now route through coordinator)

| Path | PR | Status |
|------|----|--------|
| Manual Entry | PR 2 | Migrated |
| Pending Review Approval | PR 3 | Migrated |
| Notification Auto-Accept | PR 4 | Migrated |
| Receipt Path | PR 5 | Migrated |
| CSV Import | PR 6 | Migrated (dedup + lifecycle) |
| MainActivity direct DAO | PR 6 | Removed |
| Delete Lifecycle | PR 7 | Migrated |
| Email Receipt | PR 7 | Migrated |
| Group/Shared | PR 8 | Migrated |
| Bank API | PR 9 | Migrated |

#### New Guardrails

- `docs/development/DAO_ACCESS_GUARDRAILS.md` — defines approved ExpenseDao access patterns
- `scripts/guardrails/dao-access-check.kts` — CI-enforceable check for violations
- `scripts/guardrails/dao-approved-files.txt` — approved file list for the check

#### New DB Layer

- `TransactionEvent` — Room entity for `transaction_events` table (immutable lifecycle audit log)
- `TransactionEventDao` — DAO with `insert()` and `getEventsForExpense()`
- `Expense.source` — new nullable column tracking expense origin (ExpenseSource as String)
- Migration 94→95: adds `source` column + creates `transaction_events` table with indices

---

### Receipt Lifecycle Architecture (Phase 4 — May 2026)

A ~20-file cross-cutting feature establishing a single, auditable entry point for all receipt processing, with document-type-aware lifecycle management.

#### New `domain/receipt/` Models

| Type | File | Purpose |
|------|------|---------|
| `ReceiptSourceType` | `domain/receipt/ReceiptSourceType.kt` | Enum: CAMERA, GALLERY, FILE_IMPORT, EMAIL, BANK_STATEMENT, MANUAL_RECORD, BATCH_SCAN, DEBUG_IMPORT, UNKNOWN |
| `ReceiptDocumentType` | `domain/receipt/ReceiptDocumentType.kt` | Enum: RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, MANUAL_PLACEHOLDER, PDF_RECEIPT, UNKNOWN |
| `ReceiptProcessingStatus` | `domain/receipt/ReceiptProcessingStatus.kt` | Enum: 14 values from CAPTURED through DELETED, covering the full receipt lifecycle |
| `EmailReceiptData` | `domain/receipt/EmailReceiptData.kt` | Structured email receipt with parsed financial fields (amount, merchant, currency, date, items) |

#### New `domain/receipt/lifecycle/` Package

| Component | File | Purpose |
|-----------|------|---------|
| `ReceiptLifecycleCoordinator` | `lifecycle/ReceiptLifecycleCoordinator.kt` | **Single entry point** for all receipt processing. Pipeline: validate → persist asset → OCR/parse → dedupe → save → event logging → side effects. Handles camera/gallery, email, bank statement, and manual receipt paths. |
| `ReceiptLinkService` | `lifecycle/ReceiptLinkService.kt` | Centralized receipt-expense link management via `receipt_expense_links` join table. Supports many-to-many links (BANK_STATEMENT) and single links (all other types). Writes audit events for every link/unlink. |
| `ReceiptAssetStore` | `lifecycle/ReceiptAssetStore.kt` | File persistence layer: copies receipt images to app-local storage, computes SHA-256 hashes, creates camera temp URIs via FileProvider, generates backup manifests. |
| `ReceiptInputValidator` | `lifecycle/ReceiptInputValidator.kt` | URI/MIME/size validation: checks readability, supported MIME types (JPEG, PNG, WebP, PDF, HEIC), file size limit (50 MB), bitmap decode validity. |
| `ReceiptDuplicateDetector` | `lifecycle/ReceiptDuplicateDetector.kt` | 3-signal deduplication: EXACT_HASH (SHA-256, 1.0 confidence), TEXT_FINGERPRINT (normalized OCR text, 0.95), SEMANTIC (merchant+amount+date+currency, 0.8), plus EXTERNAL_ID for email dedup. |
| `ReceiptSideEffectDispatcher` | `lifecycle/ReceiptSideEffectDispatcher.kt` | Document-type-gated post-save effects: RETAIL_RECEIPT → warranty extraction, item categorization, transaction matching, price protection. EMAIL_RECEIPT → item categorization only. BANK_STATEMENT/MANUAL_PLACEHOLDER → no effects. |
| `BankStatementLifecycleProcessor` | `lifecycle/BankStatementLifecycleProcessor.kt` | Statement-specific processing: OCR → parse transactions → create PendingReview entries → lifecycle events. Returns structured `BankStatementResult`. |

#### Migration Paths (all now route through coordinator)

| Path | PR | Status |
|------|----|--------|
| Camera/Gallery/File scan | PR 4 | Migrated |
| Receipt→Expense save (with link service) | PR 4 | Migrated |
| Review queue receipt linking | PR 5 | Migrated |
| Bank statement processing | PR 5 | Migrated |
| Email receipt ingestion | PR 5 | Migrated |
| Warranty/Return/Price side effects | PR 6 | Document-type-gated |
| Receipt matching | PR 7 | Migrated via LinkService |
| Item categorization gating | PR 7 | Status-consistent + document-type gating |

#### New DB Layer

- `ReceiptEvent` — Room entity for `receipt_events` table (immutable lifecycle audit log for receipts)
- `ReceiptEventDao` — DAO with `insert()` and `getEventsForReceipt()`
- `ReceiptExpenseLink` — Room entity for `receipt_expense_links` table (many-to-many receipt↔expense join)
- `ReceiptExpenseLinkDao` — DAO with `insert()`, `getLinksForReceipt()`, `getLinksForExpense()`, `unlink()`, `deleteAllLinksForReceipt()`
- `ScannedReceipt` — 10 new columns: `sourceType`, `documentType`, `processingStatus`, `sourceFingerprint`, `imageHash`, `textFingerprint`, `semanticFingerprint`, `ocrConfidence`, `parseFailureReason`, `updatedAt`
- Migration 95→96: adds `receipt_events` and `receipt_expense_links` tables, adds 10 columns to `scanned_receipts`
- Migration 95→96: removes `transaction_events_eventType_source_index` before re-creating it to fix schema drift

---

### Recurring / Planned / Reminder Lifecycle Foundation (Phase 5 — May 2026)

A ~5-file domain expansion establishing an auditable lifecycle for recurring-expense occurrences, with conflict resolution and reminder scheduling.

#### New `domain/recurring/` Package — Expansion & Resolution

| Component | File | Purpose |
|-----------|------|---------|
| `RecurringOccurrenceExpander` | `domain/recurring/RecurringOccurrenceExpander.kt` | Pure utility that expands a recurrence rule into concrete occurrence candidates within a half-open date range. Supports WEEKLY/BIWEEKLY/MONTHLY/QUARTERLY/SEMI_ANNUALLY/ANNUALLY frequencies via calendar-aware arithmetic (DST/leap-year safe). |
| `OccurrenceConflictResolver` | `domain/recurring/OccurrenceConflictResolver.kt` | Resolves occurrence candidates against actual expenses to determine PLANNED/PAID/SKIPPED status. Matching rules: same calendar day, merchant match (case-insensitive via MerchantKeyGenerator), amount ±10% tolerance, same currency. Each expense matched at most once. |
| `RecurringPlanProjectionService` | `domain/recurring/RecurringPlanProjectionService.kt` | Bridges the recurring lifecycle system to forecasting/budgeting by materialising `PlannedExpense` rows from PLANNED occurrences. Prevents double-count risk by deduplicating via `sourceOccurrenceKey`. |

#### New `domain/recurring/lifecycle/` Package — Coordination & Materialization

| Component | File | Purpose |
|-----------|------|---------|
| `RecurringLifecycleCoordinator` | `lifecycle/RecurringLifecycleCoordinator.kt` | **Primary entry point** for generating and managing recurring occurrences. Orchestrates expand → resolve → materialize pipeline. Provides `generateOccurrences()`, `linkExpenseToOccurrence()` (best-effort post-creation linking), `getOccurrences()`, `updateOccurrenceStatus()`, and `getDueReminders()`. |
| `RecurringOccurrenceMaterializer` | `lifecycle/RecurringOccurrenceMaterializer.kt` | Persists resolved occurrences and creates reminder deliveries. INSERT with IGNORE for new (occurrenceKey unique constraint), UPDATE for status changes. Creates `RecurringReminderDelivery` rows for PLANNED occurrences (DUE_DAY, N_DAYS_BEFORE, OVERDUE windows). |

#### Cross-Cutting Integration

- **TransactionLifecycleCoordinator auto-link hook (Phase 3 ↔ Phase 5):** After every `createExpense()`, a best-effort call to `recurringLifecycleCoordinator.linkExpenseToOccurrence()` attempts to match the new expense to a PLANNED occurrence on the same calendar day. Failures are silently caught (non-blocking).
- **ForecastInputAssembler** updated to inject `RecurringLifecycleCoordinator` for future dedup integration (replacing ad-hoc recurrence expansion with coordinator-driven generation).
- **Subscription math normalization** fixed across subscription detection and recurring engines.

#### New DB Layer

- `RecurringOccurrence` — Room entity for `recurring_occurrences` table (sourceType, sourceId, occurrenceKey unique, dueDate, status, linkedExpenseId, expectedAmount/Currency, paid fields, frequency, merchant, categoryId, timestamps). Indices on (sourceType+sourceId), dueDate, status, occurrenceKey (unique), linkedExpenseId.
- `RecurringOccurrenceDao` — DAO with insert (IGNORE), insertAll, update, getByKey, getBySource, getByDateRange, getByStatus, updateStatus.
- `RecurringReminderDelivery` — Room entity for `recurring_reminder_deliveries` table (occurrenceId, reminderWindow, scheduledAt, status, lastSentAt, dismissedAt, snoozedUntil, notificationId, createdAt). Indices on (occurrenceId+reminderWindow), status, scheduledAt.
- `RecurringReminderDeliveryDao` — DAO with insert, insertAll, update, getByOccurrenceAndWindow, getPendingDeliveries.
- `PlannedExpense` — 2 new columns: `sourceOccurrenceKey` (TEXT, nullable) and `sourceRecurringRuleId` (INTEGER, nullable) for cross-linking planned expenses to recurring occurrences.
- Migration 96→100: creates both new tables with indices, adds 2 columns to `planned_expenses`.

### Phase 6 — Privacy & Capability Gates (May 2026)

Phase 6 introduces a privacy capability gate system and backup encryption for
the expense tracker database.

#### Privacy Gate Architecture

| Component | File | Purpose |
|-----------|------|---------|
| `PrivacyGate` (interface) | `domain/privacy/PrivacyGate.kt` | Contract for evaluating a capability against current privacy settings, returning Allowed or Denied |
| `CompositePrivacyGate` | `domain/privacy/CompositePrivacyGate.kt` | Chains multiple gates; first Denied short-circuits |
| `NotificationPrivacyGate` | `domain/privacy/NotificationPrivacyGate.kt` | Guards NOTIFICATION_CAPTURE and NOTIFICATION_PACKAGE_ALLOWLIST |
| `LocationPrivacyGate` | `domain/privacy/LocationPrivacyGate.kt` | Guards EXTERNAL_GEOCODING, BACKGROUND_LOCATION_BACKFILL, DEVICE_GPS_LOCATION, OVERPASS_API |
| `CloudAiPrivacyGate` | `domain/privacy/CloudAiPrivacyGate.kt` | Guards all CLOUD_AI_* capabilities plus RECEIPT_IMAGE_CLOUD_UPLOAD |
| `BackupPrivacyGate` | `domain/privacy/BackupPrivacyGate.kt` | Guards RAWBACKUP_EXPORT and ENCRYPTED_BACKUP based on `encryptedBackupEnabled` setting |
| `PrivacySettings` | `domain/privacy/PrivacySettings.kt` | Data class with ALL 10 privacy toggles + 2 retention day settings |
| `PrivacySettingsRepository` | `domain/privacy/PrivacySettingsRepository.kt` | Interface for reading/writing privacy settings |
| `PrivacySettingsRepositoryImpl` | `data/privacy/PrivacySettingsRepositoryImpl.kt` | DataStore-backed implementation |

#### Backup Encryption

| Component | File | Purpose |
|-----------|------|---------|
| `BackupEncryptionService` | `data/privacy/BackupEncryptionService.kt` | AES-256-GCM encrypt/decrypt with PBKDF2 key derivation |
| `ExportAnonymizer` | `data/privacy/ExportAnonymizer.kt` | Strips rawOcrText and raw notification content from temp DB copy before export |
| `DatabaseBackupRepositoryImpl` (updated) | `data/repository/DatabaseBackupRepositoryImpl.kt` | Now checks privacy gates, optionally encrypts exports, and sanitizes sensitive data |

#### Privacy UI

| Component | File | Purpose |
|-----------|------|---------|
| `PrivacySettingsViewModel` | `ui/screens/privacysettings/PrivacySettingsViewModel.kt` | Reads/writes all 10 privacy settings via repository |
| `PrivacySettingsScreen` | `ui/screens/privacysettings/PrivacySettingsScreen.kt` | Compose screen with toggles for all privacy settings |

#### DI Wiring

`PrivacyModule.kt` now binds all four gates (Notification, Location, CloudAI, Backup)
into the `CompositePrivacyGate`. The `BackupEncryptionService`, `ExportAnonymizer`,
and `SecureKeyStorage` are injected into `DatabaseBackupRepositoryImpl`.

**Phase 6 is complete.**

---

## Database Schema

### Version: v100 (post recurring lifecycle migration)

The Room schema in v100 includes all tables from v96 plus:

**New table:** `recurring_occurrences` — stores expanded occurrence candidates for recurring rules with status tracking (PLANNED/PAID/SKIPPED/MISSED/CANCELLED). Unique constraint on `occurrenceKey` enables idempotent insert.

**New table:** `recurring_reminder_deliveries` — schedules and tracks reminder dispatch for recurring occurrences (SCHEDULED/SENT/DISMISSED/SNOOZED/FAILED states, configurable reminder windows like DUE_DAY, 3_DAYS_BEFORE, OVERDUE).

**New columns on `planned_expenses`:** `sourceOccurrenceKey` (TEXT, nullable) and `sourceRecurringRuleId` (INTEGER, nullable) — links planned expenses back to the recurring occurrence and rule that generated them, preventing double-count.

The full schema now covers:

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
- typed errors, time standardization (half-open periods, single source of "now"), accessibility, and performance refactors are now cross-cutting concerns

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

### Post-Review Hardening (May 2026)

Cross-cutting fixes applied after architecture review tightened correctness, consistency, and edge-case handling across Phases 3–5:

**Transactional guarantees (Phase 3+4+5):** All coordinator operations (create, update, delete, link, unlink, materialize) now run inside a single Room transaction. Receipt delete performs post-commit file cleanup.

**Validation hardening:** Full validation wired for future-date checks, transfer direction/account, expense ownership, and ISO 4217 currency codes in all paths.

**Deduplication completeness:** `deduplicationMode` / `idempotencyKey` fully propagated through coordinators. `STRICT_EXTERNAL_ID` mode uses `idem:`-prefixed dedupeKeys. `BULK_IMPORT` runs standard dedup (no external-id skip). Text and semantic dedup now run post-OCR in the receipt pipeline. Duplicate detection returns real existing IDs (not placeholders).

**Receipt lifecycle fixes:** `processEmailReceipt()` fully implemented with non-bank receipt relink prevention. Hardcoded EUR removed from receipt asset paths. Duplicate receipts correctly marked `DUPLICATE_DETECTED`. Asset double-persistence removed.

**Recurring / materialization fixes:** `RecurringOccurrenceExpander` uses `rule.nextDate` as the expansion anchor. `ReminderDeliveryDao` unique index on (`occurrenceId`, `reminderWindow`). Materialization runs transactionally. `PlannedExpense` gains `status`, `linkedExpenseId`, `merchantKey` columns. Subscription cost-per-use normalized to monthly.

**DI & code quality:** Hilt `@Inject` added to `RecurringOccurrenceExpander` and `OccurrenceConflictResolver`. `ManualExpenseRepository` and `SmartBillNegotiationEngine` DAO leaks fixed. `linkExpenseToOccurrence()` matches on merchant/amount/currency. NLP last-month uses calendar-month boundaries. Structured JSON snapshots for all audit events.

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
