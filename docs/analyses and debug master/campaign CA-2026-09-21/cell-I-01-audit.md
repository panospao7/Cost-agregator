# I-01 — Worker/backup integration audit

Date: 2026-09-22
Campaign: CA-2026-09-21
Pinned source: 37601232b9778170c57a656a245b199ab6d7d965
Auditor: direct astra session
Mode: AUDIT, static only; no builds/tests/guards executed.

## Provenance and scope
HEAD short = 37601232; app/config/scripts committed diff from pin is empty. Working-tree drift is docs and agent configuration only. Production source is read-only. Scope: worker DI, scheduler wiring, backup encryption bindings, restore drain/resumption, WorkerRegistry consumers, inventory reconciliation.
Read governing spec §2 and §4; extracted I-01 matrix row; read legal sections Workers / Background Jobs, Backup / Restore, Diagnostics; segment entries 12, 18, 29, 30 and inventory worker section. Shared runtime internals belong to P-09; backup internals belong to P-07; persistence belongs to I-02.

## Coverage ledger (append as examined)
- [x] Source pin and production cleanliness verified.
- [x] Scoped intent docs and engine-row lookup (no wholesale large maps).
- [x] DI/singleton construction.
- [x] Scheduling consumers and inventory.
- [x] Restore drain/resume and startup integration.
- [x] Relevant tests/guards and known-debt reconciliation.
- [x] All 15 defect classes disposition.

### Coverage increment 1
Full production reads 1–11 (paths relative to app/src/main/java/com/yourname/expensetracker/): di/WorkerModule.kt (1–42), di/BackupRepositoryModule.kt (1–30), di/PrivacyModule.kt (1–106), domain/workers/WorkerRegistry.kt (1–144), WorkerLeaseRegistryImpl.kt (1–120), data/backup/MaintenanceOperationRunner.kt (1–71), RestoreMaintenanceMode.kt (1–232), domain/workers/WorkerSpecScheduler.kt (1–386), WorkerSpec.kt (1–130), startup/AppStartupCoordinator.kt (1–747), MainApplication.kt (1–55). Scheduler output was re-read in bounded chunks after truncation.
- DI: SingletonComponent binds both WorkerLeaseRegistry and WorkerDrainController to the @Singleton WorkerLeaseRegistryImpl. RestoreMaintenanceMode uses Lazy<WorkerLeaseRegistry>, breaking the barrier/maintenance dependency cycle; test-only no-op constructor is not @Inject.
- Registry has seven entries matching DEFAULTS; ten names include three dynamic one-shot workers. Startup and maintenance are the two scheduleAll callers; PrivacySettingsRepositoryImpl consumes entries.
- Normal maintenance exit schedules then clears stop; restart-required exit keeps writes blocked; startup clears non-critical recovered modes synchronously. AssetsIncomplete branches launch on applicationScope and return before normal startup hooks.
- Existing candidates excluded: CA-P-09-003 drain wall clock; CA-P-07-003 persistence decoding/commit; CA-P-07-005 journal durability; CA-P-07-008 recovery throwable logging; NEW-P9-007 version/enqueue atomicity; MIT-070/MIT-017 schedule diagnostics.
- Unpromoted leads: the extraction helper's default `BackupEncryptionService()` is a production singleton bypass, but the service is stateless at the pin; existing asset collision behavior is explicitly covered by the fail-closed test contract.

## Findings

### CA-I-01-001
ID: CA-I-01-001
Title: Asset-recovery rollback re-enables writes without re-scheduling cancelled workers
Defect class: 13 (wiring / dead code) and 10 (worker hygiene)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt`, `checkRestoreJournal`, lines 125-133, returns immediately for `AssetsIncomplete` after launching `handleAssetsIncompleteRecovery`; `initialize`, lines 47-59, then observes the still-active maintenance mode and skips `scheduleStartupWork`. In the asynchronous rollback branch `resumeAssetsIncompleteRecovery`, lines 380-401, a corrupt swapped DB recovered from a safety source calls `restoreMaintenanceMode.reset()` and returns. `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt`, `reset`, lines 157-163, only writes NORMAL mode and does not call `WorkerRegistry.scheduleAll`; only `exit`'s NORMAL branch, lines 136-151, schedules workers. Therefore a successful rollback from this startup path leaves the seven workers cancelled by the earlier maintenance entry while writes are NORMAL; no later scheduling call occurs in this process.
Impact path: process starts with an `ASSETS_RESTORING` journal and the swapped DB fails verification -> safety backup recovery succeeds -> async resume resets mode to NORMAL -> retention, matching, backfill, reminders, and briefing workers remain unscheduled until a later process start, producing stale background state despite a recovered writable database.
Caller trace: `MainApplication.onCreate` -> `AppStartupDelegate.initialize` -> `AppStartupCoordinator.initialize/checkRestoreJournal` -> `resumeAssetsIncompleteRecovery` -> `RestoreMaintenanceMode.reset`; `WorkerRegistry.scheduleAll` has startup and normal `exit` callers, but none on this rollback branch.
Existing tests/guards: `AppStartupCoordinatorRecoveryTest` test `ASSETS_RESTORING journal with corrupt swapped DB rolls back from safety backup to NORMAL` (around lines 491-517) asserts the mode and DB recovery, but does not assert worker scheduling; `WorkerRestoreRegressionTest` covers worker gating generally, not this async rollback-to-NORMAL transition. No build/test run performed.
Cross-cell impact: P-07 restore recovery correctness; P-09 worker runtime; I-01 WorkerRegistry/RestoreMaintenanceMode integration.
Old-ID cross-refs: none found; P7-002 is the previously tracked infinite ASSETS_RESTORING lock and is not restated (this is the post-recovery worker-resumption gap).

## Defect-class disposition (all 15)
1 Legal path: no new direct DAO/coordinator bypass found in the inspected integration files; restore-internal DAO use is owned by P-07.
2 Barrier: maintenance runner enters mode and drains through the injected singleton; no new bypass promoted.
3 Atomicity/TOCTOU: lease/drain ordering and scheduler version persistence checked; no new finding.
4 Idempotency/duplicates: registry unique names and restore resume ledger checked; no new finding beyond tracked P-07 asset semantics.
5 Cancellation: the `CostbackupBundle.extract` `runCatching` lead was checked; the function is non-suspending/blocking and no concrete `CancellationException` source was found, so it is not promoted.
6 Side-effect timing: scheduling occurs after successful enqueue; restore journal sequencing delegated to P-07; no new finding.
7 Money/currency: not applicable to worker/backup integration; no arithmetic path inspected.
8 Time: injected TimeProvider use checked; wall-clock drain issue is CA-P-09-003 and excluded.
9 Privacy: encryption service and privacy gate bindings traced; no new privacy finding.
10 Worker hygiene: CA-I-01-001; all guarded worker wiring otherwise traced.
11 Data integrity: no new FK/schema issue; restore database integrity work is P-07/I-02.
12 Error handling: no new issue; scheduler failures are tracked MIT-070/MIT-017 and excluded.
13 Wiring/dead code: CA-I-01-001; BackupEncryptionService production extraction default is a singleton bypass lead, but current service is stateless and no runtime defect was promoted.
14 Test correctness: identified coverage gaps above; no tautological test finding promoted.
15 Fix-regression: no diff-scoped production delta exists after the pinned commit; no separate regression finding.

## Completion and limits
Bounded static audit completed for the I-01 integration scope within the 20-file read budget. Primary DI, registry/scheduler, maintenance/drain, startup recovery, encryption service usage, and relevant tests/remediation records were read. No production files were edited; no builds, tests, or guards were run. Findings are discovery findings pending independent verifier review.

## Final provenance
- Agents invoked: none (direct astra session as requested).
- Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`; `git rev-parse --short HEAD` = `37601232`.
- Production diff guard: `git diff --stat 37601232..HEAD -- app config scripts` empty.
- Timestamp: 2026-09-22 Europe/Bucharest.
