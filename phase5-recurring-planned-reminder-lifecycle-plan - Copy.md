# Phase 5 — Recurring / Planned / Reminder Lifecycle Foundation Implementation Plan

## 0. Phase 5 Mission

Phase 5 creates one lifecycle foundation for:

- confirmed recurring expenses
- detected recurring patterns
- subscriptions
- planned expenses
- bill reminders
- recurring forecast occurrences
- planned-vs-actual reconciliation

Current audit problems:

- no centralized recurrence occurrence expansion
- no persistent occurrence identity
- no reminder state or deduplication
- no reminder scheduling infrastructure
- no planned-vs-actual comparison
- no recurring-to-planned conversion
- duplicated recurring DAOs/repositories
- ad-hoc recurrence logic in forecasts, cashflow, reminders, subscriptions
- hardcoded `7/14/30/90/365` period math in multiple places
- forecast double-counting risk between recurring and planned expenses
- no depletion of recurring obligations when actual expenses already exist
- subscription monthly totals are wrong for non-monthly subscriptions
- recurring rule creation leaks through direct DAO access

The goal is to introduce a central recurring lifecycle layer and make every consumer use the same occurrence expansion, conflict resolution, reminder state, and planned/actual matching rules.

---

# 1. Dependencies on Earlier Phases

## Phase 1 — Currency

Recurring, planned, and subscription rows must use explicit currency.

Rules:

- no silent `"EUR"` fallback in lifecycle creation
- `ManualRecurringExpense.currency` must be set explicitly
- `PlannedExpense.currency` must be set explicitly
- detected patterns must carry the source transaction currency
- subscription candidates must preserve detected currency
- reminder notifications display the occurrence currency

## Phase 2 — Time / period semantics

All recurrence expansion must use Phase 2 time rules:

- half-open ranges: `[startInclusive, endExclusive)`
- `TimeProvider.now()` for current time
- capture `now` once per operation
- `TimePeriodUtils.addDays`, `addMonths`, `addYears`
- no raw `DAY_IN_MILLIS` calendar math
- no deprecated `RecurrenceFrequency.days` production usage
- no `30/90/365` approximation for calendar month/quarter/year

## Phase 3 — Transaction lifecycle

Recurring/planned reconciliation should integrate with `TransactionLifecycleCoordinator`.

When an actual expense is created:

- attempt to link it to an occurrence
- attempt to link it to a planned expense
- mark matched occurrence/planned item as paid/fulfilled
- write lifecycle events

Do not auto-create real `Expense` rows from recurrence rules unless explicitly approved by product/user flow. Forecast/planning occurrences are not actual transactions.

---

# 2. Non-goals

Do not include in this phase:

- real bill payment execution
- external subscription cancellation automation
- bank debit mandate management
- full calendar UI redesign
- automatic creation of actual `Expense` rows from every recurrence rule
- removing legacy columns before compatibility is stable
- rewriting all forecasting engines beyond occurrence input migration
- replacing notification capture subscription detection entirely in one PR

---

# 3. Target Architecture

## 3.1 New central lifecycle owner

Create:

`RecurringLifecycleCoordinator`

Suggested package:

`domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`

Responsibilities:

1. Create/update/deactivate recurring rules.
2. Convert subscription candidates to confirmed recurring subscriptions.
3. Expand recurring rules into occurrences.
4. Materialize future occurrences.
5. Reconcile occurrences with actual expenses.
6. Reconcile occurrences with planned expenses.
7. Generate planned expenses from occurrences if enabled.
8. Schedule/deduplicate bill reminders.
9. Mark occurrences as paid, skipped, dismissed, snoozed, or overdue.
10. Write recurring lifecycle events.
11. Provide occurrence data to forecast, cashflow, reminder, and subscription features.

---

## 3.2 Supporting components

| Component | Responsibility |
|---|---|
| `RecurringRuleRepository` | single repository over `ManualRecurringExpenseDao` |
| `RecurringOccurrenceExpander` | pure calendar-aware expansion |
| `RecurringOccurrenceMaterializer` | persists occurrence rows for a rolling horizon |
| `RecurringConflictResolver` | dedup/reconcile recurring vs actual/planned |
| `RecurringReminderScheduler` | schedules WorkManager reminder checks |
| `BillReminderWorker` | finds due reminders and sends notifications |
| `RecurringReminderStateRepository` | tracks sent/snoozed/dismissed reminders |
| `PlannedActualReconciliationService` | links planned/occurrence records to actual expenses |
| `SubscriptionLifecycleService` | converts candidates and calculates normalized cost |
| `RecurringLifecycleEventRepository` | event ledger |
| `RecurringForecastProvider` | occurrence-based forecast input for engines |

