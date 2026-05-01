package com.yourname.expensetracker.domain.currency

import kotlinx.coroutines.flow.Flow

/**
 * Domain representation of an exchange rate.
 */
data class DomainExchangeRate(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: Double,
    val lastUpdated: Long,
    val source: String = "manual"
)

/**
 * Domain port for exchange-rate persistence.
 */
interface ExchangeRateStore {
    suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate?
    suspend fun insertOrUpdate(rate: DomainExchangeRate)
    suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>)
    fun getAllRatesForBase(baseCurrency: String): Flow<List<DomainExchangeRate>>
    suspend fun getLatestRate(): DomainExchangeRate?
    suspend fun deleteOldRates(olderThan: Long)
}
