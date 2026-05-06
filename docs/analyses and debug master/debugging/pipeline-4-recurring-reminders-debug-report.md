# Pipeline 4 Debugging Report — Recurring Expenses / Planned Occurrences / Bill Reminders

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local/device execution.

## 1. Executive summary

Pipeline 4 is intended to be:

```text
manual recurring rule / detected recurring pattern
→ RecurringLifecycleCoordinator
→ RecurringOccurrenceExpander
→ OccurrenceConflictResolver
→ RecurringOccurrenceMaterializer
→ RecurringOccurrenceDao
→ RecurringReminderDeliveryDao
→ BillReminderWorker
→ user reminder notification
→ actual expense created through TransactionLifecycleCoordinator
→ RecurringLifecycleCoordinator.linkExpenseToOccurrence()
→ occurrence PAID
→ forecast/cashflow/dashboard no-double-count
```

The design is good, but the current implementation has several serious stability risks.

Highest-risk findings:

1. **Read/projection flows call `generateOccurrences()` and accidentally schedule reminder deliveries.**
2. **`RecurringPlanProjectionService` likely violates or bypasses the `planned_expenses.openSourceOccurrenceKey` invariant.**
3. **`PlannedExpenseDao.linkToActualExpense()` and `updateStatus()` do not refresh `openSourceOccurrenceKey`, risking constraint failures or stale unique keys.**
4. **`OccurrenceConflictResolver` can match recurring occurrences to invalid actual transactions because it does not exclude `isNotMine`, `TRANSFER`, `DEPOSIT`, or `UNKNOWN`.**
5. **Reminder snooze/dismiss receivers bypass Hilt/lifecycle/restore guards and use direct DB builders + `System.currentTimeMillis()`.**
6. **Snoozed reminders are never eligible again, because due-reminder query only selects `status='SCHEDULED'`.**
7. **`BillReminderWorker` notification body uses `occurrenceId` as the amount, not occurrence merchant/amount.**
8. **Updating or deleting an actual expense does not unlink/reconcile the paid recurring occurrence.**
9. Existing recurring lifecycle tests are mock-only; DB-backed occurrence/reminder/planned no-double-count tests are missing.

Main recommendation:

> Split recurring lifecycle into **pure projection** and **side-effecting materialization/reminder scheduling**. Forecast/cashflow/reconciliation must not schedule bill reminders accidentally.

---

# 2. Intended architecture contract

From the dependency map, Pipeline 4 is:

```text
RecurringExpensesScreen
→ RecurringLifecycleCoordinator
   → RecurringOccurrenceExpander
   → OccurrenceConflictResolver
   → RecurringOccurrenceMaterializer
      → RecurringOccurrenceDao
      → RecurringReminderDeliveryDao
      → RecurringLifecycleEventDao

TransactionLifecycleCoordinator
→ RecurringLifecycleCoordinator.linkExpenseToOccurrence()

BillReminderWorker
→ RecurringLifecycleCoordinator.getDueReminders()
→ notification
→ markReminderSent()
```

Downstream consumers include:

- `RecurringExpensesViewModel`
- `ManualRecurringExpenseViewModel`
- `HomeViewModel`
- `BudgetViewModel`
- `FinancialWeatherRepository`
- `CashFlowCalculator`
- `ForecastInputAssembler`
- `BillReminderWorker`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

---

# 3. Actual code path

## 3.1 Manual rule creation

Manual recurring rules are stored in:

```text
manual_recurring_expenses
```

Primary files:

- `ManualRecurringExpense.kt`
- `ManualRecurringExpenseDao.kt`
- `ManualRecurringExpenseRepository.kt`
- `RecurringExpenseRepository.kt`
- `ManualRecurringExpenseViewModel.kt`
- `RecurringExpensesScreen.kt`

`ManualRecurringExpenseViewModel.addRecurringExpense()` creates:

```kotlin
ManualRecurringExpense(
  merchant = merchant,
  amount = amount,
  frequency = frequency,
  nextDate = nextDate,
  note = note,
  isSubscription = false,
  isActive = true
)
```

Potential issue: currency is not passed, so it defaults to `"EUR"` even though the ViewModel has `homeCurrency`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt

---

## 3.2 Occurrence generation

`RecurringLifecycleCoordinator.generateOccurrences()`:

```text
restore write guard
→ load ManualRecurringExpense
→ advance anchor from rule.nextDate until startDate
→ expand occurrences
→ load actual expenses in range
→ resolve candidates against actual expenses
→ materializer.materialize(resolved, reminderWindows)
```

Default reminder window is:

```kotlin
listOf("DUE_DAY")
```

This means **every call to `generateOccurrences()` schedules due-day reminders unless caller overrides it**.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/OccurrenceConflictResolver.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt

---

## 3.3 Reminder materialization

`RecurringOccurrenceMaterializer.materialize()`:

```text
for each resolved occurrence:
  insert occurrence with IGNORE
  if existing and status changed → update
  if new → write OCCURRENCE_GENERATED
  if status PLANNED → create reminder deliveries for reminder windows
```

Important details:

- reminders are created for planned occurrences,
- reminders use unique `(occurrenceId, reminderWindow)`,
- status update does not write lifecycle event,
- existing occurrence details are not updated unless status changed,
- insert return is checked for occurrences, but planned expense insert elsewhere is not checked.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt

---

## 3.4 Actual expense matching

`TransactionLifecycleCoordinator` calls:

```kotlin
recurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId)
```

`linkExpenseToOccurrence()`:

```text
load expense
skip isNotMine
skip TRANSFER / DEPOSIT
same calendar day
same merchant key
amount within ±10%
same currency ignore-case
update occurrence to PAID
write OCCURRENCE_PAID event
```

This is a good actual-payment hook.

But there is no equivalent hook for:

- expense update,
- expense delete,
- expense category/merchant/date/amount change,
- recurring occurrence unlink/relink.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

---

## 3.5 Bill reminder worker

`BillReminderWorker`:

```text
restore write guard
→ WorkerSpec enabled check
→ coordinator.getDueReminders()
→ send notification
→ coordinator.markReminderSent()
```

Issues:

- notification text does not load occurrence details,
- `markReminderSent()` does not write `REMINDER_SENT` lifecycle event,
- if notification permission is missing, `sendNotification()` catches `SecurityException`, but worker still marks the reminder as sent,
- notification ID is based only on `occurrenceId`, so multiple reminder windows for one occurrence can collide.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

---

# 4. Major findings

## Finding P0-1 — Forecast/cashflow/read paths accidentally schedule bill reminders

This is the biggest Pipeline 4 risk.

`generateOccurrences()` defaults to:

```kotlin
reminderWindows = listOf("DUE_DAY")
```

Several read/projection paths call it:

- `ForecastInputAssembler.assemble()`
- `CashFlowCalculator.calculateDailyCashFlow()`
- `CashFlowCalculator.getUpcomingBills()`
- `RecurringPlanProjectionService.projectFromRule()`
- `RecurringLifecycleCoordinator.reconcilePlannedVsActual()`

These methods are not “user scheduled reminders” flows. They are forecast, cashflow, projection, or reconciliation flows.

But because `generateOccurrences()` always materializes reminders by default, opening a forecast/cashflow/dashboard-like screen can create reminder rows.

Worst case:

```text
reconcilePlannedVsActual(monthsBack = 3)
→ generateOccurrences(past range)
→ create DUE_DAY reminders in the past
→ BillReminderWorker sees scheduledAt <= now
→ sends old bill reminders
```

This can create confusing reminder notifications for old/past occurrences.

### Recommended fix

Split APIs:

```kotlin
suspend fun previewOccurrences(...)
suspend fun materializeOccurrences(..., scheduleReminders: Boolean)
suspend fun scheduleRemindersForOccurrences(...)
```

Or minimally:

```kotlin
generateOccurrences(
  ruleId,
  startDate,
  endDate,
  reminderWindows = emptyList()
)
```

and require reminder scheduling callers to pass explicit windows:

```kotlin
generateOccurrences(..., reminderWindows = listOf("DUE_DAY", "3_DAYS_BEFORE"))
```

For forecast/cashflow/reconciliation:

```kotlin
reminderWindows = emptyList()
```

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

---

## Finding P0-2 — Planned expense projection likely breaks `openSourceOccurrenceKey` invariant

`PlannedExpense` has this documented invariant:

```text
PLANNED with sourceOccurrenceKey
→ openSourceOccurrenceKey = sourceOccurrenceKey

non-PLANNED
→ openSourceOccurrenceKey = NULL
```

But `RecurringPlanProjectionService.projectFromRule()` inserts:

```kotlin
PlannedExpense(
  sourceOccurrenceKey = occ.occurrenceKey,
  sourceRecurringRuleId = ruleId,
  ...
)
```

It does **not** set:

```kotlin
openSourceOccurrenceKey = occ.occurrenceKey
```

The entity default is `null`.

If the DB CHECK constraint from migration 106→107 is active, this insert can fail or be ignored. If SQLite `OR IGNORE` applies, it can silently skip. The service still increments:

```kotlin
created++
```

without checking the insert result.

So the app can report that a planned expense was created when no row exists.

### Recommended fix

Set the invariant on insert:

```kotlin
val planned = PlannedExpense(
  ...
  sourceOccurrenceKey = occ.occurrenceKey,
  openSourceOccurrenceKey = occ.occurrenceKey,
  status = "PLANNED"
)

val insertedId = plannedExpenseDao.insertPlannedExpense(planned)
if (insertedId > 0) created++
```

Also update the DAO comment: Room insert with `IGNORE` returns `-1`, not `0`, for ignored inserts.

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt

---

## Finding P0-3 — Planned expense status updates do not maintain `openSourceOccurrenceKey`

`PlannedExpenseDao.updateStatus()` changes:

```sql
status = :status
```

but does not update `openSourceOccurrenceKey`.

`linkToActualExpense()` changes:

```sql
linkedActualExpenseId = :expenseId,
status = 'FULFILLED'
```

but also does not set:

```sql
openSourceOccurrenceKey = NULL
```

Given the entity invariant:

```text
non-PLANNED → openSourceOccurrenceKey IS NULL
```

these updates can:

- violate CHECK constraints,
- leave stale unique keys,
- prevent future planned row generation,
- cause forecast dedupe errors.

### Recommended fix

Make status updates invariant-safe:

```sql
UPDATE planned_expenses
SET status = :status,
    updatedAt = :updatedAt,
    openSourceOccurrenceKey =
      CASE WHEN :status = 'PLANNED' THEN sourceOccurrenceKey ELSE NULL END
WHERE id = :id
```

and:

```sql
UPDATE planned_expenses
SET linkedActualExpenseId = :expenseId,
    status = 'FULFILLED',
    updatedAt = :updatedAt,
    openSourceOccurrenceKey = NULL
WHERE id = :id
```

Or require every status mutation to call `refreshOpenOccurrenceKey(id)` inside the same transaction.

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt

---

## Finding P0-4 — Conflict resolver can match non-spending transactions

`RecurringLifecycleCoordinator.linkExpenseToOccurrence()` correctly skips:

```text
isNotMine
TRANSFER
DEPOSIT
```

But `OccurrenceConflictResolver.resolve()` does not apply those filters.

It only checks:

```text
same day
merchant match
amount ±10%
same currency
```

So during occurrence generation, an occurrence can be marked `PAID` by:

- a transfer,
- a deposit,
- an `isNotMine` expense,
- possibly an unknown transaction type.

That creates incorrect no-double-count behavior because a future subscription can disappear from forecast/reminders after matching the wrong actual transaction.

### Recommended fix

Move matching policy into one shared component:

```kotlin
RecurringActualExpenseMatcher
```

Use it from both:

- `OccurrenceConflictResolver`
- `RecurringLifecycleCoordinator.linkExpenseToOccurrence()`

Eligibility should require:

```text
!isNotMine
transactionType == PURCHASE or WITHDRAWAL according to recurring policy
not DEPOSIT
not TRANSFER
not UNKNOWN unless explicitly allowed
currency compare ignore-case
merchant key match
date tolerance
amount tolerance
```

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/OccurrenceConflictResolver.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

---

## Finding P0-5 — Snoozed reminders are never due again

`SnoozeReminderReceiver` sets:

```text
status = "SNOOZED"
snoozedUntil = now + 24h
```

But `RecurringReminderDeliveryDao.getPendingDeliveries(now)` only selects:

```sql
status = 'SCHEDULED' AND scheduledAt <= :now
```

It does not include:

```text
status = 'SNOOZED' AND snoozedUntil <= now
```

Therefore snoozed reminders never come back.

### Recommended fix

Change due query:

```sql
SELECT * FROM recurring_reminder_deliveries
WHERE
  (status = 'SCHEDULED' AND scheduledAt <= :now)
  OR
  (status = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now)
ORDER BY COALESCE(snoozedUntil, scheduledAt)
```

When sending a snoozed reminder, either set status back to `SENT` after notification or transition through `SCHEDULED`.

Priority: highest.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringReminderDeliveryDao.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt

---

## Finding P1-1 — BillReminderWorker marks reminders SENT even when notification permission is missing

`sendNotification()` catches `SecurityException` when notification permission is missing.

But the worker then always calls:

```kotlin
coordinator.markReminderSent(reminder.id)
```

So a reminder can be marked `SENT` even though no notification was displayed.

### Recommended fix

Make `sendNotification()` return a result:

```kotlin
sealed interface ReminderDispatchResult {
  data class Sent(val notificationId: Int)
  data class PermissionDenied(val reason: String)
  data class Failed(val error: Throwable)
}
```

Only call `markReminderSent()` on `Sent`.

For permission denied, mark:

```text
FAILED
metadata = MISSING_POST_NOTIFICATIONS_PERMISSION
```

or leave scheduled with a retry policy.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

---

## Finding P1-2 — Bill reminder notification body is wrong

`BillReminderWorker.buildNotificationBody()` does:

```kotlin
val amount = "%.2f".format(reminder.occurrenceId)
return "Bill due: $amount EUR"
```

This uses `occurrenceId` as the amount.

So notification text can say:

```text
Bill due: 42.00 EUR
```

where `42` is just the occurrence ID.

### Recommended fix

Load the occurrence:

```kotlin
val occurrence = occurrenceDao.getById(reminder.occurrenceId)
```

or add coordinator method:

```kotlin
getReminderContext(deliveryId)
```

Then body should use:

```text
merchant
expectedAmount
expectedCurrency
dueDate
reminderWindow
```

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

---

## Finding P1-3 — Reminder status changes do not write lifecycle events

`RecurringLifecycleEvent` documents event types:

```text
REMINDER_SCHEDULED
REMINDER_SENT
```

But:

- `RecurringOccurrenceMaterializer` writes `REMINDER_SCHEDULED`,
- `markReminderSent()` updates delivery status only,
- `SnoozeReminderReceiver` updates delivery status only,
- `DismissReminderReceiver` updates delivery status only.

Missing events:

```text
REMINDER_SENT
REMINDER_SNOOZED
REMINDER_DISMISSED
REMINDER_FAILED
```

### Recommended fix

Route all reminder transitions through coordinator methods:

```kotlin
markReminderSent(deliveryId, notificationId)
snoozeReminder(deliveryId, until)
dismissReminder(deliveryId)
markReminderFailed(deliveryId, reason)
```

Each should:

```text
update delivery
write RecurringLifecycleEvent
respect RestoreMaintenanceMode
use TimeProvider
```

Priority: high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringLifecycleEvent.kt

---

## Finding P1-4 — Snooze/dismiss receivers bypass Hilt, restore mode, and TimeProvider

`SnoozeReminderReceiver` and `DismissReminderReceiver` directly do:

