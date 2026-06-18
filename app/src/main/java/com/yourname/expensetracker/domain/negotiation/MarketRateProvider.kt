package com.yourname.expensetracker.domain.negotiation

/**
 * W25: Provides market-rate data for bill negotiation comparisons.
 *
 * Used by [SmartBillNegotiationEngine] to compare a user's subscription price
 * against competitive market alternatives.
 */
interface MarketRateProvider {
    /**
     * Returns market-rate quotes for a given service type in a region/currency.
     *
     * @param serviceType E.g., "streaming", "cloud_storage", "gym", "insurance".
     * @param region ISO 3166-1 alpha-2 country code (e.g. "GR", "US").
     * @param currency ISO 4217 currency code (e.g. "EUR", "USD").
     * @return Market-rate result or empty quotes if no data available.
     */
    suspend fun getRates(
        serviceType: ServiceType,
        region: String,
        currency: String
    ): MarketRateResult
}

data class MarketRateResult(
    val quotes: List<MarketRateQuote>,
    val source: String,
    val lastUpdatedAt: Long
)

data class MarketRateQuote(
    val providerName: String,
    val averageMonthlyPrice: Double,
    val competitiveMonthlyPrice: Double,
    val bestMonthlyPrice: Double,
    val currency: String,
    val region: String,
    val confidence: MarketRateConfidence = MarketRateConfidence.MEDIUM
)

enum class MarketRateConfidence { LOW, MEDIUM, HIGH }

enum class ServiceType {
    STREAMING, CLOUD_STORAGE, GYM, INSURANCE,
    MUSIC, NEWS, PRODUCTIVITY, VPN, DELIVERY,
    INTERNET, MOBILE, ENERGY, WATER, OTHER
}
