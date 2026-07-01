# Cancellation / Atomicity Risk Baseline

Generated: 2026-07-01  
Scope: MIT-031, MIT-034, MIT-041, MIT-043  
PR: PR 1 — Baseline and Policies  
Format: Full inventory of cancellation risks, atomicity gaps, event consistency risks, and hidden writes across the codebase.

---

## Executive Summary

The codebase has made significant progress on cancellation safety (U-PR1 landed — 146 CE guards across 38 files). However, gaps remain:

| Category | Count | Status |
|----------|------:|--------|
| `catch(Exception)` **with** CE rethrow in main source | ~100+ | ✅ Covered |
| `catch(Exception)` **without** CE rethrow in suspend paths | ~17 | ❌ Needs fix (in PR 2+) |
| `runCatching` in suspend paths | ~50+ | ⚠️ Mixed — some safe, some risky |
| Direct lifecycle event DAO inserts | ~90 | ❌ No guard blocking direct inserts |
| State+event transactional pairs | ~20+ files | ⚠️ Mostly in `withTransaction` blocks but not verified for atomic completeness |
| Hidden writes in read-named methods | 3 HIGH, 4 MEDIUM | ❌ Needs split into explicit write commands |
| `PendingReview` atomic with receipt save | Unknown | ❌ Not verified — gap for MIT-041 |
| Recurring projection atomicity | Partial | ⚠️ `withTransaction` exists but gaps remain |
| Worker terminal state CAS | Partial | ⚠️ Guard wraps workers, terminal race mitigated by Mutex + conditional UPDATE (`WHERE status='RUNNING'`) but not proven single-write under all concurrency patterns |
| Post-commit side-effect durability | Partial | ⚠️ `PostCommitActionRunner` exists, outbox pattern not implemented |

Previously-landed universal PRs (acknowledged):
- **U-PR1** — CancellationException safety (146 guards, architecture guard test) ✅
- **U-PR2** — TOCTOU race elimination ✅
- **U-PR3** — Mixed-currency arithmetic guard ✅
- **U-PR4** — Maintenance mode exit guarantee + write/restore barrier enforcement ✅
- **U-PR6** — WorkerExecutionGuard contract (barrier before run log, cancellation handling, NO_WORK status) ✅
- **U-PR7** — TimeProvider consistency ✅
- **U-PR8** — Transaction side-effect semantics ✅

---

## 1. CancellationException Safety Inventory

### 1.1 Sites WITH CE Rethrow (✅ Covered)

U-PR1 has landed with extensive CE rethrow coverage. Key files with full coverage:

| Category | Files | Approx. sites |
|----------|-------|-------------|
| Workers | `NotificationIntakeWorker`, `ReceiptMatchingWorker`, `BillReminderWorker`, `DataRetentionWorker`, `WarrantyExpirationWorker`, `LocationBackfillWorker`, `MerchantKeyBackfillWorker`, `SourceLinkBackfillWorker` | 20+ |
| Coordinators | `RecurringLifecycleCoordinator` (8 sites), `GroupLifecycleCoordinator` (9 sites), `TransactionLifecycleCoordinator` | 20+ |
| Repositories | `BudgetRepository` (8 sites), `ReceiptRepository`, `DatabaseBackupRepositoryImpl` (18 sites) | 30+ |
| Services | `EmailReceiptIngestionService` (12 sites), `LegacyDataMigrationService` (14 sites) | 26+ |
| Guard infrastructure | `WorkerExecutionGuard` (8 sites), `WorkerRunLogger`, `WorkerSpecScheduler` | 10+ |
| Other | `CashFlowCalculator`, `TotalsAggregationEngine`, `CloudReceiptAssistService`, `SharedExpenseManager`, etc. | 20+ |

**Total CE-guarded sites in main source: ~100+**

**Architecture guard:** `CancellationSafetyArchitectureGuardTest` — 146 CE guards across 38 files, PASSES in CI.

**Contract test:** `CancellationPropagationContractTest` — 12 critical entry points verified.

### 1.2 Sites WITHOUT CE Rethrow in Suspend Paths (❌ Needs Fix)

These are the remaining risky sites found in the inventory. They will be addressed in PR 2+.

| # | File | Line(s) | Method | Risk |
|---|------|---------|--------|------|
| 1 | `service/RecommendationInvalidator.kt` | 46, 68, 88, 107 | `invalidateAllForUser`, `invalidateStale`, `clearForUser`, `cleanupExpired` | HIGH |
| 2 | `service/RecommendationDismissalHandler.kt` | 23, 30, 41 | `dismiss`, `dismissAndRefresh` | HIGH |
| 3 | `service/RecommendationLifecycleManager.kt` | 40, 52, 69 | `checkAndExpire`, `cleanupExpired`, `refreshThreshold` | HIGH |
| 4 | `service/RecommendationStateManager.kt` | 138, 171, 246 | `refreshForUser`, `dismiss`, `clearForUser` | HIGH |
| 5 | `domain/notification/capture/NotificationCaptureGate.kt` | 77, 84, 187, 199, 229, 262 | `warmUp`, `decide` | MEDIUM |
| 6 | `data/ai/provider/OnDeviceCategorizationAssistService.kt` | 55 | `suggestCategory` | MEDIUM |

**Total sites needing CE rethrow: ~17 (all in suspend functions or coroutine scopes)**

### 1.3 runCatching Sites

`runCatching` is used extensively (~100+ sites in main source). Many uses are safe (non-suspend, UI, enum parsing). The risky uses are in suspend/coroutine paths.

**Risky categories:**

| Category | Files | Approx. sites | Risk |
|----------|-------|-------------|------|
| Suspend coordinator paths | `TransactionLifecycleCoordinator.kt` | 10 | HIGH |
| Domain engines | `FinancialStressForecastEngine`, `InvestmentTracker`, `FinancialHealthScoreV2`, `AdvancedAnalyticsEngine` | 20+ | MEDIUM |
| AI use cases | `ValidateBankStatementTransactionsUseCase`, `SuggestReceiptExtractionUseCase`, etc. | 10+ | MEDIUM |
| UI ViewModels (launch blocks) | Various | ~50 | LOW |
| Non-suspend helpers | `JsonExpenseImporter`, `ColorExtensions`, etc. | ~20 | LOW |

**Key concern:** `TransactionLifecycleCoordinator.kt` has 10 `runCatching` sites in suspend functions. These need investigation for whether CE can be swallowed. The file already has `withTransaction` wrapping and CE rethrows in catch blocks, but `runCatching` blocks can silently convert cancellation into a failed Result, which downstream code may misinterpret.

### 1.4 Static Guard Status

| Guard | Source | Status |
|-------|--------|--------|
| `CancellationSafetyArchitectureGuardTest` | `app/src/test/.../architecture/` | ✅ PASSES — 146 guards verified |
| `CancellationPropagationContractTest` | `app/src/test/.../contracts/` | ✅ PASSES — 12 entry points |
| `SourceScanningArchitectureGuardTest` | Worker auto-detection | ⚠️ 5 legacy workers flagged with `"Legacy worker — CancellationException rethrow to be added"` |
| Detekt custom rule (proposed) | Not implemented | ❌ Would catch future violations |

---

## 2. Atomicity / Event Consistency Inventory

### 2.1 Lifecycle Event DAO Inserts

All critical event DAO inserts found across the codebase:

| Category | Files | Approx. inserts | Coordinator-owned? |
|----------|-------|-------------|-------------------|
| Transaction events | `TransactionLifecycleCoordinator` (25), `DebugExpenseAuditWriter` (2), `DefaultExpenseCategoryAssignmentService` (1) | 28 | ✅ Mostly coordinator |
| Receipt events | `ReceiptLifecycleCoordinator` (8), `ReceiptSideEffectPlanner` (3), `ReceiptMatchLifecycleService` (9), `ReceiptRepository` (1) | 21 | ✅ Mostly coordinator |
| Recurring events | `RecurringLifecycleCoordinator` (11), `RecurringRuleLifecycleCoordinator` (6), `RecurringOccurrenceMaterializer` (8), `RecurringExpenseRepository` (1), `ManualRecurringExpenseRepository` (1) | 27 | ⚠️ Some in repositories |
| Group events | `GroupLifecycleCoordinator` (7) | 7 | ✅ Coordinator only |
| Operation run events | `OperationRunRecorder`, `RestoreJournalImporter` | 3 | ✅ Infrastructure |
| Notification events | `NotificationRepository` (1), `ReviewQueueRepository` (1) | 2 | ⚠️ In repositories |
| Privacy audit events | `DataRetentionWorker` (2) | 2 | ✅ Worker/guard |

**Key finding:** ~90 lifecycle event inserts exist across 15+ files. Most are in coordinators that already use `database.withTransaction`. However:

- **No static guard** blocks direct event DAO inserts from arbitrary code.
- Repository-level inserts (`ReceiptRepository`, `NotificationRepository`, `RecurringExpenseRepository`) bypass coordinator transaction boundaries.
- `RecurringOccurrenceMaterializer` injects `RecurringLifecycleEventDao` directly for event writes — this bypasses `RecurringLifecycleEventWriter`, which is the approved event writer per LEGAL_PATHS.md. This is a known architecture-law deviation to be resolved in PR 3+.

### 2.2 withTransaction Usage

`database.withTransaction` is used extensively (100+ match results). Key coordinators:

| Coordinator | Uses `withTransaction`? | State+event in same transaction? |
|-------------|------------------------|--------------------------------|
| `TransactionLifecycleCoordinator` | ✅ Yes, per-method wrapping | ✅ Generally yes, but some methods use multiple transactions |
| `ReceiptLifecycleCoordinator` | ✅ Yes | ⚠️ Some paths insert receipt, then separately insert event |
| `RecurringLifecycleCoordinator` | ✅ Yes | ⚠️ `getDueReminders()` has side-effect writes outside caller's transaction |
| `RecurringRuleLifecycleCoordinator` | ✅ Yes, CRUD methods | ✅ Each CRUD method wraps state+event |
| `GroupLifecycleCoordinator` | ✅ Yes | ✅ |
| `BankStatementLifecycleProcessor` | ✅ Yes | ⚠️ Multi-row; row-level partial failure not fully atomic |
| `ReceiptMatchLifecycleService` | ✅ Yes | ✅ Each method wraps match+event |
| `ReceiptSideEffectPlanner` | ✅ Yes | ✅ |

### 2.3 PendingReview Atomicity

`PendingReview` is used in:
- `ReceiptLifecycleCoordinator` — inserts review rows, but **not verified** as atomic with receipt save in all paths
- `BankStatementLifecycleProcessor` — inserts review for low-confidence bank rows
- `ReviewQueueRepository` — centralized review queue access
- `NotificationProcessingPipeline` — creates reviews from notifications
- `ReceiptRepository` — some review-related operations
- `ExpenseRepository` — review lookups
- `SourceLinkBackfillWorker` — review-related backfill

**Gap (MIT-041):** It is not proven that `PendingReview` insert is always in the same transaction as the receipt save/status update. A failure between receipt save and review insert would leave a receipt without required review.

### 2.4 Recurring / Reminder Atomicity

| Concern | File | Line | Status |
|---------|------|------|--------|
| `getDueReminders()` hidden write | `RecurringLifecycleCoordinator.kt` | 836–838 | ❌ `get*` method calls `recoverStaleClaimedDeliveries()` — UPDATEs DB |
| `reconcilePlannedVsActual()` hidden write | `RecurringLifecycleCoordinator.kt` | 1102–1110 | ❌ `reconcile` method materializes new occurrence rows |
| `projectOccurrences()` atomicity | `RecurringLifecycleCoordinator.kt` | ~285 | ⚠️ `database.withTransaction` used, but nested calls to materializer may not share transaction |
| `RecurringOccurrenceMaterializer` event writes | Materializer | Multiple | ⚠️ Events written inside materializer, may not share caller transaction |
| Duplicate linked actual conflict | `RecurringLifecycleCoordinator.kt` | — | ⚠️ DB constraint from MIT-033 should prevent, but mapping to typed result not verified |

### 2.5 Worker Terminal State Consistency

| Concern | Status |
|---------|--------|
| `WorkerExecutionGuard` barrier check before run log | ✅ Fixed (U-PR6) |
| CE rethrow in guard | ✅ Handled |
| Terminal Mutex + conditional UPDATE (single-write) | ⚠️ `WorkerRunLogger` uses `Mutex` + conditional SQL UPDATE (`WHERE id = :id AND status = 'RUNNING'` in `BackgroundJobRunDao.completeTerminal`). Concurrent terminal writes rely on the SQL condition for race prevention — not separately proven for all worker cancellation patterns. |
| Cancellation does not emit success/failure | ✅ Guard catches CE and returns `Cancelled` |
| Post-cancellation diagnostic safe (WorkerRunLogger) | ✅ `WorkerRunLogger` uses bounded reason codes. |
| Post-cancellation diagnostic safe (PostCommitActionRunnerImpl) | ⚠️ `PostCommitActionRunnerImpl` cancellation reasons use raw `e.message` — NOT yet bounded. See TRANSACTIONAL_EVENT_POLICY §10. |

---

## 3. Hidden Write Inventory

### 3.1 HIGH Risk — Read-named methods with hidden DB writes

| # | Method | File | Line | Hidden Write | Severity |
|---|--------|------|------|-------------|----------|
| 1 | `getDueReminders()` | `RecurringLifecycleCoordinator.kt` | 836 | Calls `recoverStaleClaimedDeliveries()` → `reminderDeliveryDao.recoverStaleClaimedDeliveries()` (UPDATE) | S1 |
| 2 | `calculateHealthScore()` | `FinancialHealthScoreV2.kt` | 82 | Calls `saveToHistory()` → INSERT/UPDATE/DELETE on `healthScoreHistoryDao` | S1 |
| 3 | `reconcilePlannedVsActual()` | `RecurringLifecycleCoordinator.kt` | 1102 | Calls `generateOccurrences()` → materializer INSERT/UPDATE `RecurringOccurrence` rows | S1 |

