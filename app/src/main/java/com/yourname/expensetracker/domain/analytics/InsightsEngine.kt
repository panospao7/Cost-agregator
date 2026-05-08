package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.text.DomainTextKeys
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

// === Engine ===

/**
 * ## CURRENCY NORMALIZATION: Caller responsibility
 * This engine does **not** inject [AnalyticsCurrencyNormalizer]. All amount
 * arithmetic (averages, medians, anomaly detection, daily totals) operates
 * directly on [ExpenseSnapshot.effectiveAmount] — which is only safe if the
 * caller has already normalized the data to a single currency before passing
 * it in. If un-normalized multi-currency data reaches this engine, raw
 * `Double` sums (e.g. `map { it.effectiveAmount }.average()`,
 * `maxByOrNull { it.effectiveAmount }`, `monthTotals.merge(key, p.effectiveAmount)`)
 * will produce incorrect results by mixing values in different currencies.
 *
 * Callers should use [AnalyticsCurrencyNormalizer.normalizeSnapshots] before
 * passing expenses to [generateInsights] or [getLegacyInsights].
 *
 * TODO (PR-E11): Accept NormalizedAnalyticsInput instead of querying raw expenses.
 * Engine should not call CurrencyConverter itself unless explicitly responsible.
 *
 * ## CURRENCY LIMITATION: Hardcoded EUR default
 * Several methods in this engine default `displayCurrency` to `"EUR"` when no
 * currency is provided by the caller. This means insights (formatting, comparison)
 * will be denominated in EUR even when the user's home currency is different.
 * A future migration should wire the user's actual home currency via
 * [com.yourname.expensetracker.domain.currency.CurrencySettingsRepository.homeCurrency]
 * and remove the hardcoded default.
 *
 * ## AIML-11: Confidence propagation
 * Insights that rely on AI-classified transactions inherit the classifier's
 * confidence score. The `adjustedConfidence` from [ConfidenceRouter] is stored
 * on the [PendingReview] and propagated through to insights. Callers should
 * check confidence thresholds before surfacing AI-derived insights to the user.
 *
 * ## AIML-12: Stale category IDs
 * Category references obtained at classification time may become stale if the
 * user renames or deletes categories after the expense is created. This engine
 * resolves category IDs via the `categoryMap` parameter provided by the caller
 * at insight-generation time. If a category has been deleted, the insight falls
 * back to `category = null` rather than displaying a dangling reference.
 *
 * ## AIML-13: Duplicate-inflated trust
 * Repeated identical transactions from the same merchant can inflate the
 * confidence score in pattern-detection logic. The recurring-expense
 * suppression in [AnomalyDetector.detect] mitigates this by excluding expenses
 * whose merchant key matches a known recurring rule. The merchant-level
 * anomaly detection in [findAnomalies] also uses an adaptive multiplier
 * (3× to 5×) that grows stricter as historical data accumulates.
 */
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

    /**
     * Generate insights for an explicit [PeriodRange] instead of the current month.
     *
     * ## AI-1: Explicit period support
     * This overload allows callers to specify an arbitrary time range, enabling
     * historical or custom-period insight generation. The range is decomposed
     * into a "current" period and an automatically computed "previous" period
     * of equal duration for month-over-month comparison.
     */
    suspend fun generateInsights(
        periodRange: PeriodRange,
        categories: List<AnalyticsCategoryRef>,
        allExpenses: List<ExpenseSnapshot>,
        /** Placeholder default. Production callers should pass explicit currency. */
        displayCurrency: String = "EUR",
        conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
    ): InsightsSnapshot {
        val currentMonth = MonthPeriod(
            year = TimePeriodUtils.getYear(periodRange.start),
            month = TimePeriodUtils.getMonth(periodRange.start),
            startMs = periodRange.start,
            endMs = periodRange.end
        )
        val durationMs = periodRange.duration
        val previousMonth = MonthPeriod(
            year = TimePeriodUtils.getYear(periodRange.start - durationMs),
            month = TimePeriodUtils.getMonth(periodRange.start - durationMs),
            startMs = periodRange.start - durationMs,
            endMs = periodRange.start
        )
        return generateInsightsForPeriods(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            categories = categories,
            allExpenses = allExpenses,
            displayCurrency = displayCurrency,
            conversionWarnings = conversionWarnings
        )
    }

    /**
     * Original overload: generates insights for the current month (from [TimeProvider.now]).
     */
    suspend fun generateInsights(
        categories: List<AnalyticsCategoryRef>,
        allExpenses: List<ExpenseSnapshot>,
        /** Placeholder default. Production callers should pass explicit currency. */
        displayCurrency: String = "EUR",
        conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
    ): InsightsSnapshot {
        val now = timeProvider.now()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)
        return generateInsightsForPeriods(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            categories = categories,
            allExpenses = allExpenses,
            displayCurrency = displayCurrency,
            conversionWarnings = conversionWarnings
        )
    }

    /**
     * Internal implementation shared by both [generateInsights] overloads.
     */
    private suspend fun generateInsightsForPeriods(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod,
        categories: List<AnalyticsCategoryRef>,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String,
        conversionWarnings: List<AnalyticsConversionWarning>
    ): InsightsSnapshot = coroutineScope {
        // Compute a reference "now" for relative-period calculations (day-of-week analysis etc.)
        val now = timeProvider.now()

        val categoryMap = categories.associateBy { it.id }

        // Start all independent queries in parallel with error handling
        val monthlyComparisonDeferred = async {
            try {
                monthlyComparisonCalculator.calculate(currentMonth, previousMonth, allExpenses, displayCurrency)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e(e, "InsightsEngine: monthlyComparison branch failed"); null }
        }
        val categoryInsightsDeferred = async {
            try {
                categoryInsightEngine.calculate(currentMonth, previousMonth, categoryMap, allExpenses, displayCurrency)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e(e, "InsightsEngine: categoryInsights branch failed"); null }
        }
        val topMerchantsDeferred = async {
            try {
                merchantInsightEngine.calculate(allExpenses, displayCurrency)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e(e, "InsightsEngine: topMerchants branch failed"); null }
        }
        val spendingPaceDeferred = async { 
            try { buildSpendingPace(currentMonth, previousMonth, allExpenses, displayCurrency) } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e(e, "InsightsEngine: spendingPace branch failed"); null }
        }
        val anomaliesDeferred = async { 
            try { findAnomalies(currentMonth, categoryMap, allExpenses, displayCurrency) } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e(e, "InsightsEngine: anomalies branch failed"); null }
        }
        val recurringExpensesDeferred = async { 
            try { findRecurringExpenses(allExpenses, displayCurrency) } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e(e, "InsightsEngine: recurringExpenses branch failed"); emptyList() }
        }
        
        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPatternDeferred = async {
            try {
                dayOfWeekAnalyzer.analyze(threeMonthsAgo.startMs, currentMonth.endMs, allExpenses, displayCurrency)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e(e, "InsightsEngine: dayOfWeekPattern branch failed"); null }
        }
        // Await all results with error resilience
        val monthlyComparison = monthlyComparisonDeferred.await()
        val categoryInsights = categoryInsightsDeferred.await()
        val topMerchants = topMerchantsDeferred.await()
        val spendingPace = spendingPaceDeferred.await()
        val anomalies = anomaliesDeferred.await()
        val recurringExpenses = recurringExpensesDeferred.await()
        val dayOfWeekPattern = dayOfWeekPatternDeferred.await()

        // Transaction size stats
        val currentMonthPurchases = allExpenses.filter {
            it.transactionType == DomainTransactionType.PURCHASE
                    && it.date >= currentMonth.startMs
                    && it.date < currentMonth.endMs
                    && !it.isNotMine
        }
        val largestTransaction = currentMonthPurchases.maxByOrNull { it.effectiveAmount }
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
                previousCount = null,
                displayCurrency = displayCurrency
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
                paceStatus = PaceStatus.NO_BASELINE,
                displayCurrency = displayCurrency
            ),
            anomalies = anomalies ?: emptyList(),
            recurringExpenses = recurringExpenses,
            dayOfWeekPattern = dayOfWeekPattern ?: emptyList(),
            largestTransaction = largestTransaction?.toAnalyticsSummary(),
            averageTransactionSize = avgTxSize,
            medianTransactionSize = medianTxSize,
            totalMonthsOfData = totalMonthsOfData,
            displayCurrency = displayCurrency,
            conversionWarnings = conversionWarnings
        )
    }

    private fun ExpenseSnapshot.toAnalyticsSummary(): AnalyticsTransactionSummary {
        return AnalyticsTransactionSummary(
            id = id,
            amount = effectiveAmount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            date = date,
            categoryId = categoryId
        )
    }

    // === Legacy Compatibility ===

    fun getLegacyInsights(snapshot: InsightsSnapshot, /** Placeholder default. Production callers should pass explicit currency. */ homeCurrency: String = "EUR"): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()

        // 1. Monthly Comparison (Spending Increase/Decrease)
        val comparison = snapshot.monthlyComparison
        if (comparison.changePercentage != null) {
            if (comparison.changePercentage > 20) {
                insights.add(
                        SpendingInsight(
                            InsightType.SPENDING_INCREASE, "📈",
                            UiText.fromKey(
                                DomainTextKeys.ANALYTICS_INSIGHT_SPENDING_UP_TITLE_FORMAT,
                                comparison.changePercentage.toInt()
                            ),
                            UiText.fromKey(
            DomainTextKeys.ANALYTICS_INSIGHT_SPENDING_UP_DESCRIPTION_FORMAT,
                        formatCurrency(comparison.currentTotal, homeCurrency),
                        formatCurrency(comparison.previousTotal ?: 0.0, homeCurrency)
                            ),
                            (comparison.changePercentage / 100).coerceAtMost(1.0f).toFloat()
                        )
                    )
            } else if (comparison.changePercentage < -15) {
                insights.add(
                        SpendingInsight(
                            InsightType.SPENDING_DECREASE, "📉",
                            UiText.fromKey(
                                DomainTextKeys.ANALYTICS_INSIGHT_SPENDING_DOWN_TITLE_FORMAT,
                                (-comparison.changePercentage).toInt()
                            ),
                            UiText.fromKey(
            DomainTextKeys.ANALYTICS_INSIGHT_SPENDING_DOWN_DESCRIPTION_FORMAT,
                        formatCurrency((comparison.previousTotal ?: 0.0) - comparison.currentTotal, homeCurrency)
                            ),
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
                    UiText.fromKey(DomainTextKeys.ANALYTICS_INSIGHT_PACE_WARNING_TITLE),
                    UiText.fromKey(
                        DomainTextKeys.ANALYTICS_INSIGHT_PACE_WARNING_DESCRIPTION_FORMAT,
                        pace.daysElapsed,
                        pace.pacePercentage.toInt()
                    ),
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
                            UiText.fromKey(
                                DomainTextKeys.ANALYTICS_INSIGHT_CATEGORY_UP_TITLE_FORMAT,
                                catInsight.category.name,
                                catInsight.changeFromPrevious.toInt()
                            ),
                            UiText.fromKey(
                        DomainTextKeys.ANALYTICS_INSIGHT_CATEGORY_UP_DESCRIPTION_FORMAT,
                        formatCurrency(catInsight.currentTotal, homeCurrency),
                        formatCurrency(catInsight.previousTotal ?: 0.0, homeCurrency)
                            ),
                            0.7f
                        )
                    )
                 }
            }
        }

        // 4. Recurring
        snapshot.recurringExpenses.take(3).forEach { recurring ->
                 if (recurring.intervalDays > 0) {
                     val cadenceLabel = recurringCadenceLabel(recurring.intervalDays)
                     insights.add(
                        SpendingInsight(
                            InsightType.RECURRING_DETECTED, "🔄",
                            UiText.fromKey(
                                DomainTextKeys.ANALYTICS_INSIGHT_RECURRING_TITLE_FORMAT,
                                recurring.merchant
                            ),
                            UiText.from("${formatCurrency(recurring.avgAmount, homeCurrency)} • $cadenceLabel"),
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
                    UiText.fromKey(
                        DomainTextKeys.ANALYTICS_INSIGHT_LARGEST_TRANSACTION_TITLE_FORMAT,
                        largest.merchant
                    ),
                    UiText.fromKey(
                    DomainTextKeys.ANALYTICS_INSIGHT_LARGEST_TRANSACTION_DESCRIPTION_FORMAT,
                        formatCurrency(largest.effectiveAmount, homeCurrency),
                        formatDate(largest.date)
                    ),
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

    // === Spending Pace ===

    private fun buildSpendingPace(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String
    ): SpendingPace {
        val canonicalPace = spendingPaceCalculator.calculate(
            currentMonthStart = currentMonth.startMs,
            previousMonthStart = previousMonth.startMs,
            previousMonthEnd = previousMonth.endMs,
            allExpenses = allExpenses,
            displayCurrency = displayCurrency
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
            paceStatus = canonicalPace.paceStatus,
            displayCurrency = displayCurrency
        )
    }

    private fun calculateAverageMonthlySpend(
        allExpenses: List<ExpenseSnapshot>,
        currentMonth: MonthPeriod
    ): Double? {
        val purchases = allExpenses.filter {
            it.transactionType == DomainTransactionType.PURCHASE
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
        categoryMap: Map<Long, AnalyticsCategoryRef>,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String
    ): List<AnomalyTransaction> = coroutineScope {

        // ── Path 1: merchant-level DB-backed detection (existing logic) ────────
        val historicalPurchasesByMerchant = allExpenses
            .asSequence()
            .filter {
                it.transactionType == DomainTransactionType.PURCHASE &&
                    !it.isNotMine &&
                    !it.merchantKey.isNullOrBlank() &&
                    it.date < currentMonth.startMs
            }
            .groupBy { it.merchantKey!!.trim() }

        val currentMonthPurchasesByMerchant = allExpenses
            .asSequence()
            .filter {
                it.transactionType == DomainTransactionType.PURCHASE &&
                    !it.isNotMine &&
                    !it.merchantKey.isNullOrBlank() &&
                    it.date >= currentMonth.startMs &&
                    it.date < currentMonth.endMs
            }
            .groupBy { it.merchantKey!!.trim() }
        val analyticsCategoryMap = categoryMap

        val candidates: List<AnomalyCandidate?> = currentMonthPurchasesByMerchant.mapNotNull { (merchantKey, currentMonthExpenses) ->
            val historicalExpenses = historicalPurchasesByMerchant[merchantKey].orEmpty()
            if (historicalExpenses.size < 3) return@mapNotNull null

            val historicalAverage = historicalExpenses.map { it.effectiveAmount }.average()
            if (!historicalAverage.isFinite() || historicalAverage <= 0.0) {
                return@mapNotNull null
            }

            val multiplier = when {
                historicalExpenses.size < 5  -> 5.0
                historicalExpenses.size < 10 -> 4.0
                else                                  -> 3.0
            }

            val currentMaxExpense = currentMonthExpenses.maxByOrNull { it.effectiveAmount } ?: return@mapNotNull null
            val currentMax = currentMaxExpense.effectiveAmount

            if (currentMax > historicalAverage * multiplier) {
                AnomalyCandidate(
                    merchantKey      = merchantKey,
                    expense          = currentMaxExpense,
                    historicalAvg    = historicalAverage,
                    deviationMultiple = (currentMax / historicalAverage).toFloat()
                )
            } else null
        }

        val topCandidates = candidates
            .filterNotNull()
            .sortedByDescending { it.deviationMultiple }
            .take(5)

        val merchantAnomalies: List<AnomalyTransaction> = topCandidates.map { candidate ->
            AnomalyTransaction(
                expense           = candidate.expense.toAnalyticsSummary(),
                merchantAvg       = candidate.historicalAvg,
                deviationMultiple = candidate.deviationMultiple,
                category          = candidate.expense.categoryId
                    ?.let { categoryMap[it] }
                    ?.let { category -> category },
                detectionMethod   = AnomalyMethod.MULTIPLIER,
                displayCurrency = displayCurrency
            )
        }

        // ── Path 2: statistical in-memory detection (IQR / MAD / contextual) ──
        // AI-2: Compute recurring merchant keys to suppress recurring expenses
        // from statistical anomaly detection.
        val recurringKeys: Set<String> = try {
            recurringExpenseEngine.getPatternsFromSnapshots(allExpenses)
                .mapNotNull { pattern ->
                    pattern.merchantName.takeIf { it.isNotBlank() }
                        ?.let { com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate(it) }
                        ?.takeIf { it.isNotBlank() }
                }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val statisticalAnomalies: List<AnomalyTransaction> =
            anomalyDetector.detect(currentMonth, analyticsCategoryMap, allExpenses, displayCurrency, recurringKeys)

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
        val merchantKey: String,
        val expense: ExpenseSnapshot,
        val historicalAvg: Double,
        val deviationMultiple: Float
    )

    // === Recurring Expenses ===

    private suspend fun findRecurringExpenses(
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String
    ): List<RecurringExpense> {
        // Use the centralized engine
        val patterns = recurringExpenseEngine.getPatternsFromSnapshots(allExpenses)
        
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
                frequency = pattern.previousDates.size.coerceAtLeast(1),
                intervalDays = intervalDays,
                amountVariation = 0.0, // Pattern doesn't expose this raw stat easily, but could add to Pattern if needed.
                isStable = pattern.amountVariancePercent < 0.1,
                displayCurrency = displayCurrency
            )
        }
    }

    // === Utility Functions ===
    
    // A08: Replace buildDailyTotals(expenses, days) with buildBuckets(expenses, startMs, endMs, granularity).
    // Using timeProvider.now() as the anchor means the window shifts every day, which is wrong
    // for historical analysis. An explicit startMs/endMs pair keeps the window fixed.
    fun buildDailyTotals(expenses: List<ExpenseSnapshot>, days: Int): Map<String, Double> {
        val now = timeProvider.now()
        val result = LinkedHashMap<String, Double>()

        // Initialize all days with 0
        for (i in days - 1 downTo 0) {
            val dayTs = TimePeriodUtils.addDays(now, -i)
            val key = DateFormatterUtils.formatTimestampJavaTime(dayTs, "yyyy-MM-dd")
            result[key] = 0.0
        }

        // Fill in actual values - Optimized: reuse Date object
        val purchases = expenses.filter { it.transactionType == DomainTransactionType.PURCHASE }
        for (expense in purchases) {
            val key = DateFormatterUtils.formatTimestampJavaTime(expense.date, "yyyy-MM-dd")
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

    private fun recurringCadenceLabel(intervalDays: Int): String {
        return when {
            intervalDays <= 0 -> "irregular"
            intervalDays in 6..8 -> "every week"
            intervalDays in 13..15 -> "every 2 weeks"
            intervalDays in 27..31 -> "monthly"
            intervalDays in 85..95 -> "quarterly"
            intervalDays in 170..190 -> "every 6 months"
            intervalDays in 350..380 -> "yearly"
            else -> "every $intervalDays days"
        }
    }


    private fun countDistinctMonths(expenses: List<ExpenseSnapshot>): Int {
        if (expenses.isEmpty()) return 0
        return expenses.map { expense ->
            String.format("%d-%02d", TimePeriodUtils.getYear(expense.date), TimePeriodUtils.getMonth(expense.date) + 1)
        }.distinct().size
    }

    // === Exposed Suspend Functions for Repository Usage ===
    
    suspend fun getSpendingPaceSuspend(expenses: List<ExpenseSnapshot>? = null, /** Placeholder default. Production callers should pass explicit currency. */ displayCurrency: String = "EUR"): SpendingPace {
        val now = timeProvider.now()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)
        
        // Use provided expenses or fetch from DB if null
        val recentExpenses = expenses ?: run {
            val sixMonthsAgo = getMonthPeriod(now, -6).startMs
            expenseRepository.getExpenseSnapshotsBetween(sixMonthsAgo, now)
        }
        
        return buildSpendingPace(currentMonth, previousMonth, recentExpenses, displayCurrency = displayCurrency)
    }

    private fun formatCurrency(amount: Double, /** Placeholder default. Production callers should pass explicit currency. */ currency: String = "EUR"): String = CurrencyFormatter.format(amount, currency)
    
    private fun formatDate(dateMs: Long): String {
         return DateFormatterUtils.formatTimestampJavaTime(dateMs, "MMM dd")
    }

}