```kotlin
val db = AppDatabase.fileBuilder(context).build()
System.currentTimeMillis()
runBlocking(Dispatchers.IO)
```

Problems:

- bypasses Hilt,
- bypasses `RestoreMaintenanceMode`,
- bypasses `TimeProvider`,
- no lifecycle events,
- no `BackgroundJobRun`,
- uses direct database builder outside official test/production path,
- can write during restore maintenance mode,
- `runBlocking` in a broadcast receiver is fragile.

### Recommended fix

Use Hilt broadcast receivers or delegate to WorkManager/use case:

```text
ReminderActionReceiver
→ enqueue OneTimeWorkRequest(ReminderActionWorker)
→ RecurringLifecycleCoordinator.snoozeReminder()/dismissReminder()
```

Or inject via Hilt:

```kotlin
@AndroidEntryPoint
class SnoozeReminderReceiver : BroadcastReceiver()
```

and call coordinator.

Priority: high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/DismissReminderReceiver.kt

---

## Finding P1-5 — Existing occurrence details are not updated when a rule changes

`RecurringOccurrenceMaterializer.materialize()` handles existing rows like this:

```text
if existing.status != entity.status:
    update
else:
    skipped++
```

So if a recurring rule changes:

```text
amount: 15.99 → 17.99
merchant: Netflix → Netflix Premium
currency: EUR → USD
categoryId: null → streaming category
```

but the occurrence status remains `PLANNED`, the existing occurrence is not updated.

Because occurrence key excludes amount and merchant:

```text
sourceId|dayStart|frequency
```

this is very likely.

### Recommended fix

Compare mutable fields too:

```text
expectedAmount
expectedCurrency
merchant
categoryId
dueDate
frequency
```

If any differ, update occurrence and write:

```text
OCCURRENCE_UPDATED
```

Do not update fields that should be immutable after `PAID` unless policy says so.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt

---

## Finding P1-6 — Materializer updates PAID/PLANNED status without event logging

If an existing occurrence changes status inside `materialize()`:

```kotlin
occurrenceDao.update(...)
updated++
```

No lifecycle event is written.

So status can change:

```text
PLANNED → PAID
PAID → PLANNED
```

without `OCCURRENCE_PAID`, `OCCURRENCE_REOPENED`, etc.

### Recommended fix

When status changes:

```text
PLANNED → PAID: OCCURRENCE_PAID
PAID → PLANNED: OCCURRENCE_REOPENED or ACTUAL_UNLINKED
PLANNED → SKIPPED: OCCURRENCE_SKIPPED
```

Write events inside the same transaction.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt

---

## Finding P1-7 — No unlink/reconcile on actual expense update/delete

When an expense is created, `TransactionLifecycleCoordinator` attempts:

```kotlin
linkExpenseToOccurrence(expenseId)
```

But if that expense is later:

- deleted,
- changed amount,
- changed date,
- changed merchant,
- changed currency,
- changed to transfer/deposit,
- marked not mine,

there is no visible recurring unlink/relink hook.

The occurrence can remain:

```text
status = PAID
linkedExpenseId = deleted/wrong expense
```

This can suppress future reminders and forecasts incorrectly.

### Recommended fix

Add coordinator methods:

```kotlin
unlinkExpenseFromOccurrence(expenseId, reason)
reconcileLinkedExpense(expenseId, before, after)
```

Call them from transaction lifecycle update/delete:

```text
on update: if matching fields changed → relink/reconcile
on delete: unlink occurrence → PLANNED or CANCELLED according to due date policy
```

Priority: high.

---

## Finding P1-8 — Recurring occurrence/reminder/event entities lack foreign keys

Visible entities define indexes but not foreign keys for:

```text
RecurringOccurrence.linkedExpenseId → expenses.id
RecurringReminderDelivery.occurrenceId → recurring_occurrences.id
RecurringLifecycleEvent.occurrenceId → recurring_occurrences.id
```

Without FK constraints, these can become orphaned.

### Recommended fix

Add FKs if migration cost is acceptable:

```text
reminder occurrenceId → occurrence id ON DELETE CASCADE
event occurrenceId → occurrence id ON DELETE SET NULL or CASCADE
occurrence linkedExpenseId → expense id ON DELETE SET NULL
```

If not, add a database integrity scanner check for orphaned recurring links.

Priority: medium-high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringOccurrence.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringReminderDelivery.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringLifecycleEvent.kt

---

## Finding P1-9 — Manual recurring UI ignores inactive rows

`ManualRecurringExpenseRepository.getAll()` returns active-only rows.

But `ManualRecurringExpenseViewModel.loadRecurringExpenses()` does:

```kotlin
val expenses = recurringExpenseRepository.getAll()
val activeExpenses = expenses.filter { it.isActive }
```

Since `getAll()` is already active-only, inactive rows never appear. After toggling inactive, the item disappears and cannot be toggled back from that screen.

### Recommended fix

Use:

```kotlin
getAllIncludingInactive()
```

in the manual management screen.

Keep active-only repository calls for public forecast/dashboard paths.

Priority: medium.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ManualRecurringExpenseRepository.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt

---

## Finding P1-10 — Manual recurring entries default to EUR and `createdAt=0`

`ManualRecurringExpense` defaults:

```text
currency = "EUR"
createdAt = 0L sentinel
```

