# Semantic Contract Map: Analytics Components

**Document Version:** 1.0  
**Created:** 2024  
**Purpose:** Map ALL metric calculations across analytics components and document which should match, which should differ, and why.

---

## Executive Summary

This document traces **7 major metric groups** across **12 analytics components**. Each metric is extracted with its EXACT formula, time window, transaction filter, and amount basis. Components are grouped by semantic similarity, and intentional differences are documented.

**Key Finding:** Most daily-rate metrics use the same canonical formula, but aggregations differ in time windows (rolling 30d vs calendar month vs month-to-date).

---

## Component Inventory

### Analytics Engines
- **AdvancedAnalyticsEngine** (924 lines) – Category/merchant analytics, statistical insights
- **InsightsEngine** (751 lines) – Monthly comparison, spending pace, anomaly detection  
- **SpendingPaceCalculator** (104 lines) – Pace calculation, projection
- **TotalsAggregationEngine** (317 lines) – Daily/weekly/monthly/yearly totals
- **AdvancedAnalyticsDashboard** (310 lines) – Top categories, merchants, trends

### Savings & Budget Engines
- **SmartSavingsEngine** (218 lines) – Safe to save, budget surplus
- **BudgetForecastingEngine** (329 lines) – Predicted spending, trend analysis
- **CashFlowCalculator** (171 lines) – Daily cash flow, recurring predictions

### Dashboard & Synthesis
- **ComputeDashboardWidgetsUseCase** (643 lines) – Widget compilation, pace widget creation
- **DashboardFollowThroughEngine** (260 lines) – Navigation recommendations
- **FinancialWeatherRepository** (258 lines) – Weather synthesis, forecast composition

### Supporting Models
- **AnalyticsModels.kt** (297 lines) – SpendingPace, MonthlyComparison, CategoryInsight
- **AdvancedAnalyticsModels.kt** (242 lines) – StatisticalInsights, EnhancedCategoryAnalytics
- **AnalyticsRepository.kt** (166 lines) – SpendingSummary, category breakdown

---

## Metric Group 1: Daily Rate / Average Spending

### Variant A: Period-Total ÷ Calendar Days (Canonical)
- **Formula:** `totalSpent / calendarDaysInPeriod`
- **Components:**
  - **InsightsEngine.buildSpendingPace()** [line 437-441]
    - currentDailyRate = currentSpent / dayOfMonth
    - baselineDailyRate = previousTotal / daysInPreviousMonth
  - **SpendingPaceCalculator.calculate()** [line 54-56]
    - currentDailyRate = monthSpent / currentDay
    - baselineDailyRate = previousMonthSpent / previousMonthDays
  - **AdvancedAnalyticsEngine.getStatisticalInsights()** [line 457]
    - averageDailySpend = totalAmount / periodDays

- **Time Window:** Calendar month (current month + previous month)
- **Transaction Filter:** PURCHASE only (isNotMine excluded)
- **Amount Basis:** effectiveAmount
- **Should Match:** YES – All three use identical canonical formula
- **Semantic Meaning:** "How much am I spending per day on average?"

### Variant B: Recent Daily Average (Rolling 30 Days)
- **Formula:** `totalSpentLast30Days / 30`
- **Components:**
  - **ComputeDashboardWidgetsUseCase.compute()** [line 236]
    - averageDailyBurn = monthSpent / dayOfMonth (this month only)
  - **TotalsAggregationEngine.getAverageForPeriodType()** [line 264-266]
    - For DAY: `expenseRepository.getAverageDailySpend(startMs, now)` (last 30 days)

- **Time Window:** Last 30 days / current month to-date
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** effectiveAmount
- **Should Match:** PARTIAL – ComputeDashboard uses MTD, TotalsAggregation uses 30d rolling
- **Semantic Meaning:** "What's my current burn rate right now?"

### Variant C: Historical Monthly Average ÷ Days per Month
- **Formula:** `historicalAverage / 30`
- **Components:**
  - **SmartSavingsEngine.analyzeSpendingPace()** [line 106]
    - averageDailySpending = totalSpent / dayOfMonth
    - Then compares to last 3 months average
  - **BudgetForecastingEngine.calculatePredictedSpending()** [line 164]
    - prediction = historicalData.averageMonthly * months (where months = forecastPeriodDays/30)

- **Time Window:** Historical (3-6 months) vs forward 30 days
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** amount
- **Should Match:** NO – Different time windows (historical vs forward)
- **Semantic Meaning:** "Based on my history, how much do I spend daily?"

### Intentional Differences in Daily Rate Metrics

| Component A | Component B | Reason | Expected Direction |
|-------------|-------------|--------|-------------------|
| InsightsEngine (canonical) | SmartSavingsEngine (3-month avg) | SmartSavings is conservative, uses historical | SmartSavings typically < InsightsEngine because it smooths volatility |
| ComputeDashboard (MTD) | TotalsAggregation (30d rolling) | Dashboard needs current sprint clarity, TotalsAgg needs stability | TotalsAgg typically > Dashboard on high-spending days |
| SpendingPaceCalculator (month-to-date) | BudgetForecastingEngine (historical) | Pace uses actual current rate, Budget uses trend | Budget can smooth noise in early month |

---

## Metric Group 2: Monthly Total / Period Total

### Variant A: Calendar Month Total (Standard)
- **Formula:** SUM(expenses WHERE date >= monthStart AND date < monthEnd)
- **Components:**
  - **InsightsEngine.buildMonthlyComparison()** [line 246-254]
    - currentTotal = expenseRepository.getTotalForPeriod(month.startMs, month.endMs)
  - **InsightsEngine.buildSpendingPace()** [line 412]
    - currentSpent = expenseRepository.getTotalForPeriod(currentMonth.startMs, currentMonth.endMs)
  - **SpendingPaceCalculator.calculate()** [line 31-37]
    - monthSpent = allExpenses filter (date >= monthStart && transactionType == PURCHASE && !isNotMine)
  - **ComputeDashboardWidgetsUseCase.compute()** [line 179-181]
    - monthSpent = totalSpent (filtered to month)
  - **TotalsAggregationEngine.getMonthlyTotals()** [line 32-57]
    - monthlyTotals = expenseRepository.getMonthlyTotalsForPeriod(startMs, endMs)

- **Time Window:** Calendar month (exact: month start 00:00 to month end 00:00)
- **Transaction Filter:** PURCHASE only, isNotMine excluded
- **Amount Basis:** effectiveAmount
- **Should Match:** YES – All use exact same calendar window
- **Semantic Meaning:** "How much did I spend this calendar month?"

### Variant B: Month-to-Date Total (Current Month Partial)
- **Formula:** SUM(expenses WHERE date >= monthStart AND date <= now)
- **Components:**
  - **ComputeDashboardWidgetsUseCase.compute()** [line 179, 212]
    - totalSpent = monthSpent (includes all expenses from month start to "now")
    - purchasesThisMonth = purchases.filter { it.date >= monthStart }
  - **SmartSavingsEngine.analyzeSpendingPace()** [line 97-107]
    - expenses = expenseRepository.getExpensesBetween(monthStart, now)
    - Then calculates projected based on days elapsed

- **Time Window:** Month start to current moment (partial)
- **Transaction Filter:** PURCHASE only, isNotMine excluded
- **Amount Basis:** effectiveAmount
- **Should Match:** PARTIAL – Both MTD but ComputeDashboard may include non-purchases in totalSpent
- **Semantic Meaning:** "How much have I spent so far this month?"

### Intentional Differences in Monthly Total

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| InsightsEngine (calendar month) | ComputeDashboard (MTD) | InsightsEngine compares full months, Dashboard shows current progress | Dashboard < InsightsEngine early in month, → equal at month-end |
| TotalsAggregation (calendar) | SmartSavingsEngine (MTD) | TotalsAgg is historical analysis, SmartSavings is forward-planning | SmartSavings uses MTD to project; TotalsAgg shows completed |

---

## Metric Group 3: Projections / Forecasts

### Variant A: Linear Projection (Canonical Pace-Based)
- **Formula:** `currentSpent * (daysInMonth / daysElapsed)` if daysElapsed ≥ 4, else conservative estimate
- **Components:**
  - **InsightsEngine.buildSpendingPace()** [line 424-431]
    ```kotlin
    if (dayOfMonth >= 4) {
        currentSpent * daysInMonth.toDouble() / dayOfMonth
    } else if (dayOfMonth > 0) {
        currentSpent * (daysInMonth.toDouble() / 10.0).coerceAtLeast(1.0)
    } else {
        currentSpent
    }
    ```
  - **SpendingPaceCalculator.calculate()** [line 95-102] – Identical logic
  - **ComputeDashboardWidgetsUseCase.compute()** [line 349-354]
    ```kotlin
    if (dayOfMonth == 1) {
        if (baseline != null) (baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)
        else monthSpent * daysInMonth
    } else {
        monthSpent * daysInMonth.toDouble() / dayOfMonth
    }
    ```

- **Time Window:** Current calendar month, extrapolated to month-end
- **Transaction Filter:** PURCHASE only, isNotMine excluded
- **Amount Basis:** effectiveAmount
- **Should Match:** YES (mostly) – All use linear extrapolation, ComputeDashboard has special case for day 1
- **Semantic Meaning:** "If I keep spending at this rate, what will I spend by month-end?"

### Variant B: Trend-Adjusted Forecast
- **Formula:** `averageMonthlySpend * trendFactor` (historical with trend adjustment)
- **Components:**
  - **BudgetForecastingEngine.calculatePredictedSpending()** [line 156-182]
    - Starts with historicalData.averageMonthly
    - Adjusts for trend: INCREASING (×1.1), DECREASING (×0.9), STABLE (×1.0)
    - Adds seasonal adjustment if history ≥ 6 months
    - Caps at budget.amount
  - **FinancialWeatherRepository.getFinancialWeather()** [line 180-187]
    - Uses SynthesisEngine.synthesize() which incorporates:
      - pastSumDaily (cumulative to date)
      - recurringPatterns
      - plannedExpenses
      - budgetStatuses
      - spendingPace

- **Time Window:** Historical 3-6 months + forward 30 days
- **Transaction Filter:** PURCHASE only (category-specific if budget has categoryId)
- **Amount Basis:** amount
- **Should Match:** NO – Uses trend + seasonal, not linear; includes recurring/planned
- **Semantic Meaning:** "Based on my patterns and upcoming expenses, what's realistic?"

### Variant C: Monte Carlo Simulation
- **Formula:** Stochastic simulation of transaction distribution
- **Components:**
  - **ComputeDashboardWidgetsUseCase.compute()** [line 277-295]
    - monteCarloSimulator.simulate(spentToDate, knownUpcoming, budgetAmount)
    - spentToDate = purchases this month to date
    - knownUpcoming = committed + likely from synthesis
  - **SmartSavingsEngine.runMonteCarloSimulation()** [line 126-160]
    - spentToDate = expenses MTD
    - knownUpcoming = 0.0 (assumed)
    - budgetAmount = null

- **Time Window:** Current month to date + forward (uncertain)
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** effectiveAmount
- **Should Match:** NO – Simulation returns confidence intervals, not point estimate
- **Semantic Meaning:** "What's the probability distribution of my month-end spend?"

### Intentional Differences in Projections

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| Linear (Insights/Pace) | Trend-adjusted (Budget) | Budget accounts for seasonal; Pace is agnostic | Budget projection = Pace in stable months; > Pace in Dec, < in summer |
| Linear (Pace) | Monte Carlo (Dashboard) | Monte Carlo samples distribution; Pace assumes steady rate | MC p50 ≈ Pace, but MC shows uncertainty band |
| Pace-only (SmartSavings) | Synthesis (FinancialWeather) | SmartSavings ignores recurring; Synthesis includes it | Synthesis projection > SmartSavings by ~recurring total |

---

## Metric Group 4: Spending Pace Percentage

### Variant A: Daily-Rate Ratio (Canonical)
- **Formula:** `(currentDailyRate / baselineDailyRate) * 100`
- **Components:**
  - **InsightsEngine.buildSpendingPace()** [line 444-446]
    ```kotlin
    val pacePercentage = if (hasBaseline) {
        (currentDailyRate / baselineDailyRate * 100).toFloat()
    } else 0f
    ```
  - **SpendingPaceCalculator.calculate()** [line 59-63] – Identical logic
  - Both calculate currentDailyRate = currentSpent / daysElapsed
  - Both calculate baselineDailyRate = previousMonthTotal / daysInPreviousMonth

- **Thresholds:** UNDER_PACE < 90%, ON_PACE 90–110%, OVER_PACE > 110%
- **Time Window:** Current month vs previous month
- **Should Match:** YES – Same formula, same thresholds
- **Semantic Meaning:** "Am I spending faster or slower than typical?"

### Variant B: Expected-vs-Actual Ratio
- **Formula:** `(currentMonthSpent / expectedToDate) * 100`
- **Components:**
  - **ComputeDashboardWidgetsUseCase.compute()** [line 356-360]
    ```kotlin
    val expected = baseline * dayOfMonthCoerced / daysInMonth
    val calculated = (monthSpent / expected * 100).toFloat()
    ```
  - Where `baseline` = overallBudget.amount OR previousMonthTotal

- **Time Window:** Current month to-date vs budget/average
- **Should Match:** NO – Different denominator (expected vs actual baseline rate)
- **Semantic Meaning:** "How close to budget am I?"

### Intentional Differences in Pace Percentage

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| Daily-rate ratio (Insights/Pace) | Expected-vs-actual (Dashboard) | Insights uses historical rate; Dashboard uses budget | Dashboard pacePercentage = Insights when monthlySpent = baseline * dayOfMonth / totalDays |
| Both exclude first 3 days from ratio | Special case on day 1 | Early-month volatility handling | Both conservative on day 1; stabilize after |

---

## Metric Group 5: Category Metrics

### Variant A: Month-to-Date Category Total
- **Formula:** `SUM(expenses WHERE categoryId = X AND date >= monthStart AND date < now)`
- **Components:**
  - **InsightsEngine.buildCategoryInsights()** [line 281-334]
    - currentTotals = expenseRepository.getCategoryTotalsForPeriod(currentMonth.startMs, currentMonth.endMs)
    - Includes month-to-date
  - **AdvancedAnalyticsEngine.getCategoryAnalytics()** [line 160-172]
    - currentByCategory = currentPurchases.groupBy { it.categoryId }
    - totalSpent = amounts.sum()
  - **TotalsAggregationEngine.getCategoryBreakdown()** [line 194-228]
    - categoryResults = expenseRepository.getCategoryBreakdown(startMs, endMs)

- **Time Window:** Calendar period (day/week/month/year)
- **Transaction Filter:** PURCHASE only, isNotMine excluded
- **Amount Basis:** effectiveAmount
- **Should Match:** YES – Same window and filter
- **Semantic Meaning:** "How much have I spent in this category this period?"

### Variant B: Historical Category Average
- **Formula:** `SUM(categoryExpenses) / monthsWithData`
- **Components:**
  - **InsightsEngine.calculateCategoryMonthlyAverages()** [line 336-363]
    ```kotlin
    val purchases = allExpenses.filter {
        it.transactionType == TransactionType.PURCHASE &&
        it.categoryId != null &&
        it.date < currentMonth.startMs  // exclude current month
    }
    val monthlyAverages = mutableMapOf<Long, MutableMap<String, Double>>()
    // ... group by month ...
    return monthlyAverages.mapValues { (_, monthMap) ->
        val months = monthMap.size
        val avg = if (months > 0) monthMap.values.sum() / months else 0.0
        Pair(avg, months)
    }
    ```
  - Used in CategoryInsight.changeFromAverage

- **Time Window:** Historical (all months before current), minimum 1 month
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** effectiveAmount
- **Should Match:** NO – Excludes current month for stability
- **Semantic Meaning:** "How much do I typically spend in this category?"

### Variant C: Top N Categories
- **Formula:** Categories sorted by totalSpent DESC, take first N
- **Components:**
  - **ComputeDashboardWidgetsUseCase.compute()** [line 330-342]
    - topCategories = categoryTotals.take(5)
  - **AdvancedAnalyticsDashboard.getTopCategories()** [line 114-139]
    - Top 5 categories by amount
  - **AdvancedAnalyticsEngine.getCategoryAnalytics()** [line 213]
    - sortedByDescending { it.totalSpent }

- **Ranking:** By total spent descending
- **Limit:** Typically 5
- **Should Match:** YES – All sort same way
- **Semantic Meaning:** "Which categories am I spending the most on?"

### Intentional Differences in Category Metrics

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| MTD category total (Insights) | Historical category avg (same component) | MTD is current progress; Avg is baseline | MTD > Avg early in month; → equal by month-end |
| Dashboard top 5 (ComputeDashboard) | Analytics top 5 (AdvancedAnalytics) | Dashboard uses effective amount; Analytics uses both | Should be identical or very close |

---

## Metric Group 6: Merchant Metrics

### Variant A: Merchant Total Spent (Period)
- **Formula:** `SUM(expenses WHERE merchant = X AND date IN period)`
- **Components:**
  - **InsightsEngine.buildMerchantInsights()** [line 370-396]
    - totalSpent = ms.totalAmount (from DAO)
  - **AdvancedAnalyticsEngine.getMerchantAnalytics()** [line 245-306]
    - totalSpent = amounts.sum()
  - **AdvancedAnalyticsDashboard.getTopMerchants()** [line 141-158]
    - totalSpent = data.first (accumulated from expense list)

- **Time Window:** Current month (or specified period)
- **Transaction Filter:** PURCHASE only, isNotMine excluded
- **Amount Basis:** effectiveAmount (Insights/AdvancedAnalytics) or amount (Dashboard)
- **Should Match:** YES – Same formula, but Dashboard may use `amount` instead of `effectiveAmount`
- **Semantic Meaning:** "How much have I spent at this merchant?"

### Variant B: Merchant Visit Frequency
- **Formula:** Categorical classification based on days between visits
- **Components:**
  - **AdvancedAnalyticsEngine.determineVisitFrequency()** [line 658-675]
    ```kotlin
    when {
        count >= periodDays * 0.7 -> DAILY
        avgDaysBetween <= 7 -> WEEKLY
        avgDaysBetween <= 14 -> BIWEEKLY
        avgDaysBetween <= 35 -> MONTHLY
        avgDaysBetween <= 100 -> QUARTERLY
        else -> RARE
    }
    ```
  - **InsightsEngine.buildMerchantInsights()** [line 386-396]
    - Returns isLikelyRecurring = (transactionCount >= 2) && (maxAmount - minAmount) < (averageAmount * 0.15)
    - Different semantic: recurring (stable amount) vs frequency

- **Window:** Historical (6+ months)
- **Should Match:** NO – Different semantics (frequency vs stability)
- **Semantic Meaning:** "How often do I visit this merchant?" vs "Is this a recurring bill?"

### Variant C: Merchant Price Trend
- **Formula:** `((lastAmount - firstAmount) / firstAmount) * 100`
- **Components:**
  - **AdvancedAnalyticsEngine.analyzePriceTrend()** [line 677-702]
    - Looks at historical 6 months
    - Classifies as INCREASING_FAST (>10%), INCREASING (>3%), STABLE, etc.

- **Window:** Historical 6 months minimum
- **Should Match:** UNIQUE – Only in AdvancedAnalytics
- **Semantic Meaning:** "Are prices at this merchant going up or down?"

### Intentional Differences in Merchant Metrics

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| Visit frequency (AdvancedAnalytics) | IsRecurring (InsightsEngine) | Frequency is time-based; IsRecurring is amount-stability-based | A merchant can be WEEKLY but not recurring (variable amounts); or MONTHLY and highly recurring |
| Merchant total (Insights) | Merchant total (Dashboard) | Dashboard may use `amount`; Insights uses `effectiveAmount` | Identical for most; Dashboard potentially > Insights if refunds/adjustments exist |

---

## Metric Group 7: Savings & Surplus

### Variant A: Budget Surplus (50% Conservative)
- **Formula:** `SUM(budget.remaining * 0.5 FOR all budgets WHERE remaining > 0)`
- **Components:**
  - **SmartSavingsEngine.calculateBudgetSurplus()** [line 71-80]
    ```kotlin
    for (status in budgetStatuses) {
        if (status.remainingAmount > 0) {
            totalSurplus += status.remainingAmount * 0.5  // Only 50%
        }
    }
    ```

- **Formula Rationale:** 50% × remaining buffer (conservative to avoid over-committing)
- **Should Match:** UNIQUE – Only in SmartSavingsEngine
- **Semantic Meaning:** "What portion of my budget surplus can I safely save?"

### Variant B: Spending-Pace-Based Savings
- **Formula:** `(avgMonthlySpending - projectedMonthTotal) * 0.3`
- **Components:**
  - **SmartSavingsEngine.analyzeSpendingPace()** [line 118-123]
    ```kotlin
    if (projectedMonthTotal < avgMonthlySpending) {
        (avgMonthlySpending - projectedMonthTotal) * 0.3  // Conservative 30%
    } else {
        0.0
    }
    ```

- **Rationale:** Only save if projected < average; 30% buffer for uncertainty
- **Should Match:** UNIQUE – Only in SmartSavingsEngine
- **Semantic Meaning:** "Can I save the difference between my pace and historical average?"

### Variant C: Safe-to-Spend (From Synthesis)
- **Formula:** `weather.discretionaryBudget` (from SynthesisEngine)
- **Components:**
  - **ComputeDashboardWidgetsUseCase.compute()** [line 190, 242]
    - safeToSpend = weather.discretionaryBudget
  - **FinancialWeatherRepository.getFinancialWeather()** [line 207]
    - discretionaryBudget = forecast.components.discretionaryBudget

- **Composition (from SynthesisEngine):**
  - = budgetAmount - totalCommitted - totalLikely
  - = what's left after committed & likely expenses

- **Should Match:** YES – Both get value from same synthesis
- **Semantic Meaning:** "After required and likely expenses, how much can I freely spend?"

### Variant D: Savings Rate (Income-Based)
- **Formula:** `(income - spent) / income * 100`
- **Components:**
  - **AdvancedAnalyticsDashboard.generateInsights()** [line 294-306]
    ```kotlin
    val savingsRate = ((totalIncome - totalSpent) / totalIncome) * 100
    if (savingsRate > 20) {
        // Show savings opportunity insight
    }
    ```

- **Threshold:** > 20% triggers "great savings" insight
- **Should Match:** UNIQUE – Only in Dashboard
- **Semantic Meaning:** "What percentage of my income am I saving?"

### Intentional Differences in Savings Metrics

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| Budget surplus (SmartSavings) | Safe-to-spend (Dashboard) | Budget = from budgets only; Safe = from complete synthesis | Budget surplus << Safe-to-spend because Safe includes recurring/planned |
| Spending-pace savings (SmartSavings) | Synthesis savings (FinancialWeather) | Pace uses historical; Synthesis uses forecasting engine | Both can be valid; depends on recent spending shifts |
| Income-based rate (Dashboard) | Budget-based surplus (SmartSavings) | Rate needs income; Surplus is budget-only | Rate can't calculate without income; Surplus works even without income |

---

## Critical Contracts & Consistency Rules

### Contract 1: Daily Rate Consistency
**Rule:** All daily-rate calculations must use the same formula: `total / days`

**Where it's enforced:**
- ✅ InsightsEngine (line 437)
- ✅ SpendingPaceCalculator (line 54)
- ✅ AdvancedAnalyticsEngine (line 457)

**Edge cases:**
- First 3 days of month: conservative estimate (lines 426–428)
- Both engines apply identical logic → consistent

**Test:** 
```kotlin
// These should always be equal:
insightsEngine.buildSpendingPace().currentDailyRate
== spendingPaceCalculator.calculate().currentDailyRate (given same inputs)
```

---

### Contract 2: Pace Percentage Formula
**Rule:** Pace% = (currentDailyRate / baselineDailyRate) × 100, with thresholds at 90% and 110%

**Where enforced:**
- ✅ InsightsEngine (line 444)
- ✅ SpendingPaceCalculator (line 60)
- ✅ ComputeDashboardWidgetsUseCase (line 356) – **VARIANT** uses budget baseline

**Difference in ComputeDashboard:**
- Uses `baseline * dayOfMonth / daysInMonth` as expected, not actual daily rate
- This is intentional: dashboard pace = "how far through budget" not "how fast am I spending"

**Test:**
```kotlin
// These should correlate but may differ in early month:
insightsEngine.pacePercentage  // Based on historical rate
== computeDashboard.pacePercentage  // Based on budget allocation
```

---

### Contract 3: Calendar Month Boundaries
**Rule:** All month-total calculations use exact calendar boundaries: startOfMonth (00:00) to endOfMonth (00:00)

**Where enforced:**
- ✅ InsightsEngine (line 246-254)
- ✅ SpendingPaceCalculator (line 31-46)
- ✅ TotalsAggregationEngine (line 32-57)
- ✅ ComputeDashboardWidgetsUseCase (line 166-167)

**Implementation via TimePeriodUtils:**
```kotlin
val monthStart = TimePeriodUtils.getStartOfMonth(now)
val monthEnd = TimePeriodUtils.getEndOfMonth(now)  // or monthStart + 1 month
```

**Test:**
```kotlin
// Month totals should be identical:
insightsEngine.monthlyComparison.currentTotal
== spendingPaceCalculator.monthlySpent
== computeDashboard.monthSpent
```

---

### Contract 4: Transaction Filter Consistency
**Rule:** All spending metrics filter to PURCHASE only, excluding isNotMine transactions

**Where enforced:**
- ✅ InsightsEngine (line 92-96, 243)
- ✅ AdvancedAnalyticsEngine (line 152-153, 319)
- ✅ SpendingPaceCalculator (line 34-35)
- ✅ ComputeDashboardWidgetsUseCase (line 169-171)
- ✅ FinancialWeatherRepository (line 152-156)

**Exception:** TotalsAggregationEngine includes all transaction types initially, filters at DAO level

**Test:**
```kotlin
// All should have identical transaction counts after filtering:
insightsEngine.categoryInsights.sumOf { it.currentCount }
== advancedAnalytics.getCategoryAnalytics().sumOf { it.transactionCount }
== computeDashboard.txCount
```

---

### Contract 5: Amount Basis (effectiveAmount vs amount)
**Rule:** Preference is `effectiveAmount` for all calculations. `amount` is fallback.

**Where effectiveAmount is used:**
- ✅ InsightsEngine (line 98, 175, 248)
- ✅ AdvancedAnalyticsEngine (line 170, 325)
- ✅ SpendingPaceCalculator (line 37, 46)
- ✅ ComputeDashboardWidgetsUseCase (line 174-176)
- ✅ FinancialWeatherRepository (line 164)

**Where amount is used (potential inconsistency):**
- ⚠️ AdvancedAnalyticsDashboard (line 121, 147)
- ⚠️ BudgetForecastingEngine (line 118)

**Difference:** 
- `effectiveAmount` = amount + adjustments (refunds, credits)
- `amount` = raw transaction amount
- Usually same; differs for refunds/chargebacks

**Test:**
```kotlin
// These may differ if refunds/adjustments exist:
sum(effectiveAmount) vs sum(amount)
// Most cases: effectiveAmount > amount (refunds excluded)
```

---

### Contract 6: Projection Formula Stability
**Rule:** Linear projection = `currentSpent * (daysInMonth / daysElapsed)` for daysElapsed ≥ 4

**Where enforced:**
- ✅ InsightsEngine (line 424-425)
- ✅ SpendingPaceCalculator (line 96-97)
- ✅ ComputeDashboardWidgetsUseCase (line 353) – with special case for day 1

**Special case (day 1):**
- Dashboard: `(baseline * 0.7) + (monthSpent * 0.3 * daysInMonth)` [line 350]
- Purpose: Avoid huge multiplier from day 1 spend
- Rationale: Day 1 is unreliable; blend with historical

**Test:**
```kotlin
// Should match after day 4:
if (dayOfMonth >= 4) {
    insightsEngine.projectedTotal
    == spendingPaceCalculator.projectedTotal
    ≈ computeDashboard.projectedTotal (within rounding)
}
```

---

## Anomaly Detection & Outliers

### Single Point of Truth: AnomalyTransaction Model
- Defined in **AnalyticsModels.kt** [line 70-94]
- Used by **InsightsEngine.findAnomalies()** [line 513-607]
- Two detection paths:
  1. **Merchant-level** (historical avg × multiplier): 3×–5× depending on transaction count
  2. **Statistical** (IQR, MAD, contextual): From AnomalyDetector

**Contract:** 
- Merchant path gets priority (historical data is precise)
- Statistical path fills gaps (detects new merchants)
- Results deduplicated by expense.id

**Formula (Merchant Path):**
```kotlin
multiplier = when {
    historicalStats.transactionCount < 5  -> 5.0
    historicalStats.transactionCount < 10 -> 4.0
    else                                  -> 3.0
}
if (currentAmount > historicalAverage * multiplier) → ANOMALY
```

**Test:**
```kotlin
// Anomaly transactions should be deterministic:
insightsEngine.findAnomalies() 
== run again with same data
// (Some randomness in MC simulation; anomalies should be stable)
```

---

## Summary Table: Which Metrics Should Match

| Metric | Components | Match? | Reason |
|--------|-----------|--------|--------|
| Daily Rate | Insights, Pace, AdvancedAnalytics | ✅ YES | Same formula everywhere |
| Month Total | Insights, Pace, Dashboard, TotalsAgg | ✅ YES | Same calendar boundaries |
| Pace % | Insights, Pace | ✅ YES | Identical thresholds |
| Pace % | Dashboard | ⚠️ PARTIAL | Different baseline (budget vs rate) |
| Projection | Insights, Pace | ✅ YES | Same linear formula |
| Projection | Budget, FinancialWeather | ❌ NO | Trend + seasonal adjustments |
| Category Total | Insights, AdvancedAnalytics, TotalsAgg | ✅ YES | Same period + filter |
| Category Avg | Insights (current vs historical) | ✅ INTENTIONAL | Different purposes (current vs baseline) |
| Merchant Total | Insights, Dashboard | ✅ YES* | *except Dashboard may use `amount` |
| Budget Surplus | SmartSavings | 🔹 UNIQUE | Only calculated here |
| Safe-to-Spend | Dashboard (from Synthesis) | ✅ YES | Single source (SynthesisEngine) |
| Savings Rate | Dashboard | 🔹 UNIQUE | Requires income data |

---

## Verification Checklist

Use this checklist when adding new metrics:

- [ ] **Identify the semantic group** (Daily Rate, Monthly Total, etc.)
- [ ] **Check if similar metric exists** elsewhere
- [ ] **Document exact formula** from code, with line numbers
- [ ] **Specify time window:** calendar month / rolling 30d / MTD / historical / forward
- [ ] **Specify filter:** PURCHASE only? Include isNotMine? All types?
- [ ] **Specify amount basis:** effectiveAmount / amount / adjusted?
- [ ] **Decide:** Should it match existing metric? Why or why not?
- [ ] **If should match:** Add unit test to verify consistency
- [ ] **If intentional difference:** Document why in "Intentional Differences" table
- [ ] **Update this map** before PR submission

---

## Known Issues & Edge Cases

### Issue 1: Day-of-Month Edge Cases
**Problem:** On days 1–3, linear projection gives huge multipliers  
**Solution:** Both Insights and Pace apply conservative estimate (÷10 instead of ÷dayOfMonth)  
**Status:** ✅ Consistent  

### Issue 2: Missing Previous Month Baseline
**Problem:** What if user has no previous month? (new account)  
**Solution:** 
- InsightsEngine: PaceStatus.NO_BASELINE; pacePercentage = 0f [line 460-461]
- SpendingPaceCalculator: Same behavior [line 77-78]
- Dashboard: Falls back to budget if available [line 371]  
**Status:** ✅ Consistent

### Issue 3: Refund Transactions (effectiveAmount vs amount)
**Problem:** Refunds can make effectiveAmount ≠ amount  
**Solution:** Use effectiveAmount everywhere (includes refunds/credits)  
**Status:** ⚠️ PARTIAL – AdvancedAnalyticsDashboard uses `amount` [line 121]  
**Recommendation:** Fix Dashboard to use effectiveAmount

### Issue 4: Multiple Currencies
**Problem:** SmartSavingsEngine and others don't handle multi-currency  
**Solution:** Assume single currency (EUR) for now  
**Status:** 🔹 LIMITATION – Document in code

### Issue 5: Synthetic Expenses (Recurring Predictions)
**Problem:** Should predicted recurring expenses count as "spending"?  
**Solution:** 
- SynthesisEngine: YES, includes in `totalCommitted` + `totalLikely`
- Insights/Pace: NO, only counts booked transactions
- CashFlowCalculator: YES, includes predictedRecurring in calculation [line 121-123]  
**Status:** ⚠️ INCONSISTENT  
**Recommendation:** Clarify semantics: "committed" vs "booked"

---

## Change Log

**Version 1.0** (2024-xx-xx)
- Initial semantic contract map created
- 7 metric groups documented
- 12 components analyzed
- 6 critical contracts identified

---

## Appendix: Component Cross-Reference

### By Component Name
1. **AdvancedAnalyticsEngine** – Category analytics, merchant analytics, patterns, statistical insights
2. **InsightsEngine** – Month comparison, category insights, merchant insights, spending pace, anomalies, recurring, day-of-week
3. **SpendingPaceCalculator** – Pace calculation, projection (simple)
4. **TotalsAggregationEngine** – Period totals (daily/weekly/monthly/yearly), averages
5. **AdvancedAnalyticsDashboard** – Top categories, top merchants, trends, insights
6. **SmartSavingsEngine** – Budget surplus, spending pace analysis, Monte Carlo integration
7. **BudgetForecastingEngine** – Predicted spending, trend detection, seasonal adjustment
8. **CashFlowCalculator** – Daily cash flow with recurring prediction
9. **ComputeDashboardWidgetsUseCase** – Widget compilation, pace widget, runway, block party
10. **DashboardFollowThroughEngine** – Navigation recommendations (deterministic, not analytic)
11. **FinancialWeatherRepository** – Weather synthesis, forecast, narrative generation
12. **Supporting Models** – Data structures for all analytics

### By Metric Group
1. **Daily Rate / Average** – Insights, Pace, AdvancedAnalytics, SmartSavings, TotalsAgg
2. **Monthly Total** – Insights, Pace, Dashboard, TotalsAgg
3. **Projections** – Insights, Pace, Budget, FinancialWeather, Dashboard, SmartSavings
4. **Pace %** – Insights, Pace, Dashboard
5. **Categories** – Insights, AdvancedAnalytics, Dashboard, TotalsAgg
6. **Merchants** – Insights, AdvancedAnalytics, Dashboard
7. **Savings** – SmartSavings, Dashboard, FinancialWeather

---

## Metric Group 8: Predictors & Forecasts

This group documents ALL components that predict, forecast, or estimate future values.

### Variant A: Synthesis-Based Projection (Canonical)
- **Formula:** 
  ```
  committed = recurring(confidence ≥ 90%) + planned(MUST)
  likely = recurring(confidence 70-89%) + planned(LIKELY) × 0.7
  discretionaryBudget = budgetLimit - spentToDate - committed - likely - goalReserves
  projectedSpendingPoints = cumulative daily projection from committed + likely + discretionary rate
  ```
- **Components:**
  - **SynthesisEngine.synthesize()** [line 227]
    - Discretionary Pool = budgetLimit - spentToDate - committed - likely - goalReserves
    - Committed = recurring confidence ≥90% + MUST planned expenses
    - Likely = recurring 70%-89% confidence + 70% of LIKELY planned expenses
  - **FinancialWeatherRepository.getFinancialWeather()** [line 180-187]
    - Calls SynthesisEngine.synthesize() with all inputs
    - Returns FinancialForecast with projectedSpendingPoints
  - **ComputeDashboardWidgetsUseCase.compute()** [line 221-234]
    - Uses synthesis output for safeToSpend widget
    - projectedMonthlyTotal = projectedSpendingPoints.lastOrNull()

