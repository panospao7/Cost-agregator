# Durable Diagnostics Deep Review — Commit `f876b3b`

Commit reviewed: `f876b3bc3963b0a5f9557932b641d7979ea03060`  
Previous reviewed commit: `a8e1c94ee41cc05d19904921e1ba0a280d7907ee`

Mode: static source review from GitHub. I did **not** execute Gradle, Room migration tests, or Android tests.

---

## Executive verdict

This commit fixes a meaningful part of the previous DDL-A8 issues:

- `RestoreDiagnosticsSink` now receives `RestoreJournal` and appends events.
- `RestoreJournal.appendEvent()` now works against raw JSON and preserves existing events.
- `RESTART_REQUIRED` in `.costbackup` restore is emitted before `commitJournal()`.
- Many `.costbackup` pre-swap restore failures now emit terminal events and finalize the operation run.
- `OperationRunEvent` has an `eventId` column/index in entity/schema.
- Notification listener correlation is passed into `NotificationRepository` and `NotificationProcessingPipeline`.
- `TransactionEvent` now has `correlationId` / `causationId` columns.
- Bank sync starts the operation run before write-barrier checks.
- Metadata JSON sanitization now validates hash-key values.
- Recent-failure debug queries now aggregate more sources.

However, the implementation is **not fully complete**. The highest-risk remaining area is still **backup/restore/reset DB safety**, especially flows that replace/delete the live DB while still writing normal Room operation-run events.

Highest priority remaining issues:

1. `resetDatabase()` still calls `run.event()` / `run.success()` after deleting the live DB.
2. legacy `importDatabase()` still calls `run.event()` / `run.success()` / `run.failedFinal()` after DB swap.
3. restore diagnostics still store internal full paths in the same JSON file as diagnostics.
4. `SafeSinkOperationRunHandle` can still produce multiple terminal events because direct `event(..., isTerminal = true)` does not mark the handle terminal.
5. notification pipeline still has correlation holes: parse diagnostics and exception diagnostics can use unrelated correlation IDs.
6. transaction create events are correlated, but side effects/update/delete/bulk lifecycle paths are not.
7. behavioral tests are still too synthetic; several tests simulate JSON manually instead of exercising `RestoreJournal` / `RestoreDiagnosticsSink`.

---

# 1. What appears resolved or mostly resolved

## 1.1 RestoreDiagnosticsSink now appends to RestoreJournal

Status: **mostly resolved**

Previously, `RestoreDiagnosticsSink` did not write to the journal. It now has `RestoreJournal` in the constructor and calls either:

```text
restoreJournal.appendEvent(...)
restoreJournal.appendEventToFailureJournal(...)
```

It also serializes sanitized metadata into `metadataJson`.

Remaining caveat:

- It chooses failure journal whenever active journal is absent. That is acceptable after `failJournal()`, but dangerous as a general fallback after success commit/deletion. Keep use tightly scoped.

Files:

```text
data/backup/RestoreDiagnosticsSink.kt
data/backup/RestoreJournal.kt
```

---

## 1.2 RestoreJournal append behavior is improved

Status: **mostly resolved**

Good:

- `appendEventToFile()` reads the target JSON directly.
- Existing `events` are parsed from the raw JSON.
- New event is appended.
- `writeJournal()` preserves existing `events`.

This fixes the previous “only last event survives” bug.

Remaining caveats:

- `renameTo()` result is not checked.
- no file lock / mutex protects concurrent journal writes.
- this is probably acceptable if restore event emission is sequential, but tests should cover preservation through `transitionTo`, `failJournal`, and `commitJournal`.

---

## 1.3 `.costbackup` restore `RESTART_REQUIRED` ordering improved

Status: **mostly resolved**

In the main `.costbackup` restore path, `RESTART_REQUIRED` is now emitted before:

```text
transitionTo(COMPLETE)
commitJournal(...)
```

This is correct and should put the terminal restart-required event into the success journal.

---

## 1.4 Pre-swap `.costbackup` restore failure coverage improved

Status: **mostly resolved**

Now covered with terminal event + `finalizeRunFailed()` before `failJournal()`:

```text
bundle extraction / wrong password
empty manifest
staged quick verification failed
post-migration verification failed
staged migration failed
safety backup failed
safety backup path unavailable
```

Remaining:

- some post-swap failure paths are journal/safe-sink only, which is correct, but should be tested with fake operation handle assertions.

---

## 1.5 Notification correlation is partially propagated

Status: **partially resolved**

Good:

- `NotificationCaptureService` passes `correlationId` into `repository.processAndSave(...)`.
- `NotificationRepository` passes it to `NotificationProcessingPipeline`.
- pipeline terminal diagnostic events use it in the success path.
- auto-accepted `CreateExpenseRequest` can carry it.

Remaining holes are listed below.

---

## 1.6 Transaction create lifecycle correlation added

Status: **partially resolved**

Good:

- `TransactionEvent` now has `correlationId` / `causationId`.
- migration `128 -> 129` adds both columns and an index.
- create attempt, validation failure, created, insert conflict, and duplicate-skip events use request correlation.

Remaining:

- update/delete/bulk events do not accept/use correlation.
- side-effect dispatcher calls still do not receive correlation from the transaction create request.

---

## 1.7 Metadata hash-value validation improved

Status: **mostly resolved**

Good:

- hash keys now require hex-like values.
- `sanitizeJsonObject()` uses `sanitizeValue(key, value)`, so prebuilt JSON cannot bypass hash-key validation.
- nested JSON/list sanitation is stronger.

Remaining caveat:

- some safe exact keys that are hash-like, e.g. `packageHash`, are not in `SAFE_HASH_KEYS`, so plain text could pass under that exact key.

---

# 2. Critical / high-priority remaining issues

---

## DDL-F876-01 — `resetDatabase()` still writes Room operation events after deleting live DB

Severity: **Critical**  
Type: **actual user-impacting reset/restore safety bug**  
Pipeline: P7 Backup/restore/reset

In `resetDatabase()`, after entering maintenance and deleting the live DB files, the code does:

```text
restoreJournal.commitJournal(journalEntry)
run.event("RESTART_REQUIRED", COMPLETED, isTerminal = true)
run.success()
restoreMaintenanceMode.exit(forceRestartRequired = true)
```

This is unsafe for the same reason we fixed `.costbackup` restore:

```text
after close/delete/swap of live DB, the old Room operation handle must not be used
```

Impact:

- reset can succeed at the file level but operation-run finalization can write to a stale/closed/replaced DB.
- diagnostic finalization can fail or produce misleading state.
- `RESTART_REQUIRED` is emitted after `commitJournal()`, so it may not be inside the preserved journal either.
- successful reset trail may be incomplete.

Fix strategy:

Use `RestoreDiagnosticsSink` for reset too, or create a `ResetDiagnosticsSink`.

Required flow:

```text
beginJournal
restoreEvents.event(STARTED/MAINTENANCE_ENTERED/SAFETY_BACKUP_CREATED)
close/delete live DB
restoreEvents.markLiveDbSwapStarted()
restoreEvents.event(LIVE_DB_DELETED or RESET_APPLIED)
restoreEvents.event(RESTART_REQUIRED, COMPLETED, terminal=true)
restoreJournal.transitionTo(COMPLETE)
restoreJournal.commitJournal(...)
restoreMaintenanceMode.exit(forceRestartRequired=true)
```

After `markLiveDbSwapStarted()`:

```text
no run.event()
no run.success()
no run.failedFinal()
```

Required tests:

```text
reset_database_after_live_db_delete_does_not_call_room_run_event
reset_database_restart_required_written_before_commit_journal
reset_database_success_journal_contains_terminal_event
reset_database_operation_event_failure_does_not_fail_successful_reset
```

---

## DDL-F876-02 — legacy `importDatabase()` still writes Room operation events after DB swap

Severity: **High**  
Type: **actual bug if legacy import is used**  
Pipeline: P7 legacy import

`importDatabase()` is marked debug/deprecated-ish, but it is still executable. It now has an operation run, but after `replaceDatabaseFiles(...)` and verification it does:

```text
restoreJournal.commitJournal(journalEntry)
run.event("RESTART_REQUIRED", COMPLETED, isTerminal = true)
run.success()
restoreMaintenanceMode.exit(forceRestartRequired = true)
```

In the failure path after `destinationFilesMutated == true`, it also calls:

```text
run.failedFinal(...)
```

after the live DB was swapped.

Impact:

- same stale/closed Room handle problem as restore/reset.
- legacy import success/failure diagnostics can corrupt or misrepresent the run.
- success journal likely misses `RESTART_REQUIRED`.

Fix strategy:

Apply the same `RestoreDiagnosticsSink` pattern used by `.costbackup` restore:

```text
before swap: operation run + journal + safe sink
after swap: journal + safe sink only
```

Required tests:

```text
legacy_import_after_swap_does_not_call_room_operation_run
legacy_import_restart_required_in_success_journal
legacy_import_after_swap_failure_uses_journal_not_room_run_failed_final
```

---

## DDL-F876-03 — restore journal still mixes recovery-only full paths with diagnostics

Severity: **High privacy risk / medium recovery risk**  
Type: **privacy architecture bug**

`RestoreJournal.JournalEntry.toJson()` still writes internal full paths:

```text
_sourceBackupPath
_stagedDbPath
_safetyBackupPath
_liveDbPath
```

The code has `toDiagnosticsJson()` that strips those fields, but the same durable JSON file still contains both recovery and diagnostic data.

Impact:

- if the journal file is ever attached to a support bundle, copied by debug tooling, or exposed in UI, full local paths leak.
- future code may accidentally read/export the raw file instead of `toDiagnosticsJson()`.
- the current design depends on discipline, not type/API separation.

Also, asset target recovery is weakened:

```text
toJson stores targetName only
fromJson reads targetName into targetPath
```

A basename may not be sufficient for crash recovery/cleanup.

Fix strategy:

Split files/models:

```text
restore_recovery_journal.json
  pathful, internal only, never exported

restore_diagnostics_journal.json / restore_journal_last_success.json / failure
  privacy-safe, no full paths
```

If split is deferred, enforce:

```text
DiagnosticsRepository never returns raw JournalEntry JSON
support export strips all "_..." fields
tests assert no /data/, /storage/, file://, C:\ in diagnostics outputs
```

Required tests:

```text
restore_diagnostics_journal_has_no_full_paths
restore_recovery_journal_roundtrips_full_paths
diagnostics_trace_never_exposes_internal_path_fields
support_export_never_includes_internal_path_fields
asset_restore_recovery_keeps_real_target_path_in_recovery_journal
```

---

## DDL-F876-04 — SafeSinkOperationRunHandle can still emit multiple terminal events

Severity: **Medium/High**  
Type: **diagnostic correctness bug**

`SafeSinkOperationRunHandle` protects terminal methods with `terminalOnce(...)`, but plain `event(..., isTerminal = true)` does **not** set `_isTerminal`.

Example in bank sync:

```text
run.event("WRITE_BARRIER", BLOCKED, isTerminal = true)
run.cancelled(RESTORE_BLOCKED)
```

Safe-sink result can be:

```text
WRITE_BARRIER terminal
CANCELLED terminal
```

Then `runOperation()` calls `success()`, which is blocked only because `cancelled()` set terminal. So success is avoided, but there are still two terminal events.

Room path can also have a terminal operation event plus a cancelled final event.

Fix options:

Option A:

```text
Do not mark intermediate WRITE_BARRIER as terminal.
Let cancelled()/failedFinal() be the only terminal event.
```

Option B:

```kotlin
override suspend fun event(..., isTerminal: Boolean) {
    if (isTerminal) {
        if (!_isTerminal.compareAndSet(false, true)) return
    }
    ...
}
```

Recommended: use both:

```text
operation-stage blocked event = non-terminal
operation final cancelled/failed = terminal
```

Also preserve the cancellation reason:

```kotlin
cancelled(reason = RESTORE_BLOCKED)
```

should emit reasonCode `RESTORE_BLOCKED`, not always `CANCELLED_BY_SYSTEM`.

Required tests:

```text
safe_handle_direct_terminal_event_marks_handle_terminal
bank_sync_restore_blocked_safe_handle_has_one_terminal_event
bank_sync_restore_blocked_terminal_reason_is_restore_blocked
room_operation_blocked_then_cancelled_has_single_terminal_policy
```

---

## DDL-F876-05 — operation run final events lose reason codes

Severity: **Medium**  
Type: **debug/support gap**

`RoomOperationRunRecorder.Handle.finalizeNonCancellable(...)` inserts a terminal event using:

```text
stage = status
outcome = statusToOutcome(status)
```

but does not pass:

```text
reasonCode
summary
cancellation reason
status reason
```

Impact:

- `operation_runs.errorSummary` may have a reason, but `operation_run_events` does not.
- recent-failure summaries based on operation events can show missing `reasonCode`.
- `cancelled(RESTORE_BLOCKED)` is not queryable as `RESTORE_BLOCKED`.

Fix:

Change terminal methods to pass reason:

```kotlin
cancelled(reason) -> reasonCode = reason.toDiagnosticReasonOrNull()
failedFinal(reason, error) -> metadata.reasonSummary = safe reason
failedRetryable(...) -> reasonCode if known
partialSuccess(summary) -> metadata.summary
```

Required tests:

```text
operation_cancelled_terminal_event_has_reason_code
operation_failed_terminal_event_has_safe_summary
operation_partial_success_terminal_event_has_summary_metadata
```

---

## DDL-F876-06 — operation counter increment failures are not durable

Severity: **Medium**  
Type: **diagnostic durability gap**

`RoomOperationRunRecorder.Handle.increment()` is now best-effort in the sense that it does not throw, but it only logs:

```text
Timber.w(...)
```

It does not write a safe-sink diagnostic.

Impact:

- if DB counter updates fail during bank/import/export loops, the loss is invisible except logs.
- violates the “failed diagnostic side effect should itself be durable/safe” principle.

Fix:

On increment failure:

```text
safeSink.recordDiagnosticEvent(
  pipeline = operation pipeline,
  stage = operation_increment_failed,
  outcome = SIDE_EFFECT_FAILED,
  reasonCode = SIDE_EFFECT_EXCEPTION,
  correlationId = run.correlationId
)
```

Required tests:

```text
operation_increment_failure_records_safe_diagnostic
operation_increment_failure_does_not_fail_business_flow
```

---

# 3. Notification/correlation remaining issues

---

## DDL-F876-07 — notification parse diagnostic lacks listener correlation

Severity: **Medium**  
Type: **traceability gap**

In `NotificationProcessingPipeline.processInternal(...)`, the parse provenance event is emitted without:

```text
correlationId = correlationId
```

So the trace can look like:

```text
listener RECEIVED corr=A
pipeline parse COMPLETED corr=random/new
pipeline terminal CREATED corr=A
```

Fix:

```kotlin
DiagnosticEvent(
    ...
    correlationId = correlationId ?: existingBoundaryCorrelation
)
```

Required test:

```text
notification_parse_event_uses_listener_correlation
```

---

## DDL-F876-08 — notification pipeline exception path loses correlation

Severity: **Medium/High**  
Type: **actual support trace bug**

In `NotificationProcessingPipeline.process(...)`:

```text
try {
  val cid = correlationId ?: newId()
  processInternal(..., cid)
  writePipelineDiagnosticEvent(..., cid)
} catch {
  writePipelineDiagnosticEvent(errorOutcome, packageName)
}
```

The catch path does not pass `cid`, so processing errors get a new/random correlation ID.

Impact:

- repository failure path may have listener correlation.
- pipeline error diagnostic may not.
- support trace by listener correlation misses the internal pipeline failure.

Fix:

Move CID creation outside the try:

```kotlin
val cid = correlationId ?: CorrelationIds.newId()
return try {
   ...
} catch {
   writePipelineDiagnosticEvent(errorOutcome, packageName, correlationId = cid)
}
```

Required test:

```text
notification_pipeline_exception_uses_listener_correlation
```

---

## DDL-F876-09 — pending review lifecycle correlation is still weak

Severity: **Medium**  
Type: **architecture/support gap**

Notification success path now correlates:

```text
listener -> pipeline terminal diagnostic -> transaction create events
```

for auto-accepted expenses.

But for `NeedsReview`, the pending review row/event path does not appear to have a durable lifecycle event table or correlation column. The pipeline emits a terminal diagnostic with `entityType=PendingReview`, but the review lifecycle itself is not queryable by correlation unless using diagnostics only.

Fix options:

Short term:

```text
ensure pipeline diagnostic for review creation has correlationId and reviewId
```

Already appears true.

Longer term:

```text
add pending_review_lifecycle_events or use pipeline_diagnostic_events consistently for REVIEW_CREATED
```

Acceptance test:

```text
notification_success_review_created_has_correlated_terminal_diagnostic
```

---

# 4. Transaction lifecycle remaining issues

---

## DDL-F876-10 — only create events have correlation; update/delete/bulk still do not

Severity: **Medium**  
Type: **traceability gap**

`TransactionEvent` schema now supports correlation, but many transaction mutation APIs still write events without correlation:

```text
updateExpense
updateMerchant
updateType
bulkUpdateCategory
bulkUpdateMerchant
delete paths likely similar
```

For manual edits this may be okay, but for flows triggered by receipt/email/bank/review or side effects, correlation is lost after create.

Fix:

Add optional correlation to mutation methods:

```kotlin
updateExpense(..., correlationId: String? = null, causationId: String? = null)
deleteExpense(..., correlationId: String? = null)
bulkUpdate..., correlationId: String? = null
```

Use generated boundary correlation if absent.

Required tests:

```text
transaction_update_uses_supplied_correlation
transaction_delete_uses_supplied_correlation
bulk_update_uses_supplied_correlation
```

---

## DDL-F876-11 — transaction side effects still do not receive correlation

Severity: **Medium/High**  
Type: **traceability gap**

`dispatchPostCreationSideEffects(expenseId, source)` calls:

```text
sideEffectDispatcher.dispatchOnCreated(expenseId, source)
```

No correlation is passed.

Impact:

- bank/notification create events are correlated.
- side-effect events after create may still use fresh/random correlation or no original entity context.
- trace breaks at post-commit side effects.

Fix:

Change API:

```kotlin
dispatchPostCreationSideEffects(
    expenseId: Long,
    source: ExpenseSource,
    correlationId: String,
    causationId: String? = null
)
```

Then in create flow:

```kotlin
dispatchPostCreationSideEffects(insertedId, request.source, correlationId)
```

Required tests:

```text
transaction_create_side_effect_uses_request_correlation
bank_transaction_side_effect_uses_bank_sync_correlation
notification_auto_accept_side_effect_uses_listener_correlation
```

---

# 5. Restore/import/journal robustness issues

---

## DDL-F876-12 — RestoreJournalImporter can still skip journals with zero events forever

Severity: **Low/Medium**  
Type: **cleanup/support gap**

`RestoreJournalImporter.importLastSuccessJournalIfPresent()` does:

```text
val events = restoreJournal.getSuccessJournalEvents()
if (events.isEmpty()) return
```

If a success journal from an older build has no events, it is never marked imported. Startup may keep checking it.

Fix:

If no events but journal state is success/complete:

```text
insert operation_runs summary row or mark imported as legacy_empty_events
```

Required test:

```text
restore_import_marks_legacy_success_journal_without_events_as_imported_or_summary_imported
```

---

## DDL-F876-13 — RestoreJournalImporter idempotency can insert duplicate eventIds inside the same import pass

Severity: **Low/Medium**  
Type: **edge-case import bug**

The importer computes:

```text
existingEventIds = operationRunEventDao.getByRunId(runId).mapNotNull { it.eventId }.toSet()
```

before the loop.

If the journal itself contains duplicate event IDs, the second duplicate is not blocked because `existingEventIds` is not updated during the loop.

Fix:

Use a mutable set:

```kotlin
val importedIds = existingEventIds.toMutableSet()
for (event in events) {
    if (!importedIds.add(event.eventId)) continue
    insert(...)
}
```

Also consider unique index on `operation_run_events.eventId`.

Required test:

```text
restore_import_skips_duplicate_event_ids_within_same_journal
```

---

## DDL-F876-14 — OperationRunEvent eventId is nullable and not set for normal operation events

Severity: **Low/Medium**  
Type: **traceability/idempotency gap**

`OperationRunEvent` has `eventId`, but normal `RoomOperationRunRecorder.Handle.event()` does not populate it. It remains useful for restore-imported events only, but the general operation event stream still lacks stable event IDs.

Fix:

Generate event ID on every operation event:

```kotlin
eventId = CorrelationIds.newId()
```

Longer term, accept `eventId`/`causationId` in `OperationRunHandle.event(...)`.

Required test:

```text
operation_run_event_has_event_id_for_normal_room_events
```

---

# 6. Metadata/privacy remaining issue

---

## DDL-F876-15 — some hash-like safe keys can still accept plain values

Severity: **Low/Medium privacy hardening**  
Type: **privacy edge case**

`SAFE_EXACT_KEYS` contains some hash-looking keys, e.g.:

```text
packagehash
```

but `SAFE_HASH_KEYS` does not include all of them.

So:

```kotlin
sanitizeValue("packageHash", "com.private.bank")
```

can pass as a normal string if it does not match token/path/account patterns.

Fix:

Make every `*hash` exact safe key also part of `SAFE_HASH_KEYS`, or remove hash-looking keys from `SAFE_EXACT_KEYS`.

Recommended rule:

```text
Any canonical key ending with "hash" must either:
  - be in SAFE_HASH_KEYS and have valid hash value, or
  - be redacted.
```

Required tests:

```text
package_hash_plain_text_value_is_redacted
package_hash_hex_value_is_allowed
all_safe_exact_hash_keys_are_in_safe_hash_keys
```

---

# 7. Test quality issues

---

## DDL-F876-16 — DDL512RegressionTest still has too many simulated/structural tests

Severity: **High regression risk**  
Type: **test quality gap**

`DDL512RegressionTest` is useful but still often tests hand-built JSON helper behavior instead of real production classes.

Examples:

```text
appendEventToFile(...) test helper, not RestoreJournal.appendEvent(...)
manual event ordering, not NotificationCaptureService path
reflection check for entity fields/indexes, not migration/runtime behavior
```

These tests would not catch:

```text
RestoreDiagnosticsSink not calling RestoreJournal
RestoreJournal.writeJournal erasing events
resetDatabase writing run.event after DB deletion
legacy import post-swap Room writes
safe handle multiple terminal events
parse diagnostic missing correlation
```

Fix:

Add behavior tests with real/fake collaborators:

```text
RestoreJournalTest using real RestoreJournal with temp Context/filesDir
RestoreDiagnosticsSinkTest with fake OperationRunHandle + fake SafeSink + real journal
OperationRunRecorderTest with fake DAOs throwing on event/increment
NotificationProcessingPipelineTest with fake writer verifying correlation
DatabaseBackupRepository restore/reset tests with fake operation handle asserting no post-swap calls
```

Required high-value tests:

```text
restore_diagnostics_sink_appends_to_real_restore_journal
restore_journal_transition_preserves_real_events_array
reset_database_after_delete_does_not_call_room_operation_handle
legacy_import_after_swap_does_not_call_room_operation_handle
safe_handle_direct_terminal_then_cancelled_has_one_terminal
notification_parse_event_uses_listener_correlation
notification_pipeline_error_uses_listener_correlation
transaction_side_effect_uses_create_request_correlation
```

---

# 8. Acceptance matrix after `f876b3b`

| Criterion | Status | Notes |
|---|---:|---|
| RestoreDiagnosticsSink appends to journal | Mostly done | Good for `.costbackup`; tests should use real class |
| RestoreJournal append preserves history | Mostly done | Better; needs transition/commit tests |
| `.costbackup` RESTART_REQUIRED before commit | Mostly done | Good |
| No Room writes after DB swap/delete | Partial | `.costbackup` mostly; `resetDatabase` and legacy import still violate |
| Pre-swap restore failures finalize run | Mostly done | `.costbackup` improved |
| Restore diagnostics no full paths | Partial | `toDiagnosticsJson` strips, but same file still stores `_...Path` |
| Operation safe handle single terminal | Partial | terminal methods guarded; direct terminal event not guarded |
| Operation increments best-effort | Partial | no throw, but no durable safe-sink diagnostic |
| Notification RECEIVED ordering | Mostly done | Helper added; normal path emits received first in tracked coroutine |
| Notification correlation to repository | Mostly done | service -> repository -> pipeline added |
| Notification internal diagnostics same correlation | Partial | parse + exception path gaps remain |
| Transaction create correlation | Mostly done | create attempt/created/duplicates correlated |
| Transaction update/delete/bulk correlation | Not done | schema supports it, APIs do not |
| Side-effect correlation | Partial/not done | create side-effect dispatcher still called without correlation |
| Bank blocked sync durable | Partial | run starts before barrier; multiple terminal event issue remains |
| Recent failures aggregate sources | Better | includes operation/safe/journal/worker; verify with integration tests |
| Regression tests prove behavior | Partial | many tests still structural/simulated |

---

# 9. Recommended next PR order

## PR 1 — DB replacement safety for reset/import

Fix first:

```text
DDL-F876-01
DDL-F876-02
```

Goal:

```text
No normal Room OperationRunHandle calls after live DB delete/swap in resetDatabase or importDatabase.
```

Use `RestoreDiagnosticsSink` or a similar journal/safe-sink-only sink after the destructive point.

---

## PR 2 — Operation handle terminal/reason correctness

Fix:

```text
DDL-F876-04
DDL-F876-05
DDL-F876-06
```

Goal:

```text
Exactly one terminal operation outcome.
Terminal reason codes preserved.
Increment failures durable to safe sink.
```

---

## PR 3 — Notification and transaction correlation completion

Fix:

```text
DDL-F876-07
DDL-F876-08
DDL-F876-10
DDL-F876-11
```

Goal:

```text
listener/bank/email correlation reaches parse diagnostics, errors, transaction events, and side-effect events.
```

---

## PR 4 — Restore journal privacy split

Fix:

```text
DDL-F876-03
DDL-F876-12
DDL-F876-13
```

Goal:

```text
Recovery journal can contain paths; diagnostics journal cannot.
Importer handles empty/duplicate event edge cases.
```

---

## PR 5 — Metadata final edge hardening

Fix:

```text
DDL-F876-15
```

Goal:

```text
All hash-looking keys validate hash-looking values.
```

---

## PR 6 — Real behavioral tests

Fix:

```text
DDL-F876-16
```

Goal:

```text
Regression tests exercise production classes and actual failure paths, not only helper JSON/reflection checks.
```

---

# 10. Highest priority bug list

Fix these before continuing other pipelines:

```text
1. resetDatabase writes run.event/run.success after DB deletion.
2. importDatabase writes run.event/run.success/run.failedFinal after DB swap.
3. SafeSinkOperationRunHandle direct terminal events do not mark handle terminal.
4. NotificationProcessingPipeline parse/error diagnostics can lose listener correlation.
5. Transaction side effects do not receive create request correlation.
6. Restore diagnostics still keep full paths in the same durable JSON file.
```

---

# 11. Sources checked

Commit:

- https://github.com/panospao7/Cost-agregator/commit/f876b3bc3963b0a5f9557932b641d7979ea03060

Key files:

- `RestoreDiagnosticsSink.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt

- `RestoreJournal.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `RestoreJournalImporter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt

- `DatabaseBackupRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `OperationRunRecorder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt

- `CompositeOperationRunRecorder.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt

- `NotificationCaptureService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `NotificationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationRepository.kt

- `NotificationProcessingPipeline.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

- `TransactionLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionEvent.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- `CreateExpenseRequest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

- `BankApiIntegration.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `EventMetadataSanitizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt

- `SafeEventMetadata.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt

- `DiagnosticsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/domain/debug/DiagnosticsRepository.kt

- `AppDatabase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- `DDL512RegressionTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/f876b3bc3963b0a5f9557932b641d7979ea03060/app/src/test/java/com/yourname/expensetracker/diagnostics/DDL512RegressionTest.kt