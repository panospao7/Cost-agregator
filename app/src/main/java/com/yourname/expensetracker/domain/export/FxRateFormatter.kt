package com.yourname.expensetracker.domain.export

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * RP-19 (19-C): dedicated FX-rate serializer for exports.
 *
 * This is NOT a money formatter and must never be used for monetary amounts:
 * money keeps its own formatting/rounding semantics (`CurrencyFormatter`).
 * Exchange rates previously went through either `CurrencyFormatter.formatForExport`
 * (silently truncating rates like 0.912346 down to "0.91") or raw `Double.toString`
 * (which can emit scientific notation such as `1.0E-7` into CSV/JSON).
 *
 * Contract:
 * - Up to six fractional digits, rounded HALF_UP on the decimal string form.
 * - Never scientific notation ([BigDecimal.toPlainString]).
 * - Always `Locale.US`-style: period decimal separator, no grouping.
 * - Non-finite rates are rejected with [IllegalArgumentException] so callers
 *   keep their existing `INVALID` fallback handling.
 */
object FxRateFormatter {

    /** Maximum fractional digits exported for an exchange rate. */
    const val MAX_RATE_SCALE: Int = 6

    fun formatRate(rate: Double): String {
        require(rate.isFinite()) { "Cannot export non-finite exchange rate" }
        // Build from Double.toString (shortest roundtrip decimal) rather than
        // the binary double, so HALF_UP rounds the decimal value users see.
        return BigDecimal(rate.toString())
            .setScale(MAX_RATE_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }
}
