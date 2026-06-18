package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

/**
 * Spending summary for the dashboard.
 *
 * @param currency Currency code (e.g. "EUR", "USD"). The default "EUR" is a
 *   backward-compat placeholder — production callers should always pass the
 *   user's home currency from [CurrencySettingsRepository.homeCurrency].
 */
data class SpendingSummary(
    val totalSpent: Double,
    val previousTotalSpent: Double?,
    val changePercent: Double?,
    val dailyHistory: List<Double>,
    val previousDailyHistory: List<Double>,
    val transactionCount: Int,
    val currency: String = "EUR",
    val isPartial: Boolean = false,
    val warningMessage: String? = null
) {
    val moneyTotalSpent: MoneyAmount get() = MoneyAmount(totalSpent, CurrencyCode(currency))
}
