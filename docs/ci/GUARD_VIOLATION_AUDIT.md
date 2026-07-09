# Guard Violation Audit — CI Static Guardrails

Generated: 2026-07-10
Branch: `atomicity-pr21-enforcement-final`
Status: 10 blocking guards passing, 6 warning guards with pre-existing violations

---

## 1. PII Logging (G-PII-01) — 10 violations
**CI status: BLOCKING** | **Risk: HIGH** | **Must fix before merge**

### Violations

| # | File | Line | Category | Description |
|---|---|---|---|---|
| 1 | `…/ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt` | 149 | printStackTrace | `printStackTrace()` call. Stack traces may leak file paths, user data, or internal state. Use structured diagnostics instead. |
| 2 | `…/ui/screens/negotiation/BillNegotiationViewModel.kt` | 37 | printStackTrace | `printStackTrace()` call. Stack traces may leak file paths, user data, or internal state. Use structured diagnostics instead. |
| 3 | `…/ui/screens/price/PriceProtectionViewModel.kt` | 46 | printStackTrace | `printStackTrace()` call. Stack traces may leak file paths, user data, or internal state. Use structured diagnostics instead. |
| 4 | `…/ui/screens/price/PriceProtectionViewModel.kt` | 98 | printStackTrace | `printStackTrace()` call. Stack traces may leak file paths, user data, or internal state. Use structured diagnostics instead. |
| 5 | `…/ui/screens/price/PriceProtectionViewModel.kt` | 111 | printStackTrace | `printStackTrace()` call. Stack traces may leak file paths, user data, or internal state. Use structured diagnostics instead. |
| 6 | `…/domain/receipt/ReceiptOcrService.kt` | 562 | raw e.message wrapping | Exception wrapping raw `e.message` — may propagate PII from the original exception. Use structured diagnostics with safe reason codes. |
| 7 | `…/domain/receipt/ReceiptOcrService.kt` | 695 | raw e.message wrapping | Exception wrapping raw `e.message` — may propagate PII from the original exception. Use structured diagnostics with safe reason codes. |
| 8 | `…/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | 1403 | user data in exception | Exception constructed with user data field(s): `email`. Never put user PII in exception messages — use safe reason codes. |
| 9 | `…/domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt` | 171 | sensitive variable logged | Log/print statement with sensitive variable(s): `rawOcrText`. May leak PII. Use sanitized data or guard with `BuildConfig.DEBUG` (file paths only). |
| 10 | `…/data/backup/SqliteSnapshotCreator.kt` | 50 | raw e.message logging | Logging raw exception message (`e.message`). Exception messages may contain PII. Use structured diagnostics with safe reason codes. |

### Recommended fixes

| # | Fix |
|---|---|
| 1 | Replace `e.printStackTrace()` with `Timber.e(e, "structured_diagnostic_code")` or `Log.e(TAG, "reason_code", e)` (class name only, not message) |
| 2 | Replace `e.printStackTrace()` with safe logging |
| 3–5 | Replace all 3 `printStackTrace()` calls in `PriceProtectionViewModel.kt` with structured diagnostics |
| 6–7 | Replace `Exception("msg: ${e.message}")` with new exception carrying a controlled reason code, not the raw message |
| 8 | Replace `Exception("email: $email")` with `Exception("REASON_EMAIL_VALIDATION")` — never embed user PII |
| 9 | Remove `Log.d(…, rawOcrText)` entirely or guard with `if (BuildConfig.DEBUG) { … }` and truncate |
| 10 | Replace `Log.e(TAG, e.message)` with `Log.e(TAG, "BACKUP_SNAPSHOT_FAILED", e)` (class name only) |

**Estimated effort: 1–2 hours**

---

## 2. Cancellation (G-CANCEL-01/02/03) — 198 violations
**CI status: WARNING** | **Risk: LOW** (most guarded by `WorkerExecutionGuard`)

### Violation breakdown by category

| Category | Count | Description |
|---|---|---|
| G-CANCEL-01 | ~90 | Broad catch (`Exception`/`Throwable`/`RuntimeException`) in suspend function without `CancellationException` propagation |
| G-CANCEL-02 | ~102 | `runCatching` in suspend function — swallows `CancellationException` |
| G-CANCEL-03 | ~6 | `.onFailure` in suspend function without `CancellationException` check |

### Top affected files by violation count

| # | File | Violations | Primary category |
|---|---|---|---|
| 1 | `…/data/repository/DatabaseBackupRepositoryImpl.kt` | 24 | G-CANCEL-02 (runCatching) |
| 2 | `…/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` | 13 | G-CANCEL-01 + G-CANCEL-02 |
| 3 | `…/domain/debug/DiagnosticsRepository.kt` | 11 | G-CANCEL-02 (runCatching) |
| 4 | `…/domain/diagnostics/OperationRunRecorder.kt` | 9 | G-CANCEL-01 + G-CANCEL-02 + G-CANCEL-03 |
| 5 | `…/domain/sideeffect/PostCommitActionRunnerImpl.kt` | 7 | G-CANCEL-01 |
| 6 | `…/domain/health/FinancialHealthScoreV2.kt` | 7 | G-CANCEL-01 + G-CANCEL-02 |
| 7 | `…/domain/receipt/lifecycle/ReceiptAssetStore.kt` | 7 | G-CANCEL-02 + G-CANCEL-03 |
| 8 | `…/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 6 | G-CANCEL-01 + G-CANCEL-02 + G-CANCEL-03 |
| 9 | `…/domain/forecasting/FinancialStressForecastEngine.kt` | 6 | G-CANCEL-02 |
| 10 | `…/data/repository/MultiCurrencyRepository.kt` | 6 | G-CANCEL-02 |

### Recommended fixes

**Short-term (safe patterns):**
1. For G-CANCEL-01: Replace `catch (e: Exception) { … }` with:
   ```kotlin
   catch (e: CancellationException) { throw e }
   catch (e: Exception) { /* handle non-cancellation */ }
   ```
2. For G-CANCEL-02: Replace `runCatching { … }` with `CancellationSafe.runCatchingCancellable { … }` if available, or use explicit try/catch with CancellationException rethrow.
3. For G-CANCEL-03: Add `is CancellationException -> throw it` at the top of `.onFailure` blocks.

**Migration guidance:**
- Workers already guarded by `WorkerExecutionGuard` are lowest priority.
- Start with `TransactionLifecycleCoordinator.kt` (high-traffic lifecycle path).
- `DatabaseBackupRepositoryImpl.kt` has 24 isolated violations — batch-fix with a helper.
- `PostCommitActionRunnerImpl.kt` has 7 violations in side-effect fire-and-forget code — low risk but easy to fix.

**Estimated effort: Multi-sprint (fix 10–20 per sprint)**

---

## 3. DB Access (UNALLOWLISTED_CLASS / UNALLOWLISTED_CLASS_DIRECT_CHAIN / FORBIDDEN_FILE_OP / MISSING_WRITE_BARRIER) — 70 violations
**CI status: WARNING** | **Risk: MEDIUM**

### Violation types

| Type | Count | Description |
|---|---|---|
| FORBIDDEN_FILE_OP | 32 | DB file operation (`execSQL`, `openDatabase`, `getDatabasePath`) outside approved backup/restore class |
| UNALLOWLISTED_CLASS | 30 | DAO mutation (insert/update/claim) from a class not in the DB access allowlist |
| MISSING_WRITE_BARRIER | 3 | DAO mutation without `writeBarrier.checkWritesAllowed()` preceding it |
| UNALLOWLISTED_CLASS_DIRECT_CHAIN | 2 | Direct DB access chain (`database.xxxDao().yyy()`) from non-allowlisted class |
| MISSING_WRITE_BARRIER_DIRECT_CHAIN | 2 | Direct chain access without write barrier |

### Files that call DAOs directly

| File | Violations | DAOs accessed |
|---|---|---|
| `…/data/backup/RestoreJournalImporter.kt` | 5 | `operationRunDao.insert`, `operationRunEventDao.insert` |
| `…/data/database/DatabaseMigrations.kt` | 19 | `database.execSQL` (FORBIDDEN_FILE_OP — but migrations are expected to use raw SQL) |
| `…/data/rescue/FinancialRescueCoordinator.kt` | 12 | `getDatabasePath`, `openDatabase`, `db.execSQL` |
| `…/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt` | 12 | `scannedReceiptDao.update`, `receiptEventDao.insert` |
| `…/domain/receipt/lifecycle/ReceiptSideEffectPlanner.kt` | 4 | `scannedReceiptDao.update`, `receiptEventDao.insert` |
| `…/domain/diagnostics/OperationRunRecorder.kt` | 3 | `runDao.insert`, `eventDao.insert` |
| `…/domain/provenance/SourceLinkBackfillWorker.kt` | 3 | `sourceLinkDao.insert` |
| `…/domain/transaction/lifecycle/DebugExpenseAuditWriter.kt` | 2 | `transactionEventDao.insert` |
| `…/domain/negotiation/SmartBillNegotiationEngine.kt` | 2 | `negotiationOutcomeDao().insert`, `priceHistoryDao.insert` |
| `…/domain/bank/BankApiIntegration.kt` | 2 | `bankConnectionDao.insert`, `pendingReviewDao.insert` |
| `…/data/repository/ManualRecurringExpenseRepository.kt` | 1 | `lifecycleEventDao.insert` (MISSING_WRITE_BARRIER) |
| `…/data/repository/ReceiptInsertResolver.kt` | 1 | `scannedReceiptDao.insert` |
| `…/data/repository/WarrantyTrackerRepository.kt` | 1 | `warrantyLifecycleEventDao().insert` (MISSING_WRITE_BARRIER) |
| `…/domain/transaction/DefaultExpenseCategoryAssignmentService.kt` | 1 | `transactionEventDao.insert` |
| `…/domain/provenance/SourceLinkWriterImpl.kt` | 1 | `sourceLinkDao.insert` |
| `…/service/warranty/WarrantyExpirationWorker.kt` | 1 | `deliveryDao.claim` |

### Recommended fixes

| Priority | File | Fix |
|---|---|---|
| HIGH | `DatabaseMigrations.kt` | This is expected — migrations must use raw SQL. Add to allowlist with reason: `"Room migration class — raw SQL required for schema evolution"` |
| HIGH | `FinancialRescueCoordinator.kt` | This is a repair/rescue utility. Add to allowlist with owner: `"Low-level DB rescue — must bypass normal paths for recovery"` |
| MEDIUM | `RestoreJournalImporter.kt` | Route through lifecycle event writer or add to allowlist with owner |
| MEDIUM | `ReceiptMatchLifecycleService.kt` | This is already a lifecycle service — add to allowlist or inject an approved event writer |
| MEDIUM | `ReceiptSideEffectPlanner.kt` | Same as above — lifecycle service, add to allowlist |
| MEDIUM | `OperationRunRecorder.kt` | Add `writeBarrier.checkWritesAllowed()` before DAO inserts or add to allowlist |
| LOW | `SmartBillNegotiationEngine.kt` | Route through `NegotiationLifecycleCoordinator` or add to allowlist |
| LOW | `SourceLinkBackfillWorker.kt` | Worker already has write barrier from `WorkerExecutionGuard` — add to allowlist |
| LOW | `BankApiIntegration.kt` | Route through bank connection lifecycle or add to allowlist |

**Estimated effort: 2–3 days**

---

## 4. Event Writers — 45 violations
**CI status: WARNING** | **Risk: LOW** (observability consistency)

### Violation types

| Type | Count | Description |
|---|---|---|
| ENTITY | 25 | Direct entity construction (e.g., `ReceiptEvent(…)`, `TransactionEvent(…)`, `OperationRun(…)`) outside approved event writers |
| DAO | 20 | Direct DAO insert (`receiptEventDao.insert`, `transactionEventDao.insert`, `operationRunDao.insert`) outside approved writers |

### Files with violations

| File | Entity violations | DAO violations |
|---|---|---|
| `…/data/backup/RestoreJournalImporter.kt` | 5 (OperationRun, OperationRunEvent) | 5 (operationRunDao, operationRunEventDao) |
| `…/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt` | 9 (ReceiptEvent) | 9 (receiptEventDao) |
| `…/domain/receipt/lifecycle/ReceiptSideEffectPlanner.kt` | 3 (ReceiptEvent) | 3 (receiptEventDao) |
| `…/domain/transaction/lifecycle/DebugExpenseAuditWriter.kt` | 2 (TransactionEvent) | 2 (transactionEventDao) |
| `…/domain/transaction/DefaultExpenseCategoryAssignmentService.kt` | 1 (TransactionEvent) | 1 (transactionEventDao) |
| `…/data/repository/NotificationRepository.kt` | 1 (TransactionEvent) | 0 |
| `…/data/repository/ReceiptRepository.kt` | 1 (ReceiptEvent) | 1 (receiptEventDao) |
| `…/data/repository/ReviewQueueRepository.kt` | 1 (TransactionEvent) | 1 (transactionEventDao) |

### Recommended fixes

