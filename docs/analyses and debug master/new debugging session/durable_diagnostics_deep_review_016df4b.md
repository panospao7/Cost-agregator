# Durable Diagnostics Deep Review — Commit `016df4b`

Commit reviewed: `016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e`  
Previous reviewed commit: `81e1e828998d39549b2c404df52466965e75b182`

## Executive verdict

The recent fixes are a **major improvement**. Most DDL-81 items were addressed at least partially:

- metadata safe-prefix bypass mostly fixed
- exception sanitizer improved
- composite diagnostic writer now uses full safe-sink records
- notification refresh path now has diagnostics
- repository notification failures now emit terminal events
- operation recorder has composite/safe fallback
- side-effect recorder preserves metadata and terminal flags
- email outer exception now emits terminal event
- bank generic per-item exception now emits `TRANSACTION_FAILED`
- debug trace repository was added

However, the refactor is **not fully safe yet**. The biggest remaining problems are around **restore/backup operation events**, **operation-run event failure behavior**, **journal privacy/recovery correctness**, and **trace/debug repository behavior**.

The most serious new/remaining issue:

> `DatabaseBackupRepositoryImpl.restoreCostBackup()` still calls `run.event(...)` after DB swap, despite comments saying not to. Because `OperationRunHandle.event()` is not best-effort, a diagnostic insert failure after a successful restore can enter the rollback catch path and potentially turn a successful restore into rollback/error behavior.

That is an **actual high-impact user bug**, not just architecture cleanup.

---

# 1. Resolved or mostly resolved issues

## DDL-81-01 / 02 — metadata sanitizer hardening

Status: **mostly resolved**

Good:

- broad `SAFE_PREFIXES` removed
- exact safe-key allowlist added
- dangerous substring checks added
- exception sanitizer now reuses string sanitizer
- key variants like `sourceRawText`, `statusToken`, `reasonAuthorization` should now be blocked

Remaining privacy gaps are listed below as new issues.

Files:

```text
domain/diagnostics/EventMetadataSanitizer.kt
domain/diagnostics/SafeEventMetadata.kt
```

---

## DDL-81-03 — composite diagnostic safe-sink fallback

Status: **mostly resolved**

Good:

- `MaintenanceSafeDiagnosticSink.recordDiagnosticEvent(...)` exists
- `DataStoreMaintenanceSafeDiagnosticSink` stores correlation/outcome/severity/reason/entity/source/metadata/exception fields
- `CompositeDiagnosticEventWriter` uses `recordDiagnosticEvent(...)`, not lossy `recordBlockedOperation(...)`

Remaining issue:

- `DiagnosticEvent` still has no stable `eventId`, so safe sink cannot preserve the same event ID as Room would write.

Details below.

---

## DDL-81-08 / 09 / 10 — notification diagnostics

Status: **mostly resolved**

Good:

- `onNotificationPosted` now creates correlation ID early
- `RECEIVED` is emitted at listener entry
- restore blocked, shutdown, dedupe, fast privacy, filter, async privacy, package policy all have terminal diagnostics
- refresh path now mirrors the normal path much better
- repository failures now emit terminal `FAILED_*`
- repository cancellation emits `CANCELLED`

Remaining issues:

- duplicate `RECEIVED` events in the normal path
- fire-and-forget diagnostic emission can reorder or lose early-exit diagnostics on service cancellation
- success path still does not clearly propagate correlation into repository/domain lifecycle events

Details below.

---

## DDL-81-14 / 15 — side-effect recorder

Status: **mostly resolved**

Good:

- caller metadata is merged
- `SIDE_EFFECT_COMPLETED`, `SIDE_EFFECT_FAILED`, and `CANCELLED` are terminal
- cancellation uses `NonCancellable`
- failures are swallowed as post-commit best-effort behavior

Remaining minor issue:

- `emit()` catches all `Exception`, including cancellation, but most calls are already inside non-cancellable/controlled paths. Low risk.

---

## DDL-81-17 / 19 / 20 / 21 — email and bank terminal gaps

Status: **mostly resolved**

Good:

- email outer catch emits terminal `FAILED_FINAL`
- provider-detection diagnostic exists
- bank generic per-transaction exception emits `TRANSACTION_FAILED`
- bank errors use hashed provider IDs
- bank `InsertConflict`, validation, and error events include provider hash in most paths

Remaining issues:

- bank sync checks the write barrier before starting the operation run, so blocked bank sync can still have no durable operation record
- bank-created expense lifecycle likely does not share the bank sync correlation ID

Details below.

---

# 2. Critical / high-priority remaining issues

---

## DDL-016-01 — restore still writes `OperationRun` events after DB swap

Severity: **Critical**  
Type: **actual user-impacting restore bug**  
Pipeline: P7 Backup/restore

In `DatabaseBackupRepositoryImpl.restoreCostBackup()`, after the live DB is swapped, the code comments say:

```text
After live DB swap, do NOT use run handle for Room writes.
All further diagnostics go to the restore journal only.
```

But the code still calls:

```kotlin
run.event("LIVE_DB_VERIFIED", ...)
run.event("RESTART_REQUIRED", ...)
run.event("ROLLBACK_STARTED", ...)
run.event("ROLLBACK_FAILED", ...)
run.event("ROLLBACK_COMPLETED", ...)
```

after the destructive swap point.

Why this is dangerous:

1. `run` was created before restore/maintenance.
2. It is likely a `RoomOperationRunRecorder.Handle`.
3. `run.event(...)` writes through Room DAOs.
4. After DB close/swap, this can fail or write to stale/wrong DB.
5. Worse: `run.event("RESTART_REQUIRED", ...)` occurs inside the success try block. If that diagnostic insert fails, the surrounding catch can treat it like restore verification failure and enter rollback/error handling.

This can turn a **successful restore** into an apparent failed restore because a diagnostic write failed.

### Fix strategy

After this point:

```kotlin
closeLiveDatabaseForFileSwap()
copy staged -> live
```

never call the old `run` handle again.

Replace post-swap `run.event(...)` with:

```kotlin
restoreJournal.appendEvent(...)
maintenanceSafeDiagnosticSink.recordDiagnosticEvent(...)
```

or a restore-specific event sink that writes journal-only after swap.

Recommended abstraction:

```kotlin
class RestoreDiagnosticsSink {
    var roomAllowed: Boolean = true

    suspend fun event(stage: String, outcome: EventOutcome, ...) {
        if (roomAllowed) {
            runCatching { operationRunHandle.event(...) }
        }
        restoreJournal.appendEvent(...)
        safeSink.recordDiagnosticEvent(...)
    }

    fun markDbSwapped() {
        roomAllowed = false
    }
}
```

Then:

```kotlin
restoreEvents.markDbSwapped()
restoreEvents.event("LIVE_DB_VERIFIED", COMPLETED)
restoreEvents.event("RESTART_REQUIRED", COMPLETED, terminal = true)
```

### Required tests

```text
restore_after_swap_does_not_call_room_operation_run_event
restore_success_not_rolled_back_when_operation_event_insert_fails
restore_restart_required_written_to_journal_only
rollback_failed_event_written_to_journal_not_room_after_swap
```

---

## DDL-016-02 — `OperationRunHandle.event()` is not best-effort

Severity: **High**  
Type: **actual reliability bug**  
Pipelines: P7/P10/P11/P12, any operation run

`RoomOperationRunRecorder.Handle.event()` directly calls:

```kotlin
eventDao.insert(...)
```

without `runCatching`, safe-sink fallback, or barrier awareness.

This means any intermediate diagnostic event can fail the business operation. The previous fix made terminal finalization mostly best-effort, but **not intermediate event writes**.

Impact examples:

- backup stage event fails → backup fails
- restore stage event fails → restore can fail or rollback
- bank per-item event fails → bank sync fails
- export/import diagnostic insert fails → user operation fails

### Fix strategy

Make `OperationRunHandle.event()` best-effort by construction.

Option A — add safe fallback into `RoomOperationRunRecorder.Handle`:

```kotlin
override suspend fun event(...) {
    runCatching {
        eventDao.insert(...)
    }.onFailure { e ->
        safeSink.recordDiagnosticEvent(...)
        Timber.w(e, "Failed to write operation event")
    }
}
```

Option B — split strict vs best-effort:

```kotlin
suspend fun event(...)
suspend fun eventStrict(...)
```

Default should be best-effort.

### Required tests

```text
operation_intermediate_event_failure_does_not_fail_business_operation
operation_intermediate_event_failure_goes_to_safe_sink
backup_stage_event_failure_does_not_abort_backup
bank_transaction_event_failure_does_not_abort_sync
```

---

## DDL-016-03 — Room operation run can be orphaned if STARTED event insert fails

Severity: **High**  
Type: **actual diagnostic consistency bug**

`RoomOperationRunRecorder.start()` does:

```kotlin
val id = runDao.insert(OperationRun(... RUNNING ...))
handle.event("STARTED", ATTEMPTED)
return handle
```

If the run row insert succeeds but the `STARTED` event insert fails, the exception propagates to `CompositeOperationRunRecorder`, which creates a new `SafeSinkOperationRunHandle`.

Result:

```text
Room operation_runs row remains RUNNING with correlation A
safe-sink operation continues with correlation B
```

That creates an orphan `RUNNING` operation that only stale recovery can clean later.

### Fix strategy

In `RoomOperationRunRecorder.start()`:

```kotlin
val id = runDao.insert(...)
val handle = Handle(...)
runCatching {
    handle.event("STARTED", ATTEMPTED)
}.onFailure {
    safeSink.recordDiagnosticEvent(...)
}
return handle
```

Do not throw after the run row exists.

Alternatively, if STARTED is mandatory and fails, immediately finalize the inserted row as `FAILED_RETRYABLE` or `STALE_ABORTED`.

### Required tests

```text
operation_start_started_event_failure_returns_room_handle
operation_start_started_event_failure_does_not_create_second_correlation
operation_start_started_event_failure_does_not_leave_running_orphan
```

---

## DDL-016-04 — safe operation handle does not emit `STARTED`

Severity: **Medium/High**  
Type: **diagnostic contract gap**

`CompositeOperationRunRecorder.start()` returns `SafeSinkOperationRunHandle` during maintenance or Room failure. But `SafeSinkOperationRunHandle` does not automatically emit:

```text
STARTED / ATTEMPTED
```

Room start does emit STARTED. Safe start does not.

Impact:

- operation may have only terminal safe-sink event
- global rule “STARTED -> terminal” is not satisfied for safe-handle operation runs

### Fix strategy

When creating safe handle:

```kotlin
private suspend fun safeHandle(operationType: String, metadata: SafeEventMetadata): OperationRunHandle {
    val handle = SafeSinkOperationRunHandle(...)
    handle.event("STARTED", EventOutcome.ATTEMPTED, metadata = metadata)
    return handle
}
```

Because `start()` is suspend, this is easy.

Also include:

```text
operationType
actor
start metadata
```

in safe-sink metadata.

### Required tests

```text
safe_operation_handle_emits_started_on_start
safe_operation_handle_started_preserves_operation_type
safe_operation_handle_started_preserves_start_metadata
```

---

## DDL-016-05 — restore journal JSON writes full internal paths and cannot read them back

Severity: **Critical for crash recovery / High privacy risk**  
Type: **actual restore recovery bug + privacy bug**

`RestoreJournal.JournalEntry.toJson()` writes:

```json
"_sourceBackupPath"
"_stagedDbPath"
"_safetyBackupPath"
"_liveDbPath"
```

These are full local paths.

But `fromJson()` reads old names:

```kotlin
sourceBackupPath = json.optString("sourceBackupPath", null)
stagedDbPath = json.optString("stagedDbPath", null)
safetyBackupPath = json.optString("safetyBackupPath", null)
liveDbPath = json.optString("liveDbPath", null)
```

So the journal both:

1. persists full paths in the same durable file, violating the diagnostics privacy rule, and
2. fails to recover those paths when reading the journal back, because it ignores the `_...Path` fields.

Asset paths have the same problem:

- `toJson()` writes `targetName`
- `fromJson()` reads `target`

So asset target recovery loses the target path.

### User impact

If the app crashes during restore:

- `checkAndRecover()` may load a journal with null paths
- staging cleanup may not work
- destructive swap recovery may not have safety backup/live/staged paths
- asset recovery state may be incomplete

### Fix strategy

Separate the two concerns:

## Option A — two files

```text
restore_recovery_journal.json      // internal, pathful, not exposed/exported
restore_diagnostics_journal.json   // privacy-safe, no full paths
```

Recovery journal can contain full paths because it is operational state, not diagnostics.

Diagnostics journal should contain:

```text
sourceBackupName
sourceBackupHash
stagedDbPathHash
safetyBackupPathHash
liveDbPathHash
assetTargetHash
assetDisplayName
```

## Option B — one file with strict access rules

If keeping one file, then:

- fix `fromJson()` to read `_sourceBackupPath`, etc.
- ensure `DiagnosticsRepository` and debug/export code never expose `_...Path`
- add sanitizer when returning journal data

Option A is cleaner.

### Required tests

```text
restore_journal_roundtrip_preserves_recovery_paths
restore_journal_roundtrip_preserves_asset_target_recovery_path
restore_diagnostics_journal_does_not_contain_full_paths
restore_debug_trace_does_not_expose_internal_path_fields
check_and_recover_can_clean_staging_after_roundtrip
```

---

## DDL-016-06 — restore journal is not an append-only stage trail

Severity: **High for support/debug**  
Type: **diagnostic architecture gap**

The plan required durable restore stages like:

```text
BUNDLE_VALIDATED
STAGED_DB_CREATED
STAGED_DB_VERIFIED
SAFETY_BACKUP_CREATED
LIVE_DB_SWAPPED
LIVE_DB_VERIFIED
ASSETS_RESTORING
RESTART_REQUIRED
ROLLBACK_FAILED
```

Current `RestoreJournal` mostly stores:

```text
current state
paths/names
asset tasks
error
```

It does **not** store an append-only `events` array.

Some stage events are written to `OperationRun`, but those Room events are unsafe after DB swap and may not survive restore.

### Fix strategy

Add journal events:

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

Add:

```kotlin
fun appendEvent(entry: JournalEntry, event: RestoreJournalEvent): JournalEntry
```

Store:

```json
"events": [...]
```

Then every restore stage writes to the journal, before and after DB swap.

### Required tests

```text
restore_journal_contains_bundle_validated_event
restore_journal_contains_live_db_swapped_event
restore_journal_contains_restart_required_terminal_event
restore_journal_contains_rollback_failed_critical_event
restore_success_journal_has_complete_stage_history
```

---

## DDL-016-07 — restore success journal is preserved but not imported/queryable

Severity: **Medium/High**  
Type: **support/debug gap**

`commitJournal()` renames the active journal to:

```text
restore_journal_last_success.json
```

Good. But I found no importer that converts this preserved journal into:

```text
operation_runs
operation_run_events
pipeline_diagnostic_events
```

Also `DiagnosticsRepository.getTraceByCorrelationId()` does not include restore journal events.

Impact:

- restore success may survive as a file, but not appear in the diagnostics trace UI/repository
- support has to inspect internal file manually
- correlation trace is incomplete

### Fix strategy

Add startup importer:

```kotlin
class RestoreJournalImporter {
    suspend fun importLastSuccessJournalIfPresent()
}
```

After DB is healthy:

1. read success journal
2. insert `operation_runs` row in restored DB
3. insert journal events as `operation_run_events`
4. mark journal imported or delete only after successful import

Also add to `DiagnosticsRepository`:

```kotlin
restoreJournalEvents: List<RestoreJournalEvent>
```

### Required tests

```text
restore_success_after_restart_imports_operation_run
restore_success_journal_deleted_only_after_successful_import
trace_by_correlation_includes_restore_journal_events
```

---

# 3. High/medium remaining issues outside restore

---

## DDL-016-08 — `DiagnosticEvent` has no stable `eventId`

Severity: **Medium**  
Type: **diagnostic consistency gap**

Room writer generates `eventId` internally:

```kotlin
eventId = UUID.randomUUID().toString()
```

Safe sink creates its own record ID.

So the same logical event cannot have a stable ID across:

```text
Room path
safe-sink fallback path
test expected event
future causationId references
```

### Fix strategy

Add to `DiagnosticEvent`:

```kotlin
val eventId: String = UUID.randomUUID().toString()
```

Then:

- Room writer stores `event.eventId`
- safe sink stores `event.eventId`
- operation/safe fallback can set causation IDs reliably

### Required tests

```text
diagnostic_event_has_stable_event_id
room_writer_uses_event_event_id
safe_sink_uses_event_event_id
```

---

## DDL-016-09 — hash-suffix keys can still bypass dangerous-key blocking

Severity: **Medium/High privacy risk**  
Type: **privacy bug**

Sanitizer logic allows any key ending with:

```text
hash
idhash
```

before dangerous substring checks.

So this key is allowed:

```text
rawTextHash
```

If a caller mistakenly does:

```kotlin
SafeEventMetadata.builder()
    .put("rawTextHash", "Bought coffee at private place")
```

the key bypasses dangerous-key blocking. The value scanner may not redact short raw text.

### Fix strategy

Do not trust key suffix alone.

Option A:

Only allow exact known hash keys:

```text
sourceIdHash
notificationKeyHash
messageIdHash
providerTransactionIdHash
externalHash
payloadHash
```

Option B:

Allow suffix only if value matches hash format:

```kotlin
private val HASH_VALUE_PATTERN = Regex("^[a-fA-F0-9]{8,128}$")
```

In `sanitizeValue`:

```kotlin
if (isHashKey(key) && value is String && HASH_VALUE_PATTERN.matches(value)) allow
else apply dangerous substring policy
```

Best: require `putHashed()` to create hash fields.

### Required tests

```text
metadata_put_raw_text_hash_with_plain_value_is_redacted
metadata_put_hashed_raw_text_hash_is_allowed_if_hash_format
metadata_hash_suffix_does_not_override_raw_token_path_substrings
```

---

## DDL-016-10 — nested lists/arrays are not fully recursively sanitized

Severity: **Medium privacy risk**  
Type: **privacy hardening gap**

`sanitizeList()` handles:

```text
Map
String
else original item
```

It does not recursively sanitize:

```text
List<List<Map>>
JSONArray inside JSONArray
```

`sanitizeJsonArray()` also does not recursively handle nested `JSONArray`.

### Fix strategy

Make sanitization fully recursive:

```kotlin
private fun sanitizeAny(value: Any?): Any? = when (value) {
    is JSONObject -> sanitizeJsonObject(value)
    is JSONArray -> sanitizeJsonArray(value)
    is Map<*, *> -> sanitizeMap(...)
    is Iterable<*> -> value.map { sanitizeAny(it) }
    is Array<*> -> value.map { sanitizeAny(it) }
    is String -> sanitizeStringValue(value)
    else -> value
}
```

### Required tests

```text
metadata_sanitizer_redacts_prompt_inside_nested_list
metadata_sanitizer_redacts_token_inside_json_array_of_arrays
```

---

## DDL-016-11 — notification emits duplicate `RECEIVED` events on normal path

Severity: **Low/Medium**  
Type: **diagnostic noise / trace ambiguity**

Normal notification path now emits `RECEIVED`:

1. immediately at listener entry
2. again inside `workTracker.launch` before filter

That creates two `RECEIVED` events for one input.

### Fix strategy

Keep only the listener-entry `RECEIVED`.

Remove the second one inside the work tracker.

If you want a pre-filter marker, use:

```text
stage = extraction
outcome = COMPLETED
```

or:

```text
stage = filter
outcome = ATTEMPTED
```

### Required tests

```text
notification_normal_path_emits_exactly_one_received_event
notification_received_before_filter_attempt
```

---

## DDL-016-12 — notification early diagnostics are fire-and-forget

Severity: **Medium**  
Type: **durability race**

Many early diagnostics use:

```kotlin
serviceScope.launch {
    diagnosticEventWriter.emit(...)
}
return
```

This means:

- terminal diagnostic may be cancelled if service scope is cancelled immediately
- `RECEIVED` and terminal event ordering is not guaranteed
- a test may pass with fake scope but production can still lose events during shutdown

### Fix strategy

For early exits, emit received + terminal in the same tracked coroutine, or use a helper that preserves ordering:

```kotlin
private fun emitNotificationTerminalAsync(...) {
    workTracker.launch(serviceScope) {
        diagnosticEventWriter.emit(received)
        diagnosticEventWriter.emit(terminal)
    }
}
```

For shutdown path, consider `NonCancellable` if safe.

### Required tests

```text
notification_shutdown_records_received_then_cancelled_in_same_job
notification_restore_blocked_records_received_then_blocked_ordered
```

---

## DDL-016-13 — notification success path still lacks correlation propagation into domain lifecycle

Severity: **Medium/High support gap**  
Type: **architecture/traceability gap**

`processNotification()` passes `correlationId` only to diagnostics in the service. It calls:

```kotlin
repository.processAndSave(processingNotification, storageNotification)
```

without correlation.

If the repository creates:

```text
raw notification row
pending review
expense
transaction event
side effects
```

those downstream events likely do not share the notification correlation ID.

### Fix strategy

Add correlation to the request/model:

```kotlin
repository.processAndSave(
    processingNotification,
    storageNotification,
    correlationId = correlationId
)
```

Then propagate into:

```text
NotificationProcessingPipeline
pending review lifecycle
TransactionLifecycleCoordinator
side-effect dispatcher
```

### Required tests

```text
notification_success_expense_created_uses_same_correlation
notification_success_review_created_uses_same_correlation
notification_side_effect_uses_notification_correlation
```

---

## DDL-016-14 — bank sync write barrier happens before operation run

Severity: **High for bank sync**  
Type: **actual blocked-operation diagnostic bug**

`BankApiIntegration.syncTransactions()` does:

```kotlin
writeBarrier.checkWritesAllowed("BankApiIntegration.syncTransactions")
operationRunRecorder.runOperation("BANK_SYNC") { ... }
```

If restore/write barrier blocks the sync, no operation run is started and no terminal `BLOCKED/RESTORE_BLOCKED` bank diagnostic is recorded.

### Fix strategy

Start operation before barrier, or catch barrier failure and emit a safe diagnostic:

```kotlin
operationRunRecorder.runOperation("BANK_SYNC") { run ->
    try {
        writeBarrier.checkWritesAllowed(...)
    } catch (e: DatabaseAccessBlockedException) {
        run.event(
            "WRITE_BARRIER",
            EventOutcome.BLOCKED,
            reasonCode = DiagnosticReasonCode.RESTORE_BLOCKED,
            isTerminal = true
        )
        run.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
        return@runOperation blockedResult
    }
}
```

### Required tests

```text
bank_sync_restore_blocked_writes_operation_event
bank_sync_restore_blocked_finalizes_operation_run
bank_sync_write_barrier_denied_uses_safe_sink_if_room_unavailable
```

---

## DDL-016-15 — bank sync does not propagate operation correlation to created expenses

Severity: **Medium/High support gap**  
Type: **traceability architecture gap**

Bank per-item operation events now include the created expense ID. Good.

But `CreateExpenseRequest` likely does not receive the bank operation correlation ID. Therefore:

```text
operation_run_events.TRANSACTION_IMPORTED correlation = bank sync
transaction_events.CREATED correlation = separate/generated
```

Support can jump from bank event to expense ID, but cannot trace by one correlation ID across all tables.

### Fix strategy

Add optional correlation ID to `CreateExpenseRequest` if not already present:

```kotlin
correlationId = run.correlationId
```

Then transaction lifecycle events and side effects use the same correlation.

### Required tests

```text
bank_transaction_imported_and_expense_created_share_correlation
bank_duplicate_transaction_and_transaction_duplicate_event_share_correlation
```

---

## DDL-016-16 — `DiagnosticsRepository.getTraceByCorrelationId()` can hang forever

Severity: **High for debug/support UI**  
Type: **actual runtime bug**

Implementation uses:

```kotlin
safeSink.observeRecent().collect { records ->
    result = records
    return@collect
}
```

For a `Flow`, `return@collect` only returns from the lambda, not from the `collect` call. If the flow is a DataStore flow, it is effectively endless. So `getTraceByCorrelationId()` can suspend forever.

### Fix strategy

Use:

```kotlin
import kotlinx.coroutines.flow.first

val safeSinkEvents = safeSink.observeRecent()
    .first()
    .filter { it.correlationId == correlationId }
```

### Required tests

```text
trace_by_correlation_returns_with_safe_sink_flow
trace_by_correlation_filters_safe_sink_records
```

---

## DDL-016-17 — `DiagnosticsRepository` interface may not be Hilt-bound

Severity: **Medium**  
Type: **DI/support tooling bug**

`DiagnosticsRepositoryImpl` has `@Inject`, but I did not see a binding in `DiagnosticsModule`:

```kotlin
@Binds abstract fun bindDiagnosticsRepository(...)
```

If anything injects `DiagnosticsRepository`, Hilt will fail.

### Fix strategy

Add:

```kotlin
@Binds
@Singleton
abstract fun bindDiagnosticsRepository(
    impl: DiagnosticsRepositoryImpl
): DiagnosticsRepository
```

### Required test/build check

```text
Hilt compile passes with DiagnosticsRepository injection
```

---

## DDL-016-18 — restore failure paths before swap do not consistently finalize operation run

Severity: **Medium/High**  
Type: **operation-run completeness bug**

In `restoreCostBackup()`, early failures such as:

```text
wrong password / extraction failed
empty backup
staged verification failed
post-migration verification failed
staged migration failed
safety backup failed
```

write `restoreJournal.failJournal(...)` but do not consistently call:

```kotlin
run.failedFinal(...)
```

Because these are pre-swap and live DB is still intact, the Room operation run can and should be finalized.

### Fix strategy

For every early return before DB swap:

```kotlin
run.event(stage, FAILED_FINAL, reasonCode = ...)
run.failedFinal(reason, error)
restoreJournal.failJournal(...)
restoreMaintenanceMode.exit(...)
return Result.failure(...)
```

Use best-effort event/finalization so it cannot mask original failure.

### Required tests

```text
restore_wrong_password_finalizes_operation_run_failed
restore_empty_backup_finalizes_operation_run_failed
restore_staged_verification_failed_finalizes_operation_run_failed
restore_safety_backup_failed_finalizes_operation_run_failed
```

---

# 4. Acceptance matrix after `016df4b`

| Criterion | Status | Notes |
|---|---:|---|
| Metadata prefix bypass fixed | Mostly | Hash-suffix bypass remains |
| Exception sanitizer improved | Mostly | Good, but nested recursion gaps remain |
| Safe-sink preserves full diagnostic | Mostly | No stable `eventId` in `DiagnosticEvent` |
| Notification refresh terminal diagnostics | Mostly | Fire-and-forget ordering/durability issue |
| Notification repository failures durable | Mostly | Good |
| Operation run safe fallback | Partial | Safe handle lacks STARTED; event writes not best-effort |
| Operation stale recovery cutoff | Mostly | Age-based now |
| Restore diagnostics survive DB swap | Not yet | Room `run.event` still used after swap |
| Restore journal privacy | Not yet | Full `_...Path` fields in same JSON |
| Restore crash recovery | Risky | `fromJson()` ignores `_...Path` fields |
| Side-effect terminal/metadata | Mostly | Good |
| Email outer terminal event | Mostly | Good |
| Bank per-item exception event | Mostly | Good |
| Bank blocked sync durable | Not yet | Barrier checked before operation run |
| Debug trace repository | Partial | Safe-sink flow collection can hang |
| Real golden tests | Partial | Tests exist, but critical restore/operation failure paths still uncovered |

---

# 5. Recommended next PR order

## PR 1 — Restore operation safety hotfix

Fix first:

```text
DDL-016-01
DDL-016-02
DDL-016-18
```

Goal:

```text
No Room operation-run writes after DB swap.
No operation event write can fail business/restore success.
All pre-swap restore failures finalize operation run.
```

## PR 2 — Restore journal split / roundtrip correctness

Fix:

```text
DDL-016-05
DDL-016-06
DDL-016-07
```

Goal:

```text
Separate recovery journal from diagnostics journal.
Add append-only stage events.
Fix fromJson roundtrip.
Import success journal after restart.
```

## PR 3 — Operation recorder reliability

Fix:

```text
DDL-016-03
DDL-016-04
DDL-016-08
```

Goal:

```text
Stable event IDs.
Safe operation handles emit STARTED.
STARTED event failure does not orphan RUNNING rows.
```

## PR 4 — Metadata final privacy hardening

Fix:

```text
DDL-016-09
DDL-016-10
```

Goal:

```text
No hash-suffix raw-value bypass.
Fully recursive metadata sanitization.
```

## PR 5 — Notification and bank trace completion

Fix:

```text
DDL-016-11
DDL-016-12
DDL-016-13
DDL-016-14
DDL-016-15
```

Goal:

```text
No duplicate RECEIVED.
Ordered/trackable notification diagnostics.
Notification/bank success paths propagate correlation to domain lifecycle.
Bank blocked sync durable.
```

## PR 6 — Debug repository correctness

Fix:

```text
DDL-016-16
DDL-016-17
```

Goal:

```text
Trace repository returns reliably and is injectable.
Trace includes safe sink and restore journal/imported events.
```

---

# 6. Highest-priority tests to add now

```text
restore_success_not_rolled_back_when_operation_event_insert_fails
restore_after_swap_does_not_call_room_operation_run_event
restore_journal_roundtrip_preserves_recovery_paths
restore_diagnostics_journal_does_not_expose_full_paths
operation_intermediate_event_failure_does_not_fail_business_operation
operation_start_started_event_failure_does_not_orphan_running_row
safe_operation_handle_emits_started
trace_by_correlation_returns_with_datastore_safe_sink
bank_sync_restore_blocked_finalizes_operation_run
metadata_put_raw_text_hash_plain_value_is_redacted
notification_normal_path_emits_exactly_one_received
notification_success_expense_created_uses_same_correlation
```

---

# 7. Source links checked

- Commit `016df4b`:  
  https://github.com/panospao7/Cost-agregator/commit/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e

- `EventMetadataSanitizer.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt

- `SafeEventMetadata.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt

- `CompositeDiagnosticEventWriter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeDiagnosticEventWriter.kt

- `DataStoreMaintenanceSafeDiagnosticSink.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/data/backup/DataStoreMaintenanceSafeDiagnosticSink.kt

- `OperationRunRecorder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt

- `CompositeOperationRunRecorder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt

- `NotificationCaptureService.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `RestoreJournal.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `DatabaseBackupRepositoryImpl.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `SideEffectDiagnosticRecorder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SideEffectDiagnosticRecorder.kt

- `BankApiIntegration.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `EmailReceiptIngestionService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `DiagnosticsRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/016df4b6a5b8fb09b40847ca7fa0c406f7e40e4e/app/src/main/java/com/yourname/expensetracker/domain/debug/DiagnosticsRepository.kt