`ManualRecurringExpenseViewModel.addRecurringExpense()` does not pass actual home currency or `createdAt`.

So non-EUR users can accidentally create EUR recurring rules.

Also, DAO sorting by `createdAt` can be wrong because many rows have `0L`.

### Recommended fix

Use:

```kotlin
val currency = homeCurrency.first()
val now = timeProvider.now()
ManualRecurringExpense(
  currency = currency,
  createdAt = now,
  ...
)
```

Add validation:

```text
merchant nonblank
amount > 0
currency valid ISO
nextDate normalized to start of day
frequency not invalid
```

Priority: medium-high.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt

---

## Finding P2-1 — Reminder notification IDs can collide

Notification ID:

```kotlin
val notificationId = (delivery.occurrenceId % Int.MAX_VALUE).toInt()
```

If there are multiple reminder windows for the same occurrence:

```text
7_DAYS_BEFORE
3_DAYS_BEFORE
DUE_DAY
OVERDUE
```

they all share the same notification ID and can replace each other.

### Recommended fix

Use delivery ID:

```kotlin
val notificationId = (delivery.id % Int.MAX_VALUE).toInt()
```

Store it:

```text
delivery.notificationId = notificationId
```

Priority: medium.

---

## Finding P2-2 — Event DAO is too minimal

`RecurringLifecycleEventDao` only supports:

```text
insert
getEventsForOccurrence
```

For debugging you need:

```text
getRecentEvents(limit)
getEventsByType(type)
getEventsBetween(start, end)
getEventsForRule(sourceType, sourceId)
getReminderEvents(deliveryId)
getFailureEvents()
```

Also order by:

```sql
ORDER BY occurredAt ASC, id ASC
```

for timeline reconstruction.

Priority: medium.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringLifecycleEventDao.kt

---

# 5. Debugging checklist for Pipeline 4

## Rule creation

Check:

- [ ] merchant nonblank
- [ ] amount positive
- [ ] currency valid and from user home/default
- [ ] `createdAt` set
- [ ] `nextDate` normalized
- [ ] active/inactive state visible
- [ ] category if needed
- [ ] subscription fields only set when subscription

## Occurrence expansion

Check:

- [ ] start/end range half-open
- [ ] anchor date rolled forward correctly
- [ ] weekly/biweekly/monthly/quarterly/semiannual/annual correct
- [ ] DST safe
- [ ] leap day safe
- [ ] month-end safe
- [ ] irregular handled intentionally
- [ ] occurrence key stable
- [ ] amount/currency/merchant changes update existing planned occurrences

## Conflict resolution

Check:

- [ ] excludes `isNotMine`
- [ ] excludes `TRANSFER`
- [ ] excludes `DEPOSIT`
- [ ] excludes `UNKNOWN`
- [ ] merchant key match
- [ ] currency compare ignore-case
- [ ] amount tolerance
- [ ] date tolerance policy
- [ ] one actual expense matches at most one occurrence

## Materialization

Check:

- [ ] occurrence inserted once
- [ ] duplicate occurrence skipped
- [ ] changed details updated
- [ ] status transition event written
- [ ] reminder scheduling explicit, not automatic for read paths
- [ ] reminder insert result checked
- [ ] no past reminders scheduled from reconciliation/reporting paths

## Planned projection

Check:

- [ ] `sourceOccurrenceKey` set
- [ ] `openSourceOccurrenceKey` set for `PLANNED`
- [ ] insert result checked
- [ ] status updates refresh open key
- [ ] fulfilled planned expenses excluded from forecast
- [ ] planned + actual not double-counted

## Reminder worker

Check:

- [ ] due query includes matured snoozes
- [ ] notification permission checked
- [ ] notification success required before SENT
- [ ] actual merchant/amount loaded
- [ ] notification ID uses delivery ID
- [ ] `REMINDER_SENT` event written
- [ ] failure event written
- [ ] idempotent rerun
- [ ] restore mode respected

## Snooze/dismiss

Check:

- [ ] Hilt or use-case path
- [ ] RestoreMaintenanceMode respected
- [ ] TimeProvider used
- [ ] lifecycle event written
- [ ] snoozed reminder becomes due again
- [ ] dismissed reminder never sends again
- [ ] no direct DB builder outside approved infrastructure

## Actual payment link

Check:

- [ ] create expense links occurrence
- [ ] update expense reconciles link
- [ ] delete expense unlinks occurrence
- [ ] planned expense marked fulfilled
- [ ] dashboard does not double-count planned + actual
- [ ] forecast includes future planned but excludes fulfilled current planned
- [ ] reminders suppressed after paid

---

# 6. Recommended fix plan

## PR 1 — Stop accidental reminder scheduling

Change default:

```kotlin
reminderWindows = emptyList()
```

or add explicit:

```kotlin
scheduleReminders = false
```

Then update true reminder flows to pass windows explicitly.

Acceptance:

```text
ForecastInputAssembler, CashFlowCalculator, getUpcomingBills, reconcilePlannedVsActual do not create reminder deliveries.
```

---

## PR 2 — Fix planned expense invariant

Update `RecurringPlanProjectionService`:

```kotlin
openSourceOccurrenceKey = occ.occurrenceKey
```

Check insert result before `created++`.

Update `PlannedExpenseDao.updateStatus()` and `linkToActualExpense()` to maintain `openSourceOccurrenceKey`.

