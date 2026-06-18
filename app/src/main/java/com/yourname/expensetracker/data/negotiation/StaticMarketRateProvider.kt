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
            .filter { it.key == serviceType }
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
            ServiceType.INTERNET to listOf(
                MarketRateQuote("Cosmote Fiber", 34.99, 24.99, 19.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Vodafone Fiber", 32.99, 22.99, 18.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Nova Fiber", 29.99, 19.99, 15.99, "EUR", "GR", MarketRateConfidence.LOW),
            ),
            ServiceType.MOBILE to listOf(
                MarketRateQuote("Cosmote Mobile", 24.99, 14.99, 9.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Vodafone Mobile", 22.99, 12.99, 8.99, "EUR", "GR", MarketRateConfidence.MEDIUM),
                MarketRateQuote("Wind Mobile", 19.99, 11.99, 7.99, "EUR", "GR", MarketRateConfidence.LOW),
            ),
            ServiceType.ENERGY to listOf(
                MarketRateQuote("DEI", 0.18, 0.14, 0.10, "EUR", "GR", MarketRateConfidence.LOW),
                MarketRateQuote("Elpedison", 0.17, 0.13, 0.09, "EUR", "GR", MarketRateConfidence.LOW),
                MarketRateQuote("Heron", 0.16, 0.12, 0.08, "EUR", "GR", MarketRateConfidence.LOW),
            ),
            ServiceType.WATER to listOf(
                MarketRateQuote("EYDAP", 15.99, 11.99, 8.99, "EUR", "GR", MarketRateConfidence.LOW),
            ),
        )
    }
}
