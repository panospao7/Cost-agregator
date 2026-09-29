# Wave-2 bounded DB-guard repair — September 27, 2026

**Status: IMPLEMENTED_UNVERIFIED. Wave 2 remains open.**

## 1. Authorization, scope, and safety

This is the bounded DB parser / exact-policy artifact / provenance-test repair authorized by the human after the post-validation adjudication. It does not authorize new exemptions, baseline growth, unrelated application changes, historical-record rewrites, or commits. One agent performed the work; no subagents were used.

The implementing agent completed a source-level review of the nine-file repair. This is not an independent strict-review or guardian approval and is not test-execution evidence. The relevant requirements remain `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07, and FG-23. Unknown or infrastructure results are not passing evidence.

No Gradle, pytest, compilation, lint, guard verification, or validation-runner profile was executed in this repair. The authorized paired artifact-generation write utility was executed once; its exact command, exit 1, and visible legacy debt are recorded below. No active-policy promotion was performed.

## 2. Reviewed snapshot and preservation

- Branch: `bug-fixes`.
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b` (unchanged).
- Index: empty before and after the repair.
- Authorization-intake snapshot: `2026-09-27T16:05:38.3879593+03:00`; 144 dirty paths; fingerprint `08c8a0a11156baafa3ca585b77e455ffa3ca106822c525f7abbdc23983b45ee9`.
- Pre-handoff snapshot: `2026-09-27T16:29:56.4405763+03:00`; 153 dirty paths; fingerprint `f86b076e955ffe66ae6665973d578a3702cfdb730990022c2c85ce7f5fea35e4`.
- All 144 previously dirty paths have exactly their intake bytes. The nine repair files were clean at intake and are the only additional dirty paths before this report.
- Write preflight at `2026-09-27T16:29:57.4727852+03:00`: no active lock, RUNNING/STARTING results, or detected validation processes; this report did not already exist.
- No application/Kotlin source, frozen seed input, existing campaign record, baseline, allowlist, structural manifest, guard registry, recursive test selection, or validation runner was changed by this bounded repair.
- No commit, push, reset, stash, branch switch, merge, rebase, or worktree operation was performed.

The appendices record the exact pre-handoff status and dirty-file byte manifest. Creating this new report itself adds one untracked path and changes the subsequent runner fingerprint. Human validation must record its own complete start/end fingerprint; matching HEAD alone is insufficient.

Prior evidence and scope remain in:
- `workflows/active/wave2-independent-deep-review-20260927-100231Z.md`
- `workflows/active/wave2-finalization-repair-20260927-batch1.md`
- `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md` (SHA-256 `6b9d75c287c23a917d80c1262dc96241de0bb0f3e2c3de5d6002d0c046100e76`).

## 3. Files touched and exact repairs

| File | Repair |
|---|---|
| `scripts/db_guard/sql_classifier.py:744–752` | Rebase a nested SELECT by its actual opening depth plus one, rather than the constant one. Preserve the complete SELECT grammar and failure path. |
| `scripts/test_db_guard_sql_classifier.py:1183` | Add 24 valid nested SELECT/DELETE cases and 16 malformed-subquery fail-closed cases. |
| `scripts/test_db_guard_room_inventory.py:242` | Add three Room inventory fixtures covering the real orphan-event DELETE shape and malformed inner queries. |
| `scripts/test_migrate_db_policy_seed_rows.py:1381` | Reconcile ten exact historical retention signature moves without changing frozen seeds or other authorization fields; preserve the removal ledger and exact combined-file comparison. |
| `scripts/test_migrate_db_policy_signatures.py:1512` | Pin the evidenced 55 resolved / 38 unresolved / 45 keeper distribution and the exact ten folded indexes; retain reproducibility and full accounting checks. |
| `scripts/test_verify_db_access_boundaries.py:5090` | Correct the stale 406-entry pins to the existing 412-entry policy with commit provenance; add an exact seven-entry transfer-signature contract. Preserve trusted-scan, no-findings, and strict-manifest requirements. |
| `config/guards/db_ownership_policy.yml` | Move exactly seven existing identities to the current six-parameter transfer callable signatures. No new entry or mutation pair. |
| `config/guards/db_ownership_policy.signatures.candidate.yml` | Regenerate the candidate, changing only the same seven exact signature identities. |
| `config/guards/db_ownership_policy.signatures.accounting.json` | Regenerate paired signature and source-coverage evidence; retain every legacy outcome and all seed records. |

New documentation: `workflows/active/wave2-db-guard-repair-20260927-handoff.md` (this file). Existing reports were not overwritten.

### SQL classification and authored assertions

The production query in `OperationRunEventDao.kt:41–48` contains `NOT IN (SELECT ...)` inside a further parenthesized predicate. The old classifier left the SELECT/FROM tokens at nonzero statement depth, while `_valid_select_tail` requires depth zero. The repair normalizes to the nested statement itself, just as structured CASE operands already normalize to their own depth.

Valid test cases cross four surrounding depths (0, 1, 2, 4), SELECT/DELETE, and IN/NOT IN/EXISTS. Assertions pin the exact operation, absence of classification error, and read/mutation polarity. Malformed cases cross two depths, both outer verbs, and four malformed inner statements; they must remain UNCLASSIFIABLE and neither reads nor mutations. The Room fixtures require one recognized mutation for the valid query and a controlled `DB_ROOM_QUERY_UNCLASSIFIABLE` diagnostic with no recognized mutation for invalid queries. No DAO exemption or unknown-as-read fallback was added.

Authored additions total **45 test cases**: 24 valid SQL + 16 invalid SQL + 3 Room inventory + 1 exact seed-move contract + 1 exact active-policy contract. These cases have NOT executed yet.

### Historical provenance and no-broadening audit

