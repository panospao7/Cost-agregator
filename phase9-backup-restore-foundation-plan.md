# Phase 9 — Backup / Restore Foundation Implementation Plan

## 0. Phase 9 Mission

Phase 9 should turn backup/restore from a debug-only raw database copy into a safe, user-facing, encrypted, crash-safe, complete backup system.

Current audit problems:

- backup is still mostly DB-file based
- encrypted backup is device-key-bound, not portable
- no `.costbackup` bundle format
- receipt images are not included
- no manifest or checksums
- no restore journal
- no restore maintenance mode
- workers and notification ingestion can write during restore
- restore verification checks only 5 tables
- verification allows duplicate row increases
- restore does not force clean restart
- plaintext raw DB export is still possible
- backup UI exists only through Debug
- report exports are confused with restorable backups
- export files accumulate
- JSON export converts non-finite values to `0.0`

Target outcome:

1. User-facing encrypted backup/restore.
2. Portable `.costbackup` bundle.
3. DB + receipt assets + manifest + checksums.
4. User-provided password support.
5. Crash-safe restore with journal.
6. Maintenance mode that pauses workers and notification ingestion.
7. Full verification across protected tables.
8. Mandatory app restart after restore.
9. Clear separation between backup and report/accounting export.
10. Tests for restore crash, wrong password, asset restore, and table parity.

---

# 1. Preconditions

Before Phase 9 starts, stabilize Phases 6–8:

1. App compiles.
2. Hilt graph compiles.
3. Room schema/migration tests pass.
4. Worker registry/scheduler state is stable enough to pause/cancel/resume workers.
5. Privacy gates are wired for raw backup/export.
6. DB version is known.

Run:

```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:kaptDebugKotlin
./gradlew.bat :app:testDebugUnitTest
```

If Phase 9 adds new tables/columns, bump DB version from current version, e.g. `106 -> 107`.

---

# 2. Non-goals

Do not use Phase 9 to:

- redesign accounting export formats
- build cloud sync
- build automatic cloud backup
- implement Google Drive/Dropbox integration
- rewrite every Debug screen
- make CSV/JSON exports restorable backups
- support arbitrary future schema imports beyond current app migration chain
- support multi-device merge/conflict resolution

Phase 9 is about **single-device full backup/restore**, not sync.

---

# 3. Target Backup Types

## 3.1 Full encrypted backup

User-facing primary option.

Format:

```text
.costbackup
```

Contents:

- encrypted database copy
- optional receipt image assets
- manifest
- checksums
- backup metadata
- format version

This is the only default backup type.

## 3.2 Redacted encrypted backup

Similar to full encrypted backup but excludes/scrubs raw data:

- raw notification content
- raw OCR text
- debug data
- AI conversation history if applicable
- optionally precise location

Useful for privacy-conscious backup sharing or support.

## 3.3 Raw DB export

Debug/developer only.

- hidden behind debug/developer mode
- never default
- scary confirmation
- privacy audit event
- optionally disabled entirely in production builds

## 3.4 Report/accounting exports

Non-restorable reports:

- CSV
- JSON
- PDF
- QuickBooks IIF
- Xero CSV
- FreshBooks CSV

Must be labeled clearly:

```text
Reports are not backups and cannot restore your app data.
```

---

# 4. Backup Bundle Format

## 4.1 File extension

Use:

```text
.costbackup
```

## 4.2 Container

Recommended implementation:

- ZIP container
- each payload entry encrypted or whole archive encrypted

Simpler safe approach:

1. Build a temporary directory:
   - `manifest.json`
   - `checksums.json`
   - `database.sqlite`
   - `files/receipts/...`
2. Zip it to temp.
3. Encrypt entire zip using AES-GCM.
4. Write final `.costbackup`.

This keeps the external file opaque and encrypted.

Internal temp files must be deleted after export.

## 4.3 Manifest

Add:

`domain/backup/BackupManifest.kt`

Suggested schema:

```kotlin
data class BackupManifest(
    val backupFormatVersion: Int,
    val appVersionName: String?,
    val appVersionCode: Long?,
    val databaseSchemaVersion: Int,
    val createdAt: Long,
    val createdAtIso: String,
    val deviceInfo: BackupDeviceInfo?,
    val encrypted: Boolean,
    val encryption: BackupEncryptionMetadata,
    val contents: BackupContents,
    val tableCounts: Map<String, Long>,
    val assetCount: Int,
    val totalAssetBytes: Long,
    val privacyOptions: BackupPrivacyOptions,
    val warnings: List<String>
)
```

`BackupContents`:

```kotlin
data class BackupContents(
    val includesDatabase: Boolean,
    val includesReceiptImages: Boolean,
    val includesRawNotifications: Boolean,
    val includesRawOcrText: Boolean,
    val includesDebugData: Boolean,
    val includesPrivacyAuditEvents: Boolean
)
```

## 4.4 Checksums

Add:

`checksums.json`

Suggested:

```kotlin
data class BackupChecksums(
    val entries: List<BackupEntryChecksum>
)

data class BackupEntryChecksum(
    val path: String,
    val sha256: String,
    val sizeBytes: Long
)
```

Verify checksums before restore.

---

# 5. Encryption Design

## 5.1 Current problem

Current encrypted backup uses an auto-generated password stored in `SecureKeyStorage`.

This means:

- encrypted file is protected at rest
- but not portable to another device
- restore after uninstall/device loss may fail because key is gone

## 5.2 Target

Support user-provided backup password.

Use existing `BackupEncryptionService` primitives:

- AES-256-GCM
- PBKDF2-HMAC-SHA256
- 600,000 iterations
- random salt
- random IV
- 128-bit GCM tag

## 5.3 Backup password UX

Add:

`BackupPasswordProvider`

For export:

- require password
- require confirmation
- show warning: password cannot be recovered
- optional biometric/device-key convenience later, but not as only key

For import:

- prompt for password
- wrong password fails before touching live DB

## 5.4 Encryption metadata

Store non-sensitive metadata outside encrypted payload only if needed.

Option A:

- whole file is raw encrypted blob
- app tries to decrypt based on header

Option B, preferred:

Small unencrypted header:

```text
COSTBACKUP1
salt
iv
kdf params
ciphertext
```

Do not store manifest outside encryption if it contains sensitive table counts or settings.

---

# 6. Backup Writer Architecture

## 6.1 New components

Create:

- `BackupBundleWriter`
- `BackupBundleReader`
- `BackupManifestBuilder`
- `BackupChecksumService`
- `BackupDatabaseSnapshotter`
- `BackupAssetCollector`
- `BackupTempFileManager`

Suggested packages:

```text
domain/backup/
data/backup/
```

## 6.2 Writer flow

`createBackup(options, password)`:

1. Check privacy gate.
2. Enter backup read lock / maintenance read mode if needed.
3. WAL checkpoint.
4. Create temp workspace.
5. Create database snapshot copy.
6. If redacted:
   - sanitize snapshot, not live DB
7. Collect table counts from snapshot.
8. Collect receipt image assets if enabled.
9. Copy assets into workspace.
10. Build manifest.
11. Build checksums.
12. Zip workspace.
13. Encrypt zip with password.
14. Write final `.costbackup` with collision-safe name.
15. Verify final file exists and can be decrypted/read minimally.
16. Cleanup temp workspace.
17. Write privacy audit event.

## 6.3 Filename collision fix

Current timestamp has second precision.

Use:

```text
expense_tracker_backup_yyyy-MM-dd_HH-mm-ss_SSS_<shortUuid>.costbackup
```

Example:

```text
expense_tracker_backup_2026-05-02_14-10-33-512_a8f31c.costbackup
```

---

# 7. Receipt Asset Backup

## 7.1 Current issue

Receipt images in `filesDir/receipts/` are not included.

`ReceiptAssetStore.generateBackupManifest()` exists but is unused.

## 7.2 Target

When `includeReceiptImages = true`:

1. Query receipts with image paths.
2. Validate file exists and is readable.
3. Compute SHA-256.
4. Copy into bundle under:

```text
files/receipts/<receiptId>_<filename>
```

5. Store mapping in manifest:

```kotlin
data class ReceiptAssetBackupEntry(
    val receiptId: Long,
    val originalPath: String,
    val bundlePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String?
)
```

## 7.3 Restore path rewriting

On restore:

1. Extract files into new app-private receipt directory.
2. Generate new safe filenames.
3. Update `scanned_receipts.imagePath` in staged DB to new paths.
4. Verify every restored path exists.
5. For missing optional assets:
   - mark warning
   - keep DB row but clear imagePath or mark missing depending policy

Do not restore absolute paths from old device.

---

# 8. Backup Privacy Options

Add:

```kotlin
data class BackupOptions(
    val includeReceiptImages: Boolean,
    val includeRawNotifications: Boolean,
    val includeRawOcrText: Boolean,
    val includeDebugData: Boolean,
    val includePrivacyAuditEvents: Boolean,
    val includePreciseLocations: Boolean,
    val redacted: Boolean
)
```

Defaults:

- include receipt images: true for full backup
- include raw notifications: false
- include raw OCR: false
- include debug data: false
- include privacy audit events: false
- include precise locations: ask user / false by default if privacy-first
- redacted: true unless user explicitly chooses full raw backup

Important:

- never sanitize live DB
- always sanitize temp snapshot

---

# 9. Restore Maintenance Mode

## 9.1 Current issue

Restore runs while app is fully active:

- workers can write
- notification listener can insert raw notifications
- UI can modify DB
- repositories/flows hold stale DB state

## 9.2 Target components

Add:

- `DatabaseMaintenanceGate`
- `MaintenanceModeRepository`
- `WorkerPauseManager`
- `RestoreCoordinator`

## 9.3 Maintenance mode states

```kotlin
enum class DatabaseMaintenanceMode {
    NORMAL,
    BACKUP_EXPORTING,
    RESTORE_PREPARING,
    RESTORE_SWAPPING,
    RESTORE_VERIFYING,
    RESTORE_ROLLING_BACK,
    RESTORE_COMPLETE_RESTART_REQUIRED
}
```

## 9.4 Enter restore mode

Before import modifies anything:

1. Set maintenance mode to `RESTORE_PREPARING`.
2. Block new write operations at lifecycle coordinators where possible:
   - transaction lifecycle
   - receipt lifecycle
   - notification processing
   - recurring lifecycle
3. Cancel/pause all workers through Phase 8 scheduler:
   - data retention
   - location backfill
   - bill reminder
   - receipt matching
   - daily briefing
   - warranty expiration
   - merchant key backfill
4. Stop or pause notification ingestion:
   - `NotificationCaptureService` checks gate
   - Boot/Restart receivers respect mode
5. UI shows restore in progress.
6. Wait briefly for active operations to finish, or fail if timeout.

## 9.5 Exit restore mode

After successful restore:

- set `RESTORE_COMPLETE_RESTART_REQUIRED`
- force restart prompt
- do not resume workers in current process
- after restart, scheduler resumes workers

After rollback:

- set `NORMAL`
- resume workers
- show failure message

---

# 10. Crash-Safe Restore Journal

## 10.1 Current issue

If app crashes between deleting live DB and copying staged DB, user may lose live DB.

## 10.2 Restore journal file

Create:

`RestoreJournalManager`

Journal path:

```text
filesDir/restore_journal.json
```

Journal:

```kotlin
data class RestoreJournal(
    val operationId: String,
    val state: RestoreJournalState,
    val startedAt: Long,
    val sourceBackupPath: String,
    val stagedDbPath: String?,
    val safetyBackupPath: String?,
    val liveDbPath: String,
    val error: String?
)
```

States:

- `PREPARING`
- `STAGED`
- `SAFETY_BACKUP_CREATED`
- `SWAPPING`
- `VERIFYING`
- `ROLLING_BACK`
- `COMPLETE`
- `FAILED`

## 10.3 Startup recovery

At app startup:

1. Check for restore journal.
2. If state is `COMPLETE`, delete journal.
3. If state is before swap, delete staging and clear journal.
4. If state is `SWAPPING` or `VERIFYING`:
   - if live DB valid and expected, complete
   - else restore from safety backup
5. If rollback fails:
   - show critical recovery screen
   - do not continue normal app startup

## 10.4 Atomic-ish swap protocol

Safer file swap:

1. Close Room.
2. Ensure safety backup exists and verified.
3. Move live DB to `live.pre_restore`.
4. Move staged DB to live path.
5. Verify live DB.
6. Delete `live.pre_restore` only after success.

Avoid deleting the only live copy before replacement is ready.

---

# 11. Restore Reader Flow

`restoreBackup(file, password)`:

1. Enter maintenance mode.
2. Create restore journal.
3. Validate source file exists/readable.
4. Decrypt backup with password into temp workspace.
5. Verify manifest.
6. Verify checksums.
7. Extract DB snapshot.
8. Validate DB schema version.
9. Open staged DB through Room migration.
10. Verify staged DB:
    - schema version current
    - integrity check
    - foreign key check
    - protected table counts
    - asset references if included
11. Create safety backup.
12. Swap live DB with journal.
13. Restore receipt assets.
14. Reopen DB and verify live import.
15. Set restart-required state.
16. Return `SuccessNeedsRestart`.

Wrong password must fail before maintenance destructive operations or live swap.

---

# 12. Import Verification Expansion

## 12.1 Current issue

Only 5 tables checked:

- expenses
- categories
- merchant_categories
- pending_reviews
- budgets

And condition is `actual >= source`, allowing duplicates.

## 12.2 Protected table tiers

Create:

`BackupVerificationPolicy`

### Tier 1 — exact count required

User/business data:

- expenses
- categories
- merchant_categories
- pending_reviews
- budgets
- scanned_receipts
- warranties
- return_windows
- manual_recurring_expenses
- planned_expenses
- recurring_occurrences
- recurring_reminder_deliveries
- savings_goals
- savings_sweep_plan
- expense_groups
- group_members
- group_expenses
- split_templates
- split_item_assignments
- mileage_tracking
- investments
- investment_values
- bank_connections
- subscription_candidates
- subscription_price_history
- subscription_usage
- receipt_item_categorizations
- email_receipt_sources
- health_score_history
- budget_forecasts
- budget_adjustment_recommendations
- budget_adjustment_events
- spending_challenges
- user_corrections
- source_stats

### Tier 2 — count can differ but must be valid

Caches/derived/audit/background:

- ai_artifacts
- transaction_events
- receipt_events
- privacy_audit_events
- background_job_runs
- background worker state
- stress_forecast_snapshots
- spending_personality_profiles
- prompt_states
- anomaly_alerts
- recommendations

### Tier 3 — optional/external/cache

- exchange_rates
- location caches if any
- debug data

## 12.3 Verification rules

For Tier 1:

- exact count equality after migration unless documented migration transforms table
- optionally ID set equality for stable ID tables
- no duplicate primary keys
- important unique indexes valid
- foreign key check passes

For Tier 2:

- schema valid
- row count non-negative
- no FK violations
- can be dropped/recomputed if needed

For all:

```sql
PRAGMA integrity_check;
PRAGMA foreign_key_check;
```

## 12.4 Duplicate increase fix

Replace:

```kotlin
actualCount >= sourceCount
```

with:

```kotlin
actualCount == expectedCount
```

For tables where migration legitimately changes counts, document expected delta.

---

# 13. Meaningful Data Check Fix

Current backup with only receipts/warranties/groups/subscriptions can be rejected as empty.

New meaningful data check should count all user-owned tables.

Meaningful if any of:

- expenses
- categories
- receipts
- warranties
- return windows
- recurring rules
- planned expenses
- groups
- group expenses
- subscriptions
- savings goals
- budgets
- investments
- mileage records
- bank connections

Do not require expenses/categories only.

---

# 14. App Restart After Restore

## 14.1 Current issue

`SuccessNeedsRestart` exists but is never emitted.

## 14.2 Target

After successful restore:

- always return `SuccessNeedsRestart`
- show non-dismissible restart screen/dialog
- block normal UI until restart
- cancel workers until restart
- restart app or ask user to close/reopen

Implementation options:

### Option A — soft process restart

Use an intent to launch root activity, clear task, then kill process.

### Option B — user-driven restart

Show:

```text
Restore complete. Restart the app to finish.
```

Disable navigation except restart button.

Recommended: Option B first, safer.

---

# 15. Backup UI

## 15.1 New production screen

Add:

- `BackupScreen.kt`
- `BackupViewModel.kt`

Location:

```text
ui/screens/backup/
```

Features:

### Backup

- Create full encrypted backup
- Choose include receipt images
- Choose include raw notification/OCR data, with warning
- Enter backup password
- Confirm password
- Save/share `.costbackup`

### Restore

- Pick `.costbackup`
- Enter password
- Show manifest preview after decrypt:
  - created date
  - schema version
  - app version
  - table summary
  - receipt image count
  - raw data included yes/no
- Confirm destructive restore
- Create safety backup
- Run restore
- Show restart required

### Reports

Link to export/report screen, clearly separate.

## 15.2 Debug screen changes

Keep debug raw DB tools behind developer section.

Add warnings:

- raw DB contains sensitive data
- raw DB not portable secure backup
- use `.costbackup` for normal backup

---

# 16. Report Export Cleanup

## 16.1 Current issue

Files in `cacheDir/exports/` accumulate.

## 16.2 Add

`ExportFileCleanupManager`

Responsibilities:

- delete export temp file when UI clears share state
- keep only last N report exports
- purge exports older than TTL
- integrate with DataRetentionWorker or separate cleanup

## 16.3 Streaming report exports

Ensure all exports use paging:

- generic CSV
- JSON
- PDF if feasible
- accounting export

Avoid loading all expenses into memory.

## 16.4 Non-finite amount handling

Current JSON export can convert NaN/Infinity to `0.0`.

Fix:

- fail export if amount is not finite
- log/report row ID
- do not silently mutate financial values

---

# 17. Maintenance Mode Write Blocking

## 17.1 Critical write paths to block

During restore:

- `TransactionLifecycleCoordinator.createExpense/update/delete`
- `ReceiptLifecycleCoordinator`
- `NotificationProcessingPipeline`
- `NotificationRepository.processAndSave`
- `CsvExpenseImporter`
- `RecurringLifecycleCoordinator`
- `BudgetRepository` writes
- `GroupsRepositoryImpl` writes
- `PlannedExpenseRepository` writes
- worker writes

## 17.2 Implementation options

### Option A — coordinator/repository checks

Inject `DatabaseMaintenanceGate` into write owners.

Before write:

```kotlin
maintenanceGate.assertWritesAllowed()
```

### Option B — Room-level interceptor

Harder and not recommended now.

Use Option A.

---

# 18. Safety Backup Improvements

## 18.1 Current issue

Safety backup is raw `.db`.

## 18.2 Target

Safety backup remains app-internal and can be raw because it is private, but improve:

- include WAL/SHM properly or checkpoint first
- manifest with timestamp/schema/counts
- retention last 3 is okay
- restore journal references safety backup
- verify safety backup before swap

Do not expose safety backup to user unless debugging.

---

# 19. Scheduled Backup Decision

Audit marks no scheduled backup as medium.

For Phase 9 foundation:

- do not auto-backup by default
- add architecture for future scheduled backups
- maybe add reminder prompt: “Create backup monthly”
- scheduled backup requires storage destination/product decision

Optional later Phase:

- WorkManager scheduled encrypted backup
- user-selected SAF URI destination
- password handling strategy

---

# 20. PR Implementation Plan

## PR 0 — Baseline and docs

### Goal

Document backup/restore contract.

### Actions

1. Add `docs/development/BACKUP_RESTORE_FOUNDATION.md`.
2. Document:
   - backup types
   - restore states
   - maintenance mode
   - verification tiers
   - supported schema versions
   - non-restorable reports
3. Record current tests and failures.
4. No behavior change.

### Done when

- scope is explicit.

---

## PR 1 — Backup bundle models and encryption header

### Add

- `BackupBundle.kt`
- `BackupManifest.kt`
- `BackupChecksums.kt`
- `BackupOptions.kt`
- `BackupEncryptionMetadata.kt`
- `BackupPasswordProvider`

### Update

- `BackupEncryptionService` to support header/container format
- user password encryption/decryption

### Tests

- encrypt/decrypt with password
- wrong password fails
- output is not SQLite plaintext
- two encryptions with same password produce different bytes

---

## PR 2 — Bundle writer for DB-only backup

### Goal

Create `.costbackup` with encrypted DB and manifest, no assets yet.

### Add

- `BackupBundleWriter`
- `BackupManifestBuilder`
- `BackupChecksumService`
- `BackupTempFileManager`

### Flow

- checkpoint
- snapshot DB
- sanitize snapshot depending options
- manifest
- checksums
- zip
- encrypt
- output collision-safe filename

### Tests

- creates `.costbackup`
- manifest present after decrypt
- table counts included
- raw OCR/notification scrubbed by default
- temp files deleted
- same-second backups do not collide

---

## PR 3 — Bundle reader and validation

### Add

- `BackupBundleReader`
- `BackupBundleValidator`

### Capabilities

- decrypt
- read manifest
- verify checksums
- extract staged DB
- reject wrong password
- reject missing manifest
- reject checksum mismatch
- reject unsupported format version

### Tests

- wrong password leaves live DB untouched
- corrupt checksum rejected
- unsupported format rejected
- manifest preview works

---

## PR 4 — Maintenance mode

### Add

- `DatabaseMaintenanceGate`
- `MaintenanceModeRepository`
- `WorkerPauseManager`

### Wire

- backup restore coordinator
- workers/scheduler
- notification capture service
- key write coordinators

### Tests

- writes blocked during restore
- workers cancelled/paused
- notification ingestion denied
- mode clears on failure/rollback
- backup export can allow reads while blocking writes if chosen

---

## PR 5 — Restore journal and crash recovery

### Add

- `RestoreJournal`
- `RestoreJournalManager`
- startup recovery hook

### Implement

- journal states
- safety backup verification
- crash recovery on startup
- atomic-ish swap protocol

### Tests

- crash before swap cleans staging
- crash during swap restores safety backup
- crash after swap verification completes restore
- journal deleted only after success

---

## PR 6 — Restore `.costbackup` DB-only path

### Goal

Restore encrypted DB bundle without assets first.

### Flow

- enter maintenance
- decrypt/extract
- validate DB
- migrate through Room
- safety backup
- swap
- verify
- return `SuccessNeedsRestart`

### Tests

- restore succeeds
- restart-required emitted
- wrong password no live touch
- verification failure rolls back
- safety backup used

---

## PR 7 — Full verification expansion

### Add

- `BackupVerificationPolicy`
- `BackupTableVerifier`
- table tier registry

### Replace

- 5-table count verification
- `actual >= source`

With:

- Tier 1 exact counts
- Tier 2 validity checks
- PRAGMA integrity/foreign-key checks
- expected migration deltas

### Tests

- all protected tables checked
- row loss fails
- unexpected duplicate row gain fails
- receipt-only backup is meaningful
- group-only/subscription-only backup meaningful

---

## PR 8 — Receipt image asset backup/restore

### Add

- `BackupAssetCollector`
- `BackupAssetRestorer`

### Use

- `ReceiptAssetStore.generateBackupManifest()`

### Implement

- include receipt images
- checksums
- restore to new app paths
- rewrite `scanned_receipts.imagePath`
- missing asset warnings

### Tests

- backup includes image
- restore image exists
- DB path rewritten
- checksum mismatch fails
- missing optional asset gives warning or failure according policy

---

## PR 9 — Production backup UI

### Add

- `BackupScreen`
- `BackupViewModel`

### Features

- create backup
- choose options
- password input
- restore picker
- manifest preview
- destructive restore confirmation
- progress states
- restart-required UI

### Tests

- password mismatch blocks export
- restore confirmation required
- restart-required shown
- debug raw DB not exposed as normal backup

---

## PR 10 — Report/export cleanup

### Implement

- clear temp export on dismiss
- TTL cleanup
- last-N cleanup
- fail on non-finite amounts
- label JSON/CSV as non-restorable
- stream/paginate large exports

### Tests

- export clear deletes file
- old exports purged
- NaN/Infinity fails
- large export pages
- labels are correct

---

## PR 11 — Debug/raw DB containment

### Actions

- raw `.db` export debug-only
- privacy gate for raw export
- warning confirmation
- audit event
- do not call it “backup” in production UI

### Tests

- production UI cannot trigger raw DB export
- debug raw export still works if enabled
- raw export denied when privacy gate denies

---

## PR 12 — Final guardrails and docs

### Guardrails

Flag:

- direct raw DB copy outside backup module
- restore without maintenance mode
- restore without journal
- backup without manifest
- backup without checksum
- `.db` export from production UI
- report export labeled as backup
- backup filename without UUID/millis
- verification using `>=` count

### Docs

Update:

- backup format spec
- restore safety protocol
- supported versions
- user password warning
- receipt image behavior
- privacy defaults
- report vs backup distinction

---

# 21. Test Matrix

## Backup tests

- encrypted `.costbackup` created
- two backups same second do not collide
- manifest valid
- checksum valid
- default backup excludes raw data
- full backup can include raw data if opted in
- temp files cleaned
- encrypted file cannot be opened as SQLite
- wrong password fails

## Restore tests

- DB-only bundle restores
- image bundle restores
- wrong password live DB untouched
- corrupt bundle live DB untouched
- unsupported format live DB untouched
- migration applied
- safety backup created
- rollback works
- restart-required emitted

## Crash tests

- crash before swap
- crash during swap
- crash after live replace before verify
- startup recovery completes or rolls back

## Maintenance tests

- workers cancelled
- notification ingestion blocked
- transaction writes blocked
- receipt writes blocked
- restore mode exits correctly

## Verification tests

- Tier 1 row loss fails
- Tier 1 duplicate gain fails
- Tier 2 valid differences allowed
- all meaningful-data table groups accepted
- FK check failure rejects
- integrity check failure rejects

## Asset tests

- image path rewritten
- image checksum mismatch rejects
- missing optional asset handled
- orphan image not restored unless manifest includes it

## Export tests

- report files cleaned
- non-finite amount fails
- CSV/JSON report not labeled backup
- large export streams pages

---

# 22. Acceptance Criteria

Phase 9 is complete when:

1. User-facing `.costbackup` format exists.
2. Backups are encrypted with user-provided password.
3. Backup contains manifest and checksums.
4. DB snapshot is sanitized by default according to privacy options.
5. Receipt images can be included and restored.
6. Restore uses maintenance mode.
7. Workers are paused/cancelled during restore.
8. Notification ingestion is blocked during restore.
9. Restore journal exists.
10. Startup crash recovery handles incomplete restore.
11. Safety backup rollback works.
12. Restore verification covers all protected tables.
13. Verification uses exact counts for Tier 1 tables.
14. Meaningful data check covers non-expense data.
15. Successful restore returns/requires restart.
16. Production backup UI exists outside Debug.
17. Raw DB export is debug-only and gated.
18. Report exports are clearly non-restorable.
19. Export temp files are cleaned.
20. Tests cover wrong password, corrupted backup, crash during swap, image restore, worker pause, and count mismatch.

---

# 23. Recommended Implementation Order

1. Backup models and encryption header.
2. DB-only `.costbackup` writer.
3. Bundle reader/validator.
4. Maintenance mode.
5. Restore journal.
6. DB-only restore path.
7. Full verification expansion.
8. Receipt image asset backup/restore.
9. Production backup UI.
10. Report/export cleanup.
11. Raw DB debug containment.
12. Guardrails and docs.

This order builds the safe bundle format first, then restore safety, then completeness with assets, then UI/export cleanup.