Route event construction through approved event writer classes:

- `ReceiptEvent` → use `ReceiptEventWriter` (or add `ReceiptMatchLifecycleService` and `ReceiptSideEffectPlanner` to the event writer allowlist since they are lifecycle services)
- `TransactionEvent` → use `TransactionEventWriter` (or add `DebugExpenseAuditWriter` and `DefaultExpenseCategoryAssignmentService` to allowlist)
- `OperationRun` / `OperationRunEvent` → use `OperationRunRecorder` / `DiagnosticEventWriter`
- `RestoreJournalImporter` → import path has special needs; allowlist with reason or inject `DiagnosticEventWriter`

**Estimated effort: 1 day**

---

## 5. Privacy — 1 pre-existing violation
**CI status: WARNING** | **Risk: MEDIUM**

### Violation

| # | File | Line | Description |
|---|---|---|---|
| 1 | `…/domain/transaction/TransactionContext.kt` | 96 | `String.hashCode()` used for sensitive ID (`transactionId.hashCode()`) — must use `SensitiveHashingService.hmacSha256Prefix` |

```kotlin
// Violation at line 96:
result = 31 * result + transactionId.hashCode()
```

### Recommended fix

Replace `hashCode()` with `SensitiveHashingService.hmacSha256Prefix(transactionId)` for sensitive transaction IDs used in contexts. `String.hashCode()` is not cryptographically secure and may leak information about the original value.

**Estimated effort: 1 hour**

---

## 6. Money — 2 pre-existing violations
**CI status: WARNING** | **Risk: MEDIUM**

### Violations

| # | File | Line | Rule | Description |
|---|---|---|---|---|
| 1 | `…/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 611 | G-MONEY-15 | Dashboard widget must not use raw `ctx.totalBudgetAmount` — use normalized input |
| 2 | `…/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 612 | G-MONEY-15 | Dashboard widget must not use raw `ctx.totalBudgetAmount` — use normalized input |

```kotlin
// L611-612:
val totalRemaining = if (ctx.totalBudgetAmount > 0) {
    (ctx.totalBudgetAmount - ctx.monthSpent).coerceAtLeast(0.0)
```

### Recommended fix

Use `MultiCurrencyRepository` to normalize the budget amount before this computation, or route through `BudgetNormalizer` to ensure currency-safe subtraction. Raw `Double` arithmetic with budget amounts can produce rounding errors.

**Estimated effort: 1–2 hours**

---

## Summary

| Guard | Violations | CI | Priority | Estimated Effort |
|---|---|---|---|---|
| PII Logging (G-PII-01) | 10 | **BLOCKING** | **HIGH** | 1–2 hours |
| Cancellation (G-CANCEL-01/02/03) | 198 | WARNING | LOW | Multi-sprint |
| DB Access (UNALLOWLISTED_CLASS, FORBIDDEN_FILE_OP, MISSING_WRITE_BARRIER) | 70 | WARNING | MEDIUM | 2–3 days |
| Event Writers | 45 | WARNING | LOW | 1 day |
| Privacy (G5) | 1 | WARNING | MEDIUM | 1 hour |
| Money (G-MONEY-15) | 2 | WARNING | MEDIUM | 1–2 hours |

**Total violations: 326**
**Blocking: 10 (PII Logging)**

---

## How to use this document

1. **Start with PII logging** — smallest (10 violations), highest risk, CI-blocking. Must fix before landing any PR.
2. **Pick 1–2 files per sprint** for the remaining guards.
3. **After fixing**, run the guard locally to verify:
   ```bash
   python scripts/verify_pii_logging_boundaries.py --fail-on-violation
   python scripts/verify_db_access_boundaries.py --fail-on-violation
   python scripts/verify_event_writers.py --fail-on-violation
   # etc.
   ```
4. **Remove the guard's `|| true` suffix in CI** when the backlog is clear for that guard.
5. **Update this document** after each burn-down session to track progress.

### Quick local validation commands

```bash
# PII Logging (BLOCKING)
python scripts/verify_pii_logging_boundaries.py --fail-on-violation

# DB Access
python scripts/verify_db_access_boundaries.py --fail-on-violation

# Event Writers
python scripts/verify_event_writers.py --fail-on-violation

# Privacy
python scripts/verify_privacy_boundaries.py --root .

# Money
python scripts/verify_money_boundaries.py --root .

# Cancellation
python scripts/verify_cancellation_boundaries.py --fail-on-violation
```
