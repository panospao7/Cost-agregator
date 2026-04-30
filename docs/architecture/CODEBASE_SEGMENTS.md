# ExpenseTracker Codebase Segments

Canonical guide for segment ownership and AI analysis.

## Rules
- One segment list, one ascending order, one owning section per segment.
- The summary table below matches the section headers exactly.
- OCR/capture/review is separate from AI receipt item categorization.
- Totals live only in the dashboard/totals segment.
- Lifestyle detection is separate from savings prompt extensions.
- Base shared-expense groups are separate from budget-offset extensions.

## Segment Map

| # | Segment | Owns |
|---|---|---|
| 1 | Forecasting & Runway | spending forecast, runway estimation, deterministic + Monte Carlo forecasting |
| 2 | Budget Management | budget CRUD, rollover, budget health, budget alerts logic |
| 3 | Notification Capture, Parsing & Review | notification listener, parser registry, transaction review queue |
| 4 | Receipt Scanning (OCR) | OCR capture, receipt parsing, statement image parsing, scan review UI |
| 5 | AI Receipt Item Categorization | line-item AI categorization, item prompts, confidence, tax split |
| 6 | Merchant Categorization | merchant normalization, rule/ML categorization pipeline |
| 7 | Recurring Expenses | recurring pattern detection and planned recurring costs |
| 8 | Analytics & Insights | trends, anomalies, merchant/category insights |
| 9 | Core Expense Management | expense CRUD, manual entry, filters, shared flags, core repositories |
| 10 | Dashboard Totals & Widgets | dashboard composition, totals aggregation, drill-down totals UI |
| 11 | Notifications & Alerts | in-app/system notification delivery for budget and app alerts |
| 12 | Startup & Background Runtime | boot/startup wiring, service restarts, background workers, periodic runtime jobs |
| 13 | Cash Flow Planning | cash-flow pacing, balance/runway operations, cash-flow-oriented planners |
| 14 | Bank Integration | bank account sync/import, bank adapters, bank transaction ingestion |
| 15 | Investment Tracking | holdings, portfolio tracking, investment metrics and sync |
| 16 | Currency & Exchange | multi-currency support, normalization, conversion, exchange rates |
| 17 | Tax Calculation & Reporting | tax allocation, tax-aware summaries, reporting prep |
| 18 | Export & Backup | export, backup, restore, file packaging and recovery flows |
| 19 | Location Enrichment | geocoding, map UI, merchant location enrichment, corrections |
| 20 | AI Platform, Assistant & Follow-Through | app-wide AI policy, assistant/settings surface, provider wiring, and AI briefing follow-through recommendations |
| 21 | Enhanced Split Transactions | visual split editor, templates, item-level split assignment |
| 22 | Lifestyle Inflation Detector | lifestyle creep detection and lifestyle metrics |
| 23 | Savings Prompts & Nudges | savings prompt persistence and nudges; separate from lifestyle detection |
| 24 | Shared Expense Groups | group CRUD, membership, shared-expense coordination |
| 25 | Shared Expense Budget Offset | reimbursement-aware budget offset extension for shared expenses |
| 26 | Natural Language Search | query interpretation, entity extraction, voice search |
| 27 | Carbon Footprint Tracking | emission calculations and sustainability recommendations |
| 28 | Security & API Key Management | secure key storage, network/security bindings, secret handling |
| 29 | Debug & Diagnostics | debug screens, data viewers, diagnostics, test seeding |
| 30 | Dependency Injection | Hilt modules and app wiring |
| 31 | Use Cases | application-layer orchestration use cases |
| 32 | Utilities & Shared Helpers | reusable domain/UI helpers and cross-cutting utilities |
| 33 | Configuration, Performance & Accessibility | app config, caching, and accessibility-focused UI polish |
| 34 | Warranty, Subscription & Offers | warranty tracking, subscription management, bill negotiation, price protection |
| 35 | Savings Optimization & Health | smart savings optimization, financial health scoring, savings KPI logic |
| 36 | Bill Reminders | bill reminder scheduling, due-date tracking, reminder UI |
| 37 | Spending Challenges | spending challenge creation, tracking, and challenge UI |
| 38 | Receipt Matching | receipt-to-transaction matching and reconciliation UI |

---

## SEGMENT 1: Forecasting & Runway

Owns the core spending forecast engine and month-end runway views.

**Representative files**
- `domain/logic/SynthesisEngine.kt`
- `domain/forecasting/MonteCarloSpendingSimulator.kt`
- `domain/forecasting/MonteCarloResult.kt`
- `domain/forecasting/HistoricalSpendingDistribution.kt`
- `domain/forecasting/DataQualityAssessor.kt`
- `data/repository/FinancialWeatherRepository.kt`
- `ui/components/FinancialRunwayCard.kt`
- `ui/components/FinancialWeatherCard.kt`

**Boundary note:** forecast inputs may come from budgets and recurring expenses, but budget ownership stays in Segment 2.

**Boundary note:** `ui/screens/home/HomeViewModel.kt` is owned with Segment 10.

## SEGMENT 2: Budget Management

Owns budget lifecycle, rollover, and budget status calculation.

**Representative files**
- `domain/budget/BudgetCalculator.kt`
- `domain/budget/BudgetMonitor.kt`
- `domain/budget/BudgetModels.kt`
- `data/repository/BudgetRepository.kt`
- `ui/screens/budget/BudgetScreen.kt`
- `ui/screens/budget/BudgetViewModel.kt`

## SEGMENT 3: Notification Capture, Parsing & Review

Owns notification capture, parser routing, structured transaction extraction, and review workflow.

**Representative files**
- `domain/parser/AppParserRegistry.kt`
- `domain/parser/GenericTransactionParser.kt`
- `domain/parser/parsers/*.kt`
- `domain/intelligence/ConfidenceRouter.kt`
- `domain/intelligence/TransactionClassifier.kt`
- `data/repository/NotificationRepository.kt`
- `data/repository/ReviewQueueRepository.kt`
- `ui/screens/review/ReviewScreen.kt`

**Boundary note:** this segment stops at parsed/reviewable transactions; OCR belongs to Segment 4 and receipt-item AI belongs to Segment 5.

## SEGMENT 4: Receipt Scanning (OCR)

Owns receipt capture, OCR extraction, receipt parsing, and scanned-receipt review flow.

**Representative files**
- `domain/receipt/ReceiptOcrService.kt`
- `domain/receipt/ReceiptParser.kt`
- `domain/receipt/BankStatementParser.kt`
- `data/repository/ReceiptRepository.kt`
- `ui/screens/receiptscan/ReceiptScanScreen.kt`
- `ui/screens/receiptscan/ReceiptScanViewModel.kt`

**Boundary note:** no item-level AI categorization here.

## SEGMENT 5: AI Receipt Item Categorization

Owns per-line-item AI categorization for scanned receipts, including confidence, corrections, and tax allocation.

**Representative files**
- `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `domain/ai/model/ReceiptItemCategorizationModels.kt`
- `data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
- `data/ai/provider/HybridReceiptItemCategorizationService.kt`
- `data/database/entity/ReceiptItemCategorization.kt`
- `data/database/dao/ReceiptItemCategorizationDao.kt`
- `ui/components/ai/ReceiptItemBreakdownCard.kt`

## SEGMENT 6: Merchant Categorization

Owns merchant-name normalization and category prediction for ordinary expenses.

**Representative files**
- `domain/categorization/CategorizationEngine.kt`
- `domain/categorization/GreeklishNormalizer.kt`
- `domain/categorization/MerchantCanonicalizer.kt`
- `domain/intelligence/ml/HybridExpenseClassifier.kt`
- `domain/intelligence/ml/ExpenseCategoryClassifier.kt`
- `data/repository/CategoryRepository.kt`
- `data/repository/MerchantCategoryRepository.kt`

## SEGMENT 7: Recurring Expenses

Owns recurring pattern detection and future recurring item planning.

**Representative files**
- `domain/logic/RecurringExpenseEngine.kt`
- `domain/model/RecurringPattern.kt`
- `domain/model/UpcomingItem.kt`
- `data/repository/RecurringExpenseRepository.kt`
- `data/repository/PlannedExpenseRepository.kt`

## SEGMENT 8: Analytics & Insights

Owns statistical analysis, anomaly detection, category insights, and merchant insights.

**Representative files**
- `domain/analytics/InsightsEngine.kt`
- `domain/analytics/AnomalyDetector.kt`
- `domain/analytics/AdvancedAnalyticsEngine.kt`
- `data/repository/AnalyticsRepository.kt`
- `ui/screens/analytics/AnalyticsScreen.kt`
- `ui/components/analytics/StatisticalVisualizations.kt`

## SEGMENT 9: Core Expense Management

Owns the base expense CRUD surface and shared core expense models.

**Representative files**
- `data/repository/ExpenseRepository.kt`
- `data/repository/ManualExpenseRepository.kt`
- `ui/screens/transactions/TransactionsScreen.kt`
- `ui/screens/addexpense/AddExpenseSheet.kt`
- `data/database/entity/Expense.kt`
- `data/database/dao/ExpenseDao.kt`

## SEGMENT 10: Dashboard Totals & Widgets

Owns the dashboard home composition and all totals aggregation logic.

**Representative files**
- `data/repository/DashboardRepository.kt`
- `domain/analytics/TotalsAggregationEngine.kt`
- `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- `ui/screens/home/HomeScreen.kt`
- `ui/screens/home/HomeViewModel.kt`
- `ui/components/TotalsDashboardCard.kt`
- `ui/components/PeriodNavigationBar.kt`
- `ui/components/PeriodGridView.kt`
- `ui/components/PeriodBlock.kt`

**Boundary note:** totals are owned only here; no phantom totals segment elsewhere.

## SEGMENT 11: Notifications & Alerts

Owns notification delivery plumbing for budget and app alerts.

**Representative files**
- `domain/service/NotificationService.kt`
- `data/service/AndroidNotificationService.kt`

## SEGMENT 12: Startup & Background Runtime

Owns startup wiring, service lifecycle recovery, and background runtime jobs.

**Representative files**
- `startup/AppStartupDelegate.kt`
- `startup/AppStartupCoordinator.kt`
- `startup/AppBackgroundLifecycleObserver.kt`
- `data/ai/worker/DailyBriefingWorker.kt`
- `data/location/LocationBackfillWorker.kt`
- `data/location/MerchantKeyBackfillWorker.kt`
- `service/warranty/WarrantyExpirationWorker.kt`

## SEGMENT 13: Cash Flow Planning

Owns operational cash-flow planning, pacing, and balance-oriented runtime logic.

**Representative files**
- `domain/cashflow/CashFlowCalculator.kt`
- `domain/forecasting/FinancialStressForecastEngine.kt`
- `ui/screens/cashflow/CashFlowCalendarScreen.kt`

**Boundary note:** cash-flow planning can read forecast results from Segment 1, but it does not own `data/repository/FinancialWeatherRepository.kt`.

## SEGMENT 14: Bank Integration

Owns bank account sync/import and bank-facing adapters.

**Representative files**
- `domain/bank/BankApiIntegration.kt`
- `domain/bank/BankApiConfig.kt`
- `data/database/entity/BankConnection.kt`
- `data/database/dao/BankConnectionDao.kt`
- `ui/screens/bank/BankConnectionsScreen.kt`

## SEGMENT 15: Investment Tracking

Owns holdings, portfolio tracking, and investment metrics.

**Representative files**
- `domain/investment/InvestmentTracker.kt`
- `data/database/entity/Investment.kt`
- `data/database/entity/InvestmentValue.kt`
- `data/database/dao/InvestmentDao.kt`
- `ui/screens/investment/InvestmentPortfolioScreen.kt`

## SEGMENT 16: Currency & Exchange

Owns currency normalization, exchange-rate handling, multi-currency calculations, and type-safe money primitives.

**Representative files — domain/core/money/**
- `domain/core/money/CurrencyCode.kt` — Type-safe ISO 4217 value class
- `domain/core/money/MoneyAmount.kt` — Amount + currency pair with safe arithmetic
- `domain/core/money/ConvertedMoney.kt` — Conversion result with rate metadata
- `domain/core/money/MoneyBucket.kt` — Per-currency subtotal bucket
- `domain/core/money/MoneyAggregate.kt` — Primary aggregation return type (replaces raw Double)
- `domain/core/money/ConversionFailure.kt` — Failed conversion record
- `domain/core/money/CurrencyAssumption.kt` — Why a currency was assigned (UNKNOWN, ASSUMED_LEGACY_EUR, etc.)
- `domain/core/money/MoneyMappers.kt` — Bridge from legacy ConversionResult → ConvertedMoney
- `domain/core/money/MoneyFormatUtils.kt` — MoneyAmount extension formatting functions

**Representative files — legacy + new**
- `domain/currency/CurrencyConverter.kt` — Currency conversion engine
- `domain/currency/CurrencyRatesRepository.kt` — FX rate data source
- `domain/currency/ExchangeRateContracts.kt` — Exchange rate store contract
- `domain/currency/CurrencySettingsRepository.kt` — Currency preferences + emergencyBuffer()
- `data/repository/MultiCurrencyRepository.kt` — **Canonical aggregation backbone** (wired into 10+ pipelines)
- `data/database/entity/ExchangeRate.kt` — Exchange rate entity (now with `validDate`)
- `domain/util/CurrencyNormalizer.kt` — Legacy currency code normalizer
- `domain/util/CurrencyFormatter.kt` — Format utilities (new formatMoney/formatMoneyCompact methods)
- `domain/analytics/AnalyticsCurrencyNormalizer.kt` — Per-expense home-currency normalization
- `scripts/currency_guardrails.ps1` — CI guardrails against currency regressions

**Boundary note:** AnalyticsCurrencyNormalizer lives in domain/analytics/ but is logically part of the currency infrastructure. MultiCurrencyRepository is the canonical entry point for all currency-aware aggregation; raw DAO aggregation methods are deprecated.

## SEGMENT 17: Tax Calculation & Reporting

Owns tax allocation and tax-aware reporting logic.

**Representative files**
- `domain/tax/TaxEstimator.kt`
- `domain/tax/TaxConfiguration.kt`
- `domain/business/BusinessExpenseReportGenerator.kt`
- `domain/export/AccountantReportPdfExporter.kt`
- `ui/screens/tax/TaxConfigurationScreen.kt`

## SEGMENT 18: Export & Backup

Owns export pipelines, backup/restore flows, and file packaging.

**Representative files**
- `data/repository/AccountingExportRepository.kt`
- `data/repository/DatabaseBackupRepositoryImpl.kt`
- `domain/backup/DatabaseBackupRepository.kt`
- `domain/export/AccountingExporters.kt`
- `ui/screens/export/ExportOptionsScreen.kt`
- `di/BackupRepositoryModule.kt`

## SEGMENT 19: Location Enrichment

Owns geocoding, location correction, and map-based enrichment.

**Representative files**
- `domain/location/LocationResolver.kt`
- `domain/location/LocationInsightsEngine.kt`
- `data/location/CompositeGeocodingService.kt`
- `ui/screens/map/SpendingMapScreen.kt`

**Boundary note:** `data/location/LocationBackfillWorker.kt` is orchestrated by this segment, but owned with startup/runtime in Segment 12.

## SEGMENT 20: AI Platform, Assistant & Follow-Through

Owns the app-wide AI platform surface: policy, assistant sheet, AI settings, provider wiring, and briefing follow-through.

**Representative files**
- `di/AiModule.kt`
- `domain/ai/policy/AiPolicy.kt`
- `ui/screens/assistant/AssistantSheet.kt`
- `ui/screens/aisettings/AiSettingsScreen.kt`
- `data/ai/provider/DefaultAiEnvironmentMonitor.kt`
- `data/ai/provider/StrictAiJsonParsing.kt`
- `data/ai/provider/DashboardBriefingPromptFormatter.kt`
- `data/ai/provider/CloudDashboardBriefingService.kt`
- `data/ai/provider/OnDeviceDashboardBriefingService.kt`
- `data/ai/provider/HybridDashboardBriefingService.kt`
- `data/ai/provider/NoOpDashboardBriefingService.kt`
- `domain/engine/DashboardFollowThroughEngine.kt`
- `data/repository/RecommendationRepository.kt`
- `ui/components/RecommendationCard.kt`

## SEGMENT 21: Enhanced Split Transactions

Owns the visual split editor, templates, and item-level participant assignments.

**Representative files**
- `domain/split/EnhancedSplitManager.kt`
- `ui/screens/split/VisualSplitEditorScreen.kt`
- `ui/screens/split/SplitTemplatesScreen.kt`
- `data/database/entity/SplitTemplate.kt`
- `data/database/entity/SplitItemAssignment.kt`

## SEGMENT 22: Lifestyle Inflation Detector

Owns lifestyle creep detection only.

**Representative files**
- `domain/lifestyle/LifestyleInflationDetector.kt`
- `ui/screens/lifestyle/LifestyleInflationScreen.kt`

**Boundary note:** prompt persistence and savings nudges are not owned here.

## SEGMENT 23: Savings Prompts & Nudges

Owns savings prompt persistence, nudges, and related follow-up logic.

**Representative files**
- `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt`
- `data/database/entity/PromptState.kt`
- `data/database/dao/PromptStateDao.kt`

## SEGMENT 24: Shared Expense Groups

Owns the base group/shared-expense model, membership, and transaction coordination.

**Representative files**
- `data/repository/GroupsRepository.kt`
- `data/repository/GroupsRepositoryImpl.kt`
- `domain/groups/GroupTransactionCoordinator.kt`
- `domain/groups/usecase/AddGroupExpenseUseCase.kt`
- `domain/groups/SharedExpenseManager.kt`
- `ui/screens/groups/SharedExpenseGroupsViewModel.kt`

## SEGMENT 25: Shared Expense Budget Offset

Owns reimbursement-aware budget offset logic built on top of shared expenses.

**Representative files**
- `domain/groups/SharedExpenseBudgetOffsetEngine.kt`

**Boundary note:** this is an extension of Segment 24, not a replacement for it.

## SEGMENT 26: Natural Language Search

Owns natural-language query parsing and voice-enabled search.

**Representative files**
- `domain/naturallanguage/NaturalLanguageSearchEngine.kt`
- `ui/screens/naturallanguage/NaturalLanguageSearchScreen.kt`

## SEGMENT 27: Carbon Footprint Tracking

Owns emissions calculation and sustainability-facing reporting.

**Representative files**
- `domain/carbon/CarbonFootprintCalculator.kt`
- `ui/screens/carbon/CarbonFootprintScreen.kt`

## SEGMENT 28: Security & API Key Management

Owns encrypted key storage and security/network bindings.

**Representative files**
- `data/security/SecureKeyStorage.kt`
- `di/SecurityModule.kt`
- `di/NetworkModule.kt`
- `di/NetworkQualifiers.kt`

## SEGMENT 29: Debug & Diagnostics

Owns debug surfaces, issue detectors, and test data helpers.

**Representative files**
- `ui/screens/debug/DebugScreen.kt`
- `ui/screens/debug/DebugViewModel.kt`
- `ui/screens/debug/DebugViewerScreen.kt`
- `domain/debug/ServiceDiagnostics.kt`
- `domain/debug/NotificationSeeder.kt`

## SEGMENT 30: Dependency Injection

Owns Hilt module wiring and app-wide providers.

**Representative files**
- `di/DatabaseModule.kt`
- `di/DaoModule.kt`
- `di/ServiceModule.kt`
- `di/TimeModule.kt`
- `di/DispatchersModule.kt`
- `MainApplication.kt`

## SEGMENT 31: Use Cases

Owns application-layer orchestration use cases.

**Representative files**
- `domain/usecase/receipt/ProcessReceiptUseCase.kt`
- `domain/usecase/expense/CategorizeExpenseUseCase.kt`
- `domain/usecase/budget/CalculateBudgetStatusUseCase.kt`
- `domain/usecase/dashboard/DashboardDataProvider.kt`
- `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`

## SEGMENT 32: Utilities & Shared Helpers

Owns reusable helpers shared across segments.

**Representative files**
- `domain/util/TimeProvider.kt`
- `domain/util/SystemTimeProvider.kt`
- `domain/util/AmountUtils.kt`
- `domain/util/TimePeriodUtils.kt`
- `domain/util/CommonPatterns.kt`
- `domain/util/BKTree.kt`
- `ui/util/ColorExtensions.kt`
- `ui/util/HapticFeedback.kt`

## SEGMENT 33: Configuration, Performance & Accessibility

Owns global configuration, hot-path performance helpers, and accessibility polish.

**Representative files**
- `domain/config/AppConfig.kt`
- `domain/performance/ImageCache.kt`
- `ui/components/CategoryDonutChart.kt`
- `ui/components/SpendingPaceGauge.kt`
- `ui/components/ForecastTimeline.kt`
- `ui/components/BudgetBlockPartyCard.kt`

## SEGMENT 34: Warranty, Subscription & Offers

Owns warranty tracking, subscription management, bill negotiation, and price protection surfaces.

**Representative files**
- `data/repository/WarrantyTrackerRepository.kt`
- `ui/screens/warranty/WarrantyTrackerScreen.kt`
- `data/repository/SubscriptionManagementRepository.kt`
- `ui/screens/subscription/SubscriptionManagementScreen.kt`
- `domain/negotiation/SmartBillNegotiationEngine.kt`
- `ui/screens/negotiation/BillNegotiationScreen.kt`
- `domain/price/PriceProtectionTracker.kt`
- `ui/screens/price/PriceProtectionScreen.kt`

## SEGMENT 35: Savings Optimization & Health

Owns smart savings optimization and financial health score computation.

**Representative files**
- `domain/savings/SmartSavingsEngine.kt`
- `domain/health/FinancialHealthScoreV2.kt`

**Boundary note:** savings prompt persistence and nudges stay in Segment 23.

## SEGMENT 36: Bill Reminders

Owns bill reminder scheduling, due-date tracking, and reminder presentation.

**Representative files**
- `domain/reminder/BillReminderManager.kt`
- `ui/screens/reminder/BillRemindersScreen.kt`

## SEGMENT 37: Spending Challenges

Owns spending challenge creation, progress tracking, and challenge presentation.

**Representative files**
- `domain/challenge/SpendingChallengeManager.kt`
- `ui/screens/challenge/SpendingChallengesScreen.kt`

**Boundary note:** challenge gamification is separate from savings prompts/nudges in Segment 23.

## SEGMENT 38: Receipt Matching

Owns receipt-to-transaction matching and reconciliation UI.

**Representative files**
- `ui/screens/receiptmatching/ReceiptMatchingScreen.kt`

**Boundary note:** OCR capture stays in Segment 4 and item-level AI categorization stays in Segment 5.

---

## Quick checks
- Forecast issues → Segment 1
- Budget rollover issues → Segment 2
- Notification parsing issues → Segment 3
- OCR receipt issues → Segment 4
- AI receipt item issues → Segment 5
- Totals dashboard issues → Segment 10
- Shared expense offset issues → Segment 25
- Lifestyle detection issues → Segment 22
- Smart savings / health score issues → Segment 35
- Bill reminder issues → Segment 36
- Spending challenge issues → Segment 37
- Receipt matching issues → Segment 38
