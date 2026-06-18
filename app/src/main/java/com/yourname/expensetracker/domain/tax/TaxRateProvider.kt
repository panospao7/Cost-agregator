package com.yourname.expensetracker.domain.tax

/**
 * T04-FIXED: VAT rate provider interface.
 * Currently used only for VAT rate lookup in TaxEstimator.estimateTaxes().
 * Income tax brackets still come from TaxConfiguration — this provider
 * does not (yet) cover full income tax rate tables.
 */
interface TaxRateProvider {
    suspend fun getRate(
        country: String,
        region: String? = null
    ): TaxRateResult

    /** Metadata about this provider's source and confidence. */
    val metadata: TaxRateMetadata
}

data class TaxRateResult(
    val standardVatRate: Double,
    val reducedVatRates: List<Double> = emptyList(),
    val currency: String,
    val country: String,
    val region: String? = null
)

data class TaxRateMetadata(
    val source: String,
    val confidence: TaxRateConfidence,
    val lastUpdatedAt: Long = 0L,
    val isDemo: Boolean = false
)

enum class TaxRateConfidence { LOW, MEDIUM, HIGH, OFFICIAL }
