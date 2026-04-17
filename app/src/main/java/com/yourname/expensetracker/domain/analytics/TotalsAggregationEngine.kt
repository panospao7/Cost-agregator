package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.dao.CategoryTotalResult
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.model.CategoryBreakdown
import com.yourname.expensetracker.domain.model.CategoryInfo
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TotalsAggregationEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        // HIGH-01 FIX: Use DateTimeFormatter (thread-safe) instead of SimpleDateFormat
        private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        private val MONTH_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    }

    suspend fun getMonthlyTotals(year: Int): List<PeriodTotal> = withContext(ioDispatcher) {
        try {
            val (startMs, endMs) = getYearRange(year)
            val monthlyTotals = expenseRepository.getMonthlyTotalsForPeriod(startMs, endMs)
            val average = getAverageForPeriodType(PeriodType.MONTH, excludeCurrent = false)

            val totalsByKey = monthlyTotals.associateBy { it.monthKey }
            (1..12).map { month ->
                val monthStart = LocalDate.of(year, month, 1)
                    .atStartOfDay(systemZoneId())
                    .toInstant()
                    .toEpochMilli()
                val monthEnd = TimePeriodUtils.getEndOfMonth(monthStart)
                val periodKey = "%04d-%02d".format(year, month)
                val monthly = totalsByKey[periodKey]
                val total = monthly?.total ?: 0.0
                PeriodTotal(
                    periodLabel = MONTH_FORMAT.format(toLocalDate(monthStart)),
                    periodKey = periodKey,
                    totalAmount = total,
                    transactionCount = monthly?.txCount ?: 0,
                    periodType = PeriodType.MONTH,
                    startDateMs = monthly?.startDate ?: monthStart,
                    endDateMs = monthly?.endDate ?: monthEnd,
                    status = getPeriodStatus(total, average)
                )
            }
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting monthly totals for year $year")
            emptyList()
        }
    }

    suspend fun getWeeklyTotals(year: Int, month: Int): List<PeriodTotal> = withContext(ioDispatcher) {
        try {
            val (monthStartMs, monthEndMs) = getMonthRange(year, month)
            val weeklyTotals = expenseRepository.getWeeklyTotalsForPeriod(monthStartMs, monthEndMs)
            val average = getAverageForPeriodType(PeriodType.WEEK, excludeCurrent = false)

            // Include ALL weeks that touch this month (have at least one day in the month)
            // This ensures no expenses are lost at month boundaries
            val totalsByStart = weeklyTotals
                .filter { weekly -> weekly.startDate < monthEndMs && weekly.endDate > monthStartMs }
                .associateBy { TimePeriodUtils.getStartOfWeek(it.startDate) }

            generateWeekStarts(monthStartMs, monthEndMs).mapIndexed { index, weekStart ->
                val weekEnd = TimePeriodUtils.addDays(weekStart, 7)
                val weekly = totalsByStart[weekStart]
                // Check if this is a partial week (spans month boundary)
                val isPartialWeek = weekStart < monthStartMs || weekEnd > monthEndMs
                
                // Format label: W1, W2, etc. Partial weeks show date range
                val weekLabel = if (isPartialWeek) {
                    val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                    val startStr = dateFormat.format(toLocalDate(maxOf(weekStart, monthStartMs)))
                    val endStr = dateFormat.format(toLocalDate(TimePeriodUtils.addDays(minOf(weekEnd, monthEndMs), -1)))
                    "W${index + 1} ($startStr-$endStr)"
                } else {
                    "W${index + 1}"
                }
                val total = weekly?.total ?: 0.0
                
                PeriodTotal(
                    periodLabel = weekLabel,
                    periodKey = weekly?.weekKey ?: weekKey(weekStart),
                    totalAmount = total,
                    transactionCount = weekly?.txCount ?: 0,
                    periodType = PeriodType.WEEK,
                    startDateMs = weekStart,
                    endDateMs = weekEnd,
                    status = getPeriodStatus(total, average)
                )
            }
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting weekly totals for $year-$month")
            emptyList()
        }
    }

    suspend fun getDailyTotals(year: Int, weekOfYear: Int): List<PeriodTotal> = withContext(ioDispatcher) {
        try {
            val (startMs, endMs) = getWeekRange(year, weekOfYear)
            val dailyTotals = expenseRepository.getDailyTotalsWithDatesForPeriod(startMs, endMs)
            val average = getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)

            buildDailyPeriodTotals(startMs, endMs, dailyTotals, average)
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting daily totals for $year week $weekOfYear")
            emptyList()
        }
    }

    /**
     * Get daily totals for a specific date range.
     * Used for drill-down to prevent duplicate days from week boundary mismatches.
     */
    suspend fun getDailyTotalsForRange(startMs: Long, endMs: Long): List<PeriodTotal> = withContext(ioDispatcher) {
        try {
            val dailyTotals = expenseRepository.getDailyTotalsWithDatesForPeriod(startMs, endMs)
            val average = getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)

            buildDailyPeriodTotals(startMs, endMs, dailyTotals, average)
        } catch (e: Exception) {
            Timber.tag("TotalsAggregationEngine").e(e, "Error getting daily totals for range $startMs to $endMs")
            emptyList()
        }
    }

    /**
     * Returns yearly spending totals for the last 5 years.
     *
     * Both the total amount and transaction count reflect **purchase-only**
     * data: the underlying repository methods ([ExpenseRepository.getTotalForPeriod]
     * and [ExpenseRepository.getTransactionCountForPeriod]) use the DAO's
     * `SPENDING_TYPE_SQL` filter, which excludes deposits, transfers,
     * withdrawals, and non-owned transactions.
     */
    suspend fun getYearlyTotals(): List<PeriodTotal> = withContext(ioDispatcher) {
        try {
            val now = timeProvider.now()
            val currentYear = TimePeriodUtils.getYear(now)
            
            // Get data for last 5 years
            val years = (currentYear - 4..currentYear).toList()
            val average = getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = true)
            
            years.map { year ->
                val (startMs, endMs) = getYearRange(year)
                // Both calls return purchase-only data (SPENDING_TYPE_SQL at DAO layer)
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

    suspend fun getCategoryBreakdown(startMs: Long, endMs: Long, periodLabel: String): List<CategoryBreakdown> = withContext(ioDispatcher) {
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
                    result.total / grandTotal * 100
                } else {
                    0.0
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

    suspend fun getAverageForPeriodType(periodType: PeriodType, excludeCurrent: Boolean): Double = withContext(ioDispatcher) {
        try {
            val now = timeProvider.now()

            when (periodType) {
                PeriodType.YEAR -> {
                    val currentYear = TimePeriodUtils.getYear(now)
                    val endYear = if (excludeCurrent) currentYear - 1 else currentYear
                    val yearTotals = (currentYear - 4..endYear).mapNotNull { year ->
                        val (startMs, endMs) = getYearRange(year)
                        val total = expenseRepository.getTotalForPeriod(startMs, endMs)
                        if (total > 0) total else null
                    }
                    yearTotals.takeIf { it.isNotEmpty() }?.average() ?: 0.0
                }
                PeriodType.MONTH -> {
                    val startMs = TimePeriodUtils.getStartOfMonth(TimePeriodUtils.addMonths(now, -12))
                    val months = expenseRepository.getMonthlyTotalsForPeriod(startMs, now)
                    if (excludeCurrent) {
                        months.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    } else {
                        months.map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    }
                }
                PeriodType.WEEK -> {
                    val startMs = TimePeriodUtils.getStartOfWeek(TimePeriodUtils.addDays(now, -56))
                    val weeks = expenseRepository.getWeeklyTotalsForPeriod(startMs, now)
                    if (excludeCurrent) {
                        weeks.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    } else {
                        weeks.map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
                    }
                }
                PeriodType.DAY -> {
                    val startMs = TimePeriodUtils.getStartOfDay(TimePeriodUtils.addDays(now, -30))
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

    private fun formatPeriodLabel(type: PeriodType, date: java.time.LocalDate): String {
        return when (type) {
            PeriodType.YEAR -> date.year.toString()
            PeriodType.MONTH -> MONTH_FORMAT.format(date)
            PeriodType.WEEK -> MONTH_YEAR_FORMAT.format(date)
            PeriodType.DAY -> DAY_FORMAT.format(date)
        }
    }

    private fun getYearRange(year: Int): Pair<Long, Long> {
        val jan1 = java.time.LocalDate.of(year, 1, 1)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return TimePeriodUtils.getYearRange(jan1)
    }

    private fun getMonthRange(year: Int, month: Int): Pair<Long, Long> {
        val monthStart = java.time.LocalDate.of(year, month, 1)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return TimePeriodUtils.getMonthRange(monthStart)
    }

    private fun getWeekRange(year: Int, weekOfYear: Int): Pair<Long, Long> {
        val jan4 = java.time.LocalDate.of(year, 1, 4)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val weekOneStart = TimePeriodUtils.getStartOfWeek(jan4)
        val weekStart = TimePeriodUtils.addDays(weekOneStart, (weekOfYear - 1) * 7)
        return weekStart to TimePeriodUtils.addDays(weekStart, 7)
    }

    private fun buildDailyPeriodTotals(
        startMs: Long,
        endMs: Long,
        dailyTotals: List<com.yourname.expensetracker.data.database.dao.DailyTotal>,
        average: Double
    ): List<PeriodTotal> {
        val totalsByStart = dailyTotals.associateBy { TimePeriodUtils.getStartOfDay(it.startDate) }
        return generateDayStarts(startMs, endMs).map { dayStart ->
            val dayEnd = TimePeriodUtils.getEndOfDay(dayStart)
            val daily = totalsByStart[dayStart]
            val total = daily?.total ?: 0.0
            val date = toLocalDate(dayStart)
            PeriodTotal(
                periodLabel = DAY_FORMAT.format(date),
                periodKey = dayKey(dayStart),
                totalAmount = total,
                transactionCount = daily?.txCount ?: 0,
                periodType = PeriodType.DAY,
                startDateMs = daily?.startDate ?: dayStart,
                endDateMs = daily?.endDate ?: dayEnd,
                status = getPeriodStatus(total, average)
            )
        }
    }

    private fun generateWeekStarts(monthStartMs: Long, monthEndMs: Long): List<Long> {
        val starts = mutableListOf<Long>()
        var cursor = TimePeriodUtils.getStartOfWeek(monthStartMs)
        while (cursor < monthEndMs) {
            starts.add(cursor)
            cursor = TimePeriodUtils.addDays(cursor, 7)
        }
        return starts
    }

    private fun generateDayStarts(startMs: Long, endMs: Long): List<Long> {
        val starts = mutableListOf<Long>()
        var cursor = TimePeriodUtils.getStartOfDay(startMs)
        val endDayStart = TimePeriodUtils.getStartOfDay(endMs)
        val normalizedEnd = if (endMs > endDayStart) {
            TimePeriodUtils.addDays(endDayStart, 1)
        } else {
            endDayStart
        }
        while (cursor < normalizedEnd) {
            starts.add(cursor)
            cursor = TimePeriodUtils.addDays(cursor, 1)
        }
        return starts
    }

    private fun weekKey(weekStart: Long): String {
        val year = TimePeriodUtils.getYear(weekStart)
        val week = TimePeriodUtils.getWeekOfYear(weekStart)
        return "%04d-W%d".format(year, week)
    }

    private fun dayKey(dayStart: Long): String {
        val date = toLocalDate(dayStart)
        return "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)
    }

    private fun toLocalDate(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(systemZoneId()).toLocalDate()

    private fun systemZoneId(): ZoneId = ZoneId.systemDefault()
}
