# Universal Issue Tracker — All 12 Pipelines

> **Generated:** 2026-05-31  
> **Input:** Pipeline reports 1–12 + deep audit NEW issues (59 + 59 + 18 = 136 new issues found)  
> **Total issues reviewed:** ~250 (121 existing report issues P5-9 + 77 P10-12 + ~50 P1-5 report issues + 136 new)  
> **Method:** Cross-pipeline root-cause clustering → classification → deduplication → PR organization  
> **Implementation progress (2026-05-31):** U-PR1 (CancellationException Safety) ✅ FULLY IMPLEMENTED — 146 guards across 38 files, architecture guard test passing

---

## 1. Executive Summary

| Category | Count |
|----------|------:|
| **Pipeline-local** | ~140 |
| **Multi-pipeline** | 18 |
| **Universal/shared infrastructure** | 14 |
| **Engine-level** | 8 |
| **Test/docs-only** | ~12 |
| **Total universal + engine issues** | **22** |

### Highest-risk universal issues:
1. **CancellationException swallowing** — 15+ locations across 8 pipelines
2. **TOCTOU race in update methods** — all Pipeline 2 update paths (consumed by all pipelines)
3. **Mixed-currency arithmetic without conversion** — P5, P6, P12
4. **Maintenance mode leaks** — P7 export paths leave app write-locked
5. **RawStorageMode inconsistency** — P1, P3, P8, P10, P11 use different modes for same content types
6. **WorkerExecutionGuard contract gaps** — P9 affects all 7 workers

---

## 2. Universal Issue Master Table

| ID | Sev | Title | Pipelines | Class | PR |
|----|-----|-------|-----------|-------|-----|
| U-CANCEL-01 | P1 | CancellationException swallowed in broad catch blocks | 1,3,4,6,7,8,9,10,11 | Universal | U-PR1 ✅ |
| U-TOCTOU-01 | P1 | beforeSnapshot captured outside DB transaction in all update methods | 2 (consumed by all) | Engine | U-PR2 ✅ |
| U-MONEY-01 | P1 | Mixed-currency arithmetic without conversion | 5,6,12 | Universal | U-PR3 ✅ |
| U-MONEY-02 | P1 | MoneyAggregate quality/warnings dropped by consumers | 5,6,12 | Universal | U-PR3 ✅ |
| U-MONEY-03 | P2 | Silent EUR fallback on homeCurrency resolution failure | 5,6,12 | Universal | U-PR3 ⏭ |
| U-BARRIER-01 | P0 | Maintenance mode not exited on early-return/failure paths | 7 (affects all) | Universal | U-PR4 |
| U-BARRIER-02 | P1 | DatabaseWriteBarrier not enforced consistently across all writers | 1,2,3,4,6,7,9,10 | Universal | U-PR4 |
| U-BARRIER-03 | P1 | Restore-blocked operations classified as FAILED not SKIPPED | 9 (affects all workers) | Universal | U-PR4 |
| U-PRIVACY-01 | P0 | RawStorageMode semantics inconsistent across content types | 1,3,8,10,11 | Universal | U-PR5 |
| U-PRIVACY-02 | P1 | EffectiveCloudAiPolicy not wired as authoritative cloud gate | 8 (affects 5,10,11) | Universal | U-PR5 |
| U-PRIVACY-03 | P1 | Retention/export redaction scope incomplete | 7,8,11,12 | Universal | U-PR5 |
| U-WORKER-01 | P1 | WorkerExecutionGuard writes BackgroundJobRun before barrier check | 9 (affects all workers) | Engine | U-PR6 |
| U-WORKER-02 | P1 | Cancelled workers leave RUNNING rows with no startup recovery | 9 (affects all workers) | Engine | U-PR6 |
| U-WORKER-03 | P1 | Worker run counts always zero / no-work paths logged as SUCCESS | 9 (affects all workers) | Engine | U-PR6 |
| U-WORKER-04 | P1 | DailyBriefing KEEP policy breaks one-shot chain | 9 | Engine | U-PR6 |
| U-TIME-01 | P2 | System.currentTimeMillis() used instead of TimeProvider | 4,9,10 | Universal | U-PR7 |
| U-TIME-02 | P2 | DST-unsafe day arithmetic (n * DAY_IN_MILLIS) | 6 | Engine | U-PR7 |
| U-SIDEEFFECT-01 | P1 | Transaction side effects dispatched twice | 11 | Multi-pipeline | U-PR8 |
| U-SIDEEFFECT-02 | P2 | Side-effect planner hardcodes EXPENSE_CREATED for update paths | 2 | Engine | U-PR8 |
| U-EXPORT-01 | P0 | JSON export produces invalid JSON (missing comma) | 12 | Pipeline-local | — |
| U-EXPORT-02 | P1 | CsvCellSanitizer corrupts negative amounts in accounting exports | 12 | Pipeline-local | — |
| U-DEAD-01 | P1 | Dead dashboard features (previousMonth null, runway=0) | 5 | Pipeline-local | — |

---

## 3. Detailed Universal Issues

### U-CANCEL-01: CancellationException swallowed in broad catch blocks

**Classification:** Universal/shared  
**Severity:** P1  
**Affected pipelines:** 1, 3, 4, 6, 7, 8, 9, 10, 11  
**Linked issue IDs:**
- NEW-P1-001, NEW-P3-001, NEW-P3-002, NEW-P3-003
- NEW-P4-001, NEW-P4-007
- NEW-P6-001, NEW-P6-002, NEW-P6-003, NEW-P6-005, NEW-P6-006
- NEW-P10-003, NEW-P10-018 (from report)
- P11-CURRENT-012, NEW-P11-001 (mutex)

**Shared files/classes:** Every `suspend fun` with `catch (e: Exception)` or `catch (_: Exception)`

**Root cause:** Kotlin coroutines use `CancellationException` (which extends `Exception`) for structured concurrency. Broad catches without rethrowing break cancellation propagation.

**Evidence per pipeline:**
- P1: `NotificationCaptureService.captureNotification` outer catch
- P3: `ReceiptSideEffectDispatcher`, `BankStatementLifecycleProcessor` per-item, `ReceiptLinkService.unlinkReceiptFromExpense`
- P4: `RecurringLifecycleCoordinator.reconcileAllLinkedExpensesAfterBulkUpdate`, `regenerateReminderDeliveries` (3 locations)
- P6: `FinancialStressForecastEngine.computeStressForecast`, `BudgetMonitor` (2 locations), `BudgetRepository` CRUD (5 methods), `computeAdjustedSpend`
- P9: Implicit via `WorkerExecutionGuard` rethrow before run finalization
- P10: `syncTransactions` per-transaction catch, `BankStatementLifecycleProcessor` per-item
- P11: `EmailReceiptIngestionService.processEmailReceipt`

**Why local fixes are unsafe:** Each pipeline fixing independently will use different patterns (some check `is CancellationException`, some use `runCatching`, some add `ensureActive()`). Need one shared pattern.

**Recommended shared fix:**
1. Add detekt custom rule: `SuspendFunctionBroadCatch` — flags `catch (e: Exception)` in suspend functions without `if (e is CancellationException) throw e`.
2. Add shared extension: `inline fun <T> runSuspendCatching(block: suspend () -> T): Result<T>` that rethrows CE.
3. Apply across all affected locations.

**Tests required:** `cancellation_propagates_through_all_pipeline_coordinators`  
**Architecture guard:** detekt rule in CI  
**Suggested PR:** U-PR1

**✅ IMPLEMENTATION STATUS (2026-05-31):**
- All Category B catches fixed (spec targets): P1, P3, P4, P6 — 22 guards
- All Category C catches fixed (best-effort events): P4 lifecycle events — 7 guards
- Additional suspend-fun catches fixed: P3 (ReceiptLifecycleCoordinator, ReceiptOcrService, ReceiptInputValidator), P5 (TransactionLifecycleCoordinator, TransactionSideEffectPlanner), P7 (SharedExpenseManager, SharedExpenseBudgetOffsetEngine), P9 (workers) — ~40 guards
- Architecture guard test: `CancellationSafetyArchitectureGuardTest` — PASSES
- Contract test: `CancellationPropagationContractTest` — 12 critical entry points
- Total CE guards in codebase: 146 across 38 files
- Remaining: ~120 catches in ViewModel/UI `launch {}` blocks (low-risk, deferred)

---

### U-TOCTOU-01: beforeSnapshot captured outside DB transaction

**Classification:** Engine-level  
**Severity:** P1  
**Affected pipelines:** 2 (consumed by 1, 3, 4, 5, 10, 11, 12)  
**Linked issue IDs:** NEW-P2-001, NEW-P2-002, NEW-P2-003

**Shared files:** `TransactionLifecycleCoordinator.kt`

**Root cause:** All update/delete methods read the existing row BEFORE `database.withTransaction`, creating a window where concurrent modifications can corrupt the audit snapshot.

**Recommended shared fix:**
```kotlin
private suspend inline fun <T> atomicReadModifyWrite(
    expenseId: Long,
    crossinline block: suspend (existing: Expense, snapshot: ExpenseSnapshot) -> T
): T = database.withTransaction {
    val existing = expenseDao.getById(expenseId) ?: throw ...
    val snapshot = expenseToSnapshot(existing)
    block(existing, snapshot)
}
```
Apply to all 8 affected methods.

**Suggested PR:** U-PR2

---

### U-MONEY-01: Mixed-currency arithmetic without conversion

**Classification:** Universal  
**Severity:** P1  
**Affected pipelines:** 5, 6, 12  
**Linked issue IDs:**
- NEW-P5-005 (SynthesisEngine sums planned expenses across currencies)
- P6-CURRENT-012 (RecurringPattern/ConfirmedOccurrence unnormalized)
- P5-CURRENT-009 (Block Party raw expenses)
- P6-CURRENT-021 (Stress detected patterns not converted)
- P12-CURRENT-010 (Accounting uses original not effective amount)

**Shared files:** `SynthesisEngine.kt`, `ForecastInputAssembler.kt`, `CashFlowCalculator.kt`, `FinancialStressForecastEngine.kt`

**Root cause:** Multiple engines sum `Double` amounts from different currencies without conversion. The `MoneyAggregate` primitive exists but isn't used everywhere.

**Recommended shared fix:**
1. `ForecastInputAssembler` must normalize ALL inputs (recurring patterns, confirmed occurrences) to home currency.
2. `SynthesisEngine` raw overload made `internal` — only accepts `ForecastInput` with normalized amounts.
3. Add CI guard: flag `.values.sum()` on currency-keyed maps without prior normalization.

**Suggested PR:** U-PR3

---

### U-BARRIER-01: Maintenance mode not exited on early-return paths

**Classification:** Universal  
**Severity:** P0  
**Affected pipelines:** 7 (affects all pipelines since app is write-locked)  
**Linked issue IDs:** NEW-P7-001, NEW-P7-002

**Root cause:** `exportDatabase()` enters `BACKUP_EXPORTING` mode but has 4 early-return paths that don't call `exit()`.

**Recommended shared fix:** Wrap all maintenance-mode-entering operations in try/finally:
```kotlin
restoreMaintenanceMode.enterBackupExporting()
try { ... } finally { restoreMaintenanceMode.exit(forceRestartRequired = false) }
```

**Suggested PR:** U-PR4

---

### U-PRIVACY-01: RawStorageMode semantics inconsistent across content types

**Classification:** Universal  
**Severity:** P0  
**Affected pipelines:** 1, 3, 8, 10, 11  
**Linked issue IDs:**
- P8-CURRENT-002 (notification DO_NOT_STORE falls through)
- P8-CURRENT-003 (redacted storage breaks parsing)
- P8-CURRENT-004 (bank statement ignores rawOcrStorageMode)
- P11-CURRENT-003 (email uses rawOcrStorageMode instead of emailReceiptStorageMode)
- P10-CURRENT-024 (bank statement stores raw OCR outside policy)

**Root cause:** Each pipeline applies `RawStorageMode` differently. Some use `rawOcrStorageMode` for email content, some don't apply it at all for bank statements, and notification path has non-exhaustive `when` handling.

**Recommended shared fix:**
1. Define `RawContentPolicy` with per-source-type modes: `notificationStorageMode`, `ocrStorageMode`, `emailStorageMode`, `bankStatementStorageMode`.
2. Each pipeline uses its own mode from the policy.
3. All pipelines share the pattern: ephemeral processing payload → sanitized storage payload.
4. `RawContentSanitizer` becomes the single sanitization entry point with source-type-aware methods.

**Suggested PR:** U-PR5

---

### U-WORKER-01/02/03: WorkerExecutionGuard contract gaps

**Classification:** Engine-level  
**Severity:** P1  
**Affected pipelines:** 9 (all 7 workers)  
**Linked issue IDs:** P9-CURRENT-001, P9-CURRENT-002, P9-CURRENT-003, P9-CURRENT-006, P9-CURRENT-007

**Root cause:** The guard writes `BackgroundJobRun` before checking the write barrier, classifies restore-blocked as FAILED, leaves RUNNING rows on cancellation, and records zero counts.

**Recommended shared fix:** Single PR to `WorkerExecutionGuard`:
1. Check barrier BEFORE `workerRunLogger.start()`
2. Catch `CancellationException` → mark CANCELLED (not rethrow before finalization)
3. Add `WorkerRunContext` with counts
4. Add startup recovery for stale RUNNING rows
5. Distinguish SUCCESS from SKIPPED_NO_WORK

**Suggested PR:** U-PR6

---

## 4. Multi-pipeline (not universal) Issues

These affect multiple pipelines but are best fixed per-pipeline because the fix is call-site-specific:

| Pattern | Pipelines | Why not universal |
|---------|-----------|-------------------|
| Notification ID collisions | 4 | Only BillReminderWorker uses this pattern |
| Parser `canParse()` too broad | 11 | Each parser has unique detection logic |
| Deposit filter includes "not mine" | 5 | Dashboard-specific filter logic |
| Budget alert uses wrong currency | 6 | Budget-specific notification path |
| JSON export invalid JSON | 12 | Export-specific string assembly |
| Accounting sanitizer corrupts negatives | 12 | Export-specific sanitizer usage |

---

## 5. Pipeline-local Issue Pass-through

Issues that remain local and should go back to pipeline agents:

**Pipeline 1:** NEW-P1-005 (filter blocks deposits), NEW-P1-006 (failed keyword too broad), NEW-P1-008 (processMutex bottleneck), NEW-P1-013 (combinedBody as bigText)

**Pipeline 2:** NEW-P2-004 (non-atomic duplicate check), NEW-P2-005 (DefaultExpenseCategoryAssignmentService bypass), NEW-P2-009 (planner hardcodes trigger type), NEW-P2-010 (inconsistent event guard)

**Pipeline 3:** NEW-P3-005 (race in post-OCR duplicate), NEW-P3-007 (deleteReceipt event for non-existent receipt)

**Pipeline 4:** NEW-P4-003 (occurrence lookup outside transaction), NEW-P4-005/006 (notification/PendingIntent ID collisions), NEW-P4-009 (JSON injection in metadata)

**Pipeline 5:** NEW-P5-001 (previousMonth always null), NEW-P5-004 (wrong average denominator), NEW-P5-010 (per-expense vs per-day average), NEW-P5-011 (runway always 0)

**Pipeline 6:** NEW-P6-004 (unbounded rollover loop), NEW-P6-007/008 (stress interval/pattern issues), NEW-P6-010 (hardcoded thresholds), NEW-P6-015 (income recurring as expense)

**Pipeline 7:** NEW-P7-003 (non-atomic critical state), NEW-P7-004 (journal race), NEW-P7-005 (FileInputStream leak)

**Pipeline 8:** NEW-P8-003 (regex over-matches), NEW-P8-004 (PII sanitizer gaps), NEW-P8-005 (requireAllowed ignores capability)

**Pipeline 9:** NEW-P9-001 (TimeoutCancellationException), NEW-P9-002 (BillReminder bypasses guard), NEW-P9-008 (NotificationIntakeWorker not registered)

**Pipeline 10:** NEW-P10-002 (BankTokenCipher swallows key invalidation), NEW-P10-004 (non-deterministic mocks)

**Pipeline 11:** NEW-P11-002/003 (parser canParse too broad), NEW-P11-005 (double-escaped regex)

**Pipeline 12:** NEW-P12-001 (invalid JSON), NEW-P12-002 (double-escaped sourceLinks), NEW-P12-003 (sanitizer corrupts negatives), NEW-P12-005 (OOM in validation)

---

## 6. Do-Not-Fix-Locally List

Pipeline agents should **PAUSE** on these until the universal PR lands:

| Issue | Pipelines affected | Wait for |
|-------|-------------------|----------|
| CancellationException in catch blocks | 1,3,4,6,8,9,10,11 | U-PR1 (shared detekt rule + helper) |
| RawStorageMode handling for notification/OCR/email/bank | 1,3,8,10,11 | U-PR5 (shared RawContentPolicy) |
| Mixed-currency forecast/cashflow sums | 5,6 | U-PR3 (ForecastInputAssembler normalization) |
| WorkerExecutionGuard barrier/cancellation/counts | All workers in P9 | U-PR6 (guard contract fix) |
| Maintenance mode exit guarantee | 7 (affects all) | U-PR4 (try/finally pattern) |
| beforeSnapshot outside transaction | 2 (affects all consumers) | U-PR2 (atomicReadModifyWrite helper) |

---

## 7. Suggested Universal PR Plan

### U-PR1 — CancellationException Safety
- **Issues:** U-CANCEL-01
- **Pipelines:** 1, 3, 4, 6, 8, 9, 10, 11
- **Files:** Add `domain/util/SuspendSafety.kt`, detekt rule, fix 15+ catch blocks
- **Steps:** (1) Add `rethrowIfCancellation(e)` helper (2) Add detekt rule (3) Fix all locations (4) CI gate
- **Tests:** Per-pipeline cancellation propagation tests
- **Risk:** Low — additive, no behavior change for non-cancelled paths

### U-PR2 — TOCTOU Race Elimination in TransactionLifecycleCoordinator
- **Issues:** U-TOCTOU-01
- **Pipelines:** 2 (consumed by all)
- **Files:** `TransactionLifecycleCoordinator.kt`
- **Steps:** (1) Create `atomicReadModifyWrite` helper (2) Refactor 8 update methods (3) Refactor delete(Expense) overload
- **Tests:** Concurrent update test proving snapshot correctness
- **Risk:** Medium — touches hot path; needs thorough testing

### U-PR3 — Mixed-Currency Arithmetic Guard
- **Issues:** U-MONEY-01, U-MONEY-02, U-MONEY-03
- **Pipelines:** 5, 6, 12
- **Files:** `ForecastInputAssembler.kt`, `SynthesisEngine.kt`, `CashFlowCalculator.kt`, `FinancialStressForecastEngine.kt`
- **Steps:** (1) Normalize recurring patterns/occurrences in assembler (2) Make raw SynthesisEngine overload internal (3) Add CI guard for `.values.sum()` on currency maps (4) HomeCurrencyResolution instead of silent EUR
- **Tests:** `forecast_recurring_pattern_amounts_are_home_currency`, `synthesis_rejects_mixed_currency_input`
- **Risk:** Medium — changes forecast arithmetic; verify with golden tests

### U-PR4 — Maintenance Mode Exit Guarantee
- **Issues:** U-BARRIER-01, U-BARRIER-02, U-BARRIER-03
- **Pipelines:** 7 (affects all)
- **Files:** `DatabaseBackupRepositoryImpl.kt`, `RestoreMaintenanceMode.kt`, `WorkerExecutionGuard.kt`
- **Steps:** (1) try/finally for all maintenance-entering operations (2) Restore-blocked → SKIPPED not FAILED (3) Audit all early-return paths
- **Tests:** `encrypted_export_success_exits_maintenance`, `privacy_gate_denial_exits_maintenance`
- **Risk:** Low — defensive fix, no behavior change for happy path
- **Dependencies:** None — should land FIRST

### U-PR5 — RawStorageMode/Privacy Contract
- **Issues:** U-PRIVACY-01, U-PRIVACY-02, U-PRIVACY-03
- **Pipelines:** 1, 3, 7, 8, 10, 11, 12
- **Files:** `RawContentSanitizer.kt`, `PrivacySettings.kt`, `EffectiveCloudAiPolicy.kt`, `DataRetentionWorker.kt`, `ExportAnonymizer.kt`
- **Steps:** (1) Define per-source-type storage modes (2) Wire EffectiveCloudAiPolicy as authoritative (3) Expand retention targets (4) Expand export redaction scope
- **Tests:** Per-source-type privacy matrix tests
- **Risk:** High — touches privacy-critical paths; needs careful review
- **Dependencies:** U-PR4 (barrier must work before privacy writes)

### U-PR6 — WorkerExecutionGuard Contract
- **Issues:** U-WORKER-01, U-WORKER-02, U-WORKER-03, U-WORKER-04
- **Pipelines:** 9 (all 7 workers)
- **Files:** `WorkerExecutionGuard.kt`, `WorkerRunLogger.kt`, `WorkerRunContext.kt`, `AppStartupCoordinator.kt`
- **Steps:** (1) Barrier check before run log (2) CANCELLED handling (3) Startup stale recovery (4) WorkerRunContext with counts (5) SKIPPED_NO_WORK status
- **Tests:** `worker_guard_does_not_write_before_barrier`, `cancelled_worker_marks_CANCELLED`, `startup_recovers_stale_RUNNING`
- **Risk:** Medium — changes all worker finalization behavior
- **Dependencies:** U-PR4 (barrier semantics must be stable)

### U-PR7 — TimeProvider Consistency
- **Issues:** U-TIME-01, U-TIME-02
- **Pipelines:** 4, 6, 9, 10
- **Files:** `BillReminderWorker.kt`, `WarrantyExpirationWorker.kt`, `FinancialStressForecastEngine.kt`, `BankApiIntegration.kt`
- **Steps:** (1) Inject TimeProvider into all affected classes (2) Replace System.currentTimeMillis() (3) Replace n*DAY_IN_MILLIS with calendar-aware helpers
- **Tests:** Deterministic time tests for each affected worker/engine
- **Risk:** Low — mechanical replacement

### U-PR8 — Transaction Side-Effect Semantics
- **Issues:** U-SIDEEFFECT-01, U-SIDEEFFECT-02
- **Pipelines:** 2, 11
- **Files:** `TransactionSideEffectPlanner.kt`, `EmailReceiptIngestionService.kt`
- **Steps:** (1) Remove duplicate dispatch in email service (2) Fix planner trigger type for update paths (3) Make idempotency keys time-unique
- **Tests:** `email_receipt_dispatches_side_effects_once`, `update_path_uses_correct_trigger_type`
- **Risk:** Low — removes duplicate work

---

## 8. Dependency/Order Graph

```
U-PR4 (Maintenance mode exit guarantee)
  ├── before U-PR5 (Privacy contract — needs barrier working)
  ├── before U-PR6 (Worker guard — needs barrier semantics stable)
  └── before all pipeline-local barrier fixes

U-PR1 (CancellationException safety)
  └── before all pipeline-local catch-block fixes
  
U-PR2 (TOCTOU race elimination)
  └── before Pipeline 2 local fixes (duplicate check, event propagation)

U-PR3 (Mixed-currency guard)
  ├── before Pipeline 5 dashboard fixes
  ├── before Pipeline 6 forecast/cashflow fixes
  └── before Pipeline 12 accounting export fixes

U-PR5 (Privacy contract)
  ├── before Pipeline 1 notification storage fixes
  ├── before Pipeline 3 receipt/email storage fixes
  ├── before Pipeline 8 retention/redaction fixes
  ├── before Pipeline 10 bank statement privacy fixes
  ├── before Pipeline 11 email privacy fixes
  └── before Pipeline 12 export redaction fixes

U-PR6 (Worker guard contract)
  └── before all Pipeline 9 worker-specific fixes

U-PR7 (TimeProvider)
  └── independent — can land anytime

U-PR8 (Side-effect semantics)
  └── before Pipeline 11 email side-effect fixes
```

**Recommended landing order:**
1. U-PR4 (barrier — unblocks everything)
2. U-PR1 (cancellation — mechanical, low risk)
3. U-PR7 (time — mechanical, low risk)
4. U-PR8 (side effects — small scope)
5. U-PR2 (TOCTOU — medium risk, needs testing)
6. U-PR6 (worker guard — medium risk)
7. U-PR3 (money — medium risk, needs golden tests)
8. U-PR5 (privacy — high risk, needs careful review)

---

## 9. Validation Strategy

```bash
# After each universal PR:
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# After U-PR1 (cancellation):
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"

# After U-PR2 (TOCTOU):
./gradlew :app:testDebugUnitTest --tests "*TransactionLifecycle*"

# After U-PR3 (money):
./gradlew :app:testDebugUnitTest --tests "*Forecast*" --tests "*Synthesis*" --tests "*CashFlow*"

# After U-PR4 (barrier):
./gradlew :app:testDebugUnitTest --tests "*Restore*" --tests "*Backup*" --tests "*Barrier*"

# After U-PR5 (privacy):
python scripts/verify_privacy_boundaries.py
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --tests "*RawStorage*" --tests "*Retention*"

# After U-PR6 (worker):
./gradlew :app:testDebugUnitTest --tests "*Worker*" --tests "*Guard*"

# Full verification:
./gradlew :app:check --stacktrace
python scripts/verify_db_access_boundaries.py
python scripts/verify_money_boundaries.py
python scripts/verify_privacy_boundaries.py
python scripts/verify_event_writers.py
```

---

## 10. Final Recommendation

**Must land before pipeline-local work continues:**

1. **U-PR4** (Maintenance mode exit guarantee) — P0, one-line fixes, unblocks everything
2. **U-PR1** (CancellationException safety) — P1, mechanical, prevents 15+ inconsistent local fixes
3. **U-PR5** (RawStorageMode contract) — P0 privacy, prevents 5 pipelines from implementing different storage policies

**Can proceed in parallel with pipeline-local work:**
- U-PR7 (TimeProvider) — independent
- U-PR8 (Side-effect semantics) — small scope

**Should land before dashboard/forecast/export fixes:**
- U-PR3 (Mixed-currency guard)
- U-PR2 (TOCTOU race)
- U-PR6 (Worker guard)

Pipeline agents should implement their local fixes ONLY for issues not in the "Do-Not-Fix-Locally" list. For issues in that list, they should add pipeline-specific tests that will pass once the universal PR lands.
