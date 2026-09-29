# Wave-2 next-step readiness — S1 gates and S2 asset recovery

Date: 2026-09-28. Status: **PARTIAL / NOT IMPLEMENTED for S2**. Closure verdict: **FAIL — not ready for Wave-2 closure**. This packet records source-grounded preparation, not an independent approval of the author's S1 patch.

## 1. Current state and work performed

- Branch `bug-fixes`; HEAD `a48076322e57f3d312f34cf6a71139efc4fcc48b`; index empty. Intake and pre-artifact fingerprint: `971bf77747e998ad1b2ac553a7b7bf73935452be661de5b520e27fa0219ae3c3`.
- 132 tracked dirty plus 81 untracked paths. All 14 paths in the S1 handoff SHA-256 manifest matched. The tree was unchanged throughout this discovery pass.
- Runner quiescent at the pre-write check: no active run, lock or validation process. No builds, tests, guards, scanners or other validation were executed. No subagents were used.
- The latest persisted run inspected is `vr-20260928-114400-31054a39`, LegacyDataConsistencyCheckerTest PASS at fingerprint `d736eca4da0676c108cc987c921fa57d16ad3609accd9e09a93243c440e067d5`. It predates S1 and cannot validate the S1 snapshot. No new S1 execution was found in the run inventory.
- The current S1 handoff still records independent strict/guardian approvals as NOT RUN. No S1 independent approval was supplied. The same author cannot certify independence by performing another self-review.
- Production, tests, configuration, baselines, allowlists, policy, pins and existing records were left unchanged. This new readiness packet is the only intended write in this turn; it changes the subsequent full-tree fingerprint.

Immediate gate: independent review and the human-owned serialized compile/targeted sweep in `workflows/active/wave2-generic-pdf-partiality-20260928-handoff.md`, section 5. The existing one-batch-at-a-time plan does not permit silently stacking the larger S2 state-machine change onto unvalidated S1.

## 2. S2 authority and scope

Original authority: `docs/analyses and debug master/remediation/RP-03-backup-restore.md:35-81`, :94-97 and :107-114. This is the previously recorded RP-03 remainder, not a newly invented Wave-2 finding. Preserve the narrow, execution-evidenced pointer-CAS/snapshot repair described in `workflows/active/wave2-recovery-contract-repair-20260928-handoff.md:37-46`.

Owners: Segment 18 backup/restore, Segment 12 startup, and the narrowly approved restore-internal receipt mutation. Preserve fresh restored-database handles, RestoreInternalWriteScope, maintenance/drain/read/write barriers, F6 post-swap operation-ledger prohibition, and forced restart. No Room schema change is presently indicated. Encryption-envelope redesign and money semantic-checkpoint implementation are not silently folded into this asset batch.

Architecture discrepancy already noted: `docs/architecture/LEGAL_PATHS.md:128-130` still describes a receipt repository draft insert, while the actual generic receipt repository returns an uninserted draft and the coordinator owns the transaction. This packet follows the inspected source without rewriting the architecture map or treating that stale wording as permission for a new writer.

## 3. Source-verified S2 gaps

Production paths below are relative to `app/src/main/java/com/yourname/expensetracker/`. These are existing recovery gaps; none was introduced by S1. No runtime reproduction is claimed.

| ID | Evidence | Required correction |
|---|---|---|
| S2-R1 | `data/backup/CostbackupBundle.kt:49-98` has no per-asset ID/size/hash ledger. Its checksum loop at :521-544 verifies listed extracted files at extraction time, but those proofs are not carried into the later asset task. `data/repository/DatabaseBackupRepositoryImpl.kt:1426-1438` still builds ZIP entries from receipt ID plus original basename, and :1482-1798 does not consume its manifest parameter. | Carry canonical receipt/kind identity, validated entry, expected SHA-256 and exact size from the authenticated bundle into durable tasks. Preserve existing checksum/extraction limits; do not substitute them for copy/replay integrity checks. |
| S2-R2 | `data/backup/RestoreJournal.kt:45-56` has only PENDING/COMPLETED/FAILED. `startup/AppStartupCoordinator.kt:618-627` rejects an already-present final before inspecting whether the same interrupted task created it; primary restore does the same at `DatabaseBackupRepositoryImpl.kt:1678-1684`. | Implement the approved intermediate states and ownership proof. A crash after publish but before journal/DB completion must resume its own valid output without authorizing reuse or overwrite of a foreign file. |
| S2-R3 | Asset fsync failures are ignored at `AppStartupCoordinator.kt:652` and `DatabaseBackupRepositoryImpl.kt:1692`. Copy/rename fallback lacks the required final hash/size proof before pointer mutation (:655-677 and :1695-1713). | Treat file durability and integrity failures as failures; no pointer update or success metric until final output is proven. An existence check is not a hash/size check. |
| S2-R4 | `DatabaseBackupRepositoryImpl.kt:1541-1596` rebuilds the ledger from currently present files and replaces assetTasks. With ledger A+B but only source A remaining, B can disappear rather than become SOURCE_MISSING. Per-task completion/failure journal exceptions are also discarded at :1755 and :1820. | Merge tasks by stable operation/asset identity under one journal writer; retain absent-source tasks with an explicit disposition. Never publish completion from an in-memory update whose required durable transition failed. |
| S2-R5 | `RestoreJournal.writeJournal` is synchronized, but :582-585 falls back to writing the active journal directly if rename fails. `appendEventToFile` has the analogous direct-write fallback at :312-314. Existing tests assert fallback success or file existence, not preservation through a kill during partial replacement. | Prove atomic old-or-new journal publication across a crash, not only in-process exception handling. Retain previous recovery evidence when atomic publication cannot be completed. Do not relax the tests to accept corruption. |
| S2-R6 | `DatabaseBackupRepositoryImpl.kt:1608-1610` and :1629-1631 include assetFile.name in warnings/logs. `RestoreJournal.kt:93-96` emits task src/targetName, and :107-114 leaves them in diagnostic JSON. | Replace raw filename/path diagnostics with controlled reason/receipt/kind/count fields. Keep the private original-pointer snapshot available for CAS but out of exported diagnostics; deleting it is not a privacy fix. |

Additional acceptance issue: startup selects only non-COMPLETED tasks (`AppStartupCoordinator.kt:535-537`); the approved table also requires verification of a completed task's file identity/hash/size on repeated recovery. Cancellation cleanup and missing-source handling must be tested at each real file boundary, not inferred from a PENDING JSON round trip.

## 4. Concrete implementation contract for the next bounded batch

1. **Admission and compatibility first.** Extend bundle/task metadata without altering the encryption envelope. Canonical asset identity is SHA-256 of receipt ID plus a fixed asset-kind token, not a user filename, UUID or timestamp. Distinguish canonical asset identity from operation ownership. Validate source entry containment, IDs, duplicates, size/hash and manifest-to-restored-DB mapping before any pointer write. Explicitly review old bundle/journal behavior: legacy readability is not permission to synthesize missing authorization from the current row or arbitrary local bytes.
2. **One shared per-asset algorithm.** Primary restore and startup must use the same transition rules instead of maintaining divergent copy/CAS loops. Supply the fresh restored database/DAO through the existing restore scope; do not inject the stale app singleton or create a new unrestricted writer.
3. **Durable task transitions.** PENDING -> TEMP_WRITTEN -> FINAL_DURABLE -> DB_UPDATED -> COMPLETED, with explicit FAILED reasons. Journal the complete original-pointer/identity ledger before external work. Stream/count/hash, flush/fsync, publish with proven operation ownership, verify final content, execute existing null-safe pointer CAS, read back, and durably complete. A row already pointing to this task's proven final on replay must not be overwritten or needlessly counted as a new mutation.
4. **Narrow cleanup and error behavior.** Delete only operation-owned candidates. Foreign collisions/newer pointers remain protected. A corrupt/missing image does not justify rollback of an already verified database. Required journal failures keep recovery blocked/resumable rather than becoming a best-effort PASS. Cancellation performs bounded NonCancellable temporary cleanup/checkpointing, preserves resumability and restart-required state, then propagates.
5. **Privacy and publication.** Export controlled diagnostics, not task filenames or private pointers. Replace/strengthen journal publication only with an explicit old-or-new crash contract. Review existing fallback-success tests as a substantive durability contract change, not a mechanical assertion update.

This design needs the required architecture/privacy review before implementation. Any helper extraction that changes mutation ownership must be reflected precisely in the later ownership reconciliation, not covered by a wildcard. Preserve `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07 and FG-23. The four previously identified policy rows are not authorization for arbitrary new owners or broader exceptions.

## 5. Required assertions, not merely test-class names

- `AssetRestoreAtomicityTest.kt:1-238` currently covers journal fields, ASSETS_RESTORING classification and PENDING round trips. It does not run the per-asset file/DB crash matrix. The previous PASS is valid for those assertions, not the unimplemented matrix.
- Add shared-engine tests with real temporary files, a real journal/fresh journal owner, and real Room pointer predicates where appropriate. Observe filesystem bytes, hash/size, row contents, task state, affected-row count and diagnostics.
- Cover: crash after temp fsync; publish before journal advancement; final journal before CAS; CAS before journal completion; completed-task replay; source disappearance; invalid/missing temp/final; exact-size/hash mismatch; concurrent newer/null pointer; missing row; foreign target; duplicate identity; file sync/rename failure; journal publication failure; cancellation at each boundary; repeated startup; task/event merge without loss.
- Startup and repository tests must exercise the shared production implementation, not a different helper or a mocked prebuilt successful outcome. Preserve the existing original-pointer, missing-snapshot, zero-row and read-back mismatch assertions.
- Extend CostbackupBundleLimitsTest and RestoreAssetSnapshotJournalTest for strict additive metadata parsing, malformed types, missing proof and legacy behavior. Preserve encrypted round trips and existing extraction limits.
- Device kill/relaunch evidence remains required. An exact BackupRestoreIntegrityE2ETest file was not found in the inspected androidTest filename inventory; no device coverage is credited from unit class names.

## 6. Validation and gate sequence

Validation in this turn: **NOT RUN**. Independent S1 review: **not supplied / still pending in handoff**. S2 strict/guardian review: **NOT RUN**. No new test methods were authored in this read-only preparation.

1. Obtain the independent S1 review without subagent delegation by this author. Use the existing S1 packet and its exact 14-path SHA-256 manifest.
2. Once the global runner is quiescent and the tree is frozen, the human runs the exact compile + ten-filter stop-on-fail PowerShell block in `workflows/active/wave2-generic-pdf-partiality-20260928-handoff.md`, section 5. This newly added document changes the full-tree fingerprint but not any S1 source hash; record the new fingerprint rather than pretending it remains 971bf777....
3. Diagnose any failure from preserved run evidence. Only after S1 gates are satisfied, implement/review this bounded S2 contract. Keep missing compatibility decisions explicit; no silent format downgrade.
4. Then issue the S2-specific serialized human validation packet against its actual final file/test manifest. No runnable S2 closure command is claimed ready before implementation exists. Full guard closure remains sequenced with the precise policy/advisory work.
5. RP-03 semantic equivalence (P7-P1-05), remaining Wave-2 finding/rider evidence, independent accumulated-batch approvals and device gates remain open. Completing the asset state machine alone will not prove all of RP-03 or Wave 2 complete. Commit remains a separate human decision.

## 7. Inspected source-byte anchors

| Path | SHA-256 |
|---|---|
| `app/src/main/java/com/yourname/expensetracker/data/backup/CostbackupBundle.kt` | `2b0b8a11c1c4ecd228ce664b6ec4a07eb690f0863460647a09fef480e1b0a1c1` |
| `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt` | `0b9cac3238d2f5526438783f8d685ddb82db39ab41c7fefb6473a7f5ceffafcb` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | `402cbe59ff8301d75fdc6878bd596dd76e47d5cb78a1a79f641b4629abd59383` |
| `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt` | `1cabe9e7aeb041dc5a064d0eeaf292ce10566aab321e76d1f14840ecd57ae2cc` |
| `app/src/test/java/com/yourname/expensetracker/data/backup/AssetRestoreAtomicityTest.kt` | `4214c53c0f199f64263c78e2443c6d234f2ae94378f2a56827f0cf2a1b75306d` |
| `app/src/test/java/com/yourname/expensetracker/data/backup/RestoreJournalDurabilityTest.kt` | `eed9ef89e61893afd91b5feef2095ae3225470c282f9389691945381764680e6` |

Only this new readiness document was written. Existing implementation, test, campaign and handoff files were preserved. The post-write read-back and full-tree comparison must be completed before reporting this packet as saved.

