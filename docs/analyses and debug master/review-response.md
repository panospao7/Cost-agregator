# Review Response: Migration Policy Cross-Check

> **Source document:** `docs/analyses and debug master/response (1).md`  
> **Codebase scanned:** `app/src/main/java/com/yourname/expensetracker/`  
> **Date:** 2026-05-02

---

## VERDICT: FAIL

Issues remain that must be addressed before the migration policy guidance in `response (1).md` is fully satisfied.

---

## Recommendation-by-Recommendation Cross-Check

### R1: Never use destructive migration in production

| Status | Detail |
|--------|--------|
| **RESOLVED** | `fallbackToDestructiveMigration()` is **not** called in the production builder path. |

- **Evidence:** `AppDatabase.kt` line 6524–6533 (`configureBuilder`) uses only `.addMigrations(*ALL_MIGRATIONS)`. No destructive fallback.
- **Test-only usage:** `DatabaseMigrationTest.kt` line 301 uses `.fallbackToDestructiveMigration()` exclusively in the `fallback_to_destructive_migration_works()` test – acceptable.
- **Verdict:** ✅ Fully resolved.

---

### R2: Keep every old migration path

| Status | Detail |
|--------|--------|
| **PARTIALLY RESOLVED** | Major chain intact (6→108) but one gap plus a multi-hop risk exist. |

**What’s present (good):**
- `ALL_MIGRATIONS` (line 6542–6641) registers **102 consecutive migrations** from `MIGRATION_6_7` through `MIGRATION_107_108`.
- Every migration object exists in the same file, preventing drift.

**Gaps identified:**

1. **[ISSUE-R2A] [MAJOR] No migration path for schema versions 1–5**
   - `ALL_MIGRATIONS` starts at `MIGRATION_6_7`. There are NO `MIGRATION_1_2`, `MIGRATION_2_3`, …, `MIGRATION_5_6`.
   - The production builder does **not** call `fallbackToDestructiveMigration()`, so a device with a v1–v5 database will crash on startup with `IllegalStateException: A migration from X to Y was required but not found`.
   - *Impact:* Any user who installed the app very early and never updated past v5 will lose all financial data if Room cannot migrate.
   - *Fix:* Either add explicit v1→v6 migrations, add `fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)` as a guarded last resort with user consent, or implement a legacy importer (see R5).

2. **[ISSUE-R2B] [MINOR] Multi-hop MIGRATION_96_100 with no per-version fallback**
   - `MIGRATION_96_100` (line 5908) is a single migration spanning four versions (96→97→98→99→100). Room accepts multi-hop migrations.
   - However: **no schema JSON files exist for versions 97, 98, 99** (only `96.json` and `100.json` are present in `app/schemas/`).
   - If any device has database version 97, 98, or 99 (e.g., from an intermediate dev/test build), Room will fail because there is no migration registered FROM those versions.
   - *Fix:* Either add individual `MIGRATION_97_98`, `MIGRATION_98_99`, `MIGRATION_99_100` objects, or ensure versions 97–99 were never released.

---

### R3: Test your real old DB before shipping

| Status | Detail |
|--------|--------|
| **PARTIALLY RESOLVED** | Migration tests exist but have significant coverage gaps. |

**What’s present (good):**
- `DatabaseMigrationTest.kt` (3731 lines) – 56 instrumented migration tests.
- `MigrationContractTest.kt` (880 lines) – 10 contract-level migration tests (no schema JSON dependency).
- Tests exercise data preservation (INSERT→migrate→SELECT), index creation, FK validation, deduplication logic.
- `hasSchema(version)` helper (line 2679) gracefully skips tests when schema JSON is absent.

**Gaps identified:**

1. **[ISSUE-R3A] [MAJOR] No migration tests for versions 92 → 108**
   - The last version-specific migration test is `migrate_91_to_92_heals_email_receipt_sources_default_for_room_validation()` (line 3222).
   - **16 migrations (MIGRATION_92_93 through MIGRATION_107_108) have zero instrumented test coverage.**
   - These migrations add real structural changes: currency columns (v93), transaction events (v94), receipt lifecycle tables (v95), recurring occurrence tables (v96→100), lifecycle events (v101), privacy audit events (v102), data retention columns (v103), database invariants (v104), CHECK constraints (v107→108), and more.
   - *Fix:* Add at minimum one test per migration or a full-chain test from v91→108.

2. **[ISSUE-R3B] [MINOR] No tests use real user database snapshots**
   - All tests rely on Room’s `MigrationTestHelper.createDatabase(version)` which builds a blank schema from the exported JSON. This does not exercise edge cases where real user data has unexpected NULLs, malformed values, or orphaned rows.
   - *Fix:* Add at least one test that opens a pre-prepared `.db` file (captured from a real/varied device) and runs the migration chain against it.

3. **[ISSUE-R3C] [MINOR] No explicit PRAGMA foreign_key_check in test assertions**
   - Migrations 49→50 and 48→49 do perform FK checks internally, but the test assertions do not independently run `PRAGMA foreign_key_check` after migration to catch silent integrity corruption.
   - *Fix:* Add a utility assertion that runs `PRAGMA foreign_key_check` and asserts 0 rows after every migration test.

---

### R4: Add in-app backup/export before risky releases

| Status | Detail |
|--------|--------|
| **PARTIALLY RESOLVED** | Backup/export infrastructure exists; pre-upgrade prompt is missing. |

**What’s present (good):**
- `DatabaseBackupRepository` interface (domain layer) + `DatabaseBackupRepositoryImpl` (data layer, 1815 lines) providing:
  - `exportDatabase()` – legacy `.db` export
  - `importDatabase()` – with safety backup before import
  - `createCostBackup()` – encrypted `.costbackup` bundle with manifest, checksums, receipt images
  - `restoreCostBackup()` – encrypted bundle restore
  - Full 56-table verification via `BackupVerifier`
  - `BackupEncryptionService`, `ExportAnonymizer`, `RestoreJournal`, `RestoreMaintenanceMode`
- `CsvExpenseImporter` – CSV import utility
- `ExportModule` – QuickBooks IIF, Xero CSV, FreshBooks exporters
- `BackupVerifier.allTableNames()` is hardcoded (no SQL injection risk from dynamic table names)

**Gap identified:**

1. **[ISSUE-R4A] [MINOR] No automatic pre-upgrade backup prompt**
   - There is no UI flow that detects a pending schema upgrade and prompts the user to create a backup first, as recommended: *"We recommend creating a backup before updating your database."*
   - *Fix:* In the `MainActivity` or `MainApplication` startup, detect when `APP_DATABASE_SCHEMA_VERSION` has increased since last launch and show a dialog offering backup before proceeding.

---

### R5: For very old schemas, use a legacy importer

| Status | Detail |
|--------|--------|
| **PARTIALLY RESOLVED** | CSV importer exists, but no direct legacy DB importer for v1–v5. |

**What’s present (good):**
- `CsvExpenseImporter` (278 lines) – imports expenses from CSV with full lifecycle (validate → normalize → dedupe → insert → event logging).

**Gap identified:**

1. **[ISSUE-R5A] [MINOR] No legacy database importer for pre-v6 schemas**
   - Combined with ISSUE-R2A: users with v1–v5 databases have no upgrade path (no migrations, no destructive fallback, no legacy importer).
   - A legacy importer would open the old database using `SQLiteDatabase.openDatabase()`, read old tables row-by-row, and insert data into the current Room-managed schema.
   - *Fix:* Implement a `LegacyDatabaseImporter` that handles v1–v5 schemas, or add v1→v6 migrations to `ALL_MIGRATIONS`.

---

### R6: Add fresh-vs-migrated parity tests

| Status | Detail |
|--------|--------|
| **PARTIALLY RESOLVED** | Chain tests exist; explicit parity tests do not. |

**What’s present (good):**
- Multiple chain tests (e.g., `migrate_33_to_51_chain_passes`, `migrate_38_to_51_chain_passes`, `migrate_1_to_34_full_chain`, `migrate_49_to_51_chain_passes`, etc.)

**Gap identified:**

1. **[ISSUE-R6A] [MINOR] No side-by-side parity test**
   - No test creates a fresh v108 database and a v6→108 migrated database, then compares schema (table list, column list, indexes, constraints) and behavior (identical query results with same seed data).
   - *Fix:* Add a parity test that seeds identical data into a fresh v108 DB and a v6→108 migrated DB, then asserts identical row counts and schema structure.

---

## Additional Issues Found (Beyond `response (1).md`)

### [ISSUE-A1] [MAJOR] MIGRATION_107_108 CHECK constraint may fail with existing data

- `MIGRATION_107_108` (line 6424) adds a strict `CHECK` constraint on `planned_expenses.openSourceOccurrenceKey`:
  ```sql
  CHECK(
      (status != 'PLANNED' AND openSourceOccurrenceKey IS NULL)
      OR
      (status = 'PLANNED' AND sourceOccurrenceKey IS NULL AND openSourceOccurrenceKey IS NULL)
      OR
      (status = 'PLANNED' AND sourceOccurrenceKey IS NOT NULL AND openSourceOccurrenceKey = sourceOccurrenceKey)
  )
  ```
- The migration does **heal** rows before rebuilding via `UPDATE` statements (lines 6437–6446), which is good.
- However, if any row has `openSourceOccurrenceKey` populated but `sourceOccurrenceKey` IS NULL while `status='PLANNED'`, the CHECK will fail (third branch requires equality). The healing only sets `openSourceOccurrenceKey = sourceOccurrenceKey` for PLANNED rows – it does **not** handle the case where `openSourceOccurrenceKey` already has a non-NULL value that differs from `sourceOccurrenceKey`.
- *Fix:* Add a pre-healing step that nullifies `openSourceOccurrenceKey` where it is inconsistent with the invariant, or log-and-nullify mismatched rows.

### [ISSUE-A2] [MINOR] `SimpleDateFormat` stored as class property in `CsvExpenseImporter`

- `CsvExpenseImporter` (line 34) stores `SimpleDateFormat` as an instance property. `SimpleDateFormat` is not thread-safe. While `importFromContent()` dispatches to `Dispatchers.IO`, if the class were ever used from multiple coroutines concurrently, date parsing could produce corrupted results.
- *Fix:* Use `java.time.format.DateTimeFormatter` (thread-safe) or wrap `SimpleDateFormat` usage in a `ThreadLocal`.

### [ISSUE-A3] [MINOR] `countRowsFromSourceTable` uses string interpolation for table names

- `DatabaseBackupRepositoryImpl.kt` line 1315: `db.rawQuery("SELECT COUNT(*) FROM $tableName", null)`
- Currently safe because all callers pass hardcoded strings (lines 1180–1184: `"expenses"`, `"categories"`, etc.).
- However, the function is `private` and could be refactored later to accept dynamic input.
- *Fix:* Add a whitelist check or use `BackupVerifier.allTableNames().contains(tableName)` guard before interpolation.

---

## Summary

| Issue ID | Severity | Category | Status vs. `response (1).md` |
|----------|----------|----------|------------------------------|
| R1 | — | No destructive migration | ✅ RESOLVED |
| R2A | **MAJOR** | Missing v1–v5 migration path | ❌ STILL PRESENT |
| R2B | MINOR | MIGRATION_96_100 multi-hop gap | ❌ STILL PRESENT |
| R3A | **MAJOR** | No migration tests for v92–v108 | ❌ STILL PRESENT |
| R3B | MINOR | No real-DB snapshot tests | ❌ STILL PRESENT |
| R3C | MINOR | No PRAGMA FK check in tests | ❌ STILL PRESENT |
| R4A | MINOR | No pre-upgrade backup prompt | ❌ STILL PRESENT |
| R5A | MINOR | No legacy v1–v5 DB importer | ❌ STILL PRESENT |
| R6A | MINOR | No fresh-vs-migrated parity test | ❌ STILL PRESENT |
| A1 | **MAJOR** | CHECK constraint risk in MIGRATION_107_108 | ⚠️ NEW |
| A2 | MINOR | SimpleDateFormat thread-safety | ⚠️ NEW |
| A3 | MINOR | Unvalidated table name in rawQuery | ⚠️ NEW |

**Total: 2 RESOLVED (R1 + infrastructure for R4), 7 STILL PRESENT (partial resolutions), 3 NEW issues.**

---

## Recommendations (Priority Order)

1. **CRITICAL:** Add migration tests for MIGRATION_92_93 through MIGRATION_107_108 (ISSUE-R3A).
2. **HIGH:** Either add v1→v6 migrations or implement a legacy importer, or add `fallbackToDestructiveMigrationFrom(1,2,3,4,5)` with explicit user consent (ISSUE-R2A, ISSUE-R5A).
3. **HIGH:** Review MIGRATION_107_108 CHECK constraint healing logic to ensure no rows violate the invariant (ISSUE-A1).
4. **MEDIUM:** Add pre-upgrade backup prompt in UI (ISSUE-R4A).
5. **MEDIUM:** Add fresh-vs-migrated parity test (ISSUE-R6A).
6. **LOW:** Address minor issues (R2B, R3B, R3C, A2, A3).
