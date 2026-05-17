# Pipeline 7 Debug Report — Backup / Restore

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 7 is **significantly improved but not fully clean/stable yet**.

The modern `.costbackup` path has strong foundations:

- encrypted bundle format;
- manifest;
- checksums;
- table counts;
- staged DB verification;
- pre-swap Room migration check;
- safety backup;
- restore journal;
- maintenance mode;
- startup recovery hook;
- restart-required mode after successful restore.

But the pipeline remains **yellow/orange** because several critical safety guarantees are incomplete:

1. legacy `.db` import is still explicitly non-journaled and non-maintenance-mode;
2. startup crash recovery can delete the journal and reset writes even if safety restore fails;
3. restore still uses the stale injected Room instance after DB file swap;
4. maintenance/write barrier is not global;
5. backup creation does not freeze writes or use SQLite backup API;
6. receipt asset restore is not atomic/idempotent enough;
7. worker pause/resume is partly hardcoded;
8. restore-equivalence tests for dashboard/analytics/links/recurring/privacy audit are not proven.

Current state: **modern encrypted restore is beta-safe, legacy import is unsafe, global write-barrier is incomplete**.

---

# Severity scale

- **P0 / Critical:** can corrupt/lose live DB, resume writes on corrupt DB, or silently restore wrong data.
- **P1 / High:** crash-safety hole, incomplete write barrier, stale Room, backup inconsistency, failed restore UX.
- **P2 / Medium:** diagnostics, partial asset restore, UX gaps, regression risk.
- **P3 / Low:** cleanup/maintainability.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| Backup bundle created | Mostly yes via `.costbackup`. |
| Manifest included | Yes. |
| DB included | Yes. |
| Receipt files included | Optional; skipped when redacted. |
| Checksums valid | Mostly yes for DB/assets. |
| Encryption/decryption works | Mostly yes via AES-GCM service + password. |
| Wrong password fails safely | Mostly yes; live DB untouched before extraction success. |
| Tampered bundle rejected | Mostly yes through GCM/checksum/header validation. |
| Restore journal starts | Yes for `.costbackup`; no for legacy `.db` import. |
| Workers paused during restore | Partial. Cancels workers in `WorkerSpec.DEFAULTS`; resume hardcoded. |
| Notification capture paused during restore | Partial. Depends on notification path checking `RestoreMaintenanceMode`. |
| DB writes blocked during unsafe restore | Partial. Only paths that explicitly check maintenance mode are blocked. |
| Restore completes | Yes for happy path, returns restart-required. |
| Workers resume after success | Partial. If restart required, writes/workers stay blocked until restart; normal exit reschedules. |
| Failed restore leaves recoverable state | Partial. `.costbackup` has safety backup; startup recovery has a serious failure-handling gap. |
| Restored dashboard equals original | Not proven by tests. |
| Restored analytics equals original | Not proven by tests. |
| Receipt links preserved | DB rows likely preserved; receipt image path restore is partial. |
| Recurring state preserved | Table counts verified, semantic equality not proven. |
| Privacy audit preserved | Marked Tier 3 optional, so exact preservation is not enforced. |

---

# Positive findings to preserve

## PF-01 — `.costbackup` is the right production path

`createCostBackup()` creates an encrypted bundle with:

```text
manifest.json
checksums.json
database.sqlite
optional files/receipts
```

This is much safer than old raw `.db` export.

## PF-02 — Restore validates before touching live DB

`restoreCostBackup()` performs:

```text
extract
→ manifest/table-count sanity
→ copy to staging
→ quick verification
→ Room migration open on staging
→ post-migration verification
→ safety backup
→ swap
→ live verification
```

That is the right high-level restore flow.

## PF-03 — Safety backup path is now journaled before swap

The journal transitions through `SAFETY_BACKUP_CREATED` with `safetyBackupPath`, so a crash during swap can theoretically recover.

## PF-04 — Restart-required mode acknowledges stale Room risk

After successful restore, maintenance mode exits to `RESTORE_COMPLETE_RESTART_REQUIRED`, keeping writes blocked until app restart.

## PF-05 — Backup verifier is much stronger

`BackupVerifier` now checks:

```text
PRAGMA integrity_check
PRAGMA foreign_key_check
Tier 1 exact table counts
required table existence
```

This is a major improvement over the old 5-table check.

## PF-06 — Bundle extraction has ZIP Slip protection

`CostbackupBundle.extract()` canonicalizes paths and rejects traversal entries.

## PF-07 — Redacted backups skip receipt images

Receipt images are not included when `redacted = true`, which avoids accidentally exporting PII-heavy images.

---

# Issue P0-01 — Legacy `.db` import still lacks journal and maintenance mode

## Severity

P0 / Critical

## Evidence

`DatabaseBackupRepositoryImpl.importDatabase()` has an in-code warning saying the legacy import path lacks:

```text
RestoreJournal
RestoreMaintenanceMode
```

The method validates and stages the DB, creates a safety backup, closes Room, then replaces live DB files directly.

## Impact

If the process crashes during legacy import, the app can end up with:

```text
partially replaced live DB
no restore journal
no startup recovery signal
workers/services still writing during swap
```

This is the biggest remaining backup/restore hole.

## Fixing strategy

Either disable legacy import in production or make it use the same restore transaction contract as `.costbackup`.

## Implementation plan

1. Rename current method:

```kotlin
importLegacyDatabaseUnsafe(...)
```

and make it debug/internal only until hardened.

2. Add modern wrapper:

```kotlin
restoreLegacyDatabase(sourceFile)
```

using the same state machine:

```text
enter RESTORE_PREPARING
begin journal
copy to staging
verify/migrate staging
create safety backup
journal safety path
swap
verify live
commit journal
exit restart-required
```

3. Add tests:

```text
legacy_import_enters_maintenance_mode
legacy_import_creates_restore_journal
legacy_import_crash_during_swap_recovers_from_safety_backup
legacy_import_failure_preserves_live_database
legacy_import_blocks_workers_and_notification_capture
```

---

# Issue P0-02 — Startup crash recovery can resume writes after failed recovery

## Severity

P0 / Critical

## Evidence

`RestoreJournal.checkAndRecover()` returns `RecoveredFromSwap(success = false)` for destructive states.

`AppStartupCoordinator.checkRestoreJournal()` then tries to copy safety backup over live DB. If that copy fails, it logs the exception, but still:

```text
cleanStagingFiles()
deleteJournal()
then later resets maintenance mode to NORMAL
```

There is no durable “critical recovery failed” state in that branch.

## Impact

A failed crash recovery can leave a corrupt live database and still allow normal startup/writes.

This defeats the purpose of restore maintenance mode.

## Fixing strategy

Startup recovery must be fail-closed.

## Implementation plan

1. Change recovery branch to track success:

```kotlin
var recovered = false
try {
    restoreSafetyBackup()
    verifyRestoredLiveDb()
    recovered = true
} catch (...)
```

2. If `recovered == false`:

```kotlin
restoreMaintenanceMode.enter(RESTORE_COMPLETE_RESTART_REQUIRED)
preserve journal as restore_journal_critical_failure.json
do not delete journal
do not schedule workers
return
```

3. After safety restore, run:

```text
PRAGMA integrity_check
PRAGMA foreign_key_check
BackupVerifier.verifyQuick or verify
```

4. Tests:

```text
startup_recovery_failure_keeps_maintenance_mode
startup_recovery_failure_preserves_journal
startup_recovery_failure_does_not_schedule_workers
startup_recovery_success_verifies_live_db_before_reset
```

---

# Issue P1-03 — Restore uses stale injected Room instance after DB file swap

## Severity

P1 / High

## Evidence

`restoreCostBackup()` closes the injected `database`, swaps DB files, then uses the same injected `database` for:

```text
openHelper.writableDatabase
queryRoomCountsForVerification(database)
restoreReceiptAssets()
scannedReceiptDao().update(...)
```

The code itself comments that cached DAOs/Room references are stale and that restart is relied on.

## Impact

Live verification and receipt image path updates may operate through a Room instance that was constructed before the file replacement.

Symptoms can include:

```text
false verification result
stale DAO handles
asset path updates not persisted correctly
crashes from closed Room/openHelper
```

## Fixing strategy

Do not use the app-wide injected Room instance after file swap.

## Implementation plan

1. Introduce a restore-only DB opener:

```kotlin
interface RestoreDatabaseOpener {
    fun openFreshDatabase(name: String = AppDatabase.DATABASE_NAME): AppDatabase
}
```

2. After swap, use:

```kotlin
val freshDb = AppDatabase.fileBuilder(context, AppDatabase.DATABASE_NAME).build()
```

for verification and asset path repair.

3. Close fresh DB after restore.

4. Keep app-wide singleton invalid until restart.

5. Tests:

```text
restore_live_verification_uses_fresh_room_instance
receipt_asset_path_updates_use_fresh_restored_database
old_injected_database_not_used_after_swap
```

---

# Issue P1-04 — Maintenance mode is not a global DB write barrier

## Severity

P1 / High

## Evidence

`RestoreMaintenanceMode.isWritesAllowed()` exists, but enforcement is caller-by-caller.

Previous pipeline reviews already found multiple writers without restore guard:

```text
some budget/planned/forecast writes
receipt link service before hardening
recurring rule CRUD
legacy import
debug snapshot paths
```

`RestoreMaintenanceMode` cancels workers, but it cannot stop all foreground repository writes unless each path checks it.

## Impact

During restore, any unguarded repository/DAO path can mutate the old/new DB while files are being staged/swapped.

## Fixing strategy

Add a shared write barrier at lowest practical boundaries.

## Implementation plan

1. Add:

```kotlin
class DatabaseWriteBarrier {
    fun checkWritesAllowed(operation: String)
}
```

2. Inject it into all lifecycle coordinators and repositories with write methods.

3. Add static guard:

```text
production DB write methods must call writeBarrier or route through a coordinator that does
```

4. For Room DAOs, add CI grep allowlist for direct writes.

5. Tests:

```text
restore_mode_blocks_all_repository_write_methods
restore_mode_blocks_worker_write_methods
restore_mode_blocks_notification_processing
restore_mode_blocks_foreground_manual_expense_create
```

---

# Issue P1-05 — Backup creation does not freeze writes or use SQLite backup API

## Severity

P1 / High

## Evidence

`createCostBackup()`:

```text
checkpointWal()
copy live DB file to temp
sanitize temp
bundle temp
```

It does not enter `BACKUP_EXPORTING` mode, does not pause write-heavy workers, and does not use SQLite’s backup API.

## Impact

Writes can occur after checkpoint and during DB file copy.

Possible results:

```text
backup misses just-written data
main DB copy does not include concurrent WAL writes
manifest counts reflect copied snapshot, not user’s expected latest state
```

The backup may be internally valid but not a clean point-in-time snapshot.

## Fixing strategy

Make backup snapshot creation an explicit read-consistent operation.

## Implementation plan

1. Enter backup mode:

```kotlin
restoreMaintenanceMode.enter(BACKUP_EXPORTING)
```

2. Decide policy:
   - allow foreground writes but use SQLite backup API, or
   - block writes briefly while snapshot is copied.

3. Preferred: use SQLite backup API / `VACUUM INTO` / controlled checkpoint + read transaction, depending Android SQLite support.

4. Write a `BackupOperationEvent`:

```text
BACKUP_STARTED
WAL_CHECKPOINTED
SNAPSHOT_CREATED
MANIFEST_WRITTEN
BACKUP_COMPLETED
BACKUP_FAILED
```

5. Tests:

```text
backup_snapshot_is_point_in_time_under_concurrent_writes
backup_enters_and_exits_backup_mode
backup_failure_exits_backup_mode
```

---

# Issue P1-06 — Receipt asset restore is not atomic with DB restore

## Severity

P1 / High

## Evidence

After live DB verification, `restoreCostBackup()` calls `restoreReceiptAssets()`.

That method copies files to `files/receipts` and updates `ScannedReceipt.imagePath`.

If restore crashes during this stage, journal state is still around `VERIFYING`, so startup may roll back the DB from safety backup even though the DB swap already succeeded. Copied receipt files can also remain orphaned.

## Impact

Possible states:

```text
restored DB rolled back but receipt image files copied
restored DB active but some imagePath updates missing
success with warnings but no durable asset-repair ledger
```

## Fixing strategy

Split DB restore from asset restore and make asset repair idempotent.

## Implementation plan

1. Add journal states:

```text
DB_VERIFIED
ASSETS_RESTORING
ASSETS_RESTORED
RESTART_REQUIRED
```

2. Commit DB restore after DB verification.

3. Store asset restore tasks in a durable table or journal section:

```kotlin
receiptId
sourceAssetName
targetPath
status
error
```

4. On startup, resume incomplete asset repair instead of rolling back DB.

5. Tests:

```text
crash_after_db_verified_before_asset_restore_does_not_rollback_valid_db
crash_mid_asset_restore_resumes_asset_repair
orphan_asset_files_are_cleaned_or_reported
asset_restore_warning_is_visible_after_success
```

---

# Issue P1-07 — Restore success does not prove dashboard/analytics equivalence

## Severity

P1 / High

## Evidence

Current verification checks table counts, integrity, FK, and some summary counts.

It does not compute semantic equality for:

```text
dashboard totals
analytics category totals
budget statuses
receipt links
recurring occurrence/planned state
privacy audit continuity
```

## Impact

A restore can pass row-count checks while still changing behavior due to:

```text
bad image paths
missing optional/cache tables
stale exchange rates
wrong planned/open occurrence keys
broken receipt_expense_links semantics
```

## Fixing strategy

Add golden restore-equivalence checks.

## Implementation plan

1. Create test fixture DB with:

```text
expenses
multi-currency rates
receipt + receipt_expense_link
recurring rule + occurrence + reminder delivery
budget + forecast
group expense
privacy audit event
```

2. Before backup, compute:

```text
dashboard monthly total
analytics category totals
budget spent
receipt link count
recurring open/paid counts
privacy audit count
```

3. Backup → restore into fresh DB → recompute.

4. Tests:

```text
backup_restore_preserves_dashboard_totals
backup_restore_preserves_analytics_totals
backup_restore_preserves_receipt_links
backup_restore_preserves_recurring_state
backup_restore_preserves_privacy_audit_events_or_documents_optional_policy
```

---

# Issue P1-08 — Privacy audit events are optional in backup verification

## Severity

P1/P2 depending privacy/audit policy

## Evidence

`BackupVerifier` classifies `privacy_audit_events` as Tier 3 optional.

## Impact

A restored backup can drop privacy audit rows and still pass verification.

For a privacy-sensitive app, audit history may be user data, not disposable cache.

## Fixing strategy

Decide audit retention contract.

## Implementation plan

Option A — preserve privacy audit exactly:

```text
privacy_audit_events → Tier 1 exact
```

Option B — intentionally exclude/redact audit logs:

```text
manifest must say privacyAuditIncluded=false
UI warns audit history is not included
```

Tests:

```text
privacy_audit_preserved_when_policy_include
privacy_audit_excluded_only_when_manifest_declares_exclusion
restore_verification_fails_if_required_audit_missing
```

---

# Issue P1-09 — Worker pause/resume is not fully spec-driven

## Severity

P1/P2

## Evidence

`pauseAllWorkers()` cancels all names from `WorkerSpec.DEFAULTS`.

`RestoreMaintenanceMode.scheduleAllWorkers()` hardcodes companion calls for a fixed list of workers plus AI briefing.

If a new worker is added to `WorkerSpec.DEFAULTS` but not added to `scheduleAllWorkers()`, it will be cancelled during restore but not resumed by maintenance exit.

## Impact

After restore, some workers may silently stop running.

## Fixing strategy

Worker pause/resume should be symmetric and registry-driven.

## Implementation plan

1. Add central registry:

```kotlin
data class WorkerRegistration(
    val specName: String,
    val schedule: (Context) -> Unit
)
```

2. Use same registry for:
   - startup scheduling,
   - maintenance pause,
   - maintenance resume.

3. Add guard test:

```text
every_WorkerSpec_DEFAULTS_entry_has_worker_registration
restore_exit_reschedules_every_enabled_worker
disabled_worker_is_not_rescheduled
```

---

# Issue P1-10 — Successful restore leaves app in blocked mode but UI can dismiss warning

## Severity

P1/P2

## Evidence

After successful restore:

```text
restoreMaintenanceMode.exit(forceRestartRequired = true)
```

This keeps writes blocked until restart. UI sets `restartRequired = true`, but `dismissRestartRequired()` only clears UI state.

There is no visible global app lock in this slice.

## Impact

User can dismiss the restart message and continue using app. Some operations will fail due maintenance mode; unguarded operations may still write with stale Room.

## Fixing strategy

Make restart-required state a global app-level blocking state.

## Implementation plan

1. Add global state:

```kotlin
AppOperationalState.RestartRequiredAfterRestore
```

2. Navigation shell should show modal/blocking banner:

```text
Restore complete. Restart required before using the app.
```

3. Disable write actions until app restarts.

4. Tests:

```text
after_restore_success_write_actions_disabled_until_restart
dismiss_restart_banner_does_not_clear_maintenance_mode
startup_after_restart_resets_mode_to_normal
```

---

# Issue P2-11 — Raw `.db/.enc` export remains public API

## Severity

P2 / Medium, P1 if reachable in release UI

## Evidence

`exportDatabase()` is deprecated and described as debug-only, but it is still an override on `DatabaseBackupRepositoryImpl`.

It can create plaintext DB backups if encrypted backup setting is off and privacy gate allows raw backup export.

## Impact

Future UI or automation can call old raw export and bypass `.costbackup` manifest/checksum/asset policy.

## Fixing strategy

Make raw export impossible in release builds or move it to debug-only repository.

## Implementation plan

1. Add release guard:

```kotlin
if (!BuildConfig.DEBUG) {
    return Result.failure(UnsupportedOperationException("Raw DB export disabled in release"))
}
```

2. Or remove from production interface.

3. Static guard:

```text
no production UI calls exportDatabase()
```

4. Tests:

```text
raw_export_rejected_in_release
costbackup_export_available_in_release
```

---

# Issue P2-12 — Restore URI copy has no file size/header precheck

## Severity

P2 / Medium

## Evidence

`BackupRestoreViewModel.restoreBackup()` copies the selected URI to a temp file before repository validation.

There is no visible file size cap or early header check.

## Impact

A huge file can fill cache/storage before being rejected.

## Fixing strategy

Validate early.

## Implementation plan

1. Add max bundle size setting:

```kotlin
MAX_BACKUP_BUNDLE_BYTES
```

2. Use `ContentResolver` metadata when available.

3. Stream first 12 bytes and validate `.costbackup` header before full copy.

4. Tests:

```text
restore_rejects_file_over_max_size_before_full_copy
restore_rejects_wrong_magic_before_repository_call
restore_temp_file_deleted_after_failure
```

---

# Issue P2-13 — Receipt asset restore can leave orphan copied files

## Severity

P2 / Medium

## Evidence

`restoreReceiptAssets()` copies an asset to the destination before validating that:

```text
receipt ID can be parsed
receipt row exists
DB update succeeds
```

If any of those fail, the destination file remains.

