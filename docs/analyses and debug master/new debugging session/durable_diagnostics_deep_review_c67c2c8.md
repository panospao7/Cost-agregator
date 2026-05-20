# Durable Diagnostics Deep Review — Commit `c67c2c8`

Commit reviewed: `c67c2c8236bbc43553cbb9c0c96ca339afe2515a`  
Previous reviewed commit: `f876b3bc3963b0a5f9557932b641d7979ea03060`

Mode: static source review from GitHub. I did **not** execute Gradle, Room migration tests, or Android tests.

---

## Executive verdict

This commit fixes several previously identified issues:

- `resetDatabase()` now uses `RestoreDiagnosticsSink` after the destructive DB-delete point.
- legacy `importDatabase()` now uses a restore diagnostics sink after `closeLiveDatabaseForFileSwap()`.
- `OperationRunEvent.eventId` exists and normal Room operation events now set it.
- operation counter failures now write a safe-sink diagnostic.
- notification parse/error diagnostics now use the listener correlation.
- transaction create side effects now receive the create correlation.
- `packageHash` moved into hash-key validation.
- `DiagnosticsRepository` now aggregates more failure sources.
- importer handles zero-event legacy journals and duplicate event IDs better.

However, some important bugs remain. The most serious one is new/visible in this commit:

> `SafeSinkOperationRunHandle.terminalOnce()` sets `_isTerminal = true` and then calls `event(..., isTerminal = true)`. But `event()` rejects terminal events when `_isTerminal` is already true. Therefore `success()`, `cancelled()`, `failedFinal()`, `failedRetryable()`, and `partialSuccess()` on the safe handle can emit **no terminal diagnostic at all**.

This is an actual durable-diagnostics bug for restore/maintenance-safe operation runs.

---

# 1. Resolved or mostly resolved

## 1.1 DB replacement safety for reset/import

Status: **mostly resolved**

Good:

- `resetDatabase()` creates `RestoreDiagnosticsSink`.
- `resetDatabase()` calls `markLiveDbSwapStarted()` before deleting DB files.
- reset success emits `LIVE_DB_DELETED` and `RESTART_REQUIRED` before `commitJournal()`.
- legacy import creates `RestoreDiagnosticsSink` after `closeLiveDatabaseForFileSwap()` and marks Room events disabled.
- legacy import success emits `RESTART_REQUIRED` before `commitJournal()`.
- direct `run.success()` and `run.failedFinal()` after swap/delete were removed from these paths.

Remaining:

- reset journal history starts late; `MAINTENANCE_ENTERED` and `SAFETY_BACKUP_CREATED` are emitted before `beginJournal()`, so they are not in the restore journal.
- legacy import post-swap failure still does not emit a terminal restore event through `importEvents.event(...)` before/after `failJournal()`.

Files:

```text
DatabaseBackupRepositoryImpl.kt
RestoreDiagnosticsSink.kt
RestoreJournal.kt
```

---

## 1.2 Notification correlation

Status: **mostly resolved**

Good:

- `NotificationProcessingPipeline.process()` creates `cid` outside `try`.
- exception path uses the same `cid`.
- parse diagnostic uses the passed `correlationId`.
- repository accepts and forwards `correlationId`.
- auto-accept request path can carry the listener correlation.

Remaining:

- batch processing still generates independent/default correlations.
- pending-review lifecycle is still only represented via pipeline diagnostic, not a dedicated lifecycle table.
- raw/pending review rows themselves are not queryable by correlation unless you use diagnostic events.

Files:

```text
NotificationRepository.kt
NotificationProcessingPipeline.kt
```

---

## 1.3 Transaction create correlation and create side effects

Status: **partially resolved**

Good:

- `CreateExpenseRequest.correlationId` is used in create attempt / validation failure / created / conflict / duplicate events.
- `dispatchPostCreationSideEffects()` accepts correlation and forwards it to `TransactionSideEffectDispatcher.dispatchOnCreated()`.
- bank-created and notification-created expenses should now pass correlation into the create flow.

Remaining:

- many update/delete/bulk paths still do not accept or persist correlation.
- several update side-effect calls still call `dispatchOnUpdated(expenseId, source)` without passing correlation.
- one `deleteExpense(expense: Expense, ...)` overload still writes `DELETED` without correlation.

Files:

```text
TransactionLifecycleCoordinator.kt
TransactionSideEffectDispatcher.kt
TransactionEvent.kt
```

---

## 1.4 Metadata hash hardening

Status: **mostly resolved**

Good:

- `packageHash` is now in `SAFE_HASH_KEYS`.
- any unknown key ending with `hash` is treated as dangerous.
- hash slots require a hex-like value.
- unknown object `toString()` goes through `sanitizeStringValue()`.

Remaining edge:

- `putHashed(key, value)` does not validate that `key` is an approved hash key. It can still produce an unknown key like `rawTextHash`; later writer-side sanitization should redact it, but the builder itself stores it.

Files:

```text
EventMetadataSanitizer.kt
SafeEventMetadata.kt
```

---

## 1.5 DiagnosticsRepository failure aggregation

Status: **improved**

Good:

- trace includes restore journal events from active/success/failure journals.
- recent failures include pipeline, operation event, worker, safe sink, and restore journal sources.

Remaining:

- safe-sink recent failures are filtered only by outcome, not severity. A safe-sink record with severity `ERROR` but outcome not in the hardcoded failure set can be missed.
- debug models still return message fields directly from DAOs/safe sink; they rely on upstream sanitization.

File:

```text
DiagnosticsRepository.kt
```

---

# 2. Critical / high-priority remaining issues

---

## DDL-C67-01 — SafeSinkOperationRunHandle terminal methods emit no terminal event

Severity: **Critical**  
Type: **actual durable diagnostics bug**  
Area: operation runs / maintenance-safe fallback

Current safe-handle logic:

```kotlin
private suspend fun terminalOnce(...) {
    if (!_isTerminal.compareAndSet(false, true)) return
    event(..., isTerminal = true)
}
```

But `event(..., isTerminal = true)` does:

```kotlin
if (isTerminal && !_isTerminal.compareAndSet(false, true)) return
```

Because `terminalOnce()` already set `_isTerminal = true`, the second compare-and-set fails and `event()` returns before writing anything.

Affected methods:

```text
success()
partialSuccess()
failedFinal()
failedRetryable()
cancelled()
```

Impact:

- safe-sink operation runs can have `STARTED` but no terminal event.
- maintenance/restore-blocked operation runs can disappear at terminal stage.
- bank sync in restore-blocked mode may emit only `WRITE_BARRIER` and never a proper `CANCELLED`.
- restore/backup operations that fall back to safe handle may not satisfy `STARTED -> terminal`.

This also means the added tests that use `TrackingHandle` should fail if executed, because the test double mirrors the same double-CAS bug.

### Fix

Do not call `event()` from `terminalOnce()` after setting terminal state. Split emission into a lower-level method.

Recommended:

```kotlin
override suspend fun event(..., isTerminal: Boolean) {
    if (isTerminal && !_isTerminal.compareAndSet(false, true)) return
    emitSafeEvent(..., isTerminal = isTerminal)
}

private suspend fun terminalOnce(...) {
    if (!_isTerminal.compareAndSet(false, true)) return
    emitSafeEvent(..., isTerminal = true)
}
```

Where `emitSafeEvent()` does only:

```text
safeSink.recordDiagnosticEvent(...)
```

and does not touch `_isTerminal`.

### Tests

```text
safe_handle_cancelled_emits_terminal_cancelled_event
safe_handle_success_emits_terminal_success_event
safe_handle_failed_final_emits_terminal_failed_event
safe_handle_cancelled_then_success_has_exactly_one_terminal_event
safe_handle_terminal_once_does_not_double_compare_and_skip
```

---

## DDL-C67-02 — Bank blocked sync terminal semantics are still inconsistent

Severity: **High**  
Type: **diagnostic correctness bug**  
Pipeline: P10 Bank

Bank blocked sync currently does:

```kotlin
run.event("WRITE_BARRIER", BLOCKED, isTerminal = true)
run.cancelled(RESTORE_BLOCKED)
```

Consequences:

### Room operation handle

- `WRITE_BARRIER` inserts a terminal `BLOCKED` event.
- `run.cancelled(...)` then finalizes the run and inserts another terminal `CANCELLED` event.
- Result: two terminal operation events.

### Safe operation handle

- direct `WRITE_BARRIER` terminal event marks terminal.
- `cancelled()` is skipped.
- Result: terminal outcome is `BLOCKED`, not `CANCELLED`, and final cancellation reason may not be represented as the operation terminal status.

### Fix

Make the intermediate barrier event non-terminal:

```kotlin
run.event(
    stage = "WRITE_BARRIER",
    outcome = EventOutcome.BLOCKED,
    reasonCode = DiagnosticReasonCode.RESTORE_BLOCKED,
    severity = EventSeverity.WARNING,
    exception = e,
    isTerminal = false
)

run.cancelled(DiagnosticReasonCode.RESTORE_BLOCKED.name)
```

Then both Room and safe handle produce one final terminal event:

```text
CANCELLED / RESTORE_BLOCKED
```

### Tests

```text
bank_restore_blocked_room_handle_has_one_terminal_event
bank_restore_blocked_safe_handle_has_one_terminal_event
bank_restore_blocked_terminal_event_is_cancelled_restore_blocked
```

---

## DDL-C67-03 — Room operation terminal events still lose reasonCode/summary

Severity: **Medium/High**  
Type: **support/debug gap**  
Area: operation runs

Room `finalizeNonCancellable()` receives:

```text
status
summary
error
```

but terminal `OperationRunEvent` is inserted via:

```kotlin
event(stage = status, outcome = statusToOutcome(status), isTerminal = true)
```

It does not pass:

```text
reasonCode
statusReason
cancellationReason
summary
safe error summary
```

Impact:

- `operation_runs.errorSummary` may contain the reason.
- `operation_run_events.reasonCode` is null.
- recent-failure summaries based on operation events lose key reason information.
- `cancelled(RESTORE_BLOCKED)` is not queryable as reason `RESTORE_BLOCKED`.

### Fix

Update `finalizeNonCancellable()`:

```kotlin
val parsedReason = parseReasonCode(summary)
event(
    stage = status,
    outcome = statusToOutcome(status),
    severity = ...,
    reasonCode = parsedReason,
    metadata = SafeEventMetadata.builder()
        .put("statusReason", summary)
        .put("errorSummary", sanitizer.sanitizeExceptionMessage(error?.message))
        .build(),
    exception = error,
    isTerminal = true
)
```

For `cancelled(reason)`:

```text
reasonCode = DiagnosticReasonCode.valueOf(reason) when possible
```

### Tests

```text
room_operation_cancelled_terminal_event_has_restore_blocked_reason
room_operation_failed_final_event_has_safe_summary_metadata
room_operation_partial_success_event_has_summary_metadata
```

---

## DDL-C67-04 — Restore/reset journal history is still incomplete

Severity: **Medium/High**  
Type: **support/debug gap**  
Pipeline: P7 reset/restore

In `resetDatabase()`:

```text
resetEvents.event("MAINTENANCE_ENTERED")
resetEvents.event("SAFETY_BACKUP_CREATED")
beginJournal(...)
```

Because `RestoreDiagnosticsSink.event()` appends to the journal only if a journal file exists, these two stages are not written to the restore journal.

They may go to Room/safe sink before destructive point, but after reset deletes the DB, the Room operation trail may not survive. The success journal likely contains only later events such as:

```text
LIVE_DB_DELETED
RESTART_REQUIRED
```

Impact:

- support cannot see complete reset phase history from the preserved journal.
- reset success journal lacks key pre-delete stages.

### Fix

Create the journal before `MAINTENANCE_ENTERED` or at least before first `resetEvents.event(...)`.

Suggested reset flow:

```kotlin
var journalEntry = restoreJournal.beginJournal(
    sourceBackupPath = "",
    stagedDbPath = "",
    liveDbPath = liveDbFile.absolutePath
)

resetEvents.event("RESET_STARTED", ATTEMPTED)
maintenanceOperationRunner.enterAndDrain(...)
resetEvents.event("MAINTENANCE_ENTERED", COMPLETED)
...
resetEvents.event("SAFETY_BACKUP_CREATED", COMPLETED)
...
resetEvents.markLiveDbSwapStarted()
```

### Tests

```text
reset_success_journal_contains_reset_started
reset_success_journal_contains_maintenance_entered
reset_success_journal_contains_safety_backup_created
reset_success_journal_contains_live_db_deleted_and_restart_required
```

---

## DDL-C67-05 — Legacy import post-swap failure lacks terminal journal event

Severity: **High**  
Type: **actual support/debug bug**  
Pipeline: P7 legacy import

In legacy `importDatabase()` post-swap catch, code now avoids `run.failedFinal(...)`, which is good. But it only calls:

```text
restoreJournal.failJournal(...)
```

It does not emit a terminal event through `importEvents.event(...)` such as:

```text
LEGACY_IMPORT_FAILED_AFTER_SWAP / FAILED_FINAL / CRITICAL
ROLLBACK_COMPLETED or ROLLBACK_FAILED
```

Impact:

- failure journal may have state/error but no append-only terminal event.
- recent-failure aggregation from restore journal events can miss the failure.
- support trace cannot see rollback outcome as a stage event.

### Fix

In post-swap failure path:

```kotlin
importEvents.event(
    stage = "LEGACY_IMPORT_FAILED_AFTER_SWAP",
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.CRITICAL,
    reasonCode = DiagnosticReasonCode.UNKNOWN_ERROR,
    exception = importError,
    isTerminal = true
)
```

If rollback succeeds:

```kotlin
importEvents.event("ROLLBACK_COMPLETED", COMPLETED, severity = WARNING)
```

If rollback fails:

```kotlin
importEvents.event("ROLLBACK_FAILED", FAILED_FINAL, severity = CRITICAL, isTerminal = true)
```

Ordering detail:

- If `failJournal()` renames the active file first, `RestoreDiagnosticsSink` will append to failure journal.
- Prefer emitting the terminal event before `failJournal()` if active journal still exists, then `failJournal()` preserves it.

### Tests

```text
legacy_import_post_swap_failure_writes_terminal_failed_event
legacy_import_rollback_success_writes_rollback_completed_event
legacy_import_rollback_failure_writes_rollback_failed_critical_event
recent_failures_includes_legacy_import_post_swap_failure
```

---

## DDL-C67-06 — Restore journal still stores full paths in same JSON file

Severity: **High privacy risk**  
Type: **privacy architecture gap**

`RestoreJournal.JournalEntry.toJson()` still stores internal fields:

```text
_sourceBackupPath
_stagedDbPath
_safetyBackupPath
_liveDbPath
```

`toDiagnosticsJson()` strips them, but the durable journal file itself still contains them.

Impact:

- if raw journal is attached to support/debug export, full local paths leak.
- future code can accidentally expose raw journal instead of diagnostics projection.
- privacy safety depends on caller discipline, not type separation.

Current design is improved but not final. It is acceptable as an interim bridge only if every debug/export path is proven to call `toDiagnosticsJson()` or event-only APIs.

### Fix

Split into two files/models:

```text
restore_recovery_journal.json
  pathful, internal-only, used for crash recovery

restore_diagnostics_journal.json / last_success / last_failure
  privacy-safe, no _...Path fields
```

If full split is deferred:

- add static guard or support-export test forbidding `_sourceBackupPath` etc.
- ensure `DiagnosticsRepository` never exposes `JournalEntry.toJson()`.

### Tests

```text
restore_diagnostics_journal_file_has_no_full_paths
restore_recovery_journal_roundtrips_full_paths
diagnostics_repository_never_returns_internal_path_fields
support_export_never_includes_restore_internal_paths
```

---

# 3. Medium-priority issues

---

## DDL-C67-07 — `RestoreJournal.renameTo()` results are unchecked

Severity: **Medium**  
Type: **durability/reliability bug**

Several journal writes use:

```text
tmpFile.renameTo(targetFile)
journalFile.renameTo(successFile)
journalFile.renameTo(failureFile)
```

without checking the Boolean result.

Impact:

- if rename fails, code can log success while the journal was not actually preserved.
- success journal may be missing after restore/reset/import.
- append event may write temp file but not replace target.

### Fix

Use atomic move where possible:

```kotlin
Files.move(tmp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
```

or at minimum:

```kotlin
if (!tmpFile.renameTo(targetFile)) {
    throw IOException("Failed to rename journal temp file")
}
```

### Tests

```text
commit_journal_reports_failure_when_success_rename_fails
append_event_reports_failure_when_tmp_rename_fails
fail_journal_reports_failure_when_failure_rename_fails
```

---

## DDL-C67-08 — Stale recovery event insert failure is Timber-only

Severity: **Medium**  
Type: **diagnostic durability gap**

`recoverStaleRunningOperationRuns()` wraps stale event insert in `runCatching`, but on failure only logs:

```text
Timber.w(...)
```

It does not write safe-sink diagnostic.

Impact:

- startup recovery status may be changed to `STALE_ABORTED`, but the event insert failure itself is invisible except logs.
- violates the same rule already applied to normal `increment()` failures.

### Fix

On stale event insert failure:

```kotlin
safeSink.recordDiagnosticEvent(
    DiagnosticEvent(
        pipeline = pipelineForOperation(run.operationType),
        stage = "stale_recovery_event_write_failed",
        outcome = SIDE_EFFECT_FAILED,
        reasonCode = SIDE_EFFECT_EXCEPTION,
        correlationId = run.correlationId,
        exception = error
    ),
    mode = restoreMaintenanceMode.currentMode()
)
```

Also set `eventId` on stale recovery event.

### Tests

```text
stale_recovery_event_insert_failure_records_safe_sink_diagnostic
stale_recovery_event_has_event_id
```

---

## DDL-C67-09 — `SafeSinkOperationRunHandle.increment()` is a no-op

Severity: **Low/Medium**  
Type: **safe-mode operation count gap**

Safe operation handles ignore increments:

```text
override suspend fun increment(...) = Unit
```

Impact:

- safe-sink operation traces during restore/maintenance lose row/count progress.
- bank/import/export safe-mode summaries may lack processed/succeeded/failed counts.

### Fix

Maintain in-memory counters in `SafeSinkOperationRunHandle`.

```kotlin
private val processed = AtomicInteger()
...
override suspend fun increment(...) {
    counters.add(...)
    safeSink.recordDiagnosticEvent(... stage="operation_increment" metadata counts ...)
}
```

Terminal safe event should include final counters.

### Tests

```text
safe_handle_increment_accumulates_counts
safe_handle_terminal_event_includes_counts
```

---

## DDL-C67-10 — Transaction update/delete/bulk correlation remains partial

Severity: **Medium**  
Type: **traceability gap**

The main `updateExpense(...)` has optional correlation and writes it to `UPDATED`.

But many granular methods still do not:

```text
updateCategory
updateLocation
updateBusinessFlags
updateMerchant
updateType
updateTransferDetails
updateTypeAndTransferDetails
updateOwnership
bulkUpdateCategory
bulkUpdateMerchant
deleteExpense(expense: Expense)
```

Most side-effect dispatches also omit correlation:

```text
dispatchOnUpdated(expenseId, source)
dispatchOnDeleted(expenseId, source)
dispatchOnBulkUpdated(source, affectedCount)
```

Impact:

- a correlated flow that later updates/links/cleans up an expense loses traceability.
- side-effect diagnostics after update/delete/bulk get fresh correlations.

### Fix

Add optional `correlationId` and `causationId` to all mutation methods and pass to:

```text
TransactionEvent.correlationId
TransactionEvent.causationId
sideEffectDispatcher.dispatchOnUpdated(...)
sideEffectDispatcher.dispatchOnDeleted(...)
sideEffectDispatcher.dispatchOnBulkUpdated(...)
```

### Tests

```text
update_category_uses_supplied_correlation
update_merchant_uses_supplied_correlation
update_type_uses_supplied_correlation
delete_expense_object_overload_uses_supplied_correlation
bulk_update_category_uses_supplied_correlation
bulk_update_side_effect_uses_supplied_correlation
```

---

## DDL-C67-11 — `putHashed()` can store unapproved hash-like keys before final writer sanitization

Severity: **Low/Medium privacy hardening**  
Type: **metadata builder gap**

`SafeEventMetadata.Builder.putHashed(key, value)` stores:

```text
map[key] = sha256Prefix(value)
```

without checking that `key` is approved.

If caller uses:

```kotlin
putHashed("rawTextHash", rawText)
```

the builder stores the key. Later writer/sanitizer should redact it because the key is not in `SAFE_HASH_KEYS`, but the object called `SafeEventMetadata` is not fully safe by construction.

### Fix

In `putHashed()`:

```kotlin
val canonical = sanitizer.canonicalizeKey(key)
if (canonical !in SAFE_HASH_KEYS) {
    map[key] = REDACTED
    return this
}
map[key] = sha256Prefix(value)
```

Because `SAFE_HASH_KEYS` is private now, expose:

```kotlin
fun isApprovedHashKey(key: String): Boolean
```

### Tests

```text
put_hashed_raw_text_hash_is_redacted
put_hashed_unknown_hash_key_is_redacted
put_hashed_source_id_hash_is_allowed
```

---

## DDL-C67-12 — DiagnosticsRepository safe-sink failure filtering misses severity-only failures

Severity: **Low/Medium**  
Type: **support tooling gap**

Safe-sink recent failures currently filter on outcome set only:

```text
FAILED_RETRYABLE
FAILED_FINAL
BLOCKED
DROPPED
CANCELLED
SIDE_EFFECT_FAILED
```

If a safe-sink record has:

```text
severity = ERROR
outcome = COMPLETED or null
```

it may be missed.

### Fix

Filter safe-sink records by:

```text
outcome in failureOutcomes
OR severity in WARNING/ERROR/CRITICAL
```

### Tests

```text
recent_failures_includes_safe_sink_error_severity_even_if_outcome_completed
recent_failures_includes_safe_sink_warning
```

---

# 4. Test quality findings

## DDL-C67-13 — Regression tests still mirror bugs instead of testing production classes

Severity: **High regression risk**

`DDL512RegressionTest` still contains many helper-based tests:

```text
appendEventToFile(...)
TrackingHandle
manual DiagnosticEvent emission
reflection checks
```

The `TrackingHandle` specifically mirrors the `SafeSinkOperationRunHandle` double-CAS bug. It calls `terminalOnce()`, sets terminal, then calls `event(... isTerminal=true)`, which skips emission. Therefore tests like:

```text
cancelled then success produces one terminal event
cancelled with RESTORE_BLOCKED reason code is preserved
```

should fail if actually run. If they do not fail, then the test double and assertions are not exercising the real path.

### Fix

Replace helper tests with real production class tests:

```text
SafeSinkOperationRunHandleTest
RestoreJournalTest
RestoreDiagnosticsSinkTest
NotificationProcessingPipelineTest
TransactionLifecycleCoordinatorCorrelationTest
DatabaseBackupRepositoryResetImportTest
```

Use fake collaborators, but real classes.

### Required tests

```text
real_safe_sink_operation_handle_cancelled_emits_terminal_event
real_safe_sink_operation_handle_failed_final_emits_terminal_event
real_restore_diagnostics_sink_appends_to_restore_journal
real_restore_journal_transition_preserves_events
real_reset_database_success_journal_contains_full_stage_history
real_legacy_import_failure_writes_terminal_journal_event
real_notification_pipeline_parse_uses_correlation
real_transaction_update_delete_bulk_use_correlation
```

---

# 5. Acceptance matrix after `c67c2c8`

| Criterion | Status | Notes |
|---|---:|---|
| resetDatabase avoids Room handle after DB delete | Mostly | Good, but journal starts late |
| legacy import avoids Room handle after swap | Mostly | Success path improved; failure lacks terminal event |
| restore journal append-only event history | Mostly | Better; rename failures unchecked |
| RESTART_REQUIRED before commit | Mostly | costbackup/reset/import success paths improved |
| safe operation handle terminal-once | **Broken** | terminal methods emit no event due double CAS |
| operation terminal reason codes | Partial | safe cancelled reason preserved; Room terminal events still lose reason |
| operation eventId | Partial | normal operation events set eventId; stale recovery event does not |
| operation increment failure durable | Mostly | Room increment writes safe-sink diagnostic; safe handle increment no-op |
| notification parse/error correlation | Mostly | fixed for single notification path |
| notification batch correlation | Partial | batch still independent |
| transaction create correlation | Mostly | create path good |
| transaction update/delete/bulk correlation | Partial | many methods still missing |
| transaction side-effect correlation | Partial | create fixed; update/delete/bulk not complete |
| restore diagnostics path privacy | Partial | projections safe, raw journal still pathful |
| recent failures aggregation | Better | misses safe-sink severity-only failures |
| tests catch behavior | Not yet | many tests are helper/reflection based; one test double mirrors bug |

---

# 6. Recommended next PR order

## PR 1 — Safe operation handle terminal hotfix

Fix immediately:

```text
DDL-C67-01
DDL-C67-02
```

Must ensure:

```text
success/cancelled/failedFinal/failedRetryable/partialSuccess emit exactly one terminal event.
bank blocked sync has one terminal CANCELLED/RESTORE_BLOCKED event.
```

## PR 2 — Operation terminal reason + stale recovery durability

Fix:

```text
DDL-C67-03
DDL-C67-08
DDL-C67-09
```

Must ensure:

```text
Room terminal operation events preserve reasonCode/summary.
Stale recovery event insert failures go to safe sink.
Safe handle increments are counted or at least terminal summaries include counters.
```

## PR 3 — Reset/import journal completeness

Fix:

```text
DDL-C67-04
DDL-C67-05
DDL-C67-07
```

Must ensure:

```text
reset journal contains full stage trail.
legacy import post-swap failure writes terminal event and rollback outcome.
journal file renames are checked/atomic.
```

## PR 4 — Transaction mutation correlation completion

Fix:

```text
DDL-C67-10
```

Must ensure:

```text
all transaction update/delete/bulk lifecycle events and side effects can share caller correlation.
```

## PR 5 — Restore journal privacy split

Fix:

```text
DDL-C67-06
```

Must ensure:

```text
pathful recovery data is separated from privacy-safe diagnostic data.
```

## PR 6 — Metadata/debug/test hardening

Fix:

```text
DDL-C67-11
DDL-C67-12
DDL-C67-13
```

Must ensure:

```text
putHashed rejects unknown hash keys.
recent failures include severity-only safe-sink issues.
regression tests exercise real classes.
```

---

# 7. Highest-priority bug list

Fix these first:

```text
1. SafeSinkOperationRunHandle terminalOnce emits no terminal event.
2. Bank blocked sync uses WRITE_BARRIER as terminal then cancelled.
3. Room operation terminal events lose reason codes.
4. resetDatabase journal starts after MAINTENANCE_ENTERED / SAFETY_BACKUP_CREATED.
5. legacy import post-swap failure lacks terminal journal event.
6. raw restore journal still contains full internal paths.
7. tests mirror the safe-handle bug instead of catching it.
```

---

# 8. Sources checked

Commit:

- https://github.com/panospao7/Cost-agregator/commit/c67c2c8236bbc43553cbb9c0c96ca339afe2515a

Key files:

- `DatabaseBackupRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `RestoreDiagnosticsSink.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt

- `RestoreJournal.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `CompositeOperationRunRecorder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt

- `OperationRunRecorder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt

- `NotificationProcessingPipeline.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `NotificationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt

- `TransactionLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionSideEffectDispatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt

- `BankApiIntegration.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `EventMetadataSanitizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt

- `SafeEventMetadata.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt

- `DiagnosticsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/domain/debug/DiagnosticsRepository.kt

- `AppDatabase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- `OperationRunEvent.kt`  
  https://github.com/panospao7/Cost-agregator/blob/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/data/database/entity/OperationRunEvent.kt

- `OperationRunEventDao.kt`  
  https://github.com/panospao7/Cost-agregator/blob/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/main/java/com/yourname/expensetracker/data/database/dao/OperationRunEventDao.kt

- `DDL512RegressionTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/c67c2c8236bbc43553cbb9c0c96ca339afe2515a/app/src/test/java/com/yourname/expensetracker/diagnostics/DDL512RegressionTest.kt