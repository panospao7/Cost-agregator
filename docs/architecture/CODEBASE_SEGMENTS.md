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
- `domain/forecasting/ForecastDataQuality.kt` — additive quality metadata for forecast inputs
- `domain/forecasting/AccountBalanceProvider.kt` — Interface for resolving current account balance
- `domain/forecasting/NetCashflowBalanceProvider.kt` — 90-day net cashflow fallback implementation of AccountBalanceProvider

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
- `domain/intelligence/CrossSourceDeduplication.kt`
- `data/repository/NotificationRepository.kt`
- `data/repository/NotificationProcessingPipeline.kt`
- `data/repository/ReviewQueueRepository.kt`
- `ui/screens/review/ReviewScreen.kt`

**Boundary note:** this segment stops at parsed/reviewable transactions; OCR belongs to Segment 4 and receipt-item AI belongs to Segment 5.

## SEGMENT 4: Receipt Scanning (OCR) & Receipt Lifecycle

Owns receipt capture, OCR extraction, receipt parsing, scanned-receipt review flow, and the **receipt lifecycle** (Phase 4).

**Representative files**
- `domain/receipt/ReceiptOcrService.kt`
- `domain/receipt/ReceiptParser.kt`
- `domain/receipt/BankStatementParser.kt`
- `domain/receipt/ReceiptSourceType.kt` — Enum: CAMERA, GALLERY, FILE_IMPORT, EMAIL, etc.
- `domain/receipt/ReceiptDocumentType.kt` — Enum: RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, etc.
- `domain/receipt/ReceiptProcessingStatus.kt` — Enum: CAPTURED → DELETED (14 values)
- `domain/receipt/EmailReceiptData.kt` — Structured email receipt data
- `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` — **Single entry point for ALL receipt processing**
- `domain/receipt/lifecycle/ReceiptLinkService.kt` — Centralized receipt-expense linking (join table)
- `domain/receipt/lifecycle/ReceiptAssetStore.kt` — File persistence, hashing, backup manifest
- `domain/receipt/lifecycle/ReceiptInputValidator.kt` — URI / MIME / size validation
- `domain/receipt/lifecycle/ReceiptDuplicateDetector.kt` — 3-signal dedup (hash, text, semantic)
- `domain/receipt/lifecycle/ReceiptAssetStore.computeUriHash()` — pre-OCR exact-hash dedup (2026-05-06)
- `domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt` — Document-type-gated downstream effects
- `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` — Statement-specific processing
- `data/database/entity/ReceiptEvent.kt` — Immutable receipt lifecycle event log (table: `receipt_events`)
- `data/database/dao/ReceiptEventDao.kt` — DAO for receipt lifecycle events
- `data/database/entity/ReceiptExpenseLink.kt` — Many-to-many receipt↔expense join (table: `receipt_expense_links`)
- `data/database/dao/ReceiptExpenseLinkDao.kt` — DAO for receipt-expense links
- `data/repository/ReceiptRepository.kt`
- `ui/screens/receiptscan/ReceiptScanScreen.kt`
- `ui/screens/receiptscan/ReceiptScanViewModel.kt`

**Boundary note:** no item-level AI categorization here.

**Boundary note:** All receipt processing paths now route through `ReceiptLifecycleCoordinator`. The `receipt_events` and `receipt_expense_links` tables are owned by this segment.

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

Owns recurring pattern detection, future recurring item planning, and the **recurring occurrence lifecycle** (Phase 5).

