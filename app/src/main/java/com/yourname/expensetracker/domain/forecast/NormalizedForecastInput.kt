package com.yourname.expensetracker.domain.forecast

import com.yourname.expensetracker.domain.analytics.NormalizedExpense
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.usecase.dashboard.CurrencyDataQuality

/**
 * CURR-587-06: Normalized forecast input — replaces raw ExpenseSnapshot for forecast/runway synthesis.
 *
 * All monetary values are already normalized to [homeCurrency] at [RateBasis.TRANSACTION_DATE].
 * Synthesis must use [NormalizedExpense.normalizedEffectiveAmount] only.
 */
data class NormalizedForecastInput(
    val actualExpenses: List<NormalizedExpense>,
    val homeCurrency: CurrencyCode,
    val currencyQuality: CurrencyDataQuality
)
