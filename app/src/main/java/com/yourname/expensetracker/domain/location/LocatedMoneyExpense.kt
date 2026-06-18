package com.yourname.expensetracker.domain.location

/**
 * Located expense with normalized currency for multi-currency safety.
 * PR-E6: Replaces raw Double amounts in heatmap/insight engines.
 */
data class LocatedMoneyExpense(
    val expenseId: Long,
    val latitude: Double,
    val longitude: Double,
    val normalizedAmount: Double?,  // converted to home currency, null if conversion failed
    val normalizedCurrency: String,  // home currency
    val originalAmount: Double,      // raw effectiveAmount
    val originalCurrency: String,    // expense's currency
    val conversionStatus: ConversionStatus,
    val merchant: String,
    val date: Long,
    val resolvedAddress: String? = null  // PR3: resolved address from geocoding backfill
) {
    /** Returns the normalized amount only if conversion succeeded and amount is finite. */
    val normalizedAmountOrNull: Double?
        get() = normalizedAmount
            ?.takeIf { it.isFinite() }
            ?.takeIf {
                conversionStatus == ConversionStatus.HOME_CURRENCY ||
                conversionStatus == ConversionStatus.CONVERTED
            }
}

enum class ConversionStatus {
    HOME_CURRENCY,    // no conversion needed
    CONVERTED,        // successfully converted
    FAILED            // conversion failed, exclude from normalized totals
}
