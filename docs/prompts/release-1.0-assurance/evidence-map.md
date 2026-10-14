# Source/test/guard evidence map - initial dispatch guide

Read-only scoping on 2026-10-13, not a completed audit. Re-resolve paths at the
accepted candidate; the current tree is still mixed Wave 5 work. Test presence
is not adequate or passing coverage. Current source beats old map method lists.

## Primary owner seeds

Owners below are relative to app/src/main/java/com/yourname/expensetracker/.
Test filters are proposals, NOT RUN. Use the wrapper for all execution.

| Cell | Actual owner seed | Existing test seeds | Relevant registered guards |
| --- | --- | --- | --- |
| E-01 money | domain/currency/CurrencyConverter.kt -> data/repository/MultiCurrencyRepository.kt -> domain/core/money/ | *CurrencyConverter*, *MoneyAggregate*, *MultiCurrencyRepository*, *MulticurrencyAnalyticsDashboardBudgetGoldenTest* | money, raw_money_aggregates |
| E-01 time | injected TimeProvider -> period windows and scheduling | *TimePeriod*, *PeriodRange*, *BlockPartyDst*, *RecurringOccurrence* | time_boundaries, cancellation |
| P-02 lifecycle | domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt -> ExpenseDao.insertAtomic -> events/post-commit | *TransactionLifecycleCoordinator*, *TransactionLifecycleCoordinatorDbContractTest*, *TransactionLifecycleFullContractGoldenTest* | db_access, event_writers, source_provenance, ui_dao |
| P-03 receipt | domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt -> ReceiptRepository/link/match services | *ReceiptLifecycleDbContractTest*, *ReceiptPartialOcrPipelineTest*, *ReceiptMatchingWorkerTest*, *ReceiptMatchingNoDoubleCountGoldenTest* | receipt_link, db_access, privacy, cancellation |
| P-09/I-01 runtime | domain/workers/WorkerExecutionGuard.kt + data/backup/DatabaseWriteBarrier.kt + startup/leases/logger | *WorkerExecutionGuardTest*, *WorkerRunLoggerTest*, *WorkerBarrierIntegrationTest*, *WorkerRestoreBarrierIdempotencyGoldenTest* | worker, cancellation, db_access |
| I-02 schema | data/database/AppDatabase.kt -> DatabaseSchemaPolicy/migrations -> entities/DAOs | *Migration*, connected DatabaseMigrationMatrixTest/MigrationContractTest/fresh-install tests | migration_matrix, db_access, db_artifact_sync |
| P-07 restore | data/repository/DatabaseBackupRepositoryImpl.kt + data/backup/BackupVerifier.kt/journal/barrier | *BackupRestoreContractTest*, *BackupRestoreMoneyIntegrityScenarioTest*, *BackupVerifier*, *BackupRestoreRoundtripGoldenTest* | db_access, privacy, cancellation |
| Savings owner | data/repository/SavingsSweepAcceptanceCoordinator.kt -> balance/plan/ledger DAOs -> history -> backup | *SavingsSweepAcceptanceCoordinatorTest*, *SavingsContributionHistoryRepositoryTest*, *SavingsGoalsViewModelTest*, *BackupVerifierSavingsLedgerTest* | db_access, money, cancellation, migration_matrix |
| P-08 privacy/AI | domain/privacy/ gates + cloud policy/prepared payload + HybridRouter | *PrivacyGate*, *CloudProviderTransportPayloadTest*, *PrivacyDoNotStoreTest*, *PrivacyGateEnforcementGoldenTest* | privacy, cloud_payload, pii_logging |
| P-12 export | accounting export policy/repository + domain/export/AccountantReportPdfExporter.kt | *AccountantReportPdfMoneyTest*, *CsvExportImportRoundtripGoldenTest*, *ExportOptionsViewModel*, connected PDF | import_lifecycle, privacy, money, cancellation |
| I-05 UI/routes | ui/navigation/NavigationDestination.kt -> controller/feature config/deep link/persistence policy -> screens/VMs | *NavigationRouteContractTest*, *NavigationControllerBehaviorTest*, *DestinationPersistencePolicyTest*, *DeepLinkParserTest* | ui_dao, pii_logging, di_release |

Historical campaign paths sometimes place CurrencyConverter in domain/core/money
or abbreviate transaction paths. The current converter is domain/currency/
CurrencyConverter.kt; the lifecycle coordinator is domain/transaction/lifecycle/
TransactionLifecycleCoordinator.kt. Failed lookup means resolve the exact name,
not report a nonexistent-file defect.

## Test names are not proof boundaries

Scoped source inspection identified useful existing evidence, not test verdicts:

- SavingsSweepAcceptanceCoordinatorTest builds real in-memory Room, uses actual
  DAOs and controls the barrier/time collaborators. Useful for atomicity and
  idempotency; not proof of real restore exclusion or process-death recovery.
- MulticurrencyAnalyticsDashboardBudgetGoldenTest wires real rates/converter/
  repository with its DB fixture. Review its oracle and partial/unavailable
  semantics instead of replacing it with only mocks.
- NotificationExpenseDashboardE2ETest explicitly tests parser -> DB -> totals,
  not the full notification processing pipeline. Keep that useful test, but do
  not treat its name as capture/consent/intake/worker/review/lifecycle/UI proof.
  Inspect other pipeline and golden tests before authoring missing boundaries.

Tag each test as pure unit, mock contract, real Room integration, production
orchestration, connected device, or manual artifact smoke. Inspect inherited
fixtures/helpers and independent expected values. Guards detect selected
patterns, not every plausible but semantically wrong calculation.

## Existing serial shard names

Source: config/validation/test-shards.json.

- architecture-contracts: architecture, consistency, contracts, guard(s), verification
- data: repositories, providers, persistence, backups
- domain-a-m: money, AI, analytics, budget, business/carbon/cashflow, groups,
  forecasting/health, investment/location, logic and models
- domain-n-z: notification/parser/privacy/provenance/receipt/recurring/reminder,
  savings/split/subscription/tax/transaction/usecase/util/widget/workers
- runtime-ui: services, receivers, startup, UI, metrics, workers and utilities
- integration-golden: e2e, golden, integration and scenarios

Verify packages/root-package tests before calling shards exhaustive. Record
overlaps, executed XML cases, skips/failures and cache honestly. trusted-tests
is not the full suite; legacy/hang suspects remain in the required inventory.

## Registered guards and external evidence

Source: scripts/ci/guard_registry.py; reference policy via
FINAL_CI_GUARD_ACCEPTANCE_GATE.md + FG-ID only.

- Ownership/lifecycle: db_access, ui_dao, source_provenance, receipt_link,
  import_lifecycle, event_writers.
- Money/time/runtime: money, raw_money_aggregates, time_boundaries, worker,
  cancellation.
- Privacy/DI: privacy, cloud_payload, pii_logging, di_release.
- Infrastructure: allowlist_compliance, migration_matrix, ignored_test_budget,
  lint_baseline_policy, deprecation_escalations, db_artifact_sync,
  known_good_state, guard_docs_truth.

currency_guardrails_ps and release_artifact are declared external surfaces,
not static-suite passes. registered-guard rejects external engines: do not
invent -GuardId release_artifact execution. Existing verifier
scripts/verify_release_artifact.py and CI release-check already define the
artifact contract. Reuse verified CI evidence or approve a narrow durable runner
integration for local execution; do not reimplement or execute it directly.

Inspect existing ratchet/allowlist debt. Guard PASS does not mean zero baseline
issues, and baseline growth/exception broadening cannot conceal release risks.

## Logical bounded-slice order

1. Candidate, shipped scope, earlier-wave deferrals, original fix closure, guard
   debt and required evidence inventory.
2. Shared owners: E-01 money/time, I-02 persistence, P-07 restore, P-02/03
   mutations/provenance, P-09 runtime and P-08 privacy.
3. Consumers: E-02/P-05/P-06 analytics/dashboard/budget/forecast/health/savings;
   P-01/P-04/P-10/P-11/P-12 intake/recurrence/bank/email/export.
4. E-03/E-04/E-05 and other shipped surfaces: categorization, groups,
   investment/tax, warranty/subscriptions/offers, challenges/carbon/location,
   AI/search/nudges, actual UI/DI/helpers.
5. Real device journeys, optimized artifact, final I-04/CI evidence, independent
   recommendation, then human release decision.

This is a Phase 0/1 proposal, not authority to run the entire campaign. At most
two independent read-only slices; writing and validation remain serial.
