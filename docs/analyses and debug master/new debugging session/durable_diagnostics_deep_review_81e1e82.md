# Durable Diagnostics / Lifecycle Events Deep Review

Target commit: `81e1e828998d39549b2c404df52466965e75b182`  
Parent reviewed before: `5fba524acbafc3625af58646909daf6e8b8af5df`

## Executive verdict

The recent commit fixes a large part of the foundation work:

- metadata sanitizer is much stronger
- composite diagnostic writer exists
- worker cancellation/read-only backup issues are mostly fixed
- operation runs now have started/terminal events
- notification normal path is improved
- side-effect recorder exists
- bank sync has operation events
- CI static guard step was added

But the durable diagnostics refactor is **not fully done**. Several remaining issues are actual user/support bugs, not only architectural cleanup.

Highest priority remaining problems:

1. `CompositeDiagnosticEventWriter` safe-sink fallback is lossy and does not preserve diagnostic event details.
2. Notification refresh path still drops restore/privacy/filter/shutdown outcomes without diagnostics.
3. Notification repository failures are still swallowed without terminal `FAILED` events.
4. Restore success after DB swap can still lose the operation trail because the journal is deleted and Room is not safely finalized.
5. Metadata sanitizer has a serious allowlist-prefix bypass.
6. Operation run stale recovery API is confusing/bug-prone and may not recover stale runs correctly.
7. Side-effect recorder ignores caller metadata and marks failed/completed side effects as non-terminal.
8. Bank per-item generic exceptions still increment counters without per-transaction failure events.
9. Email outer exception path still returns parse error without durable terminal diagnostic.
10. Golden tests are mostly pure taxonomy tests, not pipeline/DAO/integration acceptance tests.

---

# 1. Fix Pack A — Metadata safety hardening

## Status

**Partially resolved, but one important privacy bug remains.**

Implemented:

- canonical key normalization
- recursive map/list/JSON sanitization
- token/JWT/path/IBAN/long-digit value scanning
- non-throwing `SafeEventMetadata.Builder`
- writer final-pass sanitation

## Remaining issue DDL-81-01 — safe-prefix bypass can allow dangerous keys

Severity: **High**  
Type: **privacy bug**

Current sanitizer logic:

```kotlin
if (SAFE_PREFIXES.any { canonical.startsWith(it) }) return false
```

This makes broad prefixes dangerous.

Examples that can bypass redaction:

```text
sourceRawText
sourceFullPath
sourceAccessToken
sourceAccountNumber
statusToken
reasonAuthorization
currencyAccountNumber
```

Because keys like `source...`, `status...`, `reason...`, `currency...` start with safe prefixes.

## User impact

Sensitive raw text, paths, account values, or auth values may enter diagnostics if callers use one of these prefixed keys.

## Fix strategy

Do not use broad prefix allowlisting.

Replace with:

