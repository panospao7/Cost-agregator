# Pipeline 7 — Backup / Restore: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 7 — Backup / Restore  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 7 — Backup / Restore
Verdict: RED
Summary:
- 2 old issues FIXED, 8 TODO ONLY (fundamental design gaps)
- 2 issues FIXED by universal (NEW-P7-001/002 via U-PR4)
- 12 pipeline-local issues remain (1 P0, 7 P1, 3 P2, 1 P3)
- P0: Legacy .db import lacks journal/maintenance mode — crash corrupts live DB
- Most P1 issues are fundamental design gaps (stale Room, non-global barrier, non-atomic assets)
- These require significant architectural work, not simple patches
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_7_CONSOLIDATED_ISSUES.md`

**Source files:** `DatabaseBackupRepositoryImpl.kt`, `RestoreMaintenanceMode.kt`, `RestoreJournal.kt`, `CostbackupBundle.kt`, `BackupVerifier.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 7 | Adapter Needed | Status |
|---|---|---|---|
| U-PR4 (Barrier/Maintenance) | **Fixes** NEW-P7-001/002 — try/finally exit guarantee | No | ✅ Fixed |
| U-PR5 (Privacy) | Backup/export privacy redaction scope | Yes — adapter for export redaction | ⏳ Blocked |
| Others | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P7-P0-01 | 📝 TODO | None | Add journal + maintenance mode to legacy import |
| P7-P0-02 | ✅ FIXED | None | None |
| P7-P1-01 | 📝 TODO | None | Invalidate/recreate Room after DB swap |
| P7-P1-02 | 📝 TODO | U-PR4 partial | Make barrier truly global |
| P7-P1-03 | 📝 TODO | None | Use SQLite backup API or freeze writes |
| P7-P1-04 | 📝 TODO | None | Atomic asset+DB restore |
| P7-P1-05 | 📝 TODO | None | Semantic verification |
| P7-P1-06 | 📝 TODO | None | Make privacy events non-optional |
| P7-P1-07 | ✅ FIXED | None | None |
| P7-P1-08 | ✅ FIXED | None | `dismissRestartRequired()` now clears UI + exits maintenance mode (unblocks writes) |
| NEW-P7-001 | ✅ FIXED | U-PR4 | None |
| NEW-P7-002 | ✅ FIXED | U-PR4 | None |
| NEW-P7-003 | 🔴 OPEN | None | Atomic state transition |
| NEW-P7-004 | 🔴 OPEN | None | Atomic journal append |
| NEW-P7-005 | 🔴 OPEN | None | Close stream in finally |
| NEW-P7-006 | 🔴 OPEN | None | Quote table name |

---

## 5. New Issues / Regressions

No regressions from universal fixes. U-PR4 correctly added try/finally to maintenance mode operations.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| P7-P0-01 | P0 | Legacy .db import lacks journal/maintenance | Import | P7-PR1 |
| P7-P1-03 | P1 | Backup creation doesn't freeze writes | Backup | P7-PR1 |
| P7-P1-01 | P1 | Stale Room instance after DB swap | Restore | P7-PR2 |
| P7-P1-04 | P1 | Asset restore not atomic with DB | Restore | P7-PR2 |
| P7-P1-08 | P1 | Restore leaves app blocked; UI dismisses | Restore | P7-PR2 |
| P7-P1-02 | P1 | Maintenance mode not global barrier | Architecture | P7-PR3 |
| P7-P1-05 | P1 | No semantic verification | Verification | P7-PR3 |
| P7-P1-06 | P1 | Privacy events optional in verification | Privacy | P7-PR3 |
| NEW-P7-003 | P2 | Non-atomic critical state transition | Atomicity | P7-PR4 |
| NEW-P7-004 | P2 | Journal append race | Atomicity | P7-PR4 |
| NEW-P7-005 | P2 | FileInputStream leak | Resource | P7-PR4 |
| NEW-P7-006 | P3 | Unquoted table name in SQL | Security | P7-PR4 |

---

## 7. PR Organization

### P7-PR1 — Backup & Import Safety

```
PR name: fix(p7): legacy import journal + maintenance mode, backup write freeze
Goal: Fix P0 import crash risk and backup consistency
Issues fixed: P7-P0-01, P7-P1-03
Universal dependencies: U-PR4 (already landed — maintenance mode exit guarantee)
Files likely touched:
  - DatabaseBackupRepositoryImpl.kt
  - RestoreMaintenanceMode.kt
Implementation steps:
  1. P7-P0-01: In importDatabase(), enter maintenance mode before file operations; wrap in try/finally; create RestoreJournal entries for import phases; validate imported DB before swapping
  2. P7-P1-03: In createCostBackup(), enter BACKUP_EXPORTING mode; use SQLite backup API (sqlite3_backup_init) or checkpoint WAL + copy; exit mode in finally
Tests:
  - legacy_import_enters_maintenance_mode
  - legacy_import_crash_does_not_corrupt_live_db
  - backup_creation_produces_consistent_snapshot
  - backup_exits_maintenance_on_failure
Risks: High — touches critical data paths; needs careful testing
Acceptance criteria:
  - Legacy import crash leaves app in recoverable state (not corrupted)
  - Backup snapshot is consistent (no partial writes)
  - Maintenance mode always exited
```

### P7-PR2 — Restore Lifecycle Hardening

```
PR name: fix(p7): Room invalidation after swap, atomic asset restore
Goal: Fix restore correctness issues
Issues fixed: P7-P1-01, P7-P1-04
Universal dependencies: None
Files likely touched:
  - DatabaseBackupRepositoryImpl.kt
  - RestoreMaintenanceMode.kt
  - AppStartupCoordinator.kt
Implementation steps:
   1. P7-P1-01: After DB file swap, invalidate Room instance; either kill process or use Room.close() + reopen; verification must use fresh Room instance
   2. P7-P1-04: Implement two-phase asset restore: (a) extract to temp dir, (b) atomic rename after DB restore succeeds; on failure, clean temp dir
Tests:
   - verification_uses_fresh_room_after_swap
   - asset_restore_failure_does_not_corrupt_db
Risks: High — process lifecycle changes; needs integration testing
Acceptance criteria:
   - Post-restore verification reads from new DB (not cached)
   - Asset restore failure is recoverable
```

### P7-PR3 — Architecture Hardening

```
PR name: fix(p7): global write barrier, semantic verification, privacy events required
Goal: Address architectural gaps in backup/restore
Issues fixed: P7-P1-02, P7-P1-05, P7-P1-06
Universal dependencies: None
Files likely touched:
  - DatabaseWriteBarrier.kt / RestoreMaintenanceMode.kt
  - BackupVerifier.kt
Implementation steps:
  1. P7-P1-02: Add Room callback or DAO interceptor that checks isWritesAllowed() before any INSERT/UPDATE/DELETE; or use compile-time annotation processing
  2. P7-P1-05: Add semantic verification: compare dashboard totals, expense count by category, budget states between source and restored DB
  3. P7-P1-06: Promote privacy_audit_events to Tier 1 required in verification; fail verification if missing
Tests:
  - write_during_maintenance_throws_globally
  - semantic_verification_catches_data_loss
  - missing_privacy_events_fails_verification
Risks: High — global barrier is architectural; needs careful rollout
Acceptance criteria:
  - No DB write succeeds during maintenance mode (regardless of caller)
  - Verification catches semantic differences (not just row counts)
  - Privacy events are required for valid backup
```

### P7-PR4 — Bug Fixes & Cleanup

```
PR name: fix(p7): atomic state transition, journal race, stream leak, SQL quoting
Goal: Fix remaining P2/P3 issues
Issues fixed: NEW-P7-003, NEW-P7-004, NEW-P7-005, NEW-P7-006
Universal dependencies: None
Files likely touched:
  - RestoreMaintenanceMode.kt
  - RestoreJournal.kt
  - CostbackupBundle.kt
  - BackupVerifier.kt
Implementation steps:
  1. NEW-P7-003: Combine two SharedPreferences commits into single atomic apply() call
  2. NEW-P7-004: Use synchronized block or Mutex around journal read-modify-write; or use atomic file append
  3. NEW-P7-005: Wrap FileInputStream in use {} block (Kotlin auto-close)
  4. NEW-P7-006: Quote table name with backticks in SQL: `"SELECT COUNT(*) FROM `$tableName`"`
Tests:
  - critical_state_transition_is_atomic
  - concurrent_journal_appends_dont_lose_events
  - extract_closes_stream_on_exception
  - special_table_names_dont_cause_sql_injection
Risks: Low — targeted fixes
Acceptance criteria:
  - No partial state on crash between commits
  - Journal events never lost under concurrency
  - No resource leaks
  - No SQL injection via table names
```

---

## 8. Detailed Implementation Plan

### P7-PR1 Step-by-Step
1. **Open** `DatabaseBackupRepositoryImpl.kt` — find `importDatabase()`; add `restoreMaintenanceMode.enterRestoring()` at start; wrap in try/finally with exit; add journal entries
2. **Find** `createCostBackup()` — add `restoreMaintenanceMode.enterBackupExporting()` at start; use `database.openHelper.writableDatabase.path` to get DB path; use SQLite backup API or WAL checkpoint + file copy

### P7-PR2 Step-by-Step
1. **Find** post-swap verification code — add `database.close()` before verification; reopen with fresh instance
2. **Find** asset restore — extract to `$cacheDir/restore_temp/`; after DB swap succeeds, rename to final location
3. ~~**P7-P1-08** (✅ FIXED) — `dismissRestartRequired()` now clears UI + exits maintenance mode (unblocks writes)~~

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 7 Adapter/Follow-up |
|---|---|
| U-PR4 (Barrier) | ✅ Already landed — maintenance mode exit guarantee working |
| U-PR5 (Privacy) | Required: Ensure backup/export applies retention/redaction scope per U-PR5 contract |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 7 targeted tests
./gradlew :app:testDebugUnitTest --tests "*Backup*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Restore*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Maintenance*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Journal*" --stacktrace

# Migration tests (if schema changes)
./gradlew :app:connectedDebugAndroidTest --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P7-PR1: Legacy import uses journal+maintenance; backup freezes writes
- [ ] P7-PR2: Room invalidated after swap; assets atomic
- [ ] P7-PR3: Global barrier enforced; semantic verification; privacy events required
- [ ] P7-PR4: Atomic state; no journal race; no stream leak; SQL quoted
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] U-PR5 adapter landed (export redaction)
- [ ] Pipeline 7 status upgraded to GREEN in master tracker
