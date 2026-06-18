package com.yourname.expensetracker.domain.ai.validation

/**
 * Shared validators for AI-generated output values.
 *
 * These functions provide a single source of truth for bounding and
 * sanitising AI-produced numeric and temporal values before they
 * are consumed by the rest of the system.
 */
object AiOutputValidators {

    /**
     * Bounds a confidence value to the [0, 1] range.
     * AI models may occasionally return confidence outside this range;
     * this function clamps the value to prevent downstream issues.
     */
    fun boundedConfidence(confidence: Float): Float = confidence.coerceIn(0f, 1f)

    /**
     * Returns `true` if [amount] is a finite positive number.
     * Use this to reject zero or negative amounts hallucinated by AI.
     */
    fun isPositiveAmount(amount: Double): Boolean = amount.isFinite() && amount > 0

    /**
     * Returns `true` if [epoch] is a plausible Unix epoch millisecond
     * timestamp — between epoch zero and one year from now.
     */
    fun isPlausibleEpochMillis(epoch: Long): Boolean =
        epoch in 0..(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
}
