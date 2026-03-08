package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.CategoryBreakdown
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.data.database.entity.Category

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

        return combine(
            expenseDao.getExpensesByTypeBetweenFlow(start, end, TransactionType.PURCHASE.name),
            expenseDao.getExpensesByTypeBetweenFlow(previousStart, previousEnd, TransactionType.PURCHASE.name)
        ) { currentPurchases, previousPurchases ->
            
            val totalSpent = currentPurchases.sumOf { it.effectiveAmount }
            val previousTotal = previousPurchases.sumOf { it.effectiveAmount }
            
            val changePercent = if (previousTotal > 0) {
                ((totalSpent - previousTotal) / previousTotal * 100).toFloat()
            } else null

            // Generate Daily History (Trend)
            // Determine number of days to plot
            val days = ((end - start) / 86400000L).toInt().coerceAtLeast(1)
            val dailyHistory = DoubleArray(days)
            
            val startOfDay = TimePeriodUtils.getStartOfDay(start)
            
            currentPurchases.forEach { expense ->
                val dayIndex = ((expense.date - startOfDay) / 86400000L).toInt()
                if (dayIndex in 0 until days) {
                    dailyHistory[dayIndex] += expense.effectiveAmount
                }
            }
            
            // Previous History
            val prevDays = ((previousEnd - previousStart) / 86400000L).toInt().coerceAtLeast(1)
            val previousDailyHistory = DoubleArray(prevDays)
            val prevStartOfDay = TimePeriodUtils.getStartOfDay(previousStart)
            
            previousPurchases.forEach { expense ->
                val dayIndex = ((expense.date - prevStartOfDay) / 86400000L).toInt()
                if (dayIndex in 0 until prevDays) {
                    previousDailyHistory[dayIndex] += expense.effectiveAmount
                }
            }
            
            // Convert to cumulative or just daily? 
            // SpendingTrendChart usually expects cumulative for "pace" or daily for "bars". 
            // Existing HomeViewModel uses cumulative. Existing AnalyticsViewModel uses daily totals.
            // Let's return Daily Totals here, UI can accumulate if needed.
            
            SpendingSummary(
                totalSpent = totalSpent,
                previousTotalSpent = if (previousTotal > 0) previousTotal else null,
                changePercent = changePercent,
                dailyHistory = dailyHistory.map { it.toFloat() },
                previousDailyHistory = previousDailyHistory.map { it.toFloat() },
                transactionCount = currentPurchases.size
            )
        }
    }

    /**
     * getCategoryBreakdown - Returns a list of categories sorted by spending amount.
     */
    fun getCategoryBreakdown(start: Long, end: Long): Flow<List<CategoryBreakdown>> {
        return combine(
             expenseDao.getExpensesByTypeBetweenFlow(start, end, TransactionType.PURCHASE.name),
             categoryRepository.allCategories
        ) { purchases, categories ->
            val totalSpent = purchases.sumOf { it.effectiveAmount }
            val categoryMap = categories.associateBy { it.id }
            
            purchases.groupBy { it.categoryId }
                .mapNotNull { (catId, exps) ->
                    val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                    val catTotal = exps.sumOf { it.effectiveAmount }
                    
                    CategoryBreakdown(
                        category = cat,
                        total = catTotal,
                        count = exps.size,
                        percentage = if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f
                    )
                }
                .sortedByDescending { it.total }
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
