# Pipeline 4 Debug Report — Recurring Expenses / Bill Reminders

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 4 is **partially refactored but not clean/stable yet**.

Good new infrastructure exists:

- `RecurringLifecycleCoordinator`
- `RecurringOccurrenceExpander`
- `OccurrenceConflictResolver`
- `RecurringOccurrenceMaterializer`
- `RecurringOccurrence`
- `RecurringReminderDelivery`
- `RecurringLifecycleEvent`
- `BillReminderWorker`
- snooze/dismiss receivers
- `RecurringPlanProjectionService`

But the full runtime contract is still **yellow/orange** because the lifecycle is split between old direct repositories and the new coordinator.

The biggest remaining risks:

1. planned expenses can remain `PLANNED` after an actual expense pays the occurrence;
2. paid occurrences do not suppress scheduled reminder deliveries;
3. reminder dispatch is not exactly-once safe;
4. recurring rule CRUD still bypasses lifecycle/events/restore guard/timestamps;
5. the worker is disabled by default through static `WorkerSpec`;
6. reminder deliveries are only created when callers pass `reminderWindows`;
7. occurrence keys can collide across source types;
8. actual expense → occurrence linking is not globally guaranteed after transaction creation.

Current state: **not production-stable**.  
Best label: **beta infrastructure present, lifecycle contract incomplete**.

---

# Severity scale

- **P0 / Critical:** can double-count money, send wrong reminders after payment, or corrupt lifecycle state.
- **P1 / High:** lifecycle bypass, restore/write-barrier hole, non-idempotent worker behavior, missing audit.
- **P2 / Medium:** edge correctness, diagnostics gap, stale UI/forecast behavior.
- **P3 / Low:** cleanup, naming, maintainability.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| Recurring rule saved | Partial. Direct repositories save rules, but no lifecycle event/restore guard/timestamps. |
| Occurrence expansion correct | Mostly good for regular frequencies; `IRREGULAR` explicitly deferred. Key-source collision risk remains. |
| Planned expense materialized | Partial. Projection exists, but not atomically reconciled with actual payments. |
| Reminder delivery created once | Partial. Unique index exists, but only if caller passes `reminderWindows`; insert result race is not checked. |
| Worker rerun idempotent | Partial/unsafe. DB duplicate rows are guarded, but notification sending is not exactly-once. |
| Paid actual expense links to occurrence | Partial. Method exists, but global transaction side-effect wiring is not guaranteed. |
| Paid occurrence suppresses duplicate reminders | Not clean. PAID occurrence does not cancel/suppress scheduled deliveries. |
| Dashboard avoids planned + actual double count | Not proven; likely unsafe because planned expense is not fulfilled when occurrence is paid. |
| Forecast includes future planned costs | Partial. `RecurringPlanProjectionService` creates planned rows. |
| Recurring lifecycle event inserted | Partial. Some events exist; status updates/materialized paid transitions are under-audited. |

---

# Positive findings to preserve

## PF-01 — Recurring lifecycle now has a real coordinator

`RecurringLifecycleCoordinator.generateOccurrences()` centralizes:

```text
rule lookup
→ date anchor adjustment
→ recurrence expansion
→ actual expense conflict resolution
→ occurrence materialization
→ reminder delivery creation
```

This is the correct direction.

## PF-02 — Expansion is calendar-aware

`RecurringOccurrenceExpander` uses `TimePeriodUtils.addDays`, `addMonths`, and `addYears`, which is much safer than raw millisecond intervals for DST/month boundaries.

## PF-03 — Occurrence and reminder persistence is transactional

`RecurringOccurrenceMaterializer.materialize()` wraps occurrence insert/update, reminder insert, and lifecycle event insert in `database.withTransaction`.

## PF-04 — Reminder deliveries have a uniqueness contract

`RecurringReminderDelivery` has a unique index on:

```text
occurrenceId + reminderWindow
```

This is the right base for idempotent scheduling.

## PF-05 — Bill reminder worker uses the new coordinator path

`BillReminderWorker` calls:

```kotlin
coordinator.getDueReminders()
coordinator.markReminderSent(reminder.id)
```

instead of the old `BillReminderManager.getNotificationsDue()` path.

## PF-06 — Snooze and dismiss are persisted

`SnoozeReminderReceiver` and `DismissReminderReceiver` update delivery state and write lifecycle events.

---

# Issue P0-01 — Actual payment does not fulfill corresponding planned expense

## Severity

P0 / Critical

## Evidence

`RecurringPlanProjectionService.projectFromRule()` creates `PlannedExpense` rows with:

```text
status = PLANNED
sourceOccurrenceKey = occurrence.occurrenceKey
openSourceOccurrenceKey = occurrence.occurrenceKey
sourceRecurringRuleId = ruleId
```

`RecurringLifecycleCoordinator.linkExpenseToOccurrence()` marks the occurrence as:

```text
status = PAID
linkedExpenseId = expenseId
paidAmount / paidCurrency / paidAt
```

But it does **not** update the matching `PlannedExpense`:

```text
status = FULFILLED
linkedActualExpenseId = expenseId
openSourceOccurrenceKey = null
```

## Impact

Dashboard/cash-flow/forecast can double-count:

```text
planned bill still open
+ actual expense already paid
= inflated future/current cost
```

This directly violates:

```text
dashboard does not double-count planned + actual
cash-flow calendar does not double-count planned and actual
```

## Fixing strategy

Payment reconciliation must atomically update:

```text
RecurringOccurrence
PlannedExpense
ReminderDelivery
LifecycleEvent
```

## Implementation plan

1. Inject `PlannedExpenseDao` into `RecurringLifecycleCoordinator`.

2. In `linkExpenseToOccurrence(expenseId)`, wrap writes in one DB transaction:

```kotlin
database.withTransaction {
    val match = findMatchingPlannedOccurrence(...)
    occurrenceDao.update(match.copy(status = "PAID", ...))

    plannedExpenseDao.getBySourceOccurrenceKey(match.occurrenceKey)?.let { planned ->
        plannedExpenseDao.linkToActualExpense(
            id = planned.id,
            expenseId = expenseId,
            updatedAt = now
        )
    }

    reminderDeliveryDao.suppressOpenDeliveriesForOccurrence(
        occurrenceId = match.id,
        status = "SUPPRESSED_PAID",
        updatedAt = now
    )

    lifecycleEventDao.insert(OCCURRENCE_PAID...)
    lifecycleEventDao.insert(PLANNED_FULFILLED...)
}
```

3. Add DAO method:

```kotlin
@Query("""
UPDATE recurring_reminder_deliveries
SET status = 'DISMISSED'
WHERE occurrenceId = :occurrenceId
  AND status IN ('SCHEDULED', 'SNOOZED', 'FAILED')
""")
suspend fun suppressOpenDeliveriesForOccurrence(occurrenceId: Long): Int
```

4. Tests:

```text
actual_expense_link_marks_occurrence_paid
actual_expense_link_marks_planned_expense_fulfilled
actual_expense_link_clears_openSourceOccurrenceKey
actual_expense_link_suppresses_scheduled_reminders
dashboard_query_does_not_count_fulfilled_planned_plus_actual
```

---

# Issue P0-02 — Paid occurrence does not suppress scheduled reminders

## Severity

P0 / Critical

## Evidence

`linkExpenseToOccurrence()` only updates the occurrence and writes `OCCURRENCE_PAID`.

`RecurringReminderDeliveryDao.getPendingDeliveries()` returns due deliveries by delivery status:

```sql
status = 'SCHEDULED'
OR status = 'SNOOZED' AND snoozedUntil <= now
```

It does not join/filter by occurrence status.

So if a reminder delivery was already scheduled and the user pays the bill, the delivery can still be sent later.

## Impact

User can receive “Bill due” notifications for a bill already paid.

## Fixing strategy

Either:

1. suppress reminder deliveries when occurrence becomes PAID, and/or
2. make `getPendingDeliveries()` join `recurring_occurrences` and return only `occurrence.status = 'PLANNED'`.

Do both for defense-in-depth.

## Implementation plan

1. Add DAO query:

```kotlin
@Query("""
SELECT d.*
FROM recurring_reminder_deliveries d
JOIN recurring_occurrences o ON o.id = d.occurrenceId
WHERE o.status = 'PLANNED'
  AND (
    (d.status = 'SCHEDULED' AND d.scheduledAt <= :now)
    OR
    (d.status = 'SNOOZED' AND d.snoozedUntil IS NOT NULL AND d.snoozedUntil <= :now)
  )
ORDER BY COALESCE(d.snoozedUntil, d.scheduledAt)
""")
suspend fun getPendingDeliveriesForPlannedOccurrences(now: Long): List<RecurringReminderDelivery>
```

2. Replace current `getPendingDeliveries()` usage.

3. In payment linking, mark open deliveries as:

```text
SUPPRESSED_PAID
```

or `DISMISSED` with metadata reason.

4. Tests:

```text
paid_occurrence_is_not_returned_by_getDueReminders
payment_suppresses_existing_scheduled_delivery
payment_suppresses_snoozed_delivery
sent_delivery_is_not_rescheduled_after_payment
```

---

# Issue P1-03 — Reminder dispatch is not exactly-once safe

## Severity

P1 / High

## Evidence

`BillReminderWorker` does:

```text
getDueReminders()
→ send Android notification
→ markReminderSent()
```

If the app/process crashes after `notify()` but before `markReminderSent()`, the DB row remains `SCHEDULED`, so the next worker run can send the same notification again.

Also, if two worker executions overlap, both can read the same `SCHEDULED` row before either marks it sent.

## Impact

Duplicate bill notifications.

## Fixing strategy

Add an atomic claim/dispatch state machine.

## Implementation plan

1. Add statuses:

```text
SCHEDULED
CLAIMED
SENT
FAILED
DISMISSED
SNOOZED
SUPPRESSED_PAID
```

2. Add DAO method:

```kotlin
@Query("""
UPDATE recurring_reminder_deliveries
SET status = 'CLAIMED'
WHERE id = :id
  AND (
    status = 'SCHEDULED'
    OR (status = 'SNOOZED' AND snoozedUntil IS NOT NULL AND snoozedUntil <= :now)
  )
""")
suspend fun claimDueDelivery(id: Long, now: Long): Int
```

3. Worker flow:

```text
load due IDs
for each:
  claim row atomically
  if claim count == 0 skip
  send notification
  if success → SENT
  if permission missing → FAILED_PERMISSION
  if transient failure → FAILED_RETRYABLE or SCHEDULED with retryAt
```

4. Store `notificationId`, `lastAttemptAt`, `failureReason`, `attemptCount`.

5. Tests:

```text
two_workers_claim_same_delivery_only_one_sends
crash_after_claim_does_not_double_send_without_recovery_policy
security_exception_marks_failed_permission
successful_send_sets_SENT_and_notificationId
```

---

# Issue P1-04 — Recurring rule CRUD bypasses lifecycle/restore/events/timestamps

## Severity

P1 / High

## Evidence

`RecurringExpenseRepository` and `ManualRecurringExpenseRepository` directly call `ManualRecurringExpenseDao` for:

```text
insert
update
delete
deleteById
setActiveStatus
updateNextDate
```

`ManualRecurringExpense.createdAt` defaults to `0L`.

`RecurringExpenseRepository.createRecurringExpenseEntity()` does not set `createdAt`.

No lifecycle event is written for:

```text
RULE_CREATED
RULE_UPDATED
RULE_DEACTIVATED
RULE_DELETED
NEXT_DATE_ADVANCED
```

No `RestoreMaintenanceMode` guard exists in these repository writes.

## Impact

- recurring rules can be written during restore;
- new rules can have `createdAt = 0`;
- rule deletion/deactivation can leave orphan future occurrences/reminders/planned expenses;
- debugging “why did this reminder appear?” becomes hard.

## Fixing strategy

Make recurring rule CRUD go through a lifecycle service/coordinator.

## Implementation plan

1. Create coordinator methods:

```kotlin
createRule(request)
updateRule(ruleId, patch)
deactivateRule(ruleId, reason)
deleteRule(ruleId, cleanupPolicy)
advanceNextDate(ruleId, reason)
```

2. Each write must:

```text
check restore mode
validate amount/currency/date/frequency
set createdAt/updatedAt
write RecurringLifecycleEvent
cleanup/reconcile generated rows where needed
```

3. Deprecate direct repository mutations or make them internal.

4. Add static guard:

```text
No direct ManualRecurringExpenseDao insert/update/delete outside RecurringLifecycleCoordinator, migrations, debug-only code.
```

5. Tests:

```text
create_rule_sets_createdAt
create_rule_blocked_during_restore
delete_rule_cancels_future_occurrences
deactivate_rule_suppresses_future_reminders
update_rule_writes_RULE_UPDATED_event
```

---

# Issue P1-05 — Bill reminder worker is disabled by default with static config

## Severity

P1 / High for reminder feature availability

## Evidence

`WorkerSpec.DEFAULTS` has:

```kotlin
"bill_reminder_periodic" to WorkerSpec(
    name = "bill_reminder_periodic",
    enabled = false,
    repeatIntervalHours = 6,
    ...
)
```

`BillReminderWorker.schedule(context)` delegates to `WorkerSpecScheduler.scheduleFromSpec()`.

If the spec is disabled, the scheduler cancels/skips work.

## Impact

The reminder pipeline does not actually dispatch reminders unless another runtime mechanism overrides the static spec.

A static `enabled = false` is not a real user opt-in system.

## Fixing strategy

Separate default worker capability from user reminder setting.

## Implementation plan

1. Keep worker spec enabled as infrastructure, or make scheduler accept runtime gate:

```kotlin
BillReminderSettingsRepository.remindersEnabled()
```

2. Add user setting:

```text
billRemindersEnabled
reminderWindows
quietHours
notificationPermissionState
```

3. Schedule/cancel worker when setting changes.

4. Worker should check runtime setting before doing work.

5. Tests:

```text
reminders_disabled_cancels_worker
reminders_enabled_schedules_worker
worker_skips_when_user_setting_disabled
worker_runs_when_spec_enabled_and_user_enabled
```

---

# Issue P1-06 — Reminder deliveries are only created when caller passes `reminderWindows`

## Severity

P1 / High

## Evidence

`RecurringLifecycleCoordinator.generateOccurrences()` accepts:

```kotlin
reminderWindows: List<String> = emptyList()
```

`RecurringOccurrenceMaterializer.materialize()` only creates deliveries for the provided windows.

`RecurringPlanProjectionService.projectFromRule()` calls:

```kotlin
coordinator.generateOccurrences(ruleId, now, endDate)
```

with no windows.

`reconcilePlannedVsActual()` also calls `generateOccurrences()` with no windows.

## Impact

Occurrences and planned expenses may exist, but no reminder deliveries are scheduled.

The worker then has nothing to dispatch.

## Fixing strategy

Reminder policy must be configured at rule/settings level, not passed opportunistically.

## Implementation plan

1. Add default reminder windows to settings/rule:

```text
3_DAYS_BEFORE
DUE_DAY
OVERDUE
```

2. `generateOccurrences()` should resolve windows from:

```text
rule-specific windows if present
else user default windows
else empty only if reminders disabled
```

3. Add explicit options object:

```kotlin
data class OccurrenceGenerationOptions(
    val createReminderDeliveries: Boolean,
    val reminderWindows: List<String>
)
```

4. Tests:

```text
projectFromRule_creates_default_reminder_deliveries_when_enabled
generateOccurrences_with_empty_windows_does_not_create_reminders_when_disabled
rule_specific_windows_override_defaults
```

---

# Issue P1-07 — `occurrenceKey` can collide across source types

## Severity

P1 / High

## Evidence

`RecurringOccurrenceExpander.buildOccurrenceKey()` returns:

```kotlin
"$sourceId|$dayStart|${frequency.name}"
```

It does not include `sourceType`.

`RecurringOccurrence.occurrenceKey` is unique globally.

The entity supports multiple source types:

```text
RECURRING_RULE
DETECTED_PATTERN
SUBSCRIPTION
PLANNED
```

## Impact

Two different sources with the same numeric `sourceId`, same due day, and same frequency can collide and cause one occurrence to be skipped/merged incorrectly.

Example:

```text
RECURRING_RULE id=1 monthly
SUBSCRIPTION id=1 monthly
same due date
→ same occurrenceKey
```

## Fixing strategy

Include source type in key.

## Implementation plan

1. Change key format:

```kotlin
"$sourceType|$sourceId|$dayStart|${frequency.name}"
```

2. Add migration/backfill for existing occurrence keys.

3. Also update planned expense `sourceOccurrenceKey` values consistently.

4. Tests:

```text
different_source_types_same_source_id_do_not_collide
planned_expense_keys_migrated_with_occurrence_keys
old_key_lookup_supports_migration_or_backfill
```

---

# Issue P1-08 — Actual expense → occurrence linking is not globally guaranteed

## Severity

P1 / High

## Evidence

`RecurringLifecycleCoordinator.linkExpenseToOccurrence(expenseId)` exists.

But `TransactionSideEffectDispatcher.dispatchOnCreated()` explicitly says source-specific side effects such as recurring matching are not handled there.

So unless every create path separately calls recurring linking, this pipeline can miss actual payments.

## Impact

Manual/notification/receipt/bank/import-created expenses can fail to mark recurring occurrences as paid.

This causes:

```text
unpaid occurrence remains PLANNED
planned expense remains open
reminders continue
forecast double-count risk
```

## Fixing strategy

Recurring payment matching should be part of the global transaction post-commit side-effect contract, unless explicitly disabled.

## Implementation plan

1. Add port:

```kotlin
interface RecurringPaymentMatcher {
    suspend fun onExpenseCreated(expenseId: Long): RecurringMatchResult
    suspend fun onExpenseDeleted(expenseId: Long)
}
```

2. Inject it into `TransactionSideEffectDispatcher`.

3. After successful create, call:

```kotlin
recurringPaymentMatcher.onExpenseCreated(expenseId)
```

4. After delete, call:

```kotlin
recurringPaymentMatcher.onExpenseDeleted(expenseId)
```

5. Add static guard/golden tests:

```text
manual_expense_payment_links_occurrence
notification_expense_payment_links_occurrence
receipt_expense_payment_links_occurrence
bank_import_expense_payment_links_occurrence
delete_actual_expense_unlinks_occurrence_and_reopens_planned
```

---

# Issue P1-09 — Existing PAID occurrences can be downgraded by regeneration

## Severity

P1 / High

## Evidence

`RecurringOccurrenceMaterializer.materialize()` does:

```text
insert occurrence
if exists and status differs:
    occurrenceDao.update(entity.copy(id = existing.id, createdAt = existing.createdAt))
```

The new `entity.status` comes from conflict resolution.

If a previously `PAID` occurrence is regenerated and the resolver does not match the actual expense — for example merchant/amount changed, date changed, or the actual expense is outside the loaded range — the status can be overwritten to `PLANNED`.

## Impact

Paid bill history can be lost or reopened unintentionally.

## Fixing strategy

Terminal/user-confirmed statuses must not be downgraded by passive regeneration.

## Implementation plan

1. Define transition policy:

```text
PLANNED → PAID allowed
PLANNED → SKIPPED/CANCELLED allowed by explicit user action
PAID → PLANNED only through explicit unlink/delete actual expense
CANCELLED/SKIPPED → PLANNED only through explicit user action
```

2. Add function:

```kotlin
fun canAutoTransition(oldStatus: String, newStatus: String): Boolean
```

3. In materializer, apply only safe transitions.

4. Tests:

```text
regeneration_does_not_downgrade_paid_to_planned
explicit_unlink_reopens_paid_occurrence
cancelled_occurrence_not_recreated_as_planned
```

---

# Issue P1-10 — Materializer updates status without lifecycle event

## Severity

P1 / High for audit/debugging

## Evidence

When `RecurringOccurrenceMaterializer` inserts a new occurrence, it writes:

```text
OCCURRENCE_GENERATED
```

When an existing occurrence is updated because status changed, it increments `updated++`, but does not write a lifecycle event.

## Impact

A generated occurrence can silently become `PAID` or another status without an event explaining why.

## Fixing strategy

Every status transition must have a durable event.

## Implementation plan

1. On update, write:

```text
OCCURRENCE_AUTO_MATCHED
OCCURRENCE_STATUS_CHANGED
```

depending on cause.

2. Include metadata:

```json
{
  "oldStatus": "...",
  "newStatus": "...",
  "linkedExpenseId": 123,
  "source": "generateOccurrences"
}
```

3. Tests:

```text
materializer_status_change_writes_lifecycle_event
auto_paid_from_conflict_resolution_writes_OCCURRENCE_AUTO_MATCHED
no_event_written_when_existing_same_status_skipped
```

---

# Issue P1-11 — Shared recurring write methods miss restore guard

## Severity

P1 / High

## Evidence

`generateOccurrences()` and `updateOccurrenceStatus()` check `restoreMaintenanceMode`.

But these methods write without a visible internal guard:

```text
linkExpenseToOccurrence()
unlinkExpenseFromOccurrence()
markReminderSent()
```

Worker/receivers have their own restore checks, but the shared coordinator method itself should also guard writes.

## Impact

Any future direct caller can mutate recurring state during restore.

## Fixing strategy

Put the write barrier in the lowest shared write boundary.

## Implementation plan

1. Add guard to every writing coordinator method:

```kotlin
private fun checkWritesAllowed() {
    if (!restoreMaintenanceMode.isWritesAllowed()) {
        throw IllegalStateException("Database writes blocked during restore")
    }
}
```

2. Call it from:

```text
generateOccurrences
linkExpenseToOccurrence
unlinkExpenseFromOccurrence
updateOccurrenceStatus
markReminderSent
```

3. Tests:

```text
restore_blocks_linkExpenseToOccurrence
restore_blocks_unlinkExpenseFromOccurrence
restore_blocks_markReminderSent
restore_blocks_updateOccurrenceStatus
```

---

# Issue P1-12 — Legacy `BillReminderManager.markBillPaid()` creates mixed old/new behavior

## Severity

P1 / High

## Evidence

`BillReminderManager.markBillPaid()`:

```text
loads recurring rule
calculates next date
updates recurringExpenseRepository.update(updated)
```

It does not:

```text
mark occurrence PAID
fulfill planned expense
suppress reminders
create/link actual expense
write recurring lifecycle event
check restore mode
```

## Impact

The user can mark a bill paid in the legacy path, but the new lifecycle tables remain stale.

## Fixing strategy

Deprecate/remove legacy payment mutation or make it delegate to the coordinator.

## Implementation plan

1. Replace API with:

```kotlin
suspend fun markBillPaid(command: MarkRecurringBillPaidCommand): Result
```

2. It should either:
   - link to an existing actual expense, or
   - create an actual expense through `TransactionLifecycleCoordinator`, then link.

3. Update next due date only after occurrence payment succeeds.

4. Tests:

```text
legacy_markBillPaid_delegates_to_lifecycle
markBillPaid_creates_or_links_actual_expense
markBillPaid_advances_rule_nextDate
markBillPaid_writes_occurrence_and_rule_events
```

---

# Issue P2-13 — `updateOccurrenceStatus()` accepts arbitrary strings and can event missing rows

## Severity

P2 / Medium

## Evidence

`updateOccurrenceStatus(occurrenceId, newStatus: String)`:

- accepts any string;
- does not return whether a row was updated;
- loads occurrence but proceeds even if null;
- maps `"MISSED"` to `"OCCURRENCE_SKIPPED"`.

## Impact

Invalid states can enter the DB and debug history can contain events for nonexistent rows.

## Fixing strategy

Use typed statuses and structured results.

## Implementation plan

1. Add enum or sealed value:

```kotlin
enum class RecurringOccurrenceStatus {
    PLANNED, PAID, SKIPPED, MISSED, CANCELLED, IGNORED
}
```

2. DAO update returns affected row count.

3. Return:

```kotlin
sealed interface UpdateOccurrenceStatusResult {
    data object Updated
    data object NotFound
    data class InvalidTransition(...)
}
```

4. Map `MISSED` to `OCCURRENCE_MISSED`.

5. Tests:

```text
invalid_status_rejected
missing_occurrence_does_not_write_event
missed_status_writes_OCCURRENCE_MISSED
```

---

# Issue P2-14 — Notification permission failure is not durable

## Severity

P2 / Medium

## Evidence

`BillReminderWorker.sendNotification()` catches `SecurityException` and returns `false`.

Worker logs:

```text
Notification not delivered ... leaving status unchanged
```

The delivery remains due and can retry forever.

No lifecycle event records:

```text
REMINDER_FAILED_PERMISSION
```

## Impact

If notification permission is denied, the worker repeatedly attempts to send and the user has no durable reason why reminders are not appearing.

## Fixing strategy

Make delivery failure states durable.

## Implementation plan

1. Add failure columns:

```text
lastAttemptAt
attemptCount
failureReason
```

or store in lifecycle event metadata.

2. On `SecurityException`:

```text
status = FAILED_PERMISSION
event = REMINDER_DELIVERY_FAILED
reason = MISSING_POST_NOTIFICATIONS_PERMISSION
```

3. UI should show permission-denied state.

4. Tests:

```text
missing_permission_marks_delivery_failed_permission
failed_permission_not_returned_as_due_until_permission_changes
permission_restored_reschedules_failed_permission_delivery
```

---

# Issue P2-15 — Snooze/dismiss receivers use `runBlocking` inside `BroadcastReceiver.onReceive`

## Severity

P2 / Medium

## Evidence

Both receivers call:

```kotlin
runBlocking(Dispatchers.IO) { ... }
```

inside `onReceive()`.

## Impact

This can block receiver completion and risks ANR-like behavior if DB is slow.

## Fixing strategy

Use `goAsync()` and a coroutine.

## Implementation plan

1. In receiver:

```kotlin
val pendingResult = goAsync()
applicationScope.launch(Dispatchers.IO) {
    try { ... }
    finally { pendingResult.finish() }
}
```

2. Or route action to a one-shot WorkManager job.

3. Tests:

```text
dismiss_receiver_uses_goAsync_and_finishes
snooze_receiver_uses_goAsync_and_finishes
receiver_failure_logs_event_or_metric
```

---

# Issue P2-16 — Rule category is lost during occurrence/projection

## Severity

P2 / Medium

## Evidence

`ManualRecurringExpense` has:

```kotlin
categoryId: Long?
```

But `RecurringLifecycleCoordinator.generateOccurrences()` builds request with:

```kotlin
categoryId = null
```

Comment says manual recurring expense does not carry a categoryId, but the entity does.

## Impact

Generated occurrences and planned expenses lose category assignment.

This affects:

```text
budget forecast
cash-flow category view
analytics
dashboard planned spending by category
```

## Fixing strategy

Pass rule category through the pipeline.

## Implementation plan

1. Change:

```kotlin
categoryId = rule.categoryId
```

2. Add tests:

```text
rule_category_propagates_to_occurrence
rule_category_propagates_to_planned_expense
```

---

# Issue P2-17 — Reminder time policy is too coarse

## Severity

P2 / Medium

## Evidence

`computeScheduledAt()` schedules:

```text
DUE_DAY → dueDate
OVERDUE → dueDate
N_DAYS_BEFORE → dueDate - N days
```

`dueDate` is start-of-day.

## Impact

Users can get reminders at midnight/start-of-day. `OVERDUE` is not really overdue if scheduled at the same timestamp as due day.

## Fixing strategy

Add configurable reminder time-of-day and overdue offset.

## Implementation plan

1. Add settings:

```text
reminderHour
reminderMinute
overdueDelayHours
quietHours
timezone policy
```

2. Compute schedule using date + preferred local time.

3. Tests:

```text
due_day_reminder_scheduled_at_user_configured_time
overdue_reminder_scheduled_after_due_date
dst_boundary_reminder_uses_local_time
```

---

# Issue P2-18 — Planned projection is not atomic with occurrence generation

## Severity

P2 / Medium

## Evidence

`RecurringPlanProjectionService.projectFromRule()`:

```text
coordinator.generateOccurrences()
→ fetch occurrences
→ insert PlannedExpense rows one by one
```

No transaction covers both occurrence generation and planned projection.

No `PLANNED_GENERATED` lifecycle event is written.

Existing planned expenses are not updated if occurrence amount/category changes.

## Impact

Partial projection state:

```text
occurrence exists
planned expense missing
```

or stale planned rows after rule edit.

## Fixing strategy

Create a single projection transaction.

## Implementation plan

1. Move projection into coordinator or add `RecurringProjectionCoordinator`.

2. In one transaction:

```text
generate/update occurrences
upsert planned expenses
write PLANNED_GENERATED / PLANNED_UPDATED events
```

3. Add update behavior:

```text
if existing planned is still PLANNED and expected amount/category changed → update
if FULFILLED/CANCELLED → do not overwrite
```

4. Tests:

```text
projection_creates_occurrence_and_planned_expense_consistently
projection_updates_open_planned_expense_after_rule_amount_change
projection_does_not_overwrite_fulfilled_planned_expense
```

---

# Recommended fixing order

## PR 1 — Recurring actual-payment reconciliation

Files:

```text
RecurringLifecycleCoordinator.kt
RecurringOccurrenceDao.kt
RecurringReminderDeliveryDao.kt
PlannedExpenseDao.kt
RecurringLifecycleEventDao.kt
```

Fix:

```text
- link actual expense → occurrence + planned expense + reminder suppression in one transaction
- unlink actual expense reopens planned expense and scheduled reminders only if policy allows
```

## PR 2 — Global transaction side-effect hook

Files:

```text
TransactionSideEffectDispatcher.kt
RecurringLifecycleCoordinator.kt
TransactionLifecycleCoordinator.kt
```

Fix:

```text
- every expense create attempts recurring match post-commit
- every expense delete unlinks recurring occurrence post-commit
```

## PR 3 — Rule lifecycle coordinator

Files:

```text
RecurringLifecycleCoordinator.kt
ManualRecurringExpenseRepository.kt
RecurringExpenseRepository.kt
ManualRecurringExpenseDao.kt
```

Fix:

```text
- rule create/update/delete/deactivate through coordinator
- restore guard
- timestamps
- lifecycle events
- cleanup future generated rows on delete/deactivate
```

## PR 4 — Reminder dispatch idempotency

Files:

```text
BillReminderWorker.kt
RecurringReminderDeliveryDao.kt
RecurringReminderDelivery.kt
RecurringLifecycleCoordinator.kt
```

Fix:

```text
- CLAIMED/SENT/FAILED states
- atomic claim before notify
- notificationId persisted
- permission failure durable
```

## PR 5 — Worker enablement and user settings

Files:

```text
WorkerSpec.kt
WorkerSpecScheduler.kt
BillReminderWorker.kt
ReminderSettingsRepository.kt
UI settings if needed
```

Fix:

```text
- static disabled worker replaced by runtime user setting
- schedule/cancel when setting changes
```

## PR 6 — Reminder scheduling policy

Files:

```text
RecurringOccurrenceMaterializer.kt
RecurringLifecycleCoordinator.kt
ReminderSettingsRepository.kt
ManualRecurringExpense.kt if rule-specific windows needed
```

Fix:

```text
- default reminder windows
- rule-specific windows
- user-configured reminder time
- overdue scheduling semantics
```

## PR 7 — Occurrence key migration

Files:

```text
RecurringOccurrenceExpander.kt
Room migration
PlannedExpense migration
tests
```

Fix:

```text
- include sourceType in occurrenceKey
- backfill occurrence/planned keys
```

## PR 8 — Receiver cleanup

Files:

```text
SnoozeReminderReceiver.kt
DismissReminderReceiver.kt
```

Fix:

```text
- replace runBlocking with goAsync/coroutine or WorkManager
- persist notification cancellation state
```

---

# Golden tests to add

```text
create_recurring_rule_sets_createdAt_and_RULE_CREATED_event
create_recurring_rule_blocked_during_restore
generate_occurrences_is_idempotent_for_same_range
generate_occurrences_does_not_downgrade_paid_to_planned
different_source_types_do_not_collide_occurrenceKey
rule_category_propagates_to_occurrence_and_planned_expense
project_rule_creates_planned_expenses_once
project_rule_updates_open_planned_when_amount_changes
manual_expense_matching_bill_marks_occurrence_paid
manual_expense_matching_bill_fulfills_planned_expense
manual_expense_matching_bill_suppresses_due_reminders
notification_expense_matching_bill_marks_occurrence_paid
delete_actual_expense_unlinks_occurrence_and_reopens_planned
paid_occurrence_not_returned_by_getDueReminders
two_workers_claim_same_reminder_only_one_sends
missing_notification_permission_marks_delivery_failed
snooze_delivery_reschedules_until_snoozedUntil
dismiss_delivery_never_returns_as_due
worker_disabled_by_user_setting_does_not_dispatch
worker_enabled_by_user_setting_dispatches_due_delivery
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "ManualRecurringExpenseDao" app/src/main/java
grep -R "RecurringExpenseRepository" app/src/main/java
grep -R "ManualRecurringExpenseRepository" app/src/main/java
grep -R "generateOccurrences" app/src/main/java
grep -R "linkExpenseToOccurrence" app/src/main/java
grep -R "markBillPaid" app/src/main/java
grep -R "RecurringReminderDelivery(" app/src/main/java
grep -R "getPendingDeliveries" app/src/main/java
grep -R "plannedExpenseDao" app/src/main/java
grep -R "sourceOccurrenceKey" app/src/main/java
```

Allowed direct recurring DAO writes should be explicitly allowlisted:

```text
RecurringLifecycleCoordinator
RecurringOccurrenceMaterializer
Room migrations
debug-only data repair tools
```

Everything else should go through lifecycle services.

---

# Definition of done

```text
- Rule create/update/delete/deactivate is lifecycle-owned and restore-guarded.
- No persisted ManualRecurringExpense has createdAt = 0.
- Actual expense payment marks occurrence PAID.
- Actual expense payment fulfills linked PlannedExpense.
- Actual expense payment suppresses open reminder deliveries.
- Paid occurrences are never returned by getDueReminders().
- Reminder worker atomically claims before sending.
- Notification permission failures are durable and visible.
- Bill reminder worker can be enabled/disabled by real user setting, not static WorkerSpec only.
- Default reminder windows are applied consistently.
- occurrenceKey includes sourceType and is collision-safe.
- Regeneration cannot downgrade PAID/CANCELLED/SKIPPED without explicit action.
- Lifecycle events exist for rule, occurrence, reminder, and planned-expense transitions.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `RecurringLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

- `RecurringOccurrenceMaterializer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt

- `RecurringOccurrenceExpander.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt

- `OccurrenceConflictResolver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/recurring/OccurrenceConflictResolver.kt

- `RecurringPlanProjectionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt

- `BillReminderManager.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt

- `BillReminderWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

- `SnoozeReminderReceiver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt

- `DismissReminderReceiver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/service/reminder/DismissReminderReceiver.kt

- `RecurringOccurrenceDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringOccurrenceDao.kt

- `RecurringReminderDeliveryDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringReminderDeliveryDao.kt

- `RecurringLifecycleEventDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringLifecycleEventDao.kt

- `ManualRecurringExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt

- `PlannedExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt

- `ManualRecurringExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt

- `RecurringOccurrence.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringOccurrence.kt

- `RecurringReminderDelivery.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringReminderDelivery.kt

- `PlannedExpense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt

- `WorkerSpec.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt

- `WorkerSpecScheduler.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt

- `TransactionSideEffectDispatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt