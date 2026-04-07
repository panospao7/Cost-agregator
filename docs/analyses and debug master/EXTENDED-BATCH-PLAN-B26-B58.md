# Extended Batch Plan — B26 to B58

> **Purpose**: Cover all `.kt` files NOT analyzed in B01–B24.
> **Total missed files**: ~364
> **Strategy**: Group by layer and feature, max 30 files per batch.
> **Priority order**: Data → Domain → UI

---

## DATA LAYER — B26 to B34

### B26: AI Providers — Cloud & Hybrid (27 files)
- `data/ai/provider/CloudWarrantyExtractionService.kt`
- `data/ai/provider/CloudReviewExplanationService.kt`
- `data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `data/ai/provider/CloudQueryInterpretationService.kt`
- `data/ai/provider/CloudReceiptAssistService.kt`
- `data/ai/provider/CloudDedupeJudgeService.kt`
- `data/ai/provider/CloudDashboardBriefingService.kt`
- `data/ai/provider/CloudCategorizationAssistService.kt`
- `data/ai/provider/HybridReviewExplanationService.kt`
- `data/ai/provider/HybridReceiptItemCategorizationService.kt`
- `data/ai/provider/HybridReceiptAssistService.kt`
- `data/ai/provider/HybridQueryInterpretationService.kt`
- `data/ai/provider/HybridDedupeJudgeService.kt`
- `data/ai/provider/HybridDashboardBriefingService.kt`
- `data/ai/provider/HybridCategorizationAssistService.kt`
- `data/ai/provider/SmartReceiptAssistService.kt`
- `data/ai/provider/internal/CloudRetryPolicy.kt`
- `data/ai/provider/internal/CloudCorrelation.kt`
- `data/ai/provider/internal/CloudPiiSanitizer.kt`
- `data/ai/provider/internal/CloudJsonParser.kt`
- `data/ai/provider/DefaultAiEnvironmentMonitor.kt`
- `data/ai/provider/DefaultAiCapabilityRouter.kt`
- `data/ai/provider/DefaultAiProvider.kt`
- `data/ai/provider/AiProvider.kt`
- `data/ai/provider/AiProviderModels.kt`
- `data/ai/provider/AiProviderRegistry.kt`
- `data/ai/provider/AiProviderRouter.kt`

### B27: AI Providers — On-Device & NoOp (14 files)
- `data/ai/provider/OnDeviceCategorizationAssistService.kt`
- `data/ai/provider/OnDeviceDashboardBriefingService.kt`
- `data/ai/provider/OnDeviceDedupeJudgeService.kt`
- `data/ai/provider/OnDeviceQueryInterpretationService.kt`
- `data/ai/provider/OnDeviceReceiptAssistService.kt`
- `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
- `data/ai/provider/OnDeviceReviewExplanationService.kt`
- `data/ai/provider/OnDeviceReviewPriorityScorer.kt`
- `data/ai/provider/OnDeviceSemanticDuplicateDetector.kt`
- `data/ai/provider/OnDeviceNotificationParser.kt`
- `data/ai/provider/NoOpCategorizationAssistService.kt`
- `data/ai/provider/NoOpDashboardBriefingService.kt`
- `data/ai/provider/NoOpDedupeJudgeService.kt`
- `data/ai/provider/NoOpQueryInterpretationService.kt`

### B28: Database — Advanced DAOs (22 files)
- `data/database/dao/AiArtifactDao.kt`
- `data/database/dao/AiChatMessageDao.kt`
- `data/database/dao/AiChatSessionDao.kt`
- `data/database/dao/AnomalyAlertDao.kt`
- `data/database/dao/BlockedPackageDao.kt`
- `data/database/dao/BudgetAdjustmentDao.kt`
- `data/database/dao/BudgetForecastDao.kt`
- `data/database/dao/HealthScoreHistoryDao.kt`
- `data/database/dao/InvestmentValueDao.kt`
- `data/database/dao/MerchantStatsDao.kt`
- `data/database/dao/MerchantStatsSummaryDao.kt`
- `data/database/dao/MileageTrackingDao.kt`
- `data/database/dao/NotificationCaptureDao.kt`
- `data/database/dao/PriceProtectionDao.kt`
- `data/database/dao/StressForecastSnapshotDao.kt`
- `data/database/dao/SubscriptionCandidateDao.kt`
- `data/database/dao/SubscriptionPriceHistoryDao.kt`
- `data/database/dao/SubscriptionUsageDao.kt`
- `data/database/dao/TaxCategoryDao.kt`
- `data/database/dao/TaxReportDao.kt`
- `data/database/dao/TransactionInsightDao.kt`
- `data/database/dao/UserCorrectionDao.kt`

### B29: Database — Advanced Entities (24 files)
- `data/database/entity/AiArtifactEntity.kt`
- `data/database/entity/AiChatMessageEntity.kt`
- `data/database/entity/AiChatSessionEntity.kt`
- `data/database/entity/AnomalyAlertEntity.kt`
- `data/database/entity/BlockedPackageEntity.kt`
- `data/database/entity/BudgetAdjustmentEntity.kt`
- `data/database/entity/BudgetForecastEntity.kt`
- `data/database/entity/HealthScoreHistoryEntity.kt`
- `data/database/entity/InvestmentValue.kt`
- `data/database/entity/MerchantStatsEntity.kt`
- `data/database/entity/MerchantStatsSummaryEntity.kt`
- `data/database/entity/MileageTrackingEntity.kt`
- `data/database/entity/NotificationCaptureEntity.kt`
- `data/database/entity/PriceProtectionEntity.kt`
- `data/database/entity/StressForecastSnapshotEntity.kt`
- `data/database/entity/SubscriptionCandidateEntity.kt`
- `data/database/entity/SubscriptionPriceHistoryEntity.kt`
- `data/database/entity/SubscriptionUsageEntity.kt`
- `data/database/entity/TaxCategoryEntity.kt`
- `data/database/entity/TaxReportEntity.kt`
- `data/database/entity/TransactionInsightEntity.kt`
- `data/database/entity/UserCorrectionEntity.kt`
- `data/database/entity/WidgetStyleEntity.kt`
- `data/database/entity/WidgetStylePresetEntity.kt`

### B30: Database — Models/DTOs (5 files)
- `data/database/model/DashboardWidgetConfig.kt`
- `data/database/model/ExpenseGroupWithDetails.kt`
- `data/database/model/ExpenseWithCategory.kt`
- `data/database/model/ExpenseWithCategoryName.kt`
- `data/database/model/ExpenseWithCategory_Extensions.kt`
- `data/database/model/PendingReviewWithReceipt.kt`

### B31: Location, Geocoding & Workers (11 files)
- `data/location/CompositeGeocodingService.kt`
- `data/location/GeoapifyGeocodingService.kt`
- `data/location/GooglePlacesGeocodingService.kt`
- `data/location/NominatimGeocodingService.kt`
- `data/location/PhotonGeocodingService.kt`
- `data/location/OverpassNearbyService.kt`
- `data/location/AndroidForegroundLocationProvider.kt`
- `data/location/LocationBackfillWorker.kt`
- `data/location/MerchantKeyBackfillWorker.kt`
- `data/location/internal/LogSanitizer.kt`
- `data/location/LocationModels.kt`

### B32: Email, Currency, Speech & Security (11 files)
- `data/email/EmailReceiptIngestionService.kt`
- `data/email/provider/AmazonReceiptParser.kt`
- `data/email/provider/AppleReceiptParser.kt`
- `data/email/provider/EmailReceiptParser.kt`
- `data/email/provider/UberReceiptParser.kt`
- `data/currency/ExchangeRateStoreAdapter.kt`
- `data/speech/AndroidSpeechInputGateway.kt`
- `data/security/BankTokenCipher.kt`
- `data/security/SecureKeyStorage.kt`
- `data/provider/MerchantCategoryProvider.kt`
- `data/repository/ParserEnumMappers.kt`

### B33: Repositories — Core & AI (28 files)
- `data/repository/AiArtifactRepositoryImpl.kt`
- `data/repository/AiChatRepositoryImpl.kt`
- `data/repository/AiEngagementRepositoryImpl.kt`
- `data/repository/AiSettingsRepositoryImpl.kt`
- `data/repository/AnalyticsRepository.kt`
- `data/repository/BudgetRepository.kt`
- `data/repository/BusinessExpenseRepository.kt`
- `data/repository/CategoryRepository.kt`
- `data/repository/CurrencyDataRepository.kt`
- `data/repository/CurrencyRatesRepositoryImpl.kt`
- `data/repository/CurrencySettingsRepositoryImpl.kt`
- `data/repository/DashboardContractsAdapter.kt`
- `data/repository/DashboardRepository.kt`
- `data/repository/DatabaseBackupRepositoryImpl.kt`
- `data/repository/ExpenseRepository.kt`
- `data/repository/ExportDataRepository.kt`
- `data/repository/FinancialWeatherRepository.kt`
- `data/repository/GroupsRepository.kt`
- `data/repository/GroupsRepositoryImpl.kt`
- `data/repository/LocationResolverPortsAdapters.kt`
- `data/repository/ManualExpenseRepository.kt`
- `data/repository/ManualRecurringExpenseRepository.kt`
- `data/repository/MerchantCategoryRepository.kt`
- `data/repository/MerchantLocationRepository.kt`
- `data/repository/MerchantNormalizationRepository.kt`
- `data/repository/MerchantRulesRepository.kt`
- `data/repository/MultiCurrencyRepository.kt`
- `data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt`

### B34: Repositories — Remaining (14 files)
- `data/repository/NotificationProcessingPipeline.kt`
- `data/repository/NotificationRepository.kt`
- `data/repository/PlannedExpenseRepository.kt`
- `data/repository/PromptStateRepository.kt`
- `data/repository/ReceiptItemCategorizationRepository.kt`
- `data/repository/ReceiptRepository.kt`
- `data/repository/RecommendationRepository.kt`
- `data/repository/RecurringExpenseRepository.kt`
- `data/repository/ReviewQueueRepository.kt`
- `data/repository/SavingsGoalRepository.kt`
- `data/repository/SharedExpenseDataPortAdapter.kt`
- `data/repository/SourceStatsRepository.kt`
- `data/repository/SubscriptionManagementRepository.kt`
- `data/repository/UserCorrectionRepository.kt`
- `data/repository/WidgetStyleRepositoryImpl.kt`
- `data/repository/AccountingExportRepository.kt`

---

## DOMAIN LAYER — B35 to B49

### B35: AI Models, Policies & Services (24 files)
- `domain/ai/model/AiModels.kt`
- `domain/ai/model/AiRuntimeStatusModels.kt`
- `domain/ai/model/AiArtifactPresentation.kt`
- `domain/ai/model/AiLoadState.kt`
- `domain/ai/model/CaptureAssistModels.kt`
- `domain/ai/model/FinancialQueryModels.kt`
- `domain/ai/model/NotificationParsingModels.kt`
- `domain/ai/model/OnDeviceRuntimePresentation.kt`
- `domain/ai/model/ReceiptItemCategorizationModels.kt`
- `domain/ai/model/ReviewPriorityModels.kt`
- `domain/ai/model/SemanticDuplicateModels.kt`
- `domain/ai/model/WarrantyExtractionModels.kt`
- `domain/ai/policy/AiPolicy.kt`
- `domain/ai/policy/AiPolicyImpl.kt`
- `domain/ai/policy/DefaultAiCapabilityRouter.kt`
- `domain/ai/service/AiArtifactRepository.kt`
- `domain/ai/service/AiCapabilityRouter.kt`
- `domain/ai/service/AiChatRepository.kt`
- `domain/ai/service/AiEngagementRepository.kt`
- `domain/ai/service/AiEnvironmentMonitor.kt`
- `domain/ai/service/AiSettingsRepository.kt`
- `domain/ai/service/AiWorkScheduler.kt`
- `domain/ai/service/CategorizationAssistService.kt`
- `domain/ai/service/DashboardBriefingService.kt`

### B36: AI Services — Remaining & Use Cases (24 files)
- `domain/ai/service/DedupeJudgeService.kt`
- `domain/ai/service/NotificationFallbackParser.kt`
- `domain/ai/service/QueryInterpretationService.kt`
- `domain/ai/service/ReceiptAssistService.kt`
- `domain/ai/service/ReceiptItemCategorizationService.kt`
- `domain/ai/service/ReviewExplanationService.kt`
- `domain/ai/service/ReviewPriorityScorer.kt`
- `domain/ai/service/SemanticDuplicateDetector.kt`
- `domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- `domain/ai/usecase/DashboardBriefingInputBuilder.kt`
- `domain/ai/usecase/DedupeJudgeInputBuilder.kt`
- `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
- `domain/ai/usecase/DetectSemanticDuplicateUseCase.kt`
- `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`
- `domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- `domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
- `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `domain/ai/usecase/GenerateTransactionInsightUseCase.kt`
- `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt`
- `domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
- `domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
- `domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt`
- `domain/ai/usecase/PrioritizeReviewItemsUseCase.kt`

### B37: AI Use Cases — Remaining & Analytics (22 files)
- `domain/ai/usecase/ReceiptAssistInputBuilder.kt`
- `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `domain/ai/usecase/ReviewExplanationInputBuilder.kt`
- `domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
- `domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
- `domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt`
- `domain/analytics/AdvancedAnalyticsDashboard.kt`
- `domain/analytics/AdvancedAnalyticsEngine.kt`
- `domain/analytics/AdvancedAnalyticsModels.kt`
- `domain/analytics/AnalyticsModels.kt`
- `domain/analytics/AnomalyDetector.kt`
- `domain/analytics/CategoryInsightEngine.kt`
- `domain/analytics/DayOfWeekAnalyzer.kt`
- `domain/analytics/InsightsEngine.kt`
- `domain/analytics/MerchantInsightEngine.kt`
- `domain/analytics/MonthlyComparisonCalculator.kt`
- `domain/analytics/SpendingPaceCalculator.kt`
- `domain/analytics/SpendingPersonalityClassifier.kt`
- `domain/analytics/SpendingThresholdCalculator.kt`
- `domain/analytics/TotalsAggregationEngine.kt`
- `domain/analytics/TransferDirectionAnalytics.kt`
- `domain/alerts/AnomalyAlertOrchestrator.kt`

### B38: Budget, Business, Carbon & Cashflow (10 files)
- `domain/budget/BudgetAutopilotEngine.kt`
- `domain/budget/BudgetCalculator.kt`
- `domain/budget/BudgetForecastingEngine.kt`
- `domain/budget/BudgetModels.kt`
- `domain/budget/BudgetMonitor.kt`
- `domain/budget/BudgetRecommendationEngine.kt`
- `domain/budget/BudgetRecommendationInputs.kt`
- `domain/budget/SharedBudgetManager.kt`
- `domain/business/BusinessExpenseReportGenerator.kt`
- `domain/carbon/CarbonFootprintCalculator.kt`
- `domain/cashflow/CashFlowCalculator.kt`

### B39: Categorization, Challenges & Config (12 files)
- `domain/categorization/CategorizationEngine.kt`
- `domain/categorization/CategorizationModels.kt`
- `domain/categorization/ContextualInferenceEngine.kt`
- `domain/categorization/MerchantCanonicalizer.kt`
- `domain/categorization/SemanticKeywordMatcher.kt`
- `domain/challenge/SpendingChallengeModels.kt`
- `domain/challenge/SpendingChallengeOrchestrator.kt`
- `domain/challenge/SpendingChallengeRepository.kt`
- `domain/config/AppConfig.kt`
- `domain/config/FeatureFlags.kt`
- `domain/config/FeatureToggles.kt`
- `domain/currency/CurrencyConverter.kt`

### B40: Debug, Backup & Export (13 files)
- `domain/debug/AiRuntimeDiagnostics.kt`
- `domain/debug/DebugData.kt`
- `domain/debug/DebugIssue.kt`
- `domain/debug/DebugIssueDetector.kt`
- `domain/debug/NotificationSeeder.kt`
- `domain/debug/ServiceDiagnostics.kt`
- `domain/backup/DatabaseBackupRepository.kt`
- `domain/backup/DatabaseOperationResults.kt`
- `domain/export/AccountingExporters.kt`
- `domain/export/ExportTransaction.kt`
- `domain/engine/DashboardFollowThroughEngine.kt`
- `domain/bank/BankApiIntegration.kt`
- `domain/reminder/BillReminderManager.kt`

### B41: Forecasting & Groups (13 files)
- `domain/forecasting/DataQualityAssessor.kt`
- `domain/forecasting/FinancialStressForecastEngine.kt`
- `domain/forecasting/HistoricalSpendingDistribution.kt`
- `domain/forecasting/MonteCarloResult.kt`
- `domain/forecasting/MonteCarloSpendingSimulator.kt`
- `domain/groups/GroupTransactionCoordinator.kt`
- `domain/groups/SettlementCalculator.kt`
- `domain/groups/SharedExpenseBudgetOffsetEngine.kt`
- `domain/groups/SharedExpenseManager.kt`
- `domain/groups/SharedExpensePort.kt`
- `domain/groups/usecase/AddGroupExpenseUseCase.kt`
- `domain/groups/usecase/DeleteGroupMemberUseCase.kt`
- `domain/groups/usecase/DeleteGroupUseCase.kt`

### B42: Health, Income, Intelligence & Investment (13 files)
- `domain/health/FinancialHealthCalculator.kt`
- `domain/health/FinancialHealthScoreV2.kt`
- `domain/income/RecurringIncomeTracker.kt`
- `domain/intelligence/ConfidenceRouter.kt`
- `domain/intelligence/CrossSourceDeduplication.kt`
- `domain/intelligence/TransactionClassifier.kt`
- `domain/intelligence/ml/ExpenseCategoryClassifier.kt`
- `domain/intelligence/ml/ExpenseClassifier.kt`
- `domain/intelligence/ml/FeatureExtractor.kt`
- `domain/intelligence/ml/HybridExpenseClassifier.kt`
- `domain/intelligence/ml/MerchantNormalizer.kt`
- `domain/investment/InvestmentTracker.kt`
- `domain/investment/InvestmentModels.kt`

### B43: Location Insights & ML Intelligence (11 files)
- `domain/location/LocationInsightsRepository.kt`
- `domain/location/LocationResolver.kt`
- `domain/location/LocationModels.kt`
- `domain/location/LocationInsightsCalculator.kt`
- `domain/location/MerchantLocationService.kt`
- `domain/location/PriceProtectionRepository.kt`
- `domain/location/CarbonFootprintRepository.kt`
- `domain/ml/IntelligenceModels.kt`
- `domain/ml/IntelligenceEngine.kt`
- `domain/ml/FeaturePipeline.kt`
- `domain/ml/ModelEvaluator.kt`

### B44: Logic, Negotiation & Parsers (14 files)
- `domain/logic/CustomSplitParser.kt`
- `domain/logic/NarrativeGenerator.kt`
- `domain/logic/RecurrenceCalculator.kt`
- `domain/logic/RecurringExpenseEngine.kt`
- `domain/logic/SplitCalculator.kt`
- `domain/logic/SynthesisEngine.kt`
- `domain/negotiation/NegotiationTracker.kt`
- `domain/negotiation/NegotiationModels.kt`
- `domain/parser/AppParserRegistry.kt`
- `domain/parser/GenericTransactionParser.kt`
- `domain/parser/ParsedTransactionEnums.kt`
- `domain/parser/TransferDirectionDetector.kt`
- `domain/parser/parsers/GreekBankParser.kt`
- `domain/parser/parsers/GoogleWalletParser.kt`

### B45: Parsers — Remaining & Performance (11 files)
- `domain/parser/parsers/RevolutParser.kt`
- `domain/parser/parsers/SmsParser.kt`
- `domain/performance/ImageCache.kt`
- `domain/performance/PerformanceMonitor.kt`
- `domain/performance/PerformanceModels.kt`
- `domain/price/PriceProtectionTracker.kt`
- `domain/receipt/BankStatementParser.kt`
- `domain/receipt/EnhancedMerchantExtractor.kt`
- `domain/receipt/MerchantRulesPolicy.kt`
- `domain/receipt/OcrLanguageProcessor.kt`
- `domain/receipt/OcrPreprocessingPipeline.kt`

### B46: Receipt, Savings & Tax (14 files)
- `domain/receipt/ReceiptOcrService.kt`
- `domain/receipt/ReceiptParser.kt`
- `domain/receipt/ReceiptSource.kt`
- `domain/receipt/WarrantyTextExtractor.kt`
- `domain/receiptmatching/ReceiptTransactionMatcher.kt`
- `domain/savings/AutomatedSavingsRuleEngine.kt`
- `domain/savings/SavingsGamificationEngine.kt`
- `domain/savings/SavingsGoalRepository.kt`
- `domain/savings/SmartSavingsEngine.kt`
- `domain/tax/TaxConfiguration.kt`
- `domain/tax/TaxEstimator.kt`
- `domain/service/NotificationService.kt`
- `domain/receiptmatching/ReceiptMatchingModels.kt`
- `domain/receiptmatching/ReceiptMatchingWorker.kt`

### B47: Domain Models — Dashboard & Recommendation (14 files)
- `domain/model/BlockPartyDay.kt`
- `domain/model/CategoryBreakdown.kt`
- `domain/model/CategoryInfo.kt`
- `domain/model/FinancialForecast.kt`
- `domain/model/PeriodDrillDownState.kt`
- `domain/model/PeriodRange.kt`
- `domain/model/PeriodTotal.kt`
- `domain/model/PlannedExpense.kt`
- `domain/model/RecurringPattern.kt`
- `domain/model/Result.kt`
- `domain/model/SavingsGoal.kt`
- `domain/model/UpcomingItem.kt`
- `domain/model/UiText.kt`
- `domain/model/budget/MonteCarloBudgetImpact.kt`

