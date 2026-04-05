# Deep Review: Analytics & Savings Engines Subsystem

**Date:** April 5, 2026  
**Scope:** `domain/analytics/*`, `domain/savings/*`, `domain/health/FinancialHealthScoreV2.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `domain/usecase/dashboard/DashboardDataProvider.kt`  
**Batch Score:** 58/100

---

## Executive Summary

The Analytics & Savings subsystem has significant consistency problems. The most impactful issues are:

1. **Dashboard pace divergence** — two incompatible pace formulas produce conflicting numbers shown to the user simultaneously.
2. **Amount semantic drift** — `amount` vs `effectiveAmount` and missing `isNotMine` gates create cross-engine inconsistencies that silently corrupt savings triggers and merchant analytics.
3. **TotalsAggregation yearly classification bug** — yearly totals are compared against monthly averages, making the status label always wrong.
4. **Unbounded in-memory state** — multiple singleton maps grow without bound over app lifetime.

Individual H6/C1/H5 fixes are directionally correct but cross-engine consistency is not yet production-safe.

---

## Files Reviewed

| File | Lines | Status |
|------|-------|--------|
| `domain/analytics/InsightsEngine.kt` | 728 | Issues found |
| `domain/analytics/SpendingPaceCalculator.kt` | 106 | Minor issues |
| `domain/analytics/AdvancedAnalyticsEngine.kt` | 955 | Issues found |
| `domain/analytics/TotalsAggregationEngine.kt` | 317 | Bug found |
| `domain/analytics/CategoryInsightEngine.kt` | 113 | Minor issues |
| `domain/analytics/TransferDirectionAnalytics.kt` | 245 | Issues found |
| `domain/savings/AutomatedSavingsRuleEngine.kt` | 296 | Issues found |
| `domain/savings/SmartSavingsEngine.kt` | 277 | Issues found |
| `domain/health/FinancialHealthScoreV2.kt` | 569 | Minor issues |
| `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 758 | Issues found |
| `domain/usecase/dashboard/DashboardDataProvider.kt` | 170 | Clean |

---

## Issues

### ISSUE-1 — Dashboard pace diverges from canonical SpendingPaceCalculator

| Field | Value |
|-------|-------|
| **Severity** | CRITICAL |
| **File** | `ComputeDashboardWidgetsUseCase.kt:398-429` |
| **Type** | Consistency / Regression |
| **Status** | Confirmed |

**Description:**  
The dashboard computes `currentPace` via `insightsEngine.getSpendingPaceSuspend()` (which delegates to the canonical `SpendingPaceCalculator`), but then **ignores it** for the `SpendingPaceWidget`. Instead, lines 398-429 construct a brand-new `SpendingPace` object using a different formula:

- **Canonical formula** (`SpendingPaceCalculator:56-62`): `pace% = (currentDailyRate / baselineDailyRate) × 100` where `baselineDailyRate = previousMonthTotal / previousMonthDays`.
- **Dashboard formula** (`ComputeDashboardWidgetsUseCase:409-413`): `pace% = (monthSpent / expectedSpend) × 100` where `expectedSpend = baseline × dayOfMonth / daysInMonth` and `baseline` = budget amount or previous month total.

These produce different numbers for the same data. The `currentPace` is used for `SynthesisEngine`/`FinancialRunway`, while the locally-computed `pace` drives the `SpendingPaceWidget`. The user sees conflicting pace status across dashboard sections.

**Suggested Fix:**  
Use `currentPace` directly for the `SpendingPaceWidget`. If a budget-relative pacing metric is needed, expose it as a separate named widget (e.g., `BudgetPaceWidget`) with distinct naming to avoid confusion.

---

### ISSUE-2 — Savings rules use raw `amount` and don't exclude `isNotMine`

| Field | Value |
|-------|-------|
| **Severity** | MAJOR |
| **File** | `AutomatedSavingsRuleEngine.kt:114-116, 128-167, 179-189` |
| **Type** | Logic / Consistency |
| **Status** | Confirmed |