Acceptance:

```text
projectFromRule creates real rows.
FULFILLED rows clear openSourceOccurrenceKey.
Repeated projection does not duplicate.
```

---

## PR 3 — Unify recurring actual matching policy

Create:

```kotlin
RecurringActualExpenseMatcher
```

Use it in:

- `OccurrenceConflictResolver`
- `RecurringLifecycleCoordinator.linkExpenseToOccurrence`

Acceptance:

```text
deposit/transfer/notMine/unknown never satisfy recurring actual payment.
```

---

## PR 4 — Fix reminder dispatch state machine

Add coordinator methods:

```kotlin
markReminderSent(deliveryId, notificationId)
markReminderFailed(deliveryId, reason)
snoozeReminder(deliveryId, until)
dismissReminder(deliveryId)
```

Worker and receivers must call these methods.

Acceptance:

```text
permission-denied notification is not marked SENT.
snoozed reminder returns when snoozedUntil passes.
events are written.
```

---

## PR 5 — Fix reminder notification content

Load occurrence details for notification body.

Acceptance:

```text
notification says:
"Netflix due today: 15.99 EUR"
not:
"Bill due: 123.00 EUR"
```

---

## PR 6 — Reconcile actual expense update/delete

Add recurring hooks to transaction lifecycle:

```text
onCreated → link
onUpdated → relink/reconcile
onDeleted → unlink/reopen
```

Acceptance:

```text
deleted actual expense reopens occurrence or marks it planned/missed according to policy.
dashboard/forecast no-double-count remains correct.
```

---

# 7. Tests to add

## 7.1 `RecurringLifecycleCoordinatorDbContractTest`

Use real in-memory Room.

Cases:

1. generate monthly rule,
2. creates occurrences,
3. creates lifecycle events,
4. does not create reminders when `reminderWindows=emptyList()`,
5. creates reminders only when requested,
6. duplicate generation is idempotent,
7. changed amount updates existing planned occurrence,
8. restore mode blocks writes.

---

## 7.2 `RecurringReminderDeliveryDaoTest`

Cases:

1. insert delivery,
2. unique `(occurrenceId, reminderWindow)`,
3. due scheduled query,
4. snoozed due query,
5. dismissed not due,
6. sent not due.

---

## 7.3 `BillReminderWorkerContractTest`

Cases:

1. due reminder sends notification and marks SENT,
2. missing notification permission marks FAILED or leaves scheduled,
3. second run does not send duplicate,
4. snoozed reminder not sent before snoozedUntil,
5. snoozed reminder sent after snoozedUntil,
6. restore mode skips safely,
7. notification body uses merchant/amount/currency.

---

## 7.4 `RecurringPlanProjectionServiceDbContractTest`

Cases:

1. projected planned expense has `sourceOccurrenceKey`,
2. projected planned expense has `openSourceOccurrenceKey`,
3. duplicate projection does not duplicate,
4. fulfilled planned expense clears open key,
5. status update refreshes open key,
6. insert result is respected.

---

## 7.5 `OccurrenceConflictResolverPolicyTest`

Cases:

1. purchase can match,
2. deposit cannot match,
3. transfer cannot match,
4. not-mine cannot match,
5. unknown cannot match,
6. currency compare ignore-case,
7. merchant key match,
8. amount ±10% boundary,
9. same actual cannot match two occurrences.

---

## 7.6 `RecurringActualExpenseUpdateDeleteScenarioTest`

Seed:

```text
monthly Netflix rule
planned occurrence
actual Netflix payment linked
```

Actions:

```text
update actual amount/date/merchant
delete actual payment
```

Assert:

```text
occurrence relinked or reopened correctly
planned expense status updated
reminders suppressed or re-enabled according to policy
dashboard and forecast do not double-count
```

---

## 7.7 `RecurringForecastNoReminderSideEffectsTest`

Run:

```text
ForecastInputAssembler.assemble()
CashFlowCalculator.getUpcomingBills()
CashFlowCalculator.calculateDailyCashFlow()
reconcilePlannedVsActual()
```

Assert:

```text
no new recurring_reminder_deliveries rows
unless explicitly requested
```

This is the most important regression test for Pipeline 4.

---

# 8. Suggested canonical scenario

## `recurring_planned_actual_no_double_count`

Seed:

```text
home currency EUR
manual recurring rule:
  merchant = Netflix
  amount = 12.99 EUR
  frequency = MONTHLY
  nextDate = 2026-05-10

budget:
  subscriptions monthly = 50 EUR
```

Run:

```text
1. generate occurrences for May-July with reminderWindows = ["7_DAYS_BEFORE", "DUE_DAY"]
2. project planned expenses for 3 months
3. simulate actual Netflix expense on 2026-05-10
4. TransactionLifecycleCoordinator creates actual expense
5. RecurringLifecycleCoordinator links actual to occurrence
6. run forecast/dashboard
7. run BillReminderWorker
```

Expected:

```text
May occurrence = PAID
May linkedExpenseId = actual expense ID
June/July occurrences = PLANNED
May planned expense = FULFILLED or excluded from forecast
June/July planned expenses = PLANNED
dashboard current month counts actual Netflix once
forecast includes June/July future Netflix
reminders for May suppressed after payment
reminders for June/July scheduled once
transaction event CREATED exists
recurring event OCCURRENCE_PAID exists
no duplicate planned/actual counting
```

---

# 9. Most likely real instability sources

Ranked:

1. **Projection/read paths scheduling reminders accidentally.**
   - Can cause random old/future reminder notifications.

2. **Broken planned-expense open key invariant.**
   - Can silently skip planned rows or break status transitions.

3. **Snoozed reminders never returning.**
   - User snoozes once, reminder disappears forever.

4. **Bill reminder marked SENT even when notification permission missing.**
   - User never sees reminder, app thinks it sent.

5. **Wrong reminder notification text.**
   - Uses occurrence ID as amount.

6. **Invalid actual transaction matching.**
   - Deposits/transfers/not-mine can mark bills paid.

7. **No update/delete reconciliation.**
   - Actual expense changes leave stale PAID occurrences.

8. **Mock-only lifecycle coverage.**
   - Real Room constraints, events, and no-double-count behavior unproven.

---

# 10. Final recommendation

For Pipeline 4, stabilize in this order:

```text
1. Stop generateOccurrences() from scheduling reminders by default.
2. Fix PlannedExpense openSourceOccurrenceKey invariant.
3. Fix snooze/dismiss/send reminder state machine.
4. Fix reminder notification content and permission result handling.
5. Unify actual-payment matching policy.
6. Add expense update/delete recurring reconciliation.
7. Add DB-backed no-double-count scenario tests.
```

Guiding rule:

> Forecasting, cashflow, and reconciliation may materialize/query occurrences, but they must not accidentally schedule user notifications.

Second guiding rule:

> A recurring occurrence should be paid by exactly one valid actual spending expense, and the app must never count planned + actual twice.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — Forecast/cashflow/read paths accidentally schedule bill reminders
**STATUS: CONFIRMED — FIXED**
- `RecurringLifecycleCoordinator.generateOccurrences()` default `reminderWindows` changed from `listOf("DUE_DAY")` to `emptyList()`.
- `RecurringOccurrenceMaterializer.materialize()` default also changed to `emptyList()`.
- ForecastInputAssembler, CashFlowCalculator, FinancialStressForecastEngine, and RecurringPlanProjectionService all call `generateOccurrences()` without passing `reminderWindows`, so they will now correctly NOT schedule any reminders.
- True reminder scheduling paths must now explicitly pass `reminderWindows = listOf("DUE_DAY")` (or other windows).

## Finding P0-2 — Planned expense projection likely breaks openSourceOccurrenceKey invariant
**STATUS: CONFIRMED — FIXED**
- `RecurringPlanProjectionService.projectFromRule()` now sets `openSourceOccurrenceKey = occ.occurrenceKey` on the `PlannedExpense` constructor, satisfying the entity invariant.
- Insert result is now checked: `if (insertedId > 0) created++` instead of unconditionally incrementing.

## Finding P0-3 — Planned expense status updates do not maintain openSourceOccurrenceKey
**STATUS: CONFIRMED — FIXED**
- `PlannedExpenseDao.updateStatus()` now includes `openSourceOccurrenceKey = CASE WHEN :status = 'PLANNED' THEN sourceOccurrenceKey ELSE NULL END`.
- `PlannedExpenseDao.linkToActualExpense()` now explicitly sets `openSourceOccurrenceKey = NULL`.

## Finding P0-4 — Conflict resolver can match non-spending transactions
**STATUS: CONFIRMED — FIXED**
- `OccurrenceConflictResolver.resolve()` now excludes `isNotMine`, `TRANSFER`, `DEPOSIT`, and `UNKNOWN` transaction types before matching.
- Currency comparison changed to case-insensitive (`equals(ignoreCase = true)`).

## Finding P0-5 — Snoozed reminders are never eligible again
**STATUS: CONFIRMED — FIXED**
- `RecurringReminderDeliveryDao.getPendingDeliveries()` query updated to include: `OR (status = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now)`.
- Ordering changed to `ORDER BY COALESCE(snoozedUntil, scheduledAt)`.

## Finding P1-1 — BillReminderWorker marks reminders SENT even when notification permission is missing
**STATUS: CONFIRMED — FIXED**
- `sendNotification()` now returns `Boolean` indicating delivery success.
- `markReminderSent()` is only called when `sendNotification()` returns `true`.
- `SecurityException` (missing permission) returns `false`, leaving the delivery status unchanged for retry.

## Finding P1-2 — Bill reminder notification body is wrong
**STATUS: CONFIRMED — FIXED**
- `buildNotificationBody()` now loads the actual `RecurringOccurrence` via `coordinator.getOccurrenceById()`.
- Notification text now shows real merchant name, amount, and currency (e.g. "Netflix due: 12.99 EUR").
- Falls back to generic text if occurrence cannot be loaded.

## Finding P1-3 — Reminder status changes do not write lifecycle events
**STATUS: FIXED (2026-05-06):** `markReminderSent()` writes `REMINDER_SENT` event. `SnoozeReminderReceiver` writes `REMINDER_SNOOZED`. `DismissReminderReceiver` writes `REMINDER_DISMISSED`.

## Finding P1-4 — Snooze/dismiss receivers bypass Hilt, restore mode, and TimeProvider
**STATUS: CONFIRMED — FIXED**
- Both `SnoozeReminderReceiver` and `DismissReminderReceiver` now use `@AndroidEntryPoint` with Hilt injection.
- They inject `RecurringReminderDeliveryDao`, `TimeProvider`, and `RestoreMaintenanceMode` instead of creating a raw `AppDatabase.fileBuilder(context).build()`.
- Both receivers now check `restoreMaintenanceMode.isWritesAllowed()` before making DB changes.
- Both receivers now use `timeProvider.now()` instead of `System.currentTimeMillis()`.

## Finding P1-5 — Existing occurrence details are not updated when a rule changes
**STATUS: CONFIRMED — NOT FIXED (materializer enhancement needed)**

## Finding P1-6 — Materializer updates PAID/PLANNED status without event logging
**STATUS: CONFIRMED — NOT FIXED (event-writing enhancement needed)**

## Finding P1-7 — No unlink/reconcile on actual expense update/delete
**STATUS: FIXED (2026-05-06):** `RecurringLifecycleCoordinator.unlinkExpenseFromOccurrence()` resets linked occurrence to PLANNED on delete. `TransactionLifecycleCoordinator.updateExpense()` reconciles by unlinking + relinking when key fields change.

## Finding P1-8 — Recurring occurrence/reminder/event entities lack foreign keys
**STATUS: CONFIRMED — NOT FIXED (Room migration required)**

## Finding P1-9 — Manual recurring UI ignores inactive rows
**STATUS: CONFIRMED — NOT FIXED (UI enhancement)**

## Finding P1-10 — Manual recurring entries default to EUR and createdAt=0
**STATUS: CONFIRMED — NOT FIXED (ViewModel enhancement needed)**

## Finding P2-1 — Reminder notification IDs can collide
**STATUS: CONFIRMED — FIXED**
- Notification ID changed from `delivery.occurrenceId % Int.MAX_VALUE` to `delivery.id % Int.MAX_VALUE`, ensuring unique IDs per delivery rather than per occurrence.

## Finding P2-2 — Event DAO is too minimal
**STATUS: CONFIRMED — NOT FIXED (enhancement)**

---

# 12. New issues discovered (not in original report)

No additional issues were discovered beyond those documented in the original report. The report's analysis was thorough.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Default reminderWindows = emptyList() | `RecurringLifecycleCoordinator.kt`, `RecurringOccurrenceMaterializer.kt` | P0-1 |
| Set openSourceOccurrenceKey on insert + check result | `RecurringPlanProjectionService.kt` | P0-2 |
| Maintain openSourceOccurrenceKey in status updates | `PlannedExpenseDao.kt` | P0-3 |
| Exclude isNotMine/TRANSFER/DEPOSIT/UNKNOWN + case-insensitive currency | `OccurrenceConflictResolver.kt` | P0-4 |
| Include snoozed reminders in pending query | `RecurringReminderDeliveryDao.kt` | P0-5 |
| Only mark SENT on successful notification delivery | `BillReminderWorker.kt` | P1-1 |
| Load real occurrence data for notification body | `BillReminderWorker.kt` | P1-2 |
| Use delivery.id for notification ID | `BillReminderWorker.kt` | P2-1 |
| Migrate SnoozeReminderReceiver to Hilt + restore guard + TimeProvider | `SnoozeReminderReceiver.kt` | P1-4 |
| Migrate DismissReminderReceiver to Hilt + restore guard + TimeProvider | `DismissReminderReceiver.kt` | P1-4 |

---

# 14. Remaining work priority

1. ~~**P1-3**: Route all reminder transitions through coordinator methods that write lifecycle events~~ **DONE**
2. ~~**P1-7**: Add expense update/delete hooks to reconcile linked recurring occurrences~~ **DONE**
3. **P1-5**: Update materializer to compare mutable fields (amount, merchant, currency, categoryId) on existing occurrences
4. **P1-6**: Add event logging for status transitions in materializer
5. **P1-10**: Fix ManualRecurringExpenseViewModel to pass home currency and set createdAt from TimeProvider
6. **P1-8**: Add foreign key constraints (Room migration)
7. **P1-9**: Make manual recurring management screen show inactive rows

---

# Sources

- Dependency map  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `RecurringLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

- `RecurringOccurrenceExpander.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt

- `OccurrenceConflictResolver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/OccurrenceConflictResolver.kt

- `RecurringOccurrenceMaterializer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt

- `RecurringOccurrence.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringOccurrence.kt

- `RecurringReminderDelivery.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringReminderDelivery.kt

- `RecurringLifecycleEvent.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringLifecycleEvent.kt

- `RecurringOccurrenceDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringOccurrenceDao.kt

- `RecurringReminderDeliveryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringReminderDeliveryDao.kt

- `RecurringLifecycleEventDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringLifecycleEventDao.kt

- `BillReminderWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

- `SnoozeReminderReceiver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt

- `DismissReminderReceiver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/DismissReminderReceiver.kt

- `RecurringPlanProjectionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt

- `PlannedExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt

- `PlannedExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt

- `ManualRecurringExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt

- `ManualRecurringExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt

- `ManualRecurringExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ManualRecurringExpenseRepository.kt

- `RecurringExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt

- `ManualRecurringExpenseViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt

- `RecurringExpensesScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/recurring/RecurringExpensesScreen.kt

- `CashFlowCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt

- `ForecastInputAssembler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt

- Existing mock recurring lifecycle test  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt