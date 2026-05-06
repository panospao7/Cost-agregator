# Pipeline 7 Debugging Report — Backup / Restore / Maintenance Mode / Startup Recovery

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local/device execution.

## 1. Executive summary

Pipeline 7 is intended to be:

```text
BackupRestoreScreen
→ BackupRestoreViewModel
→ DatabaseBackupRepositoryImpl
→ PrivacyGate
→ WAL checkpoint
→ temp DB snapshot
→ optional redaction
→ receipt asset collection
→ CostbackupBundle encrypted ZIP
→ restore extraction/checksum
→ staged DB verification/migration
→ safety backup
→ RestoreJournal
→ RestoreMaintenanceMode
→ live DB swap
→ live DB verification
→ receipt asset restore
→ restart-required state
→ AppStartupCoordinator crash recovery
```

The design is much better than a simple “copy database file” backup. Strong parts:

- `.costbackup` bundle format exists.
- AES-GCM encryption exists.
- Manifest + SHA-256 checksums exist.
- ZIP Slip protection exists.
- WAL checkpoint happens before backup copy.
- Restore uses staging before touching live DB.
- Restore creates a safety backup before swap.
- Restore journal exists.
- Maintenance mode exists.
- Startup recovery exists.
- Live DB verification exists after restore.

But there are several serious release-safety issues.

Highest-risk findings:

1. **Restore journal safety-backup path can be lost during state transitions.**
2. **Backup snapshot copies the live DB file while app writes may still occur.**
3. **Not all workers check `RestoreMaintenanceMode`; cancelling WorkManager is not enough for already-running work.**
4. **Critical restore recovery can still reset maintenance mode to normal and schedule workers.**
5. **Legacy `importDatabase()` swaps DB files without restore journal / maintenance mode.**
6. **Post-migration staged DB is not fully verified before live swap.**
7. **“Redacted backup” may still include raw receipt images.**
8. **Bundle encryption/extraction reads large files into memory.**
9. **Backup verifier can pass missing required tables when expected count is `0`.**
10. **Lifecycle/link/currency tables are under-protected by verification tiers.**

Main recommendation:

> Treat backup/restore as a destructive transactional pipeline. It needs a global write barrier, journal state correctness, all-worker restore guards, staged post-migration verification, and fed-DB roundtrip tests.

---

# 2. Intended architecture contract

From `DEPENDENCY_MAP.md`, backup/restore is:

```text
BackupRestoreScreen
→ DatabaseBackupRepositoryImpl
   → CostbackupBundle
   → BackupEncryptionService
   → ExportAnonymizer
   → PrivacyGate
   → RestoreJournal
   → RestoreMaintenanceMode
→ AppStartupCoordinator.checkRestoreJournal()
→ workers paused during restore
→ NotificationCaptureService blocked during restore
```

Workers listed as affected:

```text
DailyBriefingWorker
LocationBackfillWorker
MerchantKeyBackfillWorker
WarrantyExpirationWorker
BillReminderWorker
ReceiptMatchingWorker
DataRetentionWorker
```

This is the right architecture. The main gaps are implementation consistency and tests.

---

# 3. Actual code path summary

## 3.1 Backup creation path

`BackupRestoreViewModel.createBackup(password)` calls:

```text
DatabaseBackupRepository.createCostBackup(password)
```

`DatabaseBackupRepositoryImpl.createCostBackup()` does:

```text
PrivacyGate.check(ENCRYPTED_BACKUP)
→ checkpointWal()
→ copy live DB file to tempDb
→ optionally ExportAnonymizer.sanitizeExport(tempDb)
→ count tables from temp snapshot
→ collect receipt images
→ CostbackupBundle.create(...)
→ encrypted .costbackup file in app files/backups
```

`CostbackupBundle.create()`:

```text
build ZIP temp file
  manifest.json
  database.sqlite
  receipt files
  checksums.json
encrypt ZIP with BackupEncryptionService
write COSTBACKUP header + ciphertext
delete temp ZIP
```

Strengths:

- app-private backup directory,
- encrypted bundle,
- manifest and checksums,
- DB redaction option,
- receipt asset inclusion.

Risks:

- no global write barrier during backup file copy,
- “redacted” does not redact receipt images,
- encryption still reads full temp ZIP into memory,
- table count failures become `0`.

---

## 3.2 Restore path

`BackupRestoreViewModel.restoreBackup(uri, password)` copies the selected URI to a temp file, then calls:

```text
DatabaseBackupRepository.restoreCostBackup(tempFile, password)
```

`restoreCostBackup()` does:

```text
enter RESTORE_PREPARING
begin RestoreJournal(PREPARING)
extract/decrypt bundle
transition STAGED
manifest non-empty check
copy extracted DB to staging file
BackupVerifier.verifyQuick(staged)
open staged DB with Room to trigger migrations
create safety backup
transition SAFETY_BACKUP_CREATED
transition SWAPPING
close live database
move live DB to .pre_restore
copy staged DB to live
transition VERIFYING
open live DB
BackupVerifier.verify(live)
verify summary preserved
restore receipt assets
cleanup
commit journal
exit maintenance with RESTORE_COMPLETE_RESTART_REQUIRED
return SuccessNeedsRestart
```

Strengths:

- live DB is not touched before extraction, quick verification, and migration preflight,
- safety backup exists before destructive swap,
- restore returns restart-required,
- failed live verification attempts rollback.

Risks:

- journal transition bug can erase safety-backup path,
- post-migration staged DB not fully verified before swap,
- some failure paths may leave `.pre_restore`,
- live verification happens after destructive swap rather than before,
- same closed `AppDatabase` instance is reused for verification,
- startup critical-recovery path is too permissive.

---

## 3.3 Startup recovery

`AppStartupCoordinator.initialize()` does:

```text
if maintenance mode == RESTORE_COMPLETE_RESTART_REQUIRED:
    store restart-required flag

checkRestoreJournal()
register lifecycle observer
schedule startup work
sync proactive briefing work
```

`checkRestoreJournal()`:

```text
NoAction → normal
CompleteClean → cleanup
CleanedNonDestructive → cleanup
RecoveredFromSwap → attempt safety backup restore
CriticalRecoveryRequired → log critical
then reset maintenance mode to NORMAL if non-normal
```

Strength:

- restore journal is checked on startup before workers are scheduled.

Risk:

- even critical recovery appears to continue to schedule workers,
- generic maintenance-mode reset can unblock writes after critical failure,
- recovery from safety backup is not clearly verified before journal deletion.

---

# 4. Major findings

## Finding P0-1 — Restore journal safety-backup path can be lost

This is the most concrete high-risk bug I found.

In `DatabaseBackupRepositoryImpl.restoreCostBackup()`:

```kotlin
restoreJournal.transitionTo(
    journalEntry,
    RestoreJournal.JournalState.SAFETY_BACKUP_CREATED,
    safetyBackupPath = safetyBackupFile.absolutePath
)

restoreJournal.transitionTo(journalEntry, RestoreJournal.JournalState.SWAPPING)
```

`transitionTo()` returns the updated `JournalEntry`, but the caller ignores it.

Because the second call passes the original `journalEntry`, the `safetyBackupPath` can be overwritten back to `null`.

Why this matters:

```text
restore reaches SWAPPING
process crashes
journal says SWAPPING but has no safetyBackupPath
AppStartupCoordinator cannot recover live DB from safety backup
```

This defeats the main purpose of the crash-safe journal.

### Fix

Always reassign:

```kotlin
var journalEntry = restoreJournal.beginJournal(...)

journalEntry = restoreJournal.transitionTo(journalEntry, STAGED)

journalEntry = restoreJournal.transitionTo(
    journalEntry,
    SAFETY_BACKUP_CREATED,
    safetyBackupPath = safetyBackupFile.absolutePath
)

journalEntry = restoreJournal.transitionTo(journalEntry, SWAPPING)
journalEntry = restoreJournal.transitionTo(journalEntry, VERIFYING)
journalEntry = restoreJournal.commitJournal(journalEntry)
```

Also make `transitionTo(operationId, state, ...)` read current journal from disk and preserve fields, so callers cannot accidentally erase critical paths.

Priority: highest.

---

## Finding P0-2 — Backup file copy is not protected by a global write barrier

`createCostBackup()` does:

```text
checkpointWal()
copy live DB file → tempDb
```

But the app can still write while the copy is happening:

- notification capture,
- workers,
- user expense edits,
- receipt processing,
- recurring reminders,
- privacy retention,
- location backfill.

`BACKUP_EXPORTING` exists as a maintenance mode, but `createCostBackup()` does not enter it. Even if it did, `isWritesAllowed()` allows writes during `BACKUP_EXPORTING`.

In WAL mode, a simple file copy is only safe if you know no write is happening and WAL state is settled. A checkpoint before copying is not enough if new writes happen after the checkpoint starts/finishes.

Symptoms:

- backup missing recent rows,
- backup with inconsistent main DB vs side effects,
- copied file failing integrity check,
- table counts not matching real user state,
- intermittent restore failures.

### Fix options

Best:

```text
Use SQLite backup API or VACUUM INTO equivalent if available.
```

Practical app-level fix:

```text
enter BACKUP_EXPORTING
block app writes through RestoreMaintenanceMode / write gate
cancel/pause workers
checkpoint WAL
copy DB file
verify copied DB integrity
exit BACKUP_EXPORTING
```

But then `isWritesAllowed()` must probably return false for `BACKUP_EXPORTING`, or a separate `isDestructiveWriteAllowed()` / `isSnapshotWriteAllowed()` policy must exist.

Minimum:

```text
global BackupRestoreMutex
all lifecycle coordinators/repositories check maintenance mode before writes
backup holds mutex while checkpoint+copy
```

Priority: highest.

---

## Finding P0-3 — Not all workers check restore maintenance mode

`RestoreMaintenanceMode.enter()` cancels all unique workers from `WorkerSpec.DEFAULTS`.

That is good, but cancellation is not enough because:

- a worker may already be running,
- cancellation is cooperative,
- some workers may continue until their next `isStopped` check,
- some workers do not inject/check `RestoreMaintenanceMode`.

From inspected files:

### Has restore guard

- `BillReminderWorker`
- `ReceiptMatchingWorker`
- `WarrantyExpirationWorker`
- `LocationBackfillWorker`

### Missing visible restore guard

- `DataRetentionWorker`
- `MerchantKeyBackfillWorker`
- `DailyBriefingWorker`

This contradicts the dependency map’s implication that all 7 workers are paused/blocked during restore.

Why it matters:

During restore, a running worker can mutate:

```text
raw_notifications
scanned_receipts
privacy_audit_events
expenses.merchantKey
ai_artifacts
notifications
```

while the DB is being staged/swapped/verified.

### Fix

Inject `RestoreMaintenanceMode` into every worker and check at the start:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    return Result.success()
}
```

For long workers, also check inside loops:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed() || isStopped) break
```

Specifically update:

```text
DataRetentionWorker
MerchantKeyBackfillWorker
DailyBriefingWorker
```

Also add `BackgroundJobRun` logging:

```text
SKIPPED_RESTORE_MODE
```

Priority: highest.

---

## Finding P0-4 — Critical restore recovery can still unblock the app

`AppStartupCoordinator.checkRestoreJournal()` has a `CriticalRecoveryRequired` branch that logs a critical error.

But after the `when`, it does:

```text
if maintenance mode != NORMAL:
    restoreMaintenanceMode.reset()
```

Then startup continues:

```text
registerLifecycleObserver()
scheduleStartupWork()
syncProactiveBriefingWork()
```

Risk:

```text
live DB corrupt
safety backup missing/corrupt
startup logs critical
maintenance mode reset to NORMAL
workers scheduled
app writes to possibly corrupted DB
```

### Fix

Introduce persistent critical state:

```kotlin
RestoreMaintenanceMode.Mode.CRITICAL_RECOVERY_REQUIRED
```

If journal recovery is critical:

```text
do not reset to NORMAL
do not schedule workers
do not sync proactive briefing
show blocking recovery UI
allow only export logs / manual restore / reset DB
```

Also verify safety-backup recovery before deleting the journal.

Priority: highest.

---

## Finding P0-5 — Legacy `importDatabase()` bypasses maintenance mode and journal

`restoreCostBackup()` uses:

- `RestoreMaintenanceMode`,
- `RestoreJournal`,
- staging,
- safety backup,
- swap,
- verification.

But legacy `importDatabase(sourceFile)` performs file staging and live swap without the same maintenance/journal state machine.

It does create a safety backup and rollback on failure, but it does not appear to:

```text
enter restore maintenance mode
pause workers
block notification capture
write restore journal
recover on startup if process dies during legacy import swap
```

If the app still exposes or uses legacy DB import, this is a destructive path with weaker safety than `.costbackup`.

### Fix

Either:

1. remove/disable legacy import in production, or
2. route `importDatabase()` through the same restore state machine as `.costbackup`.

At minimum:

```text
enter RESTORE_PREPARING
begin journal
stage
migrate
safety backup
swap
verify
restart-required
```

Priority: highest if legacy import is reachable.

---

## Finding P1-1 — Post-migration staged DB is not fully verified before live swap

In `.costbackup` restore:

```text
verifyQuick(staged)
open staged with Room to trigger migration
create safety backup
swap staged to live
verify live
```

The code verifies the staged DB before migration, but after Room migration it only checks that opening succeeded.

If a migration succeeds but changes counts incorrectly, the app only discovers this after live swap, then rolls back.

That is better than data loss, but it still performs a destructive swap that could have been avoided.

### Fix

After opening the staged DB with Room, run:

```text
queryRoomCountsForVerification(stagedDatabase)
verifyDatabaseFileStateForVerification(stagedDbFile, ...)
BackupVerifier.verify(stagedDbFile, manifestTableCounts)
```

Only create safety backup and swap after post-migration staged verification passes.

Priority: high.

---

## Finding P1-2 — “Redacted backup” can still include raw receipt images

`ExportAnonymizer` nulls DB fields:

```text
scanned_receipts.rawOcrText
raw_notifications title/text/bigText/subText/extrasJson/parseResult
```

But if `includeReceiptImages = true`, the `.costbackup` bundle can include the original receipt image files.

Receipt images themselves can contain:

- merchant,
- card digits,
- location,
- items,
- timestamps,
- loyalty IDs,
- personal data.

So a backup with:

```text
redacted = true
includeReceiptImages = true
```

is not truly redacted. It is only DB-text-redacted.

### Fix

Make the contract explicit:

Option A:

```text
redacted = true → force includeReceiptImages = false
```

Option B:

```text
call it “redact raw parsed text only”
warn that images remain sensitive
```

Option C:

```text
support image redaction/export thumbnails only
```

In UI, show:

```text
“Receipt images may contain sensitive information even in redacted backups.”
```

Priority: high.

---

## Finding P1-3 — Bundle encryption/extraction can OOM on large backups

`CostbackupBundle.create()` streams ZIP construction to a temp file, which is good.

But `BackupEncryptionService.encrypt(file, outputStream, password)` does:

```kotlin
val plaintext = plaintextFile.readBytes()
```

`CostbackupBundle.extract()` does:

```kotlin
bundleFile.readBytes()
decrypt(...)
ZipInputStream(zipBytes.inputStream())
```

So large DB + many receipt images can exist as multiple large byte arrays in memory.

Symptoms:

- backup creation fails on older phones,
- restore fails for large receipt libraries,
- OOM crash during extract/decrypt,
- inconsistent user experience for power users.

### Fix

Short-term:

```text
set max backup size
show user-facing error before loading huge file
reduce default receipt image inclusion
```

Long-term:

```text
streaming encryption/decryption
chunked archive format
or split DB and assets into separately encrypted entries
```

Priority: high.

---

## Finding P1-4 — Backup verifier can miss required empty tables

`BackupVerifier.countRows()` returns `0` when a table does not exist.

For Tier 1 exact tables, if the manifest expects `0`, then a missing required table can appear to pass count verification.

This is dangerous for newly added tables that are legitimately empty but still required by schema.

Example risk:

```text
expected transaction_events = 0
actual table missing
countRows returns 0
verification passes
```

Room schema validation may catch some cases, but `BackupVerifier` should not rely only on Room.

### Fix

For Tier 1 and Tier 2 tables:

```text
table must exist
then count/check validity
```

Add explicit result state:

```kotlin
MISSING_REQUIRED_TABLE
```

Only Tier 3 optional tables may be absent.

Priority: high.

---

## Finding P1-5 — Verification tiers under-protect lifecycle/currency/privacy tables

`BackupVerifier` classifies these as Tier 2 validity:

```text
transaction_events
receipt_events
receipt_expense_links
recurring_occurrences
recurring_reminder_deliveries
recurring_lifecycle_events
```

And these as Tier 3 optional:

```text
exchange_rates
privacy_audit_events
background_job_runs
```

But for your current architecture:

- transaction events are transaction lifecycle audit,
- receipt links prevent duplicate receipt/expense counting,
- recurring occurrences prevent planned/actual double-count,
- exchange rates affect dashboard/analytics correctness,
- privacy audit events matter for compliance/debugging.

A restore that loses these rows can pass too easily.

### Fix

Reclassify:

```text
Tier 1 exact:
  transaction_events
  receipt_events
  receipt_expense_links
  recurring_occurrences
  recurring_lifecycle_events
  exchange_rates

Tier 2 validity/exact depending policy:
  recurring_reminder_deliveries
  privacy_audit_events
  background_job_runs
```

At minimum, roundtrip scenario tests must assert these are preserved.

Priority: high.

---

## Finding P1-6 — Table counts during backup creation silently fall back to 0

When `createCostBackup()` builds `tableCounts`, it does:

```text
for each BackupVerifier table:
    try count
    catch → 0
```

That means a broken count query can produce a manifest that says a table had 0 rows.

Later restore verification compares against the wrong expected value.

### Fix

For Tier 1 / Tier 2 tables:

```text
if count query fails → backup creation fails
```

Only Tier 3 optional tables can fall back to 0 with a warning.

Priority: high.

---

## Finding P1-7 — Restore receipt asset failures do not fail restore

`restoreReceiptAssets()` catches per-file failures and increments `updateErrors`, but the top-level restore still succeeds.

This may be acceptable if receipt images are optional, but the manifest has:

```text
receiptAssetCount
includeReceiptImages
```

If the user expects full restore, missing images should be visible.

### Fix

Add restore result warnings:

```kotlin
DatabaseImportResult.SuccessNeedsRestart(
    summary,
    warnings = listOf("3 receipt images could not be restored")
)
```

If manifest says receipt images are required and none restore, consider failing.

Priority: medium-high.

---

## Finding P1-8 — Restore failure deletes journal, losing durable diagnostics

`RestoreJournal.failJournal()` writes `FAILED`, then deletes the journal file.

That avoids getting stuck, but it also loses a durable postmortem record.

For a destructive pipeline, users and developers need:

```text
last restore operation
state failed
error message
paths involved
rollback success/failure
timestamp
```

### Fix

Keep:

```text
restore_journal_last_failure.json
```

or write a `BackupRestoreEvent` / `BackgroundJobRun` style event table.

Priority: medium-high.

---

## Finding P1-9 — App uses same Room database instance after file swap

Restore closes the live `AppDatabase`, swaps files, then calls:

```text
database.openHelper.writableDatabase
```

on the injected `database` instance for live verification.

After successful restore, the app returns `SuccessNeedsRestart`, so long-term use is intentionally blocked. Still, using a previously closed Room instance for verification can be fragile.

### Fix

For verification, open a fresh isolated DB instance:

```kotlin
val verifyDb = AppDatabase.fileBuilder(context, AppDatabase.DATABASE_NAME).build()
```

Then close it.

After success, keep app in restart-required mode and avoid additional reads/writes from old singleton DB.

Priority: medium.

---

## Finding P2-1 — Password UX and policy need strengthening

Current UI checks only:

```text
password not blank
```

`BackupEncryptionService` itself uses strong PBKDF2 and AES-GCM, which is good.

But backup UX should also include:

- confirm password,
- warn password cannot be recovered,
- minimum length recommendation,
- optional passphrase strength meter,
- preserve no password in logs/state.

Priority: medium.

---

# 5. Debugging checklist for Pipeline 7

## Backup creation

Check:

- [ ] privacy gate allows encrypted backup,
- [ ] redacted vs full backup policy clear,
- [ ] receipt image inclusion policy clear,
- [ ] global write barrier active,
- [ ] workers paused or blocked,
- [ ] notification capture blocked,
- [ ] WAL checkpoint success,
- [ ] DB snapshot integrity check passes,
- [ ] table counts fail loudly for required tables,
- [ ] manifest database version = current schema,
- [ ] checksums include DB and every included asset,
- [ ] output file is app-private,
- [ ] temp files deleted,
- [ ] large backup memory usage safe.

## Bundle format

Check:

- [ ] magic header validated,
- [ ] unsupported version rejected,
- [ ] wrong password rejected,
- [ ] tampered ciphertext rejected by AES-GCM,
- [ ] tampered ZIP entry rejected by checksum,
- [ ] missing manifest rejected,
- [ ] missing database rejected,
- [ ] ZIP Slip blocked,
- [ ] oversized bundle rejected before OOM.

## Restore staging

Check:

- [ ] enter restore maintenance mode,
- [ ] journal PREPARING written,
- [ ] extraction temp directory cleaned on failure,
- [ ] manifest non-empty,
- [ ] quick staged integrity and FK checks,
- [ ] Room migration triggered on staged DB,
- [ ] post-migration staged verification,
- [ ] safety backup created and verified,
- [ ] journal preserves safetyBackupPath through every transition.

## Live swap

Check:

- [ ] live DB closed,
- [ ] live WAL/SHM handled,
- [ ] live DB moved to pre-restore safely,
- [ ] staged DB copied/moved to live,
- [ ] swap failure rolls back,
- [ ] live verification uses fresh DB instance,
- [ ] full table verification,
- [ ] receipt assets restored,
- [ ] restore warnings surfaced,
- [ ] journal committed only after all required steps,
- [ ] restart-required state blocks writes.

## Startup recovery

Check:

- [ ] PREPARING/STAGED cleans staging only,
- [ ] SAFETY_BACKUP_CREATED does not destroy safety backup,
- [ ] SWAPPING restores safety backup,
- [ ] VERIFYING restores safety backup,
- [ ] recovery verifies restored DB,
- [ ] critical recovery blocks app writes,
- [ ] workers not scheduled during critical recovery,
- [ ] user sees recovery screen,
- [ ] journal not deleted until safe state known.

## Worker / notification protection

Check:

- [ ] every worker injects RestoreMaintenanceMode,
- [ ] every worker checks at start,
- [ ] long workers check during loops,
- [ ] notification capture checks restore mode,
- [ ] lifecycle coordinators check restore mode,
- [ ] repositories/import adapters do not bypass restore guard.

---

# 6. Recommended fix plan

## PR 1 — Fix journal transition state preservation

Change restore code to use mutable journal entries.

Acceptance:

```text
A crash during SWAPPING leaves a journal containing safetyBackupPath.
Startup can restore from it.
```

Add test:

```text
RestoreJournalStatePreservationTest
```

Priority: P0.

---

## PR 2 — Add global backup/restore write barrier

During backup and restore:

```text
block lifecycle writes
block notification capture
block workers
block repository imports
```

Acceptance:

```text
no ExpenseDao/RawNotificationDao/ScannedReceiptDao writes occur during BACKUP_EXPORTING or RESTORE_* modes.
```

Priority: P0.

---

## PR 3 — Add restore guards to all workers

Update:

```text
DailyBriefingWorker
DataRetentionWorker
MerchantKeyBackfillWorker
```

and verify existing guarded workers.

Acceptance:

```text
all 7 WorkerSpec workers check RestoreMaintenanceMode.
```

Priority: P0.

---

## PR 4 — Critical recovery mode

Add:

```text
CRITICAL_RECOVERY_REQUIRED
```

Startup must not reset to normal, schedule workers, or sync AI briefings.

Acceptance:

```text
if recovery fails, app shows blocking recovery state and no background writes happen.
```

Priority: P0.

---

## PR 5 — Unify legacy import with restore state machine

Either remove legacy import or route it through the same journal/maintenance process.

Acceptance:

```text
no live DB file swap can occur without RestoreJournal + RestoreMaintenanceMode.
```

Priority: P0/P1 depending reachability.

---

## PR 6 — Verify staged DB after migration

After Room migrates staged DB:

```text
run full verifier on staged DB
compare counts
only then swap
```

Acceptance:

```text
bad migration is caught before live DB is touched.
```

Priority: P1.

---

## PR 7 — Strengthen BackupVerifier

Rules:

```text
Tier 1/2 required tables must exist.
Tier 1 count query failure fails backup creation.
Lifecycle/link/rate tables get stronger verification.
```

Acceptance:

```text
missing required empty table fails verification.
```

Priority: P1.

---

## PR 8 — Clarify redacted backup semantics

Either:

```text
redacted=true disables receipt images
```

or UI warns:

```text
receipt images are still raw sensitive data.
```

Priority: P1.

---

## PR 9 — Large-backup memory hardening

Short-term:

```text
size limit + user-facing error
```

Long-term:

```text
streaming/chunked encryption.
```

Priority: P1/P2.

---

# 7. Tests to add

## 7.1 `RestoreJournalStatePreservationTest`

Cases:

```text
PREPARING → STAGED → SAFETY_BACKUP_CREATED(path) → SWAPPING
assert journal still has safetyBackupPath
```

Also:

```text
VERIFYING state preserves safetyBackupPath
ROLLING_BACK preserves safetyBackupPath
```

---

## 7.2 `RestoreCrashRecoverySwapTest`

Simulate:

```text
journal state = SWAPPING
safety backup exists
live DB corrupt or missing
startup checkRestoreJournal()
```

Assert:

```text
safety backup restored
journal deleted only after successful verify
maintenance mode normal only after recovery success
```

---

## 7.3 `CriticalRecoveryBlocksStartupTest`

Simulate:

```text
journal state = SWAPPING
safety backup missing/corrupt
live DB corrupt
```

Assert:

```text
mode = CRITICAL_RECOVERY_REQUIRED
workers not scheduled
notification capture blocked
UI gets recovery-required state
```

---

## 7.4 `BackupSnapshotWriteBarrierTest`

Run:

```text
createCostBackup()
```

while attempting:

```text
notification insert
expense create
receipt save
worker write
```

Assert:

```text
writes are blocked or queued
backup snapshot integrity passes
```

---

## 7.5 `AllWorkersRestoreGuardTest`

Static or unit test:

```text
every WorkerSpec.DEFAULTS worker has restore guard or allowlist
```

Explicitly check:

```text
DailyBriefingWorker
LocationBackfillWorker
MerchantKeyBackfillWorker
WarrantyExpirationWorker
BillReminderWorker
ReceiptMatchingWorker
DataRetentionWorker
```

---

## 7.6 `BackupVerifierRequiredTableTest`

Seed:

```text
expectedCounts["transaction_events"] = 0
actual DB missing transaction_events table
```

Assert:

```text
verification fails because required table missing
```

---

## 7.7 `CostbackupBundleTamperTest`

Cases:

```text
wrong password fails
unsupported header version fails
invalid magic fails
missing manifest fails
missing database fails
checksum mismatch fails
zip slip entry fails
tampered ciphertext fails
```

---

## 7.8 `RedactedBackupContractTest`

Seed:

```text
raw notification text
raw OCR text
receipt image
```

Create redacted backup.

Assert chosen contract:

```text
DB raw text removed
receipt image excluded
```

or:

```text
receipt image included but manifest/UI marks backup as containing raw image data
```

---

## 7.9 `BackupRestoreFullRoundtripScenarioTest`

Seed full DB:

```text
expenses
transaction_events
raw_notifications
pending_reviews
scanned_receipts
receipt_events
receipt_expense_links
recurring_occurrences
recurring_reminder_deliveries
recurring_lifecycle_events
exchange_rates
budgets
groups
privacy_audit_events
background_job_runs
receipt assets
```

Run:

```text
createCostBackup()
restoreCostBackup() into fresh DB
```

Assert:

```text
dashboard totals match
analytics totals match
receipt links preserved
recurring no-double-count preserved
exchange rates preserved
privacy audit preserved
assets restored or warnings surfaced
```

---

# 8. Suggested canonical scenario

## `backup_restore_full_app_roundtrip_workers_paused`

Seed:

```text
home currency EUR
expenses:
  grocery 40 EUR
  coffee 10 USD with rate
transaction events:
  CREATED for both
receipt:
  OCR receipt linked to grocery
recurring:
  Netflix rule, May occurrence PAID, June occurrence PLANNED
groups:
  dinner split + reimbursement
budget:
  groceries monthly
privacy audit:
  notification capture allowed
background job:
  receipt matching last run
receipt image:
  one asset file
```

Run:

```text
1. createCostBackup(password, includeReceiptImages = true, redacted = false)
2. restoreCostBackup(bundle, password) into fresh DB
3. simulate startup after SuccessNeedsRestart
4. run dashboard/analytics queries
```

Expected:

```text
restore enters maintenance mode
workers are cancelled and all worker write paths are blocked
journal states preserve safetyBackupPath
staged DB migrates and verifies before swap
live DB verifies
restart-required returned
expenses preserved
transaction events preserved
receipt link preserved
receipt asset restored and imagePath updated
recurring occurrence statuses preserved
dashboard monthly total equals pre-backup
analytics category totals equal pre-backup
exchange rates preserved
privacy audit preserved
no worker writes during restore
maintenance resets only after safe restart
```

This should be one of the highest-priority fed-DB tests.

---

# 9. Most likely real instability sources

Ranked:

1. **Journal transition bug losing safetyBackupPath.**
2. **Backup snapshot copy while writes are still allowed.**
3. **Workers without restore guards.**
4. **Critical recovery reset/scheduling workers after failure.**
5. **Legacy import bypassing journal/maintenance mode.**
6. **Post-migration staged DB not verified before swap.**
7. **Verifier treating missing required empty tables as OK.**
8. **Lifecycle/event/link/rate tables too weakly verified.**
9. **Redacted backup including raw receipt images.**
10. **Large bundle memory pressure.**

---

# 10. Final recommendation

For Pipeline 7, stabilize in this order:

```text
1. Fix RestoreJournal transition state preservation.
2. Add true global write barrier for backup/restore.
3. Add RestoreMaintenanceMode checks to all workers.
4. Add critical recovery mode that blocks startup work.
5. Unify legacy import with the safe restore state machine or disable it.
6. Verify staged DB after migration before live swap.
7. Strengthen BackupVerifier table-existence and tier rules.
8. Clarify redacted-backup image semantics.
9. Add full backup/restore fed-DB roundtrip scenario.
```

Guiding rule:

> No live database file swap should ever happen without maintenance mode, journal state, verified staging, safety backup, and worker/write blocking.

Second guiding rule:

> A restored app is only safe when DB rows, lifecycle events, receipt links, recurring state, exchange rates, privacy audit, and assets are all verified against the pre-backup contract.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — Restore journal safety-backup path can be lost
**STATUS: CONFIRMED — NOT FIXED (requires journal state machine refactor)**

## Finding P0-2 — Backup snapshot copies live DB while writes may still occur
**STATUS: CONFIRMED — NOT FIXED (requires WAL checkpoint + write barrier coordination)**

## Finding P0-3 — Not all workers check RestoreMaintenanceMode
**STATUS: CONFIRMED — NOT FIXED (requires audit of all workers and services)**

## Finding P0-4 — Critical restore recovery resets maintenance mode to NORMAL
**STATUS: CONFIRMED — FIXED**
- `AppStartupCoordinator.checkRestoreJournal()` now sets `RESTORE_COMPLETE_RESTART_REQUIRED` mode and returns early in `CriticalRecoveryRequired` case, preventing workers from being scheduled against a corrupt database.
- Previously, the code fell through to the maintenance mode reset block, which would set NORMAL mode and allow writes.

## Finding P0-5 — Legacy importDatabase() bypasses restore journal / maintenance mode
**STATUS: CONFIRMED — NOT FIXED (requires deprecation or routing through full restore pipeline)**

## Finding P0-6 — Post-migration staged DB is not fully verified
**STATUS: CONFIRMED — NOT FIXED (requires staged verification enhancement)**

## Finding P0-7 — Redacted backup may include raw receipt images
**STATUS: CONFIRMED — NOT FIXED (requires privacy audit of image handling in CostbackupBundle)**

## Finding P0-8 — Bundle encryption reads large files into memory
**STATUS: CONFIRMED — NOT FIXED (requires streaming encryption implementation)**

## Finding P0-9 — Backup verifier can pass missing tables when expected count is 0
**STATUS: CONFIRMED — FIXED**
- `BackupVerifier.countRows()` now returns `-1` (not `0`) when the table doesn't exist, distinguishing "table exists with 0 rows" from "table is missing."
- `TIER_1_EXACT` verification now explicitly checks for `actual == -1` and fails with "Required table X is missing from database" before the count comparison.

## Finding P0-10 / P1-5 — Verification tiers under-protect lifecycle/currency/privacy tables
**STATUS: CONFIRMED — FIXED**
- Promoted 6 lifecycle/event tables from `TIER_2_VALIDITY` to `TIER_1_EXACT`:
  - `transaction_events`, `receipt_events`, `receipt_expense_links`
  - `recurring_occurrences`, `recurring_reminder_deliveries`, `recurring_lifecycle_events`
- These are critical audit/lifecycle records that should be exactly preserved during backup/restore.

---

# 12. New issues discovered

No additional issues beyond those in the original report were found during code verification.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Don't reset maintenance mode after critical recovery | `AppStartupCoordinator.kt` | P0-4 |
| Detect missing required tables in backup verifier | `BackupVerifier.kt` | P0-9 |
| Promote lifecycle/event tables to TIER_1_EXACT | `BackupVerifier.kt` | P1-5 |

---

# 14. Remaining work priority

1. **P0-1**: Fix journal state machine to preserve safetyBackupPath across state transitions
2. **P0-2**: Ensure WAL checkpoint + write barrier before backup snapshot copy
3. **P0-3**: Audit all workers/services for RestoreMaintenanceMode guard
4. **P0-5**: Deprecate or route legacy importDatabase through full restore pipeline
5. **P0-6**: Add post-migration staged DB verification (query critical tables)
6. **P0-7**: Strip raw receipt images from redacted backups in CostbackupBundle
7. **P0-8**: Implement streaming encryption for large bundle files

---

# Sources

- Dependency map  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `DatabaseBackupRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `CostbackupBundle.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/backup/CostbackupBundle.kt

- `BackupVerifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt

- `RestoreJournal.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt

- `RestoreMaintenanceMode.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt

- `BackupEncryptionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/BackupEncryptionService.kt

- `ExportAnonymizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt

- `BackupRestoreViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt

- `DatabaseBackupRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt

- `AppStartupCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt

- `WorkerSpec.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerSpec.kt

- `BillReminderWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt

- `ReceiptMatchingWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt

- `DataRetentionWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt

- `DailyBriefingWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt

- `MerchantKeyBackfillWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt

- `LocationBackfillWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt

- `WarrantyExpirationWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt

- Existing backup test file  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt