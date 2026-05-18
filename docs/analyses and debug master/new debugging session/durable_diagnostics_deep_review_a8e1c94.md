# Durable Diagnostics Deep Review — Commit `a8e1c94`

Commit reviewed: `a8e1c94ee41cc05d19904921e1ba0a280d7907ee`  
Previous reviewed commit: `016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e`

Mode: static source review from GitHub. I did **not** execute Gradle/tests.

---

## Executive verdict

The commit fixes several previously identified issues, especially:

- stable `DiagnosticEvent.eventId`
- best-effort `OperationRunHandle.event()`
- safe operation handle emits `STARTED`
- metadata sanitizer no longer trusts arbitrary `*Hash` suffixes
- nested metadata sanitation is improved
- bank sync starts operation run before write barrier
- `DiagnosticsRepository` no longer uses endless `collect`
- DI binding for `DiagnosticsRepository` exists

However, several important problems remain. The largest area is still **restore diagnostics**.

The most serious findings:

1. `RestoreDiagnosticsSink` does **not** append events to `RestoreJournal`, despite comments/commit summary.
2. `RestoreJournal.appendEvent()` is not actually append-only; previous events are lost.
3. `transitionTo()` / `writeJournal()` can erase the `events` array.
4. Successful restore journal import will usually import **zero events**.
5. `RESTART_REQUIRED` is emitted **after** `commitJournal()`, so it cannot land in the success journal.
6. Some restore failure paths still do not finalize the operation run.
7. Restore journal still stores full internal paths in the same JSON file.
8. Bank/notification correlation is still not propagated into transaction lifecycle events.
9. `SafeSinkOperationRunHandle` can emit multiple terminal events.
10. Tests are still mostly structural and do not catch the critical restore/journal bugs.

---

# 1. What is now resolved or mostly resolved

## 1.1 Stable diagnostic event IDs

Status: **mostly resolved**

`DiagnosticEvent` now has:

```kotlin
val eventId: String = CorrelationIds.newId()
```

`RoomDiagnosticEventWriter` stores `event.eventId`, so Room and safe-sink fallback can use the same logical event ID.

Remaining:
- Ensure `MaintenanceSafeDiagnosticSink.recordDiagnosticEvent()` also always persists `event.eventId`.

Files:
- `DiagnosticEventWriter.kt`

---

## 1.2 Operation event writes are best-effort

Status: **mostly resolved**

`RoomOperationRunRecorder.Handle.event()` now wraps `eventDao.insert(...)` in `runCatching`.

This fixes the previous issue where an operation event insert could fail the business operation.

Remaining:
- `increment()` is still not best-effort.
- stale recovery `eventDao.insert(...)` is still not best-effort.
- safe-sink operation handles are not idempotently terminal.

Files:
- `OperationRunRecorder.kt`

---

## 1.3 Safe operation handle emits `STARTED`

Status: **mostly resolved**

`CompositeOperationRunRecorder.safeHandle()` now emits:

```text
STARTED / ATTEMPTED
```

This fixes the missing `STARTED` event for maintenance-safe operation runs.

Remaining:
- safe handle does not include enough operation metadata consistently.
- safe handle can emit multiple terminal events.

Files:
- `CompositeOperationRunRecorder.kt`

---

## 1.4 Metadata sanitizer hash-suffix bypass

Status: **mostly resolved**

The sanitizer now uses `SAFE_HASH_KEYS` instead of trusting all keys ending with `hash`.

Good:
- `rawTextHash` is dangerous.
- `accessTokenHash` is dangerous.
- exact known keys like `providerTransactionIdHash` are allowed.

Remaining:
- exact safe hash keys do not validate that the value is actually a hash.
- unknown object `toString()` can still leak because `sanitizeValue()` uses `.toString().take(...)` instead of full string sanitizer.

Files:
- `EventMetadataSanitizer.kt`
- `SafeEventMetadata.kt`

---

## 1.5 DiagnosticsRepository hang fixed

Status: **mostly resolved**

`DiagnosticsRepositoryImpl.getTraceByCorrelationId()` uses:

```kotlin
safeSink.observeRecent().first()
```

instead of endless `collect`.

DI binding exists:

```kotlin
bindDiagnosticsRepository(...)
```

Remaining:
- trace includes only active restore journal events, not success/failure journals.
- `getRecentFailures()` only returns pipeline events, not operation/safe-sink/journal failures.

Files:
- `DiagnosticsRepository.kt`
- `DiagnosticsModule.kt`

---

# 2. Critical/high-priority remaining issues

---

## DDL-A8-01 — `RestoreDiagnosticsSink` does not write to `RestoreJournal`

Severity: **Critical**  
Type: **actual restore diagnostics bug**  
Pipeline: P7 Backup/restore

Commit summary says restore diagnostics now write journal + safe sink and Room only before swap.

Actual `RestoreDiagnosticsSink` constructor has:

```kotlin
private val operationRunHandle: OperationRunHandle?
private val safeSink: MaintenanceSafeDiagnosticSink
private val maintenanceMode: RestoreMaintenanceMode
```

It does **not** receive `RestoreJournal`.

Its `event(...)` method writes:

```text
safeSink.recordDiagnosticEvent(...)
operationRunHandle.event(...) if roomAllowed
```

It never calls:

```kotlin
restoreJournal.appendEvent(...)
```

Impact:

- restore journal `events[]` is not populated by restore stages
- success journal importer imports no operation events
- restore trace by correlation is incomplete
- post-swap journal-only guarantee is not true

Fix:

```kotlin
class RestoreDiagnosticsSink(
    private val operationRunHandle: OperationRunHandle?,
    private val restoreJournal: RestoreJournal,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    ...
)
```

Then in `event(...)`:

```kotlin
runCatching {
    restoreJournal.appendEvent(
        correlationId = correlationId,
        stage = stage,
        outcome = outcome.name,
        severity = severity.name,
        reasonCode = reasonCode?.name,
        exceptionClass = exception?.javaClass?.simpleName,
        exceptionMessageSafe = sanitizer.sanitizeExceptionMessage(exception?.message),
        metadataJson = metadata.toJsonOrNull(),
        isTerminal = isTerminal
    )
}
```

Acceptance tests:

```text
restore_diagnostics_sink_appends_event_to_restore_journal
restore_success_journal_contains_restore_events
restore_restart_required_is_in_success_journal
```

---

## DDL-A8-02 — `RestoreJournal.appendEvent()` is not append-only

Severity: **Critical**  
Type: **actual restore diagnostics bug**

Current logic:

```kotlin
val entry = readJournal() ?: return
val events = parseEvents(entry.toJson())
val updatedEvents = events + event
val json = entry.toJson()
json.put("events", serializeEvents(updatedEvents))
```

Problem:

- `readJournal()` returns `JournalEntry`.
- `JournalEntry` does not contain `events`.
- `entry.toJson()` does not contain existing `events`.

Therefore:

```text
events is always empty
each append overwrites previous events
only the latest event survives
```

Worse, any later `writeJournal(entry)` / `transitionTo(...)` writes `entry.toJson()` and removes the `events` array entirely.

Impact:

- restore event history is not durable
- importer cannot reconstruct operation events
- support cannot see the stage sequence

Fix:

Use raw journal JSON as the source of truth for events:

```kotlin
fun appendEvent(...) {
    val json = readJournalJson() ?: return
    val events = parseEvents(json)
    json.put("events", serializeEvents(events + event))
    writeJournalJson(json)
}
```

And preserve `events` in `writeJournal`:

```kotlin
fun writeJournal(entry: JournalEntry) {
    val oldJson = readJournalJson()
    val oldEvents = oldJson?.optJSONArray("events")
    val json = entry.toJson()
    if (oldEvents != null) json.put("events", oldEvents)
    writeJsonAtomically(json)
}
```

Acceptance tests:

```text
restore_journal_append_event_keeps_previous_events
restore_transition_to_preserves_events_array
restore_write_journal_preserves_events_array
restore_success_journal_has_full_stage_history
```

---

## DDL-A8-03 — `RESTART_REQUIRED` is emitted after `commitJournal()`

Severity: **High**  
Type: **restore trace correctness bug**

Current sequence:

```kotlin
restoreJournal.commitJournal(journalEntry)
restoreMaintenanceMode.exit(forceRestartRequired = true)
restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.COMPLETE)
restoreEvents.event("RESTART_REQUIRED", COMPLETED, isTerminal = true)
```

Problems:

1. `commitJournal()` renames active `restore_journal.json` to `restore_journal_last_success.json`.
2. After that, there is no active journal for `appendEvent()` to update.
3. Then `transitionTo(...)` recreates a new active `restore_journal.json` with state `COMPLETE`.
4. On startup, `checkAndRecover()` may delete that active complete journal.
5. The success journal misses `RESTART_REQUIRED`.

Fix sequence:

```kotlin
restoreEvents.event("LIVE_DB_VERIFIED", COMPLETED)
restoreEvents.event("RESTART_REQUIRED", COMPLETED, isTerminal = true)

journalEntry = restoreJournal.transitionTo(journalEntry, COMPLETE)

restoreJournal.commitJournal(journalEntry)
restoreMaintenanceMode.exit(forceRestartRequired = true)
```

Do **not** call `transitionTo()` after `commitJournal()`.

Acceptance tests:

```text
restore_restart_required_written_before_commit_journal
restore_commit_journal_preserves_terminal_restart_required_event
restore_success_does_not_recreate_active_complete_journal
```

---

## DDL-A8-04 — restore still directly calls `run.failedFinal()` after swap starts

Severity: **High**  
Type: **restore safety bug**

In swap failure catch, after:

```kotlin
restoreEvents.markLiveDbSwapStarted()
```

the code still does:

```kotlin
run.failedFinal("Database swap failed", e)
```

Even though `OperationRunHandle.failedFinal()` is now best-effort, this violates the safety rule:

```text
After live DB close/swap starts, do not use the old Room operation handle.
```

Fix:

Replace with journal/safe sink only:

```kotlin
restoreEvents.event(
    "LIVE_DB_SWAP_FAILED",
    FAILED_FINAL,
    severity = ERROR,
    reasonCode = UNKNOWN_ERROR,
    exception = e,
    isTerminal = true
)
```

Do not call `run.failedFinal(...)` after `markLiveDbSwapStarted()`.

Acceptance tests:

```text
swap_failure_after_mark_swap_started_does_not_call_room_run_failed_final
swap_failure_writes_journal_and_safe_sink_event
```

---

## DDL-A8-05 — pre-swap restore failures are still incompletely finalized

Severity: **High**  
Type: **operation-run completeness bug**

Fixed paths:
- extraction / wrong password
- empty manifest
- staged quick verification

Still missing or incomplete:

```text
post-migration verification failed
staged migration failed
safety backup failed
safety backup path unavailable
```

These paths call `restoreJournal.failJournal(...)` and return failure, but do not consistently call:

```kotlin
restoreEvents.event(... FAILED_FINAL ...)
restoreEvents.finalizeRunFailed(...)
```

Examples found:

- post-migration verification failure returns after `restoreJournal.failJournal(...)`
- staged migration failure returns after `restoreJournal.failJournal(...)`
- safety backup failure returns after `restoreJournal.failJournal(...)`

Fix:

Create one helper and use it for every pre-swap failure:

```kotlin
private suspend fun failRestoreBeforeSwap(
    restoreEvents: RestoreDiagnosticsSink,
    journalEntry: RestoreJournal.JournalEntry,
    stage: String,
    reason: String,
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
    restoreEvents.finalizeRunFailed(reason, error)
    restoreJournal.failJournal(journalEntry, reason)
    restoreMaintenanceMode.exit(forceRestartRequired = false)
    return Result.failure(error ?: Exception(reason))
}
```

Acceptance tests:

```text
restore_post_migration_verification_failed_finalizes_operation_run
restore_staged_migration_failed_finalizes_operation_run
restore_safety_backup_failed_finalizes_operation_run
restore_safety_backup_missing_path_finalizes_operation_run
```

---

## DDL-A8-06 — restore journal still stores full paths in diagnostics JSON

Severity: **High privacy risk**  
Type: **privacy / architecture bug**

`JournalEntry.toJson()` writes both safe names and internal full paths:

```json
"_sourceBackupPath"
"_stagedDbPath"
"_safetyBackupPath"
"_liveDbPath"
```

The comment says these are “not exported”, but there is still one JSON file.

Risk:
- debug/support tooling may copy the file
- failure/success journals may be attached to support bundles later
- `DiagnosticsRepository` or future UI could expose it accidentally

Also asset target recovery is now weak:
- `toJson()` writes only `targetName`
- `fromJson()` reads `targetName` into `targetPath`
- a basename is not enough if crash recovery requires the original path

Fix properly:

Use two files or two models:

```text
restore_recovery_journal.json       // internal, pathful, never exposed
restore_diagnostics_journal.json    // privacy-safe, no full paths
```

If you keep one file temporarily:
- `DiagnosticsRepository` must never expose `_...Path`
- support export must strip `_...Path`
- add explicit tests that debug trace does not contain `/data/`, `/storage/`, `C:\`, etc.

Acceptance tests:

```text
restore_diagnostics_journal_has_no_full_paths
restore_recovery_journal_roundtrips_full_paths
diagnostics_trace_never_exposes_internal_path_fields
support_export_never_includes_internal_path_fields
asset_restore_recovery_keeps_real_target_path_in_recovery_journal
```

---

## DDL-A8-07 — restore journal importer can permanently skip failed event imports

Severity: **Medium/High**  
Type: **restore trace import bug**

`RestoreJournalImporter.importLastSuccessJournalIfPresent()` does:

```kotlin
val existing = operationRunDao.getByCorrelationId(correlationId)
if (existing != null) return
```

Then it inserts operation events one by one with `runCatching`.

Problem:

If the run insert succeeds but some/all event inserts fail:

```text
operation_runs row exists
events missing
next startup sees existing run and skips import
missing events are never retried
```

Fix:

Make import idempotent per event:

```text
if run exists, still import missing events
```

Add event identity:

```text
eventId unique/indexed in operation_run_events
```

or check by:

```text
operationRunId + stage + occurredAt + eventId
```

Acceptance tests:

```text
restore_import_retries_missing_events_when_run_exists
restore_import_partial_event_failure_is_retried_next_startup
restore_import_is_idempotent_per_event_id
```

---

## DDL-A8-08 — success journal is not deleted/marked imported after successful import

Severity: **Medium**  
Type: **support/cleanup gap**

The importer comment says “idempotent”, but it never marks success journal as imported or deletes it.

This is not immediately user-breaking, but it means:
- startup repeatedly checks the same success journal
- support tooling may see stale restore traces forever
- future imports can become confusing

Fix:

After full successful import:

```kotlin
restoreJournal.markSuccessJournalImported(correlationId)
```

or rename:

```text
restore_journal_last_success.imported.json
```

Do not delete unless you are sure no debug retention is required.

Acceptance tests:

```text
restore_success_journal_marked_imported_after_full_import
restore_import_does_not_mark_imported_if_any_event_insert_fails
```

---

# 3. Operation-run remaining issues

---

## DDL-A8-09 — safe operation handles can emit multiple terminal events

Severity: **Medium/High**  
Type: **diagnostic correctness bug**

`runOperation()` always calls:

```kotlin
run.success()
```

after the block returns.

But some blocks manually finalize:

```kotlin
run.cancelled(...)
run.failedFinal(...)
run.partialSuccess(...)
```

Room handle is protected by `finalizeIfRunning(...)`.

`SafeSinkOperationRunHandle` is not protected. It just emits terminal diagnostics every time.

Example in bank sync:

```kotlin
run.cancelled(RESTORE_BLOCKED)
return@runOperation
```

Then outer `runOperation()` calls `success()`.

Safe-sink result:

```text
STARTED
BLOCKED terminal
CANCELLED terminal
SUCCESS terminal
```

Also:

```kotlin
if (errors.isNotEmpty()) run.partialSuccess(...)
```

Then outer success emits `SUCCESS`.

Fix:

Add terminal state to `SafeSinkOperationRunHandle`:

```kotlin
@Volatile private var terminal = false

private suspend fun terminalOnce(...) {
    if (terminal) return
    terminal = true
    event(... isTerminal = true)
}
```

And expose `isTerminal` if useful:

```kotlin
interface OperationRunHandle {
    val isTerminal: Boolean
}
```

Then `runOperation()` should only call success if not terminal.

Acceptance tests:

```text
safe_handle_cancelled_then_run_operation_success_emits_only_cancelled
safe_handle_partial_success_then_success_emits_only_partial_success
safe_handle_failed_then_success_emits_only_failed
```

---

## DDL-A8-10 — operation increments can still fail business operations

Severity: **Medium**  
Type: **reliability bug**

`RoomOperationRunRecorder.Handle.increment()` calls:

```kotlin
runDao.incrementCounters(...)
```

directly.

If DB is locked/closed, this can still fail:
- bank sync loop
- import/export loops
- backup/restore counters

Fix:

Make increment best-effort:

```kotlin
override suspend fun increment(...) {
    runCatching {
        runDao.incrementCounters(...)
    }.onFailure {
        safeSink.recordDiagnosticEvent(...)
    }
}
```

Acceptance tests:

```text
operation_increment_failure_does_not_fail_bank_sync
operation_increment_failure_records_safe_diagnostic
```

---

## DDL-A8-11 — stale recovery event insert is not best-effort

Severity: **Low/Medium**  
Type: **startup resilience bug**

In `recoverStaleRunningOperationRuns()`:

```kotlin
eventDao.insert(OperationRunEvent(...))
```

is not wrapped.

If event insert fails after status update, startup recovery can throw.

Fix:

```kotlin
runCatching { eventDao.insert(...) }
    .onFailure { safeSink.recordDiagnosticEvent(...) }
```

Acceptance test:

```text
stale_recovery_status_update_survives_event_insert_failure
```

---

# 4. Notification remaining issues

---

## DDL-A8-12 — notification `RECEIVED` and terminal events are still unordered

Severity: **Medium**  
Type: **durability / trace ordering race**

`RECEIVED` is emitted via:

```kotlin
serviceScope.launch { diagnosticEventWriter.emit(RECEIVED) }
```

Early terminal events are emitted via `workTracker.launch(...)` or sometimes `serviceScope.launch(...)`.

This means:
- terminal can be written before `RECEIVED`
- `RECEIVED` can be cancelled independently
- refresh path still uses `serviceScope.launch` for several terminal events

Fix:

Use one ordered helper for `RECEIVED -> terminal`:

```kotlin
private fun emitNotificationReceivedAndMaybeTerminal(
    received: DiagnosticEvent,
    terminal: DiagnosticEvent? = null
) {
    workTracker.launch(serviceScope) {
        diagnosticEventWriter.emit(received)
        terminal?.let { diagnosticEventWriter.emit(it) }
    }
}
```

Or emit `RECEIVED` synchronously inside the same work-tracked job before early return.

Acceptance tests:

```text
notification_restore_blocked_records_received_before_blocked
notification_dedupe_records_received_before_duplicate
notification_refresh_filter_records_received_before_terminal
notification_shutdown_received_and_cancelled_same_job
```

---

## DDL-A8-13 — refresh path still uses fire-and-forget `serviceScope.launch`

Severity: **Medium**  
Type: **durability race**

In `processNotificationBypassDedupe`, fast privacy/filter/shutdown terminal events still use:

```kotlin
serviceScope.launch { diagnosticEventWriter.emit(...) }
```

not consistently `workTracker.launch`.

Fix:
- use the same helper as normal path
- remove direct `serviceScope.launch` diagnostics from refresh path

Acceptance tests:

```text
refresh_privacy_fast_terminal_is_work_tracked
refresh_filter_terminal_is_work_tracked
refresh_shutdown_terminal_is_work_tracked
```

---

## DDL-A8-14 — notification correlation is still not propagated to repository/domain lifecycle

Severity: **Medium/High**  
Type: **traceability gap**

`processNotification(...)` receives `correlationId`, but still calls:

```kotlin
repository.processAndSave(processingNotification, storageNotification)
```

No correlation is passed.

So downstream:
- raw notification row
- pending review
- created expense
- transaction lifecycle event
- side effects

likely get their own unrelated correlation or no correlation.

Fix:

Update repository API:

```kotlin
repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId = correlationId
)
```

Then propagate through:
- `NotificationRepository`
- notification processing pipeline
- pending review creation
- transaction creation request
- side-effect dispatcher

Acceptance tests:

```text
notification_success_review_created_uses_listener_correlation
notification_success_expense_created_uses_listener_correlation
notification_side_effect_uses_listener_correlation
```

---

# 5. Bank remaining issues

---

## DDL-A8-15 — bank correlation does not reach `TransactionEvent`

Severity: **Medium/High**  
Type: **traceability gap**

Bank sync now does:

```kotlin
mapTransactionToExpense(...).copy(correlationId = run.correlationId)
```

But `TransactionLifecycleCoordinator` contains no `correlationId` usage, and `TransactionEvent` has no `correlationId` column.

So the bank operation event has the bank correlation, but the actual transaction lifecycle event does not.

Fix options:

## Option A — add correlation columns to `transaction_events`

Add:

```kotlin
val correlationId: String?
val causationId: String?
```

Migration:
```sql
ALTER TABLE transaction_events ADD COLUMN correlationId TEXT;
ALTER TABLE transaction_events ADD COLUMN causationId TEXT;
CREATE INDEX IF NOT EXISTS index_transaction_events_correlationId
ON transaction_events(correlationId);
```

Then use:

```kotlin
request.correlationId ?: CorrelationIds.newId()
```

in all transaction events.

## Option B — put correlation in metadata short-term

Less ideal:

```json
{"correlationId":"..."}
```

But queryability is poor.

Acceptance tests:

```text
bank_transaction_imported_event_and_transaction_created_event_share_correlation
transaction_create_attempted_uses_request_correlation
transaction_created_uses_request_correlation
```

---

## DDL-A8-16 — bank blocked sync can produce multiple terminal events in safe mode

Severity: **Medium**  
Type: **diagnostic correctness bug**

Bank blocked path:

```kotlin
run.event("WRITE_BARRIER", BLOCKED, isTerminal = true)
run.cancelled(RESTORE_BLOCKED)
return@runOperation
```

Then `runOperation()` calls `success()` after block returns.

Room handle probably avoids final status overwrite via `finalizeIfRunning`.

Safe handle does not.

Fix this via DDL-A8-09 terminal-once logic.

Also consider returning a typed result and throwing a controlled cancellation-like exception if the operation should not be finalized as success.

Acceptance test:

```text
bank_sync_restore_blocked_safe_handle_has_only_one_terminal_status
```

---

# 6. Metadata privacy remaining issues

---

## DDL-A8-17 — exact safe hash keys do not validate hash-looking values

Severity: **Medium privacy risk**  
Type: **privacy hardening**

Current:

```kotlin
if (canonical in SAFE_HASH_KEYS) return false
```

So this can pass:

```kotlin
SafeEventMetadata.builder()
    .put("sourceIdHash", "Bought medicine at private clinic")
```

Because `sourceIdHash` is an exact safe key.

Fix:

Safe hash keys should be safe only if value matches hash format.

Option:

```kotlin
private val HASH_VALUE_PATTERN = Regex("^[a-fA-F0-9]{8,128}$")
```

In `sanitizeValue`:

```kotlin
if (canonical in SAFE_HASH_KEYS) {
    return if (value is String && HASH_VALUE_PATTERN.matches(value)) value else REDACTED
}
```

Better:
- only `putHashed()` should populate these keys
- plain `put()` to a hash key with non-hash value should redact

Acceptance tests:

```text
source_id_hash_plain_text_value_is_redacted
provider_transaction_id_hash_plain_text_value_is_redacted
source_id_hash_hex_value_is_allowed
put_hashed_source_id_hash_is_allowed
```

---

## DDL-A8-18 — unknown object `toString()` is not fully sanitized in `sanitizeValue`

Severity: **Medium privacy risk**  
Type: **privacy hardening**

In `sanitizeAny`, unknown object uses:

```kotlin
sanitizeStringValue(value.toString())
```

Good.

But in `sanitizeValue`, unknown object uses:

```kotlin
value.toString().take(MAX_STRING_LENGTH)
```

That bypasses token/path/IBAN/long-digit scanning.

Fix:

```kotlin
else -> sanitizeStringValue(value.toString())
```

Acceptance test:

```text
safe_metadata_unknown_object_to_string_path_is_sanitized
safe_metadata_unknown_object_to_string_token_is_sanitized
```

---

# 7. DiagnosticsRepository remaining issues

---

## DDL-A8-19 — restore trace reads only active journal, not success/failure journals

Severity: **Medium**  
Type: **support/debug gap**

`DiagnosticsRepositoryImpl` uses:

```kotlin
restoreJournal.getEventsByCorrelationId(correlationId)
```

That reads only active `restore_journal.json`.

But restore events may be in:
- `restore_journal_last_success.json`
- `restore_journal_last_failure.json`

Fix:

Add:

```kotlin
restoreJournal.getAllDiagnosticEventsByCorrelationId(correlationId)
```

that checks:
- active journal
- success journal
- failure journal

Acceptance tests:

```text
trace_by_correlation_includes_success_journal_events
trace_by_correlation_includes_failure_journal_events
```

---

## DDL-A8-20 — `getRecentFailures()` excludes safe-sink, operation-run, and journal failures

Severity: **Low/Medium**  
Type: **support tooling gap**

Current:

```kotlin
pipelineEventDao.getRecentFailures(limit)
```

This misses:
- `operation_run_events`
- safe sink diagnostics
- restore journal critical failures
- worker failures

Fix:

Return a combined summary model:

```kotlin
data class DiagnosticFailureSummary(...)
```

Sources:
- pipeline diagnostics
- operation run events
- background job runs
- safe sink records
- restore journal events

Acceptance test:

```text
recent_failures_includes_safe_sink_and_restore_journal_failures
```

---

# 8. Testing gaps

The new `DurableDiagnosticsRegressionTest` is useful but still mostly structural.

Examples:

```kotlin
assertTrue("Handle.event() is best-effort by design", true)
assertTrue("RestoreDiagnosticsSink.markLiveDbSwapStarted() API exists", true)
```

These tests do not catch the main restore bugs.

Needed real tests:

```text
restore_diagnostics_sink_appends_to_journal
restore_journal_append_keeps_previous_events
restore_transition_to_preserves_events
restore_commit_journal_contains_restart_required
restore_import_imports_events_from_success_journal
restore_import_retries_missing_events_when_run_exists
swap_failure_after_mark_swap_started_does_not_call_room_run
safe_handle_cancelled_then_success_has_one_terminal
source_id_hash_plain_text_value_is_redacted
notification_repository_receives_correlation
bank_expense_transaction_event_uses_bank_correlation
```

---

# 9. Acceptance matrix after `a8e1c94`

| Criterion | Status | Notes |
|---|---:|---|
| Stable diagnostic event ID | Mostly done | Good for Room; verify safe sink |
| Operation event writes best-effort | Mostly done | `increment()`/stale recovery still direct |
| Safe operation STARTED | Partial | Exists, but safe handle terminal idempotency missing |
| Restore no Room writes after swap | Partial | `run.failedFinal` still called in swap failure |
| Restore journal append-only trail | Not done | `RestoreDiagnosticsSink` never appends; append logic loses events |
| Restore success import queryable | Partial/broken | Importer exists but likely imports zero/missing events |
| Restore journal privacy | Partial | Full `_...Path` fields still in same JSON |
| Pre-swap restore failures finalized | Partial | several paths still missing |
| Metadata hash bypass fixed | Partial | exact hash keys need value validation |
| Metadata recursive sanitation | Mostly | unknown object path in `sanitizeValue` still weak |
| Notification duplicate `RECEIVED` removed | Mostly | yes, but ordering still racey |
| Notification correlation propagated | Not done | repository call still lacks correlation |
| Bank blocked sync durable | Partial | operation starts first, but safe handle terminal duplication remains |
| Bank transaction lifecycle correlation | Not done | coordinator/entity do not use correlation |
| DiagnosticsRepository hang fixed | Done | `.first()` used |
| DiagnosticsRepository trace complete | Partial | active journal only; recent failures incomplete |
| Golden tests prove behavior | Not yet | mostly structural |

---

# 10. Recommended next PR order

## PR 1 — Restore journal correctness hotfix

Fix immediately:

```text
DDL-A8-01
DDL-A8-02
DDL-A8-03
```

Core changes:
- inject `RestoreJournal` into `RestoreDiagnosticsSink`
- append restore events from `RestoreDiagnosticsSink.event(...)`
- make `appendEvent()` read/write raw JSON and preserve previous events
- preserve `events` through `transitionTo()` / `writeJournal()`
- emit `RESTART_REQUIRED` before `commitJournal()`

## PR 2 — Restore safety/completeness

Fix:

```text
DDL-A8-04
DDL-A8-05
DDL-A8-06
```

Core changes:
- remove direct `run.failedFinal()` after swap starts
- centralize pre-swap failure finalization
- split recovery vs diagnostics journal or strictly hide internal path fields

## PR 3 — Restore importer robustness

Fix:

```text
DDL-A8-07
DDL-A8-08
DDL-A8-19
```

Core changes:
- retry missing event imports even when run exists
- mark success journal imported only after full import
- trace success/failure journals too

## PR 4 — Operation handle terminal/idempotency cleanup

Fix:

```text
DDL-A8-09
DDL-A8-10
DDL-A8-11
DDL-A8-16
```

Core changes:
- safe handle terminal-once
- best-effort increments
- best-effort stale recovery event insert
- avoid success after manual terminal

## PR 5 — Correlation propagation

Fix:

```text
DDL-A8-14
DDL-A8-15
```

Core changes:
- pass notification correlation into repository/pipeline
- add correlation to `transaction_events` or metadata
- `TransactionLifecycleCoordinator` must use `CreateExpenseRequest.correlationId`

## PR 6 — Metadata final hardening

Fix:

```text
DDL-A8-17
DDL-A8-18
```

Core changes:
- validate exact hash-key values
- sanitize unknown object `toString()` via full string sanitizer

## PR 7 — Real regression tests

Add behavior tests for:
- restore journal append/preserve/import
- no Room writes after swap
- safe handle terminal idempotency
- correlation propagation
- metadata hash value validation

---

# 11. Highest-priority bug list

Fix these first:

```text
1. RestoreDiagnosticsSink never appends to RestoreJournal.
2. RestoreJournal.appendEvent loses previous events.
3. transitionTo/writeJournal erase events.
4. RESTART_REQUIRED emitted after commitJournal.
5. swap failure still calls old run.failedFinal after markLiveDbSwapStarted.
6. post-migration/staged-migration/safety-backup restore failures do not finalize run.
7. SafeSinkOperationRunHandle can emit CANCELLED + SUCCESS or PARTIAL_SUCCESS + SUCCESS.
8. notification/bank correlation still not reaching transaction lifecycle.
```

---

# 12. Source links checked

Commit:

- https://github.com/panospao7/Cost-agregator/commit/a8e1c94ee41cc05d19904921e1ba0a280d7907ee

Key files:

- `RestoreDiagnosticsSink.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt

- `RestoreJournal.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `RestoreJournalImporter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt

- `DatabaseBackupRepositoryImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `OperationRunRecorder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt

- `CompositeOperationRunRecorder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt

- `DiagnosticEventWriter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticEventWriter.kt

- `EventMetadataSanitizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt

- `SafeEventMetadata.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt

- `NotificationCaptureService.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `BankApiIntegration.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `CreateExpenseRequest.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

- `TransactionLifecycleCoordinator.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionEvent.kt`  
  https://github.com/panospao7/Cost-agregator/blob/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- `DiagnosticsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/domain/debug/DiagnosticsRepository.kt

- `DiagnosticsModule.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt

- `DurableDiagnosticsRegressionTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/a8e1c94ee41cc05d19904921e1ba0a280d7907ee/app/src/test/java/com/yourname/expensetracker/diagnostics/DurableDiagnosticsRegressionTest.kt