---

# 4. Core Invariants

## 4.1 Recurring rule invariants

Every confirmed recurring rule must have:

1. non-blank merchant
2. normalized merchant key
3. positive finite amount
4. explicit currency
5. valid frequency
6. valid anchor date / next date
7. explicit active/inactive state
8. created/updated timestamp from `TimeProvider`
9. no direct DAO creation outside lifecycle/repository boundary

## 4.2 Occurrence invariants

Every materialized occurrence must have:

1. stable identity
2. source recurring rule ID or source pattern signature
3. due date normalized to local start of day
4. amount
5. currency
6. merchant and merchant key
7. status
8. optional linked planned expense
9. optional linked actual expense
10. reminder state
11. lifecycle event history

## 4.3 Reminder invariants

A reminder must not be sent twice for the same:

- occurrence
- reminder window/type
- notification channel/action

unless explicitly snoozed or reset.

## 4.4 Forecast invariants

Forecasts must count each future obligation once.

Do not double-count:

- recurring occurrence + generated planned expense
- recurring occurrence + user-created matching planned expense
- recurring occurrence + actual expense already paid
- planned expense + actual expense already paid

---

# 5. Domain Model Design

## 5.1 Recurrence rule abstraction

Create a domain abstraction so manual recurring rows, subscriptions, and detected patterns can be expanded consistently.

```kotlin
data class RecurringRuleLike(
    val ruleId: Long?,
    val sourceType: RecurringRuleSourceType,
    val merchant: String,
    val merchantKey: String,
    val amount: Double,
    val currency: String,
    val frequency: RecurrenceFrequency,
    val anchorDate: Long,
    val nextDate: Long,
    val categoryId: Long?,
    val confidence: Float,
    val isConfirmed: Boolean,
    val isSubscription: Boolean
)
```

Suggested source types:

- `MANUAL_RULE`
- `CONFIRMED_SUBSCRIPTION`
- `DETECTED_PATTERN`
- `NOTIFICATION_SUBSCRIPTION_CANDIDATE`
- `PLANNED_SERIES`
- `UNKNOWN`

Important rule:

- confirmed/manual/subscription rules can be materialized and reminded
- detected patterns are virtual forecast inputs only until user confirms them

---

## 5.2 Recurring occurrence model

```kotlin
data class RecurringOccurrence(
    val occurrenceKey: String,
    val ruleId: Long?,
    val sourceType: RecurringRuleSourceType,
    val dueDate: Long,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val merchantKey: String,
    val frequency: RecurrenceFrequency,
    val categoryId: Long?,
    val confidence: Float,
    val status: OccurrenceStatus,
    val linkedExpenseId: Long?,
    val linkedPlannedExpenseId: Long?,
    val reminderState: ReminderState,
    val generatedAt: Long,
    val updatedAt: Long
)
```

Suggested occurrence key:

`ruleId_or_signature|dueDateStart|amountMinor|currency|merchantKey|frequency`

For persisted manual rules, `ruleId` should be used.  
For virtual detected patterns, use a deterministic signature.

---

## 5.3 Occurrence status

Suggested statuses:

- `PENDING`
- `PAID`
- `SKIPPED`
- `DISMISSED`
- `SNOOZED`
- `OVERDUE`
- `CANCELLED`
- `SUPERSEDED_BY_ACTUAL`
- `SUPERSEDED_BY_PLANNED`

Keep the initial implementation small if needed:

- `PENDING`
- `PAID`
- `SKIPPED`
- `DISMISSED`
- `OVERDUE`

Add richer statuses later.

---

## 5.4 Reminder state

Suggested reminder state:

- `NONE`
- `SCHEDULED`
- `SENT`
- `SNOOZED`
- `DISMISSED`
- `ACTIONED`

Reminder delivery should be tracked separately so several reminder windows can be deduped.

Example delivery types:

- `FOURTEEN_DAYS_BEFORE`
- `SEVEN_DAYS_BEFORE`
- `THREE_DAYS_BEFORE`
- `ONE_DAY_BEFORE`
- `DUE_TODAY`
- `OVERDUE`

---

# 6. Database Design

## 6.1 Update `ManualRecurringExpense`

Add nullable/defaulted columns:

- `anchorDate`
- `merchantKey`
- `updatedAt`
- `endedAt`
- `lastGeneratedThrough`
- `source`
- `status`

Migration:

- `anchorDate = nextDate` for legacy rows
- `updatedAt = createdAt` if available, otherwise `0`
- `source = MANUAL_RULE`
- `status = ACTIVE` if `isActive = 1`, else `INACTIVE`
- `merchantKey` can be lazily backfilled because Room migrations cannot easily call normalizer logic

Keep existing `nextDate` for compatibility. Long-term, `nextDate` should be derived from the next pending occurrence, but do not remove it yet.

---

## 6.2 Add `recurring_occurrences`

Fields:

- `id`
- `occurrenceKey`
- `ruleId`
- `sourceType`
- `merchant`
- `merchantKey`
- `amount`
- `currency`
- `frequency`
- `dueDate`
- `categoryId`
- `confidence`
- `status`
- `linkedExpenseId`
- `linkedPlannedExpenseId`
- `dateVarianceDays`
- `amountVariancePercent`
- `generatedAt`
- `updatedAt`

Indexes:

- unique `occurrenceKey`
- `(ruleId, dueDate)`
- `(dueDate, status)`
- `linkedExpenseId`
- `linkedPlannedExpenseId`
- `merchantKey`

---

## 6.3 Add `recurring_reminder_deliveries`

Fields:

- `id`
- `occurrenceId`
- `occurrenceKey`
- `reminderType`
- `notificationId`
- `sentAt`
- `dismissedAt`
- `snoozedUntil`
- `actionedAt`
- `status`

Indexes:

- unique `(occurrenceKey, reminderType)`
- `sentAt`
- `snoozedUntil`
- `status`

This prevents duplicate reminder sends even if the worker runs multiple times.

---

## 6.4 Add `recurring_lifecycle_events`

Fields:

- `id`
- `ruleId`
- `occurrenceId`
- `occurrenceKey`
- `eventType`
- `source`
- `occurredAt`
- `oldStatus`
- `newStatus`
- `linkedExpenseId`
- `linkedPlannedExpenseId`
- `metadataJson`
- `reason`

Event types:

- `RULE_CREATED`
- `RULE_UPDATED`
- `RULE_DEACTIVATED`
- `OCCURRENCE_GENERATED`
- `OCCURRENCE_RECONCILED`
- `OCCURRENCE_MARKED_PAID`
- `OCCURRENCE_SKIPPED`
- `OCCURRENCE_DISMISSED`
- `OCCURRENCE_OVERDUE`
- `PLANNED_GENERATED`
- `PLANNED_LINKED`
- `ACTUAL_LINKED`
- `REMINDER_SENT`
- `REMINDER_SNOOZED`
- `REMINDER_DISMISSED`
- `DRIFT_DETECTED`

---

## 6.5 Update `PlannedExpense`

Add nullable/defaulted columns:

- `sourceType`
- `sourceOccurrenceKey`
- `sourceRecurringRuleId`
- `linkedActualExpenseId`
- `status`
- `merchantKey`
- `updatedAt`

Suggested statuses:

- `PLANNED`
- `FULFILLED`
- `SKIPPED`
- `CANCELLED`
- `SUPERSEDED`

Indexes:

- `sourceOccurrenceKey`
- `sourceRecurringRuleId`
- `linkedActualExpenseId`
- `(date, status)`
- `merchantKey`

Add DAO methods:

- `getById`
- `getByIdFlow`
- `update`
- `updateStatus`
- `linkActualExpense`
- `getByOccurrenceKey`
- `getForPeriodOneShot`
- `getOpenPlannedForPeriod`

---

# 7. Occurrence Expansion Contract

## 7.1 `RecurringOccurrenceExpander`

Pure service.

API:

```kotlin
fun expand(
    rule: RecurringRuleLike,
    rangeStartInclusive: Long,
    rangeEndExclusive: Long,
    maxOccurrences: Int = 500
): List<RecurringOccurrencePreview>
```

Rules:

1. Normalize range start/end through `TimePeriodUtils`.
2. Normalize due dates to start of local day.
3. Use `RecurrenceCalculator.addFrequencyInterval()` for every step.
4. Use `TimePeriodUtils.addDays()` for weekly/biweekly, not raw millis.
5. Use `TimePeriodUtils.addMonths()` for monthly/quarterly/semiannual/annual.
6. Never use deprecated `frequency.days`.
7. Stop if next date does not advance.
8. Return only dates inside `[start, end)`.
9. `IRREGULAR` should not expand automatically. It may expose only a single known `nextDate` if product explicitly chooses that behavior.

