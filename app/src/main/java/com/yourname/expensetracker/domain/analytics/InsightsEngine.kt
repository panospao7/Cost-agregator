package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// === Engine ===

@Singleton
class InsightsEngine @Inject constructor(
    private val expenseDao: ExpenseDao
) {

    companion object {
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    suspend fun generateInsights(
        categories: List<Category>,
        allExpenses: List<Expense>
    ): InsightsSnapshot {
        val now = System.currentTimeMillis()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)

        val categoryMap = categories.associateBy { it.id }

        // Monthly comparison
        val monthlyComparison = buildMonthlyComparison(currentMonth, previousMonth)

        // Category insights (current vs previous vs average)
        val categoryInsights = buildCategoryInsights(
            currentMonth, previousMonth, categoryMap, allExpenses
        )

        // Top merchants this month
        val topMerchants = buildMerchantInsights(allExpenses)

        // Spending pace
        val spendingPace = buildSpendingPace(currentMonth, previousMonth, allExpenses)

        // Anomalies (transactions that deviate significantly from merchant average)
        val anomalies = findAnomalies(currentMonth, categoryMap)

        // Recurring expenses
        val recurringExpenses = findRecurringExpenses()

        // Day of week pattern (last 3 months)
        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPattern = buildDayOfWeekPattern(threeMonthsAgo.startMs, currentMonth.endMs)

        // Largest transaction this month
        val largestTransaction = expenseDao.getLargestExpenseForPeriod(
            currentMonth.startMs, currentMonth.endMs
        )

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

        return InsightsSnapshot(
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
             insights.add(
                SpendingInsight(
                    InsightType.RECURRING_DETECTED, "🔄",
                    "Recurring: ${recurring.merchant}",
                    "€${fmt(recurring.avgAmount)} ~every ${30.0/recurring.frequency} days", // approx
                    0.5f
                )
            )
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

    fun getMonthPeriod(timeMs: Long, monthOffset: Int = 0): MonthPeriod {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        cal.add(Calendar.MONTH, monthOffset)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)

        cal.add(Calendar.MONTH, 1)
        val endMs = cal.timeInMillis

        return MonthPeriod(year, month, startMs, endMs)
    }

    private fun getPreviousMonthPeriod(current: MonthPeriod): MonthPeriod {
        val cal = Calendar.getInstance()
        cal.timeInMillis = current.startMs
        cal.add(Calendar.MONTH, -1)
        return getMonthPeriod(cal.timeInMillis)
    }

    // === Monthly Comparison ===

    private suspend fun buildMonthlyComparison(
        current: MonthPeriod,
        previous: MonthPeriod
    ): MonthlyComparison {
        val currentTotal = expenseDao.getTotalForPeriod(current.startMs, current.endMs)
        val currentCount = expenseDao.getCountForPeriod(current.startMs, current.endMs)
        val previousTotal = expenseDao.getTotalForPeriod(previous.startMs, previous.endMs)
        val previousCount = expenseDao.getCountForPeriod(previous.startMs, previous.endMs)

        val hasPrevious = previousCount > 0
        val changeAmount = if (hasPrevious) currentTotal - previousTotal else null
        val changePercentage = if (hasPrevious && previousTotal > 0)
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat() else null

        return MonthlyComparison(
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
    ): List<CategoryInsight> {
        val currentTotals = expenseDao.getCategoryTotalsForPeriod(current.startMs, current.endMs)
        val previousTotals = expenseDao.getCategoryTotalsForPeriod(previous.startMs, previous.endMs)
        val previousMap = previousTotals.associateBy { it.categoryId }

        val currentGrandTotal = currentTotals.sumOf { it.total }

        // Calculate multi-month averages per category
        val monthlyAverages = calculateCategoryMonthlyAverages(allExpenses, current)

        return currentTotals.mapNotNull { ct ->
            val category = categoryMap[ct.categoryId] ?: return@mapNotNull null
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
        val cal = Calendar.getInstance()
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
        val stats = expenseDao.getAllMerchantStats()

        // For std deviation, compute from raw data grouped by merchant
        val purchasesByMerchant = allExpenses
            .filter { it.transactionType == TransactionType.PURCHASE }
            .groupBy { it.merchant }

        return stats.map { ms ->
            val amounts = purchasesByMerchant[ms.merchant]?.map { it.amount } ?: emptyList()
            val stdDev = if (amounts.size >= 3) calculateStdDev(amounts) else null

            val isRecurring = ms.txCount >= 2 &&
                    (ms.maxAmount - ms.minAmount) < (ms.avgAmount * 0.15)

            MerchantInsight(
                merchant = ms.merchant,
                avgAmount = ms.avgAmount,
                minAmount = ms.minAmount,
                maxAmount = ms.maxAmount,
                totalSpent = ms.totalAmount,
                transactionCount = ms.txCount,
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
    ): SpendingPace {
        val now = System.currentTimeMillis()
        val currentSpent = expenseDao.getTotalForPeriod(currentMonth.startMs, currentMonth.endMs)
        val previousTotal = expenseDao.getTotalForPeriod(previousMonth.startMs, previousMonth.endMs)
        val previousCount = expenseDao.getCountForPeriod(previousMonth.startMs, previousMonth.endMs)

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // Calculate average monthly total (excluding current month)
        val avgMonthly = calculateAverageMonthlySpend(allExpenses, currentMonth)

        // Project: if we've spent X in D days, we'll spend X * (totalDays/D) by month end
        val projectedTotal = if (dayOfMonth > 0)
            currentSpent * daysInMonth.toDouble() / dayOfMonth else currentSpent

        // Pace percentage: how much of the baseline have we consumed
        val baseline = avgMonthly ?: if (previousCount > 0) previousTotal else null
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expectedAtThisPoint = baseline * dayOfMonth / daysInMonth
            (currentSpent / expectedAtThisPoint * 100).toFloat()
        } else 0f

        val paceStatus = when {
            baseline == null || baseline == 0.0 -> PaceStatus.NO_BASELINE
            pacePercentage < 90f -> PaceStatus.UNDER_PACE
            pacePercentage > 110f -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }

        return SpendingPace(
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

        val cal = Calendar.getInstance()
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
    ): List<AnomalyTransaction> {
        val merchantStats = expenseDao.getMerchantStats() // only merchants with 2+ tx
        val statsMap = merchantStats.associateBy { it.merchant }

        val anomalies = mutableListOf<AnomalyTransaction>()

        // Check top merchants this month for outliers
        val topMerchants = expenseDao.getTopMerchantsForPeriod(
            currentMonth.startMs, currentMonth.endMs, 100
        )

        for (merchantStat in topMerchants) {
            val historicalStats = statsMap[merchantStat.merchant] ?: continue
            // We need 3+ transactions historically to have a reliable average
            if (historicalStats.txCount < 3) continue

            // If the max amount this month is > 2x the historical average
            if (merchantStat.maxAmount > historicalStats.avgAmount * 2.0) {
                // Find the actual expense (largest for this merchant this month)
                val expense = expenseDao.getLargestExpenseForPeriod(
                    currentMonth.startMs, currentMonth.endMs
                )
                // Filter specifically for this merchant
                if (expense != null && expense.merchant == merchantStat.merchant) {
                     anomalies.add(
                        AnomalyTransaction(
                            expense = expense,
                            merchantAvg = historicalStats.avgAmount,
                            deviationMultiple = (expense.amount / historicalStats.avgAmount).toFloat(),
                            category = expense.categoryId?.let { categoryMap[it] }
                        )
                    )
                }
            }
        }

        return anomalies.sortedByDescending { it.deviationMultiple }.take(5)
    }

    // === Recurring Expenses ===

    private suspend fun findRecurringExpenses(): List<RecurringExpense> {
        val candidates = expenseDao.getRecurringCandidates()

        return candidates.map { ms ->
            RecurringExpense(
                merchant = ms.merchant,
                avgAmount = ms.avgAmount,
                frequency = ms.txCount,
                amountVariation = ms.maxAmount - ms.minAmount,
                isStable = (ms.maxAmount - ms.minAmount) < (ms.avgAmount * 0.05)
            )
        }
    }

    // === Day of Week Pattern ===

    private suspend fun buildDayOfWeekPattern(
        startMs: Long,
        endMs: Long
    ): List<DayOfWeekInsight> {
        val data = expenseDao.getDayOfWeekPattern(startMs, endMs)

        // Fill in missing days with zeros
        val dayMap = data.associateBy { it.dayOfWeek }
        return (0..6).map { dayIndex ->
            val d = dayMap[dayIndex]
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
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val result = LinkedHashMap<String, Double>()
        val dateKeyFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        // Initialize all days with 0
        for (i in days - 1 downTo 0) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val key = dateKeyFormat.format(cal.time)
            result[key] = 0.0
        }

        // Fill in actual values
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        for (expense in purchases) {
            val key = dateKeyFormat.format(java.util.Date(expense.date))
            if (result.containsKey(key)) {
                result[key] = (result[key] ?: 0.0) + expense.amount
            }
        }

        return result
    }

    // Make detectRecurring available for ViewModel compatibility if needed
    // But it's better to use the snapshot.
    // We already have findRecurringExpenses internally.
    
    // Legacy helper for detections from list
    fun detectRecurring(expenses: List<Expense>): List<com.yourname.expensetracker.domain.analytics.RecurringCandidate> {
         val dayMs = 86_400_000L
         val results = mutableListOf<com.yourname.expensetracker.domain.analytics.RecurringCandidate>()
         val byMerchant = expenses
            .filter { it.transactionType == TransactionType.PURCHASE }
            .groupBy { it.merchant.uppercase() }

         for ((merchant, exps) in byMerchant) {
             if (exps.size < 2) continue
             val sorted = exps.sortedBy { it.date }

             // Check if amounts are similar (within 15%)
             val amounts = sorted.map { it.amount }
             val avgAmount = amounts.average()
             val allSimilar = amounts.all { it > 0 && Math.abs(it - avgAmount) / avgAmount < 0.15 }

             if (allSimilar && sorted.size >= 2) {
                 val intervals = sorted.zipWithNext().map { (a, b) ->
                     ((b.date - a.date) / dayMs).toInt()
                 }
                 val avgInterval = intervals.average().toInt()

                 val isRecurring = avgInterval in 5..9 ||
                         avgInterval in 12..16 ||
                         avgInterval in 25..35 ||
                         avgInterval in 350..380

                 if (isRecurring) {
                     val lastDate = sorted.last().date
                     val nextExpected = lastDate + avgInterval * dayMs

                     results.add(
                         com.yourname.expensetracker.domain.analytics.RecurringCandidate(
                             merchant = exps.first().merchant,
                             amount = avgAmount,
                             intervalDays = avgInterval,
                             occurrences = sorted.size,
                             nextExpectedDate = nextExpected
                         )
                     )
                 }
             }
         }
         return results.sortedByDescending { it.occurrences }
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
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun countDistinctMonths(expenses: List<Expense>): Int {
        if (expenses.isEmpty()) return 0
        val cal = Calendar.getInstance()
        return expenses.map { expense ->
            cal.timeInMillis = expense.date
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        }.distinct().size
    }

    private fun fmt(amount: Double): String = String.format("%.2f", amount)
    
    private fun formatDate(dateMs: Long): String {
         val format = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
         return format.format(java.util.Date(dateMs))
    }
}
