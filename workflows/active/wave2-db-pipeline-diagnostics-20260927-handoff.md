# Wave-2 DB pipeline diagnostics and exact-policy handoff — September 27, 2026

## Verdict and scope

**VERDICT: FAIL for Wave-2 closure.** The three retained DB pipeline failures are not marked resolved. The bounded assertion-diagnostics change below is IMPLEMENTED_UNVERIFIED; no new test, guard, build, Python import, syntax check, or validation profile was executed by this agent.

One agent performed this work, without subagents. Application source, active policy, generated policy artifacts, baselines, allowlists, recursive test selection, prior reports, campaign STATE/JOURNAL records, and git history were not changed in this batch. Existing implementation, validator, and unrelated work is preserved. No commit, push, branch switch, stash, reset, merge, or worktree operation was performed.

Applicable acceptance gates: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07, and FG-23. A source-level self-review is not independent strict review or guardian approval. Diagnostic visibility does not authorize policy entries or turn infrastructure failure into passing evidence.

## 1. Verified retained execution evidence

- Run: `vr-20260927-145831-ebc6b232`, full `static-guards` profile. Its persisted `result.json` reports FAIL, exit 2, `E_COMMAND_FAILED`; its completion marker exists.
- Command recorded by the runner: `python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards`.
- Retained start/end fingerprint: `d50ffa7c861f9c316e30b83fb19d541c8ea7e019d4e4c68d33edeb89b7dbc55d`; HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`. This is the preceding follow-up snapshot, not the diagnostics patch authored here.
- Pytest summary: 3 failed, 4,386 passed, 28 skipped. Full guard suite remains 18/25 passing, 5 violations, 2 infrastructure outcomes. This is not an overall PASS.
- The retained failing nodes are `test_current_db_gate_activated_policy_real_config_pipeline`, `test_default_project_root_uses_canonical_manifest`, and `test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict`.
- The first test calls the production entry point in-process; the latter two consume the same module-cached subprocess scan. They are not three independent subprocess scans.
- The retained `pytest-663/real-tree-scan0/real-tree.json` named in the failure log was readable. It contains `trusted: false`, no findings, and only `DB_POLICY_SOURCE_EVIDENCE_INVALID` with empty context. The public CLI intentionally collapses internal policy errors into that umbrella. Merely appending stderr would still not reveal the failing callable or mutation.

## 2. Concrete source/policy mismatches — no speculative pin regeneration

The current active ownership policy still authorizes five full-row `scannedReceiptDao.update` calls that the actual receipt services no longer make. The source-evidence verifier requires each exact operation to exist (`scripts/db_guard/policy_v2_evidence.py:1262-1277`); an obsolete `update` entry fails that requirement. These are concrete source-level blockers, not proof that they exhaust every internal diagnostic suppressed by the retained CLI report.

| Service / callable | Active policy operation anchor | Actual source operation(s) | Proposed exact reconciliation |
|---|---|---|---|
| `ReceiptLinkService.linkReceiptToExpense` | `config/guards/db_ownership_policy.yml:4737` — `update` | `ReceiptLinkService.kt:261` — `updateLinkTargets` | One row replaced by one row |
| `ReceiptLinkService.unlinkReceiptFromExpense` | `config/guards/db_ownership_policy.yml:4828` — `update` | `ReceiptLinkService.kt:458,460` — `clearMatchFields`, `updatePrimaryExpenseId` in alternative branches | One row replaced by two exact rows |
| `ReceiptMatchLifecycleService.clearMatchForReceipt` | `config/guards/db_ownership_policy.yml:4876` — `update` | `ReceiptMatchLifecycleService.kt:99` — `clearMatchFields` | One row replaced by one row |
| `ReceiptMatchLifecycleService.rejectAllSuggestions` | `config/guards/db_ownership_policy.yml:4998` — `update` | `ReceiptMatchLifecycleService.kt:80` — `updateMatchRejected` | One row replaced by one row |
| `ReceiptMatchLifecycleService.saveMatchSuggestion` | `config/guards/db_ownership_policy.yml:5035` — `update` | `ReceiptMatchLifecycleService.kt:54` — `updateMatchSuggestion` | One row replaced by one row |

Both service files are under `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/`. Their DAO is `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt`; the column-scoped declarations are at lines 108, 126, 139, 148, and 154. Receipt matching/linking remain on their established lifecycle legal paths (`docs/architecture/LEGAL_PATHS.md#receipt-mutations`).

Provenance: commit `b25dc3d821d84133d2e02c1e965a56bc054917c4`, September 15, 2026, RP-12 12b P3-004, introduced the column-scoped methods to prevent resurrection of purged receipt fields. Its ancestry to current HEAD was checked (ancestor exit 0), and current callsites were read. These operation-name moves predate the remaining Wave-2 batch; the Wave-2 Boolean return/affected-row repair is not a reason to restore the old full-row writes.

**Policy edit held for specific human authorization.** The proposed migration removes five obsolete exact identities and installs six exact column-scoped identities, changing active ownership cardinality from 412 to 413. This is an exact-authorization expansion in row count, even though the replacement APIs are narrower in the fields they mutate. The preceding bounded repair did not authorize new exemptions. No row was added, no unrelated entry was removed to conceal the increase, and no baseline was changed. Approval must name this five-to-six replacement; it is not a request for blanket exemptions, a waiver of parser failures, or approval to mark the suite green.

After approval, retain the exact path, owner, callable signature, DAO identity, and barrier contract; remove all five old `update` identities; add no wildcard or fallback. Preserve the existing `claimForAutoMatch` entry at policy line 4710. Reconcile provenance/count tests honestly and verify the whole production pipeline. Do not assert that 413 rows alone proves completeness or that no further evidence/scanner diagnostics can emerge.

## 3. Files touched in this diagnostics batch

| File | Change / meaningful assertions |
|---|---|
| `scripts/ci/guard_test_diagnostics.py` | New read-only formatter: bounded report read; catalog-known finding/diagnostic codes; trust/counts; bounded failed callable identities and mutation identifiers; controlled missing/malformed/oversized report states. No raw process arguments, streams, exception messages, source text, or arbitrary context is copied. |
| `scripts/test_guard_test_diagnostics.py` | New formatter tests cover code/trust visibility, byte preservation, missing/malformed/oversized input, unknown-code redaction, row caps and omitted counts, exact owner separators, invalid paths, bounded identities, and filtered mutation context. Authored only; not collected or executed here. |
| `scripts/test_verify_db_access_boundaries.py:5623-5662` | Observe and return the actual evidence result through a pass-through spy. Add bounded evidence/report context to the existing `exit_code == 0` assertion; assert one evidence invocation. No extra scan and no bypass. Existing 412-entry, structural-stage, trusted, schema, and no-findings assertions remain. |
| `scripts/test_verify_db_access_v2.py:1435,2262` | Add retained report summaries to the two existing return-code assertions. Shared subprocess fixture, strict report checks, canonical defaults, and fail-closed fixture tests remain unchanged. |

The production CLI protocol, production verifier, policy matching, rule catalog, return codes, and guard registry are unchanged in this batch. Source read-back caught and corrected a literal-dot escaping mistake in the new formatter; negative owner-separator tests were added before handoff. This source inspection is not execution evidence.

## 4. Preservation and snapshot

- Branch: `bug-fixes`.
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`.
- Staged paths: none.
- Pre-report captured time: `2026-09-27T18:42:40.5601113+03:00`.
- Pre-report runner-compatible dirty-tree fingerprint: `b8f7080c80d5a003ab4d7cb4712f44ae86a3d1d6f3e2841ba4a502caebee6786`.
- Pre-report dirty paths: 161. This is a snapshot count, not a claim that these are unrelated files or files to discard.
- The intake fingerprint was `bb87dc70ca0dad3d757a98f4c153dda568327dcd821740d6dde1abc95718bebf` (158 dirty paths). Compared with the preceding completed follow-up, only the campaign JOURNAL had changed before this batch; that external change was preserved.
- The post-edit manifest comparison found only the four intended code/test paths above changed or newly dirty, no removed paths, and no unexpected changes. All other intake file bytes were preserved.
- `GroupsRepositoryImplTest.kt` remains byte-identical to intake, SHA-256 `18cf20bd1c4810ea44c13c1949490197c12b36a980bb1b41b034e1763c15708a`. The reported validator brace repair was already in the retained snapshot; it was not reapplied or overwritten.
- Before creating this new, non-overwriting handoff, the runner lock was absent, no RUNNING/STARTING result or matching live validation process was observed, and the source fingerprint still matched the reviewed post-edit snapshot.
- This report is the sole additional path after the manifest below. Its own hash and the post-report fingerprint must be obtained by final read-back rather than embedded self-referentially.

## 5. Human validation — NOT RUN by this agent

The current tree still contains the five known stale policy entries. Running the suite now may collect useful detailed diagnostics but is not expected to close the DB acceptance gate. Prefer the specifically approved exact-policy repair and applicable strict review first, then one serialized full recursive suite run. The valid command from repository root is:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; inspect the persisted run." }
```

No direct pytest/Gradle/guard invocation is authorized here. `guard_tests` is a suite segment, not a registered standalone GuardId. Preserve recursive selection. If a run is already RUNNING, poll that run rather than starting another. Do not edit the tree, including this report, while validation fingerprints it. Keep run ID, result, completion marker, command, start/end fingerprints, and full assertion summaries.

Acceptance for the proposed follow-up includes all three real-tree tests passing with trusted reports and zero unauthorized findings, the existing source-evidence and fail-closed negative tests remaining intact, the new formatter tests actually executing, and policy-delta/self-protection evidence required by `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-06/FG-07/FG-23. Unknown, skipped-required, and infrastructure results remain failures under FG-03.

## 6. Wave-2 closure items not erased by this handback

This narrow diagnostics pass does not supersede the seven-cluster review or the evidence reconciliation in `workflows/active/wave2-independent-deep-review-20260927-100231Z.md` and `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md`.

- Generic receipt/PDF partial-OCR propagation (W2-R3 / CA-P-03-003) remains a substantive carried item; the earlier report traced loss of page-completeness information through the generic receipt lifecycle. It was not repaired here.
- The earlier reconciled targeted ledger still has an unresolved WorkerRunLogger failure and six missing current-batch CL-09 execution records. The pasted 52/52 claim does not, by itself, reconcile that ledger or prove a common dirty-tree snapshot. No new unit-test evidence was collected here.
- Device/PDF/image and MerchantKeyBackfill fixture gates, independent strict review, applicable guardian decisions, required CI capture, and protected-base/head policy/self-protection evidence remain separate requirements.
- Existing non-DB guard violations and known_good_state infrastructure debt remain open; unchanged guard names are not equivalent-fingerprint proof of zero new findings.
- A commit is neither authorized nor performed. Keeping all existing work is the default, not an unresolved decision.

## 7. Pre-report git status

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
?? workflows/active/wave2-finalization-repair-20260927-batch1.md
?? workflows/active/wave2-independent-deep-review-20260927-100231Z.md
?? workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md
?? workflows/active/wave2-remaining-files.md
?? workflows/active/wave2-remaining-handoff.md
?? workflows/active/wave2-remaining-validation.md
?? workflows/active/wave2-validation-repair-20260927.md
```

## 8. Pre-report dirty-file byte manifest

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
| `config/guards/db_ownership_policy.yml` | 325532 | `597db4871df92c98e41946207ba2315c48039b14165838e3a0362a855a4308db` |
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
| `scripts/test_verify_db_access_boundaries.py` | 246432 | `42d5b482a1c20645d1c18de2bf16a34f54c66b9b9fe83de1d5eb3e16367757bf` |
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
| `workflows/active/wave2-finalization-repair-20260927-batch1.md` | 15234 | `31e41d8f72a55d9ccef924a7655756b5020f0f2635536b064ade2f3fabd51d66` |
| `workflows/active/wave2-independent-deep-review-20260927-100231Z.md` | 169752 | `a6c17cd4081dee93b351bbc167223eb9fcbf4db83ce382f4f2072bd1a96dc83d` |
| `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md` | 60453 | `6b9d75c287c23a917d80c1262dc96241de0bb0f3e2c3de5d6002d0c046100e76` |
| `workflows/active/wave2-remaining-files.md` | 11480 | `1bd01939a0e4e30fce314890678162daeb4fd96fdba06aa735cf1113a0fe0456` |
| `workflows/active/wave2-remaining-handoff.md` | 10535 | `0d47563109df0e6f37559ab670e56fb10e8bf1c2bf56df492f66ca525d88ef48` |
| `workflows/active/wave2-remaining-validation.md` | 8707 | `3fe0ec8e4e6e83a65dbbd5ce9d3596476ed0080d527259687646fa8b6fdcfc7c` |
| `workflows/active/wave2-validation-repair-20260927.md` | 11022 | `b95431469690db4523310e2383d37c89bdf21471d79e5e1126cec3a317362156` |

