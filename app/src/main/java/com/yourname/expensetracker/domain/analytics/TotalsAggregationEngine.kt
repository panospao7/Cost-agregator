package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.MonthMoneyAggregate
import com.yourname.expensetracker.di.IoDispatcher
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.model.CategoryBreakdown
import com.yourname.expensetracker.domain.model.CategoryInfo
import com.yourname.expensetracker.domain.model.PeriodStatus
import com.yourname.expensetracker.domain.model.PeriodTotal
import com.yourname.expensetracker.domain.model.PeriodType
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ## Multi-currency safety
 * All period aggregation paths (year/month/week/day/category) are currency-safe:
 * they delegate to [MultiCurrencyRepository] historical aggregation, which converts
 * each expense at its transaction-date rate and never raw-sums mixed currencies.
 *
 * ## DSH-10-FIXED: Analytics methods now return reactive Flows
 *
 * All public analytics methods now return `Flow<List<PeriodTotal>>` (or `Flow<List<CategoryBreakdown>>`)
 * so that the analytics UI automatically refreshes when underlying expense data changes.
 * The reactive trigger is [ExpenseRepository.getTotalSpent] — a `Flow<Double?>` that emits
 * whenever the expenses table is invalidated by Room.
 *
 * Internally, each method is wrapped in a `flow { }` builder that re-executes the aggregate
 * query on each trigger emission. This avoids changing the DAO layer's aggregate queries
 * (which remain one-shot) while still providing reactive updates to callers.
 *
 * ## I4 / P5-P1-01: All period methods use [MultiCurrencyRepository] historical aggregation
 * Every public period method uses per-expense TRANSACTION_DATE, PURCHASE-only conversion
 * so totals across granularities share one rate basis and exclude deposits/transfers:
 * - [getMonthlyTotals] — [MultiCurrencyRepository.getMonthlyAggregatesHistorical]
 * - [getYearlyTotals] — [MultiCurrencyRepository.getHomeCurrencyPurchaseTotalHistoricalResult]
 * - [getWeeklyTotals] — [MultiCurrencyRepository.getWeeklyAggregatesHistorical]
 * - [getDailyTotals] / [getDailyTotalsForRange] — [MultiCurrencyRepository.getDailyAggregatesHistorical]
 * - [getCategoryBreakdown] — [MultiCurrencyRepository.getCategoryAggregatesHistorical]
 * - [getAverageForPeriodType] uses the matching historical APIs above.
 *
 * Conversion partial-state ([MoneyAggregate.isPartial] / [MoneyAggregate.warningMessage])
 * is propagated onto every [PeriodTotal].
 */
