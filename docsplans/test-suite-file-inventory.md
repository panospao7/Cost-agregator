# Test Suite File Inventory

## Unit Tests (app/src/test/)

### . (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | AnalyticsEngineTestBase.kt | 1 | No |
| 2 | TestUtils.kt | 0 | No |

### consistency/ (16 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | consistency/ConcurrencyStateRaceTest.kt | 4 | Yes |
| 2 | consistency/ConstantsConsistencyTest.kt | 2 | No |
| 3 | consistency/CrossParserConsistencyStressTest.kt | 4 | Yes |
| 4 | consistency/CrossParserConsistencyTest.kt | 8 | No |
| 5 | consistency/CurrencyNormalizerConsistencyTest.kt | 10 | No |
| 6 | consistency/DedupeKeyProducerConsistencyTest.kt | 12 | No |
| 7 | consistency/DuplicateLogicConsistencyIntegrationTest.kt | 23 | No |
| 8 | consistency/EmptyZeroNullResilienceTest.kt | 2 | No |
| 9 | consistency/FinancialArithmeticPrecisionTest.kt | 4 | No |
| 10 | consistency/HaversineConsistencyTest.kt | 5 | No |
| 11 | consistency/MerchantKeyConsistencyTest.kt | 3 | No |
| 12 | consistency/MerchantKeyCrossConsumerConsistencyTest.kt | 10 | No |
| 13 | consistency/SharedUtilityConsistencyStressTest.kt | 6 | Yes |
| 14 | consistency/SharedUtilityConsistencyTest.kt | 15 | No |
| 15 | consistency/TemporalConsistencyTest.kt | 4 | No |
| 16 | consistency/TimePeriodAnalyticsAlignmentTest.kt | 6 | No |

### data/ai/provider/ (20 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/ai/provider/CloudCategorizationAssistServiceTest.kt | 10 | No |
| 2 | data/ai/provider/CloudDashboardBriefingServiceTest.kt | 5 | No |
| 3 | data/ai/provider/CloudDedupeJudgeServiceTest.kt | 5 | No |
| 4 | data/ai/provider/CloudQueryInterpretationServiceTest.kt | 4 | No |
| 5 | data/ai/provider/CloudReceiptAssistServiceTest.kt | 6 | No |
| 6 | data/ai/provider/CloudReceiptItemCategorizationServiceTest.kt | 3 | No |
| 7 | data/ai/provider/CloudReviewExplanationServiceTest.kt | 1 | No |
| 8 | data/ai/provider/CloudWarrantyExtractionServiceTest.kt | 4 | No |
| 9 | data/ai/provider/DashboardBriefingResponseParserTest.kt | 3 | No |
| 10 | data/ai/provider/HybridReceiptItemCategorizationServiceTest.kt | 1 | No |
| 11 | data/ai/provider/HybridServiceDelegationTest.kt | 7 | No |
| 12 | data/ai/provider/OnDeviceCategorizationAssistServiceTest.kt | 21 | No |
| 13 | data/ai/provider/OnDeviceDashboardBriefingServiceTest.kt | 5 | No |
| 14 | data/ai/provider/OnDeviceDedupeJudgeServiceTest.kt | 8 | No |
| 15 | data/ai/provider/OnDeviceNotificationParserTest.kt | 3 | No |
| 16 | data/ai/provider/OnDeviceQueryInterpretationServiceTest.kt | 7 | No |
| 17 | data/ai/provider/OnDeviceReceiptAssistServiceTest.kt | 8 | No |
| 18 | data/ai/provider/OnDeviceReceiptItemCategorizationServiceTest.kt | 1 | No |
| 19 | data/ai/provider/OnDeviceReviewExplanationServiceTest.kt | 6 | No |
| 20 | data/ai/provider/SmartReceiptAssistServiceTest.kt | 8 | No |

### data/ai/provider/internal/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/ai/provider/internal/CloudJsonParserTest.kt | 12 | No |
| 2 | data/ai/provider/internal/CloudRetryPolicyTest.kt | 7 | No |

### data/ai/worker/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/ai/worker/DailyBriefingWorkerTest.kt | 6 | No |

### data/currency/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/currency/ExchangeRateStoreAdapterTest.kt | 4 | No |

### data/database/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/database/GroupTransactionCoordinatorTest.kt | 23 | No |
| 2 | data/database/TransactionRollbackTest.kt | 15 | Yes |

### data/database/converter/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/database/converter/ConvertersTest.kt | 4 | No |

### data/database/dao/ (3 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/database/dao/BankConnectionDaoTest.kt | 12 | No |
| 2 | data/database/dao/ExpenseDaoBoundaryConsistencyTest.kt | 15 | No |
| 3 | data/database/dao/RecommendationDaoTest.kt | 15 | No |

### data/database/entity/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/database/entity/CategoryTest.kt | 7 | No |
| 2 | data/database/entity/DedupeKeyTest.kt | 9 | No |
| 3 | data/database/entity/ExpenseEntityStressTest.kt | 42 | Yes |
| 4 | data/database/entity/MileageTrackingValidationTest.kt | 1 | No |