```kotlin
private val SAFE_EXACT_KEYS = setOf(
    "expenseid",
    "receiptid",
    "operationtype",
    "operationid",
    "stage",
    "status",
    "count",
    "rows",
    "duration",
    "elapsed",
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
    "entityid",
    "entitytype",
    "causationid",
    "correlationid",
    "delivered",
    "partial",
    "percent",
    "spent",
    "limit",
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

Then:

```kotlin
fun isDangerousKey(key: String): Boolean {
    val canonical = canonicalizeKey(key)

    if (canonical in BLOCKED_EXACT) return true
    if (BLOCKED_SUBSTRINGS.any { canonical.contains(it) }) {
        return canonical !in SAFE_EXACT_KEYS
    }

    return false
}
```

If you need prefixes, only allow very specific hash suffixes:

```kotlin
canonical.endsWith("hash")
canonical.endsWith("idhash")
```

But never allow keys containing:

```text
raw
body
prompt
token
auth
secret
path
iban
account
card
```

## Tests to add

```text
metadata_sanitizer_blocks_source_raw_text
metadata_sanitizer_blocks_source_full_path
metadata_sanitizer_blocks_source_access_token
metadata_sanitizer_blocks_status_token
metadata_sanitizer_blocks_reason_authorization
metadata_sanitizer_allows_exact_source_only
metadata_sanitizer_allows_sourceIdHash
```

---

## Remaining issue DDL-81-02 — exception sanitizer is weaker than metadata sanitizer

Severity: **Medium/High**  
Type: **privacy bug**

`sanitizeExceptionMessage()` redacts paths, bearer tokens, and JWT-like values, but it does not apply:

```text
IBAN_PATTERN
LONG_DIGITS_PATTERN
email/account/card-like values
raw body-length text checks
```

## Fix strategy

Make exception message sanitization reuse `sanitizeStringValue()`:

```kotlin
fun sanitizeExceptionMessage(message: String?): String? {
    if (message == null) return null
    return sanitizeStringValue(message)
}
```

But tune `sanitizeStringValue()` so it redacts IBANs/long digits/paths/tokens and truncates safely.

## Tests

```text
exception_sanitizer_redacts_iban
exception_sanitizer_redacts_long_account_digits
exception_sanitizer_redacts_file_path
exception_sanitizer_redacts_bearer_token
exception_sanitizer_truncates_large_blob
```

---

# 2. Fix Pack B — Composite diagnostic writer

## Status

**Partially resolved.**

Implemented:

- `CompositeDiagnosticEventWriter`
- normal mode writes to Room
- maintenance/write-barrier/Room failure falls back to `MaintenanceSafeDiagnosticSink`

## Remaining issue DDL-81-03 — safe-sink fallback loses the actual diagnostic event

Severity: **High**  
Type: **actual diagnostic/support bug**

Current fallback calls:

```kotlin
safeSink.recordBlockedOperation(
    operation = "${event.pipeline.name}.${event.stage}",
    mode = restoreMaintenanceMode.currentMode(),
    pipeline = event.pipeline.name,
    entity = event.entityType,
    reason = MaintenanceBlockedReason.RESTORE_IN_PROGRESS
)
```

This loses:

```text
eventId
correlationId
causationId
stage detail
outcome
severity
reasonCode
entityId
sourceType
sourceIdHash
metadataJson
exceptionClass
exceptionMessage
isTerminal
elapsedMs
```

It also always uses:

```text
MaintenanceBlockedReason.RESTORE_IN_PROGRESS
```

even when the actual reason is:

```text
WRITE_BARRIER_DENIED
Room insert failed
DB closed
DB locked
migration issue
```

## User impact

During restore/DB failure, the app may record only “some blocked operation happened” instead of a durable terminal diagnostic. Correlation tracing is broken exactly when support needs it most.

## Fix strategy

Extend `MaintenanceSafeDiagnosticSink`:

```kotlin
suspend fun recordDiagnosticEvent(
    event: DiagnosticEvent,
    mode: RestoreMaintenanceMode.Mode,
    writeFailure: Throwable? = null
)
```

Persist a safe serialized record containing:

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
metadataJson sanitized
exceptionClass
exceptionMessageSafe
writeFailureClass
writeFailureMessageSafe
isTerminal
```

Then change composite writer:

```kotlin
private suspend fun recordToSafeSink(event: DiagnosticEvent, cause: Throwable?) {
    safeSink.recordDiagnosticEvent(
        event = event,
        mode = restoreMaintenanceMode.currentMode(),
        writeFailure = cause
    )
}
```

Use `recordBlockedOperation()` only for generic barrier diagnostics where no `DiagnosticEvent` exists.

## Tests

```text
composite_writer_fallback_preserves_correlation_id
composite_writer_fallback_preserves_outcome_and_reason_code
composite_writer_fallback_preserves_terminal_flag
composite_writer_fallback_uses_write_barrier_reason
composite_writer_fallback_sanitizes_metadata
```

---

# 3. Fix Pack C — Worker finalization/read-only mode

## Status

**Mostly resolved.**

Good fixes:

- cancellation finalization uses `NonCancellable`
- read-only backup path in `runGuardedWithContext` avoids `background_job_runs`
- `WorkerRunLogger.cancelled()` sets both `statusReason` and `cancellationReason`
- timeout cancellation removed from transient classifier

## Remaining issue DDL-81-04 — worker run start failure can still escape unclassified

Severity: **Medium**  
Type: **support/reliability bug**

Both guarded paths call:

```kotlin
val run = workerRunLogger.start(request.workerName)
```

outside the inner `try` that classifies worker execution failures.

If `workerRunLogger.start()` fails because Room is locked/closed or write barrier state changed, the exception can propagate instead of becoming:

```text
WorkerGuardResult.Retry
WorkerGuardResult.Skipped
maintenance-safe diagnostic
```

## Fix strategy

Wrap start in a guarded helper:

```kotlin
private suspend fun startRunOrSkipRetry(
    request: WorkerGuardRequest,
    mode: RestoreMaintenanceMode.Mode
): StartRunResult
```

If start fails:

- cancellation -> rethrow
- DB locked/busy -> return `Retry`
- write barrier/restore blocked -> safe sink + `Skipped`
- unknown -> safe sink + `Retry` or `Failed`

## Tests

```text
worker_run_start_db_locked_returns_retry
worker_run_start_write_blocked_records_safe_sink_and_skips
worker_run_start_cancellation_rethrows
```

---

# 4. Fix Pack D — OperationRunRecorder reliability

## Status

**Partially resolved.**

Good fixes:

- `STARTED` event emitted
- `increment()` persists counters immediately
- terminal finalization is idempotent
- `runOperation()` helper exists
- stale recovery method exists
- `OperationRunEvent.isTerminal` added

## Remaining issue DDL-81-05 — no maintenance-safe operation recorder

Severity: **High for restore/backup**  
Type: **actual diagnostic reliability bug**

`RoomOperationRunRecorder.start()` always writes Room:

```kotlin
val id = runDao.insert(OperationRun(...))
```

There is no composite/safe fallback equivalent to `CompositeDiagnosticEventWriter`.

## User impact

Operations started during DB-unsafe phases can still fail before recording anything, or write to the wrong/pre-swap DB.

## Fix strategy

Add:

```kotlin
class CompositeOperationRunRecorder : OperationRunRecorder
```

Behavior:

```text
normal DB writable:
    delegate to RoomOperationRunRecorder

maintenance/restore/write blocked:
    return SafeSinkOperationRunHandle

Room insert failure:
    return SafeSinkOperationRunHandle
```

`SafeSinkOperationRunHandle` writes operation events to `MaintenanceSafeDiagnosticSink` or restore journal.

## Tests

```text
operation_run_recorder_uses_room_when_normal
operation_run_recorder_uses_safe_handle_when_restore_mode
operation_run_recorder_uses_safe_handle_when_room_insert_fails
safe_operation_run_handle_preserves_correlation_and_terminal_status
```

---

## Remaining issue DDL-81-06 — stale recovery threshold API is bug-prone

Severity: **Medium**  
Type: **actual stale recovery bug depending on caller**

DAO query:

```sql
SELECT * FROM operation_runs
WHERE status = 'RUNNING' AND startedAt < :staleThresholdMs
```

Recorder API:

```kotlin
recoverStaleRunningOperationRuns(staleThresholdMs: Long = Long.MAX_VALUE)
```

This parameter name suggests a duration, but the DAO expects an absolute cutoff timestamp.

If caller passes:

```kotlin
4 * 60 * 60 * 1000L
```

then it searches for rows started before January 1970 + 4h, so no modern stale rows are recovered.

If caller uses default `Long.MAX_VALUE`, every running operation is marked stale immediately.

## Fix strategy

Change API:

```kotlin
suspend fun recoverStaleRunningOperationRuns(
    staleAgeMs: Long = STALE_THRESHOLD_MS
) {
    val cutoff = timeProvider.now() - staleAgeMs
    val stale = runDao.getStaleRunning(cutoff)
}
```

Rename DAO parameter:

```kotlin
getStaleRunning(startedBeforeMs: Long)
```

Also wire this method into app startup.

## Tests

```text
operation_recovery_uses_now_minus_stale_age
operation_recovery_does_not_abort_recent_running_run
operation_recovery_aborts_old_running_run
operation_recovery_is_called_on_startup
```

---

## Remaining issue DDL-81-07 — operation terminal event insert failure can break successful operation

Severity: **Medium**  
Type: **actual reliability bug**

`finalizeNonCancellable()` updates the run, then inserts terminal event:

```kotlin
val updated = runDao.finalizeIfRunning(...)
if (updated > 0) {
    event(...)
}
```

If `eventDao.insert()` fails after the operation succeeded, `run.success()` can throw. In `runOperation()`, that exception is caught by the outer catch and rethrown, potentially turning a successful business operation into a failure caused only by diagnostics.

## Fix strategy

Finalization must be best-effort after the business operation:

```kotlin
private suspend fun finalizeNonCancellable(...) {
    withContext(NonCancellable) {
        try {
            val updated = runDao.finalizeIfRunning(...)
            if (updated > 0) {
                runCatching { event(...) }
                    .onFailure { Timber.w(it, "Failed to write terminal operation event") }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to finalize operation run")
            // safe sink fallback if available
        }
    }
}
```

Do not throw from terminal diagnostics unless caller explicitly requested strict mode.

## Tests

```text
operation_success_not_failed_when_terminal_event_insert_fails
operation_status_finalized_even_if_terminal_event_insert_fails
operation_terminal_event_failure_goes_to_safe_sink
```

---

# 5. Fix Pack E — Notification capture diagnostics

## Status

**Normal path partially resolved; refresh path still broken.**

Good fixes in normal `onNotificationPosted` path:

- correlation ID generated early
- dedupe terminal diagnostic added
- fast privacy drop diagnostic added
- filter terminal diagnostic added
- package blocked terminal diagnostic added
- async privacy drop diagnostic added
- correlation passed to `processNotification`

## Remaining issue DDL-81-08 — `RECEIVED` is not emitted at true listener entry

