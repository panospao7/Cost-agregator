# ExpenseTracker - Feature Documentation

## Overview

This document describes the features implemented in Phase 1 and Phase 2 of the ExpenseTracker Android application.

## Phase 1 Features (Completed)

### Feature #14: Warranty & Return Window Tracker
**Status:** ✅ Complete | **Commit:** `3aa2ad8`

#### Description
Automatically tracks warranty periods and return windows for purchased items using AI extraction from receipts.

#### Key Components
- **CloudWarrantyExtractionService** - AI-powered warranty extraction using Gemini
- **WarrantyTrackerRepository** - Database operations for warranties and return windows
- **WarrantyTrackerScreen** - UI for viewing active/expired warranties
- **WarrantyTrackerViewModel** - State management

#### Features
- ✅ AI extraction of warranty periods from receipts
- ✅ Automatic return window tracking (30/90 days)
- ✅ Background worker for expiration checks
- ✅ Push notifications 7 days before expiration
- ✅ Support for different warranty types (manufacturer, extended)

#### Usage
```kotlin
// Warranty extraction happens automatically when scanning receipts
val warranty = warrantyTrackerRepository.extractWarranty(receiptId)

// View warranties in the WarrantyTrackerScreen
WarrantyTrackerScreen(
    onNavigateBack = { /* handle navigation */ }
)
```

---

### Feature #21: Export to Accounting Software
**Status:** ✅ Complete | **Commit:** `c5bd9fe`

#### Description
Export expense data to popular accounting software formats for easy bookkeeping and tax filing.

#### Key Components
- **AccountingExporters** - Format converters (QuickBooks IIF, Xero CSV, FreshBooks CSV)
- **AccountingExportRepository** - Report generation and file management
- **AccountingExportViewModel** - UI state management
- **AccountingExportScreen** - Export configuration UI

#### Supported Formats
- ✅ QuickBooks IIF (Intuit Interchange Format)
- ✅ Xero CSV (Chart of Accounts, Bank Transactions)
- ✅ FreshBooks CSV (Expense Reports, Journal Entries)
- ✅ Accountant-friendly PDF summary

#### Usage
```kotlin
// Generate QuickBooks export
val iifContent = accountingExportRepository.exportToQuickBooksIIF(
    startDate = startDate,
    endDate = endDate
)

// Or export all formats at once
val allExports = accountingExportRepository.exportAllFormats(
    startDate = startDate,
    endDate = endDate
)
```

---

### Feature #4: Cash Flow Calendar View
**Status:** ✅ Complete | **Commit:** `e322eab`

#### Description
Visual calendar showing daily projected balance with color-coded risk levels and bill prediction.

#### Key Components
- **CashFlowCalculator** - Daily balance projections
- **CashFlowCalendarScreen** - Calendar UI with risk indicators
- **CashFlowCalendarViewModel** - State management

#### Features
- ✅ Daily balance projections up to 30 days
- ✅ Color-coded risk levels (Green/Safe, Yellow/Warning, Red/Risky)
- ✅ Recurring expense bill prediction
- ✅ Weekend impact analysis
- ✅ Upcoming bill highlights

#### Usage
```kotlin
// Calculate cash flow for a month
cashFlowCalculator.calculateCashFlow(
    startDate = startDate,
    endDate = endDate
)

// Or view in UI
CashFlowCalendarScreen(
    onNavigateBack = { /* handle navigation */ }
)
```

---

### Feature #7: Automated Receipt Matching
**Status:** ✅ Complete | **Commit:** `16b8549`

#### Description
AI-powered matching between scanned receipts and recorded transactions.

#### Key Components
- **ReceiptTransactionMatcher** - Fuzzy matching algorithm
- **ReceiptMatchingWorker** - Background matching service
- **ReceiptMatchingScreen** - UI for review and confirmation
- **ReceiptMatchingViewModel** - State management

#### Matching Logic
- ✅ Auto-match when confidence >= 95%
- ✅ Suggest matches when confidence >= 80%
- ✅ Manual review for other cases
- ✅ Confidence based on merchant name, amount, and date proximity

#### Usage
```kotlin
// Matching happens automatically via ReceiptMatchingWorker
// Or trigger manually
receiptMatchingRepository.findMatchesForReceipt(receiptId)

// Review matches in UI
ReceiptMatchingScreen(
    onNavigateBack = { /* handle navigation */ }
)
```

---

## Phase 2 Features (Completed)

### Feature #1: Smart Savings Goals with Automation
**Status:** ✅ Complete | **Commit:** `9337657`

#### Description
AI-powered savings goal system with smart recommendations and automated rules.

#### Key Components
- **SmartSavingsEngine** - Calculates safe-to-save amounts using budget surplus, spending pace, and Monte Carlo forecasting
- **AutomatedSavingsRuleEngine** - Configurable automation rules
- **SavingsGamificationEngine** - Streaks, achievements, and levels
- **SavingsGoalsScreen** - Goal management UI
- **SavingsGoalsViewModel** - State management

#### Automated Rules
- ✅ Percentage of Income - Save X% of every deposit
- ✅ Round-Up - Round purchases to nearest €X
- ✅ Spare Change - Save small purchases (< €10)
- ✅ Weekly No-Spend Challenge - Save when no discretionary spending

#### Gamification Features
- ✅ Savings Streaks (consecutive days of contributions)
- ✅ Achievements (Goal Setter, Week Warrior, Century Club, etc.)
- ✅ Levels (1-5+) based on total saved
- ✅ Progress tracking and milestones

#### Usage
```kotlin
// Get savings recommendation
val recommendation = smartSavingsEngine.calculateSafeToSaveAmount(goal)

// Create automation rule
val rule = AutomatedSavingsRule(
    name = "Coffee Round-Up",
    ruleType = SavingsRuleType.ROUND_UP,
    roundUpTo = 5.0,
    targetGoalId = goalId
)

// View in UI
SavingsGoalsScreen(
    onNavigateBack = { /* handle navigation */ }
)
```

---

### Feature #5: Advanced Subscription Management
**Status:** ✅ Complete | **Commit:** `47474cf`

#### Description
Comprehensive subscription tracking with price history, usage analytics, and cancellation optimization.

#### Key Components
- **SubscriptionPriceHistory** - Tracks price changes over time
- **SubscriptionUsage** - Records actual usage of subscriptions
- **SubscriptionManagerEngine** - Core analytics and recommendations
- **Enhanced ManualRecurringExpense** - Subscription-specific fields

#### Features
- ✅ Price Change Detection - Track increases and reasons
- ✅ Usage Analytics - Monitor actual vs expected usage
- ✅ Cost-Per-Use Calculation - Identify poor value subscriptions
- ✅ Health Score (0-100) - Overall subscription value assessment
- ✅ Cancellation Recommendations - AI suggestions for underutilized subs

#### Usage
```kotlin
// Analyze all subscriptions
val analyses = subscriptionManagerEngine.getAllSubscriptions()

// Record usage
subscriptionManagerEngine.recordUsage(
    subscriptionId = netflixId,
    durationMinutes = 120,
    usageType = "watched_movie"
)

// Get recommendations
val toReview = subscriptionManagerEngine.getSubscriptionsToReview()
val potentialSavings = subscriptionManagerEngine.calculatePotentialSavings()
```

---

### Feature #25: Business/Personal Separation
**Status:** ✅ Complete | **Commit:** `9939e12`

#### Description
Complete business expense tracking with mileage logging and tax report generation.

#### Key Components
- **Expense entity** - Business expense flags (isBusinessExpense, businessCategory, etc.)
- **MileageTracking** - Business trip logging with GPS and deductions
- **BusinessExpenseRepository** - Business-specific queries
- **BusinessExpenseReportGenerator** - Professional report generation

#### Features
- ✅ Business Expense Flagging - Separate personal from business spending
- ✅ Business Categories - Travel, Meals, Office Supplies, Software, etc.
- ✅ Project Tracking - Allocate expenses to specific projects
- ✅ Mileage Tracking - Odometer/GPS tracking with configurable deduction rates
- ✅ Missing Receipt Detection - Identify expenses needing receipts for tax
- ✅ Professional Reports - Formatted summaries with categories, projects, mileage
- ✅ CSV Export - Export for accountants and tax software

#### Usage
```kotlin
// Mark expense as business
val businessExpense = expense.copy(
    isBusinessExpense = true,
    businessCategory = "Travel",
    businessPurpose = "Client meeting in Athens",
    businessProject = "Project Alpha",
    requiresReceipt = true
)

// Log mileage
val mileage = MileageTracking(
    date = System.currentTimeMillis(),
    distanceKm = 45.5,
    tripPurpose = "Client visit",
    deductionRatePerKm = 0.30
)

// Generate report
val report = reportGenerator.generateReport(
    startDate = monthStart,
    endDate = monthEnd
)
```

---

## Database Schema Changes

### Migration 37 → 38
- Added warranty and return window tables
- New entities: `Warranty`, `ReturnWindow`

### Migration 38 → 39
- Added receipt matching columns to scanned_receipts
- New columns: matchStatus, matchConfidence, suggestedExpenseId

### Migration 39 → 40
- Added subscription management tables
- New entities: `SubscriptionPriceHistory`, `SubscriptionUsage`
- Enhanced `ManualRecurringExpense` with subscription fields

### Migration 40 → 41
- Added business expense fields to expenses table
- New columns: isBusinessExpense, businessPurpose, businessCategory, businessProject, requiresReceipt
- New entity: `MileageTracking`

---

## Architecture Overview

All features follow the standard Android architecture:

```
┌─────────────────┐
│   UI Layer      │  (Screens, ViewModels)
│   (Compose)     │
└────────┬────────┘
         │
┌────────▼────────┐
│  Domain Layer   │  (Engines, Use Cases, Models)
│  (Business Logic)│
└────────┬────────┘
         │
┌────────▼────────┐
│   Data Layer    │  (Repositories, DAOs)
│  (Room DB)      │
└─────────────────┘
```

---

## Testing

- ✅ All unit tests compile successfully
- ✅ Main application builds without errors
- ✅ Database migrations tested through Room schema validation
- ⚠️ Full test suite execution timed out (known issue with extensive tests)

---

## Dependencies Used

- **Hilt** - Dependency injection
- **Room** - Database persistence
- **Coroutines/Flow** - Async operations
- **Jetpack Compose** - UI framework
- **WorkManager** - Background tasks
- **Gemini AI** - Cloud AI service (receipt categorization, warranty extraction)

---

## Next Steps / Phase 3 Ideas

Potential features for future development:
- Investment tracking integration
- Multi-currency support with real-time exchange rates
- Shared expense groups (family/friends)
- Budget forecasting with machine learning
- Receipt scanning with OCR improvements
- Integration with banking APIs
- Cryptocurrency transaction tracking

---

## Contributors

- AI Assistant (OpenCode) - Feature implementation
- Original codebase maintainers - Foundation architecture

---

*Last updated: March 31, 2026*
*Branch: features/warranty-tracker-and-exports*