### data/database/model/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/database/model/ExpenseWithCategoryFormattedAmountTest.kt | 11 | No |
| 2 | data/database/model/ExpenseWithCategoryFormattedTimeTest.kt | 5 | No |

### data/email/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/email/EmailReceiptIngestionServiceTest.kt | 12 | No |
| 2 | data/email/EmailReceiptIngestionServiceTransactionTest.kt | 1 | No |

### data/email/provider/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/email/provider/AmazonReceiptParserTest.kt | 1 | No |
| 2 | data/email/provider/AppleReceiptParserTest.kt | 2 | No |
| 3 | data/email/provider/EmailReceiptParserTest.kt | 3 | No |
| 4 | data/email/provider/UberReceiptParserTest.kt | 6 | No |

### data/location/ (9 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/location/AndroidForegroundLocationProviderTest.kt | 1 | No |
| 2 | data/location/CompositeGeocodingServiceStressTest.kt | 8 | Yes |
| 3 | data/location/CompositeGeocodingServiceTest.kt | 1 | No |
| 4 | data/location/GeocodingCancellationTest.kt | 2 | No |
| 5 | data/location/GeocodingRetryHttpSemanticsTest.kt | 5 | No |
| 6 | data/location/LocationBackfillWorkerTest.kt | 5 | No |
| 7 | data/location/MerchantKeyBackfillWorkerTest.kt | 5 | No |
| 8 | data/location/NominatimGeocodingServiceLocaleTest.kt | 1 | No |
| 9 | data/location/OverpassNearbyServiceTest.kt | 2 | No |

### data/location/internal/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/location/internal/LogSanitizerTest.kt | 3 | No |

### data/repository/ (34 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/repository/AccountingExportRepositoryTest.kt | 14 | No |
| 2 | data/repository/AiArtifactRepositoryImplTest.kt | 9 | No |
| 3 | data/repository/AiChatRepositoryImplTest.kt | 8 | No |
| 4 | data/repository/AutomatedSavingsRuleStateRepositoryTest.kt | 6 | No |
| 5 | data/repository/BudgetRepositoryHistoricalStatusTest.kt | 2 | No |
| 6 | data/repository/BudgetRepositoryStressTest.kt | 18 | Yes |
| 7 | data/repository/BudgetRepositorySuggestionsBatchTest.kt | 1 | No |
| 8 | data/repository/BudgetRepositoryTruncationTest.kt | 9 | No |
| 9 | data/repository/BudgetRolloverTest.kt | 12 | No |
| 10 | data/repository/BusinessExpenseRepositoryTest.kt | 2 | No |
| 11 | data/repository/CategoryRepositoryStressTest.kt | 4 | Yes |
| 12 | data/repository/CategoryRepositoryTest.kt | 1 | No |
| 13 | data/repository/DashboardContractsAdapterTest.kt | 1 | No |
| 14 | data/repository/DatabaseBackupRepositoryImplTest.kt | 14 | No |
| 15 | data/repository/DeterministicExpenseExportPagerTest.kt | 2 | No |
| 16 | data/repository/ExpenseRepositoryStressTest.kt | 13 | Yes |
| 17 | data/repository/ExpenseRepositoryTest.kt | 9 | No |
| 18 | data/repository/ExpenseRepositoryTruncationTest.kt | 9 | No |
| 19 | data/repository/FinancialWeatherRepositoryTest.kt | 9 | No |
| 20 | data/repository/GroupsRepositoryImplTest.kt | 6 | No |
| 21 | data/repository/MerchantRulesRepositoryTest.kt | 4 | No |
| 22 | data/repository/MultiCurrencyRepositoryTest.kt | 27 | No |
| 23 | data/repository/NotificationProcessingPipelineOversizedAmountTest.kt | 3 | No |
| 24 | data/repository/NotificationProcessingPipelineReliabilityTest.kt | 11 | No |
| 25 | data/repository/NotificationProcessingPipelineStressTest.kt | 28 | Yes |
| 26 | data/repository/NotificationRepositoryStressTest.kt | 21 | Yes |
| 27 | data/repository/ReceiptRepositoryStatementDuplicateTest.kt | 1 | No |
| 28 | data/repository/ReceiptRepositoryStressTest.kt | 12 | Yes |
| 29 | data/repository/RecommendationRepositoryTest.kt | 17 | No |
| 30 | data/repository/RecurringExpenseRepositoryTest.kt | 3 | No |
| 31 | data/repository/ReviewQueueRepositoryStressTest.kt | 8 | Yes |
| 32 | data/repository/ReviewQueueRepositoryTest.kt | 11 | No |
| 33 | data/repository/SavingsContributionHistoryRepositoryTest.kt | 3 | No |
| 34 | data/repository/WarrantyTrackerRepositoryTest.kt | 11 | No |

### data/security/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/security/SecureKeyStorageTest.kt | 17 | No |

### data/service/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/service/AndroidNotificationServiceTest.kt | 2 | No |

### data/speech/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/speech/AndroidSpeechInputGatewayTest.kt | 3 | No |

### domain/ai/model/ (6 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/ai/model/AiArtifactPresentationTest.kt | 3 | No |
| 2 | domain/ai/model/AiRuntimeStatusModelsTest.kt | 2 | No |
| 3 | domain/ai/model/CategorizationAssistInputTest.kt | 1 | No |
| 4 | domain/ai/model/NotificationParsingModelsTest.kt | 2 | No |
| 5 | domain/ai/model/OnDeviceRuntimePresentationTest.kt | 4 | No |
| 6 | domain/ai/model/WarrantyExtractionModelsTest.kt | 2 | No |

### domain/ai/policy/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/ai/policy/AiPolicyTest.kt | 15 | No |
| 2 | domain/ai/policy/DefaultAiCapabilityRouterTest.kt | 17 | No |

### domain/ai/usecase/ (20 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/ai/usecase/CategorizationAssistInputBuilderTest.kt | 6 | No |
| 2 | domain/ai/usecase/CategorizeReceiptItemsUseCaseTest.kt | 1 | No |
| 3 | domain/ai/usecase/DedupeJudgeInputBuilderTest.kt | 8 | No |
| 4 | domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt | 6 | No |
| 5 | domain/ai/usecase/ExecuteFinancialQueryUseCaseTest.kt | 9 | No |
| 6 | domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt | 12 | No |
| 7 | domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt | 3 | No |
| 8 | domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt | 10 | No |
| 9 | domain/ai/usecase/GenerateTransactionInsightUseCaseTest.kt | 1 | No |
| 10 | domain/ai/usecase/GetAiRuntimeStatusUseCaseTest.kt | 4 | No |
| 11 | domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt | 7 | No |
| 12 | domain/ai/usecase/JudgePendingReviewDuplicateUseCaseTest.kt | 7 | No |
| 13 | domain/ai/usecase/MapFinancialQueryToNavigationUseCaseTest.kt | 2 | No |
| 14 | domain/ai/usecase/PrioritizeReviewItemsUseCaseTest.kt | 4 | No |
| 15 | domain/ai/usecase/ReceiptAssistInputBuilderTest.kt | 3 | Yes |
| 16 | domain/ai/usecase/ReceiptItemCategorizationInputBuilderTest.kt | 1 | No |
| 17 | domain/ai/usecase/ReviewExplanationInputBuilderTest.kt | 2 | No |
| 18 | domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt | 10 | No |
| 19 | domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt | 9 | Yes |
| 20 | domain/ai/usecase/SyncProactiveBriefingWorkUseCaseTest.kt | 3 | No |

### domain/ai/util/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/ai/util/AiArtifactSourceHashTest.kt | 6 | No |

### domain/alerts/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/alerts/AnomalyAlertOrchestratorTest.kt | 10 | No |

### domain/analytics/ (27 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/analytics/AdvancedAnalyticsDashboardTest.kt | 5 | No |
| 2 | domain/analytics/AdvancedAnalyticsEngineDeepTest.kt | 10 | No |
| 3 | domain/analytics/AdvancedAnalyticsEngineStressTest.kt | 33 | No |
| 4 | domain/analytics/AdvancedAnalyticsEngineTest.kt | 3 | No |
| 5 | domain/analytics/AnalyticsStressTest.kt | 1 | No |
| 6 | domain/analytics/AnalyticsWindowingSupportTest.kt | 5 | No |
| 7 | domain/analytics/AnomalyDetectorTest.kt | 4 | No |
| 8 | domain/analytics/CategoryInsightEngineTest.kt | 11 | No |
| 9 | domain/analytics/DayOfWeekAnalyzerTest.kt | 4 | No |
| 10 | domain/analytics/InsightsEngineDeepTest.kt | 7 | No |
| 11 | domain/analytics/InsightsEngineEdgeCaseTest.kt | 6 | No |
| 12 | domain/analytics/InsightsEngineStressTest.kt | 27 | No |
| 13 | domain/analytics/InsightsEngineTest.kt | 4 | No |
| 14 | domain/analytics/InsightsEngineValidationTest.kt | 13 | No |
| 15 | domain/analytics/MerchantInsightEngineTest.kt | 4 | No |
| 16 | domain/analytics/MonthlyComparisonCalculatorTest.kt | 3 | No |
| 17 | domain/analytics/RecurringIntervalLogicTest.kt | 1 | No |
| 18 | domain/analytics/SpendingPaceBoundaryTest.kt | 4 | No |
| 19 | domain/analytics/SpendingPaceCalculatorDeepTest.kt | 6 | No |
| 20 | domain/analytics/SpendingPaceCalculatorValidationTest.kt | 14 | No |
| 21 | domain/analytics/SpendingPaceGoldenTest.kt | 2 | No |
| 22 | domain/analytics/SpendingPersonalityClassifierTest.kt | 14 | No |
| 23 | domain/analytics/SpendingThresholdCalculatorTest.kt | 11 | No |
| 24 | domain/analytics/TotalsAggregationEngineDeepTest.kt | 5 | No |
| 25 | domain/analytics/TotalsAggregationEngineTest.kt | 43 | No |
| 26 | domain/analytics/TotalsAggregationEngineValidationTest.kt | 18 | No |
| 27 | domain/analytics/TransferDirectionAnalyticsTest.kt | 5 | No |

### domain/bank/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/bank/BankApiIntegrationTest.kt | 4 | No |

### domain/budget/ (13 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/budget/BudgetAutopilotEngineTest.kt | 13 | No |
| 2 | domain/budget/BudgetCalculatorBoundaryTest.kt | 16 | No |
| 3 | domain/budget/BudgetCalculatorGoldenTest.kt | 3 | No |
| 4 | domain/budget/BudgetCalculatorStressTest.kt | 25 | No |
| 5 | domain/budget/BudgetCalculatorTest.kt | 16 | No |
| 6 | domain/budget/BudgetForecastingEngineStubTest.kt | 2 | No |
| 7 | domain/budget/BudgetForecastingEngineTest.kt | 22 | No |
| 8 | domain/budget/BudgetHistorySeriesBuilderTest.kt | 2 | No |
| 9 | domain/budget/BudgetMonitorStressTest.kt | 11 | No |
| 10 | domain/budget/BudgetMonitorTest.kt | 4 | No |
| 11 | domain/budget/BudgetRecommendationEngineTest.kt | 5 | No |
| 12 | domain/budget/BudgetTrendBoundaryTest.kt | 1 | No |
| 13 | domain/budget/SharedBudgetManagerTest.kt | 14 | No |

### domain/business/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/business/BusinessExpenseReportGeneratorTest.kt | 38 | No |

### domain/carbon/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/carbon/CarbonFootprintCalculatorTest.kt | 23 | No |

### domain/cashflow/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/cashflow/CashFlowCalculatorTest.kt | 12 | No |

### domain/categorization/ (8 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/categorization/CategorizationComponentsTest.kt | 40 | No |
| 2 | domain/categorization/CategorizationEngineDebugTest.kt | 2 | No |
| 3 | domain/categorization/CategorizationEngineStressTest.kt | 33 | No |
| 4 | domain/categorization/CategorizationEngineTest.kt | 5 | No |
| 5 | domain/categorization/CategoryKeywordsTest.kt | 7 | No |
| 6 | domain/categorization/ContextualInferenceEngineStressTest.kt | 36 | No |
| 7 | domain/categorization/MerchantCanonicalizerStressTest.kt | 8 | No |
| 8 | domain/categorization/SemanticKeywordMatcherStressTest.kt | 6 | No |

### domain/challenge/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/challenge/SpendingChallengeManagerTest.kt | 4 | No |

### domain/currency/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/currency/CurrencyConversionTest.kt | 28 | No |
| 2 | domain/currency/CurrencyConverterEdgeCaseTest.kt | 7 | No |
| 3 | domain/currency/CurrencyConverterGoldenTest.kt | 2 | No |
| 4 | domain/currency/CurrencyConverterStressTest.kt | 2 | No |

### domain/debug/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/debug/ServiceDiagnosticsTest.kt | 11 | No |

### domain/engine/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/engine/DashboardFollowThroughEngineTest.kt | 20 | No |

### domain/export/ (3 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/export/AccountingExportPolicyTest.kt | 3 | No |
| 2 | domain/export/CsvEscapingTest.kt | 25 | Yes |
| 3 | domain/export/ExpenseExportMapperTest.kt | 2 | No |

### domain/forecasting/ (6 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/forecasting/FinancialStressForecastEngineTest.kt | 15 | No |
| 2 | domain/forecasting/ForecastInputAssemblerTest.kt | 10 | No |
| 3 | domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt | 8 | No |
| 4 | domain/forecasting/MergedRecurringPatternsProviderTest.kt | 5 | No |
| 5 | domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt | 1 | No |
| 6 | domain/forecasting/MonteCarloSpendingSimulatorTest.kt | 1 | No |

### domain/groups/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/groups/SettlementCalculatorStressTest.kt | 3 | No |
| 2 | domain/groups/SettlementCalculatorTest.kt | 6 | No |
| 3 | domain/groups/SharedExpenseBudgetOffsetEngineTest.kt | 8 | No |
| 4 | domain/groups/SharedExpenseManagerTest.kt | 14 | No |

### domain/groups/usecase/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/groups/usecase/GroupUseCasesTest.kt | 12 | No |

### domain/health/ (6 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/health/FinancialHealthCalculatorBoundaryTest.kt | 11 | No |
| 2 | domain/health/FinancialHealthCalculatorBudgetNormalizationTest.kt | 3 | No |
| 3 | domain/health/FinancialHealthCalculatorTransactionTypeTest.kt | 9 | No |
| 4 | domain/health/FinancialHealthScoreV2Test.kt | 12 | No |
| 5 | domain/health/HealthScoreEdgeCaseTest.kt | 5 | No |
| 6 | domain/health/HealthScoreGoldenTest.kt | 2 | No |

### domain/income/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/income/RecurringIncomeTrackerTest.kt | 6 | No |

