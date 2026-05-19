package com.yourname.expensetracker.domain.core.money

/**
 * Declares which exchange-rate temporal basis was used (or requested) for a conversion.
 */
enum class RateBasis {
    /** Source and target currency are the same — no conversion needed. */
    IDENTITY,
    /** Most recent available rate regardless of transaction date. */
    LATEST_AVAILABLE,
    /** Rate valid on the transaction's date. */
    TRANSACTION_DATE,
    /** Rate valid at the start of the reporting period. */
    PERIOD_START,
    /** Rate valid at the end of the reporting period. */
    PERIOD_END,
    /** Midpoint estimate for the period (approximate). */
    PERIOD_MIDPOINT_ESTIMATE,
    /** Rate projected for a future forecast date. */
    FORECAST_DATE,
    /** Manually locked rate (e.g., budget limit set by user). */
    MANUAL_LOCKED
}
