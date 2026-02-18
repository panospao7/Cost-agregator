package com.yourname.expensetracker.domain.util

object AmountUtils {
    fun parseAmount(amountStr: String): Double? {
        return amountStr
            .replace(",", ".")
            .replace(Regex("""[^0-9.]"""), "")
            .toDoubleOrNull()
    }

    fun isValidAmount(amount: Double, max: Double = 1_000_000.0): Boolean {
        return amount > 0 && amount <= max
    }

    fun parseEuropeanAmount(amountStr: String): Double? {
        return amountStr
            .replace(".", "")
            .replace(",", ".")
            .toDoubleOrNull()
    }

    fun formatAmount(amount: Double, currency: String = "€"): String {
        return "$currency%.2f".format(amount)
    }
}
