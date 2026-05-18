# Remaining Durable Diagnostics / Lifecycle Events Implementation Plan

Target commit reviewed: `5fba524acbafc3625af58646909daf6e8b8af5df`

Goal: finish the durable diagnostics/lifecycle refactor so every meaningful input, mutation, duplicate, skip, blocked operation, worker run, batch operation, restore phase, and side effect has a durable, privacy-safe, correlated trail.

---

## 0. Current status summary

The foundation exists:

- diagnostic taxonomy enums exist
- `SafeEventMetadata` exists
- `DiagnosticEventWriter` exists
- `OperationRun` / `OperationRunEvent` exist
- worker run logging exists
- notification/email received/failure events are partially added
- backup/restore and bank sync start operation runs
- side-effect dispatchers emit some diagnostics
- static guard script exists

But remaining gaps still break the global rule:

```text
Every meaningful attempt must have:
RECEIVED / ATTEMPTED -> terminal outcome
CREATED / UPDATED / DELETED / LINKED -> lifecycle event
DROPPED / SKIPPED / DUPLICATE / BLOCKED / FAILED / CANCELLED -> diagnostic event
SIDE_EFFECT_STARTED -> SIDE_EFFECT_COMPLETED / SIDE_EFFECT_FAILED / CANCELLED
```

The rest of the work should be done in cross-cutting fix packs, not pipeline-by-pipeline.

---

# 1. Priority order

Implement in this exact order:

1. **Metadata privacy hardening**
2. **Maintenance-safe diagnostic writer**
3. **Worker finalization and read-only backup-mode fix**
4. **OperationRunRecorder reliability**
5. **Notification front-door terminal diagnostics**
6. **Backup/restore journal-backed operation events**
7. **Side-effect correlation/cancellation fix**
8. **Email/receipt remaining terminal events**
9. **Bank sync per-item diagnostics**
10. **Static guard + CI**
11. **Debug query/repository support**
12. **Global golden tests**

Reason: later phases depend on the diagnostic writer being safe and reliable.

---

# 2. Fix Pack A — Privacy-safe metadata by construction

## Problem

`SafeEventMetadata` and `EventMetadataSanitizer` currently block only exact lowercase keys like:

```text
rawtext
accesstoken
fullpath
```

This can miss variants:

```text
raw_text
rawOcr
access_token
authHeader
filePath
emailSubject
bankDescription
nested.prompt
```

Also, metadata is not recursively sanitized.

## Type

High priority privacy bug / architectural hardening.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticEventWriter.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizerTest.kt
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadataTest.kt
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticEventWriterTest.kt
```

## Implementation steps

### A1. Centralize metadata policy

Move all metadata safety logic into `EventMetadataSanitizer`.

Add:

```kotlin
fun canonicalizeKey(key: String): String =
    key.lowercase().replace(Regex("[^a-z0-9]"), "")

fun isDangerousKey(key: String): Boolean
fun sanitizeValue(key: String, value: Any?): Any?
fun sanitizeMap(raw: Map<String, Any?>): Map<String, Any?>
fun sanitizeJsonString(json: String?): String?
fun sanitizeExceptionMessage(message: String?): String?
```

### A2. Dangerous key policy

Block/redact by canonical key exact match and suspicious substrings.

Exact blocked canonical keys:

```text
body
rawbody
rawtext
rawocr
rawocrtext
prompt
token
accesstoken
refreshtoken
authorization
password
secret
fullpath
filepath
iban
accountnumber
cardnumber
cvv
pin
bankdescription
emailbody
emailsubject
sender
```

Substring blocks:

```text
raw
ocr
prompt
token
auth
password
secret
path
iban
account
card
cvv
pin
body
```

Be careful with harmless app keys like `entityId`, `expenseId`, `receiptId`, `operationType`.

Recommended allowlist prefixes:

```text
expenseId
receiptId
operation
stage
status
count
rows
duration
elapsed
source
pipeline
reason
sideEffect
packageHash
notificationKeyHash
messageIdHash
providerHash
externalHash
matchedEntityId
duplicateEntityId
retryable
```

### A3. Recursive sanitization

Support:

```kotlin
Map<*, *>
Iterable<*>
Array<*>
String
Number
Boolean
Enum<*>
null
```

Rules:

- strings max 256 chars
- dangerous string values replaced with `[REDACTED]`
- nested maps sanitized recursively
- unknown object types converted to safe class name or `.toString().take(256)` after value scan

### A4. Value scanning

Redact strings that look like:

```text
Bearer <token>
JWT-like abc.def.ghi
Android/data path
/storage/emulated/...
C:\...
IBAN-like values
long digit account/card values
email body-sized text > 512 chars
```

### A5. Builder should not crash production diagnostics

Current `SafeEventMetadata.Builder.put()` can throw on blocked keys. That is risky because diagnostics should never crash production flow.

Change behavior:

```text
Blocked key -> store "[REDACTED]" or drop key
Invalid value -> redacted
```

If you still want strict behavior, make it test-only:

```kotlin
SafeEventMetadata.builder(strict = false)
```

Default must be non-throwing.

### A6. Writer final-pass sanitization

`DiagnosticEventWriter` and `OperationRunRecorder` must sanitize metadata again even if caller used `SafeEventMetadata`.

Add:

```kotlin
val safeMetadataJson = sanitizer.sanitizeJsonString(event.metadata.toJson())
```

Do this before storing:

```text
PipelineDiagnosticEvent.metadataJson
OperationRun.metadataJson
OperationRunEvent.metadataJson
```

## Acceptance tests

```text
metadata_sanitizer_blocks_raw_text_variant
metadata_sanitizer_blocks_access_token_variant
metadata_sanitizer_blocks_file_path_variant
metadata_sanitizer_blocks_nested_prompt
metadata_sanitizer_redacts_bearer_token_value
metadata_sanitizer_redacts_jwt_like_value
metadata_sanitizer_truncates_long_strings
safe_event_metadata_put_does_not_throw_for_blocked_key
diagnostic_writer_sanitizes_final_metadata_json
operation_run_event_sanitizes_final_metadata_json
```

## Done criteria

- No raw body/prompt/token/path/account-like metadata can be persisted through diagnostics or operation events.
- Diagnostics cannot crash app flow because metadata contained a blocked key.

---

# 3. Fix Pack B — Maintenance-safe diagnostic writer

## Problem

`RoomDiagnosticEventWriter` writes directly to `pipeline_diagnostic_events`. During restore, backup maintenance, DB swap, or DB-closed states, normal Room writes may be unsafe or fail. Several callers catch and ignore diagnostic write errors.

## Type

High priority actual diagnostics bug.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticEventWriter.kt
app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt
app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSink.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt
app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/CompositeDiagnosticEventWriterTest.kt
```

## Implementation steps

### B1. Add safe diagnostic sink API

Extend `MaintenanceSafeDiagnosticSink`.

Add method:

```kotlin
suspend fun recordDiagnosticEvent(
    event: DiagnosticEvent,
    writeFailure: Throwable? = null
)
```

Minimum implementation can store to existing safe sink / DataStore ring buffer / restore journal / Timber fallback.

The stored safe record should include:

```text
eventId
correlationId
pipeline
stage
outcome
severity
reasonCode
entityType
entityId
sourceType
sourceIdHash
occurredAt
metadataJson
exceptionClass
exceptionMessageSafe
writeFailureClass
```

Do not store raw metadata.

### B2. Add CompositeDiagnosticEventWriter

Create:

```text
domain/diagnostics/CompositeDiagnosticEventWriter.kt
```

Behavior:

```kotlin
class CompositeDiagnosticEventWriter @Inject constructor(
    private val roomWriter: RoomDiagnosticEventWriter,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier
) : DiagnosticEventWriter
```

Algorithm:

```text
if maintenance mode != NORMAL:
    safeSink.recordDiagnosticEvent(event)
    return

try:
    writeBarrier.checkWritesAllowed("DiagnosticEventWriter.emit")
    roomWriter.emit(event)
catch CancellationException:
    rethrow
catch db-closed/db-locked/restore/write-barrier exception:
    safeSink.recordDiagnosticEvent(event, writeFailure)
catch any other exception:
    safeSink.recordDiagnosticEvent(event, writeFailure)
```

Important: diagnostic emission should be best-effort, but not silently vanish.

### B3. Bind composite writer in DI

Current module binds:

```kotlin
DiagnosticEventWriter -> RoomDiagnosticEventWriter
```

Change to:

```kotlin
DiagnosticEventWriter -> CompositeDiagnosticEventWriter
```

Keep `RoomDiagnosticEventWriter` injectable as internal concrete class.

### B4. Add optional direct Room writer qualifier if needed

If Hilt binding conflicts, use qualifiers:

```kotlin
@RoomDiagnostics
@CompositeDiagnostics
```

But preferred: bind only interface to composite, inject concrete `RoomDiagnosticEventWriter` into composite.

## Acceptance tests

```text
diagnostic_writer_writes_room_when_normal
diagnostic_writer_uses_safe_sink_when_restore_mode
diagnostic_writer_uses_safe_sink_when_write_barrier_denies
diagnostic_writer_uses_safe_sink_when_room_insert_throws
diagnostic_writer_rethrows_cancellation
diagnostic_writer_sanitizes_metadata_before_room_and_safe_sink
```

## Done criteria

- No pipeline needs to manually decide Room vs safe sink for normal diagnostic events.
- Restore/backup blocked diagnostics cannot disappear just because Room is unavailable.

---

# 4. Fix Pack C — Worker finalization and backup read-only mode

## Problems

1. Cancellation finalization can be cancelled before `background_job_runs` is updated.
2. `runGuardedWithContext` starts a DB worker run even when worker is allowed read-only during backup export.
3. `WorkerRunLogger.cancelled()` sets `cancellationReason` but not `statusReason`.
4. Timeout semantics are contradictory.

## Type

Actual worker diagnostics bug and barrier bug.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt
app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt
app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunContext.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuardTest.kt
app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt
```

## Implementation steps

### C1. NonCancellable finalization

Add helper:

```kotlin
private suspend fun finalizeNonCancellable(block: suspend () -> Unit) {
    withContext(NonCancellable) {
        block()
    }
}
```

Use for:

```text
run.success()
run.skipped()
run.retry()
run.failure()
run.cancelled()
```

Especially:

```kotlin
catch (e: CancellationException) {
    finalizeNonCancellable {
        run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name)
    }
    throw e
}
```

Apply in both:

```text
runGuarded
runGuardedWithContext
```

### C2. Fix read-only path in runGuardedWithContext

Current `runGuarded` handles allowed read-only before starting a run. `runGuardedWithContext` should do the same.

Add branch before `workerRunLogger.start()`:

```kotlin
if (allowedReadOnly) {
    try {
        readBarrier.checkReadAllowed(...)
    } catch (...) {
        diagnosticSink.recordBlockedOperation(...)
        return WorkerGuardResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
    }

    val readOnlyCtx = WorkerRunContext(
        checkpointDelegate = { op ->
            readBarrier.checkReadAllowed(
                DatabaseAccessOperation(op, pipeline = "P9"),
                DatabaseReadPolicy.EXPORT_OR_BACKUP_SNAPSHOT_READ
            )
            yield()
        }
    )

    val result = block(readOnlyCtx)
    return WorkerGuardResult.Success(result)
}
```

Do **not** call `workerRunLogger.start()` in this path because that inserts into DB.

### C3. Fix WorkerRunLogger.cancelled

Change:

```kotlin
override suspend fun cancelled(reason: String) {
    update("CANCELLED", cancellationReason = reason)
}
```

To:

```kotlin
override suspend fun cancelled(reason: String) {
    update(
        "CANCELLED",
        statusReason = reason,
        cancellationReason = reason
    )
}
```

### C4. Clarify timeout semantics

Recommended short-term decision:

```text
CancellationException / TimeoutCancellationException => CANCELLED
IOException / SQLITE_BUSY / locked DB / network-like failure => RETRY
```

Then remove:

```kotlin
e is TimeoutCancellationException -> true
```

from `classifyTransient`, because it is unreachable after the cancellation catch anyway.

Long-term, if a worker uses internal timeouts that should retry, wrap them in a custom non-cancellation exception:

```kotlin
class WorkerTimedOutRetryableException(...) : IOException(...)
```

## Acceptance tests

```text
cancelled_worker_updates_run_cancelled_in_noncancellable_context
cancelled_worker_sets_status_reason_and_cancellation_reason
run_guarded_with_context_read_only_backup_does_not_insert_background_job_run
read_only_context_checkpoint_uses_read_barrier_not_write_barrier
timeout_cancellation_is_not_classified_twice
stale_running_recovery_marks_stale_aborted
```

## Done criteria

- Cancelled workers cannot remain `RUNNING`.
- Read-only backup workers do not write `background_job_runs`.
- Cancellation reasons are typed and queryable.

---

# 5. Fix Pack D — OperationRunRecorder reliability

## Problems

`OperationRunRecorder` exists, but:

- `start()` does not automatically emit a `STARTED` event
- counters are in-memory until terminal finalization
- terminal finalization is not NonCancellable
- no helper guarantees terminal status in `finally`
- no stale recovery is wired
- event API lacks entity/correlation/exception richness
- no maintenance-safe fallback

## Type

Medium/high support reliability gap.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/OperationRunEvent.kt
app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorderTest.kt
```

## Implementation steps

### D1. Expand OperationRunHandle.event API

Current event method accepts:

```text
stage
outcome
reasonCode
severity
metadata
```

Add optional fields:

```kotlin
eventType: String? = null,
causationId: String? = null,
entityType: String? = null,
entityId: Long? = null,
exception: Throwable? = null,
isTerminal: Boolean = false
```

Map them into `OperationRunEvent`.

### D2. Emit STARTED on start

After inserting `OperationRun(status = RUNNING)`, insert:

```text
stage = "STARTED"
outcome = ATTEMPTED
eventType = "${operationType}_STARTED"
severity = INFO
```

### D3. Persist increments

Add DAO method:

```kotlin
@Query("""
UPDATE operation_runs
SET rowsProcessed = rowsProcessed + :processed,
    rowsSucceeded = rowsSucceeded + :succeeded,
    rowsFailed = rowsFailed + :failed,
    rowsSkipped = rowsSkipped + :skipped,
    warningCount = warningCount + :warnings,
    errorCount = errorCount + :errors
WHERE id = :id AND status = 'RUNNING'
""")
suspend fun incrementCounters(...)
```

Then `increment()` should:

1. update in-memory counters if still needed
2. persist increments immediately

This prevents process death losing progress.

### D4. Idempotent finalization

Add DAO:

```kotlin
@Query("""
UPDATE operation_runs
SET status = :status,
    finishedAt = :finishedAt,
    errorSummary = :errorSummary
WHERE id = :id AND status = 'RUNNING'
""")
suspend fun finalizeIfRunning(...): Int
```

Only first terminal state wins.

### D5. NonCancellable terminal updates

Use:

```kotlin
withContext(NonCancellable) {
    finalizeIfRunning(...)
    event(stage = status, outcome = mappedOutcome, isTerminal = true)
}
```

### D6. Add runOperation helper

Add to interface:

```kotlin
suspend fun <T> runOperation(
    operationType: String,
    actor: String? = null,
    metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    block: suspend (OperationRunHandle) -> T
): T
```

Behavior:

```text
start
try:
    result = block(run)
    if still RUNNING -> success
    return result
catch CancellationException:
    cancelled in NonCancellable
    rethrow
catch Exception:
    failedFinal in NonCancellable
    throw
```

For operations that return `Result<T>`, callers can still explicitly call failure/success.

### D7. Stale recovery

Use existing `OperationRunDao.getStaleRunning`.

Add recorder method:

```kotlin
suspend fun recoverStaleRunningOperationRuns(staleThresholdMs: Long)
```

Set:

```text
status = STALE_ABORTED
finishedAt = now
errorSummary = "Recovered stale RUNNING operation after process death"
```

Also insert terminal operation event:

```text
stage = "STALE_RECOVERY"
outcome = CANCELLED or FAILED_FINAL
reasonCode = CANCELLED_BY_SYSTEM
severity = WARNING
```

### D8. Maintenance-safe fallback

Do not force this into `RoomOperationRunRecorder` immediately if too large. Add a wrapper:

```kotlin
CompositeOperationRunRecorder
```

Behavior:

- normal DB available -> Room recorder
- maintenance/restore blocked -> safe no-op handle that records to `MaintenanceSafeDiagnosticSink` / `RestoreJournal`
- DB exception -> fallback handle

Bind interface to composite later.

## Acceptance tests

```text
operation_run_start_inserts_started_event
operation_run_increment_persists_immediately
operation_run_success_finalizes_non_cancellable
operation_run_cancelled_finalizes_non_cancellable
operation_run_terminal_update_is_idempotent
operation_run_stale_recovery_marks_stale_aborted
operation_run_event_supports_entity_and_exception
operation_run_metadata_is_sanitized
```

## Done criteria

- Every operation run gets a terminal status even on cancellation.
- Long operations do not lose counts on process death.
- Operation run events can identify per-item/entity failures.

---

# 6. Fix Pack E — Notification capture terminal diagnostics

## Problems

Notification diagnostics are partially implemented, but important exits still vanish:

- filter reject returns before correlation/RECEIVED
- dedupe return has no `DUPLICATE`
- blocked package return has no diagnostic
- `isShuttingDown` return has no `CANCELLED`
- repository processing exception is logged but no terminal `FAILED`
- refresh path lacks same diagnostics as normal path
- `processNotification()` does not receive correlation ID

## Type

High priority actual user/support bug.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceDiagnosticsTest.kt
app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineDiagnosticsTest.kt
```

## Implementation steps

### E1. Create local helper

In `NotificationCaptureService` add:

```kotlin
private suspend fun emitNotificationEvent(
    correlationId: String,
    stage: String,
    outcome: EventOutcome,
    reasonCode: DiagnosticReasonCode? = null,
    severity: EventSeverity = EventSeverity.INFO,
    packageName: String?,
    notificationKey: String?,
    isTerminal: Boolean = false,
    metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    exception: Throwable? = null
)
```

It should use:

```text
pipeline = NOTIFICATION
sourceType = "notification"
sourceIdHash = hash(notificationKey)
metadata.packageNameHash
metadata.notificationKeyHash
```

Do not store notification title/text/body.

### E2. Generate correlation immediately

At very beginning of `onNotificationPosted`:

```kotlin
val correlationId = CorrelationIds.newId()
val notificationKey = sbn.key
```

Do this before restore check, dedupe, privacy, filter.

### E3. Emit RECEIVED before early exits

Emit:

```text
stage = listener
outcome = RECEIVED
```

Metadata only:

```text
packageNameHash
notificationKeyHash
postTime
```

If maintenance blocks Room, composite writer should safe-sink it.

### E4. Restore blocked

If `!restoreMaintenanceMode.isWritesAllowed()`:

```text
stage = listener
outcome = BLOCKED or DROPPED
reasonCode = RESTORE_BLOCKED
isTerminal = true
```

Prefer:

```text
outcome = BLOCKED
```

Use composite writer or direct safe sink.

### E5. Dedupe return

Current dedupe window return should emit:

```text
stage = dedupe
outcome = DUPLICATE
reasonCode = DUPLICATE
isTerminal = true
```

Metadata:

```text
dedupeWindowMs
```

### E6. Fast privacy return

Current privacy drop event exists but should use same correlation ID.

Emit:

```text
stage = privacy_gate_fast
outcome = DROPPED
reasonCode = PRIVACY_DENIED or PRIVACY_FAIL_CLOSED
isTerminal = true
```

### E7. Filter return

After text extraction and `NotificationFilter.shouldCapture(...) == false`:

```text
stage = filter
outcome = DROPPED
reasonCode = FILTER_REJECTED
isTerminal = true
```

Do not include raw text or title.

### E8. Shutting down return

If `isShuttingDown`:

```text
stage = listener
outcome = CANCELLED
reasonCode = CANCELLED_BY_SYSTEM
isTerminal = true
```

### E9. Pass correlation into processNotification

Change signature:

```kotlin
private suspend fun processNotification(
    sbn: StatusBarNotification,
    packageName: String,
    parts: NotificationTextParts,
    extras: Bundle,
    correlationId: String
)
```

Use it for:

- blocked package event
- repository failure event
- repository success/terminal delegated outcome if available

### E10. Blocked package

In `processNotification`, before return:

```text
stage = package_policy
outcome = DROPPED
reasonCode = BLOCKED_PACKAGE
isTerminal = true
```

### E11. Repository failure

Wrap `repository.processAndSave(...)`:

```text
on success:
    if repository/pipeline does not emit terminal, emit COMPLETED
on exception:
    outcome = FAILED_RETRYABLE or FAILED_FINAL
    reasonCode = UNKNOWN_ERROR
    exception = e
    isTerminal = true
```

Do not swallow without event.

### E12. Refresh path parity

`processNotificationBypassDedupe` must do the same:

- correlation ID
- RECEIVED with stage `refresh`
- restore blocked terminal
- privacy terminal
- filter terminal
- cancelled terminal
- pass correlation to `processNotification`

## Acceptance tests

```text
notification_listener_entry_writes_received
notification_restore_blocked_writes_terminal_safe_event
notification_dedupe_writes_duplicate_terminal_event
notification_fast_privacy_drop_uses_same_correlation
notification_filter_drop_writes_terminal_event
notification_blocked_package_writes_terminal_event
notification_shutdown_writes_cancelled_event
notification_repository_exception_writes_failed_terminal_event
refresh_path_privacy_drop_writes_terminal_event
refresh_path_filter_drop_writes_terminal_event
each_notification_received_has_terminal_or_domain_outcome
diagnostic_metadata_never_contains_notification_text
```

## Done criteria

- No notification listener input can vanish without a terminal durable/safe record.
- Normal and refresh paths have equivalent diagnostics.

---

# 7. Fix Pack F — Backup/restore operation events and restore journal safety

## Problems

Backup/restore use `OperationRunRecorder`, but:

- major stage events are missing
- operation run writes after maintenance may violate no-write policy
- restore `run.success()` after live DB swap can write to stale/wrong DB
- failed early returns do not always finalize operation run
- restore journal does not appear to mirror operation-run correlation/stages fully

## Type

Critical restore diagnostics/support issue.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt
app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSink.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryDiagnosticsTest.kt
app/src/test/java/com/yourname/expensetracker/data/backup/RestoreJournalOperationEventTest.kt
```

## Implementation strategy

Use two layers:

```text
Normal DB operation_runs:
    safe only before maintenance and after maintenance exits if live DB unchanged

RestoreJournal / maintenance-safe sink:
    authoritative during restore and after DB swap
```

## Implementation steps

### F1. Add operation correlation to RestoreJournal

Extend journal entry with:

```text
operationCorrelationId
operationType
lastStage
lastOutcome
eventHistoryJson or append-only journal events
```

Preferred:

```kotlin
data class RestoreJournalEvent(
    val eventId: String,
    val operationId: String,
    val correlationId: String,
    val stage: String,
    val outcome: String,
    val severity: String,
    val reasonCode: String?,
    val occurredAt: Long,
    val metadataJson: String?,
    val exceptionClass: String?,
    val exceptionMessageSafe: String?
)
```

Add:

```kotlin
restoreJournal.appendEvent(...)
```

### F2. Add RestoreOperationEventSink

Create helper:

```kotlin
class RestoreOperationEventSink(
    private val restoreJournal: RestoreJournal,
    private val operationRunHandle: OperationRunHandle?,
    private val maintenanceMode: RestoreMaintenanceMode
)
```

Behavior:

```text
before maintenance / DB safe:
    write to operation run and journal

during maintenance:
    write to journal only, optionally safe sink

after live DB swap:
    write to journal only

after failure before swap and DB unchanged:
    can finalize operation run after exiting maintenance

after success with restart required:
    do not use old operation run handle
```

### F3. Backup export stages

For `createCostBackup`, record:

```text
STARTED / ATTEMPTED
PRIVACY_CHECK_PASSED / COMPLETED
MAINTENANCE_ENTERED / COMPLETED
WORKERS_DRAINED / COMPLETED
WAL_CHECKPOINTED / COMPLETED or FAILED
SNAPSHOT_CREATED / COMPLETED
ASSETS_COLLECTED / COMPLETED or PARTIAL
MANIFEST_WRITTEN / COMPLETED
ENCRYPTION_STARTED / SIDE_EFFECT_STARTED or ATTEMPTED
ENCRYPTED / COMPLETED
COMPLETED / COMPLETED terminal
FAILED / FAILED_FINAL terminal
CANCELLED / CANCELLED terminal
```

Important:

- Do not write normal Room operation events while `BACKUP_EXPORTING` if your barrier says no writes.
- Either:
  - emit pre-maintenance and post-maintenance only to Room, with in-maintenance details in safe sink, or
  - explicitly allow operation-run logging under internal maintenance scope.
- Safer: safe sink for in-maintenance stages.

### F4. Restore stages

For `restoreCostBackup`, record to journal:

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
ASSET_RESTORED / ASSET_FAILED
JOURNAL_COMMITTED
RESTART_REQUIRED
COMPLETED
ROLLBACK_STARTED
ROLLBACK_COMPLETED
ROLLBACK_FAILED
FAILED
```

### F5. Fix early restore failures to finalize/safe-record

In `restoreCostBackup`, current early returns inside `getOrElse` and validation branches should record failure before returning:

```text
wrong password -> FAILED_FINAL / VALIDATION_FAILED or PARSER_FAILED
empty backup -> BLOCKED / VALIDATION_FAILED
staged verification failed -> FAILED_FINAL
migration failed -> FAILED_FINAL
safety backup failed -> FAILED_FINAL
```

If live DB not swapped yet and maintenance exited, finalize normal run. Also write journal failure.

### F6. Do not call old run handle after live DB swap

After this point:

```text
closeLiveDatabaseForFileSwap()
copy staged -> live
```

Do not call:

```kotlin
run.success()
run.failedFinal()
run.event()
```

Instead:

```text
restoreJournal.appendEvent(COMPLETED / RESTART_REQUIRED)
restoreJournal.commitJournal(...)
restoreMaintenanceMode.exit(forceRestartRequired = true)
```

On next startup, add recovery/import:

```text
if restore journal has committed successful restore:
    optionally create operation_runs row in restored DB
    correlationId = journal.operationCorrelationId
    status = SUCCESS
    metadata = summary from journal
```

### F7. Rollback failure must be critical

If rollback fails:

```text
journal event:
    stage = ROLLBACK_FAILED
    outcome = FAILED_FINAL
    severity = CRITICAL
    reasonCode = UNKNOWN_ERROR
```

Also maintenance mode should stay critical recovery required.

### F8. Receipt asset restore events

Inside `restoreReceiptAssets`:

- for each restored asset:
  - `ASSET_RESTORED`
- for each failed/missing:
  - `ASSET_FAILED`
  - severity `WARNING`
  - reason `UNKNOWN_ERROR` or `VALIDATION_FAILED`

Do not log full file paths. Use basename or hash.

## Acceptance tests

```text
backup_success_writes_required_stage_events
backup_privacy_denied_finalizes_operation_run
backup_failure_during_snapshot_records_failed_event
restore_wrong_password_writes_failed_journal_and_final_run_if_safe
restore_empty_backup_writes_validation_failed_event
restore_success_after_db_swap_does_not_use_old_room_run_handle
restore_success_imports_or_preserves_journal_after_restart
restore_rollback_failure_writes_critical_journal_event
asset_restore_failure_writes_asset_failed_event
restore_journal_metadata_does_not_store_full_paths
```

## Done criteria

- Restore diagnostics survive DB swap and restart.
- No normal Room writes are required after the live DB is replaced.
- Backup/restore support trail shows exact failed stage.

---

# 8. Fix Pack G — Side-effect context, correlation, and cancellation

## Problems

Side-effect events exist but:

- transaction side effects lack entity fields
- transaction side effects use fresh correlation IDs
- receipt side effects partially write direct `ReceiptEvent`
- receipt helper `runSideEffect` appears not consistently used
- cancellation rethrows without terminal `CANCELLED`
- side effects do not consistently emit started/completed/failed per actual side effect

## Type

Medium/high support gap; some actual bug for traceability.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
```

Optional new file:

```text
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SideEffectDiagnosticRecorder.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/SideEffectDiagnosticRecorderTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcherTest.kt
app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcherTest.kt
```

## Implementation steps

### G1. Add SideEffectContext

```kotlin
data class SideEffectContext(
    val pipeline: AppPipeline,
    val correlationId: String,
    val causationId: String? = null,
    val entityType: String,
    val entityId: Long?,
    val source: String?,
    val actor: String = "system"
)
```

### G2. Add SideEffectDiagnosticRecorder

API:

```kotlin
suspend fun <T> runSideEffect(
    context: SideEffectContext,
    name: String,
    metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    block: suspend () -> T
): T?
```

Behavior:

```text
emit SIDE_EFFECT_STARTED
run block
emit SIDE_EFFECT_COMPLETED
on CancellationException:
    emit CANCELLED / CANCELLED_BY_SYSTEM in NonCancellable
    rethrow
on Exception:
    emit SIDE_EFFECT_FAILED / SIDE_EFFECT_EXCEPTION
    swallow or return null depending caller policy
```

Fields:

```text
pipeline = context.pipeline
correlationId = context.correlationId
causationId = context.causationId
entityType = context.entityType
entityId = context.entityId
metadata.sideEffect = name
metadata.source = context.source
```

### G3. Update TransactionSideEffectDispatcher API

Current:

```kotlin
dispatchOnCreated(expenseId: Long, source: ExpenseSource)
```

Add overload or replace with:

```kotlin
dispatchOnCreated(
    expenseId: Long,
    source: ExpenseSource,
    correlationId: String,
    causationId: String? = null
)
```

Also:

```text
dispatchOnUpdated
dispatchOnDeleted
dispatchOnBulkUpdated
```

Use `SideEffectDiagnosticRecorder` for each actual side effect:

```text
budget_check
anomaly_alert_check
merchant_category_pattern_learning
merchant_canonical_stats_update
recurring_occurrence_matching
recurring_occurrence_unlink
bulk_budget_check
```

### G4. Propagate correlation from lifecycle coordinator

Where transaction is created/updated/deleted, pass same correlation ID used for:

```text
CREATE_ATTEMPTED
CREATED
UPDATED
DELETED
```

to side effects.

If existing request lacks correlation ID, generate at boundary.

### G5. Update ReceiptSideEffectDispatcher

Change:

```kotlin
dispatchAfterSave(receipt: ScannedReceipt)
```

To:

```kotlin
dispatchAfterSave(
    receipt: ScannedReceipt,
    correlationId: String,
    causationId: String? = null
)
```

Use recorder for:

```text
warranty_extraction
receipt_item_categorization
transaction_matching
price_protection_check
```

### G6. Emit receipt match terminal outcomes

For receipt matching:

```text
MATCH_ATTEMPTED
AUTO_MATCHED
MATCH_SUGGESTED
MATCH_NOT_FOUND
SIDE_EFFECT_FAILED
```

Rules:

- `AUTO_MATCHED` and `MATCH_SUGGESTED` that mutate receipt/link state should use lifecycle events in same DB transaction.
- `MATCH_NOT_FOUND` can be diagnostic/lifecycle event with no mutation.
- `SIDE_EFFECT_FAILED` diagnostic includes receipt entity.

### G7. Remove direct ReceiptEvent construction from side-effect dispatcher if possible

Long-term preferred:

```text
ReceiptSideEffectDispatcher -> ReceiptLifecycleEventWriter
```

If keeping direct writes temporarily, static guard allowlist must document why.

## Acceptance tests

```text
transaction_side_effect_started_completed_have_expense_entity
transaction_side_effect_failure_has_original_correlation
transaction_side_effect_cancelled_writes_cancelled_before_rethrow
receipt_side_effect_started_completed_have_receipt_entity
receipt_match_not_found_writes_terminal_event
receipt_auto_match_writes_link_event_atomically
receipt_side_effect_failure_uses_safe_metadata
```

## Done criteria

- Side-effect failures are traceable from original input to entity.
- Side-effect cancellation does not disappear.
- Receipt matcher always records terminal match outcome.

---

# 9. Fix Pack H — Email/receipt remaining terminal outcomes

## Problems

Email diagnostics are improved but need full terminal coverage:

- duplicate message/content outcomes
- source insert conflict
- low-confidence review route
- existing-expense link vs create distinction
- parse/validation failures should share same correlation
- raw body/subject/sender must not leak

Receipt/OCR gaps:

- parse failure before structured fields
- duplicate receipt outcome
- review-created after dedupe
- match-not-found
- asset-delete failure

## Type

Medium actual support gap.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionServiceDiagnosticsTest.kt
app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorDiagnosticsTest.kt
```

## Implementation steps

### H1. Email boundary correlation

At start of email ingestion:

```kotlin
val correlationId = request.correlationId ?: CorrelationIds.newId()
```

Every event in the email flow uses it.

### H2. EMAIL_RECEIVED

Emit before provider detection/parser selection:

```text
pipeline = EMAIL
stage = intake
outcome = RECEIVED
sourceType = email
sourceIdHash = hash(messageId if available)
metadata:
    providerHintHash?
    hasAttachments
    receivedAt
```

Do not store raw subject/body/sender unless policy explicitly allows; default hash.

### H3. Provider/parser events

Emit:

```text
PROVIDER_DETECTED / COMPLETED
PARSER_SELECTED / COMPLETED
PARSE_FAILED / FAILED_FINAL / PARSER_FAILED
VALIDATION_FAILED / FAILED_FINAL / VALIDATION_FAILED
```

### H4. Duplicate events

For duplicate message ID:

```text
stage = dedupe_message_id
outcome = DUPLICATE
reasonCode = DUPLICATE
entityType = receipt/email_source if known
metadata.duplicateEntityId
isTerminal = true
```

For duplicate content:

```text
stage = dedupe_content
outcome = DUPLICATE
reasonCode = DUPLICATE
metadata.contentFingerprintHash
isTerminal = true
```

### H5. Source insert conflict

Inside coordinator transaction:

- if email source insert conflict means duplicate/resolved:
  - write duplicate event
- if conflict is unexpected/unresolved:
  - rollback
  - write diagnostic failure outside transaction

### H6. Review route

When low confidence creates pending review:

```text
REVIEW_CREATED
entityType = pending_review
entityId = reviewId
correlationId = email correlation
```

Must happen after dedupe.

### H7. Existing expense link

If email receipt links to existing expense:

```text
event = LINKED
entityType = expense
entityId = expenseId
metadata.receiptId
```

Do not emit `EXPENSE_CREATED`.

### H8. Receipt parse/duplicate/review/match gaps

Add events:

```text
PARSE_FAILED
DUPLICATE_DETECTED
REVIEW_CREATED
MATCH_ATTEMPTED
MATCH_NOT_FOUND
ASSET_DELETE_FAILED
```

Use lifecycle event where a receipt row exists; diagnostic where no row exists.

## Acceptance tests

```text
email_received_event_before_parser
email_parse_failure_writes_safe_terminal_diagnostic
email_validation_failure_writes_safe_terminal_diagnostic
duplicate_message_id_writes_terminal_diagnostic
duplicate_content_writes_terminal_diagnostic
email_source_conflict_unresolved_writes_failure_and_rolls_back
low_confidence_email_writes_review_created_event
existing_expense_link_writes_link_event_not_create_event
receipt_parse_failure_writes_parse_failed_event
duplicate_receipt_writes_duplicate_event
receipt_review_created_after_dedupe
receipt_match_not_found_writes_event
asset_delete_failure_writes_event
```

## Done criteria

- Email/receipt inputs cannot vanish on parse/dedupe/review/link paths.
- Create vs link semantics are correct.

---

# 10. Fix Pack I — Bank sync per-item diagnostics

## Problems

`BankApiIntegration.syncTransactions` starts an operation run, but:

- token refresh failure can return without finalizing the run
- no `SYNC_STARTED`, `PAGE_FETCHED`, or per-transaction events
- duplicates/validation failures/errors are only counted in memory/result
- provider transaction IDs must be hashed
- low-confidence routing not represented
- connection/token events missing

## Type

Medium/high if bank sync is enabled.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

Optional future entity:

```text
bank_transaction_imports
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/bank/BankApiIntegrationDiagnosticsTest.kt
```

## Implementation steps

### I1. Use runOperation helper

Wrap sync in:

```kotlin
operationRunRecorder.runOperation("BANK_SYNC", actor = "system") { run ->
    ...
}
```

This prevents early returns leaving `RUNNING`.

Until helper exists, manually finalize every return path.

### I2. Start and connection events

Emit:

```text
SYNC_STARTED
TOKEN_REFRESH_STARTED
TOKEN_REFRESHED
TOKEN_REFRESH_FAILED
REAUTH_REQUIRED
```

For connection:

```text
BANK_CONNECTION_STARTED
BANK_CONNECTION_COMPLETED
BANK_CONNECTION_FAILED
DISCONNECTED
```

### I3. Token refresh failure

Before returning:

```text
run.event(
    stage = "TOKEN_REFRESH",
    outcome = FAILED_FINAL,
    reasonCode = TOKEN_INVALID or NETWORK_UNAVAILABLE
)
run.failedFinal("Token expired and refresh failed")
```

### I4. Fetch/page event

For mock/future real API:

```text
PAGE_FETCHED
metadata:
    pageNumber
    itemCount
```

### I5. Per transaction event sequence

For each provider transaction:

```text
TRANSACTION_RECEIVED
TRANSACTION_CLASSIFIED
TRANSACTION_IMPORTED
TRANSACTION_REVIEW_CREATED
TRANSACTION_DUPLICATE_SKIPPED
TRANSACTION_FAILED
```

Metadata:

```text
providerTransactionIdHash
amountBucket? optional
currency
confidence
```

Do not store raw bank description/reference.

### I6. Map CreateExpenseResult

```text
Created:
    TRANSACTION_IMPORTED
    outcome = CREATED
    entityType = expense
    entityId = expenseId

DuplicateSkipped:
    TRANSACTION_DUPLICATE_SKIPPED
    outcome = DUPLICATE
    reasonCode = DUPLICATE
    metadata.matchedEntityId if available

ValidationFailed:
    TRANSACTION_FAILED
    outcome = FAILED_FINAL
    reasonCode = VALIDATION_FAILED
    metadata.errorCount only, not raw messages if sensitive

InsertConflict:
    TRANSACTION_DUPLICATE_SKIPPED or FAILED_FINAL depending semantics

Error:
    TRANSACTION_FAILED
    outcome = FAILED_RETRYABLE or FAILED_FINAL
```

### I7. Low-confidence route

If classifier exists:

```text
confidence < threshold:
    create pending review
    event TRANSACTION_REVIEW_CREATED
```

If classifier does not exist yet, add explicit TODO event gap:

```text
TRANSACTION_CLASSIFIED
outcome = NEEDS_REVIEW or COMPLETED
metadata.classifier = "stub"
```

### I8. Counts

Call `run.increment()` per transaction, not only at end.

## Acceptance tests

```text
bank_sync_token_refresh_failure_finalizes_operation_run
bank_sync_writes_started_completed_counts
bank_sync_page_fetched_event
bank_transaction_created_writes_imported_event
bank_transaction_duplicate_writes_duplicate_event
bank_transaction_validation_failed_writes_failed_event
bank_transaction_error_writes_failed_event
bank_transaction_provider_id_is_hashed
low_confidence_bank_transaction_writes_review_created_event
```

## Done criteria

- No bank sync run remains `RUNNING` after early return.
- Every bank transaction has an import outcome.

---

# 11. Fix Pack J — Static guard and CI enforcement

## Current status

`verify_event_writers.py` exists and includes rules for:

```text
PipelineDiagnosticEvent
TransactionEvent
ReceiptEvent
BackgroundJobRun
OperationRun
OperationRunEvent
```

Good.

Remaining gap: it only matters if CI runs it and if it catches DAO direct writes.

## Type

Process/architecture guard.

## Files

Primary:

```text
scripts/verify_event_writers.py
.github/workflows/*
build.gradle.kts or app/build.gradle.kts
```

## Implementation steps

### J1. Add CI step

If GitHub Actions exists, add:

```yaml
- name: Verify event writer boundaries
  run: python3 scripts/verify_event_writers.py --fail-on-violation
```

If no workflow exists, create:

```text
.github/workflows/ci.yml
```

Include:

```text
./gradlew testDebugUnitTest
python3 scripts/verify_event_writers.py --fail-on-violation
```

### J2. Add Gradle task

Optional but useful:

```kotlin
tasks.register<Exec>("verifyEventWriters") {
    commandLine("python3", "scripts/verify_event_writers.py", "--fail-on-violation")
}
```

Make `check` depend on it.

### J3. Guard direct DAO writes

Add regex rules for direct DAO event inserts outside writers/coordinators:

```text
pipelineDiagnosticEventDao.insert(
operationRunDao.insert(
operationRunDao.update(
operationRunEventDao.insert(
backgroundJobRunDao.insert(
backgroundJobRunDao.update(
transactionEventDao.insert(
receiptEventDao.insert(
recurringLifecycleEventDao.insert(
```

Allowlist:

```text
DiagnosticEventWriter.kt
OperationRunRecorder.kt
WorkerRunLogger.kt
TransactionLifecycleEventWriter.kt
ReceiptLifecycleEventWriter.kt
RecurringLifecycleEventWriter.kt
approved coordinators
tests
migrations
```

### J4. Reduce ReceiptSideEffectDispatcher exception

Currently static guard allows direct `ReceiptEvent` construction in `ReceiptSideEffectDispatcher`. After Fix Pack G, remove this allowlist and require `ReceiptLifecycleEventWriter`.

### J5. Add allowlist file

Create:

```text
scripts/event_writer_allowlist.txt
```

Each allowlist line must include reason:

```text
ReceiptLifecycleCoordinator.kt # owns atomic receipt transaction boundary
```

The script should fail if allowlisted file has no reason.

## Acceptance tests

```text
verify_event_writers_fails_on_direct_pipeline_diagnostic_event
verify_event_writers_fails_on_direct_operation_run_event
verify_event_writers_fails_on_direct_event_dao_insert
verify_event_writers_allows_writer_files
ci_runs_verify_event_writers_with_fail_flag
```

## Done criteria

- New direct event entity construction or event DAO insert cannot enter main branch unnoticed.

---

# 12. Fix Pack K — Debug query/repository support

## Current status

`PipelineDiagnosticEventDao` has:

```text
getRecent
getRecentByPipeline
getByCorrelationId
getByEntity
observeRecent
```

Missing or incomplete:

```text
recent failures
operation run with events
events by operation correlation
worker runs by correlation
privacy audit by correlation
combined trace view
```

## Type

Support tooling gap.

## Files

Primary:

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/PipelineDiagnosticEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/BackgroundJobRunDao.kt
```

Optional:

```text
app/src/main/java/com/yourname/expensetracker/domain/debug/DiagnosticsRepository.kt
app/src/main/java/com/yourname/expensetracker/ui/debug/DiagnosticsViewModel.kt
```

## Implementation steps

### K1. Add recent failures query

In `PipelineDiagnosticEventDao`:

```sql
SELECT * FROM pipeline_diagnostic_events
WHERE severity IN ('WARNING','ERROR','CRITICAL')
   OR outcome IN ('FAILED_RETRYABLE','FAILED_FINAL','BLOCKED','DROPPED','CANCELLED','SIDE_EFFECT_FAILED')
ORDER BY timestamp DESC
LIMIT :limit
```

### K2. Operation event queries

In `OperationRunEventDao`:

```text
getByOperationRunId(operationRunId)
getByCorrelationId(correlationId)
getRecentFailures(limit)
```

### K3. Operation run debug model

Add:

```kotlin
data class OperationRunWithEvents(
    val run: OperationRun,
    val events: List<OperationRunEvent>
)
```

Room relation optional. Simpler repository method is fine.

### K4. Combined correlation trace

Add repository:

```kotlin
suspend fun getTraceByCorrelationId(correlationId: String): DiagnosticTrace
```

Containing:

```text
pipeline diagnostics
operation run
operation run events
background job runs
transaction events if correlationId added there
receipt events if correlationId added there
privacy audit if available
```

### K5. Debug UI optional

Not required for correctness, but add repository first.

## Acceptance tests

```text
get_recent_failures_returns_failed_and_blocked_events
get_operation_run_with_events_orders_events_by_time
get_trace_by_correlation_id_combines_diagnostics_and_operation_events
```

## Done criteria

- Developer/support can inspect a failed flow by correlation ID.

---

# 13. Fix Pack L — Transaction/receipt lifecycle atomicity verification

## Problem

Writers exist, but atomicity is not guaranteed unless coordinators call them inside the same Room transaction as domain mutation.

## Type

Correctness verification / possible hidden actual bug.

## Files

Primary check targets:

```text
TransactionLifecycleCoordinator.kt
ReceiptLifecycleCoordinator.kt
RecurringLifecycleCoordinator.kt
ReceiptLinkService.kt
GroupTransactionCoordinator.kt
BudgetMonitor.kt
WarrantyTrackerRepository.kt
BillReminderWorker.kt
BankStatementLifecycleProcessor.kt
```

Tests:

```text
TransactionLifecycleAtomicityTest.kt
ReceiptLifecycleAtomicityTest.kt
RecurringLifecycleAtomicityTest.kt
```

## Implementation steps

### L1. Audit mutation methods

For each method that mutates:

```text
Expense
ScannedReceipt
ReceiptExpenseLink
PendingReview
RecurringRule
RecurringOccurrence
Budget
Warranty
Group transaction
```

Verify:

```text
domain mutation + lifecycle event are inside same database.withTransaction
```

### L2. Add rollback tests

Pattern:

```text
force exception after lifecycle event insert but before transaction end
assert neither domain row nor lifecycle event exists
```

### L3. Add validation failure tests

Validation failures before mutation should write diagnostic outside transaction:

```text
CREATE_VALIDATION_FAILED
UPDATE_VALIDATION_FAILED
PARSE_FAILED
```

### L4. Add duplicate tests

Duplicates must be terminal durable outcomes:

```text
transaction duplicate
receipt duplicate
email duplicate
bank duplicate
review approval duplicate
```

## Acceptance tests

```text
expense_created_event_rolls_back_with_expense
expense_update_event_rolls_back_with_update
receipt_saved_event_rolls_back_with_receipt
receipt_link_event_rolls_back_with_link
recurring_occurrence_paid_event_rolls_back_with_status
transaction_validation_failed_diagnostic_survives
receipt_duplicate_diagnostic_survives
```

## Done criteria

- Lifecycle events are atomic with mutations.
- Attempt/failure/duplicate diagnostics survive when no domain mutation commits.

---

# 14. Global golden tests

Add a single global test suite:

```text
app/src/test/java/com/yourname/expensetracker/diagnostics/GlobalDurableDiagnosticsGoldenTest.kt
```

Tests:

```text
diagnostic_metadata_never_contains_raw_sensitive_keys
diagnostic_writer_generates_event_id_and_correlation_id
diagnostic_writer_falls_back_to_safe_sink_when_room_blocked
operation_run_success_has_started_and_terminal_event
operation_run_failure_has_terminal_status
operation_run_cancellation_finalizes
worker_cancellation_finalizes_run
worker_read_only_backup_mode_does_not_write_run
stale_worker_recovery_marks_stale_aborted
stale_operation_recovery_marks_stale_aborted
notification_filter_drop_durable
notification_duplicate_drop_durable
notification_blocked_package_durable
notification_refresh_privacy_drop_durable
email_parse_failed_durable
email_duplicate_message_id_durable
receipt_parse_failed_durable
receipt_match_not_found_durable
transaction_validation_failed_durable
bank_sync_token_failure_finalizes_run
bank_sync_duplicate_transaction_durable
backup_privacy_denied_durable
restore_wrong_password_journaled
restore_after_swap_does_not_write_old_room_operation_run
side_effect_failure_durable_with_correlation
side_effect_cancellation_durable
static_guard_fails_on_direct_event_construction
```

---

# 15. Suggested PR breakdown

## PR 1 — Metadata safety hardening

Includes Fix Pack A.

Must pass:

```text
EventMetadataSanitizerTest
SafeEventMetadataTest
DiagnosticEventWriterTest
```

## PR 2 — Composite diagnostic writer

Includes Fix Pack B.

Must pass:

```text
CompositeDiagnosticEventWriterTest
existing diagnostics tests
```

## PR 3 — Worker finalization/read-only fix

Includes Fix Pack C.

Must pass:

```text
WorkerExecutionGuardTest
WorkerRunLoggerTest
```

## PR 4 — OperationRunRecorder reliability

Includes Fix Pack D.

Must pass:

```text
OperationRunRecorderTest
migration/schema tests
```

## PR 5 — Notification terminal diagnostics

Includes Fix Pack E.

Must pass:

```text
NotificationCaptureServiceDiagnosticsTest
NotificationProcessingPipelineDiagnosticsTest
```

## PR 6 — Backup/restore journal-backed diagnostics

Includes Fix Pack F.

Must pass:

```text
DatabaseBackupRepositoryDiagnosticsTest
RestoreJournalOperationEventTest
```

## PR 7 — Side-effect correlation/cancellation

Includes Fix Pack G.

Must pass:

```text
SideEffectDiagnosticRecorderTest
TransactionSideEffectDispatcherTest
ReceiptSideEffectDispatcherTest
```

## PR 8 — Email/receipt terminal coverage

Includes Fix Pack H.

Must pass:

```text
EmailReceiptIngestionServiceDiagnosticsTest
ReceiptLifecycleCoordinatorDiagnosticsTest
```

## PR 9 — Bank sync diagnostics

Includes Fix Pack I.

Must pass:

```text
BankApiIntegrationDiagnosticsTest
```

## PR 10 — Static guard CI + debug queries

Includes Fix Packs J and K.

Must pass:

```text
python3 scripts/verify_event_writers.py --fail-on-violation
DAO query tests
```

## PR 11 — Atomicity/golden tests

Includes Fix Pack L and global tests.

---

# 16. Agent execution checklist

Use this checklist per PR:

```text
1. Add failing tests first.
2. Implement minimal production fix.
3. Run targeted tests.
4. Run full unit test suite.
5. Run static guard.
6. Inspect metadata JSON assertions.
7. Check no new direct event entity construction.
8. Check no Room writes occur during restore unsafe section.
9. Check every RECEIVED/ATTEMPTED has terminal outcome.
10. Update docs/debug tracker with resolved issue IDs.
```

Recommended commands:

```bash
./gradlew testDebugUnitTest
python3 scripts/verify_event_writers.py --fail-on-violation
./gradlew connectedDebugAndroidTest # if available
```

If migration changed:

```bash
./gradlew roomSchemaLocationCheck
./gradlew testDebugUnitTest --tests '*Migration*'
```

---

# 17. Issue-to-fix-pack mapping

| Issue | Fix pack | User impact |
|---|---|---|
| Metadata key variants/nested raw values can leak | A | Privacy risk |
| Diagnostic writer Room-only | B | Events vanish during restore/DB failure |
| Worker cancellation can stay RUNNING | C | Bad worker/debug state |
| runGuardedWithContext writes during backup read-only mode | C | Barrier violation |
| Operation run early returns/stale RUNNING | D/I | Bad batch/debug state |
| Notification filter/duplicate/blocked-package vanish | E | User-visible missed capture with no explanation |
| Restore operation run unsafe after DB swap | F | Critical restore debugging loss |
| Backup/restore missing stage trail | F | Support cannot identify failed restore phase |
| Side effects lose correlation/entity | G | Cannot trace post-commit failures |
| Side-effect cancellation invisible | G | Missing terminal outcome |
| Email duplicates/source conflicts incomplete | H | Email ingestion support gaps |
| Receipt match-not-found/parse/duplicate gaps | H/G | Receipt support gaps |
| Bank sync per-transaction outcomes missing | I | Import support gaps |
| Static guard not CI-enforced/direct DAO gaps | J | Regression risk |
| Debug queries incomplete | K | Support tooling gap |
| Lifecycle atomicity not proven | L | Possible audit inconsistency |

---

# 18. Sources checked

- Commit `5fba524` summary and changed files:  
  https://github.com/panospao7/Cost-agregator/commit/5fba524acbafc3625af58646909daf6e8b8af5df

- `DiagnosticEventWriter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticEventWriter.kt

- `EventMetadataSanitizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt

- `SafeEventMetadata.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt

- `DiagnosticsModule.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt

- `WorkerExecutionGuard.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt

- `WorkerRunLogger.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt

- `OperationRunRecorder.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt

- `OperationRunDao.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunDao.kt

- `PipelineDiagnosticEventDao.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/data/database/dao/PipelineDiagnosticEventDao.kt

- `NotificationCaptureService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `EmailReceiptIngestionService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `TransactionSideEffectDispatcher.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt

- `ReceiptSideEffectDispatcher.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt

- `DatabaseBackupRepositoryImpl.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `BankApiIntegration.kt`:  
  https://github.com/panospao7/Cost-agregator/blob/5fba524acbafc3625af58646909daf6e8b8af5df/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `verify_event_writers.py`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/5fba524acbafc3625af58646909daf6e8b8af5df/scripts/verify_event_writers.py