package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.model.ExpenseSnapshot

internal object SingleCurrencyAnalyticsInput {
    fun currencyOf(expenses: List<ExpenseSnapshot>): String {
        val currency = expenses.map { CurrencyCode.parse(it.currency) }.distinct().singleOrNull()
            ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY)
        return currency.code
    }

    fun from(expenses: List<ExpenseSnapshot>, displayCurrency: String): NormalizedAnalyticsInput {
        val target = CurrencyCode.parse(displayCurrency)
            ?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
            ?: throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY)
        if (expenses.any { CurrencyCode.parse(it.currency) != target }) {
            throw MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY)
        }
        return NormalizedAnalyticsInput(
            homeCurrency = target.code,
            includedExpenses = expenses.map { expense ->
                NormalizedExpense(
                    id = expense.id,
                    originalAmount = expense.amount,
                    originalEffectiveAmount = expense.effectiveAmount,
                    originalCurrency = expense.currency,
                    normalizedAmount = expense.effectiveAmount,
                    normalizedCurrency = target.code,
                    date = expense.date,
                    merchant = expense.merchant,
                    merchantKey = expense.merchantKey,
                    categoryId = expense.categoryId,
                    categoryNameSnapshot = null,
                    transactionType = expense.transactionType.name,
                    isNotMine = expense.isNotMine,
                    isSharedExpense = false,
                    ownershipMode = null,
                    source = null
                )
            }
        )
    }
}
