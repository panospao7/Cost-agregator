# Master Issue Registry — ExpenseTracker Codebase

> Generated from 48 batch verification reports. Each issue is traced back to its source batch.
> Fix order: Section A (Universal) → Section B (Pipeline) → Section C (Dependencies) → Section D (Isolated)

---

## Section A: Universal Architectural Epics

### A.1: effectiveAmount vs amount Inconsistency
**Batches affected:** 01, 02, 03, 05, 12, 16, 17, 29, 32, 33, 36, 37, 38, 41, 45, 46
**Severity:** CRITICAL
**Description:** Raw `amount` is used instead of `effectiveAmount` (user-owned share) across analytics SQL, dashboard math, budget calculations, business reports, currency conversions, tax estimates, receipt matching, challenge scoring, income tracking, and merchant aggregates. Shared-expense users see overstated spending in multiple surfaces.
**Affected files:** `ExpenseDao.kt`, `ExpenseRepository.kt`, `AdvancedAnalyticsDashboard.kt`, `AdvancedAnalyticsEngine.kt`, `BudgetRepository.kt`, `SharedBudgetManager.kt`, `MultiCurrencyRepository.kt`, `BusinessExpenseReportGenerator.kt`, `RecurringIncomeTracker.kt`, `SpendingThresholdCalculator.kt`, `TaxEstimator.kt`, `ReceiptTransactionMatcher.kt`, `SpendingChallengeManager.kt`, `InsightsEngine.kt`, `ExpenseWithCategory.kt`, `TransactionsScreen.kt`, `FinancialWeatherRepository.kt`, `BudgetForecastingEngine.kt`, `CashFlowCalculator.kt`, `AccountingExportRepository.kt`, `TotalsAggregationEngine.kt`, `SynthesisEngine.kt`
**Suggested fix:** Centralize the SQL effective-amount `CASE` expression in one shared DAO helper. Audit every aggregate query, dashboard computation, budget check, and report to use this helper. Add regression tests that verify shared-expense rows produce correct user-owned totals.

### A.2: Domain/Data Layer Boundary Violations
**Batches affected:** 24, 34, 46, 47
**Severity:** HIGH
**Description:** Multiple domain model files import Room entities directly: `BlockPartyDay` exposes `data.database.entity.Expense`, `DashboardExpenseMapper` round-trips `DashboardExpense` back to incomplete `Expense` entities (losing shared-expense metadata), AI domain models import `Category`, `PendingReview`, `TransactionType`, and `AiArtifactEntity`. This couples domain contracts to persistence schema and causes data loss on reconstruction.
**Affected files:** `BlockPartyDay.kt`, `DashboardExpenseMapper.kt`, `AiArtifactPresentation.kt`, `ReceiptItemCategorizationModels.kt`, `AiArtifactRepository.kt`, `FinancialQueryModels.kt`, `CaptureAssistModels.kt`, `SemanticDuplicateModels.kt`, `ReviewPriorityModels.kt`, `DomainTransactionFilter.kt`, `NarrativeGenerator.kt`
**Suggested fix:** Introduce domain DTOs for all cross-boundary data. Move entity mappers to the data/adapter layer. Stop round-tripping through `Expense` — keep `DashboardExpense` downstream or extend it with ownership/share fields.

### A.3: Non-deterministic Default Values (System.currentTimeMillis, UUID.randomUUID)
**Batches affected:** 01, 07, 10, 16, 17, 24, 34, 36, 38, 40, 41, 47
**Severity:** HIGH
**Description:** `System.currentTimeMillis()` is used directly instead of the injected `TimeProvider` in: review priority scoring, notification ID generation, challenge ID generation, filter correlation IDs, cache timestamps, worker day keys, investment timestamps, and feature extraction. This makes tests non-deterministic, breaks clock injection for time-travel testing, and causes midnight boundary bugs when different components capture `now` at different moments.
**Affected files:** `ReviewPriorityModels.kt`, `NotificationIdGenerator.kt`, `SpendingChallengeManager.kt`, `DomainTransactionFilter.kt`, `TransactionFilter.kt`, `DailyBriefingWorker.kt`, `InvestmentTracker.kt`, `FeatureExtractor.kt`, `ConfidenceRouter.kt`, `ReviewExplanationInputBuilder.kt`, `DashboardBriefingInputBuilder.kt`, `AddGroupExpenseUseCase.kt`, `SharedExpenseBudgetOffsetEngine.kt`, `SharedExpenseManager.kt`
**Suggested fix:** Inject `TimeProvider` everywhere. Replace all `System.currentTimeMillis()` calls with `timeProvider.now()`. For IDs, use UUID or auto-generated DB keys instead of timestamp-based IDs.

### A.4: Duplicate Detection Logic Inconsistencies
**Batches affected:** 05, 07, 12, 33, 41, 43
**Severity:** HIGH
**Description:** Duplicate detection is currency-blind across notification auto-accept, statement import, and review approval. The 24-hour cross-source dedupe window is too broad for legitimate repeat purchases. DB-level dedupe uses a ~5-minute window. Candidate filtering ignores transaction type, so deposits/transfers can match as purchase duplicates. Dedupe key generation uses locale-sensitive amount formatting.
**Affected files:** `DetectDuplicateExpenseUseCase.kt`, `Expense.generateDedupeKey()`, `ExpenseDao.kt`, `ExpenseRepository.kt`, `NotificationProcessingPipeline.kt`, `ReceiptRepository.kt`, `ReviewQueueRepository.kt`, `CrossSourceDeduplication.kt`, `DedupeJudgeInputBuilder.kt`
**Suggested fix:** Include currency in the dedupe key. Centralize duplicate policy (window, merchant normalization, amount tolerance, scoring) behind one shared component. Filter candidates by compatible transaction type. Make dedupe key generation locale-invariant.

### A.5: Time Boundary / Calendar Arithmetic Inconsistencies
**Batches affected:** 01, 02, 03, 04, 10, 16, 17, 30, 32, 36, 37, 41, 43
**Severity:** HIGH
**Description:** Week boundaries use locale-dependent `Calendar.firstDayOfWeek` instead of standardized Monday-start. Month boundaries use `+30 days` instead of calendar month math. Day indexing uses millisecond division causing DST errors. End boundaries use `23:59:59` instead of start-of-next-day exclusive. Reactive flows capture time windows once and never refresh on rollover.
**Affected files:** `FinancialHealthCalculator.kt`, `BudgetCalculator.kt`, `HistoricalSpendingDistribution.kt`, `TransactionFilterSheet.kt`, `DashboardContractsAdapter.kt`, `BudgetRepository.kt`, `LocationBackfillWorker.kt`, `BillReminderManager.kt`, `RecurrenceCalculator.kt`, `RecurringExpenseEngine.kt`, `TimePeriodUtils.kt`, `AdvancedAnalyticsDashboard.kt`, `SpendingPaceCalculator.kt`
**Suggested fix:** Centralize all period math through `TimePeriodUtils`. Use calendar-aware day/month addition. Use exclusive end boundaries consistently. Drive long-lived reactive flows from a rollover-aware clock/ticker.

### A.6: Mixed Numeric Types (Float vs Double for financial data)
**Batches affected:** 24, 36, 46, 47
**Severity:** MEDIUM
**Description:** Financial domain models mix `Float` and `Double`: `SpendingSummary` uses `Double` totals with `Float` histories, `BudgetStatusSnapshot` stores `percentUsed` as `Float` while amounts are `Double`, `CategoryBreakdown` uses `Float` percentages, `MonteCarloBudgetImpact` uses `Float` for risk fields. This introduces avoidable precision loss in financial calculations.
**Affected files:** `SpendingSummary.kt`, `BudgetStatusSnapshot.kt`, `CategoryBreakdown.kt`, `DashboardCategoryBreakdown.kt`, `MonteCarloBudgetImpact.kt`, `FinancialForecast.kt`
**Suggested fix:** Use `Double` consistently in all domain/repository models. Convert to `Float` only at chart/UI rendering boundaries.

### A.7: Fire-and-Forget Coroutine Anti-Pattern
**Batches affected:** 02, 05, 07, 10, 16, 17, 18, 19, 21, 35, 36, 42, 45, 48
**Severity:** HIGH
**Description:** `catch (e: Exception)` blocks swallow `CancellationException` across analytics, AI use cases, workers, and services. This converts coroutine cancellation into silent failures, prevents proper structured concurrency, and causes stale FAILED artifacts to be persisted for cancelled jobs. Broad exception catching also masks real errors.
**Affected files:** `BudgetMonitor.kt`, `CategorizationAssistInputBuilder.kt`, `InterpretFinancialQueryUseCase.kt`, `DailyBriefingWorker.kt`, `SuggestReceiptExtractionUseCase.kt`, `SuggestCategoryFallbackUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `GenerateDashboardBriefingUseCase.kt`, `InsightsEngine.kt`, `AnomalyAlertOrchestrator.kt`, `ReceiptOcrService.kt`, `WarrantyExpirationWorker.kt`
**Suggested fix:** Re-throw `CancellationException` before any generic catch block. Use `try/catch` instead of `runCatching { }.getOrElse { }` in suspend functions. Log non-cancellation exceptions explicitly.

### A.8: Shared Mutable State / Thread Safety Gaps
**Batches affected:** 01, 02, 07, 10, 11, 15, 25, 27, 28, 34, 36, 41, 42, 45
**Severity:** HIGH
**Description:** `SimpleDateFormat` instances stored as mutable singleton state are used across concurrent calls (warranty extraction, export formatters, dashboard briefing builders). In-memory caches use `mutableMapOf` without `Mutex` or `ConcurrentHashMap`. Singleton mutable state (`lastUsedImageInput`, `processedNotifications`) is shared across concurrent requests.
**Affected files:** `WarrantyTextExtractor.kt`, `AccountingExporters.kt`, `DashboardBriefingInputBuilder.kt`, `SpendingThresholdCalculator.kt`, `BudgetMonitor.kt`, `HybridReceiptAssistService.kt`, `TransactionClassifier.kt`, `GroupTransactionCoordinator.kt`, `RecommendationStateManager.kt`, `ServiceDiagnostics.kt`, `LogSanitizer.kt`, `LocationResolver.kt`
**Suggested fix:** Replace `SimpleDateFormat` with `java.time.DateTimeFormatter` (immutable). Protect cache access with `Mutex` or use `ConcurrentHashMap`. Remove shared mutable state from service classes and return metadata in result objects.

### A.9: Hidden Data Truncation / DAO Default Limits
**Batches affected:** 01, 02, 03, 05, 14, 27, 32, 33, 37, 39, 41, 44, 45
**Severity:** CRITICAL
**Description:** Multiple DAO queries default to `LIMIT 2000` or `LIMIT 500` rows. Consumers across analytics, forecasting, budgeting, export, and business reports never page through results or detect truncation. Users with large histories see silently incomplete data — analytics show wrong totals, forecasts miss recurring patterns, exports are truncated, and tax calculations use partial data.
**Affected files:** `ExpenseDao.kt`, `ExpenseRepository.kt`, `BudgetRepository.kt`, `BudgetForecastingEngine.kt`, `BudgetAutopilotEngine.kt`, `SharedBudgetManager.kt`, `CarbonFootprintCalculator.kt`, `CashFlowCalculator.kt`, `AccountingExportRepository.kt`, `FinancialWeatherRepository.kt`, `MultiCurrencyRepository.kt`, `TaxEstimator.kt`, `SpendingThresholdCalculator.kt`, `RecurringExpenseRepository.kt`
**Suggested fix:** Add an uncapped or explicitly paged variant of each query. Audit every consumer to either page through results or use aggregate SQL (SUM/COUNT/GROUP BY) instead of fetching full row sets. Add a `TruncationDetected` error state to alert when capped queries are used inappropriately.

### A.10: Transaction Type Blindness
**Batches affected:** 02, 03, 05, 32, 33, 37, 39, 41, 42, 45
**Severity:** HIGH
**Description:** Multiple aggregation pipelines treat all `Expense` rows as spending, ignoring `TransactionType` (DEPOSIT, TRANSFER, WITHDRAWAL). Deposits feed spending heatmaps, transfers inflate budget tracking, refunds appear as purchases, and business reports include non-deductible movements. This compounds with A.1 (effectiveAmount) to produce systematically wrong numbers.
**Affected files:** `SpendingHeatmapEngine.kt`, `BudgetRepository.kt`, `CashFlowCalculator.kt`, `BusinessExpenseReportGenerator.kt`, `TaxEstimator.kt`, `FinancialHealthCalculator.kt`, `CategoryInsightEngine.kt`, `TotalsAggregationEngine.kt`, `RecurringIncomeTracker.kt`
**Suggested fix:** Add a canonical `isSpending()` filter at the DAO or repository level. Audit every aggregation pipeline to filter by transaction type. Ensure deposits/transfers are excluded from spending metrics but included in cash flow.

---

## Section B: Domain-Specific Pipelines

### B.1: AI/ML Pipeline
**Batches:** 06, 07, 08, 09, 10, 25, 26, 34, 35, 36

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
  - Month bucketing uses UTC while rest of budget stack uses local calendar (B02, B37)
  - Confidence scoring rewards missing/sparse history as stable evidence (B02, B37)
  - `BudgetForecastingEngine` inserts active rows without deactivating older ones (B27, B28, B37)
  - `BudgetRepository.getBudgetStatuses()` captures time bounds once — long-lived collectors go stale (B32)
  - `BudgetRepository` computes raw spend, ignores `SharedExpenseBudgetOffsetEngine` — budget screen overlays different adjusted spend (B32)
  - `GetMonteCarloBudgetImpactUseCase` messages say "exceed by €0.00" when probability is high but median under budget (B05, B48)
  - `CalculateBudgetStatusUseCase.getBudgetHealth()` ignores `CRITICAL` status (B48)
  - `ComputeDashboardWidgetsUseCase` budget summary says "all on track" when nothing is `EXCEEDED`, even with WARNING/CRITICAL (B48)
  - `AdvancedAnalyticsDashboard.getMonthlyTrend()` builds `23:59:59` month end, passes to end-exclusive repo query — drops last second of month (B36-missed)
  - `AdvancedAnalyticsDashboard` monthly trend N+1 pattern — one repo query per month (B36-missed)
  - `AdvancedAnalyticsEngine` current-period sparklines stop before today — on first day of period can render empty despite spend (B36-missed)
  - `SpendingPersonalityClassifier` confidence calculation mixes normalized 0..1 features with raw `transactionsPerMonth` (B36-missed)
  - `DayOfWeekAnalyzer` results sorted by total spend instead of weekday order — breaks chronological consumers (B36-missed)
  - `TransferDirectionAnalytics` user corrections only change `correctDetections`/accuracy counters — incoming/outgoing totals and top source/destination lists remain wrong after corrections (B36-missed)
  - `BudgetAutopilotEngine` and `BudgetForecastingEngine` use different month bucketing, timezone rules, trend heuristics, confidence formulas — inconsistent signals for same history (B37-missed)
  - `RecurringExpenseRepository` leaves `IRREGULAR` items without advancing `nextDate`; different semantics per code path (B12)

- **LOW:**
  - Daily window snaps to local midnight ignoring anchor time-of-day (B02)
  - Seasonal adjustment unreachable (90-day lookback vs 6-month threshold) (B02)
  - `BudgetMonitor` hardcodes English copy, emoji, hex colors in domain engine (B02)
  - `BudgetForecastingEngine` seasonal factor is simplistic model limitation (B37)
  - `SpendingPaceModels.kt` referenced in batch plan but file doesn't exist — models live in `AnalyticsModels.kt` (B01)

### B.3: Receipt/OCR Pipeline
**Batches:** 08, 09, 10, 39, 44, 45

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

- **CRITICAL:**
  - (None — all database issues are High or below)

- **HIGH:**
  - `Budget` entity allows multiple active overall/category budgets; DAO reads use `LIMIT 1` with no ordering — nondeterministic (B12, B14)
  - `ManualRecurringExpense.isSubscription` defaults to `true` — generic recurring creation paths misclassify as subscriptions (B12, B13)
  - `GroupMember` schema allows multiple `isCurrentUser = 1` per group; DAO uses `LIMIT 1` — nondeterministic current-user resolution (B13, B15)
  - `GroupExpense.expenseId` treated as one-to-one link but not unique — one expense linked to multiple group_expenses rows (B13)
  - `GroupExpense.paidById` only references `group_members.id` without enforcing same-group membership (B13)
  - `MerchantCanonical` keyed by `searchKey` but only `normalizedName` is unique — different display names collapse to same searchKey (B13)
  - `MerchantAlias` reads by `normalizedKey LIMIT 1` but `normalizedKey` not unique — arbitrary alias resolution (B13, B15)
  - `BankConnectionDao.disconnect()` marks inactive but leaves `accessToken`, `refreshToken`, `tokenExpiry` intact — live credentials preserved (B15)
  - `EmailReceiptDao.insert()` uses `REPLACE` on unique `emailMessageId` — re-ingesting same email overwrites source row (B15)
  - `RawNotification` unique index includes nullable `title` and `text` — SQLite NULL rows don't collide, bypassing dedupe (B28)
  - `AnomalyAlert.expenseId` has no FK — orphan alerts remain after expense deletion (B28)
  - `SubscriptionCandidate` schema doesn't enforce one pending per merchant — concurrent detections create duplicates (B27, B28)
  - `BudgetForecast` allows multiple overlapping active forecasts — date-based lookups nondeterministic (B27, B28)
  - `SavingsGoalDao.updateGoalAmount()` overwrites `currentAmount` with caller-computed absolute value — concurrent contributions lose money (B14)
  - `ScannedReceiptDao.linkToExpense()` updates only `expenseId`, leaves `matchStatus`/`suggestedExpenseId` untouched — linked receipts remain `UNMATCHED` (B14)
  - `ExpenseDao.getBusinessExpensesMissingReceipts()` uses `rawNotificationId IS NULL` as receipt proxy — manual/receipt-created business expenses falsely flagged (B14)
  - Business expense queries use raw `amount`, omit `isNotMine`/effective-amount handling — deductible totals overstated (B14)
  - `ExpenseWithCategory.formattedAmount` built from raw `amount`, omits transaction polarity (B29)
  - `UserCorrection` table has no index on `originalMerchant` — hot lookup queries degrade to full scans (B27)
  - `SubscriptionCandidateDao` dedupe is read-then-insert without transaction — concurrent notifications create duplicates (B27)
  - `InvestmentTracker.getInvestmentPerformance()` computes "all-time" high/low from only 30 days (B27)
  - `RecurringExpenseRepository.getAll()` pulls inactive manual recurring rows — engine suppresses detection for deactivated subscriptions (B14)
  - `GroupTransactionCoordinator` `addMemberToGroup()`, `addExpenseToGroup()`, `addExpenseWithLink()` validate state outside single DB transaction — concurrent archive/delete can invalidate checks (B11)
  - `SharedExpenseGroupsViewModel.addExpense()` creates system expense first then `group_expenses` row — crash between two writes orphans the system expense (B11)
  - Migration `69→70` + Android Keystore encryption causing DB open failure (B11)
  - `BankConnection.defaultCategoryId` has no FK to `categories` — deleted categories leave stale IDs (B13)
  - `MerchantLocation.areaKey` nullable inside composite unique index — multiple `(normalizedMerchantName, NULL)` rows bypass uniqueness in SQLite (B13)
  - Financially sensitive numeric fields have no DB-level CHECK constraints across 7 entities (B13)
  - `ExpenseDao.getBusinessExpensesBetween()` doesn't filter `transactionType = 'PURCHASE'` — transfers/deposits listed as deductible (B14)
  - `CategoryDao` → `CategoryRepository.ensureDefaultCategories()` race — concurrent seeding creates duplicate defaults (B14)
  - `CsvExpenseImporter` bypasses singleton Room graph — builds fresh `AppDatabase` instances via local extension (B23)
  - `AnomalyAlertDao.getLastAlertForCategory()` has no `(category, alertedAt)` index — category cooldown checks scan full table (B27)

- **LOW:**
  - Migration `42→43` created `group_expenses.expenseId` as `NOT NULL` for nullable field — repaired in `49→50` (B11)
  - `GroupTransactionCoordinator.addExpenseToGroupAtomic()` ignores `newBalance` — silent no-op (B11)
  - `deleteGroup()` always returns `true` if `archiveGroup()` doesn't throw — nonexistent group reported as success (B11-missed)
  - `Category` entity `init` block throws on invalid persisted values — bad DB row turns reads into exceptions (B12)
  - `Budget.notifyAtWarning/notifyAtCritical` unconstrained (B12)
  - `SavingsGoal.targetAmount/currentAmount` unconstrained (B12)
  - `Expense.splitTemplateId` has no FK (B12-missed)
  - `PendingReview.suggestedType` stored as raw `String` — corrupted rows silently change transaction type (B12-missed)
  - `ExchangeRateDao.getAllRatesForBase()` filters on `toCurrency` but index leads with `fromCurrency` (B15)
  - `EmailReceiptDao.getByReceiptId()` returns single row but multiple sources can share same receiptId (B15-missed)
  - `Global merchant-location keys` inconsistent across pipeline (`"global"` vs `"<normalized>|global"`) (B15-missed)
  - `UserCorrectionDao` tie-breaking in `getMostCommon*` uses `LIMIT 1` with no secondary ordering (B27)
  - `SubscriptionUsageDao.getAllUsageSince()` effectively unindexed for global queries (B27-missed)
  - `SubscriptionCandidate.convertedSubscriptionId` has no FK (B28-missed)
  - `MileageTracking` entity accepts impossible states (negative distance, endOdometer < startOdometer) (B28)
  - `formattedAmount` hardcodes `Locale.US` (B29-missed)

### B.5: Location/Geocoding Pipeline
**Batches:** 18, 30, 32, 42, 44

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
  - `AndroidForegroundLocationProvider.getLastKnownLocation()` only calls `getCurrentLocation()`, never reads cached `lastLocation` (B30)
  - `CompositeGeocodingService.safeLookup()` maps unexpected provider exceptions to `Unknown` — only cascades on transient errors, disabling fallback chain (B30)
  - `NominatimGeocodingService.searchMultiple()` accepts `limit` but always sends `NOMINATIM_MAX_RESULTS` (B30)
  - `LocationResolver` fetches device location before correction/cache checks — multiplies latency and battery cost in backfill runs (B30)
  - Grid-cell bucketing uses `.toLong()` (truncate toward zero) instead of flooring — negative lat/lon hash to wrong cell (B42)
  - `PriceProtectionTracker` uses `receipt.createdAt` and `Instant.now()` instead of `parsedDate` and `TimeProvider` — imported old receipts look newly eligible (B42, B44)
  - `PriceProtectionTracker` generates price drops/deals/coupons from hard-coded heuristics, rendered in user-facing UI as real results (B42, B44)
  - `PriceProtectionTracker.getDealsCouponsAndBenefits()` loads entire receipts table then applies `take(20)` (B42, B44)
  - `MerchantLocationRepository` cache hits call DAO methods that update `lastResolvedAt` — TTL based on last access, not last resolution (B32)
  - DAO uses `CAST(lat/0.045 AS INTEGER)` while repository uses `floor(...)` — negative coordinates hash to different area keys (B42)

- **LOW:**
  - `SpendingMapScreen` date-range chips built from `remember { System.currentTimeMillis() }` — stale windows on long-lived screens (B18)
  - `SpendingMapViewModel` manual `recomputeMapData()` races with reactive collector (B18)
  - Map auto-centres on every GPS change — yanks map away from user viewport (B18)
  - `AreaSpendingEngine` grid cells keep first parsed area name — mixed-address cells labelled by unrepresentative first expense (B42)
  - `TravelDetectionEngine` destination hints extracted with `split(",").getOrNull(1)` — one-part addresses lose destination label (B42)
  - `MerchantNormalizer` logs raw merchant names in plaintext (B42)

### B.6: Notification/Service/Worker Pipeline
**Batches:** 20, 21, 23, 33, 36, 39, 44

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
  - `NotificationCaptureService.onDestroy()` cancels `serviceJob` immediately — in-flight `processNotification()` can be aborted before persistence (B20)
  - `RecommendationStateManager.clearForUser()` always clears in-memory state even when cleared user is not currently displayed (B20)
  - `RecommendationDeduplicator.computeSignature()` always includes `rec.category` — same effective filter with different originating categories treated as distinct (B20)
  - `RecommendationDeduplicator.computeSignature()` omits `ownership` from signature — recommendations differing only by ownership type incorrectly collapsed as duplicates (B47-missed)
  - `NotificationCaptureService` force-started at boot, re-started every minute via repeating alarm — unnecessary wakeups (B21)
  - `RecommendationInvalidator.invalidateAllForUser()` claims to invalidate all but only clears cache and expires already-expired rows (B21)
  - `NotificationRepository.deleteAll()` wipes notifications/expenses/reviews/corrections but only zeroes `pendingReview` in `source_stats` — other counts remain stale (B33)
  - `WidgetStyleRepositoryImpl` DataStore flow lacks `catch` — store corruption terminates all consumers (B33)
  - `ReviewQueueRepository.markAsRelevant(true)` inserts new `PendingReview` when reparsing fails without checking for existing review (B33)
  - `SmsParser` and `RevolutParser` amount regex only accepts single decimal separator — thousands-separated amounts rejected (B44-missed)
  - `NotificationProcessingPipeline` oversized-amount fallback inserts `PendingReview` without semantic duplicate check (B33)

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
  - All three exporters keep `SimpleDateFormat` as instance state, Hilt provides as singletons — concurrent exports race on shared formatter (B39)
  - All exporters emit raw `Double.toString()` for money — inconsistent precision or scientific notation (B39)
  - `DebugData.toJson()` hand-builds JSON, only escapes subset of fields — backslashes/control chars produce invalid JSON (B39)
  - `includeReceipts` parameter exposed but never used (B33-missed)
  - Generic CSV export header omits currency column — mixed-currency CSV flattens unlike amounts (B39-missed)
  - CSV escaping handles commas/quotes/newlines but not formula-injection prefixes (`=`, `+`, `-`, `@`) (B37)

- **LOW:**
  - `DebugData` transaction dates exported as epoch millis while metadata uses ISO — not a functional bug (B39)
  - Mileage summary exposes first trip's `deductionRatePerKm` as if one rate applied to whole period (B37)

### B.8: Savings/Investment Pipeline
**Batches:** 03, 05, 32, 37, 41, 45, 48

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
  - Confidence scoring compares millisecond-squared variance against tiny raw threshold — "low variance" branch practically unreachable (B41)
  - `getStartOfMonth()` leaves milliseconds untouched — transactions at `00:00:00.000` can fall before computed start (B41)
  - `ReceiptMatchingWorker` rescans all unmatched receipts every run with no last-attempt marker (B45)
  - `LifestyleSavingsPromptUseCase` treats `savingsRate <= 1.0` as fraction but detector emits percentages — low rates inflated 100x (B05, B48)
  - Sweep-risk spending includes `WITHDRAWAL` while budget paths use purchase-only (B48)
  - Null Monte Carlo falls back to hardcoded `100.0` risk buffer (B48)
  - `MAX_SINGLE_ALLOCATION_PERCENT` enforced only for non-last goals — final remainder branch can exceed cap (B05-missed, B48-missed)
  - `InvestmentTracker.updatePrice()` stores `dayChange` against previous snapshot, not previous day's close (B41-missed)

- **LOW:**
  - Conservative `* 3.0` projection is arbitrary product heuristic (B01)
  - `SavingsGoal.createdAt` defaults to `0L` (B46)
  - `PlannedExpense.amount` has no non-negative invariant (B46)
  - Alerts/rankings/history use `getAllInvestments()` instead of active holdings only (B41)
  - `allocationPercentage` keeps pre-cap urgency share — displayed percentages disagree with final allocations (B48)
  - `userCorrectionRepository` injected in `DetectDuplicateExpenseUseCase` but never used (B48)

### B.9: UI/Compose Pipeline
**Batches:** 05, 16, 17, 18, 19, 36, 40, 48

- **CRITICAL:**
  - `ReviewViewModel.approveReviewWithEdits()` runs `applyToAll` and `approveAllPending` after primary approve returns `Duplicate` or `Error` — bulk mutations run even though edited approval failed (B18)
  - `LifestyleInflationScreen` uses `Modifier.weight(0f)` when essential/discretionary spending is zero — throws `IllegalArgumentException` (B19)

- **HIGH:**
  - `ALL` tab never records end-of-pagination — after last page, `shouldLoadMore` becomes `true` again, keeps issuing empty requests (B16, B17)
  - `ChangeTypeDialog` only enables Save when transaction type changes — existing `TRANSFER` rows cannot correct direction/account name (B16, B17)
  - Date chips not initialized from `currentFilter`, Apply falls back to previous filter — existing date filters cannot be viewed or cleared (B16)
  - Date headers sum unsigned `effectiveAmount`, render red only when aggregate negative — expense-heavy days shown as positive green totals (B16, B17)
  - Category/merchant/type/not-mine/shared edits update database but don't refresh `_pagedExpenses` — `ALL` tab shows stale rows (B16, B17)
  - `HomeViewModel.reloadDashboard()` doesn't recreate dashboard pipeline after `Error` — Home stuck on same error state (B17)
  - `TransactionsViewModel.applyFilter()` stores `TransactionFilter.ownership` in `_filter` but actual filtering uses `_ownershipFilter` — external ownership filter shown as active but results unfiltered (B17)
  - Manual expense creation and recurring-rule creation not atomic — expense insert succeeds, recurring-rule creation throws, UI reports failure (B17)
  - Year-over-year analytics computed from `purchases` containing only selected period — prior-year data missing for TODAY/WEEK/MONTH/QUARTER (B17)
  - `BudgetForecastingViewModel` on first forecast failure, `_uiState.budget` remains null — `refreshForecast()` becomes no-op, Retry cannot recover (B17)
  - `SharedExpenseGroupsViewModel.loadGroups()` rebuilds state from scratch, wiping `selectedGroup` and dialog flags (B18)
  - `Expense.isNotMine` + `isSharedExpense` simultaneously allowed; `effectiveAmount` zeroes both — rows disappear from analytics (B16)
  - `TransactionsViewModel` external `dateRange` filters intersected with default `MONTH` tab window — drill-down navigation clips results (B16)
  - `VisualSplitEditorScreen` "Apply Split" hands data to callback but navigation host navigates back and discards result — no-op (B19)
  - Spending challenges end-to-end feature incomplete — no persistence API, no domain manager wire-up (B19)
  - `SavingsGoalsViewModel` contributions use read-modify-write snapshots plus `updateGoalAmount()` — concurrent contributions lose money (B18)
  - `AssistantViewModel` clarification replies intentionally drop `conversationHistory` — follow-up answers interpreted without prior context (B19)
  - `AiSettingsViewModel.testConnection()` persists typed API key before connectivity test succeeds — failed tests overwrite working key (B19)
  - `VisualSplitEditorScreen` accepts `currencyCode` but formatted amounts use locale default currency (B19)
  - Visual split assigned amounts matched by `participantName` — duplicate names make multiple rows resolve to same segment (B19)
  - `LifestyleInflationViewModel.analyze()` launches detached jobs without cancelling prior requests — slower older analyses replace newer report (B19)
  - `CarbonFootprintViewModel.loadReport()` has same detached-job pattern — quick period changes leave stale report (B19)
  - `SpendingChallengesViewModel.activeChallenges` exposed to UI but never populated (B19)
  - `AddGroupExpenseUseCase` accepts zero/negative/non-finite amounts and blank descriptions (B40)
  - `ReviewQueueRepository` approved transfer/deposit reviews never copy `suggestedDirection`/`suggestedAccountName` into `Expense` — transfer metadata lost (B18)
  - Review approval pipeline loses optional metadata end-to-end — place id dropped, transfer metadata never copied (B18)
  - Currency presentation not centralized; multiple screens hardcode `€` (B18)

- **MEDIUM:**
  - `ExpenseWithCategory` member formatters shadow extension formatters — row resolves to member formatters using raw `amount` (B16)
  - Active-filter banner only depends on `activeFilter != null` — ownership-only filtering leaves list filtered with no visible banner (B16, B17)
  - Shared-expense editing accepts blank participant names and both-or-neither share fields (B16)
  - Month/year filter ranges use `System.currentTimeMillis()` instead of `TimeProvider`, end timestamps stop at `:59.000` (B16)
  - `correlationId` defaults to `System.currentTimeMillis()`, part of data-class equality — logically identical filters compare unequal (B16)
  - `BudgetViewModel.uiState` recalculates `calculateAdjustedSpend()` for every budget on every emission (B17)
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
  - `CurrencyConverter.storeRate()` accepts zero, negative, `NaN`, infinite exchange rates (B38)
  - `AppConfig` Nominatim User-Agent hardcodes personal email address in shipped source (B38)
  - `Merchant approval/rejection history` cached under lowercase keys, DB queries use raw merchant string — casing/spacing variants miss prior corrections (B41)
  - `CrossSourceDeduplication.isCrossSourceDuplicate()` doesn't compare real transaction data — bank-like sources with non-blank merchant treated as same transaction (B41, B42)
  - Default 24-hour duplicate window broad enough to collapse legitimate repeat purchases, candidate confidence ignores time distance and merchant proximity (B41)
  - `TransactionClassifier` retraining rebuilds counts without clearing `vocabulary` — old tokens remain, inflate `vocabularySize` (B41, B42)
  - `FeatureExtractor.extractFromNotification()` uses current wall clock instead of notification timestamp — reprocessing produces different features (B41)
  - `HybridExpenseClassifier` categories loaded once and cached forever — added/renamed categories invisible until restart (B41)
  - `SemanticKeywordMatcher` wraps every keyword in `\b...\b` — keywords ending in non-word characters like `disney+` never match (B38-missed)
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
  - `fuzzyMatch()` picks first BK-tree result, then computes Jaro-Winkler — equal-distance candidates can resolve suboptimally (B41)

### B.11: Email/Parsing Pipeline
**Batches:** 31, 43, 44

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
  - `GoogleWalletParser` has no transfer path — P2P sends emitted as `PURCHASE`, incoming P2P as `DEPOSIT` (B43)
  - `BankStatementParser` Revolut path emits only `DEPOSIT` or `PURCHASE` — transfers/top-ups/refunds misclassified (B44)
  - `OcrLanguageProcessor.normalizeForLanguage()` routes Cyrillic/Arabic/CJK through Latin-only normalization (B44)
  - `OcrLanguageProcessor` amount extraction mishandles locale-specific separators (B44)
  - `AndroidSpeechInputGateway` voice input starts without `RECORD_AUDIO` permission guard or `SecurityException` handling; recognizer `onError()` signals dropped (B31)
  - `BillReminderManager` `SEMI_ANNUALLY` not handled in reminder scheduling or monthly-cost conversion (B43-missed)

- **MEDIUM:**
  - Seeded merchant mappings uppercased and inserted without `normalizedCanonicalName` — fuzzy layer filters with case-sensitive `startsWith(prefix)` against lowercase input, seeded mappings never participate in fuzzy fallback (B31)
  - `ProcessReceiptUseCase` injected but email receipt service never calls it — email imports have separate behavior path that can drift (B31)
  - `AndroidSpeechInputGateway` starts without `RECORD_AUDIO` permission guard or `SecurityException` handling (B31)
  - `CustomSplitParser` validates with raw `Double` sums and inclusive tolerances — boundary-valid payloads rejected by floating-point drift (B43)
  - `CUSTOM_AMOUNT`/`UNEQUAL` splits accept arbitrary decimal precision — sub-cent liabilities stored (B43)
  - `RecurringExpenseEngine` groups merchants with `lowercase().trim()` instead of canonical merchant key (B43)
  - `SynthesisEngine.pastSumDaily.lastOrNull()` used without `isFinite()` guard — single `NaN`/`Infinity` poisons every projected point (B43)
  - `GenericTransactionParser` date extraction uses lenient `Calendar` normalization — impossible dates accepted (B43)
  - `GreekBankParser` transfer parsing accepts Latin one-letter codes but direction detection only recognizes Greek/full-word codes (B43)
  - `SmsParser` and `RevolutParser` amount regex only accepts single decimal separator — thousands-separated amounts rejected (B44-missed)
  - `EmailReceiptIngestionService` inserts `ScannedReceipt` and `EmailReceiptSource` in separate DAO calls — partial-write state (B13)

- **LOW:**
  - (None specific to this pipeline beyond those captured above)

### B.12: Groups/Shared Expenses Pipeline
**Batches:** 33, 40, 43

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
  - `SharedExpenseManager.addExpense()` can persist group expense whose `paidById` belongs to different group — DB enforces member exists but not same-group membership (B40)
  - `SharedExpenseBudgetOffsetEngine.getPendingReimbursement()` subtracts `totalReimbursed` from `totalSharedSpend` — fields represent different concepts, sign and amount can be wrong (B40)
  - `isExpenseFullySettled()` uses `myShareAmount ?: totalAmount / members.size` as generic fallback — ignores custom splits, misuses current-user-specific field (B40)
  - Equal-split budget math uses naive floating-point division while authoritative group split uses cent-based remainder distribution (B40)
  - `SharedExpenseManager.addExpense()` validates custom-split finiteness but doesn't reject blank descriptions, non-finite totals, non-positive amounts (B40-missed)
  - `SharedExpenseBudgetOffsetEngine.calculateMyShare()` diverges from authoritative split pipeline for `CUSTOM_PERCENT` and malformed payloads (B40-missed)
  - `SharedExpenseBudgetOffsetEngine.calculateEffectiveBudgetSpend()` accepts `userId` but never uses it (B40-missed)
  - `SharedExpenseGroupsViewModel` computes splits and balances via `SplitCalculator` directly instead of consuming domain services (B40)
  - Room-entity repository path vs domain-port path: group subsystem has two parallel access patterns (B40)
  - `SynthesisEngine` resolves `budgetLimit` as `overall budget or category-budget sum`, but Block Party receives only `overallBudget?.budgetAmount` (B43)
  - `SharedExpenseDataPortAdapter.addMember()` bypasses `GroupTransactionCoordinator.addMemberToGroup()` — archived/inactive-group validation skipped for member creation (B33-missed)
  - Validation pipeline for group creation vulnerable to archive/member-change races (B11)
  - UI validation → database: invariants only inconsistently enforced above DB (B12)
  - `customSplitsJson` not actually JSON; parsing split between `CustomSplitParser` and `SharedExpenseBudgetOffsetEngine` (B13)

- **LOW:**
  - `SharedExpenseManager.isCurrentUser = (name == currentUserName)` is case-sensitive (B40)
  - `SharedExpenseManager.addExpense()` hardcodes `System.currentTimeMillis()` (B40)
  - `AddGroupExpenseUseCase` hardcodes `System.currentTimeMillis()` in default `date` parameter (B40)
  - `SharedExpenseBudgetOffsetEngine` hardcodes `Dispatchers.IO` (B40)
  - Personal-spend summation uses `amount` instead of `effectiveAmount` (B40)
  - `SharedExpenseBudgetOffsetEngine` imports Room entities and repository implementations directly (B40)
  - `AddGroupExpenseUseCase` depends directly on data-layer types (B40)

---

## Section C: Cross-Component Pipeline Dependencies

### C.1: Blocking Fixes (Must Fix First)

1. **effectiveAmount standardization** → Blocks: All analytics, budget, business, tax, currency, challenge, and receipt-matching pipelines that currently use raw `amount`
2. **Budget period/window centralization** → Blocks: Budget status, forecasting, shared-budget progress, alerts, dashboard weather, recommendations
3. **Duplicate detection policy centralization (currency-aware)** → Blocks: Notification ingestion, review approval, statement import, cross-source dedupe
4. **Domain/data boundary cleanup (BlockPartyDay, DashboardExpenseMapper, AI models)** → Blocks: Dashboard widgets, analytics, AI artifact diagnostics, recommendation engine
5. **TimeProvider injection everywhere** → Blocks: Deterministic testing, rollover-aware reactive flows, worker day-key consistency, feature extraction reproducibility
6. **CancellationException handling across all catch blocks** → Blocks: Structured concurrency, proper job lifecycle, stale artifact prevention
7. **Deduplication locale-invariant amount formatting** → Blocks: Cross-locale duplicate detection in notification ingestion, statement import, review approval (B12)

### C.2: Sequential Fix Dependencies

- Step 1: Centralize effective-amount SQL helper → Enables: Fix all analytics, budget, business, tax, currency, challenge, receipt-matching pipelines (A.1 → B.2/B.3/B.8/B.10/B.12)
- Step 2: Make BudgetCalculator single source of truth for period math → Enables: Fix forecasting, shared-budget, alerts, dashboard weather (B.2 → B.6)
- Step 3: Fix duplicate detection (currency + transaction type) → Enables: Fix notification pipeline, review approval, statement import (A.4 → B.6/B.11)
- Step 4: Remove DashboardExpense→Expense round-trip → Enables: Fix dashboard widgets, analytics, block-party, spending pace (A.2 → B.9)
- Step 5: Inject TimeProvider everywhere → Enables: Fix rollover-aware flows, worker consistency, feature extraction reproducibility (A.3 → B.5/B.6/B.10)
- Step 6: Fix CancellationException handling → Enables: Proper structured concurrency across AI, workers, services (A.7 → B.1/B.6)
- Step 7: Centralize split-resolution logic → Enables: Fix budget-offset, settlement, UI calculation paths (B.12 → B.9)
- Step 8: Fix AI routing/privacy policy → Enables: Fix all AI use case input builders, cloud providers, artifact persistence (B.1 → B.3)

### C.3: Independent Fix Groups

- **Group 1: Database schema constraints** — Unique indexes, FK constraints, CHECK constraints across entities (B.4)
- **Group 2: Email parser fixes** — Capture groups, HTML parsing, locale-aware amounts, currency detection (B.11)
- **Group 3: Geocoding service fixes** — Retry semantics, Unicode normalization, log sanitization, HTTP cancellation (B.5)
- **Group 4: Export format fixes** — Currency column, transaction type, PDF generation, formula-injection escaping (B.7)
- **Group 5: UI Compose fixes** — Pagination, filter state, ownership validation, date header signs (B.9)
- **Group 6: Notification pipeline fixes** — Package allowlist, text fallback chain, recommendation state management (B.6)
- **Group 7: Investment tracker fixes** — Fee inclusion, day-change calculation, portfolio history, all-time extrema (B.8)
- **Group 8: Tax estimator fixes** — Progressive brackets, period math, annual summary, business-only scope (B.8)
- **Group 9: Analytics engine consistency** — `InsightsEngine`, `AdvancedAnalyticsDashboard`, `AdvancedAnalyticsEngine`, and `SpendingPersonalityClassifier` re-implement each other's logic; merchant naming, day ordering, and anomaly baselines already diverge — fix at source engines before fixing consumers (B36-missed)
- **Group 10: Lifestyle savings prompt pipeline** — `LifestyleSavingsPromptUseCase` evaluates savings rate and fires prompts but never records impressions; cooldown never starts after show, only after action — fix impression recording to close the loop (B33-missed)
- **Group 11: `ExpenseDao` weekly aggregates** — `MIN(date)/MAX(date)` transaction timestamps forwarded as week boundaries — final transaction of week or day can be omitted from weekly analytics (B36-missed)
- **Group 12: Financial health KPI duplication** — `FinancialHealthCalculator ↔ FinancialHealthScoreV2 ↔ ComputeDashboardWidgetsUseCase` — two incompatible health KPIs side by side with different formulas/filters/week definitions (B03)
- **Group 13: FinancialHealthScoreV2 exception swallowing** — `FinancialHealthScoreV2 → ComputeDashboardWidgetsUseCase` V2 swallows fatal calculation exceptions into `50` — dashboard renders as real health data (B03)
- **Group 14: Dual Monte Carlo implementations** — `FinancialStressForecastEngine` injects but doesn't use `MonteCarloSpendingSimulator` — two separate Monte Carlo implementations with divergent assumptions coexist (B04)
- **Group 15: MonthlySavingsSweepUseCase dead widget** — `MonthlySavingsSweepUseCase → ComputeDashboardWidgetsUseCase → HomeScreen` — `DashboardWidget.SavingsSweepPrompt` never emitted, `HomeScreen` renders empty placeholder (B05)
- **Group 16: AI artifact → recommendation persistence** — `RecommendationEntity.sourceArtifactId` joins and cleanup cannot be enforced safely — stored as required `String` with empty-string sentinels (B12)
- **Group 17: Merchant analytics inconsistency** — some paths group by raw merchant text, some by canonical `merchantKey`, one path exposes key as display name — fix at engine level before fixing consumers (B36)
- **Group 18: Forecasting duplication and divergent assumptions** — `FinancialStressForecastEngine ↔ MonteCarloSpendingSimulator ↔ DataQualityAssessor` — different period math, sampling strategies, and confidence models (B37)
- **Group 19: RevolutParser ↔ BankStatementParser inconsistency** — same Revolut bank produces different transaction types (TRANSFER vs PURCHASE/DEPOSIT) depending on parser path (B44)
- **Group 20: ReceiptTransactionMatcher → ReceiptMatchingWorker → NotificationService chain** — matching error becomes data-integrity + notification mismatch (B45)
- **Group 21: Savings recommendation ↔ automation ↔ gamification divergent ledger** — use different proxies instead of shared contribution ledger (B45)
- **Group 22: TaxConfiguration vs TaxEstimator contract mismatch** — `TaxConfiguration` exposes progressive brackets but `TaxEstimator` uses flat-rate (B45)
- **Group 23: ReceiptRepository.processBatch() parallelism vs WarrantyTextExtractor thread safety** — singleton warranty path reuses one `WarrantyTextExtractor` with shared `SimpleDateFormat` — thread-safety exposed in real pipeline (B45)
- **Group 24: Block-party domain boundary violation** — `BlockPartyDay` carries `Expense`, use case maps to `DomainExpenseSummary`, UI mapper recreates `Expense` — crosses domain boundary twice (B46)
- **Group 25: Duplicate model types** — two `CategoryBreakdown` types / two `PeriodRange` types with overlapping semantics used by different screens/components (B46)
- **Group 26: Inconsistent localization boundary** — raw `String`, `UiText`, hardcoded currency text, direct Android `R` in domain logic (B46)
- **Group 27: RecommendationRepository in-memory dedup vs JSON ordering** — parses JSON into normalized fields but compares rows using `filterCriteria.hashCode()` — semantically identical filters with different JSON ordering bypass cross-call deduplication (B47)
- **Group 28: Financial weather vs dashboard forecast divergence** — uses merged detected+manual recurring patterns, but dashboard forecast widgets get only manual recurring rows — weather/runway/block-party/Monte Carlo can disagree (B48)

---

## Section D: Isolated / Quick-Win Bugs

### D.1: Critical (Quick Wins)
- `CarbonFootprintCalculator.calculateCarbonFootprint()` collects Room Flow in one-shot suspend — replace `collect` with `first()` (B37, B42)
- `ReviewViewModel.approveReviewWithEdits()` runs bulk mutations after primary approve fails — add early return unless `Result.Success` (B18)
- `LifestyleInflationScreen` `Modifier.weight(0f)` throws `IllegalArgumentException` — clamp weights to positive minimum (B19)

### D.2: High (Quick Wins)
- `RecommendationStateManager` sorts by `compareByDescending { it.priority }` using enum ordinal — `LOW` placed ahead of `HIGH` (B20)
- `BankConnectionDao.disconnect()` leaves token fields intact — null them out in same update (B15)
- `BillReminderManager` urgency thresholds don't match enum semantics — overdue/today should be `CRITICAL` (B39)
- `BillReminderManager.markBillPaid()` advances only one interval from stored due date — advance from `max(now, currentDueDate)` (B39)
- `BudgetForecastingViewModel` on first failure, `_uiState.budget` remains null — persist requested budget before running forecast (B17)
- `CalculateFinancialForecastUseCase` maps all savings goals to `TRACKING` — map entity protection enum to domain enum (B05, B48)
- `MonthlySavingsSweepUseCase` goal allocations never capped by remaining gap — cap each goal by remaining target (B05)
- `SharedBudgetManager.getMemberContributions()` returns hardcoded zero placeholders — disable API or implement real calculation (B02, B37)
- `PriceProtectionTracker` generates price drops/deals from hard-coded heuristics rendered as real results — hide behind debug providers (B42, B44)
- `BankApiIntegration` returns successful OAuth URLs, demo tokens, mock sync results — gate behind "not implemented" error (B39)
- `ManualRecurringExpense.isSubscription` defaults to `true` — change default to `false` (B12, B13)
- `TaxEstimator.getTaxYearSummary()` hardcodes annual income to `30000.0` — feed real annual income (B45)
- `AdvancedAnalyticsDashboard` incoming `TRANSFER` transactions counted as income — filter transfers from cashflow calculation (B36-missed)
- `AdvancedAnalyticsDashboard` top categories rendered as placeholder labels like `Category 5` — resolve category names from category store (B36-missed)
- `InsightsEngine` merchant insights expose `ms.merchantName` (canonical key) not display label — use resolved display label (B36-missed)
- `InsightsEngine` merchant-level anomaly detection uses all-time stats including current-month — exclude current month from baseline (B36-missed)
- `InsightsEngine` `RecurringExpense.frequency` set to `30 / intervalDays` — use actual occurrence count (B36-missed)
- `SpendingPersonalityClassifier.calculateImpulseRatio()` uses `abs(purchase.date - incomeDate)` — only count purchases after payday (B36-missed)
- `SpendingPersonalityClassifier` budget adherence scales 3-month window against raw budget without period scaling — scale budget to comparison window (B36-missed)
- `SynthesisEngine.calculateBlockPartyData()` sorts `topTransactions` by raw `Expense.amount` while rest of budgeting uses `effectiveAmount` — sort by `effectiveAmount` (B46-missed)
- `DashboardFollowThroughRecommendation.expiresAt` derived from `createdAt` only at construction — later `copy(createdAt = ...)` leaves `expiresAt` stale, breaking TTL invariant (B24)
- `DashboardExpenseMapper` `DashboardExpense` → `Expense` reconstruction loses shared-expense fields — `isSharedExpense`, `myShareAmount`, `mySharePercentage` not carried through (B24)
- `SuggestReceiptExtractionUseCase` `sourceHash` derived from `ReceiptAssistInput.hashCode()` including `currentTimeMs` — cache effectively disabled (B36)
- `BillReminderManager.calculateNextDate()` `ANNUALLY`, `SEMI_ANNUALLY`, `IRREGULAR` fall through to default monthly advance — stringly-typed enum drift (B39)
- `MultiCurrencyRepository` every reporting method calls `expenseDao.getExpensesBetween()` with default 2000-row cap — large reporting windows return incomplete converted totals (B32-missed)

### D.3: Medium (Quick Wins)
- `BudgetDao.getOverallBudget()` and `getByCategory()` assume single active row — add deterministic `ORDER BY` (B14)
- `ExpenseDao.searchMerchants()` uses `UPPER(merchant) LIKE '%...%'` — use normalized/indexed search key (B14)
- `WarrantyDao.getTotalProtectedValue()` treats `status = 'ACTIVE'` as sufficient — add `currentTime` filter (B14)
- `WarrantyDao.getTotalProtectedValue()` sums raw `expense.amount` instead of `effectiveAmount` (B12)
- `ExpenseDao` → `BudgetRepository.getSuggestions()` N+1 per-category loop (B14)
- `CsvExpenseImporter` `line.split(",")` breaks quoted CSV fields — merchants/descriptions with commas corrupt column parsing (B23)
- `CsvExpenseImporter` failed date parse silently substitutes `System.currentTimeMillis()` — historical expenses rewritten with today's date (B23)
- `RecurringPattern.kt` missing invariants — allows negative/non-finite amounts, negative variance days, out-of-range confidence/percentage (B24)
- `WarrantyExtractionModels.kt` missing invariants — allows negative `warrantyMonths`, negative `returnDays`, out-of-range `confidence` (B24)
- `NotificationParsingModels.kt` missing invariants — documents positive amount and bounded confidence but enforces neither (B24)
- `DomainTransactionFilter.correlationId` dropped by `TransactionFilterSerializer` — recommendation-generated filters lose end-to-end trace (B24)
- Artifact hashing — several use cases derive `sourceHash` from `hashCode().toString()`, weaker than SHA-256 for long-lived cache identity (B35)
- `toReadableMessage()` / route-diagnostic formatting / failure-message assembly duplicated across AI use cases (B35)
- `MonteCarloSpendingSimulator.countRecentQualifyingWeeks()` treats any `total > 0` week as qualifying — confidence overstated (B40)
- `SpendingPatternsCard` `maxOfOrNull(...) ?: 1.0` produces `NaN` when all totals `0.0` — use `takeIf { it > 0 } ?: 1.0` (B17)
- `TransferDirection.valueOf(review.suggestedDirection)` assumes valid enum — parse with `runCatching` (B18)
- `CategoryDao.getByName()` is case-sensitive — add unique `COLLATE NOCASE` index (B14)
- `ReceiptItemCategorizationDao.getTotalForCategoryInExpense()` counts rows where either suggested or corrected matches — use `COALESCE` (B14)
- `PendingReviewDao` legacy fallback queries have no index on `suggestedMerchant` — add composite index (B14)
- `MerchantNormalizationDao.getAliasByNormalizedKey()` does `LIMIT 1` on non-unique key — enforce uniqueness (B15)
- `ManualRecurringExpenseDao` dual APIs disagree on ordering — make both use same ordering (B15)
- `GroupMemberDao.getCurrentUserFlow()` uses `LIMIT 1` with no `ORDER BY` — enforce single current-user row (B15)
- `GroupExpenseDao.getGroupExpenseForExpense()` returns `LIMIT 1` but `expenseId` only indexed — add unique constraint (B15)
- `MerchantLocationDao.upsertLocation()` read-then-insert under unique index — use single-statement upsert (B15)
- `ExchangeRateDao.getAllRatesForBase()` filters on non-leading index column — add index on `(toCurrency, fromCurrency)` (B15)
- `EmailReceiptDao.getByReceiptId()` returns single row but multiple sources can share same receiptId — return `List` (B15-missed)
- `SplitItemAssignmentDao.getParticipantTotals()` groups by `participantName` only — group by stable key (B15)
- `UserCorrectionDao` tie-breaking uses `LIMIT 1` with no secondary ordering — add stable secondary sort (B27)
- `AiChatRepositoryImpl.appendMessage()` persists message and updates session as two writes — move to one transaction (B27)
- `InvestmentValueDao.getPortfolioValueHistory()` one query per investment — add batched query (B27)
- `MileageTracking` reporting queries need composite index `(isBusinessTrip, date)` (B27)
- `BudgetForecastDao.getForecastForDate()` returns `LIMIT 1` without ordering — add `ORDER BY` (B27)
- `HealthScoreHistory` `(periodStart, periodEnd)` only indexed — make unique (B27)
- `SubscriptionUsageDao.getAllUsageSince()` effectively unindexed — add standalone index on `usedAt` (B27-missed)
- `SubscriptionCandidate.convertedSubscriptionId` has no FK — add nullable FK (B28-missed)
- `MileageTracking` entity accepts impossible states — add validation (B28)
- `formattedAmount` hardcodes `Locale.US` — centralize formatting (B29-missed)
- `ExpenseWithCategory_Extensions` shadowed by member properties — delete duplicate extensions (B29)
- `getExpensesPagedDynamic()` selects subset of columns but maps to full `ExpenseWithCategory` — use `SELECT e.*` (B29)
- `CloudReceiptItemCategorizationService` uses `ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS` for cloud — add cloud-specific constant (B09)
- `CloudWarrantyExtractionService` hardcodes model name and token budget — use shared config (B09)
- `CloudReviewExplanationService` generates new correlation ID per retry — generate one before retry loop (B09)
- `CloudWarrantyExtractionService` accepts `"null"` string placeholders — filter placeholders (B09)
- `CloudReceiptItemCategorizationService` hardcodes `€` in prompts — use input currency (B09)
- `OnDeviceDashboardBriefingService` confidence parsed with `optDouble.toFloat()` without finiteness check — parse strictly (B09-missed)
- `OnDeviceDedupeJudgeService` `matchedTargetId`/`confidence` use lenient parsing — use strict parsing (B09-missed)
- `OnDeviceCategorizationAssistService` lenient numeric parsing can emit `categoryId = 0`, `confidence = NaN` — require finite values (B09)
- `NotificationFilter.shouldCapture()` lowercases content but regex only matches uppercase — make regex case-insensitive (B20)
- `RecommendationStateManager.clearForUser()` clears in-memory state for non-current user — only clear when `currentUserId == userId` (B20)
- `RecommendationDeduplicator.computeSignature()` always includes `rec.category` — build target-specific signatures (B20)
- `RecommendationInvalidator` swallows exceptions with empty catch — log failures (B21)
- `NotificationSeeder` derives package names from display labels — map to valid parser package IDs (B39)
- `NotificationSeeder.generateRecurring()` produces isolated random charges — generate clustered occurrences (B39)
- `ServiceDiagnostics` counters use unsynchronized read-modify-write on SharedPreferences — guard with lock (B39)
- `DebugIssueDetector` OCR-quality heuristic counts literal `?` — count only replacement characters (B39)
- `DebugData.toJson()` hand-builds JSON, only escapes subset of fields — use real serializer (B39)
- `DashboardFollowThroughEngine` category/merchant recommendations hardcode `PURCHASE` — preserve source transaction type (B39)
- `DatabaseBackupRepository` import restart semantics tunnelled through sentinel values — return explicit result model (B39)
- `AccountingExporters` SimpleDateFormat as singleton instance state — use `java.time` or instantiate per call (B39)
- `AccountingExporters` emit raw `Double.toString()` for money — centralize money formatting (B39)
- `includeReceipts` parameter exposed but never used — implement or remove (B33-missed)
- `Generic CSV export` header omits currency column — add Currency column (B39-missed)
- `CSV escaping` doesn't handle formula-injection prefixes — prefix dangerous characters with `'` (B37)
- `Mileage summary` exposes first trip's rate as if uniform — show weighted rate (B37)
- `SmsParser.detectSmsDirection()` returns `INCOMING` on tie for transfers — return `null` for ambiguous transfers (B44)
- `RevolutParser` amount regex only accepts single decimal separator — broaden regex (B44-missed)
- `SmsParser` amount regex same limitation — capture full token, delegate to `AmountUtils.parseAmount()` (B44-missed)
- `ImageCache` keyed only by URI hashCode — include dimensions in key (B44)
- `Disk cache` never evicts — add size/age-based pruning (B44)
- `BankStatementParser` header/date-column detection computed but never used — apply or remove (B44)
- `EnhancedMerchantExtractor.isPrice()` only filters lines with currency token — reject total/amount lines without currency (B44)
- `EnhancedMerchantExtractor` drops known merchant when OCR yields no candidates — fall back to existingMerchant (B44)
- `OcrPreprocessingPipeline` median-filter allocates new list per pixel — use reusable buffer (B44)
- `CustomSplitParser` validates with raw Double sums — validate in cents/basis points (B43)
- `CUSTOM_AMOUNT/UNEQUAL` splits accept arbitrary decimal precision — reject >2 decimal places (B43)
- `RecurringExpenseEngine` groups with `lowercase().trim()` instead of canonical key — group by `merchantKey` (B43)
- `SynthesisEngine.pastSumDaily.lastOrNull()` without `isFinite()` guard — reject non-finite inputs (B43)
- `GenericTransactionParser` date extraction uses lenient Calendar — use strict java.time (B43)
- `GreekBankParser` direction detection doesn't recognize Latin codes — extend detection (B43)
- `BillReminderManager` `SEMI_ANNUALLY` not handled — add explicit handling (B43-missed)
- `ComputeDashboardWidgetsUseCase` keeps only `overallBudget` as resolved limit — resolve as `overall-or-category-sum` (B43-missed)
- `CalculateBudgetStatusUseCase.getBudgetHealth()` ignores `CRITICAL` — count explicitly (B48)
- `ComputeDashboardWidgetsUseCase` budget summary says "all on track" when nothing EXCEEDED — treat non-ON_TRACK as non-healthy (B48)
- `ReviewExpenseUseCase` returns Success when categoryId is null — require non-null category (B48)
- `ProcessReceiptUseCase` coerces missing merchant/total to "Unknown"/0.0 with no review signal — return incomplete result (B48)
- `LifestyleSavingsPromptUseCase` maxCap becomes 0 but coerceAtLeast(1.0) forces 1% uplift — handle zero rates explicitly (B48)
- `MonthlySavingsSweepUseCase` allocationPercentage keeps pre-cap urgency share — recalculate from finalized amounts (B48)
- `ComputeMoneyRadarUseCase` depends directly on `AnomalyAlertDao` — introduce repository interface (B48)
- `MonthlySavingsSweepUseCase` redefines `effectiveAmount` locally — use canonical property (B48)
- `ComputeMoneyRadarUseCase` independent fetches run sequentially — use `async`/`await` (B48)
- `DetectDuplicateExpenseUseCase` userCorrectionRepository injected but unused — remove or integrate (B48)
- `GetMonteCarloBudgetImpactUseCase` messages say "exceed by €0.00" — choose messages from riskTier + expectedOverrun (B48-missed)
- `MonthlySavingsSweepUseCase` MAX_SINGLE_ALLOCATION_PERCENT not enforced for last goal — cap all allocations consistently (B48-missed)
- `BlockPartyDay` imports Room `Expense` entity — replace with domain DTO (B46)
- `FinancialForecast.actionableInsights` is `List<String>` while feature family uses `UiText` — use `List<UiText>` (B46)
- `ForecastHorizon.REST_OF_MONTH` uses `days = 0` as sentinel — model calendar-bound case explicitly (B46)
- `PeriodRange` accepts `end < start` — add `require(end >= start)` (B46)
- `PlannedExpense.amount` has no non-negative invariant — enforce `amount >= 0` (B46)
- `RecurrenceFrequency` mixes approximate fixed-day values for calendar frequencies — remove `intervalInMs` for calendar-based (B46)
- `SavingsGoal.createdAt` defaults to `0L` — remove default or supply from injected clock (B46)
- `UpcomingItem.Recurring.id` uses only `merchantName` — use `pattern.id` or composite key (B46)
- `MonteCarloBudgetImpact` stores preformatted UI strings, hardcodes EUR — keep raw values only (B46)
- `DashboardExpenseMapper` imports Room `Expense`/`TransactionType` — move mapper to data layer (B47)
- `DomainTransactionFilter` depends on `TransactionType` and `OwnershipFilter` from data layer — define domain-level enums (B47)
- `DomainTransactionFilter.correlationId` defaults to `System.currentTimeMillis()` — move outside value object (B47)
- `SpendingSummary` mixes `Double` totals with `Float` histories — use `Double` consistently (B47)
- `WidgetStyleConfig` accepts any string key but persistence only restores allowlisted set — validate at boundary (B47)
- `DashboardCategoryBreakdown.changeFromLastPeriod` hardcoded to `0.0` — calculate or remove (B47)
- `BudgetStatusSnapshot` `percentUsed` is `Float` while amounts are `Double` — store as `Double` (B47)
- `ComputeDashboardWidgetsUseCase.DomainExpenseSummary.categoryName` populated with `categoryId?.toString()` — pass real name or rename field (B47-missed)
- `DashboardWidgetUiMapper` converts transaction summaries into synthetic `Expense` entities with hardcoded `PURCHASE` — map to dedicated UI summary model (B47-missed)
- `CategoryRepository.learnMerchantCategory()` inserts without `normalizedCanonicalName` and without cache invalidation — route through engine path (B38)
- `CategoryKeywords` `"roasters"` declared twice — deduplicate (B38-missed)
- `SemanticKeywordMatcher` wraps keywords in `\b...\b` — handle punctuation-at-edge tokens (B38-missed)
- `AppleReceiptParser.detectCurrency()` uses raw substring checks — match bounded tokens (B31-missed)
- `UberReceiptParser` same currency detection issue (B31-missed)
- `UberReceiptParser.parseUberDate()` fills in current year for year-less dates — derive from email `receivedAt` year (B31-missed)
- `WarrantyTextExtractor` "date at start of line" regex not compiled with `MULTILINE` — add flag (B45-missed)
- `Expense.splitTemplateId` has no FK — add nullable FK (B12-missed)
- `PendingReview.suggestedType` stored as raw `String` — validate against allowed names (B12-missed)
- `ClipboardAmountParser` regex grabs partial match on thousands-formatted values — anchor whole-token matching (B23-missed)
- `CsvExpenseImporter` emits 8-digit ARGB colors but Category entity only accepts 6-digit `#RRGGBB` — emit 6-digit hex (B23-missed)
- `AmountUtils` comma-group validation accepts `1,0000` — require 3-digit chunks (B23)
- `CurrencyNormalizer.uppercase(Locale.getDefault())` is locale-sensitive — use `Locale.ROOT` (B23)
- `MerchantCleaner` stop-word stripping truncates at first internal `" at"` — strip only anchored positions (B23)
- `Money.format()` depends on device locale — use fixed locale or `BigDecimal.toPlainString()` (B23)
- `DateFormatterUtils` ThreadLocal cache never evicts — remove or bound (B23)
- `DateFormatterUtils` cached formatters capture locale at creation — cache by `(pattern, locale)` (B23)
- `HapticFeedback` uses `CONFIRM`/`REJECT` without pre-30 fallback — gate on `SDK_INT` (B23)
- `StringDistanceUtils.isFuzzyMatch()` recompiles regexes every call — hoist to constants (B23)
- `EmailReceiptSource.fingerprint` is primary dedupe lookup but schema only adds non-unique index — make unique (B13-missed)
- `GroupTransactionCoordinator.deleteGroup()` always returns `true` — return affected-row count (B11-missed)
- `InvestmentTracker.getValuesBetween()` returns ascending, `getInvestmentPerformance()` reads `firstOrNull()` for day change — use `lastOrNull()` (B27-missed)
- `FinancialHealthScoreV2.saveToHistory()` read-then-insert without uniqueness guarantee — add unique constraint, use UPSERT (B41)
- `RecurringIncomeTracker` groups deposits by raw merchant including blank — skip blank merchants (B41)
- `ConfidenceRouter` ensureSourceStats timestamps with `System.currentTimeMillis()` — use `timeProvider.now()` (B41)
- `CrossSourceDeduplication.isCrossSourceDuplicate()` doesn't compare real transaction data — redesign API (B41)
- `TransactionClassifier` save/load failures log only message, not exception — use `Timber.e(e, ...)` (B41)
- `FeatureExtractor.extractFromNotification()` uses wall clock — accept explicit timestamp (B41)
- `MerchantNormalizer` alias persistence stores original `rawName` bypassing length guard — persist sanitized name (B41)
- `MerchantNormalizer` logs raw merchant names — hash/anonymize (B42)
- `HybridExpenseClassifier.initialized` read outside mutex, not `@Volatile` — make `@Volatile` or move inside mutex (B42)
- `AreaSpendingEngine` grid cells keep first parsed area name — track frequencies, keep most common (B42)
- `TravelDetectionEngine` destination hints use `split(",").getOrNull(1)` — fall back to first component (B42)
- `SavingsGamificationEngine.goal_crusher` uses `goals.firstOrNull()` — use max normalized progress (B03)
- `SavingsGamificationEngine.unlockedAt` recomputed on each call — persist first-unlock timestamps (B03)
- `goal.currentAmount / goal.targetAmount` unguarded for zero target — guard division (B03)
- `FinancialHealthCalculator.calculateBudgetHealthScore()` accepts `periodExpenses` but never uses it — remove parameter or make period-aware (B03)
- `FinancialHealthCalculator.calculateTodayScore()` increments `noSpendStreak` locally — trust supplied streak (B03)
- `FinancialHealthCalculator` week calculations use locale-dependent `Calendar.firstDayOfWeek` — reuse `TimePeriodUtils` (B03)
- `FinancialHealthScoreV2` trend compares against `getMostRecent()` without excluding current period — compare against latest different period (B03)
- `FinancialHealthCalculator.budgetStatuses.all { }` vacuously true for empty list — require `isNotEmpty()` (B03)
- `FinancialHealthCalculator` legacy score capped at 70, `EXCELLENT (85-100)` unreachable — rebalance weights (B41)
- `FinancialHealthScoreV2` on exception returns synthetic score of 50 — add explicit fallback flag (B41)
- `RecurringIncomeTracker` confidence compares ms-squared variance against tiny threshold — normalize to days (B41)
- `RecurringIncomeTracker.getStartOfMonth()` leaves milliseconds untouched — set `MILLISECOND = 0` (B41)
- `SpendingChallengeManager.durationDays * 24 * 60 * 60 * 1000L` overflow — cast to `Long` first (B38)
- `SpendingChallengeManager.daysRemaining` can go negative — clamp with `coerceAtLeast(0)` (B38)
- `SpendingChallengeManager` IDs use `System.currentTimeMillis()` — use UUID (B38)
- `CategorizationEngine` reloads cache fragments via three accessors per call — fetch one snapshot (B38)
- `CategorizationEngine` fuzzy matcher prefilters by first two chars — loosen prefix heuristic (B38)
- `CategorizationEngine.getCategoryIdByName()` reads outside snapshot — use snapshot's name-to-id map (B38)
- `WarrantyTrackerScreen` expired warranties never show expired badge — render when `isExpired || isExpiringSoon` (B18)
- `ReviewScreen` `showTrustSignal` never toggled — add expand/collapse or remove (B18-missed)
- `WarrantyTrackerViewModel` auto-detected filter chip can't be toggled off — make chip toggle its own boolean (B18)
- `CurrencyManagementScreen` conversion dialog leaves Convert enabled for invalid amounts — disable or show validation error (B18)
- `SubscriptionManagementViewModel` no-spend status loaded once in `init` — observe reactively (B19)
- `CarbonFootprintScreen` negative `parisAgreementGap` passed to formatter — use `abs(gap)` (B19)
- `CarbonFootprintViewModel` collapses exceptions into `report = null` — add explicit error state (B19-missed)
- `Loading states` ignore scaffold padding in multiple screens — apply padding (B19)
- `Hardcoded English copy` in multiple screens — extract to string resources (B19)
- `NoSpendStreakCard` hardcodes `Locale.GERMANY` — use `Locale.getDefault()` (B19)
- `Starter prompt chips` display localized labels but inject hardcoded English queries — back with localized query strings (B19-missed)
- `Active challenges` branch renders placeholder text — replace with real challenge card (B19-missed)
- `balance == 0.0` exact float equality — compare with tolerance (B18)
- `AddExpenseViewModel.reset()` doesn't cancel debounced search job — cancel in reset (B17)
- `AddExpenseSheet` prefill keyed with `LaunchedEffect(Unit)` — key to `initialAmount`/`initialMerchant` (B17)
- `ReceiptScanScreen` uses `collectAsState()` — use `collectAsStateWithLifecycle()` (B19)
- `Camera permission denial copy` hardcoded English — move to strings.xml (B19)
- `Retry button` hardcoded "Retry" string — extract to resource (B19)
- `Currency.getInstance(currencyCode)` unguarded — wrap in `runCatching` (B19)
- `Percentage/amount fields` coerce input through `Double.toString()` — store editable text separately (B19)
- `LifestyleInflationViewModel` exceptions swallowed into `report = null` — add explicit error state (B19)
- `SavingsPromptCard` hardcoded English copy — move to resources (B19)
- `ReviewPriorityFactors.fromReview()` uses `System.currentTimeMillis()` — pass `now` (B34)
- `ReviewPriorityFactors.calculateTimeSensitivity` reads `System.currentTimeMillis()` — inject clock (B24)
- `CaptureAssistInput.amount` accepts `NaN`/`Infinity`/zero/negative — require finite positive (B34)
- `ReviewExplanationInputBuilder` imports `data.ai.provider.internal.sha256Prefix` — move hashing to domain/common (B36)
- `DashboardBriefingInputBuilder.SimpleDateFormat` shared mutable state — use `DateTimeFormatter` (B35)
- `RecurrenceFrequency.IRREGULAR.intervalInMs` returns `0L` — make nullable or model separately (B24)
- `MonteCarloBudgetImpact.formatCurrency` hardcodes euro symbol — use `NumberFormat` with explicit `Currency` (B24)
- `CategoryBreakdown`/`DashboardCategoryBreakdown` duplicated across packages — consolidate (B24)
- `PeriodRange` duplicated across `domain.model` and `domain.analytics` — rename one or add conversion layer (B46)
- `SavingsGoal` domain and entity definitions differ — keep Room entities internal to data layer (B46)
- `NarrativeGenerator` imports app `R`, constructs formatted strings in domain — emit `UiText`/message keys (B46-missed)
- `FinancialForecast.generatedAt` uses `Instant.now()` instead of `TimeProvider` — use `Instant.ofEpochMilli(timeProvider.now())` (B46-missed)
- `CalculateFinancialForecastUseCase` fabricated `SpendingPace` with `projectedTotal = monthSpent`, fixed `ON_PACE` — reuse real pace path (B48)
- `CalculateFinancialForecastUseCase` passes `pastSumDaily = emptyList()` — build cumulative daily spend (B48)
- `DashboardContractsAdapter.observeDashboardExpenses()` snapshots month once — drive from `TimeProvider` (B48)
- `DashboardDataProvider` flows silently replace failures with empty/default — log or surface error state (B48)
- `GroupsModule` unused imports — remove (B22)
- `EmailIngestionModule` provides parser singletons but `EmailReceiptIngestionService` manually constructs them — inject or remove bindings (B22-missed)
- `ExportOptionsViewModel` constructs exporters directly instead of using Hilt-provided instances — inject (B22-missed)
- `LifecycleObserver.onStop()` cancels `TransactionClassifier` singleton scope — don't cancel on every `onStop` (B22)
- `BudgetMonitor.cleanup()` cancels `serviceJob` on every `onStop()` — keep scope alive or recreate on resume (B22)
- `SavingsModule` engines depend on `data.repository.SavingsGoalRepository` instead of domain abstraction — change to domain interface (B22)
- `AiSettingsRepositoryImpl.settings()` lacks `IOException` recovery — add `catch` (B06, B34)
- `DefaultAiCapabilityRouter` disabled-route reasons interpolate raw enum names — use `displayName()` (B06)
- `GetAiRuntimeStatusUseCase.highestPriorityMessage` is first-match not severity-ranked — rank by severity or rename (B06)
- `OnDeviceDedupeJudgeService` raw `Enum.valueOf()` calls — use case-insensitive safe lookup (B09)
- `HybridReceiptAssistService.lastUsedImageInput` mutable singleton state — remove shared mutable state (B10)
- `CloudPiiSanitizer.PHONE_REGEX` broad enough to redact non-phone numeric text — tighten matching (B10)
- `CloudJsonParser.extractFirstJsonObject()` returns first brace-balanced block, not first valid JSON — validate each candidate (B10)
- `CloudCorrelation` keeps only 8 chars of UUID — use full UUID or longer token (B10)
- `ExpenseGroupDao.insertGroupWithMembers()` unused in production — remove or move to coordinator (B15)
- `ExpenseGroupDao` `groupId <= 0` guard unreachable — remove (B15)
- `ExpenseGroupDao` `memberIds.any { it <= 0 }` guard unreachable — remove (B15)
- `ManualRecurringExpenseDao.insert()` uses `REPLACE` — use `ABORT` for create-only (B15)
- `MerchantNormalizationDao.linkAliasToCanonical()` read-then-insert — use atomic upsert (B15)
- `ExpenseDao.getChanges()` exposes SQLite `changes()` as standalone helper — remove or wrap (B14)
- `ScannedReceiptDao.linkToExpense()` updates only `expenseId` — atomically set matched status (B14)
- `ReturnWindowDao` returns single row without 1:1 enforcement — enforce uniqueness (B14)

