# Backup / Restore / Export Deep Analysis

Branch: `master-refactor`

Static review scope:
- database backup/export
- database import/restore
- reset flow
- app-private export files
- reporting exports: CSV / JSON / Xero / QuickBooks / FreshBooks
- file sharing via Android `FileProvider`

## Executive verdict

This area has a good safety foundation:

- WAL checkpoint before DB copy
- app-private export location by default
- staged import before live swap
- Room migration verification on staged DB
- safety backup before import/reset
- schema version guard
- legacy public-backup warning

But for a finance app, the system is not yet strong enough.

The main issue:

> The app currently treats a raw `.db` file as the backup format, but the real product needs a versioned, encrypted, self-describing backup bundle.

Highest-risk problems:

1. backups are plaintext `.db` files
2. backup only copies the database, not necessarily receipt images or external files
3. import verification checks only a few tables
4. import verification allows row-count increases, so duplication can pass
5. live database file swap is not crash-atomic
6. import can happen while app/background workers may still use the DB
7. successful import does not clearly force a clean app restart
8. JSON/CSV exports are reporting exports, not restorable backups
9. export UI loads all expenses into memory
10. non-finite JSON amounts are silently exported as `0.0`

---

# Strong parts

## 1. WAL checkpoint before copying DB

`exportDatabase()` and `createSafetyBackup()` checkpoint WAL before copying the main DB file.

Good. This avoids exporting a stale main DB without recent WAL pages.

## 2. App-private backup location by default

Backups are written under app-private files, not public Downloads.

Good privacy baseline.

## 3. Import validates source before touching live DB

`importDatabase()` checks:

- file exists
- readable
- non-empty
- schema version supported
- required core tables exist
- basic row counts

Good.

## 4. Import uses a staged DB and Room migration verification

The source DB is copied to a staging DB, then opened through Room to trigger migrations and schema validation.

Good pattern.

## 5. Safety backup before destructive import/reset

Before import/reset, the repository creates a safety backup.

Good.

## 6. Unsupported old/new schema versions are blocked

Source DB schema must be between minimum supported and current supported version.

Good.

---

# Critical / high-priority findings

## 1. Full DB backups are plaintext

### Where

`DatabaseBackupRepositoryImpl.exportDatabase()`

The backup is a direct `.db` copy.

### Problem

That DB likely contains highly sensitive data:

- expenses
- raw notifications
- OCR text
- receipt metadata
- merchant history
- locations
- AI artifacts/chat
- groups/splits
- budgets
- warranties
- subscriptions

The app-private location helps while the file stays inside the app sandbox, but once shared/saved/exported, it is plaintext.

### Impact

A user can accidentally upload or share their full financial history.

### Severity

**Critical / privacy**

### Fix

Introduce an encrypted backup format:

```text
.costbackup
  manifest.json
  database.sqlite.enc
  files/
  checksums.json
```

Use password-based encryption or Android Keystore-assisted encryption.

Offer export modes:

1. encrypted full backup
2. redacted backup
3. CSV/accounting report
4. developer/debug raw DB export

Raw `.db` export should be hidden behind debug/advanced mode.

---

## 2. Backup copies only the DB, not necessarily receipt/image files

### Where

`exportDatabase()` copies only `AppDatabase.DATABASE_NAME`.

### Problem

If receipts, images, attachments, or OCR assets are stored as files referenced by DB paths, a DB-only backup will restore broken references.

### Impact

After restore:

- receipt image paths may point to missing files
- warranty proof may be gone
- OCR source images may be gone
- receipt matching audit trail may be incomplete

### Severity

**Critical if receipt images are file-based**

### Fix

Backup must include file assets referenced by DB rows.

Add backup manifest:

```json
{
  "schemaVersion": 1,
  "appDatabaseVersion": 92,
  "createdAt": "...",
  "includes": {
    "database": true,
    "receiptImages": true,
    "exports": false,
    "rawNotifications": true
  }
}
```

During restore, rewrite file paths if needed.

---

## 3. Import verification checks only five tables

### Where

`queryRoomCountsForVerification()` and `SourceValidationSummary`

Currently checked:

- `expenses`
- `categories`
- `merchant_categories`
- `pending_reviews`
- `budgets`

### Problem

Many important tables are not counted or validated:

- receipts
- raw notifications
- groups
- group expenses
- group members
- split assignments
- warranties
- return windows
- recurring expenses
- planned expenses
- subscriptions
- exchange rates
- AI chat/artifacts
- locations/corrections
- savings/goals
- bank connections
- settings-like DB tables

### Impact

A migrated import could drop receipts, groups, warranties, or recurring data and still pass verification as long as those five counts survive.

### Severity

**Critical**

### Fix

Create table coverage tiers.

Example:

```text
Tier 1 financial core:
expenses, pending_reviews, categories, budgets

Tier 2 relationship integrity:
receipts, groups, group_expenses, group_members, warranties, return_windows

Tier 3 enrichment:
locations, merchant mappings, AI artifacts, forecasts

Tier 4 cache/rebuildable:
exchange rates, temporary diagnostics
```

Then verify every table by policy:

- exact count preserved
- allowed repair/rebuild
- allowed cache loss
- explicitly ignored

No table should be silently untracked.

---

## 4. Verification allows duplicate row increases

### Where

`verifyCoreTableCountPreservedForVerification()`

It fails only when:

```text
actualCount < sourceCount
```

### Problem

If import/migration duplicates data, count increases and verification passes.

### Impact

A bad import could turn:

```text
100 expenses → 200 expenses
```

and pass.

That is just as dangerous as data loss.

### Severity

**Critical**

### Fix

Use table-specific policies:

- `expenses`: exact count unless a documented dedupe/repair runs
- `categories`: exact or controlled merge
- `pending_reviews`: exact or controlled status repair
- caches: may differ

Also verify stable fingerprints:

```text
expense id set
dedupe key set
raw notification fingerprint set
group expense link set
receipt id set
```

---

## 5. Live DB replacement is not crash-atomic

### Where

`replaceDatabaseFiles()`

It deletes live DB/WAL/SHM, then renames/copies staged files.

### Problem

If the process dies or the device loses power between delete and copy/rename, the app can be left without a valid DB.

The safety backup helps only if the code resumes and rollback runs. It does not protect against abrupt process death in the middle of file replacement.

### Severity

**Critical**

### Fix

Use an atomic restore protocol:

1. close DB
2. move live DB to `live.db.pre_restore`
3. move staged DB to live path
4. verify open
5. only then delete old backup marker

If crash occurs, startup recovery can detect:

```text
live missing + pre_restore exists
```

and recover.

Also add a `RestoreJournal` file:

```json
{
  "state": "SWAPPING",
  "livePath": "...",
  "backupPath": "...",
  "startedAt": ...
}
```

Startup should complete or roll back unfinished restores.

---

## 6. Import runs while the app may still be active

### Where

`importDatabase()` closes the injected Room DB and swaps files.

### Problem

Other app components may still exist:

- repositories
- flows
- DAOs
- workers
- ViewModels
- background jobs
- notification listener

They may hold references to the old DB or attempt writes during import.

### Impact

Race conditions:

- worker writes after staging verification but before close
- UI reads while DB is closed
- repository uses stale DAO after swap
- pending review/notification pipeline writes to old connection
- import succeeds but app state remains stale

### Severity

**Critical**

### Fix

Add app-wide maintenance mode:

```kotlin
DatabaseMaintenanceGate.enterRestoreMode()
```

It should:

- cancel/suspend workers
- stop notification ingestion
- block writes
- close active screens or show restore overlay
- reject new DB operations
- resume only after restart/reopen

Recommended UX:

> “Restore completed. Restart app now.”

For safety, force process restart or navigate to a clean root after import.

---

## 7. Successful import does not clearly force a clean app restart

### Where

`importDatabase()` reopens `database.openHelper.writableDatabase`.

### Problem

Room can reopen, but the rest of the app may still have:

- cached flows
- old screen state
- old repository assumptions
- old DataStore/DB-derived settings
- stale dashboard/forecast artifacts

### Impact

User may see pre-restore data until app restart or invalidation refresh works.

### Severity

**High**

### Fix

After successful restore:

- mark `RestoreCompleted`
- show mandatory restart prompt
- optionally call activity recreate/process restart
- clear in-memory caches
- cancel/reschedule workers

---

## 8. Backup filenames can collide within the same second

### Where

`DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss"`

### Problem

Multiple exports/safety backups in the same second can use the same filename.

### Impact

A second backup can overwrite the first.

Especially risky because import/reset may create safety backups close together.

### Severity

**High**

### Fix

Use:

```text
yyyy-MM-dd_HH-mm-ss_SSS + random suffix
```

or UUID:

```text
expense_tracker_backup_2026-04-26_13-30-15_9f3a.costbackup
```

Use atomic create-new semantics.

---

## 9. “Meaningful data” check can reject valid non-core backups

### Where

`SourceValidationSummary.hasMeaningfulData()`

It only considers:

- expenses
- categories
- merchant mappings
- pending reviews
- budgets

### Problem

A backup containing only receipts, warranties, groups, subscriptions, settings, or AI artifacts could be considered empty.

### Impact

Potentially valid backups are blocked.

### Severity

**Medium / High**

### Fix

Meaningful data should include all user-owned tables, or the backup manifest should declare content.

---

## 10. Reset database is very destructive

### Where

`DatabaseBackupRepository.resetDatabase()`

### Problem

The interface exposes a method that resets the DB after creating a safety backup.

Even if currently hidden, this is a dangerous capability.

### Impact

If wired incorrectly, it can wipe all financial history.

### Severity

**High**

### Fix

Move behind:

```text
DangerousDataWipeUseCase
```

Require:

- explicit typed confirmation
- safety backup success
- backup share/save option
- app restart after wipe
- debug/admin guard if not user-facing

---

# Reporting export findings

## 11. CSV/JSON export is not a restorable backup

### Where

`ExportOptionsViewModel`

CSV/JSON export includes mostly:

- id
- date
- merchant
- amount
- currency
- category
- notes

### Problem

It omits many fields needed to reconstruct app state:

- transaction type
- source raw notification
- dedupe key
- merchant key
- receipt links
- shared-expense fields
- category id
- location
- review status
- warranty/subscription links
- recurring/planned source metadata
- normalized/base currency snapshot

### Impact

Users may think JSON export is a backup, but it is only a report.

### Severity

**High UX / data portability**

### Fix

Clearly label:

```text
CSV/JSON report export
```

Separate from:

```text
Full app backup
```

If JSON should be restorable, create a complete versioned export schema.

---

## 12. Export UI loads all expenses into memory

### Where

`ExportOptionsViewModel.generateExport()`

It calls `getExpensesBetweenForExport(...)` and stores all expenses in a list before writing.

### Impact

Large user histories can cause:

- memory pressure
- slow export
- UI delays
- inconsistent data if records change during export

### Severity

**High**

### Fix

Use deterministic paging or an ID snapshot:

```text
capture stable expense IDs
stream rows by ID pages
write file incrementally
```

The accounting export area already has a deterministic pager concept; reuse it here.

---

## 13. Export has no snapshot consistency

### Where

`ExportOptionsViewModel.generateExport()`

### Problem

The count is loaded separately from the final list, and the final list is read outside a stable snapshot.

### Impact

If expenses change during export:

- count differs from rows
- report misses/duplicates edits
- preview may not match saved file

### Severity

**High**

### Fix

Use one export transaction/snapshot:

```text
ExportRun(id, startedAt, filters)
stable row IDs
stream those exact IDs
```

---

## 14. JSON export silently converts invalid numbers to `0.0`

### Where

`formatJsonNumber(value: Double)`

Non-finite values become `"0.0"`.

### Problem

If an amount is NaN/infinite due to upstream bug, export silently corrupts it.

### Impact

A bad amount becomes a real zero amount in exported JSON.

### Severity

**High**

### Fix

Fail export or emit explicit null/error:

```json
"amount": null,
"amountError": "NON_FINITE"
```

For finance, silent numeric substitution is unsafe.

---

## 15. Date range validation is weak

### Where

`setDateRange(startDate, endDate)`

No clear guard for:

- start after end
- same instant
- date-picker midnight/exclusive-end confusion
- future-only range

### Impact

User can generate empty/wrong exports.

### Severity

**Medium**

### Fix

Validate:

```text
start < end
end adjusted to end-of-day or half-open next-day
range not absurdly huge without confirmation
```

---

## 16. Export files can accumulate in app-private storage

### Where

`ExportOptionsViewModel.generateExport()`

Creates export files and stores file path in UI state.

### Problem

`clearExport()` clears UI state but does not delete the generated file.

### Impact

Sensitive reports accumulate in app files/cache.

### Severity

**Medium / privacy**

### Fix

Add export cleanup:

- delete temp file when dismissed if not saved/shared
- keep only recent N exports
- expose “clear generated exports”
- store reports under cache if temporary

---

# Recommended fix order

## PR 1 — Create encrypted backup bundle format

Replace raw `.db` export with:

```text
.costbackup
```

containing:

- manifest
- encrypted DB
- included files
- checksums
- app/schema version
- backup format version

## PR 2 — Add restore maintenance mode

Before import:

- pause workers
- stop notification ingestion
- block writes
- close active DB users
- show full-screen restore state

After import:

- force clean restart/reopen

## PR 3 — Make restore crash-recoverable

Add restore journal and atomic swap protocol.

Startup should detect incomplete restore and roll back or complete.

## PR 4 — Expand import verification

Track all user-owned tables.

Use exact counts/fingerprints, not just “actual >= source”.

## PR 5 — Include file assets

Backup/restore receipt images and any referenced files.

Rewrite paths on restore.

## PR 6 — Separate backup vs report export

UI should show:

- Full encrypted backup
- Redacted export
- CSV/JSON report
- Accounting export

Do not imply CSV/JSON is restorable unless it is.

## PR 7 — Stream report exports

Use deterministic paging / stable ID snapshot.

Do not load all rows into memory.

---

# Regression tests to add

1. Two backups created in same second do not collide.
2. Full backup restore preserves all tracked table counts.
3. Restore fails if any protected table loses rows.
4. Restore fails if protected table gains unexpected duplicate rows.
5. Backup includes receipt image files and restore rewrites paths.
6. Import cannot run while notification ingestion writes.
7. Import cannot run while background workers are active.
8. Crash during DB swap recovers on next startup.
9. Successful restore forces clean app restart/reopen.
10. Encrypted backup cannot be read as plaintext SQLite.
11. Wrong password fails without touching live DB.
12. Raw DB export is unavailable in normal production UI.
13. JSON report export is clearly labeled non-restorable.
14. Large export streams pages without loading all rows.
15. Export during concurrent edit uses stable row snapshot.
16. Non-finite amount causes export failure, not `0.0`.
17. Dismissing unsaved export deletes temporary report file.
18. Reset database requires explicit confirmation and safety backup.

---

# Top three fixes

If only three things get fixed first:

1. **Replace plaintext DB backups with encrypted backup bundles.**
2. **Make restore atomic, maintenance-gated, and restart-based.**
3. **Expand verification beyond five tables and reject both data loss and duplicate increases.**

These are the biggest data-preservation and privacy wins.

---

# Sources reviewed

- `DatabaseBackupRepositoryImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt

- `DatabaseBackupRepository.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt

- `ExportOptionsScreen.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsScreen.kt

- `ExportOptionsViewModel.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt

- Earlier export/report files also relevant:
  - `AccountingExportRepository.kt`
  - `DeterministicExpenseExportPager.kt`
  - `AccountingExportPolicy.kt`
  - `AccountingExporters.kt`