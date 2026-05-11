# Pipeline 4 — Recurring / Bill Reminders evaluation

## Executive verdict

My status call:

- **2 items look genuinely implemented**
- **1 tracker TODO is stale in a positive way**
- **but several core lifecycle issues are still open or only partially solved**
- **test proof is weak**

So the honest summary is:

> **Pipeline 4 is improved, but not closure-ready.**

---

## Issue-by-issue

### P4-P0-01 — Actual payment fulfills planned expense
**Tracker:** ✅ fixed  
**My verdict:** **not clean / only partial from sampled code**

Why I’m cautious:
- `RecurringLifecycleCoordinator.linkExpenseToOccurrence()` in sampled HEAD code only:
  - loads the expense
  - finds a same-day `PLANNED` occurrence
  - sets it to `PAID`
  - writes `linkedExpenseId`, `paidAmount`, `paidCurrency`, `paidAt`
- In the sampled implementation, I did **not** see that method itself:
  - fulfill a `PlannedExpense`
  - suppress reminder deliveries
  - write a recurring lifecycle event

So the contract may be *intended*, but the implementation I inspected is thinner than the tracker wording.

**Call:** **PARTIAL / not certifiable as clean**

---

### P4-P0-02 — Paid occurrence suppresses reminders
**Tracker:** ✅ fixed  
**My verdict:** **unproven / likely partial**

I saw the bill reminder worker consume scheduled deliveries, but I did **not** verify a clean paid-path that suppresses all open deliveries from the occurrence lifecycle call I inspected.

That matters because if occurrence state flips to `PAID` but deliveries remain `SCHEDULED`, the worker can still notify.

**Call:** **not proven clean**

---

### P4-P1-01 — Reminder dispatch exactly-once safe
**Tracker:** ✅ fixed  
**My verdict:** **mostly fixed**

This is one of the stronger improvements:
- `BillReminderWorker` calls `coordinator.claimReminderDelivery(reminder.id)`
- if claim fails, it skips
- so duplicate concurrent dispatch is materially better

Why not fully “stable”:
- I did not verify recovery semantics if the app crashes after notification send but before `markReminderSent()`
- worker loop does not use `executionGuard.checkpoint()` mid-loop

**Call:** **good fix, but not fully hardened**

---

### P4-P1-02 — Recurring rule CRUD bypasses lifecycle/events
**Tracker:** TODO ONLY  
**My verdict:** **still open, but partially improved structurally**

What improved:
- repos now inject `DatabaseWriteBarrier`
- recurring repos inject lifecycle/event infrastructure and time stamping support

What is still open:
- `RecurringExpenseRepository` KDoc still says higher-level workflows should be coordinated elsewhere rather than calling the repo directly
- `BillReminderManager.markBillPaid()` still updates legacy recurring state directly
- I did not see hard enforcement that all recurring CRUD routes through one lifecycle owner

**Call:** **OPEN, with groundwork present**

---

### P4-P1-03 — Bill reminder worker disabled by default
**Tracker:** ✅ fixed  
**My verdict:** **fixed**

`BillReminderWorker` exists and schedules via `WorkerSpecScheduler`.

**Call:** **fixed**

---

### P4-P1-04 — Deliveries only created when caller passes reminder windows
**Tracker:** TODO ONLY  
**My verdict:** **tracker is stale; specific bug appears fixed**

In sampled `RecurringLifecycleCoordinator.generateOccurrences(...)`, the default is:
- `reminderWindows = listOf("DUE_DAY")`

So the old bug “no deliveries unless caller passes windows” is no longer literally true.

But:
- defaulting to `DUE_DAY` only is not the same as a fully designed reminder policy

**Call:** **specific bug mostly fixed; broader behavior still modest**

---

### P4-P1-05 — `occurrenceKey` collision across source types
**Tracker:** ⏭ deferred  
**My verdict:** **still real**

`RecurringOccurrence` still documents the key as effectively:
- `ruleId|normalizedDueDate|frequency`

I did not see `sourceType` included in the uniqueness contract.

**Call:** **deferred and still valid**

---

### P4-P1-06 — Expense→occurrence linking not globally guaranteed
**Tracker:** TODO ONLY  
**My verdict:** **still open**

Evidence:
- legacy `BillReminderManager.markBillPaid()` still exists
- it is explicitly deprecated because it only advances `nextDate`
- there is no sign of hard enforcement that every expense-create/update path must call the recurring linker

**Call:** **OPEN**

---

### P4-P1-07 — Existing `PAID` occurrences downgraded by regeneration
**Tracker:** TODO ONLY  
**My verdict:** **definitely still OPEN**

This is one of the clearest remaining bugs.

In `RecurringOccurrenceMaterializer.materialize(...)`:
- if insert conflicts, it loads the existing row
- if `existing.status != entity.status`, it updates to the new entity status

I did **not** see a guard protecting:
- `PAID -> PLANNED`

So regeneration can still downgrade a paid occurrence if the resolver returns a planned candidate.

