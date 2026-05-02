# Backup / Restore / Export — Re-Verification Review

Date: 2026-05-02  
Review of: `docs/analyses and debug master/backup-restore-export-analysis.md`  
Against: current `app/src/main/java/com/yourname/expensetracker/**`

---

## EXECUTIVE VERDICT: FAIL

Substantial progress has been made — the new `.costbackup` bundle format, `BackupVerifier` (56-table), `RestoreMaintenanceMode`, and `RestoreJournal` address many of the highest-priority issues. However, the **legacy `importDatabase()` / `exportDatabase()` paths** remain un-upgraded in several critical areas, and the reporting export issues (12–16) are almost entirely unresolved. Twelve of sixteen original issues are only **partially** resolved because fixes were applied only to the new `.costbackup` path, leaving the legacy path as a vulnerability.

---

## PER-ISSUE STATUS

### Issue 1 — Full DB backups are plaintext

**Status: PARTIALLY RESOLVED**

| Aspect | Status |
|--------|--------|
| `.costbackup` encrypted bundle format | ✅ Created (`CostbackupBundle.kt`), AES-256-GCM |
| `restoreCostBackup()` using password | ✅ Implemented |
| `createCostBackup()` wired to UI | ✅ Integrated in `BackupRestoreScreen` |
| Legacy `exportDatabase()` deprecated | ✅ `@Deprecated` annotation |
| Old `.db` export gated behind privacy check | ✅ `PrivacyCapability.RAWBACKUP_EXPORT` |
| Old `.enc` auto-key encrypted option | ✅ Added via `BackupEncryptionService` |
| Raw `.db` export hidden behind debug mode | ❌ Still accessible if privacy gate allows |

*Remaining gap*: The legacy `exportDatabase()` is deprecated but not dead. It is still callable if the `RAWBACKUP_EXPORT` privacy capability is allowed. The analysis recommended raw `.db` export be truly hidden behind debug/advanced mode, not just a runtime privacy gate.

---

### Issue 2 — Backup copies only the DB, not receipt/image files

**Status: RESOLVED**

| Aspect | Status |
|--------|--------|
| `createCostBackup()` includes receipt images | ✅ `includeReceiptImages` / `collectReceiptAssetsForBackup()` |
| Manifest with `BackupIncludes` | ✅ `database`, `receiptImages`, `rawNotifications`, `rawOcr` |
| `restoreCostBackup()` restores receipt assets | ✅ `restoreReceiptAssets()` rewrites paths |
| Checksums for file assets | ✅ SHA-256 in `checksums.json` |
| Legacy `exportDatabase()` includes files | N/A — deprecated path |

---

### Issue 3 — Import verification checks only five tables

**Status: RESOLVED**

| Aspect | Status |
|--------|--------|
| `BackupVerifier` with 56 tables | ✅ 3-tier system (30 Tier-1, 16 Tier-2, 10 Tier-3) |
| `queryRoomCountsForVerification()` uses `allTableCounts` | ✅ `BackupVerifier.allTableNames()` |
| Fallback to 5-table for legacy | ✅ Functional but less comprehensive (see also NEW-F) |
| Manifest includes per-table counts | ✅ `CostbackupBundle.BackupManifest.tableCounts` |

---

### Issue 4 — Verification allows duplicate row increases

**Status: RESOLVED**

| Aspect | Status |
|--------|--------|
| Old `>=` check replaced with exact match | ✅ `verifyCoreTableCountPreservedForVerification` uses `!=` |
| `BackupVerifier` Tier-1 exact count | ✅ `actual != expected → CountMismatchException` |
| Comment documents the fix | ✅ "EXACT match required — NOT >= (G5/G6 fix)" |

---

### Issue 5 — Live DB replacement is not crash-atomic

**Status: PARTIALLY RESOLVED**

| Aspect | Status |
|--------|--------|
| `.costbackup` path: `renameTo` instead of delete | ✅ Moves live → `.pre_restore` first |
| `.costbackup` path: `RestoreJournal` | ✅ State machine with `checkAndRecover()` |
| Startup crash recovery | ✅ `AppStartupCoordinator.checkRestoreJournal()` |
| Legacy `importDatabase()` path | ❌ Still uses `replaceDatabaseFiles()` — delete-then-copy; no journal |
| Safety backup rollback on failure | ✅ Both paths use `restoreFromSafetyBackup()` |

---

### Issue 6 — Import runs while the app may still be active

**Status: PARTIALLY RESOLVED**

| Aspect | Status |
|--------|--------|
| `restoreCostBackup()` enters `RESTORE_PREPARING` | ✅ `restoreMaintenanceMode.enter()` |
| Pauses all 7 workers | ✅ `pauseAllWorkers()` via `WorkManager.cancelUniqueWork()` |
| Blocks writes | ✅ `isWritesAllowed()` returns `false` for non-NORMAL modes |
| Legacy `importDatabase()` (DebugViewModel) | ❌ Does **NOT** enter maintenance mode |
| Legacy `importDatabase()` (repository) | ❌ No `restoreMaintenanceMode.enter()` call |

---

### Issue 7 — Successful import does not clearly force a clean app restart

**Status: PARTIALLY RESOLVED**

| Aspect | Status |
|--------|--------|
| `.costbackup` path: `exit(forceRestartRequired=true)` | ✅ Returns `SuccessNeedsRestart` |
| Startup reads `RESTORE_COMPLETE_RESTART_REQUIRED` | ✅ `AppStartupCoordinator` sets `SharedPreferences` flag |
| UI shows restart banner | ✅ `BackupRestoreScreen` banner with "Restart Now" |
| Legacy `importDatabase()` returns `SuccessNeedsRestart` | ❌ Returns plain `Success` only; `DebugViewModel` uses `transactionCount == -1` hack |
| Maintenance mode keeps writes blocked until restart | ✅ `RESTORE_COMPLETE_RESTART_REQUIRED` |

---

### Issue 8 — Backup filenames can collide within the same second

**Status: PARTIALLY RESOLVED**

| Aspect | Status |
|--------|--------|
| `.costbackup` filename | ✅ `expense_tracker_backup_{timestamp}_{8charUUID}.costbackup` |
| Legacy `exportDatabase()` filename | ❌ `${BACKUP_PREFIX}${timestamp}.db` — second-only resolution |
| `createSafetyBackup()` filename | ❌ `${BACKUP_PREFIX}SAFETY_${timestamp}.db` — second-only resolution |

*Risk*: Safety backups are created during import/reset — two safety backups within the same second can collide.

---

### Issue 9 — "Meaningful data" check can reject valid non-core backups

**Status: PARTIALLY RESOLVED**

| Aspect | Status |
|--------|--------|
| `.costbackup` restore: checks all manifest table counts | ✅ `manifestTableCounts.values.all { it == 0 }` |
| `DatabaseImportSummary.hasMeaningfulData()` extended | ✅ Now includes receipt, warranty, group, subscription, savings counts |
| Legacy `importDatabase()`: `SourceValidationSummary.hasMeaningfulData()` | ❌ Still only 5 old fields |

---

### Issue 10 — Reset database is very destructive

**Status: STILL PRESENT**

| Aspect | Status |
|--------|--------|
| Safety backup before reset | ✅ Present |
| Explicit typed confirmation | ❌ Missing |
| Debug/admin guard | ❌ Missing |
| Backup share/save option before wipe | ❌ Missing |
| App restart after wipe | ❌ Missing |

---

### Issue 11 — CSV/JSON export is not a restorable backup

**Status: PARTIALLY RESOLVED**

| Aspect | Status |
|--------|--------|
| Separate Backup/Restore screen with `.costbackup` | ✅ `BackupRestoreScreen` distinct from `ExportOptionsScreen` |
| Export screen still calls formats "Export" | ⚠️ Functional separation exists in code; label clarity could improve |
| CSV/JSON still labeled generically | ❌ No explicit "This is a report, not a restorable backup" warning |

---

### Issue 12 — Export UI loads all expenses into memory

**Status: STILL PRESENT**

| Aspect | Status |
|--------|--------|
| `DeterministicExpenseExportPager` with paged queries | ⚠️ Partial — queries page-by-page but accumulates all into `mutableListOf<Expense>()` |
| Streaming write to file incrementally | ❌ All expenses collected before writing starts |
| Stable ID snapshot | ❌ Not implemented |

---

### Issue 13 — Export has no snapshot consistency

**Status: STILL PRESENT**

| Aspect | Status |
|--------|--------|
| Count loaded separately from final list | ❌ Still two separate queries |
| Stable transaction/snapshot | ❌ Not implemented |

---

### Issue 14 — JSON export silently converts invalid numbers to `0.0`

**Status: STILL PRESENT**

| Aspect | Status |
|--------|--------|
| `formatJsonNumber` returns `"0.0"` for non-finite | ❌ Line 353: `if (value.isFinite()) value.toString() else "0.0"` |
| No error emitted | ❌ Exported JSON silently contains valid `0.0` for corrupt data |

---

### Issue 15 — Date range validation is weak

**Status: STILL PRESENT**

| Aspect | Status |
|--------|--------|
| `setDateRange()` validates start < end | ❌ No guard |
| End-of-day / half-open adjustment | ❌ Not implemented |
| Future-only range guard | ❌ Not implemented |
| Absurdly large range confirmation | ❌ Not implemented |

---

### Issue 16 — Export files can accumulate in app-private storage

**Status: PARTIALLY RESOLVED**

| Aspect | Status |
|--------|--------|
| Export directory moved to `cacheDir` | ✅ `ExportDataRepository.createExportFile()` uses `context.cacheDir` |
| `clearExport()` deletes temp file | ❌ Only clears UI state (line 358–365 of `ExportOptionsViewModel`) |
| Explicit cleanup of old exports | ❌ Not implemented |

---

## NEW ISSUES FOUND

During cross-check, the following additional issues were identified:

### NEW-A [MAJOR] Legacy `importDatabase()` does not use maintenance mode or journal

**File**: `DatabaseBackupRepositoryImpl.kt` — `importDatabase()`  
**File**: `DebugViewModel.kt` — `importDatabase()`  

The `.costbackup` restore path now safely gates writes, pauses workers, and journals every step. The legacy `importDatabase()` path (called from `DebugViewModel`) performs none of these: no `restoreMaintenanceMode.enter()`, no `RestoreJournal`, and no forced restart. This means a debug-mode import can race with active workers and leave stale app state.

**Fix**: Either retire `importDatabase()` in favor of `restoreCostBackup()`, or backport maintenance mode + journal to it.

---

### NEW-B [MAJOR] `DebugViewModel` uses fragile `transactionCount == -1` restart heuristic

**File**: `DebugViewModel.kt`, line 447  

```kotlin
val needsRestart = summary?.transactionCount == -1
```

The legacy `importDatabase()` never returns `transactionCount == -1`. This is dead code. The correct check should match the `SuccessNeedsRestart` return type from `restoreCostBackup()`, but legacy import never returns that type.

**Fix**: Route the debug import through `restoreCostBackup()` or implement proper restart detection.

---

### NEW-C [MINOR] `BackupEncryptionService.encrypt(File, OutputStream, String)` reads entire file into memory

**File**: `BackupEncryptionService.kt`, line 80  

```kotlin
fun encrypt(plaintextFile: File, outputStream: OutputStream, password: String) {
    val plaintext = plaintextFile.readBytes()  // ← reads entire ZIP into memory
```

The `.costbackup` ZIP is built to a temp file, then this method reads the entire temp ZIP into a `ByteArray` before encrypting. For large databases with many receipt images, this can cause OOM.

**Fix**: Use `CipherOutputStream` to stream the encryption directly from the file.

---

### NEW-D [MINOR] `RestoreJournal.failJournal()` / `commitJournal()` write state then immediately delete

**File**: `RestoreJournal.kt`, lines 173–188  

Both methods write the journal to disk with the final state (COMPLETE or FAILED) and then immediately call `deleteJournal()`. While harmless, the intermediate write is wasted I/O and the journal is never observed in its terminal state by crash recovery (since it's deleted).

**Fix**: Either skip the write when the journal will be deleted, or keep the journal for audit purposes.

---

### NEW-E [MINOR] `RestoreMaintenanceMode.exit()` does not reschedule cancelled workers

**File**: `RestoreMaintenanceMode.kt`, lines 77–91  

When `exit(forceRestartRequired = false)` transitions to NORMAL, the comment says _"Workers are re-enabled; they'll be rescheduled on next app start"_. This is correct because `AppStartupCoordinator.scheduleStartupWork()` reschedules them. However, if the app does not restart (e.g., user dismisses the restart banner), the paused workers remain cancelled indefinitely.

**Fix**: Explicitly reschedule workers on `exit(NORMAL)` to handle the no-restart path.

---

### NEW-F [MAJOR] Legacy `importDatabase()` verification doesn't capture all table counts

**File**: `DatabaseBackupRepositoryImpl.kt`, line 1255–1263  

`SourceValidationSummary.toImportSummary()` only populates 5 old fields. The resulting `DatabaseImportSummary` has empty `allTableCounts`, which causes `verifySummaryPreservedForVerification()` to fall back to checking only 5 tables (lines 218–250). This means a legacy `.db` import skips verification of receipts, warranties, groups, subscriptions, etc. — exactly the problem Issue 3 described.

**Fix**: Populate `allTableCounts` via `BackupVerifier.allTableNames()` in the legacy import path too.

---

### NEW-G [MINOR] `.costbackup` temp ZIP file not cleaned up on certain failure paths

**File**: `CostbackupBundle.kt`, line 238–246  

The temp ZIP is cleaned in the `finally` block. However, if `buildZip()` succeeds but the encryption or output write throws, the temp ZIP is cleaned. This is correct. But if `buildZip()` itself throws, the temp file may not exist yet — the `delete()` would fail silently, which is fine. **No action needed**, just confirming correctness.

---

## RECOMMENDED FIX ORDER (Updated)

| Priority | Action | Affected Issues |
|----------|--------|----------------|
| **P0** | Retire or upgrade legacy `importDatabase()` / `exportDatabase()` to use maintenance mode, journal, and full table verification | 5, 6, 7, NEW-A, NEW-B, NEW-F |
| **P1** | Stream `.costbackup` encryption instead of loading full ZIP into memory | NEW-C |
| **P1** | Add UUID/suffix to safety backup filenames | 8 |
| **P2** | Stream report exports with stable ID snapshot + incremental file writing | 12, 13 |
| **P2** | Fix JSON non-finite number handling | 14 |
| **P2** | Add date range validation | 15 |
| **P3** | Add explicit confirmation guard + restart to `resetDatabase()` | 10 |
| **P3** | Delete temp export file on `clearExport()` | 16 |
| **P3** | Add restorable/non-restorable labeling to export UI | 11 |
| **P3** | Extend provenance of legacy `hasMeaningfulData()` | 9 |
| **P4** | Reschedule workers on maintenance mode exit(NORMAL) | NEW-E |
| **P4** | Remove wasted journal write-before-delete | NEW-D |

---

## COVERAGE ASSESSMENT

| Criterion | Status | Detail |
|-----------|--------|--------|
| Requirements met | **Partial** | The `.costbackup` format meets all core backup/restore requirements. However, the legacy paths and export streams do not meet the spec. |
| Testing adequate | **Partial** | Tests exist for `AccountingExportRepository`, `DeterministicExpenseExportPager`, `AccountingExportPolicy`, `ExportOptionsViewModel`, `DatabaseBackupRepositoryImpl`, and `ExpenseExportMapper`. However, there are no tests for the new `.costbackup` restore crash-recovery scenarios, and no integration tests verifying that maintenance mode actually blocks writes during restore. |

---

## REGRESSION TEST TABLE

The analysis recommended 18 regression tests. Current coverage:

| # | Test | Status |
|---|------|--------|
| 1 | Two backups in same second do not collide | ❌ Not covered (safety/legacy still collide) |
| 2 | Full backup restore preserves all tracked table counts | ⚠️ `.costbackup` only |
| 3 | Restore fails if any protected table loses rows | ✅ `BackupVerifier` |
| 4 | Restore fails if protected table gains duplicates | ✅ Exact match |
| 5 | Backup includes receipt images + restore rewrites paths | ✅ `.costbackup` |
| 6 | Import cannot run while notification ingestion writes | ⚠️ `.costbackup` only |
| 7 | Import cannot run while workers active | ⚠️ `.costbackup` only |
| 8 | Crash during DB swap recovers on next startup | ⚠️ `.costbackup` only |
| 9 | Successful restore forces clean app restart | ⚠️ `.costbackup` only |
| 10 | Encrypted backup cannot be read as plaintext SQLite | ✅ AES-256-GCM |
| 11 | Wrong password fails without touching live DB | ✅ Verified |
| 12 | Raw DB export unavailable in normal production UI | ❌ Still available via privacy gate |
| 13 | JSON report export labeled non-restorable | ❌ Not labeled |
| 14 | Large export streams pages without loading all rows | ❌ Still accumulates |
| 15 | Export during concurrent edit uses stable row snapshot | ❌ Not implemented |
| 16 | Non-finite amount causes export failure, not `0.0` | ❌ Still `0.0` |
| 17 | Dismissing unsaved export deletes temporary report file | ❌ Not deleted |
| 18 | Reset requires explicit confirmation + safety backup | ⚠️ Safety backup yes, confirmation no |
