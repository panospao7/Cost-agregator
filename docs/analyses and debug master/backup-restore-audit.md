# Phase 9 — Backup / Restore Foundation Audit

**Date:** 2026-05-02
**Scope:** ALL `.kt` files for backup, restore, export, import, and data migration code
**App:** ExpenseTracker (schema v106, `expense_tracker_db`)

---

## Table of Contents

1. [Current Backup Implementation](#1-current-backup-implementation)
2. [Restore Implementation](#2-restore-implementation)
3. [Restore Safety & Crash Safety](#3-restore-safety--crash-safety)
4. [Validation & Verification](#4-validation--verification)
5. [Current Gaps](#5-current-gaps)
6. [Existing Restore/Export Files](#6-existing-restoreexport-files)
7. [Export / Accounting Reporters](#7-export--accounting-reporters)
8. [Privacy & Gate Infrastructure](#8-privacy--gate-infrastructure)
9. [Worker & Service Landscape](#9-worker--service-landscape)
10. [Test Coverage](#10-test-coverage)
11. [Complete File Inventory](#11-complete-file-inventory)
12. [Gap Analysis & Recommendations](#12-gap-analysis--recommendations)

---

## 1. Current Backup Implementation

### 1.1 `DatabaseBackupRepositoryImpl.kt` (1289 lines)

**Location:** `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`

#### Backup Interface (`DatabaseBackupRepository.kt`)

```kotlin
interface DatabaseBackupRepository {
    suspend fun exportDatabase(): Result<File>
    suspend fun getLegacyPublicBackupNotice(): String?
    suspend fun importDatabase(sourceFile: File): Result<DatabaseImportSummary>
    suspend fun getDatabaseStats(): DatabaseStats
    suspend fun createSafetyBackup(): Result<File>
    suspend fun resetDatabase(): Result<Unit>
}
```

#### Export Flow (`exportDatabase()`)

1. **Privacy gate checks** — Checks `PrivacyCapability.RAWBACKUP_EXPORT` or `PrivacyCapability.ENCRYPTED_BACKUP` depending on `settings.encryptedBackupEnabled`.
2. **WAL checkpoint** — Runs `PRAGMA wal_checkpoint(FULL)` with up to 3 retries (200ms delay).
3. **File export path** — Two modes:

   **Plaintext mode (default disabled):**
   - Copies `expense_tracker_db` → `{filesDir}/exports/expense_tracker_backup_{timestamp}.db`
   - Direct byte-for-byte SQLite file copy.

   **Encrypted mode (default enabled):**
   - Copies DB → temp file
   - Runs `ExportAnonymizer.sanitizeExport(tempCopy)` — nulls out `rawOcrText` from `scanned_receipts` and raw content columns (`title`, `text`, `bigText`, `subText`, `extrasJson`, `parseResult`) from `raw_notifications`
   - Reads sanitized bytes → encrypts with `BackupEncryptionService.encrypt()` using AES-256-GCM
   - Password: auto-generated 256-bit random key stored in `SecureKeyStorage` (key name: `"backup_encryption_password"`)
   - Writes `{filesDir}/exports/expense_tracker_backup_{timestamp}.enc`

#### Safety Backup (`createSafetyBackup()`)

- Creates a safety backup before import/reset operations
- WAL checkpoint → copy DB → `{filesDir}/safety_backups/expense_tracker_backup_SAFETY_{timestamp}.db`
- **Retention:** keeps only last 3 safety backups (`cleanupOldSafetyBackups()`)

#### Database Reset (`resetDatabase()`)

- Creates safety backup first (failure aborts reset)
- Closes Room DB, deletes `.db`, `-wal`, `-shm` files
- Does NOT recreate the database — next access triggers Room recreation

#### Key Constants

| Constant | Value |
|---|---|
| `BACKUP_PREFIX` | `"expense_tracker_backup_"` |
| `DATE_FORMAT` | `"yyyy-MM-dd_HH-mm-ss"` |
| `EXPORT_SUBDIR` | `"exports"` |
| `MIN_SUPPORTED_SCHEMA_VERSION` | 6 |
| `BUDGETS_SCHEMA_GUARD_VERSION` | 86 |
| `CURRENT_SUPPORTED_SCHEMA_VERSION` | `APP_DATABASE_SCHEMA_VERSION` (106) |
| `BACKUP_ENCRYPTION_KEY_BYTES` | 32 (256 bits) |
| `IMPORT_STAGING_PREFIX` | `"expense_tracker_db_import_stage_"` |

### 1.2 BackupEncryptionService.kt (95 lines)

**Location:** `app/src/main/java/com/yourname/expensetracker/data/privacy/BackupEncryptionService.kt`

- **Algorithm:** AES-256-GCM
- **Key derivation:** PBKDF2 with HMAC-SHA256, 600,000 iterations
- **Salt:** 16 bytes (random per encryption)
- **IV/nonce:** 12 bytes (random per encryption)
- **GCM tag:** 128 bits (embedded in ciphertext)
- **Output format:** `salt(16) + iv(12) + ciphertext_with_tag(variable)`
- **Decrypt:** Reverses the process, requires the exact password

### 1.3 Backup Triggers

**Manual only** — there is NO:
- Scheduled/periodic backup worker
- Backup on app update
- Backup before Room migration
- Auto-backup trigger of any kind

The backup is invoked exclusively from `DebugViewModel.exportDatabase()` which is only accessible via the Debug screen (hidden/non-production UI).

---

## 2. Restore Implementation

### 2.1 Import Flow (`importDatabase()`)

The restore pipeline has **9 stages**:

1. **Source validation** — File exists, readable, non-empty.
2. **Schema version check** — Must be between 6 and 106 (inclusive).
3. **Table existence check** — Required tables: `expenses`, `categories`. Optional: `merchant_categories`, `pending_reviews`, `budgets`.
4. **Budgets schema guard (v86)** — If schema v86, validates budgets table columns, foreign keys, and indexes. Repairs stale defaults/indexes automatically if possible.
5. **Empty data rejection** — Blocks if ALL tracked tables have zero rows.
6. **Staged import** — Copies source to staging DB (`expense_tracker_db_import_stage_{timestamp}`), opens through Room to trigger migrations.
7. **Pre-import safety backup** — Creates `{filesDir}/safety_backups/...SAFETY_...db`. If this fails, import is ABORTED.
8. **Live file swap** — Closes Room DB, deletes live DB/WAL/SHM, renames/copies staged files to live paths.
9. **Post-swap verification** — Reopens Room DB, verifies row counts match expected via `reopenAndVerifyLiveImport()`.

### 2.2 Crash Safety on Swap

- **Safety backup** is created BEFORE the live swap.
- If verification fails AFTER the swap, `restoreFromSafetyBackup()` rolls back the live DB to the safety backup.
- **However:** There is NO restore journal file, NO atomic rename protocol. If the process crashes mid-swap (between deleting live files and copying staged files), the app can be left without a valid database.

### 2.3 Rollback Mechanism (`restoreFromSafetyBackup()`)

- Closes Room DB and openHelper
- Deletes live DB/WAL/SHM
- Copies safety backup → live DB path
- Copies safety backup WAL/SHM if they exist
- Reopens Room writable database

---

## 3. Restore Safety & Crash Safety

### 3.1 Maintenance Mode: ❌ NOT IMPLEMENTED

There is NO:
- `DatabaseMaintenanceGate` or `MaintenanceMode` class
- Worker pause/cancel mechanism during import
- Notification ingestion stop during import
- Write block during import
- UI overlay showing restore progress

**Current behavior:** Import runs while the app is fully active. Other components (workers, notification listener, UI screens, DAOs) may hold references to the old DB. After swap, Room reopens but cached flows, ViewModels, and repositories may have stale data.

### 3.2 Restore Journal: ❌ NOT IMPLEMENTED

There is NO restore journal file. No crash-recovery mechanism on app startup:
- No detection of incomplete restore (`live missing + pre_restore exists`)
- No automatic rollback/complete of in-progress restore
- No `"restore_in_progress"` marker file

### 3.3 Post-Restore Restart

- `DatabaseImportResult.SuccessNeedsRestart` exists but is never emitted (only `Success` or `Error`)
- UI displays: "Restart app to use all data" but does NOT force restart
- `_databaseImportRefreshSignal` is emitted, but this is a best-effort invalidation

### 3.4 App Restart Enforcement: ❌ NOT IMPLEMENTED

- No `Activity.Recover` / process restart after import
- No mandatory restart prompt
- No in-memory cache clearing

---

## 4. Validation & Verification

### 4.1 Source Validation (`validateSourceDatabase()`)

- **Schema version:** `MIN_SUPPORTED_SCHEMA_VERSION (6)` ≤ version ≤ `CURRENT_SUPPORTED_SCHEMA_VERSION (106)`
- **Table existence:** `expenses` and `categories` are REQUIRED; others optional
- **Row counts** for 5 tracked tables: expenses, categories, merchant_categories, pending_reviews, budgets
- **Budgets schema v86 guard:** Full column/foreign key/index validation with auto-repair

### 4.2 Import Verification (`verifyStagedImportWithRoom()` / `reopenAndVerifyLiveImport()`)

Verification checks:
1. **Schema version** after Room migration matches `CURRENT_SUPPORTED_SCHEMA_VERSION`
2. **`PRAGMA integrity_check`** must return `"ok"`
3. **Row count preservation** — only checks that actualCount >= sourceCount (ALLOWS DUPLICATES)

### 4.3 Tables Checked During Verification

Only **5 tables** are verified:

| Table | Check Type |
|---|---|
| `expenses` | Count preserved |
| `categories` | Count preserved |
| `merchant_categories` | Count preserved |
| `pending_reviews` | Count preserved |
| `budgets` | Count preserved |

**NOT verified (57+ tables unchecked):** `scanned_receipts`, `raw_notifications`, `warranties`, `return_windows`, `subscription_*`, `receipt_*`, `groups`, `group_expenses`, `group_members`, `split_*`, `budget_forecasts`, `investments`, `bank_connections`, `exchange_rates`, `ai_artifacts`, `ai_chat_*`, `locations`, `mileage_tracking`, `anomaly_alerts`, `savings_goals`, `savings_sweep_plans`, `recurring_*`, `planned_expenses`, `manual_recurring_expenses`, `recommendations`, `prompt_state`, `health_score_history`, `budget_adjustment_*`, `spending_personality_profile`, `stress_forecast_snapshot`, `email_receipt_sources`, `spending_challenges`, `transaction_events`, `receipt_events`, `receipt_expense_links`, `privacy_audit_events`, `background_job_runs`, `user_corrections`, `source_stats`, etc.

### 4.4 Verification Count Logic

```kotlin
// Only fails if actual < source — DUPLICATE ROWS ALLOWED
private fun verifyCoreTableCountPreservedForVerification(...) {
    if (actualCount < sourceCount) { throw Exception(...) }
}
```

This means if a backup import duplicates 100 expenses → 200 expenses, verification **passes**.

### 4.5 Meaningful Data Check

```kotlin
fun hasMeaningfulData(): Boolean {
    return transactionCount > 0 || categoryCount > 0 || 
           merchantCount > 0 || pendingReviewCount > 0 || budgetCount > 0
}
```

A backup containing ONLY receipts, warranties, groups, subscriptions, or AI data would be considered "empty" and rejected.

---

## 5. Current Gaps

### 5.1 Backup Encryption

| Aspect | Status |
|---|---|
| AES-256-GCM encryption | ✅ Implemented |
| PBKDF2 key derivation (600K iterations) | ✅ Implemented |
| Random salt/IV per encryption | ✅ Implemented |
| User-provided password | ❌ Auto-generated random key stored in `SecureKeyStorage` |
| Encrypted file format | `.enc` (raw salt+iv+ciphertext, no container format) |
| Plaintext export option | ✅ Still available (gated by privacy setting) |

**Issue:** The encryption password is auto-generated and stored in app-private EncryptedSharedPreferences. There is NO user-provided password. This means:
- Backup files are encrypted at rest but the key is on the same device
- Backups restored on a different device cannot be decrypted (different key)
- No "backup password" UX

### 5.2 Receipt Images in Backup: ❌ MISSING

**Known limitation** documented in the code:

```kotlin
/**
 * ## Known limitation — receipt images are NOT included
 *
 * This implementation only copies the Room database file (`.db`).  Receipt
 * image assets stored by [ReceiptAssetStore] in `filesDir/receipts/` are
 * **not** included in the backup.
 */
```

The `ReceiptAssetStore` already has `generateBackupManifest()` (SHA-256 hashing, MIME type detection, file size) but it is NEVER CALLED during backup.

### 5.3 Raw Data in Backup

**ExportAnonymizer** strips these before encrypted export:
- `scanned_receipts.rawOcrText` → NULL
- `raw_notifications.title`, `.text`, `.bigText`, `.subText`, `.extrasJson`, `.parseResult` → NULL

**But for plaintext exports:** NO sanitization occurs. Raw notifications and OCR text are fully exposed.

### 5.4 Partial Backup: ❌ NOT IMPLEMENTED

No mechanism for users to choose what to include/exclude in backup.

### 5.5 Backup Manifest: ❌ NOT IMPLEMENTED

No manifest file describing backup contents. No:
- `manifest.json` in backup bundle
- Content type listing (database, receipt images, etc.)
- Schema version annotation
- Checksum manifest
- Created-at timestamp in machine-readable format

### 5.6 Backup Format

| Aspect | Current | Target (Phase 9) |
|---|---|---|
| Format | Raw `.db` or `.enc` | `.costbackup` bundle |
| Container | None (single file) | ZIP/TAR with manifest |
| Self-describing | No | Yes (manifest.json) |
| Includes files | DB only | DB + receipt images + checksums |
| Versioned | Partial (schema version in DB) | Yes (backup format version) |
| Restorable on other device | No (key-bound) | Yes (password-based) |

### 5.7 Filename Collision Risk

**DATE_FORMAT** = `"yyyy-MM-dd_HH-mm-ss"` — no millisecond/UUID suffix. Multiple exports in the same second will overwrite each other.

### 5.8 Backup After Reset

`resetDatabase()` creates a safety backup before deleting the database. However, after reset there is NO automatic recreation of database tables — the app relies on Room's `fallbackToDestructiveMigration()` being absent and `FRESH_INSTALL_CALLBACK` running on next access.

---

## 6. Existing Restore/Export Files

### 6.1 Debug Export

**`DebugDataStorage.kt`** — Saves parser debug data to `{filesDir}/last_debug_data.json`:
- Parsed transactions, parsing logs, issues, raw text preview
- NOT a backup/restore mechanism — debug-only diagnostic data
- Loaded via `DebugViewModel` for display on Debug screen

**`DebugViewModel.kt`** — Contains:
- `exportDatabase()` — calls `databaseBackupRepository.exportDatabase()` 
- `importDatabase(uri)` — creates temp file from URI, calls `repository.importDatabase(tempFile)`
- `resetDatabase()` — calls `databaseBackupRepository.resetDatabase()`
- CSV import via `csvExpenseImporter.importFromContent()`

### 6.2 CSV Import

**`CsvExpenseImporter.kt`** (278 lines):
- Imports from CSV format: `date,amount,merchant,category,description`
- Uses `TransactionLifecycleCoordinator` (full lifecycle validation, dedup, event logging)
- Auto-creates categories if they don't exist
- Supports quoted CSV fields
- Reports per-row results (imported, duplicate, failed)
- Called from Debug screen only

### 6.3 Database File Access

- `AppDatabase.DATABASE_NAME = "expense_tracker_db"` — the actual SQLite file
- Access via `context.getDatabasePath(AppDatabase.DATABASE_NAME)` consistently
- WAL/SHM sidecar files: `{db}-wal`, `{db}-shm`
- Staging files: `expense_tracker_db_import_stage_{timestamp}` + WAL/SHM
- Safety backups: `{filesDir}/safety_backups/expense_tracker_backup_SAFETY_{timestamp}.db`

---

## 7. Export / Accounting Reporters

### 7.1 Export Formats

| Format | Class | File Extension | Medium |
|---|---|---|---|
| QuickBooks IIF | `QuickBooksIIFExporter` | `.iif` | Tab-separated |
| Xero CSV | `XeroCSVExporter` | `.csv` | Comma-separated |
| FreshBooks CSV | `FreshBooksExporter` | `.csv` | Comma-separated |
| Generic CSV | `ExportOptionsViewModel.streamGenericCsvExport()` | `.csv` | Comma-separated |
| Structured JSON | `ExportOptionsViewModel.streamJsonExport()` | `.json` | JSON (v1 schema) |
| Accountant Report PDF | `AccountantReportPdfExporter` | `.pdf` | Android `PdfDocument` |

### 7.2 Accounting Export Policy

- **Single-currency requirement** — Xero, QuickBooks, FreshBooks require single-currency datasets
- **PURCHASE-only** — Non-PURCHASE transaction types rejected
- Applied in both `AccountingExportRepository` and `ExportOptionsViewModel`
- **Paging:** `DeterministicExpenseExportPager` (page size: 2000, ordered by date ASC, id ASC, merchant COLLATE NOCASE ASC)

### 7.3 Accountant Report PDF

- Groups by currency
- Summary + category breakdown + percentage
- Large transaction review (>€500)
- Per-currency totals (no cross-currency aggregation)
- Simple PDF with title/heading/body text, no tables

### 7.4 Export Data Repository

- Writes to `{cacheDir}/exports/` (not `filesDir` — these are temporary)
- Shared via `FileProvider` authority
- Generic CSV fields: Date, Merchant, Amount, Currency, Category, Notes, ID
- JSON fields: id, date, timestamp, merchant, amount, currency, category, notes

### 7.5 Export Anonymization

**`ExportAnonymizer.kt`** strips (only for encrypted exports):
- `scanned_receipts.rawOcrText`
- `raw_notifications.title`, `text`, `bigText`, `subText`, `extrasJson`, `parseResult`

**NO anonymization** for:
- Plaintext DB exports
- CSV/JSON/PDF report exports (these contain merchant names, amounts, notes — but not raw data)
- Plaintext exports don't run sanitization at all

### 7.6 Export Data Note

CSV/JSON exports include ONLY a subset of fields:
- id, date, merchant, amount, currency, category, notes
- NOT included: transaction type, source raw notification, dedupe key, merchant key, receipt links, shared-expense fields, location, review status, warranty/subscription links, recurring/planned metadata, normalized currency snapshot

**These exports are NOT restorable backups** — they are reporting exports only.

### 7.7 Export Cleanup: ❌ NOT IMPLEMENTED

Generated export files in `{cacheDir}/exports/`:
- Are NOT cleaned up after share/dismiss
- Accumulate indefinitely
- `clearExport()` clears UI state but does NOT delete the file

---

## 8. Privacy & Gate Infrastructure

### 8.1 Privacy Capabilities for Backup

| Capability | Description |
|---|---|
| `RAWBACKUP_EXPORT` | Plaintext DB export (denied when encrypted backup enabled) |
| `ENCRYPTED_BACKUP` | Encrypted DB export (denied when encrypted backup disabled) |

### 8.2 BackupPrivacyGate

Routes to `RAWBACKUP_EXPORT` or `ENCRYPTED_BACKUP` based on `PrivacySettings.encryptedBackupEnabled` (default: `true`).

### 8.3 Privacy Settings

Stored in DataStore (`privacy_settings`):
- `encryptedBackupEnabled` (default: `true`) — UI toggle in Privacy Settings screen

### 8.4 SecureKeyStorage

- Uses Android Keystore + EncryptedSharedPreferences (AES-256)
- Stores the auto-generated backup encryption password under key `"backup_encryption_password"`
- Also stores API keys for Geoapify, Google Places, Gemini

---

## 9. Worker & Service Landscape

### 9.1 Background Workers (7 total)

| Worker | Interval | Purpose |
|---|---|---|
| `DataRetentionWorker` | 24h | Purge old raw notifications/OCR data |
| `LocationBackfillWorker` | 12h | Geocode unmapped expenses |
| `BillReminderWorker` | 6h (disabled) | Check due reminders |
| `ReceiptMatchingWorker` | 2h | Match receipts to expenses |
| `DailyBriefingWorker` | 24h | Generate AI dashboard briefing |
| `WarrantyExpirationWorker` | 24h | Check expiring warranties |
| `MerchantKeyBackfillWorker` | One-shot | Backfill merchant keys |

**All workers have a `WorkerSpec` gate with an `enabled` flag.** However:
- There is NO mechanism to pause/cancel all workers during restore
- Workers are not notified of database changes post-restore
- Workers may attempt to read/write during active import

### 9.2 Notification Listener Service

- `NotificationCaptureService` extends `NotificationListenerService`
- Captures financial app notifications in real-time
- Runs as a foreground service
- **NOT paused during backup/restore** — can write to DB during import

### 9.3 Receipt Asset Store

- Stores receipt images at `{filesDir}/receipts/`
- Provides `generateBackupManifest()` with SHA-256 hashing — exists but unused
- Images are `.jpg` files with UUID filenames

---

## 10. Test Coverage

### 10.1 DatabaseBackupRepositoryImplTest (798 lines)

**Location:** `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt`

**Tests:**
- ✅ Backup creates file successfully
- ✅ Restore from backup works (counts match)
- ✅ Rollback safety if restore fails (original DB preserved)
- ✅ WAL checkpoint helper works
- ✅ Import repairs same-version budgets defaults
- ✅ Import allows missing non-core tables (legacy compatible)
- ✅ Import doesn't reject backup with non-expense data
- ✅ Import rejects schema86 with non-repairable budgets
- ✅ Import rejects schema86 with bad budgets index uniqueness
- ✅ Temp migration open failure leaves live DB untouched
- ✅ Staged import swaps only after verification
- ✅ Verification rejects partial count loss for core tables
- ✅ Schema37 fixture preserves exact core counts
- ✅ Rollback on post-swap reopen failure

**Missing tests:**
- ❌ No test for encrypted export flow
- ❌ No test for import with receipt image data
- ❌ No test for import while workers are active
- ❌ No test for crash during file swap
- ❌ No test for concurrent import/notification write
- ❌ No test for export filename collision
- ❌ No test for backup manifest generation
- ❌ No test for partial backup scenarios

### 10.2 Export Tests

| Test | Location |
|---|---|
| `AccountingExportRepositoryTest` | `app/src/test/.../data/repository/AccountingExportRepositoryTest.kt` |
| `ExportOptionsViewModelTest` | `app/src/test/.../ui/screens/export/ExportOptionsViewModelTest.kt` |
| `AccountingExportPolicyTest` | `app/src/test/.../domain/export/AccountingExportPolicyTest.kt` |
| `DeterministicExpenseExportPagerTest` | `app/src/test/.../data/repository/DeterministicExpenseExportPagerTest.kt` |
| `ExpenseExportMapperTest` | `app/src/test/.../domain/export/ExpenseExportMapperTest.kt` |

### 10.3 Other Related Tests

| Test | Location |
|---|---|
| `CsvExpenseImporterTest` | `app/src/test/.../util/CsvExpenseImporterTest.kt` |
| `SecureKeyStorageTest` | `app/src/test/.../data/security/SecureKeyStorageTest.kt` |
| `DebugViewModelStressTest` | `app/src/test/.../ui/screens/debug/DebugViewModelStressTest.kt` |
| `DatabaseMigrationTest` | `app/src/androidTest/.../data/database/DatabaseMigrationTest.kt` |

---

## 11. Complete File Inventory

### Backup Core

| File | Purpose |
|---|---|
| `domain/backup/DatabaseBackupRepository.kt` | Interface: export, import, stats, safety backup, reset |
| `domain/backup/DatabaseOperationResults.kt` | `DatabaseExportResult` and `DatabaseImportResult` sealed classes |
| `domain/backup/DatabaseStats.kt` | (in DatabaseBackupRepository.kt) Stats data class |
| `domain/backup/DatabaseImportSummary.kt` | (in DatabaseBackupRepository.kt) Import summary data class |
| `data/repository/DatabaseBackupRepositoryImpl.kt` | Full implementation (1289 lines) |
| `data/privacy/BackupEncryptionService.kt` | AES-256-GCM encrypt/decrypt with PBKDF2 |
| `domain/privacy/BackupPrivacyGate.kt` | Privacy gate for RAWBACKUP_EXPORT / ENCRYPTED_BACKUP |
| `di/BackupRepositoryModule.kt` | Dagger module |

### Export Core

| File | Purpose |
|---|---|
| `domain/export/AccountingExporters.kt` | QuickBooksIIFExporter, XeroCSVExporter, FreshBooksExporter |
| `domain/export/AccountantReportPdfExporter.kt` | PDF accountant report with per-currency grouping |
| `domain/export/ExportTransaction.kt` | Export DTO with currency, type, source account |
| `domain/export/ExpenseExportMapper.kt` | Shared Expense → ExportTransaction mapper |
| `domain/export/AccountingExportPolicy.kt` | Single-currency, purchase-only validation |
| `data/repository/AccountingExportRepository.kt` | Repository-level accounting export |
| `data/repository/ExportDataRepository.kt` | Generic export data helper |
| `data/repository/DeterministicExpenseExportPager.kt` | Paged expense fetcher (page size: 2000) |
| `data/privacy/ExportAnonymizer.kt` | Strips raw OCR/notification content from DB copy |
| `ui/screens/export/ExportOptionsViewModel.kt` | ViewModel for export options screen |
| `ui/screens/export/ExportOptionsScreen.kt` | UI for export options |
| `di/ExportModule.kt` | Dagger module for exporters |

### Import Core

| File | Purpose |
|---|---|
| `util/CsvExpenseImporter.kt` | CSV expense importer via TransactionLifecycleCoordinator |

### Debug/Admin

| File | Purpose |
|---|---|
| `ui/screens/debug/DebugViewModel.kt` | Backup/import/reset from debug screen |
| `ui/screens/debug/DebugScreen.kt` | UI with DatabaseManagementSection |
| `ui/screens/debug/DebugDataStorage.kt` | Parser debug data persistence |
| `domain/debug/DebugData.kt` | Debug data model with JSON serialization |
| `ui/screens/debug/CategorizationDebugViewModel.kt` | Categorization debug |
| `ui/screens/debug/CategorizationDebugScreen.kt` | Categorization debug UI |

### Privacy/Supporting

| File | Purpose |
|---|---|
| `domain/privacy/PrivacyCapability.kt` | Enum with RAWBACKUP_EXPORT, ENCRYPTED_BACKUP |
| `domain/privacy/PrivacyGate.kt` | Gate interface |
| `domain/privacy/PrivacySettings.kt` | Data class with encryptedBackupEnabled |
| `data/privacy/PrivacySettingsRepositoryImpl.kt` | DataStore-backed settings |
| `data/security/SecureKeyStorage.kt` | EncryptedSharedPreferences for keys |
| `ui/screens/privacysettings/PrivacySettingsScreen.kt` | Settings UI with backup toggle |
| `ui/screens/privacysettings/PrivacySettingsViewModel.kt` | Settings ViewModel |

### Receipt Assets (future backup target)

| File | Purpose |
|---|---|
| `domain/receipt/lifecycle/ReceiptAssetStore.kt` | Receipt file storage, `generateBackupManifest()` |
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | Receipt lifecycle with image path management |
| `domain/receipt/lifecycle/ReceiptLinkService.kt` | Links receipts to expenses |
| `domain/receipt/ReceiptOcrService.kt` | OCR (uses `filesDir/receipts/`) |
| `data/database/dao/ScannedReceiptDao.kt` | DAO with `getAllWithImagePath()` |

### Workers (need maintenance mode)

| File | Purpose |
|---|---|
| `domain/workers/WorkerSpec.kt` | Worker specification with enabled flag |
| `service/reminder/BillReminderWorker.kt` | Periodic reminder checker |
| `service/receiptmatching/ReceiptMatchingWorker.kt` | Periodic receipt matching |
| `service/warranty/WarrantyExpirationWorker.kt` | Periodic warranty checker |
| `data/location/LocationBackfillWorker.kt` | Periodic geocoding backfill |
| `data/location/MerchantKeyBackfillWorker.kt` | One-shot merchant key backfill |
| `data/ai/worker/DailyBriefingWorker.kt` | Periodic AI briefing |
| `data/privacy/DataRetentionWorker.kt` | Periodic data retention cleanup |
| `service/NotificationCaptureService.kt` | Real-time notification listener |

---

## 12. Gap Analysis & Recommendations

### 12.1 Gap Summary

| # | Gap | Severity | Area |
|---|---|---|---|
| G1 | No `.costbackup` bundle format — raw single-file export only | CRITICAL | Backup Format |
| G2 | Receipt images NOT included in backup | CRITICAL | Backup Completeness |
| G3 | No restore journal — crash during file swap = data loss risk | CRITICAL | Crash Safety |
| G4 | No maintenance mode — workers/notifications active during restore | CRITICAL | Restore Safety |
| G5 | Import verification only checks 5 of 62+ tables | CRITICAL | Verification |
| G6 | Verification allows duplicate row increases (only checks `actual >= source`) | CRITICAL | Verification |
| G7 | No user-provided backup password — key is device-bound | HIGH | Encryption |
| G8 | No backup manifest — no self-describing metadata | HIGH | Backup Format |
| G9 | No forced app restart after restore — stale in-memory state | HIGH | Restore |
| G10 | No scheduled/periodic backup | MEDIUM | Backup Triggers |
| G11 | Filename collision risk (same-second exports overwrite) | MEDIUM | Backup |
| G12 | Plaintext exports NOT sanitized (raw OCR/notifications exposed) | MEDIUM | Privacy |
| G13 | "Meaningful data" check rejects backups without core tables | MEDIUM | Validation |
| G14 | No export cleanup — files accumulate indefinitely | MEDIUM | Export |
| G15 | JSON export silently converts NaN/Infinity to 0.0 | HIGH | Export |
| G16 | Export UI loads all rows into memory | MEDIUM | Export |
| G17 | No snapshot consistency during export | MEDIUM | Export |
| G18 | CSV/JSON exports labeled as exports but not restorable backups | MEDIUM | UX |
| G19 | Debug-only backup UI — not accessible from production screens | MEDIUM | UX |

### 12.2 Phase 9 Build Order (Recommended)

#### PR 1 — Create `.costbackup` Encrypted Bundle Format
- Replace raw `.db`/`.enc` with ZIP/TAR bundle containing:
  - `manifest.json` (backup format version, app version, schema version, creation timestamp, content listing)
  - `database.sqlite.enc` (AES-256-GCM encrypted DB)
  - `files/` directory (receipt images, etc.)
  - `checksums.json` (SHA-256 hashes of all entries)
- Use PBKDF2-derived key from user-provided password (not auto-generated)
- Backup format versioning for forward compatibility

#### PR 2 — Add Restore Maintenance Mode
- Create `DatabaseMaintenanceGate` with `enterRestoreMode()` / `exitRestoreMode()`
- On restore start:
  - Cancel all WorkManager workers
  - Stop notification ingestion (disable `NotificationListenerService`)
  - Block new DB writes (throw from DAO wrappers)
  - Show full-screen restore-in-progress UI
- On restore complete:
  - Force process restart (or `Activity.recreate()` chain)
  - Reschedule workers after restart

#### PR 3 — Crash-Safe Restore Journal
- Create `RestoreJournal` file during import:
  ```json
  {
    "state": "PRE_SWAP" | "SWAPPING" | "COMPLETE",
    "livePath": "...",
    "safetyBackupPath": "...",
    "startedAt": 1234567890
  }
  ```
- On app startup: detect incomplete restore → roll back or complete
- Atomic rename protocol: move-to-backup-first, then swap
- Delete journal only after post-swap verification passes

#### PR 4 — Expand Import Verification
- Track ALL 62+ Room entities
- Table coverage tiers:
  - **Tier 1 (exact count):** expenses, categories, pending_reviews, budgets, merchant_categories, scanned_receipts, warranties, return_windows, groups, group_expenses, group_members, split_item_assignments, recurring_*, planned_expenses
  - **Tier 2 (may differ):** cache tables, exchange_rates, background_job_runs
- Verify exact counts (not just `>=`) for Tier 1
- Add fingerprint verification (ID sets, dedupe keys)

#### PR 5 — Include File Assets in Backup
- Call `ReceiptAssetStore.generateBackupManifest()` during backup
- Bundle all receipt image files into the `.costbackup` archive
- On restore: extract and rewrite `imagePath` references in DB

#### PR 6 — Separate Backup vs Report Export UX
- Production UI should show:
  1. "Full Encrypted Backup" (`.costbackup`)
  2. "Redacted Export" (anonymized DB)
  3. "CSV/JSON Report" (clearly labeled non-restorable)
  4. "Accounting Export" (Xero/QB/FreshBooks)
- Move raw `.db` export behind developer options
- Add backup/restore screen outside of Debug section

#### PR 7 — Stream Report Exports & Add Cleanup
- Use `DeterministicExpenseExportPager` for ALL export paths
- Add export file cleanup (delete on dismiss, keep only last N)
- Handle non-finite amounts as export failures

---

### 12.3 Regression Test Requirements

| # | Test |
|---|---|
| R1 | Two backups created in same second do not collide |
| R2 | Full backup restore preserves ALL tracked table counts (62+ tables) |
| R3 | Restore fails if any protected table loses rows |
| R4 | Restore fails if protected table gains unexpected duplicate rows |
| R5 | Backup includes receipt image files and restore rewrites paths |
| R6 | Import cannot run while notification ingestion writes |
| R7 | Import cannot run while background workers are active |
| R8 | Crash during DB swap recovers on next startup (restore journal) |
| R9 | Successful restore forces clean app restart |
| R10 | Encrypted backup cannot be read as plaintext SQLite |
| R11 | Wrong password fails without touching live DB |
| R12 | Raw DB export hidden in production (debug-only) |
| R13 | JSON report export clearly labeled non-restorable |
| R14 | Large export streams pages without loading all rows |
| R15 | Export during concurrent edit uses stable row snapshot |
| R16 | Non-finite amount causes export failure, not 0.0 |
| R17 | Dismissing unsaved export deletes temporary report file |
| R18 | Reset database requires explicit confirmation + safety backup |
| R19 | Maintenance mode pauses ALL workers before restore |
| R20 | Notification ingestion stops during restore |

---

### 12.4 Key Files to Create for Phase 9

| File | Purpose |
|---|---|
| `domain/backup/BackupBundle.kt` | `.costbackup` bundle data model |
| `domain/backup/BackupManifest.kt` | Manifest schema and validation |
| `data/backup/BackupBundleWriter.kt` | Bundle creation (ZIP/TAR + encrypt) |
| `data/backup/BackupBundleReader.kt` | Bundle extraction + decrypt + verify |
| `domain/backup/RestoreJournal.kt` | Journal file model + atomic swap protocol |
| `domain/backup/RestoreJournalManager.kt` | Journal create/read/commit/rollback |
| `domain/backup/DatabaseMaintenanceGate.kt` | Maintenance mode (worker pause, write block) |
| `data/backup/WorkerPauseManager.kt` | Cancel/reschedule all WorkManager workers |
| `ui/screens/backup/BackupScreen.kt` | Production backup/restore UI |
| `ui/screens/backup/BackupViewModel.kt` | Backup/restore ViewModel |
| `domain/backup/BackupPasswordProvider.kt` | User-provided password handling |

---

## Sources Reviewed

- `data/repository/DatabaseBackupRepositoryImpl.kt` (full file, 1289 lines)
- `domain/backup/DatabaseBackupRepository.kt`
- `domain/backup/DatabaseOperationResults.kt`
- `data/privacy/BackupEncryptionService.kt`
- `data/privacy/ExportAnonymizer.kt`
- `domain/privacy/BackupPrivacyGate.kt`
- `domain/privacy/PrivacyCapability.kt`
- `domain/privacy/PrivacyGate.kt`
- `domain/privacy/PrivacySettings.kt`
- `data/privacy/PrivacySettingsRepositoryImpl.kt`
- `data/security/SecureKeyStorage.kt`
- `di/BackupRepositoryModule.kt`
- `domain/export/AccountingExporters.kt`
- `domain/export/AccountantReportPdfExporter.kt`
- `domain/export/ExportTransaction.kt`
- `domain/export/ExpenseExportMapper.kt`
- `domain/export/AccountingExportPolicy.kt`
- `data/repository/AccountingExportRepository.kt`
- `data/repository/ExportDataRepository.kt`
- `data/repository/DeterministicExpenseExportPager.kt`
- `util/CsvExpenseImporter.kt`
- `di/ExportModule.kt`
- `ui/screens/export/ExportOptionsViewModel.kt`
- `ui/screens/export/ExportOptionsScreen.kt`
- `ui/screens/debug/DebugViewModel.kt`
- `ui/screens/debug/DebugScreen.kt`
- `ui/screens/debug/DebugDataStorage.kt`
- `domain/debug/DebugData.kt`
- `domain/receipt/lifecycle/ReceiptAssetStore.kt`
- `data/database/AppDatabase.kt` (schema v106, 62+ entities)
- `domain/workers/WorkerSpec.kt`
- `service/NotificationCaptureService.kt`
- `ui/screens/privacysettings/PrivacySettingsScreen.kt`
- `ui/screens/privacysettings/PrivacySettingsViewModel.kt`
- `test/.../DatabaseBackupRepositoryImplTest.kt` (798 lines)
- `docs/analyses and debug master/backup-restore-export-analysis.md` (923 lines)
- `docs/plans/PLAN-B7-export-backup-pipeline.md`
- `docs/reviews/REVIEW-B7-export-backup-pipeline.md`
