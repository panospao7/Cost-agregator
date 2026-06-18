# Pipeline 1–5 Consolidated Debug Handoff

> **Generated:** 2026-05-31  
> **Baseline commit:** `092b264bd54ee47629f325ba40934a0fc12f6c7c`  
> **Local HEAD verified against:** current working tree  
> **Method:** Source doc fetch → local code trace → issue reconciliation

---

## Executive Summary

| Pipeline | Report Verdict | Current Local State | Key Issues Fixed | Remaining |
|----------|---------------|--------------------|-----------------:|----------:|
| 1 — Notification Capture | NOT CLEAN | **MOSTLY CLEAN** | 4/5 critical | 1 partial |
| 2 — Transaction Lifecycle | MOSTLY_FIXED_BUT_NOT_CLEAN | **MOSTLY CLEAN** | 4/5 critical | 1 partial |
| 3 — Receipt/OCR/Email | NOT_CLEAN | **MOSTLY CLEAN** | 4/5 critical | 1 partial |
| 4 — Recurring/Bill Reminders | NOT CLEAN (eval) | **CLEAN** | 5/5 critical | 0 |
| 5 — Currency/Dashboard/Analytics | IMPROVED_BUT_NOT_CLEAN | **IMPROVED** | 4/7 critical | 3 remaining |

**Overall: The codebase has advanced significantly beyond the debug reports.** Pipeline 4 is fully resolved. Pipelines 1–3 each have one remaining partial issue. Pipeline 5 has the most remaining work (recurring forecast normalization, block party actuals, budget dashboard wiring).

---

## Pipeline 1 — Notification Capture

### Source Map
- **Segment:** 3 (Notification Capture, Parsing & Review)
- **Entry point:** `NotificationCaptureService.onNotificationPosted()`
- **Coordinator:** `NotificationProcessingPipeline` → `NotificationRepository`
- **Key entities:** `RawNotification`, `PipelineDiagnosticEvent`, `PendingReview`

### Issue Reconciliation Table

| Report Issue | Severity | Report Status | Local Code Status | Evidence |
|---|---|---|---|---|
| P1-CURRENT-001: Live filter ignores combinedBody | P1_HIGH | OPEN | ✅ **FIXED** | `NotificationFilter.decide()` receives `parts.combinedBody` as bigText param |
| P1-CURRENT-002: DO_NOT_STORE falls through | P0_PRIVACY | OPEN | ✅ **FIXED** | Separate `processingNotification`/`storageNotification`; DO_NOT_STORE nulls storage fields |
| P1-CURRENT-003: STORE_REDACTED breaks parsing | P1_HIGH | OPEN | ✅ **FIXED** | Parser receives ephemeral `processingNotification`; DB gets `storageNotification` |
| P1-CURRENT-006: Service drops no diagnostics | P1_HIGH | OPEN | ✅ **FIXED** | All drop paths emit via `notificationDiagnosticEmitter` |
| P1-CURRENT-007: Shutdown durability | P1_HIGH | PARTIAL | ⚠️ **PARTIAL** | `NonCancellable` protects intake insert; pre-insert work can still be lost |

### Remaining Work
1. **Shutdown durability (P1-CURRENT-007):** Full fix requires durable intake table or app-scope handoff. Current mitigation (NonCancellable around critical insert) is acceptable for most scenarios but process kill before the NonCancellable block can lose a notification.

### Tests That Prove Behavior
- Filter uses combinedBody: verified via `NotificationFilter.decide()` signature
- Privacy modes: separate processing/storage notification pattern
- Diagnostics: `notificationDiagnosticEmitter.emit()` at every exit path

### Missing Tests
- `service_cancel_after_receive_does_not_lose_raw_intake`
- `unprocessed_raw_notification_resumes_after_restart`

---

## Pipeline 2 — Transaction Lifecycle

### Source Map
- **Segment:** 9 (Core Expense Management)
- **Entry point:** `TransactionLifecycleCoordinator.createExpense()` / `updateExpense()` / `deleteExpense()`
- **Guard:** `@RestrictedExpenseDaoMutation` annotation + CI architecture test
- **Key entities:** `Expense`, `TransactionEvent`

### Issue Reconciliation Table

| Report Issue | Severity | Report Status | Local Code Status | Evidence |
|---|---|---|---|---|
| P2-CURRENT-001: Raw DAO mutation unguarded | P1_HIGH | OPEN | ✅ **FIXED** | `@RestrictedExpenseDaoMutation` + `ExpenseDaoMutationAccessTest` CI guard |
| P2-CURRENT-002: Deprecated createExpense footgun | P1_HIGH | OPEN | ⚠️ **MITIGATED** | Deprecated with annotations; not removed |
| P2-CURRENT-003: deleteExpense bypasses coordinator | P1_HIGH | OPEN | ✅ **FIXED** | `ExpenseRepository.deleteExpense()` delegates to coordinator |
| P2-CURRENT-007: Uses restoreMaintenanceMode directly | P1_HIGH | OPEN | ✅ **FIXED** | Uses `DatabaseWriteBarrier.checkWritesAllowed()` |
| P2-CURRENT-009: Unit test stale/broken | P1_HIGH | OPEN | ⚠️ **PARTIAL** | Test functional but has stale `RestoreMaintenanceMode` mock import |

### Remaining Work
1. **Deprecated createExpense methods:** Still exist in `ReceiptRepository` with `@Deprecated` annotations. Should be removed or elevated to `DeprecationLevel.ERROR`.
2. **Test cleanup:** Remove stale `RestoreMaintenanceMode` mock import from `TransactionLifecycleCoordinatorTest`.

### Tests That Prove Behavior
- `ExpenseDaoMutationAccessTest` — CI guard against direct DAO mutations
- Coordinator test exists with proper `DatabaseWriteBarrier` mocking

### Missing Tests
- `TransactionLifecycleDbContractTest` (Room-backed, not mock-only)
- `NestedTransactionPostCommitTest`

---

## Pipeline 3 — Receipt Capture / OCR / Email

### Source Map
- **Segment:** 4 (Receipt Scanning & Lifecycle), 38 (Receipt Matching)
- **Entry point:** `ReceiptLifecycleCoordinator.processReceiptInput()`
- **Link owner:** `ReceiptLinkService`
- **Key entities:** `ScannedReceipt`, `ReceiptEvent`, `ReceiptExpenseLink`

### Issue Reconciliation Table

| Report Issue | Severity | Report Status | Local Code Status | Evidence |
|---|---|---|---|---|
| P3-CURRENT-001: Bank statement raw OCR no privacy | P0_PRIVACY | OPEN | ✅ **FIXED** | `BankStatementLifecycleProcessor` calls `RawContentSanitizer.sanitizeRawOcr()` |
| P3-CURRENT-002: Email subject leaks to Expense.notes | P0_PRIVACY | OPEN | ✅ **FIXED** | Uses sanitized subject; expense notes use generic "Email receipt from {provider}" |
| P3-CURRENT-003: Sanitized OCR used for fingerprinting | P1_HIGH | OPEN | ✅ **FIXED** | `textFingerprint` computed from `processResult.ephemeralRawOcrText` before sanitization |
| P3-CURRENT-005: Receipt insert not atomic | P1_HIGH | OPEN | ✅ **FIXED** | `database.withTransaction` wraps insert + event + review |
| P3-CURRENT-008: No unique fingerprint constraints | P1_HIGH | OPEN | ⚠️ **PARTIAL** | App-layer dedup via `ReceiptDuplicateDetector`; no DB unique index on fingerprints |

### Remaining Work
1. **Fingerprint uniqueness (P3-CURRENT-008):** Application-layer dedup is comprehensive (hash, text, semantic, external ID) but concurrent identical imports can theoretically race. Adding partial unique indexes on `(imageHash)` and/or a `ReceiptFingerprint` claim table would harden this.

### Tests That Prove Behavior
- Privacy: `RawContentSanitizer` used across all paths (camera, email, bank statement)
- Atomicity: `database.withTransaction` in coordinator
- Dedup: Multi-signal `ReceiptDuplicateDetector` with short-circuit

### Missing Tests
- `parallel_identical_receipt_import_creates_one_canonical_receipt`
- `fingerprint_claim_conflict_returns_existing_receipt_id`

---

## Pipeline 4 — Recurring / Bill Reminders

### Source Map
- **Segment:** 7 (Recurring Expenses), 36 (Bill Reminders)
- **Entry points:** `RecurringRuleLifecycleCoordinator` (rule CRUD), `RecurringLifecycleCoordinator` (occurrence/link/reminder)
- **Worker:** `BillReminderWorker`
- **Key entities:** `RecurringOccurrence`, `RecurringReminderDelivery`, `RecurringLifecycleEvent`, `PlannedExpense`

### Issue Reconciliation Table

| Report Issue | Severity | Report Status | Local Code Status | Evidence |
|---|---|---|---|---|
| Unlink path incomplete | CRITICAL | OPEN | ✅ **FIXED** | `unlinkExpenseFromOccurrenceDetailed()` reopens planned + regenerates reminders |
| PAID downgrade by materializer | CRITICAL | OPEN | ✅ **FIXED** | Materializer checks `terminalDbValues` before update |
| No lifecycle event on materializer | HIGH | OPEN | ✅ **FIXED** | Writes `OCCURRENCE_STATUS_CHANGED`, `OCCURRENCE_GENERATED` events |
| Snooze/dismiss bypass coordinator | HIGH | OPEN | ✅ **FIXED** | Coordinator has `dismissReminderDelivery()` / `snoozeReminderDelivery()` |
| No single rule lifecycle owner | CRITICAL | OPEN | ✅ **FIXED** | `RecurringRuleLifecycleCoordinator` owns create/update/delete/activate/deactivate |

### Additional Improvements Found
- Stale claim recovery: `recoverStaleClaimedDeliveries()` resets CLAIMED > 5min back to SCHEDULED
- Transition policy: `RecurringOccurrenceTransitionPolicy` enforces valid state transitions
- Quiet hours + runtime toggle in worker
- Bulk reconciliation: `reconcileAllLinkedExpensesAfterBulkUpdate()`

### Remaining Work
**None critical.** Pipeline 4 is the cleanest of all 5 pipelines.

### Tests That Prove Behavior
- Coordinator test exists (mock-based)
- Materializer terminal status guard
- Atomic rule CRUD via `database.withTransaction`

### Missing Tests (nice-to-have)
- `RecurringLifecycleDbContractTest` (Room-backed)
- `OccurrenceKeyCompatibilityTest` (migration safety)

---

## Pipeline 5 — Currency / Dashboard / Analytics

### Source Map
- **Segment:** 16 (Currency & Exchange), 10 (Dashboard Totals), 8 (Analytics & Insights)
- **Entry points:** `MultiCurrencyRepository`, `AnalyticsCurrencyNormalizer`, `ComputeDashboardWidgetsUseCase`
- **Key entities:** `ExchangeRate`, `Expense` (currency fields), `MoneyAggregate`

### Issue Reconciliation Table

| Report Issue | Severity | Report Status | Local Code Status | Evidence |
|---|---|---|---|---|
| P5-CURRENT-001: Historical uses midpoint | P0_FINANCIAL | OPEN | ✅ **RESOLVED** | Uses `MoneyNormalizationEngine` with `RateBasis.TRANSACTION_DATE` |
| P5-CURRENT-003: getRate orders by lastUpdated | P1_HIGH | OPEN | ✅ **RESOLVED** | `getLatestRateForPair()` uses `validDate DESC, lastUpdated DESC` |
| P5-CURRENT-005: Weekly/daily use latest-rate | P1_HIGH | OPEN | ✅ **RESOLVED** | All period totals use historical aggregation APIs |
| P5-CURRENT-008: Spending trend raw-sums | P1_HIGH | OPEN | ✅ **RESOLVED** | Uses `DashboardNormalizedInput` exclusively |
| P5-CURRENT-009: Block Party raw expenses | P1_HIGH | OPEN | ⚠️ **PARTIAL** | dailyHistory normalized; raw expenses still passed to SynthesisEngine |
| P5-CURRENT-010: Recurring patterns not normalized | P1_HIGH | OPEN | ❌ **OPEN** | Explicit TODO P2-20; patterns summed raw without FX |
| P5-CURRENT-011: Budget spent/limit different bases | P1_HIGH | OPEN | ⚠️ **PARTIAL** | AsOf API exists; dashboard not wired yet |

### Remaining Work
1. **P5-CURRENT-009 (Block Party):** `SynthesisEngine.calculateBlockPartyData()` still sums `expensesByDay[day]?.sumOf { it.effectiveAmount }` from raw expenses. Fix: pass normalized `ExpenseSnapshot` list or remove the raw expense path.
2. **P5-CURRENT-010 (Recurring forecast normalization):** `ForecastInputAssembler.mergeRecurringPatterns()` has explicit TODO. `SynthesisEngine` sums `recurringPatterns.averageAmount` and `confirmedOccurrences.expectedAmount` without currency conversion. Fix: normalize in `ForecastInputAssembler` before passing to engine.
3. **P5-CURRENT-011 (Budget dashboard wiring):** `getHomeCurrencyPurchaseTotalAsOf()` with `RateBasis.PERIOD_END` exists but `ComputeDashboardWidgetsUseCase` still uses `ctx.totalBudgetAmount` from legacy path. SafeToSpend shows `isUnavailable = true`.

### Tests That Prove Behavior
- `MoneyNormalizationEngine` with `RateBasis.TRANSACTION_DATE`
- `ExchangeRateDao.getLatestRateForPair()` ordering
- `DashboardNormalizedInput` canonical input for widgets