### 3.2 MEDIUM Risk

| # | Method | File | Line | Hidden Write | Severity |
|---|--------|------|------|-------------|----------|
| 4 | `checkAndAlert()` | `AnomalyAlertOrchestrator.kt` | 70 | INSERT `StoredAnomalyAlert` | S2 |
| 5 | `checkBudgets()` | `BudgetMonitor.kt` | 121 | INSERT diagnostic events (multiple outcomes: ATTEMPTED, COMPLETED, SKIPPED, FAILED) | S2 |
| 6 | `reconcileExpiredItems()` | `WarrantyTrackerRepository.kt` | 398 | UPDATE warranties + INSERT lifecycle event | S2 |
| 7 | `checkAndExpire()` | `RecommendationLifecycleManager.kt` | 34 | UPDATE expire recommendations | S2 |

### 3.3 Naming convention violation summary

Methods named with read-like prefixes that perform writes:
- `get*`: 1 method
- `calculate*`: 1 method
- `reconcile*`: 2 methods (debatable — "reconcile" implies sync)
- `check*`: 3 methods

---

## 4. Post-Commit Side-Effect Infrastructure

### 4.1 Existing

| Component | File | Status |
|-----------|------|--------|
| `PostCommitActionRunner` (interface) | `domain/sideeffect/PostCommitActionRunner.kt` | ✅ Exists — `run(batch) -> SideEffectBatchResult` |
| `PostCommitActionRunnerImpl` | `domain/sideeffect/` | ✅ Exists |
| `PostCommitActionBatch` | `domain/sideeffect/` | ✅ Exists |
| `SideEffectDiagnosticRecorder` | `domain/diagnostics/` | ✅ Exists |
| `ReceiptSideEffectDispatcher` | `domain/receipt/lifecycle/` | ✅ Uses `PostCommitActionRunner` |

### 4.2 Missing

| Component | Purpose | Priority |
|-----------|---------|----------|
| Side-effect outbox/ledger | Durable recording of pending/failed post-commit actions | P1 (MIT-075) |
| Retry policy for failed side effects | Configurable retry/no-retry | P2 |
| Failed side-effect queryability | UI/diagnostic visibility into stuck side effects | P2 |

---

## 5. Cross-Referenced MIT Status

| MIT | Issue | Current Status | This PR |
|-----|-------|---------------|---------|
| MIT-031 | Make state changes and lifecycle events atomic | ❌ TODO — no shared infrastructure | inventories gap |
| MIT-034 | Fix cancellation propagation everywhere | ⚠️ U-PR1 landed but ~17 gaps remain | inventories remaining gaps |
| MIT-041 | Make receipt/OCR/bank-statement review writes atomic | ❌ TODO | inventories PendingReview paths |
| MIT-043 | Fix recurring/bill reminder duplicate fulfillment and hidden writes | ❌ TODO | inventories hidden writes and projection gaps |

---

## 6. Validation Commands

Suggested for human to run after PR 1 (optional — documents only):

```bash
# Verify architecture guard still passes (no regression):
./gradlew :app:testDebugUnitTest --tests "*CancellationSafetyArchitectureGuardTest*" --console=plain
./gradlew :app:testDebugUnitTest --tests "*CancellationPropagationContractTest*" --console=plain

# Verify no test regressions:
./gradlew :app:testDebugUnitTest --console=plain
```

---

## 7. Next Steps

This baseline feeds into the full 10-PR implementation plan at `docs/analyses and debug master/universal issues implementation plan/CANCELLATION_ATOMICITY_EVENT_CONSISTENCY_IMPLEMENTATION_PLAN.md`. The immediate next PRs:

1. **PR 2** — Add CE rethrow to the 17 remaining sites + implement `CancellationSafe` helper + static guard
2. **PR 3** — Implement `DomainTransactionRunner`, `TransactionContext`, `TransactionalEventWriter`
3. **PR 4** — Fix receipt/PendingReview atomicity (MIT-041)
4. **PR 5** — Fix bank-statement receipt/review atomicity (MIT-041 continued)
5. **PR 6** — Fix recurring lifecycle atomicity (MIT-043)
6. **PR 7** — Hidden write cleanup (split query/write, explicit commands)
7. **PR 8** — Post-commit side-effect evidence (outbox/ledger)
8. **PR 9** — Legacy inconsistency repair (diagnostic queries, backfill)
9. **PR 10** — Final static guards and CI enforcement, tracker closure
