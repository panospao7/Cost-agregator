package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.MerchantStats
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.dashboard.DashboardExpense
import com.yourname.expensetracker.domain.model.dashboard.toEntityExpense
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmName
import timber.log.Timber

// === Engine ===

@Singleton
class InsightsEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringExpenseEngine: com.yourname.expensetracker.domain.logic.RecurringExpenseEngine,
    private val timeProvider: TimeProvider,
    // Extracted focused engines
    private val spendingPaceCalculator: SpendingPaceCalculator,
    private val anomalyDetector: AnomalyDetector,
    private val monthlyComparisonCalculator: MonthlyComparisonCalculator,
    private val categoryInsightEngine: CategoryInsightEngine,
    private val merchantInsightEngine: MerchantInsightEngine,
    private val dayOfWeekAnalyzer: DayOfWeekAnalyzer
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

        // Start all independent queries in parallel with error handling
        val monthlyComparisonDeferred = async { 
            try { buildMonthlyComparison(currentMonth, previousMonth) } catch (e: Exception) { null }
        }
        val categoryInsightsDeferred = async { 
            try { buildCategoryInsights(currentMonth, previousMonth, categoryMap, allExpenses) } catch (e: Exception) { null }
        }
        val topMerchantsDeferred = async { 
            try { buildMerchantInsights(allExpenses) } catch (e: Exception) { null }
        }
        val spendingPaceDeferred = async { 
            try { buildSpendingPace(currentMonth, previousMonth, allExpenses) } catch (e: Exception) { null }
        }
        val anomaliesDeferred = async { 
            try { findAnomalies(currentMonth, categoryMap, allExpenses) } catch (e: Exception) { null }
        }
        val recurringExpensesDeferred = async { 
            try { findRecurringExpenses(allExpenses) } catch (e: Exception) { emptyList() }
        }
        
        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPatternDeferred = async {
            try { buildDayOfWeekPattern(threeMonthsAgo.startMs, currentMonth.endMs, allExpenses) } catch (e: Exception) { null }
        }
        val largestTransactionDeferred = async { 
            try { expenseRepository.getLargestExpenseForPeriod(currentMonth.startMs, currentMonth.endMs) } catch (e: Exception) { null }
        }

        // Await all results with error resilience
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
                    && !it.isNotMine
        }
        val avgTxSize = if (currentMonthPurchases.isNotEmpty())
            currentMonthPurchases.map { it.effectiveAmount }.average() else 0.0
        val medianTxSize = calculateMedian(currentMonthPurchases.map { it.effectiveAmount })

        // How many months of data we have
        val totalMonthsOfData = countDistinctMonths(allExpenses)

        InsightsSnapshot(
            currentMonth = currentMonth,
            monthlyComparison = monthlyComparison ?: MonthlyComparison(
                currentMonth = currentMonth,
                previousMonth = null,
                currentTotal = 0.0,
                previousTotal = null,
                changeAmount = null,
                changePercentage = null,
                currentCount = 0,
                previousCount = null
            ),
            categoryInsights = categoryInsights ?: emptyList(),
            topMerchants = topMerchants ?: emptyList(),
            spendingPace = spendingPace ?: SpendingPace(
                currentMonthSpent = 0.0,
                daysElapsed = 0,
                daysInMonth = 30,
                projectedTotal = 0.0,
                previousMonthTotal = null,
                averageMonthlyTotal = null,
                pacePercentage = 0f,
                paceStatus = PaceStatus.NO_BASELINE
            ),
            anomalies = anomalies ?: emptyList(),
            recurringExpenses = recurringExpenses,
            dayOfWeekPattern = dayOfWeekPattern ?: emptyList(),
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
        if (largest != null && largest.effectiveAmount > 50) {
             insights.add(
                SpendingInsight(
                    InsightType.UNUSUAL_TRANSACTION, "⚡",
                    "Largest: ${largest.merchant}",
                    "€${fmt(largest.effectiveAmount)} on ${formatDate(largest.date)}",
                    0.25f
                )
            )
        }

        return insights.sortedByDescending { it.severity }
    }

    // === Month Period Helpers ===
    
    fun getMonthPeriod(timeMs: Long, monthOffset: Int = 0): MonthPeriod {
        val range = com.yourname.expensetracker.domain.util.TimePeriodUtils.getMonthRange(timeMs, monthOffset)
        val year = TimePeriodUtils.getYear(range.first)
        val month = TimePeriodUtils.getMonth(range.first)
        
        return MonthPeriod(year, month, range.first, range.second)
    }

    private fun getPreviousMonthPeriod(current: MonthPeriod): MonthPeriod {
        return getMonthPeriod(TimePeriodUtils.addMonths(current.startMs, -1))
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

        // Build insights; normalize percentageOfTotal so they sum to 100 (fixes off-by-one rounding)
        val insights = currentTotals.mapNotNull { ct ->
            val category = categoryMap[ct.categoryId]
            if (category == null) {
                Timber.tag("InsightsEngine").w("Category ${ct.categoryId} not found for expense integration")
                return@mapNotNull null
            }
            val prev = previousMap[ct.categoryId]
            val avgData = monthlyAverages[ct.categoryId]

            val changeFromPrev = if (prev != null && prev.total > 0)
                ((ct.total - prev.total) / prev.total * 100).toFloat() else null

            val changeFromAvg = if (avgData != null && avgData.first > 0)
                ((ct.total - avgData.first) / avgData.first * 100).toFloat() else null

            val rawPct = if (currentGrandTotal > 0) (ct.total / currentGrandTotal * 100).toFloat() else 0f

            CategoryInsight(
                category = category,
                currentTotal = ct.total,
                currentCount = ct.txCount,
                previousTotal = prev?.total,
                previousCount = prev?.txCount,
                averageOverMonths = avgData?.first,
                monthsOfData = avgData?.second ?: 0,
                percentageOfTotal = rawPct,
                changeFromPrevious = changeFromPrev,
                changeFromAverage = changeFromAvg
            )
        }.sortedByDescending { it.currentTotal }
        // Normalize last category's percentage so total = 100 (fixes aggregation off-by-one)
        return@coroutineScope if (insights.size > 1 && currentGrandTotal > 0) {
            val sumPct = insights.sumOf { it.percentageOfTotal.toDouble() }.toFloat()
            if (kotlin.math.abs(sumPct - 100f) > 0.01f) {
                val adjusted = insights.dropLast(1) + insights.last().let { last ->
                    last.copy(percentageOfTotal = (100f - insights.dropLast(1).sumOf { it.percentageOfTotal.toDouble() }.toFloat()).coerceIn(0f, 100f))
                }
                adjusted
            } else insights
        } else insights
    }

    private fun calculateCategoryMonthlyAverages(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Map<Long, Pair<Double, Int>> {
        // Group purchases by category, then by month, compute average
        val purchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && !it.isNotMine
                    && it.categoryId != null
                    && it.date < currentMonth.startMs // exclude current month
        }

        // Group by categoryId -> month key -> sum
        val categoryMonthTotals = mutableMapOf<Long, MutableMap<String, Double>>()
        for (expense in purchases) {
            val catId = expense.categoryId ?: continue
            val monthKey = String.format("%d-%02d", TimePeriodUtils.getYear(expense.date), TimePeriodUtils.getMonth(expense.date) + 1)
            categoryMonthTotals
                .getOrPut(catId) { mutableMapOf() }
                .merge(monthKey, expense.effectiveAmount) { a, b -> a + b }
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

        // For std deviation, compute from raw data grouped by canonical merchant key.
        // DAO merchant stats alias merchantKey -> merchantName, so lookup must use the
        // same canonical key to avoid key mismatches across raw merchant labels.
        val purchasesByMerchantKey = allExpenses
            .filter { it.transactionType == TransactionType.PURCHASE && !it.isNotMine }
            .groupBy { it.merchantKey }

        return stats.map { ms ->
            val amounts = purchasesByMerchantKey[ms.merchantName]?.map { it.effectiveAmount } ?: emptyList()
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

    private fun buildSpendingPace(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod,
        allExpenses: List<Expense>
    ): SpendingPace {
        val canonicalPace = spendingPaceCalculator.calculate(
            currentMonthStart = currentMonth.startMs,
            previousMonthStart = previousMonth.startMs,
            previousMonthEnd = previousMonth.endMs,
            allExpenses = allExpenses
        )

        // Preserve Insights-specific enrichment while delegating pace math.
        val avgMonthly = calculateAverageMonthlySpend(allExpenses, currentMonth)

        return SpendingPace(
            currentMonthSpent = canonicalPace.currentMonthSpent,
            daysElapsed = canonicalPace.daysElapsed,
            daysInMonth = canonicalPace.daysInMonth,
            projectedTotal = canonicalPace.projectedTotal,
            previousMonthTotal = canonicalPace.previousMonthTotal,
            averageMonthlyTotal = avgMonthly,
            pacePercentage = canonicalPace.pacePercentage,
            paceStatus = canonicalPace.paceStatus
        )
    }

    private fun calculateAverageMonthlySpend(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Double? {
        val purchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && !it.isNotMine
                    && it.date < currentMonth.startMs
        }
        if (purchases.isEmpty()) return null

        val monthTotals = mutableMapOf<String, Double>()
        for (p in purchases) {
            val key = String.format("%d-%02d", TimePeriodUtils.getYear(p.date), TimePeriodUtils.getMonth(p.date) + 1)
            monthTotals.merge(key, p.effectiveAmount) { a, b -> a + b }
        }

        return if (monthTotals.isNotEmpty()) monthTotals.values.average() else null
    }

    // === Anomaly Detection ===

    /**
     * Merges two complementary anomaly detection paths:
     *
     *  1. Merchant-level (DB-backed): compares each merchant's current-month max
     *     against their all-time historical average with an adaptive multiplier.
     *     Precise but only fires on known merchants with enough history.
     *
     *  2. Statistical (in-memory, [AnomalyDetector]): IQR, MAD, and contextual
     *     methods operating on the current month's expenses grouped by category.
     *     Fires even for new merchants; more sensitive to distributional outliers.
     *
     * Results are deduplicated by expense id and capped at 10, sorted by
     * deviation multiple descending.
     */
    private suspend fun findAnomalies(
        currentMonth: MonthPeriod,
        categoryMap: Map<Long, Category>,
        allExpenses: List<Expense>
    ): List<AnomalyTransaction> = coroutineScope {

        // ── Path 1: merchant-level DB-backed detection (existing logic) ────────
        val merchantStatsDeferred = async { expenseRepository.getMerchantStats() }
        val topMerchantsDeferred = async {
            expenseRepository.getTopMerchantsForPeriod(
                currentMonth.startMs, currentMonth.endMs, 100
            )
        }

        val merchantStats = merchantStatsDeferred.await()
        val topMerchants  = topMerchantsDeferred.await()
        val statsMap: Map<String, MerchantStats> = merchantStats.associateBy { it.merchantName }

        val candidates: List<AnomalyCandidate?> = topMerchants.mapNotNull { merchantStat ->
            val historicalStats = statsMap[merchantStat.merchantName]
            if (historicalStats == null || historicalStats.transactionCount < 3) return@mapNotNull null
            if (!historicalStats.averageAmount.isFinite() || historicalStats.averageAmount <= 0.0) {
                return@mapNotNull null
            }

            val multiplier = when {
                historicalStats.transactionCount < 5  -> 5.0
                historicalStats.transactionCount < 10 -> 4.0
                else                                  -> 3.0
            }

            if (merchantStat.maxAmount > historicalStats.averageAmount * multiplier) {
                AnomalyCandidate(
                    merchantName     = merchantStat.merchantName,
                    maxAmount        = merchantStat.maxAmount,
                    historicalAvg    = historicalStats.averageAmount,
                    deviationMultiple = (merchantStat.maxAmount / historicalStats.averageAmount).toFloat()
                )
            } else null
        }

        val topCandidates = candidates
            .filterNotNull()
            .sortedByDescending { it.deviationMultiple }
            .take(5)

        val merchantAnomalies: List<AnomalyTransaction> = topCandidates
            .map { candidate ->
                async {
                    expenseRepository.getLargestExpenseForMerchant(
                        candidate.merchantName, currentMonth.startMs, currentMonth.endMs
                    )?.let { expense ->
                        AnomalyTransaction(
                            expense           = expense,
                            merchantAvg       = candidate.historicalAvg,
                            deviationMultiple = candidate.deviationMultiple,
                            category          = expense.categoryId?.let { categoryMap[it] },
                            detectionMethod   = AnomalyMethod.MULTIPLIER
                        )
                    }
                }
            }
            .awaitAll()
            .filterNotNull()

        // ── Path 2: statistical in-memory detection (IQR / MAD / contextual) ──
        val statisticalAnomalies: List<AnomalyTransaction> =
            anomalyDetector.detect(currentMonth, categoryMap, allExpenses)

        // ── Merge: deduplicate by expense.id; statistical results fill gaps ────
        val merged = mutableMapOf<Long, AnomalyTransaction>()

        // Merchant-path results get priority (they carry precise historical avg)
        merchantAnomalies.forEach { merged[it.expense.id] = it }

        // Statistical results add new detections; skip if already found by merchant path
        statisticalAnomalies.forEach { anomaly ->
            if (!merged.containsKey(anomaly.expense.id)) {
                merged[anomaly.expense.id] = anomaly
            } else {
                // Enrich existing entry with contextual note if the statistical
                // path picked up extra context information
                val existing = merged[anomaly.expense.id]!!
                if (existing.contextualNote == null && anomaly.contextualNote != null) {
                    merged[anomaly.expense.id] = existing.copy(
                        contextualNote = anomaly.contextualNote
                    )
                }
            }
        }

        merged.values
            .sortedByDescending { it.deviationMultiple }
            .take(10)
    }


    private data class AnomalyCandidate(
        val merchantName: String,
        val maxAmount: Double,
        val historicalAvg: Double,
        val deviationMultiple: Float
    )

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

    private fun buildDayOfWeekPattern(
        startMs: Long,
        endMs: Long,
        allExpenses: List<Expense>
    ): List<DayOfWeekInsight> {
        val totalsByDay = DoubleArray(7)
        val countsByDay = IntArray(7)

        allExpenses.forEach { expense ->
            if (expense.transactionType != TransactionType.PURCHASE || expense.isNotMine) return@forEach
            if (expense.date < startMs || expense.date >= endMs) return@forEach

            val dayOfWeek = TimePeriodUtils.getDayOfWeek(expense.date) // Sun=1..Sat=7
            val dayIndex = (dayOfWeek + 5) % 7 // Mon=0..Sun=6
            totalsByDay[dayIndex] += expense.effectiveAmount
            countsByDay[dayIndex] += 1
        }

        if (countsByDay.sum() == 0) return emptyList()

        return (0..6).map { dayIndex: Int ->
            val total = totalsByDay[dayIndex]
            val txCount = countsByDay[dayIndex]
            DayOfWeekInsight(
                dayName = DAY_NAMES[dayIndex],
                dayIndex = dayIndex,
                totalSpent = total,
                transactionCount = txCount,
                avgPerTransaction = if (txCount > 0) total / txCount else 0.0
            )
        }
    }

    // === Utility Functions ===
    
    fun buildDailyTotals(expenses: List<Expense>, days: Int): Map<String, Double> {
        val now = timeProvider.now()
        val result = LinkedHashMap<String, Double>()

        // Initialize all days with 0
        for (i in days - 1 downTo 0) {
            val dayTs = TimePeriodUtils.addDays(now, -i)
            val key = DateFormatterUtils.dateKey().format(Date(dayTs))
            result[key] = 0.0
        }

        // Fill in actual values - Optimized: reuse Date object
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        val dateObj = java.util.Date()
        for (expense in purchases) {
            dateObj.time = expense.date
            val key = DateFormatterUtils.dateKey().format(dateObj)
            if (result.containsKey(key)) {
                result[key] = (result[key] ?: 0.0) + expense.effectiveAmount
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
        return expenses.map { expense ->
            String.format("%d-%02d", TimePeriodUtils.getYear(expense.date), TimePeriodUtils.getMonth(expense.date) + 1)
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

    @JvmName("getSpendingPaceSuspendDashboard")
    suspend fun getSpendingPaceSuspend(expenses: List<DashboardExpense>): SpendingPace {
        return getSpendingPaceSuspend(expenses.map { it.toEntityExpense() })
    }

    private fun fmt(amount: Double): String = String.format(java.util.Locale.getDefault(), "%.2f", amount)
    
    private fun formatDate(dateMs: Long): String {
         return DateFormatterUtils.monthDay().format(java.util.Date(dateMs))
    }
}
