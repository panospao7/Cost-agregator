package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Builds a historical spending distribution from the user's transaction data.
 *
 * Design decisions (agreed with user):
 * - **Sampling unit**: Weekly totals (not daily — too sparse with ~200-500 txns over ~18 months)
 * - **Filtering**: Only PURCHASE + WITHDRAWAL with isNotMine=false
 * - **Quality filter**: Weeks with < 3 distinct transaction-days are excluded (likely incomplete data)
 * - **Outlier trimming**: Middle 80% of weekly totals (trim top/bottom 10%)
 * - **Distribution**: Log-normal (always positive, right-skewed — matches spending behaviour)
 */
@Singleton
class HistoricalSpendingDistribution @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val timeProvider: TimeProvider
) {
    companion object {
        /** Look back 18 months for historical data. */
        private const val LOOKBACK_MONTHS = 18

        /** Minimum distinct transaction-days in a week for it to count. */
        private const val MIN_TRANSACTION_DAYS_PER_WEEK = 3

        /** Trim percentile from each tail (10% each side = middle 80%). */
        private const val TRIM_PERCENTILE = 0.10
    }

    /**
     * Fetches historical expenses and computes the log-normal distribution parameters.
     *
     * @return [DistributionFit] with mu, sigma, qualifying week count, and raw weekly totals;
     *         or null if there is insufficient data to fit.
     */
    suspend fun computeDistribution(): DistributionFit? {
        val now = timeProvider.now()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }

        // Lookback start: 18 months ago, start of that week (Monday)
        calendar.add(Calendar.MONTH, -LOOKBACK_MONTHS)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val lookbackStart = calendar.timeInMillis

        // End: start of the current week (so we don't include a partial current week)
        val nowCal = Calendar.getInstance().apply { timeInMillis = now }
        nowCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        nowCal.set(Calendar.HOUR_OF_DAY, 0)
        nowCal.set(Calendar.MINUTE, 0)
        nowCal.set(Calendar.SECOND, 0)
        nowCal.set(Calendar.MILLISECOND, 0)
        val currentWeekStart = nowCal.timeInMillis

        if (currentWeekStart <= lookbackStart) {
            Timber.w("Not enough history for Monte Carlo (lookback start >= current week start)")
            return null
        }

        // Fetch all qualifying expenses in the range
        val allExpenses = expenseRepository.getExpensesBetween(lookbackStart, currentWeekStart)

        val spendingExpenses = allExpenses.filter { expense ->
            (expense.transactionType == TransactionType.PURCHASE ||
                expense.transactionType == TransactionType.WITHDRAWAL) &&
                !expense.isNotMine
        }

        if (spendingExpenses.isEmpty()) {
            Timber.w("No qualifying spending expenses found for Monte Carlo")
            return null
        }

        // Group into ISO weeks
        val weeklyData = groupIntoWeeks(spendingExpenses, lookbackStart, currentWeekStart)

        // Count total weeks examined (including ones we'll exclude)
        val totalWeeksExamined = weeklyData.size

        // Filter: only weeks with >= MIN_TRANSACTION_DAYS_PER_WEEK distinct transaction-days
        val qualifyingWeeks = weeklyData.filter { it.distinctDays >= MIN_TRANSACTION_DAYS_PER_WEEK }

        if (qualifyingWeeks.size < 4) {
            Timber.w("Only ${qualifyingWeeks.size} qualifying weeks — need at least 4 for distribution fit")
            return DistributionFit(
                mu = 0.0,
                sigma = 0.0,
                qualifyingWeekCount = qualifyingWeeks.size,
                totalWeeksExamined = totalWeeksExamined,
                trimmedWeeklyTotals = qualifyingWeeks.map { it.total },
                allWeeklyTotals = weeklyData.map { it.total }
            )
        }

        // Sort and trim outliers (middle 80%)
        val sortedTotals = qualifyingWeeks.map { it.total }.sorted()
        val trimCount = (sortedTotals.size * TRIM_PERCENTILE).toInt().coerceAtLeast(0)
        val trimmed = if (trimCount > 0 && sortedTotals.size > 2 * trimCount) {
            sortedTotals.subList(trimCount, sortedTotals.size - trimCount)
        } else {
            sortedTotals // Not enough data to trim — use all
        }

        if (trimmed.isEmpty() || trimmed.any { it <= 0.0 }) {
            Timber.w("Trimmed data is empty or contains non-positive values; falling back to untrimmed")
            val fallback = sortedTotals.filter { it > 0.0 }
            if (fallback.size < 2) return null
            return fitLogNormal(fallback, qualifyingWeeks.size, totalWeeksExamined, weeklyData.map { it.total })
        }

        return fitLogNormal(trimmed, qualifyingWeeks.size, totalWeeksExamined, weeklyData.map { it.total })
    }

    /**
     * Groups expenses into calendar weeks (Monday-Sunday) and calculates per-week metrics.
     */
    private fun groupIntoWeeks(
        expenses: List<Expense>,
        rangeStart: Long,
        rangeEnd: Long
    ): List<WeekData> {
        val msPerDay = 24 * 60 * 60 * 1000L
        val msPerWeek = 7 * msPerDay

        // Build a map: weekIndex -> list of expenses
        val weekMap = mutableMapOf<Int, MutableList<Expense>>()
        for (expense in expenses) {
            val weekIndex = ((expense.date - rangeStart) / msPerWeek).toInt()
            weekMap.getOrPut(weekIndex) { mutableListOf() }.add(expense)
        }

        // Also enumerate all weeks in the range (so we count empty weeks too)
        val totalWeeks = ((rangeEnd - rangeStart) / msPerWeek).toInt()

        return (0 until totalWeeks).map { weekIndex ->
            val weekExpenses = weekMap[weekIndex] ?: emptyList()
            val total = weekExpenses.sumOf { it.amount }

            // Count distinct calendar days with transactions
            val distinctDays = weekExpenses
                .map { it.date / msPerDay }
                .toSet()
                .size

            WeekData(
                weekIndex = weekIndex,
                total = total,
                transactionCount = weekExpenses.size,
                distinctDays = distinctDays
            )
        }
    }

    /**
     * Fits a log-normal distribution to the given positive weekly totals.
     *
     * Log-normal: if X ~ LogNormal(mu, sigma), then ln(X) ~ Normal(mu, sigma).
     * We compute mu and sigma as the mean and std dev of the log-transformed values.
     */
    private fun fitLogNormal(
        weeklyTotals: List<Double>,
        qualifyingWeekCount: Int,
        totalWeeksExamined: Int,
        allWeeklyTotals: List<Double>
    ): DistributionFit {
        val logValues = weeklyTotals.map { ln(it) }
        val mu = logValues.average()
        val variance = if (logValues.size > 1) {
            logValues.sumOf { (it - mu) * (it - mu) } / (logValues.size - 1) // Bessel's correction
        } else {
            0.0
        }
        val sigma = sqrt(variance)

        return DistributionFit(
            mu = mu,
            sigma = sigma,
            qualifyingWeekCount = qualifyingWeekCount,
            totalWeeksExamined = totalWeeksExamined,
            trimmedWeeklyTotals = weeklyTotals,
            allWeeklyTotals = allWeeklyTotals
        )
    }
}

/** Per-week aggregation data (internal). */
private data class WeekData(
    val weekIndex: Int,
    val total: Double,
    val transactionCount: Int,
    val distinctDays: Int
)

/**
 * Result of fitting a log-normal distribution to historical weekly spending.
 */
data class DistributionFit(
    /** Mean of ln(weeklyTotal). */
    val mu: Double,

    /** Std dev of ln(weeklyTotal). */
    val sigma: Double,

    /** Number of weeks that passed the quality filter. */
    val qualifyingWeekCount: Int,

    /** Total number of weeks in the lookback window. */
    val totalWeeksExamined: Int,

    /** The trimmed weekly totals used for the fit. */
    val trimmedWeeklyTotals: List<Double>,

    /** All weekly totals (before trimming, but after quality filter is applied to the fit — this is pre-filter). */
    val allWeeklyTotals: List<Double>
) {
    /** Whether this fit has enough data to be usable for simulation. */
    val isUsable: Boolean get() = qualifyingWeekCount >= 4 && sigma > 0.0
}
