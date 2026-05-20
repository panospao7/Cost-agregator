# Remaining Durable Diagnostics Implementation Plan

Target commit: `f876b3bc3963b0a5f9557932b641d7979ea03060`

Purpose: finish the remaining durable diagnostics/lifecycle-event refactor after the DDL-512 fixes.

---

## 0. Remaining issue summary

Highest-priority actual bugs:

```text
DDL-F876-01 resetDatabase writes Room operation events after deleting live DB
DDL-F876-02 legacy importDatabase writes Room operation events after DB swap
DDL-F876-04 SafeSinkOperationRunHandle can emit multiple terminal outcomes
DDL-F876-07 notification parse diagnostic loses listener correlation
DDL-F876-08 notification pipeline exception diagnostic loses listener correlation
DDL-F876-11 transaction side effects do not receive create correlation
DDL-F876-03 restore diagnostics still store recovery full paths in same durable JSON
```

Secondary but important:

```text
DDL-F876-05 operation terminal events lose reason codes
DDL-F876-06 operation increment failure is Timber-only, not durable
DDL-F876-10 update/delete/bulk transaction events lack correlation
DDL-F876-12 importer can ignore legacy zero-event success journals forever
DDL-F876-13 importer can duplicate event IDs within same import pass
DDL-F876-14 normal operation_run_events do not get eventId
DDL-F876-15 hash-looking safe keys can still accept plain values
DDL-F876-16 regression tests are too structural
```

---

# PR 1 — DB replacement safety for reset/import

## Issues fixed

```text
DDL-F876-01
DDL-F876-02
```

## Type

Critical actual restore/reset/import safety bug.

## Goal

After the app deletes, replaces, swaps, or mutates the live DB files, no code may call the old Room-backed `OperationRunHandle`.

Forbidden after destructive point:

```kotlin
run.event(...)
run.success()
run.failedFinal(...)
run.failedRetryable(...)
run.cancelled(...)
run.partialSuccess(...)
run.increment(...)
```

Use restore journal + maintenance-safe sink only.

## Files

```text
app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
```

Tests:

```text
DatabaseBackupRepositoryResetDiagnosticsTest.kt
DatabaseBackupRepositoryLegacyImportDiagnosticsTest.kt
RestoreDiagnosticsSinkTest.kt
```

---

## Step 1.1 — Reuse RestoreDiagnosticsSink for resetDatabase

In `resetDatabase()`, create `RestoreDiagnosticsSink` immediately after starting the operation run and restore journal.

Current risky pattern:

```kotlin
restoreJournal.commitJournal(journalEntry)
run.event("RESTART_REQUIRED", ...)
run.success()
restoreMaintenanceMode.exit(forceRestartRequired = true)
```

Replace with:

```kotlin
val restoreEvents = RestoreDiagnosticsSink(
    operationRunHandle = run,
    restoreJournal = restoreJournal,
    safeSink = maintenanceSafeDiagnosticSink,
    maintenanceMode = restoreMaintenanceMode,
    correlationId = run.correlationId,
    operationType = "RESET_DATABASE",
    ...
)
```

Before deleting/clearing live DB files:

```kotlin
restoreEvents.event("RESET_STARTED", EventOutcome.ATTEMPTED)
restoreEvents.event("MAINTENANCE_ENTERED", EventOutcome.COMPLETED)
restoreEvents.event("SAFETY_BACKUP_CREATED", EventOutcome.COMPLETED)
```

At the destructive point:

```kotlin
restoreEvents.markLiveDbSwapStarted()
```

After this point, only use:

```kotlin
restoreEvents.event(...)
restoreJournal.transitionTo(...)
restoreJournal.commitJournal(...)
restoreMaintenanceMode.exit(...)
```

No direct `run.*`.

---

## Step 1.2 — Reset success ordering

Correct success sequence:

```kotlin
restoreEvents.markLiveDbSwapStarted()

restoreEvents.event(
    stage = "LIVE_DB_DELETED",
    outcome = EventOutcome.COMPLETED,
    severity = EventSeverity.WARNING
)

restoreEvents.event(
    stage = "RESTART_REQUIRED",
    outcome = EventOutcome.COMPLETED,
    severity = EventSeverity.WARNING,
    isTerminal = true
)

journalEntry = restoreJournal.transitionTo(
    journalEntry,
    RestoreJournal.JournalState.COMPLETE
)

restoreJournal.commitJournal(journalEntry)

restoreMaintenanceMode.exit(forceRestartRequired = true)
```

Important:

```text
RESTART_REQUIRED must be appended before commitJournal().
Do not transition after commitJournal().
```

---

## Step 1.3 — Reset failure after destructive point

If reset fails after live DB deletion begins:

```kotlin
restoreEvents.event(
    stage = "RESET_FAILED_AFTER_DB_DELETE",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.CRITICAL,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = e,
    isTerminal = true
)
```

Do not call:

```kotlin
run.failedFinal(...)
```

after destructive point.

---

## Step 1.4 — Apply same pattern to legacy importDatabase

In legacy `importDatabase()`:

Before `replaceDatabaseFiles(...)`, Room-backed run is allowed.

Immediately before or inside the DB replacement section:

```kotlin
restoreEvents.markLiveDbSwapStarted()
```

After that, replace:

```kotlin
run.event("RESTART_REQUIRED", ...)
run.success()
run.failedFinal(...)
```

with:

```kotlin
restoreEvents.event(...)
restoreJournal.transitionTo(...)
restoreJournal.commitJournal(...)
```

For post-swap failure:

```kotlin
restoreEvents.event(
    stage = "LEGACY_IMPORT_FAILED_AFTER_SWAP",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.CRITICAL,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = e,
    isTerminal = true
)
```

---

## Step 1.5 — Add helper to prevent future mistakes

Add local helper in `DatabaseBackupRepositoryImpl`:

```kotlin
private fun assertRoomRunAllowedAfterSwapAllowed(restoreEvents: RestoreDiagnosticsSink) {
    check(!restoreEvents.isLiveDbSwapStarted) {
        "Room operation run must not be used after live DB swap/delete starts"
    }
}
```

Or better, do not keep `run` in scope after destructive point:

```kotlin
var roomRun: OperationRunHandle? = run
...
restoreEvents.markLiveDbSwapStarted()
roomRun = null
```

This makes accidental calls harder.

---

## Tests for PR 1

```text
reset_database_after_live_db_delete_does_not_call_room_run_event
reset_database_after_live_db_delete_does_not_call_room_run_success
reset_database_restart_required_written_before_commit_journal
reset_database_success_journal_contains_terminal_restart_required
reset_database_operation_event_failure_does_not_fail_successful_reset
legacy_import_after_swap_does_not_call_room_operation_run
legacy_import_after_swap_does_not_call_room_run_success
legacy_import_after_swap_failure_uses_journal_not_room_run_failed_final
legacy_import_restart_required_in_success_journal
```

## Acceptance criteria

```text
1. resetDatabase uses journal/safe sink only after live DB deletion starts.
2. legacy importDatabase uses journal/safe sink only after DB replacement starts.
3. RESTART_REQUIRED is committed into success journal for reset/import.
4. Diagnostic finalization cannot fail a successful reset/import.
```

---

# PR 2 — Operation handle terminal/reason correctness

## Issues fixed

```text
DDL-F876-04
DDL-F876-05
DDL-F876-06
DDL-F876-14
```

## Type

Batch operation diagnostic correctness/reliability.

## Files

```text
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeSinkOperationRunHandle.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/OperationRunEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunEventDao.kt
```

Tests:

```text
OperationRunRecorderTest.kt
CompositeOperationRunRecorderTest.kt
SafeSinkOperationRunHandleTest.kt
BankApiIntegrationDiagnosticsTest.kt
```

---

