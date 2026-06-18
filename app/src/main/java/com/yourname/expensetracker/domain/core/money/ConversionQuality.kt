package com.yourname.expensetracker.domain.core.money

/**
 * Quality classification of a currency-converted aggregate.
 */
enum class ConversionQuality {
    /** All rows converted successfully with fresh rates. */
    COMPLETE,
    /** Some rows excluded due to missing/stale rates. */
    PARTIAL,
    /** No conversion possible — aggregate is unavailable. */
    UNAVAILABLE,
    /** Rates used are estimates (e.g., period midpoint). */
    ESTIMATED,
    /** Different rows used different rate bases. */
    MIXED_BASIS
}
