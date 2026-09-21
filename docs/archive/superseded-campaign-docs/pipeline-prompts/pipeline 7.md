Here is the **Pipeline 7 master prompt pack** using the same directive.

<pipeline7-master-prompts.md>
# Pipeline 7 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P7 — Backup / Restore**

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
- P7 issue doc: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_7_CONSOLIDATED_ISSUES.md
- P7 implementation plan: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/pipelines%20issues%20implementantion%20plan/PIPELINE_7_IMPLEMENTATION_PLAN.md
- Backup/restore barrier contract: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/backup-restore-barrier-contract.md
- DB write ownership: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/DB_WRITE_OWNERSHIP.md
- Codebase segments: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/CODEBASE_SEGMENTS.md
- Architecture folder: https://github.com/panospao7/Cost-agregator/tree/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture

Important context:
- P7 is **Backup / Restore**.
- Core architecture segment: **Segment 18 — Export & Backup**.
- Cross-cutting segments:
  - Segment 12 — Startup & Background Runtime
  - Segment 28 — Security / Privacy
  - Segment 29 — Debug & Diagnostics
  - Segment 30 — Dependency Injection
  - Segment 4 — Receipt assets / scanned receipts
  - Segment 9 — Core expense data being preserved
  - Segment 10 / 8 / 2 / 6 — semantic verification outputs.
- P7 docs say the pipeline is **RED** after universal fixes.
- P7 docs/trackers are internally inconsistent in places:
  - P7 issue doc header and summary counts disagree.
  - Some items marked fixed in one section still appear in open PR lists.
  - Architecture docs may be newer than the consolidated issue tracker.
- Therefore: **code at the target SHA is the source of truth. Validate every tracker claim.**

---

## Prompt A — P7 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin, Room, SQLite, backup/restore, crash-recovery, privacy, WorkManager, and data-integrity architecture reviewer.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P7 — Backup / Restore

Mode:
Review only + issue discovery + validation of already-fixed claims.
Do NOT implement code changes unless explicitly asked later.
You may propose exact fixes and tests, but this run is an audit/debug review.

Checkout command:
git clone https://github.com/panospao7/Cost-agregator.git
cd Cost-agregator
git checkout 83b798e849b4408b2bf683f52cb2746d37f7af16

If the checkout is dirty or not exactly at this SHA, stop and report it.

## 2. Pipeline scope

Audit Pipeline 7 end-to-end:

### Backup/export scope
- production `.costbackup` creation,
- legacy raw `.db` / `.enc` export,
- debug-only raw export restrictions,
- encrypted bundle creation,
- manifest creation,
- checksum generation,
- receipt asset inclusion/exclusion,
- privacy-mode handling,
- export redaction/anonymization,
- WAL checkpoint / snapshot consistency,
- SQLite backup API or snapshot fallback,
- `VACUUM INTO` / drained file-copy behavior,
- write freeze during backup,
- worker drain before snapshot,
- backup verification before returning success,
- operation-run diagnostics.

### Restore/import scope
- `.costbackup` restore,
- legacy `.db` import,
- staged import,
- schema migration during import,
- pre-swap verification,
- destructive live DB replacement,
- stale Room singleton invalidation/reopen,
- post-swap verification,
- receipt asset restore,
- asset path updates,
- restore-internal write scope,
- rollback on failure,
- crash during every restore phase,
- startup recovery after restore crash,
- restart-required / dismiss flow,
- reset database flow.

### Barrier/maintenance scope
- `RestoreMaintenanceMode`,
- `AppOperationalState`,
- `DatabaseWriteBarrier`,
- `DatabaseReadBarrier`,
- `DatabaseReadBarrierFlowExt`,
- `MaintenanceOperationRunner`,
- `RestoreInternalWriteScope`,
- worker drain / pause / resume,
- global vs caller-by-caller write barrier,
- read blocking during restore,
- maintenance-safe diagnostics.

### Journal/diagnostics scope
- `RestoreJournal`,
- `RestoreJournalImporter`,
- `RestoreDiagnosticsSink`,
- operation-run ledger import,
- journal durability / fsync,
- event ordering,
- append race behavior,
- success/failure journal persistence,
- idempotent journal import,
- privacy-safe diagnostics.

### Verification scope
- `BackupVerifier`,
- table-tier definitions,
- manifest completeness,
- exact row counts for Tier 1,
- validity checks for Tier 2,
- optional tables for Tier 3,
- semantic verification beyond counts,
- privacy audit event preservation,
- dashboard / analytics / budget equivalence if claimed.

### Cross-pipeline dependencies
- Every table family listed in Room must be either preserved or intentionally excluded.
- Receipt images/assets from P3/P4 receipt flows must remain consistent with DB rows.
- Privacy audit events from P8 must not be silently dropped.
- Workers from P1/P3/P4/P7/P8/P10/P11 must be drained and rescheduled correctly.
- P5 dashboard/analytics and P6 budget/forecast outputs should not silently change after restore if semantic equivalence is claimed.
- Import/export from P12 overlaps with P7 backup/restore contracts.

Read first:
- `docs/analyses and debug master/PIPELINE_7_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_7_IMPLEMENTATION_PLAN.md`
- `docs/backup-restore-barrier-contract.md`
- `docs/DB_WRITE_OWNERSHIP.md`
- `docs/DATABASE_BASELINE_POLICY.md`
- relevant universal implementation-plan docs if referenced by P7.

The master tracker says the methodology was:
Scout → Planner → Coder → Tester → Reviewer → Debugger.

Follow that methodology:
1. Scout files and flows.
2. Plan review coverage.
3. Inspect code deeply.
4. Inspect tests.
5. Compare with architecture.
6. Debug mismatches.
7. Produce evidence-backed findings.

Shared contracts must be validated before pipeline-local conclusions.

## 3. Architecture docs to read

Always read:
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`

Conditional docs to read:
- UI pipeline:
  - `COMPREHENSIVE_UI_MAP.md`
  - `VIEWMODEL_INJECTION_MAP.md`
  - `route-viewmodel-map.md`
- Privacy/diagnostics:
  - `PRIVACY_UI_ARCHITECTURE.md`
  - `SENSITIVE_DIAGNOSTICS_POLICY.md`
- DB/restore/import/export:
  - `DATABASE_BASELINE_POLICY.md`
  - `DB_WRITE_OWNERSHIP.md`
  - `backup-restore-barrier-contract.md`
  - `expense-mutation-inventory.md`

For P7 specifically, pay special attention to:
- Segment 18 — Export & Backup.
- Segment 12 — Startup & Background Runtime.
- Segment 28 — Security & API Key Management / Privacy.
- Segment 29 — Debug & Diagnostics.
- Segment 30 — DI.
- `backup-restore-barrier-contract.md`.
- `DB_WRITE_OWNERSHIP.md`.
- `DATABASE_BASELINE_POLICY.md`.
- AppDatabase schema version and entity/DAO registration.

## 4. Build a pipeline file inventory

Do not rely only on this seed list.
Use `rg`, import graph, Hilt map, DAO map, callers/callees, and tests to build the real inventory.

### Backup/restore repository and interfaces
- `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseOperationResults.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/backup/BackupPrivacyMode.kt`

### Backup infrastructure
- `app/src/main/java/com/yourname/expensetracker/data/backup/AppOperationalState.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/CostbackupBundle.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/DataStoreMaintenanceSafeDiagnosticSink.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseAccessModels.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrier.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrierFlowExt.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceOperationRunner.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSink.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDatabaseOpener.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDiagnosticsSink.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreInternalWriteScope.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/SqliteSnapshotCreator.kt`
- `app/src/main/java/com/yourname/expensetracker/data/backup/TimberMaintenanceSafeDiagnosticSink.kt`

### Privacy / encryption / redaction
- `app/src/main/java/com/yourname/expensetracker/data/privacy/BackupEncryptionService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyGate.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyCapability.kt`
- `app/src/main/java/com/yourname/expensetracker/data/security/SecureKeyStorage.kt`
- `app/src/main/java/com/yourname/expensetracker/data/security/BankTokenCipher.kt` if backup touches encrypted tokens.

### DB / schema / migration
- `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt`
- all exported Room schema JSON if present.
- every entity and DAO table listed in `BackupVerifier.allTableNames()`.
- any table tiers in `BackupVerifier`.

### Receipt assets
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptAssetStore.kt`
- `ScannedReceipt` entity/DAO.
- receipt image path update code.
- receipt asset restore/update tests.

### Startup/recovery
- `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/startup/AppStartupDelegate.kt`
- `app/src/main/java/com/yourname/expensetracker/startup/AppBackgroundLifecycleObserver.kt`
- `app/src/main/java/com/yourname/expensetracker/MainApplication.kt`
- `MainActivity.kt` / app-shell code if operational-state lock UI is involved.

### Workers / WorkManager
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerDrainController.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/NoOpWorkerDrainController.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLease.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistry.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunContext.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpecScheduler.kt`
- every Worker in `WorkerRegistry.DEFAULTS`.
- `app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt`
- all service workers found by `rg -n "class .*Worker|CoroutineWorker|ListenableWorker"`.

### Diagnostics
- `app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt`
- `OperationRun`, `OperationRunEvent`, DAOs.
- pipeline diagnostic sink/repository.
- maintenance-safe diagnostic sinks.
- journal importer path.

### Export/import overlap
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/**`
- P12 import/export classes that call backup/restore or file import.

### Hilt modules
Review all Hilt modules that provide:
- backup repository,
- database,
- DAOs,
- privacy gate/settings,
- encryption/security,
- receipt asset store,
- worker drain/registry/scheduler/logger,
- diagnostics,
- dispatchers,
- startup dependencies.

Likely seeds:
- `app/src/main/java/com/yourname/expensetracker/di/BackupRepositoryModule.kt`
- `DatabaseModule.kt`
- `DaoModule.kt`
- `PrivacyModule.kt`
- `SecurityModule.kt`
- `DiagnosticsModule.kt`
- `DispatchersModule.kt`
- `WorkerModule.kt`
- `ExportModule.kt`
- `RetentionModule.kt`

### UI
If backup/restore UI exists, include:
- backup/restore screen,
- export options screen,
- backup/restore ViewModel,
- debug screen raw export entrypoint,
- app-shell operational-state lock,
- restart-required banner,
- restore result dialogs,
- reset database UI.

Search:
- `rg -n "Backup|Restore|costbackup|exportDatabase|restoreCostBackup|resetDatabase|restartRequired|maintenance" app/src/main/java/com/yourname/expensetracker/ui`
- `rg -n "BackupRestoreViewModel|ExportOptionsScreen|DebugScreen|dismissRestartRequired"`

### Tests
Search the whole repo:
- `rg -n "Backup|Restore|Maintenance|Journal|costbackup|DatabaseBarrier|Snapshot|Reset|Room|WorkerDrain|PrivacyAudit" app/src/test app/src/androidTest`

Known visible test seeds:
- `app/src/test/java/com/yourname/expensetracker/data/backup/AppOperationalStateTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/AssetRestoreAtomicityTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierManifestTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/CostbackupBundleLimitsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/DatabaseBarrierTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/ExportReadBarrierTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/MaintenanceOperationRunnerTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/MaintenanceSafeDiagnosticSinkTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/P7BugFixesTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/RestoreJournalDurabilityTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/backup/RestoreJournalImporterFailureTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt`
- `app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt`

Do not stop at these. Search the entire repo.

## 5. Code-reading rules

Mandatory:
- Do not trust docs over code.
- If docs and code disagree, report the mismatch.
- Do not review only filenames; open implementation and tests.
- Trace actual runtime flow, not package structure.
- Search direct and indirect callers.
- Include cross-pipeline dependencies.
- Mark uncertainty clearly.
- Verify Hilt-injected runtime path, not just constructor signatures.
- Check whether public methods bypass maintenance/write/read barriers.
- Check whether tests assert real crash/data-integrity invariants.
- If tracker says fixed/open/TODO, validate against code at this SHA.
- For backup/restore, treat “works on happy path” as insufficient.
- Model crash at every phase boundary:
  - before journal,
  - after journal,
  - after maintenance entered,
  - after staging copy,
  - after safety backup,
  - after live DB swap,
  - during asset restore,
  - during rollback,
  - during journal success/failure rename.

Use searches like:
- `rg -n "restoreCostBackup|createCostBackup|importDatabase|exportDatabase|resetDatabase"`
- `rg -n "RestoreMaintenanceMode|DatabaseWriteBarrier|DatabaseReadBarrier|MaintenanceOperationRunner|RestoreInternalWriteScope"`
- `rg -n "beginJournal|appendEvent|transitionTo|failJournal|completeJournal|JournalState|CRITICAL_RECOVERY_REQUIRED"`
- `rg -n "VACUUM INTO|checkpoint|wal|shm|copyFile|replaceDatabaseFiles|openFreshDatabase|close\\("`
- `rg -n "CostbackupBundle|manifest|checksum|ZipInputStream|ZipEntry|FileInputStream|use \\{"`
- `rg -n "WorkerDrain|pauseAllWorkers|scheduleAllWorkers|WorkerRegistry.DEFAULTS|cancelAllWork"`
- `rg -n "PrivacyCapability|RAW_DATABASE_EXPORT|ENCRYPTED_BACKUP|RAWBACKUP_EXPORT|ExportAnonymizer|privacy_audit"`
- `rg -n "OperationRun|OperationRunEvent|RestoreDiagnosticsSink|MaintenanceSafeDiagnosticSink"`
- `rg -n "fallbackToDestructiveMigration|fallbackToDestructiveMigrationOnDowngrade"`
- `rg -n "rawQuery\\(\"SELECT COUNT|SELECT COUNT\\(\\*\\) FROM|countRowsFromSourceTable"`
- `rg -n "catch \\(e: Exception\\)|catch \\(t: Throwable\\)|CancellationException"`

## 6. Universal contracts to verify

Audit these for P7:

1. Restore/write barrier:
   - every DB/file mutation enters correct maintenance mode,
   - all app writes blocked in BACKUP_EXPORTING and RESTORE modes,
   - no caller-by-caller gaps for repository/DAO writes,
   - restore-internal writes are scoped and auditable,
   - UI and background flows observe operational state.

2. Worker guard, worker drain, run logging:
   - destructive DB operations drain workers before mutation,
   - workers are paused/cancelled/rescheduled using spec/registry,
   - worker run logging remains consistent,
   - worker execution guard blocks writes during restore,
   - drain timeout aborts operation.

3. Privacy/redaction/raw-storage policy:
   - release build cannot raw-export `.db`,
   - raw export is debug-only and gated,
   - encrypted backup requires privacy gate consent,
   - backup privacy mode matches inclusion/redaction behavior,
   - raw OCR/notification/email content is redacted when required,
   - receipt images are excluded when redacted mode requires it,
   - privacy audit events are preserved or intentionally classified with evidence.

4. Money/currency normalization:
   - not directly owned by P7, but restored data must preserve currency/exchange-rate tables exactly enough for P5/P6 outputs.
   - semantic verification should catch currency/rate table loss if claimed.

5. Transaction lifecycle ownership:
   - restore/import must not create live expense mutations through illegal DAO paths unless it is full DB file replacement under maintenance.
   - no post-restore “repair” writes bypass lifecycle except explicit restore-internal scope.

6. Receipt lifecycle/link ownership:
   - receipt assets and DB rows remain consistent,
   - asset restore is atomic with DB restore or recoverable,
   - ScannedReceipt paths are updated only through allowed restore-internal path.

7. Recurring planned/actual reconciliation:
   - recurring tables, planned expenses, reminder deliveries, lifecycle events are preserved.
   - P4 state must not be silently regenerated incorrectly after restore.

8. Diagnostics/drop reasons/events:
   - terminal success/failure events are durable,
   - post-swap diagnostics do not write through stale Room,
   - journal import is idempotent,
   - diagnostic failures do not corrupt restore,
   - cancellation propagates.

9. Import/export schema/roundtrip:
   - `.costbackup` bundle restores current schema correctly,
   - old `.db` import follows baseline policy,
   - unsupported schema rejected safely,
   - Room migrations are not used below supported baseline except rescue path,
   - manifest completeness fail-closes before destructive swap.