### domain/intelligence/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/intelligence/ConfidenceRouterEdgeCaseTest.kt | 7 | No |
| 2 | domain/intelligence/ConfidenceRouterTest.kt | 9 | No |
| 3 | domain/intelligence/DuplicateDetectionPolicyDedupeKeyTest.kt | 6 | No |
| 4 | domain/intelligence/TransactionClassifierTest.kt | 10 | No |

### domain/intelligence/ml/ (6 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/intelligence/ml/ExpenseCategoryClassifierTest.kt | 11 | No |
| 2 | domain/intelligence/ml/FeatureExtractorTest.kt | 1 | No |
| 3 | domain/intelligence/ml/HybridExpenseClassifierStressTest.kt | 28 | No |
| 4 | domain/intelligence/ml/HybridExpenseClassifierTest.kt | 12 | No |
| 5 | domain/intelligence/ml/MerchantNormalizerStressTest.kt | 12 | No |
| 6 | domain/intelligence/ml/MerchantNormalizerTest.kt | 3 | No |

### domain/investment/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/investment/InvestmentTrackerTest.kt | 9 | No |

### domain/location/ (8 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/location/AreaSpendingEngineStressTest.kt | 4 | No |
| 2 | domain/location/AreaSpendingEngineTest.kt | 1 | No |
| 3 | domain/location/LocationInsightsEngineStressTest.kt | 6 | No |
| 4 | domain/location/LocationResolverStressTest.kt | 12 | No |
| 5 | domain/location/LocationResolverTest.kt | 2 | No |
| 6 | domain/location/SpendingHeatmapEngineStressTest.kt | 32 | No |
| 7 | domain/location/TravelDetectionEngineStressTest.kt | 7 | No |
| 8 | domain/location/TravelDetectionEngineTest.kt | 1 | No |

### domain/logic/ (11 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/logic/CustomSplitParserTest.kt | 12 | Yes |
| 2 | domain/logic/RecurrenceCalculatorTest.kt | 5 | No |
| 3 | domain/logic/RecurringExpenseEngineEmptyListTest.kt | 4 | No |
| 4 | domain/logic/RecurringExpenseEngineStressTest.kt | 28 | No |
| 5 | domain/logic/RecurringExpenseEngineTest.kt | 14 | No |
| 6 | domain/logic/SplitCalculatorGoldenTest.kt | 4 | No |
| 7 | domain/logic/SplitCalculatorStressTest.kt | 3 | No |
| 8 | domain/logic/SplitCalculatorTest.kt | 12 | No |
| 9 | domain/logic/SynthesisEngineGoldenTest.kt | 3 | No |
| 10 | domain/logic/SynthesisEngineStressTest.kt | 56 | No |
| 11 | domain/logic/SynthesisEngineTest.kt | 9 | No |

### domain/model/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/model/CategoryBreakdownTest.kt | 19 | No |
| 2 | domain/model/FinancialForecastModelTest.kt | 2 | No |
| 3 | domain/model/PeriodTotalTest.kt | 14 | No |
| 4 | domain/model/RecurringPatternModelTest.kt | 2 | No |

### domain/model/dashboard/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/model/dashboard/DashboardExpenseMapperTest.kt | 4 | No |

### domain/naturallanguage/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/naturallanguage/NaturalLanguageSearchEngineVoiceInputTest.kt | 3 | No |

### domain/parser/ (13 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/parser/AppParserRegistryRoutingTest.kt | 6 | No |
| 2 | domain/parser/AppParserRegistryStressTest.kt | 26 | No |
| 3 | domain/parser/AppParserRegistryTest.kt | 8 | No |
| 4 | domain/parser/GenericTransactionParserStressTest.kt | 11 | No |
| 5 | domain/parser/GenericTransactionParserTest.kt | 21 | No |
| 6 | domain/parser/GoogleWalletParserTest.kt | 18 | No |
| 7 | domain/parser/GreekBankParserStressTest.kt | 10 | No |
| 8 | domain/parser/GreekBankParserTest.kt | 10 | No |
| 9 | domain/parser/NBGReproTest.kt | 1 | No |
| 10 | domain/parser/RevolutParserTest.kt | 25 | No |
| 11 | domain/parser/SmsParserTest.kt | 17 | No |
| 12 | domain/parser/TransferDirectionDetectorStressTest.kt | 16 | No |
| 13 | domain/parser/TransferDirectionDetectorTest.kt | 52 | No |

### domain/parser/parsers/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/parser/parsers/RevolutParserStressTest.kt | 20 | No |

### domain/price/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/price/PriceProtectionTrackerTest.kt | 19 | Yes |

### domain/receipt/ (8 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/receipt/BankStatementParserTest.kt | 17 | No |
| 2 | domain/receipt/BitmapConcurrencyTest.kt | 13 | No |
| 3 | domain/receipt/EnhancedMerchantExtractorTest.kt | 4 | No |
| 4 | domain/receipt/GreekNormalizationTest.kt | 5 | No |
| 5 | domain/receipt/OcrLanguageProcessorTest.kt | 13 | No |
| 6 | domain/receipt/ReceiptParserOcrPatternsTest.kt | 59 | No |
| 7 | domain/receipt/ReceiptParserTest.kt | 20 | No |
| 8 | domain/receipt/WarrantyTextExtractorTest.kt | 11 | Yes |

### domain/receiptmatching/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/receiptmatching/ReceiptTransactionMatcherTest.kt | 2 | No |

### domain/reminder/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/reminder/BillReminderManagerTest.kt | 5 | No |

### domain/savings/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt | 1 | No |
| 2 | domain/savings/AutomatedSavingsRuleEngineTest.kt | 8 | No |
| 3 | domain/savings/SavingsGamificationEngineTest.kt | 7 | No |
| 4 | domain/savings/SmartSavingsEngineTest.kt | 7 | No |

### domain/split/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/split/SplitCalculationPrecisionTest.kt | 22 | Yes |

### domain/tax/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/tax/TaxCalculationTest.kt | 35 | Yes |
| 2 | domain/tax/TaxEstimatorTest.kt | 14 | No |

### domain/usecase/budget/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/usecase/budget/CalculateBudgetStatusUseCaseTest.kt | 4 | No |
| 2 | domain/usecase/budget/GetMonteCarloBudgetImpactUseCaseTest.kt | 12 | No |

### domain/usecase/dashboard/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt | 14 | No |

### domain/usecase/expense/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/usecase/expense/CategorizeExpenseUseCaseTest.kt | 4 | No |
| 2 | domain/usecase/expense/DetectDuplicateExpenseUseCaseTest.kt | 5 | No |

### domain/usecase/forecast/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt | 6 | No |

### domain/usecase/savings/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/usecase/savings/LifestyleSavingsPromptUseCaseTest.kt | 9 | No |
| 2 | domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt | 10 | No |

### domain/usecase/warranty/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCaseTest.kt | 8 | No |

### domain/util/ (13 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/util/AmountUtilsStressTest.kt | 65 | No |
| 2 | domain/util/AmountUtilsTest.kt | 6 | No |
| 3 | domain/util/BKTreeTest.kt | 1 | No |
| 4 | domain/util/MerchantCleanerStressTest.kt | 35 | No |
| 5 | domain/util/MerchantKeyGeneratorStressTest.kt | 29 | No |
| 6 | domain/util/MerchantKeyGeneratorTest.kt | 16 | No |
| 7 | domain/util/MoneyTest.kt | 31 | Yes |
| 8 | domain/util/NotificationIdGeneratorTest.kt | 36 | No |
| 9 | domain/util/StatisticsUtilsStressTest.kt | 35 | No |
| 10 | domain/util/StringDistanceUtilsStressTest.kt | 28 | No |
| 11 | domain/util/TimePeriodUtilsStressTest.kt | 40 | No |
| 12 | domain/util/TimePeriodUtilsTest.kt | 46 | No |
| 13 | domain/util/TimePeriodUtilsValidationTest.kt | 77 | No |

### domain/widget/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | domain/widget/WidgetStyleRepositoryTest.kt | 2 | No |

### e2e/ (12 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | e2e/AnalyticsPipelineTest.kt | 5 | No |
| 2 | e2e/BudgetAlertPipelineTest.kt | 4 | No |
| 3 | e2e/CategoryBreakdownFlowTest.kt | 1 | No |
| 4 | e2e/DailyAverageFlowTest.kt | 1 | No |
| 5 | e2e/DateBoundaryFlowTest.kt | 1 | No |
| 6 | e2e/EmptyDataFlowTest.kt | 1 | No |
| 7 | e2e/FlowPipelineTestHarness.kt | 0 | No |
| 8 | e2e/GroupSettlementPipelineTest.kt | 4 | No |
| 9 | e2e/MonthlyTotalFlowTest.kt | 1 | No |
| 10 | e2e/NotificationExpenseDashboardPipelineTest.kt | 3 | No |
| 11 | e2e/ReceiptProcessingPipelineTest.kt | 4 | No |
| 12 | e2e/SharedExpenseFlowTest.kt | 1 | No |

### integration/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | integration/CategorizationPipelineIntegrationTest.kt | 20 | No |
| 2 | integration/EffectiveAmountPipelineIntegrationTest.kt | 1 | No |
| 3 | integration/ExpenseCreationPipelineIntegrationTest.kt | 16 | No |
| 4 | integration/MultiCurrencyAnalyticsTest.kt | 4 | No |

### metrics/ (7 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | metrics/DashboardWidgetConsistencyStressTest.kt | 3 | No |
| 2 | metrics/DashboardWidgetConsistencyTest.kt | 6 | No |
| 3 | metrics/EffectiveAmountConsistencyStressTest.kt | 7 | No |
| 4 | metrics/EffectiveAmountConsistencyTest.kt | 10 | No |
| 5 | metrics/GoldenAnalyticsDatasetTest.kt | 8 | No |
| 6 | metrics/TimePeriodAlignmentStressTest.kt | 5 | No |
| 7 | metrics/TimePeriodAlignmentTest.kt | 21 | No |

### receiver/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | receiver/BootReceiverStressTest.kt | 4 | No |
| 2 | receiver/ServiceRestartReceiverStressTest.kt | 3 | No |

### service/ (10 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | service/NavigationTargetResolverTest.kt | 32 | No |
| 2 | service/NotificationCaptureServiceFallbackTest.kt | 8 | No |
| 3 | service/NotificationCaptureServiceStressTest.kt | 3 | Yes |
| 4 | service/NotificationFilterTest.kt | 18 | No |
| 5 | service/RecommendationCacheServiceTest.kt | 18 | No |
| 6 | service/RecommendationDeduplicatorTest.kt | 11 | No |
| 7 | service/RecommendationDismissalHandlerTest.kt | 23 | No |
| 8 | service/RecommendationLifecycleManagerTest.kt | 32 | No |
| 9 | service/RecommendationStateManagerTest.kt | 30 | No |
| 10 | service/TransactionFilterSerializerTest.kt | 16 | No |

### service/receiptmatching/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | service/receiptmatching/ReceiptMatchingWorkerTest.kt | 6 | No |

### service/warranty/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | service/warranty/WarrantyExpirationWorkerTest.kt | 6 | No |

### ui/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/MainActivityDeepLinkTest.kt | 2 | No |
| 2 | ui/MainViewModelStressTest.kt | 5 | Yes |

### ui/components/emptystate/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/components/emptystate/ContextualActionRegistryTest.kt | 7 | No |

### ui/screens/addexpense/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/addexpense/AddExpenseViewModelStressTest.kt | 8 | Yes |
| 2 | ui/screens/addexpense/AddExpenseViewModelTest.kt | 4 | No |

### ui/screens/aisettings/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/aisettings/AiSettingsScreenTextTest.kt | 6 | Yes |
| 2 | ui/screens/aisettings/AiSettingsViewModelTest.kt | 8 | No |

### ui/screens/analytics/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/analytics/AnalyticsStateStressTest.kt | 20 | Yes |
| 2 | ui/screens/analytics/AnalyticsViewModelStressTest.kt | 8 | Yes |

### ui/screens/assistant/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/assistant/AssistantViewModelTest.kt | 20 | No |

### ui/screens/budget/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/budget/BudgetForecastingViewModelTest.kt | 6 | No |
| 2 | ui/screens/budget/BudgetViewModelStressTest.kt | 16 | Yes |

### ui/screens/carbon/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/carbon/CarbonFootprintScreenTest.kt | 3 | No |
| 2 | ui/screens/carbon/CarbonFootprintViewModelTest.kt | 8 | No |

### ui/screens/cashflow/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/cashflow/CashFlowCalendarViewModelTest.kt | 7 | No |

### ui/screens/challenge/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/challenge/SpendingChallengesViewModelTest.kt | 3 | No |

### ui/screens/currency/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/currency/CurrencyManagementScreenValidationTest.kt | 4 | No |
| 2 | ui/screens/currency/CurrencyManagementViewModelTest.kt | 4 | No |

### ui/screens/debug/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/debug/DebugScreenTextTest.kt | 5 | No |
| 2 | ui/screens/debug/DebugViewModelStressTest.kt | 7 | Yes |

### ui/screens/export/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/export/ExportOptionsViewModelTest.kt | 8 | No |

### ui/screens/groups/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt | 2 | No |
| 2 | ui/screens/groups/SharedExpenseGroupsViewModelTest.kt | 7 | No |

### ui/screens/home/ (3 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/home/HomeScreenWidgetTest.kt | 4 | No |
| 2 | ui/screens/home/HomeViewModelRecommendationTest.kt | 24 | No |
| 3 | ui/screens/home/HomeViewModelStressTest.kt | 20 | Yes |

### ui/screens/lifestyle/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/lifestyle/LifestyleInflationScreenTest.kt | 6 | No |
| 2 | ui/screens/lifestyle/LifestyleInflationViewModelTest.kt | 7 | No |

### ui/screens/map/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/map/SpendingMapViewModelStressTest.kt | 10 | Yes |

### ui/screens/price/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/price/PriceProtectionViewModelTest.kt | 9 | No |

### ui/screens/receiptmatching/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt | 4 | No |

### ui/screens/receiptscan/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt | 19 | Yes |

### ui/screens/recurringmanual/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/recurringmanual/ManualRecurringExpenseViewModelTest.kt | 4 | No |

### ui/screens/review/ (3 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/review/ReviewScreenTransactionTypeParserTest.kt | 3 | No |
| 2 | ui/screens/review/ReviewScreenTransferDirectionParserTest.kt | 3 | No |
| 3 | ui/screens/review/ReviewViewModelStressTest.kt | 37 | Yes |

### ui/screens/savings/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/savings/SavingsGoalsViewModelTest.kt | 7 | No |

### ui/screens/split/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/split/VisualSplitEditorScreenStateTest.kt | 11 | No |
| 2 | ui/screens/split/VisualSplitViewModelTest.kt | 5 | No |

