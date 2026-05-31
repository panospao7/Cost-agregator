# Pipeline 9 — Workers / Background Jobs: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 9 — Workers / Background Jobs  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 9 — Workers / Background Jobs
Verdict: YELLOW
Summary:
- All 12 old issues fully FIXED
- 2 issues FIXED by universal (NEW-P9-005 via U-PR7, NEW-P9-012 via U-PR6)
- 12 pipeline-local issues remain (4 P1, 6 P2, 2 P3)
- Core WorkerExecutionGuard infrastructure is solid
- Key gaps: timeout misclassification, guard bypass in BillReminder, thread-unsafe counters
- NotificationIntakeWorker not integrated with guard/registry
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_9_CONSOLIDATED_ISSUES.md`

**Source files:** `WorkerExecutionGuard.kt`, `WorkerRunContext.kt`, `BillReminderWorker.kt`, `WarrantyExpirationWorker.kt`, `WorkerSpecScheduler.kt`, `NotificationIntakeWorker.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 9 | Adapter Needed | Status |
|---|---|---|---|
| U-PR6 (Worker Guard) | Fixes NEW-P9-012 — DailyBriefing reschedule | No | ✅ Fixed |
| U-PR7 (TimeProvider) | Fixes NEW-P9-005 — WarrantyWorker System.currentTimeMillis | No | ✅ Fixed |
| Others | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P9-P1-01 through P9-P1-11 | ✅ FIXED | U-PR6 | None |
| P9-NEW-03 | ✅ FIXED | U-PR6 | None |
| NEW-P9-001 | 🔴 OPEN | None | Classify TimeoutCancellationException |
| NEW-P9-002 | 🔴 OPEN | None | Move settings check inside guard |
| NEW-P9-003 | 🔴 OPEN | None | Make counters AtomicInteger |
| NEW-P9-004 | 🔴 OPEN | None | Migrate to runGuardedWithContext |
| NEW-P9-005 | ✅ FIXED | U-PR7 | None |
| NEW-P9-006 | 🔴 OPEN | None | Use KEEP or UPDATE policy |
| NEW-P9-007 | 🔴 OPEN | None | Atomic version+enqueue |
| NEW-P9-008 | 🔴 OPEN | None | Register in guard/registry |
| NEW-P9-009/010 | 🔴 OPEN | None | Return RETRY on isStopped |
| NEW-P9-011 | 🔴 OPEN | None | Handle near-midnight edge |
| NEW-P9-012 | ✅ FIXED | U-PR6 | None |
| NEW-P9-013 | 🔴 OPEN | None | Add exception handling |
| NEW-P9-014/015 | 🔴 OPEN | None | Battery constraint; idempotent handle |

---

## 5. New Issues / Regressions

No regressions from universal fixes.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| NEW-P9-001 | P1 | TimeoutCancellationException misclassified | Guard | P9-PR1 |
| NEW-P9-002 | P1 | BillReminderWorker bypasses guard | Worker | P9-PR1 |
| NEW-P9-003 | P1 | WorkerRunContext counters not thread-safe | Context | P9-PR1 |
| NEW-P9-004 | P1 | WarrantyWorker uses runGuarded (no context) | Worker | P9-PR1 |
| NEW-P9-008 | P2 | NotificationIntakeWorker not in guard | Worker | P9-PR2 |
| NEW-P9-009 | P2 | LocationBackfillWorker isStopped=SUCCESS | Worker | P9-PR2 |
| NEW-P9-010 | P2 | MerchantKeyBackfillWorker isStopped=SUCCESS | Worker | P9-PR2 |
| NEW-P9-013 | P2 | Guard read-only path no exception handling | Guard | P9-PR2 |
| NEW-P9-006 | P2 | WorkerSpecScheduler REPLACE policy | Scheduler | P9-PR3 |
| NEW-P9-007 | P2 | Version write not atomic with enqueue | Scheduler | P9-PR3 |
| NEW-P9-011 | P2 | scheduleAtMidnight near-zero delay | Scheduler | P9-PR3 |
| NEW-P9-014 | P3 | No battery constraint for backfill | Spec | P9-PR3 |
| NEW-P9-015 | P3 | WorkerRunLogger.Handle not idempotent | Logger | P9-PR3 |

---

## 7. PR Organization

### P9-PR1 — Guard & Worker Correctness

```
PR name: fix(p9): timeout classification, guard bypass, thread-safe counters, warranty context
Goal: Fix P1 correctness issues in worker execution
Issues fixed: NEW-P9-001, NEW-P9-002, NEW-P9-003, NEW-P9-004
Universal dependencies: U-PR6 (already landed)
Files likely touched:
  - WorkerExecutionGuard.kt
  - WorkerRunContext.kt
  - BillReminderWorker.kt
  - WarrantyExpirationWorker.kt
Implementation steps:
  1. NEW-P9-001: In guard's catch block, check `e is TimeoutCancellationException`; classify as TIMED_OUT (not CANCELLED_BY_SYSTEM); allow retry
  2. NEW-P9-002: Move BillReminderWorker's settings/quiet-hours check INSIDE the guard's runGuardedWithContext block (after barrier check, before work)
  3. NEW-P9-003: Replace Int counters in WorkerRunContext with AtomicInteger; or use synchronized increment
  4. NEW-P9-004: Migrate WarrantyExpirationWorker from runGuarded() to runGuardedWithContext(); use context for counting processed/skipped warranties
Tests:
  - timeout_classified_as_TIMED_OUT_not_CANCELLED
  - bill_reminder_settings_check_inside_guard
  - concurrent_counter_increments_are_safe
  - warranty_worker_records_nonzero_counts
Risks: Low — targeted fixes within existing guard infrastructure
Acceptance criteria:
  - Timeout produces TIMED_OUT status (retryable)
  - BillReminderWorker fully guarded (no pre-guard side effects)
  - Counter values correct under concurrent access
  - WarrantyWorker records actual processed count
```

### P9-PR2 — Worker Integration & Edge Cases

```
PR name: fix(p9): register intake worker, fix isStopped handling, guard exception path
Goal: Integrate remaining workers and fix edge cases
Issues fixed: NEW-P9-008, NEW-P9-009, NEW-P9-010, NEW-P9-013
Universal dependencies: None
Files likely touched:
  - NotificationIntakeWorker.kt
  - LocationBackfillWorker.kt
  - MerchantKeyBackfillWorker.kt
  - WorkerExecutionGuard.kt
Implementation steps:
  1. NEW-P9-008: Register NotificationIntakeWorker in WorkerRegistry; migrate to use WorkerExecutionGuard.runGuardedWithContext
  2. NEW-P9-009/010: When isStopped is true, return Result.retry() (not SUCCESS); log STOPPED_BY_SYSTEM status
  3. NEW-P9-013: Add try/catch around guard's read-only path (spec check, privacy check); on exception, log and return SKIPPED (not crash)
Tests:
  - intake_worker_registered_in_guard
  - stopped_worker_returns_retry_not_success
  - guard_read_only_exception_returns_skipped
Risks: Low — integration work
Acceptance criteria:
  - All workers registered in guard/registry
  - isStopped never produces misleading SUCCESS
  - Guard exceptions don't crash worker
```

### P9-PR3 — Scheduler & Cleanup

```
PR name: fix(p9): scheduler policy, atomic version, midnight edge, battery, idempotent handle
Goal: Fix scheduler issues and cleanup
Issues fixed: NEW-P9-006, NEW-P9-007, NEW-P9-011, NEW-P9-014, NEW-P9-015
Universal dependencies: None
Files likely touched:
  - WorkerSpecScheduler.kt
  - WorkerSpec.kt
  - WorkerRunLogger.kt
Implementation steps:
  1. NEW-P9-006: Change periodic worker enqueue from REPLACE to KEEP (don't restart running workers); use UPDATE for constraint changes
  2. NEW-P9-007: Write version to SharedPreferences AFTER successful enqueue (not before); or use single atomic operation
  3. NEW-P9-011: In scheduleAtMidnight, if computed delay < MIN_DELAY_MS (e.g. 60s), schedule for next day's midnight
  4. NEW-P9-014: Add NetworkType.CONNECTED + battery constraint to merchant_key_backfill WorkerSpec
  5. NEW-P9-015: Make WorkerRunLogger.Handle.complete() idempotent — no-op on second call; log warning
Tests:
  - periodic_worker_uses_KEEP_policy
  - version_written_after_successful_enqueue
  - near_midnight_schedules_next_day
  - backfill_requires_battery
  - double_complete_is_noop
Risks: Low — scheduler/cleanup
Acceptance criteria:
  - Running workers not restarted by scheduler
  - Version consistent with actual enqueued state
  - No near-zero delay scheduling
  - Heavy backfill respects battery
  - Double-complete doesn't corrupt run record
```

---

## 8. Detailed Implementation Plan

### P9-PR1 Step-by-Step

1. **Open** `WorkerExecutionGuard.kt` — find cancellation handling; add: `is TimeoutCancellationException -> WorkerOutcome.TIMED_OUT`
2. **Open** `BillReminderWorker.kt` — find settings/quiet-hours check; move inside `runGuardedWithContext { }` lambda
3. **Open** `WorkerRunContext.kt` — replace `var processed: Int = 0` with `val processed = AtomicInteger(0)`; update increment calls
4. **Open** `WarrantyExpirationWorker.kt` — change `runGuarded { }` to `runGuardedWithContext { ctx -> ... ctx.incrementProcessed() }`

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 9 Adapter/Follow-up |
|---|---|
| U-PR6 (Worker Guard) | ✅ Already landed — all old issues resolved |
| U-PR7 (TimeProvider) | ✅ Already landed — WarrantyWorker uses TimeProvider |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 9 targeted tests
./gradlew :app:testDebugUnitTest --tests "*Worker*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Guard*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*WorkerRun*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Scheduler*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P9-PR1: Timeout classified correctly; guard not bypassed; counters thread-safe; warranty has context
- [ ] P9-PR2: All workers in registry; isStopped returns retry; guard exceptions handled
- [ ] P9-PR3: KEEP policy; atomic version; midnight edge handled; battery constraint; idempotent handle
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] Pipeline 9 status upgraded to GREEN in master tracker
