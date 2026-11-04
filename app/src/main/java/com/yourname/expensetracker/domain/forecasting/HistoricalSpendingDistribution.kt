package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.requireCompleteMoneyInput
import com.yourname.expensetracker.domain.analytics.completeMoneyAggregateForRows
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarningType
import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.RateBasis
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
 * - **Filtering**: Only spending (PURCHASE; withdrawals are not spending) with isNotMine=false
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
    @Deprecated("Use computeDistributionResult() to preserve unavailable quality")
    suspend fun computeDistribution(): DistributionFit? =
        computeDistributionResult().asNullableCompatibility()

    @Deprecated("Pass an explicit supported home currency at decision boundaries")
    suspend fun computeDistributionResult(): HistoricalDistributionResult {
        val currency = try {
            currencySettingsRepository.homeCurrency().first()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return HistoricalDistributionResult.Unavailable(
                reasonCode = MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE
            )
        }
        return computeDistributionResult(currency)
    }

    @Deprecated("Use computeDistributionResult(homeCurrency) to preserve unavailable quality")
    suspend fun computeDistribution(homeCurrency: String): DistributionFit? =
        computeDistributionResult(homeCurrency).asNullableCompatibility()

    suspend fun computeDistributionResult(homeCurrency: String): HistoricalDistributionResult {
        // Validate before querying history or selecting a deterministic fallback.
        // Valid input is normalized, but no target currency is ever guessed.
        val resolvedHomeCurrency = CurrencyCode.parse(homeCurrency)
            ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?.code
            ?: return HistoricalDistributionResult.Unavailable(
                reasonCode = MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY
            )
        val now = timeProvider.now()

        // Lookback start: 18 months ago, snapped to the start of that week (Monday)
        val lookbackRaw = TimePeriodUtils.addMonths(now, -LOOKBACK_MONTHS)
        val lookbackStart = TimePeriodUtils.getStartOfWeek(lookbackRaw)

        // End: start of the current week (so we don't include a partial current week)
        val currentWeekStart = TimePeriodUtils.getStartOfWeek(now)

        if (currentWeekStart <= lookbackStart) {
            Timber.w("Not enough history for Monte Carlo (lookback start >= current week start)")
            return HistoricalDistributionResult.NoFit()
        }

        // An explicit captured currency, including EUR, must never be relabeled by settings.

        // Fetch all qualifying expenses in the range and normalize to home currency
        val allExpenses = expenseRepository.getExpensesBetween(lookbackStart, currentWeekStart)
        val normalized = try {
            analyticsCurrencyNormalizer.normalizeExpenses(allExpenses, resolvedHomeCurrency)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return HistoricalDistributionResult.Unavailable(
                reasonCode = MoneyDisplayUnavailableReasonCode.DISPLAY_CONVERSION_UNAVAILABLE,
                totalInputCount = allExpenses.size,
                excludedInputCount = allExpenses.size
            )
        }
        try {
            normalized.requireCompleteMoneyInput(resolvedHomeCurrency)
        } catch (error: com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException) {
            return normalized.toUnavailable(error.reasonCode)
        }
        val normalizedExpenses = normalized.includedExpenses
        val moneyQuality = HistoricalMoneyQuality(
            actualRateBasis = if (normalized.normalizedExpenses.all {
                    it.originalCurrency == normalized.homeCurrency
                }) RateBasis.IDENTITY else RateBasis.TRANSACTION_DATE,
            totalInputCount = normalized.totalInputCount,
            includedInputCount = normalized.normalizedExpenses.size,
            excludedInputCount = normalized.excludedCount,
            warnings = normalized.warnings,
            latestRateTimestamp = normalized.latestRateTimestamp
        )

        val spendingExpenses = normalizedExpenses.filter { expense ->
            expense.transactionType.isSpending && !expense.isNotMine
        }

        if (spendingExpenses.isEmpty()) {
            Timber.w("No qualifying spending expenses found for Monte Carlo")
            return HistoricalDistributionResult.NoFit(
                totalInputCount = normalized.totalInputCount,
                includedInputCount = normalized.normalizedExpenses.size
            )
        }

        // Exclude pre-first-transaction weeks so new users' pre-history does not inflate observed quiet weeks
        val firstTransactionWeekStart = spendingExpenses.minOf { TimePeriodUtils.getStartOfWeek(it.date) }
        val effectiveRangeStart = maxOf(lookbackStart, firstTransactionWeekStart)

        // Group into ISO weeks
        val weeklyData = groupIntoWeeks(
            expenses = spendingExpenses,
            rangeStart = effectiveRangeStart,
            rangeEnd = currentWeekStart,
            homeCurrency = resolvedHomeCurrency,
            normalized = normalized
        )

        // Count total weeks examined (including ones we'll exclude)
        val totalWeeksExamined = weeklyData.size

        // Filter: only weeks with >= MIN_TRANSACTION_DAYS_PER_WEEK distinct transaction-days
        val qualifyingWeeks = weeklyData.filter { it.distinctDays >= MIN_TRANSACTION_DAYS_PER_WEEK }

        // BL-01: Fit LogNormal strictly on positive qualifying weeks (no 0.01 substitution for quiet weeks),
        // and record observed quiet (zero-spend) weeks separately so ln(0.01) never poisons mu/sigma.
        val positiveQualifying = qualifyingWeeks.filter { it.total.displayAmount > 0.0 }
        val zeroWeekCount = weeklyData.count { it.distinctDays == 0 && it.total.displayAmount == 0.0 }
        val observedWeekCount = positiveQualifying.size + zeroWeekCount
        val zeroWeekProbability = if (observedWeekCount > 0) {
            zeroWeekCount.toDouble() / observedWeekCount
        } else {
            0.0
        }

        if (positiveQualifying.size < 4) {
            Timber.w("Only ${positiveQualifying.size} positive qualifying weeks — need at least 4 for distribution fit")
            return HistoricalDistributionResult.Available(
                DistributionFit(
                    mu = 0.0,
                    sigma = 0.0,
                    qualifyingWeekCount = positiveQualifying.size,
                    totalWeeksExamined = totalWeeksExamined,
                    trimmedWeeklyTotals = positiveQualifying.map { it.total.displayAmount },
                    allWeeklyTotals = weeklyData.map { it.total.displayAmount },
                    displayCurrency = resolvedHomeCurrency,
                    moneyQuality = moneyQuality,
                    zeroWeekCount = zeroWeekCount,
                    zeroWeekProbability = zeroWeekProbability
                )
            )
        }

        // Sort and trim outliers (middle 80%)
        val sortedTotals = positiveQualifying.map { it.total.displayAmount }.sorted()
        val trimCount = (sortedTotals.size * TRIM_PERCENTILE).toInt().coerceAtLeast(0)
        val trimmed = if (trimCount > 0 && sortedTotals.size > 2 * trimCount) {
            sortedTotals.subList(trimCount, sortedTotals.size - trimCount)
        } else {
            sortedTotals // Not enough data to trim — use all
        }

        if (trimmed.isEmpty() || trimmed.any { it <= 0.0 }) {
            Timber.w("Trimmed data is empty or contains non-positive values; falling back to untrimmed")
            val fallback = sortedTotals.filter { it > 0.0 }
            if (fallback.size < 2) return HistoricalDistributionResult.NoFit(
                totalInputCount = normalized.totalInputCount,
                includedInputCount = normalized.normalizedExpenses.size
            )
            return HistoricalDistributionResult.Available(
                fitLogNormal(
                    fallback,
                    positiveQualifying.size,
                    totalWeeksExamined,
                    weeklyData.map { it.total.displayAmount },
                    resolvedHomeCurrency,
                    moneyQuality,
                    zeroWeekCount,
                    zeroWeekProbability
                )
            )
        }

        return HistoricalDistributionResult.Available(
            fitLogNormal(
                trimmed,
                positiveQualifying.size,
                totalWeeksExamined,
                weeklyData.map { it.total.displayAmount },
                resolvedHomeCurrency,
                moneyQuality,
                zeroWeekCount,
                zeroWeekProbability
            )
        )
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
        rangeEnd: Long,
        homeCurrency: String,
        normalized: AnalyticsNormalizationResult
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
            val total = normalized.completeMoneyAggregateForRows(weekExpenses, homeCurrency)

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
        displayCurrency: String,
        moneyQuality: HistoricalMoneyQuality,
        zeroWeekCount: Int = 0,
        zeroWeekProbability: Double = 0.0
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
            displayCurrency = displayCurrency,
            moneyQuality = moneyQuality,
            zeroWeekCount = zeroWeekCount,
            zeroWeekProbability = zeroWeekProbability
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

private fun AnalyticsNormalizationResult.toUnavailable(
    reasonCode: MoneyDisplayUnavailableReasonCode
): HistoricalDistributionResult.Unavailable = HistoricalDistributionResult.Unavailable(
    reasonCode = reasonCode,
    quality = when {
        reasonCode == MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY ||
            (excludedCount >= totalInputCount && totalInputCount > 0) -> ConversionQuality.UNAVAILABLE
        excludedCount > 0 -> ConversionQuality.PARTIAL
        warnings.any { it.type == AnalyticsConversionWarningType.STALE_EXCHANGE_RATE } ||
            normalizedExpenses.any { it.qualityWarning == AnalyticsConversionWarningType.STALE_EXCHANGE_RATE } ->
            ConversionQuality.ESTIMATED
        else -> ConversionQuality.UNAVAILABLE
    },
    totalInputCount = totalInputCount,
    includedInputCount = normalizedExpenses.size,
    excludedInputCount = excludedCount,
    warnings = warnings,
    latestRateTimestamp = latestRateTimestamp
)

private fun HistoricalDistributionResult.asNullableCompatibility(): DistributionFit? =
    (this as? HistoricalDistributionResult.Available)?.fit

/**
 * Typed distribution boundary. No-fit history is distinct from failed money
 * normalization so callers cannot treat an unavailable result as empty data.
 */
sealed interface HistoricalDistributionResult {
    data class Available(val fit: DistributionFit) : HistoricalDistributionResult

    data class Unavailable(
        val reasonCode: MoneyDisplayUnavailableReasonCode,
        val quality: ConversionQuality = ConversionQuality.UNAVAILABLE,
        val requestedRateBasis: RateBasis = RateBasis.TRANSACTION_DATE,
        val totalInputCount: Int = 0,
        val includedInputCount: Int = 0,
        val excludedInputCount: Int = 0,
        val warnings: List<AnalyticsConversionWarning> = emptyList(),
        val latestRateTimestamp: Long? = null
    ) : HistoricalDistributionResult

    data class NoFit(
        val totalInputCount: Int = 0,
        val includedInputCount: Int = 0,
        val rateBasis: RateBasis = RateBasis.TRANSACTION_DATE
    ) : HistoricalDistributionResult
}

/** Normalization provenance for the historical input, not statistical fit confidence. */
data class HistoricalMoneyQuality(
    val quality: ConversionQuality = ConversionQuality.COMPLETE,
    val requestedRateBasis: RateBasis = RateBasis.TRANSACTION_DATE,
    val actualRateBasis: RateBasis = RateBasis.TRANSACTION_DATE,
    val totalInputCount: Int = 0,
    val includedInputCount: Int = 0,
    val excludedInputCount: Int = 0,
    val warnings: List<AnalyticsConversionWarning> = emptyList(),
    val latestRateTimestamp: Long? = null
)

/** Per-week aggregation data (internal). */
private data class WeekData(
    val weekIndex: Int,
    val total: MoneyAggregate,
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

    /** Explicit supported currency in which all amounts in this fit are denominated. */
    val displayCurrency: String,

    val moneyQuality: HistoricalMoneyQuality = HistoricalMoneyQuality(),

    /** Number of observed quiet (zero-spend) weeks after the user's first transaction. */
    val zeroWeekCount: Int = 0,

    /** Proportion of observed weeks (positive qualifying + quiet) that had zero spend. */
    val zeroWeekProbability: Double = 0.0
) {
    companion object {
        /**
         * Maximum plausible LogNormal weekly sigma before the fit is considered too noisy to model.
         * Aligns with [DataQualityAssessor]'s `sigma > 2.0` noisy threshold (where P90/P50 = exp(1.28 * 2.0) ≈ 13x).
         */
        const val MAX_USABLE_SIGMA = 2.0
    }

    /**
     * Whether this fit has enough data and bounded log-variance to be usable for simulation.
     * Requires at least 4 positive qualifying weeks and `0.0 < sigma <= MAX_USABLE_SIGMA` so extreme
     * dispersion degrades gracefully to deterministic totals instead of producing implausible P90s.
     */
    val isUsable: Boolean get() = qualifyingWeekCount >= 4 && sigma > 0.0 && sigma <= MAX_USABLE_SIGMA
}
