# Remaining Durable Diagnostics Implementation Plan

Target commit: `51215d760fd29a9bbdbdec53b4e508e0c119d09f`

Basis: static review of the durable diagnostics fixes at commit `51215d7`.

## Executive goal

Finish the remaining durable diagnostics work so that:

```text
1. Restore success/failure journals contain a complete append-only stage trail.
2. Restore failure terminal events are written before the journal is moved to failure storage.
3. Restore journal events preserve safe metadata.
4. Notification and bank correlations reach transaction lifecycle events.
5. Metadata final-pass sanitization cannot leak raw values under hash-looking keys.
6. Operation/debug schema and recent-failure queries are consistent.
7. Backup/reset/legacy restore paths have terminal operation records.
```

---

# 0. Remaining issue list

## Critical / high priority

```text
DDL-512-01 Restore terminal failure events are appended after failJournal(), so they are lost.
DDL-512-02 MAINTENANCE_ENTERED is emitted before restore journal exists.
DDL-512-03 Restore journal event metadata is dropped.
DDL-512-04 operation_run_events.eventId index exists in migration but not entity.
DDL-512-05 Notification correlation is accepted but not propagated through pipeline/domain.
DDL-512-06 TransactionLifecycleCoordinator only propagates correlation on CREATE_ATTEMPTED.
DDL-512-07 sanitizeJsonString() bypasses hash-value validation.
```

## Medium priority

```text
DDL-512-08 Restore journal still mixes diagnostics and recovery-only full paths.
DDL-512-09 Receipt asset restore lacks ASSETS_RESTORING / ASSET_RESTORED / ASSET_FAILED events.
DDL-512-10 DiagnosticsRepository.getRecentFailures() misses restore failure journals.
DDL-512-11 OperationRunEventDao.getRecentFailures() misses BLOCKED / DROPPED / SIDE_EFFECT_FAILED.
DDL-512-12 Backup export early failures can leave operation run incomplete.
DDL-512-13 RESET_DATABASE / RESTORE_LEGACY_DB do not use the unified operation-run diagnostics model.
DDL-512-14 Notification RECEIVED/terminal ordering race remains.
```

---

# PR 1 — Restore failure-journal correctness

## Issues fixed

```text
DDL-512-01
DDL-512-02
DDL-512-03
```

## Goal

Restore journals must contain a complete, append-only, metadata-rich trail for both success and failure cases.

## Files

```text
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
```

Tests:

```text
RestoreJournalTest.kt
RestoreDiagnosticsSinkTest.kt
DatabaseBackupRepositoryRestoreDiagnosticsTest.kt
```

---

## Step 1.1 — Always emit terminal failure event before failJournal()

Current dangerous pattern:

```kotlin
restoreJournal.failJournal(journalEntry, reason)
restoreEvents.event("SOME_STAGE", EventOutcome.FAILED_FINAL, ...)
```

This loses the terminal event because `failJournal()` renames the active journal to the failure journal. Later `appendEvent()` only sees the active journal.

Replace all such branches with:

```kotlin
restoreEvents.event(
    stage = "SOME_STAGE",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.ERROR,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = error,
    isTerminal = true
)

restoreEvents.finalizeRunFailed(reason, error)

restoreJournal.failJournal(journalEntry, reason)
```

Apply specifically to:

```text
wrong password / extraction failure
empty manifest / invalid bundle
staged DB verification failure
post-migration verification failure
staged migration failure
safety backup failure
safety backup path unavailable
live DB swap failure
rollback completed after failed live verification
rollback failed
```

## Step 1.2 — Add one helper for pre-swap failures

In `DatabaseBackupRepositoryImpl`, create:

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

    restoreJournal.failJournal(journalEntry, message)
    restoreMaintenanceMode.exit(forceRestartRequired = false)

    return Result.failure(error ?: IllegalStateException(message))
}
```

Use it for all failures before `markLiveDbSwapStarted()`.

## Step 1.3 — Create restore journal before MAINTENANCE_ENTERED

Current order:

```text
enter maintenance
emit MAINTENANCE_ENTERED
beginJournal()
emit JOURNAL_CREATED
```

This means `MAINTENANCE_ENTERED` is not appended to the journal.

Change to:

```text
prepare paths
beginJournal()
create RestoreDiagnosticsSink
emit STARTED / ATTEMPTED
enter maintenance / drain workers
emit MAINTENANCE_ENTERED / COMPLETED
emit JOURNAL_CREATED / COMPLETED
```

If some information is unavailable until maintenance starts, create the journal with placeholder-safe values, then transition/update it after.

## Step 1.4 — Preserve metadataJson in restore journal events

Current `RestoreDiagnosticsSink.event(...)` passes no metadata to `RestoreJournal.appendEvent(...)`, and `RestoreJournal` stores `metadataJson = null`.

Update `RestoreJournal.appendEvent(...)`:

```kotlin
fun appendEvent(
    correlationId: String,
    stage: String,
    outcome: String,
    severity: String = "INFO",
    reasonCode: String? = null,
    metadataJson: String? = null,
    exceptionClass: String? = null,
    exceptionMessageSafe: String? = null,
    isTerminal: Boolean = false
)
```

Update `RestoreDiagnosticsSink.event(...)`:

```kotlin
restoreJournal.appendEvent(
    correlationId = correlationId,
    stage = stage,
    outcome = outcome.name,
    severity = severity.name,
    reasonCode = reasonCode?.name,
    metadataJson = metadata.toJson().takeIf { it != "{}" },
    exceptionClass = exception?.javaClass?.simpleName,
    exceptionMessageSafe = sanitizer.sanitizeExceptionMessage(exception?.message),
    isTerminal = isTerminal
)
```

Update `serializeEvents()`:

```kotlin
if (e.metadataJson != null) put("metadataJson", e.metadataJson)
```

Update `parseEvents()`:

```kotlin
metadataJson = o.optString("metadataJson").takeIf { it.isNotEmpty() }
```

## Step 1.5 — Ensure metadata is sanitized

Before writing `metadataJson`, pass it through sanitizer if needed:

```kotlin
val safeMetadataJson = sanitizer.sanitizeJsonString(metadata.toJson())
```

## Tests

```text
restore_wrong_password_failure_journal_contains_failed_final_event
restore_empty_manifest_failure_journal_contains_failed_final_event
restore_post_migration_failure_journal_contains_failed_final_event
restore_swap_failure_journal_contains_live_db_swap_failed
restore_rollback_failed_journal_contains_critical_terminal_event
restore_success_journal_contains_started_event
restore_success_journal_contains_maintenance_entered_event
restore_failure_journal_contains_maintenance_entered_event
restore_journal_event_preserves_metadata_json
restore_journal_event_metadata_is_sanitized
restore_importer_imports_metadata_json_to_operation_run_event
```

## Acceptance criteria

```text
1. No restore failure path calls failJournal() before the terminal event is appended.
2. STARTED and MAINTENANCE_ENTERED appear in success/failure journals.
3. Restore journal events preserve safe metadataJson.
```

---

# PR 2 — OperationRunEvent schema/index correctness

## Issue fixed

```text
DDL-512-04
```

## Goal

Fresh installs and migrated installs must have the same `operation_run_events.eventId` schema/index.

## Files

```text
app/src/main/java/com/yourname/expensetracker/data/database/entity/OperationRunEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
app/schemas/com.yourname.expensetracker.data.database.AppDatabase/*.json
```

Tests:

```text
MigrationTest.kt
OperationRunEventSchemaTest.kt
```

## Implementation

Add index to entity:

```kotlin
@Entity(
    tableName = "operation_run_events",
    indices = [
        Index(value = ["operationRunId"]),
        Index(value = ["correlationId"]),
        Index(value = ["eventType"]),
        Index(value = ["occurredAt"]),
        Index(value = ["eventId"])
    ]
)
data class OperationRunEvent(...)
```

If this app version has not shipped, updating the current schema may be enough. If version `129` has shipped or is treated as immutable, bump DB version:

```text
129 -> 130
```

Migration:

```sql
CREATE INDEX IF NOT EXISTS index_operation_run_events_eventId
ON operation_run_events(eventId);
```

Optional if event IDs are guaranteed unique:

```kotlin
Index(value = ["eventId"], unique = true)
```

But only do this if all non-null event IDs are unique and old rows are safe.

## Tests

```text
fresh_schema_contains_operation_run_event_eventId_index
migration_129_130_schema_matches_entity
restore_importer_can_lookup_eventId_efficiently
```

## Acceptance criteria

```text
Room entity schema, fresh DB schema, exported schema JSON, and migrations agree.
```

---

# PR 3 — Notification correlation propagation

## Issue fixed

```text
DDL-512-05
```

## Goal

Notification listener correlation must trace into notification pipeline diagnostics, review creation, expense creation, transaction lifecycle events, and side effects.

## Files

```text
NotificationCaptureService.kt
NotificationRepository.kt
NotificationProcessingPipeline.kt
CreateExpenseRequest.kt
TransactionLifecycleCoordinator.kt
TransactionLifecycleEventWriter.kt
PendingReview-related coordinator/repository
```

## Step 3.1 — Thread correlation into `processInternal`

Current issue:

```kotlin
pipeline.process(..., correlationId = correlationId)
```

but inside:

```kotlin
processInternal(notification, storageNotification, initializeClassifier = true)
```

without correlation.

Change signatures:

```kotlin
suspend fun process(
    notification: ProcessingNotification,
    storageNotification: StorageNotification,
    correlationId: String? = null
) {
    val cid = correlationId ?: CorrelationIds.newId()
    val outcome = processInternal(
        notification = notification,
        storageNotification = storageNotification,
        initializeClassifier = true,
        correlationId = cid
    )
    writePipelineDiagnosticEvent(outcome, notification.packageName, correlationId = cid)
}
```

Update:

```kotlin
private suspend fun processInternal(..., correlationId: String)
private suspend fun handleAutoAcceptInTransaction(..., correlationId: String)
private suspend fun handleNeedsReviewInTransaction(..., correlationId: String)
```

## Step 3.2 — Use correlation in `CreateExpenseRequest`

When building an expense from notification:

```kotlin
CreateExpenseRequest(
    ...,
    correlationId = correlationId
)
```

## Step 3.3 — Use correlation for notification pipeline diagnostics

Update any helper like:

```kotlin
writePipelineDiagnosticEvent(outcome, packageName)
```

to:

```kotlin
writePipelineDiagnosticEvent(outcome, packageName, correlationId)
```

Ensure duplicate/drop/failure diagnostics use the same correlation.

## Step 3.4 — Correlate pending review path

If pending review table has no correlation column, short-term emit a diagnostic event:

```text
pipeline = NOTIFICATION
stage = review_created
outcome = NEEDS_REVIEW or CREATED
correlationId = listener correlation
entityType = pending_review
entityId = reviewId
```

Long-term add `correlationId` to pending review lifecycle/audit event if such table exists.

## Step 3.5 — Correlate AI auto-accept lifecycle event

Where `AI_AUTO_ACCEPT` transaction lifecycle event is written:

```kotlin
TransactionLifecycleEvent(
    ...,
    correlationId = correlationId
)
```

## Tests

```text
notification_pipeline_diagnostic_uses_listener_correlation
notification_auto_accept_create_request_uses_listener_correlation
notification_ai_auto_accept_event_uses_listener_correlation
notification_needs_review_event_uses_listener_correlation
notification_duplicate_diagnostic_uses_listener_correlation
notification_drop_diagnostic_uses_listener_correlation
```

## Acceptance criteria

```text
A single correlationId traces notification RECEIVED -> pipeline decision -> review/expense -> transaction event.
```

---

# PR 4 — Transaction lifecycle correlation completion

## Issue fixed

```text
DDL-512-06
```

## Goal

Every transaction create outcome must preserve `CreateExpenseRequest.correlationId`.

## Files

```text
TransactionLifecycleCoordinator.kt
TransactionLifecycleEventWriter.kt
TransactionEvent.kt
TransactionEventDao.kt
TransactionSideEffectDispatcher.kt
```

## Step 4.1 — Generate one boundary correlation

At create boundary:

```kotlin
val correlationId = request.correlationId ?: CorrelationIds.newId()
val causationId = request.causationId
```

Do not call `CorrelationIds.newId()` separately for each event.

## Step 4.2 — Apply to all create events

Set `correlationId` and `causationId` on:

```text
CREATE_ATTEMPTED
CREATE_VALIDATION_FAILED
CREATED
CREATE_INSERT_CONFLICT
CREATE_DUPLICATE_SKIPPED
AI_AUTO_ACCEPT
SOURCE_LINKED
SIDE_EFFECT_STARTED
SIDE_EFFECT_COMPLETED
SIDE_EFFECT_FAILED
```

Example:

```kotlin
transactionEventWriter.write(
    TransactionLifecycleEvent(
        expenseId = insertedId,
        eventType = "CREATED",
        correlationId = correlationId,
        causationId = causationId,
        ...
    )
)
```

## Step 4.3 — Update duplicate helper

Change:

```kotlin
writeDuplicateEvent(...)
```

to:

```kotlin
writeDuplicateEvent(..., correlationId: String, causationId: String?)
```

## Step 4.4 — Propagate into side effects

Change:

```kotlin
dispatchPostCreationSideEffects(expenseId, request.source)
```

to:

```kotlin
dispatchPostCreationSideEffects(
    expenseId = expenseId,
    source = request.source,
    correlationId = correlationId,
    causationId = causationId
)
```

## Step 4.5 — Update update/delete later if applicable

If update/delete request models have no correlation yet, add optional fields or generate boundary correlation and apply consistently.

## Tests

```text
transaction_create_attempted_uses_request_correlation
transaction_validation_failed_uses_request_correlation
transaction_created_uses_request_correlation
transaction_insert_conflict_uses_request_correlation
transaction_duplicate_uses_request_correlation
transaction_side_effect_uses_request_correlation
bank_transaction_imported_and_transaction_created_share_correlation
notification_expense_created_uses_listener_correlation
```

## Acceptance criteria

```text
All transaction create outcomes and side effects are queryable by the input correlationId.
```

---

# PR 5 — Metadata sanitizer final-pass fix

## Issue fixed

```text
DDL-512-07
```

## Goal

Final-pass JSON sanitization must enforce the same key-aware rules as builder sanitization.

## Files

```text
EventMetadataSanitizer.kt
SafeEventMetadata.kt
DiagnosticEventWriter.kt
OperationRunRecorder.kt
```

Tests:

```text
EventMetadataSanitizerTest.kt
DiagnosticEventWriterTest.kt
OperationRunRecorderTest.kt
```

## Problem

`sanitizeValue(key, value)` validates hash-key values, but `sanitizeJsonString()` uses `sanitizeStringValue(v)` directly, so this may pass:

```json
{"sourceIdHash":"plain raw external id"}
```

## Implementation

In `sanitizeJsonObject`, use key-aware sanitization:

```kotlin
private fun sanitizeJsonObject(obj: JSONObject): JSONObject {
    val result = JSONObject()

    for (key in obj.keys()) {
        val value = obj.opt(key)

        val sanitized = when (value) {
            is JSONObject -> {
                if (isDangerousKey(key)) REDACTED else sanitizeJsonObject(value)
            }
            is JSONArray -> {
                if (isDangerousKey(key)) REDACTED else sanitizeJsonArray(value)
            }
            else -> sanitizeValue(key, value)
        }

        result.put(key, sanitized)
    }

    return result
}
```

In arrays, no key exists, so use generic recursive sanitizer:

```kotlin
private fun sanitizeJsonArray(array: JSONArray): JSONArray {
    val result = JSONArray()
    for (i in 0 until array.length()) {
        result.put(sanitizeAny(array.opt(i)))
    }
    return result
}
```

Ensure hash keys only allow values matching:

```kotlin
Regex("^[a-fA-F0-9]{8,128}$")
```

## Tests

```text
sanitize_json_string_redacts_plain_source_id_hash
sanitize_json_string_redacts_plain_provider_transaction_id_hash
diagnostic_writer_final_pass_redacts_plain_hash_key_values
operation_run_writer_final_pass_redacts_plain_hash_key_values
sanitize_json_object_uses_key_policy_for_nested_hash_keys
```

## Acceptance criteria

```text
No writer can persist raw external IDs by passing prebuilt JSON with hash-looking keys.
```

---

# PR 6 — Restore/debug recent-failure completeness

## Issues fixed

```text
DDL-512-10
DDL-512-11
```

## Goal

Recent failures should include pipeline, operation, worker, safe-sink, active restore, success restore warnings/errors, and failure restore journals.

## Files

```text
DiagnosticsRepository.kt
OperationRunEventDao.kt
RestoreJournal.kt
BackgroundJobRunDao.kt
MaintenanceSafeDiagnosticSink.kt
```

## Step 6.1 — Add all restore journal events API

In `RestoreJournal`:

```kotlin
fun getAllDiagnosticEvents(): List<RestoreJournalEvent>
```

It should read:

```text
active restore_journal.json
restore_journal_last_success.json
restore_journal_last_failure.json
imported success journal if retained
```

Deduplicate by `eventId`.

## Step 6.2 — Use it in recent failures

In `DiagnosticsRepository.getRecentFailures(limit)`:

```kotlin
val restoreFailures = restoreJournal.getAllDiagnosticEvents()
    .filter { it.isFailureLike() }
    .map { it.toDiagnosticFailureSummary() }
```

Failure predicate:

```kotlin
severity in WARNING, ERROR, CRITICAL
or outcome in FAILED_RETRYABLE, FAILED_FINAL, BLOCKED, DROPPED, CANCELLED, SIDE_EFFECT_FAILED
```

## Step 6.3 — Expand operation-run recent failure query

Update `OperationRunEventDao.getRecentFailures()`:

```sql
WHERE outcome IN (
  'FAILED_RETRYABLE',
  'FAILED_FINAL',
  'CANCELLED',
  'BLOCKED',
  'DROPPED',
  'SIDE_EFFECT_FAILED'
)
OR severity IN ('WARNING','ERROR','CRITICAL')
ORDER BY occurredAt DESC
LIMIT :limit
```

## Tests

```text
recent_failures_includes_restore_failure_journal_failed_final
recent_failures_includes_rollback_failed_critical
recent_failures_includes_active_restore_failure
recent_failures_includes_success_journal_warning
recent_failures_does_not_require_blank_correlation
operation_recent_failures_includes_blocked
operation_recent_failures_includes_side_effect_failed
recent_failures_sorted_desc_after_merging_sources
```

## Acceptance criteria

```text
Support/debug recent failures show all meaningful failures, not only pipeline_diagnostic_events.
```

---

# PR 7 — Restore/backup operation completeness

## Issues fixed

```text
DDL-512-08
DDL-512-09
DDL-512-12
DDL-512-13
```

## Goal

All backup/restore/reset/import operations have uniform durable operation traces and privacy-safe restore diagnostics.

## Files

```text
DatabaseBackupRepositoryImpl.kt
RestoreJournal.kt
RestoreDiagnosticsSink.kt
OperationRunRecorder.kt
DiagnosticsRepository.kt
```

## Step 7.1 — Harden restore journal privacy

Preferred final design:

```text
restore_recovery_journal.json      // internal, pathful, crash recovery only
restore_diagnostics_journal.json   // privacy-safe, debug/support only
```

If full split is too large now, enforce this bridge:

```text
1. All debug/repository/support APIs must use toDiagnosticsJson().
2. Raw restore journal file is never returned from DiagnosticsRepository.
3. Any support export strips keys beginning with "_".
4. Tests assert no /data/, /storage/, file://, C:\ in diagnostics output.
```

## Step 7.2 — Add asset restore events

Pass `RestoreDiagnosticsSink` into `restoreReceiptAssets(...)`.

Emit:

```text
ASSETS_RESTORING / ATTEMPTED
ASSET_RESTORED / COMPLETED
ASSET_FAILED / FAILED_FINAL or WARNING
```

Metadata:

```kotlin
SafeEventMetadata.builder()
    .put("receiptId", receiptId)
    .putHashed("assetRelativePath", relativePath)
    .put("assetKind", "receipt")
    .build()
```

Never include full file path.

## Step 7.3 — Finalize backup export early failures

In `createCostBackup(...)`, ensure every early return finalizes the operation run.

Failures to cover:

```text
checkpointWal() failure
database file missing
snapshot verification failed
manifest/write failure
encryption failure
asset collection failure if terminal
```

Prefer wrapping with:

```kotlin
operationRunRecorder.runOperation("BACKUP_EXPORT", actor = "user") { run ->
    ...
}
```

But if the method already manually starts a run, add helper:

```kotlin
failBackup(stage, reasonCode, message, error)
```

that emits stage failure and calls `run.failedFinal(...)`.

## Step 7.4 — Add operation runs for reset and legacy import

Wrap:

```text
resetDatabase() -> RESET_DATABASE
legacy importDatabase() -> RESTORE_LEGACY_DB
```

with operation run recorder:

```kotlin
operationRunRecorder.runOperation("RESET_DATABASE", actor = "user") { run ->
    run.event("STARTED", ATTEMPTED)
    ...
    run.event("RESTART_REQUIRED", COMPLETED, isTerminal = true)
}
```

## Tests

```text
restore_diagnostics_journal_has_no_full_paths
diagnostics_repository_never_exposes_internal_path_fields
asset_restore_success_writes_asset_restored_event
asset_restore_failure_writes_asset_failed_event
asset_restore_event_does_not_include_full_path
backup_checkpoint_failure_finalizes_operation_run
backup_missing_db_file_finalizes_operation_run
backup_snapshot_verification_failure_finalizes_operation_run
reset_database_writes_operation_started_and_terminal
legacy_import_writes_operation_started_and_terminal
legacy_import_failure_writes_failed_final_event
```

## Acceptance criteria

```text
1. Restore debug diagnostics are privacy-safe.
2. Asset restore successes/failures are durable.
3. Backup early failures cannot leave RUNNING operation rows.
4. Reset and legacy import use operation_runs.
```

---

# PR 8 — Notification diagnostic ordering

## Issue fixed

```text
DDL-512-14
```

## Goal

For notification listener and refresh inputs, `RECEIVED` must be durably ordered before terminal early-exit events.

## Files

```text
NotificationCaptureService.kt
```

Tests:

```text
NotificationCaptureServiceDiagnosticsTest.kt
```

## Problem

`RECEIVED` is emitted in one coroutine, terminal events in other jobs. Ordering and durability can race.

## Implementation

Create ordered helper:

```kotlin
private fun emitNotificationEventsOrdered(
    received: DiagnosticEvent?,
    terminal: DiagnosticEvent?
) {
    workTracker.launch(serviceScope) {
        received?.let { diagnosticEventWriter.emit(it) }
        terminal?.let { diagnosticEventWriter.emit(it) }
    }
}
```

For listener entry:

- either do not emit `RECEIVED` separately; include it in ordered helper for early exits
- or emit `RECEIVED` synchronously inside the same tracked work block before any terminal

Recommended approach:

```kotlin
val receivedEvent = buildReceivedEvent(...)
val terminalEvent = buildTerminalEvent(...)

emitNotificationEventsOrdered(receivedEvent, terminalEvent)
return
```

For paths that continue processing:

```kotlin
workTracker.launch(serviceScope) {
    diagnosticEventWriter.emit(receivedEvent)
    ... continue extraction/filter/privacy/process
}
```

Remove direct fire-and-forget diagnostics:

```kotlin
serviceScope.launch { diagnosticEventWriter.emit(...) }
```

from early terminal paths.

Apply to:

```text
restore blocked
shutdown
dedupe
fast privacy
filter rejected
blocked package
async privacy denied
refresh restore blocked
refresh fast privacy
refresh filter
refresh shutdown
refresh async privacy
repository exception/cancellation
```

## Tests

```text
notification_restore_blocked_received_before_blocked
notification_dedupe_received_before_duplicate
notification_fast_privacy_received_before_dropped
notification_filter_received_before_dropped
refresh_filter_received_before_dropped
refresh_shutdown_received_before_cancelled
notification_received_and_terminal_same_correlation
```

## Acceptance criteria

```text
Notification early exits have ordered RECEIVED -> terminal diagnostics with the same correlation ID.
```

---

# PR 9 — Behavioral regression tests

## Goal

Replace structural tests with tests that would catch the actual bugs found.

## Required tests

### Restore journal

```text
restore_wrong_password_failure_journal_contains_failed_final_event
restore_empty_manifest_failure_journal_contains_failed_final_event
restore_success_journal_contains_maintenance_entered_event
restore_journal_event_preserves_metadata_json
restore_transition_preserves_events_array
```

### Schema

```text
fresh_schema_contains_operation_run_event_eventId_index
migration_129_130_schema_matches_entity
```

### Correlation

```text
notification_pipeline_diagnostic_uses_listener_correlation
notification_auto_accept_create_request_uses_listener_correlation
transaction_created_uses_request_correlation
bank_transaction_imported_and_transaction_created_share_correlation
```

### Metadata

```text
sanitize_json_string_redacts_plain_source_id_hash
diagnostic_writer_final_pass_redacts_plain_hash_key_values
```

### Recent failures

```text
recent_failures_includes_restore_failure_journal
operation_recent_failures_includes_blocked
```

### Backup/reset/import

```text
backup_checkpoint_failure_finalizes_operation_run
reset_database_writes_operation_started_and_terminal
legacy_import_failure_writes_failed_final_event
```

### Notification ordering

```text
notification_restore_blocked_received_before_blocked
refresh_filter_received_before_dropped
```

---

# Final recommended PR order

```text
PR 1  Restore failure-journal correctness
PR 2  OperationRunEvent schema/index correctness
PR 3  Notification correlation propagation
PR 4  Transaction lifecycle correlation completion
PR 5  Metadata sanitizer final-pass fix
PR 6  Restore/debug recent-failure completeness
PR 7  Restore/backup operation completeness
PR 8  Notification diagnostic ordering
PR 9  Behavioral regression tests
```

---

# Definition of done

Durable Diagnostics can be considered complete when:

```text
1. Every restore success/failure journal contains STARTED -> terminal stage trail.
2. Terminal restore failure events are appended before failJournal().
3. Restore journal events preserve sanitized metadataJson.
4. operation_run_events.eventId schema/index matches entity + migrations.
5. Notification correlation reaches pipeline diagnostics, review, expense, transaction event, and side effects.
6. Transaction create/duplicate/validation/insert-conflict/created events all use request correlation.
7. sanitizeJsonString() applies key-aware hash-value validation.
8. Recent failures include pipeline, operation, worker, safe-sink, active/success/failure restore journals.
9. Backup, reset, and legacy import all have STARTED -> terminal operation runs.
10. Notification early exits preserve ordered RECEIVED -> terminal diagnostics.
11. Tests prove behavior, not only API presence.
```

---

# Sources

- Commit `51215d7`:  
  https://github.com/panospao7/Cost-agregator/commit/51215d760fd29a9bbdbdec53b4e508e0c119d09f

- `RestoreDiagnosticsSink.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt

- `RestoreJournal.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `DatabaseBackupRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `NotificationProcessingPipeline.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt