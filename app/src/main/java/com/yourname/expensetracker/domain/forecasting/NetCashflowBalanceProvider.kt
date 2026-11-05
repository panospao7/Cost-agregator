package com.yourname.expensetracker.domain.forecasting

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback [AccountBalanceProvider] that estimates balance from 90-day net cashflow:
 * deposits minus purchases minus withdrawals (cash leaving the account).
 *
 * P-06-4: the estimate is computed in the home currency only. It returns null (unavailable,
 * never 0) when the requested currency differs from the home currency, when the home currency
 * cannot be resolved, when any read fails, or when any amount could not be converted.
 * It is also null (never a clamped 0.0) when the net is negative, or when the window has no
 * deposit, purchase or withdrawal row at all: the net is not a balance in those cases.
 * Transfers are neutral and are not counted (DR-06).
 */
@Singleton
class NetCashflowBalanceProvider @Inject constructor(
    private val multiCurrencyRepository: MultiCurrencyRepository,
    private val expenseRepository: ExpenseRepository,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val timeProvider: TimeProvider
) : AccountBalanceProvider {

    override suspend fun currentBalance(currency: String): Double? {
        val homeCurrency = CancellationSafe.runCatchingCancellable {
            currencySettingsRepository.resolveHomeCurrency()
        }.getOrNull()?.currencyOrNull?.code
        if (homeCurrency == null) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=HOME_CURRENCY_UNRESOLVED")
            return null
        }
        if (!homeCurrency.equals(currency, ignoreCase = true)) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=CURRENCY_NOT_HOME")
            return null
        }

        val now = timeProvider.now()
        val ninetyDaysAgo = now - 90 * TimePeriodUtils.DAY_IN_MILLIS

        val deposits = CancellationSafe.runCatchingCancellable {
            multiCurrencyRepository.getHomeCurrencyDepositTotal(ninetyDaysAgo, now)
        }.getOrNull()
        val purchases = CancellationSafe.runCatchingCancellable {
            multiCurrencyRepository.getHomeCurrencyPurchaseTotal(ninetyDaysAgo, now)
        }.getOrNull()
        if (deposits == null || purchases == null || deposits.isPartial || purchases.isPartial) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=TOTALS_UNAVAILABLE")
            return null
        }

        val withdrawals = withdrawalTotalOrNull(ninetyDaysAgo, now, homeCurrency)
        if (withdrawals == null) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=WITHDRAWALS_UNAVAILABLE")
            return null
        }

        // P-06-4: with no deposit, purchase or withdrawal row in the window there is no cashflow
        // to read, so the net is not a balance.
        if (deposits.totalTransactionCount + purchases.totalTransactionCount + withdrawals.second == 0) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=NO_CASHFLOW_ROWS")
            return null
        }
        val net = deposits.displayAmount - purchases.displayAmount - withdrawals.first
        // P-06-4: a negative net is spending that exceeds the deposits seen, not a balance of 0;
        // a clamped 0.0 would read as a real zero balance and show a crunch at every horizon.
        if (net < 0.0) {
            Timber.w("NET_CASHFLOW_BALANCE_UNAVAILABLE reason=NET_NEGATIVE")
            return null
        }
        return net
    }

    /**
     * Home-currency withdrawal total and row count, or null when the read fails or any row
     * cannot be converted.
     */
    private suspend fun withdrawalTotalOrNull(
        startDate: Long,
        endDate: Long,
        homeCurrency: String
    ): Pair<Double, Int>? {
        val normalized = CancellationSafe.runCatchingCancellable {
            analyticsCurrencyNormalizer.normalizeExpenses(
                // Only WITHDRAWAL rows may affect this total. Filter before normalizing so an
                // unconvertible row of another type (for example a transfer with no rate) cannot
                // exclude itself and withhold the forecast.
                expenseRepository.getExpensesBetween(startDate, endDate)
                    .filter { it.transactionType == TransactionType.WITHDRAWAL && !it.isNotMine },
                homeCurrency
            )
        }.getOrNull() ?: return null
        if (normalized.severeWarnings.isNotEmpty() || normalized.excludedCount > 0) return null
        val rows = normalized.includedExpenses
            .filter { it.transactionType == DomainTransactionType.WITHDRAWAL && !it.isNotMine }
        return rows.sumOf { it.effectiveAmount } to rows.size
    }
}
