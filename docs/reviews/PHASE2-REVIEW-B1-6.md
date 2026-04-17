# Phase 2 B.1-6 Pipeline Review

## B.1 AI/ML Pipeline
Status: ✅ RESOLVED

Evidence:
- Commit: `98bff27` (`fix(B.1): AI/ML Pipeline - CRITICAL/HIGH batch completion`)
- Review: `docs/reviews/REVIEW-B1-ai-ml-pipeline.md`

Issues:
- [CRITICAL] Financial query interpretation no longer loses category/period/alias filters end-to-end — RESOLVED
- [CRITICAL] Categorization assist no longer leaks raw merchant history/category labels to cloud when redaction is enabled — RESOLVED
- [HIGH] Smart receipt retry chain now falls through across cloud/on-device route families — RESOLVED
- [HIGH] AI dedupe judge now runs for single-candidate cases and validates model-selected targets against the bounded candidate set — RESOLVED
- [HIGH] Dashboard briefing/review explanation cache freshness now requires matching `sourceHash` — RESOLVED
- [HIGH] `CategorizeReceiptItemsUseCase` restores stuck `ANALYZING` receipts back to `PENDING` on failure/null paths — RESOLVED
- [HIGH] Transaction insight generation now uses a redaction-safe input builder instead of fabricated briefing input/logging raw transaction text — RESOLVED
- [HIGH] `ExecuteFinancialQueryUseCase` no longer depends on capped 500-row queries, preserves multi-value filters, returns exact list counts, and avoids fake mixed-currency EUR math — RESOLVED
- [HIGH] On-device notification parser no longer assigns `transferDirection` to purchases — RESOLVED
- [HIGH] On-device receipt scoring no longer floors every overlap into `0.3..0.7` and block keyword fallback — RESOLVED

Summary:
- All B.1 HIGH/CRITICAL registry items are closed.
- Remaining B.1 MEDIUM/LOW items are out of scope for this review.

## B.2 Budget/Forecasting Pipeline
Status: ❌ NOT RESOLVED

Evidence:
- Commit: `9e53a63` (`fix(B.2): Budget/Forecast Pipeline - CRITICAL/HIGH batch completion`)
- Review: `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md`

Issues:
- [CRITICAL] `BudgetCalculator.calculatePeriodRange()` rolling-window/yearly-calendar defects — RESOLVED
- [CRITICAL] `BudgetForecastingEngine` forecast horizon now uses the active remaining budget period — RESOLVED
- [CRITICAL] `CarbonFootprintCalculator.calculateCarbonFootprint()` one-shot suspend behavior — RESOLVED
- [CRITICAL] `BudgetCalculator.calculatePeriodWindow(period, anchorDate)` historical/next-window semantics — RESOLVED
- [HIGH] Deterministic overspend probability no longer gets reduced by confidence — RESOLVED
- [HIGH] `BudgetAutopilotEngine` no longer double-counts overall + category budgets — RESOLVED
- [HIGH] `SharedBudgetManager` now uses canonical budget windows/whole-wallet semantics — RESOLVED
- [HIGH] `BudgetMonitor` synchronization/cancellation handling — RESOLVED
- [HIGH] `BudgetMonitor.cleanup()` lifecycle kill behavior — RESOLVED
- [HIGH] `FinancialStressForecastEngine` current-balance/income-fallback semantics — RESOLVED
- [HIGH] Daily discretionary sampling now includes zero-spend days — RESOLVED
- [HIGH] `RecurringExpenseEngine` stale `nextExpectedDate` output — RESOLVED
- [HIGH] Hidden truncation inherited from capped DAO calls — RESOLVED (covered by A.9 / downstream audit)
- [HIGH] `CashFlowCalculator` transfer-direction income handling — RESOLVED (covered by current cashflow logic)
- [HIGH] `BusinessExpenseReportGenerator` raw amount / transaction-type / effective-amount drift — RESOLVED (covered by upstream A.1/A.10/B.4 fixes)
- [HIGH] `BudgetRecommendationEngine.potentialSavings` can still go negative — OPEN
- [HIGH] `CalculateFinancialForecastUseCase` still feeds `SynthesisEngine` placeholder inputs (`pastSumDaily = emptyList()`, forced `ON_PACE`, forced `TRACKING`) — OPEN
- [HIGH] `MonthlySavingsSweepUseCase` still hardcodes `knownUpcoming = 0.0` — OPEN
- [HIGH] Goal allocations are still not capped by remaining goal gap before allocation — OPEN
- [HIGH] `observeDashboardExpenses()` month-rollover staleness — RESOLVED
- [HIGH] `ComputeMoneyRadarUseCase.getBudgetRisk()` still includes future-dated purchases because it sums `getExpensesSince(monthStart)` without `expense.date <= now` bound — OPEN
- [HIGH] `CalculateFinancialForecastUseCase` still lacks a rollover trigger and recomputes only when repository flows emit — OPEN
- [HIGH] `TotalsAggregationEngine` still omits zero-spend periods / stable zero buckets — OPEN
- [HIGH] `BudgetCalculator` calendar-year yearly budgets — RESOLVED
- [HIGH] `BudgetAutopilotEngine` zero-month history infill — RESOLVED
- [HIGH] `BudgetAutopilotEngine` sparse-history confidence floor / `MIN_HISTORY_MONTHS` enforcement — RESOLVED
- [HIGH] `ComputeMoneyRadarUseCase` urgency still uses only overrun probability and not overrun magnitude/risk tier — OPEN
- [HIGH] `BudgetRepository.getBudgetStatuses()` yearly-budget truncation — RESOLVED

Summary:
- Batches 1-7 landed correctly and fixed the original budget-window, forecast-horizon, shared-budget, monitor, autopilot, and recurring-date defects.
- The pipeline is still incomplete because multiple B.2 HIGH items from later batches remain live in `CalculateFinancialForecastUseCase`, `MonthlySavingsSweepUseCase`, `ComputeMoneyRadarUseCase`, `TotalsAggregationEngine`, and `BudgetRecommendationEngine`.

## B.3 Receipt/OCR Pipeline
Status: ❌ NOT RESOLVED

Evidence:
- Commits: `ffe1199` (Batch 6A), `b655d94` (Batches 6B/6C)
- Reviews: `docs/reviews/REVIEW-B3-Batch6A.md`, `docs/reviews/REVIEW-B3-Batches6B6C.md`

Issues:
- [CRITICAL] `WarrantyTextExtractor` shared `SimpleDateFormat` thread-safety issue — RESOLVED
- [CRITICAL] `OcrLanguageProcessor.normalizeForLanguage()` destructive Latin-only normalization — RESOLVED
- [HIGH] Cloud receipt assist still uploads raw images when image mode is enabled even if `redactBeforeCloud=true` (`CloudReceiptAssistService.buildImageInlineData()`) — OPEN
- [HIGH] On-device receipt assist still does not attach image input and remains text-only (`GenerateContentRequest.builder(TextPart(prompt))`) — OPEN
- [HIGH] `ReceiptParser` line-item extraction still runs overlapping patterns without dedupe, so quantity-formatted lines can be added twice — OPEN
- [HIGH] `WarrantyTextExtractor.isReasonablePurchaseDate()` still rejects receipts older than one year — OPEN
- [HIGH] `ReceiptTransactionMatcher` still treats any positive-amount transaction as receipt-compatible — OPEN
- [HIGH] `ReceiptTransactionMatcher.normalizeMerchant()` still strips non-ASCII characters and can collapse Greek merchants to empty strings — OPEN
- [HIGH] `BankStatementParser` amount/date-column correctness — RESOLVED
- [HIGH] Revolut grouped-amount parsing correctness — RESOLVED
- [HIGH] Revolut transaction-type classification correctness — RESOLVED
- [HIGH] `OcrLanguageProcessor` locale-specific amount parsing (`25,50 -> 2550`) — RESOLVED
- [HIGH] `ReceiptMatchingWorker` permanent-failure retry-loop issue — OPEN
- [HIGH] `AutoCreateWarrantyFromReceiptUseCase` draft-vs-create conflict on same `receiptId` — OPEN
- [HIGH] `CloudWarrantyExtractionService` return-policy-only receipts -> `null` path — OPEN
- [HIGH] Extracted return-window fields are still ignored during persistence — OPEN
- [HIGH] Warranty extraction still uses fixed 30-day month math vs calendar-month UI behavior — OPEN
- [HIGH] Warranty / return-window rows still lack production expiry reconciliation — OPEN

Summary:
- The landed work correctly fixes the two B.3 CRITICAL issues plus the bank-statement / OCR-amount correctness subset.
- Major receipt-assist privacy/vision, warranty-lifecycle, receipt matching, and parser-deduplication HIGH issues remain open.

## B.4 Database/DAO/Entity Pipeline
Status: ✅ RESOLVED

Evidence:
- Commit: `76651e3` (`fix(B.4): harden database pipeline invariants and persistence contracts`)
- Review: `docs/reviews/REVIEW-B4.md`
- Registry state: `Section B.4` is explicitly marked `[RESOLVED BY B.4]`

Issues:
- All 31 B.4 HIGH registry issues are RESOLVED.
- Verified resolved themes include: budget/group uniqueness and atomicity, merchant normalization/cache invariants, bank credential cleanup on disconnect, non-destructive email receipt ingestion, notification/anomaly FK+d edupe hardening, subscription/forecast uniqueness, numeric CHECK constraints, business-expense correctness, migration/index closeout, CSV importer Room-graph compliance, and final Batch 29 presentation/runtime follow-ups.

Summary:
- No B.4 HIGH/CRITICAL items remain open.
- The late-closeout review and migration evidence are consistent with the registry’s resolved state.

## B.5 Location/Geocoding Pipeline
Status: ✅ RESOLVED

Evidence:
- Commit: `2b3dfa7` (`fix(B.5): Location/Geocoding Pipeline - HIGH batch completion`)
- Review: `docs/reviews/REVIEW-B5.md`

Issues:
- [HIGH] Overpass final `429/5xx` transport semantics — RESOLVED
- [HIGH] Unicode-aware merchant-name ranking for Overpass / Greek names — RESOLVED
- [HIGH] Merchant-key backfill worker no-progress liveness — RESOLVED
- [HIGH] `LocationBackfillWorker` raw-merchant logging leak — RESOLVED
- [HIGH] `LogSanitizer.anonymizeForLog()` weak `hashCode()` redaction — RESOLVED
- [HIGH] `LocationResolver` private weak anonymizer duplication — RESOLVED
- [HIGH] Retryable geocoder failures no longer collapse to terminal unresolved — RESOLVED
- [HIGH] Blocking provider HTTP calls without cancellation propagation — RESOLVED
- [HIGH] GPS-biased/name-only cache writes under global area key — RESOLVED
- [HIGH] `SpendingHeatmapEngine` negative-value log normalization bug — RESOLVED
- [HIGH] Map heatmap input transaction-type blindness — RESOLVED
- [HIGH] Arbitrary area-scoped fallback through global cache lookup — RESOLVED
- [HIGH] Merchant-location global cache fallback duplicate row from older registry entries — RESOLVED
- [HIGH] Merchant-location global-key consistency drift — RESOLVED

Summary:
- All B.5 HIGH registry issues are closed.
- Medium/low location issues remain out of scope, but the high-severity transport/cache/privacy defects were fixed and verified.

## B.6 Notification/Service/Worker Pipeline
Status: ❌ NOT RESOLVED

Evidence:
- Commit: `c6d50be` (`fix(B.6): Notification/Worker Pipeline - HIGH batch completion`)
- Review: `docs/reviews/REVIEW-B6.md`

Issues:
- [HIGH] `NotificationFilter.MONITORED_PACKAGES` unconditional Gmail/Viber/SMS whitelist — RESOLVED
- [HIGH] `NotificationCaptureService` blank-field fallback chain — RESOLVED
- [HIGH] `RecommendationDismissalHandler` persistence/state divergence — RESOLVED
- [HIGH] `RecommendationStateManager.refreshForUser()` same-user refresh short-circuit — RESOLVED
- [HIGH] `DailyBriefingWorker` timeout/retry behavior — RESOLVED
- [HIGH] `NotificationIdGenerator.forWarranty()` overlapping ID bands — RESOLVED
- [HIGH] `RecommendationRepository.saveAll()` active-set cap enforcement — RESOLVED
- [HIGH] Currency-blind duplicate detection across notification/review/statement paths — RESOLVED
- [HIGH] `BillReminderManager` string-based recurrence handling — RESOLVED
- [HIGH] `BankApiIntegration.mapTransactionToExpense()` forced `PURCHASE + abs(amount)` semantics — RESOLVED
- [HIGH] `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` now records prompt impression/cooldown start when prompting — RESOLVED
- [HIGH] `AnomalyAlertOrchestrator` single-flight atomic dedupe — RESOLVED
- [HIGH] `RecommendationStateManager` stale overwrite guard — RESOLVED
- [HIGH] Anomaly notifications still deep-link to `expensetracker://transaction/{id}` while `AndroidManifest.xml`/`MainActivity` do not declare or handle `transaction` host — OPEN
- [HIGH] `NavigationAction.ToAnalytics(period)` / `NavigationAction.ToMap(location)` payloads are still dropped because `HomeScreen` calls parameterless navigation callbacks and `MainActivity` only switches tabs — OPEN
- [HIGH] `DeliverProactiveBriefingNotificationUseCase` delivery truth / confirmation-before-recording — RESOLVED

Summary:
- Most B.6 reliability fixes landed correctly: notification ingress, recommendation persistence, worker timeout/retry, ID-band repair, duplicate-policy correctness, reminder/bank semantics, prompt cooldown, and anomaly single-flight.
- The pipeline is still incomplete because deep-link host support and analytics/map payload preservation remain open HIGH issues.

## Summary
Total resolved: 101/123 issues

What was accomplished:
- Fully closed pipelines: B.1, B.4, B.5.
- Partially closed but still incomplete: B.2, B.3, B.6.
- The strongest completed work is in AI/ML correctness/privacy, database invariants/migrations, and location/geocoding reliability/privacy.
- Remaining blockers are concentrated in forecast/sweep/dashboard synthesis (B.2), receipt-assist/warranty lifecycle (B.3), and notification deep-link/payload handling (B.6).
