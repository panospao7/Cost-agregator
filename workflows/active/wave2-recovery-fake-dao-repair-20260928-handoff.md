# Wave 2 recovery batch: test-fake compile repair

Date: 2026-09-28. Status: IMPLEMENTED_UNVERIFIED. Wave-2 closure remains blocked.

## Root cause and existing evidence

The prior recovery-contract batch added ScannedReceiptDao.updateImagePathIfUnchanged but omitted the direct implementer FakeScannedReceiptDao in LegacyDataConsistencyCheckerTest. This was an interface-consumer omission, not an observed bank-recovery behavioral failure.

- vr-20260928-103727-ff02f317: production compile PASS, exit 0.
- vr-20260928-104519-1dc0b7b6: targeted run FAIL, exit 1, at :app:compileDebugUnitTestKotlin. stderr.log:1-2 identifies the missing abstract override.
- Both result.json files and completion markers were inspected. Both runs have equal start/end fingerprints: 0a68452ac3ebfc2a94902903ec8d541ba7c0d119d90631a960d96b387a56b58f.
- The selected test methods did not execute. The remaining eight targeted filters were not attempted. Those results do not validate this repaired snapshot.

## Minimal repair

File: app/src/test/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyCheckerTest.kt:35.

Added exactly one override under the existing unused-write-stub section:

    override suspend fun updateImagePathIfUnchanged(receiptId: Long, expectedImagePath: String?, imagePath: String): Int = 0

The checker only reads receipts through getAll/getById. Zero affected rows matches this fake's other no-op write methods; returning one without a mutation would falsely report success. This is not a substitute for the real Room CAS tests, which remain unchanged. No production interface default, test assertion relaxation or removal was introduced.

All ScannedReceiptDao references under app/src were inspected for other direct implementers; this is the only handwritten implementation found. Source inspection and the one-line diff were read back, but compilation/tests have NOT been rerun.

## Snapshot and preservation

- Branch: bug-fixes; HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b; staged changes: none.
- Repaired code snapshot before this new handoff: 2026-09-28T14:17:07.9759408+03:00.
- Pre-handoff fingerprint: 4ab3d4db4b4b7fd9dbb85c383f80b2c9b2a32c12070053c439bd372862e1582d.
- Repaired file SHA-256: 283ad01eaeb340fa3834328b88c405a397f2bef9f85639890235801f38a6c225.
- All 198 paths already dirty/untracked at this repair's intake remained byte-identical, including the original fifteen batch files, the existing handoff, and validator journal updates. The previously clean fake test is the only additional code change.
- No production, policy, baseline, allowlist, RawQuery pin, guard, existing campaign record or existing handoff was modified. No subagents, validation execution, staging or commit.
- Runner quiescence was checked before edits and before this new handoff. Adding this report changes the fingerprint again; use the post-handoff fingerprint in the final response for the next validation run.

## Human validation handback

Restart the complete compile plus original nine-filter chain from the top. Use the validator-confirmed Windows PowerShell invocation without -Raw; the global serialization lock still applies. Freeze the entire tree during each run, stop on every nonzero/unknown/stale result, and wait on an existing RUNNING run rather than replacing it.

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/vrun.ps1 -Worktree . -Profile compile -MaxTotalMinutes 130

Only after terminal PASS on the same frozen tree, run each filter separately with:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter FILTER -MaxTotalMinutes 130

Original serialized filter order (quote the filter argument):
1. *BankSyncStartupRecoveryTest
2. *BankRecoveryDaoContractTest
3. *RestoreImagePathDaoContractTest
4. *RestoreAssetSnapshotJournalTest
5. *AppStartupCoordinatorRecoveryTest
6. *DatabaseBackupRepositoryImplTest
7. *RestoreJournalDurabilityTest
8. *AssetRestoreAtomicityTest
9. *WorkerLeaseRegistryTest

An additional *LegacyDataConsistencyCheckerTest run is recommended for the directly touched fixture; it does not replace or reduce the original nine filters.

Static guards remain gated behind the exact ownership-policy reconciliation described in workflows/active/wave2-recovery-contract-repair-20260928-handoff.md section 4. Advisory evidence, independent reviews, device gates and the other documented closure blockers remain open. Preserve FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03, FG-06, FG-07 and FG-23.

