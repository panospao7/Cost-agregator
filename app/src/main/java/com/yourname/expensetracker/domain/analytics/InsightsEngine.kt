package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.DayOfWeekTotal
import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// === Engine ===

@Singleton
class InsightsEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine,
    private val timeProvider: TimeProvider
) {

    companion object {
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    suspend fun generateInsights(
        categories: List<Category>,
        allExpenses: List<Expense>
    ): InsightsSnapshot = coroutineScope {
        val now = timeProvider.now()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)

        val categoryMap = categories.associateBy { it.id }

        // Start all independent queries in parallel
        val monthlyComparisonDeferred = async { buildMonthlyComparison(currentMonth, previousMonth) }
        val categoryInsightsDeferred = async { buildCategoryInsights(currentMonth, previousMonth, categoryMap, allExpenses) }
        val topMerchantsDeferred = async { buildMerchantInsights(allExpenses) }
        val spendingPaceDeferred = async { buildSpendingPace(currentMonth, previousMonth, allExpenses) }
        val anomaliesDeferred = async { findAnomalies(currentMonth, categoryMap) }
        // Use RecurringExpenseEngine directly
        val recurringExpensesDeferred = async { findRecurringExpenses(allExpenses) }
        
        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPatternDeferred = async { buildDayOfWeekPattern(threeMonthsAgo.startMs, currentMonth.endMs) }
        val largestTransactionDeferred = async { 
            expenseRepository.getLargestExpenseForPeriod(currentMonth.startMs, currentMonth.endMs) 
        }

        // Await all results
        val monthlyComparison = monthlyComparisonDeferred.await()
        val categoryInsights = categoryInsightsDeferred.await()
        val topMerchants = topMerchantsDeferred.await()
        val spendingPace = spendingPaceDeferred.await()
        val anomalies = anomaliesDeferred.await()
        val recurringExpenses = recurringExpensesDeferred.await()
        val dayOfWeekPattern = dayOfWeekPatternDeferred.await()
        val largestTransaction = largestTransactionDeferred.await()

        // Transaction size stats
        val currentMonthPurchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.date >= currentMonth.startMs
                    && it.date < currentMonth.endMs
        }
        val avgTxSize = if (currentMonthPurchases.isNotEmpty())
            currentMonthPurchases.map { it.amount }.average() else 0.0
        val medianTxSize = calculateMedian(currentMonthPurchases.map { it.amount })

        // How many months of data we have
        val totalMonthsOfData = countDistinctMonths(allExpenses)

        InsightsSnapshot(
            currentMonth = currentMonth,
            monthlyComparison = monthlyComparison,
            categoryInsights = categoryInsights,
            topMerchants = topMerchants,
            spendingPace = spendingPace,
            anomalies = anomalies,
            recurringExpenses = recurringExpenses,
            dayOfWeekPattern = dayOfWeekPattern,
            largestTransaction = largestTransaction,
            averageTransactionSize = avgTxSize,
            medianTransactionSize = medianTxSize,
            totalMonthsOfData = totalMonthsOfData
        )
    }

    // === Legacy Compatibility ===

    fun getLegacyInsights(snapshot: InsightsSnapshot): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()

        // 1. Monthly Comparison (Spending Increase/Decrease)
        val comparison = snapshot.monthlyComparison
        if (comparison.changePercentage != null) {
            if (comparison.changePercentage > 20) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_INCREASE, "📈",
                        "Spending up ${comparison.changePercentage.toInt()}%",
                        "€${fmt(comparison.currentTotal)} this month vs €${fmt(comparison.previousTotal ?: 0.0)} last month",
                        (comparison.changePercentage / 100).coerceAtMost(1.0f).toFloat()
                    )
                )
            } else if (comparison.changePercentage < -15) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_DECREASE, "📉",
                        "Good job! Spending down ${(-comparison.changePercentage).toInt()}%",
                        "You saved €${fmt((comparison.previousTotal ?: 0.0) - comparison.currentTotal)} compared to last month",
                        0.3f
                    )
                )
            }
        }

        // 2. Spending Pace
        val pace = snapshot.spendingPace
        if (pace.paceStatus == PaceStatus.OVER_PACE) {
             insights.add(
                SpendingInsight(
                    InsightType.BUDGET_WARNING, "⚠️",
                    "Spending Pace Warning",
                    "You're at ${pace.daysElapsed} days but spent ${pace.pacePercentage.toInt()}% of typical month",
                    0.8f
                )
            )
        }

        // 3. Category Trends
        snapshot.categoryInsights.take(3).forEach { catInsight ->
            if (catInsight.changeFromPrevious != null) {
                 if (catInsight.changeFromPrevious > 40 && catInsight.currentTotal > 50) {
                    insights.add(
                        SpendingInsight(
                            InsightType.CATEGORY_TREND, catInsight.category.icon,
                            "${catInsight.category.name} up ${catInsight.changeFromPrevious.toInt()}%",
                            "€${fmt(catInsight.currentTotal)} vs €${fmt(catInsight.previousTotal ?: 0.0)}",
                            0.7f
                        )
                    )
                 }
            }
        }

        // 4. Recurring
        snapshot.recurringExpenses.take(3).forEach { recurring ->
                 if (recurring.intervalDays > 0) {
                     insights.add(
                        SpendingInsight(
                            InsightType.RECURRING_DETECTED, "🔄",
                            "Recurring: ${recurring.merchant}",
                            "€${fmt(recurring.avgAmount)} ~every ${recurring.intervalDays} days",
                            0.5f
                        )
                    )
                 }
        }

        // 5. Largest Transaction
        val largest = snapshot.largestTransaction
        if (largest != null && largest.amount > 50) {
             insights.add(
                SpendingInsight(
                    InsightType.UNUSUAL_TRANSACTION, "⚡",
                    "Largest: ${largest.merchant}",
                    "€${fmt(largest.amount)} on ${formatDate(largest.date)}",
                    0.25f
                )
            )
        }

        return insights.sortedByDescending { it.severity }
    }

    // === Month Period Helpers ===

    // === Month Period Helpers ===
    
    fun getMonthPeriod(timeMs: Long, monthOffset: Int = 0): MonthPeriod {
        // Use TimePeriodUtils for start/end
        val range = com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(timeMs, monthOffset)
        
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        cal.timeInMillis = range.first
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        
        return MonthPeriod(year, month, range.first, range.second + 1) // +1 because Utils gives inclusive end, MonthPeriod likely uses exclusive end or similar. 
        // Logic check: PeriodRange is usually inclusive. ExpenseDao queries are simpler with inclusive/exclusive.
        // Let's standardise. MonthPeriod seems to store start/end.
        // Existing implementation: endMs is start of *next* month (exclusive).
        // TimePeriodUtils.getMonthRange returns (start, end) inclusive (last millisecond).
        // So endMs = utils.end + 1
    }

    private fun getPreviousMonthPeriod(current: MonthPeriod): MonthPeriod {
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        cal.timeInMillis = current.startMs
        cal.add(Calendar.MONTH, -1)
        return getMonthPeriod(cal.timeInMillis)
    }

    // === Monthly Comparison ===

    private suspend fun buildMonthlyComparison(
        current: MonthPeriod,
        previous: MonthPeriod
    ): MonthlyComparison = coroutineScope {
        val currentTotalDeferred = async { expenseRepository.getTotalForPeriod(current.startMs, current.endMs) }
        val currentCountDeferred = async { expenseRepository.getCountForPeriod(current.startMs, current.endMs) }
        val previousTotalDeferred = async { expenseRepository.getTotalForPeriod(previous.startMs, previous.endMs) }
        val previousCountDeferred = async { expenseRepository.getCountForPeriod(previous.startMs, previous.endMs) }

        val currentTotal = currentTotalDeferred.await()
        val currentCount = currentCountDeferred.await()
        val previousTotal = previousTotalDeferred.await()
        val previousCount = previousCountDeferred.await()

        val hasPrevious = previousCount > 0
        val changeAmount = if (hasPrevious) currentTotal - previousTotal else null
        val changePercentage = if (hasPrevious && previousTotal > 0)
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat() else null

        MonthlyComparison(
            currentMonth = current,
            previousMonth = if (hasPrevious) previous else null,
            currentTotal = currentTotal,
            previousTotal = if (hasPrevious) previousTotal else null,
            changeAmount = changeAmount,
            changePercentage = changePercentage,
            currentCount = currentCount,
            previousCount = if (hasPrevious) previousCount else null
        )
    }

    // === Category Insights ===

    private suspend fun buildCategoryInsights(
        current: MonthPeriod,
        previous: MonthPeriod,
        categoryMap: Map<Long, Category>,
        allExpenses: List<Expense>
    ): List<CategoryInsight> = coroutineScope {
        val currentTotalsDeferred = async { expenseRepository.getCategoryTotalsForPeriod(current.startMs, current.endMs) }
        val previousTotalsDeferred = async { expenseRepository.getCategoryTotalsForPeriod(previous.startMs, previous.endMs) }

        val currentTotals = currentTotalsDeferred.await()
        val previousTotals = previousTotalsDeferred.await()
        val previousMap = previousTotals.associateBy { it.categoryId }

        val currentGrandTotal = currentTotals.sumOf { it.total }

        // Calculate multi-month averages per category
        val monthlyAverages = calculateCategoryMonthlyAverages(allExpenses, current)

        currentTotals.mapNotNull { ct ->
            val category = categoryMap[ct.categoryId]
            if (category == null) {
                android.util.Log.w("InsightsEngine", "Category ${ct.categoryId} not found for expense integration")
                return@mapNotNull null
            }
            val prev = previousMap[ct.categoryId]
            val avgData = monthlyAverages[ct.categoryId]

            val changeFromPrev = if (prev != null && prev.total > 0)
                ((ct.total - prev.total) / prev.total * 100).toFloat() else null

            val changeFromAvg = if (avgData != null && avgData.first > 0)
                ((ct.total - avgData.first) / avgData.first * 100).toFloat() else null

            CategoryInsight(
                category = category,
                currentTotal = ct.total,
                currentCount = ct.txCount,
                previousTotal = prev?.total,
                previousCount = prev?.txCount,
                averageOverMonths = avgData?.first,
                monthsOfData = avgData?.second ?: 0,
                percentageOfTotal = if (currentGrandTotal > 0)
                    (ct.total / currentGrandTotal * 100).toFloat() else 0f,
                changeFromPrevious = changeFromPrev,
                changeFromAverage = changeFromAvg
            )
        }.sortedByDescending { it.currentTotal }
    }

    private fun calculateCategoryMonthlyAverages(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Map<Long, Pair<Double, Int>> {
        // Group purchases by category, then by month, compute average
        val purchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.categoryId != null
                    && it.date < currentMonth.startMs // exclude current month
        }

        // Group by categoryId -> month key -> sum
        val categoryMonthTotals = mutableMapOf<Long, MutableMap<String, Double>>()
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        for (expense in purchases) {
            val catId = expense.categoryId ?: continue
            cal.timeInMillis = expense.date
            val monthKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            categoryMonthTotals
                .getOrPut(catId) { mutableMapOf() }
                .merge(monthKey, expense.amount) { a, b -> a + b }
        }

        // For each category, compute average monthly spend
        return categoryMonthTotals.mapValues { (_, monthMap) ->
            val months = monthMap.size
            val avg = if (months > 0) monthMap.values.sum() / months else 0.0
            Pair(avg, months)
        }
    }

    // === Merchant Insights ===

    private suspend fun buildMerchantInsights(
        allExpenses: List<Expense>
    ): List<MerchantInsight> {
        val stats = expenseRepository.getAllMerchantStats()

        // For std deviation, compute from raw data grouped by merchant
        val purchasesByMerchant = allExpenses
            .filter { it.transactionType == TransactionType.PURCHASE }
            .groupBy { it.merchant }

        return stats.map { ms ->
            val amounts = purchasesByMerchant[ms.merchantName]?.map { it.amount } ?: emptyList()
            val stdDev = if (amounts.size >= 3) calculateStdDev(amounts) else null

            val isRecurring = ms.transactionCount >= 2 &&
                    (ms.maxAmount - ms.minAmount) < (ms.averageAmount * 0.15)

            MerchantInsight(
                merchant = ms.merchantName,
                avgAmount = ms.averageAmount,
                minAmount = ms.minAmount,
                maxAmount = ms.maxAmount,
                totalSpent = ms.totalAmount,
                transactionCount = ms.transactionCount,
                isLikelyRecurring = isRecurring,
                stdDeviation = stdDev
            )
        }.sortedByDescending { it.totalSpent }
    }

    // === Spending Pace ===

    private suspend fun buildSpendingPace(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod,
        allExpenses: List<Expense>
    ): SpendingPace = coroutineScope {
        val now = timeProvider.now()
        
        val currentSpentDeferred = async { expenseRepository.getTotalForPeriod(currentMonth.startMs, currentMonth.endMs) }
        val previousTotalDeferred = async { expenseRepository.getTotalForPeriod(previousMonth.startMs, previousMonth.endMs) }
        val previousCountDeferred = async { expenseRepository.getCountForPeriod(previousMonth.startMs, previousMonth.endMs) }

        val currentSpent = currentSpentDeferred.await()
        val previousTotal = previousTotalDeferred.await()
        val previousCount = previousCountDeferred.await()

        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        cal.timeInMillis = now
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // Calculate average monthly total (excluding current month)
        val avgMonthly = calculateAverageMonthlySpend(allExpenses, currentMonth)

        // Project: if we've spent X in D days, we'll spend X * (totalDays/D) by month end
        // Project: if we've spent X in D days, we'll spend X * (totalDays/D) by month end
        val projectedTotal = if (dayOfMonth >= 4) {
            currentSpent * daysInMonth.toDouble() / dayOfMonth
        } else if (dayOfMonth > 0) {
            // Conservative estimate for first 3 days to avoid massive multipliers
            currentSpent * (daysInMonth.toDouble() / 10.0).coerceAtLeast(1.0)
        } else {
            currentSpent
        }

        // Pace percentage: how much of the baseline have we consumed
        val baseline = avgMonthly ?: if (previousCount > 0) previousTotal else null
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expectedAtThisPoint = baseline * dayOfMonth.coerceAtLeast(1) / daysInMonth
            (currentSpent / expectedAtThisPoint * 100).toFloat()
        } else 0f

        val paceStatus = when {
            baseline == null || baseline == 0.0 -> PaceStatus.NO_BASELINE
            pacePercentage < 90f -> PaceStatus.UNDER_PACE
            pacePercentage > 110f -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }

        SpendingPace(
            currentMonthSpent = currentSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousCount > 0) previousTotal else null,
            averageMonthlyTotal = avgMonthly,
            pacePercentage = pacePercentage,
            paceStatus = paceStatus
        )
    }

    private fun calculateAverageMonthlySpend(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Double? {
        val purchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.date < currentMonth.startMs
        }
        if (purchases.isEmpty()) return null

        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val monthTotals = mutableMapOf<String, Double>()
        for (p in purchases) {
            cal.timeInMillis = p.date
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            monthTotals.merge(key, p.amount) { a, b -> a + b }
        }

        return if (monthTotals.isNotEmpty()) monthTotals.values.average() else null
    }

    // === Anomaly Detection ===

    private suspend fun findAnomalies(
        currentMonth: MonthPeriod,
        categoryMap: Map<Long, Category>
    ): List<AnomalyTransaction> = coroutineScope {
        val merchantStats = expenseRepository.getMerchantStats()
        val statsMap: Map<String, MerchantStats> = merchantStats.associateBy { it.merchantName }

        val topMerchants = expenseRepository.getTopMerchantsForPeriod(
            currentMonth.startMs, currentMonth.endMs, 100
        )

        val candidates: List<AnomalyCandidate?> = topMerchants.mapNotNull { merchantStat: MerchantStats ->
            val historicalStats: MerchantStats? = statsMap[merchantStat.merchantName]
            if (historicalStats == null || historicalStats.transactionCount < 3) return@mapNotNull null

            val multiplier = when {
                historicalStats.transactionCount < 5 -> 5.0
                historicalStats.transactionCount < 10 -> 4.0
                else -> 3.0
            }

            if (merchantStat.maxAmount > historicalStats.averageAmount * multiplier) {
                AnomalyCandidate(
                    merchantName = merchantStat.merchantName,
                    maxAmount = merchantStat.maxAmount,
                    historicalAvg = historicalStats.averageAmount,
                    deviationMultiple = (merchantStat.maxAmount / historicalStats.averageAmount).toFloat()
                )
            } else null
        }

        val topCandidates: List<AnomalyCandidate> = candidates
            .filterNotNull()
            .sortedByDescending { it.deviationMultiple }
            .take(5)

        val deferredAnomalies = topCandidates.map { candidate: AnomalyCandidate ->
            async {
                expenseRepository.getLargestExpenseForMerchant(
                    candidate.merchantName, currentMonth.startMs, currentMonth.endMs
                )?.let { expense ->
                    AnomalyTransaction(
                        expense = expense,
                        merchantAvg = candidate.historicalAvg,
                        deviationMultiple = candidate.deviationMultiple,
                        category = expense.categoryId?.let { categoryMap[it] }
                    )
                }
            }
        }

        deferredAnomalies.awaitAll().filterNotNull()
    }

    private data class AnomalyCandidate(
        val merchantName: String,
        val maxAmount: Double,
        val historicalAvg: Double,
        val deviationMultiple: Float
    )

    // === Recurring Expenses ===

    // === Recurring Expenses ===

    private suspend fun findRecurringExpenses(allExpenses: List<Expense>): List<RecurringExpense> {
        // Use the centralized engine
        val patterns = recurringExpenseEngine.getPatterns(allExpenses)
        
        // Map to Insights Snapshot model
        return patterns.map { pattern ->
            val intervalDays = when (pattern.frequency) {
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.WEEKLY -> 7
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.BIWEEKLY -> 14
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY -> 30
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.QUARTERLY -> 90
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.SEMI_ANNUALLY -> 180
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.ANNUALLY -> 365
                else -> 0
            }
            
            RecurringExpense(
                merchant = pattern.merchantName,
                avgAmount = pattern.averageAmount,
                frequency = (30.0 / intervalDays.coerceAtLeast(1)).toInt(), // Estimate monthly occurrences
                intervalDays = intervalDays,
                amountVariation = 0.0, // Pattern doesn't expose this raw stat easily, but could add to Pattern if needed.
                isStable = pattern.amountVariancePercent < 0.1
            )
        }
    }

    // === Day of Week Pattern ===

    private suspend fun buildDayOfWeekPattern(
        startMs: Long,
        endMs: Long
    ): List<DayOfWeekInsight> {
        val timeZoneOffset = java.util.TimeZone.getDefault().getOffset(timeProvider.now())
        val data = expenseRepository.getDayOfWeekPattern(startMs, endMs, timeZoneOffset)

        val dayMap: Map<Int, DayOfWeekTotal> = data.associateBy { it.dayOfWeek }
        return (0..6).map { dayIndex: Int ->
            val d: DayOfWeekTotal? = dayMap[dayIndex]
            DayOfWeekInsight(
                dayName = DAY_NAMES[dayIndex],
                dayIndex = dayIndex,
                totalSpent = d?.total ?: 0.0,
                transactionCount = d?.txCount ?: 0,
                avgPerTransaction = d?.avgAmount ?: 0.0
            )
        }
    }

    // === Utility Functions ===
    
    fun buildDailyTotals(expenses: List<Expense>, days: Int): Map<String, Double> {
        val now = timeProvider.now()
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val result = LinkedHashMap<String, Double>()

        // Initialize all days with 0
        for (i in days - 1 downTo 0) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val key = DateFormatterUtils.dateKey().format(cal.time)
            result[key] = 0.0
        }

        // Fill in actual values - Optimized: reuse Date object
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val dateObj = java.util.Date()
        for (expense in purchases) {
            dateObj.time = expense.date
            val key = DateFormatterUtils.dateKey().format(dateObj)
            if (result.containsKey(key)) {
                result[key] = (result[key] ?: 0.0) + expense.amount
            }
        }

        return result
    }

    private fun calculateMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun calculateStdDev(values: List<Double>): Double {
        return com.yourname.expensetracker.domain.util.StatisticsUtils.calculateStdDev(values)
    }

    private fun countDistinctMonths(expenses: List<Expense>): Int {
        if (expenses.isEmpty()) return 0
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        return expenses.map { expense ->
            cal.timeInMillis = expense.date
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        }.distinct().size
    }

    // === Exposed Suspend Functions for Repository Usage ===
    
    suspend fun getSpendingPaceSuspend(expenses: List<Expense>? = null): SpendingPace {
        val now = timeProvider.now()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)
        
        // Use provided expenses or fetch from DB if null
        val recentExpenses = expenses ?: run {
            val sixMonthsAgo = getMonthPeriod(now, -6).startMs
            expenseRepository.getExpensesBetween(sixMonthsAgo, now)
        }
        
        return buildSpendingPace(currentMonth, previousMonth, recentExpenses)
    }

    private fun fmt(amount: Double): String = String.format(java.util.Locale.US, "%.2f", amount)
    
    private fun formatDate(dateMs: Long): String {
         return DateFormatterUtils.monthDay().format(java.util.Date(dateMs))
    }
}
