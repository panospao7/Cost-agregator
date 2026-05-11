# Pipeline 4 implementation plan — Recurring / Bill Reminders
Basis: HEAD `c424274` on **May 11, 2026**

## 1. What is already real vs what is stale

### Real improvements already in code
- `linkExpenseToOccurrence()` now:
  - marks occurrence `PAID`
  - links actual expense
  - writes `OCCURRENCE_PAID`
  - fulfills planned expense via `plannedExpenseDao.linkToActualExpense(...)`
  - suppresses open reminder deliveries via `suppressOpenDeliveriesForOccurrence(...)`
- `BillReminderWorker` uses atomic `claimReminderDelivery(...)`
- `generateOccurrences()` now defaults reminder windows from `DEFAULT_REMINDER_WINDOWS`
- `RecurringOccurrenceMaterializer.materialize()` now writes `OCCURRENCE_STATUS_CHANGED`

### Tracker rows that are stale in a positive direction
- **P4-P1-04**: no longer true literally; defaults now come from `DEFAULT_REMINDER_WINDOWS`
- **P4-P1-08**: no longer true literally; materializer now writes status-change events
- **P4-P1-05**: likely fixed in current expander logic because `sourceType` is now part of the occurrence key builder, but comments/entities/docs still lag and this needs verification/backfill review

### Important real gaps still in code
These are the ones I would actually prioritize:
1. **Unlink path is incomplete**
   - `unlinkExpenseFromOccurrence()` resets occurrence back to `PLANNED`
   - but it does **not** reopen the linked planned expense
   - and it does **not** recreate/reopen cancelled reminder deliveries
2. **Unlink audit reason is wrong**
   - method hardcodes metadata reason `"expense_deleted"`
   - but `TransactionLifecycleCoordinator.updateExpense(...)` also uses it for edit reconciliation
3. **Reminder claim durability is incomplete**
   - delivery can become `CLAIMED`
   - but there is no stale-claim recovery
   - so worker/process crash can strand reminders permanently
4. **Reminder action receivers bypass coordinator**
   - `SnoozeReminderReceiver` / `DismissReminderReceiver` write DAO rows directly
   - they use `runBlocking` in `BroadcastReceiver`
   - event + mutation are not atomic
5. **Recurring rule CRUD still has no single lifecycle owner**
   - repositories still mutate rules directly
   - rule row + lifecycle event are not atomic
   - no downstream occurrence/reminder/planned refresh contract
6. **Reminder window semantics are wrong for `OVERDUE`**
   - current `computeScheduledAt()` maps `"OVERDUE"` to the same timestamp as `"DUE_DAY"`
   - that can create duplicate same-day reminders instead of an actual overdue reminder
7. **Worker hardening is incomplete**
   - `BillReminderWorker` does not use `executionGuard.checkpoint(...)`
8. **Tests are still too weak**
   - `RecurringLifecycleCoordinatorTest` is still mock-only, not DB-contract proof

---

## 2. Recommended PR order

### PR0 — Contract inventory + test harness skeleton
**Priority:** Critical

### Goal
Freeze the real Pipeline 4 contract before further refactor.

### Files
- new doc under `docs/`
- new integration test package
- maybe migration test scaffold

### Work
- inventory all recurring mutation entrypoints:
  - rule create/update/delete/activate/deactivate
  - occurrence generation/materialization
  - expense→occurrence link/unlink
  - reminder send/fail/snooze/dismiss/cancel
  - planned-expense fulfill/reopen/cancel
- classify each as:
  - lifecycle-owned
  - legacy/deprecated
  - unresolved bypass
- add skeleton tests:
  - `RecurringLifecycleDbContractTest`
  - `ReminderDeliveryDurabilityTest`
  - `RecurringRuleCrudLifecycleTest`

### Done when
You have an explicit ownership map for recurring state transitions.

---

## PR1 — Fix the paid/unpaid/planned/reminder state machine
**Priority:** Critical

### Why
This is the highest-value correctness gap left in Pipeline 4.

### Files
- `RecurringLifecycleCoordinator.kt`
- `PlannedExpenseDao.kt`
- `RecurringReminderDeliveryDao.kt`
- maybe `PlannedExpense.kt`

### Changes
Create explicit transition helpers inside the coordinator:
- `markOccurrencePaidFromExpense(...)`
- `reopenOccurrenceFromExpenseRemoval(...)`
- `cancelOccurrence(...)`
- `skipOccurrence(...)`

### Specific fixes
1. **Complete `unlinkExpenseFromOccurrence()`**
   - occurrence: `PAID -> PLANNED`
   - planned expense: `FULFILLED -> PLANNED`
   - clear `linkedActualExpenseId`
   - restore `openSourceOccurrenceKey = sourceOccurrenceKey`
   - reminder deliveries:
     - either reopen previously cancelled ones if still valid
     - or regenerate fresh deliveries from rule/default windows

2. **Add explicit unlink reason**
   - change method to accept something like:
     - `EXPENSE_DELETED`
     - `EXPENSE_UPDATED_RECONCILE`
     - `RESTORE_ROLLBACK`
   - stop hardcoding `"expense_deleted"`

3. **Status transitions must cascade**
   - if occurrence becomes `SKIPPED` / `CANCELLED` / `MISSED`:
     - suppress or cancel open deliveries
     - update linked planned expense status consistently

4. **Fix semantic loss for MISSED**
   - current event mapping uses `MISSED -> OCCURRENCE_SKIPPED`
   - add real `OCCURRENCE_MISSED` event type

### New DAO methods likely needed
- `PlannedExpenseDao.unlinkActualExpenseAndReopen(id, updatedAt)`
- `PlannedExpenseDao.updateStatusAndUnlink(...)`
- reminder DAO helper to reopen or regenerate deliveries for a specific occurrence

### Tests
- pay occurrence from actual expense
- delete actual expense -> occurrence reopens + planned reopens + reminders become active again
- update actual expense causing relink -> old occurrence/planned are restored correctly
- unlink event metadata reflects real reason

### Done when
Expense create/update/delete can move recurring state forward and backward without leaving planned/reminder state behind.

---

## PR2 — Introduce a single recurring rule lifecycle owner
**Priority:** Critical / Large

### Why
Current recurring rule CRUD is still split across repositories and direct DAO-style paths.

### Files
- `RecurringExpenseRepository.kt`
- `ManualRecurringExpenseRepository.kt`
- new `RecurringRuleLifecycleCoordinator.kt` or extend `RecurringLifecycleCoordinator.kt`
- `RecurringLifecycleEvent.kt`
- `RecurringLifecycleEventDao.kt`

### Changes
Make one owner responsible for:
- create rule
- update rule
- activate/deactivate rule
- delete/archive rule

### Required behavior
Each mutation should:
1. guard with write barrier
2. run in one DB transaction
3. write lifecycle event atomically
4. trigger downstream reconciliation:
   - regenerate future occurrences
   - refresh planned expenses
   - schedule/suppress reminder deliveries as needed

### Event model improvement
Current rule events use `occurrenceId = null`, which is weak.
Add either:
- `ruleId` column, or
- normalized `entityType/entityId`

Recommendation: use `entityType/entityId` so rule/occurrence/delivery/planned-expense events are queryable consistently.

### Delete/deactivate policy
Be explicit:
- keep historical `PAID` occurrences for audit
- cancel or archive future `PLANNED` occurrences
- suppress future open deliveries
- update future planned expenses to `CANCELLED` or archived equivalent

### Done when
No normal business path mutates recurring rules outside one lifecycle owner.

---

## PR3 — Harden reminder dispatch durability
**Priority:** Critical

### Why
The current exactly-once work is only partial. Crash after `CLAIMED` can strand reminders forever.

### Files
- `RecurringReminderDelivery.kt`
- `RecurringReminderDeliveryDao.kt`
- `RecurringLifecycleCoordinator.kt`
- `BillReminderWorker.kt`

### Schema changes
Add durable claim/failure metadata:
- `claimedAt`
- `claimRunId`
- `attemptCount`
- `lastError`
- `lastStateChangedAt`

### State machine
Make delivery transitions explicit:
- `SCHEDULED`
- `CLAIMED`
- `SENT`
- `SNOOZED`
- `DISMISSED`
- `CANCELLED`
- `FAILED_PERMISSION`
- `FAILED_TRANSIENT`

Also update stale entity comments to match reality.

### Worker changes
1. recover stale claims at worker start:
   - `CLAIMED` older than threshold -> `SCHEDULED` or `FAILED_TRANSIENT`
2. wrap each reminder iteration in per-item `try/catch`
3. on non-permission failure:
   - do not leave row in `CLAIMED`
4. call `executionGuard.checkpoint(...)`:
   - before claim
   - before sent/failed finalization
   - between iterations
5. persist more diagnostics:
   - claimed
   - sent
   - failed
   - requeued stale claim

### Important bug to fix
`OVERDUE` currently schedules at the same timestamp as `DUE_DAY`.
That should be redesigned, e.g.:
- `DUE_DAY` = start of due day
- `OVERDUE` = next day start, or due day end + offset

### Tests
- crash after claim before send -> recovered next run
- send failure -> not stranded in `CLAIMED`
- permission failure -> terminal status
- due-day + overdue windows do not both fire at the same instant

### Done when
Worker/process failure cannot permanently lose a reminder delivery.

---

## PR4 — Move snooze/dismiss into the coordinator
**Priority:** High

### Why
Current receivers directly mutate the DAO and use `runBlocking` inside `BroadcastReceiver`.

### Files
- `SnoozeReminderReceiver.kt`
- `DismissReminderReceiver.kt`
- `RecurringLifecycleCoordinator.kt`

### Changes
Add coordinator methods:
- `snoozeReminder(deliveryId, until)`
- `dismissReminder(deliveryId)`

These methods should:
- guard writes
- update delivery + lifecycle event atomically
- validate current state transitions

### Receiver cleanup
- replace `runBlocking` with `goAsync()` + coroutine
- receivers should only parse the intent and delegate to coordinator
- add structured logs/diagnostic events

### Done when
Reminder action buttons no longer bypass the recurring lifecycle owner.

---

## PR5 — Lock down materialization invariants
**Priority:** High

### Why
This is where recurrence drift and accidental downgrades happen.

### Files
- `RecurringOccurrenceMaterializer.kt`
- `RecurringOccurrenceExpander.kt`
- `RecurringOccurrence.kt`

### Changes
1. formalize allowed transitions:
   - never downgrade `PAID`
   - never downgrade `SKIPPED`/`CANCELLED` unless explicit coordinator reopen path says so
2. keep materializer “append/generate/safe-upgrade only”
3. verify occurrence-key format migration
   - current builder includes `sourceType`
   - comments/docs/entities still describe the older shape
   - verify mixed old/new keys cannot create silent duplication
4. if materializer remains callable directly, either:
   - inject write barrier, or
   - make it `internal` and only callable via coordinator

### Tests
- paid occurrence survives regeneration
- skipped/cancelled occurrence survives regeneration
- old/new occurrence-key compatibility if legacy rows exist

### Done when
Regeneration cannot silently corrupt terminal recurring state.

---

## PR6 — Cross-pipeline enforcement of expense→occurrence reconciliation
**Priority:** High  
**Dependency:** Pipeline 2 lifecycle closure

### Why
Pipeline 4 cannot be fully closed unless expense mutation paths reliably call recurring reconciliation.

### Files
- `TransactionLifecycleCoordinator.kt`
- any remaining expense mutation bypass callsites
- CI guard script/detekt rules

### Changes
- audit all create/update/delete expense paths
- ensure they route through `TransactionLifecycleCoordinator`
- or explicitly call recurring reconcile owner
- add CI allowlist for recurring-related expense mutations

### Specific thing to verify
Current update path does:
- unlink old occurrence
- relink new occurrence

That must remain correct after PR1 reason/refill changes.

### Done when
No real expense mutation path can silently skip recurring reconciliation.

---

## PR7 — Docs sync + DB contract suite
**Priority:** Medium but required for closure

### Tests to add
- `RecurringLifecycleDbContractTest`
- `RecurringOccurrenceReopenTest`
- `ReminderDeliveryClaimRecoveryTest`
- `ReminderActionReceiverIntegrationTest`
- `RecurringRuleCrudLifecycleTest`
- `OccurrenceKeyCompatibilityTest`
- `RecurringRestoreModeIntegrationTest`

### Minimum DB scenarios
1. create rule -> occurrences + deliveries generated
2. create actual expense -> occurrence paid + planned fulfilled + reminders cancelled
3. delete actual expense -> occurrence/planned/reminders reopened
4. update actual expense -> unlink+relink works with correct event reason
5. worker crash after claim -> recovery works
6. snooze/dismiss via receiver -> coordinator-owned transitions
7. regeneration never downgrades terminal statuses

### Docs cleanup
Update stale docs/KDoc/comments:
- `BillReminderManager` still says worker is future work
- `RecurringOccurrence` comment still describes old key format
- `RecurringReminderDelivery` status comment lags actual statuses
- tracker statuses for P4-P1-04 / P4-P1-08 / probably P4-P1-05 need reconciliation

### Done when
Tracker reflects current code truth, and the code truth is proven by Room-backed tests.

---

## 3. Closure criteria for Pipeline 4

I would only call Pipeline 4 “clean and stable” when all of these are true:

- actual payment **and** payment removal keep occurrence/planned/reminder state in sync
- rule CRUD has one lifecycle owner
- reminder delivery cannot be stranded in `CLAIMED`
- snooze/dismiss paths do not bypass the coordinator
- regeneration cannot downgrade terminal occurrence states
- expense create/update/delete paths all reconcile recurring links
- DB integration tests prove the contract
- docs/tracker match HEAD

---

## 4. Key source files reviewed
- Tracker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Recurring lifecycle coordinator:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- Occurrence materializer:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt
- Occurrence expander:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt
- Bill reminder worker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt
- Legacy manager:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt
- Snooze receiver:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/reminder/SnoozeReminderReceiver.kt
- Dismiss receiver:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/reminder/DismissReminderReceiver.kt
- Reminder DAO/entity:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringReminderDeliveryDao.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringReminderDelivery.kt
- Planned expense DAO/entity:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/PlannedExpenseDao.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt
- Rule repositories:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ManualRecurringExpenseRepository.kt
- Current coordinator test:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt