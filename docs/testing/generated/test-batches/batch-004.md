# Batch 004 — Test File Audit Report

**Audit date:** 2026-05-12  
**Batch file:** `docs/testing/generated/test-batches/batch-files-004.txt`  
**Total files analyzed:** 100  
**Auditor:** Automated (deepseek-v4-pro)

---

## Summary

| Action | Count |
|--------|-------|
| KEEP | 88 |
| DELETE | 3 |
| REWRITE | 2 |
| MOVE | 1 |
| MOVE_TO_NIGHTLY | 6 |
| UNKNOWN_NEEDS_LOCAL_RUN | 0 |

| Value | Count |
|-------|-------|
| P0_CRITICAL | 27 |
| P1_HIGH | 42 |
| P2_MEDIUM | 25 |
| P3_LOW | 4 |
| P4_NEGATIVE_VALUE | 2 |

---

## Detailed Classifications

| # | File | @Test | @Ignore | Assertions | Touches Production Classes | Action | Value | Test Type | Confidence |
|---|------|-------|---------|------------|---------------------------|--------|-------|-----------|------------|
| 1 | TransactionLifecycleCoordinatorTest.kt | 4 | No | Real (assertTrue, coVerify) | TransactionLifecycleCoordinator, ExpenseDao, TransactionEventDao | KEEP | P0_CRITICAL | unit | HIGH |
| 2 | CalculateBudgetStatusUseCaseTest.kt | 4 | No | Real (assertEquals, assertApproxEquals) | CalculateBudgetStatusUseCase, BudgetRepository | KEEP | P1_HIGH | unit | HIGH |
| 3 | GetMonteCarloBudgetImpactUseCaseTest.kt | 12 | No | Real (assertEquals, assertApproxEquals) | GetMonteCarloBudgetImpactUseCase | KEEP | P0_CRITICAL | unit | HIGH |
| 4 | ComputeMoneyRadarUseCaseTest.kt | 14 | No | Real (assertEquals, assertTrue) | ComputeMoneyRadarUseCase, MonteCarloSpendingSimulator | KEEP | P0_CRITICAL | unit | HIGH |
| 5 | CategorizeExpenseUseCaseTest.kt | 4 | No | Real (assertEquals, coVerify) | CategorizeExpenseUseCase, CategorizationEngine | KEEP | P1_HIGH | unit | HIGH |
| 6 | DetectDuplicateExpenseUseCaseTest.kt | 5 | No | Real (assertTrue, coVerify) | DetectDuplicateExpenseUseCase, ExpenseRepository | KEEP | P1_HIGH | unit | HIGH |
| 7 | CalculateFinancialForecastUseCaseTest.kt | 6 | No | Real (assertEquals, slot capture) | CalculateFinancialForecastUseCase, SynthesisEngine | KEEP | P0_CRITICAL | unit | HIGH |
| 8 | LifestyleSavingsPromptUseCaseTest.kt | 9 | No | Real (assertNotNull, assertApproxEquals, coVerify) | LifestyleSavingsPromptUseCase, LifestyleInflationDetector | KEEP | P1_HIGH | unit | HIGH |
| 9 | MonthlySavingsSweepUseCaseTest.kt | 10 | No | Real (assertNotNull, assertApproxEquals) | MonthlySavingsSweepUseCase, MonteCarloSpendingSimulator | KEEP | P0_CRITICAL | unit | HIGH |
| 10 | AutoCreateWarrantyFromReceiptUseCaseTest.kt | 8 | No | Real (assertTrue, assertEquals, slot capture) | AutoCreateWarrantyFromReceiptUseCase, WarrantyTrackerRepository | KEEP | P1_HIGH | unit | HIGH |
| 11 | AmountUtilsStressTest.kt | 30+ | No | Real (assertEquals on parsing) | AmountUtils | KEEP | P0_CRITICAL | stress | HIGH |
| 12 | AmountUtilsTest.kt | 6 | No | Real (assertEquals, assertNull) | AmountUtils | KEEP | P1_HIGH | unit | HIGH |
| 13 | BKTreeTest.kt | 1 | No | Real (Truth assertThat) | BKTree (StringBKTree) | REWRITE | P3_LOW | unit | HIGH |
| 14 | FakeTimeProvider.kt | 0 | No | N/A — test utility, not a test | TimeProvider (fake implementation) | KEEP | P1_HIGH | fixture | HIGH |
| 15 | MerchantCleanerStressTest.kt | 25+ | No | Real (assertEquals, assertFalse) | MerchantCleaner | KEEP | P1_HIGH | stress | HIGH |
| 16 | MerchantKeyGeneratorStressTest.kt | 25+ | No | Real (assertEquals, assertTrue, perf) | MerchantKeyGenerator | KEEP | P1_HIGH | stress | HIGH |
| 17 | MerchantKeyGeneratorTest.kt | 14 | No | Real (assertEquals, assertTrue) | MerchantKeyGenerator | KEEP | P0_CRITICAL | unit | HIGH |
| 18 | MoneyTest.kt | 15+ | Yes (2 tests) | Real (Truth assertThat, some ignored) | Money | KEEP | P0_CRITICAL | unit | HIGH |
| 19 | NotificationIdGeneratorTest.kt | 15+ | Yes? | Real (Truth assertThat) | NotificationIdGenerator | KEEP | P1_HIGH | unit | HIGH |
| 20 | StatisticsUtilsStressTest.kt | 15+ | No | Real (assertEquals, assertTrue) | StatisticsUtils | KEEP | P2_MEDIUM | stress | HIGH |
| 21 | StringDistanceUtilsStressTest.kt | 23 | No | Real (assertEquals, assertTrue) | StringDistanceUtils | KEEP | P2_MEDIUM | stress | HIGH |
| 22 | TimePeriodUtilsStressTest.kt | 40+ | No | Real (assertEquals, assertTrue) | TimePeriodUtils | MOVE_TO_NIGHTLY | P2_MEDIUM | stress | HIGH |
| 23 | TimePeriodUtilsTest.kt | 23 | No | Real (assertEquals, assertTrue) | TimePeriodUtils | KEEP | P0_CRITICAL | unit | HIGH |
| 24 | TimePeriodUtilsValidationTest.kt | 76 | No | Real (assertEquals, assertTrue) | TimePeriodUtils | KEEP | P0_CRITICAL | unit | HIGH |
| 25 | WidgetStyleRepositoryTest.kt | 2 | No | Real (assertEquals) | WidgetStyleRepository | MOVE_TO_NIGHTLY | P3_LOW | unit | MEDIUM |
| 26 | WorkerIdempotencyTest.kt | 4 | No | Real (assertEquals, assertTrue) | WorkerSpec | KEEP | P1_HIGH | unit | HIGH |
| 27 | AnalyticsPipelineTest.kt | 5 | No | Real (assertApproxEquals, assertEquals) | InsightsEngine, AnalyticsEngine | KEEP | P0_CRITICAL | e2e | HIGH |
| 28 | BudgetAlertPipelineTest.kt | 4 | No | Mock verify (verify, coVerify) | BudgetMonitor, NotificationService | KEEP | P1_HIGH | e2e | HIGH |
| 29 | CategoryBreakdownFlowTest.kt | 1 | No | Real (assertApproxEquals, assertEquals) | InsightsEngine, ExpenseRepository | KEEP | P1_HIGH | e2e | HIGH |
| 30 | DailyAverageFlowTest.kt | 1 | No | Real (assertApproxEquals, assertEquals) | AdvancedAnalyticsEngine | KEEP | P2_MEDIUM | e2e | HIGH |
| 31 | DateBoundaryFlowTest.kt | 1 | No | Real (assertApproxEquals, assertEquals) | ExpenseRepository, TimePeriodUtils | KEEP | P1_HIGH | e2e | HIGH |
| 32 | EmptyDataFlowTest.kt | 1 | No | Real (assertEquals, assertNotNull) | AnalyticsEngine, AnalyticsRepository | KEEP | P2_MEDIUM | e2e | HIGH |
| 33 | FlowPipelineTestHarness.kt | 0 | No | N/A — test harness, not a test | buildPipeline, FlowPipeline | KEEP | P1_HIGH | fixture | HIGH |
| 34 | GroupSettlementPipelineTest.kt | 4 | No | Real (assertApproxEquals, assertEquals) | SplitCalculator, SettlementCalculator | KEEP | P1_HIGH | e2e | HIGH |
| 35 | MonthlyTotalFlowTest.kt | 1 | No | Real (assertApproxEquals, assertEquals) | InsightsEngine, ExpenseRepository | KEEP | P1_HIGH | e2e | HIGH |
| 36 | NotificationExpenseDashboardPipelineTest.kt | 3 | No | Real (assertNotNull, assertEquals) | ComputeDashboardWidgetsUseCase, parser chain | KEEP | P0_CRITICAL | e2e | HIGH |
| 37 | ReceiptProcessingPipelineTest.kt | 4 | No | Real (assertApproxEquals, assertEquals) | ReceiptParser, CategorizationEngine | KEEP | P1_HIGH | e2e | HIGH |
| 38 | SharedExpenseFlowTest.kt | 1 | No | Real (assertApproxEquals) | ExpenseRepository, InsightsEngine | KEEP | P2_MEDIUM | e2e | HIGH |
| 39 | GuardSeededViolationTest.kt | 4 | No | Real (assertTrue, assertEquals) | Guard scripts (file system checks) | KEEP | P2_MEDIUM | guard | HIGH |
| 40 | CategorizationPipelineIntegrationTest.kt | 10 | No | Real (assertTrue, assertNotNull) | MerchantCleaner, MerchantKeyGenerator, AmountUtils | REWRITE | P3_LOW | integration | HIGH |
| 41 | EffectiveAmountPipelineIntegrationTest.kt | 1 | No | Real (assertApproxEquals) | EffectiveAmount, ExpenseRepository, AdvancedAnalyticsEngine | KEEP | P0_CRITICAL | integration | HIGH |
| 42 | ExpenseCreationPipelineIntegrationTest.kt | 10 | No | Real (assertTrue, assertNull) | MerchantCleaner, AmountUtils, StringDistanceUtils | REWRITE | P3_LOW | integration | HIGH |
| 43 | MultiCurrencyAnalyticsTest.kt | 4 | No | Real (assertTrue, assertEquals, coVerify) | MultiCurrencyRepository, CurrencyConverter | KEEP | P0_CRITICAL | integration | HIGH |
| 44 | DashboardWidgetConsistencyTest.kt | 4 | No | Real (assertEquals, assertNotNull) | ComputeDashboardWidgetsUseCase, SynthesisEngine | KEEP | P0_CRITICAL | unit | HIGH |
| 45 | EffectiveAmountConsistencyTest.kt | 6 | No | Real (assertEquals) | SpendingPaceCalculator, MonthlyComparisonCalculator, DayOfWeekAnalyzer | KEEP | P0_CRITICAL | unit | HIGH |
| 46 | GoldenAnalyticsDataset.kt | 0 | No | N/A — pure data fixture (data classes, constants) | GoldenScenario data objects | KEEP | P1_HIGH | fixture | HIGH |
| 47 | GoldenAnalyticsDatasetTest.kt | 8 | No | Real (assertEquals, assertTrue) | GoldenAnalyticsDataset | KEEP | P1_HIGH | scenario | HIGH |
| 48 | TimePeriodAlignmentTest.kt | 13 | No | Real (assertEquals, assertTrue) | TimePeriodUtils, AdvancedAnalyticsEngine, BudgetCalculator | KEEP | P0_CRITICAL | unit | HIGH |
| 49 | BootReceiverStressTest.kt | 4 | No | Mock verify only | BootReceiver, NotificationCaptureService | MOVE_TO_NIGHTLY | P2_MEDIUM | stress | MEDIUM |
| 50 | ServiceRestartReceiverStressTest.kt | 3 | No | Mock verify only | ServiceRestartReceiver, NotificationCaptureService | MOVE_TO_NIGHTLY | P2_MEDIUM | stress | MEDIUM |
| 51 | BackupRestoreContractTest.kt | 14 | No | Real (assertTrue, assertFalse, assertEquals) | RestoreMaintenanceMode, RestoreJournal, CostbackupBundle | KEEP | P0_CRITICAL | contract | HIGH |
| 52 | BackupRestoreMoneyIntegrityScenarioTest.kt | 3 | No | Real (assertTrue, assertNotNull, assertEquals) | AppDatabase (migrations), ScenarioSeeder | KEEP | P0_CRITICAL | scenario | HIGH |
| 53 | BankSyncScenarioTest.kt | 3 | No | Real (assertTrue, assertEquals, assertNotNull) | BankConnection, ExpenseDao (DB-backed) | KEEP | P1_HIGH | scenario | HIGH |
| 54 | CsvExportImportRoundtripTest.kt | 2 | No | Real (assertEquals, assertNotNull, assertTrue) | ExpenseDao, ScenarioSeeder | KEEP | P1_HIGH | scenario | HIGH |
| 55 | CurrencyRateStalenessScenarioTest.kt | 4 | No | Real (Truth assertThat, assertWithin) | CurrencyConverter, ExchangeRateStore | KEEP | P0_CRITICAL | scenario | HIGH |
| 56 | DatabaseIntegrityTest.kt | 3 | No | Real (assertEquals, assertTrue) | DatabaseIntegrityScanner, ExpenseDao | KEEP | P1_HIGH | scenario | HIGH |
| 57 | EmailReceiptPipelineScenarioTest.kt | 3 | No | Real (assertTrue, assertEquals, assertNotNull) | EmailReceiptSource, ScannedReceipt (DB-backed) | KEEP | P2_MEDIUM | scenario | HIGH |
| 58 | ExpenseDaoAggregateFilterTest.kt | 4 | No | Real (assertEquals, assertTrue) | ExpenseDao aggregate queries | KEEP | P0_CRITICAL | scenario | HIGH |
| 59 | GoldenScenarioSmokeTest.kt | 3 | No | Real (assertEquals, assertTrue, assertFalse) | ScenarioSeeder, ScenarioAssertions, MoneyAggregate, PrivacyGate | KEEP | P0_CRITICAL | scenario | HIGH |
| 60 | GroupGoldenScenarioTest.kt | 4 | No | Real (Truth assertThat, assertGreaterThan) | GroupLifecycleCoordinator, GroupTransactionCoordinator | KEEP | P0_CRITICAL | scenario | HIGH |
| 61 | GroupLifecycleScenarioTest.kt | 14 | No | Real (Truth assertThat) | GroupLifecycleCoordinator (all 7 methods) | KEEP | P0_CRITICAL | scenario | HIGH |
| 62 | GroupSettlementLifecycleScenarioTest.kt | 3 | No | Real (assertEquals, assertNotNull, assertTrue) | ExpenseGroup, GroupExpense, GroupSettlementEntity | KEEP | P1_HIGH | scenario | HIGH |
| 63 | HeatmapNormalizesCurrencyTest.kt | 4 | No | Real (assertEquals, assertTrue) | AnalyticsCurrencyNormalizer, CurrencyConverter | KEEP | P1_HIGH | scenario | HIGH |
| 64 | InvestmentGoldenScenarioTest.kt | 2 | No | Real (Truth assertThat) | InvestmentTracker, InvestmentDao | KEEP | P1_HIGH | scenario | HIGH |
| 65 | InvestmentPortfolioScenarioTest.kt | 4 | No | Real (assertEquals, assertNotNull, assertTrue) | Investment, InvestmentDao, InvestmentValue | KEEP | P2_MEDIUM | scenario | HIGH |
| 66 | LocationMapScenarioTest.kt | 3 | No | Real (assertEquals, assertNotNull, assertTrue) | MerchantLocation, MerchantLocationDao | KEEP | P2_MEDIUM | scenario | HIGH |
| 67 | MapMarkerConversionCurrencyTest.kt | 3 | No | Real (assertEquals, assertNotNull) | ConversionResult (domain marker logic) | MOVE_TO_NIGHTLY | P3_LOW | scenario | MEDIUM |
| 68 | MixedCurrencyCoreFinancialScenarioTest.kt | 2 | No | Real (assertEquals, assertTrue, assertFalse) | MoneyAggregate, ConversionFailure | KEEP | P1_HIGH | scenario | HIGH |
| 69 | MoneyAggregateBuilderTest.kt | 10 | No | Real (assertEquals, assertTrue) | MoneyAggregateBuilder, CurrencyConverter | KEEP | P0_CRITICAL | unit | HIGH |
| 70 | MoneyAggregateConversionScenarioTest.kt | 10 | No | Real (assertEquals, assertThrows) | MoneyAggregate, MoneyAmount | KEEP | P0_CRITICAL | scenario | HIGH |
| 71 | MulticurrencyPartialRateScenarioTest.kt | 6 | No | Real (assertEquals, assertThrows, assertTrue) | MoneyAmount, ScenarioSeeder | KEEP | P1_HIGH | scenario | HIGH |
| 72 | NotificationPipelineScenarioTest.kt | 8 | No | Real (assertEquals, assertNotNull) | GreekBankParser, ScenarioSeeder | KEEP | P1_HIGH | scenario | HIGH |
| 73 | PrivacyCloudLocationDeniedScenarioTest.kt | 3 | No | Real (Truth assertThat, coVerify) | CloudAiPrivacyGate, LocationPrivacyGate, CompositePrivacyGate | KEEP | P1_HIGH | scenario | HIGH |
| 74 | PrivacyGateContractTest.kt | 6 | No | Real (Truth assertThat) | PrivacyGate, RedactionSanitizer, PrivacyDecision | KEEP | P1_HIGH | contract | HIGH |
| 75 | PrivacyGateEnforcementScenarioTest.kt | 4 | No | Real (Truth assertThat, coVerify) | CloudAiPrivacyGate, LocationPrivacyGate, CompositePrivacyGate | DELETE | P4_NEGATIVE_VALUE | scenario | HIGH |
| 76 | ReceiptLifecycleDbContractTest.kt | 4 | No | Real (assertEquals, assertNotNull, assertTrue) | ScannedReceipt, ReceiptEvent, ReceiptExpenseLink (DB-backed) | KEEP | P1_HIGH | contract | HIGH |
| 77 | ReceiptPreOcrDedupeScenarioTest.kt | 2 | No | Real (assertEquals, assertNotNull, assertNull) | ScannedReceiptDao (imageHash dedup) | KEEP | P2_MEDIUM | scenario | HIGH |
| 78 | RecurringNoDoubleCountScenarioTest.kt | 5 | No | Real (assertEquals, assertTrue, assertNotNull) | RecurringOccurrence, ScenarioSeeder | KEEP | P1_HIGH | scenario | HIGH |
| 79 | SharedExpenseGroupScenarioTest.kt | 6 | No | Real (assertEquals, assertNotNull, assertTrue) | ExpenseGroup, GroupMember, GroupExpense | KEEP | P1_HIGH | scenario | HIGH |
| 80 | SpeechInputGatewayLifecycleTest.kt | 2 | No | Mock verify only | SpeechInputGateway (mock-based contract) | DELETE | P4_NEGATIVE_VALUE | unit | HIGH |
| 81 | TaxGoldenScenarioTest.kt | 2 | No | Real (Truth assertThat) | TaxEstimator, BusinessExpenseRepository | KEEP | P1_HIGH | scenario | HIGH |
| 82 | TransactionLifecycleCoordinatorDbContractTest.kt | 4 | No | Real (assertEquals, assertNotNull, assertTrue) | TransactionLifecycleCoordinator with real DB | KEEP | P0_CRITICAL | contract | HIGH |
| 83 | TransactionLifecycleDbContractTest.kt | 4 | No | Real (assertEquals, assertNotEquals) | ScenarioSeeder, ScenarioAssertions | KEEP | P1_HIGH | contract | HIGH |
| 84 | TransactionTargetedUpdateSideEffectsTest.kt | 4 | No | Real (assertEquals, assertNotNull, assertTrue) | TransactionLifecycleCoordinator (updateCategory) | KEEP | P1_HIGH | scenario | HIGH |
| 85 | NavigationTargetResolverTest.kt | 25+ | No | Real (assertTrue, assertEquals, assertNull) | NavigationTargetResolverImpl, TransactionFilterSerializer | KEEP | P2_MEDIUM | unit | HIGH |
| 86 | NotificationCaptureServiceFallbackTest.kt | 8 | No | Real (assertEquals, assertNull, assertNotEquals) | NotificationTextParts, NotificationServiceWorkTracker | KEEP | P1_HIGH | unit | HIGH |
| 87 | NotificationCaptureServiceStressTest.kt | 2 | Yes (class) | Very weak (assert != null) | NotificationCaptureService (Robolectric) | MOVE_TO_NIGHTLY | P2_MEDIUM | stress | HIGH |
| 88 | NotificationFilterTest.kt | 16 | No | Real (assertTrue, assertFalse) | NotificationFilter | KEEP | P1_HIGH | unit | HIGH |
| 89 | RecommendationCacheServiceTest.kt | 14 | No | Real (assertNotNull, assertEquals, coVerify) | RecommendationCacheService, RecommendationRepository | KEEP | P2_MEDIUM | unit | HIGH |
| 90 | RecommendationDeduplicatorTest.kt | 12 | No | Real (assertEquals, assertTrue) | RecommendationDeduplicator, TransactionFilterSerializer | KEEP | P2_MEDIUM | unit | HIGH |
| 91 | RecommendationDismissalHandlerTest.kt | 18 | No | Real (assertEquals, coVerify, verify) | RecommendationDismissalHandler, RecommendationStateManager | KEEP | P2_MEDIUM | unit | HIGH |
| 92 | RecommendationLifecycleManagerTest.kt | 16 | No | Real (coVerify, assert) | RecommendationLifecycleManager, RecommendationRepository | KEEP | P2_MEDIUM | unit | HIGH |
| 93 | RecommendationStateManagerTest.kt | 30+ | No | Real (assertEquals, assertNull, assertTrue) | RecommendationStateManager, RecommendationRepository | KEEP | P1_HIGH | unit | HIGH |
| 94 | TransactionFilterSerializerTest.kt | 15 | No | Real (assertEquals, assertNotNull, assertTrue) | TransactionFilterSerializer | KEEP | P2_MEDIUM | unit | HIGH |
| 95 | ReceiptMatchingWorkerTest.kt | 6 | No | Real (assertEquals, coVerify) | ReceiptMatchingWorker, ReceiptTransactionMatcher | KEEP | P1_HIGH | unit | HIGH |
| 96 | WarrantyExpirationWorkerTest.kt | 6 | No | Real (assertEquals, verify, coVerify) | WarrantyExpirationWorker, WarrantyTrackerRepository | KEEP | P1_HIGH | unit | HIGH |
| 97 | TestFixtures.kt | 0 | No | N/A — shared test helpers | Date/Currency test extensions | KEEP | P1_HIGH | fixture | HIGH |
| 98 | AppDatabaseTestFactory.kt | 0 | No | N/A — test infrastructure | AppDatabase in-memory builder factory | KEEP | P1_HIGH | fixture | HIGH |
| 99 | GoldenScenarioVerifier.kt | 0 | No | N/A — test utility | JSON golden file comparison | KEEP | P2_MEDIUM | fixture | HIGH |
| 100 | ScenarioAssertions.kt | 0 | No | N/A — test assertion helpers | DB assertion utilities for scenarios | KEEP | P1_HIGH | fixture | HIGH |

