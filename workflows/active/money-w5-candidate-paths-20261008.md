# Wave 5 candidate paths — October 8, 2026

Original selection checkpoint: not independently accepted or release-ready. On
2026-10-12 the user authorized a selected WIP commit/push for cross-PC continuation;
see the transfer amendment below. Earlier no-staging/commit notes are historical,
not a prohibition on this transfer. Unrelated files stay untouched.

## Application production and directly related tests

- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptItemCategorizationRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/di/SavingsModule.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/ReceiptTaxAllocator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssembler.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsModels.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnomalyDetector.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DailyBucketEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/MerchantInsightEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsInput.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsMoneyAdapter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SingleCurrencyAnalyticsInput.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateMetadata.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateResult.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/NormalizedMoneyRow.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/core/money/SourceCurrencyAllocation.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloResult.kt` (R17 historical conversion metadata)
- `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/model/PeriodTotal.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptSource.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/receipt/ProcessReceiptUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/RetroTopCategoriesCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/components/TotalsDashboardCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`
- `app/src/test/java/com/yourname/expensetracker/architecture/DeprecatedApiArchitectureGuardTest.kt`
- `app/src/test/java/com/yourname/expensetracker/consistency/EmptyZeroNullResilienceTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationServiceTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/repository/DashboardContractsAdapterTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepositoryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptItemCategorizationMoneyPersistenceTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/ai/model/ReceiptTaxAllocatorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilderTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngineMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AnalyticsModelsCurrencyInvariantTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngineNormalizedMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/DailyBucketEngineMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzerNormalizedMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzerTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineDeepTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineEdgeCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineNormalizedDailyMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineValidationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/LegacyAnalyticsMoneyBoundaryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculatorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/NormalizedAnalyticsMoneyAdapterTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/NormalizedPeriodComparisonMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculatorValidationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPaceGoldenTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifierNormalizedMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifierTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineDeepTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineNormalizedMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineStatusBaselineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineValidationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderNormalizedRowsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderSourceCurrencyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/core/money/SourceCurrencyAllocationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/export/AccountantReportPdfMoneyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssemblerPaceSentinelTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssemblerTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorBoundaryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthCalculatorTransactionTypeTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2Test.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineBlockPartyMultiCurrencyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/ReceiptParserMoneyBoundaryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/ReceiptParserOcrPatternsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/ReceiptParserTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/receipt/ProcessReceiptUseCaseHomeCurrencyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/e2e/CategoryBreakdownFlowTest.kt`
- `app/src/test/java/com/yourname/expensetracker/e2e/EmptyDataFlowTest.kt`
- `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/e2e/ReceiptProcessingPipelineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/metrics/EffectiveAmountConsistencyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/TestUtils.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/components/RetroTopCategoriesCardCategoryTransactionsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsStateMoneySafetyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModelInsightsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/cashflow/CashFlowCalendarViewModelTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/home/TopCategoryTransactionFilterTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModelTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModelTest.kt`
- `app/src/test/java/com/yourname/expensetracker/verification/CrossGroupIntegrationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/verification/GoldenMasterVerificationTest.kt`

- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AnalyticsInputAssemblerProvenanceTest.kt` (R05 captured currency/provenance regression)
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulatorTest.kt` (R05 captured currency/provenance regression)
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt` (R05 captured currency/provenance regression)

- `app/src/test/java/com/yourname/expensetracker/domain/health/HealthScoreEdgeCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/health/HealthScoreGoldenTest.kt`