## Impact

Orphan files accumulate in `files/receipts`.

## Fixing strategy

Validate before copy or delete on failure.

## Implementation plan

1. Parse receipt ID first.
2. Check receipt exists before copying.
3. Copy to temp file.
4. Update DB.
5. Rename temp to final path.
6. On error, delete temp/final.

Tests:

```text
invalid_asset_filename_does_not_leave_orphan_file
missing_receipt_row_does_not_leave_orphan_file
db_update_failure_deletes_copied_asset
```

---

# Issue P2-14 — Quick staged verification skips Tier 1 tables absent from manifest

## Severity

P2 / Medium

## Evidence

`BackupVerifier.verifyQuick()` only checks Tier 1 exact counts if a table has an expected count in the manifest.

Full verification fails if a Tier 1 count is absent, but quick pre-swap verification can pass incomplete manifests.

## Impact

Bad/incomplete manifests are caught later after live swap rather than before swap.

## Fixing strategy

Require manifest completeness before swap.

## Implementation plan

1. Add:

```kotlin
BackupVerifier.validateManifestCompleteness(manifest.tableCounts)
```

2. For current `.costbackup` format, require all Tier 1 tables to be present.

3. Legacy compatibility can use explicit manifest version policy.

Tests:

```text
costbackup_missing_tier1_count_fails_before_swap
legacy_manifest_missing_tier1_count_requires_compat_policy
```

---

# Issue P2-15 — Backup/restore diagnostics are mostly logs + journal

## Severity

P2 / Medium

## Evidence

There is a restore journal, but no durable user-visible operation ledger for successful/failed backup/restore events beyond files and logs.

## Impact

Debugging user reports still requires logcat.

## Fixing strategy

Add `backup_restore_events` or use existing diagnostics infrastructure.

## Implementation plan

1. Entity:

```kotlin
BackupRestoreEvent(
    id,
    operationId,
    operationType,
    stage,
    outcome,
    backupPathHash,
    schemaVersion,
    tableCountSummaryJson,
    errorClass,
    errorMessage,
    timestamp
)
```

2. Write events:

```text
BACKUP_STARTED
BACKUP_COMPLETED
BACKUP_FAILED
RESTORE_STARTED
EXTRACT_FAILED
STAGED_VERIFIED
SAFETY_BACKUP_CREATED
SWAP_STARTED
LIVE_VERIFIED
ASSET_RESTORE_WARNING
RESTORE_COMPLETED_RESTART_REQUIRED
ROLLBACK_COMPLETED
CRITICAL_RECOVERY_REQUIRED
```

3. Tests:

```text
restore_wrong_password_writes_extract_failed_event
restore_success_writes_restart_required_event
rollback_success_writes_rollback_completed_event
```

---

# Recommended fixing order

## PR 1 — Fail-closed startup recovery

Files:

```text
AppStartupCoordinator.kt
RestoreJournal.kt
RestoreMaintenanceMode.kt
BackupVerifier.kt
```

Fix:

```text
- do not delete journal if safety restore fails
- do not reset maintenance mode on recovery failure
- verify live DB after safety restore
```

## PR 2 — Harden or disable legacy import

Files:

```text
DatabaseBackupRepository.kt
DatabaseBackupRepositoryImpl.kt
BackupRestoreViewModel.kt
```

Fix:

```text
- legacy import uses restore journal + maintenance mode
- or release-build disables it
```

## PR 3 — Fresh Room instance after swap

Files:

```text
DatabaseBackupRepositoryImpl.kt
AppDatabase.kt
RestoreDatabaseOpener.kt
```

Fix:

```text
- no injected singleton Room use after swap
- verification and asset path updates use fresh DB
```

## PR 4 — Global write barrier

Files:

```text
new DatabaseWriteBarrier.kt
all write coordinators/repositories
static guard script
```

Fix:

```text
- restore mode blocks all writes, not just guarded flows
```

## PR 5 — Point-in-time backup snapshot

Files:

```text
DatabaseBackupRepositoryImpl.kt
RestoreMaintenanceMode.kt
BackupVerifier.kt
```

Fix:

```text
- backup enters BACKUP_EXPORTING
- use SQLite backup/safe snapshot mechanism
- backup operation ledger
```

## PR 6 — Asset restore state machine

Files:

```text
RestoreJournal.kt
DatabaseBackupRepositoryImpl.kt
ReceiptAssetStore.kt
ScannedReceiptDao.kt
```

Fix:

```text
- asset restore is idempotent
- DB verified state does not rollback valid DB because asset phase crashed
- no orphan receipt files
```

## PR 7 — Worker registry symmetry

Files:

```text
WorkerSpec.kt
WorkerSpecScheduler.kt
RestoreMaintenanceMode.kt
AppStartupCoordinator.kt
```

Fix:

```text
- one worker registry for startup, pause, resume
```

## PR 8 — Golden restore equivalence tests

Files:

```text
backup/restore integration tests
dashboard/analytics/budget/receipt/recurring fixtures
```

Fix:

```text
- restore preserves actual app outputs, not only row counts
```

---

# Golden tests to add

```text
costbackup_create_extract_roundtrip_preserves_db_counts
costbackup_wrong_password_does_not_touch_live_db
costbackup_tampered_ciphertext_rejected
costbackup_tampered_asset_checksum_rejected
restore_enters_maintenance_and_pauses_workers
restore_blocks_notification_processing
restore_blocks_all_repository_writes
restore_staged_migration_failure_leaves_live_db_unchanged
restore_safety_backup_failure_aborts_before_swap
restore_crash_during_swap_recovers_safety_backup
startup_recovery_failure_keeps_maintenance_mode_and_journal
restore_uses_fresh_room_after_swap
restore_success_requires_restart_and_blocks_writes_until_restart
receipt_assets_restored_and_image_paths_updated
receipt_asset_restore_crash_resumes_idempotently
backup_snapshot_consistent_under_concurrent_writes
legacy_import_release_disabled_or_journaled
backup_restore_dashboard_totals_equal
backup_restore_analytics_totals_equal
backup_restore_receipt_links_equal
backup_restore_recurring_state_equal
backup_restore_privacy_audit_policy_enforced
all_WorkerSpec_defaults_have_pause_resume_registration
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "importDatabase(" app/src/main/java
grep -R "exportDatabase(" app/src/main/java
grep -R "restoreCostBackup" app/src/main/java
grep -R "RestoreMaintenanceMode" app/src/main/java
grep -R "isWritesAllowed" app/src/main/java
grep -R "database.close()" app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
grep -R "scannedReceiptDao().update" app/src/main/java
grep -R "WorkerSpec.DEFAULTS" app/src/main/java
grep -R "scheduleAllWorkers" app/src/main/java
```

Allowed restore-time DB file operations should be restricted to:

```text
DatabaseBackupRepositoryImpl
RestoreJournal/AppStartupCoordinator recovery
approved test utilities
```

Definition of done:

```text
- `.costbackup` restore is fail-closed on crash recovery failure.
- Legacy `.db` import is either disabled in release or journaled + maintenance-guarded.
- No stale injected Room/DAO is used after DB file swap.
- Backup creation produces a point-in-time consistent snapshot.
- Restore maintenance mode blocks all app writes through a global barrier.
- Receipt asset restore is idempotent and does not leave orphan files.
- Worker pause/resume uses one registry and covers every WorkerSpec.
- Successful restore requires restart and app UI globally blocks use until restart.
- Golden restore tests prove dashboard, analytics, receipts, recurring, and privacy-audit behavior.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `DatabaseBackupRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `BackupVerifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt

- `CostbackupBundle.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/backup/CostbackupBundle.kt

- `RestoreJournal.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `RestoreMaintenanceMode.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

- `AppStartupCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

- `BackupRestoreViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt

- `DataRetentionWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt