# Master Issue Registry — ExpenseTracker Codebase

> Generated from 48 batch verification reports. Each issue is traced back to its source batch.
> Fix order: Section A (Universal) → Section B (Pipeline) → Section C (Dependencies) → Section D (Isolated)
> Updated based on the comprehensive Phase 2 audit; markers below reflect items verified as resolved, partially resolved, or still open.

---

## Section A: Universal Architectural Epics

### A.1: effectiveAmount vs amount Inconsistency
**Batches affected:** 01, 02, 03, 05, 12, 16, 17, 29, 32, 33, 36, 37, 38, 41, 45, 46
**Severity:** CRITICAL
**Description:** Raw `amount` is used instead of `effectiveAmount` (user-owned share) across analytics SQL, dashboard math, budget calculations, business reports, currency conversions, tax estimates, receipt matching, challenge scoring, income tracking, and merchant aggregates. Shared-expense users see overstated spending in multiple surfaces.
**Affected files:** `ExpenseDao.kt`, `ExpenseRepository.kt`, `AdvancedAnalyticsDashboard.kt`, `AdvancedAnalyticsEngine.kt`, `BudgetRepository.kt`, `SharedBudgetManager.kt`, `MultiCurrencyRepository.kt`, `BusinessExpenseReportGenerator.kt`, `RecurringIncomeTracker.kt`, `SpendingThresholdCalculator.kt`, `TaxEstimator.kt`, `ReceiptTransactionMatcher.kt`, `SpendingChallengeManager.kt`, `InsightsEngine.kt`, `ExpenseWithCategory.kt`, `TransactionsScreen.kt`, `FinancialWeatherRepository.kt`, `BudgetForecastingEngine.kt`, `CashFlowCalculator.kt`, `AccountingExportRepository.kt`, `TotalsAggregationEngine.kt`, `SynthesisEngine.kt`
**Suggested fix:** Centralize the SQL effective-amount `CASE` expression in one shared DAO helper. Audit every aggregate query, dashboard computation, budget check, and report to use this helper. Add regression tests that verify shared-expense rows produce correct user-owned totals.

**[RESOLVED BY A.1]**

### A.2: Domain/Data Layer Boundary Violations
**Batches affected:** 24, 34, 46, 47
**Severity:** HIGH
**Description:** Multiple domain model files import Room entities directly: `BlockPartyDay` exposes `data.database.entity.Expense`, `DashboardExpenseMapper` round-trips `DashboardExpense` back to incomplete `Expense` entities (losing shared-expense metadata), AI domain models import `Category`, `PendingReview`, `TransactionType`, and `AiArtifactEntity`. This couples domain contracts to persistence schema and causes data loss on reconstruction.
**Affected files:** `BlockPartyDay.kt`, `DashboardExpenseMapper.kt`, `AiArtifactPresentation.kt`, `ReceiptItemCategorizationModels.kt`, `AiArtifactRepository.kt`, `FinancialQueryModels.kt`, `CaptureAssistModels.kt`, `SemanticDuplicateModels.kt`, `ReviewPriorityModels.kt`, `DomainTransactionFilter.kt`, `NarrativeGenerator.kt`
**Suggested fix:** Introduce domain DTOs for all cross-boundary data. Move entity mappers to the data/adapter layer. Stop round-tripping through `Expense` — keep `DashboardExpense` downstream or extend it with ownership/share fields.

**[RESOLVED - Domain boundary violations removed from NarrativeGenerator, SynthesisEngine, DashboardDataProvider, DashboardBriefingInputBuilder]**

### A.3: Non-deterministic Default Values (System.currentTimeMillis, UUID.randomUUID)
**Batches affected:** 01, 07, 10, 16, 17, 24, 34, 36, 38, 40, 41, 47
**Severity:** HIGH
**Description:** `System.currentTimeMillis()` is used directly instead of the injected `TimeProvider` in: review priority scoring, notification ID generation, challenge ID generation, filter correlation IDs, cache timestamps, worker day keys, investment timestamps, and feature extraction. This makes tests non-deterministic, breaks clock injection for time-travel testing, and causes midnight boundary bugs when different components capture `now` at different moments.
**Affected files:** `ReviewPriorityModels.kt`, `NotificationIdGenerator.kt`, `SpendingChallengeManager.kt`, `DomainTransactionFilter.kt`, `TransactionFilter.kt`, `DailyBriefingWorker.kt`, `InvestmentTracker.kt`, `FeatureExtractor.kt`, `ConfidenceRouter.kt`, `ReviewExplanationInputBuilder.kt`, `DashboardBriefingInputBuilder.kt`, `AddGroupExpenseUseCase.kt`, `SharedExpenseBudgetOffsetEngine.kt`, `SharedExpenseManager.kt`
**Suggested fix:** Inject `TimeProvider` everywhere. Replace all `System.currentTimeMillis()` calls with `timeProvider.now()`. For IDs, use UUID or auto-generated DB keys instead of timestamp-based IDs.

**[RESOLVED - Remaining wall-clock reads removed from scoped Phase A surfaces]**

### A.4: Duplicate Detection Logic Inconsistencies
**Batches affected:** 05, 07, 12, 33, 41, 43
**Severity:** HIGH
**Description:** Duplicate detection is currency-blind across notification auto-accept, statement import, and review approval. The 24-hour cross-source dedupe window is too broad for legitimate repeat purchases. DB-level dedupe uses a ~5-minute window. Candidate filtering ignores transaction type, so deposits/transfers can match as purchase duplicates. Dedupe key generation uses locale-sensitive amount formatting.
**Affected files:** `DetectDuplicateExpenseUseCase.kt`, `Expense.generateDedupeKey()`, `ExpenseDao.kt`, `ExpenseRepository.kt`, `NotificationProcessingPipeline.kt`, `ReceiptRepository.kt`, `ReviewQueueRepository.kt`, `CrossSourceDeduplication.kt`, `DedupeJudgeInputBuilder.kt`
**Suggested fix:** Include currency in the dedupe key. Centralize duplicate policy (window, merchant normalization, amount tolerance, scoring) behind one shared component. Filter candidates by compatible transaction type. Make dedupe key generation locale-invariant.

**[RESOLVED BY A.4]**

### A.5: Time Boundary / Calendar Arithmetic Inconsistencies
**Batches affected:** 01, 02, 03, 04, 10, 16, 17, 30, 32, 36, 37, 41, 43
**Severity:** HIGH
**Description:** Week boundaries use locale-dependent `Calendar.firstDayOfWeek` instead of standardized Monday-start. Month boundaries use `+30 days` instead of calendar month math. Day indexing uses millisecond division causing DST errors. End boundaries use `23:59:59` instead of start-of-next-day exclusive. Reactive flows capture time windows once and never refresh on rollover.
**Affected files:** `FinancialHealthCalculator.kt`, `BudgetCalculator.kt`, `HistoricalSpendingDistribution.kt`, `TransactionFilterSheet.kt`, `DashboardContractsAdapter.kt`, `BudgetRepository.kt`, `LocationBackfillWorker.kt`, `BillReminderManager.kt`, `RecurrenceCalculator.kt`, `RecurringExpenseEngine.kt`, `TimePeriodUtils.kt`, `AdvancedAnalyticsDashboard.kt`, `SpendingPaceCalculator.kt`
**Suggested fix:** Centralize all period math through `TimePeriodUtils`. Use calendar-aware day/month addition. Use exclusive end boundaries consistently. Drive long-lived reactive flows from a rollover-aware clock/ticker.
**[RESOLVED - Scoped ad-hoc day-boundary logic replaced with calendar-safe math]**

### A.6: Mixed Numeric Types (Float vs Double for financial data)
**Batches affected:** 24, 36, 46, 47
**Severity:** MEDIUM
**Description:** Financial domain models mix `Float` and `Double`: `SpendingSummary` uses `Double` totals with `Float` histories, `BudgetStatusSnapshot` stores `percentUsed` as `Float` while amounts are `Double`, `CategoryBreakdown` uses `Float` percentages, `MonteCarloBudgetImpact` uses `Float` for risk fields. This introduces avoidable precision loss in financial calculations.
**Affected files:** `SpendingSummary.kt`, `BudgetStatusSnapshot.kt`, `CategoryBreakdown.kt`, `DashboardCategoryBreakdown.kt`, `MonteCarloBudgetImpact.kt`, `FinancialForecast.kt`
**Suggested fix:** Use `Double` consistently in all domain/repository models. Convert to `Float` only at chart/UI rendering boundaries.
- [RESOLVED BY A.6]

### A.7: Fire-and-Forget Coroutine Anti-Pattern
**Batches affected:** 02, 05, 07, 10, 16, 17, 18, 19, 21, 35, 36, 42, 45, 48
**Severity:** HIGH
**Description:** `catch (e: Exception)` blocks swallow `CancellationException` across analytics, AI use cases, workers, and services. This converts coroutine cancellation into silent failures, prevents proper structured concurrency, and causes stale FAILED artifacts to be persisted for cancelled jobs. Broad exception catching also masks real errors.
**Affected files:** `BudgetMonitor.kt`, `CategorizationAssistInputBuilder.kt`, `InterpretFinancialQueryUseCase.kt`, `DailyBriefingWorker.kt`, `SuggestReceiptExtractionUseCase.kt`, `SuggestCategoryFallbackUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `GenerateDashboardBriefingUseCase.kt`, `InsightsEngine.kt`, `AnomalyAlertOrchestrator.kt`, `ReceiptOcrService.kt`, `WarrantyExpirationWorker.kt`
**Suggested fix:** Re-throw `CancellationException` before any generic catch block. Use `try/catch` instead of `runCatching { }.getOrElse { }` in suspend functions. Log non-cancellation exceptions explicitly.
- [RESOLVED BY A.7]

### A.8: Shared Mutable State / Thread Safety Gaps
**Batches affected:** 01, 02, 07, 10, 11, 15, 25, 27, 28, 34, 36, 41, 42, 45
**Severity:** HIGH
**Description:** `SimpleDateFormat` instances stored as mutable singleton state are used across concurrent calls (warranty extraction, export formatters, dashboard briefing builders). In-memory caches use `mutableMapOf` without `Mutex` or `ConcurrentHashMap`. Singleton mutable state (`lastUsedImageInput`, `processedNotifications`) is shared across concurrent requests.
**Affected files:** `WarrantyTextExtractor.kt`, `AccountingExporters.kt`, `DashboardBriefingInputBuilder.kt`, `SpendingThresholdCalculator.kt`, `BudgetMonitor.kt`, `HybridReceiptAssistService.kt`, `TransactionClassifier.kt`, `GroupTransactionCoordinator.kt`, `RecommendationStateManager.kt`, `ServiceDiagnostics.kt`, `LogSanitizer.kt`, `LocationResolver.kt`
**Suggested fix:** Replace `SimpleDateFormat` with `java.time.DateTimeFormatter` (immutable). Protect cache access with `Mutex` or use `ConcurrentHashMap`. Remove shared mutable state from service classes and return metadata in result objects.
- [RESOLVED BY A.8]

### A.9: Hidden Data Truncation / DAO Default Limits
**Batches affected:** 01, 02, 03, 05, 14, 27, 32, 33, 37, 39, 41, 44, 45
**Severity:** CRITICAL
**Description:** Multiple DAO queries default to `LIMIT 2000` or `LIMIT 500` rows. Consumers across analytics, forecasting, budgeting, export, and business reports never page through results or detect truncation. Users with large histories see silently incomplete data — analytics show wrong totals, forecasts miss recurring patterns, exports are truncated, and tax calculations use partial data.
**Affected files:** `ExpenseDao.kt`, `ExpenseRepository.kt`, `BudgetRepository.kt`, `BudgetForecastingEngine.kt`, `BudgetAutopilotEngine.kt`, `SharedBudgetManager.kt`, `CarbonFootprintCalculator.kt`, `CashFlowCalculator.kt`, `AccountingExportRepository.kt`, `FinancialWeatherRepository.kt`, `MultiCurrencyRepository.kt`, `TaxEstimator.kt`, `SpendingThresholdCalculator.kt`, `RecurringExpenseRepository.kt`
**Suggested fix:** Add an uncapped or explicitly paged variant of each query. Audit every consumer to either page through results or use aggregate SQL (SUM/COUNT/GROUP BY) instead of fetching full row sets. Add a `TruncationDetected` error state to alert when capped queries are used inappropriately.
**[RESOLVED - Pending-review reads now use an explicit split: uncapped `getAllPendingReviews()` / `getPendingUncappedFlow()` for full reads, and separate limit-required batch APIs for bounded reads; live zero-arg callers no longer truncate at 100]**

### A.10: Transaction Type Blindness
**Batches affected:** 02, 03, 05, 32, 33, 37, 39, 41, 42, 45
**Severity:** HIGH
**Description:** Multiple aggregation pipelines treat all `Expense` rows as spending, ignoring `TransactionType` (DEPOSIT, TRANSFER, WITHDRAWAL). Deposits feed spending heatmaps, transfers inflate budget tracking, refunds appear as purchases, and business reports include non-deductible movements. This compounds with A.1 (effectiveAmount) to produce systematically wrong numbers.
**Affected files:** `SpendingHeatmapEngine.kt`, `BudgetRepository.kt`, `CashFlowCalculator.kt`, `BusinessExpenseReportGenerator.kt`, `TaxEstimator.kt`, `FinancialHealthCalculator.kt`, `CategoryInsightEngine.kt`, `TotalsAggregationEngine.kt`, `RecurringIncomeTracker.kt`
**Suggested fix:** Add a canonical `isSpending()` filter at the DAO or repository level. Audit every aggregation pipeline to filter by transaction type. Ensure deposits/transfers are excluded from spending metrics but included in cash flow.
- [RESOLVED BY A.10]

---

## Section B: Domain-Specific Pipelines

### B.1: AI/ML Pipeline
**Batches:** 06, 07, 08, 09, 10, 25, 26, 34, 35, 36

**[RESOLVED - Dashboard and transaction insight prompt wording/redaction now live in data-layer prompt formatting, not domain builders]**

- **CRITICAL:**
  - Financial query interpretation loses category/period/alias filters end-to-end: builder prepares them, parser drops them, executor widens queries (B07, B09, B25, B26, B35)
  - Categorization assist leaks raw merchant history and category labels to cloud when redaction enabled (B07, B25, B34)

> ⚠️ **STOP-SHIP**: The CRITICAL items in this section represent active privacy/GDPR violations. Categorization assist leaks raw merchant history to cloud when redaction is enabled. These must be fixed before any production release.

- **HIGH:**
  - Smart receipt retry chain commits to one route family upfront; cloud failures don't fall through to on-device (B10, B25)
  - AI dedupe judge skipped when exactly one candidate exists — the most common duplicate-pair shape (B07, B08, B35)
  - Cache reuse ignores `sourceHash` in dashboard briefing and review explanation, returning stale artifacts (B07, B08, B35, B36)
  - `CategorizeReceiptItemsUseCase` sets receipt to `ANALYZING` but null-service path returns Error without restoring status, leaving receipt stuck (B35)
  - Transaction insight generation fabricates a `DashboardBriefingInput`, bypasses redaction policy, and logs raw merchant/amount text (B35)
  - `ExecuteFinancialQueryUseCase` aggregates from capped 500-row page, drops multi-value filters with `singleOrNull()`, hardcodes EUR (B07, B35)
  - On-device notification parser sets `transferDirection` for purchases, which `ParsedTransaction` rejects, dropping valid parses (B26)
  - On-device receipt scoring floors every overlap to `0.3..0.7`, preventing keyword fallback for unknown items (B26)
  - Routing asymmetry: CLOUD mode skips on-device-only capabilities, ON_DEVICE mode skips cloud fallback (B34)
  - `InterpretFinancialQueryUseCase` early special-case returns collapse richer queries into plain TOTAL intents — merchant/category/grouping/metric cues discarded (B07)
  - `ExecuteFinancialQueryUseCase` query totals/breakdowns aggregate raw amounts across currencies with no conversion/separation — hardcoded `EUR` labels hide mixed-currency math bug (B07)
  - `CloudDedupeJudgeService`/`OnDeviceDedupeJudgeService` model-emitted `matchedTargetType`/`matchedTargetId` trusted without bounds-checking against candidate set (B08)
  - `ExecuteFinancialQueryUseCase.executeList()` reports `previewCount = preview.size` from capped 500-row query — underreports total matches (B35)

- **MEDIUM:**
  - Hybrid services re-route inside the hybrid wrapper, causing artifact metadata drift from actual executor (B10, B35)
  - On-device JSON parsing uses greedy `first '{'` / `last '}'` extraction across all providers (B09, B26)
  - Only query interpretation applies timeout; other on-device providers can block indefinitely (B09, B26)
  - `AiSettings()` defaults enable AI features but repository hydration defaults them off; startup state can transiently behave as opt-in (B06, B34)
  - Artifact persistence collapses `DETERMINISTIC_FALLBACK`/`DISABLED` into `AiMode.AUTO`, losing route provenance (B06, B34)
  - Cloud retry policy ignores server `Retry-After` headers (B10)
  - `CloudPiiSanitizer` truncates before redacting, allowing PII fragments at boundaries to survive (B10, B25)
  - Review-explanation and category-assist error propagation collapses all failures into `null` (B08)
  - Domain builders import `data.ai.provider.internal` sanitization helpers, creating upward dependencies (B35)
  - `merchantClarity` treats only exact string `"Unknown"` as unclear — other producers emit `"Unknown Merchant"` etc., producing different priority scores for same condition (B34-missed)
  - AI policy/capability coupling: `WARRANTY_EXTRACTION` controlled by `receiptAssistEnabled` — turning off receipt assist silently disables warranty extraction (B34-missed)
  - `MapFinancialQueryToNavigationUseCase.singleOrNull()` silently drops multi-value filters — drill-down opens broader list (B08)
  - `DefaultAiCapabilityRouter` cloud-mode routing for on-device-only capabilities skips viable on-device providers and drops to deterministic fallback (B25)
  - `HybridReceiptAssistService`/`CloudReceiptAssistService` `usedImageInput=true` reported when cloud actually fell back to text-only — metadata accuracy (B25)
  - `CloudReceiptItemCategorizationService`/`CloudWarrantyExtractionService` two provider-local sanitizers repeat truncate-before-redact mistake (B25)
  - `OnDeviceReviewPriorityScorer` batch scoring re-reads `reviewQueueRepository.getPendingReviews().first()` for every review — O(n²) duplicate checks per batch (B26)
  - `OnDeviceQueryInterpretationService` structured-query schema has no `transactionTypes` field — transaction-type filters cannot be expressed (B26)
  - `GenerateDashboardBriefingUseCase`/`HybridDashboardBriefingService` same double-routing flaw as review explanations: artifact metadata from one route, hybrid service re-routes and can execute different route (B10)
  - `SuggestCategoryFallbackUseCase` broad `catch(Exception)` swallows `CancellationException` (B36)

- **LOW:**
  - Correlation IDs use only 8 chars of UUID (B10)
  - `SimpleDateFormat` as shared mutable state in `DashboardBriefingInputBuilder` (B07)
  - Disabled-route reasons interpolate raw enum names (B06)
  - `highestPriorityMessage` is first-match not severity-ranked (B06)
  - `ReviewPriorityScorer` batch scoring pre-computes `duplicateRisk` but `calculateBaseScore()` uses placeholder `0.5f` — single-item and batch scoring diverge for same review (B34-missed)

### B.2: Budget/Forecasting Pipeline
**Batches:** 02, 04, 05, 22, 27, 28, 32, 36, 37, 40, 42, 48

**[RESOLVED - SharedBudgetManager member contributions now fail fast instead of returning fabricated data]**

- **CRITICAL:**
  - `BudgetCalculator.calculatePeriodRange()` keeps `startDate` as start for rolling budgets — active window never advances; monthly uses `+30 days` approximation (B02, B37)
  - `BudgetForecastingEngine` projects `forecastPeriodDays` (default 30) instead of actual remaining budget period duration (B02, B37)
  - `CarbonFootprintCalculator.calculateCarbonFootprint()` collects a Room `Flow` inside one-shot suspend — can hang indefinitely (B37, B42)
  - `BudgetCalculator.calculatePeriodWindow(period, anchorDate)` always uses `timeProvider.now()` — callers cannot derive historical/next windows reliably (B02)

- **HIGH:**
  - `overspendProbability` multiplied by `confidence`, so deterministic overspend can look safe just because model is uncertain (B02, B37)
  - `BudgetAutopilotEngine` aggregate totals blindly sum every active budget — overall + category budgets double-count (B02, B37)
  - `SharedBudgetManager` queries month-to-date, filters overall budgets incorrectly (`categoryId == null` only matches uncategorized), sums raw `amount` (B02, B37)
  - `BudgetMonitor` mutable singleton state (`lastCheckTime`, `cachedStatuses`, `cacheTimestamp`) has no synchronization; `CancellationException` swallowed (B02, B37)
  - `BudgetMonitor.cleanup()` cancels singleton scope on `onStop()`, later `checkBudgets()` launches into canceled scope (B02, B22)
  - `FinancialStressForecastEngine` calls current-month net cashflow "current balance", uses budget caps as income fallback (B04, B40)
  - Daily discretionary sampling excludes zero-spend days, biasing every future day toward spending (B04, B40)
  - `RecurringExpenseEngine` emits stale `nextExpectedDate` — not rolled forward to next future occurrence (B04, B40)
  - Hidden truncation: forecasting/autopilot/shared-budget/carbon/cashflow all inherit DAO default `limit = 2000` or `500` (B37)
  - `CashFlowCalculator` treats `DEPOSIT || amount < 0` as income, ignoring `transferDirection` — transfers inverted (B37)
  - `BusinessExpenseReportGenerator` sums raw `amount`, includes deposits/transfers, omits effective-amount (B37)
  - `BudgetRecommendationEngine.potentialSavings` can go negative (B02)
  - `CalculateFinancialForecastUseCase` feeds `SynthesisEngine` placeholder inputs (empty history, hardcoded `ON_PACE`, all goals `TRACKING`) (B05, B48)
  - `MonthlySavingsSweepUseCase` hardcodes `knownUpcoming = 0.0` in Monte Carlo input (B05)
  - Goal allocations never capped by remaining gap — can overfund past targets (B05)
  - `observeDashboardExpenses()` snapshots month once with `System.currentTimeMillis()` — stale after rollover (B05, B32, B48)
  - `ComputeMoneyRadarUseCase.getBudgetRisk()` sums purchases without bounding to `now` — future-dated purchases inflate spent-to-date (B05, B48)
  - `CalculateFinancialForecastUseCase` forecast flow recomputes `now`/`monthStart`/`currentDay` only when repository flows emit — stale across day/month rollover (B05)
  - `TotalsAggregationEngine` monthly/weekly/daily totals only return periods with transactions — zero-spend periods disappear, week labels renumbered (B01)
  - `BudgetCalculator` `CALENDAR` yearly budgets fall through to anniversary-style anchor instead of Jan 1 → Jan 1 (B37)
  - `BudgetAutopilotEngine` autopilot monthly history drops zero-spend months — biasing trend and volatility (B37)
  - `BudgetAutopilotEngine` empty/one-point histories score ~0.7 confidence — `MIN_HISTORY_MONTHS` never enforced (B37)
  - `ComputeMoneyRadarUseCase` budget-risk urgency scoring uses only overrun probability — `HIGH`/`CRITICAL` risk driven by overrun magnitude can contribute zero score (B48)
  - `BudgetRepository.getBudgetStatuses()` reads `getExpensesBetweenFlow()` without overriding default `limit = 2000` — yearly budgets silently drop older purchases (B32-missed)

- **MEDIUM:**
  - Historical monthly analysis drops zero-spend months, inflating averages (B02, B37)
  - `updateForecastAccuracy()` is unfinished — queries by forecastId but uses budgetId API (B02)
  - Month bucketing uses UTC while rest of budget stack uses local calendar (B02, B37) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 3: month bucketing now uses shared local-calendar helpers via BudgetHistorySeriesBuilder/TimePeriodUtils]**
  - Confidence scoring rewards missing/sparse history as stable evidence (B02, B37) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 3: confidence now penalizes sparse history and requires sufficient evidence windows]**
  - `BudgetForecastingEngine` inserts active rows without deactivating older ones (B27, B28, B37)
  - `BudgetRepository.getBudgetStatuses()` captures time bounds once — long-lived collectors go stale (B32)
  - `BudgetRepository` computes raw spend, ignores `SharedExpenseBudgetOffsetEngine` — budget screen overlays different adjusted spend (B32)
  - `GetMonteCarloBudgetImpactUseCase` messages say "exceed by €0.00" when probability is high but median under budget (B05, B48)
  - `CalculateBudgetStatusUseCase.getBudgetHealth()` ignores `CRITICAL` status (B48)
  - `ComputeDashboardWidgetsUseCase` budget summary says "all on track" when nothing is `EXCEEDED`, even with WARNING/CRITICAL (B48)
  - `AdvancedAnalyticsDashboard.getMonthlyTrend()` builds `23:59:59` month end, passes to end-exclusive repo query — drops last second of month (B36-missed)
  - `AdvancedAnalyticsDashboard` monthly trend N+1 pattern — one repo query per month (B36-missed) **[RESOLVED - `getMonthlyTrend()` now loads the range once and groups monthly buckets in memory]**
  - `AdvancedAnalyticsEngine` current-period sparklines stop before today — on first day of period can render empty despite spend (B36-missed) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 1: sparkline window now includes current day boundary for active periods]**
  - `SpendingPersonalityClassifier` confidence calculation mixes normalized 0..1 features with raw `transactionsPerMonth` (B36-missed) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 5: confidence inputs normalized to consistent bounded feature scales]**
  - `DayOfWeekAnalyzer` results sorted by total spend instead of weekday order — breaks chronological consumers (B36-missed) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 1: output order is now canonical weekday chronology]**
  - `TransferDirectionAnalytics` user corrections only change `correctDetections`/accuracy counters — incoming/outgoing totals and top source/destination lists remain wrong after corrections (B36-missed) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 5: correction handling now recomputes directional totals and source/destination aggregates]**
  - `BudgetAutopilotEngine` and `BudgetForecastingEngine` use different month bucketing, timezone rules, trend heuristics, confidence formulas — inconsistent signals for same history (B37-missed) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 3 (BudgetHistorySeriesBuilder): shared month bucketing/timezone history series now powers both engines]**
  - `RecurringExpenseRepository` leaves `IRREGULAR` items without advancing `nextDate`; different semantics per code path (B12)

- **LOW:**
  - Daily window snaps to local midnight ignoring anchor time-of-day (B02)
  - Seasonal adjustment unreachable (90-day lookback vs 6-month threshold) (B02)
  - `BudgetMonitor` hardcodes English copy, emoji, hex colors in domain engine (B02)
  - `BudgetForecastingEngine` seasonal factor is simplistic model limitation (B37)
  - `SpendingPaceModels.kt` referenced in batch plan but file doesn't exist — models live in `AnalyticsModels.kt` (B01)

### B.3: Receipt/OCR Pipeline
**Batches:** 08, 09, 10, 39, 44, 45

**[RESOLVED BY B.3]**

- **CRITICAL:**
  - `WarrantyTextExtractor` uses shared `SimpleDateFormat` instances across parallel batch imports — not thread-safe (B45)
  - `OcrLanguageProcessor.normalizeForLanguage()` routes Cyrillic/Arabic/CJK through Latin-only normalization, destroying characters (B44)

- **HIGH:**
  - Cloud receipt assist uploads raw images when image mode enabled even with `redactBeforeCloud=true` (B08, B09)
  - On-device receipt assist sends only `TextPart(prompt)`, never attaches image — advertised vision path is text-only (B09, B26)
  - `ReceiptParser` line-item extraction runs overlapping patterns, adding quantity-formatted lines twice (B45)
  - `WarrantyTextExtractor.isReasonablePurchaseDate()` rejects receipts older than 1 year, blocking 2-5 year warranty receipts (B45)
  - `ReceiptTransactionMatcher` treats any positive-amount transaction as receipt-compatible — deposits/transfers suggested for matching (B45)
  - Merchant normalization strips everything outside `[a-z0-9]`, collapsing Greek names to empty strings — unrelated merchants score as perfect matches (B45)
  - `BankStatementParser` amount selection breaks ties by largest absolute value — can select running balance instead of transaction amount (B44)
  - Revolut statement parsing strips currency symbols and blindly replaces commas with dots — thousands separators fail (B44)
  - Revolut statement emits only `DEPOSIT` or `PURCHASE` — transfers/top-ups/refunds misclassified (B44)
  - `OcrLanguageProcessor` amount extraction mishandles locale-specific separators: `25,50` → `2550` (B44)
  - `ReceiptMatchingWorker` returns `Result.retry()` for all exceptions including permanent failures — infinite retry loops (B45)
  - `AutoCreateWarrantyFromReceiptUseCase` medium-confidence extraction persists `PENDING_REVIEW` draft; `createWarrantyForReview()` inserts new warranty with same `receiptId` → conflicts with draft → `AlreadyExists` (B05)
  - `CloudWarrantyExtractionService` returns `null` for return-policy-only receipts — `WarrantyTrackerRepository` only creates `ReturnWindow` when `Warranty` was produced (B09)
  - Warranty extraction → return-window mapping: AI extracts `returnDays`/`returnConditions` but repository ignores them, recreates merchant-default windows — extracted fields dead code (B09)
  - Receipt warranty extraction uses fixed 30-day months vs. manual warranty UI uses calendar-month addition — inconsistent warranty period math (B12)
  - Warranty/return-window status never reconciled — no production path transitions rows to `EXPIRED` (B14)

- **MEDIUM:**
  - `CloudReceiptItemCategorizationService` uses `ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS` for cloud request, truncating multi-item responses (B09)
  - Cloud receipt-item parser persists model-returned `description` verbatim when redaction enabled — aliases/truncated placeholders stored as real text (B09)
  - `OnDeviceReceiptAssistService` uses `optDouble`/`optLong` for parsed numerics — malformed output coerced to `NaN`/`0` (B09, B26)
  - `ReceiptOcrService.recognizeText()` serializes with `recognizerMutex` but `close()` uses different lock — recognizer can close mid-OCR (B45)
  - `WarrantyTextExtractor` shared formatters left lenient — impossible OCR dates normalized to different "valid" dates (B45)
  - `BankStatementParser` header/date-column detection computed but never used (B44)
  - `EnhancedMerchantExtractor.isPrice()` only filters lines with currency token — `TOTAL 123.45` remains eligible merchant candidate (B44)
  - `EnhancedMerchantExtractor` drops known merchant when OCR yields no candidates, falls back to `Unknown Merchant` (B44)
  - `ImageCache` keyed only by `uri.toString().hashCode()` — different target sizes reuse wrong bitmap (B44)
  - Disk cache never evicts by age or size (B44)
  - `ReceiptParser` computes `total - tax` for missing subtotal — impossible negative subtotals when OCR tax is wrong (B45)
  - `SmsParser.detectSmsDirection()` returns `INCOMING` on tie/unknown for transfers — ambiguous transfers labeled as incoming money (B44)
  - OCR improvement components registered in DI but never injected into `ReceiptOcrService` or `ReceiptParser` — no runtime effect (B44)
  - `WarrantyTrackerScreen` "Expired" filter uses `status == EXPIRED` but nothing auto-transitions warranties — filter never shows items (B18)

- **LOW:**
  - `CloudWarrantyExtractionService` hardcodes model name and token budget (B09)
  - `CloudReceiptItemCategorizationService` hardcodes `€` in prompts (B09)
  - `CloudReviewExplanationService` generates new correlation ID per retry attempt (B09)
  - `CloudWarrantyExtractionService` accepts `"null"` string placeholders for contact fields (B09)
  - `ReceiptParser.lineItemsFromJson()` swallows deserialization failures (B45)

### B.4: Database/DAO/Entity Pipeline
**Batches:** 11, 12, 13, 14, 15, 27, 28, 29

**[RESOLVED BY B.4]** — All items below were addressed across B.4 micro-batches 1–10 plus late closeout fixes (ISSUE-B4-11: migration 76→77 `UserCorrection.originalMerchant` index; migration 77→78 `AnomalyAlert (category, alertedAt)` composite index; `InvestmentTracker` recent-value ordering; `ExpenseRepository` paged-projection re-verification; Batch 29 final: `formattedTime` extension rename + stale `TransactionsScreen` import removed; migration 78→79 `ExchangeRate.toCurrency` supplementary index). Targeted validation evidence and device/environment waivers recorded in `docs/reviews/REVIEW-B4.md`. Final schema version: 81.

- **CRITICAL:**
  - (None — all database issues are High or below)

- **HIGH:**
  - `Budget` entity allows multiple active overall/category budgets; DAO reads use `LIMIT 1` with no ordering — nondeterministic (B12, B14) **[RESOLVED BY B.4 — Batch 4: partial unique index + transactional deactivation]**
  - `ManualRecurringExpense.isSubscription` defaults to `true` — generic recurring creation paths misclassify as subscriptions (B12, B13) **[RESOLVED BY B.4 — Batch 4: default changed to `false`]**
  - `GroupMember` schema allows multiple `isCurrentUser = 1` per group; DAO uses `LIMIT 1` — nondeterministic current-user resolution (B13, B15) **[RESOLVED BY B.4 — Batch 3: partial unique index on `(groupId)` where `isCurrentUser = 1`]**
  - `GroupExpense.expenseId` treated as one-to-one link but not unique — one expense linked to multiple group_expenses rows (B13) **[RESOLVED BY B.4 — Batch 3: unique index on non-null `expenseId`]**
  - `GroupExpense.paidById` only references `group_members.id` without enforcing same-group membership (B13) **[RESOLVED BY B.4 — Batch 3: coordinator-level same-group validation enforced transactionally; trigger deferred per plan split rule]**
  - `MerchantCanonical` keyed by `searchKey` but only `normalizedName` is unique — different display names collapse to same searchKey (B13) **[RESOLVED BY B.4 — Batch 5: `searchKey` made unique with deterministic duplicate retention]**
  - `MerchantAlias` reads by `normalizedKey LIMIT 1` but `normalizedKey` not unique — arbitrary alias resolution (B13, B15) **[RESOLVED BY B.4 — Batch 5: `normalizedKey` uniqueness enforced]**
  - `BankConnectionDao.disconnect()` marks inactive but leaves `accessToken`, `refreshToken`, `tokenExpiry` intact — live credentials preserved (B15) **[RESOLVED BY B.4 — Batch 6: token fields nulled in same update]**
  - `EmailReceiptDao.insert()` uses `REPLACE` on unique `emailMessageId` — re-ingesting same email overwrites source row (B15) **[RESOLVED BY B.4 — Batch 6: `emailMessageId` checked before insert; semantics changed to `ABORT`/explicit dedupe]**
  - `RawNotification` unique index includes nullable `title` and `text` — SQLite NULL rows don't collide, bypassing dedupe (B28) **[RESOLVED BY B.4 — Batch 6: nullable dedupe fields normalized to non-null sentinels before insert]**
  - `AnomalyAlert.expenseId` has no FK — orphan alerts remain after expense deletion (B28) **[RESOLVED BY B.4 — Batch 6: FK with `ON DELETE CASCADE` added and orphan migration included]**
  - `SubscriptionCandidate` schema doesn't enforce one pending per merchant — concurrent detections create duplicates (B27, B28) **[RESOLVED BY B.4 — Batch 7: DB-level pending-candidate uniqueness + transactional upsert in pipeline]**
  - `BudgetForecast` allows multiple overlapping active forecasts — date-based lookups nondeterministic (B27, B28) **[RESOLVED BY B.4 — Batch 7: transactional deactivate-then-insert + active-row uniqueness rule]**
  - `SavingsGoalDao.updateGoalAmount()` overwrites `currentAmount` with caller-computed absolute value — concurrent contributions lose money (B14) **[RESOLVED BY B.4 — Batch 9: atomic delta update (`currentAmount = currentAmount + :delta`)]**
  - `ScannedReceiptDao.linkToExpense()` updates only `expenseId`, leaves `matchStatus`/`suggestedExpenseId` untouched — linked receipts remain `UNMATCHED` (B14) **[RESOLVED BY B.4 — Batch 9: link atomically sets matched status and clears suggestion metadata]**
  - `ExpenseDao.getBusinessExpensesMissingReceipts()` uses `rawNotificationId IS NULL` as receipt proxy — manual/receipt-created business expenses falsely flagged (B14) **[RESOLVED BY B.4 — Batch 9: replaced with `NOT EXISTS` on `scanned_receipts.expenseId`]**
  - Business expense queries use raw `amount`, omit `isNotMine`/effective-amount handling — deductible totals overstated (B14) **[RESOLVED BY B.4 — Batch 9: effective-amount `CASE` applied to business queries]**
  - `ExpenseWithCategory.formattedAmount` built from raw `amount`, omits transaction polarity (B29) **[RESOLVED BY B.4 — Batch 10: formatting now uses `effectiveAmount` with polarity sign rules]**
  - `UserCorrection` table has no index on `originalMerchant` — hot lookup queries degrade to full scans (B27) **[RESOLVED BY B.4 — late closeout: `Index("originalMerchant")` entity annotation + `MIGRATION_76_77` (schema version 77)]**
  - `SubscriptionCandidateDao` dedupe is read-then-insert without transaction — concurrent notifications create duplicates (B27) **[RESOLVED BY B.4 — Batch 7: transactional upsert enforced in `NotificationProcessingPipeline`]**
  - `InvestmentTracker.getInvestmentPerformance()` computes "all-time" high/low from only 30 days (B27) **[RESOLVED BY B.4 — late closeout: true all-time min/max DAO queries used (`getMaxPrice`/`getMinPrice` from epoch 0); recent-value ordering also fixed (`recentValues.lastOrNull()` on ASC window returns the most-recent sample)]**
  - `RecurringExpenseRepository.getAll()` pulls inactive manual recurring rows — engine suppresses detection for deactivated subscriptions (B14) **[RESOLVED BY B.4 — Batch 4: repository migrated to active-only `ManualRecurringExpenseDao` query]**
  - `GroupTransactionCoordinator` `addMemberToGroup()`, `addExpenseToGroup()`, `addExpenseWithLink()` validate state outside single DB transaction — concurrent archive/delete can invalidate checks (B11) **[RESOLVED BY B.4 — Batch 2: validation collapsed into single `withTransaction {}` block]**
  - `SharedExpenseGroupsViewModel.addExpense()` creates system expense first then `group_expenses` row — crash between two writes orphans the system expense (B11) **[RESOLVED BY B.4 — Batch 2: both writes moved behind one atomic coordinator transaction]**
  - Migration `69→70` + Android Keystore encryption causing DB open failure (B11) **[RESOLVED BY B.4 — Batch 1: per-row Keystore failure caught and deferred; DB open no longer aborts]**
  - `BankConnection.defaultCategoryId` has no FK to `categories` — deleted categories leave stale IDs (B13) **[RESOLVED BY B.4 — Batch 6: FK to `categories(id)` with `ON DELETE SET NULL` added]**
  - `MerchantLocation.areaKey` nullable inside composite unique index — multiple `(normalizedMerchantName, NULL)` rows bypass uniqueness in SQLite (B13) **[RESOLVED BY B.4 — Batch 5: NULL backfilled to `'global'`; column made non-null]**
  - Financially sensitive numeric fields have no DB-level CHECK constraints across 7 entities (B13) **[RESOLVED BY B.4 — Batch 8: CHECK constraints added for all 7 identified entities]**
  - `ExpenseDao.getBusinessExpensesBetween()` doesn't filter `transactionType = 'PURCHASE'` — transfers/deposits listed as deductible (B14) **[RESOLVED BY B.4 — Batch 9: purchase-type filter added to list and flow variants]**
  - `CategoryDao` → `CategoryRepository.ensureDefaultCategories()` race — concurrent seeding creates duplicate defaults (B14) **[RESOLVED BY B.4 — Batch 4: seeding wrapped in one DB transaction with idempotent insert-or-ignore]**
  - `CsvExpenseImporter` bypasses singleton Room graph — builds fresh `AppDatabase` instances via local extension (B23) **[RESOLVED BY B.4 — Batch 10: importer refactored to use DI-backed `AppDatabase` reference]**
  - `AnomalyAlertDao.getLastAlertForCategory()` has no `(category, alertedAt)` index — category cooldown checks scan full table (B27) **[RESOLVED BY B.4 — late closeout: composite index `(category, alertedAt)` added via `MIGRATION_77_78` (schema version 78)]**

- **LOW:**
  - Migration `42→43` created `group_expenses.expenseId` as `NOT NULL` for nullable field — repaired in `49→50` (B11) **[RESOLVED BY B.4 — Batch 1: regression migration test added covering `42→43` / `49→50` path]**
  - `GroupTransactionCoordinator.addExpenseToGroupAtomic()` ignores `newBalance` — silent no-op (B11) **[RESOLVED BY B.4 — Batch 2: unused parameter and dead balance-update loop removed]**
  - `deleteGroup()` always returns `true` if `archiveGroup()` doesn't throw — nonexistent group reported as success (B11-missed) **[RESOLVED BY B.4 — Batch 2: `archiveGroup()` now returns affected-row count; `0` rows mapped to not-found result]**
  - `Category` entity `init` block throws on invalid persisted values — bad DB row turns reads into exceptions (B12) **[RESOLVED BY B.4 — Batch 4: validation moved to write path; reads are now recovery-safe]**
  - `Budget.notifyAtWarning/notifyAtCritical` unconstrained (B12) **[RESOLVED BY B.4 — Batch 8: CHECK constraints enforce legal threshold ranges]**
  - `SavingsGoal.targetAmount/currentAmount` unconstrained (B12) **[RESOLVED BY B.4 — Batch 8: CHECK constraints enforce positive target and non-negative balance]**
  - `Expense.splitTemplateId` has no FK (B12-missed) **[RESOLVED BY B.4 — Batch 8: nullable FK to `split_templates(id)` with `ON DELETE SET NULL` added]**
  - `PendingReview.suggestedType` stored as raw `String` — corrupted rows silently change transaction type (B12-missed) **[RESOLVED BY B.4 — Batch 8: enum-backed validation enforced on persistence; invalid strings rejected]**
  - `ExchangeRateDao.getAllRatesForBase()` filters on `toCurrency` but index leads with `fromCurrency` (B15) **[RESOLVED BY B.4 — Batch 29 closeout: `Index(["toCurrency"])` entity annotation added to `ExchangeRate.kt`; `MIGRATION_78_79` lands `CREATE INDEX IF NOT EXISTS index_exchange_rates_toCurrency ON exchange_rates (toCurrency)`; schema version bumped to 79; `MigrationContractTest` extended with `migration_78_to_79_adds_toCurrency_index_on_exchange_rates`; `DatabaseMigrationTest` extended with `migrate_77_to_79_chain_passes_and_has_toCurrency_index`]**
  - `EmailReceiptDao.getByReceiptId()` returns single row but multiple sources can share same receiptId (B15-missed) **[RESOLVED BY B.4 — Batch 6: return type changed to `List<EmailReceiptSource>`]**
  - `Global merchant-location keys` inconsistent across pipeline (`"global"` vs `"<normalized>|global"`) (B15-missed) **[RESOLVED BY B.4 — Batch 5: single global-key convention standardized; legacy rows migrated]**
  - `UserCorrectionDao` tie-breaking in `getMostCommon*` uses `LIMIT 1` with no secondary ordering (B27) **[RESOLVED BY B.4 — Batch 5: stable secondary sort (recency/id) added as tie-breaker]**
  - `SubscriptionUsageDao.getAllUsageSince()` effectively unindexed for global queries (B27-missed) **[RESOLVED BY B.4 — Batch 7: standalone `Index(["usedAt"])` added]**
  - `SubscriptionCandidate.convertedSubscriptionId` has no FK (B28-missed) **[RESOLVED BY B.4 — Batch 8: nullable FK to `ManualRecurringExpense(id)` with `ON DELETE SET NULL` added]**
  - `MileageTracking` entity accepts impossible states (negative distance, endOdometer < startOdometer) (B28) **[RESOLVED BY B.4 — Batch 8: repository guard and DB CHECK constraints reject impossible mileage states]**
  - `formattedAmount` hardcodes `Locale.US` (B29-missed) **[RESOLVED BY B.4 — Batch 10: `ExpenseWithCategory.formattedAmount` now uses `effectiveAmount` with polarity sign rules (`-`/`+`/`""` based on `transactionType`), `Locale.getDefault()` via `String.format(Locale.getDefault(), "%.2f", ...)`, and a prefixed currency code string (`"$prefix${expense.currency}$value"`); `NumberFormat`/`Currency` API is not used; Batch 29 closeout: `ExpenseWithCategory_Extensions.kt` extension renamed from `formattedDate` to `formattedTime` (time-only "HH:mm" helper) fully resolving the member-shadows-extension ambiguity; dead `formattedAmount` extension and stale shadowed `formattedDate` extension removed; `TransactionsScreen.kt` import updated to `formattedTime`]**

### B.5: Location/Geocoding Pipeline
**Batches:** 18, 30, 32, 42, 44

**[PARTIALLY_RESOLVED - Many fixes landed but 13 items still open]**

- **CRITICAL:**
  - (None)

- **HIGH:**
  - `OverpassNearbyService.executeWithRetry()` closes every 429/5xx response including the last one — callers lose `RateLimited`/`HttpError` semantics (B30)
  - Overpass name ranking strips everything outside `[a-z0-9]` — Greek merchant names normalize to empty, similarity collapses to 0.0 (B30)
  - `MerchantKeyBackfillWorker` per-row update failures swallowed, failed rows remain `merchantKey IS NULL` — worker spins in tight loop (B30)
  - `LocationBackfillWorker` logs raw merchant names on resolver exception — exposes transaction-derived identifiers in logcat (B30)
  - `LogSanitizer.anonymizeForLog()` is just `String.hashCode()` in hex — deterministic, unsalted, 32-bit, brute-forceable (B30)
  - `LocationResolver` defines its own private `anonymizeForLog()` using same weak `hashCode()` — even if shared sanitizer fixed, resolver logs remain brute-forceable (B30-missed)
  - `LocationResolver.geocode()` collapses every geocoder failure to `null` — transient outages become `Unresolved`, consuming retry budget (B30)
  - All geocoding providers use blocking `OkHttp.execute()` — coroutine cancellation doesn't cancel underlying HTTP calls, wasting quota (B30)
  - `LocationResolver.saveLocation()` saves GPS-biased and name-only resolutions under global area key — multi-branch merchants poison later resolutions (B42)
  - `SpendingHeatmapEngine` sums raw `amount` values with `ln(1 + totalSpend)` — negative totals produce NaN log weights (B42)
  - Map pipeline consumes `getLocatedExpensesFlow()` without filtering transaction type — deposits/transfers feed "spending" heatmaps (B42)
  - `LocationResolver` global cache fallback returns arbitrary area-scoped entry when no global entry exists — wrong branch returned (B42)
  - Merchant location global-cache fallback returns arbitrary area-scoped entry — wrong branch for multi-branch merchants (B15)
  - Merchant location global-key encoding inconsistency — `"global"` vs `"<normalized>|global"` (B15)

- **MEDIUM:**
  - [RESOLVED] `AndroidForegroundLocationProvider.getLastKnownLocation()` only calls `getCurrentLocation()`, never reads cached `lastLocation` (B30)
  - [RESOLVED] `CompositeGeocodingService.safeLookup()` maps unexpected provider exceptions to `Unknown` — only cascades on transient errors, disabling fallback chain (B30)
  - `NominatimGeocodingService.searchMultiple()` accepts `limit` but always sends `NOMINATIM_MAX_RESULTS` (B30)
  - `LocationResolver` fetches device location before correction/cache checks — multiplies latency and battery cost in backfill runs (B30)
  - Grid-cell bucketing uses `.toLong()` (truncate toward zero) instead of flooring — negative lat/lon hash to wrong cell (B42)
  - `PriceProtectionTracker` uses `receipt.createdAt` and `Instant.now()` instead of `parsedDate` and `TimeProvider` — imported old receipts look newly eligible (B42, B44)
  - `PriceProtectionTracker` generates price drops/deals/coupons from hard-coded heuristics, rendered in user-facing UI as real results (B42, B44)
  - `PriceProtectionTracker.getDealsCouponsAndBenefits()` loads entire receipts table then applies `take(20)` (B42, B44)
  - [RESOLVED] DAO uses `CAST(lat/0.045 AS INTEGER)` while repository uses `floor(...)` — negative coordinates hash to different area keys (B42)

- **LOW:**
  - `SpendingMapScreen` date-range chips built from `remember { System.currentTimeMillis() }` — stale windows on long-lived screens (B18)
  - `SpendingMapViewModel` manual `recomputeMapData()` races with reactive collector (B18)
  - Map auto-centres on every GPS change — yanks map away from user viewport (B18)
  - [RESOLVED] `AreaSpendingEngine` grid cells keep first parsed area name — mixed-address cells labelled by unrepresentative first expense (B42)
  - [RESOLVED] `TravelDetectionEngine` destination hints extracted with `split(",").getOrNull(1)` — one-part addresses lose destination label (B42)
  - `MerchantNormalizer` logs raw merchant names in plaintext (B42)

### B.6: Notification/Service/Worker Pipeline
**Batches:** 20, 21, 23, 33, 36, 39, 44

**[PARTIALLY_RESOLVED - Many fixes landed but 11 items still open]**

- **CRITICAL:**
  - (None)

- **HIGH:**
  - `NotificationFilter.MONITORED_PACKAGES` unconditionally whitelists Gmail, Viber, SMS apps — unrelated personal messages bypass heuristics (B20, B21)
  - `NotificationCaptureService` normalizes `title/text/bigText` with `orEmpty()` before fallback chain — `effectiveBigText = bigText ?: infoText` never falls back when `bigText` is empty string (B20, B21)
  - `RecommendationDismissalHandler` removes card from in-memory state first, only logs repository failures — failed archive leaves DB active, recommendation reappears (B20)
  - `RecommendationStateManager.refreshForUser()` skips work for already-active user unless `forceRefresh=true` — blocks normal refresh/invalidation paths (B20, B21)
  - `DailyBriefingWorker` has no timeout around generation/delivery, converts all failures to `Result.success()` — transient stalls silently drop day's briefing (B21)
  - `NotificationIdGenerator.forWarranty()` uses 5000 offset inside 9999-wide range — 30-day warranty notifications can land inside receipt band (B21, B23)
  - `RecommendationRepository.saveAll()` limits only new batch, not total active set — can accumulate more than 5 `ACTIVE` rows (B20-missed, B33)
  - Duplicate detection is currency-blind across notification auto-accept, statement import, and review approval (B33)
  - `BillReminderManager` string-matches `frequency.name` — handles `YEARLY` but enum is `ANNUALLY`, also includes `SEMI_ANNUALLY`/`IRREGULAR` (B39, B43)
  - `BankApiIntegration.mapTransactionToExpense()` forces every bank movement to `PURCHASE` with `abs(amount)` — deposits/refunds/transfers imported as positive expenses (B39)
  - `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` never records prompt impression — cooldown only starts after explicit user action, not after prompt is shown (B33-missed)
  - `AnomalyAlertOrchestrator` alert deduplication not atomic — concurrent `checkAndAlert()` calls can both pass `getLastAlertForExpense()` and both insert/send duplicate notifications (B36-missed)
  - `RecommendationStateManager.refreshForUser()` concurrent stale result overwrite — slower stale request can overwrite newer state (B20)
  - Anomaly notifications deep-link to `expensetracker://transaction/{id}` but manifest doesn't declare host (B20)
  - `NavigationAction.ToAnalytics(period)` / `ToMap(location)` payload dropped at `HomeScreen`/`MainActivity` (B20)
  - `DeliverProactiveBriefingNotificationUseCase` records briefing as delivered even when `AndroidNotificationService` returns early — permanently suppressed (B20)

- **MEDIUM:**
  - `NotificationFilter.shouldCapture()` lowercases content but `REGEX_CURRENCY` only matches uppercase — misses lowercase currency codes (B20)
  - [RESOLVED] `NotificationCaptureService.onDestroy()` cancels `serviceJob` immediately — in-flight `processNotification()` can be aborted before persistence (B20)
  - `RecommendationStateManager.clearForUser()` always clears in-memory state even when cleared user is not currently displayed (B20)
  - `RecommendationDeduplicator.computeSignature()` always includes `rec.category` — same effective filter with different originating categories treated as distinct (B20)
  - `RecommendationDeduplicator.computeSignature()` omits `ownership` from signature — recommendations differing only by ownership type incorrectly collapsed as duplicates (B47-missed)
  - `NotificationCaptureService` force-started at boot, re-started every minute via repeating alarm — unnecessary wakeups (B21)
  - [RESOLVED] `RecommendationInvalidator.invalidateAllForUser()` claims to invalidate all but only clears cache and expires already-expired rows (B21)
  - `NotificationRepository.deleteAll()` wipes notifications/expenses/reviews/corrections but only zeroes `pendingReview` in `source_stats` — other counts remain stale (B33)
  - `WidgetStyleRepositoryImpl` DataStore flow lacks `catch` — store corruption terminates all consumers (B33)
  - [RESOLVED] `ReviewQueueRepository.markAsRelevant(true)` inserts new `PendingReview` when reparsing fails without checking for existing review (B33) — repository upsert path is fixed and `pending_reviews.rawNotificationId` uniqueness now matches on both upgrade (`81→82`) and fresh-install callback paths
  - `SmsParser` and `RevolutParser` amount regex only accepts single decimal separator — thousands-separated amounts rejected (B44-missed)
  - [RESOLVED] `NotificationProcessingPipeline` oversized-amount fallback inserts `PendingReview` without semantic duplicate check (B33)

- **LOW:**
  - AI briefing notifications use `targetKey.hashCode()` directly instead of shared notification ID allocator (B21)
  - `RecommendationInvalidator` swallows exceptions with empty catch blocks (B21)
  - `NotificationSeeder` derives package names from display labels instead of valid parser package IDs (B39)
  - `NotificationSeeder.generateRecurring()` produces isolated random charges instead of clustered patterns (B39)
  - `ServiceDiagnostics` counters use unsynchronized read-modify-write on `SharedPreferences` (B39)
  - `DebugIssueDetector` OCR-quality heuristic counts literal `?` as unrecognized characters (B39)
  - `BankApiIntegration.shouldSync()` only checks `autoSync` flag and elapsed time — ignores `isActive` and `isConnected`, so disabled/disconnected bank connections still qualify for sync (B39-missed)

### B.7: Export/Backup Pipeline
**Batches:** 39, 44

**[PARTIALLY_RESOLVED - Much improved but still has code issues]**

- **CRITICAL:**
  - (None)

- **HIGH:**
  - `AccountingExportRepository.exportExpenses()` reads through `expenseRepository.getExpensesBetween()` — inherits DAO default 2000-row cap, large exports silently incomplete (B33, B39-missed)
  - Accountant report sums raw amounts across currencies, hardcodes `€` in totals — mixed-currency reports mathematically meaningless (B33)
  - `ExportTransaction` omits `currency` — accounting exporters cannot distinguish multi-currency rows (B39)
  - `ExportTransaction` omits `transactionType` — deposits/withdrawals/transfers serialized as expense rows (B39-missed)
  - `ACCOUNTANT_REPORT_PDF` writes plain-text `.txt` file, not PDF (B33)
  - QuickBooks IIF uses category account on both `TRNS` and `SPL` rows — imports become self-canceling without real source account (B39)
  - Repository export path buffers capped result set while UI export path pages deterministically — one path already truncates (B39)

- **MEDIUM:**
  - [RESOLVED] All three exporters keep `SimpleDateFormat` as instance state, Hilt provides as singletons — concurrent exports race on shared formatter (B39)
  - All exporters emit raw `Double.toString()` for money — inconsistent precision or scientific notation (B39)
  - `DebugData.toJson()` hand-builds JSON, only escapes subset of fields — backslashes/control chars produce invalid JSON (B39)
  - Generic CSV export header omits currency column — mixed-currency CSV flattens unlike amounts (B39-missed)
  - CSV escaping handles commas/quotes/newlines but not formula-injection prefixes (`=`, `+`, `-`, `@`) (B37)

- **LOW:**
  - `DebugData` transaction dates exported as epoch millis while metadata uses ISO — not a functional bug (B39)
  - [RESOLVED] Mileage summary exposes first trip's `deductionRatePerKm` as if one rate applied to whole period (B37)

### B.8: Savings/Investment Pipeline
**Batches:** 03, 05, 32, 37, 41, 45, 48

**[PARTIALLY_RESOLVED - additional verified Phase B.8 fixes landed in this pass; resolved bullets are marked inline below]**

- **CRITICAL:**
  - (None)

- **HIGH:**
  - `AutomatedSavingsRuleEngine.PERCENTAGE_OF_INCOME` accepts negative, `NaN`, infinite percentages — can emit negative/non-finite rule executions (B03, B45)
  - `WEEKLY_NO_SPEND` evaluated on every `evaluateRules()` call with no per-week idempotency — qualifying periods can mint repeated rewards (B03, B45)
  - `AutomatedSavingsRuleEngine.WEEKLY_NO_SPEND` uses rolling `now - 7 days` instead of stable calendar week (B03)
  - Monthly-cap enforcement lives only in in-memory singleton map — caps reset after process death (B03, B45)
  - `SmartSavingsEngine.calculateBudgetSurplus()` sums every positive remaining budget — overall + category budgets double-count (B03, B45)
  - `calculateSafeToSaveAmount()` computes portfolio-wide amount but returns per goal — multiple goals each receive full recommendation (B03, B45)
  - `SavingsGamificationEngine` streaks fabricated from `goal.createdAt` and hardcoded `5`-day placeholder instead of real contribution history (B45)
  - `InvestmentTracker` gain/loss calculations ignore `purchaseFees` — overstating gains (B41)
  - `getInvestmentPerformance()` reads 30-day history ascending, uses `firstOrNull()` for day change — displays oldest snapshot instead of latest (B41)
  - `getPortfolioValueHistory()` sums every snapshot recorded on a day — multiple updates double-count (B41)
  - `TaxEstimator` selects single bracket rate and applies to all taxable income — progressive brackets ignored (B45)
  - `estimateTaxes()` collapses any non-zero period to one month of income while subtracting expenses for full period (B45)
  - `getTaxYearSummary()` hardcodes annual income to `30000.0`, annualizes already-annual values (B45)
  - Tax estimation sums raw `amount` for deductible expenses — shared expenses overstate deductions (B45-missed)
  - VAT paid computed from all purchases, not business-only, with DAO default 2000-row cap (B45)
  - `FinancialHealthCalculator` includes deposits, transfers, and all non-purchase rows when computing `spentToday/week/month`, volatility, spending-control penalties — inflating health score inputs (B41)
  - `FinancialHealthCalculator` spending-control targets sum every budget amount together without normalizing to common period, mixing daily/weekly/monthly/yearly budgets and double-counting overall + category (B41)
  - `FinancialHealthScoreV2.calculateHealthScore(periodStart, periodEnd)` always uses `budgetRepository.getBudgetStatuses().first()` computed for `timeProvider.now()` — not the requested period (B41)

- **MEDIUM:**
  - WEEK/QUARTER horizons scale month-end Monte Carlo forecast by `0.25`/`3.0` — simulator models only current month (B03, B45)
  - `monthlyDiscretionary` always divides by `3.0` for any non-empty 90-day history (B03, B45)
  - `FinancialHealthScoreV2` bill reliability derived from recurring-pattern confidence, not due-date/on-time-payment signal (B41)
  - `RecurringIncomeTracker` uses raw `amount` instead of `effectiveAmount` — shared transactions overstated (B41)
  - Confidence scoring compares millisecond-squared variance against tiny raw threshold — "low variance" branch practically unreachable (B41) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 5: confidence variance now uses normalized day-scale metrics instead of ms² thresholds]**
  - `getStartOfMonth()` leaves milliseconds untouched — transactions at `00:00:00.000` can fall before computed start (B41)
  - `ReceiptMatchingWorker` rescans all unmatched receipts every run with no last-attempt marker (B45)
  - `LifestyleSavingsPromptUseCase` treats `savingsRate <= 1.0` as fraction but detector emits percentages — low rates inflated 100x (B05, B48)
  - Sweep-risk spending includes `WITHDRAWAL` while budget paths use purchase-only (B48)
  - Null Monte Carlo falls back to hardcoded `100.0` risk buffer (B48) **[RESOLVED - fallback now derives from spent-to-date, known upcoming obligations, budget size, and days remaining]**
  - `MAX_SINGLE_ALLOCATION_PERCENT` enforced only for non-last goals — final remainder branch can exceed cap (B05-missed, B48-missed) **[RESOLVED - final allocation branch now applies remaining-gap and concentration caps consistently]**

- **LOW:**
  - Conservative `* 3.0` projection is arbitrary product heuristic (B01) **[RESOLVED - early-day projection now uses the shared bounded spending-pace projection path in both `SpendingPaceCalculator` and `CalculateFinancialForecastUseCase`, with no live literal `* 3.0` fallback remaining]**
  - `SavingsGoal.createdAt` defaults to `0L` (B46) **[RESOLVED - domain model now requires explicit `createdAt`]**
  - `PlannedExpense.amount` has no non-negative invariant (B46)
  - Alerts/rankings/history use `getAllInvestments()` instead of active holdings only (B41)
  - `allocationPercentage` keeps pre-cap urgency share — displayed percentages disagree with final allocations (B48)
  - `userCorrectionRepository` injected in `DetectDuplicateExpenseUseCase` but never used (B48)

### B.9: UI/Compose Pipeline
**Batches:** 05, 16, 17, 18, 19, 36, 40, 48

**[PARTIALLY_RESOLVED - additional verified Phase B.9 fixes landed in this pass; resolved bullets are marked inline below]**

- **CRITICAL:**
  - `ReviewViewModel.approveReviewWithEdits()` runs `applyToAll` and `approveAllPending` after primary approve returns `Duplicate` or `Error` — bulk mutations run even though edited approval failed (B18)
  - `LifestyleInflationScreen` uses `Modifier.weight(0f)` when essential/discretionary spending is zero — throws `IllegalArgumentException` (B19)

- **HIGH:**
  - `ALL` tab never records end-of-pagination — after last page, `shouldLoadMore` becomes `true` again, keeps issuing empty requests (B16, B17)
  - `ChangeTypeDialog` only enables Save when transaction type changes — existing `TRANSFER` rows cannot correct direction/account name (B16, B17)
  - Date chips not initialized from `currentFilter`, Apply falls back to previous filter — existing date filters cannot be viewed or cleared (B16)
  - Date headers sum unsigned `effectiveAmount`, render red only when aggregate negative — expense-heavy days shown as positive green totals (B16, B17)
  - Category/merchant/type/not-mine/shared edits update database but don't refresh `_pagedExpenses` — `ALL` tab shows stale rows (B16, B17)
  - `HomeViewModel.reloadDashboard()` doesn't recreate dashboard pipeline after `Error` — Home stuck on same error state (B17) **[RESOLVED - reload now rebuilds processed dashboard flow via a reload trigger]**
  - `TransactionsViewModel.applyFilter()` stores `TransactionFilter.ownership` in `_filter` but actual filtering uses `_ownershipFilter` — external ownership filter shown as active but results unfiltered (B17)
  - Manual expense creation and recurring-rule creation not atomic — expense insert succeeds, recurring-rule creation throws, UI reports failure (B17)
  - Year-over-year analytics computed from `purchases` containing only selected period — prior-year data missing for TODAY/WEEK/MONTH/QUARTER (B17)
  - `BudgetForecastingViewModel` on first forecast failure, `_uiState.budget` remains null — `refreshForecast()` becomes no-op, Retry cannot recover (B17) **[RESOLVED - budget and forecast period are retained across failures so retry can recover]**
  - `SharedExpenseGroupsViewModel.loadGroups()` rebuilds state from scratch, wiping `selectedGroup` and dialog flags (B18)
  - `Expense.isNotMine` + `isSharedExpense` simultaneously allowed; `effectiveAmount` zeroes both — rows disappear from analytics (B16) **[RESOLVED - ownership flags are now normalized in entity helpers, repositories, add-expense UI, and migration cleanup]**
  - `TransactionsViewModel` external `dateRange` filters intersected with default `MONTH` tab window — drill-down navigation clips results (B16)
  - `VisualSplitEditorScreen` "Apply Split" hands data to callback but navigation host navigates back and discards result — no-op (B19)
  - `SavingsGoalsViewModel` contributions use read-modify-write snapshots plus `updateGoalAmount()` — concurrent contributions lose money (B18)
  - `AssistantViewModel` clarification replies intentionally drop `conversationHistory` — follow-up answers interpreted without prior context (B19)
  - `AiSettingsViewModel.testConnection()` persists typed API key before connectivity test succeeds — failed tests overwrite working key (B19)
  - `VisualSplitEditorScreen` accepts `currencyCode` but formatted amounts use locale default currency (B19)
  - Visual split assigned amounts matched by `participantName` — duplicate names make multiple rows resolve to same segment (B19)
  - `LifestyleInflationViewModel.analyze()` launches detached jobs without cancelling prior requests — slower older analyses replace newer report (B19)
  - `CarbonFootprintViewModel.loadReport()` has same detached-job pattern — quick period changes leave stale report (B19)
  - `AddGroupExpenseUseCase` accepts zero/negative/non-finite amounts and blank descriptions (B40)
  - `ReviewQueueRepository` approved transfer/deposit reviews never copy `suggestedDirection`/`suggestedAccountName` into `Expense` — transfer metadata lost (B18)
  - Review approval pipeline loses optional metadata end-to-end — place id dropped, transfer metadata never copied (B18)
  - Currency presentation not centralized; multiple screens hardcode `€` (B18)

- **MEDIUM:**
  - `ExpenseWithCategory` member formatters shadow extension formatters — row resolves to member formatters using raw `amount` (B16)
  - Active-filter banner only depends on `activeFilter != null` — ownership-only filtering leaves list filtered with no visible banner (B16, B17)
  - Shared-expense editing accepts blank participant names and both-or-neither share fields (B16)
  - Month/year filter ranges use `System.currentTimeMillis()` instead of `TimeProvider`, end timestamps stop at `:59.000` (B16)
  - `BudgetViewModel.uiState` recalculates `calculateAdjustedSpend()` for every budget on every emission (B17) **[RESOLVED - adjusted budget statuses now refresh on a dedicated budget trigger instead of every combined emission]**
  - Budget suggestions loaded only from `_refreshTrigger` — add/delete/toggle mutations don't bump trigger (B17)
  - `AdvancedAnalyticsViewModel` on exception only sets `_dashboardData` to null — screen falls through to blank surface with no error/retry (B17)
  - `BudgetViewModel` suggestions loading wrapped in one-shot flow with no local recovery — one failure terminates shared UI pipeline (B17)
  - `ReviewScreen` `consumePrefilledReceiptSuggestion()` called during composition — recomposition mutates ViewModel state (B18)
  - `ReviewScreen.processingIds` only guards swipe actions, never cleared, approve/reject buttons bypass it (B18)
  - `ReviewViewModel` AI explanation "in-flight" guard populated only after `settings().first()` — rapid taps launch duplicate requests (B18)
  - `ReviewViewModel.approveAllPending` searches by `finalMerchant` instead of original merchant — "approve all identical" silently misses matches (B18)
  - Edit sheet captures `osmId` but `approveReviewWithEdits()` doesn't accept or persist it (B18)
  - `CashFlowCalendarScreen` starting-balance field writes parsed doubles on every keystroke, triggers uncancelled recalculations (B18)
  - `CashFlowCalendarViewModel.setStartingBalance()` always calls `loadCurrentMonth()` — editing balance while viewing another month jumps back (B18)
  - `SpendingMapViewModel` located/unlocated counters only refreshed at init — external updates leave stats bar stale (B18)
  - `CurrencyManagementViewModel.homeCurrency().collect` launches new load coroutine for every emission without cancelling previous (B18)
  - `SuggestCategoryFallbackUseCase` cached suggestions deserialized without re-validating — renamed/deleted categories leak stale data (B36)
  - `SuggestReceiptExtractionUseCase` hard-fails when OCR text missing — image-aware receipt path blocked (B36)
  - `SuggestReceiptExtractionUseCase` `force` flag ignored on cache hits (B36)
  - `SuggestReceiptExtractionUseCase` broad `catch (Exception)` swallows `CancellationException` (B36)
  - `SpendingChallengesScreen` never collects `completedActions`, `emptyStateActions` remembered once — stale empty-state actions (B19)
  - "Month" semantics differ across Home/Analytics/Transactions — calendar-month vs rolling 30 days (B17)

- **LOW:**
  - `Block Party` maps `categoryName = expense.categoryId?.toString()` — field named `categoryName` but stores ID string (B05)
  - Deleting from `ALL` tab calls `refresh()`, resets pagination to page 0 — users lose position (B17)
  - `ErrorBanner` rendered outside scaffold-padded/scrollable content — overlaps top app bar (B17)
  - `SpendingPatternsCard` uses `maxOfOrNull(...) ?: 1.0` — when all weekday totals `0.0`, produces `NaN` passed to `fillMaxHeight()` (B17)
  - Loading states ignore scaffold padding in multiple screens (B19)
  - Hardcoded English copy in multiple screens (B19)

### B.10: Categorization/Intelligence Pipeline
**Batches:** 31, 38, 41, 42, 44

**[PARTIALLY_RESOLVED - 16/35 verified resolved, 17 still open, 2 false positives]**

- **CRITICAL:**
  - (None)

- **HIGH:**
  - `SpendingChallengeManager.checkNoSpendStreak()` walks backward forever with one DB read per day — unbounded for empty/sparse histories (B38)
  - Challenge spend calculations use `expense.amount` instead of `effectiveAmount` — shared/not-mine semantics diverge (B38)
  - Budget-style challenges use remaining-budget percentage as both progress and completion — fresh under-budget challenge marked completed immediately at 0 spend (B38)
  - `CategoryKeywords` equal-confidence duplicate keywords across categories resolved by declaration/order effects — common merchants routed to earlier category (B38)
  - `ExpenseCategoryClassifier` category-learning writes deferred until 100 samples, `saveModel()` returns before file write completes — process death loses learned corrections (B41)
  - `HybridExpenseClassifier` gates ML predictions on `nbClassifier.isReady()` which only checks in-memory counters — persisted model on disk ignored after app restart (B42-missed)
  - `TransactionClassifier.cleanup()` permanently cancels singleton's private scope, app calls from `onStop()` — after first background transition, scheduled saves/retrains cancelled for rest of process (B42)
  - `AnomalyDetector` IQR/MAD zero-dispersion bailout — obvious spikes like `[10,10,10,100]` missed (B01)
  - `SpendingChallengeManager.REDUCE_SPENDING` challenge type has no stored baseline/reference period — progress formula identical to simple budget cap (B38)
  - `SpendingChallengeManager → SpendingChallengesViewModel` — `createChallenge()` only returns in-memory object, no repository-backed persistence (B38)

- **MEDIUM:**
  - `MerchantCanonicalizer` treats single-character prefix `"s"` as removable business prefix — `"s market"` → `"market"` (B38)
  - `ContextualInferenceEngine.isLikelySurname()` drops words shorter than 3 chars before checking `BUSINESS_INDICATORS` — 2-letter legal suffixes like `AE`/`SA`/`AB` ignored (B38)
  - `GreeklishNormalizer.getVariations()` compares normalized input against raw alias lists — case/spacing/Greek-script variants miss (B38)
  - [RESOLVED] `CurrencyConverter.storeRate()` accepts zero, negative, `NaN`, infinite exchange rates (B38)
  - `Merchant approval/rejection history` cached under lowercase keys, DB queries use raw merchant string — casing/spacing variants miss prior corrections (B41)
  - `CrossSourceDeduplication.isCrossSourceDuplicate()` doesn't compare real transaction data — bank-like sources with non-blank merchant treated as same transaction (B41, B42)
  - `TransactionClassifier` retraining rebuilds counts without clearing `vocabulary` — old tokens remain, inflate `vocabularySize` (B41, B42)
  - `FeatureExtractor.extractFromNotification()` uses current wall clock instead of notification timestamp — reprocessing produces different features (B41)
  - [RESOLVED] `HybridExpenseClassifier` categories loaded once and cached forever — added/renamed categories invisible until restart (B41)
  - [RESOLVED] `SemanticKeywordMatcher` wraps every keyword in `\b...\b` — keywords ending in non-word characters like `disney+` never match (B38-missed)
  - `CategoryRepository.ensureDefaultCategories()` seeds merchant dictionary only when categories table empty — existing installations never receive seeded mappings (B31-missed)
  - `AppleReceiptParser` and `UberReceiptParser` `detectCurrency()` uses raw substring checks for short region fragments — `MUSIC`, `ORDER`, `DETAILS` select wrong currency (B31-missed)
  - Dead feature pipeline — classifier trains/classifies only on `merchantTokens`; amount, day, hour, weekend, source-package features extracted but thrown away (B42)

- **LOW:**
  - `SpendingChallengeManager.durationDays * 24 * 60 * 60 * 1000L` performs most multiplication in `Int` — large durations overflow (B38)
  - `daysRemaining` can go negative after expiry (B38)
  - Challenge IDs use `System.currentTimeMillis()` — can collide for rapid consecutive creations (B38)
  - `CategorizationEngine` reloads cache fragments via three separate accessors per call (B38)
  - Fuzzy matcher prefilters by first two characters — typo in first two chars prevents valid matches (B38)
  - `getCategoryIdByName()` populates cache under lock, then reads outside snapshot — concurrent invalidation can null out map (B38)
  - `CategoryKeywords` `"roasters"` declared twice — last entry silently downgrades confidence from `0.85` to `0.0.70` (B38-missed)
  - Levenshtein distance duplicated across modules instead of reusing `StringDistanceUtils` (B38)
  - `MerchantNormalizer` truncates long names for matching but alias persistence stores original `rawName` (B41)
  - [RESOLVED] `fuzzyMatch()` picks first BK-tree result, then computes Jaro-Winkler — equal-distance candidates can resolve suboptimally (B41)

### B.11: Email/Parsing Pipeline
**Batches:** 31, 43, 44

**[PARTIALLY_RESOLVED - additional verified Phase B.11 fixes landed in this pass; resolved bullets are marked inline below]**

- **CRITICAL:**
  - (None)

- **HIGH:**
  - Email ingestion dedupes by fingerprint before checking `messageId`, then persists with `REPLACE` — reprocessing same message with different parsed data creates second `ScannedReceipt`/`Expense`, silently rewrites email-source linkage (B31)
  - `createExpenseFromReceipt()` swallows all failures, returns `emptyList()`, but `processEmailReceipt()` still returns `Success` — email marked as processed even when no expense exists (B31)
  - `cleanHtml()` strips all tags/entities and collapses all whitespace into single spaces — destroys line boundaries used by provider regexes, removes HTML entities instead of decoding them (B31)
  - Amazon date regex has no capture group but `extractDate()` always reads `group(1)` — throws and aborts parsing for valid receipts (B31)
  - Apple date extraction has same capture-group invariant break — two patterns expose no group 1 (B31)
  - Uber date pattern stores `AM/PM` in group 1, actual date in group 2, but `extractDate()` always reads group 1 (B31)
  - All three provider parsers assume English month names and dot-decimal amounts — localized receipts rejected or misdated (B31)
  - `GenericTransactionParser` treats `transfer received` as deposit, emits `DEPOSIT` instead of `TRANSFER` (B43)
  - `BankStatementParser` Revolut path emits only `DEPOSIT` or `PURCHASE` — transfers/top-ups/refunds misclassified (B44)
  - `OcrLanguageProcessor.normalizeForLanguage()` routes Cyrillic/Arabic/CJK through Latin-only normalization (B44)
  - `OcrLanguageProcessor` amount extraction mishandles locale-specific separators (B44)
  - `AndroidSpeechInputGateway` voice input starts without `RECORD_AUDIO` permission guard or `SecurityException` handling; recognizer `onError()` signals dropped (B31)
  - `BillReminderManager` `SEMI_ANNUALLY` not handled in reminder scheduling or monthly-cost conversion (B43-missed)

- **MEDIUM:**
  - Seeded merchant mappings uppercased and inserted without `normalizedCanonicalName` — fuzzy layer filters with case-sensitive `startsWith(prefix)` against lowercase input, seeded mappings never participate in fuzzy fallback (B31) **[RESOLVED - seeded and learned mappings now share canonical-name construction and missing canonical names are backfilled]**
  - `ProcessReceiptUseCase` injected but email receipt service never calls it — email imports have separate behavior path that can drift (B31)
  - `CustomSplitParser` validates with raw `Double` sums and inclusive tolerances — boundary-valid payloads rejected by floating-point drift (B43)
  - `CUSTOM_AMOUNT`/`UNEQUAL` splits accept arbitrary decimal precision — sub-cent liabilities stored (B43)
  - `RecurringExpenseEngine` groups merchants with `lowercase().trim()` instead of canonical merchant key (B43) **[RESOLVED - recurring grouping now prefers stored `merchantKey` and falls back to canonical merchant-key generation]**
  - `SynthesisEngine.pastSumDaily.lastOrNull()` used without `isFinite()` guard — single `NaN`/`Infinity` poisons every projected point (B43) **[RESOLVED - past spending series is sanitized before tail lookup and forecast emission]**
  - `GenericTransactionParser` date extraction uses lenient `Calendar` normalization — impossible dates accepted (B43)
  - `GreekBankParser` transfer parsing accepts Latin one-letter codes but direction detection only recognizes Greek/full-word codes (B43)
  - `SmsParser` and `RevolutParser` amount regex only accepts single decimal separator — thousands-separated amounts rejected (B44-missed)
  - `EmailReceiptIngestionService` inserts `ScannedReceipt` and `EmailReceiptSource` in separate DAO calls — partial-write state (B13)

- **LOW:**
  - (None specific to this pipeline beyond those captured above)

### B.12: Groups/Shared Expenses Pipeline
**Batches:** 33, 40, 43

**[PARTIALLY_RESOLVED - additional verified Phase B.12 fixes landed in this pass; resolved bullets are marked inline below]**

- **CRITICAL:**
  - (None)

- **HIGH:**
  - `SharedExpenseBudgetOffsetEngine` swallows any failure in budget-offset calculation, replaces with all-zero breakdown — failures indistinguishable from genuine zero shared spend (B40)
  - Delete member / current-membership reads vs historical split recomputation: equal-split historical expenses recomputed from *current* member list — after member removed, old expenses change who owes what (B40)
  - `SharedExpenseBudgetOffsetEngine` uses open-coded share math while balances/settlements use cent-based split pipeline — same expense produces different liabilities across budget and group-settlement surfaces (B40)
  - `CustomSplitParser` / `SplitCalculator` vs `SharedExpenseBudgetOffsetEngine`: settlement code uses strict validation plus cent-based fallbacks, budget-offset code reparses raw string with weaker parser — malformed payloads return partial/zero shares (B43)
  - `RecurrenceCalculator` vs `RecurringExpenseRepository` vs `BillReminderManager`: recurrence semantics not centralized — `IRREGULAR` advances differently in each path, string-based mappings mis-handle `ANNUALLY` and omit `SEMI_ANNUALLY` (B43)
  - `SplitCalculator` converts money to cents with `Int` — amounts above ~€21.47M overflow, produces corrupted negative splits (B43-missed)
  - `SharedExpenseBudgetOffsetEngine` group expense creation inserts full system `Expense` then adds user's group share on top — adjusted budget pipeline can overcount linked group expenses (B33-missed)

- **MEDIUM:**
  - `SharedExpenseManager.addExpense()` can persist group expense whose `paidById` belongs to different group — DB enforces member exists but not same-group membership (B40) **[RESOLVED - payer must now belong to the target group's current member set]**
  - `SharedExpenseBudgetOffsetEngine.getPendingReimbursement()` subtracts `totalReimbursed` from `totalSharedSpend` — fields represent different concepts, sign and amount can be wrong (B40)
  - `isExpenseFullySettled()` uses `myShareAmount ?: totalAmount / members.size` as generic fallback — ignores custom splits, misuses current-user-specific field (B40)
  - Equal-split budget math uses naive floating-point division while authoritative group split uses cent-based remainder distribution (B40)
  - `SharedExpenseManager.addExpense()` validates custom-split finiteness but doesn't reject blank descriptions, non-finite totals, non-positive amounts (B40-missed) **[RESOLVED - blank descriptions and invalid total amounts are now rejected up front]**
  - `SharedExpenseBudgetOffsetEngine.calculateMyShare()` diverges from authoritative split pipeline for `CUSTOM_PERCENT` and malformed payloads (B40-missed)
  - `SharedExpenseBudgetOffsetEngine.calculateEffectiveBudgetSpend()` accepts `userId` but never uses it (B40-missed)
  - `SharedExpenseGroupsViewModel` computes splits and balances via `SplitCalculator` directly instead of consuming domain services (B40)
  - Room-entity repository path vs domain-port path: group subsystem has two parallel access patterns (B40)
  - `SynthesisEngine` resolves `budgetLimit` as `overall budget or category-budget sum`, but Block Party receives only `overallBudget?.budgetAmount` (B43)
  - `SharedExpenseDataPortAdapter.addMember()` bypasses `GroupTransactionCoordinator.addMemberToGroup()` — archived/inactive-group validation skipped for member creation (B33-missed) **[RESOLVED - member creation now routes through coordinator validation before resolving inserted ID]**
  - Validation pipeline for group creation vulnerable to archive/member-change races (B11)
  - UI validation → database: invariants only inconsistently enforced above DB (B12)
  - `customSplitsJson` not actually JSON; parsing split between `CustomSplitParser` and `SharedExpenseBudgetOffsetEngine` (B13)

- **LOW:**
  - `SharedExpenseManager.isCurrentUser = (name == currentUserName)` is case-sensitive (B40)
  - `SharedExpenseManager.addExpense()` hardcodes `System.currentTimeMillis()` (B40) **[RESOLVED - group expense creation now uses injected `TimeProvider`]**
  - `AddGroupExpenseUseCase` hardcodes `System.currentTimeMillis()` in default `date` parameter (B40)
  - `SharedExpenseBudgetOffsetEngine` hardcodes `Dispatchers.IO` (B40)
  - Personal-spend summation uses `amount` instead of `effectiveAmount` (B40) **[RESOLVED - budget-offset personal spend now sums `effectiveAmount`]**
  - `SharedExpenseBudgetOffsetEngine` imports Room entities and repository implementations directly (B40)
  - `AddGroupExpenseUseCase` depends directly on data-layer types (B40)

---

## Section C: Cross-Component Pipeline Dependencies

### C.1: Blocking Fixes (Must Fix First)

1. **effectiveAmount standardization** → Blocks: All analytics, budget, business, tax, currency, challenge, and receipt-matching pipelines that currently use raw `amount`
2. **Budget period/window centralization** → Blocks: Budget status, forecasting, shared-budget progress, alerts, dashboard weather, recommendations
3. **Duplicate detection policy centralization (currency-aware)** → Blocks: Notification ingestion, review approval, statement import, cross-source dedupe
4. **Domain/data boundary cleanup (BlockPartyDay, DashboardExpenseMapper, AI models)** → Blocks: Dashboard widgets, analytics, AI artifact diagnostics, recommendation engine **[STILL_OPEN]**
5. **TimeProvider injection everywhere** → Blocks: Deterministic testing, rollover-aware reactive flows, worker day-key consistency, feature extraction reproducibility **[PARTIALLY_RESOLVED - D3 time-determinism pass removed targeted wall-clock defaults (including `SourceStats` creation paths), normalized single-capture `now` usage in targeted synthesis/income flows, and completed deterministic parser/date anchoring fixes; broader cross-app rollout still open]**
6. **CancellationException handling across all catch blocks** → Blocks: Structured concurrency, proper job lifecycle, stale artifact prevention **[STILL_OPEN]**
7. **Deduplication locale-invariant amount formatting** → Blocks: Cross-locale duplicate detection in notification ingestion, statement import, review approval (B12)

### C.2: Sequential Fix Dependencies

- Step 1: Centralize effective-amount SQL helper → Enables: Fix all analytics, budget, business, tax, currency, challenge, receipt-matching pipelines (A.1 → B.2/B.3/B.8/B.10/B.12)
- Step 2: Make BudgetCalculator single source of truth for period math → Enables: Fix forecasting, shared-budget, alerts, dashboard weather (B.2 → B.6)
- Step 3: Fix duplicate detection (currency + transaction type) → Enables: Fix notification pipeline, review approval, statement import (A.4 → B.6/B.11)
- Step 4: Remove DashboardExpense→Expense round-trip → Enables: Fix dashboard widgets, analytics, block-party, spending pace (A.2 → B.9)
- Step 5: Inject TimeProvider everywhere → Enables: Fix rollover-aware flows, worker consistency, feature extraction reproducibility (A.3 → B.5/B.6/B.10) **[PARTIALLY_RESOLVED - D3 time-determinism pass closed targeted D.3 wall-clock/multi-now hotspots; full app-wide rollout remains open]**
- Step 6: Fix CancellationException handling → Enables: Proper structured concurrency across AI, workers, services (A.7 → B.1/B.6) **[STILL_OPEN]**
- Step 7: Centralize split-resolution logic → Enables: Fix budget-offset, settlement, UI calculation paths (B.12 → B.9) **[STILL_OPEN]**
- Step 8: Fix AI routing/privacy policy → Enables: Fix all AI use case input builders, cloud providers, artifact persistence (B.1 → B.3)

### C.3: Independent Fix Groups

- **Group 1: Database schema constraints** — Unique indexes, FK constraints, CHECK constraints across entities (B.4)
- **Group 2: Email parser fixes** — Capture groups, HTML parsing, locale-aware amounts, currency detection (B.11)
- **Group 3: Geocoding service fixes** — Retry semantics, Unicode normalization, log sanitization, HTTP cancellation (B.5)
- **Group 4: Export format fixes** — Currency column, transaction type, PDF generation, formula-injection escaping (B.7)
- **Group 5: UI Compose fixes** — Pagination, filter state, ownership validation, date header signs (B.9) **[STILL_OPEN]**
- **Group 6: Notification pipeline fixes** — Package allowlist, text fallback chain, recommendation state management (B.6)
- **Group 7: Investment tracker fixes** — Fee inclusion, day-change calculation, portfolio history, all-time extrema (B.8)
- **Group 8: Tax estimator fixes** — Progressive brackets, period math, annual summary, business-only scope (B.8)
- **Group 9: Analytics engine consistency** — `InsightsEngine`, `AdvancedAnalyticsDashboard`, `AdvancedAnalyticsEngine`, and `SpendingPersonalityClassifier` re-implement each other's logic; merchant naming, day ordering, and anomaly baselines already diverge — fix at source engines before fixing consumers (B36-missed) **[RESOLVED BY D3-ANALYTICS-FORECASTING - InsightsEngine now delegates to canonical analytics engines/calculators; no inline reimplementations remain; merchant anomaly baselines exclude current-month data; recurring frequency uses cadence labels]**
- **Group 10: Lifestyle savings prompt pipeline** — `LifestyleSavingsPromptUseCase` evaluates savings rate and fires prompts but never records impressions; cooldown never starts after show, only after action — fix impression recording to close the loop (B33-missed)
- **Group 11: `ExpenseDao` weekly aggregates** — `MIN(date)/MAX(date)` transaction timestamps forwarded as week boundaries — final transaction of week or day can be omitted from weekly analytics (B36-missed) **[RESOLVED BY D3-TIME-DETERMINISM - weekly boundaries are now canonicalized to Monday-start/next-Monday-exclusive ranges via `TimePeriodUtils` normalization in the repository path]**
- **Group 12: Financial health KPI duplication** — `FinancialHealthCalculator ↔ FinancialHealthScoreV2 ↔ ComputeDashboardWidgetsUseCase` — two incompatible health KPIs side by side with different formulas/filters/week definitions (B03) **[RESOLVED - dashboard now emits a single authoritative health KPI: V2 when available, legacy only as fallback]**
- **Group 13: FinancialHealthScoreV2 exception swallowing** — `FinancialHealthScoreV2 → ComputeDashboardWidgetsUseCase` V2 swallows fatal calculation exceptions into `50` — dashboard renders as real health data (B03) **[RESOLVED - both the top-level score path and the bill-reliability subpath now propagate non-cancellation failures instead of fabricating fallback scores, so callers can suppress the widget rather than mixing real data with synthetic health output]**
- **Group 14: Dual Monte Carlo implementations** — `FinancialStressForecastEngine` injects but doesn't use `MonteCarloSpendingSimulator` — two separate Monte Carlo implementations with divergent assumptions coexist (B04) **[STILL_OPEN]**
- **Group 15: MonthlySavingsSweepUseCase dead widget** — `MonthlySavingsSweepUseCase → ComputeDashboardWidgetsUseCase → HomeScreen` — `DashboardWidget.SavingsSweepPrompt` never emitted, `HomeScreen` renders empty placeholder (B05) **[RESOLVED - dashboard now computes, emits, and renders the sweep prompt widget]**
- **Group 16: AI artifact → recommendation persistence** — `RecommendationEntity.sourceArtifactId` joins and cleanup cannot be enforced safely — stored as required `String` with empty-string sentinels (B12) **[STILL_OPEN]**
- **Group 17: Merchant analytics inconsistency** — some paths group by raw merchant text, some by canonical `merchantKey`, one path exposes key as display name — fix at engine level before fixing consumers (B36) **[RESOLVED BY D3-ANALYTICS-FORECASTING - all merchant analytics paths (AdvancedAnalyticsEngine, MerchantInsightEngine, InsightsEngine, AnalyticsViewModel) now group by canonical merchantKey via AnalyticsWindowingSupport; display names resolved separately; O(merchants × history) eliminated]**
- **Group 18: Forecasting duplication and divergent assumptions** — `FinancialStressForecastEngine ↔ MonteCarloSpendingSimulator ↔ DataQualityAssessor` — different period math, sampling strategies, and confidence models (B37) **[RESOLVED BY D3-ANALYTICS-FORECASTING - confidence/horizon semantics explicitly documented as intentionally isolated (stress=probability-tiered, MonteCarlo=data-quality-tiered) with adapter notes in FinancialStressForecastEngine; shared ForecastInputAssembler converges recurring inputs]**
- **Group 19: RevolutParser ↔ BankStatementParser inconsistency** — same Revolut bank produces different transaction types (TRANSFER vs PURCHASE/DEPOSIT) depending on parser path (B44)
- **Group 20: ReceiptTransactionMatcher → ReceiptMatchingWorker → NotificationService chain** — matching error becomes data-integrity + notification mismatch (B45) **[STILL_OPEN]**
- **Group 21: Savings recommendation ↔ automation ↔ gamification divergent ledger** — use different proxies instead of shared contribution ledger (B45) **[STILL_OPEN]**
- **Group 22: TaxConfiguration vs TaxEstimator contract mismatch** — `TaxConfiguration` exposes progressive brackets but `TaxEstimator` uses flat-rate (B45)
- **Group 23: ReceiptRepository.processBatch() parallelism vs WarrantyTextExtractor thread safety** — singleton warranty path reuses one `WarrantyTextExtractor` with shared `SimpleDateFormat` — thread-safety exposed in real pipeline (B45)
- **Group 24: Block-party domain boundary violation** — `BlockPartyDay` carries `Expense`, use case maps to `DomainExpenseSummary`, UI mapper recreates `Expense` — crosses domain boundary twice (B46)
- **Group 25: Duplicate model types** — two `CategoryBreakdown` types / two `PeriodRange` types with overlapping semantics used by different screens/components (B46) **[PARTIALLY_RESOLVED - analytics-local `CategoryBreakdown` was removed in favor of `AnalyticsCategoryBreakdown`, but duplicated `PeriodRange` semantics remain open]**
- **Group 26: Inconsistent localization boundary** — raw `String`, `UiText`, hardcoded currency text, direct Android `R` in domain logic (B46) **[RESOLVED - validated C.1 files now use domain-safe `UiText`/message-key contracts and presentation-layer resolution instead of Android resources in domain code]**
- **Group 27: RecommendationRepository in-memory dedup vs JSON ordering** — parses JSON into normalized fields but compares rows using `filterCriteria.hashCode()` — semantically identical filters with different JSON ordering bypass cross-call deduplication (B47)
- **Group 28: Financial weather vs dashboard forecast divergence** — uses merged detected+manual recurring patterns, but dashboard forecast widgets get only manual recurring rows — weather/runway/block-party/Monte Carlo can disagree (B48) **[RESOLVED BY D3-ANALYTICS-FORECASTING - all forecast surfaces (FinancialWeatherRepository, CalculateFinancialForecastUseCase, ComputeDashboardWidgetsUseCase) now consume ForecastInputAssembler with unified recurring merge policy (manual + high-confidence detected ≥ 0.70f, manual precedence); SynthesisEngine consumes shared input contract]**

---

## Section D: Isolated / Quick-Win Bugs

### D.1: Critical (Quick Wins)
- `CarbonFootprintCalculator.calculateCarbonFootprint()` collects Room Flow in one-shot suspend — replace `collect` with `first()` (B37, B42) **[RESOLVED BY AUDIT]**
- `ReviewViewModel.approveReviewWithEdits()` runs bulk mutations after primary approve fails — add early return unless `Result.Success` (B18) **[RESOLVED BY AUDIT]**
- `LifestyleInflationScreen` `Modifier.weight(0f)` throws `IllegalArgumentException` — clamp weights to positive minimum (B19) **[RESOLVED BY AUDIT]**

### D.2: High (Quick Wins)
- `RecommendationStateManager` sorts by `compareByDescending { it.priority }` using enum ordinal — `LOW` placed ahead of `HIGH` (B20)
- `BankConnectionDao.disconnect()` leaves token fields intact — null them out in same update (B15) **[RESOLVED BY B.4 — Batch 6: token fields nulled in same update]**
- `BillReminderManager` urgency thresholds don't match enum semantics — overdue/today should be `CRITICAL` (B39) **[RESOLVED - due today/overdue now map to `CRITICAL`, 1-2 days to `URGENT`, 3-7 days to `WARNING`]**
- `BillReminderManager.markBillPaid()` advances only one interval from stored due date — advance from `max(now, currentDueDate)` (B39)
- `BudgetForecastingViewModel` on first failure, `_uiState.budget` remains null — persist requested budget before running forecast (B17)
- `CalculateFinancialForecastUseCase` maps all savings goals to `TRACKING` — map entity protection enum to domain enum (B05, B48)
- `MonthlySavingsSweepUseCase` goal allocations never capped by remaining gap — cap each goal by remaining target (B05)
- `SharedBudgetManager.getMemberContributions()` returns hardcoded zero placeholders — disable API or implement real calculation (B02, B37) **[RESOLVED - placeholder output removed; API now fails fast with explicit unsupported state until real budget↔member attribution exists]**
- `PriceProtectionTracker` generates price drops/deals from hard-coded heuristics rendered as real results — hide behind debug providers (B42, B44)
- `BankApiIntegration` returns successful OAuth URLs, demo tokens, mock sync results — gate behind "not implemented" error (B39)
- `ManualRecurringExpense.isSubscription` defaults to `true` — change default to `false` (B12, B13) **[RESOLVED BY B.4 — Batch 4: default changed to `false`]**
- `TaxEstimator.getTaxYearSummary()` hardcodes annual income to `30000.0` — feed real annual income (B45)
- `AdvancedAnalyticsDashboard` incoming `TRANSFER` transactions counted as income — filter transfers from cashflow calculation (B36-missed) **[RESOLVED - transfers no longer inflate income/cashflow totals]**
- `AdvancedAnalyticsDashboard` top categories rendered as placeholder labels like `Category 5` — resolve category names from category store (B36-missed)
- `InsightsEngine` merchant insights expose `ms.merchantName` (canonical key) not display label — use resolved display label (B36-missed) **[RESOLVED - merchant insights now prefer DAO display label and keep canonical key internal]**
- `InsightsEngine` merchant-level anomaly detection uses all-time stats including current-month — exclude current month from baseline (B36-missed)
- `InsightsEngine` `RecurringExpense.frequency` set to `30 / intervalDays` — use actual occurrence count (B36-missed)
- `SpendingPersonalityClassifier.calculateImpulseRatio()` uses `abs(purchase.date - incomeDate)` — only count purchases after payday (B36-missed)
- `SpendingPersonalityClassifier` budget adherence scales 3-month window against raw budget without period scaling — scale budget to comparison window (B36-missed)
- `SynthesisEngine.calculateBlockPartyData()` sorts `topTransactions` by raw `Expense.amount` while rest of budgeting uses `effectiveAmount` — sort by `effectiveAmount` (B46-missed)
- `DashboardFollowThroughRecommendation.expiresAt` derived from `createdAt` only at construction — later `copy(createdAt = ...)` leaves `expiresAt` stale, breaking TTL invariant (B24)
- `DashboardExpenseMapper` `DashboardExpense` → `Expense` reconstruction loses shared-expense fields — `isSharedExpense`, `myShareAmount`, `mySharePercentage` not carried through (B24)
- `SuggestReceiptExtractionUseCase` `sourceHash` derived from `ReceiptAssistInput.hashCode()` including `currentTimeMs` — cache effectively disabled (B36) **[RESOLVED - receipt-assist cache identity now uses stable SHA-256 over deterministic business fields only]**
- `BillReminderManager.calculateNextDate()` `ANNUALLY`, `SEMI_ANNUALLY`, `IRREGULAR` fall through to default monthly advance — stringly-typed enum drift (B39)
- `MultiCurrencyRepository` every reporting method calls `expenseDao.getExpensesBetween()` with default 2000-row cap — large reporting windows return incomplete converted totals (B32-missed)

### D.3: Medium (Quick Wins)
### SubBatch D.1
- `BudgetDao.getOverallBudget()` and `getByCategory()` assumed single active row — both queries now use deterministic `ORDER BY id DESC LIMIT 1` on active budgets **[RESOLVED]**
- `ExpenseDao.searchMerchants()` uses `UPPER(merchant) LIKE '%...%'` — use normalized/indexed search key (B14)
- `WarrantyDao.getTotalProtectedValue()` treated `status = 'ACTIVE'` as sufficient — query now also filters by `warrantyEndDate > :currentTime` **[RESOLVED]**
- `WarrantyDao.getTotalProtectedValue()` sums raw `expense.amount` instead of `effectiveAmount` (B12)
- `ExpenseDao` → `BudgetRepository.getSuggestions()` N+1 per-category loop (B14) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 5: suggestions path now batches category spend retrieval and removes per-category query fan-out]**
- `CsvExpenseImporter` `line.split(",")` breaks quoted CSV fields — merchants/descriptions with commas corrupt column parsing (B23) **[RESOLVED - importer now uses quote-aware CSV tokenization with escaped-quote handling instead of naive `split(",")`]**
- `CsvExpenseImporter` failed date parse silently substitutes `System.currentTimeMillis()` — historical expenses rewritten with today's date (B23) **[RESOLVED BY D3-TIME-DETERMINISM - invalid date rows are now surfaced as import failures instead of being rewritten to wall-clock `now`]**
- `RecurringPattern.kt` missing invariants — model now enforces positive finite amounts, non-negative variances/dates, bounded confidence, and non-blank merchant/currency **[RESOLVED]**
- `WarrantyExtractionModels.kt` missing invariants — allows negative `warrantyMonths`, negative `returnDays`, out-of-range `confidence` (B24) **[RESOLVED - model now enforces positive optional day/month values and bounded finite confidence]**
- `NotificationParsingModels.kt` missing invariants — documents positive amount and bounded confidence but enforces neither (B24) **[RESOLVED - model now enforces finite positive amount and bounded finite confidence]**
- `DomainTransactionFilter.correlationId` dropped by `TransactionFilterSerializer` — serializer now preserves `correlationId` in both serialize/deserialize paths **[RESOLVED]**
- Artifact hashing — `SuggestReceiptExtractionUseCase` now uses stable SHA-256 over deterministic business fields, but multiple AI use cases still derive `sourceHash` from `hashCode().toString()` (including dedupe judge, review explanation, dashboard briefing, transaction insight, and receipt-item categorization) **[RESOLVED - scoped AI artifact paths now use deterministic SHA-256 source hashes via `AiArtifactSourceHash` instead of `hashCode().toString()`]**
- `toReadableMessage()` / route-diagnostic formatting / failure-message assembly duplicated across AI use cases (B35)
- `MonteCarloSpendingSimulator.countRecentQualifyingWeeks()` treats any `total > 0` week as qualifying — confidence overstated (B40)
- `SpendingPatternsCard` `maxOfOrNull(...) ?: 1.0` produces `NaN` when all totals `0.0` — use `takeIf { it > 0 } ?: 1.0` (B17)
### SubBatch D.2
- `TransferDirection.valueOf(review.suggestedDirection)` is only partially fixed — review approval now parses defensively, but `ReviewScreen` still calls `TransferDirection.valueOf(it)` directly for transfer badges **[RESOLVED - ReviewScreen transfer badges now use safe parsing (`parseTransferDirectionOrNull`) and no longer call raw enum `valueOf` on untrusted strings]**
- `CategoryDao.getByName()` is case-sensitive — add unique `COLLATE NOCASE` index (B14)
- `ReceiptItemCategorizationDao.getTotalForCategoryInExpense()` counts rows where either suggested or corrected matches — use `COALESCE` (B14)
- `PendingReviewDao` legacy fallback queries have no index on `suggestedMerchant` — add composite index (B14)
- `MerchantNormalizationDao.getAliasByNormalizedKey()` no longer relies on arbitrary `LIMIT 1` behavior — `merchant_aliases.normalizedKey` is now unique, making lookup deterministic **[RESOLVED BY B.4 — Batch 5: `normalizedKey` uniqueness enforced]**
- `ManualRecurringExpenseDao` dual APIs disagree on ordering — make both use same ordering (B15)
- `GroupMemberDao.getCurrentUserFlow()` no longer depends on unordered `LIMIT 1` behavior — a partial unique index now enforces at most one `isCurrentUser = 1` row per group **[RESOLVED BY B.4 — Batch 3: partial unique index on `(groupId)` where `isCurrentUser = 1`]**
- `GroupExpenseDao.getGroupExpenseForExpense()` no longer returns an arbitrary row — a partial unique index now enforces one non-null `expenseId` mapping **[RESOLVED BY B.4 — Batch 3: unique index on non-null `expenseId`]**
- `MerchantLocationDao.upsertLocation()` read-then-insert under unique index — use single-statement upsert (B15)
- `ExchangeRateDao.getAllRatesForBase()` filters on non-leading index column — add index on `(toCurrency, fromCurrency)` (B15) **[RESOLVED BY B.4 — Batch 7 / late closeout (ISSUE-B4-7, ISSUE-B4-11): B.4 resolved the non-leading-column scan by adding a standalone `Index(["toCurrency"])` entity annotation to `ExchangeRate.kt`; `MIGRATION_78_79` lands `CREATE INDEX IF NOT EXISTS index_exchange_rates_toCurrency ON exchange_rates (toCurrency)` at schema version 79]**
- `EmailReceiptDao.getByReceiptId()` returns single row but multiple sources can share same receiptId — return `List` (B15-missed) **[RESOLVED BY B.4 — Batch 6: return type changed to `List<EmailReceiptSource>`]**
- `SplitItemAssignmentDao.getParticipantTotals()` groups by `participantName` only — group by stable key (B15)
- `UserCorrectionDao` tie-breaking uses `LIMIT 1` with no secondary ordering — add stable secondary sort (B27) **[RESOLVED BY B.4 — Batch 5: stable secondary sort (recency/id) added as tie-breaker]**
- `AiChatRepositoryImpl.appendMessage()` no longer splits message persistence and session timestamp updates across separate writes — both operations now run inside `database.withTransaction { ... }` **[RESOLVED]**
- `InvestmentTracker.getPortfolioValueHistory()` no longer issues one query per investment — it now uses batched DAO reads via `investmentValueDao.getPortfolioHistoryBatch(...)` **[RESOLVED]**
### SubBatch D.3
- `MileageTracking` reporting queries need composite index `(isBusinessTrip, date)` (B27)
- `BudgetForecastDao.getForecastForDate()` returns `LIMIT 1` without ordering — add `ORDER BY` (B27)
- `HealthScoreHistory` `(periodStart, periodEnd)` only indexed — make unique (B27)
- `SubscriptionUsageDao.getAllUsageSince()` effectively unindexed — add standalone index on `usedAt` (B27-missed) **[STILL_OPEN - Current schema still exposes only the composite index `(subscriptionId, usedAt)`; no standalone `usedAt` index exists for global `getAllUsageSince()` scans]**
- `SubscriptionCandidate.convertedSubscriptionId` has no FK — add nullable FK (B28-missed) **[STILL_OPEN - `SubscriptionCandidate` and canonical schema definitions still declare `convertedSubscriptionId` without a foreign key to `ManualRecurringExpense(id)`]**
- `MileageTracking` entity accepts impossible states — add validation (B28) **[RESOLVED - impossible mileage values are now rejected on repository write path (`BusinessExpenseRepository.addMileage(...)`) before DAO insert; entity constructor remains Room-safe]**
- `formattedAmount` hardcodes `Locale.US` — centralize formatting (B29-missed) **[RESOLVED BY B.4 — Batch 10: `ExpenseWithCategory.formattedAmount` now uses `effectiveAmount` with polarity sign rules, `Locale.getDefault()` via `String.format`, and a prefixed currency code string; `NumberFormat`/`Currency` API is not used]**
- `ExpenseWithCategory_Extensions` shadowed by member properties — delete duplicate extensions (B29) **[RESOLVED BY B.4 — Batch 10: shadowed duplicate extensions removed]**
- `getExpensesPagedDynamic()` selects subset of columns but maps to full `ExpenseWithCategory` — use `SELECT e.*` (B29) **[RESOLVED BY B.4 — Batch 10: projection changed to `SELECT e.*`; re-verified in late closeout (ISSUE-B4-11)]**


- `CloudReceiptItemCategorizationService` uses `ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS` for cloud — add cloud-specific constant (B09) **[RESOLVED - Cloud request now uses `AppConfig.Ai.CLOUD_RECEIPT_ITEM_MAX_TOKENS` in `buildRequestBody()`]**
- `CloudWarrantyExtractionService` hardcodes model name and token budget — use shared config (B09)
- `CloudReviewExplanationService` generates new correlation ID per retry — generate one before retry loop (B09)
- `CloudWarrantyExtractionService` accepts `"null"` string placeholders — filter placeholders (B09)
- `CloudReceiptItemCategorizationService` hardcodes `€` in prompts — use input currency (B09) **[RESOLVED - Prompt now formats line-item amounts via `CurrencyFormatter.format(item.totalPrice, input.currency)`; no hardcoded euro literal remains]**
- `OnDeviceDashboardBriefingService` confidence parsed with `optDouble.toFloat()` without finiteness check — parse strictly (B09-missed) **[RESOLVED - parser now uses strict bounded confidence parsing and rejects non-finite/out-of-range values]**
- `OnDeviceDedupeJudgeService` `matchedTargetId`/`confidence` use lenient parsing — use strict parsing (B09-missed)
### SubBatch D.4
- `OnDeviceCategorizationAssistService` lenient numeric parsing can emit `categoryId = 0`, `confidence = NaN` — require finite values (B09) **[RESOLVED - strict JSON parsing now requires positive category IDs and finite bounded confidence]**
- `NotificationFilter.shouldCapture()` lowercases content but regex only matches uppercase — make regex case-insensitive (B20) **[RESOLVED - `REGEX_CURRENCY` now uses `RegexOption.IGNORE_CASE`]**
- `RecommendationStateManager.clearForUser()` clears in-memory state for non-current user — only clear when `currentUserId == userId` (B20) **[RESOLVED - state clear is now conditional on `currentUserId == userId`]**
- `RecommendationDeduplicator.computeSignature()` always includes `rec.category` — build target-specific signatures (B20) **[RESOLVED - signature no longer includes `rec.category`; it is derived from navigation target plus deserialized filter fields]**
- `RecommendationInvalidator` swallows exceptions with empty catch — log failures (B21) **[RESOLVED - invalidation/clear/cleanup paths now log failures with `Timber.e(...)`]**
- `NotificationSeeder` derives package names from display labels — map to valid parser package IDs (B39)
- `NotificationSeeder.generateRecurring()` produces isolated random charges — generate clustered occurrences (B39)
- `ServiceDiagnostics` counters use unsynchronized read-modify-write on SharedPreferences — guard with lock (B39) **[RESOLVED - counter updates and snapshot reads are synchronized on a shared lock]**
- `DebugIssueDetector` OCR-quality heuristic counts literal `?` — count only replacement characters (B39)
- `DebugData.toJson()` hand-builds JSON, only escapes subset of fields — use real serializer (B39)
- `DashboardFollowThroughEngine` recommendation filters still do not consistently preserve source transaction type — high-amount recommendations now use the source type, but category recommendations still hardcode `PURCHASE` and merchant recommendations still omit transaction type (B39) **[PARTIALLY_RESOLVED]**
- Database backup import restart semantics are still tunneled through `DatabaseImportSummary.transactionCount == -1` in `DatabaseBackupRepository.importDatabase()`, even though `DatabaseImportResult.SuccessNeedsRestart` now exists at the UI/result layer (B39) **[PARTIALLY_RESOLVED]**
- `AccountingExporters` SimpleDateFormat as singleton instance state — use `java.time` or instantiate per call (B39) **[RESOLVED - exporters now use immutable `java.time.format.DateTimeFormatter`]**
- `AccountingExporters` emit raw `Double.toString()` for money — centralize money formatting (B39) **[RESOLVED - money output now goes through `CurrencyFormatter.formatForExport(...)`]**
- `Generic CSV export` header omits currency column — add Currency column (B39-missed) **[RESOLVED - generic CSV header and rows now include `Currency`]**
- CSV formula-injection hardening is incomplete — generic/export-accounting CSV paths now prefix dangerous `=`, `+`, `-`, `@` starters, but `BusinessExpenseReportGenerator.escapeCSV()` still writes them raw (B37) **[PARTIALLY_RESOLVED]**
- `Mileage summary` exposes first trip's rate as if uniform — show weighted rate (B37) **[RESOLVED - mileage summary now shows a weighted average when multiple deduction rates are present]**
- `SmsParser.detectSmsDirection()` returns `INCOMING` on tie for transfers — return `null` for ambiguous transfers (B44) **[RESOLVED - tie/no-evidence cases now return `null`]**
- `RevolutParser` amount regex only accepts single decimal separator — broaden regex (B44-missed) **[RESOLVED - parser now uses shared grouped-amount token and delegates normalization to `AmountUtils.parseAmount()`]**
- `SmsParser` amount regex same limitation — capture full token, delegate to `AmountUtils.parseAmount()` (B44-missed) **[RESOLVED - parser now uses shared grouped-amount token and delegates normalization to `AmountUtils.parseAmount()`]**
- `ImageCache` keyed only by URI hashCode — include dimensions in key (B44) **[RESOLVED - cache key now includes URI plus requested dimensions before hashing]**
### SubBatch D.5
- `Disk cache` now evicts by size, but still lacks age-based pruning for stale entries (B44) **[PARTIALLY_RESOLVED - `ImageCache` now enforces a 50MB size cap via `evictIfNeededLocked()`, but no age-based pruning exists]**
- `BankStatementParser` header/date-column detection computed but never used — apply or remove (B44) **[RESOLVED - detected date-column order now flows into `extractTransactionFromRow(...)` and is used when selecting the transaction date]**
- `EnhancedMerchantExtractor.isPrice()` only filters lines with currency token — reject total/amount lines without currency (B44)
- `EnhancedMerchantExtractor` drops known merchant when OCR yields no candidates — fall back to existingMerchant (B44)
- `OcrPreprocessingPipeline` median-filter allocates new list per pixel — use reusable buffer (B44)
- `CustomSplitParser` validates with raw Double sums — validate in cents/basis points (B43) **[RESOLVED - totals and split values are now converted to integer minor units / basis points before sum validation]**
- `CUSTOM_AMOUNT/UNEQUAL` splits accept arbitrary decimal precision — reject >2 decimal places (B43) **[RESOLVED - amount splits now reject fractional-cent values via exact minor-unit validation]**
- `RecurringExpenseEngine` groups with `lowercase().trim()` instead of canonical key — group by `merchantKey` (B43) **[RESOLVED - recurring grouping now prefers stored `merchantKey` and otherwise falls back to canonical merchant-key generation]**
- `SynthesisEngine.pastSumDaily.lastOrNull()` without `isFinite()` guard — reject non-finite inputs (B43) **[RESOLVED - past spending series is sanitized before tail lookup and forecast emission]**
- `GenericTransactionParser` date extraction uses lenient Calendar — use strict java.time (B43) **[RESOLVED - notification date parsing now uses strict `java.time` / `LocalDate` parsing with `ResolverStyle.STRICT`]**
- `GreekBankParser` direction detection doesn't recognize Latin codes — extend detection (B43)
- `BillReminderManager` `SEMI_ANNUALLY` not handled — add explicit handling (B43-missed) **[RESOLVED - reminder date advancement and monthly-cost conversion now delegate to `RecurrenceCalculator`, which explicitly handles `SEMI_ANNUALLY` and `ANNUALLY`]**
- `ComputeDashboardWidgetsUseCase` keeps only `overallBudget` as resolved limit — resolve as `overall-or-category-sum` (B43-missed)
- `CalculateBudgetStatusUseCase.getBudgetHealth()` ignores `CRITICAL` — count explicitly (B48)
- `ComputeDashboardWidgetsUseCase` budget summary says "all on track" when nothing EXCEEDED — treat non-ON_TRACK as non-healthy (B48)
- `ReviewExpenseUseCase` returns Success when categoryId is null — require non-null category (B48)
- `ProcessReceiptUseCase` coerces missing merchant/total to "Unknown"/0.0 with no review signal — return incomplete result (B48)
- `LifestyleSavingsPromptUseCase` maxCap becomes 0 but coerceAtLeast(1.0) forces 1% uplift — handle zero rates explicitly (B48)
- `MonthlySavingsSweepUseCase` allocationPercentage keeps pre-cap urgency share — recalculate from finalized amounts (B48) **[RESOLVED - allocationPercentage is now derived from finalized allocated amounts, not the pre-cap urgency share]**
- `ComputeMoneyRadarUseCase` depends directly on `AnomalyAlertDao` — introduce repository interface (B48) **[RESOLVED - use case now depends on domain `AnomalyAlertRepository` port with data-layer adapter implementation and DI binding]**
- `MonthlySavingsSweepUseCase` redefines `effectiveAmount` locally — use canonical property (B48)
### SubBatch D.6
- `ComputeMoneyRadarUseCase` independent fetches run sequentially — use `async`/`await` (B48) **[RESOLVED - `compute()` now fetches due bills, anomaly alerts, and budget risk concurrently via `async`/`await`]**
- `DetectDuplicateExpenseUseCase` userCorrectionRepository injected but unused — remove or integrate (B48)
- `GetMonteCarloBudgetImpactUseCase` messages say "exceed by €0.00" — choose messages from riskTier + expectedOverrun (B48-missed) **[RESOLVED - domain use case now returns only raw Monte Carlo impact data and no longer builds display strings; UI mapper now selects risk-tier wording and avoids zero-overrun overrun phrasing]**
- `BlockPartyDay` imports Room `Expense` entity — replace with domain DTO (B46) **[RESOLVED - block-party previews now use domain `TransactionSummary` instead of Room `Expense`]**
- `FinancialForecast.actionableInsights` is `List<String>` while feature family uses `UiText` — use `List<UiText>` (B46) **[RESOLVED - `FinancialForecast.actionableInsights` now uses `List<UiText>`]**
- `ForecastHorizon.REST_OF_MONTH` uses `days = 0` as sentinel — model calendar-bound case explicitly (B46) **[RESOLVED BY D3-TIME-DETERMINISM - `ForecastHorizon` now uses explicit calendar-bound metadata (no sentinel `days = 0` semantics)]**
- `PeriodRange` accepts `end < start` — add `require(end >= start)` (B46) **[RESOLVED - `PeriodRange` now enforces `end >= start` in its init block]**
- `PlannedExpense.amount` has no non-negative invariant — enforce `amount >= 0` (B46) **[RESOLVED - `PlannedExpense` now requires a positive finite `amount`]**
- `RecurrenceFrequency` mixes approximate fixed-day values for calendar frequencies — remove `intervalInMs` for calendar-based (B46) **[RESOLVED BY D3-TIME-DETERMINISM - production recurrence advancement now uses explicit fixed-interval vs calendar-bound helpers via `RecurrenceCalculator`; legacy sentinel accessors deprecated]**
- `UpcomingItem.Recurring.id` uses only `merchantName` — use `pattern.id` or composite key (B46)
- `MonteCarloBudgetImpact` stores preformatted UI strings, hardcodes EUR — keep raw values only (B46) **[RESOLVED - model now contains only raw risk inputs/outputs (`budgetAmount`, `p50Forecast`, `expectedOverrun`, `probabilityOfOverrun`, `riskTier`); UI formatting moved to presentation mapper]**
- `DashboardExpenseMapper` imports Room `Expense`/`TransactionType` — move mapper to data layer (B47) **[RESOLVED - mapper no longer imports Room types and now maps `DashboardExpense` to domain `TransactionSummary`]**
- `DomainTransactionFilter` depends on `TransactionType` and `OwnershipFilter` from data layer — define domain-level enums (B47) **[RESOLVED - `DomainTransactionFilter` now depends on domain-owned transaction and ownership enums]**
- `DomainTransactionFilter.correlationId` defaults to `System.currentTimeMillis()` — move outside value object (B47) **[RESOLVED - `correlationId` now defaults to `0L` instead of a wall-clock timestamp]**
- `SpendingSummary` mixes `Double` totals with `Float` histories — use `Double` consistently (B47) **[RESOLVED - `SpendingSummary` now uses `Double` consistently for totals and history series]**
### SubBatch D.7
- `WidgetStyleConfig` accepts any string key but persistence only restores allowlisted set — validate at boundary (B47)
- `DashboardCategoryBreakdown.changeFromLastPeriod` hardcoded to `0.0` — calculate or remove (B47) **[RESOLVED - advanced analytics dashboard now computes `changeFromLastPeriod` via `calculateChangeFromLastPeriod(currentAmount, previousAmount)` using comparison-period category totals]**
- `BudgetStatusSnapshot` `percentUsed` is `Float` while amounts are `Double` — store as `Double` (B47) **[RESOLVED - `BudgetStatusSnapshot.percentUsed` is now stored as `Double`, matching the rest of the amount fields]**
- `ComputeDashboardWidgetsUseCase.DomainExpenseSummary.categoryName` populated with `categoryId?.toString()` — pass real name or rename field (B47-missed) **[RESOLVED - `DomainExpenseSummary.categoryName` is now resolved from the preloaded category map instead of stringifying `categoryId`]**
- `DashboardWidgetUiMapper` converts transaction summaries into synthetic `Expense` entities with hardcoded `PURCHASE` — map to dedicated UI summary model (B47-missed) **[RESOLVED - the mapper now converts `DomainExpenseSummary` into lightweight `TransactionSummary` DTOs instead of fabricating synthetic `Expense` entities with a hardcoded transaction type]**
- `CategoryRepository.learnMerchantCategory()` inserts without `normalizedCanonicalName` and without cache invalidation — route through engine path (B38) **[RESOLVED - repository learning now delegates to `CategorizationEngine.learnMerchantCategory()`, preserving centralized mapping creation and cache invalidation]**
- `CategoryKeywords` `"roasters"` declared twice — deduplicate (B38-missed) **[RESOLVED - `CategoryKeywords` now contains only one `"roasters"` entry]**
- `SemanticKeywordMatcher` wraps keywords in `\b...\b` — handle punctuation-at-edge tokens (B38-missed) **[RESOLVED - keyword matching now uses Unicode-aware boundary lookarounds instead of blanket `\b...\b` wrappers]**
- `AppleReceiptParser.detectCurrency()` uses raw substring checks — match bounded tokens (B31-missed) **[RESOLVED - currency detection now uses bounded-token matching to avoid incidental substring hits]**
- `UberReceiptParser` same currency detection issue (B31-missed) **[RESOLVED - currency detection now uses bounded-token matching to avoid incidental substring hits]**
- `UberReceiptParser.parseUberDate()` fills in current year for year-less dates — derive from email `receivedAt` year (B31-missed) **[RESOLVED BY D3-TIME-DETERMINISM - year-less parsing now anchors to `receivedAt` with near-year-boundary future-date clamp]**
- `WarrantyTextExtractor` "date at start of line" regex not compiled with `MULTILINE` — add flag (B45-missed) **[RESOLVED - start-of-line date pattern now uses `Pattern.MULTILINE`]**
- `Expense.splitTemplateId` has no FK — add nullable FK (B12-missed) **[RESOLVED BY B.4 — Batch 8: nullable FK to `split_templates(id)` with `ON DELETE SET NULL` added]**
- `PendingReview.suggestedType` stored as raw `String` — validate against allowed names (B12-missed) **[RESOLVED BY B.4 — Batch 8: enum-backed validation enforced on persistence; invalid strings rejected]**
### SubBatch D.8
- `ClipboardAmountParser` regex grabs partial match on thousands-formatted values — anchor whole-token matching (B23-missed) **[RESOLVED - parser now enforces whole-token extraction and preserves grouped values like `1,234.56` without partial-tail matches]**
- `CsvExpenseImporter` emits 8-digit ARGB colors but Category entity only accepts 6-digit `#RRGGBB` — emit 6-digit hex (B23-missed) **[RESOLVED - importer category colors are now limited to 6-digit `#RRGGBB` values, matching `Category` validation]**
- `AmountUtils` comma-group validation accepts `1,0000` — require 3-digit chunks (B23) **[RESOLVED - comma-group parsing now requires canonical 3-digit grouping chunks when comma is treated as thousands separator]**
- `CurrencyNormalizer.uppercase(Locale.getDefault())` is locale-sensitive — use `Locale.ROOT` (B23) **[RESOLVED - currency normalization now uppercases with `Locale.ROOT`]**
- `MerchantCleaner` stop-word stripping truncates at first internal `" at"` — strip only anchored positions (B23)
- `Money.format()` depends on device locale — use fixed locale or `BigDecimal.toPlainString()` (B23)
- `DateFormatterUtils` ThreadLocal cache never evicts — remove or bound (B23) **[RESOLVED - `DateFormatterUtils` no longer uses a `ThreadLocal` formatter cache and now keeps a bounded 16-entry LRU cache]**
- `DateFormatterUtils` cached formatters capture locale at creation — cache by `(pattern, locale)` (B23) **[RESOLVED - formatter cache keys now include both pattern and locale]**
- `HapticFeedback` uses `CONFIRM`/`REJECT` without pre-30 fallback — gate on `SDK_INT` (B23)
- `StringDistanceUtils.isFuzzyMatch()` recompiles regexes every call — hoist to constants (B23) **[RESOLVED - emoji/noise stripping regexes are now object-level constants reused across calls]**
- `EmailReceiptSource.fingerprint` is primary dedupe lookup but schema only adds non-unique index — make unique (B13-missed)
- `GroupTransactionCoordinator.deleteGroup()` always returns `true` — return affected-row count (B11-missed)
- `InvestmentTracker.getValuesBetween()` returns ascending, `getInvestmentPerformance()` reads `firstOrNull()` for day change — use `lastOrNull()` (B27-missed) **[RESOLVED BY B.4 — Batch 10 / late closeout (ISSUE-B4-11): `recentValues.lastOrNull()` on ASC-ordered window confirmed correct]**
- `FinancialHealthScoreV2.saveToHistory()` read-then-insert without uniqueness guarantee — add unique constraint, use UPSERT (B41)
- `RecurringIncomeTracker` now groups deposits by canonical merchant key, but still keeps blank merchants instead of skipping them (B41) **[RESOLVED BY D3-TIME-DETERMINISM - blank/empty canonical merchant keys are now filtered out before recurring-income grouping]**
### SubBatch D.9
- `ConfidenceRouter` ensureSourceStats timestamps with `System.currentTimeMillis()` — use `timeProvider.now()` (B41) **[RESOLVED BY D3-TIME-DETERMINISM - `SourceStats.lastSeen` wall-clock default removed; `ensureSourceStats()` and notification source-stats paths now pass explicit `timeProvider.now()` timestamps]**
- `CrossSourceDeduplication.isCrossSourceDuplicate()` doesn't compare real transaction data — redesign API (B41)
- `TransactionClassifier` save/load failures log only message, not exception — use `Timber.e(e, ...)` (B41)
- `FeatureExtractor.extractFromNotification()` uses wall clock — accept explicit timestamp (B41) **[RESOLVED - the extractor now accepts an explicit `eventTimeMillis` parameter and no longer reads `System.currentTimeMillis()` internally]**
- `MerchantNormalizer` alias persistence stores original `rawName` bypassing length guard — persist sanitized name (B41)
- `MerchantNormalizer` logs raw merchant names — hash/anonymize (B42)
- `HybridExpenseClassifier.initialized` read outside mutex, not `@Volatile` — make `@Volatile` or move inside mutex (B42)
- `AreaSpendingEngine` grid cells keep first parsed area name — track frequencies, keep most common (B42) **[RESOLVED - grid cells now accumulate area-name frequencies/total spend and choose the most representative candidate via `selectRepresentativeAreaName()`]**
- `TravelDetectionEngine` destination hints use `split(",").getOrNull(1)` — fall back to first component (B42) **[RESOLVED - destination parsing now returns the second address component when present and falls back to the first component for single-part addresses]**
- `SavingsGamificationEngine.goal_crusher` uses `goals.firstOrNull()` — use max normalized progress (B03) **[RESOLVED - goal-crusher progress now uses the goal with the highest normalized completion ratio via `maxByOrNull { currentAmount / targetAmount.coerceAtLeast(0.01) }`]**
- `SavingsGamificationEngine.unlockedAt` recomputed on each call — persist first-unlock timestamps (B03) **[PARTIALLY_RESOLVED - unlock times are now derived from persisted goal/contribution timestamps instead of `now`, but achievement unlock state is still recomputed on each call rather than stored as dedicated first-unlock metadata]**
- `goal.currentAmount / goal.targetAmount` unguarded for zero target — guard division (B03) **[RESOLVED - goal-progress calculations now guard zero/invalid targets with `targetAmount.coerceAtLeast(0.01)`]**
- `FinancialHealthCalculator.calculateBudgetHealthScore()` accepts `periodExpenses` but never uses it — remove parameter or make period-aware (B03) **[RESOLVED - `calculateBudgetHealthScore()` no longer accepts an unused `periodExpenses` parameter]**
- `FinancialHealthCalculator.calculateTodayScore()` increments `noSpendStreak` locally — trust supplied streak (B03) **[RESOLVED - today-score bonus logic now uses the caller-supplied `noSpendStreak` and only applies it when `spentToday == 0.0`]**
- `FinancialHealthCalculator` week calculations use locale-dependent `Calendar.firstDayOfWeek` — reuse `TimePeriodUtils` (B03) **[RESOLVED - weekly ranges now come from locale-independent `TimePeriodUtils.getWeekRange(...)`]**
- `FinancialHealthScoreV2` trend compares against `getMostRecent()` without excluding current period — compare against latest different period (B03) **[RESOLVED - trend lookup now uses `healthScoreHistoryDao.getMostRecentBefore(periodStart, periodEnd)` to exclude the current period]**
- `FinancialHealthCalculator.budgetStatuses.all { }` vacuously true for empty list — require `isNotEmpty()` (B03) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 5: empty budget-status sets no longer qualify as all-on-track]**
### SubBatch D.10
- `FinancialHealthCalculator` legacy score capped at 70, `EXCELLENT (85-100)` unreachable — rebalance weights (B41) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 5: scoring weights/ranges rebalanced so EXCELLENT is reachable under top-performing inputs]**
- `FinancialHealthScoreV2` on exception returns synthetic score of 50 — add explicit fallback flag (B41) **[RESOLVED - top-level `calculateHealthScore()` now rethrows non-cancellation exceptions after logging instead of returning a synthetic score]**
- `RecurringIncomeTracker` confidence compares ms-squared variance against tiny threshold — normalize to days (B41) **[RESOLVED BY D3-TIME-DETERMINISM - interval variance/confidence now uses day-scale statistics instead of millisecond-squared thresholds]**
- `RecurringIncomeTracker.getStartOfMonth()` leaves milliseconds untouched — set `MILLISECOND = 0` (B41) **[RESOLVED - `getStartOfMonth()` now explicitly clears `Calendar.MILLISECOND`]**
- `SpendingChallengeManager.durationDays * 24 * 60 * 60 * 1000L` overflow — cast to `Long` first (B38) **[RESOLVED - challenge date math now uses `durationDays.toLong() * DAY_MS` for both challenge end dates and reduce-spending baselines]**
- `SpendingChallengeManager.daysRemaining` can go negative — clamp with `coerceAtLeast(0)` (B38) **[RESOLVED - `daysRemaining` is now clamped with `.coerceAtLeast(0)` in `getChallengeProgress()`]**
- `SpendingChallengeManager` IDs use `System.currentTimeMillis()` — use UUID (B38) **[RESOLVED - challenge creation now persists `id = 0` and relies on Room auto-generated IDs instead of timestamp-based IDs]**
- `CategorizationEngine` reloads cache fragments via three accessors per call — fetch one snapshot (B38) **[PARTIALLY_RESOLVED - cache refresh is now centralized in `getCacheData()`, but `categorize()` / `debugCategorize()` still call separate wrapper accessors instead of reusing one local snapshot]**
- `CategorizationEngine` fuzzy matcher prefilters by first two chars — loosen prefix heuristic (B38)
- `CategorizationEngine.getCategoryIdByName()` reads outside snapshot — use snapshot's name-to-id map (B38)
- `WarrantyTrackerScreen` expired warranties never show expired badge — render when `isExpired || isExpiringSoon` (B18) **[RESOLVED - expired badge now renders when isExpired || isExpiringSoon]**
- `ReviewScreen` `showTrustSignal` never toggled — add expand/collapse or remove (B18-missed) **[RESOLVED - trust-signal section now has explicit expand/collapse affordance; dead state path removed]**
- `WarrantyTrackerViewModel` auto-detected filter chip can't be toggled off — make chip toggle its own boolean (B18) **[RESOLVED - auto-detected filter chip is now a true toggle; mutually exclusive with status/needs-review filters]**
- `CurrencyManagementScreen` conversion dialog leaves Convert enabled for invalid amounts — disable or show validation error (B18) **[RESOLVED - Convert button disabled for invalid/zero/negative amounts and same-currency pairs; inline validation error shown after user interaction]**
- `SubscriptionManagementViewModel` no-spend status loaded once in `init` — observe reactively (B19) **[RESOLVED - the current subscription management viewmodel/screen path no longer computes or renders a no-spend status, so the stale init-loaded state is gone]**
- `CarbonFootprintScreen` negative `parisAgreementGap` passed to formatter — use `abs(gap)` (B19) **[RESOLVED - below-target gap now displays with abs(gap) semantics]**
### SubBatch D.11
- `CarbonFootprintViewModel` collapses exceptions into `report = null` — add explicit error state (B19-missed) **[RESOLVED - additive error: StateFlow<String?> preserves last successful report on failure; generic LOAD_ERROR_MESSAGE for UI]**
- `Loading states` ignore scaffold padding in multiple screens — apply padding (B19) **[RESOLVED - CarbonFootprintScreen and LifestyleInflationScreen loading/error states now render inside scaffold-padded containers]**
- `Hardcoded English copy` in multiple screens — extract to string resources (B19) **[PARTIALLY_RESOLVED - targeted hardcoded copy in `SpendingChallengesScreen.kt`, `ReceiptScanScreen.kt`, `LifestyleInflationScreen.kt`, and `AssistantSheet.kt` is now resource-backed; broader app-wide cleanup outside this targeted set remains open]**
- Domain analytics layer imports Room `Expense` / `data.database.entity` types directly (D3 domain boundary) **[RESOLVED - targeted analytics engines now consume domain `ExpenseSnapshot`/`BudgetSnapshot` models, with entity→domain mapping moved to repository/adapter boundaries]**
- `LifestyleInflationScreen` / `SavingsGoalsScreen` display enum `.name` values directly in chips (localization leakage) **[RESOLVED - enum labels are now mapped through localized string resources via `when` mappings]**
- `NoSpendStreakCard` hardcodes `Locale.GERMANY` — use `Locale.getDefault()` (B19) **[RESOLVED - `NoSpendStreakCard` no longer hardcodes `Locale.GERMANY`; savings text now formats through `CurrencyFormatter`, which uses the default locale]**
- `Starter prompt chips` display localized labels but inject hardcoded English queries — back with localized query strings (B19-missed) **[RESOLVED - starter chips now submit resource-backed localized query payloads (`assistant_query_*`) instead of inline English literals]**
- `Active challenges` branch renders placeholder text — replace with real challenge card (B19-missed) **[RESOLVED - non-empty active-challenges state now renders `ActiveChallengeCard` rows with challenge name, type, progress, and target details]**
- `balance == 0.0` exact float equality — compare with tolerance (B18) **[RESOLVED - SharedExpenseGroupsScreen now uses HALF_UP rounding and strict settlement threshold instead of exact float equality]**
- `AddExpenseViewModel.reset()` doesn't cancel debounced search job — cancel in reset (B17) **[RESOLVED - reset() now cancels searchJob; stale suggestions cannot repopulate after sheet dismissal]**
- `AddExpenseSheet` prefill keyed with `LaunchedEffect(Unit)` — key to `initialAmount`/`initialMerchant` (B17) **[RESOLVED - prefill keyed to incomingPrefill tuple; BackHandler ensures reset() on system back dismiss, preventing stale initialValuesApplied]**
- `ReceiptScanScreen` uses `collectAsState()` — use `collectAsStateWithLifecycle()` (B19) **[RESOLVED - ReceiptScanScreen now uses collectAsStateWithLifecycle() for state and categories]**
- `Camera permission denial copy` hardcoded English — move to strings.xml (B19) **[RESOLVED - camera-denial title/message and open-settings CTA are now string-resource backed in `ReceiptScanScreen`]**
- `Retry button` hardcoded "Retry" string — extract to resource (B19) **[RESOLVED - retry action text in `ReceiptScanScreen` now uses shared resource (`action_retry`)]**
- `Currency.getInstance(currencyCode)` unguarded — wrap in `runCatching` (B19) **[RESOLVED - guarded currency resolution is now used in `VisualSplitEditorScreen.buildCurrencyFormat()`, with a safe fallback when the code is invalid]**
### SubBatch D.12
- `Percentage/amount fields` coerce input through `Double.toString()` — store editable text separately (B19) **[RESOLVED - transient text state via SplitTextFieldState preserves partial user input; committed-value tracking prevents LaunchedEffect from clobbering in-progress edits; stable rowIds prevent state migration after participant removal; non-finite (NaN/Infinity) inputs are rejected]**
- `LifestyleInflationViewModel` exceptions swallowed into `report = null` — add explicit error state (B19) **[RESOLVED - mirrors CarbonFootprint pattern: additive error StateFlow, preserves last successful report on failure, generic LOAD_ERROR_MESSAGE]**
- `SavingsPromptCard` hardcoded English copy — move to resources (B19) **[RESOLVED - title/body/action in `SavingsPromptCard` now use `lifestyle_savings_prompt_*` string resources]**
- `ReviewPriorityFactors.fromReview()` uses `System.currentTimeMillis()` — pass `now` (B34) **[RESOLVED - `fromReview()` now requires `nowMs`, and reviewed production callers pass an injected clock value instead of reading wall time internally]**
- `ReviewPriorityFactors.calculateTimeSensitivity` reads `System.currentTimeMillis()` — inject clock (B24) **[RESOLVED - `calculateTimeSensitivity(createdAt, nowMs)` now takes the evaluation time as a parameter instead of reading wall time internally]**
- `CaptureAssistInput.amount` accepts `NaN`/`Infinity`/zero/negative — require finite positive (B34) **[RESOLVED - `CategorizationAssistInput` now enforces finite positive amount in init invariants]**
- `ReviewExplanationInputBuilder` imports `data.ai.provider.internal.sha256Prefix` — move hashing to domain/common (B36)
- `DashboardBriefingInputBuilder.SimpleDateFormat` shared mutable state — use `DateTimeFormatter` (B35) **[RESOLVED - the builder now uses an immutable `DateTimeFormatter` field and no longer holds shared `SimpleDateFormat` state]**
- `RecurrenceFrequency.IRREGULAR.intervalInMs` returns `0L` — make nullable or model separately (B24) **[RESOLVED BY D3-TIME-DETERMINISM - irregular/calendar recurrence semantics are now explicit; sentinel `0L` interval semantics removed from production logic]**
- `MonteCarloBudgetImpact.formatCurrency` hardcodes euro symbol — use `NumberFormat` with explicit `Currency` (B24) **[RESOLVED - currency-format helper was removed from the domain model entirely; Monte Carlo domain contract is now raw-only and presentation formatting is handled in UI mapper code]**
- `CategoryBreakdown`/`DashboardCategoryBreakdown` duplicated across packages — consolidate (B24) **[PARTIALLY_RESOLVED - analytics-local `CategoryBreakdown` duplicate removed; domain-vs-dashboard semantic duplication still tracked]**
- `PeriodRange` duplicated across `domain.model` and `domain.analytics` — rename one or add conversion layer (B46)
- `SavingsGoal` domain and entity definitions differ — keep Room entities internal to data layer (B46) **[PARTIALLY_RESOLVED - domain usage now routes through `domain.savings.SavingsGoalRepository` in targeted adapters; legacy entity-first helper APIs remain deprecated but present in data repository for compatibility]**
- `NarrativeGenerator` imports app `R`, constructs formatted strings in domain — emit `UiText`/message keys (B46-missed) **[RESOLVED - `NarrativeGenerator` now emits domain text keys with typed args (`UiTextArg.Money`) and no longer imports app `R` or performs currency string formatting in domain]**
### SubBatch D.13
- `FinancialForecast.generatedAt` is now sourced from `timeProvider.now()` at forecast creation sites (`SynthesisEngine`) instead of `Instant.now()` (B46-missed) **[RESOLVED]**
- `CalculateFinancialForecastUseCase` now builds `SpendingPace` from real owned-purchase history, using `SpendingPaceProjection.calculateProjectedTotal(...)` and dynamic `paceStatus` instead of fabricated `projectedTotal = monthSpent` / fixed `ON_PACE` (B48) **[RESOLVED]**
- `CalculateFinancialForecastUseCase` now builds cumulative `pastSumDaily` from current-month owned purchases via `buildPastSumDaily(...)` instead of passing `emptyList()` (B48) **[RESOLVED]**
- `DashboardContractsAdapter.observeDashboardExpenses()` now re-derives the month window from `timeBoundaryTicker.dayBoundaryTicks()` and `TimePeriodUtils.getMonthRange(now)` instead of snapshotting the month once (B48) **[RESOLVED]**
- `DashboardDataProvider` flows silently replace failures with empty/default — log or surface error state (B48)
- `GroupsModule` unused imports — remove (B22)
- `EmailIngestionModule` provides parser singletons but `EmailReceiptIngestionService` manually constructs them — inject or remove bindings (B22-missed)
- `ExportOptionsViewModel` now receives `XeroCSVExporter`, `QuickBooksIIFExporter`, and `FreshBooksExporter` via Hilt; direct construction path is gone (B22-missed) **[RESOLVED]**
- `LifecycleObserver.onStop()` now calls `TransactionClassifier.onBackground()` instead of canceling the singleton scope; routine backgrounding no longer destroys classifier work scheduling (B22) **[RESOLVED]**
- `BudgetMonitor` is no longer destroyed on every `onStop()`; app lifecycle now calls non-destructive `onBackground()` and leaves the monitor scope reusable (B22) **[RESOLVED]**
- `SavingsModule` is only partially migrated off `data.repository.SavingsGoalRepository`: `SavingsGamificationEngine` now uses the domain `SavingsGoalRepository`, but `SmartSavingsEngine` and `AutomatedSavingsRuleEngine` still depend on the data repository type (B22) **[RESOLVED - targeted engines and dependent adapters now consume the domain `SavingsGoalRepository` contract]**
- `AiSettingsRepositoryImpl.settings()` now recovers from `IOException` via `catch { emit(emptyPreferences()) }` (B06, B34) **[RESOLVED]**
- `DefaultAiCapabilityRouter` is only partially fixed: some unavailable-route messages use `displayName()`, but the disabled-capability fast path still returns raw enum text (`"$capability is disabled in settings."`) (B06) **[PARTIALLY_RESOLVED]**
- `GetAiRuntimeStatusUseCase.highestPriorityMessage` is first-match not severity-ranked — rank by severity or rename (B06)
- `OnDeviceDedupeJudgeService` raw `Enum.valueOf()` calls — use case-insensitive safe lookup (B09) **[RESOLVED - parser now uses safe enum lookup and strict numeric parsing for IDs/confidence]**
- `HybridReceiptAssistService` no longer keeps `lastUsedImageInput` shared mutable state; image-usage reporting is per-request via `ReceiptAssistSuggestion.usedImageInput` (B10) **[RESOLVED]**
- `CloudPiiSanitizer.PHONE_REGEX` broad enough to redact non-phone numeric text — tighten matching (B10)
- `CloudJsonParser.extractFirstJsonObject()` now validates each brace-balanced candidate by parsing it as `JSONObject` before returning it, instead of returning the first balanced block blindly (B10) **[RESOLVED]**
- `CloudCorrelation` keeps only 8 chars of UUID — use full UUID or longer token (B10)
### SubBatch D.14
- `ExpenseGroupDao.insertGroupWithMembers()` unused in production — remove or move to coordinator (B15)
- `ExpenseGroupDao` `groupId <= 0` guard unreachable — remove (B15)
- `ExpenseGroupDao` `memberIds.any { it <= 0 }` guard unreachable — remove (B15)
- `ManualRecurringExpenseDao.insert()` uses `REPLACE` — use `ABORT` for create-only (B15) **[RESOLVED - DAO now uses `@Insert(onConflict = OnConflictStrategy.ABORT)`]**
- `MerchantNormalizationDao.linkAliasToCanonical()` read-then-insert — use atomic upsert (B15)
- `ExpenseDao.getChanges()` exposes SQLite `changes()` as standalone helper — remove or wrap (B14)
- `ScannedReceiptDao.linkToExpense()` updates only `expenseId` — atomically set matched status (B14) **[RESOLVED BY B.4 — Batch 9: link atomically sets matched status and clears suggestion metadata]**
- `ReturnWindowDao` single-row contract is only partially enforced: `expenseId` is now unique, but `receiptId` is still non-unique while `getReturnWindowByReceiptId()` returns a single row — add DB-level uniqueness on `receiptId` or return a list for receipt-based lookups (B14) **[PARTIALLY_RESOLVED - `expenseId` uniqueness is enforced; `receiptId` uniqueness is not]**
- `SpendingThresholdCalculator` percentile query uses raw `amount` not `effectiveAmount` — shared expenses inflate threshold, anomaly detection less sensitive (B36-missed) **[RESOLVED - `ExpenseDao.getAmountsForPercentileCalc()` now uses canonical `EFFECTIVE_AMOUNT_SQL` for PURCHASE-only percentile inputs]**
- `AnomalyAlertOrchestrator` broad `catch` swallows `CancellationException` — re-throw before generic catch (B36-missed) **[RESOLVED - inner/outer catch blocks now rethrow `CancellationException` before generic handling]**
- `AdvancedAnalyticsDashboard.getMonthlyTrend()` builds `23:59:59` month end passed to end-exclusive repo query — use start-of-next-month exclusive boundary (B36-missed) **[RESOLVED - monthly buckets now use calendar-month starts with next-month exclusive end boundaries]**
- `AdvancedAnalyticsDashboard` monthly trend N+1 pattern — batch into single date-range query or aggregate SQL (B36-missed) **[RESOLVED - `getMonthlyTrend()` now loads the range once and groups monthly buckets in memory]**
- `AdvancedAnalyticsEngine` current-period sparklines stop before today — extend end boundary to include current day (B36-missed) **[RESOLVED BY D3-TIME-DETERMINISM - sparkline day indexing now uses day-safe calculations and includes today for current periods]**
- `SpendingPersonalityClassifier` confidence mixes normalized 0..1 features with raw `transactionsPerMonth` — normalize all inputs (B36-missed) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 5: confidence features are now fully normalized before aggregation]**
### SubBatch D.15
- `DayOfWeekAnalyzer` results sorted by total spend instead of weekday order — sort by day-of-week index (B36-missed) **[RESOLVED BY D3-TIME-DETERMINISM - analyzer output is now chronological Monday→Sunday]**
- `TransferDirectionAnalytics` corrections only update accuracy counters, not incoming/outgoing totals or source/destination lists — rebuild full stats on correction (B36-missed) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 5: correction flow now updates full directional aggregates and rankings]**
- `ExpenseDao` weekly aggregates expose `MIN(date)/MAX(date)` transaction timestamps as week boundaries — use explicit period start/end from calendar (B36-missed) **[RESOLVED BY D3-TIME-DETERMINISM - weekly totals now expose canonical `[start,end)` Monday-week ranges via repository normalization from week keys]**
- `CrossSourceDeduplication` candidate ranking ignores time distance and merchant similarity when multiple candidates pass hard filters — weight by time delta and merchant score (B40-missed) **[RESOLVED - duplicate selection now builds `ScoredCandidate`s and routes tie-breaks through `DuplicateDetectionPolicy.bestCandidate()`, which ranks by time delta, amount delta, and merchant confidence]**
- `BudgetAutopilotEngine` history fetched through `ExpenseRepository.getExpensesBetween()` — inherits 2000-row cap; add uncapped variant (B37-missed) **[RESOLVED - historical spend now comes from `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween()` / `getMonthlySpendingTotalsBetween()` aggregate SQL instead of capped raw row reads]**
- `BudgetForecastingEngine` historical reads call `getExpensesByCategory()`/`getExpensesByTypeBetween()` without overriding default limit — add uncapped variant (B37-missed) **[RESOLVED - historical spend now uses `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween()` / `getMonthlySpendingTotalsBetween()` aggregate SQL instead of capped raw history queries]**
- `BudgetAutopilotEngine` and `BudgetForecastingEngine` use different month bucketing and timezone rules — centralize through shared period calculator (B37-missed) **[RESOLVED BY D3-TIME-DETERMINISM - both engines now share `TimePeriodUtils` month-key parse/format/range helpers and aligned month-bucket contract]**
- `InsightsEngine` composes analytics engines but reimplements their logic inline — delegate to engines to prevent drift (B36-missed)
- `SplitCalculator` converts money to cents with `Int` — amounts above ~€21.47M overflow; use `Long` (B43-missed) **[RESOLVED - cent conversion and split math now use `Long` (`toCents(): Long`, `baseCents`, `centsByMember`), removing `Int` overflow for high-value amounts]**
- `ReviewPriorityScorer` batch scoring pre-computes `duplicateRisk` but `calculateBaseScore()` uses placeholder `0.5f` — feed computed risk into single-item scoring path (B34-missed) **[PARTIALLY_RESOLVED - `scoreSingle()` now delegates to `scoreReviews(listOf(review))`, but `calculateBaseScore()` still relies on `ReviewPriorityFactors.fromReview(...)` with placeholder `duplicateRisk = 0.5f`, so quick/base scoring still diverges]**
- `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` never records impression — record impression before returning so cooldown starts at show time (B33-missed) **[RESOLVED - `evaluateAndPrompt()` now calls `promptStateRepository.recordPrompt(PROMPT_TYPE)` before returning the recommendation, so cooldown starts at show time]**
- `RecommendationDeduplicator.computeSignature()` omits `ownership` — include ownership in signature (B47-missed) **[RESOLVED - `computeSignature()` now includes `filter.ownership` in the serialized filter signature, so ownership-distinct recommendations are no longer deduplicated together]**
- `AdvancedAnalyticsEngine` merchant analytics groups by raw `merchant`, re-filters full 6-month history per merchant — aliases fragment results, O(merchants × history) runtime (B01)
- `AdvancedAnalyticsDashboard` `generateDashboardData()` hardcodes `Dispatchers.IO` (B01)
### SubBatch D.16
- `CategoryInsightEngine` previous-period expenses re-filtered for every category — O(categories × previous-expenses) (B01)
- `MerchantInsightEngine` merchant grouping by `merchant.lowercase()` not canonical `merchantKey` (B01)
- `TransferDirectionAnalytics.recordUserCorrection()` assumes initial detection was correct — double-decrement on incorrect initial detection (B01)
- `AdvancedAnalyticsEngine.getMerchantAnalytics()` loads history with `getExpensesSince(historicalStart)` and never caps at `period.endMs` — post-period transactions leak (B01)
- `BudgetForecastingEngine.generateForecast()` inserts forecast row but returns pre-insert object — caller always gets `id = 0` (B02) **[RESOLVED BY D3-ANALYTICS-FORECASTING - Batch 3: generateForecast now returns persisted forecast with inserted row ID/state]**
- `SynthesisEngine` biweekly matching treats any date within ±2 days as bill day — one bill appears on up to 5 days (B04)
- `MonteCarloSpendingSimulator.countRecentQualifyingWeeks()` uses `total > 0` instead of `>= 3` distinct transaction-days (B04)
- `SynthesisEngine` `now` captured once but `Calendar` seeded with second `timeProvider.now()` call — midnight race (B04) **[RESOLVED BY D3-TIME-DETERMINISM - synthesis now reuses a single captured `now` for calendar seeding and in-method period calculations]**
- `SplitCalculator.formatBalance()` hardcodes `$` — non-USD users see wrong currency (B04)
- `ComputeDashboardWidgetsUseCase` when no overall budget, `SafeToSpend.amount` populated with `ctx.monthSpent` (already-spent money) (B05)
- `ComputeMoneyRadarUseCase.compute()` captures `now` but helpers call `timeProvider.now()` again — midnight mixing (B05) **[REVALIDATED IN D3-TIME-DETERMINISM - STALE/ALREADY_FIXED: current implementation captures one `now` in `compute()` and threads it through helper calls; no additional code change required]**
- `ComputeDashboardWidgetsUseCase` zero `averageDailyBurn` + remaining budget → runway days = 0 → CRITICAL (B05)
- `ComputeDashboardWidgetsUseCase` `monthSpent` from `summary.totalSpent` while `todaySpent`/`weekSpent` from `purchases` — different reactive paths → inconsistent (B05)
### SubBatch D.17
- `GetAiRuntimeStatusUseCase` capability status checks awaited sequentially — latency grows linearly (B07)
- `SuggestReceiptExtractionUseCase` non-forced path no longer enforces deterministic `needsAssist()` gate (B08)
- `MapFinancialQueryToNavigationUseCase` `QueryMetric.MIN` explicitly rejected — unsupported end-to-end (B08)
- `PrioritizeReviewItemsUseCase` has no production call site — review-priority feature is dead code (B08)
- `CloudReceiptAssistService.buildImageInlineData()` reads full file into memory before checking size (B08)
- `ExecuteFinancialQueryUseCase` `QueryMetric.MIN` advertised but never executed — "smallest/cheapest" falls through to `Unsupported` (B08)

