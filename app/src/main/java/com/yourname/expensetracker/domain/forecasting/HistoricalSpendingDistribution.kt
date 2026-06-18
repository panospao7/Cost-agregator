package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
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
    private val timeProvider: TimeProvider,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository
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
    suspend fun computeDistribution(homeCurrency: String = "EUR"): DistributionFit? {
        val now = timeProvider.now()

        // Lookback start: 18 months ago, snapped to the start of that week (Monday)
        val lookbackRaw = TimePeriodUtils.addMonths(now, -LOOKBACK_MONTHS)
        val lookbackStart = TimePeriodUtils.getStartOfWeek(lookbackRaw)

        // End: start of the current week (so we don't include a partial current week)
        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)

        if (currentWeekStart <= lookbackStart) {
            Timber.w("Not enough history for Monte Carlo (lookback start >= current week start)")
            return null
        }

        // Resolve authoritative home currency if the default placeholder was passed
        val resolvedHomeCurrency = if (homeCurrency == "EUR") {
            runCatching { currencySettingsRepository.homeCurrency().first() }.getOrElse { throw IllegalStateException("Home currency unavailable: ${it.message}") }
        } else {
            homeCurrency
        }

        // Fetch all qualifying expenses in the range and normalize to home currency
        val allExpenses = expenseRepository.getExpensesBetween(lookbackStart, currentWeekStart)
        val normalized = analyticsCurrencyNormalizer.normalizeExpenses(allExpenses, resolvedHomeCurrency)
        val normalizedExpenses = normalized.includedExpenses

        val spendingExpenses = normalizedExpenses.filter { expense ->
            (expense.transactionType == DomainTransactionType.PURCHASE ||
                expense.transactionType == DomainTransactionType.WITHDRAWAL) &&
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

        // FCST-16: Include zero-spend weeks in the distribution.
        // Weeks with zero total spending (and >= MIN_TRANSACTION_DAYS_PER_WEEK
        // distinct days, meaning the user was active but spent nothing) are
        // legitimate data points. Previously these were excluded because they
        // had no transactions; now they are included as 0.0 totals.
        val allWeeksIncludingZero = weeklyData.map { week ->
            if (week.distinctDays == 0 && week.total == 0.0) {
                // Genuinely quiet week — set a small positive value to keep
                // log-normal fit numerically stable (ln(0) = -inf).
                // Use 0.01 as a nominal minimum.
                week.copy(total = 0.01)
            } else {
                week
            }
        }
        // Rebuild qualifying weeks list that includes quiet weeks as valid data points
        val expandedQualifying = allWeeksIncludingZero.filter { it.distinctDays >= MIN_TRANSACTION_DAYS_PER_WEEK || it.distinctDays == 0 } // include qualifying weeks + quiet zero-spend weeks
        val qualifyingForFit = if (expandedQualifying.size >= 4) expandedQualifying else qualifyingWeeks

        if (qualifyingForFit.size < 4) {
            Timber.w("Only ${qualifyingForFit.size} qualifying weeks — need at least 4 for distribution fit")
            return DistributionFit(
                mu = 0.0,
                sigma = 0.0,
                qualifyingWeekCount = qualifyingForFit.size,
                totalWeeksExamined = totalWeeksExamined,
                trimmedWeeklyTotals = qualifyingForFit.map { it.total },
                allWeeklyTotals = weeklyData.map { it.total },
                displayCurrency = resolvedHomeCurrency
            )
        }

        // Sort and trim outliers (middle 80%)
        val sortedTotals = qualifyingForFit.map { it.total }.sorted()
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
            return fitLogNormal(fallback, qualifyingForFit.size, totalWeeksExamined, weeklyData.map { it.total }, resolvedHomeCurrency)
        }

        return fitLogNormal(trimmed, qualifyingForFit.size, totalWeeksExamined, weeklyData.map { it.total }, resolvedHomeCurrency)
    }

    /**
     * Groups expenses into calendar weeks (Monday-Sunday) and calculates per-week metrics.
     *
     * Uses [TimePeriodUtils.getStartOfWeek] for calendar-safe, DST-aware week bucketing
     * and [TimePeriodUtils.getStartOfDay] for distinct-day counting.
     */
    private fun groupIntoWeeks(
        expenses: List<ExpenseSnapshot>,
        rangeStart: Long,
        rangeEnd: Long
    ): List<WeekData> {
        // Build a map: weekStartTimestamp -> list of expenses
        val weekMap = mutableMapOf<Long, MutableList<ExpenseSnapshot>>()
        for (expense in expenses) {
            val weekStart = TimePeriodUtils.getStartOfWeek(expense.date)
            weekMap.getOrPut(weekStart) { mutableListOf() }.add(expense)
        }

        // Enumerate all weeks in the range so we count empty weeks too
        val allWeekStarts = mutableListOf<Long>()
        var cursor = rangeStart // rangeStart is already a Monday 00:00:00
        while (cursor < rangeEnd) {
            allWeekStarts.add(cursor)
            cursor = TimePeriodUtils.addDays(cursor, 7)
        }

        return allWeekStarts.mapIndexed { index, weekStart ->
            val weekExpenses = weekMap[weekStart] ?: emptyList()
            // SAFE: weekExpenses derived from normalized expenses at line 75 via AnalyticsCurrencyNormalizer
            val total = weekExpenses.sumOf { it.effectiveAmount }

            // Count distinct calendar days with transactions (DST-safe)
            val distinctDays = weekExpenses
                .map { TimePeriodUtils.getStartOfDay(it.date) }
                .toSet()
                .size

            WeekData(
                weekIndex = index,
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
        allWeeklyTotals: List<Double>,
        displayCurrency: String = "EUR"
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
            allWeeklyTotals = allWeeklyTotals,
            displayCurrency = displayCurrency
        )
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
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
    val allWeeklyTotals: List<Double>,

    /** Currency in which all amounts in this fit are denominated. */
    val displayCurrency: String = "EUR"
) {
    /** Whether this fit has enough data to be usable for simulation. */
    val isUsable: Boolean get() = qualifyingWeekCount >= 4 && sigma > 0.0
}
