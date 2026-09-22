# CL-19 Implementation Spec — Restore/backup fail-closed state machine
Provenance: 2026-09-22; pin 37601232 verified; astra xhigh Stage-3 session; source cluster-map.md.

## GATE

- **UPDATE 2026-09-22 (post-spec): GATE LIFTED.** All member findings verified 13/13 CONFIRMED by independent adversarial pass (ZCode/GLM-5.3, different model+session from finder) in `../verification-2026-09-22-wave1.md`; journal PHASE-2-VERIFICATION 2026-09-22T17:3x. Implementation may proceed.
- Implementation is blocked until the independent verification gate revalidates all five member findings: CA-P-07-003 (P0, unverified), CA-P-07-004 (P0, unverified), CA-P-07-005 (P0, unverified), CA-P-10-001 (P1, unverified), and CA-I-01-001 (P2, unverified). Do not treat the audit evidence below as verification approval.

## Context for the coder

Backup and restore pause workers, gate Room access, replace database files, verify the replacement, and restore receipt assets. The maintenance mode and on-disk restore journal are the two durable records that tell startup whether the app may write and how to recover after a crash. Bank sync is a separate debug surface that can receive a typed RESTORE_BLOCKED outcome while its coordinator still attempts to persist terminal status.

The root cause is fail-open state handling: a failed SharedPreferences commit is ignored, invalid mode/journal values are coerced to a safe-looking state, journal I/O failures are swallowed before a destructive swap, the bank coordinator writes after the integration has already been denied by the restore barrier, and the asset rollback branch resets to NORMAL without rescheduling workers. The fix is a single fail-closed contract: uncertainty remains blocked, is durable when storage permits, and is visible as CRITICAL_RECOVERY_REQUIRED/recovery-required UI.

## Fail-closed state-machine design (must be implemented before the work items)

### Durable maintenance modes and transitions

RestoreMaintenanceMode.Mode at app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt:56 has these eleven persisted values:

- NORMAL: the only mode in which ordinary writes and normal app reads are admitted. Startup may schedule workers.
- BACKUP_EXPORTING: entered before the backup snapshot; workers are paused, ordinary writes are denied, and only the existing export snapshot read policy is allowed. A successful export transitions to NORMAL through the existing exit path, which must reschedule workers.
- RESTORE_PREPARING: entered before restore/import/reset worker drain. All ordinary writes are denied.
- RESTORE_STAGING: staging and pre-swap validation. Ordinary writes and restore-stage app reads remain denied.
- RESTORE_SWAPPING: live file replacement. All ordinary Room access remains denied; the journal must already contain SWAPPING.
- RESTORE_VERIFYING: the new live DB is opened and verified. Ordinary writes remain denied; only the existing restore-internal scope may perform explicitly allowed verification writes.
- RESTORE_ROLLING_BACK: safety/pre-restore recovery is in progress. All ordinary writes remain denied. It may transition to RESTORE_COMPLETE_RESTART_REQUIRED when rollback is verified and a restart is required, or to CRITICAL_RECOVERY_REQUIRED when no verified recovery source remains.
- ASSETS_RESTORING: the DB is verified and the asset ledger is being resumed. Ordinary writes remain denied; only the existing restore-internal asset path is allowed.
- RESETTING_DATABASE: database deletion/reset is in progress; ordinary writes remain denied. A successful reset requires RESTORE_COMPLETE_RESTART_REQUIRED; a failure before any destructive point may return to NORMAL only after the active journal is durably finalized and the original DB is known intact.
- RESTORE_COMPLETE_RESTART_REQUIRED: the DB/journal operation completed, but the Room singleton is stale until a forced restart. Writes remain denied. On the next clean startup, this mode may transition to NORMAL only if there is no active journal and no critical marker.
- CRITICAL_RECOVERY_REQUIRED: absorbing fail-closed state. It is persisted with a safe reason and timestamp, displayed by the existing AppOperationalState.CriticalRecoveryRequired UI, and never auto-reset by startup. Only an explicit, separately reviewed recovery action may clear it.

Required maintenance-mode transitions (including failure edges) are: NORMAL -> BACKUP_EXPORTING for a backup snapshot; BACKUP_EXPORTING -> NORMAL only after export cleanup and a successful mode commit, or -> CRITICAL_RECOVERY_REQUIRED on mode persistence failure; NORMAL -> RESTORE_PREPARING for costbackup restore, legacy import, or reset; RESTORE_PREPARING -> RESTORE_STAGING after the worker drain and staging preconditions, or -> NORMAL only for a proven pre-destructive abort, or -> CRITICAL_RECOVERY_REQUIRED on an uncertain journal/mode write; RESTORE_STAGING -> RESTORE_SWAPPING only after the durable SAFETY_BACKUP_CREATED journal transition, or -> RESTORE_ROLLING_BACK/CRITICAL_RECOVERY_REQUIRED on a post-safety failure; RESTORE_SWAPPING -> RESTORE_VERIFYING after the file swap, or -> RESTORE_ROLLING_BACK; RESTORE_VERIFYING -> ASSETS_RESTORING after verification, or -> RESTORE_ROLLING_BACK; ASSETS_RESTORING -> RESTORE_COMPLETE_RESTART_REQUIRED after the asset journal is committed, or -> NORMAL only after a verified rollback that invokes worker rescheduling, or -> CRITICAL_RECOVERY_REQUIRED when rollback is not verified; RESTORE_ROLLING_BACK -> RESTORE_COMPLETE_RESTART_REQUIRED after verified recovery, or -> CRITICAL_RECOVERY_REQUIRED after failed/uncertain recovery; RESETTING_DATABASE -> RESTORE_COMPLETE_RESTART_REQUIRED after successful reset, or -> NORMAL only when no destructive point was reached and all failure records are durable, or -> CRITICAL_RECOVERY_REQUIRED when durability or DB health is uncertain; RESTORE_COMPLETE_RESTART_REQUIRED -> NORMAL only on a clean restart with no active/corrupt journal; CRITICAL_RECOVERY_REQUIRED has no automatic outgoing transition.

Every transition that writes a mode must synchronously persist it with SharedPreferences.commit() and check the Boolean result before publishing the in-memory flow. A false result or thrown persistence error is a mode/barrier durability failure: do not publish NORMAL or any requested non-critical state; abort the operation, attempt to persist CRITICAL_RECOVERY_REQUIRED, expose the critical state in memory, and keep the write barrier closed. If the critical marker commit also fails, retain the in-memory critical lock and surface a typed persistence failure so the next startup treats an unreadable/unknown mode as critical rather than defaulting to NORMAL. Never use apply().

An unknown persisted mode string, missing/corrupt mode value that cannot be proven to mean NORMAL, or a mode-read exception must resolve to CRITICAL_RECOVERY_REQUIRED (or the equivalent fail-closed read result), never NORMAL. DatabaseWriteBarrier.checkWritesAllowed at app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt:15 remains the ordinary write admission point: it must deny every mode other than NORMAL, and a mode-read/persistence-health failure must also deny. Do not rewrite the barrier into a second mode store.

### Durable journal states and transitions

RestoreJournal.JournalState at app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt:150 has these nine values:

- PREPARING — beginJournal has durably recorded source, staged, and live DB identities before maintenance work.
- STAGED — extraction/staging and manifest checks completed while the live DB is intact.
- SAFETY_BACKUP_CREATED — the safety backup path is durably recorded before any live-file mutation.
- SWAPPING — the destructive live-file swap has begun; startup must attempt safety/pre-restore recovery.
- VERIFYING — the new live DB is being opened and checked.
- ASSETS_RESTORING — DB verification passed and the idempotent asset ledger is active.
- COMPLETE — all required restore/reset state is finalized; commitJournal must durably preserve the success journal before the restart-required mode is published.
- ROLLING_BACK — rollback is in progress after a destructive-stage failure. If a verified source is restored, finalize the failure journal and require restart; if every source fails, enter critical mode and retain recovery evidence.
- FAILED — terminal failure record for a non-success path; it may be moved to the failure journal only after the terminal state itself was durably written.

Allowed forward/recovery transitions are:

- beginJournal creates PREPARING.
- PREPARING -> STAGED after extracted DB/manifest validation and staged copy succeed.
- STAGED -> SAFETY_BACKUP_CREATED only after the safety backup exists and its path is written durably.
- SAFETY_BACKUP_CREATED -> SWAPPING only after the journal write returns durable success; no file rename/copy may begin before this point.
- SWAPPING -> VERIFYING after the live file replacement and fresh Room open succeed.
- VERIFYING -> ASSETS_RESTORING after required integrity/count/semantic verification succeeds.
- ASSETS_RESTORING -> COMPLETE after the asset ledger is durably finalized.
- Any pre-swap validation/staging failure -> FAILED, cleanup, and NORMAL only when the live DB remained intact and every terminal journal/mode write succeeded.
- Any failure in SWAPPING, VERIFYING, or asset recovery first records/attempts ROLLING_BACK; verified rollback -> FAILED plus RESTORE_COMPLETE_RESTART_REQUIRED; failed rollback or uncertain DB health -> CRITICAL_RECOVERY_REQUIRED.
- COMPLETE is moved to the success journal only after its write is durable. A success-journal rename/copy failure is critical; never delete the only active journal and continue.
- FAILED/failure-journal preservation failure is also critical because the recovery record is no longer durable.

RestoreJournal.readJournal at :482 must distinguish “file absent” from “file present but blank, unreadable, malformed, or containing an unknown state.” Only an absent file may produce RecoveryResult.NoAction. JournalEntry.fromJson at :108 must reject an unknown state instead of mapping it to PREPARING; malformed required fields and malformed asset task entries must be treated as corrupt journal input. Corrupt/unknown bytes must produce RecoveryResult.CriticalRecoveryRequired (or a typed corrupt result that AppStartupCoordinator immediately maps to that state), preserve the bytes for diagnostics/recovery, and prevent startup mode reset.

RestoreJournal.writeJournal at :497 must make fsync and rename/copy failures observable. writeTextSynced must not swallow FileDescriptor.sync() failure. A failed renameTo may use a copy fallback only if the fallback write, fsync, and cleanup all succeed; otherwise throw a typed journal-durability failure. The same durability rule applies to active, success, and failure journal preservation. Callers must stop before SWAPPING on a failed transition write; after a destructive point, they must enter rollback/critical recovery rather than silently continuing.

### Startup and user-visible enforcement location

- RestoreMaintenanceMode owns the persisted mode, mode decoding, commit result, and mode-flow publication.
- RestoreJournal owns journal parsing, durable write/rename semantics, and the explicit corrupt-journal result. It must not silently turn corruption into NoAction.
- AppStartupCoordinator.checkRestoreJournal at :114 owns recovery orchestration: any corrupt/unknown journal result, failed recovery, or failed journal finalization must call enterCriticalRecoveryRequired; the existing clean-start auto-reset at :186-200 must remain limited to known transient restart/in-progress modes and must never reset critical mode.
- DatabaseWriteBarrier remains the single ordinary write admission check. It consumes the fail-closed mode; it does not repair mode/journal persistence and must not be rewritten to special-case the bank caller.
- BankConnectionLifecycleCoordinator.persistOutcome is the caller-level barrier fix. BankApiIntegration.syncTransactions may continue to return Blocked(RESTORE_BLOCKED), but the coordinator must check the write barrier immediately before its terminal DAO update and skip that update when denied.
- AppStartupCoordinator.resumeAssetsIncompleteRecovery is the rollback rescheduling owner. After a verified rollback, use the normal maintenance-exit path (or the exact equivalent that invokes WorkerRegistry.scheduleAll) so workers cancelled at maintenance entry are rescheduled before the process is considered writable. Do not use RestoreMaintenanceMode.reset() on this branch.

## Pre-implementation checks (coder MUST run, in order)

1. git diff 37601232..HEAD -- app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt app/src/main/java/com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt — read every hunk; the anchors below are from pin 37601232 and may have drifted.
2. Re-read each target function at current HEAD before editing: RestoreMaintenanceMode.readMode/writeMode/enter/exit/reset; DatabaseWriteBarrier.checkWritesAllowed; RestoreJournal.JournalEntry.fromJson/readJournal/writeJournal/beginJournal/transitionTo/commitJournal/failJournal/checkAndRecover; AppStartupCoordinator.initialize/checkRestoreJournal/resumeAssetsIncompleteRecovery; DatabaseBackupRepositoryImpl.restoreCostBackup/importDatabase/resetDatabase; BankConnectionLifecycleCoordinator.syncConnection/persistOutcome; and BankApiIntegration.syncTransactions.

## Legal path constraints

- Backup/restore must follow docs/architecture/LEGAL_PATHS.md#backup--restore: maintenance entry and worker drain precede snapshot/swap work; the persisted mode blocks all ordinary writes; the journal is append/durable before destructive file changes; rollback failure enters CRITICAL_RECOVERY_REQUIRED; forced restart follows a successful swap.
- All ordinary DB writes remain behind DatabaseWriteBarrier; restore-internal writes remain behind the existing RestoreInternalWriteScope for its explicitly allowed modes. Do not broaden that scope to make the bank fix pass.
- Bank sync keeps its coordinator ownership: BankConnectionLifecycleCoordinator.syncConnection calls BankApiIntegration, then owns terminal status persistence. BankApiIntegration must not write BankConnectionDao directly, and no UI/ViewModel/repository caller may bypass the coordinator.
- DatabaseBackupRepositoryImpl remains the restore/import/reset owner. Do not move journal or mode writes into DAOs, BankConnectionDao, or UI code.

## Work items

### WI-1 — Durable maintenance mode and barrier decoding (CA-P-07-003, P0, UNVERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt — readMode, writeMode, enter, exit, reset — ≈L103-213 @ pin 37601232; DatabaseWriteBarrier.kt — checkWritesAllowed — ≈L15-28.
- Defect: writeMode ignores the Boolean returned by SharedPreferences.commit() and publishes the requested mode anyway; readMode maps an invalid persisted enum name to NORMAL, allowing writes during maintenance.
- Change: Make every mode persistence operation synchronous and checked. Publish _modeFlow and _operationalStateFlow only after a successful commit. On false/throw, abort the transition and enter the durable critical/recovery path; do not call worker scheduling or reset stop flags after a failed transition. Decode only known enum names; unknown, blank, malformed, or mode-read failures must be fail-closed and must never be represented as NORMAL. Ensure enterCriticalRecoveryRequired follows the same commit-result rule and does not expose a non-durable normal state. Keep DatabaseWriteBarrier as the central check and make it deny if mode state is unknown/unreadable or persistence is unhealthy.
- Acceptance criteria:
  - A false/throwing mode commit never publishes NORMAL and leaves writes denied.
  - An unknown persisted mode survives construction as a critical/recovery state, and a fresh DatabaseWriteBarrier rejects writes.
  - Every known non-NORMAL mode is denied by checkWritesAllowed; NORMAL is the only admitted mode.
  - A successful exit(false) schedules workers only after the NORMAL commit succeeds; a failed commit schedules none.
  - Critical mode is visible through operationalStateFlow and is not auto-reset on a later startup.

### WI-2 — Corrupt/unknown journal is a critical recovery result (CA-P-07-004, P0, UNVERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt — JournalEntry.fromJson, readJournal, checkAndRecover — ≈L108-116, L482-490, L695-736; app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt — checkRestoreJournal — ≈L114-205.
- Defect: Unknown journal state is coerced to PREPARING; read/parse errors return null; startup interprets null as NoAction, cleans/reset modes, and can resume writes without knowing the restore stage.
- Change: Replace nullable corruption handling with an explicit absent-versus-corrupt result. Reject unknown enum values and malformed required JSON instead of defaulting state/fields. checkAndRecover must return a critical/corrupt result for present-but-unreadable bytes, including blank/truncated JSON and unknown state. AppStartupCoordinator must persist CRITICAL_RECOVERY_REQUIRED, retain the active/failure journal bytes when possible, and return before the transient-mode reset block. Do not delete or rename a corrupt active journal as if it were a normal failure.
- Acceptance criteria:
  - Missing journal file still yields NoAction.
  - Blank, truncated, invalid-JSON, unknown-state, and malformed-required-field journals yield critical recovery, not NoAction, CleanedNonDestructive, or PREPARING.
  - A critical result persists across a second coordinator construction/startup and keeps DatabaseWriteBarrier closed.
  - A valid PREPARING/STAGED journal retains the existing non-destructive cleanup behavior; valid SWAPPING/VERIFYING/ROLLING_BACK still enters recovery.
  - Corrupt bytes remain available for manual recovery/diagnostic inspection.

### WI-3 — Journal durability before destructive transitions (CA-P-07-005, P0, UNVERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt — writeJournal, writeTextSynced, beginJournal, transitionTo, commitJournal, failJournal, preserveJournal — ≈L497-656; app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt — restoreCostBackup — ≈L836-1300, especially beginJournal, SAFETY_BACKUP_CREATED, and SWAPPING transitions; also importDatabase ≈L1740-1935 and resetDatabase ≈L2718-2807 where the same journal contract is used.
- Defect: fsync/rename failures are swallowed, rename fallback is not checked, and restore proceeds to SWAPPING without knowing whether the safety-backup path and stage are durable.
- Change: Propagate a typed journal durability failure from fsync, temp write, rename, checked fallback copy, success-journal preservation, or failure-journal preservation. Every caller must treat beginJournal/transitionTo failure as a hard operation failure. In restoreCostBackup, require durable SAFETY_BACKUP_CREATED and then durable SWAPPING before closeLiveDatabaseForFileSwap() or any live-file mutation. Apply the same ordering to legacy import/reset transitions. Before any destructive point, abort while the original DB is intact; after a destructive point, enter RESTORE_ROLLING_BACK and then either restart-required after verified rollback or critical after failed/uncertain rollback. Never delete the only journal after a failed success/failure rename.
- Acceptance criteria:
  - Injected fsync failure, temp-write failure, rename failure, and failed fallback copy all abort before live DB swap and leave a durable recovery/critical state.
  - A journal transition that returns failure cannot be followed by SWAPPING, closeLiveDatabaseForFileSwap, or live file rename/copy.
  - A failure to preserve the success or failure journal is surfaced as critical and does not silently delete the active journal.
  - Cancellation still propagates; it is not converted into a journal durability success or swallowed.
  - Existing successful journal round-trip and restore/import/reset ordering remain intact.

### WI-4 — Bank terminal status caller barrier (CA-P-10-001, P1, UNVERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt — syncConnection/persistOutcome — ≈L140-187; BankApiIntegration.kt — syncTransactions — ≈L210-237; BankConnectionDao.kt — updateSyncStatus/updateSyncStatusOnly — ≈L42-50.
- Defect: syncTransactions can return Blocked(RESTORE_BLOCKED), after which persistOutcome unconditionally updates bank_connections.lastSyncStatus to FAILED without a barrier check.
- Change: After toTerminalSyncStatus() returns a status and immediately before either DAO update, call DatabaseWriteBarrier.checkWritesAllowed from persistOutcome using the coordinator operation identity. Catch DatabaseAccessBlockedException at this caller boundary, skip both DAO updates, and return the original typed outcome. Preserve cancellation propagation and the existing secondary-status-write error handling. Do not add a second barrier to the DAO and do not rewrite DatabaseWriteBarrier; do not make BankApiIntegration write status or retry a denied write.
- Acceptance criteria:
  - When the integration returns Blocked(RESTORE_BLOCKED) and the barrier denies at persistence time, neither updateSyncStatus nor updateSyncStatusOnly is called.
  - The coordinator returns the original Blocked outcome and does not convert a barrier denial into success or an untyped exception.
  - Success/partial outcomes still update status and timestamp when the barrier is NORMAL; failure-family outcomes still use updateSyncStatusOnly when NORMAL.
  - A barrier denial that occurs after integration success also skips the status write and returns the success outcome (status persistence remains secondary).
  - BankApiIntegration retains its existing barrier check and typed outcome behavior; no direct DAO write is introduced.

### WI-5 — Reschedule workers after asset rollback (CA-I-01-001, P2, UNVERIFIED)
- Location: app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt — resumeAssetsIncompleteRecovery — ≈L380-401; RestoreMaintenanceMode.kt — reset/exit — ≈L136-166, with WorkerRegistry.scheduleAll reached through the normal exit path at ≈L189-193.
- Defect: A verified rollback from ASSETS_RESTORING calls restoreMaintenanceMode.reset(), which writes NORMAL but does not reschedule workers cancelled at maintenance entry. The process becomes writable with background jobs absent.
- Change: In the successful rollback branch, after journal cleanup and verified DB recovery, use restoreMaintenanceMode.exit(forceRestartRequired = false) (or the exact existing equivalent that commits NORMAL, resets the worker stop flag, and invokes WorkerRegistry.scheduleAll). Keep the journal and mode blocked until the rollback is verified. If mode commit/scheduling fails, leave the operation in a durable restart/critical recovery state instead of returning writable with an unscheduled worker set.
- Acceptance criteria:
  - Successful asset rollback reaches NORMAL only after the worker registry scheduling path is invoked.
  - WorkManager contains the registry’s seven default worker schedules after rollback; the test must assert the scheduling side effect, not only the mode.
  - A failed rollback still enters CRITICAL_RECOVERY_REQUIRED, preserves recovery evidence, and schedules no normal workers.
  - The normal ASSETS_RESTORING completion path still exits to RESTORE_COMPLETE_RESTART_REQUIRED and does not schedule ordinary workers prematurely.
  - Repeated startup recovery is idempotent and does not duplicate unique worker work.

## Tests

