package com.yourname.expensetracker.domain.currency

/**
 * How a currency was resolved from notification text.
 * P2-10: Used by the future NotificationMoneySignalDetector.
 */
enum class CurrencyResolution {
    /** Explicit ISO code found (e.g. "EUR", "PLN"). */
    EXPLICIT_ISO_CODE,
    /** Unambiguous symbol found (e.g. "€", "£", "₺"). */
    EXPLICIT_UNAMBIGUOUS_SYMBOL,
    /** Ambiguous symbol resolved by user's home currency. */
    AMBIGUOUS_SYMBOL_RESOLVED_BY_HOME,
    /** Ambiguous symbol that could not be resolved. */
    AMBIGUOUS_UNRESOLVED,
    /** User's home currency used as fallback. */
    USER_HOME_CURRENCY,
    /** App default currency used as fallback. */
    APP_DEFAULT_CURRENCY,
    /** Currency could not be determined. */
    UNKNOWN
}

/**
 * Represents a detected monetary amount with its currency context.
 * P2-10: Produced by NotificationMoneySignalDetector.
 */
data class MoneySignal(
    val raw: String,
    val amount: Double,
    val currencyCode: String?,
    val currencyCandidates: Set<String>,
    val resolution: CurrencyResolution,
    val confidence: Float,
    val ambiguous: Boolean
)
