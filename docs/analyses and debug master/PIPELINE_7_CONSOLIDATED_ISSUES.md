# Pipeline 7 — Backup/Restore: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 2 FIXED, 0 PARTIAL, 8 TODO, 6 NEW open issues  
> **Total open items:** 14

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P7-P0-01 | P0 | Legacy `.db` import lacks journal and maintenance mode | 📝 TODO ONLY | 📝 **TODO ONLY** | `importDatabase()` has no `RestoreJournal`/`RestoreMaintenanceMode`; crash corrupts live DB |
| P7-P0-02 | P0 | Startup crash recovery resumes writes after failed recovery | ✅ FIXED | ✅ **FIXED** | Persistent `CRITICAL_RECOVERY_REQUIRED` state; startup auto-reset exempts it; writes stay blocked across restarts. Test: `AppStartupCoordinatorRecoveryTest` |
| P7-P1-01 | P1 | Restore uses stale injected Room instance after DB file swap | 📝 TODO ONLY | 📝 **TODO ONLY** | Uses same injected Room for verification after file swap |
| P7-P1-02 | P1 | Maintenance mode not a global DB write barrier | 📝 TODO ONLY | 📝 **TODO ONLY** | `isWritesAllowed()` enforcement is caller-by-caller |
| P7-P1-03 | P1 | Backup creation does not freeze writes or use SQLite backup API | 📝 TODO ONLY | 📝 **TODO ONLY** | `createCostBackup()` does not enter backup mode; concurrent writes cause inconsistent snapshot |
| P7-P1-04 | P1 | Receipt asset restore not atomic with DB restore | 📝 TODO ONLY | 📝 **TODO ONLY** | Crash mid-asset-restore can rollback valid DB or leave orphan files |
| P7-P1-05 | P1 | Restore success does not prove dashboard/analytics equivalence | 📝 TODO ONLY | 📝 **TODO ONLY** | Verification checks table counts only, not semantic output equivalence |
| P7-P1-06 | P1 | Privacy audit events optional in backup verification | 📝 TODO ONLY | 📝 **TODO ONLY** | `privacy_audit_events` classified Tier 3 optional; can be dropped silently |
| P7-P1-07 | P1 | Worker pause/resume not fully spec-driven | ✅ FIXED | ✅ **FIXED** | `pauseAllWorkers()` and `scheduleAllWorkers()` both use DEFAULTS |
| P7-P1-08 | P1 | Successful restore leaves app blocked; UI can dismiss warning | 📝 TODO ONLY | 📝 **TODO ONLY** | `dismissRestartRequired()` only clears UI; writes still blocked |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P7-001 | P0 | Encrypted export never exits maintenance mode on success | BackupRestoreRepository.kt | ✅ FIXED (U-PR4) |
| NEW-P7-002 | P1 | Privacy gate denial / WAL failure leak maintenance mode | BackupRestoreRepository.kt | ✅ FIXED (U-PR4) |
| NEW-P7-003 | P2 | `enterCriticalRecoveryRequired` non-atomic two-commit | RestoreMaintenanceMode.kt | 🔴 OPEN |
| NEW-P7-004 | P2 | RestoreJournal `appendEvent` read-modify-write race | RestoreJournal.kt | 🔴 OPEN |
| NEW-P7-005 | P2 | `CostbackupBundle.extract()` leaks FileInputStream | CostbackupBundle.kt | 🔴 OPEN |
| NEW-P7-006 | P3 | `countRowsFromSourceTable` uses unquoted table name | BackupVerifier.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 2 |
| 📝 TODO ONLY (old issues) | 8 |
| 🔴 OPEN (new issues) | 6 |
| **Total open work** | **14** |

---

## Priority Order for Remaining Work

### P0 (critical)
1. **NEW-P7-001** — Encrypted export never exits maintenance mode on success (app stuck in maintenance after successful export)
2. **P7-P0-01** — Legacy `.db` import lacks journal and maintenance mode (crash corrupts live DB)

### P1 (must fix)
3. **NEW-P7-002** — Privacy gate denial / WAL failure leak maintenance mode
4. **P7-P1-01** — Stale Room instance after DB file swap
5. **P7-P1-02** — Maintenance mode not a global write barrier
6. **P7-P1-03** — Backup creation does not freeze writes or use SQLite backup API
7. **P7-P1-04** — Receipt asset restore not atomic with DB restore
8. **P7-P1-05** — Restore success does not prove semantic equivalence
9. **P7-P1-06** — Privacy audit events optional in backup verification
10. **P7-P1-08** — Successful restore leaves app blocked; UI can dismiss warning

### P2 (should fix)
11. **NEW-P7-003** — `enterCriticalRecoveryRequired` non-atomic two-commit
12. **NEW-P7-004** — RestoreJournal `appendEvent` read-modify-write race
13. **NEW-P7-005** — `CostbackupBundle.extract()` leaks FileInputStream

### P3 (cleanup)
14. **NEW-P7-006** — `countRowsFromSourceTable` uses unquoted table name