### ui/screens/subscription/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/subscription/SubscriptionManagementViewModelTest.kt | 4 | No |

### ui/screens/transactions/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/transactions/TransactionsScreenTest.kt | 1 | No |
| 2 | ui/screens/transactions/TransactionsViewModelStressTest.kt | 16 | Yes |

### ui/screens/warranty/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/screens/warranty/WarrantyTrackerViewModelTest.kt | 6 | No |

### ui/util/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | ui/util/ClipboardAmountParserTest.kt | 3 | No |

### util/ (4 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | util/CsvExpenseImporterTest.kt | 12 | No |
| 2 | util/FlowTestUtils.kt | 0 | No |
| 3 | util/HiltTestUtils.kt | 0 | No |
| 4 | util/ViewModelTestUtils.kt | 1 | No |

### verification/ (6 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | verification/CarbonFootprintTest.kt | 7 | No |
| 2 | verification/CrossGroupIntegrationTest.kt | 9 | No |
| 3 | verification/CrossSourceVerificationTest.kt | 6 | No |
| 4 | verification/GoldenMasterVerificationTest.kt | 22 | No |
| 5 | verification/LifestyleAnalysisTest.kt | 7 | No |
| 6 | verification/SharedExpenseTest.kt | 15 | No |

## Instrumented Tests (app/src/androidTest/)

### data/database/ (2 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/database/DatabaseMigrationTest.kt | 63 | No |
| 2 | data/database/MigrationContractTest.kt | 10 | No |

### data/database/dao/ (23 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/database/dao/AiArtifactDaoTest.kt | 14 | No |
| 2 | data/database/dao/AiChatMessageDaoTest.kt | 5 | No |
| 3 | data/database/dao/AiChatSessionDaoTest.kt | 5 | No |
| 4 | data/database/dao/BudgetDaoTest.kt | 18 | No |
| 5 | data/database/dao/CategoryDaoTest.kt | 4 | No |
| 6 | data/database/dao/ComplexQueryTest.kt | 18 | No |
| 7 | data/database/dao/DaoStressTest.kt | 20 | No |
| 8 | data/database/dao/DedupeKeyUniquenessRegressionTest.kt | 3 | No |
| 9 | data/database/dao/ExchangeRateDaoTest.kt | 4 | No |
| 10 | data/database/dao/ExpenseDaoTest.kt | 40 | No |
| 11 | data/database/dao/ExpenseGroupDaoTest.kt | 4 | No |
| 12 | data/database/dao/FreshInstallBatch8ParityTest.kt | 23 | No |
| 13 | data/database/dao/FreshInstallIndexParityTest.kt | 5 | No |
| 14 | data/database/dao/GroupMemberDaoTest.kt | 16 | No |
| 15 | data/database/dao/MerchantLocationDaoTest.kt | 14 | No |
| 16 | data/database/dao/MerchantNormalizationDaoTest.kt | 12 | No |
| 17 | data/database/dao/PendingReviewDaoTest.kt | 8 | No |
| 18 | data/database/dao/RecommendationDaoTest.kt | 4 | No |
| 19 | data/database/dao/RecurringExpenseDaoTest.kt | 7 | No |
| 20 | data/database/dao/SavingsGoalDaoTest.kt | 16 | No |
| 21 | data/database/dao/ScannedReceiptDaoTest.kt | 7 | No |
| 22 | data/database/dao/UserCorrectionDaoTest.kt | 16 | No |
| 23 | data/database/dao/WarrantyDaoTest.kt | 4 | No |

### data/location/ (1 files)
| # | File | @Test Count | @Ignore? |
|---|------|-------------|----------|
| 1 | data/location/MerchantKeyBackfillWorkerTest.kt | 3 | No |

## Test Helpers & Fixtures
| # | File | Purpose |
|---|------|---------|
| 1 | AnalyticsEngineTestBase.kt | base test class |
| 2 | TestUtils.kt | test utility |
| 3 | domain/budget/BudgetForecastingEngineStubTest.kt | stub implementation |
| 4 | domain/util/AmountUtilsStressTest.kt | test utility |
| 5 | domain/util/AmountUtilsTest.kt | test utility |
| 6 | domain/util/FakeTimeProvider.kt | fake implementation |
| 7 | domain/util/StatisticsUtilsStressTest.kt | test utility |
| 8 | domain/util/StringDistanceUtilsStressTest.kt | test utility |
| 9 | domain/util/TimePeriodUtilsStressTest.kt | test utility |
| 10 | domain/util/TimePeriodUtilsTest.kt | test utility |
| 11 | domain/util/TimePeriodUtilsValidationTest.kt | test utility |
| 12 | e2e/FlowPipelineTestHarness.kt | test harness |
| 13 | util/FlowTestUtils.kt | test utility |
| 14 | util/HiltTestUtils.kt | test utility |
| 15 | util/ViewModelTestUtils.kt | test utility |

## Summary
- Total unit test files: 399
- Total instrumented test files: 26
- Total helper files: 15
- Total @Ignore'd files: 35
- Total @Test methods (approx): 4347