# Remaining Durable Diagnostics Implementation Plan

Target commit: `aeebe3f667cd56bb7c445fc8aaba249ab84dfb26`

## Executive summary

Most of the durable diagnostics foundation is in place. The remaining work is now mostly about:

1. making operation-run diagnostics correct by construction,
2. separating restore recovery data from privacy-safe diagnostics,
3. finishing correlation propagation across transaction/update/delete/bulk paths,
4. making restore/reset/import failures durable and explicit,
5. replacing synthetic regression checks with behavior tests.

---

# Remaining issue clusters

| Area | Remaining problem |
|---|---|
| Operation runs | terminal metadata still loses reason/summary in some paths; safe fallback diagnostics use the wrong pipeline |
| Restore journal | durability failures can still be swallowed; raw recovery paths still live in the same durable JSON as diagnostics |
| Asset recovery | diagnostics-only path info is too weak for recovery |
| Transaction lifecycle | update/delete/bulk and side-effect paths still do not fully share correlation |
| Reset/import flows | post-swap failure journaling still needs stronger terminal coverage |
| Tests | several tests still mirror helpers instead of exercising production behavior |

---

# PR 1 — Operation diagnostics correctness

## Issues fixed
- terminal payload/reason loss
- safe-sink fallback uses wrong pipeline
- stale recovery / increment failure diagnostics need the right operation context

## Files
- `domain/diagnostics/OperationRunRecorder.kt`
- `domain/diagnostics/CompositeOperationRunRecorder.kt`
- `domain/diagnostics/SafeSinkOperationRunHandle.kt`
- `data/database/entity/OperationRunEvent.kt`
- `data/database/dao/OperationRunEventDao.kt`

## Plan

### 1. Add operation pipeline context
Introduce an explicit pipeline context for operation runs instead of hardcoding one pipeline in safe-sink fallback diagnostics.

Recommended shape:
```kotlin
data class OperationDiagnosticsContext(
    val pipeline: AppPipeline,
    val operationType: String,
    val correlationId: String,
    val actor: String?
)
```

Map operation types to pipelines:
- `BACKUP_*`, `RESTORE_*`, `RESET_*` -> `BACKUP_RESTORE`
- `BANK_SYNC` -> `BANK`
- `EMAIL_*` -> `EMAIL`
- `EXPORT_*`, `IMPORT_*` -> `EXPORT_IMPORT`

### 2. Preserve terminal payload
Make terminal events preserve:
- `reasonCode`
- `statusReason`
- `cancellationReason`
- `summary`
- safe exception summary
- final counters

If the schema already has enough columns, write them there; otherwise standardize them in `metadataJson`.

### 3. Fix safe-sink terminal metadata
`SafeSinkOperationRunHandle` should include:
- final counters
- supplied terminal reason
- supplied summary
- status kind

For example:
- `failedFinal(reason, error)` should record the safe reason and safe summary
- `partialSuccess(summary)` should record summary
- `cancelled(reason)` should preserve cancellation reason

### 4. Keep stale-recovery failures durable
If stale recovery event insert fails:
- emit a safe-sink diagnostic
- use the correct pipeline
- include the run correlation ID and operation type

### 5. Make `increment()` failures durable
Do not just Timber-log increment failures.
Write a safe diagnostic with:
- `operationType`
- counters attempted
- pipeline
- correlation ID

## Acceptance tests
- safe handle success emits one terminal event with counters
- safe handle cancelled preserves reason
- safe handle failedFinal preserves reason/summary
- stale recovery event insert failure goes to safe sink
- increment failure goes to safe sink with the correct pipeline

---

# PR 2 — Restore journal durability and privacy by construction

## Issues fixed
- restore write failures can still be swallowed
- raw recovery paths still live in the same durable JSON as diagnostics
- recovery/diagnostic boundary is still API-only, not structural

## Files
- `data/backup/RestoreJournal.kt`
- `data/backup/RestoreDiagnosticsSink.kt`
- `data/repository/DatabaseBackupRepositoryImpl.kt`
- `data/debug/DiagnosticsRepositoryImpl.kt`

## Plan

### 1. Split recovery data from diagnostics data
Create two concepts:
- `RestoreRecoveryJournal` for internal crash recovery
- `RestoreDiagnosticsJournal` for privacy-safe support/debug history

Recovery journal may keep:
- `_sourceBackupPath`
- `_stagedDbPath`
- `_safetyBackupPath`
- `_liveDbPath`
- asset target path

Diagnostics journal must not contain those fields.

Diagnostics should expose only:
- operation ID / correlation ID
- operation type
- state
- stage events
- hashed path values
- asset display name / hashed relative path

### 2. Make journal writes explicit
Change restore journal writes so failures are not silently ignored:
- `appendEvent`
- `writeJournal`
- `commitJournal`
- `preserveJournal`

If write/rename/copy fails before the destructive DB point, the restore/reset/import should fail fast.
After the destructive point, emit safe-sink diagnostics and preserve failure state.

### 3. Keep append history append-only
Ensure every append preserves prior events:
- read raw JSON
- append new event
- write back atomically
- preserve events across `transitionTo`, `failJournal`, `commitJournal`

### 4. Make diagnostics APIs use only diagnostics journal
`DiagnosticsRepository` must read only the diagnostics journal / events array, never the recovery journal file or raw `JournalEntry.toJson()`.

### 5. Preserve terminal restore stage coverage
For restore/reset/import, the committed diagnostics trail should contain:
- started
- maintenance entered
- safety backup created
- live DB swapped/deleted
- restart required
- rollback started/completed/failed where applicable

## Acceptance tests
- diagnostics journal contains no full paths
- recovery journal roundtrips full paths
- append preserves previous events
- commit/preserve failures are surfaced
- diagnostics repository never exposes `_...Path` fields
- support export never includes internal paths

---

# PR 3 — Transaction correlation completion

## Issues fixed
- update/delete/bulk still do not fully share correlation
- side-effect dispatchers still need the same correlation

## Files
- `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`
- `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt`
- `data/database/entity/TransactionEvent.kt`
- `data/database/dao/TransactionEventDao.kt`

## Plan

### 1. Add correlation parameters everywhere
Add optional:
- `correlationId`
- `causationId`

to all transaction mutation methods:
- update
- delete
- bulk update
- link/unlink
- ownership/merchant/category/type changes

### 2. Use one boundary correlation
If caller provides a correlation ID, reuse it.
If not, generate one once at the boundary and keep it across:
- attempted
- validated
- created/updated/deleted
- side effects

### 3. Pass the same correlation to side effects
Update dispatcher APIs so update/delete/bulk side effects use the same correlation as the mutation event.

### 4. Keep bank/email/notification flows aligned
Bank-created and notification-created expenses should carry the same correlation into:
- transaction event
- side-effect events
- downstream diagnostics

## Acceptance tests
- update/delete/bulk mutation events use supplied correlation
- side-effect events for update/delete/bulk use the same correlation
- bank-created and notification-created flows keep one shared correlation through the lifecycle

---

# PR 4 — Reset/import post-swap terminal coverage

## Issues fixed
- reset/import still need stronger post-swap terminal coverage
- legacy import post-swap failure still needs durable terminal journaling
- asset recovery path still needs a strong recovery/diagnostics split

## Files
- `data/repository/DatabaseBackupRepositoryImpl.kt`
- `data/backup/RestoreJournal.kt`
- `data/backup/RestoreDiagnosticsSink.kt`

## Plan

### 1. Reset flow
For reset:
- begin journal early enough to capture the whole reset lifecycle
- include terminal stages in the committed trail
- never use Room operation handles after destructive DB deletion begins

### 2. Legacy import flow
If legacy import fails after DB swap:
- emit a terminal restore event to the journal/safe sink
- include rollback outcome
- do not rely on the old Room handle

### 3. Make write failures explicit
If journal preservation fails:
- abort before destructive point
- or preserve failure state and emit safe-sink diagnostic after destructive point

