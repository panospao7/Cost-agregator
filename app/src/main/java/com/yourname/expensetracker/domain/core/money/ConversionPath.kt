package com.yourname.expensetracker.domain.core.money

/**
 * How a conversion was routed between source and target currency.
 */
enum class ConversionPath {
    /** Same currency — rate is 1.0. */
    IDENTITY,
    /** Direct rate found for the pair. */
    DIRECT,
    /** Routed via an intermediate base currency (e.g., EUR). */
    VIA_BASE_CURRENCY
}