- WI-1: update app/src/test/java/com/yourname/expensetracker/data/backup/P7BugFixesTest.kt and DatabaseBarrierTest.kt; add/update app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt for unknown persisted mode and commit failure. Enumerate: every known mode except NORMAL blocks writes; NORMAL admits; unknown mode string, blank mode, mode-read exception, false commit(), thrown commit(); failed transition does not schedule workers; critical state is visible and persists across fresh mode/coordinator instances.
- WI-2: update app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt and add malformed-input cases to app/src/test/java/com/yourname/expensetracker/data/backup/RestoreJournalDurabilityTest.kt. Enumerate: absent file; blank bytes; truncated JSON; invalid JSON; unknown state; missing/invalid required state/identity fields; valid PREPARING, STAGED, SWAPPING, VERIFYING, and ROLLING_BACK; critical mode persistence across a second startup; corrupt bytes retained.
- WI-3: update app/src/test/java/com/yourname/expensetracker/data/backup/RestoreJournalDurabilityTest.kt, app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt, and app/src/test/java/com/yourname/expensetracker/scenarios/BackupRestoreContractTest.kt. Enumerate: successful fsync/rename round-trip; injected fsync exception; temp write exception; renameTo=false with successful fallback; fallback copy/fsync failure; success-journal rename failure; failure-journal rename failure; restore abort before live swap; safety-backup path is present after reread; cancellation propagation; import/reset use the same durable transition ordering.
- WI-4: update app/src/test/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinatorOutcomeTest.kt. Enumerate: blocked outcome with barrier denied; success with barrier allowed; success with barrier denied after integration; partial with barrier allowed; failure-family with barrier allowed; no DAO update on denial; CancellationException propagation; BankApiIntegration remains the only provider path and never writes the DAO.
- WI-5: update app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt and app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt. Enumerate: verified rollback reaches NORMAL and schedules every WorkerRegistry.entries/WorkerSpec.DEFAULTS worker; no duplicate unique work on repeated recovery; pending asset completion reaches restart-required without normal scheduling; failed rollback reaches critical and leaves workers paused; mode commit failure does not expose writable unscheduled state.

Where the current filesystem APIs cannot deterministically inject fsync/rename failure, add only a narrow internal test seam inside RestoreJournal; do not weaken production error handling or use timing-based tests.

## Validation

- Use the repository validation runner only. First run the targeted-unit-test profile for the named backup/journal/startup/bank test classes; then run the compile profile.
- The coder NEVER invokes Gradle directly. Record the runner command, exit code, durable result/log path, and any infrastructure failure. A missing, stale, running, or unknown runner result is not PASS.

## Blast-radius fence

- ALLOWED: app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt, app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt, app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt, app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt, app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt, app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt, app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt, app/src/main/java/com/yourname/expensetracker/data/database/dao/BankConnectionDao.kt, and the specifically named test classes under app/src/test.
- FORBIDDEN: BackupVerifier.kt and semantic verification query behavior (CL-15/P-07-009); DatabaseReadBarrier.kt and getDatabaseStats read admission (CL-15/P-07-002); CompositePrivacyGate.kt and export privacy fail-closed behavior (CL-18/P-08-001/P-07-001); RestoreJournalImporter.kt raw failure-text handling (CL-17/P-07-006); restore/recovery throwable logging and financial payload logging (CL-17/P-07-008/CA-E-04-001); BackupPrivacyMode.kt/CostbackupBundle.kt image contract (CL-20/P-07-007); notification, receipt, recurring, currency, group, and unrelated worker changes; schema/migration files.

## Review gate

- reviewer-strict must inspect the complete diff and verify the state-machine transitions, all non-normal barrier denials, persistence-result checks, corrupt/unknown mode and journal handling, fsync/rename failure propagation, and the no-silent-reset rule.
- The reviewer must specifically confirm that no live DB swap can follow an uncommitted journal transition, that CRITICAL_RECOVERY_REQUIRED survives restart, that persistOutcome performs a caller-level barrier check without changing the barrier contract or adding a DAO bypass, and that asset rollback schedules workers only after verified recovery.
- Review must also confirm cancellation propagation and that the named adjacent clusters remain outside the diff.

## Out of scope

- CA-P-07-001 / CL-18: FailClosed privacy decisions and CompositePrivacyGate cancellation/rethrow/export entry points are handled by the CL-18 spec.
- CA-P-07-002, CA-P-07-006, CA-P-07-007, CA-P-07-008, and CA-P-07-009: database stats read barrier, journal/importer privacy, backup image contract, throwable logging, and semantic verification are owned by CL-15, CL-17, or CL-20.
- CA-P-09-001 and other worker stale-write/drain findings: owned by their Wave 2/3 clusters.
- Any UI redesign, PPTX/React viewer work, staff-grade filters, or unrelated product request is outside this campaign spec.