**Call:** **OPEN and important**

---

### P4-P1-08 — Materializer updates status without lifecycle event
**Tracker:** TODO ONLY  
**My verdict:** **still OPEN**

`RecurringOccurrenceMaterializer` currently depends on:
- `RecurringOccurrenceDao`
- `RecurringReminderDeliveryDao`
- `TimeProvider`

It does **not** depend on a recurring lifecycle event DAO, and the sampled code writes no event on occurrence status change.

**Call:** **OPEN**

---

### P4-P1-09 — Shared recurring write methods miss restore guard
**Tracker:** ✅ fixed  
**My verdict:** **partial / not fully clean**

What looks better:
- recurring repositories now use `DatabaseWriteBarrier`
- worker execution is guarded

Why I won’t call it universally fixed:
- the sampled `RecurringLifecycleCoordinator` mutates occurrence rows directly
- I did not see a restore guard in the sampled coordinator methods themselves

So restore coverage is better, but not obviously universal across all recurring writes.

**Call:** **PARTIAL**

---

### P4-P1-10 — Legacy `markBillPaid()` mixed behavior
**Tracker:** TODO ONLY  
**My verdict:** **still OPEN**

This one is explicit in code.

`BillReminderManager.markBillPaid()` is deprecated and its own KDoc says it:
- only advances `nextDate`
- does **not** update occurrence/planned-expense/reminder state

So the legacy footgun still exists.

**Call:** **OPEN**

---

## Important implemented improvements

These are real:
- atomic reminder claim before notification send
- WorkManager-based bill reminder path exists
- reminder-delivery table exists
- recurring occurrence materialization exists
- default reminder window behavior improved from “none” to `DUE_DAY`
- legacy path is clearly marked deprecated

So Pipeline 4 is **not** in bad shape anymore.

---

## Why I still would not call it clean/stable

1. **The recurring lifecycle is still split**
   - repository path
   - coordinator path
   - deprecated legacy manager path

2. **Paid-state stability is not guaranteed**
   - materializer still appears able to downgrade `PAID`

3. **Lifecycle audit trail is incomplete**
   - materializer status changes still do not emit recurring lifecycle events

4. **Restore hardening is uneven**
   - better in repos/workers than in sampled coordinator writes

5. **Docs/comments are stale**
   - `BillReminderManager` still says the worker is “to be created in a future PR”, but `BillReminderWorker` already exists
   - `RecurringExpenseRepository` KDoc also lags the actual architecture

6. **Tests are weak**
   - `RecurringLifecycleCoordinatorTest` is still mock-only and only proves:
     - happy-path expand/resolve/materialize
     - missing-rule failure
   - it does **not** prove:
     - paid-occurrence no-downgrade behavior
     - reminder suppression
     - exact DB contract
     - restore-blocked writes
     - end-to-end expense linkage

---

## Final scorecard

My current table for HEAD would be roughly:

- **P4-P0-01 actual payment fulfills planned expense:** **⚠ PARTIAL**
- **P4-P0-02 paid occurrence suppresses reminders:** **⚠ PARTIAL / unproven**
- **P4-P1-01 exactly-once reminder dispatch:** **✅ MOSTLY FIXED**
- **P4-P1-02 recurring CRUD bypasses lifecycle/events:** **⚠ OPEN**
- **P4-P1-03 worker enabled by default:** **✅ FIXED**
- **P4-P1-04 reminder windows default bug:** **⚠ tracker stale / mostly fixed**
- **P4-P1-05 occurrenceKey collision:** **⏭ DEFERRED and still real**
- **P4-P1-06 expense→occurrence linking not guaranteed:** **⚠ OPEN**
- **P4-P1-07 PAID downgrade on regeneration:** **⚠ OPEN**
- **P4-P1-08 no lifecycle event on materializer updates:** **⚠ OPEN**
- **P4-P1-09 restore guard coverage:** **⚠ PARTIAL**
- **P4-P1-10 legacy markBillPaid mixed behavior:** **⚠ OPEN**

---

## Bottom line

### Are Pipeline 4 issues fixed?
**Some are materially improved, yes.**

### Are they clean and stable?
**No.**

Best summary:

> **Pipeline 4 has real architectural progress, especially in reminder dispatch, but the recurring lifecycle is still split and key state-integrity issues remain open, so I would not declare it clean or stable yet.**

---

## Sources

- Master tracker  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- `BillReminderWorker.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt
- `BillReminderManager.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt
- `RecurringLifecycleCoordinator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- `RecurringOccurrenceMaterializer.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt
- `RecurringExpenseRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt
- `ManualRecurringExpenseRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ManualRecurringExpenseRepository.kt
- `RecurringOccurrence.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringOccurrence.kt
- `RecurringReminderDelivery.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringReminderDelivery.kt
- `RecurringLifecycleCoordinatorTest.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinatorTest.kt

## Scope note
This was a **static code/doc review** of current GitHub HEAD. I did **not** run Gradle, Room migrations, or instrumentation tests.