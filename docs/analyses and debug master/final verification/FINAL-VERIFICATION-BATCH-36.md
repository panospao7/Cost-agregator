# Final Verification — Batch 36: AI Use Cases — Remaining & Analytics

> **[RESOLVED BY A.1]** The `effectiveAmount` vs `amount` inconsistency has been standardized across the codebase. All related issues in this batch are now resolved.
> **[RESOLVED BY A.3]** The non-deterministic default values issue (System.currentTimeMillis) has been fixed across the codebase.

## Scope
### Batch scope files
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt`
- `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt`
- `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`
- `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsModels.kt`
- `com/yourname/expensetracker/domain/analytics/AnalyticsModels.kt`
- `com/yourname/expensetracker/domain/analytics/AnomalyDetector.kt`
- `com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt`
- `com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt`
- `com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
- `com/yourname/expensetracker/domain/analytics/MerchantInsightEngine.kt`
- `com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt`
- `com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt`
- `com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt`
- `com/yourname/expensetracker/domain/analytics/SpendingThresholdCalculator.kt`
- `com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
- `com/yourname/expensetracker/domain/analytics/TransferDirectionAnalytics.kt`
- `com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt`

### Supporting validation files read during verification
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt`
- `com/yourname/expensetracker/data/database/dao/AnomalyAlertDao.kt`
- `com/yourname/expensetracker/data/database/dao/BudgetDao.kt`
- `com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `com/yourname/expensetracker/data/database/entity/AiArtifactEntity.kt`
- `com/yourname/expensetracker/data/database/entity/AnomalyAlert.kt`
- `com/yourname/expensetracker/data/database/entity/Budget.kt`
- `com/yourname/expensetracker/data/database/entity/Expense.kt`
- `com/yourname/expensetracker/data/repository/BudgetRepository.kt`
- `com/yourname/expensetracker/data/repository/CategoryRepository.kt`
- `com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- `com/yourname/expensetracker/domain/ai/model/AiModels.kt`
- `com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
- `com/yourname/expensetracker/domain/ai/service/AiArtifactRepository.kt`
- `com/yourname/expensetracker/domain/ai/service/AiCapabilityRouter.kt`
- `com/yourname/expensetracker/domain/ai/service/ReceiptAssistService.kt`
- `com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/budget/BudgetCalculator.kt`
- `com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt`
- `com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`
- `com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt:3` | Low | Architecture | The domain use case imports `data.ai.provider.internal.sha256Prefix`, creating a direct domain→data dependency for a trivial utility. | R | CONFIRMED | Move the hashing helper to a domain/common utility package and reference that instead. |
| 2 | `com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt:143-155` | High | Logic | Cached category suggestions are deserialized and returned without re-running `validateCategorySuggestion()`, so renamed/deleted categories can leak stale ids/names until TTL expiry. | R | CONFIRMED | Revalidate cached payloads against current categories before returning success; invalidate stale artifacts. |
| 3 | `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:50-54` | High | Logic | Receipt extraction still hard-fails when OCR text is missing, even though the surrounding pipeline now supports image-aware receipt analysis. Image-only or OCR-failed receipts cannot use the new vision path. | R | CONFIRMED | Allow execution when a usable image is present and the selected route can analyze images; require OCR only when no image path is usable. |
| 4 | `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:69-87` | Medium | Logic | The `force` flag is ignored on cache hits. The code returns a ready artifact immediately instead of honoring the caller’s request for a fresh run. | R | DOWNGRADED | Guard the cache fast path with `!force`, matching the fallback use case behavior. |
| 5 | `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:155-166` | Medium | Error handling | The broad `catch (e: Exception)` also swallows `CancellationException`, converting coroutine cancellation into a FAILED artifact and an error result. | R | CONFIRMED | Add `catch (e: CancellationException) { throw e }` before the generic catch. |
| 6 | `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt:88-110,119-157,223-231,270-278` | High | Incorrect formula | Dashboard totals, category breakdowns, merchant totals, weekly patterns, and weekend/weekday insights use raw `amount` instead of `effectiveAmount`, so shared expenses are overstated throughout the dashboard. | R | CONFIRMED | Replace spending math in this class with `effectiveAmount` and centralize transaction-type handling. |
| 7 | `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt:95-98` | High | Business logic | Incoming `TRANSFER` transactions are counted as income, inflating cashflow for internal account moves and making this dashboard disagree with the rest of the analytics stack, which treats transfers separately. | R | CONFIRMED | Exclude transfers from income/spend totals or model them in a separate transfer-only section. |
| 8 | `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt:133` | High | Incorrect output | Top categories are rendered as placeholder labels like `Category 5` instead of actual category names. | B | CONFIRMED | Join category metadata before building `DashboardCategoryBreakdown`, or use a repository query that already returns category display data. |
| 9 | `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt:179-184` | High | Boundary | `getMonthlyTrend()` builds a `23:59:59` month end and passes it to an end-exclusive repository query. That drops the last second/fraction of the month, and the reused `Calendar` can leak stale milliseconds into both bounds. | R | CONFIRMED | Use the start of the next month as the exclusive end boundary (`TimePeriodUtils.getEndOfMonth()` / `getMonthRange()`). **[RESOLVED BY A.5]** |
| 10 | `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt:175-184` | Medium | Performance | Monthly trend generation performs one repository query per month, creating an avoidable N+1 pattern over longer ranges. | B | CONFIRMED | Reuse already-loaded expenses or fetch grouped monthly aggregates in a single repository/DAO query. |
| 11 | `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt:148-149,162,184-189` | High | Logic | Budget context blindly `associateBy { categoryId }` over all active budgets and compares the analyzed period’s spend against raw `budget.amount`, ignoring `Budget.period`/`periodMode` and collapsing duplicate category budgets arbitrarily. | R | CONFIRMED | Match budgets to the analyzed period, normalize allowance to the `PeriodRange`, and handle duplicate category budgets explicitly. |
| 12 | `com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt:618-644` | Medium | Logic | Current-period sparklines stop before today. On the first day of a period they can even render empty despite spend already existing today. | R | CONFIRMED | Include today’s bucket in the displayed cumulative range (`daysPassed + 1` / calendar-day based logic). |
| 13 | `com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:53-77` | Medium | Error handling | Each async branch catches all exceptions and silently returns `null`/empty data, with no logging and no `CancellationException` passthrough. Partial analytics failures become invisible and structured concurrency is weakened. | R | CONFIRMED | Log failures, rethrow cancellation, and surface an explicit degraded-state/result instead of silently nulling data. |
| 14 | `com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:368-389` | High | Logic | Merchant insights expose `ms.merchantName`, which is the canonical `merchantKey`, not the display label. UI consumers receive normalized keys instead of readable merchant names. | R | CONFIRMED | Use `ms.displayName` for presentation and keep `merchantName` only for internal lookups. |
| 15 | `com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:472-496` | High | Logic | Merchant-level anomaly detection uses all-time merchant stats that already include current-month transactions, so large current-period outliers are averaged into their own baseline and can be missed. | R | CONFIRMED | Build the baseline from data strictly before `currentMonth.startMs`, or explicitly exclude current-period rows from the historical stats query. |
| 16 | `com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:577-593` | High | Model misuse | `RecurringExpense.frequency` is populated with estimated monthly occurrences (`30 / intervalDays`) instead of actual occurrence count. Long intervals degrade to `0`, which does not match the model’s meaning. | R | CONFIRMED | Map the real occurrence count from the recurring engine, or rename the field/model to represent estimated monthly frequency. |
| 17 | `com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:25-35,242-398,600-631` | Medium | Architecture | `InsightsEngine` injects specialized calculators/engines (`MonthlyComparisonCalculator`, `CategoryInsightEngine`, `MerchantInsightEngine`, `DayOfWeekAnalyzer`) but reimplements their logic instead of delegating, leaving dead dependencies and already creating drift. | B | DOWNGRADED | Either delegate to the extracted engines or remove the unused injections and keep a single canonical implementation. |
| 18 | `com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt:169-173` | High | Logic | `calculateImpulseRatio()` uses `abs(purchase.date - incomeDate)`, so purchases made *before* payday are counted as post-income impulse spending. | R | CONFIRMED | Only count purchases where `purchase.date >= incomeDate` and the forward difference is within the impulse window. |
| 19 | `com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt:257-278` | High | Logic | Budget adherence compares the entire 3-month analysis window against each raw budget amount without scaling for budget period length or mode. | R | CONFIRMED | Normalize each budget to the analysis window using budget-period calculations before scoring adherence. |
| 20 | `com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt:398-404` | Medium | Logic | Confidence calculation mixes normalized 0..1 features with raw `transactionsPerMonth`, so the feature-variance term is on inconsistent scales and can arbitrarily collapse confidence. | R | CONFIRMED | Normalize every feature to a comparable scale before computing cross-feature variance. |
| 21 | `com/yourname/expensetracker/domain/analytics/SpendingPersonalityClassifier.kt:37-43` | Low | Maintainability | `insightsEngine`, `spendingPaceCalculator`, `anomalyDetector`, and `totalsAggregationEngine` are injected but never used, inflating the object graph for no benefit. | D | DOWNGRADED | Remove unused constructor dependencies or actually delegate to them. |
| 22 | `com/yourname/expensetracker/domain/analytics/SpendingThresholdCalculator.kt:43,82-115,129-133` | Medium | Concurrency | The singleton percentile cache uses an unsynchronized `mutableMapOf` on background threads, so concurrent callers can race on read/write/removal. | B | DOWNGRADED | Protect cache access with a `Mutex` or replace it with `ConcurrentHashMap`. |
| 23 | `com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt:32-44` | Medium | Logic | Results are sorted by total spend instead of weekday order, breaking chronological consumers and diverging from `InsightsEngine.buildDayOfWeekPattern()`. | R | CONFIRMED | Return Monday→Sunday order and let presentation code sort differently if needed. |
| 24 | `com/yourname/expensetracker/domain/analytics/TransferDirectionAnalytics.kt:183-201,101-145` | Medium | Logic | User corrections only change `correctDetections`/accuracy. Incoming/outgoing counters and top source/destination lists remain as originally recorded, so aggregate insights stay wrong after corrections. | R | CONFIRMED | Store enough metadata to reverse/update counters and endpoint tallies when a correction is applied. |
| 25 | `com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt:121-164` | Medium | Concurrency | Alert deduplication is not atomic: two concurrent `checkAndAlert()` calls can both pass `getLastAlertForExpense()` and then both insert/send notifications for the same expense. | R | CONFIRMED | Add a uniqueness guarantee on `expenseId` and make the deduplication decision + insert atomic (transaction/upsert). |
| 26 | `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:60-67` | High | Caching | Receipt-assist `sourceHash` is derived from `ReceiptAssistInput.hashCode()`, but that input includes `currentTimeMs`, so the hash changes on every invocation and cache reuse is effectively disabled. | D | UPGRADED | Exclude volatile fields like `currentTimeMs` from the cache key and use a deterministic content hash of stable input fields. |
| 27 | `com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt:34-45; com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt:16-29; com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt:16-29` | Low | Dead code | These analytics classes still guard `it.date != null`, but `Expense.date` is a non-nullable `Long`, so the checks are unreachable noise. | D | DOWNGRADED | Remove the dead null checks and the related non-null assertions. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt:179-218` | Medium | Error handling | `SuggestCategoryFallbackUseCase` has the same broad `catch (e: Exception)` pattern as receipt extraction and also swallows `CancellationException`, converting cancellation into a FAILED artifact/error result. | Add `catch (e: CancellationException) { throw e }` before the generic catch. |
| 2 | `com/yourname/expensetracker/domain/analytics/SpendingThresholdCalculator.kt:92-104` | Medium | Incorrect formula | Personalized high-amount thresholds are computed from `ExpenseDao.getAmountsForPercentileCalc()`, which returns raw `amount` instead of `effectiveAmount`. Shared expenses therefore inflate the threshold and make anomaly/high-amount detection less sensitive. | Change the percentile query to use the same effective-amount CASE expression used elsewhere in analytics. |
| 3 | `com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt:69-145` | Medium | Error handling | The top-level `checkAndAlert()` catch also swallows `CancellationException`, so parent coroutine cancellation can be converted into a logged no-op instead of propagating correctly. | Re-throw `CancellationException` before the broad catch. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | D1 | `SuggestCategoryFallbackUseCase.kt:36` | The nullable cache var is unsynchronized, but the only realistic consequence here is redundant reloading of the same derived `Set<Long>`. The report’s “critical race / lost write / TOCTOU” claim does not produce a correctness failure in this implementation. |
| 2 | D3 | `AdvancedAnalyticsEngine.kt:756-757,783-784,888-889` | Using population variance in heuristic scoring methods while another method uses sample variance is not, by itself, a functional bug. These methods are not required to share one statistical estimator. |
| 3 | D4 | `AdvancedAnalyticsDashboard.kt:91-98` | The real defect is the opposite one: incoming transfers are incorrectly counted as income. The report’s claim that outgoing transfers must be counted as spending does not match the rest of the analytics model, which treats transfers separately from spend. |
| 4 | D10 | `SuggestCategoryFallbackUseCase.kt:142` | `CategorizationAssistInput` is a data class composed of stable value types/data classes; its `hashCode()` is content-based. The reported “process-restart instability” is not substantiated here. |
| 5 | D15 | `AnomalyAlertOrchestrator.kt:72-91` | `MonthPeriod.year/month` are never surfaced or stored by the orchestrator; `AnomalyDetector` only uses `startMs/endMs`. There is no user-visible metadata bug from this label mismatch. |
| 6 | D16 | `AdvancedAnalyticsDashboard.kt:282` | This is only a readability/style complaint. The expression is valid and not a functional defect. |
| 7 | D17 | `AdvancedAnalyticsEngine.kt:443` | The code already guards `amounts.size > 1` before dividing by `amounts.size - 1`, so there is no divide-by-zero path. |
| 8 | D18 | `AdvancedAnalyticsDashboard.kt:~100-110` | `AdvancedAnalyticsDashboard` does not contain the claimed `averagePerDay = totalSpent / daysDiff` calculation. |
| 9 | D19 | `TotalsAggregationEngine.kt (general)` | The class does not load all expenses into memory for totals aggregation; it already delegates to SQL aggregate queries (`getMonthlyTotalsForPeriod`, `getWeeklyTotalsForPeriod`, `getDailyTotalsWithDatesForPeriod`, etc.). |
| 10 | D20/D21/D22 | `ReceiptAssistInputBuilder.kt:60`; `ReviewExplanationInputBuilder.kt (general)`; `ReceiptItemCategorizationInputBuilder.kt (general)` | These are style-only comments (magic numbers / `buildString` preference), not actual defects. |
| 11 | D23 | `SyncProactiveBriefingWorkUseCase.kt (general)` | The file does not catch a broad `Exception` at all. |
| 12 | D24/D25 | `AdvancedAnalyticsModels.kt (general)`; `AnalyticsModels.kt (general)` | Lack of percentage-range validation in plain data models is a defensive-coding preference, not a concrete bug in this batch. |
| 13 | D27 | `AnomalyDetector.kt (general)` | The cited “hardcoded z-score threshold” does not exist in this class. The detector already uses named constants for IQR and MAD thresholds. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `ReceiptAssistInputBuilder` → `SuggestReceiptExtractionUseCase` | High | Logic | The builder/input models are image-first, but the top-level extraction use case still rejects receipts without usable OCR text, blocking the new image-aware pipeline at orchestration time. | `domain/ai/usecase/ReceiptAssistInputBuilder.kt`, `domain/ai/usecase/SuggestReceiptExtractionUseCase.kt` | Align entry validation with the new contract: accept either usable OCR or a valid image-supported route. |
| 2 | `ExpenseDao` weekly aggregates → `TotalsAggregationEngine` → `HomeViewModel` | High | Boundary | Weekly rows expose `MIN(date)`/`MAX(date)` transaction timestamps, but the engine forwards them as week boundaries and the UI reuses them as an exclusive drill-down range. The final transaction/day can be omitted. | `data/database/dao/ExpenseDao.kt`, `domain/analytics/TotalsAggregationEngine.kt`, `ui/screens/home/HomeViewModel.kt` | Store canonical week start/end boundaries in the aggregate model, or reconstruct true week bounds before drill-down. |
| 3 | `InsightsEngine` ↔ `MonthlyComparisonCalculator` / `CategoryInsightEngine` / `MerchantInsightEngine` / `DayOfWeekAnalyzer` | High | Architecture | `InsightsEngine` injects extracted analytics engines but reimplements their logic instead of delegating. This has already created drift (merchant naming, day ordering, previous-month handling). | `domain/analytics/InsightsEngine.kt`, `domain/analytics/MonthlyComparisonCalculator.kt`, `domain/analytics/CategoryInsightEngine.kt`, `domain/analytics/MerchantInsightEngine.kt`, `domain/analytics/DayOfWeekAnalyzer.kt` | Make `InsightsEngine` compose the extracted engines instead of duplicating their algorithms. |
| 4 | Merchant analytics across dashboard/advanced/insights paths | High | Logic | Merchant identity handling is inconsistent: some paths group by raw merchant text, some by lowercased raw text, some by canonical `merchantKey`, and one path exposes the key as the display name. Rankings and labels can disagree between screens. | `domain/analytics/AdvancedAnalyticsDashboard.kt`, `domain/analytics/AdvancedAnalyticsEngine.kt`, `domain/analytics/MerchantInsightEngine.kt`, `domain/analytics/InsightsEngine.kt`, `data/database/dao/ExpenseDao.kt` | Standardize grouping on `merchantKey` and carry a separate display label everywhere. |
| 5 | Budget semantics: `BudgetRepository` / `BudgetCalculator` ↔ analytics/personality scoring | High | Logic | Budget period semantics (daily/weekly/monthly/yearly, rolling/calendar) are modeled centrally, but advanced analytics and personality scoring ignore them and compare raw spend against raw budget amounts. | `data/repository/BudgetRepository.kt`, `domain/budget/BudgetCalculator.kt`, `domain/analytics/AdvancedAnalyticsEngine.kt`, `domain/analytics/SpendingPersonalityClassifier.kt` | Reuse the budget-period calculators whenever analytics need budget-aware comparisons. |
| 6 | `InsightsEngine` anomaly pipeline ↔ `AnomalyAlertOrchestrator` | Medium | Logic | Dashboard insights merge merchant-history anomalies with statistical/category-local detection, while real-time alerts use only the statistical detector. The same expense can be flagged in one surface and missed in another. | `domain/analytics/InsightsEngine.kt`, `domain/analytics/AnomalyDetector.kt`, `domain/alerts/AnomalyAlertOrchestrator.kt` | Share a common anomaly-evaluation pipeline or explicitly align alerting with the merged insights detector. |
| 7 | `ExpenseRepository.getExpensesBetween()` → dashboard/advanced analytics | High | Data truncation | These analytics classes call a repository API that silently inherits the DAO’s default `limit = 2000`, so long analysis windows can be truncated without any signal. | `data/repository/ExpenseRepository.kt`, `data/database/dao/ExpenseDao.kt`, `domain/analytics/AdvancedAnalyticsDashboard.kt`, `domain/analytics/AdvancedAnalyticsEngine.kt` | Expose an uncapped/paged aggregate path for analytics, or rename the current API to make the cap explicit. |
| 8 | `ExpenseDao` merchant stats → `InsightsEngine` merchant insights/anomaly baseline | High | Incorrect formula | Merchant totals use `effectiveAmount`, but `averageAmount`/`minAmount`/`maxAmount` in the DAO use raw `amount`. Shared-expense users therefore get inconsistent merchant insights and anomaly baselines. | `data/database/dao/ExpenseDao.kt`, `domain/analytics/InsightsEngine.kt` | Use the effective-amount expression consistently for all merchant aggregate columns. |
| 9 | `ExpenseDao.getAmountsForPercentileCalc()` → `SpendingThresholdCalculator` | Medium | Incorrect formula | The threshold pipeline computes percentiles from raw `amount`, not `effectiveAmount`, so shared-expense users get thresholds that are too high. | `data/database/dao/ExpenseDao.kt`, `domain/analytics/SpendingThresholdCalculator.kt` | Switch the percentile query to the effective-amount expression used across the rest of analytics. |
| 10 | `AdvancedAnalyticsDashboard` ↔ `TransferDirectionAnalytics` | Medium | Logic | Dashboard cashflow folds incoming transfers into income, while transfer analytics keeps transfer direction as a separate channel. The same transfer can therefore be treated inconsistently across analytics surfaces. | `domain/analytics/AdvancedAnalyticsDashboard.kt`, `domain/analytics/TransferDirectionAnalytics.kt` | Centralize transfer semantics and either exclude transfers from cashflow or present them in a dedicated transfer section everywhere. |

## Summary
- Total verified issues: 27
- Confirmed: 27 (Critical: 0, High: 13, Medium: 11, Low: 3)
- False positives: 16
- Missed issues found: 3
- Files affected: 13/22

## Key Patterns
- **Period semantics are drifting across analytics layers.** Budget periods, week/month boundaries, and current-period “today” handling are implemented differently in different engines.
- **`effectiveAmount` is not consistently treated as the financial source of truth.** Raw `amount` still leaks into dashboard math, merchant aggregates, and threshold calculations, overstating spend for shared-expense users.
- **AI orchestration is only partially wired through.** Receipt extraction still preserves the old OCR-only gate, and its current cache key is effectively disabled by a volatile timestamp field.
- **Several async/background paths silently degrade on failure.** Broad exception swallowing appears in analytics and alerting code, often without cancellation passthrough.
- **Canonical identity handling is inconsistent.** Merchant keys vs display labels and transfer semantics differ across analytics components, leading to diverging results between screens.
