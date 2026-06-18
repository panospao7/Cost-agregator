package com.yourname.expensetracker.domain.core.money

/**
 * A per-currency subtotal bucket.
 *
 * Used when expenses are grouped by currency before conversion.
 * For example, if a user has spent 50 EUR and 100 USD, these
 * would be two buckets:
 *   - MoneyBucket(currency=EUR, amount=50.0)
 *   - MoneyBucket(currency=USD, amount=100.0)
 *
 * Buckets are the intermediate step before conversion into a single
 * display currency.
 */
data class MoneyBucket(
    val currency: CurrencyCode,
    val amount: Double,
    val transactionCount: Int = 0
) {

    init {
        require(amount.isFinite()) { "amount must be finite" }
    }

    /** Format as a display string with currency symbol. */
    fun formatDisplay(): String = MoneyAmount(amount, currency).formatDisplay()

    companion object {
        /** Create a zero bucket for a currency. */
        fun zero(currency: CurrencyCode): MoneyBucket = MoneyBucket(currency, 0.0, 0)
    }
}