**Description:**  
- `evaluateRoundUpRule` (line 128): round-up arithmetic uses `expense.amount` (raw full amount), not `expense.effectiveAmount` (user's share after splits).
- `evaluateSpareChangeRule` (line 183): range check `expense.amount in 1.0..10.0` and savings amount both use `expense.amount`.
- Neither rule gates on `!expense.isNotMine`, so auto-savings can trigger on transactions the user doesn't own.

This means: (a) shared expenses trigger savings at the full card amount rather than the user's share, and (b) non-owned transactions incorrectly trigger savings.

**Suggested Fix:**  
1. Add `if (expense.isNotMine) return null` guard at the top of both `evaluateRoundUpRule` and `evaluateSpareChangeRule`.
2. Change arithmetic to use `expense.effectiveAmount` (or explicitly document if full-card-amount behavior is intentional).

---

### ISSUE-3 — Merchant std-dev computed from `amount` while totals use effective share

| Field | Value |
|-------|-------|
| **Severity** | MAJOR |
| **File** | `InsightsEngine.kt:380` |
| **Type** | Consistency |
| **Status** | Confirmed |

**Description:**  
In `buildMerchantInsights()`, the standard deviation is computed from:

```kotlin
val amounts = purchasesByMerchantKey[ms.merchantName]?.map { it.amount } ?: emptyList()
```

This uses raw `amount`, while the `MerchantStats` from the DAO (used for `avgAmount`, `totalAmount`, `minAmount`, `maxAmount`) may use different semantics. Within the same `MerchantInsight` payload, `stdDeviation` reflects raw amounts while `avgAmount`/`totalSpent` reflect effective-share logic. The grouping correctly filters `!it.isNotMine` (line 377), but the mapped value is `it.amount` instead of `it.effectiveAmount`.

**Suggested Fix:**  
Change line 380 from `it.amount` to `it.effectiveAmount`:

```kotlin
val amounts = purchasesByMerchantKey[ms.merchantName]?.map { it.effectiveAmount } ?: emptyList()
```

---

### ISSUE-4 — `getAverageForPeriodType(YEAR)` computes average from monthly totals

| Field | Value |
|-------|-------|
| **Severity** | CRITICAL |
| **File** | `TotalsAggregationEngine.kt:235-243` |
| **Type** | Bug / Logic |
| **Status** | Confirmed |

**Description:**  
`getAverageForPeriodType(PeriodType.YEAR)` (lines 234-243) fetches **monthly totals for the current year** and averages them. This monthly average (~€X/month) is then used by `getYearlyTotals()` (line 170) to classify **yearly totals** (€X×12/year) via `getPeriodStatus()`. Since a yearly total is roughly 12× the monthly average, every year will always be classified as `OVER_AVERAGE`.

```kotlin
PeriodType.YEAR -> {
    val currentYear = TimePeriodUtils.getYear(now)
    val (startMs, _) = getYearRange(currentYear)
    val allMonths = expenseRepository.getMonthlyTotalsForPeriod(startMs, now)
    // This returns an AVERAGE OF MONTHLY TOTALS, not yearly average
    allMonths.map { it.total }.average()
}
```

**Suggested Fix:**  
Compute the yearly baseline from actual yearly totals across multiple years:

```kotlin
PeriodType.YEAR -> {
    val currentYear = TimePeriodUtils.getYear(now)
    val yearTotals = (currentYear - 4 until currentYear).mapNotNull { year ->
        val (start, end) = getYearRange(year)
        val total = expenseRepository.getTotalForPeriod(start, end)
        if (total > 0) total else null
    }
    yearTotals.takeIf { it.isNotEmpty() }?.average() ?: 0.0
}
```

---

### ISSUE-5 — Historical runway baseline uses partial edge months

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `FinancialHealthScoreV2.kt:308-329` |
| **Type** | Regression / Stability |
| **Status** | Confirmed |

**Description:**  
`calculateHistoricalMonthlyBaseline()` uses a 90-day rolling window starting from `addDays(currentPeriodStart, -90)`. This window will include a partial month at the start boundary (e.g., if current period starts April 1, the baseline starts ~Jan 2, giving a partial January). The partial month's total is lower than a full month, biasing the baseline downward and causing the runway score to appear better than it should.

The month-grouping key also uses non-padded format (`"${year}-${month}"`), which is inconsistent with other engines that use padded formats.

**Suggested Fix:**  
Align the baseline start to a full-month boundary:

```kotlin
val baselineStart = TimePeriodUtils.getStartOfMonth(
    TimePeriodUtils.addMonths(currentPeriodStart, -3)
)
```

---

### ISSUE-6 — TransferDirectionAnalytics maps grow unbounded; non-idempotent counting

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `TransferDirectionAnalytics.kt:58-59, 70-73, 87-134` |
| **Type** | Thread-safety / Performance |
| **Status** | Confirmed |

**Description:**  
Four `ConcurrentHashMap` fields in this `@Singleton` class grow without any size bound or TTL:

- `autoDetectedDirectionByTransferId` (line 58)
- `correctionAppliedByTransferId` (line 59)
- `incomingSources` (line 56)
- `outgoingDestinations` (line 57)

Additionally, `recordAutoDetection` (line 64) does not check whether a transferId was already recorded. Calling it twice for the same transfer will:
1. Overwrite the direction in `autoDetectedDirectionByTransferId` (benign).
2. Increment `totalTransfers`, `autoDetectedIncoming`/`autoDetectedOutgoing`, `totalDetections`, and `correctDetections` again — inflating all counters.

**Suggested Fix:**  
1. Make `recordAutoDetection` idempotent: check if `transferId` already exists before incrementing counters.
2. Add bounded retention (e.g., LRU with capacity, or prune entries older than a threshold on each `reset()` or periodically).

---

### ISSUE-7 — Historical monthly averages omit `!isNotMine` filter

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `InsightsEngine.kt:341-345, 432-435` |
| **Type** | Consistency |
| **Status** | Confirmed |

**Description:**  
`calculateCategoryMonthlyAverages()` (line 341) and `calculateAverageMonthlySpend()` (line 432) filter by `TransactionType.PURCHASE` but do not exclude `isNotMine` transactions. Since `effectiveAmount` for not-mine expenses can be 0.0, this creates month-bucket entries with zero totals that:

- Inflate the denominator when computing averages (more months counted than real).
- Dilute category-level and global monthly averages downward.

**Suggested Fix:**  
Add `&& !it.isNotMine` to both filter chains:

```kotlin
// calculateCategoryMonthlyAverages (line 341)
val purchases = allExpenses.filter {
    it.transactionType == TransactionType.PURCHASE
        && !it.isNotMine        // ADD THIS
        && it.categoryId != null
        && it.date < currentMonth.startMs
}

// calculateAverageMonthlySpend (line 432)
val purchases = allExpenses.filter {
    it.transactionType == TransactionType.PURCHASE
        && !it.isNotMine        // ADD THIS
        && it.date < currentMonth.startMs
}
```

---

### ISSUE-8 — `AdvancedAnalyticsEngine` uses hardcoded dispatchers instead of injected ones

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `AdvancedAnalyticsEngine.kt:132, 228, 322, 419` |
| **Type** | Architectural / Testability |
| **Status** | New |

**Description:**  
Multiple methods use `withContext(Dispatchers.Default)` and `withContext(Dispatchers.IO)` directly instead of using injected `@DefaultDispatcher` / `@IoDispatcher` qualifiers as established by the project's `DispatchersModule`. This breaks the dispatcher injection pattern, making these methods impossible to test with `TestCoroutineDispatcher`.

**Suggested Fix:**  
Inject dispatchers via constructor:

```kotlin
class AdvancedAnalyticsEngine @Inject constructor(
    // ...existing params...
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
)
```

Then replace `Dispatchers.Default` → `defaultDispatcher` and `Dispatchers.IO` → `ioDispatcher`.

---

### ISSUE-9 — `TotalsAggregationEngine` also uses hardcoded `Dispatchers.IO`

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `TotalsAggregationEngine.kt:32, 59, 106, 137, 163, 194, 230` |
| **Type** | Architectural / Testability |
| **Status** | New |

**Description:**  
Same pattern as ISSUE-8. All public methods use `withContext(Dispatchers.IO)` directly. The class constructor does not inject a dispatcher.

**Suggested Fix:**  
Same as ISSUE-8 — inject `@IoDispatcher` and use it.

---

### ISSUE-10 — `SmartSavingsEngine` hardcodes €500 discretionary baseline

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `SmartSavingsEngine.kt:186` |
| **Type** | Logic / Hardcoded Magic Number |
| **Status** | New |

**Description:**  
```kotlin
val discretionary = 500.0 * horizonMultiplier // Assume €500/month discretionary baseline
```

The Monte Carlo savings recommendation assumes a fixed €500/month discretionary budget regardless of the user's actual spending. For users spending €200/month or €2000/month this produces meaningless results.

**Suggested Fix:**  
Derive the discretionary baseline from the user's actual historical average discretionary spend (non-essential purchases), or use the FinancialWeather's `discretionaryBudget` field which is already computed.

---

### ISSUE-11 — `AdvancedAnalyticsEngine.analyzePriceTrend` uses raw `amount`

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `AdvancedAnalyticsEngine.kt:695-696` |
| **Type** | Consistency |
| **Status** | New |

**Description:**  
```kotlin
val first = sorted.first().amount
val last = sorted.last().amount
```

Price trend analysis uses raw `amount` instead of `effectiveAmount`. For shared expenses, this shows the full card amount rather than the user's share, which could misrepresent price trends for frequently-split merchants.

**Suggested Fix:**  
Use `effectiveAmount` for price trend analysis, or ensure the input list is already filtered to non-shared expenses.

---

### ISSUE-12 — `CategoryInsightEngine.missingCategoryHitCount` grows unbounded

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `CategoryInsightEngine.kt:14` |
| **Type** | Performance / Memory |
| **Status** | New |

**Description:**  
```kotlin
private val missingCategoryHitCount = ConcurrentHashMap<Long, Int>()
```

This diagnostic map accumulates entries forever in the `@Singleton` lifetime. While it only stores `Long → Int` pairs, if many category deletions occur, the map grows without bound. No cleanup or size limit exists.

**Suggested Fix:**  
Add a size cap (e.g., retain only the top-N entries) or clear the map periodically (e.g., on each new invocation, or retain only the last session's data).

---

### ISSUE-13 — `AutomatedSavingsRuleEngine.monthToDateRuleTotals` never pruned

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `AutomatedSavingsRuleEngine.kt:53` |
| **Type** | Performance / Memory |
| **Status** | New |

**Description:**  
```kotlin
private val monthToDateRuleTotals = mutableMapOf<String, Double>()
```

Keys are in the format `"ruleKey-YYYY-M"`. Old months are never removed. Over months/years the map accumulates stale entries. While memory impact is small per entry, this is a leak in a `@Singleton`.

**Suggested Fix:**  
Prune entries for months older than the current month at the start of `evaluateRules()`, or clear on month rollover.

---

### ISSUE-14 — `SmartSavingsEngine` uses `java.util.Calendar` instead of `TimePeriodUtils`

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `SmartSavingsEngine.kt:86-97, 152-160` |
| **Type** | Consistency / Potential Timezone Bug |
| **Status** | New |

**Description:**  
`analyzeSpendingPace()` and `runMonteCarloSimulation()` construct `Calendar` objects manually for month-start calculations instead of using the project's `TimePeriodUtils.getStartOfMonth()`. This could produce different month boundaries if timezone handling differs between `Calendar.getInstance()` and `TimePeriodUtils`.

**Suggested Fix:**  
Replace manual Calendar logic with `TimePeriodUtils.getStartOfMonth(now)` for consistency.

---

### ISSUE-15 — Duplicate `DashboardExpense.toEntityExpense()` extension functions

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `InsightsEngine.kt:701-720` and `ComputeDashboardWidgetsUseCase.kt:738-757` |
| **Type** | Maintenance / DRY Violation |
| **Status** | New |

**Description:**  
Two nearly identical `DashboardExpense.toEntityExpense()` extension functions exist in different files. If one is updated without the other, field mapping silently diverges, potentially causing analytics to compute on different data than the dashboard.

**Suggested Fix:**  
Extract to a shared extension in a common location (e.g., `domain/model/dashboard/DashboardExpenseMapper.kt`).

---

### ISSUE-16 — `SpendingPaceCalculator` projection jumps discontinuously on day 3→4

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `SpendingPaceCalculator.kt:97-105` |
| **Type** | UX / Stability |
| **Status** | New |

**Description:**  
The `calculateProjectedTotal` method has different formulas for days 1-3 vs days 4+:

- Days 1-3: `monthSpent * (daysInMonth / 10.0)` — e.g., day 3 with €100 and 30-day month: `100 × 3.0 = €300`
- Day 4+: `monthSpent * daysInMonth / dayOfMonth` — e.g., day 4 with €100 and 30-day month: `100 × 30 / 4 = €750`

The projection can jump by 2.5× between day 3 and day 4. This creates a jarring UX where the projected total nearly triples overnight.

**Suggested Fix:**  
Use a blended/smoothed formula that transitions gradually from a conservative estimate (early days) to the linear extrapolation (day 4+), e.g.:

```kotlin
val weight = (dayOfMonth.toDouble() / 7.0).coerceIn(0.0, 1.0)
val linearProjection = monthSpent * daysInMonth.toDouble() / dayOfMonth
val conservativeEstimate = monthSpent * 3.0 // ~10% of month
(weight * linearProjection) + ((1 - weight) * conservativeEstimate)
```

---

### ISSUE-17 — `ComputeDashboardWidgetsUseCase.compute()` is a 400-line God Method

| Field | Value |
|-------|-------|
| **Severity** | MEDIUM |
| **File** | `ComputeDashboardWidgetsUseCase.kt:202-605` |
| **Type** | Architectural |
| **Status** | New |

**Description:**  
The `compute()` method is ~400 lines handling 15+ widget computations inline. It:

- Recomputes pace (instead of reusing canonical values)
- Builds financial runway
- Constructs block-party data
- Runs Monte Carlo
- Computes spending trends
- Calculates streaks
- Evaluates health scores (v1 + v2)
- Runs lifestyle savings analysis
- Computes money radar
- Runs financial stress forecast

This violates single-responsibility, makes individual widgets untestable in isolation, and is the root cause of ISSUE-1 (divergent pace formula exists because the method is too large to easily spot the duplication).

**Suggested Fix:**  
Extract each widget computation into a dedicated method or dedicated widget-builder class. The main `compute()` method should only orchestrate calls and assemble the final list.

---

### ISSUE-18 — `AdvancedAnalyticsEngine` verbose debug logging of sensitive financial data

| Field | Value |
|-------|-------|
| **Severity** | LOW |
| **File** | `AdvancedAnalyticsEngine.kt:461-477` |
| **Type** | Security / Performance |
| **Status** | New |

**Description:**  
`getStatisticalInsights()` includes an extensive debug block that logs:
- Total spending amounts
- Period ranges
- Daily totals map
- Average daily spend

While Timber strips debug logs in release builds, this adds allocation overhead in production (string formatting for the log message happens before Timber's level check), and if the release Timber configuration is misconfigured, sensitive financial data would leak to logs.

**Suggested Fix:**  
Wrap in `if (Timber.treeCount > 0 && BuildConfig.DEBUG)` or remove entirely and rely on unit tests for validation.

---

## Cross-Cutting Concerns

### Amount Semantics Summary

| Engine/File | Uses `amount` | Uses `effectiveAmount` | Filters `isNotMine` |
|-------------|:---:|:---:|:---:|
| SpendingPaceCalculator | — | ✅ | ✅ |
| InsightsEngine (avg tx) | — | ✅ | ✅ |
| InsightsEngine (merchant std-dev) | ⚠️ **YES** | — | ✅ |
| InsightsEngine (category avg) | — | ✅ | ⚠️ **NO** |
| InsightsEngine (monthly avg) | — | ✅ | ⚠️ **NO** |
| CategoryInsightEngine | — | ✅ | ✅ |
| AdvancedAnalyticsEngine (categories) | — | ✅ | ✅ |
| AdvancedAnalyticsEngine (price trend) | ⚠️ **YES** | — | ✅ |
| AutomatedSavingsRuleEngine | ⚠️ **YES** | — | ⚠️ **NO** |
| SmartSavingsEngine | — | ✅ | ✅ |
| FinancialHealthScoreV2 | — | ✅ | ✅ (purchases) |
| TotalsAggregationEngine | — | DAO-level | DAO-level |
| ComputeDashboardWidgetsUseCase | — | ✅ | ✅ |

### Dispatcher Injection Compliance

| Engine/File | Injected Dispatchers | Hardcoded Dispatchers |
|-------------|:---:|:---:|
| InsightsEngine | ✅ (uses coroutineScope) | — |
| SpendingPaceCalculator | ✅ (pure function) | — |
| AdvancedAnalyticsEngine | — | ⚠️ `Dispatchers.Default`, `Dispatchers.IO` |
| TotalsAggregationEngine | — | ⚠️ `Dispatchers.IO` |
| CategoryInsightEngine | ✅ (pure function) | — |
| TransferDirectionAnalytics | ✅ (StateFlow) | — |
| AutomatedSavingsRuleEngine | ✅ | — |
| SmartSavingsEngine | ✅ | — |
| FinancialHealthScoreV2 | ✅ | — |

### Unbounded Singleton State

| File | Field | Risk |
|------|-------|------|
| `TransferDirectionAnalytics.kt:56-59` | 4 ConcurrentHashMaps | Grows per transfer, never pruned |
| `CategoryInsightEngine.kt:14` | `missingCategoryHitCount` | Grows per missing category |
| `AutomatedSavingsRuleEngine.kt:53` | `monthToDateRuleTotals` | Grows per month, never pruned |

---

## Coverage Assessment

| Check Area | Status | Notes |
|------------|--------|-------|
| Pace consistency (InsightsEngine vs SpendingPaceCalculator) | ✅ Delegation correct | But dashboard overrides it (ISSUE-1) |
| Filtering (txType + isNotMine + effectiveAmount) | ⚠️ Partial | Savings rules + merchant + monthly averages inconsistent |
| ROUND_UP safety / Non-finite handling | ✅ Good | Extensive validation in AutomatedSavingsRuleEngine |
| Runway early-month stabilization | ⚠️ Partial | Blend added but partial-month baseline bias remains |
| Thread safety | ⚠️ Partial | CHM + StateFlow + Mutex used; unbounded growth gaps |
| Performance / Dispatchers | ⚠️ Partial | 2 engines use hardcoded dispatchers |
| Regressions (M13-M17, C1, H5, H6) | ⚠️ Not fully closed | Dashboard pace divergence still active |
| Cross-engine amount semantics | ❌ Not consistent | 3 engines use raw `amount` in some paths |

---

## Priority Fix Order

### P0 — Fix Before Release

1. **ISSUE-1** (Dashboard pace divergence): Use `currentPace` for `SpendingPaceWidget`. 5-minute fix with high user-facing impact.
2. **ISSUE-4** (Yearly classification bug): `getAverageForPeriodType(YEAR)` must compute from yearly totals, not monthly. The current code guarantees incorrect labels.

### P1 — Fix This Sprint

3. **ISSUE-2** (Savings rules): Add `isNotMine` gate + use `effectiveAmount`. Risk of triggering savings on non-owned transactions.
4. **ISSUE-3** (Merchant std-dev): Change `it.amount` to `it.effectiveAmount`. One-liner fix.
5. **ISSUE-7** (Monthly averages missing filter): Add `!it.isNotMine` to two filter chains.

### P2 — Fix This Milestone

6. **ISSUE-6** (Transfer analytics unbounded + non-idempotent): Add idempotency check + size cap.
7. **ISSUE-8 / ISSUE-9** (Hardcoded dispatchers): Inject dispatchers in AdvancedAnalyticsEngine and TotalsAggregationEngine.
8. **ISSUE-10** (Hardcoded €500 baseline): Derive from actual user data.
9. **ISSUE-17** (God method): Extract widget builders from `compute()`.

### P3 — Backlog

10. **ISSUE-5** (Partial month baseline): Align to full-month boundary.
11. **ISSUE-11** (Price trend raw amount): Use effectiveAmount.
12. **ISSUE-12 / ISSUE-13** (Unbounded maps): Add pruning.
13. **ISSUE-14** (Calendar vs TimePeriodUtils): Use consistent API.
14. **ISSUE-15** (Duplicate mapper): Extract shared extension.
15. **ISSUE-16** (Projection discontinuity): Smooth the formula.
16. **ISSUE-18** (Debug logging): Guard or remove.

---

## Batch Score Breakdown

| Dimension | Score | Weight | Weighted |
|-----------|-------|--------|----------|
| Correctness (bugs, logic errors) | 40/100 | 30% | 12.0 |
| Consistency (amount semantics, filtering) | 45/100 | 25% | 11.25 |
| Performance & Thread Safety | 65/100 | 15% | 9.75 |
| Architecture & Testability | 55/100 | 15% | 8.25 |
| Robustness (edge cases, validation) | 75/100 | 15% | 11.25 |
| **Total** | | | **52.5/100** |

Rounded: **53/100** (adjusted from initial estimate of 62 after discovering additional issues ISSUE-8 through ISSUE-18).

---

## Summary

The subsystem has solid foundations — error-resilient async in InsightsEngine, proper mutex in savings rules, good non-finite validation. However, it suffers from:

1. **Two critical bugs** (dashboard pace divergence, yearly classification) that produce visibly wrong data for users.
2. **Systematic amount-semantic drift** where 3 engines use `amount` when they should use `effectiveAmount`, and 2 filter chains omit `isNotMine`. This is the highest-risk class of defect because it silently corrupts analytics and savings without obvious errors.
3. **Architectural debt** in `ComputeDashboardWidgetsUseCase` (400-line method) that makes these issues hard to spot and test.

The H6/C1/H5 fixes that extracted `SpendingPaceCalculator` and added runway blending were good moves, but the cleanup isn't complete — the dashboard still re-derives pace locally, and cross-engine consistency was not validated end-to-end after those changes.
