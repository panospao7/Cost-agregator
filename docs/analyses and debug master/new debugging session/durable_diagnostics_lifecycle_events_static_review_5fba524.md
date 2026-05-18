# Durable Diagnostics / Lifecycle Events Static Review

Target commit: `5fba524acbafc3625af58646909daf6e8b8af5df`  
Baseline plan reviewed: `global_durable_diagnostics_lifecycle_events_plan.md`  
Scope: global durable diagnostics/lifecycle events, worker runs, operation runs, side effects, notification/email front doors, backup/restore, bank sync, static guard.

## Executive verdict

The refactor **did land the foundation**: taxonomy enums, diagnostic writer, operation run tables, worker run updates, side-effect diagnostics, some notification/email front-door events, and a static guard.

However, the system is **not yet definition-of-done complete**. Several important issues remain:

1. Some user inputs can still disappear without terminal durable diagnostics.
2. Restore/backup diagnostics are still unsafe because some Room writes happen during or after DB-swap-sensitive phases.
3. Worker cancellation finalization is not reliable under coroutine cancellation.
4. Side-effect events are emitted but usually lose correlation/entity context.
5. Safe metadata is only shallowly safe; it is not privacy-safe by construction yet.
6. Operation runs are skeletal: terminal status exists, but stage events/counts/stale recovery are incomplete.
7. Static guard exists but is not enough unless wired into CI and expanded.

## What appears resolved

### PR 1 — taxonomy and safe metadata

Status: **partially resolved**

Implemented:
- `AppPipeline`
- `EventOutcome`
- `EventSeverity`
- `DiagnosticReasonCode`
- `CorrelationIds`
- `SafeEventMetadata`
- `EventMetadataSanitizer`

Remaining risk:
- The sanitizer is shallow and exact-key based.
- It does not enforce a true allowlist.
- It does not recursively sanitize nested metadata.
- It does not block key variants like `raw_text`, `access_token`, `authHeader`, `filePath`, `emailSubject`, etc.
- Exception sanitizer mostly strips path-like strings; token/prompt/bank-description leakage is still possible.

User impact: **privacy/support risk**. This can leak sensitive data into diagnostics if future callers pass slightly different key names or nested objects.

Files:
- `domain/diagnostics/SafeEventMetadata.kt`
- `domain/diagnostics/EventMetadataSanitizer.kt`
- `domain/diagnostics/DiagnosticEventWriter.kt`

### PR 2 — schema

Status: **mostly resolved**

Implemented:
- `pipeline_diagnostic_events` has new event/correlation/severity/reason/source/terminal/schema fields.
- `operation_runs` exists.
- `operation_run_events` exists.
- `background_job_runs` has correlation/cancellation/metadata/error-class additions.
- App database version moved to `126`.

Remaining:
- Need actual migration test from `125 -> 126`.
- Need query helpers for correlation/debug views.
- `operation_runs` has no stale recovery equivalent to worker runs.

Files:
- `data/database/AppDatabase.kt`
- `data/database/entity/PipelineDiagnosticEvent.kt`
- `data/database/entity/OperationRun.kt`
- `data/database/entity/OperationRunEvent.kt`
- `data/database/entity/BackgroundJobRun.kt`

### PR 3 — writers

Status: **partially resolved**

Implemented:
- `DiagnosticEventWriter`
- lifecycle writer wrappers
- `OperationRunRecorder`
- `WorkerRunLogger`

Remaining:
- Writers improve consistency but do not by themselves guarantee same-transaction lifecycle writes.
- Atomicity still depends on each coordinator calling the writer inside the correct Room transaction.
- `DiagnosticEventWriter` is Room-only; it is not maintenance-safe when writes are blocked.

### PR 4 — worker logging

Status: **partially resolved**

Implemented:
- `RUNNING`, `SUCCESS`, `SKIPPED`, `RETRY`, `FAILED`, `CANCELLED`, `STALE_ABORTED`.
- `WorkerRunLogger.start`.
- finalization methods.
- stale `RUNNING` recovery method.

Remaining actual bugs:
1. Cancellation finalization occurs inside the cancelling coroutine context.
   - `run.cancelled(...)` is suspend and can itself be cancelled.
   - Result: worker row can still remain `RUNNING`.
   - Fix: wrap finalization in `withContext(NonCancellable)`.

2. `runGuardedWithContext` can start a `WorkerRunLogger` even when the worker is allowed only as read-only during backup export.
   - This writes to DB while the worker path is supposed to be read-only.
   - Fix: mirror the `runGuarded` allowed-read-only branch or use a maintenance-safe/no-op run handle.

3. `WorkerRunLogger.cancelled(reason)` stores `cancellationReason` but not `statusReason`.
   - The entity comment says typed SKIPPED/CANCELLED reason should be in `statusReason`.
   - Fix: set both `statusReason = reason` and `cancellationReason = reason`.

4. `TimeoutCancellationException` is listed as transient in `classifyTransient`, but cancellation is rethrown before classification.
   - Decide desired semantics:
     - timeout = retry, or
     - timeout = cancelled.
   - Current code suggests both.

Files:
- `domain/workers/WorkerExecutionGuard.kt`
- `domain/workers/WorkerRunLogger.kt`
- `data/database/entity/BackgroundJobRun.kt`

## Open actual bugs / high-priority gaps

## DD-001 — Notification filter drops are still not durable

Severity: **High**  
Type: **actual user/support bug**  
Pipeline: P1 Notification capture

In `NotificationCaptureService`, notification text is extracted and then:

```kotlin
if (!NotificationFilter.shouldCapture(...)) return
```

This happens before correlation ID creation and before `RECEIVED` diagnostic emission.

Impact:
- A notification rejected by filter leaves no durable `DROPPED / FILTER_REJECTED` event.
- If the filter is too aggressive, the user sees “notification capture missed it” with no explanation.

Fix:
1. Generate `correlationId` at listener entry, before filter.
2. Emit `RECEIVED` or maintenance-safe equivalent before filter.
3. If filter rejects, emit terminal:
   - pipeline `NOTIFICATION`
   - stage `filter`
   - outcome `DROPPED`
   - reason `FILTER_REJECTED`
   - `isTerminal = true`
4. Hash notification key/package as needed.
5. Add test:
   - `notification_filter_drop_writes_terminal_diagnostic`.

File:
- `service/NotificationCaptureService.kt`

## DD-002 — Notification restore-blocked diagnostics appear missing

Severity: **High**  
Type: **actual support/debug bug**  
Pipeline: P1 Notification capture / global restore barrier

I found notification `RECEIVED` and privacy-drop diagnostics, but no `RESTORE_BLOCKED` path in `NotificationCaptureService`.

Impact:
- During restore/maintenance, notification capture may be blocked or fail while diagnostics are either not written or swallowed.
- This violates the plan’s restore-blocked rule.

Fix:
1. Check restore/write barrier before Room diagnostic writes.
2. If DB writes are blocked, use `MaintenanceSafeDiagnosticSink`.
3. Do not silently swallow diagnostic failure without safe fallback.
4. Add tests:
   - `notification_restore_blocked_uses_safe_sink`
   - `notification_received_has_terminal_or_safe_sink`.

Files:
- `service/NotificationCaptureService.kt`
- `domain/diagnostics/DiagnosticEventWriter.kt`
- backup/restore maintenance-safe sink package

## DD-003 — DiagnosticEventWriter is not maintenance-safe

Severity: **High**  
Type: **actual global bug during restore/backup**

`RoomDiagnosticEventWriter` writes directly to `PipelineDiagnosticEventDao`.

Impact:
- The plan explicitly says restore-blocked diagnostics must not depend on normal Room inserts.
- Current callers often catch and ignore diagnostic insert failures.
- This can create silent observability loss exactly when diagnostics matter most.

Fix strategy:
- Replace direct binding with `CompositeDiagnosticEventWriter`:
  1. If DB writes allowed -> Room writer.
  2. If writes blocked -> `MaintenanceSafeDiagnosticSink`.
  3. If Room insert fails with DB-closed/locked/restore exception -> safe sink fallback.
- Add `emitBestEffort` and `emitRequired` variants if needed.

Files:
- `domain/diagnostics/DiagnosticEventWriter.kt`
- `data/backup/MaintenanceSafeDiagnosticSink`
- DI module binding

## DD-004 — Worker cancellation finalization is not reliable

Severity: **High**  
Type: **actual user/debug bug**  
Pipeline: P9 Workers

Current flow:
- catch `CancellationException`
- call `run.cancelled(...)`
- rethrow

Problem:
- Because the coroutine is already cancelling, the suspend DB update can itself be cancelled.
- This can leave `background_job_runs.status = RUNNING`.

Fix:
```kotlin
catch (e: CancellationException) {
    withContext(NonCancellable) {
        run.cancelled(DiagnosticReasonCode.CANCELLED_BY_SYSTEM.name)
    }
    throw e
}
```

Also wrap `retry/failure/success` finalization in a helper that cannot be interrupted after the domain operation is done.

Test:
- `cancelled_worker_updates_run_CANCELLED_even_when_scope_cancelled`

Files:
- `domain/workers/WorkerExecutionGuard.kt`

## DD-005 — runGuardedWithContext violates read-only backup mode

Severity: **High**  
Type: **actual barrier bug**  
Pipeline: P9 Workers / P7 Backup

`runGuarded` has a special `allowedReadOnly` path that avoids worker run DB writes.  
`runGuardedWithContext` computes `allowedReadOnly`, but then still starts `workerRunLogger.start(...)`.

Impact:
- A read-only worker allowed during backup export may write `background_job_runs`.
- This conflicts with the global rule: no DB writes unless write barrier allows it.

Fix:
- In `runGuardedWithContext`, if `allowedReadOnly`:
  - check read barrier,
  - run block with a no-op/read-only context,
  - record via maintenance-safe sink if necessary,
  - do not call `workerRunLogger.start`.

Test:
- `read_only_worker_during_backup_does_not_insert_background_job_run`

File:
- `domain/workers/WorkerExecutionGuard.kt`

## DD-006 — Restore operation run can be lost across DB swap

Severity: **Critical for restore diagnostics**  
Type: **actual support/debug bug**  
Pipeline: P7 Backup/restore

`restoreCostBackup` starts an `OperationRun` before entering maintenance / swapping DB. Later it calls `run.success()` after restore completion.

Problem:
- If the live DB is replaced by the restored DB, the original `operation_runs` row may be in the old DB and not survive.
- If the Room instance points to the pre-swap DB, finalization may fail or write to the wrong place.
- This violates the plan warning: do not rely only on Room after DB swap.

Fix:
1. Use `RestoreJournal` or an external maintenance-safe operation log as the authoritative restore event stream.
2. Record:
   - restore start,
   - bundle validation,
   - staged DB creation,
   - live DB swap,
   - restart required,
   - rollback start/end/failure.
3. After app restart, import/reflect the journal into Room if desired.
4. Do not trust pre-swap `OperationRunHandle` after live DB replacement.
5. Add tests:
   - `restore_success_keeps_operation_record_after_swap`
   - `restore_failure_after_swap_writes_journal_event`
   - `restore_rollback_failure_writes_critical_journal_event`

File:
- `data/repository/DatabaseBackupRepositoryImpl.kt`

## DD-007 — OperationRunRecorder is skeletal

Severity: **Medium/High**  
Type: **architectural gap with support impact**  
Pipelines: P7/P10/P11/P12 batch operations

Implemented:
- start row
- in-memory counters
- terminal finalization

Missing:
- automatic STARTED event
- helper ensuring terminal finalization in `finally`
- durable incremental counters
- stale recovery for `RUNNING` operation runs
- entity/correlation/exception fields in the public `event(...)` API
- causation ID support
- maintenance-safe fallback
- standard per-stage event calls in backup/restore/bank

Impact:
- Process death can leave operation run as `RUNNING`.
- Counts stay zero until finalization.
- Stage-level debugging remains weak.

Fix:
- Add `OperationRunRecorder.runOperation(...)` helper:
```kotlin
operationRunRecorder.runOperation("BACKUP_EXPORT", actor = "user") { run ->
    run.event("STARTED", ATTEMPTED)
    ...
}
```
- Internally ensure terminal status in `NonCancellable`.
- Add stale recovery:
  - `recoverStaleOperationRuns(...) -> STALE_ABORTED`
- Persist counter increments or stage summaries periodically for long operations.

Files:
- `domain/diagnostics/OperationRunRecorder.kt`
- `data/database/dao/OperationRunDao.kt`

## DD-008 — Backup/restore operation runs lack required stage events

Severity: **Medium/High**  
Type: **architectural gap; actual support issue for failed restores**

The plan requires backup stages like:
- `MAINTENANCE_ENTERED`
- `WORKERS_DRAINED`
- `SNAPSHOT_CREATED`
- `MANIFEST_WRITTEN`
- `ENCRYPTED`
- `COMPLETED`

Restore stages like:
- `BUNDLE_VALIDATED`
- `STAGED_DB_CREATED`
- `LIVE_DB_SWAPPED`
- `ROLLBACK_STARTED`
- `ROLLBACK_FAILED`

Current inspected backup/restore snippets show operation run start and terminal success/failure, but not the full stage trail.

Fix:
- Add `run.event(...)` at every major stage.
- For restore after DB danger point, write to restore journal/safe sink, not normal Room.

File:
- `data/repository/DatabaseBackupRepositoryImpl.kt`

## DD-009 — Side-effect diagnostics lack correlation/entity context

Severity: **Medium**  
Type: **architectural/support gap**  
Pipelines: P2/P3 and any side-effect caller

`TransactionSideEffectDispatcher` and `ReceiptSideEffectDispatcher` emit:
- `SIDE_EFFECT_STARTED`
- `SIDE_EFFECT_COMPLETED`
- `SIDE_EFFECT_FAILED`

But inspected events use fresh/default diagnostic correlation unless a caller passes it. Metadata contains side-effect name, but not consistently:
- source operation correlation
- entity type
- entity ID
- causation ID

Impact:
- You cannot trace:
  - expense create -> transaction lifecycle -> side effect -> failure
  - receipt saved -> matching side effect -> failed match

Fix:
1. Update dispatcher APIs:
```kotlin
runSideEffect(
    correlationId: String,
    causationId: String?,
    entityType: String,
    entityId: Long?,
    sideEffectName: String,
    block: suspend () -> Unit
)
```
2. Emit entity fields directly, not only metadata.
3. Use same correlation ID as the domain mutation.
4. Add tests:
   - `side_effect_failure_has_same_correlation_as_expense_create`
   - `receipt_matching_side_effect_has_receipt_entity`

Files:
- `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt`
- `domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt`

## DD-010 — Side-effect cancellation has no terminal diagnostic

Severity: **Medium**  
Type: **architectural gap**

Current side-effect dispatcher rethrows `CancellationException`.

This is correct for coroutine semantics, but the universal rule includes `CANCELLED` terminal outcomes.

Fix:
- Before rethrowing cancellation, emit:
  - outcome `CANCELLED`
  - reason `CANCELLED_BY_SYSTEM`
  - `isTerminal = true`
- Use `NonCancellable` for that diagnostic finalization.
- If DB writes blocked, safe sink fallback.

## DD-011 — Safe metadata is not privacy-safe enough

Severity: **High**  
Type: **potential privacy bug**

Current design blocks exact lowercase keys only. It does not normalize separators or block dangerous substrings. Examples that may pass:
- `raw_text`
- `rawOcr`
- `access_token`
- `authHeader`
- `bearer`
- `full_path`
- `filePath`
- `bankDescription`
- `emailSubject`
- nested map containing `prompt`

Fix:
1. Canonicalize key:
```kotlin
key.lowercase().replace(Regex("[^a-z0-9]"), "")
```
2. Block by canonical exact and dangerous substrings:
   - raw/body/ocr/prompt/token/auth/password/secret/path/iban/account/card
3. Recursively sanitize maps/lists.
4. Add value scanning for JWT-like strings, bearer tokens, file paths, IBAN-like/account-like values.
5. Make writer sanitize final JSON even if caller used `SafeEventMetadata`.
6. Prefer drop/redact over `require(...)` for diagnostics, because diagnostics should not crash production flow.

Tests:
- `metadata_sanitizer_blocks_raw_text_variant`
- `metadata_sanitizer_blocks_nested_prompt`
- `metadata_sanitizer_redacts_token_like_values`
- `diagnostic_writer_sanitizes_final_json`

Files:
- `domain/diagnostics/SafeEventMetadata.kt`
- `domain/diagnostics/EventMetadataSanitizer.kt`
- `domain/diagnostics/DiagnosticEventWriter.kt`

## DD-012 — Email front-door diagnostics are improved but not complete

Severity: **Medium**  
Type: **partially resolved actual bug**

Resolved:
- restore-blocked event exists
- parser failure event exists
- validation failure event appears present

Still verify/fix:
- `EMAIL_RECEIVED` should be emitted before parse.
- duplicate message ID/content should be terminal durable outcomes.
- source insert conflict should be durable and rollback-safe.
- low-confidence review route should be lifecycle-visible.
- existing-expense link should emit `LINKED`, not create.

Files:
- `data/email/EmailReceiptIngestionService.kt`
- `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`

Tests:
- `email_received_event_before_parser`
- `duplicate_message_id_writes_terminal_diagnostic`
- `email_source_conflict_writes_failure_and_rolls_back`

## DD-013 — Bank sync instrumentation is mostly not done

Severity: **Medium/High depending on feature maturity**  
Type: **architectural gap; actual if bank sync is enabled**

I found `operationRunRecorder.start("BANK_SYNC")`, but the file still contains comments/TODO-style real implementation notes and no evidence of detailed per-transaction outcomes like:
- transaction received
- classified
- imported
- review created
- duplicate skipped
- failed

Fix:
- Add per-sync run stages:
  - `SYNC_STARTED`
  - `PAGE_FETCHED`
  - `SYNC_COMPLETED/PARTIAL/FAILED`
- Add per-transaction outcome table or operation events:
  - `TRANSACTION_IMPORTED`
  - `TRANSACTION_DUPLICATE_SKIPPED`
  - `TRANSACTION_FAILED`
  - `TRANSACTION_REVIEW_CREATED`
- Add low-confidence routing event.
- Add duplicate event with matched entity ID.

File:
- `domain/bank/BankApiIntegration.kt`

## DD-014 — Static guard is useful but incomplete

Severity: **Medium**  
Type: **process/architecture gap**

Script exists:
- `scripts/verify_event_writers.py`

Gaps:
1. It only fails if run with `--fail-on-violation`.
2. I did not verify CI invokes it.
3. Header rules mention direct `OperationRun(...)`, but not direct `OperationRunEvent(...)`.
4. It cannot detect missing events, only direct construction.
5. It cannot detect wrong transaction boundary.

Fix:
- Add CI step:
```bash
python3 scripts/verify_event_writers.py --fail-on-violation
```
- Add rules for:
  - direct `OperationRunEvent(...)`
  - direct DAO insert/update of event entities outside writer classes
  - direct `PipelineDiagnosticEventDao.insert(...)`
- Add allowlist file with explicit comments.
- Add dynamic golden tests for coverage.

File:
- `scripts/verify_event_writers.py`

## DD-015 — Debug queries/UI are still missing

Severity: **Low/Medium**  
Type: **support tooling gap**

`PipelineDiagnosticEventDao` did not show `getDiagnosticsByCorrelationId`.

The plan wanted:
- diagnostics by correlation ID
- operation run with events
- events by entity
- recent failures

Fix:
- Add DAO queries:
```kotlin
getDiagnosticsByCorrelationId(correlationId)
getRecentFailures(limit)
getDiagnosticsForEntity(entityType, entityId)
getOperationRunWithEvents(operationRunId)
```
- Add repository/debug screen later.

Files:
- `data/database/dao/PipelineDiagnosticEventDao.kt`
- `data/database/dao/OperationRunDao.kt`
- `data/database/dao/OperationRunEventDao.kt`

# Acceptance criteria status

| Criterion | Status | Notes |
|---|---:|---|
| Every input has domain row or terminal diagnostic/safe sink | Partial | Notification filter drops still vanish; restore-blocked notification path missing |
| Domain mutations have lifecycle events in same transaction | Unknown/partial | Writers exist, but atomicity depends on coordinators; needs transaction tests |
| Every duplicate decision durable | Partial | Need verify receipt/email/bank/review duplicates |
| Every validation failure durable | Partial | Email yes; transaction/receipt need targeted checks |
| Privacy/restore blocked decision durable | Partial | Email yes; notification/diagnostic writer not safe |
| Every worker has final status | Partial | cancellation not NonCancellable; read-only context bug |
| Every batch operation has terminal run/counts | Partial | start/final exists, counts/stages/stale recovery weak |
| Every side-effect failure durable | Partial | failure emitted, but correlation/entity/cancellation gaps |
| Metadata privacy-safe by construction | Not yet | sanitizer shallow/exact-key |
| Correlation trace across tables | Not yet | side effects and operation runs do not consistently propagate |

# Recommended implementation plan

## Phase 0 — add failing tests first

Add tests that currently expose the gaps:

1. `notification_filter_drop_writes_terminal_diagnostic`
2. `notification_restore_blocked_uses_safe_sink`
3. `diagnostic_writer_falls_back_to_safe_sink_when_room_write_blocked`
4. `cancelled_worker_finalizes_with_non_cancellable_update`
5. `read_only_worker_during_backup_does_not_insert_background_job_run`
6. `restore_operation_record_survives_live_db_swap_or_journal`
7. `side_effect_failure_has_original_correlation_and_entity`
8. `metadata_sanitizer_blocks_key_variants_and_nested_values`
9. `operation_run_stale_running_recovery_marks_stale_aborted`
10. `static_guard_blocks_operation_run_event_direct_construction`

## Phase 1 — make diagnostics maintenance-safe

Implement `CompositeDiagnosticEventWriter`.

Pseudo-plan:
```kotlin
class CompositeDiagnosticEventWriter(
    private val room: RoomDiagnosticEventWriter,
    private val safeSink: MaintenanceSafeDiagnosticSink,
    private val maintenanceMode: RestoreMaintenanceMode,
    private val writeBarrier: DatabaseWriteBarrier
) : DiagnosticEventWriter {
    override suspend fun emit(event: DiagnosticEvent) {
        if (!canWriteRoom()) {
            safeSink.recordDiagnostic(event)
            return
        }

        try {
            room.emit(event)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            safeSink.recordDiagnostic(event, e)
        }
    }
}
```

Then replace DI binding to use composite writer.

## Phase 2 — fix notification front door

In `NotificationCaptureService.onNotificationPosted`:

