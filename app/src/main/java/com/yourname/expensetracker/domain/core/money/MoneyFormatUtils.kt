package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.domain.util.CurrencyFormatter

/**
 * Format extensions for [MoneyAmount] using [CurrencyFormatter].
 */

fun MoneyAmount.formatMoney(showCents: Boolean = true): String =
    CurrencyFormatter.formatMoney(amount, currency.code, showCents)

fun MoneyAmount.formatMoneyCompact(): String =
    CurrencyFormatter.formatMoneyCompact(amount, currency.code)

fun MoneyAmount.formatMoneyWithSign(): String =
    CurrencyFormatter.formatMoneyWithSign(amount, currency.code)
