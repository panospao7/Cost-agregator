# Complete Backend & Database Map - ExpenseTracker

**Generated:** 2026-05-07  
**Total Files Mapped:** ~926 (344 domain + 253 data + 31 di + ~298 other)  
**Test Coverage:** 475 test files (449 test + 26 androidTest)

---

## Table of Contents

1. [Domain Package (344 files)](#domain-package)
   - [AI/ML Subsystem](#ai-subsystem)
   - [Alerts & Anomalies](#alerts--anomalies)
   - [Analytics & Insights](#analytics--insights)
   - [Backup & Export](#backup--export)
   - [Bank API Integration](#bank-api-integration)
   - [Budget Management](#budget-management)
   - [Business Expense Reporting](#business-expense-reporting)
   - [Carbon Footprint](#carbon-footprint)
   - [Cash Flow](#cash-flow)
   - [Categorization Engine](#categorization-engine)
   - [Challenge, Config, Currency](#challenge-config-currency)
   - [Core Money Types](#core-money-types)
   - [Core Time Types](#core-time-types)
   - [Dashboard & Engine](#dashboard--engine)
   - [Data Models](#data-models)
   - [Debug Utilities](#debug-utilities)
   - [Diagnostics](#diagnostics)
   - [DTOs](#dtos)
   - [Forecasting & Financial](#forecasting--financial)
   - [Groups & Shared Expenses](#groups--shared-expenses)
   - [Health & Income](#health--income)
   - [Intelligence/ML](#intelligenceml)
   - [Investment & Lifestyle](#investment--lifestyle)
   - [Location Services](#location-services)
   - [Logic & Business Rules](#logic--business-rules)
   - [Natural Language](#natural-language)
   - [Other Domain Services](#other-domain-services)
   - [Parsing & Receipt](#parsing--receipt)
   - [Privacy & Data Protection](#privacy--data-protection)
   - [Receipt Lifecycle](#receipt-lifecycle)
   - [Recurring Expenses](#recurring-expenses)
   - [Reminder Management](#reminder-management)
   - [Savings](#savings)
   - [Tax](#tax)
   - [Text & UI Keys](#text--ui-keys)
   - [Transaction Lifecycle](#transaction-lifecycle)
   - [Use Cases](#use-cases)
   - [Utilities](#utilities)
   - [Widget](#widget)
   - [Workers](#workers)
2. [Data Package (253 files)](#data-package)
   - [Database Layer](#database-layer)
   - [Repositories](#repositories)
   - [AI Providers](#ai-providers)
   - [Backup Services](#backup-services)
   - [Email Ingestion](#email-ingestion)
   - [Location Services](#location-services-1)
   - [Privacy Services](#privacy-services)
   - [Security Services](#security-services)
   - [Speech Services](#speech-services)
   - [Other Services](#other-services)
  3. [DI/Modules Package (31 files)](#dimodules-package)
 4. [App Services Package (16 files)](#app-services-package)
 5. [Dependency Graph & Data Flow](#dependency-graph--data-flow)

---

## DOMAIN PACKAGE

### AI Subsystem (64 files)

**Location:** `com.yourname.expensetracker.domain.ai`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `ai/model/AiArtifactPresentation.kt` | AiArtifactPresentation | Presentation models for AI artifacts | Model | - | No |
| `ai/model/AiLoadState.kt` | AiLoadState | Loading state enums for AI operations | Model/Enum | - | No |
| `ai/model/AiModels.kt` | AiModels | Core AI capability and routing models | Model | - | No |
| `ai/model/AiRuntimeStatusModels.kt` | AiRuntimeStatusModels | Runtime status data classes | Model | - | No |
| `ai/model/CaptureAssistModels.kt` | CaptureAssistModels | Receipt capture assistance models | Model | - | No |
| `ai/model/FinancialQueryModels.kt` | FinancialQueryModels | Natural language query models | Model | - | No |
| `ai/model/NotificationParsingModels.kt` | NotificationParsingModels | SMS/notification parsing models | Model | - | No |
| `ai/model/OnDeviceRuntimePresentation.kt` | OnDeviceRuntimePresentation | On-device ML runtime presentation | Model | - | No |
| `ai/model/ReceiptItemCategorizationModels.kt` | ReceiptItemCategorizationModels | Receipt item categorization models | Model | - | No |
| `ai/model/ReviewPriorityModels.kt` | ReviewPriorityModels | Transaction review prioritization models | Model | - | No |
| `ai/model/SemanticDuplicateModels.kt` | SemanticDuplicateModels | Duplicate detection models | Model | - | No |
| `ai/model/WarrantyExtractionModels.kt` | WarrantyExtractionModels | Warranty data extraction models | Model | - | No |
| `ai/model/ExtractedAmountFilter.kt` | ExtractedAmountFilter | Currency filter for NL queries | Model | - | No |
| `ai/model/FinancialQueryDataQuality.kt` | FinancialQueryDataQuality | Data quality metadata for NL queries | Model | - | No |
| `ai/policy/AiPolicy.kt` | AiPolicy | AI policy interface | Service | - | No |
| `ai/policy/AiPolicyImpl.kt` | AiPolicyImpl | AI policy implementation | Service | AiPolicy | No |
| `ai/policy/DefaultAiCapabilityRouter.kt` | DefaultAiCapabilityRouter | Default routing logic | Engine | AiCapabilityRouter | No |
| `ai/service/AiArtifactRepository.kt` | AiArtifactRepository | AI artifact storage interface | Repository | - | No |
| `ai/service/AiCapabilityRouter.kt` | AiCapabilityRouter | Routes AI requests to providers | Service | AiModels | No |
| `ai/service/AiChatRepository.kt` | AiChatRepository | Chat history storage interface | Repository | - | No |
| `ai/service/AiEngagementRepository.kt` | AiEngagementRepository | User engagement tracking | Repository | - | No |
| `ai/service/AiEnvironmentMonitor.kt` | AiEnvironmentMonitor | Monitors device/network environment | Service | - | No |
| `ai/service/AiSettingsRepository.kt` | AiSettingsRepository | AI settings persistence | Repository | - | No |
| `ai/service/AiWorkScheduler.kt` | AiWorkScheduler | Schedules AI background tasks | Service | - | No |
| `ai/service/CategorizationAssistService.kt` | CategorizationAssistService | Assists with expense categorization | Service | - | No |
| `ai/service/DashboardBriefingService.kt` | DashboardBriefingService | Generates dashboard briefings | Service | - | No |
| `ai/service/DedupeJudgeService.kt` | DedupeJudgeService | Judges duplicate transactions | Service | - | No |
| `ai/service/NotificationFallbackParser.kt` | NotificationFallbackParser | Parses SMS when AI unavailable | Service | - | No |
| `ai/service/QueryInterpretationService.kt` | QueryInterpretationService | Interprets natural language queries | Service | - | No |
| `ai/service/ReceiptAssistService.kt` | ReceiptAssistService | Assists receipt extraction | Service | - | No |
| `ai/service/ReceiptItemCategorizationService.kt` | ReceiptItemCategorizationService | Categorizes receipt line items | Service | - | No |
| `ai/service/ReviewExplanationService.kt` | ReviewExplanationService | Explains why items need review | Service | - | No |
| `ai/service/ReviewPriorityScorer.kt` | ReviewPriorityScorer | Scores review priority | Service | - | No |
| `ai/service/SemanticDuplicateDetector.kt` | SemanticDuplicateDetector | Detects semantic duplicates | Service | - | No |
| `ai/usecase/AiArtifactFreshness.kt` | AiArtifactFreshness | AI artifact freshness checks | UseCase | AiArtifactRepository | No |
| `ai/usecase/CategorizationAssistInputBuilder.kt` | CategorizationAssistInputBuilder | Builds categorization assist inputs | UseCase | - | No |
| `ai/usecase/CategorizeReceiptItemsUseCase.kt` | CategorizeReceiptItemsUseCase | Categorizes receipt items | UseCase | ReceiptItemCategorizationService | No |
| `ai/usecase/DashboardBriefingInputBuilder.kt` | DashboardBriefingInputBuilder | Builds briefing inputs | UseCase | - | No |
| `ai/usecase/DedupeJudgeInputBuilder.kt` | DedupeJudgeInputBuilder | Builds dedupe judge inputs | UseCase | - | No |
| `ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt` | DeliverProactiveBriefingNotificationUseCase | Delivers proactive briefing | UseCase | DashboardBriefingService, AiArtifactRepository | No |
| `ai/usecase/DetectSemanticDuplicateUseCase.kt` | DetectSemanticDuplicateUseCase | Detects semantic duplicates | UseCase | SemanticDuplicateDetector | No |
| `ai/usecase/ExecuteFinancialQueryUseCase.kt` | ExecuteFinancialQueryUseCase | Executes financial queries | UseCase | QueryInterpretationService | No |
| `ai/usecase/ExplainPendingReviewUseCase.kt` | ExplainPendingReviewUseCase | Explains pending review items | UseCase | ReviewExplanationService | No |
| `ai/usecase/FinancialQueryInterpretationInputBuilder.kt` | FinancialQueryInterpretationInputBuilder | Builds query inputs | UseCase | - | No |
| `ai/usecase/GenerateDashboardBriefingUseCase.kt` | GenerateDashboardBriefingUseCase | Generates dashboard briefings | UseCase | DashboardBriefingService | No |
| `ai/usecase/GenerateTransactionInsightUseCase.kt` | GenerateTransactionInsightUseCase | Generates transaction insights | UseCase | - | No |
| `ai/usecase/GetAiRuntimeStatusUseCase.kt` | GetAiRuntimeStatusUseCase | Gets AI runtime status | UseCase | AiEnvironmentMonitor | No |
| `ai/usecase/InterpretFinancialQueryUseCase.kt` | InterpretFinancialQueryUseCase | Interprets financial queries | UseCase | QueryInterpretationService | No |
| `ai/usecase/JudgePendingReviewDuplicateUseCase.kt` | JudgePendingReviewDuplicateUseCase | Judges review duplicates | UseCase | DedupeJudgeService | No |
| `ai/usecase/MapFinancialQueryToNavigationUseCase.kt` | MapFinancialQueryToNavigationUseCase | Maps queries to UI navigation | UseCase | - | No |
| `ai/usecase/PrioritizeReviewItemsUseCase.kt` | PrioritizeReviewItemsUseCase | Prioritizes review items | UseCase | ReviewPriorityScorer | No |
| `ai/usecase/ReceiptAssistInputBuilder.kt` | ReceiptAssistInputBuilder | Builds receipt assist inputs | UseCase | - | No |
| `ai/usecase/ReceiptItemCategorizationInputBuilder.kt` | ReceiptItemCategorizationInputBuilder | Builds item categorization inputs | UseCase | - | No |
| `ai/usecase/ReviewExplanationInputBuilder.kt` | ReviewExplanationInputBuilder | Builds review explanation inputs | UseCase | - | No |
| `ai/usecase/SuggestCategoryFallbackUseCase.kt` | SuggestCategoryFallbackUseCase | Fallback category suggestion | UseCase | - | No |
| `ai/usecase/SuggestReceiptExtractionUseCase.kt` | SuggestReceiptExtractionUseCase | Suggests receipt extraction | UseCase | ReceiptAssistService | No |
| `ai/usecase/SyncProactiveBriefingWorkUseCase.kt` | SyncProactiveBriefingWorkUseCase | Syncs briefing work schedules | UseCase | AiWorkScheduler | No |
| `ai/usecase/TransactionInsightInputBuilder.kt` | TransactionInsightInputBuilder | Builds transaction insight inputs | UseCase | - | No |
| `ai/usecase/ValidateBankStatementTransactionsUseCase.kt` | ValidateBankStatementTransactionsUseCase | Validates bank statement transactions | UseCase | - | No |

### Alerts & Anomalies (2 files)

**Location:** `com.yourname.expensetracker.domain.alerts`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `alerts/AnomalyAlertRepository.kt` | AnomalyAlertRepository | Anomaly alert domain interface | Repository | - | No |
| `alerts/AnomalyAlertOrchestrator.kt` | AnomalyAlertOrchestrator | Coordinates anomaly alerting | Engine | AnomalyAlertRepository | No |

### Analytics & Insights (16 files)

**Location:** `com.yourname.expensetracker.domain.analytics`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `analytics/AdvancedAnalyticsDashboard.kt` | AdvancedAnalyticsDashboard | Advanced analytics dashboard logic | Engine | - | No |
| `analytics/AdvancedAnalyticsEngine.kt` | AdvancedAnalyticsEngine | Advanced analytics computation | Engine | - | No |
| `analytics/AdvancedAnalyticsModels.kt` | AdvancedAnalyticsModels | Advanced analytics data models | Model | - | No |
| `analytics/AnalyticsModels.kt` | AnalyticsModels | Core analytics models | Model | - | No |
| `analytics/AnomalyDetector.kt` | AnomalyDetector | Detects spending anomalies | Engine | - | No |
| `analytics/CategoryInsightEngine.kt` | CategoryInsightEngine | Generates category insights | Engine | - | No |
| `analytics/DayOfWeekAnalyzer.kt` | DayOfWeekAnalyzer | Analyzes spending by day of week | Engine | - | No |
| `analytics/InsightsEngine.kt` | InsightsEngine | Central insights generation | Engine | - | No |
| `analytics/MerchantInsightEngine.kt` | MerchantInsightEngine | Merchant-specific insights | Engine | - | No |
| `analytics/MonthlyComparisonCalculator.kt` | MonthlyComparisonCalculator | Calculates monthly comparisons | Engine | - | No |
| `analytics/SpendingPaceCalculator.kt` | SpendingPaceCalculator | Calculates spending pace | Engine | - | No |
| `analytics/SpendingPersonalityClassifier.kt` | SpendingPersonalityClassifier | Classifies spending personality | Engine | - | No |
| `analytics/SpendingThresholdCalculator.kt` | SpendingThresholdCalculator | Calculates spending thresholds | Engine | - | No |
| `analytics/TotalsAggregationEngine.kt` | TotalsAggregationEngine | Aggregates financial totals | Engine | - | No |
| `analytics/TransferDirectionAnalytics.kt` | TransferDirectionAnalytics | Analyzes transfer directions | Engine | - | No |
| `analytics/NormalizedAnalyticsInput.kt` | NormalizedAnalyticsInput | Currency-normalized analytics input | Model | - | No |
| `analytics/AnalyticsInputAssembler.kt` | AnalyticsInputAssembler | Assembles analytics inputs | Engine | - | No |

### Backup & Export (7 files)

**Location:** `com.yourname.expensetracker.domain.backup`, `export`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `backup/DatabaseBackupRepository.kt` | DatabaseBackupRepository | Database backup interface | Repository | - | No |
| `backup/DatabaseOperationResults.kt` | DatabaseOperationResults | Backup operation result models | Model | - | No |
| `export/AccountingExporters.kt` | AccountingExporters | Accounting system exporters | Service | - | No |
| `export/ExportTransaction.kt` | ExportTransaction | Transaction export models | Model | - | No |

### Bank API Integration (2 files)

**Location:** `com.yourname.expensetracker.domain.bank`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `bank/BankApiConfig.kt` | BankApiConfig | Bank API configuration | Config | - | No |
| `bank/BankApiIntegration.kt` | BankApiIntegration | Bank API integration logic | Service | BankApiConfig | No |

### Budget Management (9 files)

**Location:** `com.yourname.expensetracker.domain.budget`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `budget/BudgetAutopilotEngine.kt` | BudgetAutopilotEngine | Auto-adjusts budgets | Engine | - | No |
| `budget/BudgetCalculator.kt` | BudgetCalculator | Budget calculations | Engine | - | No |
| `budget/BudgetForecastingEngine.kt` | BudgetForecastingEngine | Forecasts budget impact | Engine | - | No |
| `budget/BudgetModels.kt` | BudgetModels | Budget data models | Model | - | No |
| `budget/BudgetMonitor.kt` | BudgetMonitor | Monitors budget status | Engine | - | No |
| `budget/BudgetRecommendationEngine.kt` | BudgetRecommendationEngine | Recommends budget adjustments | Engine | - | No |
| `budget/BudgetRecommendationInputs.kt` | BudgetRecommendationInputs | Budget recommendation inputs | Model | - | No |
| `budget/SharedBudgetManager.kt` | SharedBudgetManager | Manages shared budgets | Engine | - | No |

### Business Expense Reporting (1 file)

**Location:** `com.yourname.expensetracker.domain.business`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `business/BusinessExpenseReportGenerator.kt` | BusinessExpenseReportGenerator | Business expense reporting | Engine | - | No |

### Carbon Footprint (1 file)

**Location:** `com.yourname.expensetracker.domain.carbon`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `carbon/CarbonFootprintCalculator.kt` | CarbonFootprintCalculator | Calculates carbon footprint | Engine | - | No |

### Cash Flow (1 file)

**Location:** `com.yourname.expensetracker.domain.cashflow`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `cashflow/CashFlowCalculator.kt` | CashFlowCalculator | Cash flow calculations | Engine | - | No |

### Categorization Engine (7 files)

**Location:** `com.yourname.expensetracker.domain.categorization`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `categorization/CategorizationEngine.kt` | CategorizationEngine | Core categorization logic | Engine | CategoryKeywords, ContextualInferenceEngine | No |
| `categorization/CategoryKeywords.kt` | CategoryKeywords | Category keyword database | Service | - | No |
| `categorization/ContextualInferenceEngine.kt` | ContextualInferenceEngine | Context-aware categorization | Engine | - | No |
| `categorization/GreeklishNormalizer.kt` | GreeklishNormalizer | Greek to Latin text conversion | Utility | - | No |
| `categorization/MerchantCanonicalizer.kt` | MerchantCanonicalizer | Standardizes merchant names | Engine | - | No |
| `categorization/SemanticKeywordMatcher.kt` | SemanticKeywordMatcher | Semantic keyword matching | Engine | - | No |

### Challenge, Config, Currency (10 files)

**Location:** Various domain subsystems

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `challenge/SpendingChallengeManager.kt` | SpendingChallengeManager | Manages spending challenges | Engine | - | No |
| `config/AppConfig.kt` | AppConfig | Application configuration | Config | - | No |
| `currency/CurrencyConverter.kt` | CurrencyConverter | Currency conversion logic | Engine | - | No |
| `currency/CurrencyRatesRepository.kt` | CurrencyRatesRepository | Exchange rates interface | Repository | - | No |
| `currency/CurrencySettingsRepository.kt` | CurrencySettingsRepository | Currency settings interface | Repository | - | No |
| `currency/ExchangeRateContracts.kt` | ExchangeRateContracts | Exchange rate interfaces | Service | - | No |

### Core Money Types (9 files)

**Location:** `com.yourname.expensetracker.domain.core.money`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `core/money/CurrencyCode.kt` | CurrencyCode | Type-safe ISO 4217 wrapper | Model | - | No |
| `core/money/MoneyAmount.kt` | MoneyAmount | Amount + currency pair with safe arithmetic | Model | CurrencyCode | No |
| `core/money/ConvertedMoney.kt` | ConvertedMoney | Conversion trace with rate metadata | Model | MoneyAmount | No |
| `core/money/MoneyBucket.kt` | MoneyBucket | Per-currency subtotal bucket | Model | CurrencyCode | No |
| `core/money/MoneyAggregate.kt` | MoneyAggregate | Primary aggregation return type | Model | MoneyBucket | No |
| `core/money/ConversionFailure.kt` | ConversionFailure | Failed conversion record | Model | CurrencyCode | No |
| `core/money/CurrencyAssumption.kt` | CurrencyAssumption | Why a currency was assigned | Enum | - | No |
| `core/money/MoneyMappers.kt` | MoneyMappers | Bridge legacy to new money types | Utility | MoneyAmount | No |
| `core/money/MoneyFormatUtils.kt` | MoneyFormatUtils | Money formatting extensions | Utility | CurrencyCode | No |
| `core/money/MoneyAggregateBuilder.kt` | MoneyAggregateBuilder | Builder for MoneyAggregate | Utility | MoneyAggregate | No |

### Core Time Types (2 files)

**Location:** `com.yourname.expensetracker.domain.core.time`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `core/time/PeriodRange.kt` | PeriodRange | Typed half-open period | Model | - | No |
| `core/time/PeriodKind.kt` | PeriodKind | Semantic period enum | Enum | - | No |

### Core Validation (1 file)

**Location:** `com.yourname.expensetracker.domain.core.validation`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `core/validation/EntityTimeValidation.kt` | EntityTimeValidation | Validates entity time fields | Utility | - | No |

### Dashboard & Engine (3 files)

**Location:** `com.yourname.expensetracker.domain.engine`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `engine/DashboardFollowThroughEngine.kt` | DashboardFollowThroughEngine | Generates dashboard recommendations | Engine | - | No |

### Data Models (24 files)

**Location:** `com.yourname.expensetracker.domain.model`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `model/BlockPartyDay.kt` | BlockPartyDay | Block party day models | Model | - | No |
| `model/CategoryBreakdown.kt` | CategoryBreakdown | Category spending breakdown | Model | - | No |
| `model/CategoryInfo.kt` | CategoryInfo | Category information models | Model | - | No |
| `model/FinancialForecast.kt` | FinancialForecast | Financial forecast models | Model | - | No |
| `model/PeriodDrillDownState.kt` | PeriodDrillDownState | Period drill-down state | Model | - | No |
| `model/PeriodRange.kt` | PeriodRange | Time period range | Model | - | No |
| `model/PeriodTotal.kt` | PeriodTotal | Period total models | Model | - | No |
| `model/PlannedExpense.kt` | PlannedExpense | Planned expense models | Model | - | No |
| `model/RecurringPattern.kt` | RecurringPattern | Recurring transaction pattern | Model | - | No |
| `model/SavingsGoal.kt` | SavingsGoal | Savings goal models | Model | - | No |
| `model/UiText.kt` | UiText | UI text localization | Model | - | No |
| `model/UpcomingItem.kt` | UpcomingItem | Upcoming transaction models | Model | - | No |
| `model/budget/MonteCarloBudgetImpact.kt` | MonteCarloBudgetImpact | Budget impact models | Model | - | No |
| `model/dashboard/BudgetStatusSnapshot.kt` | BudgetStatusSnapshot | Budget status snapshot | Model | - | No |
| `model/dashboard/DashboardCategoryBreakdown.kt` | DashboardCategoryBreakdown | Dashboard category data | Model | - | No |
| `model/dashboard/DashboardExpenseMapper.kt` | DashboardExpenseMapper | Maps expenses for dashboard | Mapper | - | No |
| `model/dashboard/DashboardPrimitives.kt` | DashboardPrimitives | Dashboard UI primitives | Model | - | No |
| `model/dashboard/DomainBlockStatus.kt` | DomainBlockStatus | Block status models | Model | - | No |
| `model/dashboard/DomainDayBudgetStatus.kt` | DomainDayBudgetStatus | Daily budget status | Model | - | No |
| `model/dashboard/FinancialWeather.kt` | FinancialWeather | Financial weather metaphors | Model | - | No |
| `model/dashboard/SpendingSummary.kt` | SpendingSummary | Spending summary models | Model | - | No |
| `model/navigation/DomainTransactionFilter.kt` | DomainTransactionFilter | Transaction filter models | Model | - | No |
| `model/recommendation/DashboardFollowThroughRecommendation.kt` | DashboardFollowThroughRecommendation | Dashboard recommendations | Model | - | No |
| `model/recommendation/RecommendationPriority.kt` | RecommendationPriority | Recommendation priority enum | Enum | - | No |
| `model/recommendation/RecommendationStatus.kt` | RecommendationStatus | Recommendation status enum | Enum | - | No |

### Debug Utilities (6 files)

**Location:** `com.yourname.expensetracker.domain.debug`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `debug/AiRuntimeDiagnostics.kt` | AiRuntimeDiagnostics | Diagnoses AI runtime issues | Utility | - | No |
| `debug/DebugData.kt` | DebugData | Debug data models | Model | - | No |
| `debug/DebugIssue.kt` | DebugIssue | Issue reporting models | Model | - | No |
| `debug/DebugIssueDetector.kt` | DebugIssueDetector | Detects and reports issues | Engine | - | No |
| `debug/NotificationSeeder.kt` | NotificationSeeder | Seeds test notifications | Utility | - | No |
| `debug/ServiceDiagnostics.kt` | ServiceDiagnostics | Service diagnostics | Utility | - | No |

### Diagnostics (1 file)

**Location:** `com.yourname.expensetracker.domain.diagnostics`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `diagnostics/DatabaseIntegrityScanner.kt` | DatabaseIntegrityScanner | Scans DB integrity | Engine | - | No |

### DTOs (4 files)

**Location:** `com.yourname.expensetracker.domain.dto`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `dto/AiArtifactRecord.kt` | AiArtifactRecord | AI artifact record DTO | Model | - | No |
| `dto/CategoryRef.kt` | CategoryRef | Category reference DTO | Model | - | No |
| `dto/ReceiptItemCategorizationSnapshot.kt` | ReceiptItemCategorizationSnapshot | Categorization snapshot DTO | Model | - | No |
| `dto/ReviewPriorityInput.kt` | ReviewPriorityInput | Review priority input DTO | Model | - | No |

### Forecasting & Financial (8 files)

**Location:** `com.yourname.expensetracker.domain.forecasting`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `forecasting/DataQualityAssessor.kt` | DataQualityAssessor | Assesses historical data quality | Engine | - | No |
| `forecasting/FinancialStressForecastEngine.kt` | FinancialStressForecastEngine | Stress tests financial scenarios | Engine | - | No |
| `forecasting/HistoricalSpendingDistribution.kt` | HistoricalSpendingDistribution | Models spending distribution | Model | - | No |
| `forecasting/MonteCarloResult.kt` | MonteCarloResult | Monte Carlo simulation results | Model | - | No |
| `forecasting/MonteCarloSpendingSimulator.kt` | MonteCarloSpendingSimulator | Simulates spending scenarios | Engine | - | No |

### Groups & Shared Expenses (11 files)

**Location:** `com.yourname.expensetracker.domain.groups`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `groups/GroupTransactionCoordinator.kt` | GroupTransactionCoordinator | Coordinates group transactions | Engine | - | No |
| `groups/SettlementCalculator.kt` | SettlementCalculator | Calculates settlement amounts | Engine | - | No |
| `groups/SharedExpenseBudgetOffsetEngine.kt` | SharedExpenseBudgetOffsetEngine | Budget impact for shared expenses | Engine | - | No |
| `groups/SharedExpenseManager.kt` | SharedExpenseManager | Manages shared expenses | Engine | - | No |
| `groups/SharedExpensePort.kt` | SharedExpensePort | Port for shared expenses | Service | - | No |
| `groups/GroupValidationError.kt` | GroupValidationError | Group validation error models | Model | - | No |
| `groups/Result.kt` | Result (copied) | Result wrapper for groups | Model | - | No |
| `groups/usecase/AddGroupExpenseUseCase.kt` | AddGroupExpenseUseCase | Adds group expenses | UseCase | - | No |
| `groups/usecase/AddGroupMemberUseCase.kt` | AddGroupMemberUseCase | Adds group members | UseCase | - | No |
| `groups/usecase/DeleteGroupMemberUseCase.kt` | DeleteGroupMemberUseCase | Deletes group members | UseCase | - | No |
| `groups/usecase/DeleteGroupUseCase.kt` | DeleteGroupUseCase | Deletes groups | UseCase | - | No |

### Health & Income (3 files)

**Location:** `com.yourname.expensetracker.domain.health`, `income`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `health/FinancialHealthCalculator.kt` | FinancialHealthCalculator | Calculates financial health score | Engine | - | No |
| `health/FinancialHealthScoreV2.kt` | FinancialHealthScoreV2 | Health score models v2 | Model | - | No |
| `income/RecurringIncomeTracker.kt` | RecurringIncomeTracker | Tracks recurring income | Engine | - | No |

### Intelligence/ML (8 files)

**Location:** `com.yourname.expensetracker.domain.intelligence`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `intelligence/ConfidenceRouter.kt` | ConfidenceRouter | Routes based on confidence scores | Engine | - | No |
| `intelligence/CrossSourceDeduplication.kt` | CrossSourceDeduplication | Deduplicates cross-source data | Engine | - | No |
| `intelligence/TransactionClassifier.kt` | TransactionClassifier | Classifies transactions | Engine | - | No |
| `intelligence/ml/ExpenseCategoryClassifier.kt` | ExpenseCategoryClassifier | ML-based category classifier | Engine | - | No |
| `intelligence/ml/ExpenseClassifier.kt` | ExpenseClassifier | ML expense classification | Engine | - | No |
| `intelligence/ml/FeatureExtractor.kt` | FeatureExtractor | Extracts ML features | Engine | - | No |
| `intelligence/ml/HybridExpenseClassifier.kt` | HybridExpenseClassifier | Hybrid classification | Engine | - | No |
| `intelligence/ml/MerchantNormalizer.kt` | MerchantNormalizer | Normalizes merchant names | Utility | - | No |

### Investment & Lifestyle (3 files)

**Location:** `com.yourname.expensetracker.domain.investment`, `lifestyle`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `investment/InvestmentTracker.kt` | InvestmentTracker | Tracks investments | Engine | - | No |
| `lifestyle/LifestyleInflationDetector.kt` | LifestyleInflationDetector | Detects lifestyle inflation | Engine | - | No |

### Location Services (12 files)

**Location:** `com.yourname.expensetracker.domain.location`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `location/AreaSpendingEngine.kt` | AreaSpendingEngine | Analyzes spending by area | Engine | - | No |
| `location/GeocodingResult.kt` | GeocodingResult | Geocoding result models | Model | - | No |
| `location/LocatedExpense.kt` | LocatedExpense | Expense with location data | Model | - | No |
| `location/LocatedMoneyExpense.kt` | LocatedMoneyExpense | Currency-aware located expense | Model | - | No |
| `location/LocationInsightsEngine.kt` | LocationInsightsEngine | Location-based insights | Engine | - | No |
| `location/LocationModels.kt` | LocationModels | Location data models | Model | - | No |
| `location/LocationResolver.kt` | LocationResolver | Resolves merchant locations | Engine | - | No |
| `location/LocationResolverPorts.kt` | LocationResolverPorts | Location resolver ports/interfaces | Service | - | No |
| `location/NearbyPoi.kt` | NearbyPoi | Nearby point of interest | Model | - | No |
| `location/SpendingHeatmapEngine.kt` | SpendingHeatmapEngine | Generates heatmaps | Engine | - | No |
| `location/TravelDetectionEngine.kt` | TravelDetectionEngine | Detects travel patterns | Engine | - | No |

### Logic & Business Rules (7 files)

**Location:** `com.yourname.expensetracker.domain.logic`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `logic/CustomSplitParser.kt` | CustomSplitParser | Parses custom expense splits | Parser | - | No |
| `logic/NarrativeGenerator.kt` | NarrativeGenerator | Generates expense narratives | Engine | - | No |
| `logic/RecurrenceCalculator.kt` | RecurrenceCalculator | Calculates recurrence patterns | Engine | - | No |
| `logic/RecurringExpenseEngine.kt` | RecurringExpenseEngine | Manages recurring expenses | Engine | - | No |
| `logic/SplitCalculator.kt` | SplitCalculator | Calculates expense splits | Engine | - | No |
| `logic/SynthesisEngine.kt` | SynthesisEngine | Synthesizes expense data | Engine | - | No |

### Natural Language (3 files)

**Location:** `com.yourname.expensetracker.domain.naturallanguage`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `naturallanguage/NaturalLanguageExpenseQueryRepository.kt` | NaturalLanguageExpenseQueryRepository | NL query interface | Repository | - | No |
| `naturallanguage/NaturalLanguageSearchEngine.kt` | NaturalLanguageSearchEngine | NL search logic | Engine | - | No |
| `naturallanguage/SpeechInputGateway.kt` | SpeechInputGateway | Speech input interface | Service | - | No |

### Other Domain Services (8 files)

**Location:** Various domain subsystems

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `negotiation/SmartBillNegotiationEngine.kt` | SmartBillNegotiationEngine | Bill negotiation logic | Engine | - | No |
| `price/PriceProtectionTracker.kt` | PriceProtectionTracker | Tracks price protection | Engine | - | No |
| `service/NotificationService.kt` | NotificationService | Notification interface | Service | - | No |
| `split/EnhancedSplitManager.kt` | EnhancedSplitManager | Enhanced split management | Engine | - | No |
| `subscription/NotificationSubscriptionDetector.kt` | NotificationSubscriptionDetector | Detects subscriptions | Engine | - | No |
| `subscription/SubscriptionManagerEngine.kt` | SubscriptionManagerEngine | Subscription management | Engine | - | No |

### Parsing & Receipt (18 files)

**Location:** `com.yourname.expensetracker.domain.parser`, `receipt`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `parser/AppParserRegistry.kt` | AppParserRegistry | Registry of parsers | Registry | - | No |
| `parser/GenericTransactionParser.kt` | GenericTransactionParser | Generic parsing logic | Parser | - | No |
| `parser/ParsedTransactionEnums.kt` | ParsedTransactionEnums | Parsed transaction enums | Enum | - | No |
| `parser/TransferDirectionDetector.kt` | TransferDirectionDetector | Detects transfer direction | Engine | - | No |
| `parser/parsers/GoogleWalletParser.kt` | GoogleWalletParser | Google Wallet parser | Parser | - | No |
| `parser/parsers/GreekBankParser.kt` | GreekBankParser | Greek bank parser | Parser | - | No |
| `parser/parsers/RevolutParser.kt` | RevolutParser | Revolut parser | Parser | - | No |
| `parser/parsers/SmsParser.kt` | SmsParser | SMS parser | Parser | - | No |
| `receipt/BankStatementParser.kt` | BankStatementParser | Bank statement parser | Parser | - | No |
| `receipt/EnhancedMerchantExtractor.kt` | EnhancedMerchantExtractor | Merchant extraction | Engine | - | No |
| `receipt/MerchantRulesPolicy.kt` | MerchantRulesPolicy | Merchant business rules | Service | - | No |
| `receipt/OcrLanguageProcessor.kt` | OcrLanguageProcessor | OCR language processing | Engine | - | No |
| `receipt/OcrPreprocessingPipeline.kt` | OcrPreprocessingPipeline | OCR preprocessing | Engine | - | No |
| `receipt/ReceiptOcrService.kt` | ReceiptOcrService | OCR interface | Service | - | No |
| `receipt/ReceiptParser.kt` | ReceiptParser | Receipt parsing interface | Service | - | No |
| `receipt/ReceiptSource.kt` | ReceiptSource | Receipt source enum | Enum | - | No |
| `receipt/WarrantyTextExtractor.kt` | WarrantyTextExtractor | Extracts warranty text | Engine | - | No |
| `receiptmatching/ReceiptTransactionMatcher.kt` | ReceiptTransactionMatcher | Matches receipts to transactions | Engine | - | No |

### Privacy & Data Protection (12 files)

**Location:** `com.yourname.expensetracker.domain.privacy`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `privacy/PrivacyCapability.kt` | PrivacyCapability | Enum of 21 gated capabilities | Enum | - | No |
| `privacy/PrivacyGate.kt` | PrivacyGate | Gate check interface | Service | - | No |
| `privacy/PrivacyDecision.kt` | PrivacyDecision | Allowed/Denied sealed interface | Model | - | No |
| `privacy/PrivacySettings.kt` | PrivacySettings | 10 toggle + 2 retention settings | Model | - | No |
| `privacy/PrivacySettingsRepository.kt` | PrivacySettingsRepository | Settings read/write interface | Repository | - | No |
| `privacy/PrivacyAuditLogger.kt` | PrivacyAuditLogger | Gate check audit logging | Service | - | No |
| `privacy/NotificationPrivacyGate.kt` | NotificationPrivacyGate | Notification capture gate | Service | PrivacyGate | No |
| `privacy/CloudAiPrivacyGate.kt` | CloudAiPrivacyGate | Cloud AI capability gate | Service | PrivacyGate | No |
| `privacy/LocationPrivacyGate.kt` | LocationPrivacyGate | Location capability gate | Service | PrivacyGate | No |
| `privacy/BackupPrivacyGate.kt` | BackupPrivacyGate | Backup capability gate | Service | PrivacyGate | No |
| `privacy/CompositePrivacyGate.kt` | CompositePrivacyGate | Chains all sub-gates | Service | All sub-gates | No |
| `privacy/RedactionSanitizer.kt` | RedactionSanitizer | PII redaction utility | Utility | - | No |

### Receipt Lifecycle (8 files)

**Location:** `com.yourname.expensetracker.domain.receipt.lifecycle`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | ReceiptLifecycleCoordinator | Single entry for ALL receipt processing | Engine | - | No |
| `receipt/lifecycle/ReceiptMatchLifecycleService.kt` | ReceiptMatchLifecycleService | Lifecycle-aware receipt match mutations + events (P3) | Service | AppDatabase, ScannedReceiptDao, ReceiptEventDao, DatabaseWriteBarrier, TimeProvider | No |
| `receipt/lifecycle/ReceiptLinkService.kt` | ReceiptLinkService | Centralized receipt-expense linking | Service | - | No |
| `receipt/lifecycle/ReceiptAssetStore.kt` | ReceiptAssetStore | File persistence, hashing, backup | Service | - | No |
| `receipt/lifecycle/ReceiptInputValidator.kt` | ReceiptInputValidator | URI/MIME/size validation | Service | - | No |
| `receipt/lifecycle/ReceiptDuplicateDetector.kt` | ReceiptDuplicateDetector | 3-signal dedup engine | Engine | - | No |
| `receipt/lifecycle/ReceiptSideEffectDispatcher.kt` | ReceiptSideEffectDispatcher | Document-type-gated side effects | Engine | - | No |
| `receipt/lifecycle/BankStatementLifecycleProcessor.kt` | BankStatementLifecycleProcessor | Statement-specific processing | Engine | - | No |

### Recurring Expenses (14 files)

**Location:** `com.yourname.expensetracker.domain.recurring`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `recurring/RecurringOccurrenceExpander.kt` | RecurringOccurrenceExpander | Expands recurrence rules to occurrences | Engine | - | No |
| `recurring/OccurrenceConflictResolver.kt` | OccurrenceConflictResolver | Resolves candidates vs actuals | Engine | - | No |
| `recurring/RecurringPlanProjectionService.kt` | RecurringPlanProjectionService | Materialises PlannedExpense rows | Service | - | No |
| `recurring/lifecycle/RecurringLifecycleCoordinator.kt` | RecurringLifecycleCoordinator | Primary entry for occurrence generation | Engine | - | No |
| `recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` | RecurringRuleLifecycleCoordinator | Single writer for rule CRUD lifecycle (P4) | Engine | AppDatabase, writeBarrier, expander, resolver, materializer, eventWriter | No |
| `recurring/lifecycle/RecurringOccurrenceMaterializer.kt` | RecurringOccurrenceMaterializer | Persists occurrences + reminders | Engine | - | No |
| `recurring/lifecycle/OccurrenceGenerationOptions.kt` | OccurrenceGenerationOptions | Controls reminder creation during generation (P4) | Data | - | No |
| `recurring/lifecycle/RecurringExpenseReconcileResult.kt` | RecurringExpenseReconcileResult | Sealed result for link/unlink ops (P4) | Model | - | No |
| `recurring/lifecycle/RecurringOccurrenceStatus.kt` | RecurringOccurrenceStatus | Typed enum + transition policy (P4) | Enum | - | No |

### Reminder Management (3 files)

**Location:** `com.yourname.expensetracker.domain.reminder`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `reminder/BillReminderManager.kt` | BillReminderManager | Bill reminder management (deprecated) | Engine | - | No |
| `reminder/BillReminderSettings.kt` | BillReminderSettings | Runtime reminder dispatch config (P4) | Data | - | No |
| `reminder/BillReminderSettingsRepository.kt` | BillReminderSettingsRepository | Interface for reminder settings (P4) | Interface | - | No |

### Savings (4 files)

**Location:** `com.yourname.expensetracker.domain.savings`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `savings/AutomatedSavingsRuleEngine.kt` | AutomatedSavingsRuleEngine | Automated savings rules | Engine | - | No |
| `savings/SavingsGamificationEngine.kt` | SavingsGamificationEngine | Gamification for savings | Engine | - | No |
| `savings/SavingsGoalRepository.kt` | SavingsGoalRepository | Savings goal interface | Repository | - | No |
| `savings/SmartSavingsEngine.kt` | SmartSavingsEngine | Smart savings logic | Engine | - | No |

### Tax (2 files)

**Location:** `com.yourname.expensetracker.domain.tax`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `tax/TaxConfiguration.kt` | TaxConfiguration | Tax configuration | Config | - | No |
| `tax/TaxEstimator.kt` | TaxEstimator | Tax estimation logic | Engine | - | No |

### Text & UI Keys (2 files)

**Location:** `com.yourname.expensetracker.domain.text`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `text/DashboardTextKeys.kt` | DashboardTextKeys | Dashboard text keys | Config | - | No |
| `text/DomainTextKeys.kt` | DomainTextKeys | Domain text keys | Config | - | No |

### Transaction Lifecycle (9 files)

**Location:** `com.yourname.expensetracker.domain.transaction`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `transaction/ExpenseSource.kt` | ExpenseSource | 14-value expense origin enum | Enum | - | No |
| `transaction/LifecycleEventType.kt` | LifecycleEventType | 14-value lifecycle event enum | Enum | - | No |
| `transaction/DeduplicationMode.kt` | DeduplicationMode | Dedup strategy enum | Enum | - | No |
| `transaction/CreateExpenseRequest.kt` | CreateExpenseRequest | Source-neutral creation request (40+ fields) | Model | - | No |
| `transaction/CreateExpenseResult.kt` | CreateExpenseResult | Sealed result (Created, DuplicateSkipped, etc.) | Model | - | No |
| `transaction/ExpenseUpdates.kt` | ExpenseUpdates | Patch-style update model | Model | - | No |
| `transaction/SideEffectMode.kt` | SideEffectMode | IMMEDIATE/DEFER enum | Enum | - | No |
| `transaction/lifecycle/TransactionLifecycleCoordinator.kt` | TransactionLifecycleCoordinator | Single entry point for ALL expense CUD | Engine | - | No |
| `transaction/lifecycle/TransactionSideEffectDispatcher.kt` | TransactionSideEffectDispatcher | Post-creation side effects | Engine | - | No |

### Use Cases (41 files)

**Location:** `com.yourname.expensetracker.domain.usecase`

#### AI Use Cases (listed in AI Subsystem section above)

#### Budget Use Cases

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `usecase/budget/CalculateBudgetStatusUseCase.kt` | CalculateBudgetStatusUseCase | Calculates budget status | UseCase | BudgetCalculator | No |
| `usecase/budget/GetMonteCarloBudgetImpactUseCase.kt` | GetMonteCarloBudgetImpactUseCase | Gets budget impact forecast | UseCase | - | No |

#### Dashboard Use Cases

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | ComputeDashboardWidgetsUseCase | Computes dashboard widgets | UseCase | - | No |
| `usecase/dashboard/ComputeMoneyRadarUseCase.kt` | ComputeMoneyRadarUseCase | Computes money radar | UseCase | - | No |
| `usecase/dashboard/DashboardDataProvider.kt` | DashboardDataProvider | Dashboard data provider | Service | - | No |
| `usecase/dashboard/DashboardRepositoryContracts.kt` | DashboardRepositoryContracts | Dashboard repo contracts | Service | - | No |

#### Expense Use Cases

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `usecase/expense/CategorizeExpenseUseCase.kt` | CategorizeExpenseUseCase | Categorizes expenses | UseCase | CategorizationEngine | No |
| `usecase/expense/DetectDuplicateExpenseUseCase.kt` | DetectDuplicateExpenseUseCase | Detects duplicate expenses | UseCase | - | No |
| `usecase/expense/ExpenseUseCases.kt` | ExpenseUseCases | Expense use cases facade | Service | - | No |

#### Forecast Use Cases

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `usecase/forecast/CalculateFinancialForecastUseCase.kt` | CalculateFinancialForecastUseCase | Calculates financial forecast | UseCase | - | No |

#### Receipt Use Cases

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `usecase/receipt/ProcessReceiptUseCase.kt` | ProcessReceiptUseCase | Processes receipts | UseCase | - | No |

#### Savings Use Cases

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `usecase/savings/LifestyleSavingsPromptUseCase.kt` | LifestyleSavingsPromptUseCase | Lifestyle savings prompt | UseCase | - | No |
| `usecase/savings/MonthlySavingsSweepUseCase.kt` | MonthlySavingsSweepUseCase | Monthly savings sweep | UseCase | - | No |

#### Warranty Use Cases

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt` | AutoCreateWarrantyFromReceiptUseCase | Creates warranty from receipt | UseCase | - | No |

### Utilities (24 files)

**Location:** `com.yourname.expensetracker.domain.util`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `util/AmountExtractionUtils.kt` | AmountExtractionUtils | Extracts amounts from text | Utility | - | No |
| `util/AmountUtils.kt` | AmountUtils | Amount utility functions | Utility | - | No |
| `util/AppConstants.kt` | AppConstants | Application constants | Config | - | No |
| `util/BKTree.kt` | BKTree | BK-tree data structure | DataStructure | - | No |
| `util/CommonPatterns.kt` | CommonPatterns | Common regex patterns | Utility | - | No |
| `util/CurrencyFormatter.kt` | CurrencyFormatter | Currency formatting | Utility | - | No |
| `util/CurrencyNormalizer.kt` | CurrencyNormalizer | Currency normalization | Utility | - | No |
| `util/DateFormatterUtils.kt` | DateFormatterUtils | Date formatting | Utility | - | No |
| `util/GeoUtils.kt` | GeoUtils | Geolocation utilities | Utility | - | No |
| `util/MerchantCleaner.kt` | MerchantCleaner | Cleans merchant names | Utility | - | No |
| `util/MerchantKeyGenerator.kt` | MerchantKeyGenerator | Generates merchant keys | Utility | - | No |
| `util/Money.kt` | Money | Money value object | Model | - | No |
| `util/NotificationIdGenerator.kt` | NotificationIdGenerator | Generates notification IDs | Utility | - | No |
| `util/StatisticsUtils.kt` | StatisticsUtils | Statistical functions | Utility | - | No |
| `util/StringDistanceUtils.kt` | StringDistanceUtils | String distance algorithms | Utility | - | No |
| `util/SystemTimeProvider.kt` | SystemTimeProvider | System time provider | Service | TimeProvider | No |
| `util/TimePeriodUtils.kt` | TimePeriodUtils | Time period utilities | Utility | - | No |
| `util/TimeProvider.kt` | TimeProvider | Time provider interface | Service | - | No |

### Common Utilities — `domain/common/` (1 file)

**Location:** `com.yourname.expensetracker.domain.common`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `common/Hashing.kt` | *(extension)* | SHA-256 hash prefix utility (`String.sha256Prefix()`) | Utility | - | No |

### Notification Utilities — `domain/notification/` (1 file)

**Location:** `com.yourname.expensetracker.domain.notification`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `notification/RawNotificationFingerprint.kt` | RawNotificationFingerprint | SHA-256 fingerprinting for notification deduplication | Utility | MessageDigest | No |

### Widget (2 files)

**Location:** `com.yourname.expensetracker.domain.widget`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `widget/model/WidgetStyle.kt` | WidgetStyle | Widget style models | Model | - | No |
| `widget/service/WidgetStyleRepository.kt` | WidgetStyleRepository | Widget style interface | Repository | - | No |

### Workers (2 files)

**Location:** `com.yourname.expensetracker.domain.workers`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `workers/WorkerSpec.kt` | WorkerSpec | Worker specification data class | Model | - | No |
| `workers/WorkerSpecScheduler.kt` | WorkerSpecScheduler | Centralized worker scheduling | Service | - | No |

---

## DATA PACKAGE

### Database Layer (109 files)

**Location:** `com.yourname.expensetracker.data.database`

#### Main Database & Coordinator (2 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `database/AppDatabase.kt` | AppDatabase | Room database definition (v141) | Database | All entities, DAOs | No |
| `database/GroupTransactionCoordinator.kt` | GroupTransactionCoordinator | Coordinates group transactions | Engine | GroupExpenseDao, GroupMemberDao | No |

#### Type Converters (1 file)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `database/converter/Converters.kt` | Converters | Room type converters | Converter | - | No |

#### DAOs (58 files)

**Location:** `com.yourname.expensetracker.data.database.dao`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `dao/AiArtifactDao.kt` | AiArtifactDao | AI artifacts DAO | DAO | - | No |
| `dao/AiChatMessageDao.kt` | AiChatMessageDao | Chat messages DAO | DAO | - | No |
| `dao/AiChatSessionDao.kt` | AiChatSessionDao | Chat sessions DAO | DAO | - | No |
| `dao/AnomalyAlertDao.kt` | AnomalyAlertDao | Anomaly alerts DAO | DAO | - | No |
| `dao/BackgroundJobRunDao.kt` | BackgroundJobRunDao | Background job runs DAO | DAO | - | No |
| `dao/BankConnectionDao.kt` | BankConnectionDao | Bank connections DAO | DAO | - | No |
| `dao/BlockedPackageDao.kt` | BlockedPackageDao | Blocked packages DAO | DAO | - | No |
| `dao/BudgetAdjustmentDao.kt` | BudgetAdjustmentDao | Budget adjustments DAO | DAO | - | No |
| `dao/BudgetDao.kt` | BudgetDao | Budgets DAO | DAO | - | No |
| `dao/BudgetForecastDao.kt` | BudgetForecastDao | Budget forecasts DAO | DAO | - | No |
| `dao/CategoryDao.kt` | CategoryDao | Categories DAO | DAO | - | No |
| `dao/EmailReceiptDao.kt` | EmailReceiptDao | Email receipts DAO | DAO | - | No |
| `dao/ExchangeRateDao.kt` | ExchangeRateDao | Exchange rates DAO | DAO | - | No |
| `dao/ExpenseDao.kt` | ExpenseDao | Expenses DAO | DAO | - | Yes |
| `dao/ExpenseGroupDao.kt` | ExpenseGroupDao | Expense groups DAO | DAO | - | No |
| `dao/GroupExpenseDao.kt` | GroupExpenseDao | Group expenses DAO | DAO | - | No |
| `dao/GroupMemberDao.kt` | GroupMemberDao | Group members DAO | DAO | - | No |
| `dao/HealthScoreHistoryDao.kt` | HealthScoreHistoryDao | Health score history DAO | DAO | - | No |
| `dao/InvestmentDao.kt` | InvestmentDao | Investments DAO | DAO | - | No |
| `dao/InvestmentValueDao.kt` | InvestmentValueDao | Investment values DAO | DAO | - | No |
| `dao/ManualRecurringExpenseDao.kt` | ManualRecurringExpenseDao | Manual recurring DAO | DAO | - | No |
| `dao/MerchantCategoryDao.kt` | MerchantCategoryDao | Merchant categories DAO | DAO | - | No |
| `dao/MerchantLocationDao.kt` | MerchantLocationDao | Merchant locations DAO | DAO | - | No |
| `dao/MerchantNormalizationDao.kt` | MerchantNormalizationDao | Merchant normalization DAO | DAO | - | No |
| `dao/MileageTrackingDao.kt` | MileageTrackingDao | Mileage tracking DAO | DAO | - | No |
| `dao/PendingReviewDao.kt` | PendingReviewDao | Pending review DAO | DAO | - | No |
| `dao/PlannedExpenseDao.kt` | PlannedExpenseDao | Planned expenses DAO | DAO | - | No |
| `dao/PrivacyAuditDao.kt` | PrivacyAuditDao | Privacy audit log DAO | DAO | - | No |
| `dao/PromptStateDao.kt` | PromptStateDao | Prompt state DAO | DAO | - | No |
| `dao/RawNotificationDao.kt` | RawNotificationDao | Raw notifications DAO | DAO | - | No |
| `dao/ReceiptEventDao.kt` | ReceiptEventDao | Receipt events DAO | DAO | - | No |
| `dao/ReceiptExpenseLinkDao.kt` | ReceiptExpenseLinkDao | Receipt-expense link DAO | DAO | - | No |
| `dao/ReceiptItemCategorizationDao.kt` | ReceiptItemCategorizationDao | Receipt items DAO | DAO | - | No |
| `dao/RecommendationDao.kt` | RecommendationDao | Recommendations DAO | DAO | - | No |
| `dao/RecurringEventDao.kt` | RecurringLifecycleEventDao | Recurring lifecycle events DAO | DAO | - | No |
| `dao/RecurringExpenseDao.kt` | RecurringExpenseDao | Recurring expenses DAO | DAO | - | No |
| `dao/RecurringOccurrenceDao.kt` | RecurringOccurrenceDao | Recurring occurrences DAO | DAO | - | No |
| `dao/RecurringReminderDeliveryDao.kt` | RecurringReminderDeliveryDao | Recurring reminder delivery DAO | DAO | - | No |
| `dao/ReturnWindowDao.kt` | ReturnWindowDao | Return windows DAO | DAO | - | No |
| `dao/SavingsGoalDao.kt` | SavingsGoalDao | Savings goals DAO | DAO | - | No |
| `dao/SavingsSweepPlanDao.kt` | SavingsSweepPlanDao | Savings sweep plans DAO | DAO | - | No |
| `dao/ScannedReceiptDao.kt` | ScannedReceiptDao | Scanned receipts DAO | DAO | - | No |
| `dao/SourceStatsDao.kt` | SourceStatsDao | Source statistics DAO | DAO | - | No |
| `dao/SourceStatsEventDao.kt` | SourceStatsEventDao | Source stats events DAO | DAO | - | No |
| `dao/SpendingChallengeDao.kt` | SpendingChallengeDao | Spending challenges DAO | DAO | - | No |
| `dao/SpendingPersonalityProfileDao.kt` | SpendingPersonalityProfileDao | Spending personality DAO | DAO | - | No |
| `dao/SplitItemAssignmentDao.kt` | SplitItemAssignmentDao | Split assignments DAO | DAO | - | No |
| `dao/SplitTemplateDao.kt` | SplitTemplateDao | Split templates DAO | DAO | - | No |
| `dao/StressForecastSnapshotDao.kt` | StressForecastSnapshotDao | Stress forecast DAO | DAO | - | No |
| `dao/SubscriptionCandidateDao.kt` | SubscriptionCandidateDao | Subscription candidates DAO | DAO | - | No |
| `dao/SubscriptionPriceHistoryDao.kt` | SubscriptionPriceHistoryDao | Subscription price history DAO | DAO | - | No |
| `dao/SubscriptionUsageDao.kt` | SubscriptionUsageDao | Subscription usage DAO | DAO | - | No |
| `dao/UserCorrectionDao.kt` | UserCorrectionDao | User corrections DAO | DAO | - | No |
| `dao/WarrantyDao.kt` | WarrantyDao | Warranties DAO | DAO | - | No |
| `dao/InvestmentTransactionDao.kt` | InvestmentTransactionDao | Investment transactions DAO | DAO | - | No |
| `dao/WarrantyLifecycleEventDao.kt` | WarrantyLifecycleEventDao | Warranty lifecycle events DAO | DAO | - | No |
| `dao/GroupSettlementDao.kt` | GroupSettlementDao | Group settlement DAO | DAO | - | No |

#### Entities (64 files)

**Location:** `com.yourname.expensetracker.data.database.entity`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `entity/AiArtifactEntity.kt` | AiArtifactEntity | AI artifact entity | Entity | - | No |
| `entity/AiChatMessageEntity.kt` | AiChatMessageEntity | Chat message entity | Entity | - | No |
| `entity/AiChatSessionEntity.kt` | AiChatSessionEntity | Chat session entity | Entity | - | No |
| `entity/AnomalyAlert.kt` | AnomalyAlert | Anomaly alert entity | Entity | - | No |
| `entity/BackgroundJobRun.kt` | BackgroundJobRun | Background job run entity | Entity | - | No |
| `entity/BankConnection.kt` | BankConnection | Bank connection entity | Entity | - | No |
| `entity/BlockedPackage.kt` | BlockedPackage | Blocked package entity | Entity | - | No |
| `entity/Budget.kt` | Budget | Budget entity | Entity | - | No |
| `entity/BudgetAdjustmentRecommendation.kt` | BudgetAdjustmentRecommendation | Budget recommendation entity | Entity | - | No |
| `entity/BudgetForecast.kt` | BudgetForecast | Budget forecast entity | Entity | - | No |
| `entity/Category.kt` | Category | Category entity | Entity | - | No |
| `entity/EmailReceiptSource.kt` | EmailReceiptSource | Email receipt entity | Entity | - | No |
| `entity/ExchangeRate.kt` | ExchangeRate | Exchange rate entity | Entity | - | No |
| `entity/Expense.kt` | Expense | Core expense entity | Entity | - | No |
| `entity/ExpenseGroup.kt` | ExpenseGroup | Expense group entity | Entity | - | No |
| `entity/GroupExpense.kt` | GroupExpense | Group expense entity | Entity | - | No |
| `entity/GroupMember.kt` | GroupMember | Group member entity | Entity | - | No |
| `entity/HealthScoreHistory.kt` | HealthScoreHistory | Health score history entity | Entity | - | No |
| `entity/Investment.kt` | Investment | Investment entity | Entity | - | No |
| `entity/InvestmentValue.kt` | InvestmentValue | Investment value entity | Entity | - | No |
| `entity/ManualRecurringExpense.kt` | ManualRecurringExpense | Manual recurring expense entity | Entity | - | No |
| `entity/MerchantAlias.kt` | MerchantAlias | Merchant alias entity | Entity | - | No |
| `entity/MerchantCanonical.kt` | MerchantCanonical | Canonical merchant entity | Entity | - | No |
| `entity/MerchantCategory.kt` | MerchantCategory | Merchant category entity | Entity | - | No |
| `entity/MerchantLocation.kt` | MerchantLocation | Merchant location entity | Entity | - | No |
| `entity/MerchantLocationCorrection.kt` | MerchantLocationCorrection | Location correction entity | Entity | - | No |
| `entity/MileageTracking.kt` | MileageTracking | Mileage tracking entity | Entity | - | No |
| `entity/PendingReview.kt` | PendingReview | Pending review entity | Entity | - | No |
| `entity/PlannedExpense.kt` | PlannedExpense | Planned expense entity | Entity | - | No |
| `entity/PromptState.kt` | PromptState | Prompt state entity | Entity | - | No |
| `entity/RawNotification.kt` | RawNotification | Raw notification entity | Entity | - | No |
| `entity/ReceiptEvent.kt` | ReceiptEvent | Receipt event entity | Entity | - | No |
| `entity/ReceiptExpenseLink.kt` | ReceiptExpenseLink | Receipt-expense link entity | Entity | - | No |
| `entity/ReceiptItemCategorization.kt` | ReceiptItemCategorization | Receipt item entity | Entity | - | No |
| `entity/RecommendationEntity.kt` | RecommendationEntity | Recommendation entity | Entity | - | No |
| `entity/RecurringLifecycleEvent.kt` | RecurringLifecycleEvent | Recurring lifecycle event entity | Entity | - | No |
| `entity/RecurringOccurrence.kt` | RecurringOccurrence | Recurring occurrence entity | Entity | - | No |
| `entity/RecurringReminderDelivery.kt` | RecurringReminderDelivery | Recurring reminder delivery entity | Entity | - | No |
| `entity/ReturnWindow.kt` | ReturnWindow | Return window entity | Entity | - | No |
| `entity/SavingsGoal.kt` | SavingsGoal | Savings goal entity | Entity | - | No |
| `entity/SavingsSweepPlan.kt` | SavingsSweepPlan | Savings sweep plan entity | Entity | - | No |
| `entity/ScannedReceipt.kt` | ScannedReceipt | Scanned receipt entity | Entity | - | No |
| `entity/SourceStats.kt` | SourceStats | Source statistics entity | Entity | - | No |
| `entity/SourceStatsEvent.kt` | SourceStatsEvent | Source stats event entity | Entity | - | No |
| `entity/SpendingChallengeEntity.kt` | SpendingChallengeEntity | Spending challenge entity | Entity | - | No |
| `entity/SpendingPersonalityProfileEntity.kt` | SpendingPersonalityProfileEntity | Spending profile entity | Entity | - | No |
| `entity/SplitItemAssignment.kt` | SplitItemAssignment | Split item assignment entity | Entity | - | No |
| `entity/SplitTemplate.kt` | SplitTemplate | Split template entity | Entity | - | No |
| `entity/StressForecastSnapshot.kt` | StressForecastSnapshot | Stress forecast entity | Entity | - | No |
| `entity/SubscriptionCandidate.kt` | SubscriptionCandidate | Subscription candidate entity | Entity | - | No |
| `entity/SubscriptionPriceHistory.kt` | SubscriptionPriceHistory | Subscription price entity | Entity | - | No |
| `entity/SubscriptionUsage.kt` | SubscriptionUsage | Subscription usage entity | Entity | - | No |
| `entity/UserCorrection.kt` | UserCorrection | User correction entity | Entity | - | No |
| `entity/Warranty.kt` | Warranty | Warranty entity | Entity | - | No |
| `entity/InvestmentTransaction.kt` | InvestmentTransaction | Investment transaction entity | Entity | - | No |
| `entity/WarrantyLifecycleEvent.kt` | WarrantyLifecycleEvent | Warranty lifecycle event entity | Entity | - | No |
| `entity/GroupSettlementEntity.kt` | GroupSettlementEntity | Group settlement entity | Entity | - | No |

#### Database Models (6 files)

**Location:** `com.yourname.expensetracker.data.database.model`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `model/DashboardWidgetConfig.kt` | DashboardWidgetConfig | Dashboard widget config | Model | - | No |
| `model/ExpenseGroupWithDetails.kt` | ExpenseGroupWithDetails | Expense group with details | Model | - | No |
| `model/ExpenseWithCategory.kt` | ExpenseWithCategory | Expense with category | Model | - | No |
| `model/ExpenseWithCategoryName.kt` | ExpenseWithCategoryName | Expense with category name | Model | - | No |
| `model/ExpenseWithCategory_Extensions.kt` | ExpenseWithCategory_Extensions | Extension functions | Utility | - | No |
| `model/PendingReviewWithReceipt.kt` | PendingReviewWithReceipt | Review with receipt | Model | - | No |

### Repositories (65 files)

**Location:** `com.yourname.expensetracker.data.repository`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `repository/AccountingExportRepository.kt` | AccountingExportRepository | Export to accounting systems | Repository | - | No |
| `repository/AiArtifactRepositoryImpl.kt` | AiArtifactRepositoryImpl | AI artifacts implementation | Repository | AiArtifactDao | No |
| `repository/AiChatRepositoryImpl.kt` | AiChatRepositoryImpl | Chat history implementation | Repository | AiChatSessionDao, AiChatMessageDao | No |
| `repository/AiEngagementRepositoryImpl.kt` | AiEngagementRepositoryImpl | Engagement tracking | Repository | - | No |
| `repository/AiSettingsRepositoryImpl.kt` | AiSettingsRepositoryImpl | AI settings implementation | Repository | - | No |
| `repository/AnalyticsRepository.kt` | AnalyticsRepository | Analytics data access | Repository | ExpenseDao | No |
| `repository/AnomalyAlertRepositoryImpl.kt` | AnomalyAlertRepositoryImpl | Anomaly alert repository impl | Repository | AnomalyAlertDao | No |
| `repository/AutomatedSavingsRuleStateRepository.kt` | AutomatedSavingsRuleStateRepository | Savings rule state persistence | Repository | - | No |
| `repository/BudgetRepository.kt` | BudgetRepository | Budget data access | Repository | BudgetDao, BudgetForecastDao | No |
| `repository/BusinessExpenseRepository.kt` | BusinessExpenseRepository | Business expenses | Repository | ExpenseDao | No |
| `repository/CategoryRepository.kt` | CategoryRepository | Category data access | Repository | CategoryDao | No |
| `repository/CurrencyDataRepository.kt` | CurrencyDataRepository | Currency data access | Repository | - | No |
| `repository/CurrencyRatesRepositoryImpl.kt` | CurrencyRatesRepositoryImpl | Exchange rates implementation | Repository | ExchangeRateDao | No |
| `repository/CurrencySettingsRepositoryImpl.kt` | CurrencySettingsRepositoryImpl | Currency settings implementation | Repository | - | No |
| `repository/DashboardContractsAdapter.kt` | DashboardContractsAdapter | Adapts dashboard contracts | Repository | - | No |
| `repository/DashboardRepository.kt` | DashboardRepository | Dashboard data access | Repository | Multiple DAOs | No |
| `repository/DatabaseBackupRepositoryImpl.kt` | DatabaseBackupRepositoryImpl | Backup implementation | Repository | AppDatabase | No |
| `repository/DeterministicExpenseExportPager.kt` | DeterministicExpenseExportPager | Deterministic paged export | Repository | ExpenseDao | No |
| `repository/ExpenseRepository.kt` | ExpenseRepository | Core expense repository | Repository | ExpenseDao, UserCorrectionDao | No |
| `repository/ExportDataRepository.kt` | ExportDataRepository | Export data access | Repository | Multiple DAOs | No |
| `repository/FinancialWeatherRepository.kt` | FinancialWeatherRepository | Financial weather data | Repository | - | No |
| `repository/GroupsRepository.kt` | GroupsRepository | Groups interface | Repository | - | No |
| `repository/GroupsRepositoryImpl.kt` | GroupsRepositoryImpl | Groups implementation | Repository | GroupExpenseDao, GroupMemberDao | No |
| `repository/LocationResolverPortsAdapters.kt` | LocationResolverPortsAdapters | Location adapters | Repository | - | No |
| `repository/ManualExpenseRepository.kt` | ManualExpenseRepository | Manual expense entry | Repository | ExpenseDao | No |
| `repository/ManualRecurringExpenseRepository.kt` | ManualRecurringExpenseRepository | Recurring expense data | Repository | ManualRecurringExpenseDao | No |
| `repository/MerchantCategoryRepository.kt` | MerchantCategoryRepository | Merchant categories | Repository | MerchantCategoryDao | No |
| `repository/MerchantLocationRepository.kt` | MerchantLocationRepository | Merchant locations | Repository | MerchantLocationDao | No |
| `repository/MerchantNormalizationRepository.kt` | MerchantNormalizationRepository | Merchant normalization | Repository | MerchantNormalizationDao | No |
| `repository/MerchantRulesRepository.kt` | MerchantRulesRepository | Merchant business rules | Repository | - | No |
| `repository/MultiCurrencyRepository.kt` | MultiCurrencyRepository | Multi-currency support | Repository | Multiple DAOs | No |
| `repository/NaturalLanguageExpenseQueryRepositoryImpl.kt` | NaturalLanguageExpenseQueryRepositoryImpl | NL query implementation | Repository | ExpenseDao | No |
| `repository/NotificationProcessingPipeline.kt` | NotificationProcessingPipeline | Notification pipeline | Service | RawNotificationDao | No |
| `repository/NotificationRepository.kt` | NotificationRepository | Notification data access | Repository | RawNotificationDao | No |
| `repository/ParserEnumMappers.kt` | ParserEnumMappers | Parser enum mappings | Utility | - | No |
| `repository/PlannedExpenseRepository.kt` | PlannedExpenseRepository | Planned expenses | Repository | PlannedExpenseDao | No |
| `repository/PromptStateRepository.kt` | PromptStateRepository | Prompt state persistence | Repository | PromptStateDao | No |
| `repository/ReceiptItemCategorizationRepository.kt` | ReceiptItemCategorizationRepository | Receipt items | Repository | ReceiptItemCategorizationDao | No |
| `repository/ReceiptRepository.kt` | ReceiptRepository | Receipt data access | Repository | ScannedReceiptDao, EmailReceiptDao | No |
| `repository/RecommendationRepository.kt` | RecommendationRepository | Recommendations | Repository | RecommendationDao | No |
| `repository/RecurringExpenseRepository.kt` | RecurringExpenseRepository | Recurring expenses | Repository | RecurringExpenseDao | No |
| `repository/ReviewQueueRepository.kt` | ReviewQueueRepository | Pending review queue | Repository | PendingReviewDao | No |
| `repository/SavingsContributionHistoryRepository.kt` | SavingsContributionHistoryRepository | Savings contribution tracking | Repository | - | No |
| `repository/SavingsGoalRepository.kt` | SavingsGoalRepository | Savings goals | Repository | SavingsGoalDao | No |
| `repository/SharedExpenseDataPortAdapter.kt` | SharedExpenseDataPortAdapter | Shared expense adapter | Repository | GroupExpenseDao | No |
| `repository/SourceStatsRepository.kt` | SourceStatsRepository | Source statistics | Repository | SourceStatsDao | No |
| `repository/SpendingChallengeRepository.kt` | SpendingChallengeRepository | Spending challenge data | Repository | SpendingChallengeDao | No |
| `repository/SubscriptionManagementRepository.kt` | SubscriptionManagementRepository | Subscription data | Repository | SubscriptionCandidateDao | No |
| `repository/UserCorrectionRepository.kt` | UserCorrectionRepository | User corrections | Repository | UserCorrectionDao | No |
| `repository/WarrantyTrackerRepository.kt` | WarrantyTrackerRepository | Warranty data | Repository | WarrantyDao | No |
| `repository/WidgetStyleRepositoryImpl.kt` | WidgetStyleRepositoryImpl | Widget styles | Repository | - | No |
| `repository/TaxSettingsRepository.kt` | TaxSettingsRepository | Tax settings repository | Repository | - | No |

### AI Providers (44 files)

**Location:** `com.yourname.expensetracker.data.ai`

#### Provider Implementations (33 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `ai/provider/CloudCategorizationAssistService.kt` | CloudCategorizationAssistService | Cloud-based categorization | Service | - | Yes |
| `ai/provider/CloudDashboardBriefingService.kt` | CloudDashboardBriefingService | Cloud-based briefing | Service | - | No |
| `ai/provider/CloudDedupeJudgeService.kt` | CloudDedupeJudgeService | Cloud-based dedup | Service | - | Yes |
| `ai/provider/CloudQueryInterpretationService.kt` | CloudQueryInterpretationService | Cloud-based query | Service | - | Yes |
| `ai/provider/CloudReceiptAssistService.kt` | CloudReceiptAssistService | Cloud receipt extraction | Service | - | Yes |
| `ai/provider/CloudReceiptItemCategorizationService.kt` | CloudReceiptItemCategorizationService | Cloud item categorization | Service | - | No |
| `ai/provider/CloudReviewExplanationService.kt` | CloudReviewExplanationService | Cloud review explanation | Service | - | No |
| `ai/provider/CloudWarrantyExtractionService.kt` | CloudWarrantyExtractionService | Cloud warranty extraction | Service | - | No |
| `ai/provider/DefaultAiEnvironmentMonitor.kt` | DefaultAiEnvironmentMonitor | Environment monitoring | Service | - | No |
| `ai/provider/HybridCategorizationAssistService.kt` | HybridCategorizationAssistService | Hybrid categorization | Service | - | No |
| `ai/provider/HybridDashboardBriefingService.kt` | HybridDashboardBriefingService | Hybrid briefing | Service | - | No |
| `ai/provider/HybridDedupeJudgeService.kt` | HybridDedupeJudgeService | Hybrid dedup | Service | - | No |
| `ai/provider/HybridQueryInterpretationService.kt` | HybridQueryInterpretationService | Hybrid query | Service | - | No |
| `ai/provider/HybridReceiptAssistService.kt` | HybridReceiptAssistService | Hybrid receipt | Service | - | No |
| `ai/provider/HybridReceiptItemCategorizationService.kt` | HybridReceiptItemCategorizationService | Hybrid item cat | Service | - | No |
| `ai/provider/HybridReviewExplanationService.kt` | HybridReviewExplanationService | Hybrid review | Service | - | No |
| `ai/provider/NoOpCategorizationAssistService.kt` | NoOpCategorizationAssistService | No-op categorization | Service | - | No |
| `ai/provider/NoOpDashboardBriefingService.kt` | NoOpDashboardBriefingService | No-op briefing | Service | - | No |
| `ai/provider/NoOpDedupeJudgeService.kt` | NoOpDedupeJudgeService | No-op dedup | Service | - | No |
| `ai/provider/NoOpQueryInterpretationService.kt` | NoOpQueryInterpretationService | No-op query | Service | - | No |
| `ai/provider/NoOpReceiptAssistService.kt` | NoOpReceiptAssistService | No-op receipt | Service | - | No |
| `ai/provider/NoOpReviewExplanationService.kt` | NoOpReviewExplanationService | No-op review | Service | - | No |
| `ai/provider/OnDeviceCategorizationAssistService.kt` | OnDeviceCategorizationAssistService | On-device categorization | Service | - | No |
| `ai/provider/OnDeviceDashboardBriefingService.kt` | OnDeviceDashboardBriefingService | On-device briefing | Service | - | No |
| `ai/provider/OnDeviceDedupeJudgeService.kt` | OnDeviceDedupeJudgeService | On-device dedup | Service | - | No |
| `ai/provider/OnDeviceNotificationParser.kt` | OnDeviceNotificationParser | On-device parser | Service | - | No |
| `ai/provider/OnDeviceQueryInterpretationService.kt` | OnDeviceQueryInterpretationService | On-device query | Service | - | No |
| `ai/provider/OnDeviceReceiptAssistService.kt` | OnDeviceReceiptAssistService | On-device receipt | Service | - | No |
| `ai/provider/OnDeviceReceiptItemCategorizationService.kt` | OnDeviceReceiptItemCategorizationService | On-device item cat | Service | - | No |
| `ai/provider/OnDeviceReviewExplanationService.kt` | OnDeviceReviewExplanationService | On-device review | Service | - | No |
| `ai/provider/OnDeviceReviewPriorityScorer.kt` | OnDeviceReviewPriorityScorer | On-device scorer | Service | - | No |
| `ai/provider/OnDeviceSemanticDuplicateDetector.kt` | OnDeviceSemanticDuplicateDetector | On-device duplicate | Service | - | No |
| `ai/provider/SmartReceiptAssistService.kt` | SmartReceiptAssistService | Smart receipt assist | Service | - | No |

#### Provider Internals (7 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `ai/provider/internal/CloudCorrelation.kt` | CloudCorrelation | Cloud request correlation | Utility | - | No |
| `ai/provider/internal/CloudJsonParser.kt` | CloudJsonParser | Cloud JSON parsing | Parser | - | No |
| `ai/provider/internal/CloudPiiSanitizer.kt` | CloudPiiSanitizer | PII sanitization | Security | - | No |
| `ai/provider/internal/CloudRetryPolicy.kt` | CloudRetryPolicy | Retry policy | Utility | - | No |
| `ai/provider/internal/DashboardBriefingPromptFormatter.kt` | DashboardBriefingPromptFormatter | Formats briefing prompts | Utility | - | No |
| `ai/provider/internal/DashboardBriefingResponseParser.kt` | DashboardBriefingResponseParser | Parses briefing responses | Parser | - | No |
| `ai/provider/internal/StrictAiJsonParsing.kt` | StrictAiJsonParsing | Strict JSON parsing for AI | Parser | - | No |

#### AI Worker (2 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `ai/worker/AiWorkSchedulerImpl.kt` | AiWorkSchedulerImpl | Work scheduler implementation | Service | - | No |
| `ai/worker/DailyBriefingWorker.kt` | DailyBriefingWorker | Daily briefing worker | Worker | DashboardBriefingService | No |

### Backup Services (4 files)

**Location:** `com.yourname.expensetracker.data.backup`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `backup/BackupVerifier.kt` | BackupVerifier | Verifies backup integrity | Service | - | No |
| `backup/CostbackupBundle.kt` | CostbackupBundle | Bundled backup data model | Model | - | No |
| `backup/RestoreJournal.kt` | RestoreJournal | Restore operation journal | Service | - | No |
| `backup/RestoreMaintenanceMode.kt` | RestoreMaintenanceMode | Maintenance mode for restore | Service | - | No |

### Currency Services (1 file)

**Location:** `com.yourname.expensetracker.data.currency`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `currency/ExchangeRateStoreAdapter.kt` | ExchangeRateStoreAdapter | Exchange rate adapter | Repository | ExchangeRateDao | No |

### Email Ingestion (5 files)

**Location:** `com.yourname.expensetracker.data.email`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `email/EmailReceiptIngestionService.kt` | EmailReceiptIngestionService | Email receipt ingestion | Service | - | No |
| `email/provider/AmazonReceiptParser.kt` | AmazonReceiptParser | Amazon receipt parser | Parser | - | No |
| `email/provider/AppleReceiptParser.kt` | AppleReceiptParser | Apple receipt parser | Parser | - | No |
| `email/provider/EmailReceiptParser.kt` | EmailReceiptParser | Email receipt parser interface | Service | - | No |
| `email/provider/UberReceiptParser.kt` | UberReceiptParser | Uber receipt parser | Parser | - | No |

### Location Services (11 files)

**Location:** `com.yourname.expensetracker.data.location`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `location/AndroidForegroundLocationProvider.kt` | AndroidForegroundLocationProvider | Android location provider | Service | - | No |
| `location/CompositeGeocodingService.kt` | CompositeGeocodingService | Composite geocoding | Service | Multiple geocoding services | No |
| `location/GeoapifyGeocodingService.kt` | GeoapifyGeocodingService | Geoapify geocoding | Service | - | No |
| `location/GooglePlacesGeocodingService.kt` | GooglePlacesGeocodingService | Google Places geocoding | Service | - | No |
| `location/LocationBackfillWorker.kt` | LocationBackfillWorker | Location backfill worker | Worker | MerchantLocationDao | No |
| `location/MerchantKeyBackfillWorker.kt` | MerchantKeyBackfillWorker | Merchant key backfill | Worker | ExpenseDao | No |
| `location/NominatimGeocodingService.kt` | NominatimGeocodingService | Nominatim geocoding | Service | - | No |
| `location/OverpassNearbyService.kt` | OverpassNearbyService | Overpass nearby POI | Service | - | No |
| `location/PhotonGeocodingService.kt` | PhotonGeocodingService | Photon geocoding | Service | - | No |
| `location/internal/CancellableHttpCall.kt` | CancellableHttpCall | Cancellable HTTP call utility | Utility | - | No |
| `location/internal/LogSanitizer.kt` | LogSanitizer | Log sanitization | Security | - | No |

### Privacy Services (7 files)

**Location:** `com.yourname.expensetracker.data.privacy`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `privacy/AtRestEncryptionService.kt` | AtRestEncryptionService | At-rest data encryption | Security | - | No |
| `privacy/BackupEncryptionService.kt` | BackupEncryptionService | Backup encryption/decryption | Security | - | No |
| `privacy/DataRetentionWorker.kt` | DataRetentionWorker | Data retention policy worker | Worker | - | No |
| `privacy/DefaultCloudPayloadRedactor.kt` | DefaultCloudPayloadRedactor | Cloud payload redaction | Security | - | No |
| `privacy/ExportAnonymizer.kt` | ExportAnonymizer | Anonymizes exported data | Security | - | No |
| `privacy/PrivacyAuditLoggerImpl.kt` | PrivacyAuditLoggerImpl | Privacy audit logging impl | Repository | PrivacyAuditDao | No |
| `privacy/PrivacySettingsRepositoryImpl.kt` | PrivacySettingsRepositoryImpl | Privacy settings persistence | Repository | - | No |

### Security Services (2 files)

**Location:** `com.yourname.expensetracker.data.security`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `security/BankTokenCipher.kt` | BankTokenCipher | Bank token encryption | Security | - | No |
| `security/SecureKeyStorage.kt` | SecureKeyStorage | Secure key storage | Security | - | No |

### Speech Services (1 file)

**Location:** `com.yourname.expensetracker.data.speech`

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `speech/AndroidSpeechInputGateway.kt` | AndroidSpeechInputGateway | Android speech input | Service | - | No |

### Other Data Services (3 files)

**Location:** Various data subsystems

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `provider/MerchantCategoryProvider.kt` | MerchantCategoryProvider | Merchant category provider | Provider | - | No |
| `service/AndroidNotificationService.kt` | AndroidNotificationService | Android notifications | Service | - | No |

---

## DI/MODULES PACKAGE

**Location:** `com.yourname.expensetracker.di` — 31 Hilt @Module files + qualifier annotations

| File | Class | Purpose | Type | Provides | Tests |
|------|-------|---------|------|----------|-------|
| `AiModule.kt` | AiModule | AI service binding | Module | All AI services, 3 AI DAOs, RedactionSanitizer, AiPolicy | No |
| `BackupRepositoryModule.kt` | BackupRepositoryModule | Backup binding | Module | DatabaseBackupRepository | No |
| `CashFlowModule.kt` | CashFlowModule | Cash flow binding | Module | CashFlowCalculator | No |
| `CurrencyModule.kt` | CurrencyModule | Currency binding | Module | CurrencySettingsRepository, CurrencyRatesRepository, ExchangeRateStore | No |
| `DaoModule.kt` | DaoModule | DAO injection (58 DAOs) | Module | All DAOs except AiModule-provided | No |
| `DashboardAnomalyModule.kt` | DashboardAnomalyModule | Anomaly alert binding | Module | AnomalyAlertRepository (domain + dashboard) | No |
| `DashboardContractsModule.kt` | DashboardContractsModule | Dashboard contracts (7 adapters) | Module | DashboardRepositoryContracts | No |
| `DatabaseModule.kt` | DatabaseModule | Database initialization | Module | AppDatabase, GroupTransactionCoordinator | No |
| `DiagnosticsModule.kt` | DiagnosticsModule | Diagnostic writers binding | Module | DiagnosticEventWriter, Lifecycle event writers, OperationRunRecorder, DiagnosticsRepository | No |
| `DispatchersModule.kt` | DispatchersModule | Coroutine dispatchers | Module | IO, Default, Main dispatchers, ApplicationScope | No |
| `EmailIngestionModule.kt` | EmailIngestionModule | Email parsing | Module | Amazon, Uber, Apple receipt parsers | No |
| `EmptyStateModule.kt` | EmptyStateModule | Empty state registry | Module | Empty state configurations (multibind) | No |
| `ExportModule.kt` | ExportModule | Export binding | Module | QuickBooksIIF, XeroCSV, FreshBooks exporters | No |
| `GroupsModule.kt` | GroupsModule | Groups binding | Module | GroupsRepository, SharedExpenseDataPort, Use cases | No |
| `LocationResolverPortsModule.kt` | LocationResolverPortsModule | Location ports | Module | LocationCachePort, MerchantClusterPort | No |
| `NaturalLanguageModule.kt` | NaturalLanguageModule | NL binding | Module | NaturalLanguageExpenseQueryRepository | No |
| `NetworkModule.kt` | NetworkModule | Network client | Module | @LocationHttpClient, @CloudAiHttpClient OkHttpClient | No |
| `OcrImprovementsModule.kt` | OcrImprovementsModule | OCR binding | Module | EnhancedMerchantExtractor, OcrLanguageProcessor, OcrPreprocessingPipeline | No |
| `ParserModule.kt` | ParserModule | Bank parser binding | Module | GreekBankParser | No |
| `PrivacyModule.kt` | PrivacyModule | Privacy gate binding | Module | CompositePrivacyGate, PrivacyAuditLogger, PrivacySettingsRepository | No |
| `ProvenanceModule.kt` | ProvenanceModule | Provenance tracking | Module | Provenance event recording | No |
| `ReceiptParsingModule.kt` | ReceiptParsingModule | Receipt parsing | Module | MerchantRulesPolicy binding | No |
| `ReminderSettingsModule.kt` | ReminderSettingsModule | Reminder settings binding (P4) | Module | BillReminderSettingsRepository | No |
| `RetentionModule.kt` | RetentionModule | Data retention policy | Module | RetentionRegistry with 5 targets | No |
| `SavingsModule.kt` | SavingsModule | Savings binding | Module | SmartSavingsEngine, AutomatedSavingsRule*, SavingsGamificationEngine | No |
| `SavingsRepositoryBindingsModule.kt` | SavingsRepositoryBindingsModule | Savings repos | Module | DomainSavingsGoalRepository binding | No |
| `SecurityModule.kt` | SecurityModule | Security binding | Module | SecureKeyStorage | No |
| `ServiceModule.kt` | ServiceModule | Services binding | Module | Gson, NotificationService, GeocodingService, NearbyPoi, Location, Widget, Speech | No |
| `TaxModule.kt` | TaxModule | Tax binding | Module | TaxConfiguration → GreeceTaxConfiguration | No |
| `TimeModule.kt` | TimeModule | Time provider | Module | TimeProvider → SystemTimeProvider | No |
| `WorkerModule.kt` | WorkerModule | Worker logging | Module | WorkerRunLogger → WorkerRunLoggerImpl | No |

---

## APP SERVICES PACKAGE

**Location:** `com.yourname.expensetracker.service`

### Recommendation System (7 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `RecommendationCacheService.kt` | RecommendationCacheService | In-memory LRU cache with 7-day TTL for dashboard recommendations | Service | RecommendationRepository, TimeProvider | No |
| `RecommendationDeduplicator.kt` | RecommendationDeduplicator | Signature-based dedup per merchant/category/target | Service | TransactionFilterSerializer | No |
| `RecommendationDismissalHandler.kt` | RecommendationDismissalHandler | Handles user dismissal of recommendation cards | Service | RecommendationRepository, RecommendationStateManager | No |
| `RecommendationInvalidator.kt` | RecommendationInvalidator | Invalidates stale/expired recommendations on transaction changes | Service | RecommendationRepository, RecommendationStateManager, RecommendationCacheService | No |
| `RecommendationLifecycleManager.kt` | RecommendationLifecycleManager | Manages lifecycle: expiration, cleanup, threshold refresh | Service | RecommendationRepository, RecommendationStateManager, RecommendationCacheService, SpendingThresholdCalculator | No |
| `RecommendationStateManager.kt` | RecommendationStateManager | Reactive StateFlow for UI, max 5 limit, user-specific | Service | RecommendationRepository, TimeProvider | No |

### Notification Capture (2 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `NotificationCaptureService.kt` | NotificationCaptureService | Android NotificationListenerService | Service | PrivacyGate, NotificationFilter | No |
| `NotificationFilter.kt` | NotificationFilter | Filters notifications by package/type | Service | - | No |

### Utilities (2 files)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `NavigationTargetResolver.kt` | NavigationTargetResolver | Resolves navigation targets from recommendations | Service | - | No |
| `TransactionFilterSerializer.kt` | TransactionFilterSerializer | Serializes transaction filters for dedup signatures | Service | - | No |

### Receivers (2 files — `receiver/` package)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `receiver/BootReceiver.kt` | BootReceiver | BOOT_COMPLETED / MY_PACKAGE_REPLACED receiver | Receiver | - | No |
| `receiver/ServiceRestartReceiver.kt` | ServiceRestartReceiver | Service keep-alive receiver | Receiver | - | No |

### Root Utilities (1 file)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `util/CsvExpenseImporter.kt` | CsvExpenseImporter | Bulk CSV expense import through TransactionLifecycleCoordinator | Utility | CategoryDao, TransactionLifecycleCoordinator, CurrencySettingsRepository | No |

### Legacy (1 file)

| File | Class | Purpose | Type | Dependencies | Tests |
|------|-------|---------|------|--------------|-------|
| `service/LegacyDataMigrationService.kt` | LegacyDataMigrationService | One-time data migration from older app versions | Service | - | No |

---

## Dependency Graph & Data Flow

### Core Data Flow Architecture

```
User Input (Notification/Manual Entry)
    ↓
ParserRegistry (SMS, Email, Manual)
    ↓
GenericTransactionParser / SpecializedParsers
    ↓
ExpenseEntity (stored in DB)
    ↓
ExpenseRepository (CRUD layer)
    ↓
Use Cases (Domain layer)
    ↓
Engines (Analytics, Budget, Categorization, etc.)
    ↓
Dashboard/UI
```

### AI Pipeline

```
Raw Input (Receipt/Notification/Query)
    ↓
AiCapabilityRouter (decision point)
    ↓
┌─────────────────┬──────────────┬─────────────────┐
│ Cloud Provider  │ OnDevice ML  │ Fallback/NoOp   │
│ (best quality)  │ (offline)    │ (no AI)         │
└─────────────────┴──────────────┴─────────────────┘
    ↓
Specific AI Service (Categorization/Receipt/Dedup)
    ↓
Result (with confidence score)
    ↓
ConfidenceRouter (routes based on confidence)
    ↓
Domain Engine (categorization, dedup, etc.)
```

### Privacy Gate Pipeline

```
Feature Request
    ↓
CompositePrivacyGate (chains all sub-gates)
    ↓
┌────────────────┬──────────────┬──────────────┬──────────────┐
│ Notification  │ Cloud AI     │ Location     │ Backup       │
│ PrivacyGate   │ PrivacyGate  │ PrivacyGate  │ PrivacyGate  │
└────────────────┴──────────────┴──────────────┴──────────────┘
    ↓
PrivacyDecision (Allowed / Denied)
    ↓
PrivacyAuditLogger (logs gate check)
    ↓
Proceed or Block
```

### Transaction Lifecycle Flow

```
CreateExpenseRequest (from any source)
    ↓
TransactionLifecycleCoordinator (single entry point)
    ↓
┌──────────────────────────────────────────────┐
│ 1. Deduplication check (DeduplicationMode)   │
│ 2. Expense creation (CreateExpenseResult)    │
│ 3. Side effect dispatch (SideEffectMode)     │
└──────────────────────────────────────────────┘
    ↓
TransactionSideEffectDispatcher
    ↓
┌─────────────────┬─────────────────┬─────────────────┐
│ Receipt Linking │ Budget Update    │ Analytics       │
│ Notification    │ Recurring Check  │ AI Processing   │
└─────────────────┴─────────────────┴─────────────────┘
```

### Receipt Lifecycle Flow

```
Receipt Input (Scan / Email / Gallery)
    ↓
ReceiptInputValidator (URI/MIME/size)
    ↓
ReceiptDuplicateDetector (3-signal dedup)
    ↓
ReceiptAssetStore (file persistence + hashing)
    ↓
ReceiptLifecycleCoordinator (orchestrates)
    ↓
┌──────────────────────────────────────────────┐
│ 1. OCR Processing                            │
│ 2. Merchant Extraction                       │
│ 3. Categorization                            │
│ 4. Receipt-Expense Linking (ReceiptLinkSvc)  │
└──────────────────────────────────────────────┘
    ↓
ReceiptSideEffectDispatcher (document-type-gated)
    ↓
BankStatementLifecycleProcessor (for statements)
```

### Database Entity Relationships

**Core Transaction Entities:**
- `Expense` ← Core transaction
- `PendingReview` ← Needs user review
- `Category` ← Transaction category
- `UserCorrection` ← User adjustments

**Related Entities:**
- `ScannedReceipt`, `EmailReceiptSource` ← Receipt sources
- `ReceiptEvent`, `ReceiptExpenseLink` ← Receipt lifecycle
- `ManualRecurringExpense`, `RecurringExpense` ← Recurring patterns
- `RecurringOccurrence`, `RecurringLifecycleEvent` ← Recurring lifecycle
- `RecurringReminderDelivery` ← Reminder tracking
- `Budget`, `BudgetForecast` ← Budget tracking
- `SavingsGoal`, `SavingsSweepPlan` ← Savings
- `SpendingChallengeEntity` ← Challenges
- `GroupExpense`, `GroupMember`, `ExpenseGroup` ← Shared expenses
- `MerchantCanonical`, `MerchantAlias`, `MerchantLocation` ← Merchant data
- `ExchangeRate` ← Currency conversion
- `Warranty`, `ReturnWindow` ← Warranty tracking
- `Investment`, `InvestmentValue` ← Investment tracking
- `SubscriptionCandidate`, `SubscriptionUsage` ← Subscription detection
- `RawNotification`, `BlockedPackage` ← Notification tracking
- `SourceStats`, `SourceStatsEvent` ← Source analytics
- `BackgroundJobRun` ← Background job tracking
- `AnomalyAlert` ← Anomaly alerting

### Repository → DAO → Entity Flow

```
UseCase
    ↓
Repository (business logic, data transformation)
    ↓
DAO (Room database access)
    ↓
Entity (Room-managed table)
    ↓
SQLite Database
```

### Engine → Repository → DAO Chain

**Example: Budget Calculation**
```
CalculateBudgetStatusUseCase
    ↓
BudgetCalculator (domain logic)
    ↓
BudgetRepository (provides data)
    ↓
BudgetDao + ExpenseDao (queries)
    ↓
Budget + Expense entities
```

**Example: Categorization**
```
CategorizeExpenseUseCase
    ↓
CategorizationEngine (domain logic)
    ↓
CategoryKeywords + ContextualInferenceEngine
    ↓
MerchantCategoryRepository
    ↓
MerchantCategoryDao
    ↓
MerchantCategory entity
```

### AI Services → Domain Engines

```
AiModels (input/output contracts)
    ↓
AiCapabilityRouter (routes to provider)
    ↓
Cloud/OnDevice/NoOp Providers
    ↓
Domain AI Service (wraps provider)
    ↓
UseCase (business logic)
    ↓
Engine (integration with other domain logic)
```

### Cross-Cutting Concerns

| Concern | Implementation | Files |
|---------|----------------|-------|
| **Dependency Injection** | Dagger/Hilt | `di/*Module.kt` |
| **Database** | Room | `data/database/*` |
| **Security** | Encryption, Key Storage | `data/security/*`, `data/privacy/*` |
| **Geocoding** | Multiple providers | `data/location/*` |
| **AI** | Cloud + OnDevice + Hybrid + NoOp | `data/ai/provider/*` |
| **Parsing** | Strategy pattern | `domain/parser/*`, `data/email/*` |
| **Privacy** | Gate pattern + redaction | `domain/privacy/*`, `data/privacy/*` |
| **Backup** | Encrypted bundles + journal | `data/backup/*` |
| **Utilities** | Shared helpers | `domain/util/*` |

---

## Summary Statistics

| Metric | Count |
|--------|-------|
| **Domain Files** | 344 |
| **Data Files** | 253 |
| **DI / @Module Files** | 31 |
| **Total Backend Files** | ~926 |
| **Test Files** | ~500+ |
| **Database Entities** | 64 |
| **DAOs** | 62 |
| **Repositories** | 65 (52 data + 13 domain interfaces) |
| **Use Cases** | 41 |
| **Engines** | ~70 |
| **AI Providers** | 44 |

---

## Key Architecture Patterns

1. **Clean Architecture** - Domain, Data, DI separation
2. **Repository Pattern** - Data abstraction layer
3. **Use Case Pattern** - Single responsibility use cases
4. **Strategy Pattern** - Multiple AI providers, parsers, geocoders
5. **Adapter Pattern** - Data/domain boundary adaptation
6. **Observer Pattern** - Flow-based reactive data
7. **Builder Pattern** - Complex AI input construction
8. **Decorator Pattern** - Hybrid AI services wrapping
9. **Factory Pattern** - ParserRegistry, AppDatabase
10. **Singleton Pattern** - Repositories, Engines via DI
11. **Gate Pattern** - Privacy capability gating
12. **Lifecycle Coordinator Pattern** - Centralized CUD entry points
13. **Sealed Result Pattern** - Typed operation results (CreateExpenseResult, etc.)

---

**End of Complete Backend Map**
