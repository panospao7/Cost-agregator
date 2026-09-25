package com.yourname.expensetracker.domain.core.money

/**
 * CURR-587-01: Typed result for money aggregation operations.
 *
 * Unavailable state is explicit — no fake/sentinel currency.
 * MoneyAggregate continues to mean "available aggregate with a valid display currency".
 */
sealed interface MoneyAggregateResult {
    data class Available(
        val aggregate: MoneyAggregate
    ) : MoneyAggregateResult

    data class Unavailable(
        val reason: String,
        val requestedRateBasis: RateBasis,
        val metadata: MoneyAggregateMetadata = MoneyAggregateMetadata(),
        val warningMessage: String = reason
    ) : MoneyAggregateResult
}

enum class MoneyDisplayUnavailableReasonCode {
    INVALID_TARGET_CURRENCY,
    INVALID_SOURCE_CURRENCY,
    INVALID_AMOUNT,
    INVALID_TRANSACTION_COUNTS,
    DISPLAY_CONVERSION_UNAVAILABLE,
    HOME_CURRENCY_UNAVAILABLE
}

class MoneyDisplayUnavailableException(
    val reasonCode: MoneyDisplayUnavailableReasonCode
) : IllegalStateException(reasonCode.name)

val MoneyAggregateResult.isAvailable: Boolean
    get() = this is MoneyAggregateResult.Available

val MoneyAggregateResult.isUnavailable: Boolean
    get() = this is MoneyAggregateResult.Unavailable

fun MoneyAggregateResult.aggregateOrNull(): MoneyAggregate? =
    (this as? MoneyAggregateResult.Available)?.aggregate

fun MoneyAggregateResult.warningOrNull(): String? = when (this) {
    is MoneyAggregateResult.Available -> aggregate.warningMessage
    is MoneyAggregateResult.Unavailable -> warningMessage
}
