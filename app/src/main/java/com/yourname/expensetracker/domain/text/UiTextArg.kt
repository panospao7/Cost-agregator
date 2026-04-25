package com.yourname.expensetracker.domain.text

sealed interface UiTextArg {
    data class Money(
        val amount: Double,
        val currency: String? = null,
        val showCents: Boolean = true
    ) : UiTextArg

    data class Percent(val value: Double, val decimals: Int = 1) : UiTextArg

    data class DateMillis(
        val timestamp: Long,
        val pattern: String = "dd/MM/yyyy"
    ) : UiTextArg
}
