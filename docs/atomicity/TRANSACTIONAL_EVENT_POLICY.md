# Transactional Event Consistency Policy

Last updated: 2026-07-01  
PR: PR 1 — Baseline and Policies  
MIT: MIT-031, MIT-041, MIT-043  
Status: **APPROVED — awaiting implementation in PR 3+**

---

## 1. Purpose

Define the contract for writing database state and lifecycle/audit events atomically. Ensure that state changes and their corresponding events never diverge — i.e., never commit one without the other when they represent the same logical mutation.

---

## 2. Non-Negotiable Rule

> **Any domain operation that changes important persistent state AND emits a lifecycle event MUST execute both writes in a single database transaction (`database.withTransaction`). If either write fails, the entire transaction rolls back.**

---

## 3. Critical Event Domains

These domains have state+event pairs that must be atomic:

| Domain | State Entity | Event Entity | Coordinator |
|--------|-------------|--------------|-------------|
| Transaction | `Expense` | `TransactionEvent` | `TransactionLifecycleCoordinator` |
| Receipt | `ScannedReceipt` | `ReceiptEvent` | `ReceiptLifecycleCoordinator` |
| Receipt match | `ScannedReceipt` (link) | `ReceiptEvent` | `ReceiptMatchLifecycleService` |
| Pending review | `PendingReview` | `TransactionEvent` / diagnostic | `ReceiptLifecycleCoordinator` |
| Bank statement | `ScannedReceipt` + `PendingReview` | `ReceiptEvent` | `BankStatementLifecycleProcessor` |
| Recurring rule | `RecurringRule` | `RecurringLifecycleEvent` | `RecurringRuleLifecycleCoordinator` |
| Recurring occurrence | `RecurringOccurrence` | `RecurringLifecycleEvent` | `RecurringLifecycleCoordinator` |
| Recurring reminder | `RecurringReminderDelivery` | `RecurringLifecycleEvent` | `RecurringLifecycleCoordinator` |
| Recurring projection | Occurrence + reminder + planned rows | `RecurringLifecycleEvent` | `RecurringLifecycleCoordinator` |
| Group | Group tables | `LifecycleEvent` | `GroupLifecycleCoordinator` |
| Worker terminal | `BackgroundJobRun` | `OperationRunEvent` | `WorkerRunLogger` |
| Operation run | `OperationRun` | `OperationRunEvent` | `OperationRunRecorder` |

---

## 4. Allowed Patterns

### 4.1 Pattern A — Single coordinator, single transaction (PREFERRED)

```kotlin
suspend fun approveReceipt(receiptId: Long): Result<Unit> {
    return database.withTransaction {
        val receipt = scannedReceiptDao.getById(receiptId)
            ?: return@withTransaction Result.failure(NotFound)

        // State update
        scannedReceiptDao.updateStatus(receiptId, "APPROVED")

        // Event insert — in same transaction
        receiptEventDao.insert(ReceiptEvent(
            receiptId = receiptId,
            eventType = "RECEIPT_APPROVED",
            timestamp = timeProvider.now()
        ))

        Result.success(Unit)
    }
}
```

### 4.2 Pattern B — Multi-entity, single transaction

```kotlin
suspend fun processLowConfidenceBankRow(row: BankImportRow): Result<Long> {
    // Validation BEFORE transaction
    val validated = validateAmount(row) ?: return Result.failure(InvalidAmount)

    return database.withTransaction {
        // Insert receipt
        val receiptId = scannedReceiptDao.insert(receipt)

        // Insert required review — same transaction
        val reviewId = pendingReviewDao.insert(PendingReview(
            receiptId = receiptId,
            source = "BANK_STATEMENT",
            ...
        ))

        // Insert event
        receiptEventDao.insert(ReceiptEvent(
            receiptId = receiptId,
            eventType = "BANK_RECEIPT_IMPORTED",
            ...
        ))

        Result.success(receiptId)
    }
}
```

### 4.3 Pattern C — Nested transaction context

When an operation must be atomic with a caller's larger transaction:

```kotlin
// Caller opens transaction
suspend fun processBatch(rows: List<BankImportRow>): Result<Int> {
    return database.withTransaction {
        var count = 0
        for (row in rows) {
            // materializeInCurrentTransaction shares the caller's transaction
            when (val result = bankProcessor.processRowInCurrentTransaction(row)) {
                is Success -> count++
                is RowFailure -> recordRowDiagnostic(row, result.reason)
            }
        }
        Result.success(count)
    }
}
```

### 4.4 Pattern D — Post-commit side effects

Side effects that must NOT run inside the DB transaction:

```kotlin
suspend fun createExpense(data: ExpenseData): Result<Long> {
    val (expenseId, sideEffectBatch) = database.withTransaction {
        val id = expenseDao.insert(expense)
        val event = transactionEventDao.insert(event)
        val batch = sideEffectPlanner.plan(expense, event)
        Triple(id, event, batch)
    }

    // After commit — side effects do not roll back primary state
    postCommitActionRunner.run(sideEffectBatch)

    return Result.success(expenseId)
}
```

