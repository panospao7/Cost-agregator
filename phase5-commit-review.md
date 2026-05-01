# Phase 5 Commit Review — Recurring / Planned / Reminder Lifecycle

Reviewed commit:

- `1ea5eb2` — `Phase 5 — Recurring/Planned/Reminder Lifecycle Foundation...`
- URL: https://github.com/panospao7/Cost-agregator/commit/1ea5eb2a2def45d77fdb53a9d66aa451b761943b

## Overall verdict

This is a useful **foundation commit**, but I would **not mark Phase 5 complete** yet.

It adds several correct building blocks:

- `RecurringOccurrenceExpander`
- `OccurrenceConflictResolver`
- `RecurringLifecycleCoordinator`
- `RecurringOccurrenceMaterializer`
- `RecurringPlanProjectionService`
- `RecurringOccurrence` table
- `RecurringReminderDelivery` table
- migration `96 -> 100`
- partial subscription monthly-cost fix
- partial repository consolidation

But several key lifecycle requirements from the audit are still incomplete, and there may be a **likely Hilt compile blocker**.

---

# Critical issues

## 1. Likely Hilt missing bindings

`RecurringLifecycleCoordinator` injects:

```kotlin
private val expander: RecurringOccurrenceExpander,
private val resolver: OccurrenceConflictResolver,
```

But both classes appear to be plain classes:

```kotlin
class RecurringOccurrenceExpander
class OccurrenceConflictResolver
```

I did not see `@Inject constructor()` on them, and I did not see a provider module added for them.

Required fix:

```kotlin
class RecurringOccurrenceExpander @Inject constructor()
class OccurrenceConflictResolver @Inject constructor()
```

or add explicit Hilt providers.

This is especially risky because `ForecastInputAssembler` now injects `RecurringLifecycleCoordinator`, even though it only documents a future TODO. That pulls the whole coordinator graph into more places.

Recommended:

- either add proper bindings immediately, or
- remove `RecurringLifecycleCoordinator` injection from `ForecastInputAssembler` until it is actually used.

---

## 2. Occurrence expansion ignores the recurring rule’s `nextDate`

This is the biggest logic bug.

`RecurringLifecycleCoordinator.generateOccurrences()` builds:

```kotlin
ExpandRequest(
    startDate = startDate,
    endDate = endDate,
    ...
)
```

Then `RecurringOccurrenceExpander.expand()` starts from:

```kotlin
var currentDate = TimePeriodUtils.getStartOfDay(request.startDate)
```

So if `RecurringPlanProjectionService.projectFromRule()` calls:

```kotlin
coordinator.generateOccurrences(ruleId, now, endDate)
```

then the first occurrence is generated for **today**, not for `rule.nextDate`.

Example:

- Netflix rule `nextDate = May 15`
- today = May 1
- generated occurrences become May 1, Jun 1, Jul 1
- expected: May 15, Jun 15, Jul 15

Required fix:

- `ExpandRequest` needs an `anchorDate` or `firstDueDate`.
- Coordinator should pass `rule.nextDate`, rolled forward if needed.
- Range start should only filter output, not define the recurrence anchor.

Suggested model:

```kotlin
data class ExpandRequest(
    val anchorDate: Long,
    val rangeStartInclusive: Long,
    val rangeEndExclusive: Long,
    ...
)
```

---

## 3. Expense-to-occurrence linking is unsafe

`RecurringLifecycleCoordinator.linkExpenseToOccurrence()` matches by day only:

```kotlin
occ.status == "PLANNED" && occ.linkedExpenseId == null
```

It does not check:

- merchant
- amount
- currency
- transaction type
- ownership flags
- category
- tolerance

So any expense on the same day can mark the first planned occurrence as paid.

Required fix:

- reuse `OccurrenceConflictResolver`
- match merchant key, amount tolerance, currency, date window, transaction type
- exclude non-purchase, transfers, deposits, and `isNotMine` unless explicitly intended.

---

## 4. Raw millis day boundary reintroduced

`linkExpenseToOccurrence()` uses:

```kotlin
val expenseDayEnd = expenseDayStart + 86_400_000L
```

`RecurringOccurrenceMaterializer.computeScheduledAt()` uses:

```kotlin
dueDate - days * 86_400_000L
```

This violates Phase 2.

Required fix:

```kotlin
val expenseDayEnd = TimePeriodUtils.getEndOfDay(expense.date)
val scheduledAt = TimePeriodUtils.addDays(dueDate, -days)
```

---

## 5. Forecast integration is mostly TODO, not implemented

`ForecastInputAssembler` now documents the exact unresolved double-count risk:

```kotlin
TODO: Use RecurringLifecycleCoordinator.generateOccurrences ...
```

`SynthesisEngine` still uses the old `isRecurringExpected()` block-party day matching logic.

So the central occurrence system is not yet the source of truth for forecast/cashflow/dashboard behavior.

Still missing:

- `FinancialStressForecastEngine` migration
- `CashFlowCalculator` migration
- `SynthesisEngine` migration
- `MonthlySavingsSweepUseCase` migration
- double-count prevention between recurring occurrence + generated planned expense
- paid occurrence depletion from forecasts

This is fine for a foundation PR, but the commit message overstates completion.

---

# High-priority issues

## 6. Reminder scheduling is not implemented

`RecurringReminderDelivery` exists, and `getDueReminders()` exists, but there is no actual worker/scheduler.

`BillReminderManager` explicitly says:

> `ReminderDispatchWorker` ... to be created in a future PR

So the audit gaps remain:

- no proactive reminder notifications
- no WorkManager worker
- no notification dispatch state transition from `SCHEDULED` to `SENT`
- no dismiss/snooze action path
- no duplicate-send protection under concurrent workers

Required next piece:

- `BillReminderWorker`
- `RecurringReminderScheduler`
- delivery state update transaction
- notification ID generation
- dismiss/snooze/mark-paid actions

---

## 7. Reminder deliveries are not uniquely constrained

Entity index:

```kotlin
Index(value = ["occurrenceId", "reminderWindow"])
```

But it is not unique.

DAO insert:

```kotlin
@Insert
suspend fun insert(delivery: RecurringReminderDelivery): Long
```

The materializer checks for existing delivery first, but that is not race-safe.

Required fix:

```kotlin
Index(value = ["occurrenceId", "reminderWindow"], unique = true)
```

and use `OnConflictStrategy.IGNORE`.

---

## 8. Materialization is not transactional

`RecurringOccurrenceMaterializer.materialize()` loops through:

- occurrence insert
- occurrence update
- delivery query
- delivery insert

without a Room transaction.

If it fails halfway, you can get:

- occurrence without reminders
- reminders without all intended occurrences
- partial status updates

Required fix:

- move transaction boundary to coordinator or DAO-level `@Transaction` method.
- materialize occurrence + reminder deliveries atomically per batch.

---

## 9. No lifecycle event ledger was added

The Phase 5 plan called for `recurring_lifecycle_events`.

The commit adds:

- `recurring_occurrences`
- `recurring_reminder_deliveries`

But I did not see:

- `RecurringLifecycleEvent`
- `RecurringLifecycleEventDao`
- lifecycle event writes

So there is no audit trail yet for:

- occurrence generated
- occurrence paid
- occurrence skipped
- reminder sent
- reminder dismissed
- planned generated
- drift detected

This should be added before calling Phase 5 complete.

---

## 10. Planned-vs-actual is only partially started

`PlannedExpense` now has:

- `sourceOccurrenceKey`
- `sourceRecurringRuleId`

Good.

But still missing:

- planned status: `PLANNED`, `FULFILLED`, `SKIPPED`, etc.
- `linkedActualExpenseId`
- `merchantKey`
- `updatedAt`
- DAO update/link methods
- drift detection
- actual expense -> planned expense matching

Also, `RecurringPlanProjectionService` creates planned rows but does not link back from the occurrence because `RecurringOccurrence` has no `linkedPlannedExpenseId`.

---

## 11. `RecurringPlanProjectionService` has a today-filter bug

It filters:

```kotlin
it.dueDate in now until endDate
```

But occurrence due dates are start-of-day. If `now` is midday, an occurrence due today at 00:00 is excluded.

Required fix:

```kotlin
val start = TimePeriodUtils.getStartOfDay(timeProvider.now())
...
it.dueDate >= start && it.dueDate < endDate
```

---

# Remaining audit gaps

## 12. Direct DAO leaks still remain

From the audit, these were supposed to be cleaned up.

Still present:

### `ManualExpenseRepository`

Still calls:

```kotlin
database.recurringExpenseDao().insert(recurringExpense)
```

This should use `RecurringExpenseRepository`, or better, `RecurringLifecycleCoordinator`.

### `SmartBillNegotiationEngine`

Still injects:

```kotlin
ManualRecurringExpenseDao
```

This should use `RecurringExpenseRepository` or a query port.

So repository consolidation is incomplete.

---

## 13. Deprecated DAO still exists

`RecurringExpenseDao` is now more clearly deprecated, and `RecurringExpenseRepository` wraps `ManualRecurringExpenseDao`, which is good.

But old direct access and DI wiring should be scanned to ensure no production caller still uses the deprecated DAO.

---

## 14. Subscription math fix is partial

Good:

```kotlin
getTotalMonthlySubscriptionCost()
```

now uses:

```kotlin
RecurrenceCalculator.toMonthlyAmount(...)
```

Still problematic:

### Cost per use

`calculateUsageStats()` still uses:

```kotlin
subscription.amount / averageUsesPerMonth
```

For quarterly/annual subscriptions, this is wrong. It should use monthly normalized amount.

### Raw 90-day millis

`generateRecommendations()` still uses:

```kotlin
90L * 24 * 60 * 60 * 1000
```

Use `TimePeriodUtils.addDays(now, -90)` or a calendar-aware helper.

### Hardcoded euro strings

Recommendation descriptions still include `€`. This may violate the currency cleanup direction.

---

## 15. Occurrence model is too thin for full Phase 5

Current `RecurringOccurrence` has:

- source type/id
- occurrence key
- due date
- status
- linked expense
- expected/paid amount/currency
- frequency
- merchant
- category

Missing from the plan:

- merchant key
- linked planned expense ID
- date variance
- amount variance
- generatedAt separate from createdAt
- reminder state
- source confidence
- lifecycle event linkage

Not all are required in the first foundation PR, but they are needed for the full lifecycle contract.

---

# Positive feedback

## Good additions

1. `RecurringOccurrence` table is the right direction.
2. Unique `occurrenceKey` on occurrences is good.
3. `RecurringReminderDelivery` is the right abstraction for reminder dedup.
4. `RecurringOccurrenceExpander` correctly avoids raw millis in `advance()`.
5. `IRREGULAR` does not expand automatically.
6. `OccurrenceConflictResolver` at least introduces the idea of paid-vs-planned resolution.
7. `RecurringPlanProjectionService` introduces idempotent planned generation via `sourceOccurrenceKey`.
8. `SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()` is correctly normalized now.
9. `RecurringExpenseRepository` now delegates to `ManualRecurringExpenseDao`, which is the right consolidation direction.
10. The docs acknowledge several remaining TODOs honestly.

---

# Recommended next fixes, in order

## Must fix before continuing

1. Add Hilt bindings / `@Inject constructor()` for:
   - `RecurringOccurrenceExpander`
   - `OccurrenceConflictResolver`

2. Fix occurrence expansion anchor:
   - do not start expansion from `now`
   - start from `rule.nextDate` / anchor
   - use range only as output filter

3. Fix unsafe `linkExpenseToOccurrence()`:
   - no day-only match
   - use merchant/currency/amount/type matching

4. Remove raw `86_400_000L` usage:
   - day end
   - reminder scheduling

5. Add unique index for reminder delivery:
   - `(occurrenceId, reminderWindow)` unique

6. Wrap materialization in a DB transaction.

## Next high-priority work

7. Add `RecurringLifecycleEvent` table + DAO.
8. Add `BillReminderWorker`.
9. Move `BillReminderManager` to occurrence-backed reminders.
10. Remove `ManualExpenseRepository.database.recurringExpenseDao()` direct insert.
11. Remove `SmartBillNegotiationEngine` direct DAO injection.
12. Actually migrate `ForecastInputAssembler` / `SynthesisEngine` to occurrence-based inputs.
13. Update `FinancialStressForecastEngine` and `CashFlowCalculator` to use the expander/coordinator.
14. Add planned status + `linkedActualExpenseId`.
15. Add transaction lifecycle hook for actual expense -> occurrence/planned reconciliation. I searched for `RecurringLifecycleCoordinator` / `linkExpenseToOccurrence` in `TransactionLifecycleCoordinator` and did not see it wired yet.

---

# Final assessment

Phase 5 is **started well**, but it is not yet equivalent to the implementation plan.

Current status:

- **Schema foundation:** partially done.
- **Occurrence expansion:** exists but anchor logic is wrong.
- **Conflict resolution:** exists but too weak.
- **Materialization:** exists but non-transactional.
- **Reminder persistence:** started.
- **Reminder scheduling:** not done.
- **Planned generation:** started, but not fully linked.
- **Planned-vs-actual:** not done.
- **Forecast/cashflow migration:** mostly not done.
- **Subscription monthly total:** partially fixed.
- **DAO leak cleanup:** incomplete.
- **Transaction lifecycle hook:** not visibly wired.

I would treat `1ea5eb2` as **Phase 5 PR1/Foundation**, not Phase 5 closeout.

## Sources

- Commit: https://github.com/panospao7/Cost-agregator/commit/1ea5eb2a2def45d77fdb53a9d66aa451b761943b
- RecurringOccurrenceExpander: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt
- OccurrenceConflictResolver: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/OccurrenceConflictResolver.kt
- RecurringLifecycleCoordinator: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- RecurringOccurrenceMaterializer: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt
- RecurringPlanProjectionService: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt
- ForecastInputAssembler: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt
- SynthesisEngine: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt
- BillReminderManager: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt
- SubscriptionManagerEngine: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt
- ManualExpenseRepository: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt
- SmartBillNegotiationEngine: https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/negotiation/SmartBillNegotiationEngine.kt