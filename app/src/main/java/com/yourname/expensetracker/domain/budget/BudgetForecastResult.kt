package com.yourname.expensetracker.domain.budget

import com.yourname.expensetracker.data.database.entity.BudgetForecast

/**
 * CURR-587-03: Typed result for budget forecast operations.
 *
 * Unavailable state is explicit — no normal-looking BudgetForecast with fake/placeholder currency.
 */
sealed interface BudgetForecastResult {
    data class Available(val forecast: BudgetForecast) : BudgetForecastResult
    data class Unavailable(
        val budgetId: Long,
        val reasonCode: ForecastUnavailableReason,
        val reason: String,
        val createdAt: Long
    ) : BudgetForecastResult
}

enum class ForecastUnavailableReason {
    HOME_CURRENCY_UNAVAILABLE,
    LIMIT_CONVERSION_FAILED,
    MISSING_RATE,
    INVALID_CURRENCY,
    UNKNOWN
}

/** Thrown by legacy [BudgetForecastingEngine.generateForecast] when forecast is unavailable. */
class BudgetForecastUnavailableException(
    val reasonCode: ForecastUnavailableReason,
    override val message: String
) : IllegalStateException(message)

val BudgetForecastResult.canFormatMoney: Boolean
    get() = this is BudgetForecastResult.Available