---

## 7.2 Required expansion tests

Cover:

- weekly recurrence across DST spring-forward
- weekly recurrence across DST fall-back
- biweekly recurrence from anchor
- monthly recurrence from January 31
- monthly recurrence from March 31
- quarterly recurrence crossing year boundary
- semiannual recurrence
- annual recurrence from February 29
- due date exactly at range start included
- due date exactly at range end excluded
- overdue nextDate rolled forward correctly
- `IRREGULAR` not expanded silently
- max occurrence guard prevents infinite loop

---

# 8. Conflict Resolution

## 8.1 `RecurringConflictResolver`

Inputs:

- expanded occurrences
- actual expenses in range
- planned expenses in range

Outputs:

- occurrence contribution list
- links to actual/planned
- duplicate/skipped/superseded markers
- drift metrics

## 8.2 Actual matching policy

Match occurrence to actual expense using:

- merchant key exact or fuzzy match
- currency exact
- transaction type compatible
- date within `periodVarianceDays`
- amount within configured tolerance or percent variance
- category optional secondary signal

If matched:

- mark occurrence `PAID`
- set `linkedExpenseId`
- record `dateVarianceDays`
- record `amountVariancePercent`
- exclude from future forecast contribution

## 8.3 Planned matching policy

Match occurrence to planned expense using:

- `sourceOccurrenceKey` if available
- otherwise merchant key + date window + amount/currency

If planned was generated from occurrence:

- link directly
- prevent double-counting

If user-created planned expense overlaps recurring occurrence:

- flag as covered by planned
- forecast should count only once

---

# 9. Reminder Lifecycle

## 9.1 Scheduler

Add:

`BillReminderWorker`

Use WorkManager.

Recommended cadence:

- periodic daily worker
- optional immediate one-shot run after rule changes
- optional app startup scheduler registration

Avoid exact alarms unless truly required.

## 9.2 Worker flow

1. Capture `now`.
2. Ensure occurrences materialized for reminder horizon.
3. Query pending occurrences due within reminder windows.
4. Exclude:
   - paid
   - skipped
   - dismissed
   - cancelled
   - snoozed until future
5. For each occurrence/window:
   - check `recurring_reminder_deliveries`
   - if already sent, skip
   - send notification
   - write delivery row
   - write lifecycle event

## 9.3 Notification actions

Minimum foundation:

- open bill reminders screen
- mark paid
- dismiss
- snooze

If actions are too much for first PR, implement open + state table first, then add actions.

## 9.4 Reminder dedup tests

- running worker twice does not send duplicate
- paid occurrence is not reminded
- dismissed occurrence is not reminded
- snoozed occurrence waits until snooze expires
- overdue occurrence gets overdue notification once per policy
- notification ID is stable per occurrence/reminder type

---

# 10. Planned Expense Lifecycle

## 10.1 Planned generation from recurring

Add:

`RecurringPlanProjectionService`

Modes:

1. disabled
2. preview only
3. generate planned expenses for confirmed recurring rules
4. generate only for subscriptions
5. generate for all confirmed rules

Initial safe default: preview only or generate only when user opts in.

When generated:

- planned row gets `sourceType = RECURRING_OCCURRENCE`
- planned row gets `sourceOccurrenceKey`
- planned row gets `sourceRecurringRuleId`
- unique occurrence key prevents duplicates

## 10.2 Planned-vs-actual reconciliation

When an actual expense is created or updated:

1. Find matching planned expense.
2. Link `linkedActualExpenseId`.
3. Mark planned status `FULFILLED`.
4. Link occurrence if planned came from recurrence.
5. Record drift:
   - paid early/late
   - amount higher/lower
   - merchant changed
   - currency mismatch

Add periodic reconciliation worker or call from transaction lifecycle side effect.

## 10.3 Tests

- generated planned expense is unique per occurrence
- user-created duplicate planned item does not double-count
- actual expense links to planned item
- planned item becomes fulfilled
- late payment drift detected
- amount variance detected
- deleting/deactivating recurring rule cancels future generated planned items

---

# 11. Forecast / Cashflow Migration

Replace all ad-hoc occurrence logic.

## 11.1 `FinancialStressForecastEngine`

Current issue:

- expands weekly/biweekly using hardcoded millis

New behavior:

- query expanded/reconciled occurrences for horizon
- sum unpaid future occurrence amounts
- exclude occurrences already linked to actuals
- include only relevant statuses

## 11.2 `SynthesisEngine`

Current issue:

- has its own `isRecurringExpected()` day matching

New behavior:

- consume occurrence list grouped by day
- committed = confirmed high-confidence occurrences
- likely = detected high-confidence virtual occurrences
- exclude planned duplicates

## 11.3 `CashFlowCalculator`

Current issue:

- checks only one `nextExpectedDate`

New behavior:

- expand confirmed occurrences over cashflow period
- add every unpaid occurrence to daily cashflow
- include subscriptions correctly

## 11.4 `BillReminderManager`

Current issue:

- reminders generated from `nextDate` only

New behavior:

- reminders generated from materialized occurrences
- mark paid updates occurrence and rule nextDate
- monthly total uses normalized monthly amount via `RecurrenceCalculator`

## 11.5 `MonthlySavingsSweepUseCase`

Current issue:

- only checks recurring rows whose `nextDate` is inside month

New behavior:

- query/expand all unpaid occurrences in target period
- avoids missing weekly/biweekly multiple occurrences

## 11.6 Forecast double-count prevention

`ForecastInputAssembler` should stop passing raw recurring patterns and planned expenses as unrelated obligations.

Target model:

- recurring occurrences
- planned expenses
- reconciliation map
- actual coverage map

Forecast counts final resolved obligations, not raw independent lists.

---

# 12. Subscription Lifecycle Fixes

## 12.1 Candidate conversion

`SubscriptionManagementViewModel.acceptCandidate()` should not compute next date with hardcoded day multipliers.

New flow:

1. candidate accepted
2. `SubscriptionLifecycleService.convertCandidateToRule()`
3. create confirmed recurring rule through `RecurringLifecycleCoordinator`
4. use `RecurrenceCalculator.calculateNextDate()` for next date
5. preserve currency
6. mark candidate converted

## 12.2 Monthly subscription cost

Fix:

`SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()`

Use:

`RecurrenceCalculator.toMonthlyAmount(amount, frequency)`

Same fix for UI cost-per-use logic.

Remove hardcoded:

- quarterly `/ 3`
- semiannual `/ 6`
- annual `/ 12`

from ViewModel and use central calculator.

## 12.3 Direct DAO leak

`SmartBillNegotiationEngine` must inject repository/lifecycle service, not `ManualRecurringExpenseDao`.

---

# 13. Repository / DAO Consolidation

## 13.1 Remove deprecated path from active use

Current redundant stack:

- `RecurringExpenseDao` deprecated
- `RecurringExpenseRepository` wraps deprecated DAO
- `ManualRecurringExpenseDao` current
- `ManualRecurringExpenseRepository` current but thin

Target:

- one DAO: `ManualRecurringExpenseDao`
- one repository: `RecurringRuleRepository`
- old repositories become compatibility wrappers temporarily

## 13.2 Migrate callers

Update these to use `RecurringRuleRepository` or lifecycle coordinator:

- `BillReminderManager`
- `RecurringExpenseEngine`
- `MergedRecurringPatternsProvider`
- `RecurringExpensesViewModel`
- `FinancialWeatherRepository`
- `CalculateFinancialForecastUseCase`
- `MonthlySavingsSweepUseCase`
- `ManualRecurringExpenseViewModel`
- `ManualExpenseRepository`
- `SmartBillNegotiationEngine`

## 13.3 Direct DAO leak cleanup

Replace:

- `ManualExpenseRepository.database.recurringExpenseDao()`
- `SmartBillNegotiationEngine.recurringExpenseDao.getAll()`

Review separately:

- `RecurringIncomeTracker` direct `ExpenseDao`
- `BudgetForecastingEngine` direct `ExpenseDao`

Those may need query ports, but they are not the highest Phase 5 blocker unless touched.

---

# 14. Implementation PR Plan

## PR 0 — Baseline and docs

### Goal

Record current behavior.

### Actions

1. Run compile/tests.
2. Document current failing tests.
3. Add `docs/development/RECURRING_LIFECYCLE.md`.
4. Add audit checklist:
   - deprecated DAO usage
   - direct DAO leaks
   - hardcoded recurrence math
   - reminder scheduling gaps
   - planned-vs-actual gaps

### Done when

- Baseline is documented.
- No behavior change.

---

## PR 1 — Recurrence math contract

### Goal

Make recurrence calculation safe and canonical.

### Files

- `RecurrenceFrequency.kt`
- `RecurrenceCalculator.kt`
- `TimePeriodUtils.kt` if needed
- recurrence tests

### Actions

1. Mark deprecated `days` as forbidden in production.
2. Add KDoc explaining fixed-day vs calendar-month recurrence.
3. Ensure weekly/biweekly use `TimePeriodUtils.addDays`.
4. Ensure monthly+ use `TimePeriodUtils.addMonths`.
5. Add tests for DST, Jan 31, Feb 29, range boundaries.

### Done when

- recurrence math is deterministic and tested.
- no new raw millis recurrence code is introduced.

---

## PR 2 — Repository consolidation foundation

### Goal

Create one recurring rule access path.

### Add

- `RecurringRuleRepository`

### Actions

1. Wrap `ManualRecurringExpenseDao`.
2. Add methods:
   - get active rules
   - get subscriptions
   - get by ID
   - insert/update/deactivate
   - update next date
   - backfill merchant keys
3. Keep old repositories temporarily as delegators.
4. Stop exposing deprecated `RecurringExpenseDao` to new code.

### Migrate

- `ManualRecurringExpenseViewModel`
- `SmartBillNegotiationEngine`
- `ManualExpenseRepository` recurring rule creation

### Tests

- insert rule
- update rule
- deactivate rule
- subscription query
- no direct DAO use in migrated files

### Done when

- new code has one repository target.

---

## PR 3 — Pure occurrence expander

### Goal

Add centralized expansion without persistence.

### Add

- `RecurringRuleLike`
- `RecurringOccurrencePreview`
- `RecurringOccurrenceExpander`

### Actions

1. Convert `ManualRecurringExpense` to `RecurringRuleLike`.
2. Convert `RecurringPattern` to virtual `RecurringRuleLike`.
3. Implement range expansion.
4. Implement overdue rolling helper.
5. Implement occurrence key generation.

### Tests

Use all cases from section 7.2.

### Done when

- all consumers can theoretically use one expansion API.

---

## PR 4 — Occurrence conflict resolver

### Goal

Detect coverage by actual and planned expenses.

### Add

- `RecurringConflictResolver`
- `OccurrenceResolution`
- `OccurrenceCoverage`

### Actions

1. Match occurrences against actual expenses.
2. Match occurrences against planned expenses.
3. Produce resolved contribution list.
4. Add configurable amount/date tolerances.
5. Write tests for duplicate/double-count scenarios.

### Tests

- actual expense depletes occurrence
- planned expense covers occurrence
- planned + recurring counted once
- actual + planned counted once
- currency mismatch does not match
- merchant mismatch does not match
- amount drift recorded

### Done when

- forecast can avoid double-counting.

---

## PR 5 — Persistent occurrence schema

### Goal

Add occurrence identity and event storage.

### Add tables

- `recurring_occurrences`
- `recurring_reminder_deliveries`
- `recurring_lifecycle_events`

### Modify

- `ManualRecurringExpense`
- `PlannedExpense`

### Add DAOs

- `RecurringOccurrenceDao`
- `RecurringReminderDeliveryDao`
- `RecurringLifecycleEventDao`

### Migration

- add new columns as nullable/defaulted
- no destructive migration
- do not remove legacy DAO yet

### Tests

- migration test
- occurrence insert/upsert test
- unique occurrence key test
- reminder delivery dedup test
- event insert/query test

### Done when

- occurrence state can be persisted safely.

---

## PR 6 — Materializer and coordinator skeleton

### Goal

Create lifecycle coordinator and materialize future occurrences.

### Add

- `RecurringLifecycleCoordinator`
- `RecurringOccurrenceMaterializer`

### Coordinator methods

- `materializeOccurrences(range)`
- `reconcileOccurrences(range)`
- `getUpcomingOccurrences(range)`
- `markOccurrencePaid`
- `markOccurrenceSkipped`
- `dismissOccurrence`
- `snoozeOccurrence`
- `deactivateRule`

### Materialization policy

Initial rolling horizon:

- past: 30 days
- future: 12 months

Use configuration constants.

### Tests

- materialize active rules
- materialize idempotently
- deactivated rules stop generating future occurrences
- updating rule supersedes future pending occurrences
- mark paid updates occurrence
- rule `nextDate` remains compatible

### Done when

- recurring lifecycle has a working core.

---

## PR 7 — Forecast and cashflow migration

### Goal

Replace ad-hoc expansion.

### Files

- `FinancialStressForecastEngine`
- `SynthesisEngine`
- `CashFlowCalculator`
- `ForecastInputAssembler`
- `MergedRecurringPatternsProvider`
- `MonthlySavingsSweepUseCase`
- `FinancialWeatherRepository`
- `CalculateFinancialForecastUseCase`

### Actions

1. Use occurrence expander/coordinator for recurring obligations.
2. Remove `SynthesisEngine.isRecurringExpected()` day-matching logic.
3. Remove stress engine hardcoded weekly/biweekly millis expansion.
4. Cashflow should include multiple occurrences in range.
5. Monthly sweep should count every occurrence in month.
6. Forecast assembler should resolve recurring/planned conflicts.

### Tests

- weekly bill appears four/five times in month as appropriate
- biweekly bill appears correct number of times
- paid current-month occurrence not forecast again
- planned duplicate not double-counted
- stress forecast counts quarterly subscription only when due
- cashflow daily list shows all future occurrences

### Done when

- forecasts use one occurrence source.

---

## PR 8 — Planned expense lifecycle

### Goal

Add planned generation and planned-vs-actual linking.

### Files

- `PlannedExpense.kt`
- `PlannedExpenseDao`
- `PlannedExpenseRepository`
- forecast input files
- transaction lifecycle integration point

### Add

- `RecurringPlanProjectionService`
- `PlannedActualReconciliationService`

### Actions

1. Add DAO update/link methods.
2. Generate planned expenses from occurrences if policy enabled.
3. Link actual expenses to planned items.
4. Link planned items to occurrences.
5. Mark planned items fulfilled/skipped/cancelled.
6. Add drift detection.

### Tests

- recurring occurrence generates one planned row
- repeated generation is idempotent
- actual expense fulfills planned row
- generated planned does not double-count with occurrence
- manual planned duplicate is detected
- cancelled occurrence cancels generated future planned row

### Done when

- planned expenses have lifecycle state and actual matching.

---

## PR 9 — Bill reminder scheduling

### Goal

Make reminders real and deduped.

### Add

- `BillReminderWorker`
- `RecurringReminderScheduler`
- notification action handler if feasible

### Actions

1. Schedule periodic WorkManager job.
2. Run one-shot worker after rule changes.
3. Query due pending occurrences.
4. Dedup using `recurring_reminder_deliveries`.
5. Send notifications.
6. Support dismiss/snooze/mark-paid state updates.
7. Update `BillReminderManager` to read occurrences, not raw `nextDate`.

### Tests

- worker sends due reminders
- duplicate worker run sends zero duplicates
- dismiss prevents future sends
- snooze delays sends
- mark paid prevents reminder
- monthly bill total uses normalized recurrence math

### Done when

- bill reminders are scheduled and stateful.

---

## PR 10 — Subscription lifecycle cleanup

### Goal

Make subscriptions use recurring lifecycle math.

### Files

- `SubscriptionManagerEngine`
- `SubscriptionManagementRepository`
- `SubscriptionManagementViewModel`
- `NotificationSubscriptionDetector`
- `SmartBillNegotiationEngine`
- subscription tests

### Actions

1. Candidate acceptance calls lifecycle coordinator.
2. Remove hardcoded nextDate calculations.
3. Fix monthly subscription total with `toMonthlyAmount`.
4. Fix cost-per-use with central monthly normalization.
5. Keep candidate detection as detection-only.
6. Remove direct DAO access from negotiation engine.

### Tests

- quarterly €90 subscription counts as €30/month
- annual €120 subscription counts as €10/month
- accepted candidate creates confirmed recurring rule
- candidate conversion preserves currency
- next date calculated through recurrence calculator

### Done when

- subscription math is centralized and correct.

---

## PR 11 — UI integration

### Goal

Expose lifecycle state cleanly.

### Files

- `RecurringExpensesScreen`
- `RecurringExpensesViewModel`
- `ManualRecurringExpenseScreen`
- `ManualRecurringExpenseViewModel`
- `BillRemindersScreen`
- `BillRemindersViewModel`
- `SubscriptionManagementScreen`
- `SubscriptionManagementViewModel`
- `CashFlowCalendarScreen/ViewModel`

### Actions

1. Recurring screen shows next occurrences, not only nextDate.
2. Bill reminders screen shows persistent reminder/occurrence status.
3. Add mark paid/skipped/dismiss/snooze actions.
4. Cashflow calendar uses occurrence list.
5. Subscription screen shows normalized monthly cost.
6. Planned screen shows fulfilled/skipped/planned status if surfaced.

### Done when

- UI reflects lifecycle state instead of ephemeral calculations.

---

## PR 12 — Recurring income alignment

### Goal

Bring income recurrence closer to the same pattern system.

### Files

- `RecurringIncomeTracker`
- income tests

### Actions

1. Replace direct `ExpenseDao` with repository/query port if practical.
2. Use shared recurrence interval detection helpers.
3. Use shared occurrence expansion for income forecast if income forecasting exists.
4. Keep income separate from expense reminders unless product wants income reminders.

### Done when

- recurring income no longer duplicates basic recurrence logic unnecessarily.

---

## PR 13 — Guardrails and cleanup

### Goal

Prevent regressions.

### Add scan checks for:

- `RecurringExpenseDao` usage outside migration/compat wrappers
- `frequency.days`
- `DAY_IN_MILLIS` in recurring logic
- hardcoded `30/90/365` recurrence math
- direct `ManualRecurringExpenseDao` injection outside approved repository
- direct recurring insert from `ManualExpenseRepository`
- direct reminder notification without delivery dedup
- forecast use of raw patterns without occurrence resolution

### Remove/deprecate

- active use of deprecated `RecurringExpenseDao`
- duplicate repository APIs where migrated
- ad-hoc recurrence functions in forecast/cashflow/reminders

### Done when

- audit guard passes.
- docs are updated with verified fixes only.

---

# 15. File-by-File Migration Targets

## High priority

| File | Action |
|---|---|
| `RecurrenceCalculator.kt` | canonical recurrence math |
| `RecurringPattern.kt` | remove production reliance on deprecated days |
| `ManualRecurringExpense.kt` | add lifecycle columns |
| `ManualRecurringExpenseDao.kt` | become only recurring DAO |
| `RecurringExpenseDao.kt` | remove active usage |
| `RecurringExpenseRepository.kt` | compatibility wrapper or delete later |
| `ManualRecurringExpenseRepository.kt` | merge into `RecurringRuleRepository` |
| `BillReminderManager.kt` | use occurrences and reminder state |
| `FinancialStressForecastEngine.kt` | replace ad-hoc expansion |
| `SynthesisEngine.kt` | replace day-matching logic |
| `CashFlowCalculator.kt` | use expanded occurrences |
| `ForecastInputAssembler.kt` | avoid recurring/planned double count |
| `MonthlySavingsSweepUseCase.kt` | use occurrences for known obligations |
| `SubscriptionManagerEngine.kt` | fix monthly normalization |
| `SubscriptionManagementViewModel.kt` | remove hardcoded normalization |
| `SmartBillNegotiationEngine.kt` | remove DAO leak |
| `ManualExpenseRepository.kt` | stop direct recurring DAO insert |

---

# 16. Acceptance Criteria

Phase 5 is complete when:

1. A central occurrence expander exists and is tested.
2. Forecast, cashflow, reminders, and savings use the same occurrence expansion.
3. Persistent occurrence identity exists.
4. Reminder delivery state exists.
5. WorkManager reminder scheduling exists.
6. Reminders are deduped.
7. Planned expenses can link to occurrences.
8. Planned expenses can link to actual expenses.
9. Planned-vs-actual drift can be detected.
10. Forecasts do not double-count recurring + planned + actual.
11. Subscription monthly cost is frequency-normalized.
12. Deprecated recurring DAO is no longer used by active production paths.
13. Direct recurring DAO leaks are removed.
14. Hardcoded recurrence period math is removed from production recurring logic.
15. Manual recurring creation uses the lifecycle/repository path.
16. Tests cover recurrence edge cases, reminder dedup, planned matching, and forecast conflicts.
17. Guardrails prevent new recurrence bypasses.

---

# 17. Recommended Implementation Order

1. Baseline and docs.
2. Recurrence math contract.
3. Repository consolidation.
4. Pure occurrence expander.
5. Conflict resolver.
6. Persistent occurrence/reminder/event schema.
7. Coordinator and materializer.
8. Forecast/cashflow migration.
9. Planned lifecycle and planned-vs-actual.
10. Reminder scheduler.
11. Subscription cleanup.
12. UI integration.
13. Recurring income alignment.
14. Guardrails and final cleanup.

This order keeps the riskiest user-visible changes — reminders and planned generation — behind the safer foundation of recurrence math, occurrence identity, and conflict resolution.