---

## Action: REWRITE

### 13. BKTreeTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/domain/util/BKTreeTest.kt`
- **@Test count:** 1
- **Problem:** Single test method ("size and empty state reflect inserts and clear") covers only basic insert/clear/size. The BKTree is a core data structure used for fuzzy-merchant matching (BK-tree Levenshtein distance), yet the only test here exercises trivial collection semantics. No tests for: distance-based search (searchByDistance), insertion order independence, large dataset behavior, or edge cases (distance > threshold, duplicates). This is a placeholder test that provides almost no coverage for the actual BK-tree algorithm.
- **Recommendation:** Either expand to at least 6-8 tests covering real BK-tree search semantics, or delete if the search functionality is already tested through consumer classes (CategorizationEngine, StringDistanceUtils). Currently provides <10% of the needed coverage for this data structure.

### 40. CategorizationPipelineIntegrationTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/integration/CategorizationPipelineIntegrationTest.kt`
- **@Test count:** 10
- **Problem:** Tests instantiate MerchantCleaner and MerchantKeyGenerator directly (not via DI/Hilt) and test them in isolation, not as an integration pipeline. The "integration" tests here are just unit tests that compose multiple utility functions together (clean → generateKey → fuzzyMatch). There are no real integration boundaries crossed: no DB, no repository, no DAO, no coordinator — just in-memory utility composition. These tests duplicate coverage already present in MerchantCleanerStressTest, MerchantKeyGeneratorTest, and AmountUtilsTest. The "integration" label is misleading. File also imports MerchantKeyGenerator as an object but declares it as `private lateinit var merchantKeyGenerator: MerchantKeyGenerator` in setup (line 18) with `merchantKeyGenerator = MerchantKeyGenerator` (line 19) — this works but is confusing vs. direct static calls.
- **Recommendation:** Either move these tests into their respective utility stress/unit test files (where the same logic is already covered) or rewrite to use real DB-backed pipelines with AppDatabaseTestFactory to make them true integration tests.

### 42. ExpenseCreationPipelineIntegrationTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/integration/ExpenseCreationPipelineIntegrationTest.kt`
- **@Test count:** 10
- **Problem:** Same pattern as #40 — labeled "integration" but tests only utility-level composition (MerchantCleaner.clean + AmountUtils.parseAmount). No DB, no coordinator, no real pipeline. Duplicates coverage from MerchantCleanerStressTest, AmountUtilsTest, and the CategorizationPipelineIntegrationTest itself. The "Expense Creation Pipeline" name implies testing the full TransactionLifecycleCoordinator.createExpense flow, but none of these tests go anywhere near it.
- **Recommendation:** Merge with #40 or move utility composition tests to their respective stress files. Replace with 3-4 true integration tests that seed data into an in-memory DB, call the real Coordinator, and verify DB state, event logging, and side-effect dispatch.

---

## Action: DELETE

### 75. PrivacyGateEnforcementScenarioTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/scenarios/PrivacyGateEnforcementScenarioTest.kt`
- **@Test count:** 4
- **Problem:** Near-identical copy of `PrivacyCloudLocationDeniedScenarioTest.kt` (#73) and `PrivacyGateContractTest.kt` (#74). All four tests here duplicate the exact same scenarios: cloud AI denied → warranty extraction blocked, GPS denied → location blocked, composite gate short-circuits, audit event logged on denial. Same mocks, same assertions, same class names. The only difference is the class name and some slightly rephrased test method names (e.g., "cloud AI denied blocks warranty extraction" appears in both #73 and #75 verbatim). File was likely a work-in-progress copy that was accidentally retained.
- **Recommendation:** Delete immediately. No unique coverage.

### 80. SpeechInputGatewayLifecycleTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/scenarios/SpeechInputGatewayLifecycleTest.kt`
- **@Test count:** 2
- **Problem:** Tests a mock object, not real behavior. Test 1 uses Java reflection to verify the SpeechInputGateway interface declares `destroy()` — this is a compile-time contract check masquerading as a test. Test 2 creates a relaxed mock, calls startListening/stopListening/destroy on it, and verifies those exact calls with MockK — proving only that a mock returns what you told it, not that the real implementation behaves correctly. This is tautological testing (testing mocks, not code) and provides zero protection against regressions.
- **Recommendation:** Delete. The presence of destroy() on the interface is enforced by the Kotlin compiler. If lifecycle testing is needed, test the real implementation (which requires Android SpeechRecognizer).

---

## Action: MOVE

### 67. MapMarkerConversionCurrencyTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/scenarios/MapMarkerConversionCurrencyTest.kt`
- **@Test count:** 3
- **Problem:** Currently in `scenarios/` but this is a pure domain test (no DB, no Android dependency beyond Robolectric runner). Tests a local `ExpenseMapMarker` data class and `createMarker()` helper that live entirely within the test file — no production classes are tested directly. The ConversionResult domain type is referenced but the test creates its own ad-hoc logic rather than exercising the real marker-generation code path. This is effectively a design-document-as-test or TDD spike artifact.
- **Recommendation:** Move to `domain/util/` or `domain/currency/` package, matching where the real marker-conversion logic lives. Alternatively, if no production code implements this logic yet, move to `MOVE_TO_NIGHTLY` as a planned-feature test.

---

## Action: MOVE_TO_NIGHTLY

### 22. TimePeriodUtilsStressTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/domain/util/TimePeriodUtilsStressTest.kt`
- **Reason:** 824 lines, ~40 tests. Heavy DST/timezone tests that manipulate `TimeZone.setDefault()` globally (side-effect on JVM). Some tests take >100ms. DST boundary tests are valuable but are environment-dependent and fragile. The core contract is already well-covered by TimePeriodUtilsTest (23 tests) and TimePeriodUtilsValidationTest (76 tests). The stress tests are redundant with ValidationTest and do not test additional edge cases.

### 25. WidgetStyleRepositoryTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/domain/widget/WidgetStyleRepositoryTest.kt`
- **Reason:** 2 tests, 73 lines total. Tests an anonymous inline implementation of WidgetStyleRepository, not the real production implementation. Tests basic toggle logic that could be verified in 2 lines of manual testing. Covers a single bug fix (force-unwrap crash). No remaining risk of regression.

### 49. BootReceiverStressTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/receiver/BootReceiverStressTest.kt`
- **Reason:** 4 tests, mock-verify only. Requires Robolectric. Tests simple Intent routing (BOOT_COMPLETED triggers service start, TIME_CHANGED does not). Crash-on-exception test is same as ServiceRestartReceiverStressTest pattern. These are integration-smoke tests that need real device behavior to be meaningful; the mock-based unit versions add little confidence.

### 50. ServiceRestartReceiverStressTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/receiver/ServiceRestartReceiverStressTest.kt`
- **Reason:** 3 tests, mock-verify only. Same pattern as #49. Tests simple Intent routing on a thin receiver class that delegates entirely to context.startForegroundService(). Crash-on-exception is a one-line try/catch in production. Mock-verify testing of framework calls provides false confidence.

### 87. NotificationCaptureServiceStressTest.kt
- **Path:** `app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceStressTest.kt`
- **Reason:** Already annotated with `@Ignore("Stress test: may hang in CI, run manually")` at class level. 2 tests, both extremely weak (one checks service != null after creation, the other calls onNotificationPosted(null)). Was explicitly designed for manual trigger only. Already self-classified as nightly/manual.

---

## Key Observations

### Strengths
- **Money/Currency domain** is exceptionally well-tested: MoneyTest, MoneyAggregateBuilderTest, MoneyAggregateConversionScenarioTest, MultiCurrencyAnalyticsTest, CurrencyRateStalenessScenarioTest, MulticurrencyPartialRateScenarioTest, HeatmapNormalizesCurrencyTest, MixedCurrencyCoreFinancialScenarioTest, MapMarkerConversionCurrencyTest, EffectiveAmountConsistencyTest, EffectiveAmountPipelineIntegrationTest — together forming a comprehensive cross-currency safety net.
- **Time/Date handling** has triple coverage: unit (TimePeriodUtilsTest), validation (TimePeriodUtilsValidationTest at 76 tests), and stress (TimePeriodUtilsStressTest). The half-open [inclusive, exclusive) contract is verified across all three.
- **Scenario tests** using AppDatabaseTestFactory + ScenarioSeeder + ScenarioAssertions provide excellent DB-backed coverage: 24 scenario tests with real in-memory Room databases.
- **Backup/restore primitives** have thorough contract testing (14 tests in BackupRestoreContractTest, 3 in BackupRestoreMoneyIntegrityScenarioTest).
- **Use-case tests** are consistently well-structured with real assertions (not just mock verification), using assertApproxEquals for floating-point comparisons.

### Weaknesses
- **Mock-only tests** in the receiver/ package (#49, #50) verify framework calls that the Android OS controls, providing no real safety.
- **Duplication**: PrivacyGateEnforcementScenarioTest (#75) is a near-verbatim copy of PrivacyCloudLocationDeniedScenarioTest (#73).
- **Fake "integration" tests**: CategorizationPipelineIntegrationTest (#40) and ExpenseCreationPipelineIntegrationTest (#42) are unit tests mislabeled as integration tests.
- **Tautological mock tests**: SpeechInputGatewayLifecycleTest (#80) tests mocks, not real implementations.
- **Placeholder tests**: BKTreeTest (#13) has only 1 test covering trivial collection behavior for what should be a fully-tested BK-tree data structure.
- **NotificationExpenseDashboardPipelineTest** (#36) has only 3 tests with an extensive test-gaps comment documenting at least 4 major uncovered scenarios. Worth expanding rather than just keeping as-is.

### Test Fixtures (files 97-100)
All four fixture files (TestFixtures.kt, AppDatabaseTestFactory.kt, GoldenScenarioVerifier.kt, ScenarioAssertions.kt) are KEPT as P1_HIGH infrastructure. They have zero @Test annotations but are essential shared testing infrastructure. ScenarioAssertions and AppDatabaseTestFactory are used by 20+ scenario tests; removing them would break the entire scenario test layer.

---

## Recommended Actions (Priority Order)

1. **DELETE** #75 PrivacyGateEnforcementScenarioTest.kt — complete duplicate of #73.
2. **DELETE** #80 SpeechInputGatewayLifecycleTest.kt — tests mocks, not real code.
3. **REWRITE** #13 BKTreeTest.kt — add real BK-tree distance-search tests or merge into integration tests.
4. **REWRITE** #40 + #42 — merge CategorizationPipelineIntegrationTest and ExpenseCreationPipelineIntegrationTest into a single file with 4-6 true DB-backed integration tests.
5. **MOVE_TO_NIGHTLY** #22, #25, #49, #50, #87 — five low-value or environment-dependent tests.
6. **MOVE** #67 MapMarkerConversionCurrencyTest.kt — from scenarios/ to appropriate domain package.
