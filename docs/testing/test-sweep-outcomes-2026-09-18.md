# Test Suite Sweep Outcomes — 2026-09-18

> Measurement report only — **no fixes were applied** in this sweep. All evidence is in durable logs under `build/validation-runs/<run-id>/stdout.log` (+ `result.json`). Status wording: **measured** = executed and observed; **predicted** = static analysis only. Companion doc: `docs/testing/test-suite-groups-2026-09.md` (group definitions). Historical context only: `TEST_FAILURE_LEDGER.md`.

## Method

Static triage first (6 agents over ~678 test files), then serialized live validation through `validation-runner` (shards, targeted follow-ups, full `unit-tests` gate, `static-guards`, `migration-tests`), then log-based root-cause classification from the durable per-run logs. No fixes were applied — this is a measurement + diagnosis report.

## Executive summary

- Suite: ~663 JVM test files + 28 device test files. Full-suite JVM gate CANNOT complete: stalls in the `data.repository` zone (measured twice) and JVM fork OOM/instrument crashes.
- Approximate measured JVM totals across runs: ~5,000+ test executions observed passing, ~330 distinct failing tests measured (deduped by class), 96 SKIPPED (@Ignore) in the full gate, 35 @Ignore sites total in suite.
- 4 confirmed hang sites (2 new), 2 infra defects (fork OOM crashes; `migration-tests` profile wildcard broken), 1 privacy-relevant finding (CLOUD_AI_RECEIPT_OCR policy map gap), 1 real migration-chain gap candidate (`pipeline_diagnostic_events` in 119→121 path), 1 money-math red flag (MoneyTest split-sum).
- Top cross-cutting root cause: relaxed-mock `database.withTransaction` harness pattern → explains ~33 ClassCastException failures AND the hang family.

## Run ledger

| Run ID | Profile/Filter | Result | Measured outcome |
|---|---|---|---|
| vr-20260918-071835-3c3eb532 | legacy-tests | TIMEOUT | hang at TransactionLifecycleCoordinatorTest; ExportReadBarrierTest 3F; ReceiptLifecycleCoordinatorTest 6F |
| vr-20260918-073422-da5ac03b | unit-test-shard architecture-contracts | FAIL | 45F / 9 classes; 349 passed |
| vr-20260918-074329-4ed6383c | unit-test-shard data | TIMEOUT | 867 passed, ~100F; stall after NotificationProcessingPipelineStressTest |
| vr-20260918-080853-97610467 | targeted R* | FAIL | ReviewQueueRepositoryTest 10F |
| vr-20260918-081241-82e778c8 | targeted S* | FAIL | SavingsContributionHistoryRepositoryTest 1F (DataStore) |
| vr-20260918-081633-9fa219cb | targeted W* | FAIL | fork JVM crash (instrument assertion + OOM) |
| vr-20260918-082037-8ec8f982 | targeted WarrantyTrackerRepositoryTest | TIMEOUT | isolated hang + OOM |
| vr-20260918-083813-125d02b7 | unit-test-shard domain-a-m | FAIL | 137F / 54 classes; 1393 passed |
| vr-20260918-084331-d0d1825f | unit-test-shard domain-n-z | TIMEOUT | stall after NegotiationEngineTest |
| vr-20260918-090110-8b3e40fd | targeted domain.notification.* | PASS | 43 passed |
| vr-20260918-090730-2c6329b0 | targeted NegotiationEngineTest | TIMEOUT | isolated hang (1 test passes then silence) |
| vr-20260918-092405-d36859b2 | targeted domain.parser.* | PASS | 179 passed |
| vr-20260918-092721-d0e0ca0d | targeted domain.privacy.* | FAIL | 3F (PrivacyCapabilityHandlingPolicyTest 2, UPR5CompletionTest 1) |
| vr-20260918-093043-2fd8bf7d | targeted domain.receipt.* | FAIL | 28F (BankStatementParser 15, ReceiptLifecycleCoordinator 7, ReceiptMatchLifecycle 3, ReceiptParser 2, WarrantyTextExtractor 1) |
| vr-20260918-093508-7bf5e5eb | targeted domain.recurring.* | FAIL | 2F (UncompletedCoroutinesError) |
| vr-20260918-093822-7ff79c28 | targeted domain.transaction.* | TIMEOUT | stall after CategoryAssignmentServiceBarrierTest |
| vr-20260918-094946-aad0b1ac | targeted TransactionLifecycleCoordinatorTest | TIMEOUT | isolated hang confirmed |
| vr-20260918-100131-6199f3b0 | targeted domain.usecase.* | FAIL | 4F |
| vr-20260918-100621-83ef6f6a | targeted domain.util.* | FAIL | 4F (AmountUtils 1, Money 3) |
| vr-20260918-101039-c0e251a2 | targeted domain.workers.* | PASS | 224 passed |
| vr-20260918-101451-63f3bee2 | targeted domain.tax.* | FAIL | 1F (TaxEstimator) |
| vr-20260918-101804-322e300b | unit-test-shard runtime-ui | TIMEOUT | w/ OOM; 577 passed, ~100F |
| vr-20260918-105048-084d2da2 | targeted ui.screens.map.* | FAIL | 6F (Turbine 3s) |
| vr-20260918-105510-5a73196d | unit-test-shard integration-golden | TIMEOUT | w/ OOM; 12 passed, ~47F / 30 classes |
| vr-20260918-111954-aa46a702 | targeted AnalyticsPipelineTest | FAIL | 3F |
| vr-20260918-112409-e045ecba | targeted Pipeline4LifecycleGoldenTest | FAIL | 3F |
| vr-20260918-112900-288f667e | static-guards | FAIL | 11/25 pass, 12 violations, 2 infra errors; guard_tests pytest 41F/1820P |
| vr-20260918-113652-02e4035a | migration-tests | INFRA-DEFECT | `*Migration*` wildcard expanded to markdown filename; no tests ran |
| vr-20260918-114538-5aaac0e9 | targeted *MigrationRegistration* | FAIL | MigrationRegistrationTest 5F |
| vr-20260918-114955-94f2330d | targeted *DatabaseMigrationProof* | PASS | 3 passed (1 skipped) |
| vr-20260918-115441-5778820e | unit-tests (full) | TIMEOUT | 1109 passed, 77F, 96 skipped before stall |
| vr-20260918-122601-14116d36 | targeted ReviewQueueRepositoryTest | FAIL | 11F |
| vr-20260918-123020-8d26aee5 | targeted NotificationProcessingPipelineReliabilityTest | FAIL | 9F |
| vr-20260918-123440-9a319bc0 | targeted DatabaseBackupRepositoryImplTest | FAIL | 6F |
| vr-20260918-123904-e4d3b551 | targeted NotificationProcessingPipelineAtomicityTest | FAIL | 7F |
| vr-20260918-124326-aa72ddd2 | targeted HomeViewModelRecommendationTest | PASS | 24 passed (shard failure was contamination) |
| vr-20260918-124743-fd517c53 | targeted ExportOptionsViewModelTest | TIMEOUT | OOM (9F then heap death) |
| vr-20260918-130755-dbb383c1 | targeted WarrantyExpirationWorkerTest | FAIL | 13F (guard API stub mismatch) |
| vr-20260918-131218-24eef11f | targeted BillReminderWorkerTest | FAIL | 3F |
| vr-20260918-131651-57af64a2 | targeted AppStartupCoordinatorRecoveryTest | PASS | 9 passed (shard failure was contamination) |
| vr-20260918-132120-48b45cc6 | targeted P7BugFixesTest | FAIL | 4F |
| vr-20260918-132539-147e4355 | targeted MaintenanceOperationRunnerTest | FAIL | 4F |
| vr-20260918-133022-5d9b0f22 | targeted CloudDashboardBriefingServiceTest | FAIL | 5F |
| vr-20260918-133507-ec743b24 | targeted DefaultAiEnvironmentMonitorTest | FAIL | 5F (MlKit) |
| vr-20260918-133929-27206839 | targeted GroupTransactionCoordinatorTest | FAIL | 3F |
| vr-20260918-134354-3c313c88 | targeted ExportReadBarrierTest | FAIL | 4F |
| vr-20260918-134820-34468253 | targeted DatabaseBarrierTest | FAIL | 2F |

## Group outcomes

Skipped: 96 observed in the full gate (vr-115441); per-group skip counts not separately measured. "n/m" = not measured (run tail cut off).

| Group | Run(s) | Passed | Failed (deduped classes) | Hang/stall | Notable classes |
|---|---|---|---|---|---|
| GROUP-A | vr-073422 (architecture-contracts) | 349 | 45F / 9 classes | none observed | guard/consistency fixtures — see core table (metrics/diagnostics files ran under the runtime-ui shard per groups doc) |
| GROUP-B | vr-074329 (data) | 867 | ~100F (tail unmeasured) | STALL after NotificationProcessingPipelineStressTest | ReviewQueueRepositoryTest 11, NotificationProcessingPipelineReliabilityTest 9, NotificationProcessingPipelineAtomicityTest 7, DatabaseBackupRepositoryImplTest 6; ~80 failures not individually reproduced |
| GROUP-C | vr-083813 (domain-a-m) | 1393 | 137F / 54 classes | none observed | DefaultAiCapabilityRouterTest 18, InvestmentTrackerTest 9, ExpenseCategoryClassifierTest 7, BudgetMonitor* 8, FinancialStressForecastEngineTest 5, BankApiIntegrationTest 4 |
| GROUP-D | vr-084331 (domain-n-z) + targeted follow-ups | n/m (stall cut tail) | receipt 28F, privacy 3F, usecase 4F, util 4F, recurring 2F, tax 1F via targeted runs | NegotiationEngineTest + TransactionLifecycleCoordinatorTest isolated hangs | BankStatementParserTest 15, ReceiptLifecycleCoordinatorTest 7, MoneyTest 3; clean targeted: notification 43P, parser 179P, workers 224P |
| GROUP-E | vr-101804 (runtime-ui) + follow-ups | 577 | ~100F (OOM tail) | TIMEOUT w/ OOM | SpendingMap family (Turbine), ExportOptionsViewModelTest 9F→OOM; HomeViewModelRecommendationTest / AppStartupCoordinatorRecoveryTest shard failures were contamination (isolated PASS 24 / 9) |
| GROUP-F | vr-105510 (integration-golden) + follow-ups | 12 | ~47F / 30 classes (OOM-inflated) | TIMEOUT w/ OOM | only AnalyticsPipelineTest 3 + Pipeline4LifecycleGoldenTest 3 isolated-confirmed; GoldenMasterVerificationTest 4, CrossGroupIntegrationTest 2 measured; cascade ~40F across ~25 classes |
| GROUP-G | full gate only (vr-115441) | n/m | CanonicalMultiCurrencyFixtureTest 2F | none observed | orphan fixture test, only runnable in unfiltered runs; remaining GROUP-G files are fixtures with 0 @Test |
| GROUP-H | not run | — | — | — | device required; 28 files; historical audit says ~25 failures expected when run manually |

## Failing classes by root cause (the core table)

F counts are measured failure observations, deduped by class; `a of b` = attributed subset of that class's measured failures. `n/p` = not pinned to a single run (class sits in a shard whose tail was unmeasured, or was not individually triaged); otherwise evidence is the isolated follow-up run or the shard covering the package.

| Class | F | Category | Likely reason | Evidence run |
|---|---|---|---|---|
| ReviewQueueRepositoryTest | 11 | TEST-INFRA | relaxed `withTransaction` → CCE `Object`→`ReviewApprovalTxOutcome` | vr-122601 |
| NotificationProcessingPipelineReliabilityTest | 9 | TEST-INFRA | same pattern → CCE `Object`→`ParsedDbOutcome` | vr-123020 |
| NotificationProcessingPipelineAtomicityTest | 7 | TEST-INFRA | same pattern → no outcome tracked | vr-123904 |
| ReceiptLifecycleCoordinatorTest | 7 | TEST-INFRA | same family via `stubTransactionRunnerExecutesBlocks` | vr-093043 |
| DefaultAiCapabilityRouterTest | 18 | TEST-INFRA | MockK relaxed + default-arg synthetic → `timeProvider` NPE at `AiRuntimeDiagnostics.kt:23` | vr-083813 |
| InvestmentTrackerTest | 9 | TEST-INFRA | `resolveHomeCurrency` not stubbed → empty flow | vr-083813 |
| DefaultAiEnvironmentMonitorTest | 5 | TEST-INFRA | MlKit untestable on JVM | vr-133507 |
| ReceiptParserTest | 2 | TEST-INFRA | `TimeProvider.now` not stubbed | vr-093043 |
| SpendingMapHeatmapFilterTest + SpendingMapViewModelStressTest | 2 + 4 | TEST-INFRA | Turbine 3s; VM IO dispatcher vs test scheduler | vr-105048 |
| SavingsContributionHistoryRepositoryTest | 1 | TEST-INFRA | DataStore same-file | vr-081241 |
| MoneyBoundaryGuardTest | 9 | TEST-INFRA | `DB_SOURCE_ROOT_UNDECLARED` env | n/p |
| CrossSourceVerificationTest | ~2 | TEST-INFRA | `final val activeBudgets` backing-field write | n/p |
| OnDeviceReceiptAssistServiceTest | 1 | TEST-INFRA | mechanism n/p | n/p |
| AiChatRepositoryImplTest | 1 | TEST-INFRA | UncompletedCoroutinesError | vr-074329 |
| RecurringLifecycleCoordinatorTest | 2 | TEST-INFRA | UncompletedCoroutinesError; mechanism unpinned | vr-093508 |
| P7BugFixesTest | 1 of 4 | TEST-INFRA | WorkManager not initialized in Robolectric | vr-132120 |
| WarrantyExpirationWorkerTest | 13 | TEST-STALE | `runGuarded`→`runGuardedWithContext` API migration | vr-130755 |
| BankStatementParserTest | 15 | TEST-STALE | parser emits 2 transactions where 1 expected + date drift | vr-093043 |
| DbGuardPolicyFixtureTest | 19 | TEST-STALE | counts 471→407, 62→64, DAO renames | n/p |
| ConstantsConsistencyTest | 2 | TEST-STALE | `DFS_ITERATION_LIMIT` static→ctor param | n/p |
| CloudDashboardBriefingServiceTest | 5 | TEST-STALE | fail-closed resolver wired in test ctor → attempts 0 | vr-133022 |
| PrivacyCapabilityHandlingPolicyTest + UPR5CompletionTest | 2 + 1 | TEST-STALE | CLOUD_AI_RECEIPT_OCR new capability (privacy-relevant) | vr-092721 |
| BillReminderWorkerTest | 3 | TEST-STALE | `getDueReminders`→`recoverAndGetDueReminders` | vr-131218 |
| SharedExpenseTest | 2 | TEST-STALE | validation order changed | n/p |
| AmountUtilsTest | 1 | TEST-STALE | comma-decimal now accepted | vr-100621 |
| MigrationRegistrationTest | 4 of 5 | TEST-STALE | schema history drift; v145 baseline policy | vr-114538 |
| GroupTransactionCoordinatorTest | 2 of 3 | TEST-STALE | isCurrentUser fixture | vr-133929 |
| GroupsRepositoryImplTest | 2 | TEST-STALE | same family | n/p |
| EmptyZeroNullResilienceTest | 1 | TEST-STALE | 75 vs 50 default | n/p |
| AnalyticsRepositoryAggregateTest | 1 | TEST-STALE | warning redaction? | n/p |
| RecurringOccurrenceDaoTest | 1 | TEST-STALE | boundary inclusive/exclusive | n/p |
| DatabaseBarrierTest | 2 | TEST-BUG | nested `runTest` inside `assertThrows` → ISE "single call to runTest" | vr-134820 |
| ExportReadBarrierTest | 2 of 4 | TEST-BUG | nested `runTest` inside `assertThrows` (same) | vr-134354 |
| ExchangeRateStoreAdapterTest | 1 | TEST-BUG | nested `runTest` inside `assertThrows` (same) | n/p |
| MaintenanceOperationRunnerTest | 2 of 4 | TEST-BUG | nested `runTest` inside `assertThrows` (same) | vr-132539 |
| ExportReadBarrierTest | 2 of 4 | TEST-BUG | `toList` on never-completing `flatMapLatest` flow → UncompletedCoroutinesError | vr-134354 |
| CanonicalMultiCurrencyFixtureTest | 2 | TEST-BUG | fixture stamps `lastUpdated=currentTimeMillis` vs frozen FakeTimeProvider → 24h staleness gate rejects → 50.0 not 142.0 | vr-115441 |
| MaintenanceOperationRunnerTest | 1 of 4 | TEST-BUG | drain timeout policy default FAIL_OPERATION vs expected PROCEED_WITH_WARNING | vr-132539 |
| MigrationRegistrationTest | 1 of 5 | PROD-REGRESSION-CANDIDATE | `pipeline_diagnostic_events` missing from 119→121 migration path vs 121.json | vr-114538 |
| PrivacyStorageContractTest | 1 | PROD-REGRESSION-CANDIDATE | `RawStorageMode` non-exhaustive `when` in ReceiptRepository.kt / ReceiptDebugExporter.kt | n/p |
| GoldenMasterVerificationTest | 4 | PROD-REGRESSION-CANDIDATE | numeric parity (e.g. 3738.49 vs 738.49, delta 3000.0) | vr-105510 |
| MoneyTest | 3 | PROD-REGRESSION-CANDIDATE | split-sum 99.99 vs 100.0 — money math | vr-100621 |
| CrossGroupIntegrationTest | 2 | PROD-REGRESSION-CANDIDATE | numeric parity | vr-105510 |
| AnalyticsPipelineTest | 3 | PROD-REGRESSION-CANDIDATE | assertApproxEquals drift | vr-111954 |
| TaxEstimatorTest | 1 | PROD-REGRESSION-CANDIDATE | 8156 vs 8100 | vr-101451 |
| ComputeMoneyRadarUseCaseTest | 1 | PROD-REGRESSION-CANDIDATE | needs human review (mechanism n/p) | vr-100131 |
| CalculateFinancialForecastUseCaseTest | 1 | PROD-REGRESSION-CANDIDATE | needs human review (mechanism n/p) | vr-100131 |
| DatabaseBackupRepositoryImplTest | 6 | PROD-REGRESSION-CANDIDATE | schema86 rejection not triggering — bare AssertionError | vr-123440 |
| integration-golden cascade | ~40 of 47F across ~25 classes | ENV-OOM | fork OOM cascade; incl. HiltGraphSmokeTest, Pipeline4LifecycleGoldenTest (initially), PrivacyDoNotStoreTest, ReceiptProcessingPipelineTest, RecurringBillPaymentMatchTest | vr-105510 |
| ExportOptionsViewModelTest | 9F→OOM | ENV-OOM | 9F then heap death | vr-124743 |
| runtime-ui SpendingMap classes | n/m | ENV-OOM | OOM at SpendingMap classes | vr-101804 |
| W-tail fork crash; full-gate + shard stalls (partial) | n/m | ENV-JVM | byte-buddy instrument assertion + OOM killing forks | vr-081633 (+ vr-115441, shards) |
| ExpenseCategoryClassifierTest | 7 | UNCLASSIFIED | insufficient log evidence | vr-083813 |
| BudgetMonitorTest + StressTest | 8 | UNCLASSIFIED | insufficient log evidence | vr-083813 |
| FinancialStressForecastEngineTest | 5 | UNCLASSIFIED | insufficient log evidence | vr-083813 |
| BankApiIntegrationTest | 4 | UNCLASSIFIED | insufficient log evidence | vr-083813 |
| WarrantyTextExtractorTest | 1 | UNCLASSIFIED | insufficient log evidence | vr-093043 |
| ReceiptMatchLifecycleServiceTest | 3 | UNCLASSIFIED | insufficient log evidence | vr-093043 |
| AutoCreateWarrantyFromReceiptUseCaseTest | 2 | UNCLASSIFIED | insufficient log evidence | vr-100131 |
| MerchantKeyBackfillWorkerTest | 2 | UNCLASSIFIED | insufficient log evidence | n/p |
| WarrantyReminderDeliveryDaoTest | 2 | UNCLASSIFIED | insufficient log evidence | n/p |
| BudgetRepositoryHistoricalStatusTest | 2 | UNCLASSIFIED | insufficient log evidence | n/p |
| BudgetRepositoryDiagnosticsTest | 2 | UNCLASSIFIED | insufficient log evidence | n/p |
| RecommendationDaoTest | 1 | UNCLASSIFIED | insufficient log evidence | n/p |
| AccountingExportRepositoryTest | 1 | UNCLASSIFIED | insufficient log evidence | n/p |
| DeterministicExpenseExportPagerTest | 1 | UNCLASSIFIED | insufficient log evidence | n/p |
| EmailReceiptIngestionServiceTest | 1 | UNCLASSIFIED | insufficient log evidence | n/p |

## Hangs (measured)

| Site | Run | Mechanism hypothesis (evidence) | Ledger action needed |
|---|---|---|---|
| TransactionLifecycleCoordinatorTest | isolated run | real Room `withTransaction` on relaxed AppDatabase suspends forever (test kt:90-92 coAnswers passthrough without mockkStatic) | add/update HANG entry |
| NotificationRepositoryDeleteAllNotificationsClockTest (full-suite stall zone, next class after NotificationProcessingPipelineStressTest) | vr-115441 + vr-074329 | same `withTransaction` stub pattern (kt:67) | add HANG entry (probable stall point) |
| WarrantyTrackerRepositoryTest | isolated run | unbounded ticker flow `while(true) delay(1h)` (WarrantyTrackerRepository.kt:79-84) + OOM on DefaultExecutor | add HANG entry |
| NegotiationEngineTest | isolated run | 1 test passes, then silence; next test stubs `withTransaction` passthrough (kt:242 region) | add HANG entry (weakest evidence) |

## Infra defects found (not test bugs)

1. `migration-tests` profile: `--tests *Migration*` wildcard expanded by shell to a repo-root markdown file (`pr-d-make-migration-proof-blocking.md`) → "No tests found". Profile needs a quoting fix in the runner or a shard-style filter list.
2. JVM fork instability: byte-buddy instrument assertion failures + `OutOfMemoryError` killing forks mid-run (W-tail, ExportOptionsViewModelTest, integration-golden) — matches the TEST_FAILURE_LEDGER.md operational note. Consider fork memory / `forkEvery` tuning (human decision).
3. Runner dedup quirk: `Find-OverlappingSuccessfulRun` dedups per-profile, so a targeted-unit-test PASS at the same fingerprint blocks further targeted runs regardless of filter; worked around with `-AllowOverlap -OverlapReasonCode INVESTIGATE_INCONSISTENT_RESULT` (sanctioned).

## Static guard suite (measured)

11/25 PASS (vr-112900). Violations: ui_dao, receipt_link, import_lifecycle, pii_logging, ignored_test_budget, time_boundaries, deprecation_escalations, db_artifact_sync, cancellation, event_writers, raw_money_aggregates, guard_tests (41 pytest F / 1820 P). Infra errors: known_good_state, db_access. These are separate from JVM tests; pre-existing repo state, not caused by the sweep.

## Recommended fix order (for a future session — NOT executed)

1. Fix the `withTransaction` harness pattern (cluster a + hang family) — biggest lever, ~35 failures + 2-3 hangs.
2. Fix nested `runTest`-in-`assertThrows` harness bug (mechanical, 6 failures).
3. Update stale worker/guard API tests (WarrantyExpiration, BillReminder, ConstantsConsistency, InvestmentTracker) — ~27 failures.
4. Human decisions: DbGuardPolicyFixtureTest regeneration (FG-06/07 approval), migration 119→121 `pipeline_diagnostic_events` gap, MoneyTest/GoldenMaster numeric parity (possible prod bugs), privacy policy map test update (privacy-relevant), MlKit seam strategy, fixture time-stamping.
5. Re-baseline shards after 1-2; then full `unit-tests` gate.

## Caveats

- Shard TIMEOUT runs leave unmeasured tails; ~80 data-shard failures were not individually reproduced (treat as unverified until rerun).
- OOM cascades inflate integration-golden failure counts; only AnalyticsPipelineTest and Pipeline4LifecycleGoldenTest were isolated and confirmed as real failures.
- Static triage predictions vs measured: 17 predicted failing classes — DbGuardPolicyFixtureTest, BudgetMonitor*, TaxEstimator, ReviewQueue, SavingsContribution, CloudDashboardBriefing, ExportReadBarrier, MigrationRegistration, BankStatementParser, ReceiptLifecycleCoordinator confirmed; GoldenMasterVerificationTest, MoneyBoundaryGuardTest, DefaultAiCapabilityRouterTest, InvestmentTrackerTest, WarrantyExpirationWorkerTest, HomeViewModelRecommendationTest (contamination-only), SpendingMap family were surprises or overturned.
- All results measured at git revision `a0c4ae0d68c56e3ca6eb5eabc5cb8b082612b169`, worktree fingerprint `74188b5b...`.
