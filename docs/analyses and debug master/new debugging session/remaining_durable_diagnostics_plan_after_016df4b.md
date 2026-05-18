# Remaining Durable Diagnostics Implementation Plan

Target commit: `016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e`

Scope: remaining issues from the deep review after the durable diagnostics deep-fix PRs.

Main remaining risk areas:

```text
1. Restore/backup diagnostics can still affect restore correctness.
2. OperationRun event writes are not fully best-effort.
3. Restore journal mixes recovery paths with diagnostics and lacks append-only stage history.
4. Some metadata/privacy edge cases remain.
5. Notification/bank success paths still need better correlation propagation.
6. Debug trace repository has correctness/DI issues.
```

---

# 0. Priority order

Implement in this order:

```text
PR 1  Restore operation safety hotfix
PR 2  Restore journal split, append-only events, and importer
PR 3  OperationRunRecorder reliability cleanup
PR 4  Metadata sanitizer final edge hardening
PR 5  Notification + bank trace/correlation completion
PR 6  DiagnosticsRepository correctness and DI
PR 7  Golden tests / regression locks
```

Why this order:

```text
Restore safety comes first because diagnostic writes must never break or roll back a successful restore.
Operation event best-effort behavior is foundational for backup/bank/export/import.
Journal privacy/recovery must be fixed before exposing restore traces.
Metadata edge fixes protect all later diagnostics.
Correlation work is important but less dangerous than restore correctness.
```

---

# PR 1 — Restore operation safety hotfix

## Issues fixed

```text
DDL-016-01  restore still writes OperationRun events after DB swap
DDL-016-02  OperationRunHandle.event() is not best-effort
DDL-016-18  restore pre-swap failures do not consistently finalize operation run
```

## Type

```text
Critical actual user-impacting bug
```

## Goal

After the live DB swap starts, restore must never depend on Room diagnostics.

Diagnostic event failure must not:

```text
fail backup
fail bank sync
fail restore
enter rollback after successful restore
mask the real restore result
```

---

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSink.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryRestoreDiagnosticsTest.kt
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorderTest.kt
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorderTest.kt
```

---

## Step 1.1 — Add restore diagnostic sink wrapper

Create a helper owned by restore flow:

```kotlin
private class RestoreDiagnosticsSink(
    private val operationRunHandle: OperationRunHandle?,
    private val restoreJournal: RestoreJournal,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val correlationId: String,
    private val operationType: String,
    private val timeProvider: TimeProvider
) {
    private var roomOperationEventsAllowed: Boolean = true

    fun markLiveDbSwapStarted() {
        roomOperationEventsAllowed = false
    }

    suspend fun event(
        stage: String,
        outcome: EventOutcome,
        severity: EventSeverity = EventSeverity.INFO,
        reasonCode: DiagnosticReasonCode? = null,
        metadata: SafeEventMetadata = SafeEventMetadata.empty(),
        exception: Throwable? = null,
        isTerminal: Boolean = false
    ) {
        // Always append to restore journal.
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

        // Room operation events only before DB swap.
        if (roomOperationEventsAllowed && operationRunHandle != null) {
            runCatching {
                operationRunHandle.event(
                    stage = stage,
                    outcome = outcome,
                    severity = severity,
                    reasonCode = reasonCode,
                    metadata = metadata,
                    exception = exception,
                    isTerminal = isTerminal
                )
            }.onFailure { eventFailure ->
                safeSink.recordDiagnosticEvent(
                    event = DiagnosticEvent(
                        pipeline = AppPipeline.BACKUP_RESTORE,
                        stage = "operation_event_write_failed",
                        outcome = EventOutcome.SIDE_EFFECT_FAILED,
                        severity = EventSeverity.WARNING,
                        reasonCode = DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
                        correlationId = correlationId,
                        metadata = SafeEventMetadata.builder()
                            .put("operationType", operationType)
                            .put("failedStage", stage)
                            .build(),
                        exception = eventFailure,
                        isTerminal = false
                    ),
                    mode = RestoreMaintenanceMode.Mode.NORMAL,
                    writeFailure = eventFailure
                )
            }
        }
    }
}
```

If exact `RestoreMaintenanceMode.Mode` enum differs, use the real current mode accessor.

---

## Step 1.2 — Replace post-swap `run.event(...)`

In `DatabaseBackupRepositoryImpl.restoreCostBackup()` find the destructive point:

```text
close live DB
copy staged DB to live DB
live DB swapped
```

After this point, remove all direct calls:

```kotlin
run.event("LIVE_DB_VERIFIED", ...)
run.event("RESTART_REQUIRED", ...)
run.event("ROLLBACK_STARTED", ...)
run.event("ROLLBACK_FAILED", ...)
run.event("ROLLBACK_COMPLETED", ...)
run.success()
run.failedFinal(...)
run.cancelled(...)
```

Replace with:

```kotlin
restoreDiagnostics.markLiveDbSwapStarted()

restoreDiagnostics.event("LIVE_DB_SWAPPED", EventOutcome.COMPLETED)
restoreDiagnostics.event("LIVE_DB_VERIFIED", EventOutcome.COMPLETED)
restoreDiagnostics.event(
    stage = "RESTART_REQUIRED",
    outcome = EventOutcome.COMPLETED,
    severity = EventSeverity.WARNING,
    isTerminal = true
)
```

Rollback after swap must also be journal/safe-sink only:

```kotlin
restoreDiagnostics.event(
    stage = "ROLLBACK_FAILED",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.CRITICAL,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = rollbackError,
    isTerminal = true
)
```

---

## Step 1.3 — Make `OperationRunHandle.event()` best-effort

In `RoomOperationRunRecorder.Handle.event(...)`, wrap the insert:

```kotlin
override suspend fun event(...) {
    val operationEvent = buildOperationRunEvent(...)

    runCatching {
        eventDao.insert(operationEvent)
    }.onFailure { error ->
        safeSink.recordDiagnosticEvent(
            event = DiagnosticEvent(
                pipeline = pipelineForOperation(operationType),
                stage = "operation_event_write_failed",
                outcome = EventOutcome.SIDE_EFFECT_FAILED,
                severity = EventSeverity.WARNING,
                reasonCode = DiagnosticReasonCode.SIDE_EFFECT_EXCEPTION,
                correlationId = correlationId,
                metadata = SafeEventMetadata.builder()
                    .put("operationType", operationType)
                    .put("failedStage", stage)
                    .build(),
                exception = error,
                isTerminal = false
            ),
            mode = restoreMaintenanceMode.currentMode(),
            writeFailure = error
        )
        Timber.w(error, "Failed to write operation run event")
    }
}
```

Important rule:

```text
Default event(...) must not throw.
```

If strict behavior is needed later, add:

```kotlin
eventStrict(...)
```

but do not use it in normal app flows.

---

## Step 1.4 — Ensure pre-swap restore failures finalize operation run

Before DB swap, live DB is still intact. For each early failure:

```text
wrong password / extraction failed
empty backup
bundle validation failed
staged DB verification failed
staged DB migration failed
post-migration verification failed
safety backup failed
```

record both:

```text
restore journal failure
Room operation run FAILED_FINAL if Room still safe
```

Pattern:

```kotlin
suspend fun failRestoreBeforeSwap(
    stage: String,
    reasonCode: DiagnosticReasonCode,
    message: String,
    error: Throwable? = null
): Result<RestoreResult> {
    restoreDiagnostics.event(
        stage = stage,
        outcome = EventOutcome.FAILED_FINAL,
        severity = EventSeverity.ERROR,
        reasonCode = reasonCode,
        exception = error,
        isTerminal = true
    )

    runCatching {
        run.failedFinal(message, error)
    }.onFailure {
        safeSink.recordDiagnosticEvent(...)
    }

    restoreJournal.failJournal(message, error)
    restoreMaintenanceMode.exit(...)
    return Result.failure(error ?: IllegalStateException(message))
}
```

---

## Tests for PR 1

Required:

```text
restore_after_swap_does_not_call_room_operation_run_event
restore_success_not_rolled_back_when_operation_event_insert_fails
restore_restart_required_written_to_journal_only
rollback_failed_event_written_to_journal_not_room_after_swap
operation_intermediate_event_failure_does_not_fail_business_operation
operation_intermediate_event_failure_goes_to_safe_sink
backup_stage_event_failure_does_not_abort_backup
bank_transaction_event_failure_does_not_abort_sync
restore_wrong_password_finalizes_operation_run_failed
restore_empty_backup_finalizes_operation_run_failed
restore_staged_verification_failed_finalizes_operation_run_failed
restore_safety_backup_failed_finalizes_operation_run_failed
```

---

## Acceptance criteria

```text
1. No Room operation-run write occurs after live DB swap starts.
2. A failed operation event insert cannot fail a user operation.
3. Successful restore cannot enter rollback because diagnostics failed.
4. All pre-swap restore failures finalize operation run as FAILED_FINAL.
```

---

# PR 2 — Restore journal split, append-only events, and import

## Issues fixed

```text
DDL-016-05  restore journal writes full paths and cannot read them back
DDL-016-06  restore journal is not append-only stage trail
DDL-016-07  restore success journal preserved but not imported/queryable
```

## Type

```text
Critical restore recovery bug
High privacy risk
Support/debug architecture gap
```

---

## Files

```text
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt
app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
app/src/main/java/com/yourname/expensetracker/domain/debug/DiagnosticsRepository.kt
app/src/main/java/com/yourname/expensetracker/data/debug/DiagnosticsRepositoryImpl.kt
```

Tests:

```text
RestoreJournalTest.kt
RestoreJournalPrivacyTest.kt
RestoreJournalImporterTest.kt
DatabaseBackupRepositoryRestoreDiagnosticsTest.kt
DiagnosticsRepositoryTest.kt
```

---

## Step 2.1 — Split recovery journal from diagnostics journal

Create two concepts:

```text
restore_recovery_journal.json
restore_diagnostics_journal.json
```

### Recovery journal

Purpose:

```text
crash recovery
rollback
cleanup
asset recovery
```

Allowed to contain internal full paths if necessary.

Must not be:

```text
exported
shown in debug diagnostics
included in support bundles
returned from DiagnosticsRepository
```

### Diagnostics journal

Purpose:

```text
privacy-safe support/debug trace
operation stage history
correlation lookup
```

Must not contain full paths.

Fields should be safe:

```text
operationCorrelationId
operationType
status
startedAt
finishedAt
sourceBackupName
sourceBackupHash
stagedDbPathHash
safetyBackupPathHash
liveDbPathHash
assetDisplayName
assetRelativePathHash
events[]
```

---

## Step 2.2 — Fix current JSON roundtrip immediately

If two-file split is too large for one PR, at minimum fix the current mismatch.

Current problem:

```text
toJson writes _sourceBackupPath
fromJson reads sourceBackupPath
```

Fix `fromJson()` to read both:

```kotlin
sourceBackupPath = json.optString("_sourceBackupPath")
    .ifBlank { json.optString("sourceBackupPath", null) }
```

Same for:

```text
_stagedDbPath
_safetyBackupPath
_liveDbPath
asset targetName / target
```

But this is only a bridge. The final design should still separate recovery and diagnostics privacy.

---

## Step 2.3 — Add append-only restore journal events

Add model:

```kotlin
data class RestoreJournalEvent(
    val eventId: String,
    val correlationId: String,
    val stage: String,
    val outcome: String,
    val severity: String,
    val reasonCode: String?,
    val occurredAt: Long,
    val metadataJson: String?,
    val exceptionClass: String?,
    val exceptionMessageSafe: String?,
    val isTerminal: Boolean
)
```

Add to diagnostics journal:

```kotlin
val events: List<RestoreJournalEvent>
```

Add APIs:

```kotlin
suspend fun appendEvent(
    correlationId: String,
    stage: String,
    outcome: EventOutcome,
    severity: EventSeverity = EventSeverity.INFO,
    reasonCode: DiagnosticReasonCode? = null,
    metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    exception: Throwable? = null,
    isTerminal: Boolean = false
)
```

Every restore stage must call this.

---

## Step 2.4 — Required restore stage trail

Write append-only journal events for:

```text
STARTED
MAINTENANCE_ENTERED
WORKERS_DRAINED
JOURNAL_CREATED
BUNDLE_EXTRACT_STARTED
BUNDLE_VALIDATED
STAGED_DB_CREATED
STAGED_DB_VERIFIED
STAGED_DB_MIGRATED
STAGED_DB_POST_MIGRATION_VERIFIED
SAFETY_BACKUP_CREATED
LIVE_DB_CLOSED
LIVE_DB_SWAPPING
LIVE_DB_SWAPPED
LIVE_DB_VERIFYING
LIVE_DB_VERIFIED
ASSETS_RESTORING
ASSET_RESTORED
ASSET_FAILED
JOURNAL_COMMITTED
RESTART_REQUIRED
COMPLETED
ROLLBACK_STARTED
ROLLBACK_COMPLETED
ROLLBACK_FAILED
FAILED
```

The exact sequence depends on actual restore path, but every major phase must append.

---

## Step 2.5 — Preserve and import successful restore journal

Current `commitJournal()` preserves:

```text
restore_journal_last_success.json
```

Keep it, but add importer.

Create:

```kotlin
class RestoreJournalImporter @Inject constructor(
    private val restoreJournal: RestoreJournal,
    private val operationRunDao: OperationRunDao,
    private val operationRunEventDao: OperationRunEventDao,
    private val timeProvider: TimeProvider
)
```

API:

```kotlin
suspend fun importLastSuccessJournalIfPresent()
```

Flow:

```text
1. read last-success diagnostics journal
2. if already imported, return
3. insert operation_runs row into restored DB
4. insert operation_run_events for each journal event
5. mark journal imported or delete only after successful import
```

Add idempotency:

```text
operation_runs.correlationId unique already exists
if exists, skip/import missing events only
```

---

## Step 2.6 — Include restore journal events in diagnostics trace

Extend `DiagnosticTrace`:

```kotlin
data class DiagnosticTrace(
    ...
    val restoreJournalEvents: List<RestoreJournalEvent>
)
```

In `DiagnosticsRepositoryImpl.getTraceByCorrelationId()` include:

```kotlin
restoreJournal.getEventsByCorrelationId(correlationId)
```

Do not expose recovery journal path fields.

---

## Tests for PR 2

Required:

```text
restore_journal_roundtrip_preserves_recovery_paths
restore_journal_roundtrip_preserves_asset_target_recovery_path
restore_diagnostics_journal_does_not_contain_full_paths
restore_debug_trace_does_not_expose_internal_path_fields
check_and_recover_can_clean_staging_after_roundtrip
restore_journal_contains_bundle_validated_event
restore_journal_contains_live_db_swapped_event
restore_journal_contains_restart_required_terminal_event
restore_journal_contains_rollback_failed_critical_event
restore_success_journal_has_complete_stage_history
restore_success_after_restart_imports_operation_run
restore_success_journal_deleted_only_after_successful_import
trace_by_correlation_includes_restore_journal_events
```

---

## Acceptance criteria

```text
1. Crash recovery can still use pathful recovery data.
2. Debug/support diagnostics never expose full local paths.
3. Restore has append-only stage history.
4. Successful restore trace survives DB swap and restart.
5. Successful restore journal is imported into restored DB or remains safely preserved.
```

---

# PR 3 — OperationRunRecorder reliability cleanup

## Issues fixed

```text
DDL-016-03  Room operation run orphaned if STARTED event insert fails
DDL-016-04  safe operation handle does not emit STARTED
DDL-016-08  DiagnosticEvent has no stable eventId
```

## Type

```text
Diagnostic consistency and traceability gap
```

---

## Files

```text
domain/diagnostics/DiagnosticEventWriter.kt
domain/diagnostics/DiagnosticEvent.kt
domain/diagnostics/OperationRunRecorder.kt
domain/diagnostics/CompositeOperationRunRecorder.kt
domain/diagnostics/SafeSinkOperationRunHandle.kt
data/backup/DataStoreMaintenanceSafeDiagnosticSink.kt
data/database/entity/PipelineDiagnosticEvent.kt
```

Tests:

```text
DiagnosticEventWriterTest.kt
OperationRunRecorderTest.kt
CompositeOperationRunRecorderTest.kt
SafeSinkOperationRunHandleTest.kt
```

---

## Step 3.1 — Add stable `eventId` to `DiagnosticEvent`

Update model:

```kotlin
data class DiagnosticEvent(
    val eventId: String = CorrelationIds.newId(),
    ...
)
```

Then:

```text
RoomDiagnosticEventWriter stores event.eventId
MaintenanceSafeDiagnosticSink stores event.eventId
Composite fallback preserves event.eventId
```

Do not generate separate IDs in writer unless old event has null, which should no longer happen.

---

## Step 3.2 — Make `RoomOperationRunRecorder.start()` resilient

Current risk:

```text
run row insert succeeds
STARTED event insert fails
Composite catches failure and creates safe handle with new correlation
old Room run remains RUNNING
```

Fix:

```kotlin
override suspend fun start(...): OperationRunHandle {
    val correlationId = CorrelationIds.newId()
    val id = runDao.insert(OperationRun(...))
    val handle = Handle(id, correlationId, ...)

    runCatching {
        handle.event(
            stage = "STARTED",
            outcome = EventOutcome.ATTEMPTED,
            metadata = metadata,
            isTerminal = false
        )
    }.onFailure { error ->
        safeSink.recordDiagnosticEvent(...)
        Timber.w(error, "Failed to write STARTED event for operation run")
    }

    return handle
}
```

After row insert succeeds, do not throw because of STARTED event failure.

---

## Step 3.3 — Safe handle emits STARTED

In `CompositeOperationRunRecorder.start()` when falling back to safe handle:

```kotlin
val handle = SafeSinkOperationRunHandle(...)
handle.event(
    stage = "STARTED",
    outcome = EventOutcome.ATTEMPTED,
    metadata = metadata,
    isTerminal = false
)
return handle
```

Ensure safe handle event includes:

```text
operationType
actor
correlationId
start metadata
```

---

## Step 3.4 — Ensure safe operation terminal status

`SafeSinkOperationRunHandle.success()`, `failedFinal()`, `failedRetryable()`, `cancelled()` must emit terminal safe-sink records with:

```text
isTerminal = true
operationType
status
correlationId
counts
summary/error
```

---

## Tests for PR 3

Required:

```text
diagnostic_event_has_stable_event_id
room_writer_uses_event_event_id
safe_sink_uses_event_event_id
operation_start_started_event_failure_returns_room_handle
operation_start_started_event_failure_does_not_create_second_correlation
operation_start_started_event_failure_does_not_leave_running_orphan
safe_operation_handle_emits_started_on_start
safe_operation_handle_started_preserves_operation_type
safe_operation_handle_started_preserves_start_metadata
safe_operation_handle_terminal_preserves_correlation
```

---

## Acceptance criteria

```text
1. DiagnosticEvent has one stable eventId across Room and safe sink.
2. Operation STARTED event failure cannot orphan a RUNNING row.
3. Safe operation handles satisfy STARTED -> terminal contract.
```

---

# PR 4 — Metadata sanitizer final edge hardening

## Issues fixed

```text
DDL-016-09  hash-suffix keys can bypass dangerous-key blocking
DDL-016-10  nested lists/arrays are not fully recursively sanitized
```

## Type

```text
Privacy hardening
```

---

## Files

```text
domain/diagnostics/EventMetadataSanitizer.kt
domain/diagnostics/SafeEventMetadata.kt
```

Tests:

```text
EventMetadataSanitizerTest.kt
SafeEventMetadataTest.kt
GlobalDurableDiagnosticsGoldenTest.kt
```

---

## Step 4.1 — Remove blind hash-suffix trust

Current risk:

```text
rawTextHash key allowed even if value is plain raw text
```

Replace generic suffix allowance with one of these.

### Preferred option — exact known hash keys

Only allow exact safe hash keys:

```kotlin
private val SAFE_HASH_KEYS = setOf(
    "sourceidhash",
    "notificationkeyhash",
    "packagenamehash",
    "messageidhash",
    "providerhash",
    "providertransactionidhash",
    "externalhash",
    "payloadhash",
    "contentfingerprinthash",
    "filehash",
    "backuphash",
    "assetrelativepathhash"
)
```

Then:

```kotlin
if (canonical in SAFE_HASH_KEYS) return false
```

Do not allow arbitrary `endsWith("hash")`.

### Alternative option — hash value validation

If dynamic hash keys are needed:

```kotlin
private val HASH_VALUE_PATTERN = Regex("^[a-fA-F0-9]{8,128}$")
```

For hash-like keys:

```kotlin
if (canonical.endsWith("hash") || canonical.endsWith("idhash")) {
    return value !is String || !HASH_VALUE_PATTERN.matches(value)
}
```

But exact key list is safer.

---

## Step 4.2 — Require `putHashed()` for external identifiers

In `SafeEventMetadata.Builder`:

```kotlin
fun putHashed(key: String, value: String?): Builder
```

should:

```text
canonicalize key
hash value
store under approved hash key
```

Do not rely on callers manually passing a hash-looking key.

---

## Step 4.3 — Fully recursive sanitization

Add one central function:

```kotlin
private fun sanitizeAny(value: Any?): Any? = when (value) {
    null -> null
    is JSONObject -> sanitizeJsonObject(value)
    is JSONArray -> sanitizeJsonArray(value)
    is Map<*, *> -> sanitizeMap(value)
    is Iterable<*> -> value.map { sanitizeAny(it) }
    is Array<*> -> value.map { sanitizeAny(it) }
    is String -> sanitizeStringValue(value)
    is Number, is Boolean -> value
    is Enum<*> -> value.name
    else -> sanitizeStringValue(value.toString())
}
```

Ensure `sanitizeJsonArray()` handles nested arrays:

```kotlin
private fun sanitizeJsonArray(array: JSONArray): JSONArray {
    val out = JSONArray()
    for (i in 0 until array.length()) {
        out.put(sanitizeAny(array.opt(i)))
    }
    return out
}
```

---

## Tests for PR 4

Required:

```text
metadata_put_raw_text_hash_with_plain_value_is_redacted
metadata_put_hashed_raw_text_hash_is_allowed_only_if_hash_format
metadata_hash_suffix_does_not_override_raw_token_path_substrings
metadata_sanitizer_redacts_prompt_inside_nested_list
metadata_sanitizer_redacts_token_inside_json_array_of_arrays
metadata_sanitizer_redacts_path_inside_array_inside_map
metadata_sanitizer_unknown_object_to_string_is_sanitized
```

---

## Acceptance criteria

```text
1. Hash-looking keys cannot smuggle plain raw text.
2. Nested metadata is sanitized at any depth.
3. Unknown objects cannot leak sensitive toString() output.
```

---

# PR 5 — Notification and bank trace completion

## Issues fixed

```text
DDL-016-11  notification duplicate RECEIVED events
DDL-016-12  notification early diagnostics are fire-and-forget
DDL-016-13  notification success path lacks correlation propagation
DDL-016-14  bank sync write barrier happens before operation run
DDL-016-15  bank sync does not propagate operation correlation to created expenses
```

## Type

```text
Support/debug traceability gap
Some actual blocked-operation diagnostic gaps
```

---

## Part A — Notification fixes

## Files

```text
service/NotificationCaptureService.kt
data/repository/NotificationRepository.kt
data/repository/NotificationProcessingPipeline.kt
domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
domain/review/PendingReviewCoordinator.kt
```

Exact downstream file names may differ; use actual repository/coordinator paths.

---

## Step 5.1 — Remove duplicate RECEIVED event

Keep only:

```text
listener-entry RECEIVED
```

Remove second `RECEIVED` inside `workTracker.launch`.

If you need another marker, emit:

```text
stage = extraction
outcome = COMPLETED
```

or:

```text
stage = filter
outcome = ATTEMPTED
```

but not another `RECEIVED`.

---

## Step 5.2 — Order early diagnostics

Avoid this pattern for early exits:

```kotlin
serviceScope.launch {
    diagnosticEventWriter.emit(...)
}
return
```

Use a tracked coroutine and emit events in order:

```kotlin
private fun emitNotificationTerminalAsync(
    received: DiagnosticEvent?,
    terminal: DiagnosticEvent
) {
    workTracker.launch(serviceScope) {
        received?.let { diagnosticEventWriter.emit(it) }
        diagnosticEventWriter.emit(terminal)
    }
}
```

Since `RECEIVED` is already emitted at entry, early exits should ensure terminal follows same correlation.

For shutdown/cancellation, use:

```kotlin
withContext(NonCancellable) {
    diagnosticEventWriter.emit(cancelledEvent)
}
```

where possible.

---

## Step 5.3 — Propagate correlation into repository

Change:

```kotlin
repository.processAndSave(processingNotification, storageNotification)
```

to:

```kotlin
repository.processAndSave(
    processingNotification = processingNotification,
    storageNotification = storageNotification,
    correlationId = correlationId
)
```

Then propagate through:

```text
NotificationRepository
NotificationProcessingPipeline
pending review creation
expense creation
transaction lifecycle event
side-effect dispatcher
```

If request models exist, add:

```kotlin
val correlationId: String?
```

to them instead of adding long parameter chains.

---

## Step 5.4 — Notification outcome model

If feasible, make pipeline return a sealed result:

```kotlin
sealed interface NotificationProcessingOutcome {
    data class ExpenseCreated(val expenseId: Long) : NotificationProcessingOutcome
    data class ReviewCreated(val reviewId: Long) : NotificationProcessingOutcome
    data class Duplicate(val matchedEntityId: Long?) : NotificationProcessingOutcome
    data class Dropped(val reason: DiagnosticReasonCode) : NotificationProcessingOutcome
    data class Failed(val reason: DiagnosticReasonCode, val retryable: Boolean) : NotificationProcessingOutcome
}
```

Service can then ensure terminal outcome only when pipeline does not already emit lifecycle/domain event.

---

## Notification tests

```text
notification_normal_path_emits_exactly_one_received_event
notification_received_before_filter_attempt
notification_shutdown_records_received_then_cancelled_ordered
notification_restore_blocked_records_received_then_blocked_ordered
notification_success_expense_created_uses_same_correlation
notification_success_review_created_uses_same_correlation
notification_side_effect_uses_notification_correlation
```

---

## Part B — Bank fixes

## Files

```text
domain/bank/BankApiIntegration.kt
domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
domain/transaction/CreateExpenseRequest.kt
```

Exact request file may differ.

---

## Step 5.5 — Start BANK_SYNC operation before write barrier

Current risky pattern:

```kotlin
writeBarrier.checkWritesAllowed(...)
operationRunRecorder.runOperation("BANK_SYNC") { ... }
```

Fix:

```kotlin
operationRunRecorder.runOperation("BANK_SYNC", actor = "system") { run ->
    try {
        writeBarrier.checkWritesAllowed("BankApiIntegration.syncTransactions")
    } catch (e: Exception) {
        run.event(
            stage = "WRITE_BARRIER",
            outcome = EventOutcome.BLOCKED,
            severity = EventSeverity.WARNING,
            reasonCode = DiagnosticReasonCode.RESTORE_BLOCKED,
            exception = e,
            isTerminal = true
        )
        run.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
        return@runOperation SyncResult.Blocked(...)
    }

    // continue sync
}
```

If write barrier denial type is specific, catch only that.

---

## Step 5.6 — Propagate bank operation correlation to expense creation

When creating expenses from bank transactions:

```kotlin
CreateExpenseRequest(
    ...,
    correlationId = run.correlationId,
    sourceType = "BANK",
    sourceIdHash = hash(providerTransactionId)
)
```

Then transaction lifecycle writer must use request correlation ID.

If `CreateExpenseRequest` does not have `correlationId`, add it:

```kotlin
val correlationId: String? = null
```

Boundary rule:

```text
If request.correlationId == null, coordinator generates one.
If provided, use it for all lifecycle/side-effect diagnostics.
```

---

## Bank tests

```text
bank_sync_restore_blocked_writes_operation_event
bank_sync_restore_blocked_finalizes_operation_run
bank_sync_write_barrier_denied_uses_safe_sink_if_room_unavailable
bank_transaction_imported_and_expense_created_share_correlation
bank_duplicate_transaction_and_transaction_duplicate_event_share_correlation
```

---

## Acceptance criteria

```text
1. Notification normal path has exactly one RECEIVED.
2. Notification early exits have ordered terminal events.
3. Notification-created review/expense shares notification correlation.
4. Bank blocked sync gets operation STARTED -> BLOCKED/CANCELLED.
5. Bank-created expenses share bank sync correlation.
```

---

# PR 6 — DiagnosticsRepository correctness and DI

## Issues fixed

```text
DDL-016-16  DiagnosticsRepository.getTraceByCorrelationId can hang forever
DDL-016-17  DiagnosticsRepository interface may not be Hilt-bound
```

## Type

```text
Actual support/debug runtime bug
DI bug
```

---

## Files

```text
domain/debug/DiagnosticsRepository.kt
data/debug/DiagnosticsRepositoryImpl.kt
di/DiagnosticsModule.kt
data/backup/MaintenanceSafeDiagnosticSink.kt
data/backup/RestoreJournal.kt
```

Tests:

```text
DiagnosticsRepositoryTest.kt
HiltCompileSmokeTest.kt
```

---

## Step 6.1 — Fix endless Flow collection

Replace:

```kotlin
safeSink.observeRecent().collect { records ->
    result = records
    return@collect
}
```

with:

```kotlin
val safeSinkEvents = safeSink.observeRecent()
    .first()
    .filter { it.correlationId == correlationId }
```

Import:

```kotlin
import kotlinx.coroutines.flow.first
```

Do same for any other endless flows.

---

## Step 6.2 — Add repository binding

In DI module:

```kotlin
@Binds
@Singleton
abstract fun bindDiagnosticsRepository(
    impl: DiagnosticsRepositoryImpl
): DiagnosticsRepository
```

If current module is object module with `@Provides`, either convert or create a new abstract module:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class DebugRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsRepository(
        impl: DiagnosticsRepositoryImpl
    ): DiagnosticsRepository
}
```

---

## Step 6.3 — Include safe sink and restore journal

`getTraceByCorrelationId()` should include:

```text
pipeline_diagnostic_events
operation_runs
operation_run_events
background_job_runs
safe sink diagnostic records
restore journal events
```

Use only diagnostics-safe restore journal, never recovery journal.

---

## Tests for PR 6

```text
trace_by_correlation_returns_with_safe_sink_flow
trace_by_correlation_filters_safe_sink_records
trace_by_correlation_includes_restore_journal_events
trace_by_correlation_excludes_recovery_path_fields
diagnostics_repository_is_hilt_bound
```

---

## Acceptance criteria

```text
1. getTraceByCorrelationId returns and does not hang.
2. DiagnosticsRepository is injectable.
3. Trace includes Room + safe sink + restore journal diagnostics.
4. Trace never exposes full recovery paths.
```

---

# PR 7 — Golden tests and regression locks

## Goal

Lock the fixes with real code-path tests, not only enum/default tests.

---

## Test suites to add or extend

```text
GlobalDurableDiagnosticsGoldenTest.kt
DatabaseBackupRepositoryRestoreDiagnosticsTest.kt
RestoreJournalTest.kt
RestoreJournalPrivacyTest.kt
OperationRunRecorderTest.kt
CompositeOperationRunRecorderTest.kt
NotificationCaptureServiceDiagnosticsTest.kt
BankApiIntegrationDiagnosticsTest.kt
DiagnosticsRepositoryTest.kt
EventMetadataSanitizerTest.kt
```

---

## Required golden tests

### Restore

```text
restore_success_not_rolled_back_when_operation_event_insert_fails
restore_after_swap_does_not_call_room_operation_run_event
restore_journal_roundtrip_preserves_recovery_paths
restore_diagnostics_journal_does_not_expose_full_paths
restore_success_after_restart_imports_operation_run
restore_rollback_failure_critical
```

### Operation recorder

```text
operation_intermediate_event_failure_does_not_fail_business_operation
operation_start_started_event_failure_does_not_orphan_running_row
safe_operation_handle_emits_started
diagnostic_event_has_stable_event_id
safe_sink_uses_diagnostic_event_id
```

### Metadata

```text
metadata_put_raw_text_hash_plain_value_is_redacted
metadata_hash_suffix_does_not_override_raw_token_path_substrings
metadata_sanitizer_redacts_prompt_inside_nested_list
metadata_sanitizer_redacts_token_inside_json_array_of_arrays
```

### Notification

```text
notification_normal_path_emits_exactly_one_received
notification_shutdown_records_received_then_cancelled_ordered
notification_success_expense_created_uses_same_correlation
```

### Bank

```text
bank_sync_restore_blocked_finalizes_operation_run
bank_transaction_imported_and_expense_created_share_correlation
```

### Diagnostics repository

```text
trace_by_correlation_returns_with_datastore_safe_sink
trace_by_correlation_includes_restore_journal_events
trace_by_correlation_excludes_recovery_paths
```

---

# Final remaining issue map

| Issue | PR | Type | Priority |
|---|---:|---|---:|
| Restore writes Room events after DB swap | PR 1 | Actual restore bug | Critical |
| Operation event failure can fail business op | PR 1 | Actual reliability bug | High |
| Pre-swap restore failures not finalized | PR 1 | Diagnostic completeness | High |
| Journal path roundtrip mismatch | PR 2 | Crash recovery bug | Critical |
| Journal exposes full paths | PR 2 | Privacy bug | High |
| Journal lacks append-only events | PR 2 | Support gap | High |
| Success journal not imported/queryable | PR 2 | Support gap | Medium/High |
| STARTED event failure can orphan run | PR 3 | Diagnostic consistency | High |
| Safe operation handle lacks STARTED | PR 3 | Contract gap | Medium/High |
| DiagnosticEvent lacks stable eventId | PR 3 | Traceability gap | Medium |
| Hash-suffix metadata bypass | PR 4 | Privacy bug | Medium/High |
| Nested arrays not fully sanitized | PR 4 | Privacy hardening | Medium |
| Duplicate notification RECEIVED | PR 5 | Diagnostic noise | Low/Medium |
| Notification fire-and-forget ordering | PR 5 | Durability race | Medium |
| Notification correlation not propagated | PR 5 | Traceability gap | Medium/High |
| Bank barrier before operation run | PR 5 | Blocked diagnostic bug | High |
| Bank correlation not propagated | PR 5 | Traceability gap | Medium/High |
| DiagnosticsRepository Flow hang | PR 6 | Runtime bug | High |
| DiagnosticsRepository missing DI binding | PR 6 | DI/build bug | Medium |

---

# Definition of done

The durable diagnostics refactor can be considered complete when:

```text
1. Restore never writes Room diagnostics after DB swap.
2. Diagnostic/operation event write failure never fails a successful business operation.
3. Restore recovery journal roundtrips operational paths correctly.
4. Restore diagnostics journal/debug trace never exposes full local paths.
5. Restore journal has append-only stage history and survives restart.
6. Operation runs always have STARTED -> terminal, including safe-sink fallback.
7. DiagnosticEvent has stable eventId across Room and safe sink.
8. Metadata sanitizer blocks hash-key raw-value bypass and sanitizes nested arrays/maps fully.
9. Notification emits exactly one RECEIVED and propagates correlation to domain lifecycle.
10. Bank blocked sync is durable and bank-created expenses share sync correlation.
11. DiagnosticsRepository returns reliably, is injectable, and includes Room/safe/journal traces.
12. Golden tests cover actual restore, operation, notification, bank, metadata, and trace paths.
```