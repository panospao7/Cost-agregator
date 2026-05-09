package com.yourname.expensetracker.data.negotiation

import com.yourname.expensetracker.domain.negotiation.MarketRateProvider
import com.yourname.expensetracker.domain.negotiation.MarketRateQuote
import com.yourname.expensetracker.domain.negotiation.MarketRateResult
import com.yourname.expensetracker.domain.negotiation.MarketRateConfidence
import com.yourname.expensetracker.domain.negotiation.ServiceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * W25: Static seed-data market-rate provider.
 *
 * Provides fixed reference rates for common subscription categories.
 * Metadata explicitly declares source = "static_seed" and low/medium confidence.
 * Callers should treat these as approximate benchmarks, not live market data.
 */
@Singleton
class StaticMarketRateProvider @Inject constructor() : MarketRateProvider {

    override suspend fun getRates(
        serviceType: ServiceType,
        region: String,
        currency: String
    ): MarketRateResult {
        val quotes = SEED_DATA
            .filter { serviceType == ServiceType.OTHER || it.key == serviceType }
            .flatMap { (_, quoteList) -> quoteList }
            .filter { it.currency == currency || currency == "EUR" }
            .map { it.copy(region = region) }

        return MarketRateResult(
            quotes = quotes,
            source = "static_seed",
            lastUpdatedAt = 0L // never updated — static
        )
    }

    private companion object {
        private val SEED_DATA: Map<ServiceType, List<MarketRateQuote>> = mapOf(
            ServiceType.STREAMING to listOf(
                MarketRateQuote("Netflix", 13.99, 8.99, 6.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Amazon Prime", 8.99, 5.99, 4.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Disney+", 11.99, 7.99, 6.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
            ),
            ServiceType.CLOUD_STORAGE to listOf(
                MarketRateQuote("Google One", 9.99, 4.99, 2.49, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Dropbox", 11.99, 7.99, 5.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("iCloud+", 9.99, 4.99, 2.99, "EUR", "GR", MarketRateConfidence.LOW),
            ),
            ServiceType.GYM to listOf(
                MarketRateQuote("Basic Gym", 29.99, 19.99, 14.99, "EUR", "GR", MarketRateConfidence.LOW),
                MarketRateQuote("Premium Gym", 49.99, 34.99, 29.99, "EUR", "GR", MarketRateConfidence.LOW),
            ),
            ServiceType.MUSIC to listOf(
                MarketRateQuote("Spotify", 10.99, 6.99, 5.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Apple Music", 10.99, 6.99, 5.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
            ),
            ServiceType.DELIVERY to listOf(
                MarketRateQuote("Wolt+", 9.99, 6.99, 4.99, "EUR", "GR", MarketRateConfidence.LOW),
            ),
        )
    }
}