Severity: **Medium/High**  
Type: **diagnostic contract gap**

The plan required:

```text
listener entry -> RECEIVED
then terminal outcome
```

Current code emits `RECEIVED` only after:

```text
restore mode check
isShuttingDown check
dedupe check
fast privacy check
text extraction
```

So these paths have no `RECEIVED` event:

```text
restore blocked
service shutting down
dedupe duplicate
fast privacy denied
```

They do have some terminal events, but not the full `RECEIVED -> terminal` attempt trail.

## Fix strategy

At the top of `onNotificationPosted`, after `sbn ?: return` and correlation creation, emit a safe `RECEIVED` event before all early exits.

For restore mode, the composite writer/safe sink must record it without Room.

## Tests

```text
notification_restore_blocked_has_received_and_terminal_safe_event
notification_dedupe_has_received_and_duplicate_terminal_event
notification_fast_privacy_has_received_and_privacy_terminal_event
notification_shutdown_has_received_and_cancelled_terminal_event
```

---

## Remaining issue DDL-81-09 — refresh path still has almost no diagnostics

Severity: **High**  
Type: **actual user/support bug**

`processNotificationBypassDedupe()` still returns silently for:

```text
restore blocked
fast privacy denied
filter rejected
isShuttingDown
async privacy denied
```

It also generates correlation only at the final `processNotification(...)` call, not at refresh entry.

## User impact

Manual refresh can miss notifications with no durable explanation.

## Fix strategy

Mirror normal path:

```kotlin
private fun processNotificationBypassDedupe(sbn: StatusBarNotification) {
    val packageName = sbn.packageName
    val notificationKey = sbn.key
    val correlationId = CorrelationIds.newId()

    emit RECEIVED stage="refresh"

    if (!restoreMaintenanceMode.isWritesAllowed()) {
        emit BLOCKED/RESTORE_BLOCKED terminal via safe writer
        return
    }

    if (isPrivacyDeniedFast()) {
        emit DROPPED/PRIVACY_DENIED terminal
        return
    }

    extract parts

    if (!NotificationFilter.shouldCapture(...)) {
        emit DROPPED/FILTER_REJECTED terminal
        return
    }

    if (isShuttingDown) {
        emit CANCELLED/CANCELLED_BY_SYSTEM terminal
        return
    }

    launch {
        privacyGate.check(...)
        if denied:
            emit DROPPED/PRIVACY_DENIED terminal
            return
        processNotification(..., correlationId)
    }
}
```

## Tests

```text
refresh_restore_blocked_writes_terminal_safe_event
refresh_privacy_fast_drop_writes_terminal_event
refresh_filter_drop_writes_terminal_event
refresh_shutdown_writes_cancelled_event
refresh_async_privacy_drop_writes_terminal_event
refresh_success_uses_same_correlation_id
```

---

## Remaining issue DDL-81-10 — repository processing exceptions are still swallowed

Severity: **High**  
Type: **actual user/support bug**

In `processNotification()`:

```kotlin
try {
    repository.processAndSave(...)
    Timber.d("Processed notification from: $packageName")
} catch (e: Exception) {
    Timber.e(e, "Failed to process notification")
}
```

No terminal diagnostic is emitted.

## User impact

If parser/save/repository logic fails, the user can lose a notification with no durable failure event.

## Fix strategy

Emit terminal failure:

```kotlin
catch (e: Exception) {
    if (e is CancellationException) {
        emit CANCELLED/CANCELLED_BY_SYSTEM terminal
        throw e
    }

    diagnosticEventWriter.emit(
        DiagnosticEvent(
            pipeline = AppPipeline.NOTIFICATION,
            stage = "repository",
            outcome = if (isRetryable(e)) FAILED_RETRYABLE else FAILED_FINAL,
            reasonCode = UNKNOWN_ERROR,
            severity = ERROR,
            correlationId = correlationId,
            sourceType = "notification",
            sourceIdHash = notificationKey.sha256Prefix(16),
            exception = e,
            isTerminal = true
        )
    )
}
```

Also emit `COMPLETED` only if downstream pipeline does not already produce terminal domain/lifecycle outcome.

## Tests

```text
notification_repository_exception_writes_failed_terminal_event
notification_repository_cancellation_writes_cancelled_and_rethrows
notification_success_has_terminal_domain_or_completed_event
```

---

# 6. Fix Pack F — Backup/restore journal-backed diagnostics

## Status

**Partially resolved, but still not sufficient.**

Good fixes:

- `RestoreJournal.JournalEntry` has `operationCorrelationId`
- some backup stages added
- some restore stages added
- post-swap code reportedly avoids old `run.success()`

## Remaining issue DDL-81-11 — successful restore operation trail can still disappear

