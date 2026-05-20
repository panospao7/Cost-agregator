# Durable Diagnostics Deep Review — Commit `51215d7`

Commit reviewed: `51215d760fd29a9bbdbdec53b4e508e0c119d09f`  
Previous reviewed commit: `a8e1c94ee41cc05d19904921e1ba0a280d7907ee`

Mode: static GitHub source review. I did **not** run Gradle/tests.

---

## Executive verdict

This commit fixes a meaningful part of the previous `DDL-A8` issues:

- `RestoreDiagnosticsSink` now receives `RestoreJournal`.
- `RestoreJournal.appendEvent()` now reads raw JSON instead of rebuilding only from `JournalEntry`.
- `writeJournal()` preserves an existing `events` array.
- `RESTART_REQUIRED` was moved before `commitJournal()`.
- `OperationRunEvent.eventId` and migrations were added.
- `SafeSinkOperationRunHandle` has terminal-once behavior.
- operation counter increments and stale recovery event inserts are now best-effort.
- `CreateExpenseRequest` and `TransactionEvent` now have correlation fields.
- metadata hash-value validation and unknown-object string sanitization were improved.
- `DiagnosticsRepository.getRecentFailures()` now aggregates several sources.

However, the refactor is **still not complete**. The biggest remaining problems are:

1. Restore failure events are still often written **after** `failJournal()`, so they are not appended to the failure journal.
2. `MAINTENANCE_ENTERED` is emitted before the restore journal exists, so it is missing from the journal trail.
3. Restore journal events still lose metadata.
4. `operation_run_events.eventId` has a migration index but the entity does not declare the index.
5. Notification correlation is passed into `NotificationProcessingPipeline.process(...)` but is not actually propagated into `processInternal`, `CreateExpenseRequest`, pending review creation, or pipeline diagnostics.
6. `TransactionLifecycleCoordinator` writes `correlationId` only on `CREATE_ATTEMPTED`; most transaction events still lose it.
7. `DiagnosticsRepository.getRecentFailures()` still misses restore failure journals and some operation failure outcomes.
8. Metadata hash validation does not apply inside `sanitizeJsonString(...)`.
9. Backup/reset/legacy restore operation instrumentation remains incomplete.

---

# 1. What is resolved or mostly resolved

## 1.1 RestoreDiagnosticsSink now has RestoreJournal

Status: **partially resolved**

Good:

- `RestoreDiagnosticsSink` now injects `RestoreJournal`.
- `event(...)` calls `restoreJournal.appendEvent(...)` before safe sink / Room event writes.
- Room operation events are disabled after `markLiveDbSwapStarted()`.

Remaining:

- failure ordering bugs still prevent many terminal events from reaching the failure journal.
- metadata is not passed into restore journal events.

Files:

```text
data/backup/RestoreDiagnosticsSink.kt
data/backup/RestoreJournal.kt
```

---

## 1.2 RestoreJournal append preserves previous events

Status: **mostly resolved**

Good:

- `appendEvent()` reads raw JSON.
- `writeJournal()` preserves an existing `events` array.
- `commitJournal()` should now preserve events when renaming to success journal.

Remaining:

- events are still lost when code calls `failJournal()` before `restoreEvents.event(...)`.
- `events` are not metadata-complete.
- the active/success/failure journal model is still mixed with internal recovery paths.

---

## 1.3 RESTART_REQUIRED ordering

Status: **mostly resolved**

Good:

- `RESTART_REQUIRED` is now emitted before `transitionTo(COMPLETE)` and `commitJournal()` in the main `.costbackup` restore path.

Remaining:

- reset/import paths still do not appear to emit equivalent terminal operation events.

---

## 1.4 Operation run event reliability

Status: **improved**

Good:

- `OperationRunHandle.event()` is best-effort.
- `increment()` is best-effort.
- stale recovery event insert is best-effort.
- safe handle terminal-once exists.
- `runOperation()` in the Room recorder checks `isTerminal`.

Remaining:

- `CompositeOperationRunRecorder.runOperation()` still unconditionally calls `success()`. Safe handle terminal-once and Room `finalizeIfRunning()` mostly protect this, but the code does not match the intended contract.
- `RoomOperationRunRecorder.Handle` does not override `isTerminal`, so in-memory terminal status is not accurate for Room handles.

---

## 1.5 Metadata hardening

Status: **mostly resolved**

Good:

- arbitrary `*Hash` suffixes are no longer trusted.
- known hash keys require hex-like values in `sanitizeValue`.
- unknown objects go through string sanitizer.

Remaining:

- hash-value validation is bypassed in `sanitizeJsonString()` / `sanitizeJsonObject()`.

Details below.

---

# 2. Critical/high-priority remaining issues

---

## DDL-512-01 — restore terminal failure events are appended after `failJournal()`, so they are lost

Severity: **Critical**  
Type: **actual restore diagnostics bug**  
Pipeline: P7 Backup/restore

Several restore failure branches call:

```text
restoreJournal.failJournal(...)
restoreEvents.event(...)
```

This is backwards.

`failJournal()` renames the active journal to the failure journal. After that, `RestoreDiagnosticsSink.event(...)` calls `restoreJournal.appendEvent(...)`, which reads only the active journal file. Since the active file was already renamed, the terminal event is not appended.

Observed patterns:

```text
extraction / wrong password:
  restoreJournal.failJournal(...)
  restoreEvents.event("BUNDLE_VALIDATED", FAILED_FINAL)

empty manifest:
  restoreJournal.failJournal(...)
  restoreEvents.event("BUNDLE_VALIDATED", FAILED_FINAL)

swap failure:
  restoreJournal.failJournal(...)
  restoreEvents.event("LIVE_DB_SWAP_FAILED", FAILED_FINAL)

verification rollback success:
  restoreJournal.failJournal(...)
  restoreEvents.event("ROLLBACK_COMPLETED", COMPLETED)
```

Impact:

- failure journal can miss the most important terminal event.
- support sees an incomplete stage trail.
- importer/debug trace may not show why restore failed.

Fix strategy:

Always emit/append the terminal event **before** `failJournal()`:

```kotlin
restoreEvents.event(
    stage = "BUNDLE_VALIDATED",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.ERROR,
    reasonCode = DiagnosticReasonCode.VALIDATION_FAILED,
    exception = error,
    isTerminal = true
)

restoreEvents.finalizeRunFailed(...)
restoreJournal.failJournal(journalEntry, ...)
```

Alternative: add `restoreJournal.appendEventToFailureJournal(...)`, but ordering is simpler and safer.

Required tests:

```text
restore_wrong_password_failure_journal_contains_failed_final_event
restore_empty_manifest_failure_journal_contains_failed_final_event
restore_swap_failure_journal_contains_live_db_swap_failed
restore_rollback_completed_event_written_before_fail_journal
```

---

## DDL-512-02 — `MAINTENANCE_ENTERED` is emitted before the journal exists

Severity: **High**  
Type: **restore stage-history gap**

Current sequence:

```text
enter maintenance
restoreEvents.event("MAINTENANCE_ENTERED")
restoreJournal.beginJournal(...)
restoreEvents.event("JOURNAL_CREATED")
```

Because there is no active journal yet, `restoreJournal.appendEvent(...)` returns without appending `MAINTENANCE_ENTERED`.

Impact:

- committed success/failure journal lacks the first required stage.
- operation run/safe sink may have it, but restore journal is not complete.

Fix options:

Option A — begin journal earlier:

```kotlin
val liveDbFile = context.getDatabasePath(...)
val stagedDbFile = ...
var journalEntry = restoreJournal.beginJournal(...)
restoreEvents.event("STARTED", ATTEMPTED)

maintenanceOperationRunner.enterAndDrain(...)
restoreEvents.event("MAINTENANCE_ENTERED", COMPLETED)
```

Option B — buffer events in `RestoreDiagnosticsSink` until `beginJournal()` exists, then flush. More complex.

Recommended: **Option A**.

Required tests:

```text
restore_success_journal_contains_started_event
restore_success_journal_contains_maintenance_entered_event
restore_failure_journal_contains_maintenance_entered_event
```

---

## DDL-512-03 — restore journal event metadata is dropped

Severity: **Medium/High**  
Type: **support/debug gap**

`RestoreJournalEvent` has:

```kotlin
metadataJson: String?
```

But `RestoreDiagnosticsSink.event(...)` does not pass metadata to `appendEvent()`, and `appendEvent()` currently creates events with:

```text
metadataJson = null
```

Also `serializeEvents()` / `parseEvents()` do not preserve metadata.

Impact:

- asset names/hashes, row counts, stage counts, retryability, and summaries are lost.
- imported `OperationRunEvent.metadataJson` from restore journal is null.
- debug trace is less useful.

Fix:

Add metadata to `appendEvent()`:

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

From `RestoreDiagnosticsSink`:

```kotlin
metadataJson = if (metadata.isEmpty()) null else metadata.toJson()
```

Then serialize/parse:

```text
metadataJson -> "metadataJson"
```

Required tests:

```text
restore_journal_event_preserves_metadata_json
restore_journal_event_metadata_is_sanitized
restore_importer_imports_metadata_json_to_operation_run_event
```

---

## DDL-512-04 — `operation_run_events.eventId` index exists in migration but not in entity

Severity: **High**  
Type: **schema/migration correctness bug**

Migration creates:

```sql
CREATE INDEX IF NOT EXISTS index_operation_run_events_eventId
ON operation_run_events(eventId)
```

But `OperationRunEvent` entity indices do not include:

```kotlin
Index(value = ["eventId"])
```

Impact:

- migrated DB may have an index that fresh installs do not.
- Room schema validation can fail because actual migrated schema differs from entity-declared schema.
- query performance differs between fresh install and migrated install.

Fix:

Add the index to the entity:

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
```

Optional stronger idempotency:

```kotlin
Index(value = ["eventId"], unique = true)
```

Only use `unique = true` if all non-null event IDs are guaranteed globally unique.

Required tests:

```text
fresh_schema_contains_operation_run_event_eventId_index
migration_127_128_schema_matches_entity
restore_importer_can_lookup_eventId_efficiently
```

---

## DDL-512-05 — notification correlation is accepted but still not propagated

Severity: **High**  
Type: **traceability bug**  
Pipelines: P1 Notification, P2 Transaction

`NotificationRepository.processAndSave(...)` accepts `correlationId` and passes it to:

```kotlin
pipeline.process(..., correlationId = correlationId)
```

But inside `NotificationProcessingPipeline.process(...)` the value is not used:

```kotlin
val outcome = processInternal(notification, storageNotification, initializeClassifier = true)
```

`processInternal(...)` has no correlation parameter. Downstream:

- `handleAutoAcceptInTransaction(...)` receives no correlation.
- `CreateExpenseRequest` is built without `correlationId`.
- `TransactionLifecycleEventWriter.write(AI_AUTO_ACCEPT)` does not get correlation.
- `writePipelineDiagnosticEvent(...)` emits a new/default correlation.

Impact:

A notification listener `RECEIVED` event cannot be traced through:

```text
notification -> raw row -> review/expense -> transaction event -> side effects
```

Fix:

Thread correlation through the pipeline:

```kotlin
suspend fun process(..., correlationId: String? = null) {
    val cid = correlationId ?: CorrelationIds.newId()
    val outcome = processInternal(..., correlationId = cid)
    writePipelineDiagnosticEvent(outcome, packageName, correlationId = cid)
}
```

Update:

```kotlin
processInternal(..., correlationId: String)
handleAutoAcceptInTransaction(..., correlationId: String)
handleNeedsReviewInTransaction(..., correlationId: String)
```

When creating an expense:

```kotlin
CreateExpenseRequest(..., correlationId = correlationId)
```

When writing AI audit:

```kotlin
TransactionLifecycleEvent(..., correlationId = correlationId)
```

For pending review, either add a correlation field if schema supports it, or emit a correlated diagnostic/lifecycle event.

Required tests:

```text
notification_pipeline_diagnostic_uses_listener_correlation
notification_auto_accept_create_request_uses_listener_correlation
notification_ai_auto_accept_event_uses_listener_correlation
notification_needs_review_event_uses_listener_correlation
```

---

## DDL-512-06 — `TransactionLifecycleCoordinator` only propagates correlation on `CREATE_ATTEMPTED`

Severity: **High**  
Type: **traceability bug**  
Pipelines: P2 Transaction, P10 Bank, P1 Notification

`TransactionEvent` now has `correlationId`, but most inserts do not set it.

Currently observed:

```text
CREATE_ATTEMPTED -> correlationId = request.correlationId
CREATE_VALIDATION_FAILED -> missing
CREATED -> missing
CREATE_INSERT_CONFLICT -> missing
CREATE_DUPLICATE_SKIPPED -> missing
writeDuplicateEvent(...) -> missing
UPDATED events -> no correlation API
side effects -> dispatchPostCreationSideEffects does not pass correlation
```

Impact:

Bank sync creates a `CreateExpenseRequest.copy(correlationId = run.correlationId)`, but the important `CREATED` transaction event still has null correlation.

Fix:

Use a local correlation at transaction boundary:

```kotlin
val correlationId = request.correlationId ?: CorrelationIds.newId()
```

Apply it to every event for the create attempt:

```kotlin
TransactionEvent(..., correlationId = correlationId)
```

Update helper:

```kotlin
writeDuplicateEvent(..., correlationId: String)
```

Update side effects:

```kotlin
dispatchPostCreationSideEffects(insertedId, request.source, correlationId)
```

Update `TransactionSideEffectDispatcher` if not already correlation-aware.

Required tests:

```text
transaction_create_attempted_uses_request_correlation
transaction_validation_failed_uses_request_correlation
transaction_created_uses_request_correlation
transaction_insert_conflict_uses_request_correlation
transaction_duplicate_uses_request_correlation
bank_transaction_imported_and_transaction_created_share_correlation
```

---

## DDL-512-07 — hash validation is bypassed in `sanitizeJsonString()`

Severity: **Medium/High privacy risk**  
Type: **privacy bug**

`sanitizeValue(key, value)` validates known hash keys:

```text
sourceIdHash must be hex-like
providerTransactionIdHash must be hex-like
```

But `sanitizeJsonString()` goes through `sanitizeJsonObject()`, and for string values it uses:

```kotlin
sanitizeStringValue(v)
```

not:

```kotlin
sanitizeValue(key, v)
```

So JSON like:

```json
{"sourceIdHash":"plain raw external id"}
```

can survive final-pass JSON sanitization.

Impact:

Writers that sanitize already-built JSON can persist raw values under hash-looking keys.

Fix:

In `sanitizeJsonObject`, use `sanitizeValue(key, value)` for all non-container values, and for containers still preserve key policy:

```kotlin
private fun sanitizeJsonObject(obj: JSONObject): JSONObject {
    val result = JSONObject()
    for (key in obj.keys()) {
        val value = obj.get(key)
        result.put(
            key,
            when (value) {
                is JSONObject -> if (isDangerousKey(key)) REDACTED else sanitizeJsonObject(value)
                is JSONArray -> if (isDangerousKey(key)) REDACTED else sanitizeJsonArray(value)
                else -> sanitizeValue(key, value)
            }
        )
    }
    return result
}
```

Required tests:

```text
sanitize_json_string_redacts_plain_source_id_hash
sanitize_json_string_redacts_plain_provider_transaction_id_hash
diagnostic_writer_final_pass_redacts_plain_hash_key_values
operation_run_writer_final_pass_redacts_plain_hash_key_values
```

---

# 3. Medium-priority remaining issues

---

## DDL-512-08 — restore journal still mixes diagnostics and recovery-only paths

Severity: **Medium/High privacy risk**  
Type: **privacy/design gap**

`JournalEntry.toJson()` still writes internal path fields:

```text
_sourceBackupPath
_stagedDbPath
_safetyBackupPath
_liveDbPath
```

`toDiagnosticsJson()` strips them, which is good, but the same durable file still contains them.

Impact:

- accidental support export can leak local paths.
- future debug code may read raw JSON instead of diagnostics JSON.
- the privacy rule is not enforced by storage separation.

Fix:

Preferred final architecture:

```text
restore_recovery_journal.json      // pathful, internal only
restore_diagnostics_journal.json   // privacy-safe, support/debug only
```

Short-term hardening:

- make every diagnostics/debug/support path call `toDiagnosticsJson()`.
- add a static guard or tests preventing raw success/failure journal export.
- add `getDiagnosticsJsonFor...()` APIs and keep raw file private.

Required tests:

```text
diagnostics_repository_never_exposes_internal_path_fields
support_export_never_contains_internal_restore_paths
restore_recovery_journal_roundtrips_internal_paths
```

---

## DDL-512-09 — receipt asset restore lacks durable stage events

Severity: **Medium**  
Type: **restore support gap**

`restoreReceiptAssets(...)` updates the journal asset ledger, but no durable stage events are emitted:

```text
ASSETS_RESTORING
ASSET_RESTORED
ASSET_FAILED
```

Search found no `ASSET_RESTORED` or `ASSET_FAILED` events.

Impact:

- asset failures are only returned as warnings and ledger changes.
- restore journal event trail misses asset-level outcomes.
- support cannot query recent failures for asset restore failures.

Fix:

Pass `RestoreDiagnosticsSink` into `restoreReceiptAssets(...)`.

Emit:

```kotlin
restoreEvents.event("ASSETS_RESTORING", ATTEMPTED)

restoreEvents.event(
    "ASSET_RESTORED",
    COMPLETED,
    metadata = SafeEventMetadata.builder()
        .put("receiptId", receiptId)
        .putHashed("assetRelativePath", assetFile.name)
        .build()
)

restoreEvents.event(
    "ASSET_FAILED",
    FAILED_FINAL,
    severity = WARNING,
    reasonCode = UNKNOWN_ERROR,
    exception = e,
    metadata = ...
)
```

Do not store full file paths.

Required tests:

```text
asset_restore_success_writes_asset_restored_event
asset_restore_failure_writes_asset_failed_event
asset_restore_event_does_not_include_full_path
```

---

## DDL-512-10 — `DiagnosticsRepository.getRecentFailures()` misses restore failure journals

Severity: **Medium**  
Type: **support/debug bug**

Current restore-journal failure aggregation uses:

```text
getSuccessJournalEvents()
plus(getEventsByCorrelationId(""))
```

Problems:

- `getEventsByCorrelationId("")` only returns active journal events with blank correlation, so it misses real active events.
- failure journal is not included.
- success journal usually should not be a failure source unless it contains warning/error events.

Fix:

Add:

```kotlin
restoreJournal.getAllDiagnosticEvents()
```

or:

```kotlin
getRecentDiagnosticEvents()
```

that reads active + success + failure journals.

Then filter:

```text
severity in WARNING/ERROR/CRITICAL
or outcome in FAILED_RETRYABLE, FAILED_FINAL, BLOCKED, DROPPED, CANCELLED, SIDE_EFFECT_FAILED
```

Required tests:

```text
recent_failures_includes_restore_failure_journal_failed_final
recent_failures_includes_rollback_failed_critical
recent_failures_includes_active_restore_failure
recent_failures_does_not_require_blank_correlation
```

---

## DDL-512-11 — operation recent-failure query misses `BLOCKED` and `SIDE_EFFECT_FAILED`

Severity: **Low/Medium**  
Type: **support/debug gap**

`OperationRunEventDao.getRecentFailures()` currently includes only:

```text
FAILED_RETRYABLE
FAILED_FINAL
CANCELLED
```

Missing:

```text
BLOCKED
DROPPED
SIDE_EFFECT_FAILED
```

Fix query:

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
```

Required test:

```text
operation_recent_failures_includes_blocked_and_side_effect_failed
```

---

## DDL-512-12 — backup export still has unfinalized early failures

Severity: **Medium/High**  
Type: **operation-run completeness bug**  
Pipeline: P7 Backup

`createCostBackup(...)` starts `BACKUP_EXPORT`, but several early failures return without finalizing the operation run:

Examples:

```text
checkpointWal() failure
database file missing
snapshot verification failed
```

Some later failures call `run.failedFinal(...)`, but not all.

Impact:

- backup operation can remain `RUNNING`.
- support sees no terminal operation result.

Fix:

Use `operationRunRecorder.runOperation("BACKUP_EXPORT") { run -> ... }` or ensure every return path calls terminal status.

Pattern:

```kotlin
if (checkpointResult.isFailure) {
    run.event("WAL_CHECKPOINTED", FAILED_FINAL, ...)
    run.failedFinal("Failed to checkpoint WAL", checkpointResult.exceptionOrNull())
    return@withContext Result.failure(...)
}
```

Required tests:

```text
backup_checkpoint_failure_finalizes_operation_run
backup_missing_db_file_finalizes_operation_run
backup_snapshot_verification_failure_finalizes_operation_run
```

---

## DDL-512-13 — reset/legacy import still bypass operation-run diagnostics

Severity: **Medium**  
Type: **architecture/support gap**  
Pipeline: P7 Backup/restore

The original plan included operation types:

```text
RESET_DATABASE
RESTORE_LEGACY_DB
```

But `resetDatabase()` and legacy `importDatabase()` still use mostly direct `RestoreJournal` / maintenance calls. They do not appear to use `OperationRunRecorder` or `RestoreDiagnosticsSink`.

Impact:

- reset/import operations do not have uniform `STARTED -> terminal` operation trails.
- diagnostics repository cannot query them the same way as `.costbackup` restore.

Fix:

Wrap with operation recorder:

```kotlin
operationRunRecorder.runOperation("RESET_DATABASE", actor = "user") { run ->
    val restoreEvents = RestoreDiagnosticsSink(...)
    ...
}
```

Similarly for:

```text
RESTORE_LEGACY_DB
```

Required tests:

```text
reset_database_writes_operation_started_and_terminal
legacy_import_writes_operation_started_and_terminal
reset_database_restore_journal_contains_restart_required
legacy_import_failure_writes_failed_final_event
```

---

## DDL-512-14 — notification RECEIVED/terminal ordering race remains

Severity: **Medium**  
Type: **durability race**

Normal listener path still emits `RECEIVED` with independent:

```kotlin
serviceScope.launch { diagnosticEventWriter.emit(RECEIVED) }
```

Early terminal events use different jobs, often `workTracker.launch(...)`.

Refresh path still uses direct `serviceScope.launch` for several early terminal events.

Impact:

- terminal event can be stored before `RECEIVED`.
- `RECEIVED` can be cancelled independently.
- shutdown/refresh paths may still lose ordering.

Fix:

Use one helper:

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

Or move `RECEIVED` emission into the same work-tracked coroutine for each path.

Required tests:

```text
notification_restore_blocked_received_before_blocked
notification_dedupe_received_before_duplicate
refresh_filter_received_before_dropped
refresh_shutdown_received_before_cancelled
```

---

# 4. Acceptance matrix after `51215d7`

| Criterion | Status | Notes |
|---|---:|---|
| RestoreDiagnosticsSink appends to journal | Partial | Yes, but some events happen before journal exists or after failJournal |
| RestoreJournal append preserves events | Mostly | Raw JSON preservation added |
| RESTART_REQUIRED in success journal | Mostly | Main costbackup path improved |
| Failure journal contains terminal failure | Not yet | failJournal often called before terminal event |
| Restore journal metadata preserved | Not yet | metadataJson always null |
| OperationRunEvent eventId schema consistent | Not yet | migration index exists, entity lacks index |
| Safe handle terminal-once | Mostly | Good; Composite runOperation still unconditionally success but protected |
| Operation increments best-effort | Mostly | Good |
| Metadata hash validation | Partial | sanitizeValue yes; sanitizeJsonString no |
| Notification correlation to repository | Partial | service -> repo yes |
| Notification correlation through pipeline/domain | Not yet | pipeline ignores correlation internally |
| Bank correlation to request | Partial | request copy gets correlation |
| Bank correlation to TransactionEvent.CREATED | Not yet | coordinator does not set it |
| Recent failures combined | Partial | misses restore failure journal and some operation outcomes |
| Restore asset events | Not yet | no ASSET_RESTORED/ASSET_FAILED |
| Backup/reset/import operation completeness | Partial | restoreCostBackup improved; backup/reset/import still incomplete |
| Regression tests | Partial | improved but still mostly model/contract tests, not behavior/integration tests |

---

# 5. Recommended next PR order

## PR 1 — Restore failure-journal correctness

Fix:

```text
DDL-512-01
DDL-512-02
DDL-512-03
```

Tasks:

```text
- emit terminal failure event before failJournal()
- create journal before MAINTENANCE_ENTERED or buffer early events
- preserve metadataJson in restore journal events
```

## PR 2 — Schema/migration correctness

Fix:

```text
DDL-512-04
```

Tasks:

```text
- add Index(value=["eventId"]) to OperationRunEvent entity
- add migration/fresh schema validation tests
```

## PR 3 — Correlation propagation

Fix:

```text
DDL-512-05
DDL-512-06
```

Tasks:

```text
- pass correlation into NotificationProcessingPipeline.processInternal()
- pass correlation into CreateExpenseRequest and AI audit event
- set correlationId on all transaction create/duplicate/failure events
- propagate correlation into side effects
```

## PR 4 — Metadata final-pass fix

Fix:

```text
DDL-512-07
```

Tasks:

```text
- make sanitizeJsonObject use sanitizeValue(key, value)
- add final-pass writer tests for hash keys
```

## PR 5 — Debug/recent failure completeness

Fix:

```text
DDL-512-10
DDL-512-11
```

Tasks:

```text
- read active/success/failure restore journals for recent failures
- expand operation failure query criteria
```

## PR 6 — Restore/backup operation completeness

Fix:

```text
DDL-512-08
DDL-512-09
DDL-512-12
DDL-512-13
```

Tasks:

```text
- split recovery vs diagnostics journal or harden exports
- emit asset restore events
- finalize backup early failures
- add operation runs for reset/legacy import
```

## PR 7 — Notification ordering

Fix:

```text
DDL-512-14
```

Tasks:

```text
- replace fire-and-forget RECEIVED/terminal emissions with ordered work-tracked helper
```

---

# 6. Highest-priority regression tests to add

```text
restore_wrong_password_failure_journal_contains_failed_final_event
restore_empty_manifest_failure_journal_contains_failed_final_event
restore_success_journal_contains_maintenance_entered_event
restore_journal_event_preserves_metadata_json
migration_128_129_schema_matches_entity_indices
sanitize_json_string_redacts_plain_source_id_hash
notification_auto_accept_transaction_created_uses_listener_correlation
bank_created_transaction_event_uses_bank_sync_correlation
recent_failures_includes_restore_failure_journal
backup_checkpoint_failure_finalizes_operation_run
operation_recent_failures_includes_blocked
```

---

# 7. Source links checked

Commit:

- https://github.com/panospao7/Cost-agregator/commit/51215d760fd29a9bbdbdec53b4e508e0c119d09f

Key files:

- `RestoreDiagnosticsSink.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt

- `RestoreJournal.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `RestoreJournalImporter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt

- `DatabaseBackupRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `OperationRunRecorder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt

- `CompositeOperationRunRecorder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt

- `EventMetadataSanitizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt

- `DiagnosticsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/domain/debug/DiagnosticsRepository.kt

- `OperationRunEvent.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/database/entity/OperationRunEvent.kt

- `OperationRunEventDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunEventDao.kt

- `TransactionEvent.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- `TransactionLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `NotificationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt

- `NotificationProcessingPipeline.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `NotificationCaptureService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `BankApiIntegration.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `AppDatabase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- `DurableDiagnosticsA8RegressionTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/51215d760fd29a9bbdbdec53b4e508e0c119d09f/app/src/test/java/com/yourname/expensetracker/diagnostics/DurableDiagnosticsA8RegressionTest.kt