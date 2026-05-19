package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.BuildConfig
import timber.log.Timber
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.di.DefaultDispatcher
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.BudgetSnapshot
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Advanced analytics engine for detailed spending analysis.
 * Provides temporal category breakdowns, merchant intelligence, and statistical insights.
 *
 * ## CURRENCY NORMALIZATION: SAFE — fully normalized
 * This engine injects [AnalyticsCurrencyNormalizer] and normalizes every data
 * path before performing any arithmetic:
 * - [getCategoryAnalytics] — normalizes current and previous expenses (lines 163-166)
 * - [getMerchantAnalytics] — normalizes current and historical expenses (lines 270-273)
 * - [getSpendingPatterns] — normalizes expenses (lines 364-365)
 * - [getStatisticalInsights] — normalizes expenses (lines 472-473)
 *
 * All `sumOf { it.effectiveAmount }`, `amounts.sum()`, and other raw-Double
 * arithmetic operate on normalized values. No gap exists.
 *
 * TODO (PR-E11): Accept NormalizedAnalyticsInput instead of querying raw expenses.
 * Engine should not call CurrencyConverter itself unless explicitly responsible.
 */
@Singleton
class AdvancedAnalyticsEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val timeProvider: TimeProvider,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "AdvancedAnalytics"
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
    
    // ============================================================
    // PERIOD CALCULATIONS
    // ============================================================
    
    // A09: Accept explicit AnalyticsPeriodRange from ViewModel, don't recalculate internally.
    // This method duplicates period logic that the ViewModel already computes, risking
    // inconsistent boundaries between the engine and the UI.
    /**
     * Calculates the period range for the given analytics period type.
     * @param period The period type to calculate
     * @param referenceDate Reference timestamp (defaults to now)
     * @return AnalyticsPeriodRange with start/end timestamps and comparison period
     */
    fun getPeriodRange(
        period: AnalyticsPeriod,
        referenceDate: Long = timeProvider.now(),
        computeComparison: Boolean = true
    ): AnalyticsPeriodRange {
        val (startMs, endMs, label) = when (period) {
            AnalyticsPeriod.WEEK -> calculateWeekRange(referenceDate)
            AnalyticsPeriod.MONTH -> calculateMonthRange(referenceDate)
            AnalyticsPeriod.QUARTER -> calculateQuarterRange(referenceDate)
            AnalyticsPeriod.YEAR -> calculateYearRange(referenceDate)
            AnalyticsPeriod.CUSTOM -> throw IllegalArgumentException(
                "Custom period requires explicit date range. Use getCustomPeriodRange() instead."
            )
        }
        
        return AnalyticsPeriodRange(
            period = period,
            startMs = startMs,
            endMs = endMs,
            label = label,
            comparisonRange = if (computeComparison) getPreviousPeriodRange(period, startMs) else null
        )
    }
    
    private fun calculateWeekRange(referenceDate: Long): Triple<Long, Long, String> {
        val start = TimePeriodUtils.getStartOfWeek(referenceDate)
        val end = TimePeriodUtils.getEndOfWeek(referenceDate)
        
        return Triple(
            start,
            end,
            "${DateFormatterUtils.formatTimestampJavaTime(start, "MMM d")} - ${DateFormatterUtils.formatTimestampJavaTime(end - 1, "MMM d")}"
        )
    }
    
    private fun calculateMonthRange(referenceDate: Long): Triple<Long, Long, String> {
        val start = TimePeriodUtils.getStartOfMonth(referenceDate)
        val end = TimePeriodUtils.getEndOfMonth(start)
        
        return Triple(start, end, DateFormatterUtils.formatTimestampJavaTime(start, "MMMM yyyy"))
    }
    
    private fun calculateQuarterRange(referenceDate: Long): Triple<Long, Long, String> {
        val start = TimePeriodUtils.getStartOfQuarter(referenceDate)
        val end = TimePeriodUtils.getEndOfQuarter(start)
        
        val quarterNum = (TimePeriodUtils.getMonth(start) / 3) + 1
        val year = TimePeriodUtils.getYear(start)
        
        return Triple(start, end, "Q$quarterNum $year")
    }
    
    private fun calculateYearRange(referenceDate: Long): Triple<Long, Long, String> {
        val start = TimePeriodUtils.getStartOfYear(referenceDate)
        val end = TimePeriodUtils.getEndOfYear(start)
        
        val year = TimePeriodUtils.getYear(start)
        
        return Triple(start, end, year.toString())
    }
    
    private fun getPreviousPeriodRange(period: AnalyticsPeriod, currentStartMs: Long): AnalyticsPeriodRange? {
        return try {
            val previousRef = when (period) {
                AnalyticsPeriod.WEEK -> TimePeriodUtils.addDays(currentStartMs, -7)
                AnalyticsPeriod.MONTH -> TimePeriodUtils.addMonths(currentStartMs, -1)
                AnalyticsPeriod.QUARTER -> TimePeriodUtils.addMonths(currentStartMs, -3)
                AnalyticsPeriod.YEAR -> TimePeriodUtils.addYears(currentStartMs, -1)
                AnalyticsPeriod.CUSTOM -> return null
            }
            
            getPeriodRange(period, previousRef, computeComparison = false)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to calculate previous period")
            null
        }
    }
    
    // ============================================================
    // CATEGORY ANALYTICS
    // ============================================================

    /**
     * New canonical overload: accepts pre-normalized [NormalizedAnalyticsInput].
     * Normalization is done by the caller (ViewModel), ensuring all analytics cards
     * share the same normalized data.
     */
    suspend fun getCategoryAnalytics(
        input: NormalizedAnalyticsInput,
        previousInput: NormalizedAnalyticsInput?,
        categories: List<Category>,
        budgets: List<BudgetSnapshot>
    ): Pair<List<EnhancedCategoryAnalytics>, List<AnalyticsConversionWarning>> = withContext(defaultDispatcher) {
        val period = AnalyticsPeriodRange(
            period = AnalyticsPeriod.CUSTOM,
            startMs = input.period?.startInclusiveMillis ?: 0L,
            endMs = input.period?.endExclusiveMillis ?: 0L,
            label = input.period?.label ?: "",
            comparisonRange = previousInput?.period?.let {
                AnalyticsPeriodRange(
                    period = AnalyticsPeriod.CUSTOM,
                    startMs = it.startInclusiveMillis,
                    endMs = it.endExclusiveMillis,
                    label = it.label,
                    comparisonRange = null
                )
            }
        )
        val currentPurchases = input.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine }
            .map { it.toExpenseSnapshot() }
        val previousPurchases = previousInput?.includedExpenses
            ?.filter { it.transactionType == "PURCHASE" && !it.isNotMine }
            ?.map { it.toExpenseSnapshot() }
            ?: emptyList()
        val allWarnings = (input.dataQuality.warnings +
            (previousInput?.dataQuality?.warnings ?: emptyList())).distinct()
        computeCategoryAnalyticsCore(
            currentPurchases, previousPurchases, categories, budgets, period, input.homeCurrency, allWarnings
        )
    }

    /**
     * Generates enhanced analytics for all categories within the specified period.
     */
    @Deprecated(
        "Use getCategoryAnalytics(input, previousInput, categories, budgets)",
        level = DeprecationLevel.WARNING
    )
    suspend fun getCategoryAnalytics(period: AnalyticsPeriodRange, displayCurrency: String): Pair<List<EnhancedCategoryAnalytics>, List<AnalyticsConversionWarning>> = withContext(defaultDispatcher) {
        coroutineScope {
        // Fetch all required data in parallel
        val currentExpensesDeferred = async(ioDispatcher) { 
            expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) 
        }
        val previousExpensesDeferred = async(ioDispatcher) { 
            period.comparisonRange?.let { 
                expenseRepository.getExpenseSnapshotsBetween(it.startMs, it.endMs) 
            } ?: emptyList()
        }
        val categoriesDeferred = async(ioDispatcher) { categoryRepository.getAll() }
        val budgetsDeferred = async(ioDispatcher) { getBudgetSnapshots() }
        
        val currentExpenses = currentExpensesDeferred.await()
        val previousExpenses = previousExpensesDeferred.await()
        val categories = categoriesDeferred.await()
        val budgets = budgetsDeferred.await()
        val currentNorm = analyticsCurrencyNormalizer
            .normalizeSnapshots(currentExpenses, displayCurrency)
        val previousNorm = analyticsCurrencyNormalizer
            .normalizeSnapshots(previousExpenses, displayCurrency)
        val allWarnings = (currentNorm.warnings + previousNorm.warnings).distinct()
        val currentPurchases = currentNorm.includedExpenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }
        val previousPurchases = previousNorm.includedExpenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }

        computeCategoryAnalyticsCore(
            currentPurchases, previousPurchases, categories, budgets, period, displayCurrency, allWarnings
        )
    }
}

    private fun computeCategoryAnalyticsCore(
        currentPurchases: List<ExpenseSnapshot>,
        previousPurchases: List<ExpenseSnapshot>,
        categories: List<Category>,
        budgets: List<BudgetSnapshot>,
        period: AnalyticsPeriodRange,
        displayCurrency: String,
        allWarnings: List<AnalyticsConversionWarning>
    ): Pair<List<EnhancedCategoryAnalytics>, List<AnalyticsConversionWarning>> {
        // Build lookup maps
        val categoryMap = categories.associateBy { it.id }
        val budgetMap = budgets.associateBy { it.categoryId }
        
        // Group expenses by category
        val currentByCategory = currentPurchases.groupBy { it.categoryId }
        val previousByCategory = previousPurchases.groupBy { it.categoryId }
        
        // Build sparkline data (daily cumulative by category)
        val sparklineData = buildSparklineDataByCategory(currentPurchases, period)
        
        // Build analytics for each category with spending
        return currentByCategory.mapNotNull { (categoryId, expenses) ->
            val category = categoryMap[categoryId] ?: return@mapNotNull null
            
            val amounts = expenses.map { it.effectiveAmount }
            val sortedAmounts = amounts.sorted()
            val total = amounts.sum()
            
            // Previous period comparison
            val previousTotal = previousByCategory[categoryId]?.sumOf { it.effectiveAmount }
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
                category = AnalyticsCategoryRef(
                    id = category.id,
                    name = category.name,
                    icon = category.icon,
                    color = category.color
                ),
                period = period,
                displayCurrency = displayCurrency,
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
            .let { Pair(it, allWarnings) }
    }
    
    // ============================================================
    // MERCHANT ANALYTICS
    // ============================================================

    /**
     * New canonical overload: accepts pre-normalized [NormalizedAnalyticsInput].
     * The [historicalInput] should cover 12 months back for price trend analysis.
     */
    suspend fun getMerchantAnalytics(
        input: NormalizedAnalyticsInput,
        historicalInput: NormalizedAnalyticsInput,
        limit: Int = 20
    ): Pair<List<EnhancedMerchantAnalytics>, List<AnalyticsConversionWarning>> = withContext(defaultDispatcher) {
        val period = AnalyticsPeriodRange(
            period = AnalyticsPeriod.CUSTOM,
            startMs = input.period?.startInclusiveMillis ?: 0L,
            endMs = input.period?.endExclusiveMillis ?: 0L,
            label = input.period?.label ?: "",
            comparisonRange = null
        )
        val currentPurchases = input.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine }
            .map { it.toExpenseSnapshot() }
        val historicalPurchases = historicalInput.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine }
            .map { it.toExpenseSnapshot() }
        val allWarnings = (input.dataQuality.warnings + historicalInput.dataQuality.warnings).distinct()
        computeMerchantAnalyticsCore(
            currentPurchases, historicalPurchases, period, input.homeCurrency, limit, allWarnings
        )
    }

    /**
     * Generates enhanced analytics for top merchants within the specified period.
     * @param limit Maximum number of merchants to return
     */
    @Deprecated(
        "Use getMerchantAnalytics(input, historicalInput, limit)",
        level = DeprecationLevel.WARNING
    )
    suspend fun getMerchantAnalytics(
        period: AnalyticsPeriodRange,
        displayCurrency: String,
        limit: Int = 20
    ): Pair<List<EnhancedMerchantAnalytics>, List<AnalyticsConversionWarning>> = withContext(defaultDispatcher) {
        coroutineScope {
        val currentExpensesDeferred = async(ioDispatcher) { 
            expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs) 
        }
        
        // A12-FIXED: 12-month merchant lookback for anomaly baseline
        // Get historical data for price trends (12 calendar months back)
        val historicalStart = TimePeriodUtils.addMonths(period.startMs, -12)
        val historicalExpensesDeferred = async(ioDispatcher) { 
            expenseRepository.getExpenseSnapshotsBetween(historicalStart, period.endMs)
        }
        
        val currentExpenses = currentExpensesDeferred.await()
        val historicalExpenses = historicalExpensesDeferred.await()
        
        val currentNorm = analyticsCurrencyNormalizer
            .normalizeSnapshots(currentExpenses, displayCurrency)
        val historicalNorm = analyticsCurrencyNormalizer
            .normalizeSnapshots(historicalExpenses, displayCurrency)
        val allWarnings = (currentNorm.warnings + historicalNorm.warnings).distinct()
        val currentPurchases = currentNorm.includedExpenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }
        val historicalPurchases = historicalNorm.includedExpenses
            .filter {
            it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine
        }
        val historicalByMerchantKey = historicalPurchases.groupBy { it.canonicalMerchantKey() }
        
        computeMerchantAnalyticsCore(
            currentPurchases, historicalPurchases, period, displayCurrency, limit, allWarnings
        )
    }
}

    private fun computeMerchantAnalyticsCore(
        currentPurchases: List<ExpenseSnapshot>,
        historicalPurchases: List<ExpenseSnapshot>,
        period: AnalyticsPeriodRange,
        displayCurrency: String,
        limit: Int,
        allWarnings: List<AnalyticsConversionWarning>
    ): Pair<List<EnhancedMerchantAnalytics>, List<AnalyticsConversionWarning>> {
        val historicalByMerchantKey = historicalPurchases.groupBy { it.canonicalMerchantKey() }

        return currentPurchases
            .groupBy { it.canonicalMerchantKey() }
            .map { (merchantKey, transactions) ->
                val amounts = transactions.map { it.effectiveAmount }
                val sortedAmounts = amounts.sorted()
                val dates = transactions.map { it.date }.sorted()
                val displayName = resolveMerchantDisplayName(transactions)
                
                // Historical context for price trends
                val historicalForMerchant = historicalByMerchantKey[merchantKey]
                    .orEmpty()
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
                    merchant = displayName,
                    period = period,
                    displayCurrency = displayCurrency,
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
                    recentTransactions = transactions
                        .take(5)
                        .map { it.toAnalyticsTransactionSummary() }
                )
            }
            .sortedWith(compareByDescending<EnhancedMerchantAnalytics> { it.transactionCount }
                .thenByDescending { it.totalSpent })
            .take(limit)
            .let { Pair(it, allWarnings) }
    }
    
    // ============================================================
    // SPENDING PATTERNS
    // ============================================================
    
    /**
     * Analyzes spending patterns including day-of-week distribution and detected behaviors.
     *
     * TODO (E2-008): Migrate to accept NormalizedAnalyticsInput instead of fetching
     * expenses internally. This method still queries ExpenseRepository directly,
     * creating a second data source that may diverge from the ViewModel's normalized input.
     */
    suspend fun getSpendingPatterns(period: AnalyticsPeriodRange, displayCurrency: String): Pair<SpendingPatternAnalysis, List<AnalyticsConversionWarning>> = withContext(defaultDispatcher) {
        coroutineScope {
            val expenses = withContext(ioDispatcher) {
                expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs)
            }
            val normResult = analyticsCurrencyNormalizer
                .normalizeSnapshots(expenses, displayCurrency)
            val allWarnings = normResult.warnings
            val purchases = normResult.includedExpenses
                .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }
        
        if (purchases.isEmpty()) {
            return@coroutineScope Pair(createEmptyPatternAnalysis(period), allWarnings)
        }
        
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val totalSpent = purchases.sumOf { it.effectiveAmount }
        
        // Use arrays for better performance
        val dayTotals = DoubleArray(7)
        val dayCounts = IntArray(7)
        val timeSlotStats = mutableMapOf<TimeSlot, Double>()
        
        for (purchase in purchases) {
            val dayIndex = calendarDayToIndex(purchase.date)
            val hour = TimePeriodUtils.getHourOfDay(purchase.date)
            
            dayTotals[dayIndex] += purchase.effectiveAmount
            dayCounts[dayIndex]++
            
            val slot = hourToTimeSlot(hour)
            timeSlotStats[slot] = (timeSlotStats[slot] ?: 0.0) + purchase.effectiveAmount
        }
        
        // Build day of week stats map
        val dayOfWeekStats = (0..6).associateWith { index ->
                DayOfWeekStats(
                    dayName = DAY_NAMES[index],
                    dayIndex = index,
                    totalSpent = dayTotals[index],
                    transactionCount = dayCounts[index],
                    averagePerDay = if (dayCounts[index] > 0) dayTotals[index] / dayCounts[index] else 0.0,
                    percentageOfWeek = if (totalSpent > 0) (dayTotals[index] / totalSpent * 100).toFloat() else 0f,
                    displayCurrency = displayCurrency
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
            weekendToWeekdayRatio = if (weekdayTotal > 0) (weekendTotal / weekdayTotal).toFloat() else 0f,
            displayCurrency = displayCurrency
        )
        
        // Detect patterns
        val detectedPatterns = detectSpendingPatterns(
            purchases, dayTotals, timeSlotStats, totalSpent
        )
        
        Pair(SpendingPatternAnalysis(
            period = period,
            dayOfWeekStats = dayOfWeekStats,
            mostActiveDayIndex = dayTotals.indices.maxByOrNull { dayTotals[it] } ?: 0,
            leastActiveDayIndex = dayTotals.indices.minByOrNull { dayTotals[it] } ?: 0,
            weekendVsWeekday = weekendWeekdayComparison,
            timeOfDayDistribution = timeSlotStats,
            detectedPatterns = detectedPatterns
        ), allWarnings)
    }
}
    
    private fun createEmptyPatternAnalysis(period: AnalyticsPeriodRange): SpendingPatternAnalysis {
        val displayCurrency = defaultDisplayCurrency()
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
                weekendToWeekdayRatio = 0f,
                displayCurrency = displayCurrency
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
     *
     * TODO (E2-008): Migrate to accept NormalizedAnalyticsInput instead of fetching
     * expenses internally. This method still queries ExpenseRepository directly,
     * creating a second data source that may diverge from the ViewModel's normalized input.
     */
    suspend fun getStatisticalInsights(period: AnalyticsPeriodRange, displayCurrency: String): Pair<StatisticalInsights, List<AnalyticsConversionWarning>> = withContext(defaultDispatcher) {
        coroutineScope {
            val expenses = withContext(ioDispatcher) {
                expenseRepository.getExpenseSnapshotsBetween(period.startMs, period.endMs)
            }
            val normResult = analyticsCurrencyNormalizer
                .normalizeSnapshots(expenses, displayCurrency)
            val allWarnings = normResult.warnings
            val purchases = normResult.includedExpenses
                .filter { it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine }
        
        if (purchases.isEmpty()) {
            return@coroutineScope Pair(createEmptyStatisticalInsights(period), allWarnings)
        }
        
        val amounts = purchases.map { it.effectiveAmount }
        val sortedAmounts = amounts.sorted()
        
        val mean = amounts.average()
        // Use sample variance (N-1) for stdDev; single value => 0 (LOW bug fix)
        val variance = if (amounts.size > 1) {
            // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
            amounts.sumOf { (it - mean) * (it - mean) } / (amounts.size - 1)
        } else 0.0
        val stdDev = sqrt(variance)
        val cv = if (mean > 0) (stdDev / mean).toFloat() else 0f
        
        // Build histogram (O(n) single pass)
        val histogram = buildHistogram(amounts, 10, displayCurrency)
        
        // Calculate percentiles
        val percentiles = TransactionPercentiles(
            p10 = getPercentile(sortedAmounts, 0.10),
            p25 = getPercentile(sortedAmounts, 0.25),
            p50 = getPercentile(sortedAmounts, 0.50),
            p75 = getPercentile(sortedAmounts, 0.75),
            p90 = getPercentile(sortedAmounts, 0.90),
            p95 = getPercentile(sortedAmounts, 0.95),
            p99 = getPercentile(sortedAmounts, 0.99),
            displayCurrency = displayCurrency
        )
        
        // Daily spending analysis
        val dailyTotals = purchases.groupBy { expense ->
            val dayStart = TimePeriodUtils.getStartOfDay(expense.date)
            "${TimePeriodUtils.getYear(dayStart)}-${TimePeriodUtils.getMonth(dayStart) + 1}-${TimePeriodUtils.getDayOfMonth(dayStart)}"
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        }.mapValues { it.value.sumOf { e -> e.effectiveAmount } }
        
        val periodDays = TimePeriodUtils.daysBetween(period.startMs, period.endMs).coerceAtLeast(1)
        
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val totalAmount = purchases.sumOf { it.effectiveAmount }
        val averageDailySpend = totalAmount / periodDays

        if (Timber.treeCount > 0 && BuildConfig.DEBUG) {
            fun formatTimestamp(ms: Long): String = java.time.Instant.ofEpochMilli(ms)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm"))

            Timber.d("=== STATISTICAL INSIGHTS DEBUG ===")
            Timber.d("Period: ${period.period} (${period.label})")
            Timber.d("Period Range: ${formatTimestamp(period.startMs)} → ${formatTimestamp(period.endMs)}")
            Timber.d("Period Days: $periodDays")
            Timber.d("Transactions: ${purchases.size}")
            Timber.d("Total: €$totalAmount")
            Timber.d("Daily Totals: $dailyTotals")
            Timber.d("Average Daily: €$averageDailySpend (€$totalAmount / $periodDays days)")
            Timber.d("========================")
        }
        
        Pair(StatisticalInsights(
            period = period,
            displayCurrency = displayCurrency,
            histogramBins = histogram,
            percentiles = percentiles,
            volatilityIndex = (cv * 100).coerceIn(0f, 100f),
            coefficientOfVariation = cv,
            standardDeviation = stdDev,
            meanTransaction = mean,
            medianTransaction = percentiles.p50,
            modeTransaction = findMode(amounts),
            largestTransaction = purchases
                .maxByOrNull { it.effectiveAmount }
                ?.toAnalyticsTransactionSummary(),
            smallestTransaction = purchases
                .minByOrNull { it.effectiveAmount }
                ?.toAnalyticsTransactionSummary(),
            averageDailySpend = averageDailySpend,
            maxDailySpend = dailyTotals.values.maxOrNull() ?: 0.0,
            daysWithSpending = dailyTotals.size,
            daysWithoutSpending = (periodDays - dailyTotals.size).coerceAtLeast(0)
        ), allWarnings)
    }
}
    
    private fun createEmptyStatisticalInsights(period: AnalyticsPeriodRange): StatisticalInsights {
        val displayCurrency = defaultDisplayCurrency()
        return StatisticalInsights(
            period = period,
            displayCurrency = displayCurrency,
            histogramBins = emptyList(),
            percentiles = TransactionPercentiles(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, displayCurrency),
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
    
    private fun calendarDayToIndex(timestamp: Long): Int {
        // A18: Replace Calendar constants with java.time.DayOfWeek
        return when (TimePeriodUtils.getDayOfWeek(timestamp)) {
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
        // Edge case: 0th and 100th percentile (LOW bug fix)
        val p = percentile.coerceIn(0.0, 1.0)
        if (p <= 0.0) return sorted.first()
        if (p >= 1.0) return sorted.last()
        
        val index = (sorted.size - 1) * p
        val lowerIndex = index.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(sorted.size - 1)
        
        if (lowerIndex == upperIndex) return sorted[lowerIndex]
        
        val fraction = index - lowerIndex
        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fraction
    }
    
    private fun buildSparklineDataByCategory(
        purchases: List<ExpenseSnapshot>,
        period: AnalyticsPeriodRange
    ): Map<Long?, List<Double>> {
        val periodStartDay = TimePeriodUtils.getStartOfDay(period.startMs)
        val now = timeProvider.now()
        val todayStart = TimePeriodUtils.getStartOfDay(now)
        val todayEnd = TimePeriodUtils.getEndOfDay(now)

        // If the requested range overlaps today's calendar window, include today's
        // day bucket even when period.endMs is earlier than today's end (e.g. custom
        // ranges that end at "now").
        val periodContainsToday = period.startMs < todayEnd && period.endMs > todayStart
        val sparklineEndExclusive = if (periodContainsToday) {
            maxOf(period.endMs, todayEnd)
        } else {
            period.endMs
        }

        val periodDays = TimePeriodUtils.daysBetween(periodStartDay, sparklineEndExclusive)
        if (periodDays <= 0) return emptyMap()

        // Determine how many days to actually show
        // If the period is current, we only show up to today to avoid a long "future" flat line
        val daysPassed = if (periodContainsToday) {
            TimePeriodUtils.daysBetween(periodStartDay, todayEnd)
                .coerceIn(0, periodDays)
        } else {
            periodDays
        }

        val dailyByCategory = mutableMapOf<Long?, DoubleArray>()

        for (purchase in purchases) {
            val purchaseDay = TimePeriodUtils.getStartOfDay(purchase.date)
            val dayIndex = TimePeriodUtils.daysBetween(periodStartDay, purchaseDay)

            if (dayIndex in 0 until periodDays) {
                val catArray = dailyByCategory.getOrPut(purchase.categoryId) { 
                    DoubleArray(periodDays) 
                }
                catArray[dayIndex] += purchase.effectiveAmount
            }
        }
        
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
    
    private fun calculateVelocity(expenses: List<ExpenseSnapshot>): Double {
        if (expenses.size < 2) return 0.0
        
        val sorted = expenses.sortedBy { it.date }
        val midpoint = sorted.size / 2
        
        val firstHalf = sorted.subList(0, midpoint)
        val secondHalf = sorted.subList(midpoint, sorted.size)
        
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val firstHalfTotal = firstHalf.sumOf { it.effectiveAmount }
        // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
        val secondHalfTotal = secondHalf.sumOf { it.effectiveAmount }
        
        return secondHalfTotal - firstHalfTotal
    }
    
    private fun calculateAverageDaysBetween(dates: List<Long>): Double? {
        if (dates.size < 2) return null
        
        val sorted = dates.sorted()
        var totalDays = 0L
        
        for (i in 1 until sorted.size) {
            val diff = TimePeriodUtils.daysBetween(sorted[i-1], sorted[i])
            totalDays += diff.coerceAtLeast(0)
        }
        
        return totalDays.toDouble() / (sorted.size - 1)
    }
    
    private fun determineVisitFrequency(
        count: Int,
        period: AnalyticsPeriodRange,
        avgDaysBetween: Double?
    ): MerchantVisitFrequency {
        val periodDays = TimePeriodUtils.daysBetween(period.startMs, period.endMs)
        
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
        historicalExpenses: List<ExpenseSnapshot>
    ): PriceTrendResult {
        if (historicalExpenses.size < 2) {
            return PriceTrendResult(MerchantPriceTrend.INSUFFICIENT_DATA, null, null, null)
        }
        
        val sorted = historicalExpenses.sortedBy { it.date }
        val first = sorted.first().effectiveAmount
        val last = sorted.last().effectiveAmount
        val change = if (first > 0) ((last - first) / first * 100).toFloat() else null
        
        // Collinear points (all same amount): treat as STABLE (LOW bug fix)
        val allSame = sorted.all { kotlin.math.abs(it.effectiveAmount - first) < 0.001 }
        val trend = when {
            change == null -> MerchantPriceTrend.INSUFFICIENT_DATA
            allSame -> MerchantPriceTrend.STABLE
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

    private suspend fun getBudgetSnapshots(): List<BudgetSnapshot> =
        budgetRepository.getActiveBudgetSnapshots()

    private fun ExpenseSnapshot.toAnalyticsTransactionSummary(): AnalyticsTransactionSummary {
        return AnalyticsTransactionSummary(
            id = id,
            amount = amount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            date = date,
            categoryId = categoryId
        )
    }

    private suspend fun resolveHomeCurrency(): String {
        return runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault(defaultDisplayCurrency())
    }

    private fun defaultDisplayCurrency(): String =
        runCatching { java.util.Currency.getInstance(Locale.getDefault()).currencyCode }
            .getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }
    
    private fun calculateLoyaltyScore(amounts: List<Double>, historicalCount: Int): Float {
        if (amounts.isEmpty()) return 0f
        
        // Amount consistency (lower variance = higher score)
        val avg = amounts.average()
        val stdDev = if (amounts.size > 1) {
            // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
            sqrt(amounts.sumOf { (it - avg) * (it - avg) } / amounts.size)
        } else 0.0  // Single element has zero variance
        
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
    
    /**
     * Calculates the longest streak of consecutive months the merchant was visited.
     *
     * ## Limitation: bounded by oldest available purchase data
     * The streak is computed purely from the [historicalExpenses] list passed in.
     * If the caller only provides expenses from, say, the last 6 months, the
     * streak cannot extend further back even if the merchant was visited for
     * 12 consecutive months prior. The result is therefore relative to the
     * analysis window, not the user's entire history with the merchant.
     *
     * Callers wanting an absolute (unbounded) streak should fetch expense data
     * from the user's earliest recorded purchase.
     */
    private fun calculateStreakCount(historicalExpenses: List<ExpenseSnapshot>): Int {
        if (historicalExpenses.isEmpty()) return 0
        
        // Use zero-padded months for proper string sorting: "2024-01", "2024-02", etc.
        val months = historicalExpenses.map { expense ->
            "%d-%02d".format(TimePeriodUtils.getYear(expense.date), TimePeriodUtils.getMonth(expense.date))
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
    
    private fun calculateSpendingByDayOfWeek(transactions: List<ExpenseSnapshot>): Map<Int, Double> {
        val result = mutableMapOf<Int, Double>()
        
        for (tx in transactions) {
            val dayIndex = calendarDayToIndex(tx.date)
            result[dayIndex] = (result[dayIndex] ?: 0.0) + tx.effectiveAmount
        }
        
        return result
    }
    
    private fun predictNextVisit(dates: List<Long>, avgDaysBetween: Double?): Long? {
        if (dates.isEmpty() || avgDaysBetween == null || avgDaysBetween <= 0) return null
        
        val lastVisit = dates.max()
        return lastVisit + (avgDaysBetween * TimePeriodUtils.DAY_IN_MILLIS).toLong()
    }
    
    private fun detectSpendingPatterns(
        purchases: List<ExpenseSnapshot>,
        dayTotals: DoubleArray,
        timeSlotStats: Map<TimeSlot, Double>,
        totalSpent: Double
    ): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()
        
        if (totalSpent <= 0 || purchases.isEmpty()) return patterns
        
        // Weekend Warrior pattern
        val weekendTotal = dayTotals[5] + dayTotals[6]
        if (weekendTotal / totalSpent > 0.5) {
            val weekendMerchants = purchases.filter { tx ->
                val dow = TimePeriodUtils.getDayOfWeek(tx.date)
                // A18: Replace Calendar constants with java.time.DayOfWeek
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
        val amounts = purchases.map { it.effectiveAmount }
        val avg = amounts.average()
        val stdDev = if (amounts.size > 1) {
            // SAFE: data normalized via AnalyticsCurrencyNormalizer before reaching this engine
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
    private fun buildHistogram(
        values: List<Double>,
        binCount: Int = 10,
        displayCurrency: String = defaultDisplayCurrency()
    ): List<HistogramBin> {
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
                percentage = 100f,
                displayCurrency = displayCurrency
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
                percentage = if (totalCount > 0) (count / totalCount) * 100f else 0f,
                displayCurrency = displayCurrency
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
