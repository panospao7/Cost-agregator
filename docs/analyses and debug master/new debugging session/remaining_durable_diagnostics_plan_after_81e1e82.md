# Remaining Durable Diagnostics Implementation Plan

Target commit: `81e1e828998d39549b2c404df52466965e75b182`

Purpose: finish the remaining durable diagnostics/lifecycle-event work after the recent Fix Packs A-L commit.

---

## 0. Remaining critical issue list

Fix these first:

```text
DDL-81-01 Metadata sanitizer safe-prefix bypass
DDL-81-03 CompositeDiagnosticEventWriter safe-sink fallback is lossy
DDL-81-08 Notification RECEIVED not emitted at true listener entry
DDL-81-09 Notification refresh path still has silent exits
DDL-81-10 Notification repository exceptions swallowed
DDL-81-11 Restore success trail can disappear after DB swap
DDL-81-12 Restore journal stores full file paths
```

Then:

```text
DDL-81-05 OperationRunRecorder lacks maintenance-safe fallback
DDL-81-06 Operation stale recovery cutoff API bug
DDL-81-07 Terminal operation event failure can fail business success
DDL-81-14 Side-effect recorder ignores caller metadata
DDL-81-15 Side-effect terminal flags wrong
DDL-81-17 Email outer exception path lacks durable diagnostic
DDL-81-19 Bank generic per-transaction exception lacks event
```

---

# PR 1 — Metadata privacy hardening final pass

## Issues fixed

```text
DDL-81-01
DDL-81-02
```

## Problem

`EventMetadataSanitizer` allows broad “safe prefixes”. This can let dangerous keys bypass redaction:

```text
sourceRawText
sourceFullPath
sourceAccessToken
statusToken
reasonAuthorization
currencyAccountNumber
```

Also exception-message sanitization is weaker than metadata sanitization.

## Files

```text
domain/diagnostics/EventMetadataSanitizer.kt
domain/diagnostics/SafeEventMetadata.kt
domain/diagnostics/DiagnosticEventWriter.kt
domain/diagnostics/OperationRunRecorder.kt
```

Tests:

```text
EventMetadataSanitizerTest.kt
SafeEventMetadataTest.kt
DiagnosticEventWriterTest.kt
OperationRunRecorderTest.kt
```

## Implementation steps

### 1. Remove broad prefix allowlisting

Delete logic equivalent to:

```kotlin
if (SAFE_PREFIXES.any { canonical.startsWith(it) }) return false
```

Replace with exact safe keys only.

Recommended model:

```kotlin
private val SAFE_EXACT_KEYS = setOf(
    "expenseid",
    "receiptid",
    "entityid",
    "entitytype",
    "operationtype",
    "operationid",
    "operationrunid",
    "stage",
    "status",
    "count",
    "rowcount",
    "rows",
    "rowssucceeded",
    "rowsfailed",
    "rowsskipped",
    "duration",
    "elapsed",
    "elapsedms",
    "source",
    "sourcetype",
    "sourceidhash",
    "pipeline",
    "reason",
    "reasoncode",
    "sideeffect",
    "packagehash",
    "packagenamehash",
    "notificationkeyhash",
    "messageidhash",
    "providerhash",
    "providertransactionidhash",
    "externalhash",
    "matchedentityid",
    "duplicateentityid",
    "retryable",
    "causationid",
    "correlationid",
    "eventid",
    "isTerminal",
    "delivered",
    "partial",
    "percent",
    "confidence",
    "parsersource",
    "transactionsfound",
    "reviewscreated",
    "duplicatesskipped",
    "pagecount",
    "itemcount",
    "currency",
    "classifier"
)
```

### 2. Use dangerous substring checks after exact safe-key check

Recommended logic:

```kotlin
fun isDangerousKey(key: String): Boolean {
    val canonical = canonicalizeKey(key)

    if (canonical in SAFE_EXACT_KEYS) return false
    if (canonical.endsWith("hash") || canonical.endsWith("idhash")) return false

    if (canonical in BLOCKED_EXACT_KEYS) return true

    return BLOCKED_SUBSTRINGS.any { canonical.contains(it) }
}
```

Important: hash-suffix allowance must not override obvious dangerous unhashed keys.

Examples:

```text
sourceIdHash allowed
providerTransactionIdHash allowed
sourceRawText blocked
sourceRawTextHash allowed only if value is truly a hash
```

If uncertain, require explicit `putHashed()` rather than trusting suffix.

### 3. Make exception sanitizer reuse full string sanitizer

Change:

```kotlin
sanitizeExceptionMessage(message)
```

to internally call the same redaction pipeline used for metadata string values.

It must redact:

```text
Bearer tokens
JWT-like values
file paths
IBAN-like values
long account/card-like digit strings
huge body-like text
```

### 4. Add tests

Required tests:

```text
metadata_sanitizer_blocks_source_raw_text
metadata_sanitizer_blocks_source_full_path
metadata_sanitizer_blocks_source_access_token
metadata_sanitizer_blocks_status_token
metadata_sanitizer_blocks_reason_authorization
metadata_sanitizer_blocks_currency_account_number
metadata_sanitizer_allows_exact_source
metadata_sanitizer_allows_source_id_hash
exception_sanitizer_redacts_iban
exception_sanitizer_redacts_long_account_digits
exception_sanitizer_redacts_file_path
exception_sanitizer_redacts_bearer_token
exception_sanitizer_truncates_large_blob
```

## Acceptance criteria

```text
No broad safe-prefix bypass remains.
All metadata and exception messages pass the same privacy policy.
Diagnostics still never throw due to unsafe metadata.
```

---

# PR 2 — Full safe-sink diagnostic fallback

## Issues fixed

```text
DDL-81-03
DDL-81-22
```

## Problem

`CompositeDiagnosticEventWriter` currently falls back to:

```kotlin
MaintenanceSafeDiagnosticSink.recordBlockedOperation(...)
```

This loses most event fields:

```text
eventId
correlationId
causationId
outcome
severity
reasonCode
entityId
sourceType/sourceIdHash
metadata
exception info
isTerminal
elapsedMs
```

## Files

```text
domain/diagnostics/CompositeDiagnosticEventWriter.kt
domain/diagnostics/DiagnosticEventWriter.kt
data/backup/MaintenanceSafeDiagnosticSink.kt
data/backup/RestoreJournal.kt
scripts/verify_event_writers.py
scripts/event_writer_allowlist.txt
```

Tests:

```text
CompositeDiagnosticEventWriterTest.kt
MaintenanceSafeDiagnosticSinkTest.kt
VerifyEventWritersTest.kt
```

## Implementation steps

### 1. Add full diagnostic safe-sink API

In `MaintenanceSafeDiagnosticSink` add:

```kotlin
suspend fun recordDiagnosticEvent(
    event: DiagnosticEvent,
    mode: RestoreMaintenanceMode.Mode,
    writeFailure: Throwable? = null
)
```

Safe record should include:

```text
eventId
correlationId
causationId
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
elapsedMs
metadataJson
exceptionClass
exceptionMessageSafe
writeFailureClass
writeFailureMessageSafe
isTerminal
metadataSchemaVersion
```

Do not store raw body/text/path/token/account values.

### 2. Implement safe serialization model

Create:

```kotlin
data class MaintenanceSafeDiagnosticRecord(...)
```

Recommended fields:

```kotlin
data class MaintenanceSafeDiagnosticRecord(
    val eventId: String,
    val correlationId: String,
    val causationId: String?,
    val pipeline: String,
    val stage: String,
    val outcome: String,
    val severity: String,
    val reasonCode: String?,
    val entityType: String?,
    val entityId: Long?,
    val sourceType: String?,
    val sourceIdHash: String?,
    val occurredAt: Long,
    val elapsedMs: Long?,
    val metadataJson: String?,
    val exceptionClass: String?,
    val exceptionMessageSafe: String?,
    val writeFailureClass: String?,
    val writeFailureMessageSafe: String?,
    val isTerminal: Boolean,
    val metadataSchemaVersion: Int = 1
)
```

### 3. Update `CompositeDiagnosticEventWriter`

Replace lossy fallback:

```kotlin
safeSink.recordBlockedOperation(...)
```

with:

```kotlin
safeSink.recordDiagnosticEvent(
    event = event,
    mode = restoreMaintenanceMode.currentMode(),
    writeFailure = cause
)
```

Only use `recordBlockedOperation` for places where no `DiagnosticEvent` exists.

### 4. Preserve true reason

If write barrier denies, map reason:

```text
RESTORE_BLOCKED -> RESTORE_BLOCKED
WRITE_BARRIER_DENIED -> WRITE_BARRIER_DENIED
READ_BARRIER_DENIED -> READ_BARRIER_DENIED
DB insert failure -> UNKNOWN_ERROR or DB_WRITE_FAILED if enum added
```

Do not always store `RESTORE_IN_PROGRESS`.

### 5. Static guard update

Add script rule:

```text
Direct MaintenanceSafeDiagnosticSink.recordBlockedOperation(...) is forbidden in pipeline/service classes.
```

Allowed only in:

```text
CompositeDiagnosticEventWriter
DatabaseWriteBarrier / DatabaseReadBarrier internals
WorkerExecutionGuard
Backup/restore maintenance coordinator
tests
```

## Acceptance tests

```text
composite_writer_room_success_writes_full_event
composite_writer_restore_mode_safe_sink_preserves_event_id
composite_writer_restore_mode_safe_sink_preserves_correlation_id
composite_writer_restore_mode_safe_sink_preserves_outcome_reason_terminal
composite_writer_room_failure_safe_sink_preserves_exception
composite_writer_write_barrier_denied_uses_write_barrier_reason
composite_writer_rethrows_cancellation
safe_sink_record_sanitizes_metadata_and_exception
verify_event_writers_flags_record_blocked_operation_in_pipeline
```

## Acceptance criteria

```text
Safe-sink diagnostic fallback is equivalent to Room diagnostic event, not a lossy blocked-operation stub.
Correlation tracing works even when Room is unavailable.
```

---

# PR 3 — Notification diagnostics completion

## Issues fixed

```text
DDL-81-08
DDL-81-09
DDL-81-10
```

## Problem

Normal notification path improved, but:

```text
RECEIVED is not emitted at true listener entry
refresh path has silent returns
repository exceptions are only logged
```

## Files

```text
service/NotificationCaptureService.kt
data/repository/NotificationRepository.kt
data/repository/NotificationProcessingPipeline.kt
domain/diagnostics/DiagnosticEventWriter.kt
```

Tests:

```text
NotificationCaptureServiceDiagnosticsTest.kt
NotificationRefreshDiagnosticsTest.kt
NotificationRepositoryDiagnosticsTest.kt
```

## Implementation steps

### 1. Add true listener-entry RECEIVED

In `onNotificationPosted`, after:

```kotlin
val sbn = sbn ?: return
val correlationId = CorrelationIds.newId()
```

immediately emit:

```text
pipeline = NOTIFICATION
stage = listener
outcome = RECEIVED
correlationId = correlationId
sourceType = notification
sourceIdHash = hash(sbn.key)
metadata:
  packageNameHash
  notificationKeyHash
  postTime
```

Do this before:

```text
restore check
shutdown check
dedupe check
fast privacy check
text extraction
filter
```

Because composite writer is now safe, restore mode should record this safely.

### 2. Ensure early exits are terminal

For every early return:

```text
restore blocked -> BLOCKED / RESTORE_BLOCKED / terminal
shutting down -> CANCELLED / CANCELLED_BY_SYSTEM / terminal
dedupe -> DUPLICATE / DUPLICATE / terminal
fast privacy -> DROPPED / PRIVACY_DENIED or PRIVACY_FAIL_CLOSED / terminal
filter reject -> DROPPED / FILTER_REJECTED / terminal
blocked package -> DROPPED / BLOCKED_PACKAGE / terminal
async privacy denied -> DROPPED / PRIVACY_DENIED / terminal
```

Use same correlation ID as `RECEIVED`.

### 3. Mirror logic in refresh path

Refactor shared logic so normal and refresh paths cannot diverge.

Recommended helper:

```kotlin
private data class NotificationDiagnosticContext(
    val correlationId: String,
    val packageName: String,
    val notificationKey: String?,
    val stagePrefix: String
)
```

Add helper:

```kotlin
private suspend fun emitNotificationDiagnostic(
    ctx: NotificationDiagnosticContext,
    stage: String,
    outcome: EventOutcome,
    reasonCode: DiagnosticReasonCode? = null,
    severity: EventSeverity = EventSeverity.INFO,
    isTerminal: Boolean = false,
    metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    exception: Throwable? = null
)
```

Then `processNotificationBypassDedupe()` should:

```text
generate correlation at refresh entry
emit RECEIVED stage=refresh
restore blocked terminal
shutdown terminal
fast privacy terminal
extract text
filter terminal
async privacy terminal
processNotification(..., correlationId)
```

### 4. Repository exception terminal event

In `processNotification()`:

```kotlin
try {
    repository.processAndSave(...)
} catch (e: CancellationException) {
    emit CANCELLED terminal in NonCancellable
    throw e
} catch (e: Exception) {
    emit FAILED_FINAL or FAILED_RETRYABLE terminal
}
```

Recommended:

```kotlin
private fun isRetryableNotificationFailure(e: Throwable): Boolean =
    e is IOException ||
    e.message?.contains("database is locked", ignoreCase = true) == true
```

Event:

```text
pipeline = NOTIFICATION
stage = repository
outcome = FAILED_RETRYABLE or FAILED_FINAL
reasonCode = UNKNOWN_ERROR
severity = ERROR
correlationId = existing
sourceType = notification
sourceIdHash = hash(notification key)
exception = e
isTerminal = true
```

### 5. Success terminal policy

Avoid double terminal events if downstream lifecycle event is authoritative.

Short-term acceptable:

```text
repository returns success and no known domain event -> emit COMPLETED terminal
```

Better:

```text
NotificationProcessingPipeline returns sealed outcome:
  ExpenseCreated(expenseId)
  ReviewCreated(reviewId)
  Duplicate(existingId)
  Dropped(reason)
  Failed(reason)
```

Then service writes or trusts terminal based on outcome.

## Tests

```text
notification_restore_blocked_has_received_and_terminal
notification_shutdown_has_received_and_cancelled
notification_dedupe_has_received_and_duplicate
notification_fast_privacy_has_received_and_privacy_drop
notification_filter_drop_has_received_and_terminal
notification_blocked_package_has_terminal
notification_async_privacy_drop_has_terminal
notification_repository_exception_writes_failed_terminal
notification_repository_cancellation_writes_cancelled_and_rethrows
refresh_restore_blocked_writes_received_and_terminal
refresh_privacy_fast_drop_writes_terminal
refresh_filter_drop_writes_terminal
refresh_shutdown_writes_cancelled
refresh_async_privacy_drop_writes_terminal
refresh_success_uses_same_correlation_id
notification_diagnostics_do_not_store_title_or_text
```

## Acceptance criteria

```text
Every notification entry, including refresh, has RECEIVED -> terminal/domain outcome.
Repository failures cannot vanish as Timber-only errors.
```

---

# PR 4 — OperationRunRecorder reliability and safe fallback

## Issues fixed

```text
DDL-81-05
DDL-81-06
DDL-81-07
```

## Problem

Operation runs improved but:

```text
RoomOperationRunRecorder has no maintenance-safe fallback
stale recovery API uses confusing cutoff parameter
terminal operation event insert failure can turn business success into failure
```

## Files

```text
domain/diagnostics/OperationRunRecorder.kt
domain/diagnostics/CompositeOperationRunRecorder.kt
domain/diagnostics/SafeSinkOperationRunHandle.kt
data/database/dao/OperationRunDao.kt
data/database/dao/OperationRunEventDao.kt
di/DiagnosticsModule.kt
```

Tests:

```text
OperationRunRecorderTest.kt
CompositeOperationRunRecorderTest.kt
SafeSinkOperationRunHandleTest.kt
OperationRunStaleRecoveryTest.kt
```

## Implementation steps

### 1. Split Room implementation from interface binding

Keep:

```kotlin
class RoomOperationRunRecorder : OperationRunRecorder
```

Add:

```kotlin
class CompositeOperationRunRecorder : OperationRunRecorder
```

DI should bind:

```text
OperationRunRecorder -> CompositeOperationRunRecorder
```

Composite injects concrete `RoomOperationRunRecorder`.

### 2. Add safe sink handle

Create:

```kotlin
class SafeSinkOperationRunHandle(
    override val runId: Long = 0L,
    override val correlationId: String,
    private val operationType: String,
    private val actor: String?,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val timeProvider: TimeProvider
) : OperationRunHandle
```

All methods write full safe diagnostic/operation records to safe sink:

```text
start -> operation started safe record
event -> operation event safe record
increment -> safe counter snapshot or in-memory only with terminal summary
success/partial/failed/cancelled -> terminal safe record
```

If you do not want a separate safe operation model yet, map operation events into `DiagnosticEvent`:

```text
pipeline = BACKUP_RESTORE / BANK / EXPORT_IMPORT / EMAIL depending operation type
stage = operation stage
outcome = event outcome
correlationId = operation correlation
metadata.operationType = operationType
```

### 3. Composite start behavior

```kotlin
override suspend fun start(...): OperationRunHandle {
    if (!canWriteRoom()) return safeHandle(...)

    return try {
        roomRecorder.start(...)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        safeHandle(..., writeFailure = e)
    }
}
```

### 4. Fix stale recovery API

Replace:

```kotlin
recoverStaleRunningOperationRuns(staleThresholdMs: Long)
```

with:

```kotlin
recoverStaleRunningOperationRuns(staleAgeMs: Long = DEFAULT_STALE_OPERATION_AGE_MS)
```

Implementation:

```kotlin
val cutoffStartedBefore = timeProvider.now() - staleAgeMs
val staleRuns = runDao.getStaleRunning(startedBeforeMs = cutoffStartedBefore)
```

Rename DAO parameter:

```kotlin
getStaleRunning(startedBeforeMs: Long)
```

Default suggestion:

```text
DEFAULT_STALE_OPERATION_AGE_MS = 6 hours
```

or operation-specific if available.

### 5. Wire stale recovery on startup

Find app startup initialization path. Add:

```kotlin
operationRunRecorder.recoverStaleRunningOperationRuns()
workerRunLogger.recoverStaleRunning(...)
```

Must run after DB open and migrations.

### 6. Make terminal event insert best-effort

Current finalize flow must not throw after business success.

Recommended:

```kotlin
private suspend fun finalizeNonCancellable(...) {
    withContext(NonCancellable) {
        runCatching {
            val updated = runDao.finalizeIfRunning(...)
            if (updated > 0) {
                runCatching {
                    insertTerminalEvent(...)
                }.onFailure { terminalEventError ->
                    safeSink.recordDiagnosticEvent(...)
                    Timber.w(terminalEventError, "Failed to write terminal operation event")
                }
            }
        }.onFailure { finalizeError ->
            safeSink.recordDiagnosticEvent(...)
            Timber.w(finalizeError, "Failed to finalize operation run")
        }
    }
}
```

Important: `success()` should not throw unless the app explicitly chooses strict diagnostics mode.

### 7. Ensure `runOperation()` does not convert diagnostic-finalization failure into business failure

Pattern:

```kotlin
val result = block(handle)
handle.successBestEffort()
return result
```

If `successBestEffort()` fails, log/safe-sink, do not enter outer failure path.

## Tests

```text
operation_recorder_uses_room_when_normal
operation_recorder_uses_safe_handle_when_restore_mode
operation_recorder_uses_safe_handle_when_room_start_fails
safe_operation_handle_preserves_correlation_and_terminal_status
operation_recovery_uses_now_minus_stale_age
operation_recovery_does_not_abort_recent_running_run
operation_recovery_aborts_old_running_run
operation_recovery_called_on_startup
operation_success_not_failed_when_terminal_event_insert_fails
operation_status_finalized_even_if_terminal_event_insert_fails
operation_terminal_event_failure_goes_to_safe_sink
```

## Acceptance criteria

```text
Batch operations never lose all diagnostics because Room is unavailable.
Stale operation recovery uses a duration, not an absolute timestamp.
Diagnostic finalization cannot fail a successful business operation.
```

---

# PR 5 — Restore journal durability and privacy

## Issues fixed

```text
DDL-81-11
DDL-81-12
DDL-81-13
```

## Problem

Restore diagnostics still have two big risks:

```text
successful restore trail can disappear after DB swap
journal stores full local file paths
stage coverage remains incomplete
```

## Files

```text
data/backup/RestoreJournal.kt
data/repository/DatabaseBackupRepositoryImpl.kt
data/backup/MaintenanceSafeDiagnosticSink.kt
domain/diagnostics/OperationRunRecorder.kt
```

Tests:

```text
RestoreJournalTest.kt
DatabaseBackupRepositoryRestoreDiagnosticsTest.kt
RestoreJournalPrivacyTest.kt
```

## Implementation steps

### 1. Split recovery journal from diagnostics journal

There are two different needs:

```text
Recovery journal:
  may need internal full paths to recover/rollback.
  must not be exposed/exported as diagnostics.

Diagnostics journal:
  privacy-safe.
  stores hashes/basenames/stage trail/status.
```

Implement one of these:

### Option A — two files

```text
restore_recovery_journal.json
restore_diagnostics_journal.json
```

### Option B — one file with private fields excluded from debug/export

Better long-term is Option A.

### 2. Remove full paths from diagnostics journal

Replace:

```text
sourceBackupPath
stagedDbPath
safetyBackupPath
liveDbPath
asset.targetPath
```

with:

```text
sourceBackupPathHash
sourceBackupDisplayName
stagedDbPathHash
safetyBackupPathHash
liveDbPathHash
assetTargetRelativePathHash
assetDisplayName
```

Hash function must be stable enough for support correlation but not reversible.

### 3. Preserve successful restore trail

Do not delete successful journal immediately.

Preferred flow:

```text
restore succeeds after DB swap
write committed diagnostics journal:
  status = SUCCESS
  restartRequired = true
  operationCorrelationId = ...
  terminal stage = RESTART_REQUIRED / COMPLETED
do not delete diagnostics journal
on next startup:
  import journal into restored DB operation_runs / operation_run_events
  only after successful import, mark journal imported or delete
```

Add startup importer:

```kotlin
class RestoreJournalImporter {
    suspend fun importCommittedRestoreJournalIfPresent()
}
```

Import result:

```text
operation_runs:
  operationType = RESTORE_COSTBACKUP
  correlationId = journal.operationCorrelationId
  status = SUCCESS / FAILED_FINAL / PARTIAL_SUCCESS
  startedAt / finishedAt from journal

operation_run_events:
  one per journal event
```

If import fails, keep journal.

### 4. Add full required restore stage events

Add helper:

```kotlin
private suspend fun restoreStage(
    stage: String,
    outcome: EventOutcome = EventOutcome.COMPLETED,
    severity: EventSeverity = EventSeverity.INFO,
    reasonCode: DiagnosticReasonCode? = null,
    metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    exception: Throwable? = null,
    terminal: Boolean = false
)
```

Before DB swap, it may write both operation run and journal.

After DB swap, journal only.

Required stages:

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

### 5. Wrong password / validation failure

For wrong password or invalid bundle:

```text
stage = BUNDLE_VALIDATED
outcome = FAILED_FINAL
reasonCode = VALIDATION_FAILED or PARSER_FAILED
severity = ERROR
terminal = true
```

If DB unchanged and Room safe, finalize Room operation run. Also write diagnostics journal.

### 6. Rollback failure is critical

On rollback failure:

```text
stage = ROLLBACK_FAILED
outcome = FAILED_FINAL
severity = CRITICAL
reasonCode = UNKNOWN_ERROR
terminal = true
```

Keep journal until user/support can inspect.

### 7. Asset restore events

For each asset:

```text
ASSET_RESTORED / COMPLETED
ASSET_FAILED / FAILED_FINAL or PARTIAL
```

Metadata:

```text
assetKind
assetDisplayName or assetHash
relativePathHash
```

Never full path.

## Tests

```text
restore_success_after_swap_leaves_committed_diagnostics_journal
restore_success_after_restart_imports_operation_run
restore_success_journal_deleted_only_after_successful_import
restore_journal_diagnostics_does_not_store_full_paths
restore_recovery_journal_can_keep_internal_paths_if_not_exported
restore_wrong_password_has_bundle_validation_failed_event
restore_staged_db_failure_has_stage_event
restore_rollback_failure_has_critical_event
asset_restore_success_has_asset_restored_event
asset_restore_failure_has_asset_failed_event
```

## Acceptance criteria

```text
Restore diagnostics survive DB swap and restart.
No exposed diagnostics journal contains full local paths.
Support can identify exact failed restore phase.
```

---

# PR 6 — Side-effect recorder terminal and metadata fix

## Issues fixed

```text
DDL-81-14
DDL-81-15
```

## Problem

`SideEffectDiagnosticRecorder` exists, but:

```text
caller metadata is discarded
SIDE_EFFECT_COMPLETED and SIDE_EFFECT_FAILED are not terminal
```

## Files

```text
domain/diagnostics/SideEffectDiagnosticRecorder.kt
domain/diagnostics/SafeEventMetadata.kt
domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt
```

Tests:

```text
SideEffectDiagnosticRecorderTest.kt
TransactionSideEffectDispatcherTest.kt
ReceiptSideEffectDispatcherTest.kt
```

## Implementation steps

### 1. Add metadata merge API

In `SafeEventMetadata`:

```kotlin
fun toMap(): Map<String, Any?>
fun merge(other: SafeEventMetadata): SafeEventMetadata
fun with(key: String, value: Any?): SafeEventMetadata
```

Or builder:

```kotlin
SafeEventMetadata.builder()
    .putAll(existing)
    .put("sideEffect", name)
    .put("source", context.source)
    .build()
```

All merge paths must sanitize.

### 2. Preserve caller metadata

In side-effect recorder emit:

```kotlin
val combinedMetadata = SafeEventMetadata.builder()
    .putAll(metadata)
    .put("sideEffect", name)
    .put("source", context.source ?: "unknown")
    .build()
```

Do not overwrite caller keys unless intentional.

### 3. Terminal flags

Set:

```text
SIDE_EFFECT_STARTED -> isTerminal = false
SIDE_EFFECT_COMPLETED -> isTerminal = true
SIDE_EFFECT_FAILED -> isTerminal = true
CANCELLED -> isTerminal = true
```

### 4. Ensure cancellation is NonCancellable

Keep existing cancellation behavior:

```kotlin
catch (e: CancellationException) {
    withContext(NonCancellable) {
        emit CANCELLED
    }
    throw e
}
```

### 5. Decide failure swallowing policy

For post-commit side effects, default should be:

```text
side-effect failure is durable but does not rollback primary operation
```

So recorder should return nullable/result or swallow after event based on caller config.

Recommended API:

```kotlin
suspend fun <T> runSideEffect(
    context: SideEffectContext,
    name: String,
    metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    propagateFailure: Boolean = false,
    block: suspend () -> T
): T?
```

If `propagateFailure = false`, emit failed and return null.

## Tests

```text
side_effect_recorder_preserves_caller_metadata
side_effect_recorder_sanitizes_merged_metadata
side_effect_completed_is_terminal
side_effect_failed_is_terminal
side_effect_cancelled_is_terminal
side_effect_failure_does_not_throw_by_default
side_effect_failure_can_throw_when_propagate_true
```

## Acceptance criteria

```text
Side-effect attempts are traceable with caller context.
Completed/failed/cancelled are terminal.
Side-effect failures do not accidentally fail primary committed operations.
```

---

# PR 7 — Email and bank remaining terminal paths

## Issues fixed

```text
DDL-81-16
DDL-81-17
DDL-81-18
DDL-81-19
DDL-81-20
DDL-81-21
```

---

## Part A — Email fixes

## Files

```text
data/email/EmailReceiptIngestionService.kt
domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
```

Tests:

```text
EmailReceiptIngestionServiceDiagnosticsTest.kt
ReceiptLifecycleCoordinatorDiagnosticsTest.kt
```

## Implementation steps

### 1. Add explicit PROVIDER_DETECTED

After provider detection:

```text
pipeline = EMAIL
stage = provider_detection
outcome = COMPLETED
correlationId = email correlation
metadata.providerHash
```

If provider is enum-safe, raw enum is okay, but hash is safer.

### 2. Outer exception terminal event

In outer catch:

```kotlin
catch (e: Exception) {
    if (e is CancellationException) throw e

    diagnosticEventWriter.emit(
        DiagnosticEvent(
            pipeline = AppPipeline.EMAIL,
            stage = "ingestion",
            outcome = EventOutcome.FAILED_FINAL,
            severity = EventSeverity.ERROR,
            reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
            correlationId = correlationId,
            sourceType = "email",
            sourceIdHash = messageId?.sha256Prefix(16),
            exception = e,
            isTerminal = true
        )
    )

    return EmailReceiptResult.ParseError("Processing error")
}
```

Do not include:

```text
sender
subject
body
raw headers
```

### 3. Cancellation diagnostic

If email ingestion catches cancellation at boundary:

```text
stage = ingestion
outcome = CANCELLED
reasonCode = CANCELLED_BY_SYSTEM
isTerminal = true
```

Emit in `NonCancellable`, then rethrow.

### 4. Coordinator error reason code

For coordinator failures, map to specific reason when possible:

```text
source conflict -> DUPLICATE or VALIDATION_FAILED
validation issue -> VALIDATION_FAILED
unexpected exception -> UNKNOWN_ERROR
```

Include:

```text
reasonCode
exception if available
metadata.retryable=false
```

## Email tests

```text
email_provider_detected_event_after_intake
email_provider_detected_uses_same_correlation
email_outer_exception_writes_failed_terminal_diagnostic
email_outer_exception_does_not_store_sender_subject_body
email_cancellation_writes_cancelled_and_rethrows
email_coordinator_error_has_reason_code
email_source_insert_conflict_has_specific_reason
```

---

## Part B — Bank fixes

## Files

```text
domain/bank/BankApiIntegration.kt
domain/diagnostics/OperationRunRecorder.kt
```

Tests:

```text
BankApiIntegrationDiagnosticsTest.kt
```

## Implementation steps

### 1. Generic per-transaction exception event

Inside transaction loop catch:

```kotlin
catch (e: Exception) {
    run.event(
        stage = "TRANSACTION_FAILED",
        outcome = EventOutcome.FAILED_RETRYABLE,
        reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
        severity = EventSeverity.ERROR,
        exception = e,
        metadata = SafeEventMetadata.builder()
            .putHashed("providerTransactionId", transaction.id)
            .put("currency", transaction.currency)
            .build()
    )
    run.increment(processed = 1, failed = 1, errors = 1)
}
```

If exception is clearly validation/final, use `FAILED_FINAL`.

### 2. Include provider transaction hash in all per-item terminal events

For:

```text
TRANSACTION_IMPORTED
TRANSACTION_REVIEW_CREATED
TRANSACTION_DUPLICATE_SKIPPED
TRANSACTION_FAILED
InsertConflict
CreateExpenseResult.Error
ValidationFailed
```

metadata must include:

```text
providerTransactionIdHash
currency
```

Never store raw:

```text
bank description
bank reference
provider transaction raw ID
account number
IBAN
```

### 3. Sanitize `SyncResult.errors`

Current errors may include raw transaction IDs.

Change from:

```text
Failed to import transaction ${transaction.id}: ...
```

to:

```text
Failed to import provider transaction hash=<hash>: <safe error code>
```

Better:

```text
Transaction import failed [hash=<hash>, reason=VALIDATION_FAILED]
```

Avoid raw exception message unless sanitized.

## Bank tests

```text
bank_transaction_generic_exception_writes_transaction_failed_event
bank_transaction_generic_exception_hashes_provider_id
bank_error_event_has_provider_transaction_hash
bank_insert_conflict_duplicate_event_has_provider_transaction_hash
bank_sync_result_errors_do_not_include_raw_provider_transaction_id
bank_failure_events_do_not_include_raw_bank_description
```

## Acceptance criteria

```text
Email outer failures are durable.
Bank every-per-item failure path has a terminal event.
Provider transaction IDs are hashed in events and result errors.
```

---

# PR 8 — Worker run start failure hardening

## Issue fixed

```text
DDL-81-04
```

## Problem

`workerRunLogger.start()` is called before execution failure classification. If the run row insert fails, the worker may crash instead of returning retry/skip or using safe sink.

## Files

```text
domain/workers/WorkerExecutionGuard.kt
domain/workers/WorkerRunLogger.kt
data/backup/MaintenanceSafeDiagnosticSink.kt
```

Tests:

```text
WorkerExecutionGuardTest.kt
```

## Implementation steps

### 1. Add start result sealed class

```kotlin
private sealed interface StartRunResult {
    data class Started(val run: WorkerRunHandle) : StartRunResult
    data class Skipped(val reason: String) : StartRunResult
    data class Retry(val reason: String) : StartRunResult
}
```

### 2. Wrap start

```kotlin
private suspend fun startRunSafely(request: WorkerGuardRequest): StartRunResult {
    return try {
        StartRunResult.Started(workerRunLogger.start(request.workerName))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isWriteBarrierOrRestore(e)) {
            maintenanceSafeDiagnosticSink.recordDiagnosticEvent(...)
            StartRunResult.Skipped(DiagnosticReasonCode.RESTORE_BLOCKED.name)
        } else if (classifyTransient(e)) {
            maintenanceSafeDiagnosticSink.recordDiagnosticEvent(...)
            StartRunResult.Retry(DiagnosticReasonCode.UNKNOWN_ERROR.name)
        } else {
            maintenanceSafeDiagnosticSink.recordDiagnosticEvent(...)
            StartRunResult.Retry(DiagnosticReasonCode.UNKNOWN_ERROR.name)
        }
    }
}
```

### 3. Use in both guarded paths

Apply to:

```text
runGuarded
runGuardedWithContext
```

After barrier checks and read-only special path.

## Tests

```text
worker_run_start_db_locked_returns_retry
worker_run_start_write_blocked_records_safe_sink_and_skips
worker_run_start_unknown_failure_records_safe_sink
worker_run_start_cancellation_rethrows
```

## Acceptance criteria

```text
Worker run logging failure cannot crash the worker without durable/safe diagnostic.
```

---

# PR 9 — Debug trace repository including safe sink and journal

## Issue fixed

```text
DDL-81-22 support/debug part
```

## Files

```text
domain/debug/DiagnosticsRepository.kt
data/debug/DiagnosticsRepositoryImpl.kt
data/database/dao/PipelineDiagnosticEventDao.kt
data/database/dao/OperationRunDao.kt
data/database/dao/OperationRunEventDao.kt
data/database/dao/BackgroundJobRunDao.kt
data/backup/MaintenanceSafeDiagnosticSink.kt
data/backup/RestoreJournal.kt
```

Tests:

```text
DiagnosticsRepositoryTest.kt
```

## Implementation steps

### 1. Add model

```kotlin
data class DiagnosticTrace(
    val correlationId: String,
    val pipelineEvents: List<PipelineDiagnosticEvent>,
    val operationRuns: List<OperationRun>,
    val operationRunEvents: List<OperationRunEvent>,
    val workerRuns: List<BackgroundJobRun>,
    val safeSinkEvents: List<MaintenanceSafeDiagnosticRecord>,
    val restoreJournalEvents: List<RestoreJournalEvent>
)
```

### 2. Add repository API

```kotlin
interface DiagnosticsRepository {
    suspend fun getTraceByCorrelationId(correlationId: String): DiagnosticTrace
    suspend fun getRecentFailures(limit: Int): List<DiagnosticFailureSummary>
}
```

### 3. DAO additions

Ensure these exist:

```text
PipelineDiagnosticEventDao.getByCorrelationId
OperationRunDao.getByCorrelationId
OperationRunEventDao.getByCorrelationId
BackgroundJobRunDao.getByCorrelationId
```

### 4. Include safe-sink records

`MaintenanceSafeDiagnosticSink` must expose:

```kotlin
suspend fun getRecordsByCorrelationId(correlationId: String): List<MaintenanceSafeDiagnosticRecord>
```

### 5. Include restore journal

`RestoreJournal` exposes:

```kotlin
suspend fun getEventsByCorrelationId(correlationId: String): List<RestoreJournalEvent>
```

## Tests

```text
trace_by_correlation_combines_pipeline_and_operation_events
trace_by_correlation_includes_worker_runs
trace_by_correlation_includes_safe_sink_events
trace_by_correlation_includes_restore_journal_events
recent_failures_includes_room_and_safe_sink_failures
```

## Acceptance criteria

```text
A support/debug caller can inspect one full flow by correlationId even if some events were written to safe sink or restore journal.
```

---

# PR 10 — Real golden acceptance tests

## Problem

Current golden tests mostly check taxonomy and simple defaults. They do not prove actual pipeline behavior.

## Files

```text
diagnostics/GlobalDurableDiagnosticsGoldenTest.kt
service/NotificationCaptureServiceDiagnosticsTest.kt
data/email/EmailReceiptIngestionServiceDiagnosticsTest.kt
domain/bank/BankApiIntegrationDiagnosticsTest.kt
data/repository/DatabaseBackupRepositoryRestoreDiagnosticsTest.kt
domain/diagnostics/OperationRunRecorderTest.kt
```

## Add tests

### Metadata

```text
metadata_sanitizer_blocks_source_raw_text
metadata_sanitizer_blocks_source_full_path
metadata_sanitizer_blocks_source_access_token
exception_sanitizer_redacts_iban
```

### Composite writer

```text
composite_writer_safe_sink_preserves_full_event
composite_writer_safe_sink_preserves_correlation_and_terminal
```

### Notification

```text
notification_repository_exception_terminal
notification_refresh_filter_drop_durable
notification_refresh_privacy_drop_durable
notification_listener_entry_received_before_dedupe
```

### Operation runs

```text
operation_run_terminal_event_failure_does_not_fail_business_operation
operation_stale_recovery_uses_duration_cutoff
operation_safe_handle_records_terminal_when_room_unavailable
```

### Restore

```text
restore_success_survives_db_swap_or_restart
restore_journal_does_not_expose_full_paths
restore_rollback_failure_critical
```

### Side effects

```text
side_effect_failed_is_terminal_and_preserves_metadata
side_effect_completed_is_terminal
```

### Email

```text
email_outer_exception_terminal
email_provider_detected_same_correlation
```

### Bank

```text
bank_generic_transaction_exception_terminal
bank_sync_errors_do_not_include_raw_provider_id
```

### Static guard

```text
static_guard_flags_record_blocked_operation_in_pipeline
static_guard_flags_direct_event_dao_insert
```

## Acceptance criteria

```text
Golden tests exercise actual service/coordinator/recorder code paths, not only enum/default behavior.
```

---

# Final recommended execution order

```text
PR 1  Metadata sanitizer final hardening
PR 2  Full safe-sink diagnostic fallback
PR 3  Notification diagnostics completion
PR 4  OperationRunRecorder safe fallback/reliability
PR 5  Restore journal durability/privacy/stages
PR 6  Side-effect terminal/metadata fixes
PR 7  Email + bank remaining terminal paths
PR 8  Worker start failure hardening
PR 9  Debug trace repository
PR 10 Real golden tests
```

---

# Global definition of done

The durable diagnostics refactor is complete only when:

```text
1. Every notification/email/bank/restore/worker/batch input has RECEIVED or STARTED.
2. Every input has terminal outcome, domain lifecycle event, or maintenance-safe record.
3. Safe-sink fallback preserves full diagnostic details.
4. Restore diagnostics survive DB swap and restart.
5. No diagnostic metadata exposes raw text/body/path/token/account/bank data.
6. Every operation run has STARTED and terminal status/event or safe-sink equivalent.
7. Stale RUNNING operation runs are recovered using age-based cutoff.
8. Side-effect completed/failed/cancelled outcomes are terminal.
9. Bank per-transaction failures always have item-level event.
10. Email outer failures always have terminal diagnostic.
11. Static guard blocks direct event construction, event DAO writes, and lossy blocked-operation shortcuts.
12. Golden tests cover real pipeline paths.
```