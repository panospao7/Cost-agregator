# Atomicity / Cancellation / Event Consistency — Remaining Issues Implementation Plan

Base reviewed commit: `b1ad7bc079c1d41475c301bbe6850cf75a46ccf6`

## Current status

Do **not** mark all MITs fully done yet.

Recommended current status:

| MIT | Status |
|---|---|
| MIT-031 — state/event atomicity | NEAR-COMPLETE, pending PR20 |
| MIT-041 — receipt/bank atomicity | NEAR-COMPLETE, pending PR20 + green CI |
| MIT-034 — cancellation propagation | PARTIAL, large allowlist remains |
| MIT-043 — recurring/reminder atomicity | PARTIAL, best-effort regeneration + MIT-033 dependency |
| MIT-075 — side-effect outbox/evidence | PARTIAL by documented architecture decision |

Recommended branches:

```bash
git checkout -b atomicity-pr20-final-correctness-fixes
```

If you want to keep scopes small:

1. `PR20-1 — Bank cancellation/failure finalization correctness`
2. `PR20-2 — Event writer enforcement and direct event DAO guard`
3. `PR20-3 — Recurring stale recovery policy`
4. `PR20-4 — Bank skipped/failed item audit policy`
5. `PR20-5 — CI failure fixes`
6. `PR20-6 — Docs/status correction`
7. `PR21 — MIT-034 cancellation allowlist burn-down`
8. `PR22 — MIT-043 recurring full-atomicity decision`
9. `PR23 — MIT-075 outbox decision if needed`

---

# PR20-1 — Bank Cancellation / Failure Finalization Correctness

## Problems

### Problem A

Cancellation cleanup can mask the original `CancellationException`.

Current risk pattern:

```kotlin
catch (finalizeError: Exception) {
    CancellationSafe.rethrowIfCancellation(finalizeError)
    Timber.w(...)
}
```

If cleanup uses `withTimeout`, timeout throws `TimeoutCancellationException`, which is a `CancellationException`. This can replace the original cancellation.

### Problem B

Unexpected failure fallback can finalize the import run as failed without writing a matching receipt lifecycle event, because receipt ID is not available in the catch path.

### Problem C

CI is currently failing, so MIT-041 cannot close until compile/tests are green.

## Files

Likely:

- `BankStatementLifecycleProcessor.kt`
- `BankStatementImportRunDao.kt`
- `BankStatementImportItemDao.kt`
- `ReceiptLifecycleEventWriter.kt`
- bank statement tests

## Implementation

### 1. Track statement receipt ID outside the try scope

In `BankStatementLifecycleProcessor`:

```kotlin
var runId: Long? = null
var statementReceiptId: Long? = null
```

After receipt insert:

```kotlin
statementReceiptId = insertedReceiptId
```

This lets failure/cancellation cleanup know whether receipt lifecycle event can be written.

### 2. Cancellation cleanup must never throw

Replace current cancellation cleanup with:

```kotlin
catch (e: Exception) {
    if (e is CancellationException) {
        val originalCancellation = e

        runId?.let { rid ->
            try {
                withContext(NonCancellable) {
                    withTimeout(BANK_STATEMENT_CANCEL_FINALIZE_TIMEOUT_MS) {
                        finalizeCancelledImportRunBestEffort(
                            runId = rid,
                            statementReceiptId = statementReceiptId
                        )
                    }
                }
            } catch (cleanupError: Throwable) {
                originalCancellation.addSuppressed(cleanupError)
                Timber.w(
                    cleanupError,
                    "Failed to finalize cancelled bank statement import run"
                )
                // Optional durable fallback diagnostic if available.
            }
        }

        throw originalCancellation
    }

    ...
}
```

Important:

- Do **not** call `CancellationSafe.rethrowIfCancellation(cleanupError)` here.
- Never let cleanup failure replace the original cancellation.
- Use short timeout: `500L`–`2000L`.

### 3. Add helper for cancelled run

```kotlin
private suspend fun finalizeCancelledImportRunBestEffort(
    runId: Long,
    statementReceiptId: Long?
) {
    val processed = bankStatementImportItemDao.countProcessedByRun(runId)
    val failed = bankStatementImportItemDao.countFailedByRun(runId)

    transactionRunner.runInTransaction(
        operationId = "bank_statement.cancel_finalize",
        correlationId = ...
    ) { ctx ->
        bankStatementImportRunDao.finalize(
            runId = runId,
            status = "CANCELLED",
            processedCount = processed,
            failedCount = failed,
            reasonCode = "WORKER_CANCELLED"
        )

        if (statementReceiptId != null) {
            receiptLifecycleEventWriter.write(
                ctx,
                ReceiptLifecycleEvent.processingCancelled(
                    receiptId = statementReceiptId,
                    reasonCode = "WORKER_CANCELLED"
                )
            )
        }
    }
}
```

