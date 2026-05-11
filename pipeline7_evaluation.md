# Pipeline 7 Unified Evaluation — Backup / Restore

**Date:** 2026-05-11
**HEAD:** `10d5ee24` (post pipeline 6)
**Combines:** Debug report analysis + pre-existing evaluation + fresh code verification

## Verdict: Mostly stabilized — 4 gaps remain for closure

The old `RestoreMaintenanceMode` architecture has been significantly hardened:
- `checkRestoreJournal()` is wired in `AppStartupCoordinator` with **fail-closed** crash recovery
- `DatabaseWriteBarrier` exists with 226+ call sites
- Backup enters `BACKUP_EXPORTING` and WAL-checkpoints before snapshot
- `privacy_audit_events` is Tier 1 exact (not optional)
- Restart-required state persists after successful restore

### Remaining critical gaps

1. **P0-01:** Legacy `.db` import has no `BuildConfig.DEBUG` guard — reachable in release
2. **P1-02/P1-03:** `isWritesAllowed()` returns `true` in `BACKUP_EXPORTING` — writes during snapshot
3. **P1-07:** `scheduleAllWorkers()` is hardcoded (7 workers) — new workers won't be resumed
4. **P1-04:** Asset restore has no journal states — crash mid-asset leaves orphan state

## Detailed Status

| ID | Sev | Title | Pre-existing verdict | Scout verified | Combined verdict |
|----|-----|-------|---------------------|----------------|-----------------|
| P0-01 | P0 | Legacy .db import no journal | unclear/stale | IN_CODE (journal+mode exist) | ⚠ PARTIAL — has journal but no release guard |
| P0-02 | P0 | Startup crash recovery | NOT fixed | ✅ IN_CODE (fail-closed, L145-158) | ✅ FIXED |
| P1-01 | P1 | Stale Room after swap | PARTIAL | IN_CODE (acknowledged, restart mitigates) | ⚠ PARTIAL — mitigated, not solved |
| P1-02 | P1 | Global write barrier | PARTIAL | IN_CODE (226+ call sites) | ⚠ PARTIAL — no freeze in BACKUP_EXPORTING |
| P1-03 | P1 | Backup snapshot consistency | PARTIAL | IN_CODE (BACKUP_EXPORTING + WAL) | ⚠ PARTIAL — writes allowed during snapshot |
| P1-04 | P1 | Asset restore atomicity | OPEN | NOT_IMPLEMENTED | ❌ OPEN — no journal states |
| P1-05 | P1 | Semantic equivalence verification | OPEN | NOT_IMPLEMENTED | ❌ OPEN — tests only |
| P1-06 | P1 | Privacy audit events optional | ✅ FIXED | IN_CODE (TIER_1_EXACT) | ✅ FIXED |
| P1-07 | P1 | Worker pause/resume spec-driven | PARTIAL | NOT_IMPLEMENTED (hardcoded) | ❌ OPEN — no WorkerRegistry |
| P1-08 | P1 | Restart-required UI dismiss | OPEN | IN_CODE (writes blocked, UI soft) | ⚠ PARTIAL — DB blocked, UI dismissible |

## Implementation Priority

| PR | Priority | Items | Effort |
|----|----------|-------|--------|
| PR1 | P0 | Legacy import DEBUG guard | Tiny |
| PR2 | P1 | Block writes during BACKUP_SNAPSHOTTING | Small |
| PR3 | P1 | WorkerRegistry for scheduleAllWorkers | Medium |
| PR4 | P1 | Asset restore journal states | Medium |
