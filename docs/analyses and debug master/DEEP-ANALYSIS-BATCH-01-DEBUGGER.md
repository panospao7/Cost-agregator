# Deep Analysis — Batch 01: Analytics Engines (@debugger)

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

## Per-File Issues

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | AdvancedAnalyticsEngine.kt:803 | HIGH | Incorrect Formula (Streak) | `calculateStreakCount()` uses 0-indexed months from `getMonth()` (0–11), producing `"2024-00"` for January. Inconsistent with InsightsEngine which uses `getMonth()+1` (1-indexed). | Create expenses in January and December of consecutive years; inspect streak count. | Use `getMonth() + 1` consistently (1–12). |
| 2 | AdvancedAnalyticsEngine.kt:307 | MEDIUM | Data Correctness | `recentTransactions` built from `.take(5)` on unsorted list — "recent" 5 may be random, not most recent. | Merchant with >5 transactions; "recent" shows random items. | Sort by date descending before taking. |
| 3 | AdvancedAnalyticsEngine.kt:493 | MEDIUM | Incorrect Formula | `volatilityIndex = (cv * 100).coerceIn(0f, 100f)` — CV legitimately exceeds 1.0 for right-skewed expense data. Clamping to 100 loses information. | Expenses [1,1,1,1,500]; CV>1.0, volatilityIndex clamped to 100. | Remove coerceIn or use logarithmic mapping. |
| 4 | AdvancedAnalyticsEngine.kt:619 | MEDIUM | Off-by-One / AIOOB | `daysPassed` can be 0 on first millisecond of period. If `daysPassed` exceeds `periodDays` (DST edge), `daily[i]` causes ArrayIndexOutOfBoundsException. | Access analytics on first day of period or around DST transition. | Use `safeDaysPassed = daysPassed.coerceIn(0, periodDays)`. |
| 5 | AdvancedAnalyticsDashboard.kt:84 | HIGH | Hardcoded Dispatcher | Uses `Dispatchers.IO` directly instead of injected dispatcher. Breaks testability. | Unit-test with TestCoroutineDispatcher; still runs on real IO pool. | Inject `@IoDispatcher` via constructor. |
| 6 | AdvancedAnalyticsDashboard.kt:92-98 | MEDIUM | Missing Transaction Types | Uses `expense.amount` instead of `expense.effectiveAmount`. Ignores shared-expense splits and `isNotMine`. Shared expenses counted at full amount. | Shared expense 50% share; dashboard shows full amount. | Use `effectiveAmount` and filter `!isNotMine`. |
| 7 | AdvancedAnalyticsDashboard.kt:160-206 | HIGH | N+1 Query | `getMonthlyTrend()` calls `getExpensesBetween()` inside loop — 12 separate DB queries for 12-month range. | 12-month view; observe 12 sequential DB roundtrips. | Fetch all expenses once, group by month in-memory. |
| 8 | AdvancedAnalyticsDashboard.kt:282 | LOW | Logic Error | `weekendSpending > weekdaySpending / 5 * 2` — checks if weekend > 40% of weekday, fires very easily, noisy insights. | Any moderate weekend spending triggers "high weekend" insight. | Compare per-day averages: `weekendSpending / 2 > weekdaySpending / 5`. |
| 9 | AnomalyDetector.kt:128-130 | MEDIUM | Priority Logic Inverted | Merge uses `new.detectionMethod.ordinal > existing.detectionMethod.ordinal`. Enum order: MULTIPLIER=0, IQR=1, MAD=2, CONTEXTUAL=3. CONTEXTUAL becomes highest priority but should be lowest. | Expense flagged by both IQR and CONTEXTUAL; CONTEXTUAL overwrites IQR. | Reorder enum to match priority or use explicit priority map. |
| 10 | SpendingThresholdCalculator.kt:43 | MEDIUM | Thread Safety | `cache` is plain `mutableMapOf<>()` (non-thread-safe) accessed from `ioDispatcher`. ConcurrentModificationException risk. | Multiple concurrent calls on Dispatchers.IO. | Use `ConcurrentHashMap`. |
| 11 | SpendingPaceCalculator.kt:30 | MEDIUM | Off-by-One | `currentDay` computed as `daysBetween(...) + 1`. On first day at 00:00, treated as "1 day elapsed" even if 0 seconds passed. | Check pace at midnight on 1st after late-night purchase. | Document assumption or use fractional days. |
| 12 | SpendingPaceCalculator.kt:97-103 | LOW | Inaccurate Projection | Conservative estimate uses arbitrary `* 3.0` factor. Non-monotonic projection (day 2 < day 1). | Day 1 spend €100: projected €678. Day 2 spend €100: projected €648. | Document rationale or use standard projection method. |
| 13 | InsightsEngine.kt:381 | MEDIUM | Key Mismatch | Groups by `merchantKey` (canonical) but looks up via `ms.merchantName` (display name). If they differ, lookup misses, stdDev always null. | Merchant "Starbucks" with key "starbucks"; lookup fails. | Use `ms.merchantKey` for lookup. |
| 14 | InsightsEngine.kt:152 | LOW | Incorrect Float Coerce | `severity` not bounded below at 0. Negative values possible when spending decreased. | Spending decreased 50%; severity = -0.5. | Use `.coerceIn(0f, 1.0f)`. |
| 15 | CategoryInsightEngine.kt:35 | LOW | Redundant Null Check | `it.date != null` always true — `Expense.date` is non-nullable `Long`. | N/A | Remove dead check. |
| 16 | DayOfWeekAnalyzer.kt:26 | LOW | Unnecessary Non-Null Assert | `expense.date!!` unnecessary — `Expense.date` is non-nullable. | N/A | Remove `!!`. |
| 17 | SpendingPersonalityClassifier.kt:58 | MEDIUM | Hardcoded Dispatcher | Uses `Dispatchers.Default` directly. Breaks testability. | Unit tests cannot control dispatcher. | Inject `@DefaultDispatcher`. |
| 18 | TransferDirectionAnalytics.kt:225-240 | LOW | Non-Deterministic Pruning | Prunes arbitrary entries from ConcurrentHashMap (iteration order not guaranteed). Old entries retained, recent ones discarded. | >10,000 tracked transfers; pruning removes recent entries. | Use LinkedHashMap with access-order or ArrayDeque. |
| 19 | AdvancedAnalyticsEngine.kt:240-242 | LOW | Historical Overlap | Historical set includes current period's expenses, counted twice in price trend analysis. | Merchant with only current-period transactions; inflated historical stats. | Filter historical to exclude current period. |
| 20 | AdvancedAnalyticsDashboard.kt:133 | LOW | Placeholder | `categoryName = "Category $catId"` — actual name never fetched. UI shows "Category 5" instead of "Groceries". | View dashboard top categories. | Inject CategoryRepository and look up name. |
| 21 | AdvancedAnalyticsDashboard.kt:136 | LOW | Placeholder | `changeFromLastPeriod = 0.0` — always zero, never calculated. | All categories show 0% change. | Calculate actual change or remove field. |
| 22 | MerchantInsightEngine.kt:47 | MEDIUM | Division by Zero Risk | `stdDev / avg` — if avg == 0.0, produces NaN. NaN propagates to UI. | Expenses with effectiveAmount == 0.0 for all. | Guard: `stdDev != null && avg > 0 && stdDev / avg < 0.3`. |

### Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | InsightsEngine → MerchantInsightEngine (merchantKey mismatch) | HIGH | Data Integration | `InsightsEngine.buildMerchantInsights()` fetches DAO `MerchantStats` (display names) but groups in-memory by `merchantKey` (canonical). Lookup `purchasesByMerchantKey[ms.merchantName]` bridges two key spaces — stdDev almost always null when keys differ. | Have DAO return `merchantKey` alongside `merchantName`, or group in-memory by `merchant` with case-insensitive matching. |
| 2 | AnomalyDetector ↔ InsightsEngine (priority inversion) | HIGH | Logic | AnomalyDetector merge uses enum ordinal — CONTEXTUAL (ordinal 3) becomes highest priority when it should be lowest. InsightsEngine dedup correctly gives merchant-path priority, but within AnomalyDetector itself, MAD vs CONTEXTUAL incorrectly retains CONTEXTUAL. | Fix enum order or use explicit priority map. |
| 3 | SpendingPaceCalculator ↔ InsightsEngine (duplicate pace) | MEDIUM | Redundancy | Both calculate previous-month totals independently — SpendingPaceCalculator filters in-memory, InsightsEngine uses DB queries. If in-memory list is stale, paths diverge. | Ensure both use same data source. |
| 4 | AdvancedAnalyticsDashboard ↔ Rest of Analytics | MEDIUM | Inconsistency | Dashboard uses `expense.amount` while every other engine uses `effectiveAmount`. Doesn't filter `isNotMine`. Dashboard totals disagree with insights/pace engines. | Align to use `effectiveAmount` and filter `isNotMine`. |
| 5 | AdvancedAnalyticsEngine → CategoryRepository/BudgetRepository | LOW | Performance | `getCategoryAnalytics()` launches `async(ioDispatcher)` inside `withContext(defaultDispatcher)`. Double context-switch is correct but adds overhead. | No fix needed — correct but noting for awareness. |

### Summary
- **Total issues: 27** (22 per-file + 5 cross-component)
- **CRITICAL: 0**
- **HIGH: 4** (issues #5, #7, and cross-component #1, #2)
- **MEDIUM: 9**
- **LOW: 9**
- **Files with issues: 13/15** (AnalyticsModels.kt and AdvancedAnalyticsModels.kt are clean data models)

### Top-Priority Fixes
1. **AnomalyDetector enum priority inversion** (#9 + cross #2) — wrong detection method labels
2. **InsightsEngine merchantKey mismatch** (#13 + cross #1) — silently nullifies stdDev
3. **AdvancedAnalyticsDashboard N+1 queries** (#7) — severe latency
4. **Dashboard uses `amount` instead of `effectiveAmount`** (#6 + cross #4) — total mismatch
5. **SpendingThresholdCalculator non-thread-safe cache** (#10) — ConcurrentModificationException risk