**Representative files**
- `domain/logic/RecurringExpenseEngine.kt`
- `domain/model/RecurringPattern.kt`
- `domain/model/UpcomingItem.kt`
- `domain/recurring/RecurringOccurrenceExpander.kt` — Expands recurrence rules into concrete occurrence candidates
- `domain/recurring/OccurrenceConflictResolver.kt` — Resolves candidates against actual expenses (PLANNED/PAID/SKIPPED)
- `domain/recurring/RecurringPlanProjectionService.kt` — Materialises PlannedExpense rows from occurrences
- `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt` — **Primary entry point** for occurrence generation and management
- `domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` — Rule-level lifecycle (deactivate/delete with atomic cleanup of occurrences, reminders, planned expenses)
- `domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt` — Persists occurrences and creates reminder deliveries
- `data/database/entity/RecurringOccurrence.kt` — Occurrence entity (table: `recurring_occurrences`)
- `data/database/dao/RecurringOccurrenceDao.kt` — DAO for recurring occurrences
- `data/database/entity/RecurringReminderDelivery.kt` — Reminder delivery entity (table: `recurring_reminder_deliveries`)
- `data/database/dao/RecurringReminderDeliveryDao.kt` — DAO for reminder deliveries
- `data/database/entity/RecurringLifecycleEvent.kt` — Lifecycle event log (table: `recurring_lifecycle_events`)
- `data/database/dao/RecurringLifecycleEventDao.kt` — DAO for recurring lifecycle events
- `data/repository/RecurringExpenseRepository.kt`
- `data/repository/PlannedExpenseRepository.kt`

**Boundary note:** The `recurring_occurrences`, `recurring_reminder_deliveries`, and `recurring_lifecycle_events` tables are owned by this segment. The `TransactionLifecycleCoordinator` (Segment 9) auto-links new expenses to PLANNED occurrences via `RecurringLifecycleCoordinator.linkExpenseToOccurrence()` as a best-effort post-creation hook.

**Boundary note:** `RecurringLifecycleCoordinator.unlinkExpenseFromOccurrence()` is the direct unlink path triggered when an expense linked to a PLANNED occurrence is deleted. It resets the occurrence back to PLANNED (2026-05-06).

## SEGMENT 8: Analytics & Insights

Owns statistical analysis, anomaly detection, category insights, and merchant insights.

**Representative files**
- `domain/analytics/InsightsEngine.kt`
- `domain/analytics/AnomalyDetector.kt`
- `domain/analytics/AdvancedAnalyticsEngine.kt`
- `domain/analytics/NormalizedAnalyticsInput.kt` — Canonical analytics input with per-expense normalization + data quality (PR-E11); houses `AnalyticsDataQuality` (with `confidencePenalty`, `confidenceMultiplier`, `warnings`) and `NormalizedExpense` (with `categoryNameSnapshot`)
- `domain/analytics/AnalyticsInputAssembler.kt` — Assembles `NormalizedAnalyticsInput` from raw expense data; houses `AnalyticsInputOptions` data class
- `domain/analytics/DailyBucketEngine.kt` — Builds exact-range daily expense buckets from `NormalizedAnalyticsInput`
- `domain/analytics/BudgetVsActualEngine.kt` — Compares actual category spending vs budget limits from `NormalizedAnalyticsInput`
- `data/repository/AnalyticsRepository.kt`
- `ui/screens/analytics/AnalyticsScreen.kt`
- `ui/components/analytics/StatisticalVisualizations.kt`
- `data/database/entity/PipelineDiagnosticEvent.kt` — Cross-pipeline diagnostic event (table: `pipeline_diagnostic_events`)
- `data/database/dao/PipelineDiagnosticEventDao.kt` — DAO for pipeline diagnostic events

## SEGMENT 9: Core Expense Management

Owns the base expense CRUD surface, shared core expense models, and the **transaction lifecycle** (Phase 3).

