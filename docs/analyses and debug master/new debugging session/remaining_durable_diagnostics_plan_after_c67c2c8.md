# Remaining Durable Diagnostics Implementation Plan

Target commit: `c67c2c8236bbc43553cbb9c0c96ca339afe2515a`

Goal: finish the remaining durable diagnostics/lifecycle-event work after the latest fixes.

---

## 0. Remaining issue list

### Critical / high priority

```text
DDL-C67-01 SafeSinkOperationRunHandle terminal methods emit no terminal event
DDL-C67-02 Bank blocked sync can produce inconsistent terminal outcomes
DDL-C67-03 Room operation terminal events lose reasonCode / summary
DDL-C67-04 resetDatabase journal history starts too late
DDL-C67-05 legacy import post-swap failure lacks terminal journal event
DDL-C67-06 restore journal still stores full internal paths in same durable JSON
```

### Medium priority

```text
DDL-C67-07 RestoreJournal renameTo() results are unchecked
DDL-C67-08 stale recovery event insert failure is Timber-only
DDL-C67-09 SafeSinkOperationRunHandle.increment() is no-op
DDL-C67-10 transaction update/delete/bulk correlation remains partial
DDL-C67-11 putHashed() accepts unapproved hash-like keys before final sanitation
DDL-C67-12 DiagnosticsRepository safe-sink failure filtering misses severity-only failures
DDL-C67-13 regression tests still mirror/helper-test bugs instead of production behavior
```

---

# Recommended PR order

```text
PR 1  Safe operation terminal hotfix
PR 2  Operation terminal reason/increment/stale-recovery durability
PR 3  Reset/import journal completeness and atomic journal writes
PR 4  Transaction mutation + side-effect correlation completion
PR 5  Restore journal privacy split
PR 6  Metadata/debug filtering hardening
PR 7  Real behavioral regression tests
```

---

# PR 1 — Safe operation terminal hotfix

## Issues fixed

```text
DDL-C67-01
DDL-C67-02
```

## Problem

`SafeSinkOperationRunHandle.terminalOnce()` currently sets `_isTerminal = true`, then calls `event(..., isTerminal = true)`.  
But `event()` also checks and sets `_isTerminal`, so it sees the handle already terminal and returns without writing the terminal event.

Result:

```text
STARTED exists
terminal success/cancelled/failed may not be emitted
```

Bank restore-blocked sync also currently emits:

```text
WRITE_BARRIER / BLOCKED / terminal
then cancelled(RESTORE_BLOCKED)
```

This can produce either duplicate terminal events or the wrong terminal status.

## Files

```text
domain/diagnostics/CompositeOperationRunRecorder.kt
domain/diagnostics/SafeSinkOperationRunHandle.kt
domain/bank/BankApiIntegration.kt
```

If `SafeSinkOperationRunHandle` is nested inside `CompositeOperationRunRecorder.kt`, keep it there or extract it.

---

## Step 1.1 — Split terminal state mutation from event emission

Add a lower-level method that writes the safe event without touching `_isTerminal`.

```kotlin
private suspend fun emitSafeEvent(
    stage: String,
    outcome: EventOutcome,
    reasonCode: DiagnosticReasonCode?,
    severity: EventSeverity,
    metadata: SafeEventMetadata,
    exception: Throwable?,
    isTerminal: Boolean
) {
    safeSink.recordDiagnosticEvent(
        event = DiagnosticEvent(
            pipeline = pipelineForOperation(operationType),
            stage = stage,
            outcome = outcome,
            severity = severity,
            reasonCode = reasonCode,
            correlationId = correlationId,
            metadata = metadataWithOperationContext(metadata),
            exception = exception,
            isTerminal = isTerminal
        ),
        mode = maintenanceMode.currentMode(),
        writeFailure = null
    )
}
```

Then implement `event()` like:

```kotlin
override suspend fun event(
    stage: String,
    outcome: EventOutcome,
    reasonCode: DiagnosticReasonCode?,
    severity: EventSeverity,
    metadata: SafeEventMetadata,
    eventType: String?,
    causationId: String?,
    entityType: String?,
    entityId: Long?,
    exception: Throwable?,
    isTerminal: Boolean
) {
    if (isTerminal && !_isTerminal.compareAndSet(false, true)) {
        return
    }

    emitSafeEvent(
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        severity = severity,
        metadata = metadata,
        exception = exception,
        isTerminal = isTerminal
    )
}
```

And `terminalOnce()` like:

```kotlin
private suspend fun terminalOnce(
    stage: String,
    outcome: EventOutcome,
    reasonCode: DiagnosticReasonCode?,
    severity: EventSeverity,
    metadata: SafeEventMetadata,
    exception: Throwable? = null
) {
    if (!_isTerminal.compareAndSet(false, true)) {
        return
    }

    emitSafeEvent(
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        severity = severity,
        metadata = metadata,
        exception = exception,
        isTerminal = true
    )
}
```

The key rule:

```text
terminalOnce() must not call event(... isTerminal=true)
```

---

## Step 1.2 — Fix terminal methods

Ensure these call `terminalOnce()`:

```kotlin
override suspend fun success() {
    terminalOnce(
        stage = "SUCCESS",
        outcome = EventOutcome.COMPLETED,
        reasonCode = null,
        severity = EventSeverity.INFO,
        metadata = finalCounterMetadata()
    )
}

override suspend fun partialSuccess(summary: String?) {
    terminalOnce(
        stage = "PARTIAL_SUCCESS",
        outcome = EventOutcome.COMPLETED,
        reasonCode = null,
        severity = EventSeverity.WARNING,
        metadata = finalCounterMetadata()
            .merge(SafeEventMetadata.builder().put("summary", summary).build())
    )
}

override suspend fun failedFinal(reason: String, error: Throwable?) {
    terminalOnce(
        stage = "FAILED_FINAL",
        outcome = EventOutcome.FAILED_FINAL,
        reasonCode = parseReasonCode(reason) ?: DiagnosticReasonCode.UNKNOWN_ERROR,
        severity = EventSeverity.ERROR,
        metadata = finalCounterMetadata()
            .merge(SafeEventMetadata.builder().put("statusReason", reason).build()),
        exception = error
    )
}

override suspend fun failedRetryable(reason: String, error: Throwable?) {
    terminalOnce(
        stage = "FAILED_RETRYABLE",
        outcome = EventOutcome.FAILED_RETRYABLE,
        reasonCode = parseReasonCode(reason) ?: DiagnosticReasonCode.UNKNOWN_ERROR,
        severity = EventSeverity.WARNING,
        metadata = finalCounterMetadata()
            .merge(SafeEventMetadata.builder().put("statusReason", reason).build()),
        exception = error
    )
}

override suspend fun cancelled(reason: String?) {
    terminalOnce(
        stage = "CANCELLED",
        outcome = EventOutcome.CANCELLED,
        reasonCode = parseReasonCode(reason) ?: DiagnosticReasonCode.CANCELLED_BY_SYSTEM,
        severity = EventSeverity.WARNING,
        metadata = finalCounterMetadata()
            .merge(SafeEventMetadata.builder().put("cancellationReason", reason).build())
    )
}
```

---

## Step 1.3 — Fix bank blocked sync terminal policy

In `BankApiIntegration`, change:

```kotlin
run.event(
    stage = "WRITE_BARRIER",
    outcome = EventOutcome.BLOCKED,
    reasonCode = DiagnosticReasonCode.RESTORE_BLOCKED,
    isTerminal = true
)
run.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
```

to:

```kotlin
run.event(
    stage = "WRITE_BARRIER",
    outcome = EventOutcome.BLOCKED,
    reasonCode = DiagnosticReasonCode.RESTORE_BLOCKED,
    severity = EventSeverity.WARNING,
    exception = e,
    isTerminal = false
)

run.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
```

Rule:

```text
WRITE_BARRIER is a stage event.
CANCELLED / RESTORE_BLOCKED is the single terminal operation event.
```

---

## Tests

```text
real_safe_handle_success_emits_terminal_success_event
real_safe_handle_cancelled_emits_terminal_cancelled_event
real_safe_handle_failed_final_emits_terminal_failed_event
real_safe_handle_cancelled_then_success_has_exactly_one_terminal_event
real_safe_handle_direct_terminal_event_marks_handle_terminal
real_safe_handle_terminal_once_does_not_double_compare_and_skip
bank_restore_blocked_safe_handle_has_one_terminal_event
bank_restore_blocked_room_handle_has_one_terminal_event
bank_restore_blocked_terminal_event_is_cancelled_restore_blocked
```

## Acceptance criteria

```text
1. Safe operation handles always emit terminal event for success/cancel/failure.
2. Safe operation handles emit at most one terminal event.
3. Bank restore-blocked sync has exactly one terminal outcome: CANCELLED / RESTORE_BLOCKED.
```

---

# PR 2 — Operation terminal reason, increment, and stale-recovery durability

## Issues fixed

```text
DDL-C67-03
DDL-C67-08
DDL-C67-09
```

## Problem

