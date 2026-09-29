# Remaining Wave-2 — human-run validation plan

Status as of September 27, 2026: human validation is partial; the new repair reruns are NOT RUN. See wave2-validation-repair-20260927.md for durable run IDs, the receipt baseline correction, collection repair and six-edit source review. The 54-class inventory below is still the original command plan, not 54 passing classes. Starting checkout: bug-fixes at a48076322e57f3d312f34cf6a71139efc4fcc48b, with uncommitted changes listed in wave2-remaining-files.md.

## Preconditions and recording

1. The UI DAO allowlist decision is resolved: retain the authorized explicit empty list and isolated PASS vr-20260927-075548-69c06619. No permission for other exemptions, baseline growth, skipped guards or converting infrastructure failure into PASS is implied.
2. Obtain required strict-review/guardian decisions before live validation. No independent approval or new waiver has been supplied by this session.
3. Keep the worktree quiescent. Check existing runner state and active Gradle/validation processes before starting. The blocking wrapper still delegates execution to the sole validation-runner owner; do not invoke Gradle, pytest, lint or guards directly.
4. Run one command at a time. Stop on any non-PASS result. A RUNNING/STARTING result must be polled, never launched again. Missing, stale, timed-out or infrastructure-error evidence is not PASS.
5. For every run record exact command, run ID, status/exit code, worktree fingerprint, and build/validation-runs/<run-id>/result.json, stdout.log and stderr.log. Previous human-run artifacts are recorded in the table below; no post-repair run exists yet.

Runner inventory command (does not launch a build):

    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action List

After prerequisites, the exact compile command from the repository root is:

    pwsh scripts/vrun.ps1 -Worktree . -Profile compile

## Targeted Kotlin checks

For each class below, the exact command form is:

    pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*WorkerRunLoggerTest'

Replace only the class suffix with the listed target. Alternatively run ONE chosen array below through this serial, stop-on-failure loop. This document does not execute it:

    foreach ($class in $targets) {
        pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter ('*' + $class)
        if ($LASTEXITCODE -ne 0) { throw ('Validation stopped at ' + $class) }
    }

### CL-23 and existing worker invariants

    $targets = @(
        'WorkerRunLoggerTest',
        'WorkerLeaseRegistryTest',
        'WorkerRestoreRegressionTest',
        'WorkerExecutionGuardTest',
        'MaintenanceOperationRunnerTest',
        'DataRetentionWorkerTest'
    )

### CL-09 lifecycle and stale-write boundaries

    $targets = @(
        'ReceiptMatchLifecycleServiceTest',
        'ReceiptMatchingWorkerTest',
        'RecurringRuleLifecycleCoordinatorTest',
        'ExpenseRepositoryMerchantKeyBackfillTest',
        'MerchantKeyBackfillWorkerTest',
        'WarrantyReminderDeliveryDaoTest',
        'WarrantyExpirationWorkerTest',
        'ReceiptMatchingViewModelTest',
        'ReceiptLinkServiceColumnScopeTest'
    )

### CL-21 and A2 privacy/routing contracts

    $targets = @(
        'CloudQueryPreparedPayloadTest',
        'CloudQueryInterpretationServiceTest',
        'HybridRouterIntegrationTest',
        'HybridRouterTest',
        'HybridServiceDelegationTest',
        'HybridReceiptItemCategorizationServiceTest',
        'PrivacyDeniedExceptionTest',
        'PrivacyCapabilityPolicyTest',
        'CompositePrivacyGateTest',
        'BackupPrivacyGateOwnershipTest',
        'KeystoreInstallationSecretHashingTest'
    )

The overlapping backup repository/ViewModel targets appear once below; their final-state results must cover both A2 and CL-15.

### CL-15 failure semantics and consumers

    $targets = @(
        'MoneyAggregateBuilderRestrictionTest',
        'CsvImportRfc4180Test',
        'BackupVerifierRequiredSemanticQueryTest',
        'DatabaseBackupRepositoryImplTest',
        'OcrResultPartialTest',
        'ReceiptOcrRetryIsolationTest',
        'BankStatementCompletionStatusTest',
        'ComputeMoneyRadarUseCaseTest',
        'FinancialStressForecastEngineTest',
        'ComputeDashboardWidgetsUseCasePaceWiringTest',
        'BackupRestoreViewModelPrivacyDenialTest',
        'ReviewViewModelPrivacyDenialTest',
        'DebugViewModelDatabaseStatsTest',
        'BudgetForecastingEngineDiagnosticsTest'
    )

### A7 displayed group settlement balances

    $targets = @(
        'GroupSettlementBalancePolicyTest',
        'SharedExpenseManagerSettlementTest',
        'SharedExpenseSettlementReadTest',
        'GroupsRepositoryImplTest',
        'SharedExpenseGroupsViewModelTest',
        'GroupTransactionCoordinatorTest',
        'GroupBalanceCalculatorTest',
        'SharedExpenseManagerTest',
        'GroupSettlementPipelineTest',
        'NotificationExpenseDashboardPipelineTest'
    )

### CL-22 Kotlin guard and provider acceptance

    $targets = @(
        'WorkerEntryPointProofTest',
        'WorkerGuardArchitectureGuardTest',
        'CloudProviderTransportPayloadTest',
        'CloudProviderPreparedPayloadTest'
    )

The provider transport tests cover nine invoked paths, including receipt image-policy and bank-statement text variants. The older prepared-payload tests remain policy-unit coverage, not a substitute for captured HTTP assertions.

## Full static guards and Python regression surface

Run the canonical profile only through the wrapper:

    pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards

The authored suite/capture wiring recursively selects scripts, including new proof/wiring/ratchet/input fixtures and existing nested barrier, source-scope and policy tests. Confirm the durable manifest and logs actually include that surface and each required guard. No Python test was run during implementation. Do not call a subset PASS a full-suite result; FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/FG-06/FG-07/FG-23 apply.

Required new/updated regression modules include:

- scripts/guardrails/test_cloud_payload_proof.py
- scripts/test_cloud_payload_guard_integration.py
- scripts/test_verify_worker_entrypoint_proof.py
- scripts/test_verify_cancellation_allowlist_scope.py
- scripts/test_verify_allowlist_compliance_fail_closed.py
- scripts/ci/test_guard_ratchet_occurrences.py
- scripts/ci/test_wave2_guard_wiring.py
- scripts/ci/test_run_static_guard_suite.py
- scripts/ci/test_capture_db_guard_evidence.py

Record every newly exposed violation with its rule, source anchor and whether it is a regression, pre-existing debt or unsupported proof shape. No authority to change baselines/allowlists is supplied by this handoff.

## Additional evidence, not automatically scheduled

- MerchantKeyBackfillWorker instrumented fixtures require a device-enabled connected-tests runner session. Unit-test filters do not execute app/src/androidTest.
- Device PDF/image recognition remains the separately recorded follow-up; metadata/helper/ViewModel tests do not prove page rendering or the complete OCR/Room transaction.
- CI evidence capture must run with a real reviewed comparison-base SHA and run SHA, twice through the existing workflow. Static source wiring tests alone do not demonstrate successful remote CI captures or byte-identical summaries.
- Broader serial shards, lint, app-check or connected-tests may be requested after targeted results and human cost approval. No full-suite recovery or green-baseline claim is implied by the targeted list.

## Evidence record at handoff

| Check | Result | Run ID / logs |
|---|---|---|
| Scoped tracked-file git diff --check | PASS, whitespace only | Shell exit 0; not a validation-runner result |
| Production compile before repair | PASS | vr-20260927-065201-c65ae48d |
| Targeted Kotlin sweep before repair | PARTIAL: five worker classes PASS; receipt identity case FAIL; logger adjudication separate | See current repair report; not a complete sweep |
| Python guard tests before repair | COLLECTION FAILURE; no execution result | guard_tests.log in vr-20260927-074529 suite output |
| Full static guards before repair | FAIL: 16 pass, five violations, four infra | vr-20260927-074529-57e4e7b1 |
| Authorized allowlist isolated rerun | PASS | vr-20260927-075548-69c06619; does not replace full-suite evidence |
| Receipt fixture / collection repair reruns | NOT RUN | None; exact commands in current repair report |
| Device tests / CI capture | NOT RUN | None |
| Independent strict and guardian gates | PENDING | None |