Examples of post-commit side effects:
- Notification scheduling
- Dashboard/budget cache refresh
- Cloud/network calls
- Worker reschedule
- Analytics update
- Source-link promotion

---

## 5. Forbidden Patterns

### 5.1 ❌ Split state+event writes (two transactions)

```kotlin
// FORBIDDEN:
suspend fun approveReceipt(receiptId: Long) {
    scannedReceiptDao.updateStatus(receiptId, "APPROVED")  // Transaction 1

    // CRASH HERE: receipt is APPROVED but no event exists

    receiptEventDao.insert(ReceiptEvent(...))  // Transaction 2 — never runs!
}
```

### 5.2 ❌ Direct event DAO insert outside coordinator

```kotlin
// FORBIDDEN from arbitrary code:
class SomeViewModel {
    suspend fun doSomething() {
        // Bypassing the coordinator — no transaction wrapping, no state atomicity
        transactionEventDao.insert(TransactionEvent(...))
    }
}
```

### 5.3 ❌ State update without event

```kotlin
// FORBIDDEN for critical state changes:
suspend fun markOccurrenceComplete(occurrenceId: Long) {
    // State updated, but no RecurringLifecycleEvent emitted
    recurringOccurrenceDao.updateStatus(occurrenceId, "COMPLETED")
}
```

### 5.4 ❌ Partial commit of multi-row operations

```kotlin
// FORBIDDEN — individual row commits make partial-failure unrecoverable:
suspend fun importRows(rows: List<Row>) {
    for (row in rows) {
        database.withTransaction {  // Each row in its OWN transaction!
            importRow(row)  // Some rows committed, others failed — inconsistent
        }
    }
}
```

Allowed alternative: use a single transaction for batch, with row-level failure recording inside the transaction.

### 5.5 ❌ Hidden write in read-named method

```kotlin
// FORBIDDEN:
suspend fun getDueReminders(): List<Reminder> {
    recoverStaleClaimedDeliveries()  // Hidden UPDATE!
    return reminderDao.getScheduled()
}
```

Must be split into:
- `getDueReminders()` — read-only query
- `recoverStaleClaimedDeliveries()` — explicit write command (transactional, evented)

---

## 6. Coordinator Ownership Rules

### 6.1 Approved write owners

Only these coordinators may write to their respective state+event pairs:

| Domain | Write Owner | Auxiliary Writers |
|--------|-------------|-------------------|
| Expense CRUD | `TransactionLifecycleCoordinator` | `GroupTransactionCoordinator` (group expenses only) |
| Receipt lifecycle | `ReceiptLifecycleCoordinator` | `ReceiptMatchLifecycleService` (matches only), `BankStatementLifecycleProcessor` (bank imports only) |
| Recurring rules | `RecurringRuleLifecycleCoordinator` | — |
| Recurring lifecycle | `RecurringLifecycleCoordinator` | `RecurringOccurrenceMaterializer` — use `materializeInCurrentTransaction()` (shares caller's transaction) as the approved coordinator-call path. ⚠️ **Known LEGAL_PATHS deviation:** Materializer injects `RecurringLifecycleEventDao` directly rather than using `RecurringLifecycleEventWriter`. To be resolved in PR 3+. |
| Groups | `GroupLifecycleCoordinator` | — |
| Worker runs | `WorkerExecutionGuard` + `WorkerRunLogger` | — |
| Operation runs | `OperationRunRecorder` | — |

### 6.2 Event DAO visibility

After PR 3+: Critical lifecycle event DAO `insert`/`update`/`delete` methods should be made `internal` or guarded so they cannot be called from outside approved coordinator packages.

---

## 7. Static Enforcement

### 7.1 Planned Guards (PR 3+)

| Guard | What it blocks |
|-------|---------------|
| Direct event DAO insert guard | `transactionEventDao.insert()`, `receiptEventDao.insert()`, `lifecycleEventDao.insert()` from non-coordinator code |
| State/event split write guard | State update without event insert in same `withTransaction` |
| Hidden write guard | DAO writes inside methods named `get*`, `load*`, `observe*`, `find*`, `query*`, `calculate*`, `report*` |
| Transaction-required mutation guard | Critical state mutations outside `database.withTransaction` |

### 7.2 Existing Scripts

```bash
python scripts/verify_db_access_boundaries.py
python scripts/verify_event_writers.py
```

---

## 8. Fault-Injection Test Requirements

For each critical coordinator, tests must prove:

1. **Exception after state insert, before event** — transaction rolls back, no partial state.
2. **Exception after event insert, before state** — transaction rolls back, no orphan event.
3. **Cancellation during transaction** — transaction rolls back, CE propagated.
4. **Required review insert failure** — receipt save rolls back.
5. **Recurring projection partial failure** — all generated rows roll back.
6. **Worker terminal double-write** — only one terminal state committed.
7. **Privacy: no raw PII in event fields after fault** — persisted event `errorDetails` and `message` fields contain no raw exception text, file paths, or PII. Only bounded reason codes or `SafeEventMetadata` output.

---

## 9. Pre-Transaction Rules

### 9.1 Raw Content Sanitization Timing

Raw-sensitive content (OCR text, notification body, email body, bank descriptions, receipt text) MUST be passed through `RawContentSanitizer` with the resolved `RawStorageMode` BEFORE entering the `database.withTransaction` block. Never pass raw text directly into DAO insert parameters inside a transaction.

```kotlin
// CORRECT: sanitize before transaction
val sanitizedOcr = rawContentSanitizer.sanitize(rawOcr, rawStorageMode)
database.withTransaction {
    scannedReceiptDao.insert(receipt.copy(ocrText = sanitizedOcr))
    receiptEventDao.insert(event)
}

// WRONG: raw text inside transaction
database.withTransaction {
    scannedReceiptDao.insert(receipt.copy(ocrText = rawOcr))  // Raw PII!
    receiptEventDao.insert(event)
}
```

### 9.2 Validation Before Transaction

Domain validation (amount, currency, required fields, privacy gates) must execute BEFORE `database.withTransaction`. Invalid data must never enter the write path.

---

## 10. Event Field Privacy

### 10.1 Mandatory Rules

All lifecycle event entities (`TransactionEvent`, `ReceiptEvent`, `RecurringLifecycleEvent`, `LifecycleEvent`, `OperationRunEvent`) must adhere to:

1. **`errorDetails` field:** Must contain bounded diagnostic codes or `EventMetadataSanitizer` output. Never raw `e.message`, file paths, or stack traces.
2. **`message` field:** Must not contain raw exception text, file paths, user financial data, or PII.
3. **`metadata` field (JSON):** Must be built via `SafeEventMetadata`, not raw string interpolation.
4. **Post-commit side-effect failure reasons:** Must use bounded diagnostic codes (e.g., `SIDE_EFFECT_EXCEPTION`, `PRIVACY_DENIED`), not raw `e.message`.

### 10.2 Known Gaps

The following existing code paths persist raw exception messages and will be addressed in PR 2+:

| File | Issue |
|------|-------|
| `ReceiptSideEffectPlanner.kt` (line 315) | `errorDetails = e.message?.take(300)` — raw exception message |
| `ReceiptSideEffectPlanner.kt` (line 352) | Interpolates raw exception text into `ReceiptEvent.message` |
| `PostCommitActionRunnerImpl.kt` | Passes raw `e.message` as `SideEffectOutcome` reason |

---

## 11. Acceptance Criteria

### MIT-031 (State/Event Atomicity)

- [ ] `DomainTransactionRunner` implemented (PR 3)
- [ ] `TransactionContext` implemented (PR 3)
- [ ] `TransactionalEventWriter` implemented (PR 3)
- [ ] Direct event DAO inserts blocked outside coordinators (PR 3)
- [ ] State/event rollback tests pass (PR 3)

### MIT-041 (Receipt/PendingReview Atomicity)

- [ ] Receipt save + PendingReview insert in same transaction (PR 4)
- [ ] Receipt status update + event insert in same transaction (PR 4)
- [ ] Bank-statement receipt/review/event atomic (PR 5)
- [ ] No receipt requiring review can commit without review row (PR 4)

### MIT-043 (Recurring Atomicity)

- [ ] Occurrence state + event in same transaction (PR 6)
- [ ] Reminder delivery state + event in same transaction (PR 6)
- [ ] Projection generates all rows atomically (PR 6)
- [ ] `getDueReminders()` split from `recoverStaleClaimedDeliveries()` (PR 7)
- [ ] `reconcilePlannedVsActual()` split into read/write phases (PR 7)

---

## 12. Post-Commit Side-Effect Durability Decision (PR17)

**Decision: No durable outbox for post-commit side effects.**

**Justification:**
- Most post-commit side effects are non-critical (budget checks, merchant learning,
  price protection). Lost effects on crash are inconvenient but not data-corrupting.
- Critical side effects (receipt-to-expense matching) are already guarded by
  idempotency keys and can be re-triggered by re-running the matching.
- A full outbox would require: a `pending_side_effect` Room table, lambda-to-use-case
  dispatch table, replay worker, and WorkManager registration — estimated 400+ LOC
  with significant architectural risk.
- The existing diagnostics pipeline (SideEffectEventWriter, PostCommitSideEffectEvidenceService)
  provides sufficient observability for monitoring.

**What IS guaranteed:**
- All side effects execute ONLY after database commit (runBestEffortAfterCommit).
- Idempotency keys prevent double-execution on retry.
- CancellationException is properly propagated.
- FailedRetryable vs FailedFinal distinction enables future retry if needed.

**If outbox becomes necessary in the future:**
- Add `pending_side_effect` Room table.
- Replace PostCommitAction.execute lambda with a named use-case dispatch.
- Add `SideEffectReplayWorker` scheduled via WorkManager.
- See CANCELLATION_ATOMICITY_BASELINE.md §MIT-075 for tracking.
