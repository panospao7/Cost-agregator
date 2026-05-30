# Backup / Restore Barrier Contract

Part of: Global Write/Read/Restore Barrier

---

## Write policy

| Mode | Writes allowed? |
|---|---|
| `NORMAL` | Yes |
| `BACKUP_EXPORTING` | No (snapshot reads only via `EXPORT_OR_BACKUP_SNAPSHOT_READ`) |
| `RESTORE_*` | No |
| `RESETTING_DATABASE` | No |
| `ASSETS_RESTORING` | Restore-internal only via `RestoreInternalWriteScope` |
| `RESTORE_COMPLETE_RESTART_REQUIRED` | No |
| `CRITICAL_RECOVERY_REQUIRED` | No |

## Snapshot policy

`.costbackup` export uses `SqliteSnapshotCreator`:
- Preferred: `VACUUM INTO` (SQLite 3.27+, Android API 30+)
- Fallback: drained file-copy after WAL checkpoint under `BACKUP_EXPORTING`

Both paths require workers to be drained before snapshot creation.

## Worker drain policy

All destructive DB operations must call `MaintenanceOperationRunner.enterAndDrain()` before any file mutation. Drain timeout → `WorkerDrainTimeoutException` → operation aborted.

## Rollback policy

| Rollback outcome | Mode after |
|---|---|
| Rollback succeeded | `NORMAL` |
| Rollback failed | `CRITICAL_RECOVERY_REQUIRED` (reason persisted) |

## Critical recovery

`CRITICAL_RECOVERY_REQUIRED` persists reason + timestamp in SharedPreferences. UI shows reason. Workers are cancelled. No writes allowed until process restart + manual recovery.

## Restore-internal writes

Asset path updates during `ASSETS_RESTORING` use `RestoreInternalWriteScope`. No other code may use this scope.

## Startup recovery

On crash during restore, startup:
1. Copies safety backup to live DB path
2. Runs `PRAGMA integrity_check` + `PRAGMA foreign_key_check`
3. Opens fresh Room instance
4. If any check fails → `CRITICAL_RECOVERY_REQUIRED`
5. If all pass → delete journal → reset to `NORMAL`

### Cross-restart fail-closed (P7-CURRENT-003)

If the safety-backup copy itself cannot complete (missing/unreadable backup, copy error),
startup enters `CRITICAL_RECOVERY_REQUIRED` — **not** `RESTORE_COMPLETE_RESTART_REQUIRED`.

`failJournal()` renames the active journal to the failure file, so on the *next* process
start `checkAndRecover()` returns `NoAction`. The startup auto-reset that returns transient
modes (e.g. `RESTORE_COMPLETE_RESTART_REQUIRED`) to `NORMAL` **explicitly exempts**
`CRITICAL_RECOVERY_REQUIRED`. As a result the fail-closed state survives repeated restarts
and writes remain blocked until manual recovery clears the mode. By contrast,
`RESTORE_COMPLETE_RESTART_REQUIRED` (set after a *successful* restore) is auto-reset to
`NORMAL` on the next clean start so the app resumes normally.

## Asset restore resumability

`RestoreJournal.JournalEntry.assetTasks` tracks per-asset status (`PENDING`/`COMPLETED`/`FAILED`). On crash during `ASSETS_RESTORING`, incomplete tasks are visible in the journal for manual recovery or future resume.

## Backup creation integrity (P7-CURRENT-015)

`createCostBackup()` collects manifest table counts via `BackupVerifier.collectTableCountsStrict()`. A row-count query failure on a required (Tier 1 / Tier 2) table aborts backup creation — counts are never silently recorded as 0. Optional (Tier 3) tables that are absent are recorded as 0.

`warranty_reminder_deliveries` (durable warranty-reminder sent-state, added in migration 142→143) is captured by the whole-file snapshot and verified by `BackupVerifier` at **TIER_1_EXACT** — its row count must match exactly on restore, so warranty reminder state survives backup/restore.

## Pre-swap manifest completeness (P7-CURRENT-014)

`restoreCostBackup()` calls `BackupVerifier.validateManifestCompleteness()` before any destructive swap. A manifest missing any required Tier 1 count is rejected while the live DB is still intact (it would otherwise pass `verifyQuick`, which skips null counts, and only fail in full `verify` after the swap).

## Restore URI preflight (P7-CURRENT-017)

`BackupRestoreViewModel.restoreBackup()` rejects oversized URIs using `OpenableColumns.SIZE` and validates the `COSTBACKUP` header magic before/while copying. The copy is hard-capped at `MAX_BACKUP_BUNDLE_BYTES` (500 MB) so a provider that under-reports its size cannot fill the cache.

## Bundle extraction limits (P7-CURRENT-023)

`CostbackupBundle.extract()` enforces `ExtractionLimits` (max total decompressed bytes, max per-entry bytes, max entry count). The declared `ZipEntry.size` is untrusted; actual streamed bytes are measured and capped, so a zip bomb cannot fill storage after passing password/header validation.

## Journal durability (P7-CURRENT-022)

`RestoreJournal` writes journal/event temp files via an fsync'd write (`FileOutputStream.fd.sync()`) before the atomic rename, so a crash immediately after rename cannot leave a transition or safety-backup path unflushed.

## Restart-required is a global, non-dismissible lock (P7-CURRENT-019)

After a successful restore, writes are blocked by `RESTORE_COMPLETE_RESTART_REQUIRED`
(persisted in SharedPreferences). The authoritative, non-dismissible enforcement is the
**app shell**: `MainActivity` observes `RestoreMaintenanceMode.operationalStateFlow` and, on
`AppOperationalState.RestartRequiredAfterRestore`, renders a full-screen lock and `return`s
before any app content. The only action is restarting the process.

`BackupRestoreViewModel.dismissRestartRequired()` is a **no-op** (deprecated) — it formerly
cleared a screen-local banner flag, which could mislead a caller/test into thinking the app
was usable while writes were still globally blocked. The screen banner is purely informational;
it cannot unblock writes.

## Diagnostics ledger import (P7-CURRENT-016)

The restore/reset path bans Room after the DB swap (P7-CURRENT-005), so the operation trail —
**including terminal FAILURE outcomes** (wrong password, staged/post-migration/verification
failure, rollback failure, reset failure) — is written only to the on-disk `RestoreJournal`
(`restore_journal_last_success.json` / `restore_journal_last_failure.json`).

On the next **healthy** startup (writes allowed), `AppStartupCoordinator.importRestoreJournals()`
calls both `RestoreJournalImporter.importLastSuccessJournalIfPresent()` and
`importLastFailureJournalIfPresent()`, ingesting each journal's events into the queryable
`OperationRun` / `OperationRunEvent` ledger. Both importers are **idempotent per event**
(checked against `OperationRunEvent.eventId` and a journal `importedAt` marker), so repeated
startups never duplicate rows. This closes the loop so backup/restore outcomes are queryable
without logcat.

## Destructive reset guard (P7-CURRENT-020)

`resetDatabase()` enters `RESETTING_DATABASE` maintenance mode via
`MaintenanceOperationRunner.enterAndDrain()` (drains workers, blocks all writes) and creates a
`RestoreJournal` before deleting the live DB/WAL/SHM, so a crash mid-reset is recoverable and
audited. A static guard (`BackupRestoreArchitectureGuardTest`) asserts both wrappers remain.
(Typed-confirmation token for the reset is tracked separately and not yet enforced.)

## Raw export is debug-only (P7-CURRENT-021)

Raw `exportDatabase()` produces a legacy `.db`/`.enc` artifact (no `.costbackup` manifest/assets)
and is release-disabled at runtime. A static guard (`BackupRestoreArchitectureGuardTest`) asserts
no production UI references it — the debug screen (`ui/screens/debug/**`) is the single intended
caller. Production restore/backup must use `restoreCostBackup()` / `createCostBackup()`.
