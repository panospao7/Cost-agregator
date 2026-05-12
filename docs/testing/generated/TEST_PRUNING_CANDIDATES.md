# Test Pruning Candidates

Generated from audit of 489 test files. Grouped by recommended action.

---

## Delete Now (can delete immediately, no replacement needed)

| # | File | Reason |
|---|------|--------|
| 1 | `data/database/TransactionRollbackTest.kt` | Simulated rollback only — never exercises real Room transactions |
| 2 | `domain/budget/BudgetForecastingEngineStubTest.kt` | Tests that `updateForecastAccuracy` does nothing (dead-code documentation) |
| 3 | `domain/usecase/savings/...` (stub tests) | Tests a no-op stub, verifies nothing happens |
| 4 | `ui/screens/debug/DebugScreenTextTest.kt` | Reads production Kotlin file from disk, asserts string literals |
| 5 | `ui/MainActivityDeepLinkTest.kt` | Source-text assertion — reads .kt file and greps for string patterns |
| 6 | `ui/screens/home/HomeScreenWidgetTest.kt` | Source-text assertion — greps production source for widget strings |
| 7 | `ui/screens/transactions/TransactionsScreenTest.kt` | Source-text assertion — greps production source |
| 8 | `scenarios/PrivacyGateEnforcementScenarioTest.kt` | Complete duplicate of PrivacyCloudLocationDeniedScenarioTest |
| 9 | `scenarios/SpeechInputGatewayLifecycleTest.kt` | 100% tautological — tests mocks that the test itself created |
| 10 | `service/NotificationCaptureServiceStressTest.kt` | Already @Ignore'd, self-declared "run manually" |
| 11 | `domain/intelligence/ml/FeatureExtractorTest.kt` | 1 trivial test (16 lines), no real feature extraction |
| 12 | `domain/model/CategoryBreakdownTest.kt` | Data-class tautology — 27 tests of zero business value |
| 13 | `domain/model/PeriodTotalTest.kt` | Data-class tautology — 6 tests of zero business value |
| 14 | `domain/location/TravelDetectionEngineTest.kt` | Redundant with TravelDetectionEngineStressTest |
| 15 | `domain/config/AppConfigTest.kt` | Tests static string constants — no production logic |
| 16 | `ui/screens/bank/BankConnectionsViewModelTest.kt` | Stub ViewModel test — no real dependencies, tests nothing |
| 17 | `ui/screens/analytics/AnalyticsStateStressTest.kt` | 18 tests asserting default field values of a data class |
| 18 | `util/FlowTestUtils.kt` | Dead utility object — 8 lines, no code |

---

## Delete After Replacement (need replacement test first)

| # | File | What's needed |
|---|------|---------------|
| 1 | `e2e/CategoryBreakdownFlowTest.kt` | Needs real DB-backed category breakdown pipeline test |
| 2 | `e2e/DateBoundaryFlowTest.kt` | Replace with deterministic boundary contract test |
| 3 | `e2e/EmptyDataFlowTest.kt` | Replace with proper empty-data integration test |
| 4 | `e2e/MonthlyTotalFlowTest.kt` | Replace with aggregate-pipeline test |
| 5 | `e2e/SharedExpenseFlowTest.kt` | Replace with real group pipeline scenario |
| 6 | `e2e/DailyAverageFlowTest.kt` | Replace with proper analytics pipeline test |

---

## Rewrite Candidates

| # | File | Current Weakness | Desired |
|---|------|-----------------|---------|
| 1 | `integration/CategorizationPipelineIntegrationTest.kt` | Mislabeled "integration" — is actually utility unit test; no real pipeline | Real DB-backed categorization pipeline |
| 2 | `integration/ExpenseCreationPipelineIntegrationTest.kt` | Same — verifies test-created mock behavior | Real lifecycle coordinator test |
| 3 | `e2e/AnalyticsPipelineTest.kt` | Uses deprecated DAO methods, mock-heavy | Real analytics pipeline with MultiCurrencyRepository |
| 4 | `e2e/FlowPipelineTestHarness.kt` | Infrastructure file with deprecated DAO calls, no real pipeline | Clean pipeline harness with new APIs |
| 5 | `e2e/NotificationExpenseDashboardPipelineTest.kt` | Multiple constructor mismatches, fragile | Stable multi-pipeline test |
| 6 | `e2e/ReceiptProcessingPipelineTest.kt` | Mock-heavy, no real receipt processing | Real receipt lifecycle pipeline |
| 7 | `e2e/BudgetAlertPipelineTest.kt` | Tests mock-verified alert dispatch | Real budget alert scenario with DB |
| 8 | `e2e/GroupSettlementPipelineTest.kt` | Mixed mock/real, suspend context issues | Real group settlement pipeline |
| 9 | `domain/util/BKTreeTest.kt` | Only 1 trivial test for core data structure | Real BK-tree distance-search tests |
| 10 | `domain/categorization/AppParserRegistryTest.kt` | Duplicates AppParserRegistryRoutingTest | Merge into RoutingTest |
| 11 | `domain/categorization/SpendingHeatmapEngineStressTest.kt` | 32 tests, could be ≤8 | Compact deterministic tests |
| 12 | `ui/screens/home/HomeViewModelRecommendationTest.kt` | 501 lines but never instantiates HomeViewModel | Real ViewModel test |
| 13 | `data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt` | TODO-only skeleton, no real tests | Real DAO boundary tests |
| 14 | `data/database/dao/BackgroundJobRunDaoTest.kt` | Skeleton marked TODO | Real worker run DAO tests |
| 15 | `data/database/dao/InvestmentDaoTest.kt` | TODO marker, minimal assertions | Real investment DAO tests |
| 16 | `data/database/dao/PrivacyAuditDaoTest.kt` | TODO scaffold | Real privacy audit DAO tests |
| 17 | `data/database/dao/RawNotificationDaoTest.kt` | Mock-heavy, no Room | Real Room-backed raw notification test |