### D.4: Low (Quick Wins)
- `ExpenseTrackerApp` creates own `CoroutineScope` instead of using Hilt-provided `@ApplicationScope` — inject (B22) **[RESOLVED - bespoke app scope removed; startup sync now launches from process lifecycle scope]**
- `TransactionClassifier` and `BudgetMonitor` eagerly field-injected into `Application` — use `Lazy`/`Provider` (B22) **[RESOLVED - application now defers both via `Lazy` and only resolves them inside lifecycle callbacks]**
- `NotificationIdGenerator` `% RANGE_SIZE` preserves sign for negatives — use `floorMod` (B23) **[RESOLVED - centralized positive range mapping now uses `Math.floorMod`]**
- `BKTree.size`/`isEmpty` read mutable state outside mutex — guard with mutex (B23) **[RESOLVED - tree now exposes atomic snapshot state for lock-free reads consistent with mutex-protected writes]**
- `Math.abs(hash) % colors.size` can be negative for `Int.MIN_VALUE` — use `floorMod` (B23) **[STILL_OPEN]**

---

## Statistics Summary

- **Total tracked items:** 794 (A epics + B pipeline issues + C dependency entries + D quick wins; after all scout-audit corrections, false-positive removal, and deduplication)
- **By severity (B + D combined):**
  - Critical: 21 (B: 18, D.1: 3)
  - High: 223 (B: 198, D.2: 25)
  - Medium: 416 (B: 155, D.3: 261)
  - Low: 81 (B: 76, D.4: 5)
