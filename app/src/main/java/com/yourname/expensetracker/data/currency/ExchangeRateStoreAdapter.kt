package com.yourname.expensetracker.data.currency

import com.yourname.expensetracker.data.database.dao.ExchangeRateDao
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExchangeRateStoreAdapter @Inject constructor(
    private val exchangeRateDao: ExchangeRateDao
) : ExchangeRateStore {

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        return exchangeRateDao.getRate(fromCurrency, toCurrency)?.toDomain()
    }

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
        return exchangeRateDao.getRateAsOf(fromCurrency, toCurrency, atMillis)?.toDomain()
    }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        exchangeRateDao.insertOrUpdate(rate.toEntity())
    }

    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        exchangeRateDao.insertOrUpdateAll(rates.map { it.toEntity() })
    }

    override fun getAllRatesForBase(baseCurrency: String): Flow<List<DomainExchangeRate>> {
        return exchangeRateDao.getAllRatesForBase(baseCurrency).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getLatestRate(): DomainExchangeRate? {
        return exchangeRateDao.getLatestRate()?.toDomain()
    }

    override suspend fun deleteOldRates(olderThan: Long) {
        exchangeRateDao.deleteOldRates(olderThan)
    }
}

private fun ExchangeRate.toDomain(): DomainExchangeRate {
    return DomainExchangeRate(
        fromCurrency = fromCurrency,
        toCurrency = toCurrency,
        rate = rate,
        lastUpdated = lastUpdated,
        source = source
    )
}

private fun DomainExchangeRate.toEntity(): ExchangeRate {
    return ExchangeRate(
        fromCurrency = fromCurrency,
        toCurrency = toCurrency,
        rate = rate,
        lastUpdated = lastUpdated,
        source = source
    )
}
