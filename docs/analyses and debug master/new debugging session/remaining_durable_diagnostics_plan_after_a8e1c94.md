# Remaining Durable Diagnostics Implementation Plan

Target commit: `a8e1c94ee41cc05d19904921e1ba0a280d7907ee`

Scope: remaining durable diagnostics / lifecycle event issues after the DDL-016 deep-fix commit.

Primary remaining risk areas:

```text
1. Restore journal events are not truly durable/append-only.
2. Restore diagnostics still mix recovery-only data with privacy-safe diagnostics.
3. Restore operation runs can still be incomplete or misleading.
4. Safe operation handles can emit multiple terminal outcomes.
5. Operation run counter/recovery writes are not fully best-effort.
6. Notification and bank correlations still do not fully reach transaction lifecycle events.
7. Metadata sanitizer still has hash-value and unknown-object edge leaks.
8. Diagnostics trace/recent-failure support is incomplete.
9. Regression tests are still too structural and do not catch the critical behavior bugs.
```

---

# 0. Priority order

Implement in this order:

```text
PR 1  Restore journal correctness hotfix
PR 2  Restore safety/completeness/privacy
PR 3  Restore importer and trace robustness
PR 4  Operation handle terminal/idempotency cleanup
PR 5  Correlation propagation to transaction lifecycle
PR 6  Metadata final hardening
PR 7  DiagnosticsRepository recent-failure completeness
PR 8  Real regression/golden tests
```

Reason:

```text
Restore correctness is highest-risk because diagnostic bugs must never corrupt or misrepresent restore.
Operation terminal idempotency affects all batch operations.
Correlation and metadata issues are important but less immediately dangerous than restore safety.
```

---

# PR 1 — Restore journal correctness hotfix

## Issues fixed

```text
DDL-A8-01 RestoreDiagnosticsSink does not write to RestoreJournal
DDL-A8-02 RestoreJournal.appendEvent is not append-only
DDL-A8-03 RESTART_REQUIRED emitted after commitJournal
```

## Type

```text
Critical actual restore diagnostics bug
```

## Goal

Every restore stage must be written to the restore journal as an append-only history. Successful restore journal must contain the terminal event.

## Files

```text
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
```

Tests:

```text
RestoreDiagnosticsSinkTest.kt
RestoreJournalTest.kt
DatabaseBackupRepositoryRestoreDiagnosticsTest.kt
```

---

## Step 1.1 — Inject RestoreJournal into RestoreDiagnosticsSink

Current problem:

```text
RestoreDiagnosticsSink writes safe sink + OperationRunHandle.
It does not append to RestoreJournal.
```

Change constructor:

```kotlin
class RestoreDiagnosticsSink(
    private val operationRunHandle: OperationRunHandle?,
    private val restoreJournal: RestoreJournal,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val maintenanceMode: RestoreMaintenanceMode,
    private val correlationId: String,
    private val operationType: String,
    private val timeProvider: TimeProvider,
    private val metadataSanitizer: EventMetadataSanitizer
)
```

In `event(...)`, always write journal first/best-effort:

```kotlin
runCatching {
    restoreJournal.appendEvent(
        correlationId = correlationId,
        stage = stage,
        outcome = outcome,
        severity = severity,
        reasonCode = reasonCode,
        metadata = metadata,
        exception = exception,
        isTerminal = isTerminal
    )
}.onFailure { journalError ->
    safeSink.recordDiagnosticEvent(
        event = DiagnosticEvent(
            pipeline = AppPipeline.BACKUP_RESTORE,
            stage = "restore_journal_append_failed",
            outcome = EventOutcome.SIDE_EFFECT_FAILED,
            severity = EventSeverity.ERROR,
            reasonCode = DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
            correlationId = correlationId,
            metadata = SafeEventMetadata.builder()
                .put("operationType", operationType)
                .put("failedStage", stage)
                .build(),
            exception = journalError,
            isTerminal = false
        ),
        mode = maintenanceMode.currentMode(),
        writeFailure = journalError
    )
}
```

Then:

```text
if roomOperationEventsAllowed:
    best-effort operationRunHandle.event(...)
always:
    best-effort safeSink.recordDiagnosticEvent(...)
```

## Step 1.2 — Make RestoreJournal.appendEvent truly append-only

Current bug:

```text
appendEvent reads JournalEntry.
JournalEntry does not contain events.
entry.toJson() loses old events.
Each append can overwrite previous events.
transitionTo/writeJournal can erase events.
```

Add raw JSON helpers:

```kotlin
private suspend fun readJournalJson(): JSONObject?
private suspend fun writeJournalJson(json: JSONObject)
private fun parseEvents(json: JSONObject): List<RestoreJournalEvent>
private fun serializeEvents(events: List<RestoreJournalEvent>): JSONArray
```

Implement append using raw JSON:

```kotlin
suspend fun appendEvent(...) {
    val json = readJournalJson() ?: return
    val existingEvents = parseEvents(json)
    val newEvent = RestoreJournalEvent(...)
    json.put("events", serializeEvents(existingEvents + newEvent))
    writeJournalJson(json)
}
```

## Step 1.3 — Preserve events through writeJournal / transitionTo

Update `writeJournal(entry)`:

```kotlin
private suspend fun writeJournalPreservingEvents(entry: JournalEntry) {
    val oldJson = readJournalJson()
    val oldEvents = oldJson?.optJSONArray("events")

    val newJson = entry.toJson()
    if (oldEvents != null) {
        newJson.put("events", oldEvents)
    }

    writeJournalJson(newJson)
}
```

Use this from:

```text
createJournal
transitionTo
failJournal
commitJournal preparation
```

## Step 1.4 — Move RESTART_REQUIRED before commitJournal

Current unsafe sequence:

```text
commitJournal()
transitionTo(COMPLETE)
event(RESTART_REQUIRED)
```

Fix sequence:

```kotlin
restoreEvents.event("LIVE_DB_VERIFIED", EventOutcome.COMPLETED)

restoreEvents.event(
    stage = "RESTART_REQUIRED",
    outcome = EventOutcome.COMPLETED,
    severity = EventSeverity.WARNING,
    isTerminal = true
)

journalEntry = restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.COMPLETE)

restoreJournal.commitJournal(journalEntry)

restoreMaintenanceMode.exit(forceRestartRequired = true)
```

Do not call `transitionTo()` after `commitJournal()`.

---

## Tests for PR 1

```text
restore_diagnostics_sink_appends_event_to_restore_journal
restore_journal_append_event_keeps_previous_events
restore_transition_to_preserves_events_array
restore_write_journal_preserves_events_array
restore_restart_required_written_before_commit_journal
restore_commit_journal_preserves_terminal_restart_required_event
restore_success_journal_has_full_stage_history
restore_success_does_not_recreate_active_complete_journal
```

## Acceptance criteria

```text
1. RestoreDiagnosticsSink writes every event to RestoreJournal.
2. RestoreJournal events are append-only.
3. transitionTo/writeJournal never erase events.
4. Success journal contains RESTART_REQUIRED terminal event.
```

---

# PR 2 — Restore safety, failure finalization, and privacy

## Issues fixed

```text
DDL-A8-04 restore still calls run.failedFinal after swap starts
DDL-A8-05 pre-swap restore failures incompletely finalized
DDL-A8-06 restore journal still stores full internal paths in diagnostics JSON
```

## Type

```text
High restore safety bug
High privacy bug
Operation-run completeness bug
```

## Files

```text
DatabaseBackupRepositoryImpl.kt
RestoreDiagnosticsSink.kt
RestoreJournal.kt
RestoreJournalPrivacyTest.kt
```

---

## Step 2.1 — Remove all OperationRunHandle calls after swap starts

After this call:

```kotlin
restoreEvents.markLiveDbSwapStarted()
```

forbid:

```kotlin
run.event(...)
run.failedFinal(...)
run.success()
run.cancelled()
run.partialSuccess(...)
```

In swap failure catch, replace:

```kotlin
run.failedFinal("Database swap failed", e)
```

with:

```kotlin
restoreEvents.event(
    stage = "LIVE_DB_SWAP_FAILED",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.ERROR,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = e,
    isTerminal = true
)
```

This writes journal/safe sink only after `markLiveDbSwapStarted()`.

## Step 2.2 — Add one helper for pre-swap restore failure

Create:

```kotlin
private suspend fun failRestoreBeforeSwap(
    restoreEvents: RestoreDiagnosticsSink,
    journalEntry: RestoreJournal.JournalEntry,
    stage: String,
    message: String,
    reasonCode: DiagnosticReasonCode,
    error: Throwable? = null
): Result<DatabaseImportResult> {
    restoreEvents.event(
        stage = stage,
        outcome = EventOutcome.FAILED_FINAL,
        severity = EventSeverity.ERROR,
        reasonCode = reasonCode,
        exception = error,
        isTerminal = true
    )

    restoreEvents.finalizeRunFailed(message, error)

    restoreJournal.failJournal(journalEntry, message, error)

    restoreMaintenanceMode.exit(forceRestartRequired = false)

    return Result.failure(error ?: IllegalStateException(message))
}
```

Use this helper for every pre-swap failure:

```text
wrong password / extraction failed
empty manifest
bundle validation failed
staged quick verification failed
staged migration failed
post-migration verification failed
safety backup failed
safety backup path unavailable
```

## Step 2.3 — Split recovery journal from diagnostics journal

Current problem:

```text
JournalEntry.toJson() stores _sourceBackupPath, _stagedDbPath, etc.
Same file is used as durable diagnostics.
```

Implement two models/files:

```text
restore_recovery_journal.json
restore_diagnostics_journal.json
```

### Recovery journal

May contain:

```text
sourceBackupPath
stagedDbPath
safetyBackupPath
liveDbPath
assetTargetPath
```

Rules:

```text
internal only
never exposed by DiagnosticsRepository
never included in support export
used only for crash recovery / cleanup / rollback
```

### Diagnostics journal

May contain only:

```text
sourceBackupName
sourceBackupPathHash
stagedDbPathHash
safetyBackupPathHash
liveDbPathHash
assetDisplayName
assetRelativePathHash
operationCorrelationId
events[]
status
startedAt
finishedAt
```

## Step 2.4 — If full split is too large, add strict bridge now

Short-term bridge:

```text
Keep _...Path only in recovery-only section.
DiagnosticsRepository must strip all fields starting with "_".
Support export must strip all fields starting with "_".
Tests must assert no /data/, /storage/, C:\, file:// in diagnostics output.
```

But final architecture should still split the files.

## Tests for PR 2

```text
swap_failure_after_mark_swap_started_does_not_call_room_run_failed_final
swap_failure_writes_journal_and_safe_sink_event
restore_post_migration_verification_failed_finalizes_operation_run
restore_staged_migration_failed_finalizes_operation_run
restore_safety_backup_failed_finalizes_operation_run
restore_safety_backup_missing_path_finalizes_operation_run
restore_diagnostics_journal_has_no_full_paths
restore_recovery_journal_roundtrips_full_paths
diagnostics_trace_never_exposes_internal_path_fields
support_export_never_includes_internal_path_fields
asset_restore_recovery_keeps_real_target_path_in_recovery_journal
```

## Acceptance criteria

```text
1. No Room operation handle is touched after swap starts.
2. Every pre-swap failure writes FAILED_FINAL and finalizes operation run.
3. Privacy-safe diagnostics never contain full local paths.
4. Recovery still has the path data it needs.
```

---

# PR 3 — Restore importer and trace robustness

## Issues fixed

```text
DDL-A8-07 importer permanently skips missing events
DDL-A8-08 success journal not marked imported
DDL-A8-19 trace reads only active journal
```

## Type

```text
Support/debug correctness gap
Restore trace durability gap
```

## Files

```text
RestoreJournalImporter.kt
RestoreJournal.kt
DiagnosticsRepositoryImpl.kt
OperationRunDao.kt
OperationRunEventDao.kt
OperationRunEvent.kt
```

---

## Step 3.1 — Add eventId to restore journal events

Ensure each `RestoreJournalEvent` has:

```kotlin
val eventId: String
```

This must survive JSON roundtrip.

## Step 3.2 — Add eventId to OperationRunEvent or unique import metadata

Best option:

```kotlin
@Entity(...)
data class OperationRunEvent(
    ...
    val eventId: String?
)
```

Migration:

```sql
ALTER TABLE operation_run_events ADD COLUMN eventId TEXT;
CREATE INDEX IF NOT EXISTS index_operation_run_events_eventId
ON operation_run_events(eventId);
```

If schema change is too much, store eventId in metadata and query by metadata is poor. Prefer column.

## Step 3.3 — Make importer idempotent per event

Current bad logic:

```kotlin
if (operationRunDao.getByCorrelationId(correlationId) != null) return
```

Replace with:

```kotlin
val run = operationRunDao.getByCorrelationId(correlationId)
    ?: insertOperationRunFromJournal(journal)

for (event in journal.events) {
    if (!operationRunEventDao.existsByEventId(event.eventId)) {
        operationRunEventDao.insert(event.toOperationRunEvent(run.id))
    }
}
```

If one event insert fails:

```text
do not mark journal imported
next startup retries missing events
```

## Step 3.4 — Mark success journal imported only after full import

Add:

```kotlin
restoreJournal.markLastSuccessImported(correlationId)
```

Implementation options:

```text
rename restore_journal_last_success.json -> restore_journal_last_success.imported.json
or add "importedAt": timestamp
```

Preferred:

```text
add importedAt to diagnostics journal
```

Do not delete immediately unless retention policy says so.

## Step 3.5 — DiagnosticsRepository should read all journal locations

Add API:

```kotlin
suspend fun getAllDiagnosticEventsByCorrelationId(
    correlationId: String
): List<RestoreJournalEvent>
```

It should read:

```text
active restore diagnostics journal
last success journal
last failure journal
imported-but-retained success journal
```

Then in trace:

```kotlin
restoreJournalEvents = restoreJournal.getAllDiagnosticEventsByCorrelationId(correlationId)
```

## Tests for PR 3

```text
restore_import_retries_missing_events_when_run_exists
restore_import_partial_event_failure_is_retried_next_startup
restore_import_is_idempotent_per_event_id
restore_success_journal_marked_imported_after_full_import
restore_import_does_not_mark_imported_if_any_event_insert_fails
trace_by_correlation_includes_active_journal_events
trace_by_correlation_includes_success_journal_events
trace_by_correlation_includes_failure_journal_events
trace_by_correlation_excludes_recovery_path_fields
```

## Acceptance criteria

```text
1. Importer never permanently skips missing events.
2. Success journal is marked imported only after full import.
3. Diagnostics trace can find active/success/failure restore events.
```

---

# PR 4 — Operation handle terminal/idempotency cleanup

## Issues fixed

```text
DDL-A8-09 safe operation handles can emit multiple terminal events
DDL-A8-10 operation increments can fail business operations
DDL-A8-11 stale recovery event insert is not best-effort
DDL-A8-16 bank blocked sync can produce multiple terminal events in safe mode
```

## Type

```text
Batch operation diagnostic correctness/reliability
```

## Files

```text
CompositeOperationRunRecorder.kt
OperationRunRecorder.kt
SafeSinkOperationRunHandle.kt
BankApiIntegration.kt
```

---

## Step 4.1 — Add terminal state to OperationRunHandle

Update interface:

```kotlin
interface OperationRunHandle {
    val runId: Long
    val correlationId: String
    val isTerminal: Boolean

    suspend fun event(...)
    suspend fun increment(...)
    suspend fun success()
    suspend fun partialSuccess(summary: String?)
    suspend fun failedFinal(reason: String, error: Throwable?)
    suspend fun failedRetryable(reason: String, error: Throwable?)
    suspend fun cancelled(reason: String?)
}
```

## Step 4.2 — Room handle terminal state

For Room handle:

```kotlin
override val isTerminal: Boolean
    get() = terminalState.get()
```

Set terminal true only when `finalizeIfRunning(...) > 0`.

If status already terminal, set local terminal true after detecting update result 0 and optional DB check.

## Step 4.3 — Safe handle terminal-once

In `SafeSinkOperationRunHandle`:

```kotlin
private val terminal = AtomicBoolean(false)

override val isTerminal: Boolean
    get() = terminal.get()

private suspend fun terminalOnce(
    stage: String,
    outcome: EventOutcome,
    reasonCode: DiagnosticReasonCode?,
    severity: EventSeverity,
    metadata: SafeEventMetadata,
    exception: Throwable? = null
) {
    if (!terminal.compareAndSet(false, true)) return

    event(
        stage = stage,
        outcome = outcome,
        severity = severity,
        reasonCode = reasonCode,
        metadata = metadata,
        exception = exception,
        isTerminal = true
    )
}
```

Use for:

```text
success
partialSuccess
failedFinal
failedRetryable
cancelled
```

## Step 4.4 — runOperation should not success after manual terminal

Change:

```kotlin
val result = block(handle)
handle.success()
return result
```

to:

```kotlin
val result = block(handle)
if (!handle.isTerminal) {
    handle.success()
}
return result
```

## Step 4.5 — Make increment best-effort

In Room handle:

```kotlin
override suspend fun increment(...) {
    runCatching {
        runDao.incrementCounters(...)
    }.onFailure { error ->
        safeSink.recordDiagnosticEvent(
            event = DiagnosticEvent(
                pipeline = pipelineForOperation(operationType),
                stage = "operation_increment_failed",
                outcome = EventOutcome.SIDE_EFFECT_FAILED,
                severity = EventSeverity.WARNING,
                reasonCode = DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
                correlationId = correlationId,
                metadata = SafeEventMetadata.builder()
                    .put("operationType", operationType)
                    .build(),
                exception = error,
                isTerminal = false
            ),
            mode = maintenanceMode.currentMode(),
            writeFailure = error
        )
    }
}
```

## Step 4.6 — Make stale recovery event insert best-effort

In `recoverStaleRunningOperationRuns()`:

```kotlin
val updated = runDao.finalizeIfRunning(...)
if (updated > 0) {
    runCatching {
        eventDao.insert(...)
    }.onFailure { error ->
        safeSink.recordDiagnosticEvent(...)
    }
}
```

## Step 4.7 — Simplify bank blocked sync terminal flow

Bank blocked path can remain:

```kotlin
run.event("WRITE_BARRIER", BLOCKED, isTerminal = false)
run.cancelled(RESTORE_BLOCKED)
return@runOperation blockedResult
```

Do not make `WRITE_BARRIER` terminal if `cancelled()` is terminal. Prefer exactly one terminal event.

## Tests for PR 4

```text
safe_handle_cancelled_then_run_operation_success_emits_only_cancelled
safe_handle_partial_success_then_success_emits_only_partial_success
safe_handle_failed_then_success_emits_only_failed
room_handle_manual_terminal_prevents_run_operation_success
operation_increment_failure_does_not_fail_bank_sync
operation_increment_failure_records_safe_diagnostic
stale_recovery_status_update_survives_event_insert_failure
bank_sync_restore_blocked_safe_handle_has_only_one_terminal_status
```

## Acceptance criteria

```text
1. Safe handles emit exactly one terminal outcome.
2. runOperation does not add SUCCESS after manual terminal.
3. Counter/recovery diagnostic failures do not fail user operations or startup.
```

---

# PR 5 — Correlation propagation to transaction lifecycle

## Issues fixed

```text
DDL-A8-14 notification correlation not propagated to repository/domain lifecycle
DDL-A8-15 bank correlation does not reach TransactionEvent
```

## Type

```text
Traceability architecture gap
Medium/high support impact
```

## Files

```text
NotificationCaptureService.kt
NotificationRepository.kt
NotificationProcessingPipeline.kt
CreateExpenseRequest.kt
TransactionLifecycleCoordinator.kt
TransactionEvent.kt
AppDatabase.kt
migrations
TransactionEventDao.kt
```

---

## Step 5.1 — Add correlation fields to transaction_events

Modify `TransactionEvent`:

```kotlin
val correlationId: String? = null,
val causationId: String? = null
```

Migration:

```sql
ALTER TABLE transaction_events ADD COLUMN correlationId TEXT;
ALTER TABLE transaction_events ADD COLUMN causationId TEXT;

CREATE INDEX IF NOT EXISTS index_transaction_events_correlationId
ON transaction_events(correlationId);
```

If DB version already advanced in the recent commits, increment again.

## Step 5.2 — Update transaction writer/coordinator

Where transaction events are created:

```kotlin
TransactionEvent(
    ...
    correlationId = request.correlationId ?: correlationId,
    causationId = request.causationId
)
```

Boundary rule:

```text
If request correlation exists, use it.
If absent, generate once at transaction boundary and reuse for attempted/created/side-effects.
```

## Step 5.3 — Propagate notification correlation to repository

Change service call:

```kotlin
repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId = correlationId
)
```

Update downstream method signatures.

Propagation targets:

```text
raw notification processing diagnostic
pending review creation
CreateExpenseRequest
TransactionLifecycleCoordinator
TransactionSideEffectDispatcher
```

## Step 5.4 — Propagate bank correlation to transaction lifecycle

Bank already does roughly:

```kotlin
mapTransactionToExpense(...).copy(correlationId = run.correlationId)
```

Now ensure:

```text
CreateExpenseRequest.correlationId reaches TransactionEvent.correlationId
side effects use same correlation
duplicates use same correlation
validation failures use same correlation
```

## Step 5.5 — Add optional causation ID

If operation event created expense, causation can be the operation event ID.

Short-term:

```text
causationId = run.correlationId or null
```

Long-term:

```text
causationId = operationRunEvent.eventId
```

## Tests for PR 5

```text
notification_success_review_created_uses_listener_correlation
notification_success_expense_created_uses_listener_correlation
notification_side_effect_uses_listener_correlation
bank_transaction_imported_event_and_transaction_created_event_share_correlation
transaction_create_attempted_uses_request_correlation
transaction_created_uses_request_correlation
transaction_validation_failed_uses_request_correlation
transaction_duplicate_uses_request_correlation
```

## Acceptance criteria

```text
1. Notification -> review/expense/side-effect trace uses one correlation ID.
2. Bank sync -> transaction lifecycle trace uses one correlation ID.
3. transaction_events are queryable by correlationId.
```

---

# PR 6 — Metadata sanitizer final hardening

## Issues fixed

```text
DDL-A8-17 exact safe hash keys do not validate hash-looking values
DDL-A8-18 unknown object toString not fully sanitized in sanitizeValue
```

## Type

```text
Privacy hardening
```

## Files

```text
EventMetadataSanitizer.kt
SafeEventMetadata.kt
EventMetadataSanitizerTest.kt
SafeEventMetadataTest.kt
```

---

## Step 6.1 — Validate values for safe hash keys

Add:

```kotlin
private val HASH_VALUE_PATTERN = Regex("^[a-fA-F0-9]{8,128}$")
```

In sanitize logic:

```kotlin
private fun isSafeHashValue(value: Any?): Boolean {
    return value is String && HASH_VALUE_PATTERN.matches(value)
}
```

For exact safe hash keys:

```kotlin
if (canonical in SAFE_HASH_KEYS) {
    return if (isSafeHashValue(value)) value else REDACTED
}
```

Important:

```text
sourceIdHash with plain text must be redacted.
providerTransactionIdHash with plain text must be redacted.
putHashed() should produce valid hash and be allowed.
```

## Step 6.2 — Route unknown object through full string sanitizer

Find any branch like:

```kotlin
else -> value.toString().take(MAX_STRING_LENGTH)
```

Replace with:

```kotlin
else -> sanitizeStringValue(value.toString())
```

This should apply in both:

```text
sanitizeAny
sanitizeValue
SafeEventMetadata builder paths
```

## Step 6.3 — Add strict tests for object leaks

Create fake object:

```kotlin
private class SensitiveToString {
    override fun toString(): String =
        "token=Bearer abc.def.ghi path=/storage/emulated/0/private.txt iban=GB82WEST12345698765432"
}
```

Expected sanitized output must not contain:

```text
Bearer
/storage
private.txt
GB82
```

## Tests for PR 6

```text
source_id_hash_plain_text_value_is_redacted
provider_transaction_id_hash_plain_text_value_is_redacted
source_id_hash_hex_value_is_allowed
put_hashed_source_id_hash_is_allowed
safe_metadata_unknown_object_to_string_path_is_sanitized
safe_metadata_unknown_object_to_string_token_is_sanitized
safe_metadata_unknown_object_to_string_iban_is_sanitized
```

## Acceptance criteria

```text
1. Hash-key names cannot smuggle raw values.
2. Unknown object toString cannot leak tokens/paths/accounts.
```

---

# PR 7 — DiagnosticsRepository recent-failure completeness

## Issues fixed

```text
DDL-A8-20 getRecentFailures excludes safe-sink, operation-run, journal failures
```

## Type

```text
Support tooling gap
```

## Files

```text
DiagnosticsRepository.kt
DiagnosticsRepositoryImpl.kt
PipelineDiagnosticEventDao.kt
OperationRunEventDao.kt
BackgroundJobRunDao.kt
MaintenanceSafeDiagnosticSink.kt
RestoreJournal.kt
```

---

## Step 7.1 — Add failure summary model

```kotlin
data class DiagnosticFailureSummary(
    val source: String,
    val correlationId: String?,
    val pipelineOrOperation: String?,
    val stage: String,
    val outcome: String,
    val severity: String,
    val reasonCode: String?,
    val occurredAt: Long,
    val entityType: String?,
    val entityId: Long?,
    val messageSafe: String?
)
```

## Step 7.2 — Combine failure sources

`getRecentFailures(limit)` should merge:

```text
pipeline_diagnostic_events
operation_run_events
background_job_runs failed/retry/cancelled/stale
maintenance safe sink records
restore journal events
```

Failure criteria:

```text
severity in WARNING/ERROR/CRITICAL
or outcome in FAILED_RETRYABLE, FAILED_FINAL, BLOCKED, DROPPED, CANCELLED, SIDE_EFFECT_FAILED
or worker status in FAILED, RETRY, CANCELLED, STALE_ABORTED
or operation status in FAILED_RETRYABLE, FAILED_FINAL, CANCELLED, STALE_ABORTED, PARTIAL_SUCCESS
```

Sort descending by `occurredAt`.

Apply final `limit`.

## Step 7.3 — Ensure no recovery paths leak

When mapping restore journal events:

```text
use diagnostics journal only
strip fields starting with "_"
sanitize metadata again
```

## Tests for PR 7

```text
recent_failures_includes_pipeline_failures
recent_failures_includes_operation_run_event_failures
recent_failures_includes_worker_failures
recent_failures_includes_safe_sink_failures
recent_failures_includes_restore_journal_failures
recent_failures_sorted_by_occurred_at_desc
recent_failures_never_exposes_recovery_paths
```

## Acceptance criteria

```text
Support/debug recent failures show all important failures, not only pipeline_diagnostic_events.
```

---

# PR 8 — Real regression/golden tests

## Type

```text
Regression lock
```

Current tests are useful but too structural. Add behavior tests that would have caught the bugs from this review.

## Required test groups

### Restore journal behavior

```text
restore_diagnostics_sink_appends_to_journal
restore_journal_append_keeps_previous_events
restore_transition_to_preserves_events
restore_commit_journal_contains_restart_required
restore_success_journal_has_full_stage_history
```

### Restore safety

```text
restore_after_swap_does_not_call_room_operation_run_event
swap_failure_after_mark_swap_started_does_not_call_room_run_failed_final
restore_success_not_rolled_back_when_operation_event_insert_fails
```

### Restore importer

```text
restore_import_imports_events_from_success_journal
restore_import_retries_missing_events_when_run_exists
restore_import_does_not_mark_imported_if_any_event_insert_fails
```

### Operation handles

```text
safe_handle_cancelled_then_success_has_one_terminal
safe_handle_partial_success_then_success_has_one_terminal
operation_increment_failure_does_not_fail_business_flow
stale_recovery_status_update_survives_event_insert_failure
```

### Metadata

```text
source_id_hash_plain_text_value_is_redacted
provider_transaction_id_hash_plain_text_value_is_redacted
unknown_object_to_string_token_path_iban_are_redacted
```

### Notification/bank correlation

```text
notification_repository_receives_correlation
notification_expense_transaction_event_uses_listener_correlation
bank_expense_transaction_event_uses_bank_correlation
```

### Diagnostics repository

```text
trace_by_correlation_includes_success_journal_events
trace_by_correlation_includes_failure_journal_events
recent_failures_includes_safe_sink_and_restore_journal_failures
```

## Acceptance criteria

```text
Tests must exercise real implementations or realistic fakes.
Avoid tests that only assert “API exists”.
Every critical bug from this review should have a failing-before/fixed-after regression test.
```

---

# Final issue map

| Issue | PR | Priority | Type |
|---|---:|---:|---|
| RestoreDiagnosticsSink does not append to journal | PR 1 | Critical | Actual restore diagnostics bug |
| RestoreJournal append loses previous events | PR 1 | Critical | Actual journal bug |
| transitionTo/writeJournal erase events | PR 1 | Critical | Actual journal bug |
| RESTART_REQUIRED after commitJournal | PR 1 | High | Restore trace bug |
| run.failedFinal after swap starts | PR 2 | High | Restore safety bug |
| pre-swap failures not finalized | PR 2 | High | Operation completeness bug |
| full paths in diagnostics journal | PR 2 | High | Privacy bug |
| importer skips missing events | PR 3 | Medium/High | Trace import bug |
| success journal not marked imported | PR 3 | Medium | Cleanup/support gap |
| trace reads only active journal | PR 3 | Medium | Support gap |
| safe handle multiple terminal events | PR 4 | Medium/High | Diagnostic correctness bug |
| increment can fail business operation | PR 4 | Medium | Reliability bug |
| stale recovery event insert can throw | PR 4 | Low/Medium | Startup resilience bug |
| notification correlation not propagated | PR 5 | Medium/High | Traceability gap |
| bank correlation not in TransactionEvent | PR 5 | Medium/High | Traceability gap |
| hash key plain value leak | PR 6 | Medium | Privacy hardening |
| unknown object toString leak | PR 6 | Medium | Privacy hardening |
| recent failures incomplete | PR 7 | Low/Medium | Support tooling gap |
| tests too structural | PR 8 | High | Regression risk |

---

# Definition of done

The remaining durable diagnostics work is complete when:

```text
1. RestoreDiagnosticsSink writes every stage to RestoreJournal.
2. RestoreJournal events are append-only and survive transitions/commit.
3. RESTART_REQUIRED is inside the committed success journal.
4. No Room operation handle is used after DB swap starts.
5. Every pre-swap restore failure finalizes operation run as FAILED_FINAL.
6. Diagnostics journal/debug trace never exposes full local paths.
7. Restore importer retries missing events and marks imported only after full success.
8. Safe operation handles emit exactly one terminal event.
9. Operation increments and stale-recovery event inserts are best-effort.
10. notification -> transaction and bank -> transaction share correlationId.
11. transaction_events are queryable by correlationId.
12. Metadata hash keys validate hash-looking values.
13. Unknown object metadata is fully sanitized.
14. Recent failures aggregate pipeline, operation, worker, safe-sink, and restore journal failures.
15. Regression tests prove the actual behavior, not just API presence.
```