**Representative files**
- `data/repository/ExpenseRepository.kt`
- `data/repository/ManualExpenseRepository.kt`
- `ui/screens/transactions/TransactionsScreen.kt`
- `ui/screens/addexpense/AddExpenseSheet.kt`
- `data/database/entity/Expense.kt` (now includes `source` column — ExpenseSource as String)
- `data/database/dao/ExpenseDao.kt`
- `data/database/entity/TransactionEvent.kt` — Immutable lifecycle event log (table: `transaction_events`)
- `data/database/dao/TransactionEventDao.kt` — DAO for lifecycle events
- `domain/transaction/ExpenseSource.kt` — Enum: MANUAL_ENTRY, NOTIFICATION_AUTO_ACCEPT, CSV_IMPORT, BANK_API_SYNC, etc.
- `domain/transaction/LifecycleEventType.kt` — Enum: CREATED, UPDATED, DELETED, etc.
- `domain/transaction/DeduplicationMode.kt` — Enum: STANDARD, STRICT_EXTERNAL_ID, BULK_IMPORT, etc.
- `domain/transaction/CreateExpenseRequest.kt` — Source-neutral creation request (40+ fields)
- `domain/transaction/CreateExpenseResult.kt` — Sealed result (Created, DuplicateSkipped, etc.)
- `domain/transaction/ExpenseUpdates.kt` — Patch-style update model
- `domain/transaction/SideEffectMode.kt` — Enum: IMMEDIATE, DEFER
- `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` — **Single entry point for ALL expense CUD**
- `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt` — Post-creation side effects
- `docs/development/DAO_ACCESS_GUARDRAILS.md` — DAO access policy
- `scripts/guardrails/dao-access-check.kts` — Guardrail enforcement
- `scripts/guards/check_lifecycle_bypasses.kts` — CI guard for direct DAO bypasses
- `util/ImportCoordinator.kt` — CSV/JSON import orchestration (format detection, delegation, result reporting)
- `util/JsonExpenseImporter.kt` — JSON bulk import engine (v1 flat + v2 enriched formats, bulk dedup mode)

**Boundary note:** All expense creation paths now route through `TransactionLifecycleCoordinator`. Direct `insertAtomic` calls are forbidden outside grandfathered files listed in `DAO_ACCESS_GUARDRAILS.md`.

**Boundary note:** The `transaction_events` table is owned by this segment. It is an immutable append-only log; no updates or deletes should be performed on it.

## SEGMENT 10: Dashboard Totals & Widgets

Owns the dashboard home composition and all totals aggregation logic.

**Representative files**
- `data/repository/DashboardRepository.kt`
- `domain/analytics/TotalsAggregationEngine.kt`
- `domain/analytics/DataQualityReport.kt` — Unified data quality contract (totalExpenses, conversionConfidence, warnings, etc.)
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
- `data/privacy/DataRetentionWorker.kt`
- `domain/workers/WorkerSpec.kt`
- `domain/workers/WorkerSpecScheduler.kt`
- `domain/workers/WorkerRunLogger.kt` — Per-run lifecycle tracking (start/success/skipped/retry/failure)
- `domain/workers/WorkerExecutionGuard.kt` — Structured guarded execution with restore check
- `domain/workers/WorkerRegistry.kt` — Centralized registry for all 7 workers (specName + schedule lambda); replaces hardcoded lists
- `di/WorkerModule.kt` — Binds WorkerRunLogger interface → WorkerRunLoggerImpl
- `service/reminder/BillReminderWorker.kt`
- `service/receiptmatching/ReceiptMatchingWorker.kt`
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
- `domain/investment/InvestmentTracker.kt` — houses `InvestmentDataQuality` data class (staleness model: `isPartial`, `staleHoldingCount`, `missingPriceCount`, `lastUpdatedAt`)
- `domain/investment/InvestmentPerformance.kt` — includes `currentValueAggregate` and `costBasisAggregate` MoneyAggregate fields
- `data/database/entity/Investment.kt`
- `data/database/entity/InvestmentValue.kt`
- `data/database/entity/InvestmentTransaction.kt`
- `data/database/dao/InvestmentDao.kt`
- `data/database/dao/InvestmentTransactionDao.kt`
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
- `domain/core/money/MoneyAggregateBuilder.kt` — Builds `MoneyAggregate` from per-expense normalized amounts

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

**Note:** Rate staleness: CurrencyConverter checks rates against 24h threshold (2026-05-06)

## SEGMENT 17: Tax Calculation & Reporting

Owns tax allocation and tax-aware reporting logic.