- **By section:**
  - Section A (Universal): 10 epics (A.1–A.10)
  - Section B (Pipeline): 447 issue bullets across 12 pipelines
  - Section C (Dependencies): 43 entries (C.1: 7 blocking fixes, C.2: 8 sequential steps, C.3: 28 independent groups)
  - Section D (Isolated): 294 quick wins (D.1 CRITICAL: 3, D.2 HIGH: 25, D.3 MEDIUM: 261, D.4 LOW: 5)
- **Batches with most issues:** 36 (27+), 37 (27+), 01 (26), 40 (24+), 02 (24), 48 (24), 45 (24), 18 (23), 34 (15+), 33 (14+)
- **Most affected files:**
  1. `ExpenseDao.kt` — effectiveAmount, business queries, merchant stats, percentile calc, pagination caps, weekly boundary aggregates
  2. `ExpenseRepository.kt` — hidden truncation, effectiveAmount, dedupe queries
  3. `BudgetRepository.kt` — raw spend, stale reactive window, hidden truncation
  4. `AdvancedAnalyticsDashboard.kt` — raw amount, monthly trend N+1, placeholder categories, transfer counting, month-end boundary
  5. `SharedBudgetManager.kt` — wrong period, raw amount, member filtering bypass
  6. `AdvancedAnalyticsEngine.kt` — budget period blindness, sparkline boundary, effectiveAmount
  7. `InsightsEngine.kt` — merchant key vs display label, anomaly baseline, frequency calculation, logic duplication
  8. `BudgetForecastingEngine.kt` — wrong horizon, zero-spend months, forecast accumulation, truncation cap
  9. `SpendingPersonalityClassifier.kt` — impulse ratio sign, budget scaling, confidence normalization
  10. `RecommendationStateManager.kt` — refresh suppression, priority ordering, multi-user state
