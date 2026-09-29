# Wave-2 authorized receipt-policy reconciliation — September 27, 2026

**Status: IMPLEMENTED_UNVERIFIED. Wave-2 closure remains pending.**

## 1. Authorization and scope

The human explicitly authorized the preceding question: replace five obsolete exact receipt-update policy identities with six exact column-scoped identities (412 → 413), with no baseline additions, wildcard permissions, or broad exemptions. This handoff records that approval and the resulting bounded edit. It supersedes only the held-for-authorization portion of `workflows/active/wave2-db-pipeline-diagnostics-20260927-handoff.md`; that report and every earlier campaign/review record remain unchanged.

One agent worked without subagents. No tests, Python imports, syntax checks, builds, Gradle commands, guards, or validation profiles were executed. No application/Kotlin/Room-schema changes, commits, pushes, resets, stashes, branch switches, merges, or worktree operations were performed. All existing implementation, validator, and unrelated work was retained.

Requirements remain `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07, and FG-23. The specific authorization does not waive fail-closed behavior, unrelated guard debt, protected-base/head evidence, independent review, or guardian decisions.

## 2. Applied exact policy replacement

File: `config/guards/db_ownership_policy.yml`. The five obsolete `scannedReceiptDao.update` identities were removed. Six replacement rows retain the same exact source path, owner FQCN, callable kind/name/signature, receiver, DAO accessor/FQCN, owner, linked issue, and helper barrier mode. Unlink now has separate identities and branch-specific reasons rather than claiming two different operations share one fingerprint.

| Callable | Authorized operation | Current policy operation line | Actual source call |
|---|---|---:|---|
| `ReceiptLinkService.linkReceiptToExpense` | `updateLinkTargets` | 4737 | `ReceiptLinkService.kt:261` |
| `ReceiptLinkService.unlinkReceiptFromExpense` | `clearMatchFields` | 4828 | `ReceiptLinkService.kt:458` |
| `ReceiptLinkService.unlinkReceiptFromExpense` | `updatePrimaryExpenseId` | 4845 | `ReceiptLinkService.kt:460` |
| `ReceiptMatchLifecycleService.clearMatchForReceipt` | `clearMatchFields` | 4892 | `ReceiptMatchLifecycleService.kt:99` |
| `ReceiptMatchLifecycleService.rejectAllSuggestions` | `updateMatchRejected` | 5014 | `ReceiptMatchLifecycleService.kt:80` |
| `ReceiptMatchLifecycleService.saveMatchSuggestion` | `updateMatchSuggestion` | 5051 | `ReceiptMatchLifecycleService.kt:54` |

The service files are under `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/`. The legal lifecycle routes remain those documented in `docs/architecture/LEGAL_PATHS.md#receipt-mutations`. The five DAO method declarations and their SQL were inspected at `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt:94-154`: these writes touch match/link fields and timestamps, not purged raw OCR, parsed columns, or categorization state. No full-row write was restored.

These source operations came from RP-12 12b, commit `b25dc3d821d84133d2e02c1e965a56bc054917c4`, September 15, 2026; ancestry to HEAD was previously verified and the source remained unchanged in this batch. The Wave-2 conditional suggestion write keeps its current CAS predicates and affected-row semantics. This policy reconciliation does not change them.

- Active ownership cardinality: **412 − 5 + 6 = 413**. This is an authorized exact-policy change, not a baseline increase.
- The existing `claimForAutoMatch` grant is preserved.
- All **407 non-target policy entries**, including their ordering, metadata, and surrounding policy text, are unchanged. Removing the five original blocks before the edit and the six replacement blocks after read-back yields the same UTF-8 text SHA-256: `33f9443b85c2b21826af7da9cc634159f4ebf1ad92a3ca209c11235a0ffe1d95`.
- Policy SHA-256 before: `597db4871df92c98e41946207ba2315c48039b14165838e3a0362a855a4308db`.
- Policy SHA-256 after: `ff6e6a038d26aa7ea4829a61ac374e8d81db6e30a4f056a44a873c6a6254028e`.
- The targeted policy text edit retained its existing encoding and line endings. Preparation failures were inspected before recovery and had not changed the tree.

## 3. Tests and count provenance

File: `scripts/test_verify_db_access_boundaries.py`.

| Current anchor | Authored acceptance coverage |
|---|---|
| `:5203-5229` | Literal approved callable signatures and operation sets; the pre-existing CAS claim is included explicitly. |
| `:5267` | Five parameterized contract cases load the actual active policy through the production loader and exercise the production exact matcher. They pin the complete operation sets, unique matches, callable signatures, DAO identity, helper mode, and existing ownership metadata. |
| `:5296` | Five parameterized cases require all obsolete full-row `update` identities to be denied. |
| `:5312` | Six parameterized cases require each new grant to reject near misses in all nine identity fields: path, owner, kind, method, receiver, parameter signature, DAO accessor, DAO FQCN, and operation. |
| `:5378` and `:5817` | Existing canonical integration count assertions now require 413, with explicit 412 − 5 + 6 provenance rather than a weakened range or self-derived count. |
| `:5798-5844` | Existing full-pipeline exit-zero, real evidence/structural-stage invocation, schema, trusted=true, no-findings, and advisory-only diagnostic assertions remain mandatory. The previously added bounded failure summaries remain intact. |

The additions describe **16 parameterized cases by source inspection (5 + 5 + 6)**, not an execution or collection result. The three retained failing real-tree pipeline tests still require actual execution before being called resolved. No failing assertion was relaxed to accept an untrusted scan, no test was removed/skipped, and recursive selection was not narrowed.

## 4. Why no artifact regeneration or unrelated pin changes

`scripts/migrate_db_policy_signatures.py:126-144` explicitly takes `db_ownership_policy.legacy.yml` as its archived v1 migration input; the active v2 file is the activation output and cannot be that migration input. This batch changes only the active exact authorizations and their tests, not the archived input, seeds, generator, parser, or production source. The tracked candidate/accounting artifacts were therefore left unchanged; no generator was executed.

The ExpenseDao RawQuery pin, structural exceptions and manifest, baselines, allowlists, rule catalog, production verifier, and guard registry were not changed. Existing earlier repairs and their retained evidence were preserved rather than rewritten.

## 5. Validation — NOT RUN

Source inspection, edit read-back, and byte/fingerprint comparison were performed. They are not guard execution, compilation, pytest collection, independent strict review, or guardian approval.

The previously reviewed `vr-20260927-145831-ebc6b232` remains historical FAIL evidence (3 failed / 4,386 passed / 28 skipped; suite 18/25 passing, 5 violations, 2 infrastructure outcomes). It tested fingerprint `d50ffa7c861f9c316e30b83fb19d541c8ea7e019d4e4c68d33edeb89b7dbc55d`, not this repair. The three DB tests are **not marked FIXED_AND_VERIFIED** by editing their policy input.

After the applicable strict review, the human should run one serialized full recursive static-guards profile from the repository root:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; inspect the persisted run." }
```

Use only the validation runner and its global serialization. If another run is RUNNING, poll that run rather than duplicating it. Freeze all fingerprinted files, including handoffs, during execution. `guard_tests` is a suite segment, not a registered standalone GuardId. Preserve the run ID, command, completion marker, result, logs, and matching start/end dirty-tree fingerprints.

Required proof: the three real-tree pipeline tests pass with trusted zero-finding reports; db_access no longer infra-errors; all new receipt-policy and prior diagnostic-formatter tests actually execute; earlier passing coverage stays intact; and the full suite result is reported honestly. Any newly exposed internal evidence/scanner failures require diagnosis—not broader exemptions or acceptance of unknown results. Compare finding identities, not just guard names. FG-03/FG-06/FG-07/FG-23 acceptance and protected-base/head evidence remain required.

## 6. Snapshot and preservation

- Branch: `bug-fixes`; HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`; staged paths: none.
- Authorization-intake snapshot: `2026-09-27T18:51:32.5565360+03:00`, fingerprint `75185647d570e97478d89caacfb7cabcb1dc6f92e3abd9199d79e12b7f4fed7e`, 162 dirty paths.
- Reviewed pre-report snapshot: `2026-09-27T19:12:01.4998750+03:00`, fingerprint `2340d8ff3c1d7b5350ab6f27d69fcd2ab86d805809909aacf9211fcfe6dbf899`, 162 dirty paths.
- Compared with authorization intake, only `config/guards/db_ownership_policy.yml` and `scripts/test_verify_db_access_boundaries.py` changed. No paths were added or removed before this new report. All other intake bytes, including validator edits and previous handoffs, remained identical.
- Post-edit test-module SHA-256: `612c10016e549942c0d881e4c461242b982e7e1e80a9ef7149a1b267603511e4`.
- At report creation, no runner lock, RUNNING/STARTING record, or matching live validation process was observed, and the tree still matched the reviewed fingerprint.
- This new non-overwriting handoff is the sole additional path after the manifest below. Final read-back records its hash and the post-report fingerprint separately to avoid self-referential hashes.

## 7. Remaining Wave-2 closure work

This specific authorization and repair do not close the broader campaign. The independent seven-cluster review and subsequent evidence reconciliation remain authoritative: `workflows/active/wave2-independent-deep-review-20260927-100231Z.md` and `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md`.

Carried items include generic receipt/PDF partial-OCR propagation (W2-R3 / CA-P-03-003); unresolved WorkerRunLogger and missing current-batch CL-09 validation evidence from the reconciled ledger; device/PDF/image/MerchantKeyBackfill gates; independent strict and applicable guardian reviews; remaining guard failures and required CI/self-protection evidence. This batch supplies no new execution evidence for those items and no commit authorization. All current work remains preserved.

## 8. Pre-report git status

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
 M config/guards/db_structural_exceptions.yml
 M config/guards/db_structural_exceptions_expected_methods.yml
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
 M scripts/test_verify_db_access_v2.py
 M scripts/verify_allowlist_compliance.py
 M scripts/verify_cancellation_boundaries.py
 M scripts/verify_cloud_payload_boundaries.py
 M scripts/verify_db_access_boundaries.py
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
?? scripts/ci/guard_test_diagnostics.py
?? scripts/ci/test_guard_ratchet_occurrences.py
?? scripts/ci/test_wave2_guard_wiring.py
?? scripts/guardrails/cloud_payload_proof.py
?? scripts/guardrails/test_cloud_payload_proof.py
?? scripts/test_cloud_payload_guard_integration.py
?? scripts/test_guard_test_diagnostics.py
?? scripts/test_verify_allowlist_compliance_fail_closed.py
?? scripts/test_verify_cancellation_allowlist_scope.py
?? scripts/test_verify_worker_entrypoint_proof.py
?? workflows/active/wave2-db-guard-followup-20260927-handoff.md
?? workflows/active/wave2-db-guard-repair-20260927-handoff.md
?? workflows/active/wave2-db-pipeline-diagnostics-20260927-handoff.md
?? workflows/active/wave2-finalization-repair-20260927-batch1.md
?? workflows/active/wave2-independent-deep-review-20260927-100231Z.md
?? workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md
?? workflows/active/wave2-remaining-files.md
?? workflows/active/wave2-remaining-handoff.md
?? workflows/active/wave2-remaining-validation.md
?? workflows/active/wave2-validation-repair-20260927.md
```

## 9. Pre-report dirty-file byte manifest

| Path | Bytes | SHA-256 |
|---|---:|---|
| `.codex/agents/explorer-lite.toml` | 1676 | `04645298e64b0ec47241bf29c66e94c9e4c09d2360d402c2d2253b9ce20bdf9b` |
| `.codex/agents/orchestrator.toml` | 13776 | `ca156e28f1ca3c02559af0f62035131af2671afdd2b4f289ba41b5166200401c` |
| `.github/workflows/ci.yml` | 18844 | `4289990a1fd3d0d83ac35161bbb4b1286bab65eff66a682207ce5d9a1c63041c` |
| `app/src/androidTest/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt` | 4773 | `87eeb2da61a737c89d49e182a9ad057bd6772ff10f54b56fba0ed1b3c3866faa` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt` | 12626 | `677eea069aac9deae39f9ec7518559700eb1d4c109026f869378dfbeca98fec5` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt` | 1688 | `de196d23e96940c45a33426af0e9bf8d20be26c958e5e7804afdad14fd27ae10` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt` | 1721 | `9d457b66b2cf6e3f6137268467668def3bb28fc15da2a989ee45a2f69195de28` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt` | 1713 | `7bcf5c4b2d7000da90ae85ed539bca43584d8ad4a85062006e792b2fd947952e` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt` | 1584 | `d8f87c13eca0e1e939eb10d24d6155da771a190800b615d259e60c632085e925` |
| `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt` | 1721 | `79c0d2840488811f2b64e9bfc323b7a76ba70b5c3b6595e82d9fbf67f16c3501` |
| `app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt` | 28220 | `574ee78413ab341f5217094221eb9228f94f18bfb0c6f54b30545acd04ab4af1` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt` | 114500 | `efb05f5a2ddd53d5eabcaad96cdcc566f1d6024d604364939cb6fbbc603d264e` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupSettlementDao.kt` | 801 | `10df4f2f9f8776b34e4f34aef8d40c776c763aa3d6dc706d054ad0a6f881c0ca` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt` | 4741 | `86920ef4d61ee61865f2529471ee9cd1d7a2e7229e796374ba5eee4c597535dc` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt` | 13532 | `ecdf1d4bf81edf924b02fb3487942b52ccc965c9d72942bc16445fdfbc88c54a` |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDao.kt` | 7334 | `e47859334156928a7544295d7201e34d10023684ab89d31171692e01b39dde67` |
| `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt` | 7251 | `38cadb28cef425ffca84396131e9bab51940ff8dfe3c2dbd9e1e6793010c3206` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | 163899 | `a30fbced5ff907ad5306b529754ab588cf83d0bcbabe17028d6456f397512ce4` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt` | 50960 | `061cf92c1e52a6457cb1e6ffee8c701fd9833a71915a42e479aa972f895e9a8f` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt` | 2887 | `f6dd17806a51762a4442b7bbde49c17e78e27c9fc9341f0986a26dd16b0a95bf` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt` | 15402 | `348f00a9a6daf96500e22488eb86aa0642cf28a01753282b90bafc99e52cf2f3` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt` | 10580 | `c500b754350ce30f6c3ca83b6e7bdde2454d2ae2c552f6cf306042f8cbb1422f` |
| `app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt` | 3135 | `80f2888ce0caa0eb3ef6bb69e3adfa62d37353c10d3c1d29c2e97281422cffda` |
| `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt` | 6045 | `c0a49edc0954059e1419f9705cce6cb4152a1013c011fc0bfc0f7839ff3b4de7` |
| `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseStatsUnavailableException.kt` | 324 | `5ea763794c7c4f883a756485a2692d74dc166506e7a5b088153415518b4a47f6` |
| `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt` | 12318 | `f3f4d84e945cb69509c69201bb5488797d95b2d039ce4cc04881e09cec6ba26b` |
| `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt` | 40560 | `b0159d0157637be68aca727246eb96dfd3855746947c9e42713b77f206958794` |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt` | 2912 | `fa0c06528f5ceed3beb25406be644944907a5fa594a9af5937514a94c2ef2668` |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicy.kt` | 2066 | `3786ffabc99ad2917df2c3cfa7558662161e673f13a70355748aed989435d076` |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt` | 22242 | `bb66d61e5a0debb2aa48e01daa7731ba30066ef3d404cc10654bf823deda155f` |
| `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt` | 3597 | `69857ac1381f35f5b5332982055b08772396b2b4d6e6a46d08fba00f4d62f03e` |
| `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedException.kt` | 994 | `bbd647b32e7c0910c9314a3cfa562b45d964ba6d480e5103675bfff31a02cf92` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | 63146 | `868a8844cd591262b7892099f79b01c71448549d6704078073b732f0708a37e0` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt` | 12145 | `0891e516c824359ab8433b0319d88038f3a3f58fb50e65e18549b670410fe5fd` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt` | 40407 | `4fbbcd0105bea427987fcb155dcf608d117b4e8f0eac3ec2f8f8a0cf8b96b1ea` |
| `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` | 31554 | `1751ea6a1c3cb245060275e696ffd5018f496704ac04361e081353fa30e440db` |
| `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 72584 | `c595722741b9dccafd56711a1fa2fdfc812c76fb4e410a59574efa27f9738908` |
| `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` | 20932 | `633ae470cd9c8583e2c643652003f0645ce3b622726f19bb192a9bad6d091827` |
| `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt` | 5176 | `668af7e5db8d808d55a0b8defadeaeb216c1e05ac16c50e0c1f1c4633c39d75f` |
| `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt` | 16833 | `0fc22d75cb03d2003c9ee1e40fdb9a1b1fc9709ec15d47442bb4c77926db4331` |
| `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt` | 20070 | `b35dad56a65aa7ce93a176be951eec06b521f6f201999bd2e76e2af84b9429f8` |
| `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt` | 12327 | `74901ad4fa2d53b480c04fdfcaaf4c3dea6af816db1d72852e0c2db191349636` |
| `app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt` | 18615 | `667191bbe391822219e81caf3919b2b73b71ada954f36e32010b8dfbfcc7355d` |
| `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt` | 15232 | `a544a2a887559e929e06a1b0db5544f905473493503f6584489c114e1ea8d154` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt` | 17481 | `0827b20e5ba180ba1dbc9d20479c8db6ea623a297243052141b4b418f657a085` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt` | 19645 | `46b5da5d1fce829ed01ccf773eeded32643bc7ac60cebc06671ebb8fdfa48f21` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt` | 86352 | `a6ba4d04d24a8de7b510115565a14f4fad9262f8637096b57d4eccda14172f4d` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt` | 25237 | `e1d3854970d890d5dd04a59a033c0fe03555809e6362fd71078185e379967153` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt` | 17676 | `60c4e9e0b1ac7025e735b0929ff4c6e4cbea711ea9a3be13fca1d095012d835c` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt` | 61047 | `ee23129fc281cd35053de27a393f6de2c0f6a0fb09d3b4f221a91ea2b982192e` |
| `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt` | 17076 | `66dfde120a93658c00013085287489d6b57009b39077d8f46d5859154bf981ac` |
| `app/src/main/res/values/strings.xml` | 176851 | `92d631eb6d3c441617606a81e350a734ef0b4b02026627ab7725dfa09ff1f27f` |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProof.kt` | 6796 | `e1c92676d6abacd5950e181b3b4c64a308b315c95961d64b0f1d46ad11607f57` |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProofTest.kt` | 4614 | `018e7825717b0a0427101776671f5884dfe6aa6a3df41f7eb9d81c31e2f2a9ae` |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt` | 5592 | `6fb1189b2b224c4c40613c969a16dc2404730a70cda87350de0185990ddaa682` |
| `app/src/test/java/com/yourname/expensetracker/architecture/WorkerSourceMask.kt` | 2999 | `62c416748b1c4a585301040b6aa86c7df71cdcdb3169061283fbf1d5250f112f` |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudProviderTransportPayloadTest.kt` | 13536 | `7648dd83f2987c77d443886199e3a71eb4da80d86c1751e022d2c5cad7d34489` |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt` | 25514 | `ddd92179c56fa76d794a3abb5d412b1d391e68ba9fe10379eef9780ddab93344` |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryPreparedPayloadTest.kt` | 8828 | `6bcb9443add66d5c5f76624951e7ea2948b05078d9534b82788e45fac3ddcc55` |
| `app/src/test/java/com/yourname/expensetracker/data/ai/provider/HybridRouterIntegrationTest.kt` | 9570 | `472ab479b09db130f6dd980978b88c31880844b63f2bc7d1d5b8217a46945db2` |
| `app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierRequiredSemanticQueryTest.kt` | 4618 | `4c221c253010b5d94342e5bb6030cf47950cd484dcf7012393b6e7c7704db5a5` |
| `app/src/test/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDaoTest.kt` | 12203 | `fea4db43d1ae602b79d8063eb8eb67575d4251075e5c3c529c80ea26277ee3e6` |
| `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt` | 63142 | `fd48bf4b5a64eaeaa15aed9026fa2d1b1b09436287575f5ca996777a88466cbf` |
| `app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt` | 9318 | `aaefe92879843d012f654834c18bc595181d55a23bff9f37cb97064305c4b9ad` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` | 112936 | `8b1e487cdd17eaa41bf33b82a9087215cf61ac1ef06d4b808a48069f57e6e5f3` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryMerchantKeyBackfillTest.kt` | 6666 | `48b88063a1ed0b169d00d3404ddf6fd4dcb5c9e005ef608d6821976cee94cedf` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt` | 27437 | `18cf20bd1c4810ea44c13c1949490197c12b36a980bb1b41b034e1763c15708a` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/SharedExpenseSettlementReadTest.kt` | 2055 | `e970ae8df3ecbc02ed3dbd59cb2950322646784bb3c9e30bd8e7740105ce57d7` |
| `app/src/test/java/com/yourname/expensetracker/domain/ai/HybridRouterTest.kt` | 5132 | `24bd95c9c73007945e9755fd25c3cc5f182408de7b8e6c6db6c5afd276659468` |
| `app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt` | 16573 | `bced6298d3817071f1f017ec8c186723f789ed24dea0d8e0afedcfb309d3e76d` |
| `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt` | 37583 | `0852af0cbc9977759720f0f4544143fc83ef25c4dd19919f43542f058c8f7e60` |
| `app/src/test/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicyTest.kt` | 2275 | `a561d25956e952ca9685c243222f9c73fc4be19abf7ffbcc955d17ed9ffdeac6` |
| `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerSettlementTest.kt` | 3987 | `d35ac5d791ba9f6b9c8634872c6dcd427cb5a5aeb0a00508e00f493ab4792592` |
| `app/src/test/java/com/yourname/expensetracker/domain/privacy/CloudProviderPreparedPayloadTest.kt` | 5849 | `c05b3680731c9d0f0bd781c53a46cc12f463c47c5fc285ac36f8c52f02a06140` |
| `app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedExceptionTest.kt` | 1609 | `e974145fa9ed8024c7543eb057b1bb8d41f9cfe80241ea913fb83793dab0b119` |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementCompletionStatusTest.kt` | 1856 | `2c960ed0475ade974e587c5e0113dd81c9d0fccd95df3cf98ccbf54794211e36` |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt` | 17324 | `8b942ea217d30355685b9d80da70954f709bf5bf67867c1453f4ddfa6f61a24b` |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrResultPartialTest.kt` | 1112 | `2f648954b8f66f7f78e0d2696be25511b40be0ca74ee3123d9528c24e25c9086` |
| `app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinatorTest.kt` | 44136 | `c4d8a6788f134babf7cf492d721a0c5de69022c4e8b1c1a3068aa8f8c6c20255` |
| `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt` | 19189 | `0ee9524945f6ea0b4873c8207d3570e5c5781c1bc09381343439577bd9a59208` |
| `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt` | 29728 | `84fc68f519cff22b330ef2b2560b2c882fd7de9e24ed81e6359d14c61f1a280b` |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryTest.kt` | 16184 | `e54bf2b971cc7ec03b3eebc47645b32034966f1cbfe1ec5be76e11536b29b1a3` |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt` | 27314 | `f5ba4227c4b0d4199601410a9d45b7cfbc75776d2565ccaa44bf2207f21f84a2` |
| `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt` | 48708 | `3e0991afd01d7a31fb2523e3e351c3b018dc141009e515db1c1d99c91a822a0b` |
| `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt` | 34257 | `1d6bd170f8f0fd34d113aae8e51cd7e9cbfdb3a5c0fb3327ec666b4aa1cd763b` |
| `app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt` | 38239 | `cf94f172c839f653869977d66b15c3f7a2478fc3a03a51f0e1292bc3bfcdde37` |
| `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt` | 19352 | `bb78094c7efd3985c71b7343eccee493c3df66223f1de73d145802367d2daffe` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` | 15403 | `9ae3fd99ecfcc8f2044136c1ec548a538643010a0301063c8efb3ed10c57284d` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelDatabaseStatsTest.kt` | 7837 | `d23d06f07f82258231ad5a94c13319ffe417eeb66663144178283ab113a07315` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt` | 34043 | `0dfe535da453a3e4498e791c8ade6a3329eaedaefa7bf155ea9d821704023c2c` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt` | 10529 | `04ce37e2c5afcd81d0268d849b80da98869b1607bfd665245ea1079d95708342` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelPrivacyDenialTest.kt` | 8699 | `9ba57cabd64a0594c794dff9228371864fa466cd649edac0d570673041d12276` |
| `app/src/test/java/com/yourname/expensetracker/util/CsvImportRfc4180Test.kt` | 11512 | `f9c74f75b6c4288cc7e9a5deb629ea3f206bce2e728d752e6ec5876dac404394` |
| `config/guards/db_ownership_policy.signatures.accounting.json` | 403312 | `d93da596d674ff5cfa44af687041dbd9736bb202c82ebcb3af7c86380a1b915b` |
| `config/guards/db_ownership_policy.signatures.candidate.yml` | 313973 | `d057d4fab03d31a13f030239e2958fb5aaa8939f68b179d95b73b75b41671017` |
| `config/guards/db_ownership_policy.yml` | 326231 | `ff6e6a038d26aa7ea4829a61ac374e8d81db6e30a4f056a44a873c6a6254028e` |
| `config/guards/db_structural_exceptions.yml` | 26454 | `15bdb87d796470dd6a42db70e07dc0b2bbc153352e3a6050fec1b2e1a5a4cc36` |
| `config/guards/db_structural_exceptions_expected_methods.yml` | 28238 | `ed33a235204288647276bc6f9d263f438b5266ded99a618bbc42af47450b7c2f` |
| `docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md` | 60198 | `a2e437e46c048aee238e031b04083b23f4160641d868aaf90775e39892001683` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A2-denial-contract.md` | 3365 | `3bf8dc1a98385ed81c22c3742a6eb74e481b1d7302d8fb551fafd20029a15b65` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-self-review.md` | 4041 | `aeae818741c12c2f85475d127edb79915cee838270e0f3c1b624627de8e030b0` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-spec.md` | 3306 | `b76ffa0b6e42e561d3d31bf7a4ae9b32ea74f94a5393141f774b5371237e76f6` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-05-review.md` | 29013 | `13b19217a6dd807df94371784a1c171f91d2d0dcffafcf093f2ccb0cfa834da5` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-self-review.md` | 4293 | `6d3f6e004a66aa1939ad5b15b5453ef72af369ab818125bc551afb0b243ce65a` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-spec.md` | 8022 | `4eca24002f9de4caf8cff6f3bba422d78b83623d93d656cb6b7d9cb66be1c5b0` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-self-review.md` | 7081 | `db08aaf10ec0da8d217b368287bfef037eb574b9d2374b2b61a843eff2e56ead` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-spec.md` | 4307 | `2af81380894bd1689d28c50bb9ed232caf118c6b2ed21a5f178050f94c2e5744` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-self-review.md` | 4341 | `6e7e7cf0d49888eab58e82e99dbdcf641308357a581fca62933563cae4d6c507` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-spec.md` | 4688 | `5fbaeb87e8dabbdb25920fc486e7d6cfa1d28f3a9018022d8166e45d39b53461` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-self-review.md` | 8495 | `5a204b954c492dcf89ada462d5858c7cfcbf7cdf1be0ca9c0da5c9f319a08e54` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-spec.md` | 5433 | `e53d2c44c560965154b611548718d5663729d2daf7ac599fad470ea92c862232` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-self-review.md` | 3623 | `b16072b7b9fd7392b0bea9706cd0852071f20c6178c60e25181b8b469fbafb90` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-spec.md` | 6653 | `731cbb28887cef53aa409728a09f3aa5a1357049b85a96bff4a3b65640c81975` |
| `docs/analyses and debug master/campaign CA-2026-09-21/wave2/remaining-wave2-plan.md` | 7340 | `05637fe213b06d86875ce37a0a3b8513a33c13e70f046d6d992a4179720a86ec` |
| `docs/analyses and debug master/CL-29-wave1-implementation-report.md` | 4994 | `e26b82b86bc4bd7f243a7d505cde197e42f67379f1ba521081e59f616b436d4a` |
| `docs/architecture/COVERAGE_MATRIX.md` | 32948 | `edc6edde036ff454bc78eb8d8675be1383cbe97b10cedac7f2da78bfb88a0c4d` |
| `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md` | 23470 | `ae7685d47100bed31744f545415f10d8306886ab5c87b171c7c9b7f835788808` |
| `scripts/allowlists/ui_dao_allowlist.yml` | 398 | `fa74538a2792dc4a786243a33e3bccea1d77f423a82531f1fc47b9356cb88374` |
| `scripts/ci/capture_db_guard_evidence.py` | 190307 | `1ae59e95f10d6b97b9eb7a41798ca74823c5f7055e66ac78a09ca118a37ec9c4` |
| `scripts/ci/guard_ratchet.py` | 91792 | `b77e8cf946b7fea6df7f61307b0f441b12f5f429ff6d1083d73c98fffb06b26b` |
| `scripts/ci/guard_test_diagnostics.py` | 5852 | `8781c3432d208f8fbbf5c7075fc95a4bc678e3d55b4e435a9ed1c9adc72b78a1` |
| `scripts/ci/run_static_guard_suite.py` | 39854 | `da3b46af40c93046d56850cf517d0a7122ced3a9aac8e91b029039c72b8f14ed` |
| `scripts/ci/test_capture_db_guard_evidence.py` | 237978 | `a76ddf913009e6143b847f6c4d13a7c2445080c5b0e4f190cd41dd176912dbdc` |
| `scripts/ci/test_ci_workflow_evidence_gate.py` | 5662 | `e9633f5bf396cc8c960aa4668027f2d332ab653e988a837765a12412339c7bac` |
| `scripts/ci/test_guard_ratchet_occurrences.py` | 4582 | `b79bc4fffbe64b5ef525893165e6dd48d9b26f2520def93e563ecc8a36681806` |
| `scripts/ci/test_run_static_guard_suite.py` | 99780 | `5b13b4d6738aad52f50878a3ef789e30becec22aa801f5e05d1cd98e232cdd7c` |
| `scripts/ci/test_wave2_guard_wiring.py` | 1757 | `092a5b2fd523d534e76beca1319c3ab17853872bcc9a90844b91c6773424cc94` |
| `scripts/db_guard/mediation_analysis/test_models.py` | 4040 | `83c97e3e8f77b1c4fc347e7bff9ce2fe75d5382e322d0d56fcf03f2531de9db8` |
| `scripts/db_guard/sql_classifier.py` | 83300 | `fc32415cdd53c98d5307376127b3d93856e5fbd443c9b5e60a79747058b9fb58` |
| `scripts/guardrails/cloud_payload_proof.py` | 31403 | `904654f677729f490a7dbce1fc76c0e586313a8e9f9869a4b805cf5a3bad2e71` |
| `scripts/guardrails/test_cloud_payload_proof.py` | 12916 | `d7a9b290cd3780338478d00ef2f193d8f8f9983d92020b461672d8b8a2169938` |
| `scripts/test_cloud_payload_guard_integration.py` | 4309 | `422727c0f8895292ce9aa3762b99ba3d616cabde14d20df1d0c164ac7b38a3b2` |
| `scripts/test_db_guard_room_inventory.py` | 154588 | `db447c2d158aa0c5a320d72720855ac6aefb07bc0add3254426372e0aa4fc17a` |
| `scripts/test_db_guard_run_cache.py` | 16686 | `0760e84989a427b471a9b5ebd3900c1e5a4fc36fbe2c641b8fe7fdd58ea8f0d3` |
| `scripts/test_db_guard_sql_classifier.py` | 60463 | `f9e278cd7028e19af539b9b06d5ac2abf56dac51f386b67414a7611bb8130e37` |
| `scripts/test_guard_test_diagnostics.py` | 6553 | `9bc44fc00d633df4f6a0f189af5cb824b552b89775ef70b82906b31dd93f6e26` |
| `scripts/test_migrate_db_policy_seed_rows.py` | 442929 | `365791d881ecf505a5425d4e9ad52390e1958858714b8479752720f9a3d5368f` |
| `scripts/test_migrate_db_policy_signatures.py` | 141848 | `2775ff725b392bee68ac09545ed32eb5ca9885d2deb316bd9fd1ff4c16a9bc02` |
| `scripts/test_verify_allowlist_compliance_fail_closed.py` | 3535 | `6e1c1ae0b4f7a9ba9773492e50de4942653b25b3e3c338caaf755b42f129490c` |
| `scripts/test_verify_cancellation_allowlist_scope.py` | 6144 | `fed4dac4efa53b78ca73e166da3bd68039da7a688fd82b0c68843f1144b1d202` |
| `scripts/test_verify_cancellation_boundaries.py` | 26052 | `24ab49a27c7e018ea07b29b5bc6a2033301eb4d089d185a00babc14d81941ffa` |
| `scripts/test_verify_cloud_payload_boundaries.py` | 12028 | `69c0e25fb1ad784c8b194ed576cc760a5f2c01daed007ea5bdb1e5ac97407396` |
| `scripts/test_verify_db_access_boundaries.py` | 252153 | `612c10016e549942c0d881e4c461242b982e7e1e80a9ef7149a1b267603511e4` |
| `scripts/test_verify_db_access_v2.py` | 130378 | `c5dde019d73818a3b1cfd6934585fecdf3ca931b6e8feff5c95e10544689106d` |
| `scripts/test_verify_worker_entrypoint_proof.py` | 4521 | `2c34a9d7a76ebc568faea57b4897f3f47dbbc17b46b5b71eab466c6d54025569` |
| `scripts/verify_allowlist_compliance.py` | 18069 | `c5d13efad18e82b233d8f46002b28b147e390edb6f9afb561d282b512d8946fb` |
| `scripts/verify_cancellation_boundaries.py` | 22731 | `671db49813976969db18d99a6cc3278c045c75083f379f5554cf4606744af45c` |
| `scripts/verify_cloud_payload_boundaries.py` | 10878 | `49ab0d18f0203878fce46819162c7463f9b44b5820f6f07f60027f7712398426` |
| `scripts/verify_db_access_boundaries.py` | 180038 | `11f997c3f027cd139b0a7ef7eacdfc7743f65cf519ecdcb8c81749bc5d74e379` |
| `scripts/verify_event_writers.py` | 11761 | `18fa0e0d90eef570dcb081926c0d45b1037d2f0e8fccfccf42c33d9673fcfa4a` |
| `scripts/verify_privacy_boundaries.py` | 25500 | `6f73f3bea2ce48f88832c47dbe0640095bbf2b46a73c768452ed8daa6c0309d1` |
| `scripts/verify_worker_boundaries.py` | 26027 | `f40564c1e3441677f5569f4c57f4fbd23e875278337e324d10424ab63976eb0e` |
| `workflows/active/wave2-db-guard-followup-20260927-handoff.md` | 52322 | `d7d20dd2fdb8b28843b79c53015f4fcc247720a68303819cb045857542b1a29d` |
| `workflows/active/wave2-db-guard-repair-20260927-handoff.md` | 59438 | `051f6db09fe4ff5fe3b302e35779070270c6b39663c8f4dbf1637d52a4e83cf6` |
| `workflows/active/wave2-db-pipeline-diagnostics-20260927-handoff.md` | 50578 | `d2c08d39761b81ec34805647052d094562405624c47472021e21cd8baf2e4305` |
| `workflows/active/wave2-finalization-repair-20260927-batch1.md` | 15234 | `31e41d8f72a55d9ccef924a7655756b5020f0f2635536b064ade2f3fabd51d66` |
| `workflows/active/wave2-independent-deep-review-20260927-100231Z.md` | 169752 | `a6c17cd4081dee93b351bbc167223eb9fcbf4db83ce382f4f2072bd1a96dc83d` |
| `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md` | 60453 | `6b9d75c287c23a917d80c1262dc96241de0bb0f3e2c3de5d6002d0c046100e76` |
| `workflows/active/wave2-remaining-files.md` | 11480 | `1bd01939a0e4e30fce314890678162daeb4fd96fdba06aa735cf1113a0fe0456` |
| `workflows/active/wave2-remaining-handoff.md` | 10535 | `0d47563109df0e6f37559ab670e56fb10e8bf1c2bf56df492f66ca525d88ef48` |
| `workflows/active/wave2-remaining-validation.md` | 8707 | `3fe0ec8e4e6e83a65dbbd5ce9d3596476ed0080d527259687646fa8b6fdcfc7c` |
| `workflows/active/wave2-validation-repair-20260927.md` | 11022 | `b95431469690db4523310e2383d37c89bdf21471d79e5e1126cec3a317362156` |

