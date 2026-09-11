# PIPELINE_07 — Fresh Validation (2026-09-04)

- Validated at: d1fa9c68 (branch `atomicity-pr21-enforcement-final`). Static validation only — no compile/test run.
- Source doc: docs/analyses and debug master/PIPELINE_7_CONSOLIDATED_ISSUES.md
- Cross-checks: PIPELINE_ISSUES_MASTER_TRACKER.md (P7 section), FIXED_CLAIMS_VALIDATION_AUDIT_v4.md, docs/architecture/{CODEBASE_SEGMENTS,LEGAL_PATHS,dao-map,ENGINE_INTERACTION_MAP}.md

## 1. Pipeline theme

Backup/restore integrity: maintenance-mode write barriers, crash-safe restore journaling, atomic DB file swap + receipt-asset restore, snapshot consistency, and backup verification tiers. At HEAD this pipeline is in dramatically better shape than its 2026-05-31 registry suggests: **14 of 16 issues are now fixed in code**; only the semantic-equivalence TODO (P7-P1-05) and the "caller-by-caller" caveat on the write barrier (P7-P1-02) remain genuinely open. The master tracker is stale in the *conservative* direction (lists fixed work as OPEN/TODO).

## 2. Verdict summary

| Verdict | Count |
|---|---:|
| Verified fixed | 14 |
| Verified open | 1 |
| Verified partial | 1 |
| Regressed | 0 |
| Tracker drift (doc/tracker says open, code says fixed) | 9 |
| New issues found this session | 3 |
| Not verifiable statically | 0 |

## 3. Issue-by-issue validation

| ID | Title (short) | Old status | Fresh status | Class | Evidence (file:line) | Notes |
|----|---------------|------------|--------------|-------|----------------------|-------|
| P7-P0-01 | Legacy `.db` import lacks journal/maintenance | TODO ONLY | **VERIFIED_FIXED** (drift) | PIPELINE | `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt:1397-1431` | Debug-only gate (`BuildConfig.DEBUG`, :1401-1405); journal created before maintenance mode (:1410-1431); `enterAndDrain(RESTORE_PREPARING, failOnTimeout=true)`. Introduced by commit a1ff1afe. |
| P7-P0-02 | Startup recovery resumes writes after failed recovery | FIXED | **VERIFIED_FIXED** | PIPELINE | `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt:204-216` | `CRITICAL_RECOVERY_REQUIRED` is never auto-reset on startup; writes stay blocked across restarts. Callers pass controlled constant reason strings (:165, :182, :194). |
| P7-P1-01 | Stale Room instance after DB file swap | TODO ONLY | **VERIFIED_FIXED** (drift) | PIPELINE | `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreDatabaseOpener.kt:17-24`; used at `DatabaseBackupRepositoryImpl.kt:991, 1572, 2259` and `AppStartupCoordinator.kt:261` | Fresh one-shot `AppDatabase` opened for post-swap verification. Commit dd9de8fd. Residual MINOR: internal test constructor at `DatabaseBackupRepositoryImpl.kt:114` still wires the opener to the injected singleton (see NEW-P7-2026-003). |
| P7-P1-02 | Maintenance mode not a global write barrier | TODO ONLY | **VERIFIED_PARTIAL** | UNIVERSAL (U-BARRIER-01/02) | `app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt:12-38`; `RestoreMaintenanceMode.kt:88-90`; 280 `checkWritesAllowed`/`runWrite` call sites under `app/src/main` | Enforcement is far broader than at audit time (writes blocked in every non-NORMAL mode incl. BACKUP_EXPORTING), but it remains opt-in per writer — no Room-level interceptor guarantees every DAO write is gated. Static full-coverage proof is impossible. Matches audit v4's open item "U-BARRIER-02 (SourceLinkBackfillWorker mid-run)". |
| P7-P1-03 | Backup creation does not freeze writes / no SQLite backup API | TODO ONLY | **VERIFIED_FIXED** (drift) | PIPELINE | `DatabaseBackupRepositoryImpl.kt:566-593` (mode + WAL checkpoint + barrier double-check `require(!isWritesAllowed())`); `app/src/main/java/com/yourname/expensetracker/data/backup/SqliteSnapshotCreator.kt:26-49` (VACUUM INTO, drained-copy fallback); commit ab679c0f | Snapshot consistency now protected by 4 layers; comment at :566-570 documents intent. |
| P7-P1-04 | Receipt asset restore not atomic with DB restore | TODO ONLY | **VERIFIED_FIXED** (drift) | PIPELINE | `RestoreJournal.kt:161-162` (ASSETS_RESTORING state), `:722-726` (AssetsIncomplete recovery), `:41-49` (per-asset tasks); `DatabaseBackupRepositoryImpl.kt:1019-1021`; commit cc7888fd | Crash mid-asset-restore is journaled and recovered via `RecoveryResult.AssetsIncomplete`. |
| P7-P1-05 | Restore success does not prove semantic equivalence | TODO ONLY | **VERIFIED_OPEN** | PIPELINE | `app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt:20-26` — explicit "TODO (P7-P1-05)" | Verification still count/tier-based; `liveCountsBeforeCopy` captured (`DatabaseBackupRepositoryImpl.kt:593-601`) but only count-level. Doc and tracker agree (no drift). Design TODO remains. |
| P7-P1-06 | Privacy audit events optional in backup verification | TODO ONLY | **VERIFIED_FIXED** (drift) | PIPELINE | `BackupVerifier.kt:80` — `"privacy_audit_events" to VerificationTier.TIER_1_EXACT` | No longer Tier-3 droppable. |
| P7-P1-07 | Worker pause/resume not spec-driven | FIXED | **VERIFIED_FIXED** | PIPELINE | `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt:27-47`; `RestoreMaintenanceMode.kt:173-192` | Pause via `WorkerSpec.DEFAULTS.keys` (:175-179), resume via `WorkerRegistry.scheduleAll` (:189-192). Single source of truth. |
| P7-P1-08 | Successful restore leaves app blocked | FIXED | **VERIFIED_FIXED** | PIPELINE | `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt:252-255` | `dismissRestartRequired()` clears UI flag AND exits maintenance mode (reschedules workers, resets stop flag). |
| NEW-P7-001 | Encrypted export never exits maintenance mode | FIXED (U-PR4) in tracker; OPEN in source doc table | **VERIFIED_FIXED** | UNIVERSAL (U-BARRIER-01) | `DatabaseBackupRepositoryImpl.kt:700-709` — outer `finally { runCatching { restoreMaintenanceMode.exit(...) } }` wraps the whole `createCostBackup` | Runs on success, gate denial, WAL failure, bundle failure, and after CancellationException rethrow. Tracker correct; source-doc table row stale. |
| NEW-P7-002 | Privacy gate denial / WAL failure leak maintenance mode | FIXED (U-PR4) in tracker; OPEN in source doc | **VERIFIED_FIXED** | UNIVERSAL (U-BARRIER-01) | same outer finally (:700-709); gates evaluated before mode entry where possible (:527-535); `MaintenanceOperationRunner.runExclusive` exits in `catch (t: Throwable)` (`app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceOperationRunner.kt:48-71`) | No leak path found on any export/import/reset entry point inspected. |
| NEW-P7-003 | `enterCriticalRecoveryRequired` non-atomic two-commit | OPEN | **VERIFIED_FIXED** (drift) | PIPELINE | `RestoreMaintenanceMode.kt:115-127` — single `prefs.edit().putString(reason).putLong(timestamp).putString(mode).commit()` | Fixes audit v4-confirmed P7-PR4 fix; confirmed intact at HEAD. Uses `timeProvider.now()` (U-PR7 clean). |
| NEW-P7-004 | RestoreJournal `appendEvent` read-modify-write race | OPEN | **VERIFIED_FIXED** (drift) | UNIVERSAL (TOCTOU/RMW) | `RestoreJournal.kt:235` (`journalLock`), `:249-276` (`appendEventToFile` synchronized); **additionally** `writeJournal` is now also `synchronized(journalLock)` (`:497-522`) | Audit v4's residual ("writeJournal does its own RMW without the lock") is also resolved at HEAD. Cross-process race remains (out of scope; @Singleton in-JVM only). |
| NEW-P7-005 | `CostbackupBundle.extract()` leaks FileInputStream | OPEN | **VERIFIED_FIXED** (drift) | PIPELINE | `CostbackupBundle.kt:332-335` (fis wrapped), `:481-483` (`finally { runCatching { fis.close() } }`) | Closes on every exception path. Time migration (bdd2b05c) touched the same file cleanly — no regression. |
| NEW-P7-006 | `countRowsFromSourceTable` unquoted table name | OPEN | **VERIFIED_FIXED** (drift) | PIPELINE (SQL injection) | `DatabaseBackupRepositoryImpl.kt:1931-1934` — `val safe = "\"" + tableName.replace("\"", "\"\"") + "\""`; `rawQuery("SELECT COUNT(*) FROM $safe")` | **Contradicts both the source doc (OPEN) and audit v4 (ACTUALLY_OPEN)**: fix landed in commit 25c636e1 (2026-06-01, "docs: sweep-update all 22 architecture/backend docs" — code change rode along), one day after the doc's validation date and after audit v4's snapshot. `tableExists` uses parameterized query (:1939-1949). Other raw-count sites quote identifiers (`BackupVerifier.kt:311, 601`). |

### 3.1 Tracker/doc drift found this session

1. **MASTER TRACKER IS STALE (conservative direction) — 9 issues listed OPEN/TODO are fixed in code:** P7-P0-01, P7-P1-01, P7-P1-03, P7-P1-04, P7-P1-06 (all TODO ONLY) and NEW-P7-003/004/005/006 (all OPEN 🔴). Resolves the audit-v4 contradiction: audit v4 confirmed 003/004/005 fixed (tracker wrong) and 006 open (tracker right *at the time*); NEW-P7-006 was subsequently fixed in 25c636e1 (2026-06-01), so **at HEAD the tracker is now wrong on all four**.
2. **Source doc header inconsistency:** PIPELINE_7_CONSOLIDATED_ISSUES.md header says "2 FIXED" but its own table lists 3 FIXED old issues (P7-P0-02, P7-P1-07, P7-P1-08) and 2 FIXED new issues (NEW-P7-001/002).
3. **DOC-DRIFT inside code (NEW-P7-2026-002):** the BAK-N1 KDoc at `DatabaseBackupRepositoryImpl.kt:1344-1374` still claims `importDatabase()` has "No restore journal / No maintenance mode" while the body implements both (a1ff1afe). Two stacked KDoc blocks (:1344-1374 and :1375-1396) — first one is dead/stale.

## 4. New issues & regressions found this session

| ID | Sev | Evidence | Description | Suspected origin |
|----|-----|----------|-------------|------------------|
| NEW-P7-2026-001 | MAJOR (privacy) | `DatabaseBackupRepositoryImpl.kt:2388-2393` (`reason = safetyBackupResult.exceptionOrNull()?.message` → `finalizeRunFailed("Safety backup failed: $reason")`), `:2438` (`restoreJournal.failJournal(journalEntry, e.message ?: "Reset failed")`), `:2450` (`resetEvents.finalizeRunFailed(e.message ?: "Exception", e)`) | `resetDatabase()` failure paths persist **arbitrary exception messages** into the on-disk restore journal (`error` field) and the OperationRun ledger (Room-backed). Violates repo privacy law: "never persist … file paths from exceptions / arbitrary e.message; reason fields must contain controlled constants only". File-paths/SQL detail from storage failures can land in persisted diagnostics; `RestoreJournalImporter` ingests these into the queryable ledger. Contrast: all `enterCriticalRecoveryRequired` callers use controlled constants. | Pre-existing; missed by the PII/structured-runCatching burn-down (e7f3496e/92a6ebf7/b2025407/ca760783/f652218f targeted allowlist suppressions, not raw-message arguments to structured loggers). |
| NEW-P7-2026-002 | MINOR (doc drift) | `DatabaseBackupRepositoryImpl.kt:1344-1374` vs `:1397-1431` | Stale BAK-N1 KDoc asserts legacy import lacks journal + maintenance mode; code has both since a1ff1afe. Misleads future maintainers/reviewers. | Doc sweep 25c636e1 updated docs but not this in-code narrative. |
| NEW-P7-2026-003 | MINOR | `DatabaseBackupRepositoryImpl.kt:92, :114` — internal secondary constructor wires `object : RestoreDatabaseOpener { override fun openFreshDatabase() = database }` | The internal (test-seam) constructor re-introduces the exact stale-Room-instance hazard that P7-P1-01 fixed, and also bypasses `WorkerDrainController`. Safe today (Hilt binds the primary constructor; opener impl is the real one), but any future wiring change that adopts this constructor silently regresses P7-P1-01. | Test-seam convenience predating the P7-P1-01 fix (dd9de8fd). |

No regressions found from the T1–T4C time migration (da2b8565..96c6b27d: bdd2b05c/68658c99/214bbb44 touched backup clocks cleanly — explicit `nowEpochMs` params, legacy JSON fallbacks preserved, no hidden wall-clock reads) or the PR18–PR24 cancellation rework (all inspected catch-sites in `DatabaseBackupRepositoryImpl.kt` rethrow `CancellationException` before handling, e.g. :473-475, :697-699, :877, :1657, :2436, :2447). Zero `System.currentTimeMillis` remains in backup/restore files.

## 5. Universal candidates

- **U-BARRIER-01 (maintenance exit on all paths):** VERIFIED COVERED in this pipeline — outer `finally` exits + `MaintenanceOperationRunner.runExclusive` catch-all; NEW-P7-001/002 closed by it. Keep as the reference implementation for other pipelines.
- **U-BARRIER-02 (write-barrier consistency):** STILL PARTIAL here (see P7-P1-02). 280 call sites prove breadth, not completeness; no static/architecture guard pins "every DAO write goes through the barrier". Candidate for a new architecture guard test.
- **U-PR1 (CancellationException):** rethrow discipline is present at audited catch sites; residual pattern risk = 35 raw `runCatching` blocks in `DatabaseBackupRepositoryImpl.kt`, 2 of which wrap **suspend** `finalizeRunFailed` (:1664, :2450) — benign today because `finalizeNonCancellable` (`OperationRunRecorder.kt:302`) never throws on cancellation, but they violate the raw-runCatching convention and would swallow a future cancellation-sensitive refactor.
- **U-PR2-family (RMW races):** RestoreJournal now fully lock-consistent (NEW-P7-004 including the audit-v4 residual). Cross-process journal writes remain theoretically racy — documented, out of scope for a single-process app.
- **U-PR7 (TimeProvider):** fully migrated in all P7 files; journal/manifest legacy fallbacks take explicit `nowEpochMs`.

## 6. Recommended next actions (ordered, concrete, with file paths)

1. **Tracker correction (docs only):** flip P7-P0-01, P7-P1-01, P7-P1-03, P7-P1-04, P7-P1-06 and NEW-P7-003/004/005/006 to FIXED in `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`, citing this doc. Leave P7-P1-02 as PARTIAL and P7-P1-05 as TODO.
2. **NEW-P7-2026-001:** replace `e.message` args with `DiagnosticReasonCode` constants at `DatabaseBackupRepositoryImpl.kt:2388-2393, :2438, :2450` (keep the `Throwable` param for class-name-safe diagnostics, mirroring `RestoreJournal.RestoreJournalEvent.exceptionClass/exceptionMessageSafe`).
3. **NEW-P7-2026-002:** delete or rewrite the stale BAK-N1 KDoc block at `DatabaseBackupRepositoryImpl.kt:1344-1374` to describe the implemented debug-only journaled import.
4. **NEW-P7-2026-003:** make the internal test constructor at `DatabaseBackupRepositoryImpl.kt:92-120` delegate `openFreshDatabase()` to a real file-based opener (or rename/comment it `@VisibleForTesting` with a guard), so the P7-P1-01 hazard cannot be re-wired accidentally.
5. **P7-P1-05 (only genuinely open design item):** implement post-restore semantic-equivalence verification per the TODO at `BackupVerifier.kt:20-26` (dashboard/analytics spot-queries against `liveCountsBeforeCopy` already captured at `DatabaseBackupRepositoryImpl.kt:593-601`).
6. **Optional hardening:** add an architecture-guard test asserting `RestoreDatabaseOpener` is only implemented by `RestoreDatabaseOpenerImpl` in production bindings (`di/BackupRepositoryModule.kt`).
