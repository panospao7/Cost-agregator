package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryBreakdown
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryRef
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.DataQualityReport
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.currency.CurrencyConverter
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
 *
 * A05: SpendingSummary now carries [aggregate] for multi-currency safety and
 * [isPartial] for conversion-failure awareness. DataQualityReport can be
 * derived from aggregate.conversionFailures when needed.
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
    val currency: String = "EUR",
    val aggregate: MoneyAggregate? = null,  // A05: full aggregate for multi-currency safety
    val isPartial: Boolean = false           // A05: whether any conversion failures exist
)

data class LocationSpendSummary(
    /** Top spending places sorted by total spend descending. */
    val topMerchants: List<LocationMerchantStat>,
    /** Total number of expenses with coordinates. */
    val locatedCount: Int,
    /** Total number of expenses without coordinates. */
    val unlocatedCount: Int
)

// A14: aggregate + currency fields added below on LocationMerchantStat.
data class LocationMerchantStat(
    val merchant: String,
    val totalSpend: Double,
    val transactionCount: Int,
    val aggregate: MoneyAggregate? = null,  // A14: per-merchant aggregate
    val currency: String? = null           // A14: currency from per-currency DAO
)

@Singleton
class AnalyticsRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencyConverter: CurrencyConverter
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

            // ── Fetch and normalize expenses per-transaction-date for accuracy ──
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

            // ── Headline totals derived from same normalized data as daily history ──
            val totalSpent = currentNormalization.normalizedExpenses.sumOf { it.normalizedEffectiveAmount }
            val previousTotal = previousNormalization.normalizedExpenses.sumOf { it.normalizedEffectiveAmount }
            val transactionCount = currentNormalization.normalizedExpenses.size

            val isPartial = currentNormalization.hasWarnings
            val warningMessage = currentNormalization.warnings.firstOrNull()?.message

            // ── Daily history from the same normalized expenses ─────────────
            val days = TimePeriodUtils.daysBetween(start, end).coerceAtLeast(1)
            val prevDays = TimePeriodUtils.daysBetween(previousStart, previousEnd).coerceAtLeast(1)
            val startOfDay = TimePeriodUtils.getStartOfDay(start)
            val prevStartOfDay = TimePeriodUtils.getStartOfDay(previousStart)

            val dailyHistory = DoubleArray(days)
            val previousDailyHistory = DoubleArray(prevDays)

            currentNormalization.normalizedExpenses.forEach { norm ->
                val dayStart = TimePeriodUtils.getStartOfDay(norm.snapshot.date)
                val idx = TimePeriodUtils.daysBetween(startOfDay, dayStart)
                if (idx in 0 until days) dailyHistory[idx] += norm.normalizedEffectiveAmount
            }

            previousNormalization.normalizedExpenses.forEach { norm ->
                val dayStart = TimePeriodUtils.getStartOfDay(norm.snapshot.date)
                val idx = TimePeriodUtils.daysBetween(prevStartOfDay, dayStart)
                if (idx in 0 until prevDays) previousDailyHistory[idx] += norm.normalizedEffectiveAmount
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
                currency = homeCurrency,
                aggregate = null,
                isPartial = isPartial
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
     *
     * TODO (A10): Budget snapshots from BudgetRepository.getActiveBudgetSnapshots()
     * use convert() (current exchange rate) which may differ from the historical
     * conversion used by MultiCurrencyRepository. Normalize budget snapshots using
     * the same period-appropriate conversion as the spend data before comparison.
     */
    fun getCategoryBreakdown(start: Long, end: Long): Flow<List<AnalyticsCategoryBreakdown>> {
        return flow {
            val categories = categoryRepository.getAll()
            val categoryMap = categories.associateBy { it.id }
            val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }.getOrDefault("EUR")

            // ── Normalized per-category totals via MultiCurrencyRepository ──
            // TODO (P5-CURRENT-007): Category breakdown uses latest-rate via
            // MultiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals, but the
            // spending summary uses as-of-transaction-date rates via NormalizedAnalyticsInput.
            // This FX basis mismatch means category percentages may not sum to the summary total.
            // Fix: Use NormalizedAnalyticsInput for both category breakdown and summary.
            val categoryAggregates = multiCurrencyRepository.getHomeCurrencyPurchaseCategoryTotals(start, end)
            // TODO (P2-11): totalSpent sums only displayAmount, ignoring partial aggregates.
            // If a category aggregate is partial (missing exchange rates), its percentage
            // is calculated over only successfully converted amounts. The breakdown should
            // carry isPartial and warningMessage fields so the UI can show caveats.

            // TODO (P5-PR2): Downstream consumers of category breakdown (e.g. analytics
            // ViewModel, dashboard widgets) should also propagate isPartial and warningMessage
            // fields from SpendingSummary so that users see data-quality warnings consistently
            // across all surfaces. Currently these fields may be dropped at the ViewModel layer.
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
                        displayCurrency = homeCurrency,
                        isPartial = aggregate.isPartial,
                        warningMessage = aggregate.warningMessage
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
     *
     * RESOLVED (A11): confidencePenalty is now computed in ForecastInputAssembler
     * and propagated via ForecastDataQuality. DataQualityReport.conversionConfidence
     * remains available for UI surfaces but downstream engines consume
     * ForecastDataQuality.confidencePenalty for confidence scoring.
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
     * A14: Now uses per-currency DAO and builds MoneyAggregate per merchant
     * for multi-currency safety.
     */
    suspend fun getLocationSpendSummary(): LocationSpendSummary {
        val locatedCount = expenseDao.countLocated()
        val unlocatedCount = expenseDao.countUnlocated()
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }
            .getOrDefault(MultiCurrencyRepository.DEFAULT_HOME_CURRENCY)

        // A14: Use per-currency DAO instead of deprecated raw-sum version
        val merchantCurrencyTotals = expenseDao.getLocatedMerchantTotalsByCurrency()

        // Group per-currency rows by merchant and build LocationMerchantStat
        val grouped = merchantCurrencyTotals.groupBy { it.merchant }
        val topMerchants = grouped.entries.take(20).map { (merchant, rows) ->
            val buckets = rows.map { Pair(it.total, it.currency) }
            val counts = rows.map { it.txCount }
            val aggregate = MoneyAggregateBuilder.fromBuckets(buckets, homeCurrency, currencyConverter, counts)

            LocationMerchantStat(
                merchant = merchant,
                totalSpend = aggregate.displayAmount,
                transactionCount = aggregate.totalTransactionCount,
                aggregate = aggregate,
                currency = homeCurrency
            )
        }

        return LocationSpendSummary(
            topMerchants = topMerchants,
            locatedCount = locatedCount,
            unlocatedCount = unlocatedCount
        )
    }
}