### B48: Domain Models — Remaining & Text/Widgets (14 files)
- `domain/model/dashboard/BudgetStatusSnapshot.kt`
- `domain/model/dashboard/DashboardBlockStatus.kt`
- `domain/model/dashboard/DashboardCategoryBreakdown.kt`
- `domain/model/dashboard/DashboardDayBudgetStatus.kt`
- `domain/model/dashboard/DashboardExpenseMapper.kt`
- `domain/model/dashboard/DashboardPrimitives.kt`
- `domain/model/dashboard/FinancialWeather.kt`
- `domain/model/dashboard/SpendingSummary.kt`
- `domain/model/navigation/DomainTransactionFilter.kt`
- `domain/model/recommendation/DashboardFollowThroughRecommendation.kt`
- `domain/model/recommendation/RecommendationPriority.kt`
- `domain/model/recommendation/RecommendationStatus.kt`
- `domain/widget/model/WidgetStyle.kt`
- `domain/widget/service/WidgetStyleRepository.kt`

### B49: Domain Use Cases & Text (10 files)
- `domain/usecase/budget/CalculateBudgetStatusUseCase.kt`
- `domain/usecase/budget/GetMonteCarloBudgetImpactUseCase.kt`
- `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `domain/usecase/dashboard/DashboardContractsAdapter.kt`
- `domain/usecase/dashboard/DashboardDataProvider.kt`
- `domain/usecase/dashboard/DashboardRepositoryContracts.kt`
- `domain/usecase/expense/CategorizeExpenseUseCase.kt`
- `domain/usecase/expense/DetectDuplicateExpenseUseCase.kt`
- `domain/usecase/expense/ExpenseUseCases.kt`
- `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
- `domain/usecase/receipt/ProcessReceiptUseCase.kt`
- `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`
- `domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
- `domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`
- `domain/text/DashboardTextKeys.kt`
- `domain/text/DomainTextKeys.kt`

---

## UI LAYER — B50 to B58

### B50: UI — Main, Theme, Navigation & Mappers (9 files)
- `ui/MainActivity.kt`
- `ui/MainViewModel.kt`
- `ui/theme/Theme.kt`
- `ui/theme/Dimens.kt`
- `ui/navigation/NavigationController.kt`
- `ui/navigation/NavigationDestination.kt`
- `ui/navigation/FeatureConfig.kt`
- `ui/mappers/DashboardWidgetUiMapper.kt`
- `ui/mappers/TransactionFilterUiMapper.kt`
- `ui/integration/FeatureIntegration.kt`

### B51: UI — Common Utilities (7 files)
- `ui/util/ClipboardAmountParser.kt`
- `ui/util/ColorExtensions.kt`
- `ui/util/HapticFeedback.kt`
- `ui/util/ModifierExtensions.kt`
- `ui/components/UiTextExtensions.kt`
- `ui/components/ChartMarker.kt`
- `ui/components/PulseDot.kt`

### B52: UI — EmptyState & Common Components (11 files)
- `ui/components/common/EmptyState.kt`
- `ui/components/common/EnhancedEmptyState.kt`
- `ui/components/common/ErrorState.kt`
- `ui/components/common/LoadingSkeleton.kt`
- `ui/components/emptystate/ContextualActionRegistry.kt`
- `ui/components/emptystate/DefaultEmptyStateRegistryInitializer.kt`
- `ui/components/emptystate/EmptyStateAction.kt`
- `ui/components/emptystate/EmptyStatePresentationModule.kt`
- `ui/components/NotificationPermissionDialog.kt`
- `ui/components/LocationPermissionDialog.kt`
- `ui/components/LocationCorrectionSheet.kt`

### B53: UI — Feature Components (10 files)
- `ui/components/feature/FeatureComponents.kt`
- `ui/components/feature/FormComponents.kt`
- `ui/components/feature/MetricComponents.kt`
- `ui/components/PeriodBlock.kt`
- `ui/components/PeriodGridView.kt`
- `ui/components/PeriodNavigationBar.kt`
- `ui/components/AppFabMenu.kt`
- `ui/components/AppNavigationBar.kt`
- `ui/components/BentoCard.kt`
- `ui/components/RecommendationCard.kt`

### B54: UI — Dashboard & Analytics Components (14 files)
- `ui/components/analytics/NoSpendStreakWidget.kt`
- `ui/components/analytics/PersonalityProfileCard.kt`
- `ui/components/analytics/StatisticalVisualizations.kt`
- `ui/components/health/FinancialHealthScoreV2Widget.kt`
- `ui/components/health/HealthScoreWidget.kt`
- `ui/components/dashboard/MoneyRadarWidget.kt`
- `ui/components/CategoryBreakdownSheet.kt`
- `ui/components/CategoryDonutChart.kt`
- `ui/components/ForecastTimeline.kt`
- `ui/components/SpendingPaceGauge.kt`
- `ui/components/SpendingTrendChart.kt`
- `ui/components/TotalsDashboardCard.kt`
- `ui/components/RetroTopCategoriesCard.kt`
- `ui/components/RetroTotalsDashboardCard.kt`

### B55: UI — AI, Location & Specialized Components (16 files)
- `ui/components/ai/AiChatBubble.kt`
- `ui/components/ai/AiInsightsCard.kt`
- `ui/components/ai/AiRecommendationCard.kt`
- `ui/components/ai/AiTypingIndicator.kt`
- `ui/components/ai/AssistantResultCard.kt`
- `ui/components/ai/CategoryAssistCard.kt`
- `ui/components/ai/DedupeAssistCard.kt`
- `ui/components/ai/ReceiptAssistCard.kt`
- `ui/components/ai/ReceiptItemBreakdownCard.kt`
- `ui/components/FinancialRunwayCard.kt`
- `ui/components/FinancialStressForecastCard.kt`
- `ui/components/FinancialWeatherCard.kt`
- `ui/components/LocationSearchPicker.kt`
- `ui/components/MonteCarloForecastCard.kt`
- `ui/components/NearbyShopSuggestionCard.kt`
- `ui/components/PlaceInsightCard.kt`

### B56: UI — Retro & Budget Components (8 files)
- `ui/components/RetroBudgetBlockPartyCard.kt`
- `ui/components/RetroCategoryBreakdownSheet.kt`
- `ui/components/BudgetBlockPartyCard.kt`
- `ui/components/TransferDirectionBadge.kt`
- `ui/screens/analytics/AdvancedAnalyticsScreen.kt`
- `ui/screens/analytics/AnalyticsScreen.kt`
- `ui/screens/analytics/AnalyticsViewModel.kt`
- `ui/screens/cashflow/CashFlowCalendarScreen.kt`
- `ui/screens/cashflow/CashFlowCalendarViewModel.kt`
- `ui/screens/map/SpendingMapScreen.kt`
- `ui/screens/map/SpendingMapViewModel.kt`

### B57: UI — Screens Part 1 (28 files)
- `ui/screens/addexpense/AddExpenseSheet.kt`
- `ui/screens/addexpense/AddExpenseViewModel.kt`
- `ui/screens/aisettings/AiSettingsScreen.kt`
- `ui/screens/aisettings/AiSettingsViewModel.kt`
- `ui/screens/assistant/AssistantSheet.kt`
- `ui/screens/assistant/AssistantViewModel.kt`
- `ui/screens/bank/BankConnectionsScreen.kt`
- `ui/screens/bank/BankConnectionsViewModel.kt`
- `ui/screens/budget/BudgetForecastingScreen.kt`
- `ui/screens/budget/BudgetForecastingViewModel.kt`
- `ui/screens/budget/BudgetScreen.kt`
- `ui/screens/budget/BudgetViewModel.kt`
- `ui/screens/carbon/CarbonFootprintScreen.kt`
- `ui/screens/carbon/CarbonFootprintViewModel.kt`
- `ui/screens/categories/CategoryScreen.kt`
- `ui/screens/categories/CategoryViewModel.kt`
- `ui/screens/challenge/SpendingChallengesScreen.kt`
- `ui/screens/challenge/SpendingChallengesViewModel.kt`
- `ui/screens/currency/CurrencyManagementScreen.kt`
- `ui/screens/currency/CurrencyManagementViewModel.kt`
- `ui/screens/debug/CategorizationDebugScreen.kt`
- `ui/screens/debug/CategorizationDebugViewModel.kt`
- `ui/screens/debug/DebugDataStorage.kt`
- `ui/screens/debug/DebugIssueDetector.kt`
- `ui/screens/debug/DebugScreen.kt`
- `ui/screens/debug/DebugViewerScreen.kt`
- `ui/screens/debug/DebugViewModel.kt`
- `ui/screens/export/ExportOptionsScreen.kt`

### B58: UI — Screens Part 2 (22 files)
- `ui/screens/export/ExportOptionsViewModel.kt`
- `ui/screens/groups/SharedExpenseGroupsScreen.kt`
- `ui/screens/groups/SharedExpenseGroupsViewModel.kt`
- `ui/screens/home/HomeScreen.kt`
- `ui/screens/home/HomeViewModel.kt`
- `ui/screens/lifestyle/LifestyleInflationScreen.kt`
- `ui/screens/lifestyle/LifestyleInflationViewModel.kt`
- `ui/screens/receiptscan/ReceiptScanScreen.kt`
- `ui/screens/receiptscan/ReceiptScanViewModel.kt`
- `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt`
- `ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt`
- `ui/screens/reminder/BillRemindersScreen.kt`
- `ui/screens/reminder/BillRemindersViewModel.kt`
- `ui/screens/review/ReviewScreen.kt`
- `ui/screens/review/ReviewViewModel.kt`
- `ui/screens/savings/SavingsGoalsScreen.kt`
- `ui/screens/savings/SavingsGoalsViewModel.kt`
- `ui/screens/split/SplitTemplatesScreen.kt`
- `ui/screens/split/VisualSplitEditorScreen.kt`
- `ui/screens/split/VisualSplitViewModel.kt`
- `ui/screens/subscription/SubscriptionManagementScreen.kt`
- `ui/screens/subscription/SubscriptionManagementViewModel.kt`
- `ui/screens/tax/TaxConfigurationScreen.kt`
- `ui/screens/tax/TaxConfigurationViewModel.kt`
- `ui/screens/transactions/TransactionFilter.kt`
- `ui/screens/transactions/TransactionFilterSheet.kt`
- `ui/screens/transactions/TransactionsScreen.kt`
- `ui/screens/transactions/TransactionsViewModel.kt`
- `ui/screens/warranty/WarrantyTrackerScreen.kt`
- `ui/screens/warranty/WarrantyTrackerViewModel.kt`

---

## Summary

| Batch | Layer | Description | Files |
|-------|-------|-------------|-------|
| B26 | Data | AI Providers — Cloud & Hybrid | 27 |
| B27 | Data | AI Providers — On-Device & NoOp | 14 |
| B28 | Data | Database — Advanced DAOs | 22 |
| B29 | Data | Database — Advanced Entities | 24 |
| B30 | Data | Database — Models/DTOs | 6 |
| B31 | Data | Location, Geocoding & Workers | 11 |
| B32 | Data | Email, Currency, Speech & Security | 11 |
| B33 | Data | Repositories — Core & AI | 28 |
| B34 | Data | Repositories — Remaining | 16 |
| B35 | Domain | AI Models, Policies & Services | 24 |
| B36 | Domain | AI Services — Remaining & Use Cases | 24 |
| B37 | Domain | AI Use Cases — Remaining & Analytics | 22 |
| B38 | Domain | Budget, Business, Carbon & Cashflow | 11 |
| B39 | Domain | Categorization, Challenges & Config | 12 |
| B40 | Domain | Debug, Backup & Export | 13 |
| B41 | Domain | Forecasting & Groups | 13 |
| B42 | Domain | Health, Income, Intelligence & Investment | 13 |
| B43 | Domain | Location Insights & ML Intelligence | 11 |
| B44 | Domain | Logic, Negotiation & Parsers | 14 |
| B45 | Domain | Parsers — Remaining & Performance | 11 |
| B46 | Domain | Receipt, Savings & Tax | 14 |
| B47 | Domain | Domain Models — Dashboard & Recommendation | 14 |
| B48 | Domain | Domain Models — Remaining & Text/Widgets | 14 |
| B49 | Domain | Domain Use Cases & Text | 17 |
| B50 | UI | Main, Theme, Navigation & Mappers | 10 |
| B51 | UI | Common Utilities | 7 |
| B52 | UI | EmptyState & Common Components | 11 |
| B53 | UI | Feature Components | 10 |
| B54 | UI | Dashboard & Analytics Components | 14 |
| B55 | UI | AI, Location & Specialized Components | 16 |
| B56 | UI | Retro & Budget Components | 11 |
| B57 | UI | Screens Part 1 | 28 |
| B58 | UI | Screens Part 2 | 30 |
| **TOTAL** | | | **~530** |

> **Note**: File counts are approximate. Some files listed may not exist (dead references from the batch plan) or may have been renamed. Each batch should be verified against the actual filesystem before analysis begins.
