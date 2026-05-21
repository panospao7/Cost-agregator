# Pipeline 4 Static Debug Report — Recurring Expenses / Bill Reminders

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 4 is **much improved** versus the earlier debug report, but it is **not fully closed**.

A lot of the big fixes are now present:

```text
actual expense -> occurrence PAID
planned expense fulfillment in direct link path
paid occurrence reminder suppression
atomic reminder claim before notify
worker enabled in WorkerSpec
default reminder windows
sourceType included in occurrenceKey
transaction side-effect hook calls recurring matching
terminal-status downgrade guard
status-change lifecycle event
write barriers on coordinator/repositories
rule deactivate/delete coordinator
```

However, the current implementation still has several user-impact bugs.

Highest remaining risks:

1. **Reminder claim recovery can reset freshly claimed overdue reminders**, allowing duplicate sends.
2. **Generating/reconciling occurrences now creates reminder deliveries by default**, so report/forecast-style calls can create old/past-due notification rows.
3. **Payment while a reminder is already `CLAIMED` is not fully suppressed**, so a user can still get a bill notification after paying.
4. **Rule lifecycle is split between two repositories and the new rule coordinator**, so some delete/update paths still leave stale occurrences/planned rows/reminders.
5. **Expense updates do not relink/unlink recurring occurrences**, so an edited expense can leave recurring state wrong.
6. **Auto-matched PAID occurrences can fulfill planned expenses without storing `linkedActualExpenseId`.**
7. **Occurrence key format changed to include source type, but migration/backfill is not proven.**
8. **Planned projection remains non-atomic and does not update stale open planned rows after rule edits.**

Current status: **yellow**. The main architecture is there, but recurring/reminder behavior still needs hardening before it should be considered production-stable.

---

# Sources checked

- Latest commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 4 report:  
  https://github.com/panospao7/Cost-agregator/blob/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-4-recurring-bill-reminders-debug-report.md

- Current code:
  - `RecurringLifecycleCoordinator.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
  - `RecurringOccurrenceMaterializer.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt
  - `RecurringRuleLifecycleCoordinator.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt
  - `RecurringOccurrenceExpander.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt
  - `OccurrenceConflictResolver.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/recurring/OccurrenceConflictResolver.kt
  - `RecurringPlanProjectionService.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt
  - `BillReminderWorker.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt
  - `SnoozeReminderReceiver.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt
  - `DismissReminderReceiver.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/service/reminder/DismissReminderReceiver.kt
  - `BillReminderManager.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt
  - `RecurringExpenseRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt
  - `ManualRecurringExpenseRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/ManualRecurringExpenseRepository.kt
  - `RecurringOccurrenceDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringOccurrenceDao.kt
  - `RecurringReminderDeliveryDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringReminderDeliveryDao.kt
  - `ManualRecurringExpenseDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt
  - `PlannedExpenseDao.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt
  - `TransactionSideEffectDispatcher.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
  - `WorkerSpec.kt`, `WorkerRegistry.kt`, `AppStartupCoordinator.kt`.

---

# 1. Tracker reconciliation

Master tracker currently says:

| ID | Tracker status |
|---|---|
| P4-P0-01 | fixed |
| P4-P0-02 | fixed |
| P4-P1-01 | fixed |
| P4-P1-02 | TODO |
| P4-P1-03 | fixed |
| P4-P1-04 | TODO |
| P4-P1-05 | deferred |
| P4-P1-06 | TODO |
| P4-P1-07 | TODO |
| P4-P1-08 | TODO |
| P4-P1-09 | fixed |
| P4-P1-10 | TODO |

My current status:

| ID | My status | Reason |
|---|---:|---|
| P4-P0-01 | **Partial, not fully fixed** | Direct `linkExpenseToOccurrence()` fulfills planned row with actual ID, but materializer auto-PAID path uses `fulfillByOccurrenceKey()` without `linkedActualExpenseId`. |
| P4-P0-02 | **Mostly fixed** | Due query filters planned occurrences and link path suppresses scheduled/snoozed reminders. Caveat: `CLAIMED` deliveries are not suppressed in the direct payment path. |
| P4-P1-01 | **Partial / still risky** | Atomic claim exists, but stale-claim recovery uses `scheduledAt`, not `claimedAt`, which can reset fresh claims. |
| P4-P1-02 | **Partial** | Some repository writes now guard/events; rule coordinator exists for deactivate/delete. But `RecurringExpenseRepository.delete/update` still bypass generated-row cleanup/regeneration. |
| P4-P1-03 | **Fixed for infrastructure** | Worker spec now `enabled = true` and registry schedules it. Missing runtime user setting remains product/design work. |
| P4-P1-04 | **Partial / risky fix** | Default windows now exist, but every `generateOccurrences()` call creates deliveries unless caller opts out, including reconciliation/report paths. |
| P4-P1-05 | **Partial** | New key format includes `sourceType`, but migration/backfill for existing occurrence/planned keys is not proven. |
| P4-P1-06 | **Mostly fixed / partial** | `TransactionSideEffectDispatcher` now calls recurring link/unlink on create/delete. Update/edit paths still do not relink. Failures are best-effort only. |
| P4-P1-07 | **Mostly fixed** | Materializer terminal-status guard prevents passive PAID/CANCELLED/SKIPPED/MISSED downgrade. |
| P4-P1-08 | **Partial** | Materializer writes `OCCURRENCE_STATUS_CHANGED`, but planned fulfillment and reminder suppression do not get durable lifecycle events. |
| P4-P1-09 | **Mostly fixed** | Coordinator/repositories use `DatabaseWriteBarrier`. Receivers still write directly and use `RestoreMaintenanceMode` directly. |
| P4-P1-10 | **Partial** | `markBillPaid()` is `DeprecationLevel.ERROR`, but method still exists and can be suppressed/called. |

Older medium issues:

| Old issue | My status |
|---|---:|
| P2-13 `updateOccurrenceStatus()` accepts arbitrary strings | **Open** |
| P2-14 permission failure not durable | **Partial** |
| P2-15 receivers use `runBlocking` | **Open** |
| P2-16 rule category lost | **Fixed** |
| P2-17 reminder time policy coarse | **Partial** |
| P2-18 planned projection not atomic | **Open** |

---

# 2. Original issue evaluation

## P4-P0-01 — Actual payment does not fulfill planned expense

### Current state

Direct payment link path is much better:

```text
RecurringLifecycleCoordinator.linkExpenseToOccurrence()
  -> occurrenceDao.claimForExpense(...)
  -> RecurringLifecycleEvent(OCCURRENCE_PAID)
  -> plannedExpenseDao.linkToActualExpense(...)
  -> reminderDeliveryDao.suppressOpenDeliveriesForOccurrence(...)
```

That path marks:

```text
occurrence.status = PAID
occurrence.linkedExpenseId = expenseId
planned.status = FULFILLED
planned.linkedActualExpenseId = expenseId
planned.openSourceOccurrenceKey = null
```

But materializer auto-match path is weaker:

```text
RecurringOccurrenceMaterializer.materialize()
  if entity.status == "PAID":
      plannedExpenseDao.fulfillByOccurrenceKey(entity.occurrenceKey, now)
```

`fulfillByOccurrenceKey()` marks planned row `FULFILLED`, but does **not** set `linkedActualExpenseId`.

### Classification

- **Original double-count bug:** mostly fixed in direct link path.
- **Traceability bug:** still open in auto-match path.
- **Audit gap:** planned fulfillment has no dedicated lifecycle event.

### Fix strategy

Replace materializer call:

```kotlin
plannedExpenseDao.fulfillByOccurrenceKey(...)
```

with a method that accepts actual expense ID when available:

```kotlin
@Query("""
UPDATE planned_expenses
SET status = 'FULFILLED',
    linkedActualExpenseId = :expenseId,
    openSourceOccurrenceKey = NULL,
    updatedAt = :updatedAt
WHERE sourceOccurrenceKey = :occurrenceKey
  AND status = 'PLANNED'
""")
suspend fun fulfillByOccurrenceKey(
    occurrenceKey: String,
    expenseId: Long,
    updatedAt: Long
): Int
```

Then write event:

```text
PLANNED_FULFILLED
metadata = { occurrenceKey, expenseId, source = "materializer_auto_match" }
```

---

## P4-P0-02 — Paid occurrence does not suppress scheduled reminders

### Current state

Mostly fixed.

Good:

- `linkExpenseToOccurrence()` calls `suppressOpenDeliveriesForOccurrence()`.
- `getDueReminders()` uses `getPendingDeliveriesForPlannedOccurrences()`.
- Materializer calls `suppressByOccurrenceId()` for auto-PAID occurrences.
- Worker no longer uses the unsafe raw `getPendingDeliveries()` path.

Remaining issue:

`linkExpenseToOccurrence()` uses:

```text
suppressOpenDeliveriesForOccurrence()
```

which suppresses only:

```text
SCHEDULED, SNOOZED
```

It does **not** suppress:

```text
CLAIMED
```

So if a worker already claimed a delivery and then the user pays the bill, the claimed notification may still be sent.

### Fix strategy

Use one suppression method everywhere:

```sql
UPDATE recurring_reminder_deliveries
SET status = 'CANCELLED'
WHERE occurrenceId = :occurrenceId
  AND status IN ('SCHEDULED', 'SNOOZED', 'CLAIMED', 'FAILED_TRANSIENT')
```

Also, worker should re-read occurrence status after claim and before notify:

```kotlin
val occurrence = coordinator.getOccurrenceById(reminder.occurrenceId)
if (occurrence?.status != "PLANNED") {
    coordinator.cancelClaimedReminder(reminder.id, "occurrence_not_planned")
    continue
}
```

---

## P4-P1-01 — Reminder dispatch not exactly-once safe

### Current state

Partially fixed.

Good:

```text
worker -> getDueReminders()
worker -> claimReminderDelivery(id)
claimDelivery() atomically sets status = CLAIMED
worker -> notify()
worker -> markReminderSent()
```

But there is a serious bug in stale-claim recovery.

Current recovery query resets claimed rows based on `scheduledAt <= staleThreshold`.

There is no `claimedAt`.

For overdue reminders, `scheduledAt` can be hours/days in the past. That means another worker run can reset a freshly claimed row back to `SCHEDULED` almost immediately, because its scheduled time is old even though the claim is new.

### User impact

Duplicate reminder notifications are still possible.

### Fix strategy

Add columns:

```text
claimedAt
lastAttemptAt
attemptCount
failureReason
updatedAt
```

Change claim query:

```sql
UPDATE recurring_reminder_deliveries
SET status = 'CLAIMED',
    claimedAt = :now,
    lastAttemptAt = :now,
    attemptCount = attemptCount + 1
WHERE id = :id
  AND (
    (status = 'SCHEDULED' AND scheduledAt <= :now)
    OR
    (status = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now)
  )
```

Change recovery:

```sql
UPDATE recurring_reminder_deliveries
SET status = 'SCHEDULED',
    claimedAt = NULL
WHERE status = 'CLAIMED'
  AND claimedAt < :staleClaimThreshold
```

Also, `markReminderSent()` should only update from `CLAIMED`:

```sql
UPDATE recurring_reminder_deliveries
SET status = 'SENT', lastSentAt = :now, notificationId = :notificationId
WHERE id = :id AND status = 'CLAIMED'
```

---

## P4-P1-02 — Recurring rule CRUD bypasses lifecycle/events

### Current state

Partial.

Good:

- `RecurringExpenseRepository` now checks `DatabaseWriteBarrier`.
- `RecurringExpenseRepository` writes rule lifecycle events.
- `ManualRecurringExpenseRepository.deleteById()` delegates to `RecurringRuleLifecycleCoordinator.deleteRule()`.
- `ManualRecurringExpenseRepository.setActiveStatus(false)` delegates to `deactivateRule()`.
- `RecurringRuleLifecycleCoordinator` atomically deactivates/deletes rule + generated rows.

Still risky:

1. `RecurringExpenseRepository.delete()` and `deleteById()` directly delete the rule and do **not** clean:
   - recurring occurrences,
   - reminder deliveries,
   - planned expenses.

2. `RecurringExpenseRepository.update()` writes the rule only. It does not:
   - update open occurrences,
   - update open planned expenses,
   - cancel/regenerate reminders,
   - preserve terminal paid/skipped/cancelled occurrences.

3. `ManualRecurringExpenseRepository.insert()` still directly inserts rule rows instead of using rule lifecycle coordinator.

4. DAO mutation surface is still public.

5. Rule events are not in the same DB transaction as the rule mutation in repository paths. If event write fails, mutation still succeeds; if delete fails after event, audit can lie.

### User impact

Editing or deleting recurring bills through the wrong repository path can leave stale reminders/planned rows.

### Fix strategy

Make `RecurringRuleLifecycleCoordinator` the only writer.

Required methods:

```kotlin
createRule(request): RuleResult.Created
updateRule(ruleId, patch): RuleResult.Updated
deactivateRule(ruleId, reason)
deleteRule(ruleId, cleanupPolicy)
advanceNextDate(ruleId, reason)
```

All repository write methods should delegate.

Static guard:

```bash
grep -R "manualRecurringExpenseDao\.\(insert\|update\|delete\|deleteById\|setActiveStatus\|updateNextDate\)" app/src/main/java
```

Allowed only in:

```text
RecurringRuleLifecycleCoordinator
Room migrations
tests/debug repair tools
```

---

## P4-P1-03 — Bill reminder worker disabled by static config

### Current state

Fixed for infrastructure.

`WorkerSpec.DEFAULTS["bill_reminder_periodic"]` is now:

```text
enabled = true
version = 2
repeatIntervalHours = 6
```

`WorkerRegistry` includes `BillReminderWorker.schedule()`, and `AppStartupCoordinator` schedules via the registry.

Remaining design issue:

There is still no real user-facing runtime setting model:

```text
billRemindersEnabled
default reminder windows
quiet hours
reminder time of day
notification permission state
```

Now the risk has shifted from “worker never runs” to “worker can run without granular user settings.”

### Fix strategy

Keep infrastructure enabled, but add runtime setting gate:

```kotlin
if (!settings.billRemindersEnabled) return Result.success()
```

Also schedule/cancel when setting changes.

---

## P4-P1-04 — Reminder deliveries only created when caller passes `reminderWindows`

### Current state

Partially fixed, but with a dangerous side effect.

Good:

```kotlin
DEFAULT_REMINDER_WINDOWS = ["3_DAYS_BEFORE", "DUE_DAY", "OVERDUE"]
```

`generateOccurrences()` resolves empty windows to defaults.

Problem:

`generateOccurrences()` is used by multiple paths, not only reminder scheduling. For example:

```text
reconcilePlannedVsActual()
  -> generateOccurrences(ruleId, historicalStart, today)
```

This now creates reminder deliveries for historical/past occurrences by default.

### User impact

Opening/running a reconciliation/report path can create overdue reminder deliveries, which the worker may later dispatch.

### Fix strategy

Replace the window parameter with an explicit options object:

```kotlin
data class OccurrenceGenerationOptions(
    val createReminderDeliveries: Boolean,
    val reminderWindows: List<String> = emptyList(),
    val generationSource: String
)
```

Rules:

```text
projection/reminder scheduling -> createReminderDeliveries = true
reconciliation/report/debug -> createReminderDeliveries = false
historical generation -> false unless explicitly requested
```

---

## P4-P1-05 — `occurrenceKey` can collide across source types

### Current state

Code is fixed:

```kotlin
"$sourceType|$sourceId|$dayStart|${frequency.name}"
```

But migration/backfill is not proven.

Risk:

Existing rows created with the old key:

```text
sourceId|dayStart|frequency
```

can coexist with new rows:

```text
sourceType|sourceId|dayStart|frequency
```

This can create duplicate occurrences and break planned-expense links.

### Fix strategy

Add migration/backfill:

```sql
UPDATE recurring_occurrences
SET occurrenceKey = sourceType || '|' || sourceId || '|' || dueDate || '|' || frequency
WHERE occurrenceKey NOT LIKE '%|%|%|%';
```

Also update:

```text
planned_expenses.sourceOccurrenceKey
planned_expenses.openSourceOccurrenceKey
```

Acceptance:

```text
old_key_rows_migrated
planned_rows_migrated_to_new_keys
regeneration_after_migration_does_not_duplicate_occurrences
```

---

## P4-P1-06 — Expense → occurrence linking not globally guaranteed

### Current state

Mostly fixed for create/delete.

`TransactionSideEffectDispatcher.dispatchOnCreated()` now calls:

```kotlin
recurringLifecycleCoordinator.get().linkExpenseToOccurrence(expenseId)
```

`dispatchOnDeleted()` calls:

```kotlin
unlinkExpenseFromOccurrence(expenseId)
```

Remaining risks:

1. The side effect is best-effort only. Failure logs but is not durable.
2. Expense updates do not relink/unlink recurring state.
3. Create flows that bypass transaction lifecycle side effects can still miss recurring matching.
4. If a linked expense is edited so it no longer matches, the occurrence remains PAID.
5. If a non-matching expense is edited so it now matches, no recurring link is attempted.

### Fix strategy

Add recurring side effects to updates:

```kotlin
onExpenseUpdated(expenseId, beforeSnapshot, afterSnapshot)
```

Behavior:

```text
if expense was linked and no longer matches -> unlink/reopen planned/reminders
if expense was unlinked and now matches -> link
if linked amount changed but still matches -> update paidAmount/paidCurrency and planned fulfillment metadata
```

Also write durable diagnostic event on recurring match failure.

---

## P4-P1-07 — Existing PAID occurrences downgraded by regeneration

### Current state

Mostly fixed.

`RecurringOccurrenceMaterializer` has terminal status guard:

```text
PAID, CANCELLED, SKIPPED, IGNORED, MISSED
```

It only auto-updates an existing row if existing status is not terminal.

### Remaining caveat

This is string-based and not enforced by DB/enum. Other writer methods can still write arbitrary status strings.

### Fix strategy

Add typed status enum and transition policy shared by:

```text
materializer
updateOccurrenceStatus()
link/unlink
receivers
DAO update helpers
```

---

## P4-P1-08 — Materializer updates status without lifecycle event

### Current state

Partially fixed.

`RecurringOccurrenceMaterializer` now writes:

```text
OCCURRENCE_STATUS_CHANGED
```

when an existing occurrence status changes.

Still missing:

- `OCCURRENCE_AUTO_MATCHED`
- `PLANNED_FULFILLED`
- `REMINDER_SUPPRESSED_PAID`
- count/result metadata for planned/reminder side effects
- durable event when suppression/fulfillment affects zero rows unexpectedly

### Fix strategy

Add event taxonomy:

```text
OCCURRENCE_AUTO_MATCHED
OCCURRENCE_STATUS_CHANGED
PLANNED_GENERATED
PLANNED_UPDATED
PLANNED_FULFILLED
REMINDER_SUPPRESSED_PAID
REMINDER_CLAIMED
REMINDER_DELIVERY_FAILED
RULE_UPDATED_REGENERATED
```

---

## P4-P1-09 — Shared recurring writes miss restore guard

### Current state

Mostly fixed.

Good:

- coordinator methods use `DatabaseWriteBarrier`;
- repositories use `DatabaseWriteBarrier`;
- projection uses `DatabaseWriteBarrier`.

Caveats:

- receivers write directly and use `RestoreMaintenanceMode` instead of shared barrier;
- direct DAO surface is still public.

### Fix strategy

Inject `DatabaseWriteBarrier` into receivers or route receiver action through coordinator methods:

```text
snoozeReminder(deliveryId)
dismissReminder(deliveryId)
```

---

## P4-P1-10 — Legacy `BillReminderManager.markBillPaid()` mixed behavior

### Current state

Partially mitigated.

`markBillPaid()` is:

```text
@Deprecated(level = DeprecationLevel.ERROR)
```

But the method still exists and still only advances `nextDate`. It does not update occurrences/planned/reminders.

### Fix strategy

Delete it, or change implementation to throw in non-test builds:

```kotlin
error("markBillPaid legacy path removed; use MarkRecurringBillPaidUseCase")
```

Preferred replacement:

```kotlin
MarkRecurringBillPaidUseCase(
    ruleId,
    actualExpenseId? = null,
    createActualExpenseIfMissing = true
)
```

---

# 3. New/current issues found

## P4-NEW-01 — Reconciliation/report calls can create reminder deliveries

### Severity

P1.

### Evidence

`reconcilePlannedVsActual()` calls `generateOccurrences()`.

Current `generateOccurrences()` converts empty reminder windows into default reminder windows. Therefore reconciliation creates reminder deliveries even though it looks like a reporting method.

### User impact

Viewing a recurring bill analysis can create notification rows, including for historical due dates.

### Fix

Add generation options and disable reminders for report/reconciliation paths.

---

## P4-NEW-02 — Stale `CLAIMED` recovery can reset fresh claims

### Severity

P1.

### Evidence

Recovery uses `scheduledAt <= staleThreshold` instead of `claimedAt`.

For overdue reminders, scheduled time is already old, so a newly claimed reminder can be immediately considered stale by another worker.

### User impact

Duplicate reminder notifications.

### Fix

Add `claimedAt` and recover based on claim age.

---

## P4-NEW-03 — Payment while reminder is `CLAIMED` can still send notification

### Severity

P1.

### Evidence

Direct payment suppression only handles `SCHEDULED` and `SNOOZED`, not `CLAIMED`.

Worker does not re-check occurrence status after claim and before notify.

### User impact

User can pay bill and still receive “Bill due” notification.

### Fix

Suppress `CLAIMED`; worker revalidates occurrence status before notify.

---

## P4-NEW-04 — Auto-PAID materializer fulfillment loses actual-expense link

### Severity

P1/P2.

### Evidence

`fulfillByOccurrenceKey()` has no `expenseId` parameter.

### User impact

Planned row may show fulfilled but cannot trace which actual expense fulfilled it.

### Fix

Use `linkedExpenseId` from resolved occurrence and set `linkedActualExpenseId`.

---

## P4-NEW-05 — Rule update does not regenerate open occurrences/planned expenses

### Severity

P1.

### Evidence

`RecurringExpenseRepository.update()` writes only the rule row and event.

### User impact

Changing amount/date/category/frequency can leave future occurrences and planned expenses stale.

### Fix

Route update through rule lifecycle coordinator:

```text
load old rule
apply patch
update rule
cancel/update/regenerate open occurrences
update open planned rows
preserve terminal PAID/SKIPPED/CANCELLED rows
write events
```

---

## P4-NEW-06 — Rule delete path differs by repository

### Severity

P1.

### Evidence

`ManualRecurringExpenseRepository.deleteById()` delegates to rule coordinator.  
`RecurringExpenseRepository.deleteById()` directly deletes the rule.

### User impact

Depending on caller, deleting a rule can either clean generated rows or orphan them.

### Fix

Make both repositories delegate to the same coordinator or remove one write surface.

---

## P4-NEW-07 — Expense edits do not maintain recurring links

### Severity

P1.

### Evidence

Transaction side effects link on create and unlink on delete. Update side effects do not call recurring lifecycle.

### User impact

Editing merchant/date/amount/currency/type/ownership can make recurring paid state wrong.

### Fix

Add recurring reconcile to `dispatchOnUpdated()` using before/after snapshots.

---

## P4-NEW-08 — Reminder failure state lacks operational metadata

### Severity

P2.

### Evidence

`markReminderFailed()` sets status and writes event, but delivery entity has no:

```text
lastAttemptAt
attemptCount
failureReason
updatedAt
```

### User impact

UI/debug cannot show how many attempts happened or why the current state exists without parsing events.

### Fix

Add columns and update them in claim/failure/sent paths.

---

## P4-NEW-09 — `claimDelivery()` can claim snoozed rows too early if called directly

### Severity

P2.

### Evidence

Claim condition:

```sql
status IN ('SCHEDULED', 'SNOOZED')
AND (
  scheduledAt <= now
  OR (status = 'SNOOZED' AND snoozedUntil <= now)
)
```

For a snoozed delivery whose original `scheduledAt` is in the past but `snoozedUntil` is future, a direct call to `claimDelivery(id)` can claim too early.

The current worker avoids this because its due query filters snoozed rows correctly, but the DAO method itself is unsafe.

### Fix

Make status-specific claim condition:

```sql
(
  status = 'SCHEDULED' AND scheduledAt <= :now
)
OR
(
  status = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now
)
```

---

## P4-NEW-10 — Receivers directly mutate delivery status and use `runBlocking`

### Severity

P2.

### Evidence

`SnoozeReminderReceiver` and `DismissReminderReceiver` call DAOs directly and use `runBlocking(Dispatchers.IO)` inside `onReceive()`.

### User impact

Potential receiver blocking and lifecycle bypass.

### Fix

Use `goAsync()` + coroutine/WorkManager and delegate to coordinator:

```text
snoozeReminder(deliveryId, duration)
dismissReminder(deliveryId)
```

---

## P4-NEW-11 — Recurring tables lack foreign keys

### Severity

P2, P1 for orphan cleanup.

### Evidence

Migration comment states recurring occurrence/reminder tables intentionally omit FK clauses to match entity definitions.

### User impact

Direct deletes can leave orphan deliveries/events/planned rows.

### Fix

Either add FKs with migrations or add orphan-cleanup diagnostics/tests:

```sql
SELECT * FROM recurring_reminder_deliveries
WHERE occurrenceId NOT IN (SELECT id FROM recurring_occurrences);
```

---

## P4-NEW-12 — Reconciliation totals include skipped/cancelled/missed in total planned

### Severity

P2.

### Evidence

`reconcilePlannedVsActual()` increments `totalPlanned` before checking occurrence status.

Comment says skipped/cancelled/missed are not relevant, but code already added their expected amount.

### User impact

Drift report overstates planned spend.

### Fix

Only add to `totalPlanned` for included statuses:

```kotlin
when (occ.status) {
    "PLANNED" -> { totalPlanned += occ.expectedAmount; unmatchedCount++ }
    "PAID" -> { totalPlanned += occ.expectedAmount; totalActual += paid }
}
```

---

## P4-NEW-13 — `notificationId` is never persisted on sent reminders

### Severity

P2.

### Evidence

Entity has `notificationId`, worker computes it, but `markReminderSent()` only sets status and `lastSentAt`.

### User impact

App cannot reliably cancel/update sent reminder notifications later.

### Fix

Change:

```kotlin
markReminderSent(deliveryId, notificationId)
```

and persist it.

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize these:

1. **Duplicate reminder sends still possible due stale claim recovery bug.**
2. **Reconciliation/report generation can create past-due reminder deliveries.**
3. **Payment while delivery is CLAIMED can still send a paid-bill notification.**
4. **Rule delete/update paths can leave stale occurrences/reminders/planned expenses.**
5. **Expense edits can leave recurring links wrong.**
6. **Auto-match planned fulfillment loses actual expense traceability.**
7. **Occurrence key migration/backfill not proven.**
8. **Planned projection is non-atomic and stale after rule edit.**
9. **Receiver direct writes and `runBlocking` can cause lifecycle/UX issues.**
10. **Reconciliation totals include skipped/cancelled planned amounts.**

## Architectural / cleanup work

Important, but lower immediate severity:

1. Typed recurring status/event enums.
2. Static DAO mutation guard.
3. Full user reminder settings model.
4. Rule-specific reminder windows.
5. Quiet hours and local time-of-day scheduling.
6. FK migration or orphan repair worker.
7. Remove legacy `BillReminderManager` mutation path.
8. More complete lifecycle event taxonomy.

---

# 5. Recommended implementation plan

## PR 1 — Fix reminder claim state machine

### Goal

No duplicate sends from stale recovery or overlapping workers.

### Files

- `RecurringReminderDelivery.kt`
- `RecurringReminderDeliveryDao.kt`
- `RecurringLifecycleCoordinator.kt`
- `BillReminderWorker.kt`
- migration

### Tasks

1. Add:
   - `claimedAt`
   - `lastAttemptAt`
   - `attemptCount`
   - `failureReason`
   - `updatedAt`
2. Claim sets `CLAIMED`, `claimedAt = now`.
3. Recover stale claims by `claimedAt`, not `scheduledAt`.
4. `markReminderSent()` updates only `CLAIMED` rows.
5. Persist `notificationId`.
6. Add `REMINDER_CLAIMED`, `REMINDER_SENT`, `REMINDER_DELIVERY_FAILED` events.

### Acceptance tests

```text
two_workers_claim_same_reminder_only_one_sends
fresh_claim_for_overdue_reminder_is_not_recovered
stale_claim_after_threshold_is_recovered
mark_sent_only_from_claimed
notification_id_persisted_on_sent
```

---

## PR 2 — Separate occurrence generation from reminder scheduling

### Goal

Reports/reconciliation do not create reminders.

### Files

- `RecurringLifecycleCoordinator.kt`
- `RecurringPlanProjectionService.kt`
- `RecurringOccurrenceMaterializer.kt`
- `reconcilePlannedVsActual()`

### Tasks

1. Add `OccurrenceGenerationOptions`.
2. `generateOccurrences()` requires explicit reminder behavior.
3. Projection/reminder path enables reminder creation.
4. Reconciliation/report/debug disables reminder creation.
5. Historical ranges default reminders off.

### Acceptance tests

```text
reconcilePlannedVsActual_does_not_create_reminder_deliveries
projectFromRule_creates_reminders_when_enabled
historical_generation_does_not_create_past_due_notifications_by_default
```

---

## PR 3 — Payment/reminder race hardening

### Goal

No notification after payment.

### Files

- `RecurringLifecycleCoordinator.kt`
- `RecurringReminderDeliveryDao.kt`
- `BillReminderWorker.kt`

### Tasks

1. Suppress `CLAIMED` deliveries on payment.
2. Worker revalidates occurrence status after claim.
3. If occurrence no longer planned, cancel claimed delivery.
4. Write `REMINDER_SUPPRESSED_PAID`.

### Acceptance tests

```text
payment_suppresses_claimed_delivery
worker_claim_then_payment_before_notify_does_not_send
paid_occurrence_not_returned_by_due_query
```

---

## PR 4 — Rule lifecycle single writer

### Goal

Rule create/update/delete/deactivate are lifecycle-owned.

### Files

- `RecurringRuleLifecycleCoordinator.kt`
- `RecurringExpenseRepository.kt`
- `ManualRecurringExpenseRepository.kt`
- `ManualRecurringExpenseDao.kt`
- tests/static guard

### Tasks

1. Move create/update/delete/deactivate to rule coordinator.
2. Make repositories delegate only.
3. For update:
   - update open occurrences,
   - update open planned rows,
   - suppress/regenerate reminders as needed,
   - preserve terminal rows.
4. Make rule event + mutation atomic.
5. Add static guard for direct DAO mutation.

### Acceptance tests

```text
update_rule_amount_updates_open_occurrence_and_planned
update_rule_category_updates_open_planned_category
delete_rule_removes_or_cancels_future_occurrences
delete_rule_suppresses_future_reminders
repository_delete_paths_both_delegate_to_coordinator
```

---

## PR 5 — Expense update recurring reconciliation

### Goal

Editing expenses keeps recurring state correct.

### Files

- `TransactionLifecycleCoordinator.kt`
- `TransactionSideEffectDispatcher.kt`
- `RecurringLifecycleCoordinator.kt`

### Tasks

1. Add recurring update hook.
2. If linked expense no longer matches, unlink.
3. If unlinked expense now matches, link.
4. If linked amount changed, update paid amount/planned metadata.
5. Record durable diagnostic event on failure.

### Acceptance tests

```text
editing_linked_expense_amount_outside_tolerance_reopens_occurrence
editing_unlinked_expense_to_match_links_occurrence
editing_linked_expense_date_reconciles_old_and_new_occurrence
```

---

## PR 6 — Planned fulfillment traceability

### Goal

Every fulfilled planned expense knows the actual expense.

### Files

- `PlannedExpenseDao.kt`
- `RecurringOccurrenceMaterializer.kt`
- `RecurringLifecycleCoordinator.kt`
- tests

### Tasks

1. Add `fulfillByOccurrenceKey(occurrenceKey, expenseId, updatedAt)`.
2. Use `resolved.linkedExpenseId`.
3. Write `PLANNED_FULFILLED`.
4. Emit warning event if no planned row exists.

### Acceptance tests

```text
auto_paid_occurrence_sets_planned_linkedActualExpenseId
manual_link_sets_planned_linkedActualExpenseId
planned_fulfilled_event_written
```

---

## PR 7 — Occurrence key migration/backfill

### Goal

No duplicate occurrences after key format change.

### Files

- `RecurringOccurrenceExpander.kt`
- Room migration
- `PlannedExpenseDao.kt`
- tests

### Tasks

1. Backfill old occurrence keys to sourceType-prefixed keys.
2. Backfill planned source keys.
3. Handle collision conflicts safely.
4. Add migration test.

### Acceptance tests

```text
legacy_occurrence_key_migrates_to_sourceType_format
planned_sourceOccurrenceKey_migrates_with_occurrence
generation_after_migration_does_not_duplicate
different_source_types_same_id_do_not_collide
```

---

## PR 8 — Receiver cleanup

### Goal

Snooze/dismiss use lifecycle coordinator and do not block broadcast thread.

### Files

- `SnoozeReminderReceiver.kt`
- `DismissReminderReceiver.kt`
- `RecurringLifecycleCoordinator.kt`

### Tasks

1. Add:
   - `snoozeReminder(deliveryId, duration)`
   - `dismissReminder(deliveryId)`
2. Use `goAsync()` or WorkManager.
3. Status transitions guarded and evented.
4. Do not snooze/cancel non-open rows incorrectly.

### Acceptance tests

```text
snooze_receiver_uses_goAsync_and_finishes
dismiss_receiver_uses_goAsync_and_finishes
snooze_nonexistent_delivery_no_success_event
dismiss_cancelled_delivery_noops_or_records_noop
```

---

## PR 9 — Reconciliation/report correctness

### Goal

Reports do not overstate planned spend.

### Files

- `RecurringLifecycleCoordinator.kt`
- tests

### Tasks

1. Count `totalPlanned` only for PLANNED/PAID.
2. Exclude SKIPPED/CANCELLED/MISSED unless explicit option says include.
3. Add per-status counts.

### Acceptance tests

```text
reconciliation_excludes_cancelled_from_totalPlanned
reconciliation_excludes_skipped_from_totalPlanned
reconciliation_paid_counts_expected_and_actual
```

---

# 6. Suggested tracker updates

Update Pipeline 4 tracker:

| ID | Suggested status |
|---|---|
| P4-P0-01 | Partial |
| P4-P0-02 | Mostly fixed / claimed-race caveat |
| P4-P1-01 | Partial, not fixed |
| P4-P1-02 | Partial |
| P4-P1-03 | Fixed for infrastructure; runtime setting still needed |
| P4-P1-04 | Partial / risky default |
| P4-P1-05 | Partial until migration/backfill proven |
| P4-P1-06 | Mostly fixed / update caveat |
| P4-P1-07 | Mostly fixed |
| P4-P1-08 | Partial |
| P4-P1-09 | Mostly fixed |
| P4-P1-10 | Partial / deprecated but present |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P4-NEW-01 | P1 | Reconciliation/report calls can create reminder deliveries |
| P4-NEW-02 | P1 | Stale `CLAIMED` recovery can reset fresh claims |
| P4-NEW-03 | P1 | Payment while reminder is `CLAIMED` can still send notification |
| P4-NEW-04 | P1/P2 | Auto-PAID planned fulfillment loses actual-expense link |
| P4-NEW-05 | P1 | Rule update does not regenerate open occurrences/planned expenses |
| P4-NEW-06 | P1 | Rule delete path differs by repository |
| P4-NEW-07 | P1 | Expense edits do not maintain recurring links |
| P4-NEW-08 | P2 | Reminder failure state lacks operational metadata |
| P4-NEW-09 | P2 | `claimDelivery()` can claim snoozed rows too early if called directly |
| P4-NEW-10 | P2 | Receivers directly mutate delivery status and use `runBlocking` |
| P4-NEW-11 | P2/P1 | Recurring tables lack FKs / orphan protection |
| P4-NEW-12 | P2 | Reconciliation totals include skipped/cancelled/missed in total planned |
| P4-NEW-13 | P2 | `notificationId` is never persisted on sent reminders |

---

# 7. Golden tests for Pipeline 4

Add or verify:

```text
actual_expense_link_marks_occurrence_paid
actual_expense_link_fulfills_planned_with_expense_id
actual_expense_link_suppresses_scheduled_snoozed_claimed_reminders
auto_paid_occurrence_fulfills_planned_with_expense_id
auto_paid_occurrence_writes_planned_fulfilled_event
two_workers_claim_same_reminder_only_one_sends
fresh_claim_for_overdue_reminder_not_recovered
stale_claim_recovered_after_claimedAt_threshold
payment_after_claim_before_notify_does_not_send
missing_notification_permission_marks_failed_permission
failed_permission_not_returned_as_due
worker_enabled_scheduled_by_registry
worker_disabled_by_runtime_setting_skips
generate_occurrences_default_for_projection_creates_reminders
reconcilePlannedVsActual_does_not_create_reminders
different_source_types_do_not_collide_occurrenceKey
legacy_occurrence_keys_migrated
rule_category_propagates_to_occurrence_and_planned
update_rule_amount_updates_open_occurrences_and_planned
update_rule_preserves_paid_occurrences
delete_rule_cleans_occurrences_reminders_planned
all_repository_rule_delete_paths_delegate_to_coordinator
editing_linked_expense_to_nonmatch_reopens_occurrence
editing_unlinked_expense_to_match_links_occurrence
delete_actual_expense_unlinks_occurrence_and_reopens_planned
snooze_receiver_uses_goAsync
dismiss_receiver_uses_goAsync
reconciliation_excludes_cancelled_skipped_missed_from_totalPlanned
sent_reminder_persists_notificationId
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "ManualRecurringExpenseDao" app/src/main/java
grep -R "manualRecurringExpenseDao\." app/src/main/java
grep -R "RecurringExpenseRepository" app/src/main/java
grep -R "ManualRecurringExpenseRepository" app/src/main/java
grep -R "generateOccurrences" app/src/main/java
grep -R "linkExpenseToOccurrence" app/src/main/java
grep -R "unlinkExpenseFromOccurrence" app/src/main/java
grep -R "claimDelivery" app/src/main/java
grep -R "recoverStaleClaimedDeliveries" app/src/main/java
grep -R "getPendingDeliveries" app/src/main/java
grep -R "markBillPaid" app/src/main/java
grep -R "runBlocking(Dispatchers.IO)" app/src/main/java/com/yourname/expensetracker/service/reminder
grep -R "fulfillByOccurrenceKey" app/src/main/java
grep -R "sourceOccurrenceKey" app/src/main/java
```

Allowed direct recurring DAO mutation list should be explicit:

```text
RecurringRuleLifecycleCoordinator
RecurringOccurrenceMaterializer
RecurringLifecycleCoordinator
Room migrations
debug/test repair tools only
```

Definition of done:

```text
- No report/reconcile path creates reminder deliveries.
- Reminder claim uses claimedAt and cannot be prematurely recovered.
- Payment suppresses SCHEDULED, SNOOZED, CLAIMED reminders.
- Worker revalidates occurrence status before notify.
- Rule CRUD is single-writer and transactionally evented.
- Rule updates refresh open generated rows without touching terminal rows.
- Expense create/update/delete all reconcile recurring state.
- Auto-paid planned fulfillment records linkedActualExpenseId.
- Occurrence key migration/backfill is tested.
- Receivers no longer block with runBlocking and delegate to coordinator.
- Reconciliation totals exclude skipped/cancelled/missed unless explicitly requested.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Fix reminder claim/recovery state machine** — prevents duplicate sends.
2. **Separate occurrence generation from reminder scheduling** — prevents report-created notifications.
3. **Fix payment vs claimed-reminder race** — prevents “bill due” after payment.
4. **Make rule lifecycle coordinator the only writer** — prevents stale generated rows.
5. **Add recurring reconcile on expense update** — keeps links correct after edits.
6. **Fix auto-PAID planned fulfillment traceability**.
7. **Add occurrence-key migration/backfill test.**
8. **Clean receivers: coordinator + goAsync/WorkManager.**
9. **Fix reconciliation totals.**
10. **Add runtime reminder settings / quiet hours / time-of-day policy.**