@Singleton
class TotalsAggregationEngine @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider,
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val categoryRepository: CategoryRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        // HIGH-01 FIX: Use DateTimeFormatter (thread-safe) instead of SimpleDateFormat
        private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        private val MONTH_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    }

    /**
     * P5-P1-01 / P5-NEW-09 FIX: Uses [MultiCurrencyRepository.getMonthlyAggregatesHistorical]
     * (per-expense TRANSACTION_DATE, PURCHASE-only) so monthly totals share the same rate
     * basis as the weekly/daily drilldown, analytics summary, and yearly totals. The
     * aggregate's [MoneyAggregate.displayAmount] supplies the converted total,
     * [MoneyAggregate.totalTransactionCount] supplies the count, and
     * [MoneyAggregate.isPartial]/[MoneyAggregate.warningMessage] propagate conversion gaps.
     */
    fun getMonthlyTotals(year: Int): Flow<List<PeriodTotal>> = reactiveFlow {
        val (startMs, endMs) = getYearRange(year)
        // P5-NEW-09 / P5-P1-01 FIX: per-expense TRANSACTION_DATE PURCHASE-only totals,
        // same rate basis as weekly/daily drilldown and analytics summary.
        val monthlyTotals = multiCurrencyRepository.getMonthlyAggregatesHistorical(startMs, endMs)
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
            val total = monthly?.aggregate?.displayAmount ?: 0.0
            PeriodTotal(
                periodLabel = MONTH_FORMAT.format(toLocalDate(monthStart)),
                periodKey = periodKey,
                totalAmount = total,
                transactionCount = monthly?.aggregate?.totalTransactionCount ?: 0,
                periodType = PeriodType.MONTH,
                startDateMs = monthStart,
                endDateMs = monthEnd,
                status = getPeriodStatus(total, average),
                isPartial = monthly?.aggregate?.isPartial ?: false,
                warningMessage = monthly?.aggregate?.warningMessage
            )
        }
    }

    /**
     * DSH-2: Weekly drill-down now clips `startDateMs`/`endDateMs` to the
     * containing month boundaries instead of using the raw ISO week range,
     * which could extend into the previous or next month.
     *
     * DSH-3: When a week straddles a month boundary, the visible date range
     * is clipped to the current month so that daily drill-down does not show
     * days outside the month.
     */
    // P5-NEW-01 FIX: Uses MultiCurrencyRepository.getWeeklyAggregatesHistorical() —
    // PURCHASE-only, per-expense TRANSACTION_DATE conversion — so weekly drilldown
    // excludes deposits/transfers and uses the same rate basis as monthly/yearly.
    fun getWeeklyTotals(year: Int, month: Int): Flow<List<PeriodTotal>> = reactiveFlow {
        val (monthStartMs, monthEndMs) = getMonthRange(year, month)
        val weeklyAggregates = multiCurrencyRepository.getWeeklyAggregatesHistorical(monthStartMs, monthEndMs)
        val average = getAverageForPeriodType(PeriodType.WEEK, excludeCurrent = false)

        if (weeklyAggregates.isEmpty()) return@reactiveFlow emptyList()

        weeklyAggregates.mapIndexed { index, periodAgg ->
            val periodKey = periodAgg.periodKey
            val aggregate = periodAgg.aggregate
            val weekStart = TimePeriodUtils.parseWeekKeyToStart(periodKey) ?: monthStartMs
            val weekEnd = TimePeriodUtils.addDays(weekStart, 7)

            val clippedStart = maxOf(weekStart, monthStartMs)
            val clippedEnd = minOf(weekEnd, monthEndMs)
            val isPartialWeek = weekStart < monthStartMs || weekEnd > monthEndMs

            val weekLabel = if (isPartialWeek) {
                val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                val startStr = dateFormat.format(toLocalDate(clippedStart))
                val endStr = dateFormat.format(toLocalDate(TimePeriodUtils.addDays(clippedEnd, -1)))
                "W${index + 1} ($startStr-$endStr)"
            } else {
                "W${index + 1}"
            }

            PeriodTotal(
                periodLabel = weekLabel,
                periodKey = periodKey,
                totalAmount = aggregate.displayAmount,
                transactionCount = aggregate.totalTransactionCount,
                periodType = PeriodType.WEEK,
                startDateMs = clippedStart,
                endDateMs = clippedEnd,
                status = getPeriodStatus(aggregate.displayAmount, average),
                isPartial = aggregate.isPartial,
                warningMessage = aggregate.warningMessage
            )
        }
    }

    /**
     * DSH-3: Daily drill-down for a week is now clipped to the containing
     * month. When the ISO week overlaps into the previous or next month,
     * only days within the month are returned. This ensures weekly drill-down
     * does not show days that belong to a different month in the dashboard.
     */
    // P5-NEW-01 FIX: Uses MultiCurrencyRepository.getDailyAggregatesHistorical() —
    // PURCHASE-only, per-expense TRANSACTION_DATE conversion.
    fun getDailyTotals(year: Int, weekOfYear: Int): Flow<List<PeriodTotal>> = reactiveFlow {
        val (startMs, endMs) = getWeekRange(year, weekOfYear)

        // DSH-3: Determine the month that contains this week's Thursday
        // (ISO week rule) and clip to that month's boundaries.
        val monthStartMs = TimePeriodUtils.getStartOfMonth(startMs)
        val monthEndMs = TimePeriodUtils.getEndOfMonth(startMs)
        val clippedStart = maxOf(startMs, monthStartMs)
        val clippedEnd = minOf(endMs, monthEndMs)

        // Skip weeks that fall entirely outside any month boundary
        if (clippedStart >= clippedEnd) {
            emptyList()
        } else {
            val dailyAggregates = multiCurrencyRepository.getDailyAggregatesHistorical(clippedStart, clippedEnd)
            val average = getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)

            dailyAggregates.map { periodAgg ->
                val dayStart = TimePeriodUtils.parseDayKeyToStart(periodAgg.periodKey) ?: clippedStart
                val dayEnd = TimePeriodUtils.getEndOfDay(dayStart)
                val date = toLocalDate(dayStart)
                val aggregate = periodAgg.aggregate
                PeriodTotal(
                    periodLabel = DAY_FORMAT.format(date),
                    periodKey = periodAgg.periodKey,
                    totalAmount = aggregate.displayAmount,
                    transactionCount = aggregate.totalTransactionCount,
                    periodType = PeriodType.DAY,
                    startDateMs = dayStart,
                    endDateMs = dayEnd,
                    status = getPeriodStatus(aggregate.displayAmount, average),
                    isPartial = aggregate.isPartial,
                    warningMessage = aggregate.warningMessage
                )
            }
        }
    }

    // P5-NEW-01 FIX: Uses MultiCurrencyRepository.getDailyAggregatesHistorical() —
    // PURCHASE-only, per-expense TRANSACTION_DATE conversion.
    fun getDailyTotalsForRange(startMs: Long, endMs: Long): Flow<List<PeriodTotal>> = reactiveFlow {
        val dailyAggregates = multiCurrencyRepository.getDailyAggregatesHistorical(startMs, endMs)
        val average = getAverageForPeriodType(PeriodType.DAY, excludeCurrent = false)

        dailyAggregates.map { periodAgg ->
            val dayStart = TimePeriodUtils.parseDayKeyToStart(periodAgg.periodKey) ?: startMs
            val dayEnd = TimePeriodUtils.getEndOfDay(dayStart)
            val date = toLocalDate(dayStart)
            val aggregate = periodAgg.aggregate
            PeriodTotal(
                periodLabel = DAY_FORMAT.format(date),
                periodKey = periodAgg.periodKey,
                totalAmount = aggregate.displayAmount,
                transactionCount = aggregate.totalTransactionCount,
                periodType = PeriodType.DAY,
                startDateMs = dayStart,
                endDateMs = dayEnd,
                status = getPeriodStatus(aggregate.displayAmount, average),
                isPartial = aggregate.isPartial,
                warningMessage = aggregate.warningMessage
            )
        }
    }

    /**
     * Returns yearly spending totals for the last 5 years.
     *
     * Both the total amount and transaction count reflect **purchase-only** data.
     * P5-P1-01 / P5-NEW-09 FIX: uses per-expense TRANSACTION_DATE historical
     * conversion via [purchaseTotalHistorical] (same basis as monthly/weekly/daily
     * and analytics summary) and propagates conversion partial-state.
     */
    fun getYearlyTotals(): Flow<List<PeriodTotal>> = reactiveFlow {
        val now = timeProvider.now()
        val currentYear = TimePeriodUtils.getYear(now)
        
        // Get data for last 5 years
        val years = (currentYear - 4..currentYear).toList()
        val average = getAverageForPeriodType(PeriodType.YEAR, excludeCurrent = true)
        
        years.map { year ->
            val (startMs, endMs) = getYearRange(year)
            val aggregate = purchaseTotalHistorical(startMs, endMs)
            val total = aggregate?.displayAmount ?: 0.0
            
            PeriodTotal(
                periodLabel = year.toString(),
                periodKey = year.toString(),
                totalAmount = total,
                transactionCount = aggregate?.totalTransactionCount ?: 0,
                periodType = PeriodType.YEAR,
                startDateMs = startMs,
                endDateMs = endMs,
                status = getPeriodStatus(total, average),
                isPartial = aggregate?.isPartial ?: false,
                warningMessage = aggregate?.warningMessage
            )
        }.filter { it.totalAmount > 0 || it.periodKey == currentYear.toString() }
    }

    /**
     * P5-P1-01 FIX: per-expense TRANSACTION_DATE historical PURCHASE total in home currency.
     * Returns null when home currency is unavailable so callers degrade gracefully
     * (treat as zero / skip) rather than fabricating a sentinel-currency aggregate.
     */
    private suspend fun purchaseTotalHistorical(startMs: Long, endMs: Long): MoneyAggregate? =
        when (val result = multiCurrencyRepository.getHomeCurrencyPurchaseTotalHistoricalResult(startMs, endMs)) {
            is com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Available -> result.aggregate
            is com.yourname.expensetracker.domain.core.money.MoneyAggregateResult.Unavailable -> null
        }

    /**
     * SRH-13-FIXED: Uncategorized category breakdown.
     *
     * When [ExpenseRepository.getCategoryBreakdown] returns results with a null
     * category ID (i.e., expenses with no assigned category), this method maps
     * them into an "Uncategorized" pseudo-category bucket. This ensures that
     * uncategorized spend is visible in the breakdown rather than silently omitted.
     *
     * The "Uncategorized" bucket is always last in sort order (lowest totalAmount),
     * which is correct since individual categorized buckets should be prioritized.
     *
     * ## I4
     * Replaced deprecated [ExpenseRepository.getCategoryBreakdown] (raw DAO doubles)
     * with [MultiCurrencyRepository.getCategoryAggregatesHistorical] for currency-safe,
     * purchase-only, per-expense TRANSACTION_DATE totals (P5-P1-01: same rate basis as
     * the monthly/yearly/weekly/daily totals), plus [CategoryRepository.getAll] for
     * category metadata.
     */
    fun getCategoryBreakdown(startMs: Long, endMs: Long, periodLabel: String): Flow<List<CategoryBreakdown>> = reactiveCategoryBreakdownFlow {
        val categories = categoryRepository.getAll()
        val categoryMap = categories.associateBy { it.id }
        val categoryAggregates = multiCurrencyRepository.getCategoryAggregatesHistorical(startMs, endMs)
        val grandTotal = categoryAggregates.values.sumOf { it.displayAmount }

        categoryAggregates.mapNotNull { (categoryId, aggregate) ->
            val category = if (categoryId == null) {
                // SRH-13: Include null-category expenses as "Uncategorized" pseudo-category
                CategoryInfo(
                    id = 0L,
                    name = "Uncategorized",
                    icon = "?",
                    color = "#808080",
                    isIncome = false
                )
            } else {
                val cat = categoryMap[categoryId] ?: return@mapNotNull null
                CategoryInfo(
                    id = cat.id,
                    name = cat.name,
                    icon = cat.icon,
                    color = cat.color,
                    isIncome = false
                )
            }

            val percentage = if (grandTotal > 0) {
                aggregate.displayAmount / grandTotal * 100
            } else {
                0.0
            }

            CategoryBreakdown(
                category = category,
                totalAmount = aggregate.displayAmount,
                transactionCount = aggregate.totalTransactionCount,
                percentageOfTotal = percentage,
                periodLabel = periodLabel
            )
        }.sortedByDescending { it.totalAmount }
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
                        // P5-P1-01 FIX: historical TRANSACTION_DATE basis matches getYearlyTotals
                        val total = purchaseTotalHistorical(startMs, endMs)?.displayAmount ?: 0.0
                        if (total > 0) total else null
                    }
                    yearTotals.takeIf { it.isNotEmpty() }?.average() ?: 0.0
                }
                PeriodType.MONTH -> {
                    val startMs = TimePeriodUtils.getStartOfMonth(TimePeriodUtils.addMonths(now, -12))
                    // P5-P1-01 FIX: historical TRANSACTION_DATE basis matches getMonthlyTotals
                    val months = multiCurrencyRepository.getMonthlyAggregatesHistorical(startMs, now)
                    if (excludeCurrent) {
                        // DSH-13: Use time-based filtering instead of positional dropLast(1).
                        // The current (incomplete) month is excluded by comparing the monthKey
                        // (format "YYYY-MM") against the current month's key.
                        val currentYear = TimePeriodUtils.getYear(now)
                        val currentMonth = TimePeriodUtils.getMonth(now) + 1
                        val currentMonthKey = "%04d-%02d".format(currentYear, currentMonth)
                        months.filter { it.monthKey < currentMonthKey }
                            .map { it.aggregate.displayAmount }
                            .average()
                            .takeIf { !it.isNaN() } ?: 0.0
                    } else {
                        months.map { it.aggregate.displayAmount }.average().takeIf { !it.isNaN() } ?: 0.0
                    }
                }
                PeriodType.WEEK -> {
                    val startMs = TimePeriodUtils.getStartOfWeek(TimePeriodUtils.addDays(now, -56))
                    val weeks = multiCurrencyRepository.getWeeklyAggregatesHistorical(startMs, now)
                    if (excludeCurrent) {
                        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)
                        weeks.filter { periodAgg ->
                            val weekStart = TimePeriodUtils.parseWeekKeyToStart(periodAgg.periodKey) ?: return@filter false
                            weekStart < currentWeekStart
                        }
                            .map { it.aggregate.displayAmount }
                            .average()
                            .takeIf { !it.isNaN() } ?: 0.0
                    } else {
                        weeks.map { it.aggregate.displayAmount }.average().takeIf { !it.isNaN() } ?: 0.0
                    }
                }
                PeriodType.DAY -> {
                    val startMs = TimePeriodUtils.getStartOfDay(TimePeriodUtils.addDays(now, -30))
                    // P5-P1-01 FIX: historical TRANSACTION_DATE basis matches the daily drilldown
                    val daysCount = TimePeriodUtils.daysBetween(startMs, now).coerceAtLeast(1)
                    val total = purchaseTotalHistorical(startMs, now)?.displayAmount ?: 0.0
                    total / daysCount
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
                startDateMs = dayStart,
                endDateMs = dayEnd,
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

    /**
     * PR-E11: Compute period totals from pre-normalized input.
     *
     * Accepts a [NormalizedAnalyticsInput] whose [includedExpenses] are already
     * normalized to [homeCurrency]. Groups by day, week, or month depending on
     * context, using the same period-key logic as the other methods.
     *
     * TODO (ARCH-E13): Extract private period-key helpers (dayKey, weekKey, monthKey)
     *                  into shared utility so this method and the reactive methods
     *                  use the same grouping logic.
     * TODO (ARCH-E13): Support [PeriodType] parameter to control grouping granularity.
     * ARCH-E13: PeriodTotal now carries [input.dataQuality] for stale-rate and conversion
     * warnings. Callers should inspect periodTotal.dataQuality instead of querying input separately.
     */
    fun computeFromNormalized(input: NormalizedAnalyticsInput): List<PeriodTotal> {
        val expenses = input.includedExpenses
        if (expenses.isEmpty()) return emptyList()

        val defaultStatus = PeriodStatus.NO_DATA
        val average = if (expenses.isNotEmpty()) {
            expenses.sumOf { it.normalizedAmount } / expenses.size
        } else 0.0

        // Group by day using the same dayKey format as [dayKey]
        val groupedByDay = expenses.groupBy { exp ->
            val date = Instant.ofEpochMilli(exp.date).atZone(systemZoneId()).toLocalDate()
            "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)
        }

        return groupedByDay.map { (periodKey, group) ->
            val firstDate = Instant.ofEpochMilli(group.first().date).atZone(systemZoneId()).toLocalDate()
            val dayStart = firstDate.atStartOfDay(systemZoneId()).toInstant().toEpochMilli()
            val dayEnd = dayStart + 86_400_000L // 24 hours in ms
            val total = group.sumOf { it.normalizedAmount }

            PeriodTotal(
                periodLabel = DAY_FORMAT.format(firstDate),
                periodKey = periodKey,
                totalAmount = total,
                transactionCount = group.size,
                periodType = PeriodType.DAY,
                startDateMs = dayStart,
                endDateMs = dayEnd,
                status = if (average > 0 && total > average) PeriodStatus.OVER_AVERAGE
                         else if (average > 0) PeriodStatus.UNDER_AVERAGE
                         else defaultStatus,
                dataQuality = input.dataQuality
            )
        }.sortedBy { it.periodKey }
    }

    /**
     * Safe summary computed from pre-normalized input (already converted to home currency).
     *
     * This is the preferred entry point for callers that have a [NormalizedAnalyticsInput]
     * available, as it avoids the raw DAO sum pitfalls of the deprecated methods.
     */
    data class TotalsSummary(
        val total: Double,
        val count: Int,
        val avgPerDay: Double,
        val avgPerWeek: Double,
        val currency: String
    )

    fun summarize(input: NormalizedAnalyticsInput): TotalsSummary {
        val expenses = input.includedExpenses.filter { it.transactionType == "PURCHASE" && !it.isNotMine }
        val total = expenses.sumOf { it.normalizedAmount }
        val count = expenses.size
        val days = input.period?.let { period ->
            java.time.Duration.between(
                java.time.Instant.ofEpochMilli(period.startInclusiveMillis),
                java.time.Instant.ofEpochMilli(period.endExclusiveMillis)
            ).toDays().coerceAtLeast(1)
        } ?: 30
        return TotalsSummary(
            total = total, count = count,
            avgPerDay = total / days,
            avgPerWeek = total / (days / 7.0).coerceAtLeast(1.0),
            currency = input.homeCurrency
        )
    }

    private fun systemZoneId(): ZoneId = ZoneId.systemDefault()

    /**
     * DSH-10-FIXED: Wraps a one-shot analytics computation in a reactive Flow.
     *
     * Re-emits whenever [ExpenseRepository.getTotalSpent] fires
     * (i.e. whenever the expenses table is invalidated by Room).
     * The [block] is executed on [ioDispatcher] and its result emitted downstream.
     *
     * P2-16: Replaced while(true) busy-loop with flatMapLatest. The previous
     * pattern called `.first()` on a Room Flow inside a while-loop, which
     * re-subscribed immediately after each emission causing an infinite busy-loop.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun reactiveFlow(block: suspend () -> List<PeriodTotal>): Flow<List<PeriodTotal>> =
        expenseRepository.getTotalSpent().flatMapLatest {
            flow {
                emit(withContext(ioDispatcher) {
                    try {
                        block()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.tag("TotalsAggregationEngine").e(e, "Reactive flow computation failed")
                        emptyList()
                    }
                })
            }
        }

    /**
     * DSH-10-FIXED: Wraps a one-shot category breakdown computation in a reactive Flow.
     *
     * P2-16: Replaced while(true) busy-loop with flatMapLatest.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun reactiveCategoryBreakdownFlow(block: suspend () -> List<CategoryBreakdown>): Flow<List<CategoryBreakdown>> =
        expenseRepository.getTotalSpent().flatMapLatest {
            flow {
                emit(withContext(ioDispatcher) {
                    try {
                        block()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.tag("TotalsAggregationEngine").e(e, "Reactive category breakdown flow failed")
                        emptyList()
                    }
                })
            }
        }
}
