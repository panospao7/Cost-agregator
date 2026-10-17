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
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregateMetadata
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.MoneyBucket
import com.yourname.expensetracker.domain.core.money.RateBasis
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
 * Financial totals, velocity, histogram totals, and time slices use the
 * canonical money aggregate boundary. Normalized inputs retain original
 * currency and rate-date provenance without a second conversion. Scalar
 * compatibility projections and chart/statistical consumers still require
 * the separately planned caller migration before money-guard closure.
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
        // GR-14g: comparison windows come from the non-recursive leaf below;
        // getPeriodRange -> getPreviousPeriodRange -> calculatePeriodRange,
        // never back into getPeriodRange.
        val range = calculatePeriodRange(period, referenceDate)
        if (!computeComparison) return range
        return range.copy(comparisonRange = getPreviousPeriodRange(period, range.startMs))
    }

    /** Single-window leaf (comparisonRange always null) — no recursion. */
    private fun calculatePeriodRange(
        period: AnalyticsPeriod,
        referenceDate: Long
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
            comparisonRange = null
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

            calculatePeriodRange(period, previousRef)
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
        budgets: List<BudgetSnapshot>,
        comparisonCutoffMillisInclusive: Long? = null
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
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine && input.period?.contains(it.date) != false }
            .map { it.toExpenseSnapshot() }
        val previousPurchases = previousInput?.includedExpenses
            ?.filter { it.transactionType == "PURCHASE" && !it.isNotMine && previousInput?.period?.contains(it.date) != false }
            ?.map { it.toExpenseSnapshot() }
            ?: emptyList()
        val allWarnings = (input.dataQuality.warnings +
            (previousInput?.dataQuality?.warnings ?: emptyList())).distinct()
        computeCategoryAnalyticsCore(
            currentPurchases,
            previousPurchases,
            categories,
            budgets,
            period,
            input.homeCurrency,
            allWarnings,
            currentContext = MoneySliceContext(input, input.includedExpenses.associateBy { it.id }),
            previousContext = previousInput?.let {
                MoneySliceContext(it, it.includedExpenses.associateBy { expense -> expense.id })
            },
            comparisonCutoffMillisInclusive = comparisonCutoffMillisInclusive
        )
    }

    /**
     * Generates enhanced analytics for all categories within the specified period.
     *
     * PR8-GUARDRAIL: Self-fetches from [ExpenseRepository], creating a second DB query
     * that may diverge from the ViewModel's normalization pipeline. Safe for isolated
     * use (e.g., HomeViewModel category trends) but should be migrated to the
     * [NormalizedAnalyticsInput] overload for consistency with the main analytics screen.
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
        allWarnings: List<AnalyticsConversionWarning>,
        currentContext: MoneySliceContext? = null,
        previousContext: MoneySliceContext? = null,
        // Like-for-like delta: when set, only current rows dated at or before this instant are
        // compared with the (cut) previous window. Displayed totals stay the full period.
        comparisonCutoffMillisInclusive: Long? = null
    ): Pair<List<EnhancedCategoryAnalytics>, List<AnalyticsConversionWarning>> {
        // Build lookup maps
        val categoryMap = categories.associateBy { it.id }
        val budgetMap = budgets.filter {
            CurrencyCode.parse(it.currency) == CurrencyCode.parse(displayCurrency) && it.amount.isFinite()
        }.associateBy { it.categoryId }
        
        // Group expenses by category
        val currentByCategory = currentPurchases.groupBy { it.categoryId }
        val previousByCategory = previousPurchases.groupBy { it.categoryId }
        
        // Build sparkline data (daily cumulative by category)
        val sparklineData = buildSparklineDataByCategory(currentPurchases, period, displayCurrency, currentContext)
        
        // Build analytics for each category with spending
        return currentByCategory.mapNotNull { (categoryId, expenses) ->
            val category = categoryMap[categoryId] ?: return@mapNotNull null
            
            val totalResult = aggregateMoney(expenses, displayCurrency, currentContext)
            if (totalResult is MoneyAggregateResult.Unavailable) {
                throwUnavailable(totalResult)
            }
            val total = availableAmount(totalResult)
            val amounts = expenses.map { normalizedValue(it, displayCurrency, currentContext) }
            val sortedAmounts = amounts.sorted()
            
            // Previous period comparison
            val previousExpenses = previousByCategory[categoryId]
            val previousResult = if (previousExpenses != null || previousContext?.scopedInput()?.excludedExpenses?.isNotEmpty() == true) {
                aggregateMoney(previousExpenses.orEmpty(), displayCurrency, previousContext)
            } else null
            val previousTotal = (previousResult as? MoneyAggregateResult.Available)?.aggregate?.displayAmount
            val comparisonResult = if (comparisonCutoffMillisInclusive == null) {
                totalResult
            } else {
                aggregateMoney(
                    expenses.filter { it.date <= comparisonCutoffMillisInclusive },
                    displayCurrency,
                    currentContext
                )
            }
            val changePercent = if (comparisonResult is MoneyAggregateResult.Available) {
                calculateChangePercent(availableAmount(comparisonResult), previousTotal)
            } else null
            
            // Budget context
            val budget = budgetMap[categoryId]
            val budgetUtilization = budget?.takeIf { totalResult is MoneyAggregateResult.Available }?.let { b ->
                if (b.amount > 0) (total / b.amount * 100).toFloat() else null
            }
            val budgetStatus = budget?.takeIf { totalResult is MoneyAggregateResult.Available }
                ?.let { b -> determineBudgetStatus(total, b.amount) }
            
            // Percentiles
            val p25 = getPercentile(sortedAmounts, 0.25)
            val p75 = getPercentile(sortedAmounts, 0.75)
            
            // Velocity (spending acceleration within period)
            val velocityResult = calculateVelocityResult(expenses, displayCurrency, currentContext)
            val velocity = when (velocityResult) {
                is MoneyAggregateResult.Available -> velocityResult.aggregate.displayAmount
                is MoneyAggregateResult.Unavailable -> return@mapNotNull null
            }
            
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
                averagePerTransaction = if (expenses.isNotEmpty()) total / expenses.size else 0.0,
                medianTransaction = getPercentile(sortedAmounts, 0.50),
                previousPeriodTotal = previousTotal,
                changePercent = changePercent,
                trendDirection = determineTrendDirection(changePercent),
                budgetAmount = budget?.amount,
                budgetUtilizationPercent = budgetUtilization,
                budgetRemaining = budget?.takeIf { totalResult is MoneyAggregateResult.Available }?.let { it.amount - total },
                budgetStatus = budgetStatus,
                minTransaction = sortedAmounts.firstOrNull() ?: 0.0,
                maxTransaction = sortedAmounts.lastOrNull() ?: 0.0,
                percentile25 = p25,
                percentile75 = p75,
                sparklineData = if (totalResult is MoneyAggregateResult.Available) sparklineData[categoryId].orEmpty() else emptyList(),
                velocity = velocity,
                totalMoneyResult = totalResult,
                previousPeriodMoneyResult = previousResult,
                velocityMoneyResult = velocityResult
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
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine && input.period?.contains(it.date) != false }
            .map { it.toExpenseSnapshot() }
        val historicalPurchases = historicalInput.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine && historicalInput.period?.contains(it.date) != false }
            .map { it.toExpenseSnapshot() }
        val allWarnings = (input.dataQuality.warnings + historicalInput.dataQuality.warnings).distinct()
        computeMerchantAnalyticsCore(
            currentPurchases,
            historicalPurchases,
            period,
            input.homeCurrency,
            limit,
            allWarnings,
            currentContext = MoneySliceContext(input, input.includedExpenses.associateBy { it.id }),
            historicalContext = MoneySliceContext(historicalInput, historicalInput.includedExpenses.associateBy { it.id })
        )
    }

    /**
     * Generates enhanced analytics for top merchants within the specified period.
     * @param limit Maximum number of merchants to return
     *
     * PR8-GUARDRAIL: Self-fetches from [ExpenseRepository]. No production callers remain;
     * exists only for legacy test coverage. Prefer [getMerchantAnalytics] with
     * [NormalizedAnalyticsInput] so the caller controls the data source.
     */
    @Deprecated(
        "Use getMerchantAnalytics(input, historicalInput, limit)",
        level = DeprecationLevel.ERROR
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
        allWarnings: List<AnalyticsConversionWarning>,
        currentContext: MoneySliceContext? = null,
        historicalContext: MoneySliceContext? = null
    ): Pair<List<EnhancedMerchantAnalytics>, List<AnalyticsConversionWarning>> {
        val historicalByMerchantKey = historicalPurchases.groupBy { it.canonicalMerchantKey() }

        return currentPurchases
            .groupBy { it.canonicalMerchantKey() }
            .mapNotNull { (merchantKey, transactions) ->
                val totalResult = aggregateMoney(transactions, displayCurrency, currentContext)
                if (totalResult is MoneyAggregateResult.Unavailable) return@mapNotNull null
                val amounts = transactions.map { normalizedValue(it, displayCurrency, currentContext) }
                val sortedAmounts = amounts.sorted()
                val dates = transactions.map { it.date }.sorted()
                val displayName = resolveMerchantDisplayName(transactions)
                
                // Historical context for price trends
                val historicalCandidates = historicalByMerchantKey[merchantKey]
                    .orEmpty()
                    .sortedBy { it.date }
                val historicalResult = aggregateMoney(historicalCandidates, displayCurrency, historicalContext)
                val historicalForMerchant = if (historicalResult is MoneyAggregateResult.Available) {
                    historicalCandidates
                } else emptyList()
                
                // Visit frequency analysis
                val avgDaysBetween = calculateAverageDaysBetween(dates)
                val visitFrequency = determineVisitFrequency(transactions.size, period, avgDaysBetween)
                
                // Price trend analysis
                val priceTrendData = analyzePriceTrend(historicalForMerchant, displayCurrency, historicalContext)
                
                // Loyalty score
                val loyaltyScore = calculateLoyaltyScore(amounts, historicalForMerchant.size)
                
                // Consistency rating
                val consistencyRating = determineConsistencyRating(amounts, avgDaysBetween)
                
                // Streak count (consecutive months visited)
                val streakCount = calculateStreakCount(historicalForMerchant)
                
                // Spending by day of week
                val spendingByDayMoney = transactions.groupBy { calendarDayToIndex(it.date) }
                    .mapValues { (_, dayTransactions) -> aggregateMoney(dayTransactions, displayCurrency, currentContext) }
                val spendingByDay = spendingByDayMoney.mapNotNull { (day, result) ->
                    (result as? MoneyAggregateResult.Available)?.aggregate?.displayAmount?.let { day to it }
                }.toMap()
                // Predicted next visit
                val predictedNext = predictNextVisit(dates, avgDaysBetween)
                
                EnhancedMerchantAnalytics(
                    merchant = displayName,
                    period = period,
                    displayCurrency = displayCurrency,
                    totalSpent = availableAmount(totalResult),
                    transactionCount = transactions.size,
                    averagePerVisit = if (transactions.isNotEmpty()) availableAmount(totalResult) / transactions.size else 0.0,
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
                        .map { it.toAnalyticsTransactionSummary() },
                    totalMoneyResult = totalResult,
                    spendingByDayMoneyResults = spendingByDayMoney
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
     *
     * PR8-GUARDRAIL: No production callers remain; exists only for legacy test coverage.
     */
    @Deprecated(
        "Use getSpendingPatterns(input: NormalizedAnalyticsInput)",
        level = DeprecationLevel.WARNING
    )
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
            computeSpendingPatternsCore(purchases, period, displayCurrency, allWarnings)
        }
    }

    suspend fun getSpendingPatterns(
        input: NormalizedAnalyticsInput
    ): Pair<SpendingPatternAnalysis, List<AnalyticsConversionWarning>> = withContext(defaultDispatcher) {
        val period = AnalyticsPeriodRange(
            period = AnalyticsPeriod.CUSTOM,
            startMs = input.period?.startInclusiveMillis ?: 0L,
            endMs = input.period?.endExclusiveMillis ?: 0L,
            label = input.period?.label ?: "",
            comparisonRange = null
        )
        val purchases = input.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine && input.period?.contains(it.date) != false }
            .map { it.toExpenseSnapshot() }
        computeSpendingPatternsCore(
            purchases,
            period,
            input.homeCurrency,
            input.dataQuality.warnings,
            MoneySliceContext(input, input.includedExpenses.associateBy { it.id })
        )
    }

    private fun computeSpendingPatternsCore(
        purchases: List<ExpenseSnapshot>,
        period: AnalyticsPeriodRange,
        displayCurrency: String,
        allWarnings: List<AnalyticsConversionWarning>,
        moneyContext: MoneySliceContext? = null
    ): Pair<SpendingPatternAnalysis, List<AnalyticsConversionWarning>> {
        val totalResult = aggregateMoney(purchases, displayCurrency, moneyContext) { true }
        if (totalResult is MoneyAggregateResult.Unavailable) {
            throwUnavailable(totalResult)
        }
        if (purchases.isEmpty()) {
            return Pair(
                createEmptyPatternAnalysis(period, displayCurrency, totalResult),
                allWarnings
            )
        }
        val totalSpent = availableAmount(totalResult)

        val purchasesByDay = purchases.groupBy { calendarDayToIndex(it.date) }
        val dayCounts = IntArray(7) { index -> purchasesByDay[index].orEmpty().size }
        val dayMoneyResults = (0..6).associateWith { index ->
            aggregateMoney(purchasesByDay[index].orEmpty(), displayCurrency, moneyContext) {
                it.date == null || calendarDayToIndex(it.date) == index
            }
        }
        val purchasesByTimeSlot = purchases.groupBy {
            hourToTimeSlot(TimePeriodUtils.getHourOfDay(it.date))
        }
        val timeSlotMoneyResults = TimeSlot.values().associateWith { slot ->
            aggregateMoney(purchasesByTimeSlot[slot].orEmpty(), displayCurrency, moneyContext) {
                it.date == null || hourToTimeSlot(TimePeriodUtils.getHourOfDay(it.date)) == slot
            }
        }
        val weekdayResult = aggregateMoney(
            purchases.filter { calendarDayToIndex(it.date) in 0..4 }, displayCurrency, moneyContext
        ) { it.date == null || calendarDayToIndex(it.date) in 0..4 }
        val weekendResult = aggregateMoney(
            purchases.filter { calendarDayToIndex(it.date) in 5..6 }, displayCurrency, moneyContext
        ) { it.date == null || calendarDayToIndex(it.date) in 5..6 }
        if (dayMoneyResults.values.any { it is MoneyAggregateResult.Unavailable } ||
            timeSlotMoneyResults.values.any { it is MoneyAggregateResult.Unavailable } ||
            weekdayResult is MoneyAggregateResult.Unavailable ||
            weekendResult is MoneyAggregateResult.Unavailable
        ) {
            return Pair(
                createEmptyPatternAnalysis(period, displayCurrency, totalResult),
                allWarnings
            )
        }
        val dayTotals = DoubleArray(7) { index -> availableAmount(dayMoneyResults.getValue(index)) }
        val timeSlotStats = timeSlotMoneyResults.mapValues { (_, result) -> availableAmount(result) }

        val dayOfWeekStats = (0..6).associateWith { index ->
            DayOfWeekStats(
                dayName = DAY_NAMES[index],
                dayIndex = index,
                totalSpent = dayTotals[index],
                transactionCount = dayCounts[index],
                averagePerDay = if (dayCounts[index] > 0) dayTotals[index] / dayCounts[index] else 0.0,
                percentageOfWeek = if (totalSpent > 0) (dayTotals[index] / totalSpent * 100).toFloat() else 0f,
                displayCurrency = displayCurrency,
                moneyResult = dayMoneyResults.getValue(index)
            )
        }
        val weekdayTotal = availableAmount(weekdayResult)
        val weekendTotal = availableAmount(weekendResult)
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
            displayCurrency = displayCurrency,
            weekdayMoneyResult = weekdayResult,
            weekendMoneyResult = weekendResult
        )

        val detectedPatterns = if (totalResult is MoneyAggregateResult.Available && !totalResult.aggregate.isPartial) {
            detectSpendingPatterns(purchases, dayTotals, timeSlotStats, totalSpent, displayCurrency, moneyContext)
        } else emptyList()

        return Pair(SpendingPatternAnalysis(
            period = period,
            dayOfWeekStats = dayOfWeekStats,
            mostActiveDayIndex = dayTotals.indices.maxByOrNull { dayTotals[it] } ?: 0,
            leastActiveDayIndex = dayTotals.indices.minByOrNull { dayTotals[it] } ?: 0,
            weekendVsWeekday = weekendWeekdayComparison,
            timeOfDayDistribution = timeSlotStats,
            detectedPatterns = detectedPatterns,
            totalMoneyResult = totalResult,
            dayOfWeekMoneyResults = dayMoneyResults,
            timeOfDayMoneyResults = timeSlotMoneyResults
        ), allWarnings)
    }

    private fun createEmptyPatternAnalysis(
        period: AnalyticsPeriodRange,
        displayCurrency: String,
        totalMoneyResult: MoneyAggregateResult? = null
    ): SpendingPatternAnalysis {
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
            detectedPatterns = emptyList(),
            totalMoneyResult = totalMoneyResult
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
     *
     * PR8-GUARDRAIL: No production callers remain; exists only for legacy test coverage.
     */
    @Deprecated(
        "Use getStatisticalInsights(input: NormalizedAnalyticsInput)",
        level = DeprecationLevel.WARNING
    )
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
            computeStatisticalInsightsCore(purchases, period, displayCurrency, allWarnings)
        }
    }

    suspend fun getStatisticalInsights(
        input: NormalizedAnalyticsInput
    ): Pair<StatisticalInsights, List<AnalyticsConversionWarning>> = withContext(defaultDispatcher) {
        val period = AnalyticsPeriodRange(
            period = AnalyticsPeriod.CUSTOM,
            startMs = input.period?.startInclusiveMillis ?: 0L,
            endMs = input.period?.endExclusiveMillis ?: 0L,
            label = input.period?.label ?: "",
            comparisonRange = null
        )
        val purchases = input.includedExpenses
            .filter { it.transactionType == "PURCHASE" && !it.isNotMine && input.period?.contains(it.date) != false }
            .map { it.toExpenseSnapshot() }
        computeStatisticalInsightsCore(
            purchases, period, input.homeCurrency, input.dataQuality.warnings,
            MoneySliceContext(input, input.includedExpenses.associateBy { it.id })
        )
    }

    private fun computeStatisticalInsightsCore(
        purchases: List<ExpenseSnapshot>,
        period: AnalyticsPeriodRange,
        displayCurrency: String,
        allWarnings: List<AnalyticsConversionWarning>,
        moneyContext: MoneySliceContext? = null
    ): Pair<StatisticalInsights, List<AnalyticsConversionWarning>> {
        val totalResult = aggregateMoney(purchases, displayCurrency, moneyContext) { true }
        if (totalResult is MoneyAggregateResult.Unavailable) {
            throwUnavailable(totalResult)
        }
        if (purchases.isEmpty()) {
            return Pair(createEmptyStatisticalInsights(period, displayCurrency).copy(totalMoneyResult = totalResult), allWarnings)
        }

        val amounts = purchases.map { normalizedValue(it, displayCurrency, moneyContext) }
        val sortedAmounts = amounts.sorted()

        val totalAmount = availableAmount(totalResult)
        val mean = totalAmount / amounts.size
        val variance = if (amounts.size > 1) {
            amounts.sumOf { (it - mean) * (it - mean) } / (amounts.size - 1)
        } else 0.0
        val stdDev = sqrt(variance)
        val cv = if (mean > 0) (stdDev / mean).toFloat() else 0f

        val histogram = buildHistogram(purchases, 10, displayCurrency, moneyContext)

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

        val purchasesByDay = purchases.groupBy { TimePeriodUtils.getStartOfDay(it.date) }
        val excludedDays = moneyContext?.scopedInput()?.excludedExpenses.orEmpty()
            .mapNotNull { it.date?.let(TimePeriodUtils::getStartOfDay) }
        val dailyResults = (purchasesByDay.keys + excludedDays).associate { dayStart ->
            val result = aggregateMoney(purchasesByDay[dayStart].orEmpty(), displayCurrency, moneyContext) {
                it.date == null || TimePeriodUtils.getStartOfDay(it.date) == dayStart
            }
            val key = "${TimePeriodUtils.getYear(dayStart)}-${TimePeriodUtils.getMonth(dayStart) + 1}-${TimePeriodUtils.getDayOfMonth(dayStart)}"
            key to result
        }
        if (dailyResults.values.any { it is MoneyAggregateResult.Unavailable }) {
            return Pair(
                createEmptyStatisticalInsights(period, displayCurrency)
                    .copy(totalMoneyResult = totalResult, dailyMoneyResults = dailyResults),
                allWarnings
            )
        }
        val dailyTotals = dailyResults.mapValues { (_, result) -> availableAmount(result) }

        val periodDays = TimePeriodUtils.daysBetween(period.startMs, period.endMs).coerceAtLeast(1)

        val averageDailySpend = totalAmount / periodDays

        return Pair(StatisticalInsights(
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
                .maxByOrNull { normalizedValue(it, displayCurrency, moneyContext) }
                ?.toAnalyticsTransactionSummary(),
            smallestTransaction = purchases
                .minByOrNull { normalizedValue(it, displayCurrency, moneyContext) }
                ?.toAnalyticsTransactionSummary(),
            averageDailySpend = averageDailySpend,
            maxDailySpend = dailyTotals.values.maxOrNull() ?: 0.0,
            daysWithSpending = purchasesByDay.size,
            daysWithoutSpending = (periodDays - purchasesByDay.size).coerceAtLeast(0),
            totalMoneyResult = totalResult,
            dailyMoneyResults = dailyResults
        ), allWarnings)
    }

    private fun createEmptyStatisticalInsights(
        period: AnalyticsPeriodRange,
        displayCurrency: String
    ): StatisticalInsights {
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
        period: AnalyticsPeriodRange,
        displayCurrency: String,
        moneyContext: MoneySliceContext?
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

        return purchases.groupBy { it.categoryId }.mapValues { (_, categoryExpenses) ->
            (0 until daysPassed).map { dayIndex ->
                val dayEnd = TimePeriodUtils.addDays(periodStartDay, dayIndex + 1)
                val result = aggregateMoney(
                    categoryExpenses.filter { it.date < dayEnd }, displayCurrency, moneyContext
                )
                (result as? MoneyAggregateResult.Available)?.aggregate?.displayAmount
            }.takeIf { totals -> totals.all { it != null } }?.map { it!! }.orEmpty()
        }
    }
    

    private fun calculateVelocityResult(
        expenses: List<ExpenseSnapshot>,
        displayCurrency: String,
        moneyContext: MoneySliceContext?
    ): MoneyAggregateResult {
        val totalResult = aggregateMoney(expenses, displayCurrency, moneyContext)
        if (totalResult !is MoneyAggregateResult.Available) return totalResult
        val sorted = expenses.sortedBy { it.date }
        val midpoint = sorted.size / 2
        val firstHalf = sorted.subList(0, midpoint)
        val secondHalf = sorted.subList(midpoint, sorted.size)
        val velocity = if (expenses.size < 2) 0.0 else {
            val secondResult = aggregateMoney(secondHalf, displayCurrency, moneyContext)
            val firstResult = aggregateMoney(firstHalf, displayCurrency, moneyContext)
            if (secondResult is MoneyAggregateResult.Unavailable) return secondResult
            if (firstResult is MoneyAggregateResult.Unavailable) return firstResult
            availableAmount(secondResult) - availableAmount(firstResult)
        }
        if (!velocity.isFinite()) {
            return MoneyAggregateResult.Unavailable(
                MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT.name,
                totalResult.aggregate.requestedRateBasis,
                totalResult.aggregate.metadata
            )
        }
        return MoneyAggregateResult.Available(totalResult.aggregate.copy(
            displayAmount = velocity,
            sourceBuckets = listOf(MoneyBucket(totalResult.aggregate.displayCurrency, velocity, expenses.size))
        ))
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
        historicalExpenses: List<ExpenseSnapshot>,
        displayCurrency: String,
        moneyContext: MoneySliceContext?
    ): PriceTrendResult {
        if (historicalExpenses.size < 2) {
            return PriceTrendResult(MerchantPriceTrend.INSUFFICIENT_DATA, null, null, null)
        }
        
        val sorted = historicalExpenses.sortedBy { it.date }
        val first = normalizedValue(sorted.first(), displayCurrency, moneyContext)
        val last = normalizedValue(sorted.last(), displayCurrency, moneyContext)
        val change = if (first > 0) ((last - first) / first * 100).toFloat() else null
        
        // Collinear points (all same amount): treat as STABLE (LOW bug fix)
        val allSame = sorted.all { kotlin.math.abs(normalizedValue(it, displayCurrency, moneyContext) - first) < 0.001 }
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
        val currency = currencySettingsRepository.homeCurrency().first()
        return CurrencyCode.parse(currency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }?.code
            ?: throw com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException(
                MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE
            )
    }

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
    
    private fun predictNextVisit(dates: List<Long>, avgDaysBetween: Double?): Long? {
        if (dates.isEmpty() || avgDaysBetween == null || avgDaysBetween <= 0) return null
        
        val lastVisit = dates.max()
        return lastVisit + (avgDaysBetween * TimePeriodUtils.DAY_IN_MILLIS).toLong()
    }
    
    private fun detectSpendingPatterns(
        purchases: List<ExpenseSnapshot>,
        dayTotals: DoubleArray,
        timeSlotStats: Map<TimeSlot, Double>,
        totalSpent: Double,
        displayCurrency: String,
        moneyContext: MoneySliceContext?
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
        val amounts = purchases.map { normalizedValue(it, displayCurrency, moneyContext) }
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
        purchases: List<ExpenseSnapshot>,
        binCount: Int = 10,
        displayCurrency: String,
        moneyContext: MoneySliceContext? = null
    ): List<HistogramBin> {
        if (purchases.isEmpty()) return emptyList()
        val values = purchases.map { normalizedValue(it, displayCurrency, moneyContext) }
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 0.0
        if (min == max) {
            val result = aggregateMoney(purchases, displayCurrency, moneyContext)
            if (result is MoneyAggregateResult.Unavailable) return emptyList()
            return listOf(HistogramBin(
                rangeStart = min, 
                rangeEnd = max, 
                count = values.size, 
                percentage = 100f,
                displayCurrency = displayCurrency,
                moneyResult = result
            ))
        }
        
        val range = max - min
        val binWidth = range / binCount
        
        val grouped = Array(binCount) { mutableListOf<ExpenseSnapshot>() }
        for (purchase in purchases) {
            val binIndex = ((normalizedValue(purchase, displayCurrency, moneyContext) - min) / binWidth).toInt()
            val index = binIndex.coerceIn(0, binCount - 1)
            grouped[index].add(purchase)
        }
        
        // Create bin objects
        val totalCount = values.size.toFloat()
        val results = grouped.map { transactions ->
            aggregateMoney(transactions, displayCurrency, moneyContext)
        }
        if (results.any { it is MoneyAggregateResult.Unavailable }) return emptyList()
        return grouped.mapIndexed { index, transactions ->
            val count = transactions.size
            val result = results[index]
            val binStart = min + (index * binWidth)
            val binEnd = min + ((index + 1) * binWidth)
            HistogramBin(
                rangeStart = binStart,
                rangeEnd = binEnd,
                count = count,
                percentage = if (totalCount > 0) (count / totalCount) * 100f else 0f,
                displayCurrency = displayCurrency,
                moneyResult = result
            )
        }
    }
    
    private data class MoneySliceContext(
        val input: NormalizedAnalyticsInput,
        val includedById: Map<Long, NormalizedExpense>
    ) {
        fun scopedInput(): NormalizedAnalyticsInput = input.copy(
            excludedExpenses = input.excludedExpenses.filter {
                it.date == null || input.period?.contains(it.date) != false
            }
        )
    }

    private fun aggregateMoney(
        expenses: List<ExpenseSnapshot>,
        displayCurrency: String,
        moneyContext: MoneySliceContext?,
        includeExcluded: ((ExcludedExpense) -> Boolean)? = null
    ): MoneyAggregateResult {
        if (moneyContext == null) {
            val normalized = try {
                SingleCurrencyAnalyticsInput.from(expenses, displayCurrency)
            } catch (_: Exception) {
                return MoneyAggregateResult.Unavailable(
                    MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY.name,
                    RateBasis.IDENTITY,
                    MoneyAggregateMetadata(includedTransactionCount = expenses.size, invalidCurrencyCount = expenses.size)
                )
            }
            return NormalizedAnalyticsMoneyAdapter.aggregateUnattributedSlice(
                normalized,
                normalized.includedExpenses,
                displayCurrency = displayCurrency
            )
        }
        val rows = expenses.mapNotNull { moneyContext.includedById[it.id] }
        if (rows.size != expenses.size) {
            return MoneyAggregateResult.Unavailable(
                MoneyDisplayUnavailableReasonCode.INVALID_TRANSACTION_COUNTS.name,
                RateBasis.TRANSACTION_DATE,
                MoneyAggregateMetadata(includedTransactionCount = rows.size)
            )
        }
        val input = moneyContext.scopedInput()
        return if (includeExcluded == null) {
            NormalizedAnalyticsMoneyAdapter.aggregateUnattributedSlice(input, rows, displayCurrency = displayCurrency)
        } else {
            NormalizedAnalyticsMoneyAdapter.aggregateSelectedSlice(input, rows, includeExcluded, displayCurrency = displayCurrency, exclusionAttribution = ExclusionAttribution.TEMPORAL)
        }
    }

    private fun normalizedValue(
        expense: ExpenseSnapshot,
        displayCurrency: String,
        moneyContext: MoneySliceContext?
    ): Double {
        val target = CurrencyCode.parse(displayCurrency)
            ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: throw com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException(
                MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY
            )
        val row = moneyContext?.includedById?.get(expense.id)
        val amount = if (moneyContext != null) {
            if (row == null || CurrencyCode.parse(moneyContext.input.homeCurrency) != target ||
                CurrencyCode.parse(row.normalizedCurrency) != target
            ) {
                throw com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException(
                    MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY
                )
            }
            row.normalizedAmount
        } else {
            if (CurrencyCode.parse(expense.currency) != target) {
                throw com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException(
                    MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY
                )
            }
            SingleCurrencyAnalyticsInput
                .from(listOf(expense), displayCurrency)
                .includedExpenses
                .single()
                .normalizedAmount
        }
        if (!amount.isFinite()) {
            throw com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException(
                MoneyDisplayUnavailableReasonCode.INVALID_AMOUNT
            )
        }
        return amount
    }

    private fun availableAmount(result: MoneyAggregateResult): Double = when (result) {
        is MoneyAggregateResult.Available -> result.aggregate.displayAmount
        is MoneyAggregateResult.Unavailable -> throwUnavailable(result)
    }

    private fun throwUnavailable(result: MoneyAggregateResult.Unavailable): Nothing {
        throw MoneyDisplayUnavailableException(
            MoneyDisplayUnavailableReasonCode.entries.firstOrNull { it.name == result.reason }
                ?: MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE
        )
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
