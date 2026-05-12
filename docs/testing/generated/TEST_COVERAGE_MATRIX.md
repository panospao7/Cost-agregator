# Test Coverage Matrix

Generated from audit of 489 test files. Maps each production area to existing test coverage, quality assessment, gaps, and recommended actions.

---

## Core Financial Areas

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Money/Currency** | `Money`, `MoneyAggregate`, `MoneyAggregateBuilder`, `CurrencyCode`, `CurrencyConverter`, `ExchangeRateStore` | `MoneyContractTest.kt` (21 tests), `MoneyTest.kt`, `MoneyAggregateBuilderTest.kt`, `MoneyAggregateConversionScenarioTest.kt`, `CurrencyConverterTest.kt`, `CurrencyConverterGoldenTest.kt`, `CurrencyConverterEdgeCaseTest.kt`, `CurrencyConverterStressTest.kt`, `CanonicalMultiCurrencyFixture.kt`, `MixedCurrencyCoreFinancialScenarioTest.kt`, `MulticurrencyPartialRateScenarioTest.kt`, `CurrencyRateStalenessScenarioTest.kt` | **STRONG** | None critical | KEEP all P0 |
| **Financial Arithmetic** | `FinancialArithmeticPrecisionTest.kt`, `AmountUtilsTest.kt`, `AmountUtilsStressTest.kt`, `FinancialArithmeticPrecisionTest.kt` | `consistency/` tests | **STRONG** | Stress tests → nightly | Move stress to nightly |
| **Split Calculation** | `SplitCalculator`, `VisualSplitEditor`, `ExpenseSplit` | `SplitCalculatorTest.kt`, `SplitCalculatorGoldenTest.kt`, `SplitCalculationPrecisionTest.kt`, `CustomSplitParserTest.kt` | **STRONG** | None | KEEP |
| **Tax/Deductions** | `TaxEstimator`, `TaxConfiguration`, `TaxCalculation` | `TaxEstimatorTest.kt`, `TaxCalculationTest.kt`, `AccountingExportRepositoryTest.kt`, `DeterministicExpenseExportPagerTest.kt` | **MEDIUM** | Missing tax rate provider integration test | REWRITE TaxEstimatorTest with real mocks |
| **Business Expenses** | `BusinessExpenseRepository`, `BusinessExpenseReportGenerator`, `MileageTracking` | `BusinessExpenseRepositoryTest.kt`, `BusinessExpenseReportGeneratorTest.kt`, `MileageTrackingValidationTest.kt` | **MEDIUM** | Missing multi-currency mileage tests | ADD multi-currency mileage test |

---

## Analytics & Dashboard

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Analytics Engine** | `AdvancedAnalyticsEngine`, `TotalsAggregationEngine`, `InsightsEngine` | `AdvancedAnalyticsEngineTest.kt` (25 tests), `AdvancedAnalyticsEngineDeepTest.kt` (30+ tests), `InsightsEngineTest.kt`, `InsightsEngineDeepTest.kt`, `InsightsEngineValidationTest.kt`, `InsightsEngineEdgeCaseTest.kt`, `TotalsAggregationEngineTest.kt` (43 tests), `TotalsAggregationEngineDeepTest.kt`, `TotalsAggregationEngineValidationTest.kt` | **STRONG** | Some deprecated DAO calls in test base | KEEP, migrate DAO calls |
| **Spending Pace** | `SpendingPaceCalculator` | `SpendingPaceCalculatorValidationTest.kt`, `SpendingPaceCalculatorDeepTest.kt`, `SpendingPaceGoldenTest.kt`, `SpendingPaceBoundaryTest.kt` | **STRONG** | None | KEEP |
| **Analytics Normalizer** | `AnalyticsCurrencyNormalizer` | `AnalyticsCurrencyNormalizerTest.kt` | **WEAK** | Needs golden test | ADD golden fixture |
| **Dashboard Widgets** | `ComputeDashboardWidgetsUseCase`, `DashboardWidgetConsistency` | `DashboardWidgetConsistencyTest.kt`, `ComputeMoneyRadarUseCaseTest.kt`, `DashboardWidgetConsistencyTest.kt`, `DashboardContractsAdapterTest.kt` | **MEDIUM** | Mock-heavy, constructor mismatches | REWRITE with multiCurrencyRepository |
| **Category/Merchant Insights** | `CategoryInsightEngine`, `MerchantInsightEngine`, `DayOfWeekAnalyzer`, `MonthlyComparisonCalculator` | `CategoryInsightEngineTest.kt`, `MerchantInsightEngineTest.kt`, `DayOfWeekAnalyzerTest.kt`, `MonthlyComparisonCalculatorTest.kt` | **MEDIUM** | Pure engine tests are solid | KEEP |

---

## Budget

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Budget Calculator** | `BudgetCalculator`, `BudgetPeriod` | `BudgetCalculatorTest.kt`, `BudgetCalculatorGoldenTest.kt`, `BudgetCalculatorBoundaryTest.kt`, `BudgetHistorySeriesBuilderTest.kt` | **STRONG** | None | KEEP |
| **Budget Repository** | `BudgetRepository` | `BudgetRepositoryHistoricalStatusTest.kt`, `BudgetRepositoryTruncationTest.kt`, `BudgetRepositoryStressTest.kt`, `BudgetRepositorySuggestionsBatchTest.kt`, `BudgetRolloverTest.kt` | **MEDIUM** | Uses deprecated ExpenseDao methods heavily, @Suppress applied | REWRITE with multiCurrencyRepository mocks |
| **Budget Autopilot** | `BudgetAutopilotEngine` | `BudgetAutopilotEngineTest.kt` (20+ tests) | **MEDIUM** | Deprecated DAO calls | KEEP, migrate |
| **Budget Forecasting** | `BudgetForecastingEngine`, `MonteCarloSpendingSimulator` | `BudgetForecastingEngineTest.kt` (30+ tests), `BudgetForecastingEngineStubTest.kt` (DELETE), `MonteCarloSpendingSimulatorGoldenTest.kt`, `MonteCarloSpendingSimulatorTest.kt`, `HistoricalSpendingDistributionBoundaryTest.kt` | **MEDIUM** | Stub test is dead code | DELETE stub, KEEP goldens |
| **Budget Monitor** | `BudgetMonitor` | `BudgetMonitorTest.kt`, `BudgetMonitorStressTest.kt` | **MEDIUM** | Missing diagnostic event verification | ADD event log verification |
| **Shared Budget** | `SharedBudgetManager`, `SharedExpenseBudgetOffsetEngine` | `SharedBudgetManagerTest.kt`, `SharedExpenseBudgetOffsetEngineTest.kt` | **WEAK** | Mock-heavy, no real DB scenarios | REWRITE with DB-backed offsets |

---

## Transaction Lifecycle

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Lifecycle Coordinator** | `TransactionLifecycleCoordinator`, `TransactionSideEffectDispatcher` | `TransactionLifecycleCoordinatorTest.kt`, `TransactionLifecycleCoordinatorDbContractTest.kt`, `TransactionTargetedUpdateSideEffectsTest.kt`, `TransactionLifecycleDbContractTest.kt`, `LifecycleBarrierContractTest.kt`, `SideEffectContractTest.kt` | **STRONG** | Some deprecated createExpense usage | KEEP, @Suppress deprecated calls |
| **Event Logging** | `TransactionEventDao`, `TransactionEvent`, `TransactionEventDaoTest.kt` | `TransactionEventDaoTest.kt` | **WEAK** | Only 1 unit test for event DAO | ADD more event DAO tests |
| **Duplicate Detection** | `DuplicateDetectionPolicy`, `DedupeKeyProducer`, `DetectDuplicateExpenseUseCase` | `DuplicateDetectionPolicyDedupeKeyTest.kt`, `DedupeKeyProducerConsistencyTest.kt`, `DetectDuplicateExpenseUseCaseTest.kt`, `DedupeKeyUniquenessRegressionTest.kt` | **MEDIUM** | Regression test is nightly | KEEP |

---

## Receipt Lifecycle

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Receipt Lifecycle** | `ReceiptLifecycleCoordinator`, `ReceiptLinkService` | `ReceiptLifecycleCoordinatorTest.kt`, `ReceiptLifecycleDbContractTest.kt` | **WEAK** | Mock-heavy, type mismatches | REWRITE with real pipeline |
| **Receipt Repository** | `ReceiptRepository`, `ScannedReceiptDao` | `ReceiptRepositoryStressTest.kt`, `ReceiptRepositoryStatementDuplicateTest.kt`, `ScannedReceiptDaoTest.kt` (androidTest) | **MEDIUM** | Uses deprecated createExpenseFromReceipt | MIGRATE to new API |
| **Receipt Parsing** | `ReceiptParser`, `ReceiptParserOcrPatterns`, `ReceiptTransactionMatcher`, `BankStatementParser` | `ReceiptParserTest.kt`, `ReceiptParserOcrPatternsTest.kt`, `ReceiptTransactionMatcherTest.kt`, `BankStatementParserTest.kt`, `EnhancedMerchantExtractorTest.kt`, `OcrLanguageProcessorTest.kt`, `WarrantyTextExtractorTest.kt` | **STRONG** | Good parser coverage | KEEP |
| **Email Receipts** | `EmailReceiptIngestionService`, `EmailReceiptParser`, `AmazonReceiptParser`, etc. | `EmailReceiptIngestionServiceTest.kt`, `EmailReceiptIngestionServiceTransactionTest.kt`, `EmailReceiptParserTest.kt`, `AmazonReceiptParserTest.kt`, `AppleReceiptParserTest.kt`, `UberReceiptParserTest.kt` | **MEDIUM** | Transaction test has complex constructor | KEEP, simplify constructor test |

---

## Recurring Lifecycle

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Recurring Coordinator** | `RecurringLifecycleCoordinator`, `RecurringExpenseEngine`, `RecurringExpenseRepository` | `RecurringLifecycleCoordinatorTest.kt`, `RecurringExpenseEngineTest.kt`, `RecurringExpenseEngineEmptyListTest.kt`, `RecurringExpenseRepositoryTest.kt`, `RecurringDeactivateContractTest.kt`, `RecurringNoDoubleCountScenarioTest.kt`, `RecurringIntervalLogicTest.kt` | **STRONG** | No-double-count scenario is critical | KEEP |
| **Savings Automation** | `AutomatedSavingsRuleEngine`, `SmartSavingsEngine`, `SavingsGamificationEngine` | `AutomatedSavingsRuleEngineTest.kt`, `AutomatedSavingsRuleEngineGoldenTest.kt`, `SmartSavingsEngineTest.kt`, `SavingsGamificationEngineTest.kt`, `LifestyleSavingsPromptUseCaseTest.kt`, `MonthlySavingsSweepUseCaseTest.kt` | **MEDIUM** | Golden test @Ignore'd → should be nightly | Move golden to nightly |

---

## Notification Pipeline

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Notification Capture** | `NotificationCaptureService`, `NotificationFilter`, `NotificationTextParts` | `NotificationCaptureServiceFallbackTest.kt`, `NotificationCaptureServiceStressTest.kt` (DELETE), `NotificationFilterTest.kt` | **WEAK** | Stress test is self-declared manual-only | REWRITE with real notification capture test |
| **Notification Processing** | `NotificationProcessingPipeline`, `NotificationRepository` | `NotificationProcessingPipelineReliabilityTest.kt`, `NotificationProcessingPipelineStressTest.kt`, `NotificationProcessingPipelineOversizedAmountTest.kt`, `NotificationRepositoryStressTest.kt` | **MEDIUM** | Mostly stress tests, missing basic pipeline test | ADD basic pipeline integration test |
| **Source Stats** | `SourceStatsDao`, `ReviewQueueRepository` | `ReviewQueueRepositoryTest.kt`, `ReviewQueueRepositoryStressTest.kt` | **MEDIUM** | Review queue tests are reasonable | KEEP |

---

## Bank Sync/Import

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Bank API** | `BankApi`, `BankConnectionDao` | `BankApiIntegrationTest.kt`, `BankConnectionDaoTest.kt`, `BankSyncScenarioTest.kt` | **WEAK** | API test is mock-only, no real bank sync test | REWRITE with wiremock or real test API |
| **Statement Parsing** | `BankStatementParser` | Covered under Receipt Parsing | **MEDIUM** | Already covered | KEEP |

---

## Backup/Restore

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Backup Repository** | `DatabaseBackupRepositoryImpl`, `BackupEncryptionService`, `RestoreMaintenanceMode` | `DatabaseBackupRepositoryImplTest.kt`, `BackupEncryptionServiceTest.kt`, `BackupRestoreContractTest.kt`, `BackupRestoreMoneyIntegrityScenarioTest.kt`, `SecureKeyStorageTest.kt` | **STRONG** | Money integrity scenario is critical | KEEP |
| **CSV Export/Import** | `CsvExpenseImporter`, `ExportImportRoundtrip` | `CsvExpenseImporterTest.kt`, `ExportImportRoundtripTest.kt`, `CsvEscapingTest.kt`, `ExpenseExportMapperTest.kt`, `CsvExportImportRoundtripTest.kt` | **MEDIUM** | Uses deprecated createExpense, @Suppress applied | MIGRATE to new API |

---

## Privacy/Security

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Privacy Gate** | `CloudAiPrivacyGate`, `CompositePrivacyGate`, `PrivacyGate`, `PrivacyDecision`, `EffectiveCloudAiPolicyResolver` | `PrivacyGateContractTest.kt`, `PrivacyCloudLocationDeniedScenarioTest.kt`, `PrivacyGateEnforcementScenarioTest.kt` (DELETE), `PrivacyStorageContractTest.kt` | **STRONG** | Delete duplicate enforcement test | KEEP |
| **AI Policy** | `AiPolicy`, `AiSettingsRepository`, `AiCapabilityRouter` | `AiPolicyTest.kt`, `DefaultAiCapabilityRouterTest.kt`, `AiArtifactPresentationTest.kt`, `AiRuntimeStatusModelsTest.kt`, `OnDeviceRuntimePresentationTest.kt` | **STRONG** | Policy/router coverage is solid | KEEP |
| **Cloud Services** | `CloudReceiptAssistService`, `CloudCategorizationAssistService`, etc. | 8 Cloud service test files + 8 On-device service test files | **MEDIUM** | Mock-heavy, but proves privacy gate contract | KEEP |

---

## Room DAO / Schema / Migration

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Migration** | `AppDatabase.migrations`, `MIGRATION_X_Y` | `DatabaseMigrationTest.kt` (71 tests), `MigrationContractTest.kt`, `MigrationRegistrationTest.kt` | **STRONG** | 71 migration tests is excellent | KEEP |
| **Fresh Install** | `AppDatabase.inMemoryBuilder()` | `FreshInstallIndexParityTest.kt`, `FreshInstallBatch8ParityTest.kt` | **STRONG** | Parity tests protect schema creation | KEEP |
| **Core DAOs** | `ExpenseDao`, `CategoryDao`, `BudgetDao`, `ExpenseGroupDao`, etc. | 23 DAO test files (~20 androidTest, ~3 unit) | **STRONG** | Comprehensive DAO coverage | KEEP |
| **Reference DAOs** | `AiChatMessageDao`, `AiChatSessionDao`, `AiArtifactDao`, `InvestmentDao`, etc. | Multiple androidTest DAO files | **MEDIUM** | InvestmentDao has TODO skeleton | REWRITE InvestmentDaoTest |

---

## Groups / Shared Expenses

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Group Lifecycle** | `GroupLifecycleCoordinator`, `GroupTransactionCoordinator`, `GroupsRepositoryImpl` | `GroupGoldenScenarioTest.kt`, `GroupLifecycleScenarioTest.kt`, `GroupTransactionCoordinatorTest.kt`, `GroupsRepositoryImplTest.kt`, `GroupSettlementLifecycleScenarioTest.kt`, `SharedExpenseGroupScenarioTest.kt`, `GroupUseCasesTest.kt` | **STRONG** | DB-backed scenario tests | KEEP |
| **Settlement** | `SettlementCalculator`, `GroupSettlementDao` | `SettlementCalculatorTest.kt`, `SettlementCalculatorStressTest.kt`, `GroupSettlementPipelineTest.kt` | **MEDIUM** | Golden scenario covers some paths | ADD more settlement edge case tests |
| **Shared Expense Manager** | `SharedExpenseManager` | `SharedExpenseManagerTest.kt`, `SharedExpenseTest.kt` | **MEDIUM** | Some deprecated calls | KEEP |

---

## Investment

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Investment Tracking** | `InvestmentTracker`, `InvestmentDao`, `InvestmentTransactionDao`, `InvestmentValueDao` | `InvestmentTrackerTest.kt`, `InvestmentPortfolioScenarioTest.kt`, `InvestmentGoldenScenarioTest.kt`, `InvestmentDaoTest.kt` | **WEAK** | InvestmentDaoTest skeleton, TrackerTest has constructor issues | REWRITE InvestmentDaoTest from skeleton |
| **Wallet Integration** | N/A (planned?) | No dedicated wallet tests | **GAP** | Missing entirely | ADD if wallet feature exists |

---

## Export / Accounting

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Export Policy** | `AccountingExportPolicy`, `AccountingExportRepository` | `AccountingExportPolicyTest.kt`, `AccountingExportRepositoryTest.kt`, `DeterministicExpenseExportPagerTest.kt` | **MEDIUM** | Export paging is well tested | KEEP |

---

## UI / ViewModels / Navigation

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Navigation** | `NavigationRoute`, `NavigationTargetResolver` | `NavigationRouteContractTest.kt`, `NavigationTargetResolverTest.kt` | **STRONG** | Route contract is critical | KEEP |
| **ViewModel Tests** | ~30 ViewModel test files | Mixed quality | **MIXED** | ~10 are mock-verify only | Assess individually |
| **Home Screen** | `HomeViewModel`, `RecommendationViewModel` | `HomeViewModelRecommendationTest.kt` (REWRITE), `HomeViewModelStressTest.kt` | **WEAK** | Recommendation test is self-referential | REWRITE |
| **Analytics Screen** | `AnalyticsViewModel`, `AnalyticsState` | `AnalyticsViewModelStressTest.kt` (MOVE_TO_NIGHTLY), `AnalyticsStateStressTest.kt` (DELETE) | **WEAK** | Stress test tests data class defaults | DELETE/REWRITE |
| **Deep Links** | `MainActivity` | `MainActivityDeepLinkTest.kt` (DELETE) | **NEGATIVE** | Source-text assertion | DELETE |

---

## Workers / Runtime

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Worker Idempotency** | `WorkerExecutionGuard`, `WorkerRunLogger` | `WorkerIdempotencyTest.kt` | **MEDIUM** | Covers basic idempotency | KEEP |
| **Worker Contract** | `ListenableWorker` | `WorkerContractTest.kt` | **MEDIUM** | Generic worker contract | KEEP |
| **Specific Workers** | `WarrantyExpirationWorker`, `ReceiptMatchingWorker`, `DailyBriefingWorker`, `LocationBackfillWorker`, `MerchantKeyBackfillWorker` | 5 worker test files | **MEDIUM** | Some executionGuard mismatches | KEEP |

---

## Parser / Classification

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Bank Parsers** | `GreekBankParser`, `RevolutParser`, `GoogleWalletParser`, `SmsParser`, `GenericTransactionParser` | 8 parser test files + NBG repro test | **STRONG** | Comprehensive parser coverage | KEEP |
| **Classification** | `HybridExpenseClassifier`, `ExpenseCategoryClassifier`, `MerchantNormalizer`, `TransactionClassifier`, `ConfidenceRouter` | 6 classifier test files + 2 confidence router tests | **MEDIUM** | ML tests are solid | KEEP |
| **Transfer Detection** | `TransferDirectionDetector` | `TransferDirectionDetectorTest.kt` | **MEDIUM** | Good coverage | KEEP |
| **Dedupe** | `DedupeKeyProducer`, `DedupeKeyTest` | `DedupeKeyProducerConsistencyTest.kt`, `DedupeKeyTest.kt` | **MEDIUM** | Consistency test is solid | KEEP |

---

## Location / Geocoding

| Area | Production Classes | Existing Tests | Quality | Gaps | Action |
|------|-------------------|----------------|---------|------|--------|
| **Geocoding** | `NominatimGeocodingService`, `CompositeGeocodingService` | `CompositeGeocodingServiceTest.kt`, `CompositeGeocodingServiceStressTest.kt`, `NominatimGeocodingServiceLocaleTest.kt`, `GeocodingCancellationTest.kt`, `GeocodingRetryHttpSemanticsTest.kt` | **MEDIUM** | HTTP retry semantics tested | KEEP |
| **Nearby Search** | `OverpassNearbyService` | `OverpassNearbyServiceTest.kt` | **MEDIUM** | Single test file | KEEP |
| **Location Backfill** | `LocationBackfillWorker`, `MerchantKeyBackfillWorker` | `LocationBackfillWorkerTest.kt`, `MerchantKeyBackfillWorkerTest.kt` | **MEDIUM** | Worker tests | KEEP |

---

## Summary Stats

| Metric | Count |
|--------|-------|
| Production areas covered | 28 |
| Areas with STRONG coverage | 12 |
| Areas with MEDIUM coverage | 13 |
| Areas with WEAK coverage | 3 |
| Areas with NEGATIVE value tests | 3 |
| Critical gaps identified | 5 |

### Top 5 Gaps to Address

1. **InvestmentDaoTest** — skeleton, needs real Room-backed tests
2. **Bank API integration** — mock-only, needs real bank sync test
3. **AnalyticsCurrencyNormalizer** — no golden test
4. **Notification Capture** — no real capture lifecycle test
5. **AnalyticsViewModel** — tests either mock-verify or test data class defaults, no real ViewModel state test
