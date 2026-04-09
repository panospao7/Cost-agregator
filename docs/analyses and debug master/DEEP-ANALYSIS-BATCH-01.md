# Deep Analysis — Batch 01: Analytics Engines

## Scope
- domain/analytics/AdvancedAnalyticsEngine.kt
- domain/analytics/AdvancedAnalyticsDashboard.kt
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
- domain/analytics/AnalyticsModels.kt
- domain/analytics/AdvancedAnalyticsModels.kt
- domain/analytics/SpendingPaceModels.kt (not found in codebase)

## @reviewer Findings

### Per-File Issues
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | domain/analytics/AdvancedAnalyticsEngine.kt:250-271 | MEDIUM | Architecture | Merchant analytics groups by raw `merchant` text and then rescans the full 6-month history for every merchant. That creates fragmented merchant buckets versus `merchantKey`-based analytics elsewhere and turns the historical pass into O(current merchants × history). | Pre-group historical/current expenses by canonical `merchantKey`, keep a display-name alongside it, and reuse the grouped map instead of filtering `historicalExpenses` inside each merchant loop. |
| 2 | domain/analytics/AdvancedAnalyticsEngine.kt:468-508 | HIGH | Bug | `averageDailySpend`, `daysWithSpending`, and `daysWithoutSpending` are computed against the full selected period, even when the period is still in progress. For current week/month views this counts future days as zero-spend days and materially understates daily averages. | When `timeProvider.now()` falls inside the period, cap the analysis end at `now` (or end-of-today) before computing elapsed days and zero-spend counts. |
| 3 | domain/analytics/AdvancedAnalyticsEngine.kt:611-645 | MEDIUM | Bug | Sparkline generation uses millisecond division by `DAY_IN_MILLIS` and `daysPassed` excludes the current partial day. On day 1 the sparkline can be empty, and DST transitions can shift transactions into the wrong day bucket. | Use calendar-day differences via `TimePeriodUtils.daysBetween(...)` and include the current day with `elapsedDays + 1` clamped to the period length. |
| 4 | domain/analytics/AdvancedAnalyticsDashboard.kt:91-110,118-147,223-301 | HIGH | Bug | Dashboard totals, category totals, merchant totals, weekly pattern, and insights all use raw `expense.amount` instead of `effectiveAmount`. Shared-expense and split-expense scenarios are therefore overstated across the whole dashboard. | Replace every dashboard aggregation with `effectiveAmount` and keep transfer/income handling consistent with repository/domain analytics rules. |
| 5a | domain/analytics/AdvancedAnalyticsDashboard.kt:160-206 | HIGH | Boundary | `getMonthlyTrend()` used an end timestamp of `23:59:59` as an exclusive bound, so the chart could miss end-of-month transactions. | Use an exclusive month-end boundary (`start of next month`). **[RESOLVED BY A.5]** |
| 5b | domain/analytics/AdvancedAnalyticsDashboard.kt:160-206 | HIGH | Bug/Performance | `getMonthlyTrend()` still issues one repository query per month and ignores incoming transfers even though `generateDashboardData()` counts them as income. The chart can still disagree with the dashboard header on those broader semantics. | Aggregate the whole range in one query, and apply the same income/spending rules as the top-level totals. |
| 6 | domain/analytics/AdvancedAnalyticsDashboard.kt:133-136 | MEDIUM | Architecture | Top categories are returned as placeholder labels like `Category 5` instead of real category names, making a user-facing dashboard depend on fake data. | Join/fetch category metadata through the category repository/DAO and populate real display names, icons, and colors. |
| 7 | domain/analytics/AnomalyDetector.kt:187-188,224-225,271 | HIGH | Bug | When a category/context has a flat baseline (`IQR == 0` or `MAD == 0`), the detector returns no anomalies at all. Common patterns like `[10, 10, 10, 10, 100]` therefore miss the obvious spike. | Add a zero-dispersion fallback (for example median/multiplier or max-vs-median logic) before bailing out on `IQR == 0` / `MAD == 0`. |
| 8 | domain/analytics/CategoryInsightEngine.kt:90-91 | MEDIUM | Performance | Previous-period expenses are filtered again for every category, producing an avoidable O(categories × previous expenses) pass. | Pre-group `previousExpenses` by `categoryId` once, then look up the prior bucket per category. |
| 9 | domain/analytics/DayOfWeekAnalyzer.kt:32-44 | MEDIUM | Bug | The analyzer returns results sorted by spend, not by weekday order. That conflicts with the `dayIndex` contract and with other analytics outputs that return Monday→Sunday ordering. | Return the list in stable weekday order and let callers sort separately if they need a ranking view. |
| 10 | domain/analytics/InsightsEngine.kt:29-35,242-334,368-397,600-631 | HIGH | Architecture | The class injects `MonthlyComparisonCalculator`, `CategoryInsightEngine`, `MerchantInsightEngine`, and `DayOfWeekAnalyzer`, but then reimplements those calculations internally instead of delegating to them. This defeats the “extracted focused engines” split and guarantees behavioral drift. | Make `InsightsEngine` a coordinator that delegates to the focused engines, or remove the unused engines and keep one source of truth. |
| 11 | domain/analytics/InsightsEngine.kt:380-389 | HIGH | Bug | Merchant insights expose `ms.merchantName`, which the DAO defines as the canonical `merchantKey`, not the human-readable display name. UI consumers can therefore show normalized keys instead of actual merchant names. | Populate `MerchantInsight.merchant` with `ms.displayName` and keep the key only for internal joins/lookups. |
| 12 | domain/analytics/MerchantInsightEngine.kt:23-27 | MEDIUM | Architecture | Merchant grouping is based on `merchant.lowercase()` instead of the app’s canonical `merchantKey`. The same merchant can be split across aliases/scripts here while other engines merge them. | Group by `merchantKey` (with a safe fallback only for legacy null keys) and keep one display name per bucket. |
| 13 | domain/analytics/SpendingPersonalityClassifier.kt:245-277 | HIGH | Bug | Budget adherence compares ~3 months of purchases against the current active monthly budget amount once. A user who stays exactly on budget each month is scored as heavily over budget after three months. | Normalize spending to the same cadence as the budget (per month), or compute adherence month-by-month across the analysis window and average the results. |
| 14 | domain/analytics/SpendingThresholdCalculator.kt:43,83-115,129-133 | MEDIUM | Bug | The singleton cache is a plain mutable map accessed from `Dispatchers.IO` without synchronization. Concurrent threshold requests can race on reads/writes and produce stale or torn cache state. | Guard cache access with a `Mutex` or replace it with a `ConcurrentHashMap` plus atomic update semantics. |
| 15 | domain/analytics/TotalsAggregationEngine.kt:37-54,74-101,114-158 | HIGH | Bug | Monthly/weekly/daily totals return only periods that contain transactions. Zero-spend months/days disappear from the series, and week labels are renumbered based on the filtered subset, which distorts charts and drill-down navigation. | Materialize the full expected timeline (12 months, all touched weeks, 7 days) and fill missing periods with zero totals before labeling/sorting. |
| 16 | domain/analytics/TransferDirectionAnalytics.kt:225-240 | MEDIUM | Bug | Transfer pruning removes an arbitrary slice of IDs from a `ConcurrentHashMap`. Once a tracked ID is pruned, a later user correction for that transfer no longer adjusts accuracy, so long-running stats drift. | Track insertion order / last-seen time and prune the oldest entries deterministically with a bounded queue or LRU structure. |
| 17 | domain/analytics/TransferDirectionAnalytics.kt:70-75,188-201 | MEDIUM | Bug | Correction accounting assumes every auto-detection was initially counted as correct. If `recordAutoDetection(..., wasCorrect = false, transferId = ...)` is used, a later `recordUserCorrection()` decrements `correctDetections` again and undercounts accuracy. | Persist the initial correctness state per tracked transfer and compute correction deltas from that baseline instead of assuming “counted as correct unless corrected later”. |
| 18 | domain/analytics/SpendingPaceModels.kt:N/A | LOW | Architecture | The batch plan and requested file list reference `SpendingPaceModels.kt`, but no such file exists; pace models currently live in `AnalyticsModels.kt`. This creates stale review/tooling boundaries and easy batch drift. | Either restore a dedicated `SpendingPaceModels.kt` file or update the batch plan/file lists so tooling matches the actual source layout. |

### Cross-Component Issues
| # | Components | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | ExpenseDao ↔ InsightsEngine ↔ SpendingThresholdCalculator | HIGH | Bug | Merchant stats, “largest expense”, and percentile inputs are sourced from DAO queries that still use raw `amount`/`AVG(amount)`/`MAX(amount)` instead of the domain’s `effectiveAmount` share logic. Shared-expense users will get inconsistent anomaly, merchant, and threshold analytics depending on which path is used. | Centralize the SQL effective-amount expression (or expose precomputed views) and make every analytics DAO query use the same adjusted amount semantics. |
| 2 | AdvancedAnalyticsEngine ↔ MerchantInsightEngine ↔ InsightsEngine | HIGH | Architecture | Merchant identity is implemented three different ways: raw merchant string, lowercased merchant string, and canonical `merchantKey`. The same data can therefore produce three different “top merchant” answers depending on which engine is called. | Define one merchant-analytics contract around `merchantKey + displayName` and make all engines consume that canonical representation. |
| 3 | InsightsEngine ↔ MonthlyComparisonCalculator ↔ CategoryInsightEngine ↔ DayOfWeekAnalyzer ↔ MerchantInsightEngine | MEDIUM | Architecture | The approved extraction into focused engines exists in DI, but `InsightsEngine` still contains parallel implementations. Tests can pass against one engine while production calls another, which undermines the entire decomposition. | Pick a single execution path: either delegate from `InsightsEngine` to the focused engines everywhere, or remove the duplicate code paths. |
| 4 | AdvancedAnalyticsDashboard ↔ DashboardDataProvider ↔ DashboardContractsAdapter ↔ ComputeDashboardWidgetsUseCase | MEDIUM | Architecture | `AdvancedAnalyticsDashboard` builds a second dashboard analytics pipeline with its own DTOs, aggregation rules, and repository access instead of reusing the existing dashboard contracts/use cases. That increases drift risk and doubles maintenance cost for dashboard semantics. | Consolidate dashboard aggregation behind the existing dashboard provider/contracts or clearly separate the “advanced dashboard” as a derived view built from shared core metrics. |

### Overlapping Functionality
| # | Files | Description | Recommendation |
|---|-------|-------------|----------------|
| 1 | InsightsEngine.kt, MonthlyComparisonCalculator.kt | Monthly comparison is implemented twice (repository-backed in `InsightsEngine`, in-memory in `MonthlyComparisonCalculator`). | Keep one canonical calculator and have `InsightsEngine` delegate to it. |
| 2 | InsightsEngine.kt, CategoryInsightEngine.kt | Category insight generation exists both as a dedicated engine and as a bespoke implementation in `InsightsEngine`. | Remove one path or refactor both to share a common core function. |
| 3 | InsightsEngine.kt, DayOfWeekAnalyzer.kt, AdvancedAnalyticsEngine.kt | Day-of-week analytics are computed in multiple places with different ordering and semantics (`avgPerTransaction` vs `averagePerDay`, sorted vs chronological). | Consolidate on a single day-of-week analyzer/model contract and adapt views from that shared output. |
| 4 | InsightsEngine.kt, MerchantInsightEngine.kt, AdvancedAnalyticsEngine.kt | Merchant analytics are duplicated across three engines, each with different grouping keys, limits, and recurrence heuristics. | Promote one canonical merchant analytics engine and let the other features compose or filter its output. |
| 5 | AdvancedAnalyticsDashboard.kt, DashboardContractsAdapter.kt, DashboardDataProvider.kt | Two parallel dashboard aggregation layers exist for broadly similar outputs. | Reuse the shared dashboard data pipeline or extract a common aggregation layer under both callers. |

### Summary
- Total issues: 22 (0 critical, 10 high, 11 medium, 1 low)
- Files with issues: 12/16
- Files clean: 4/16
