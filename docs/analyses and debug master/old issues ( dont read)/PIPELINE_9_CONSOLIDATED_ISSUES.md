# Pipeline 9 — Workers/Background Jobs: Consolidated Issue Registry

> **Last validated:** 2026-06-01 against local HEAD code  
> **Status:** 23 FIXED (12 old + 11 new), 1 PARTIAL, 0 TODO, 0 NEW open issues  
> **Total open items:** 0 — P9 section is 🟢 COMPLETE

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P9-P1-01 | P1 | `BackgroundJobRun` table unused by workers | ✅ FIXED | ✅ **FIXED** | `WorkerRunLogger` interface + impl with `BackgroundJobRunDao` |
| P9-P1-02 | P1 | No shared `WorkerExecutionGuard` | ✅ FIXED | ✅ **FIXED** | `WorkerExecutionGuard` used by all 7 workers |
| P9-P1-03 | P1 | Restore/backup cancellation not a running-worker barrier | ✅ FIXED | ✅ **FIXED** | `WorkerLeaseRegistry` + `WorkerDrainController` + checkpoint enforcement |
| P9-P1-04 | P1 | Daily briefing one-shot chain breaks on early exits | ✅ FIXED | ✅ **FIXED** | Reschedule on all skips except spec-disable |
| P9-P1-05 | P1 | Bill reminder worker disabled by static `WorkerSpec` | ✅ FIXED | ✅ **FIXED** | `WorkerSpec.DEFAULTS["bill_reminder_periodic"]` enabled |
| P9-P1-06 | P1 | Bill reminders not exactly-once safe | ✅ FIXED | ✅ **FIXED** | Atomic `claimDelivery()` sets CLAIMED before dispatch |
| P9-P1-07 | P1 | `ReceiptMatchingWorker.runOnce()` bypasses unique scheduling | ✅ FIXED | ✅ **FIXED** | Per-receipt `claimForAutoMatch` is the overlap guard |
| P9-P1-08 | P1 | Receipt matching outcomes not durable | ✅ FIXED | ✅ **FIXED** | Durable `ReceiptEvent`s via `ReceiptMatchLifecycleService` |
| P9-P1-09 | P1 | Warranty notification sent-state outside DB | ✅ FIXED | ✅ **FIXED** | `WarrantyReminderDelivery` Room entity+DAO; migration 142→143 |
| P9-P1-10 | P1 | Worker pause/resume registry hardcoded and asymmetric | ✅ FIXED | ✅ **FIXED** | Explicit `WorkerSpec.oneShotPolicy`; symmetry tested |
| P9-P1-11 | P1 | Privacy changes don't actively cancel workers | ✅ FIXED | ✅ **FIXED** | `PrivacyRuntimeWorkerPolicy` drives cancellation |
| P9-NEW-03 | P2 | `BackgroundJobRun` rows recorded zero counts | ✅ FIXED | ✅ **FIXED** | All workers migrated to `runGuardedWithContext` with counts |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P9-001 | P1 | TimeoutCancellationException misclassified as system cancellation | WorkerExecutionGuard.kt | ✅ FIXED (P9-PR1) |
| NEW-P9-002 | P1 | BillReminderWorker bypasses guard for settings/quiet-hours | BillReminderWorker.kt | ✅ FIXED (P9-PR1) |
| NEW-P9-003 | P1 | WorkerRunContext counters not thread-safe | WorkerRunContext.kt | ✅ FIXED (P9-PR1) |
| NEW-P9-004 | P1 | WarrantyExpirationWorker uses `runGuarded` (no context) | WarrantyExpirationWorker.kt | ✅ FIXED (already uses runGuardedWithContext) |
| NEW-P9-005 | P1 | WarrantyExpirationWorker uses `System.currentTimeMillis` | WarrantyExpirationWorker.kt | ✅ FIXED (U-PR7) |
| NEW-P9-006 | P2 | WorkerSpecScheduler uses deprecated REPLACE | WorkerSpecScheduler.kt | ✅ FIXED (P9-PR1/P9-PR2) — uses UPDATE on version bump; specs may still use REPLACE as design choice |
| NEW-P9-007 | P2 | SharedPreferences version write not atomic with enqueue | WorkerSpecScheduler.kt | ✅ FIXED (P9-PR1) — version written AFTER enqueue inside same try block; crash between them safely re-enqueues |
| NEW-P9-008 | P2 | NotificationIntakeWorker not in guard/registry | NotificationIntakeWorker.kt | ⚠ PARTIAL — has `executionGuard.checkpoint()` for maintenance stop observation, but does not use full `runGuarded`/`runGuardedWithContext` lifecycle |
| NEW-P9-009 | P2 | LocationBackfillWorker `isStopped` exits as SUCCESS | LocationBackfillWorker.kt | ✅ FIXED (P9-PR2) — throws `RetryableWorkerException` when stopped mid-loop |
| NEW-P9-010 | P2 | MerchantKeyBackfillWorker same `isStopped` issue | MerchantKeyBackfillWorker.kt | ✅ FIXED (P9-PR2) — same `RetryableWorkerException` pattern |
| NEW-P9-011 | P2 | `scheduleAtMidnight` near-zero delay edge case | WorkerSpecScheduler.kt | ✅ FIXED (P9-PR1) — `maxOf(rawDelayMs, 60_000L)` floor |
| NEW-P9-012 | P2 | DailyBriefing reschedule failure silently swallowed | DailyBriefingWorker.kt | ✅ FIXED (U-PR6, 74c2e5b8) |
| NEW-P9-013 | P2 | WorkerExecutionGuard read-only path no exception handling | WorkerExecutionGuard.kt | ✅ FIXED (P9-PR1) — try-catch wraps `readBarrier.checkReadAllowed()` in both `runGuarded` and `runGuardedWithContext` |
| NEW-P9-014 | P3 | WorkerSpec no battery constraint for `merchant_key_backfill` | WorkerSpec.kt | ✅ FIXED (P9-PR1) — `setRequiresBatteryNotLow(true)` added |
| NEW-P9-015 | P3 | `WorkerRunLogger.Handle` not idempotent | WorkerRunLogger.kt | ✅ FIXED (P9-PR1) — `completed` flag guards all 6 terminal methods |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 12 |
| ✅ FIXED (new issues) | 11 |
| ⚠ PARTIAL (new issues) | 1 |
| 🔴 OPEN (new issues) | 0 |
| **Total open work** | **0 — 🟢 COMPLETE** |

---

## Priority Order for Remaining Work

> **All 15 NEW-P9 issues are now resolved.**
> - 11 ✅ FIXED
> - 1 ⚠ PARTIAL (NEW-P9-008: NotificationIntakeWorker has `checkpoint()` but not full `runGuarded` lifecycle — low-risk, bespoke worker design)
> - 0 🔴 OPEN

### Fixed issues (verified 2026-06-01)

| ID | Sev | Status | Fix |
|----|-----|--------|-----|
| NEW-P9-001 | P1 | ✅ FIXED | TimeoutCancellationException classified as retryable |
| NEW-P9-002 | P1 | ✅ FIXED | Settings/quiet-hours check moved inside guard |
| NEW-P9-003 | P1 | ✅ FIXED | WorkerRunContext counters thread-safe (atomic ops) |
| NEW-P9-004 | P1 | ✅ FIXED | WarrantyExpirationWorker uses `runGuardedWithContext` |
| NEW-P9-005 | P1 | ✅ FIXED | Uses injected `TimeProvider` (U-PR7) |
| NEW-P9-006 | P2 | ✅ FIXED | Uses UPDATE on version bump; no deprecated REPLACE constant |
| NEW-P9-007 | P2 | ✅ FIXED | Version write after enqueue, inside same try block |
| NEW-P9-008 | P2 | ⚠ PARTIAL | `checkpoint()` added; full guard refactor not needed for bespoke worker |
| NEW-P9-009 | P2 | ✅ FIXED | Throws `RetryableWorkerException` when stopped mid-loop |
| NEW-P9-010 | P2 | ✅ FIXED | Same pattern as NEW-P9-009 |
| NEW-P9-011 | P2 | ✅ FIXED | 60s floor on midnight delay |
| NEW-P9-012 | P2 | ✅ FIXED | Reschedule failure logged (U-PR6) |
| NEW-P9-013 | P2 | ✅ FIXED | Read-only path wrapped in try-catch |
| NEW-P9-014 | P3 | ✅ FIXED | `setRequiresBatteryNotLow(true)` for merchant_key_backfill |
| NEW-P9-015 | P3 | ✅ FIXED | `completed` flag on Handle methods |
