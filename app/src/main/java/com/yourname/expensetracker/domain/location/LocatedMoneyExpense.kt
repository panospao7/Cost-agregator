package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.domain.core.money.ConversionQuality
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAggregate
import com.yourname.expensetracker.domain.core.money.MoneyAggregateBuilder
import com.yourname.expensetracker.domain.core.money.MoneyAggregateResult
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyQuality
import com.yourname.expensetracker.domain.core.money.NormalizedMoneyRow
import com.yourname.expensetracker.domain.core.money.RateBasis

/**
 * S19-4: aggregates already-normalized located expenses without any conversion.
 *
 * Rows whose normalized currency equals [homeCurrency] are summed with
 * [RateBasis.IDENTITY]. Rows in any other currency, and rows without a usable
 * normalized amount, are excluded and the result is marked partial (or
 * unavailable when nothing is left). Home-currency sums are unchanged.
 *
 * The aggregate rate basis is IDENTITY only when no included row was converted
 * upstream; otherwise it carries the upstream [LocatedMoneyExpense.rateBasis].
 *
 * @param additionalExcludedCount rows dropped by the caller before aggregation
 *   (for example FAILED conversions); they count as excluded so the result is partial.
 */
internal fun List<LocatedMoneyExpense>.toHomeCurrencyAggregate(
    homeCurrency: String,
    additionalExcludedCount: Int = 0
): MoneyAggregate {
    val home = CurrencyCode.parse(homeCurrency)?.takeIf { it in CurrencyCode.ALL_SUPPORTED }
        ?: CurrencyCode.EUR
    val rows = ArrayList<NormalizedMoneyRow>(size)
    var excluded = additionalExcludedCount.coerceAtLeast(0)
    var basis = RateBasis.IDENTITY
    for (expense in this) {
        val amount = expense.normalizedAmountOrNull
        val rowCurrency = CurrencyCode.parse(expense.normalizedCurrency)
        if (amount == null || rowCurrency != home) {
            excluded++
        } else {
            rows.add(NormalizedMoneyRow(amount = amount, normalizedCurrency = home))
            if (basis == RateBasis.IDENTITY && expense.conversionStatus == ConversionStatus.CONVERTED) {
                basis = expense.rateBasis
            }
        }
    }
    val quality = NormalizedMoneyQuality(
        excludedTransactionCount = excluded,
        conversionQuality = if (excluded > 0) ConversionQuality.PARTIAL else ConversionQuality.COMPLETE
    )
    return when (
        val result = MoneyAggregateBuilder.fromNormalizedRows(
            rows, home, quality, requestedRateBasis = basis, actualRateBasis = basis
        )
    ) {
        is MoneyAggregateResult.Available -> result.aggregate
        is MoneyAggregateResult.Unavailable -> MoneyAggregate.empty(home, RateBasis.IDENTITY).copy(
            isPartial = true,
            conversionQuality = ConversionQuality.UNAVAILABLE,
            warningMessage = result.warningMessage,
            metadata = result.metadata
        )
    }
}

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
    val resolvedAddress: String? = null,  // PR3: resolved address from geocoding backfill
    /** Basis of the upstream conversion; IDENTITY unless [conversionStatus] is CONVERTED. */
    val rateBasis: RateBasis = RateBasis.IDENTITY
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
