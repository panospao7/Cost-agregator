package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExchangeRateDao
import com.yourname.expensetracker.data.database.entity.ExchangeRate
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyDataRepository @Inject constructor(
    private val exchangeRateDao: ExchangeRateDao
) {
    fun getAllRatesForBase(baseCurrency: String): Flow<List<ExchangeRate>> =
        exchangeRateDao.getAllRatesForBase(baseCurrency)
}
