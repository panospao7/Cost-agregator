package com.yourname.expensetracker.domain.core.money

/**
 * Result of normalizing a single item — either included (converted) or excluded (failed).
 */
sealed interface NormalizationResult<out T> {
    data class Included<T>(val value: T) : NormalizationResult<T>
    data class Excluded(
        val sourceEntityId: Long?,
        val failure: ConversionFailure
    ) : NormalizationResult<Nothing>
}