1. `3d3b5bc9d` (September 12, 2026; GR-14u51b) added `DatabaseWriteBarrier` to `RetentionModule.provideRetentionTargets`. Frozen GR-08k1 rows keep their original two-parameter identity. The combined living seed already uses three parameters. A test-only transformation accepts only the exact path, owner, callable kind, method, receiver, old parameter tuple, and ten explicitly listed DAO mutation triples. It changes only parameter types; field equality and eight identity near-misses per entry protect the other metadata. Combined rows remain 352 and the existing removal ledger remains unchanged.
2. `cad0b664b` (September 20, 2026) added terminal nullable `correlationId` to `TransactionLifecycleCoordinator.updateTransferDetails` and `updateTypeAndTransferDetails`. These changes predate the remaining Wave-2 batch and are not attributed to CL-05. Active and candidate policy identities now include the final `String?` parameter.
3. `fea8cdfe` (September 21, 2026; RP-14) had already added six exact retention mutations to the active policy, moving its count from 406 to 412. This repair updates the stale tests, not the policy cardinality or structural-manifest contract.

The seven transfer identity moves preserve these existing mutation pairs:

| Callable | DAO accessor | Operation |
|---|---|---|
| updateTransferDetails | expenseDao | updateTransferAccountName |
| updateTransferDetails | expenseDao | updateTransferDirection |
| updateTransferDetails | transactionEventDao | insert |
| updateTypeAndTransferDetails | expenseDao | updateTransactionType |
| updateTypeAndTransferDetails | expenseDao | updateTransferAccountName |
| updateTypeAndTransferDetails | expenseDao | updateTransferDirection |
| updateTypeAndTransferDetails | transactionEventDao | insert |

Path, owner FQCN, receiver, callable kind, DAO identity, operation, barrier metadata, reason, owner, and linked issue were retained. The new test also pins the current signatures, helper barrier, existing owner, and MIT-003 link. The active-policy diff contains only the seven additional parameter lines. The 412-entry active policy was NOT replaced with the separate 407-entry candidate.

## 4. Paired artifact generation — not a validation PASS

Executed once as the authorized artifact-writing operation:

```powershell
python -B scripts/migrate_db_policy_signatures.py --generate --seed-rows docs/ci/db-findings/GR-08-seeds.yml
```

**Exit code: 1.** The paired files were written, but unresolved legacy debt remains visible. No claim of test, guard, or suite PASS follows from this operation.

```text
db-policy migration: input=93 resolved=55 unresolved=38 duplicateMutationKeys=0 seeds=352
unresolved CALLABLE_MISSING=8
unresolved DAO_IDENTITY_UNRESOLVED=19
unresolved MUTATION_PAIR_MISSING=2
unresolved OWNER_MISSING=9
```

Read-back audit against the pre-repair tracked artifacts:
- Candidate entries: 407 before and after; the entire candidate equals the previous text with only the seven reviewed signature moves. Changed zero-based entry positions: 376, 377, 378, 381, 382, 383, 384.
- Accounting records: 93 before and after. Only positions 14–20 change their exact signature keys; no unexpected record or outcome change was found.
- Seed records: 352 before and after, unchanged.
- Changed accounting root fields only: `candidateSha256`, `records`, `sourceMutations`, `sourceTreeSha`.
- Candidate SHA-256 matches the accounting pair reference: `d057d4fab03d31a13f030239e2958fb5aaa8939f68b179d95b73b75b41671017`.
- The active-policy hash after generation equals its pre-generation hash, proving generation did not promote or rewrite that separate document.
- Source-coverage observations: 269 → 283, comprising 21 added and 7 removed occurrences. Multiset comparison was used so repeated `deleteOlderThan` observations were not collapsed. These are current-tree evidence, not newly authorized policy entries; unresolved analyzer observations remain visible.

Coverage changes reflect existing bank lifecycle/recovery, recurring reconciliation, retention, and DataRetentionWorker paths. The detailed multiset delta is preserved in Appendix C. The 38 unresolved legacy records were not suppressed or treated as passing execution evidence.

### Protected input hashes (unchanged)

| Input | SHA-256 |
|---|---|
| `config/guards/db_ownership_policy.legacy.yml` | `f415cc4c09b34e610de3cfeb8ebb7e861106ab66118d54f411c494dc4e4e5927` |
| `docs/ci/db-findings/GR-08-seeds.yml` | `90ff0224793dcc0dafed6be0cfa1822121759965592aeace226e66ffd7052008` |
| `docs/ci/db-findings/GR-08k1-seed.yml` | `fc728e4fb35090f0a515f809b7b34129186d77e81d632350e4cc4417a8781983` |

## 5. Mapping the eleven prior pytest failures

All dispositions below are **IMPLEMENTED_UNVERIFIED**, not FIXED_AND_VERIFIED. The prior failing execution was `vr-20260927-115801-d3fc6fab`, not this repaired snapshot.

| Prior failing test | Repair to be verified |
|---|---|
| `test_migrate_db_policy_seed_rows.py::test_combined_seed_file_concatenates_all_twenty_seven_batch_seed_files` | Exact ten-entry historical signature reconciliation. |
| `test_migrate_db_policy_signatures.py::test_real_run_distribution_pinned_and_reproducible` | Evidence-backed exact distribution and folded-index pins. |
| `test_migrate_db_policy_signatures.py::test_tracked_candidate_artifact_matches_regeneration_bytes` | Paired candidate regeneration. |
| `test_migrate_db_policy_signatures.py::test_tracked_accounting_artifact_matches_regeneration_bytes` | Paired accounting regeneration. |
| `test_migrate_db_policy_signatures.py::test_verify_happy_path_tracked_artifacts_match` | Reconciled exact artifact identities and pair hashes. |
| `test_migrate_db_policy_signatures.py::test_verify_report_distribution_matches_accounting_records` | Reconciled artifacts and current distribution. |
| `test_migrate_db_policy_signatures.py::test_verify_detects_seedless_regeneration` | Restore valid seeded tracked pair so the existing negative test can test its intended tampering. |
| `test_verify_db_access_boundaries.py::test_checked_in_structural_only_manifest_contract_via_production_apis` | Existing 412-entry cardinality, with provenance rather than a relaxed range. |
| `test_verify_db_access_boundaries.py::test_current_db_gate_activated_policy_real_config_pipeline` | Correct nested SQL classification, exact transfer identities, and current cardinality. |
| `test_verify_db_access_v2.py::test_default_project_root_uses_canonical_manifest` | Correct real-tree inventory classification and active identities; this test file is untouched. |
| `test_verify_db_access_v2.py::test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict` | Same real-tree repair; fail-closed fixture and production-default assertions remain untouched. |

No failing test was removed, skipped, narrowed out of recursive selection, or changed to accept untrusted scans. Artifact byte comparisons, duplicate rejection, exact index accounting, reproducibility, structural-manifest checks, trusted=true, and no-unauthorized-findings assertions remain required.

## 6. Human-run validation handoff

**Validation: NOT RUN for this repair.** The implementing agent inspected source and file bytes only, apart from the explicitly authorized generation write above. Independent strict-review and required guardian approvals remain separate closure gates.

After the review gate and while the tree is frozen, the human should run the complete recursive static-guards profile through the serialized runner wrapper:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; inspect the persisted run." }
```

`guard_tests` is a suite segment, not a registered guard. Do NOT use `-GuardId guard_tests`, invoke pytest directly, reduce recursive selection, or interpret collection completion alone as PASS. The wrapper blocks on the same runner ID and the repository lock preserves serialization. If it reports an unfinished run at the wrapper deadline, poll that existing ID; never start a duplicate. Do not edit this report or any fingerprinted file during the run.

Persist the full command, run ID, terminal result, exit code, completion marker, logs, HEAD, and matching start/end dirty-tree fingerprints. Inspect the individual guard outcomes and the recursive pytest summary: the eleven prior failure nodes and all 45 authored cases must actually execute, without newly skipped coverage. Any unrelated guard failure still leaves the overall suite non-passing; this repair does not waive it. Apply `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07, and FG-23.

## 7. Remaining Wave-2 closure blockers

- This nine-file repair has no new validation evidence. Its eleven-failure handback is not closed until the human run proves it.
- The generic receipt OCR partial-result propagation requirement (W2-R3 / CA-P-03-003) remains PARTIAL: page partiality must survive the repository → lifecycle/persisted-state → UI path. Statement-specific/helper tests do not establish that contract.
- The previous adjudication found 54 planned targeted filters but only 48 distinct September 27 main-checkout result records: 47 latest PASS, WorkerRunLoggerTest latest FAIL, and six CL-09 filters without located execution evidence. The historical “52/52 green” assertion is not substituted for those records, and older dirty-snapshot passes are not final-tree passes.
- The six missing filter records are ExpenseRepositoryMerchantKeyBackfillTest, MerchantKeyBackfillWorkerTest, WarrantyReminderDeliveryDaoTest, WarrantyExpirationWorkerTest, ReceiptMatchingViewModelTest, and ReceiptLinkServiceColumnScopeTest. See the prior adjudication for their human commands and evidence table.
- WorkerRunLoggerTest environmental adjudication still needs reproducible evidence or an explicit approved disposition; the latest retained failure is not automatically waived.
- Existing non-DB guard debt, known-good-state evidence, detector/base-head comparison, and any required remote CI capture remain unresolved by this batch. An unchanged failing guard-name set does not prove no new violations; do not grow baselines to make it green.
- PDF/image and MerchantKeyBackfill device/instrumentation gates remain pending.
- Independent strict review and required architecture/privacy/money guardian decisions remain pending. This agent’s self-review is not an independent approval.
- Commit authorization was not granted or exercised. Nothing is marked DONE/GREEN/complete for Wave 2.

## Appendix A — exact pre-handoff git status

```text
 M .codex/agents/orchestrator.toml
 M .github/workflows/ci.yml
 M app/src/androidTest/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt
 M app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt
 M app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupSettlementDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDao.kt
 M app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt
 M app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt
 M app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt
 M app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt
 M app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt
 M app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt
 M app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt
 M app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt
 M app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt
 M app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedException.kt
 M app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt
 M app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
 M app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt
 M app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt
 M app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
 M app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt
 M app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt
 M app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt
 M app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt
 M app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt
 M app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt
 M app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt
 M app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt
 M app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt
 M app/src/main/res/values/strings.xml
 M app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDaoTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt
 M app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/privacy/CloudProviderPreparedPayloadTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinatorTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt
 M app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt
 M app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt
 M app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt
 M app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt
 M app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt
 M app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt
 M app/src/test/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt
 M app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelPrivacyDenialTest.kt
 M app/src/test/java/com/yourname/expensetracker/util/CsvImportRfc4180Test.kt
 M config/guards/db_ownership_policy.signatures.accounting.json
 M config/guards/db_ownership_policy.signatures.candidate.yml
 M config/guards/db_ownership_policy.yml
 M "docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md"
 M "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-05-review.md"
 M docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md
 M scripts/allowlists/ui_dao_allowlist.yml
 M scripts/ci/capture_db_guard_evidence.py
 M scripts/ci/guard_ratchet.py
 M scripts/ci/run_static_guard_suite.py
 M scripts/ci/test_capture_db_guard_evidence.py
 M scripts/ci/test_ci_workflow_evidence_gate.py
 M scripts/ci/test_run_static_guard_suite.py
 M scripts/db_guard/mediation_analysis/test_models.py
 M scripts/db_guard/sql_classifier.py
 M scripts/test_db_guard_room_inventory.py
 M scripts/test_db_guard_run_cache.py
 M scripts/test_db_guard_sql_classifier.py
 M scripts/test_migrate_db_policy_seed_rows.py
 M scripts/test_migrate_db_policy_signatures.py
 M scripts/test_verify_cancellation_boundaries.py
 M scripts/test_verify_cloud_payload_boundaries.py
 M scripts/test_verify_db_access_boundaries.py
 M scripts/verify_allowlist_compliance.py
 M scripts/verify_cancellation_boundaries.py
 M scripts/verify_cloud_payload_boundaries.py
 M scripts/verify_event_writers.py
 M scripts/verify_privacy_boundaries.py
 M scripts/verify_worker_boundaries.py
?? .codex/agents/explorer-lite.toml
?? app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseStatsUnavailableException.kt
?? app/src/main/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicy.kt
?? app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProof.kt
?? app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProofTest.kt
?? app/src/test/java/com/yourname/expensetracker/architecture/WorkerSourceMask.kt
?? app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudProviderTransportPayloadTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryPreparedPayloadTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/ai/provider/HybridRouterIntegrationTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierRequiredSemanticQueryTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryMerchantKeyBackfillTest.kt
?? app/src/test/java/com/yourname/expensetracker/data/repository/SharedExpenseSettlementReadTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/ai/HybridRouterTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicyTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerSettlementTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedExceptionTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrResultPartialTest.kt
?? app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementCompletionStatusTest.kt
?? app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelDatabaseStatsTest.kt
?? "docs/analyses and debug master/CL-29-wave1-implementation-report.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/A2-denial-contract.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-self-review.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-spec.md"
?? "docs/analyses and debug master/campaign CA-2026-09-21/wave2/remaining-wave2-plan.md"
?? docs/architecture/COVERAGE_MATRIX.md
?? scripts/ci/test_guard_ratchet_occurrences.py
?? scripts/ci/test_wave2_guard_wiring.py
?? scripts/guardrails/cloud_payload_proof.py
?? scripts/guardrails/test_cloud_payload_proof.py
?? scripts/test_cloud_payload_guard_integration.py
?? scripts/test_verify_allowlist_compliance_fail_closed.py
?? scripts/test_verify_cancellation_allowlist_scope.py
?? scripts/test_verify_worker_entrypoint_proof.py
?? workflows/active/wave2-finalization-repair-20260927-batch1.md
?? workflows/active/wave2-independent-deep-review-20260927-100231Z.md
?? workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md
?? workflows/active/wave2-remaining-files.md
?? workflows/active/wave2-remaining-handoff.md
?? workflows/active/wave2-remaining-validation.md
?? workflows/active/wave2-validation-repair-20260927.md
```

## Appendix B — pre-handoff dirty-file byte manifest

Runner-compatible fingerprint: `f86b076e955ffe66ae6665973d578a3702cfdb730990022c2c85ce7f5fea35e4`.

| Path | SHA-256 | Bytes |
|---|---|---:|
| `.codex/agents/explorer-lite.toml` | `04645298e64b0ec47241bf29c66e94c9e4c09d2360d402c2d2253b9ce20bdf9b` | 1676 |
| `.codex/agents/orchestrator.toml` | `ca156e28f1ca3c02559af0f62035131af2671afdd2b4f289ba41b5166200401c` | 13776 |
| `.github/workflows/ci.yml` | `4289990a1fd3d0d83ac35161bbb4b1286bab65eff66a682207ce5d9a1c63041c` | 18844 |
| `app/src/androidTest/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt` | `87eeb2da61a737c89d49e182a9ad057bd6772ff10f54b56fba0ed1b3c3866faa` | 4773 |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt` | `677eea069aac9deae39f9ec7518559700eb1d4c109026f869378dfbeca98fec5` | 12626 |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt` | `de196d23e96940c45a33426af0e9bf8d20be26c958e5e7804afdad14fd27ae10` | 1688 |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt` | `9d457b66b2cf6e3f6137268467668def3bb28fc15da2a989ee45a2f69195de28` | 1721 |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt` | `7bcf5c4b2d7000da90ae85ed539bca43584d8ad4a85062006e792b2fd947952e` | 1713 |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt` | `d8f87c13eca0e1e939eb10d24d6155da771a190800b615d259e60c632085e925` | 1584 |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt` | `79c0d2840488811f2b64e9bfc323b7a76ba70b5c3b6595e82d9fbf67f16c3501` | 1721 |
| `app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt` | `574ee78413ab341f5217094221eb9228f94f18bfb0c6f54b30545acd04ab4af1` | 28220 |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt` | `efb05f5a2ddd53d5eabcaad96cdcc566f1d6024d604364939cb6fbbc603d264e` | 114500 |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupSettlementDao.kt` | `10df4f2f9f8776b34e4f34aef8d40c776c763aa3d6dc706d054ad0a6f881c0ca` | 801 |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt` | `86920ef4d61ee61865f2529471ee9cd1d7a2e7229e796374ba5eee4c597535dc` | 4741 |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt` | `ecdf1d4bf81edf924b02fb3487942b52ccc965c9d72942bc16445fdfbc88c54a` | 13532 |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDao.kt` | `e47859334156928a7544295d7201e34d10023684ab89d31171692e01b39dde67` | 7334 |
| `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt` | `38cadb28cef425ffca84396131e9bab51940ff8dfe3c2dbd9e1e6793010c3206` | 7251 |
| `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | `a30fbced5ff907ad5306b529754ab588cf83d0bcbabe17028d6456f397512ce4` | 163899 |
| `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt` | `061cf92c1e52a6457cb1e6ffee8c701fd9833a71915a42e479aa972f895e9a8f` | 50960 |
| `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt` | `f6dd17806a51762a4442b7bbde49c17e78e27c9fc9341f0986a26dd16b0a95bf` | 2887 |
| `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt` | `348f00a9a6daf96500e22488eb86aa0642cf28a01753282b90bafc99e52cf2f3` | 15402 |
| `app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt` | `c500b754350ce30f6c3ca83b6e7bdde2454d2ae2c552f6cf306042f8cbb1422f` | 10580 |
| `app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt` | `80f2888ce0caa0eb3ef6bb69e3adfa62d37353c10d3c1d29c2e97281422cffda` | 3135 |
| `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt` | `c0a49edc0954059e1419f9705cce6cb4152a1013c011fc0bfc0f7839ff3b4de7` | 6045 |
| `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseStatsUnavailableException.kt` | `5ea763794c7c4f883a756485a2692d74dc166506e7a5b088153415518b4a47f6` | 324 |
| `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt` | `f3f4d84e945cb69509c69201bb5488797d95b2d039ce4cc04881e09cec6ba26b` | 12318 |
| `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt` | `b0159d0157637be68aca727246eb96dfd3855746947c9e42713b77f206958794` | 40560 |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt` | `fa0c06528f5ceed3beb25406be644944907a5fa594a9af5937514a94c2ef2668` | 2912 |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicy.kt` | `3786ffabc99ad2917df2c3cfa7558662161e673f13a70355748aed989435d076` | 2066 |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt` | `bb66d61e5a0debb2aa48e01daa7731ba30066ef3d404cc10654bf823deda155f` | 22242 |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt` | `69857ac1381f35f5b5332982055b08772396b2b4d6e6a46d08fba00f4d62f03e` | 3597 |
| `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedException.kt` | `bbd647b32e7c0910c9314a3cfa562b45d964ba6d480e5103675bfff31a02cf92` | 994 |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | `868a8844cd591262b7892099f79b01c71448549d6704078073b732f0708a37e0` | 63146 |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt` | `0891e516c824359ab8433b0319d88038f3a3f58fb50e65e18549b670410fe5fd` | 12145 |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt` | `4fbbcd0105bea427987fcb155dcf608d117b4e8f0eac3ec2f8f8a0cf8b96b1ea` | 40407 |
| `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` | `1751ea6a1c3cb245060275e696ffd5018f496704ac04361e081353fa30e440db` | 31554 |
| `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | `c595722741b9dccafd56711a1fa2fdfc812c76fb4e410a59574efa27f9738908` | 72584 |
| `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` | `633ae470cd9c8583e2c643652003f0645ce3b622726f19bb192a9bad6d091827` | 20932 |
| `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt` | `668af7e5db8d808d55a0b8defadeaeb216c1e05ac16c50e0c1f1c4633c39d75f` | 5176 |
| `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt` | `0fc22d75cb03d2003c9ee1e40fdb9a1b1fc9709ec15d47442bb4c77926db4331` | 16833 |
| `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt` | `b35dad56a65aa7ce93a176be951eec06b521f6f201999bd2e76e2af84b9429f8` | 20070 |
| `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt` | `74901ad4fa2d53b480c04fdfcaaf4c3dea6af816db1d72852e0c2db191349636` | 12327 |
| `app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt` | `667191bbe391822219e81caf3919b2b73b71ada954f36e32010b8dfbfcc7355d` | 18615 |
| `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt` | `a544a2a887559e929e06a1b0db5544f905473493503f6584489c114e1ea8d154` | 15232 |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt` | `0827b20e5ba180ba1dbc9d20479c8db6ea623a297243052141b4b418f657a085` | 17481 |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt` | `46b5da5d1fce829ed01ccf773eeded32643bc7ac60cebc06671ebb8fdfa48f21` | 19645 |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt` | `a6ba4d04d24a8de7b510115565a14f4fad9262f8637096b57d4eccda14172f4d` | 86352 |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt` | `e1d3854970d890d5dd04a59a033c0fe03555809e6362fd71078185e379967153` | 25237 |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt` | `60c4e9e0b1ac7025e735b0929ff4c6e4cbea711ea9a3be13fca1d095012d835c` | 17676 |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt` | `ee23129fc281cd35053de27a393f6de2c0f6a0fb09d3b4f221a91ea2b982192e` | 61047 |
| `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt` | `66dfde120a93658c00013085287489d6b57009b39077d8f46d5859154bf981ac` | 17076 |
| `app/src/main/res/values/strings.xml` | `92d631eb6d3c441617606a81e350a734ef0b4b02026627ab7725dfa09ff1f27f` | 176851 |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProof.kt` | `e1c92676d6abacd5950e181b3b4c64a308b315c95961d64b0f1d46ad11607f57` | 6796 |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProofTest.kt` | `018e7825717b0a0427101776671f5884dfe6aa6a3df41f7eb9d81c31e2f2a9ae` | 4614 |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt` | `6fb1189b2b224c4c40613c969a16dc2404730a70cda87350de0185990ddaa682` | 5592 |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerSourceMask.kt` | `62c416748b1c4a585301040b6aa86c7df71cdcdb3169061283fbf1d5250f112f` | 2999 |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudProviderTransportPayloadTest.kt` | `7648dd83f2987c77d443886199e3a71eb4da80d86c1751e022d2c5cad7d34489` | 13536 |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt` | `ddd92179c56fa76d794a3abb5d412b1d391e68ba9fe10379eef9780ddab93344` | 25514 |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryPreparedPayloadTest.kt` | `6bcb9443add66d5c5f76624951e7ea2948b05078d9534b82788e45fac3ddcc55` | 8828 |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/HybridRouterIntegrationTest.kt` | `472ab479b09db130f6dd980978b88c31880844b63f2bc7d1d5b8217a46945db2` | 9570 |
| `app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierRequiredSemanticQueryTest.kt` | `4c221c253010b5d94342e5bb6030cf47950cd484dcf7012393b6e7c7704db5a5` | 4618 |
| `app/src/test/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDaoTest.kt` | `fea4db43d1ae602b79d8063eb8eb67575d4251075e5c3c529c80ea26277ee3e6` | 12203 |
| `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt` | `fd48bf4b5a64eaeaa15aed9026fa2d1b1b09436287575f5ca996777a88466cbf` | 63142 |
| `app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt` | `aaefe92879843d012f654834c18bc595181d55a23bff9f37cb97064305c4b9ad` | 9318 |
| `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` | `8b1e487cdd17eaa41bf33b82a9087215cf61ac1ef06d4b808a48069f57e6e5f3` | 112936 |
| `app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryMerchantKeyBackfillTest.kt` | `48b88063a1ed0b169d00d3404ddf6fd4dcb5c9e005ef608d6821976cee94cedf` | 6666 |
| `app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt` | `18cf20bd1c4810ea44c13c1949490197c12b36a980bb1b41b034e1763c15708a` | 27437 |
| `app/src/test/java/com/yourname/expensetracker/data/repository/SharedExpenseSettlementReadTest.kt` | `e970ae8df3ecbc02ed3dbd59cb2950322646784bb3c9e30bd8e7740105ce57d7` | 2055 |
| `app/src/test/java/com/yourname/expensetracker/domain/ai/HybridRouterTest.kt` | `24bd95c9c73007945e9755fd25c3cc5f182408de7b8e6c6db6c5afd276659468` | 5132 |
| `app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt` | `bced6298d3817071f1f017ec8c186723f789ed24dea0d8e0afedcfb309d3e76d` | 16573 |
| `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt` | `0852af0cbc9977759720f0f4544143fc83ef25c4dd19919f43542f058c8f7e60` | 37583 |
| `app/src/test/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicyTest.kt` | `a561d25956e952ca9685c243222f9c73fc4be19abf7ffbcc955d17ed9ffdeac6` | 2275 |
| `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerSettlementTest.kt` | `d35ac5d791ba9f6b9c8634872c6dcd427cb5a5aeb0a00508e00f493ab4792592` | 3987 |
| `app/src/test/java/com/yourname/expensetracker/domain/privacy/CloudProviderPreparedPayloadTest.kt` | `c05b3680731c9d0f0bd781c53a46cc12f463c47c5fc285ac36f8c52f02a06140` | 5849 |
| `app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedExceptionTest.kt` | `e974145fa9ed8024c7543eb057b1bb8d41f9cfe80241ea913fb83793dab0b119` | 1609 |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementCompletionStatusTest.kt` | `2c960ed0475ade974e587c5e0113dd81c9d0fccd95df3cf98ccbf54794211e36` | 1856 |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt` | `8b942ea217d30355685b9d80da70954f709bf5bf67867c1453f4ddfa6f61a24b` | 17324 |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrResultPartialTest.kt` | `2f648954b8f66f7f78e0d2696be25511b40be0ca74ee3123d9528c24e25c9086` | 1112 |
| `app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinatorTest.kt` | `c4d8a6788f134babf7cf492d721a0c5de69022c4e8b1c1a3068aa8f8c6c20255` | 44136 |
| `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt` | `0ee9524945f6ea0b4873c8207d3570e5c5781c1bc09381343439577bd9a59208` | 19189 |
| `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt` | `84fc68f519cff22b330ef2b2560b2c882fd7de9e24ed81e6359d14c61f1a280b` | 29728 |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryTest.kt` | `e54bf2b971cc7ec03b3eebc47645b32034966f1cbfe1ec5be76e11536b29b1a3` | 16184 |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt` | `f5ba4227c4b0d4199601410a9d45b7cfbc75776d2565ccaa44bf2207f21f84a2` | 27314 |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt` | `3e0991afd01d7a31fb2523e3e351c3b018dc141009e515db1c1d99c91a822a0b` | 48708 |
| `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt` | `1d6bd170f8f0fd34d113aae8e51cd7e9cbfdb3a5c0fb3327ec666b4aa1cd763b` | 34257 |
| `app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt` | `cf94f172c839f653869977d66b15c3f7a2478fc3a03a51f0e1292bc3bfcdde37` | 38239 |
| `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt` | `bb78094c7efd3985c71b7343eccee493c3df66223f1de73d145802367d2daffe` | 19352 |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` | `9ae3fd99ecfcc8f2044136c1ec548a538643010a0301063c8efb3ed10c57284d` | 15403 |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelDatabaseStatsTest.kt` | `d23d06f07f82258231ad5a94c13319ffe417eeb66663144178283ab113a07315` | 7837 |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt` | `0dfe535da453a3e4498e791c8ade6a3329eaedaefa7bf155ea9d821704023c2c` | 34043 |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt` | `04ce37e2c5afcd81d0268d849b80da98869b1607bfd665245ea1079d95708342` | 10529 |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelPrivacyDenialTest.kt` | `9ba57cabd64a0594c794dff9228371864fa466cd649edac0d570673041d12276` | 8699 |
| `app/src/test/java/com/yourname/expensetracker/util/CsvImportRfc4180Test.kt` | `f9c74f75b6c4288cc7e9a5deb629ea3f206bce2e728d752e6ec5876dac404394` | 11512 |
| `config/guards/db_ownership_policy.signatures.accounting.json` | `d93da596d674ff5cfa44af687041dbd9736bb202c82ebcb3af7c86380a1b915b` | 403312 |
| `config/guards/db_ownership_policy.signatures.candidate.yml` | `d057d4fab03d31a13f030239e2958fb5aaa8939f68b179d95b73b75b41671017` | 313973 |
| `config/guards/db_ownership_policy.yml` | `597db4871df92c98e41946207ba2315c48039b14165838e3a0362a855a4308db` | 325532 |
| `docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md` | `9add3bb1c173c405ac71b6d81ddfcad01d60443135de93b29ebe17b2794a0e2c` | 57635 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A2-denial-contract.md` | `3bf8dc1a98385ed81c22c3742a6eb74e481b1d7302d8fb551fafd20029a15b65` | 3365 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-self-review.md` | `aeae818741c12c2f85475d127edb79915cee838270e0f3c1b624627de8e030b0` | 4041 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-spec.md` | `b76ffa0b6e42e561d3d31bf7a4ae9b32ea74f94a5393141f774b5371237e76f6` | 3306 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-05-review.md` | `13b19217a6dd807df94371784a1c171f91d2d0dcffafcf093f2ccb0cfa834da5` | 29013 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-self-review.md` | `6d3f6e004a66aa1939ad5b15b5453ef72af369ab818125bc551afb0b243ce65a` | 4293 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-spec.md` | `4eca24002f9de4caf8cff6f3bba422d78b83623d93d656cb6b7d9cb66be1c5b0` | 8022 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-self-review.md` | `db08aaf10ec0da8d217b368287bfef037eb574b9d2374b2b61a843eff2e56ead` | 7081 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-spec.md` | `2af81380894bd1689d28c50bb9ed232caf118c6b2ed21a5f178050f94c2e5744` | 4307 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-self-review.md` | `6e7e7cf0d49888eab58e82e99dbdcf641308357a581fca62933563cae4d6c507` | 4341 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-spec.md` | `5fbaeb87e8dabbdb25920fc486e7d6cfa1d28f3a9018022d8166e45d39b53461` | 4688 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-self-review.md` | `5a204b954c492dcf89ada462d5858c7cfcbf7cdf1be0ca9c0da5c9f319a08e54` | 8495 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-spec.md` | `e53d2c44c560965154b611548718d5663729d2daf7ac599fad470ea92c862232` | 5433 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-self-review.md` | `b16072b7b9fd7392b0bea9706cd0852071f20c6178c60e25181b8b469fbafb90` | 3623 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-spec.md` | `731cbb28887cef53aa409728a09f3aa5a1357049b85a96bff4a3b65640c81975` | 6653 |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/remaining-wave2-plan.md` | `05637fe213b06d86875ce37a0a3b8513a33c13e70f046d6d992a4179720a86ec` | 7340 |
| `docs/analyses and debug master/CL-29-wave1-implementation-report.md` | `e26b82b86bc4bd7f243a7d505cde197e42f67379f1ba521081e59f616b436d4a` | 4994 |
| `docs/architecture/COVERAGE_MATRIX.md` | `edc6edde036ff454bc78eb8d8675be1383cbe97b10cedac7f2da78bfb88a0c4d` | 32948 |
| `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md` | `ae7685d47100bed31744f545415f10d8306886ab5c87b171c7c9b7f835788808` | 23470 |
| `scripts/allowlists/ui_dao_allowlist.yml` | `fa74538a2792dc4a786243a33e3bccea1d77f423a82531f1fc47b9356cb88374` | 398 |
| `scripts/ci/capture_db_guard_evidence.py` | `1ae59e95f10d6b97b9eb7a41798ca74823c5f7055e66ac78a09ca118a37ec9c4` | 190307 |
| `scripts/ci/guard_ratchet.py` | `b77e8cf946b7fea6df7f61307b0f441b12f5f429ff6d1083d73c98fffb06b26b` | 91792 |
| `scripts/ci/run_static_guard_suite.py` | `da3b46af40c93046d56850cf517d0a7122ced3a9aac8e91b029039c72b8f14ed` | 39854 |
| `scripts/ci/test_capture_db_guard_evidence.py` | `a76ddf913009e6143b847f6c4d13a7c2445080c5b0e4f190cd41dd176912dbdc` | 237978 |
| `scripts/ci/test_ci_workflow_evidence_gate.py` | `e9633f5bf396cc8c960aa4668027f2d332ab653e988a837765a12412339c7bac` | 5662 |
| `scripts/ci/test_guard_ratchet_occurrences.py` | `b79bc4fffbe64b5ef525893165e6dd48d9b26f2520def93e563ecc8a36681806` | 4582 |
| `scripts/ci/test_run_static_guard_suite.py` | `5b13b4d6738aad52f50878a3ef789e30becec22aa801f5e05d1cd98e232cdd7c` | 99780 |
| `scripts/ci/test_wave2_guard_wiring.py` | `092a5b2fd523d534e76beca1319c3ab17853872bcc9a90844b91c6773424cc94` | 1757 |
| `scripts/db_guard/mediation_analysis/test_models.py` | `83c97e3e8f77b1c4fc347e7bff9ce2fe75d5382e322d0d56fcf03f2531de9db8` | 4040 |
| `scripts/db_guard/sql_classifier.py` | `fc32415cdd53c98d5307376127b3d93856e5fbd443c9b5e60a79747058b9fb58` | 83300 |
| `scripts/guardrails/cloud_payload_proof.py` | `904654f677729f490a7dbce1fc76c0e586313a8e9f9869a4b805cf5a3bad2e71` | 31403 |
| `scripts/guardrails/test_cloud_payload_proof.py` | `d7a9b290cd3780338478d00ef2f193d8f8f9983d92020b461672d8b8a2169938` | 12916 |
| `scripts/test_cloud_payload_guard_integration.py` | `422727c0f8895292ce9aa3762b99ba3d616cabde14d20df1d0c164ac7b38a3b2` | 4309 |
| `scripts/test_db_guard_room_inventory.py` | `b432b826f8141ee8b966a865303d65709a8fae751dfc50fe292343646e59e0f6` | 154385 |
| `scripts/test_db_guard_run_cache.py` | `0760e84989a427b471a9b5ebd3900c1e5a4fc36fbe2c641b8fe7fdd58ea8f0d3` | 16686 |
| `scripts/test_db_guard_sql_classifier.py` | `f9e278cd7028e19af539b9b06d5ac2abf56dac51f386b67414a7611bb8130e37` | 60463 |
| `scripts/test_migrate_db_policy_seed_rows.py` | `365791d881ecf505a5425d4e9ad52390e1958858714b8479752720f9a3d5368f` | 442929 |
| `scripts/test_migrate_db_policy_signatures.py` | `2775ff725b392bee68ac09545ed32eb5ca9885d2deb316bd9fd1ff4c16a9bc02` | 141848 |
| `scripts/test_verify_allowlist_compliance_fail_closed.py` | `6e1c1ae0b4f7a9ba9773492e50de4942653b25b3e3c338caaf755b42f129490c` | 3535 |
| `scripts/test_verify_cancellation_allowlist_scope.py` | `fed4dac4efa53b78ca73e166da3bd68039da7a688fd82b0c68843f1144b1d202` | 6144 |
| `scripts/test_verify_cancellation_boundaries.py` | `24ab49a27c7e018ea07b29b5bc6a2033301eb4d089d185a00babc14d81941ffa` | 26052 |
| `scripts/test_verify_cloud_payload_boundaries.py` | `69c0e25fb1ad784c8b194ed576cc760a5f2c01daed007ea5bdb1e5ac97407396` | 12028 |
| `scripts/test_verify_db_access_boundaries.py` | `9842c0e4d90abd9ce3eb9d4752d7e140313a61b10b20ea2126eed28b871293d6` | 240872 |
| `scripts/test_verify_worker_entrypoint_proof.py` | `2c34a9d7a76ebc568faea57b4897f3f47dbbc17b46b5b71eab466c6d54025569` | 4521 |
| `scripts/verify_allowlist_compliance.py` | `c5d13efad18e82b233d8f46002b28b147e390edb6f9afb561d282b512d8946fb` | 18069 |
| `scripts/verify_cancellation_boundaries.py` | `671db49813976969db18d99a6cc3278c045c75083f379f5554cf4606744af45c` | 22731 |
| `scripts/verify_cloud_payload_boundaries.py` | `49ab0d18f0203878fce46819162c7463f9b44b5820f6f07f60027f7712398426` | 10878 |
| `scripts/verify_event_writers.py` | `18fa0e0d90eef570dcb081926c0d45b1037d2f0e8fccfccf42c33d9673fcfa4a` | 11761 |
| `scripts/verify_privacy_boundaries.py` | `6f73f3bea2ce48f88832c47dbe0640095bbf2b46a73c768452ed8daa6c0309d1` | 25500 |
| `scripts/verify_worker_boundaries.py` | `f40564c1e3441677f5569f4c57f4fbd23e875278337e324d10424ab63976eb0e` | 26027 |
| `workflows/active/wave2-finalization-repair-20260927-batch1.md` | `31e41d8f72a55d9ccef924a7655756b5020f0f2635536b064ade2f3fabd51d66` | 15234 |
| `workflows/active/wave2-independent-deep-review-20260927-100231Z.md` | `a6c17cd4081dee93b351bbc167223eb9fcbf4db83ce382f4f2072bd1a96dc83d` | 169752 |
| `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md` | `6b9d75c287c23a917d80c1262dc96241de0bb0f3e2c3de5d6002d0c046100e76` | 60453 |
| `workflows/active/wave2-remaining-files.md` | `1bd01939a0e4e30fce314890678162daeb4fd96fdba06aa735cf1113a0fe0456` | 11480 |
| `workflows/active/wave2-remaining-handoff.md` | `0d47563109df0e6f37559ab670e56fb10e8bf1c2bf56df492f66ca525d88ef48` | 10535 |
| `workflows/active/wave2-remaining-validation.md` | `3fe0ec8e4e6e83a65dbbd5ce9d3596476ed0080d527259687646fa8b6fdcfc7c` | 8707 |
| `workflows/active/wave2-validation-repair-20260927.md` | `b95431469690db4523310e2383d37c89bdf21471d79e5e1126cec3a317362156` | 11022 |

## Appendix C — generated source-coverage multiset delta

These evidence occurrences are not policy authorizations. Counts explicitly retain multiplicity.

| Kind | Source / symbol | Operation | Before | After |
|---|---|---|---:|---:|
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt` / `com.yourname.expensetracker.domain.bank.BankConnectionLifecycleCoordinator#disconnectConnection` | `deleteByBankConnectionScope` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#purge` | `deleteOlderThan` | 2 | 5 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` / `com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator#updateRule` | `deleteOpenPlannedByRecurringRuleId` | 1 | 0 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` / `com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator#reconcileUpdateInCurrentTransaction` | `deleteOpenPlannedBySourceKeys` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#purge` | `deleteOrphanEventsOlderThan` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecovery.kt` / `com.yourname.expensetracker.domain.bank.BankSyncStartupRecovery#recoverStaleRuns` | `finalizeIfRunning` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt` / `com.yourname.expensetracker.data.privacy.DataRetentionWorker#doWork` | `insert` | 1 | 0 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringPlanProjectionService.kt` / `com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService#projectFromRule` | `insertPlannedExpense` | 1 | 0 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecovery.kt` / `com.yourname.expensetracker.domain.bank.BankSyncStartupRecovery#recoverStaleRuns` | `markStaleFailed` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#purge` | `purgeRawOcrText` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#purge` | `purgeTerminalRunsWithEvents` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` / `com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator#reconcileUpdateInCurrentTransaction` | `update` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` / `com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator#updateRule` | `update` | 1 | 0 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` / `com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator#reconcileUpdateInCurrentTransaction` | `updateDerivedSnapshotForKey` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#purge` | `updateRawOcrTextPurged` | 1 | 0 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt` / `com.yourname.expensetracker.domain.bank.BankConnectionLifecycleCoordinator#persistOutcome` | `updateSyncStatus` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt` / `com.yourname.expensetracker.domain.bank.BankConnectionLifecycleCoordinator#persistOutcome` | `updateSyncStatusOnly` | 0 | 1 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt` / `com.yourname.expensetracker.domain.bank.BankApiIntegration#refreshToken` | `updateToken` | 1 | 0 |
| OBSERVED_NOT_IN_LEGACY_POLICY | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt` / `com.yourname.expensetracker.domain.bank.BankApiIntegration#refreshToken` | `updateTokenIfConnected` | 0 | 1 |
| UNRESOLVED_ANALYZER_INPUT | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#provideRetentionTargets` | `deleteOlderThan` | 2 | 5 |
| UNRESOLVED_ANALYZER_INPUT | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#provideRetentionTargets` | `deleteOrphanEventsOlderThan` | 0 | 1 |
| UNRESOLVED_ANALYZER_INPUT | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#provideRetentionTargets` | `purgeRawOcrText` | 0 | 1 |
| UNRESOLVED_ANALYZER_INPUT | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#provideRetentionTargets` | `purgeTerminalRunsWithEvents` | 0 | 1 |
| UNRESOLVED_ANALYZER_INPUT | `app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt` / `com.yourname.expensetracker.di.RetentionModule#provideRetentionTargets` | `updateRawOcrTextPurged` | 1 | 0 |