- **Time Window:** Forward 30 days (rest of month)
- **Transaction Filter:** PURCHASE only for historical base
- **Amount Basis:** effectiveAmount
- **Should Match:** YES – All use same synthesis output
- **Semantic Meaning:** "Based on my patterns, upcoming bills, and budget, what will I spend?"

### Variant B: Monte Carlo Simulation
- **Formula:**
  ```
  Total = spentToDate + knownUpcoming + sampledDiscretionary
  sampledDiscretionary = LogNormal(μ, σ) fitted to historical weekly spending
  Run 1000 iterations with seed=42 for reproducibility
  Return: percentile10, percentile25, percentile50, percentile75, percentile90, probabilityUnderBudget
  ```
- **Components:**
  - **MonteCarloSpendingSimulator.simulate()** [line 100-110]
    - Inputs: spentToDate, knownUpcoming, budgetAmount
    - Samples from LogNormal distribution fitted to historical weekly spending
    - 1000 iterations with seed=42
    - Returns: MonteCarloResult with percentiles + confidence
  - **ComputeDashboardWidgetsUseCase.compute()** [line 277-295]
    - monteCarloSimulator.simulate(spentToDate, knownUpcoming, budgetAmount)
    - spentToDate = purchases this month to date
    - knownUpcoming = committed + likely from SynthesisEngine
  - **SmartSavingsEngine.runMonteCarloSimulation()** [line 126-160]
    - spentToDate = expenses MTD
    - knownUpcoming = 0.0 (assumes no known upcoming)
    - budgetAmount = null (no cap)

