# Wave-2 DB guard follow-up — September 27, 2026

**Status: IMPLEMENTED_UNVERIFIED for this follow-up. Wave 2 remains open.**

## 1. Verified new execution evidence

The human run `vr-20260927-140520-bd1f4fc8` is genuine evidence against the previous DB-repair snapshot. It supersedes the earlier status that no post-repair run existed. The durable result has a completion marker, exit 2 / FAIL, and matching start/end dirty-tree fingerprints.

- Command executed by the human through the runner: `python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards`.
- Start: September 27, 2026, 14:05:20Z (17:05:20 +03:00). Finish: 14:18:59Z (17:18:59 +03:00); elapsed 814 seconds.
- Tested HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`.
- Tested fingerprint: `bc10689cb2303dc3ed25b6afd49fc8ae89a3ed62a19097f900995b91d8d48add`, matching the previous repair handoff’s verified final snapshot.
- Suite: 18 PASS, 5 blocking violations, 2 infrastructure errors out of 25 components.
- Pytest: 4,403 collected; 4,370 passed; 5 failed; 28 skipped. Thus 4,375 executed, not 4,403 passing.
- `db_artifact_sync` now PASS.
- Result SHA-256: `e65310594a3ded597dbf2ef1d0c480be9a5fc78f7e150f14d459c430b2a46592`.
- Observed `build/ci/static-guards/guard_tests.log` SHA-256: `a79048a03d5642fe30c194dca5aa174c9a6486d0b50c907d73f9c4140989f6b6`. That suite-output path can be overwritten by later runs; associate this digest with the run above.

### Seven prior migration failures are verified resolved on that snapshot

Each of these exact nodes has one PASSED record and no failure or skip in the new log:
- `scripts/test_migrate_db_policy_seed_rows.py::test_combined_seed_file_concatenates_all_twenty_seven_batch_seed_files`
- `scripts/test_migrate_db_policy_signatures.py::test_real_run_distribution_pinned_and_reproducible`
- `scripts/test_migrate_db_policy_signatures.py::test_tracked_candidate_artifact_matches_regeneration_bytes`
- `scripts/test_migrate_db_policy_signatures.py::test_tracked_accounting_artifact_matches_regeneration_bytes`
- `scripts/test_migrate_db_policy_signatures.py::test_verify_happy_path_tracked_artifacts_match`
- `scripts/test_migrate_db_policy_signatures.py::test_verify_report_distribution_matches_accounting_records`
- `scripts/test_migrate_db_policy_signatures.py::test_verify_detects_seedless_regeneration`

### The prior 45 authored cases were checked by node identity, not just net totals

| Case group | Executed | Passed | Failed |
|---|---:|---:|---:|
| Nested valid SELECT/DELETE classification | 24 | 24 | 0 |
| Nested malformed-query rejection | 16 | 16 | 0 |
| Orphan-event Room inventory contract | 3 | 2 | 1 |
| Exact historical retention signature move | 1 | 1 | 0 |
| Exact transfer-policy signature contract | 1 | 1 | 0 |
| Total | 45 | 44 | 1 |

These results prove progress in the SQL classifier and artifact/provenance repair. They do not establish a passing full suite or make every original Wave-2 finding closed. Unchanged skip totals or failing guard names alone are not a full no-regression comparison.

## 2. Corrections to the validator diagnosis

### A. The new Room test failure is fixture isolation, not proof of a stale production ExpenseDao pin

The successful-query branch of `test_orphan_event_delete_nested_query_inventory_contract` passed its one-mutator assertion and then failed its empty-diagnostics assertion. `build_room_inventory(..., raw_query_policy=None)` deliberately loads the canonical production RawQuery policy. The synthetic temporary tree contains only its test OperationRunEventDao, not the production ExpenseDao named by that policy, so stale-policy detection correctly rejects that mismatched fixture/policy combination.

This was an isolation mistake in the authored test. It was NOT a reason to regenerate or remove the production ExpenseDao classification. The localized repair supplies the explicit, schema-valid empty policy for this synthetic DAO, which has no RawQuery methods. The exact one-mutator assertion, empty diagnostics assertion, and malformed-query rejection controls remain intact. Production defaults and production policy bytes are unchanged.

Anchors: `scripts/test_db_guard_room_inventory.py:247–275`; `scripts/db_guard/room_inventory.py:1407–1421`.

### B. The four DB test names persist, but their current failure is not the original SQL-depth/cardinality failure

The checked-in structural-manifest test now gets past the repaired 412-entry ownership assertion and reports three `SOURCE_EVIDENCE_MISSING` errors for DatabaseBackupRepositoryImpl.createCostBackup: writableDatabase, getDatabasePath, and openDatabase. The other three DB test failures cascade from the real-tree report’s `DB_POLICY_SOURCE_EVIDENCE_INVALID`, with trusted=false and exit 2.

The actual operations live in `runCostBackupExport`, reached by both createCostBackup overloads. Git blame and commit `0835b5c7fdda6c83df9552f699c765e524051520` establish that RP-03A moved the shared export pipeline there on September 14, 2026, before this remaining Wave-2 batch. The two structural YAML documents and their immutable expected-tuple contract still named the wrappers. Updating a RawQuery pin would not fix this.

Anchors: `DatabaseBackupRepositoryImpl.kt:536–607,672,681,696,723`; retained failures at `build/ci/static-guards/guard_tests.log:4427–4456`; real-tree report under `C:/Users/panos/AppData/Local/Temp/pytest-of-panos/pytest-662/real-tree-scan0/real-tree.json`.

### C. A source-confirmed generic-declaration gap also had to be addressed

The structural declaration matcher previously required a function name immediately after fun and whitespace. It could not recognize the existing `private suspend fun <T> runCostBackupExport(...)` header. Moving only the policy names would therefore still leave an undiscoverable declaration. Both structural scope attribution and manifest function discovery use that shared matcher.

The matcher now recognizes bounded leading identifier/reified type-parameter lists while retaining the function name as its sole capture. It does not use arbitrary-header matching, whole-file evidence, or a class-wide fallback. Comment/string masking, exact operation evidence, bounded-body checks, full method-name matching, and fail-closed unsupported-body handling remain unchanged. This is a bounded grammar extension for these declaration shapes, not a claim of a complete Kotlin type-parameter parser.

The generic gap is a source-level diagnosis, not an agent-executed reproduction. The follow-up code still requires human execution evidence.

## 3. Follow-up files touched

| File | Change |
|---|---|
| `scripts/test_db_guard_room_inventory.py` | Explicit empty RawQuery policy for the synthetic no-RawQuery fixture; retain all assertions. |
| `scripts/verify_db_access_boundaries.py` | Recognize bounded leading generic parameters; move exactly three canonical backup-operation identities to the existing helper. |
| `scripts/test_verify_db_access_boundaries.py` | Add 14 cases for generic names/body boundaries, malformed headers, comment/string masking, exact three-operation policy movement, and rejection of stale/near-miss names. |
| `config/guards/db_structural_exceptions.yml` | Three one-for-one method-identity replacements, createCostBackup → runCostBackupExport. |
| `config/guards/db_structural_exceptions_expected_methods.yml` | Matching three expected-identity replacements; no reclassification into fixtures. |

New documentation: this handoff. No application/Kotlin file, existing campaign record, raw-query policy, baseline, recursive test selector, or guard registry was edited by this follow-up.

### No-broadening audit

- Each structural YAML document still has exactly 64 entries. The canonical split remains 60 expected and 4 fixtures; the pinned count is unchanged.
- Full-document text comparison, normalizing line endings only, shows each YAML differs solely by the three reviewed method-name replacements. Path, class, operation, reason, owner, linked issue, order, and remaining entries are retained.
- The three operations remain writableDatabase, getDatabasePath, and openDatabase in the same DatabaseBackupRepositoryImpl path/class. No wildcard, new operation, fixture exemption, or extra row was added.
- Existing duplicate, cardinality, source-evidence, trusted-scan, negative fixture, and immutable-classification tests remain required.
- The already validated active ownership policy and generated candidate/accounting pair remain byte-for-byte unchanged.
- `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07, and FG-23 apply. This read-back audit is not execution of the policy-delta verifier or protected-base/head guard modes; those approval/evidence gates are not claimed complete.

### New cases authored, not run

- Six valid generic-header cases: three parameter-list forms × braced/expression body; assertions pin the actual function name, body boundaries, operation containment and shared declaration lookup.
- Four malformed generic-header cases must not supply a declaration name.
- One comment/string/triple-string masking case must discover only the real generic function.
- One actual-policy contract pins the three operations and unchanged metadata across the structural file, expected manifest and canonical tuples, with no fixture reclassification.
- Two negative contracts reject the old wrapper name and a near-miss helper name without changing cardinality.

## 4. Snapshot and preservation

- Branch: `bug-fixes`; HEAD `a48076322e57f3d312f34cf6a71139efc4fcc48b`; index empty.
- Follow-up intake: `2026-09-27T17:27:11.2676317+03:00`, 154 dirty paths, fingerprint `8120fb469e10545affe67a78d6468b8d9506118c4924889941758f64ceca69e3`.
- Between the prior tested snapshot and this follow-up intake, only campaign JOURNAL.md bytes changed. The validator’s appended record is preserved, not rewritten.
- Follow-up source snapshot before this new report: `2026-09-27T17:46:48.9945672+03:00`, 157 dirty paths, fingerprint `f261483395b2dce217c1cbc255b663fd506e6018aa9de6d3803444896ecc6de9`.
- Of the 154 intake dirty paths, only the two identified test files received localized follow-up edits. The other 152 dirty paths are byte-for-byte preserved. Three previously clean files became dirty: the DB guard and the two structural manifests.
- Quiescence rechecked at `2026-09-27T17:46:50.7465806+03:00`: no active lock, RUNNING/STARTING result, or detected validation process; no colliding handoff existed.
- No subagents, live validation, artifact regeneration, staging, commit, reset, stash, branch switch, merge, rebase, or worktree operation was performed.

Creating this report adds one untracked path and changes the subsequent fingerprint. Associate later execution with its own start/end fingerprint, not merely branch/HEAD or the pre-report fingerprint below.

### Repair-file byte manifest before report creation

| Path | SHA-256 | Bytes |
|---|---|---:|
| `config/guards/db_structural_exceptions.yml` | `15bdb87d796470dd6a42db70e07dc0b2bbc153352e3a6050fec1b2e1a5a4cc36` | 26454 |
| `config/guards/db_structural_exceptions_expected_methods.yml` | `ed33a235204288647276bc6f9d263f438b5266ded99a618bbc42af47450b7c2f` | 28238 |
| `scripts/test_db_guard_room_inventory.py` | `db447c2d158aa0c5a320d72720855ac6aefb07bc0add3254426372e0aa4fc17a` | 154588 |
| `scripts/test_verify_db_access_boundaries.py` | `96b7b7651890d45abf75417507bf1902f41a490a8c199277c540fedd79732215` | 245717 |
| `scripts/verify_db_access_boundaries.py` | `11f997c3f027cd139b0a7ef7eacdfc7743f65cf519ecdcb8c81749bc5d74e379` | 180038 |

### Protected artifacts remain unchanged

| Path | SHA-256 |
|---|---|
| `config/guards/db_raw_query_classification.yml` | `93b47ee13fb8e88592966a5f255fdf6eb7b65eb474251df44983bceb5db11066` |
| `config/guards/db_ownership_policy.yml` | `597db4871df92c98e41946207ba2315c48039b14165838e3a0362a855a4308db` |
| `config/guards/db_ownership_policy.signatures.candidate.yml` | `d057d4fab03d31a13f030239e2958fb5aaa8939f68b179d95b73b75b41671017` |
| `config/guards/db_ownership_policy.signatures.accounting.json` | `d93da596d674ff5cfa44af687041dbd9736bb202c82ebcb3af7c86380a1b915b` |

## 5. Review and validation status

Author-only source review and post-write read-back are complete. Independent strict review and applicable guardian approval are not supplied by that self-review. No Python import/syntax check, pytest, Gradle, guard, or validation profile was executed by this agent. Reading and counting persisted log records is not a fresh execution.

**Follow-up validation: NOT RUN.** The previous run verified seven prior migration repairs and 44 of the prior 45 authored cases on fingerprint bc10689c…; it cannot verify these subsequent five-file changes or their 14 additional cases.

After the required review decision and while the entire tree is frozen, the human should run:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; inspect the persisted run." }
```

This preserves full recursive Python selection and the repository runner’s global serialization. guard_tests is a suite segment, not a registered GuardId. Do not run pytest/guards directly, narrow selection, or edit fingerprinted files while validation is running. If a run remains RUNNING/STARTING, poll that same run ID rather than start a duplicate.

The human must retain command, run ID, completion marker, exit/status, logs, HEAD, and matching start/end fingerprints; confirm the five currently failing nodes and all 14 added cases actually execute; and inspect every remaining guard result. No unknown, required skip, stale run, or infrastructure error is PASS. FG-23 protected-base/head and policy-delta evidence remain separate requirements; this document is not their substitute.

## 6. Wave-2 scope still not closed

This follow-up repairs the five-test DB handback in source; it does not declare all five now passing. The last executed suite still has five violations (time_boundaries, cancellation, event_writers, raw_money_aggregates, guard_tests) and two infrastructure errors (known_good_state, db_access). New execution must establish what remains after this repair; no blanket “pre-existing means waived” conclusion is made.

The generic receipt/PDF partial-OCR propagation requirement (W2-R3 / CA-P-03-003), WorkerRunLogger evidence, six missing CL-09 filter records, device coverage, required CI capture/self-protection evidence, and independent review/guardian decisions remain as recorded in the previous adjudication and handoffs. The static-guards rerun does not supply missing Kotlin/device runs. Commit remains a separate human decision. Wave 2 is not marked DONE or GREEN.

## Appendix A — exact pre-report git status

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
?? scripts/ci/test_guard_ratchet_occurrences.py
?? scripts/ci/test_wave2_guard_wiring.py
?? scripts/guardrails/cloud_payload_proof.py
?? scripts/guardrails/test_cloud_payload_proof.py
?? scripts/test_cloud_payload_guard_integration.py
?? scripts/test_verify_allowlist_compliance_fail_closed.py
?? scripts/test_verify_cancellation_allowlist_scope.py
?? scripts/test_verify_worker_entrypoint_proof.py
?? workflows/active/wave2-db-guard-repair-20260927-handoff.md
?? workflows/active/wave2-finalization-repair-20260927-batch1.md
?? workflows/active/wave2-independent-deep-review-20260927-100231Z.md
?? workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md
?? workflows/active/wave2-remaining-files.md
?? workflows/active/wave2-remaining-handoff.md
?? workflows/active/wave2-remaining-validation.md
?? workflows/active/wave2-validation-repair-20260927.md
```

## Appendix B — pre-report dirty-file byte manifest

Fingerprint: `f261483395b2dce217c1cbc255b663fd506e6018aa9de6d3803444896ecc6de9`.

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
| `config/guards/db_structural_exceptions.yml` | `15bdb87d796470dd6a42db70e07dc0b2bbc153352e3a6050fec1b2e1a5a4cc36` | 26454 |
| `config/guards/db_structural_exceptions_expected_methods.yml` | `ed33a235204288647276bc6f9d263f438b5266ded99a618bbc42af47450b7c2f` | 28238 |
| `docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md` | `711ee5bb1bdd8c56a14219cd4192546bb141079609ffc47c8d04fe5572b940ac` | 58736 |
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
| `scripts/test_db_guard_room_inventory.py` | `db447c2d158aa0c5a320d72720855ac6aefb07bc0add3254426372e0aa4fc17a` | 154588 |
| `scripts/test_db_guard_run_cache.py` | `0760e84989a427b471a9b5ebd3900c1e5a4fc36fbe2c641b8fe7fdd58ea8f0d3` | 16686 |
| `scripts/test_db_guard_sql_classifier.py` | `f9e278cd7028e19af539b9b06d5ac2abf56dac51f386b67414a7611bb8130e37` | 60463 |
| `scripts/test_migrate_db_policy_seed_rows.py` | `365791d881ecf505a5425d4e9ad52390e1958858714b8479752720f9a3d5368f` | 442929 |
| `scripts/test_migrate_db_policy_signatures.py` | `2775ff725b392bee68ac09545ed32eb5ca9885d2deb316bd9fd1ff4c16a9bc02` | 141848 |
| `scripts/test_verify_allowlist_compliance_fail_closed.py` | `6e1c1ae0b4f7a9ba9773492e50de4942653b25b3e3c338caaf755b42f129490c` | 3535 |
| `scripts/test_verify_cancellation_allowlist_scope.py` | `fed4dac4efa53b78ca73e166da3bd68039da7a688fd82b0c68843f1144b1d202` | 6144 |
| `scripts/test_verify_cancellation_boundaries.py` | `24ab49a27c7e018ea07b29b5bc6a2033301eb4d089d185a00babc14d81941ffa` | 26052 |
| `scripts/test_verify_cloud_payload_boundaries.py` | `69c0e25fb1ad784c8b194ed576cc760a5f2c01daed007ea5bdb1e5ac97407396` | 12028 |
| `scripts/test_verify_db_access_boundaries.py` | `96b7b7651890d45abf75417507bf1902f41a490a8c199277c540fedd79732215` | 245717 |
| `scripts/test_verify_worker_entrypoint_proof.py` | `2c34a9d7a76ebc568faea57b4897f3f47dbbc17b46b5b71eab466c6d54025569` | 4521 |
| `scripts/verify_allowlist_compliance.py` | `c5d13efad18e82b233d8f46002b28b147e390edb6f9afb561d282b512d8946fb` | 18069 |
| `scripts/verify_cancellation_boundaries.py` | `671db49813976969db18d99a6cc3278c045c75083f379f5554cf4606744af45c` | 22731 |
| `scripts/verify_cloud_payload_boundaries.py` | `49ab0d18f0203878fce46819162c7463f9b44b5820f6f07f60027f7712398426` | 10878 |
| `scripts/verify_db_access_boundaries.py` | `11f997c3f027cd139b0a7ef7eacdfc7743f65cf519ecdcb8c81749bc5d74e379` | 180038 |
| `scripts/verify_event_writers.py` | `18fa0e0d90eef570dcb081926c0d45b1037d2f0e8fccfccf42c33d9673fcfa4a` | 11761 |
| `scripts/verify_privacy_boundaries.py` | `6f73f3bea2ce48f88832c47dbe0640095bbf2b46a73c768452ed8daa6c0309d1` | 25500 |
| `scripts/verify_worker_boundaries.py` | `f40564c1e3441677f5569f4c57f4fbd23e875278337e324d10424ab63976eb0e` | 26027 |
| `workflows/active/wave2-db-guard-repair-20260927-handoff.md` | `051f6db09fe4ff5fe3b302e35779070270c6b39663c8f4dbf1637d52a4e83cf6` | 59438 |
| `workflows/active/wave2-finalization-repair-20260927-batch1.md` | `31e41d8f72a55d9ccef924a7655756b5020f0f2635536b064ade2f3fabd51d66` | 15234 |
| `workflows/active/wave2-independent-deep-review-20260927-100231Z.md` | `a6c17cd4081dee93b351bbc167223eb9fcbf4db83ce382f4f2072bd1a96dc83d` | 169752 |
| `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md` | `6b9d75c287c23a917d80c1262dc96241de0bb0f3e2c3de5d6002d0c046100e76` | 60453 |
| `workflows/active/wave2-remaining-files.md` | `1bd01939a0e4e30fce314890678162daeb4fd96fdba06aa735cf1113a0fe0456` | 11480 |
| `workflows/active/wave2-remaining-handoff.md` | `0d47563109df0e6f37559ab670e56fb10e8bf1c2bf56df492f66ca525d88ef48` | 10535 |
| `workflows/active/wave2-remaining-validation.md` | `3fe0ec8e4e6e83a65dbbd5ce9d3596476ed0080d527259687646fa8b6fdcfc7c` | 8707 |
| `workflows/active/wave2-validation-repair-20260927.md` | `b95431469690db4523310e2383d37c89bdf21471d79e5e1126cec3a317362156` | 11022 |