1. Generate correlation ID immediately.
2. Emit `RECEIVED` before filter.
3. If privacy fails before extraction, emit safe diagnostic.
4. If filter rejects, emit `DROPPED/FILTER_REJECTED`.
5. If restore/write barrier blocks, emit `BLOCKED/RESTORE_BLOCKED` to safe sink.
6. Ensure every return path after listener entry has terminal outcome.

## Phase 3 — fix worker finalization and read-only behavior

1. Add helper:
```kotlin
private suspend fun finalizeRunNonCancellable(block: suspend () -> Unit) {
    withContext(NonCancellable) { block() }
}
```

2. Use it for:
- success
- skipped
- retry
- failure
- cancelled

3. In `runGuardedWithContext`, handle `allowedReadOnly` before `workerRunLogger.start`.

4. Set `statusReason` for cancelled.

5. Clarify timeout semantics.

## Phase 4 — make restore diagnostics journal-backed

1. Treat `RestoreJournal` as authoritative during restore.
2. OperationRun can exist for normal DB, but restore events after DB swap must go to journal/safe sink.
3. On startup recovery, import journal into diagnostics/operation runs if DB is healthy.
4. Add rollback failure critical event.

## Phase 5 — upgrade operation runs

1. Add `runOperation` helper with automatic finalization.
2. Emit `STARTED` operation event automatically.
3. Add stale recovery.
4. Persist periodic counters for long operations.
5. Expose event API with:
   - entityType/entityId
   - causationId
   - exception
   - terminal flag if needed

## Phase 6 — fix metadata safety

1. Canonical key normalization.
2. Recursive sanitizer.
3. Dangerous substring blocking.
4. Token/path/account/IBAN value redaction.
5. Writer final-pass sanitizer.
6. Tests.

## Phase 7 — correlation propagation

1. Add optional `correlationId` to:
   - operation run start
   - side-effect dispatcher calls
   - transaction/receipt lifecycle event models
   - notification/email requests
2. Side-effect events must include:
   - same correlation ID
   - causation ID
   - entity type/id
3. Operation run events should share operation correlation ID.

## Phase 8 — static guard and CI

1. Add CI command with `--fail-on-violation`.
2. Expand guard to `OperationRunEvent`.
3. Guard direct DAO inserts/updates of event entities.
4. Keep explicit allowlist.

# Sources checked

- Commit `5fba524`:  
  https://github.com/panospao7/Cost-agregator/commit/5fba524acbafc3625af58646909daf6e8b8af5df

- Last commits page:  
  https://github.com/panospao7/Cost-agregator/commits/5fba524acbafc3625af58646909daf6e8b8af5df/

- Docs architecture directory:  
  https://github.com/panospao7/Cost-agregator/tree/5fba524acbafc3625af58646909daf6e8b8af5df/docs/architecture

- Docs analyses/debug master directory:  
  https://github.com/panospao7/Cost-agregator/tree/5fba524acbafc3625af58646909daf6e8b8af5df/docs/analyses%20and%20debug%20master

- New debugging session directory:  
  https://github.com/panospao7/Cost-agregator/tree/5fba524acbafc3625af58646909daf6e8b8af5df/docs/analyses%20and%20debug%20master/new%20debugging%20session

- Main implementation files:
  - `SafeEventMetadata.kt`
  - `EventMetadataSanitizer.kt`
  - `DiagnosticEventWriter.kt`
  - `OperationRunRecorder.kt`
  - `BackgroundJobRun.kt`
  - `WorkerRunLogger.kt`
  - `WorkerExecutionGuard.kt`
  - `NotificationCaptureService.kt`
  - `EmailReceiptIngestionService.kt`
  - `TransactionSideEffectDispatcher.kt`
  - `ReceiptSideEffectDispatcher.kt`
  - `DatabaseBackupRepositoryImpl.kt`
  - `BankApiIntegration.kt`
  - `verify_event_writers.py`