If receipt cancellation events are not desired, document that cancellation finalization is import-run-only. But then do not claim receipt/run/event full atomicity for cancelled statements.

### 4. Unexpected failure finalization must include receipt event if receipt exists

In non-cancellation failure catch:

```kotlin
runId?.let { rid ->
    val receiptId = statementReceiptId

    if (receiptId != null) {
        transactionRunner.runInTransaction(
            operationId = "bank_statement.unexpected_failure_finalize",
            correlationId = ...
        ) { ctx ->
            bankStatementImportRunDao.finalize(
                runId = rid,
                status = "FAILED",
                reasonCode = "WORKER_UNHANDLED_EXCEPTION",
                ...
            )

            receiptLifecycleEventWriter.write(
                ctx,
                ReceiptLifecycleEvent.processingFailed(
                    receiptId = receiptId,
                    reasonCode = "WORKER_UNHANDLED_EXCEPTION",
                    errorClass = e.javaClass.simpleName
                )
            )
        }
    } else {
        // Run ledger only; no receipt exists yet.
        bankStatementImportRunDao.finalize(...)
    }
}
```

### 5. Tests

Add:

1. `cancellation_cleanup_timeout_rethrows_original_cancellation`
2. `cancellation_cleanup_dao_failure_rethrows_original_cancellation`
3. `cancellation_finalize_adds_suppressed_cleanup_error`
4. `unexpected_failure_after_receipt_insert_finalizes_run_and_writes_receipt_event_atomically`
5. `unexpected_failure_before_receipt_insert_finalizes_run_only`
6. `failure_event_insert_failure_rolls_back_run_finalize`
7. `success_run_finalize_status_and_event_commit_atomically`

## Acceptance criteria

- Cancellation is never converted into DB failure.
- Unexpected failure after receipt creation cannot finalize run without corresponding receipt event.
- Bank statement tests pass.
- `compileDebugKotlin` passes.

---

# PR20-2 — Event Writer Enforcement and Direct Event DAO Guard

## Problems

MIT-031 was marked done, but enforcement is still weak:

- `TransactionalEventWriter` is marker-only.
- `TransactionContext` can be manually constructed.
- `ReceiptLifecycleEventWriter.write(event)` still exists as deprecated context-free method.
- Direct event DAO guard uses broad plain filename allowlist.

## Files

Likely:

- `TransactionalEventWriter.kt`
- `TransactionContext.kt`
- `DomainTransactionRunner.kt`
- `ReceiptLifecycleEventWriter.kt`
- `RecurringLifecycleEventWriter.kt`
- `TransactionLifecycleEventWriter.kt`
- `DirectEventDaoInsertGuardTest.kt`

## Implementation

### 1. Make `TransactionContext` harder to forge

Change from public data class constructor to internal constructor where possible:

```kotlin
class TransactionContext internal constructor(
    val transactionId: String,
    val operationId: String,
    val correlationId: String,
    val actor: String,
    val startedAtMs: Long,
    internal val token: TransactionToken
)

internal class TransactionToken internal constructor()
```

Only `DomainTransactionRunner` can create:

```kotlin
TransactionContext(
    ...,
    token = TransactionToken()
)
```

If Kotlin module boundaries make this difficult, at minimum add a token field and writer validation.

### 2. Remove or error deprecated context-free writer methods

Current dangerous method:

```kotlin
@Deprecated(...)
suspend fun write(event: ReceiptLifecycleEvent)
```

Preferred: remove it.

If not removable yet:

```kotlin
@Deprecated(
    message = "Use write(ctx, event) inside DomainTransactionRunner",
    level = DeprecationLevel.ERROR
)
suspend fun write(event: ReceiptLifecycleEvent)
```

Same rule for recurring/transaction event writers.

### 3. Add static guard against context-free event writes

Fail patterns:

```kotlin
receiptLifecycleEventWriter.write(event)
recurringLifecycleEventWriter.write(event)
transactionLifecycleEventWriter.write(event)
```

Allow only:

```kotlin
writer.write(ctx, event)
```

Tests:

- context-free call fixture fails;
- transaction-scoped call fixture passes.

### 4. Convert direct event DAO allowlist to structured entries

Create:

```kotlin
data class DirectEventDaoAllowlistEntry(
    val fileName: String,
    val rule: String,
    val owner: String,
    val reason: String,
    val issue: String,
    val expires: LocalDate
)
```

Validation:

- owner required;
- reason required;
- issue required;
- expiry required;
- expired entries fail;
- duplicate file+rule entries fail.

### 5. Shrink allowlist

Long-term allowed:

- event writer implementations;
- migrations;
- dedicated legacy repair coordinator with expiry;
- tests.

Move these off direct event DAO inserts or give short expiry:

- `ReceiptRepository.kt`
- `ReviewQueueRepository.kt`
- `ExpenseRepository.kt`
- `NotificationRepository.kt`
- `RecurringExpenseRepository.kt`
- `ManualRecurringExpenseRepository.kt`
- `BankApiIntegration.kt`

### 6. Tests

Add:

1. `context_free_receipt_event_writer_call_fails`
2. `transaction_scoped_event_writer_call_passes`
3. `manual_transaction_context_is_rejected_or_impossible`
4. `direct_event_dao_insert_in_repository_fails`
5. `approved_event_writer_insert_passes`
6. `direct_event_allowlist_requires_owner_reason_issue_expiry`
7. `expired_direct_event_allowlist_fails`
8. `duplicate_direct_event_allowlist_fails`

## Acceptance criteria

- MIT-031 enforcement is not marker-only.
- Critical events require transaction context.
- Direct event DAO writes are blocked outside approved owners.

---

# PR20-3 — Recurring Stale Recovery Policy

## Problems

MIT-043 is correctly partial, but stale recovery still needs a proper policy.

Current issue:

- `recoverStaleClaimedDeliveries()` mutates state.
- It now attempts an event, but event failure may be swallowed.
- If event is critical, state/event divergence remains.
- If event is operational, it should be a diagnostic, not a lifecycle event.

## Files

- `RecurringLifecycleCoordinator.kt`
- `ReminderDeliveryDao.kt`
- `RecurringLifecycleEventWriter.kt`
- diagnostics/event service if used
- recurring tests

## Implementation options

### Option A — Critical lifecycle recovery

Use if stale recovery is considered part of reminder lifecycle.

Implementation:

```kotlin
transactionRunner.runInTransaction(
    operationId = "recurring.recover_stale_claimed_deliveries",
    correlationId = ...
) { ctx ->
    val recovered = reminderDeliveryDao.recoverStaleClaimedDeliveries(...)
    if (recovered > 0) {
        recurringLifecycleEventWriter.write(
            ctx,
            RecurringLifecycleEvent.staleDeliveriesRecovered(...)
        )
    }
    recovered
}
```

No catch around event insert.

If event insert fails, transaction rolls back recovery.

Tests:

- event failure rolls back recovery;
- recovery event success commits state+event;
- write barrier denied prevents recovery.

### Option B — Operational recovery diagnostic

Use if stale recovery is not domain-lifecycle-critical.

Implementation:

```kotlin
transactionRunner.runInTransaction(...) {
    val recovered = reminderDeliveryDao.recoverStaleClaimedDeliveries(...)
    if (recovered > 0) {
        operationalDiagnosticDao.insert(
            code = "STALE_REMINDER_DELIVERIES_RECOVERED",
            count = recovered
        )
    }
}
```

If diagnostic failure should not block recovery, write it outside transaction through bounded diagnostic sink and document it as best-effort.

### Recommendation

Use **Option A** if you want to eventually close MIT-043. Use **Option B** only if you keep MIT-043 partial by design.

## Tests

Add:

1. `stale_recovery_checks_write_barrier`
2. `stale_recovery_event_failure_rolls_back_state_if_critical`
3. `stale_recovery_success_writes_event`
4. `stale_recovery_cancellation_rethrows`
5. `stale_recovery_returns_affected_count`

## Acceptance criteria

- No silent stale recovery writes.
- Event/diagnostic policy is explicit and tested.
- MIT-043 status remains honest.

---

# PR20-4 — Recurring Regeneration Best-Effort Policy

## Problem

Regeneration is best-effort by design. That is okay, but it means MIT-043 cannot be fully DONE under strict atomicity.

## Decision

Choose one:

### Option A — Keep best-effort and keep MIT-043 PARTIAL

