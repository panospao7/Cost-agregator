package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback [AccountBalanceProvider] that estimates balance from 90-day net cashflow.
 */
@Singleton
class NetCashflowBalanceProvider @Inject constructor(
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val timeProvider: TimeProvider
) : AccountBalanceProvider {

    override suspend fun currentBalance(currency: String): Double? {
        val now = timeProvider.now()
        val ninetyDaysAgo = now - 90 * TimePeriodUtils.DAY_IN_MILLIS
        val deposits = runCatching {
            multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now).displayAmount
        }.getOrDefault(0.0)
        val expenses = runCatching {
            multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now).displayAmount
        }.getOrDefault(0.0)
        return (deposits - expenses).coerceAtLeast(0.0)
    }
}
