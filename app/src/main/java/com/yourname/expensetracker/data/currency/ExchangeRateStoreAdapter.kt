package com.yourname.expensetracker.data.currency

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
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
    private val exchangeRateDao: ExchangeRateDao,
    private val writeBarrier: DatabaseWriteBarrier
) : ExchangeRateStore {

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        return exchangeRateDao.getRate(fromCurrency, toCurrency)?.toDomain()
    }

    override suspend fun getLatestRateForPair(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        return exchangeRateDao.getLatestRateForPair(fromCurrency, toCurrency)?.toDomain()
    }

    override suspend fun getRateAsOf(fromCurrency: String, toCurrency: String, atMillis: Long): DomainExchangeRate? {
        return exchangeRateDao.getRateAsOf(fromCurrency, toCurrency, atMillis)?.toDomain()
    }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdate")
        exchangeRateDao.insertOrUpdate(rate.toEntity())
    }

    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.insertOrUpdateAll")
        exchangeRateDao.insertOrUpdateAll(rates.map { it.toEntity() })
    }

    override fun getRatesToCurrency(targetCurrency: String): Flow<List<DomainExchangeRate>> {
        return exchangeRateDao.getRatesToCurrency(targetCurrency).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getLatestRate(): DomainExchangeRate? {
        return exchangeRateDao.getLatestRate()?.toDomain()
    }

    override suspend fun deleteOldRates(olderThan: Long) {
        writeBarrier.checkWritesAllowed("ExchangeRateStoreAdapter.deleteOldRates")
        exchangeRateDao.deleteOldRates(olderThan)
    }
}

private fun ExchangeRate.toDomain(): DomainExchangeRate {
    return DomainExchangeRate(
        fromCurrency = fromCurrency,
        toCurrency = toCurrency,
        rate = rate,
        lastUpdated = lastUpdated,
        source = source,
        validDate = validDate.takeIf { it != 0L }
    )
}

private fun DomainExchangeRate.toEntity(): ExchangeRate {
    // CURR-70F-04: Reject undated rates at storage boundary
    require(validDate != null && validDate > 0L) {
        "Exchange rate $fromCurrency->$toCurrency must have a non-zero validDate (got $validDate)"
    }
    return ExchangeRate(
        fromCurrency = fromCurrency,
        toCurrency = toCurrency,
        rate = rate,
        lastUpdated = lastUpdated,
        source = source,
        validDate = validDate
    )
}
