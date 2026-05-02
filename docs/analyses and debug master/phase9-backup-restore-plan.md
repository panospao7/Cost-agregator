# Phase 9 — Backup / Restore Foundation Plan

**Status:** Final Implementation Plan  
**Date:** 2026-05-02  
**DB Schema:** v106 (56 Room entities)  
**Phases Complete:** 1–8  
**Plan Version:** 1.0 — Endorsement of template with refinements

---

## 0. Evaluation Summary

The [template plan](../../phase9-backup-restore-foundation-plan.md) is exceptionally thorough: it addresses **all 19 gaps** identified by the [backup-restore audit](./backup-restore-audit.md). This document is an endorsement (`Option A`) that consolidates the template’s 23 sections into a single executable plan with the following refinements:

| Refinement | Rationale |
|---|---|
| Concrete entity-level verification table | Derived from actual `AppDatabase.kt` entity list (56 entities) |
| Explicit worker pause strategy | Leverages existing `WorkerSpec.DEFAULTS` names |
| Notification ingestion block mechanism | Integrates with existing `NotificationCaptureService` |
| Snapshot consistency guarantee | Clarifies WAL checkpoint + single-read transaction |
| Explicit “no new tables” assertion for Phase 9 | DB stays at v106 unless a migration schema column is needed; bump to v107 only if required |
| Merged regression test matrix | Combines template Section 21 with audit Section 12.3 |
| PR-zero just records the gap | No code change — pure audit → plan traceability |

**Decision:** The template plan’s 12-PR build order is correct and is adopted as-is.

---

## 1. Scope

### In
- Encrypted `.costbackup` bundle format (ZIP container, manifest, checksums)
- User-provided backup password (AES-256-GCM + PBKDF2-HMAC-SHA256, 600K iterations)
- DB snapshot backup with configurable privacy options (redacted by default)
- Receipt image asset backup/restore with path rewriting
- Maintenance mode that pauses 7 workers and blocks notification ingestion
- Crash-safe restore journal with startup recovery
- Atomic-ish file swap protocol
- Full verification across all 56 protected tables (Tier 1 exact counts, Tier 2 validity)
- Forced app restart after successful restore
- Production backup/restore UI (outside Debug)
- Report export cleanup, streaming, and non-finite-amount rejection
- Raw DB export confined to Debug/developer mode only

### Out
- Cloud sync or automatic cloud backup (Google Drive, Dropbox, etc.)
- Multi-device merge/conflict resolution
- Scheduled/periodic automatic backup (architecture prep only)
- Redesign of accounting export formats
- Restoring backups from schema versions older than the migration chain supports
- Making CSV/JSON report exports restorable backups

---

## 2. Current State (from Audit)

### What exists and is GOOD
| Component | Location | Notes |
|---|---|---|
| AES-256-GCM encryption | `data/privacy/BackupEncryptionService.kt` | Salt(16) + IV(12) + PBKDF2(600k iter) — rock solid |
| DB export path | `DatabaseBackupRepositoryImpl.exportDatabase()` | WAL checkpoint, privacy gate, retry logic |
| Safety backup | `DatabaseBackupRepositoryImpl.createSafetyBackup()` | Keeps last 3, verified before swap |
| Export anonymizer | `data/privacy/ExportAnonymizer.kt` | Nulls raw OCR + notification content |
| Privacy gates | `domain/privacy/BackupPrivacyGate.kt` | RAWBACKUP_EXPORT / ENCRYPTED_BACKUP |
| Receipt asset manifest | `ReceiptAssetStore.generateBackupManifest()` | SHA-256, MIME — exists but UNUSED |
| Worker specs | `domain/workers/WorkerSpec.kt` | 7 workers, each with `enabled` flag |
| Paged export | `DeterministicExpenseExportPager.kt` | Page size 2000 |
| `SuccessNeedsRestart` | `DatabaseOperationResults.kt` | Declared but NEVER emitted |

### What is missing (6 CRITICAL, 13 NON-CRITICAL)
See [audit Section 12.1](./backup-restore-audit.md#121-gap-summary) for the full 19-gap table. The 6 CRITICAL gaps drive the implementation priority:

| # | Gap | Template Section |
|---|---|---|
| G1 | No `.costbackup` bundle format | §4, §6 |
| G2 | Receipt images NOT included | §7 |
| G3 | No restore journal | §10 |
| G4 | No maintenance mode | §9, §17 |
| G5 | Verification only 5/56 tables | §12 |
| G6 | Verification allows duplicate row increases (`>=`) | §12.4 |

---

## 3. Target Architecture

### 3.1 The `.costbackup` Bundle

```
.costbackup (encrypted ZIP)
├── COSTBACKUP1           ← 12-byte magic + header (salt, IV, KDF params)
├── [ciphertext]          ← AES-256-GCM encrypted ZIP payload
│   ├── manifest.json     ← BackupManifest (format version, schema, counts, options)
│   ├── checksums.json    ← SHA-256 per entry
│   ├── database.sqlite   ← Sanitized DB snapshot
│   └── files/
│       └── receipts/
│           └── r1_a8f3.jpg  ← Copy of receipt images
```

**Key property:** the outer archive is an opaque encrypted blob. No metadata leaks outside encryption.

### 3.2 Encryption Architecture

| Parameter | Value |
|---|---|
| Algorithm | AES-256-GCM |
| Key derivation | PBKDF2-HMAC-SHA256 |
| Iterations | 600,000 |
| Salt | 16 random bytes per encryption |
| IV/Nonce | 12 random bytes per encryption |
| Tag | 128-bit GCM auth tag |
| Password source | **User-provided** (not auto-generated device key) |

**Header format (unencrypted prefix):**
```
COSTBACKUP1          ← 10-byte magic
format_version (2B)  ← big-endian uint16 = 1
salt (16B)
iv (12B)
[ciphertext...]
```

### 3.3 Backup Privacy Options

```kotlin
data class BackupOptions(
    val includeReceiptImages: Boolean = true,       // for full backup
    val includeRawNotifications: Boolean = false,    // NEVER default
    val includeRawOcrText: Boolean = false,          // NEVER default
    val includeDebugData: Boolean = false,           // NEVER default
    val includePrivacyAuditEvents: Boolean = false,  // NEVER default
    val includePreciseLocations: Boolean = false,
    val redacted: Boolean = true                     // true = privacy-first default
)
```

**Sanitization target:** ALWAYS the temp snapshot, NEVER the live DB.

---

## 4. Implementation Batches (12 PRs)

### PR 0 — Baseline Documentation (no behavior change)

**Goal:** Establish the contract. Record current gaps.

**Files to create:**
- `docs/development/BACKUP_RESTORE_FOUNDATION.md`

**Actions:**
1. Document backup types (full encrypted, redacted encrypted, raw DB debug, report exports).
2. Document restore states (preparing, staged, safety-backup-created, swapping, verifying, rolling-back, complete).
3. Document maintenance mode contract.
4. Document verification tiers (Tier 1 exact, Tier 2 valid, Tier 3 optional).
5. Document supported schema version range.
6. Document non-restorable report types.
7. Cross-reference the audit’s gap table (G1–G19).

**Done when:** `BACKUP_RESTORE_FOUNDATION.md` exists and accurately reflects the current `DatabaseBackupRepositoryImpl` behavior versus the target.

**Depends on:** Nothing (pure docs).

---

### PR 1 — Backup Bundle Models + Encryption Header

**Goal:** Define all data models and the `.costbackup` header format. Wire user-password encryption.

**Files to create:**
| File | Purpose |
|---|---|
| `domain/backup/BackupManifest.kt` | `BackupManifest`, `BackupContents`, `BackupDeviceInfo`, `BackupEncryptionMetadata`, `BackupPrivacyOptions` |
| `domain/backup/BackupChecksums.kt` | `BackupChecksums`, `BackupEntryChecksum` |
| `domain/backup/BackupOptions.kt` | User-facing backup options data class |
| `domain/backup/BackupBundleHeader.kt` | COSTBACKUP1 magic, format version, salt, IV layout |
| `domain/backup/BackupPasswordProvider.kt` | Interface for user password input |
| `data/backup/BackupPasswordProviderImpl.kt` | Password validation, confirmation, strength check |
| `domain/backup/ReceiptAssetBackupEntry.kt` | Receipt-to-bundle-path mapping |

**Files to modify:**
| File | Change |
|---|---|
| `data/privacy/BackupEncryptionService.kt` | Add `encryptWithPassword(data, password)` / `decryptWithPassword(data, password)`. Accept password string, derive key, return `salt+iv+ciphertext`. Add `writeHeader()` / `readHeader()`. Keep existing auto-key methods for backward compat during transition. |

**Critical design rule:** The new encrypt/decrypt MUST accept a `String` password. The auto-generated `SecureKeyStorage` path is deprecated for `.costbackup` but preserved for raw `.enc` legacy exports.

**Tests (new file: `src/test/.../data/backup/BackupEncryptionServiceTest.kt`):**
- [ ] Encrypt then decrypt with same password succeeds, bytes match original
- [ ] Wrong password on decrypt throws (distinct exception type, e.g. `WrongBackupPasswordException`)
- [ ] Two encryptions with same password produce different ciphertext (different salt/IV)
- [ ] Encrypted output is not valid SQLite (magic bytes check)
- [ ] Encrypted output is not valid ZIP (PK header absent)
- [ ] Header read/write round-trip (magic, version, salt, IV)
- [ ] Unsupported format version is rejected

**Done when:** All encryption tests pass. `BackupEncryptionService` can encrypt/decrypt with user password. Models compile. No live behavior change.

---

### PR 2 — Bundle Writer (DB-only, no assets)

**Goal:** Create valid `.costbackup` files containing encrypted DB + manifest + checksums. This is the foundation — assets come in PR 8.

**Files to create:**
| File | Purpose |
|---|---|
| `data/backup/BackupTempFileManager.kt` | Creates/destroys temp workspace directories |
| `data/backup/BackupDatabaseSnapshotter.kt` | WAL checkpoint → copy DB to temp → optionally sanitize |
| `data/backup/BackupManifestBuilder.kt` | Builds `BackupManifest` from snapshot + options |
| `data/backup/BackupChecksumService.kt` | SHA-256 each entry → `checksums.json` |
| `data/backup/BackupBundleWriter.kt` | Orchestrates: snapshot → manifest → checksums → zip → encrypt → output `.costbackup` |

**Writer flow (in `BackupBundleWriter.createBackup(options, password)`):**
1. **Verify privacy gate** — `ENCRYPTED_BACKUP` must be allowed.
2. **WAL checkpoint** — `PRAGMA wal_checkpoint(FULL)` with up to 3 retries.
3. **Create temp workspace** — `BackupTempFileManager.createWorkspace()`.
4. **Snapshot DB** — `BackupDatabaseSnapshotter.copyDatabaseTo(workDir)`.
5. **Sanitize snapshot** (if `options.redacted`) — `ExportAnonymizer.sanitizeExport(snapshotFile)`; also strip raw data columns per `BackupOptions`.
6. **Gather table counts** — query snapshot via Room, record all 56 entity counts in `BackupManifest.tableCounts`.
7. **Build manifest** — `BackupManifestBuilder.build(snapshot, options, ...)`.
8. **Build checksums** — `BackupChecksumService.compute(workDir)` → `checksums.json`.
9. **Zip workspace** → `backup.zip` in memory or temp file.
10. **Encrypt zip** with `BackupEncryptionService.encryptWithPassword(zipBytes, password)`.
11. **Write final `.costbackup`** — filename: `expense_tracker_backup_{yyyy-MM-dd_HH-mm-ss_SSS}_{uuid8}.costbackup` in `filesDir/backups/` (new directory).
12. **Verify final file** — exists, non-empty, header readable.
13. **Cleanup temp workspace** via `BackupTempFileManager.destroyWorkspace()`.
14. **Write privacy audit event** — `PrivacyAuditDao` records the export.

**Sanitization per default options (redacted=true):**
- `scanned_receipts.rawOcrText` → NULL
- `raw_notifications.title`, `.text`, `.bigText`, `.subText`, `.extrasJson`, `.parseResult` → NULL
- `ai_chat_messages.content` → NULL (if applicable)
- `privacy_audit_events` → excluded entirely

**Filename collision fix:**
```
expense_tracker_backup_2026-05-02_14-10-33-512_a8f31c.costbackup
                                            ^^^^    ^^^^^^
                                            millis  short UUID (8 hex chars)
```

**Tests (new file: `src/test/.../data/backup/BackupBundleWriterTest.kt`):**
- [ ] Creates `.costbackup` file successfully
- [ ] Output encrypted (not plaintext SQLite, not plaintext ZIP)
- [ ] Manifest present after decrypt
- [ ] Table counts in manifest (≥ 56 tables)
- [ ] `rawOcrText` and raw notification content scrubbed by default
- [ ] Full-raw backup retains data when `redacted = false` explicitly opted in
- [ ] Temp workspace deleted after success AND after failure
- [ ] Two backups created in same second do NOT collide (different UUID suffix)
- [ ] Privacy audit event emitted on export
- [ ] Writer fails if privacy gate denies `ENCRYPTED_BACKUP`

**Done when:** `BackupBundleWriter` tests pass. `.costbackup` files can be manually inspected (decrypt, unzip) to confirm structure.

---

### PR 3 — Bundle Reader + Validator

**Goal:** Decrypt, extract, and validate `.costbackup` files BEFORE touching the live database.

**Files to create:**
| File | Purpose |
|---|---|
| `data/backup/BackupBundleValidator.kt` | Validates header, decrypts, verifies checksums, checks format version |
| `data/backup/BackupBundleReader.kt` | Full decrypt + extract: returns `DecryptedBackupBundle` with manifest, DB file path, asset dir |

**Data classes:**
```kotlin
data class DecryptedBackupBundle(
    val manifest: BackupManifest,
    val dbFile: File,           // decrypted database.sqlite in temp dir
    val assetsDir: File?,       // decrypted files/ dir
    val checksumsVerified: Boolean,
    val warnings: List<String>
)
```

**Reader flow:**
1. Validate header magic (`COSTBACKUP1`) and format version.
2. Extract salt, IV from header.
3. Prompt user for password.
4. Derive key via PBKDF2.
5. Decrypt ciphertext.
6. Verify decrypted payload is valid ZIP.
7. Extract ZIP to temp workspace.
8. Verify `manifest.json` exists and parses.
9. Verify `checksums.json` exists.
10. Verify every entry checksum matches.
11. Verify `database.sqlite` exists.
12. Return `DecryptedBackupBundle`.

**Pre-swap validation checklist (MUST all pass before live DB is touched):**
- [ ] Header magic valid
- [ ] Format version supported
- [ ] Password decrypts successfully
- [ ] ZIP valid
- [ ] `manifest.json` present and schema-valid
- [ ] `checksums.json` present
- [ ] All checksums match
- [ ] `database.sqlite` present and readable

**Tests (new file: `src/test/.../data/backup/BackupBundleReaderTest.kt`):**
- [ ] Reader decrypts a valid `.costbackup` and returns correct manifest
- [ ] Wrong password → `WrongBackupPasswordException`, live DB untouched (verify by checking live DB row count before/after)
- [ ] Corrupt checksum → `ChecksumMismatchException`
- [ ] Missing manifest → `InvalidBackupFormatException`
- [ ] Unsupported format version → `UnsupportedBackupVersionException`
- [ ] Tampered ciphertext → decryption fails cleanly (no crash, no partial extraction)
- [ ] Manifest preview works (decrypt, read manifest, present summary — no swap)

**Done when:** Reader tests pass. Valid `.costbackup` can be read. All error paths are covered.

---

### PR 4 — Maintenance Mode Gate

**Goal:** During restore, block all writes, pause all 7 workers, and stop notification ingestion.

**Files to create:**
| File | Purpose |
|---|---|
| `domain/backup/DatabaseMaintenanceMode.kt` | `DatabaseMaintenanceMode` enum |
| `domain/backup/DatabaseMaintenanceGate.kt` | Interface: `enterMode()`, `exitMode()`, `assertWritesAllowed()` |
| `data/backup/DatabaseMaintenanceGateImpl.kt` | Implementation: mode state, write gate, timeout |
| `data/backup/WorkerPauseManager.kt` | Cancel all 7 WorkManager workers, prevent new enqueues |
| `domain/backup/MaintenanceModeState.kt` | Observable state for UI (current mode, progress message) |

**Mode states:**
```kotlin
enum class DatabaseMaintenanceMode {
    NORMAL,
    BACKUP_EXPORTING,        // writes may be allowed depending on policy
    RESTORE_PREPARING,       // writes blocked
    RESTORE_STAGING,         // writes blocked
    RESTORE_SWAPPING,        // writes blocked, DB may be closing
    RESTORE_VERIFYING,       // writes blocked
    RESTORE_ROLLING_BACK,    // writes blocked
    RESTORE_COMPLETE_RESTART_REQUIRED  // writes blocked, force restart
}
```

**7 Workers to pause (names from `WorkerSpec.DEFAULTS`):**

| Worker Name | Worker Class |
|---|---|
| `data_retention` | `DataRetentionWorker` |
| `location_backfill` | `LocationBackfillWorker` |
| `bill_reminder_periodic` | `BillReminderWorker` |
| `receipt_matching` | `ReceiptMatchingWorker` |
| `ai_daily_briefing` | `DailyBriefingWorker` |
| `warranty_expiration_check` | `WarrantyExpirationWorker` |
| `merchant_key_backfill` | `MerchantKeyBackfillWorker` |

**Worker pause strategy:**
1. Call `WorkManager.cancelAllWorkByTag()` for each worker's unique tag.
2. Set a volatile `paused: Boolean` flag that each worker checks on `doWork()` entry. If paused, return `Result.success()` immediately (no-op).
3. After restore success: do NOT resume workers in current process. Flag `RESTORE_COMPLETE_RESTART_REQUIRED`. Workers resume on next app start.
4. After restore rollback: clear paused flag, re-enqueue workers.

**NotificationCaptureService block:**
- Inject `DatabaseMaintenanceGate` into `NotificationCaptureService`.
- In `onNotificationPosted()`: call `maintenanceGate.assertWritesAllowed()`. If denied, drop the notification with a log.
- Alternative: check a `@Volatile Boolean` flag set by the gate for zero-overhead fast-path.

**Writes to block (inject `DatabaseMaintenanceGate` into these components):**
- `TransactionLifecycleCoordinator.createExpense/update/delete`
- `ReceiptLifecycleCoordinator` (all write methods)
- `NotificationRepository.processAndSave`
- `CsvExpenseImporter.importFromContent`
- `RecurringLifecycleCoordinator`
- `BudgetRepository` writes
- `GroupsRepositoryImpl` writes
- `PlannedExpenseRepository` writes

**Tests (new file: `src/test/.../data/backup/DatabaseMaintenanceGateTest.kt`):**
- [ ] `assertWritesAllowed()` throws when mode ≠ NORMAL
- [ ] `enterMode(RESTORE_PREPARING)` sets correct state
- [ ] `exitMode()` returns to NORMAL
- [ ] Exit to `RESTORE_COMPLETE_RESTART_REQUIRED` keeps writes blocked
- [ ] Concurrent enter attempts are serialized (second enter waits or fails)
- [ ] Timeout: if mode is not exited within 5 minutes, force-exit to NORMAL with log (prevents permanent lock)

**Done when:** Maintenance gate compiles and unit tests pass. Wired into notification service and key coordinators. No integration test yet (integrated in PR 6).

---

### PR 5 — Restore Journal + Crash Recovery

**Goal:** If the app crashes mid-restore, recover on next startup without data loss.

**Files to create:**
| File | Purpose |
|---|---|
| `domain/backup/RestoreJournal.kt` | Data class for journal entries |
| `domain/backup/RestoreJournalState.kt` | Enum: PREPARING, STAGED, SAFETY_BACKUP_CREATED, SWAPPING, VERIFYING, ROLLING_BACK, COMPLETE, FAILED |
| `data/backup/RestoreJournalManager.kt` | Create, update, commit, rollback journal file |
| `data/backup/RestoreCrashRecovery.kt` | Startup hook: check journal → recover or clean up |

**Journal file location:** `{filesDir}/restore_journal.json`

**Journal schema:**
```json
{
  "operationId": "uuid",
  "state": "STAGED",
  "startedAt": 1714651200000,
  "sourceBackupPath": "/data/.../backup.costbackup",
  "stagedDbPath": "/data/.../stage_xxx.db",
  "safetyBackupPath": "/data/.../SAFETY_xxx.db",
  "liveDbPath": "/data/.../expense_tracker_db",
  "error": null
}
```

**Journal lifecycle:**
```
PREPARING
  → STAGED                      (after staging DB extracted & validated)
    → SAFETY_BACKUP_CREATED     (after safety backup created)
      → SWAPPING                (moving staged → live)
        → VERIFYING             (reopening live, checking integrity)
          → COMPLETE            (delete journal)
        → ROLLING_BACK          (verification failed)
      → ROLLING_BACK
    → ROLLING_BACK
  → FAILED                      (delete staging, clear journal)
```

**Startup recovery hook (`RestoreCrashRecovery`):**

At `App.onCreate()` (after Hilt, before Room first access):
1. Check `filesDir/restore_journal.json` exists.
2. If absent → normal startup.
3. If present → read journal:
   - **COMPLETE** → delete journal, normal startup.
   - **PREPARING, STAGED, SAFETY_BACKUP_CREATED** → delete staging files, delete journal, normal startup (nothing destructive happened).
   - **SWAPPING** → attempt atomic completion:
     - If live DB exists and `PRAGMA integrity_check` = ok → set COMPLETE, prompt restart.
     - If live DB missing or corrupt → restore from safety backup → NORMAL, show error.
   - **VERIFYING** → same as SWAPPING: either complete or rollback.
   - **ROLLING_BACK** → check if safety backup was restored → NORMAL or show critical recovery screen.
   - **FAILED** → clean up, normal startup with error msg.
4. If recovery fails (safety backup also corrupt): show **non-dismissible critical recovery screen** with "Reinstall app" guidance.

**Atomic-ish swap protocol:**
1. Ensure safety backup exists and is verified (`PRAGMA integrity_check` = ok).
2. Checkpoint WAL on live DB.
3. Close Room DB (`close()` + `openHelper.close()`).
4. **Move** (not copy) live DB → `expense_tracker_db.pre_restore`.
5. **Copy** staged DB → `expense_tracker_db`.
6. Copy staged WAL/SHM if they exist.
7. Reopen Room.
8. `PRAGMA integrity_check` on live.
9. If ok → delete `.pre_restore` + set COMPLETE.
10. If not ok → move `.pre_restore` back → rollback.

**Key principle:** NEVER delete the live DB before the staged DB is verified in place. Move-to-backup-first is safer than delete-then-copy.

**Tests (new file: `src/test/.../data/backup/RestoreJournalManagerTest.kt`):**
- [ ] Journal created with PREPARING state
- [ ] State transitions recorded correctly
- [ ] `commitJournal()` marks COMPLETE
- [ ] `rollbackJournal()` marks FAILED with error
- [ ] Crash during PREPARING → staging cleaned on recovery
- [ ] Crash during STAGED → staging cleaned on recovery
- [ ] Crash during SAFETY_BACKUP_CREATED → staging cleaned, safety backup retained
- [ ] Crash during SWAPPING → recovery from safety backup
- [ ] Crash after VERIFYING succeeds → COMPLETE, journal deleted
- [ ] Crash during VERIFYING failure → ROLLING_BACK → NORMAL
- [ ] Safety backup corrupt on recovery → critical recovery screen triggered
- [ ] Journal deleted after COMPLETE

**Done when:** Journal and recovery tests pass. Startup hook integrated into `App.onCreate()`.

---

### PR 6 — Restore `.costbackup` (DB-only path)

**Goal:** End-to-end restore of encrypted DB bundle (no assets yet). This is the first integration PR that ties together PR 3, 4, and 5.

**Files to create:**
| File | Purpose |
|---|---|
| `domain/backup/RestoreCoordinator.kt` | Orchestrates the full restore pipeline |
| `data/backup/RestoreCoordinatorImpl.kt` | Implementation: read → validate → stage → safety → swap → verify → complete |

**Files to modify:**
| File | Change |
|---|---|
| `domain/backup/DatabaseOperationResults.kt` | Make `SuccessNeedsRestart` functional — add `data class SuccessNeedsRestart(val summary: DatabaseImportSummary)` with actual summary data |
| `domain/backup/DatabaseBackupRepository.kt` | Add `suspend fun restoreCostBackup(file: File, password: String): Result<DatabaseImportResult>` |
| `data/repository/DatabaseBackupRepositoryImpl.kt` | Implement `restoreCostBackup()` delegating to `RestoreCoordinator` |

**Full restore flow (`RestoreCoordinator.restore(file, password)`):**
1. **Enter maintenance mode** — `RESTORE_PREPARING`.
2. **Create restore journal** — state = PREPARING.
3. **Read & decrypt bundle** — `BackupBundleReader.read(file, password)`.
   - ✋ Wrong password → `WrongBackupPasswordException` → exit maintenance → return `Error`. **Live DB never touched.**
4. **Validate bundle** — manifest, checksums, format version.
5. **Validate DB schema** — snapshot schema version must be ≤ current. If older, Room migration will handle it.
6. **Extract staged DB** — copy decrypted `database.sqlite` to `{filesDir}/import_stage_{uuid}.db`.
7. **Open staged DB through Room** — triggers migrations if needed.
8. **Verify staged DB:**
   - `PRAGMA integrity_check` = ok
   - `PRAGMA foreign_key_check` = 0 violations
   - Schema version after migration = current
   - Meaningful data check (expanded — see §13 of template)
9. **Update journal** → state = STAGED.
10. **Create safety backup** → state = SAFETY_BACKUP_CREATED.
    - If safety backup fails → `Error` → exit maintenance mode.
11. **Swap live DB** (atomic-ish protocol from PR 5):
    - Update journal → state = SWAPPING.
    - Close Room.
    - Move live → `.pre_restore`.
    - Copy staged → live.
    - Reopen Room.
12. **Verify live DB:**
    - Update journal → state = VERIFYING.
    - `PRAGMA integrity_check` = ok
    - `PRAGMA foreign_key_check` = 0 violations
    - **Full verification** (Tier 1 exact counts, Tier 2 validity — wired in PR 7)
    - If failed → restore from safety backup → `Error` → exit maintenance mode.
13. **Cleanup:**
    - Delete `.pre_restore`.
    - Delete staging files.
    - Delete decrypted temp workspace.
14. **Update journal** → state = COMPLETE.
15. **Set maintenance mode** → `RESTORE_COMPLETE_RESTART_REQUIRED`.
16. **Return** `SuccessNeedsRestart`.

**Tests (new file: `src/test/.../data/backup/RestoreCoordinatorTest.kt`):**
- [ ] Restore succeeds — all table counts preserved
- [ ] `SuccessNeedsRestart` returned after successful restore
- [ ] Wrong password → live DB untouched (verify live DB row counts unchanged)
- [ ] Corrupt bundle → live DB untouched
- [ ] Unsupported format → live DB untouched
- [ ] Migration applied (restore older schema DB → upgraded to current)
- [ ] Safety backup created before swap
- [ ] Verification failure → rollback to safety backup successful
- [ ] Restore fails if safety backup creation fails (abort, exit maintenance)
- [ ] After success, maintenance mode = `RESTORE_COMPLETE_RESTART_REQUIRED`
- [ ] After failure, maintenance mode = `NORMAL`

**Done when:** `RestoreCoordinatorTest` passes. Full `.costbackup` round-trip (create → restore) works end-to-end in tests.

---

### PR 7 — Full Verification Expansion

**Goal:** Replace the 5-table `actual >= source` check with full 56-entity verification.

**Files to create:**
| File | Purpose |
|---|---|
| `domain/backup/BackupVerificationPolicy.kt` | Tier definitions for all 56 tables |
| `domain/backup/BackupTableVerifier.kt` | Runs Tier 1 exact counts, Tier 2 validity, PRAGMA checks |
| `domain/backup/VerifiedTableResult.kt` | Per-table result: expected count, actual count, pass/fail, delta explanation |

**Verification tier assignment (all 56 entities):**

### Tier 1 — Exact Row Count Required (30 tables)

These are user/business data tables. Row loss or unexpected duplication = restore failure.

| # | Table (Entity) | Notes |
|---|---|---|
| 1 | `RawNotification` | Only if `includeRawNotifications = true` |
| 2 | `Expense` | Core — must match exactly |
| 3 | `Category` | Core |
| 4 | `MerchantCategory` | Core |
| 5 | `PendingReview` | Core |
| 6 | `UserCorrection` | User data |
| 7 | `SourceStats` | User data |
| 8 | `Budget` | Core |
| 9 | `ScannedReceipt` | Receipt metadata |
| 10 | `ManualRecurringExpense` | Recurring config |
| 11 | `PlannedExpense` | Planning data |
| 12 | `SavingsGoal` | Savings data |
| 13 | `Warranty` | Warranty tracking |
| 14 | `ReturnWindow` | Return tracking |
| 15 | `MileageTracking` | Mileage records |
| 16 | `ExpenseGroup` | Group expenses |
| 17 | `GroupMember` | Group data |
| 18 | `GroupExpense` | Group data |
| 19 | `Investment` | Investment tracking |
| 20 | `InvestmentValue` | Investment data |
| 21 | `BankConnection` | Bank connection data |
| 22 | `SplitTemplate` | Split config |
| 23 | `SplitItemAssignment` | Split data |
| 24 | `SubscriptionCandidate` | Subscription data |
| 25 | `SubscriptionPriceHistory` | Subscription data |
| 26 | `SubscriptionUsage` | Subscription data |
| 27 | `BudgetForecast` | Budget data |
| 28 | `BudgetAdjustmentRecommendation` | Budget data |
| 29 | `BudgetAdjustmentEvent` | Budget data |
| 30 | `SpendingChallengeEntity` | Challenge data |

### Tier 2 — Count May Differ But Must Be Valid (16 tables)

These are derived, cached, or event-log tables. Row count can differ (old caches dropped, events regenerated). Must pass integrity and FK checks.

| # | Table (Entity) | Notes |
|---|---|---|
| 1 | `BlockedPackage` | Config, may be recreated |
| 2 | `MerchantCanonical` | Normalization, may differ |
| 3 | `MerchantAlias` | Normalization, may differ |
| 4 | `MerchantLocation` | Lookup data |
| 5 | `MerchantLocationCorrection` | Correction data |
| 6 | `AiArtifactEntity` | AI cache, can be regenerated |
| 7 | `AiChatSessionEntity` | Chat history |
| 8 | `AiChatMessageEntity` | Chat history |
| 9 | `RecommendationEntity` | Derived recommendations |
| 10 | `ReceiptItemCategorization` | AI-derived |
| 11 | `TransactionEvent` | Event log |
| 12 | `ReceiptEvent` | Event log |
| 13 | `ReceiptExpenseLink` | Derived links |
| 14 | `RecurringOccurrence` | Derived occurrences |
| 15 | `RecurringReminderDelivery` | Delivery log |
| 16 | `RecurringLifecycleEvent` | Event log |

### Tier 3 — Optional/Cache (10 tables)

These can be absent. Schema validity only.

| # | Table (Entity) | Notes |
|---|---|---|
| 1 | `ExchangeRate` | External data, can be refetched |
| 2 | `AnomalyAlert` | Derived alerts |
| 3 | `PromptState` | UI state |
| 4 | `HealthScoreHistory` | Derived scores |
| 5 | `SavingsSweepPlan` | Derived plan |
| 6 | `SpendingPersonalityProfileEntity` | Derived profile |
| 7 | `StressForecastSnapshot` | Derived forecast |
| 8 | `EmailReceiptSource` | External source config |
| 9 | `PrivacyAuditEvent` | Audit log (only if `includePrivacyAuditEvents`) |
| 10 | `BackgroundJobRun` | Worker log, regenerated |

**Verification rules:**
```kotlin
// Tier 1: EXACT
actualCount == expectedCount   // NOT >= expectedCount!
// If migration documented-delta exists, apply it, but document WHY.

// Tier 2: VALIDITY
actualCount >= 0
PRAGMA foreign_key_check passes
Schema column set matches expected

// Tier 3: OPTIONAL
PRAGMA integrity_check passes for table
```

**Additional mandatory checks:**
```sql
PRAGMA integrity_check;    -- must return "ok"
PRAGMA foreign_key_check;  -- must return 0 rows
-- Unique index validation on key indexes
-- No duplicate primary keys in any table
```

**Duplicate-increase fix:**

Replace in `DatabaseBackupRepositoryImpl.verifyCoreTableCountPreservedForVerification()`:
```kotlin
// OLD (bug):
if (actualCount < sourceCount) throw Exception(...)
// NEW:
if (actualCount != expectedCount) throw CountMismatchException(table, expected, actual)
```

For tables where migration legitimately changes counts, add a `MigrationDelta` registry:
```kotlin
object MigrationDeltas {
    // Example: schema v86 budgets repair adds a row
    fun expectedDelta(table: String, fromVersion: Int, toVersion: Int): Long
}
```

**Tests (new file: `src/test/.../data/backup/BackupTableVerifierTest.kt`):**
- [ ] All 56 table names present in tier registry (no missing table)
- [ ] Tier 1 exact match passes
- [ ] Tier 1 row LOSS fails
- [ ] Tier 1 unexpected duplicate ROW GAIN fails
- [ ] Tier 2 valid differences allowed (count 5 → 3 passes if FK ok)
- [ ] Tier 2 FK violation fails
- [ ] Tier 3 missing table allowed
- [ ] `PRAGMA integrity_check` failure → restore fails
- [ ] `PRAGMA foreign_key_check` violation → restore fails
- [ ] Backup with only receipts passes meaningful data check
- [ ] Backup with only groups passes meaningful data check
- [ ] Backup with only subscriptions passes meaningful data check
- [ ] Backup with only warranties passes meaningful data check
- [ ] Backup with only savings goals passes meaningful data check

**Done when:** Verifier tests pass. All 56 tables accounted for in tier registry. `restoreCostBackup` uses `BackupTableVerifier` instead of old 5-table check.

---

### PR 8 — Receipt Image Asset Backup + Restore

**Goal:** Bundle receipt images into `.costbackup`, restore with path rewriting.

**Files to create:**
| File | Purpose |
|---|---|
| `data/backup/BackupAssetCollector.kt` | Collects receipt images, computes hashes, copies to workspace |
| `data/backup/BackupAssetRestorer.kt` | Extracts images from bundle, rewrites `scanned_receipts.imagePath` |

**Files to modify:**
| File | Change |
|---|---|
| `data/backup/BackupBundleWriter.kt` | Add step: after snapshot → `BackupAssetCollector.collect(workspace, options)` |
| `data/backup/RestoreCoordinatorImpl.kt` | Add step: after DB swap → `BackupAssetRestorer.restore(assetsDir, dao)` |
| `domain/backup/BackupManifest.kt` | Add `assetEntries: List<ReceiptAssetBackupEntry>` field |

**Backup flow (asset collection):**
1. Query all `ScannedReceipt` rows with non-null `imagePath`.
2. For each receipt:
   - Verify file exists and is readable.
   - Compute SHA-256.
   - Detect MIME type via `URLConnection.guessContentTypeFromName()` or magic bytes.
   - Copy to `workspace/files/receipts/{receiptId}_{originalFilename}`.
   - Record `ReceiptAssetBackupEntry(receiptId, originalPath, bundlePath, sha256, sizeBytes, mimeType)`.
3. Add entries to manifest.
4. Missing/phantom image files → warning, continue (skip that asset).

**Restore flow (asset restoration):**
1. After live DB is swapped and verified, restore assets.
2. For each `ReceiptAssetBackupEntry` in manifest:
   - Extract file from bundle → `{filesDir}/receipts/{newUuid}.jpg`.
   - Verify SHA-256 matches.
   - Update `scanned_receipts.imagePath` in live DB to new path.
3. Verify every restored path exists on disk.
4. For assets where restore failed (disk full, etc.):
   - Keep DB row but clear `imagePath` or set to null.
   - Add warning to `RestoreJournal.error`.
5. For receipts referenced in manifest but missing from bundle → warning.

**Path rewriting rules:**
- NEVER restore absolute paths from another device.
- Generate new UUID-based filenames in the current app's `filesDir/receipts/`.
- If the restored path conflicts with an existing receipt file, generate a NEW UUID (never overwrite).

**Tests (new file: `src/test/.../data/backup/BackupAssetCollectorTest.kt`):**
- [ ] Backup includes receipt image files
- [ ] Each image entry has valid SHA-256
- [ ] Missing file on backup → warning, skipped (no crash)
- [ ] Image checksum mismatch on restore → asset rejected, warning
- [ ] Restored receipt has correct new `imagePath` in DB
- [ ] Restored image file exists on disk at new path
- [ ] Path rewritten (not original absolute path)
- [ ] Empty receipts directory → backup still succeeds, manifest has empty asset list
- [ ] Multiple receipts with same filename → no collision (UUID-based naming)

**Done when:** Asset tests pass. `.costbackup` round-trip preserves receipt images with correct paths.

---

### PR 9 — Production Backup UI

**Goal:** User-facing backup/restore screen accessible outside Debug.

**Files to create:**
| File | Purpose |
|---|---|
| `ui/screens/backup/BackupScreen.kt` | Composable UI for backup/restore/reports |
| `ui/screens/backup/BackupViewModel.kt` | ViewModel: create backup, restore, report navigation |
| `ui/screens/backup/BackupRestartScreen.kt` | Non-dismissible restart-required screen |

**Files to modify:**
| File | Change |
|---|---|
| `ui/screens/settings/SettingsScreen.kt` or navigation graph | Add "Backup & Restore" entry point to navigation |

**Backup tab features:**
- Create full encrypted backup button.
- Toggle: "Include receipt images" (default: ON).
- Toggle: "Include raw notification data" (default: OFF) — with scary warning.
- Toggle: "Include OCR text" (default: OFF) — with scary warning.
- Password input field.
- Confirm password field.
- Password strength indicator.
- Warning: "Passwords cannot be recovered. Store this password safely."
- "Create Backup" button → progress indicator → success with filename + share intent.
- Privacy audit event logged on every backup.

**Restore tab features:**
- "Select backup file" button → SAF file picker (`.costbackup` filter).
- After file selected → password prompt.
- After password → decrypt → show manifest preview:
  - Created date
  - App version
  - Schema version
  - Table summary (count)
  - Receipt image count
  - Raw data included: yes/no
- "Restore this backup" button → **destructive warning**:
  > ⚠️ This will replace ALL current app data with the backup. This cannot be undone.
  > A safety backup will be created before restoring.
- Second confirmation: "I understand, restore now."
- Progress indicator + status messages.
- After success → `BackupRestartScreen` (non-dismissible):
  > Restore complete. Restart the app to finish.
  > [Restart App]
- After failure → error message + "try again" / "contact support".

**Debug screen containment:**
- Raw DB export remains in Debug screen only.
- Add warning banner in Debug DB section:
  > ⚠️ Raw .db export contains unprotected sensitive data. Use .costbackup for normal backup.
- `DatabaseManagementSection` adds "Create Encrypted Backup" button that navigates to `BackupScreen`.

**Tests (new file: `src/test/.../ui/screens/backup/BackupViewModelTest.kt`):**
- [ ] Password mismatch → backup not created, error shown
- [ ] Password too short (< 8 chars) → warning/error
- [ ] Restore confirmation required (two-step)
- [ ] After successful restore → `BackupRestartScreen` state emitted
- [ ] After restore failure → error state, NORMAL maintenance mode
- [ ] Restore in progress → UI shows progress, back navigation blocked
- [ ] Report exports clearly labeled "not a backup" with warning
- [ ] Debug raw DB NOT accessible from production BackupScreen

**Done when:** BackupViewModel tests pass. UI navigable from Settings. Manual smoke test: create backup → factory reset → restore → data intact.

---

### PR 10 — Report Export Cleanup

**Goal:** Clean up accumulated export files, fail on non-finite amounts, ensure paging, label reports correctly.

**Files to create:**
| File | Purpose |
|---|---|
| `data/export/ExportFileCleanupManager.kt` | Delete temp export files on dismiss, TTL cleanup, keep last N |

**Files to modify:**
| File | Change |
|---|---|
| `ui/screens/export/ExportOptionsViewModel.kt` | Call `ExportFileCleanupManager.clearExport()` when UI clears share state |
| `data/repository/ExportDataRepository.kt` | Stream/paginate large exports using `DeterministicExpenseExportPager` |
| `data/repository/AccountingExportRepository.kt` | Ensure accounting exports also page (if not already) |

**Export cleanup rules:**
1. **On dismiss:** Delete the specific export file when user navigates away or cancels share.
2. **TTL cleanup:** Delete exports older than 7 days.
3. **Last-N:** Keep maximum 5 exports per format type.
4. **Run cleanup:** On app startup (via `ExportFileCleanupManager.cleanup()`) and after each new export.

**Non-finite amount fix:**
- In `ExpenseExportMapper` and JSON serializer:
  ```kotlin
  require(amount.isFinite()) { 
      "Non-finite amount $amount in expense #${expense.id}. Export aborted." 
  }
  ```
- Do NOT silently convert `NaN`, `Infinity`, `-Infinity` to `0.0`.
- If non-finite amount found → export fails with clear error message including the expense ID.

**Report labeling fix:**
- CSV export button label: "Export CSV Report (not a backup)"
- JSON export button label: "Export JSON Report (not a backup)"
- Tooltip/banner: "Reports are for accounting and analysis. They cannot restore your app data."
- Accounting export formats: same warning.

**Tests (new file: `src/test/.../data/export/ExportFileCleanupManagerTest.kt`):**
- [ ] `clearExport()` deletes the specific file
- [ ] TTL purge deletes files older than threshold
- [ ] Last-N purge keeps only N most recent
- [ ] `NaN` amount → export fails with error
- [ ] `Infinity` amount → export fails with error
- [ ] Large export (> 2000 rows) pages correctly
- [ ] CSV export file labeled "not a backup" in UI text

**Done when:** Cleanup tests pass. Non-finite amounts cause failure. Reports are labeled as non-restorable.

---

### PR 11 — Debug/Raw DB Containment

**Goal:** Ensure raw `.db` export is ONLY accessible from Debug screen, never from production UI. Add privacy gate and audit events.

**Files to modify:**
| File | Change |
|---|---|
| `ui/screens/debug/DebugViewModel.kt` | `exportDatabase()` checks developer mode flag. Adds explicit consent dialog: "This exports raw unencrypted data. Use .costbackup for normal backup." |
| `ui/screens/debug/DebugScreen.kt` | `DatabaseManagementSection` labels raw export clearly. Wraps in developer-mode gate. |
| `data/repository/DatabaseBackupRepositoryImpl.kt` | `exportDatabase()` plaintext path already checks `RAWBACKUP_EXPORT` gate — ensure it's NEVER bypassed. |

**Guardrails:**
1. `RAWBACKUP_EXPORT` capability is denied when `encryptedBackupEnabled = true` (existing behavior verified).
2. Raw `.db` export filename clearly marked: `expense_tracker_RAW_{timestamp}.db` (NOT `.costbackup`).
3. Privacy audit event recorded for every raw export (already exists? verify).
4. In `BackupScreen` (production): NO path to raw DB export.
5. Lint/ArchUnit test: ensure `exportDatabase()` is only called from `DebugViewModel`.

**Tests:**
- [ ] `BackupViewModel` (production) has no method to export raw `.db`
- [ ] Debug raw export still works when developer mode enabled and `encryptedBackupEnabled = false`
- [ ] Debug raw export DENIED when `encryptedBackupEnabled = true` (privacy gate)
- [ ] Raw export filename contains "RAW" and `.db` extension, NOT `.costbackup`

**Done when:** Raw DB export is fully contained behind debug + privacy gate. Production UI cannot trigger it.

---

### PR 12 — Final Guardrails, Tests, and Documentation

**Goal:** Lock everything down. All tests pass. Documentation complete.

**Documentation to update:**
- `docs/development/BACKUP_RESTORE_FOUNDATION.md` — final version with all sections
- `docs/development/BACKUP_FORMAT_SPEC.md` — byte-level format specification for `.costbackup`
- `docs/development/RESTORE_SAFETY_PROTOCOL.md` — step-by-step restore protocol with journal
- Update `README.md` or developer guide with backup/restore instructions

**Guardrail lint checks (to add or enforce manually):**
- [ ] No direct `.db` file copy outside `BackupBundleWriter` module
- [ ] No restore without maintenance mode (ArchUnit: `RestoreCoordinator` → `MaintenanceGate` dependency)
- [ ] No restore without journal (`RestoreCoordinator` → `RestoreJournalManager` dependency)
- [ ] No backup without manifest (`BackupBundleWriter` → must produce `manifest.json`)
- [ ] No backup without checksum (`BackupBundleWriter` → must produce `checksums.json`)
- [ ] Production UI cannot reference `.db` or `.enc` export methods
- [ ] Report export button text never says "backup" (only "export" or "report")
- [ ] Backup filename always has UUID suffix (regex check)
- [ ] Verification NEVER uses `>=` for Tier 1 tables (grep check)

**Full test suite (verify all test files pass):**
```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:kaptDebugKotlin  
./gradlew.bat :app:testDebugUnitTest
```

**Soak test (manual):**
1. Create `.costbackup` with 500+ expenses, receipts, warranties, groups, subscriptions.
2. Wait for all 7 workers to run at least once.
3. Restore backup.
4. Verify:
   - All expense counts match.
   - All receipt images restored and viewable.
   - All warranties intact.
   - All group memberships preserved.
   - All subscription records intact.
   - No worker is running during restore.
   - Notification listener rejects new notifications during restore.
   - App forces restart after restore.
   - After restart, workers resume normally.

**Done when:** All guardrail checks pass. Full test suite passes. Documentation complete. Soak test passed.

---

## 5. Consolidated Test Matrix

### A. Backup Tests (`BackupBundleWriterTest`, `BackupEncryptionServiceTest`)
- [ ] T1: Encrypted `.costbackup` created successfully
- [ ] T2: Two backups same second → different filenames (no collision)
- [ ] T3: Manifest valid (all fields populated)
- [ ] T4: Checksums valid (SHA-256 matches extracted files)
- [ ] T5: Default backup excludes raw notification content
- [ ] T6: Default backup excludes raw OCR text
- [ ] T7: Full-raw backup (`redacted=false`) includes raw data if opted in
- [ ] T8: Temp workspace deleted after success
- [ ] T9: Temp workspace deleted after failure
- [ ] T10: Encrypted output ≠ plaintext SQLite (magic bytes check)
- [ ] T11: Encrypted output ≠ plaintext ZIP (PK header absent)
- [ ] T12: Wrong password → `WrongBackupPasswordException`
- [ ] T13: Two encryptions with same password → different ciphertext (different salt/IV)

### B. Restore Tests (`RestoreCoordinatorTest`, `BackupBundleReaderTest`)
- [ ] T14: DB-only `.costbackup` restores successfully
- [ ] T15: DB + assets `.costbackup` restores successfully
- [ ] T16: `SuccessNeedsRestart` emitted after restore
- [ ] T17: Wrong password → live DB untouched (count unchanged)
- [ ] T18: Corrupt checksum → live DB untouched
- [ ] T19: Unsupported format version → live DB untouched
- [ ] T20: Migration applied (older schema → current)
- [ ] T21: Safety backup created before swap
- [ ] T22: Verification failure → rollback to safety backup successful
- [ ] T23: Restore fails if safety backup creation fails (abort)

### C. Crash Recovery Tests (`RestoreJournalManagerTest`)
- [ ] T24: Crash before swap → staging cleaned, normal startup
- [ ] T25: Crash during swap → recovery from safety backup
- [ ] T26: Crash after live replace before verify → complete restore or rollback
- [ ] T27: Startup recovery detects incomplete restore → fixes or warns
- [ ] T28: Journal deleted only after COMPLETE state
- [ ] T29: Double crash (crash during recovery) → critical recovery screen

### D. Maintenance Mode Tests (`DatabaseMaintenanceGateTest`)
- [ ] T30: Workers cancelled during restore (all 7)
- [ ] T31: Notification ingestion blocked during restore
- [ ] T32: Transaction writes blocked during restore
- [ ] T33: Receipt writes blocked during restore
- [ ] T34: Maintenance mode exits to NORMAL on failure
- [ ] T35: Maintenance mode exits to RESTORE_COMPLETE_RESTART_REQUIRED on success
- [ ] T36: Mode timeout (5 min) forces exit to NORMAL

### E. Verification Tests (`BackupTableVerifierTest`)
- [ ] T37: Tier 1 row loss → restore fails
- [ ] T38: Tier 1 unexpected duplicate gain → restore fails
- [ ] T39: Tier 2 valid count differences → allowed
- [ ] T40: All meaningful-data table groups accepted (receipts-only, groups-only, warranties-only, subscriptions-only)
- [ ] T41: `PRAGMA foreign_key_check` failure → restore rejected
- [ ] T42: `PRAGMA integrity_check` failure → restore rejected
- [ ] T43: Migration delta applied correctly (documented count change accepted)
- [ ] T44: All 56 tables present in tier registry (ArchUnit/grep assertion)

### F. Asset Tests (`BackupAssetCollectorTest`)
- [ ] T45: Receipt image included in backup
- [ ] T46: Restored receipt `imagePath` rewritten (new UUID path)
- [ ] T47: Restored image file exists on disk
- [ ] T48: Asset checksum mismatch → asset rejected + warning
- [ ] T49: Missing optional asset → warning, DB row kept
- [ ] T50: Orphan image (in manifest, not in DB) → not restored unless manifest explicitly includes

### G. Export Tests (`ExportFileCleanupManagerTest`)
- [ ] T51: Dismissing export → temp file deleted
- [ ] T52: TTL purge → old exports deleted
- [ ] T53: Last-N purge → only N recent kept
- [ ] T54: `NaN` amount → export fails with error
- [ ] T55: `Infinity` amount → export fails with error
- [ ] T56: Large export (>2000 rows) streams in pages
- [ ] T57: CSV export UI label: "not a backup"
- [ ] T58: JSON export UI label: "not a backup"

### H. UI + Containment Tests (`BackupViewModelTest`)
- [ ] T59: Password mismatch → export blocked
- [ ] T60: Restore requires two-step confirmation
- [ ] T61: `BackupRestartScreen` shown after success
- [ ] T62: Production UI cannot trigger raw `.db` export
- [ ] T63: Debug raw export still works if developer mode + privacy gate allows
- [ ] T64: Raw export denied when privacy gate denies `RAWBACKUP_EXPORT`

---

## 6. Acceptance Criteria

Phase 9 is complete when ALL of the following hold:

1. ✅ `.costbackup` format exists as the default backup type.
2. ✅ Backups encrypted with user-provided password (AES-256-GCM, PBKDF2 600K iterations).
3. ✅ Backup contains `manifest.json` and `checksums.json`.
4. ✅ DB snapshot sanitized by default (raw notifications, OCR text stripped).
5. ✅ Receipt images included in backup when opted in, restored with path rewriting.
6. ✅ Restore uses maintenance mode — all writes blocked.
7. ✅ All 7 workers paused/cancelled during restore.
8. ✅ `NotificationCaptureService` blocked during restore.
9. ✅ Restore journal exists — survives crashes.
10. ✅ Startup crash recovery handles incomplete restores.
11. ✅ Safety backup rollback works after verification failure.
12. ✅ Verification covers all 56 tables (Tier 1 exact, Tier 2 valid, Tier 3 optional).
13. ✅ Verification uses exact counts for Tier 1 (NOT `>=`).
14. ✅ Meaningful data check covers non-expense data (receipts, warranties, groups, subscriptions, savings).
15. ✅ Successful restore returns `SuccessNeedsRestart` and forces app restart.
16. ✅ Production `BackupScreen` exists outside Debug, accessible from Settings.
17. ✅ Raw `.db` export is Debug-only, gated by privacy capability.
18. ✅ Report exports clearly labeled "not a backup".
19. ✅ Export temp files cleaned on dismiss, TTL, and last-N policies.
20. ✅ Non-finite amounts (NaN, Infinity) cause export failure, not silent conversion.
21. ✅ All 64 tests from the consolidated test matrix pass.
22. ✅ One manual soak test passed (create → restore → verify all data intact).

---

## 7. Implementation Order & Dependencies

```
PR 0: Docs (no deps)
  ↓
PR 1: Models + Encryption (no deps)
  ↓
PR 2: Bundle Writer (depends on PR 1)
  ↓
PR 3: Bundle Reader (depends on PR 1, can parallel with PR 2)
  ↓
PR 4: Maintenance Gate (depends on PR 1)
  ↓
PR 5: Restore Journal (depends on PR 1)
  ↓
PR 6: Restore Coordinator (depends on PR 2, 3, 4, 5)
  ↓
PR 7: Full Verification (depends on PR 6 for integration hook)
  ↓
PR 8: Asset Backup/Restore (depends on PR 2, 6)
  ↓
PR 9: Production UI (depends on PR 6, 7, 8)
  ↓
PR 10: Export Cleanup (independent of backup, can run parallel with PR 7-9)
  ↓
PR 11: Debug Containment (depends on PR 9)
  ↓
PR 12: Guardrails + Docs (depends on all)
```

**Parallelizable pairs:**
- PR 2 + PR 3 can be developed in parallel (writer and reader against a common spec)
- PR 10 (export cleanup) can be done in parallel with PR 7–9

---

## 8. Risks & Contingencies

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Room migration fails on staged DB | Medium | High — restore blocked | PR 6: validate staged DB with Room migration before swap. If migration fails, return clear error with schema version difference. |
| Safety backup corrupt on recovery | Low | Critical — data loss | PR 5: verify safety backup with `PRAGMA integrity_check` before using. If corrupt, show critical recovery screen (last resort: reinstall). |
| Worker has stale DB handle after swap | Medium | Medium — writes to old DB | PR 4: all workers cancelled BEFORE swap. After restart, workers get fresh DB handles. |
| Large receipts directory (100s of MB) | Medium | Medium — slow backup/restore | PR 8: asset collection is async with progress callback. Future: compression or lazy-load option. |
| Password forgotten by user | Medium | High — backup unrecoverable | PR 1: UX warning that passwords cannot be recovered. PR 9: password strength + confirmation. Future: optional biometric convenience (not Phase 9). |
| Disk full during backup | Low | High — partial file | PR 2: write to temp first, verify, then atomically rename to final path. Catch `IOException` for disk space. |
| Concurrent app use during restore | Medium | High — data corruption | PR 4: maintenance mode blocks writes. PR 9: full-screen overlay during restore. |

---

## 9. Post-Phase 9: Future Enhancements (NOT in Phase 9)

These are explicitly deferred:

- 🔮 Scheduled/periodic automatic encrypted backup (WorkManager + SAF URI)
- 🔮 Cloud backup destinations (Google Drive, Dropbox, WebDAV)
- 🔮 Multi-device merge/conflict resolution
- 🔮 Biometric convenience unlock for backup password
- 🔮 Incremental/differential backup
- 🔮 Backup size estimation before export
- 🔮 Backup comparison (diff two backups)
- 🔮 Selective table-level restore ("restore only expenses")
- 🔮 Encrypted backup sharing with family/accountant (shared password)

---

## 10. Pre-Flight Checklist (before PR 1)

Run these to confirm Phase 9 preconditions:

```bash
# Compile check
./gradlew.bat :app:compileDebugKotlin

# Hilt graph
./gradlew.bat :app:kaptDebugKotlin

# Existing tests
./gradlew.bat :app:testDebugUnitTest

# DB version
# Confirm APP_DATABASE_SCHEMA_VERSION = 106 in AppDatabase.kt
```

If Phase 9 adds any new columns/tables → bump to v107.  
**As planned, Phase 9 adds no new schema** — all new data classes live outside Room. Version stays v106 unless a migration column is needed.

---

## 11. Files Summary

### New files (≈ 35 files)

**domain/backup/** (8 files):
- `BackupManifest.kt`
- `BackupChecksums.kt`
- `BackupOptions.kt`
- `BackupBundleHeader.kt`
- `BackupPasswordProvider.kt`
- `DatabaseMaintenanceMode.kt`
- `DatabaseMaintenanceGate.kt`
- `MaintenanceModeState.kt`
- `RestoreJournal.kt`
- `RestoreJournalState.kt`
- `RestoreCoordinator.kt`
- `BackupVerificationPolicy.kt`
- `BackupTableVerifier.kt`
- `VerifiedTableResult.kt`
- `ReceiptAssetBackupEntry.kt`

**data/backup/** (13 files):
- `BackupPasswordProviderImpl.kt`
- `BackupTempFileManager.kt`
- `BackupDatabaseSnapshotter.kt`
- `BackupManifestBuilder.kt`
- `BackupChecksumService.kt`
- `BackupBundleWriter.kt`
- `BackupBundleValidator.kt`
- `BackupBundleReader.kt`
- `DatabaseMaintenanceGateImpl.kt`
- `WorkerPauseManager.kt`
- `RestoreJournalManager.kt`
- `RestoreCrashRecovery.kt`
- `RestoreCoordinatorImpl.kt`
- `BackupAssetCollector.kt`
- `BackupAssetRestorer.kt`

**data/export/** (1 file):
- `ExportFileCleanupManager.kt`

**ui/screens/backup/** (3 files):
- `BackupScreen.kt`
- `BackupViewModel.kt`
- `BackupRestartScreen.kt`

**test files** (≈ 8 files):
- `BackupEncryptionServiceTest.kt`
- `BackupBundleWriterTest.kt`
- `BackupBundleReaderTest.kt`
- `DatabaseMaintenanceGateTest.kt`
- `RestoreJournalManagerTest.kt`
- `RestoreCoordinatorTest.kt`
- `BackupTableVerifierTest.kt`
- `BackupAssetCollectorTest.kt`
- `ExportFileCleanupManagerTest.kt`
- `BackupViewModelTest.kt`

**docs/** (4 files):
- `docs/development/BACKUP_RESTORE_FOUNDATION.md`
- `docs/development/BACKUP_FORMAT_SPEC.md`
- `docs/development/RESTORE_SAFETY_PROTOCOL.md`

### Modified files (≈ 12 files)

| File | PR |
|---|---|
| `data/privacy/BackupEncryptionService.kt` | PR 1 |
| `domain/backup/DatabaseOperationResults.kt` | PR 6 |
| `domain/backup/DatabaseBackupRepository.kt` | PR 6 |
| `data/repository/DatabaseBackupRepositoryImpl.kt` | PR 6, 7 |
| `data/backup/BackupBundleWriter.kt` | PR 8 |
| `data/backup/RestoreCoordinatorImpl.kt` | PR 8 |
| `domain/backup/BackupManifest.kt` | PR 8 |
| `ui/screens/export/ExportOptionsViewModel.kt` | PR 10 |
| `data/repository/ExportDataRepository.kt` | PR 10 |
| `data/repository/AccountingExportRepository.kt` | PR 10 |
| `ui/screens/debug/DebugViewModel.kt` | PR 11 |
| `ui/screens/debug/DebugScreen.kt` | PR 11 |
| `service/NotificationCaptureService.kt` | PR 4 |
| `domain/export/ExpenseExportMapper.kt` | PR 10 |
| Navigation graph / Settings | PR 9 |

---

@orchestrator The Advanced Technical Plan is ready. Please begin execution of Batch 1 (PR 0 — Baseline Documentation).
