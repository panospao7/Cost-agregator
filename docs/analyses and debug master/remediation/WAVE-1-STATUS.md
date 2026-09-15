# Wave-1 Remediation Status

> **WAVE 1 COMPLETE (2026-09-15).** All three lanes merged into the
> integration line with verification. Living status note; decisions:
> `RP-00-DECISIONS-RECORDED.md`. Lane branches: `rp-01-wip`, `rp-02-wip`,
> `rp-03-wip` (one worktree each); integration line:
> `atomicity-pr21-enforcement-final`. Nothing pushed.

## Landed

| Lane | Batch | Commit | Contents |
|---|---|---|---|
| rp-01 | 1 (U-001) | `ee6de6b9` | CE propagation through create/update conversion + 5 event writes; `bestEffortEvent` helper; contract entries + 4 passing runtime cancellation tests (5th `@Ignore`d — see RP-21 feed) |
| rp-02 | 1 (U-004) | `402ee2e1` | Alias-proof class-level write-barrier + recurring guards; 24-entry temporary exemption ratchet (owner/issue/expiry enforced); `SourceTextSanitizer` shared helper |
| rp-03 | 3a (P7-003/005) | `0835b5c7` | SAF `.costbackup` destination; storage permissions removed; fsync'd safety copy; envelope pinned by 11 tests |
| rp-01 | 2 (U-002/U-003/U-006) | `83652778` | Guard rewritten (balanced-paren scan, executable-only evidence, sanitized text, FQN-safe sibling check); both allowlist expiries enforced; FHS2 ×4 + coordinator ×3 + startup ×2 CE fixes; stale entry + coordinator exclusion removed; 12 owner-tagged temporary exemptions expiring 2026-12-31 |
| rp-03 | 3b (restore state machine) | `e48aed43` | P7-002 resume (idempotent asset-ledger resume, journal consumed, restart reachable-to-completion); P7-004 `.pre_restore` recovery source; P7-006 staging cleanup on all 9 failure paths; P7-008 journal-honoring exits + dedicated CE catch; P7-009 rename-before-DB-update; deferred fsync sites |
| rp-02 | 2 (writer ownership) | `6f415329` | All 8 writers barrier-adopted; ratchet 28→20 (8 RP-02 exemptions removed); UNASSIGNED triage table (RP-03/10/11/12/14/15/16/19/20); withTransaction mock defect independently corroborated for RP-21 |

## Wave-1 merge endgame (integration line)

| Commit | Contents |
|---|---|
| `44cb16fe` | merge rp-01-wip. Coordinator conflict: kept gr-14f's restructured structure, re-ported all 10 U-001 CE-safety sites onto it |
| `c57d37f1` | merge rp-02-wip. gr-14f had ALREADY mediated RetentionModule / NotificationIntakeCoordinator / RecurringOccurrenceMaterializer via `runWrite(DatabaseAccessOperation)` — HEAD versions kept (supersede the lane's check-only work); genuine gap ported: barrier on `RoomRecurringLifecycleEventWriter.writeCritical` (gr-14f removed the caller-less `writeDiagnostic`) |
| `367213d7` | merge rp-03-wip (clean auto-merge) |
| `16093145` | fix E2E constructor arg duplicated by auto-merge |
| `c0f2434b` | align writer tests to the gr-14f mediated contract (real-barrier harness; intake: `capture`→typed `Dropped`, `captureForRetry`→silent skip); AppStartupCoordinator raw runCatching → `CancellationSafe.runCatchingCancellable` |
| `8c11ecb3` | acceptance registry regenerated 20→18: `SourceLinkWriterImpl.linkTarget` + `RoomRecurringLifecycleEventWriter.writeCritical` became PROVEN via barrier adoption (proven_helper 344→346); GR-15 suite 15/15 |

## Merged-tree verification

- Battery (16 test families): **206 PASSED**; 8 failures all proven
  pre-existing on pristine lane HEADs (5 DatabaseBackupRepositoryImplTest
  via rp-03 stash-baseline, 3 BackupRestoreViewModelTest via 3a-era logs).
- Cancellation guard suite green on the merged tree (20/20), now directly
  enforcing the coordinator (exclusion removed in the same merge).
- Mediation gate: applied **18**, mismatches **0**; board 346+14+1 proven
  + 18 owner_accepted, 0 unproven, 0 counterexamples.

## Carried debts (post-wave-1)

1. **GR-engine debt (holds mediation gate at exit 2, fail-closed):**
   `DatabaseBackupRepositoryImpl.restoreReceiptAssets` ScannedReceiptDao.update
   emits `GR13_NO_D4_OBSERVATION` after the RP-03 P7-009 reorder inside
   RestoreInternalWriteScope; scan diagnostic `DB_DAO_SCOPE_UNRESOLVED` is new.
   NOT owner-acceptable (unsupported ≠ unproven). Owner: parked GR-engine
   typing batch. Evidence: `wave1-merged-board2.json`, registry header note.
2. **DbGuardPolicyFixtureTest drift (pre-existing since the gr-14f merge):**
   fixture expects old-format `daos: [privacyAuditDao]` pins; gr-14f rewrote
   the policy YAML to the new `daoAccessor:` format. 5 tests red on the
   integration line before wave 1. Owner: GR program close-out.
3. **RP-21 feed:** withTransaction hang family (root cause confirmed twice —
   relaxed-mock `withTransaction` stubbing cannot intercept the static Room
   extension; candidate fix `mockkStatic("androidx.room.RoomDatabaseKt")`;
   logs `rp01-baseline-pristine3.log`, `rp01-isolation.log`,
   `rp02-batch2-*`); DirectEvent expired allowlist (8 entries, 2026-08-15);
   cancellation raw-runCatching allowlist expires **2026-10-01**; rp-01/rp-02
   temporary exemptions expire 2026-10-31 / 2026-12-31.

## Wave 2 (next)

RP-05→06→07→08→09 money chain ∥ RP-10 ∥ RP-12→13 (RP-13 carries the one
Room-schema bump of the wave). Cut lanes from the current integration tip.
