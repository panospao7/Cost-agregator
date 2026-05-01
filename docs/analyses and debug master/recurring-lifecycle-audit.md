# Recurring / Planned / Reminder Lifecycle Audit

## Summary Statistics

- **Recurring pattern files (domain/logic):** 8 (RecurringPattern.kt, RecurrenceCalculator.kt, RecurringExpenseEngine.kt, MergedRecurringPatternsProvider.kt, ForecastInputAssembler.kt, NotificationSubscriptionDetector.kt, RecurringIncomeTracker.kt, SubscriptionManagerEngine.kt)
- **Recurring entity/DAO/repository files (data):** 7 (ManualRecurringExpense.kt, ManualRecurringExpenseDao.kt, RecurringExpenseDao.kt [deprecated], RecurringExpenseRepository.kt, ManualRecurringExpenseRepository.kt, SubscriptionCandidate.kt, SubscriptionCandidateDao.kt)
- **Planned expense files:** 4 (PlannedExpense.kt [entity], PlannedExpense.kt [domain model], PlannedExpenseDao.kt, PlannedExpenseRepository.kt)
- **Reminder files:** 3 (BillReminderManager.kt, BillRemindersScreen.kt, BillRemindersViewModel.kt)
- **Subscription files:** 7 (SubscriptionManagementEngine.kt, SubscriptionManagementRepository.kt, SubscriptionManagementScreen.kt, SubscriptionManagementViewModel.kt, SubscriptionCandidate.kt, SubscriptionCandidateDao.kt, SubscriptionPriceHistory.kt, SubscriptionUsage.kt, SubscriptionPriceHistoryDao.kt, SubscriptionUsageDao.kt, NotificationSubscriptionDetector.kt, SmartBillNegotiationEngine.kt)
- **Forecast/cashflow files consuming recurring data:** 12 (ForecastInputAssembler.kt, SynthesisEngine.kt, MergedRecurringPatternsProvider.kt, CashFlowCalculator.kt, FinancialStressForecastEngine.kt, CalculateFinancialForecastUseCase.kt, FinancialWeatherRepository.kt, SmartSavingsEngine.kt, MonthlySavingsSweepUseCase.kt, BudgetForecastingEngine.kt, MonteCarloSpendingSimulator.kt, HistoricalSpendingDistribution.kt)
- **UI screens consuming recurring data:** 5 (RecurringExpensesScreen.kt, ManualRecurringExpenseScreen.kt, ManualRecurringExpenseViewModel.kt, SubscriptionManagementScreen.kt, CashFlowCalendarScreen/ViewModel.kt)
- **Direct DAO leaks:** 2 (ManualExpenseRepository.kt line 198, SmartBillNegotiationEngine.kt line 124)
- **Total files involved (main source):** ~38 unique `.kt` files
- **Duplicate reminder detection:** ZERO — no dedup logic exists
- **Occurrence expansion / dedup:** ZERO — no centralized occurrence expansion exists

---

## 1. Recurring Pattern Detection & Storage

### 1.1 RecurringPattern Domain Model

**File:** `domain/model/RecurringPattern.kt`

**Fields:**
| Field | Type | Description |
|---|---|---|
| `merchantName` | String | Normalized merchant name |
| `averageAmount` | Double | Mean transaction amount |
| `currency` | String | Currency code (e.g. "EUR") |
| `frequency` | RecurrenceFrequency | Enum: WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, SEMI_ANNUALLY, ANNUALLY, IRREGULAR |
| `periodVarianceDays` | Int | Expected variation in days (e.g. ±2) |
| `amountVariancePercent` | Double | Coefficient of variation (e.g. 0.05 = 5%) |
| `nextExpectedDate` | Long | Epoch millis of predicted next occurrence |
| `confidence` | Float | 0.0–1.0 confidence score |
| `previousDates` | List<Long> | Last 5 dates for debug/UI |
| `categoryId` | Long? | Optional category association |
| `id` | Long? | FK to ManualRecurringExpense if confirmed |

**RecurrenceFrequency enum** has deprecated `days` property (sentinel values: WEEKLY=7, BIWEEKLY=14, MONTHLY=30, QUARTERLY=90, SEMI_ANNUALLY=180, ANNUALLY=365, IRREGULAR=0). The enum also has `fixedIntervalDays`, `calendarMonths`, and `isIrregular` helpers.

### 1.2 ManualRecurringExpense Entity

**File:** `data/database/entity/ManualRecurringExpense.kt`

**Fields:**
| Field | Type | Default |
|---|---|---|
| `id` | Long (PK, autoGenerate) | 0 |
| `merchant` | String | — |
| `amount` | Double | — |
| `currency` | String | "EUR" |
| `frequency` | RecurrenceFrequency | — |
| `nextDate` | Long | — |
| `note` | String? | null |
| `createdAt` | Long | 0L (sentinel — must be set to `timeProvider.now()`) |
| `isSubscription` | Boolean | false |
| `subscriptionCategory` | String? | null |
| `usageTargetPerMonth` | Int? | null |
| `cancellationUrl` | String? | null |
| `isActive` | Boolean | true |

**Indices:** `(isActive, nextDate)`, `(isSubscription, isActive, nextDate)`, `(merchant)`

### 1.3 DAO Layer — TWO redundant DAOs

**Primary DAO:** `ManualRecurringExpenseDao` (73 lines)
- Reactive: `getAllFlow()`, `getAllActiveFlow()`, `getAllActiveSubscriptionsFlow()`, `getByIdFlow()`
- One-shot: `getAll()`, `getAllActive()`, `getAllActiveSubscriptions()`, `getById()`, `getByMerchant()`
- CRUD: `insert`, `update`, `delete`, `deleteById`
- Status: `setActiveStatus()`, `updateNextDate()`
- Stats: `getActiveCount()`, `getExpensesDueBefore()`, `getExpensesDueBeforeFlow()`

**Deprecated DAO:** `RecurringExpenseDao` (77 lines) — marked `@Deprecated("Use ManualRecurringExpenseDao instead")`. Both DAOs are injected and exposed via DI. The deprecated one is still wired in DI and used by `RecurringExpenseRepository`.

### 1.4 Repository Layer — TWO redundant repositories

**RecurringExpenseRepository** (90 lines) — wraps deprecated `RecurringExpenseDao`. Used by `BillReminderManager`, `RecurringExpenseEngine`, `MergedRecurringPatternsProvider`, `RecurringExpensesViewModel`, `FinancialWeatherRepository`, `CalculateFinancialForecastUseCase`, `MonthlySavingsSweepUseCase`.

**ManualRecurringExpenseRepository** (29 lines) — wraps `ManualRecurringExpenseDao`. Used by `ManualRecurringExpenseViewModel`.

Both have similar `getAll()` methods but they call different DAOs, both filtered to active-only (`isActive = 1`).

### 1.5 Pattern Detection Engine

**File:** `domain/logic/RecurringExpenseEngine.kt`

**`getPatterns()` method:**
1. Fetch 12 months of expense snapshots → `ExpenseSnapshot`
2. Fetch manual recurring rows from `RecurringExpenseRepository`
3. Convert manual rows to `RecurringPattern` (confidence=1.0f)
4. Call `detectPatternsFromSnapshots()` with manual merchant keys excluded
5. Merge and sort by descending confidence

**`detectPatternsFromSnapshots()` algorithm:**
- Filters to PURCHASE only
- Groups by canonical merchant key
- Requires ≥3 transactions
- Amount stability: CV must be ≤40%
- Interval analysis via `determineFrequency()` which:
  - Computes calendar-day intervals between consecutive dates
  - Finds mode interval
  - Maps mode to frequency using ranges: 3-11d→WEEKLY, 12-22d→BIWEEKLY, 23-45d→MONTHLY, 46-135d→QUARTERLY, 136-270d→SEMI_ANNUALLY, 271-400d→ANNUALLY
  - Calculates consistency score (matching intervals / total)
- Staleness filter: drops patterns with last occurrence >6 months ago
- Predicts next date via `RecurrenceCalculator.addFrequencyInterval()`

### 1.6 Notification-Based Subscription Detection

**File:** `domain/subscription/NotificationSubscriptionDetector.kt`

Independent pattern detection for notification-sourced transactions. Uses `canonicalMerchant` grouping, requires ≥3 transactions, uses amount CV ≤40%, and interval analysis with similar frequency ranges. Produces `SubscriptionCandidateResult` with confidence, which gets stored as `SubscriptionCandidate` entity.

---

## 2. Recurrence Calculation & Expansion

### 2.1 RecurrenceCalculator

**File:** `domain/logic/RecurrenceCalculator.kt`

**Key methods:**
- `normalizeToDateOnly(timestamp)` — snaps to midnight via `TimePeriodUtils.getStartOfDay()`
- `monthlyMultiplier(frequency)` — canonical multipliers: WEEKLY→4.33, BIWEEKLY→2.17, MONTHLY→1.0, QUARTERLY→1/3, SEMI_ANNUALLY→1/6, ANNUALLY→1/12, IRREGULAR→1.0
- `toMonthlyAmount(amount, frequency)` — `amount * monthlyMultiplier(frequency)`
- `fromMonthlyAmount(monthly, frequency)` — inverse
- `calculateNextDate(currentDate, frequency)` — calls `addFrequencyInterval()` forward
- `calculatePreviousDate(currentDate, frequency)` — backward
- `addFrequencyInterval(baseDate, frequency, forward=true)` — uses `fixedIntervalDays` for WEEKLY/BIWEEKLY (simple day add), uses `calendarMonths` for MONTHLY+ (calendar-aware via `TimePeriodUtils.addMonths()`)
- `isDue(nextDueDate, referenceDate)` — simple comparison
- `isUpcoming(nextDueDate, daysWithin, referenceDate)` — checks window
- `toAnnualAmount(monthlyAmount)` — `monthlyAmount * 12`

### 2.2 RecurringExpenseEngine (Pattern Detection)

Already documented in §1.5. It detects patterns but does NOT expand them into occurrences.

### 2.3 Occurrence Expansion — The Critical Gap

**There is NO centralized occurrence expansion system.** Occurrence expansion is done ad-hoc in at least three places:

1. **`FinancialStressForecastEngine.calculateRecurringOutflows()`** (line 225-260) — Manual while-loop expanding `nextDate` using hardcoded day multiples for WEEKLY/BIWEEKLY and `TimePeriodUtils.addMonths()` for others.

2. **`SynthesisEngine.isRecurringExpected()`** (line 426-484) — For each day in the month, tests whether a pattern falls on that day by checking day-of-week match (WEEKLY), modulo-based biweekly match, day-of-month match (MONTHLY), or day+month quarter boundary (QUARTERLY+). This is block-party day-matching logic.

3. **`SynthesisEngine.synthesizeInternal()`** (line 105-282) — Filters patterns by `nextExpectedDate` within month range to calculate committed/likely totals. Does not expand; uses single-date matching.

4. **`CashFlowCalculator.calculateDailyCashFlow()`** (line 110-118) — For each day, checks if any pattern's `nextExpectedDate` falls within that day. Single-day matching only — no expansion.

### 2.4 ForecastHorizon Enum

**File:** `domain/model/FinancialForecast.kt`

```
NEXT_7_DAYS  (fixedDays=7, kind=FIXED_DAYS)
NEXT_30_DAYS (fixedDays=30, kind=FIXED_DAYS)
REST_OF_MONTH (kind=REST_OF_MONTH, calendar-bound)
```

The deprecated `days` property throws for REST_OF_MONTH.

---

## 3. Planned Expenses

### 3.1 PlannedExpense Entity

**File:** `data/database/entity/PlannedExpense.kt`

**Fields:**
| Field | Type | Default |
|---|---|---|
| `id` | Long (PK, autoGenerate) | 0 |
| `description` | String | — |
| `amount` | Double | — |
| `currency` | String | "EUR" |
| `currencyAssumption` | String | "LEGACY_DEFAULT" |
| `date` | Long | — (planned date) |
| `categoryId` | Long? | null (FK→Category) |
| `isRecurring` | Boolean | false |
| `priority` | PlannedExpensePriority | LIKELY |
| `createdAt` | Long | 0L (sentinel) |

**Priority enum:** MUST, LIKELY, OPTIONAL

**Indexes:** `(date)`, `(categoryId)`

### 3.2 Domain PlannedExpense Model

**File:** `domain/model/PlannedExpense.kt`

Mirrors entity with validation: description not blank, amount positive finite.

### 3.3 PlannedExpenseDao

**File:** `data/database/dao/PlannedExpenseDao.kt`

- `getAllPlannedExpenses()` — Flow, ordered by date
- `getPlannedExpensesForPeriod(startMs, endMs)` — Flow
- `insertPlannedExpense()` — OnConflict.REPLACE
- `deletePlannedExpense()` — by entity
- `deletePlannedExpenseById()` — by id

**Missing:** Update method, getById, getByIdFlow, any recurring-specific queries.

### 3.4 PlannedExpenseRepository

**File:** `data/repository/PlannedExpenseRepository.kt`

Simple wrapper over DAO, adds no business logic. Exposes all DAO methods.

### 3.5 Planned Expense Creation

Planned expenses are:
- Created manually by user via UI (PlannedExpenseItem in RecurringExpensesScreen)
- No automated creation from recurring patterns exists
- No recurring→planned conversion pipeline

### 3.6 Planned vs Actual — ZERO Comparison

Searching for `plannedVsActual`, `planned.*actual`, `plannedVsReal`, `plannedVsSpent` — **no results found**. There is no code anywhere that compares planned expenses against actual expenses to detect drift.

---

## 4. Reminders & Notifications

### 4.1 BillReminderManager

**File:** `domain/reminder/BillReminderManager.kt`

**Key models:**
```kotlin
data class BillReminder(
    val recurringExpenseId: Long,
    val merchant: String,
    val amount: Double,
    val currency: String,
    val dueDate: Long,
    val daysUntilDue: Int,
    val isOverdue: Boolean,
    val urgency: ReminderUrgency  // INFO, WARNING, URGENT, CRITICAL
)
```

**Key methods:**
- `getUpcomingReminders(daysAhead=14)` — Reads all active recurring from `RecurringExpenseRepository`, filters by nextDate within window, computes urgency based on days until due.
- `getNotificationsDue()` — Filters `getUpcomingReminders(7)` by urgency rules (CRITICAL always, URGENT when daysUntilDue=1..2, WARNING when daysUntilDue=3).
- `markBillPaid(recurringExpenseId)` — Calculates nextDate via `RecurrenceCalculator.calculateNextDate()` and updates the expense.
- `getMonthlyBillsTotal()` — Sums `RecurrenceCalculator.toMonthlyAmount()` for all active recurring expenses.

### 4.2 Reminder Scheduling — NO Scheduled Notifications

Searching for `AlarmManager.*remind`, `WorkManager.*remind`, `scheduleBillReminder`, `scheduleNotification.*reminder` — **no results found**.

The `NotificationCaptureService` uses `AlarmManager` only for service restart (heartbeat), not for bill reminders. The `AiWorkScheduler`/`AiWorkSchedulerImpl` uses `WorkManager` only for daily AI briefing, not bill reminders.

**There is no mechanism to proactively push bill reminder notifications.** The `BillReminderManager.getNotificationsDue()` method exists but is never called by any scheduling infrastructure.

### 4.3 BillRemindersScreen & ViewModel

**Screen:** Shows a list of `BillReminder` items with urgency-colored cards, monthly total, and "Mark Paid" / "Pay Now" buttons.
**ViewModel:** Calls `billReminderManager.getUpcomingReminders()` and `getMonthlyBillsTotal()` in init. `markBillPaid()` calls `BillReminderManager.markBillPaid()`.

### 4.4 Duplicate Reminders — NO Dedup

- No `duplicate.*reminder` or `reminder.*duplicate` code exists.
- The `BillReminderManager.getUpcomingReminders()` generates reminders on-the-fly each time from the `ManualRecurringExpense` table with no persistence of which reminders have been sent.
- No `reminderState`, `lastSentAt`, `dismissedAt`, `snoozedUntil` fields exist on any reminder-specific model.
- The `dismissedAt` fields found in the DB are for recommendations and anomaly alerts, NOT for bill reminders.

### 4.5 NotificationIdGenerator

**File:** `domain/util/NotificationIdGenerator.kt`

Has a comment about generating notification IDs for bill reminders but does not integrate with any bill reminder scheduling.

---

## 5. Subscriptions

### 5.1 Subscription Models

**SubscriptionCandidate entity** — stores detected subscription candidates from notifications:
- `merchant`, `canonicalMerchant`, `averageAmount`, `currency`
- `detectedInterval` (String: "weekly", "monthly", etc.)
- `confidence` (Double), `transactionCount`
- `firstSeen`, `lastSeen`
- `estimatedAnnualCost` (computed via days-per-year math)
- `isConverted`, `convertedSubscriptionId`, `userAction`

**SubscriptionUsage entity** — tracks usage events per subscription.
**SubscriptionPriceHistory entity** — tracks price changes per subscription.

### 5.2 SubscriptionManagerEngine

**File:** `domain/subscription/SubscriptionManagerEngine.kt`

Full subscription analysis engine that merges recurring data with usage/price history. Key methods:
- `getAllSubscriptions()` → `List<SubscriptionAnalysis>`
- `analyzeSubscription()` → price history + usage stats + recommendations + health score
- `getTotalMonthlySubscriptionCost()` — sums `currentPrice` directly (NOT using `toMonthlyAmount()` — potential inconsistency for non-monthly frequencies)
- `recordUsage()`, `recordPriceChange()`

**BUG:** `getTotalMonthlySubscriptionCost()` sums `analysis.currentPrice` directly without normalizing to monthly via `RecurrenceCalculator.toMonthlyAmount()`. If a subscription is QUARTERLY at €90, it would count as €90 instead of €30/month.

### 5.3 Subscription Math — Direct DAO Access

**SubscriptionManagementRepository** wraps `ManualRecurringExpenseDao` but is a separate path from `ManualRecurringExpenseRepository`.

**`SubscriptionManagementViewModel.calculateCostPerUse()`** (line 163-171) has its own hardcoded monthly normalization for QUARTERLY (÷3), SEMI_ANNUALLY (÷6), ANNUALLY (÷12) — duplicating `RecurrenceCalculator.toMonthlyAmount()` logic.

### 5.4 SmartBillNegotiationEngine — DIRECT DAO LEAK

**File:** `domain/negotiation/SmartBillNegotiationEngine.kt`

**Line 124:** `val subscriptions = recurringExpenseDao.getAll()` — directly accesses `ManualRecurringExpenseDao` instead of going through a repository. This is a direct DAO leak from the domain layer.

---

## 6. Forecast & Cashflow Dependencies

### 6.1 ForecastInputAssembler

**File:** `domain/forecasting/ForecastInputAssembler.kt`

**`mergeRecurringPatterns()` method** — critical dedup logic:
1. Convert manual entities to `RecurringPattern` via `mapConfirmedRecurringPatterns()`
2. Deduplicate manual patterns by rule signature: `(merchantKey, frequency, amountMinor, currency)`
3. Filter detected patterns to confidence ≥0.70 (HIGH_CONFIDENCE_THRESHOLD)
4. Remove detected patterns whose signature matches any manual pattern
5. Merge and sort by descending confidence

**`assemble()` method** — assembles complete `ForecastInput` with:
- Normalized expense snapshots
- Merged recurring patterns (manual + high-confidence detected)
- Planned expenses
- Savings goals
- Budget statuses
- Spending pace

**`mapConfirmedRecurringPatterns()`** — converts `ManualRecurringExpense` → `RecurringPattern`, rolling nextExpectedDate forward if overdue.

### 6.2 MergedRecurringPatternsProvider

**File:** `domain/forecasting/MergedRecurringPatternsProvider.kt`

Orchestrator that provides:
- `getConfirmedPatterns()` — manual recurring only, deduped and sorted
- `getPatterns()` — detected + manual merged via `ForecastInputAssembler.mergeRecurringPatterns()`

### 6.3 SynthesisEngine

**File:** `domain/logic/SynthesisEngine.kt`

**`synthesize()`** — produces `FinancialForecast`:
- Committed = patterns with confidence ≥0.90 + MUST planned expenses
- Likely = patterns with 0.70 ≤ confidence < 0.90 + LIKELY planned (×0.7)
- `monthlyRecurringTotal` = sum of `toMonthlyAmount()` for all patterns (excluding IRREGULAR)
- Discretionary = budget - spent - committed - likely - goal reserves

**`calculateBlockPartyData()`** — daily budget tracking:
- For each day of month, checks if recurring pattern falls on that day via `isRecurringExpected()`
- Priority weighting: MUST=100%, LIKELY=70%, OPTIONAL=0%

**`isRecurringExpected()`** — day-matching logic:
- WEEKLY: same day-of-week
- BIWEEKLY: simple modulo check with 2-day tolerance
- MONTHLY: same day-of-month (clamped to month length)
- QUARTERLY: day match + month diff % 3 == 0
- SEMI_ANNUALLY: day match + month diff % 6 == 0
- ANNUALLY: day match + same month

### 6.4 CashFlowCalculator

**File:** `domain/cashflow/CashFlowCalculator.kt`

Uses `MergedRecurringPatternsProvider.getConfirmedPatterns()`:
- For each day, checks if any pattern's `nextExpectedDate` falls within that day
- Only checks single date — no multi-occurrence expansion
- If a pattern's nextExpectedDate falls on a day, adds `pattern.averageAmount` to that day's expenses

**`getUpcomingBills(daysAhead)`** — filters confirmed patterns whose `nextExpectedDate` falls within [startOfToday, endOfToday+daysAhead].

### 6.5 FinancialStressForecastEngine

**File:** `domain/forecasting/FinancialStressForecastEngine.kt`

**`computeStressForecast()`** — for 30/60/90 day horizons:
- Gets confirmed patterns via `MergedRecurringPatternsProvider`
- Calls `calculateRecurringOutflows()` — **the only place that expands recurring into multiple occurrences within a date range**
- **BUG in expansion:** Uses hardcoded day multipliers: WEEKLY→7×DAY_IN_MILLIS, BIWEEKLY→14×DAY_IN_MILLIS. This is WRONG for calendar-aware recurrence (doesn't handle month boundaries, DST, etc.). MONTHLY+ uses `TimePeriodUtils.addMonths()` which is correct.
- Runs Monte Carlo for discretionary spending
- Computes crunch probability (P(balance < 0))

### 6.6 BudgetForecastingEngine

**File:** `domain/budget/BudgetForecastingEngine.kt`

Does NOT use recurring patterns. Uses pure historical monthly spending totals from SQL aggregation. Independent of the recurring/planned system.

### 6.7 MonteCarloSpendingSimulator

**File:** `domain/forecasting/MonteCarloSpendingSimulator.kt`

Takes `knownUpcoming` as a parameter (deterministic obligations from SynthesisEngine). Does not directly query recurring data. It receives the pre-computed committed+likely totals.

### 6.8 CalculateFinancialForecastUseCase

**File:** `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`

Orchestrates the full forecast pipeline:
1. Combines expenses, budget statuses, recurring entities, planned entities, savings goals into `ForecastSourceData`
2. Maps expenses to snapshots
3. Gets confirmed patterns via `MergedRecurringPatternsProvider`
4. Calls `ForecastInputAssembler.assemble()` with `manualRecurringEntities = emptyList()` (patterns already confirmed in step 3)
5. Calls `SynthesisEngine.synthesize()`

**NOTE:** Both `FinancialWeatherRepository` and `CalculateFinancialForecastUseCase` implement nearly identical pipelines — this is duplication.

---

## 7. Smart Savings & Health Dependencies

### 7.1 SmartSavingsEngine

**File:** `domain/savings/SmartSavingsEngine.kt`

Does NOT directly query recurring patterns. Uses:
- Expense repository for historical data
- Budget surplus from `BudgetRepository`
- Monte Carlo simulation results
- Spending pace analysis

Recurring obligations are factored implicitly through the budget system (budgets already account for known commitments).

### 7.2 FinancialHealthCalculator

**File:** `domain/health/FinancialHealthCalculator.kt`

Does NOT use recurring patterns directly. Calculates health scores from:
- Normalized expenses (spending control)
- Budget statuses (budget health)
- Pending reviews (cleanliness)
- Streaks (bonus points)

No reference to RecurringPattern, ManualRecurringExpense, or recurrence at all.

### 7.3 RecurringIncomeTracker

**File:** `domain/income/RecurringIncomeTracker.kt`

Similar pattern detection logic for income (DEPOSIT transactions). Uses `ExpenseDao` directly (DAO leak) via `getExpensesByTypeBetweenUncapped()`. Independent of expense recurring system.

---

## 8. Anti-Patterns & Gaps

### 8.1 Identified Anti-Patterns

#### AP-1: TWO Redundant DAOs for Recurring Expenses
`RecurringExpenseDao` is deprecated but still wired in DI and actively used by `RecurringExpenseRepository`. `ManualRecurringExpenseDao` is the replacement. Both are injectable. This creates confusion about which DAO/repository to use.

#### AP-2: TWO Redundant Repositories
`RecurringExpenseRepository` (wraps deprecated DAO) has 90 lines. `ManualRecurringExpenseRepository` (wraps new DAO) has 29 lines. They coexist with overlapping interfaces.

#### AP-3: Direct DAO Access from Domain Layer
- `SmartBillNegotiationEngine` injects `ManualRecurringExpenseDao` directly (line 124: `recurringExpenseDao.getAll()`)
- `ManualExpenseRepository` accesses `database.recurringExpenseDao()` directly (line 198) to insert recurring expenses outside the repository
- `RecurringIncomeTracker` injects `ExpenseDao` directly

#### AP-4: Hardcoded Period Math in Multiple Places
- `SubscriptionManagementViewModel.calculateUsageAndCostPerUse()` — hardcoded monthly normalization for QUARTERLY/ANNUALLY (lines 163-171)
- `FinancialStressForecastEngine.calculateRecurringOutflows()` — hardcoded day multipliers for WEEKLY/BIWEEKLY (lines 248-249)
- `NotificationSubscriptionDetector` — static `DAYS_IN_WEEK=7`, `DAYS_IN_MONTH=30`, `DAYS_IN_QUARTER=90`, `DAYS_IN_YEAR=365` constants
- `SubscriptionManagementViewModel.acceptCandidate()` — hardcoded day multipliers for nextDate calculation (lines 344-352)
- `SmartSavingsEngine` — `DEFAULT_CAP_WEEK=75`, `DEFAULT_CAP_MONTH=200`, `DAY_IN_MILLIS` etc.

#### AP-5: Two Nearly Identical Forecast Pipelines
`FinancialWeatherRepository.getFinancialWeather()` and `CalculateFinancialForecastUseCase.invoke()` both:
- Fetch expenses, budget statuses, recurring, planned, goals
- Map to snapshots
- Get confirmed patterns
- Call `ForecastInputAssembler.assemble()`
- Call `SynthesisEngine.synthesize()`

#### AP-6: Subscription Cost Calculation Ignores Frequency Normalization
`SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()` sums `analysis.currentPrice` directly without calling `RecurrenceCalculator.toMonthlyAmount()`. Non-monthly subscriptions will report incorrect monthly totals.

### 8.2 Critical Gaps

#### GAP-1: No Centralized Occurrence Expansion
No `RecurringOccurrenceCoordinator` exists. Each consumer implements its own occurrence logic:
- `FinancialStressForecastEngine` — while-loop expansion with hardcoded multipliers
- `SynthesisEngine.isRecurringExpected()` — day-by-day matching
- `CashFlowCalculator` — single-nextExpectedDate matching
- `BillReminderManager` — single-nextDate matching

#### GAP-2: No Reminder Persistence / State Tracking
- No reminder state machine (pending/sent/dismissed/snoozed)
- No `lastSentAt` tracking
- No dedup of reminder notifications
- `getNotificationsDue()` exists but is never called by any scheduler

#### GAP-3: No Reminder Scheduling Infrastructure
- No `WorkManager` or `AlarmManager` integration for bill reminders
- No periodic reminder check
- No boot receiver for reminder re-scheduling

#### GAP-4: No Planned vs Actual Drift Detection
Zero code compares planned expenses to actual expenses. Users cannot see if their planned budget matches reality.

#### GAP-5: No Recurring → Planned Conversion
Planned expenses are never automatically generated from recurring patterns. The `isRecurring` flag on `PlannedExpense` is never set to `true` anywhere in the codebase.

#### GAP-6: No Occurrence Identity
Occurrences are ephemeral — computed on-the-fly from the recurrence rule. There is no persistent occurrence record with its own ID, status (pending/paid/skipped), or modification history.

#### GAP-7: No Duplicate Detection in Occurrence Expansion
When `FinancialStressForecastEngine` expands patterns across 90 days, it does not check whether the expanded date already has an actual expense recorded. This means forecast double-counts if the user also has planned or actual entries for the same merchant on that date.

#### GAP-8: MonthlySubscriptionsSweepUseCase Has Its Own Recurring Calculation
`MonthlySavingsSweepUseCase.calculateKnownUpcomingObligations()` directly queries `recurringExpenseRepository.getAllFlow().first()` and filters by `nextDate` — but this only catches expenses due in the current month, NOT expanded occurrences of longer-interval patterns.

#### GAP-9: Forecast Double-Counting Risk
The `ForecastInputAssembler.assemble()` pipeline includes BOTH:
- Confirmed recurring patterns (from `getConfirmedPatterns()`)
- Planned expenses (from `PlannedExpenseRepository`)

If a user creates a planned expense for the same merchant/amount/date as a recurring pattern, it will be counted twice in `SynthesisEngine.synthesizeInternal()` — once in `committedUpcomingBills` and once in `committedPlanned`.

#### GAP-10: `calculateRecurringOutflows()` Doesn't Deplete Occurrences
When `FinancialStressForecastEngine` expands a recurring pattern across 90 days, it doesn't check if an actual expense already exists for any of those expanded dates. The outflows are purely theoretical, which is correct for future forecasting but risks double-counting if run against a period with actuals.

---

## 9. Direct DAO Access Inventory

| File | Line | DAO Accessed | Context |
|---|---|---|---|
| `ManualExpenseRepository.kt` | 198 | `database.recurringExpenseDao()` | Direct DB access to insert recurring expense during manual expense creation |
| `SmartBillNegotiationEngine.kt` | 124 | `recurringExpenseDao.getAll()` | Domain-level engine bypassing repository |
| `RecurringIncomeTracker.kt` | 24, 41, 127 | `ExpenseDao` (via `getExpensesByTypeBetweenUncapped`, `getExpensesBetweenUncapped`) | Bypasses repository for uncapped queries |
| `BudgetForecastingEngine.kt` | 28, 129, 135, 300, 306 | `ExpenseDao` (via `getMonthlySpendingTotalsByCategoryBetween`, `getCategorySpentInPeriod`, `getTotalSpentBetween`) | Debugging engine accesses DAO directly |

**Total: 4 files with direct DAO access** (not through repository layer).

---

## 10. Recommended RecurringOccurrenceCoordinator Design

Based on the audit findings, a `RecurringOccurrenceCoordinator` should address the following:

### Core Responsibilities

```
RecurringOccurrenceCoordinator
├── expandOccurrences(rule, range) → List<Occurrence>
│   - Calendar-aware date expansion from recurrence rule
│   - Handles ALL frequencies consistently
│   - Single source of truth (replace 3+ implementations)
│
├── resolveConflicts(occurrences, actualExpenses, plannedExpenses)
│   - Dedup against existing actual expenses (merchant + date matching)
│   - Dedup against existing planned expenses
│   - Flag duplicates with conflict resolution strategy
│
├── generatePlannedExpenses(occurrences)
│   - Auto-create planned expenses from recurring expansion
│   - Link back to source recurring rule via FK
│
├── scheduleReminders(occurrences)
│   - Schedule Android notifications for upcoming occurrences
│   - Track reminder state (sent/dismissed/snoozed)
│   - Dedup reminder scheduling (don't re-send)
│
└── trackPlannedVsActual(occurrenceId, actualExpenseId)
    - Link occurrence → actual expense when paid
    - Detect drift (date variance, amount variance)
    - Report on planned vs actual lifecycle
```

### Key Design Decisions

1. **Occurrence Identity**: Each expanded occurrence MUST have a unique ID (could be composite: patternId + occurrenceDate fingerprint). Without identity, dedup is impossible.

2. **Persistent State**: Occurrence state should be persisted (at minimum: `occurrenceId`, `patternId`, `dueDate`, `amount`, `status: PENDING|PAID|SKIPPED|DISMISSED`, `linkedExpenseId`, `reminderSentAt`, `dismissedAt`).

3. **Unified Expansion API**: Replace all ad-hoc occurrence logic with a single `expandPattern(pattern: RecurringPattern, range: DateRange)` function that:
   - Uses `RecurrenceCalculator.addFrequencyInterval()` for ALL frequency types
   - Generates all dates within the range
   - Returns `List<Occurrence>` with proper metadata

4. **Forecast Integration**: `ForecastInputAssembler` should use the coordinator's expanded occurrences instead of raw patterns. This ensures forecast counts each occurrence once.

5. **Reminder Scheduler**: Integrate with `WorkManager` for periodic reminder checks (daily). Check `getNotificationsDue()` and schedule local notifications with debounce.

6. **Planned → Actual Linking**: When an expense is created with merchant + amount matching an upcoming occurrence, auto-link them and set occurrence status to PAID.

7. **Replace Direct DAO Access**: `SmartBillNegotiationEngine` should inject `ManualRecurringExpenseRepository` instead of DAO.

8. **Fix Monthly Subscription Cost**: `SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()` must normalize via `RecurrenceCalculator.toMonthlyAmount()`.

### Migration Strategy

1. **Phase 5a**: Create `RecurringOccurrenceCoordinator` with `expandOccurrences()` and `resolveConflicts()`. Replace all ad-hoc expansion in `FinancialStressForecastEngine`, `SynthesisEngine`, `CashFlowCalculator`.
2. **Phase 5b**: Add persisent occurrence state table + DAO. Add `generatePlannedExpenses()` with FK linking.
3. **Phase 5c**: Add reminder scheduling via WorkManager. Implement reminder state tracking.
4. **Phase 5d**: Add planned-vs-actual tracking. Add drift detection metrics to dashboard.
5. **Cleanup**: Deprecate `RecurringExpenseDao` usage, remove `SmartBillNegotiationEngine` DAO leak, consolidate redundant repositories.
