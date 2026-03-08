# ExpenseTracker - Architecture Documentation

## Table of Contents
1. [Overview](#overview)
2. [Technology Stack](#technology-stack)
3. [Project Structure](#project-structure)
4. [Architecture Layers](#architecture-layers)
5. [Data Layer](#data-layer)
6. [Domain Layer](#domain-layer)
7. [UI Layer](#ui-layer)
8. [Android Components](#android-components)
9. [Dependency Injection](#dependency-injection)
10. [Database Schema](#database-schema)
11. [Navigation Flow](#navigation-flow)
12. [Key Features](#key-features)
13. [Testing Strategy](#testing-strategy)
14. [Build Configuration](#build-configuration)
15. [Notes](#notes)

---

## Overview

ExpenseTracker is an Android expense management application that automatically captures expenses from SMS notifications, bank statements, and receipts. It uses machine learning for merchant categorization, provides budget tracking with alerts, and offers comprehensive analytics.

## Technology Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin |
| UI Framework | Jetpack Compose |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| Architecture | Clean Architecture + MVVM |
| DI Framework | Hilt |
| Database | Room |
| Async | Kotlin Coroutines + Flow |
| Charts | Vico |
| OCR | ML Kit Text Recognition |
| PDF Processing | PDFBox Android |
| Image Loading | Coil |
| Logging | Timber |

---

## Project Structure

```
app/src/main/java/com/yourname/expensetracker/
├── data/                      # Data Layer
│   ├── database/              # Room Database
│   │   ├── dao/               # Data Access Objects
│   │   ├── entity/            # Database Entities
│   │   ├── model/             # Composite Models
│   │   └── converter/         # Type Converters
│   ├── repository/            # Repository Implementations
│   ├── service/               # Android Services (implementations)
│   └── provider/              # Data Providers
├── di/                        # Dependency Injection Modules
├── domain/                    # Domain Layer
│   ├── analytics/             # Analytics Engines
│   ├── budget/                # Budget Management
│   ├── categorization/        # Category Engine
│   ├── debug/                # Debug Utilities
│   ├── intelligence/          # ML/AI Components
│   │   └── ml/               # Machine Learning
│   ├── logic/                 # Business Logic
│   ├── model/                 # Domain Models
│   ├── parser/               # Notification/SMS Parsers
│   │   └── parsers/          # Specific Parsers
│   ├── receipt/              # Receipt Processing
│   ├── service/              # Domain Services (interfaces)
│   └── util/                 # Utilities
├── service/                  # Android Services
├── receiver/                 # Broadcast Receivers
└── ui/                       # UI Layer
    ├── common/               # Shared UI Components
    ├── components/           # Reusable Compose Components
    ├── screens/              # Screen Composables + ViewModels
    ├── theme/                # Material3 Theme
    └── util/                 # UI Utilities
```

---

## Architecture Layers

### Clean Architecture Overview

```
┌─────────────────────────────────────────┐
│              UI Layer                   │
│   (Compose Screens + ViewModels)        │
├─────────────────────────────────────────┤
│            Domain Layer                 │
│   (Use Cases, Business Logic, Models)   │
├─────────────────────────────────────────┤
│             Data Layer                  │
│   (Repositories, Room DB, Providers)    │
└─────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Responsibility |
|-------|----------------|
| **UI** | Compose screens, state management, user interactions |
| **Domain** | Business rules, parsing logic, ML classification, analytics |
| **Data** | Database operations, data sources, repository implementations |

---

## Data Layer

### Database

**AppDatabase** (Room) - Version 27 (Updated Mar 2026)

#### Entities

| Entity | Purpose |
|--------|---------|
| `Expense` | Core expense records |
| `Category` | User-defined expense categories |
| `MerchantCategory` | Merchant-to-category mappings |
| `PendingReview` | Expenses awaiting user approval |
| `RawNotification` | Captured SMS/notification data |
| `BlockedPackage` | Blocked notification sources |
| `Budget` | Budget limits per category/period |
| `ScannedReceipt` | OCR-processed receipts |
| `ManualRecurringExpense` | User-defined recurring expenses |
| `PlannedExpense` | Future planned expenses |
| `SavingsGoal` | Savings targets |
| `UserCorrection` | User correction history |
| `SourceStats` | Notification source statistics |
| `MerchantCanonical` | Normalized merchant names |
| `MerchantAlias` | Merchant name variants |

### DAOs (Data Access Objects)

| DAO | Purpose |
|-----|---------|
| `ExpenseDao` | Expense CRUD and queries |
| `CategoryDao` | Category management |
| `BudgetDao` | Budget operations |
| `PendingReviewDao` | Review queue management |
| `RawNotificationDao` | Raw notification storage |
| `BlockedPackageDao` | Package blocking |
| `ScannedReceiptDao` | Receipt storage |
| `MerchantCategoryDao` | Merchant-category mappings |
| `MerchantNormalizationDao` | Merchant name normalization |
| `RecurringExpenseDao` | Recurring expenses |
| `PlannedExpenseDao` | Planned expenses |
| `SavingsGoalDao` | Savings goals |
| `UserCorrectionDao` | User corrections |
| `SourceStatsDao` | Source statistics |

### Repositories

| Repository | Responsibility |
|------------|----------------|
| `ExpenseRepository` | CRUD operations for expenses |
| `CategoryRepository` | Category management |
| `BudgetRepository` | Budget tracking and alerts |
| `ReviewQueueRepository` | Pending review queue |
| `NotificationRepository` | Notification capture/storage |
| `ReceiptRepository` | Receipt scanning and storage |
| `RecurringExpenseRepository` | Recurring expense management |
| `PlannedExpenseRepository` | Planned expenses |
| `SavingsGoalRepository` | Savings goals |
| `AnalyticsRepository` | Analytics data aggregation |
| `MerchantNormalizationRepository` | Merchant name normalization |
| `MerchantRulesRepository` | Merchant categorization rules |
| `UserCorrectionRepository` | User correction tracking |
| `SourceStatsRepository` | Source statistics |
| `DashboardRepository` | Dashboard aggregations |
| `FinancialWeatherRepository` | Financial health data |
| `ManualExpenseRepository` | Manual expense entry |
| `MerchantCategoryRepository` | Merchant category provider |

### Data Services

| Service | Purpose |
|---------|---------|
| `AndroidNotificationService` | Android notification implementation (implements `NotificationService` interface) |

### Database Models (Composite)

| Model | Purpose |
|-------|---------|
| `ExpenseWithCategory` | Expense joined with category |
| `PendingReviewWithReceipt` | Review item joined with receipt |
| `DashboardWidgetConfig` | Dashboard widget configuration |

---

## Domain Layer

### Parsing System

**AppParserRegistry** - Routes notifications to appropriate parsers

```
Notification → AppParserRegistry → [SmsParser | GreekBankParser | RevolutParser | GoogleWalletParser | GenericTransactionParser] → ParsedTransaction
```

| Parser | Description |
|--------|-------------|
| `SmsParser` | Generic SMS transaction parsing |
| `GreekBankParser` | Greek bank notifications (NBG, Alpha, etc.) |
| `RevolutParser` | Revolut-specific notifications |
| `GoogleWalletParser` | Google Wallet receipts |
| `GenericTransactionParser` | Fallback generic parser |

### Intelligence/ML

| Component | Description |
|-----------|-------------|
| `HybridExpenseClassifier` | Combined merchant dictionary + ML classifier (uses CategorizationEngine as single source of truth) |
| `ExpenseCategoryClassifier` | Category prediction |
| `MerchantNormalizer` | Merchant name normalization using BK-Tree |
| `FeatureExtractor` | Feature extraction for ML models |
| `ConfidenceRouter` | Routes based on prediction confidence |
| `TransactionClassifier` | Transaction type classification |
| `CategorizationEngine` | Centralized merchant→category mapping using rich dictionary |

### Analytics Engines

| Engine | Purpose |
|--------|---------|
| `InsightsEngine` | Generates spending insights (coordinator) |
| `AdvancedAnalyticsEngine` | Advanced analytics and trends |
| `AnomalyDetector` | Multi-method anomaly detection (MAD, IQR, Contextual, Multiplier) |
| `BudgetMonitor` | Budget tracking and alerts (uses `NotificationService` interface) |
| `BudgetCalculator` | Budget calculations |

### Advanced Analytics Features (Mar 2026) - ALL 6 IMPLEMENTED

#### Feature 1: Anomaly Detection (Multi-Method)
- **AnomalyDetector.kt**: MAD, IQR, Contextual, Multiplier detection
- **AnalyticsModels.kt**: `AnomalyMethod` enum, `AnomalyTransaction` fields
- Priority: MAD > IQR > Contextual > Multiplier

#### Feature 2: Cumulative Spending Curve
- **ComputeDashboardWidgetsUseCase.kt**: `SpendingTrendSeries` with multi-month data
- **SpendingTrendChart.kt**: Multi-series line chart (current + 5 prior months)
- Shows cumulative daily spending per month

#### Feature 3: Year-over-Year Comparison
- **AnalyticsViewModel.kt**: `computeYearOverYear()` function
- **AnalyticsModels.kt**: `MonthlyYearTotal`, `YearOverYearComparison`
- Compares same month across years

#### Feature 4: Spending Velocity Anomaly
- **AnalyticsViewModel.kt**: `computeVelocityAnomalies()` function
- Flags days with spending >2x daily average AND >IQR upper fence

#### Feature 5: Post-Salary Sequential Pattern
- **AnalyticsViewModel.kt**: `computePostSalaryPattern()` function
- Tracks spending in 7 days after salary deposits
- Shows: avg days to first purchase, top categories

#### Feature 6: Duplicate/Error Detection
- **AnalyticsViewModel.kt**: `detectSuspectTransactions()` function
- Near-duplicates (same amount+merchant within 24h)
- Round amounts (>=500, divisible by 50, >2x avg)
- Extreme outliers (>5x average)

### Logic Engines

| Engine | Purpose |
|--------|---------|
| `RecurringExpenseEngine` | Recurring expense detection and generation |
| `SynthesisEngine` | Data synthesis, financial forecasting, trajectory calculation |
| `NarrativeGenerator` | Generates expense narratives |
| `CategorizationEngine` | Auto-categorization (single source of truth for merchant→category) |

### Receipt Processing

| Component | Purpose |
|-----------|---------|
| `ReceiptOcrService` | ML Kit OCR integration |
| `ReceiptParser` | Receipt text parsing |
| `BankStatementParser` | Bank statement PDF parsing |

### Domain Utilities

| Utility | Purpose |
|---------|---------|
| `TimeProvider` | Time abstraction for testing |
| `SystemTimeProvider` | System time implementation |
| `TimePeriodUtils` | Period calculations |
| `DateFormatterUtils` | Date formatting |
| `AmountUtils` | Amount parsing/formatting |
| `CurrencyNormalizer` | Currency normalization |
| `MerchantCleaner` | Merchant name cleaning |
| `StringDistanceUtils` | String similarity (Levenshtein) |
| `BKTree` | BK-Tree for fuzzy matching |
| `StatisticsUtils` | Statistical calculations |
| `CommonPatterns` | Regex patterns |
| `AppConstants` | App constants |

---

## UI Layer

### Screens

| Screen | Route | Description |
|--------|-------|-------------|
| `HomeScreen` | Tab 0 | Dashboard with financial overview |
| `HomeViewModel` | - | Dashboard ViewModel |
| `TransactionsScreen` | Tab 1 | Transaction history with filters |
| `TransactionsViewModel` | - | Transactions ViewModel |
| `ReviewScreen` | Tab 2 | Pending expense review |
| `ReviewViewModel` | - | Review queue ViewModel |
| `BudgetScreen` | Tab 3 | Budget planning |
| `BudgetViewModel` | - | Budget ViewModel |
| `AnalyticsScreen` | Tab 4 | Financial Insights (all 6 advanced features) |
| `AdvancedAnalyticsScreen` | - | Legacy - replaced by AnalyticsScreen |
| `AnalyticsViewModel` | - | Analytics ViewModel with all 6 features |
| `AdvancedAnalyticsViewModel` | - | Legacy ViewModel - replaced by AnalyticsViewModel |
| `AddExpenseSheet` | Modal | Manual expense entry |
| `AddExpenseViewModel` | - | Add expense ViewModel |
| `ReceiptScanScreen` | Modal | Receipt OCR scanning |
| `ReceiptScanViewModel` | - | Receipt scan ViewModel |
| `RecurringExpensesScreen` | Modal | Recurring expenses management |
| `CategoryScreen` | Modal | Category management |
| `CategoryViewModel` | - | Category ViewModel |
| `DebugScreen` | Debug | Debug tools |
| `DebugViewModel` | - | Debug ViewModel |
| `DebugViewerScreen` | Debug | Data viewer |

### Shared Components

| Component | Purpose |
|-----------|---------|
| `BentoCard` | Bento-style card layout |
| `FinancialRunwayCard` | Financial runway display |
| `FinancialWeatherCard` | Financial health indicator |
| `SpendingTrendChart` | Multi-month cumulative spending curve (Feature 2) |
| `ForecastTimeline` | Expense forecast |
| `SpendingPaceGauge` | Budget pace indicator |
| `BudgetBlockPartyCard` | Block party budget card |
| `FinancialRunwayCard` | Days remaining + discretionary + daily rate |
| `MonteCarloForecastCard` | Probabilistic month-end forecast (P10/P50/P90) |
| `VelocityAnomalyCard` | Spending velocity anomaly display (Feature 4) |
| `YearOverYearCard` | Year-over-year comparison (Feature 3) |
| `PostSalaryPatternCard` | Post-salary spending pattern (Feature 5) |
| `SuspectTransactionCard` | Duplicate/error detection (Feature 6) |
| `PulseDot` | Notification indicator |
| `ChartMarker` | Chart tooltips |
| `CurrencyFormatter` | Currency formatting |

### Debug Utilities

| Component | Purpose |
|-----------|---------|
| `DebugScreen` | Debug tools UI |
| `DebugViewModel` | Debug operations |
| `DebugViewerScreen` | Data inspection |
| `DebugDataStorage` | Debug data management |
| `DebugIssueDetector` | Issue detection |
| `NotificationSeeder` | Test notification seeding |

### Domain Models

| Model | Purpose |
|-------|---------|
| `Expense` | Core expense entity |
| `TransactionType` | Enum: PURCHASE, WITHDRAWAL, TRANSFER, DEPOSIT, REFUND |
| `Category` | Expense category |
| `Budget` | Budget entity |
| `PendingReview` | Review queue item |
| `RawNotification` | Captured notification |
| `ScannedReceipt` | OCR receipt |
| `RecurringPattern` | Recurring expense pattern |
| `PlannedExpense` | Future expense |
| `SavingsGoal` | Savings target |
| `FinancialForecast` | Forecast data |
| `BlockPartyDay` | Budget block party day |
| `UpcomingItem` | Upcoming expense item |
| `PeriodRange` | Date range |
| `Result` | Result wrapper |

---

## Dependency Injection

### Modules

| Module | Purpose |
|--------|---------|
| `AppModule` | Database, DAOs, parsers |
| `DispatchersModule` | Coroutine dispatchers |
| `TimeModule` | Time provider abstraction |

### DI Architecture

```
AppModule
├── AppDatabase (Room)
├── All DAOs
├── Parser Registry (SmsParser, GreekBankParser, RevolutParser, GoogleWalletParser, GenericTransactionParser)
└── NotificationService (AndroidNotificationService implementation)

DispatchersModule
├── IoDispatcher
├── MainDispatcher
├── DefaultDispatcher
└── ComputationDispatcher

TimeModule
└── TimeProvider (SystemTimeProvider)
```

### Domain Services (Interfaces)

| Service | Purpose |
|---------|---------|
| `NotificationService` | Interface for sending notifications (implemented by AndroidNotificationService) |

---

## Database Schema

### Key Tables

#### expenses
| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Primary key |
| amount | REAL | Expense amount |
| currency | TEXT | Currency code |
| merchant | TEXT | Merchant name |
| categoryId | INTEGER FK | Category |
| date | INTEGER | Transaction date |
| transactionType | TEXT | PURCHASE/WITHDRAWAL/TRANSFER/DEPOSIT/REFUND |
| paymentMethod | TEXT | Payment method |
| isManualEntry | INTEGER | Manual entry flag |
| notes | TEXT | User notes |
| rawNotificationId | INTEGER FK | Source notification |
| createdAt | INTEGER | Record creation time |
| transferDirection | TEXT | INCOMING/OUTGOING (for transfers) |
| transferAccountName | TEXT | Account/person name for transfers |
| isNotMine | INTEGER | Expense belongs to someone else |
| ownerName | TEXT | Owner name (e.g., Partner, Roommate) |
| isSharedExpense | INTEGER | Expense is split with someone |
| sharedWithName | TEXT | Person shared with |
| mySharePercentage | INTEGER | My share percentage |
| myShareAmount | REAL | My share amount |

#### TransactionType Enum

| Type | Description |
|------|-------------|
| `PURCHASE` | Regular purchase/expense |
| `WITHDRAWAL` | Cash withdrawal |
| `TRANSFER` | Money transfer |
| `DEPOSIT` | Money deposit |
| `REFUND` | Refund/credit |

#### TransferDirection Enum

| Type | Description |
|------|-------------|
| `INCOMING` | Money coming to me (received transfer, borrowed money returned) |
| `OUTGOING` | Money going from me (sent transfer, lent money) |

#### budgets
| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Primary key |
| categoryId | INTEGER FK | Category (null = global) |
| amount | REAL | Budget limit |
| period | TEXT | WEEKLY/MONTHLY/YEARLY |
| startDate | INTEGER | Budget start date |
| isActive | INTEGER | Active flag |
| notifyAtWarning | REAL | Warning threshold (0.75) |
| notifyAtCritical | REAL | Critical threshold (0.90) |
| rollover | INTEGER | Rollover flag |

#### pending_reviews
| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Primary key |
| rawNotificationId | INTEGER FK | Source notification |
| scannedReceiptId | INTEGER FK | Linked receipt |
| suggestedAmount | REAL | Parsed amount |
| suggestedMerchant | TEXT | Parsed merchant |
| suggestedCategoryId | INTEGER FK | Suggested category |
| confidence | REAL | Parsing confidence |
| status | TEXT | PENDING/APPROVED/REJECTED |

#### raw_notifications
| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Primary key |
| packageName | TEXT | Source app package |
| title | TEXT | Notification title |
| text | TEXT | Notification text |
| bigText | TEXT | Expanded notification text |
| subText | TEXT | Subtext |
| postedAt | INTEGER | Notification timestamp |
| isRelevant | INTEGER | Relevance flag |

#### scanned_receipts
| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Primary key |
| imagePath | TEXT | Receipt image path |
| rawOcrText | TEXT | Raw OCR text |
| parsedTotal | REAL | Extracted total |
| parsedMerchant | TEXT | Extracted merchant |
| parsedDate | INTEGER | Extracted date |
| parsedItems | TEXT | Extracted line items |
| parsedTaxAmount | REAL | Extracted tax |
| currency | TEXT | Currency code |
| confidence | REAL | OCR confidence |
| expenseId | INTEGER FK | Linked expense |

#### merchant_canonicals
| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Primary key |
| normalizedName | TEXT | Normalized name |
| searchKey | TEXT | Search key |
| categoryId | INTEGER FK | Category |
| totalOccurrences | INTEGER | Occurrence count |
| totalSpent | REAL | Total spent |
| isVerified | INTEGER | Verified flag |

#### merchant_aliases
| Column | Type | Description |
|--------|------|-------------|
| id | INTEGER PK | Primary key |
| rawName | TEXT | Raw merchant name |
| normalizedKey | TEXT | Normalized key |
| canonicalId | INTEGER FK | Canonical merchant |
| occurrenceCount | INTEGER | Occurrences |
| isUserDefined | INTEGER | User-defined flag |

---

## Navigation Flow

### Main Navigation (Bottom Bar)

```
Tab 0: HomeScreen (Dashboard)
    ├── Quick Actions FAB
    ├── Financial Runway Card
    ├── Recent Transactions
    └── Spending Summary

Tab 1: TransactionsScreen (Activity)
    ├── Filter Bar
    ├── Transaction List
    └── Advanced Analytics → Tab 4

Tab 2: ReviewScreen
    ├── Pending Review List
    ├── Approve/Reject Actions
    └── FAB → Approve All

Tab 3: BudgetScreen (Plan)
    ├── Budget List
    ├── Savings Goals
    └── Planned Expenses
```

### Deep Links

| URI Pattern | Action |
|-------------|--------|
| `expensetracker://dashboard` | Navigate to Tab 0 |
| `expensetracker://activity` | Navigate to Tab 1 |
| `expensetracker://review` | Navigate to Tab 2 |
| `expensetracker://plan` | Navigate to Tab 3 |
| `expensetracker://analytics` | Navigate to Tab 4 |
| `expensetracker://add` | Open Add Expense |

### Modal Screens

- **AddExpenseSheet**: Manual expense entry with clipboard detection
- **ReceiptScanScreen**: Camera/gallery receipt scanning
- **RecurringExpensesScreen**: Recurring expense management
- **CategoryScreen**: Category CRUD operations

---

## Android Components

### Application Class

**ExpenseTrackerApp** - Hilt-enabled Application class
- Initializes Timber logging in debug builds
- Sets up StrictMode for thread/VM policy in debug
- Registers ProcessLifecycleOwner observer for cleanup

### LifecycleObserver
- Listens for app lifecycle events
- Triggers TransactionClassifier cleanup when app stops

### NotificationCaptureService

**NotificationListenerService** - Background notification capture

| Feature | Description |
|---------|-------------|
| Deduplication | Thread-safe cache with 500 entry limit |
| Processing Window | 5 second deduplication window |
| Cache Cleanup | Automatic cleanup at 50 entries or 60s |

#### Monitored Packages

| Package | App |
|---------|-----|
| `com.revolut.revolut` | Revolut |
| `com.google.android.apps.walletnfcrel` | Google Wallet |
| `com.google.android.apps.nbu.paisa.user` | Google Pay |
| `gr.nbg.mobilebanking` | National Bank of Greece |
| `com.eurobank.mobile` | Eurobank |
| `gr.alpha.mobile` | Alpha Bank |
| `com.winbank.mobile` | Piraeus Bank |
| `com.viber.voip` | Viber |
| `com.google.android.gm` | Gmail |
| `com.android.mms` | SMS (generic) |
| `com.google.android.apps.messaging` | Google Messages |
| `com.samsung.android.messaging` | Samsung Messages |

#### Ignored Packages
`android`, `com.android.systemui`, `com.android.settings`, `com.whatsapp`, `com.facebook.orca`, `com.instagram.android`, `com.snapchat.android`, `com.google.android.youtube`

### BootReceiver

**BroadcastReceiver** - Starts NotificationCaptureService on device boot

---

## Android Manifest

### Permissions

| Permission | Purpose |
|------------|---------|
| `FOREGROUND_SERVICE` | Keep notification capture running |
| `FOREGROUND_SERVICE_DATA_SYNC` | Foreground service type |
| `POST_NOTIFICATIONS` | Android 13+ notification permission |
| `RECEIVE_BOOT_COMPLETED` | Start on device boot |
| `CAMERA` | Receipt scanning |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Notification access |

### Registered Components

| Component | Type | Description |
|-----------|------|-------------|
| `ExpenseTrackerApp` | Application | Hilt application class |
| `MainActivity` | Activity | Main UI activity |
| `NotificationCaptureService` | Service | NotificationListenerService |
| `BootReceiver` | Receiver | Boot completed receiver |
| `FileProvider` | Provider | Camera photo sharing |

### Intent Filters

**MainActivity:**
- `android.intent.action.MAIN` + `LAUNCHER` category

**NotificationCaptureService:**
- `android.service.notification.NotificationListenerService`

**BootReceiver:**
- `android.intent.action.BOOT_COMPLETED`
- `android.intent.action.MY_PACKAGE_REPLACED`

---

## Key Features

### 1. Automatic Expense Capture

- **NotificationCaptureService**: Captures SMS/banking notifications
- **Parser Pipeline**: Multi-parser routing based on source
- **Confidence Scoring**: Routes low-confidence parses to review queue

### 2. Receipt Scanning

- ML Kit OCR for text extraction
- PDFBox for bank statement parsing
- Automatic merchant/total/date extraction

### 3. Smart Categorization

- **Single Source of Truth**: `CategorizationEngine` → `MerchantCategoryProvider` (1000+ merchants)
- **Hybrid Approach**: Merchant Dictionary → ML Classification → Fallback
- **BK-Tree**: Fuzzy merchant name matching
- **Learning**: User corrections improve both ML model AND merchant dictionary
- **Confidence Routing**: High-confidence → auto-approve, Low-confidence → review
- **All entry points use same classifier**: Bank notifications, receipts, review queue, manual entry

### 4. Budget Management

- Category-based budgets (weekly/monthly/yearly)
- Rollover support
- Warning/Critical notifications at 75%/90%
- Real-time spending pace tracking

### 5. Financial Forecasting

- **Financial runway calculation**: Days remaining based on discretionary budget
- **Block party day prediction**: Daily budget allocation with past/future views
- **Trajectory chart**: Date-based projections with spikes for planned expenses
- **SynthesisEngine**:
  - `MUST` planned expenses: 100% spike on exact date
  - `LIKELY` planned expenses: 70% spike on exact date (weighted)
  - `OPTIONAL` planned expenses: ignored
  - Connected trajectory line (past + projected seamless)
  - Uses `CategorizationEngine` for consistent merchant categorization

### 6. Analytics (Enhanced Mar 2026)

#### Basic Analytics
- Spending by category/time/source
- Trend analysis
- Source statistics
- Custom date ranges

#### Advanced Features (All 6 Implemented)
1. **Anomaly Detection** - Multi-method (MAD, IQR, Contextual, Multiplier)
2. **Cumulative Spending Curve** - Multi-month visualization
3. **Year-over-Year Comparison** - Same month vs prior year
4. **Spending Velocity Anomaly** - Unusually high daily spending
5. **Post-Salary Pattern** - Spending after receiving salary
6. **Duplicate/Error Detection** - Near-duplicates, round amounts, outliers

#### Monte Carlo Forecasting
- 1000-iteration probabilistic simulation
- Log-normal distribution fitted to historical weekly totals
- Percentile bands: P10 (best), P50 (likely), P90 (worst)
- Probability under budget
- Confidence scoring based on data quality

#### Financial Runway
- Days remaining based on discretionary budget
- Daily burn rate calculation
- Committed vs likely expenses tracking

---

## Testing Strategy

### Test Types

| Type | Location | Coverage |
|------|----------|----------|
| Unit Tests | `src/test/java/` | Domain logic, parsers, utilities |
| Android Tests | `src/androidTest/java/` | DAO operations, database |

### Test Examples

- `SmsParserTest` - SMS parsing logic
- `GreekBankParserTest` - Greek bank formats
- `RevolutParserTest` - Revolut parsing
- `InsightsEngineTest` - Analytics calculations
- `BudgetCalculatorTest` - Budget logic
- `HybridExpenseClassifierTest` - ML classification
- `ExpenseDaoTest` - Database operations
- `PendingReviewDaoTest` - Review queue operations

---

## Build Configuration

- **Compile SDK**: 35
- **Min SDK**: 26
- **Kotlin**: 2.0.21
- **Compose BOM**: Latest stable
- **Room**: 2.6.1
- **Hilt**: 2.51.1
- **KSP**: 2.0.21-1.0.27

---

## Notes

- Deep-link support for external integration
- Clipboard detection for quick expense entry
- Haptic feedback throughout UI
- Edge-to-edge display support
- Write-ahead logging journal mode for database
- Migration strategy: Destructive fallback from v1-v5, named migrations v6+
- **Note**: `AnalyticsScreen` now active at Tab 4 with all 6 advanced features (replaced `AdvancedAnalyticsScreen`)

### Recent Architecture Changes (2026)

#### Financial Forecasting (SynthesisEngine)
- **Trajectory Calculation**: Date-based projections with spikes instead of linear averaging
- **Planned Expense Handling**:
  - `MUST` priority: 100% on actual date (full spike)
  - `LIKELY` priority: 70% on actual date (weighted spike via `LIKELY_EXPENSE_WEIGHT = 0.7`)
  - `OPTIONAL` priority: ignored
- **Connected Chart**: Past and projected lines connect seamlessly
- **Error Handling**: Try-catch wrapper with fallback to prevent crashes

#### Categorization Refactor
- **Problem**: `HybridExpenseClassifier` had hardcoded keywords (~30) separate from rich `MerchantCategoryProvider` (1000+)
- **Solution**: `HybridExpenseClassifier` now uses `CategorizationEngine` as single source of truth
- **Result**: Consistent categorization across all entry points (notifications, review, receipts, manual)
- **Learning**: User corrections now persist to both ML model AND merchant dictionary

---

## Recent Fixes & Improvements (February 2026)

### Sprint 1-2: Critical Bugs & Architecture

#### Notification Capture Fixes
| Issue | Fix |
|-------|-----|
| `NotificationCaptureService` timing issue | Refresh now happens in `onListenerConnected()` after listener binds |
| Silent failures in processing | Added try-catch with error logging in `NotificationRepository` |

#### Amount Parsing Improvements (`AmountUtils.kt`)
- Enhanced European format handling (e.g., "1.602,57")
- Proper US format with thousands separator (e.g., "1,602.57")
- Added null/blank validation with logging

#### Day Grouping Bug (`AdvancedAnalyticsEngine.kt`)
- **Issue**: Used `DAY_OF_YEAR` (1-365) - all Jan 1sts grouped together
- **Fix**: Changed to `YEAR-MONTH-DAY` format for correct daily grouping

#### Database Migration Safety (`AppModule.kt`)
- **Issue**: Redundant `fallbackToDestructiveMigration()` after specific fallbacks
- **Fix**: Removed redundant call to prevent accidental data loss

#### Division by Zero Guards
- `ForecastTimeline`: Added guard for `budgetLimit <= 0`
- `ManualExpenseRepository`: Added zero/negative amount validation

#### Architecture: Clean Domain Layer

**NotificationService Interface** - Clean Architecture pattern:
```
domain/service/NotificationService.kt     (interface - no Android deps)
data/service/AndroidNotificationService.kt (implementation)
```
- Domain layer no longer imports Android Context/NotificationManager
- Easier to test (mock interface)
- Follows dependency inversion principle

**Updated Files**:
- `BudgetMonitor` now uses `NotificationService` interface
- `SynthesisEngine` uses Timber instead of android.util.Log

#### Dead Code Cleanup
- Added `@Suppress("UNUSED_PARAMETER")` for intentionally unused params in parsers
- `SmsParser`: unused `subText`
- `GreekBankParser`: unused `subText`, `packageName`

---

### Sprint 4-5: Code Quality

#### Input Validation
- `ManualExpenseRepository`: Zero/negative amount check
- `ReceiptParser`: Increased max amount from 5000 to 50000 for B2B
- `GenericTransactionParser`: Extracted magic numbers to constants

#### UI State Improvements
- `BudgetBlockPartyCard`: Improved null handling for `selectedDay` (removed `!!`)

#### Null Safety
- Fixed potential NPE in parsers with proper null checks

### Build & Test Status
- ✅ All compilations passing
- ✅ All unit tests passing

### Additional Fixes (February 2026)

#### Thread Safety Improvements
- **DateFormatterUtils**: ThreadLocal for SimpleDateFormat, ConcurrentHashMap for DateTimeFormatter
- **TransactionClassifier**: Moved StateFlow emission outside mutex to prevent potential deadlock
- **CurrencyFormatter**: Added ConcurrentHashMap cache for currency formatters

#### Dead Code & Cleanup
- Removed unused `budgetMonitor` injection from `ExpenseRepository`
- Added duplicate check in `MerchantCategoryRepository.learnPattern()`

#### Resource Management
- Fixed ExifInterface handling in ReceiptOcrService

#### Code Quality Improvements (February 2026)
- Removed dead code `classifyWithRules()` from `HybridExpenseClassifier`
- Removed unused import `kotlin.math.sqrt` from `InsightsEngine`

#### Performance Fixes (February 2026)
- **Amount Parsing**: Centralized in `AmountUtils.parseAmount()`, added E-prefix and space handling
- **ReceiptParser**: Now delegates to `AmountUtils.parseAmount()` for consistency
- **SynthesisEngine**: Fixed Calendar instance creation in loop - now reuses single instance
- **SynthesisEngine**: Fixed O(n²) projection loop - now uses running totals for O(n) complexity


### Recent Fixes (March 2026)

| Issue | Fix |
|-------|-----|
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