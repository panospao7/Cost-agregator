package com.yourname.expensetracker.domain.analytics

import timber.log.Timber
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Advanced analytics engine for detailed spending analysis.
 * Provides temporal category breakdowns, merchant intelligence, and statistical insights.
 */
@Singleton
class AdvancedAnalyticsEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val TAG = "AdvancedAnalytics"
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
    }
    
    // ============================================================
    // PERIOD CALCULATIONS
    // ============================================================
    
    /**
     * Calculates the period range for the given analytics period type.
     * @param period The period type to calculate
     * @param referenceDate Reference timestamp (defaults to now)
     * @return PeriodRange with start/end timestamps and comparison period
     */
    fun getPeriodRange(
        period: AnalyticsPeriod,
        referenceDate: Long = timeProvider.now(),
        computeComparison: Boolean = true
    ): PeriodRange {
        val (startMs, endMs, label) = when (period) {
            AnalyticsPeriod.WEEK -> calculateWeekRange(referenceDate)
            AnalyticsPeriod.MONTH -> calculateMonthRange(referenceDate)
            AnalyticsPeriod.QUARTER -> calculateQuarterRange(referenceDate)
            AnalyticsPeriod.YEAR -> calculateYearRange(referenceDate)
            AnalyticsPeriod.CUSTOM -> throw IllegalArgumentException(
                "Custom period requires explicit date range. Use getCustomPeriodRange() instead."
            )
        }
        
        return PeriodRange(
            period = period,
            startMs = startMs,
            endMs = endMs,
            label = label,
            comparisonRange = if (computeComparison) getPreviousPeriodRange(period, startMs) else null
        )
    }
    
    private fun calculateWeekRange(referenceDate: Long): Triple<Long, Long, String> {
        val start = TimePeriodUtils.getStartOfWeek(referenceDate)
        val end = start + (7 * MILLIS_PER_DAY)
        
        val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
        return Triple(start, end, "${fmt.format(Date(start))} - ${fmt.format(Date(end - 1))}")
    }
    
    private fun calculateMonthRange(referenceDate: Long): Triple<Long, Long, String> {
        val start = TimePeriodUtils.getStartOfMonth(referenceDate)
        val end = TimePeriodUtils.getEndOfMonth(start) + 1
        
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return Triple(start, end, fmt.format(Date(start)))
    }
    
    private fun calculateQuarterRange(referenceDate: Long): Triple<Long, Long, String> {
        val start = TimePeriodUtils.getStartOfQuarter(referenceDate)
        val end = TimePeriodUtils.getEndOfQuarter(start) + 1
        
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        cal.timeInMillis = start
        val quarterNum = (cal.get(Calendar.MONTH) / 3) + 1
        val year = cal.get(Calendar.YEAR)
        
        return Triple(start, end, "Q$quarterNum $year")
    }
    
    private fun calculateYearRange(referenceDate: Long): Triple<Long, Long, String> {
        val start = TimePeriodUtils.getStartOfYear(referenceDate)
        val end = TimePeriodUtils.getEndOfYear(start) + 1
        
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        cal.timeInMillis = start
        val year = cal.get(Calendar.YEAR)
        
        return Triple(start, end, year.toString())
    }
    
    private fun getPreviousPeriodRange(period: AnalyticsPeriod, currentStartMs: Long): PeriodRange? {
        return try {
            val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
            cal.timeInMillis = currentStartMs
            
            when (period) {
                AnalyticsPeriod.WEEK -> cal.add(Calendar.DAY_OF_MONTH, -7)
                AnalyticsPeriod.MONTH -> cal.add(Calendar.MONTH, -1)
                AnalyticsPeriod.QUARTER -> cal.add(Calendar.MONTH, -3)
                AnalyticsPeriod.YEAR -> cal.add(Calendar.YEAR, -1)
                AnalyticsPeriod.CUSTOM -> return null
            }
            
            getPeriodRange(period, cal.timeInMillis, computeComparison = false)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to calculate previous period")
            null
        }
    }
    
    // ============================================================
    // CATEGORY ANALYTICS
    // ============================================================
    
    /**
     * Generates enhanced analytics for all categories within the specified period.
     */
    suspend fun getCategoryAnalytics(period: PeriodRange): List<EnhancedCategoryAnalytics> = withContext(Dispatchers.Default) {
        coroutineScope {
        // Fetch all required data in parallel
        val currentExpensesDeferred = async { 
            expenseRepository.getExpensesBetween(period.startMs, period.endMs) 
        }
        val previousExpensesDeferred = async { 
            period.comparisonRange?.let { 
                expenseRepository.getExpensesBetween(it.startMs, it.endMs) 
            } ?: emptyList()
        }
        val categoriesDeferred = async { categoryRepository.getAll() }
        val budgetsDeferred = async { budgetRepository.getActiveBudgets() }
        
        val currentExpenses = currentExpensesDeferred.await()
        val previousExpenses = previousExpensesDeferred.await()
        val categories = categoriesDeferred.await()
        val budgets = budgetsDeferred.await()
        
        // Filter to purchases only
        val currentPurchases = currentExpenses.filter { it.transactionType == TransactionType.PURCHASE }
        val previousPurchases = previousExpenses.filter { it.transactionType == TransactionType.PURCHASE }
        
        // Build lookup maps
        val categoryMap = categories.associateBy { it.id }
        val budgetMap = budgets.associateBy { it.categoryId }
        
        // Group expenses by category
        val currentByCategory = currentPurchases.groupBy { it.categoryId }
        val previousByCategory = previousPurchases.groupBy { it.categoryId }
        
        // Build sparkline data (daily cumulative by category)
        val sparklineData = buildSparklineDataByCategory(currentPurchases, period)
        
        // Build analytics for each category with spending
        currentByCategory.mapNotNull { (categoryId, expenses) ->
            val category = categoryMap[categoryId] ?: return@mapNotNull null
            
            val amounts = expenses.map { it.amount }
            val sortedAmounts = amounts.sorted()
            val total = amounts.sum()
            
            // Previous period comparison
            val previousTotal = previousByCategory[categoryId]?.sumOf { it.amount }
            val changePercent = calculateChangePercent(total, previousTotal)
            
            // Budget context
            val budget = budgetMap[categoryId]
            val budgetUtilization = budget?.let { b ->
                if (b.amount > 0) (total / b.amount * 100).toFloat() else null
            }
            val budgetStatus = budget?.let { b -> determineBudgetStatus(total, b.amount) }
            
            // Percentiles
            val p25 = getPercentile(sortedAmounts, 0.25)
            val p75 = getPercentile(sortedAmounts, 0.75)
            
            // Velocity (spending acceleration within period)
            val velocity = calculateVelocity(expenses)
            
            EnhancedCategoryAnalytics(
                category = category,
                period = period,
                totalSpent = total,
                transactionCount = expenses.size,
                averagePerTransaction = if (amounts.isNotEmpty()) amounts.average() else 0.0,
                medianTransaction = getPercentile(sortedAmounts, 0.50),
                previousPeriodTotal = previousTotal,
                changePercent = changePercent,
                trendDirection = determineTrendDirection(changePercent),
                budgetAmount = budget?.amount,
                budgetUtilizationPercent = budgetUtilization,
                budgetRemaining = budget?.let { it.amount - total },
                budgetStatus = budgetStatus,
                minTransaction = sortedAmounts.firstOrNull() ?: 0.0,
                maxTransaction = sortedAmounts.lastOrNull() ?: 0.0,
                percentile25 = p25,
                percentile75 = p75,
                sparklineData = sparklineData[categoryId] ?: emptyList(),
                velocity = velocity
            )
        }.sortedByDescending { it.totalSpent }
    }
}
    
    // ============================================================
    // MERCHANT ANALYTICS
    // ============================================================
    
    /**
     * Generates enhanced analytics for top merchants within the specified period.
     * @param limit Maximum number of merchants to return
     */
    suspend fun getMerchantAnalytics(
        period: PeriodRange,
        limit: Int = 20
    ): List<EnhancedMerchantAnalytics> = withContext(Dispatchers.Default) {
        coroutineScope {
        val currentExpensesDeferred = async { 
            expenseRepository.getExpensesBetween(period.startMs, period.endMs) 
        }
        
        // Get historical data for price trends (6 months back)
        val historicalStart = period.startMs - (180L * MILLIS_PER_DAY)
        val historicalExpensesDeferred = async { 
            expenseRepository.getExpensesSince(historicalStart) 
        }
        
        val currentExpenses = currentExpensesDeferred.await()
        val historicalExpenses = historicalExpensesDeferred.await()
        
        val currentPurchases = currentExpenses.filter { it.transactionType == TransactionType.PURCHASE }
        
        currentPurchases
            .groupBy { it.merchant }
            .map { (merchant, transactions) ->
                val amounts = transactions.map { it.amount }
                val sortedAmounts = amounts.sorted()
                val dates = transactions.map { it.date }.sorted()
                
                // Historical context for price trends
                val historicalForMerchant = historicalExpenses
                    .filter { it.merchant.equals(merchant, ignoreCase = true) }
                    .sortedBy { it.date }
                
                // Visit frequency analysis
                val avgDaysBetween = calculateAverageDaysBetween(dates)
                val visitFrequency = determineVisitFrequency(transactions.size, period, avgDaysBetween)
                
                // Price trend analysis
                val priceTrendData = analyzePriceTrend(historicalForMerchant)
                
                // Loyalty score
                val loyaltyScore = calculateLoyaltyScore(amounts, historicalForMerchant.size)
                
                // Consistency rating
                val consistencyRating = determineConsistencyRating(amounts, avgDaysBetween)
                
                // Streak count (consecutive months visited)
                val streakCount = calculateStreakCount(historicalForMerchant)
                
                // Spending by day of week
                val spendingByDay = calculateSpendingByDayOfWeek(transactions)
                
                // Predicted next visit
                val predictedNext = predictNextVisit(dates, avgDaysBetween)
                
                EnhancedMerchantAnalytics(
                    merchant = merchant,
                    period = period,
                    totalSpent = amounts.sum(),
                    transactionCount = transactions.size,
                    averagePerVisit = if (amounts.isNotEmpty()) amounts.average() else 0.0,
                    medianPerVisit = getPercentile(sortedAmounts, 0.50),
                    visitFrequency = visitFrequency,
                    averageDaysBetweenVisits = avgDaysBetween,
                    predictedNextVisitDate = predictedNext,
                    priceTrend = priceTrendData.trend,
                    firstPurchaseAmount = priceTrendData.first,
                    latestPurchaseAmount = priceTrendData.last,
                    priceChangePercent = priceTrendData.change,
                    loyaltyScore = loyaltyScore,
                    consistencyRating = consistencyRating,
                    consecutiveMonthsVisited = streakCount,
                    spendingByDayOfWeek = spendingByDay,
                    recentTransactions = transactions.take(5)
                )
            }
            .sortedByDescending { it.totalSpent }
            .take(limit)
    }
}
    
    // ============================================================
    // SPENDING PATTERNS
    // ============================================================
    
    /**
     * Analyzes spending patterns including day-of-week distribution and detected behaviors.
     */
    suspend fun getSpendingPatterns(period: PeriodRange): SpendingPatternAnalysis = withContext(Dispatchers.Default) {
        coroutineScope {
            val expenses = expenseRepository.getExpensesBetween(period.startMs, period.endMs)
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        
        if (purchases.isEmpty()) {
            return@coroutineScope createEmptyPatternAnalysis(period)
        }
        
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val totalSpent = purchases.sumOf { it.amount }
        
        // Use arrays for better performance
        val dayTotals = DoubleArray(7)
        val dayCounts = IntArray(7)
        val timeSlotStats = mutableMapOf<TimeSlot, Double>()
        
        for (purchase in purchases) {
            val dayIndex = calendarDayToIndex(purchase.date, cal)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            
            dayTotals[dayIndex] += purchase.amount
            dayCounts[dayIndex]++
            
            val slot = hourToTimeSlot(hour)
            timeSlotStats[slot] = (timeSlotStats[slot] ?: 0.0) + purchase.amount
        }
        
        // Build day of week stats map
        val dayOfWeekStats = (0..6).associateWith { index ->
            DayOfWeekStats(
                dayName = DAY_NAMES[index],
                dayIndex = index,
                totalSpent = dayTotals[index],
                transactionCount = dayCounts[index],
                averagePerDay = if (dayCounts[index] > 0) dayTotals[index] / dayCounts[index] else 0.0,
                percentageOfWeek = if (totalSpent > 0) (dayTotals[index] / totalSpent * 100).toFloat() else 0f
            )
        }
        
        // Weekend vs Weekday
        val weekdayTotal = (0..4).sumOf { dayTotals[it] }
        val weekendTotal = (5..6).sumOf { dayTotals[it] }
        val weekdayCount = (0..4).sumOf { dayCounts[it] }
        val weekendCount = (5..6).sumOf { dayCounts[it] }
        
        val weekendWeekdayComparison = WeekendWeekdayComparison(
            weekdayTotal = weekdayTotal,
            weekdayCount = weekdayCount,
            weekendTotal = weekendTotal,
            weekendCount = weekendCount,
            weekdayAveragePerTransaction = if (weekdayCount > 0) weekdayTotal / weekdayCount else 0.0,
            weekendAveragePerTransaction = if (weekendCount > 0) weekendTotal / weekendCount else 0.0,
            weekendToWeekdayRatio = if (weekdayTotal > 0) (weekendTotal / weekdayTotal).toFloat() else 0f
        )
        
        // Detect patterns
        val detectedPatterns = detectSpendingPatterns(
            purchases, dayTotals, timeSlotStats, totalSpent
        )
        
        SpendingPatternAnalysis(
            period = period,
            dayOfWeekStats = dayOfWeekStats,
            mostActiveDayIndex = dayTotals.indices.maxByOrNull { dayTotals[it] } ?: 0,
            leastActiveDayIndex = dayTotals.indices.minByOrNull { dayTotals[it] } ?: 0,
            weekendVsWeekday = weekendWeekdayComparison,
            timeOfDayDistribution = timeSlotStats,
            detectedPatterns = detectedPatterns
        )
    }
}
    
    private fun createEmptyPatternAnalysis(period: PeriodRange): SpendingPatternAnalysis {
        return SpendingPatternAnalysis(
            period = period,
            dayOfWeekStats = emptyMap(),
            mostActiveDayIndex = 0,
            leastActiveDayIndex = 0,
            weekendVsWeekday = WeekendWeekdayComparison(
                weekdayTotal = 0.0, weekdayCount = 0,
                weekendTotal = 0.0, weekendCount = 0,
                weekdayAveragePerTransaction = 0.0,
                weekendAveragePerTransaction = 0.0,
                weekendToWeekdayRatio = 0f
            ),
            timeOfDayDistribution = emptyMap(),
            detectedPatterns = emptyList()
        )
    }
    
    // ============================================================
    // STATISTICAL INSIGHTS
    // ============================================================
    
    /**
     * Calculates statistical insights for the specified period.
     */
    suspend fun getStatisticalInsights(period: PeriodRange): StatisticalInsights = withContext(Dispatchers.Default) {
        coroutineScope {
        val expenses = expenseRepository.getExpensesBetween(period.startMs, period.endMs)
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        
        if (purchases.isEmpty()) {
            return@coroutineScope createEmptyStatisticalInsights(period)
        }
        
        val amounts = purchases.map { it.amount }
        val sortedAmounts = amounts.sorted()
        
        val mean = amounts.average()
        val variance = if (amounts.size > 1) {
            amounts.sumOf { (it - mean) * (it - mean) } / amounts.size
        } else 0.0
        val stdDev = sqrt(variance)
        val cv = if (mean > 0) (stdDev / mean).toFloat() else 0f
        
        // Build histogram
        // Build histogram (O(n) single pass)
        // Build histogram (O(n) single pass)
        val histogram = buildHistogram(amounts, 10)
        
        // Calculate percentiles
        val percentiles = TransactionPercentiles(
            p10 = getPercentile(sortedAmounts, 0.10),
            p25 = getPercentile(sortedAmounts, 0.25),
            p50 = getPercentile(sortedAmounts, 0.50),
            p75 = getPercentile(sortedAmounts, 0.75),
            p90 = getPercentile(sortedAmounts, 0.90),
            p95 = getPercentile(sortedAmounts, 0.95),
            p99 = getPercentile(sortedAmounts, 0.99)
        )
        
        // Daily spending analysis
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val dailyTotals = purchases.groupBy { expense ->
            cal.timeInMillis = expense.date
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        }.mapValues { it.value.sumOf { e -> e.amount } }
        
        val periodDays = ((period.endMs - period.startMs) / MILLIS_PER_DAY).toInt().coerceAtLeast(1)
        
        StatisticalInsights(
            period = period,
            histogramBins = histogram,
            percentiles = percentiles,
            volatilityIndex = (cv * 100).coerceIn(0f, 100f),
            coefficientOfVariation = cv,
            standardDeviation = stdDev,
            meanTransaction = mean,
            medianTransaction = percentiles.p50,
            modeTransaction = findMode(amounts),
            largestTransaction = purchases.maxByOrNull { it.amount },
            smallestTransaction = purchases.minByOrNull { it.amount },
            averageDailySpend = if (dailyTotals.isNotEmpty()) dailyTotals.values.average() else 0.0,
            maxDailySpend = dailyTotals.values.maxOrNull() ?: 0.0,
            daysWithSpending = dailyTotals.size,
            daysWithoutSpending = (periodDays - dailyTotals.size).coerceAtLeast(0)
        )
    }
}
    
    private fun createEmptyStatisticalInsights(period: PeriodRange): StatisticalInsights {
        return StatisticalInsights(
            period = period,
            histogramBins = emptyList(),
            percentiles = TransactionPercentiles(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            volatilityIndex = 0f,
            coefficientOfVariation = 0f,
            standardDeviation = 0.0,
            meanTransaction = 0.0,
            medianTransaction = 0.0,
            modeTransaction = null,
            largestTransaction = null,
            smallestTransaction = null,
            averageDailySpend = 0.0,
            maxDailySpend = 0.0,
            daysWithSpending = 0,
            daysWithoutSpending = 0
        )
    }
    
    // ============================================================
    // PRIVATE HELPER METHODS
    // ============================================================
    
    private fun calendarDayToIndex(timestamp: Long, cal: Calendar): Int {
        cal.timeInMillis = timestamp
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
    }
    
    private fun hourToTimeSlot(hour: Int): TimeSlot {
        return when (hour) {
            in 6 until 9 -> TimeSlot.EARLY_MORNING
            in 9 until 12 -> TimeSlot.MORNING
            in 12 until 17 -> TimeSlot.AFTERNOON
            in 17 until 21 -> TimeSlot.EVENING
            in 21..23 -> TimeSlot.NIGHT
            else -> TimeSlot.LATE_NIGHT
        }
    }
    
    private fun calculateChangePercent(current: Double, previous: Double?): Float? {
        if (previous == null || previous == 0.0) return null
        val percent = ((current - previous) / previous * 100)
        return percent.coerceIn(-1000.0, 1000.0).toFloat() // Clamp to avoid infinite/huge values
    }
    
    private fun determineTrendDirection(changePercent: Float?): CategoryTrendDirection {
        if (changePercent == null) return CategoryTrendDirection.STABLE
        return when {
            changePercent > 20 -> CategoryTrendDirection.UP_FAST
            changePercent > 5 -> CategoryTrendDirection.UP
            changePercent < -20 -> CategoryTrendDirection.DOWN_FAST
            changePercent < -5 -> CategoryTrendDirection.DOWN
            else -> CategoryTrendDirection.STABLE
        }
    }
    
    private fun determineBudgetStatus(spent: Double, budget: Double): BudgetHealthStatus {
        if (budget <= 0) return BudgetHealthStatus.ON_TRACK
        val ratio = spent / budget
        return when {
            ratio >= 1.0 -> BudgetHealthStatus.EXCEEDED
            ratio >= 0.9 -> BudgetHealthStatus.CRITICAL
            ratio >= 0.75 -> BudgetHealthStatus.WARNING
            else -> BudgetHealthStatus.ON_TRACK
        }
    }
    
    private fun getPercentile(sorted: List<Double>, percentile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted.first()
        
        val index = (sorted.size - 1) * percentile
        val lowerIndex = index.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(sorted.size - 1)
        
        if (lowerIndex == upperIndex) return sorted[lowerIndex]
        
        val fraction = index - lowerIndex
        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fraction
    }
    
    private fun buildSparklineDataByCategory(
        purchases: List<Expense>,
        period: PeriodRange
    ): Map<Long?, List<Double>> {
        val periodDuration = period.endMs - period.startMs
        val periodDays = (periodDuration / MILLIS_PER_DAY).toInt()
        if (periodDays <= 0) return emptyMap()
        
        // Determine how many days to actually show
        // If the period is current, we only show up to today to avoid a long "future" flat line
        val now = timeProvider.now()
        val daysPassed = if (now in period.startMs until period.endMs) {
            ((now - period.startMs) / MILLIS_PER_DAY).toInt()
        } else {
            periodDays
        }
        
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val dailyByCategory = mutableMapOf<Long?, DoubleArray>()
        
        for (purchase in purchases) {
            val dayIndex = ((purchase.date - period.startMs) / MILLIS_PER_DAY).toInt()
            
            if (dayIndex in 0 until periodDays) {
                val catArray = dailyByCategory.getOrPut(purchase.categoryId) { 
                    DoubleArray(periodDays) 
                }
                catArray[dayIndex] += purchase.amount
            }
        }
        
        // Build cumulative data for sparkline
        // Build cumulative data for sparkline with safe running total
        return dailyByCategory.mapValues { (_, daily) ->
            var running = 0.0
            val cumulative = ArrayList<Double>(daysPassed)
            for (i in 0 until daysPassed) {
                running += daily[i]
                cumulative.add(running)
            }
            cumulative
        }
    }
    
    private fun calculateVelocity(expenses: List<Expense>): Double {
        if (expenses.size < 2) return 0.0
        
        val sorted = expenses.sortedBy { it.date }
        val midPoint = sorted.size / 2
        
        val firstHalfTotal = sorted.take(midPoint).sumOf { it.amount }
        val secondHalfTotal = sorted.takeLast(midPoint).sumOf { it.amount }
        
        return secondHalfTotal - firstHalfTotal
    }
    
    private fun calculateAverageDaysBetween(dates: List<Long>): Double? {
        if (dates.size < 2) return null
        
        val sorted = dates.sorted()
        var totalDays = 0L
        
        for (i in 1 until sorted.size) {
            val diff = (sorted[i] - sorted[i-1]) / MILLIS_PER_DAY
            totalDays += diff.coerceAtLeast(0)
        }
        
        return totalDays.toDouble() / (sorted.size - 1)
    }
    
    private fun determineVisitFrequency(
        count: Int,
        period: PeriodRange,
        avgDaysBetween: Double?
    ): MerchantVisitFrequency {
        val periodDays = ((period.endMs - period.startMs) / MILLIS_PER_DAY).toInt()
        
        return when {
            periodDays <= 0 -> MerchantVisitFrequency.RARE
            count >= periodDays * 0.7 -> MerchantVisitFrequency.DAILY
            avgDaysBetween == null -> MerchantVisitFrequency.RARE
            avgDaysBetween <= 7 -> MerchantVisitFrequency.WEEKLY
            avgDaysBetween <= 14 -> MerchantVisitFrequency.BIWEEKLY
            avgDaysBetween <= 35 -> MerchantVisitFrequency.MONTHLY
            avgDaysBetween <= 100 -> MerchantVisitFrequency.QUARTERLY
            else -> MerchantVisitFrequency.RARE
        }
    }
    
    private fun analyzePriceTrend(
        historicalExpenses: List<Expense>
    ): PriceTrendResult {
        if (historicalExpenses.size < 2) {
            return PriceTrendResult(MerchantPriceTrend.INSUFFICIENT_DATA, null, null, null)
        }
        
        val sorted = historicalExpenses.sortedBy { it.date }
        val first = sorted.first().amount
        val last = sorted.last().amount
        val change = if (first > 0) ((last - first) / first * 100).toFloat() else null
        
        val trend = when {
            change == null -> MerchantPriceTrend.INSUFFICIENT_DATA
            change > 10 -> MerchantPriceTrend.INCREASING_FAST
            change > 3 -> MerchantPriceTrend.INCREASING
            change < -10 -> MerchantPriceTrend.DECREASING_FAST
            change < -3 -> MerchantPriceTrend.DECREASING
            else -> MerchantPriceTrend.STABLE
        }
        
        return PriceTrendResult(trend, first, last, change)
    }
    
    private data class PriceTrendResult(
        val trend: MerchantPriceTrend,
        val first: Double?,
        val last: Double?,
        val change: Float?
    )
    
    private fun calculateLoyaltyScore(amounts: List<Double>, historicalCount: Int): Float {
        if (amounts.isEmpty()) return 0f
        
        // Amount consistency (lower variance = higher score)
        val avg = amounts.average()
        val stdDev = if (amounts.size > 1) {
            sqrt(amounts.sumOf { (it - avg) * (it - avg) } / amounts.size)
        } else avg
        
        val cv = if (avg > 0) stdDev / avg else 1.0
        val safeCv = if (cv.isNaN() || cv.isInfinite()) 1.0 else cv
        val consistencyScore = (1.0 - safeCv.coerceIn(0.0, 1.0)) * 0.4
        
        // Longevity (more historical visits = higher score)
        val longevityScore = (historicalCount / 24.0).coerceIn(0.0, 1.0) * 0.3
        
        // Frequency (more recent visits = higher score)
        // Cap at 12 visits (e.g. monthly for a year)
        val frequencyScore = (amounts.size / 12.0).coerceIn(0.0, 1.0) * 0.3
        
        return ((consistencyScore + longevityScore + frequencyScore) * 100).toFloat().coerceIn(0f, 100f)
    }
    
    private fun determineConsistencyRating(
        amounts: List<Double>,
        avgDaysBetween: Double?
    ): MerchantConsistencyRating {
        return when {
            amounts.size < 3 -> MerchantConsistencyRating.NEW_MERCHANT
            avgDaysBetween == null -> MerchantConsistencyRating.IRREGULAR
            else -> {
                val avg = amounts.average()
                val stdDev = if (amounts.size > 1) {
                    sqrt(amounts.sumOf { (it - avg) * (it - avg) } / amounts.size)
                } else 0.0
                val cv = if (avg > 0) stdDev / avg else 1.0
                
                when {
                    cv < 0.1 && avgDaysBetween in 25.0..35.0 -> MerchantConsistencyRating.HIGHLY_CONSISTENT
                    cv < 0.25 -> MerchantConsistencyRating.CONSISTENT
                    cv < 0.5 -> MerchantConsistencyRating.VARIABLE
                    else -> MerchantConsistencyRating.IRREGULAR
                }
            }
        }
    }
    
    private fun calculateStreakCount(historicalExpenses: List<Expense>): Int {
        if (historicalExpenses.isEmpty()) return 0
        
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val months = historicalExpenses.map { expense ->
            cal.timeInMillis = expense.date
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        }.distinct().sorted()
        
        if (months.size < 2) return 1
        
        var maxStreak = 1
        var currentStreak = 1
        
        for (i in 1 until months.size) {
            val prevParts = months[i-1].split("-").map { it.toInt() }
            val currParts = months[i].split("-").map { it.toInt() }
            
            val isConsecutive = (currParts[0] == prevParts[0] && currParts[1] == prevParts[1] + 1) ||
                               (currParts[0] == prevParts[0] + 1 && currParts[1] == 0 && prevParts[1] == 11)
            
            if (isConsecutive) {
                currentStreak++
                maxStreak = maxOf(maxStreak, currentStreak)
            } else {
                currentStreak = 1
            }
        }
        
        return maxStreak
    }
    
    private fun calculateSpendingByDayOfWeek(transactions: List<Expense>): Map<Int, Double> {
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        val result = mutableMapOf<Int, Double>()
        
        for (tx in transactions) {
            result[calendarDayToIndex(tx.date, cal)] = (result[calendarDayToIndex(tx.date, cal)] ?: 0.0) + tx.amount
        }
        
        return result
    }
    
    private fun predictNextVisit(dates: List<Long>, avgDaysBetween: Double?): Long? {
        if (dates.isEmpty() || avgDaysBetween == null || avgDaysBetween <= 0) return null
        
        val lastVisit = dates.max()
        return lastVisit + (avgDaysBetween * MILLIS_PER_DAY).toLong()
    }
    
    private fun detectSpendingPatterns(
        purchases: List<Expense>,
        dayTotals: DoubleArray,
        timeSlotStats: Map<TimeSlot, Double>,
        totalSpent: Double
    ): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()
        
        if (totalSpent <= 0 || purchases.isEmpty()) return patterns
        
        val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
        
        // Weekend Warrior pattern
        val weekendTotal = dayTotals[5] + dayTotals[6]
        if (weekendTotal / totalSpent > 0.5) {
            val cal = Calendar.getInstance().apply { timeInMillis = timeProvider.now() }
            val weekendMerchants = purchases.filter { tx ->
                cal.timeInMillis = tx.date
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            }.map { it.merchant }.distinct()
            
            patterns.add(DetectedPattern(
                type = SpendingPatternType.WEEKEND_WARRIOR,
                description = "Most spending happens on weekends",
                confidence = (weekendTotal / totalSpent * 100).toFloat(),
                affectedMerchants = weekendMerchants.take(5)
            ))
        }
        
        // Lunch Browser pattern
        val lunchSpending = (timeSlotStats[TimeSlot.MORNING] ?: 0.0) + 
                           (timeSlotStats[TimeSlot.AFTERNOON] ?: 0.0)
        if (lunchSpending / totalSpent > 0.4) {
            patterns.add(DetectedPattern(
                type = SpendingPatternType.LUNCH_BROWSER,
                description = "Regular daytime spending suggests frequent lunch outings",
                confidence = (lunchSpending / totalSpent * 100).toFloat(),
                affectedMerchants = emptyList() // Could filter for restaurant categories if available
            ))
        }
        
        // Impulse Buyer pattern (high transaction variance)
        val amounts = purchases.map { it.amount }
        val avg = amounts.average()
        val stdDev = if (amounts.size > 1) {
            sqrt(amounts.sumOf { (it - avg) * (it - avg) } / amounts.size)
        } else 0.0
        val cv = if (avg > 0) stdDev / avg else 0.0
        
        if (cv > 1.0 && purchases.size > 10) {
            patterns.add(DetectedPattern(
                type = SpendingPatternType.IMPULSE_BUYER,
                description = "High spending variability detected",
                confidence = (cv * 50).toFloat().coerceAtMost(100f),
                affectedMerchants = emptyList()
            ))
        }
        
        return patterns
    }

    /**
     * Builds a histogram from a list of values.
     * O(n) complexity.
     */
    private fun buildHistogram(values: List<Double>, binCount: Int = 10): List<HistogramBin> {
        if (values.isEmpty()) return emptyList()
        
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 0.0
        val sum = values.sum()
        
        if (min == max) {
            return listOf(HistogramBin(
                rangeStart = min, 
                rangeEnd = max, 
                count = values.size, 
                total = sum,
                percentage = 100f
            ))
        }
        
        val range = max - min
        val binWidth = range / binCount
        
        // Count frequencies and sums
        val counts = IntArray(binCount)
        val totals = DoubleArray(binCount)
        
        for (value in values) {
            val binIndex = ((value - min) / binWidth).toInt()
            // Handle edge case where value == max (goes into last bin)
            val index = binIndex.coerceIn(0, binCount - 1)
            counts[index]++
            totals[index] += value
        }
        
        // Create bin objects
        val totalCount = values.size.toFloat()
        return counts.mapIndexed { index, count ->
            val binStart = min + (index * binWidth)
            val binEnd = min + ((index + 1) * binWidth)
            HistogramBin(
                rangeStart = binStart,
                rangeEnd = binEnd,
                count = count,
                total = totals[index],
                percentage = if (totalCount > 0) (count / totalCount) * 100f else 0f
            )
        }
    }
    
    private fun findMode(amounts: List<Double>): Double? {
        if (amounts.isEmpty()) return null
        
        // Round to nearest 0.50 for grouping
        val rounded = amounts.map { round(it * 2) / 2.0 }
        return rounded.groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key
    }
}