Severity: **Critical for restore support**  
Type: **actual diagnostics durability bug**

`RestoreJournal.commitJournal()` deletes the journal:

```kotlin
deleteJournal()
```

If, after live DB swap, the code no longer uses the old Room `OperationRunHandle`, then a successful restore may leave:

```text
no old Room terminal event
no new Room terminal event
no journal file
```

That violates:

```text
Every batch/long operation has terminal durable status.
Restore diagnostics survive DB swap/restart.
```

## Fix strategy

Do not delete all successful operation evidence.

Options:

### Option A — keep compact success journal

Rename terminal success journal:

```text
restore_journal_last_success.json
```

Keep only safe metadata:

```text
operationId
operationCorrelationId
startedAt
finishedAt
sourceBackupHash
stages
status=SUCCESS
restartRequired=true
```

### Option B — import on next startup

After restart, read committed journal and insert into the restored DB:

```text
operation_runs status SUCCESS
operation_run_events terminal COMPLETED
```

Then delete the journal only after successful import.

Preferred: **B**, with A as fallback if import fails.

## Tests

```text
restore_success_after_swap_leaves_success_journal
restore_success_after_restart_imports_operation_run
restore_success_journal_deleted_only_after_import
```

---

## Remaining issue DDL-81-12 — restore journal stores full file paths

Severity: **High**  
Type: **privacy bug**

`RestoreJournal.JournalEntry` contains:

```text
sourceBackupPath
stagedDbPath
safetyBackupPath
liveDbPath
asset targetPath
```

These are written to JSON.

The diagnostics plan says no full local file paths in durable diagnostics.

## Fix strategy

Store:

```text
sourceBackupPathHash
sourceBackupDisplayName? basename only
stagedDbPathHash or internal token
safetyBackupPathHash or internal token
liveDbPathHash or internal token
asset targetRelativePathHash
```

If full paths are required for crash recovery, split journal into:

```text
restore_recovery_journal.json      // internal, pathful, not exposed/exported
restore_diagnostics_journal.json   // privacy-safe
```

Never expose/export the recovery journal as diagnostics.

## Tests

```text
restore_journal_diagnostics_does_not_store_full_paths
restore_recovery_can_still_use_internal_paths
asset_restore_event_uses_relative_or_hashed_path_only
```

---

## Remaining issue DDL-81-13 — backup/restore stage coverage remains incomplete

Severity: **Medium/High**  
Type: **support gap**

Commit summary says only these restore events were added:

```text
MAINTENANCE_ENTERED
JOURNAL_CREATED
LIVE_DB_SWAPPING
```

Still needed:

```text
BUNDLE_VALIDATED
STAGED_DB_CREATED
STAGED_DB_VERIFIED
STAGED_DB_MIGRATED
SAFETY_BACKUP_CREATED
LIVE_DB_SWAPPED
LIVE_DB_VERIFIED
ASSETS_RESTORING
ASSET_RESTORED
ASSET_FAILED
RESTART_REQUIRED
ROLLBACK_STARTED
ROLLBACK_COMPLETED
ROLLBACK_FAILED
```

## Fix strategy

Add a small helper:

```kotlin
restoreEvents.stage("BUNDLE_VALIDATED", COMPLETED)
restoreEvents.stage("STAGED_DB_VERIFIED", COMPLETED)
restoreEvents.stage("ROLLBACK_FAILED", FAILED_FINAL, severity = CRITICAL)
```

During DB-unsafe phases, this helper writes journal/safe sink only.

## Tests

```text
restore_wrong_password_has_bundle_validation_failed_event
restore_staged_db_failure_has_stage_event
restore_rollback_failure_has_critical_event
asset_restore_failure_has_asset_failed_event
```

---

# 7. Fix Pack G — Side-effect diagnostics

## Status

**Partially resolved.**

Good fixes:

- `SideEffectContext` exists
- `SideEffectDiagnosticRecorder` exists
- cancellation writes `CANCELLED` in `NonCancellable`
- side-effect events include entity type/id and correlation ID

## Remaining issue DDL-81-14 — side-effect recorder ignores caller metadata

Severity: **Medium**  
Type: **support/debug gap**

`runSideEffect()` accepts:

```kotlin
metadata: SafeEventMetadata
```

but `emit()` discards it and creates a new metadata object containing only:

```text
sideEffect
source
```

## Impact

Useful context passed by callers is lost:

```text
retryable
expenseId
receiptId
matchedEntityId
ruleId
source subtype
count
```

## Fix strategy

Add merge support to `SafeEventMetadata`:

```kotlin
fun merge(other: SafeEventMetadata): SafeEventMetadata
```

Then:

```kotlin
metadata = metadata.toBuilder()
    .put("sideEffect", name)
    .put("source", context.source ?: "")
    .build()
```

or:

```kotlin
SafeEventMetadata.builder()
    .putAll(metadata)
    .put("sideEffect", name)
    .put("source", context.source ?: "")
    .build()
```

## Tests

```text
side_effect_recorder_preserves_caller_metadata
side_effect_recorder_sanitizes_merged_metadata
```

---

## Remaining issue DDL-81-15 — side-effect terminal outcomes are not marked terminal

Severity: **Medium**  
Type: **diagnostic contract gap**

`SIDE_EFFECT_COMPLETED` and `SIDE_EFFECT_FAILED` are terminal outcomes for that side-effect attempt, but current recorder sets:

```text
SIDE_EFFECT_COMPLETED isTerminal=false
SIDE_EFFECT_FAILED isTerminal=false
CANCELLED isTerminal=true
```

## Fix strategy

Set:

```text
SIDE_EFFECT_COMPLETED isTerminal=true
SIDE_EFFECT_FAILED isTerminal=true
CANCELLED isTerminal=true
```

`SIDE_EFFECT_STARTED` remains non-terminal.

## Tests

```text
side_effect_completed_is_terminal
side_effect_failed_is_terminal
side_effect_cancelled_is_terminal
```

---

# 8. Fix Pack H — Email/receipt diagnostics

## Status

**Partially resolved.**

Good fixes:

- parser failure durable
- validation failure durable
- coordinator success/duplicate/error events added

## Remaining issue DDL-81-16 — `PROVIDER_DETECTED` event not found

Severity: **Medium**  
Type: **implementation gap**

Commit summary claims `PROVIDER_DETECTED`, but I did not find the literal `PROVIDER_DETECTED` in `EmailReceiptIngestionService.kt`.

Possible explanations:

- implemented under another stage name
- missing despite commit summary

## Fix strategy

Add explicit provider event after detection:

```kotlin
diagnosticEventWriter.emit(
    DiagnosticEvent(
        pipeline = AppPipeline.EMAIL,
        stage = "provider_detection",
        outcome = EventOutcome.COMPLETED,
        correlationId = correlationId,
        metadata = SafeEventMetadata.builder()
            .put("providerHash", provider.sha256Prefix(16))
            .build()
    )
)
```

If provider string is not sensitive, still prefer hash or enum.

## Tests

```text
email_provider_detected_event_after_intake
email_provider_detected_uses_same_correlation
```

---

## Remaining issue DDL-81-17 — email outer exception path has no durable diagnostic

Severity: **High**  
Type: **actual support bug**

Outer catch:

```kotlin
catch (e: Exception) {
    if (e is CancellationException) throw e
    Timber.e(e, "Error processing email receipt from $sender")
    return EmailReceiptResult.ParseError("Processing error: ${e.message}")
}
```

No durable `FAILED_FINAL` event is written.

## Fix strategy

Before returning:

```kotlin
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
```

Do not include sender/subject/body.

## Tests

```text
email_outer_exception_writes_failed_terminal_diagnostic
email_outer_exception_does_not_store_sender_subject_body
email_cancellation_rethrows_after_cancelled_event_if_needed
```

---

## Remaining issue DDL-81-18 — coordinator error event lacks reason/exception/message safety context

Severity: **Medium**  
Type: **support gap**

Coordinator error event:

```text
stage = coordinator
outcome = FAILED_FINAL
correlationId = correlationId
isTerminal = true
```

Missing:

```text
reasonCode
exceptionClass/message when available
safe error summary
entity/source context
```

## Fix strategy

Map coordinator error to reason:

```text
source conflict -> DUPLICATE or VALIDATION_FAILED
unexpected -> UNKNOWN_ERROR
```

Add safe metadata:

```text
errorCode
retryable=false
```

## Tests

```text
email_coordinator_error_has_reason_code
email_source_insert_conflict_has_specific_reason
```

---

# 9. Fix Pack I — Bank sync diagnostics

## Status

**Partially resolved.**

Good fixes:

- `BANK_SYNC` uses `runOperation()`
- token refresh events added
- page fetched event added
- per-transaction created/duplicate/validation/error events added for typed `CreateExpenseResult`
- counters increment per item

## Remaining issue DDL-81-19 — generic per-transaction exception has no `TRANSACTION_FAILED` event

Severity: **High if bank sync enabled**  
Type: **actual per-item diagnostics bug**

Inside per-transaction loop, generic catch does:

```kotlin
errors.add(...)
run.increment(processed = 1, failed = 1, errors = 1)
Timber.e(e, "Failed to import transaction")
```

But no operation event is inserted.

## User impact

A provider transaction can fail due to mapping/classification unexpected exception and only affect counters. The specific transaction outcome is not durable.

## Fix strategy

Inside catch:

```kotlin
run.event(
    "TRANSACTION_FAILED",
    EventOutcome.FAILED_RETRYABLE,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = e,
    metadata = SafeEventMetadata.builder()
        .putHashed("providerTransactionId", transaction.id)
        .put("currency", transaction.currency)
        .build()
)
```

## Tests

```text
bank_transaction_generic_exception_writes_transaction_failed_event
bank_transaction_generic_exception_hashes_provider_id
```

---

## Remaining issue DDL-81-20 — some bank failure events lack provider transaction hash

Severity: **Medium**  
Type: **support gap**

`CreateExpenseResult.Error` event has exception but no provider transaction hash.  
`InsertConflict` duplicate event lacks provider transaction hash.

## Fix strategy

Always include:

```text
providerTransactionIdHash
currency
```

Never include raw bank description/reference.

## Tests

```text
bank_error_event_has_provider_transaction_hash
bank_insert_conflict_duplicate_event_has_provider_transaction_hash
```

---

## Remaining issue DDL-81-21 — bank error list contains raw provider transaction IDs

Severity: **Medium**  
Type: **privacy/support risk**

Errors list includes strings like:

```text
Failed to import transaction ${transaction.id}: ...
Validation failed for transaction ${transaction.id}: ...
```

If `SyncResult.errors` is displayed/exported/logged, raw provider IDs leak.

## Fix strategy

Use hash or index:

```text
Failed to import provider transaction hash=<hash>
```

or store raw only in memory, never persisted/exported.

## Tests

```text
bank_sync_result_errors_do_not_include_raw_provider_transaction_id
```

---

# 10. Fix Pack J — Static guard / CI

## Status

**Mostly resolved.**

Good:

- CI runs `python3 scripts/verify_event_writers.py --fail-on-violation`
- allowlist file exists
- DAO insert/update rules reportedly added

## Remaining issue DDL-81-22 — static guard cannot detect lossy safe-sink substitutions

Severity: **Medium**  
Type: **process gap**

Static guard prevents direct event writes, but it does not prevent code from doing:

```kotlin
diagnosticSink.recordBlockedOperation(...)
```

instead of a full diagnostic event.

This is currently happening in notification restore-blocked paths and composite fallback.

## Fix strategy

Add guard rule:

```text
Direct MaintenanceSafeDiagnosticSink.recordBlockedOperation calls allowed only in:
- CompositeDiagnosticEventWriter
- DatabaseRead/WriteBarrier internals
- WorkerExecutionGuard for true worker barrier skips
- Restore/backup maintenance coordinator
```

Notification pipeline should use `DiagnosticEventWriter` and let composite fallback decide.

## Tests

```text
verify_event_writers_flags_direct_record_blocked_operation_in_pipeline
verify_event_writers_allows_record_blocked_operation_in_barrier_classes
```

---

# 11. Fix Pack K — Debug query support

## Status

**Partially resolved.**

Good:

- `PipelineDiagnosticEventDao.getRecentFailures()`
- `OperationRunEventDao.getRecentFailures()`

Remaining:

- no combined correlation trace model verified
- operation run with events repository not verified
- safe-sink diagnostics are not query-compatible with Room diagnostics

## Fix strategy

Add:

```kotlin
DiagnosticsRepository.getTraceByCorrelationId(correlationId)
```

Include:

```text
pipeline_diagnostic_events
operation_runs
operation_run_events
background_job_runs
safe sink events
restore journal events
transaction_events if correlation supported
receipt_events if correlation supported
privacy_audit_events if correlation supported
```

## Tests

```text
trace_by_correlation_combines_pipeline_and_operation_events
trace_by_correlation_includes_safe_sink_events
trace_by_correlation_includes_restore_journal_events
```

---

# 12. Fix Pack L — Golden tests

## Status

**Insufficient.**

Commit adds `GlobalDurableDiagnosticsGoldenTest`, but commit summary says they are mostly pure unit tests covering:

```text
metadata safety
correlation IDs
DiagnosticEvent defaults
taxonomy completeness
exception message sanitization
```

That does not prove the actual pipeline acceptance criteria.

## Required test upgrades

Add tests for actual code paths:

```text
notification_refresh_filter_drop_durable
notification_repository_exception_terminal
composite_writer_safe_sink_preserves_full_event
operation_run_terminal_event_failure_does_not_fail_business_operation
operation_stale_recovery_uses_duration_cutoff
restore_success_survives_db_swap_or_restart
side_effect_failed_is_terminal_and_preserves_metadata
email_outer_exception_terminal
bank_generic_transaction_exception_terminal
metadata_sanitizer_blocks_source_raw_text
```

Use fake DAOs/fake safe sink where Android integration is hard.

---

# 13. Acceptance matrix after commit 81e1e82

| Global criterion | Status | Notes |
|---|---:|---|
| Every input has domain row or terminal diagnostic/safe sink | Partial | Notification refresh and repository exceptions still fail |
| Domain mutations have lifecycle events in same transaction | Unknown | Not re-proven by this commit |
| Every duplicate decision durable | Better/partial | Notification normal/email/bank improved; verify receipt/review/import |
| Every validation failure durable | Better/partial | Email/bank improved; broad coverage unknown |
| Privacy/restore blocked decision durable | Partial | Composite fallback lossy; refresh restore blocked silent |
| Every worker has final status | Mostly | Start failure edge remains |
| Every batch operation has terminal status/counts | Partial | Operation runs improved; stale recovery/safe fallback issues remain |
| Restore diagnostics survive DB swap | Not yet | Success trail can disappear if journal deleted |
| Every side-effect failure durable | Partial | Failure emitted but not terminal and metadata lost |
| Metadata privacy-safe by construction | Partial | Safe-prefix bypass and exception gaps remain |
| Correlation can trace whole flow | Partial | Safe sink loses correlation; refresh creates late correlation |

---

# 14. Recommended next PR order

## PR 1 — Metadata sanitizer bypass fix

Fix:

```text
DDL-81-01
DDL-81-02
```

Tests:

```text
metadata_sanitizer_blocks_source_raw_text
metadata_sanitizer_blocks_source_full_path
metadata_sanitizer_blocks_source_access_token
exception_sanitizer_redacts_iban
exception_sanitizer_redacts_long_digits
```

## PR 2 — Full diagnostic safe-sink fallback

Fix:

```text
DDL-81-03
DDL-81-22
```

Add `recordDiagnosticEvent()` to `MaintenanceSafeDiagnosticSink`.

## PR 3 — Notification completion

Fix:

```text
DDL-81-08
DDL-81-09
DDL-81-10
```

Highest user-visible support value.

## PR 4 — Operation run reliability

Fix:

```text
DDL-81-05
DDL-81-06
DDL-81-07
```

Add composite operation recorder and correct stale cutoff API.

## PR 5 — Restore journal durability/privacy

Fix:

```text
DDL-81-11
DDL-81-12
DDL-81-13
```

Make restore success survive DB swap/restart.

## PR 6 — Side-effect terminal/metadata fix

Fix:

```text
DDL-81-14
DDL-81-15
```

## PR 7 — Email and bank remaining terminal paths

Fix:

```text
DDL-81-16
DDL-81-17
DDL-81-18
DDL-81-19
DDL-81-20
DDL-81-21
```

## PR 8 — Real golden tests

Add actual pipeline/fake DAO tests, not only taxonomy tests.

---

# 15. Highest priority bug list for agent

## Must fix first

```text
1. EventMetadataSanitizer safe-prefix bypass.
2. CompositeDiagnosticEventWriter safe-sink loses correlation/outcome/reason.
3. Notification refresh path silent returns.
4. Notification repository exception swallowed.
5. Restore success trail deleted after DB swap.
```

## Then fix

```text
6. OperationRunRecorder safe fallback.
7. Operation run stale recovery cutoff.
8. Operation terminal event failure should not fail business operation.
9. SideEffectDiagnosticRecorder metadata lost / terminal flag.
10. Bank generic per-transaction exception missing event.
11. Email outer catch missing event.
```

---

# 16. Source links checked

Commit:

- https://github.com/panospao7/Cost-agregator/commit/81e1e828998d39549b2c404df52466965e75b182

Key files:

- `EventMetadataSanitizer.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt

- `SafeEventMetadata.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt

- `DiagnosticEventWriter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticEventWriter.kt

- `CompositeDiagnosticEventWriter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeDiagnosticEventWriter.kt

- `MaintenanceSafeDiagnosticSink.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSink.kt

- `RestoreJournal.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `OperationRunRecorder.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt

- `OperationRunDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunDao.kt

- `OperationRunEventDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunEventDao.kt

- `WorkerExecutionGuard.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt

- `WorkerRunLogger.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt

- `NotificationCaptureService.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `SideEffectDiagnosticRecorder.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SideEffectDiagnosticRecorder.kt

- `EmailReceiptIngestionService.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `BankApiIntegration.kt`  
  https://github.com/panospao7/Cost-agregator/blob/81e1e828998d39549b2c404df52466965e75b182/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `.github/workflows/ci.yml`  
  https://github.com/panospao7/Cost-agregator/commit/81e1e828998d39549b2c404df52466965e75b182