**Representative files**
- `domain/tax/TaxRateProvider.kt` — Interface for tax-rate data (standard/reduced VAT rates per country+region)
- `data/tax/DemoTaxRateProvider.kt` — @Singleton @Inject seed-data implementation (static EUR rates, LOW confidence)
- `domain/tax/TaxEstimator.kt` — TaxEstimate and TaxYearSummary now carry MoneyAggregate fields (deductibleAggregate, vatAggregate, taxableIncomeAggregate, incomeAggregate, estimatedTaxAggregate) via MoneyAggregateBuilder.fromBuckets()
- `domain/tax/TaxConfiguration.kt`
- `domain/business/BusinessExpenseReportGenerator.kt`
- `domain/export/AccountantReportPdfExporter.kt`
- `data/repository/TaxSettingsRepository.kt`
- `ui/screens/tax/TaxConfigurationScreen.kt`

## SEGMENT 18: Export & Backup

Owns export pipelines, backup/restore flows, and file packaging.

**Representative files**
- `domain/export/CsvCellSanitizer.kt` — Kotlin `object` preventing CSV formula injection (neutralizes =, +, -, @, strips tabs/newlines)
- `domain/export/AccountingExportPolicy.kt` — Export policy validation (single-currency, purchase-only, global dataset checks)
- `data/repository/AccountingExportRepository.kt`
- `data/repository/DatabaseBackupRepositoryImpl.kt`
- `data/backup/BackupVerifier.kt`
- `data/backup/CostbackupBundle.kt`
- `data/backup/RestoreJournal.kt`
- `data/backup/RestoreMaintenanceMode.kt`
- `data/backup/DatabaseReadBarrier.kt` — Operation-level read blocking during restore
- `data/backup/DatabaseWriteBarrier.kt` — Operation-level write blocking during restore
- `domain/backup/BackupPrivacyMode.kt` — enum defining 4 backup privacy levels
- `domain/backup/DatabaseBackupRepository.kt`
- `domain/export/AccountingExporters.kt`
- `ui/screens/export/ExportOptionsScreen.kt`
- `di/BackupRepositoryModule.kt`

## SEGMENT 19: Location Enrichment

Owns geocoding, location correction, and map-based enrichment.

**Representative files**
- `domain/location/LocationResolver.kt`
- `domain/location/LocationInsightsEngine.kt`
- `domain/location/LocatedMoneyExpense.kt` — Multi-currency-safe expense for heatmap/insight engines (PR-E6)
- `domain/location/SpendingHeatmapEngine.kt`
- `data/location/CompositeGeocodingService.kt`
- `ui/screens/map/SpendingMapScreen.kt`
- `domain/location/GeoCoordinate.kt` — Validated coordinate value class rejecting NaN/Infinity/out-of-range/null-island

**Boundary note:** `data/location/LocationBackfillWorker.kt` is orchestrated by this segment, but owned with startup/runtime in Segment 12.

## SEGMENT 20: AI Platform, Assistant & Follow-Through

Owns the app-wide AI platform surface: policy, assistant sheet, AI settings, provider wiring, and briefing follow-through.

**Representative files**
- `di/AiModule.kt`
- `domain/ai/policy/AiPolicy.kt`
- `domain/ai/HybridRouter.kt` — Consolidates routing logic previously duplicated across provider services
- `ui/screens/assistant/AssistantSheet.kt`
- `domain/ai/model/AssistantHistoryMode.kt` — Enum (OFF/REDACTED/RAW) for conversation history redaction
- `ui/screens/aisettings/AiSettingsScreen.kt`
- `data/ai/provider/DefaultAiEnvironmentMonitor.kt`
- `data/ai/provider/StrictAiJsonParsing.kt`
- `data/ai/provider/DashboardBriefingPromptFormatter.kt`
- `data/ai/provider/DashboardBriefingResponseParser.kt`
- `data/ai/provider/CloudDashboardBriefingService.kt`
- `data/ai/provider/OnDeviceDashboardBriefingService.kt`
- `data/ai/provider/HybridDashboardBriefingService.kt`
- `data/ai/provider/NoOpDashboardBriefingService.kt`
- `data/ai/provider/SmartReceiptAssistService.kt`
- `data/ai/provider/internal/CloudPiiSanitizer.kt`
- `domain/engine/DashboardFollowThroughEngine.kt`
- `data/repository/RecommendationRepository.kt`
- `ui/components/RecommendationCard.kt`

**Boundary note:** `HybridRouter` (`domain/ai/`) replaces duplicated routing logic that was previously spread across individual provider implementations. `data/ai/provider/internal/CloudPiiSanitizer` is consumed by `DefaultCloudPayloadRedactor` in Segment 28.

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
- `domain/groups/GroupLifecycleCoordinator.kt` — @Singleton domain coordinator wrapping GroupTransactionCoordinator (7 methods, 8 invariants)
- `domain/groups/GroupBalanceCalculator.kt` — @Singleton @Inject per-member net balance calculator (paidTotal, owedShareTotal, settlementsPaid/Received, netBalance)
- `domain/groups/usecase/AddGroupExpenseUseCase.kt`
- `domain/groups/SharedExpenseManager.kt`
- `data/database/dao/GroupSettlementDao.kt`
- `data/database/dao/GroupLifecycleEventDao.kt` — Group lifecycle event audit log
- `data/database/entity/GroupSettlementEntity.kt`
- `data/database/entity/GroupLifecycleEventEntity.kt` — Immutable group lifecycle event log (table: `group_lifecycle_events`)
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
- `domain/naturallanguage/NaturalLanguageExpenseQueryRepository.kt` — SearchCursor keyset pagination cursor; QueryDataQuality flags
- `domain/ai/model/FinancialQueryDataQuality.kt` — Partial-conversion metadata for query results (isPartial, staleRateCount, missingRateCount)
- `domain/ai/model/ExtractedAmountFilter.kt` — Currency-aware amount filter extracted from NL queries (PR-E8)
- `ui/screens/naturallanguage/NaturalLanguageSearchScreen.kt`

## SEGMENT 27: Carbon Footprint Tracking

Owns emissions calculation and sustainability-facing reporting.

**Representative files**
- `domain/carbon/CarbonFootprintCalculator.kt`
- `ui/screens/carbon/CarbonFootprintScreen.kt`

## SEGMENT 28: Security & API Key Management

Owns encrypted key storage and security/network bindings.

**Representative files**
- `data/security/BankTokenCipher.kt`
- `data/security/SecureKeyStorage.kt`
- `di/SecurityModule.kt`
- `di/NetworkModule.kt`
- `di/NetworkQualifiers.kt`
- `domain/privacy/CloudPayloadRedactor.kt` — unified cloud AI payload redaction interface
- `data/privacy/DefaultCloudPayloadRedactor.kt` — wraps CloudPiiSanitizer (ARCH-04 Stage 1)
- `domain/privacy/RawStorageMode.kt` — Enum: STORE_RAW / STORE_REDACTED / STORE_METADATA_ONLY / DO_NOT_STORE
- `domain/privacy/RawContentSanitizer.kt` — Write-time sanitizer applying RawStorageMode to OCR/email content
- `domain/privacy/EffectiveCloudAiPolicy.kt` — Resolves effective cloud AI policy from privacy + AI settings, used by hybrid services for pre-flight checks
- `domain/privacy/PrivacyBlocked.kt` — Sealed interface standardizing privacy-denied states (CloudAiDisabled, ReceiptImageUploadDisabled, etc.); returned by all privacy gates
- `domain/privacy/PrivacyDecision.kt` — Now includes `FailClosed(reason)` variant; `blocksExecution()` and `reason()` methods; 30+ callers use for fail-closed propagation
- `ui/components/PrivacyBlockedCard.kt` — Reusable Compose card for privacy-blocked state display

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
- `di/ApplicationScope.kt`
- `di/BackupRepositoryModule.kt`
- `di/DatabaseModule.kt`
- `di/DaoModule.kt`
- `di/DispatchersModule.kt`
- `di/PrivacyModule.kt`
- `di/ServiceModule.kt`
- `di/TimeModule.kt`
- `di/WorkerModule.kt` — Binds WorkerRunLogger interface → WorkerRunLoggerImpl
- `MainApplication.kt`

**Boundary note:** `BackupRepositoryModule.kt` is also listed under Segment 18 (Export & Backup) as it cross-cuts DI wiring with backup infrastructure. `PrivacyModule.kt` cross-cuts with Segment 28 (Security & API Key Management).

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
- `domain/util/TimeProvider.kt` — Single source of "now" (interface, injected into 50+ classes; also used by `TransactionLifecycleCoordinator` and `TransactionSideEffectDispatcher`)
- `domain/util/SystemTimeProvider.kt` — Production clock implementation
- `domain/util/AmountUtils.kt`
- `domain/util/TimePeriodUtils.kt` — Canonical calendar boundary math; 7 new helpers in Phase 2 (parseMonthKeyToRange, getLastNCalendarDaysRange, getLastNCompleteDaysRange, getTrailingElapsedRange, getDayIndexForSparkline, toPeriodRange, daysBetween); `getLastNDaysRange` deprecated
- `domain/util/DateFormatterUtils.kt` — 13 convenience methods, all accept explicit timestamps (no `Instant.now()`)
- `domain/util/CommonPatterns.kt`
- `domain/util/BKTree.kt`
- `domain/core/time/PeriodRange.kt` — Typed half-open period model `[startInclusive, endExclusive)`
- `domain/core/time/PeriodKind.kt` — Semantic period kind enum (TODAY, THIS_WEEK, THIS_MONTH, etc.)
- `domain/core/validation/EntityTimeValidation.kt` — Cross-entity time constraint validation
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
- `domain/negotiation/MarketRateProvider.kt` — Interface for market-rate data (new negotiation/ package)
- `data/negotiation/StaticMarketRateProvider.kt` — @Singleton @Inject seed-data implementation
- `ui/screens/negotiation/BillNegotiationScreen.kt`
- `data/database/dao/WarrantyLifecycleEventDao.kt`
- `data/database/entity/WarrantyLifecycleEvent.kt`
- `domain/price/PriceProtectionTracker.kt`
- `ui/screens/price/PriceProtectionScreen.kt`

## SEGMENT 35: Savings Optimization & Health

Owns smart savings optimization and financial health score computation.

**Representative files**
- `domain/savings/AutomatedSavingsRuleEngine.kt`
- `domain/savings/SavingsGamificationEngine.kt`
- `domain/savings/SmartSavingsEngine.kt`
- `domain/health/FinancialHealthCalculator.kt`
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

Owns receipt-to-transaction matching and reconciliation UI. Link persistence goes through `ReceiptLinkService` (Segment 4).

**Representative files**
- `domain/receiptmatching/ReceiptTransactionMatcher.kt`
- `ui/screens/receiptmatching/ReceiptMatchingScreen.kt`

**Boundary note:** OCR capture stays in Segment 4 and item-level AI categorization stays in Segment 5. Link mutations via `ReceiptLinkService` are owned by Segment 4.

---

## Segment Quick Reference

File-to-segment mapping for all 38 segments:

| # | Segment | Key pattern / issue |
|---|---|---|
| 1 | Forecasting & Runway | `domain/forecasting/`, `FinancialWeather`, `AccountBalanceProvider`, `NetCashflowBalanceProvider` |
| 2 | Budget Management | `domain/budget/`, `BudgetScreen` |
| 3 | Notification Capture, Parsing & Review | `domain/parser/`, `NotificationRepository`, `ReviewQueueRepository` |
| 4 | Receipt Scanning (OCR) & Lifecycle | `domain/receipt/`, `receipt_events`, `receipt_expense_links`, OCR lifecycle |
| 5 | AI Receipt Item Categorization | `domain/ai/usecase/CategorizeReceiptItems`, `ReceiptItemCategorization` |
| 6 | Merchant Categorization | `domain/categorization/`, `MerchantCanonicalizer`, `HybridExpenseClassifier` |
| 7 | Recurring Expenses | `domain/recurring/`, `recurring_occurrences`, recurring lifecycle |
| 8 | Analytics & Insights | `domain/analytics/`, `InsightsEngine`, `AnomalyDetector`, `PipelineDiagnosticEvent` |
| 9 | Core Expense Management | `domain/transaction/`, `TransactionLifecycleCoordinator`, `transaction_events`, expense CRUD, `ImportCoordinator`, `JsonExpenseImporter` |
| 10 | Dashboard Totals & Widgets | `TotalsAggregationEngine`, `DashboardRepository`, totals UI |
| 11 | Notifications & Alerts | `NotificationService` |
| 12 | Startup & Background Runtime | `startup/`, workers, `AppStartupCoordinator`, `WorkerRunLogger`, `WorkerExecutionGuard`, `WorkerModule` |
| 13 | Cash Flow Planning | `domain/cashflow/`, `CashFlowCalculator` |
| 14 | Bank Integration | `domain/bank/`, `BankConnection` |
| 15 | Investment Tracking | `domain/investment/`, `InvestmentTracker`, `InvestmentDataQuality`, `InvestmentPerformance` |
| 16 | Currency & Exchange | `domain/core/money/`, `CurrencyConverter`, `MultiCurrencyRepository` |
| 17 | Tax Calculation & Reporting | `domain/tax/`, `TaxEstimator`, `TaxRateProvider`, `DemoTaxRateProvider` |
| 18 | Export & Backup | `domain/backup/`, `data/backup/`, `AccountingExport`, `CsvCellSanitizer`, `DatabaseReadBarrier`, `DatabaseWriteBarrier` |
| 19 | Location Enrichment | `domain/location/`, `CompositeGeocodingService` |
| 20 | AI Platform, Assistant & Follow-Through | `domain/ai/policy/`, `AiModule`, assistant, briefing |
| 21 | Enhanced Split Transactions | `domain/split/`, `VisualSplitEditor`, `SplitTemplate` |
| 22 | Lifestyle Inflation Detector | `domain/lifestyle/`, `LifestyleInflationDetector` |
| 23 | Savings Prompts & Nudges | `domain/usecase/savings/`, `PromptState` |
| 24 | Shared Expense Groups | `domain/groups/`, `GroupsRepository`, `GroupSettlementDao`, `GroupSettlementEntity`, `GroupLifecycleEventDao`, `GroupLifecycleEventEntity`, `GroupBalanceCalculator` |
| 25 | Shared Expense Budget Offset | `SharedExpenseBudgetOffsetEngine` |
| 26 | Natural Language Search | `domain/naturallanguage/` |
| 27 | Carbon Footprint Tracking | `domain/carbon/` |
| 28 | Security & API Key Management | `data/security/`, `SecurityModule`, `CloudPayloadRedactor`, `RawStorageMode`, `RawContentSanitizer`, `EffectiveCloudAiPolicy` |
| 29 | Debug & Diagnostics | `DebugScreen`, `ServiceDiagnostics` |
| 30 | Dependency Injection | `di/` modules, `MainApplication.kt`, `WorkerModule` |
| 31 | Use Cases | `domain/usecase/` |
| 32 | Utilities & Shared Helpers | `domain/util/`, `domain/core/time/` |
| 33 | Configuration, Performance & Accessibility | `domain/config/`, accessibility components |
| 34 | Warranty, Subscription & Offers | `WarrantyTracker`, `SubscriptionManagement`, `PriceProtection` |
| 35 | Savings Optimization & Health | `domain/savings/`, `FinancialHealthScore` |
| 36 | Bill Reminders | `domain/reminder/`, `BillReminderManager` |
| 37 | Spending Challenges | `domain/challenge/`, `SpendingChallengeManager` |
| 38 | Receipt Matching | `domain/receiptmatching/`, `ReceiptTransactionMatcher` |

### Quick checks
- Forecast issues → Segment 1
- Budget rollover issues → Segment 2
- Notification parsing issues → Segment 3
- OCR / receipt lifecycle issues → Segment 4
- AI receipt item issues → Segment 5
- Merchant categorization issues → Segment 6
- Recurring lifecycle issues → Segment 7
- Analytics & insight issues → Segment 8
- Transaction lifecycle issues → Segment 9
- Totals dashboard issues → Segment 10
- Startup / background worker issues → Segment 12
- Multi-currency issues → Segment 16
- Privacy settings issues → Segment 28 / Segment 6 (merchant cat.)
- Smart savings / health score issues → Segment 35
- Bill reminder issues → Segment 36
- Spending challenge issues → Segment 37
- Receipt matching issues → Segment 38
