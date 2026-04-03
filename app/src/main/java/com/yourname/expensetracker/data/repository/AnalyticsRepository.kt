package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.domain.analytics.CategoryBreakdown
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.flow.Flow
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
    val changePercent: Float?,
    /** Daily spending totals for the current period, one entry per day. */
    val dailyHistory: List<Float>,
    /** Daily spending totals for the previous period, one entry per day. */
    val previousDailyHistory: List<Float>,
    val transactionCount: Int
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
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository
) {

    /**
     * getSpendingSummary - Returns a comprehensive summary of spending for the given period.
     * Includes current total, previous period total, percent change, and daily trend.
     */
    fun getSpendingSummary(start: Long, end: Long): Flow<SpendingSummary> {
        val periodLength = end - start
        val previousStart = start - periodLength
        val previousEnd = start

        return flow {
            val totalSpent = expenseDao.getTotalSpentBetween(start, end) ?: 0.0
            val previousTotal = expenseDao.getTotalSpentBetween(previousStart, previousEnd) ?: 0.0
            val transactionCount = expenseDao.getCountForPeriod(start, end)

            val days = TimePeriodUtils.daysBetween(start, end).coerceAtLeast(1)
            val prevDays = TimePeriodUtils.daysBetween(previousStart, previousEnd).coerceAtLeast(1)
            val dailyHistory = DoubleArray(days)
            val previousDailyHistory = DoubleArray(prevDays)
            val startOfDay = TimePeriodUtils.getStartOfDay(start)
            val prevStartOfDay = TimePeriodUtils.getStartOfDay(previousStart)

            expenseDao.getDailyTotalsForPeriod(start, end).forEach { daily ->
                val idx = TimePeriodUtils.daysBetween(startOfDay, daily.startDate)
                if (idx in 0 until days) dailyHistory[idx] = daily.total
            }
            expenseDao.getDailyTotalsForPeriod(previousStart, previousEnd).forEach { daily ->
                val idx = TimePeriodUtils.daysBetween(prevStartOfDay, daily.startDate)
                if (idx in 0 until prevDays) previousDailyHistory[idx] = daily.total
            }

            val changePercent = if (previousTotal > 0) {
                ((totalSpent - previousTotal) / previousTotal * 100).toFloat()
            } else null

            emit(
                SpendingSummary(
                    totalSpent = totalSpent,
                    previousTotalSpent = if (previousTotal > 0) previousTotal else null,
                    changePercent = changePercent,
                    dailyHistory = dailyHistory.map { it.toFloat() },
                    previousDailyHistory = previousDailyHistory.map { it.toFloat() },
                    transactionCount = transactionCount
                )
            )
        }
    }

    /**
     * getCategoryBreakdown - Returns a list of categories sorted by spending amount.
     */
    fun getCategoryBreakdown(start: Long, end: Long): Flow<List<CategoryBreakdown>> {
        return flow {
            val categories = categoryRepository.getAll()
            val categoryMap = categories.associateBy { it.id }

            val totals = expenseDao.getCategoryTotalsBetween(start, end)
            val totalSpent = totals.sumOf { it.total }

            emit(
                totals
                .mapNotNull { (catId, total, txCount) ->
                    val cat = categoryMap[catId] ?: return@mapNotNull null
                    CategoryBreakdown(
                        category = cat,
                        total = total,
                        count = txCount,
                        percentage = if (totalSpent > 0) (total / totalSpent * 100).toFloat() else 0f
                    )
                }
                .sortedByDescending { it.total }
            )
        }
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
