# Phase 2 B.1-6 Final Review

Scope: `MASTER-ISSUE-REGISTRY.md` CRITICAL/HIGH items only for B.1-B.6.

## B.1 AI/ML Pipeline
Status: ✅ RESOLVED

- [CRITICAL] Financial query interpretation loses category/period/alias filters end-to-end — RESOLVED — Evidence: commit `98bff27`; confirmed closed in `docs/reviews/PHASE2-REVIEW-B1-6.md` and `docs/reviews/REVIEW-B1-ai-ml-pipeline.md`.
- [CRITICAL] Categorization assist leaks raw merchant history/category labels to cloud when redaction is enabled — RESOLVED — Evidence: commit `98bff27`; confirmed closed in `docs/reviews/PHASE2-REVIEW-B1-6.md` and `docs/reviews/REVIEW-B1-ai-ml-pipeline.md`.
- [HIGH] Smart receipt retry chain commits to one route family upfront and fails to fall through — RESOLVED — Evidence: commit `98bff27`; `SmartReceiptAssistService` / router fix noted in `docs/reviews/REVIEW-B1-ai-ml-pipeline.md`.
- [HIGH] AI dedupe judge skipped when exactly one candidate exists — RESOLVED — Evidence: commit `98bff27`; bounded single-candidate handling recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] Cache reuse ignores `sourceHash` in dashboard briefing/review explanation — RESOLVED — Evidence: commit `98bff27`; closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `CategorizeReceiptItemsUseCase` can leave receipts stuck in `ANALYZING` — RESOLVED — Evidence: commit `98bff27`; closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] Transaction insight generation bypasses redaction policy and logs raw text — RESOLVED — Evidence: commit `98bff27`; closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `ExecuteFinancialQueryUseCase` aggregates from capped 500-row page, drops multi-value filters, hardcodes EUR — RESOLVED — Evidence: commit `98bff27`; closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] On-device notification parser sets `transferDirection` for purchases — RESOLVED — Evidence: commit `98bff27`; closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] On-device receipt scoring floors every overlap to `0.3..0.7` — RESOLVED — Evidence: commit `98bff27`; closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] Routing asymmetry skips viable cloud/on-device fallback paths — RESOLVED — Evidence: commit `98bff27`; router/fallback correction recorded in `docs/reviews/REVIEW-B1-ai-ml-pipeline.md`.
- [HIGH] `InterpretFinancialQueryUseCase` early special-case returns collapse richer queries into TOTAL intents — RESOLVED — Evidence: commit `98bff27`; covered by the end-to-end interpretation fix recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `ExecuteFinancialQueryUseCase` mixes currencies while labeling output as EUR — RESOLVED — Evidence: commit `98bff27`; covered by the `ExecuteFinancialQueryUseCase` closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] Dedupe judge services trust model-emitted target IDs without candidate-set bounds checks — RESOLVED — Evidence: commit `98bff27`; bounded target validation recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `ExecuteFinancialQueryUseCase.executeList()` underreports total matches with capped preview size — RESOLVED — Evidence: commit `98bff27`; exact-count fix recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.

## B.2 Budget/Forecast Pipeline
Status: ✅ RESOLVED

- [CRITICAL] `BudgetCalculator.calculatePeriodRange()` keeps rolling budgets anchored to the original start and uses `+30 days` month math — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 1.
- [CRITICAL] `BudgetForecastingEngine` projects `forecastPeriodDays` instead of the real remaining budget-period duration — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 2.
- [CRITICAL] `CarbonFootprintCalculator.calculateCarbonFootprint()` collects a Room `Flow` inside a one-shot suspend path — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 4 audit lock-in.
- [CRITICAL] `BudgetCalculator.calculatePeriodWindow(period, anchorDate)` ignores `anchorDate` and always uses `timeProvider.now()` — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 1.
- [HIGH] `overspendProbability` is multiplied by `confidence` so deterministic overruns can look safe — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 2.
- [HIGH] `BudgetAutopilotEngine` double-counts overall + category budgets — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 5.
- [HIGH] `SharedBudgetManager` uses month-to-date windows, filters overall budgets incorrectly, and sums raw amounts — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 3.
- [HIGH] `BudgetMonitor` mutable singleton state is unsynchronized and swallows cancellation — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 6.
- [HIGH] `BudgetMonitor.cleanup()` kills the singleton scope on backgrounding — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 6.
- [HIGH] `FinancialStressForecastEngine` treats current-month net cashflow as balance and uses budgets as income fallback — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 7.
- [HIGH] Daily discretionary sampling excludes zero-spend days and biases future simulation upward — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 7.
- [HIGH] `RecurringExpenseEngine` emits stale `nextExpectedDate` values — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 7.
- [HIGH] Hidden truncation in forecasting/autopilot/shared-budget/carbon/cashflow consumers — RESOLVED — Evidence: upstream A.9 closure plus downstream audit recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `CashFlowCalculator` misclassifies transfer-direction income — RESOLVED — Evidence: closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`; no open B.2 item remained after upstream cashflow correction.
- [HIGH] `BusinessExpenseReportGenerator` sums raw `amount`, includes deposits/transfers, and misses effective-amount handling — RESOLVED — Evidence: upstream A.1/A.10/B.4 closeout referenced in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `BudgetRecommendationEngine.potentialSavings` can go negative — RESOLVED — Evidence: commit `339bc4b`; clamp added in `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationEngine.kt`; regression in `BudgetRecommendationEngineTest.kt`.
- [HIGH] `CalculateFinancialForecastUseCase` feeds `SynthesisEngine` placeholder history/pace/goal inputs — RESOLVED — Evidence: commit `339bc4b`; real `pastSumDaily`, `SpendingPace`, and mapped savings goals now built in `CalculateFinancialForecastUseCase.kt`; regressions in `CalculateFinancialForecastUseCaseTest.kt`.
- [HIGH] `MonthlySavingsSweepUseCase` hardcodes `knownUpcoming = 0.0` — RESOLVED — Evidence: commit `339bc4b`; real recurring/planned obligations now computed in `MonthlySavingsSweepUseCase.kt`; regression in `MonthlySavingsSweepUseCaseTest.kt`.
- [HIGH] Goal allocations are not capped by remaining goal gap before allocation — RESOLVED — Evidence: commit `339bc4b`; remaining-gap and concentration caps now enforced in `MonthlySavingsSweepUseCase.kt`; regression in `MonthlySavingsSweepUseCaseTest.kt`.
- [HIGH] `observeDashboardExpenses()` goes stale after month rollover — RESOLVED — Evidence: closure already recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `ComputeMoneyRadarUseCase.getBudgetRisk()` counts future-dated purchases — RESOLVED — Evidence: commit `339bc4b`; `it.date <= now` bound added in `ComputeMoneyRadarUseCase.kt`; regression in `ComputeMoneyRadarUseCaseTest.kt`.
- [HIGH] `CalculateFinancialForecastUseCase` recomputes only when repository flows emit and misses day/month rollover — RESOLVED — Evidence: commit `339bc4b`; rollover-aware recomputation added via `TimeBoundaryTicker.dayBoundaryTicks()` in `CalculateFinancialForecastUseCase.kt`; regression coverage in `CalculateFinancialForecastUseCaseTest.kt`.
- [HIGH] `TotalsAggregationEngine` omits zero-spend periods / stable zero buckets — RESOLVED — Evidence: commit `339bc4b`; zero-fill month/week/day generation present in `TotalsAggregationEngine.kt`; regressions in `TotalsAggregationEngineTest.kt` and `TotalsAggregationEngineValidationTest.kt`.
- [HIGH] `BudgetCalculator` calendar-year yearly budgets fall through to anniversary semantics — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 1.
- [HIGH] `BudgetAutopilotEngine` monthly history drops zero-spend months — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 5.
- [HIGH] `BudgetAutopilotEngine` gives sparse histories unjustifiably high confidence — RESOLVED — Evidence: commit `9e53a63`; `docs/reviews/REVIEW-B2-budget-forecast-pipeline.md` Batch 5.
- [HIGH] `ComputeMoneyRadarUseCase` urgency scoring uses only overrun probability and ignores magnitude/risk tier — RESOLVED — Evidence: commit `339bc4b`; magnitude and risk-tier bonuses now applied in `ComputeMoneyRadarUseCase.kt`; regression in `ComputeMoneyRadarUseCaseTest.kt`.
- [HIGH] `BudgetRepository.getBudgetStatuses()` yearly-budget reads inherit DAO truncation — RESOLVED — Evidence: closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md` after the B.2/A.9 downstream audit.

## B.3 Receipt/OCR Pipeline
Status: ✅ RESOLVED

### Intake / Vision / Parsing

- [CRITICAL] `OcrLanguageProcessor.normalizeForLanguage()` routes Cyrillic/Arabic/CJK through Latin-only normalization — RESOLVED — Evidence: commit `ffe1199`; verified in `docs/reviews/REVIEW-B3-Batch6A.md`.
- [HIGH] Cloud receipt assist uploads raw images when `redactBeforeCloud=true` — RESOLVED — Evidence: commit `339bc4b`; redaction-required image suppression in `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`; regression in `CloudReceiptAssistServiceTest.kt`.
- [HIGH] On-device receipt assist advertises vision but never attaches image input — RESOLVED — Evidence: commit `339bc4b`; `ImagePart` is now attached in `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt`; regression in `OnDeviceReceiptAssistServiceTest.kt`.
- [HIGH] `ReceiptParser` line-item extraction double-adds overlapping quantity-formatted lines — RESOLVED — Evidence: commit `339bc4b`; dedupe logic added in `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt`; regression in `ReceiptParserTest.kt`.
- [HIGH] `ReceiptTransactionMatcher` treats any positive-amount transaction as receipt-compatible — RESOLVED — Evidence: commit `339bc4b`; purchase-only compatibility enforced in `app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt`; regression in `ReceiptTransactionMatcherTest.kt`.
- [HIGH] Merchant normalization strips non-ASCII characters and collapses Greek names — RESOLVED — Evidence: commit `339bc4b`; Unicode-safe normalization now used in `ReceiptTransactionMatcher.kt`; regression in `ReceiptTransactionMatcherTest.kt`.
- [HIGH] `BankStatementParser` amount selection can choose running balance instead of transaction amount — RESOLVED — Evidence: commit `b655d94`; verified in `docs/reviews/REVIEW-B3-Batches6B6C.md` Batch 6B.
- [HIGH] Revolut statement parsing strips currency symbols and breaks grouped amounts — RESOLVED — Evidence: commit `b655d94`; verified in `docs/reviews/REVIEW-B3-Batches6B6C.md` Batches 6B/6C.
- [HIGH] Revolut statement parser misclassifies transfers/top-ups/refunds — RESOLVED — Evidence: commit `b655d94`; verified in `docs/reviews/REVIEW-B3-Batches6B6C.md` Batch 6B.
- [HIGH] `OcrLanguageProcessor` mishandles locale-specific separators (`25,50 -> 2550`) — RESOLVED — Evidence: commit `ffe1199`; verified in `docs/reviews/REVIEW-B3-Batch6A.md`.
- [HIGH] `ReceiptMatchingWorker` retries permanent failures indefinitely — RESOLVED — Evidence: commit `339bc4b`; permanent-failure classification added in `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt`; regression in `ReceiptMatchingWorkerTest.kt`.

### Lifecycle / Warranty

- [CRITICAL] `WarrantyTextExtractor` uses shared `SimpleDateFormat` instances across parallel batch imports — RESOLVED — Evidence: current `WarrantyTextExtractor.kt` uses immutable `DateTimeFormatter`s; prior closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `WarrantyTextExtractor.isReasonablePurchaseDate()` rejects receipts older than one year — RESOLVED — Evidence: commit `339bc4b`; 50-year plausibility window now used in `WarrantyTextExtractor.kt`; regression in `WarrantyTextExtractorTest.kt`.
- [HIGH] `AutoCreateWarrantyFromReceiptUseCase` review confirmation inserts a duplicate warranty for the same `receiptId` — RESOLVED — Evidence: commit `339bc4b`; draft promotion path added in `AutoCreateWarrantyFromReceiptUseCase.kt`; regression in `AutoCreateWarrantyFromReceiptUseCaseTest.kt`.
- [HIGH] `CloudWarrantyExtractionService` drops return-policy-only receipts by returning `null` — RESOLVED — Evidence: commit `339bc4b`; return-policy-only parsing preserved in `CloudWarrantyExtractionService.kt`; regression in `CloudWarrantyExtractionServiceTest.kt`.
- [HIGH] Extracted `returnDays` / `returnConditions` are ignored during persistence — RESOLVED — Evidence: commit `339bc4b`; `WarrantyTrackerRepository.extractReturnWindow()` now persists AI-extracted return metadata; regressions in `WarrantyTrackerRepositoryTest.kt`.
- [HIGH] Warranty extraction uses fixed 30-day month math instead of calendar-month addition — RESOLVED — Evidence: commit `339bc4b`; calendar `plusMonths()` logic now used in `WarrantyTextExtractor.kt`, `AutoCreateWarrantyFromReceiptUseCase.kt`, and `WarrantyTrackerRepository.kt`; regressions in `WarrantyTextExtractorTest.kt` and `WarrantyTrackerRepositoryTest.kt`.
- [HIGH] Warranty/return-window rows never transition to `EXPIRED` in production code — RESOLVED — Evidence: commit `339bc4b`; `WarrantyTrackerRepository.reconcileExpiredItems()` plus `WarrantyExpirationWorker` now reconcile statuses; regressions in `WarrantyTrackerRepositoryTest.kt` and `WarrantyExpirationWorkerTest.kt`.

## B.4 Database/DAO
Status: ✅ RESOLVED

- [HIGH] `Budget` allows multiple active overall/category budgets with nondeterministic `LIMIT 1` reads — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 4]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-4.
- [HIGH] `ManualRecurringExpense.isSubscription` defaults to `true` and misclassifies generic recurring rows — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 4]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-4.
- [HIGH] `GroupMember` permits multiple `isCurrentUser = 1` rows per group — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 3]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-3.
- [HIGH] `GroupExpense.expenseId` is treated as one-to-one but is not unique — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 3]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-3.
- [HIGH] `GroupExpense.paidById` does not enforce same-group membership — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 3]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-3.
- [HIGH] `MerchantCanonical` is keyed by `searchKey` but only `normalizedName` is unique — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 5]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-5.
- [HIGH] `MerchantAlias` resolves by non-unique `normalizedKey` with `LIMIT 1` — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 5]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-5.
- [HIGH] `BankConnectionDao.disconnect()` preserves live credentials after disconnect — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 6]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-6.
- [HIGH] `EmailReceiptDao.insert()` uses destructive `REPLACE` on unique `emailMessageId` — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 6]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-6.
- [HIGH] `RawNotification` unique index is bypassed by nullable dedupe fields — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 6]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-6.
- [HIGH] `AnomalyAlert.expenseId` has no FK and can orphan alerts — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 6]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-6.
- [HIGH] `SubscriptionCandidate` does not enforce one pending candidate per merchant — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 7]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-7.
- [HIGH] `BudgetForecast` allows overlapping active forecasts — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 7]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-7.
- [HIGH] `SavingsGoalDao.updateGoalAmount()` overwrites absolute value and loses concurrent contributions — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 9]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-9.
- [HIGH] `ScannedReceiptDao.linkToExpense()` leaves linked receipts in `UNMATCHED` state — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 9]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-9.
- [HIGH] `ExpenseDao.getBusinessExpensesMissingReceipts()` uses `rawNotificationId IS NULL` as a receipt proxy — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 9]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-9.
- [HIGH] Business expense queries use raw `amount` and miss effective-amount handling — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 9]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-9.
- [HIGH] `ExpenseWithCategory.formattedAmount` uses raw `amount` and omits transaction polarity — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 10]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-10.
- [HIGH] `UserCorrection.originalMerchant` lacks an index for hot lookups — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — late closeout]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-11.
- [HIGH] `SubscriptionCandidateDao` dedupe is a read-then-insert race — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 7]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-7.
- [HIGH] `InvestmentTracker.getInvestmentPerformance()` computes all-time high/low from only 30 days — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — late closeout]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-10 / ISSUE-B4-11.
- [HIGH] `RecurringExpenseRepository.getAll()` includes inactive manual recurring rows — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 4]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-4.
- [HIGH] `GroupTransactionCoordinator` validates state outside a single DB transaction — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 2]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-2.
- [HIGH] `SharedExpenseGroupsViewModel.addExpense()` can orphan a system expense between two writes — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 2]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-2.
- [HIGH] Migration `69→70` plus Android Keystore encryption can prevent DB open — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 1]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-1.
- [HIGH] `BankConnection.defaultCategoryId` has no FK to `categories` — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 6]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-6.
- [HIGH] `MerchantLocation.areaKey` nullability bypasses composite uniqueness — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 5]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-5.
- [HIGH] Sensitive numeric fields lack DB-level `CHECK` constraints across seven entities — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 8]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-8.
- [HIGH] `ExpenseDao.getBusinessExpensesBetween()` does not filter `transactionType = 'PURCHASE'` — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 9]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-9.
- [HIGH] `CategoryRepository.ensureDefaultCategories()` has a concurrent seeding race — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 4]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-4.
- [HIGH] `CsvExpenseImporter` bypasses the singleton Room graph by building fresh DB instances — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — Batch 10]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-10.
- [HIGH] `AnomalyAlertDao.getLastAlertForCategory()` lacks a `(category, alertedAt)` index — RESOLVED — Evidence: registry annotation `[RESOLVED BY B.4 — late closeout]`; `docs/reviews/REVIEW-B4.md` ISSUE-B4-11.

## B.5 Location/Geocoding
Status: ✅ RESOLVED

- [HIGH] `OverpassNearbyService.executeWithRetry()` loses final `429/5xx` semantics — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 2.
- [HIGH] Overpass name ranking strips non-ASCII characters and breaks Greek merchant matching — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 2.
- [HIGH] `MerchantKeyBackfillWorker` swallows per-row failures and can loop forever with no progress — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 3.
- [HIGH] `LocationBackfillWorker` logs raw merchant names on resolver exceptions — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 8.
- [HIGH] `LogSanitizer.anonymizeForLog()` is weak deterministic `hashCode()` redaction — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 4.
- [HIGH] `LocationResolver` keeps a second weak private anonymizer — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 7.
- [HIGH] `LocationResolver.geocode()` collapses retryable failures to terminal `null` — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 7.
- [HIGH] Geocoding providers use blocking `OkHttp.execute()` without cancellation propagation — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 1/2.
- [HIGH] `LocationResolver.saveLocation()` stores GPS-biased/name-only resolutions under the global area key — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 6.
- [HIGH] `SpendingHeatmapEngine` can feed negative totals into `ln(1 + totalSpend)` and produce `NaN` weights — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 9.
- [HIGH] Map heatmap input is transaction-type blind and includes deposits/transfers — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 9 plus `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `LocationResolver` global-cache fallback can return arbitrary area-scoped entries — RESOLVED — Evidence: commit `2b3dfa7`; `docs/reviews/REVIEW-B5.md` Batch 5.
- [HIGH] Merchant-location global-cache fallback older registry duplicate still returns arbitrary branch rows — RESOLVED — Evidence: commit `2b3dfa7`; strict global-only lookup documented in `docs/reviews/REVIEW-B5.md` Batch 5.
- [HIGH] Merchant-location global-key encoding drifts between `global` and `normalized|global` — RESOLVED — Evidence: commit `2b3dfa7`; consistency closeout documented in `docs/reviews/REVIEW-B5.md` and `docs/reviews/PHASE2-REVIEW-B1-6.md`.

## B.6 Notification/Worker
Status: ✅ RESOLVED

- [HIGH] `NotificationFilter.MONITORED_PACKAGES` unconditionally whitelists Gmail/Viber/SMS apps — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 1.
- [HIGH] `NotificationCaptureService` blanks fields with `orEmpty()` before fallback evaluation — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 1.
- [HIGH] `RecommendationDismissalHandler` removes state before persistence and can resurrect active recommendations — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 2.
- [HIGH] `RecommendationStateManager.refreshForUser()` skips same-user refreshes unless forced — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 2.
- [HIGH] `DailyBriefingWorker` lacks timeout/retry semantics and converts failures to success — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 5.
- [HIGH] `NotificationIdGenerator.forWarranty()` overlaps 30-day warranty IDs with receipt IDs — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 6.
- [HIGH] `RecommendationRepository.saveAll()` limits only the incoming batch and not the total active set — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 3.
- [HIGH] Duplicate detection is currency-blind across notification/review/statement paths — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batches 7/8.
- [HIGH] `BillReminderManager` string-matches recurrence enum names and misses real variants — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 9.
- [HIGH] `BankApiIntegration.mapTransactionToExpense()` forces every movement to `PURCHASE + abs(amount)` — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 9.
- [HIGH] `LifestyleSavingsPromptUseCase.evaluateAndPrompt()` never records prompt impression/cooldown start — RESOLVED — Evidence: commit `c6d50be`; closure recorded in `docs/reviews/PHASE2-REVIEW-B1-6.md`.
- [HIGH] `AnomalyAlertOrchestrator` alert dedupe is not atomic — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 11.
- [HIGH] `RecommendationStateManager.refreshForUser()` allows slower stale refreshes to overwrite newer state — RESOLVED — Evidence: commit `c6d50be`; generation-based stale publish guard documented in `docs/reviews/REVIEW-B6.md` Batch 2.
- [HIGH] Anomaly notifications deep-link to unsupported host `expensetracker://transaction/{id}` — RESOLVED — Evidence: commit `339bc4b`; supported `expensetracker://activity?expenseId=...` deep link now emitted in `AndroidNotificationService.kt`, declared in `AndroidManifest.xml`, and handled in `MainActivity.kt`; regression in `MainActivityDeepLinkTest.kt`.
- [HIGH] `NavigationAction.ToAnalytics(period)` / `ToMap(location)` payloads are dropped at `HomeScreen` / `MainActivity` — RESOLVED — Evidence: commit `339bc4b`; payload plumbing now preserved through `HomeScreen.kt`, `MainActivity.kt`, and `NavigationDestination.kt`; regression in `MainActivityDeepLinkTest.kt` and source assertions in `HomeScreenWidgetTest.kt`.
- [HIGH] `DeliverProactiveBriefingNotificationUseCase` records briefings as delivered before confirming notification delivery — RESOLVED — Evidence: commit `c6d50be`; `docs/reviews/REVIEW-B6.md` Batch 4.

## Summary
Total: 123/123 CRITICAL/HIGH issues RESOLVED

- Previously OPEN in `docs/reviews/PHASE2-REVIEW-B1-6.md`: 22/22 now RESOLVED.
- `339bc4b` closed the remaining open B.2 forecast/sweep/radar items, B.3 intake + warranty-lifecycle items, and B.6 deep-link/payload items.
- Pipelines fully closed: B.1, B.2, B.3, B.4, B.5, B.6.
- Pipelines still open: none.