### Missing Tests
- `block_party_actuals_use_normalized_expenses`
- `forecast_recurring_pattern_amounts_are_home_currency`
- `budget_status_snapshot_preserves_currency_quality`

---

## Cross-Pipeline Dependency Verification

| Dependency | Status | Evidence |
|---|---|---|
| P1 → P2: Notification auto-accept uses coordinator | ✅ | `TransactionLifecycleCoordinator.createExpense()` with `SideEffectMode.DEFER` |
| P2 → P4: Expense CUD triggers recurring reconcile | ✅ | Side-effect planner dispatches recurring link/unlink |
| P2 → P3: Receipt-created expenses atomic | ✅ | `database.withTransaction` + `SideEffectMode.DEFER` |
| P3 → P2: Receipt convenience paths use coordinator | ✅ | Legacy paths deprecated; coordinator is single owner |
| P4 → P5: Recurring planned amounts in forecast | ⚠️ | Planned expenses normalized; recurring patterns NOT normalized |
| P5 ← all: Dashboard reflects upstream quality | ⚠️ | Most paths normalized; block party and budget still partial |

---

## Universal Contracts Status (Pipelines 1–5 scope)

| Contract | Status | Notes |
|---|---|---|
| U1: DatabaseWriteBarrier | ✅ | All coordinators use `writeBarrier.checkWritesAllowed()` |
| U2: WorkerExecutionGuard | ✅ | BillReminderWorker uses guard + checkpoint |
| U3: RawStorageMode/Sanitizer | ✅ | All 3 pipelines (1,3,11) use ephemeral processing + sanitized storage |
| U4: Money/currency quality | ⚠️ | Core primitives done; forecast/budget dashboard wiring incomplete |
| U5: TransactionLifecycleCoordinator | ✅ | Single owner enforced via `@RestrictedExpenseDaoMutation` |
| U6: ReceiptLifecycleCoordinator | ✅ | Single owner; legacy paths deprecated |
| U7: RecurringLifecycleCoordinator | ✅ | Full state machine with transition policy |
| U8: PipelineDiagnosticEvent | ✅ | All pipelines write diagnostic events |
| U10: DAO conflict/timestamps | ✅ | `createdAt` propagated; conflict resolution typed |

---

## Recommended Next Actions (Priority Order)

### P0 — Must Fix
1. **P5-CURRENT-010:** Normalize recurring pattern amounts in `ForecastInputAssembler` before `SynthesisEngine` sums them. This causes incorrect financial runway projections for multi-currency users.

### P1 — Should Fix
2. **P5-CURRENT-009:** Pass normalized expenses (not raw `TransactionSummary`) to `SynthesisEngine.calculateBlockPartyData()`.
3. **P5-CURRENT-011:** Wire `ComputeDashboardWidgetsUseCase` budget widgets to use the existing `getHomeCurrencyPurchaseTotalAsOf()` API.
4. **P3-CURRENT-008:** Add partial unique index on `ScannedReceipt.imageHash` to prevent concurrent duplicate races at DB level.

### P2 — Nice to Have
5. **P1-CURRENT-007:** Implement durable intake table for full shutdown safety.
6. **P2-CURRENT-002:** Remove deprecated `createExpense` methods or elevate to `DeprecationLevel.ERROR`.
7. Add Room-backed DB contract tests for Pipelines 2 and 4.

---

## Validation Commands

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Run architecture guard tests specifically
./gradlew testDebugUnitTest --tests "*ArchitectureGuard*"
./gradlew testDebugUnitTest --tests "*ExpenseDaoMutationAccessTest*"

# Run currency guardrails
powershell -File scripts/currency_guardrails.ps1

# Run DB access boundary verification
python scripts/verify_db_access_boundaries.py

# Run privacy boundary verification
python scripts/verify_privacy_boundaries.py

# Run money boundary verification
python scripts/verify_money_boundaries.py

# Run event writer verification
python scripts/verify_event_writers.py
```

---

## Source Documents Used

| Document | URL |
|---|---|
| Master Tracker | `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md` |
| Codebase Segments | `docs/architecture/CODEBASE_SEGMENTS.md` |
| Pipeline 1 Report | `pipeline_1_notification_debug_report.yaml` |
| Pipeline 2 Report | `pipeline_2_transaction_lifecycle_debug_report.yaml` |
| Pipeline 3 Report | `pipeline_3_receipt_ocr_email_debug_report.yaml` |
| Pipeline 4 Evaluation | `pipeline4_evaluation.md` |
| Pipeline 4 Plan | `pipeline4_implementation_plan.md` |
| Pipeline 5 Report | `pipeline_5_currency_dashboard_analytics_debug_report.yaml` |