Required:

- durable diagnostic for skipped regeneration window;
- docs say regeneration is best-effort;
- MIT-043 stays partial until all-or-nothing is implemented or accepted as product policy.

### Option B — Make regeneration all-or-nothing

Required:

- remove per-window catch;
- event/state failure aborts entire regeneration/unlink transaction;
- tests prove rollback.

## Recommended implementation for Option A

When a window fails:

```kotlin
catch (e: Exception) {
    CancellationSafe.rethrowIfCancellation(e)

    recurringDiagnosticWriter.record(
        code = "REMINDER_REGENERATION_WINDOW_SKIPPED",
        errorClass = e.javaClass.simpleName,
        occurrenceId = occurrenceId,
        windowStart = window.start
    )
}
```

No raw exception message.

Tests:

1. `regeneration_window_failure_records_durable_diagnostic`
2. `regeneration_window_failure_does_not_store_raw_message`
3. `regeneration_cancellation_rethrows`

## Acceptance criteria

- Best-effort behavior is visible and durable.
- MIT-043 remains partial or is explicitly redefined.

---

# PR20-5 — Bank Skipped / Failed Item Audit Policy

## Problem

Docs may imply all bank item lifecycle outcomes have receipt lifecycle events. But validation-skipped items do not have receipts, so they cannot have receipt events.

## Decision

Recommended policy:

```text
BankStatementImportItem is the authoritative audit ledger for skipped/failed rows before a receipt exists.
Receipt lifecycle events start once a receipt/review exists.
```

## Implementation

### 1. Update docs

Clarify:

- invalid amount/currency/date rows create skipped item ledger rows;
- no receipt lifecycle event is expected because no receipt exists;
- reason codes must be sanitized;
- item row insert is atomic as a single-row ledger write.

### 2. Tests

Add:

1. `invalid_amount_creates_skipped_item_ledger`
2. `invalid_currency_creates_skipped_item_ledger`
3. `skipped_item_has_sanitized_reason_code`
4. `skipped_item_does_not_create_receipt_event_without_receipt`
5. `failed_item_records_error_class_only`

## Acceptance criteria

- MIT-041 docs do not overclaim.
- Skipped/failed item audit policy is tested.

---

# PR20-6 — Fix CI Failures

## Problem

Latest GitHub Actions are failing for `b1ad7bc`.

## Tasks

### 1. Reproduce locally