- `app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt` (inherited AM-substring row loss found by broad receipt validation)
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt`

- `app/src/main/java/com/yourname/expensetracker/domain/util/AmountUtils.kt` (documented currency-prefix grouping repair found by integration shard)
- `app/src/test/java/com/yourname/expensetracker/integration/CategorizationPipelineIntegrationTest.kt`

- `app/src/test/java/com/yourname/expensetracker/domain/util/AmountUtilsTest.kt` (controlled diagnostic regression)

- `app/src/main/java/com/yourname/expensetracker/data/repository/SavingsGoalRepository.kt` (R02 real persistence currency/assumption mappings)
- `app/src/test/java/com/yourname/expensetracker/data/repository/SavingsGoalRepositoryCurrencyTest.kt`

Forecast continuation also includes `app/src/test/java/com/yourname/expensetracker/golden/ForecastSynthesisGoldenTest.kt`.
The two existing distribution/simulator regression paths and simulator golden path remain candidates.
These paths are not independent acceptance or permission to stage the mixed tree.

## Runner repair candidate group

- `scripts/validation-runner.ps1`
- `scripts/ci/run_registered_guard.py`
- `scripts/ci/test_run_registered_guard.py`
- `scripts/ci/test_validation_runner_protocol.py`

## Money documentation candidate group

- `workflows/active/money-w5-remaining-implementation-plan-20261009.md`: planner-derived remaining batches and local-only continuation amendment.
- `workflows/active/money-w5-dashboard-b1-20261009-handoff.md`: corrected dashboard slice and validation/review limitations.

- `workflows/active/money-m0-20261003-handoff.md`
- `workflows/active/money-m0-inventory-20261003.md`
- `workflows/active/money-m1a-20261003-handoff.md`
- `workflows/active/money-m1b-20261003-handoff.md`
- `workflows/active/money-m2a-20261004-handoff.md`
- `workflows/active/money-m2c1-20261004-handoff.md`
- `workflows/active/money-m2c2-20261004-handoff.md`
- `workflows/active/money-m2d-20261004-handoff.md`
- `workflows/active/money-w5-focused-review-20261007-handoff.md`
- `workflows/active/money-w5-review-remediation-20261004-handoff.md`
- `workflows/active/money-w5-runtime-followup-20261006-handoff.md`
- `workflows/active/money-w5-candidate-paths-20261008.md`
- `workflows/active/money-w5-forecast-quality-20261009-handoff.md`: latest forecast-only continuation and remaining caller gaps.
- `workflows/active/wave4-money-batch-plan-20261001.md`: relevant Wave 5 money hunks only.

## Conditional cancellation ratchet
- `config/baselines/cancellation.json`: only the already authorized AdvancedAnalyticsEngine removal (118 to 117); no further baseline edits.
- `docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md`: retain the authorization record around entries 185–186 and narrow money reconciliations only; do not stage unrelated journal hunks.

## Excluded
- `.codex/*`, `.codex-temp.patch`, `### VERDICT.txt`.
- `docs/analyses and debug master/CL-29-wave1-implementation-report.md`.
- `docs/architecture/COVERAGE_MATRIX.md`.
- `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md`.
- `config/guards/db_ownership_policy.yml`: line-ending-only noise.
- Generated outputs, JVM crash logs, and all unrelated prior-wave/campaign artifacts.

This manifest does not authorize any commit or broaden validation/guard policy.

## Savings Batch 3 continuation — 2026-10-11

Additional selection-only paths (review every authored hunk; not commit-ready):
- app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsDecisionQuality.kt
- app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardWidgetRegistry.kt
- app/src/main/res/values/strings.xml (savings unavailable message only)
- app/src/test/java/com/yourname/expensetracker/ui/screens/home/DashboardWidgetRenderCoverageTest.kt
- app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsHistoricalMeanPolicyTest.kt (typed sweep fixture only)
- workflows/active/money-w5-savings-b3-20261011-handoff.md

Existing candidate paths retain prior savings/dashboard/source/test ownership.
The new helper is additional to the five previously listed untracked money helpers;
preserve all of them. No staging or baseline authority is implied.

## Savings Batch 4 continuation - 2026-10-11

Additional selection-only paths (not commit-ready):
- app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGoalCurrencyPolicy.kt
- app/src/test/java/com/yourname/expensetracker/domain/savings/SavingsGoalCurrencyPolicyTest.kt
- workflows/active/money-w5-savings-b4-20261011-handoff.md

Existing candidate savings/test/UI paths retain earlier hunks. The GoalCard and
one goal-unavailable string are narrowly scoped provenance presentation changes;
GoldenMasterVerificationTest has one explicit savings fixture adjustment only
in this batch. Repository currency tests retain their older round-trip coverage.
No persistence, schema, baseline, staging, or commit authority is implied.

## Savings Batch 5 continuation - 2026-10-11

Additional selection-only path (not commit-ready):
- workflows/active/money-w5-savings-b5-20261011-handoff.md

Existing history repository/tests, gamification tests, savings ViewModel/UI and
strings paths retain their earlier hunks. Current selection is semantic JSON/event
validation plus honest history-write failure handling and its regression tests.
No Room schema, transaction owner, durable idempotence, guard/baseline, staging,
or commit authority is implied. Batch 8 persistence ownership remains separate.

## Batch 6 partial source continuation - 2026-10-11

Additional selection-only paths (unvalidated, not commit-ready):
- app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementSourceBoundaryTest.kt
- workflows/active/money-w5-source-b6-20261011-handoff.md

Bank parser/processor and parser tests retain earlier hunks. Source review found
same-home legacy savings still influencing FinancialHealthScoreV2 runway; that
health producer and FinancialHealthScoreV2Test now have the user-approved narrow
provenance correction. The existing shared savings policy is reused unchanged.
Batch 6 compile and 269 focused tests across fifteen filters now pass locally;
durable evidence is in the Batch 6 packet. Independent acceptance is not implied. No staging,
schema, guard, baseline or commit authority is implied.

## Batch 7 PDF continuation - 2026-10-12

Additional selection-only packet (not commit-ready):
- workflows/active/money-w5-pdf-b7-20261012-handoff.md

Existing candidate PDF exporter/money-test and instrumented PDF-test paths retain
their earlier hunks. This continuation normalizes deductible source labels before
grouping and strengthens source-money reconciliation/fail-closed/cancellation tests
plus the separate real Android page-rendering/multipage gate. Compile and 32 focused
JVM tests passed locally at the earlier fingerprint, with evidence in the Batch 7 packet.
Pixel_8a was subsequently booted with user approval. Full connected run
vr-20261012-061140-9032e23b failed Android test compilation in the unchanged
FreshInstallBatch8ParityTest (missing JUnit fail import), before any native tests.
Native PDF acceptance and independent acceptance remain pending. See Batch 7 packet.

User-approved connected compile repair/retry:
- app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/FreshInstallBatch8ParityTest.kt
  (missing org.junit.Assert.fail import only; no assertion changes).
  Retry vr-20261012-064543-2da21b78 passed Android test compilation but failed
  suite startup with zero tests: enabled orchestrator has no helper APK dependency.
  app/build.gradle.kts was unchanged at that checkpoint, before subsequent approval.

User subsequently approved the narrow test utility dependency repair:
- app/build.gradle.kts (androidTestUtil orchestrator:1.6.1 only; test-services:1.6.0
  is transitive). Isolation/clearPackageData and assertion coverage remain unchanged;
  connected retry vr-20261012-070252-55901f2e compiled and executed native tests;
  terminal FAIL/1, final XML 350 tests: 298 passed / 43 failed / 9 skipped.
  Direct mixed-currency multipage PDF rendering passed; repository PDF fixture
  crashed in MockK's native instrumentation agent, so native repository acceptance
  remains pending. No production dependency upgrade.
No staging, commits, schema, guard, runner, baseline or allowlist authority implied.

Current connected evidence is persisted in the Batch 7 packet. Read-only failure
triage: 29 pre-v145 migration tests, 9 current-baseline migration/snapshot tests,
4 fresh-install index/uniqueness tests, and 1 native PDF fixture crash. No automatic
fix/rerun or Batch 8 implementation started after the failure. User discussed a
first-release DB baseline; no reset, migration/test retirement or destructive
fallback was authorized or implemented. Baseline planning remains separate.

## Connected failure fixture repair C1 - 2026-10-12

User requested resolving the failures. Additional selection-only paths:
- app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationMatrixTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/Migration148to149ContractTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/FreshInstallIndexParityTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/GroupMemberDaoTest.kt
- app/schemas/com.yourname.expensetracker.data.database.AppDatabase/147.json
  (encoding-only UTF-16LE-to-UTF-8 repair; decoded text/identity/schema unchanged).
- workflows/active/money-w5-connected-repair-20261012-handoff.md

Existing PDF instrumented path retains earlier Batch 7 assertions and now uses
real platform Context/FileProvider instead of native framework/static mocks.
Current-contract fixtures preserve Room uniqueness and seeded data; supported
migration assets fail closed when absent/malformed. Pre-baseline refusal test
now verifies the old DB is not silently destroyed; no production fallback added.
Local strict source review/diff check passed; fresh compile/device validation
NOT RUN, awaiting expensive-profile approval. 28 untouched historical failures
remain from the earlier report; no all-suite PASS or commit-ready claim. No
production schema/index/migration/DAO changes, guard weakening, reset, retirement,
staging or commit authority implied at that checkpoint. Details in the C1 packet.

## User-authorized WIP transfer amendment - 2026-10-12

- User requested commit and push so Wave 5 can continue on another PC. Transfer
  branch: `bug-fixes`, upstream `origin/bug-fixes`; this is not a release checkpoint.
- Selected candidates include the money application changes/tests, archived C2-C5
  migration repairs in `AppDatabase.kt` and their migration regressions, runner
  and registered-guard repairs/tests, the cancellation baseline tightening, and
  money workflow packets. No allowlist broadening or baseline growth authorized.
- Additional transfer packet:
  `workflows/active/money-w5-cross-pc-20261012-handoff.md`.
- Latest full connected run `vr-20261012-142426-b8ed659c`: FAIL/1, 354 XML cases,
  341 passed / 4 failed / 9 skipped. All failures are archived category-index
  parity. Exact evidence, remaining methods and next actions are in the repair
  and cross-PC packets; do not use the earlier C1 totals as current evidence.
- Independent acceptance and broad final unit/static-guard validation remain
  pending. Savings Batch 8 is not implemented. Commit/push is a WIP transfer only.
- Local agent configuration, private session transcript, scratch patches/verdict,
  unrelated campaign/audit docs, generated outputs and line-ending-only ownership
  policy noise remain excluded and untouched.

## Batch 8 and final automated evidence - 2026-10-13

Selection-only continuation paths below; inspect authored hunks before any future
staging. No staging, commit or push is authorized by this checkpoint. Prior
WIP-transfer acceptance is historical, not final Wave 5 acceptance.

All twelve final automated profiles passed at snapshot
9b3c78c8856b8e278d9819ca7c2e589753cef61ed1392c8300b4148156adcf4d.
The required 149-to-150 upgrade, baseline/fresh parity and both native PDF cases
passed without skips. See money-w5-savings-b8-20261012-handoff.md for run IDs,
commands/log paths, actual versus cached execution and remaining independent
acceptance. Later documentation appends change the overall worktree fingerprint.

Additional or reaffirmed paths:
- .agents/skills/run-validation/SKILL.md
- app/build.gradle.kts
- app/schemas/com.yourname.expensetracker.data.database.AppDatabase/150.json
- app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/CategoryDaoTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/dao/ExpenseDaoTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationMatrixTest.kt
- app/src/androidTest/java/com/yourname/expensetracker/data/database/MigrationContractTest.kt
- app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt
- app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
- app/src/main/java/com/yourname/expensetracker/data/database/dao/CategoryDao.kt
- app/src/main/java/com/yourname/expensetracker/data/database/dao/SavingsContributionLedgerDao.kt
- app/src/main/java/com/yourname/expensetracker/data/database/dao/SavingsGoalDao.kt
- app/src/main/java/com/yourname/expensetracker/data/database/dao/SavingsSweepPlanDao.kt
- app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt
- app/src/main/java/com/yourname/expensetracker/data/database/entity/SavingsContributionLedgerEntry.kt
- app/src/main/java/com/yourname/expensetracker/data/database/entity/SavingsSweepPlan.kt
- app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
- app/src/main/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepository.kt
- app/src/main/java/com/yourname/expensetracker/data/repository/SavingsSweepAcceptanceCoordinator.kt
- app/src/main/java/com/yourname/expensetracker/di/DaoModule.kt
- app/src/main/java/com/yourname/expensetracker/di/SavingsModule.kt
- app/src/main/java/com/yourname/expensetracker/domain/savings/SavingsGoalCurrencyPolicy.kt
- app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt
- app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt
- app/src/main/res/values-el/strings.xml
- app/src/main/res/values-es/strings.xml
- app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierManifestTest.kt
- app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierSavingsLedgerTest.kt
- app/src/test/java/com/yourname/expensetracker/data/database/DatabaseMigrationProofTest.kt
- app/src/test/java/com/yourname/expensetracker/data/database/Migration148to149Test.kt
- app/src/test/java/com/yourname/expensetracker/data/database/MigrationRegistrationTest.kt
- app/src/test/java/com/yourname/expensetracker/data/database/MigrationTestInstrumentation.kt
- app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt
- app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryTruncationTest.kt
- app/src/test/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepositoryTest.kt
- app/src/test/java/com/yourname/expensetracker/data/repository/SavingsSweepAcceptanceCoordinatorTest.kt
- app/src/test/java/com/yourname/expensetracker/domain/bank/BankApiIntegrationTest.kt
- app/src/test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt
- app/src/test/java/com/yourname/expensetracker/guard/DbGuardPolicyFixtureTest.kt
- app/src/test/java/com/yourname/expensetracker/scenarios/BackupRestoreContractTest.kt
- app/src/test/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModelTest.kt
- config/baselines/cancellation.json
- config/guards/db_ownership_policy.yml
- docs/architecture/ARCHITECTURE.md
- docs/architecture/CODEBASE_INVENTORY.md
- docs/architecture/CODEBASE_SEGMENTS.md
- docs/architecture/ENGINE_INTERACTION_MAP.md
- docs/architecture/LEGAL_PATHS.md
- docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md
- scripts/ci/test_gradle_registered_guard_contract.py
- scripts/ci/test_validation_runner_protocol.py
- scripts/test_verify_db_access_boundaries.py
- scripts/test_verify_ignored_test_budget.py
- scripts/validation-runner.ps1
- workflows/active/money-w5-savings-b8-20261012-handoff.md

Ownership-policy amendment: the earlier line-ending-only exclusion remains for
unrelated formatting noise; the four exact human-approved MIT-DB-W5-R16 writer
contracts are now selected authored hunks. No blanket owner grant. Cancellation
baseline candidate now includes the five previously approved resolved removals
recorded in the Batch 8 handoff, not the older one-removal-only snapshot. Refer
to FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/FG-06/FG-07/FG-23.

Runner extension permits only focused release-unit filters under the existing
lock, timeout, fingerprint and marker protocol. Canonical ignored-test bridge
retains fixed registry budget 29 and fail-closed reason/denylist enforcement.
Release test fixtures assert production debug-only API denial; no release gate
was relaxed. Schema JSON is test resources only, not release APK assets.

Excluded and preserved: .codex/config.toml, .claude/, CLAUDE.md, private session
transcript, docs/prompts/release-1.0-assurance/, its separate handoff, and generated
build outputs. No blanket selection of the mixed worktree.

Local strict review PASS is self-review only. Final independent reviewer and
guardian acceptance remains NOT RUN; Wave 5 is PARTIAL, not finalized.
The local-only/no-subagents amendment remains in force.

## Renewed user-authorized checkpoint commit - 2026-10-13

The user's "commit and push first" instruction authorizes staging, committing
and pushing the 56 selected Wave 5 continuation paths, including this manifest.
This supersedes the earlier selection-only/no-commit checkpoint wording for
these exact paths. Unrelated/excluded files remain untouched. This is a WIP
checkpoint with independent acceptance outstanding, not finalized Wave 5.