# Batch 17: Analytics & Savings Fixes — Implementation Plan

> **Scope**: 18 issues across analytics engines, savings rules, dashboard pace, health scoring, transfer analytics, and architectural consistency.  
> **Risk Level**: HIGH — multiple user-visible financial KPIs will shift; 2 critical bugs guarantee wrong data.  
> **Estimated Files**: 15–20 source + 10–15 test files.  
> **Review Reference**: `docs/quality/REVIEW-analytics-savings-engines.md` (batch score: 53/100)  
> **Last Updated**: April 5, 2026 — expanded from 7 to 18 issues after deep code review.

---

## Table of Contents

### Original Issues (confirmed by deep review)
1. [ISSUE-1: Canonicalize Dashboard Spending Pace](#issue-1-canonicalize-dashboard-spending-pace) — **CRITICAL**
2. [ISSUE-2: Savings Rules Use Effective Ownership-Aware Amount](#issue-2-savings-rules-use-effective-ownership-aware-amount) — MAJOR
3. [ISSUE-3: Merchant Std-Dev Must Use Effective Share](#issue-3-merchant-std-dev-must-use-effective-share) — MAJOR
4. [ISSUE-4: Correct Yearly Average Baseline Computation](#issue-4-correct-yearly-average-baseline-computation) — **CRITICAL**
5. [ISSUE-5: Runway Baseline Should Use Full Historical Months Only](#issue-5-runway-baseline-should-use-full-historical-months-only) — LOW
6. [ISSUE-6: Bound Transfer Analytics Memory and Dedupe Repeated Detections](#issue-6-bound-transfer-analytics-memory-and-dedupe-repeated-detections) — MEDIUM
7. [ISSUE-7: Explicit Ownership Filtering in Historical Monthly Averages](#issue-7-explicit-ownership-filtering-in-historical-monthly-averages) — MEDIUM

### New Issues (discovered during deep review)
8. [ISSUE-8: AdvancedAnalyticsEngine Uses Hardcoded Dispatchers](#issue-8-advancedanalyticsengine-uses-hardcoded-dispatchers) — MEDIUM
9. [ISSUE-9: TotalsAggregationEngine Uses Hardcoded Dispatchers](#issue-9-totalsaggregationengine-uses-hardcoded-dispatchers) — MEDIUM
10. [ISSUE-10: SmartSavingsEngine Hardcodes €500 Discretionary Baseline](#issue-10-smartsavingsengine-hardcodes-500-discretionary-baseline) — MEDIUM
11. [ISSUE-11: AdvancedAnalyticsEngine Price Trend Uses Raw Amount](#issue-11-advancedanalyticsengine-price-trend-uses-raw-amount) — LOW
12. [ISSUE-12: CategoryInsightEngine Unbounded Diagnostic Map](#issue-12-categoryinsightengine-unbounded-diagnostic-map) — LOW
13. [ISSUE-13: AutomatedSavingsRuleEngine Monthly Cap Map Never Pruned](#issue-13-automatedsavingsruleengine-monthly-cap-map-never-pruned) — LOW
14. [ISSUE-14: SmartSavingsEngine Calendar Inconsistency with TimePeriodUtils](#issue-14-smartsavingsengine-calendar-inconsistency-with-timeperiodutils) — LOW
15. [ISSUE-15: Duplicate DashboardExpense.toEntityExpense() Mappers](#issue-15-duplicate-dashboardexpensetoentityexpense-mappers) — LOW
16. [ISSUE-16: SpendingPaceCalculator Projection Discontinuity on Day 3→4](#issue-16-spendingpacecalculator-projection-discontinuity-on-day-34) — LOW
17. [ISSUE-17: ComputeDashboardWidgetsUseCase God Method](#issue-17-computedashboardwidgetsusecase-god-method) — MEDIUM
18. [ISSUE-18: AdvancedAnalyticsEngine Verbose Debug Logging](#issue-18-advancedanalyticsengine-verbose-debug-logging) — LOW

### Coordination
19. [Cross-Issue Regression Gate](#cross-issue-regression-gate)
20. [Execution Order & Dependencies](#execution-order--dependencies)
21. [Rollback & Safety](#rollback--safety)
22. [Open Assumptions](#open-assumptions)

---

## ISSUE-1: Canonicalize Dashboard Spending Pace

**Severity**: CRITICAL *(upgraded from MAJOR after deep review — two formulas produce visibly conflicting numbers to users)*  
**Effort**: Low–Medium  
**Priority**: P0 — Fix Before Release  
**Status**: CONFIRMED BUG

### Root Cause Analysis

In `ComputeDashboardWidgetsUseCase.kt`, there are **two independent pace computations** in the same execution:

1. **Canonical pace** (lines 249–263): Computed via `insightsEngine.getSpendingPaceSuspend(expenseEntitiesForEngines)`, which delegates to `SpendingPaceCalculator.calculate()`. Uses daily-rate ratio formula: `pace% = (currentDailyRate / baselineDailyRate) * 100`.

2. **Dashboard-local pace** (lines 398–429): Recomputes pace from scratch with a completely different formula:
   - Baseline = `overallBudget.amount` or `previousMonthTotal` (budget-first, not daily-rate based)
   - Projection = blended `(baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)` on day 1, then linear projection
   - Pace% = `monthSpent / (baseline * dayOfMonth / daysInMonth) * 100`

```kotlin
// Lines 398-429 — the conflicting local formula
val baseline = overallBudget?.budgetAmount
    ?: if (previousMonthTotal > 0) previousMonthTotal else null

val projectedTotal = if (dayOfMonth == 1) {
    if (baseline != null) (baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)
    else monthSpent * daysInMonth
} else {
    monthSpent * daysInMonth.toDouble() / dayOfMonth
}

val pacePercentage = if (baseline != null && baseline > 0) {
    val expected = baseline * dayOfMonthCoerced / daysInMonth
    val calculated = (monthSpent / expected * 100).toFloat()
    if (calculated.isFinite()) calculated else 0f
} else 0f

val pace = SpendingPace(/* ... uses local formula ... */)
```

**Impact**: The `SpendingPaceWidget` (line 590) uses the locally-computed `pace` object, while the insights screen and SynthesisEngine use the canonical pace. Users see **different pace values** depending on which screen they're on. Future fixes to `SpendingPaceCalculator` won't propagate to the dashboard.

> **WARNING**: The canonical pace (`currentPace`, line 249) is already computed but then **ignored** for the widget — only used for `SynthesisEngine.synthesize()`.

### Implementation Strategy

1. **Delete** the local pace recomputation block (lines 397–429).
2. **Replace** `pace` with `currentPace` when constructing the `SpendingPaceWidget` at line 590:
   ```kotlin
   // Line 590 — replace `pace` with `currentPace`
   if (currentPace.paceStatus != PaceStatus.NO_BASELINE)
       add(DashboardWidget.SpendingPaceWidget(currentPace))
   ```
3. **Remove dead variables**: `baseline`, `dayOfMonthCoerced`, `projectedTotal` (the local one), `pacePercentage`, and the locally-constructed `pace` object.
4. **Keep** the canonical pace's gating semantics (`NO_BASELINE` → hide widget).

### Files to Modify

| File | Change |
|------|--------|
| `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Delete lines 397-429, update line 590 |

### Dependencies

- Depends on `SpendingPaceCalculator` being the single source of truth (already established).
- No dependency on other Batch 17 issues.

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| User-visible pace % changes immediately | HIGH | Expected and correct; capture before/after in test fixtures |
| Existing `DashboardWidgetConsistencyTest` may encode old formula values | MEDIUM | Update test expectations to match canonical pace |
| `GoldenMasterVerificationTest` pace parity assertions may break | LOW | These should now pass *better* since both sources are unified |

### Verification Plan

- **Unit test**: Create a fixture where canonical and old-local formulas produce different results; assert the dashboard widget now matches canonical output.
- **Test files to update**:
  - `metrics/DashboardWidgetConsistencyTest.kt` — update pace assertions
  - `verification/GoldenMasterVerificationTest.kt` — verify pace parity assertions pass
- **Manual**: Compare dashboard pace card vs insights pace for same account snapshot.

---

## ISSUE-2: Savings Rules Use Effective Ownership-Aware Amount

**Severity**: MAJOR  
**Effort**: Low  
**Priority**: P1  
**Status**: CONFIRMED BUG

### Root Cause Analysis

In `AutomatedSavingsRuleEngine.kt`, three problems exist:

**Problem A — `evaluateRoundUpRule` (lines 109–172)**:
- Guards on `expense.amount <= 0` (line 114), not `expense.effectiveAmount`
- Computes modulo from `expense.amount` (line 128): `expense.amount % roundUpTo`
- Computes ceil from `expense.amount` (line 152): `ceil(expense.amount / roundUpTo) * roundUpTo`
- Uses `expense.amount` in user-facing reason text (line 166)
- **Does NOT check `expense.isNotMine`**

**Problem B — `evaluateSpareChangeRule` (lines 174–192)**:
- Guards on `expense.amount <= 0` (line 179)
- Range check uses `expense.amount in 1.0..10.0` (line 183)
- Uses `expense.amount` in the `RuleExecution.amount` and reason string (lines 186–188)
- **Does NOT check `expense.isNotMine`**

**Problem C — `evaluatePercentageRule` (lines 83–107)**:
- Uses `expense.amount` for income — this is actually *correct* for deposits (no shared-expense semantics expected for income deposits).

```kotlin
// Lines 113-116 — the problematic guard
if (expense.transactionType != TransactionType.PURCHASE || expense.amount <= 0) {
    return null
}
// Line 128 — uses raw amount for modulo
val remainder = expense.amount % roundUpTo
```

**Impact**: 
- A "not mine" purchase (e.g., someone else's coffee on user's card) can trigger savings actions.
- A shared €40 dinner where user's share is €20 will round up based on €40, over-saving.
- Spare change matching on `1..10` checks raw amount, not effective share.

> **NOTE**: The `evaluateWeeklyNoSpendRule` (lines 194–228) already correctly uses `expense.effectiveAmount` (line 208), proving the contract exists — the other rules simply missed it.

### Implementation Strategy

1. In `evaluateRoundUpRule` — replace `expense.amount` with `candidateAmount`:
   ```kotlin
   private fun evaluateRoundUpRule(expense: Expense, rule: AutomatedSavingsRule): RuleExecution? {
       if (expense.transactionType != TransactionType.PURCHASE) return null
       val candidateAmount = expense.effectiveAmount
       if (expense.isNotMine || candidateAmount <= 0.0) return null

       val roundUpTo = rule.roundUpTo ?: 5.0
       // ... existing validity checks on roundUpTo unchanged ...

       val remainder = candidateAmount % roundUpTo
       // ... remainder finite check unchanged ...

       if (remainder > 0) {
           val roundUpAmount = roundUpTo - remainder
           // ... finite check unchanged ...
           val roundedTarget = kotlin.math.ceil(candidateAmount / roundUpTo) * roundUpTo
           // ... finite check unchanged ...
           return RuleExecution(
               rule = rule,
               amount = roundUpAmount,
               reason = "Round up €${String.format("%.2f", candidateAmount)} to €${String.format("%.2f", roundedTarget)}",
               timestamp = timeProvider.now()
           )
       }
       return null
   }
   ```

2. In `evaluateSpareChangeRule` — replace `expense.amount` with `candidateAmount`:
   ```kotlin
   private fun evaluateSpareChangeRule(expense: Expense, rule: AutomatedSavingsRule): RuleExecution? {
       if (expense.transactionType != TransactionType.PURCHASE) return null
       val candidateAmount = expense.effectiveAmount
       if (expense.isNotMine || candidateAmount <= 0.0) return null

       if (candidateAmount in 1.0..10.0) {
           return RuleExecution(
               rule = rule,
               amount = candidateAmount,
               reason = "Spare change: ${expense.merchant} €${String.format("%.2f", candidateAmount)}",
               timestamp = timeProvider.now()
           )
       }
       return null
   }
   ```

### Files to Modify

| File | Change |
|------|--------|
| `domain/savings/AutomatedSavingsRuleEngine.kt` | Update `evaluateRoundUpRule` and `evaluateSpareChangeRule` |

### Dependencies

- Fully independent from all other issues.

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Savings transfer counts drop for users with shared/not-mine records | HIGH | Expected and correct |
| Round-up amounts change for shared expenses | HIGH | Expected (lower, correct) |

### Verification Plan

- **Test file**: `domain/savings/AutomatedSavingsRuleEngineTest.kt`
- **New test cases**:
  - `isNotMine = true` purchase → no `RuleExecution` for ROUND_UP
  - `isNotMine = true` purchase → no `RuleExecution` for SPARE_CHANGE
  - Shared purchase (raw=€40, effective=€20) → round-up based on €20
  - Shared purchase (raw=€8, effective=€4) → spare change saves €4, not €8
  - Fully-owned purchase → unchanged behavior (regression check)
  - Boundary: `effectiveAmount` exactly at 1.0, 10.0 for spare change

---

## ISSUE-3: Merchant Std-Dev Must Use Effective Share

**Severity**: MAJOR  
**Effort**: Low  
**Priority**: P1  
**Status**: CONFIRMED BUG

### Root Cause Analysis

In `InsightsEngine.kt`, the `buildMerchantInsights` function (lines 367–397):

```kotlin
// Line 380 — uses raw `amount`, not `effectiveAmount`
val amounts = purchasesByMerchantKey[ms.merchantName]?.map { it.amount } ?: emptyList()
val stdDev = if (amounts.size >= 3) calculateStdDev(amounts) else null
```

Meanwhile, the DAO query behind `getAllMerchantStats()` computes `totalAmount` using the effective-share `CASE WHEN` expression (ExpenseDao.kt lines 539–541):
```sql
SUM(CASE WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount
         WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0
         ELSE amount END) as totalAmount
```

> **CAUTION — Additional DAO inconsistency discovered**: The same DAO query uses `AVG(amount)` for `averageAmount` and `MIN(amount)/MAX(amount)` — these also use raw `amount`, not effective share. This is a **pre-existing DAO-level inconsistency** that is out of scope for this batch but should be tracked for a future fix.

**Impact**: For merchants with shared expenses, the std-dev will be inflated relative to the totals, skewing the `isLikelyRecurring` confidence check and the variability insight.

### Implementation Strategy

1. Change line 380 to use `effectiveAmount`:
   ```kotlin
   val amounts = purchasesByMerchantKey[ms.merchantName]?.map { it.effectiveAmount } ?: emptyList()
   ```

2. No other changes to the filtering — `!it.isNotMine` is already present in the groupBy filter (line 376).

3. The `< 3 samples` guard remains unchanged (line 381).

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/InsightsEngine.kt` | Line 380: `it.amount` → `it.effectiveAmount` |

### Dependencies

- **Co-land with ISSUE-7** (same file, same semantic contract on ownership/effective-amount). Both edit different functions, no merge conflict risk.

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Std-dev values change for merchants with shared purchases | HIGH | Expected and correct |
| `isLikelyRecurring` classification could change | MEDIUM | Verify with fixture |
| Golden test baselines may shift | LOW | Update expectations |

### Verification Plan

- **Test file**: `domain/analytics/InsightsEngineValidationTest.kt`
- **New test**: Fixture with shared expenses where `amount=100, mySharePercentage=50` → std-dev should use 50, not 100.
- **Regression**: Verify `avg/min/max/total/stdDev` semantic alignment in `MerchantInsight` payload.

---

## ISSUE-4: Correct Yearly Average Baseline Computation

**Severity**: CRITICAL *(upgraded from MAJOR after deep review — unit mismatch guarantees 100% wrong classification)*  
**Effort**: Medium  
**Priority**: P0 — Fix Before Release  
**Status**: CONFIRMED BUG

### Root Cause Analysis

In `TotalsAggregationEngine.kt`, the `getAverageForPeriodType` function (lines 230–273):

```kotlin
// Lines 235-243 — YEAR branch computes MONTHLY average, not YEARLY average
PeriodType.YEAR -> {
    val currentYear = TimePeriodUtils.getYear(now)
    val (startMs, _) = getYearRange(currentYear)
    val allMonths = expenseRepository.getMonthlyTotalsForPeriod(startMs, now)
    if (excludeCurrent) {
        allMonths.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
    } else {
        allMonths.map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
    }
}
```

**The bug**: This computes the **average of monthly totals** within the current year, then this value is used to classify **yearly totals** via `getPeriodStatus(total, average)`.

In `getYearlyTotals()` (line 163–192), yearly totals are compared against this average:
```kotlin
val average = getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = false)
// ...
status = getPeriodStatus(total, average)  // total = yearly total (~€12k), average = monthly average (~€1k)!!
```

**Unit mismatch**: A yearly total of €12,000 compared against a monthly average of €1,000 will **always** be `OVER_AVERAGE`.

**Impact**: Every year is mathematically guaranteed to show `OVER_AVERAGE` status, making the status indicator completely meaningless.

### Implementation Strategy

1. Replace the YEAR branch to compute yearly totals across a multi-year horizon:

```kotlin
PeriodType.YEAR -> {
    val currentYear = TimePeriodUtils.getYear(now)
    // Use 5-year window matching getYearlyTotals()
    val years = (currentYear - 4..currentYear).toList()
    val yearlyTotals = years.map { year ->
        val (startMs, endMs) = getYearRange(year)
        expenseRepository.getTotalForPeriod(startMs, endMs)
    }

    val totalsToAverage = if (excludeCurrent) {
        yearlyTotals.dropLast(1)
    } else {
        yearlyTotals
    }

    // Only include non-zero years so sparse history doesn't dilute
    val nonZeroTotals = totalsToAverage.filter { it > 0.0 }
    nonZeroTotals.takeIf { it.isNotEmpty() }?.average() ?: 0.0
}
```

2. Keep the `NaN-safe` fallback pattern consistent with other branches.

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/TotalsAggregationEngine.kt` | Replace YEAR branch in `getAverageForPeriodType` (lines 235-243) |

### Dependencies

- Fully independent from other issues.
- **Consumer impact**: `HomeViewModel` drill-up flows use `getYearlyTotals()` → their status labels will now be meaningful.

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Yearly status labels change materially | 100% | Expected — they were always wrong before |
| DB calls increase (5 yearly total queries) | LOW | Simple SUM queries over indexed columns |
| Years with sparse data may look inflated | LOW | Filter out zero-total years from average |

### Verification Plan

- **Test files**:
  - `domain/analytics/TotalsAggregationEngineTest.kt`
  - `domain/analytics/TotalsAggregationEngineValidationTest.kt`
- **New tests**:
  - Fixture: Year1=€10k, Year2=€15k, Year3=€12k → average ≈ €12.3k, not ≈ €1k
  - A year below the yearly average → `UNDER_AVERAGE` (was impossible before)
  - `excludeCurrent=true` → current year excluded
  - `excludeCurrent=false` → current year included

---

## ISSUE-5: Runway Baseline Should Use Full Historical Months Only

**Severity**: LOW *(downgraded from MINOR — impact limited to early-month edge case with blended fallback already in place)*  
**Effort**: Medium  
**Priority**: P2  
**Status**: CONFIRMED BUG

### Root Cause Analysis

In `FinancialHealthScoreV2.kt`, `calculateHistoricalMonthlyBaseline` (lines 308–329):

```kotlin
private suspend fun calculateHistoricalMonthlyBaseline(currentPeriodStart: Long): Double? {
    val baselineStart = TimePeriodUtils.addDays(currentPeriodStart, -RUNWAY_BASELINE_LOOKBACK_DAYS) // 90 days back
    val historicalExpenses = expenseRepository
        .getExpensesBetween(baselineStart, currentPeriodStart)
        .filter { ... }

    val monthlyTotals = historicalExpenses
        .groupBy { "${TimePeriodUtils.getYear(it.date)}-${TimePeriodUtils.getMonth(it.date)}" }
        .values
        .map { monthRows -> monthRows.sumOf { it.effectiveAmount } }
        .filter { it > 0.0 }

    return monthlyTotals.takeIf { it.isNotEmpty() }?.average()
}
```

**The bug**: `baselineStart` is calculated as exactly 90 days before the current period start. This date almost always falls mid-month. The resulting partial first month gets a lower sum but is counted as a full month in the average denominator.

**Example**: If `currentPeriodStart` = April 1st, `baselineStart` ≈ January 1st (clean). But if `currentPeriodStart` = April 15th (custom period), `baselineStart` ≈ January 15th — January bucket is partial (Jan 15–31 only), yet averaged as full month.

**Impact**: Baseline underestimation → optimistic runway score → misleading health metric.

### Implementation Strategy

1. After grouping by month, filter to only include fully-enclosed months:

```kotlin
private suspend fun calculateHistoricalMonthlyBaseline(currentPeriodStart: Long): Double? {
    val baselineStart = TimePeriodUtils.addDays(currentPeriodStart, -RUNWAY_BASELINE_LOOKBACK_DAYS)
    val historicalExpenses = expenseRepository
        .getExpensesBetween(baselineStart, currentPeriodStart)
        .filter {
            it.transactionType == TransactionType.PURCHASE &&
            !it.isNotMine &&
            it.date < currentPeriodStart
        }

    if (historicalExpenses.isEmpty()) return null

    val monthlyTotals = historicalExpenses
        .groupBy { "${TimePeriodUtils.getYear(it.date)}-${TimePeriodUtils.getMonth(it.date)}" }
        .filter { (_, monthRows) ->
            // Only include months fully enclosed by [baselineStart, currentPeriodStart)
            val sampleDate = monthRows.first().date
            val monthStart = TimePeriodUtils.getStartOfMonth(sampleDate)
            val monthEnd = TimePeriodUtils.getEndOfMonth(sampleDate)
            monthStart >= baselineStart && monthEnd <= currentPeriodStart
        }
        .values
        .map { monthRows -> monthRows.sumOf { it.effectiveAmount } }
        .filter { it > 0.0 }

    return monthlyTotals.takeIf { it.isNotEmpty() }?.average()
}
```

2. If no full months remain, return `null` — existing neutral handling (line 264) already covers this scenario.

### Files to Modify

| File | Change |
|------|--------|
| `domain/health/FinancialHealthScoreV2.kt` | Update `calculateHistoricalMonthlyBaseline` (lines 308-329) |

### Dependencies

- Fully independent from other issues.

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Fewer baseline months → more null baselines | MEDIUM | Existing neutral fallback handles this |
| Runway scores shift | LOW | Toward more accurate values |

### Verification Plan

- **Test file**: `domain/health/FinancialHealthScoreV2Test.kt`
- **New tests**:
  - Lookback starting mid-month: partial first month excluded, baseline from remaining full months
  - All months partial (very short history) → returns null → neutral score fallback
  - Round boundary (lookback starts exactly at month start) → all months included

---

## ISSUE-6: Bound Transfer Analytics Memory and Dedupe Repeated Detections

**Severity**: MEDIUM *(upgraded from MINOR — unbounded memory + metric inflation compound over time)*  
**Effort**: Medium–High  
**Priority**: P2  
**Status**: CONFIRMED BUG

### Root Cause Analysis

In `TransferDirectionAnalytics.kt`:

**Problem A — Unbounded maps** (lines 58–59):
```kotlin
private val autoDetectedDirectionByTransferId = ConcurrentHashMap<Long, TransferDirection>()
private val correctionAppliedByTransferId = ConcurrentHashMap<Long, Boolean>()
```
Never pruned except via `reset()`. In long sessions, grows without bound.

**Problem B — No idempotency guard** (lines 64–134):
`recordAutoDetection` always increments all counters. If same `transferId` is processed twice (notification reprocessing), all metrics inflate:

```kotlin
// Line 87-88: always increments — no dedup guard
_insights.update { current ->
    val newTotalTransfers = current.totalTransfers + 1  // always +1
    // ...
}
```

**Impact**: Memory grows linearly; detection rate/accuracy/top-sources become unreliable.

### Implementation Strategy

1. **Add idempotency guard** for known transfer IDs:
```kotlin
fun recordAutoDetection(
    direction: TransferDirection,
    accountName: String?,
    wasCorrect: Boolean = true,
    transferId: Long? = null
) {
    // Idempotency: skip if already recorded with same direction
    if (transferId != null) {
        val existing = autoDetectedDirectionByTransferId[transferId]
        if (existing != null) return  // already tracked, no metric inflation
    }

    transferId?.let { id ->
        autoDetectedDirectionByTransferId[id] = direction
        correctionAppliedByTransferId.remove(id)
    }

    // ... rest of counter increment logic unchanged ...

    maybeEvictOldest()
}
```

2. **Add bounded retention** with eviction:
```kotlin
companion object {
    private const val MAX_TRACKED_TRANSFERS = 10_000
}

private fun maybeEvictOldest() {
    if (autoDetectedDirectionByTransferId.size > MAX_TRACKED_TRANSFERS) {
        val toRemove = autoDetectedDirectionByTransferId.keys.take(MAX_TRACKED_TRANSFERS / 5)
        toRemove.forEach { id ->
            autoDetectedDirectionByTransferId.remove(id)
            correctionAppliedByTransferId.remove(id)
        }
    }
}
```

3. Also bound `incomingSources` and `outgoingDestinations` at ~1000 entries.

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/TransferDirectionAnalytics.kt` | Add idempotency guard + map bounding |
| `data/repository/NotificationProcessingPipeline.kt` | Pass `transferId` to `recordUnknownDirection` if new overload added |

### Dependencies

- If adding `recordUnknownDirection(transferId)` overload, pipeline call-site needs coordination.
- Otherwise independent.

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Eviction removes transfer IDs that later need correction | LOW | 10k cap is generous; corrections are rare |
| ConcurrentHashMap `take()` random eviction | LOW | Approximate eviction is acceptable for analytics |

### Verification Plan

- **New test file**: `domain/analytics/TransferDirectionAnalyticsTest.kt`
- **Test cases**:
  - Same `transferId` recorded twice → counters increment only once
  - Null `transferId` → still increments (best-effort, non-idempotent)
  - Map cap → size stays bounded after many records
  - Correction still works for tracked transfer IDs
  - Correction for evicted IDs → no-op

---

## ISSUE-7: Explicit Ownership Filtering in Historical Monthly Averages

**Severity**: MEDIUM *(upgraded from MINOR — affects all monthly average denominators, biases pace + category insights)*  
**Effort**: Low  
**Priority**: P1  
**Status**: CONFIRMED BUG

### Root Cause Analysis

In `InsightsEngine.kt`, two helper functions omit the `!isNotMine` filter:

**`calculateCategoryMonthlyAverages` (lines 341–345)**:
```kotlin
val purchases = allExpenses.filter {
    it.transactionType == TransactionType.PURCHASE
            && it.categoryId != null
            && it.date < currentMonth.startMs  // ← no !isNotMine
}
```

**`calculateAverageMonthlySpend` (lines 432–435)**:
```kotlin
val purchases = allExpenses.filter {
    it.transactionType == TransactionType.PURCHASE
            && it.date < currentMonth.startMs  // ← no !isNotMine
}
```

Although both sum `effectiveAmount` (which returns 0.0 for `isNotMine`), the `isNotMine` transactions still **create month buckets** in the `monthTotals` map. A month with *only* not-mine transactions gets `monthTotals["2026-03"] = 0.0` → inflates the denominator.

**Impact**: Historical monthly averages for category insights (`CategoryInsight.averageOverMonths`) and pace enrichment (`SpendingPace.averageMonthlyTotal`) are biased downward.

### Implementation Strategy

1. Add `!it.isNotMine` to both filters:

```kotlin
// calculateCategoryMonthlyAverages, lines 341-345
val purchases = allExpenses.filter {
    it.transactionType == TransactionType.PURCHASE
            && !it.isNotMine
            && it.categoryId != null
            && it.date < currentMonth.startMs
}

// calculateAverageMonthlySpend, lines 432-435
val purchases = allExpenses.filter {
    it.transactionType == TransactionType.PURCHASE
            && !it.isNotMine
            && it.date < currentMonth.startMs
}
```

2. Keep `effectiveAmount` summing unchanged — defense-in-depth.

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/InsightsEngine.kt` | Add `!it.isNotMine` to two filter blocks |

### Dependencies

- **Co-land with ISSUE-3** (same file, same semantic ownership contract).

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Monthly averages shift upward | MEDIUM | Expected and correct |
| Fewer months in average for affected users | LOW | More accurate denominator |

### Verification Plan

- **Test file**: `domain/analytics/InsightsEngineValidationTest.kt`
- **New tests**:
  - Month with only `isNotMine=true` purchases → not in average denominator
  - Mixed month → month appears, sum uses own `effectiveAmount` only
  - No not-mine purchases → unchanged (regression check)
- **Cross-verification**: `verification/CrossSourceVerificationTest.kt` — pace parity tests

---

---

## ISSUE-8: AdvancedAnalyticsEngine Uses Hardcoded Dispatchers

**Severity**: MEDIUM  
**Effort**: Low  
**Priority**: P2  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

`AdvancedAnalyticsEngine` uses `Dispatchers.Default` and `Dispatchers.IO` directly in 4+ methods instead of injected `@DefaultDispatcher` / `@IoDispatcher` qualifiers established by `DispatchersModule`:

```kotlin
// Line 132 — hardcoded dispatcher
suspend fun getCategoryAnalytics(period: PeriodRange): List<EnhancedCategoryAnalytics> =
    withContext(Dispatchers.Default) { ... }

// Line 228
suspend fun getMerchantAnalytics(...): List<EnhancedMerchantAnalytics> =
    withContext(Dispatchers.Default) { ... }

// Line 322
suspend fun getSpendingPatterns(period: PeriodRange): SpendingPatternAnalysis =
    withContext(Dispatchers.Default) { ... }

// Line 419
suspend fun getStatisticalInsights(period: PeriodRange): StatisticalInsights =
    withContext(Dispatchers.Default) { ... }
```

**Impact**: Makes these methods impossible to test with `TestCoroutineDispatcher`. Violates the DI dispatcher pattern used everywhere else in the codebase.

### Implementation Strategy

1. Add dispatcher injection to constructor:
```kotlin
@Singleton
class AdvancedAnalyticsEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val timeProvider: TimeProvider,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) {
```

2. Replace all `Dispatchers.Default` → `defaultDispatcher` in `withContext` calls.

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/AdvancedAnalyticsEngine.kt` | Add injected dispatcher, replace 4 hardcoded references |

### Verification Plan

- Ensure existing tests pass.
- No new test required — this is an infrastructure change that enables future testability.

---

## ISSUE-9: TotalsAggregationEngine Uses Hardcoded Dispatchers

**Severity**: MEDIUM  
**Effort**: Low  
**Priority**: P2  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

Same pattern as ISSUE-8. All public methods in `TotalsAggregationEngine` use `withContext(Dispatchers.IO)` directly (lines 32, 59, 106, 137, 163, 194, 230).

### Implementation Strategy

1. Inject `@IoDispatcher` via constructor:
```kotlin
@Singleton
class TotalsAggregationEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
```

2. Replace all `Dispatchers.IO` → `ioDispatcher`.

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/TotalsAggregationEngine.kt` | Add injected dispatcher, replace 7 hardcoded references |

### Dependencies

- **Co-land with ISSUE-4** (same file). No merge conflict risk — ISSUE-4 changes the YEAR branch body, this changes `withContext` wrappers.

---

## ISSUE-10: SmartSavingsEngine Hardcodes €500 Discretionary Baseline

**Severity**: MEDIUM  
**Effort**: Medium  
**Priority**: P2  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

In `SmartSavingsEngine.kt` line 186:

```kotlin
val discretionary = 500.0 * horizonMultiplier // Assume €500/month discretionary baseline
```

The Monte Carlo savings recommendation assumes a fixed €500/month discretionary budget regardless of the user's actual spending. For users spending €200/month or €2000/month, this produces meaningless save-amount recommendations.

### Implementation Strategy

1. Derive discretionary baseline from actual historical spend:

```kotlin
private suspend fun runMonteCarloSimulation(
    goal: SavingsGoal,
    now: Long,
    timeHorizon: TimeHorizon
): Double {
    // ... existing code for spentToDate ...

    // Derive actual discretionary baseline from 3-month rolling average
    val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
    val historicalPurchases = expenseRepository.getExpensesBetween(threeMonthsAgo, now)
        .asSequence()
        .filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
        .sumOf { it.effectiveAmount }
    val monthlyDiscretionary = if (historicalPurchases > 0) {
        historicalPurchases / 3.0
    } else {
        500.0 // fallback when no history
    }

    val discretionary = monthlyDiscretionary * horizonMultiplier
    // ... rest unchanged ...
}
```

2. Keep €500 as fallback for users with no spending history.

### Files to Modify

| File | Change |
|------|--------|
| `domain/savings/SmartSavingsEngine.kt` | Replace hardcoded €500 with historical average |

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Save-amount recommendations change | HIGH | Expected and more accurate |
| Extra DB query | LOW | Reuse existing repository method |

---

## ISSUE-11: AdvancedAnalyticsEngine Price Trend Uses Raw Amount

**Severity**: LOW  
**Effort**: Low  
**Priority**: P3  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

In `AdvancedAnalyticsEngine.kt` lines 695–696, `analyzePriceTrend` uses `amount` instead of `effectiveAmount`:

```kotlin
val first = sorted.first().amount
val last = sorted.last().amount
```

For shared expenses, this shows the full card amount rather than the user's share, misrepresenting price trends for frequently-split merchants.

### Implementation Strategy

Change to `effectiveAmount`:
```kotlin
val first = sorted.first().effectiveAmount
val last = sorted.last().effectiveAmount
```

Also change line 700:
```kotlin
val allSame = sorted.all { kotlin.math.abs(it.effectiveAmount - first) < 0.001 }
```

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/AdvancedAnalyticsEngine.kt` | Lines 695–700: `amount` → `effectiveAmount` |

---

## ISSUE-12: CategoryInsightEngine Unbounded Diagnostic Map

**Severity**: LOW  
**Effort**: Low  
**Priority**: P3  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

`CategoryInsightEngine.kt` line 14:

```kotlin
private val missingCategoryHitCount = ConcurrentHashMap<Long, Int>()
```

This diagnostic map accumulates entries forever in the `@Singleton` lifetime. If many categories are deleted and their IDs reused in stale transactions, the map grows without bound.

### Implementation Strategy

Clear the map at the start of each `calculate()` call (it only tracks per-invocation stats for logging):

```kotlin
fun calculate(...): List<CategoryInsight> {
    missingCategoryHitCount.clear()  // Reset per-invocation diagnostic
    // ... rest unchanged
}
```

Alternatively, keep cumulative tracking but cap at 100 entries and log+prune when exceeded.

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/CategoryInsightEngine.kt` | Add `clear()` or size cap |

---

## ISSUE-13: AutomatedSavingsRuleEngine Monthly Cap Map Never Pruned

**Severity**: LOW  
**Effort**: Low  
**Priority**: P3  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

`AutomatedSavingsRuleEngine.kt` line 53:

```kotlin
private val monthToDateRuleTotals = mutableMapOf<String, Double>()
```

Keys are `"ruleKey-YYYY-M"`. Old months are never removed. Over months/years this accumulates stale entries in a `@Singleton`.

### Implementation Strategy

Prune old month entries inside the `applyMonthlyCap` method:

```kotlin
private suspend fun applyMonthlyCap(rule: AutomatedSavingsRule, execution: RuleExecution?): RuleExecution? {
    val pendingExecution = execution ?: return null
    val cap = rule.maximumPerMonth ?: return pendingExecution
    if (cap <= 0.0) return null

    return monthlyCapMutex.withLock {
        val monthKey = buildMonthKey(pendingExecution.timestamp)

        // Prune stale months (keep current + previous only)
        val keySuffix = "-$monthKey"
        val prevMonthKey = buildMonthKey(pendingExecution.timestamp - 32L * 24 * 60 * 60 * 1000)
        val prevSuffix = "-$prevMonthKey"
        monthToDateRuleTotals.keys.removeAll { key ->
            !key.endsWith(keySuffix) && !key.endsWith(prevSuffix)
        }

        // ... rest of existing logic unchanged ...
    }
}
```

### Files to Modify

| File | Change |
|------|--------|
| `domain/savings/AutomatedSavingsRuleEngine.kt` | Add pruning in `applyMonthlyCap` |

---

## ISSUE-14: SmartSavingsEngine Calendar Inconsistency with TimePeriodUtils

**Severity**: LOW  
**Effort**: Low  
**Priority**: P3  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

`SmartSavingsEngine.kt` lines 86–97 and 152–160 construct `java.util.Calendar` objects manually for month-start calculations instead of using `TimePeriodUtils.getStartOfMonth()`. If timezone semantics differ between `Calendar.getInstance()` and `TimePeriodUtils` (which uses its own zone handling), month boundaries could disagree.

### Implementation Strategy

Replace manual Calendar with `TimePeriodUtils`:

```kotlin
// In analyzeSpendingPace:
val now = timeProvider.now()
val monthStart = TimePeriodUtils.getStartOfMonth(now)
val dayOfMonth = TimePeriodUtils.getDayOfMonth(now).coerceAtLeast(1)
val daysInMonth = TimePeriodUtils.getDaysInMonth(now)
```

Same for `runMonteCarloSimulation`.

### Files to Modify

| File | Change |
|------|--------|
| `domain/savings/SmartSavingsEngine.kt` | Replace Calendar with TimePeriodUtils in 2 methods |

---

## ISSUE-15: Duplicate DashboardExpense.toEntityExpense() Mappers

**Severity**: LOW  
**Effort**: Low  
**Priority**: P3  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

Two nearly identical `DashboardExpense.toEntityExpense()` extension functions exist:

- `InsightsEngine.kt` lines 701–720
- `ComputeDashboardWidgetsUseCase.kt` lines 738–757

If one is updated without the other, field mapping silently diverges, causing analytics to compute on different data than the dashboard.

### Implementation Strategy

1. Extract to a shared location:

```kotlin
// domain/model/dashboard/DashboardExpenseMapper.kt
package com.yourname.expensetracker.domain.model.dashboard

fun DashboardExpense.toEntityExpense(): Expense {
    val txType = when (transactionType) {
        DashboardTransactionType.PURCHASE -> TransactionType.PURCHASE
        DashboardTransactionType.WITHDRAWAL -> TransactionType.WITHDRAWAL
        DashboardTransactionType.TRANSFER -> TransactionType.TRANSFER
        DashboardTransactionType.DEPOSIT -> TransactionType.DEPOSIT
        DashboardTransactionType.UNKNOWN -> TransactionType.UNKNOWN
    }
    return Expense(
        id = id,
        amount = amount,
        merchant = merchant,
        transactionType = txType,
        date = date,
        categoryId = categoryId,
        isNotMine = isNotMine,
        isManualEntry = isManualEntry,
        merchantKey = MerchantKeyGenerator.generate(merchant)
    )
}
```

2. Delete the private copies from both files; import the shared one.

### Files to Modify

| File | Change |
|------|--------|
| `domain/model/dashboard/DashboardExpenseMapper.kt` | **NEW** — shared mapper |
| `domain/analytics/InsightsEngine.kt` | Delete private `toEntityExpense`, import shared |
| `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Delete private `toEntityExpense`, import shared |

---

## ISSUE-16: SpendingPaceCalculator Projection Discontinuity on Day 3→4

**Severity**: LOW  
**Effort**: Low  
**Priority**: P3  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

In `SpendingPaceCalculator.kt` lines 97–105:

```kotlin
private fun calculateProjectedTotal(monthSpent: Double, dayOfMonth: Int, daysInMonth: Int): Double {
    return if (dayOfMonth >= 4) {
        monthSpent * daysInMonth.toDouble() / dayOfMonth               // Linear extrapolation
    } else if (dayOfMonth > 0) {
        monthSpent * (daysInMonth.toDouble() / 10.0).coerceAtLeast(1.0) // Arbitrary damping
    } else {
        monthSpent
    }
}
```

**Example** (30-day month, €100 spent):
- Day 3: `100 × 3.0 = €300`
- Day 4: `100 × 30/4 = €750`

The projection jumps **2.5×** overnight, creating a jarring user experience.

### Implementation Strategy

Use a blended formula that transitions smoothly:

```kotlin
private fun calculateProjectedTotal(monthSpent: Double, dayOfMonth: Int, daysInMonth: Int): Double {
    if (dayOfMonth <= 0) return monthSpent
    val linearProjection = monthSpent * daysInMonth.toDouble() / dayOfMonth
    if (dayOfMonth >= 7) return linearProjection  // Full confidence after first week

    // Blend: low confidence early → high confidence as week progresses
    val weight = dayOfMonth.toDouble() / 7.0
    val conservativeEstimate = monthSpent * (daysInMonth.toDouble() / 10.0).coerceAtLeast(1.0)
    return (weight * linearProjection) + ((1.0 - weight) * conservativeEstimate)
}
```

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/SpendingPaceCalculator.kt` | Replace `calculateProjectedTotal` with blended formula |

### Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Projected totals change for days 1–6 | HIGH | Expected, smoother UX |
| SpendingPaceCalculatorValidationTest assertions shift | MEDIUM | Update expected values |

---

## ISSUE-17: ComputeDashboardWidgetsUseCase God Method

**Severity**: MEDIUM  
**Effort**: High  
**Priority**: P2 (defer to dedicated refactoring sprint)  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

`ComputeDashboardWidgetsUseCase.compute()` is ~400 lines handling 15+ widget computations inline. This violates single-responsibility, makes individual widgets untestable in isolation, and is the root cause of ISSUE-1 (the divergent pace formula exists because the method is too large to spot duplication).

### Implementation Strategy (outline — full decomposition is a separate task)

1. Extract each widget into a dedicated builder:
   - `FinancialRunwayWidgetBuilder`
   - `SpendingPaceWidgetBuilder`
   - `MonteCarloWidgetBuilder`
   - `BlockPartyWidgetBuilder`
   - `SpendingTrendWidgetBuilder`
   - `StreakWidgetBuilder`
   - `HealthScoreWidgetBuilder`

2. The main `compute()` method becomes an orchestrator:
```kotlin
suspend fun compute(data: ProcessedDashboardData): CompiledDashboardData {
    val context = DashboardComputeContext(data, timeProvider.now())
    return CompiledDashboardData(
        allWidgets = buildList {
            add(financialWeatherBuilder.build(context))
            add(moneyRadarBuilder.build(context))
            stressForecastBuilder.build(context)?.let { add(it) }
            // ... etc
        },
        totalSpent = context.totalSpent,
        txCount = context.txCount
    )
}
```

3. Each builder is independently testable.

### Files to Modify

| File | Change |
|------|--------|
| `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | Decompose into builders |
| `domain/usecase/dashboard/builders/*.kt` | **NEW** — 7+ widget builder files |

### Note

This is a refactoring task with no behavioral change. It should be done **after** ISSUE-1 is fixed (so the pace widget builder uses canonical pace from the start) and **after** all other bug fixes are stable.

---

## ISSUE-18: AdvancedAnalyticsEngine Verbose Debug Logging

**Severity**: LOW  
**Effort**: Low  
**Priority**: P3  
**Status**: NEW — discovered during deep review

### Root Cause Analysis

`AdvancedAnalyticsEngine.kt` lines 461–477 contain extensive `Timber.d()` calls that log detailed financial data (totals, period ranges, daily totals). While Timber strips debug logs in release builds, the string formatting and map `toString()` allocations occur before Timber's level check, adding GC pressure in production.

### Implementation Strategy

Wrap in a debug guard or use Timber's lazy evaluation:

```kotlin
if (Timber.forest().isNotEmpty()) {
    Timber.d("=== STATISTICAL INSIGHTS DEBUG ===")
    // ... logging ...
}
```

Or simpler — just remove the block and rely on unit tests for validation. The data is transient and reconstructable from fixtures.

### Files to Modify

| File | Change |
|------|--------|
| `domain/analytics/AdvancedAnalyticsEngine.kt` | Remove or guard debug logging block (lines 461-477) |

---

## Cross-Issue Regression Gate

After all 18 issues are implemented, run the following focused test suites:

| Suite | Issues Covered | Priority |
|-------|---------------|----------|
| `AutomatedSavingsRuleEngineTest` | ISSUE-2, ISSUE-13 | P1 |
| `InsightsEngineValidationTest` | ISSUE-3, ISSUE-7, ISSUE-15, insights contract | P1 |
| `InsightsEngineDeepTest` | Edge cases in insights engine | P1 |
| `SpendingPaceCalculatorValidationTest` | ISSUE-1, ISSUE-16 canonical pace contract | P0 |
| `SpendingPaceCalculatorDeepTest` | Pace edge cases, projection smoothing | P0 |
| `TotalsAggregationEngineValidationTest` | ISSUE-4, ISSUE-9 | P0 |
| `TotalsAggregationEngineTest` | Yearly totals integration | P0 |
| `FinancialHealthScoreV2Test` | ISSUE-5 runway baseline | P2 |
| `TransferDirectionAnalyticsTest` **[NEW]** | ISSUE-6 idempotency + bounding | P2 |
| `AdvancedAnalyticsEngineTest` | ISSUE-8, ISSUE-11, ISSUE-18 | P2 |
| `SmartSavingsEngineTest` | ISSUE-10, ISSUE-14 | P2 |
| `GoldenMasterVerificationTest` | End-to-end metric parity | Gate |
| `CrossSourceVerificationTest` | Cross-engine consistency | Gate |
| `DashboardWidgetConsistencyTest` | ISSUE-1, ISSUE-17 dashboard pace | P0 |
| `EffectiveAmountConsistencyTest` | Overall effective-amount contract | Gate |

---

## Execution Order & Dependencies

**Recommended order** (to reduce merge conflicts and risk):

### Phase 1: Critical Bugs (P0) — must fix before release

```
1. ISSUE-1  (dashboard pace canonicalization — highest user visibility)
     ↓
2. ISSUE-4 + ISSUE-9  (yearly classification fix + inject dispatchers in same file)
```

### Phase 2: Amount Semantics (P1) — fix this sprint

```
3. ISSUE-2 + ISSUE-13  (savings rules: effectiveAmount + monthly cap pruning, same file)
     ↓
4. ISSUE-3 + ISSUE-7  (InsightsEngine: merchant std-dev + monthly average filters, same file)
```

### Phase 3: Hardening (P2) — fix this milestone

```
5. ISSUE-5   (health baseline partial months)
     ↓
6. ISSUE-6   (transfer analytics idempotency + bounding)
     ↓
7. ISSUE-8   (AdvancedAnalyticsEngine inject dispatchers)
     ↓
8. ISSUE-10  (SmartSavingsEngine discretionary baseline)
     ↓
9. ISSUE-17  (dashboard god method decomposition — do after all bug fixes stable)
```

### Phase 4: Polish (P3) — backlog

```
10. ISSUE-11  (price trend raw amount)
11. ISSUE-12  (CategoryInsightEngine unbounded map)
12. ISSUE-14  (SmartSavingsEngine Calendar consistency)
13. ISSUE-15  (duplicate toEntityExpense mapper)
14. ISSUE-16  (pace projection discontinuity smoothing)
15. ISSUE-18  (debug logging cleanup)
     ↓
16. Cross-issue regression gate
```

**Logical couplings**:
- ISSUE-1 relies on canonical pace behavior tested by existing parity suites.
- ISSUE-3 and ISSUE-7 should be co-landed (same file, same semantic ownership contract).
- ISSUE-2 and ISSUE-13 should be co-landed (same file, savings rule engine hardening).
- ISSUE-4 and ISSUE-9 should be co-landed (same file, avoid double-editing TotalsAggregationEngine).
- ISSUE-6 may require light call-site update if transfer-id-aware unknown tracking is introduced.
- ISSUE-17 (god method refactor) should be done **last** — it's a large refactor that would conflict with any pending changes to `ComputeDashboardWidgetsUseCase`.
- ISSUE-15 (shared mapper) can be done alongside ISSUE-1 since both touch `ComputeDashboardWidgetsUseCase`.

---

## Rollback & Safety

- **Separate commits**: Each issue (or co-landed pair) in its own commit for surgical rollback.
- **Additive guards**: Prefer adding `!isNotMine` checks and delegating to canonical calculators over rewriting APIs.
- **Before/after KPI snapshots**: For ISSUE-1/4/5/7/10/16, capture fixture-based before/after values in verification notes.
- **If release risk is high**:
  - ISSUE-1: Temporarily gate widget-source switch behind internal flag (short-lived)
  - Keep old/new computed values in debug logs for one canary cycle
  - Revert individual issue commit without touching unrelated fixes
- **Low-risk issues (P3)**: Issues 11, 12, 14, 15, 16, 18 are safe to defer if sprint capacity is limited — they cause no data corruption, only suboptimal memory/consistency.

---

## Open Assumptions

> **A1 — Dashboard pace baseline**: Should the dashboard pace widget strictly follow the canonical previous-month daily-rate baseline from `SpendingPaceCalculator`? Or should it optionally use budget-amount as baseline? **Product confirmation needed.**

> **A2 — YEAR average horizon**: Should the yearly average use the same 5-year window as `getYearlyTotals()`, or a broader horizon? **Recommend aligning at 5 years.**

> **A3 — Transfer map retention cap**: What size cap for `autoDetectedDirectionByTransferId`? Proposed: **10,000** entries. Needs performance/product telemetry to validate.

> **A4 — Duplicate detection with changed direction**: If same `transferId` re-detected with *different* direction: (a) ignore, or (b) adjust counters? **Proposed: ignore (let user correction handle it).**

> **A5 — DAO `averageAmount`/`minAmount`/`maxAmount` raw-amount inconsistency**: The DAO `getAllMerchantStats()` query uses `AVG(amount)` (raw), not effective-share, for these fields. This is a broader inconsistency beyond ISSUE-3's std-dev fix. **Recommend tracking as a separate future issue.**

> **A6 — Discretionary baseline derivation (ISSUE-10)**: Should Monte Carlo use a 3-month rolling average, or should it factor in budget limits? If the user has budget data, budget may be more intentional. **Proposed: use spending average, fallback to budget, fallback to €500.**

> **A7 — Pace blending window (ISSUE-16)**: The proposed smooth-blending formula uses days 1–7 as the ramp-up. Should this be configurable, or is 7 days a good fixed threshold? **Recommend fixed at 7 — matches weekly salary cycles.**

> **A8 — God method decomposition scope (ISSUE-17)**: Should widget builders be in `domain/usecase/dashboard/builders/` or as inner classes? Recommend standalone files for independent testability.

---

## Acceptance Criteria

### P0 — Critical
- [ ] **ISSUE-1**: Dashboard `SpendingPaceWidget` uses canonical pace output; no independent formula recomputation in `ComputeDashboardWidgetsUseCase`.
- [ ] **ISSUE-4**: `getAverageForPeriodType(PeriodType.YEAR)` returns a yearly-unit average; yearly status classification is unit-consistent.

### P1 — Must Fix
- [ ] **ISSUE-2**: `ROUND_UP` and `SPARE_CHANGE` rules use `effectiveAmount`, explicitly exclude `isNotMine`, with test coverage for shared/not-mine cases.
- [ ] **ISSUE-3**: Merchant std-dev computed from `effectiveAmount` values, aligning with merchant total semantics.
- [ ] **ISSUE-7**: `calculateCategoryMonthlyAverages` and `calculateAverageMonthlySpend` explicitly filter `!isNotMine`; denominator bias tests pass.

### P2 — Hardening
- [ ] **ISSUE-5**: Historical runway baseline excludes partial edge months; tests validate edge-month scenarios.
- [ ] **ISSUE-6**: Transfer analytics per-transfer maps are bounded; repeated `recordAutoDetection` calls for the same `transferId` do not inflate totals.
- [ ] **ISSUE-8**: `AdvancedAnalyticsEngine` uses injected `@DefaultDispatcher` instead of `Dispatchers.Default`.
- [ ] **ISSUE-9**: `TotalsAggregationEngine` uses injected `@IoDispatcher` instead of `Dispatchers.IO`.
- [ ] **ISSUE-10**: Monte Carlo savings recommendation uses user's historical spend, not hardcoded €500.
- [ ] **ISSUE-17**: `ComputeDashboardWidgetsUseCase.compute()` is decomposed into individually-testable widget builders.

### P3 — Polish
- [ ] **ISSUE-11**: `analyzePriceTrend` uses `effectiveAmount` instead of `amount`.
- [ ] **ISSUE-12**: `CategoryInsightEngine.missingCategoryHitCount` is bounded or reset per-invocation.
- [ ] **ISSUE-13**: `monthToDateRuleTotals` is pruned of stale month entries.
- [ ] **ISSUE-14**: `SmartSavingsEngine` uses `TimePeriodUtils` instead of `java.util.Calendar`.
- [ ] **ISSUE-15**: Single shared `DashboardExpense.toEntityExpense()` mapper; no duplicates.
- [ ] **ISSUE-16**: `calculateProjectedTotal` transitions smoothly across all days of the month.
- [ ] **ISSUE-18**: Debug logging block removed or guarded behind level check.

### Gate
- [ ] **Cross-module**: All regression suites pass with documented, expected metric deltas.