---

## Move Candidates

| # | File | From | To |
|---|------|------|-----|
| 1 | `scenarios/MoneyAggregateBuilderTest.kt` | scenarios/ | domain/core/money/ |
| 2 | `scenarios/MoneyAggregateConversionScenarioTest.kt` | scenarios/ | domain/core/money/ |
| 3 | `scenarios/MulticurrencyPartialRateScenarioTest.kt` | scenarios/ | domain/currency/ |
| 4 | `scenarios/MapMarkerConversionCurrencyTest.kt` | scenarios/ | domain/currency/ |
| 5 | `domain/currency/MultiCurrencyTestFixture.kt` | domain/currency/ | testfixtures/ (shared) |

---

## Move to Nightly Candidates

| # | File | Reason |
|---|------|--------|
| 1 | `data/database/dao/DaoStressTest.kt` | Stress/concurrency — too slow for PR CI |
| 2 | `data/database/dao/ComplexQueryTest.kt` | Large query test — nightly appropriate |
| 3 | `data/database/dao/DedupeKeyUniquenessRegressionTest.kt` | Heavy DB stress |
| 4 | `domain/categorization/CategorizationEngineStressTest.kt` | 33 tests, 10k concurrent |
| 5 | `domain/categorization/ContextualInferenceEngineStressTest.kt` | Heavy stress test |
| 6 | `domain/logic/SynthesisEngineStressTest.kt` | 56 tests, 1709 lines |
| 7 | `domain/analytics/AnalyticsStressTest.kt` | Heavy analytics stress |
| 8 | `domain/parser/GenericTransactionParserStressTest.kt` | Bulk parser stress |
| 9 | `domain/parser/GreekBankParserStressTest.kt` | Bulk parser stress |
| 10 | `receiver/BootReceiverStressTest.kt` | Environment-dependent |
| 11 | `receiver/ServiceRestartReceiverStressTest.kt` | Environment-dependent |
| 12 | `ui/screens/analytics/AnalyticsViewModelStressTest.kt` | Heavy ViewModel stress |
| 13 | `domain/util/TimePeriodUtilsStressTest.kt` | 824 lines, DST/timezone-dependent |
| 14 | `domain/util/AmountUtilsStressTest.kt` | Bulk stress test |
| 15 | `domain/util/MerchantCleanerStressTest.kt` | Bulk stress test |
| 16 | `domain/util/MerchantKeyGeneratorStressTest.kt` | Bulk stress test |
| 17 | `domain/util/StatisticsUtilsStressTest.kt` | Bulk stress test |
| 18 | `domain/util/StringDistanceUtilsStressTest.kt` | Bulk stress test |
| 19 | `data/repository/ReceiptRepositoryStressTest.kt` | Heavy repo stress |
| 20 | `data/repository/ExpenseRepositoryStressTest.kt` | Heavy repo stress |
| 21 | `data/repository/BudgetRepositoryStressTest.kt` | Heavy repo stress |
| 22 | `domain/categorization/CategorizationEngineDebugTest.kt` | Debug-only test |
| 23 | `domain/debug/ServiceDiagnosticsTest.kt` | Robolectric debug |
| 24 | `domain/forecasting/FinancialStressForecastEngineTest.kt` | Stress forecast |
| 25 | `data/repository/ReviewQueueRepositoryStressTest.kt` | Heavy repo stress |
| 26 | `data/repository/NotificationRepositoryStressTest.kt` | Heavy repo stress |
| 27 | `domain/intelligence/ml/MerchantNormalizerStressTest.kt` | Heavy ML stress |
| 28 | `data/repository/NotificationProcessingPipelineStressTest.kt` | Heavy pipeline stress |
| 29 | `domain/location/AreaSpendingEngineStressTest.kt` | Heavy location stress |
| 30 | `domain/location/LocationInsightsEngineStressTest.kt` | Heavy location stress |
| 31 | `domain/location/LocationResolverStressTest.kt` | Heavy location stress |
| 32 | `domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt` | Already marked @Ignore, run manually |

---

## Top 10 Most Impactful Removals

| Rank | File | Lines | Tests | Reason |
|------|------|-------|-------|--------|
| 1 | `domain/model/CategoryBreakdownTest.kt` | ~250 | 27 | Tests Kotlin data class equality |
| 2 | `domain/model/PeriodTotalTest.kt` | ~150 | 6 | Same — tests data class fields |
| 3 | `ui/screens/analytics/AnalyticsStateStressTest.kt` | ~200 | 18 | Default field value assertions |
| 4 | `ui/MainActivityDeepLinkTest.kt` | ~40 | ~3 | Greps production source |
| 5 | `ui/screens/home/HomeScreenWidgetTest.kt` | ~30 | ~2 | Greps production source |
| 6 | `ui/screens/transactions/TransactionsScreenTest.kt` | ~40 | ~3 | Greps production source |
| 7 | `scenarios/SpeechInputGatewayLifecycleTest.kt` | ~80 | ~5 | Tautological mock test |
| 8 | `scenarios/PrivacyGateEnforcementScenarioTest.kt` | ~100 | ~3 | Duplicate of existing test |
| 9 | `domain/intelligence/ml/FeatureExtractorTest.kt` | 16 | 1 | Trivial — no real feature extraction |
| 10 | `domain/budget/BudgetForecastingEngineStubTest.kt` | ~30 | 1 | Tests no-op stub |
