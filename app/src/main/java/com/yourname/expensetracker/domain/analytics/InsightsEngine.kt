package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.text.DomainTextKeys
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.util.DateFormatterUtils
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import com.yourname.expensetracker.domain.currency.SupportedCurrency
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

// === Engine ===

/**
 * ## Enforced currency boundary
 * Production inputs are normalized once by AnalyticsInputAssembler. Snapshot
 * compatibility entry points validate homogeneous supported currency before
 * performing financial statistics, and cannot guess an empty input currency.
 * Unavailable monthly/pace values remain null; section failures do not claim zero.
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
 *
 * Installment exclusion (INV-5, INV-29, decision 46(b)): [findAnomalies] also drops, by expense id
 * and across BOTH detection paths, every expense that [RecurringInstallmentEligibility] refuses
 * (an expense that pays an installment entry, or a plan purchase record). Such an expense is a
 * scheduled payment, not an outlier. The link sets are read once per call; merchant and
 * statistical baselines are unchanged. A reader failure reaches the anomalies stage catch and
 * yields no anomalies (fail-closed); a CancellationException propagates.
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
    private val dayOfWeekAnalyzer: DayOfWeekAnalyzer,
    private val installmentEligibility: RecurringInstallmentEligibility
) {

    /**
     * Generate insights for an explicit [PeriodRange] instead of the current month.
     *
     * ## A06-FIXED: Normalized input overload
     * This overload consumes [NormalizedAnalyticsInput] instead of raw
     * [ExpenseSnapshot] list. The home currency is resolved from
     * [input.homeCurrency] — no more hardcoded EUR.
     */
    suspend fun generateInsights(
        input: NormalizedAnalyticsInput,
        categories: List<AnalyticsCategoryRef>
    ): InsightsSnapshot {
        val period = input.period ?: return InsightsSnapshot(
            generatedAt = 0L,
            currentMonth = MonthPeriod(0, 0, 0L, 0L),
            monthlyComparison = MonthlyComparison(
                currentMonth = MonthPeriod(0, 0, 0L, 0L),
                previousMonth = null,
                currentTotal = null,
                previousTotal = null,
                changeAmount = null,
                changePercentage = null,
                currentCount = 0,
                previousCount = null,
                displayCurrency = input.homeCurrency
            ),
            categoryInsights = emptyList(),
            topMerchants = emptyList(),
            spendingPace = SpendingPace(
                currentMonthSpent = null,
                daysElapsed = 0,
                daysInMonth = 30,
                projectedTotal = null,
                previousMonthTotal = null,
                averageMonthlyTotal = null,
                // NEW-P6-013: canonical -1f sentinel paired with NO_BASELINE.
                pacePercentage = -1f,
                paceStatus = PaceStatus.NO_BASELINE,
                displayCurrency = input.homeCurrency
            ),
            anomalies = emptyList(),
            recurringExpenses = emptyList(),
            dayOfWeekPattern = emptyList(),
            largestTransaction = null,
            averageTransactionSize = 0.0,
            medianTransactionSize = 0.0,
            totalMonthsOfData = 0,
            displayCurrency = input.homeCurrency
        )
        val currentMonth = MonthPeriod(
            year = TimePeriodUtils.getYear(period.startInclusiveMillis),
            month = TimePeriodUtils.getMonth(period.startInclusiveMillis),
            startMs = period.startInclusiveMillis,
            endMs = period.endExclusiveMillis
        )
        val durationMs = period.endExclusiveMillis - period.startInclusiveMillis
        val previousMonth = MonthPeriod(
            year = TimePeriodUtils.getYear(period.startInclusiveMillis - durationMs),
            month = TimePeriodUtils.getMonth(period.startInclusiveMillis - durationMs),
            startMs = period.startInclusiveMillis - durationMs,
            endMs = period.startInclusiveMillis
        )
        // Map NormalizedExpense to ExpenseSnapshot for legacy engine consumption
        val snapshots = input.includedExpenses.map { it.toExpenseSnapshot() }
        return generateInsightsForPeriods(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            categories = categories,
            allExpenses = snapshots,
            displayCurrency = input.homeCurrency,
            conversionWarnings = input.dataQuality.conversionWarnings.map { msg ->
                AnalyticsConversionWarning(
                    type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                    message = msg,
                    affectedTransactionCount = 0
                )
            },
            currentInput = input
        )
    }

    /**
     * Generate insights with explicit historical context for comparison and baselines.
     *
     * [currentInput] drives the primary period and display currency.
     * [historicalInput] provides additional expenses for sub-engines that need
     * lookback data (monthly comparison, pace baselines, anomaly history, etc.).
     */
    suspend fun generateInsights(
        currentInput: NormalizedAnalyticsInput,
        historicalInput: NormalizedAnalyticsInput?,
        categories: List<AnalyticsCategoryRef>,
        conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
    ): InsightsSnapshot {
        val period = currentInput.period ?: return generateInsights(
            currentInput,
            categories
        )
        val currentMonth = MonthPeriod(
            year = TimePeriodUtils.getYear(period.startInclusiveMillis),
            month = TimePeriodUtils.getMonth(period.startInclusiveMillis),
            startMs = period.startInclusiveMillis,
            endMs = period.endExclusiveMillis
        )
        val durationMs = period.endExclusiveMillis - period.startInclusiveMillis
        val previousMonth = MonthPeriod(
            year = TimePeriodUtils.getYear(period.startInclusiveMillis - durationMs),
            month = TimePeriodUtils.getMonth(period.startInclusiveMillis - durationMs),
            startMs = period.startInclusiveMillis - durationMs,
            endMs = period.startInclusiveMillis
        )
        // Merge current + historical expenses so sub-engines have full context
        val currentSnapshots = currentInput.includedExpenses.map { it.toExpenseSnapshot() }
        val historicalSnapshots = historicalInput?.includedExpenses?.map { it.toExpenseSnapshot() } ?: emptyList()
        val allSnapshots = (currentSnapshots + historicalSnapshots).distinctBy { it.id }
        val mergedWarnings = if (conversionWarnings.isNotEmpty()) {
            conversionWarnings
        } else {
            (currentInput.dataQuality.conversionWarnings.map { msg ->
                AnalyticsConversionWarning(
                    type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                    message = msg,
                    affectedTransactionCount = 0
                )
            } + (historicalInput?.dataQuality?.conversionWarnings ?: emptyList()).map { msg ->
                AnalyticsConversionWarning(
                    type = AnalyticsConversionWarningType.MISSING_EXCHANGE_RATE,
                    message = msg,
                    affectedTransactionCount = 0
                )
            }).distinct()
        }
        return generateInsightsForPeriods(
            currentMonth = currentMonth,
            previousMonth = previousMonth,
            categories = categories,
            allExpenses = allSnapshots,
            displayCurrency = currentInput.homeCurrency,
            conversionWarnings = mergedWarnings,
            currentInput = currentInput,
            previousInput = historicalInput
        )
    }

    /**
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
        displayCurrency: String = SingleCurrencyAnalyticsInput.currencyOf(allExpenses),
        conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
    ): InsightsSnapshot {
        SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency)
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
        displayCurrency: String = SingleCurrencyAnalyticsInput.currencyOf(allExpenses),
        conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
    ): InsightsSnapshot {
        SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency)
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
        conversionWarnings: List<AnalyticsConversionWarning>,
        currentInput: NormalizedAnalyticsInput? = null,
        previousInput: NormalizedAnalyticsInput? = null
    ): InsightsSnapshot = coroutineScope {
        SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency)
        // Compute a reference "now" for relative-period calculations (day-of-week analysis etc.)
        val now = timeProvider.now()

        val categoryMap = categories.associateBy { it.id }

        // Start all independent queries in parallel with error handling
        val monthlyComparisonDeferred = async {
            try {
                if (currentInput != null) {
                    monthlyComparisonCalculator.calculate(
                        currentMonth = currentMonth,
                        previousMonth = previousMonth,
                        currentInput = currentInput,
                        previousInput = previousInput,
                        displayCurrency = displayCurrency
                    )
                } else {
                    monthlyComparisonCalculator.calculate(currentMonth, previousMonth, allExpenses, displayCurrency)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e("InsightsEngine: UNKNOWN_ERROR stage=monthlyComparison class=%s", e::class.java.simpleName); null }
        }
        val categoryInsightsDeferred = async {
            try {
                if (currentInput != null) {
                    categoryInsightEngine.calculate(
                        currentMonth = currentMonth,
                        previousMonth = previousMonth,
                        categoryMap = categoryMap,
                        currentInput = currentInput,
                        previousInput = previousInput,
                        displayCurrency = displayCurrency
                    )
                } else {
                    categoryInsightEngine.calculate(currentMonth, previousMonth, categoryMap, allExpenses, displayCurrency)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e("InsightsEngine: UNKNOWN_ERROR stage=categoryInsights class=%s", e::class.java.simpleName); null }
        }
        val topMerchantsDeferred = async {
            try {
                if (currentInput != null) {
                    merchantInsightEngine.calculate(currentInput)
                } else {
                    merchantInsightEngine.calculate(allExpenses, displayCurrency)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e("InsightsEngine: UNKNOWN_ERROR stage=topMerchants class=%s", e::class.java.simpleName); null }
        }
        val spendingPaceDeferred = async { 
            try {
                if (currentInput != null) {
                    buildSpendingPace(
                        currentMonth = currentMonth,
                        previousMonth = previousMonth,
                        currentInput = currentInput,
                        previousInput = previousInput,
                        allExpenses = allExpenses,
                        displayCurrency = displayCurrency
                    )
                } else {
                    buildSpendingPace(currentMonth, previousMonth, allExpenses, displayCurrency)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e("InsightsEngine: UNKNOWN_ERROR stage=spendingPace class=%s", e::class.java.simpleName); null }
        }
        val anomaliesDeferred = async { 
            try { findAnomalies(currentMonth, categoryMap, allExpenses, displayCurrency) } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e("InsightsEngine: UNKNOWN_ERROR stage=anomalies class=%s", e::class.java.simpleName); null }
        }
        val recurringExpensesDeferred = async {
            try {
                findRecurringExpenses(allExpenses)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("InsightsEngine: UNKNOWN_ERROR stage=recurringExpenses class=%s", e::class.java.simpleName)
                RecurringExpenseResult(emptyList(), emptyList())
            }
        }
        
        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPatternDeferred = async {
            try {
                if (currentInput != null) {
                    dayOfWeekAnalyzer.analyze(
                        startDate = threeMonthsAgo.startMs,
                        endDate = currentMonth.endMs,
                        input = currentInput,
                        historicalInput = previousInput,
                        displayCurrency = displayCurrency
                    )
                } else {
                    dayOfWeekAnalyzer.analyze(
                        startDate = threeMonthsAgo.startMs,
                        endDate = currentMonth.endMs,
                        input = SingleCurrencyAnalyticsInput.from(allExpenses, displayCurrency),
                        displayCurrency = displayCurrency
                    )
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Timber.e("InsightsEngine: UNKNOWN_ERROR stage=dayOfWeekPattern class=%s", e::class.java.simpleName); null }
        }
        // Await all results with error resilience
        val monthlyComparison = monthlyComparisonDeferred.await()
        val categoryInsights = categoryInsightsDeferred.await()
        val topMerchants = topMerchantsDeferred.await()
        val spendingPace = spendingPaceDeferred.await()
        val anomalies = anomaliesDeferred.await()
        val recurringExpenseResult = recurringExpensesDeferred.await()
        val dayOfWeekPattern = dayOfWeekPatternDeferred.await()

        // Transaction size stats
        val currentMonthPurchases = allExpenses.filter {
            it.transactionType == DomainTransactionType.PURCHASE
                    && it.date >= currentMonth.startMs
                    && it.date < currentMonth.endMs
                    && !it.isNotMine
        }
        val compatibilityMonthInput = if (currentInput == null && currentMonthPurchases.isNotEmpty()) {
            try {
                SingleCurrencyAnalyticsInput.from(
                    currentMonthPurchases,
                    SingleCurrencyAnalyticsInput.currencyOf(currentMonthPurchases)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val canonicalMonthPurchases = currentInput?.includedExpenses
            ?.filter { it.transactionType == DomainTransactionType.PURCHASE.name && it.date >= currentMonth.startMs && it.date < currentMonth.endMs && !it.isNotMine }
            ?.map { it.toExpenseSnapshot() }
            ?: compatibilityMonthInput?.includedExpenses?.map { it.toExpenseSnapshot() }
            ?: emptyList()
        val canonicalMonthAmounts = currentInput?.includedExpenses
            ?.filter { it.transactionType == DomainTransactionType.PURCHASE.name && it.date >= currentMonth.startMs && it.date < currentMonth.endMs && !it.isNotMine }
            ?.map { it.normalizedAmount }
            ?: compatibilityMonthInput?.includedExpenses?.map { it.normalizedAmount }
            ?: emptyList()
        val largestTransactionPair = canonicalMonthPurchases
            .zip(canonicalMonthAmounts)
            .maxByOrNull { it.second }
        val largestTransaction = largestTransactionPair?.first
        val largestTransactionDisplayAmount = largestTransactionPair?.second
        val avgTxSize = canonicalMonthAmounts.average().takeIf { canonicalMonthAmounts.isNotEmpty() } ?: 0.0
        val medianTxSize = calculateMedian(canonicalMonthAmounts)

        // How many months of data we have
        val totalMonthsOfData = countDistinctMonths(allExpenses)

        InsightsSnapshot(
            currentMonth = currentMonth,
            monthlyComparison = monthlyComparison ?: MonthlyComparison(
                currentMonth = currentMonth,
                previousMonth = null,
                currentTotal = null,
                previousTotal = null,
                changeAmount = null,
                changePercentage = null,
                currentCount = 0,
                previousCount = null,
                displayCurrency = displayCurrency
            ),
            categoryInsights = categoryInsights.orEmpty().filter { it.currentTotal != null && it.moneyResult !is com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Unavailable },
            topMerchants = topMerchants ?: emptyList(),
            spendingPace = spendingPace ?: SpendingPace(
                currentMonthSpent = null,
                daysElapsed = 0,
                daysInMonth = 30,
                projectedTotal = null,
                previousMonthTotal = null,
                averageMonthlyTotal = null,
                // NEW-P6-013: canonical -1f sentinel paired with NO_BASELINE.
                pacePercentage = -1f,
                paceStatus = PaceStatus.NO_BASELINE,
                displayCurrency = displayCurrency
            ),
            anomalies = anomalies ?: emptyList(),
            recurringExpenses = recurringExpenseResult.items,
            dayOfWeekPattern = dayOfWeekPattern.orEmpty().filter {
                it.moneyResult !is com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Unavailable
            },
            largestTransaction = largestTransaction?.let {
                largestTransactionDisplayAmount?.let { amount -> it.toAnalyticsSummary(amount) }
            },
            averageTransactionSize = avgTxSize,
            medianTransactionSize = medianTxSize,
            totalMonthsOfData = totalMonthsOfData,
            displayCurrency = displayCurrency,
            conversionWarnings = conversionWarnings + recurringExpenseResult.warnings,
            largestTransactionDisplayAmount = largestTransactionDisplayAmount
        )
    }

    private fun ExpenseSnapshot.toAnalyticsSummary(displayAmount: Double): AnalyticsTransactionSummary {
        return AnalyticsTransactionSummary(
            id = id,
            amount = displayAmount,
            effectiveAmount = displayAmount,
            currency = currency,
            merchant = merchant,
            date = date,
            categoryId = categoryId
        )
    }

    // === Legacy Compatibility ===

    /**
     * PR8-GUARDRAIL: Legacy convenience wrapper that maps [InsightsSnapshot] to a
     * list of [SpendingInsight]. Safe when [snapshot] was already computed from
     * normalized data. Prefer constructing insights directly from snapshot fields
     * to avoid the "EUR" default on [homeCurrency].
     */
    @Deprecated(
        "Legacy convenience wrapper. Prefer constructing SpendingInsight list directly from InsightsSnapshot fields.",
        level = DeprecationLevel.WARNING
    )
    fun getLegacyInsights(snapshot: InsightsSnapshot, homeCurrency: String = snapshot.monthlyComparison.displayCurrency): List<SpendingInsight> {
        require(com.yourname.expensetracker.domain.core.money.CurrencyCode.parse(homeCurrency) ==
            com.yourname.expensetracker.domain.core.money.CurrencyCode.parse(snapshot.displayCurrency)
        ) { "INSIGHT_DISPLAY_CURRENCY_MISMATCH" }
        val insights = mutableListOf<SpendingInsight>()

        // 1. Monthly Comparison (Spending Increase/Decrease)
        val comparison = snapshot.monthlyComparison
        if (comparison.changePercentage != null &&
            comparison.currentTotal != null &&
            comparison.previousTotal != null
        ) {
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
                        formatCurrency(comparison.previousTotal, homeCurrency)
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
                        formatCurrency(comparison.previousTotal - comparison.currentTotal, homeCurrency)
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
                 if (catInsight.changeFromPrevious > 40 &&
                     catInsight.currentTotal != null &&
                     catInsight.previousTotal != null &&
                     catInsight.currentTotal > 50
                 ) {
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
                        formatCurrency(catInsight.previousTotal, homeCurrency)
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
                            UiText.from("${formatCurrency(recurring.avgAmount, recurring.displayCurrency)} • $cadenceLabel"),
                            0.5f
                        )
                    )
                 }
        }

        // 5. Largest Transaction
        val largest = snapshot.largestTransaction
        val largestAmount = snapshot.largestTransactionDisplayAmount
        if (largest != null && largestAmount != null && largestAmount > 50) {
             insights.add(
                SpendingInsight(
                    InsightType.UNUSUAL_TRANSACTION, "⚡",
                    UiText.fromKey(
                        DomainTextKeys.ANALYTICS_INSIGHT_LARGEST_TRANSACTION_TITLE_FORMAT,
                        largest.merchant
                    ),
                    UiText.fromKey(
                    DomainTextKeys.ANALYTICS_INSIGHT_LARGEST_TRANSACTION_DESCRIPTION_FORMAT,
                        formatCurrency(largestAmount, homeCurrency),
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
        val referenceNowMs = minOf(timeProvider.now(), currentMonth.endMs)
        val canonicalPace = spendingPaceCalculator.calculate(
            currentMonthStart = currentMonth.startMs,
            previousMonthStart = previousMonth.startMs,
            previousMonthEnd = previousMonth.endMs,
            allExpenses = allExpenses,
            displayCurrency = displayCurrency,
            referenceNowMs = referenceNowMs
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
            displayCurrency = displayCurrency,
            currentMoneyResult = canonicalPace.currentMoneyResult,
            previousMoneyResult = canonicalPace.previousMoneyResult
        )
    }

    private fun buildSpendingPace(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod,
        currentInput: NormalizedAnalyticsInput,
        previousInput: NormalizedAnalyticsInput?,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String
    ): SpendingPace {
        return spendingPaceCalculator.calculate(
            currentMonthStart = currentMonth.startMs,
            previousMonthStart = previousMonth.startMs,
            previousMonthEnd = previousMonth.endMs,
            currentInput = currentInput,
            previousInput = previousInput,
            displayCurrency = displayCurrency,
            referenceNowMs = minOf(timeProvider.now(), currentMonth.endMs)
        ).let { canonicalPace ->
            canonicalPace.copy(
                averageMonthlyTotal = calculateAverageMonthlySpend(
                    allExpenses = allExpenses,
                    currentMonth = currentMonth
                )
            )
        }
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

        val normalizedPurchases = try {
            SingleCurrencyAnalyticsInput
                .from(purchases, SingleCurrencyAnalyticsInput.currencyOf(purchases))
                .includedExpenses
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val monthTotals = mutableMapOf<String, Double>()
        for (p in normalizedPurchases) {
            val key = String.format("%d-%02d", TimePeriodUtils.getYear(p.date), TimePeriodUtils.getMonth(p.date) + 1)
            monthTotals.merge(key, p.normalizedAmount) { a, b -> a + b }
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

        val safeNormalizedById = try {
            SingleCurrencyAnalyticsInput
                .from(allExpenses, displayCurrency)
                .includedExpenses
                .associateBy { it.id }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return@coroutineScope emptyList()
        }
        val safeExpenses = safeNormalizedById.values.map { it.toExpenseSnapshot() }

        // INV-29 (decision 46(b)): installment-linked and plan-purchase expenses are scheduled
        // payments, never anomalies. Read once per call (batch form), outside any try: a reader
        // failure reaches the stage catch in the caller (no anomalies, fail-closed) and a
        // CancellationException propagates. Baselines above/below stay untouched; the ids are
        // dropped from the merged result so both detection paths are covered.
        val installmentExcludedIds: Set<Long> = installmentEligibility.excludedSnapshotIds(safeExpenses)

        // ── Path 1: merchant-level DB-backed detection (existing logic) ────────
        val historicalPurchasesByMerchant = safeExpenses
            .asSequence()
            .filter {
                it.transactionType == DomainTransactionType.PURCHASE &&
                    !it.isNotMine &&
                    !it.merchantKey.isNullOrBlank() &&
                    it.date < currentMonth.startMs
            }
            .groupBy { it.merchantKey!!.trim() }

        val currentMonthPurchasesByMerchant = safeExpenses
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

            val historicalAmounts = historicalExpenses.mapNotNull { safeNormalizedById[it.id]?.normalizedAmount }
            if (historicalAmounts.size != historicalExpenses.size) return@mapNotNull null
            val historicalAverage = historicalAmounts.average()
            if (!historicalAverage.isFinite() || historicalAverage <= 0.0) {
                return@mapNotNull null
            }

            val multiplier = when {
                historicalExpenses.size < 5  -> 5.0
                historicalExpenses.size < 10 -> 4.0
                else                                  -> 3.0
            }

            val currentMaxExpense = currentMonthExpenses.maxByOrNull {
                safeNormalizedById[it.id]?.normalizedAmount ?: Double.NEGATIVE_INFINITY
            } ?: return@mapNotNull null
            val currentMax = safeNormalizedById[currentMaxExpense.id]?.normalizedAmount
                ?: return@mapNotNull null

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

        val merchantAnomalies: List<AnomalyTransaction> = topCandidates.mapNotNull { candidate ->
            val normalizedAmount = safeNormalizedById[candidate.expense.id]?.normalizedAmount
                ?: return@mapNotNull null
            AnomalyTransaction(
                expense           = candidate.expense.toAnalyticsSummary(normalizedAmount),
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
            recurringExpenseEngine.getPatternsFromSnapshots(safeExpenses)
                .mapNotNull { pattern ->
                    pattern.merchantName.takeIf { it.isNotBlank() }
                        ?.let { com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate(it) }
                        ?.takeIf { it.isNotBlank() }
                }
                .toSet()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptySet()
        }
        val statisticalAnomalies: List<AnomalyTransaction> =
            anomalyDetector.detect(currentMonth, analyticsCategoryMap, safeExpenses, displayCurrency, recurringKeys)

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
            .filter { it.expense.id !in installmentExcludedIds }
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

    private data class RecurringExpenseResult(
        val items: List<RecurringExpense>,
        val warnings: List<AnalyticsConversionWarning>
    )

    private suspend fun findRecurringExpenses(
        allExpenses: List<ExpenseSnapshot>
    ): RecurringExpenseResult {
        // Use the centralized engine
        val patterns = recurringExpenseEngine.getPatternsFromSnapshots(allExpenses)

        val items = mutableListOf<RecurringExpense>()
        var invalidCurrencyCount = 0

        // Source-label contract: manual rules retain their own validated currency,
        // while detected patterns from normalized input already carry the home currency.
        for (pattern in patterns) {
            val sourceCurrency = SupportedCurrency.fromCode(pattern.currency)
            if (sourceCurrency == null) {
                invalidCurrencyCount++
                continue
            }
            val intervalDays = when (pattern.frequency) {
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.WEEKLY -> 7
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.BIWEEKLY -> 14
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY -> 30
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.QUARTERLY -> 90
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.SEMI_ANNUALLY -> 180
                com.yourname.expensetracker.domain.model.RecurrenceFrequency.ANNUALLY -> 365
                else -> 0
            }
            
            items += RecurringExpense(
                merchant = pattern.merchantName,
                avgAmount = pattern.averageAmount,
                frequency = pattern.previousDates.size.coerceAtLeast(1),
                intervalDays = intervalDays,
                amountVariation = 0.0, // Pattern doesn't expose this raw stat easily, but could add to Pattern if needed.
                isStable = pattern.amountVariancePercent < 0.1,
                displayCurrency = sourceCurrency.code
            )
        }

        val warnings = if (invalidCurrencyCount > 0) {
            listOf(
                AnalyticsConversionWarning(
                    type = AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY,
                    message = AnalyticsConversionWarningType.INVALID_TRANSACTION_CURRENCY.name,
                    affectedTransactionCount = invalidCurrencyCount
                )
            )
        } else {
            emptyList()
        }
        return RecurringExpenseResult(items = items, warnings = warnings)
    }

    // === Utility Functions ===
    
    // A08: Replace buildDailyTotals(expenses, days) with buildBuckets(expenses, startMs, endMs, granularity).
    // Using timeProvider.now() as the anchor means the window shifts every day, which is wrong
    // for historical analysis. An explicit startMs/endMs pair keeps the window fixed.
    @Deprecated(
        message = "Use buildDailyTotals(NormalizedAnalyticsInput, days) for money-safe analytics",
        level = DeprecationLevel.WARNING
    )
    internal fun buildDailyTotals(expenses: List<ExpenseSnapshot>, days: Int): Map<String, Double> {
        val purchases = expenses.filter {
            it.transactionType == DomainTransactionType.PURCHASE && !it.isNotMine
        }
        if (purchases.isEmpty()) return emptyDailyTotals(days)
        val normalized = try {
            SingleCurrencyAnalyticsInput.from(
                purchases,
                SingleCurrencyAnalyticsInput.currencyOf(purchases)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyMap()
        }
        return buildDailyTotals(normalized, days)
    }

    fun buildDailyTotals(input: NormalizedAnalyticsInput, days: Int): Map<String, Double> {
        if (days <= 0) return emptyMap()
        val now = timeProvider.now()
        val result = linkedMapOf<String, Double>()
        val purchases = input.includedExpenses
            .filter { it.transactionType == DomainTransactionType.PURCHASE.name && !it.isNotMine }
        for (offset in days - 1 downTo 0) {
            val start = TimePeriodUtils.getStartOfDay(TimePeriodUtils.addDays(now, -offset))
            val end = TimePeriodUtils.addDays(start, 1)
            val moneyResult = NormalizedAnalyticsMoneyAdapter.aggregateInputSlice(
                input, purchases, startInclusiveMillis = start, endExclusiveMillis = end
            )
            val amount = when (moneyResult) {
                is MoneyAggregateResult.Available -> moneyResult.aggregate.displayAmount
                is MoneyAggregateResult.Unavailable -> throw MoneyDisplayUnavailableException(
                    MoneyDisplayUnavailableReasonCode.entries.firstOrNull { it.name == moneyResult.reason }
                        ?: MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE
                )
            }
            result[DateFormatterUtils.formatTimestampJavaTime(start, "yyyy-MM-dd")] = amount
        }
        return result
    }

    private fun emptyDailyTotals(days: Int): LinkedHashMap<String, Double> {
        val now = timeProvider.now()
        return LinkedHashMap<String, Double>().apply {
            for (i in days - 1 downTo 0) {
                val dayTs = TimePeriodUtils.addDays(now, -i)
                val key = DateFormatterUtils.formatTimestampJavaTime(dayTs, "yyyy-MM-dd")
                put(key, 0.0)
            }
        }
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
    
    suspend fun getSpendingPaceSuspend(expenses: List<ExpenseSnapshot>? = null, displayCurrency: String? = null): SpendingPace {
        val now = timeProvider.now()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)
        
        // Use provided expenses or fetch from DB if null
        val recentExpenses = expenses ?: run {
            val sixMonthsAgo = getMonthPeriod(now, -6).startMs
            expenseRepository.getExpenseSnapshotsBetween(sixMonthsAgo, now)
        }
        
        val currency = displayCurrency ?: SingleCurrencyAnalyticsInput.currencyOf(recentExpenses)
        SingleCurrencyAnalyticsInput.from(recentExpenses, currency)
        return buildSpendingPace(currentMonth, previousMonth, recentExpenses, displayCurrency = currency)
    }

    private fun formatCurrency(amount: Double, currency: String): String = CurrencyFormatter.formatMoney(amount, currency)
    
    private fun formatDate(dateMs: Long): String {
         return DateFormatterUtils.formatTimestampJavaTime(dateMs, "MMM dd")
    }

}
