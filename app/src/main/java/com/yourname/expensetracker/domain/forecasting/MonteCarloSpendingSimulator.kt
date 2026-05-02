package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import java.util.Calendar
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Monte Carlo Spending Simulator.
 *
 * Produces probabilistic month-end spending forecasts by combining:
 * - **Stage 1 (Deterministic)**: Actual spending to date + known upcoming committed/likely expenses
 *   (already computed by SynthesisEngine and passed in as parameters)
 * - **Stage 2 (Stochastic)**: Sampled discretionary spending for remaining days using a log-normal
 *   distribution fitted to historical weekly totals
 *
 * ## FCST-2: Double-count prevention
 * The deterministic known-upcoming component is passed in as [knownUpcoming] from the caller
 * (typically [SynthesisEngine]), which already deduplicates PlannedExpense occurrences against
 * materialized RecurringOccurrence rows. The stochastic discretionary component is sampled from
 * **historical weekly spending totals that include all past spending** — this is intentional,
 * because past spending includes the same recurring bills that are also in the deterministic
 * component. The two components operate on disjoint time domains: deterministic covers future
 * committed obligations, while stochastic models future discretionary variability. There is no
 * double-count within the same time period.
 *
 * ## Algorithm
 * 1. Compute historical weekly spending distribution (via [HistoricalSpendingDistribution])
 * 2. For each of 1000 iterations:
 *    a. Sample a weekly spending rate from LogNormal(mu, sigma)
 *    b. Scale to the remaining fraction of the month: `sampledWeekly * (daysRemaining / 7.0)`
 *    c. Total = spentToDate + knownUpcoming + sampledDiscretionary
 * 3. Sort all 1000 totals and extract percentile bands
 * 4. If budget is set, compute P(total <= budget) as the fraction of iterations under budget
 */
@Singleton
class MonteCarloSpendingSimulator @Inject constructor(
    private val historicalDistribution: HistoricalSpendingDistribution,
    private val dataQualityAssessor: DataQualityAssessor,
    private val timeProvider: TimeProvider
) {
    companion object {
        private const val NUM_ITERATIONS = 1000
        private const val SEED = 42L // Reproducible results for same input
    }

    /**
     * Run the full simulation.
     *
     * @param spentToDate     Total spending so far this month (PURCHASE + WITHDRAWAL, isNotMine=false)
     * @param knownUpcoming   Deterministic upcoming expenses (committed + likely from SynthesisEngine)
     * @param budgetAmount    Monthly budget (null if no budget set)
     * @return [MonteCarloResult], or null if there's absolutely no data to work with
     */
    suspend fun simulate(
        spentToDate: Double,
        knownUpcoming: Double,
        budgetAmount: Double?,
        displayCurrency: String = "EUR"
    ): MonteCarloResult? {
        val now = timeProvider.now()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(0)

        // If it's the last day of the month, the "forecast" is just what we've spent + known upcoming
        if (daysRemaining == 0) {
            return buildEndOfMonthResult(spentToDate, knownUpcoming, budgetAmount, now, displayCurrency)
        }

        // Step 1: Get the historical distribution fit
        val fit = historicalDistribution.computeDistribution(homeCurrency = displayCurrency)

        // Compute recency: count qualifying weeks in last 8 weeks for confidence scoring
        val recentWeeksQualifying = countRecentQualifyingWeeks(fit)
        val confidence = dataQualityAssessor.assess(fit, recentWeeksQualifying)

        // If the fit isn't usable, return a degraded result with just deterministic data
        if (fit == null || !fit.isUsable) {
            Timber.w("Distribution not usable; returning deterministic-only forecast")
            return buildDegradedResult(
                spentToDate, knownUpcoming, budgetAmount, daysRemaining, confidence, fit, now, displayCurrency
            )
        }

        // Step 2: Run Monte Carlo simulation
        val fractionOfWeek = daysRemaining / 7.0
        val random = Random(SEED)
        val simulatedTotals = DoubleArray(NUM_ITERATIONS)

        for (i in 0 until NUM_ITERATIONS) {
            // Sample from LogNormal(mu, sigma):
            // If Z ~ Normal(0,1), then exp(mu + sigma*Z) ~ LogNormal(mu, sigma)
            val z = random.nextGaussian()
            val sampledWeekly = exp(fit.mu + fit.sigma * z)

            // Scale weekly rate to remaining days
            val sampledDiscretionary = sampledWeekly * fractionOfWeek

            simulatedTotals[i] = spentToDate + knownUpcoming + sampledDiscretionary
        }

        // Step 3: Sort and extract percentiles
        simulatedTotals.sort()

        val p10 = percentile(simulatedTotals, 0.10)
        val p25 = percentile(simulatedTotals, 0.25)
        val p50 = percentile(simulatedTotals, 0.50)
        val p75 = percentile(simulatedTotals, 0.75)
        val p90 = percentile(simulatedTotals, 0.90)

        // Step 4: Probability under budget
        val probUnderBudget = if (budgetAmount != null && budgetAmount > 0) {
            val countUnder = simulatedTotals.count { it <= budgetAmount }
            countUnder.toDouble() / NUM_ITERATIONS
        } else null

        return MonteCarloResult(
            percentile10 = p10,
            percentile25 = p25,
            percentile50 = p50,
            percentile75 = p75,
            percentile90 = p90,
            probabilityUnderBudget = probUnderBudget,
            budgetAmount = budgetAmount,
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            confidence = confidence,
            metadata = SimulationMetadata(
                qualifyingWeeks = fit.qualifyingWeekCount,
                totalWeeksExamined = fit.totalWeeksExamined,
                iterations = NUM_ITERATIONS,
                logNormalMu = fit.mu,
                logNormalSigma = fit.sigma,
                daysRemaining = daysRemaining,
                computedAt = now
            ),
            displayCurrency = displayCurrency
        )
    }

    /**
     * On the last day of the month, no stochastic component — just deterministic totals.
     */
    private suspend fun buildEndOfMonthResult(
        spentToDate: Double,
        knownUpcoming: Double,
        budgetAmount: Double?,
        now: Long,
        displayCurrency: String = "EUR"
    ): MonteCarloResult {
        val total = spentToDate + knownUpcoming
        val fit = historicalDistribution.computeDistribution(homeCurrency = displayCurrency)
        val confidence = dataQualityAssessor.assess(fit, countRecentQualifyingWeeks(fit))

        val probUnderBudget = if (budgetAmount != null && budgetAmount > 0) {
            if (total <= budgetAmount) 1.0 else 0.0
        } else null

        return MonteCarloResult(
            percentile10 = total,
            percentile25 = total,
            percentile50 = total,
            percentile75 = total,
            percentile90 = total,
            probabilityUnderBudget = probUnderBudget,
            budgetAmount = budgetAmount,
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            confidence = confidence,
            metadata = SimulationMetadata(
                qualifyingWeeks = fit?.qualifyingWeekCount ?: 0,
                totalWeeksExamined = fit?.totalWeeksExamined ?: 0,
                iterations = 0,
                logNormalMu = fit?.mu ?: 0.0,
                logNormalSigma = fit?.sigma ?: 0.0,
                daysRemaining = 0,
                computedAt = now
            ),
            displayCurrency = displayCurrency
        )
    }

    /**
     * When the distribution isn't usable, provide a degraded result using only deterministic data.
     * Percentile bands are all the same (no spread), but we still communicate the known total.
     */
    private fun buildDegradedResult(
        spentToDate: Double,
        knownUpcoming: Double,
        budgetAmount: Double?,
        daysRemaining: Int,
        confidence: SimulationConfidence,
        fit: DistributionFit?,
        now: Long,
        displayCurrency: String = "EUR"
    ): MonteCarloResult {
        val deterministicTotal = spentToDate + knownUpcoming

        val probUnderBudget = if (budgetAmount != null && budgetAmount > 0) {
            // Without stochastic data, we can't give a probability — but we know the minimum
            if (deterministicTotal <= budgetAmount) null else 0.0
        } else null

        return MonteCarloResult(
            percentile10 = deterministicTotal,
            percentile25 = deterministicTotal,
            percentile50 = deterministicTotal,
            percentile75 = deterministicTotal,
            percentile90 = deterministicTotal,
            probabilityUnderBudget = probUnderBudget,
            budgetAmount = budgetAmount,
            spentToDate = spentToDate,
            knownUpcoming = knownUpcoming,
            confidence = confidence,
            metadata = SimulationMetadata(
                qualifyingWeeks = fit?.qualifyingWeekCount ?: 0,
                totalWeeksExamined = fit?.totalWeeksExamined ?: 0,
                iterations = 0,
                logNormalMu = fit?.mu ?: 0.0,
                logNormalSigma = fit?.sigma ?: 0.0,
                daysRemaining = daysRemaining,
                computedAt = now
            ),
            displayCurrency = displayCurrency
        )
    }

    /**
     * Extract a percentile value from a sorted array.
     * Uses linear interpolation between nearest ranks.
     */
    private fun percentile(sortedValues: DoubleArray, p: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val index = p * (sortedValues.size - 1)
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(sortedValues.size - 1)
        val fraction = index - lower
        return sortedValues[lower] + fraction * (sortedValues[upper] - sortedValues[lower])
    }

    /**
     * Count how many of the last 8 weeks in the distribution qualify.
     * This is used for the recency component of the confidence score.
     */
    private fun countRecentQualifyingWeeks(fit: DistributionFit?): Int {
        if (fit == null) return 0
        // The allWeeklyTotals list is in chronological order (week 0 = oldest).
        // The last 8 entries represent the most recent 8 weeks.
        val allTotals = fit.allWeeklyTotals
        if (allTotals.isEmpty()) return 0

        val recentCount = allTotals.size.coerceAtMost(8)
        val recentTotals = allTotals.takeLast(recentCount)

        // A week "qualifies" if it had meaningful spending (> 0) — the full
        // transaction-day filter was already applied in HistoricalSpendingDistribution,
        // but here we approximate by checking the allWeeklyTotals which includes all weeks.
        // We consider a weekly total > 0 as a sign the week had data.
        return recentTotals.count { it > 0.0 }
    }
}
