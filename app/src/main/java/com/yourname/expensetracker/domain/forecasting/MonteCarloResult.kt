package com.yourname.expensetracker.domain.forecasting

/**
 * Result of a Monte Carlo spending simulation for the remainder of the current month.
 *
 * The simulator combines:
 * - **Deterministic stage**: known remaining recurring/planned expenses (from SynthesisEngine)
 * - **Stochastic stage**: sampled discretionary spending using a log-normal fit on historical weekly totals
 *
 * Percentiles represent projected *total* month-end spending (actual-to-date + deterministic + stochastic).
 */
data class MonteCarloResult(
    /** Percentile bands for projected month-end total spending (in EUR). */
    val percentile10: Double,
    val percentile25: Double,
    val percentile50: Double,
    val percentile75: Double,
    val percentile90: Double,

    /** Probability (0.0 - 1.0) of finishing the month at or under the user's budget. */
    val probabilityUnderBudget: Double?,

    /** The monthly budget used for the probability calculation (null if no budget set). */
    val budgetAmount: Double?,

    /** Amount already spent this month (deterministic, known). */
    val spentToDate: Double,

    /** Known upcoming committed + likely expenses for the rest of the month. */
    val knownUpcoming: Double,

    /** Data quality / confidence assessment. */
    val confidence: SimulationConfidence,

    /** Metadata about the simulation run. */
    val metadata: SimulationMetadata,

    /** Currency in which all monetary values in this result are denominated. */
    val displayCurrency: String
)

/**
 * Confidence assessment of the Monte Carlo simulation.
 *
 * The score (0.0 - 1.0) communicates how much the user should trust the output,
 * based on data availability and quality.
 */
data class SimulationConfidence(
    /** Overall confidence score 0.0 - 1.0. */
    val score: Double,

    /** Human-readable confidence level. */
    val level: ConfidenceLevel,

    /** Short explanation of why confidence is at this level. */
    val reason: String
)

enum class ConfidenceLevel {
    /** >= 0.7: solid historical data, trustworthy output. */
    HIGH,
    /** 0.4 - 0.69: usable but treat with caution. */
    MODERATE,
    /** < 0.4: very sparse data, treat as rough estimate only. */
    LOW
}

/**
 * Metadata about a simulation run for diagnostics.
 */
data class SimulationMetadata(
    /** Number of qualifying historical weeks used to fit the distribution. */
    val qualifyingWeeks: Int,

    /** Total historical weeks examined (before filtering). */
    val totalWeeksExamined: Int,

    /** Number of Monte Carlo iterations run. */
    val iterations: Int,

    /** Log-normal mu parameter (mean of log-transformed weekly totals). */
    val logNormalMu: Double,

    /** Log-normal sigma parameter (std dev of log-transformed weekly totals). */
    val logNormalSigma: Double,

    /** Days remaining in the current month. */
    val daysRemaining: Int,

    /** Timestamp of when the simulation was computed. */
    val computedAt: Long
)
