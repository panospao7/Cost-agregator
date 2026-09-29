# Wave 2 closure-progress assessment — September 28, 2026

VERDICT: FAIL for Wave-2 closure. The bounded recovery repair has passing compile/class-level execution evidence; the complete campaign does not yet have closure evidence.

This is a read-only source/evidence assessment plus this new report. No subagents, production/test/configuration changes, policy promotion, validation execution, staging, commits or worktree operations were performed. This is not a replacement for the 34-finding review, and an author-side recheck cannot approve its own accumulated implementation as an independent strict/guardian review.

## 1. Exact reviewed snapshot and preservation

- Branch: bug-fixes. HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b. Staged changes: none.
- Captured: 2026-09-28T15:05:45.1329367+03:00. Runner-compatible fingerprint: c257f9d34b6ef393444b4dc7bd6d42822e374f73c1a36668f4eeb09ef2393504.
- Dirty paths before this report: 124 tracked + 76 untracked = 200. This is a preservation inventory, NOT a claim that 200 files are new Wave-2 implementation. It includes prior reports, validator records and unrelated work.
- The validated recovery snapshot is d736eca4da0676c108cc987c921fa57d16ad3609accd9e09a93243c440e067d5. Comparison against the preserved post-handoff inventory found only docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md changed. Source, tests and configuration remain byte-identical to the handed-off code. The different current fingerprint must not be relabeled as the tested fingerprint.
- Intake and pre-report inventories matched exactly. Pre-write quiescence: 2026-09-28T15:08:00.9608764+03:00; lock absent, no active run or validation process observed.
- This new report changes the next dirty-tree fingerprint. Its creation is not a new validation run. Appendix A freezes every pre-report dirty path and its byte hash.
- CL-27 merge 0ea0316a1f7d5fac4587857f3645340e01592ede and CL-05 merge 03f197d162d81e5a8821e2d82b116d71b04b9e99 are ancestors of HEAD, as is campaign audit pin 37601232b9778170c57a656a245b199ab6d7d965. Those two clusters are already committed; the remaining implementation/repairs are dirty. Do not describe all seven clusters as uncommitted.

## 2. Recovery validation independently checked

For each of the eleven runs below, the stored result.json is terminal PASS with exit 0, the completion marker exists, git_revision matches HEAD, and start/end fingerprints equal the validated fingerprint above. Each targeted stdout log includes actual :app:testDebugUnitTest execution and BUILD SUCCESSFUL; the selected test task is not UP-TO-DATE, NO-SOURCE or SKIPPED. Production compilation was up-to-date on the compile rerun; test-source compilation occurred in the first targeted run.

| Run ID | Profile / filter | Seconds | Result |
|---|---|---:|---|
| vr-20260928-112651-10c80c7d | compile | 19 | PASS |
| vr-20260928-112716-9749fc34 | *BankSyncStartupRecoveryTest | 361 | PASS |
| vr-20260928-113324-0d8fba75 | *BankRecoveryDaoContractTest | 72 | PASS |
| vr-20260928-113442-6b2de1e5 | *RestoreImagePathDaoContractTest | 65 | PASS |
| vr-20260928-113551-a77687f8 | *RestoreAssetSnapshotJournalTest | 59 | PASS |
| vr-20260928-113657-884d1b11 | *AppStartupCoordinatorRecoveryTest | 88 | PASS |
| vr-20260928-113831-45d82002 | *DatabaseBackupRepositoryImplTest | 120 | PASS |
| vr-20260928-114038-6d2261d5 | *RestoreJournalDurabilityTest | 71 | PASS |
| vr-20260928-114154-e81fd8f5 | *AssetRestoreAtomicityTest | 58 | PASS |
| vr-20260928-114257-66926702 | *WorkerLeaseRegistryTest | 57 | PASS |
| vr-20260928-114400-31054a39 | *LegacyDataConsistencyCheckerTest | 51 | PASS |

Evidence location: build/validation-runs/<run-id>/{result.json,stdout.log,stderr.log,complete.marker}; sweep log build/validation-runs/wave2-recovery-sweep2-20260928.log. The retained JUnit XML is the last class only: LegacyDataConsistencyCheckerTest, 5 tests, zero failures/errors/skips. The other classes have filter-level execution evidence, not a separately retained per-method XML archive from this assessment. The 35 authored methods must not be misrepresented as 35 individually re-counted XML results.

Corrections to the pasted summary: there are two new real-Room contract classes (BankRecoveryDaoContractTest and RestoreImagePathDaoContractTest), plus one new file-backed journal class (RestoreAssetSnapshotJournalTest). BankSyncStartupRecoveryTest uses the real ledger writer/lease registry with mocked DAO seams. These distinctions do not invalidate the passing runs.

Bounded disposition: W2-MUT-01 writer/admission recovery, W2-MUT-02 stale statement CAS/counting, and W2-MUT-03 original-pointer snapshot/path-only CAS/read-back have source plus passing class-level evidence. Policy acceptance and the complete RP-03 file/crash contract are separate gates. The missing FakeScannedReceiptDao override is fixed and its containing class executed; no further fake repair is indicated by this evidence.

## 3. Confirmed functional work still open

### 3.1 Original Wave-2 finding CA-P-03-003 / W2-R3 remains PARTIAL

Original authority: docs/analyses and debug master/campaign CA-2026-09-21/cell-P-03-audit.md:67-75, especially the camera/PDF route at :72. The CL-15 author specification names the bank-statement propagation at wave2/CL-15-spec.md:9; that narrower implementation statement is not an approved removal of the original generic receipt route.

Current source (prefix app/src/main/java/com/yourname/expensetracker/):
- domain/receipt/ReceiptOcrService.kt:81-97 exposes failed-page/capped-page partiality.
- data/repository/ReceiptRepository.kt:162-169 and :300-306 carry page counts but not the complete partiality contract.
- domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:338-359 obtains the result without consuming its page counts; :449-459 derives normal PARSED/OCR_COMPLETED status from receipt content; :585-622 persists that status and the saved event without OCR partiality.

Consequence: surviving pages of a generic PDF receipt can still be presented/persisted as ordinary successful processing. Passing OcrResultPartialTest or the statement completion helper does not prove this omitted route. Required next bounded repair: propagate partiality through the existing receipt lifecycle and actual consumers, preserve cancellation and successful surviving data, and add a regression exercising partial OCR through persisted lifecycle state and the displayed result. Do not invent transaction failures for pages that never produced transactions or silently add a schema migration.

### 3.2 Full RP-03 asset recovery remains PARTIAL, despite the passing class names

Original authority: docs/analyses and debug master/remediation/RP-03-backup-restore.md:35-81 and :107-109. The recovery handoff explicitly limits its own claim at workflows/active/wave2-recovery-contract-repair-20260928-handoff.md:37-46.

Current source:
- data/backup/RestoreJournal.kt:45-56 still has PENDING/COMPLETED/FAILED only, not the required durable TEMP_WRITTEN/FINAL_DURABLE/DB_UPDATED states or expected file hash/size fields.
- startup/AppStartupCoordinator.kt:618-627 rejects any existing final file as a collision. A crash after rename but before pointer/completion can therefore fail on replay instead of recognizing and continuing its own valid output.
- The same startup path :647-677 performs copy/rename then CAS, but ignores the non-cancellation failure result from fd.sync() at :652. The primary repository asset path similarly ignores fd.sync() failure at data/repository/DatabaseBackupRepositoryImpl.kt:1692.

The newly verified CAS protects the receipt pointer and other columns; it does not establish file identity, content integrity, durability or every crash boundary. Do not weaken collision rejection to make retry tests pass. A dedicated implementation needs ownership proof, hash/size validation, durable transitions and crash/relaunch tests while preserving fresh-DB/internal-scope/restart guarantees. Full RP-03 is a separately approved remediation scope already carried as PARTIAL in the closure handoff; this report does not silently expand the original seven clusters to every RP-03 work package. If the human separates remaining RP-03 work from Wave-2 sign-off, record that exact scope decision and retain the RP-03 release blocker. Never call RP-03 complete on the bounded CAS sweep.

## 4. Guard/policy gates still open; do not repeat old diagnoses

### Exact four-entry ownership reconciliation

config/guards/db_ownership_policy.yml remains SHA-256 9d91711177bea287c44ad5f7f28d4857805f6ac0f3f66ee022a890c50e4917e2. No new policy approval or edit was inferred from the request for a progress assessment. The four reviewed follow-ups remain those in recovery handoff section 4:
1. RoomOperationRunRecorder.recoverStaleBankRuns: runDao / finalizeIfRunning, narrowly owned existing-ledger recovery.
2. BankSyncStartupRecovery.recoverStaleRuns: bankStatementImportRunDao / markStaleFailed, with conditional affected-row handling and shared maintenance admission.
3. AppStartupCoordinator.resumeSingleAssetTask: dao / updateImagePathIfUnchanged, restore-internal pointer repair only.
4. DatabaseBackupRepositoryImpl.restoreReceiptAssets: replace the obsolete whole-row update permission at policy :982-1005 with the actual conditional pointer operation; remove the surplus broad grant and correct the old exclusivity rationale.

Before editing, confirm canonical callable/accessor identities against source and obtain explicit approval for these exact policy changes. This is not approval for a wildcard, new baseline, RawQuery repin, unrelated exception or advisory-count change. Guard self-protection and no-weakening remain mandatory: FINAL_CI_GUARD_ACCEPTANCE_GATE.md, FG-03, FG-06, FG-07, FG-23.

### Advisory-count drift is evidence/acceptance work, not a new trust-semantics bug

The preserved active-db-gate.findings.json (SHA-256 3ab1d9d274317f9391f1736cb92a593480f172b9a9b7ad3803e3569bb7960656) reports trusted=true, findingCount=3 and advisoryDiagnosticCount=21. scripts/ci/verify_known_good_state.py:177 pins 20. The historical 20-identity artifact build/guard-debug/gr09/current-findings.json is still absent; its expected SHA-256 is 3d452c7f2d7a993ebb187d39b8c319d559f42abe7e2685b1d16934c7a695c588.

Recover the hash-matching artifact and compare diagnostic identities, or obtain an explicit new acceptance based on a frozen, reviewed current identity inventory. Do not infer which file is the extra one or silently change 20 to 21. The optional freshness row is documented as optional in the scorecard; its missing stamp is not evidence that these three mutation findings are absent, nor permission to fabricate a stamp.

### Fresh Python/static execution is still required

Latest actual static run: vr-20260928-075653-8d98ca3b, FAIL/exit 1, 18/25 PASS, seven blocking violations, zero infrastructure errors. It tested fingerprint 0320a5326bfeb55c20d90b2b9edcc49f1de99d7295b55237fe4bb9a373e933b4, before the recovery repair. The failing legs were time_boundaries, known_good_state, cancellation, db_access, event_writers, raw_money_aggregates and guard_tests. Do not relabel this old result as a current-tree pass, or assume every remaining finding is unchanged pre-existing debt.

The file-discovery fixture repair (scripts/test_wave2_db_file_discovery.py SHA-256 2029c2ffad9eced5aad1ec6adea2a7e3eb65c26fced5fd5befd7da8ba8f9da5b) remains without a post-fix recursive Python execution result. Kotlin PASS records do not validate Python. guard_tests is a full-suite segment, not a registered standalone GuardId. No recursive selection reduction or skipped/unknown required result may substitute for execution.

## 5. Older execution gaps the latest sweep does not close

Rechecked the main-checkout result ledger, sorting by the actual started_at/run_id fields. No later unfiltered testDebugUnitTest run was found to supply blanket coverage. The older 52/52 claim is not reliable: the published worklist has 54 distinct filters; the prior source/evidence adjudication found 47 PASS, one FAIL and six missing batch results. See workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md:26-44. The new recovery sweep is a different, bounded worklist.

| Required evidence | Latest located main-checkout evidence | Current disposition |
|---|---|---|
| WorkerRunLoggerTest | vr-20260927-071601-23e3f70b, FAIL/exit 1; classifyDiagnostic_timeout_returns_TIMEOUT fails | Not a PASS. Diagnose or supply trustworthy current evidence; do not weaken timeout assertions. Environmental attribution alone is not a passing execution record. |
| ExpenseRepositoryMerchantKeyBackfillTest | No filtered run found | Missing current-batch execution evidence |
| MerchantKeyBackfillWorkerTest | vr-20260919-083416-ff478ddd, PASS before batch | Missing current-batch execution evidence; separate androidTest also needs device coverage |
| WarrantyReminderDeliveryDaoTest | vr-20260919-090931-9a4ab048, PASS before batch | Missing current-batch execution evidence |
| WarrantyExpirationWorkerTest | vr-20260919-074632-0162bb3c, PASS before batch | Missing current-batch execution evidence |
| ReceiptMatchingViewModelTest | No filtered run found | Missing current-batch execution evidence |
| ReceiptLinkServiceColumnScopeTest | No filtered run found | Missing current-batch execution evidence |

Historical money/currency and other cluster results retain their original tested snapshots. The next full-scope reviewer must update the existing 34-finding matrix and A1-A8 dispositions with implementation/consumer and meaningful assertion evidence, not infer acceptance from file counts or class names. The old FAIL review exists; what remains missing is a passing independent review of the final accumulated snapshot, not the existence of any review at all.

## 6. Recommended closure order and human-only commands

1. Repair the unequivocal original generic-PDF propagation gap in one bounded batch, with production-path assertions. Plan the remaining RP-03 crash/file work separately and preserve its explicit PARTIAL/release-gate status until implemented or formally scoped.
2. Obtain the exact four-entry ownership authorization; reconcile reality without broadening permissions. Resolve the advisory inventory/acceptance decision separately, not as a hidden scorecard repin.
3. Obtain independent strict review and applicable architecture/privacy/money/Room guardians on the accumulated diff and final requirements matrix. Do not spawn subagents without user authorization; a separate human-led review/session can supply independence. This author-side assessment does not supply that approval.
4. Freeze the tree. Human validation owner runs the outstanding targeted evidence, changed-path integration tests, full static suite, and required device gates serially. Any real failure or untrusted/unknown required result remains blocking; existing debt requires actual fixes or a specifically authorized disposition, never automatic dismissal as pre-existing.
5. Reconcile every original finding and rider to exact final evidence; publish the final snapshot/manifest and review decisions. Only then seek the separate commit decision. No commit, push or staging is authorized by this report.

Do not rerun the eleven unchanged recovery filters merely because this assessment exists. If functional code changes, select the impacted tests and compile the changed snapshot. All following commands are recommendations for the human, NOT executed here. Start only with the global runner quiescent, finish each run to terminal status, stop on failure, and do not edit fingerprinted files while any run is active.

```powershell
# After a source-changing repair and required review:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/vrun.ps1 -Worktree . -Profile compile -MaxTotalMinutes 130
```

For the specifically missing/failed unit evidence, after any required diagnosis/review:

```powershell
$filters = @(
    '*WorkerRunLoggerTest',
    '*ExpenseRepositoryMerchantKeyBackfillTest',
    '*MerchantKeyBackfillWorkerTest',
    '*WarrantyReminderDeliveryDaoTest',
    '*WarrantyExpirationWorkerTest',
    '*ReceiptMatchingViewModelTest',
    '*ReceiptLinkServiceColumnScopeTest'
)
foreach ($filter in $filters) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter $filter -MaxTotalMinutes 130
    if ($LASTEXITCODE -ne 0) { throw "Validation halted at $filter; preserve the run and diagnose before continuing." }
}
```

After the policy decision/reconciliation and review, the full recursive static profile:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
```

These use the established Windows PowerShell wrapper without -Raw, matching the successful human sweep. A RUNNING result is polled by its existing run ID, never started again. Required PDF/image/merchant-backfill instrumented coverage still needs the device setup and exact registered runner profile; no unverified device command is invented here. Preserve per-run JUnit evidence when possible rather than relying on an overwritten shared test-results directory.

## 7. Bottom line

The recovery batch made genuine, now-executed progress. It is not evidence that all original Wave-2 findings are fixed. At least the generic PDF partiality omission remains a concrete in-scope functional defect; the broader restore crash/file contract remains explicitly partial. Policy/advisory acceptance, fresh Python execution, older targeted gaps, final independent approvals and device evidence are still open. No percentage or clean-closure claim is justified by counting green filters alone.

## Appendix A — pre-report dirty-path byte manifest

This appendix includes unrelated pre-existing work for preservation and reproducibility, not ownership attribution. HEAD plus these path hashes identify the reviewed dirty snapshot. This report is intentionally absent from its own pre-write manifest.

| Path | Bytes | SHA-256 |
|---|---:|---|
| .codex/agents/explorer-lite.toml | 1676 | 04645298e64b0ec47241bf29c66e94c9e4c09d2360d402c2d2253b9ce20bdf9b |
| .codex/agents/orchestrator.toml | 13776 | ca156e28f1ca3c02559af0f62035131af2671afdd2b4f289ba41b5166200401c |
| .github/workflows/ci.yml | 18844 | 4289990a1fd3d0d83ac35161bbb4b1286bab65eff66a682207ce5d9a1c63041c |
| app/src/androidTest/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt | 4773 | 87eeb2da61a737c89d49e182a9ad057bd6772ff10f54b56fba0ed1b3c3866faa |
| app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt | 12626 | 677eea069aac9deae39f9ec7518559700eb1d4c109026f869378dfbeca98fec5 |
| app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt | 1688 | de196d23e96940c45a33426af0e9bf8d20be26c958e5e7804afdad14fd27ae10 |
| app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt | 1721 | 9d457b66b2cf6e3f6137268467668def3bb28fc15da2a989ee45a2f69195de28 |
| app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt | 1713 | 7bcf5c4b2d7000da90ae85ed539bca43584d8ad4a85062006e792b2fd947952e |
| app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt | 1584 | d8f87c13eca0e1e939eb10d24d6155da771a190800b615d259e60c632085e925 |
| app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt | 1721 | 79c0d2840488811f2b64e9bfc323b7a76ba70b5c3b6595e82d9fbf67f16c3501 |
| app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt | 28220 | 574ee78413ab341f5217094221eb9228f94f18bfb0c6f54b30545acd04ab4af1 |
| app/src/main/java/com/yourname/expensetracker/data/backup/RestoreInternalWriteScope.kt | 1141 | 7761e3f43593aeeaa9b09f2667dda7f9048f53bf866bbbe196356ee4a7cf7e36 |
| app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt | 41272 | 0b9cac3238d2f5526438783f8d685ddb82db39ab41c7fefb6473a7f5ceffafcb |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/BankStatementImportRunDao.kt | 2316 | 8628738be1f8659d9792be77162ac28eafa93fa0bcae4ee4b24f633a3d94fa5f |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt | 114500 | efb05f5a2ddd53d5eabcaad96cdcc566f1d6024d604364939cb6fbbc603d264e |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupSettlementDao.kt | 801 | 10df4f2f9f8776b34e4f34aef8d40c776c763aa3d6dc706d054ad0a6f881c0ca |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt | 4741 | 86920ef4d61ee61865f2529471ee9cd1d7a2e7229e796374ba5eee4c597535dc |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt | 13985 | c3192a91fca0818414a0657e663c6e6af5a148623b41a042801bac663e7b51eb |
| app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDao.kt | 7334 | e47859334156928a7544295d7201e34d10023684ab89d31171692e01b39dde67 |
| app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt | 7251 | 38cadb28cef425ffca84396131e9bab51940ff8dfe3c2dbd9e1e6793010c3206 |
| app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt | 166936 | 402cbe59ff8301d75fdc6878bd596dd76e47d5cb78a1a79f641b4629abd59383 |
| app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt | 50960 | 061cf92c1e52a6457cb1e6ffee8c701fd9833a71915a42e479aa972f895e9a8f |
| app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt | 2887 | f6dd17806a51762a4442b7bbde49c17e78e27c9fc9341f0986a26dd16b0a95bf |
| app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt | 15402 | 348f00a9a6daf96500e22488eb86aa0642cf28a01753282b90bafc99e52cf2f3 |
| app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt | 10580 | c500b754350ce30f6c3ca83b6e7bdde2454d2ae2c552f6cf306042f8cbb1422f |
| app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt | 4387 | 6e55716b76b23cd461b585981b99f20b82fc380f4318035daf2caef27d77d095 |
| app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt | 3135 | 80f2888ce0caa0eb3ef6bb69e3adfa62d37353c10d3c1d29c2e97281422cffda |
| app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt | 6045 | c0a49edc0954059e1419f9705cce6cb4152a1013c011fc0bfc0f7839ff3b4de7 |
| app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseStatsUnavailableException.kt | 324 | 5ea763794c7c4f883a756485a2692d74dc166506e7a5b088153415518b4a47f6 |
| app/src/main/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecovery.kt | 4280 | 822b6d163e52fe6effb87ba4476d21a2bb0f515fb5e31af9e67cdcf163e1add1 |
| app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt | 12318 | f3f4d84e945cb69509c69201bb5488797d95b2d039ce4cc04881e09cec6ba26b |
| app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt | 16203 | c34a2eab37a993277efb7a84ec26e6d415908035389a8219f37f9d96cd062b6a |
| app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt | 40560 | b0159d0157637be68aca727246eb96dfd3855746947c9e42713b77f206958794 |
| app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt | 2912 | fa0c06528f5ceed3beb25406be644944907a5fa594a9af5937514a94c2ef2668 |
| app/src/main/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicy.kt | 2066 | 3786ffabc99ad2917df2c3cfa7558662161e673f13a70355748aed989435d076 |
| app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt | 22242 | bb66d61e5a0debb2aa48e01daa7731ba30066ef3d404cc10654bf823deda155f |
| app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt | 3597 | 69857ac1381f35f5b5332982055b08772396b2b4d6e6a46d08fba00f4d62f03e |
| app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedException.kt | 994 | bbd647b32e7c0910c9314a3cfa562b45d964ba6d480e5103675bfff31a02cf92 |
| app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt | 63146 | 868a8844cd591262b7892099f79b01c71448549d6704078073b732f0708a37e0 |
| app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt | 12145 | 0891e516c824359ab8433b0319d88038f3a3f58fb50e65e18549b670410fe5fd |
| app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt | 40407 | 4fbbcd0105bea427987fcb155dcf608d117b4e8f0eac3ec2f8f8a0cf8b96b1ea |
| app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt | 31554 | 1751ea6a1c3cb245060275e696ffd5018f496704ac04361e081353fa30e440db |
| app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt | 72584 | c595722741b9dccafd56711a1fa2fdfc812c76fb4e410a59574efa27f9738908 |
| app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt | 20932 | 633ae470cd9c8583e2c643652003f0645ce3b622726f19bb192a9bad6d091827 |
| app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt | 5176 | 668af7e5db8d808d55a0b8defadeaeb216c1e05ac16c50e0c1f1c4633c39d75f |
| app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt | 16833 | 0fc22d75cb03d2003c9ee1e40fdb9a1b1fc9709ec15d47442bb4c77926db4331 |
| app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt | 20070 | b35dad56a65aa7ce93a176be951eec06b521f6f201999bd2e76e2af84b9429f8 |
| app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt | 12327 | 74901ad4fa2d53b480c04fdfcaaf4c3dea6af816db1d72852e0c2db191349636 |
| app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt | 43330 | 1cabe9e7aeb041dc5a064d0eeaf292ce10566aab321e76d1f14840ecd57ae2cc |
| app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt | 18615 | 667191bbe391822219e81caf3919b2b73b71ada954f36e32010b8dfbfcc7355d |
| app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt | 15232 | a544a2a887559e929e06a1b0db5544f905473493503f6584489c114e1ea8d154 |
| app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt | 17481 | 0827b20e5ba180ba1dbc9d20479c8db6ea623a297243052141b4b418f657a085 |
| app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt | 19645 | 46b5da5d1fce829ed01ccf773eeded32643bc7ac60cebc06671ebb8fdfa48f21 |
| app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt | 86352 | a6ba4d04d24a8de7b510115565a14f4fad9262f8637096b57d4eccda14172f4d |
| app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt | 25237 | e1d3854970d890d5dd04a59a033c0fe03555809e6362fd71078185e379967153 |
| app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt | 17676 | 60c4e9e0b1ac7025e735b0929ff4c6e4cbea711ea9a3be13fca1d095012d835c |
| app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt | 61047 | ee23129fc281cd35053de27a393f6de2c0f6a0fb09d3b4f221a91ea2b982192e |
| app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt | 17076 | 66dfde120a93658c00013085287489d6b57009b39077d8f46d5859154bf981ac |
| app/src/main/res/values/strings.xml | 176851 | 92d631eb6d3c441617606a81e350a734ef0b4b02026627ab7725dfa09ff1f27f |
| app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProof.kt | 6796 | e1c92676d6abacd5950e181b3b4c64a308b315c95961d64b0f1d46ad11607f57 |
| app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProofTest.kt | 4614 | 018e7825717b0a0427101776671f5884dfe6aa6a3df41f7eb9d81c31e2f2a9ae |
| app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt | 5592 | 6fb1189b2b224c4c40613c969a16dc2404730a70cda87350de0185990ddaa682 |
| app/src/test/java/com/yourname/expensetracker/architecture/WorkerSourceMask.kt | 2999 | 62c416748b1c4a585301040b6aa86c7df71cdcdb3169061283fbf1d5250f112f |
| app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudProviderTransportPayloadTest.kt | 13536 | 7648dd83f2987c77d443886199e3a71eb4da80d86c1751e022d2c5cad7d34489 |
| app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt | 25514 | ddd92179c56fa76d794a3abb5d412b1d391e68ba9fe10379eef9780ddab93344 |
| app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryPreparedPayloadTest.kt | 8828 | 6bcb9443add66d5c5f76624951e7ea2948b05078d9534b82788e45fac3ddcc55 |
| app/src/test/java/com/yourname/expensetracker/data/ai/provider/HybridRouterIntegrationTest.kt | 9570 | 472ab479b09db130f6dd980978b88c31880844b63f2bc7d1d5b8217a46945db2 |
| app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierRequiredSemanticQueryTest.kt | 4618 | 4c221c253010b5d94342e5bb6030cf47950cd484dcf7012393b6e7c7704db5a5 |
| app/src/test/java/com/yourname/expensetracker/data/backup/RestoreAssetSnapshotJournalTest.kt | 4155 | f0cbf582f3d4931f415e4e481c4f64c83740a781ae4026b05b9ed2b73cd9d55f |
| app/src/test/java/com/yourname/expensetracker/data/database/dao/BankRecoveryDaoContractTest.kt | 4401 | d1ecb09560d2f2f37c466a4b320c61a86250e528d68cf86dec26d0b625002b0e |
| app/src/test/java/com/yourname/expensetracker/data/database/dao/RestoreImagePathDaoContractTest.kt | 4357 | fbec78858af24cbd03d45c35908b022ab76ed9731cb38648ed60adca9ee2435e |
| app/src/test/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDaoTest.kt | 12203 | fea4db43d1ae602b79d8063eb8eb67575d4251075e5c3c529c80ea26277ee3e6 |
| app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt | 63142 | fd48bf4b5a64eaeaa15aed9026fa2d1b1b09436287575f5ca996777a88466cbf |
| app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt | 9318 | aaefe92879843d012f654834c18bc595181d55a23bff9f37cb97064305c4b9ad |
| app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt | 120536 | f72272bef013ccf919295665f63fe4d4912a1d1194d8bd9560494c58b4de5c58 |
| app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryMerchantKeyBackfillTest.kt | 6666 | 48b88063a1ed0b169d00d3404ddf6fd4dcb5c9e005ef608d6821976cee94cedf |
| app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt | 27437 | 18cf20bd1c4810ea44c13c1949490197c12b36a980bb1b41b034e1763c15708a |
| app/src/test/java/com/yourname/expensetracker/data/repository/SharedExpenseSettlementReadTest.kt | 2055 | e970ae8df3ecbc02ed3dbd59cb2950322646784bb3c9e30bd8e7740105ce57d7 |
| app/src/test/java/com/yourname/expensetracker/domain/ai/HybridRouterTest.kt | 5132 | 24bd95c9c73007945e9755fd25c3cc5f182408de7b8e6c6db6c5afd276659468 |
| app/src/test/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecoveryTest.kt | 11445 | e2a56059edc6df36f611248e0a0728ec5f787af6f193423ef1f48ab54a465f88 |
| app/src/test/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyCheckerTest.kt | 24607 | 283ad01eaeb340fa3834328b88c405a397f2bef9f85639890235801f38a6c225 |
| app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt | 16573 | bced6298d3817071f1f017ec8c186723f789ed24dea0d8e0afedcfb309d3e76d |
| app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt | 37583 | 0852af0cbc9977759720f0f4544143fc83ef25c4dd19919f43542f058c8f7e60 |
| app/src/test/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicyTest.kt | 2275 | a561d25956e952ca9685c243222f9c73fc4be19abf7ffbcc955d17ed9ffdeac6 |
| app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerSettlementTest.kt | 3987 | d35ac5d791ba9f6b9c8634872c6dcd427cb5a5aeb0a00508e00f493ab4792592 |
| app/src/test/java/com/yourname/expensetracker/domain/privacy/CloudProviderPreparedPayloadTest.kt | 5849 | c05b3680731c9d0f0bd781c53a46cc12f463c47c5fc285ac36f8c52f02a06140 |
| app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedExceptionTest.kt | 1609 | e974145fa9ed8024c7543eb057b1bb8d41f9cfe80241ea913fb83793dab0b119 |
| app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementCompletionStatusTest.kt | 1856 | 2c960ed0475ade974e587c5e0113dd81c9d0fccd95df3cf98ccbf54794211e36 |
| app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt | 17324 | 8b942ea217d30355685b9d80da70954f709bf5bf67867c1453f4ddfa6f61a24b |
| app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrResultPartialTest.kt | 1112 | 2f648954b8f66f7f78e0d2696be25511b40be0ca74ee3123d9528c24e25c9086 |
| app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinatorTest.kt | 44136 | c4d8a6788f134babf7cf492d721a0c5de69022c4e8b1c1a3068aa8f8c6c20255 |
| app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt | 19189 | 0ee9524945f6ea0b4873c8207d3570e5c5781c1bc09381343439577bd9a59208 |
| app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt | 29728 | 84fc68f519cff22b330ef2b2560b2c882fd7de9e24ed81e6359d14c61f1a280b |
| app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryTest.kt | 16184 | e54bf2b971cc7ec03b3eebc47645b32034966f1cbfe1ec5be76e11536b29b1a3 |
| app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt | 27314 | f5ba4227c4b0d4199601410a9d45b7cfbc75776d2565ccaa44bf2207f21f84a2 |
| app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt | 48708 | 3e0991afd01d7a31fb2523e3e351c3b018dc141009e515db1c1d99c91a822a0b |
| app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt | 34257 | 1d6bd170f8f0fd34d113aae8e51cd7e9cbfdb3a5c0fb3327ec666b4aa1cd763b |
| app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt | 38239 | cf94f172c839f653869977d66b15c3f7a2478fc3a03a51f0e1292bc3bfcdde37 |
| app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt | 19352 | bb78094c7efd3985c71b7343eccee493c3df66223f1de73d145802367d2daffe |
| app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt | 77427 | 048d728ebfee9b4bdd81ea5f8f8f7286e1f073ac37549037435ff44de4216dac |
| app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt | 15403 | 9ae3fd99ecfcc8f2044136c1ec548a538643010a0301063c8efb3ed10c57284d |
| app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelDatabaseStatsTest.kt | 7837 | d23d06f07f82258231ad5a94c13319ffe417eeb66663144178283ab113a07315 |
| app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt | 34043 | 0dfe535da453a3e4498e791c8ade6a3329eaedaefa7bf155ea9d821704023c2c |
| app/src/test/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt | 10529 | 04ce37e2c5afcd81d0268d849b80da98869b1607bfd665245ea1079d95708342 |
| app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelPrivacyDenialTest.kt | 8699 | 9ba57cabd64a0594c794dff9228371864fa466cd649edac0d570673041d12276 |
| app/src/test/java/com/yourname/expensetracker/util/CsvImportRfc4180Test.kt | 11512 | f9c74f75b6c4288cc7e9a5deb629ea3f206bce2e728d752e6ec5876dac404394 |
| config/guards/db_ownership_policy.signatures.accounting.json | 403312 | d93da596d674ff5cfa44af687041dbd9736bb202c82ebcb3af7c86380a1b915b |
| config/guards/db_ownership_policy.signatures.candidate.yml | 313973 | d057d4fab03d31a13f030239e2958fb5aaa8939f68b179d95b73b75b41671017 |
| config/guards/db_ownership_policy.yml | 332994 | 9d91711177bea287c44ad5f7f28d4857805f6ac0f3f66ee022a890c50e4917e2 |
| config/guards/db_structural_exceptions.yml | 26915 | c9d5ef6ed50c0a1448ca1cdc46e29a8202be14f11f46b3ff940661a5e758b15a |
| config/guards/db_structural_exceptions_expected_methods.yml | 28772 | 367b0ec6b3fbdfbb8e281152b6b60cf69e053d6ba0d4dbcca4fdbcd78247f8a4 |
| docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md | 74705 | a249c8c8d33af521705d36d677fd4b2cf38ddeee1bb3cd150046e3116ef0b483 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/A2-denial-contract.md | 3365 | 3bf8dc1a98385ed81c22c3742a6eb74e481b1d7302d8fb551fafd20029a15b65 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-self-review.md | 4041 | aeae818741c12c2f85475d127edb79915cee838270e0f3c1b624627de8e030b0 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-spec.md | 3306 | b76ffa0b6e42e561d3d31bf7a4ae9b32ea74f94a5393141f774b5371237e76f6 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-05-review.md | 29013 | 13b19217a6dd807df94371784a1c171f91d2d0dcffafcf093f2ccb0cfa834da5 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-self-review.md | 4293 | 6d3f6e004a66aa1939ad5b15b5453ef72af369ab818125bc551afb0b243ce65a |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-spec.md | 8022 | 4eca24002f9de4caf8cff6f3bba422d78b83623d93d656cb6b7d9cb66be1c5b0 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-self-review.md | 7081 | db08aaf10ec0da8d217b368287bfef037eb574b9d2374b2b61a843eff2e56ead |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-spec.md | 4307 | 2af81380894bd1689d28c50bb9ed232caf118c6b2ed21a5f178050f94c2e5744 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-self-review.md | 4341 | 6e7e7cf0d49888eab58e82e99dbdcf641308357a581fca62933563cae4d6c507 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-spec.md | 4688 | 5fbaeb87e8dabbdb25920fc486e7d6cfa1d28f3a9018022d8166e45d39b53461 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-self-review.md | 8495 | 5a204b954c492dcf89ada462d5858c7cfcbf7cdf1be0ca9c0da5c9f319a08e54 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-spec.md | 5433 | e53d2c44c560965154b611548718d5663729d2daf7ac599fad470ea92c862232 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-self-review.md | 3623 | b16072b7b9fd7392b0bea9706cd0852071f20c6178c60e25181b8b469fbafb90 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-spec.md | 6653 | 731cbb28887cef53aa409728a09f3aa5a1357049b85a96bff4a3b65640c81975 |
| docs/analyses and debug master/campaign CA-2026-09-21/wave2/remaining-wave2-plan.md | 7340 | 05637fe213b06d86875ce37a0a3b8513a33c13e70f046d6d992a4179720a86ec |
| docs/analyses and debug master/CL-29-wave1-implementation-report.md | 4994 | e26b82b86bc4bd7f243a7d505cde197e42f67379f1ba521081e59f616b436d4a |
| docs/architecture/COVERAGE_MATRIX.md | 32948 | edc6edde036ff454bc78eb8d8675be1383cbe97b10cedac7f2da78bfb88a0c4d |
| docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md | 23470 | ae7685d47100bed31744f545415f10d8306886ab5c87b171c7c9b7f835788808 |
| scripts/allowlists/ui_dao_allowlist.yml | 398 | fa74538a2792dc4a786243a33e3bccea1d77f423a82531f1fc47b9356cb88374 |
| scripts/ci/capture_db_guard_evidence.py | 190307 | 1ae59e95f10d6b97b9eb7a41798ca74823c5f7055e66ac78a09ca118a37ec9c4 |
| scripts/ci/guard_ratchet.py | 91792 | b77e8cf946b7fea6df7f61307b0f441b12f5f429ff6d1083d73c98fffb06b26b |
| scripts/ci/guard_test_diagnostics.py | 9363 | d3838e0ddd0d91a762d8a60b0578458b0c47b6d0482c471223c7d09061408a2d |
| scripts/ci/run_static_guard_suite.py | 39854 | da3b46af40c93046d56850cf517d0a7122ced3a9aac8e91b029039c72b8f14ed |
| scripts/ci/test_capture_db_guard_evidence.py | 237978 | a76ddf913009e6143b847f6c4d13a7c2445080c5b0e4f190cd41dd176912dbdc |
| scripts/ci/test_ci_workflow_evidence_gate.py | 5662 | e9633f5bf396cc8c960aa4668027f2d332ab653e988a837765a12412339c7bac |
| scripts/ci/test_guard_ratchet_occurrences.py | 4582 | b79bc4fffbe64b5ef525893165e6dd48d9b26f2520def93e563ecc8a36681806 |
| scripts/ci/test_run_static_guard_suite.py | 99780 | 5b13b4d6738aad52f50878a3ef789e30becec22aa801f5e05d1cd98e232cdd7c |
| scripts/ci/test_verify_known_good_state.py | 55048 | 2e6af83f86fa965e7037ada8fb61d897b26a6258be47270c55d341974e91d077 |
| scripts/ci/test_wave2_guard_wiring.py | 1757 | 092a5b2fd523d534e76beca1319c3ab17853872bcc9a90844b91c6773424cc94 |
| scripts/ci/verify_known_good_state.py | 40954 | 4ad0ebe791f0a50339d71d761da6b5d5d5754be75f2e89023aa6612a1b7c68ce |
| scripts/db_guard/mediation_analysis/test_models.py | 4040 | 83c97e3e8f77b1c4fc347e7bff9ce2fe75d5382e322d0d56fcf03f2531de9db8 |
| scripts/db_guard/policy_parsing.py | 49710 | 9538fb1a301ff7f59ee57734cd78c44b56f935593febdde7593ea587c91985a7 |
| scripts/db_guard/scanner.py | 195892 | 3ccb4b936d8b14a7f1e44adf17618169dcc77e356b0639daf45841f28a049e17 |
| scripts/db_guard/sql_classifier.py | 83300 | fc32415cdd53c98d5307376127b3d93856e5fbd443c9b5e60a79747058b9fb58 |
| scripts/guardrails/cloud_payload_proof.py | 31403 | 904654f677729f490a7dbce1fc76c0e586313a8e9f9869a4b805cf5a3bad2e71 |
| scripts/guardrails/test_cloud_payload_proof.py | 12916 | d7a9b290cd3780338478d00ef2f193d8f8f9983d92020b461672d8b8a2169938 |
| scripts/kotlin_callable_parser.py | 74904 | 39374fe687a8beb4bc86cee789d08a45ddf8f0b53fe5efbf5df4ae50695ffa18 |
| scripts/test_cloud_payload_guard_integration.py | 4309 | 422727c0f8895292ce9aa3762b99ba3d616cabde14d20df1d0c164ac7b38a3b2 |
| scripts/test_db_discovery_diagnostic_details.py | 3780 | f7785af69b483b42b5257881f287bcb2d154c89956cbb3ee0965fab9e512b58c |
| scripts/test_db_guard_policy_v2_evidence.py | 128729 | 93b652cacd354c14c99f6fb96837902e86eba959ccc807d0f220b887a54da96f |
| scripts/test_db_guard_room_inventory.py | 154588 | db447c2d158aa0c5a320d72720855ac6aefb07bc0add3254426372e0aa4fc17a |
| scripts/test_db_guard_run_cache.py | 16686 | 0760e84989a427b471a9b5ebd3900c1e5a4fc36fbe2c641b8fe7fdd58ea8f0d3 |
| scripts/test_db_guard_scanner_d4.py | 136935 | 06b78fd128690a984d3c96f9d8af151f756cba6c05ab61dc1f60029e0212637f |
| scripts/test_db_guard_sql_classifier.py | 60463 | f9e278cd7028e19af539b9b06d5ac2abf56dac51f386b67414a7611bb8130e37 |
| scripts/test_guard_test_diagnostics.py | 15219 | 5b5895aeefb9f8055d7aee2cc91772b222c1e4ebe7c402cf911d1c9af3adddf8 |
| scripts/test_migrate_db_policy_seed_rows.py | 442929 | 365791d881ecf505a5425d4e9ad52390e1958858714b8479752720f9a3d5368f |
| scripts/test_migrate_db_policy_signatures.py | 141848 | 2775ff725b392bee68ac09545ed32eb5ca9885d2deb316bd9fd1ff4c16a9bc02 |
| scripts/test_verify_allowlist_compliance_fail_closed.py | 3535 | 6e1c1ae0b4f7a9ba9773492e50de4942653b25b3e3c338caaf755b42f129490c |
| scripts/test_verify_cancellation_allowlist_scope.py | 6144 | fed4dac4efa53b78ca73e166da3bd68039da7a688fd82b0c68843f1144b1d202 |
| scripts/test_verify_cancellation_boundaries.py | 26052 | 24ab49a27c7e018ea07b29b5bc6a2033301eb4d089d185a00babc14d81941ffa |
| scripts/test_verify_cloud_payload_boundaries.py | 12028 | 69c0e25fb1ad784c8b194ed576cc760a5f2c01daed007ea5bdb1e5ac97407396 |
| scripts/test_verify_db_access_boundaries.py | 258034 | 7f9f3fb7dcd5ad6725e495c30166c798186e04c25ab9b75a008b027473a26b3f |
| scripts/test_verify_db_access_v2.py | 130427 | 9aca452171ff243ccc0f9a752ef7f2f19777c74df6a1ddde3a6ae93dbcee565f |
| scripts/test_verify_worker_entrypoint_proof.py | 4521 | 2c34a9d7a76ebc568faea57b4897f3f47dbbc17b46b5b71eab466c6d54025569 |
| scripts/test_wave2_db_discovery_semantics.py | 21423 | 5e61bc1dba805293de6b3ff50822b7566adcd2733baee715fd5fe42672c457a2 |
| scripts/test_wave2_db_file_discovery.py | 13596 | 2029c2ffad9eced5aad1ec6adea2a7e3eb65c26fced5fd5befd7da8ba8f9da5b |
| scripts/test_wave2_db_ownership_reconciliation.py | 14374 | 2343c9045340d2128b15734f81fd9a2b17ccc24c36affbfa11715fb34d1fa2a3 |
| scripts/verify_allowlist_compliance.py | 18069 | c5d13efad18e82b233d8f46002b28b147e390edb6f9afb561d282b512d8946fb |
| scripts/verify_cancellation_boundaries.py | 22731 | 671db49813976969db18d99a6cc3278c045c75083f379f5554cf4606744af45c |
| scripts/verify_cloud_payload_boundaries.py | 10878 | 49ab0d18f0203878fce46819162c7463f9b44b5820f6f07f60027f7712398426 |
| scripts/verify_db_access_boundaries.py | 180405 | 583512f7a0bb57de4c0e1b93d963dd03480bec18e8659e8623be2277af30b00c |
| scripts/verify_event_writers.py | 11761 | 18fa0e0d90eef570dcb081926c0d45b1037d2f0e8fccfccf42c33d9673fcfa4a |
| scripts/verify_privacy_boundaries.py | 25500 | 6f73f3bea2ce48f88832c47dbe0640095bbf2b46a73c768452ed8daa6c0309d1 |
| scripts/verify_worker_boundaries.py | 26027 | f40564c1e3441677f5569f4c57f4fbd23e875278337e324d10424ab63976eb0e |
| workflows/active/wave2-backup-cleanup-policy-20260928-handoff.md | 11511 | bd3296471a5aecf36a39a326256771333610d1a1af6a3b1639ca3f9e35ffd9ba |
| workflows/active/wave2-db-coordinate-contract-20260927-handoff.md | 11753 | 4c57e1ba51fdda53513df36020643ce737b6e8f38650b60f5566397738ac5803 |
| workflows/active/wave2-db-discovery-diagnostics-20260927-handoff.md | 14900 | 2393c7949b46b5ee3f081ae1028aaa26d8965688f9737a6cb9697c4bc37eeb51 |
| workflows/active/wave2-db-discovery-followup-20260928-handoff.md | 13605 | 30ef7830337f9eb224a64502b924c1c2098898981220cc4b0315c363bbd88f81 |
| workflows/active/wave2-db-discovery-semantics-20260928-handoff.md | 12448 | 1e46872150c9ffc45b93595dab613448e344f96875f787f950c8f2da7c048eec |
| workflows/active/wave2-db-evidence-detection-repair-20260927-handoff.md | 9411 | 141b96940ffa8f19ef7a3bd472ac78c26fce90242f4815c522542d337c273fba |
| workflows/active/wave2-db-file-discovery-20260928-handoff.md | 13639 | 9e0bd6dee820407846c2409a6b2400f2fcbe68b53c67b3d2820fba88679b8af1 |
| workflows/active/wave2-db-guard-followup-20260927-handoff.md | 52322 | d7d20dd2fdb8b28843b79c53015f4fcc247720a68303819cb045857542b1a29d |
| workflows/active/wave2-db-guard-repair-20260927-handoff.md | 59438 | 051f6db09fe4ff5fe3b302e35779070270c6b39663c8f4dbf1637d52a4e83cf6 |
| workflows/active/wave2-db-ownership-reconciliation-20260927-handoff.md | 15950 | 230abd7ecbe7d2552a64268ee989a3c9530ceb602273ba39093487325fdd850e |
| workflows/active/wave2-db-pipeline-diagnostics-20260927-handoff.md | 50578 | d2c08d39761b81ec34805647052d094562405624c47472021e21cd8baf2e4305 |
| workflows/active/wave2-db-source-evidence-adjudication-20260927.md | 12398 | 96e48287527103022e996b3833c4c82c04e0ece44d0aa48886249b84d8bff851 |
| workflows/active/wave2-finalization-repair-20260927-batch1.md | 15234 | 31e41d8f72a55d9ccef924a7655756b5020f0f2635536b064ade2f3fabd51d66 |
| workflows/active/wave2-independent-deep-review-20260927-100231Z.md | 169752 | a6c17cd4081dee93b351bbc167223eb9fcbf4db83ce382f4f2072bd1a96dc83d |
| workflows/active/wave2-mutation-advisory-adjudication-20260928.md | 26615 | 7d801496f7a1a3c9190708af66b5a835f27e0b804b65042f30b27ff86d1f3267 |
| workflows/active/wave2-post075653-adjudication-20260928-handoff.md | 11898 | bbd7254699bb935a946cfb1012bab1dbb3fd20d064248c33ed8c98822bac6b91 |
| workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md | 60453 | 6b9d75c287c23a917d80c1262dc96241de0bb0f3e2c3de5d6002d0c046100e76 |
| workflows/active/wave2-receipt-policy-reconciliation-20260927-handoff.md | 49011 | 15718df600a9a97336851d61f71240d93a843f1ab6dd84c73304ff21a3ef147d |
| workflows/active/wave2-recovery-contract-repair-20260928-handoff.md | 18988 | 27857e6b07d7be8381c9cd1640f53f0c185a98aee2c9eed363ee5d9735c3297f |
| workflows/active/wave2-recovery-fake-dao-repair-20260928-handoff.md | 4561 | 207d12a8e4e37ce3bbb57703861ae8caacf550c4706bfe30bcc00d83bd469f64 |
| workflows/active/wave2-remaining-files.md | 11480 | 1bd01939a0e4e30fce314890678162daeb4fd96fdba06aa735cf1113a0fe0456 |
| workflows/active/wave2-remaining-handoff.md | 10535 | 0d47563109df0e6f37559ab670e56fb10e8bf1c2bf56df492f66ca525d88ef48 |
| workflows/active/wave2-remaining-validation.md | 8707 | 3fe0ec8e4e6e83a65dbbd5ce9d3596476ed0080d527259687646fa8b6fdcfc7c |
| workflows/active/wave2-validation-repair-20260927.md | 11022 | b95431469690db4523310e2383d37c89bdf21471d79e5e1126cec3a317362156 |