## Step 2.1 — Direct terminal event must mark safe handle terminal

Current bug:

```kotlin
run.event("WRITE_BARRIER", BLOCKED, isTerminal = true)
run.cancelled(...)
```

can produce two terminal events on safe handle.

Fix in `SafeSinkOperationRunHandle.event(...)`:

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
    if (isTerminal && !terminal.compareAndSet(false, true)) {
        return
    }

    emitSafeOperationEvent(...)
}
```

If `isTerminal = false`, do not modify terminal state.

---

## Step 2.2 — Prefer one terminal event per operation

In operation users, make intermediate blocked events non-terminal if a final status follows.

For bank write barrier:

Change:

```kotlin
run.event("WRITE_BARRIER", BLOCKED, isTerminal = true)
run.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
```

to:

```kotlin
run.event(
    stage = "WRITE_BARRIER",
    outcome = EventOutcome.BLOCKED,
    reasonCode = DiagnosticReasonCode.RESTORE_BLOCKED,
    severity = EventSeverity.WARNING,
    isTerminal = false
)

run.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
```

Terminal event should be `CANCELLED` with reason `RESTORE_BLOCKED`.

---

## Step 2.3 — Preserve reason codes in terminal operation events

Update terminal methods:

```kotlin
cancelled(reason: String?)
failedFinal(reason: String, error: Throwable?)
failedRetryable(reason: String, error: Throwable?)
partialSuccess(summary: String?)
```

Map string reason to `DiagnosticReasonCode?`:

```kotlin
private fun parseReasonCode(reason: String?): DiagnosticReasonCode? =
    reason?.let { runCatching { DiagnosticReasonCode.valueOf(it) }.getOrNull() }
```

When inserting terminal event:

```kotlin
event(
    stage = status,
    outcome = statusToOutcome(status),
    reasonCode = parseReasonCode(statusReason ?: cancellationReason),
    metadata = SafeEventMetadata.builder()
        .put("statusReason", statusReason)
        .put("summary", errorSummary)
        .build(),
    exception = error,
    isTerminal = true
)
```

For `cancelled(RESTORE_BLOCKED)`:

```text
operation_run_events.reasonCode = RESTORE_BLOCKED
outcome = CANCELLED
```

---

## Step 2.4 — Generate eventId for all normal operation events

Currently imported restore events can have `eventId`, but normal operation events may not.

In `RoomOperationRunRecorder.Handle.event(...)`:

```kotlin
val eventId = CorrelationIds.newId()
OperationRunEvent(
    eventId = eventId,
    ...
)
```

Also in terminal events.

If `eventId` can be passed from caller later, add optional parameter:

```kotlin
eventId: String = CorrelationIds.newId()
```

but avoid breaking API unless needed.

---

## Step 2.5 — Make increment failures durable

`increment()` should not throw and should not be Timber-only.

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
                    .put("processed", processed)
                    .put("succeeded", succeeded)
                    .put("failed", failed)
                    .put("skipped", skipped)
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

---

## Step 2.6 — Stale recovery event insert best-effort

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

Do not let startup fail because a stale recovery event insert failed.

---

## Tests for PR 2

```text
safe_handle_direct_terminal_event_marks_handle_terminal
safe_handle_direct_terminal_then_cancelled_has_one_terminal
safe_handle_cancelled_then_success_has_one_terminal
safe_handle_partial_success_then_success_has_one_terminal
bank_sync_restore_blocked_safe_handle_has_one_terminal_event
bank_sync_restore_blocked_terminal_reason_is_restore_blocked
operation_cancelled_terminal_event_has_reason_code
operation_failed_terminal_event_has_safe_summary
operation_partial_success_terminal_event_has_summary_metadata
operation_increment_failure_records_safe_diagnostic
operation_increment_failure_does_not_fail_business_flow
stale_recovery_status_update_survives_event_insert_failure
operation_run_event_has_event_id_for_normal_room_events
```

## Acceptance criteria

```text
1. Every operation has at most one terminal event.
2. Terminal events preserve reasonCode/statusReason.
3. Counter write failures are durable through safe sink.
4. All operation_run_events have eventId.
5. Stale recovery cannot crash startup because event insert failed.
```

---

# PR 3 — Notification and transaction correlation completion

## Issues fixed

```text
DDL-F876-07
DDL-F876-08
DDL-F876-10
DDL-F876-11
```

## Type

Traceability gap with high support impact.

## Files

```text
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt
app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
```

Tests:

```text
NotificationProcessingPipelineDiagnosticsTest.kt
NotificationCaptureServiceDiagnosticsTest.kt
TransactionLifecycleCoordinatorCorrelationTest.kt
TransactionSideEffectDispatcherTest.kt
```

---

## Step 3.1 — Move notification correlation creation outside try

In `NotificationProcessingPipeline.process(...)`, ensure this pattern:

```kotlin
val cid = correlationId ?: CorrelationIds.newId()

return try {
    processInternal(
        notification = notification,
        storageNotification = storageNotification,
        correlationId = cid
    )
} catch (e: Exception) {
    writePipelineDiagnosticEvent(
        outcome = ...,
        packageName = notification.packageName,
        correlationId = cid,
        exception = e
    )
    ...
}
```

Do not generate a new correlation inside catch.

---

## Step 3.2 — Use listener correlation for parse/provenance diagnostics

Find parse/provenance diagnostic event in `processInternal(...)`.

Change from:

```kotlin
diagnosticEventWriter.emit(
    DiagnosticEvent(
        pipeline = AppPipeline.NOTIFICATION,
        stage = "parse",
        outcome = EventOutcome.COMPLETED,
        ...
    )
)
```

to:

```kotlin
diagnosticEventWriter.emit(
    DiagnosticEvent(
        pipeline = AppPipeline.NOTIFICATION,
        stage = "parse",
        outcome = EventOutcome.COMPLETED,
        correlationId = correlationId,
        ...
    )
)
```

Every diagnostic in notification pipeline must use the `cid` from boundary.

---

## Step 3.3 — Ensure terminal review diagnostic is correlated

For `NeedsReview` result:

```text
stage = review
outcome = NEEDS_REVIEW or CREATED
entityType = PendingReview
entityId = reviewId
correlationId = listener correlation
```

If this is already present, add regression test only.

---

## Step 3.4 — Add correlation to update/delete/bulk transaction methods

Transaction schema supports correlation. APIs must accept it.

Add optional params:

```kotlin
suspend fun updateExpense(
    ...,
    correlationId: String? = null,
    causationId: String? = null
)

suspend fun deleteExpense(
    ...,
    correlationId: String? = null,
    causationId: String? = null
)

suspend fun bulkUpdateCategory(
    ...,
    correlationId: String? = null,
    causationId: String? = null
)
```

Inside coordinator:

```kotlin
val cid = correlationId ?: CorrelationIds.newId()
TransactionEvent(
    ...,
    correlationId = cid,
    causationId = causationId
)
```

For validation failures on update/delete:

```text
diagnostic or transaction event uses same cid
```

---

## Step 3.5 — Pass create correlation into side effects

Current create flow likely does:

```kotlin
dispatchPostCreationSideEffects(expenseId, source)
```

Change to:

```kotlin
dispatchPostCreationSideEffects(
    expenseId = expenseId,
    source = source,
    correlationId = cid,
    causationId = createdEventIdOrNull
)
```

Update dispatcher API:

```kotlin
suspend fun dispatchOnCreated(
    expenseId: Long,
    source: ExpenseSource,
    correlationId: String,
    causationId: String? = null
)
```

Each side-effect diagnostic uses:

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

---

## Step 3.6 — Bank and notification correlation verification

Bank already sets:

```kotlin
CreateExpenseRequest.correlationId = run.correlationId
```

After side-effect/transaction changes, ensure:

```text
bank operation event correlation = transaction CREATED correlation = side-effect correlation
```

Notification auto-accept flow should also satisfy:

```text
listener RECEIVED correlation = pipeline parse correlation = transaction CREATED correlation = side-effect correlation
```

---

## Tests for PR 3

```text
notification_parse_event_uses_listener_correlation
notification_pipeline_exception_uses_listener_correlation
notification_success_review_created_has_correlated_terminal_diagnostic
notification_success_expense_created_uses_listener_correlation
transaction_update_uses_supplied_correlation
transaction_delete_uses_supplied_correlation
bulk_update_uses_supplied_correlation
transaction_create_side_effect_uses_request_correlation
bank_transaction_side_effect_uses_bank_sync_correlation
notification_auto_accept_side_effect_uses_listener_correlation
```

## Acceptance criteria

```text
1. Notification parse, error, terminal, review, expense, side-effect events share one correlation.
2. Bank operation, transaction lifecycle, and side effects share one correlation.
3. Transaction update/delete/bulk events can be correlated by caller.
```

---

# PR 4 — Restore journal privacy split and importer robustness

## Issues fixed

```text
DDL-F876-03
DDL-F876-12
DDL-F876-13
```

## Type

Privacy architecture + restore trace import robustness.

## Files

```text
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt
app/src/main/java/com/yourname/expensetracker/data/debug/DiagnosticsRepositoryImpl.kt
```

Tests:

```text
RestoreJournalPrivacyTest.kt
RestoreJournalImporterTest.kt
DiagnosticsRepositoryTest.kt
```

---

## Step 4.1 — Split recovery and diagnostics journals

Implement two durable files:

```text
restore_recovery_journal.json
restore_diagnostics_journal.json
```

And preserved files:

```text
restore_diagnostics_last_success.json
restore_diagnostics_last_failure.json
restore_recovery_last_failure.json   // optional, internal only
```

### Recovery journal

Can contain:

```text
_sourceBackupPath
_stagedDbPath
_safetyBackupPath
_liveDbPath
assetTargetPath
```

Rules:

```text
internal only
never exposed by DiagnosticsRepository
never exported in support bundles
used only for crash recovery and cleanup
```

### Diagnostics journal

Can contain only:

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

## Step 4.2 — Keep bridge sanitizer if split is too large

If full split is risky, enforce bridge now:

```kotlin
fun toDiagnosticsJson(): JSONObject {
    val json = raw.toJson()
    remove all keys starting with "_"
    remove all values matching path patterns
    sanitize metadataJson fields
    return json
}
```

Then ensure only `toDiagnosticsJson()` is used by:

```text
DiagnosticsRepository
support export
debug UI
safe sink trace
```

But this should be considered transitional.

---

## Step 4.3 — Preserve real asset target path in recovery journal

Current risk:

```text
toJson stores targetName only
fromJson reads targetName into targetPath
```

For recovery, store:

```text
assetTargetPath
assetTargetPathHash
assetDisplayName
```

In diagnostics, expose only:

```text
assetDisplayName
assetTargetPathHash / assetRelativePathHash
```

---

## Step 4.4 — Import legacy zero-event success journal

Current importer:

```kotlin
if (events.isEmpty()) return
```

Fix:

If journal is success/complete and has zero events:

Option A — insert summary operation run:

```text
operationType = RESTORE_COSTBACKUP
status = SUCCESS
correlationId = journal.operationCorrelationId
metadata.legacyEmptyEvents = true
```

Option B — mark imported with reason:

```text
importedAt = now
importNote = legacy_empty_events
```

Preferred: insert summary row if possible, then mark imported.

---

## Step 4.5 — Importer duplicate event ID handling within same pass

Use mutable set:

```kotlin
val importedIds = operationRunEventDao.getByRunId(run.id)
    .mapNotNull { it.eventId }
    .toMutableSet()

for (event in events) {
    if (!importedIds.add(event.eventId)) continue
    operationRunEventDao.insert(...)
}
```

Add DB unique index if acceptable:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS index_operation_run_events_eventId_unique
ON operation_run_events(eventId)
WHERE eventId IS NOT NULL;
```

If Room/SQLite partial index support is awkward, keep code-level guard.

---

## Tests for PR 4

```text
restore_diagnostics_journal_has_no_full_paths
restore_recovery_journal_roundtrips_full_paths
diagnostics_trace_never_exposes_internal_path_fields
support_export_never_includes_internal_path_fields
asset_restore_recovery_keeps_real_target_path_in_recovery_journal
restore_import_marks_legacy_success_journal_without_events_as_imported_or_summary_imported
restore_import_skips_duplicate_event_ids_within_same_journal
restore_import_retries_missing_events_when_run_exists
```

## Acceptance criteria

```text
1. Diagnostics journal/debug trace/support export never expose full paths.
2. Recovery journal keeps required pathful recovery data.
3. Legacy empty success journals do not cause repeated startup work forever.
4. Duplicate event IDs do not import twice.
```

---

# PR 5 — Metadata final edge hardening

## Issue fixed

```text
DDL-F876-15
```

## Type

Privacy hardening.

## Files

```text
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt
app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt
```

Tests:

```text
EventMetadataSanitizerTest.kt
SafeEventMetadataTest.kt
```

---

## Step 5.1 — Any key ending in hash must validate hash value

Current risk:

```text
packageHash is in SAFE_EXACT_KEYS but not SAFE_HASH_KEYS
```

Rule:

```text
If canonical key endsWith("hash"), then:
  - it must be in SAFE_HASH_KEYS
  - value must match HASH_VALUE_PATTERN
Otherwise redact.
```

Implementation:

```kotlin
private val HASH_VALUE_PATTERN = Regex("^[a-fA-F0-9]{8,128}$")

private fun isHashLikeKey(canonical: String): Boolean =
    canonical.endsWith("hash") || canonical.endsWith("idhash")

private fun sanitizeHashValue(value: Any?): Any? =
    if (value is String && HASH_VALUE_PATTERN.matches(value)) value else REDACTED
```

In `sanitizeValue(key, value)`:

```kotlin
val canonical = canonicalizeKey(key)

if (isHashLikeKey(canonical)) {
    return if (canonical in SAFE_HASH_KEYS) {
        sanitizeHashValue(value)
    } else {
        REDACTED
    }
}
```

Then normal safe-key logic.

---

## Step 5.2 — Align SAFE_EXACT_KEYS and SAFE_HASH_KEYS

Add invariant test:

```kotlin
SAFE_EXACT_KEYS
    .filter { it.endsWith("hash") || it.endsWith("idhash") }
    .forEach { assertTrue(it in SAFE_HASH_KEYS) }
```

Or remove all hash-like entries from `SAFE_EXACT_KEYS`.

Recommended:

```text
Hash-like keys belong only in SAFE_HASH_KEYS.
Non-hash safe keys belong in SAFE_EXACT_KEYS.
```

---

## Step 5.3 — Ensure putHashed always creates valid hash

`SafeEventMetadata.Builder.putHashed(...)` should:

```text
hash raw value
store under approved hash key
result passes HASH_VALUE_PATTERN
```

If caller passes key that is not approved hash key:

```text
either reject/drop or convert to safe approved key
```

Prefer:

```kotlin
if (canonicalizeKey(key) !in SAFE_HASH_KEYS) {
    put("${key}Hash", sha256(value)) only if resulting canonical is approved
}
```

But avoid inventing dangerous keys like `rawTextHash`.

---

## Tests for PR 5

```text
package_hash_plain_text_value_is_redacted
package_hash_hex_value_is_allowed
source_id_hash_plain_text_value_is_redacted
provider_transaction_id_hash_plain_text_value_is_redacted
unknown_hash_key_is_redacted_even_with_hash_value
raw_text_hash_is_redacted
all_safe_exact_hash_keys_are_in_safe_hash_keys_or_removed
put_hashed_source_id_hash_is_allowed
```

## Acceptance criteria

```text
1. No hash-looking key can carry plain raw text.
2. Unknown hash-like keys are not trusted.
3. All approved hash keys validate hash-format values.
```

---

# PR 6 — Recent failures and trace completeness

## Issue fixed

```text
DDL-F876-12 partly
DDL-F876-20 equivalent from previous review
```

## Type

Support tooling completeness.

## Files

```text
app/src/main/java/com/yourname/expensetracker/domain/debug/DiagnosticsRepository.kt
app/src/main/java/com/yourname/expensetracker/data/debug/DiagnosticsRepositoryImpl.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/BackgroundJobRunDao.kt
app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSink.kt
```

Tests:

```text
DiagnosticsRepositoryTest.kt
```

---

## Step 6.1 — Ensure trace includes all journal locations

`getTraceByCorrelationId(correlationId)` must include:

```text
active diagnostics journal
last success diagnostics journal
last failure diagnostics journal
imported retained journal if present
```

API:

```kotlin
suspend fun getAllDiagnosticEventsByCorrelationId(
    correlationId: String
): List<RestoreJournalEvent>
```

Do not read recovery journal.

---

## Step 6.2 — Recent failures aggregate all sources

Define:

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

Sources:

```text
pipeline_diagnostic_events
operation_run_events
background_job_runs
maintenance safe sink records
restore diagnostics journal events
operation_runs terminal failed statuses if not represented by events
```

Failure criteria:

```text
severity in WARNING/ERROR/CRITICAL
outcome in FAILED_RETRYABLE, FAILED_FINAL, BLOCKED, DROPPED, CANCELLED, SIDE_EFFECT_FAILED
worker status in FAILED, RETRY, CANCELLED, STALE_ABORTED
operation status in FAILED_RETRYABLE, FAILED_FINAL, CANCELLED, STALE_ABORTED, PARTIAL_SUCCESS
```

Sort descending by `occurredAt`, apply final limit.

---

## Step 6.3 — Sanitize again before returning debug models

Before returning safe sink/journal metadata through debug repository:

```kotlin
metadataJson = sanitizer.sanitizeJsonString(metadataJson)
messageSafe = sanitizer.sanitizeExceptionMessage(messageSafe)
```

Reject/strip pathful fields.

---

## Tests for PR 6

```text
trace_by_correlation_includes_active_journal_events
trace_by_correlation_includes_success_journal_events
trace_by_correlation_includes_failure_journal_events
trace_by_correlation_excludes_recovery_path_fields
recent_failures_includes_pipeline_failures
recent_failures_includes_operation_event_failures
recent_failures_includes_worker_failures
recent_failures_includes_safe_sink_failures
recent_failures_includes_restore_journal_failures
recent_failures_sorted_by_occurred_at_desc
recent_failures_never_exposes_recovery_paths
```

## Acceptance criteria

```text
1. Correlation trace includes Room, safe sink, worker, operation, and restore journal diagnostics.
2. Recent failures represent all durable failure sources.
3. Debug outputs never expose recovery-only paths.
```

---

# PR 7 — Real behavioral regression tests

## Issue fixed

```text
DDL-F876-16
```

## Type

Regression risk.

## Goal

Replace structural/reflection/helper-only tests with behavior tests that exercise production classes or realistic fakes.

## Files

```text
app/src/test/java/com/yourname/expensetracker/diagnostics/DDL512RegressionTest.kt
app/src/test/java/com/yourname/expensetracker/data/backup/RestoreJournalTest.kt
app/src/test/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSinkTest.kt
app/src/test/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorderTest.kt
app/src/test/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipelineDiagnosticsTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorCorrelationTest.kt
```

---

## Required tests

### Restore journal / sink behavior

```text
restore_diagnostics_sink_appends_to_real_restore_journal
restore_journal_append_keeps_previous_events_using_real_class
restore_journal_transition_preserves_real_events_array
restore_journal_fail_preserves_events
restore_journal_commit_preserves_restart_required
```

### Reset/import DB replacement safety

Use fake `OperationRunHandle` that records calls and fails test if called after `markLiveDbSwapStarted`.

```text
reset_database_after_delete_does_not_call_room_operation_handle
legacy_import_after_swap_does_not_call_room_operation_handle
reset_database_success_journal_has_terminal_restart_required
legacy_import_success_journal_has_terminal_restart_required
```

### Operation handle terminal idempotency

```text
safe_handle_direct_terminal_then_cancelled_has_one_terminal
safe_handle_cancelled_then_success_has_one_terminal
room_handle_blocked_then_cancelled_single_terminal_policy
operation_increment_failure_records_safe_sink
```

### Notification correlation

```text
notification_parse_event_uses_listener_correlation
notification_pipeline_error_uses_listener_correlation
notification_repository_receives_listener_correlation
notification_review_terminal_diagnostic_uses_listener_correlation
```

### Transaction/side-effect correlation

```text
transaction_create_event_uses_request_correlation
transaction_update_event_uses_supplied_correlation
transaction_side_effect_uses_create_request_correlation
bank_expense_transaction_event_uses_bank_correlation
notification_expense_transaction_event_uses_listener_correlation
```

### Metadata

```text
package_hash_plain_text_value_is_redacted
raw_text_hash_is_redacted
all_hash_like_safe_keys_validate_hash_values
unknown_hash_key_is_redacted
```

### Diagnostics repository

```text
trace_by_correlation_includes_success_journal_events
trace_by_correlation_includes_failure_journal_events
recent_failures_includes_safe_sink_and_restore_journal_failures
```

---

## Test quality rules

```text
1. Prefer real production class with temp folder/fake dependencies.
2. Avoid tests that only assert an API exists.
3. Avoid hand-built helper JSON if RestoreJournal can be used directly.
4. Every high-priority issue must have a failing-before/fixed-after test.
5. Add fake DAOs/sinks that throw to verify best-effort behavior.
```

## Acceptance criteria

```text
The test suite would fail if:
- RestoreDiagnosticsSink stops appending to RestoreJournal.
- RestoreJournal append loses previous events.
- reset/import use Room operation handle after DB replacement.
- safe operation handle emits multiple terminal events.
- notification parse/error diagnostics lose listener correlation.
- transaction side effects lose request correlation.
- hash-looking metadata keys accept raw values.
```

---

# Final recommended execution order

```text
PR 1  DB replacement safety for reset/import
PR 2  Operation handle terminal/reason correctness
PR 3  Notification + transaction correlation completion
PR 4  Restore journal privacy split + importer robustness
PR 5  Metadata final edge hardening
PR 6  Recent failures and trace completeness
PR 7  Real behavioral regression tests
```

---

# Final definition of done

Durable diagnostics/lifecycle refactor is complete when:

```text
1. No Room operation-run handle is used after live DB delete/swap/replacement starts.
2. resetDatabase, importDatabase, and restoreCostBackup all write RESTART_REQUIRED before journal commit.
3. Safe operation handles emit exactly one terminal outcome.
4. Operation terminal events preserve reasonCode and safe summary.
5. Operation increment/stale-recovery diagnostic failures are durable through safe sink.
6. Notification parse/error/review/expense paths all use listener correlation.
7. Bank-created transaction lifecycle and side effects use bank operation correlation.
8. Transaction create/update/delete/bulk events are queryable by correlationId.
9. Restore recovery path data is never exposed as diagnostics/support/debug output.
10. Restore importer handles empty legacy journals and duplicate event IDs safely.
11. Hash-looking metadata keys cannot carry raw plain values.
12. DiagnosticsRepository trace/recent-failures aggregate Room + safe sink + worker + operation + restore journal sources.
13. Regression tests exercise real behavior and would catch the major bugs above.
```