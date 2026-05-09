package com.yourname.expensetracker.domain.tax

/**
 * T08-FIXED: Provides tax-rate data for TaxEstimator.
 * Separates the rate source from the estimation logic.
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
