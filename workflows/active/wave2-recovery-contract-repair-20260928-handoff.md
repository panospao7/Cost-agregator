# Wave 2 recovery-contract repair: implementation handoff

Date: 2026-09-28. **Status: IMPLEMENTED_UNVERIFIED for the bounded repair; VERDICT: FAIL for Wave-2 closure.**

This implements the functional prerequisites identified in workflows/active/wave2-mutation-advisory-adjudication-20260928.md. It does not approve blanket ownership-policy promotion, change advisory acceptance, or replace the complete seven-cluster independent review. One agent performed the work; no subagents were used. No build, test, guard, scanner probe or other validation profile was executed. No commit or staging occurred.

## 1. Exact scope and preservation

- Branch: bug-fixes. HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b. Staged changes: none.
- Intake: 2026-09-28T12:20:03.8165177+03:00; 114 tracked dirty paths and 71 untracked paths, 185 total.
- Intake fingerprint: e146b52a27b5604e3cf42cbc4302cf5b852fd5dad982443110ae1cebccd58f36.
- Final code snapshot before this new handoff: 2026-09-28T12:48:56.0117805+03:00; 123 tracked dirty paths and 74 untracked paths, 197 total.
- Reviewed code fingerprint: 6bfb98c9176a43a1b2268872149c02bc5b03b716778b1f12cb4ce49861c5a879. This report itself subsequently changes the dirty-tree fingerprint; do not associate the older validation run with this snapshot.
- Fifteen source/test paths changed in this task: nine production files and six test files, including three newly authored test classes. Three of these paths already carried prior work: ScannedReceiptDao.kt, DatabaseBackupRepositoryImpl.kt and DatabaseBackupRepositoryImplTest.kt. Changes there were localized; their earlier Wave-2/validator edits were not replaced.
- All other 182 intake paths were compared by SHA-256 and remained byte-identical. No prior report, campaign record, policy, baseline, allowlist, RawQuery pin, scanner or guard was edited.
- Quiescence was checked before mutation phases and immediately before this report: no active lock, STARTING/RUNNING result or validation process was observed. A failed, truncated inventory read was retried as read-only NDJSON; it did not trigger a write.

## 2. Finding-by-finding changes

W2-MUT identifiers below are the local identifiers from the adjudication, not new campaign IDs. Source paths are relative to app/src/main/java/com/yourname/expensetracker.

### W2-MUT-01: existing bank operation-run recovery

- Original authority: docs/analyses and debug master/remediation/RP-17-bank-sync.md:77-83; sanctioned policy promotion was explicitly deferred at :36-41. Preserve the established operation-ledger writer boundary rather than adding an event-writer exception.
- domain/diagnostics/OperationRunRecorder.kt:64-111 adds a narrow StaleBankOperationRunRecovery interface implemented by the existing RoomOperationRunRecorder. It recovers existing IDs, retains bank filtering and the controlled STALE_RUNNING_ABORTED reason, and sums actual finalizeIfRunning affected-row results. It does not create replacement operation runs.
- di/DiagnosticsModule.kt binds that required interface to the existing Room writer. The general OperationRunRecorder interface and CompositeOperationRunRecorder binding remain unchanged.
- domain/bank/BankSyncStartupRecovery.kt:50-88 acquires the shared maintenance-drain lease, checks it before suspended reads and writes, and closes it in finally. Sealed admission produces no DB access. Cancellation and database failures propagate. WorkerModule already binds the registry and drain controller to the same WorkerLeaseRegistryImpl.
- Disposition: IMPLEMENTED_UNVERIFIED for writer/admission repair. The exact ownership-policy grant and executed guard evidence remain pending. Source relocation alone is not a claim that event_writers passed.

### W2-MUT-02: stale bank statement-run recovery

- data/database/dao/BankStatementImportRunDao.kt:43-53 now requires the row still be RUNNING and startedAt be strictly before the supplied cutoff. markStaleFailed returns Int.
- BankSyncStartupRecovery adds that actual affected-row result rather than incrementing a counter unconditionally. Missing, deleted, terminal, boundary-age and already-recovered rows cannot count as successful stale transitions.
- Real Room tests cover completion winning after stale selection, repeated recovery, cutoff equality, all terminal statuses and deleted/missing rows. The unrelated live finalize method is unchanged; this batch does not claim a new global first-terminal-wins contract for that method.
- Disposition: IMPLEMENTED_UNVERIFIED. This is a conditional-query change, not a Room entity/schema/version change. Exact policy reconciliation remains pending.

### W2-MUT-03: restore-internal receipt pointer repair

- Original authority: docs/analyses and debug master/remediation/RP-03-backup-restore.md:9-13, :57-81 and :95-96. Preserve the fresh restored-database handle and restore-internal scope; do not route post-swap repair through ordinary receipt creation.
- data/database/dao/ScannedReceiptDao.kt:26-36 adds updateImagePathIfUnchanged: an ID plus null-safe expected-old-path predicate, returning affected rows, and updating only imagePath. Existing whole-row update remains available to untouched callers.
- data/backup/RestoreJournal.kt:45-53, :98-112 and :177-180 persists a private original-pointer snapshot. A captured null is distinguishable from an old journal without a snapshot. Wrong JSON types fail parsing; the private path is removed from diagnostic/export JSON.
- data/repository/DatabaseBackupRepositoryImpl.kt:1541-1596 captures and durably writes the snapshot before asset copy/DB mutation, and retains an existing task snapshot rather than rebasing it. Snapshot read failures become controlled per-asset failures without escaping into verified-database rollback; cancellation still propagates. Snapshot journal durability failures are not ignored.
- The primary restore producer at :1709 onward and startup/AppStartupCoordinator.kt:630-691 both use path-only CAS and confirm the resulting database pointer before completion. A zero-row update or mismatched read-back cannot produce a COMPLETED task. Existing extension, duplicate and collision rejection paths remain in place.
- RestoreInternalWriteScope KDoc now acknowledges both approved users. This does not broaden its permitted maintenance modes.
- Compatibility decision: a legacy pending task without the original pointer is readable but cannot authorize a new database-pointer write. It fails with EXPECTED_IMAGE_PATH_MISSING; the verified DB is retained. It is not silently treated as a captured null or rebased onto the current row.
- Disposition: IMPLEMENTED_UNVERIFIED for the path-only/snapshot/read-back subcontracts; PARTIAL for the complete RP-03 crash/retry contract. This batch does NOT implement or prove the full file-ownership/hash/size/intermediate-state recovery model. In particular, an already-present final file is still treated as a collision rather than proven to belong to the interrupted task. Existing rename/fsync behavior and crash windows still require the dedicated recovery work and tests before broad RP-03 closure.

## 3. Tests authored or strengthened — NOT RUN

| Test class | Meaningful assertions added in this task |
|---|---|
| BankSyncStartupRecoveryTest | Six new methods: zero affected rows, sealed admission, maintenance during each suspended read, database failure and cancellation; uses the real Room ledger writer and real lease registry with DAO seams. Existing four tests retain their behavioral assertions. |
| BankRecoveryDaoContractTest (new) | Five real-Room methods: stale predicate/repeat, age boundaries/terminal states, competing completion, deletion/missing rows, operation-ledger terminal race. |
| RestoreImagePathDaoContractTest (new) | Six real-Room methods: captured null, non-null/repeat, changed pointer, null/non-null mismatch, preservation of concurrent privacy/matching columns, deletion/missing rows. |
| RestoreAssetSnapshotJournalTest (new) | Four methods: absent versus captured-null snapshot, privacy-safe diagnostics, malformed types rejected, fresh journal-owner disk read-back. |
| AppStartupCoordinatorRecoveryTest | Six new startup-path methods: captured-null success, legacy task refusal, newer pointer, zero affected rows, mismatched read-back and missing row. Existing deterministic-file test now has explicit stateful DAO wiring and verifies CAS plus read-back; file/journal assertions are retained. |
| DatabaseBackupRepositoryImplTest | Eight new methods: zero affected rows, read-back mismatch, original snapshot not rebased, legacy task refusal, pointer cancellation, durable snapshot required, snapshot read failure and snapshot-read cancellation. Existing successful restore fixture now models pointer state; collision/duplicate/extension tests additionally assert no CAS. |

These are 35 newly authored test methods, not 35 observed passing tests. No exception-instance assertions were introduced for ordinary coroutine-recovered exceptions. The Room tests exercise actual SQL rather than mocking the predicates. Compilation, Hilt generation, Room query generation and runtime execution still need human validation.

## 4. Ownership-policy follow-up — not silently promoted

config/guards/db_ownership_policy.yml is byte-identical to intake: SHA-256 9d91711177bea287c44ad5f7f28d4857805f6ac0f3f66ee022a890c50e4917e2. After review, any authorized reconciliation must name the real callable, accessor and operation, with a truthful owner/reason:

| Reviewed source identity | Required reconciliation, subject to approval |
|---|---|
| RoomOperationRunRecorder.recoverStaleBankRuns(Long, WorkerLease), accessor runDao, OperationRunDao.finalizeIfRunning | New narrowly owned existing-ledger recovery permission; no direct bank event-writer exception. |
| BankSyncStartupRecovery.recoverStaleRuns(Long), accessor bankStatementImportRunDao, BankStatementImportRunDao.markStaleFailed | Exact stale-statement bookkeeping permission reflecting shared maintenance admission and conditional affected-row handling. |
| AppStartupCoordinator.resumeSingleAssetTask(JournalEntry, AssetRestoreTask, java.io.File?, java.io.File, ScannedReceiptDao), accessor dao, ScannedReceiptDao.updateImagePathIfUnchanged | Exact restore-internal pointer repair only; not a general startup writer grant. |
| DatabaseBackupRepositoryImpl.restoreReceiptAssets(java.io.File, BackupManifest, AppDatabase, JournalEntry?, RestoreDiagnosticsSink?), accessor dao | Replace the now-stale operation:update entry at policy :982-1005 with the reviewed conditional pointer operation, and correct the obsolete exclusivity rationale. Do not retain a surplus broad permission. |

Use canonical fully qualified parameter identities from source when editing the policy. Do not guess resolver output, claim unsupported discovery is trusted, or add wildcard/helper exceptions to fit the analyzer. No scanner execution was performed for these new identities. The unchanged policy deliberately does not yet describe the repaired source completely; a full-suite green result is NOT promised before reviewed, explicitly authorized reconciliation.

Applicable controls: FINAL_CI_GUARD_ACCEPTANCE_GATE.md — FG-03, FG-06, FG-07 and FG-23. A failing or unknown result remains a failure; do not relax tests, reduce recursive selection, add baselines, grow allowlists or suppress diagnostics.

## 5. Advisory-count drift remains an evidence gate

The prior adjudication remains authoritative for the inspected artifacts: the last report was already trusted=true, with three actual mutation findings, zero blocking diagnostics and 21 advisories. The scorecard pins 20. This is not a new request to change advisory trust semantics.

The hash-matching historical 20-identity artifact is still unavailable: build/guard-debug/gr09/current-findings.json, expected SHA-256 3d452c7f2d7a993ebb187d39b8c319d559f42abe7e2685b1d16934c7a695c588. Recover that artifact and compare identities, or obtain an explicit new acceptance decision with a frozen, reviewed current inventory. No 20-to-21 repin, fabricated historical inventory or fabricated freshness stamp was made.

The pre-existing scripts/test_wave2_db_file_discovery.py fixture repair is preserved unchanged (SHA-256 2029c2ffad9eced5aad1ec6adea2a7e3eb65c26fced5fd5befd7da8ba8f9da5b). Its execution still needs the full recursive guard_tests suite segment; it is not validated by this source repair.

## 6. Human validation — serialized, stop on failure

Validation in this task: NOT RUN. Scoped source/consumer/DI/test-assertion inspection was completed, but it is not independent strict or guardian approval. Obtain the required review for this risky batch; do not edit any fingerprinted files during a run. Start only when the global runner is quiescent. The following uses the human vrun wrapper, which delegates Start/Wait to scripts/validation-runner.ps1 and blocks to terminal status.

Run compile first. If it fails, is stale, is unknown, times out or remains running, stop and preserve its run ID and artifacts:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile compile -Raw -MaxTotalMinutes 130
```

After terminal PASS on the unchanged snapshot, run these filters one at a time. This PowerShell loop stops on every nonzero wrapper result; never launch it alongside another validation owner:

```powershell
$filters = @(
    '*BankSyncStartupRecoveryTest',
    '*BankRecoveryDaoContractTest',
    '*RestoreImagePathDaoContractTest',
    '*RestoreAssetSnapshotJournalTest',
    '*AppStartupCoordinatorRecoveryTest',
    '*DatabaseBackupRepositoryImplTest',
    '*RestoreJournalDurabilityTest',
    '*AssetRestoreAtomicityTest',
    '*WorkerLeaseRegistryTest'
)
foreach ($filter in $filters) {
    pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter $filter -Raw -MaxTotalMinutes 130
    if ($LASTEXITCODE -ne 0) { throw "Validation stopped at $filter; inspect the existing run before continuing." }
}
```

If the wrapper times out while a child remains RUNNING, wait/poll that SAME run through validation-runner; do not launch a replacement. Record terminal result.json, command, run ID, completion marker and start/end fingerprints for each filter.

After the policy/advisory decisions and any approved changes have been reviewed, take a new frozen snapshot and run the complete guard profile. This command is supplied, not executed; without those reconciliations it is diagnostic evidence, not a closure attempt:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -Raw -MaxTotalMinutes 130
```

guard_tests is a suite segment, not a standalone registered GuardId. Preserve recursive collection and all existing tests. Do not describe authored tests, a compile PASS, unchanged failures or optional skips as proof of the full fix.

## 7. Remaining closure blockers

1. Compile and runtime execution of this repaired snapshot; every new/changed contract above is unverified.
2. Exact reviewed/authorized ownership-policy reconciliation, plus evidence-based advisory-count adjudication.
3. The wider RP-03 file-integrity/crash/retry contract described above; the pointer CAS repair alone does not close it.
4. Independent strict and required guardian reviews of the complete accumulated Wave-2 batch. This author inspection cannot approve itself.
5. Instrumented/device gates for PDF/image and MerchantKeyBackfill coverage, and previously recorded functional/evidence gaps (including partial-result propagation, WorkerRunLogger evidence and incomplete linked CL-09 execution evidence) until individually resolved against their original requirements.
6. Other recorded guard debt still requires its own verified disposition; no blanket assertion of unchanged pre-existing violations is made for this unexecuted tree.
7. A separate human commit decision after acceptance. Nothing was committed, staged, reset, stashed, switched or discarded.

The historical run vr-20260928-075653-8d98ca3b belongs to fingerprint 0320a5326bfeb55c20d90b2b9edcc49f1de99d7295b55237fe4bb9a373e933b4, not this repaired tree. Its 18/25 guard result and 54 failed / 4663 passed / 28 skipped pytest result must not be reused as execution evidence for these changes.

## 8. Files touched and reviewed SHA-256 manifest

These hashes identify the fifteen code/test files before adding this handoff. The final response records the post-handoff fingerprint. Existing campaign records and prior handoffs are preserved.

| File | SHA-256 |
|---|---|
| app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt | c34a2eab37a993277efb7a84ec26e6d415908035389a8219f37f9d96cd062b6a |
| app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt | 6e55716b76b23cd461b585981b99f20b82fc380f4318035daf2caef27d77d095 |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/BankStatementImportRunDao.kt | 8628738be1f8659d9792be77162ac28eafa93fa0bcae4ee4b24f633a3d94fa5f |
| app/src/main/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecovery.kt | 822b6d163e52fe6effb87ba4476d21a2bb0f515fb5e31af9e67cdcf163e1add1 |
| app/src/test/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecoveryTest.kt | e2a56059edc6df36f611248e0a0728ec5f787af6f193423ef1f48ab54a465f88 |
| app/src/test/java/com/yourname/expensetracker/data/database/dao/BankRecoveryDaoContractTest.kt | d1ecb09560d2f2f37c466a4b320c61a86250e528d68cf86dec26d0b625002b0e |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt | c3192a91fca0818414a0657e663c6e6af5a148623b41a042801bac663e7b51eb |
| app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt | 0b9cac3238d2f5526438783f8d685ddb82db39ab41c7fefb6473a7f5ceffafcb |
| app/src/main/java/com/yourname/expensetracker/data/backup/RestoreInternalWriteScope.kt | 7761e3f43593aeeaa9b09f2667dda7f9048f53bf866bbbe196356ee4a7cf7e36 |
| app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt | 1cabe9e7aeb041dc5a064d0eeaf292ce10566aab321e76d1f14840ecd57ae2cc |
| app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt | 402cbe59ff8301d75fdc6878bd596dd76e47d5cb78a1a79f641b4629abd59383 |
| app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt | f72272bef013ccf919295665f63fe4d4912a1d1194d8bd9560494c58b4de5c58 |
| app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt | 048d728ebfee9b4bdd81ea5f8f8f7286e1f073ac37549037435ff44de4216dac |
| app/src/test/java/com/yourname/expensetracker/data/database/dao/RestoreImagePathDaoContractTest.kt | fbec78858af24cbd03d45c35908b022ab76ed9731cb38648ed60adca9ee2435e |
| app/src/test/java/com/yourname/expensetracker/data/backup/RestoreAssetSnapshotJournalTest.kt | f0cbf582f3d4931f415e4e481c4f64c83740a781ae4026b05b9ed2b73cd9d55f |

New documentation file: workflows/active/wave2-recovery-contract-repair-20260928-handoff.md.

