package com.yourname.expensetracker.domain.model.dashboard

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount

/**
 * Dashboard-level expense representation.
 *
 * @param currency Currency code (e.g. "EUR", "USD"). Always populated by callers;
 *   the default "EUR" is a backward-compat placeholder — do not rely on it.
 */
data class DashboardExpense(
    val id: Long,
    val amount: Double,
    val effectiveAmount: Double,
    val merchant: String,
    val transactionType: DashboardTransactionType,
    val date: Long,
    val categoryId: Long?,
    val isNotMine: Boolean,
    val isManualEntry: Boolean,
    val currency: String = "EUR"
) {
    val moneyAmount: MoneyAmount get() = MoneyAmount(amount, CurrencyCode(currency))
    val moneyEffectiveAmount: MoneyAmount get() = MoneyAmount(effectiveAmount, CurrencyCode(currency))
}

enum class DashboardTransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}

data class DashboardCategory(
    val id: Long,
    val name: String,
    val icon: String,
    val color: String
)
