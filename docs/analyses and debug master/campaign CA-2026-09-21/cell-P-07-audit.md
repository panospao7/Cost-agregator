# Cell P-07 Audit - Backup, Restore, Recovery

## Provenance
- Date: 2026-09-21
- Pinned source commit: `37601232b9778170c57a656a245b199ab6d7d965` (`git rev-parse --short HEAD` = `37601232`)
- Session: direct astra session
- Cell: P-07
- Mode: AUDIT, static only
- Production source status: `git diff --stat 37601232..HEAD -- app config scripts` empty; docs/agent artifacts may drift.
- Status: PARTIAL primary legal-path audit; large repository helpers outside the listed mutation/IO paths were not read wholesale before the context budget limit.

## Governing scope extracted
- Governing spec: section 2 known-debt/intended-behavior rules; section 4 defect classes, finding schema, and severity.
- Coverage matrix P-07 row and its legal-path headings and engine rows.
- Legal path sections: Backup / Restore; Workers / Background Jobs; Privacy / Cloud AI; Diagnostics.
- Segment sections: Segment 12 Startup & Background Runtime; Segment 18 Export & Backup; Segment 28 Security & API Key Management; Segment 29 Debug & Diagnostics.
- Known IDs excluded: FRESH-P7-001..011 and P7-P1-05 (deferred by design). No finding below restates them.

## Coverage checklist
- [x] Pin and production diff rechecked.
- [x] Governing spec sections 2 and 4 read.
- [x] COVERAGE_MATRIX.md P-07 row read.
- [x] LEGAL_PATHS.md Backup / Restore, Workers / Background Jobs, Privacy / Cloud AI, and Diagnostics sections extracted.
- [x] CODEBASE_SEGMENTS.md segments 12, 18, 28, and 29 extracted.
- [x] Backup/restore repository entry points, snapshot, restore, reset, and safety-backup paths traced.
- [x] RestoreMaintenanceMode, DatabaseReadBarrier, DatabaseWriteBarrier, MaintenanceOperationRunner, and WorkerLeaseRegistry traced.
- [x] RestoreJournal state machine, persistence, startup recovery, and journal importer traced.
- [x] BackupEncryptionService, CostbackupBundle, BackupPrivacyMode, and BackupVerifier contract paths checked.
- [x] Production callers and relevant tests/guards searched.
- [x] All 15 defect classes considered; class matrix is recorded below.
- [x] Production code remained read-only; no builds/tests/guards run in AUDIT static-only mode.

## Covered files
- `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` - backup export, costbackup restore, legacy import, safety backup, reset, stats, and caller-facing entry points.
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt` - journal model, atomic write, read/recovery state machine, failure preservation.
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt` - failure journal to durable operation ledger.
- `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt` - persisted mode and worker pause/resume.
- `app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrier.kt` and `DatabaseWriteBarrier.kt` - read/write admission rules.
- `app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt` - integrity, FK, count, and semantic-orphan verification.
- `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt` - restore/startup worker scheduling registry.
- `app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceOperationRunner.kt` and `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt` - drain and lease behavior.
- `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt` - crash recovery, mode reset, and journal import callers.
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt`, `CompositePrivacyGate.kt`, and `PrivacyCapabilityHandlingPolicy.kt` - fail-closed gate contract.
- `app/src/main/java/com/yourname/expensetracker/data/privacy/BackupEncryptionService.kt`, `app/src/main/java/com/yourname/expensetracker/data/backup/CostbackupBundle.kt`, and `app/src/main/java/com/yourname/expensetracker/domain/backup/BackupPrivacyMode.kt` - envelope and bundle options.
- `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt` and `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt` - user entry points.

## Findings

### CA-P-07-001
ID: CA-P-07-001  
Title: Costbackup export ignores a fail-closed privacy decision  
Defect class: 9 (Privacy)  
Severity: P1  
Evidence: `DatabaseBackupRepositoryImpl.kt`, `runCostBackupExport`, lines 593-615 at the pinned commit checks only `encryptedDecision is PrivacyDecision.Denied`; `PrivacyDecision.kt`, `blocksExecution`, lines 17-19 defines both `Denied` and `FailClosed` as blocking; `CompositePrivacyGate.kt`, lines 57-65 returns `FailClosed` for an unhandled gate-handled capability.  
Impact path: `BackupRestoreViewModel.createCostBackup` -> `DatabaseBackupRepositoryImpl.createCostBackup` -> `runCostBackupExport`; a gate failure or missing handler for `ENCRYPTED_BACKUP` still enters maintenance, snapshots, and writes the encrypted bundle instead of failing closed.  
Caller trace: `BackupRestoreViewModel.kt:108` -> `DatabaseBackupRepositoryImpl.kt:528-533` -> `DatabaseBackupRepositoryImpl.kt:593-615`.  
Existing tests/guards: `BackupRestoreViewModelPrivacyDenialTest`, `BackupPrivacyGateOwnershipTest`, and `PrivacyCapabilityHandlingPolicyProductionTest` cover denied/ownership wiring; no repository test asserts `FailClosed` blocks this path.  
Cross-cell impact: P-08 privacy gate semantics and P-18 export/backup policy.  
Old-ID cross-refs: none.

### CA-P-07-002
ID: CA-P-07-002  
Title: Backup repository database stats bypass the read barrier  
Defect class: 2 (Barrier violation)  
Severity: P1  
Evidence: `DatabaseBackupRepositoryImpl.kt`, constructor lines 69-95 has no `DatabaseReadBarrier`; `getDatabaseStats`, lines 2628-2645, calls four DAOs directly and converts any exception to zero-valued stats; `DatabaseReadBarrier.kt`, lines 15-32, requires an explicit policy check before reads.  
Impact path: `BackupRestoreViewModel.loadLastBackupInfo` (and the debug stats caller) -> `getDatabaseStats` can read the stale/closed Room singleton during restore/maintenance, or hide a blocked read as zero counts, violating the read admission contract.  
Caller trace: `BackupRestoreViewModel.kt:67-70` -> `DatabaseBackupRepositoryImpl.getDatabaseStats`.  
Existing tests/guards: `DatabaseBarrierTest` and `ExportReadBarrierTest` exercise the barrier and export readers; `BackupRestoreViewModelTest` does not assert repository read-barrier admission, and no architecture guard covers this constructor/call site.  
Cross-cell impact: every restore mode and P-12/P-18 UI/diagnostics that consumes backup stats.  
Old-ID cross-refs: none.

### CA-P-07-003
ID: CA-P-07-003  
Title: Maintenance mode persistence and decoding fail open to NORMAL  
Defect class: 2 (Barrier violation)  
Severity: P0  
Evidence: `RestoreMaintenanceMode.enter`, lines 103-106, calls `writeMode`; `writeMode`, lines 206-213, ignores the Boolean result of synchronous `SharedPreferences.commit()` and immediately publishes the in-memory mode; `readMode`, lines 196-203, maps an invalid persisted value to `Mode.NORMAL`; `DatabaseWriteBarrier.kt:15-23` obtains the mode by calling `currentMode()`/`readMode()`.  
Impact path: `MaintenanceOperationRunner.enterAndDrain` from backup/restore/reset -> failed preference commit or corrupted mode -> the write barrier reads `NORMAL` and admits production writes while a destructive operation is active.  
Caller trace: `DatabaseBackupRepositoryImpl` backup/restore/reset entry points -> `MaintenanceOperationRunner.enterAndDrain`/`runExclusive` -> `RestoreMaintenanceMode.enter` -> `writeMode` -> `DatabaseWriteBarrier.checkWritesAllowed`.  
Existing tests/guards: `P7BugFixesTest`, `DatabaseBarrierTest`, and `WorkerRestoreRegressionTest` cover successful mode transitions, not a false `commit()` result or invalid persisted mode.  
Cross-cell impact: all lifecycle writers (P-01 through P-04, P-09 through P-12) share `DatabaseWriteBarrier`; this is a restore/write bypass.  
Old-ID cross-refs: none.

### CA-P-07-004
ID: CA-P-07-004  
Title: Corrupt or unknown restore journals are treated as no-action/preparing  
Defect class: 2 (Barrier violation) and 12 (Error handling)  
Severity: P0  
Evidence: `RestoreJournal.JournalEntry.fromJson`, lines 108-116, maps an unknown state to `PREPARING`; `readJournal`, lines 482-490, catches any read/parse exception and returns null; `checkAndRecover`, lines 695-713, maps null to `NoAction` and `PREPARING` to cleanup/deletion; `AppStartupCoordinator.checkRestoreJournal`, lines 114-120 and 186-200, treats `NoAction` as normal startup and resets transient modes to `NORMAL`.  
Impact path: startup recovery during `SWAPPING`/`VERIFYING` with a truncated, unreadable, or unknown-state journal -> recovery is skipped or the journal is deleted -> persisted maintenance mode is reset and writes can resume against an unknown live DB.  
Caller trace: `AppStartupCoordinator.checkRestoreJournal` -> `RestoreJournal.checkAndRecover` -> `readJournal`/`JournalEntry.fromJson`.  
Existing tests/guards: `RestoreJournalTimeProviderTest` and `AppStartupCoordinatorRecoveryTest` cover valid journal states and recovery outcomes; no malformed, unreadable, or unknown-state fixture asserts a critical fail-closed result.  
Cross-cell impact: startup/runtime (P-09), diagnostics (P-12), and every database writer after a crash.  
Old-ID cross-refs: none.

### CA-P-07-005
ID: CA-P-07-005  
Title: Restore journal write failures are swallowed before destructive transitions  
Defect class: 2 (Barrier violation) and 12 (Error handling)  
Severity: P0  
Evidence: `RestoreJournal.writeJournal`, lines 497-520, catches every exception from directory creation, fsync, and rename and only logs it; `DatabaseBackupRepositoryImpl.restoreCostBackup`, lines 851-855, 905-910, and 1056-1064, calls `beginJournal`/`transitionTo` and proceeds to `SWAPPING` without checking whether the journal was durably written.  
Impact path: restore starts -> disk-full/permission/fsync/rename failure leaves no durable state or safety-backup path -> process death during swap has no journal for startup recovery, allowing the normal-mode path to continue against a partial/unknown database.  
Caller trace: `DatabaseBackupRepositoryImpl.restoreCostBackup` -> `RestoreJournal.beginJournal`/`transitionTo` -> `writeJournal`.  
Existing tests/guards: `RestoreJournalDurabilityTest` covers successful fsync/round-trip behavior; no injected write/rename failure verifies a thrown or fail-closed result before swap.  
Cross-cell impact: restore recovery, safety-backup ordering, and startup fail-closed behavior in P-09/P-12.  
Old-ID cross-refs: none.

### CA-P-07-006
ID: CA-P-07-006  
Title: Reset failure exception text is persisted and imported without sanitization  
Defect class: 9 (Privacy)  
Severity: P0  
Evidence: `DatabaseBackupRepositoryImpl.resetDatabase`, lines 2797-2805, passes `e.message` directly to `restoreJournal.failJournal`; `RestoreJournal.failJournal`, lines 624-628, stores that value in the failure journal; `RestoreJournalImporter.importLastFailureJournalIfPresent`, lines 173-190, copies `entry.error` directly into `OperationRun.errorSummary`.  
Impact path: debug reset failure -> arbitrary SQL/file/URI/exception text is written to the failure JSON and then to the Room operation ledger, where it is queryable and may be included in diagnostics/backups.  
Caller trace: `DebugViewModel.resetDatabase` -> `DatabaseBackupRepositoryImpl.resetDatabase` -> `RestoreJournal.failJournal` -> `RestoreJournalImporter` on the next healthy startup.  
Existing tests/guards: `RestoreJournalImporterFailureTest` covers import/idempotence and `MaintenanceSafeDiagnosticSinkTest` covers sanitized diagnostic events; neither asserts sanitization of `JournalEntry.error`/`OperationRun.errorSummary`.  
Cross-cell impact: P-08 privacy retention, P-12 diagnostics, and backup/export of the operation ledger.  
Old-ID cross-refs: none.

### CA-P-07-007
ID: CA-P-07-007  
Title: Redacted image-inclusive backup mode silently drops receipt images  
Defect class: 11 (Data integrity)  
Severity: P2  
Evidence: `BackupPrivacyMode.kt`, lines 10-14, labels `REDACT_RAW_TEXT` as "images included" and sets `includesReceiptImages=true`; `DatabaseBackupRepositoryImpl.runCostBackupExport`, lines 723-737, empties `receiptFiles` whenever `resolvedRedacted` is true while retaining `includeReceiptImages=true`; `CostbackupBundle.buildZip`, lines 583-594 and 607-630, records the option but writes receipt entries only when `!redacted`.  
Impact path: any caller selecting `REDACT_RAW_TEXT` receives a bundle whose manifest option says images are included but whose `includes.receiptImages` and ZIP contents omit them; restored receipts lose their image assets without a failure.  
Caller trace: `DatabaseBackupRepository.createCostBackup(..., privacyMode=REDACT_RAW_TEXT)` -> `runCostBackupExport` -> `CostbackupBundle`.  
Existing tests/guards: `CostbackupBundleLimitsTest`, `BackupVerifierManifestTest`, and `DatabaseBackupRepositoryImplTest` cover bundle limits/manifest and default options; no test asserts the enum label/flag contract for redacted image-inclusive mode.  
Cross-cell impact: receipt/asset recovery and export UX in P-10/P-18.  
Old-ID cross-refs: none.

### CA-P-07-008
ID: CA-P-07-008  
Title: Recovery paths log raw throwable messages and stack traces  
Defect class: 9 (Privacy)  
Severity: P2  
Evidence: `RestoreJournal.readJournal`, lines 482-490, and `writeJournal`, lines 497-520, call `Timber.*(e, ...)`; `DatabaseBackupRepositoryImpl`, lines 1272-1273, 2004-2005, 2643-2644, and 2800-2806, logs caught throwables from restore/import/stats/reset; `AppStartupCoordinator`, lines 222-252, logs throwable-backed recovery failures.  
Impact path: restore/reset/DB errors -> Timber receives exception messages and stack traces (which can contain SQL, file paths, or user data) outside the bounded diagnostic reason-code path; the debug tree writes them to logcat.  
Caller trace: restore/reset repository and startup recovery catches -> direct `Timber.e/w(Throwable, ...)` calls.  
Existing tests/guards: structured diagnostic sink tests assert bounded fields, but no static guard or test rejects throwable-backed Timber calls in this cell; `configureDebugTools` only gates the DebugTree by build type.  
Cross-cell impact: P-08 privacy and P-12 diagnostics/log retention.  
Old-ID cross-refs: none.

### CA-P-07-009
ID: CA-P-07-009  
Title: Semantic orphan checks fail open when a verification query throws  
Defect class: 11 (Data integrity) and 12 (Error handling)  
Severity: P1  
Evidence: `BackupVerifier.verifySemanticIntegrity`, lines 470-491, catches every query exception and only logs that the check was skipped; `BackupVerifier.verifyInternal`, lines 397-409, adds the returned list to `errors` and computes `overallPassed` from `errors.isEmpty()`.  
Impact path: restore verification -> a missing/incompatible table or failed orphan query -> the check contributes no error and the database can still pass verification, allowing orphan receipt links, recurring occurrences, or budget forecasts into the live database.  
Caller trace: `DatabaseBackupRepositoryImpl.restoreCostBackup` -> `BackupVerifier.verify`/`verifyQuick` -> `BackupVerifier.verifyInternal` -> `verifySemanticIntegrity`.  
Existing tests/guards: `BackupVerifierManifestTest` and backup/restore integrity tests cover successful integrity/FK/count outcomes; no injected semantic-query failure asserts verification failure for a required orphan check.  
Cross-cell impact: receipt links, recurring data, budgets/forecasting, and post-restore correctness in P-04/P-06/P-10/P-12.  
Old-ID cross-refs: none.

## Defect-class coverage matrix
- Class 1 legal path: backup/restore/reset entry points and DAO/Room boundaries traced; no new legal-path bypass found beyond barrier findings above.
- Class 2 barrier violation: CA-P-07-002, CA-P-07-003, CA-P-07-004, CA-P-07-005.
- Class 3 atomicity/TOCTOU: snapshot, safety-backup, swap, and journal ordering checked; no separate new issue beyond CA-P-07-005.
- Class 4 idempotency/duplicates: journal event preservation and asset-task ledger paths checked; no new issue beyond known P7 IDs.
- Class 5 cancellation safety: restore/reset cancellation branches rethrow `CancellationException`; no new issue found.
- Class 6 side-effect timing: maintenance, journal, swap, and post-commit event ordering checked; no separate new issue beyond CA-P-07-005.
- Class 7 money/currency: no money calculation in this cell; no finding.
- Class 8 time correctness: journal/mode paths use the injected `TimeProvider`; no finding.
- Class 9 privacy: CA-P-07-001, CA-P-07-006, CA-P-07-008.
- Class 10 worker hygiene: normal backup/restore operations use `MaintenanceOperationRunner` drain; no new finding asserted.
- Class 11 data integrity: CA-P-07-007 and CA-P-07-009; semantic aggregate verification remains the explicitly deferred P7-P1-05 item, while required orphan checks are not deferred.
- Class 12 error handling: CA-P-07-004 and CA-P-07-005.
- Class 13 wiring/dead code: production callers for backup/restore/reset and journal import are present; no new finding.
- Class 14 test correctness: relevant tests were checked for coverage gaps cited above; no tautological test finding asserted.
- Class 15 fix-regression: pinned production diff is empty, so no diff-scoped regression finding.

## Validation and limits
- Builds, tests, lint, and guards: NOT RUN (AUDIT static-only instruction).
- This report is a static finding pass; findings require the campaign's independent verification gate before registry promotion.
- The large backup repository was traversed through all mutation/IO/error pattern sites plus targeted legal-path reads; pure helper implementations outside those sites were not reprinted wholesale.
- Only the report and campaign journal are writable artifacts; no production source/config/script was edited.

## Final provenance
- Agents invoked: direct astra session only.
- Audit timestamp: 2026-09-21.
- Pinned commit rechecked before finalization: `37601232b9778170c57a656a245b199ab6d7d965`; `git diff --stat 37601232..HEAD -- app config scripts` empty.
- Finding count: 9 (P0=4, P1=3, P2=2, P3=0).

