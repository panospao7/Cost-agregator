package com.yourname.expensetracker.domain.core.money

/**
 * Why a particular currency was assigned to an entity or amount.
 *
 * This exists because the app has historical data where currency was not stored,
 * and we need to distinguish "the user explicitly chose EUR" from "the app defaulted
 * to EUR because older versions didn't have a currency field."
 *
 * Rule: Unknown currency must never silently become EUR.
 * This enum makes the assumption visible.
 */
enum class CurrencyAssumption(val serializedName: String) {

    /** Currency is completely unknown — no basis for assignment. */
    UNKNOWN("UNKNOWN"),

    /** We assumed the user's home currency because the source didn't specify one. */
    ASSUMED_HOME_CURRENCY("ASSUMED_HOME_CURRENCY"),

    /** Legacy data: older app versions defaulted to EUR without storing currency. */
    ASSUMED_LEGACY_EUR("ASSUMED_LEGACY_EUR"),

    /** The user explicitly confirmed or selected this currency. */
    USER_CONFIRMED("USER_CONFIRMED"),

    /** Currency was parsed from a data source (receipt, bank statement, notification). */
    PARSED_FROM_SOURCE("PARSED_FROM_SOURCE");

    /** Whether this assumption indicates the currency might be wrong. */
    val isUncertain: Boolean
        get() = this == UNKNOWN || this == ASSUMED_HOME_CURRENCY || this == ASSUMED_LEGACY_EUR

    companion object {
        /** Deserialize from stored string, defaulting to UNKNOWN for unrecognized values. */
        fun fromSerializedName(name: String): CurrencyAssumption =
            values().find { it.serializedName == name } ?: UNKNOWN
    }
}
