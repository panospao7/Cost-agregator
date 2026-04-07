# Deep Analysis — Batch 36: AI Use Cases — Remaining & Analytics (@reviewer)

## Scope
- domain/ai/usecase/ReceiptAssistInputBuilder.kt
- domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt
- domain/ai/usecase/ReviewExplanationInputBuilder.kt
- domain/ai/usecase/SuggestCategoryFallbackUseCase.kt
- domain/ai/usecase/SuggestReceiptExtractionUseCase.kt
- domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt
- domain/analytics/AdvancedAnalyticsDashboard.kt
- domain/analytics/AdvancedAnalyticsEngine.kt
- domain/analytics/AdvancedAnalyticsModels.kt
- domain/analytics/AnalyticsModels.kt
- domain/analytics/AnomalyDetector.kt
- domain/analytics/CategoryInsightEngine.kt
- domain/analytics/DayOfWeekAnalyzer.kt
- domain/analytics/InsightsEngine.kt
- domain/analytics/MerchantInsightEngine.kt
- domain/analytics/MonthlyComparisonCalculator.kt
- domain/analytics/SpendingPaceCalculator.kt
- domain/analytics/SpendingPersonalityClassifier.kt
- domain/analytics/SpendingThresholdCalculator.kt
- domain/analytics/TotalsAggregationEngine.kt
- domain/analytics/TransferDirectionAnalytics.kt
- domain/alerts/AnomalyAlertOrchestrator.kt

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | domain/ai/usecase/ReviewExplanationInputBuilder.kt:3 | LOW | Architecture | The domain use case imports `data.ai.provider.internal.sha256Prefix`, creating a domain→data layer dependency for a trivial utility. | Move the hashing helper into a domain/common utility package and depend on that instead. |
| 2 | domain/ai/usecase/SuggestCategoryFallbackUseCase.kt:143-155 | HIGH | Logic | Cached category suggestions are returned directly from artifact payloads without re-running `validateCategorySuggestion()`. If categories are renamed/deleted before TTL expiry, the use case can return stale or unsupported category ids/names from cache. | Revalidate cached payloads against current categories before returning success; invalidate cache entries that no longer map cleanly. |
| 3 | domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:50-54 | HIGH | Logic | Receipt assist still hard-fails when OCR text is missing/failed, even though this batch introduced image-aware receipt extraction. Image-only receipts and OCR-failed scans cannot use the new vision path. | Allow execution when a valid image is available and the selected route supports image analysis; only require OCR when no image path is usable. |
| 4 | domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:69-87 | HIGH | Logic | The `force` flag is ignored for cache hits. Even with `force = true`, a ready unexpired artifact is returned immediately, so the caller cannot trigger a real re-run. | Gate the cache-hit fast path with `!force`, matching the fallback use case behavior. |
| 5 | domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:155-166 | MEDIUM | Error handling | The broad `catch (e: Exception)` also swallows `CancellationException`, converting coroutine cancellation into a FAILED artifact and an error result. | Add an explicit `catch (e: CancellationException) { throw e }` before the generic catch. |
| 6 | domain/analytics/AdvancedAnalyticsDashboard.kt:88-110,119-157,223-231,270-278 | HIGH | Incorrect formula | Dashboard totals, category breakdowns, merchant totals, weekly patterns, and weekend/weekday insights all use raw `amount` instead of `effectiveAmount`, so shared expenses are overstated throughout the dashboard. | Replace all spending math in this class with `effectiveAmount` and centralize transaction-type handling. |
| 7 | domain/analytics/AdvancedAnalyticsDashboard.kt:95-98 | HIGH | Business logic | Incoming `TRANSFER` transactions are counted as income, which inflates cashflow for internal account moves and diverges from the purchase/deposit-only rules used elsewhere in analytics. | Exclude transfers from income/spend totals or model them in a separate transfer-only section. |
| 8 | domain/analytics/AdvancedAnalyticsDashboard.kt:133 | HIGH | Logic | Top categories are labeled as placeholder strings like `Category 5` instead of real category names, so the dashboard can show incorrect user-facing labels. | Join/load actual category metadata before building `DashboardCategoryBreakdown`. |
| 9 | domain/analytics/AdvancedAnalyticsDashboard.kt:179-184 | HIGH | Boundary | `getMonthlyTrend()` builds month end as `23:59:59` and passes it to an end-exclusive repository query. That drops transactions from the last second of the month, and the reused `Calendar` can also leak stale milliseconds into the boundary. | Use the start of the next month as the exclusive end boundary (or `TimePeriodUtils.getEndOfMonth()`). |
| 10 | domain/analytics/AdvancedAnalyticsDashboard.kt:175-184 | MEDIUM | Performance | Monthly trend generation performs a repository query per month, producing an avoidable N+1 pattern for longer date ranges. | Reuse the already-loaded expense list or query monthly aggregates once from the repository/DAO. |
| 11 | domain/analytics/AdvancedAnalyticsEngine.kt:148-149,162,184-189 | HIGH | Logic | Budget context blindly `associateBy { categoryId }` over all active budgets and compares period spending against raw `budget.amount`, ignoring `Budget.period`/`periodMode` and collapsing multiple budgets for the same category into an arbitrary single row. Utilization/status can be wrong for weekly/yearly budgets and multi-budget setups. | Select budgets that actually match the analyzed period and normalize budget allowance to the `PeriodRange` before computing utilization/status. |
| 12 | domain/analytics/AdvancedAnalyticsEngine.kt:618-644 | MEDIUM | Logic | Current-period sparklines stop before today. On the first day of a period they can even render empty despite spending existing today, because `daysPassed` excludes the current bucket. | Include today's bucket in the cumulative range (`daysPassed + 1` / calendar-day based calculation). |
| 13 | domain/analytics/InsightsEngine.kt:53-77 | MEDIUM | Error handling | Each async branch catches all exceptions and silently returns `null`/empty data, with no logging and no `CancellationException` passthrough. Partial analytics failures become invisible and structured concurrency is weakened. | Log failures, rethrow cancellation, and surface an explicit degraded-state/result instead of silently nulling data. |
| 14 | domain/analytics/InsightsEngine.kt:388-389 | HIGH | Logic | Merchant insights expose `ms.merchantName`, which is the canonical `merchantKey`, not the display label. UI consumers can receive normalized keys instead of readable merchant names. | Use `ms.displayName` for output and keep `merchantName`/key only for internal lookups. |
| 15 | domain/analytics/InsightsEngine.kt:472-496 | HIGH | Logic | Merchant-level anomaly detection uses `getMerchantStats()` as its “historical” baseline, but that baseline includes the current month’s transactions. Large current-period outliers are therefore averaged into their own reference set and can be missed. | Build the baseline from data strictly before `currentMonth.startMs`, or explicitly exclude current-period rows from the historical stats query. |
| 16 | domain/analytics/InsightsEngine.kt:577-593 | HIGH | Model misuse | `RecurringExpense.frequency` is populated with estimated monthly occurrences (`30 / intervalDays`) instead of actual occurrence count. Long intervals degrade to `0` (for example annual patterns), which is semantically wrong. | Map the actual recurrence occurrence count from the recurring engine or rename the field/model to reflect estimated monthly frequency. |
| 17 | domain/analytics/SpendingPersonalityClassifier.kt:169-173 | HIGH | Logic | `calculateImpulseRatio()` uses `abs(purchase.date - incomeDate)`, so purchases made *before* payday are counted as “post-income” impulse spending. | Only count purchases where `purchase.date >= incomeDate` and the forward difference is within the impulse window. |
| 18 | domain/analytics/SpendingPersonalityClassifier.kt:257-278 | HIGH | Logic | Budget adherence compares the entire 3-month analysis spend against each raw budget amount without scaling for budget period length. Monthly budgets look overrun, yearly budgets look too lenient, and rolling/calendar semantics are ignored. | Normalize each budget to the analysis window using `BudgetCalculator`/period ranges before computing adherence. |
| 19 | domain/analytics/SpendingPersonalityClassifier.kt:398-404 | MEDIUM | Logic | Confidence calculation mixes 0-1 features with raw `transactionsPerMonth`, so the feature-variance term is not on a consistent scale and can arbitrarily collapse confidence. | Normalize every feature to a comparable 0-1 range before using cross-feature variance in confidence math. |
| 20 | domain/analytics/SpendingThresholdCalculator.kt:43,82-115,129-133 | MEDIUM | Concurrency | The singleton percentile cache uses plain `mutableMapOf` with unsynchronized reads/writes on background threads, so concurrent callers can race. | Protect cache access with a `Mutex` or replace it with `ConcurrentHashMap`. |
| 21 | domain/analytics/DayOfWeekAnalyzer.kt:32-44 | MEDIUM | Logic | Results are sorted by total spend instead of weekday order, which breaks chronological consumers and diverges from `InsightsEngine.buildDayOfWeekPattern()` ordering. | Return Monday→Sunday order and let presentation code sort differently if needed. |
| 22 | domain/analytics/TransferDirectionAnalytics.kt:183-201,101-145 | MEDIUM | Logic | User corrections only change `correctDetections`/accuracy. Incoming/outgoing counters and top source/destination lists remain as originally recorded, so the aggregate insights stay wrong after corrections. | Store enough detection metadata to reverse/update direction counters and endpoint tallies when a correction is applied. |
| 23 | domain/alerts/AnomalyAlertOrchestrator.kt:159-164,121-140 | MEDIUM | Concurrency | Alert deduplication is not atomic: two concurrent `checkAndAlert()` calls can both pass `getLastAlertForExpense()` and then both insert/send notifications for the same expense. | Add a uniqueness guarantee on `expenseId` and wrap the alert decision + insert in a transaction (or use an atomic upsert). |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | ReceiptAssistInputBuilder → SuggestReceiptExtractionUseCase | HIGH | Logic | The builder/input models are image-first, but the use case still rejects receipts without usable OCR text. The new image-aware pipeline is therefore blocked at the orchestration layer. | Align entry validation with the new input contract: accept either usable OCR or a valid image-supported route. |
| 2 | ExpenseDao weekly aggregates → TotalsAggregationEngine → HomeViewModel drill-down | HIGH | Logic | Weekly rows expose `MIN(date)`/`MAX(date)` transaction timestamps, but the engine forwards them as period boundaries and the UI uses them as an exclusive range for day drill-down. Boundary-day transactions can be omitted. | Store real week start/end boundaries in the aggregate model, or reconstruct canonical week bounds before drill-down. |
| 3 | InsightsEngine ↔ MonthlyComparisonCalculator / CategoryInsightEngine / MerchantInsightEngine / DayOfWeekAnalyzer | HIGH | Architecture | `InsightsEngine` injects extracted calculators/engines but reimplements their logic instead of delegating. This has already created drift (merchant naming, day ordering, previous-month handling). | Make `InsightsEngine` compose the extracted engines instead of duplicating their algorithms. |
| 4 | Merchant analytics pipeline across AdvancedAnalyticsDashboard / AdvancedAnalyticsEngine / MerchantInsightEngine / InsightsEngine | HIGH | Logic | Merchant identity handling is inconsistent: some paths group by raw merchant, some by lowercased raw merchant, some by `merchantKey`, and one returns `displayName` incorrectly. Merchant totals and rankings can disagree between screens. | Standardize merchant grouping on `merchantKey` and carry a separate display label everywhere. |
| 5 | BudgetRepository/BudgetCalculator ↔ AdvancedAnalyticsEngine / SpendingPersonalityClassifier | HIGH | Logic | Budget period semantics (daily/weekly/monthly/yearly, rolling/calendar, rollover) are modeled centrally in budget code, but advanced analytics and personality scoring ignore them and compare raw spend to raw budget amounts. | Reuse the budget period calculators when analytics need budget-aware comparisons. |
| 6 | InsightsEngine anomaly pipeline ↔ AnomalyAlertOrchestrator | MEDIUM | Logic | Dashboard insights merge merchant-based and statistical anomaly detection, while real-time alerts use only the statistical/category-local detector. The same expense can be flagged in one surface and missed in another. | Share a common anomaly evaluation pipeline or make alerting explicitly call the same merged detector used by insights. |

## Summary
- Total issues: 23
- Critical: 0, High: 13, Medium: 9, Low: 1
- Files with issues: 11/22

## Key Patterns
- **Period semantics are drifting across analytics layers.** Budget periods, week boundaries, and current-period handling are implemented differently in different engines, causing inconsistent outputs.
- **Merchant identity handling is inconsistent.** Some code uses raw merchant text, some lowercased text, some canonical keys, and one path exposes keys as display names.
- **Several orchestration layers silently degrade on failure.** Generic exception swallowing appears in async analytics/AI paths, making production misbehavior hard to detect and sometimes breaking cancellation semantics.
- **New image-aware receipt functionality is only partially wired through.** Input builders and providers support image-first assist, but the top-level receipt extraction use case still preserves the old OCR-only gate.