10. DAO conflict handling and timestamps:
   - not a normal DAO mutation pipeline, but verify operation-run import conflict handling and idempotency.
   - if restore writes asset path rows, verify transaction, timestamps, and barriers.

## 7. P7-specific invariants to audit

### Backup creation
Check:
- `createCostBackup()` enters `BACKUP_EXPORTING` before WAL checkpoint/snapshot.
- Workers are drained before snapshot.
- `restoreMaintenanceMode.exit()` always runs on success/failure/cancellation as intended.
- Snapshot uses `SqliteSnapshotCreator`, `VACUUM INTO`, SQLite backup API, or a documented safe fallback.
- File copy fallback cannot race writes.
- Snapshot counts are collected strictly for required tables.
- Required table count failure aborts backup.
- Manifest completeness and table tiers are correct.
- Redaction runs before bundling if requested.
- Receipt images are included only when permitted by privacy mode.
- OperationRun success/failure is finalized on every early return.
- `CancellationException` is rethrown.

### Legacy raw export
Check:
- release builds cannot call raw export.
- raw export is debug-only and not exposed in production UI.
- raw export is privacy-gated.
- maintenance mode exits on every path.
- WAL checkpoint and file copy are consistent.
- raw `.db` artifacts are not written to public storage unexpectedly.
- legacy public backup warning is safe.

### Legacy `.db` import
Check:
- import enters maintenance before live file changes.
- import creates journal before destructive operations.
- source DB is copied to staging first.
- source schema/version is validated before swap.
- unsupported old DB follows rescue/import policy, not unsafe Room migration.
- crash mid-import leaves live DB recoverable.
- workers are drained before swap.
- post-import verification uses fresh Room, not stale injected instance.
- rollback restores safety backup or enters `CRITICAL_RECOVERY_REQUIRED`.

### `.costbackup` restore
Check:
- journal created before maintenance events that must be durable.
- maintenance entered and workers drained before staging/swap.
- password/corrupt bundle failures exit maintenance without touching live DB.
- extraction uses limits and closes streams.
- manifest completeness checked before any destructive swap.
- staged DB verified before swap.
- safety backup created and fsynced.
- live DB/WAL/SHM swap is atomic enough and recoverable.
- stale Room instance is closed/reopened or process restart is enforced.
- post-swap verification uses fresh DB.
- terminal events are written even after Room is unsafe.
- restore success state and restart-required UI behavior match architecture.
- restore does not leave app permanently blocked unless required.

### Receipt asset restore
Check:
- assets extract to temp location first.
- assets are restored atomically after DB restore succeeds.
- crash during asset restore is recoverable/resumable via journal.
- rollback does not delete valid existing assets incorrectly.
- orphan files and broken DB paths are handled.
- restore-internal writes are scoped and barrier-safe.

### Restore journal
Check:
- `beginJournal`, `transitionTo`, `appendEvent`, `writeJournal`, `completeJournal`, `failJournal` are durable.
- writes use temp file + fsync + atomic rename where required.
- `appendEvent` is concurrency-safe.
- state transitions are monotonic/legal.
- `enterCriticalRecoveryRequired` is atomic.
- failed journal cannot be lost before startup can recover.
- success/failure journal import is idempotent.

### Maintenance/write/read barrier
Check:
- modes map correctly:
  - NORMAL: reads/writes allowed.
  - BACKUP_EXPORTING: writes blocked, snapshot reads allowed.
  - RESTORE_*: writes blocked, most reads blocked.
  - ASSETS_RESTORING: only restore-internal writes allowed.
  - RESTORE_COMPLETE_RESTART_REQUIRED: writes blocked or explicitly dismissable per contract.
  - CRITICAL_RECOVERY_REQUIRED: fail-closed across restarts.
- global barrier is actually global or document caller-by-caller gaps.
- all repository write entrypoints call `DatabaseWriteBarrier`.
- all Flow reads that should be blocked use read barrier.

### Startup crash recovery
Check:
- startup detects active journal.
- safety backup copy path verified.
- integrity and foreign-key checks run.
- fresh

:warning: The provider stream ended early, so this response may be incomplete.