package com.yourname.expensetracker.domain.core.money

/**
 * Result of converting a [MoneyAmount] from one currency to another.
 *
 * Unlike [MoneyAmount], this preserves the original amount/currency AND the
 * converted result, plus the exchange rate metadata used for the conversion.
 *
 * When conversion fails (rate unavailable), [convertedAmount] is null and
 * [conversionStatus] indicates why.
 */
data class ConvertedMoney(
    val original: MoneyAmount,
    val convertedAmount: Double?,
    val convertedCurrency: CurrencyCode,
    val rateUsed: Double?,
    val rateTimestamp: Long?,
    val conversionStatus: ConversionStatus
) {

    /** The converted result as a MoneyAmount, or null if conversion failed. */
    val converted: MoneyAmount?
        get() = convertedAmount?.let { MoneyAmount(it, convertedCurrency) }

    /** Whether the conversion succeeded. */
    val isConverted: Boolean
        get() = conversionStatus == ConversionStatus.SUCCESS

    /** Whether the conversion failed. */
    // TODO (M01): Fix isFailed to not treat SAME_CURRENCY (identity) as a failure.
    // Add isExactSuccess/isUsable flags. Identity conversion is not a failure.
    val isFailed: Boolean
        get() = conversionStatus != ConversionStatus.SUCCESS

    companion object {
        /** Create a successful conversion result. */
        fun success(
            original: MoneyAmount,
            convertedAmount: Double,
            convertedCurrency: CurrencyCode,
            rateUsed: Double,
            rateTimestamp: Long?
        ): ConvertedMoney = ConvertedMoney(
            original = original,
            convertedAmount = convertedAmount,
            convertedCurrency = convertedCurrency,
            rateUsed = rateUsed,
            rateTimestamp = rateTimestamp,
            conversionStatus = ConversionStatus.SUCCESS
        )

        /** Create a failed conversion result (no rate available). */
        // TODO (M08): Add failureReason and failureMessage fields to ConvertedMoney.
        // Preserve the `reason` parameter in the result instead of discarding it.
        fun failed(
            original: MoneyAmount,
            targetCurrency: CurrencyCode,
            reason: String
        ): ConvertedMoney = ConvertedMoney(
            original = original,
            convertedAmount = null,
            convertedCurrency = targetCurrency,
            rateUsed = null,
            rateTimestamp = null,
            conversionStatus = ConversionStatus.FAILED_MISSING_RATE
        )

        /** Create a same-currency "conversion" (identity, no rate needed). */
        fun identity(original: MoneyAmount): ConvertedMoney = ConvertedMoney(
            original = original,
            convertedAmount = original.amount,
            convertedCurrency = original.currency,
            rateUsed = 1.0,
            rateTimestamp = null,
            conversionStatus = ConversionStatus.SAME_CURRENCY
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