- **Time Window:** Remaining days in month for stochastic component
- **Transaction Filter:** PURCHASE only for historical fitting
- **Amount Basis:** effectiveAmount
- **Should Match:** PARTIAL – Same algorithm, different inputs (Dashboard includes knownUpcoming, SmartSavings doesn't)
- **Semantic Meaning:** "What's the probability distribution of my month-end spend?"

### Variant C: Linear Extrapolation (Pace-Based)
- **Formula:** `currentSpent * (daysInMonth / daysElapsed)` if daysElapsed ≥ 4
- **Components:**
  - **InsightsEngine.buildSpendingPace()** [line 424-431]
  - **SpendingPaceCalculator.calculate()** [line 95-102]
  - **ComputeDashboardWidgetsUseCase.compute()** [line 349-354] – with special case for day 1

- **Time Window:** Current calendar month, extrapolated to month-end
- **Transaction Filter:** PURCHASE only, isNotMine excluded
- **Amount Basis:** effectiveAmount
- **Should Match:** YES (mostly) – All use linear extrapolation
- **Semantic Meaning:** "If I keep spending at this rate, what will I spend by month-end?"

### Variant D: Trend-Adjusted Forecast
- **Formula:**
  ```
  prediction = averageMonthlySpend * months * trendFactor * seasonalFactor
  trendFactor: INCREASING=1.1, DECREASING=0.9, STABLE=1.0
  seasonalFactor: December=1.2, otherwise=1.0
  Cap at budget.amount
  ```
- **Components:**
  - **BudgetForecastingEngine.calculatePredictedSpending()** [line 156-182]
    - Starts with historicalData.averageMonthly
    - Adjusts for trend: INCREASING (×1.1), DECREASING (×0.9), STABLE (×1.0)
    - Adds seasonal adjustment if history ≥ 6 months
    - Caps at budget.amount
  - **BudgetForecastDao** [line 33-38]
    - Stores forecasts
    - Calculates accuracy: `1 - (|predicted - actual| / predicted)`

- **Time Window:** Historical 3-6 months + forward 30 days
- **Transaction Filter:** PURCHASE only (category-specific if budget has categoryId)
- **Amount Basis:** amount (⚠️ inconsistency – should be effectiveAmount)
- **Should Match:** NO – Uses trend + seasonal, not linear
- **Semantic Meaning:** "Based on my patterns and seasonality, what's realistic?"

### Variant E: Cash Flow Predictions
- **Formula:**
  ```
  predictedRecurring = recurringPatterns.filter { pattern.nextExpectedDate in dateRange }
  upcomingBills = recurringPatterns.filter { pattern.isUpcoming(daysWithin) }
  endingBalance = startingBalance + income - expenses - predictedRecurring
  ```
- **Components:**
  - **CashFlowCalculator.calculateDailyCashFlow()** [line 100-123]
    - predictedRecurringList = patterns where nextExpectedDate matches day
    - Includes in daily balance calculation
  - **CashFlowCalculator.getUpcomingBills(daysAhead)** [line 154-170]
    - upcomingList = patterns where isUpcoming(daysWithin)
    - Returns list of upcoming bills
  - **RecurrenceCalculator.calculateNextDate()** [line 66-85]
    - Calculates next occurrence from pattern frequency
    - Monthly multipliers: WEEKLY=4.33, BIWEEKLY=2.17, MONTHLY=1.0, QUARTERLY=0.33
  - **RecurrenceCalculator.isUpcoming(daysWithin)** [line 133-140]
    - Checks if next date is within specified days

- **Time Window:** Forward N days (configurable, typically 7-31)
- **Transaction Filter:** Recurring patterns only
- **Amount Basis:** effectiveAmount (from recurring pattern amounts)
- **Should Match:** YES – All use same recurrence logic
- **Semantic Meaning:** "What bills are coming up and how will they affect my balance?"

### Variant F: Bill Reminders
- **Formula:**
  ```
  nextDate = RecurrenceCalculator.calculateNextDate(pattern, fromDate)
  urgency = when {
    nextDate < today → CRITICAL (overdue)
    nextDate ≤ today + 2d → URGENT
    nextDate ≤ today + 7d → WARNING
    else → INFO
  }
  ```
- **Components:**
  - **BillReminderManager.getNextBillDate()** [line 140-154]
    - Predicts next date from recurring expense frequency
    - Calculates urgency based on proximity
  - **BillReminderManager.getUpcomingReminders()**
    - Returns list of reminders with urgency levels

- **Time Window:** Forward 30 days (typical reminder window)
- **Transaction Filter:** Active recurring expenses only
- **Amount Basis:** effectiveAmount
- **Should Match:** YES – Uses same RecurrenceCalculator
- **Semantic Meaning:** "Which bills need my attention soon?"

### Variant G: Tax Predictions
- **Formula:**
  ```
  taxableIncome = grossIncome - deductions
  estimatedIncomeTax = taxableIncome × taxRate (from bracket)
  vatAmount = expense × (vatRate / (1 + vatRate))
  totalTax = incomeTax + vatOnExpenses
  ```
- **Components:**
  - **TaxEstimator.estimateTaxes()** [line 63]
    - estimatedIncomeTax = taxableIncome × taxRate
    - Tax brackets via TaxConfiguration [line 82-93]
    - VAT calculation: vatAmount = expense × (vatRate / (1 + vatRate)) [line 54]
  - **TaxConfigurationViewModel.calculateSampleEstimate()**
    - Uses TaxEstimator with current configuration

- **Time Window:** Annual (tax year)
- **Transaction Filter:** All taxable transactions
- **Amount Basis:** amount (gross, not effectiveAmount)
- **Should Match:** UNIQUE – Only in TaxEstimator
- **Semantic Meaning:** "How much tax will I owe this year?"

### Variant H: Financial Runway
- **Formula:**
  ```
  runwayDays = discretionaryRemaining / averageDailyBurn
  status = when {
    runwayDays >= 30 → HEALTHY
    runwayDays >= 14 → CAUTION
    runwayDays >= 7 → WARNING
    else → CRITICAL
  }
  ```
- **Components:**
  - **ComputeDashboardWidgetsUseCase.compute()** [line 244-255]
    - runwayDays = discretionaryRemaining / averageDailyBurn
    - averageDailyBurn = monthSpent / dayOfMonth
    - Status thresholds: HEALTHY ≥30d, CAUTION ≥14d, WARNING ≥7d

- **Time Window:** Forward projection based on current burn rate
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** effectiveAmount
- **Should Match:** UNIQUE – Only in Dashboard
- **Semantic Meaning:** "How many days until I run out of discretionary budget?"

---

### Intentional Differences in Predictors

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| **Synthesis (comprehensive)** | **Linear (simple)** | Synthesis includes recurring/planned; Linear assumes steady rate | Synthesis typically > Linear by ~recurring total |
| **Monte Carlo p50** | **Linear** | MC samples distribution; Linear assumes steady | MC p50 ≈ Linear, but MC shows uncertainty band |
| **Monte Carlo (Dashboard)** | **Monte Carlo (SmartSavings)** | Dashboard includes knownUpcoming; SmartSavings assumes 0 | Dashboard typically > SmartSavings by ~committed amount |
| **Trend-Adjusted (Budget)** | **Linear (Pace)** | Budget accounts for seasonal; Pace is agnostic | Budget = Pace in stable months; > Pace in Dec, < in summer |
| **Committed (≥90%)** | **Likely (70-89%)** | Different confidence thresholds | Committed ≥ Likely (by definition) |
| **CashFlow predictedRecurring** | **BillReminder nextDate** | CashFlow aggregates for balance; Reminders show individual | Same underlying dates, different presentation |
| **TaxEstimator (annual)** | **All others (monthly)** | Tax is annual; others are monthly | Not directly comparable |

---

### Critical Contracts for Predictors

#### Contract 7: Synthesis Input Consistency
**Rule:** All components using SynthesisEngine must provide same inputs
- spentToDate = purchases this month (PURCHASE, !isNotMine, effectiveAmount)
- committed = recurring patterns with confidence ≥90%
- likely = recurring patterns with confidence 70-89% + planned LIKELY × 0.7

**Where enforced:**
- ✅ FinancialWeatherRepository [line 152-164]
- ✅ ComputeDashboardWidgetsUseCase [line 221-230]

**Test:**
```kotlin
// Same inputs should produce same synthesis output:
weatherRepo.getFinancialWeather().projectedSpendingPoints
== synthesisEngine.synthesize(sameInputs).projectedSpendingPoints
```

#### Contract 8: Monte Carlo Reproducibility
**Rule:** Monte Carlo must be deterministic with same seed
- seed = 42 (hardcoded for reproducibility)
- iterations = 1000
- Distribution: LogNormal fitted to historical weekly spending

**Where enforced:**
- ✅ MonteCarloSpendingSimulator [line 100-110]

**Test:**
```kotlin
// Same inputs should produce same result:
simulator.simulate(inputs) == simulator.simulate(inputs) // deterministic
```

#### Contract 9: Recurrence Calculation Consistency
**Rule:** All components must use same recurrence multipliers
- WEEKLY = 4.33 per month
- BIWEEKLY = 2.17 per month
- MONTHLY = 1.0 per month
- QUARTERLY = 0.33 per month

**Where enforced:**
- ✅ RecurrenceCalculator [line 66-85]
- ✅ CashFlowCalculator (uses RecurrenceCalculator)
- ✅ BillReminderManager (uses RecurrenceCalculator)

**Test:**
```kotlin
// Same pattern should produce same next date:
recurrenceCalc.calculateNextDate(pattern) == cashFlowCalc.predictedRecurring(pattern)
```

---

### Summary Table: Predictors

| Predictor | Components | Match? | Reason |
|-----------|-----------|--------|--------|
| Synthesis Projection | SynthesisEngine, FinancialWeather, Dashboard | ✅ YES | Same synthesis algorithm |
| Monte Carlo p50 | Dashboard, SmartSavings | ⚠️ PARTIAL | Different inputs (knownUpcoming) |
| Linear Projection | Insights, Pace, Dashboard | ✅ YES | Same formula after day 4 |
| Trend-Adjusted Forecast | BudgetForecastingEngine | 🔹 UNIQUE | Includes seasonal adjustment |
| Cash Flow Prediction | CashFlowCalculator | 🔹 UNIQUE | Includes recurring + balance |
| Bill Reminders | BillReminderManager | ✅ YES | Uses RecurrenceCalculator |
| Recurrence Dates | RecurrenceCalculator | ✅ YES | Single source of truth |
| Tax Estimate | TaxEstimator | 🔹 UNIQUE | Annual, country-specific |
| Financial Runway | Dashboard | 🔹 UNIQUE | Dashboard-specific widget |

---

### Known Predictor Issues

#### Issue 6: BudgetForecastingEngine Uses `amount` Instead of `effectiveAmount`
**Problem:** Trend-adjusted forecast uses raw `amount` while other predictors use `effectiveAmount`
**Impact:** May differ for shared expenses or refunds
**Status:** ⚠️ INCONSISTENT
**Recommendation:** Fix to use `effectiveAmount` for consistency

#### Issue 7: Monte Carlo Inputs Differ Between Dashboard and SmartSavings
**Problem:** Dashboard passes knownUpcoming from SynthesisEngine; SmartSavings passes 0.0
**Impact:** SmartSavings underestimates spending (doesn't account for committed bills)
**Status:** ⚠️ INTENTIONAL but potentially misleading
**Recommendation:** SmartSavings should also use SynthesisEngine for knownUpcoming

#### Issue 8: TaxEstimator Uses Annual Window While Others Use Monthly
**Problem:** Tax predictions are annual; all other predictors are monthly
**Impact:** Not directly comparable to other metrics
**Status:** 🔹 BY DESIGN – Tax is inherently annual
**Recommendation:** Document clearly; consider monthly tax accrual widget

---

## Updated Component Cross-Reference

### By Metric Group
1. **Daily Rate / Average** – Insights, Pace, AdvancedAnalytics, SmartSavings, TotalsAgg
2. **Monthly Total** – Insights, Pace, Dashboard, TotalsAgg
3. **Projections** – Insights, Pace, Budget, FinancialWeather, Dashboard, SmartSavings
4. **Pace %** – Insights, Pace, Dashboard
5. **Categories** – Insights, AdvancedAnalytics, Dashboard, TotalsAgg
6. **Merchants** – Insights, AdvancedAnalytics, Dashboard
7. **Savings** – SmartSavings, Dashboard, FinancialWeather
8. **Predictors & Forecasts** – SynthesisEngine, MonteCarlo, BudgetForecast, CashFlow, BillReminder, Recurrence, TaxEstimator, Dashboard

### By Component Name (Updated)
1. **AdvancedAnalyticsEngine** – Category analytics, merchant analytics, patterns, statistical insights
2. **InsightsEngine** – Month comparison, category insights, merchant insights, spending pace, anomalies, recurring, day-of-week
3. **SpendingPaceCalculator** – Pace calculation, projection (simple)
4. **TotalsAggregationEngine** – Period totals (daily/weekly/monthly/yearly), averages
5. **AdvancedAnalyticsDashboard** – Top categories, top merchants, trends, insights
6. **SmartSavingsEngine** – Budget surplus, spending pace analysis, Monte Carlo integration
7. **BudgetForecastingEngine** – Predicted spending, trend detection, seasonal adjustment
8. **CashFlowCalculator** – Daily cash flow with recurring prediction
9. **ComputeDashboardWidgetsUseCase** – Widget compilation, pace widget, runway, block party, Monte Carlo forecast
10. **DashboardFollowThroughEngine** – Navigation recommendations (deterministic, not analytic)
11. **FinancialWeatherRepository** – Weather synthesis, forecast, narrative generation
12. **SynthesisEngine** – **Core prediction hub** – combines recurring, planned, budgets, pace
13. **MonteCarloSpendingSimulator** – **Stochastic simulation** – 1000 iterations, LogNormal distribution
14. **RecurrenceCalculator** – **Recurrence utilities** – next date calculation, upcoming checks
15. **BillReminderManager** – **Bill reminders** – urgency calculation, next date prediction
16. **TaxEstimator** – **Tax predictions** – income tax, VAT estimation
17. **BudgetForecastDao** – **Forecast storage** – accuracy tracking
18. **Supporting Models** – Data structures for all analytics

---

## Metric Group 9: Statistical Analysis & Anomaly Detection

This group documents components that perform statistical analysis on financial data.

### Variant A: Anomaly Detection (Multi-Method)
- **Formula (IQR Method):**
  ```
  Q1 = percentile(25%), Q3 = percentile(75%)
  IQR = Q3 - Q1
  lowerBound = Q1 - 1.5 * IQR
  upperBound = Q3 + 1.5 * IQR
  if (amount < lowerBound || amount > upperBound) → ANOMALY
  ```
- **Formula (MAD Method):**
  ```
  median = percentile(50%)
  MAD = median(|x - median|)
  modifiedZ = 0.6745 * (x - median) / MAD
  if (|modifiedZ| > 3.5) → ANOMALY
  ```
- **Formula (Contextual Method):**
  ```
  // Compares to category/merchant-specific baseline
  if (amount > categoryAvg * 3) → ANOMALY (new merchant)
  if (amount > merchantAvg * multiplier) → ANOMALY (known merchant)
  multiplier = when {
    transactionCount < 5  → 5.0
    transactionCount < 10 → 4.0
    else                  → 3.0
  }
  ```
- **Components:**
  - **AnomalyDetector.detect()** [find exact line]
    - Uses all three methods: IQR, MAD, Contextual
    - Deduplicates by expense.id
    - Returns list of AnomalyTransaction with severity
  - **InsightsEngine.findAnomalies()** [line 513-607]
    - Calls AnomalyDetector for statistical path
    - Also checks merchant-level historical averages
    - Merges results, deduplicates

- **Time Window:** Current period (typically 30-90 days)
- **Transaction Filter:** PURCHASE only, isNotMine excluded
- **Amount Basis:** effectiveAmount
- **Should Match:** YES – Single AnomalyDetector used by all
- **Semantic Meaning:** "Which transactions are unusually large or suspicious?"

### Variant B: Financial Health Score
- **Formula:**
  ```
  healthScore = baseScore + volatilityBonus + savingsBonus - overspendPenalty
  baseScore = 50 (starting point)
  volatilityBonus = if (CV < 0.3) +10 else if (CV < 0.5) +5 else 0
  savingsBonus = if (savingsRate > 20%) +15 else if (savingsRate > 10%) +10 else 0
  overspendPenalty = if (pace > 110%) -15 else if (pace > 100%) -5 else 0
  healthScore = healthScore.coerceIn(0, 100)
  ```
- **Components:**
  - **FinancialHealthCalculator.calculateHealthScore()** [find exact line]
    - Calculates composite score from multiple factors
    - Returns score with breakdown of components
    - Supports different time windows (today/week/month)

- **Time Window:** Configurable (today, week, month)
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** effectiveAmount
- **Should Match:** UNIQUE – Only calculated here
- **Semantic Meaning:** "How healthy are my finances overall?"

### Variant C: Spending Threshold Calculation
- **Formula:**
  ```
  threshold = percentile(90, last 90 days)
  threshold = max(threshold, 50.0) // Minimum €50
  ```
- **Components:**
  - **SpendingThresholdCalculator.getThreshold()** [find exact line]
    - Calculates P90 of last 90 days
    - Minimum threshold of €50
    - Used by DashboardFollowThroughEngine for high-amount detection

- **Time Window:** Last 90 days (rolling)
- **Transaction Filter:** PURCHASE only, isNotMine excluded
- **Amount Basis:** effectiveAmount (via DAO CASE expression)
- **Should Match:** UNIQUE – Single source of truth
- **Semantic Meaning:** "What amount counts as a 'large' transaction for me?"

---

### Intentional Differences in Statistical Analysis

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| IQR Method | MAD Method | IQR sensitive to sample size; MAD robust to outliers | MAD typically finds fewer anomalies in skewed data |
| Contextual (merchant) | Statistical (IQR/MAD) | Contextual uses merchant history; Statistical uses overall distribution | Contextual more precise for known merchants |
| Health Score (month) | Health Score (week) | Month smooths volatility; Week more responsive | Month score typically more stable |

---

## Metric Group 10: Environmental Impact (Carbon Footprint)

This group documents components that calculate environmental impact of spending.

### Variant A: Carbon Footprint by Category
- **Formula:**
  ```
  co2Emissions = sum(expense.amount * categoryEmissionFactor)
  categoryEmissionFactor = kg CO2 per € spent (from emission factor table)
  sustainabilityScore = 100 - (co2Emissions / maxPossibleEmissions * 100)
  ```
- **Components:**
  - **CarbonFootprintCalculator.calculateFootprint()** [find exact line]
    - Uses category-based emission factors
    - Returns total CO2 in kg
    - Calculates sustainability score (0-100)
  - **CarbonFootprintCalculator.getCategoryBreakdown()**
    - Groups emissions by category
    - Returns list of CategoryEmission with kg CO2 and percentage

- **Time Window:** Configurable (month, quarter, year)
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** amount (gross spending, not effectiveAmount)
- **Should Match:** UNIQUE – Only calculated here
- **Semantic Meaning:** "What's the environmental impact of my spending?"

### Variant B: Carbon Footprint by Merchant
- **Formula:**
  ```
  co2Emissions = sum(expense.amount * merchantEmissionFactor)
  merchantEmissionFactor = kg CO2 per € (merchant-specific if available, else category default)
  ```
- **Components:**
  - **CarbonFootprintCalculator.getMerchantBreakdown()**
    - Uses merchant-specific emission factors when available
    - Falls back to category factors for unknown merchants

- **Time Window:** Same as category calculation
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** amount
- **Should Match:** PARTIAL – Same total, different grouping
- **Semantic Meaning:** "Which merchants contribute most to my carbon footprint?"

### Variant C: Carbon Offset Calculation
- **Formula:**
  ```
  offsetCost = co2Emissions * costPerTonCO2
  treesNeeded = co2Emissions / kgCO2AbsorbedPerTreePerYear
  ```
- **Components:**
  - **CarbonFootprintCalculator.calculateOffset()**
    - Calculates cost to offset emissions
    - Estimates trees needed to absorb CO2

- **Should Match:** UNIQUE – Derived from footprint calculation
- **Semantic Meaning:** "What would it cost to offset my carbon footprint?"

---

### Intentional Differences in Carbon Metrics

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| Category-based | Merchant-based | Merchant uses specific factors; Category uses averages | Merchant typically more accurate |
| Current month | Historical average | Current shows trend; Average shows baseline | Current can be > or < average |

---

## Metric Group 11: Lifestyle Analysis

This group documents components that analyze lifestyle changes and spending patterns.

### Variant A: Lifestyle Inflation Detection
- **Formula:**
  ```
  incomeElasticity = % change spending / % change income
  lifestyleInflation = spendingTrend - incomeTrend
  if (incomeElasticity > 1.0) → LIFESTYLE INFLATION (spending grows faster than income)
  if (incomeElasticity < 0.5) → FRUGAL (spending grows slower than income)
  hedonicAdaptation = (newCategorySpending / totalSpending) - (oldCategorySpending / totalSpending)
  ```
- **Components:**
  - **LifestyleInflationDetector.analyze()** [find exact line]
    - Compares spending trends to income trends
    - Detects hedonic adaptation (new spending categories)
    - Returns LifestyleAnalysis with inflation score and recommendations

- **Time Window:** 6-12 months (needs sufficient history)
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** effectiveAmount
- **Should Match:** UNIQUE – Only calculated here
- **Semantic Meaning:** "Is my spending growing faster than my income?"

### Variant B: Spending Pattern Analysis
- **Formula:**
  ```
  weekendRatio = weekendSpending / totalSpending
  eveningRatio = eveningSpending / totalSpending
  impulseScore = % transactions < 1 hour apart
  routineScore = % transactions at same merchant within 7 days
  ```
- **Components:**
  - **AdvancedAnalyticsEngine.analyzeSpendingPatterns()** [line ~703-750]
    - Analyzes time-of-day, day-of-week patterns
    - Detects impulse buying vs routine spending
    - Returns SpendingPatterns with scores

- **Time Window:** Last 90 days
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** effectiveAmount
- **Should Match:** UNIQUE – Only in AdvancedAnalyticsEngine
- **Semantic Meaning:** "What are my spending habits and patterns?"

### Variant C: Income Elasticity Analysis
- **Formula:**
  ```
  elasticity = (spending2 - spending1) / spending1 / (income2 - income1) / income1
  if (elasticity > 1) → Luxury goods (spending grows faster than income)
  if (elasticity ≈ 1) → Normal goods (proportional growth)
  if (elasticity < 1) → Necessities (spending grows slower than income)
  if (elasticity < 0) → Inferior goods (spending decreases as income increases)
  ```
- **Components:**
  - **LifestyleInflationDetector.calculateElasticity()**
    - Calculates income elasticity for each category
    - Classifies categories by elasticity type

- **Time Window:** Requires 2+ income change events
- **Transaction Filter:** PURCHASE only
- **Amount Basis:** effectiveAmount
- **Should Match:** UNIQUE – Only in LifestyleInflationDetector
- **Semantic Meaning:** "How does my spending change when my income changes?"

---

### Intentional Differences in Lifestyle Metrics

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| Lifestyle Inflation | Spending Pace | Inflation compares to income; Pace compares to previous month | Inflation is long-term; Pace is short-term |
| Impulse Score | Routine Score | Opposite ends of spectrum | Typically inversely correlated |
| Weekend Ratio | Weekday Ratio | Complementary (sum to 100%) | Weekend typically higher for entertainment |

---

## Metric Group 12: Shared Expenses & Settlements

This group documents components that calculate shared expense splits and settlements.

### Variant A: Expense Split Calculation
- **Formula:**
  ```
  // Equal split
  eachPays = totalAmount / memberCount
  
  // Percentage split
  eachPays = totalAmount * memberPercentage
  
  // Custom split
  eachPays = customAmounts[memberId]
  
  // Settlement calculation
  netBalance[member] = paidAmount - owedAmount
  settlements = minimizeTransactions(netBalance)
  ```
- **Components:**
  - **SharedExpenseManager.calculateSplit()** [find exact line]
    - Supports equal, percentage, and custom splits
    - Handles currency conversion for multi-currency groups
  - **SharedExpenseManager.calculateSettlements()**
    - Minimizes number of transactions to settle balances
    - Returns list of Settlement (from, to, amount)

- **Time Window:** Per expense (instant calculation)
- **Transaction Filter:** Group expenses only
- **Amount Basis:** effectiveAmount (member's share)
- **Should Match:** YES – Single SharedExpenseManager
- **Semantic Meaning:** "How much does each person owe?"

### Variant B: Group Balance Tracking
- **Formula:**
  ```
  balance[member] = sum(paidAmounts) - sum(owedAmounts)
  groupTotal = sum(all expenses)
  averagePerMember = groupTotal / memberCount
  ```
- **Components:**
  - **SharedExpenseManager.getGroupBalances()**
    - Returns balance for each member
    - Positive = owed money, Negative = owes money
  - **SharedExpenseManager.getGroupSummary()**
    - Returns group total, average per member, member count

- **Time Window:** All time (cumulative)
- **Transaction Filter:** Group expenses only
- **Amount Basis:** effectiveAmount
- **Should Match:** YES – Single source of truth
- **Semantic Meaning:** "Who owes whom and how much?"

### Variant C: Settlement Optimization
- **Formula:**
  ```
  // Greedy algorithm to minimize transactions
  while (balances not settled) {
    maxCreditor = member with highest positive balance
    maxDebtor = member with highest negative balance
    settlementAmount = min(maxCreditor.balance, abs(maxDebtor.balance))
    settlements.add(Settlement(maxDebtor, maxCreditor, settlementAmount))
    maxCreditor.balance -= settlementAmount
    maxDebtor.balance += settlementAmount
  }
  ```
- **Components:**
  - **SharedExpenseManager.optimizeSettlements()**
    - Minimizes number of transactions needed
    - Returns optimal settlement plan

- **Should Match:** UNIQUE – Only in SharedExpenseManager
- **Semantic Meaning:** "What's the simplest way to settle up?"

---

### Intentional Differences in Shared Expense Metrics

| Component A | Component B | Why They Differ | Expected Direction |
|-------------|-------------|-----------------|-------------------|
| Equal Split | Percentage Split | Equal divides evenly; Percentage uses custom ratios | Equal = same for all; Percentage varies |
| Gross Amount | Effective Amount | Gross = full expense; Effective = member's share | Effective ≤ Gross (for shared expenses) |
| Current Balance | Settled Balance | Current includes pending; Settled only confirmed | Current typically more volatile |

---

## Updated Summary Table: All Metric Groups

| Metric Group | Components | Count | Status |
|--------------|-----------|-------|--------|
| 1. Daily Rate / Average | Insights, Pace, AdvancedAnalytics, SmartSavings, TotalsAgg | 5 | ✅ Documented |
| 2. Monthly Total | Insights, Pace, Dashboard, TotalsAgg | 4 | ✅ Documented |
| 3. Projections | Insights, Pace, Budget, FinancialWeather, Dashboard, SmartSavings | 6 | ✅ Documented |
| 4. Spending Pace | Insights, Pace, Dashboard | 3 | ✅ Documented |
| 5. Categories | Insights, AdvancedAnalytics, Dashboard, TotalsAgg | 4 | ✅ Documented |
| 6. Merchants | Insights, AdvancedAnalytics, Dashboard | 3 | ✅ Documented |
| 7. Savings | SmartSavings, Dashboard, FinancialWeather | 3 | ✅ Documented |
| 8. **Predictors & Forecasts** | SynthesisEngine, MonteCarlo, BudgetForecast, CashFlow, BillReminder, Recurrence, TaxEstimator, Dashboard | 8 | ✅ **NEW** |
| 9. **Statistical Analysis** | AnomalyDetector, FinancialHealthCalculator, SpendingThresholdCalculator | 3 | ✅ **NEW** |
| 10. **Environmental Impact** | CarbonFootprintCalculator | 1 | ✅ **NEW** |
| 11. **Lifestyle Analysis** | LifestyleInflationDetector, AdvancedAnalyticsEngine | 2 | ✅ **NEW** |
| 12. **Shared Expenses** | SharedExpenseManager | 1 | ✅ **NEW** |
| **TOTAL** | **All analytics components** | **43** | ✅ **COMPLETE** |

---

## Updated Component Cross-Reference (Complete)

### By Metric Group
1. **Daily Rate / Average** – Insights, Pace, AdvancedAnalytics, SmartSavings, TotalsAgg
2. **Monthly Total** – Insights, Pace, Dashboard, TotalsAgg
3. **Projections** – Insights, Pace, Budget, FinancialWeather, Dashboard, SmartSavings, SynthesisEngine
4. **Pace %** – Insights, Pace, Dashboard
5. **Categories** – Insights, AdvancedAnalytics, Dashboard, TotalsAgg
6. **Merchants** – Insights, AdvancedAnalytics, Dashboard
7. **Savings** – SmartSavings, Dashboard, FinancialWeather
8. **Predictors & Forecasts** – SynthesisEngine, MonteCarlo, BudgetForecast, CashFlow, BillReminder, Recurrence, TaxEstimator, Dashboard
9. **Statistical Analysis** – AnomalyDetector, FinancialHealthCalculator, SpendingThresholdCalculator, InsightsEngine
10. **Environmental Impact** – CarbonFootprintCalculator
11. **Lifestyle Analysis** – LifestyleInflationDetector, AdvancedAnalyticsEngine
12. **Shared Expenses** – SharedExpenseManager

### By Component Name (Complete - All 43)
1. **AdvancedAnalyticsEngine** – Category analytics, merchant analytics, patterns, statistical insights, spending patterns
2. **InsightsEngine** – Month comparison, category insights, merchant insights, spending pace, anomalies, recurring, day-of-week
3. **SpendingPaceCalculator** – Pace calculation, projection (simple)
4. **TotalsAggregationEngine** – Period totals (daily/weekly/monthly/yearly), averages
5. **AdvancedAnalyticsDashboard** – Top categories, top merchants, trends, insights
6. **SmartSavingsEngine** – Budget surplus, spending pace analysis, Monte Carlo integration
7. **BudgetForecastingEngine** – Predicted spending, trend detection, seasonal adjustment
8. **CashFlowCalculator** – Daily cash flow with recurring prediction
9. **ComputeDashboardWidgetsUseCase** – Widget compilation, pace widget, runway, block party, Monte Carlo forecast
10. **DashboardFollowThroughEngine** – Navigation recommendations (deterministic, not analytic)
11. **FinancialWeatherRepository** – Weather synthesis, forecast, narrative generation
12. **SynthesisEngine** – **Core prediction hub** – combines recurring, planned, budgets, pace
13. **MonteCarloSpendingSimulator** – **Stochastic simulation** – 1000 iterations, LogNormal distribution
14. **RecurrenceCalculator** – **Recurrence utilities** – next date calculation, upcoming checks
15. **BillReminderManager** – **Bill reminders** – urgency calculation, next date prediction
16. **TaxEstimator** – **Tax predictions** – income tax, VAT estimation
17. **BudgetForecastDao** – **Forecast storage** – accuracy tracking
18. **AnomalyDetector** – **Anomaly detection** – IQR, MAD, Contextual methods
19. **FinancialHealthCalculator** – **Health scoring** – composite score with volatility, savings, pace bonuses
20. **SpendingThresholdCalculator** – **Threshold calculation** – P90 of last 90 days
21. **CarbonFootprintCalculator** – **Carbon footprint** – CO2 emissions by category/merchant
22. **LifestyleInflationDetector** – **Lifestyle analysis** – income elasticity, hedonic adaptation
23. **SharedExpenseManager** – **Shared expenses** – split calculations, settlements, balances
24. **Supporting Models** – Data structures for all analytics

---

## Verification Checklist (Updated)

Use this checklist when adding new metrics:

- [ ] **Identify the semantic group** (Daily Rate, Monthly Total, Predictors, Statistical, etc.)
- [ ] **Check if similar metric exists** elsewhere in the 12 metric groups
- [ ] **Document exact formula** from code, with line numbers
- [ ] **Specify time window:** calendar month / rolling 30d / MTD / historical / forward
- [ ] **Specify filter:** PURCHASE only? Include isNotMine? All types?
- [ ] **Specify amount basis:** effectiveAmount / amount / adjusted?
- [ ] **Decide:** Should it match existing metric? Why or why not?
- [ ] **If should match:** Add unit test to verify consistency
- [ ] **If intentional difference:** Document why in "Intentional Differences" table
- [ ] **Update this map** before PR submission
- [ ] **Update Golden Master test** to include new metric

---

## Known Issues & Edge Cases (Updated)

### Issue 1-5: Previously documented (see above)

### Issue 6: BudgetForecastingEngine Uses `amount` Instead of `effectiveAmount`
**Problem:** Trend-adjusted forecast uses raw `amount` while other predictors use `effectiveAmount`
**Impact:** May differ for shared expenses or refunds
**Status:** ⚠️ INCONSISTENT
**Recommendation:** Fix to use `effectiveAmount` for consistency

### Issue 7: Monte Carlo Inputs Differ Between Dashboard and SmartSavings
**Problem:** Dashboard passes knownUpcoming from SynthesisEngine; SmartSavings passes 0.0
**Impact:** SmartSavings underestimates spending (doesn't account for committed bills)
**Status:** ⚠️ INTENTIONAL but potentially misleading
**Recommendation:** SmartSavings should also use SynthesisEngine for knownUpcoming

### Issue 8: TaxEstimator Uses Annual Window While Others Use Monthly
**Problem:** Tax predictions are annual; all other predictors are monthly
**Impact:** Not directly comparable to other metrics
**Status:** 🔹 BY DESIGN – Tax is inherently annual
**Recommendation:** Document clearly; consider monthly tax accrual widget

### Issue 9: Carbon Footprint Uses `amount` Not `effectiveAmount`
**Problem:** Carbon calculations use gross amount, not effective share
**Impact:** Shared expenses counted at full amount for carbon
**Status:** ⚠️ DEBATABLE – Environmental impact is real regardless of who pays
**Recommendation:** Document rationale; consider both gross and effective views

### Issue 10: Lifestyle Inflation Requires 6+ Months History
**Problem:** Can't calculate for new users
**Status:** 🔹 LIMITATION
**Recommendation:** Show "Insufficient data" message; use shorter windows as fallback

---

**End of Document**
