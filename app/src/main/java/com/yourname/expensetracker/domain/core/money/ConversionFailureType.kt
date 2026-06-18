package com.yourname.expensetracker.domain.core.money

/**
 * Why a currency conversion could not be completed.
 */
enum class ConversionFailureType {
    INVALID_SOURCE_CURRENCY,
    INVALID_TARGET_CURRENCY,
    MISSING_RATE,
    MISSING_HISTORICAL_RATE,
    STALE_RATE,
    UNSUPPORTED_PAIR,
    HOME_CURRENCY_UNAVAILABLE,
    RATE_SOURCE_UNTRUSTED,
    UNKNOWN
}
