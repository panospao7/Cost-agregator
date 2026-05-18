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

## Asset restore resumability

`RestoreJournal.JournalEntry.assetTasks` tracks per-asset status (`PENDING`/`COMPLETED`/`FAILED`). On crash during `ASSETS_RESTORING`, incomplete tasks are visible in the journal for manual recovery or future resume.
