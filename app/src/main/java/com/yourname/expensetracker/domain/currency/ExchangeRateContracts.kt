package com.yourname.expensetracker.domain.currency

import kotlinx.coroutines.flow.Flow

/**
 * Domain representation of an exchange rate.
 *
 * @property validDate The date (epoch ms) for which this rate is valid, or null
 *           if unknown. When rates are fetched from a daily feed (e.g. ECB),
 *           this should be set to the feed date. When null, callers should treat
 *           [lastUpdated] as the best approximation.
 */
data class DomainExchangeRate(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: Double,
    val lastUpdated: Long,
    val source: String = "manual",
    val validDate: Long? = null
)

/**
 * Domain port for exchange-rate persistence.
 */
interface ExchangeRateStore {
    suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate?
    suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate?
    suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate?
    suspend fun insertOrUpdate(rate: DomainExchangeRate)
    suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>)
    /**
     * Returns all exchange rates that target the given [targetCurrency].
     * CURR-10: Renamed from getAllRatesForBase for clarity since the query
     * filters on `toCurrency`, not `fromCurrency`.
     */
    fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>>
    suspend fun getLatestRate(): DomainExchangeRate?
    suspend fun deleteOldRates(olderThan: Long)
}