Room operation terminal events lose reason codes and summaries.  
Stale recovery event insert failure is only logged.  
Safe operation increment is no-op.

## Files

```text
domain/diagnostics/OperationRunRecorder.kt
domain/diagnostics/CompositeOperationRunRecorder.kt
domain/diagnostics/SafeSinkOperationRunHandle.kt
data/database/entity/OperationRunEvent.kt
data/database/dao/OperationRunEventDao.kt
```

---

## Step 2.1 — Preserve reason codes in Room terminal events

Update `RoomOperationRunRecorder.Handle.finalizeNonCancellable(...)`.

Current approximate behavior:

```kotlin
event(
    stage = status,
    outcome = statusToOutcome(status),
    isTerminal = true
)
```

Change terminal flow to include reason/summary:

```kotlin
private suspend fun finalizeNonCancellable(
    status: String,
    statusReason: String?,
    errorSummary: String?,
    error: Throwable?
) {
    withContext(NonCancellable) {
        runCatching {
            val updated = runDao.finalizeIfRunning(
                id = runId,
                status = status,
                finishedAt = timeProvider.now(),
                errorSummary = errorSummary,
                statusReason = statusReason
            )

            if (updated > 0) {
                event(
                    stage = status,
                    outcome = statusToOutcome(status),
                    reasonCode = parseReasonCode(statusReason),
                    severity = severityForStatus(status),
                    metadata = SafeEventMetadata.builder()
                        .put("statusReason", statusReason)
                        .put("errorSummary", errorSummary)
                        .put("operationType", operationType)
                        .build(),
                    exception = error,
                    isTerminal = true
                )
            }
        }.onFailure { finalizeError ->
            safeSink.recordDiagnosticEvent(...)
        }
    }
}
```

Reason parser:

```kotlin
private fun parseReasonCode(reason: String?): DiagnosticReasonCode? =
    reason?.let { runCatching { DiagnosticReasonCode.valueOf(it) }.getOrNull() }
```

Severity mapping:

```kotlin
private fun severityForStatus(status: String): EventSeverity = when (status) {
    "SUCCESS" -> EventSeverity.INFO
    "PARTIAL_SUCCESS" -> EventSeverity.WARNING
    "SKIPPED" -> EventSeverity.INFO
    "CANCELLED" -> EventSeverity.WARNING
    "FAILED_RETRYABLE" -> EventSeverity.WARNING
    "FAILED_FINAL" -> EventSeverity.ERROR
    "STALE_ABORTED" -> EventSeverity.WARNING
    else -> EventSeverity.INFO
}
```

---

## Step 2.2 — Terminal methods should pass proper reason

Examples:

```kotlin
override suspend fun cancelled(reason: String?) {
    finalizeNonCancellable(
        status = "CANCELLED",
        statusReason = reason ?: DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name,
        errorSummary = reason,
        error = null
    )
}
```

```kotlin
override suspend fun failedFinal(reason: String, error: Throwable?) {
    finalizeNonCancellable(
        status = "FAILED_FINAL",
        statusReason = reason,
        errorSummary = sanitizer.sanitizeExceptionMessage(error?.message) ?: reason,
        error = error
    )
}
```

```kotlin
override suspend fun partialSuccess(summary: String?) {
    finalizeNonCancellable(
        status = "PARTIAL_SUCCESS",
        statusReason = null,
        errorSummary = summary,
        error = null
    )
}
```

---

## Step 2.3 — Make stale-recovery event insert failure durable

Current:

```kotlin
runCatching { eventDao.insert(...) }
    .onFailure { Timber.w(...) }
```

Change to:

```kotlin
runCatching {
    eventDao.insert(staleEvent)
}.onFailure { error ->
    safeSink.recordDiagnosticEvent(
        event = DiagnosticEvent(
            pipeline = pipelineForOperation(run.operationType),
            stage = "stale_recovery_event_write_failed",
            outcome = EventOutcome.SIDE_EFFECT_FAILED,
            severity = EventSeverity.WARNING,
            reasonCode = DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
            correlationId = run.correlationId,
            metadata = SafeEventMetadata.builder()
                .put("operationType", run.operationType)
                .put("operationRunId", run.id)
                .build(),
            exception = error,
            isTerminal = false
        ),
        mode = maintenanceMode.currentMode(),
        writeFailure = error
    )
}
```

Ensure stale event has:

```kotlin
eventId = CorrelationIds.newId()
```

---

## Step 2.4 — Implement safe handle counters

In `SafeSinkOperationRunHandle` add counters:

```kotlin
private val rowsProcessed = AtomicInteger(0)
private val rowsSucceeded = AtomicInteger(0)
private val rowsFailed = AtomicInteger(0)
private val rowsSkipped = AtomicInteger(0)
private val warningCount = AtomicInteger(0)
private val errorCount = AtomicInteger(0)
```

Implement:

```kotlin
override suspend fun increment(
    processed: Int,
    succeeded: Int,
    failed: Int,
    skipped: Int,
    warnings: Int,
    errors: Int
) {
    rowsProcessed.addAndGet(processed)
    rowsSucceeded.addAndGet(succeeded)
    rowsFailed.addAndGet(failed)
    rowsSkipped.addAndGet(skipped)
    warningCount.addAndGet(warnings)
    errorCount.addAndGet(errors)

    safeSink.recordDiagnosticEvent(
        event = DiagnosticEvent(
            pipeline = pipelineForOperation(operationType),
            stage = "OPERATION_COUNTERS_UPDATED",
            outcome = EventOutcome.UPDATED,
            severity = EventSeverity.DEBUG,
            correlationId = correlationId,
            metadata = currentCounterMetadata(),
            isTerminal = false
        ),
        mode = maintenanceMode.currentMode()
    )
}
```

Terminal metadata should include final counts:

```kotlin
private fun finalCounterMetadata(): SafeEventMetadata =
    SafeEventMetadata.builder()
        .put("operationType", operationType)
        .put("rowsProcessed", rowsProcessed.get())
        .put("rowsSucceeded", rowsSucceeded.get())
        .put("rowsFailed", rowsFailed.get())
        .put("rowsSkipped", rowsSkipped.get())
        .put("warningCount", warningCount.get())
        .put("errorCount", errorCount.get())
        .build()
```

---

## Tests

```text
room_operation_cancelled_terminal_event_has_restore_blocked_reason
room_operation_failed_final_event_has_safe_summary_metadata
room_operation_partial_success_event_has_summary_metadata
stale_recovery_event_insert_failure_records_safe_sink_diagnostic
stale_recovery_event_has_event_id
safe_handle_increment_accumulates_counts
safe_handle_terminal_event_includes_counts
safe_handle_increment_emits_counter_update_or_terminal_summary
```

## Acceptance criteria

```text
1. Room operation terminal events have reasonCode/summary metadata.
2. Stale recovery event insert failure is durable via safe sink.
3. Safe operation handles preserve useful counter summaries.
```

---

# PR 3 — Reset/import journal completeness and atomic journal writes

## Issues fixed

```text
DDL-C67-04
DDL-C67-05
DDL-C67-07
```

## Problem

`resetDatabase()` emits early stages before `beginJournal()`, so the success journal misses them.  
Legacy import post-swap failure lacks a terminal event.  
`RestoreJournal` uses unchecked `renameTo()`.

## Files

```text
data/repository/DatabaseBackupRepositoryImpl.kt
data/backup/RestoreJournal.kt
data/backup/RestoreDiagnosticsSink.kt
```

Tests:

```text
DatabaseBackupRepositoryResetDiagnosticsTest.kt
DatabaseBackupRepositoryLegacyImportDiagnosticsTest.kt
RestoreJournalTest.kt
```

---

## Step 3.1 — Begin reset journal before first reset event

Current reset order roughly:

```text
enter maintenance
emit MAINTENANCE_ENTERED
create safety backup
emit SAFETY_BACKUP_CREATED
beginJournal
delete DB
```

Change to:

```kotlin
var journalEntry = restoreJournal.beginJournal(
    operationType = "RESET_DATABASE",
    operationCorrelationId = run.correlationId,
    sourceBackupPath = null,
    stagedDbPath = null,
    liveDbPath = databasePath.absolutePath
)

val resetEvents = RestoreDiagnosticsSink(...)

resetEvents.event("RESET_STARTED", EventOutcome.ATTEMPTED)

maintenanceOperationRunner.enterAndDrain(...)
resetEvents.event("MAINTENANCE_ENTERED", EventOutcome.COMPLETED)

val safetyBackup = createSafetyBackup(...)
resetEvents.event("SAFETY_BACKUP_CREATED", EventOutcome.COMPLETED)
```

Then destructive point:

```kotlin
resetEvents.markLiveDbSwapStarted()
deleteDatabaseFiles()
resetEvents.event("LIVE_DB_DELETED", EventOutcome.COMPLETED)
resetEvents.event("RESTART_REQUIRED", EventOutcome.COMPLETED, isTerminal = true)
journalEntry = restoreJournal.transitionTo(journalEntry, COMPLETE)
restoreJournal.commitJournal(journalEntry)
```

---

## Step 3.2 — Legacy import post-swap failure terminal event

In `importDatabase()` after DB replacement has started, catch failure and emit terminal event before or during `failJournal()`:

```kotlin
importEvents.event(
    stage = "LEGACY_IMPORT_FAILED_AFTER_SWAP",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.CRITICAL,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = e,
    isTerminal = true
)
```

If rollback succeeds:

```kotlin
importEvents.event(
    stage = "ROLLBACK_COMPLETED",
    outcome = EventOutcome.COMPLETED,
    severity = EventSeverity.WARNING
)
```

If rollback fails:

```kotlin
importEvents.event(
    stage = "ROLLBACK_FAILED",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.CRITICAL,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = rollbackError,
    isTerminal = true
)
```

Important ordering:

```text
Prefer event -> failJournal so the active journal contains event and failJournal preserves it.
If failJournal already happened, RestoreDiagnosticsSink should append to failure journal.
```

---

## Step 3.3 — Make journal writes check rename result

Replace unchecked:

```kotlin
tmpFile.renameTo(journalFile)
journalFile.renameTo(successFile)
journalFile.renameTo(failureFile)
```

With:

```kotlin
private fun atomicReplace(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    } catch (atomicMoveError: AtomicMoveNotSupportedException) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}
```

If Android API compatibility is an issue, use checked `renameTo()` fallback:

```kotlin
if (!tmpFile.renameTo(targetFile)) {
    throw IOException("Failed to replace journal file ${targetFile.name}")
}
```

For commit/fail:

```kotlin
if (!journalFile.renameTo(successFile)) {
    throw IOException("Failed to preserve restore success journal")
}
```

Do not silently continue if preservation failed.

---

## Tests

```text
reset_success_journal_contains_reset_started
reset_success_journal_contains_maintenance_entered
reset_success_journal_contains_safety_backup_created
reset_success_journal_contains_live_db_deleted_and_restart_required
legacy_import_post_swap_failure_writes_terminal_failed_event
legacy_import_rollback_success_writes_rollback_completed_event
legacy_import_rollback_failure_writes_rollback_failed_critical_event
recent_failures_includes_legacy_import_post_swap_failure
commit_journal_reports_failure_when_success_rename_fails
append_event_reports_failure_when_tmp_rename_fails
fail_journal_reports_failure_when_failure_rename_fails
```

## Acceptance criteria

```text
1. Reset success journal has full stage history.
2. Legacy import post-swap failure has terminal journal event.
3. Journal preservation failures are detected, not silently ignored.
```

---

# PR 4 — Transaction mutation and side-effect correlation completion

## Issue fixed

```text
DDL-C67-10
```

## Problem

Create path is correlated, but update/delete/bulk paths and their side effects are still partial.

## Files

```text
domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
data/database/entity/TransactionEvent.kt
data/database/dao/TransactionEventDao.kt
```

---

## Step 4.1 — Add optional correlation to all mutation methods

Add optional params to all transaction mutation APIs:

```kotlin
correlationId: String? = null,
causationId: String? = null
```

Targets include:

```text
updateExpense
updateCategory
updateLocation
updateBusinessFlags
updateMerchant
updateType
updateTransferDetails
updateTypeAndTransferDetails
updateOwnership
bulkUpdateCategory
bulkUpdateMerchant
deleteExpense(expenseId)
deleteExpense(expense)
```

Inside each method:

```kotlin
val cid = correlationId ?: CorrelationIds.newId()
```

Use `cid` for all:

```text
TransactionEvent
diagnostic validation failure
side-effect events
```

---

## Step 4.2 — Pass correlation to TransactionEvent

Example:

```kotlin
transactionEventDao.insert(
    TransactionEvent(
        expenseId = expenseId,
        eventType = "UPDATED",
        ...
        correlationId = cid,
        causationId = causationId
    )
)
```

Ensure both overloads of delete write correlation:

```kotlin
deleteExpense(id, correlationId = cid)
deleteExpense(expense, correlationId = cid)
```

---

## Step 4.3 — Pass correlation to side-effect dispatcher

Update dispatcher API:

```kotlin
suspend fun dispatchOnUpdated(
    expenseId: Long,
    source: ExpenseSource,
    correlationId: String,
    causationId: String? = null
)

suspend fun dispatchOnDeleted(
    expenseId: Long,
    source: ExpenseSource,
    correlationId: String,
    causationId: String? = null
)

suspend fun dispatchOnBulkUpdated(
    source: ExpenseSource,
    affectedCount: Int,
    correlationId: String,
    causationId: String? = null
)
```

Side-effect recorder context:

```kotlin
SideEffectContext(
    pipeline = AppPipeline.TRANSACTION,
    correlationId = correlationId,
    causationId = causationId,
    entityType = "Expense",
    entityId = expenseId,
    source = source.name
)
```

Bulk entity:

```text
entityType = "ExpenseBulk"
entityId = null
metadata.affectedCount = affectedCount
```

---

## Step 4.4 — Update callers

Any caller that already has a correlation should pass it:

```text
receipt link flows
email ingestion flows
bank sync
notification auto-accept
pending review approval
import flows
```

If caller does not have one, let coordinator generate.

---

## Tests

```text
update_category_uses_supplied_correlation
update_merchant_uses_supplied_correlation
update_type_uses_supplied_correlation
update_transfer_details_uses_supplied_correlation
delete_expense_id_overload_uses_supplied_correlation
delete_expense_object_overload_uses_supplied_correlation
bulk_update_category_uses_supplied_correlation
bulk_update_merchant_uses_supplied_correlation
update_side_effect_uses_supplied_correlation
delete_side_effect_uses_supplied_correlation
bulk_update_side_effect_uses_supplied_correlation
bank_expense_update_side_effect_preserves_bank_correlation_if_applicable
notification_auto_accept_update_side_effect_preserves_listener_correlation_if_applicable
```

## Acceptance criteria

```text
1. All transaction mutation lifecycle events can be queried by correlationId.
2. Update/delete/bulk side effects use the same correlation as the mutation.
3. Existing callers continue to work when no correlation is supplied.
```

---

# PR 5 — Restore journal privacy split

## Issue fixed

```text
DDL-C67-06
```

## Problem

`toDiagnosticsJson()` strips path fields, but the durable journal file still stores internal full paths. This is better than before but still not privacy-safe by construction.

## Files

```text
data/backup/RestoreJournal.kt
data/backup/RestoreJournalImporter.kt
data/debug/DiagnosticsRepositoryImpl.kt
```

---

## Step 5.1 — Split recovery and diagnostics journals

Create two separate files:

```text
restore_recovery_journal.json
restore_diagnostics_journal.json
```

Preserved files:

```text
restore_diagnostics_last_success.json
restore_diagnostics_last_failure.json
restore_recovery_last_failure.json
```

### Recovery journal

Internal only. May contain:

```text
_sourceBackupPath
_stagedDbPath
_safetyBackupPath
_liveDbPath
assetTargetPath
```

Rules:

```text
not returned by DiagnosticsRepository
not included in support export
not shown in UI
used only by checkAndRecover / rollback / cleanup
```

### Diagnostics journal

Privacy-safe only. Contains:

```text
operationCorrelationId
operationType
status
startedAt
finishedAt
sourceBackupName
sourceBackupPathHash
stagedDbPathHash
safetyBackupPathHash
liveDbPathHash
assetDisplayName
assetRelativePathHash
events[]
```

No keys starting with `_`.

---

## Step 5.2 — Write both journals together

On begin:

```kotlin
writeRecoveryJournal(recoveryEntry)
writeDiagnosticsJournal(diagnosticsEntry)
```

On transition/fail/commit:

```text
update both where relevant
preserve diagnostics events
preserve recovery operational state
```

On append event:

```text
append only to diagnostics journal
```

On recovery-only state update:

```text
write only recovery journal
```

---

## Step 5.3 — Importer reads diagnostics journal only

`RestoreJournalImporter` must read:

```text
restore_diagnostics_last_success.json
```

not recovery journal.

---

## Step 5.4 — DiagnosticsRepository reads diagnostics journals only

`DiagnosticsRepository.getTraceByCorrelationId()` should use:

```text
active diagnostics journal
last success diagnostics journal
last failure diagnostics journal
```

No recovery file reads.

---

## Step 5.5 — Transitional bridge if full split is too large

If split is too risky in one PR, add strict bridge:

```text
1. Keep raw file pathful.
2. Add support export/static guard forbidding raw RestoreJournal.toJson().
3. All debug APIs return only toDiagnosticsJson().
4. Tests assert no path patterns in all debug/export outputs.
```

But mark this as temporary.

---

## Tests

```text
restore_diagnostics_journal_file_has_no_full_paths
restore_recovery_journal_roundtrips_full_paths
diagnostics_repository_never_returns_internal_path_fields
support_export_never_includes_restore_internal_paths
active_diagnostics_journal_has_no_keys_starting_with_underscore
success_diagnostics_journal_has_no_keys_starting_with_underscore
failure_diagnostics_journal_has_no_keys_starting_with_underscore
asset_restore_recovery_keeps_real_target_path_in_recovery_journal
asset_restore_diagnostics_exposes_only_display_name_and_hash
```

## Acceptance criteria

```text
1. Pathful recovery data is physically separated from diagnostics data.
2. Diagnostics/debug/support outputs cannot expose full local paths accidentally.
3. Recovery still has the path data needed for cleanup/rollback.
```

---

# PR 6 — Metadata and debug filtering hardening

## Issues fixed

```text
DDL-C67-11
DDL-C67-12
```

## Files

```text
domain/diagnostics/SafeEventMetadata.kt
domain/diagnostics/EventMetadataSanitizer.kt
domain/debug/DiagnosticsRepository.kt
data/debug/DiagnosticsRepositoryImpl.kt
```

---

## Step 6.1 — Make putHashed reject unapproved hash keys

Expose sanitizer helper:

```kotlin
fun isApprovedHashKey(key: String): Boolean
```

Implementation:

```kotlin
fun isApprovedHashKey(key: String): Boolean =
    canonicalizeKey(key) in SAFE_HASH_KEYS
```

In `SafeEventMetadata.Builder.putHashed(...)`:

```kotlin
fun putHashed(key: String, value: String?): Builder {
    if (!sanitizer.isApprovedHashKey(key)) {
        values[key] = EventMetadataSanitizer.REDACTED
        return this
    }

    values[key] = value?.let { sha256Prefix(it) }
    return this
}
```

Do not allow:

```text
rawTextHash
accessTokenHash
fullPathHash
unknownHash
```

unless explicitly added to `SAFE_HASH_KEYS` after review.

---

## Step 6.2 — Safe-sink recent failures should include severity-only failures

In `DiagnosticsRepositoryImpl.getRecentFailures()` update safe-sink filtering:

```kotlin
private fun isFailure(record: MaintenanceSafeDiagnosticRecord): Boolean {
    return record.outcome in failureOutcomes ||
           record.severity in setOf("WARNING", "ERROR", "CRITICAL")
}
```

Apply similar rule to restore journal events if not already:

```text
outcome failure OR severity warning/error/critical
```

---

## Step 6.3 — Sanitize debug model fields again

Before returning debug summaries:

```kotlin
messageSafe = sanitizer.sanitizeExceptionMessage(messageSafe)
metadataJson = sanitizer.sanitizeJsonString(metadataJson)
```

Never return fields whose key starts with `_`.

---

## Tests

```text
put_hashed_raw_text_hash_is_redacted
put_hashed_unknown_hash_key_is_redacted
put_hashed_source_id_hash_is_allowed
put_hashed_access_token_hash_is_redacted
recent_failures_includes_safe_sink_error_severity_even_if_outcome_completed
recent_failures_includes_safe_sink_warning
recent_failures_sanitizes_message_and_metadata_again
recent_failures_strips_underscore_fields
```

## Acceptance criteria

```text
1. SafeEventMetadata is safe by construction for putHashed().
2. Recent failures include severity-only safe-sink failures.
3. Debug outputs get final-pass sanitization.
```

---

# PR 7 — Real behavioral regression tests

## Issue fixed

```text
DDL-C67-13
```

## Problem

Some current tests use helper/fake logic that can mirror production bugs instead of catching them. Add tests against real production classes or realistic fakes.

## Test files to add/extend

```text
SafeSinkOperationRunHandleTest.kt
OperationRunRecorderTest.kt
RestoreJournalTest.kt
RestoreDiagnosticsSinkTest.kt
DatabaseBackupRepositoryResetDiagnosticsTest.kt
DatabaseBackupRepositoryLegacyImportDiagnosticsTest.kt
NotificationProcessingPipelineDiagnosticsTest.kt
TransactionLifecycleCoordinatorCorrelationTest.kt
DiagnosticsRepositoryTest.kt
EventMetadataSanitizerTest.kt
SafeEventMetadataTest.kt
```

---

## Required behavior tests

### Safe operation handle

```text
real_safe_sink_operation_handle_success_emits_terminal_event
real_safe_sink_operation_handle_cancelled_emits_terminal_event
real_safe_sink_operation_handle_failed_final_emits_terminal_event
real_safe_sink_operation_handle_direct_terminal_then_cancelled_has_one_terminal
real_safe_sink_operation_handle_cancelled_then_success_has_one_terminal
real_safe_sink_operation_handle_increment_counts_in_terminal_metadata
```

### Operation run recorder

```text
room_operation_cancelled_terminal_event_has_reason_code
room_operation_failed_final_event_has_summary_metadata
operation_increment_failure_records_safe_sink
stale_recovery_event_insert_failure_records_safe_sink
```

### Restore journal/sink

```text
real_restore_diagnostics_sink_appends_to_restore_journal
real_restore_journal_transition_preserves_events
real_restore_journal_fail_preserves_events
real_restore_journal_commit_preserves_restart_required
restore_journal_atomic_rename_failure_is_reported
```

### Reset/import safety

Use a fake operation handle that records all calls and throws if called after destructive point.

```text
reset_database_after_delete_does_not_call_room_operation_handle
reset_database_success_journal_contains_full_stage_history
legacy_import_after_swap_does_not_call_room_operation_handle
legacy_import_post_swap_failure_writes_terminal_journal_event
legacy_import_rollback_failure_writes_critical_event
```

### Notification correlation

```text
notification_pipeline_parse_uses_listener_correlation
notification_pipeline_error_uses_listener_correlation
notification_repository_forwards_listener_correlation
notification_review_terminal_diagnostic_uses_listener_correlation
```

### Transaction correlation

```text
transaction_create_event_uses_request_correlation
transaction_create_side_effect_uses_request_correlation
transaction_update_event_uses_supplied_correlation
transaction_delete_event_uses_supplied_correlation
transaction_bulk_update_event_uses_supplied_correlation
transaction_update_delete_bulk_side_effects_use_supplied_correlation
```

### Metadata/debug

```text
put_hashed_unknown_hash_key_is_redacted
package_hash_plain_text_value_is_redacted
diagnostics_repository_recent_failures_includes_severity_only_safe_sink_failure
diagnostics_repository_never_returns_restore_internal_paths
```

---

## Test quality rules

```text
1. Prefer real production class + fake DAO/sink dependencies.
2. Avoid tests that only assert method/field existence.
3. Avoid helper methods that duplicate production logic.
4. Every bug fixed in PRs 1-6 must have a failing-before/fixed-after test.
5. Throwing fake DAOs/sinks should verify best-effort behavior.
6. Tests should inspect actual emitted events, reasonCode, isTerminal, correlationId, and metadata.
```

## Acceptance criteria

```text
The suite fails if:
- safe handle terminalOnce double-CAS bug returns,
- bank blocked sync emits duplicate terminals,
- reset/import call Room handle after DB replacement,
- restore journal loses events,
- transaction mutation correlation is dropped,
- debug outputs leak internal restore paths.
```

---

# Final issue-to-PR map

| Issue | PR | Priority |
|---|---:|---:|
| Safe handle terminal methods emit no event | PR 1 | Critical |
| Bank blocked sync terminal inconsistency | PR 1 | High |
| Room terminal event reason/summary missing | PR 2 | Medium/High |
| Stale recovery event insert failure Timber-only | PR 2 | Medium |
| Safe handle increment no-op | PR 2 | Low/Medium |
| Reset journal starts late | PR 3 | Medium/High |
| Legacy import post-swap failure lacks terminal event | PR 3 | High |
| RestoreJournal renameTo unchecked | PR 3 | Medium |
| Transaction update/delete/bulk correlation partial | PR 4 | Medium |
| Restore journal path privacy split | PR 5 | High |
| putHashed accepts unapproved keys | PR 6 | Low/Medium |
| Safe-sink severity-only failures missed | PR 6 | Low/Medium |
| Tests structural/mirror bugs | PR 7 | High regression risk |

---

# Definition of done

The durable diagnostics refactor is complete when:

```text
1. Safe operation handles emit exactly one terminal event for success/cancel/failure.
2. Bank restore-blocked sync ends with one terminal CANCELLED / RESTORE_BLOCKED event.
3. Room operation terminal events preserve reasonCode and safe summary metadata.
4. Operation increment and stale-recovery write failures are durable via safe sink.
5. resetDatabase success journal contains RESET_STARTED, MAINTENANCE_ENTERED, SAFETY_BACKUP_CREATED, LIVE_DB_DELETED, RESTART_REQUIRED.
6. legacy import post-swap failures emit terminal critical journal events.
7. RestoreJournal atomic writes/renames fail loudly instead of silently losing journals.
8. All transaction update/delete/bulk lifecycle and side-effect events can share caller correlation.
9. Restore recovery path data is physically separated from diagnostics data.
10. putHashed() rejects unapproved hash-like keys.
11. Recent failures include severity-only safe-sink failures.
12. Regression tests exercise production classes and catch the known failure modes.
```