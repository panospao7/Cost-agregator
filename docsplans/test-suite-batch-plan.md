# Test Suite Batch Plan

**Workflow:** `wf-20260423-0824-analyze-debug-optimize-test-suite`
**Date:** 2026-04-23
**Source docs:** `test-suite-scout-report.md`, `test-suite-file-inventory.md`, `test-suite-quality-audit.md`

---

## Overview

| Metric | Count |
|---|---|
| Meaningful test files (🟢) | ~190 |
| Marginal test files (🟡) | ~180 |
| Dead / @Ignore'd files (⚫) | 35 |
| Trivial files (🔴) | ~15 |
| Infrastructure / helper files (⚪) | 15 |
| Total files in plan | ~435 |
| Batches for meaningful tests | B03–B09 (~190 files) |
| Batch for dead/trivial cleanup | B10 (~50 files) |
| Infrastructure batch | B01 (15 files) |
| Quick-wins batch | B02 (6 files) |

---

## B01 — Infrastructure & Build Config

**Goal:** Fix build configuration, test options, Room schema verification, and test-utility quality. No production logic changes — pure infrastructure.

| # | File | Action |
|---|------|--------|
| 1 | `app/build.gradle.kts` | Add `testOptions { maxParallelForks, forkEvery, unitTests.all { … } }` block; add CI include/exclude filters |
| 2 | `app/build.gradle.kts` (verifyRoomSchemaSnapshots) | Update `maxVersion` from 35 → 92 to match `app/schemas/` range (33–92) |
| 3 | `AnalyticsEngineTestBase.kt` | Audit shared base class; ensure no hidden state leaks between tests |
| 4 | `TestUtils.kt` | Review for dead helpers; add KDoc |
| 5 | `util/FlowTestUtils.kt` | Review for dead helpers; confirm coroutine scope hygiene |
| 6 | `util/HiltTestUtils.kt` | Review; ensure @HiltAndroidApp rule reuse is safe |
| 7 | `util/ViewModelTestUtils.kt` | Review; confirm InstantTaskExecutorRule / TestDispatcher setup |
| 8 | `e2e/FlowPipelineTestHarness.kt` | Audit harness for state leaks; ensure test-coroutine scope cleanup |
| 9 | `FakeTimeProvider.kt` | Promote to shared test fixture; ensure injectable in all time-dependent VMs |
| 10 | `GoldenAnalyticsDataset.kt` | Verify golden dataset parity with production analytics engines |
| 11 | `ExpectedResults.kt` | Verify alignment with current parser contract |
| 12 | `GoldenDataSets.kt` | Verify alignment with current model contract |
| 13 | `domain/intelligence/ml/ExpenseCategoryClassifierTest.kt` | **Move** from `test/` to `androidTest/` or add `@RunWith(RobolectricTestRunner::class)` — currently uses Espresso on JVM |
| 14 | `domain/analytics/SpendingThresholdCalculatorTest.kt` | **Migrate** Mockito → MockK for framework consistency |
| 15 | `domain/util/FakeTimeProvider.kt` | Verify published as test-fixture (confirm same as #9 if alias) |

**Exit criteria:** `./gradlew test` passes with new `testOptions`; `verifyRoomSchemaSnapshots` succeeds for versions 33–92; no Espresso imports in JVM unit test; all Mockito references removed.

---

## B02 — Quick Wins: Fix Assertion-Drift @Ignore'd Tests

**Goal:** Re-enable ignored tests whose root cause is assertion drift or tooling incompatibility — not architectural instability.

| # | File | @Tests Ignored | Root Cause | Fix |
|---|------|----------------|------------|-----|
| 1 | `domain/receipt/WarrantyTextExtractorTest.kt` | 2 | Parsing order changed; non-warranty text still extracts TOTAL field | Update expected values to match current extraction contract |
| 2 | `domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt` | 2 | Missing mock for `ReceiptAssistInputBuilder.build`; artifact explanation assertion mismatch | Add missing mock stub; update artifact explanation assertion |
| 3 | `domain/ai/usecase/ReceiptAssistInputBuilderTest.kt` | 2 | `imagePath` field mismatch | Add `imagePath` field to expected output / mock |
| 4 | `domain/export/CsvEscapingTest.kt` | 3 | IIF/CSV escaping contract changed | Update expected escaped strings to match current contract |
| 5 | `domain/logic/CustomSplitParserTest.kt` | 12 | Parser contract drift | Update assertion values to match current parser output |
| 6 | `domain/split/SplitCalculationPrecisionTest.kt` | 9+ | `Truth assertThat` incompatible with Kotlin value class boxing | Replace `Truth.assertThat` with direct `assertEquals` or `assertThat(x).isEqualTo(y)` from Kotlin stdlib |

**Exit criteria:** All 6 files compile and pass with `@Ignore` removed; `SplitCalculationPrecisionTest` uses direct equality assertions.

---

## B03 — Data / Repository Layer (29 files)

**Goal:** Stabilize all repository unit tests — the highest-value test layer per audit.

| # | File | @Tests | @Ignore? | Notes |
|---|------|--------|----------|-------|
| 1 | `data/repository/AccountingExportRepositoryTest.kt` | 14 | No | |
| 2 | `data/repository/AiArtifactRepositoryImplTest.kt` | 9 | No | |
| 3 | `data/repository/AiChatRepositoryImplTest.kt` | 8 | No | |
| 4 | `data/repository/AutomatedSavingsRuleStateRepositoryTest.kt` | 6 | No | |
| 5 | `data/repository/BudgetRepositoryHistoricalStatusTest.kt` | 2 | No | |
| 6 | `data/repository/BudgetRepositorySuggestionsBatchTest.kt` | 1 | No | |
| 7 | `data/repository/BudgetRepositoryTruncationTest.kt` | 9 | No | |
| 8 | `data/repository/BudgetRolloverTest.kt` | 12 | No | |
| 9 | `data/repository/BusinessExpenseRepositoryTest.kt` | 2 | No | |
| 10 | `data/repository/CategoryRepositoryTest.kt` | 1 | No | |
| 11 | `data/repository/DashboardContractsAdapterTest.kt` | 1 | No | |
| 12 | `data/repository/DatabaseBackupRepositoryImplTest.kt` | 14 | No | File I/O: audit temp file cleanup |
| 13 | `data/repository/DeterministicExpenseExportPagerTest.kt` | 2 | No | |
| 14 | `data/repository/ExpenseRepositoryTest.kt` | 9 | No | |
| 15 | `data/repository/ExpenseRepositoryTruncationTest.kt` | 9 | No | |
| 16 | `data/repository/FinancialWeatherRepositoryTest.kt` | 9 | No | |
| 17 | `data/repository/GroupsRepositoryImplTest.kt` | 6 | No | |
| 18 | `data/repository/MerchantRulesRepositoryTest.kt` | 4 | No | |
| 19 | `data/repository/MultiCurrencyRepositoryTest.kt` | 27 | No | |
| 20 | `data/repository/NotificationProcessingPipelineOversizedAmountTest.kt` | 3 | No | |
| 21 | `data/repository/NotificationProcessingPipelineReliabilityTest.kt` | 11 | No | |
| 22 | `data/repository/ReceiptRepositoryStatementDuplicateTest.kt` | 1 | No | |
| 23 | `data/repository/RecommendationRepositoryTest.kt` | 17 | No | |
| 24 | `data/repository/RecurringExpenseRepositoryTest.kt` | 3 | No | |
| 25 | `data/repository/ReviewQueueRepositoryTest.kt` | 11 | No | |
| 26 | `data/repository/SavingsContributionHistoryRepositoryTest.kt` | 3 | No | |
| 27 | `data/repository/WarrantyTrackerRepositoryTest.kt` | 11 | No | |
| 28 | `data/repository/CategoryRepositoryStressTest.kt` | 4 | Yes | Currently @Ignore — evaluate: re-enable or merge into CategoryRepositoryTest |
| 29 | `data/repository/BudgetRepositoryStressTest.kt` | 18 | Yes | Currently @Ignore — evaluate: re-enable with test dispatcher or delete |

**Exit criteria:** All non-ignored repository tests pass; stress test decision documented (enable/delete).

---

## B04 — Data / AI Providers + Data / Database + Data / Other (34 files)

**Goal:** Stabilize AI provider tests, database entity/model/DAO unit tests, email, location, security, service, and speech layers.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `data/ai/provider/CloudCategorizationAssistServiceTest.kt` | 10 | |
| 2 | `data/ai/provider/CloudDashboardBriefingServiceTest.kt` | 5 | |
| 3 | `data/ai/provider/CloudDedupeJudgeServiceTest.kt` | 5 | |
| 4 | `data/ai/provider/CloudQueryInterpretationServiceTest.kt` | 4 | |
| 5 | `data/ai/provider/CloudReceiptAssistServiceTest.kt` | 6 | |
| 6 | `data/ai/provider/CloudReceiptItemCategorizationServiceTest.kt` | 3 | |
| 7 | `data/ai/provider/CloudReviewExplanationServiceTest.kt` | 1 | |
| 8 | `data/ai/provider/CloudWarrantyExtractionServiceTest.kt` | 4 | |
| 9 | `data/ai/provider/DashboardBriefingResponseParserTest.kt` | 3 | |
| 10 | `data/ai/provider/HybridReceiptItemCategorizationServiceTest.kt` | 1 | |
| 11 | `data/ai/provider/HybridServiceDelegationTest.kt` | 7 | |
| 12 | `data/ai/provider/OnDeviceCategorizationAssistServiceTest.kt` | 21 | |
| 13 | `data/ai/provider/OnDeviceDashboardBriefingServiceTest.kt` | 5 | |
| 14 | `data/ai/provider/OnDeviceDedupeJudgeServiceTest.kt` | 8 | |
| 15 | `data/ai/provider/OnDeviceNotificationParserTest.kt` | 3 | |
| 16 | `data/ai/provider/OnDeviceQueryInterpretationServiceTest.kt` | 7 | |
| 17 | `data/ai/provider/OnDeviceReceiptAssistServiceTest.kt` | 8 | |
| 18 | `data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt` | 1 | |
| 19 | `data/ai/provider/OnDeviceReviewExplanationServiceTest.kt` | 6 | |
| 20 | `data/ai/provider/SmartReceiptAssistServiceTest.kt` | 8 | |
| 21 | `data/ai/provider/internal/CloudJsonParserTest.kt` | 12 | |
| 22 | `data/ai/provider/internal/CloudRetryPolicyTest.kt` | 7 | |
| 23 | `data/ai/worker/DailyBriefingWorkerTest.kt` | 6 | |
| 24 | `data/database/GroupTransactionCoordinatorTest.kt` | 23 | Time-dependent — inject FakeTimeProvider |
| 25 | `data/database/converter/ConvertersTest.kt` | 4 | |
| 26 | `data/database/dao/BankConnectionDaoTest.kt` | 12 | |
| 27 | `data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt` | 15 | |
| 28 | `data/database/dao/RecommendationDaoTest.kt` | 15 | |
| 29 | `data/database/entity/CategoryTest.kt` | 7 | |
| 30 | `data/database/entity/DedupeKeyTest.kt` | 9 | |
| 31 | `data/database/entity/MileageTrackingValidationTest.kt` | 1 | |
| 32 | `data/database/model/ExpenseWithCategoryFormattedAmountTest.kt` | 11 | |
| 33 | `data/database/model/ExpenseWithCategoryFormattedTimeTest.kt` | 5 | |
| 34 | `data/currency/ExchangeRateStoreAdapterTest.kt` | 4 | |

**Exit criteria:** All 34 files pass; `GroupTransactionCoordinatorTest` uses FakeTimeProvider.

---

## B05 — Data / Email + Location + Security + Service + Speech + Domain / AI + Domain / Analytics (37 files)

**Goal:** Complete remaining data-layer tests, then start domain analytics — the largest single domain area.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `data/email/EmailReceiptIngestionServiceTest.kt` | 12 | |
| 2 | `data/email/EmailReceiptIngestionServiceTransactionTest.kt` | 1 | |
| 3 | `data/email/provider/AmazonReceiptParserTest.kt` | 1 | |
| 4 | `data/email/provider/AppleReceiptParserTest.kt` | 2 | |
| 5 | `data/email/provider/EmailReceiptParserTest.kt` | 3 | |
| 6 | `data/email/provider/UberReceiptParserTest.kt` | 6 | |
| 7 | `data/location/AndroidForegroundLocationProviderTest.kt` | 1 | |
| 8 | `data/location/CompositeGeocodingServiceTest.kt` | 1 | |
| 9 | `data/location/GeocodingCancellationTest.kt` | 2 | |
| 10 | `data/location/GeocodingRetryHttpSemanticsTest.kt` | 5 | |
| 11 | `data/location/LocationBackfillWorkerTest.kt` | 5 | |
| 12 | `data/location/MerchantKeyBackfillWorkerTest.kt` | 5 | |
| 13 | `data/location/NominatimGeocodingServiceLocaleTest.kt` | 1 | |
| 14 | `data/location/OverpassNearbyServiceTest.kt` | 2 | |
| 15 | `data/location/internal/LogSanitizerTest.kt` | 3 | |
| 16 | `data/security/SecureKeyStorageTest.kt` | 17 | |
| 17 | `data/service/AndroidNotificationServiceTest.kt` | 2 | |
| 18 | `data/speech/AndroidSpeechInputGatewayTest.kt` | 3 | |
| 19 | `domain/ai/model/AiArtifactPresentationTest.kt` | 3 | |
| 20 | `domain/ai/model/AiRuntimeStatusModelsTest.kt` | 2 | |
| 21 | `domain/ai/model/CategorizationAssistInputTest.kt` | 1 | |
| 22 | `domain/ai/model/NotificationParsingModelsTest.kt` | 2 | |
| 23 | `domain/ai/model/OnDeviceRuntimePresentationTest.kt` | 4 | |
| 24 | `domain/ai/model/WarrantyExtractionModelsTest.kt` | 2 | |
| 25 | `domain/ai/policy/AiPolicyTest.kt` | 15 | |
| 26 | `domain/ai/policy/DefaultAiCapabilityRouterTest.kt` | 17 | |
| 27 | `domain/ai/util/AiArtifactSourceHashTest.kt` | 6 | |
| 28 | `domain/analytics/AdvancedAnalyticsDashboardTest.kt` | 5 | |
| 29 | `domain/analytics/AdvancedAnalyticsEngineDeepTest.kt` | 10 | |
| 30 | `domain/analytics/AdvancedAnalyticsEngineTest.kt` | 3 | |
| 31 | `domain/analytics/AnalyticsWindowingSupportTest.kt` | 5 | |
| 32 | `domain/analytics/AnomalyDetectorTest.kt` | 4 | |
| 33 | `domain/analytics/CategoryInsightEngineTest.kt` | 11 | |
| 34 | `domain/analytics/DayOfWeekAnalyzerTest.kt` | 4 | |
| 35 | `domain/analytics/InsightsEngineDeepTest.kt` | 7 | |
| 36 | `domain/analytics/InsightsEngineEdgeCaseTest.kt` | 6 | |
| 37 | `domain/analytics/InsightsEngineTest.kt` | 4 | |

**Exit criteria:** All 37 files pass; no time-dependent assertions without FakeTimeProvider.

---

## B06 — Domain / Analytics (cont.) + Domain / Budget + Domain / Categorization + Domain / Currency + Domain / Other Small Domains (39 files)

**Goal:** Complete analytics domain, then budget & categorization — high-invariant areas.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `domain/analytics/InsightsEngineValidationTest.kt` | 13 | |
| 2 | `domain/analytics/MerchantInsightEngineTest.kt` | 4 | |
| 3 | `domain/analytics/MonthlyComparisonCalculatorTest.kt` | 3 | |
| 4 | `domain/analytics/SpendingPaceBoundaryTest.kt` | 4 | |
| 5 | `domain/analytics/SpendingPaceCalculatorDeepTest.kt` | 6 | |
| 6 | `domain/analytics/SpendingPaceCalculatorValidationTest.kt` | 14 | |
| 7 | `domain/analytics/SpendingPaceGoldenTest.kt` | 2 | |
| 8 | `domain/analytics/SpendingPersonalityClassifierTest.kt` | 14 | |
| 9 | `domain/analytics/SpendingThresholdCalculatorTest.kt` | 11 | Migrated to MockK in B01 |
| 10 | `domain/analytics/TotalsAggregationEngineDeepTest.kt` | 5 | |
| 11 | `domain/analytics/TotalsAggregationEngineTest.kt` | 43 | Highest-value analytics test |
| 12 | `domain/analytics/TotalsAggregationEngineValidationTest.kt` | 18 | |
| 13 | `domain/analytics/TransferDirectionAnalyticsTest.kt` | 5 | |
| 14 | `domain/budget/BudgetAutopilotEngineTest.kt` | 13 | |
| 15 | `domain/budget/BudgetCalculatorBoundaryTest.kt` | 16 | |
| 16 | `domain/budget/BudgetCalculatorGoldenTest.kt` | 3 | |
| 17 | `domain/budget/BudgetCalculatorTest.kt` | 16 | |
| 18 | `domain/budget/BudgetForecastingEngineTest.kt` | 22 | |
| 19 | `domain/budget/BudgetHistorySeriesBuilderTest.kt` | 2 | |
| 20 | `domain/budget/BudgetMonitorTest.kt` | 4 | |
| 21 | `domain/budget/BudgetRecommendationEngineTest.kt` | 5 | |
| 22 | `domain/budget/BudgetTrendBoundaryTest.kt` | 1 | |
| 23 | `domain/budget/SharedBudgetManagerTest.kt` | 14 | |
| 24 | `domain/categorization/CategorizationComponentsTest.kt` | 40 | |
| 25 | `domain/categorization/CategorizationEngineDebugTest.kt` | 2 | |
| 26 | `domain/categorization/CategorizationEngineTest.kt` | 5 | |
| 27 | `domain/categorization/CategoryKeywordsTest.kt` | 7 | |
| 28 | `domain/currency/CurrencyConversionTest.kt` | 28 | |
| 29 | `domain/currency/CurrencyConverterEdgeCaseTest.kt` | 7 | |
| 30 | `domain/currency/CurrencyConverterGoldenTest.kt` | 2 | |
| 31 | `domain/alerts/AnomalyAlertOrchestratorTest.kt` | 10 | |
| 32 | `domain/bank/BankApiIntegrationTest.kt` | 4 | |
| 33 | `domain/business/BusinessExpenseReportGeneratorTest.kt` | 38 | |
| 34 | `domain/carbon/CarbonFootprintCalculatorTest.kt` | 23 | |
| 35 | `domain/cashflow/CashFlowCalculatorTest.kt` | 12 | |
| 36 | `domain/challenge/SpendingChallengeManagerTest.kt` | 4 | |
| 37 | `domain/debug/ServiceDiagnosticsTest.kt` | 11 | |
| 38 | `domain/engine/DashboardFollowThroughEngineTest.kt` | 20 | |
| 39 | `domain/income/RecurringIncomeTrackerTest.kt` | 6 | |

**Exit criteria:** All 39 files pass; golden tests match canonical datasets.

---

## B07 — Domain / AI UseCases + Domain / Parser + Domain / Receipt + Domain / Price + Domain / Export + Domain / Health + Domain / Intelligence + Domain / Groups (39 files)

**Goal:** Stabilize parser & receipt processing (core data-ingestion path), AI use cases, health & intelligence domains.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `domain/ai/usecase/CategorizationAssistInputBuilderTest.kt` | 6 | |
| 2 | `domain/ai/usecase/CategorizeReceiptItemsUseCaseTest.kt` | 1 | |
| 3 | `domain/ai/usecase/DedupeJudgeInputBuilderTest.kt` | 8 | |
| 4 | `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt` | 6 | |
| 5 | `domain/ai/usecase/ExecuteFinancialQueryUseCaseTest.kt` | 9 | |
| 6 | `domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt` | 12 | |
| 7 | `domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt` | 3 | |
| 8 | `domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt` | 10 | |
| 9 | `domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt` | 1 | |
| 10 | `domain/ai/usecase/GetAiRuntimeStatusUseCaseTest.kt` | 4 | |
| 11 | `domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt` | 7 | |
| 12 | `domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt` | 7 | |
| 13 | `domain/ai/usecase/MapFinancialQueryToNavigationUseCaseTest.kt` | 2 | |
| 14 | `domain/ai/usecase/PrioritizeReviewItemsUseCaseTest.kt` | 4 | |
| 15 | `domain/ai/usecase/ReceiptItemCategorizationInputBuilderTest.kt` | 1 | |
| 16 | `domain/ai/usecase/ReviewExplanationInputBuilderTest.kt` | 2 | |
| 17 | `domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt` | 10 | |
| 18 | `domain/ai/usecase/SyncProactiveBriefingWorkUseCaseTest.kt` | 3 | |
| 19 | `domain/parser/AppParserRegistryRoutingTest.kt` | 6 | |
| 20 | `domain/parser/AppParserRegistryTest.kt` | 8 | |
| 21 | `domain/parser/GenericTransactionParserTest.kt` | 21 | |
| 22 | `domain/parser/GoogleWalletParserTest.kt` | 18 | |
| 23 | `domain/parser/GreekBankParserTest.kt` | 10 | |
| 24 | `domain/parser/NBGReproTest.kt` | 1 | |
| 25 | `domain/parser/RevolutParserTest.kt` | 25 | |
| 26 | `domain/parser/SmsParserTest.kt` | 17 | |
| 27 | `domain/parser/TransferDirectionDetectorTest.kt` | 52 | Highest-value parser test |
| 28 | `domain/receipt/BankStatementParserTest.kt` | 17 | |
| 29 | `domain/receipt/BitmapConcurrencyTest.kt` | 13 | |
| 30 | `domain/receipt/EnhancedMerchantExtractorTest.kt` | 4 | |
| 31 | `domain/receipt/GreekNormalizationTest.kt` | 5 | |
| 32 | `domain/receipt/OcrLanguageProcessorTest.kt` | 13 | |
| 33 | `domain/receipt/ReceiptParserOcrPatternsTest.kt` | 59 | |
| 34 | `domain/receipt/ReceiptParserTest.kt` | 20 | |
| 35 | `domain/receipt/WarrantyTextExtractorTest.kt` | 11 | Fixed in B02 |
| 36 | `domain/price/PriceProtectionTrackerTest.kt` | 19 | Fixed in B02 |
| 37 | `domain/export/AccountingExportPolicyTest.kt` | 3 | |
| 38 | `domain/export/ExpenseExportMapperTest.kt` | 2 | |
| 39 | `domain/receiptmatching/ReceiptTransactionMatcherTest.kt` | 2 | |

**Exit criteria:** All 39 files pass; parser tests consistent with cross-parser consistency checks.

---

## B08 — Domain / Health + Intelligence + Location + Logic + Model + Forecasting + Savings + Tax + Groups + UseCases + Widget (36 files)

**Goal:** Complete remaining domain tests — health scoring, ML intelligence, location engines, logic/synthesis, forecasting, savings, tax, groups, use cases, and widget.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `domain/health/FinancialHealthCalculatorBoundaryTest.kt` | 11 | |
| 2 | `domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt` | 3 | |
| 3 | `domain/health/FinancialHealthCalculatorTransactionTypeTest.kt` | 9 | |
| 4 | `domain/health/FinancialHealthScoreV2Test.kt` | 12 | |
| 5 | `domain/health/HealthScoreEdgeCaseTest.kt` | 5 | |
| 6 | `domain/health/HealthScoreGoldenTest.kt` | 2 | |
| 7 | `domain/intelligence/ConfidenceRouterEdgeCaseTest.kt` | 7 | |
| 8 | `domain/intelligence/ConfidenceRouterTest.kt` | 9 | |
| 9 | `domain/intelligence/DuplicateDetectionPolicyDedupeKeyTest.kt` | 6 | |
| 10 | `domain/intelligence/TransactionClassifierTest.kt` | 10 | |
| 11 | `domain/intelligence/ml/FeatureExtractorTest.kt` | 1 | |
| 12 | `domain/intelligence/ml/HybridExpenseClassifierTest.kt` | 12 | |
| 13 | `domain/intelligence/ml/MerchantNormalizerTest.kt` | 3 | |
| 14 | `domain/location/AreaSpendingEngineTest.kt` | 1 | |
| 15 | `domain/location/LocationResolverTest.kt` | 2 | |
| 16 | `domain/location/TravelDetectionEngineTest.kt` | 1 | |
| 17 | `domain/logic/RecurrenceCalculatorTest.kt` | 5 | |
| 18 | `domain/logic/RecurringExpenseEngineEmptyListTest.kt` | 4 | |
| 19 | `domain/logic/RecurringExpenseEngineTest.kt` | 14 | |
| 20 | `domain/logic/SplitCalculatorGoldenTest.kt` | 4 | |
| 21 | `domain/logic/SplitCalculatorTest.kt` | 12 | |
| 22 | `domain/logic/SynthesisEngineGoldenTest.kt` | 3 | |
| 23 | `domain/logic/SynthesisEngineTest.kt` | 9 | |
| 24 | `domain/model/CategoryBreakdownTest.kt` | 19 | |
| 25 | `domain/model/FinancialForecastModelTest.kt` | 2 | |
| 26 | `domain/model/PeriodTotalTest.kt` | 14 | |
| 27 | `domain/model/RecurringPatternModelTest.kt` | 2 | |
| 28 | `domain/model/dashboard/DashboardExpenseMapperTest.kt` | 4 | |
| 29 | `domain/forecasting/FinancialStressForecastEngineTest.kt` | 15 | |
| 30 | `domain/forecasting/ForecastInputAssemblerTest.kt` | 10 | |
| 31 | `domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt` | 8 | |
| 32 | `domain/forecasting/MergedRecurringPatternsProviderTest.kt` | 5 | |
| 33 | `domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt` | 1 | |
| 34 | `domain/forecasting/MonteCarloSpendingSimulatorTest.kt` | 1 | |
| 35 | `domain/savings/AutomatedSavingsRuleEngineTest.kt` | 8 | |
| 36 | `domain/savings/SavingsGamificationEngineTest.kt` | 7 | |

**Exit criteria:** All 36 files pass.

---

## B09 — Domain (cont.) + UI / Screens + Service + E2E + Consistency + Verification + Integration + Metrics + Receiver + Util (39 files)

**Goal:** Complete all remaining meaningful tests — UI ViewModels, services, end-to-end pipelines, consistency checks, verification, integration, metrics, and utilities.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `domain/savings/SmartSavingsEngineTest.kt` | 7 | |
| 2 | `domain/tax/TaxEstimatorTest.kt` | 14 | |
| 3 | `domain/groups/SettlementCalculatorTest.kt` | 6 | |
| 4 | `domain/groups/SharedExpenseBudgetOffsetEngineTest.kt` | 8 | |
| 5 | `domain/groups/SharedExpenseManagerTest.kt` | 14 | |
| 6 | `domain/groups/usecase/GroupUseCasesTest.kt` | 12 | |
| 7 | `domain/naturallanguage/NaturalLanguageSearchEngineVoiceInputTest.kt` | 3 | |
| 8 | `domain/reminder/BillReminderManagerTest.kt` | 5 | |
| 9 | `domain/widget/WidgetStyleRepositoryTest.kt` | 2 | |
| 10 | `domain/usecase/budget/CalculateBudgetStatusUseCaseTest.kt` | 4 | |
| 11 | `domain/usecase/budget/GetMonteCarloBudgetImpactUseCaseTest.kt` | 12 | |
| 12 | `domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt` | 14 | |
| 13 | `domain/usecase/expense/CategorizeExpenseUseCaseTest.kt` | 4 | |
| 14 | `domain/usecase/expense/DetectDuplicateExpenseUseCaseTest.kt` | 5 | |
| 15 | `domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt` | 6 | |
| 16 | `domain/usecase/savings/LifestyleSavingsPromptUseCaseTest.kt` | 9 | |
| 17 | `domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt` | 10 | |
| 18 | `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCaseTest.kt` | 8 | |
| 19 | `domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt` | 1 | |
| 20 | `ui/MainActivityDeepLinkTest.kt` | 2 | |
| 21 | `ui/screens/addexpense/AddExpenseViewModelTest.kt` | 4 | |
| 22 | `ui/screens/aisettings/AiSettingsViewModelTest.kt` | 8 | |
| 23 | `ui/screens/assistant/AssistantViewModelTest.kt` | 20 | |
| 24 | `ui/screens/budget/BudgetForecastingViewModelTest.kt` | 6 | |
| 25 | `ui/screens/carbon/CarbonFootprintScreenTest.kt` | 3 | |
| 26 | `ui/screens/carbon/CarbonFootprintViewModelTest.kt` | 8 | |
| 27 | `ui/screens/cashflow/CashFlowCalendarViewModelTest.kt` | 7 | |
| 28 | `ui/screens/challenge/SpendingChallengesViewModelTest.kt` | 3 | |
| 29 | `ui/screens/currency/CurrencyManagementScreenValidationTest.kt` | 4 | |
| 30 | `ui/screens/currency/CurrencyManagementViewModelTest.kt` | 4 | |
| 31 | `ui/screens/debug/DebugScreenTextTest.kt` | 5 | |
| 32 | `ui/screens/export/ExportOptionsViewModelTest.kt` | 8 | |
| 33 | `ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt` | 2 | |
| 34 | `ui/screens/groups/SharedExpenseGroupsViewModelTest.kt` | 7 | |
| 35 | `ui/screens/home/HomeScreenWidgetTest.kt` | 4 | |
| 36 | `ui/screens/home/HomeViewModelRecommendationTest.kt` | 24 | |
| 37 | `ui/screens/lifestyle/LifestyleInflationScreenTest.kt` | 6 | |
| 38 | `ui/screens/lifestyle/LifestyleInflationViewModelTest.kt` | 7 | |
| 39 | `ui/screens/price/PriceProtectionViewModelTest.kt` | 9 | |

**Exit criteria:** All 39 files pass; UI ViewModel tests use TestDispatcher consistently.

---

## B10 — Remaining UI Screens + Service + E2E + Consistency + Verification + Integration + Metrics + Instrumented (40 files)

**Goal:** Complete the final meaningful-test batch: remaining UI screens, services, e2e, consistency, verification, integration, metrics, and all instrumented tests.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt` | 4 | |
| 2 | `ui/screens/recurringmanual/ManualRecurringExpenseViewModelTest.kt` | 4 | |
| 3 | `ui/screens/review/ReviewScreenTransactionTypeParserTest.kt` | 3 | |
| 4 | `ui/screens/review/ReviewScreenTransferDirectionParserTest.kt` | 3 | |
| 5 | `ui/screens/savings/SavingsGoalsViewModelTest.kt` | 7 | |
| 6 | `ui/screens/split/VisualSplitEditorScreenStateTest.kt` | 11 | |
| 7 | `ui/screens/split/VisualSplitViewModelTest.kt` | 5 | |
| 8 | `ui/screens/subscription/SubscriptionManagementViewModelTest.kt` | 4 | |
| 9 | `ui/screens/warranty/WarrantyTrackerViewModelTest.kt` | 6 | |
| 10 | `ui/components/emptystate/ContextualActionRegistryTest.kt` | 7 | |
| 11 | `ui/util/ClipboardAmountParserTest.kt` | 3 | |
| 12 | `service/NavigationTargetResolverTest.kt` | 32 | |
| 13 | `service/NotificationCaptureServiceFallbackTest.kt` | 8 | |
| 14 | `service/NotificationFilterTest.kt` | 18 | |
| 15 | `service/RecommendationCacheServiceTest.kt` | 18 | |
| 16 | `service/RecommendationDeduplicatorTest.kt` | 11 | |
| 17 | `service/RecommendationDismissalHandlerTest.kt` | 23 | |
| 18 | `service/RecommendationLifecycleManagerTest.kt` | 32 | |
| 19 | `service/RecommendationStateManagerTest.kt` | 30 | |
| 20 | `service/TransactionFilterSerializerTest.kt` | 16 | |
| 21 | `service/receiptmatching/ReceiptMatchingWorkerTest.kt` | 6 | |
| 22 | `service/warranty/WarrantyExpirationWorkerTest.kt` | 6 | |
| 23 | `e2e/AnalyticsPipelineTest.kt` | 5 | |
| 24 | `e2e/BudgetAlertPipelineTest.kt` | 4 | |
| 25 | `e2e/CategoryBreakdownFlowTest.kt` | 1 | |
| 26 | `e2e/DailyAverageFlowTest.kt` | 1 | |
| 27 | `e2e/DateBoundaryFlowTest.kt` | 1 | |
| 28 | `e2e/EmptyDataFlowTest.kt` | 1 | |
| 29 | `e2e/GroupSettlementPipelineTest.kt` | 4 | |
| 30 | `e2e/MonthlyTotalFlowTest.kt` | 1 | |
| 31 | `e2e/NotificationExpenseDashboardPipelineTest.kt` | 3 | File I/O: audit temp file cleanup |
| 32 | `e2e/ReceiptProcessingPipelineTest.kt` | 4 | |
| 33 | `e2e/SharedExpenseFlowTest.kt` | 1 | |
| 34 | `consistency/ConstantsConsistencyTest.kt` | 2 | |
| 35 | `consistency/CrossParserConsistencyTest.kt` | 8 | |
| 36 | `consistency/CurrencyNormalizerConsistencyTest.kt` | 10 | |
| 37 | `consistency/DedupeKeyProducerConsistencyTest.kt` | 12 | |
| 38 | `consistency/DuplicateLogicConsistencyIntegrationTest.kt` | 23 | |
| 39 | `consistency/EmptyZeroNullResilienceTest.kt` | 2 | |
| 40 | `consistency/FinancialArithmeticPrecisionTest.kt` | 4 | |

**Exit criteria:** All 40 files pass; e2e pipeline tests use FlowPipelineTestHarness cleanly.

---

## B11 — Consistency (cont.) + Verification + Integration + Metrics + Receiver + Util + Instrumented (36 files)

**Goal:** Complete all remaining cross-cutting test areas and instrumented tests.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `consistency/HaversineConsistencyTest.kt` | 5 | |
| 2 | `consistency/MerchantKeyConsistencyTest.kt` | 3 | |
| 3 | `consistency/MerchantKeyCrossConsumerConsistencyTest.kt` | 10 | |
| 4 | `consistency/SharedUtilityConsistencyTest.kt` | 15 | |
| 5 | `consistency/TemporalConsistencyTest.kt` | 4 | |
| 6 | `consistency/TimePeriodAnalyticsAlignmentTest.kt` | 6 | |
| 7 | `verification/CarbonFootprintTest.kt` | 7 | |
| 8 | `verification/CrossGroupIntegrationTest.kt` | 9 | |
| 9 | `verification/CrossSourceVerificationTest.kt` | 6 | |
| 10 | `verification/GoldenMasterVerificationTest.kt` | 22 | |
| 11 | `verification/LifestyleAnalysisTest.kt` | 7 | |
| 12 | `verification/SharedExpenseTest.kt` | 15 | |
| 13 | `integration/CategorizationPipelineIntegrationTest.kt` | 20 | |
| 14 | `integration/EffectiveAmountPipelineIntegrationTest.kt` | 1 | |
| 15 | `integration/ExpenseCreationPipelineIntegrationTest.kt` | 16 | |
| 16 | `integration/MultiCurrencyAnalyticsTest.kt` | 4 | |
| 17 | `metrics/DashboardWidgetConsistencyTest.kt` | 6 | |
| 18 | `metrics/EffectiveAmountConsistencyTest.kt` | 10 | |
| 19 | `metrics/GoldenAnalyticsDatasetTest.kt` | 8 | |
| 20 | `metrics/TimePeriodAlignmentTest.kt` | 21 | |
| 21 | `receiver/BootReceiverStressTest.kt` | 4 | |
| 22 | `receiver/ServiceRestartReceiverStressTest.kt` | 3 | |
| 23 | `util/CsvExpenseImporterTest.kt` | 12 | |
| 24 | `data/database/DatabaseMigrationTest.kt` | 63 | Instrumented — audit `assume` statements |
| 25 | `data/database/MigrationContractTest.kt` | 10 | Instrumented |
| 26 | `data/database/dao/AiArtifactDaoTest.kt` | 14 | Instrumented |
| 27 | `data/database/dao/AiChatMessageDaoTest.kt` | 5 | Instrumented |
| 28 | `data/database/dao/AiChatSessionDaoTest.kt` | 5 | Instrumented |
| 29 | `data/database/dao/BudgetDaoTest.kt` | 18 | Instrumented |
| 30 | `data/database/dao/CategoryDaoTest.kt` | 4 | Instrumented |
| 31 | `data/database/dao/ComplexQueryTest.kt` | 18 | Instrumented |
| 32 | `data/database/dao/DaoStressTest.kt` | 20 | Instrumented |
| 33 | `data/database/dao/DedupeKeyUniquenessRegressionTest.kt` | 3 | Instrumented |
| 34 | `data/database/dao/ExchangeRateDaoTest.kt` | 4 | Instrumented |
| 35 | `data/database/dao/ExpenseDaoTest.kt` | 40 | Instrumented |
| 36 | `data/database/dao/ExpenseGroupDaoTest.kt` | 4 | Instrumented |

**Exit criteria:** All 36 files pass; `DatabaseMigrationTest` has no silently-skipped cases.

---

## B12 — Instrumented DAOs (cont.) + Location Instrumented (13 files)

**Goal:** Complete instrumented test suite.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `data/database/dao/FreshInstallBatch8ParityTest.kt` | 23 | Instrumented |
| 2 | `data/database/dao/FreshInstallIndexParityTest.kt` | 5 | Instrumented — time-dependent, inject FakeTimeProvider |
| 3 | `data/database/dao/GroupMemberDaoTest.kt` | 16 | Instrumented |
| 4 | `data/database/dao/MerchantLocationDaoTest.kt` | 14 | Instrumented |
| 5 | `data/database/dao/MerchantNormalizationDaoTest.kt` | 12 | Instrumented |
| 6 | `data/database/dao/PendingReviewDaoTest.kt` | 8 | Instrumented |
| 7 | `data/database/dao/RecommendationDaoTest.kt` | 4 | Instrumented |
| 8 | `data/database/dao/RecurringExpenseDaoTest.kt` | 7 | Instrumented |
| 9 | `data/database/dao/SavingsGoalDaoTest.kt` | 16 | Instrumented |
| 10 | `data/database/dao/ScannedReceiptDaoTest.kt` | 7 | Instrumented |
| 11 | `data/database/dao/UserCorrectionDaoTest.kt` | 16 | Instrumented |
| 12 | `data/database/dao/WarrantyDaoTest.kt` | 4 | Instrumented |
| 13 | `data/location/MerchantKeyBackfillWorkerTest.kt` | 3 | Instrumented |

**Exit criteria:** All 13 instrumented files pass on emulator/device.

---

## B13 — Marginal Tests: Minimal-Effort Batch 1 — Domain Stress & Utility Stress (38 files)

**Goal:** Process marginal tests with minimal effort: verify they compile and pass, fix obvious breakage, but do not refactor. Grouped by proximity for parallel runs.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `domain/analytics/AdvancedAnalyticsEngineStressTest.kt` | 33 | Marginal — reimplements math in test body |
| 2 | `domain/analytics/AnalyticsStressTest.kt` | 1 | Marginal |
| 3 | `domain/analytics/InsightsEngineStressTest.kt` | 27 | Marginal |
| 4 | `domain/analytics/SpendingPaceCalculatorDeepTest.kt` | 6 | (already in B06 if overlapping — confirm no duplicate) |
| 5 | `domain/budget/BudgetCalculatorStressTest.kt` | 25 | Marginal — verify non-ignored |
| 6 | `domain/budget/BudgetMonitorStressTest.kt` | 11 | Marginal |
| 7 | `domain/categorization/CategorizationEngineStressTest.kt` | 33 | Marginal |
| 8 | `domain/categorization/ContextualInferenceEngineStressTest.kt` | 36 | Marginal |
| 9 | `domain/categorization/MerchantCanonicalizerStressTest.kt` | 8 | Marginal |
| 10 | `domain/categorization/SemanticKeywordMatcherStressTest.kt` | 6 | Marginal |
| 11 | `domain/currency/CurrencyConverterStressTest.kt` | 2 | Marginal |
| 12 | `domain/groups/SettlementCalculatorStressTest.kt` | 3 | Marginal |
| 13 | `domain/intelligence/ml/HybridExpenseClassifierStressTest.kt` | 28 | Marginal |
| 14 | `domain/intelligence/ml/MerchantNormalizerStressTest.kt` | 12 | Marginal |
| 15 | `domain/location/AreaSpendingEngineStressTest.kt` | 4 | Marginal |
| 16 | `domain/location/LocationInsightsEngineStressTest.kt` | 6 | Marginal |
| 17 | `domain/location/LocationResolverStressTest.kt` | 12 | Marginal |
| 18 | `domain/location/SpendingHeatmapEngineStressTest.kt` | 32 | Marginal |
| 19 | `domain/logic/RecurringExpenseEngineStressTest.kt` | 28 | Marginal |
| 20 | `domain/logic/SplitCalculatorStressTest.kt` | 3 | Marginal |
| 21 | `domain/logic/SynthesisEngineStressTest.kt` | 56 | Marginal — high method count |
| 22 | `domain/util/AmountUtilsStressTest.kt` | 65 | Marginal — test utility fixture |
| 23 | `domain/util/MerchantCleanerStressTest.kt` | 35 | Marginal |
| 24 | `domain/util/MerchantKeyGeneratorStressTest.kt` | 29 | Marginal |
| 25 | `domain/util/StatisticsUtilsStressTest.kt` | 35 | Marginal — test utility fixture |
| 26 | `domain/util/StringDistanceUtilsStressTest.kt` | 28 | Marginal — test utility fixture |
| 27 | `domain/util/TimePeriodUtilsStressTest.kt` | 40 | Marginal — test utility fixture |
| 28 | `domain/util/TimePeriodUtilsTest.kt` | 46 | Marginal — test utility fixture |
| 29 | `domain/util/TimePeriodUtilsValidationTest.kt` | 77 | Marginal — test utility fixture |
| 30 | `domain/util/NotificationIdGeneratorTest.kt` | 36 | Marginal |
| 31 | `domain/util/MerchantKeyGeneratorTest.kt` | 16 | Marginal |
| 32 | `domain/util/MoneyTest.kt` | 31 | @Ignore'd — evaluate re-enable or delete |
| 33 | `domain/util/AmountUtilsTest.kt` | 6 | Test utility fixture |
| 34 | `domain/util/BKTreeTest.kt` | 1 | Marginal |
| 35 | `domain/parser/AppParserRegistryStressTest.kt` | 26 | Marginal |
| 36 | `domain/parser/GenericTransactionParserStressTest.kt` | 11 | Marginal |
| 37 | `domain/parser/GreekBankParserStressTest.kt` | 10 | Marginal |
| 38 | `domain/parser/TransferDirectionDetectorStressTest.kt` | 16 | Marginal |

**Exit criteria:** All non-@Ignore'd files compile and pass; @Ignore'd files have documented enable/delete decision.

---

## B14 — Marginal Tests: Minimal-Effort Batch 2 — UI Stress + Metrics Stress + Other Marginal (24 files)

**Goal:** Process remaining marginal tests: UI ViewModel stress tests, metrics stress, receiver stress, and remaining marginal files.

| # | File | @Tests | Notes |
|---|------|--------|-------|
| 1 | `ui/MainViewModelStressTest.kt` | 5 | @Ignore'd — evaluate |
| 2 | `ui/screens/addexpense/AddExpenseViewModelStressTest.kt` | 8 | @Ignore'd — evaluate |
| 3 | `ui/screens/aisettings/AiSettingsScreenTextTest.kt` | 6 | @Ignore'd — evaluate |
| 4 | `ui/screens/budget/BudgetViewModelStressTest.kt` | 16 | @Ignore'd — evaluate; manipulates Dispatchers.Main |
| 5 | `ui/screens/debug/DebugViewModelStressTest.kt` | 7 | @Ignore'd — evaluate; backgroundScope risk |
| 6 | `ui/screens/home/HomeViewModelStressTest.kt` | 20 | @Ignore'd — evaluate; shared mutable configFlow |
| 7 | `service/NotificationCaptureServiceStressTest.kt` | 3 | @Ignore'd — HIGH timing risk (delay/timeout) |
| 8 | `data/database/TransactionRollbackTest.kt` | 15 | @Ignore'd — evaluate |
| 9 | `domain/parser/parsers/RevolutParserStressTest.kt` | 20 | Marginal |
| 10 | `domain/budget/BudgetForecastingEngineStubTest.kt` | 2 | Marginal — stub fixture |
| 11 | `domain/model/dashboard/DashboardExpenseMapperTest.kt` | 4 | (already in B08 if overlapping — confirm) |
| 12 | `metrics/DashboardWidgetConsistencyStressTest.kt` | 3 | Marginal |
| 13 | `metrics/EffectiveAmountConsistencyStressTest.kt` | 7 | Marginal |
| 14 | `metrics/TimePeriodAlignmentStressTest.kt` | 5 | Marginal |
| 15 | `domain/location/TravelDetectionEngineStressTest.kt` | 7 | @Ignore'd — evaluate |
| 16 | `domain/tax/TaxCalculationTest.kt` | 35 | @Ignore'd — fixed in B02 or evaluate |
| 17 | `domain/export/CsvEscapingTest.kt` | 25 | @Ignore'd — fixed in B02 |
| 18 | `domain/logic/CustomSplitParserTest.kt` | 12 | @Ignore'd — fixed in B02 |
| 19 | `domain/split/SplitCalculationPrecisionTest.kt` | 22 | @Ignore'd — fixed in B02 |
| 20 | `domain/receipt/WarrantyTextExtractorTest.kt` | 11 | @Ignore'd — fixed in B02 (re-verify) |
| 21 | `domain/ai/usecase/ReceiptAssistInputBuilderTest.kt` | 3 | @Ignore'd — fixed in B02 (re-verify) |
| 22 | `domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt` | 9 | @Ignore'd — fixed in B02 (re-verify) |
| 23 | `domain/price/PriceProtectionTrackerTest.kt` | 19 | @Ignore'd — fixed in B02 (re-verify) |
| 24 | `consistency/EmptyZeroNullResilienceTest.kt` | 2 | (already in B10 if overlapping — confirm) |

**Exit criteria:** B02 fixes re-verified; all @Ignore'd stress tests have explicit enable/delete/keep-ignored decision documented.

---

## B15 — Dead / Trivial Cleanup (~50 files)

**Goal:** Remove or archive dead test code and trivial tests that provide no coverage value. This batch is net-negative in line count.

### 15A. Dead @Ignore'd Stress Suites — Delete or Archive

| # | File | @Tests | Action | Reason |
|---|------|--------|--------|--------|
| 1 | `consistency/ConcurrencyStateRaceTest.kt` | 4 | **Delete** | @Ignore'd — race-sensitive by design; never runs in CI |
| 2 | `consistency/CrossParserConsistencyStressTest.kt` | 4 | **Delete** | @Ignore'd — duplicates `CrossParserConsistencyTest.kt` |
| 3 | `consistency/SharedUtilityConsistencyStressTest.kt` | 6 | **Delete** | @Ignore'd — duplicates `SharedUtilityConsistencyTest.kt` |
| 4 | `data/database/entity/ExpenseEntityStressTest.kt` | 42 | **Delete** | @Ignore'd — entity stress never runs; covered by model tests |
| 5 | `data/repository/BudgetRepositoryStressTest.kt` | 18 | **Delete** | @Ignore'd — duplicates `BudgetRepositoryTest` coverage |
| 6 | `data/repository/CategoryRepositoryStressTest.kt` | 4 | **Delete** | @Ignore'd — duplicates `CategoryRepositoryTest` |
| 7 | `data/repository/ExpenseRepositoryStressTest.kt` | 13 | **Delete** | @Ignore'd — duplicates `ExpenseRepositoryTest` |
| 8 | `data/repository/NotificationProcessingPipelineStressTest.kt` | 28 | **Delete** | @Ignore'd — duplicates reliability/oversized pipeline tests |
| 9 | `data/repository/NotificationRepositoryStressTest.kt` | 21 | **Delete** | @Ignore'd — no non-stress counterpart worth keeping |
| 10 | `data/repository/ReceiptRepositoryStressTest.kt` | 12 | **Delete** | @Ignore'd — covered by statement duplicate test |
| 11 | `data/repository/ReviewQueueRepositoryStressTest.kt` | 8 | **Delete** | @Ignore'd — duplicates `ReviewQueueRepositoryTest` |
| 12 | `data/location/CompositeGeocodingServiceStressTest.kt` | 8 | **Delete** | @Ignore'd — duplicates `CompositeGeocodingServiceTest.kt` |
| 13 | `data/database/TransactionRollbackTest.kt` | 15 | **Delete** | @Ignore'd — DB rollback stress never runs |
| 14 | `ui/screens/analytics/AnalyticsStateStressTest.kt` | 20 | **Delete** | @Ignore'd — duplicates `AnalyticsViewModelStressTest` |
| 15 | `ui/screens/analytics/AnalyticsViewModelStressTest.kt` | 8 | **Delete** | @Ignore'd — covered by `AnalyticsViewModel` regular tests |
| 16 | `ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt` | 19 | **Delete** | @Ignore'd — no regular counterpart |
| 17 | `ui/screens/review/ReviewViewModelStressTest.kt` | 37 | **Delete** | @Ignore'd — no regular counterpart |
| 18 | `ui/screens/transactions/TransactionsViewModelStressTest.kt` | 16 | **Delete** | @Ignore'd — no regular counterpart |
| 19 | `ui/screens/map/SpendingMapViewModelStressTest.kt` | 10 | **Delete** | @Ignore'd — no regular counterpart |
| 20 | `ui/MainViewModelStressTest.kt` | 5 | **Delete** | @Ignore'd — no regular counterpart |
| 21 | `ui/screens/addexpense/AddExpenseViewModelStressTest.kt` | 8 | **Delete** | @Ignore'd — duplicates `AddExpenseViewModelTest` |
| 22 | `ui/screens/aisettings/AiSettingsScreenTextTest.kt` | 6 | **Delete** | @Ignore'd — source-string assertions, brittle |
| 23 | `ui/screens/budget/BudgetViewModelStressTest.kt` | 16 | **Delete** | @Ignore'd — manipulates Dispatchers.Main; unsafe |
| 24 | `ui/screens/debug/DebugViewModelStressTest.kt` | 7 | **Delete** | @Ignore'd — backgroundScope risk |
| 25 | `ui/screens/home/HomeViewModelStressTest.kt` | 20 | **Delete** | @Ignore'd — shared mutable configFlow |
| 26 | `service/NotificationCaptureServiceStressTest.kt` | 3 | **Delete** | @Ignore'd — HIGH timing risk (delay/timeout) |

### 15B. Trivial Tests — Delete

| # | File | @Tests | Action | Reason |
|---|------|--------|--------|--------|
| 27 | `domain/analytics/RecurringIntervalLogicTest.kt` | 1 | **Delete** | Tests rounding math in test body, not app code |
| 28 | `ui/screens/transactions/TransactionsScreenTest.kt` | 1 | **Delete** | Source-string assertions against implementation text |
| 29 | `domain/location/AreaSpendingEngineTest.kt` | 1 | **Delete** | Single thin test with no edge-case depth |
| 30 | `domain/model/FinancialForecastModelTest.kt` | 2 | **Delete** | Low-value model shape checks |
| 31 | `domain/analytics/AdvancedAnalyticsEngineStressTest.kt` | 33 | **Delete** | Reimplements mean/median/stddev/percentile logic in test body |
| 32 | `domain/util/MoneyTest.kt` | 31 | **Delete** | @Ignore'd — value class boxing issues; covered by AmountUtils |
| 33 | `domain/tax/TaxCalculationTest.kt` | 35 | **Delete** | @Ignore'd — if not fixable in B02 |
| 34 | `domain/budget/BudgetForecastingEngineStubTest.kt` | 2 | **Archive** | Stub fixture — not a real test |

### 15C. Infrastructure / Helper Files — Audit & Clean

| # | File | Action | Reason |
|---|------|--------|--------|
| 35 | `AnalyticsEngineTestBase.kt` | **Audit** | Remove if no subclass uses it after dead-suite deletion |
| 36 | `TestUtils.kt` | **Audit** | Remove dead helpers after dead-suite deletion |
| 37 | `util/FlowTestUtils.kt` | **Audit** | Remove dead helpers after dead-suite deletion |
| 38 | `util/HiltTestUtils.kt` | **Audit** | Remove dead helpers after dead-suite deletion |
| 39 | `util/ViewModelTestUtils.kt` | **Audit** | Remove dead helpers after dead-suite deletion |
| 40 | `e2e/FlowPipelineTestHarness.kt` | **Audit** | Keep if e2e pipeline tests remain |
| 41 | `GoldenAnalyticsDataset.kt` | **Audit** | Verify parity after analytics cleanup |
| 42 | `ExpectedResults.kt` | **Audit** | Verify parity after parser cleanup |
| 43 | `GoldenDataSets.kt` | **Audit** | Verify parity after model cleanup |
| 44 | `FakeTimeProvider.kt` | **Keep** | Required for time-dependent test fixes |
| 45 | `domain/util/FakeTimeProvider.kt` | **Dedup** | Merge with #44 if duplicate |

### 15D. Remaining @Ignore'd — Final Decision

| # | File | @Tests | Action | Reason |
|---|------|--------|--------|--------|
| 46 | `domain/location/TravelDetectionEngineStressTest.kt` | 7 | **Delete** | @Ignore'd — duplicates `TravelDetectionEngineTest.kt` |
| 47 | `domain/export/CsvEscapingTest.kt` | 25 | **Keep if B02 fix works** | Re-enable after assertion drift fix |
| 48 | `domain/logic/CustomSplitParserTest.kt` | 12 | **Keep if B02 fix works** | Re-enable after parser contract fix |
| 49 | `domain/split/SplitCalculationPrecisionTest.kt` | 22 | **Keep if B02 fix works** | Re-enable after assertion lib fix |
| 50 | `domain/price/PriceProtectionTrackerTest.kt` | 19 | **Keep if B02 fix works** | Re-enable after assertion fix |

**Exit criteria:** All deleted files removed from VCS; remaining files pass; no @Ignore annotations remain except for explicitly documented keep-ignored decisions.

---

## B16 — Domain / AI UseCase — @Ignore Re-verification (6 files)

**Goal:** Re-verify the 6 files fixed in B02 are now passing with @Ignore removed, and integrate into CI.

| # | File | @Tests | B02 Status | B16 Action |
|---|------|--------|------------|------------|
| 1 | `domain/receipt/WarrantyTextExtractorTest.kt` | 11 | Fixed | Remove @Ignore, verify green |
| 2 | `domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt` | 9 | Fixed | Remove @Ignore, verify green |
| 3 | `domain/ai/usecase/ReceiptAssistInputBuilderTest.kt` | 3 | Fixed | Remove @Ignore, verify green |
| 4 | `domain/export/CsvEscapingTest.kt` | 25 | Fixed | Remove @Ignore, verify green |
| 5 | `domain/logic/CustomSplitParserTest.kt` | 12 | Fixed | Remove @Ignore, verify green |
| 6 | `domain/split/SplitCalculationPrecisionTest.kt` | 22 | Fixed | Remove @Ignore, verify green |

**Exit criteria:** All 6 files pass without @Ignore; CI pipeline includes them.

---

## Summary Table

| Batch | Area | Files | Priority | Est. Effort |
|-------|------|-------|----------|-------------|
| B01 | Infrastructure & Build Config | 15 | 🔴 Critical | 2–3 days |
| B02 | Quick Wins — Fix @Ignore'd Assertion Drift | 6 | 🔴 Critical | 1–2 days |
| B03 | Data / Repository | 29 | 🔴 High | 3–4 days |
| B04 | Data / AI Providers + Database + Other Data | 34 | 🟡 Medium-High | 3–4 days |
| B05 | Data / Email + Location + Security + AI Models + Analytics (start) | 37 | 🟡 Medium-High | 3–4 days |
| B06 | Domain / Analytics (cont.) + Budget + Categorization + Currency + Small Domains | 39 | 🟡 Medium | 3–4 days |
| B07 | Domain / AI UseCases + Parser + Receipt + Price + Export | 39 | 🟡 Medium | 3–4 days |
| B08 | Domain / Health + Intelligence + Location + Logic + Model + Forecasting + Savings | 36 | 🟡 Medium | 2–3 days |
| B09 | Domain (cont.) + UI / Screens (part 1) + UseCases | 39 | 🟡 Medium | 2–3 days |
| B10 | UI / Screens (part 2) + Service + E2E + Consistency (start) | 40 | 🟢 Low-Medium | 2–3 days |
| B11 | Consistency (cont.) + Verification + Integration + Metrics + Instrumented (part 1) | 36 | 🟢 Low-Medium | 2–3 days |
| B12 | Instrumented DAOs (cont.) + Location Instrumented | 13 | 🟢 Low | 1–2 days |
| B13 | Marginal Tests — Domain Stress & Utility Stress | 38 | 🔵 Minimal | 1–2 days |
| B14 | Marginal Tests — UI Stress + Metrics Stress + B02 Re-verify | 24 | 🔵 Minimal | 1 day |
| B15 | Dead / Trivial Cleanup | ~50 | 🔴 Critical (debt) | 2–3 days |
| B16 | @Ignore Re-verification & CI Integration | 6 | 🔴 Critical | 0.5 day |
| **Total** | | **~435** | | **~30–40 days** |

### Execution Order Recommendation

1. **B01** → unblock parallelism & CI filtering
2. **B02** → quick wins, re-enable ignored tests
3. **B15** → delete dead weight first (reduces noise for all subsequent batches)
4. **B16** → confirm B02 fixes stick in CI
5. **B03–B12** → domain-area batches in dependency order (data first, then domain, then UI, then cross-cutting)
6. **B13–B14** → marginal tests last (minimum effort, maximum coverage already achieved)
