# Pipeline 9 — Workers/Background Jobs: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 12 FIXED, 0 PARTIAL, 0 TODO, 15 NEW open issues  
> **Total open items:** 15

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
| NEW-P9-001 | P1 | TimeoutCancellationException misclassified as system cancellation | WorkerExecutionGuard.kt | 🔴 OPEN |
| NEW-P9-002 | P1 | BillReminderWorker bypasses guard for settings/quiet-hours | BillReminderWorker.kt | 🔴 OPEN |
| NEW-P9-003 | P1 | WorkerRunContext counters not thread-safe | WorkerRunContext.kt | 🔴 OPEN |
| NEW-P9-004 | P1 | WarrantyExpirationWorker uses `runGuarded` (no context) | WarrantyExpirationWorker.kt | 🔴 OPEN |
| NEW-P9-005 | P1 | WarrantyExpirationWorker uses `System.currentTimeMillis` | WarrantyExpirationWorker.kt | 🔴 OPEN |
| NEW-P9-006 | P2 | WorkerSpecScheduler uses deprecated REPLACE | WorkerSpecScheduler.kt | 🔴 OPEN |
| NEW-P9-007 | P2 | SharedPreferences version write not atomic with enqueue | WorkerSpecScheduler.kt | 🔴 OPEN |
| NEW-P9-008 | P2 | NotificationIntakeWorker not in guard/registry | NotificationIntakeWorker.kt | 🔴 OPEN |
| NEW-P9-009 | P2 | LocationBackfillWorker `isStopped` exits as SUCCESS | LocationBackfillWorker.kt | 🔴 OPEN |
| NEW-P9-010 | P2 | MerchantKeyBackfillWorker same `isStopped` issue | MerchantKeyBackfillWorker.kt | 🔴 OPEN |
| NEW-P9-011 | P2 | `scheduleAtMidnight` near-zero delay edge case | WorkerSpecScheduler.kt | 🔴 OPEN |
| NEW-P9-012 | P2 | DailyBriefing reschedule failure silently swallowed | DailyBriefingWorker.kt | ✅ FIXED (U-PR6, 74c2e5b8) |
| NEW-P9-013 | P2 | WorkerExecutionGuard read-only path no exception handling | WorkerExecutionGuard.kt | 🔴 OPEN |
| NEW-P9-014 | P3 | WorkerSpec no battery constraint for `merchant_key_backfill` | WorkerSpec.kt | 🔴 OPEN |
| NEW-P9-015 | P3 | `WorkerRunLogger.Handle` not idempotent | WorkerRunLogger.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 12 |
| ✅ FIXED (new issues, U-PR6) | 1 |
| 🔴 OPEN (new issues) | 14 |
| **Total open work** | **14** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. **NEW-P9-001** — TimeoutCancellationException misclassified as system cancellation (worker retries wasted)
2. **NEW-P9-002** — BillReminderWorker bypasses guard for settings/quiet-hours
3. **NEW-P9-003** — WorkerRunContext counters not thread-safe (corrupted run stats)
4. **NEW-P9-004** — WarrantyExpirationWorker uses `runGuarded` (no context — zero counts)
5. **NEW-P9-005** — WarrantyExpirationWorker uses `System.currentTimeMillis` (not testable)

### P2 (should fix)
6. **NEW-P9-006** — WorkerSpecScheduler uses deprecated REPLACE
7. **NEW-P9-007** — SharedPreferences version write not atomic with enqueue
8. **NEW-P9-008** — NotificationIntakeWorker not in guard/registry
9. **NEW-P9-009** — LocationBackfillWorker `isStopped` exits as SUCCESS (misleading)
10. **NEW-P9-010** — MerchantKeyBackfillWorker same `isStopped` issue
11. **NEW-P9-011** — `scheduleAtMidnight` near-zero delay edge case
12. **NEW-P9-012** — DailyBriefing reschedule failure silently swallowed
13. **NEW-P9-013** — WorkerExecutionGuard read-only path no exception handling

### P3 (cleanup)
14. **NEW-P9-014** — WorkerSpec no battery constraint for `merchant_key_backfill`
15. **NEW-P9-015** — `WorkerRunLogger.Handle` not idempotent