### 4. Strengthen asset recovery
Keep the full target path only in recovery data.
Expose only a basename/hash in diagnostics.

## Acceptance tests
- reset success journal contains full stage history
- legacy import post-swap failure writes a terminal event
- rollback success/failure is journaled
- recovery data still contains the true asset target path
- diagnostics only contain safe asset identifiers

---

# PR 5 — Metadata and diagnostics hardening

## Issues fixed
- `putHashed()` is improved, but the remaining tests should prove it
- debug and recent-failure outputs still need strong coverage

## Files
- `domain/diagnostics/SafeEventMetadata.kt`
- `domain/diagnostics/EventMetadataSanitizer.kt`
- `domain/debug/DiagnosticsRepository.kt`
- `data/debug/DiagnosticsRepositoryImpl.kt`

## Plan

### 1. Enforce approved hash keys only
`putHashed()` should reject any unknown hash-like key.
Only approved hash keys can hold hash-looking values.

### 2. Final-pass sanitize debug outputs
Before returning any debug model:
- sanitize JSON
- sanitize exception messages
- strip any field starting with `_`

### 3. Keep severity-only safe-sink failures visible
Recent-failure aggregation should include records that are failures by severity even if the outcome is not explicitly one of the terminal failure enums.

## Acceptance tests
- unapproved hash key is redacted
- approved hash key accepts only hash-looking value
- debug outputs are sanitized again before return
- recent failures include severity-only safe-sink failures

---

# PR 6 — Behavioral regression test overhaul

## Goal
Replace helper-only or reflection-only checks with tests that exercise real production code paths.

## Test files to add/upgrade
- `RestoreJournalTest.kt`
- `RestoreDiagnosticsSinkTest.kt`
- `OperationRunRecorderTest.kt`
- `SafeSinkOperationRunHandleTest.kt`
- `DatabaseBackupRepositoryResetDiagnosticsTest.kt`
- `DatabaseBackupRepositoryLegacyImportDiagnosticsTest.kt`
- `NotificationProcessingPipelineDiagnosticsTest.kt`
- `TransactionLifecycleCoordinatorCorrelationTest.kt`
- `DiagnosticsRepositoryTest.kt`
- `EventMetadataSanitizerTest.kt`
- `SafeEventMetadataTest.kt`

## Must-have regression tests
- safe handle emits exactly one terminal event
- safe handle preserves cancellation/failed/summary metadata
- restore journal append preserves prior events
- journal commit/preserve failures are reported
- reset/import do not use Room handles after destructive points
- notification parse/error uses listener correlation
- transaction update/delete/bulk use supplied correlation
- diagnostics repository does not leak recovery paths
- safe-sink recent failures include severity-only failures

---

# Priority order

```text
PR 1  Operation diagnostics correctness
PR 2  Restore journal durability and privacy by construction
PR 3  Transaction correlation completion
PR 4  Reset/import post-swap terminal coverage
PR 5  Metadata and diagnostics hardening
PR 6  Behavioral regression test overhaul
```

---

# Definition of done

The remaining durable diagnostics work is complete when:

1. every operation run has one terminal outcome with preserved reason/summary where applicable,
2. safe-sink fallback uses the correct pipeline for the operation type,
3. restore recovery data is physically separated from privacy-safe diagnostics,
4. journal write failures are explicit and do not silently claim success,
5. transaction update/delete/bulk and side-effect events share the same correlation,
6. reset/import/restore post-swap failures are terminally recorded,
7. debug/recent-failure outputs never expose recovery-only paths,
8. regression tests exercise real behavior and would catch the known bugs.

## Sources checked
- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26
- `RestoreJournal.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt
- `RestoreDiagnosticsSink.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt
- `DatabaseBackupRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
- `TransactionLifecycleCoordinator.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `OperationRunRecorder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt
- `CompositeOperationRunRecorder.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/CompositeOperationRunRecorder.kt
- `SafeEventMetadata.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt
- `EventMetadataSanitizer.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/aeebe3f667cd56bb7c445fc8aaba249ab84dfb26/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventMetadataSanitizer.kt