Run:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```

Then targeted:

```bash
./gradlew :app:testDebugUnitTest --tests "*Recurring*"
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
```

### 2. Start with likely failing tests

Commit history mentioned recurring `generateOccurrences` tests. Focus on:

- tests expecting old direct `database.withTransaction`;
- tests not providing fake `DomainTransactionRunner`;
- tests expecting hidden-write `reconcilePlannedVsActual`;
- tests expecting no transaction context metadata.

### 3. Fix test setup, not production semantics

If failures are due to missing fakes:

- add fake `DomainTransactionRunner`;
- fake event writer that records context;
- update expected reason codes/event metadata.

### 4. CI gate

Do not update docs to DONE until this passes:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Preferred:

```bash
./gradlew :app:check
```

## Acceptance criteria

- Latest commit has green CI.
- No known failing atomicity/cancellation tests remain.

---

# PR20-7 — Docs / Tracker Correction

## Problem

Latest commit closes MIT-031 and MIT-041 as DONE, but current evidence supports NEAR-COMPLETE until PR20 fixes + green CI.

## Before PR20 passes

Set:

```text
MIT-031: NEAR-COMPLETE — PR20 enforcement/CI pending
MIT-041: NEAR-COMPLETE — PR20 bank cancellation/finalization/CI pending
MIT-034: PARTIAL — 99 allowlist entries, core paths clean
MIT-043: PARTIAL — MIT-033 dependency + best-effort regeneration
MIT-075: PARTIAL — no durable outbox by design
```

## After PR20 passes

Only close:

### MIT-031 can close if:

- context-free event writes are removed/blocked;
- direct event DAO guard is structured/expiring;
- event writer requires transaction context;
- CI green.

### MIT-041 can close if:

- bank cancellation does not mask CE;
- unexpected failure finalizes run+event atomically;
- skipped item audit policy tested;
- CI green.

MIT-034 remains partial unless allowlist is reduced substantially.

MIT-043 remains partial unless regeneration/stale recovery semantics become fully atomic or the MIT scope is redefined.

MIT-075 remains partial unless a real outbox is implemented.

---

# PR21 — MIT-034 Cancellation Allowlist Burn-Down

## Goal

Move MIT-034 from PARTIAL to NEAR-COMPLETE or DONE.

## Current issue

You reported:

```text
99 allowlist entries, 38 non-critical UI
```

Even if core worker/coordinator paths are clean, MIT-034 global scope remains partial.

## Tasks

### 1. Split allowlist by category

Categories:

```text
CORE_WORKER
CORE_COORDINATOR
REPOSITORY_MUTATION
RECEIVER
NETWORK_PROVIDER
UI_VIEWMODEL
TEST_ONLY
```

### 2. Zero out core categories

No allowlist allowed for:

- workers;
- receivers;
- receipt coordinators;
- recurring coordinators;
- bank statement processor;
- data retention;
- import/export coordinators;
- operation/worker ledger.

### 3. Short expiry for repositories

Any repository allowlist:

- owner;
- reason;
- linked issue;
- expiry within 30–45 days.

### 4. Longer expiry for UI if acceptable

UI ViewModels can be separately tracked:

```text
MIT-034-UI
```

But do not claim global MIT-034 DONE if UI remains exempt.

### 5. Tests

- no core file in cancellation allowlist;
- expired allowlist fails;
- new worker broad catch fails;
- raw runCatching in suspend path fails.

## Acceptance criteria

- MIT-034 can only close when no core/background/repository mutation path is exempt.
- UI exceptions are either fixed or scoped into separate issue.

---

# PR22 — MIT-043 Full Closure Decision

## Goal

Decide whether MIT-043 remains partial by design or is completed.

## Current blockers

- DB uniqueness depends on MIT-033.
- Regeneration is best-effort by design.
- Stale recovery policy needs final decision.

## Option A — Keep MIT-043 partial

Document:

```text
MIT-043 cannot fully close until MIT-033 uniqueness lands and regeneration best-effort policy is replaced or formally accepted.
```

Create follow-up:

```text
MIT-043B — recurring duplicate fulfillment and regeneration hardening
```

## Option B — Close MIT-043

Required:

1. MIT-033 uniqueness merged.
2. Regeneration all-or-nothing or durable diagnostics accepted.
3. Stale recovery event policy finalized.
4. Projection rollback tests pass.
5. Duplicate actual link conflict tests pass.

## Acceptance criteria

- No ambiguous “partial but done” status.
- Closure matches actual semantics.

---

# PR23 — MIT-075 Outbox Decision

## Current status

You correctly wrote:

```text
MIT-075 PARTIAL — No durable outbox, architectural decision documented.
```

## Option A — Keep partial

Docs should clearly say:

```text
Post-commit side-effect evidence is diagnostic-only.
No guaranteed retry/replay.
```

## Option B — Implement durable outbox

Add table:

```text
post_commit_side_effects
```

Fields:

```text
id
operationId
correlationId
type
payloadJson
status
attempts
nextAttemptAt
lastErrorCode
lastErrorClass
createdAt
updatedAt
```

Add dispatcher worker.

Tests:

- enqueue transactionally;
- failure updates row;
- retry schedules next attempt;
- dead-letter after max attempts.

---

# Final Validation Commands

Run before any final closure:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:verifyRoomSchemaSnapshots
./gradlew :app:verifyDbAccessBoundaries
```

Targeted:

```bash
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Recurring*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest --tests "*Consistency*"
./gradlew :app:testDebugUnitTest --tests "*Retention*"
```

---

# Final Closure Checklist

## MIT-031

Close only when:

- event writers require transaction context;
- context-free writer calls are removed/blocked;
- direct event DAO guard is structured and expiring;
- CI green.

## MIT-041

Close only when:

- bank cancellation cleanup cannot mask CE;
- unexpected failure after receipt insert writes run failure + receipt event atomically;
- skipped/failed item audit policy is tested;
- CI green.

## MIT-034

Close only when:

- no core worker/coordinator/repository mutation path is allowlisted;
- raw `runCatching` in suspend paths is blocked;
- broad catch fixtures fail;
- UI exceptions are fixed or split out.

## MIT-043

Close only when:

- MIT-033 uniqueness is merged or not required;
- regeneration policy is fully atomic or durably diagnosed;
- stale recovery policy is final;
- projection/duplicate fulfillment tests pass.

## MIT-075

Close only if:

- durable outbox exists; or
- scope is explicitly changed to diagnostic evidence only.