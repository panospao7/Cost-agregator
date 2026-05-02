package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryBreakdown
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryRef
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.DataQualityReport
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Summary of spending for a given time period, used by the analytics screen
 * and the dashboard to display trends and comparisons.
 */
data class SpendingSummary(
    val totalSpent: Double,
    val previousTotalSpent: Double?,
    val changePercent: Double?,
    /** Daily spending totals for the current period, one entry per day. */
    val dailyHistory: List<Double>,
    /** Daily spending totals for the previous period, one entry per day. */
    val previousDailyHistory: List<Double>,
    val transactionCount: Int,
    val currency: String = "EUR"
)

data class LocationSpendSummary(
    /** Top spending places sorted by total spend descending. */
    val topMerchants: List<LocationMerchantStat>,
    /** Total number of expenses with coordinates. */
    val locatedCount: Int,
    /** Total number of expenses without coordinates. */
    val unlocatedCount: Int
)

data class LocationMerchantStat(
    val merchant: String,
    val totalSpend: Double,
    val transactionCount: Int
)

@Singleton
class AnalyticsRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer
) {

    /**
     * getSpendingSummary - Returns a comprehensive summary of spending for the given period.
     * Includes current total, previous period total, percent change, and daily trend.
     * Uses MultiCurrencyRepository for currency-converted totals and
     * AnalyticsCurrencyNormalizer for daily-history granularity.
     */
    fun getSpendingSummary(start: Long, end: Long): Flow<SpendingSummary> {
        val daysInPeriod = TimePeriodUtils.daysBetween(start, end).coerceAtLeast(1)
        val previousStart = TimePeriodUtils.addDays(start, -daysInPeriod)
        val previousEnd = start

        return flow {
            val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }.getOrDefault("EUR")

            // ── Totals via MultiCurrencyRepository (currency-converted) ──────
            val currentAggregate = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(start, end)
            val previousAggregate = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(previousStart, previousEnd)

            val totalSpent = currentAggregate.displayAmount
            val previousTotal = previousAggregate.displayAmount
            val transactionCount = currentAggregate.totalTransactionCount

            // ── Daily history computed from normalized expenses ─────────────
            val days = TimePeriodUtils.daysBetween(start, end).coerceAtLeast(1)
            val prevDays = TimePeriodUtils.daysBetween(previousStart, previousEnd).coerceAtLeast(1)
            val startOfDay = TimePeriodUtils.getStartOfDay(start)
            val prevStartOfDay = TimePeriodUtils.getStartOfDay(previousStart)

            val dailyHistory = DoubleArray(days)
            val previousDailyHistory = DoubleArray(prevDays)

            val currentExpenses = expenseDao.getExpensesByTypeBetween(
                start, end, ExpenseDao.SPENDING_TYPE
            )
            val previousExpenses = expenseDao.getExpensesByTypeBetween(
                previousStart, previousEnd, ExpenseDao.SPENDING_TYPE
            )

            val currentNormalization = analyticsCurrencyNormalizer.normalizeExpenses(
                currentExpenses, homeCurrency
            )
            val previousNormalization = analyticsCurrencyNormalizer.normalizeExpenses(
                previousExpenses, homeCurrency
            )

            currentNormalization.includedExpenses.forEach { snapshot ->
                val dayStart = TimePeriodUtils.getStartOfDay(snapshot.date)
                val idx = TimePeriodUtils.daysBetween(startOfDay, dayStart)
                if (idx in 0 until days) dailyHistory[idx] += snapshot.effectiveAmount
            }

            previousNormalization.includedExpenses.forEach { snapshot ->
                val dayStart = TimePeriodUtils.getStartOfDay(snapshot.date)
                val idx = TimePeriodUtils.daysBetween(prevStartOfDay, dayStart)
                if (idx in 0 until prevDays) previousDailyHistory[idx] += snapshot.effectiveAmount
            }

            val changePercent = if (previousTotal > 0) {
                (totalSpent - previousTotal) / previousTotal * 100
            } else null

            emit(
            SpendingSummary(
                totalSpent = totalSpent,
                previousTotalSpent = if (previousTotal > 0) previousTotal else null,
                changePercent = changePercent,
                dailyHistory = dailyHistory.toList(),
                previousDailyHistory = previousDailyHistory.toList(),
                transactionCount = transactionCount,
                currency = homeCurrency
            )
            )
        }
    }

    /**
     * getCategoryBreakdown - Returns a list of categories sorted by spending amount.
     * Uses MultiCurrencyRepository for currency-converted category totals.
     *
     * Uncategorized expenses (where categoryId is null) are included as an
     * "Uncategorized" pseudo-category so that the sum of all breakdown entries
     * equals the parent total for the period.
     */
    fun getCategoryBreakdown(start: Long, end: Long): Flow<List<AnalyticsCategoryBreakdown>> {
        return flow {
            val categories = categoryRepository.getAll()
            val categoryMap = categories.associateBy { it.id }
            val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }.getOrDefault("EUR")

            // ── Normalized per-category totals via MultiCurrencyRepository ──
            val categoryAggregates = multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(start, end)
            val totalSpent = categoryAggregates.values.sumOf { it.displayAmount }

            emit(
                categoryAggregates
                .mapNotNull { (categoryId, aggregate) ->
                    val cat = if (categoryId != null) {
                        categoryMap[categoryId]
                    } else {
                        // Include null-category (uncategorized) expenses
                        null
                    }
                    if (categoryId != null && cat == null) return@mapNotNull null
                    val ref = if (cat != null) {
                        AnalyticsCategoryRef(
                            id = cat.id,
                            name = cat.name,
                            icon = cat.icon,
                            color = cat.color
                        )
                    } else {
                        // Pseudo-category for uncategorized expenses
                        AnalyticsCategoryRef(
                            id = 0L,
                            name = "Uncategorized",
                            icon = "?",
                            color = "#808080"
                        )
                    }
                    AnalyticsCategoryBreakdown(
                        category = ref,
                        total = aggregate.displayAmount,
                        count = aggregate.totalTransactionCount,
                        percentage = if (totalSpent > 0) (aggregate.displayAmount / totalSpent * 100).toFloat() else 0f,
                        displayCurrency = homeCurrency
                    )
                }
                .sortedByDescending { it.total }
            )
        }
    }

    // ── Data quality reporting ────────────────────────────────────────────────

    /**
     * Returns a [DataQualityReport] for the given time range, computing
     * conversion confidence and metadata completeness from the normalizer.
     *
     * Consumers (UI, forecast engines, health score) can use this to assess
     * how reliable analytics outputs are for the period.
     */
    suspend fun getDataQualityReport(start: Long, end: Long): DataQualityReport {
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault("EUR")
        val expenses = expenseDao.getExpensesByTypeBetween(
            start, end, ExpenseDao.SPENDING_TYPE
        )
        val normalization = analyticsCurrencyNormalizer.normalizeExpenses(
            expenses, homeCurrency
        )
        val totalWithCurrency = expenses.count { e ->
            e.currency.length == 3 && e.currency.all { it.isLetter() }
        }
        val totalWithMerchant = expenses.count { it.merchant.isNotBlank() }
        val totalWithCategory = expenses.count { it.categoryId != null }

        return DataQualityReport.fromNormalization(
            normalization = normalization,
            totalWithCurrency = totalWithCurrency,
            totalWithMerchant = totalWithMerchant,
            totalWithCategory = totalWithCategory
        )
    }

    // ── Location-aware analytics (v28) ────────────────────────────────────────

    /**
     * Returns a summary of spending grouped by located vs un-located expenses,
     * and the top merchants that have been geocoded.
     */
    suspend fun getLocationSpendSummary(): LocationSpendSummary {
        val merchantTotals = expenseDao.getLocatedMerchantTotals()
        val locatedCount = expenseDao.countLocated()
        val unlocatedCount = expenseDao.countUnlocated()

        val topMerchants = merchantTotals.take(20).map { mt ->
            LocationMerchantStat(
                merchant = mt.merchant,
                totalSpend = mt.total,
                transactionCount = mt.cnt
            )
        }

        return LocationSpendSummary(
            topMerchants = topMerchants,
            locatedCount = locatedCount,
            unlocatedCount = unlocatedCount
        )
    }
}