- `SpendingThresholdCalculator` percentile query uses raw `amount` not `effectiveAmount` — shared expenses inflate threshold, anomaly detection less sensitive (B36-missed)
- `AnomalyAlertOrchestrator` broad `catch` swallows `CancellationException` — re-throw before generic catch (B36-missed)
- `AdvancedAnalyticsDashboard.getMonthlyTrend()` builds `23:59:59` month end passed to end-exclusive repo query — use start-of-next-month exclusive boundary (B36-missed)
- `AdvancedAnalyticsDashboard` monthly trend N+1 pattern — batch into single date-range query or aggregate SQL (B36-missed)
- `AdvancedAnalyticsEngine` current-period sparklines stop before today — extend end boundary to include current day (B36-missed)
- `SpendingPersonalityClassifier` confidence mixes normalized 0..1 features with raw `transactionsPerMonth` — normalize all inputs (B36-missed)
- `DayOfWeekAnalyzer` results sorted by total spend instead of weekday order — sort by day-of-week index (B36-missed)
- `TransferDirectionAnalytics` corrections only update accuracy counters, not incoming/outgoing totals or source/destination lists — rebuild full stats on correction (B36-missed)
- `ExpenseDao` weekly aggregates expose `MIN(date)/MAX(date)` transaction timestamps as week boundaries — use explicit period start/end from calendar (B36-missed)
- `CrossSourceDeduplication` candidate ranking ignores time distance and merchant similarity when multiple candidates pass hard filters — weight by time delta and merchant score (B40-missed)
- `BudgetAutopilotEngine` history fetched through `ExpenseRepository.getExpensesBetween()` — inherits 2000-row cap; add uncapped variant (B37-missed)
- `BudgetForecastingEngine` historical reads call `getExpensesByCategory()`/`getExpensesByTypeBetween()` without overriding default limit — add uncapped variant (B37-missed)
- `BudgetAutopilotEngine` and `BudgetForecastingEngine` use different month bucketing and timezone rules — centralize through shared period calculator (B37-missed)
- `InsightsEngine` composes analytics engines but reimplements their logic inline — delegate to engines to prevent drift (B36-missed)
- `SplitCalculator` converts money to cents with `Int` — amounts above ~€21.47M overflow; use `Long` (B43-missed)
- `ReviewPriorityScorer` batch scoring pre-computes `duplicateRisk` but `calculateBaseScore()` uses placeholder `0.5f` — feed computed risk into single-item scoring path (B34-missed)
- `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` never records impression — record impression before returning so cooldown starts at show time (B33-missed)
- `RecommendationDeduplicator.computeSignature()` omits `ownership` — include ownership in signature (B47-missed)
- `AdvancedAnalyticsEngine` merchant analytics groups by raw `merchant`, re-filters full 6-month history per merchant — aliases fragment results, O(merchants × history) runtime (B01)
- `AdvancedAnalyticsDashboard` `generateDashboardData()` hardcodes `Dispatchers.IO` (B01)
- `CategoryInsightEngine` previous-period expenses re-filtered for every category — O(categories × previous-expenses) (B01)
- `MerchantInsightEngine` merchant grouping by `merchant.lowercase()` not canonical `merchantKey` (B01)
- `TransferDirectionAnalytics.recordUserCorrection()` assumes initial detection was correct — double-decrement on incorrect initial detection (B01)
- `AdvancedAnalyticsEngine.getMerchantAnalytics()` loads history with `getExpensesSince(historicalStart)` and never caps at `period.endMs` — post-period transactions leak (B01)
- `BudgetForecastingEngine.generateForecast()` inserts forecast row but returns pre-insert object — caller always gets `id = 0` (B02)
- `SynthesisEngine` biweekly matching treats any date within ±2 days as bill day — one bill appears on up to 5 days (B04)
- `MonteCarloSpendingSimulator.countRecentQualifyingWeeks()` uses `total > 0` instead of `>= 3` distinct transaction-days (B04)
- `SynthesisEngine` `now` captured once but `Calendar` seeded with second `timeProvider.now()` call — midnight race (B04)
- `SplitCalculator.formatBalance()` hardcodes `$` — non-USD users see wrong currency (B04)
- `ComputeDashboardWidgetsUseCase` when no overall budget, `SafeToSpend.amount` populated with `ctx.monthSpent` (already-spent money) (B05)
- `ComputeMoneyRadarUseCase.compute()` captures `now` but helpers call `timeProvider.now()` again — midnight mixing (B05)
- `ComputeDashboardWidgetsUseCase` zero `averageDailyBurn` + remaining budget → runway days = 0 → CRITICAL (B05)
- `ComputeDashboardWidgetsUseCase` `monthSpent` from `summary.totalSpent` while `todaySpent`/`weekSpent` from `purchases` — different reactive paths → inconsistent (B05)
- `GetAiRuntimeStatusUseCase` capability status checks awaited sequentially — latency grows linearly (B07)
- `SuggestReceiptExtractionUseCase` non-forced path no longer enforces deterministic `needsAssist()` gate (B08)
- `MapFinancialQueryToNavigationUseCase` `QueryMetric.MIN` explicitly rejected — unsupported end-to-end (B08)
- `PrioritizeReviewItemsUseCase` has no production call site — review-priority feature is dead code (B08)
- `CloudReceiptAssistService.buildImageInlineData()` reads full file into memory before checking size (B08)
- `ExecuteFinancialQueryUseCase` `QueryMetric.MIN` advertised but never executed — "smallest/cheapest" falls through to `Unsupported` (B08)

### D.4: Low (Quick Wins)
- `ExpenseTrackerApp` creates own `CoroutineScope` instead of using Hilt-provided `@ApplicationScope` — inject (B22)
- `TransactionClassifier` and `BudgetMonitor` eagerly field-injected into `Application` — use `Lazy`/`Provider` (B22)
- `NotificationIdGenerator` `% RANGE_SIZE` preserves sign for negatives — use `floorMod` (B23)
- `BKTree.size`/`isEmpty` read mutable state outside mutex — guard with mutex (B23)
- `Math.abs(hash) % colors.size` can be negative for `Int.MIN_VALUE` — use `floorMod` (B23)

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
