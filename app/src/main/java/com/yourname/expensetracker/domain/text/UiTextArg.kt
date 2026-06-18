package com.yourname.expensetracker.domain.text

sealed interface UiTextArg {
    data class Money(
        val amount: Double,
        val currency: String = "EUR",
        val currencyAssumption: String = "LEGACY_DEFAULT",
        val showCents: Boolean = true
    ) : UiTextArg

    data class Percent(val value: Double, val decimals: Int = 1) : UiTextArg

    data class DateMillis(
        val timestamp: Long,
        val pattern: String = "dd/MM/yyyy"
    ) : UiTextArg
}
