package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.CategoryTotalResult
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.model.CategoryBreakdown
import com.yourname.expensetracker.domain.model.CategoryInfo
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TotalsAggregationEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        private val MONTH_FORMAT = SimpleDateFormat("MMM", Locale.getDefault())
        private val DAY_FORMAT = SimpleDateFormat("EEE", Locale.getDefault())
        private val MONTH_YEAR_FORMAT = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    }

    suspend fun getMonthlyTotals(year: Int): List<PeriodTotal> = withContext(Dispatchers.IO) {
        try {
            val (startMs, endMs) = getYearRange(year)
            val monthlyTotals = expenseRepository.getMonthlyTotalsForPeriod(startMs, endMs)
            val average = getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)

            monthlyTotals.map { monthly ->
                val date = Date(monthly.startDate)
                PeriodTotal(
                    periodLabel = MONTH_FORMAT.format(date),
                    periodKey = monthly.monthKey,
                    totalAmount = monthly.total,
                    transactionCount = monthly.txCount,
                    periodType = PeriodType.MONTH,
                    startDateMs = monthly.startDate,
                    endDateMs = monthly.endDate,
                    status = getPeriodStatus(monthly.total, average)
                )
            }
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting monthly totals for year $year")
            emptyList()
        }
    }

    suspend fun getWeeklyTotals(year: Int, month: Int): List<PeriodTotal> = withContext(Dispatchers.IO) {
        try {
            val (monthStartMs, monthEndMs) = getMonthRange(year, month)
            val weeklyTotals = expenseRepository.getWeeklyTotalsForPeriod(monthStartMs, monthEndMs)
            val average = getAverageForPeriodType(PeriodType.WEEK, excludeCurrent = false)

            // Include ALL weeks that touch this month (have at least one day in the month)
            // This ensures no expenses are lost at month boundaries
            val monthWeeks = weeklyTotals.filter { weekly ->
                // Week touches month if: weekStart < monthEnd AND weekEnd > monthStart
                weekly.startDate < monthEndMs && weekly.endDate > monthStartMs
            }

            monthWeeks.mapIndexed { index, weekly ->
                // Check if this is a partial week (spans month boundary)
                val weekStartMonday = TimePeriodUtils.getStartOfWeek(weekly.startDate)
                val isPartialWeek = weekStartMonday < monthStartMs || weekly.endDate > monthEndMs
                
                // Format label: W1, W2, etc. Partial weeks show date range
                val weekLabel = if (isPartialWeek) {
                    val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())
                    val startStr = dateFormat.format(Date(maxOf(weekly.startDate, monthStartMs)))
                    val endStr = dateFormat.format(Date(minOf(weekly.endDate, monthEndMs - 1)))
                    "W${index + 1} ($startStr-$endStr)"
                } else {
                    "W${index + 1}"
                }
                
                PeriodTotal(
                    periodLabel = weekLabel,
                    periodKey = weekly.weekKey,
                    totalAmount = weekly.total,
                    transactionCount = weekly.txCount,
                    periodType = PeriodType.WEEK,
                    startDateMs = weekly.startDate,
                    endDateMs = weekly.endDate,
                    status = getPeriodStatus(weekly.total, average)
                )
            }
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting weekly totals for $year-$month")
            emptyList()
        }
    }

    suspend fun getDailyTotals(year: Int, weekOfYear: Int): List<PeriodTotal> = withContext(Dispatchers.IO) {
        try {
            val (startMs, endMs) = getWeekRange(year, weekOfYear)
            val dailyTotals = expenseRepository.getDailyTotalsWithDatesForPeriod(startMs, endMs)
            val average = getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)

            dailyTotals.map { daily ->
                val date = Date(daily.startDate)
                PeriodTotal(
                    periodLabel = DAY_FORMAT.format(date),
                    periodKey = daily.dayEpoch.toString(),
                    totalAmount = daily.total,
                    transactionCount = daily.txCount,
                    periodType = PeriodType.DAY,
                    startDateMs = daily.startDate,
                    endDateMs = daily.endDate,
                    status = getPeriodStatus(daily.total, average)
                )
            }
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting daily totals for $year week $weekOfYear")
            emptyList()
        }
    }

    suspend fun getYearlyTotals(): List<PeriodTotal> = withContext(Dispatchers.IO) {
        try {
            val now = timeProvider.now()
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val currentYear = cal.get(Calendar.YEAR)
            
            // Get data for last 5 years
            val years = (currentYear - 4..currentYear).toList()
            val average = getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = false)
            
            years.map { year ->
                val (startMs, endMs) = getYearRange(year)
                val total = expenseRepository.getTotalForPeriod(startMs, endMs)
                val count = expenseRepository.getTransactionCountForPeriod(startMs, endMs)
                
                PeriodTotal(
                    periodLabel = year.toString(),
                    periodKey = year.toString(),
                    totalAmount = total,
                    transactionCount = count,
                    periodType = PeriodType.YEAR,
                    startDateMs = startMs,
                    endDateMs = endMs,
                    status = getPeriodStatus(total, average)
                )
            }.filter { it.totalAmount > 0 || it.periodKey == currentYear.toString() }
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting yearly totals")
            emptyList()
        }
    }

    suspend fun getCategoryBreakdown(startMs: Long, endMs: Long, periodLabel: String): List<CategoryBreakdown> = withContext(Dispatchers.IO) {
        try {
            val categoryResults = expenseRepository.getCategoryBreakdown(startMs, endMs)
            val grandTotal = categoryResults.sumOf { it.total }

            categoryResults.mapNotNull { result ->
                if (result.id == null) return@mapNotNull null

                val category = CategoryInfo(
                    id = result.id,
                    name = result.name ?: "Unknown",
                    icon = result.icon ?: "?",
                    color = result.color ?: "#808080",
                    isIncome = false
                )

                val percentage = if (grandTotal > 0) {
                    (result.total / grandTotal * 100).toFloat()
                } else {
                    0f
                }

                CategoryBreakdown(
                    category = category,
                    totalAmount = result.total,
                    transactionCount = result.txCount,
                    percentageOfTotal = percentage,
                    periodLabel = periodLabel
                )
            }.sortedByDescending { it.totalAmount }
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting category breakdown for period $periodLabel")
            emptyList()
        }
    }

    suspend fun getAverageForPeriodType(periodType: PeriodType, excludeCurrent: Boolean): Double = withContext(Dispatchers.IO) {
        try {
            val now = timeProvider.now()
            val cal = Calendar.getInstance().apply { timeInMillis = now }

            when (periodType) {
                PeriodType.YEAR -> {
                    val currentYear = cal.get(Calendar.YEAR)
                    val (startMs, _) = getYearRange(currentYear)
                    val allMonths = expenseRepository.getMonthlyTotalsForPeriod(startMs, now)
                    if (excludeCurrent) {
                        allMonths.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    } else {
                        allMonths.map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    }
                }
                PeriodType.MONTH -> {
                    cal.add(Calendar.MONTH, -12)
                    val startMs = TimePeriodUtils.getStartOfMonth(cal.timeInMillis)
                    val months = expenseRepository.getMonthlyTotalsForPeriod(startMs, now)
                    if (excludeCurrent) {
                        months.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    } else {
                        months.map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    }
                }
                PeriodType.WEEK -> {
                    cal.add(Calendar.WEEK_OF_YEAR, -8)
                    val startMs = TimePeriodUtils.getStartOfWeek(cal.timeInMillis)
                    val weeks = expenseRepository.getWeeklyTotalsForPeriod(startMs, now)
                    if (excludeCurrent) {
                        weeks.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    } else {
                        weeks.map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    }
                }
                PeriodType.DAY -> {
                    cal.add(Calendar.DAY_OF_YEAR, -30)
                    val startMs = TimePeriodUtils.getStartOfDay(cal.timeInMillis)
                    val avgDaily = expenseRepository.getAverageDailySpend(startMs, now)
                    avgDaily ?: 0.0
                }
            }
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error calculating average for $periodType")
            0.0
        }
    }

    fun getPeriodStatus(total: Double, average: Double): PeriodStatus {
        return when {
            average <= 0 -> PeriodStatus.NO_DATA
            total < average -> PeriodStatus.UNDER_AVERAGE
            else -> PeriodStatus.OVER_AVERAGE
        }
    }

    private fun formatPeriodLabel(type: PeriodType, date: Date): String {
        return when (type) {
            PeriodType.YEAR -> SimpleDateFormat("yyyy", Locale.getDefault()).format(date)
            PeriodType.MONTH -> MONTH_FORMAT.format(date)
            PeriodType.WEEK -> MONTH_YEAR_FORMAT.format(date)
            PeriodType.DAY -> DAY_FORMAT.format(date)
        }
    }

    private fun getYearRange(year: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = cal.timeInMillis

        cal.set(Calendar.MONTH, Calendar.DECEMBER)
        cal.set(Calendar.DAY_OF_MONTH, 31)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endMs = cal.timeInMillis

        return startMs to endMs
    }

    private fun getMonthRange(year: Int, month: Int): Pair<Long, Long> {
        // month is 1-indexed (1=January, 12=December), Calendar.MONTH is 0-indexed
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)  // Convert 1-indexed to 0-indexed
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endMs = cal.timeInMillis

        return startMs to endMs
    }

    private fun getWeekRange(year: Int, weekOfYear: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.WEEK_OF_YEAR, weekOfYear)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = cal.timeInMillis

        cal.add(Calendar.DAY_OF_WEEK, 6)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endMs = cal.timeInMillis

        return startMs to endMs
    }
}
