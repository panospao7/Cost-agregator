package com.yourname.expensetracker.domain.core.money

/**
 * Result of converting a monetary amount from one currency to another.
 *
 * Unlike [MoneyAmount], this preserves the original amount/currency AND the
 * converted result. It also captures failure information when a conversion
 * cannot be completed.
 *
 * Identity conversions (same currency) are treated as successful — they are
 * NOT failures.
 */
data class ConvertedMoney(
    val amount: Double = 0.0,
    val currency: CurrencyCode,
    val originalAmount: Double = amount,
    val originalCurrency: CurrencyCode = currency,
    val isSuccess: Boolean = true,
    val isExactIdentity: Boolean = originalCurrency == currency,
    val failureReason: FailureReason? = null,
    val failureMessage: String? = null
) {

    companion object {
        /** Create a successful conversion result. */
        fun success(
            amount: Double,
            currency: CurrencyCode,
            originalAmount: Double = amount,
            originalCurrency: CurrencyCode = currency
        ): ConvertedMoney = ConvertedMoney(
            amount = amount,
            currency = currency,
            originalAmount = originalAmount,
            originalCurrency = originalCurrency,
            isSuccess = true,
            isExactIdentity = originalCurrency == currency
        )

        /**
         * Create a failed conversion result with a preserved [FailureReason].
         *
         * Unlike the previous implementation which discarded the [reason], this
         * version stores it so callers can inspect why the conversion failed.
         */
        fun failed(
            amount: Double,
            currency: CurrencyCode,
            reason: FailureReason,
            message: String? = null
        ): ConvertedMoney = ConvertedMoney(
            amount = 0.0,
            currency = currency,
            isSuccess = false,
            failureReason = reason,
            failureMessage = message
        )

        /**
         * Create a same-currency identity "conversion".
         *
         * Identity conversions are always successful — they are NOT treated as
         * failures, unlike the previous `isFailed` logic which incorrectly
         * flagged SAME_CURRENCY as failed.
         */
        fun identity(
            amount: Double,
            currency: CurrencyCode
        ): ConvertedMoney = ConvertedMoney(
            amount = amount,
            currency = currency,
            isSuccess = true,
            isExactIdentity = true
        )
    }
}

/**
 * Status of a currency conversion attempt.
 */
enum class ConversionStatus(val serializedName: String) {

    /** Conversion succeeded using an available exchange rate. */
    SUCCESS("SUCCESS"),

    /** No exchange rate was available for the requested pair. */
    FAILED_MISSING_RATE("FAILED_MISSING_RATE"),

    /** Source and target currency are the same — no conversion needed. */
    SAME_CURRENCY("SAME_CURRENCY"),

    /** Conversion used a rate from a different date than the transaction date (approximate). */
    APPROXIMATE_RATE("APPROXIMATE_RATE"),

    /** Legacy data that was never converted — currency assumption only. */
    LEGACY_NOT_CONVERTED("LEGACY_NOT_CONVERTED");

    /** Whether this status represents a successful/usable conversion. */
    val isSuccess: Boolean
        get() = this == SUCCESS || this == SAME_CURRENCY

    /** Whether this status indicates the result is approximate or uncertain. */
    val isApproximate: Boolean
        get() = this == APPROXIMATE_RATE || this == LEGACY_NOT_CONVERTED

    companion object {
        fun fromSerializedName(name: String): ConversionStatus =
            values().find { it.serializedName == name } ?: FAILED_MISSING_RATE
    }
}
