package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

data class SpendingSummary(
    val totalSpent: Double,
    val previousTotalSpent: Double?,
    val changePercent: Double?,
    val dailyHistory: List<Double>,
    val previousDailyHistory: List<Double>,
    val transactionCount: Int,
    val currency: String = "EUR"
) {
    val moneyTotalSpent: MoneyAmount get() = MoneyAmount(totalSpent, CurrencyCode(currency))
}
