package com.yourname.expensetracker.domain.forecasting

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assesses the quality/reliability of a Monte Carlo simulation based on available data.
 *
 * Factors considered:
 * 1. **Volume**: Number of qualifying weeks (more = better)
 * 2. **Density**: Ratio of qualifying weeks to total weeks examined (higher = fewer gaps)
 * 3. **Distribution fitness**: Whether the log-normal fit is usable (sigma > 0)
 * 4. **Recency**: Whether recent weeks are well-represented (not just old data)
 *
 * The score is always shown to the user — we never hide uncertainty.
 */
@Singleton
class DataQualityAssessor @Inject constructor() {

    companion object {
        /** Ideal number of qualifying weeks for full confidence. */
        private const val IDEAL_QUALIFYING_WEEKS = 40

        /** Minimum qualifying weeks to produce any result. */
        private const val MIN_QUALIFYING_WEEKS = 4

        /** Weight for each scoring component. */
        private const val WEIGHT_VOLUME = 0.40
        private const val WEIGHT_DENSITY = 0.25
        private const val WEIGHT_FITNESS = 0.20
        private const val WEIGHT_RECENCY = 0.15
    }

    /**
     * Computes a [SimulationConfidence] from a distribution fit.
     *
     * @param fit The log-normal distribution fit from historical data.
     *            Pass null if the distribution couldn't be computed at all.
     * @param recentWeeksQualifying Number of qualifying weeks in the last 8 weeks
     *                              (for recency scoring). Pass 0 if unknown.
     */
    fun assess(
        fit: DistributionFit?,
        recentWeeksQualifying: Int = 0
    ): SimulationConfidence {
        if (fit == null) {
            return SimulationConfidence(
                score = 0.0,
                level = ConfidenceLevel.LOW,
                reason = "No historical spending data available"
            )
        }

        if (fit.qualifyingWeekCount < MIN_QUALIFYING_WEEKS) {
            val score = (fit.qualifyingWeekCount.toDouble() / MIN_QUALIFYING_WEEKS) * 0.3
            return SimulationConfidence(
                score = score.coerceIn(0.0, 0.3),
                level = ConfidenceLevel.LOW,
                reason = "Only ${fit.qualifyingWeekCount} weeks of data (need $MIN_QUALIFYING_WEEKS+)"
            )
        }

        // 1. Volume score: how many qualifying weeks relative to ideal
        val volumeScore = (fit.qualifyingWeekCount.toDouble() / IDEAL_QUALIFYING_WEEKS)
            .coerceIn(0.0, 1.0)

        // 2. Density score: qualifying / total examined (1.0 = no gaps)
        val densityScore = if (fit.totalWeeksExamined > 0) {
            (fit.qualifyingWeekCount.toDouble() / fit.totalWeeksExamined).coerceIn(0.0, 1.0)
        } else 0.0

        // 3. Fitness score: is the distribution usable?
        val fitnessScore = when {
            !fit.isUsable -> 0.0
            fit.sigma < 0.1 -> 0.6  // Very low variance — suspicious
            fit.sigma > 2.0 -> 0.5  // Very high variance — data is noisy
            else -> 1.0             // Reasonable distribution shape
        }

        // 4. Recency score: are recent weeks well-represented?
        // Expect at least 4 out of 8 recent weeks to qualify
        val recencyScore = (recentWeeksQualifying.toDouble() / 4.0).coerceIn(0.0, 1.0)

        // Weighted sum
        val totalScore = (
            volumeScore * WEIGHT_VOLUME +
            densityScore * WEIGHT_DENSITY +
            fitnessScore * WEIGHT_FITNESS +
            recencyScore * WEIGHT_RECENCY
        ).coerceIn(0.0, 1.0)

        val level = when {
            totalScore >= 0.7 -> ConfidenceLevel.HIGH
            totalScore >= 0.4 -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.LOW
        }

        val reason = buildReason(fit, volumeScore, densityScore, recencyScore, level)

        return SimulationConfidence(
            score = totalScore,
            level = level,
            reason = reason
        )
    }

    private fun buildReason(
        fit: DistributionFit,
        volumeScore: Double,
        densityScore: Double,
        recencyScore: Double,
        level: ConfidenceLevel
    ): String {
        return when (level) {
            ConfidenceLevel.HIGH -> {
                "Based on ${fit.qualifyingWeekCount} weeks of spending data"
            }
            ConfidenceLevel.MODERATE -> {
                val issues = mutableListOf<String>()
                if (volumeScore < 0.5) issues.add("limited history (${fit.qualifyingWeekCount} weeks)")
                if (densityScore < 0.5) issues.add("gaps in data")
                if (recencyScore < 0.5) issues.add("sparse recent data")
                if (issues.isEmpty()) "Based on ${fit.qualifyingWeekCount} weeks of data"
                else "Estimate has ${issues.joinToString(", ")}"
            }
            ConfidenceLevel.LOW -> {
                "Rough estimate — only ${fit.qualifyingWeekCount} weeks of usable data"
            }
        }
    }
}
